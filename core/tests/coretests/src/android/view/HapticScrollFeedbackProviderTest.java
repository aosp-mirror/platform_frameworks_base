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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.view.flags.FeatureFlags;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public final class HapticScrollFeedbackProviderTest {
    private static final int INPUT_DEVICE_1 = 1;
    private static final int INPUT_DEVICE_2 = 2;

    private TestView mView;

    @Mock ViewConfiguration mMockViewConfig;
    @Mock FeatureFlags mMockFeatureFlags;

    private HapticScrollFeedbackProvider mProvider;

    @Before
    public void setUp() {
        mMockViewConfig = mock(ViewConfiguration.class);
        setHapticScrollFeedbackEnabled(true);
        when(mMockViewConfig.isViewBasedRotaryEncoderHapticScrollFeedbackEnabled())
                .thenReturn(false);

        mView = new TestView(InstrumentationRegistry.getContext());
        mProvider = new HapticScrollFeedbackProvider(mView, mMockViewConfig,
                /* disabledIfViewPlaysScrollHaptics= */ true);
    }

    @Test
    public void testRotaryEncoder_noFeedbackWhenViewBasedFeedbackIsEnabled() {
        when(mMockViewConfig.isViewBasedRotaryEncoderHapticScrollFeedbackEnabled())
                .thenReturn(true);
        setHapticScrollTickInterval(5);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 10);
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);

        assertNoFeedback(mView);
    }

    @Test
    public void testRotaryEncoder_feedbackWhenDisregardingViewBasedScrollHaptics() {
        mProvider = new HapticScrollFeedbackProvider(mView, mMockViewConfig,
                /* disabledIfViewPlaysScrollHaptics= */ false);
        when(mMockViewConfig.isViewBasedRotaryEncoderHapticScrollFeedbackEnabled())
                .thenReturn(true);
        setHapticScrollTickInterval(5);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 10);
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);

        assertFeedbackCount(mView, SCROLL_TICK, 1);
        assertFeedbackCount(mView, SCROLL_ITEM_FOCUS, 1);
        assertFeedbackCount(mView, SCROLL_LIMIT, 1);
    }

    @Test
    public void testNoFeedbackWhenFeedbackIsDisabled() {
        setHapticScrollFeedbackEnabled(false);
        // Call different types scroll feedback methods; non of them should produce feedback because
        // feedback has been disabled.
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 300);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -300);

        assertNoFeedback(mView);
    }

    @Test
    public void testSnapToItem() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_ITEM_FOCUS);
    }

    @Test
    public void testScrollLimit_start() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);

        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 1);
    }

    @Test
    public void testScrollLimit_stop() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);

        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 1);
    }

    @Test
    public void testScrollProgress_zeroTickInterval() {
        setHapticScrollTickInterval(0);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 30);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 20);

        assertNoFeedback(mView);
    }

    @Test
    public void testScrollProgress_progressEqualsOrExceedsPositiveThreshold() {
        setHapticScrollTickInterval(100);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 20);

        assertNoFeedback(mView);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 80);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 1);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 120);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 2);
    }

    @Test
    public void testScrollProgress_progressEqualsOrExceedsNegativeThreshold() {
        setHapticScrollTickInterval(100);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -20);

        assertNoFeedback(mView);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -80);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 1);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -70);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -40);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 2);
    }

    @Test
    public void testScrollProgress_positiveAndNegativeProgresses() {
        setHapticScrollTickInterval(100);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 20);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -90);

        assertNoFeedback(mView);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 10);

        assertNoFeedback(mView);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -50);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 1);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 40);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 50);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 60);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 2);
    }

    @Test
    public void testScrollProgress_singleProgressExceedsThreshold() {
        setHapticScrollTickInterval(100);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 1000);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_TICK, 1);
    }

    @Test
    public void testScrollLimit_startAndEndLimit_playsOnlyOneFeedback() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);

        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 1);
    }

    @Test
    public void testScrollLimit_doubleStartLimit_playsOnlyOneFeedback() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);

        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 1);
    }

    @Test
    public void testScrollLimit_doubleEndLimit_playsOnlyOneFeedback() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);

        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 1);
    }

    @Test
    public void testScrollLimit_notEnabledWithZeroProgress() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 0);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 1);
    }

    @Test
    public void testScrollLimit_enabledWithProgress() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 80);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 2);
    }

    @Test
    public void testScrollLimit_enabledWithSnap() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 2);
    }

    @Test
    public void testScrollLimit_notEnabledWithDissimilarSnap() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_X);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 1);
    }

    @Test
    public void testScrollLimit_enabledWithDissimilarProgress() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 80);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 2);
    }


    @Test
    public void testScrollLimit_doesNotEnabledWithMotionFromDifferentDeviceId() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        mProvider.onSnapToItem(
                INPUT_DEVICE_2, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1,
                InputDevice.SOURCE_ROTARY_ENCODER,
                MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_LIMIT, 1);
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

    private void setHapticScrollTickInterval(int interval) {
        when(mMockViewConfig.getHapticScrollFeedbackTickInterval(anyInt(), anyInt(), anyInt()))
                .thenReturn(interval);
    }

    private void setHapticScrollFeedbackEnabled(boolean enabled) {
        when(mMockViewConfig.isHapticScrollFeedbackEnabled(anyInt(), anyInt(), anyInt()))
                .thenReturn(enabled);
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