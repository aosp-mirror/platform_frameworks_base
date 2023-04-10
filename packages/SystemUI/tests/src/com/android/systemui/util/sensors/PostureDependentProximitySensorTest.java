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

import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_CLOSED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.res.Resources;
import android.hardware.Sensor;
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
    @Mock private Sensor mMockedPrimaryProxSensor;

    @Captor private ArgumentCaptor<DevicePostureController.Callback> mPostureListenerCaptor =
            ArgumentCaptor.forClass(DevicePostureController.Callback.class);
    private DevicePostureController.Callback mPostureListener;

    private PostureDependentProximitySensor mPostureDependentProximitySensor;
    private ThresholdSensor[] mPrimaryProxSensors;
    private ThresholdSensor[] mSecondaryProxSensors;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();

        setupProximitySensors(DEVICE_POSTURE_CLOSED);
        mPostureDependentProximitySensor = new PostureDependentProximitySensor(
                mPrimaryProxSensors,
                mSecondaryProxSensors,
                new FakeExecutor(new FakeSystemClock()),
                new FakeExecution(),
                mDevicePostureController
        );
    }

    /**
     * Support a proximity sensor only for the given devicePosture for the primary sensor.
     * Otherwise, all other postures don't support prox.
     */
    private void setupProximitySensors(
            @DevicePostureController.DevicePostureInt int proxExistsForPosture) {
        final ThresholdSensorImpl.Builder sensorBuilder = new ThresholdSensorImpl.BuilderFactory(
                mResources, mSensorManager, new FakeExecution()).createBuilder();

        mPrimaryProxSensors = new ThresholdSensor[DevicePostureController.SUPPORTED_POSTURES_SIZE];
        mSecondaryProxSensors =
                new ThresholdSensor[DevicePostureController.SUPPORTED_POSTURES_SIZE];
        for (int i = 0; i < DevicePostureController.SUPPORTED_POSTURES_SIZE; i++) {
            mPrimaryProxSensors[i] = sensorBuilder.setSensor(null).setThresholdValue(0).build();
            mSecondaryProxSensors[i] = sensorBuilder.setSensor(null).setThresholdValue(0).build();
        }

        mPrimaryProxSensors[proxExistsForPosture] = sensorBuilder
                .setSensor(mMockedPrimaryProxSensor).setThresholdValue(5).build();
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
                mPostureDependentProximitySensor.mDevicePosture);

        // WHEN the posture changes to DEVICE_POSTURE_CLOSED
        mPostureListener.onPostureChanged(DEVICE_POSTURE_CLOSED);

        // THEN device posture is updated to DEVICE_POSTURE_CLOSED
        assertEquals(DEVICE_POSTURE_CLOSED,
                mPostureDependentProximitySensor.mDevicePosture);

        // WHEN the posture changes to DEVICE_POSTURE_FLIPPED
        mPostureListener.onPostureChanged(DevicePostureController.DEVICE_POSTURE_FLIPPED);

        // THEN device posture is updated to DEVICE_POSTURE_FLIPPED
        assertEquals(DevicePostureController.DEVICE_POSTURE_FLIPPED,
                mPostureDependentProximitySensor.mDevicePosture);

        // WHEN the posture changes to DEVICE_POSTURE_HALF_OPENED
        mPostureListener.onPostureChanged(DevicePostureController.DEVICE_POSTURE_HALF_OPENED);

        // THEN device posture is updated to DEVICE_POSTURE_HALF_OPENED
        assertEquals(DevicePostureController.DEVICE_POSTURE_HALF_OPENED,
                mPostureDependentProximitySensor.mDevicePosture);
    }

    @Test
    public void proxSensorRegisters_proxSensorValid() {
        // GIVEN posture that supports a valid posture with a prox sensor
        capturePostureListener();
        mPostureListener.onPostureChanged(DEVICE_POSTURE_CLOSED);

        // WHEN a listener registers
        mPostureDependentProximitySensor.register(mock(ThresholdSensor.Listener.class));

        // THEN PostureDependentProximitySensor is registered
        assertTrue(mPostureDependentProximitySensor.isRegistered());
    }

    @Test
    public void proxSensorReregisters_postureChangesAndNewlySupportsProx() {
        // GIVEN there's a registered listener but posture doesn't support prox
        assertFalse(mPostureDependentProximitySensor.isRegistered());
        mPostureDependentProximitySensor.register(mock(ThresholdSensor.Listener.class));
        assertFalse(mPostureDependentProximitySensor.isRegistered());

        // WHEN posture that supports a valid posture with a prox sensor
        capturePostureListener();
        mPostureListener.onPostureChanged(DEVICE_POSTURE_CLOSED);

        // THEN PostureDependentProximitySensor is registered
        assertTrue(mPostureDependentProximitySensor.isRegistered());
    }


    private void capturePostureListener() {
        verify(mDevicePostureController).addCallback(mPostureListenerCaptor.capture());
        mPostureListener = mPostureListenerCaptor.getValue();
    }
}
