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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.hardware.SensorEventListener;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AsyncSensorManagerTest extends SysuiTestCase {

    private AsyncSensorManager mAsyncSensorManager;
    private SensorEventListener mListener;
    private FakeSensorManager.FakeProximitySensor mSensor;
    private PluginManager mPluginManager;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setUp() throws Exception {
        mPluginManager = mock(PluginManager.class);
        FakeSensorManager fakeSensorManager = new FakeSensorManager(mContext);
        mAsyncSensorManager = new AsyncSensorManager(
                fakeSensorManager, new FakeThreadFactory(mFakeExecutor), mPluginManager);
        mSensor = fakeSensorManager.getFakeProximitySensor();
        mListener = mock(SensorEventListener.class);
    }

    @Test
    public void registerListenerImpl() throws Exception {
        mAsyncSensorManager.registerListener(mListener, mSensor.getSensor(), 100);

        waitUntilRequestsCompleted();

        // Verify listener was registered.
        mSensor.sendProximityResult(true);
        verify(mListener).onSensorChanged(any());
    }

    @Test
    public void unregisterListenerImpl_withNullSensor() throws Exception {
        mAsyncSensorManager.registerListener(mListener, mSensor.getSensor(), 100);
        mAsyncSensorManager.unregisterListener(mListener);

        waitUntilRequestsCompleted();

        // Verify listener was unregistered.
        mSensor.sendProximityResult(true);
        verifyNoMoreInteractions(mListener);
    }

    @Test
    public void unregisterListenerImpl_withSensor() throws Exception {
        mAsyncSensorManager.registerListener(mListener, mSensor.getSensor(), 100);
        mAsyncSensorManager.unregisterListener(mListener, mSensor.getSensor());

        waitUntilRequestsCompleted();

        // Verify listener was unregistered.
        mSensor.sendProximityResult(true);
        verifyNoMoreInteractions(mListener);
    }

    @Test
    public void registersPlugin_whenLoaded() {
        verify(mPluginManager).addPluginListener(eq(mAsyncSensorManager),
                eq(SensorManagerPlugin.class), eq(true) /* allowMultiple */);
    }

    public void waitUntilRequestsCompleted() {
        mFakeExecutor.runAllReady();
    }
}
