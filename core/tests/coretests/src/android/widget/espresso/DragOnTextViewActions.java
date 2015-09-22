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
 * limitations under the License
 */

package android.widget.espresso;

import static android.support.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static android.support.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static com.android.internal.util.Preconditions.checkNotNull;
import static org.hamcrest.Matchers.allOf;

import android.annotation.Nullable;
import android.os.SystemClock;
import android.support.test.espresso.UiController;
import android.support.test.espresso.PerformException;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.action.CoordinatesProvider;
import android.support.test.espresso.action.GeneralClickAction;
import android.support.test.espresso.action.MotionEvents;
import android.support.test.espresso.action.PrecisionDescriber;
import android.support.test.espresso.action.Press;
import android.support.test.espresso.action.Swiper;
import android.support.test.espresso.action.Tap;
import android.support.test.espresso.util.HumanReadables;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;
import org.hamcrest.Matcher;


/**
 * Drags on text in a TextView using touch events.<br>
 * <br>
 * View constraints:
 * <ul>
 * <li>must be a TextView displayed on screen
 * <ul>
 */
public final class DragOnTextViewActions implements ViewAction {

    /**
     * Executes different "drag on text" types to given positions.
     */
    public enum Drag implements Swiper {

        /**
         * Starts a drag with a long-press.
         */
        LONG_PRESS {
            private DownMotionPerformer downMotion = new DownMotionPerformer() {
                @Override
                public MotionEvent perform(
                        UiController uiController, float[] coordinates, float[] precision) {
                    MotionEvent downEvent = MotionEvents.sendDown(
                            uiController, coordinates, precision)
                            .down;
                    // Duration before a press turns into a long press.
                    // Factor 1.5 is needed, otherwise a long press is not safely detected.
                    // See android.test.TouchUtils longClickView
                    long longPressTimeout = (long) (ViewConfiguration.getLongPressTimeout() * 1.5f);
                    uiController.loopMainThreadForAtLeast(longPressTimeout);
                    return downEvent;
                }
            };

            @Override
            public Status sendSwipe(
                    UiController uiController,
                    float[] startCoordinates, float[] endCoordinates, float[] precision) {
                return sendLinearDrag(
                        uiController, downMotion, startCoordinates, endCoordinates, precision);
            }

            @Override
            public String toString() {
                return "long press and drag to select";
            }
        },

        /**
         * Starts a drag with a double-tap.
         */
        DOUBLE_TAP {
            private DownMotionPerformer downMotion = new DownMotionPerformer() {
                @Override
                @Nullable
                public MotionEvent perform(
                        UiController uiController,  float[] coordinates, float[] precision) {
                    MotionEvent downEvent = MotionEvents.sendDown(
                            uiController, coordinates, precision)
                            .down;
                    try {
                        if (!MotionEvents.sendUp(uiController, downEvent)) {
                            String logMessage = "Injection of up event as part of the double tap " +
                                    "failed. Sending cancel event.";
                            Log.d(TAG, logMessage);
                            MotionEvents.sendCancel(uiController, downEvent);
                            return null;
                        }

                        long doubleTapMinimumTimeout = ViewConfiguration.getDoubleTapMinTime();
                        uiController.loopMainThreadForAtLeast(doubleTapMinimumTimeout);

                        return MotionEvents.sendDown(uiController, coordinates, precision).down;
                    } finally {
                        downEvent.recycle();
                    }
                }
            };

            @Override
            public Status sendSwipe(
                    UiController uiController,
                    float[] startCoordinates, float[] endCoordinates, float[] precision) {
                return sendLinearDrag(
                        uiController, downMotion, startCoordinates, endCoordinates, precision);
            }

            @Override
            public String toString() {
                return "double-tap and drag to select";
            }
        };

        private static final String TAG = Drag.class.getSimpleName();

        /** The number of move events to send for each drag. */
        private static final int DRAG_STEP_COUNT = 10;

        /** Length of time a drag should last for, in milliseconds. */
        private static final int DRAG_DURATION = 1500;

