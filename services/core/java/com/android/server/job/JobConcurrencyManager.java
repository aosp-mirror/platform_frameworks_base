/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.job;

import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.procstats.ProcessStats;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;

import java.util.Iterator;
import java.util.List;

class JobConcurrencyManager {
    private static final String TAG = JobSchedulerService.TAG;
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    private final Object mLock;
    private final JobSchedulerService mService;
    private final JobSchedulerService.Constants mConstants;

    private static final int MAX_JOB_CONTEXTS_COUNT = JobSchedulerService.MAX_JOB_CONTEXTS_COUNT;

    /**
     * This array essentially stores the state of mActiveServices array.
     * The ith index stores the job present on the ith JobServiceContext.
     * We manipulate this array until we arrive at what jobs should be running on
     * what JobServiceContext.
     */
    JobStatus[] mTmpAssignContextIdToJobMap = new JobStatus[MAX_JOB_CONTEXTS_COUNT];

    /**
     * Indicates whether we need to act on this jobContext id
     */
    boolean[] mTmpAssignAct = new boolean[MAX_JOB_CONTEXTS_COUNT];

    /**
     * The uid whose jobs we would like to assign to a context.
     */
    int[] mTmpAssignPreferredUidForContext = new int[MAX_JOB_CONTEXTS_COUNT];

    JobConcurrencyManager(JobSchedulerService service) {
        mService = service;
        mLock = mService.mLock;
        mConstants = service.mConstants;
    }

