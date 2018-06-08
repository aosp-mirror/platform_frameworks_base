/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import android.app.ActivityManager.TaskDescription;
import android.app.ActivityManager.TaskSnapshot;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.EventLog;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;

import static com.android.server.EventLogTags.WM_TASK_CREATED;
import static com.android.server.wm.ConfigurationContainer.BOUNDS_CHANGE_NONE;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

/**
 * Controller for the task container. This is created by activity manager to link task records to
 * the task container they use in window manager.
 *
 * Test class: {@link TaskWindowContainerControllerTests}
 */
public class TaskWindowContainerController
        extends WindowContainerController<Task, TaskWindowContainerListener> {

    private final int mTaskId;
    private final H mHandler;

    public TaskWindowContainerController(int taskId, TaskWindowContainerListener listener,
            StackWindowController stackController, int userId, Rect bounds, int resizeMode,
            boolean supportsPictureInPicture, boolean toTop, boolean showForAllUsers,
            TaskDescription taskDescription) {
        this(taskId, listener, stackController, userId, bounds, resizeMode,
                supportsPictureInPicture, toTop, showForAllUsers, taskDescription,
                WindowManagerService.getInstance());
    }

    public TaskWindowContainerController(int taskId, TaskWindowContainerListener listener,
            StackWindowController stackController, int userId, Rect bounds, int resizeMode,
            boolean supportsPictureInPicture, boolean toTop, boolean showForAllUsers,
            TaskDescription taskDescription, WindowManagerService service) {
        super(listener, service);
        mTaskId = taskId;
        mHandler = new H(new WeakReference<>(this), service.mH.getLooper());

        synchronized(mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "TaskWindowContainerController: taskId=" + taskId
                    + " stack=" + stackController + " bounds=" + bounds);

            final TaskStack stack = stackController.mContainer;
            if (stack == null) {
                throw new IllegalArgumentException("TaskWindowContainerController: invalid stack="
                        + stackController);
            }
            EventLog.writeEvent(WM_TASK_CREATED, taskId, stack.mStackId);
            final Task task = createTask(taskId, stack, userId, resizeMode,
                    supportsPictureInPicture, taskDescription);
            final int position = toTop ? POSITION_TOP : POSITION_BOTTOM;
            // We only want to move the parents to the parents if we are creating this task at the
            // top of its stack.
            stack.addTask(task, position, showForAllUsers, toTop /* moveParents */);
        }
    }

    @VisibleForTesting
    Task createTask(int taskId, TaskStack stack, int userId, int resizeMode,
            boolean supportsPictureInPicture, TaskDescription taskDescription) {
        return new Task(taskId, stack, userId, mService, resizeMode, supportsPictureInPicture,
                taskDescription, this);
    }

    @Override
    public void removeContainer() {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM, "removeTask: could not find taskId=" + mTaskId);
                return;
            }
            mContainer.removeIfPossible();
            super.removeContainer();
        }
    }

    public void positionChildAtTop(AppWindowContainerController childController) {
        positionChildAt(childController, POSITION_TOP);
    }

    public void positionChildAt(AppWindowContainerController childController, int position) {
        synchronized(mService.mWindowMap) {
            final AppWindowToken aToken = childController.mContainer;
            if (aToken == null) {
                Slog.w(TAG_WM,
                        "Attempted to position of non-existing app : " + childController);
                return;
            }

            final Task task = mContainer;
            if (task == null) {
                throw new IllegalArgumentException("positionChildAt: invalid task=" + this);
            }
            task.positionChildAt(position, aToken, false /* includeParents */);
        }
    }

    public void reparent(StackWindowController stackController, int position, boolean moveParents) {
        synchronized (mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "reparent: moving taskId=" + mTaskId
                    + " to stack=" + stackController + " at " + position);
            if (mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "reparent: could not find taskId=" + mTaskId);
                return;
            }
            final TaskStack stack = stackController.mContainer;
            if (stack == null) {
                throw new IllegalArgumentException("reparent: could not find stack="
                        + stackController);
            }
            mContainer.reparent(stack, position, moveParents);
            mContainer.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    public void setResizeable(int resizeMode) {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mContainer.setResizeable(resizeMode);
            }
        }
    }

    public void resize(boolean relayout, boolean forced) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                throw new IllegalArgumentException("resizeTask: taskId " + mTaskId + " not found.");
            }

            if (mContainer.setBounds(mContainer.getOverrideBounds(), forced) != BOUNDS_CHANGE_NONE
                    && relayout) {
                mContainer.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
            }
        }
    }

    public void getBounds(Rect bounds) {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mContainer.getBounds(bounds);
                return;
            }
            bounds.setEmpty();
        }
    }

    /**
     * Puts this task into docked drag resizing mode. See {@link DragResizeMode}.
     *
     * @param resizing Whether to put the task into drag resize mode.
     */
    public void setTaskDockedResizing(boolean resizing) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "setTaskDockedResizing: taskId " + mTaskId + " not found.");
                return;
            }
            mContainer.setDragResizing(resizing, DRAG_RESIZE_MODE_DOCKED_DIVIDER);
        }
    }

    public void cancelWindowTransition() {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "cancelWindowTransition: taskId " + mTaskId + " not found.");
                return;
            }
            mContainer.cancelTaskWindowTransition();
        }
    }

    public void setTaskDescription(TaskDescription taskDescription) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "setTaskDescription: taskId " + mTaskId + " not found.");
                return;
            }
            mContainer.setTaskDescription(taskDescription);
        }
    }

    void reportSnapshotChanged(TaskSnapshot snapshot) {
        mHandler.obtainMessage(H.REPORT_SNAPSHOT_CHANGED, snapshot).sendToTarget();
    }

    void requestResize(Rect bounds, int resizeMode) {
        mHandler.obtainMessage(H.REQUEST_RESIZE, resizeMode, 0, bounds).sendToTarget();
    }

    @Override
    public String toString() {
        return "{TaskWindowContainerController taskId=" + mTaskId + "}";
    }

    private static final class H extends Handler {

        static final int REPORT_SNAPSHOT_CHANGED = 0;
        static final int REQUEST_RESIZE = 1;

        private final WeakReference<TaskWindowContainerController> mController;

        H(WeakReference<TaskWindowContainerController> controller, Looper looper) {
            super(looper);
            mController = controller;
        }

        @Override
        public void handleMessage(Message msg) {
            final TaskWindowContainerController controller = mController.get();
            final TaskWindowContainerListener listener = (controller != null)
                    ? controller.mListener : null;
            if (listener == null) {
                return;
            }
            switch (msg.what) {
                case REPORT_SNAPSHOT_CHANGED:
                    listener.onSnapshotChanged((TaskSnapshot) msg.obj);
                    break;
                case REQUEST_RESIZE:
                    listener.requestResize((Rect) msg.obj, msg.arg1);
                    break;
            }
        }
    }
}
