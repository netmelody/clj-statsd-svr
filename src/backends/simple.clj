(ns backends.simple)

(defn status []
  "OK")

(defn publish [report config]
  (println report))