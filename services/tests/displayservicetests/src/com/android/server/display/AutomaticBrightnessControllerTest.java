/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DOZE;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.PowerManager;
import android.os.test.TestLooper;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.Clock;
import com.android.server.display.brightness.LightSensorController;
import com.android.server.display.brightness.clamper.BrightnessClamperController;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AutomaticBrightnessControllerTest {
    private static final float BRIGHTNESS_MIN_FLOAT = 0.0f;
    private static final float BRIGHTNESS_MAX_FLOAT = 1.0f;
    private static final int INITIAL_LIGHT_SENSOR_RATE = 20;
    private static final float DOZE_SCALE_FACTOR = 0.54f;
    private static final float EPSILON = 0.001f;
    private OffsettableClock mClock = new OffsettableClock();
    private TestLooper mTestLooper;
    private Context mContext;
    private AutomaticBrightnessController mController;

    @Mock BrightnessMappingStrategy mBrightnessMappingStrategy;
    @Mock BrightnessMappingStrategy mIdleBrightnessMappingStrategy;
    @Mock BrightnessMappingStrategy mDozeBrightnessMappingStrategy;
    @Mock HysteresisLevels mScreenBrightnessThresholds;
    @Mock HysteresisLevels mScreenBrightnessThresholdsIdle;
    @Mock Handler mNoOpHandler;
    @Mock BrightnessRangeController mBrightnessRangeController;
    @Mock
    BrightnessClamperController mBrightnessClamperController;
    @Mock BrightnessThrottler mBrightnessThrottler;

    @Mock
    LightSensorController mLightSensorController;

    @Before
    public void setUp() throws Exception {
        // Share classloader to allow package private access.
        System.setProperty("dexmaker.share_classloader", "true");
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getContext();
        setupController(BrightnessMappingStrategy.INVALID_LUX,
                BrightnessMappingStrategy.INVALID_NITS);
    }

    @After
    public void tearDown() {
        if (mController != null) {
            // Stop the update Brightness loop.
            mController.stop();
            mController = null;
        }
    }

    private void setupController(float userLux, float userNits) {
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);

        when(mBrightnessMappingStrategy.getMode()).thenReturn(AUTO_BRIGHTNESS_MODE_DEFAULT);
        when(mIdleBrightnessMappingStrategy.getMode()).thenReturn(AUTO_BRIGHTNESS_MODE_IDLE);
        when(mDozeBrightnessMappingStrategy.getMode()).thenReturn(AUTO_BRIGHTNESS_MODE_DOZE);

        SparseArray<BrightnessMappingStrategy> brightnessMappingStrategyMap = new SparseArray<>();
        brightnessMappingStrategyMap.append(AUTO_BRIGHTNESS_MODE_DEFAULT,
                mBrightnessMappingStrategy);
        brightnessMappingStrategyMap.append(AUTO_BRIGHTNESS_MODE_IDLE,
                mIdleBrightnessMappingStrategy);
        brightnessMappingStrategyMap.append(AUTO_BRIGHTNESS_MODE_DOZE,
                mDozeBrightnessMappingStrategy);
        mController = new AutomaticBrightnessController(
                new AutomaticBrightnessController.Injector() {
                    @Override
                    public Handler getBackgroundThreadHandler() {
                        return mNoOpHandler;
                    }

                    @Override
                    Clock createClock() {
                        return new Clock() {
                            @Override
                            public long uptimeMillis() {
                                return mClock.now();
                            }
                        };
                    }

                }, // pass in test looper instead, pass in offsettable clock
                () -> { }, mTestLooper.getLooper(),
                brightnessMappingStrategyMap, BRIGHTNESS_MIN_FLOAT,
                BRIGHTNESS_MAX_FLOAT, DOZE_SCALE_FACTOR, mScreenBrightnessThresholds,
                mScreenBrightnessThresholdsIdle,
                mContext, mBrightnessRangeController, mBrightnessThrottler,
                userLux, userNits, mLightSensorController, mBrightnessClamperController
        );

        when(mBrightnessRangeController.getCurrentBrightnessMax()).thenReturn(
                BRIGHTNESS_MAX_FLOAT);
        when(mBrightnessRangeController.getCurrentBrightnessMin()).thenReturn(
                BRIGHTNESS_MIN_FLOAT);
        // Disable brightness throttling by default. Individual tests can enable it as needed.
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);
        when(mBrightnessThrottler.isThrottled()).thenReturn(false);

        // Configure the brightness controller and grab an instance of the sensor listener,
        // through which we can deliver fake (for test) sensor values.
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                0 /* brightness= */, false /* userChangedBrightness= */, 0 /* adjustment= */,
                false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);
    }

    @Test
    public void testNoHysteresisAtMinBrightness() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Set up system to return 0.02f as a brightness value
        float lux1 = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness1 = 0.02f;
        when(mBrightnessMappingStrategy.getBrightness(eq(lux1), eq(null), anyInt()))
                .thenReturn(normalizedBrightness1);

        // This is the important bit: When the new brightness is set, make sure the new
        // brightening threshold is beyond the maximum brightness value...so that we can test that
        // our threshold clamping works.
        when(mScreenBrightnessThresholds.getBrighteningThreshold(normalizedBrightness1))
                .thenReturn(1.0f);

        // Send new sensor value and verify
        listener.onAmbientLuxChange(lux1);
        assertEquals(normalizedBrightness1, mController.getAutomaticScreenBrightness(), EPSILON);

        // Set up system to return 0.0f (minimum possible brightness) as a brightness value
        float lux2 = 10.0f;
        float normalizedBrightness2 = 0.0f;
        when(mBrightnessMappingStrategy.getBrightness(anyFloat(), eq(null), anyInt()))
                .thenReturn(normalizedBrightness2);

        // Send new sensor value and verify
        listener.onAmbientLuxChange(lux2);
        assertEquals(normalizedBrightness2, mController.getAutomaticScreenBrightness(), EPSILON);
    }

    @Test
    public void testNoHysteresisAtMaxBrightness() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Set up system to return 0.98f as a brightness value
        float lux1 = 100.0f;
        float normalizedBrightness1 = 0.98f;

        when(mBrightnessMappingStrategy.getBrightness(eq(lux1), eq(null), anyInt()))
                .thenReturn(normalizedBrightness1);

        // This is the important bit: When the new brightness is set, make sure the new
        // brightening threshold is beyond the maximum brightness value...so that we can test that
        // our threshold clamping works.
        when(mScreenBrightnessThresholds.getBrighteningThreshold(normalizedBrightness1))
                .thenReturn(1.1f);

        // Send new sensor value and verify
        listener.onAmbientLuxChange(lux1);
        assertEquals(normalizedBrightness1, mController.getAutomaticScreenBrightness(), EPSILON);


        // Set up system to return 1.0f as a brightness value (brightness_max)
        float lux2 = 110.0f;
        float normalizedBrightness2 = 1.0f;
        when(mBrightnessMappingStrategy.getBrightness(anyFloat(), eq(null), anyInt()))
                .thenReturn(normalizedBrightness2);

        // Send new sensor value and verify
        listener.onAmbientLuxChange(lux2);
        assertEquals(normalizedBrightness2, mController.getAutomaticScreenBrightness(), EPSILON);
    }

    @Test
    public void testUserAddUserDataPoint() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Sensor reads 1000 lux,
        listener.onAmbientLuxChange(1000);

        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                0.5f /* brightness= */, true /* userChangedBrightness= */, 0 /* adjustment= */,
                false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        // There should be a user data point added to the mapper.
        verify(mBrightnessMappingStrategy).addUserDataPoint(/* lux= */ 1000f,
                /* brightness= */ 0.5f);
    }

    @Test
    public void testRecalculateSplines() throws Exception {
        // Enabling the light sensor, and setting the ambient lux to 1000
        int currentLux = 1000;
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();
        listener.onAmbientLuxChange(currentLux);

        // User sets brightness to 0.5f
        when(mBrightnessMappingStrategy.getBrightness(currentLux,
                null, ApplicationInfo.CATEGORY_UNDEFINED)).thenReturn(0.5f);
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                0.5f /* brightness= */, true /* userChangedBrightness= */, 0 /* adjustment= */,
                false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        //Recalculating the spline with RBC enabled, verifying that the short term model is reset,
        //and the interaction is learnt in short term model
        float[] adjustments = new float[]{0.2f, 0.6f};
        mController.recalculateSplines(true, adjustments);
        verify(mBrightnessMappingStrategy).clearUserDataPoints();
        verify(mBrightnessMappingStrategy).recalculateSplines(true, adjustments);
        verify(mBrightnessMappingStrategy, times(2)).addUserDataPoint(currentLux,
                /* brightness= */ 0.5f);

        clearInvocations(mBrightnessMappingStrategy);

        // Verify short term model is not learnt when RBC is disabled
        mController.recalculateSplines(false, adjustments);
        verify(mBrightnessMappingStrategy).clearUserDataPoints();
        verify(mBrightnessMappingStrategy).recalculateSplines(false, adjustments);
        verifyNoMoreInteractions(mBrightnessMappingStrategy);
    }

    @Test
    public void testShortTermModelTimesOut() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onAmbientLuxChange(123);
        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0.5f, /* userChangedBrightness= */ true, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE);
        when(mBrightnessMappingStrategy.shouldResetShortTermModel(
                123f, 0.5f)).thenReturn(true);

        // Sensor reads 1000 lux,
        listener.onAmbientLuxChange(1000);
        mTestLooper.moveTimeForward(
                mBrightnessMappingStrategy.getShortTermModelTimeout() + 1000);
        mTestLooper.dispatchAll();

        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT);
        mTestLooper.moveTimeForward(4000);
        mTestLooper.dispatchAll();

        // Verify only happens on the first configure. (i.e. not again when switching back)
        // Intentionally using any() to ensure it's not called whatsoever.
        verify(mBrightnessMappingStrategy, times(1))
                .addUserDataPoint(/* lux= */ 123.0f, /* brightness= */ 0.5f);
        verify(mBrightnessMappingStrategy, times(1))
                .addUserDataPoint(anyFloat(), anyFloat());
    }

    @Test
    public void testShortTermModelDoesntTimeOut() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onAmbientLuxChange(123);
        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                0.51f /* brightness= */, true /* userChangedBrightness= */, 0 /* adjustment= */,
                false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        when(mBrightnessMappingStrategy.shouldResetShortTermModel(
                anyFloat(), anyFloat())).thenReturn(true);
        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);
        when(mBrightnessMappingStrategy.getUserBrightness()).thenReturn(0.51f);
        when(mBrightnessMappingStrategy.getUserLux()).thenReturn(123.0f);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE);

        // Time does not move forward, since clock is doesn't increment naturally.
        mTestLooper.dispatchAll();

        // Sensor reads 100000 lux,
        listener.onAmbientLuxChange(678910);
        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT);

        // Verify short term model is not reset.
        verify(mBrightnessMappingStrategy, never()).clearUserDataPoints();

        // Verify that we add the data point once when the user sets it, and again when we return
        // interactive mode.
        verify(mBrightnessMappingStrategy, times(2))
                .addUserDataPoint(/* lux= */ 123.0f, /* brightness= */ 0.51f);
    }

    @Test
    public void testShortTermModelIsRestoredWhenSwitchingWithinTimeout() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onAmbientLuxChange(123);
        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0.5f, /* userChangedBrightness= */ true, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);
        when(mBrightnessMappingStrategy.getUserBrightness()).thenReturn(0.5f);
        when(mBrightnessMappingStrategy.getUserLux()).thenReturn(123f);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE);
        when(mIdleBrightnessMappingStrategy.getUserBrightness()).thenReturn(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mIdleBrightnessMappingStrategy.getUserLux()).thenReturn(
                BrightnessMappingStrategy.INVALID_LUX);
        when(mBrightnessMappingStrategy.shouldResetShortTermModel(
                123f, 0.5f)).thenReturn(true);

        // Sensor reads 1000 lux,
        listener.onAmbientLuxChange(1000);
        mTestLooper.moveTimeForward(
                mBrightnessMappingStrategy.getShortTermModelTimeout() + 1000);
        mTestLooper.dispatchAll();

        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT);
        mTestLooper.moveTimeForward(4000);
        mTestLooper.dispatchAll();

        // Verify only happens on the first configure. (i.e. not again when switching back)
        // Intentionally using any() to ensure it's not called whatsoever.
        verify(mBrightnessMappingStrategy, times(1))
                .addUserDataPoint(/* lux= */ 123.0f, /* brightness= */ 0.5f);
        verify(mBrightnessMappingStrategy, times(1))
                .addUserDataPoint(anyFloat(), anyFloat());
    }

    @Test
    public void testShortTermModelNotRestoredAfterTimeout() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onAmbientLuxChange(123);
        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0.5f, /* userChangedBrightness= */ true, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);

        when(mBrightnessMappingStrategy.getUserBrightness()).thenReturn(0.5f);
        when(mBrightnessMappingStrategy.getUserLux()).thenReturn(123f);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE);
        when(mIdleBrightnessMappingStrategy.getUserBrightness()).thenReturn(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mIdleBrightnessMappingStrategy.getUserLux()).thenReturn(
                BrightnessMappingStrategy.INVALID_LUX);

        when(mBrightnessMappingStrategy.shouldResetShortTermModel(
                123f, 0.5f)).thenReturn(true);

        // Sensor reads 1000 lux,
        listener.onAmbientLuxChange(1000);
        // Do not fast-forward time.
        mTestLooper.dispatchAll();

        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT);
        // Do not fast-forward time
        mTestLooper.dispatchAll();

        // Verify this happens on the first configure and again when switching back
        // Intentionally using any() to ensure it's not called any other times whatsoever.
        verify(mBrightnessMappingStrategy, times(2))
                .addUserDataPoint(/* lux= */ 123.0f, /* brightness= */ 0.5f);
        verify(mBrightnessMappingStrategy, times(2))
                .addUserDataPoint(anyFloat(), anyFloat());
    }

    @Test
    public void testSwitchBetweenModesNoUserInteractions() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onAmbientLuxChange(123);
        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);
        when(mBrightnessMappingStrategy.getUserBrightness()).thenReturn(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mBrightnessMappingStrategy.getUserLux()).thenReturn(
                BrightnessMappingStrategy.INVALID_LUX);

        // No user brightness interaction.

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE);
        when(mIdleBrightnessMappingStrategy.getUserBrightness()).thenReturn(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mIdleBrightnessMappingStrategy.getUserLux()).thenReturn(
                BrightnessMappingStrategy.INVALID_LUX);

        // Sensor reads 1000 lux,
        listener.onAmbientLuxChange(1000);
        // Do not fast-forward time.
        mTestLooper.dispatchAll();

        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT);
        // Do not fast-forward time
        mTestLooper.dispatchAll();

        // Ensure that there are no data points added, since the user has never adjusted the
        // brightness
        verify(mBrightnessMappingStrategy, times(0))
                .addUserDataPoint(anyFloat(), anyFloat());
    }

    @Test
    public void testSwitchToIdleMappingStrategy() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();
        clearInvocations(mBrightnessMappingStrategy);

        // Sensor reads 1000 lux,
        listener.onAmbientLuxChange(1000);


        verify(mBrightnessMappingStrategy).getBrightness(anyFloat(), any(), anyInt());

        clearInvocations(mBrightnessMappingStrategy);

        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                0.5f /* brightness= */, true /* userChangedBrightness= */, 0 /* adjustment= */,
                false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        // There should be a user data point added to the mapper.
        verify(mBrightnessMappingStrategy).addUserDataPoint(/* lux= */ 1000f,
                /* brightness= */ 0.5f);
        verify(mBrightnessMappingStrategy).setBrightnessConfiguration(any());
        verify(mBrightnessMappingStrategy).getBrightness(anyFloat(), any(), anyInt());

        clearInvocations(mBrightnessMappingStrategy);
        // Now let's do the same for idle mode
        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE);

        verify(mBrightnessMappingStrategy).getMode();
        verify(mBrightnessMappingStrategy).getShortTermModelTimeout();
        verify(mBrightnessMappingStrategy).getUserBrightness();
        verify(mBrightnessMappingStrategy).getUserLux();

        // Ensure, after switching, original BMS is not used anymore
        verifyNoMoreInteractions(mBrightnessMappingStrategy);

        // User sets idle brightness to 0.5
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                0.5f /* brightness= */, true /* userChangedBrightness= */, 0 /* adjustment= */,
                false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        // Ensure we use the correct mapping strategy
        verify(mIdleBrightnessMappingStrategy).addUserDataPoint(/* lux= */ 1000f,
                /* brightness= */ 0.5f);
    }

    @Test
    public void testBrightnessGetsThrottled() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Set up system to return max brightness at 100 lux
        final float normalizedBrightness = BRIGHTNESS_MAX_FLOAT;
        final float lux = 100.0f;
        when(mBrightnessMappingStrategy.getBrightness(eq(lux), eq(null), anyInt()))
                .thenReturn(normalizedBrightness);

        // Sensor reads 100 lux. We should get max brightness.
        listener.onAmbientLuxChange(lux);
        assertEquals(BRIGHTNESS_MAX_FLOAT, mController.getAutomaticScreenBrightness(), 0.0f);
        assertEquals(BRIGHTNESS_MAX_FLOAT, mController.getRawAutomaticScreenBrightness(), 0.0f);

        // Apply throttling and notify ABC (simulates DisplayPowerController#updatePowerState())
        final float throttledBrightness = 0.123f;
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(throttledBrightness);
        when(mBrightnessThrottler.isThrottled()).thenReturn(true);
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                BRIGHTNESS_MAX_FLOAT /* brightness= */, false /* userChangedBrightness= */,
                0 /* adjustment= */, false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT,
                Display.STATE_ON, /* shouldResetShortTermModel= */ true);
        assertEquals(throttledBrightness, mController.getAutomaticScreenBrightness(), 0.0f);
        // The raw brightness value should not have throttling applied
        assertEquals(BRIGHTNESS_MAX_FLOAT, mController.getRawAutomaticScreenBrightness(), 0.0f);

        // Remove throttling and notify ABC again
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);
        when(mBrightnessThrottler.isThrottled()).thenReturn(false);
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                BRIGHTNESS_MAX_FLOAT /* brightness= */, false /* userChangedBrightness= */,
                0 /* adjustment= */, false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT,
                Display.STATE_ON, /* shouldResetShortTermModel= */ true);
        assertEquals(BRIGHTNESS_MAX_FLOAT, mController.getAutomaticScreenBrightness(), 0.0f);
        assertEquals(BRIGHTNESS_MAX_FLOAT, mController.getRawAutomaticScreenBrightness(), 0.0f);
    }

    @Test
    public void testResetShortTermModelWhenConfigChanges() {
        when(mBrightnessMappingStrategy.setBrightnessConfiguration(any())).thenReturn(true);

        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                BRIGHTNESS_MAX_FLOAT /* brightness= */, false /* userChangedBrightness= */,
                0 /* adjustment= */, false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT,
                Display.STATE_ON, /* shouldResetShortTermModel= */ false);
        verify(mBrightnessMappingStrategy, never()).clearUserDataPoints();

        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                BRIGHTNESS_MAX_FLOAT /* brightness= */, false /* userChangedBrightness= */,
                0 /* adjustment= */, false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT,
                Display.STATE_ON, /* shouldResetShortTermModel= */ true);
        verify(mBrightnessMappingStrategy).clearUserDataPoints();
    }

    @Test
    public void testUseProvidedShortTermModel() {
        verify(mBrightnessMappingStrategy, never()).addUserDataPoint(anyFloat(), anyFloat());

        float userLux = 1000;
        float userNits = 500;
        float userBrightness = 0.3f;
        when(mBrightnessMappingStrategy.getBrightnessFromNits(userNits)).thenReturn(userBrightness);
        setupController(userLux, userNits);
        verify(mBrightnessMappingStrategy).addUserDataPoint(userLux, userBrightness);
    }

    @Test
    public void testBrightnessBasedOnLastObservedLux() throws Exception {
        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mLightSensorController.getLastObservedLux()).thenReturn(lux);
        when(mBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);

        // Send a new sensor value, disable the sensor and verify
        mController.configure(AUTO_BRIGHTNESS_DISABLED, /* configuration= */ null,
                /* brightness= */ 0, /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);
        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightnessBasedOnLastObservedLux(
                        /* brightnessEvent= */ null), EPSILON);
    }

    @Test
    public void testAutoBrightnessInDoze() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);
        when(mLightSensorController.getLastObservedLux()).thenReturn(lux);

        // Set policy to DOZE
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0, /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_DOZE, Display.STATE_DOZE,
                /* shouldResetShortTermModel= */ true);

        // Send a new sensor value
        listener.onAmbientLuxChange(lux);

        // The brightness should be scaled by the doze factor
        assertEquals(normalizedBrightness * DOZE_SCALE_FACTOR,
                mController.getAutomaticScreenBrightness(
                        /* brightnessEvent= */ null), EPSILON);
        assertEquals(normalizedBrightness * DOZE_SCALE_FACTOR,
                mController.getAutomaticScreenBrightnessBasedOnLastObservedLux(
                        /* brightnessEvent= */ null), EPSILON);
    }

    @Test
    public void testAutoBrightnessInDoze_ShouldNotScaleIfUsingDozeCurve() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mDozeBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);
        when(mLightSensorController.getLastObservedLux()).thenReturn(lux);

        // Switch mode to DOZE
        mController.switchMode(AUTO_BRIGHTNESS_MODE_DOZE);

        // Set policy to DOZE
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0, /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_DOZE, Display.STATE_DOZE,
                /* shouldResetShortTermModel= */ true);

        // Send a new sensor value
        listener.onAmbientLuxChange(lux);

        // The brightness should not be scaled by the doze factor
        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightness(/* brightnessEvent= */ null), EPSILON);
        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightnessBasedOnLastObservedLux(
                        /* brightnessEvent= */ null), EPSILON);
    }

    @Test
    public void testAutoBrightnessInDoze_ShouldNotScaleIfScreenOn() throws Exception {
        ArgumentCaptor<LightSensorController.LightSensorListener> listenerCaptor =
                ArgumentCaptor.forClass(LightSensorController.LightSensorListener.class);
        verify(mLightSensorController).setListener(listenerCaptor.capture());
        LightSensorController.LightSensorListener listener = listenerCaptor.getValue();

        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);
        when(mLightSensorController.getLastObservedLux()).thenReturn(lux);

        // Set policy to DOZE
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0, /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_DOZE, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        // Send a new sensor value
        listener.onAmbientLuxChange(lux);

        // The brightness should not be scaled by the doze factor
        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightness(/* brightnessEvent= */ null), EPSILON);
        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightnessBasedOnLastObservedLux(
                        /* brightnessEvent= */ null), EPSILON);
    }
}
