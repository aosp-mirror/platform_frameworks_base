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
import android.support.test.espresso.action.MotionEvents;
import android.support.test.espresso.action.PrecisionDescriber;
import android.support.test.espresso.action.Swiper;
import android.support.test.espresso.util.HumanReadables;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.hamcrest.Matcher;


/**
 * Drags on a View using touch events.<br>
 * <br>
 * View constraints:
 * <ul>
 * <li>must be displayed on screen
 * <ul>
 */
public final class DragAction implements ViewAction {
    public interface Dragger extends Swiper {
        UiController wrapUiController(UiController uiController);
    }

    /**
     * Executes different drag types to given positions.
     */
    public enum Drag implements Dragger {

        /**
         * Starts a drag with a mouse down.
         */
        MOUSE_DOWN {
            private DownMotionPerformer downMotion = new DownMotionPerformer() {
                @Override
                public MotionEvent perform(
                        UiController uiController, float[] coordinates, float[] precision) {
                    MotionEvent downEvent = MotionEvents.sendDown(
                            uiController, coordinates, precision)
                            .down;
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
                return "mouse down and drag";
            }

            @Override
            public UiController wrapUiController(UiController uiController) {
                return new MouseUiController(uiController);
            }
        },

        /**
         * Starts a drag with a mouse double click.
         */
        MOUSE_DOUBLE_CLICK {
            private DownMotionPerformer downMotion = new DownMotionPerformer() {
                @Override
                @Nullable
                public MotionEvent perform(
                        UiController uiController,  float[] coordinates, float[] precision) {
                    return performDoubleTap(uiController, coordinates, precision);
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
                return "mouse double click and drag to select";
            }

            @Override
            public UiController wrapUiController(UiController uiController) {
                return new MouseUiController(uiController);
            }
        },

        /**
         * Starts a drag with a mouse long click.
         */
        MOUSE_LONG_CLICK {
            private DownMotionPerformer downMotion = new DownMotionPerformer() {
                @Override
                public MotionEvent perform(
                        UiController uiController, float[] coordinates, float[] precision) {
                    MotionEvent downEvent = MotionEvents.sendDown(
                            uiController, coordinates, precision)
                            .down;
                    return performLongPress(uiController, coordinates, precision);
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
                return "mouse long click and drag to select";
            }

            @Override
            public UiController wrapUiController(UiController uiController) {
                return new MouseUiController(uiController);
            }
        },

        /**
         * Starts a drag with a mouse triple click.
         */
        MOUSE_TRIPLE_CLICK {
            private DownMotionPerformer downMotion = new DownMotionPerformer() {
                @Override
                @Nullable
                public MotionEvent perform(
                        UiController uiController, float[] coordinates, float[] precision) {
                    MotionEvent downEvent = MotionEvents.sendDown(
                            uiController, coordinates, precision)
                            .down;
                    for (int i = 0; i < 2; ++i) {
                        try {
                            if (!MotionEvents.sendUp(uiController, downEvent)) {
                                String logMessage = "Injection of up event as part of the triple "
                                        + "click failed. Sending cancel event.";
                                Log.d(TAG, logMessage);
                                MotionEvents.sendCancel(uiController, downEvent);
                                return null;
                            }

                            long doubleTapMinimumTimeout = ViewConfiguration.getDoubleTapMinTime();
                            uiController.loopMainThreadForAtLeast(doubleTapMinimumTimeout);
                        } finally {
                            downEvent.recycle();
                        }
                        downEvent = MotionEvents.sendDown(
                                uiController, coordinates, precision).down;
                    }
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
                return "mouse triple click and drag to select";
            }

            @Override
            public UiController wrapUiController(UiController uiController) {
                return new MouseUiController(uiController);
            }
        },

        /**
         * Starts a drag with a tap.
         */
        TAP {
            private DownMotionPerformer downMotion = new DownMotionPerformer() {
                @Override
                public MotionEvent perform(
                        UiController uiController, float[] coordinates, float[] precision) {
                    MotionEvent downEvent = MotionEvents.sendDown(
                            uiController, coordinates, precision)
                            .down;
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
                return "tap and drag";
            }
        },

        /**
         * Starts a drag with a long-press.
         */
        LONG_PRESS {
            private DownMotionPerformer downMotion = new DownMotionPerformer() {
                @Override
                public MotionEvent perform(
                        UiController uiController, float[] coordinates, float[] precision) {
                    return performLongPress(uiController, coordinates, precision);
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
                return "long press and drag";
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
                    return performDoubleTap(uiController, coordinates, precision);
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
                return "double-tap and drag";
            }
        };

        private static final String TAG = Drag.class.getSimpleName();

        /** The number of move events to send for each drag. */
        private static final int DRAG_STEP_COUNT = 10;

        /** Length of time a drag should last for, in milliseconds. */
        private static final int DRAG_DURATION = 1500;

        /** Duration between the last move event and the up event, in milliseconds. */
        private static final int WAIT_BEFORE_SENDING_UP = 400;

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

                // Wait before sending up because some drag handling logic may discard move events
                // that has been sent immediately before the up event. e.g. HandleView.
                uiController.loopMainThreadForAtLeast(WAIT_BEFORE_SENDING_UP);

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

            for (int i = 0; i < DRAG_STEP_COUNT; i++) {
                res[i][0] = start[0] + (end[0] - start[0]) * i / (DRAG_STEP_COUNT - 1f);
                res[i][1] = start[1] + (end[1] - start[1]) * i / (DRAG_STEP_COUNT - 1f);
            }

            return res;
        }

        private static MotionEvent performLongPress(
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

        @Nullable
        private static MotionEvent performDoubleTap(
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

        @Override
        public UiController wrapUiController(UiController uiController) {
            return uiController;
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

    private final Dragger mDragger;
    private final CoordinatesProvider mStartCoordinatesProvider;
    private final CoordinatesProvider mEndCoordinatesProvider;
    private final PrecisionDescriber mPrecisionDescriber;
    private final Class<? extends View> mViewClass;

    public DragAction(
            Dragger dragger,
            CoordinatesProvider startCoordinatesProvider,
            CoordinatesProvider endCoordinatesProvider,
            PrecisionDescriber precisionDescriber,
            Class<? extends View> viewClass) {
        mDragger = checkNotNull(dragger);
        mStartCoordinatesProvider = checkNotNull(startCoordinatesProvider);
        mEndCoordinatesProvider = checkNotNull(endCoordinatesProvider);
        mPrecisionDescriber = checkNotNull(precisionDescriber);
        mViewClass = viewClass;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Matcher<View> getConstraints() {
        return allOf(isCompletelyDisplayed(), isAssignableFrom(mViewClass));
    }

    @Override
    public void perform(UiController uiController, View view) {
        checkNotNull(uiController);
        checkNotNull(view);

        uiController = mDragger.wrapUiController(uiController);

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
