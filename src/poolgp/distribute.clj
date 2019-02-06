(ns poolgp.distribute
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io])
  (:import [java.net ServerSocket SocketException Socket])
  (:gen-class))

(def CURRENT-CYCLE (atom 0))

(def OUTGOING-CHAN (async/chan 500))

(def RUNNING? (atom true))

(defn- log
  [msg]
  (println "poolgp.distribute => " msg))

(defn- make-opp-packet
  "make a clojure map for distributing
  opponents to eval workers"
  [indiv id]
  {:indiv indiv ;clojush individual containing :program
   :cycle @CURRENT-CYCLE
   :type :opponent
   :eval-id id}) ;1->total

(defn- make-indiv-packet
  "make a clojure map for distributing
  individual to eval workers"
  [indiv]
  {:indiv indiv ;clojush individual containing :program
   :cycle @CURRENT-CYCLE
   :type :individual
   :eval-id (java.util.UUID/randomUUID)}) ;random

(defn- check-worker-status
  "attempt to reach a worker node (on startup)"
  [hostname port]
  (loop []
    (log (str "Attempting to reach eval host " hostname " on port " port))
    (let [status
      (try
        (Socket. hostname port)
        true
        (catch Exception e
          false))]
      (if (not status)
        (do
            (Thread/sleep 5000)
            (recur))))))

(defn- async-distribution-server
  "take a socket and an individual and send"
  [host port]
  (async/go-loop []
    (let [socket (Socket. host port)
          ind (async/<! OUTGOING-CHAN)
          writer (io/writer socket)]
      (do
        (.write writer (str (pr-str ind) "\n"))
        (.flush writer)
        (.close socket)))
    (if @RUNNING?
      (recur))))

(defn- opp-pool-worker
  "start a thread that servers opponent pool requests"
  [socket opponents]
  (future
    (let [outgoing-ops (map make-opp-packet opponents (range))]
      (loop []
        (let [client-socket (.accept socket)
              writer (io/writer client-socket)]
            (try
              (doall (map
                #(.write writer (str (pr-str %) "\n"))
                          outgoing-ops))
              (catch Exception e
                (.printStackTrace e)))
            (.flush writer)
            (.close client-socket))
      (recur)))))

(defn- distribution-task
  "start a distribution service
  that listens on outgoing channel"
  [indivs config]
  (async/go-loop [ind-rem indivs]
    (if (not (empty? ind-rem))
      (do
        (async/>! OUTGOING-CHAN (make-indiv-packet (take 1 ind-rem)))
        (recur (drop 1 ind-rem)))
      (swap! CURRENT-CYCLE inc))))

(defn- incoming-socket-worker
  "start a listener for completed individuals"
  [socket accepted]
  (loop [returned (list)]
    (if (> accepted (count returned))
        ;if not received "all" indivs
        (let [client-socket (.accept socket)
              ind (try
                    (.readLine (io/reader client-socket))
                  (catch SocketException e
                    nil))]
          (do
            (.close client-socket)
            (recur (if ind (conj returned ind) returned))))
        returned)))

(defn- unpack-indivs
  "take individuals returned from poolgp workers and strips off extra
  information (returns clojush.individual)"
  [poolgp-indivs]
  ;TODO: aggregate fitness, etc...
  (map :indiv poolgp-indivs))

(defn eval-indivs
  "take individual list,
  server config, evaluate,
  return individual list
  IN: (list clojush.indvidual)
  OUT: (list clojush.individual)
  "
  [indivs config]
  (let
    [incoming-socket (ServerSocket. (:incoming-port config))
     opp-pool-socket (ServerSocket. (:opp-pool-req-p config))]
    (do
      ;wait for connectivity
      (check-worker-status (:host config) (:outgoing-port config))
      ;set infinite timeout
      (.setSoTimeout incoming-socket 0)
      (.setSoTimeout opp-pool-socket 0)
      ;worker that responds to opp pool requests
      (opp-pool-worker opp-pool-socket indivs)
      ;server worker that sends individuals out that arrive on outgoing channel
      (async-distribution-server
        (:host config) (:outgoing-port config))
      ;task that prepares individuals and pushes to outgoing channel
      (distribution-task indivs config))
      ;task that listens for incoming individuals
      (unpack-indivs
        (incoming-socket-worker
            incoming-socket
            (int (* (count indivs) (:accepted-return config)))))))
