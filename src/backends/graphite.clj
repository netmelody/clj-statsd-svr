(ns backends.graphite)

(defn publish [{timestamp :timestamp counters :counters timers :timers gauges :gauges}]
  (let [epoch (/ timestamp 1000)]
    (println "graphite epoch " epoch)))