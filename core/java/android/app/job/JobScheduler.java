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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import java.util.List;

/**
 * This is an API for scheduling various types of jobs against the framework that will be executed
 * in your application's own process.
 * <p>
 * See {@link android.app.job.JobInfo} for more description of the types of jobs that can be run
 * and how to construct them. You will construct these JobInfo objects and pass them to the
 * JobScheduler with {@link #schedule(JobInfo)}. When the criteria declared are met, the
 * system will execute this job on your application's {@link android.app.job.JobService}.
 * You identify which JobService is meant to execute the logic for your job when you create the
 * JobInfo with
 * {@link android.app.job.JobInfo.Builder#JobInfo.Builder(int,android.content.ComponentName)}.
 * </p>
 * <p>
 * The framework will be intelligent about when you receive your callbacks, and attempt to batch
 * and defer them as much as possible. Typically if you don't specify a deadline on your job, it
 * can be run at any moment depending on the current state of the JobScheduler's internal queue,
 * however it might be deferred as long as until the next time the device is connected to a power
 * source.
 * </p>
 * <p>You do not
 * instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.JOB_SCHEDULER_SERVICE)}.
 */
public abstract class JobScheduler {
    /**
     * Returned from {@link #schedule(JobInfo)} when an invalid parameter was supplied. This can occur
     * if the run-time for your job is too short, or perhaps the system can't resolve the
     * requisite {@link JobService} in your package.
     */
    public static final int RESULT_FAILURE = 0;
    /**
     * Returned from {@link #schedule(JobInfo)} if this job has been successfully scheduled.
     */
    public static final int RESULT_SUCCESS = 1;

    /**
     * @param job The job you wish scheduled. See
     * {@link android.app.job.JobInfo.Builder JobInfo.Builder} for more detail on the sorts of jobs
     * you can schedule.
     * @return An int representing ({@link #RESULT_SUCCESS} or {@link #RESULT_FAILURE}).
     */
    public abstract int schedule(JobInfo job);

    /**
     *
     * @param job The job to be scheduled.
     * @param packageName The package on behalf of which the job is to be scheduled. This will be
     *                    used to track battery usage and appIdleState.
     * @param userId    User on behalf of whom this job is to be scheduled.
     * @param tag Debugging tag for dumps associated with this job (instead of the service class)
     * @return {@link #RESULT_SUCCESS} or {@link #RESULT_FAILURE}
     * @hide
     */
    @SystemApi
    public abstract int scheduleAsPackage(JobInfo job, String packageName, int userId, String tag);

    /**
     * Cancel a job that is pending in the JobScheduler.
     * @param jobId unique identifier for this job. Obtain this value from the jobs returned by
     * {@link #getAllPendingJobs()}.
     */
    public abstract void cancel(int jobId);

    /**
     * Cancel all jobs that have been registered with the JobScheduler by this package.
     */
    public abstract void cancelAll();

    /**
     * Retrieve all jobs for this package that are pending in the JobScheduler.
     *
     * @return a list of all the jobs registered by this package that have not
     *         yet been executed.
     */
    public abstract @NonNull List<JobInfo> getAllPendingJobs();

    /**
     * Retrieve a specific job for this package that is pending in the
     * JobScheduler.
     *
     * @return job registered by this package that has not yet been executed.
     */
    public abstract @Nullable JobInfo getPendingJob(int jobId);
}
