(ns clj-statsd-svr
  "statsd protocol server"
  (:import [java.net DatagramPacket DatagramSocket InetAddress]))

(def port 8125)
(def socket (DatagramSocket. port))
  
(defn receive
  (let [size 32
        data (byte-array size)
        packet (DatagramPacket. data size)]
  (.receive socket packet)
  (new String (.getData receive-packet) 0 (.getLength receive-packet))))