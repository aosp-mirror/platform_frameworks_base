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
import android.os.Binder;
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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.task.controllers.TaskStatus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles client binding and lifecycle of a task. A task will only execute one at a time on an
 * instance of this class.
 */
public class TaskServiceContext extends ITaskCallback.Stub implements ServiceConnection {
    private static final boolean DEBUG = true;
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
    static final int VERB_BINDING = 0;
    static final int VERB_STARTING = 1;
    static final int VERB_EXECUTING = 2;
    static final int VERB_STOPPING = 3;

    // Messages that result from interactions with the client service.
    /** System timed out waiting for a response. */
    private static final int MSG_TIMEOUT = 0;
    /** Received a callback from client. */
    private static final int MSG_CALLBACK = 1;
    /** Run through list and start any ready tasks.*/
    private static final int MSG_SERVICE_BOUND = 2;
    /** Cancel a task. */
    private static final int MSG_CANCEL = 3;
    /** Shutdown the Task. Used when the client crashes and we can't die gracefully.*/
    private static final int MSG_SHUTDOWN_EXECUTION = 4;

    private final Handler mCallbackHandler;
    /** Make callbacks to {@link TaskManagerService} to inform on task completion status. */
    private final TaskCompletedListener mCompletedListener;
    /** Used for service binding, etc. */
    private final Context mContext;
    private PowerManager.WakeLock mWakeLock;

    // Execution state.
    private TaskParams mParams;
    @VisibleForTesting
    int mVerb;
    private AtomicBoolean mCancelled = new AtomicBoolean();

    /** All the information maintained about the task currently being executed. */
    private TaskStatus mRunningTask;
    /** Binder to the client service. */
    ITaskService service;

    private final Object mAvailableLock = new Object();
    /** Whether this context is free. */
    @GuardedBy("mAvailableLock")
    private boolean mAvailable;

    TaskServiceContext(TaskManagerService service, Looper looper) {
        this(service.getContext(), service, looper);
    }

    @VisibleForTesting
    TaskServiceContext(Context context, TaskCompletedListener completedListener, Looper looper) {
        mContext = context;
        mCallbackHandler = new TaskServiceHandler(looper);
        mCompletedListener = completedListener;
    }

    /**
     * Give a task to this context for execution. Callers must first check {@link #isAvailable()}
     * to make sure this is a valid context.
     * @param ts The status of the task that we are going to run.
     * @return True if the task was accepted and is going to run.
     */
    boolean executeRunnableTask(TaskStatus ts) {
        synchronized (mAvailableLock) {
            if (!mAvailable) {
                Slog.e(TAG, "Starting new runnable but context is unavailable > Error.");
                return false;
            }
            mAvailable = false;
        }

        final PowerManager pm =
                (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                TM_WAKELOCK_PREFIX + ts.getServiceComponent().getPackageName());
        mWakeLock.setWorkSource(new WorkSource(ts.getUid()));
        mWakeLock.setReferenceCounted(false);

        mRunningTask = ts;
        mParams = new TaskParams(ts.getTaskId(), ts.getExtras(), this);

        mVerb = VERB_BINDING;
        final Intent intent = new Intent().setComponent(ts.getServiceComponent());
        boolean binding = mContext.bindServiceAsUser(intent, this,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND,
                new UserHandle(ts.getUserId()));
        if (!binding) {
            if (DEBUG) {
                Slog.d(TAG, ts.getServiceComponent().getShortClassName() + " unavailable.");
            }
            return false;
        }

        return true;
    }

    /** Used externally to query the running task. Will return null if there is no task running. */
    TaskStatus getRunningTask() {
        return mRunningTask;
    }

    /** Called externally when a task that was scheduled for execution should be cancelled. */
    void cancelExecutingTask() {
        mCallbackHandler.obtainMessage(MSG_CANCEL).sendToTarget();
    }

    /**
     * @return Whether this context is available to handle incoming work.
     */
    boolean isAvailable() {
        synchronized (mAvailableLock) {
            return mAvailable;
        }
    }

