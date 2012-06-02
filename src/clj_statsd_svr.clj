(ns clj-statsd-svr
  "statsd protocol server"
  (:import [java.net DatagramPacket DatagramSocket]))

(def port 8125)

(defn receive [socket]
  (let [size 1024
        packet (DatagramPacket. (byte-array size) size)]
    (.receive socket packet)
    (String. (.getData packet) 0 (.getLength packet) "UTF-8")))

(defn new-listener [port-no queue]
  (let [socket (DatagramSocket. port-no)]
    (.start (Thread. #(while true (.put queue (receive socket)))))
    socket))

(defn handle [data]
  (print data))

(defn new-worker [queue]
  #(while true (handle (.take queue))))

(defn start []
  (let [worker-count 2
        work-queue (java.util.concurrent.LinkedBlockingQueue.)
        executor (java.util.concurrent.Executors/newFixedThreadPool worker-count)]
    (new-listener port work-queue)
    (doall (for [_ (range worker-count)] (.submit executor (new-worker work-queue))))))