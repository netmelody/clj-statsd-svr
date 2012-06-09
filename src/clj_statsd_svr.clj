(ns clj-statsd-svr
  "statsd protocol server"
  (:use [clojure.string :only [replace] :rename {replace re-replace}]) 
  (:import [java.net DatagramPacket DatagramSocket])
  (:import [java.util.concurrent Executors LinkedBlockingQueue TimeUnit]))

;configuration
(def config {:port 8125
             :mgmt-port 8126
             :flush-interval 10000
             :backends '[backends.simple backends.graphite]})

;initialisation
(doseq [backend (config :backends)] (require backend))
(def statistics (agent { :counters {} :timers {} :gauges {} }))

;statistics
(defn update-stat [stats stat bucket f]
  (assoc stats stat (assoc (stats stat) bucket (f ((stats stat) bucket)))))

(defn update-stat-val [stats stat bucket value]
  (let [f ({:counters (fn [x] (+ value (or x 0)))
            :timers   (fn [x] (conj x value)) 
            :gauges   (fn [x] value)} stat)]
    (update-stat stats stat bucket f)))

(defn flush-stats [stats snapshot-ref]
  (dosync (ref-set snapshot-ref stats))
  { :counters {} :timers {} :gauges {} })

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
          nicevalue (/ (Double/parseDouble value) (Double/parseDouble (or sample-rate "1")))
          nicetype ({"c" :counters "ms" :timers "g" :gauges} type)]
      {:bucket nicebucket :value nicevalue :type nicetype })
    (println "bad data:" data)))

(defn handle [{type :type value :value bucket :bucket}]
  (send statistics update-stat-val type bucket value))

(defn new-worker [queue]
  #(while true (when-let [record (decode (.take queue))] (handle record))))

;reporting
(defn report []
  (let [snapshot (ref {})]
    (send statistics flush-stats snapshot)
    (await statistics)
    (assoc @snapshot :timestamp (System/currentTimeMillis))))

(defn distribute [report config]
  (doseq [backend (config :backends)] (future ((ns-resolve backend 'publish) report config))))

;manangement
(defn manage-via [socket]
  (let [in (.useDelimiter (java.util.Scanner. (.getInputStream socket)) #"[^\w\.\t]")
        out (java.io.PrintWriter. (.getOutputStream socket) true)
        done (atom false)]
    (def commands {"quit"     #(do (swap! done (fn [x] (not x))) "bye")
                   "help"     #(str "Commands: " (reduce (fn [x y] (str x ", " y)) (keys commands)))
                   "stats"    #(System/currentTimeMillis)
                   "counters" #(@statistics :counters)
                   "timers"   #(@statistics :timers)
                   "gauges"   #(@statistics :gauges)})
    (while (and (not @done) (.hasNext in))
      (when-let [response (commands (.trim (.next in)))]
        (.println out (str (response) "\n"))))
    (.close socket)))

(defn start-manager [port-no]
  (let [server (java.net.ServerSocket. port-no)]
    (.start (Thread. #(while true (let [socket (.accept server)] (future (manage-via socket))))))))

;lifecycle
(defn start []
  (let [worker-count 2
        work-queue (LinkedBlockingQueue.)
        work-executor (Executors/newFixedThreadPool worker-count)
        report-executor (Executors/newSingleThreadScheduledExecutor)]
    (start-receiver (config :port) work-queue)
    (dotimes [_ worker-count] (.submit work-executor (new-worker work-queue)))
    (.scheduleAtFixedRate report-executor #(distribute (report) config) (config :flush-interval) (config :flush-interval) TimeUnit/MILLISECONDS)))