    @Override
    public void taskFinished(int taskId, boolean reschedule) {
        if (!verifyCallingUid()) {
            return;
        }
        mCallbackHandler.obtainMessage(MSG_CALLBACK, taskId, reschedule ? 1 : 0)
                .sendToTarget();
    }

    @Override
    public void acknowledgeStopMessage(int taskId, boolean reschedule) {
        if (!verifyCallingUid()) {
            return;
        }
        mCallbackHandler.obtainMessage(MSG_CALLBACK, taskId, reschedule ? 1 : 0)
                .sendToTarget();
    }

    @Override
    public void acknowledgeStartMessage(int taskId, boolean ongoing) {
        if (!verifyCallingUid()) {
            return;
        }
        mCallbackHandler.obtainMessage(MSG_CALLBACK, taskId, ongoing ? 1 : 0).sendToTarget();
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
        if (!name.equals(mRunningTask.getServiceComponent())) {
            mCallbackHandler.obtainMessage(MSG_SHUTDOWN_EXECUTION).sendToTarget();
            return;
        }
        this.service = ITaskService.Stub.asInterface(service);
        // Remove all timeouts.
        mCallbackHandler.removeMessages(MSG_TIMEOUT);
        mWakeLock.acquire();
        mCallbackHandler.obtainMessage(MSG_SERVICE_BOUND).sendToTarget();
    }

