/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Gravity;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.LaunchParamsController.LaunchParams;
import com.android.server.am.LaunchParamsController.LaunchParamsModifier;

import java.util.ArrayList;

/**
 * Determines where a launching task should be positioned and sized on the display.
 *
 * The modifier is fairly simple. For the new task it tries default position based on the gravity
 * and compares corners of the task with corners of existing tasks. If some two pairs of corners are
 * sufficiently close enough, it shifts the bounds of the new task and tries again. When it exhausts
 * all possible shifts, it gives up and puts the task in the original position.
 *
 * Note that the only gravities of concern are the corners and the center.
 */
class TaskLaunchParamsModifier implements LaunchParamsModifier {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "TaskLaunchParamsModifier" : TAG_AM;

    // Determines how close window frames/corners have to be to call them colliding.
    private static final int BOUNDS_CONFLICT_MIN_DISTANCE = 4;

    // Task will receive dimensions based on available dimensions divided by this.
    private static final int WINDOW_SIZE_DENOMINATOR = 2;

    // Task will receive margins based on available dimensions divided by this.
    private static final int MARGIN_SIZE_DENOMINATOR = 4;

    // If task bounds collide with some other, we will step and try again until we find a good
    // position. The step will be determined by using dimensions and dividing it by this.
    private static final int STEP_DENOMINATOR = 16;

    // We always want to step by at least this.
    private static final int MINIMAL_STEP = 1;

    // Used to indicate if positioning algorithm is allowed to restart from the beginning, when it
    // reaches the end of stack bounds.
    private static final boolean ALLOW_RESTART = true;

    private static final int SHIFT_POLICY_DIAGONAL_DOWN = 1;
    private static final int SHIFT_POLICY_HORIZONTAL_RIGHT = 2;
    private static final int SHIFT_POLICY_HORIZONTAL_LEFT = 3;

    private final Rect mAvailableRect = new Rect();
    private final Rect mTmpProposal = new Rect();
    private final Rect mTmpOriginal = new Rect();

    /**
     * Tries to set task's bound in a way that it won't collide with any other task. By colliding
     * we mean that two tasks have left-top corner very close to each other, so one might get
     * obfuscated by the other one.
     */
    @Override
    public int onCalculate(TaskRecord task, ActivityInfo.WindowLayout layout,
                           ActivityRecord activity, ActivityRecord source, ActivityOptions options,
                           LaunchParams currentParams, LaunchParams outParams) {
        // We can only apply positioning if we're in a freeform stack.
        if (task == null || task.getStack() == null || !task.inFreeformWindowingMode()) {
            return RESULT_SKIP;
        }

        final ArrayList<TaskRecord> tasks = task.getStack().getAllTasks();

        mAvailableRect.set(task.getParent().getBounds());

        final Rect resultBounds = outParams.mBounds;

        if (layout == null) {
            positionCenter(tasks, mAvailableRect, getFreeformWidth(mAvailableRect),
                    getFreeformHeight(mAvailableRect), resultBounds);
            return RESULT_CONTINUE;
        }

        int width = getFinalWidth(layout, mAvailableRect);
        int height = getFinalHeight(layout, mAvailableRect);
        int verticalGravity = layout.gravity & Gravity.VERTICAL_GRAVITY_MASK;
        int horizontalGravity = layout.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (verticalGravity == Gravity.TOP) {
            if (horizontalGravity == Gravity.RIGHT) {
                positionTopRight(tasks, mAvailableRect, width, height, resultBounds);
            } else {
                positionTopLeft(tasks, mAvailableRect, width, height, resultBounds);
            }
        } else if (verticalGravity == Gravity.BOTTOM) {
            if (horizontalGravity == Gravity.RIGHT) {
                positionBottomRight(tasks, mAvailableRect, width, height, resultBounds);
            } else {
                positionBottomLeft(tasks, mAvailableRect, width, height, resultBounds);
            }
        } else {
            // Some fancy gravity setting that we don't support yet. We just put the activity in the
            // center.
            Slog.w(TAG, "Received unsupported gravity: " + layout.gravity
                    + ", positioning in the center instead.");
            positionCenter(tasks, mAvailableRect, width, height, resultBounds);
        }

        return RESULT_CONTINUE;
    }

