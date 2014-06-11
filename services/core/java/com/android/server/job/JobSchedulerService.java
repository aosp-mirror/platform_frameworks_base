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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.IJobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.job.controllers.BatteryController;
import com.android.server.job.controllers.ConnectivityController;
import com.android.server.job.controllers.IdleController;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;
import com.android.server.job.controllers.TimeController;

import java.util.LinkedList;

/**
 * Responsible for taking jobs representing work to be performed by a client app, and determining
 * based on the criteria specified when that job should be run against the client application's
 * endpoint.
 * Implements logic for scheduling, and rescheduling jobs. The JobSchedulerService knows nothing
 * about constraints, or the state of active jobs. It receives callbacks from the various
 * controllers and completed jobs and operates accordingly.
 *
 * Note on locking: Any operations that manipulate {@link #mJobs} need to lock on that object.
 * Any function with the suffix 'Locked' also needs to lock on {@link #mJobs}.
 * @hide
 */
public class JobSchedulerService extends com.android.server.SystemService
        implements StateChangedListener, JobCompletedListener, JobMapReadFinishedListener {
    // TODO: Switch this off for final version.
    static final boolean DEBUG = true;
    /** The number of concurrent jobs we run at one time. */
    private static final int MAX_JOB_CONTEXTS_COUNT = 3;
    static final String TAG = "JobManagerService";
    /** Master list of jobs. */
    private final JobStore mJobs;

    static final int MSG_JOB_EXPIRED = 0;
    static final int MSG_CHECK_JOB = 1;

    // Policy constants
    /**
     * Minimum # of idle jobs that must be ready in order to force the JMS to schedule things
     * early.
     */
    private static final int MIN_IDLE_COUNT = 1;
    /**
     * Minimum # of connectivity jobs that must be ready in order to force the JMS to schedule
     * things early.
     */
    private static final int MIN_CONNECTIVITY_COUNT = 2;
    /**
     * Minimum # of jobs (with no particular constraints) for which the JMS will be happy running
     * some work early.
     */
    private static final int MIN_READY_JOBS_COUNT = 4;

    /**
     * Track Services that have currently active or pending jobs. The index is provided by
     * {@link JobStatus#getServiceToken()}
     */
    private final List<JobServiceContext> mActiveServices = new LinkedList<JobServiceContext>();
    /** List of controllers that will notify this service of updates to jobs. */
    private List<StateController> mControllers;
    /**
     * Queue of pending jobs. The JobServiceContext class will receive jobs from this list
     * when ready to execute them.
     */
    private final LinkedList<JobStatus> mPendingJobs = new LinkedList<JobStatus>();

    private final JobHandler mHandler;
    private final JobSchedulerStub mJobSchedulerStub;
    /**
     * Cleans up outstanding jobs when a package is removed. Even if it's being replaced later we
     * still clean up. On reinstall the package will have a new uid.
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.d(TAG, "Receieved: " + intent.getAction());
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                int uidRemoved = intent.getIntExtra(Intent.EXTRA_UID, -1);
                if (DEBUG) {
                    Slog.d(TAG, "Removing jobs for uid: " + uidRemoved);
                }
                cancelJobsForUid(uidRemoved);
            } else if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (DEBUG) {
                    Slog.d(TAG, "Removing jobs for user: " + userId);
                }
                cancelJobsForUser(userId);
            }
        }
    };

    /**
     * Entry point from client to schedule the provided job.
     * This cancels the job if it's already been scheduled, and replaces it with the one provided.
     * @param job JobInfo object containing execution parameters
     * @param uId The package identifier of the application this job is for.
     * @param canPersistJob Whether or not the client has the appropriate permissions for
     *                       persisting this job.
     * @return Result of this operation. See <code>JobScheduler#RESULT_*</code> return codes.
     */
    public int schedule(JobInfo job, int uId, boolean canPersistJob) {
        JobStatus jobStatus = new JobStatus(job, uId, canPersistJob);
        cancelJob(uId, job.getId());
        startTrackingJob(jobStatus);
        return JobScheduler.RESULT_SUCCESS;
    }

    public List<JobInfo> getPendingJobs(int uid) {
        ArrayList<JobInfo> outList = new ArrayList<JobInfo>();
        synchronized (mJobs) {
            for (JobStatus job : mJobs.getJobs()) {
                if (job.getUid() == uid) {
                    outList.add(job.getJob());
                }
            }
        }
        return outList;
    }

    private void cancelJobsForUser(int userHandle) {
        synchronized (mJobs) {
            List<JobStatus> jobsForUser = mJobs.getJobsByUser(userHandle);
            for (JobStatus toRemove : jobsForUser) {
                if (DEBUG) {
                    Slog.d(TAG, "Cancelling: " + toRemove);
                }
                cancelJobLocked(toRemove);
            }
        }
    }

    /**
     * Entry point from client to cancel all jobs originating from their uid.
     * This will remove the job from the master list, and cancel the job if it was staged for
     * execution or being executed.
     * @param uid To check against for removal of a job.
     */
    public void cancelJobsForUid(int uid) {
        // Remove from master list.
        synchronized (mJobs) {
            List<JobStatus> jobsForUid = mJobs.getJobsByUid(uid);
            for (JobStatus toRemove : jobsForUid) {
                if (DEBUG) {
                    Slog.d(TAG, "Cancelling: " + toRemove);
                }
                cancelJobLocked(toRemove);
            }
        }
    }

    /**
     * Entry point from client to cancel the job corresponding to the jobId provided.
     * This will remove the job from the master list, and cancel the job if it was staged for
     * execution or being executed.
     * @param uid Uid of the calling client.
     * @param jobId Id of the job, provided at schedule-time.
     */
    public void cancelJob(int uid, int jobId) {
        JobStatus toCancel;
        synchronized (mJobs) {
            toCancel = mJobs.getJobByUidAndJobId(uid, jobId);
            if (toCancel != null) {
                cancelJobLocked(toCancel);
            }
        }
    }

    private void cancelJobLocked(JobStatus cancelled) {
        // Remove from store.
        stopTrackingJob(cancelled);
        // Remove from pending queue.
        mPendingJobs.remove(cancelled);
        // Cancel if running.
        stopJobOnServiceContextLocked(cancelled);
    }

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public JobSchedulerService(Context context) {
        super(context);
        // Create the controllers.
        mControllers = new LinkedList<StateController>();
        mControllers.add(ConnectivityController.get(this));
        mControllers.add(TimeController.get(this));
        mControllers.add(IdleController.get(this));
        mControllers.add(BatteryController.get(this));

        mHandler = new JobHandler(context.getMainLooper());
        mJobSchedulerStub = new JobSchedulerStub();
        // Create the "runners".
        for (int i = 0; i < MAX_JOB_CONTEXTS_COUNT; i++) {
            mActiveServices.add(
                    new JobServiceContext(this, context.getMainLooper()));
        }
        mJobs = JobStore.initAndGet(this);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.JOB_SCHEDULER_SERVICE, mJobSchedulerStub);
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            // Register br for package removals and user removals.
            final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, filter, null, null);
            final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);
        }
    }

    /**
     * Called when we have a job status object that we need to insert in our
     * {@link com.android.server.job.JobStore}, and make sure all the relevant controllers know
     * about.
     */
    private void startTrackingJob(JobStatus jobStatus) {
        boolean update;
        synchronized (mJobs) {
            update = mJobs.add(jobStatus);
        }
        for (StateController controller : mControllers) {
            if (update) {
                controller.maybeStopTrackingJob(jobStatus);
            }
            controller.maybeStartTrackingJob(jobStatus);
        }
    }

    /**
     * Called when we want to remove a JobStatus object that we've finished executing. Returns the
     * object removed.
     */
    private boolean stopTrackingJob(JobStatus jobStatus) {
        boolean removed;
        synchronized (mJobs) {
            // Remove from store as well as controllers.
            removed = mJobs.remove(jobStatus);
        }
        if (removed) {
            for (StateController controller : mControllers) {
                controller.maybeStopTrackingJob(jobStatus);
            }
        }
        return removed;
    }

    private boolean stopJobOnServiceContextLocked(JobStatus job) {
        for (JobServiceContext jsc : mActiveServices) {
            final JobStatus executing = jsc.getRunningJob();
            if (executing != null && executing.matches(job.getUid(), job.getJobId())) {
                jsc.cancelExecutingJob();
                return true;
            }
        }
        return false;
    }

    /**
     * @param job JobStatus we are querying against.
     * @return Whether or not the job represented by the status object is currently being run or
     * is pending.
     */
    private boolean isCurrentlyActiveLocked(JobStatus job) {
        for (JobServiceContext serviceContext : mActiveServices) {
            final JobStatus running = serviceContext.getRunningJob();
            if (running != null && running.matches(job.getUid(), job.getJobId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A job is rescheduled with exponential back-off if the client requests this from their
     * execution logic.
     * A caveat is for idle-mode jobs, for which the idle-mode constraint will usurp the
     * timeliness of the reschedule. For an idle-mode job, no deadline is given.
     * @param failureToReschedule Provided job status that we will reschedule.
     * @return A newly instantiated JobStatus with the same constraints as the last job except
     * with adjusted timing constraints.
     */
    private JobStatus getRescheduleJobForFailure(JobStatus failureToReschedule) {
        final long elapsedNowMillis = SystemClock.elapsedRealtime();
        final JobInfo job = failureToReschedule.getJob();

        final long initialBackoffMillis = job.getInitialBackoffMillis();
        final int backoffAttempt = failureToReschedule.getNumFailures() + 1;
        long newEarliestRuntimeElapsed = elapsedNowMillis;

        switch (job.getBackoffPolicy()) {
            case JobInfo.BackoffPolicy.LINEAR:
                newEarliestRuntimeElapsed += initialBackoffMillis * backoffAttempt;
                break;
            default:
                if (DEBUG) {
                    Slog.v(TAG, "Unrecognised back-off policy, defaulting to exponential.");
                }
            case JobInfo.BackoffPolicy.EXPONENTIAL:
                newEarliestRuntimeElapsed +=
                        Math.pow(initialBackoffMillis * 0.001, backoffAttempt) * 1000;
                break;
        }
        newEarliestRuntimeElapsed =
                Math.min(newEarliestRuntimeElapsed, JobInfo.MAX_BACKOFF_DELAY_MILLIS);
        return new JobStatus(failureToReschedule, newEarliestRuntimeElapsed,
                JobStatus.NO_LATEST_RUNTIME, backoffAttempt);
    }

    /**
     * Called after a periodic has executed so we can to re-add it. We take the last execution time
     * of the job to be the time of completion (i.e. the time at which this function is called).
     * This could be inaccurate b/c the job can run for as long as
     * {@link com.android.server.job.JobServiceContext#EXECUTING_TIMESLICE_MILLIS}, but will lead
     * to underscheduling at least, rather than if we had taken the last execution time to be the
     * start of the execution.
     * @return A new job representing the execution criteria for this instantiation of the
     * recurring job.
     */
    private JobStatus getRescheduleJobForPeriodic(JobStatus periodicToReschedule) {
        final long elapsedNow = SystemClock.elapsedRealtime();
        // Compute how much of the period is remaining.
        long runEarly = Math.max(periodicToReschedule.getLatestRunTimeElapsed() - elapsedNow, 0);
        long newEarliestRunTimeElapsed = elapsedNow + runEarly;
        long period = periodicToReschedule.getJob().getIntervalMillis();
        long newLatestRuntimeElapsed = newEarliestRunTimeElapsed + period;

        if (DEBUG) {
            Slog.v(TAG, "Rescheduling executed periodic. New execution window [" +
                    newEarliestRunTimeElapsed/1000 + ", " + newLatestRuntimeElapsed/1000 + "]s");
        }
        return new JobStatus(periodicToReschedule, newEarliestRunTimeElapsed,
                newLatestRuntimeElapsed, 0 /* backoffAttempt */);
    }

    // JobCompletedListener implementations.

    /**
     * A job just finished executing. We fetch the
     * {@link com.android.server.job.controllers.JobStatus} from the store and depending on
     * whether we want to reschedule we readd it to the controllers.
     * @param jobStatus Completed job.
     * @param needsReschedule Whether the implementing class should reschedule this job.
     */
    @Override
    public void onJobCompleted(JobStatus jobStatus, boolean needsReschedule) {
        if (DEBUG) {
            Slog.d(TAG, "Completed " + jobStatus + ", reschedule=" + needsReschedule);
        }
        if (!stopTrackingJob(jobStatus)) {
            if (DEBUG) {
                Slog.e(TAG, "Error removing job: could not find job to remove. Was job " +
                        "removed while executing?");
            }
            return;
        }
        if (needsReschedule) {
            JobStatus rescheduled = getRescheduleJobForFailure(jobStatus);
            startTrackingJob(rescheduled);
        } else if (jobStatus.getJob().isPeriodic()) {
            JobStatus rescheduledPeriodic = getRescheduleJobForPeriodic(jobStatus);
            startTrackingJob(rescheduledPeriodic);
        }
        mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
    }

    // StateChangedListener implementations.

    /**
     * Off-board work to our handler thread as quickly as possible, b/c this call is probably being
     * made on the main thread.
     * For now this takes the job and if it's ready to run it will run it. In future we might not
     * provide the job, so that the StateChangedListener has to run through its list of jobs to
     * see which are ready. This will further decouple the controllers from the execution logic.
     */
    @Override
    public void onControllerStateChanged() {
        // Post a message to to run through the list of jobs and start/stop any that are eligible.
        mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
    }

    @Override
    public void onRunJobNow(JobStatus jobStatus) {
        mHandler.obtainMessage(MSG_JOB_EXPIRED, jobStatus).sendToTarget();
    }

    /**
     * Disk I/O is finished, take the list of jobs we read from disk and add them to our
     * {@link JobStore}.
     * This is run on the {@link com.android.server.IoThread} instance, which is a separate thread,
     * and is called once at boot.
     */
    @Override
    public void onJobMapReadFinished(List<JobStatus> jobs) {
        synchronized (mJobs) {
            for (JobStatus js : jobs) {
                if (mJobs.containsJobIdForUid(js.getJobId(), js.getUid())) {
                    // An app with BOOT_COMPLETED *might* have decided to reschedule their job, in
                    // the same amount of time it took us to read it from disk. If this is the case
                    // we leave it be.
                    continue;
                }
                startTrackingJob(js);
            }
        }
    }

    private class JobHandler extends Handler {

        public JobHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_JOB_EXPIRED:
                    synchronized (mJobs) {
                        JobStatus runNow = (JobStatus) message.obj;
                        if (!mPendingJobs.contains(runNow)) {
                            mPendingJobs.add(runNow);
                        }
                    }
                    queueReadyJobsForExecutionH();
                    break;
                case MSG_CHECK_JOB:
                    // Check the list of jobs and run some of them if we feel inclined.
                    maybeQueueReadyJobsForExecutionH();
                    break;
            }
            maybeRunPendingJobsH();
            // Don't remove JOB_EXPIRED in case one came along while processing the queue.
            removeMessages(MSG_CHECK_JOB);
        }

        /**
         * Run through list of jobs and execute all possible - at least one is expired so we do
         * as many as we can.
         */
        private void queueReadyJobsForExecutionH() {
            synchronized (mJobs) {
                for (JobStatus job : mJobs.getJobs()) {
                    if (isReadyToBeExecutedLocked(job)) {
                        mPendingJobs.add(job);
                    } else if (isReadyToBeCancelledLocked(job)) {
                        stopJobOnServiceContextLocked(job);
                    }
                }
            }
        }

        /**
         * The state of at least one job has changed. Here is where we could enforce various
         * policies on when we want to execute jobs.
         * Right now the policy is such:
         * If >1 of the ready jobs is idle mode we send all of them off
         * if more than 2 network connectivity jobs are ready we send them all off.
         * If more than 4 jobs total are ready we send them all off.
         * TODO: It would be nice to consolidate these sort of high-level policies somewhere.
         */
        private void maybeQueueReadyJobsForExecutionH() {
            synchronized (mJobs) {
                int idleCount = 0;
                int backoffCount = 0;
                int connectivityCount = 0;
                List<JobStatus> runnableJobs = new ArrayList<JobStatus>();
                for (JobStatus job : mJobs.getJobs()) {
                    if (isReadyToBeExecutedLocked(job)) {
                        if (job.getNumFailures() > 0) {
                            backoffCount++;
                        }
                        if (job.hasIdleConstraint()) {
                            idleCount++;
                        }
                        if (job.hasConnectivityConstraint() || job.hasUnmeteredConstraint()) {
                            connectivityCount++;
                        }
                        runnableJobs.add(job);
                    } else if (isReadyToBeCancelledLocked(job)) {
                        stopJobOnServiceContextLocked(job);
                    }
                }
                if (backoffCount > 0 || idleCount >= MIN_IDLE_COUNT ||
                        connectivityCount >= MIN_CONNECTIVITY_COUNT ||
                        runnableJobs.size() >= MIN_READY_JOBS_COUNT) {
                    for (JobStatus job : runnableJobs) {
                        mPendingJobs.add(job);
                    }
                }
            }
        }

        /**
         * Criteria for moving a job into the pending queue:
         *      - It's ready.
         *      - It's not pending.
         *      - It's not already running on a JSC.
         */
        private boolean isReadyToBeExecutedLocked(JobStatus job) {
              return job.isReady() && !mPendingJobs.contains(job) && !isCurrentlyActiveLocked(job);
        }

        /**
         * Criteria for cancelling an active job:
         *      - It's not ready
         *      - It's running on a JSC.
         */
        private boolean isReadyToBeCancelledLocked(JobStatus job) {
            return !job.isReady() && isCurrentlyActiveLocked(job);
        }

        /**
         * Reconcile jobs in the pending queue against available execution contexts.
         * A controller can force a job into the pending queue even if it's already running, but
         * here is where we decide whether to actually execute it.
         */
        private void maybeRunPendingJobsH() {
            synchronized (mJobs) {
                Iterator<JobStatus> it = mPendingJobs.iterator();
                while (it.hasNext()) {
                    JobStatus nextPending = it.next();
                    JobServiceContext availableContext = null;
                    for (JobServiceContext jsc : mActiveServices) {
                        final JobStatus running = jsc.getRunningJob();
                        if (running != null && running.matches(nextPending.getUid(),
                                nextPending.getJobId())) {
                            // Already running this tId for this uId, skip.
                            availableContext = null;
                            break;
                        }
                        if (jsc.isAvailable()) {
                            availableContext = jsc;
                        }
                    }
                    if (availableContext != null) {
                        if (!availableContext.executeRunnableJob(nextPending)) {
                            if (DEBUG) {
                                Slog.d(TAG, "Error executing " + nextPending);
                            }
                            mJobs.remove(nextPending);
                        }
                        it.remove();
                    }
                }
            }
        }
    }

    /**
     * Binder stub trampoline implementation
     */
    final class JobSchedulerStub extends IJobScheduler.Stub {
        /** Cache determination of whether a given app can persist jobs
         * key is uid of the calling app; value is undetermined/true/false
         */
        private final SparseArray<Boolean> mPersistCache = new SparseArray<Boolean>();

        // Enforce that only the app itself (or shared uid participant) can schedule a
        // job that runs one of the app's services, as well as verifying that the
        // named service properly requires the BIND_JOB_SERVICE permission
        private void enforceValidJobRequest(int uid, JobInfo job) {
            final PackageManager pm = getContext().getPackageManager();
            final ComponentName service = job.getService();
            try {
                ServiceInfo si = pm.getServiceInfo(service, 0);
                if (si.applicationInfo.uid != uid) {
                    throw new IllegalArgumentException("uid " + uid +
                            " cannot schedule job in " + service.getPackageName());
                }
                if (!JobService.PERMISSION_BIND.equals(si.permission)) {
                    throw new IllegalArgumentException("Scheduled service " + service
                            + " does not require android.permission.BIND_JOB_SERVICE permission");
                }
            } catch (NameNotFoundException e) {
                throw new IllegalArgumentException("No such service: " + service);
            }
        }

        private boolean canPersistJobs(int pid, int uid) {
            // If we get this far we're good to go; all we need to do now is check
            // whether the app is allowed to persist its scheduled work.
            final boolean canPersist;
            synchronized (mPersistCache) {
                Boolean cached = mPersistCache.get(uid);
                if (cached != null) {
                    canPersist = cached.booleanValue();
                } else {
                    // Persisting jobs is tantamount to running at boot, so we permit
                    // it when the app has declared that it uses the RECEIVE_BOOT_COMPLETED
                    // permission
                    int result = getContext().checkPermission(
                            android.Manifest.permission.RECEIVE_BOOT_COMPLETED, pid, uid);
                    canPersist = (result == PackageManager.PERMISSION_GRANTED);
                    mPersistCache.put(uid, canPersist);
                }
            }
            return canPersist;
        }

        // IJobScheduler implementation
        @Override
        public int schedule(JobInfo job) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "Scheduling job: " + job);
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();

            enforceValidJobRequest(uid, job);
            final boolean canPersist = canPersistJobs(pid, uid);

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.schedule(job, uid, canPersist);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public List<JobInfo> getAllPendingJobs() throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.getPendingJobs(uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancelAll() throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.cancelJobsForUid(uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancel(int jobId) throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.cancelJob(uid, jobId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * "dumpsys" infrastructure
         */
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

            long identityToken = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }
    };

    void dumpInternal(PrintWriter pw) {
        synchronized (mJobs) {
            pw.println("Registered jobs:");
            if (mJobs.size() > 0) {
                for (JobStatus job : mJobs.getJobs()) {
                    job.dump(pw, "  ");
                }
            } else {
                pw.println();
                pw.println("No jobs scheduled.");
            }
            for (StateController controller : mControllers) {
                pw.println();
                controller.dumpControllerState(pw);
            }
            pw.println();
            pw.println("Pending");
            for (JobStatus jobStatus : mPendingJobs) {
                pw.println(jobStatus.hashCode());
            }
            pw.println();
            pw.println("Active jobs:");
            for (JobServiceContext jsc : mActiveServices) {
                if (jsc.isAvailable()) {
                    continue;
                } else {
                    pw.println(jsc.getRunningJob().hashCode() + " for: " +
                            (SystemClock.elapsedRealtime()
                                    - jsc.getExecutionStartTimeElapsed())/1000 + "s " +
                            "timeout: " + jsc.getTimeoutElapsed());
                }
            }
        }
        pw.println();
    }
}