    /**
     * If the client service crashes we reschedule this task and clean up.
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
        if (mRunningTask == null || Binder.getCallingUid() != mRunningTask.getUid()) {
            if (DEBUG) {
                Slog.d(TAG, "Stale callback received, ignoring.");
            }
            return false;
        }
        return true;
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
                case MSG_SERVICE_BOUND:
                    handleServiceBoundH();
                    break;
                case MSG_CALLBACK:
                    if (DEBUG) {
                        Slog.d(TAG, "MSG_CALLBACK of : " + mRunningTask);
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
                            Slog.d(TAG, "Unrecognised callback: " + mRunningTask);
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
                    closeAndCleanupTaskH(true /* needsReschedule */);
                default:
                    Log.e(TAG, "Unrecognised message: " + message);
            }
        }

        /** Start the task on the service. */
        private void handleServiceBoundH() {
            if (mVerb != VERB_BINDING) {
                Slog.e(TAG, "Sending onStartTask for a task that isn't pending. "
                        + VERB_STRINGS[mVerb]);
                closeAndCleanupTaskH(false /* reschedule */);
                return;
            }
            if (mCancelled.get()) {
                if (DEBUG) {
                    Slog.d(TAG, "Task cancelled while waiting for bind to complete. "
                            + mRunningTask);
                }
                closeAndCleanupTaskH(true /* reschedule */);
                return;
            }
            try {
                mVerb = VERB_STARTING;
                scheduleOpTimeOut();
                service.startTask(mParams);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending onStart message to '" +
                        mRunningTask.getServiceComponent().getShortClassName() + "' ", e);
            }
        }

        /**
         * State behaviours.
         * VERB_STARTING   -> Successful start, change task to VERB_EXECUTING and post timeout.
         *     _PENDING    -> Error
         *     _EXECUTING  -> Error
         *     _STOPPING   -> Error
         */
        private void handleStartedH(boolean workOngoing) {
            switch (mVerb) {
                case VERB_STARTING:
                    mVerb = VERB_EXECUTING;
                    if (!workOngoing) {
                        // Task is finished already so fast-forward to handleFinished.
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
                    Log.e(TAG, "Handling started task but task wasn't starting! Was "
                            + VERB_STRINGS[mVerb] + ".");
                    return;
            }
        }

        /**
         * VERB_EXECUTING  -> Client called taskFinished(), clean up and notify done.
         *     _STOPPING   -> Successful finish, clean up and notify done.
         *     _STARTING   -> Error
         *     _PENDING    -> Error
         */
        private void handleFinishedH(boolean reschedule) {
            switch (mVerb) {
                case VERB_EXECUTING:
                case VERB_STOPPING:
                    closeAndCleanupTaskH(reschedule);
                    break;
                default:
                    Slog.e(TAG, "Got an execution complete message for a task that wasn't being" +
                            "executed. Was " + VERB_STRINGS[mVerb] + ".");
            }
        }

        /**
         * A task can be in various states when a cancel request comes in:
         * VERB_BINDING    -> Cancelled before bind completed. Mark as cancelled and wait for
         *                    {@link #onServiceConnected(android.content.ComponentName, android.os.IBinder)}
         *     _STARTING   -> Mark as cancelled and wait for
         *                    {@link TaskServiceContext#acknowledgeStartMessage(int, boolean)}
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
                    Slog.e(TAG, "Cancelling a task without a valid verb: " + mVerb);
                    break;
            }
        }

        /** Process MSG_TIMEOUT here. */
        private void handleOpTimeoutH() {
            if (Log.isLoggable(TaskManagerService.TAG, Log.DEBUG)) {
                Log.d(TAG, "MSG_TIMEOUT of " +
                        mRunningTask.getServiceComponent().getShortClassName() + " : "
                        + mParams.getTaskId());
            }

            final int taskId = mParams.getTaskId();
            switch (mVerb) {
                case VERB_STARTING:
                    // Client unresponsive - wedged or failed to respond in time. We don't really
                    // know what happened so let's log it and notify the TaskManager
                    // FINISHED/NO-RETRY.
                    Log.e(TAG, "No response from client for onStartTask '" +
                            mRunningTask.getServiceComponent().getShortClassName() + "' tId: "
                            + taskId);
                    closeAndCleanupTaskH(false /* needsReschedule */);
                    break;
                case VERB_STOPPING:
                    // At least we got somewhere, so fail but ask the TaskManager to reschedule.
                    Log.e(TAG, "No response from client for onStopTask, '" +
                            mRunningTask.getServiceComponent().getShortClassName() + "' tId: "
                            + taskId);
                    closeAndCleanupTaskH(true /* needsReschedule */);
                    break;
                case VERB_EXECUTING:
                    // Not an error - client ran out of time.
                    Log.i(TAG, "Client timed out while executing (no taskFinished received)." +
                            " Reporting failure and asking for reschedule. "  +
                            mRunningTask.getServiceComponent().getShortClassName() + "' tId: "
                            + taskId);
                    sendStopMessageH();
                    break;
                default:
                    Log.e(TAG, "Handling timeout for an unknown active task state: "
                            + mRunningTask);
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
                Log.e(TAG, "Sending onStopTask for a task that isn't started. " + mRunningTask);
                closeAndCleanupTaskH(false /* reschedule */);
                return;
            }
            try {
                mVerb = VERB_STOPPING;
                scheduleOpTimeOut();
                service.stopTask(mParams);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending onStopTask to client.", e);
                closeAndCleanupTaskH(false);
            }
        }

        /**
         * The provided task has finished, either by calling
         * {@link android.app.task.TaskService#taskFinished(android.app.task.TaskParams, boolean)}
         * or from acknowledging the stop message we sent. Either way, we're done tracking it and
         * we want to clean up internally.
         */
        private void closeAndCleanupTaskH(boolean reschedule) {
            removeMessages(MSG_TIMEOUT);
            mWakeLock.release();
            mContext.unbindService(TaskServiceContext.this);
            mCompletedListener.onTaskCompleted(mRunningTask, reschedule);

            mWakeLock = null;
            mRunningTask = null;
            mParams = null;
            mVerb = -1;
            mCancelled.set(false);
            service = null;
            synchronized (mAvailableLock) {
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
                        mRunningTask.getServiceComponent().getShortClassName() + "' tId: " +
                        mParams.getTaskId() + ", in " + (timeoutMillis / 1000) + " s");
            }
            Message m = mCallbackHandler.obtainMessage(MSG_TIMEOUT);
            mCallbackHandler.sendMessageDelayed(m, timeoutMillis);
        }
    }
}
