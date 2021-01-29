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
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ThresholdSensorImplTest extends SysuiTestCase {

    private ThresholdSensorImpl mThresholdSensor;
    private FakeSensorManager mSensorManager;
    private AsyncSensorManager mAsyncSensorManager;
    private FakeSensorManager.FakeProximitySensor mFakeProximitySensor;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        mSensorManager = new FakeSensorManager(getContext());

        mAsyncSensorManager = new AsyncSensorManager(
                mSensorManager, null, new Handler());

        mFakeProximitySensor = mSensorManager.getFakeProximitySensor();
        ThresholdSensorImpl.Builder thresholdSensorBuilder = new ThresholdSensorImpl.Builder(
                null, mAsyncSensorManager);
        mThresholdSensor = (ThresholdSensorImpl) thresholdSensorBuilder
                .setSensor(mFakeProximitySensor.getSensor())
                .setThresholdValue(mFakeProximitySensor.getSensor().getMaximumRange())
                .build();
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
                null, mAsyncSensorManager);
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
        public void onThresholdCrossed(ThresholdSensor.ThresholdSensorEvent event) {
            mBelow = event.getBelow();
            mTimestampNs = event.getTimestampNs();
            mCallCount++;
        }
    }

    private void waitForSensorManager() {
        TestableLooper.get(this).processAllMessages();
    }

}
