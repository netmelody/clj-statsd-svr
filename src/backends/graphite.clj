(ns backends.graphite)

(defn publish [{timestamp :timestamp counters :counters timers :timers gauges :gauges} config]
  (let [epoch (/ timestamp 1000)]
    (println "graphite epoch " epoch)))

(defn graphite [host port]
  (let [socket (java.net.Socket. host port)
        writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream socket)))]
    (println "boo")
    (.close socket)))
