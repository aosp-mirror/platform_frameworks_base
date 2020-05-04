/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.util.sensors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ProximitySensorDualTest extends SysuiTestCase {
    private ProximitySensor mProximitySensor;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    private FakeThresholdSensor mThresholdSensorPrimary;
    private FakeThresholdSensor mThresholdSensorSecondary;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();
        mThresholdSensorPrimary = new FakeThresholdSensor();
        mThresholdSensorPrimary.setLoaded(true);
        mThresholdSensorSecondary = new FakeThresholdSensor();
        mThresholdSensorSecondary.setLoaded(true);

        mProximitySensor = new ProximitySensor(
                mThresholdSensorPrimary, mThresholdSensorSecondary, mFakeExecutor);
    }

    @Test
    public void testSingleListener() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        // Trigger second sensor. Nothing should happen yet.
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        // Trigger first sensor. Our second sensor is now registered.
        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertFalse(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        // Trigger second sensor.
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);
        assertTrue(mThresholdSensorSecondary.isPaused());

        mProximitySensor.unregister(listener);
    }

    @Test
    public void testSecondaryPausing() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        // Trigger first sensor. Our second sensor is now registered.
        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        // Trigger second sensor.
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);
        assertTrue(mThresholdSensorSecondary.isPaused());

        // Advance time. Second sensor should resume.
        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runNextReady();
        assertFalse(mThresholdSensorSecondary.isPaused());

        // Triggering should pause again.
        mThresholdSensorSecondary.triggerEvent(false, 0);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(2, listener.mCallCount);
        assertTrue(mThresholdSensorSecondary.isPaused());

        mProximitySensor.unregister(listener);
    }

    @Test
    public void testUnregister() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);

        mThresholdSensorPrimary.triggerEvent(true, 0);
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mProximitySensor.unregister(listener);
        assertTrue(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void testPauseAndResume() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listener.mLastEvent);

        mThresholdSensorPrimary.triggerEvent(true, 0);
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mProximitySensor.pause();
        assertFalse(mProximitySensor.isRegistered());
        assertTrue(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());

        // More events do nothing when paused.
        mThresholdSensorSecondary.triggerEvent(false, 1);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mProximitySensor.resume();
        assertTrue(mProximitySensor.isRegistered());
        // Still matches our previous call
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        // Need to trigger the primary sensor before the secondary re-registers itself.
        mThresholdSensorPrimary.triggerEvent(true, 3);
        mThresholdSensorSecondary.triggerEvent(false, 3);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(2, listener.mCallCount);

        mProximitySensor.unregister(listener);
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void testPrimarySecondaryDisagreement() {
        TestableListener listener = new TestableListener();

        mProximitySensor.register(listener);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        // Trigger our sensors with different values. Secondary overrides primary.
        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);
        mThresholdSensorSecondary.triggerEvent(false, 0);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mThresholdSensorSecondary.resume();
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(2, listener.mCallCount);

        mThresholdSensorSecondary.resume();
        mThresholdSensorSecondary.triggerEvent(false, 0);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(3, listener.mCallCount);

        mProximitySensor.unregister(listener);
    }

    @Test
    public void testPrimaryCancelsSecondary() {
        TestableListener listener = new TestableListener();

        mProximitySensor.register(listener);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        // When the primary reports false, the secondary is no longer needed. We get an immediate
        // report.
        mThresholdSensorPrimary.triggerEvent(false, 1);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(2, listener.mCallCount);

        // The secondary is now ignored. No more work is scheduled.
        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runNextReady();
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(2, listener.mCallCount);
        assertEquals(0, mFakeExecutor.numPending());

        mProximitySensor.unregister(listener);
    }

    @Test
    public void testSecondaryCancelsSecondary() {
        TestableListener listener = new TestableListener();
        ThresholdSensor.Listener cancelingListener = new ThresholdSensor.Listener() {
            @Override
            public void onThresholdCrossed(ThresholdSensor.ThresholdSensorEvent event) {
                mProximitySensor.pause();
            }
        };

        mProximitySensor.register(listener);
        mProximitySensor.register(cancelingListener);
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        // The proximity sensor should now be canceled. Advancing the clock should do nothing.
        assertEquals(0, mFakeExecutor.numPending());
        mThresholdSensorSecondary.triggerEvent(false, 1);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mProximitySensor.unregister(listener);
    }

    private static class TestableListener implements ThresholdSensor.Listener {
        ThresholdSensor.ThresholdSensorEvent mLastEvent;
        int mCallCount = 0;

        @Override
        public void onThresholdCrossed(ThresholdSensor.ThresholdSensorEvent proximityEvent) {
            mLastEvent = proximityEvent;
            mCallCount++;
        }
    };

}
