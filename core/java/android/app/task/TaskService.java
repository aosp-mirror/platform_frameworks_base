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

package android.app.task;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

/**
 * <p>Entry point for the callback from the {@link android.content.TaskManager}.</p>
 * <p>This is the base class that handles asynchronous requests that were previously scheduled. You
 * are responsible for overriding {@link TaskService#onStartTask(TaskParams)}, which is where
 * you will implement your task logic.</p>
 * <p>This service executes each incoming task on a {@link android.os.Handler} running on your
 * application's main thread. This means that you <b>must</b> offload your execution logic to
 * another thread/handler/{@link android.os.AsyncTask} of your choosing. Not doing so will result
 * in blocking any future callbacks from the TaskManager - specifically
 * {@link #onStopTask(android.app.task.TaskParams)}, which is meant to inform you that the
 * scheduling requirements are no longer being met.</p>
 */
public abstract class TaskService extends Service {
    private static final String TAG = "TaskService";

    /**
     * Task services must be protected with this permission:
     *
     * <pre class="prettyprint">
     *     <service android:name="MyTaskService"
     *              android:permission="android.permission.BIND_TASK_SERVICE" >
     *         ...
     *     </service>
     * </pre>
     *
     * <p>If a task service is declared in the manifest but not protected with this
     * permission, that service will be ignored by the OS.
     */
    public static final String PERMISSION_BIND =
            "android.permission.BIND_TASK_SERVICE";

    /**
     * Identifier for a message that will result in a call to
     * {@link #onStartTask(android.app.task.TaskParams)}.
     */
    private final int MSG_EXECUTE_TASK = 0;
    /**
     * Message that will result in a call to {@link #onStopTask(android.app.task.TaskParams)}.
     */
    private final int MSG_STOP_TASK = 1;
    /**
     * Message that the client has completed execution of this task.
     */
    private final int MSG_TASK_FINISHED = 2;

    /** Lock object for {@link #mHandler}. */
    private final Object mHandlerLock = new Object();

    /**
     * Handler we post tasks to. Responsible for calling into the client logic, and handling the
     * callback to the system.
     */
    @GuardedBy("mHandlerLock")
    TaskHandler mHandler;

    /** Binder for this service. */
    ITaskService mBinder = new ITaskService.Stub() {
        @Override
        public void startTask(TaskParams taskParams) {
            ensureHandler();
            Message m = Message.obtain(mHandler, MSG_EXECUTE_TASK, taskParams);
            m.sendToTarget();
        }
        @Override
        public void stopTask(TaskParams taskParams) {
            ensureHandler();
            Message m = Message.obtain(mHandler, MSG_STOP_TASK, taskParams);
            m.sendToTarget();
        }
    };

    /** @hide */
    void ensureHandler() {
        synchronized (mHandlerLock) {
            if (mHandler == null) {
                mHandler = new TaskHandler(getMainLooper());
            }
        }
    }