    /**
     * Takes jobs from pending queue and runs them on available contexts.
     * If no contexts are available, preempts lower priority jobs to
     * run higher priority ones.
     * Lock on mJobs before calling this function.
     */
    void assignJobsToContextsLocked() {
        if (DEBUG) {
            Slog.d(TAG, printPendingQueueLocked());
        }

        final JobPackageTracker tracker = mService.mJobPackageTracker;
        final List<JobStatus> pendingJobs = mService.mPendingJobs;
        final List<JobServiceContext> activeServices = mService.mActiveServices;
        final List<StateController> controllers = mService.mControllers;

        int memLevel;
        try {
            memLevel = ActivityManager.getService().getMemoryTrimLevel();
        } catch (RemoteException e) {
            memLevel = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        }
        switch (memLevel) {
            case ProcessStats.ADJ_MEM_FACTOR_MODERATE:
                mService.mMaxActiveJobs = mConstants.BG_MODERATE_JOB_COUNT;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_LOW:
                mService.mMaxActiveJobs = mConstants.BG_LOW_JOB_COUNT;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                mService.mMaxActiveJobs = mConstants.BG_CRITICAL_JOB_COUNT;
                break;
            default:
                mService.mMaxActiveJobs = mConstants.BG_NORMAL_JOB_COUNT;
                break;
        }

        JobStatus[] contextIdToJobMap = mTmpAssignContextIdToJobMap;
        boolean[] act = mTmpAssignAct;
        int[] preferredUidForContext = mTmpAssignPreferredUidForContext;
        int numActive = 0;
        int numForeground = 0;
        for (int i=0; i<MAX_JOB_CONTEXTS_COUNT; i++) {
            final JobServiceContext js = mService.mActiveServices.get(i);
            final JobStatus status = js.getRunningJobLocked();
            if ((contextIdToJobMap[i] = status) != null) {
                numActive++;
                if (status.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP) {
                    numForeground++;
                }
            }
            act[i] = false;
            preferredUidForContext[i] = js.getPreferredUid();
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs initial"));
        }
        for (int i=0; i<pendingJobs.size(); i++) {
            JobStatus nextPending = pendingJobs.get(i);

            // If job is already running, go to next job.
            int jobRunningContext = findJobContextIdFromMap(nextPending, contextIdToJobMap);
            if (jobRunningContext != -1) {
                continue;
            }

            final int priority = mService.evaluateJobPriorityLocked(nextPending);
            nextPending.lastEvaluatedPriority = priority;

            // Find a context for nextPending. The context should be available OR
            // it should have lowest priority among all running jobs
            // (sharing the same Uid as nextPending)
            int minPriority = Integer.MAX_VALUE;
            int minPriorityContextId = -1;
            for (int j=0; j<MAX_JOB_CONTEXTS_COUNT; j++) {
                JobStatus job = contextIdToJobMap[j];
                int preferredUid = preferredUidForContext[j];
                if (job == null) {
                    if ((numActive < mService.mMaxActiveJobs ||
                            (priority >= JobInfo.PRIORITY_TOP_APP &&
                                    numForeground < mConstants.FG_JOB_COUNT)) &&
                            (preferredUid == nextPending.getUid() ||
                                    preferredUid == JobServiceContext.NO_PREFERRED_UID)) {
                        // This slot is free, and we haven't yet hit the limit on
                        // concurrent jobs...  we can just throw the job in to here.
                        minPriorityContextId = j;
                        break;
                    }
                    // No job on this context, but nextPending can't run here because
                    // the context has a preferred Uid or we have reached the limit on
                    // concurrent jobs.
                    continue;
                }
                if (job.getUid() != nextPending.getUid()) {
                    continue;
                }
                if (mService.evaluateJobPriorityLocked(job) >= nextPending.lastEvaluatedPriority) {
                    continue;
                }
                if (minPriority > nextPending.lastEvaluatedPriority) {
                    minPriority = nextPending.lastEvaluatedPriority;
                    minPriorityContextId = j;
                }
            }
            if (minPriorityContextId != -1) {
                contextIdToJobMap[minPriorityContextId] = nextPending;
                act[minPriorityContextId] = true;
                numActive++;
                if (priority >= JobInfo.PRIORITY_TOP_APP) {
                    numForeground++;
                }
            }
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs final"));
        }
        tracker.noteConcurrency(numActive, numForeground);
        for (int i=0; i<MAX_JOB_CONTEXTS_COUNT; i++) {
            boolean preservePreferredUid = false;
            if (act[i]) {
                JobStatus js = activeServices.get(i).getRunningJobLocked();
                if (js != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "preempting job: "
                                + activeServices.get(i).getRunningJobLocked());
                    }
                    // preferredUid will be set to uid of currently running job.
                    activeServices.get(i).preemptExecutingJobLocked();
                    preservePreferredUid = true;
                } else {
                    final JobStatus pendingJob = contextIdToJobMap[i];
                    if (DEBUG) {
                        Slog.d(TAG, "About to run job on context "
                                + String.valueOf(i) + ", job: " + pendingJob);
                    }
                    for (int ic=0; ic<controllers.size(); ic++) {
                        controllers.get(ic).prepareForExecutionLocked(pendingJob);
                    }
                    if (!activeServices.get(i).executeRunnableJob(pendingJob)) {
                        Slog.d(TAG, "Error executing " + pendingJob);
                    }
                    if (pendingJobs.remove(pendingJob)) {
                        tracker.noteNonpending(pendingJob);
                    }
                }
            }
            if (!preservePreferredUid) {
                activeServices.get(i).clearPreferredUid();
            }
        }
    }

    int findJobContextIdFromMap(JobStatus jobStatus, JobStatus[] map) {
        for (int i=0; i<map.length; i++) {
            if (map[i] != null && map[i].matches(jobStatus.getUid(), jobStatus.getJobId())) {
                return i;
            }
        }
        return -1;
    }

    private String printPendingQueueLocked() {
        StringBuilder s = new StringBuilder("Pending queue: ");
        Iterator<JobStatus> it = mService.mPendingJobs.iterator();
        while (it.hasNext()) {
            JobStatus js = it.next();
            s.append("(")
                    .append(js.getJob().getId())
                    .append(", ")
                    .append(js.getUid())
                    .append(") ");
        }
        return s.toString();
    }

    private String printContextIdToJobMap(JobStatus[] map, String initial) {
        StringBuilder s = new StringBuilder(initial + ": ");
        for (int i=0; i<map.length; i++) {
            s.append("(")
                    .append(map[i] == null? -1: map[i].getJobId())
                    .append(map[i] == null? -1: map[i].getUid())
                    .append(")" );
        }
        return s.toString();
    }

}
