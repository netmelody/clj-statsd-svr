{:port 8125
 :mgmt-port 8126
 :flush-interval 10000
 :backends '[backends.console
             backends.graphite]
 :backends.graphite/host "localhost"
 :backends.graphite/port 2003
 :backends.graphite/prefix          "stats"
 :backends.graphite/prefix-counters ""
 :backends.graphite/prefix-timers   "timers"
 :backends.graphite/prefix-gauges   "gauges"}
