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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.Service;
import android.compat.Compatibility;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * <p>Entry point for the callback from the {@link android.app.job.JobScheduler}.</p>
 * <p>This is the base class that handles asynchronous requests that were previously scheduled. You
 * are responsible for overriding {@link JobService#onStartJob(JobParameters)}, which is where
 * you will implement your job logic.</p>
 * <p>This service executes each incoming job on a {@link android.os.Handler} running on your
 * application's main thread. This means that you <b>must</b> offload your execution logic to
 * another thread/handler/{@link android.os.AsyncTask} of your choosing. Not doing so will result
 * in blocking any future callbacks from the JobScheduler - specifically
 * {@link #onStopJob(android.app.job.JobParameters)}, which is meant to inform you that the
 * scheduling requirements are no longer being met.</p>
 *
 * <p class="note">
 * Since the introduction of JobScheduler, if an app did not return from
 * {@link #onStartJob(JobParameters)} within several seconds, JobScheduler would consider the app
 * unresponsive and clean up job execution. In such cases, the app was no longer considered
 * to be running a job and therefore did not have any of the job lifecycle guarantees outlined
 * in {@link JobScheduler}. However, prior to Android version
 * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the failure and cleanup were silent
 * and apps had no indication that they no longer had job lifecycle guarantees.
 * Starting with Android version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
 * JobScheduler will explicitly trigger an ANR in such cases so that apps and developers
 * can be aware of the issue.
 * Similar behavior applies to the return time from {@link #onStopJob(JobParameters)} as well.
 * <br /> <br />
 * If you see ANRs, then the app may be doing too much work on the UI thread. Ensure that
 * potentially long operations are moved to a worker thread.
 *
 * <p>As a subclass of {@link Service}, there will only be one active instance of any JobService
 * subclasses, regardless of job ID. This means that if you schedule multiple jobs with different
 * job IDs but using the same JobService class, that JobService may receive multiple calls to
 * {@link #onStartJob(JobParameters)} and {@link #onStopJob(JobParameters)}, with each call being
 * for the separate jobs.</p>
 */
public abstract class JobService extends Service {
    private static final String TAG = "JobService";

    /**
     * Job services must be protected with this permission:
     *
     * <pre class="prettyprint">
     *     &#60;service android:name="MyJobService"
     *              android:permission="android.permission.BIND_JOB_SERVICE" &#62;
     *         ...
     *     &#60;/service&#62;
     * </pre>
     *
     * <p>If a job service is declared in the manifest but not protected with this
     * permission, that service will be ignored by the system.
     */
    public static final String PERMISSION_BIND =
            "android.permission.BIND_JOB_SERVICE";

    /**
     * Detach the notification supplied to
     * {@link #setNotification(JobParameters, int, Notification, int)} when the job ends.
     * The notification will remain shown even after JobScheduler stops the job.
     */
    public static final int JOB_END_NOTIFICATION_POLICY_DETACH = 0;
    /**
     * Cancel and remove the notification supplied to
     * {@link #setNotification(JobParameters, int, Notification, int)} when the job ends.
     * The notification will be removed from the notification shade.
     */
    public static final int JOB_END_NOTIFICATION_POLICY_REMOVE = 1;

    /** @hide */
    @IntDef(prefix = {"JOB_END_NOTIFICATION_POLICY_"}, value = {
            JOB_END_NOTIFICATION_POLICY_DETACH,
            JOB_END_NOTIFICATION_POLICY_REMOVE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface JobEndNotificationPolicy {
    }

    private JobServiceEngine mEngine;

    /** @hide */
    public final IBinder onBind(Intent intent) {
        if (mEngine == null) {
            mEngine = new JobServiceEngine(this) {
                @Override
                public boolean onStartJob(JobParameters params) {
                    return JobService.this.onStartJob(params);
                }

                @Override
                public boolean onStopJob(JobParameters params) {
                    return JobService.this.onStopJob(params);
                }

                @Override
                @BytesLong
                public long getTransferredDownloadBytes(@NonNull JobParameters params,
                        @Nullable JobWorkItem item) {
                    if (item == null) {
                        return JobService.this.getTransferredDownloadBytes(params);
                    } else {
                        return JobService.this.getTransferredDownloadBytes(params, item);
                    }
                }

                @Override
                @BytesLong
                public long getTransferredUploadBytes(@NonNull JobParameters params,
                        @Nullable JobWorkItem item) {
                    if (item == null) {
                        return JobService.this.getTransferredUploadBytes(params);
                    } else {
                        return JobService.this.getTransferredUploadBytes(params, item);
                    }
                }

                @Override
                public void onNetworkChanged(@NonNull JobParameters params) {
                    JobService.this.onNetworkChanged(params);
                }
            };
        }
        return mEngine.getBinder();
    }

    /**
     * Call this to inform the JobScheduler that the job has finished its work.  When the
     * system receives this message, it releases the wakelock being held for the job.
     * This does not need to be called if {@link #onStopJob(JobParameters)} has been called.
     * <p>
     * You can request that the job be scheduled again by passing {@code true} as
     * the <code>wantsReschedule</code> parameter. This will apply back-off policy
     * for the job; this policy can be adjusted through the
     * {@link android.app.job.JobInfo.Builder#setBackoffCriteria(long, int)} method
     * when the job is originally scheduled.  The job's initial
     * requirements are preserved when jobs are rescheduled, regardless of backed-off
     * policy.
     * <p class="note">
     * A job running while the device is dozing will not be rescheduled with the normal back-off
     * policy.  Instead, the job will be re-added to the queue and executed again during
     * a future idle maintenance window.
     * </p>
     *
     * <p class="note">
     * Any {@link JobInfo.Builder#setUserInitiated(boolean) user-initiated job}
     * cannot be rescheduled when the user has asked to stop the app
     * via a system provided affordance (such as the Task Manager).
     * In such situations, the value of {@code wantsReschedule} is always treated as {@code false}.
     *
     * @param params The parameters identifying this job, as supplied to
     *               the job in the {@link #onStartJob(JobParameters)} callback.
     * @param wantsReschedule {@code true} if this job should be rescheduled according
     *     to the back-off criteria specified when it was first scheduled; {@code false}
     *     otherwise. When {@code false} is returned for a periodic job,
     *     the job will be rescheduled according to its periodic policy.
     */
    public final void jobFinished(JobParameters params, boolean wantsReschedule) {
        mEngine.jobFinished(params, wantsReschedule);
    }

    /**
     * Called to indicate that the job has begun executing.  Override this method with the
     * logic for your job.  Like all other component lifecycle callbacks, this method executes
     * on your application's main thread.
     * <p>
     * Return {@code true} from this method if your job needs to continue running.  If you
     * do this, the job remains active until you call
     * {@link #jobFinished(JobParameters, boolean)} to tell the system that it has completed
     * its work, or until the job's required constraints are no longer satisfied.  For
     * example, if the job was scheduled using
     * {@link JobInfo.Builder#setRequiresCharging(boolean) setRequiresCharging(true)},
     * it will be immediately halted by the system if the user unplugs the device from power,
     * the job's {@link #onStopJob(JobParameters)} callback will be invoked, and the app
     * will be expected to shut down all ongoing work connected with that job.
     * <p>
     * The system holds a wakelock on behalf of your app as long as your job is executing.
     * This wakelock is acquired before this method is invoked, and is not released until either
     * you call {@link #jobFinished(JobParameters, boolean)}, or after the system invokes
     * {@link #onStopJob(JobParameters)} to notify your job that it is being shut down
     * prematurely.
     * <p>
     * Returning {@code false} from this method means your job is already finished.  The
     * system's wakelock for the job will be released, and {@link #onStopJob(JobParameters)}
     * will not be invoked.
     *
     * @param params Parameters specifying info about this job, including the optional
     *     extras configured with {@link JobInfo.Builder#setExtras(android.os.PersistableBundle)}.
     *     This object serves to identify this specific running job instance when calling
     *     {@link #jobFinished(JobParameters, boolean)}.
     * @return {@code true} if your service will continue running, using a separate thread
     *     when appropriate.  {@code false} means that this job has completed its work.
     */
    public abstract boolean onStartJob(JobParameters params);

    /**
     * This method is called if the system has determined that you must stop execution of your job
     * even before you've had a chance to call {@link #jobFinished(JobParameters, boolean)}.
     * Once this method is called, you no longer need to call
     * {@link #jobFinished(JobParameters, boolean)}.
     *
     * <p>This may happen if the requirements specified at schedule time are no longer met. For
     * example you may have requested WiFi with
     * {@link android.app.job.JobInfo.Builder#setRequiredNetworkType(int)}, yet while your
     * job was executing the user toggled WiFi. Another example is if you had specified
     * {@link android.app.job.JobInfo.Builder#setRequiresDeviceIdle(boolean)}, and the phone left
     * its idle state. There are many other reasons a job can be stopped early besides
     * constraints no longer being satisfied. {@link JobParameters#getStopReason()} will return the
     * reason this method was called. You are solely responsible for the behavior of your
     * application upon receipt of this message; your app will likely start to misbehave if you
     * ignore it.
     * <p>
     * Once this method returns (or times out), the system releases the wakelock that it is holding
     * on behalf of the job.</p>
     *
     * <p class="note">
     * Any {@link JobInfo.Builder#setUserInitiated(boolean) user-initiated job}
     * cannot be rescheduled when stopped by the user via a system provided affordance (such as
     * the Task Manager). In such situations, the returned value from this method call is always
     * treated as {@code false}.
     *
     * <p class="caution"><strong>Note:</strong> When a job is stopped and rescheduled via this
     * method call, the deadline constraint is excluded from the rescheduled job's constraint set.
     * The rescheduled job will run again once all remaining constraints are satisfied.
     *
     * @param params The parameters identifying this job, similar to what was supplied to the job in
     *               the {@link #onStartJob(JobParameters)} callback, but with the stop reason
     *               included.
     * @return {@code true} to indicate to the JobScheduler whether you'd like to reschedule
     * this job based on the retry criteria provided at job creation-time; or {@code false}
     * to end the job entirely (or, for a periodic job, to reschedule it according to its
     * requested periodic criteria). Regardless of the value returned, your job must stop executing.
     */
    public abstract boolean onStopJob(JobParameters params);

    /**
     * This method is called that for a job that has a network constraint when the network
     * to be used by the job changes. The new network object will be available via
     * {@link JobParameters#getNetwork()}. Any network that results in this method call will
     * match the job's requested network constraints.
     *
     * <p>
     * For example, if a device is on a metered mobile network and then connects to an
     * unmetered WiFi network, and the job has indicated that both networks satisfy its
     * network constraint, then this method will be called to notify the job of the new
     * unmetered WiFi network.
     *
     * @param params The parameters identifying this job, similar to what was supplied to the job in
     *               the {@link #onStartJob(JobParameters)} callback, but with an updated network.
     * @see JobInfo.Builder#setRequiredNetwork(android.net.NetworkRequest)
     * @see JobInfo.Builder#setRequiredNetworkType(int)
     */
    public void onNetworkChanged(@NonNull JobParameters params) {
        Log.w(TAG, "onNetworkChanged() not implemented in " + getClass().getName()
                + ". Must override in a subclass.");
    }

    /**
     * Update the amount of data this job is estimated to transfer after the job has started.
     *
     * @see JobInfo.Builder#setEstimatedNetworkBytes(long, long)
     */
    public final void updateEstimatedNetworkBytes(@NonNull JobParameters params,
            @BytesLong long downloadBytes, @BytesLong long uploadBytes) {
        mEngine.updateEstimatedNetworkBytes(params, null, downloadBytes, uploadBytes);
    }

    /**
     * Update the amount of data this JobWorkItem is estimated to transfer after the job has
     * started.
     *
     * @see JobInfo.Builder#setEstimatedNetworkBytes(long, long)
     */
    public final void updateEstimatedNetworkBytes(@NonNull JobParameters params,
            @NonNull JobWorkItem jobWorkItem,
            @BytesLong long downloadBytes, @BytesLong long uploadBytes) {
        mEngine.updateEstimatedNetworkBytes(params, jobWorkItem, downloadBytes, uploadBytes);
    }

    /**
     * Tell JobScheduler how much data has successfully been transferred for the data transfer job.
     */
    public final void updateTransferredNetworkBytes(@NonNull JobParameters params,
            @BytesLong long transferredDownloadBytes, @BytesLong long transferredUploadBytes) {
        mEngine.updateTransferredNetworkBytes(params, null,
                transferredDownloadBytes, transferredUploadBytes);
    }

    /**
     * Tell JobScheduler how much data has been transferred for the data transfer
     * {@link JobWorkItem}.
     */
    public final void updateTransferredNetworkBytes(@NonNull JobParameters params,
            @NonNull JobWorkItem item,
            @BytesLong long transferredDownloadBytes, @BytesLong long transferredUploadBytes) {
        mEngine.updateTransferredNetworkBytes(params, item,
                transferredDownloadBytes, transferredUploadBytes);
    }

    /**
     * Get the number of bytes the app has successfully downloaded for this job. JobScheduler
     * will call this if the job has specified positive estimated download bytes and
     * {@link #updateTransferredNetworkBytes(JobParameters, long, long)}
     * hasn't been called recently.
     *
     * <p>
     * This must be implemented for all data transfer jobs.
     *
     * @hide
     * @see JobInfo.Builder#setEstimatedNetworkBytes(long, long)
     * @see JobInfo#NETWORK_BYTES_UNKNOWN
     */
    // TODO(255371817): specify the actual time JS will wait for progress before requesting
    @BytesLong
    public long getTransferredDownloadBytes(@NonNull JobParameters params) {
        if (Compatibility.isChangeEnabled(THROW_ON_INVALID_DATA_TRANSFER_IMPLEMENTATION)) {
            // Regular jobs don't have to implement this and JobScheduler won't call this API for
            // non-data transfer jobs.
            throw new RuntimeException("Not implemented. Must override in a subclass.");
        }
        return 0;
    }

    /**
     * Get the number of bytes the app has successfully downloaded for this job. JobScheduler
     * will call this if the job has specified positive estimated upload bytes and
     * {@link #updateTransferredNetworkBytes(JobParameters, long, long)}
     * hasn't been called recently.
     *
     * <p>
     * This must be implemented for all data transfer jobs.
     *
     * @hide
     * @see JobInfo.Builder#setEstimatedNetworkBytes(long, long)
     * @see JobInfo#NETWORK_BYTES_UNKNOWN
     */
    // TODO(255371817): specify the actual time JS will wait for progress before requesting
    @BytesLong
    public long getTransferredUploadBytes(@NonNull JobParameters params) {
        if (Compatibility.isChangeEnabled(THROW_ON_INVALID_DATA_TRANSFER_IMPLEMENTATION)) {
            // Regular jobs don't have to implement this and JobScheduler won't call this API for
            // non-data transfer jobs.
            throw new RuntimeException("Not implemented. Must override in a subclass.");
        }
        return 0;
    }

    /**
     * Get the number of bytes the app has successfully downloaded for this job. JobScheduler
     * will call this if the job has specified positive estimated download bytes and
     * {@link #updateTransferredNetworkBytes(JobParameters, JobWorkItem, long, long)}
     * hasn't been called recently and the job has
     * {@link JobWorkItem JobWorkItems} that have been
     * {@link JobParameters#dequeueWork dequeued} but not
     * {@link JobParameters#completeWork(JobWorkItem) completed}.
     *
     * <p>
     * This must be implemented for all data transfer jobs.
     *
     * @hide
     * @see JobInfo#NETWORK_BYTES_UNKNOWN
     */
    // TODO(255371817): specify the actual time JS will wait for progress before requesting
    @BytesLong
    public long getTransferredDownloadBytes(@NonNull JobParameters params,
            @NonNull JobWorkItem item) {
        if (item == null) {
            return getTransferredDownloadBytes(params);
        }
        if (Compatibility.isChangeEnabled(THROW_ON_INVALID_DATA_TRANSFER_IMPLEMENTATION)) {
            // Regular jobs don't have to implement this and JobScheduler won't call this API for
            // non-data transfer jobs.
            throw new RuntimeException("Not implemented. Must override in a subclass.");
        }
        return 0;
    }

    /**
     * Get the number of bytes the app has successfully downloaded for this job. JobScheduler
     * will call this if the job has specified positive estimated upload bytes and
     * {@link #updateTransferredNetworkBytes(JobParameters, JobWorkItem, long, long)}
     * hasn't been called recently and the job has
     * {@link JobWorkItem JobWorkItems} that have been
     * {@link JobParameters#dequeueWork dequeued} but not
     * {@link JobParameters#completeWork(JobWorkItem) completed}.
     *
     * <p>
     * This must be implemented for all data transfer jobs.
     *
     * @hide
     * @see JobInfo#NETWORK_BYTES_UNKNOWN
     */
    // TODO(255371817): specify the actual time JS will wait for progress before requesting
    @BytesLong
    public long getTransferredUploadBytes(@NonNull JobParameters params,
            @NonNull JobWorkItem item) {
        if (item == null) {
            return getTransferredUploadBytes(params);
        }
        if (Compatibility.isChangeEnabled(THROW_ON_INVALID_DATA_TRANSFER_IMPLEMENTATION)) {
            // Regular jobs don't have to implement this and JobScheduler won't call this API for
            // non-data transfer jobs.
            throw new RuntimeException("Not implemented. Must override in a subclass.");
        }
        return 0;
    }

    /**
     * Provide JobScheduler with a notification to post and tie to this job's lifecycle.
     * This is only required for those user-initiated jobs which return {@code true} via
     * {@link JobParameters#isUserInitiatedJob()}.
     * If the app does not call this method for a required notification within
     * 10 seconds after {@link #onStartJob(JobParameters)} is called,
     * the system will trigger an ANR and stop this job.
     *
     * The notification must provide an accurate description of the work that the job is doing
     * and, if possible, the state of the work.
     *
     * <p>
     * Note that certain types of jobs
     * (e.g. {@link JobInfo.Builder#setEstimatedNetworkBytes(long, long) data transfer jobs})
     * may require the notification to have certain characteristics
     * and their documentation will state any such requirements.
     *
     * <p>
     * JobScheduler will not remember this notification after the job has finished running,
     * so apps must call this every time the job is started (if required or desired).
     *
     * <p>
     * If separate jobs use the same notification ID with this API, the most recently provided
     * notification will be shown to the user, and the
     * {@code jobEndNotificationPolicy} of the last job to stop will be applied.
     *
     * @param params                   The parameters identifying this job, as supplied to
     *                                 the job in the {@link #onStartJob(JobParameters)} callback.
     * @param notificationId           The ID for this notification, as per
     *                                 {@link android.app.NotificationManager#notify(int,
     *                                 Notification)}.
     * @param notification             The notification to be displayed.
     * @param jobEndNotificationPolicy The policy to apply to the notification when the job stops.
     */
    public final void setNotification(@NonNull JobParameters params, int notificationId,
            @NonNull Notification notification,
            @JobEndNotificationPolicy int jobEndNotificationPolicy) {
        mEngine.setNotification(params, notificationId, notification, jobEndNotificationPolicy);
    }
}
