(ns backends.console)

(defn status []
  "console.status: OK")

(defn publish [report config]
  (println report))