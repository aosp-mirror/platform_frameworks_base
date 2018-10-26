/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.android.systemui.recents.LegacyRecentsImpl;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.view.AppTransitionAnimationSpecCompat;
import com.android.systemui.shared.recents.view.RecentsTransition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A helper class to create the transition app animation specs to/from Recents
 */
public class RecentsTransitionComposer {

    private static final String TAG = "RecentsTransitionComposer";

    private Context mContext;
    private TaskViewTransform mTmpTransform = new TaskViewTransform();

    public RecentsTransitionComposer(Context context) {
        mContext = context;
    }

    /**
     * Composes a single animation spec for the given {@link TaskView}
     */
    private static AppTransitionAnimationSpecCompat composeAnimationSpec(TaskStackView stackView,
            TaskView taskView, TaskViewTransform transform, boolean addHeaderBitmap) {
        Bitmap b = null;
        if (addHeaderBitmap) {
            b = composeHeaderBitmap(taskView, transform);
            if (b == null) {
                return null;
            }
        }

        Rect taskRect = new Rect();
        transform.rect.round(taskRect);
        // Disable in for low ram devices because each task does in Recents does not have fullscreen
        // height (stackView height) and when transitioning to fullscreen app, the code below would
        // force the task thumbnail to full stackView height immediately causing the transition
        // jarring.
        if (!LegacyRecentsImpl.getConfiguration().isLowRamDevice && taskView.getTask() !=
                stackView.getStack().getFrontMostTask()) {
            taskRect.bottom = taskRect.top + stackView.getMeasuredHeight();
        }
        return new AppTransitionAnimationSpecCompat(taskView.getTask().key.id, b, taskRect);
    }

    /**
     * Composes the transition spec when docking a task, which includes a full task bitmap.
     */
    public List<AppTransitionAnimationSpecCompat> composeDockAnimationSpec(TaskView taskView,
            Rect bounds) {
        mTmpTransform.fillIn(taskView);
        Task task = taskView.getTask();
        Bitmap buffer = RecentsTransitionComposer.composeTaskBitmap(taskView, mTmpTransform);
        return Collections.singletonList(new AppTransitionAnimationSpecCompat(task.key.id, buffer,
                bounds));
    }

    /**
     * Composes the animation specs for all the tasks in the target stack.
     */
    public List<AppTransitionAnimationSpecCompat> composeAnimationSpecs(final Task task,
            final TaskStackView stackView, int windowingMode, int activityType, Rect windowRect) {
        // Calculate the offscreen task rect (for tasks that are not backed by views)
        TaskView taskView = stackView.getChildViewForTask(task);
        TaskStackLayoutAlgorithm stackLayout = stackView.getStackAlgorithm();
        Rect offscreenTaskRect = new Rect();
        stackLayout.getFrontOfStackTransform().rect.round(offscreenTaskRect);

        // If this is a full screen stack, the transition will be towards the single, full screen
        // task. We only need the transition spec for this task.

        // TODO: Sometimes targetStackId is not initialized after reboot, so we also have to
        // check for INVALID_STACK_ID (now WINDOWING_MODE_UNDEFINED)
        if (windowingMode == WINDOWING_MODE_FULLSCREEN
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                || activityType == ACTIVITY_TYPE_ASSISTANT
                || windowingMode == WINDOWING_MODE_UNDEFINED) {
            List<AppTransitionAnimationSpecCompat> specs = new ArrayList<>();
            if (taskView == null) {
                specs.add(composeOffscreenAnimationSpec(task, offscreenTaskRect));
            } else {
                mTmpTransform.fillIn(taskView);
                stackLayout.transformToScreenCoordinates(mTmpTransform, windowRect);
                AppTransitionAnimationSpecCompat spec = composeAnimationSpec(stackView, taskView,
                        mTmpTransform, true /* addHeaderBitmap */);
                if (spec != null) {
                    specs.add(spec);
                }
            }
            return specs;
        }
        return Collections.emptyList();
    }

    /**
     * Composes a single animation spec for the given {@link Task}
     */
    private static AppTransitionAnimationSpecCompat composeOffscreenAnimationSpec(Task task,
            Rect taskRect) {
        return new AppTransitionAnimationSpecCompat(task.key.id, null, taskRect);
    }

    public static Bitmap composeTaskBitmap(TaskView taskView, TaskViewTransform transform) {
        float scale = transform.scale;
        int fromWidth = (int) (transform.rect.width() * scale);
        int fromHeight = (int) (transform.rect.height() * scale);
        if (fromWidth == 0 || fromHeight == 0) {
            Log.e(TAG, "Could not compose thumbnail for task: " + taskView.getTask() +
                    " at transform: " + transform);

            return RecentsTransition.drawViewIntoHardwareBitmap(1, 1, null, 1f, 0x00ffffff);
        } else {
            if (RecentsDebugFlags.Static.EnableTransitionThumbnailDebugMode) {
                return RecentsTransition.drawViewIntoHardwareBitmap(fromWidth, fromHeight, null, 1f,
                        0xFFff0000);
            } else {
                return RecentsTransition.drawViewIntoHardwareBitmap(fromWidth, fromHeight, taskView,
                        scale, 0);
            }
        }
    }

    private static Bitmap composeHeaderBitmap(TaskView taskView,
            TaskViewTransform transform) {
        float scale = transform.scale;
        int headerWidth = (int) (transform.rect.width());
        int headerHeight = (int) (taskView.mHeaderView.getMeasuredHeight() * scale);
        if (headerWidth == 0 || headerHeight == 0) {
            return null;
        }

        if (RecentsDebugFlags.Static.EnableTransitionThumbnailDebugMode) {
            return RecentsTransition.drawViewIntoHardwareBitmap(headerWidth, headerHeight, null, 1f,
                    0xFFff0000);
        } else {
            return RecentsTransition.drawViewIntoHardwareBitmap(headerWidth, headerHeight,
                    taskView.mHeaderView, scale, 0);
        }
    }
}
