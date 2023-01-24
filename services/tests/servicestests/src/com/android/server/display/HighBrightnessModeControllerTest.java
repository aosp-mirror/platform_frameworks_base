/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.display;

import static android.hardware.display.BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
import static android.hardware.display.BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL;
import static android.hardware.display.BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR;
import static android.hardware.display.BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF;
import static android.hardware.display.BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT;

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE;
import static com.android.server.display.DisplayDeviceConfig.HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT;
import static com.android.server.display.HighBrightnessModeController.HBM_TRANSITION_POINT_INVALID;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Binder;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Message;
import android.os.PowerManager;
import android.os.Temperature;
import android.os.Temperature.ThrottlingStatus;
import android.os.test.TestLooper;
import android.test.mock.MockContentResolver;
import android.util.MathUtils;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.display.DisplayDeviceConfig.HighBrightnessModeData;
import com.android.server.display.HighBrightnessModeController.Injector;
import com.android.server.testutils.OffsettableClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class HighBrightnessModeControllerTest {

    private static final int MINIMUM_LUX = 100;
    private static final float TRANSITION_POINT = 0.763f;
    private static final long TIME_WINDOW_MILLIS = 55 * 1000;
    private static final long TIME_ALLOWED_IN_WINDOW_MILLIS = 12 * 1000;
    private static final long TIME_MINIMUM_AVAILABLE_TO_ENABLE_MILLIS = 5 * 1000;
    private static final int THERMAL_STATUS_LIMIT = PowerManager.THERMAL_STATUS_SEVERE;
    private static final boolean ALLOW_IN_LOW_POWER_MODE = false;

    private static final float DEFAULT_MIN = 0.01f;
    private static final float DEFAULT_MAX = 0.80f;

    private static final int DISPLAY_WIDTH = 900;
    private static final int DISPLAY_HEIGHT = 1600;

    private static final float EPSILON = 0.000001f;

    private OffsettableClock mClock;
    private TestLooper mTestLooper;
    private Handler mHandler;
    private Binder mDisplayToken;
    private String mDisplayUniqueId;
    private Context mContextSpy;
    private HighBrightnessModeMetadata mHighBrightnessModeMetadata;

    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock IThermalService mThermalServiceMock;
    @Mock Injector mInjectorMock;

    @Captor ArgumentCaptor<IThermalEventListener> mThermalEventListenerCaptor;

    private static final HighBrightnessModeData DEFAULT_HBM_DATA =
            new HighBrightnessModeData(MINIMUM_LUX, TRANSITION_POINT, TIME_WINDOW_MILLIS,
                    TIME_ALLOWED_IN_WINDOW_MILLIS, TIME_MINIMUM_AVAILABLE_TO_ENABLE_MILLIS,
                    THERMAL_STATUS_LIMIT, ALLOW_IN_LOW_POWER_MODE,
                    HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
        mDisplayToken = null;
        mDisplayUniqueId = "unique_id";

        mContextSpy = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        final MockContentResolver resolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(resolver);

        when(mInjectorMock.getThermalService()).thenReturn(mThermalServiceMock);
    }

    /////////////////
    // Test Methods
    /////////////////

    @Test
    public void testNoHbmData() {
        initHandler(null);
        final HighBrightnessModeController hbmc = new HighBrightnessModeController(
                mInjectorMock, mHandler, DISPLAY_WIDTH, DISPLAY_HEIGHT, mDisplayToken,
                mDisplayUniqueId, DEFAULT_MIN, DEFAULT_MAX, null, null, () -> {},
                null, mContextSpy);
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_OFF);
        assertEquals(hbmc.getTransitionPoint(), HBM_TRANSITION_POINT_INVALID, 0.0f);
    }

    @Test
    public void testNoHbmData_Enabled() {
        initHandler(null);
        final HighBrightnessModeController hbmc = new HighBrightnessModeController(
                mInjectorMock, mHandler, DISPLAY_WIDTH, DISPLAY_HEIGHT, mDisplayToken,
                mDisplayUniqueId, DEFAULT_MIN, DEFAULT_MAX, null, null, () -> {},
                null, mContextSpy);
        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX - 1); // below allowed range
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_OFF);
        assertEquals(hbmc.getTransitionPoint(), HBM_TRANSITION_POINT_INVALID, 0.0f);
    }

    @Test
    public void testOffByDefault() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);
    }

    @Test
    public void testAutoBrightnessEnabled_NoLux() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);
    }

    @Test
    public void testAutoBrightnessEnabled_LowLux() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX - 1); // below allowed range
        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);
    }

    @Test
    public void testAutoBrightnessEnabled_HighLux() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);
    }

    @Test
    public void testAutoBrightnessEnabled_HighLux_ThenDisable() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_DISABLED);

        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);
    }

    @Test
    public void testWithinHighRange_thenOverTime_thenEarnBackTime() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);

        // Verify we are in HBM
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        // Use up all the time in the window.
        advanceTime(TIME_ALLOWED_IN_WINDOW_MILLIS);

        // Verify we are not out of HBM
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        // Shift time so that the HBM event is at the beginning of the current window
        advanceTime(TIME_WINDOW_MILLIS - TIME_ALLOWED_IN_WINDOW_MILLIS);
        // Shift time again so that we are just below the minimum allowable
        advanceTime(TIME_MINIMUM_AVAILABLE_TO_ENABLE_MILLIS - 1);

        // Verify we are not out of HBM
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        // Advance the necessary millisecond
        advanceTime(1);

        // Verify we are allowed HBM again.
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);
    }

    @Test
    public void testInHBM_ThenLowLux() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);

        // Verify we are in HBM
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        advanceTime(TIME_ALLOWED_IN_WINDOW_MILLIS / 2);

        // Verify we are in HBM
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        hbmc.onAmbientLuxChange(1);
        advanceTime(TIME_ALLOWED_IN_WINDOW_MILLIS / 2 + 1);

        // Verify we are out of HBM
        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);

    }

    @Test
    public void testInHBM_TestMultipleEvents_DueToAutoBrightness() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);

        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);
        advanceTime(TIME_ALLOWED_IN_WINDOW_MILLIS / 2);

        // Verify we are in HBM
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT - 0.01f);
        advanceTime(1);

        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);
        advanceTime(TIME_ALLOWED_IN_WINDOW_MILLIS / 2);

        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        advanceTime(2);

        // Now we should be out again.
        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);
    }

    @Test
    public void testInHBM_TestMultipleEvents_DueToLux() {
        final HighBrightnessModeController hbmc = createDefaultHbm();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);

        // Go into HBM for half the allowed window
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);
        advanceTime(TIME_ALLOWED_IN_WINDOW_MILLIS / 2);
        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        // Move lux below threshold (ending first event);
        hbmc.onAmbientLuxChange(MINIMUM_LUX - 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT);
        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);

        // Move up some amount of time so that there's still time in the window even after a
        // second event.
        advanceTime((TIME_WINDOW_MILLIS - TIME_ALLOWED_IN_WINDOW_MILLIS) / 2);
        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);

        // Go into HBM for just under the second half of allowed window
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 1);
        advanceTime((TIME_ALLOWED_IN_WINDOW_MILLIS / 2) - 1);

        assertState(hbmc, DEFAULT_MIN, DEFAULT_MAX, HIGH_BRIGHTNESS_MODE_SUNLIGHT);

        // Now exhaust the time
        advanceTime(2);
        assertState(hbmc, DEFAULT_MIN, TRANSITION_POINT, HIGH_BRIGHTNESS_MODE_OFF);
    }

    @Test
    public void testNoHbmInHighThermalState() throws Exception {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());

        verify(mThermalServiceMock).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_SKIN));
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();

        // Set the thermal status too high.
        listener.notifyThrottling(getSkinTemp(Temperature.THROTTLING_CRITICAL));

        // Try to go into HBM mode but fail
        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        advanceTime(10);

        assertEquals(HIGH_BRIGHTNESS_MODE_OFF, hbmc.getHighBrightnessMode());
    }

    @Test
    public void testHbmTurnsOffInHighThermalState() throws Exception {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());

        verify(mThermalServiceMock).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_SKIN));
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();

        // Set the thermal status tolerable
        listener.notifyThrottling(getSkinTemp(Temperature.THROTTLING_LIGHT));

        // Try to go into HBM mode
        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        advanceTime(1);

        assertEquals(HIGH_BRIGHTNESS_MODE_SUNLIGHT, hbmc.getHighBrightnessMode());

        // Set the thermal status too high and verify we're off.
        listener.notifyThrottling(getSkinTemp(Temperature.THROTTLING_CRITICAL));
        advanceTime(10);
        assertEquals(HIGH_BRIGHTNESS_MODE_OFF, hbmc.getHighBrightnessMode());

        // Set the thermal status low again and verify we're back on.
        listener.notifyThrottling(getSkinTemp(Temperature.THROTTLING_SEVERE));
        advanceTime(1);
        assertEquals(HIGH_BRIGHTNESS_MODE_SUNLIGHT, hbmc.getHighBrightnessMode());
    }

    @Test
    public void testHdrRequires50PercentOfScreen() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());

        final int layerWidth = DISPLAY_WIDTH;
        final int smallLayerHeight = DISPLAY_HEIGHT / 2 - 1; // height to use for <50%
        final int largeLayerHeight = DISPLAY_HEIGHT / 2 + 1; // height to use for >50%

        // ensure hdr doesn't turn on if layer is too small
        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 1 /*numberOfHdrLayers*/,
                layerWidth, smallLayerHeight, 0 /*flags*/);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_OFF, hbmc.getHighBrightnessMode());

        // Now check with layer larger than 50%
        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 1 /*numberOfHdrLayers*/,
                layerWidth, largeLayerHeight, 0 /*flags*/);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_HDR, hbmc.getHighBrightnessMode());
    }

    @Test
    public void testHdrTrumpsSunlight() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());

        // Turn on sunlight
        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_SUNLIGHT, hbmc.getHighBrightnessMode());
        assertEquals(DEFAULT_MAX, hbmc.getCurrentBrightnessMax(), EPSILON);

        // turn on hdr
        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 1 /*numberOfHdrLayers*/,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, 0 /*flags*/);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_HDR, hbmc.getHighBrightnessMode());
        assertEquals(TRANSITION_POINT, hbmc.getCurrentBrightnessMax(), EPSILON);
    }

    @Test
    public void testHdrBrightnessLimitSameAsNormalLimit() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());

        // Check limit when HBM is off
        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 1 /*numberOfHdrLayers*/,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, 0 /*flags*/);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_HDR, hbmc.getHighBrightnessMode());
        assertEquals(TRANSITION_POINT, hbmc.getCurrentBrightnessMax(), EPSILON);

        // Check limit with HBM is set to HDR
        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 0 /*numberOfHdrLayers*/,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, 0 /*flags*/);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_OFF, hbmc.getHighBrightnessMode());
        assertEquals(TRANSITION_POINT, hbmc.getCurrentBrightnessMax(), EPSILON);
    }

    @Test
    public void testHdrBrightnessScaledNormalBrightness() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());

        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 1 /*numberOfHdrLayers*/,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, 0 /*flags*/);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_HDR, hbmc.getHighBrightnessMode());

        // verify things are scaled for 0.5f
        float brightness = 0.5f;
        float expectedHdrBrightness = MathUtils.map(DEFAULT_MIN, TRANSITION_POINT,
                DEFAULT_MIN, DEFAULT_MAX, brightness); // map value from normal range to hdr range
        hbmcOnBrightnessChanged(hbmc, brightness);
        advanceTime(0);
        assertEquals(expectedHdrBrightness, hbmc.getHdrBrightnessValue(), EPSILON);

        // Try another value
        brightness = 0.33f;
        expectedHdrBrightness = MathUtils.map(DEFAULT_MIN, TRANSITION_POINT,
                DEFAULT_MIN, DEFAULT_MAX, brightness); // map value from normal range to hdr range
        hbmcOnBrightnessChanged(hbmc, brightness);
        advanceTime(0);
        assertEquals(expectedHdrBrightness, hbmc.getHdrBrightnessValue(), EPSILON);

        // Try the min value
        brightness = DEFAULT_MIN;
        expectedHdrBrightness = DEFAULT_MIN;
        hbmcOnBrightnessChanged(hbmc, brightness);
        advanceTime(0);
        assertEquals(expectedHdrBrightness, hbmc.getHdrBrightnessValue(), EPSILON);

        // Try the max value
        brightness = TRANSITION_POINT;
        expectedHdrBrightness = DEFAULT_MAX;
        hbmcOnBrightnessChanged(hbmc, brightness);
        advanceTime(0);
        assertEquals(expectedHdrBrightness, hbmc.getHdrBrightnessValue(), EPSILON);
    }

    @Test
    public void testHbmStats_StateChange() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());
        final int displayStatsId = mDisplayUniqueId.hashCode();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT);
        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 1 /*numberOfHdrLayers*/,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, 0 /*flags*/);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_HDR, hbmc.getHighBrightnessMode());

        // Verify Stats HBM_ON_HDR
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_HDR),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 0 /*numberOfHdrLayers*/,
                0, 0, 0 /*flags*/);
        advanceTime(0);

        // Verify Stats HBM_OFF
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);

        // Verify Stats HBM_ON_SUNLIGHT
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        hbmc.onAmbientLuxChange(1);
        advanceTime(TIME_ALLOWED_IN_WINDOW_MILLIS / 2 + 1);

        // Verify Stats HBM_OFF
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_LUX_DROP));
    }

    @Test
    public void testHbmStats_NbmHdrNoReport() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());
        final int displayStatsId = mDisplayUniqueId.hashCode();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmcOnBrightnessChanged(hbmc, DEFAULT_MIN);
        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 1 /*numberOfHdrLayers*/,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, 0 /*flags*/);
        advanceTime(0);
        assertEquals(HIGH_BRIGHTNESS_MODE_HDR, hbmc.getHighBrightnessMode());

        // Verify Stats HBM_ON_HDR not report
        verify(mInjectorMock, never()).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_HDR),
            anyInt());
    }

    @Test
    public void testHbmStats_HighLuxLowBrightnessNoReport() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());
        final int displayStatsId = mDisplayUniqueId.hashCode();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, DEFAULT_MIN);
        advanceTime(0);
        // verify in HBM sunlight mode
        assertEquals(HIGH_BRIGHTNESS_MODE_SUNLIGHT, hbmc.getHighBrightnessMode());

        // Verify Stats HBM_ON_SUNLIGHT not report
        verify(mInjectorMock, never()).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT),
            anyInt());
    }

    // Test reporting of thermal throttling when triggered by HighBrightnessModeController's
    // internal thermal throttling.
    @Test
    public void testHbmStats_InternalThermalOff() throws Exception {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());
        final int displayStatsId = mDisplayUniqueId.hashCode();

        verify(mThermalServiceMock).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_SKIN));
        final IThermalEventListener thermListener = mThermalEventListenerCaptor.getValue();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);
        advanceTime(1);
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        thermListener.notifyThrottling(getSkinTemp(Temperature.THROTTLING_CRITICAL));
        advanceTime(10);
        assertEquals(HIGH_BRIGHTNESS_MODE_OFF, hbmc.getHighBrightnessMode());
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_THERMAL_LIMIT));
    }

    // Test reporting of thermal throttling when triggered externally through
    // HighBrightnessModeController.onBrightnessChanged()
    @Test
    public void testHbmStats_ExternalThermalOff() throws Exception {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());
        final int displayStatsId = mDisplayUniqueId.hashCode();
        final float hbmBrightness = TRANSITION_POINT + 0.01f;
        final float nbmBrightness = TRANSITION_POINT - 0.01f;

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        // Brightness is unthrottled, HBM brightness granted
        hbmc.onBrightnessChanged(hbmBrightness, hbmBrightness, BRIGHTNESS_MAX_REASON_NONE);
        advanceTime(1);
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        // Brightness is thermally throttled, HBM brightness denied (NBM brightness granted)
        hbmc.onBrightnessChanged(nbmBrightness, hbmBrightness, BRIGHTNESS_MAX_REASON_THERMAL);
        advanceTime(1);
        // We expect HBM mode to remain set to sunlight, indicating that HBMC *allows* this mode.
        // However, we expect the HBM state reported by HBMC to be off, since external thermal
        // throttling (reported to HBMC through onBrightnessChanged()) lowers brightness to below
        // the HBM transition point.
        assertEquals(HIGH_BRIGHTNESS_MODE_SUNLIGHT, hbmc.getHighBrightnessMode());
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_THERMAL_LIMIT));
    }

    @Test
    public void testHbmStats_TimeOut() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());
        final int displayStatsId = mDisplayUniqueId.hashCode();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);
        advanceTime(0);
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        // Use up all the time in the window.
        advanceTime(TIME_WINDOW_MILLIS + 1);

        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_TIME_LIMIT));
    }

    @Test
    public void testHbmStats_DisplayOff() {
        final HighBrightnessModeController hbmc = createDefaultHbm();
        final int displayStatsId = mDisplayUniqueId.hashCode();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);
        advanceTime(0);
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE);
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_DISPLAY_OFF));
    }

    @Test
    public void testHbmStats_HdrPlaying() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());
        final int displayStatsId = mDisplayUniqueId.hashCode();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);
        advanceTime(0);
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        hbmc.getHdrListener().onHdrInfoChanged(null /*displayToken*/, 1 /*numberOfHdrLayers*/,
                DISPLAY_WIDTH, DISPLAY_HEIGHT, 0 /*flags*/);
        advanceTime(0);

        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_HDR),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_HDR_PLAYING));
    }

    @Test
    public void tetHbmStats_LowRequestedBrightness() {
        final HighBrightnessModeController hbmc = createDefaultHbm(new OffsettableClock());
        final int displayStatsId = mDisplayUniqueId.hashCode();

        hbmc.setAutoBrightnessEnabled(AUTO_BRIGHTNESS_ENABLED);
        hbmc.onAmbientLuxChange(MINIMUM_LUX + 1);
        hbmcOnBrightnessChanged(hbmc, TRANSITION_POINT + 0.01f);
        advanceTime(0);
        // verify in HBM sunlight mode
        assertEquals(HIGH_BRIGHTNESS_MODE_SUNLIGHT, hbmc.getHighBrightnessMode());
        // verify HBM_ON_SUNLIGHT
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN));

        hbmcOnBrightnessChanged(hbmc, DEFAULT_MIN);
        // verify HBM_SV_OFF due to LOW_REQUESTED_BRIGHTNESS
        verify(mInjectorMock).reportHbmStateChange(eq(displayStatsId),
            eq(FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF),
            eq(FrameworkStatsLog
                      .DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_LOW_REQUESTED_BRIGHTNESS));
    }

    private void assertState(HighBrightnessModeController hbmc,
            float brightnessMin, float brightnessMax, int hbmMode) {
        assertEquals(brightnessMin, hbmc.getCurrentBrightnessMin(), EPSILON);
        assertEquals(brightnessMax, hbmc.getCurrentBrightnessMax(), EPSILON);
        assertEquals(hbmMode, hbmc.getHighBrightnessMode());
    }

    private HighBrightnessModeController createDefaultHbm() {
        return createDefaultHbm(null);
    }

    // Creates instance with standard initialization values.
    private HighBrightnessModeController createDefaultHbm(OffsettableClock clock) {
        initHandler(clock);
        if (mHighBrightnessModeMetadata == null) {
            mHighBrightnessModeMetadata = new HighBrightnessModeMetadata();
        }
        return new HighBrightnessModeController(mInjectorMock, mHandler, DISPLAY_WIDTH,
                DISPLAY_HEIGHT, mDisplayToken, mDisplayUniqueId, DEFAULT_MIN, DEFAULT_MAX,
                DEFAULT_HBM_DATA, null, () -> {}, mHighBrightnessModeMetadata, mContextSpy);
    }

    private void initHandler(OffsettableClock clock) {
        mClock = clock != null ? clock : new OffsettableClock.Stopped();
        when(mInjectorMock.getClock()).thenReturn(mClock::now);
        mTestLooper = new TestLooper(mClock::now);
        mHandler = new Handler(mTestLooper.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                return true;
            }
        });
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    private Temperature getSkinTemp(@ThrottlingStatus int status) {
        return new Temperature(30.0f, Temperature.TYPE_SKIN, "test_skin_temp", status);
    }

    private void hbmcOnBrightnessChanged(HighBrightnessModeController hbmc, float brightness) {
        hbmc.onBrightnessChanged(brightness, brightness, BRIGHTNESS_MAX_REASON_NONE);
    }
}
