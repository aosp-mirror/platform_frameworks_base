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

package com.android.server.task;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import android.app.task.ITaskManager;
import android.app.task.Task;
import android.app.task.TaskManager;
import android.content.BroadcastReceiver;
import android.app.task.TaskService;
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

import com.android.server.task.controllers.BatteryController;
import com.android.server.task.controllers.ConnectivityController;
import com.android.server.task.controllers.IdleController;
import com.android.server.task.controllers.StateController;
import com.android.server.task.controllers.TaskStatus;
import com.android.server.task.controllers.TimeController;

import java.util.LinkedList;

/**
 * Responsible for taking tasks representing work to be performed by a client app, and determining
 * based on the criteria specified when that task should be run against the client application's
 * endpoint.
 * Implements logic for scheduling, and rescheduling tasks. The TaskManagerService knows nothing
 * about constraints, or the state of active tasks. It receives callbacks from the various
 * controllers and completed tasks and operates accordingly.
 *
 * Note on locking: Any operations that manipulate {@link #mTasks} need to lock on that object.
 * Any function with the suffix 'Locked' also needs to lock on {@link #mTasks}.
 * @hide
 */
public class TaskManagerService extends com.android.server.SystemService
        implements StateChangedListener, TaskCompletedListener, TaskMapReadFinishedListener {
    // TODO: Switch this off for final version.
    static final boolean DEBUG = true;
    /** The number of concurrent tasks we run at one time. */
    private static final int MAX_TASK_CONTEXTS_COUNT = 3;
    static final String TAG = "TaskManager";
    /** Master list of tasks. */
    private final TaskStore mTasks;

    static final int MSG_TASK_EXPIRED = 0;
    static final int MSG_CHECK_TASKS = 1;

    // Policy constants
    /**
     * Minimum # of idle tasks that must be ready in order to force the TM to schedule things
     * early.
     */
    private static final int MIN_IDLE_COUNT = 1;
    /**
     * Minimum # of connectivity tasks that must be ready in order to force the TM to schedule
     * things early.
     */
    private static final int MIN_CONNECTIVITY_COUNT = 2;
    /**
     * Minimum # of tasks (with no particular constraints) for which the TM will be happy running
     * some work early.
     */
    private static final int MIN_READY_TASKS_COUNT = 4;

    /**
     * Track Services that have currently active or pending tasks. The index is provided by
     * {@link TaskStatus#getServiceToken()}
     */
    private final List<TaskServiceContext> mActiveServices = new LinkedList<TaskServiceContext>();
    /** List of controllers that will notify this service of updates to tasks. */
    private List<StateController> mControllers;
    /**
     * Queue of pending tasks. The TaskServiceContext class will receive tasks from this list
     * when ready to execute them.
     */
    private final LinkedList<TaskStatus> mPendingTasks = new LinkedList<TaskStatus>();

    private final TaskHandler mHandler;
    private final TaskManagerStub mTaskManagerStub;
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
                cancelTasksForUid(uidRemoved);
            } else if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (DEBUG) {
                    Slog.d(TAG, "Removing jobs for user: " + userId);
                }
                cancelTasksForUser(userId);
            }
        }
    };

    /**
     * Entry point from client to schedule the provided task.
     * This cancels the task if it's already been scheduled, and replaces it with the one provided.
     * @param task Task object containing execution parameters
     * @param uId The package identifier of the application this task is for.
     * @param canPersistTask Whether or not the client has the appropriate permissions for
     *                       persisting this task.
     * @return Result of this operation. See <code>TaskManager#RESULT_*</code> return codes.
     */
    public int schedule(Task task, int uId, boolean canPersistTask) {
        TaskStatus taskStatus = new TaskStatus(task, uId, canPersistTask);
        cancelTask(uId, task.getId());
        startTrackingTask(taskStatus);
        return TaskManager.RESULT_SUCCESS;
    }

    public List<Task> getPendingTasks(int uid) {
        ArrayList<Task> outList = new ArrayList<Task>();
        synchronized (mTasks) {
            for (TaskStatus ts : mTasks.getTasks()) {
                if (ts.getUid() == uid) {
                    outList.add(ts.getTask());
                }
            }
        }
        return outList;
    }

    private void cancelTasksForUser(int userHandle) {
        synchronized (mTasks) {
            List<TaskStatus> tasksForUser = mTasks.getTasksByUser(userHandle);
            for (TaskStatus toRemove : tasksForUser) {
                if (DEBUG) {
                    Slog.d(TAG, "Cancelling: " + toRemove);
                }
                cancelTaskLocked(toRemove);
            }
        }
    }

    /**
     * Entry point from client to cancel all tasks originating from their uid.
     * This will remove the task from the master list, and cancel the task if it was staged for
     * execution or being executed.
     * @param uid To check against for removal of a task.
     */
    public void cancelTasksForUid(int uid) {
        // Remove from master list.
        synchronized (mTasks) {
            List<TaskStatus> tasksForUid = mTasks.getTasksByUid(uid);
            for (TaskStatus toRemove : tasksForUid) {
                if (DEBUG) {
                    Slog.d(TAG, "Cancelling: " + toRemove);
                }
                cancelTaskLocked(toRemove);
            }
        }
    }

    /**
     * Entry point from client to cancel the task corresponding to the taskId provided.
     * This will remove the task from the master list, and cancel the task if it was staged for
     * execution or being executed.
     * @param uid Uid of the calling client.
     * @param taskId Id of the task, provided at schedule-time.
     */
    public void cancelTask(int uid, int taskId) {
        TaskStatus toCancel;
        synchronized (mTasks) {
            toCancel = mTasks.getTaskByUidAndTaskId(uid, taskId);
            if (toCancel != null) {
                cancelTaskLocked(toCancel);
            }
        }
    }

    private void cancelTaskLocked(TaskStatus cancelled) {
        // Remove from store.
        stopTrackingTask(cancelled);
        // Remove from pending queue.
        mPendingTasks.remove(cancelled);
        // Cancel if running.
        stopTaskOnServiceContextLocked(cancelled);
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
    public TaskManagerService(Context context) {
        super(context);
        // Create the controllers.
        mControllers = new LinkedList<StateController>();
        mControllers.add(ConnectivityController.get(this));
        mControllers.add(TimeController.get(this));
        mControllers.add(IdleController.get(this));
        mControllers.add(BatteryController.get(this));

        mHandler = new TaskHandler(context.getMainLooper());
        mTaskManagerStub = new TaskManagerStub();
        // Create the "runners".
        for (int i = 0; i < MAX_TASK_CONTEXTS_COUNT; i++) {
            mActiveServices.add(
                    new TaskServiceContext(this, context.getMainLooper()));
        }
        mTasks = TaskStore.initAndGet(this);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TASK_SERVICE, mTaskManagerStub);
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
     * Called when we have a task status object that we need to insert in our
     * {@link com.android.server.task.TaskStore}, and make sure all the relevant controllers know
     * about.
     */
    private void startTrackingTask(TaskStatus taskStatus) {
        boolean update;
        synchronized (mTasks) {
            update = mTasks.add(taskStatus);
        }
        for (StateController controller : mControllers) {
            if (update) {
                controller.maybeStopTrackingTask(taskStatus);
            }
            controller.maybeStartTrackingTask(taskStatus);
        }
    }

    /**
     * Called when we want to remove a TaskStatus object that we've finished executing. Returns the
     * object removed.
     */
    private boolean stopTrackingTask(TaskStatus taskStatus) {
        boolean removed;
        synchronized (mTasks) {
            // Remove from store as well as controllers.
            removed = mTasks.remove(taskStatus);
        }
        if (removed) {
            for (StateController controller : mControllers) {
                controller.maybeStopTrackingTask(taskStatus);
            }
        }
        return removed;
    }

    private boolean stopTaskOnServiceContextLocked(TaskStatus ts) {
        for (TaskServiceContext tsc : mActiveServices) {
            final TaskStatus executing = tsc.getRunningTask();
            if (executing != null && executing.matches(ts.getUid(), ts.getTaskId())) {
                tsc.cancelExecutingTask();
                return true;
            }
        }
        return false;
    }

    /**
     * @param ts TaskStatus we are querying against.
     * @return Whether or not the task represented by the status object is currently being run or
     * is pending.
     */
    private boolean isCurrentlyActiveLocked(TaskStatus ts) {
        for (TaskServiceContext serviceContext : mActiveServices) {
            final TaskStatus running = serviceContext.getRunningTask();
            if (running != null && running.matches(ts.getUid(), ts.getTaskId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A task is rescheduled with exponential back-off if the client requests this from their
     * execution logic.
     * A caveat is for idle-mode tasks, for which the idle-mode constraint will usurp the
     * timeliness of the reschedule. For an idle-mode task, no deadline is given.
     * @param failureToReschedule Provided task status that we will reschedule.
     * @return A newly instantiated TaskStatus with the same constraints as the last task except
     * with adjusted timing constraints.
     */
    private TaskStatus getRescheduleTaskForFailure(TaskStatus failureToReschedule) {
        final long elapsedNowMillis = SystemClock.elapsedRealtime();
        final Task task = failureToReschedule.getTask();

        final long initialBackoffMillis = task.getInitialBackoffMillis();
        final int backoffAttempt = failureToReschedule.getNumFailures() + 1;
        long newEarliestRuntimeElapsed = elapsedNowMillis;

        switch (task.getBackoffPolicy()) {
            case Task.BackoffPolicy.LINEAR:
                newEarliestRuntimeElapsed += initialBackoffMillis * backoffAttempt;
                break;
            default:
                if (DEBUG) {
                    Slog.v(TAG, "Unrecognised back-off policy, defaulting to exponential.");
                }
            case Task.BackoffPolicy.EXPONENTIAL:
                newEarliestRuntimeElapsed +=
                        Math.pow(initialBackoffMillis * 0.001, backoffAttempt) * 1000;
                break;
        }
        newEarliestRuntimeElapsed =
                Math.min(newEarliestRuntimeElapsed, Task.MAX_BACKOFF_DELAY_MILLIS);
        return new TaskStatus(failureToReschedule, newEarliestRuntimeElapsed,
                TaskStatus.NO_LATEST_RUNTIME, backoffAttempt);
    }

    /**
     * Called after a periodic has executed so we can to re-add it. We take the last execution time
     * of the task to be the time of completion (i.e. the time at which this function is called).
     * This could be inaccurate b/c the task can run for as long as
     * {@link com.android.server.task.TaskServiceContext#EXECUTING_TIMESLICE_MILLIS}, but will lead
     * to underscheduling at least, rather than if we had taken the last execution time to be the
     * start of the execution.
     * @return A new task representing the execution criteria for this instantiation of the
     * recurring task.
     */
    private TaskStatus getRescheduleTaskForPeriodic(TaskStatus periodicToReschedule) {
        final long elapsedNow = SystemClock.elapsedRealtime();
        // Compute how much of the period is remaining.
        long runEarly = Math.max(periodicToReschedule.getLatestRunTimeElapsed() - elapsedNow, 0);
        long newEarliestRunTimeElapsed = elapsedNow + runEarly;
        long period = periodicToReschedule.getTask().getIntervalMillis();
        long newLatestRuntimeElapsed = newEarliestRunTimeElapsed + period;

        if (DEBUG) {
            Slog.v(TAG, "Rescheduling executed periodic. New execution window [" +
                    newEarliestRunTimeElapsed/1000 + ", " + newLatestRuntimeElapsed/1000 + "]s");
        }
        return new TaskStatus(periodicToReschedule, newEarliestRunTimeElapsed,
                newLatestRuntimeElapsed, 0 /* backoffAttempt */);
    }

    // TaskCompletedListener implementations.

    /**
     * A task just finished executing. We fetch the
     * {@link com.android.server.task.controllers.TaskStatus} from the store and depending on
     * whether we want to reschedule we readd it to the controllers.
     * @param taskStatus Completed task.
     * @param needsReschedule Whether the implementing class should reschedule this task.
     */
    @Override
    public void onTaskCompleted(TaskStatus taskStatus, boolean needsReschedule) {
        if (DEBUG) {
            Slog.d(TAG, "Completed " + taskStatus + ", reschedule=" + needsReschedule);
        }
        if (!stopTrackingTask(taskStatus)) {
            if (DEBUG) {
                Slog.e(TAG, "Error removing task: could not find task to remove. Was task " +
                        "removed while executing?");
            }
            return;
        }
        if (needsReschedule) {
            TaskStatus rescheduled = getRescheduleTaskForFailure(taskStatus);
            startTrackingTask(rescheduled);
        } else if (taskStatus.getTask().isPeriodic()) {
            TaskStatus rescheduledPeriodic = getRescheduleTaskForPeriodic(taskStatus);
            startTrackingTask(rescheduledPeriodic);
        }
        mHandler.obtainMessage(MSG_CHECK_TASKS).sendToTarget();
    }

    // StateChangedListener implementations.

    /**
     * Off-board work to our handler thread as quickly as possible, b/c this call is probably being
     * made on the main thread.
     * For now this takes the task and if it's ready to run it will run it. In future we might not
     * provide the task, so that the StateChangedListener has to run through its list of tasks to
     * see which are ready. This will further decouple the controllers from the execution logic.
     */
    @Override
    public void onControllerStateChanged() {
        // Post a message to to run through the list of tasks and start/stop any that are eligible.
        mHandler.obtainMessage(MSG_CHECK_TASKS).sendToTarget();
    }

    @Override
    public void onRunTaskNow(TaskStatus taskStatus) {
        mHandler.obtainMessage(MSG_TASK_EXPIRED, taskStatus).sendToTarget();
    }

    /**
     * Disk I/O is finished, take the list of tasks we read from disk and add them to our
     * {@link TaskStore}.
     * This is run on the {@link com.android.server.IoThread} instance, which is a separate thread,
     * and is called once at boot.
     */
    @Override
    public void onTaskMapReadFinished(List<TaskStatus> tasks) {
        synchronized (mTasks) {
            for (TaskStatus ts : tasks) {
                if (mTasks.containsTaskIdForUid(ts.getTaskId(), ts.getUid())) {
                    // An app with BOOT_COMPLETED *might* have decided to reschedule their task, in
                    // the same amount of time it took us to read it from disk. If this is the case
                    // we leave it be.
                    continue;
                }
                startTrackingTask(ts);
            }
        }
    }

    private class TaskHandler extends Handler {

        public TaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_TASK_EXPIRED:
                    synchronized (mTasks) {
                        TaskStatus runNow = (TaskStatus) message.obj;
                        if (!mPendingTasks.contains(runNow)) {
                            mPendingTasks.add(runNow);
                        }
                    }
                    queueReadyTasksForExecutionH();
                    break;
                case MSG_CHECK_TASKS:
                    // Check the list of tasks and run some of them if we feel inclined.
                    maybeQueueReadyTasksForExecutionH();
                    break;
            }
            maybeRunPendingTasksH();
            // Don't remove TASK_EXPIRED in case one came along while processing the queue.
            removeMessages(MSG_CHECK_TASKS);
        }

        /**
         * Run through list of tasks and execute all possible - at least one is expired so we do
         * as many as we can.
         */
        private void queueReadyTasksForExecutionH() {
            synchronized (mTasks) {
                for (TaskStatus ts : mTasks.getTasks()) {
                    if (isReadyToBeExecutedLocked(ts)) {
                        mPendingTasks.add(ts);
                    } else if (isReadyToBeCancelledLocked(ts)) {
                        stopTaskOnServiceContextLocked(ts);
                    }
                }
            }
        }

        /**
         * The state of at least one task has changed. Here is where we could enforce various
         * policies on when we want to execute tasks.
         * Right now the policy is such:
         * If >1 of the ready tasks is idle mode we send all of them off
         * if more than 2 network connectivity tasks are ready we send them all off.
         * If more than 4 tasks total are ready we send them all off.
         * TODO: It would be nice to consolidate these sort of high-level policies somewhere.
         */
        private void maybeQueueReadyTasksForExecutionH() {
            synchronized (mTasks) {
                int idleCount = 0;
                int backoffCount = 0;
                int connectivityCount = 0;
                List<TaskStatus> runnableTasks = new ArrayList<TaskStatus>();
                for (TaskStatus ts : mTasks.getTasks()) {
                    if (isReadyToBeExecutedLocked(ts)) {
                        if (ts.getNumFailures() > 0) {
                            backoffCount++;
                        }
                        if (ts.hasIdleConstraint()) {
                            idleCount++;
                        }
                        if (ts.hasConnectivityConstraint() || ts.hasUnmeteredConstraint()) {
                            connectivityCount++;
                        }
                        runnableTasks.add(ts);
                    } else if (isReadyToBeCancelledLocked(ts)) {
                        stopTaskOnServiceContextLocked(ts);
                    }
                }
                if (backoffCount > 0 || idleCount >= MIN_IDLE_COUNT ||
                        connectivityCount >= MIN_CONNECTIVITY_COUNT ||
                        runnableTasks.size() >= MIN_READY_TASKS_COUNT) {
                    for (TaskStatus ts : runnableTasks) {
                        mPendingTasks.add(ts);
                    }
                }
            }
        }

        /**
         * Criteria for moving a job into the pending queue:
         *      - It's ready.
         *      - It's not pending.
         *      - It's not already running on a TSC.
         */
        private boolean isReadyToBeExecutedLocked(TaskStatus ts) {
              return ts.isReady() && !mPendingTasks.contains(ts) && !isCurrentlyActiveLocked(ts);
        }

        /**
         * Criteria for cancelling an active job:
         *      - It's not ready
         *      - It's running on a TSC.
         */
        private boolean isReadyToBeCancelledLocked(TaskStatus ts) {
            return !ts.isReady() && isCurrentlyActiveLocked(ts);
        }

        /**
         * Reconcile jobs in the pending queue against available execution contexts.
         * A controller can force a task into the pending queue even if it's already running, but
         * here is where we decide whether to actually execute it.
         */
        private void maybeRunPendingTasksH() {
            synchronized (mTasks) {
                Iterator<TaskStatus> it = mPendingTasks.iterator();
                while (it.hasNext()) {
                    TaskStatus nextPending = it.next();
                    TaskServiceContext availableContext = null;
                    for (TaskServiceContext tsc : mActiveServices) {
                        final TaskStatus running = tsc.getRunningTask();
                        if (running != null && running.matches(nextPending.getUid(),
                                nextPending.getTaskId())) {
                            // Already running this tId for this uId, skip.
                            availableContext = null;
                            break;
                        }
                        if (tsc.isAvailable()) {
                            availableContext = tsc;
                        }
                    }
                    if (availableContext != null) {
                        if (!availableContext.executeRunnableTask(nextPending)) {
                            if (DEBUG) {
                                Slog.d(TAG, "Error executing " + nextPending);
                            }
                            mTasks.remove(nextPending);
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
    final class TaskManagerStub extends ITaskManager.Stub {
        /** Cache determination of whether a given app can persist tasks
         * key is uid of the calling app; value is undetermined/true/false
         */
        private final SparseArray<Boolean> mPersistCache = new SparseArray<Boolean>();

        // Enforce that only the app itself (or shared uid participant) can schedule a
        // task that runs one of the app's services, as well as verifying that the
        // named service properly requires the BIND_TASK_SERVICE permission
        private void enforceValidJobRequest(int uid, Task job) {
            final PackageManager pm = getContext().getPackageManager();
            final ComponentName service = job.getService();
            try {
                ServiceInfo si = pm.getServiceInfo(service, 0);
                if (si.applicationInfo.uid != uid) {
                    throw new IllegalArgumentException("uid " + uid +
                            " cannot schedule job in " + service.getPackageName());
                }
                if (!TaskService.PERMISSION_BIND.equals(si.permission)) {
                    throw new IllegalArgumentException("Scheduled service " + service
                            + " does not require android.permission.BIND_TASK_SERVICE permission");
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
                    // Persisting tasks is tantamount to running at boot, so we permit
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

        // ITaskManager implementation
        @Override
        public int schedule(Task task) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "Scheduling task: " + task);
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();

            enforceValidJobRequest(uid, task);
            final boolean canPersist = canPersistJobs(pid, uid);

            long ident = Binder.clearCallingIdentity();
            try {
                return TaskManagerService.this.schedule(task, uid, canPersist);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public List<Task> getAllPendingTasks() throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                return TaskManagerService.this.getPendingTasks(uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancelAll() throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                TaskManagerService.this.cancelTasksForUid(uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancel(int taskId) throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                TaskManagerService.this.cancelTask(uid, taskId);
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
                TaskManagerService.this.dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }
    };

    void dumpInternal(PrintWriter pw) {
        synchronized (mTasks) {
            pw.println("Registered tasks:");
            if (mTasks.size() > 0) {
                for (TaskStatus ts : mTasks.getTasks()) {
                    ts.dump(pw, "  ");
                }
            } else {
                pw.println();
                pw.println("No tasks scheduled.");
            }
            for (StateController controller : mControllers) {
                pw.println();
                controller.dumpControllerState(pw);
            }
            pw.println();
            pw.println("Pending");
            for (TaskStatus taskStatus : mPendingTasks) {
                pw.println(taskStatus.hashCode());
            }
            pw.println();
            pw.println("Active jobs:");
            for (TaskServiceContext tsc : mActiveServices) {
                if (tsc.isAvailable()) {
                    continue;
                } else {
                    pw.println(tsc.getRunningTask().hashCode() + " for: " +
                            (SystemClock.elapsedRealtime()
                                    - tsc.getExecutionStartTimeElapsed())/1000 + "s " +
                            "timeout: " + tsc.getTimeoutElapsed());
                }
            }
        }
        pw.println();
    }
}
