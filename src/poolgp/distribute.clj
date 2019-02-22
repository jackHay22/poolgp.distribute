(ns poolgp.distribute
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io])
  (:import [java.net ServerSocket SocketException Socket])
  (:import java.io.PrintWriter)
  (:gen-class))

(def CURRENT-CYCLE (atom -1))
(def INCOMING-WORKER-STARTED? (atom false))
(def OPP-POOL-WORKER-STARTED? (atom false))
(def DIST-SERVER-STARTED? (atom false))

(def COMPLETED-CHAN (async/chan 1000))
(def PENDING-DIST-CHAN (async/chan 1000))

(def OPPONENT-POOL (atom nil))

(defn- log
  [msg]
  (println "poolgp.distribute =>" msg))

(defn- make-opp-packet
  "make a clojure map for distributing
  opponents to eval workers"
  [indiv id]
  {:indiv indiv ;clojush individual containing :program
   :cycle @CURRENT-CYCLE
   :eval-id id}) ;1->total

(defn- make-indiv-packet
  "make a clojure map for distributing
  individual to eval workers"
  [indiv]
  {:indiv indiv ;clojush individual containing :program
   :cycle @CURRENT-CYCLE
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
  (log "Starting distribution worker...")
  (async/go-loop []
    (let [indiv (async/<!! PENDING-DIST-CHAN)
          client-socket (Socket. host port)]
      (with-open [writer (io/writer client-socket)]
        (.write writer (str (pr-str (make-indiv-packet indiv)) "\n"))
        (.flush writer))
        (recur))))

(defn- opp-pool-worker
  "start a thread that servers opponent pool requests"
  [port]
  (reset! OPP-POOL-WORKER-STARTED? true)
  (log "Starting opponent pool worker...")
  (future
    (let [socket (ServerSocket. port)]
      (.setSoTimeout socket 0)
      (loop []
        (let [client-socket (.accept socket)
              writer (io/writer client-socket)]
            (try
              (doall (map
                #(.write writer (str (pr-str %) "\n"))
                      (map make-opp-packet @OPPONENT-POOL (range))))
              (catch Exception e
                (.printStackTrace e)))
            (.flush writer)
            (.close client-socket))
      (recur)))))

(defn- incoming-socket-worker
  "start a listener for completed individuals"
  [port]
  (reset! INCOMING-WORKER-STARTED? true)
  (log "Starting incoming socket worker...")
  (let [socket (ServerSocket. port)]
    (.setSoTimeout socket 0)
    (async/go-loop []
      (let [client-socket (.accept socket)
            ind (read-string (try
                  (.readLine (io/reader client-socket))
                (catch SocketException e
                  nil)))]
          (if (and (not (= ind nil)) (record? ind))
            (async/>! COMPLETED-CHAN ind)
            (log (str "ERROR: failed to ingest individual: " ind)))
          (recur)))))

(defn eval-indiv
  "take individual, opponent list,
  server config, evaluate,
  return individual list
  IN: (list clojush.indvidual)
  OUT: (list clojush.individual)
  "
  [indiv]
    ;wait for connectivity
    ; (check-worker-status (:host config) (:outgoing-port config))
    ; (log "Connected to eval worker")
    (if (and @OPP-POOL-WORKER-STARTED?
                  @DIST-SERVER-STARTED?
                  @INCOMING-WORKER-STARTED?)
        (async/go (async/>! PENDING-DIST-CHAN indiv))
        (log "Error: one or more services not started before trying to
              distribute individuals! (use `(start-dist-services config)`)"))

    ;take an individual from the completed channel
    ;(blocking wait)
    (async/<!! COMPLETED-CHAN))

(defn register-opponents
  "register opponents for a cycle"
  [opponents]
  (log "Registering opponent pool")
  (reset! OPPONENT-POOL opponents)
  (log "Updating cycle")
  (swap! CURRENT-CYCLE inc))

(defn start-dist-services
  "start core services (once)"
  [config]
  ;worker that responds to opp pool requests
  (if (not @OPP-POOL-WORKER-STARTED?)
    (opp-pool-worker (:opp-pool-req-p config))
    (log "Already started opponent pool worker"))

  ;server worker that sends individuals out that arrive on outgoing channel
  (if (not @DIST-SERVER-STARTED?)
    (distribution-worker (:host config) (:outgoing-port config))
    (log "Already started distribution server"))

  ;task that listens for incoming individuals
  (if (not @INCOMING-WORKER-STARTED?)
    (incoming-socket-worker (:incoming-port config))
    (log "Already started incoming server worker")))
