/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.view;

import static android.support.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static android.support.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;

import static com.google.common.base.Preconditions.checkNotNull;

import static org.hamcrest.Matchers.allOf;

import android.os.SystemClock;
import android.support.test.espresso.InjectEventSecurityException;
import android.support.test.espresso.PerformException;
import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.action.Swiper;
import android.support.test.espresso.util.HumanReadables;

import org.hamcrest.Matcher;

/**
 * Pinch and zooms on a View using touch events.
 * <br>
 * View constraints:
 * <ul>
 * <li>must be displayed on screen
 * <ul>
 */
public class PinchZoomAction implements ViewAction {
    public static Swiper.Status sendPinchZoomAction(UiController uiController,
                                                    float[] firstFingerStartCoords,
                                                    float[] firstFingerEndCoords,
                                                    float[] secondFingerStartCoords,
                                                    float[] secondFingerEndCoords,
                                                    float[] precision) {
        checkNotNull(uiController);
        checkNotNull(firstFingerStartCoords);
        checkNotNull(firstFingerEndCoords);
        checkNotNull(secondFingerStartCoords);
        checkNotNull(secondFingerEndCoords);
        checkNotNull(precision);

        // Specify the touch properties for the finger events.
        final MotionEvent.PointerProperties pp1 = new MotionEvent.PointerProperties();
        pp1.id = 0;
        pp1.toolType = MotionEvent.TOOL_TYPE_FINGER;
        final MotionEvent.PointerProperties pp2 = new MotionEvent.PointerProperties();
        pp2.id = 1;
        pp2.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerProperties[] pointerProperties =
                new MotionEvent.PointerProperties[]{pp1, pp2};

        // Specify the motion properties of the two touch points.
        final MotionEvent.PointerCoords pc1 = new MotionEvent.PointerCoords();
        pc1.x = firstFingerStartCoords[0];
        pc1.y = firstFingerStartCoords[1];
        pc1.pressure = 1;
        pc1.size = 1;
        final MotionEvent.PointerCoords pc2 = new MotionEvent.PointerCoords();
        pc2.x = secondFingerStartCoords[0];
        pc2.y = secondFingerEndCoords[1];
        pc2.pressure = 1;
        pc2.size = 1;

        final long startTime = SystemClock.uptimeMillis();
        long eventTime = startTime;
        final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[]{pc1, pc2};

        final MotionEvent firstFingerEvent = MotionEvent.obtain(startTime,
                eventTime, MotionEvent.ACTION_DOWN, 1, pointerProperties, pointerCoords,
                0, 0, 1, 1, 0, 0, 0, 0);

        eventTime = SystemClock.uptimeMillis();
        final MotionEvent secondFingerEvent = MotionEvent.obtain(startTime, eventTime,
                MotionEvent.ACTION_POINTER_DOWN +
                        (pp2.id << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, pointerProperties, pointerCoords, 0, 0, 1, 1, 0, 0, 0, 0);

        try {
            uiController.injectMotionEvent(firstFingerEvent);
        } catch (InjectEventSecurityException e) {
            throw new PerformException.Builder()
                    .withActionDescription("First finger down event")
                    .withViewDescription("Scale gesture detector")
                    .withCause(e)
                    .build();
        }

        try {
            uiController.injectMotionEvent(secondFingerEvent);
        } catch (InjectEventSecurityException e) {
            throw new PerformException.Builder()
                    .withActionDescription("Second finger down event")
                    .withViewDescription("Scale gesture detector")
                    .withCause(e)
                    .build();
        }

        // Specify the coordinates of the two touch points.
        final float[][] stepsFirstFinger = interpolate(firstFingerStartCoords,
                firstFingerEndCoords);
        final float[][] stepsSecondFinger = interpolate(secondFingerStartCoords,
                secondFingerEndCoords);

        // Loop until the end points of the two fingers are reached.
        for (int i = 0; i < PINCH_STEP_COUNT; i++) {
            eventTime = SystemClock.uptimeMillis();

            pc1.x = stepsFirstFinger[i][0];
            pc1.y = stepsFirstFinger[i][1];
            pc2.x = stepsSecondFinger[i][0];
            pc2.y = stepsSecondFinger[i][1];

            final MotionEvent event = MotionEvent.obtain(startTime, eventTime,
                    MotionEvent.ACTION_MOVE, 2, pointerProperties, pointerCoords,
                    0, 0, 1, 1, 0, 0, 0, 0);

            try {
                uiController.injectMotionEvent(event);
            } catch (InjectEventSecurityException e) {
                throw new PerformException.Builder()
                        .withActionDescription("Move event")
                        .withViewDescription("Scale gesture event")
                        .withCause(e)
                        .build();
            }

           uiController.loopMainThreadForAtLeast(800);
        }

        eventTime = SystemClock.uptimeMillis();

        // Send the up event for the second finger.
        final MotionEvent secondFingerUpEvent = MotionEvent.obtain(startTime, eventTime,
                MotionEvent.ACTION_POINTER_UP, 2, pointerProperties, pointerCoords,
                0, 0, 1, 1, 0, 0, 0, 0);
        try {
            uiController.injectMotionEvent(secondFingerUpEvent);
        } catch (InjectEventSecurityException e) {
            throw new PerformException.Builder()
                    .withActionDescription("Second finger up event")
                    .withViewDescription("Scale gesture detector")
                    .withCause(e)
                    .build();
        }

        eventTime = SystemClock.uptimeMillis();
        // Send the up event for the first finger.
        final MotionEvent firstFingerUpEvent = MotionEvent.obtain(startTime, eventTime,
                MotionEvent.ACTION_POINTER_UP, 1, pointerProperties, pointerCoords,
                0, 0, 1, 1, 0, 0, 0, 0);
        try {
            uiController.injectMotionEvent(firstFingerUpEvent);
        } catch (InjectEventSecurityException e) {
            throw new PerformException.Builder()
                    .withActionDescription("First finger up event")
                    .withViewDescription("Scale gesture detector")
                    .withCause(e)
                    .build();
        }
        return Swiper.Status.SUCCESS;
    }

    private static float[][] interpolate(float[] start, float[] end) {
        float[][] res = new float[PINCH_STEP_COUNT][2];

        for (int i = 0; i < PINCH_STEP_COUNT; i++) {
            res[i][0] = start[0] + (end[0] - start[0]) * i / (PINCH_STEP_COUNT - 1f);
            res[i][1] = start[1] + (end[1] - start[1]) * i / (PINCH_STEP_COUNT - 1f);
        }

        return res;
    }

    /** The number of move events to send for each pinch. */
    private static final int PINCH_STEP_COUNT = 10;

    private final Class<? extends View> mViewClass;
    private final float[] mFirstFingerStartCoords;
    private final float[] mFirstFingerEndCoords;
    private final float[] mSecondFingerStartCoords;
    private final float[] mSecondFingerEndCoords;

    public PinchZoomAction(float[] firstFingerStartCoords,
                           float[] firstFingerEndCoords,
                           float[] secondFingerStartCoords,
                           float[] secondFingerEndCoords,
                           Class<? extends View> viewClass) {
        mFirstFingerStartCoords = firstFingerStartCoords;
        mFirstFingerEndCoords = firstFingerEndCoords;
        mSecondFingerStartCoords = secondFingerStartCoords;
        mSecondFingerEndCoords = secondFingerEndCoords;
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
        Swiper.Status status;
        final float[] precision = {1.0f, 1.0f, 1.0f, 1.0f};

        try {
            status = sendPinchZoomAction(uiController, this.mFirstFingerStartCoords,
                this.mFirstFingerEndCoords, this.mSecondFingerStartCoords,
                this.mSecondFingerEndCoords, precision);
        } catch (RuntimeException re) {
            throw new PerformException.Builder()
                    .withActionDescription(getDescription())
                    .withViewDescription(HumanReadables.describe(view))
                    .withCause(re)
                    .build();
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
        return "Pinch Zoom Action";
    }
}
