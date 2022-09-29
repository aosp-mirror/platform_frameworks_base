/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.os.Handler;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeController;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.transition.Transitions;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link CaptionWindowDecoration}.
 */
public class CaptionWindowDecorViewModel implements WindowDecorViewModel<CaptionWindowDecoration> {
    private final ActivityTaskManager mActivityTaskManager;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Context mContext;
    private final Handler mMainHandler;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private FreeformTaskTransitionStarter mTransitionStarter;
    private DesktopModeController mDesktopModeController;

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            DesktopModeController desktopModeController) {
        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mSyncQueue = syncQueue;
        mDesktopModeController = desktopModeController;
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTransitionStarter = transitionStarter;
    }

    @Override
    public CaptionWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if (!shouldShowWindowDecor(taskInfo)) return null;
        final CaptionWindowDecoration windowDecoration = new CaptionWindowDecoration(
                mContext,
                mDisplayController,
                mTaskOrganizer,
                taskInfo,
                taskSurface,
                mMainHandler,
                mMainChoreographer,
                mSyncQueue);
        TaskPositioner taskPositioner = new TaskPositioner(mTaskOrganizer, windowDecoration);
        CaptionTouchEventListener touchEventListener =
                new CaptionTouchEventListener(taskInfo, taskPositioner);
        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener);
        windowDecoration.setDragResizeCallback(taskPositioner);
        setupWindowDecorationForTransition(taskInfo, startT, finishT, windowDecoration);
        setupCaptionColor(taskInfo, windowDecoration);
        return windowDecoration;
    }

    @Override
    public CaptionWindowDecoration adoptWindowDecoration(AutoCloseable windowDecor) {
        if (!(windowDecor instanceof CaptionWindowDecoration)) return null;
        final CaptionWindowDecoration captionWindowDecor = (CaptionWindowDecoration) windowDecor;
        if (!shouldShowWindowDecor(captionWindowDecor.mTaskInfo)) {
            return null;
        }
        return captionWindowDecor;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo, CaptionWindowDecoration decoration) {
        decoration.relayout(taskInfo);

        setupCaptionColor(taskInfo, decoration);
    }

    private void setupCaptionColor(RunningTaskInfo taskInfo, CaptionWindowDecoration decoration) {
        int statusBarColor = taskInfo.taskDescription.getStatusBarColor();
        decoration.setCaptionColor(statusBarColor);
    }

    @Override
    public void setupWindowDecorationForTransition(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT,
            CaptionWindowDecoration decoration) {
        decoration.relayout(taskInfo, startT, finishT);
    }

    private class CaptionTouchEventListener implements
            View.OnClickListener, View.OnTouchListener {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragResizeCallback mDragResizeCallback;

        private int mDragPointerId = -1;

        private CaptionTouchEventListener(
                RunningTaskInfo taskInfo,
                DragResizeCallback dragResizeCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragResizeCallback = dragResizeCallback;
        }

        @Override
        public void onClick(View v) {
            final int id = v.getId();
            if (id == R.id.close_window) {
                WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.removeTask(mTaskToken);
                if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                    mTransitionStarter.startRemoveTransition(wct);
                } else {
                    mSyncQueue.queue(wct);
                }
            } else if (id == R.id.maximize_window) {
                WindowContainerTransaction wct = new WindowContainerTransaction();
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                int targetWindowingMode = taskInfo.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                        ? WINDOWING_MODE_FULLSCREEN : WINDOWING_MODE_FREEFORM;
                int displayWindowingMode =
                        taskInfo.configuration.windowConfiguration.getDisplayWindowingMode();
                wct.setWindowingMode(mTaskToken,
                        targetWindowingMode == displayWindowingMode
                            ? WINDOWING_MODE_UNDEFINED : targetWindowingMode);
                if (targetWindowingMode == WINDOWING_MODE_FULLSCREEN) {
                    wct.setBounds(mTaskToken, null);
                }
                if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                    mTransitionStarter.startWindowingModeTransition(targetWindowingMode, wct);
                } else {
                    mSyncQueue.queue(wct);
                }
            } else if (id == R.id.minimize_window) {
                WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.reorder(mTaskToken, false);
                if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                    mTransitionStarter.startMinimizedModeTransition(wct);
                } else {
                    mSyncQueue.queue(wct);
                }
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (v.getId() != R.id.caption) {
                return false;
            }
            handleEventForMove(e);

            if (e.getAction() != MotionEvent.ACTION_DOWN) {
                return false;
            }
            RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (taskInfo.isFocused) {
                return false;
            }
            WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.reorder(mTaskToken, true /* onTop */);
            mSyncQueue.queue(wct);
            return true;
        }

        private void handleEventForMove(MotionEvent e) {
            RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            int windowingMode =  mDesktopModeController
                    .getDisplayAreaWindowingMode(taskInfo.displayId);
            if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
                return;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDragPointerId  = e.getPointerId(0);
                    mDragResizeCallback.onDragResizeStart(
                            0 /* ctrlType */, e.getRawX(0), e.getRawY(0));
                    break;
                case MotionEvent.ACTION_MOVE: {
                    int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragResizeCallback.onDragResizeMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    int statusBarHeight = mDisplayController.getDisplayLayout(taskInfo.displayId)
                            .stableInsets().top;
                    mDragResizeCallback.onDragResizeEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    if (e.getRawY(dragPointerIdx) <= statusBarHeight
                            && windowingMode == WINDOWING_MODE_FREEFORM) {
                        mDesktopModeController.setDesktopModeActive(false);
                    }
                    break;
                }
            }
        }
    }

    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) return true;
        return DesktopModeStatus.IS_SUPPORTED
                && taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && mDisplayController.getDisplayContext(taskInfo.displayId)
                .getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }
}
