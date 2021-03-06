(ns backends.graphite)

(defn status []
  "graphite.status: OK")

(defn counter-to-str [[name count]]
  (str name " " count))

(defn gauge-to-str [[name value]]
  (str "gauges." name " " value))

(defn timer-to-str [[name timings]]
  (if (empty? timings)
    []
    (str "timers." name " " (first timings))))

(defn to-graphite-str [prefix datapoint epoch]
  (str prefix "." datapoint " " epoch "\n"))

(defn graphite [host port prefix epoch datapoints]
  (let [socket (java.net.Socket. host port)
        writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream socket)))]
    (doseq [datapoint datapoints] (.append writer (to-graphite-str prefix datapoint epoch)))
    (.close writer)))

(defn publish [{timestamp :timestamp counters :counters timers :timers gauges :gauges} config]
  (let [epoch (unchecked-divide-int timestamp 1000)
        datapoints (flatten (concat (map counter-to-str counters)
                                    (map timer-to-str timers)
                                    (map gauge-to-str gauges)))]
    (graphite "localhost" 8003 "stats" epoch datapoints)))
