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

import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;

import java.util.ArrayList;

/**
 * Determines where a launching task should be positioned and sized on the display.
 *
 * The positioner is fairly simple. For the new task it tries default position based on the gravity
 * and compares corners of the task with corners of existing tasks. If some two pairs of corners are
 * sufficiently close enough, it shifts the bounds of the new task and tries again. When it exhausts
 * all possible shifts, it gives up and puts the task in the original position.
 */
class LaunchingTaskPositioner {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "LaunchingTaskPositioner" : TAG_AM;

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

    private boolean mDefaultStartBoundsConfigurationSet = false;
    private final Rect mAvailableRect = new Rect();
    private final Rect mTmpProposal = new Rect();
    private final Rect mTmpOriginal = new Rect();

    private int mDefaultFreeformStartX;
    private int mDefaultFreeformStartY;
    private int mDefaultFreeformWidth;
    private int mDefaultFreeformHeight;
    private int mDefaultFreeformStepHorizontal;
    private int mDefaultFreeformStepVertical;
    private int mDisplayWidth;
    private int mDisplayHeight;

    void setDisplay(Display display) {
        Point size = new Point();
        display.getSize(size);
        mDisplayWidth = size.x;
        mDisplayHeight = size.y;
    }

    void configure(Rect stackBounds) {
        if (stackBounds == null) {
            mAvailableRect.set(0, 0, mDisplayWidth, mDisplayHeight);
        } else {
            mAvailableRect.set(stackBounds);
        }
        int width = mAvailableRect.width();
        int height = mAvailableRect.height();
        mDefaultFreeformStartX = mAvailableRect.left + width / MARGIN_SIZE_DENOMINATOR;
        mDefaultFreeformStartY = mAvailableRect.top + height / MARGIN_SIZE_DENOMINATOR;
        mDefaultFreeformWidth = width / WINDOW_SIZE_DENOMINATOR;
        mDefaultFreeformHeight = height / WINDOW_SIZE_DENOMINATOR;
        mDefaultFreeformStepHorizontal = Math.max(width / STEP_DENOMINATOR, MINIMAL_STEP);
        mDefaultFreeformStepVertical = Math.max(height / STEP_DENOMINATOR, MINIMAL_STEP);
        mDefaultStartBoundsConfigurationSet = true;
    }

    /**
     * Tries to set task's bound in a way that it won't collide with any other task. By colliding
     * we mean that two tasks have left-top corner very close to each other, so one might get
     * obfuscated by the other one.
     *
     * @param task Task for which we want to find bounds that won't collide with other.
     * @param tasks Existing tasks with which we don't want to collide.
     * @param windowLayout Optional information from the client about how it would like to be sized
     *                      and positioned.
     */
    void updateDefaultBounds(TaskRecord task, ArrayList<TaskRecord> tasks,
            @Nullable ActivityInfo.WindowLayout windowLayout) {
        if (!mDefaultStartBoundsConfigurationSet) {
            return;
        }
        if (windowLayout == null) {
            positionCenter(task, tasks, mDefaultFreeformWidth, mDefaultFreeformHeight);
            return;
        }
        int width = getFinalWidth(windowLayout);
        int height = getFinalHeight(windowLayout);
        int verticalGravity = windowLayout.gravity & Gravity.VERTICAL_GRAVITY_MASK;
        int horizontalGravity = windowLayout.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (verticalGravity == Gravity.TOP) {
            if (horizontalGravity == Gravity.RIGHT) {
                positionTopRight(task, tasks, width, height);
            } else {
                positionTopLeft(task, tasks, width, height);
            }
        } else if (verticalGravity == Gravity.BOTTOM) {
            if (horizontalGravity == Gravity.RIGHT) {
                positionBottomRight(task, tasks, width, height);
            } else {
                positionBottomLeft(task, tasks, width, height);
            }
        } else {
            // Some fancy gravity setting that we don't support yet. We just put the activity in the
            // center.
            Slog.w(TAG, "Received unsupported gravity: " + windowLayout.gravity
                    + ", positioning in the center instead.");
            positionCenter(task, tasks, width, height);
        }
    }

    private int getFinalWidth(ActivityInfo.WindowLayout windowLayout) {
        int width = mDefaultFreeformWidth;
        if (windowLayout.width > 0) {
            width = windowLayout.width;
        }
        if (windowLayout.widthFraction > 0) {
            width = (int) (mAvailableRect.width() * windowLayout.widthFraction);
        }
        return width;
    }

    private int getFinalHeight(ActivityInfo.WindowLayout windowLayout) {
        int height = mDefaultFreeformHeight;
        if (windowLayout.height > 0) {
            height = windowLayout.height;
        }
        if (windowLayout.heightFraction > 0) {
            height = (int) (mAvailableRect.height() * windowLayout.heightFraction);
        }
        return height;
    }

