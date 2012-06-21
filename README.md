clj-statsd-svr
==============

A [StatsD][statsd] implementation in [Clojure][clojure] that runs on the JVM.

StatsD is a network daemon that listens for statistics, like counters and timers,
sent over [UDP][udp] and sends aggregates to one or more pluggable backend
services (e.g. [Graphite][graphite]).

This implementation is heavily influenced by the ([Etsy][etsy]) StatsD
implementation for [Node.js][node].

Concepts
--------

* *buckets*
  Each stat is in its own "bucket". They are not predefined anywhere. Buckets can be named anything that will translate to Graphite (periods make folders, etc)

* *values*
  Each stat will have a value. How it is interpreted depends on modifiers. In
general values should be integer.

* *flush*
  After the flush interval timeout (default 10 seconds), stats are
  aggregated and sent to an upstream backend service.

Counting
--------

    gorets:1|c

This is a simple counter. Add 1 to the "gorets" bucket. It stays in memory until the flush interval config `:flush-interval`.

Timing
------

    glork:320|ms

The glork took 320ms to complete this time. StatsD figures out 90th percentile,
average (mean), lower and upper bounds for the flush interval.  The percentile
threshold can be tweaked with config `:percent-threshold`.

The percentile threshold can be a single value, or a list of values, and will
generate the following list of stats for each threshold:

    stats.timers.$KEY.mean_$PCT stats.timers.$KEY.upper_$PCT

Where `$KEY` is the key you stats key you specify when sending to StatsD, and
`$PCT` is the percentile threshold.

Sampling
--------

    gorets:1|c|@0.1

Tells StatsD that this counter is being sent sampled every 1/10th of the time.

Gauges
------
StatsD now also supports gauges, arbitrary values, which can be recorded.

    gaugor:333|g

Supported Backends
------------------

StatsD supports multiple, pluggable, backend modules that can publish
statistics from the local StatsD daemon to a backend service or data
store. Backend services can retain statistics for longer durations in
a time series data store, visualize statistics in graphs or tables,
or generate alerts based on defined thresholds.

StatsD includes the following backends:

* [Graphite][graphite] (`graphite`): Graphite is an open-source
  time-series data store that provides visualization through a
  web-browser interface.
* Console (`console`): The console backend outputs the received
  metrics to stdout (e.g. for seeing what's going on during development).

By default, the `graphite` backend will be loaded automatically. To
select which backends are loaded, set the `:backends` configuration
variable to the list of backend modules to load.

Backends are just Clojure namespaces which implement the functions described in
section *Backend Interface*. In order to be able to load the backend, add the
namespace symbol name into the `:backends` variable in your config and ensure
the relevant files are on the classpath.

Graphite Schema
---------------

Graphite uses "schemas" to define the different round robin datasets it houses (analogous to RRAs in rrdtool). Here's what Etsy is using for the stats databases:

    [stats]
    priority = 110
    pattern = ^stats\..*
    retentions = 10:2160,60:10080,600:262974

That translates to:

* 6 hours of 10 second data (what we consider "near-realtime")
* 1 week of 1 minute data
* 5 years of 10 minute data

This has been a good tradeoff so far between size-of-file (round robin databases are fixed size) and data we care about. Each "stats" database is about 3.2 megs with these retentions.

TCP Stats Interface
-------------------

A really simple TCP management interface is available by default on port 8126 or overriden in the configuration file. Inspired by the memcache stats approach this can be used to monitor a live statsd server.  You can interact with the management server by telnetting to port 8126, the following commands are available:

* help - lists the available commands
* quit - quits the management session
* stats - some stats about the running server
* counters - a dump of all the current counters
* timers - a dump of the current timers
* gauges - a dump of the current gauges

The stats output currently will give you:

* uptime: the number of seconds elapsed since statsd started
* messages.last_msg_seen: the number of elapsed seconds since statsd received a message
* messages.bad_lines_seen: the number of bad lines seen since startup

Each backend will also publish a set of statistics, prefixed by its
module name.

Graphite:

* graphite.last_flush: the number of seconds elapsed since the last successful flush to graphite
* graphite.last_exception: the number of seconds elapsed since the last exception thrown whilst flushing to graphite

Installation and Configuration
------------------------------

 * Download the standalone jar
 * Create a config file from `exampleConfig.clj` and put it somewhere
 * Start the Daemon:

    java -jar clj-statsd-svr-1.0.0-standalone.jar /path/to/config.clj

For more information, check the `exampleConfig.clj`.

Backend Interface
-----------------

Backend modules are Clojure namespaces that define a number of functions
that are called by StatsD. Each backend module should implement the following
functions:

* (defn publish [report config] ...)

  Called on each flush interval so that backends can push aggregate
  metrics to their respective backend services. The event is passed
  two parameters: `report` is a map representing the StatsD statistics:

  ```
metrics: {
    counters: counters,
    gauges: gauges,
    timers: timers,
    pctThreshold: pctThreshold
}
  ```
  and `config` is a map containing the StatsD configuration.

* `(defn status [] ...)`

  Called when a user invokes a *stats* command on the management
  server port. It allows each backend module to dump backend-specific
  status statistics to the management port. The backend module should
  return a string when this function is invoked.


[clojure]: http://clojure.org
[graphite]: http://graphite.wikidot.com
[etsy]: http://www.etsy.com
[statsd]: https://github.com/etsy/statsd
[node]: http://nodejs.org
[udp]: http://en.wikipedia.org/wiki/User_Datagram_Protocol


License
-------

Copyright (C) 2012 Tom Denley

Distributed under the Eclipse Public License, the same as Clojure.
