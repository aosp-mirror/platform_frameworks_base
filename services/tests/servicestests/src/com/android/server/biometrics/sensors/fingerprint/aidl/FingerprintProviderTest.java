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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.common.CommonProps;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.SensorLocation;
import android.hardware.biometrics.fingerprint.SensorProps;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.HidlFingerprintSensorConfig;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.BiometricHandlerProvider;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@Presubmit
@SmallTest
public class FingerprintProviderTest {

    private static final String TAG = "FingerprintProviderTest";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private UserManager mUserManager;
    @Mock
    private IFingerprint mDaemon;
    @Mock
    private GestureAvailabilityDispatcher mGestureAvailabilityDispatcher;
    @Mock
    private AuthenticationStateListeners mAuthenticationStateListeners;
    @Mock
    private BiometricStateCallback mBiometricStateCallback;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private BiometricHandlerProvider mBiometricHandlerProvider;
    @Mock
    private Handler mBiometricCallbackHandler;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private BiometricScheduler<IFingerprint, ISession> mScheduler;

    private final TestLooper mLooper = new TestLooper();

    private SensorProps[] mSensorProps;
    private LockoutResetDispatcher mLockoutResetDispatcher;
    private FingerprintProvider mFingerprintProvider;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.obtainTypedArray(anyInt())).thenReturn(mock(TypedArray.class));
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mUserManager.getAliveUsers()).thenReturn(new ArrayList<>());
        when(mDaemon.createSession(anyInt(), anyInt(), any())).thenReturn(mock(ISession.class));
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
        when(mBiometricHandlerProvider.getBiometricCallbackHandler()).thenReturn(
                mBiometricCallbackHandler);
        if (Flags.deHidl()) {
            when(mBiometricHandlerProvider.getFingerprintHandler()).thenReturn(
                    new Handler(mLooper.getLooper()));
        } else {
            when(mBiometricHandlerProvider.getFingerprintHandler()).thenReturn(
                    new Handler(Looper.getMainLooper()));
        }

        final SensorProps sensor1 = new SensorProps();
        sensor1.commonProps = new CommonProps();
        sensor1.commonProps.sensorId = 0;
        sensor1.sensorLocations = new SensorLocation[]{new SensorLocation()};
        final SensorProps sensor2 = new SensorProps();
        sensor2.commonProps = new CommonProps();
        sensor2.commonProps.sensorId = 1;
        sensor2.sensorLocations = new SensorLocation[]{new SensorLocation()};

        mSensorProps = new SensorProps[]{sensor1, sensor2};

        mLockoutResetDispatcher = new LockoutResetDispatcher(mContext);

        mFingerprintProvider = new FingerprintProvider(mContext,
                mBiometricStateCallback, mAuthenticationStateListeners, mSensorProps, TAG,
                mLockoutResetDispatcher, mGestureAvailabilityDispatcher, mBiometricContext,
                mDaemon, mBiometricHandlerProvider,
                false /* resetLockoutRequiresHardwareAuthToken */, true /* testHalEnabled */);
    }

    @Test
    public void testAddingSensors() {
        waitForIdle();

        for (SensorProps prop : mSensorProps) {
            final Sensor sensor =
                    mFingerprintProvider.mFingerprintSensors.get(prop.commonProps.sensorId);
            assertThat(sensor.getLazySession().get().getUserId()).isEqualTo(USER_SYSTEM);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testAddingHidlSensors() {
        when(mResources.getIntArray(anyInt())).thenReturn(new int[]{});
        when(mResources.getBoolean(anyInt())).thenReturn(false);

        final int fingerprintId = 0;
        final int fingerprintStrength = 15;
        final String config = String.format("%d:2:%d", fingerprintId, fingerprintStrength);
        final HidlFingerprintSensorConfig fingerprintSensorConfig =
                new HidlFingerprintSensorConfig();
        fingerprintSensorConfig.parse(config, mContext);
        HidlFingerprintSensorConfig[] hidlFingerprintSensorConfigs =
                new HidlFingerprintSensorConfig[]{fingerprintSensorConfig};
        mFingerprintProvider = new FingerprintProvider(mContext,
                mBiometricStateCallback, mAuthenticationStateListeners,
                hidlFingerprintSensorConfigs, TAG, mLockoutResetDispatcher,
                mGestureAvailabilityDispatcher, mBiometricContext, mDaemon,
                mBiometricHandlerProvider,
                false /* resetLockoutRequiresHardwareAuthToken */,
                true /* testHalEnabled */);

        assertThat(mFingerprintProvider.mFingerprintSensors.get(fingerprintId)
                .getLazySession().get().getUserId()).isEqualTo(USER_NULL);

        waitForIdle();

        assertThat(mFingerprintProvider.mFingerprintSensors.get(fingerprintId)
                .getLazySession().get().getUserId()).isEqualTo(USER_SYSTEM);
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void halServiceDied_resetsAllSchedulers() {
        waitForIdle();
        assertEquals(mSensorProps.length, mFingerprintProvider.getSensorProperties().size());

        // Schedule N operations on each sensor
        final int numFakeOperations = 10;
        for (SensorProps prop : mSensorProps) {
            final BiometricScheduler scheduler =
                    mFingerprintProvider.mFingerprintSensors.get(prop.commonProps.sensorId)
                            .getScheduler();
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
                    mFingerprintProvider.mFingerprintSensors.get(prop.commonProps.sensorId)
                            .getScheduler();
            assertEquals(numFakeOperations - 1, scheduler.getCurrentPendingCount());
            assertNotNull(scheduler.getCurrentClient());
        }

        // It's difficult to test the linkToDeath --> serviceDied path, so let's just invoke
        // serviceDied directly.
        mFingerprintProvider.binderDied();
        waitForIdle();

        // No pending operations, no current operation.
        for (SensorProps prop : mSensorProps) {
            final BiometricScheduler scheduler =
                    mFingerprintProvider.mFingerprintSensors.get(prop.commonProps.sensorId)
                            .getScheduler();
            assertNull(scheduler.getCurrentClient());
            assertEquals(0, scheduler.getCurrentPendingCount());
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DE_HIDL)
    public void testScheduleAuthenticate() {
        waitForIdle();

        mFingerprintProvider.mFingerprintSensors.get(0).setScheduler(mScheduler);
        mFingerprintProvider.scheduleAuthenticate(mock(IBinder.class), 0 /* operationId */,
                0 /* cookie */, new ClientMonitorCallbackConverter(
                        new IBiometricSensorReceiver.Default()),
                new FingerprintAuthenticateOptions.Builder()
                        .setSensorId(0)
                        .build(),
                false /* restricted */, 1 /* statsClient */,
                true /* allowBackgroundAuthentication */);

        waitForIdle();

        ArgumentCaptor<ClientMonitorCallback> callbackArgumentCaptor = ArgumentCaptor.forClass(
                ClientMonitorCallback.class);
        ArgumentCaptor<BaseClientMonitor> clientMonitorArgumentCaptor = ArgumentCaptor.forClass(
                BaseClientMonitor.class);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(
                Message.class);

        verify(mScheduler).scheduleClientMonitor(clientMonitorArgumentCaptor.capture(),
                callbackArgumentCaptor.capture());

        BaseClientMonitor client = clientMonitorArgumentCaptor.getValue();
        ClientMonitorCallback callback = callbackArgumentCaptor.getValue();
        callback.onClientStarted(client);

        verify(mBiometricStateCallback).onClientStarted(eq(client));
        verify(mBiometricCallbackHandler).sendMessageAtTime(messageCaptor.capture(), anyLong());

        messageCaptor.getValue().getCallback().run();

        verify(mAuthSessionCoordinator).authStartedFor(anyInt(), anyInt(), anyLong());

        callback.onClientFinished(client, true /* success */);

        verify(mBiometricStateCallback).onClientFinished(eq(client), eq(true /* success */));
        verify(mBiometricCallbackHandler, times(2)).sendMessageAtTime(
                messageCaptor.capture(), anyLong());

        messageCaptor.getValue().getCallback().run();

        verify(mAuthSessionCoordinator).authEndedFor(anyInt(), anyInt(), anyInt(), anyLong(),
                anyBoolean());
    }

    private void waitForIdle() {
        if (Flags.deHidl()) {
            mLooper.dispatchAll();
        } else {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }
}
