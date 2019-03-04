# poolgp.distribute
[![Clojars Project](https://img.shields.io/clojars/v/poolgp.distribute.svg)](https://clojars.org/poolgp.distribute)

A Clojure library for using a genetic programming system with the poolgp distributed evaluation system.

## Usage

Include this library in the dependencies for your project.

In your ns declaration: `(:require [poolgp.distribute :as poolgp])`

(Note: this should be in `clojush.src.pushgp.pushgp/compute-errors`)

```clojure

;wait for eval containers to start
(Thread/sleep 10000)

;start distribution worker, opp pool worker, and return worker (if not started)
(poolgp/start-dist-services {
  :incoming-port 8000
  :outgoing-port 9999
  :opp-pool-req-p 8888
  :host "eval"})

;register opponents for distribution
(poolgp/register-opponents (map deref pop-agents))

;send evaluation function to agents
(dorun (map #((if use-single-thread swap! send)
             %1 evaluate-individual poolgp/eval-indiv %2 argmap)
           pop-agents
           rand-gens))
```

## License

Copyright © 2019 Jack Hay

Distributed under the Eclipse Public License either version 1.0.
