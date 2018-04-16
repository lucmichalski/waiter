;;
;;       Copyright (c) 2017 Two Sigma Investments, LP.
;;       All Rights Reserved
;;
;;       THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF
;;       Two Sigma Investments, LP.
;;
;;       The copyright notice above does not evidence any
;;       actual or intended publication of such source code.
;;
(ns waiter.scheduler
  (:require [clj-time.core :as t]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metrics.counters :as counters]
            [metrics.meters :as meters]
            [metrics.timers :as timers]
            [plumbing.core :as pc]
            [qbits.jet.client.http :as http]
            [slingshot.slingshot :as ss]
            [waiter.async-utils :as au]
            [waiter.metrics :as metrics]
            [waiter.utils :as utils])
  (:import (clojure.lang PersistentQueue)
           (java.io EOFException)
           (java.net ConnectException SocketTimeoutException)
           (java.util.concurrent TimeoutException)
           (org.joda.time DateTime)))

(defrecord Service
  [^String id
   instances
   task-count
   task-stats])

(defn make-Service [value-map]
  (map->Service (merge {:task-stats {:running 0
                                     :healthy 0
                                     :unhealthy 0
                                     :staged 0}}
                       value-map)))

(defrecord ServiceInstance
  [^String id
   ^String service-id
   ^DateTime started-at
   healthy?
   health-check-status
   flags
   exit-code
   ^String host
   port
   extra-ports
   ^String protocol
   ^String log-directory
   ^String message])

