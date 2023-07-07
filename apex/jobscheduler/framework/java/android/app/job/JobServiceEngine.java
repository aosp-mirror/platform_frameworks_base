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

import static android.app.job.JobScheduler.THROW_ON_INVALID_DATA_TRANSFER_IMPLEMENTATION;

import android.annotation.BytesLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.Service;
import android.compat.Compatibility;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.os.SomeArgs;

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
    /**
     * Message that will result in a call to
     * {@link #getTransferredDownloadBytes(JobParameters, JobWorkItem)}.
     */
    private static final int MSG_GET_TRANSFERRED_DOWNLOAD_BYTES = 3;
    /**
     * Message that will result in a call to
     * {@link #getTransferredUploadBytes(JobParameters, JobWorkItem)}.
     */
    private static final int MSG_GET_TRANSFERRED_UPLOAD_BYTES = 4;
    /** Message that the client wants to update JobScheduler of the data transfer progress. */
    private static final int MSG_UPDATE_TRANSFERRED_NETWORK_BYTES = 5;
    /** Message that the client wants to update JobScheduler of the estimated transfer size. */
    private static final int MSG_UPDATE_ESTIMATED_NETWORK_BYTES = 6;
    /** Message that the client wants to give JobScheduler a notification to tie to the job. */
    private static final int MSG_SET_NOTIFICATION = 7;
    /** Message that the network to use has changed. */
    private static final int MSG_INFORM_OF_NETWORK_CHANGE = 8;

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
        public void getTransferredDownloadBytes(@NonNull JobParameters jobParams,
                @Nullable JobWorkItem jobWorkItem) throws RemoteException {
            JobServiceEngine service = mService.get();
            if (service != null) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = jobParams;
                args.arg2 = jobWorkItem;
                service.mHandler.obtainMessage(MSG_GET_TRANSFERRED_DOWNLOAD_BYTES, args)
                        .sendToTarget();
            }
        }

        @Override
        public void getTransferredUploadBytes(@NonNull JobParameters jobParams,
                @Nullable JobWorkItem jobWorkItem) throws RemoteException {
            JobServiceEngine service = mService.get();
            if (service != null) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = jobParams;
                args.arg2 = jobWorkItem;
                service.mHandler.obtainMessage(MSG_GET_TRANSFERRED_UPLOAD_BYTES, args)
                        .sendToTarget();
            }
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
        public void onNetworkChanged(JobParameters jobParams) throws RemoteException {
            JobServiceEngine service = mService.get();
            if (service != null) {
                service.mHandler.removeMessages(MSG_INFORM_OF_NETWORK_CHANGE);
                service.mHandler.obtainMessage(MSG_INFORM_OF_NETWORK_CHANGE, jobParams)
                        .sendToTarget();
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
            switch (msg.what) {
                case MSG_EXECUTE_JOB: {
                    final JobParameters params = (JobParameters) msg.obj;
                    try {
                        boolean workOngoing = JobServiceEngine.this.onStartJob(params);
                        ackStartMessage(params, workOngoing);
                    } catch (Exception e) {
                        Log.e(TAG, "Error while executing job: " + params.getJobId());
                        throw new RuntimeException(e);
                    }
                    break;
                }
                case MSG_STOP_JOB: {
                    final JobParameters params = (JobParameters) msg.obj;
                    try {
                        boolean ret = JobServiceEngine.this.onStopJob(params);
                        ackStopMessage(params, ret);
                    } catch (Exception e) {
                        Log.e(TAG, "Application unable to handle onStopJob.", e);
                        throw new RuntimeException(e);
                    }
                    break;
                }
                case MSG_JOB_FINISHED: {
                    final JobParameters params = (JobParameters) msg.obj;
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
                }
                case MSG_GET_TRANSFERRED_DOWNLOAD_BYTES: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final JobParameters params = (JobParameters) args.arg1;
                    final JobWorkItem item = (JobWorkItem) args.arg2;
                    try {
                        long ret = JobServiceEngine.this.getTransferredDownloadBytes(params, item);
                        ackGetTransferredDownloadBytesMessage(params, item, ret);
                    } catch (Exception e) {
                        Log.e(TAG, "Application unable to handle getTransferredDownloadBytes.", e);
                        throw new RuntimeException(e);
                    }
                    args.recycle();
                    break;
                }
                case MSG_GET_TRANSFERRED_UPLOAD_BYTES: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final JobParameters params = (JobParameters) args.arg1;
                    final JobWorkItem item = (JobWorkItem) args.arg2;
                    try {
                        long ret = JobServiceEngine.this.getTransferredUploadBytes(params, item);
                        ackGetTransferredUploadBytesMessage(params, item, ret);
                    } catch (Exception e) {
                        Log.e(TAG, "Application unable to handle getTransferredUploadBytes.", e);
                        throw new RuntimeException(e);
                    }
                    args.recycle();
                    break;
                }
                case MSG_UPDATE_TRANSFERRED_NETWORK_BYTES: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final JobParameters params = (JobParameters) args.arg1;
                    IJobCallback callback = params.getCallback();
                    if (callback != null) {
                        try {
                            callback.updateTransferredNetworkBytes(params.getJobId(),
                                    (JobWorkItem) args.arg2, args.argl1, args.argl2);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error updating data transfer progress to system:"
                                    + " binder has gone away.");
                        }
                    } else {
                        Log.e(TAG, "updateDataTransferProgress() called for a nonexistent job id.");
                    }
                    args.recycle();
                    break;
                }
                case MSG_UPDATE_ESTIMATED_NETWORK_BYTES: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final JobParameters params = (JobParameters) args.arg1;
                    IJobCallback callback = params.getCallback();
                    if (callback != null) {
                        try {
                            callback.updateEstimatedNetworkBytes(params.getJobId(),
                                    (JobWorkItem) args.arg2, args.argl1, args.argl2);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error updating estimated transfer size to system:"
                                    + " binder has gone away.");
                        }
                    } else {
                        Log.e(TAG,
                                "updateEstimatedNetworkBytes() called for a nonexistent job id.");
                    }
                    args.recycle();
                    break;
                }
                case MSG_SET_NOTIFICATION: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final JobParameters params = (JobParameters) args.arg1;
                    final Notification notification = (Notification) args.arg2;
                    IJobCallback callback = params.getCallback();
                    if (callback != null) {
                        try {
                            callback.setNotification(params.getJobId(),
                                    args.argi1, notification, args.argi2);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error providing notification: binder has gone away.");
                        }
                    } else {
                        Log.e(TAG, "setNotification() called for a nonexistent job.");
                    }
                    args.recycle();
                    break;
                }
                case MSG_INFORM_OF_NETWORK_CHANGE: {
                    final JobParameters params = (JobParameters) msg.obj;
                    try {
                        JobServiceEngine.this.onNetworkChanged(params);
                    } catch (Exception e) {
                        Log.e(TAG, "Error while executing job: " + params.getJobId());
                        throw new RuntimeException(e);
                    }
                    break;
                }
                default:
                    Log.e(TAG, "Unrecognised message received.");
                    break;
            }
        }

        private void ackGetTransferredDownloadBytesMessage(@NonNull JobParameters params,
                @Nullable JobWorkItem item, long progress) {
            final IJobCallback callback = params.getCallback();
            final int jobId = params.getJobId();
            final int workId = item == null ? -1 : item.getWorkId();
            if (callback != null) {
                try {
                    callback.acknowledgeGetTransferredDownloadBytesMessage(jobId, workId, progress);
                } catch (RemoteException e) {
                    Log.e(TAG, "System unreachable for returning progress.");
                }
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Attempting to ack a job that has already been processed.");
            }
        }

        private void ackGetTransferredUploadBytesMessage(@NonNull JobParameters params,
                @Nullable JobWorkItem item, long progress) {
            final IJobCallback callback = params.getCallback();
            final int jobId = params.getJobId();
            final int workId = item == null ? -1 : item.getWorkId();
            if (callback != null) {
                try {
                    callback.acknowledgeGetTransferredUploadBytesMessage(jobId, workId, progress);
                } catch (RemoteException e) {
                    Log.e(TAG, "System unreachable for returning progress.");
                }
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Attempting to ack a job that has already been processed.");
            }
        }

        private void ackStartMessage(JobParameters params, boolean workOngoing) {
            final IJobCallback callback = params.getCallback();
            final int jobId = params.getJobId();
            if (callback != null) {
                try {
                    callback.acknowledgeStartMessage(jobId, workOngoing);
                } catch (RemoteException e) {
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
     * {@link JobService#jobFinished(JobParameters, boolean)} for more information.
     */
    public void jobFinished(JobParameters params, boolean needsReschedule) {
        if (params == null) {
            throw new NullPointerException("params");
        }
        Message m = Message.obtain(mHandler, MSG_JOB_FINISHED, params);
        m.arg2 = needsReschedule ? 1 : 0;
        m.sendToTarget();
    }

    /**
     * Engine's report that the network for the job has changed.
     *
     * @see JobService#onNetworkChanged(JobParameters)
     */
    public void onNetworkChanged(@NonNull JobParameters params) {
        Log.w(TAG, "onNetworkChanged() not implemented. Must override in a subclass.");
    }

    /**
     * Engine's request to get how much data has been downloaded.
     *
     * @hide
     * @see JobService#getTransferredDownloadBytes()
     */
    @BytesLong
    public long getTransferredDownloadBytes(@NonNull JobParameters params,
            @Nullable JobWorkItem item) {
        if (Compatibility.isChangeEnabled(THROW_ON_INVALID_DATA_TRANSFER_IMPLEMENTATION)) {
            throw new RuntimeException("Not implemented. Must override in a subclass.");
        }
        return 0;
    }

    /**
     * Engine's request to get how much data has been uploaded.
     *
     * @hide
     * @see JobService#getTransferredUploadBytes()
     */
    @BytesLong
    public long getTransferredUploadBytes(@NonNull JobParameters params,
            @Nullable JobWorkItem item) {
        if (Compatibility.isChangeEnabled(THROW_ON_INVALID_DATA_TRANSFER_IMPLEMENTATION)) {
            throw new RuntimeException("Not implemented. Must override in a subclass.");
        }
        return 0;
    }

    /**
     * Call in to engine to report data transfer progress.
     *
     * @see JobService#updateTransferredNetworkBytes(JobParameters, long, long)
     * @see JobService#updateTransferredNetworkBytes(JobParameters, JobWorkItem, long, long)
     */
    public void updateTransferredNetworkBytes(@NonNull JobParameters params,
            @Nullable JobWorkItem item,
            @BytesLong long downloadBytes, @BytesLong long uploadBytes) {
        if (params == null) {
            throw new NullPointerException("params");
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = params;
        args.arg2 = item;
        args.argl1 = downloadBytes;
        args.argl2 = uploadBytes;
        mHandler.obtainMessage(MSG_UPDATE_TRANSFERRED_NETWORK_BYTES, args).sendToTarget();
    }

    /**
     * Call in to engine to report data transfer progress.
     *
     * @see JobService#updateEstimatedNetworkBytes(JobParameters, long, long)
     * @see JobService#updateEstimatedNetworkBytes(JobParameters, JobWorkItem, long, long)
     */
    public void updateEstimatedNetworkBytes(@NonNull JobParameters params,
            @Nullable JobWorkItem item,
            @BytesLong long downloadBytes, @BytesLong long uploadBytes) {
        if (params == null) {
            throw new NullPointerException("params");
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = params;
        args.arg2 = item;
        args.argl1 = downloadBytes;
        args.argl2 = uploadBytes;
        mHandler.obtainMessage(MSG_UPDATE_ESTIMATED_NETWORK_BYTES, args).sendToTarget();
    }

    /**
     * Give JobScheduler a notification to tie to this job's lifecycle.
     *
     * @see JobService#setNotification(JobParameters, int, Notification, int)
     */
    public void setNotification(@NonNull JobParameters params, int notificationId,
            @NonNull Notification notification,
            @JobService.JobEndNotificationPolicy int jobEndNotificationPolicy) {
        if (params == null) {
            throw new NullPointerException("params");
        }
        if (notification == null) {
            throw new NullPointerException("notification");
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = params;
        args.arg2 = notification;
        args.argi1 = notificationId;
        args.argi2 = jobEndNotificationPolicy;
        mHandler.obtainMessage(MSG_SET_NOTIFICATION, args).sendToTarget();
    }
}
