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

package com.android.wm.shell.letterbox;

import android.app.ActivityManager;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.Transitions;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

/**
  * Organizes a task in {@link android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN} when
  * it's presented in the letterbox mode either because orientations of a top activity and a device
  * don't match or because a top activity is in a size compat mode.
  */
public class LetterboxTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "LetterboxTaskListener";

    private final SyncTransactionQueue mSyncQueue;
    private final LetterboxConfigController mLetterboxConfigController;
    private final WindowManager mWindowManager;
    private final SparseArray<SurfaceControl> mLeashByTaskId = new SparseArray<>();

    public LetterboxTaskListener(
            SyncTransactionQueue syncQueue,
            LetterboxConfigController letterboxConfigController,
            WindowManager windowManager) {
        mSyncQueue = syncQueue;
        mLetterboxConfigController = letterboxConfigController;
        mWindowManager = windowManager;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mLeashByTaskId.get(taskInfo.taskId) != null) {
            throw new IllegalStateException("Task appeared more than once: #" + taskInfo.taskId);
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Letterbox Task Appeared: #%d",
                taskInfo.taskId);
        mLeashByTaskId.put(taskInfo.taskId, leash);
        Point positionInParent = new Point();
        Rect crop = new Rect();
        resolveTaskPositionAndCrop(taskInfo, positionInParent, crop);
        mSyncQueue.runInSync(t -> {
            setPositionAndWindowCrop(t, leash, positionInParent, crop);
            if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
                t.setAlpha(leash, 1f);
                t.setMatrix(leash, 1, 0, 0, 1);
                t.show(leash);
            }
        });
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        if (mLeashByTaskId.get(taskInfo.taskId) == null) {
            Slog.e(TAG, "Task already vanished: #" + taskInfo.taskId);
            return;
        }
        mLeashByTaskId.remove(taskInfo.taskId);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Letterbox Task Vanished: #%d",
                taskInfo.taskId);
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Letterbox Task Changed: #%d",
                taskInfo.taskId);
        final SurfaceControl leash = mLeashByTaskId.get(taskInfo.taskId);
        Point positionInParent = new Point();
        Rect crop = new Rect();
        resolveTaskPositionAndCrop(taskInfo, positionInParent, crop);
        mSyncQueue.runInSync(t -> setPositionAndWindowCrop(t, leash, positionInParent, crop));
    }

    private static void setPositionAndWindowCrop(
                SurfaceControl.Transaction transaction,
                SurfaceControl leash,
                final Point positionInParent,
                final Rect crop) {
        transaction.setPosition(leash, positionInParent.x, positionInParent.y);
        transaction.setWindowCrop(leash, crop);
    }

    // TODO(b/173440321): Correct presentation of letterboxed activities in One-handed mode.
    private void resolveTaskPositionAndCrop(
                ActivityManager.RunningTaskInfo taskInfo,
                Point positionInParent,
                Rect crop) {
        // In screen coordinates
        Rect parentBounds = new Rect(taskInfo.parentBounds);
        // Intersect parent and max bounds. This is required for situations when parent bounds
        // go beyond display bounds, for example, in One-handed mode.
        final Rect maxBounds = taskInfo.getConfiguration().windowConfiguration.getMaxBounds();
        if (!parentBounds.intersect(maxBounds)) {
            Slog.w(TAG, "Task parent and max bounds don't intersect: #" + taskInfo.taskId);
        }

        // In screen coordinates
        final Rect taskBounds = taskInfo.getConfiguration().windowConfiguration.getBounds();
        final Rect activityBounds = taskInfo.letterboxActivityBounds;

        Insets insets = getInsets();
        Rect displayBoundsWithInsets =
                new Rect(mWindowManager.getMaximumWindowMetrics().getBounds());
        displayBoundsWithInsets.inset(insets);

        Rect taskBoundsWithInsets = new Rect(taskBounds);
        taskBoundsWithInsets.intersect(displayBoundsWithInsets);

        Rect activityBoundsWithInsets = new Rect(activityBounds);
        activityBoundsWithInsets.intersect(displayBoundsWithInsets);

        Rect parentBoundsWithInsets = new Rect(parentBounds);
        parentBoundsWithInsets.intersect(displayBoundsWithInsets);

        // Crop need to be in the task coordinates.
        crop.set(activityBoundsWithInsets);
        crop.offset(-taskBounds.left, -taskBounds.top);

        // Account for insets since coordinates calculations below are done with them.
        positionInParent.x = parentBoundsWithInsets.left - parentBounds.left
                    - (taskBoundsWithInsets.left - taskBounds.left);
        positionInParent.y = parentBoundsWithInsets.top - parentBounds.top
                - (taskBoundsWithInsets.top - taskBounds.top);

        // Calculating a position of task bounds (without insets) in parent coordinates (without
        // insets) to align activity bounds (without insets) as requested in config. Activity
        // accounts for insets that overlap with its bounds (this overlap can be partial) so
        // ignoring overlap with insets when computing the position. Also, cropping unwanted insets
        // while keeping the top one if the activity is aligned at the top of the window to show
        // status bar decor view.
        if (parentBounds.height() >= parentBounds.width()) {
            final int gravity = mLetterboxConfigController.getPortraitGravity();
            // Center activity horizontally.
            positionInParent.x +=
                    (parentBoundsWithInsets.width() - activityBoundsWithInsets.width()) / 2
                            + taskBoundsWithInsets.left - activityBoundsWithInsets.left;
            switch (gravity) {
                case Gravity.TOP:
                    positionInParent.y += taskBoundsWithInsets.top - activityBoundsWithInsets.top;
                    break;
                case Gravity.CENTER:
                    positionInParent.y +=
                            taskBoundsWithInsets.top - activityBoundsWithInsets.top
                                    + (parentBoundsWithInsets.height()
                                            - activityBoundsWithInsets.height()) / 2;
                    break;
                case Gravity.BOTTOM:
                    positionInParent.y +=
                            parentBoundsWithInsets.height() - activityBoundsWithInsets.bottom
                                    + taskBoundsWithInsets.top;
                    break;
                default:
                    throw new AssertionError(
                            "Unexpected portrait gravity " + gravity
                            + " for task: #" + taskInfo.taskId);
            }
        } else {
            final int gravity = mLetterboxConfigController.getLandscapeGravity();
            // Align activity to the top.
            positionInParent.y += taskBoundsWithInsets.top - activityBoundsWithInsets.top;
            switch (gravity) {
                case Gravity.LEFT:
                    positionInParent.x += taskBoundsWithInsets.left - activityBoundsWithInsets.left;
                    break;
                case Gravity.CENTER:
                    positionInParent.x +=
                            (parentBoundsWithInsets.width() - activityBoundsWithInsets.width()) / 2
                                    + taskBoundsWithInsets.left - activityBoundsWithInsets.left;
                    break;
                case Gravity.RIGHT:
                    positionInParent.x +=
                            parentBoundsWithInsets.width()
                                    - activityBoundsWithInsets.right + taskBoundsWithInsets.left;
                    break;
                default:
                    throw new AssertionError(
                            "Unexpected landscape gravity " + gravity
                            + " for task: #" + taskInfo.taskId);
            }
        }

        // New bounds of the activity after it's repositioned with required gravity.
        Rect newActivityBounds = new Rect(activityBounds);
        // Task's surfce will be repositioned to positionInParent together with the activity
        // inside it so the new activity bounds are the original activity bounds offset by
        // the task's offset.
        newActivityBounds.offset(
                positionInParent.x - taskBounds.left, positionInParent.y - taskBounds.top);
        Rect newActivityBoundsWithInsets = new Rect(newActivityBounds);
        newActivityBoundsWithInsets.intersect(displayBoundsWithInsets);
        // Activity handles insets on its own (e.g. under status bar or navigation bar).
        // crop that is calculated above crops all insets from an activity and below insets that
        // can be shown are added back to the crop bounds  (e.g. if activity is still shown at the
        // top of the display then the top inset won't be cropped).
        // After task's surface is repositioned, intersection between an activity and insets can
        // change but if it doesn't, the activity should be shown under insets to maximize visible
        // area.
        // Also, an activity can use area under insets and insets shouldn't be cropped in this case
        // regardless of a position on the screen.
        final Rect activityInsetsFromCore = taskInfo.letterboxActivityInsets;
        if (newActivityBounds.top - newActivityBoundsWithInsets.top
                == activityBounds.top - activityBoundsWithInsets.top
                // Check whether an activity is shown under inset. If it is, then the inset from
                // WM Core and the inset computed here will be different because local insets
                // doesn't take into account visibility of insets requested by the activity.
                ||  activityBoundsWithInsets.top - activityBounds.top
                        != activityInsetsFromCore.top) {
            crop.top -= activityBoundsWithInsets.top - activityBounds.top;
        }
        if (newActivityBounds.bottom - newActivityBoundsWithInsets.bottom
                == activityBounds.bottom - activityBoundsWithInsets.bottom
                || activityBounds.bottom - activityBoundsWithInsets.bottom
                        != activityInsetsFromCore.bottom) {
            crop.bottom += activityBounds.bottom - activityBoundsWithInsets.bottom;
        }
        if (newActivityBounds.left - newActivityBoundsWithInsets.left
                == activityBounds.left - activityBoundsWithInsets.left
                || activityBoundsWithInsets.left - activityBounds.left
                        != activityInsetsFromCore.left) {
            crop.left -= activityBoundsWithInsets.left - activityBounds.left;
        }
        if (newActivityBounds.right - newActivityBoundsWithInsets.right
                == activityBounds.right - activityBoundsWithInsets.right
                || activityBounds.right - activityBoundsWithInsets.right
                        != activityInsetsFromCore.right) {
            crop.right += activityBounds.right - activityBoundsWithInsets.right;
        }
    }

    private Insets getInsets() {
        return mWindowManager
                .getMaximumWindowMetrics()
                .getWindowInsets()
                .getInsets(
                        WindowInsets.Type.navigationBars()
                                | WindowInsets.Type.statusBars()
                                | WindowInsets.Type.displayCutout());
    }

}
