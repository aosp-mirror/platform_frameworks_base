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

package com.android.systemui.util.sensors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

/**
 * Tests for ProximitySensor that rely on a single hardware sensor.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class ProximitySensorImplSingleTest extends SysuiTestCase {
    private ProximitySensor mProximitySensor;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    private FakeThresholdSensor mThresholdSensor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();
        mThresholdSensor = new FakeThresholdSensor();
        mThresholdSensor.setLoaded(true);

        mProximitySensor = new ProximitySensorImpl(
                mThresholdSensor, new FakeThresholdSensor(), mFakeExecutor, new FakeExecution());
    }

    @Test
    public void testSingleListener() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listener.mLastEvent);

        mThresholdSensor.triggerEvent(false, 0);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);
        mThresholdSensor.triggerEvent(true, 0);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(2, listener.mCallCount);

        mProximitySensor.unregister(listener);
    }

    @Test
    public void testMultiListener() {
        TestableListener listenerA = new TestableListener();
        TestableListener listenerB = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());

        mProximitySensor.register(listenerA);
        assertTrue(mProximitySensor.isRegistered());
        mProximitySensor.register(listenerB);
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listenerA.mLastEvent);
        assertNull(listenerB.mLastEvent);

        mThresholdSensor.triggerEvent(false, 0);
        assertFalse(listenerA.mLastEvent.getBelow());
        assertFalse(listenerB.mLastEvent.getBelow());
        assertEquals(1, listenerA.mCallCount);
        assertEquals(1, listenerB.mCallCount);
        mThresholdSensor.triggerEvent(true, 1);
        assertTrue(listenerA.mLastEvent.getBelow());
        assertTrue(listenerB.mLastEvent.getBelow());
        assertEquals(2, listenerA.mCallCount);
        assertEquals(2, listenerB.mCallCount);

        mProximitySensor.unregister(listenerA);
        mProximitySensor.unregister(listenerB);
    }

    @Test
    public void testDuplicateListener() {
        TestableListener listenerA = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());

        mProximitySensor.register(listenerA);
        assertTrue(mProximitySensor.isRegistered());
        mProximitySensor.register(listenerA);
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listenerA.mLastEvent);

        mThresholdSensor.triggerEvent(false, 0);
        assertFalse(listenerA.mLastEvent.getBelow());
        assertEquals(1, listenerA.mCallCount);
        mThresholdSensor.triggerEvent(true, 1);
        assertTrue(listenerA.mLastEvent.getBelow());
        assertEquals(2, listenerA.mCallCount);

        mProximitySensor.unregister(listenerA);
    }
    @Test
    public void testUnregister() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listener.mLastEvent);

        mThresholdSensor.triggerEvent(false, 0);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mProximitySensor.unregister(listener);
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void testPauseAndResume() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listener.mLastEvent);

        mThresholdSensor.triggerEvent(false, 0);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mProximitySensor.pause();
        assertFalse(mProximitySensor.isRegistered());

        // More events do nothing when paused.
        mThresholdSensor.triggerEvent(false, 1);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);
        mThresholdSensor.triggerEvent(true, 2);
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mProximitySensor.resume();
        assertTrue(mProximitySensor.isRegistered());
        // Still matches our previous call
        assertFalse(listener.mLastEvent.getBelow());
        assertEquals(1, listener.mCallCount);

        mThresholdSensor.triggerEvent(true, 3);
        assertTrue(listener.mLastEvent.getBelow());
        assertEquals(2, listener.mCallCount);

        mProximitySensor.unregister(listener);
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void testAlertListeners() {
        TestableListener listenerA = new TestableListener();
        TestableListener listenerB = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());

        mProximitySensor.register(listenerA);
        mProximitySensor.register(listenerB);
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listenerA.mLastEvent);
        assertNull(listenerB.mLastEvent);

        mProximitySensor.alertListeners();
        assertNull(listenerA.mLastEvent);
        assertEquals(0, listenerA.mCallCount);
        assertNull(listenerB.mLastEvent);
        assertEquals(0, listenerB.mCallCount);

        mThresholdSensor.triggerEvent(true, 0);
        assertTrue(listenerA.mLastEvent.getBelow());
        assertEquals(1, listenerA.mCallCount);
        assertTrue(listenerB.mLastEvent.getBelow());
        assertEquals(1,  listenerB.mCallCount);

        mProximitySensor.unregister(listenerA);
        mProximitySensor.unregister(listenerB);
    }

    @Test
    public void testPreventRecursiveAlert() {
        TestableListener listenerA = new TestableListener() {
            @Override
            public void onThresholdCrossed(ThresholdSensorEvent proximityEvent) {
                super.onThresholdCrossed(proximityEvent);
                if (mCallCount < 2) {
                    mProximitySensor.alertListeners();
                }
            }
        };

        mProximitySensor.register(listenerA);

        mThresholdSensor.triggerEvent(true, 0);

        assertEquals(1, listenerA.mCallCount);
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
