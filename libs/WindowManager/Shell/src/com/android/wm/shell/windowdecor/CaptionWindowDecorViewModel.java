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

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue) {
        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mSyncQueue = syncQueue;
    }

    @Override
    public CaptionWindowDecoration createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl taskSurface) {
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
        onTaskInfoChanged(taskInfo, windowDecoration);
        return windowDecoration;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo, CaptionWindowDecoration decoration) {
        decoration.relayout(taskInfo);

        int statusBarColor = taskInfo.taskDescription.getStatusBarColor();
        decoration.setCaptionColor(statusBarColor);
    }

    private class CaptionTouchEventListener implements
            View.OnClickListener, View.OnTouchListener {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragResizeCallback mDragResizeCallback;

        private int mDragPointerId = -1;

        private CaptionTouchEventListener(
                RunningTaskInfo taskInfo, DragResizeCallback dragResizeCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragResizeCallback = dragResizeCallback;
        }

        @Override
        public void onClick(View v) {
            int id = v.getId();
            if (id == R.id.close_window) {
                mActivityTaskManager.removeTask(mTaskId);
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
                mSyncQueue.queue(wct);
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
                    mDragResizeCallback.onDragResizeEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    break;
                }
            }
        }
    }
}
