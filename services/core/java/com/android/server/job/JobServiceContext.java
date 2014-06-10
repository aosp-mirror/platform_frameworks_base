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

import android.app.ActivityManager;
import android.app.job.JobParameters;
import android.app.job.IJobCallback;
import android.app.job.IJobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.job.controllers.JobStatus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles client binding and lifecycle of a job. A job will only execute one at a time on an
 * instance of this class.
 */
public class JobServiceContext extends IJobCallback.Stub implements ServiceConnection {
    private static final boolean DEBUG = true;
    private static final String TAG = "JobServiceContext";
    /** Define the maximum # of jobs allowed to run on a service at once. */
    private static final int defaultMaxActiveJobsPerService =
            ActivityManager.isLowRamDeviceStatic() ? 1 : 3;
    /** Amount of time a job is allowed to execute for before being considered timed-out. */
    private static final long EXECUTING_TIMESLICE_MILLIS = 60 * 1000;
    /** Amount of time the JobScheduler will wait for a response from an app for a message. */
    private static final long OP_TIMEOUT_MILLIS = 8 * 1000;
    /** String prefix for all wakelock names. */
    private static final String JS_WAKELOCK_PREFIX = "*job*/";

    private static final String[] VERB_STRINGS = {
            "VERB_STARTING", "VERB_EXECUTING", "VERB_STOPPING", "VERB_PENDING"
    };

    // States that a job occupies while interacting with the client.
    static final int VERB_BINDING = 0;
    static final int VERB_STARTING = 1;
    static final int VERB_EXECUTING = 2;
    static final int VERB_STOPPING = 3;

    // Messages that result from interactions with the client service.
    /** System timed out waiting for a response. */
    private static final int MSG_TIMEOUT = 0;
    /** Received a callback from client. */
    private static final int MSG_CALLBACK = 1;
    /** Run through list and start any ready jobs.*/
    private static final int MSG_SERVICE_BOUND = 2;
    /** Cancel a job. */
    private static final int MSG_CANCEL = 3;
    /** Shutdown the job. Used when the client crashes and we can't die gracefully.*/
    private static final int MSG_SHUTDOWN_EXECUTION = 4;

    private final Handler mCallbackHandler;
    /** Make callbacks to {@link JobSchedulerService} to inform on job completion status. */
    private final JobCompletedListener mCompletedListener;
    /** Used for service binding, etc. */
    private final Context mContext;
    private PowerManager.WakeLock mWakeLock;

    // Execution state.
    private JobParameters mParams;
    @VisibleForTesting
    int mVerb;
    private AtomicBoolean mCancelled = new AtomicBoolean();

    /** All the information maintained about the job currently being executed. */
    private JobStatus mRunningJob;
    /** Binder to the client service. */
    IJobService service;

    private final Object mLock = new Object();
    /** Whether this context is free. */
    @GuardedBy("mLock")
    private boolean mAvailable;
    /** Track start time. */
    private long mExecutionStartTimeElapsed;
    /** Track when job will timeout. */
    private long mTimeoutElapsed;

    JobServiceContext(JobSchedulerService service, Looper looper) {
        this(service.getContext(), service, looper);
    }

    @VisibleForTesting
    JobServiceContext(Context context, JobCompletedListener completedListener, Looper looper) {
        mContext = context;
        mCallbackHandler = new JobServiceHandler(looper);
        mCompletedListener = completedListener;
        mAvailable = true;
    }

    /**
     * Give a job to this context for execution. Callers must first check {@link #isAvailable()}
     * to make sure this is a valid context.
     * @param job The status of the job that we are going to run.
     * @return True if the job is valid and is running. False if the job cannot be executed.
     */
    boolean executeRunnableJob(JobStatus job) {
        synchronized (mLock) {
            if (!mAvailable) {
                Slog.e(TAG, "Starting new runnable but context is unavailable > Error.");
                return false;
            }

            mRunningJob = job;
            mParams = new JobParameters(job.getJobId(), job.getExtras(), this);
            mExecutionStartTimeElapsed = SystemClock.elapsedRealtime();

            mVerb = VERB_BINDING;
            final Intent intent = new Intent().setComponent(job.getServiceComponent());
            boolean binding = mContext.bindServiceAsUser(intent, this,
                    Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND,
                    new UserHandle(job.getUserId()));
            if (!binding) {
                if (DEBUG) {
                    Slog.d(TAG, job.getServiceComponent().getShortClassName() + " unavailable.");
                }
                mRunningJob = null;
                mParams = null;
                mExecutionStartTimeElapsed = 0L;
                return false;
            }
            mAvailable = false;
            return true;
        }
    }

    /** Used externally to query the running job. Will return null if there is no job running. */
    JobStatus getRunningJob() {
        return mRunningJob;
    }

    /** Called externally when a job that was scheduled for execution should be cancelled. */
    void cancelExecutingJob() {
        mCallbackHandler.obtainMessage(MSG_CANCEL).sendToTarget();
    }

    /**
     * @return Whether this context is available to handle incoming work.
     */
    boolean isAvailable() {
        synchronized (mLock) {
            return mAvailable;
        }
    }

