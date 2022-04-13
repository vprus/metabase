(ns metabase.task.persist-refresh
  (:require [clojure.tools.logging :as log]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.jobs :as jobs]
            [clojurewerkz.quartzite.schedule.cron :as cron]
            [clojurewerkz.quartzite.triggers :as triggers]
            [java-time :as t]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.models.database :refer [Database]]
            [metabase.models.persisted-info :refer [PersistedInfo]]
            [metabase.models.task-history :refer [TaskHistory]]
            [metabase.public-settings :as public-settings]
            [metabase.task :as task]
            [metabase.util :as u]
            [metabase.util.i18n :refer [trs]]
            [potemkin.types :as p]
            [toucan.db :as db])
  (:import [org.quartz ObjectAlreadyExistsException Trigger]))

(defn job-context->job-type
  [job-context]
  (select-keys (qc/from-job-data job-context) ["db-id" "persisted-id" "type"]))

(def refreshable-states
  "States of `persisted_info` records which can be refreshed."
  #{"persisted" "error"})

(p/defprotocol+ Refresher
  "This protocol is just a wrapper of the ddl.interface multimethods to ease for testing. Rather than defing some
  multimethods on fake engine types, just work against this, and it will dispatch to the ddl.interface normally, or
  allow for easy to control custom behavior in tests."
  (unpersist! [this database persisted-info])
  (refresh! [this database persisted-info]))

(def dispatching-refresher
  "Refresher implementation that dispatches to the multimethods in [[metabase.driver.ddl.interface]]."
  (reify Refresher
    (refresh! [_ database persisted-info]
      (ddl.i/refresh! (:engine database) database persisted-info))
    (unpersist! [_ database persisted-info]
     (ddl.i/unpersist! (:engine database) database persisted-info))))

(defn- prune-deleteable-persists!
  [refresher]
  (let [deleteable (db/select PersistedInfo :state "deleteable")]
    (when (seq deleteable)
      (let [db-id->db  (u/key-by :id (db/select Database :id
                                                [:in (map :database_id deleteable)]))
            start-time (t/zoned-date-time)
            reap-stats (reduce (fn [stats d]
                                 (let [database (-> d :database_id db-id->db)]
                                   (log/info (trs "Unpersisting model with card-id {0}" (:card_id d)))
                                   (try
                                     (unpersist! refresher database d)
                                     (update stats :success inc)
                                     (catch Exception e
                                       (log/info e (trs "Error unpersisting model with card-id {0}" (:card_id d)))
                                       (update stats :error inc)))))
                               {:success 0, :error 0}
                               deleteable)
            end-time   (t/zoned-date-time)]
        (db/insert! TaskHistory {:task         "unpersist-tables"
                                 :started_at   start-time
                                 :ended_at     end-time
                                 :duration     (.toMillis (t/duration start-time end-time))
                                 :task_details reap-stats})))))

(defn- refresh-tables!
  "Refresh tables backing the persisted models. Updates all persisted tables with that database id which are in a state
  of \"persisted\"."
  [database-id refresher]
  (log/info (trs "Starting persisted model refresh task for Database {0}." database-id))
  (let [database      (Database database-id)
        ;; todo: what states are acceptable here? certainly "error". What about "refreshing"?
        persisted     (db/select PersistedInfo
                                 :database_id database-id, :state [:in refreshable-states])
        start-time    (t/zoned-date-time)
        refresh-stats (reduce (fn [stats p]
                                (try
                                  (refresh! refresher database p)
                                  (update stats :success inc)
                                  (catch Exception e
                                    (log/info e (trs "Error refreshing persisting model with card-id {0}" (:card_id p)))
                                    (update stats :error inc))))
                              {:success 0, :error 0}
                              persisted)
        end-time      (t/zoned-date-time)]
    (log/info (trs "Starting persisted model refresh task for Database {0}." database-id))
    (db/insert! TaskHistory {:task         "persist-refresh"
                             :db_id        database-id
                             :started_at   start-time
                             :ended_at     end-time
                             :duration     (.toMillis (t/duration start-time end-time))
                             :task_details refresh-stats})
    ;; look for any stragglers that we can try to delete
    (prune-deleteable-persists! refresher)))

(defn refresh-individual!
  "Refresh an individual model based on [[PersistedInfo]]."
  [persisted-info-id refresher]
  (log/info (trs "Attempting to refresh individual for persisted-info {0}."
                 persisted-info-id))
  (let [persisted-info (PersistedInfo persisted-info-id)
        database       (when persisted-info
                         (Database (:database_id persisted-info)))]
    (when (and persisted-info database)
      (let [start-time (t/zoned-date-time)
            success?   (try (refresh! refresher database persisted-info)
                            (Thread/sleep 5000)
                            true
                            (catch Exception e
                              (log/info e (trs "Error refreshing persisting model with card-id {0}"
                                               (:card_id persisted-info)))
                              false))
            end-time (t/zoned-date-time)]
        (db/insert! TaskHistory {:task       "persist-refresh"
                                 :db_id      (u/the-id database)
                                 :started_at start-time
                                 :ended_at   end-time
                                 :duration   (.toMillis (t/duration start-time end-time))
                                 :task_details (if success?
                                                 {:success 1 :error 0}
                                                 {:success 0 :error 1})})
        (log/info (trs "Finished updated model-id {0} from persisted-info {1}. {2}"
                       (:card_id persisted-info)
                       (u/the-id persisted-info)
                       (if success? "Success" "Failed")))))))

(defn- refresh-job-fn!
  "Refresh tables. Gets the database id from the job context and calls `refresh-tables!'`."
  [job-context]
  (let [{:strs [type db-id persisted-id] :as _payload} (job-context->job-type job-context)]
    (case type
      "database" (refresh-tables! db-id dispatching-refresher)
      "individual" (refresh-individual! persisted-id dispatching-refresher)
      (log/info (trs "Unknown payload type {0}" type)))))

(jobs/defjob ^{org.quartz.DisallowConcurrentExecution true} PersistenceRefresh
  [job-context]
  (refresh-job-fn! job-context))

(def persistence-job-key
  "Job key string for persistence job. Call `(jobs/key persistence-job-key)` if you need the org.quartz.JobKey
  instance."
  "metabase.task.PersistenceRefresh.job")

(def ^:private persistence-job
  (jobs/build
   (jobs/with-description "Persisted Model refresh task")
   (jobs/of-type PersistenceRefresh)
   (jobs/with-identity (jobs/key persistence-job-key))
   (jobs/store-durably)))

(defn- trigger-key [database]
  (triggers/key (format "metabase.task.PersistenceRefresh.trigger.%d" (u/the-id database))))

(defn individual-trigger-key [persisted-info]
  (triggers/key (format "metabase.task.PersistenceRefresh.individual.trigger.%d"
                        (u/the-id persisted-info))))

(defn- cron-schedule
  "Return a cron schedule that fires every `hours` hours."
  [hours]
  (cron/schedule
   ;; every 8 hours
   (cron/cron-schedule (if (= hours 24)
                         ;; hack: scheduling for midnight UTC but this does raise the issue of anchor time
                         "0 0 0 * * ? *"
                         (format "0 0 0/%d * * ? *" hours)))
   (cron/with-misfire-handling-instruction-do-nothing)))

(defn- trigger [database interval-hours]
  (triggers/build
   (triggers/with-description (format "Refresh models for database %d" (u/the-id database)))
   (triggers/with-identity (trigger-key database))
   (triggers/using-job-data {"db-id" (u/the-id database)
                             "type"  "database"})
   (triggers/for-job (jobs/key persistence-job-key))
   (triggers/start-now)
   (triggers/with-schedule
     (cron-schedule interval-hours))))

(defn schedule-persistence-for-database
  "Schedule a database for persistence refreshing."
  [database interval-hours]
  (let [tggr (trigger database interval-hours)]
    (log/info
     (u/format-color 'green
                     "Scheduling persistence refreshes for database %d: trigger: %s"
                     (u/the-id database) (.. ^Trigger tggr getKey getName)))
    (try (task/add-trigger! tggr)
         (catch ObjectAlreadyExistsException _e
           (log/info
            (u/format-color 'green "Persistence already present for database %d: trigger: %s"
                            (u/the-id database)
                            (.. ^Trigger tggr getKey getName)))))))

(defn- individual-trigger [persisted-info]
  (triggers/build
   (triggers/with-description (format "Refresh model %d: persisted-info %d"
                                      (:card_id persisted-info)
                                      (u/the-id persisted-info)))
   (triggers/with-identity (individual-trigger-key persisted-info))
   (triggers/using-job-data {"persisted-id" (u/the-id persisted-info)
                             "type"         "individual"})
   (triggers/for-job (jobs/key persistence-job-key))
   (triggers/start-now)))

(defn schedule-refresh-for-individual
  "Schedule a refresh of an individual [[PersistedInfo record]]. Done through quartz for locking purposes."
  [persisted-info]
  (let [tggr (individual-trigger persisted-info)]
    (log/info
     (u/format-color 'green
                     "Scheduling refresh for model: %d"
                     (:card_id persisted-info)))
    (try (task/add-trigger! tggr)
         (catch ObjectAlreadyExistsException _e
           (log/info
            (u/format-color 'green "Persistence already present for model %d"
                            (:card_id persisted-info)
                            (.. ^Trigger tggr getKey getName))))
         ;; other errors?
         )))

(defn unschedule-persistence-for-database
  "Stop refreshing tables for a given database. Should only be called when marking the database as not
  persisting. Tables will be left over and up to the caller to clean up."
  [database]
  (task/delete-trigger! (trigger-key database)))

(defn unschedule-all-triggers
  "Unschedule all database persisted model refresh triggers."
  []
  (let [trigger-keys (->> (task/job-info persistence-job-key)
                          :triggers
                          (map :key))]
    (doseq [tk trigger-keys]
      (task/delete-trigger! (triggers/key tk)))))

(defn reschedule-refresh
  "Reschedule refresh for all enabled databases. Removes all existing triggers, and schedules refresh for databases with
  `:persist-models-enabled` in the options at interval [[public-settings/persisted-model-refresh-interval-hours]]."
  []
  (let [dbs-with-persistence (filter (comp :persist-models-enabled :options) (Database))
        interval-hours       (public-settings/persisted-model-refresh-interval-hours)]
    (unschedule-all-triggers)
    (doseq [db dbs-with-persistence]
      (schedule-persistence-for-database db interval-hours))))

(defn- job-init
  []
  (task/add-job! persistence-job))

(defmethod task/init! ::PersistRefresh
  [_]
  (job-init)
  (reschedule-refresh))