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

package com.android.server.display.brightness.clamper;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.IBinder;
import android.os.PowerManager;

import androidx.test.filters.SmallTest;

import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.config.DisplayDeviceConfigTestUtilsKt;
import com.android.server.display.config.HdrBrightnessData;
import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Map;

@SmallTest
public class HdrClamperTest {

    private static final float FLOAT_TOLERANCE = 0.0001f;
    private static final long SEND_TIME_TOLERANCE = 100;

    private static final HdrBrightnessData TEST_HDR_DATA = DisplayDeviceConfigTestUtilsKt
            .createHdrBrightnessData(
                    Map.of(500f, 0.6f),
                    /* brightnessIncreaseDebounceMillis= */ 1000,
                    /* screenBrightnessRampIncrease= */ 0.02f,
                    /* brightnessDecreaseDebounceMillis= */ 3000,
                    /* screenBrightnessRampDecrease= */0.04f
            );

    private static final int WIDTH = 600;
    private static final int HEIGHT = 800;
    private static final float MIN_HDR_PERCENT = 0.5f;

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockListener;

    @Mock
    private IBinder mMockBinder;

    @Mock
    private HdrClamper.Injector mMockInjector;

    @Mock
    private HdrClamper.HdrLayerInfoListener mMockHdrInfoListener;

    OffsettableClock mClock = new OffsettableClock.Stopped();

    private final TestHandler mTestHandler = new TestHandler(null, mClock);


    private HdrClamper mHdrClamper;
    private HdrClamper.HdrListener mHdrChangeListener;

    @Before
    public void setUp() {
        when(mMockInjector.getHdrListener(any(), any())).thenReturn(mMockHdrInfoListener);
        mHdrClamper = new HdrClamper(mMockListener, mTestHandler, mMockInjector);
        ArgumentCaptor<HdrClamper.HdrListener> listenerCaptor = ArgumentCaptor.forClass(
                HdrClamper.HdrListener.class);
        verify(mMockInjector).getHdrListener(listenerCaptor.capture(), eq(mTestHandler));
        mHdrChangeListener = listenerCaptor.getValue();
        configureClamper();
    }

    @Test
    public void testRegisterHdrListener() {
        verify(mMockHdrInfoListener).register(mMockBinder);
    }

    @Test
    public void testRegisterOtherHdrListenerWhenCalledWithOtherToken() {
        IBinder otherBinder = mock(IBinder.class);
        mHdrClamper.resetHdrConfig(TEST_HDR_DATA, WIDTH, HEIGHT, MIN_HDR_PERCENT, otherBinder);

        verify(mMockHdrInfoListener).unregister(mMockBinder);
        verify(mMockHdrInfoListener).register(otherBinder);
    }

    @Test
    public void testRegisterHdrListenerOnceWhenCalledWithSameToken() {
        mHdrClamper.resetHdrConfig(TEST_HDR_DATA, WIDTH, HEIGHT, MIN_HDR_PERCENT, mMockBinder);

        verify(mMockHdrInfoListener, never()).unregister(mMockBinder);
        verify(mMockHdrInfoListener, times(1)).register(mMockBinder);
    }

    @Test
    public void testRegisterHdrListener_ZeroMinHdrPercent() {
        IBinder otherBinder = mock(IBinder.class);
        mHdrClamper.resetHdrConfig(TEST_HDR_DATA, WIDTH, HEIGHT,
            /* minimumHdrPercentOfScreen= */ 0, otherBinder);

        verify(mMockHdrInfoListener).unregister(mMockBinder);
        verify(mMockHdrInfoListener).register(otherBinder);
    }

    @Test
    public void testRegisterNotCalledIfHbmConfigIsMissing() {
        IBinder otherBinder = mock(IBinder.class);
        mHdrClamper.resetHdrConfig(TEST_HDR_DATA, WIDTH, HEIGHT, -1, otherBinder);

        verify(mMockHdrInfoListener).unregister(mMockBinder);
        verify(mMockHdrInfoListener, never()).register(otherBinder);
    }

    @Test
    public void testClamper_AmbientLuxChangesAboveLimit() {
        mHdrClamper.onAmbientLuxChange(500);

        assertFalse(mTestHandler.hasMessagesOrCallbacks());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(-1, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);
    }

