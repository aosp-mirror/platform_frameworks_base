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

import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;

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
import android.hardware.face.HidlFaceSensorConfig;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@Presubmit
@SmallTest
public class FaceProviderTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

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
    @Mock
    private AuthenticationStateListeners mAuthenticationStateListeners;

    private final TestLooper mLooper = new TestLooper();
    private SensorProps[] mSensorProps;
    private LockoutResetDispatcher mLockoutResetDispatcher;
    private FaceProvider mFaceProvider;

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
                mAuthenticationStateListeners, mSensorProps, TAG, mLockoutResetDispatcher,
                mBiometricContext, mDaemon, new Handler(mLooper.getLooper()),
                false /* resetLockoutRequiresChallenge */, false /* testHalEnabled */);
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
            assertThat(currentClient.getTargetUserId()).isEqualTo(USER_SYSTEM);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testAddingHidlSensors() {
        when(mResources.getIntArray(anyInt())).thenReturn(new int[]{});
        when(mResources.getBoolean(anyInt())).thenReturn(false);

        final int faceId = 0;
        final int faceStrength = 15;
        final String config = String.format("%d:8:%d", faceId, faceStrength);
        final HidlFaceSensorConfig faceSensorConfig = new HidlFaceSensorConfig();
        faceSensorConfig.parse(config, mContext);
        final HidlFaceSensorConfig[] hidlFaceSensorConfig =
                new HidlFaceSensorConfig[]{faceSensorConfig};
        mFaceProvider = new FaceProvider(mContext,
                mBiometricStateCallback, mAuthenticationStateListeners, hidlFaceSensorConfig, TAG,
                mLockoutResetDispatcher, mBiometricContext, mDaemon,
                new Handler(mLooper.getLooper()),
                true /* resetLockoutRequiresChallenge */,
                true /* testHalEnabled */);

        assertThat(mFaceProvider.mFaceSensors.get(faceId)
                .getLazySession().get().getUserId()).isEqualTo(USER_NULL);

        waitForIdle();

        assertThat(mFaceProvider.mFaceSensors.get(faceId)
                .getLazySession().get().getUserId()).isEqualTo(USER_SYSTEM);
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

    private void waitForIdle() {
        if (Flags.deHidl()) {
            mLooper.dispatchAll();
        } else {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }
}
