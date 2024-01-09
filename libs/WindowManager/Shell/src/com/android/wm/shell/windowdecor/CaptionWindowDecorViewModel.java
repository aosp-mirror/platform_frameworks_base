/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.wm.shell.windowdecor;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.os.Handler;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.transition.Transitions;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link CaptionWindowDecoration}.
 */
public class CaptionWindowDecorViewModel implements WindowDecorViewModel {
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Context mContext;
    private final Handler mMainHandler;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private final Transitions mTransitions;
    private TaskOperations mTaskOperations;

    private final SparseArray<CaptionWindowDecoration> mWindowDecorByTaskId = new SparseArray<>();

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            Transitions transitions) {
        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mSyncQueue = syncQueue;
        mTransitions = transitions;
        if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
            mTaskOperations = new TaskOperations(null, mContext, mSyncQueue);
        }
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTaskOperations = new TaskOperations(transitionStarter, mContext, mSyncQueue);
    }

    @Override
    public void setSplitScreenController(SplitScreenController splitScreenController) {}

    @Override
    public boolean onTaskOpening(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if (!shouldShowWindowDecor(taskInfo)) return false;
        createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        return true;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (decoration == null) return;

        decoration.relayout(taskInfo);
        setupCaptionColor(taskInfo, decoration);
    }

    @Override
    public void onTaskChanging(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (!shouldShowWindowDecor(taskInfo)) {
            if (decoration != null) {
                destroyWindowDecoration(taskInfo);
            }
            return;
        }

        if (decoration == null) {
            createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        } else {
            decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                    false /* setTaskCropAndPosition */);
        }
    }

    @Override
    public void onTaskClosing(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                false /* setTaskCropAndPosition */);
    }

    @Override
    public void destroyWindowDecoration(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration =
                mWindowDecorByTaskId.removeReturnOld(taskInfo.taskId);
        if (decoration == null) return;

        decoration.close();
    }

    private void setupCaptionColor(RunningTaskInfo taskInfo, CaptionWindowDecoration decoration) {
        final int statusBarColor = taskInfo.taskDescription.getStatusBarColor();
        decoration.setCaptionColor(statusBarColor);
    }

    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        return taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM
                || (taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && taskInfo.configuration.windowConfiguration.getDisplayWindowingMode()
                == WINDOWING_MODE_FREEFORM);
    }

    private void createWindowDecoration(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration oldDecoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (oldDecoration != null) {
            // close the old decoration if it exists to avoid two window decorations being added
            oldDecoration.close();
        }
        final CaptionWindowDecoration windowDecoration =
                new CaptionWindowDecoration(
                        mContext,
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        taskSurface,
                        mMainHandler,
                        mMainChoreographer,
                        mSyncQueue);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);

        final FluidResizeTaskPositioner taskPositioner =
                new FluidResizeTaskPositioner(mTaskOrganizer, mTransitions, windowDecoration,
                        mDisplayController, 0 /* disallowedAreaForEndBoundsHeight */);
        final CaptionTouchEventListener touchEventListener =
                new CaptionTouchEventListener(taskInfo, taskPositioner);
        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener);
        windowDecoration.setDragPositioningCallback(taskPositioner);
        windowDecoration.setDragDetector(touchEventListener.mDragDetector);
        windowDecoration.setTaskDragResizer(taskPositioner);
        windowDecoration.relayout(taskInfo, startT, finishT,
                false /* applyStartTransactionOnDraw */, false /* setTaskCropAndPosition */);
        setupCaptionColor(taskInfo, windowDecoration);
    }

    private class CaptionTouchEventListener implements
            View.OnClickListener, View.OnTouchListener, DragDetector.MotionEventHandler {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragPositioningCallback mDragPositioningCallback;
        private final DragDetector mDragDetector;

        private int mDragPointerId = -1;
        private boolean mIsDragging;

        private CaptionTouchEventListener(
                RunningTaskInfo taskInfo,
                DragPositioningCallback dragPositioningCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragPositioningCallback = dragPositioningCallback;
            mDragDetector = new DragDetector(this);
        }

        @Override
        public void onClick(View v) {
            final int id = v.getId();
            if (id == R.id.close_window) {
                mTaskOperations.closeTask(mTaskToken);
            } else if (id == R.id.back_button) {
                mTaskOperations.injectBackKey();
            } else if (id == R.id.minimize_window) {
                mTaskOperations.minimizeTask(mTaskToken);
            } else if (id == R.id.maximize_window) {
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                mTaskOperations.maximizeTask(taskInfo);
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (v.getId() != R.id.caption) {
                return false;
            }
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                if (!taskInfo.isFocused) {
                    final WindowContainerTransaction wct = new WindowContainerTransaction();
                    wct.reorder(mTaskToken, true /* onTop */);
                    mSyncQueue.queue(wct);
                }
            }
            return mDragDetector.onMotionEvent(e);
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if a drag is happening; or {@code false} if it is not
         */
        @Override
        public boolean handleMotionEvent(@Nullable View v, MotionEvent e) {
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = e.getPointerId(0);
                    mDragPositioningCallback.onDragPositioningStart(
                            0 /* ctrlType */, e.getRawX(0), e.getRawY(0));
                    mIsDragging = false;
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragPositioningCallback.onDragPositioningMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mIsDragging = true;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragPositioningCallback.onDragPositioningEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    final boolean wasDragging = mIsDragging;
                    mIsDragging = false;
                    return wasDragging;
                }
            }
            return true;
        }
    }
}