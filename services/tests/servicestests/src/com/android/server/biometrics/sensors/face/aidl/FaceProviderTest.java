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

package com.android.server.biometrics.sensors.face.aidl;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.biometrics.common.CommonProps;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.SensorProps;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@Presubmit
@SmallTest
public class FaceProviderTest {

    private static final String TAG = "FaceProviderTest";

    private static final float FRR_THRESHOLD = 0.2f;

    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private Resources mResources;
    @Mock
    private IFace mDaemon;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private BiometricStateCallback mBiometricStateCallback;

    private SensorProps[] mSensorProps;
    private LockoutResetDispatcher mLockoutResetDispatcher;
    private FaceProvider mFaceProvider;

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mUserManager.getAliveUsers()).thenReturn(new ArrayList<>());
        when(mDaemon.createSession(anyInt(), anyInt(), any())).thenReturn(mock(ISession.class));

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getFraction(R.fraction.config_biometricNotificationFrrThreshold, 1, 1))
                .thenReturn(FRR_THRESHOLD);

        final SensorProps sensor1 = new SensorProps();
        sensor1.commonProps = new CommonProps();
        sensor1.commonProps.sensorId = 0;
        final SensorProps sensor2 = new SensorProps();
        sensor2.commonProps = new CommonProps();
        sensor2.commonProps.sensorId = 1;

        mSensorProps = new SensorProps[]{sensor1, sensor2};

        mLockoutResetDispatcher = new LockoutResetDispatcher(mContext);

        mFaceProvider = new FaceProvider(mContext, mBiometricStateCallback,
                mSensorProps, TAG, mLockoutResetDispatcher, mBiometricContext,
                mDaemon);
    }

    @Test
    public void testAddingSensors() {
        waitForIdle();

        for (SensorProps prop : mSensorProps) {
            final BiometricScheduler scheduler =
                    mFaceProvider.mFaceSensors.get(prop.commonProps.sensorId)
                            .getScheduler();
            BaseClientMonitor currentClient = scheduler.getCurrentClient();

            assertThat(currentClient).isInstanceOf(FaceInternalCleanupClient.class);
            assertThat(currentClient.getSensorId()).isEqualTo(prop.commonProps.sensorId);
            assertThat(currentClient.getTargetUserId()).isEqualTo(UserHandle.USER_SYSTEM);
        }
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void halServiceDied_resetsAllSchedulers() {
        waitForIdle();

        assertEquals(mSensorProps.length, mFaceProvider.getSensorProperties().size());

        // Schedule N operations on each sensor
        final int numFakeOperations = 10;
        for (SensorProps prop : mSensorProps) {
            final BiometricScheduler scheduler =
                    mFaceProvider.mFaceSensors.get(prop.commonProps.sensorId).getScheduler();
            scheduler.reset();
            for (int i = 0; i < numFakeOperations; i++) {
                final HalClientMonitor testMonitor = mock(HalClientMonitor.class);
                when(testMonitor.getFreshDaemon()).thenReturn(new Object());
                scheduler.scheduleClientMonitor(testMonitor);
            }
        }

        waitForIdle();
        // The right amount of pending and current operations are scheduled
        for (SensorProps prop : mSensorProps) {
            final BiometricScheduler scheduler =
                    mFaceProvider.mFaceSensors.get(prop.commonProps.sensorId).getScheduler();
            assertEquals(numFakeOperations - 1, scheduler.getCurrentPendingCount());
            assertNotNull(scheduler.getCurrentClient());
        }

        // It's difficult to test the linkToDeath --> serviceDied path, so let's just invoke
        // serviceDied directly.
        mFaceProvider.binderDied();
        waitForIdle();

        // No pending operations, no current operation.
        for (SensorProps prop : mSensorProps) {
            final BiometricScheduler scheduler =
                    mFaceProvider.mFaceSensors.get(prop.commonProps.sensorId).getScheduler();
            assertNull(scheduler.getCurrentClient());
            assertEquals(0, scheduler.getCurrentPendingCount());
        }
    }
}
