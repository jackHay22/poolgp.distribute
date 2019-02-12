(ns poolgp.distribute
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io])
  (:import [java.net ServerSocket SocketException Socket])
  (:import java.io.PrintWriter)
  (:gen-class))

(def CURRENT-CYCLE (atom 0))
(def INCOMING-WORKER-STARTED? (atom false))
(def OPP-POOL-WORKER-STARTED? (atom false))
(def DIST-SERVER-STARTED? (atom false))

(def COMPLETED-CHAN (async/chan 1000))
(def PENDING-DIST-CHAN (async/chan 1000))

(defn- log
  [msg]
  (println "poolgp.distribute =>" msg))

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
    (log (str "Attempting to reach eval host " hostname ":" port))
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

(defn- distribution-worker
  "take a socket and an individual and send"
  [host port]
  (reset! DIST-SERVER-STARTED? true)
  (async/go-loop []
    (let [indiv (async/<! PENDING-DIST-CHAN)
          client-socket (Socket. host port)]
      (with-open [writer (io/writer client-socket)]
        (.write writer (str (pr-str (make-indiv-packet indiv)) "\n"))
        (.flush writer)
        (.close client-socket)
        ))))

(defn- opp-pool-worker
  "start a thread that servers opponent pool requests"
  [port opponents]
  ;TODO purge after cycle
  (reset! OPP-POOL-WORKER-STARTED? true)
  (println "Setting opp pool of size: " (count opponents))
  (future
    (let [socket (ServerSocket. port)
          outgoing-ops (map make-opp-packet opponents (range))]
      (.setSoTimeout socket 0)
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

(defn- unpack-indiv
  "take individuals returned from poolgp workers and strips off extra
  information (returns clojush.individual)"
  [poolgp-indiv]
  ;TODO: aggregate fitness, etc...
    (:indiv poolgp-indiv))

(defn- incoming-socket-worker
  "start a listener for completed individuals"
  [port]
  (reset! INCOMING-WORKER-STARTED? true)
  (let [socket (ServerSocket. port)]
    (.setSoTimeout socket 0)
    (async/go-loop []
      (let [client-socket (.accept socket)
            ind (try
                  (.readLine (io/reader client-socket))
                (catch SocketException e
                  nil))]
          (if (not (= ind nil))
            (async/>! COMPLETED-CHAN (unpack-indiv ind)))
          (recur)))))

(defn- verify-indiv
  [indiv]
  )

(defn- get-return-indiv
  []
  (async/go (async/<!! COMPLETED-CHAN)))

(defn eval-indiv
  "take individual, opponent list,
  server config, evaluate,
  return individual list
  IN: (list clojush.indvidual)
  OUT: (list clojush.individual)
  "
  [indiv opponents config]
    ;wait for connectivity
    ; (check-worker-status (:host config) (:outgoing-port config))
    ; (log "Connected to eval worker")
    (async/go (async/>! PENDING-DIST-CHAN indiv))
    ;set infinite timeout


    ;worker that responds to opp pool requests
    (if (not @OPP-POOL-WORKER-STARTED?)
      (opp-pool-worker (:opp-pool-req-p config) opponents))

    ;server worker that sends individuals out that arrive on outgoing channel
    (if (not @DIST-SERVER-STARTED?)
      (distribution-worker (:host config) (:outgoing-port config)))

    ;task that listens for incoming individuals
    (if (not @INCOMING-WORKER-STARTED?)
      (incoming-socket-worker (:incoming-port config)))

    ;take an individual from the completed channel
    (get-return-indiv)
    (println "HERE"))
