/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.stagesplit;

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * Side stage for split-screen mode. Only tasks that are explicitly pinned to this stage show up
 * here. All other task are launch in the {@link MainStage}.
 *
 * @see StageCoordinator
 */
class SideStage extends StageTaskListener implements
        DisplayInsetsController.OnInsetsChangedListener {
    private static final String TAG = SideStage.class.getSimpleName();
    private final Context mContext;
    private OutlineManager mOutlineManager;

    SideStage(Context context, ShellTaskOrganizer taskOrganizer, int displayId,
            StageListenerCallbacks callbacks, SyncTransactionQueue syncQueue,
            SurfaceSession surfaceSession,
            @Nullable StageTaskUnfoldController stageTaskUnfoldController) {
        super(taskOrganizer, displayId, callbacks, syncQueue, surfaceSession,
                stageTaskUnfoldController);
        mContext = context;
    }

    void addTask(ActivityManager.RunningTaskInfo task, Rect rootBounds,
            WindowContainerTransaction wct) {
        final WindowContainerToken rootToken = mRootTaskInfo.token;
        wct.setBounds(rootToken, rootBounds)
                .reparent(task.token, rootToken, true /* onTop*/)
                // Moving the root task to top after the child tasks were reparented , or the root
                // task cannot be visible and focused.
                .reorder(rootToken, true /* onTop */);
    }

    boolean removeAllTasks(WindowContainerTransaction wct, boolean toTop) {
        // No matter if the root task is empty or not, moving the root to bottom because it no
        // longer preserves visible child task.
        wct.reorder(mRootTaskInfo.token, false /* onTop */);
        if (mChildrenTaskInfo.size() == 0) return false;
        wct.reparentTasks(
                mRootTaskInfo.token,
                null /* newParent */,
                CONTROLLED_WINDOWING_MODES_WHEN_ACTIVE,
                CONTROLLED_ACTIVITY_TYPES,
                toTop);
        return true;
    }

    boolean removeTask(int taskId, WindowContainerToken newParent, WindowContainerTransaction wct) {
        final ActivityManager.RunningTaskInfo task = mChildrenTaskInfo.get(taskId);
        if (task == null) return false;
        wct.reparent(task.token, newParent, false /* onTop */);
        return true;
    }

    @Nullable
    public SurfaceControl getOutlineLeash() {
        return mOutlineManager.getOutlineLeash();
    }

    @Override
    @CallSuper
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        super.onTaskAppeared(taskInfo, leash);
        if (isRootTask(taskInfo)) {
            mOutlineManager = new OutlineManager(mContext, taskInfo.configuration);
            enableOutline(true);
        }
    }

    @Override
    @CallSuper
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        super.onTaskInfoChanged(taskInfo);
        if (isRootTask(taskInfo)) {
            mOutlineManager.setRootBounds(taskInfo.configuration.windowConfiguration.getBounds());
        }
    }

    private boolean isRootTask(ActivityManager.RunningTaskInfo taskInfo) {
        return mRootTaskInfo != null && mRootTaskInfo.taskId == taskInfo.taskId;
    }

    void enableOutline(boolean enable) {
        if (mOutlineManager == null) {
            return;
        }

        if (enable) {
            if (mRootTaskInfo != null) {
                mOutlineManager.inflate(mRootLeash,
                        mRootTaskInfo.configuration.windowConfiguration.getBounds());
            }
        } else {
            mOutlineManager.release();
        }
    }

    void setOutlineVisibility(boolean visible) {
        mOutlineManager.setVisibility(visible);
    }

    @Override
    public void insetsChanged(InsetsState insetsState) {
        mOutlineManager.onInsetsChanged(insetsState);
    }

    @Override
    public void insetsControlChanged(InsetsState insetsState,
            InsetsSourceControl[] activeControls) {
        insetsChanged(insetsState);
    }
}
