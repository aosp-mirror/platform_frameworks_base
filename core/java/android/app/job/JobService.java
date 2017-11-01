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
 * <p>Entry point for the callback from the {@link android.app.job.JobScheduler}.</p>
 * <p>This is the base class that handles asynchronous requests that were previously scheduled. You
 * are responsible for overriding {@link JobService#onStartJob(JobParameters)}, which is where
 * you will implement your job logic.</p>
 * <p>This service executes each incoming job on a {@link android.os.Handler} running on your
 * application's main thread. This means that you <b>must</b> offload your execution logic to
 * another thread/handler/{@link android.os.AsyncTask} of your choosing. Not doing so will result
 * in blocking any future callbacks from the JobManager - specifically
 * {@link #onStopJob(android.app.job.JobParameters)}, which is meant to inform you that the
 * scheduling requirements are no longer being met.</p>
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
     * permission, that service will be ignored by the OS.
     */
    public static final String PERMISSION_BIND =
            "android.permission.BIND_JOB_SERVICE";

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
            };
        }
        return mEngine.getBinder();
    }

    /**
     * Override this method with the callback logic for your job. Any such logic needs to be
     * performed on a separate thread, as this function is executed on your application's main
     * thread.
     *
     * @param params Parameters specifying info about this job, including the extras bundle you
     *               optionally provided at job-creation time.
     * @return True if your service needs to process the work (on a separate thread). False if
     * there's no more work to be done for this job.
     */
    public abstract boolean onStartJob(JobParameters params);

    /**
     * This method is called if the system has determined that you must stop execution of your job
     * even before you've had a chance to call {@link #jobFinished(JobParameters, boolean)}.
     *
     * <p>This will happen if the requirements specified at schedule time are no longer met. For
     * example you may have requested WiFi with
     * {@link android.app.job.JobInfo.Builder#setRequiredNetworkType(int)}, yet while your
     * job was executing the user toggled WiFi. Another example is if you had specified
     * {@link android.app.job.JobInfo.Builder#setRequiresDeviceIdle(boolean)}, and the phone left its
     * idle maintenance window. You are solely responsible for the behaviour of your application
     * upon receipt of this message; your app will likely start to misbehave if you ignore it. One
     * immediate repercussion is that the system will cease holding a wakelock for you.</p>
     *
     * @param params Parameters specifying info about this job.
     * @return True to indicate to the JobManager whether you'd like to reschedule this job based
     * on the retry criteria provided at job creation-time. False to drop the job. Regardless of
     * the value returned, your job must stop executing.
     */
    public abstract boolean onStopJob(JobParameters params);

    /**
     * Call this to inform the JobManager you've finished executing. This can be called from any
     * thread, as it will ultimately be run on your application's main thread. When the system
     * receives this message it will release the wakelock being held.
     * <p>
     *     You can specify post-execution behaviour to the scheduler here with
     *     <code>needsReschedule </code>. This will apply a back-off timer to your job based on
     *     the default, or what was set with
     *     {@link android.app.job.JobInfo.Builder#setBackoffCriteria(long, int)}. The original
     *     requirements are always honoured even for a backed-off job. Note that a job running in
     *     idle mode will not be backed-off. Instead what will happen is the job will be re-added
     *     to the queue and re-executed within a future idle maintenance window.
     * </p>
     *
     * @param params Parameters specifying system-provided info about this job, this was given to
     *               your application in {@link #onStartJob(JobParameters)}.
     * @param needsReschedule True if this job should be rescheduled according to the back-off
     *                        criteria specified at schedule-time. False otherwise.
     */
    public final void jobFinished(JobParameters params, boolean needsReschedule) {
        mEngine.jobFinished(params, needsReschedule);
    }
}