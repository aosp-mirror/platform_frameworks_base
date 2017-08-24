/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.job;

import android.app.job.JobInfo;

import java.util.List;

/**
 * JobScheduler local system service interface.
 * {@hide} Only for use within the system server.
 */
public interface JobSchedulerInternal {

    /**
     * Returns a list of pending jobs scheduled by the system service.
     */
    List<JobInfo> getSystemScheduledPendingJobs();

    /**
     * These are for activity manager to communicate to use what is currently performing backups.
     */
    void addBackingUpUid(int uid);
    void removeBackingUpUid(int uid);
    void clearAllBackingUpUids();

    JobStorePersistStats getPersistStats();

    /**
     * Stats about the first load after boot and the most recent save.
     * STOPSHIP Remove it and the relevant code once b/64536115 is fixed.
     */
    public class JobStorePersistStats {
        public int countAllJobsLoaded = -1;
        public int countSystemServerJobsLoaded = -1;
        public int countSystemSyncManagerJobsLoaded = -1;

        public int countAllJobsSaved = -1;
        public int countSystemServerJobsSaved = -1;
        public int countSystemSyncManagerJobsSaved = -1;

        public JobStorePersistStats() {
        }

        public JobStorePersistStats(JobStorePersistStats source) {
            countAllJobsLoaded = source.countAllJobsLoaded;
            countSystemServerJobsLoaded = source.countSystemServerJobsLoaded;
            countSystemSyncManagerJobsLoaded = source.countSystemSyncManagerJobsLoaded;

            countAllJobsSaved = source.countAllJobsSaved;
            countSystemServerJobsSaved = source.countSystemServerJobsSaved;
            countSystemSyncManagerJobsSaved = source.countSystemSyncManagerJobsSaved;
        }

        @Override
        public String toString() {
            return "FirstLoad: "
                    + countAllJobsLoaded + "/"
                    + countSystemServerJobsLoaded + "/"
                    + countSystemSyncManagerJobsLoaded
                    + " LastSave: "
                    + countAllJobsSaved + "/"
                    + countSystemServerJobsSaved + "/"
                    + countSystemSyncManagerJobsSaved;
        }
    }
}
