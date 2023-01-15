/*
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
 * limitations under the License
 */

package com.android.server.job;

import static android.app.job.JobInfo.getPriorityString;

import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_NONE;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.BytesLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.job.IJobCallback;
import android.app.job.IJobService;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobProtoEnums;
import android.app.job.JobWorkItem;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Network;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.job.controllers.JobStatus;
import com.android.server.tare.EconomicPolicy;
import com.android.server.tare.EconomyManagerInternal;
import com.android.server.tare.JobSchedulerEconomicPolicy;

import java.util.Objects;

/**
 * Handles client binding and lifecycle of a job. Jobs execute one at a time on an instance of this
 * class.
 *
 * There are two important interactions into this class from the
 * {@link com.android.server.job.JobSchedulerService}. To execute a job and to cancel a job.
 * - Execution of a new job is handled by the {@link #mAvailable}. This bit is flipped once when a
 * job lands, and again when it is complete.
 * - Cancelling is trickier, because there are also interactions from the client. It's possible
 * the {@link com.android.server.job.JobServiceContext.JobServiceHandler} tries to process a
 * {@link #doCancelLocked} after the client has already finished. This is handled by having
 * {@link com.android.server.job.JobServiceContext.JobServiceHandler#handleCancelLocked} check whether
 * the context is still valid.
 * To mitigate this, we avoid sending duplicate onStopJob()
 * calls to the client after they've specified jobFinished().
 */
public final class JobServiceContext implements ServiceConnection {
    private static final boolean DEBUG = JobSchedulerService.DEBUG;
    private static final boolean DEBUG_STANDBY = JobSchedulerService.DEBUG_STANDBY;

    private static final String TAG = "JobServiceContext";
    /** Amount of time the JobScheduler waits for the initial service launch+bind. */
    private static final long OP_BIND_TIMEOUT_MILLIS = 18 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;
    /** Amount of time the JobScheduler will wait for a response from an app for a message. */
    private static final long OP_TIMEOUT_MILLIS = 8 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    private static final String[] VERB_STRINGS = {
            "VERB_BINDING", "VERB_STARTING", "VERB_EXECUTING", "VERB_STOPPING", "VERB_FINISHED"
    };

    // States that a job occupies while interacting with the client.
    static final int VERB_BINDING = 0;
    static final int VERB_STARTING = 1;
    static final int VERB_EXECUTING = 2;
    static final int VERB_STOPPING = 3;
    static final int VERB_FINISHED = 4;

    // Messages that result from interactions with the client service.
    /** System timed out waiting for a response. */
    private static final int MSG_TIMEOUT = 0;

    public static final int NO_PREFERRED_UID = -1;

    private final Handler mCallbackHandler;
    /** Make callbacks to {@link JobSchedulerService} to inform on job completion status. */
    private final JobCompletedListener mCompletedListener;
    private final JobConcurrencyManager mJobConcurrencyManager;
    private final JobNotificationCoordinator mNotificationCoordinator;
    private final JobSchedulerService mService;
    /** Used for service binding, etc. */
    private final Context mContext;
    private final Object mLock;
    private final IBatteryStats mBatteryStats;
    private final EconomyManagerInternal mEconomyManagerInternal;
    private final JobPackageTracker mJobPackageTracker;
    private final PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;

    // Execution state.
    private JobParameters mParams;
    @VisibleForTesting
    int mVerb;
    private boolean mCancelled;
    /**
     * True if the previous job on this context successfully finished (ie. called jobFinished or
     * dequeueWork with no work left).
     */
    private boolean mPreviousJobHadSuccessfulFinish;
    /**
     * The last time a job on this context didn't finish successfully, in the elapsed realtime
     * timebase.
     */
    private long mLastUnsuccessfulFinishElapsed;

    /**
     * All the information maintained about the job currently being executed.
     *
     * Any reads (dereferences) not done from the handler thread must be synchronized on
     * {@link #mLock}.
     * Writes can only be done from the handler thread,
     * or {@link #executeRunnableJob(JobStatus, int)}.
     */
    private JobStatus mRunningJob;
    @JobConcurrencyManager.WorkType
    private int mRunningJobWorkType;
    private JobCallback mRunningCallback;
    /** Used to store next job to run when current job is to be preempted. */
    private int mPreferredUid;
    IJobService service;

    /**
     * Whether this context is free. This is set to false at the start of execution, and reset to
     * true when execution is complete.
     */
    @GuardedBy("mLock")
    private boolean mAvailable;
    /** Track start time. */
    private long mExecutionStartTimeElapsed;
    /** Track when job will timeout. */
    private long mTimeoutElapsed;
    /**
     * The minimum amount of time the context will allow the job to run before checking whether to
     * stop it or not.
     */
    private long mMinExecutionGuaranteeMillis;
    /** The absolute maximum amount of time the job can run */
    private long mMaxExecutionTimeMillis;

    private long mEstimatedDownloadBytes;
    private long mEstimatedUploadBytes;
    private long mTransferredDownloadBytes;
    private long mTransferredUploadBytes;

    /**
     * The stop reason for a pending cancel. If there's not pending cancel, then the value should be
     * {@link JobParameters#STOP_REASON_UNDEFINED}.
     */
    private int mPendingStopReason = JobParameters.STOP_REASON_UNDEFINED;
    private int mPendingInternalStopReason;
    private String mPendingDebugStopReason;

    private Network mPendingNetworkChange;

    // Debugging: reason this job was last stopped.
    public String mStoppedReason;

    // Debugging: time this job was last stopped.
    public long mStoppedTime;

    final class JobCallback extends IJobCallback.Stub {
        public String mStoppedReason;
        public long mStoppedTime;

        @Override
        public void acknowledgeGetTransferredDownloadBytesMessage(int jobId, int workId,
                @BytesLong long transferredBytes) {
            doAcknowledgeGetTransferredDownloadBytesMessage(this, jobId, workId, transferredBytes);
        }

        @Override
        public void acknowledgeGetTransferredUploadBytesMessage(int jobId, int workId,
                @BytesLong long transferredBytes) {
            doAcknowledgeGetTransferredUploadBytesMessage(this, jobId, workId, transferredBytes);
        }

        @Override
        public void acknowledgeStartMessage(int jobId, boolean ongoing) {
            doAcknowledgeStartMessage(this, jobId, ongoing);
        }

