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

import static android.view.InputDevice.SOURCE_CLASS_POINTER;
import static android.view.InputDevice.SOURCE_ROTARY_ENCODER;
import static android.view.MotionEvent.ACTION_SCROLL;
import static android.view.MotionEvent.AXIS_HSCROLL;
import static android.view.MotionEvent.AXIS_SCROLL;
import static android.view.MotionEvent.AXIS_VSCROLL;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test for the rotary scroll haptics implementation in the View class. */
@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public final class RotaryScrollHapticsTest {
    private static final int TEST_ROTARY_DEVICE_ID = 1;
    private static final int TEST_RANDOM_DEVICE_ID = 2;

    private static final float TEST_SCALED_VERTICAL_SCROLL_FACTOR = 5f;

    @Mock ViewConfiguration mMockViewConfig;
    @Mock HapticScrollFeedbackProvider mMockScrollFeedbackProvider;

    private TestGenericMotionEventControllingView mView;

    @Before
    public void setUp() {
        mMockViewConfig = mock(ViewConfiguration.class);
        mMockScrollFeedbackProvider = mock(HapticScrollFeedbackProvider.class);

        Context context = InstrumentationRegistry.getTargetContext();
        mView = new TestGenericMotionEventControllingView(context);
        mView.mScrollFeedbackProvider = mMockScrollFeedbackProvider;

        ViewConfiguration.setInstanceForTesting(context, mMockViewConfig);
        when(mMockViewConfig.getScaledVerticalScrollFactor())
                .thenReturn(TEST_SCALED_VERTICAL_SCROLL_FACTOR);
        mockRotaryScrollHapticsEnabled(true);
    }

    @After
    public void tearDown() {
        ViewConfiguration.resetCacheForTesting();
    }

    @Test
    public void testRotaryScrollHapticsDisabled_producesNoHapticEvent() {
        mockRotaryScrollHapticsEnabled(false);

        mView.configureGenericMotion(/* result= */ false, /* scroll= */ false);
        mView.dispatchGenericMotionEvent(createRotaryEvent(-20));

        mView.configureGenericMotion(/* result= */ false, /* scroll= */ true);
        mView.dispatchGenericMotionEvent(createRotaryEvent(20));

        mView.configureGenericMotion(/* result= */ true, /* scroll= */ true);
        mView.dispatchGenericMotionEvent(createRotaryEvent(10));

        mView.configureGenericMotion(/* result= */ true, /* scroll= */ false);
        mView.dispatchGenericMotionEvent(createRotaryEvent(-10));

        verifyNoScrollLimit();
        verifyNoScrollProgress();
    }

    @Test
    public void testNonRotaryEncoderMotion_producesNoHapticEvent() {
        mView.configureGenericMotion(/* result= */ false, /* scroll= */ false);
        mView.dispatchGenericMotionEvent(createGenericPointerEvent(1, 2));

        mView.configureGenericMotion(/* result= */ false, /* scroll= */ true);
        mView.dispatchGenericMotionEvent(createGenericPointerEvent(2, 2));

        mView.configureGenericMotion(/* result= */ true, /* scroll= */ true);
        mView.dispatchGenericMotionEvent(createGenericPointerEvent(1, 3));

        mView.configureGenericMotion(/* result= */ true, /* scroll= */ false);
        mView.dispatchGenericMotionEvent(createGenericPointerEvent(-1, -2));

        verifyNoScrollLimit();
        verifyNoScrollProgress();
    }

    @Test
    public void testScrollLimit_start_genericMotionEventCallbackReturningFalse_doesScrollLimit() {
        mView.configureGenericMotion(/* result= */ false, /* scroll= */ false);

        mView.dispatchGenericMotionEvent(createRotaryEvent(20));

        verifyScrollLimit(/* isStart= */ true);
        verifyNoScrollProgress();
    }

    @Test
    public void testScrollLimit_start_genericMotionEventCallbackReturningTrue_doesScrollLimit() {
        mView.configureGenericMotion(/* result= */ true, /* scroll= */ false);

        mView.dispatchGenericMotionEvent(createRotaryEvent(20));

        verifyScrollLimit(/* isStart= */ true);
        verifyNoScrollProgress();
    }

    @Test
    public void testScrollLimit_end_genericMotionEventCallbackReturningFalse_doesScrollLimit() {
        mView.configureGenericMotion(/* result= */ false, /* scroll= */ false);

        mView.dispatchGenericMotionEvent(createRotaryEvent(-20));

        verifyScrollLimit(/* isStart= */ false);
        verifyNoScrollProgress();
    }

    @Test
    public void testScrollLimit_end_genericMotionEventCallbackReturningTrue_doesScrollLimit() {
        mView.configureGenericMotion(/* result= */ true, /* scroll= */ false);

        mView.dispatchGenericMotionEvent(createRotaryEvent(-20));

        verifyScrollLimit(/* isStart= */ false);
        verifyNoScrollProgress();
    }

    @Test
    public void testScrollProgress_genericMotionEventCallbackReturningFalse_doesScrollProgress() {
        mView.configureGenericMotion(/* result= */ false, /* scroll= */ true);

        mView.dispatchGenericMotionEvent(createRotaryEvent(20));

        verifyScrollProgress(-1 * 20 * (int) TEST_SCALED_VERTICAL_SCROLL_FACTOR);
        verifyNoScrollLimit();
    }

    @Test
    public void testScrollProgress_genericMotionEventCallbackReturningTrue_doesScrollProgress() {
        mView.configureGenericMotion(/* result= */ true, /* scroll= */ true);

        mView.dispatchGenericMotionEvent(createRotaryEvent(-20));

        verifyScrollProgress(-1 * -20 * (int) TEST_SCALED_VERTICAL_SCROLL_FACTOR);
        verifyNoScrollLimit();
    }

    private void verifyScrollProgress(int scrollPixels) {
        verify(mMockScrollFeedbackProvider).onScrollProgress(
                TEST_ROTARY_DEVICE_ID, SOURCE_ROTARY_ENCODER, AXIS_SCROLL, scrollPixels);
    }

    private void verifyNoScrollProgress() {
        verify(mMockScrollFeedbackProvider, never()).onScrollProgress(
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    private void verifyScrollLimit(boolean isStart) {
        verify(mMockScrollFeedbackProvider).onScrollLimit(
                TEST_ROTARY_DEVICE_ID, SOURCE_ROTARY_ENCODER, AXIS_SCROLL, isStart);
    }

    private void verifyNoScrollLimit() {
        verify(mMockScrollFeedbackProvider, never()).onScrollLimit(
                anyInt(), anyInt(), anyInt(), anyBoolean());
    }

    private void mockRotaryScrollHapticsEnabled(boolean enabled) {
        when(mMockViewConfig.isViewBasedRotaryEncoderHapticScrollFeedbackEnabled())
                .thenReturn(enabled);
    }

    /**
     * Test implementation for View giving control on behavior of
     * {@link View#onGenericMotionEvent(MotionEvent)}.
     */
    private static final class TestGenericMotionEventControllingView extends View {
        private boolean mGenericMotionResult;
        private boolean mScrollOnGenericMotion;

        TestGenericMotionEventControllingView(Context context) {
            super(context);
        }

        void configureGenericMotion(boolean result, boolean scroll) {
            mGenericMotionResult = result;
            mScrollOnGenericMotion = scroll;
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent event) {
            if (mScrollOnGenericMotion) {
                scrollTo(100, 200); // scroll values random (not relevant for tests).
            }
            return mGenericMotionResult;
        }
    }

    private static MotionEvent createRotaryEvent(float scroll) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(AXIS_SCROLL, scroll);

        return createGenericMotionEvent(
                TEST_ROTARY_DEVICE_ID, SOURCE_ROTARY_ENCODER, ACTION_SCROLL, coords);
    }

    private static MotionEvent createGenericPointerEvent(float hScroll, float vScroll) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(AXIS_HSCROLL, hScroll);
        coords.setAxisValue(AXIS_VSCROLL, vScroll);

        return createGenericMotionEvent(
                TEST_RANDOM_DEVICE_ID, SOURCE_CLASS_POINTER, ACTION_SCROLL, coords);
    }

    private static MotionEvent createGenericMotionEvent(
            int deviceId, int source, int action, MotionEvent.PointerCoords coords) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;

        return MotionEvent.obtain(
                /* downTime= */ 0, /* eventTime= */ 100, action, /* pointerCount= */ 1,
                new MotionEvent.PointerProperties[] {props},
                new MotionEvent.PointerCoords[] {coords},
                /* metaState= */ 0, /* buttonState= */ 0, /* xPrecision= */ 0, /* yPrecision= */ 0,
                deviceId, /* edgeFlags= */ 0, source, /* flags= */ 0);
    }
}