        private static Status sendLinearDrag(
                UiController uiController, DownMotionPerformer downMotion,
                float[] startCoordinates, float[] endCoordinates, float[] precision) {
            float[][] steps = interpolate(startCoordinates, endCoordinates);
            final int delayBetweenMovements = DRAG_DURATION / steps.length;

            MotionEvent downEvent = downMotion.perform(uiController, startCoordinates, precision);
            if (downEvent == null) {
                return Status.FAILURE;
            }

            try {
                for (int i = 0; i < steps.length; i++) {
                    if (!MotionEvents.sendMovement(uiController, downEvent, steps[i])) {
                        String logMessage = "Injection of move event as part of the drag failed. " +
                                "Sending cancel event.";
                        Log.e(TAG, logMessage);
                        MotionEvents.sendCancel(uiController, downEvent);
                        return Status.FAILURE;
                    }

                    long desiredTime = downEvent.getDownTime() + delayBetweenMovements * i;
                    long timeUntilDesired = desiredTime - SystemClock.uptimeMillis();
                    if (timeUntilDesired > 10) {
                        // If the wait time until the next event isn't long enough, skip the wait
                        // and execute the next event.
                        uiController.loopMainThreadForAtLeast(timeUntilDesired);
                    }
                }

                if (!MotionEvents.sendUp(uiController, downEvent, endCoordinates)) {
                    String logMessage = "Injection of up event as part of the drag failed. " +
                            "Sending cancel event.";
                    Log.e(TAG, logMessage);
                    MotionEvents.sendCancel(uiController, downEvent);
                    return Status.FAILURE;
                }
            } finally {
                downEvent.recycle();
            }
            return Status.SUCCESS;
        }

        private static float[][] interpolate(float[] start, float[] end) {
            float[][] res = new float[DRAG_STEP_COUNT][2];

            for (int i = 1; i < DRAG_STEP_COUNT + 1; i++) {
                res[i - 1][0] = start[0] + (end[0] - start[0]) * i / (DRAG_STEP_COUNT + 2f);
                res[i - 1][1] = start[1] + (end[1] - start[1]) * i / (DRAG_STEP_COUNT + 2f);
            }

            return res;
        }
    }

    /**
     * Interface to implement different "down motion" types.
     */
    private interface DownMotionPerformer {
        /**
         * Performs and returns a down motion.
         *
         * @param uiController a UiController to use to send MotionEvents to the screen.
         * @param coordinates a float[] with x and y values of center of the tap.
         * @param precision  a float[] with x and y values of precision of the tap.
         * @return the down motion event or null if the down motion event failed.
         */
        @Nullable
        MotionEvent perform(UiController uiController, float[] coordinates, float[] precision);
    }

    private final Swiper mDragger;
    private final CoordinatesProvider mStartCoordinatesProvider;
    private final CoordinatesProvider mEndCoordinatesProvider;
    private final PrecisionDescriber mPrecisionDescriber;

    public DragOnTextViewActions(
            Swiper dragger,
            CoordinatesProvider startCoordinatesProvider,
            CoordinatesProvider endCoordinatesProvider,
            PrecisionDescriber precisionDescriber) {
        mDragger = checkNotNull(dragger);
        mStartCoordinatesProvider = checkNotNull(startCoordinatesProvider);
        mEndCoordinatesProvider = checkNotNull(endCoordinatesProvider);
        mPrecisionDescriber = checkNotNull(precisionDescriber);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Matcher<View> getConstraints() {
        return allOf(isCompletelyDisplayed(), isAssignableFrom(TextView.class));
    }

    @Override
    public void perform(UiController uiController, View view) {
        checkNotNull(uiController);
        checkNotNull(view);

        float[] startCoordinates = mStartCoordinatesProvider.calculateCoordinates(view);
        float[] endCoordinates = mEndCoordinatesProvider.calculateCoordinates(view);
        float[] precision = mPrecisionDescriber.describePrecision();

        Swiper.Status status;

        try {
            status = mDragger.sendSwipe(
                    uiController, startCoordinates, endCoordinates, precision);
        } catch (RuntimeException re) {
            throw new PerformException.Builder()
                    .withActionDescription(this.getDescription())
                    .withViewDescription(HumanReadables.describe(view))
                    .withCause(re)
                    .build();
        }

        int duration = ViewConfiguration.getPressedStateDuration();
        // ensures that all work enqueued to process the swipe has been run.
        if (duration > 0) {
            uiController.loopMainThreadForAtLeast(duration);
        }

        if (status == Swiper.Status.FAILURE) {
            throw new PerformException.Builder()
                    .withActionDescription(getDescription())
                    .withViewDescription(HumanReadables.describe(view))
                    .withCause(new RuntimeException(getDescription() + " failed"))
                    .build();
        }
    }

    @Override
    public String getDescription() {
        return mDragger.toString();
    }
}
