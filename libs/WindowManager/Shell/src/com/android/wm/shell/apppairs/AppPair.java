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

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_TASK_ORG;

import android.app.ActivityManager;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.SurfaceUtils;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.split.SplitLayout;
import com.android.wm.shell.common.split.SplitWindowManager;

import java.io.PrintWriter;

/**
 * An app-pairs consisting of {@link #mRootTaskInfo} that acts as the hierarchy parent of
 * {@link #mTaskInfo1} and {@link #mTaskInfo2} in the pair.
 * Also includes all UI for managing the pair like the divider.
 */
class AppPair implements ShellTaskOrganizer.TaskListener, SplitLayout.SplitLayoutHandler {
    private static final String TAG = AppPair.class.getSimpleName();

    private ActivityManager.RunningTaskInfo mRootTaskInfo;
    private SurfaceControl mRootTaskLeash;
    private ActivityManager.RunningTaskInfo mTaskInfo1;
    private SurfaceControl mTaskLeash1;
    private ActivityManager.RunningTaskInfo mTaskInfo2;
    private SurfaceControl mTaskLeash2;
    private SurfaceControl mDimLayer1;
    private SurfaceControl mDimLayer2;
    private final SurfaceSession mSurfaceSession = new SurfaceSession();

    private final AppPairsController mController;
    private final SyncTransactionQueue mSyncQueue;
    private final DisplayController mDisplayController;
    private final DisplayImeController mDisplayImeController;
    private final DisplayInsetsController mDisplayInsetsController;
    private SplitLayout mSplitLayout;

    private final SplitWindowManager.ParentContainerCallbacks mParentContainerCallbacks =
            new SplitWindowManager.ParentContainerCallbacks() {
        @Override
        public void attachToParentSurface(SurfaceControl.Builder b) {
            b.setParent(mRootTaskLeash);
        }

        @Override
        public void onLeashReady(SurfaceControl leash) {
            mSyncQueue.runInSync(t -> t
                    .show(leash)
                    .setLayer(leash, Integer.MAX_VALUE)
                    .setPosition(leash,
                            mSplitLayout.getDividerBounds().left,
                            mSplitLayout.getDividerBounds().top));
        }
    };

    AppPair(AppPairsController controller) {
        mController = controller;
        mSyncQueue = controller.getSyncTransactionQueue();
        mDisplayController = controller.getDisplayController();
        mDisplayImeController = controller.getDisplayImeController();
        mDisplayInsetsController = controller.getDisplayInsetsController();
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

        if (!task1.supportsMultiWindow || !task2.supportsMultiWindow) {
            ProtoLog.e(WM_SHELL_TASK_ORG,
                    "Can't pair tasks that doesn't support multi window, "
                            + "task1.supportsMultiWindow=%b, task2.supportsMultiWindow=%b",
                    task1.supportsMultiWindow, task2.supportsMultiWindow);
            return false;
        }

        mTaskInfo1 = task1;
        mTaskInfo2 = task2;
        mSplitLayout = new SplitLayout(TAG + "SplitDivider",
                mDisplayController.getDisplayContext(mRootTaskInfo.displayId),
                mRootTaskInfo.configuration, this /* layoutChangeListener */,
                mParentContainerCallbacks, mDisplayImeController, mController.getTaskOrganizer(),
                true /* applyDismissingParallax */);
        mDisplayInsetsController.addInsetsChangedListener(mRootTaskInfo.displayId, mSplitLayout);

        final WindowContainerToken token1 = task1.token;
        final WindowContainerToken token2 = task2.token;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        wct.setHidden(mRootTaskInfo.token, false)
                .reparent(token1, mRootTaskInfo.token, true /* onTop */)
                .reparent(token2, mRootTaskInfo.token, true /* onTop */)
                .setWindowingMode(token1, WINDOWING_MODE_MULTI_WINDOW)
                .setWindowingMode(token2, WINDOWING_MODE_MULTI_WINDOW)
                .setBounds(token1, mSplitLayout.getBounds1())
                .setBounds(token2, mSplitLayout.getBounds2())
                // Moving the root task to top after the child tasks were repareted , or the root
                // task cannot be visible and focused.
                .reorder(mRootTaskInfo.token, true);
        mController.getTaskOrganizer().applyTransaction(wct);
        return true;
    }

    void unpair() {
        unpair(null /* toTopToken */);
    }

    private void unpair(@Nullable WindowContainerToken toTopToken) {
        final WindowContainerToken token1 = mTaskInfo1.token;
        final WindowContainerToken token2 = mTaskInfo2.token;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        // Reparent out of this container and reset windowing mode.
        wct.setHidden(mRootTaskInfo.token, true)
                .reorder(mRootTaskInfo.token, false)
                .reparent(token1, null, token1 == toTopToken /* onTop */)
                .reparent(token2, null, token2 == toTopToken /* onTop */)
                .setWindowingMode(token1, WINDOWING_MODE_UNDEFINED)
                .setWindowingMode(token2, WINDOWING_MODE_UNDEFINED);
        mController.getTaskOrganizer().applyTransaction(wct);

        mTaskInfo1 = null;
        mTaskInfo2 = null;
        mSplitLayout.release();
        mSplitLayout = null;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mRootTaskInfo == null || taskInfo.taskId == mRootTaskInfo.taskId) {
            mRootTaskInfo = taskInfo;
            mRootTaskLeash = leash;
        } else if (taskInfo.taskId == getTaskId1()) {
            mTaskInfo1 = taskInfo;
            mTaskLeash1 = leash;
            mSyncQueue.runInSync(t -> mDimLayer1 =
                    SurfaceUtils.makeDimLayer(t, mTaskLeash1, "Dim layer", mSurfaceSession));
        } else if (taskInfo.taskId == getTaskId2()) {
            mTaskInfo2 = taskInfo;
            mTaskLeash2 = leash;
            mSyncQueue.runInSync(t -> mDimLayer2 =
                    SurfaceUtils.makeDimLayer(t, mTaskLeash2, "Dim layer", mSurfaceSession));
        } else {
            throw new IllegalStateException("Unknown task=" + taskInfo.taskId);
        }