    @VisibleForTesting
    static int getFreeformStartLeft(Rect bounds) {
        return bounds.left + bounds.width() / MARGIN_SIZE_DENOMINATOR;
    }

    @VisibleForTesting
    static int getFreeformStartTop(Rect bounds) {
        return bounds.top + bounds.height() / MARGIN_SIZE_DENOMINATOR;
    }

    @VisibleForTesting
    static int getFreeformWidth(Rect bounds) {
        return bounds.width() / WINDOW_SIZE_DENOMINATOR;
    }

    @VisibleForTesting
    static int getFreeformHeight(Rect bounds) {
        return bounds.height() / WINDOW_SIZE_DENOMINATOR;
    }

    @VisibleForTesting
    static int getHorizontalStep(Rect bounds) {
        return Math.max(bounds.width() / STEP_DENOMINATOR, MINIMAL_STEP);
    }

    @VisibleForTesting
    static int getVerticalStep(Rect bounds) {
        return Math.max(bounds.height() / STEP_DENOMINATOR, MINIMAL_STEP);
    }



    private int getFinalWidth(ActivityInfo.WindowLayout windowLayout, Rect availableRect) {
        int width = getFreeformWidth(availableRect);
        if (windowLayout.width > 0) {
            width = windowLayout.width;
        }
        if (windowLayout.widthFraction > 0) {
            width = (int) (availableRect.width() * windowLayout.widthFraction);
        }
        return width;
    }

    private int getFinalHeight(ActivityInfo.WindowLayout windowLayout, Rect availableRect) {
        int height = getFreeformHeight(availableRect);
        if (windowLayout.height > 0) {
            height = windowLayout.height;
        }
        if (windowLayout.heightFraction > 0) {
            height = (int) (availableRect.height() * windowLayout.heightFraction);
        }
        return height;
    }

    private void positionBottomLeft(ArrayList<TaskRecord> tasks, Rect availableRect, int width,
            int height, Rect result) {
        mTmpProposal.set(availableRect.left, availableRect.bottom - height,
                availableRect.left + width, availableRect.bottom);
        position(tasks, availableRect, mTmpProposal, !ALLOW_RESTART, SHIFT_POLICY_HORIZONTAL_RIGHT,
                result);
    }

    private void positionBottomRight(ArrayList<TaskRecord> tasks, Rect availableRect, int width,
            int height, Rect result) {
        mTmpProposal.set(availableRect.right - width, availableRect.bottom - height,
                availableRect.right, availableRect.bottom);
        position(tasks, availableRect, mTmpProposal, !ALLOW_RESTART, SHIFT_POLICY_HORIZONTAL_LEFT,
                result);
    }

    private void positionTopLeft(ArrayList<TaskRecord> tasks, Rect availableRect, int width,
            int height, Rect result) {
        mTmpProposal.set(availableRect.left, availableRect.top,
                availableRect.left + width, availableRect.top + height);
        position(tasks, availableRect, mTmpProposal, !ALLOW_RESTART, SHIFT_POLICY_HORIZONTAL_RIGHT,
                result);
    }

    private void positionTopRight(ArrayList<TaskRecord> tasks, Rect availableRect, int width,
            int height, Rect result) {
        mTmpProposal.set(availableRect.right - width, availableRect.top,
                availableRect.right, availableRect.top + height);
        position(tasks, availableRect, mTmpProposal, !ALLOW_RESTART, SHIFT_POLICY_HORIZONTAL_LEFT,
                result);
    }

    private void positionCenter(ArrayList<TaskRecord> tasks, Rect availableRect, int width,
            int height, Rect result) {
        final int defaultFreeformLeft = getFreeformStartLeft(availableRect);
        final int defaultFreeformTop = getFreeformStartTop(availableRect);
        mTmpProposal.set(defaultFreeformLeft, defaultFreeformTop,
                defaultFreeformLeft + width, defaultFreeformTop + height);
        position(tasks, availableRect, mTmpProposal, ALLOW_RESTART, SHIFT_POLICY_DIAGONAL_DOWN,
                result);
    }