        @Override
        public void acknowledgeStopMessage(int jobId, boolean reschedule) {
            doAcknowledgeStopMessage(this, jobId, reschedule);
        }

        @Override
        public JobWorkItem dequeueWork(int jobId) {
            return doDequeueWork(this, jobId);
        }

        @Override
        public boolean completeWork(int jobId, int workId) {
            return doCompleteWork(this, jobId, workId);
        }

        @Override
        public void jobFinished(int jobId, boolean reschedule) {
            doJobFinished(this, jobId, reschedule);
        }

        @Override
        public void updateEstimatedNetworkBytes(int jobId, JobWorkItem item,
                long downloadBytes, long uploadBytes) {
            doUpdateEstimatedNetworkBytes(this, jobId, item, downloadBytes, uploadBytes);
        }

        @Override
        public void updateTransferredNetworkBytes(int jobId, JobWorkItem item,
                long downloadBytes, long uploadBytes) {
            doUpdateTransferredNetworkBytes(this, jobId, item, downloadBytes, uploadBytes);
        }

        @Override
        public void setNotification(int jobId, int notificationId,
                Notification notification, int jobEndNotificationPolicy) {
            doSetNotification(this, jobId, notificationId, notification, jobEndNotificationPolicy);
        }
    }

    JobServiceContext(JobSchedulerService service, JobConcurrencyManager concurrencyManager,
            JobNotificationCoordinator notificationCoordinator,
            IBatteryStats batteryStats, JobPackageTracker tracker, Looper looper) {
        mContext = service.getContext();
        mLock = service.getLock();
        mService = service;
        mBatteryStats = batteryStats;
        mEconomyManagerInternal = LocalServices.getService(EconomyManagerInternal.class);
        mJobPackageTracker = tracker;
        mCallbackHandler = new JobServiceHandler(looper);
        mJobConcurrencyManager = concurrencyManager;
        mNotificationCoordinator = notificationCoordinator;
        mCompletedListener = service;
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mAvailable = true;
        mVerb = VERB_FINISHED;
        mPreferredUid = NO_PREFERRED_UID;
    }

