(ns clj-statsd-svr
  "statsd protocol server"
  (:use [clojure.string :only [replace] :rename {replace re-replace}]) 
  (:import [java.net DatagramPacket DatagramSocket])
  (:import [java.util.concurrent Executors LinkedBlockingQueue TimeUnit]))

;configuration
(def port 8125)
(def flushInterval 10000)
(def backends [ #(println %) ])

(def statistics (agent { :counters {} :timers {} :gauges {} }))

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

(defn receive [socket]
  (let [packet (DatagramPacket. (byte-array 1024) 1024)]
    (.receive socket packet)
    (String. (.getData packet) 0 (.getLength packet) "UTF-8")))

(defn start-receiver [port-no queue]
  (let [socket (DatagramSocket. port-no)]
    (.start (Thread. #(while true (doseq [data (.split #"\n" (receive socket))]
                                    (.put queue data)))))))

(defn decode [data]
  (if-let [[_ bucket value type sample-rate] (re-matches #"(.+):(\d+)\|(c|ms|g)(?:(?<=c)\|@(\d+(?:\.\d+)?))?" data)]
    (let [nicebucket (re-replace (re-replace (re-replace bucket #"\s+" "_") #"/" "-") #"[^a-zA-Z_\-0-9\.]" "")
          nicevalue (/ (Double/parseDouble value) (Double/parseDouble (or sample-rate "1")))
          nicetype ({"c" :counters "ms" :timers "g" :gauges} type)]
      {:bucket nicebucket :value nicevalue :type nicetype })
    (println "bad data:" data)))

(defn handle [{type :type value :value bucket :bucket}]
  (send statistics update-stat-val type bucket value))

(defn new-worker [queue]
  #(while true (when-let [record (decode (.take queue))] (handle record))))

(defn report []
  (let [snapshot (ref {})]
    (send statistics flush-stats snapshot)
    (await statistics)
    (assoc @snapshot :timestamp (System/currentTimeMillis))))

(defn distribute [report]
  (doseq [backend backends] (future (backend report))))

(defn start []
  (let [worker-count 2
        work-queue (LinkedBlockingQueue.)
        work-executor (Executors/newFixedThreadPool worker-count)
        report-executor (Executors/newSingleThreadScheduledExecutor)]
    (start-receiver port work-queue)
    (dotimes [_ worker-count] (.submit work-executor (new-worker work-queue)))
    (.scheduleAtFixedRate report-executor #(distribute (report)) flushInterval flushInterval TimeUnit/MILLISECONDS)))
