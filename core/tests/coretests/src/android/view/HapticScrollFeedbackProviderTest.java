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

import static android.os.vibrator.Flags.FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED;
import static android.view.HapticFeedbackConstants.SCROLL_ITEM_FOCUS;
import static android.view.HapticFeedbackConstants.SCROLL_LIMIT;
import static android.view.HapticFeedbackConstants.SCROLL_TICK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.flags.FeatureFlags;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// TODO(b/353625893): update old tests to use new infra like those with "inputDeviceCustomized".
@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public final class HapticScrollFeedbackProviderTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
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
        mSetFlagsRule.disableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
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
    public void testRotaryEncoder_inputDeviceCustomized_noFeedbackWhenViewBasedFeedbackIsEnabled() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);

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

        assertThat(mView.mHapticFeedbackRequests).hasSize(0);
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
    public void testRotaryEncoder_inputDeviceCustomized_feedbackWhenDisregardingViewBasedScrollHaptics() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider = new HapticScrollFeedbackProvider(mView, mMockViewConfig,
                /* disabledIfViewPlaysScrollHaptics= */ false);
        when(mMockViewConfig.isViewBasedRotaryEncoderHapticScrollFeedbackEnabled())
                .thenReturn(true);
        setHapticScrollTickInterval(5);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 10);
        requests.add(new HapticFeedbackRequest(
                SCROLL_TICK, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testNoFeedbackWhenFeedbackIsDisabled_inputDeviceCustomized() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);

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

        assertThat(mView.mHapticFeedbackRequests).hasSize(0);
    }

    @Test
    public void testSnapToItem() {
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);

        assertOnlyFeedback(mView, HapticFeedbackConstants.SCROLL_ITEM_FOCUS);
    }

    @Test
    public void testSnapToItem_inputDeviceCustomized() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                    SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        mProvider.onSnapToItem(
                INPUT_DEVICE_2, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.AXIS_SCROLL);
        requests.add(
                new HapticFeedbackRequest(
                        SCROLL_ITEM_FOCUS, INPUT_DEVICE_2, InputDevice.SOURCE_TOUCHSCREEN));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_inputDeviceCustomized_start() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_inputDeviceCustomized_stop() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollProgress_inputDeviceCustomized_zeroTickInterval() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);

        setHapticScrollTickInterval(0);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 30);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 20);

        assertThat(mView.mHapticFeedbackRequests).hasSize(0);
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
    public void testScrollProgress_inputDeviceCustomized_progressEqualsOrExceedsPositiveThreshold() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();
        setHapticScrollTickInterval(100);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 20);

        assertThat(mView.mHapticFeedbackRequests).hasSize(0);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 80);
        requests.add(new HapticFeedbackRequest(
                    SCROLL_TICK, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 120);
        requests.add(new HapticFeedbackRequest(
                    SCROLL_TICK, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollProgress_inputDeviceCustomized_progressEqualsOrExceedsNegativeThreshold() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();
        setHapticScrollTickInterval(100);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -20);

        assertThat(mView.mHapticFeedbackRequests).hasSize(0);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -80);
        requests.add(new HapticFeedbackRequest(
                    SCROLL_TICK, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -70);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -40);
        requests.add(new HapticFeedbackRequest(
                    SCROLL_TICK, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollProgress_inputDeviceCustomized_positiveAndNegativeProgresses() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();
        setHapticScrollTickInterval(100);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 20);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -90);

        // total pixel abs = 70
        assertThat(mView.mHapticFeedbackRequests).hasSize(0);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 10);

        // total pixel abs = 60
        assertThat(mView.mHapticFeedbackRequests).hasSize(0);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ -50);
        // total pixel abs = 110. Passed threshold. total pixel reduced to -10.
        requests.add(new HapticFeedbackRequest(
                    SCROLL_TICK, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 40);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 50);
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 60);
        // total pixel abs = 140. Passed threshold. total pixel reduced to 40.
        requests.add(new HapticFeedbackRequest(
                    SCROLL_TICK, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollProgress_inputDeviceCustomized_singleProgressExceedsThreshold() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();
        setHapticScrollTickInterval(100);

        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 1000);
        requests.add(new HapticFeedbackRequest(
                    SCROLL_TICK, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_startAndEndLimit_inputDeviceCustomized_playsOnlyOneFeedback() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // end played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // start after end NOT played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_doubleStartLimit_inputDeviceCustomized_playsOnlyOneFeedback() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // 1st start played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // 2nd start NOT played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_doubleEndLimit_inputDeviceCustomized_playsOnlyOneFeedback() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // 1st end played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // 2nd end NOT played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_inputDeviceCustomized_notEnabledWithZeroProgress() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // end played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        // progress 0. scroll not started.
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 0);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ true);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_inputDeviceCustomized_enabledWithProgress() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // end played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // No tick since tick-interval is default 0, which means no tick.
        // But still re-enable next limit feedback.
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 80);
        // scroll pixel not 0, so end played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_inputDeviceCustomized_enabledWithSnap() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        // 1st enabled limit by snap
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // 2nd enabled limit by snap
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_inputDeviceCustomized_notEnabledWithDissimilarSnap() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_X);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // Last snap is on AXIS_X, so end on AXIS_SCROLL is NOT played.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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
    public void testScrollLimit_inputDeviceCustomized_enabledWithDissimilarProgress() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        // No tick since tick-interval is default 0, which means no tick.
        // But still re-enable next limit feedback.
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 80);
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
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

    @Test
    public void testNonRotaryInputFeedbackNotBlockedByRotaryUnavailability() {
        when(mMockViewConfig.isViewBasedRotaryEncoderHapticScrollFeedbackEnabled())
                .thenReturn(true);
        setHapticScrollFeedbackEnabled(true);
        setHapticScrollTickInterval(5);
        mProvider = new HapticScrollFeedbackProvider(mView, mMockViewConfig,
                /* disabledIfViewPlaysScrollHaptics= */ true);

        // Expect one feedback here. Touch input should provide feedback since scroll feedback has
        // been enabled via `setHapticScrollFeedbackEnabled(true)`.
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.AXIS_Y,
                /* deltaInPixels= */ 10);
        // Because `isViewBasedRotaryEncoderHapticScrollFeedbackEnabled()` is false and
        // `disabledIfViewPlaysScrollHaptics` is true, the scroll progress from rotary encoders will
        // produce no feedback.
        mProvider.onScrollProgress(
                INPUT_DEVICE_2, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* deltaInPixels= */ 20);
        // This event from the touch screen should produce feedback. The rotary encoder event's
        // inability to not play scroll feedback should not impact this touch input.
        mProvider.onScrollProgress(
                INPUT_DEVICE_1, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.AXIS_Y,
                /* deltaInPixels= */ 30);

        assertFeedbackCount(mView, HapticFeedbackConstants.SCROLL_TICK, 2);
    }

    @Test
    public void testScrollLimit_inputDeviceCustomized_doesNotEnabledWithMotionFromDifferentDeviceId() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));

        mProvider.onSnapToItem(
                INPUT_DEVICE_2, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_2, InputDevice.SOURCE_ROTARY_ENCODER));
        // last snap was for input device #2, so limit for input device #1 not re-enabled.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1,
                InputDevice.SOURCE_ROTARY_ENCODER,
                MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
    }

    @Test
    public void testScrollLimit_inputDeviceCustomized_doesNotEnabledWithMotionFromDifferentSource() {
        mSetFlagsRule.enableFlags(FLAG_HAPTIC_FEEDBACK_INPUT_SOURCE_CUSTOMIZATION_ENABLED);
        List<HapticFeedbackRequest> requests = new ArrayList<>();

        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onScrollLimit(
                INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);
        requests.add(new HapticFeedbackRequest(
                SCROLL_LIMIT, INPUT_DEVICE_1, InputDevice.SOURCE_ROTARY_ENCODER));
        mProvider.onSnapToItem(
                INPUT_DEVICE_1, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.AXIS_SCROLL);
        requests.add(new HapticFeedbackRequest(
                SCROLL_ITEM_FOCUS, INPUT_DEVICE_1, InputDevice.SOURCE_TOUCHSCREEN));
        // last snap was for input source touch screen, so rotary's limit is NOT re-enabled.
        mProvider.onScrollLimit(
                INPUT_DEVICE_1,
                InputDevice.SOURCE_ROTARY_ENCODER,
                MotionEvent.AXIS_SCROLL,
                /* isStart= */ false);

        assertThat(mView.mHapticFeedbackRequests).containsExactlyElementsIn(requests).inOrder();
    }

    private void assertNoFeedback(TestView view) {
        for (int feedback : new int[]{SCROLL_ITEM_FOCUS, SCROLL_LIMIT, SCROLL_TICK}) {
            assertFeedbackCount(view, feedback, 0);
        }
    }

    private void assertOnlyFeedback(TestView view, int expectedFeedback) {
        assertOnlyFeedback(view, expectedFeedback, /* expectedCount= */ 1);
    }

    private void assertOnlyFeedback(TestView view, int expectedFeedback, int expectedCount) {
        for (int feedback : new int[]{SCROLL_ITEM_FOCUS, SCROLL_LIMIT, SCROLL_TICK}) {
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

    private static class TestView extends View {
        final Map<Integer, Integer> mFeedbackCount = new HashMap<>();
        final List<HapticFeedbackRequest> mHapticFeedbackRequests = new ArrayList<>();

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

        @Override
        public void performHapticFeedbackForInputDevice(int feedback, int inputDeviceId,
                int inputSource, int flags) {
            mHapticFeedbackRequests.add(
                    new HapticFeedbackRequest(feedback, inputDeviceId, inputSource));
        }
    }

    private static class HapticFeedbackRequest {
        // <feedback, inputDeviceId, inputSource>
        private final int[] mArgs = new int[3];

        private HapticFeedbackRequest(int feedback, int inputDeviceId, int inputSource) {
            mArgs[0] = feedback;
            mArgs[1] = inputDeviceId;
            mArgs[2] = inputSource;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            HapticFeedbackRequest other = (HapticFeedbackRequest) obj;
            return Arrays.equals(this.mArgs, other.mArgs);
        }

        @Override
        public int hashCode() {
            // Shouldn't depend on hash. Should explicitly match mArgs.
            return Objects.hash(mArgs[0], mArgs[1], mArgs[2]);
        }

        @Override
        public String toString() {
            return String.format("<feedback=%d; inputDeviceId=%d; inputSource=%d>",
                    mArgs[0], mArgs[1], mArgs[2]);
        }
    }
}