    private void position(ArrayList<TaskRecord> tasks, Rect availableRect,
            Rect proposal, boolean allowRestart, int shiftPolicy, Rect result) {
        mTmpOriginal.set(proposal);
        boolean restarted = false;
        while (boundsConflict(proposal, tasks)) {
            // Unfortunately there is already a task at that spot, so we need to look for some
            // other place.
            shiftStartingPoint(proposal, availableRect, shiftPolicy);
            if (shiftedTooFar(proposal, availableRect, shiftPolicy)) {
                // We don't want the task to go outside of the stack, because it won't look
                // nice. Depending on the starting point we either restart, or immediately give up.
                if (!allowRestart) {
                    proposal.set(mTmpOriginal);
                    break;
                }
                // We must have started not from the top. Let's restart from there because there
                // might be some space there.
                proposal.set(availableRect.left, availableRect.top,
                        availableRect.left + proposal.width(),
                        availableRect.top + proposal.height());
                restarted = true;
            }
            if (restarted && (proposal.left > getFreeformStartLeft(availableRect)
                    || proposal.top > getFreeformStartTop(availableRect))) {
                // If we restarted and crossed the initial position, let's not struggle anymore.
                // The user already must have ton of tasks visible, we can just smack the new
                // one in the center.
                proposal.set(mTmpOriginal);
                break;
            }
        }
        result.set(proposal);
    }

    private boolean shiftedTooFar(Rect start, Rect availableRect, int shiftPolicy) {
        switch (shiftPolicy) {
            case SHIFT_POLICY_HORIZONTAL_LEFT:
                return start.left < availableRect.left;
            case SHIFT_POLICY_HORIZONTAL_RIGHT:
                return start.right > availableRect.right;
            default: // SHIFT_POLICY_DIAGONAL_DOWN
                return start.right > availableRect.right || start.bottom > availableRect.bottom;
        }
    }

    private void shiftStartingPoint(Rect posposal, Rect availableRect, int shiftPolicy) {
        final int defaultFreeformStepHorizontal = getHorizontalStep(availableRect);
        final int defaultFreeformStepVertical = getVerticalStep(availableRect);

        switch (shiftPolicy) {
            case SHIFT_POLICY_HORIZONTAL_LEFT:
                posposal.offset(-defaultFreeformStepHorizontal, 0);
                break;
            case SHIFT_POLICY_HORIZONTAL_RIGHT:
                posposal.offset(defaultFreeformStepHorizontal, 0);
                break;
            default: // SHIFT_POLICY_DIAGONAL_DOWN:
                posposal.offset(defaultFreeformStepHorizontal, defaultFreeformStepVertical);
                break;
        }
    }

    private static boolean boundsConflict(Rect proposal, ArrayList<TaskRecord> tasks) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final TaskRecord task = tasks.get(i);
            if (!task.mActivities.isEmpty() && !task.matchParentBounds()) {
                final Rect bounds = task.getOverrideBounds();
                if (closeLeftTopCorner(proposal, bounds) || closeRightTopCorner(proposal, bounds)
                        || closeLeftBottomCorner(proposal, bounds)
                        || closeRightBottomCorner(proposal, bounds)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final boolean closeLeftTopCorner(Rect first, Rect second) {
        return Math.abs(first.left - second.left) < BOUNDS_CONFLICT_MIN_DISTANCE
                && Math.abs(first.top - second.top) < BOUNDS_CONFLICT_MIN_DISTANCE;
    }

    private static final boolean closeRightTopCorner(Rect first, Rect second) {
        return Math.abs(first.right - second.right) < BOUNDS_CONFLICT_MIN_DISTANCE
                && Math.abs(first.top - second.top) < BOUNDS_CONFLICT_MIN_DISTANCE;
    }

    private static final boolean closeLeftBottomCorner(Rect first, Rect second) {
        return Math.abs(first.left - second.left) < BOUNDS_CONFLICT_MIN_DISTANCE
                && Math.abs(first.bottom - second.bottom) < BOUNDS_CONFLICT_MIN_DISTANCE;
    }

    private static final boolean closeRightBottomCorner(Rect first, Rect second) {
        return Math.abs(first.right - second.right) < BOUNDS_CONFLICT_MIN_DISTANCE
                && Math.abs(first.bottom - second.bottom) < BOUNDS_CONFLICT_MIN_DISTANCE;
    }
}
