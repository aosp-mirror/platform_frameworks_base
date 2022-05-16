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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
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
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AutomaticBrightnessControllerTest {
    private static final float BRIGHTNESS_MIN_FLOAT = 0.0f;
    private static final float BRIGHTNESS_MAX_FLOAT = 1.0f;
    private static final int LIGHT_SENSOR_RATE = 20;
    private static final int INITIAL_LIGHT_SENSOR_RATE = 20;
    private static final int BRIGHTENING_LIGHT_DEBOUNCE_CONFIG = 0;
    private static final int DARKENING_LIGHT_DEBOUNCE_CONFIG = 0;
    private static final float DOZE_SCALE_FACTOR = 0.0f;
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
    @Mock HysteresisLevels mAmbientBrightnessThresholds;
    @Mock HysteresisLevels mScreenBrightnessThresholds;
    @Mock Handler mNoOpHandler;
    @Mock HighBrightnessModeController mHbmController;
    @Mock BrightnessThrottler mBrightnessThrottler;

    @Before
    public void setUp() throws Exception {
        // Share classloader to allow package private access.
        System.setProperty("dexmaker.share_classloader", "true");
        MockitoAnnotations.initMocks(this);

        mLightSensor = TestUtils.createSensor(Sensor.TYPE_LIGHT, "Light Sensor");
        mContext = InstrumentationRegistry.getContext();
        mController = setupController(mLightSensor);
    }

    @After
    public void tearDown() {
        if (mController != null) {
            // Stop the update Brightness loop.
            mController.stop();
            mController = null;
        }
    }

    private AutomaticBrightnessController setupController(Sensor lightSensor) {
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);

        AutomaticBrightnessController controller = new AutomaticBrightnessController(
                new AutomaticBrightnessController.Injector() {
                    @Override
                    public Handler getBackgroundThreadHandler() {
                        return mNoOpHandler;
                    }

                    @Override
                    AutomaticBrightnessController.Clock createClock() {
                        return mClock::now;
                    }

                }, // pass in test looper instead, pass in offsetable clock
                () -> { }, mTestLooper.getLooper(), mSensorManager, lightSensor,
                mBrightnessMappingStrategy, LIGHT_SENSOR_WARMUP_TIME, BRIGHTNESS_MIN_FLOAT,
                BRIGHTNESS_MAX_FLOAT, DOZE_SCALE_FACTOR, LIGHT_SENSOR_RATE,
                INITIAL_LIGHT_SENSOR_RATE, BRIGHTENING_LIGHT_DEBOUNCE_CONFIG,
                DARKENING_LIGHT_DEBOUNCE_CONFIG, RESET_AMBIENT_LUX_AFTER_WARMUP_CONFIG,
                mAmbientBrightnessThresholds, mScreenBrightnessThresholds,
                mContext, mHbmController, mBrightnessThrottler, mIdleBrightnessMappingStrategy,
                AMBIENT_LIGHT_HORIZON_SHORT, AMBIENT_LIGHT_HORIZON_LONG
        );

        when(mHbmController.getCurrentBrightnessMax()).thenReturn(BRIGHTNESS_MAX_FLOAT);
        when(mHbmController.getCurrentBrightnessMin()).thenReturn(BRIGHTNESS_MIN_FLOAT);
        // Disable brightness throttling by default. Individual tests can enable it as needed.
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);
        when(mBrightnessThrottler.isThrottled()).thenReturn(false);

        // Configure the brightness controller and grab an instance of the sensor listener,
        // through which we can deliver fake (for test) sensor values.
        controller.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration */,
                0 /* brightness */, false /* userChangedBrightness */, 0 /* adjustment */,
                false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);

        return controller;
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
        assertEquals(normalizedBrightness1, mController.getAutomaticScreenBrightness(), 0.001f);

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
        assertEquals(normalizedBrightness2, mController.getAutomaticScreenBrightness(), 0.001f);
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
        assertEquals(normalizedBrightness1, mController.getAutomaticScreenBrightness(), 0.001f);


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
        assertEquals(normalizedBrightness2, mController.getAutomaticScreenBrightness(), 0.001f);
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
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration */,
                0.5f /* brightness */, true /* userChangedBrightness */, 0 /* adjustment */,
                false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);

        // There should be a user data point added to the mapper.
        verify(mBrightnessMappingStrategy).addUserDataPoint(1000f, 0.5f);
    }

    @Test
    public void testRecalculateSplines() throws Exception {
        // Enabling the light sensor, and setting the ambient lux to 1000
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1000));

        //Setting the brightnessFloat to 0.5f
        float currentBrightnessFloat = 0.5f;
        when(mBrightnessMappingStrategy.getBrightness(1000,
                null, ApplicationInfo.CATEGORY_UNDEFINED)).thenReturn(currentBrightnessFloat);
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration */,
                currentBrightnessFloat /* brightness */, false /* userChangedBrightness */,
                0 /* adjustment */, false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);

        // Adjusting spline, and accordingly remapping the current 0.5f brightnessFloat to 0.3f
        float updatedBrightnessFloat = 0.3f;
        when(mBrightnessMappingStrategy.getBrightness(1000,
                null, ApplicationInfo.CATEGORY_UNDEFINED)).thenReturn(updatedBrightnessFloat);
        float[] adjustments = new float[]{0.2f, 0.5f};
        mController.recalculateSplines(true, adjustments);
        verify(mBrightnessMappingStrategy).recalculateSplines(true, adjustments);
        assertEquals(mController.getAutomaticScreenBrightness(), updatedBrightnessFloat, EPSILON);
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
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration */,
                0.5f /* brightness */, true /* userChangedBrightness */, 0 /* adjustment */,
                false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);

        // There should be a user data point added to the mapper.
        verify(mBrightnessMappingStrategy, times(1)).addUserDataPoint(1000f, 0.5f);
        verify(mBrightnessMappingStrategy, times(2)).setBrightnessConfiguration(any());
        verify(mBrightnessMappingStrategy, times(3)).getBrightness(anyFloat(), any(), anyInt());

        // Now let's do the same for idle mode
        mController.switchToIdleMode();
        // Called once for init, and once when switching
        verify(mBrightnessMappingStrategy, times(2)).isForIdleMode();
        // Ensure, after switching, original BMS is not used anymore
        verifyNoMoreInteractions(mBrightnessMappingStrategy);

        // User sets idle brightness to 0.5
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration */,
                0.5f /* brightness */, true /* userChangedBrightness */, 0 /* adjustment */,
                false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);

        // Ensure we use the correct mapping strategy
        verify(mIdleBrightnessMappingStrategy, times(1)).addUserDataPoint(1000f, 0.5f);
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
    }

    @Test
    public void testHysteresisLevels() {
        int[] ambientBrighteningThresholds = {100, 200};
        int[] ambientDarkeningThresholds = {400, 500};
        int[] ambientThresholdLevels = {500};
        float ambientDarkeningMinChangeThreshold = 3.0f;
        float ambientBrighteningMinChangeThreshold = 1.5f;
        HysteresisLevels hysteresisLevels = new HysteresisLevels(ambientBrighteningThresholds,
                ambientDarkeningThresholds, ambientThresholdLevels,
                ambientDarkeningMinChangeThreshold, ambientBrighteningMinChangeThreshold);

        // test low, activate minimum change thresholds.
        assertEquals(1.5f, hysteresisLevels.getBrighteningThreshold(0.0f), EPSILON);
        assertEquals(0f, hysteresisLevels.getDarkeningThreshold(0.0f), EPSILON);
        assertEquals(1f, hysteresisLevels.getDarkeningThreshold(4.0f), EPSILON);

        // test max
        assertEquals(12000f, hysteresisLevels.getBrighteningThreshold(10000.0f), EPSILON);
        assertEquals(5000f, hysteresisLevels.getDarkeningThreshold(10000.0f), EPSILON);

        // test just below threshold
        assertEquals(548.9f, hysteresisLevels.getBrighteningThreshold(499f), EPSILON);
        assertEquals(299.4f, hysteresisLevels.getDarkeningThreshold(499f), EPSILON);

        // test at (considered above) threshold
        assertEquals(600f, hysteresisLevels.getBrighteningThreshold(500f), EPSILON);
        assertEquals(250f, hysteresisLevels.getDarkeningThreshold(500f), EPSILON);
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

        // Apply throttling and notify ABC (simulates DisplayPowerController#updatePowerState())
        final float throttledBrightness = 0.123f;
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(throttledBrightness);
        when(mBrightnessThrottler.isThrottled()).thenReturn(true);
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration */,
                BRIGHTNESS_MAX_FLOAT /* brightness */, false /* userChangedBrightness */,
                0 /* adjustment */, false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);
        assertEquals(throttledBrightness, mController.getAutomaticScreenBrightness(), 0.0f);

        // Remove throttling and notify ABC again
        when(mBrightnessThrottler.getBrightnessCap()).thenReturn(BRIGHTNESS_MAX_FLOAT);
        when(mBrightnessThrottler.isThrottled()).thenReturn(false);
        mController.configure(AUTO_BRIGHTNESS_ENABLED, null /* configuration */,
                BRIGHTNESS_MAX_FLOAT /* brightness */, false /* userChangedBrightness */,
                0 /* adjustment */, false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);
        assertEquals(BRIGHTNESS_MAX_FLOAT, mController.getAutomaticScreenBrightness(), 0.0f);
    }
}