(defn make-ServiceInstance [value-map]
  (map->ServiceInstance (merge {:extra-ports [] :flags #{}} value-map)))

(defprotocol ServiceScheduler

  (get-apps->instances [this]
    "Returns a map of scheduler/Service records -> scheduler/ServiceInstance records.")

  (get-apps [this]
    "Returns a list of scheduler/Service records")

  (get-instances [this ^String service-id]
    "Retrieve a {:active-instances [...]. :failed-instances [...]} map of scheduler/ServiceInstance records for the given service-id.
     The active-instances should not be assumed to be healthy (or live).
     The failed-instances are guaranteed to be dead.")

  (kill-instance [this instance]
    "Instructs the scheduler to kill a specific ServiceInstance.
     Returns a map containing the following structure:
     {:instance-id instance-id, :killed? <boolean>, :message <string>,  :service-id service-id, :status status-code}")

  (app-exists? [this ^String service-id]
    "Returns truth-y value if the app exists and nil otherwise.")

  (create-app-if-new [this service-id->password-fn descriptor]
    "Sends a call to Scheduler to start an app with the descriptor if the app does not already exist.
     Returns truth-y value if the app creation was successful and nil otherwise.")

  (delete-app [this ^String service-id]
    "Instructs the scheduler to delete the specified service.
     Returns a map containing the following structure:
     {:message message
      :result :deleted|:error|:no-such-service-exists
      :success true|false}")

  (scale-app [this ^String service-id target-instances]
    "Instructs the scheduler to scale up/down instances of the specified service to
    the specified number of instances.")

  (retrieve-directory-content [this ^String service-id ^String instance-id ^String host ^String directory]
    "Retrieves the content of the directory for the specified instance (identified by `instance-id`) on the
     specified `host`. It includes links to browse subdirectories and download files.")

  (service-id->state [this ^String service-id]
    "Retrieves the state the scheduler is maintaining for the given service-id.")

  (state [this]
    "Returns the global (i.e. non-service-specific) state the scheduler is maintaining"))

(defn retry-on-transient-server-exceptions-fn
  "Helper function for `retry-on-transient-server-exceptions`.
   Calls the body which we assume includes calls to marathon.
   If a transient marathon exception occurs this macro catches it, and logs it and retries a fixed number of times.
   Will return the output of body if the first call or any of the retry calls returns successfully without
   throwing a transient exception.
   Will NOT catch unknown exceptions."
  [msg retry-status-codes f & options]
  (let [with-retries (utils/retry-strategy
                       (merge {:delay-multiplier 1.5, :initial-delay-ms 200, :max-retries 5} options))
        {:keys [error success]} (with-retries
                                  (fn []
                                    (ss/try+
                                      {:success (f)}
                                      (catch #(contains? retry-status-codes (:status %)) e
                                        (log/warn (str "scheduler unavailable (error code:" (:status e) ").") msg)
                                        (ss/throw+ e))
                                      (catch ConnectException e
                                        (log/warn "connection to scheduler failed." msg)
                                        (ss/throw+ e))
                                      (catch SocketTimeoutException e
                                        (log/warn "socket timeout in connection to scheduler." msg)
                                        (ss/throw+ e))
                                      (catch TimeoutException e
                                        (log/warn "timeout in connection to scheduler." msg)
                                        (ss/throw+ e))
                                      (catch Throwable th
                                        {:error th})
                                      (catch #(not (nil? %)) _
                                        {:error (:throwable &throw-context)}))))]
    (if error
      (throw error)
      success)))

(defmacro retry-on-transient-server-exceptions
  "Calls the body which we assume includes calls to marathon.
   If a transient marathon exception occurs this macro catches it, and logs it and retries a fixed number of times.
   Will return the output of body if the first call or any of the retry calls returns successfully without throwing
   a transient exception.
   Will NOT catch unknown exceptions."
  [msg & body]
  `(retry-on-transient-server-exceptions-fn ~msg #{500 501 502 503 504} (fn [] ~@body)))

(defn suppress-transient-server-exceptions-fn
  "Helper function for `suppress-transient-server-exceptions`.
   Calls the body which we assume includes calls to marathon.
   If a transient marathon exception occurs this macro catches and logs it and returns nil.
   Will return the output of body otherwise.
   Will NOT catch unknown exceptions."
  [msg f]
  (ss/try+
    (f)
    (catch #(contains? #{500 501 502 503 504} (:status %)) e
      (log/warn (str "Scheduler unavailable (Error code: " (:status e) ").") msg)
      (log/debug (:throwable &throw-context) "Scheduler unavailable." msg))))

(defmacro suppress-transient-server-exceptions
  "Calls the body which we assume includes calls to marathon.
   If a transient marathon exception occurs this macro catches and logs it and returns nil
   Will return the output of body otherwise. Will NOT catch unknown exceptions."
  [msg & body]
  `(suppress-transient-server-exceptions-fn ~msg (fn [] ~@body)))

(defn instance->service-id
  "Returns the name of the service, stripping out any preceding slashes."
  [service-instance]
  (:service-id service-instance))

(defn base-url
  "Returns the url at which the service definition resides."
  [{:keys [host port protocol]}]
  (str protocol "://" host ":" port))

(defn end-point-url
  "Returns the endpoint url which can be queried on the service instance."
  [service-instance ^String end-point]
  (str (base-url service-instance)
       (if (and end-point (str/starts-with? end-point "/")) end-point (str "/" end-point))))

(defn health-check-url
  "Returns the health check url which can be queried on the service instance."
  [service-instance health-check-path]
  (end-point-url service-instance health-check-path))

(defn log-health-check-issues
  "Logs messages based on the type of error (if any) encountered by a health check"
  [service-instance instance-health-check-url status error]
  (if error
    (let [error-map {:instance service-instance
                     :service instance-health-check-url}]
      (condp instance? error
        ConnectException (log/debug error "error while connecting to backend for health check" error-map)
        SocketTimeoutException (log/debug error "timeout while connecting to backend for health check" error-map)
        TimeoutException (log/debug error "timeout while connecting to backend for health check" error-map)
        Throwable (log/error error "unexpected error while connecting to backend for health check" error-map)))
    (when (not (or (<= 200 status 299)
                   (= 404 status)
                   (= 504 status)))
      (log/info "unexpected status from health check" {:status status
                                                       :instance service-instance
                                                       :service instance-health-check-url}))))

(defn available?
  "Async go block which returns the status code and success of a health check.
  Returns false if such a connection cannot be established."
  [{:keys [port] :as service-instance} health-check-path http-client]
  (async/go
    (try
      (if (pos? port)
        (let [instance-health-check-url (health-check-url service-instance health-check-path)
              {:keys [status error]} (async/<! (http/get http-client instance-health-check-url))
              error-flag (cond
                           (instance? ConnectException error) :connect-exception
                           (instance? EOFException error) :hangup-exception
                           (instance? SocketTimeoutException error) :timeout-exception
                           (instance? TimeoutException error) :timeout-exception)]
          (log-health-check-issues service-instance instance-health-check-url status error)
          {:healthy? (and (not error) (<= 200 status 299))
           :status status
           :error error-flag})
        {:healthy? false})
      (catch Exception e
        (log/error e "exception thrown while performing health check" {:instance service-instance
                                                                       :health-check-path health-check-path})
        {:healthy? false}))))

(defn instance-comparator
  "The comparison order is: service-id, started-at, and finally id."
  [x y]
  (let [service-id-comp (compare (:service-id x) (:service-id y))]
    (if (zero? service-id-comp)
      (let [started-at-comp (compare (:started-at x) (:started-at y))]
        (if (zero? started-at-comp)
          (compare (:id x) (:id y))
          started-at-comp))
      service-id-comp)))

(defn sort-instances
  "Sorts two service instances, the comparison order specified by `instance-comparator`."
  [coll]
  (sort instance-comparator coll))

(defn- scheduler-gc-sanitize-state
  "Helper function to sanitize the state in the scheduler GC routines."
  [prev-service->state cur-services]
  (let [cur-services-set (set cur-services)]
    (utils/filterm (fn [[service _]] (contains? cur-services-set service)) prev-service->state)))

(defn scheduler-services-gc
  "Performs scheduler GC by tracking which services are idle (i.e. have no outstanding requests).
   The function launches a go-block that tracks the metrics state of all services currently being managed by the scheduler.
   Idle services are detected based on no changes to the metrics state past the `idle-timeout-mins` period.
   They are then deleted by the leader using the `delete-app` function.
   If an error occurs while deleting a service, there will be repeated attempts to delete it later."
  [scheduler scheduler-state-chan service-id->metrics-fn {:keys [scheduler-gc-interval-ms]} service-gc-go-routine
   service-id->service-description-fn]
  (let [service-data-chan (au/latest-chan)]
    (let [transformer-fn (fn [scheduler-messages out*]
                           (async/go
                             (doseq [[message-type message-data] scheduler-messages]
                               (when (= :update-available-apps message-type)
                                 (when-let [global-state (service-id->metrics-fn)]
                                   (let [service->state (fn [service-id]
                                                          [service-id
                                                           (merge {"outstanding" 0 "total" 0}
                                                                  (select-keys (get global-state service-id) ["outstanding" "total"]))])
                                         service->data (into {} (map service->state (:available-apps message-data)))]
                                     (async/>! out* service->data)))))
                             (async/close! out*)))]
      (async/pipeline-async 1 service-data-chan transformer-fn scheduler-state-chan false))
    (let [service->state-fn (fn [_ _ data] data)
          gc-service?-fn (fn [service-id {:keys [last-modified-time state]} current-time]
                           (let [outstanding (get state "outstanding")]
                             (and (number? outstanding)
                                  (zero? outstanding)
                                  (let [service-description (service-id->service-description-fn service-id)
                                        idle-timeout-mins (get service-description "idle-timeout-mins")
                                        timeout-time (t/plus last-modified-time (t/minutes idle-timeout-mins))]
                                    (log/debug service-id "timeout:" (utils/date-to-str timeout-time) "current:" (utils/date-to-str current-time))
                                    (t/after? current-time timeout-time)))))
          perform-gc-fn (fn [service-id]
                          (log/info "deleting idle service" service-id)
                          (try
                            (delete-app scheduler service-id)
                            (catch Exception e
                              (log/error e "unable to delete idle service" service-id))))]
      (log/info "starting scheduler-services-gc")
      (service-gc-go-routine
        "scheduler-services-gc"
        service-data-chan
        scheduler-gc-interval-ms
        scheduler-gc-sanitize-state
        service->state-fn
        gc-service?-fn
        perform-gc-fn))))

(defn scheduler-broken-services-gc
  "Performs scheduler GC by tracking which services are broken (i.e. has no healthy instance, but at least one failed instance possibly due to a broken command).
   The function launches a go-block that tracks the metrics state of all services currently being managed by the scheduler.
   Faulty services are detected based on no changes to the healthy/failed instances state past the `broken-service-timeout-mins` period, respectively.
   They are then deleted by the leader using the `delete-app` function.
   If an error occurs while deleting a service, there will be repeated attempts to delete it later."
  [scheduler scheduler-state-chan {:keys [broken-service-timeout-mins broken-service-min-hosts scheduler-gc-broken-service-interval-ms]} service-gc-go-routine]
  (let [service-data-chan (au/latest-chan)]
    (let [transformer-fn (fn [scheduler-messages out*]
                           (async/go
                             (loop [[[message-type message-data] & remaining-scheduler-messages] scheduler-messages
                                    service->data {}]
                               (if (= :update-available-apps message-type)
                                 (let [service->state (fn service->state [service-id] [service-id (get service->data service-id)])
                                       service->data' (into {} (map service->state (:available-apps message-data)))]
                                   (recur remaining-scheduler-messages service->data'))
                                 (if (= :update-app-instances message-type)
                                   (let [{:keys [service-id failed-instances healthy-instances]} message-data
                                         service->data' (assoc service->data
                                                          service-id {"has-healthy-instances" (not (empty? healthy-instances))
                                                                      "has-failed-instances" (not (empty? failed-instances))
                                                                      "failed-instance-hosts" (set (map :host failed-instances))})]
                                     (recur remaining-scheduler-messages service->data'))
                                   (async/>! out* service->data))))
                             (async/close! out*)))]
      (async/pipeline-async 1 service-data-chan transformer-fn scheduler-state-chan false))
    (let [service->state-fn (fn [_ {:strs [failed-hosts-limit-reached]} {:strs [failed-instance-hosts has-healthy-instances] :as data}]
                              (if (and (not has-healthy-instances)
                                       (or failed-hosts-limit-reached (>= (count failed-instance-hosts) broken-service-min-hosts)))
                                (-> data (dissoc "failed-instance-hosts") (assoc "failed-hosts-limit-reached" true))
                                data))
          gc-service?-fn (fn [_ {:keys [last-modified-time state]} current-time]
                           (and (false? (get state "has-healthy-instances" false))
                                (true? (get state "has-failed-instances" false))
                                (get state "failed-hosts-limit-reached" false)
                                (let [gc-time (t/plus last-modified-time (t/minutes broken-service-timeout-mins))]
                                  (t/after? current-time gc-time))))
          perform-gc-fn (fn [service-id]
                          (log/info "deleting broken service" service-id)
                          (try
                            (delete-app scheduler service-id)
                            (catch Exception e
                              (log/error e "unable to delete broken service" service-id))))]
      (log/info "starting scheduler-broken-services-gc")
      (service-gc-go-routine
        "scheduler-broken-services-gc"
        service-data-chan
        scheduler-gc-broken-service-interval-ms
        scheduler-gc-sanitize-state
        service->state-fn
        gc-service?-fn
        perform-gc-fn))))

(defn- request-available-waiter-apps
  "Queries the scheduler and builds a list of available Waiter apps."
  [scheduler service-id->service-description-fn]
  (when-let [service->service-instances (timers/start-stop-time!
                                          (metrics/waiter-timer "core" "scheduler" "get-apps")
                                          (retry-on-transient-server-exceptions
                                            "request-available-waiter-apps"
                                            (get-apps->instances scheduler)))]
    (log/trace "request-available-waiter-apps:apps" (keys service->service-instances))
    service->service-instances))

(defn- retrieve-instances-for-app
  "Queries the scheduler and builds a list of healthy and unhealthy instances for the specified service-id."
  [service-id service-instances]
  (when service-instances
    (let [{healthy-instances true, unhealthy-instances false} (group-by (comp boolean :healthy?) service-instances)]
      (log/trace "request-instances-for-app" service-id "has" (count healthy-instances) "healthy instance(s)"
                 "and" (count unhealthy-instances) " unhealthy instance(s).")
      {:healthy-instances (vec healthy-instances)
       :unhealthy-instances (vec unhealthy-instances)})))

(defn start-health-checks
  "Takes a map from service -> service instances and replaces each active instance with a ref which performs a
   health check if necessary, or returns the instance immediately."
  [service->service-instances available? service-id->service-description-fn]
  (loop [[[service {:keys [active-instances] :as instances}] & rest] (seq service->service-instances)
         service->service-instances' {}]
    (if-not service
      service->service-instances'
      (let [{:strs [health-check-url]} (service-id->service-description-fn (:id service))
            connection-errors #{:connect-exception :hangup-exception :timeout-exception}
            update-unhealthy-instance (fn [instance status error]
                                        (-> instance
                                            (assoc :healthy? false
                                                   :health-check-status status)
                                            (update :flags
                                                    (fn [flags]
                                                      (cond-> flags
                                                        (not= error :connect-exception)
                                                        (conj :has-connected)

                                                        (not (contains? connection-errors error))
                                                        (conj :has-responded))))))
            health-check-refs (map (fn [instance]
                                     (let [chan (async/promise-chan)]
                                       (if (:healthy? instance)
                                         (async/put! chan instance)
                                         (async/pipeline
                                           1 chan (map (fn [{:keys [healthy? status error]}]
                                                         (if healthy?
                                                           (assoc instance :healthy? true)
                                                           (update-unhealthy-instance instance status error))))
                                           (available? instance health-check-url)))
                                       chan))
                                   active-instances)]
        (recur rest (assoc service->service-instances' service
                                                       (assoc instances :active-instances health-check-refs)))))))

(defn do-health-checks
  "Takes a map from service -> service instances and performs health checks in parallel. Returns a map of service -> service instances."
  [service->service-instances available? service-id->service-description-fn]
  (let [service->service-instance-futures (start-health-checks service->service-instances available? service-id->service-description-fn)]
    (loop [[[service {:keys [active-instances] :as instances}] & rest] (seq service->service-instance-futures)
           service->service-instances' {}]
      (if-not service
        service->service-instances'
        (let [active-instances (doall (map async/<!! active-instances))]
          (recur rest (assoc service->service-instances'
                        service
                        (assoc instances :active-instances active-instances))))))))

(defn- update-scheduler-state
  "Queries marathon, sends data on app and instance statuses to router state maintainer, and returns scheduler state"
  [scheduler service-id->service-description-fn available? http-client failed-check-threshold service-id->health-check-context]
  (let [^DateTime request-apps-time (t/now)
        timing-message-fn (fn [] (let [^DateTime now (t/now)]
                                   (str "scheduler-syncer: sync took " (- (.getMillis now) (.getMillis request-apps-time)) " ms")))]
    (log/trace "scheduler-syncer: querying scheduler")
    (if-let [service->service-instances (timers/start-stop-time!
                                          (metrics/waiter-timer "core" "scheduler" "app->available-tasks")
                                          (do-health-checks (request-available-waiter-apps scheduler service-id->service-description-fn)
                                                            (fn available [instance health-check-path]
                                                              (available? instance health-check-path http-client))
                                                            service-id->service-description-fn))]
      (let [available-services (keys service->service-instances)
            available-service-ids (map :id available-services)]
        (log/debug "scheduler-syncer:" (count service->service-instances) "available services:" available-service-ids)
        (doseq [service available-services]
          (when (zero? (reduce + 0 (filter number? (vals (select-keys (:task-stats service) [:staged :running :healthy :unhealthy])))))
            (log/info "scheduler-syncer:" (:id service) "has no live instances!" (:task-stats service))))
        (loop [service-id->health-check-context' {}
               scheduler-messages [[:update-available-apps {:available-apps available-service-ids :scheduler-sync-time request-apps-time}]]
               [[{:keys [id]} {:keys [active-instances failed-instances]}] & remaining] (seq service->service-instances)]
          (if id
            (let [request-instances-time (t/now)
                  active-instance-ids (->> active-instances (map :id) set)
                  {:keys [instance-id->unhealthy-instance
                          instance-id->tracked-failed-instance
                          instance-id->failed-health-check-count]} (get service-id->health-check-context id)
                  {:keys [unhealthy-instances] :as service-instance-info} (retrieve-instances-for-app id active-instances)
                  instance-id->tracked-failed-instance' ((fnil into {}) instance-id->tracked-failed-instance
                                                          (keep (fn [[instance-id unhealthy-instance]]
                                                                  (when (and (not (contains? active-instance-ids instance-id))
                                                                             (>= (or (get instance-id->failed-health-check-count instance-id) 0) failed-check-threshold))
                                                                    [instance-id (update-in unhealthy-instance [:flags] conj :never-passed-health-checks)]))
                                                                instance-id->unhealthy-instance))
                  all-failed-instances (vals (merge-with (fn [failed-instance tracked-instance]
                                                           (-> failed-instance
                                                               (update-in [:flags] into (:flags tracked-instance))
                                                               (assoc :health-check-status (:health-check-status tracked-instance))))
                                                         (pc/map-from-vals :id failed-instances)
                                                         instance-id->tracked-failed-instance'))
                  scheduler-messages' (if service-instance-info
                                        ; Assume nil service-instance-info means there was a failure in invoking marathon
                                        (conj scheduler-messages
                                              [:update-app-instances
                                               (assoc service-instance-info
                                                 :service-id id
                                                 :failed-instances all-failed-instances
                                                 :scheduler-sync-time request-instances-time)])
                                        scheduler-messages)
                  instance-id->unhealthy-instance' (->> unhealthy-instances
                                                        (map (fn [{:keys [id] :as instance}]
                                                               (let [flags (get-in instance-id->unhealthy-instance [id :flags])]
                                                                 (update instance :flags into flags))))
                                                        (pc/map-from-vals :id))
                  instance-id->failed-health-check-count' (pc/map-from-keys #((fnil inc 0) (get instance-id->failed-health-check-count %))
                                                                            (keys instance-id->unhealthy-instance'))]
              (metrics/reset-counter
                (metrics/service-counter id "instance-counts" "failed")
                (count all-failed-instances))
              (recur (conj service-id->health-check-context'
                           {id {:instance-id->unhealthy-instance instance-id->unhealthy-instance'
                                :instance-id->tracked-failed-instance instance-id->tracked-failed-instance'
                                :instance-id->failed-health-check-count instance-id->failed-health-check-count'}})
                     scheduler-messages' remaining))
            (do (log/info (timing-message-fn) "for" (count service->service-instances) "services.")
                {:service-id->health-check-context service-id->health-check-context'
                 :scheduler-messages scheduler-messages}))))
      (do
        (log/info (timing-message-fn) "and found no active services")
        {:service-id->health-check-context service-id->health-check-context}))))

(defn start-scheduler-syncer
  "Starts loop to query marathon for the app and instance statuses,
  maintains a state consisting of one map with elements of shape:

    service-id {:instance-id->unhealthy-instance        {...}
                :instance-id->tracked-failed-instance   {...}
                :instance-id->failed-health-check-count {...}}

  and sends the data to the router state maintainer."
  [clock scheduler scheduler-state-chan timeout-chan service-id->service-description-fn available? http-client failed-check-threshold]
  (log/info "Starting scheduler syncer")
  (let [exit-chan (async/chan 1)
        state-query-chan (async/chan 32)]
    (async/go
      (try
        (loop [{:keys [last-update-time service-id->health-check-context] :as current-state} {}]
          (when-let [next-state
                     (async/alt!
                       exit-chan
                       ([message]
                         (log/warn "Stopping scheduler-syncer")
                         (when (not= :exit message)
                           (throw (ex-info "Stopping scheduler-syncer" {:time (t/now), :reason message}))))

                       state-query-chan
                       ([{:keys [response-chan service-id]}]
                         (if service-id
                           (let [scheduler-state (service-id->state scheduler service-id)
                                 health-check-context (service-id->health-check-context service-id)]
                             (async/>! response-chan (-> {:last-update-time last-update-time}
                                                         (merge scheduler-state health-check-context))))
                           (async/>! response-chan (merge current-state (state scheduler))))
                         current-state)

                       timeout-chan
                       ([]
                         (try
                           (timers/start-stop-time!
                             (metrics/waiter-timer "state" "scheduler-sync")
                             (let [{:keys [service-id->health-check-context scheduler-messages]}
                                   (update-scheduler-state scheduler service-id->service-description-fn available?
                                                           http-client failed-check-threshold service-id->health-check-context)]
                               (when scheduler-messages
                                 (async/>! scheduler-state-chan scheduler-messages))
                               {:last-update-time (clock)
                                :service-id->health-check-context service-id->health-check-context}))
                           (catch Throwable th
                             (log/error th "scheduler-syncer unable to receive updates")
                             (counters/inc! (metrics/waiter-counter "state" "scheduler-sync" "errors"))
                             (meters/mark! (metrics/waiter-meter "state" "scheduler-sync" "error-rate"))
                             current-state)))
                       :priority true)]
            (recur next-state)))
        (catch Exception e
          (log/error e "Fatal error in scheduler-syncer")
          (System/exit 1))))
    {:exit-chan exit-chan
     :query-chan state-query-chan}))

;;
;; Support for tracking killed instances
;;

(defn add-instance-to-buffered-collection!
  "Helper function to add/remove entries into the transient store"
  [transient-store max-instances-to-keep service-id instance-entry initial-value-fn remove-fn]
  (swap! transient-store
         (fn [service-id->failed-instances]
           (update-in service-id->failed-instances [service-id]
                      #(cond-> (or % (initial-value-fn))
                               (= max-instances-to-keep (count %)) (remove-fn)
                               true (conj instance-entry))))))

(def service-id->killed-instances-transient-store (atom {}))

(defn preserve-only-killed-instances-for-services!
  "Removes killed instance entries for services that no longer exist based on `service-ids-to-keep`."
  ([service-ids-to-keep]
   (preserve-only-killed-instances-for-services! service-id->killed-instances-transient-store service-ids-to-keep))
  ([service-id->killed-instances-transient-store service-ids-to-keep]
   (swap! service-id->killed-instances-transient-store #(select-keys % service-ids-to-keep))))

(defn remove-killed-instances-for-service!
  "Removes killed instance entries for the specified service."
  ([service-id]
   (remove-killed-instances-for-service! service-id->killed-instances-transient-store service-id))
  ([service-id->killed-instances-transient-store service-id]
   (swap! service-id->killed-instances-transient-store #(dissoc % service-id))))

(defn service-id->killed-instances
  "Return the known list of killed service instances for a given service."
  ([service-id]
   (service-id->killed-instances service-id->killed-instances-transient-store service-id))
  ([service-id->killed-instances-transient-store service-id]
   (-> (get @service-id->killed-instances-transient-store service-id [])
       (sort-instances))))

(defn process-instance-killed!
  "Process a notification that an instance has been killed.
   It adds the instances into its cache of killed instances."
  ([instance]
   (process-instance-killed! service-id->killed-instances-transient-store instance))
  ([service-id->killed-instances-transient-store {:keys [id service-id] :as instance}]
   (log/info "tracking" id "as a killed instance")
   (let [max-instances-to-keep 10]
     (add-instance-to-buffered-collection!
       service-id->killed-instances-transient-store max-instances-to-keep service-id
       (assoc instance :killed-at (utils/date-to-str (t/now)))
       #(PersistentQueue/EMPTY) pop))))

(defn environment
  "Returns a new environment variable map with some basic variables added in"
  [service-id {:strs [cpus env mem run-as-user]} service-id->password-fn home-path]
  (merge env
         {"HOME" home-path
          "LOGNAME" run-as-user
          "USER" run-as-user
          "WAITER_CPUS" (-> cpus str)
          "WAITER_MEM_MB" (-> mem str)
          "WAITER_PASSWORD" (service-id->password-fn service-id)
          "WAITER_SERVICE_ID" service-id
          "WAITER_USERNAME" "waiter"}))
