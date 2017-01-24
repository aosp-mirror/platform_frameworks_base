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
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.EventLog;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;

import static com.android.server.EventLogTags.WM_TASK_CREATED;
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

    private static final int REPORT_SNAPSHOT_CHANGED = 0;

    private final int mTaskId;

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REPORT_SNAPSHOT_CHANGED:
                    if (mListener != null) {
                        mListener.onSnapshotChanged((TaskSnapshot) msg.obj);
                    }
                    break;
            }
        }
    };

    public TaskWindowContainerController(int taskId, TaskWindowContainerListener listener,
            int stackId, int userId, Rect bounds, Configuration overrideConfig, int resizeMode,
            boolean homeTask, boolean isOnTopLauncher, boolean toTop, boolean showForAllUsers,
            TaskDescription taskDescription) {
        super(listener, WindowManagerService.getInstance());
        mTaskId = taskId;

        synchronized(mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "TaskWindowContainerController: taskId=" + taskId
                    + " stackId=" + stackId + " bounds=" + bounds);

            // TODO: Pass controller for the stack to get the container object when stack is
            // switched to use controller.
            final TaskStack stack = mService.mStackIdToStack.get(stackId);
            if (stack == null) {
                throw new IllegalArgumentException("TaskWindowContainerController: invalid stackId="
                        + stackId);
            }
            EventLog.writeEvent(WM_TASK_CREATED, taskId, stackId);
            final Task task = createTask(taskId, stack, userId, bounds, overrideConfig, resizeMode,
                    homeTask, isOnTopLauncher, taskDescription);
            final int position = toTop ? POSITION_TOP : POSITION_BOTTOM;
            stack.addTask(task, position, showForAllUsers, true /* moveParents */);
        }
    }

    @VisibleForTesting
    Task createTask(int taskId, TaskStack stack, int userId, Rect bounds,
            Configuration overrideConfig, int resizeMode, boolean homeTask,
            boolean isOnTopLauncher, TaskDescription taskDescription) {
        return new Task(taskId, stack, userId, mService, bounds, overrideConfig, isOnTopLauncher,
                resizeMode, homeTask, taskDescription, this);
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

    public void reparent(int stackId, int position) {
        synchronized (mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "reparent: moving taskId=" + mTaskId
                    + " to stackId=" + stackId + " at " + position);
            if (mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "reparent: could not find taskId=" + mTaskId);
                return;
            }
            final TaskStack stack = mService.mStackIdToStack.get(stackId);
            if (stack == null) {
                throw new IllegalArgumentException("reparent: could not find stackId=" + stackId);
            }
            mContainer.reparent(stack, position);
            mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    public void setResizeable(int resizeMode) {
        synchronized (mWindowMap) {
            if (mContainer != null) {
                mContainer.setResizeable(resizeMode);
            }
        }
    }

    public void resize(Rect bounds, Configuration overrideConfig, boolean relayout,
            boolean forced) {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                throw new IllegalArgumentException("resizeTask: taskId " + mTaskId + " not found.");
            }

            if (mContainer.resizeLocked(bounds, overrideConfig, forced) && relayout) {
                mContainer.getDisplayContent().setLayoutNeeded();
                mService.mWindowPlacerLocked.performSurfacePlacement();
            }
        }
    }

    // TODO: Move to positionChildAt() in stack controller once we have a stack controller.
    public void positionAt(int position, Rect bounds, Configuration overrideConfig) {
        synchronized (mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "positionChildAt: positioning taskId=" + mTaskId
                    + " at " + position);
            if (mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionAt: could not find taskId=" + mTaskId);
                return;
            }
            final TaskStack stack = mContainer.mStack;
            if (stack == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionAt: could not find stack for task=" + mContainer);
                return;
            }
            mContainer.positionAt(position, bounds, overrideConfig);
            final DisplayContent displayContent = stack.getDisplayContent();
            displayContent.setLayoutNeeded();
            mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    // TODO: Replace with moveChildToTop in stack controller?
    public void moveToTop(boolean includingParents) {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.e(TAG_WM, "moveToTop: taskId=" + mTaskId + " not found");
                return;
            }
            final TaskStack stack = mContainer.mStack;
            stack.positionChildAt(POSITION_TOP, mContainer, includingParents);

            if (mService.mAppTransition.isTransitionSet()) {
                mContainer.setSendingToBottom(false);
            }
            stack.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
        }
    }

    // TODO: Replace with moveChildToBottom in stack controller?
    public void moveToBottom() {
        synchronized(mWindowMap) {
            if (mContainer == null) {
                Slog.e(TAG_WM, "moveTaskToBottom: taskId=" + mTaskId + " not found");
                return;
            }
            final TaskStack stack = mContainer.mStack;
            stack.positionChildAt(POSITION_BOTTOM, mContainer, false /* includingParents */);
            if (mService.mAppTransition.isTransitionSet()) {
                mContainer.setSendingToBottom(true);
            }
            stack.getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
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

    public void cancelThumbnailTransition() {
        synchronized (mWindowMap) {
            if (mContainer == null) {
                Slog.w(TAG_WM, "cancelThumbnailTransition: taskId " + mTaskId + " not found.");
                return;
            }
            mContainer.cancelTaskThumbnailTransition();
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
        mHandler.obtainMessage(REPORT_SNAPSHOT_CHANGED, snapshot).sendToTarget();
    }

    @Override
    public String toString() {
        return "{TaskWindowContainerController taskId=" + mTaskId + "}";
    }
}