    @Test
    public void testClamper_AmbientLuxChangesBelowLimit_MaxDecrease() {
        mHdrClamper.onAmbientLuxChange(499);

        assertTrue(mTestHandler.hasMessagesOrCallbacks());
        TestHandler.MsgInfo msgInfo = mTestHandler.getPendingMessages().peek();
        assertSendTime(3000, msgInfo.sendTime);
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(-1, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);

        mClock.fastForward(3000);
        mTestHandler.timeAdvance();
        assertEquals(0.6f, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(0.04, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);
    }

    @Test
    public void testClamper_AmbientLuxChangesBelowLimit_ThenFastAboveLimit() {
        mHdrClamper.onAmbientLuxChange(499);
        mHdrClamper.onAmbientLuxChange(500);

        assertFalse(mTestHandler.hasMessagesOrCallbacks());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(-1, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);
    }

    @Test
    public void testClamper_AmbientLuxChangesBelowLimit_ThenSlowlyAboveLimit() {
        mHdrClamper.onAmbientLuxChange(499);
        mClock.fastForward(3000);
        mTestHandler.timeAdvance();

        mHdrClamper.onAmbientLuxChange(500);

        assertTrue(mTestHandler.hasMessagesOrCallbacks());
        TestHandler.MsgInfo msgInfo = mTestHandler.getPendingMessages().peek();
        assertSendTime(4000, msgInfo.sendTime); // 3000 + 1000

        mClock.fastForward(1000);
        mTestHandler.timeAdvance();
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(0.02f, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);
    }

    @Test
    public void testClamper_HdrOff_ThenAmbientLuxChangesBelowLimit() {
        mHdrChangeListener.onHdrVisible(false);
        mHdrClamper.onAmbientLuxChange(499);

        assertFalse(mTestHandler.hasMessagesOrCallbacks());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(-1, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);
    }

    @Test
    public void testClamper_HdrOff_ThenAmbientLuxChangesBelowLimit_ThenHdrOn() {
        mHdrChangeListener.onHdrVisible(false);
        mHdrClamper.onAmbientLuxChange(499);
        mHdrChangeListener.onHdrVisible(true);

        assertTrue(mTestHandler.hasMessagesOrCallbacks());
        TestHandler.MsgInfo msgInfo = mTestHandler.getPendingMessages().peek();
        assertSendTime(3000, msgInfo.sendTime);
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);

        mClock.fastForward(3000);
        mTestHandler.timeAdvance();
        assertEquals(0.6f, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(0.04f, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);
    }

    @Test
    public void testCalmper_transitionRateOverriddenByOtherRequest() {
        mHdrClamper.onAmbientLuxChange(499);

        mClock.fastForward(3000);
        mTestHandler.timeAdvance();
        assertEquals(0.6f, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(0.04, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);
        // getTransitionRate should reset transitionRate
        assertEquals(-1f, mHdrClamper.getTransitionRate(), FLOAT_TOLERANCE);
    }

    // MsgInfo.sendTime is calculated first by adding SystemClock.uptimeMillis()
    // (in Handler.sendMessageDelayed) and then by subtracting SystemClock.uptimeMillis()
    // (in TestHandler.sendMessageAtTime, there might be several milliseconds difference between
    // SystemClock.uptimeMillis() calls, and subtracted value might be greater than added.
    private static void assertSendTime(long expectedTime, long sendTime) {
        assertTrue(expectedTime >= sendTime);
        assertTrue(expectedTime - SEND_TIME_TOLERANCE < sendTime);
    }

    private void configureClamper() {
        // AutoBrightnessController sends ambientLux values *only* when auto brightness enabled.
        // HdrClamper is temporary disabled  if auto brightness is off.
        // Temporary setting AutoBrightnessState to enabled for this test
        // The issue is tracked here: b/322445088
        mHdrClamper.setAutoBrightnessState(AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED);
        mHdrClamper.resetHdrConfig(TEST_HDR_DATA, WIDTH, HEIGHT, MIN_HDR_PERCENT, mMockBinder);
        mHdrChangeListener.onHdrVisible(true);
    }
}
