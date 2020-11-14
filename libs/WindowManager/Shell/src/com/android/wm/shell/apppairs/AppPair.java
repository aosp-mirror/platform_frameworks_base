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

package com.android.wm.shell.apppairs;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_TASK_ORG;

import android.app.ActivityManager;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;

import java.io.PrintWriter;

/**
 * An app-pairs consisting of {@link #mRootTaskInfo} that acts as the hierarchy parent of
 * {@link #mTaskInfo1} and {@link #mTaskInfo2} in the pair.
 * Also includes all UI for managing the pair like the divider.
 */
// TODO: Add divider
// TODO: Handle display rotation
class AppPair implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = AppPair.class.getSimpleName();

    private ActivityManager.RunningTaskInfo mRootTaskInfo;
    private SurfaceControl mRootTaskLeash;
    private ActivityManager.RunningTaskInfo mTaskInfo1;
    private SurfaceControl mTaskLeash1;
    private ActivityManager.RunningTaskInfo mTaskInfo2;
    private SurfaceControl mTaskLeash2;

    private final AppPairsController mController;
    private final SyncTransactionQueue mSyncQueue;

    AppPair(AppPairsController controller) {
        mController = controller;
        mSyncQueue = controller.getSyncTransactionQueue();
    }

    int getRootTaskId() {
        return mRootTaskInfo != null ? mRootTaskInfo.taskId : INVALID_TASK_ID;
    }

    private int getTaskId1() {
        return mTaskInfo1 != null ? mTaskInfo1.taskId : INVALID_TASK_ID;
    }

    private int getTaskId2() {
        return mTaskInfo2 != null ? mTaskInfo2.taskId : INVALID_TASK_ID;
    }

    boolean contains(int taskId) {
        return taskId == getRootTaskId() || taskId == getTaskId1() || taskId == getTaskId2();
    }

    boolean pair(ActivityManager.RunningTaskInfo task1, ActivityManager.RunningTaskInfo task2) {
        ProtoLog.v(WM_SHELL_TASK_ORG, "pair task1=%d task2=%d in AppPair=%s",
                task1.taskId, task2.taskId, this);

        if (!task1.isResizeable || !task2.isResizeable) {
            ProtoLog.e(WM_SHELL_TASK_ORG,
                    "Can't pair unresizeable tasks task1.isResizeable=%b task1.isResizeable=%b",
                    task1.isResizeable, task2.isResizeable);
            return false;
        }

        mTaskInfo1 = task1;
        mTaskInfo2 = task2;

        // TODO: properly calculate bounds for pairs.
        final Rect rootBounds = mRootTaskInfo.configuration.windowConfiguration.getBounds();
        final Rect bounds1 = new Rect(
                rootBounds.left, rootBounds.top, rootBounds.right / 2, rootBounds.bottom / 2);
        final Rect bounds2 = new Rect(
                bounds1.right, bounds1.bottom, rootBounds.right, rootBounds.bottom);
        final WindowContainerToken token1 = task1.token;
        final WindowContainerToken token2 = task2.token;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        wct.setHidden(mRootTaskInfo.token, false)
                .reparent(token1, mRootTaskInfo.token, true /* onTop */)
                .reparent(token2, mRootTaskInfo.token, true /* onTop */)
                .setWindowingMode(token1, WINDOWING_MODE_MULTI_WINDOW)
                .setWindowingMode(token2, WINDOWING_MODE_MULTI_WINDOW)
                .setBounds(token1, bounds1)
                .setBounds(token2, bounds2)
                // Moving the root task to top after the child tasks were repareted , or the root
                // task cannot be visible and focused.
                .reorder(mRootTaskInfo.token, true);
        mController.getTaskOrganizer().applyTransaction(wct);
        return true;
    }

    void unpair() {
        final WindowContainerToken token1 = mTaskInfo1.token;
        final WindowContainerToken token2 = mTaskInfo2.token;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        // Reparent out of this container and reset windowing mode.
        wct.setHidden(mRootTaskInfo.token, true)
                .reorder(mRootTaskInfo.token, false)
                .reparent(token1, null, false /* onTop */)
                .reparent(token2, null, false /* onTop */)
                .setWindowingMode(token1, WINDOWING_MODE_UNDEFINED)
                .setWindowingMode(token2, WINDOWING_MODE_UNDEFINED);
        mController.getTaskOrganizer().applyTransaction(wct);

        mTaskInfo1 = null;
        mTaskInfo2 = null;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mRootTaskInfo == null || taskInfo.taskId == mRootTaskInfo.taskId) {
            mRootTaskInfo = taskInfo;
            mRootTaskLeash = leash;
        } else if (taskInfo.taskId == getTaskId1()) {
            mTaskInfo1 = taskInfo;
            mTaskLeash1 = leash;
        } else if (taskInfo.taskId == getTaskId2()) {
            mTaskInfo2 = taskInfo;
            mTaskLeash2 = leash;
        } else {
            throw new IllegalStateException("Unknown task=" + taskInfo.taskId);
        }

        if (mTaskLeash1 == null || mTaskLeash2 == null) return;

        // TODO: Is there more we need to do here?
        mSyncQueue.runInSync(t -> t
                .setPosition(mTaskLeash1, mTaskInfo1.positionInParent.x,
                        mTaskInfo1.positionInParent.y)
                .setPosition(mTaskLeash2, mTaskInfo2.positionInParent.x,
                        mTaskInfo2.positionInParent.y)
                .show(mRootTaskLeash)
                .show(mTaskLeash1)
                .show(mTaskLeash2));
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo.taskId == getRootTaskId()) {
            mRootTaskInfo = taskInfo;
        } else if (taskInfo.taskId == getTaskId1()) {
            mTaskInfo1 = taskInfo;
        } else if (taskInfo.taskId == getTaskId2()) {
            mTaskInfo2 = taskInfo;
        } else {
            throw new IllegalStateException("Unknown task=" + taskInfo.taskId);
        }
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo.taskId == getRootTaskId()) {
            // We don't want to release this object back to the pool since the root task went away.
            mController.unpair(mRootTaskInfo.taskId, false /* releaseToPool */);
        } else if (taskInfo.taskId == getTaskId1() || taskInfo.taskId == getTaskId2()) {
            mController.unpair(mRootTaskInfo.taskId);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + "Root taskId=" + getRootTaskId()
                + " winMode=" + mRootTaskInfo.getWindowingMode());
        if (mTaskInfo1 != null) {
            pw.println(innerPrefix + "1 taskId=" + mTaskInfo1.taskId
                    + " winMode=" + mTaskInfo1.getWindowingMode());
        }
        if (mTaskInfo2 != null) {
            pw.println(innerPrefix + "2 taskId=" + mTaskInfo2.taskId
                    + " winMode=" + mTaskInfo2.getWindowingMode());
        }
    }

    @Override
    public String toString() {
        return TAG + "#" + getRootTaskId();
    }
}
