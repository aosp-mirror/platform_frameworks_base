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

import static android.hardware.Sensor.TYPE_ALL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ThresholdSensorImplTest extends SysuiTestCase {

    private ThresholdSensorImpl mThresholdSensor;
    private FakeSensorManager mSensorManager;
    private AsyncSensorManager mAsyncSensorManager;
    private FakeSensorManager.FakeProximitySensor mFakeProximitySensor;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setUp() throws Exception {
        mSensorManager = new FakeSensorManager(getContext());

        mAsyncSensorManager = new AsyncSensorManager(
                mSensorManager, new FakeThreadFactory(mFakeExecutor), null);

        mFakeProximitySensor = mSensorManager.getFakeProximitySensor();
        ThresholdSensorImpl.Builder thresholdSensorBuilder = new ThresholdSensorImpl.Builder(
                null, mAsyncSensorManager, new FakeExecution());
        mThresholdSensor = (ThresholdSensorImpl) thresholdSensorBuilder
                .setSensor(mFakeProximitySensor.getSensor())
                .setThresholdValue(mFakeProximitySensor.getSensor().getMaximumRange())
                .build();
    }

    @Test
    public void testRegistersWakeUpProxSensor_givenWakeUpExistsAfterNonWakeup() {
        // GIVEN sensor manager with two prox sensors (one non-wakeup, one wakeup)
        final String sensorTypeProx = "prox";
        AsyncSensorManager mockSensorManager = mock(AsyncSensorManager.class);

        Sensor mockNonWakeupProx = mock(Sensor.class);
        when(mockNonWakeupProx.isWakeUpSensor()).thenReturn(false);
        when(mockNonWakeupProx.getStringType()).thenReturn(sensorTypeProx);

        Sensor mockWakeupProx = mock(Sensor.class);
        when(mockWakeupProx.isWakeUpSensor()).thenReturn(true);
        when(mockWakeupProx.getStringType()).thenReturn(sensorTypeProx);

        when(mockSensorManager.getSensorList(TYPE_ALL)).thenReturn(
                List.of(mockNonWakeupProx, mockWakeupProx));

        // WHEN we build a threshold sensor by type
        ThresholdSensorImpl.Builder thresholdSensorBuilder = new ThresholdSensorImpl.Builder(
                null, mockSensorManager, new FakeExecution());
        Sensor proxSensor = thresholdSensorBuilder.findSensorByType(sensorTypeProx, true);

        // THEN the prox sensor used is the wakeup sensor
        assertEquals(mockWakeupProx, proxSensor);
    }

    @Test
    public void testRegistersWakeUpProxSensor_givenNonWakeUpExistsAfterWakeup() {
        // GIVEN sensor manager with two prox sensors (one wakeup, one non-wakeup)
        final String sensorTypeProx = "prox";
        AsyncSensorManager mockSensorManager = mock(AsyncSensorManager.class);

        Sensor mockNonWakeupProx = mock(Sensor.class);
        when(mockNonWakeupProx.isWakeUpSensor()).thenReturn(false);
        when(mockNonWakeupProx.getStringType()).thenReturn(sensorTypeProx);

        Sensor mockWakeupProx = mock(Sensor.class);
        when(mockWakeupProx.isWakeUpSensor()).thenReturn(true);
        when(mockWakeupProx.getStringType()).thenReturn(sensorTypeProx);

        when(mockSensorManager.getSensorList(TYPE_ALL)).thenReturn(
                List.of(mockWakeupProx, mockNonWakeupProx));

        // WHEN we build a threshold sensor by type
        ThresholdSensorImpl.Builder thresholdSensorBuilder = new ThresholdSensorImpl.Builder(
                null, mockSensorManager, new FakeExecution());
        Sensor proxSensor = thresholdSensorBuilder.findSensorByType(sensorTypeProx, true);

        // THEN the prox sensor used is the wakeup sensor
        assertEquals(mockWakeupProx, proxSensor);
    }

    @Test
    public void testRegistersNonWakeUpProxSensor_givenNonWakeUpOnly() {
        // GIVEN sensor manager with one non-wakeup prox sensor
        final String sensorTypeProx = "prox";
        AsyncSensorManager mockSensorManager = mock(AsyncSensorManager.class);

        Sensor mockNonWakeupProx = mock(Sensor.class);
        when(mockNonWakeupProx.isWakeUpSensor()).thenReturn(false);
        when(mockNonWakeupProx.getStringType()).thenReturn(sensorTypeProx);

        when(mockSensorManager.getSensorList(TYPE_ALL)).thenReturn(List.of(mockNonWakeupProx));

        // WHEN we build a threshold sensor by type
        ThresholdSensorImpl.Builder thresholdSensorBuilder = new ThresholdSensorImpl.Builder(
                null, mockSensorManager, new FakeExecution());
        Sensor proxSensor = thresholdSensorBuilder.findSensorByType(sensorTypeProx, true);

        // THEN the prox sensor used is the one available (non-wakeup)
        assertEquals(mockNonWakeupProx, proxSensor);
    }

    @Test
    public void testSingleListener() {
        TestableListener listener = new TestableListener();

        assertFalse(mThresholdSensor.isRegistered());
        mThresholdSensor.register(listener);
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        assertEquals(0, listener.mCallCount);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mBelow);
        assertEquals(1, listener.mCallCount);
        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listener.mBelow);
        assertEquals(2, listener.mCallCount);

        mThresholdSensor.unregister(listener);
        waitForSensorManager();
    }

    @Test
    public void testMultiListener() {
        TestableListener listenerA = new TestableListener();
        TestableListener listenerB = new TestableListener();

        assertFalse(mThresholdSensor.isRegistered());

        mThresholdSensor.register(listenerA);
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        mThresholdSensor.register(listenerB);
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        assertEquals(0, listenerA.mCallCount);
        assertEquals(0, listenerB.mCallCount);


        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listenerA.mBelow);
        assertFalse(listenerB.mBelow);
        assertEquals(1, listenerA.mCallCount);
        assertEquals(1, listenerB.mCallCount);
        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listenerA.mBelow);
        assertTrue(listenerB.mBelow);
        assertEquals(2, listenerA.mCallCount);
        assertEquals(2, listenerB.mCallCount);

        mThresholdSensor.unregister(listenerA);
        mThresholdSensor.unregister(listenerB);
        waitForSensorManager();
    }

    @Test
    public void testDuplicateListener() {
        TestableListener listenerA = new TestableListener();

        assertFalse(mThresholdSensor.isRegistered());

        mThresholdSensor.register(listenerA);
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        mThresholdSensor.register(listenerA);
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        assertEquals(0, listenerA.mCallCount);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listenerA.mBelow);
        assertEquals(1, listenerA.mCallCount);
        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listenerA.mBelow);
        assertEquals(2, listenerA.mCallCount);

        mThresholdSensor.unregister(listenerA);
        waitForSensorManager();
    }
    @Test
    public void testUnregister() {
        TestableListener listener = new TestableListener();

        assertFalse(mThresholdSensor.isRegistered());
        mThresholdSensor.register(listener);
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        assertEquals(0, listener.mCallCount);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mBelow);
        assertEquals(1, listener.mCallCount);

        mThresholdSensor.unregister(listener);
        waitForSensorManager();
        assertFalse(mThresholdSensor.isRegistered());
    }

    @Test
    public void testPauseAndResume() {
        TestableListener listener = new TestableListener();

        assertFalse(mThresholdSensor.isRegistered());
        mThresholdSensor.register(listener);
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        assertEquals(0, listener.mCallCount);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mBelow);
        assertEquals(1, listener.mCallCount);

        mThresholdSensor.pause();
        waitForSensorManager();
        assertFalse(mThresholdSensor.isRegistered());

        // More events do nothing when paused.
        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mBelow);
        assertEquals(1, listener.mCallCount);
        mFakeProximitySensor.sendProximityResult(false);
        assertFalse(listener.mBelow);
        assertEquals(1, listener.mCallCount);

        mThresholdSensor.resume();
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        // Still matches our previous call
        assertFalse(listener.mBelow);
        assertEquals(1, listener.mCallCount);

        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listener.mBelow);
        assertEquals(2, listener.mCallCount);

        mThresholdSensor.unregister(listener);
        waitForSensorManager();
        assertFalse(mThresholdSensor.isRegistered());
    }

    @Test
    public void testAlertListeners() {
        TestableListener listenerA = new TestableListener();
        TestableListener listenerB = new TestableListener();

        assertFalse(mThresholdSensor.isRegistered());

        mThresholdSensor.register(listenerA);
        mThresholdSensor.register(listenerB);
        waitForSensorManager();
        assertTrue(mThresholdSensor.isRegistered());
        assertEquals(0, listenerA.mCallCount);
        assertEquals(0, listenerB.mCallCount);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listenerA.mBelow);
        assertEquals(1, listenerA.mCallCount);
        assertFalse(listenerB.mBelow);
        assertEquals(1, listenerB.mCallCount);

        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listenerA.mBelow);
        assertEquals(2, listenerA.mCallCount);
        assertTrue(listenerB.mBelow);
        assertEquals(2, listenerB.mCallCount);

        mThresholdSensor.unregister(listenerA);
        mThresholdSensor.unregister(listenerB);
        waitForSensorManager();
    }

    @Test
    public void testHysteresis() {
        float lowValue = 10f;
        float highValue = 100f;
        FakeSensorManager.FakeGenericSensor sensor = mSensorManager.getFakeLightSensor();
        ThresholdSensorImpl.Builder thresholdSensorBuilder = new ThresholdSensorImpl.Builder(
                null, mAsyncSensorManager, new FakeExecution());
        ThresholdSensorImpl thresholdSensor = (ThresholdSensorImpl) thresholdSensorBuilder
                .setSensor(sensor.getSensor())
                .setThresholdValue(lowValue)
                .setThresholdLatchValue(highValue)
                .build();

        TestableListener listener = new TestableListener();

        assertFalse(thresholdSensor.isRegistered());
        thresholdSensor.register(listener);
        waitForSensorManager();
        assertTrue(thresholdSensor.isRegistered());
        assertEquals(0, listener.mCallCount);

        sensor.sendSensorEvent(lowValue - 1);

        assertTrue(listener.mBelow);
        assertEquals(1, listener.mCallCount);

        sensor.sendSensorEvent(lowValue + 1);

        assertTrue(listener.mBelow);
        assertEquals(1, listener.mCallCount);

        sensor.sendSensorEvent(highValue);

        assertFalse(listener.mBelow);
        assertEquals(2, listener.mCallCount);

        sensor.sendSensorEvent(highValue - 1);

        assertFalse(listener.mBelow);
        assertEquals(2, listener.mCallCount);


        sensor.sendSensorEvent(lowValue - 1);

        assertTrue(listener.mBelow);
        assertEquals(3, listener.mCallCount);
    }

    @Test
    public void testAlertAfterPause() {
        TestableListener listener = new TestableListener();

        mThresholdSensor.register(listener);
        waitForSensorManager();
        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listener.mBelow);
        assertEquals(1, listener.mCallCount);

        mThresholdSensor.pause();

        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listener.mBelow);
        assertEquals(1, listener.mCallCount);
    }

    static class TestableListener implements ThresholdSensor.Listener {
        boolean mBelow;
        long mTimestampNs;
        int mCallCount;

        @Override
        public void onThresholdCrossed(ThresholdSensorEvent event) {
            mBelow = event.getBelow();
            mTimestampNs = event.getTimestampNs();
            mCallCount++;
        }
    }

    private void waitForSensorManager() {
        mFakeExecutor.runAllReady();
    }

}
