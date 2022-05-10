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

package com.android.server.power;

import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;

import static com.android.server.power.FaceDownDetector.KEY_FEATURE_ENABLED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.provider.DeviceConfig;
import android.testing.TestableContext;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.modules.utils.testing.TestableDeviceConfig;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FaceDownDetectorTest {
    @ClassRule
    public static final TestableContext sContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    private final FaceDownDetector mFaceDownDetector = new FaceDownDetector(this::onFlip);

    @Mock private SensorManager mSensorManager;
    @Mock private PowerManager mPowerManager;

    private Duration mCurrentTime;
    private int mOnFaceDownCalls;
    private int mOnFaceDownExitCalls;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        sContext.addMockSystemService(SensorManager.class, mSensorManager);
        sContext.addMockSystemService(PowerManager.class, mPowerManager);
        doReturn(true).when(mPowerManager).isInteractive();
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_FEATURE_ENABLED, "true", false);
        mCurrentTime = Duration.ZERO;
        mOnFaceDownCalls = 0;
        mOnFaceDownExitCalls = 0;
    }

    @Test
    public void faceDownFor2Seconds_triggersFaceDown() throws Exception {
        mFaceDownDetector.systemReady(sContext);

        triggerFaceDown();

        assertThat(mOnFaceDownCalls).isEqualTo(1);
        assertThat(mOnFaceDownExitCalls).isEqualTo(0);
    }

    @Test
    public void faceDownFor2Seconds_withMotion_DoesNotTriggerFaceDown() throws Exception {
        mFaceDownDetector.systemReady(sContext);

        // Face up
        mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, 10.0f));

        for (int i = 0; i < 100; i++) {
            advanceTime(Duration.ofMillis(20));
            //Move along x direction
            mFaceDownDetector.onSensorChanged(createTestEvent(0.5f * i, 0.0f, -10.0f));
        }

        assertThat(mOnFaceDownCalls).isEqualTo(0);
        assertThat(mOnFaceDownExitCalls).isEqualTo(0);
    }

    @Test
    public void faceDownForHalfSecond_DoesNotTriggerFaceDown() throws Exception {
        mFaceDownDetector.systemReady(sContext);

        // Face up
        mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, 10.0f));

        for (int i = 0; i < 100; i++) {
            advanceTime(Duration.ofMillis(5));
            mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, -10.0f));
        }

        assertThat(mOnFaceDownCalls).isEqualTo(0);
        assertThat(mOnFaceDownExitCalls).isEqualTo(0);
    }

    @Test
    public void faceDownFor2Seconds_followedByFaceUp_triggersFaceDownExit() throws Exception {
        mFaceDownDetector.systemReady(sContext);

        triggerFaceDown();

        // Phone flips
        triggerUnflip();

        assertThat(mOnFaceDownCalls).isEqualTo(1);
        assertThat(mOnFaceDownExitCalls).isEqualTo(1);
    }

    @Test
    public void notInteractive_doesNotTriggerFaceDown() throws Exception {
        doReturn(false).when(mPowerManager).isInteractive();
        mFaceDownDetector.systemReady(sContext);

        triggerFaceDown();

        assertThat(mOnFaceDownCalls).isEqualTo(0);
        assertThat(mOnFaceDownExitCalls).isEqualTo(0);
    }

    @Test
    public void afterDisablingFeature_doesNotTriggerFaceDown() throws Exception {
        mFaceDownDetector.systemReady(sContext);
        setEnabled(false);

        triggerFaceDown();

        assertThat(mOnFaceDownCalls).isEqualTo(0);
    }

    @Test
    public void afterReenablingWhileNonInteractive_doesNotTriggerFaceDown() throws Exception {
        mFaceDownDetector.systemReady(sContext);
        setEnabled(false);

        doReturn(false).when(mPowerManager).isInteractive();
        setEnabled(true);

        triggerFaceDown();

        assertThat(mOnFaceDownCalls).isEqualTo(0);
    }

    @Test
    public void afterReenablingWhileInteractive_doesTriggerFaceDown() throws Exception {
        mFaceDownDetector.systemReady(sContext);
        setEnabled(false);

        setEnabled(true);

        triggerFaceDown();

        assertThat(mOnFaceDownCalls).isEqualTo(1);
    }

    @Test
    public void faceDownToScreenOff_followedByScreenOnAndUserInteraction_doesNotDisable()
            throws Exception {
        mFaceDownDetector.systemReady(sContext);
        // Face down to screen off
        triggerFaceDown();
        mFaceDownDetector.mScreenReceiver.onReceive(sContext, new Intent(Intent.ACTION_SCREEN_OFF));

        // Screen on
        mFaceDownDetector.mScreenReceiver.onReceive(sContext, new Intent(Intent.ACTION_SCREEN_ON));

        // User interaction
        mFaceDownDetector.userActivity(PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        waitForListenerToHandle();

        // Attempt another face down to see if disabled
        triggerFaceDown();

        assertThat(mOnFaceDownCalls).isEqualTo(2);
    }

    @Test
    public void faceDownUserInteraction_disablesDetector()  throws Exception {
        mFaceDownDetector.systemReady(sContext);
        triggerFaceDown();
        mFaceDownDetector.userActivity(PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        waitForListenerToHandle();

        triggerUnflip();
        triggerFaceDown();

        assertThat(mOnFaceDownCalls).isEqualTo(1);
    }

    private void triggerUnflip() throws Exception {
        for (int i = 0; i < 10; i++) {
            advanceTime(Duration.ofMillis(5));
            mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 1.0f, 0.0f));
        }
    }

    private void triggerFaceDown() throws Exception {
        // Face up
        // Using 0.5 on x to simulate constant acceleration, such as a sloped surface.
        mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, 10.0f));

        for (int i = 0; i < 200; i++) {
            advanceTime(Duration.ofMillis(20));
            mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, -10.0f));
        }
    }

    private void setEnabled(Boolean enabled) throws Exception {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_FEATURE_ENABLED, enabled.toString(), false);
        waitForListenerToHandle();
    }

    private void advanceTime(Duration duration) {
        mCurrentTime = mCurrentTime.plus(duration);
    }

    /**
     * Create a test event to replicate an accelerometer sensor event.
     * @param x Acceleration along the x dimension.
     * @param y Acceleration along the y dimension.
     * @param gravity Acceleration along the Z dimension. Relates to
     */
    private SensorEvent createTestEvent(float x, float y, float gravity) throws Exception {
        final Constructor<SensorEvent> constructor =
                SensorEvent.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        final SensorEvent event = constructor.newInstance(3);
        event.sensor = createSensor(Sensor.TYPE_ACCELEROMETER, Sensor.STRING_TYPE_ACCELEROMETER);
        event.values[0] = x;
        event.values[1] = y;
        event.values[2] = gravity;
        event.timestamp = mCurrentTime.toNanos();
        return event;
    }

    private void onFlip(boolean isFaceDown) {
        if (isFaceDown) {
            mOnFaceDownCalls++;
        } else {
            mOnFaceDownExitCalls++;
        }
    }

    private Sensor createSensor(int type, String strType) throws Exception {
        Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
        constr.setAccessible(true);
        Sensor sensor = constr.newInstance();
        Method setter = Sensor.class.getDeclaredMethod("setType", Integer.TYPE);
        setter.setAccessible(true);
        setter.invoke(sensor, type);
        if (strType != null) {
            Field f = sensor.getClass().getDeclaredField("mStringType");
            f.setAccessible(true);
            f.set(sensor, strType);
        }
        return sensor;
    }

    private void waitForListenerToHandle() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        sContext.getMainExecutor().execute(latch::countDown);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