    /**
     * Give a job to this context for execution. Callers must first check {@link
     * #getRunningJobLocked()}
     * and ensure it is null to make sure this is a valid context.
     *
     * @param job The status of the job that we are going to run.
     * @return True if the job is valid and is running. False if the job cannot be executed.
     */
    boolean executeRunnableJob(JobStatus job, @JobConcurrencyManager.WorkType int workType) {
        synchronized (mLock) {
            if (!mAvailable) {
                Slog.e(TAG, "Starting new runnable but context is unavailable > Error.");
                return false;
            }

            mPreferredUid = NO_PREFERRED_UID;

            mRunningJob = job;
            mRunningJobWorkType = workType;
            mRunningCallback = new JobCallback();
            mPendingNetworkChange = null;
            final boolean isDeadlineExpired =
                    job.hasDeadlineConstraint() &&
                            (job.getLatestRunTimeElapsed() < sElapsedRealtimeClock.millis());
            Uri[] triggeredUris = null;
            if (job.changedUris != null) {
                triggeredUris = new Uri[job.changedUris.size()];
                job.changedUris.toArray(triggeredUris);
            }
            String[] triggeredAuthorities = null;
            if (job.changedAuthorities != null) {
                triggeredAuthorities = new String[job.changedAuthorities.size()];
                job.changedAuthorities.toArray(triggeredAuthorities);
            }
            final JobInfo ji = job.getJob();
            mParams = new JobParameters(mRunningCallback, job.getNamespace(), job.getJobId(),
                    ji.getExtras(),
                    ji.getTransientExtras(), ji.getClipData(), ji.getClipGrantFlags(),
                    isDeadlineExpired, job.shouldTreatAsExpeditedJob(),
                    job.shouldTreatAsUserInitiatedJob(), triggeredUris, triggeredAuthorities,
                    job.network);
            mExecutionStartTimeElapsed = sElapsedRealtimeClock.millis();
            mMinExecutionGuaranteeMillis = mService.getMinJobExecutionGuaranteeMs(job);
            mMaxExecutionTimeMillis =
                    Math.max(mService.getMaxJobExecutionTimeMs(job), mMinExecutionGuaranteeMillis);
            mEstimatedDownloadBytes = job.getEstimatedNetworkDownloadBytes();
            mEstimatedUploadBytes = job.getEstimatedNetworkUploadBytes();
            mTransferredDownloadBytes = mTransferredUploadBytes = 0;

            final long whenDeferred = job.getWhenStandbyDeferred();
            if (whenDeferred > 0) {
                final long deferral = mExecutionStartTimeElapsed - whenDeferred;
                EventLog.writeEvent(EventLogTags.JOB_DEFERRED_EXECUTION, deferral);
                if (DEBUG_STANDBY) {
                    StringBuilder sb = new StringBuilder(128);
                    sb.append("Starting job deferred for standby by ");
                    TimeUtils.formatDuration(deferral, sb);
                    sb.append(" ms : ");
                    sb.append(job.toShortString());
                    Slog.v(TAG, sb.toString());
                }
            }

            // Once we'e begun executing a job, we by definition no longer care whether
            // it was inflated from disk with not-yet-coherent delay/deadline bounds.
            job.clearPersistedUtcTimes();

            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, job.getTag());
            mWakeLock.setWorkSource(
                    mService.deriveWorkSource(job.getSourceUid(), job.getSourcePackageName()));
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();

            // Note the start when we try to bind so that the app is charged for some processing
            // even if binding fails.
            mEconomyManagerInternal.noteInstantaneousEvent(
                    job.getSourceUserId(), job.getSourcePackageName(),
                    getStartActionId(job), String.valueOf(job.getJobId()));
            mVerb = VERB_BINDING;
            scheduleOpTimeOutLocked();
            // Use FLAG_FROM_BACKGROUND to avoid resetting the bad-app tracking.
            final Intent intent = new Intent().setComponent(job.getServiceComponent())
                    .setFlags(Intent.FLAG_FROM_BACKGROUND);
            boolean binding = false;
            try {
                final int bindFlags;
                if (job.shouldTreatAsExpeditedJob()) {
                    bindFlags = Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                            | Context.BIND_ALMOST_PERCEPTIBLE
                            | Context.BIND_BYPASS_POWER_NETWORK_RESTRICTIONS
                            | Context.BIND_NOT_APP_COMPONENT_USAGE;
                } else {
                    bindFlags = Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                            | Context.BIND_NOT_PERCEPTIBLE
                            | Context.BIND_NOT_APP_COMPONENT_USAGE;
                }
                binding = mContext.bindServiceAsUser(intent, this, bindFlags,
                        UserHandle.of(job.getUserId()));
            } catch (SecurityException e) {
                // Some permission policy, for example INTERACT_ACROSS_USERS and
                // android:singleUser, can result in a SecurityException being thrown from
                // bindServiceAsUser().  If this happens, catch it and fail gracefully.
                Slog.w(TAG, "Job service " + job.getServiceComponent().getShortClassName()
                        + " cannot be executed: " + e.getMessage());
                binding = false;
            }
            if (!binding) {
                if (DEBUG) {
                    Slog.d(TAG, job.getServiceComponent().getShortClassName() + " unavailable.");
                }
                mRunningJob = null;
                mRunningJobWorkType = WORK_TYPE_NONE;
                mRunningCallback = null;
                mParams = null;
                mExecutionStartTimeElapsed = 0L;
                mWakeLock.release();
                mVerb = VERB_FINISHED;
                removeOpTimeOutLocked();
                return false;
            }
            mJobPackageTracker.noteActive(job);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SCHEDULED_JOB_STATE_CHANGED,
                    job.getSourceUid(), null, job.getBatteryName(),
                    FrameworkStatsLog.SCHEDULED_JOB_STATE_CHANGED__STATE__STARTED,
                    JobProtoEnums.INTERNAL_STOP_REASON_UNKNOWN,
                    job.getStandbyBucket(), job.getJobId(),
                    job.hasChargingConstraint(),
                    job.hasBatteryNotLowConstraint(),
                    job.hasStorageNotLowConstraint(),
                    job.hasTimingDelayConstraint(),
                    job.hasDeadlineConstraint(),
                    job.hasIdleConstraint(),
                    job.hasConnectivityConstraint(),
                    job.hasContentTriggerConstraint(),
                    job.isRequestedExpeditedJob(),
                    job.shouldTreatAsExpeditedJob(),
                    JobProtoEnums.STOP_REASON_UNDEFINED,
                    job.getJob().isPrefetch(),
                    job.getJob().getPriority(),
                    job.getEffectivePriority(),
                    job.getNumPreviousAttempts(),
                    job.getJob().getMaxExecutionDelayMillis(),
                    isDeadlineExpired,
                    job.isConstraintSatisfied(JobInfo.CONSTRAINT_FLAG_CHARGING),
                    job.isConstraintSatisfied(JobInfo.CONSTRAINT_FLAG_BATTERY_NOT_LOW),
                    job.isConstraintSatisfied(JobInfo.CONSTRAINT_FLAG_STORAGE_NOT_LOW),
                    job.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY),
                    job.isConstraintSatisfied(JobInfo.CONSTRAINT_FLAG_DEVICE_IDLE),
                    job.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY),
                    job.isConstraintSatisfied(JobStatus.CONSTRAINT_CONTENT_TRIGGER));
            if (Trace.isTagEnabled(Trace.TRACE_TAG_SYSTEM_SERVER)) {
                // Use the context's ID to distinguish traces since there'll only be one job
                // running per context.
                Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "JobScheduler",
                        job.getTag(), getId());
            }
            try {
                mBatteryStats.noteJobStart(job.getBatteryName(), job.getSourceUid());
            } catch (RemoteException e) {
                // Whatever.
            }
            final String jobPackage = job.getSourcePackageName();
            final int jobUserId = job.getSourceUserId();
            UsageStatsManagerInternal usageStats =
                    LocalServices.getService(UsageStatsManagerInternal.class);
            usageStats.setLastJobRunTime(jobPackage, jobUserId, mExecutionStartTimeElapsed);
            mAvailable = false;
            mStoppedReason = null;
            mStoppedTime = 0;
            job.startedAsExpeditedJob = job.shouldTreatAsExpeditedJob();
            return true;
        }
    }

    @EconomicPolicy.AppAction
    private static int getStartActionId(@NonNull JobStatus job) {
        switch (job.getEffectivePriority()) {
            case JobInfo.PRIORITY_MAX:
                return JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START;
            case JobInfo.PRIORITY_HIGH:
                return JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_START;
            case JobInfo.PRIORITY_LOW:
                return JobSchedulerEconomicPolicy.ACTION_JOB_LOW_START;
            case JobInfo.PRIORITY_MIN:
                return JobSchedulerEconomicPolicy.ACTION_JOB_MIN_START;
            default:
                Slog.wtf(TAG, "Unknown priority: " + getPriorityString(job.getEffectivePriority()));
                // Intentional fallthrough
            case JobInfo.PRIORITY_DEFAULT:
                return JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_START;
        }
    }

    /**
     * Used externally to query the running job. Will return null if there is no job running.
     */
    @Nullable
    JobStatus getRunningJobLocked() {
        return mRunningJob;
    }

    @JobConcurrencyManager.WorkType
    int getRunningJobWorkType() {
        return mRunningJobWorkType;
    }

    /**
     * Used only for debugging. Will return <code>"&lt;null&gt;"</code> if there is no job running.
     */
    private String getRunningJobNameLocked() {
        return mRunningJob != null ? mRunningJob.toShortString() : "<null>";
    }

    /** Called externally when a job that was scheduled for execution should be cancelled. */
    @GuardedBy("mLock")
    void cancelExecutingJobLocked(@JobParameters.StopReason int reason,
            int internalStopReason, @NonNull String debugReason) {
        doCancelLocked(reason, internalStopReason, debugReason);
    }

    int getPreferredUid() {
        return mPreferredUid;
    }

    void clearPreferredUid() {
        mPreferredUid = NO_PREFERRED_UID;
    }

    int getId() {
        return hashCode();
    }

    long getExecutionStartTimeElapsed() {
        return mExecutionStartTimeElapsed;
    }

    long getTimeoutElapsed() {
        return mTimeoutElapsed;
    }

    long getRemainingGuaranteedTimeMs(long nowElapsed) {
        return Math.max(0, mExecutionStartTimeElapsed + mMinExecutionGuaranteeMillis - nowElapsed);
    }

    void informOfNetworkChangeLocked(Network newNetwork) {
        if (mVerb != VERB_EXECUTING) {
            Slog.w(TAG, "Sending onNetworkChanged for a job that isn't started. " + mRunningJob);
            if (mVerb == VERB_BINDING || mVerb == VERB_STARTING) {
                // The network changed before the job has fully started. Hold the change push
                // until the job has started executing.
                mPendingNetworkChange = newNetwork;
            }
            return;
        }
        try {
            mParams.setNetwork(newNetwork);
            mPendingNetworkChange = null;
            service.onNetworkChanged(mParams);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error sending onNetworkChanged to client.", e);
            // The job's host app apparently crashed during the job, so we should reschedule.
            closeAndCleanupJobLocked(/* reschedule */ true,
                    "host crashed when trying to inform of network change");
        }
    }

    boolean isWithinExecutionGuaranteeTime() {
        return sElapsedRealtimeClock.millis()
                < mExecutionStartTimeElapsed + mMinExecutionGuaranteeMillis;
    }

    @GuardedBy("mLock")
    boolean timeoutIfExecutingLocked(String pkgName, int userId, @Nullable String namespace,
            boolean matchJobId, int jobId, String reason) {
        final JobStatus executing = getRunningJobLocked();
        if (executing != null && (userId == UserHandle.USER_ALL || userId == executing.getUserId())
                && (pkgName == null || pkgName.equals(executing.getSourcePackageName()))
                && Objects.equals(namespace, executing.getNamespace())
                && (!matchJobId || jobId == executing.getJobId())) {
            if (mVerb == VERB_EXECUTING) {
                mParams.setStopReason(JobParameters.STOP_REASON_TIMEOUT,
                        JobParameters.INTERNAL_STOP_REASON_TIMEOUT, reason);
                sendStopMessageLocked("force timeout from shell");
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    Pair<Long, Long> getEstimatedNetworkBytes() {
        return Pair.create(mEstimatedDownloadBytes, mEstimatedUploadBytes);
    }

    @GuardedBy("mLock")
    Pair<Long, Long> getTransferredNetworkBytes() {
        return Pair.create(mTransferredDownloadBytes, mTransferredUploadBytes);
    }

    void doJobFinished(JobCallback cb, int jobId, boolean reschedule) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!verifyCallerLocked(cb)) {
                    return;
                }
                mParams.setStopReason(JobParameters.STOP_REASON_UNDEFINED,
                        JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH,
                        "app called jobFinished");
                doCallbackLocked(reschedule, "app called jobFinished");
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void doAcknowledgeGetTransferredDownloadBytesMessage(JobCallback cb, int jobId,
            int workId, @BytesLong long transferredBytes) {
        // TODO(255393346): Make sure apps call this appropriately and monitor for abuse
        synchronized (mLock) {
            if (!verifyCallerLocked(cb)) {
                return;
            }
            mTransferredDownloadBytes = transferredBytes;
        }
    }

    private void doAcknowledgeGetTransferredUploadBytesMessage(JobCallback cb, int jobId,
            int workId, @BytesLong long transferredBytes) {
        // TODO(255393346): Make sure apps call this appropriately and monitor for abuse
        synchronized (mLock) {
            if (!verifyCallerLocked(cb)) {
                return;
            }
            mTransferredUploadBytes = transferredBytes;
        }
    }

    void doAcknowledgeStopMessage(JobCallback cb, int jobId, boolean reschedule) {
        doCallback(cb, reschedule, null);
    }

    void doAcknowledgeStartMessage(JobCallback cb, int jobId, boolean ongoing) {
        doCallback(cb, ongoing, "finished start");
    }

    JobWorkItem doDequeueWork(JobCallback cb, int jobId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!assertCallerLocked(cb)) {
                    return null;
                }
                if (mVerb == VERB_STOPPING || mVerb == VERB_FINISHED) {
                    // This job is either all done, or on its way out.  Either way, it
                    // should not dispatch any more work.  We will pick up any remaining
                    // work the next time we start the job again.
                    return null;
                }
                final JobWorkItem work = mRunningJob.dequeueWorkLocked();
                if (work == null && !mRunningJob.hasExecutingWorkLocked()) {
                    mParams.setStopReason(JobParameters.STOP_REASON_UNDEFINED,
                            JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH,
                            "last work dequeued");
                    // This will finish the job.
                    doCallbackLocked(false, "last work dequeued");
                } else {
                    // Delivery count has been updated, so persist JobWorkItem change.
                    mService.mJobs.touchJob(mRunningJob);
                }
                return work;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    boolean doCompleteWork(JobCallback cb, int jobId, int workId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!assertCallerLocked(cb)) {
                    // Return true instead of false here so we don't just kick the
                    // Exception-throwing-can down the road to JobParameters.completeWork >:(
                    return true;
                }
                mService.mJobs.touchJob(mRunningJob);
                return mRunningJob.completeWorkLocked(workId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void doUpdateEstimatedNetworkBytes(JobCallback cb, int jobId,
            @Nullable JobWorkItem item, long downloadBytes, long uploadBytes) {
        // TODO(255393346): Make sure apps call this appropriately and monitor for abuse
        synchronized (mLock) {
            if (!verifyCallerLocked(cb)) {
                return;
            }
            mEstimatedDownloadBytes = downloadBytes;
            mEstimatedUploadBytes = uploadBytes;
        }
    }

    private void doUpdateTransferredNetworkBytes(JobCallback cb, int jobId,
            @Nullable JobWorkItem item, long downloadBytes, long uploadBytes) {
        // TODO(255393346): Make sure apps call this appropriately and monitor for abuse
        synchronized (mLock) {
            if (!verifyCallerLocked(cb)) {
                return;
            }
            mTransferredDownloadBytes = downloadBytes;
            mTransferredUploadBytes = uploadBytes;
        }
    }

    private void doSetNotification(JobCallback cb, int jodId, int notificationId,
            Notification notification, int jobEndNotificationPolicy) {
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!verifyCallerLocked(cb)) {
                    return;
                }
                if (callingUid != mRunningJob.getUid()) {
                    Slog.wtfStack(TAG, "Calling UID isn't the same as running job's UID...");
                    throw new SecurityException("Can't post notification on behalf of another app");
                }
                final String callingPkgName = mRunningJob.getServiceComponent().getPackageName();
                mNotificationCoordinator.enqueueNotification(this, callingPkgName,
                        callingPid, callingUid, notificationId,
                        notification, jobEndNotificationPolicy);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * We acquire/release a wakelock on onServiceConnected/unbindService. This mirrors the work
     * we intend to send to the client - we stop sending work when the service is unbound so until
     * then we keep the wakelock.
     * @param name The concrete component name of the service that has been connected.
     * @param service The IBinder of the Service's communication channel,
     */
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        JobStatus runningJob;
        synchronized (mLock) {
            // This isn't strictly necessary b/c the JobServiceHandler is running on the main
            // looper and at this point we can't get any binder callbacks from the client. Better
            // safe than sorry.
            runningJob = mRunningJob;

            if (runningJob == null || !name.equals(runningJob.getServiceComponent())) {
                closeAndCleanupJobLocked(true /* needsReschedule */,
                        "connected for different component");
                return;
            }
            this.service = IJobService.Stub.asInterface(service);
            doServiceBoundLocked();
        }
    }

    /** If the client service crashes we reschedule this job and clean up. */
    @Override
    public void onServiceDisconnected(ComponentName name) {
        synchronized (mLock) {
            closeAndCleanupJobLocked(true /* needsReschedule */, "unexpectedly disconnected");
        }
    }

    @Override
    public void onBindingDied(ComponentName name) {
        synchronized (mLock) {
            if (mRunningJob == null) {
                Slog.e(TAG, "Binding died for " + name.getPackageName()
                        + " but no running job on this context");
            } else if (mRunningJob.getServiceComponent().equals(name)) {
                Slog.e(TAG, "Binding died for "
                        + mRunningJob.getSourceUserId() + ":" + name.getPackageName());
            } else {
                Slog.e(TAG, "Binding died for " + name.getPackageName()
                        + " but context is running a different job");
            }
            closeAndCleanupJobLocked(true /* needsReschedule */, "binding died");
        }
    }

    @Override
    public void onNullBinding(ComponentName name) {
        synchronized (mLock) {
            if (mRunningJob == null) {
                Slog.wtf(TAG, "Got null binding for " + name.getPackageName()
                        + " but no running job on this context");
            } else if (mRunningJob.getServiceComponent().equals(name)) {
                Slog.wtf(TAG, "Got null binding for "
                        + mRunningJob.getSourceUserId() + ":" + name.getPackageName());
            } else {
                Slog.wtf(TAG, "Got null binding for " + name.getPackageName()
                        + " but context is running a different job");
            }
            // Don't reschedule the job since returning a null binding is an explicit choice by the
            // app which breaks things.
            closeAndCleanupJobLocked(false /* needsReschedule */, "null binding");
        }
    }

    /**
     * This class is reused across different clients, and passes itself in as a callback. Check
     * whether the client exercising the callback is the client we expect.
     * @return True if the binder calling is coming from the client we expect.
     */
    private boolean verifyCallerLocked(JobCallback cb) {
        if (mRunningCallback != cb) {
            if (DEBUG) {
                Slog.d(TAG, "Stale callback received, ignoring.");
            }
            return false;
        }
        return true;
    }

    /**
     * Will throw a {@link SecurityException} if the callback is not for the currently running job,
     * but may decide not to throw an exception if the call from the previous job appears to be an
     * accident.
     *
     * @return true if the callback is for the current job, false otherwise
     */
    private boolean assertCallerLocked(JobCallback cb) {
        if (!verifyCallerLocked(cb)) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            if (!mPreviousJobHadSuccessfulFinish
                    && (nowElapsed - mLastUnsuccessfulFinishElapsed) < 15_000L) {
                // Don't punish apps for race conditions
                return false;
            }
            // It's been long enough that the app should really not be calling into JS for the
            // stopped job.
            StringBuilder sb = new StringBuilder(128);
            sb.append("Caller no longer running");
            if (cb.mStoppedReason != null) {
                sb.append(", last stopped ");
                TimeUtils.formatDuration(nowElapsed - cb.mStoppedTime, sb);
                sb.append(" because: ");
                sb.append(cb.mStoppedReason);
            }
            throw new SecurityException(sb.toString());
        }
        return true;
    }

    /**
     * Scheduling of async messages (basically timeouts at this point).
     */
    private class JobServiceHandler extends Handler {
        JobServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_TIMEOUT:
                    synchronized (mLock) {
                        if (message.obj == mRunningCallback) {
                            handleOpTimeoutLocked();
                        } else {
                            JobCallback jc = (JobCallback)message.obj;
                            StringBuilder sb = new StringBuilder(128);
                            sb.append("Ignoring timeout of no longer active job");
                            if (jc.mStoppedReason != null) {
                                sb.append(", stopped ");
                                TimeUtils.formatDuration(sElapsedRealtimeClock.millis()
                                        - jc.mStoppedTime, sb);
                                sb.append(" because: ");
                                sb.append(jc.mStoppedReason);
                            }
                            Slog.w(TAG, sb.toString());
                        }
                    }
                    break;
                default:
                    Slog.e(TAG, "Unrecognised message: " + message);
            }
        }
    }

    @GuardedBy("mLock")
    void doServiceBoundLocked() {
        removeOpTimeOutLocked();
        handleServiceBoundLocked();
    }

    void doCallback(JobCallback cb, boolean reschedule, String reason) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!verifyCallerLocked(cb)) {
                    return;
                }
                doCallbackLocked(reschedule, reason);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mLock")
    void doCallbackLocked(boolean reschedule, String reason) {
        if (DEBUG) {
            Slog.d(TAG, "doCallback of : " + mRunningJob
                    + " v:" + VERB_STRINGS[mVerb]);
        }
        removeOpTimeOutLocked();

        if (mVerb == VERB_STARTING) {
            handleStartedLocked(reschedule);
        } else if (mVerb == VERB_EXECUTING ||
                mVerb == VERB_STOPPING) {
            handleFinishedLocked(reschedule, reason);
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Unrecognised callback: " + mRunningJob);
            }
        }
    }

    @GuardedBy("mLock")
    private void doCancelLocked(@JobParameters.StopReason int stopReasonCode,
            int internalStopReasonCode, @Nullable String debugReason) {
        if (mVerb == VERB_FINISHED || mVerb == VERB_STOPPING) {
            if (DEBUG) {
                Slog.d(TAG,
                        "Too late to process cancel for context (verb=" + mVerb + "), ignoring.");
            }
            return;
        }
        if (mRunningJob.startedAsExpeditedJob
                && stopReasonCode == JobParameters.STOP_REASON_QUOTA) {
            // EJs should be able to run for at least the min upper limit regardless of quota.
            final long earliestStopTimeElapsed =
                    mExecutionStartTimeElapsed + mMinExecutionGuaranteeMillis;
            final long nowElapsed = sElapsedRealtimeClock.millis();
            if (nowElapsed < earliestStopTimeElapsed) {
                mPendingStopReason = stopReasonCode;
                mPendingInternalStopReason = internalStopReasonCode;
                mPendingDebugStopReason = debugReason;
                return;
            }
        }
        mParams.setStopReason(stopReasonCode, internalStopReasonCode, debugReason);
        if (stopReasonCode == JobParameters.STOP_REASON_PREEMPT) {
            // Only preserve the UID when we're preempting the job for another one of the same UID.
            mPreferredUid = mRunningJob != null ? mRunningJob.getUid() : NO_PREFERRED_UID;
        }
        handleCancelLocked(debugReason);
    }

    /** Start the job on the service. */
    @GuardedBy("mLock")
    private void handleServiceBoundLocked() {
        if (DEBUG) {
            Slog.d(TAG, "handleServiceBound for " + getRunningJobNameLocked());
        }
        if (mVerb != VERB_BINDING) {
            Slog.e(TAG, "Sending onStartJob for a job that isn't pending. "
                    + VERB_STRINGS[mVerb]);
            closeAndCleanupJobLocked(false /* reschedule */, "started job not pending");
            return;
        }
        if (mCancelled) {
            if (DEBUG) {
                Slog.d(TAG, "Job cancelled while waiting for bind to complete. "
                        + mRunningJob);
            }
            closeAndCleanupJobLocked(true /* reschedule */, "cancelled while waiting for bind");
            return;
        }
        try {
            mVerb = VERB_STARTING;
            scheduleOpTimeOutLocked();
            service.startJob(mParams);
        } catch (Exception e) {
            // We catch 'Exception' because client-app malice or bugs might induce a wide
            // range of possible exception-throw outcomes from startJob() and its handling
            // of the client's ParcelableBundle extras.
            Slog.e(TAG, "Error sending onStart message to '" +
                    mRunningJob.getServiceComponent().getShortClassName() + "' ", e);
        }
    }

    /**
     * State behaviours.
     * VERB_STARTING   -> Successful start, change job to VERB_EXECUTING and post timeout.
     *     _PENDING    -> Error
     *     _EXECUTING  -> Error
     *     _STOPPING   -> Error
     */
    @GuardedBy("mLock")
    private void handleStartedLocked(boolean workOngoing) {
        switch (mVerb) {
            case VERB_STARTING:
                mVerb = VERB_EXECUTING;
                if (!workOngoing) {
                    // Job is finished already so fast-forward to handleFinished.
                    handleFinishedLocked(false, "onStartJob returned false");
                    return;
                }
                if (mCancelled) {
                    if (DEBUG) {
                        Slog.d(TAG, "Job cancelled while waiting for onStartJob to complete.");
                    }
                    // Cancelled *while* waiting for acknowledgeStartMessage from client.
                    handleCancelLocked(null);
                    return;
                }
                scheduleOpTimeOutLocked();
                if (mPendingNetworkChange != null
                        && !Objects.equals(mParams.getNetwork(), mPendingNetworkChange)) {
                    informOfNetworkChangeLocked(mPendingNetworkChange);
                }
                if (mRunningJob.isUserVisibleJob()) {
                    mService.informObserversOfUserVisibleJobChange(this, mRunningJob, true);
                }
                break;
            default:
                Slog.e(TAG, "Handling started job but job wasn't starting! Was "
                        + VERB_STRINGS[mVerb] + ".");
                return;
        }
    }

    /**
     * VERB_EXECUTING  -> Client called jobFinished(), clean up and notify done.
     *     _STOPPING   -> Successful finish, clean up and notify done.
     *     _STARTING   -> Error
     *     _PENDING    -> Error
     */
    @GuardedBy("mLock")
    private void handleFinishedLocked(boolean reschedule, String reason) {
        switch (mVerb) {
            case VERB_EXECUTING:
            case VERB_STOPPING:
                closeAndCleanupJobLocked(reschedule, reason);
                break;
            default:
                Slog.e(TAG, "Got an execution complete message for a job that wasn't being" +
                        "executed. Was " + VERB_STRINGS[mVerb] + ".");
        }
    }

    /**
     * A job can be in various states when a cancel request comes in:
     * VERB_BINDING    -> Cancelled before bind completed. Mark as cancelled and wait for
     *                    {@link #onServiceConnected(android.content.ComponentName, android.os.IBinder)}
     *     _STARTING   -> Mark as cancelled and wait for
     *                    {@link JobServiceContext#doAcknowledgeStartMessage}
     *     _EXECUTING  -> call {@link #sendStopMessageLocked}}, but only if there are no callbacks
     *                      in the message queue.
     *     _ENDING     -> No point in doing anything here, so we ignore.
     */
    @GuardedBy("mLock")
    private void handleCancelLocked(@Nullable String reason) {
        if (JobSchedulerService.DEBUG) {
            Slog.d(TAG, "Handling cancel for: " + mRunningJob.getJobId() + " "
                    + VERB_STRINGS[mVerb]);
        }
        switch (mVerb) {
            case VERB_BINDING:
            case VERB_STARTING:
                mCancelled = true;
                applyStoppedReasonLocked(reason);
                break;
            case VERB_EXECUTING:
                sendStopMessageLocked(reason);
                break;
            case VERB_STOPPING:
                // Nada.
                break;
            default:
                Slog.e(TAG, "Cancelling a job without a valid verb: " + mVerb);
                break;
        }
    }

    /** Process MSG_TIMEOUT here. */
    @GuardedBy("mLock")
    private void handleOpTimeoutLocked() {
        switch (mVerb) {
            case VERB_BINDING:
                Slog.w(TAG, "Time-out while trying to bind " + getRunningJobNameLocked()
                        + ", dropping.");
                closeAndCleanupJobLocked(false /* needsReschedule */, "timed out while binding");
                break;
            case VERB_STARTING:
                // Client unresponsive - wedged or failed to respond in time. We don't really
                // know what happened so let's log it and notify the JobScheduler
                // FINISHED/NO-RETRY.
                Slog.w(TAG, "No response from client for onStartJob "
                        + getRunningJobNameLocked());
                closeAndCleanupJobLocked(false /* needsReschedule */, "timed out while starting");
                break;
            case VERB_STOPPING:
                // At least we got somewhere, so fail but ask the JobScheduler to reschedule.
                Slog.w(TAG, "No response from client for onStopJob "
                        + getRunningJobNameLocked());
                closeAndCleanupJobLocked(true /* needsReschedule */, "timed out while stopping");
                break;
            case VERB_EXECUTING:
                if (mPendingStopReason != JobParameters.STOP_REASON_UNDEFINED) {
                    if (mService.isReadyToBeExecutedLocked(mRunningJob, false)) {
                        // Job became ready again while we were waiting to stop it (for example,
                        // the device was temporarily taken off the charger). Ignore the pending
                        // stop and see what the manager says.
                        mPendingStopReason = JobParameters.STOP_REASON_UNDEFINED;
                        mPendingInternalStopReason = 0;
                        mPendingDebugStopReason = null;
                    } else {
                        Slog.i(TAG, "JS was waiting to stop this job."
                                + " Sending onStop: " + getRunningJobNameLocked());
                        mParams.setStopReason(mPendingStopReason, mPendingInternalStopReason,
                                mPendingDebugStopReason);
                        sendStopMessageLocked(mPendingDebugStopReason);
                        break;
                    }
                }
                final long latestStopTimeElapsed =
                        mExecutionStartTimeElapsed + mMaxExecutionTimeMillis;
                final long nowElapsed = sElapsedRealtimeClock.millis();
                if (nowElapsed >= latestStopTimeElapsed) {
                    // Not an error - client ran out of time.
                    Slog.i(TAG, "Client timed out while executing (no jobFinished received)."
                            + " Sending onStop: " + getRunningJobNameLocked());
                    mParams.setStopReason(JobParameters.STOP_REASON_TIMEOUT,
                            JobParameters.INTERNAL_STOP_REASON_TIMEOUT, "client timed out");
                    sendStopMessageLocked("timeout while executing");
                } else {
                    // We've given the app the minimum execution time. See if we should stop it or
                    // let it continue running
                    final String reason = mJobConcurrencyManager.shouldStopRunningJobLocked(this);
                    if (reason != null) {
                        Slog.i(TAG, "Stopping client after min execution time: "
                                + getRunningJobNameLocked() + " because " + reason);
                        // Tell the developer we're stopping the job due to device state instead
                        // of timeout since all of the reasons could equate to "the system needs
                        // the resources the app is currently using."
                        mParams.setStopReason(JobParameters.STOP_REASON_DEVICE_STATE,
                                JobParameters.INTERNAL_STOP_REASON_TIMEOUT, reason);
                        sendStopMessageLocked(reason);
                    } else {
                        Slog.i(TAG, "Letting " + getRunningJobNameLocked()
                                + " continue to run past min execution time");
                        scheduleOpTimeOutLocked();
                    }
                }
                break;
            default:
                Slog.e(TAG, "Handling timeout for an invalid job state: "
                        + getRunningJobNameLocked() + ", dropping.");
                closeAndCleanupJobLocked(false /* needsReschedule */, "invalid timeout");
        }
    }

    /**
     * Already running, need to stop. Will switch {@link #mVerb} from VERB_EXECUTING ->
     * VERB_STOPPING.
     */
    @GuardedBy("mLock")
    private void sendStopMessageLocked(@Nullable String reason) {
        removeOpTimeOutLocked();
        if (mVerb != VERB_EXECUTING) {
            Slog.e(TAG, "Sending onStopJob for a job that isn't started. " + mRunningJob);
            closeAndCleanupJobLocked(false /* reschedule */, reason);
            return;
        }
        try {
            applyStoppedReasonLocked(reason);
            mVerb = VERB_STOPPING;
            scheduleOpTimeOutLocked();
            service.stopJob(mParams);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error sending onStopJob to client.", e);
            // The job's host app apparently crashed during the job, so we should reschedule.
            closeAndCleanupJobLocked(true /* reschedule */, "host crashed when trying to stop");
        }
    }

    /**
     * The provided job has finished, either by calling
     * {@link android.app.job.JobService#jobFinished(android.app.job.JobParameters, boolean)}
     * or from acknowledging the stop message we sent. Either way, we're done tracking it and
     * we want to clean up internally.
     */
    @GuardedBy("mLock")
    private void closeAndCleanupJobLocked(boolean reschedule, @Nullable String reason) {
        final JobStatus completedJob;
        if (mVerb == VERB_FINISHED) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Cleaning up " + mRunningJob.toShortString()
                    + " reschedule=" + reschedule + " reason=" + reason);
        }
        applyStoppedReasonLocked(reason);
        completedJob = mRunningJob;
        final int internalStopReason = mParams.getInternalStopReasonCode();
        mPreviousJobHadSuccessfulFinish =
                (internalStopReason == JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH);
        if (!mPreviousJobHadSuccessfulFinish) {
            mLastUnsuccessfulFinishElapsed = sElapsedRealtimeClock.millis();
        }
        mJobPackageTracker.noteInactive(completedJob, internalStopReason, reason);
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SCHEDULED_JOB_STATE_CHANGED,
                completedJob.getSourceUid(), null, completedJob.getBatteryName(),
                FrameworkStatsLog.SCHEDULED_JOB_STATE_CHANGED__STATE__FINISHED,
                internalStopReason, completedJob.getStandbyBucket(), completedJob.getJobId(),
                completedJob.hasChargingConstraint(),
                completedJob.hasBatteryNotLowConstraint(),
                completedJob.hasStorageNotLowConstraint(),
                completedJob.hasTimingDelayConstraint(),
                completedJob.hasDeadlineConstraint(),
                completedJob.hasIdleConstraint(),
                completedJob.hasConnectivityConstraint(),
                completedJob.hasContentTriggerConstraint(),
                completedJob.isRequestedExpeditedJob(),
                completedJob.startedAsExpeditedJob,
                mParams.getStopReason(),
                completedJob.getJob().isPrefetch(),
                completedJob.getJob().getPriority(),
                completedJob.getEffectivePriority(),
                completedJob.getNumPreviousAttempts(),
                completedJob.getJob().getMaxExecutionDelayMillis(),
                mParams.isOverrideDeadlineExpired(),
                completedJob.isConstraintSatisfied(JobInfo.CONSTRAINT_FLAG_CHARGING),
                completedJob.isConstraintSatisfied(JobInfo.CONSTRAINT_FLAG_BATTERY_NOT_LOW),
                completedJob.isConstraintSatisfied(JobInfo.CONSTRAINT_FLAG_STORAGE_NOT_LOW),
                completedJob.isConstraintSatisfied(JobStatus.CONSTRAINT_TIMING_DELAY),
                completedJob.isConstraintSatisfied(JobInfo.CONSTRAINT_FLAG_DEVICE_IDLE),
                completedJob.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY),
                completedJob.isConstraintSatisfied(JobStatus.CONSTRAINT_CONTENT_TRIGGER));
        if (Trace.isTagEnabled(Trace.TRACE_TAG_SYSTEM_SERVER)) {
            Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_SYSTEM_SERVER, "JobScheduler",
                    getId());
        }
        try {
            mBatteryStats.noteJobFinish(mRunningJob.getBatteryName(), mRunningJob.getSourceUid(),
                    internalStopReason);
        } catch (RemoteException e) {
            // Whatever.
        }
        if (mParams.getStopReason() == JobParameters.STOP_REASON_TIMEOUT) {
            mEconomyManagerInternal.noteInstantaneousEvent(
                    mRunningJob.getSourceUserId(), mRunningJob.getSourcePackageName(),
                    JobSchedulerEconomicPolicy.ACTION_JOB_TIMEOUT,
                    String.valueOf(mRunningJob.getJobId()));
        }
        mNotificationCoordinator.removeNotificationAssociation(this);
        if (mWakeLock != null) {
            mWakeLock.release();
        }
        final int workType = mRunningJobWorkType;
        mContext.unbindService(JobServiceContext.this);
        mWakeLock = null;
        mRunningJob = null;
        mRunningJobWorkType = WORK_TYPE_NONE;
        mRunningCallback = null;
        mParams = null;
        mVerb = VERB_FINISHED;
        mCancelled = false;
        service = null;
        mAvailable = true;
        mPendingStopReason = JobParameters.STOP_REASON_UNDEFINED;
        mPendingInternalStopReason = 0;
        mPendingDebugStopReason = null;
        mPendingNetworkChange = null;
        removeOpTimeOutLocked();
        if (completedJob.isUserVisibleJob()) {
            mService.informObserversOfUserVisibleJobChange(this, completedJob, false);
        }
        mCompletedListener.onJobCompletedLocked(completedJob, internalStopReason, reschedule);
        mJobConcurrencyManager.onJobCompletedLocked(this, completedJob, workType);
    }

    private void applyStoppedReasonLocked(@Nullable String reason) {
        if (reason != null && mStoppedReason == null) {
            mStoppedReason = reason;
            mStoppedTime = sElapsedRealtimeClock.millis();
            if (mRunningCallback != null) {
                mRunningCallback.mStoppedReason = mStoppedReason;
                mRunningCallback.mStoppedTime = mStoppedTime;
            }
        }
    }

    /**
     * Called when sending a message to the client, over whose execution we have no control. If
     * we haven't received a response in a certain amount of time, we want to give up and carry
     * on with life.
     */
    private void scheduleOpTimeOutLocked() {
        removeOpTimeOutLocked();

        // TODO(260848384): enforce setNotification timeout for user-initiated jobs
        final long timeoutMillis;
        switch (mVerb) {
            case VERB_EXECUTING:
                final long earliestStopTimeElapsed =
                        mExecutionStartTimeElapsed + mMinExecutionGuaranteeMillis;
                final long latestStopTimeElapsed =
                        mExecutionStartTimeElapsed + mMaxExecutionTimeMillis;
                final long nowElapsed = sElapsedRealtimeClock.millis();
                if (nowElapsed < earliestStopTimeElapsed) {
                    timeoutMillis = earliestStopTimeElapsed - nowElapsed;
                } else {
                    timeoutMillis = latestStopTimeElapsed - nowElapsed;
                }
                break;

            case VERB_BINDING:
                timeoutMillis = OP_BIND_TIMEOUT_MILLIS;
                break;

            default:
                timeoutMillis = OP_TIMEOUT_MILLIS;
                break;
        }
        if (DEBUG) {
            Slog.d(TAG, "Scheduling time out for '" +
                    mRunningJob.getServiceComponent().getShortClassName() + "' jId: " +
                    mParams.getJobId() + ", in " + (timeoutMillis / 1000) + " s");
        }
        Message m = mCallbackHandler.obtainMessage(MSG_TIMEOUT, mRunningCallback);
        mCallbackHandler.sendMessageDelayed(m, timeoutMillis);
        mTimeoutElapsed = sElapsedRealtimeClock.millis() + timeoutMillis;
    }

    private void removeOpTimeOutLocked() {
        mCallbackHandler.removeMessages(MSG_TIMEOUT);
    }

    void dumpLocked(IndentingPrintWriter pw, final long nowElapsed) {
        if (mRunningJob == null) {
            if (mStoppedReason != null) {
                pw.print("inactive since ");
                TimeUtils.formatDuration(mStoppedTime, nowElapsed, pw);
                pw.print(", stopped because: ");
                pw.println(mStoppedReason);
            } else {
                pw.println("inactive");
            }
        } else {
            pw.println(mRunningJob.toShortString());

            pw.increaseIndent();
            pw.print("Running for: ");
            TimeUtils.formatDuration(nowElapsed - mExecutionStartTimeElapsed, pw);
            pw.print(", timeout at: ");
            TimeUtils.formatDuration(mTimeoutElapsed - nowElapsed, pw);
            pw.println();
            pw.print("Remaining execution limits: [");
            TimeUtils.formatDuration(
                    (mExecutionStartTimeElapsed + mMinExecutionGuaranteeMillis) - nowElapsed, pw);
            pw.print(", ");
            TimeUtils.formatDuration(
                    (mExecutionStartTimeElapsed + mMaxExecutionTimeMillis) - nowElapsed, pw);
            pw.print("]");
            if (mPendingStopReason != JobParameters.STOP_REASON_UNDEFINED) {
                pw.print(" Pending stop because ");
                pw.print(mPendingStopReason);
                pw.print("/");
                pw.print(mPendingInternalStopReason);
                pw.print("/");
                pw.print(mPendingDebugStopReason);
            }
            pw.println();
            pw.decreaseIndent();
        }
    }
}
