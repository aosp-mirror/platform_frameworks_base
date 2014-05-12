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

import android.app.ActivityManager;
import android.app.task.ITaskCallback;
import android.app.task.ITaskService;
import android.app.task.TaskParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.task.controllers.TaskStatus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains information required to bind to a {@link android.app.task.TaskService}. This binding
 * is reused to start concurrent tasks on the TaskService. Information here is unique
 * to the service.
 * Functionality provided by this class:
 *     - Managages wakelock for the service.
 *     - Sends onStartTask() and onStopTask() messages to client app, and handles callbacks.
 *     -
 */
public class TaskServiceContext extends ITaskCallback.Stub implements ServiceConnection {
    private static final String TAG = "TaskServiceContext";
    /** Define the maximum # of tasks allowed to run on a service at once. */
    private static final int defaultMaxActiveTasksPerService =
            ActivityManager.isLowRamDeviceStatic() ? 1 : 3;
    /** Amount of time a task is allowed to execute for before being considered timed-out. */
    private static final long EXECUTING_TIMESLICE_MILLIS = 5 * 60 * 1000;
    /** Amount of time the TaskManager will wait for a response from an app for a message. */
    private static final long OP_TIMEOUT_MILLIS = 8 * 1000;
    /** String prefix for all wakelock names. */
    private static final String TM_WAKELOCK_PREFIX = "*task*/";

    private static final String[] VERB_STRINGS = {
            "VERB_STARTING", "VERB_EXECUTING", "VERB_STOPPING", "VERB_PENDING"
    };

    // States that a task occupies while interacting with the client.
    private static final int VERB_STARTING = 0;
    private static final int VERB_EXECUTING = 1;
    private static final int VERB_STOPPING = 2;
    private static final int VERB_PENDING = 3;

    // Messages that result from interactions with the client service.
    /** System timed out waiting for a response. */
    private static final int MSG_TIMEOUT = 0;
    /** Received a callback from client. */
    private static final int MSG_CALLBACK = 1;
    /** Run through list and start any ready tasks.*/
    private static final int MSG_CHECK_PENDING = 2;
    /** Cancel an active task. */
    private static final int MSG_CANCEL = 3;
    /** Add a pending task. */
    private static final int MSG_ADD_PENDING = 4;
    /** Client crashed, so we need to wind things down. */
    private static final int MSG_SHUTDOWN = 5;

    /** Used to identify this task service context when communicating with the TaskManager. */
    final int token;
    final ComponentName component;
    final int userId;
    ITaskService service;
    private final Handler mCallbackHandler;
    /** Tasks that haven't been sent to the client for execution yet. */
    private final SparseArray<ActiveTask> mPending;
    /** Used for service binding, etc. */
    private final Context mContext;
    /** Make callbacks to {@link TaskManagerService} to inform on task completion status. */
    final private TaskCompletedListener mCompletedListener;
    private final PowerManager.WakeLock mWakeLock;

    /** Whether this service is actively bound. */
    boolean mBound;