        if (mTaskLeash1 == null || mTaskLeash2 == null) return;

        mSplitLayout.init();

        mSyncQueue.runInSync(t -> t
                .show(mRootTaskLeash)
                .show(mTaskLeash1)
                .show(mTaskLeash2)
                .setPosition(mTaskLeash1,
                        mTaskInfo1.positionInParent.x,
                        mTaskInfo1.positionInParent.y)
                .setPosition(mTaskLeash2,
                        mTaskInfo2.positionInParent.x,
                        mTaskInfo2.positionInParent.y));
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (!taskInfo.supportsMultiWindow) {
            // Dismiss AppPair if the task no longer supports multi window.
            mController.unpair(mRootTaskInfo.taskId);
            return;
        }
        if (taskInfo.taskId == getRootTaskId()) {
            if (mRootTaskInfo.isVisible != taskInfo.isVisible) {
                mSyncQueue.runInSync(t -> {
                    if (taskInfo.isVisible) {
                        t.show(mRootTaskLeash);
                    } else {
                        t.hide(mRootTaskLeash);
                    }
                });
            }
            mRootTaskInfo = taskInfo;

            if (mSplitLayout != null
                    && mSplitLayout.updateConfiguration(mRootTaskInfo.configuration)) {
                onLayoutSizeChanged(mSplitLayout);
            }
        } else if (taskInfo.taskId == getTaskId1()) {
            mTaskInfo1 = taskInfo;
        } else if (taskInfo.taskId == getTaskId2()) {
            mTaskInfo2 = taskInfo;
        } else {
            throw new IllegalStateException("Unknown task=" + taskInfo.taskId);
        }
    }

    @Override
    public int getSplitItemPosition(WindowContainerToken token) {
        if (token == null) {
            return SPLIT_POSITION_UNDEFINED;
        }

        if (token.equals(mTaskInfo1.getToken())) {
            return SPLIT_POSITION_TOP_OR_LEFT;
        } else if (token.equals(mTaskInfo2.getToken())) {
            return SPLIT_POSITION_BOTTOM_OR_RIGHT;
        }

        return SPLIT_POSITION_UNDEFINED;
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo.taskId == getRootTaskId()) {
            // We don't want to release this object back to the pool since the root task went away.
            mController.unpair(mRootTaskInfo.taskId, false /* releaseToPool */);
        } else if (taskInfo.taskId == getTaskId1()) {
            mController.unpair(mRootTaskInfo.taskId);
            mSyncQueue.runInSync(t -> t.remove(mDimLayer1));
        } else if (taskInfo.taskId == getTaskId2()) {
            mController.unpair(mRootTaskInfo.taskId);
            mSyncQueue.runInSync(t -> t.remove(mDimLayer2));
        }
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        b.setParent(findTaskSurface(taskId));
    }

    @Override
    public void reparentChildSurfaceToTask(int taskId, SurfaceControl sc,
            SurfaceControl.Transaction t) {
        t.reparent(sc, findTaskSurface(taskId));
    }

    private SurfaceControl findTaskSurface(int taskId) {
        if (getRootTaskId() == taskId) {
            return mRootTaskLeash;
        } else if (getTaskId1() == taskId) {
            return mTaskLeash1;
        } else if (getTaskId2() == taskId) {
            return mTaskLeash2;
        } else {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
        if (mRootTaskInfo != null) {
            pw.println(innerPrefix + "Root taskId=" + mRootTaskInfo.taskId
                    + " winMode=" + mRootTaskInfo.getWindowingMode());
        }
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

    @Override
    public void onSnappedToDismiss(boolean snappedToEnd) {
        unpair(snappedToEnd ? mTaskInfo1.token : mTaskInfo2.token /* toTopToken */);
    }

    @Override
    public void onLayoutPositionChanging(SplitLayout layout) {
        mSyncQueue.runInSync(t ->
                layout.applySurfaceChanges(t, mTaskLeash1, mTaskLeash2, mDimLayer1, mDimLayer2));
    }

    @Override
    public void onLayoutSizeChanging(SplitLayout layout) {
        mSyncQueue.runInSync(t ->
                layout.applySurfaceChanges(t, mTaskLeash1, mTaskLeash2, mDimLayer1, mDimLayer2));
    }

    @Override
    public void onLayoutSizeChanged(SplitLayout layout) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        layout.applyTaskChanges(wct, mTaskInfo1, mTaskInfo2);
        mSyncQueue.queue(wct);
        mSyncQueue.runInSync(t ->
                layout.applySurfaceChanges(t, mTaskLeash1, mTaskLeash2, mDimLayer1, mDimLayer2));
    }

    @Override
    public void setLayoutOffsetTarget(int offsetX, int offsetY, SplitLayout layout) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        layout.applyLayoutOffsetTarget(wct, offsetX, offsetY, mTaskInfo1, mTaskInfo2);
        mController.getTaskOrganizer().applyTransaction(wct);
    }
}
