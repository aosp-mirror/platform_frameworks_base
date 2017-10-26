/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import android.annotation.IntDef;
import android.app.ActivityOptions;
import android.content.pm.ActivityInfo.WindowLayout;
import android.graphics.Rect;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import static com.android.server.am.LaunchingBoundsController.LaunchingBoundsPositioner.RESULT_CONTINUE;
import static com.android.server.am.LaunchingBoundsController.LaunchingBoundsPositioner.RESULT_DONE;
import static com.android.server.am.LaunchingBoundsController.LaunchingBoundsPositioner.RESULT_SKIP;

/**
 * {@link LaunchingBoundsController} calculates the launch bounds by coordinating between registered
 * {@link LaunchingBoundsPositioner}.
 */
class LaunchingBoundsController {
    private final List<LaunchingBoundsPositioner> mPositioners = new ArrayList<>();

    // Temporary {@link Rect} for calculations. This is kept separate from {@code mTmpCurrent} and
    // {@code mTmpResult} to prevent clobbering values.
    private final Rect mTmpRect = new Rect();

    private final Rect mTmpCurrent = new Rect();
    private final Rect mTmpResult = new Rect();

    /**
     * Creates a {@link LaunchingBoundsController} with default registered
     * {@link LaunchingBoundsPositioner}s.
     */
    void registerDefaultPositioners(ActivityStackSupervisor supervisor) {
        // {@link LaunchingTaskPositioner} handles window layout preferences.
        registerPositioner(new LaunchingTaskPositioner());

        // {@link LaunchingActivityPositioner} is the most specific positioner and thus should be
        // registered last (applied first) out of the defaults.
        registerPositioner(new LaunchingActivityPositioner(supervisor));
    }

    /**
     * Returns the position calculated by the registered positioners
     * @param task      The {@link TaskRecord} currently being positioned.
     * @param layout    The specified {@link WindowLayout}.
     * @param activity  The {@link ActivityRecord} currently being positioned.
     * @param source    The {@link ActivityRecord} from which activity was started from.
     * @param options   The {@link ActivityOptions} specified for the activity.
     * @param result    The resulting bounds. If no bounds are set, {@link Rect#isEmpty()} will be
     *                  {@code true}.
     */
    void calculateBounds(TaskRecord task, WindowLayout layout, ActivityRecord activity,
            ActivityRecord source, ActivityOptions options, Rect result) {
        result.setEmpty();

        // We start at the last registered {@link LaunchingBoundsPositioner} as this represents
        // The positioner closest to the product level. Moving back through the list moves closer to
        // the platform logic.
        for (int i = mPositioners.size() - 1; i >= 0; --i) {
            mTmpResult.setEmpty();
            mTmpCurrent.set(result);
            final LaunchingBoundsPositioner positioner = mPositioners.get(i);

            switch(positioner.onCalculateBounds(task, layout, activity, source, options,
                    mTmpCurrent, mTmpResult)) {
                case RESULT_SKIP:
                    // Do not apply any results when we are told to skip
                    continue;
                case RESULT_DONE:
                    // Set result and return immediately.
                    result.set(mTmpResult);
                    return;
                case RESULT_CONTINUE:
                    // Set result and continue
                    result.set(mTmpResult);
                    break;
            }
        }
    }

    /**
     * A convenience method for laying out a task.
     * @return {@code true} if bounds were set on the task. {@code false} otherwise.
     */
    boolean layoutTask(TaskRecord task, WindowLayout layout) {
        calculateBounds(task, layout, null /*activity*/, null /*source*/, null /*options*/,
                mTmpRect);

        if (mTmpRect.isEmpty()) {
            return false;
        }

        task.updateOverrideConfiguration(mTmpRect);

        return true;
    }

    /**
     * Adds a positioner to participate in future bounds calculation. Note that the last registered
     * {@link LaunchingBoundsPositioner} will be the first to calculate the bounds.
     */
    void registerPositioner(LaunchingBoundsPositioner positioner) {
        if (mPositioners.contains(positioner)) {
            return;
        }

        mPositioners.add(positioner);
    }

    /**
     * An interface implemented by those wanting to participate in bounds calculation.
     */
    interface LaunchingBoundsPositioner {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({RESULT_SKIP, RESULT_DONE, RESULT_CONTINUE})
        @interface Result {}

        // Returned when the positioner does not want to influence the bounds calculation
        int RESULT_SKIP = 0;
        // Returned when the positioner has changed the bounds and would like its results to be the
        // final bounds applied.
        int RESULT_DONE = 1;
        // Returned when the positioner has changed the bounds but is okay with other positioners
        // influencing the bounds.
        int RESULT_CONTINUE = 2;

        /**
         * Called when asked to calculate bounds.
         * @param task      The {@link TaskRecord} currently being positioned.
         * @param layout    The specified {@link WindowLayout}.
         * @param activity  The {@link ActivityRecord} currently being positioned.
         * @param source    The {@link ActivityRecord} activity was started from.
         * @param options   The {@link ActivityOptions} specified for the activity.
         * @param current   The current bounds. This can differ from the initial bounds as it
         *                  represents the modified bounds up to this point.
         * @param result    The {@link Rect} which the positioner should return its modified bounds.
         *                  Any merging of the current bounds should be already applied to this
         *                  value as well before returning.
         * @return          A {@link Result} representing the result of the bounds calculation.
         */
        @Result
        int onCalculateBounds(TaskRecord task, WindowLayout layout, ActivityRecord activity,
                ActivityRecord source, ActivityOptions options, Rect current, Rect result);
    }
}