    private void positionBottomLeft(TaskRecord task, ArrayList<TaskRecord> tasks, int width,
            int height) {
        mTmpProposal.set(mAvailableRect.left, mAvailableRect.bottom - height,
                mAvailableRect.left + width, mAvailableRect.bottom);
        position(task, tasks, mTmpProposal, !ALLOW_RESTART, SHIFT_POLICY_HORIZONTAL_RIGHT);
    }

    private void positionBottomRight(TaskRecord task, ArrayList<TaskRecord> tasks, int width,
            int height) {
        mTmpProposal.set(mAvailableRect.right - width, mAvailableRect.bottom - height,
                mAvailableRect.right, mAvailableRect.bottom);
        position(task, tasks, mTmpProposal, !ALLOW_RESTART, SHIFT_POLICY_HORIZONTAL_LEFT);
    }

    private void positionTopLeft(TaskRecord task, ArrayList<TaskRecord> tasks, int width,
            int height) {
        mTmpProposal.set(mAvailableRect.left, mAvailableRect.top,
                mAvailableRect.left + width, mAvailableRect.top + height);
        position(task, tasks, mTmpProposal, !ALLOW_RESTART, SHIFT_POLICY_HORIZONTAL_RIGHT);
    }

    private void positionTopRight(TaskRecord task, ArrayList<TaskRecord> tasks, int width,
            int height) {
        mTmpProposal.set(mAvailableRect.right - width, mAvailableRect.top,
                mAvailableRect.right, mAvailableRect.top + height);
        position(task, tasks, mTmpProposal, !ALLOW_RESTART, SHIFT_POLICY_HORIZONTAL_LEFT);
    }

    private void positionCenter(TaskRecord task, ArrayList<TaskRecord> tasks, int width,
            int height) {
        mTmpProposal.set(mDefaultFreeformStartX, mDefaultFreeformStartY,
                mDefaultFreeformStartX + width, mDefaultFreeformStartY + height);
        position(task, tasks, mTmpProposal, ALLOW_RESTART, SHIFT_POLICY_DIAGONAL_DOWN);
    }

    private void position(TaskRecord task, ArrayList<TaskRecord> tasks, Rect proposal,
            boolean allowRestart, int shiftPolicy) {
        mTmpOriginal.set(proposal);
        boolean restarted = false;
        while (boundsConflict(proposal, tasks)) {
            // Unfortunately there is already a task at that spot, so we need to look for some
            // other place.
            shiftStartingPoint(proposal, shiftPolicy);
            if (shiftedToFar(proposal, shiftPolicy)) {
                // We don't want the task to go outside of the stack, because it won't look
                // nice. Depending on the starting point we either restart, or immediately give up.
                if (!allowRestart) {
                    proposal.set(mTmpOriginal);
                    break;
                }
                // We must have started not from the top. Let's restart from there because there
                // might be some space there.
                proposal.set(mAvailableRect.left, mAvailableRect.top,
                        mAvailableRect.left + proposal.width(),
                        mAvailableRect.top + proposal.height());
                restarted = true;
            }
            if (restarted && (proposal.left > mDefaultFreeformStartX
                    || proposal.top > mDefaultFreeformStartY)) {
                // If we restarted and crossed the initial position, let's not struggle anymore.
                // The user already must have ton of tasks visible, we can just smack the new
                // one in the center.
                proposal.set(mTmpOriginal);
                break;
            }
        }
        task.updateOverrideConfiguration(proposal);
    }

    private boolean shiftedToFar(Rect start, int shiftPolicy) {
        switch (shiftPolicy) {
            case SHIFT_POLICY_HORIZONTAL_LEFT:
                return start.left < mAvailableRect.left;
            case SHIFT_POLICY_HORIZONTAL_RIGHT:
                return start.right > mAvailableRect.right;
            default: // SHIFT_POLICY_DIAGONAL_DOWN
                return start.right > mAvailableRect.right || start.bottom > mAvailableRect.bottom;
        }
    }

    private void shiftStartingPoint(Rect posposal, int shiftPolicy) {
        switch (shiftPolicy) {
            case SHIFT_POLICY_HORIZONTAL_LEFT:
                posposal.offset(-mDefaultFreeformStepHorizontal, 0);
                break;
            case SHIFT_POLICY_HORIZONTAL_RIGHT:
                posposal.offset(mDefaultFreeformStepHorizontal, 0);
                break;
            default: // SHIFT_POLICY_DIAGONAL_DOWN:
                posposal.offset(mDefaultFreeformStepHorizontal, mDefaultFreeformStepVertical);
                break;
        }
    }

    private static boolean boundsConflict(Rect proposal, ArrayList<TaskRecord> tasks) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            TaskRecord task = tasks.get(i);
            if (!task.mActivities.isEmpty() && task.mBounds != null) {
                Rect bounds = task.mBounds;
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

    void reset() {
        mDefaultStartBoundsConfigurationSet = false;
    }
}
