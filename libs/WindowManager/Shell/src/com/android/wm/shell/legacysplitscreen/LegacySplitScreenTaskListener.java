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

package com.android.wm.shell.legacysplitscreen;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_TASK_ORG;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.TaskOrganizer;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.ArrayList;

class LegacySplitScreenTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = LegacySplitScreenTaskListener.class.getSimpleName();
    private static final boolean DEBUG = LegacySplitScreenController.DEBUG;

    private final ShellTaskOrganizer mTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final SparseArray<SurfaceControl> mLeashByTaskId = new SparseArray<>();

    // TODO(shell-transitions): Remove when switched to shell-transitions.
    private final SparseArray<Point> mPositionByTaskId = new SparseArray<>();

    RunningTaskInfo mPrimary;
    RunningTaskInfo mSecondary;
    SurfaceControl mPrimarySurface;
    SurfaceControl mSecondarySurface;
    SurfaceControl mPrimaryDim;
    SurfaceControl mSecondaryDim;
    Rect mHomeBounds = new Rect();
    final LegacySplitScreenController mSplitScreenController;
    private boolean mSplitScreenSupported = false;

    final SurfaceSession mSurfaceSession = new SurfaceSession();

    private final LegacySplitScreenTransitions mSplitTransitions;

    LegacySplitScreenTaskListener(LegacySplitScreenController splitScreenController,
                    ShellTaskOrganizer shellTaskOrganizer,
                    Transitions transitions,
                    SyncTransactionQueue syncQueue) {
        mSplitScreenController = splitScreenController;
        mTaskOrganizer = shellTaskOrganizer;
        mSplitTransitions = new LegacySplitScreenTransitions(splitScreenController.mTransactionPool,
                transitions, mSplitScreenController, this);
        transitions.addHandler(mSplitTransitions);
        mSyncQueue = syncQueue;
    }

    void init() {
        synchronized (this) {
            try {
                mTaskOrganizer.createRootTask(
                        DEFAULT_DISPLAY, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, this);
                mTaskOrganizer.createRootTask(
                        DEFAULT_DISPLAY, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, this);
            } catch (Exception e) {
                // teardown to prevent callbacks
                mTaskOrganizer.removeListener(this);
                throw e;
            }
        }
    }

    boolean isSplitScreenSupported() {
        return mSplitScreenSupported;
    }

    SurfaceControl.Transaction getTransaction() {
        return mSplitScreenController.mTransactionPool.acquire();
    }

    void releaseTransaction(SurfaceControl.Transaction t) {
        mSplitScreenController.mTransactionPool.release(t);
    }

    TaskOrganizer getTaskOrganizer() {
        return mTaskOrganizer;
    }

    LegacySplitScreenTransitions getSplitTransitions() {
        return mSplitTransitions;
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        synchronized (this) {
            if (taskInfo.hasParentTask()) {
                handleChildTaskAppeared(taskInfo, leash);
                return;
            }

            final int winMode = taskInfo.getWindowingMode();
            if (winMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                ProtoLog.v(WM_SHELL_TASK_ORG,
                        "%s onTaskAppeared Primary taskId=%d", TAG, taskInfo.taskId);
                mPrimary = taskInfo;
                mPrimarySurface = leash;
            } else if (winMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
                ProtoLog.v(WM_SHELL_TASK_ORG,
                        "%s onTaskAppeared Secondary taskId=%d", TAG, taskInfo.taskId);
                mSecondary = taskInfo;
                mSecondarySurface = leash;
            } else {
                ProtoLog.v(WM_SHELL_TASK_ORG, "%s onTaskAppeared unknown taskId=%d winMode=%d",
                        TAG, taskInfo.taskId, winMode);
            }

            if (!mSplitScreenSupported && mPrimarySurface != null && mSecondarySurface != null) {
                mSplitScreenSupported = true;
                mSplitScreenController.onSplitScreenSupported();
                ProtoLog.v(WM_SHELL_TASK_ORG, "%s onTaskAppeared Supported", TAG);

                // Initialize dim surfaces:
                mPrimaryDim = new SurfaceControl.Builder(mSurfaceSession)
                        .setParent(mPrimarySurface).setColorLayer()
                        .setName("Primary Divider Dim")
                        .setCallsite("SplitScreenTaskOrganizer.onTaskAppeared")
                        .build();
                mSecondaryDim = new SurfaceControl.Builder(mSurfaceSession)
                        .setParent(mSecondarySurface).setColorLayer()
                        .setName("Secondary Divider Dim")
                        .setCallsite("SplitScreenTaskOrganizer.onTaskAppeared")
                        .build();
                SurfaceControl.Transaction t = getTransaction();
                t.setLayer(mPrimaryDim, Integer.MAX_VALUE);
                t.setColor(mPrimaryDim, new float[]{0f, 0f, 0f});
                t.setLayer(mSecondaryDim, Integer.MAX_VALUE);
                t.setColor(mSecondaryDim, new float[]{0f, 0f, 0f});
                t.apply();
                releaseTransaction(t);
            }
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        synchronized (this) {
            mPositionByTaskId.remove(taskInfo.taskId);
            if (taskInfo.hasParentTask()) {
                mLeashByTaskId.remove(taskInfo.taskId);
                return;
            }

            final boolean isPrimaryTask = mPrimary != null
                    && taskInfo.token.equals(mPrimary.token);
            final boolean isSecondaryTask = mSecondary != null
                    && taskInfo.token.equals(mSecondary.token);

            if (mSplitScreenSupported && (isPrimaryTask || isSecondaryTask)) {
                mSplitScreenSupported = false;

                SurfaceControl.Transaction t = getTransaction();
                t.remove(mPrimaryDim);
                t.remove(mSecondaryDim);
                t.remove(mPrimarySurface);
                t.remove(mSecondarySurface);
                t.apply();
                releaseTransaction(t);

                mSplitScreenController.onTaskVanished();
            }
        }
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        if (taskInfo.displayId != DEFAULT_DISPLAY) {
            return;
        }
        synchronized (this) {
            if (taskInfo.hasParentTask()) {
                // changed messages are noisy since it reports on every ensureVisibility. This
                // conflicts with legacy app-transitions which "swaps" the position to a
                // leash. For now, only update when position actually changes to avoid
                // poorly-timed duplicate calls.
                if (taskInfo.positionInParent.equals(mPositionByTaskId.get(taskInfo.taskId))) {
                    return;
                }
                handleChildTaskChanged(taskInfo);
            } else {
                handleTaskInfoChanged(taskInfo);
            }
            mPositionByTaskId.put(taskInfo.taskId, new Point(taskInfo.positionInParent));
        }
    }

    private void handleChildTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        mLeashByTaskId.put(taskInfo.taskId, leash);
        mPositionByTaskId.put(taskInfo.taskId, new Point(taskInfo.positionInParent));
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        updateChildTaskSurface(taskInfo, leash, true /* firstAppeared */);
    }

    private void handleChildTaskChanged(RunningTaskInfo taskInfo) {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        final SurfaceControl leash = mLeashByTaskId.get(taskInfo.taskId);
        updateChildTaskSurface(taskInfo, leash, false /* firstAppeared */);
    }

    private void updateChildTaskSurface(
            RunningTaskInfo taskInfo, SurfaceControl leash, boolean firstAppeared) {
        final Point taskPositionInParent = taskInfo.positionInParent;
        mSyncQueue.runInSync(t -> {
            t.setWindowCrop(leash, null);
            t.setPosition(leash, taskPositionInParent.x, taskPositionInParent.y);
            if (firstAppeared && !Transitions.ENABLE_SHELL_TRANSITIONS) {
                t.setAlpha(leash, 1f);
                t.setMatrix(leash, 1, 0, 0, 1);
                t.show(leash);
            }
        });
    }

    /**
     * This is effectively a finite state machine which moves between the various split-screen
     * presentations based on the contents of the split regions.
     */
    private void handleTaskInfoChanged(RunningTaskInfo info) {
        if (!mSplitScreenSupported) {
            // This shouldn't happen; but apparently there is a chance that SysUI crashes without
            // system server receiving binder-death (or maybe it receives binder-death too late?).
            // In this situation, when sys-ui restarts, the split root-tasks will still exist so
            // there is a small window of time during init() where WM might send messages here
            // before init() fails. So, avoid a cycle of crashes by returning early.
            Log.e(TAG, "Got handleTaskInfoChanged when not initialized: " + info);
            return;
        }
        final boolean secondaryImpliedMinimize = mSecondary.topActivityType == ACTIVITY_TYPE_HOME
                || (mSecondary.topActivityType == ACTIVITY_TYPE_RECENTS
                        && mSplitScreenController.isHomeStackResizable());
        final boolean primaryWasEmpty = mPrimary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryWasEmpty = mSecondary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        if (info.token.asBinder() == mPrimary.token.asBinder()) {
            mPrimary = info;
        } else if (info.token.asBinder() == mSecondary.token.asBinder()) {
            mSecondary = info;
        }
        if (DEBUG) {
            Log.d(TAG, "onTaskInfoChanged " + mPrimary + "  " + mSecondary);
        }
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        final boolean primaryIsEmpty = mPrimary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryIsEmpty = mSecondary.topActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean secondaryImpliesMinimize = mSecondary.topActivityType == ACTIVITY_TYPE_HOME
                || (mSecondary.topActivityType == ACTIVITY_TYPE_RECENTS
                        && mSplitScreenController.isHomeStackResizable());
        if (primaryIsEmpty == primaryWasEmpty && secondaryWasEmpty == secondaryIsEmpty
                && secondaryImpliedMinimize == secondaryImpliesMinimize) {
            // No relevant changes
            return;
        }
        if (primaryIsEmpty || secondaryIsEmpty) {
            // At-least one of the splits is empty which means we are currently transitioning
            // into or out-of split-screen mode.
            if (DEBUG) {
                Log.d(TAG, " at-least one split empty " + mPrimary.topActivityType
                        + "  " + mSecondary.topActivityType);
            }
            if (mSplitScreenController.isDividerVisible()) {
                // Was in split-mode, which means we are leaving split, so continue that.
                // This happens when the stack in the primary-split is dismissed.
                if (DEBUG) {
                    Log.d(TAG, "    was in split, so this means leave it "
                            + mPrimary.topActivityType + "  " + mSecondary.topActivityType);
                }
                mSplitScreenController.startDismissSplit(false /* toPrimaryTask */);
            } else if (!primaryIsEmpty && primaryWasEmpty && secondaryWasEmpty) {
                // Wasn't in split-mode (both were empty), but now that the primary split is
                // populated, we should fully enter split by moving everything else into secondary.
                // This just tells window-manager to reparent things, the UI will respond
                // when it gets new task info for the secondary split.
                if (DEBUG) {
                    Log.d(TAG, "   was not in split, but primary is populated, so enter it");
                }
                mSplitScreenController.startEnterSplit();
            }
        } else if (secondaryImpliesMinimize) {
            // Workaround for b/172686383, we can't rely on the sync bounds change transaction for
            // the home task to finish before the last updateChildTaskSurface() call even if it's
            // queued on the sync transaction queue, so ensure that the home task surface is updated
            // again before we minimize
            final ArrayList<RunningTaskInfo> tasks = new ArrayList<>();
            mSplitScreenController.getWmProxy().getHomeAndRecentsTasks(tasks,
                    mSplitScreenController.getSecondaryRoot());
            for (int i = 0; i < tasks.size(); i++) {
                final RunningTaskInfo taskInfo = tasks.get(i);
                final SurfaceControl leash = mLeashByTaskId.get(taskInfo.taskId);
                if (leash != null) {
                    updateChildTaskSurface(taskInfo, leash, false /* firstAppeared */);
                }
            }

            // Both splits are populated but the secondary split has a home/recents stack on top,
            // so enter minimized mode.
            mSplitScreenController.ensureMinimizedSplit();
        } else {
            // Both splits are populated by normal activities, so make sure we aren't minimized.
            mSplitScreenController.ensureNormalSplit();
        }
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        if (!mLeashByTaskId.contains(taskId)) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        b.setParent(mLeashByTaskId.get(taskId));
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + "mSplitScreenSupported=" + mSplitScreenSupported);
        if (mPrimary != null) pw.println(innerPrefix + "mPrimary.taskId=" + mPrimary.taskId);
        if (mSecondary != null) pw.println(innerPrefix + "mSecondary.taskId=" + mSecondary.taskId);
    }

    @Override
    public String toString() {
        return TAG;
    }
}
