(ns backends.graphite)

(defn status []
  "graphite.status: OK")

(defn prepend [prefix s]
  (if (clojure.string/blank? prefix) s (str prefix "." s)))

(defn format-us [f & args]
  (String/format java.util.Locale/US f (to-array args)))

(defn- fmt [v]
  (cond (float? v)   (format-us "%2.2f" v)
        (integer? v) (format-us "%d" v)
        :else        (format-us "%s" v)))

(defn ->datapoint [prefix [name val]]
  (prepend prefix (str name " " (fmt val))))

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
    (let [epoch (quot (:timestamp report) 1000)
          datapoints (flatten (concat (map (partial ->datapoint (::prefix-counters config ""))     (:counters report))
                                      (map (partial ->datapoint (::prefix-timers config "timers")) (:timers report))
                                      (map (partial ->datapoint (::prefix-gauges config "gauges")) (:gauges report))))]
      (graphite config epoch datapoints))
    (catch Exception e
      (.printStackTrace e))))
