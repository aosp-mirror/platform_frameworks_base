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

import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;

import java.util.ArrayList;

/**
 * Determines where a launching task should be positioned and sized on the display.
 */
class LaunchingTaskPositioner {
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

    private boolean mDefaultStartBoundsConfigurationSet = false;
    private final Rect mAvailableRect = new Rect();
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
     */
    void updateDefaultBounds(TaskRecord task, ArrayList<TaskRecord> tasks) {
        if (!mDefaultStartBoundsConfigurationSet) {
            return;
        }
        int startX = mDefaultFreeformStartX;
        int startY = mDefaultFreeformStartY;
        final int right = mAvailableRect.right;
        final int bottom = mAvailableRect.bottom;
        boolean restarted = false;
        while (boundsConflict(startX, startY, tasks)) {
            // Unfortunately there is already a task at that spot, so we need to look for some
            // other place.
            startX += mDefaultFreeformStepHorizontal;
            startY += mDefaultFreeformStepVertical;
            if (startX + mDefaultFreeformWidth > right
                    || startY + mDefaultFreeformHeight > bottom) {
                // We don't want the task to go outside of the display, because it won't look
                // nice. Let's restart from the top instead, because there should be some space
                // there.
                startX = mAvailableRect.left;
                startY = mAvailableRect.top;
                restarted = true;
            }
            if (restarted
                    && (startX > mDefaultFreeformStartX || startY > mDefaultFreeformStartY)) {
                // If we restarted and crossed the initial position, let's not struggle anymore.
                // The user already must have ton of tasks visible, we can just smack the new
                // one in the center.
                startX = mDefaultFreeformStartX;
                startY = mDefaultFreeformStartY;
                break;
            }
        }
        task.setInitialBounds(startX, startY, startX + mDefaultFreeformWidth,
                startY + mDefaultFreeformHeight);
    }

    private boolean boundsConflict(int startX, int startY, ArrayList<TaskRecord> tasks) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            TaskRecord task = tasks.get(i);
            if (!task.mActivities.isEmpty()) {
                Rect bounds = task.mBounds;
                if (bounds != null && (Math.abs(bounds.left - startX) < BOUNDS_CONFLICT_MIN_DISTANCE
                        || Math.abs(bounds.top - startY) < BOUNDS_CONFLICT_MIN_DISTANCE)) {
                    return true;
                }
            }
        }
        return false;
    }

    void reset() {
        mDefaultStartBoundsConfigurationSet = false;
    }
}
