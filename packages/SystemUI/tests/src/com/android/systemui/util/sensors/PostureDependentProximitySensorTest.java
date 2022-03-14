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

package com.android.systemui.util.sensors;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;

import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class PostureDependentProximitySensorTest extends SysuiTestCase {
    @Mock private Resources mResources;
    @Mock private DevicePostureController mDevicePostureController;
    @Mock private AsyncSensorManager mSensorManager;

    @Captor private ArgumentCaptor<DevicePostureController.Callback> mPostureListenerCaptor =
            ArgumentCaptor.forClass(DevicePostureController.Callback.class);
    private DevicePostureController.Callback mPostureListener;

    private PostureDependentProximitySensor mProximitySensor;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();

        mProximitySensor = new PostureDependentProximitySensor(
                new ThresholdSensor[DevicePostureController.SUPPORTED_POSTURES_SIZE],
                new ThresholdSensor[DevicePostureController.SUPPORTED_POSTURES_SIZE],
                mFakeExecutor,
                new FakeExecution(),
                mDevicePostureController
        );
    }

    @Test
    public void testPostureChangeListenerAdded() {
        capturePostureListener();
    }

    @Test
    public void testPostureChangeListenerUpdatesPosture() {
        // GIVEN posture listener is registered
        capturePostureListener();

        // WHEN the posture changes to DEVICE_POSTURE_OPENED
        mPostureListener.onPostureChanged(DevicePostureController.DEVICE_POSTURE_OPENED);

        // THEN device posture is updated to DEVICE_POSTURE_OPENED
        assertEquals(DevicePostureController.DEVICE_POSTURE_OPENED,
                mProximitySensor.mDevicePosture);

        // WHEN the posture changes to DEVICE_POSTURE_CLOSED
        mPostureListener.onPostureChanged(DevicePostureController.DEVICE_POSTURE_CLOSED);

        // THEN device posture is updated to DEVICE_POSTURE_CLOSED
        assertEquals(DevicePostureController.DEVICE_POSTURE_CLOSED,
                mProximitySensor.mDevicePosture);

        // WHEN the posture changes to DEVICE_POSTURE_FLIPPED
        mPostureListener.onPostureChanged(DevicePostureController.DEVICE_POSTURE_FLIPPED);

        // THEN device posture is updated to DEVICE_POSTURE_FLIPPED
        assertEquals(DevicePostureController.DEVICE_POSTURE_FLIPPED,
                mProximitySensor.mDevicePosture);

        // WHEN the posture changes to DEVICE_POSTURE_HALF_OPENED
        mPostureListener.onPostureChanged(DevicePostureController.DEVICE_POSTURE_HALF_OPENED);

        // THEN device posture is updated to DEVICE_POSTURE_HALF_OPENED
        assertEquals(DevicePostureController.DEVICE_POSTURE_HALF_OPENED,
                mProximitySensor.mDevicePosture);
    }

    private void capturePostureListener() {
        verify(mDevicePostureController).addCallback(mPostureListenerCaptor.capture());
        mPostureListener = mPostureListenerCaptor.getValue();
    }
}
