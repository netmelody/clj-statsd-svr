(ns clj-statsd-svr
  "statsd protocol server"
  (:import [java.net DatagramPacket DatagramSocket]))

(def port 8125)

(defn receive [socket]
  (let [size 1024
        packet (DatagramPacket. (byte-array size) size)]
    (.receive socket packet)
    (String. (.getData packet) 0 (.getLength packet) "UTF-8")))

(defn start-receiver [port-no queue]
  (let [socket (DatagramSocket. port-no)]
    (.start (Thread. #(while true (.put queue (receive socket)))))
    socket))

(defn decode [data]
  (if-let [[_ bucket value type sample-rate] (re-matches #"(.+):(\d+)\|(c|ms|g)(?:(?<=c)\|@(\d+(?:\.\d+)?))?" data)]
    {:bucket bucket :value value :type type :sample-rate sample-rate}
    (println "bad data:" data)))

(defn handle [data]
  (process ()))

(defn new-worker [queue]
  #(while true (when-let [record (decode (.take queue))] (handle record))))

(defn start []
  (let [worker-count 2
        work-queue (java.util.concurrent.LinkedBlockingQueue.)
        executor (java.util.concurrent.Executors/newFixedThreadPool worker-count)]
    (start-receiver port work-queue)
    (doall (for [_ (range worker-count)] (.submit executor (new-worker work-queue))))))