(ns backends.simple)

(defn status []
  "simple.status: OK")

(defn publish [report config]
  (println report))