/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.util;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.utils.hardware.FakeSensorManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class AsyncSensorManagerTest extends SysuiTestCase {

    private TestableAsyncSensorManager mAsyncSensorManager;
    private FakeSensorManager mFakeSensorManager;
    private SensorEventListener mListener;
    private FakeSensorManager.MockProximitySensor mSensor;

    @Before
    public void setUp() throws Exception {
        mFakeSensorManager = new FakeSensorManager(mContext);
        mAsyncSensorManager = new TestableAsyncSensorManager(mFakeSensorManager);
        mSensor = mFakeSensorManager.getMockProximitySensor();
        mListener = mock(SensorEventListener.class);
    }

    @Test
    public void registerListenerImpl() throws Exception {
        mAsyncSensorManager.registerListener(mListener, mSensor.getSensor(), 100);

        mAsyncSensorManager.waitUntilRequestsCompleted();

        // Verify listener was registered.
        mSensor.sendProximityResult(true);
        verify(mListener).onSensorChanged(any());
    }

    @Test
    public void unregisterListenerImpl_withNullSensor() throws Exception {
        mAsyncSensorManager.registerListener(mListener, mSensor.getSensor(), 100);
        mAsyncSensorManager.unregisterListener(mListener);

        mAsyncSensorManager.waitUntilRequestsCompleted();

        // Verify listener was unregistered.
        mSensor.sendProximityResult(true);
        verifyNoMoreInteractions(mListener);
    }

    @Test
    public void unregisterListenerImpl_withSensor() throws Exception {
        mAsyncSensorManager.registerListener(mListener, mSensor.getSensor(), 100);
        mAsyncSensorManager.unregisterListener(mListener, mSensor.getSensor());

        mAsyncSensorManager.waitUntilRequestsCompleted();

        // Verify listener was unregistered.
        mSensor.sendProximityResult(true);
        verifyNoMoreInteractions(mListener);
    }

    private class TestableAsyncSensorManager extends AsyncSensorManager {
        public TestableAsyncSensorManager(SensorManager sensorManager) {
            super(sensorManager);
        }

        public void waitUntilRequestsCompleted() {
            assertTrue(mHandler.runWithScissors(() -> {}, 0));
        }
    }
}