    /**
     * Runs on application's main thread - callbacks are meant to offboard work to some other
     * (app-specified) mechanism.
     * @hide
     */
    class TaskHandler extends Handler {
        TaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final TaskParams params = (TaskParams) msg.obj;
            switch (msg.what) {
                case MSG_EXECUTE_TASK:
                    try {
                        boolean workOngoing = TaskService.this.onStartTask(params);
                        ackStartMessage(params, workOngoing);
                    } catch (Exception e) {
                        Log.e(TAG, "Error while executing task: " + params.getTaskId());
                        throw new RuntimeException(e);
                    }
                    break;
                case MSG_STOP_TASK:
                    try {
                        boolean ret = TaskService.this.onStopTask(params);
                        ackStopMessage(params, ret);
                    } catch (Exception e) {
                        Log.e(TAG, "Application unable to handle onStopTask.", e);
                        throw new RuntimeException(e);
                    }
                    break;
                case MSG_TASK_FINISHED:
                    final boolean needsReschedule = (msg.arg2 == 1);
                    ITaskCallback callback = params.getCallback();
                    if (callback != null) {
                        try {
                            callback.taskFinished(params.getTaskId(), needsReschedule);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error reporting task finish to system: binder has gone" +
                                    "away.");
                        }
                    } else {
                        Log.e(TAG, "finishTask() called for a nonexistent task id.");
                    }
                    break;
                default:
                    Log.e(TAG, "Unrecognised message received.");
                    break;
            }
        }

        private void ackStartMessage(TaskParams params, boolean workOngoing) {
            final ITaskCallback callback = params.getCallback();
            final int taskId = params.getTaskId();
            if (callback != null) {
                try {
                     callback.acknowledgeStartMessage(taskId, workOngoing);
                } catch(RemoteException e) {
                    Log.e(TAG, "System unreachable for starting task.");
                }
            } else {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Attempting to ack a task that has already been processed.");
                }
            }
        }

        private void ackStopMessage(TaskParams params, boolean reschedule) {
            final ITaskCallback callback = params.getCallback();
            final int taskId = params.getTaskId();
            if (callback != null) {
                try {
                    callback.acknowledgeStopMessage(taskId, reschedule);
                } catch(RemoteException e) {
                    Log.e(TAG, "System unreachable for stopping task.");
                }
            } else {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Attempting to ack a task that has already been processed.");
                }
            }
        }
    }

    /** @hide */
    public final IBinder onBind(Intent intent) {
        return mBinder.asBinder();
    }

    /**
     * Override this method with the callback logic for your task. Any such logic needs to be
     * performed on a separate thread, as this function is executed on your application's main
     * thread.
     *
     * @param params Parameters specifying info about this task, including the extras bundle you
     *               optionally provided at task-creation time.
     * @return True if your service needs to process the work (on a separate thread). False if
     * there's no more work to be done for this task.
     */
    public abstract boolean onStartTask(TaskParams params);

    /**
     * This method is called if the system has determined that you must stop execution of your task
     * even before you've had a chance to call {@link #taskFinished(TaskParams, boolean)}.
     *
     * <p>This will happen if the requirements specified at schedule time are no longer met. For
     * example you may have requested WiFi with
     * {@link android.content.Task.Builder#setRequiredNetworkCapabilities(int)}, yet while your
     * task was executing the user toggled WiFi. Another example is if you had specified
     * {@link android.content.Task.Builder#setRequiresDeviceIdle(boolean)}, and the phone left its
     * idle maintenance window. You are solely responsible for the behaviour of your application
     * upon receipt of this message; your app will likely start to misbehave if you ignore it. One
     * immediate repercussion is that the system will cease holding a wakelock for you.</p>
     *
     * @param params Parameters specifying info about this task.
     * @return True to indicate to the TaskManager whether you'd like to reschedule this task based
     * on the retry criteria provided at task creation-time. False to drop the task. Regardless of
     * the value returned, your task must stop executing.
     */
    public abstract boolean onStopTask(TaskParams params);

    /**
     * Callback to inform the TaskManager you've finished executing. This can be called from any
     * thread, as it will ultimately be run on your application's main thread. When the system
     * receives this message it will release the wakelock being held.
     * <p>
     *     You can specify post-execution behaviour to the scheduler here with
     *     <code>needsReschedule </code>. This will apply a back-off timer to your task based on
     *     the default, or what was set with
     *     {@link android.content.Task.Builder#setBackoffCriteria(long, int)}. The original
     *     requirements are always honoured even for a backed-off task. Note that a task running in
     *     idle mode will not be backed-off. Instead what will happen is the task will be re-added
     *     to the queue and re-executed within a future idle maintenance window.
     * </p>
     *
     * @param params Parameters specifying system-provided info about this task, this was given to
     *               your application in {@link #onStartTask(TaskParams)}.
     * @param needsReschedule True if this task is complete, false if you want the TaskManager to
     *                        reschedule you.
     */
    public final void taskFinished(TaskParams params, boolean needsReschedule) {
        ensureHandler();
        Message m = Message.obtain(mHandler, MSG_TASK_FINISHED, params);
        m.arg2 = needsReschedule ? 1 : 0;
        m.sendToTarget();
    }
}