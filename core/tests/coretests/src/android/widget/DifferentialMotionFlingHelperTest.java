/*
 * Copyright 2023 The Android Open Source Project
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

package android.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.view.MotionEvent;
import android.widget.flags.FakeFeatureFlagsImpl;
import android.widget.flags.Flags;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DifferentialMotionFlingHelperTest {
    private int mMinVelocity = 0;
    private int mMaxVelocity = Integer.MAX_VALUE;
    /** A fake velocity value that's going to be returned from the velocity provider. */
    private float mVelocity;
    private boolean mVelocityCalculated;

    private final DifferentialMotionFlingHelper.DifferentialVelocityProvider mVelocityProvider =
            (vt, event, axis) -> {
                mVelocityCalculated = true;
                return mVelocity;
            };

    private final DifferentialMotionFlingHelper.FlingVelocityThresholdCalculator
            mVelocityThresholdCalculator =
                    (ctx, buffer, event, axis) -> {
                        buffer[0] = mMinVelocity;
                        buffer[1] = mMaxVelocity;
                    };

    private final TestDifferentialMotionFlingTarget mFlingTarget =
            new TestDifferentialMotionFlingTarget();

    private final FakeFeatureFlagsImpl mFakeWidgetFeatureFlags = new FakeFeatureFlagsImpl();

    private DifferentialMotionFlingHelper mFlingHelper;

    @Before
    public void setUp() throws Exception {
        mFlingHelper = new DifferentialMotionFlingHelper(
                ApplicationProvider.getApplicationContext(),
                mFlingTarget,
                mVelocityThresholdCalculator,
                mVelocityProvider,
                mFakeWidgetFeatureFlags);
        mFakeWidgetFeatureFlags.setFlag(
                Flags.FLAG_ENABLE_PLATFORM_WIDGET_DIFFERENTIAL_MOTION_FLING, true);
    }

    @Test
    public void deviceDoesNotSupportFling_noVelocityCalculated() {
        mMinVelocity = Integer.MAX_VALUE;
        mMaxVelocity = Integer.MIN_VALUE;

        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, 60);

        assertFalse(mVelocityCalculated);
    }

    @Test
    public void flingVelocityOppositeToPrevious_stopsOngoingFling() {
        deliverEventWithVelocity(createRotaryEncoderEvent(), MotionEvent.AXIS_SCROLL, 50);
        deliverEventWithVelocity(createRotaryEncoderEvent(), MotionEvent.AXIS_SCROLL, -10);

        // One stop on the initial event, and second stop due to opposite velocities.
        assertEquals(2, mFlingTarget.mNumStops);
    }

    @Test
    public void flingParamsChanged_stopsOngoingFling() {
        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, 50);
        deliverEventWithVelocity(createRotaryEncoderEvent(), MotionEvent.AXIS_SCROLL, 10);

        // One stop on the initial event, and second stop due to changed axis/source.
        assertEquals(2, mFlingTarget.mNumStops);
    }

    @Test
    public void positiveFlingVelocityTooLow_doesNotGenerateFling() {
        mMinVelocity = 50;
        mMaxVelocity = 100;
        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, 20);

        assertEquals(0, mFlingTarget.mLastFlingVelocity, /* delta= */ 0);
    }

    @Test
    public void negativeFlingVelocityTooLow_doesNotGenerateFling() {
        mMinVelocity = 50;
        mMaxVelocity = 100;
        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, -20);

        assertEquals(0, mFlingTarget.mLastFlingVelocity, /* delta= */ 0);
    }

    @Test
    public void positiveFlingVelocityAboveMinimum_generateFlings() {
        mMinVelocity = 50;
        mMaxVelocity = 100;
        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, 60);

        assertEquals(60, mFlingTarget.mLastFlingVelocity, /* delta= */ 0);
    }

    @Test
    public void negativeFlingVelocityAboveMinimum_generateFlings() {
        mMinVelocity = 50;
        mMaxVelocity = 100;
        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, -60);

        assertEquals(-60, mFlingTarget.mLastFlingVelocity, /* delta= */ 0);
    }

    @Test
    public void positiveFlingVelocityAboveMaximum_velocityClamped() {
        mMinVelocity = 50;
        mMaxVelocity = 100;
        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, 3000);

        assertEquals(100, mFlingTarget.mLastFlingVelocity, /* delta= */ 0);
    }

    @Test
    public void flingFeatureFlagDisabled_noFlingCalculation() {
        mFakeWidgetFeatureFlags.setFlag(
                Flags.FLAG_ENABLE_PLATFORM_WIDGET_DIFFERENTIAL_MOTION_FLING, false);
        mMinVelocity = 50;
        mMaxVelocity = 100;
        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, 60);

        assertFalse(mVelocityCalculated);
        assertEquals(0, mFlingTarget.mLastFlingVelocity, /* delta= */ 0);
    }

    @Test
    public void negativeFlingVelocityAboveMaximum_velocityClamped() {
        mMinVelocity = 50;
        mMaxVelocity = 100;
        deliverEventWithVelocity(createPointerEvent(), MotionEvent.AXIS_VSCROLL, -3000);

        assertEquals(-100, mFlingTarget.mLastFlingVelocity, /* delta= */ 0);
    }

    private MotionEvent createRotaryEncoderEvent() {
        return MotionEventUtils.createRotaryEvent(-2);
    }

    private MotionEvent createPointerEvent() {
        return MotionEventUtils.createGenericPointerEvent(/* hScroll= */ 0, /* vScroll= */ -1);

    }

    private void deliverEventWithVelocity(MotionEvent ev, int axis, float velocity) {
        mVelocity = velocity;
        mFlingHelper.onMotionEvent(ev, axis);
        ev.recycle();
    }

    private static class TestDifferentialMotionFlingTarget
            implements DifferentialMotionFlingHelper.DifferentialMotionFlingTarget {
        float mLastFlingVelocity = 0;
        int mNumStops = 0;

        @Override
        public boolean startDifferentialMotionFling(float velocity) {
            mLastFlingVelocity = velocity;
            return true;
        }

        @Override
        public void stopDifferentialMotionFling() {
            mNumStops++;
        }

        @Override
        public float getScaledScrollFactor() {
            return 1;
        }
    }
}
