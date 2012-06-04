(ns backends.graphite)

(defn publish [{timestamp :timestamp flushInterval :flush-interval counters :counters timers :timers gauges :gauges}]
  (let [epoch (/ timestamp 1000)]
    (println "graphite epoch " epoch)))