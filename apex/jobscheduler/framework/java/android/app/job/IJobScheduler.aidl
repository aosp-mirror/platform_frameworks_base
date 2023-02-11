/**
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License.
 */

package android.app.job;

import android.app.job.IUserVisibleJobObserver;
import android.app.job.JobInfo;
import android.app.job.JobSnapshot;
import android.app.job.JobWorkItem;
import android.content.pm.ParceledListSlice;
import java.util.Map;

 /**
  * IPC interface that supports the app-facing {@link #JobScheduler} api.
  * {@hide}
  */
interface IJobScheduler {
    int schedule(String namespace, in JobInfo job);
    int enqueue(String namespace, in JobInfo job, in JobWorkItem work);
    int scheduleAsPackage(String namespace, in JobInfo job, String packageName, int userId, String tag);
    void cancel(String namespace, int jobId);
    void cancelAll();
    void cancelAllInNamespace(String namespace);
    // Returns Map<String, ParceledListSlice>, where the keys are the namespaces.
    Map<String, ParceledListSlice<JobInfo>> getAllPendingJobs();
    ParceledListSlice<JobInfo> getAllPendingJobsInNamespace(String namespace);
    JobInfo getPendingJob(String namespace, int jobId);
    int getPendingJobReason(String namespace, int jobId);
    boolean canRunUserInitiatedJobs(String packageName);
    boolean hasRunUserInitiatedJobsPermission(String packageName, int userId);
    List<JobInfo> getStartedJobs();
    ParceledListSlice getAllJobSnapshots();
    @EnforcePermission(allOf={"MANAGE_ACTIVITY_TASKS", "INTERACT_ACROSS_USERS_FULL"})
    void registerUserVisibleJobObserver(in IUserVisibleJobObserver observer);
    @EnforcePermission(allOf={"MANAGE_ACTIVITY_TASKS", "INTERACT_ACROSS_USERS_FULL"})
    void unregisterUserVisibleJobObserver(in IUserVisibleJobObserver observer);
    @EnforcePermission(allOf={"MANAGE_ACTIVITY_TASKS", "INTERACT_ACROSS_USERS_FULL"})
    void notePendingUserRequestedAppStop(String packageName, int userId, String debugReason);
}
