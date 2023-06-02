/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.view;

import static android.view.HapticFeedbackConstants.SCROLL_ITEM_FOCUS;
import static android.view.HapticFeedbackConstants.SCROLL_LIMIT;
import static android.view.HapticFeedbackConstants.SCROLL_TICK;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public final class HapticScrollFeedbackProviderTest {
    private static final int INPUT_DEVICE_1 = 1;
    private static final int INPUT_DEVICE_2 = 2;

    private static final int TICK_INTERVAL_PIXELS = 100;

    private TestView mView;
    private long mCurrentTimeMillis = 1000; // arbitrary starting time value

    private HapticScrollFeedbackProvider mProvider;

    @Before
    public void setUp() {
        mView = new TestView(InstrumentationRegistry.getContext());
        mProvider = new HapticScrollFeedbackProvider(mView, TICK_INTERVAL_PIXELS);
    }

    @Test
    public void testSnapToItem() {
        mProvider.onSnapToItem(createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_ITEM_FOCUS);
    }

    @Test
    public void testScrollLimit_start() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ true);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT);
    }

    @Test
    public void testScrollLimit_stop() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT);
    }

    @Test
    public void testScrollProgress_zeroTickInterval() {
        mProvider =
                new HapticScrollFeedbackProvider(
                        mView, /* rotaryEncoderAxisScrollTickIntervalPixels= */ 0);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 10);
        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 30);
        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 30);

        assertNoFeedback(mView);
    }

    @Test
    public void testScrollProgress_progressEqualsOrExceedsPositiveThreshold() {
        mProvider =
                new HapticScrollFeedbackProvider(
                        mView, /* rotaryEncoderAxisScrollTickIntervalPixels= */ 100);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 20);

        assertNoFeedback(mView);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 80);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 1);


        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 80);
        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 40);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 2);
    }

    @Test
    public void testScrollProgress_progressEqualsOrExceedsNegativeThreshold() {
        mProvider =
                new HapticScrollFeedbackProvider(
                        mView, /* rotaryEncoderAxisScrollTickIntervalPixels= */ 100);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(),
                MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -20);

        assertNoFeedback(mView);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(),
                MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -80);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 1);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(),
                MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -70);
        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(),
                MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -40);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 2);
    }

    @Test
    public void testScrollProgress_positiveAndNegativeProgresses() {
        mProvider =
                new HapticScrollFeedbackProvider(
                        mView, /* rotaryEncoderAxisScrollTickIntervalPixels= */ 100);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 20);
        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(),
                MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -90);

        assertNoFeedback(mView);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 10);

        assertNoFeedback(mView);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(),
                MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -50);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 1);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 40);
        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 50);
        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 60);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 2);
    }

    @Test
    public void testScrollProgress_singleProgressExceedsThreshold() {
        mProvider =
                new HapticScrollFeedbackProvider(
                        mView, /* rotaryEncoderAxisScrollTickIntervalPixels= */ 100);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(),
                MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 1000);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 1);
    }

    @Test
    public void testScrollLimit_startAndEndLimit_playsOnlyOneFeedback() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ true);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT);
    }

    @Test
    public void testScrollLimit_doubleStartLimit_playsOnlyOneFeedback() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ true);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ true);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT);
    }

    @Test
    public void testScrollLimit_doubleEndLimit_playsOnlyOneFeedback() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT);
    }

    @Test
    public void testScrollLimit_enabledWithProgress() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        mProvider.onScrollProgress(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 80);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT, 2);
    }

    @Test
    public void testScrollLimit_enabledWithSnap() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        mProvider.onSnapToItem(createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 2);
    }

    @Test
    public void testScrollLimit_enabledWithDissimilarSnap() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        mProvider.onSnapToItem(createTouchMoveEvent(), MotionEvent.AXIS_X);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 2);
    }

    @Test
    public void testScrollLimit_enabledWithDissimilarProgress() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        mProvider.onScrollProgress(
                createTouchMoveEvent(), MotionEvent.AXIS_SCROLL, /* deltaInPixels= */ 80);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT, 2);
    }

    @Test
    public void testScrollLimit_enabledWithDissimilarLimit() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        mProvider.onScrollLimit(createTouchMoveEvent(), MotionEvent.AXIS_SCROLL, false);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(), MotionEvent.AXIS_SCROLL, /* isStart= */ false);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT, 3);
    }

    @Test
    public void testScrollLimit_enabledWithMotionFromDifferentDeviceId() {
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(INPUT_DEVICE_1),
                MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(INPUT_DEVICE_2),
                MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        mProvider.onScrollLimit(
                createRotaryEncoderScrollEvent(INPUT_DEVICE_1),
                MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_LIMIT, 3);
    }

    private void assertNoFeedback(TestView view) {
        for (int feedback : new int[] {SCROLL_ITEM_FOCUS, SCROLL_LIMIT, SCROLL_TICK}) {
            assertFeedbackCount(view, feedback, 0);
        }
    }

    private void assertOnlyFeedback(TestView view, int expectedFeedback) {
        assertOnlyFeedback(view, expectedFeedback, /* expectedCount= */ 1);
    }

    private void assertOnlyFeedback(TestView view, int expectedFeedback, int expectedCount) {
        for (int feedback : new int[] {SCROLL_ITEM_FOCUS, SCROLL_LIMIT, SCROLL_TICK}) {
            assertFeedbackCount(view, feedback, (feedback == expectedFeedback) ? expectedCount : 0);
        }
    }

    private void assertFeedbackCount(TestView view, int feedback, int expectedCount) {
        int count = view.mFeedbackCount.getOrDefault(feedback, 0);
        assertThat(count).isEqualTo(expectedCount);
    }

    private MotionEvent createTouchMoveEvent() {
        long downTime = mCurrentTimeMillis;
        long eventTime = mCurrentTimeMillis + 2; // arbitrary increment from the down time.
        ++mCurrentTimeMillis;
        return MotionEvent.obtain(
                downTime , eventTime, MotionEvent.ACTION_MOVE, /* x= */ 3, /* y= */ 5, 0);
    }

    private MotionEvent createRotaryEncoderScrollEvent() {
        return createRotaryEncoderScrollEvent(INPUT_DEVICE_1);
    }

    private MotionEvent createRotaryEncoderScrollEvent(int deviceId) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;

        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(MotionEvent.AXIS_SCROLL, 20);

        return MotionEvent.obtain(0 /* downTime */,
                ++mCurrentTimeMillis,
                MotionEvent.ACTION_SCROLL,
                /* pointerCount= */ 1,
                new MotionEvent.PointerProperties[] {props},
                new MotionEvent.PointerCoords[] {coords},
                /* metaState= */ 0,
                /* buttonState= */ 0,
                /* xPrecision= */ 0,
                /* yPrecision= */ 0,
                deviceId,
                /* edgeFlags= */ 0,
                InputDevice.SOURCE_ROTARY_ENCODER,
                /* flags= */ 0);
    }

    private static class TestView extends View  {
        final Map<Integer, Integer> mFeedbackCount = new HashMap<>();

        TestView(Context context) {
            super(context);
        }

        @Override
        public boolean performHapticFeedback(int feedback) {
            if (!mFeedbackCount.containsKey(feedback)) {
                mFeedbackCount.put(feedback, 0);
            }
            mFeedbackCount.put(feedback, mFeedbackCount.get(feedback) + 1);
            return true;
        }
    }
}
