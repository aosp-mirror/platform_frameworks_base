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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ProximitySensorImplDualTest extends SysuiTestCase {
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

        mProximitySensor = new ProximitySensorImpl(
                mThresholdSensorPrimary, mThresholdSensorSecondary, mFakeExecutor,
                new FakeExecution());
    }

    @Test
    public void testInitiallyAbovePrimary() {

        TestableListener listener = new TestableListener();

        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        mThresholdSensorPrimary.triggerEvent(false, 0);
        assertNotNull(listener.mLastEvent);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);
    }

    @Test
    public void testInitiallyBelowPrimaryAboveSecondary() {

        TestableListener listener = new TestableListener();

        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        mThresholdSensorSecondary.triggerEvent(false, 1);
        assertNotNull(listener.mLastEvent);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);
    }

    @Test
    public void testInitiallyBelowPrimaryAndSecondary() {

        TestableListener listener = new TestableListener();

        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        mThresholdSensorSecondary.triggerEvent(true, 1);
        assertNotNull(listener.mLastEvent);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);
    }

    @Test
    public void testPrimaryBelowDoesNotInvokeSecondary() {
        TestableListener listener = new TestableListener();

        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listener.mLastEvent);
        assertEquals(0, listener.mCallCount);

        // Trigger primary sensor. Our secondary sensor is not registered.
        mThresholdSensorPrimary.triggerEvent(false, 0);
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
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
        assertFalse(mThresholdSensorSecondary.isPaused());
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

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

        // Trigger second sensor. Second sensor remains registered.
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);
        assertFalse(mThresholdSensorSecondary.isPaused());

        // Triggering above should pause.
        mThresholdSensorSecondary.triggerEvent(false, 0);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(2, listener.mCallCount);
        assertTrue(mThresholdSensorSecondary.isPaused());

        // Advance time. Second sensor should resume.
        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runNextReady();
        assertFalse(mThresholdSensorSecondary.isPaused());

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
        assertFalse(mThresholdSensorSecondary.isPaused());
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mProximitySensor.unregister(listener);
        assertTrue(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void testUnregisterDuringCallback() {
        ThresholdSensor.Listener listenerA = event -> mProximitySensor.pause();
        TestableListener listenerB = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listenerA);
        mProximitySensor.register(listenerB);
        assertTrue(mProximitySensor.isRegistered());
        assertFalse(mThresholdSensorPrimary.isPaused());
        assertTrue(mThresholdSensorSecondary.isPaused());
        assertNull(listenerB.mLastEvent);

        // listenerA will pause the proximity sensor, unregistering it.
        mThresholdSensorPrimary.triggerEvent(true, 0);
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertTrue(listenerB.mLastEvent.getBelow());
        assertEquals(1, listenerB.mCallCount);


        // A second call to trigger it should be ignored.
        mThresholdSensorSecondary.triggerEvent(false, 0);
        assertTrue(listenerB.mLastEvent.getBelow());
        assertEquals(1, listenerB.mCallCount);
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
        assertFalse(mThresholdSensorSecondary.isPaused());
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
    public void testSecondaryCancelsSecondary() {
        TestableListener listener = new TestableListener();
        ThresholdSensor.Listener cancelingListener = event -> mProximitySensor.pause();

        mProximitySensor.register(listener);
        mProximitySensor.register(cancelingListener);
        assertThat(listener.mLastEvent).isNull();
        assertThat(listener.mCallCount).isEqualTo(0);

        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertThat(listener.mLastEvent).isNull();
        assertThat(listener.mCallCount).isEqualTo(0);
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertThat(listener.mLastEvent.getBelow()).isTrue();
        assertThat(listener.mCallCount).isEqualTo(1);

        // The proximity sensor should now be canceled. Advancing the clock should do nothing.
        assertThat(mFakeExecutor.numPending()).isEqualTo(0);
        mThresholdSensorSecondary.triggerEvent(false, 1);
        assertThat(listener.mLastEvent.getBelow()).isTrue();
        assertThat(listener.mCallCount).isEqualTo(1);

        mProximitySensor.unregister(listener);
    }

    @Test
    public void testSecondarySafe() {
        mProximitySensor.setSecondarySafe(true);

        TestableListener listener = new TestableListener();

        // We immediately register the secondary sensor.
        mProximitySensor.register(listener);
        assertThat(mThresholdSensorPrimary.isPaused()).isTrue();
        assertThat(mThresholdSensorSecondary.isPaused()).isFalse();
        assertThat(listener.mLastEvent).isNull();
        assertThat(listener.mCallCount).isEqualTo(0);

        mThresholdSensorPrimary.triggerEvent(true, 0);
        assertThat(listener.mLastEvent).isNull();
        assertThat(listener.mCallCount).isEqualTo(0);
        mThresholdSensorSecondary.triggerEvent(true, 0);
        assertThat(listener.mLastEvent.getBelow()).isTrue();
        assertThat(listener.mCallCount).isEqualTo(1);

        // The secondary sensor should now remain resumed indefinitely.
        assertThat(mThresholdSensorSecondary.isPaused()).isFalse();
        mThresholdSensorSecondary.triggerEvent(false, 1);
        assertThat(listener.mLastEvent.getBelow()).isFalse();
        assertThat(listener.mCallCount).isEqualTo(2);

        // The secondary is still running, and not polling with the executor.
        assertThat(mThresholdSensorSecondary.isPaused()).isFalse();
        assertThat(mFakeExecutor.numPending()).isEqualTo(0);

        mProximitySensor.unregister(listener);
    }

    @Test
    public void testSecondaryPausesPrimary() {
        TestableListener listener = new TestableListener();

        mProximitySensor.register(listener);

        assertThat(mThresholdSensorPrimary.isPaused()).isFalse();
        assertThat(mThresholdSensorSecondary.isPaused()).isTrue();

        mProximitySensor.setSecondarySafe(true);

        assertThat(mThresholdSensorPrimary.isPaused()).isTrue();
        assertThat(mThresholdSensorSecondary.isPaused()).isFalse();
    }

    @Test
    public void testSecondaryResumesPrimary() {
        mProximitySensor.setSecondarySafe(true);

        TestableListener listener = new TestableListener();
        mProximitySensor.register(listener);

        assertThat(mThresholdSensorPrimary.isPaused()).isTrue();
        assertThat(mThresholdSensorSecondary.isPaused()).isFalse();

        mProximitySensor.setSecondarySafe(false);

        assertThat(mThresholdSensorPrimary.isPaused()).isFalse();
        assertThat(mThresholdSensorSecondary.isPaused()).isTrue();


    }

    private static class TestableListener implements ThresholdSensor.Listener {
        ThresholdSensorEvent mLastEvent;
        int mCallCount = 0;

        @Override
        public void onThresholdCrossed(ThresholdSensorEvent proximityEvent) {
            mLastEvent = proximityEvent;
            mCallCount++;
        }
    };

}
