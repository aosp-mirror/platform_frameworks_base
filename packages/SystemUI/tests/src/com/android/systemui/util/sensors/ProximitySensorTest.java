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

import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ProximitySensorTest extends SysuiTestCase {

    private ProximitySensor mProximitySensor;
    private FakeSensorManager.FakeProximitySensor mFakeProximitySensor;

    @Before
    public void setUp() throws Exception {
        FakeSensorManager sensorManager = new FakeSensorManager(getContext());
        AsyncSensorManager asyncSensorManager = new AsyncSensorManager(
                sensorManager, null, new Handler());
        mFakeProximitySensor = sensorManager.getFakeProximitySensor();
        mProximitySensor = new ProximitySensor(getContext(), asyncSensorManager);
    }

    @Test
    public void testSingleListener() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        waitForSensorManager();
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listener.mLastEvent);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mLastEvent.getNear());
        assertEquals(listener.mCallCount, 1);
        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listener.mLastEvent.getNear());
        assertEquals(listener.mCallCount, 2);

        mProximitySensor.unregister(listener);
        waitForSensorManager();
    }

    @Test
    public void testMultiListener() {
        TestableListener listenerA = new TestableListener();
        TestableListener listenerB = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());

        mProximitySensor.register(listenerA);
        waitForSensorManager();
        assertTrue(mProximitySensor.isRegistered());
        mProximitySensor.register(listenerB);
        waitForSensorManager();
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listenerA.mLastEvent);
        assertNull(listenerB.mLastEvent);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listenerA.mLastEvent.getNear());
        assertFalse(listenerB.mLastEvent.getNear());
        assertEquals(listenerA.mCallCount, 1);
        assertEquals(listenerB.mCallCount, 1);
        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listenerA.mLastEvent.getNear());
        assertTrue(listenerB.mLastEvent.getNear());
        assertEquals(listenerA.mCallCount, 2);
        assertEquals(listenerB.mCallCount, 2);

        mProximitySensor.unregister(listenerA);
        mProximitySensor.unregister(listenerB);
        waitForSensorManager();
    }

    @Test
    public void testUnregister() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        waitForSensorManager();
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listener.mLastEvent);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mLastEvent.getNear());
        assertEquals(listener.mCallCount, 1);

        mProximitySensor.unregister(listener);
        waitForSensorManager();
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void testPauseAndResume() {
        TestableListener listener = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());
        mProximitySensor.register(listener);
        waitForSensorManager();
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listener.mLastEvent);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mLastEvent.getNear());
        assertEquals(listener.mCallCount, 1);

        mProximitySensor.pause();
        waitForSensorManager();
        assertFalse(mProximitySensor.isRegistered());

        // More events do nothing when paused.
        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mLastEvent.getNear());
        assertEquals(listener.mCallCount, 1);
        mFakeProximitySensor.sendProximityResult(false);
        assertFalse(listener.mLastEvent.getNear());
        assertEquals(listener.mCallCount, 1);

        mProximitySensor.resume();
        waitForSensorManager();
        assertTrue(mProximitySensor.isRegistered());
        // Still matches our previous call
        assertFalse(listener.mLastEvent.getNear());
        assertEquals(listener.mCallCount, 1);

        mFakeProximitySensor.sendProximityResult(true);
        assertFalse(listener.mLastEvent.getNear());
        assertEquals(listener.mCallCount, 2);

        mProximitySensor.unregister(listener);
        waitForSensorManager();
        assertFalse(mProximitySensor.isRegistered());
    }

    @Test
    public void testAlertListeners() {
        TestableListener listenerA = new TestableListener();
        TestableListener listenerB = new TestableListener();

        assertFalse(mProximitySensor.isRegistered());

        mProximitySensor.register(listenerA);
        mProximitySensor.register(listenerB);
        waitForSensorManager();
        assertTrue(mProximitySensor.isRegistered());
        assertNull(listenerA.mLastEvent);
        assertNull(listenerB.mLastEvent);

        mProximitySensor.alertListeners();
        assertNull(listenerA.mLastEvent);
        assertEquals(listenerA.mCallCount, 1);
        assertNull(listenerB.mLastEvent);
        assertEquals(listenerB.mCallCount, 1);

        mFakeProximitySensor.sendProximityResult(false);
        assertTrue(listenerA.mLastEvent.getNear());
        assertEquals(listenerA.mCallCount, 2);
        assertTrue(listenerB.mLastEvent.getNear());
        assertEquals(listenerB.mCallCount, 2);

        mProximitySensor.unregister(listenerA);
        mProximitySensor.unregister(listenerB);
        waitForSensorManager();
    }

    class TestableListener implements ProximitySensor.ProximitySensorListener {
        ProximitySensor.ProximityEvent mLastEvent;
        int mCallCount = 0;

        @Override
        public void onSensorEvent(ProximitySensor.ProximityEvent proximityEvent) {
            mLastEvent = proximityEvent;
            mCallCount++;
        }

        void reset() {
            mLastEvent = null;
            mCallCount = 0;
        }
    };

    private void waitForSensorManager() {
        TestableLooper.get(this).processAllMessages();
    }

}
