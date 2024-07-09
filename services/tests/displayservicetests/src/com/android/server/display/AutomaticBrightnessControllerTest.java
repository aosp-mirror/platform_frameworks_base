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

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DOZE;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.test.TestLooper;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.config.HysteresisLevels;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AutomaticBrightnessControllerTest {
    private static final int ANDROID_SLEEP_TIME = 1000;
    private static final int NANO_SECONDS_MULTIPLIER = 1000000;
    private static final float BRIGHTNESS_MIN_FLOAT = 0.0f;
    private static final float BRIGHTNESS_MAX_FLOAT = 1.0f;
    private static final int LIGHT_SENSOR_RATE = 20;
    private static final int INITIAL_LIGHT_SENSOR_RATE = 20;
    private static final int BRIGHTENING_LIGHT_DEBOUNCE_CONFIG = 2000;
    private static final int DARKENING_LIGHT_DEBOUNCE_CONFIG = 4000;
    private static final int BRIGHTENING_LIGHT_DEBOUNCE_CONFIG_IDLE = 1000;
    private static final int DARKENING_LIGHT_DEBOUNCE_CONFIG_IDLE = 2000;
    private static final float DOZE_SCALE_FACTOR = 0.54f;
    private static final boolean RESET_AMBIENT_LUX_AFTER_WARMUP_CONFIG = false;
    private static final int LIGHT_SENSOR_WARMUP_TIME = 0;
    private static final int AMBIENT_LIGHT_HORIZON_SHORT = 1000;
    private static final int AMBIENT_LIGHT_HORIZON_LONG = 2000;
    private static final float EPSILON = 0.001f;
    private OffsettableClock mClock = new OffsettableClock();
    private TestLooper mTestLooper;
    private Context mContext;
    private AutomaticBrightnessController mController;
    private Sensor mLightSensor;

    @Mock SensorManager mSensorManager;
    @Mock BrightnessMappingStrategy mBrightnessMappingStrategy;
    @Mock BrightnessMappingStrategy mIdleBrightnessMappingStrategy;
    @Mock BrightnessMappingStrategy mDozeBrightnessMappingStrategy;
    @Mock HysteresisLevels mAmbientBrightnessThresholds;
    @Mock HysteresisLevels mScreenBrightnessThresholds;
    @Mock HysteresisLevels mAmbientBrightnessThresholdsIdle;
    @Mock HysteresisLevels mScreenBrightnessThresholdsIdle;
    @Mock Handler mNoOpHandler;
    @Mock BrightnessRangeController mBrightnessRangeController;
    @Mock
    DisplayManagerFlags mDisplayManagerFlags;
    @Mock BrightnessThrottler mBrightnessThrottler;

    @Before
    public void setUp() throws Exception {
        // Share classloader to allow package private access.
        System.setProperty("dexmaker.share_classloader", "true");
        MockitoAnnotations.initMocks(this);

        mLightSensor = TestUtils.createSensor(Sensor.TYPE_LIGHT, "Light Sensor");
        mContext = InstrumentationRegistry.getContext();
        setupController(BrightnessMappingStrategy.INVALID_LUX,
                BrightnessMappingStrategy.INVALID_NITS, /* applyDebounce= */ false,
                /* useHorizon= */ true);
    }

    @After
    public void tearDown() {
        if (mController != null) {
            // Stop the update Brightness loop.
            mController.stop();
            mController = null;
        }
    }

    private void setupController(float userLux, float userNits, boolean applyDebounce,
            boolean useHorizon) {
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
                    AutomaticBrightnessController.Clock createClock(boolean isEnabled) {
                        return new AutomaticBrightnessController.Clock() {
                            @Override
                            public long uptimeMillis() {
                                return mClock.now();
                            }

                            @Override
                            public long getSensorEventScaleTime() {
                                return mClock.now() + ANDROID_SLEEP_TIME;
                            }
                        };
                    }

                }, // pass in test looper instead, pass in offsettable clock
                () -> { }, mTestLooper.getLooper(), mSensorManager, mLightSensor,
                brightnessMappingStrategyMap, LIGHT_SENSOR_WARMUP_TIME, BRIGHTNESS_MIN_FLOAT,
                BRIGHTNESS_MAX_FLOAT, DOZE_SCALE_FACTOR, LIGHT_SENSOR_RATE,
                INITIAL_LIGHT_SENSOR_RATE, applyDebounce ? BRIGHTENING_LIGHT_DEBOUNCE_CONFIG : 0,
                applyDebounce ? DARKENING_LIGHT_DEBOUNCE_CONFIG : 0,
                applyDebounce ? BRIGHTENING_LIGHT_DEBOUNCE_CONFIG_IDLE : 0,
                applyDebounce ? DARKENING_LIGHT_DEBOUNCE_CONFIG_IDLE : 0,
                RESET_AMBIENT_LUX_AFTER_WARMUP_CONFIG,
                mAmbientBrightnessThresholds, mScreenBrightnessThresholds,
                mAmbientBrightnessThresholdsIdle, mScreenBrightnessThresholdsIdle,
                mContext, mBrightnessRangeController, mBrightnessThrottler,
                useHorizon ? AMBIENT_LIGHT_HORIZON_SHORT : 1,
                useHorizon ? AMBIENT_LIGHT_HORIZON_LONG : 10000, userLux, userNits,
                mDisplayManagerFlags
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
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.02f as a brightness value
        float lux1 = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness1 = 0.02f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux1))
                .thenReturn(lux1);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux1))
                .thenReturn(lux1);
        when(mBrightnessMappingStrategy.getBrightness(eq(lux1), eq(null), anyInt()))
                .thenReturn(normalizedBrightness1);

        // This is the important bit: When the new brightness is set, make sure the new
        // brightening threshold is beyond the maximum brightness value...so that we can test that
        // our threshold clamping works.
        when(mScreenBrightnessThresholds.getBrighteningThreshold(normalizedBrightness1))
                .thenReturn(1.0f);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux1));
        assertEquals(normalizedBrightness1, mController.getAutomaticScreenBrightness(), EPSILON);

        // Set up system to return 0.0f (minimum possible brightness) as a brightness value
        float lux2 = 10.0f;
        float normalizedBrightness2 = 0.0f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux2))
                .thenReturn(lux2);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux2))
                .thenReturn(lux2);
        when(mBrightnessMappingStrategy.getBrightness(anyFloat(), eq(null), anyInt()))
                .thenReturn(normalizedBrightness2);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux2));
        assertEquals(normalizedBrightness2, mController.getAutomaticScreenBrightness(), EPSILON);
    }

    @Test
    public void testNoHysteresisAtMaxBrightness() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.98f as a brightness value
        float lux1 = 100.0f;
        float normalizedBrightness1 = 0.98f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux1))
                .thenReturn(lux1);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux1))
                .thenReturn(lux1);
        when(mBrightnessMappingStrategy.getBrightness(eq(lux1), eq(null), anyInt()))
                .thenReturn(normalizedBrightness1);

        // This is the important bit: When the new brightness is set, make sure the new
        // brightening threshold is beyond the maximum brightness value...so that we can test that
        // our threshold clamping works.
        when(mScreenBrightnessThresholds.getBrighteningThreshold(normalizedBrightness1))
                .thenReturn(1.1f);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux1));
        assertEquals(normalizedBrightness1, mController.getAutomaticScreenBrightness(), EPSILON);


        // Set up system to return 1.0f as a brightness value (brightness_max)
        float lux2 = 110.0f;
        float normalizedBrightness2 = 1.0f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux2))
                .thenReturn(lux2);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux2))
                .thenReturn(lux2);
        when(mBrightnessMappingStrategy.getBrightness(anyFloat(), eq(null), anyInt()))
                .thenReturn(normalizedBrightness2);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux2));
        assertEquals(normalizedBrightness2, mController.getAutomaticScreenBrightness(), EPSILON);
    }

    @Test
    public void testUserAddUserDataPoint() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1000));

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
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, currentLux));

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
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 123));
        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0.5f, /* userChangedBrightness= */ true, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE, /* sendUpdate= */ true);
        when(mBrightnessMappingStrategy.shouldResetShortTermModel(
                123f, 0.5f)).thenReturn(true);

        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1000));
        mTestLooper.moveTimeForward(
                mBrightnessMappingStrategy.getShortTermModelTimeout() + 1000);
        mTestLooper.dispatchAll();

        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT, /* sendUpdate= */ true);
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
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 123));
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

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE, /* sendUpdate= */ true);

        // Time does not move forward, since clock is doesn't increment naturally.
        mTestLooper.dispatchAll();

        // Sensor reads 100000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 678910));
        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT, /* sendUpdate= */ true);

        // Verify short term model is not reset.
        verify(mBrightnessMappingStrategy, never()).clearUserDataPoints();

        // Verify that we add the data point once when the user sets it, and again when we return
        // interactive mode.
        verify(mBrightnessMappingStrategy, times(2))
                .addUserDataPoint(/* lux= */ 123.0f, /* brightness= */ 0.51f);
    }

    @Test
    public void testShortTermModelIsRestoredWhenSwitchingWithinTimeout() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 123));
        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0.5f, /* userChangedBrightness= */ true, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);
        when(mBrightnessMappingStrategy.getUserBrightness()).thenReturn(0.5f);
        when(mBrightnessMappingStrategy.getUserLux()).thenReturn(123f);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE, /* sendUpdate= */ true);
        when(mIdleBrightnessMappingStrategy.getUserBrightness()).thenReturn(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mIdleBrightnessMappingStrategy.getUserLux()).thenReturn(
                BrightnessMappingStrategy.INVALID_LUX);
        when(mBrightnessMappingStrategy.shouldResetShortTermModel(
                123f, 0.5f)).thenReturn(true);

        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1000));
        mTestLooper.moveTimeForward(
                mBrightnessMappingStrategy.getShortTermModelTimeout() + 1000);
        mTestLooper.dispatchAll();

        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT, /* sendUpdate= */ true);
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
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 123));
        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0.5f, /* userChangedBrightness= */ true, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);

        when(mBrightnessMappingStrategy.getUserBrightness()).thenReturn(0.5f);
        when(mBrightnessMappingStrategy.getUserLux()).thenReturn(123f);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE, /* sendUpdate= */ true);
        when(mIdleBrightnessMappingStrategy.getUserBrightness()).thenReturn(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mIdleBrightnessMappingStrategy.getUserLux()).thenReturn(
                BrightnessMappingStrategy.INVALID_LUX);

        when(mBrightnessMappingStrategy.shouldResetShortTermModel(
                123f, 0.5f)).thenReturn(true);

        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1000));
        // Do not fast-forward time.
        mTestLooper.dispatchAll();

        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT, /* sendUpdate= */ true);
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
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Sensor reads 123 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 123));
        when(mBrightnessMappingStrategy.getShortTermModelTimeout()).thenReturn(2000L);
        when(mBrightnessMappingStrategy.getUserBrightness()).thenReturn(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mBrightnessMappingStrategy.getUserLux()).thenReturn(
                BrightnessMappingStrategy.INVALID_LUX);

        // No user brightness interaction.

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE, /* sendUpdate= */ true);
        when(mIdleBrightnessMappingStrategy.getUserBrightness()).thenReturn(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mIdleBrightnessMappingStrategy.getUserLux()).thenReturn(
                BrightnessMappingStrategy.INVALID_LUX);

        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1000));
        // Do not fast-forward time.
        mTestLooper.dispatchAll();

        mController.switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT, /* sendUpdate= */ true);
        // Do not fast-forward time
        mTestLooper.dispatchAll();

        // Ensure that there are no data points added, since the user has never adjusted the
        // brightness
        verify(mBrightnessMappingStrategy, times(0))
                .addUserDataPoint(anyFloat(), anyFloat());
    }

    @Test
    public void testSwitchToIdleMappingStrategy() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1000));

        // User sets brightness to 100
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                0.5f /* brightness= */, true /* userChangedBrightness= */, 0 /* adjustment= */,
                false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        // There should be a user data point added to the mapper.
        verify(mBrightnessMappingStrategy, times(1)).addUserDataPoint(/* lux= */ 1000f,
                /* brightness= */ 0.5f);
        verify(mBrightnessMappingStrategy, times(2)).setBrightnessConfiguration(any());
        verify(mBrightnessMappingStrategy, times(3)).getBrightness(anyFloat(), any(), anyInt());

        // Now let's do the same for idle mode
        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE, /* sendUpdate= */ true);
        // Called once when switching,
        // setAmbientLux() is called twice and once in updateAutoBrightness(),
        // nextAmbientLightBrighteningTransition() and nextAmbientLightDarkeningTransition() are
        // called twice each.
        verify(mBrightnessMappingStrategy, times(8)).getMode();
        // Called when switching.
        verify(mBrightnessMappingStrategy, times(1)).getShortTermModelTimeout();
        verify(mBrightnessMappingStrategy, times(1)).getUserBrightness();
        verify(mBrightnessMappingStrategy, times(1)).getUserLux();

        // Ensure, after switching, original BMS is not used anymore
        verifyNoMoreInteractions(mBrightnessMappingStrategy);

        // User sets idle brightness to 0.5
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration= */,
                0.5f /* brightness= */, true /* userChangedBrightness= */, 0 /* adjustment= */,
                false /* userChanged= */, DisplayPowerRequest.POLICY_BRIGHT, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        // Ensure we use the correct mapping strategy
        verify(mIdleBrightnessMappingStrategy, times(1)).addUserDataPoint(/* lux= */ 1000f,
                /* brightness= */ 0.5f);
    }

    @Test
    public void testAmbientLightHorizon() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        long increment = 500;
        // set autobrightness to low
        // t = 0
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));

        // t = 500
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));

        // t = 1000
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));
        assertEquals(0.0f, mController.getAmbientLux(), EPSILON);

        // t = 1500
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));
        assertEquals(0.0f, mController.getAmbientLux(), EPSILON);

        // t = 2000
        // ensure that our reading is at 0.
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));
        assertEquals(0.0f, mController.getAmbientLux(), EPSILON);

        // t = 2500
        // first 10000 lux sensor event reading
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 10000));
        assertTrue(mController.getAmbientLux() > 0.0f);
        assertTrue(mController.getAmbientLux() < 10000.0f);

        // t = 3000
        // lux reading should still not yet be 10000.
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 10000));
        assertTrue(mController.getAmbientLux() > 0.0f);
        assertTrue(mController.getAmbientLux() < 10000.0f);

        // t = 3500
        mClock.fastForward(increment);
        // lux has been high (10000) for 1000ms.
        // lux reading should be 10000
        // short horizon (ambient lux) is high, long horizon is still not high
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 10000));
        assertEquals(10000.0f, mController.getAmbientLux(), EPSILON);

        // t = 4000
        // stay high
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 10000));
        assertEquals(10000.0f, mController.getAmbientLux(), EPSILON);

        // t = 4500
        Mockito.clearInvocations(mBrightnessMappingStrategy);
        mClock.fastForward(increment);
        // short horizon is high, long horizon is high too
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 10000));
        verify(mBrightnessMappingStrategy, times(1)).getBrightness(10000, null, -1);
        assertEquals(10000.0f, mController.getAmbientLux(), EPSILON);

        // t = 5000
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));
        assertTrue(mController.getAmbientLux() > 0.0f);
        assertTrue(mController.getAmbientLux() < 10000.0f);

        // t = 5500
        mClock.fastForward(increment);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));
        assertTrue(mController.getAmbientLux() > 0.0f);
        assertTrue(mController.getAmbientLux() < 10000.0f);

        // t = 6000
        mClock.fastForward(increment);
        // ambient lux goes to 0
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));
        assertEquals(0.0f, mController.getAmbientLux(), EPSILON);

        // only the values within the horizon should be kept
        assertArrayEquals(new float[] {10000, 10000, 0, 0, 0}, mController.getLastSensorValues(),
                EPSILON);
        assertArrayEquals(new long[] {4000, 4500, 5000, 5500, 6000},
                mController.getLastSensorTimestamps());
    }

    @Test
    public void testHysteresisLevels() {
        float[] ambientBrighteningThresholds = {50, 100};
        float[] ambientDarkeningThresholds = {10, 20};
        float[] ambientThresholdLevels = {0, 500};
        float ambientDarkeningMinChangeThreshold = 3.0f;
        float ambientBrighteningMinChangeThreshold = 1.5f;
        HysteresisLevels hysteresisLevels = new HysteresisLevels(ambientBrighteningThresholds,
                ambientDarkeningThresholds, ambientThresholdLevels, ambientThresholdLevels,
                ambientDarkeningMinChangeThreshold, ambientBrighteningMinChangeThreshold);

        // test low, activate minimum change thresholds.
        assertEquals(1.5f, hysteresisLevels.getBrighteningThreshold(0.0f), EPSILON);
        assertEquals(0f, hysteresisLevels.getDarkeningThreshold(0.0f), EPSILON);
        assertEquals(1f, hysteresisLevels.getDarkeningThreshold(4.0f), EPSILON);

        // test max
        // epsilon is x2 here, since the next floating point value about 20,000 is 0.0019531 greater
        assertEquals(20000f, hysteresisLevels.getBrighteningThreshold(10000.0f), EPSILON * 2);
        assertEquals(8000f, hysteresisLevels.getDarkeningThreshold(10000.0f), EPSILON);

        // test just below threshold
        assertEquals(748.5f, hysteresisLevels.getBrighteningThreshold(499f), EPSILON);
        assertEquals(449.1f, hysteresisLevels.getDarkeningThreshold(499f), EPSILON);

        // test at (considered above) threshold
        assertEquals(1000f, hysteresisLevels.getBrighteningThreshold(500f), EPSILON);
        assertEquals(400f, hysteresisLevels.getDarkeningThreshold(500f), EPSILON);
    }

    @Test
    public void testBrightnessGetsThrottled() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return max brightness at 100 lux
        final float normalizedBrightness = BRIGHTNESS_MAX_FLOAT;
        final float lux = 100.0f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux))
                .thenReturn(lux);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux))
                .thenReturn(lux);
        when(mBrightnessMappingStrategy.getBrightness(eq(lux), eq(null), anyInt()))
                .thenReturn(normalizedBrightness);

        // Sensor reads 100 lux. We should get max brightness.
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux));
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
    public void testGetSensorReadings() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Choose values such that the ring buffer's capacity is extended and the buffer is pruned
        int increment = 11;
        int lux = 5000;
        for (int i = 0; i < 1000; i++) {
            lux += increment;
            mClock.fastForward(increment);
            listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, lux));
        }

        int valuesCount = (int) Math.ceil((double) AMBIENT_LIGHT_HORIZON_LONG / increment + 1);
        float[] sensorValues = mController.getLastSensorValues();
        long[] sensorTimestamps = mController.getLastSensorTimestamps();

        // Only the values within the horizon should be kept
        assertEquals(valuesCount, sensorValues.length);
        assertEquals(valuesCount, sensorTimestamps.length);

        long sensorTimestamp = mClock.now();
        for (int i = valuesCount - 1; i >= 1; i--) {
            assertEquals(lux, sensorValues[i], EPSILON);
            assertEquals(sensorTimestamp, sensorTimestamps[i]);
            lux -= increment;
            sensorTimestamp -= increment;
        }
        assertEquals(lux, sensorValues[0], EPSILON);
        assertEquals(mClock.now() - AMBIENT_LIGHT_HORIZON_LONG, sensorTimestamps[0]);
    }

    @Test
    public void testAmbientLuxBuffers_prunedBeyondLongHorizonExceptLatestValue() throws Exception {
        when(mDisplayManagerFlags.offloadControlsDozeAutoBrightness()).thenReturn(true);
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Choose values such that the ring buffer's capacity is extended and the buffer is pruned
        int increment = 11;
        int lux = 5000;
        for (int i = 0; i < 1000; i++) {
            lux += increment;
            mClock.fastForward(increment);
            listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, lux,
                    (mClock.now() + ANDROID_SLEEP_TIME) * NANO_SECONDS_MULTIPLIER));
        }
        mClock.fastForward(AMBIENT_LIGHT_HORIZON_LONG + 10);
        int newLux = 2000;
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, newLux,
                (mClock.now() + ANDROID_SLEEP_TIME) * NANO_SECONDS_MULTIPLIER));

        float[] sensorValues = mController.getLastSensorValues();
        long[] sensorTimestamps = mController.getLastSensorTimestamps();
        // Only the values within the horizon should be kept
        assertEquals(2, sensorValues.length);
        assertEquals(2, sensorTimestamps.length);

        assertEquals(lux, sensorValues[0], EPSILON);
        assertEquals(newLux, sensorValues[1], EPSILON);
        assertEquals(mClock.now() + ANDROID_SLEEP_TIME - AMBIENT_LIGHT_HORIZON_LONG,
                sensorTimestamps[0]);
        assertEquals(mClock.now() + ANDROID_SLEEP_TIME,
                sensorTimestamps[1]);
    }

    @Test
    public void testGetSensorReadingsFullBuffer() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();
        int initialCapacity = 150;

        // Choose values such that the ring buffer is pruned
        int increment1 = 200;
        int lux = 5000;
        for (int i = 0; i < 20; i++) {
            lux += increment1;
            mClock.fastForward(increment1);
            listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, lux));
        }

        int valuesCount = (int) Math.ceil((double) AMBIENT_LIGHT_HORIZON_LONG / increment1 + 1);

        // Choose values such that the buffer becomes full
        int increment2 = 1;
        for (int i = 0; i < initialCapacity - valuesCount; i++) {
            lux += increment2;
            mClock.fastForward(increment2);
            listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, lux));
        }

        float[] sensorValues = mController.getLastSensorValues();
        long[] sensorTimestamps = mController.getLastSensorTimestamps();

        // The buffer should be full
        assertEquals(initialCapacity, sensorValues.length);
        assertEquals(initialCapacity, sensorTimestamps.length);

        long sensorTimestamp = mClock.now();
        for (int i = initialCapacity - 1; i >= 1; i--) {
            assertEquals(lux, sensorValues[i], EPSILON);
            assertEquals(sensorTimestamp, sensorTimestamps[i]);

            if (i >= valuesCount) {
                lux -= increment2;
                sensorTimestamp -= increment2;
            } else {
                lux -= increment1;
                sensorTimestamp -= increment1;
            }
        }
        assertEquals(lux, sensorValues[0], EPSILON);
        assertEquals(mClock.now() - AMBIENT_LIGHT_HORIZON_LONG, sensorTimestamps[0]);
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
        setupController(userLux, userNits, /* applyDebounce= */ true,
                /* useHorizon= */ false);
        verify(mBrightnessMappingStrategy).addUserDataPoint(userLux, userBrightness);
    }

    @Test
    public void testBrighteningLightDebounce() throws Exception {
        clearInvocations(mSensorManager);
        setupController(BrightnessMappingStrategy.INVALID_LUX,
                BrightnessMappingStrategy.INVALID_NITS, /* applyDebounce= */ true,
                /* useHorizon= */ false);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // t = 0
        // Initial lux
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 500));
        assertEquals(500, mController.getAmbientLux(), EPSILON);

        // t = 1000
        // Lux isn't steady yet
        mClock.fastForward(1000);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1200));
        assertEquals(500, mController.getAmbientLux(), EPSILON);

        // t = 1500
        // Lux isn't steady yet
        mClock.fastForward(500);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1200));
        assertEquals(500, mController.getAmbientLux(), EPSILON);

        // t = 2500
        // Lux is steady now
        mClock.fastForward(1000);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1200));
        assertEquals(1200, mController.getAmbientLux(), EPSILON);
    }

    @Test
    public void testDarkeningLightDebounce() throws Exception {
        clearInvocations(mSensorManager);
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(anyFloat()))
                .thenReturn(10000f);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(anyFloat()))
                .thenReturn(10000f);
        setupController(BrightnessMappingStrategy.INVALID_LUX,
                BrightnessMappingStrategy.INVALID_NITS, /* applyDebounce= */ true,
                /* useHorizon= */ false);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // t = 0
        // Initial lux
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1200));
        assertEquals(1200, mController.getAmbientLux(), EPSILON);

        // t = 2000
        // Lux isn't steady yet
        mClock.fastForward(2000);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 500));
        assertEquals(1200, mController.getAmbientLux(), EPSILON);

        // t = 2500
        // Lux isn't steady yet
        mClock.fastForward(500);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 500));
        assertEquals(1200, mController.getAmbientLux(), EPSILON);

        // t = 4500
        // Lux is steady now
        mClock.fastForward(2000);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 500));
        assertEquals(500, mController.getAmbientLux(), EPSILON);
    }

    @Test
    public void testBrighteningLightDebounceIdle() throws Exception {
        clearInvocations(mSensorManager);
        setupController(BrightnessMappingStrategy.INVALID_LUX,
                BrightnessMappingStrategy.INVALID_NITS, /* applyDebounce= */ true,
                /* useHorizon= */ false);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE, /* sendUpdate= */ true);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // t = 0
        // Initial lux
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 500));
        assertEquals(500, mController.getAmbientLux(), EPSILON);

        // t = 500
        // Lux isn't steady yet
        mClock.fastForward(500);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1200));
        assertEquals(500, mController.getAmbientLux(), EPSILON);

        // t = 1500
        // Lux is steady now
        mClock.fastForward(1000);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1200));
        assertEquals(1200, mController.getAmbientLux(), EPSILON);
    }

    @Test
    public void testDarkeningLightDebounceIdle() throws Exception {
        clearInvocations(mSensorManager);
        when(mAmbientBrightnessThresholdsIdle.getBrighteningThreshold(anyFloat()))
                .thenReturn(10000f);
        when(mAmbientBrightnessThresholdsIdle.getDarkeningThreshold(anyFloat()))
                .thenReturn(10000f);
        setupController(BrightnessMappingStrategy.INVALID_LUX,
                BrightnessMappingStrategy.INVALID_NITS, /* applyDebounce= */ true,
                /* useHorizon= */ false);

        mController.switchMode(AUTO_BRIGHTNESS_MODE_IDLE, /* sendUpdate= */ true);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // t = 0
        // Initial lux
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1200));
        assertEquals(1200, mController.getAmbientLux(), EPSILON);

        // t = 1000
        // Lux isn't steady yet
        mClock.fastForward(1000);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 500));
        assertEquals(1200, mController.getAmbientLux(), EPSILON);

        // t = 2500
        // Lux is steady now
        mClock.fastForward(1500);
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 500));
        assertEquals(500, mController.getAmbientLux(), EPSILON);
    }

    @Test
    public void testAutoBrightnessInDoze() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux)).thenReturn(lux);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux)).thenReturn(lux);
        when(mBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);

        // Set policy to DOZE
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0, /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_DOZE, Display.STATE_DOZE,
                /* shouldResetShortTermModel= */ true);

        // Send a new sensor value
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux));

        // The brightness should be scaled by the doze factor
        assertEquals(normalizedBrightness * DOZE_SCALE_FACTOR,
                mController.getAutomaticScreenBrightness(
                        /* brightnessEvent= */ null), EPSILON);
    }

    @Test
    public void testAutoBrightnessInDoze_ShouldNotScaleIfUsingDozeCurve() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux)).thenReturn(lux);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux)).thenReturn(lux);
        when(mDozeBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);

        // Switch mode to DOZE
        mController.switchMode(AUTO_BRIGHTNESS_MODE_DOZE, /* sendUpdate= */ false);

        // Set policy to DOZE
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0, /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_DOZE, Display.STATE_DOZE,
                /* shouldResetShortTermModel= */ true);

        // Send a new sensor value
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux));

        // The brightness should not be scaled by the doze factor
        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightness(/* brightnessEvent= */ null), EPSILON);
    }

    @Test
    public void testAutoBrightnessInDoze_ShouldNotScaleIfScreenOn() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux)).thenReturn(lux);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux)).thenReturn(lux);
        when(mBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);

        // Set policy to DOZE
        mController.configure(AUTO_BRIGHTNESS_ENABLED, /* configuration= */ null,
                /* brightness= */ 0, /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChanged= */ false, DisplayPowerRequest.POLICY_DOZE, Display.STATE_ON,
                /* shouldResetShortTermModel= */ true);

        // Send a new sensor value
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux));

        // The brightness should not be scaled by the doze factor
        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightness(/* brightnessEvent= */ null), EPSILON);
    }

    @Test
    public void testSwitchMode_UpdateBrightnessImmediately() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux)).thenReturn(lux);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux)).thenReturn(lux);
        when(mDozeBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);

        // Send a new sensor value
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux));

        // Switch mode to DOZE
        mController.switchMode(AUTO_BRIGHTNESS_MODE_DOZE, /* sendUpdate= */ false);

        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightness(/* brightnessEvent= */ null), EPSILON);
    }

    @Test
    public void testSwitchMode_UpdateBrightnessInBackground() throws Exception {
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.3f as a brightness value
        float lux = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness = 0.3f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux)).thenReturn(lux);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux)).thenReturn(lux);
        when(mDozeBrightnessMappingStrategy.getBrightness(eq(lux), /* packageName= */ eq(null),
                /* category= */ anyInt())).thenReturn(normalizedBrightness);
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);

        // Send a new sensor value
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, (int) lux));

        // Switch mode to DOZE
        mController.switchMode(AUTO_BRIGHTNESS_MODE_DOZE, /* sendUpdate= */ true);
        mClock.fastForward(SystemClock.uptimeMillis());
        mTestLooper.dispatchAll();

        assertEquals(normalizedBrightness,
                mController.getAutomaticScreenBrightness(/* brightnessEvent= */ null), EPSILON);
    }
}
