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

package com.android.server.job.controllers;

import android.annotation.UserIdInt;
import android.app.job.JobInfo;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Controller for monitoring changes to content URIs through a ContentObserver.
 */
public final class ContentObserverController extends StateController {
    private static final String TAG = "JobScheduler.Content";
    private static final boolean DEBUG = false;

    /**
     * Maximum number of changing URIs we will batch together to report.
     * XXX Should be smarter about this, restricting it by the maximum number
     * of characters we will retain.
     */
    private static final int MAX_URIS_REPORTED = 50;

    /**
     * At this point we consider it urgent to schedule the job ASAP.
     */
    private static final int URIS_URGENT_THRESHOLD = 40;

    private static final Object sCreationLock = new Object();
    private static volatile ContentObserverController sController;

    final private ArraySet<JobStatus> mTrackedTasks = new ArraySet<>();
    /**
     * Per-userid {@link JobInfo.TriggerContentUri} keyed ContentObserver cache.
     */
    final SparseArray<ArrayMap<JobInfo.TriggerContentUri, ObserverInstance>> mObservers =
            new SparseArray<>();
    final Handler mHandler;

    public static ContentObserverController get(JobSchedulerService taskManagerService) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new ContentObserverController(taskManagerService,
                        taskManagerService.getContext(), taskManagerService.getLock());
            }
        }
        return sController;
    }

    @VisibleForTesting
    public static ContentObserverController getForTesting(StateChangedListener stateChangedListener,
                                           Context context) {
        return new ContentObserverController(stateChangedListener, context, new Object());
    }

    private ContentObserverController(StateChangedListener stateChangedListener, Context context,
                Object lock) {
        super(stateChangedListener, context, lock);
        mHandler = new Handler(context.getMainLooper());
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasContentTriggerConstraint()) {
            if (taskStatus.contentObserverJobInstance == null) {
                taskStatus.contentObserverJobInstance = new JobInstance(taskStatus);
            }
            if (DEBUG) {
                Slog.i(TAG, "Tracking content-trigger job " + taskStatus);
            }
            mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(JobStatus.TRACKING_CONTENT);
            boolean havePendingUris = false;
            // If there is a previous job associated with the new job, propagate over
            // any pending content URI trigger reports.
            if (taskStatus.contentObserverJobInstance.mChangedAuthorities != null) {
                havePendingUris = true;
            }
            // If we have previously reported changed authorities/uris, then we failed
            // to complete the job with them so will re-record them to report again.
            if (taskStatus.changedAuthorities != null) {
                havePendingUris = true;
                if (taskStatus.contentObserverJobInstance.mChangedAuthorities == null) {
                    taskStatus.contentObserverJobInstance.mChangedAuthorities
                            = new ArraySet<>();
                }
                for (String auth : taskStatus.changedAuthorities) {
                    taskStatus.contentObserverJobInstance.mChangedAuthorities.add(auth);
                }
                if (taskStatus.changedUris != null) {
                    if (taskStatus.contentObserverJobInstance.mChangedUris == null) {
                        taskStatus.contentObserverJobInstance.mChangedUris = new ArraySet<>();
                    }
                    for (Uri uri : taskStatus.changedUris) {
                        taskStatus.contentObserverJobInstance.mChangedUris.add(uri);
                    }
                }
                taskStatus.changedAuthorities = null;
                taskStatus.changedUris = null;
            }
            taskStatus.changedAuthorities = null;
            taskStatus.changedUris = null;
            taskStatus.setContentTriggerConstraintSatisfied(havePendingUris);
        }
        if (lastJob != null && lastJob.contentObserverJobInstance != null) {
            // And now we can detach the instance state from the last job.
            lastJob.contentObserverJobInstance.detachLocked();
            lastJob.contentObserverJobInstance = null;
        }
    }

    @Override
    public void prepareForExecutionLocked(JobStatus taskStatus) {
        if (taskStatus.hasContentTriggerConstraint()) {
            if (taskStatus.contentObserverJobInstance != null) {
                taskStatus.changedUris = taskStatus.contentObserverJobInstance.mChangedUris;
                taskStatus.changedAuthorities
                        = taskStatus.contentObserverJobInstance.mChangedAuthorities;
                taskStatus.contentObserverJobInstance.mChangedUris = null;
                taskStatus.contentObserverJobInstance.mChangedAuthorities = null;
            }
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (taskStatus.clearTrackingController(JobStatus.TRACKING_CONTENT)) {
            mTrackedTasks.remove(taskStatus);
            if (taskStatus.contentObserverJobInstance != null) {
                taskStatus.contentObserverJobInstance.unscheduleLocked();
                if (incomingJob != null) {
                    if (taskStatus.contentObserverJobInstance != null
                            && taskStatus.contentObserverJobInstance.mChangedAuthorities != null) {
                        // We are stopping this job, but it is going to be replaced by this given
                        // incoming job.  We want to propagate our state over to it, so we don't
                        // lose any content changes that had happened since the last one started.
                        // If there is a previous job associated with the new job, propagate over
                        // any pending content URI trigger reports.
                        if (incomingJob.contentObserverJobInstance == null) {
                            incomingJob.contentObserverJobInstance = new JobInstance(incomingJob);
                        }
                        incomingJob.contentObserverJobInstance.mChangedAuthorities
                                = taskStatus.contentObserverJobInstance.mChangedAuthorities;
                        incomingJob.contentObserverJobInstance.mChangedUris
                                = taskStatus.contentObserverJobInstance.mChangedUris;
                        taskStatus.contentObserverJobInstance.mChangedAuthorities = null;
                        taskStatus.contentObserverJobInstance.mChangedUris = null;
                    }
                    // We won't detach the content observers here, because we want to
                    // allow them to continue monitoring so we don't miss anything...  and
                    // since we are giving an incomingJob here, we know this will be
                    // immediately followed by a start tracking of that job.
                } else {
                    // But here there is no incomingJob, so nothing coming up, so time to detach.
                    taskStatus.contentObserverJobInstance.detachLocked();
                    taskStatus.contentObserverJobInstance = null;
                }
            }
            if (DEBUG) {
                Slog.i(TAG, "No longer tracking job " + taskStatus);
            }
        }
    }

    @Override
    public void rescheduleForFailureLocked(JobStatus newJob, JobStatus failureToReschedule) {
        if (failureToReschedule.hasContentTriggerConstraint()
                && newJob.hasContentTriggerConstraint()) {
            // Our job has failed, and we are scheduling a new job for it.
            // Copy the last reported content changes in to the new job, so when
            // we schedule the new one we will pick them up and report them again.
            newJob.changedAuthorities = failureToReschedule.changedAuthorities;
            newJob.changedUris = failureToReschedule.changedUris;
        }
    }

    final class ObserverInstance extends ContentObserver {
        final JobInfo.TriggerContentUri mUri;
        final @UserIdInt int mUserId;
        final ArraySet<JobInstance> mJobs = new ArraySet<>();

        public ObserverInstance(Handler handler, JobInfo.TriggerContentUri uri,
                @UserIdInt int userId) {
            super(handler);
            mUri = uri;
            mUserId = userId;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (DEBUG) {
                Slog.i(TAG, "onChange(self=" + selfChange + ") for " + uri
                        + " when mUri=" + mUri + " mUserId=" + mUserId);
            }
            synchronized (mLock) {
                final int N = mJobs.size();
                for (int i=0; i<N; i++) {
                    JobInstance inst = mJobs.valueAt(i);
                    if (inst.mChangedUris == null) {
                        inst.mChangedUris = new ArraySet<>();
                    }
                    if (inst.mChangedUris.size() < MAX_URIS_REPORTED) {
                        inst.mChangedUris.add(uri);
                    }
                    if (inst.mChangedAuthorities == null) {
                        inst.mChangedAuthorities = new ArraySet<>();
                    }
                    inst.mChangedAuthorities.add(uri.getAuthority());
                    inst.scheduleLocked();
                }
            }
        }
    }

    static final class TriggerRunnable implements Runnable {
        final JobInstance mInstance;

        TriggerRunnable(JobInstance instance) {
            mInstance = instance;
        }

        @Override public void run() {
            mInstance.trigger();
        }
    }

    final class JobInstance {
        final ArrayList<ObserverInstance> mMyObservers = new ArrayList<>();
        final JobStatus mJobStatus;
        final Runnable mExecuteRunner;
        final Runnable mTimeoutRunner;
        ArraySet<Uri> mChangedUris;
        ArraySet<String> mChangedAuthorities;

        boolean mTriggerPending;

        // This constructor must be called with the master job scheduler lock held.
        JobInstance(JobStatus jobStatus) {
            mJobStatus = jobStatus;
            mExecuteRunner = new TriggerRunnable(this);
            mTimeoutRunner = new TriggerRunnable(this);
            final JobInfo.TriggerContentUri[] uris = jobStatus.getJob().getTriggerContentUris();
            final int sourceUserId = jobStatus.getSourceUserId();
            ArrayMap<JobInfo.TriggerContentUri, ObserverInstance> observersOfUser =
                    mObservers.get(sourceUserId);
            if (observersOfUser == null) {
                observersOfUser = new ArrayMap<>();
                mObservers.put(sourceUserId, observersOfUser);
            }
            if (uris != null) {
                for (JobInfo.TriggerContentUri uri : uris) {
                    ObserverInstance obs = observersOfUser.get(uri);
                    if (obs == null) {
                        obs = new ObserverInstance(mHandler, uri, jobStatus.getSourceUserId());
                        observersOfUser.put(uri, obs);
                        final boolean andDescendants = (uri.getFlags() &
                                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS) != 0;
                        if (DEBUG) {
                            Slog.v(TAG, "New observer " + obs + " for " + uri.getUri()
                                    + " andDescendants=" + andDescendants
                                    + " sourceUserId=" + sourceUserId);
                        }
                        mContext.getContentResolver().registerContentObserver(
                                uri.getUri(),
                                andDescendants,
                                obs,
                                sourceUserId
                        );
                    } else {
                        if (DEBUG) {
                            final boolean andDescendants = (uri.getFlags() &
                                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS) != 0;
                            Slog.v(TAG, "Reusing existing observer " + obs + " for " + uri.getUri()
                                    + " andDescendants=" + andDescendants);
                        }
                    }
                    obs.mJobs.add(this);
                    mMyObservers.add(obs);
                }
            }
        }

        void trigger() {
            boolean reportChange = false;
            synchronized (mLock) {
                if (mTriggerPending) {
                    if (mJobStatus.setContentTriggerConstraintSatisfied(true)) {
                        reportChange = true;
                    }
                    unscheduleLocked();
                }
            }
            // Let the scheduler know that state has changed. This may or may not result in an
            // execution.
            if (reportChange) {
                mStateChangedListener.onControllerStateChanged();
            }
        }

        void scheduleLocked() {
            if (!mTriggerPending) {
                mTriggerPending = true;
                mHandler.postDelayed(mTimeoutRunner, mJobStatus.getTriggerContentMaxDelay());
            }
            mHandler.removeCallbacks(mExecuteRunner);
            if (mChangedUris.size() >= URIS_URGENT_THRESHOLD) {
                // If we start getting near the limit, GO NOW!
                mHandler.post(mExecuteRunner);
            } else {
                mHandler.postDelayed(mExecuteRunner, mJobStatus.getTriggerContentUpdateDelay());
            }
        }

        void unscheduleLocked() {
            if (mTriggerPending) {
                mHandler.removeCallbacks(mExecuteRunner);
                mHandler.removeCallbacks(mTimeoutRunner);
                mTriggerPending = false;
            }
        }

        void detachLocked() {
            final int N = mMyObservers.size();
            for (int i=0; i<N; i++) {
                final ObserverInstance obs = mMyObservers.get(i);
                obs.mJobs.remove(this);
                if (obs.mJobs.size() == 0) {
                    if (DEBUG) {
                        Slog.i(TAG, "Unregistering observer " + obs + " for " + obs.mUri.getUri());
                    }
                    mContext.getContentResolver().unregisterContentObserver(obs);
                    ArrayMap<JobInfo.TriggerContentUri, ObserverInstance> observerOfUser =
                            mObservers.get(obs.mUserId);
                    if (observerOfUser !=  null) {
                        observerOfUser.remove(obs.mUri);
                    }
                }
            }
        }
    }

    @Override
    public void dumpControllerStateLocked(PrintWriter pw, int filterUid) {
        pw.println("Content:");
        for (int i = 0; i < mTrackedTasks.size(); i++) {
            JobStatus js = mTrackedTasks.valueAt(i);
            if (!js.shouldDump(filterUid)) {
                continue;
            }
            pw.print("  #");
            js.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, js.getSourceUid());
            pw.println();
        }
        int N = mObservers.size();
        if (N > 0) {
            pw.println("  Observers:");
            for (int userIdx = 0; userIdx < N; userIdx++) {
                final int userId = mObservers.keyAt(userIdx);
                ArrayMap<JobInfo.TriggerContentUri, ObserverInstance> observersOfUser =
                        mObservers.get(userId);
                int numbOfObserversPerUser = observersOfUser.size();
                for (int observerIdx = 0 ; observerIdx < numbOfObserversPerUser; observerIdx++) {
                    ObserverInstance obs = observersOfUser.valueAt(observerIdx);
                    int M = obs.mJobs.size();
                    boolean shouldDump = false;
                    for (int j = 0; j < M; j++) {
                        JobInstance inst = obs.mJobs.valueAt(j);
                        if (inst.mJobStatus.shouldDump(filterUid)) {
                            shouldDump = true;
                            break;
                        }
                    }
                    if (!shouldDump) {
                        continue;
                    }
                    pw.print("    ");
                    JobInfo.TriggerContentUri trigger = observersOfUser.keyAt(observerIdx);
                    pw.print(trigger.getUri());
                    pw.print(" 0x");
                    pw.print(Integer.toHexString(trigger.getFlags()));
                    pw.print(" (");
                    pw.print(System.identityHashCode(obs));
                    pw.println("):");
                    pw.println("      Jobs:");
                    for (int j = 0; j < M; j++) {
                        JobInstance inst = obs.mJobs.valueAt(j);
                        pw.print("        #");
                        inst.mJobStatus.printUniqueId(pw);
                        pw.print(" from ");
                        UserHandle.formatUid(pw, inst.mJobStatus.getSourceUid());
                        if (inst.mChangedAuthorities != null) {
                            pw.println(":");
                            if (inst.mTriggerPending) {
                                pw.print("          Trigger pending: update=");
                                TimeUtils.formatDuration(
                                        inst.mJobStatus.getTriggerContentUpdateDelay(), pw);
                                pw.print(", max=");
                                TimeUtils.formatDuration(
                                        inst.mJobStatus.getTriggerContentMaxDelay(), pw);
                                pw.println();
                            }
                            pw.println("          Changed Authorities:");
                            for (int k = 0; k < inst.mChangedAuthorities.size(); k++) {
                                pw.print("          ");
                                pw.println(inst.mChangedAuthorities.valueAt(k));
                            }
                            if (inst.mChangedUris != null) {
                                pw.println("          Changed URIs:");
                                for (int k = 0; k < inst.mChangedUris.size(); k++) {
                                    pw.print("          ");
                                    pw.println(inst.mChangedUris.valueAt(k));
                                }
                            }
                        } else {
                            pw.println();
                        }
                    }
                }
            }
        }
    }
}
