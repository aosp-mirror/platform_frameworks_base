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

import static com.google.common.truth.Truth.assertThat;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.testing.TestableContext;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.display.TestUtils;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.time.Duration;

public class FaceDownDetectorTest {
    @ClassRule
    public static final TestableContext sContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);

    private final FaceDownDetector mFaceDownDetector =
            new FaceDownDetector(this::onFlip);

    @Mock private SensorManager mSensorManager;

    private long mCurrentTime;
    private int mOnFaceDownCalls = 0;
    private int mOnFaceDownExitCalls = 0;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        sContext.addMockSystemService(SensorManager.class, mSensorManager);
        mCurrentTime = 0;
    }

    @Test
    public void faceDownFor2Seconds_triggersFaceDown() throws Exception {
        mFaceDownDetector.systemReady(sContext);

        // Face up
        // Using 0.5 on x to simulate constant acceleration, such as a sloped surface.
        mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, 10.0f));

        for (int i = 0; i < 200; i++) {
            advanceTime(Duration.ofMillis(20));
            mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, -10.0f));
        }

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

        // Face up
        // Using 0.5 on x to simulate constant acceleration, such as a sloped surface.
        mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, 10.0f));

        // Trigger face down
        for (int i = 0; i < 100; i++) {
            advanceTime(Duration.ofMillis(20));
            mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 0.0f, -10.0f));
        }

        // Phone flips
        for (int i = 0; i < 10; i++) {
            advanceTime(Duration.ofMillis(5));
            mFaceDownDetector.onSensorChanged(createTestEvent(0.5f, 1.0f, 0.0f));
        }

        assertThat(mOnFaceDownCalls).isEqualTo(1);
        assertThat(mOnFaceDownExitCalls).isEqualTo(1);
    }

    private void advanceTime(Duration duration) {
        mCurrentTime += duration.toNanos();
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
        event.sensor =
                TestUtils.createSensor(Sensor.TYPE_ACCELEROMETER, Sensor.STRING_TYPE_ACCELEROMETER);
        event.values[0] = x;
        event.values[1] = y;
        event.values[2] = gravity;
        event.timestamp = mCurrentTime;
        return event;
    }

    private void onFlip(boolean isFaceDown) {
        if (isFaceDown) {
            mOnFaceDownCalls++;
        } else {
            mOnFaceDownExitCalls++;
        }
    }
}
