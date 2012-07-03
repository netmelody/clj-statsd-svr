(ns backends.graphite)

(defn status []
  "graphite.status: OK")

(defn prepend [prefix s]
  (if (clojure.string/blank? prefix) s (str prefix "." s)))

(defn counter-to-str [config [name count]]
  (prepend (::prefix-counters config) (str name " " count)))

(defn gauge-to-str [config [name value]]
  (prepend (::prefix-gauges config "gauges") (str name " " value)))

(defn timer-to-str [config [name timings]]
  (if (empty? timings)
    []
    (prepend (::prefix-timers config "timers") (str name ".random " (first timings)))))

(defn to-graphite-str [config datapoint epoch]
  (prepend (::prefix config "stats") (str datapoint " " epoch "\n")))

(defn graphite [config epoch datapoints]
  (let [{host ::host, port ::port  :or  {host "localhost", port 2003}} config]
    (with-open [w (clojure.java.io/writer (java.net.Socket. host port))]
      (binding [*out* w] 
        (doseq [datapoint datapoints]
          (print (to-graphite-str config datapoint epoch)))))))

(defn publish [report config]
  (try
    (let [{timestamp :timestamp counters :counters timers :timers gauges :gauges} report
          epoch (quot timestamp 1000)
          datapoints (flatten (concat (map (partial counter-to-str config) counters)
                                      (map (partial timer-to-str config)   timers)
                                      (map (partial gauge-to-str config)   gauges)))]
      (graphite config epoch datapoints))
    (catch Exception e
      (.printStackTrace e))))
