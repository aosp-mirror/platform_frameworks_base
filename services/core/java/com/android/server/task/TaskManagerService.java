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
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseArray;

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
 * @hide
 */
public class TaskManagerService extends com.android.server.SystemService
        implements StateChangedListener, TaskCompletedListener {
    // TODO: Switch this off for final version.
    private static final boolean DEBUG = true;
    /** The number of concurrent tasks we run at one time. */
    private static final int MAX_TASK_CONTEXTS_COUNT = 3;
    static final String TAG = "TaskManager";
    /**
     * When a task fails, it gets rescheduled according to its backoff policy. To be nice, we allow
     * this amount of time from the rescheduled time by which the retry must occur.
     */
    private static final long RESCHEDULE_WINDOW_SLOP_MILLIS = 5000L;

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
     * Entry point from client to schedule the provided task.
     * This will add the task to the
     * @param task Task object containing execution parameters
     * @param uId The package identifier of the application this task is for.
     * @param canPersistTask Whether or not the client has the appropriate permissions for persisting
     *                    of this task.
     * @return Result of this operation. See <code>TaskManager#RESULT_*</code> return codes.
     */
    public int schedule(Task task, int uId, boolean canPersistTask) {
        TaskStatus taskStatus = new TaskStatus(task, uId, canPersistTask);
        return startTrackingTask(taskStatus) ?
                TaskManager.RESULT_SUCCESS : TaskManager.RESULT_FAILURE;
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

    /**
     * Entry point from client to cancel all tasks originating from their uid.
     * This will remove the task from the master list, and cancel the task if it was staged for
     * execution or being executed.
     * @param uid To check against for removal of a task.
     */
    public void cancelTaskForUid(int uid) {
        // Remove from master list.
        synchronized (mTasks) {
            if (!mTasks.removeAllByUid(uid)) {
                // If it's not in the master list, it's nowhere.
                return;
            }
        }
        // Remove from pending queue.
        synchronized (mPendingTasks) {
            Iterator<TaskStatus> it = mPendingTasks.iterator();
            while (it.hasNext()) {
                TaskStatus ts = it.next();
                if (ts.getUid() == uid) {
                    it.remove();
                }
            }
        }
        // Cancel if running.
        synchronized (mActiveServices) {
            for (TaskServiceContext tsc : mActiveServices) {
                if (tsc.getRunningTask().getUid() == uid) {
                    tsc.cancelExecutingTask();
                }
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
        synchronized (mTasks) {
            if (!mTasks.remove(uid, taskId)) {
                // If it's not in the master list, it's nowhere.
                return;
            }
        }
        synchronized (mPendingTasks) {
            Iterator<TaskStatus> it = mPendingTasks.iterator();
            while (it.hasNext()) {
                TaskStatus ts = it.next();
                if (ts.getUid() == uid && ts.getTaskId() == taskId) {
                    it.remove();
                    // If we got it from pending, it didn't make it to active so return.
                    return;
                }
            }
        }
        synchronized (mActiveServices) {
            for (TaskServiceContext tsc : mActiveServices) {
                if (tsc.getRunningTask().getUid() == uid &&
                        tsc.getRunningTask().getTaskId() == taskId) {
                    tsc.cancelExecutingTask();
                    return;
                }
            }
        }
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
        mTasks = new TaskStore(context);
        mHandler = new TaskHandler(context.getMainLooper());
        mTaskManagerStub = new TaskManagerStub();
        // Create the "runners".
        for (int i = 0; i < MAX_TASK_CONTEXTS_COUNT; i++) {
            mActiveServices.add(
                    new TaskServiceContext(this, context.getMainLooper()));
        }

        mControllers = new LinkedList<StateController>();
        mControllers.add(ConnectivityController.get(this));
        mControllers.add(TimeController.get(this));
        mControllers.add(IdleController.get(this));
        // TODO: Add BatteryStateController when implemented.
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TASK_SERVICE, mTaskManagerStub);
    }

    /**
     * Called when we have a task status object that we need to insert in our
     * {@link com.android.server.task.TaskStore}, and make sure all the relevant controllers know
     * about.
     */
    private boolean startTrackingTask(TaskStatus taskStatus) {
        boolean added = false;
        synchronized (mTasks) {
            added = mTasks.add(taskStatus);
        }
        if (added) {
            for (StateController controller : mControllers) {
                controller.maybeStartTrackingTask(taskStatus);
            }
        }
        return added;
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

    private boolean cancelTaskOnServiceContext(TaskStatus ts) {
        synchronized (mActiveServices) {
            for (TaskServiceContext tsc : mActiveServices) {
                if (tsc.getRunningTask() == ts) {
                    tsc.cancelExecutingTask();
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @param ts TaskStatus we are querying against.
     * @return Whether or not the task represented by the status object is currently being run or
     * is pending.
     */
    private boolean isCurrentlyActive(TaskStatus ts) {
        synchronized (mActiveServices) {
            for (TaskServiceContext serviceContext : mActiveServices) {
                if (serviceContext.getRunningTask() == ts) {
                    return true;
                }
            }
            return false;
        }
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
                newEarliestRuntimeElapsed += Math.pow(initialBackoffMillis, backoffAttempt);
                break;
        }
        long newLatestRuntimeElapsed = failureToReschedule.hasIdleConstraint() ? Long.MAX_VALUE
                : newEarliestRuntimeElapsed + RESCHEDULE_WINDOW_SLOP_MILLIS;
        return new TaskStatus(failureToReschedule, newEarliestRuntimeElapsed,
                newLatestRuntimeElapsed, backoffAttempt);
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
        if (!stopTrackingTask(taskStatus)) {
            if (DEBUG) {
                Slog.e(TAG, "Error removing task: could not find task to remove. Was task" +
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
    public void onTaskDeadlineExpired(TaskStatus taskStatus) {
        mHandler.obtainMessage(MSG_TASK_EXPIRED, taskStatus);
    }

    private class TaskHandler extends Handler {

        public TaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_TASK_EXPIRED:
                    final TaskStatus expired = (TaskStatus) message.obj;  // Unused for now.
                    queueReadyTasksForExecutionH();
                    break;
                case MSG_CHECK_TASKS:
                    // Check the list of tasks and run some of them if we feel inclined.
                    maybeQueueReadyTasksForExecutionH();
                    break;
            }
            maybeRunNextPendingTaskH();
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
                    final boolean criteriaSatisfied = ts.isReady();
                    final boolean isRunning = isCurrentlyActive(ts);
                    if (criteriaSatisfied && !isRunning) {
                        synchronized (mPendingTasks) {
                            mPendingTasks.add(ts);
                        }
                    } else if (!criteriaSatisfied && isRunning) {
                        cancelTaskOnServiceContext(ts);
                    }
                }
            }
        }

        /**
         * The state of at least one task has changed. Here is where we could enforce various
         * policies on when we want to execute tasks.
         * Right now the policy is such:
         *      If >1 of the ready tasks is idle mode we send all of them off
         *      if more than 2 network connectivity tasks are ready we send them all off.
         *      If more than 4 tasks total are ready we send them all off.
         *      TODO: It would be nice to consolidate these sort of high-level policies somewhere.
         */
        private void maybeQueueReadyTasksForExecutionH() {
            synchronized (mTasks) {
                int idleCount = 0;
                int connectivityCount = 0;
                List<TaskStatus> runnableTasks = new ArrayList<TaskStatus>();
                for (TaskStatus ts : mTasks.getTasks()) {
                    final boolean criteriaSatisfied = ts.isReady();
                    final boolean isRunning = isCurrentlyActive(ts);
                    if (criteriaSatisfied && !isRunning) {
                        if (ts.hasIdleConstraint()) {
                            idleCount++;
                        }
                        if (ts.hasConnectivityConstraint() || ts.hasMeteredConstraint()) {
                            connectivityCount++;
                        }
                        runnableTasks.add(ts);
                    } else if (!criteriaSatisfied && isRunning) {
                        cancelTaskOnServiceContext(ts);
                    }
                }
                if (idleCount >= MIN_IDLE_COUNT || connectivityCount >= MIN_CONNECTIVITY_COUNT ||
                        runnableTasks.size() >= MIN_READY_TASKS_COUNT) {
                    for (TaskStatus ts : runnableTasks) {
                        synchronized (mPendingTasks) {
                            mPendingTasks.add(ts);
                        }
                    }
                }
            }
        }

        /**
         * Checks the state of the pending queue against any available
         * {@link com.android.server.task.TaskServiceContext} that can run a new task.
         * {@link com.android.server.task.TaskServiceContext}.
         */
        private void maybeRunNextPendingTaskH() {
            TaskStatus nextPending;
            synchronized (mPendingTasks) {
                nextPending = mPendingTasks.poll();
            }
            if (nextPending == null) {
                return;
            }

            synchronized (mActiveServices) {
                for (TaskServiceContext tsc : mActiveServices) {
                    if (tsc.isAvailable()) {
                        if (tsc.executeRunnableTask(nextPending)) {
                            return;
                        }
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

        // Determine whether the caller is allowed to persist tasks, with a small cache
        // because the lookup is expensive enough that we'd like to avoid repeating it.
        // This must be called from within the calling app's binder identity!
        private boolean canCallerPersistTasks() {
            final boolean canPersist;
            final int callingUid = Binder.getCallingUid();
            synchronized (mPersistCache) {
                Boolean cached = mPersistCache.get(callingUid);
                if (cached) {
                    canPersist = cached.booleanValue();
                } else {
                    // Persisting tasks is tantamount to running at boot, so we permit
                    // it when the app has declared that it uses the RECEIVE_BOOT_COMPLETED
                    // permission
                    int result = getContext().checkCallingPermission(
                            android.Manifest.permission.RECEIVE_BOOT_COMPLETED);
                    canPersist = (result == PackageManager.PERMISSION_GRANTED);
                    mPersistCache.put(callingUid, canPersist);
                }
            }
            return canPersist;
        }

        // ITaskManager implementation
        @Override
        public int schedule(Task task) throws RemoteException {
            final boolean canPersist = canCallerPersistTasks();
            final int uid = Binder.getCallingUid();

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
                TaskManagerService.this.cancelTaskForUid(uid);
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
            pw.print("Registered tasks:");
            if (mTasks.size() > 0) {
                for (TaskStatus ts : mTasks.getTasks()) {
                    pw.println();
                    ts.dump(pw, "  ");
                }
            } else {
                pw.println();
                pw.println("No tasks scheduled.");
            }
        }
        pw.println();
    }
}
