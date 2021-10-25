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

import android.annotation.NonNull;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.util.proto.ProtoOutputStream;

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
     * Cancel the jobs for a given uid (e.g. when app data is cleared)
     */
    void cancelJobsForUid(int uid, @JobParameters.StopReason int reason, int debugReasonCode,
            String debugReason);

    /**
     * These are for activity manager to communicate to use what is currently performing backups.
     */
    void addBackingUpUid(int uid);
    void removeBackingUpUid(int uid);
    void clearAllBackingUpUids();

    /** Returns the package responsible for backing up media on the device. */
    @NonNull
    String getMediaBackupPackage();

    /**
     * The user has started interacting with the app.  Take any appropriate action.
     */
    void reportAppUsage(String packageName, int userId);

    /**
     * Report a snapshot of sync-related jobs back to the sync manager
     */
    JobStorePersistStats getPersistStats();

    /**
     * Stats about the first load after boot and the most recent save.
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

        /**
         * Write the persist stats to the specified field.
         */
        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            final long flToken = proto.start(JobStorePersistStatsProto.FIRST_LOAD);
            proto.write(JobStorePersistStatsProto.Stats.NUM_TOTAL_JOBS, countAllJobsLoaded);
            proto.write(JobStorePersistStatsProto.Stats.NUM_SYSTEM_SERVER_JOBS,
                    countSystemServerJobsLoaded);
            proto.write(JobStorePersistStatsProto.Stats.NUM_SYSTEM_SYNC_MANAGER_JOBS,
                    countSystemSyncManagerJobsLoaded);
            proto.end(flToken);

            final long lsToken = proto.start(JobStorePersistStatsProto.LAST_SAVE);
            proto.write(JobStorePersistStatsProto.Stats.NUM_TOTAL_JOBS, countAllJobsSaved);
            proto.write(JobStorePersistStatsProto.Stats.NUM_SYSTEM_SERVER_JOBS,
                    countSystemServerJobsSaved);
            proto.write(JobStorePersistStatsProto.Stats.NUM_SYSTEM_SYNC_MANAGER_JOBS,
                    countSystemSyncManagerJobsSaved);
            proto.end(lsToken);

            proto.end(token);
        }
    }
}
