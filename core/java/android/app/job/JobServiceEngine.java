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

package android.app.job;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;

/**
 * Helper for implementing a {@link android.app.Service} that interacts with
 * {@link JobScheduler}.  This is not intended for use by regular applications, but
 * allows frameworks built on top of the platform to create their own
 * {@link android.app.Service} that interact with {@link JobScheduler} as well as
 * add in additional functionality.  If you just want to execute jobs normally, you
 * should instead be looking at {@link JobService}.
 */
public abstract class JobServiceEngine {
    private static final String TAG = "JobServiceEngine";

    /**
     * Identifier for a message that will result in a call to
     * {@link #onStartJob(android.app.job.JobParameters)}.
     */
    private static final int MSG_EXECUTE_JOB = 0;
    /**
     * Message that will result in a call to {@link #onStopJob(android.app.job.JobParameters)}.
     */
    private static final int MSG_STOP_JOB = 1;
    /**
     * Message that the client has completed execution of this job.
     */
    private static final int MSG_JOB_FINISHED = 2;

    private final IJobService mBinder;

    /**
     * Handler we post jobs to. Responsible for calling into the client logic, and handling the
     * callback to the system.
     */
    JobHandler mHandler;

    static final class JobInterface extends IJobService.Stub {
        final WeakReference<JobServiceEngine> mService;

        JobInterface(JobServiceEngine service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void startJob(JobParameters jobParams) throws RemoteException {
            JobServiceEngine service = mService.get();
            if (service != null) {
                Message m = Message.obtain(service.mHandler, MSG_EXECUTE_JOB, jobParams);
                m.sendToTarget();
            }
        }

        @Override
        public void stopJob(JobParameters jobParams) throws RemoteException {
            JobServiceEngine service = mService.get();
            if (service != null) {
                Message m = Message.obtain(service.mHandler, MSG_STOP_JOB, jobParams);
                m.sendToTarget();
            }
        }
    }

    /**
     * Runs on application's main thread - callbacks are meant to offboard work to some other
     * (app-specified) mechanism.
     * @hide
     */
    class JobHandler extends Handler {
        JobHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final JobParameters params = (JobParameters) msg.obj;
            switch (msg.what) {
                case MSG_EXECUTE_JOB:
                    try {
                        boolean workOngoing = JobServiceEngine.this.onStartJob(params);
                        ackStartMessage(params, workOngoing);
                    } catch (Exception e) {
                        Log.e(TAG, "Error while executing job: " + params.getJobId());
                        throw new RuntimeException(e);
                    }
                    break;
                case MSG_STOP_JOB:
                    try {
                        boolean ret = JobServiceEngine.this.onStopJob(params);
                        ackStopMessage(params, ret);
                    } catch (Exception e) {
                        Log.e(TAG, "Application unable to handle onStopJob.", e);
                        throw new RuntimeException(e);
                    }
                    break;
                case MSG_JOB_FINISHED:
                    final boolean needsReschedule = (msg.arg2 == 1);
                    IJobCallback callback = params.getCallback();
                    if (callback != null) {
                        try {
                            callback.jobFinished(params.getJobId(), needsReschedule);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error reporting job finish to system: binder has gone" +
                                    "away.");
                        }
                    } else {
                        Log.e(TAG, "finishJob() called for a nonexistent job id.");
                    }
                    break;
                default:
                    Log.e(TAG, "Unrecognised message received.");
                    break;
            }
        }

        private void ackStartMessage(JobParameters params, boolean workOngoing) {
            final IJobCallback callback = params.getCallback();
            final int jobId = params.getJobId();
            if (callback != null) {
                try {
                    callback.acknowledgeStartMessage(jobId, workOngoing);
                } catch(RemoteException e) {
                    Log.e(TAG, "System unreachable for starting job.");
                }
            } else {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Attempting to ack a job that has already been processed.");
                }
            }
        }

        private void ackStopMessage(JobParameters params, boolean reschedule) {
            final IJobCallback callback = params.getCallback();
            final int jobId = params.getJobId();
            if (callback != null) {
                try {
                    callback.acknowledgeStopMessage(jobId, reschedule);
                } catch(RemoteException e) {
                    Log.e(TAG, "System unreachable for stopping job.");
                }
            } else {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Attempting to ack a job that has already been processed.");
                }
            }
        }
    }

    /**
     * Create a new engine, ready for use.
     *
     * @param service The {@link Service} that is creating this engine and in which it will run.
     */
    public JobServiceEngine(Service service) {
        mBinder = new JobInterface(this);
        mHandler = new JobHandler(service.getMainLooper());
    }

    /**
     * Retrieve the engine's IPC interface that should be returned by
     * {@link Service#onBind(Intent)}.
     */
    public final IBinder getBinder() {
        return mBinder.asBinder();
    }

    /**
     * Engine's report that a job has started.  See
     * {@link JobService#onStartJob(JobParameters) JobService.onStartJob} for more information.
     */
    public abstract boolean onStartJob(JobParameters params);

    /**
     * Engine's report that a job has stopped.  See
     * {@link JobService#onStopJob(JobParameters) JobService.onStopJob} for more information.
     */
    public abstract boolean onStopJob(JobParameters params);

    /**
     * Call in to engine to report that a job has finished executing.  See
     * {@link JobService#jobFinished(JobParameters, boolean)}  JobService.jobFinished} for more
     * information.
     */
    public void jobFinished(JobParameters params, boolean needsReschedule) {
        if (params == null) {
            throw new NullPointerException("params");
        }
        Message m = Message.obtain(mHandler, MSG_JOB_FINISHED, params);
        m.arg2 = needsReschedule ? 1 : 0;
        m.sendToTarget();
    }
}