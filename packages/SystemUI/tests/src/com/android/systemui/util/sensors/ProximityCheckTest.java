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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.Assert;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ProximityCheckTest extends SysuiTestCase {

    private FakeProximitySensor mFakeProximitySensor;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    private TestableCallback mTestableCallback = new TestableCallback();

    private ProximitySensor.ProximityCheck mProximityCheck;

    @Before
    public void setUp() throws Exception {
        Assert.setTestableLooper(TestableLooper.get(this).getLooper());
        FakeThresholdSensor thresholdSensor = new FakeThresholdSensor();
        thresholdSensor.setLoaded(true);
        mFakeProximitySensor = new FakeProximitySensor(thresholdSensor, null, mFakeExecutor);

        mProximityCheck = new ProximitySensor.ProximityCheck(mFakeProximitySensor, mFakeExecutor);
    }

    @Test
    public void testCheck() {
        mProximityCheck.check(100, mTestableCallback);

        assertNull(mTestableCallback.mLastResult);

        mFakeProximitySensor.setLastEvent(new ProximitySensor.ThresholdSensorEvent(true, 0));
        mFakeProximitySensor.alertListeners();

        assertTrue(mTestableCallback.mLastResult);
    }

    @Test
    public void testTimeout() {
        mProximityCheck.check(100, mTestableCallback);

        assertTrue(mFakeProximitySensor.isRegistered());

        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runAllReady();

        assertFalse(mFakeProximitySensor.isRegistered());
        assertEquals(1, mTestableCallback.mNumCalls);
        assertNull(mTestableCallback.mLastResult);
    }

    @Test
    public void testNotLoaded() {
        mFakeProximitySensor.setSensorAvailable(false);

        assertThat(mTestableCallback.mLastResult).isNull();
        assertThat(mTestableCallback.mNumCalls).isEqualTo(0);

        mProximityCheck.check(100, mTestableCallback);

        assertThat(mTestableCallback.mLastResult).isNull();
        assertThat(mTestableCallback.mNumCalls).isEqualTo(1);

        mFakeProximitySensor.setSensorAvailable(true);

        mProximityCheck.check(100, mTestableCallback);

        mFakeProximitySensor.setLastEvent(new ProximitySensor.ThresholdSensorEvent(true, 0));
        mFakeProximitySensor.alertListeners();

        assertThat(mTestableCallback.mLastResult).isNotNull();
        assertThat(mTestableCallback.mNumCalls).isEqualTo(2);
    }

    @Test
    public void testProxDoesntCancelOthers() {
        assertFalse(mFakeProximitySensor.isRegistered());
        // We don't need our "other" listener to do anything. Just ensure our sensor is registered.
        ThresholdSensor.Listener emptyListener = event -> { };
        mFakeProximitySensor.register(emptyListener);
        assertTrue(mFakeProximitySensor.isRegistered());

        // Now run a basic check. This is just like testCheck()
        mProximityCheck.check(100, mTestableCallback);

        assertNull(mTestableCallback.mLastResult);

        mFakeProximitySensor.setLastEvent(new ProximitySensor.ThresholdSensorEvent(true, 0));
        mFakeProximitySensor.alertListeners();

        assertTrue(mTestableCallback.mLastResult);

        // We should still be registered, since we have another listener.
        assertTrue(mFakeProximitySensor.isRegistered());

        mFakeProximitySensor.unregister(emptyListener);
        assertFalse(mFakeProximitySensor.isRegistered());

    }

    private static class TestableCallback implements Consumer<Boolean> {
        Boolean mLastResult;
        int mNumCalls = 0;

        @Override
        public void accept(Boolean result) {
            mLastResult = result;
            mNumCalls++;
        }
    }
}
