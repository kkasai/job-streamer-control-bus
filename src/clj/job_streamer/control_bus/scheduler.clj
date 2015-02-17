(ns job-streamer.control-bus.scheduler
  (:require [job-streamer.control-bus.model :as model]
            [clojure.tools.logging :as log])
  (:import [net.unit8.job_streamer.control_bus JobStreamerExecuteJob]
           (org.quartz TriggerBuilder JobBuilder CronScheduleBuilder
                       TriggerKey TriggerUtils CronExpression)
           [org.quartz.impl StdSchedulerFactory]))

(defonce scheduler (atom nil))
(defonce control-bus (atom {}))

(defn- make-trigger [job-id cron-notation]
  (.. (TriggerBuilder/newTrigger)
      (withIdentity (str "trigger-" job-id))
      (withSchedule (CronScheduleBuilder/cronSchedule cron-notation))
      (build)))

(defn schedule [job-id cron-notation]
  (let [new-trigger (make-trigger job-id cron-notation)
        job (model/pull '[:job/id
                          {:job/schedule
                           [:db/id
                            :schedule/cron-notation]}] job-id)
        job-detail (.. (JobBuilder/newJob)
                       (ofType JobStreamerExecuteJob)
                       (withIdentity (str "job-" job-id))
                       (usingJobData "job-name" (:job/id job))
                       (usingJobData "host" (:host @control-bus))
                       (usingJobData "port" (:port @control-bus))
                       (build))]
    (if-let [trigger (.getTrigger @scheduler (TriggerKey. (str "trigger-" job-id)))]
      (do
        (.rescheduleJob @scheduler (.getKey trigger) new-trigger)
        (model/transact [{:db/id (get-in job [:job/schedule :db/id])
                          :schedule/cron-notation cron-notation}]))
      (do
        (.scheduleJob @scheduler job-detail new-trigger)
        (model/transact [{:db/id #db/id[db.part/user -1]
                          :schedule/cron-notation cron-notation}
                         {:db/id job-id
                          :job/schedule #db/id[db.part/user -1]}])))))

(defn unschedule [job-id]
  (let [job (model/pull '[{:job/schedule
                           [:db/id]}] job-id)]
    (.unscheduleJob @scheduler (TriggerKey. (str "trigger-" job-id)))
    (model/transact [[:db.fn/retractEntity (get-in job [:job/schedule :db/id])]])))

(defn fire-times [job-id]
  (let [trigger (.getTrigger @scheduler (TriggerKey. (str "trigger-" job-id)))]
    (TriggerUtils/computeFireTimes trigger nil 5)))

(defn validate-format [cron-notation]
  (CronExpression/validateExpression cron-notation))

(defn start [host port]
  (swap! control-bus assoc :host host :port (int port))
  (reset! scheduler (.getScheduler (StdSchedulerFactory.)))
  (.start @scheduler)
  (log/info "started scheduler.")
  (let [schedules (model/query '{:find [?job ?cron-notation]
                                 :where [[?job :job/schedule ?schedule]
                                         [?job :job/id ?job-id]
                                         [?schedule :schedule/cron-notation ?cron-notation]]})]
    (doseq [[job-id cron-notation] schedules]
      (log/info "Recover schedule: " job-id cron-notation)
      (schedule job-id cron-notation))))

(defn stop []
  (.shutdown @scheduler)
  (log/info "stop scheduler.")
  (reset! scheduler nil))