    long getExecutionStartTimeElapsed() {
        return mExecutionStartTimeElapsed;
    }

    long getTimeoutElapsed() {
        return mTimeoutElapsed;
    }

    @Override
    public void jobFinished(int jobId, boolean reschedule) {
        if (!verifyCallingUid()) {
            return;
        }
        mCallbackHandler.obtainMessage(MSG_CALLBACK, jobId, reschedule ? 1 : 0)
                .sendToTarget();
    }

    @Override
    public void acknowledgeStopMessage(int jobId, boolean reschedule) {
        if (!verifyCallingUid()) {
            return;
        }
        mCallbackHandler.obtainMessage(MSG_CALLBACK, jobId, reschedule ? 1 : 0)
                .sendToTarget();
    }

    @Override
    public void acknowledgeStartMessage(int jobId, boolean ongoing) {
        if (!verifyCallingUid()) {
            return;
        }
        mCallbackHandler.obtainMessage(MSG_CALLBACK, jobId, ongoing ? 1 : 0).sendToTarget();
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
        if (!name.equals(mRunningJob.getServiceComponent())) {
            mCallbackHandler.obtainMessage(MSG_SHUTDOWN_EXECUTION).sendToTarget();
            return;
        }
        this.service = IJobService.Stub.asInterface(service);
        // Remove all timeouts.
        mCallbackHandler.removeMessages(MSG_TIMEOUT);
        final PowerManager pm =
                (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                JS_WAKELOCK_PREFIX + mRunningJob.getServiceComponent().getPackageName());
        mWakeLock.setWorkSource(new WorkSource(mRunningJob.getUid()));
        mWakeLock.setReferenceCounted(false);
        mWakeLock.acquire();
        mCallbackHandler.obtainMessage(MSG_SERVICE_BOUND).sendToTarget();
    }

    /**
     * If the client service crashes we reschedule this job and clean up.
     * @param name The concrete component name of the service whose
     */
    @Override
    public void onServiceDisconnected(ComponentName name) {
        mCallbackHandler.obtainMessage(MSG_SHUTDOWN_EXECUTION).sendToTarget();
    }

    /**
     * This class is reused across different clients, and passes itself in as a callback. Check
     * whether the client exercising the callback is the client we expect.
     * @return True if the binder calling is coming from the client we expect.
     */
    private boolean verifyCallingUid() {
        if (mRunningJob == null || Binder.getCallingUid() != mRunningJob.getUid()) {
            if (DEBUG) {
                Slog.d(TAG, "Stale callback received, ignoring.");
            }
            return false;
        }
        return true;
    }

    /**
     * Handles the lifecycle of the JobService binding/callbacks, etc. The convention within this
     * class is to append 'H' to each function name that can only be called on this handler. This
     * isn't strictly necessary because all of these functions are private, but helps clarity.
     */
    private class JobServiceHandler extends Handler {
        JobServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_SERVICE_BOUND:
                    handleServiceBoundH();
                    break;
                case MSG_CALLBACK:
                    if (DEBUG) {
                        Slog.d(TAG, "MSG_CALLBACK of : " + mRunningJob + " v:" +
                                VERB_STRINGS[mVerb]);
                    }
                    removeMessages(MSG_TIMEOUT);

