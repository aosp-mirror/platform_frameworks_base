/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import static android.hardware.fingerprint.FingerprintManager.ENROLL_ENROLL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.HidlFingerprintSensorConfig;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;
import com.android.server.biometrics.sensors.fingerprint.aidl.AidlResponseHandler;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintEnrollClient;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintResetLockoutClient;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class HidlToAidlSensorAdapterTest {

    private static final String TAG = "HidlToAidlSensorAdapterTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;
    private static final byte[] HAT = new byte[69];

    @Rule
    public final MockitoRule mMockito = MockitoJUnit.rule();

    @Mock
    private IBiometricService mBiometricService;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcherForSensor;
    @Mock
    private LockoutResetDispatcher mLockoutResetDispatcherForClient;
    @Mock
    private BiometricLogger mLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private FingerprintProvider mFingerprintProvider;
    @Mock
    private GestureAvailabilityDispatcher mGestureAvailabilityDispatcher;
    @Mock
    private Runnable mInternalCleanupRunnable;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private IBiometricsFingerprint mDaemon;
    @Mock
    private AidlResponseHandler.AidlResponseHandlerCallback mAidlResponseHandlerCallback;
    @Mock
    private BiometricUtils<Fingerprint> mBiometricUtils;
    @Mock
    private AuthenticationStateListeners mAuthenticationStateListeners;
    @Mock
    private HandlerThread mThread;

    private final TestLooper mLooper = new TestLooper();
    private HidlToAidlSensorAdapter mHidlToAidlSensorAdapter;
    private final TestableContext mContext = new TestableContext(
            ApplicationProvider.getApplicationContext());

    @Before
    public void setUp() throws RemoteException {
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
        when(mThread.getLooper()).thenReturn(mLooper.getLooper());
        doAnswer((answer) -> {
            mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback()
                    .onEnrollmentProgress(1 /* enrollmentId */, 0 /* remaining */);
            return null;
        }).when(mDaemon).enroll(any(), anyInt(), anyInt());

        mContext.addMockSystemService(AlarmManager.class, mAlarmManager);
        mContext.getOrCreateTestableResources();

        final String config = String.format("%d:2:15", SENSOR_ID);
        final HidlFingerprintSensorConfig fingerprintSensorConfig =
                new HidlFingerprintSensorConfig();
        fingerprintSensorConfig.parse(config, mContext);
        mHidlToAidlSensorAdapter = new HidlToAidlSensorAdapter(
                mFingerprintProvider, mContext, new Handler(mLooper.getLooper()),
                fingerprintSensorConfig, mLockoutResetDispatcherForSensor,
                mBiometricContext, false /* resetLockoutRequiresHardwareAuthToken */,
                mInternalCleanupRunnable, mAuthSessionCoordinator, mDaemon,
                mAidlResponseHandlerCallback);
        mHidlToAidlSensorAdapter.init(mGestureAvailabilityDispatcher,
                mLockoutResetDispatcherForSensor);

        mHidlToAidlSensorAdapter.handleUserChanged(USER_ID);
    }

    @Test
    public void lockoutTimedResetViaClient() {
        setLockoutTimed();

        mHidlToAidlSensorAdapter.getScheduler().scheduleClientMonitor(
                new FingerprintResetLockoutClient(mContext,
                        mHidlToAidlSensorAdapter.getLazySession(),
                        USER_ID, TAG, SENSOR_ID, mLogger, mBiometricContext, HAT,
                        mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */),
                        mLockoutResetDispatcherForClient, 0 /* biometricStrength */));
        mLooper.dispatchAll();

        verify(mAlarmManager).setExact(anyInt(), anyLong(), any());
        verify(mLockoutResetDispatcherForClient).notifyLockoutResetCallbacks(SENSOR_ID);
        verify(mLockoutResetDispatcherForSensor).notifyLockoutResetCallbacks(SENSOR_ID);
        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_NONE);
        verify(mAuthSessionCoordinator).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    @Test
    public void lockoutTimedResetViaCallback() {
        setLockoutTimed();

        mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback().onLockoutCleared();
        mLooper.dispatchAll();

        verify(mLockoutResetDispatcherForSensor, times(2)).notifyLockoutResetCallbacks(eq(
                SENSOR_ID));
        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_NONE);
        verify(mAuthSessionCoordinator).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    @Test
    public void lockoutPermanentResetViaCallback() {
        setLockoutPermanent();

        mHidlToAidlSensorAdapter.getLazySession().get().getHalSessionCallback().onLockoutCleared();
        mLooper.dispatchAll();

        verify(mLockoutResetDispatcherForSensor, times(2)).notifyLockoutResetCallbacks(eq(
                SENSOR_ID));
        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_NONE);
        verify(mAuthSessionCoordinator).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    @Test
    @Ignore("b/317403648")
    public void lockoutPermanentResetViaClient() {
        setLockoutPermanent();

        mHidlToAidlSensorAdapter.getScheduler().scheduleClientMonitor(
                new FingerprintResetLockoutClient(mContext,
                        mHidlToAidlSensorAdapter.getLazySession(),
                        USER_ID, TAG, SENSOR_ID, mLogger, mBiometricContext, HAT,
                        mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */),
                        mLockoutResetDispatcherForClient, 0 /* biometricStrength */));
        mLooper.dispatchAll();

        verify(mAlarmManager, atLeast(1)).setExact(anyInt(), anyLong(), any());
        verify(mLockoutResetDispatcherForClient).notifyLockoutResetCallbacks(SENSOR_ID);
        verify(mLockoutResetDispatcherForSensor).notifyLockoutResetCallbacks(SENSOR_ID);
        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_NONE);
        verify(mAuthSessionCoordinator).resetLockoutFor(eq(USER_ID), anyInt(), anyLong());
    }

    @Test
    public void verifyOnEnrollSuccessCallback() {
        mHidlToAidlSensorAdapter.getScheduler().scheduleClientMonitor(new FingerprintEnrollClient(
                mContext, mHidlToAidlSensorAdapter.getLazySession(), null /* token */,
                1 /* requestId */, null /* listener */, USER_ID, HAT, TAG, mBiometricUtils,
                SENSOR_ID, mLogger, mBiometricContext,
                mHidlToAidlSensorAdapter.getSensorProperties(), null, null,
                mAuthenticationStateListeners, 5 /* maxTemplatesPerUser */, ENROLL_ENROLL));
        mLooper.dispatchAll();

        verify(mAidlResponseHandlerCallback).onEnrollSuccess();
    }

    private void setLockoutPermanent() {
        for (int i = 0; i < 20; i++) {
            mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                    .addFailedAttemptForUser(USER_ID);
        }

        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_PERMANENT);
    }

    private void setLockoutTimed() {
        for (int i = 0; i < 5; i++) {
            mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                    .addFailedAttemptForUser(USER_ID);
        }

        assertThat(mHidlToAidlSensorAdapter.getLockoutTracker(false /* forAuth */)
                .getLockoutModeForUser(USER_ID)).isEqualTo(LockoutTracker.LOCKOUT_TIMED);
    }
}
