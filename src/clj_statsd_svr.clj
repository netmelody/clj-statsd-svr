(ns clj-statsd-svr
  "statsd protocol server"
  (:import [java.net DatagramPacket DatagramSocket InetAddress]))

(def port 8125)

(defn receive [socket queue]
  (let [size 1024
        packet (DatagramPacket. (byte-array size) size)]
    (.receive socket packet)
    (.put queue (String. (.getData packet) 0 (.getLength packet) "UTF-8"))))

(defn listener [port-no queue]
  (let [socket (DatagramSocket. port-no)]
    (.start (Thread. #((receive socket queue) (recur))))))

(defn consumer [queue]
  (println (.take queue))
  (recur queue))

(let [work (java.util.concurrent.LinkedBlockingQueue.)]
  (listener port work)
  (consumer work))