    TaskServiceContext(TaskManagerService taskManager, Looper looper, TaskStatus taskStatus) {
        mContext = taskManager.getContext();
        this.component = taskStatus.getServiceComponent();
        this.token = taskStatus.getServiceToken();
        this.userId = taskStatus.getUserId();
        mCallbackHandler = new TaskServiceHandler(looper);
        mPending = new SparseArray<ActiveTask>();
        mCompletedListener = taskManager;
        final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                TM_WAKELOCK_PREFIX + component.getPackageName());
        mWakeLock.setWorkSource(new WorkSource(taskStatus.getUid()));
        mWakeLock.setReferenceCounted(false);
    }

    @Override
    public void taskFinished(int taskId, boolean reschedule) {
        mCallbackHandler.obtainMessage(MSG_CALLBACK, taskId, reschedule ? 1 : 0)
                .sendToTarget();
    }

    @Override
    public void acknowledgeStopMessage(int taskId, boolean reschedule) {
        mCallbackHandler.obtainMessage(MSG_CALLBACK, taskId, reschedule ? 1 : 0)
                .sendToTarget();
    }

    @Override
    public void acknowledgeStartMessage(int taskId, boolean ongoing) {
        mCallbackHandler.obtainMessage(MSG_CALLBACK, taskId, ongoing ? 1 : 0).sendToTarget();
    }

    /**
     * Queue up this task to run on the client. This will execute the task as quickly as possible.
     * @param ts Status of the task to run.
     */
    public void addPendingTask(TaskStatus ts) {
        final TaskParams params = new TaskParams(ts.getTaskId(), ts.getExtras(), this);
        final ActiveTask newTask = new ActiveTask(params, VERB_PENDING);
        mCallbackHandler.obtainMessage(MSG_ADD_PENDING, newTask).sendToTarget();
        if (!mBound) {
            Intent intent = new Intent().setComponent(component);
            boolean binding = mContext.bindServiceAsUser(intent, this,
                    Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND,
                    new UserHandle(userId));
            if (!binding) {
                Log.e(TAG, component.getShortClassName() + " unavailable.");
                cancelPendingTask(ts);
            }
        }
    }

    /**
     * Called externally when a task that was scheduled for execution should be cancelled.
     * @param ts The status of the task to cancel.
     */
    public void cancelPendingTask(TaskStatus ts) {
        mCallbackHandler.obtainMessage(MSG_CANCEL, ts.getTaskId(), -1 /* arg2 */)
                .sendToTarget();
    }

    /**
     * MSG_TIMEOUT is sent with the {@link com.android.server.task.TaskServiceContext.ActiveTask}
     * set in the {@link Message#obj} field. This makes it easier to remove timeouts for a given
     * ActiveTask.
     * @param op Operation that is taking place.
     */
    private void scheduleOpTimeOut(ActiveTask op) {
        mCallbackHandler.removeMessages(MSG_TIMEOUT, op);

        final long timeoutMillis = (op.verb == VERB_EXECUTING) ?
                EXECUTING_TIMESLICE_MILLIS : OP_TIMEOUT_MILLIS;
        if (Log.isLoggable(TaskManagerService.TAG, Log.DEBUG)) {
            Slog.d(TAG, "Scheduling time out for '" + component.getShortClassName() + "' tId: " +
                    op.params.getTaskId() + ", in " + (timeoutMillis / 1000) + " s");
        }
        Message m = mCallbackHandler.obtainMessage(MSG_TIMEOUT, op);
        mCallbackHandler.sendMessageDelayed(m, timeoutMillis);
    }

    /**
     * @return true if this task is pending or active within this context.
     */
    public boolean hasTaskPending(TaskStatus taskStatus) {
        synchronized (mPending) {
            return mPending.get(taskStatus.getTaskId()) != null;
        }
    }

    public boolean isBound() {
        return mBound;
    }

    /**
     * We acquire/release the wakelock on onServiceConnected/unbindService. This mirrors the work
     * we intend to send to the client - we stop sending work when the service is unbound so until
     * then we keep the wakelock.
     * @param name The concrete component name of the service that has
     * been connected.
     * @param service The IBinder of the Service's communication channel,
     */
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mBound = true;
        this.service = ITaskService.Stub.asInterface(service);
        // Remove all timeouts. We've just connected to the client so there are no other
        // MSG_TIMEOUTs at this point.
        mCallbackHandler.removeMessages(MSG_TIMEOUT);
        mWakeLock.acquire();
        mCallbackHandler.obtainMessage(MSG_CHECK_PENDING).sendToTarget();
    }

    /**
     * When the client service crashes we can have a couple tasks executing, in various stages of
     * undress. We'll cancel all of them and request that they be rescheduled.
     * @param name The concrete component name of the service whose
     */
    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Service disconnected... probably client crashed.
        startShutdown();
    }

    /**
     * We don't just shutdown outright - we make sure the scheduler isn't going to send us any more
     * tasks, then we do the shutdown.
     */
    private void startShutdown() {
        mCompletedListener.onClientExecutionCompleted(token);
        mCallbackHandler.obtainMessage(MSG_SHUTDOWN).sendToTarget();
    }

    /** Tracks a task across its various state changes. */
    private static class ActiveTask {
        final TaskParams params;
        int verb;
        AtomicBoolean cancelled = new AtomicBoolean();

        ActiveTask(TaskParams params, int verb) {
            this.params = params;
            this.verb = verb;
        }

        @Override
        public String toString() {
            return params.getTaskId() + " " + VERB_STRINGS[verb];
        }
    }

    /**
     * Handles the lifecycle of the TaskService binding/callbacks, etc. The convention within this
     * class is to append 'H' to each function name that can only be called on this handler. This
     * isn't strictly necessary because all of these functions are private, but helps clarity.
     */
    private class TaskServiceHandler extends Handler {
        TaskServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_ADD_PENDING:
                    if (message.obj != null) {
                        ActiveTask pendingTask = (ActiveTask) message.obj;
                        mPending.put(pendingTask.params.getTaskId(), pendingTask);
                    }
                    // fall through.
                case MSG_CHECK_PENDING:
                    checkPendingTasksH();
                    break;
                case MSG_CALLBACK:
                    ActiveTask receivedCallback = mPending.get(message.arg1);
                    removeMessages(MSG_TIMEOUT, receivedCallback);

                    if (Log.isLoggable(TaskManagerService.TAG, Log.DEBUG)) {
                        Log.d(TAG, "MSG_CALLBACK of : " + receivedCallback);
                    }

                    if (receivedCallback.verb == VERB_STARTING) {
                        final boolean workOngoing = message.arg2 == 1;
                        handleStartedH(receivedCallback, workOngoing);
                    } else if (receivedCallback.verb == VERB_EXECUTING ||
                            receivedCallback.verb == VERB_STOPPING) {
                        final boolean reschedule = message.arg2 == 1;
                        handleFinishedH(receivedCallback, reschedule);
                    } else {
                        if (Log.isLoggable(TaskManagerService.TAG, Log.DEBUG)) {
                            Log.d(TAG, "Unrecognised callback: " + receivedCallback);
                        }
                    }
                    break;
                case MSG_CANCEL:
                    ActiveTask cancelled = mPending.get(message.arg1);
                    handleCancelH(cancelled);
                    break;
                case MSG_TIMEOUT:
                    // Timeout msgs have the ActiveTask ref so we can remove them easily.
                    handleOpTimeoutH((ActiveTask) message.obj);
                    break;
                case MSG_SHUTDOWN:
                    handleShutdownH();
                    break;
                default:
                    Log.e(TAG, "Unrecognised message: " + message);
            }
        }

        /**
         * State behaviours.
         * VERB_STARTING   -> Successful start, change task to VERB_EXECUTING and post timeout.
         *     _PENDING    -> Error
         *     _EXECUTING  -> Error
         *     _STOPPING   -> Error
         */
        private void handleStartedH(ActiveTask started, boolean workOngoing) {
            switch (started.verb) {
                case VERB_STARTING:
                    started.verb = VERB_EXECUTING;
                    if (!workOngoing) {
                        // Task is finished already so fast-forward to handleFinished.
                        handleFinishedH(started, false);
                        return;
                    } else if (started.cancelled.get()) {
                        // Cancelled *while* waiting for acknowledgeStartMessage from client.
                        handleCancelH(started);
                        return;
                    } else {
                        scheduleOpTimeOut(started);
                    }
                    break;
                default:
                    Log.e(TAG, "Handling started task but task wasn't starting! " + started);
                    return;
            }
        }

        /**
         * VERB_EXECUTING  -> Client called taskFinished(), clean up and notify done.
         *     _STOPPING   -> Successful finish, clean up and notify done.
         *     _STARTING   -> Error
         *     _PENDING    -> Error
         */
        private void handleFinishedH(ActiveTask executedTask, boolean reschedule) {
            switch (executedTask.verb) {
                case VERB_EXECUTING:
                case VERB_STOPPING:
                    closeAndCleanupTaskH(executedTask, reschedule);
                    break;
                default:
                    Log.e(TAG, "Got an execution complete message for a task that wasn't being" +
                            "executed. " + executedTask);
            }
        }

        /**
         * A task can be in various states when a cancel request comes in:
         * VERB_PENDING    -> Remove from queue.
         *     _STARTING   -> Mark as cancelled and wait for {@link #acknowledgeStartMessage(int)}.
         *     _EXECUTING  -> call {@link #sendStopMessageH}}.
         *     _ENDING     -> No point in doing anything here, so we ignore.
         */
        private void handleCancelH(ActiveTask cancelledTask) {
            switch (cancelledTask.verb) {
                case VERB_PENDING:
                    mPending.remove(cancelledTask.params.getTaskId());
                    break;
                case VERB_STARTING:
                    cancelledTask.cancelled.set(true);
                    break;
                case VERB_EXECUTING:
                    cancelledTask.verb = VERB_STOPPING;
                    sendStopMessageH(cancelledTask);
                    break;
                case VERB_STOPPING:
                    // Nada.
                    break;
                default:
                    Log.e(TAG, "Cancelling a task without a valid verb: " + cancelledTask);
                    break;
            }
        }

        /**
         * This TaskServiceContext is shutting down. Remove all the tasks from the pending queue
         * and reschedule them as if they had failed.
         * Before posting this message, caller must invoke
         * {@link com.android.server.task.TaskCompletedListener#onClientExecutionCompleted(int)}
         */
        private void handleShutdownH() {
            for (int i = 0; i < mPending.size(); i++) {
                ActiveTask at = mPending.valueAt(i);
                closeAndCleanupTaskH(at, true /* needsReschedule */);
            }
            mWakeLock.release();
            mContext.unbindService(TaskServiceContext.this);
            service = null;
            mBound = false;
        }

        /**
         * MSG_TIMEOUT gets processed here.
         * @param timedOutTask The task that timed out.
         */
        private void handleOpTimeoutH(ActiveTask timedOutTask) {
            if (Log.isLoggable(TaskManagerService.TAG, Log.DEBUG)) {
                Log.d(TAG, "MSG_TIMEOUT of " + component.getShortClassName() + " : "
                        + timedOutTask.params.getTaskId());
            }

            final int taskId = timedOutTask.params.getTaskId();
            switch (timedOutTask.verb) {
                case VERB_STARTING:
                    // Client unresponsive - wedged or failed to respond in time. We don't really
                    // know what happened so let's log it and notify the TaskManager
                    // FINISHED/NO-RETRY.
                    Log.e(TAG, "No response from client for onStartTask '" +
                            component.getShortClassName() + "' tId: " + taskId);
                    closeAndCleanupTaskH(timedOutTask, false /* needsReschedule */);
                    break;
                case VERB_STOPPING:
                    // At least we got somewhere, so fail but ask the TaskManager to reschedule.
                    Log.e(TAG, "No response from client for onStopTask, '" +
                            component.getShortClassName() + "' tId: " + taskId);
                    closeAndCleanupTaskH(timedOutTask, true /* needsReschedule */);
                    break;
                case VERB_EXECUTING:
                    // Not an error - client ran out of time.
                    Log.i(TAG, "Client timed out while executing (no taskFinished received)." +
                            " Reporting failure and asking for reschedule. "  +
                            component.getShortClassName() + "' tId: " + taskId);
                    sendStopMessageH(timedOutTask);
                    break;
                default:
                    Log.e(TAG, "Handling timeout for an unknown active task state: "
                            + timedOutTask);
                    return;
            }
        }

        /**
         * Called on the handler thread. Checks the state of the pending queue and starts the task
         * if it can. The task only starts if there is capacity on the service.
         */
        private void checkPendingTasksH() {
            if (!mBound) {
                return;
            }
            for (int i = 0; i < mPending.size() && i < defaultMaxActiveTasksPerService; i++) {
                ActiveTask at = mPending.valueAt(i);
                if (at.verb != VERB_PENDING) {
                    continue;
                }
                sendStartMessageH(at);
            }
        }

        /**
         * Already running, need to stop. Rund on handler.
         * @param stoppingTask Task we are sending onStopMessage for. This task will be moved from
         *                     VERB_EXECUTING -> VERB_STOPPING.
         */
        private void sendStopMessageH(ActiveTask stoppingTask) {
            mCallbackHandler.removeMessages(MSG_TIMEOUT, stoppingTask);
            if (stoppingTask.verb != VERB_EXECUTING) {
                Log.e(TAG, "Sending onStopTask for a task that isn't started. " + stoppingTask);
                // TODO: Handle error?
                return;
            }
            try {
                service.stopTask(stoppingTask.params);
                stoppingTask.verb = VERB_STOPPING;
                scheduleOpTimeOut(stoppingTask);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending onStopTask to client.", e);
                closeAndCleanupTaskH(stoppingTask, false);
            }
        }

        /** Start the task on the service. */
        private void sendStartMessageH(ActiveTask pendingTask) {
            if (pendingTask.verb != VERB_PENDING) {
                Log.e(TAG, "Sending onStartTask for a task that isn't pending. " + pendingTask);
                // TODO: Handle error?
            }
            try {
                service.startTask(pendingTask.params);
                pendingTask.verb = VERB_STARTING;
                scheduleOpTimeOut(pendingTask);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending onStart message to '" + component.getShortClassName()
                        + "' ", e);
            }
        }

        /**
         * The provided task has finished, either by calling
         * {@link android.app.task.TaskService#taskFinished(android.app.task.TaskParams, boolean)}
         * or from acknowledging the stop message we sent. Either way, we're done tracking it and
         * we want to clean up internally.
         */
        private void closeAndCleanupTaskH(ActiveTask completedTask, boolean reschedule) {
            removeMessages(MSG_TIMEOUT, completedTask);
            mPending.remove(completedTask.params.getTaskId());
            if (mPending.size() == 0) {
                startShutdown();
            }
            mCompletedListener.onTaskCompleted(token, completedTask.params.getTaskId(), reschedule);
        }
    }
}