                    if (mVerb == VERB_STARTING) {
                        final boolean workOngoing = message.arg2 == 1;
                        handleStartedH(workOngoing);
                    } else if (mVerb == VERB_EXECUTING ||
                            mVerb == VERB_STOPPING) {
                        final boolean reschedule = message.arg2 == 1;
                        handleFinishedH(reschedule);
                    } else {
                        if (DEBUG) {
                            Slog.d(TAG, "Unrecognised callback: " + mRunningJob);
                        }
                    }
                    break;
                case MSG_CANCEL:
                    handleCancelH();
                    break;
                case MSG_TIMEOUT:
                    handleOpTimeoutH();
                    break;
                case MSG_SHUTDOWN_EXECUTION:
                    closeAndCleanupJobH(true /* needsReschedule */);
                    break;
                default:
                    Log.e(TAG, "Unrecognised message: " + message);
            }
        }

        /** Start the job on the service. */
        private void handleServiceBoundH() {
            if (mVerb != VERB_BINDING) {
                Slog.e(TAG, "Sending onStartJob for a job that isn't pending. "
                        + VERB_STRINGS[mVerb]);
                closeAndCleanupJobH(false /* reschedule */);
                return;
            }
            if (mCancelled.get()) {
                if (DEBUG) {
                    Slog.d(TAG, "Job cancelled while waiting for bind to complete. "
                            + mRunningJob);
                }
                closeAndCleanupJobH(true /* reschedule */);
                return;
            }
            try {
                mVerb = VERB_STARTING;
                scheduleOpTimeOut();
                service.startJob(mParams);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending onStart message to '" +
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
        private void handleStartedH(boolean workOngoing) {
            switch (mVerb) {
                case VERB_STARTING:
                    mVerb = VERB_EXECUTING;
                    if (!workOngoing) {
                        // Job is finished already so fast-forward to handleFinished.
                        handleFinishedH(false);
                        return;
                    }
                    if (mCancelled.get()) {
                        // Cancelled *while* waiting for acknowledgeStartMessage from client.
                        handleCancelH();
                        return;
                    }
                    scheduleOpTimeOut();
                    break;
                default:
                    Log.e(TAG, "Handling started job but job wasn't starting! Was "
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
        private void handleFinishedH(boolean reschedule) {
            switch (mVerb) {
                case VERB_EXECUTING:
                case VERB_STOPPING:
                    closeAndCleanupJobH(reschedule);
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
         *                    {@link JobServiceContext#acknowledgeStartMessage(int, boolean)}
         *     _EXECUTING  -> call {@link #sendStopMessageH}}.
         *     _ENDING     -> No point in doing anything here, so we ignore.
         */
        private void handleCancelH() {
            switch (mVerb) {
                case VERB_BINDING:
                case VERB_STARTING:
                    mCancelled.set(true);
                    break;
                case VERB_EXECUTING:
                    sendStopMessageH();
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
        private void handleOpTimeoutH() {
            if (Log.isLoggable(JobSchedulerService.TAG, Log.DEBUG)) {
                Log.d(TAG, "MSG_TIMEOUT of " +
                        mRunningJob.getServiceComponent().getShortClassName() + " : "
                        + mParams.getJobId());
            }

            final int jobId = mParams.getJobId();
            switch (mVerb) {
                case VERB_STARTING:
                    // Client unresponsive - wedged or failed to respond in time. We don't really
                    // know what happened so let's log it and notify the JobScheduler
                    // FINISHED/NO-RETRY.
                    Log.e(TAG, "No response from client for onStartJob '" +
                            mRunningJob.getServiceComponent().getShortClassName() + "' tId: "
                            + jobId);
                    closeAndCleanupJobH(false /* needsReschedule */);
                    break;
                case VERB_STOPPING:
                    // At least we got somewhere, so fail but ask the JobScheduler to reschedule.
                    Log.e(TAG, "No response from client for onStopJob, '" +
                            mRunningJob.getServiceComponent().getShortClassName() + "' tId: "
                            + jobId);
                    closeAndCleanupJobH(true /* needsReschedule */);
                    break;
                case VERB_EXECUTING:
                    // Not an error - client ran out of time.
                    Log.i(TAG, "Client timed out while executing (no jobFinished received)." +
                            " sending onStop. "  +
                            mRunningJob.getServiceComponent().getShortClassName() + "' tId: "
                            + jobId);
                    sendStopMessageH();
                    break;
                default:
                    Log.e(TAG, "Handling timeout for an unknown active job state: "
                            + mRunningJob);
                    return;
            }
        }

        /**
         * Already running, need to stop. Will switch {@link #mVerb} from VERB_EXECUTING ->
         * VERB_STOPPING.
         */
        private void sendStopMessageH() {
            mCallbackHandler.removeMessages(MSG_TIMEOUT);
            if (mVerb != VERB_EXECUTING) {
                Log.e(TAG, "Sending onStopJob for a job that isn't started. " + mRunningJob);
                closeAndCleanupJobH(false /* reschedule */);
                return;
            }
            try {
                mVerb = VERB_STOPPING;
                scheduleOpTimeOut();
                service.stopJob(mParams);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending onStopJob to client.", e);
                closeAndCleanupJobH(false /* reschedule */);
            }
        }

        /**
         * The provided job has finished, either by calling
         * {@link android.app.job.JobService#jobFinished(android.app.job.JobParameters, boolean)}
         * or from acknowledging the stop message we sent. Either way, we're done tracking it and
         * we want to clean up internally.
         */
        private void closeAndCleanupJobH(boolean reschedule) {
            removeMessages(MSG_TIMEOUT);
            synchronized (mLock) {
                mWakeLock.release();
                mContext.unbindService(JobServiceContext.this);
                mCompletedListener.onJobCompleted(mRunningJob, reschedule);

                mWakeLock = null;
                mRunningJob = null;
                mParams = null;
                mVerb = -1;
                mCancelled.set(false);
                service = null;
                mAvailable = true;
            }
        }

        /**
         * Called when sending a message to the client, over whose execution we have no control. If we
         * haven't received a response in a certain amount of time, we want to give up and carry on
         * with life.
         */
        private void scheduleOpTimeOut() {
            mCallbackHandler.removeMessages(MSG_TIMEOUT);

            final long timeoutMillis = (mVerb == VERB_EXECUTING) ?
                    EXECUTING_TIMESLICE_MILLIS : OP_TIMEOUT_MILLIS;
            if (DEBUG) {
                Slog.d(TAG, "Scheduling time out for '" +
                        mRunningJob.getServiceComponent().getShortClassName() + "' tId: " +
                        mParams.getJobId() + ", in " + (timeoutMillis / 1000) + " s");
            }
            Message m = mCallbackHandler.obtainMessage(MSG_TIMEOUT);
            mCallbackHandler.sendMessageDelayed(m, timeoutMillis);
            mTimeoutElapsed = SystemClock.elapsedRealtime() + timeoutMillis;
        }
    }
}
