(ns clj-statsd-svr
  "statsd protocol server"
  (:gen-class)
  (:use [clojure.string :only [replace] :rename {replace re-replace}])
  (:import [java.net DatagramPacket DatagramSocket])
  (:import [java.util.concurrent Executors LinkedBlockingQueue TimeUnit]))

;initialisation
(def statistics (agent { :counters {} :timers {} :gauges {} }))

;backends
(defn backend-send [backends f & args]
  (doall (for [backend backends] (future (apply (ns-resolve backend f) args)))))

;statistics
(defn update-stat [stats stat bucket f]
  (assoc stats stat (assoc (stats stat) bucket (f ((stats stat) bucket)))))

(defn update-stat-val [stats stat bucket value]
  (let [f (stat {:counters (fn [x] (+ value (or x 0)))
                 :timers   (fn [x] (conj (or x (sorted-set)) value)) 
                 :gauges   (fn [x] value)})]
    (update-stat stats stat bucket f)))

(defn reset-map [map originfunc]
  (into {} (for [[k v] map] [k (if (string? k) (originfunc v) v)])))

(defn report-counter [config [name val]]
  {name (* val (/ 1000.0 (:flush-interval config)))}) ; value per sec

(defn percentile [p vals]
  (take (long (* p (count vals))) vals))

(defn percentile->str
  "Formats 0.9 0.99 0.9999 to '90' '99' '9999' respectively"
  [p] 
  (second (re-matches #"0[\.,](\d\d[^0]*)0*" (format "%.10f" p))))

(defn report-timer
  ([[name vals]]
    (if (empty? vals)
      {}
      {(str name ".upper") (apply max vals)
       (str name ".lower") (apply min vals)
       (str name ".mean" ) (/ (double (apply + vals)) (count vals))}))
  ([p [name vals]]
    (let [vals   (percentile p vals)
          suffix (percentile->str p)]
      (if (empty? vals)
        {}
        {(str name ".upper_" suffix) (apply max vals)
         (str name ".mean_"  suffix) (/ (double (apply + vals)) (count vals))}))))

(defn report-all [stats config]
  (let [counters (map (partial report-counter config) (:counters stats))
        timers   (map report-timer (:timers stats))
        percentiles (for [p (:percentiles config)]
                          (map (partial report-timer p) (:timers stats)))]
    {:counters (apply merge counters)
     :timers (apply merge (flatten [timers percentiles]))
     :gauges (:gauges stats)}))

(defn flush-stats [stats config snapshot]
  (deliver snapshot (report-all stats config))
  {:counters (reset-map (:counters stats) (fn [x] 0))
   :timers   (reset-map (:timers stats) (fn [x] []))
   :gauges   {}})

;listening
(defn receive [socket]
  (let [packet (DatagramPacket. (byte-array 1024) 1024)]
    (.receive socket packet)
    (String. (.getData packet) 0 (.getLength packet) "UTF-8")))

(defn start-receiver [port-no queue]
  (let [socket (DatagramSocket. port-no)]
    (.start (Thread. #(while true (doseq [data (.split #"\n" (receive socket))]
                                    (.put queue data)))))))

;decoding
(defn decode [data]
  (if-let [[_ bucket value type sample-rate] (re-matches #"(.+):(\d+)\|(c|ms|g)(?:(?<=c)\|@(\d+(?:\.\d+)?))?" data)]
    (let [nicebucket (-> bucket (re-replace #"\s+" "_") (re-replace #"/" "-") (re-replace #"[^a-zA-Z_\-0-9\.]" "")) 
          nicevalue (Math/round (/ (Double/parseDouble value) (Double/parseDouble (or sample-rate "1"))))
          nicetype ({"c" :counters "ms" :timers "g" :gauges} type)]
      {:bucket nicebucket :value nicevalue :type nicetype })
    {:bucket :bad_lines_seen :value 1 :type :counters }))

(defn handle [{type :type value :value bucket :bucket}]
  (send statistics update-stat-val type bucket value)
  (send statistics update-stat-val :gauges :last_msg_seen (System/currentTimeMillis)))

(defn new-worker [queue]
  #(while true (when-let [record (decode (.take queue))] (handle record))))

;reporting
(defn make-report [config]
  (let [snapshot (promise)]
    (send statistics flush-stats config snapshot)
    (assoc @snapshot :timestamp (System/currentTimeMillis))))

(defn distribute [report config]
  (backend-send (config :backends) 'publish report config))

;manangement
(defn seconds-since [time-millis]
  (int (/ (- (System/currentTimeMillis) (or time-millis 0)) 1000)))

(defn vitals [backends startup-time-millis]
  (str "uptime: " (seconds-since startup-time-millis) "\r\n"
       "messages.bad_lines_seen: " (or (:bad_lines_seen (:counters @statistics)) 0) "\r\n"
       "messages.last_msg_seen: " (seconds-since (:last_msg_seen (:gauges @statistics))) "\r\n" 
       (reduce str (map #(str @% "\r\n") (backend-send backends 'status)))))

(defn manage-via [socket backends startup-time-millis]
  (let [in (.useDelimiter (java.util.Scanner. (.getInputStream socket)) #"[^\w\.\t]")
        out (java.io.PrintWriter. (.getOutputStream socket) true)
        done (atom false)]
    (def commands {"quit"     #(do (swap! done (fn [x] (not x))) "bye")
                   "help"     #(str "Commands: " (reduce (fn [x y] (str x ", " y)) (keys commands)))
                   "stats"    #(vitals backends startup-time-millis)
                   "counters" #(@statistics :counters)
                   "timers"   #(@statistics :timers)
                   "gauges"   #(@statistics :gauges)})
    (while (and (not @done) (.hasNext in))
      (when-let [response (commands (.trim (.next in)))]
        (.println out (str (response) "\n"))))
    (.close socket)))

(defn start-manager [port-no backends startup-time-millis]
  (let [server (java.net.ServerSocket. port-no)]
    (.start (Thread. #(while true (let [socket (.accept server)] (future (manage-via socket backends startup-time-millis))))))))

;lifecycle
(defn start [config]
  (let [worker-count 2
        work-queue (LinkedBlockingQueue.)
        work-executor (Executors/newFixedThreadPool worker-count)
        report-executor (Executors/newSingleThreadScheduledExecutor)]
    (start-receiver (config :port) work-queue)
    (dotimes [_ worker-count] (.submit work-executor (new-worker work-queue)))
    (start-manager (config :mgmt-port) (config :backends) (System/currentTimeMillis)) 
    (.scheduleAtFixedRate report-executor #(distribute (make-report config) config) (config :flush-interval) (config :flush-interval) TimeUnit/MILLISECONDS)))

;configuration
(def default-config {:port 8125
                     :mgmt-port 8126
                     :flush-interval 10000
                     :percentiles [0.9]
                     :backends '[backends.console]})

;main
(defn -main [& [config-file]]
  (if config-file (println "Loading config" config-file) (println "Using default config"))
  (let [config (merge default-config (if config-file (load-file config-file) {}))]
    (println "Backends:" (:backends config))
    (doseq [backend (config :backends)] (require backend))
    (start config)))
