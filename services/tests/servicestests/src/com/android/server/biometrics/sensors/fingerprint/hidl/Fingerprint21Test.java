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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.fingerprint.FingerprintStateCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@SmallTest
public class Fingerprint21Test {

    private static final String TAG = "Fingerprint21Test";
    private static final int SENSOR_ID = 1;

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private UserManager mUserManager;
    @Mock
    Fingerprint21.HalResultController mHalResultController;
    @Mock
    private BiometricScheduler mScheduler;
    @Mock
    private FingerprintStateCallback mFingerprintStateCallback;

    private LockoutResetDispatcher mLockoutResetDispatcher;
    private Fingerprint21 mFingerprint21;

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mUserManager.getAliveUsers()).thenReturn(new ArrayList<>());
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getInteger(eq(R.integer.config_fingerprintMaxTemplatesPerUser)))
                .thenReturn(5);

        mLockoutResetDispatcher = new LockoutResetDispatcher(mContext);

        final int maxEnrollmentsPerUser = 1;
        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        final boolean resetLockoutRequiresHardwareAuthToken = false;
        final FingerprintSensorPropertiesInternal sensorProps =
                new FingerprintSensorPropertiesInternal(SENSOR_ID,
                        FingerprintSensorProperties.STRENGTH_WEAK, maxEnrollmentsPerUser,
                        componentInfo, FingerprintSensorProperties.TYPE_UNKNOWN,
                        resetLockoutRequiresHardwareAuthToken);

        mFingerprint21 = new TestableFingerprint21(mContext, mFingerprintStateCallback, sensorProps,
                mScheduler, new Handler(Looper.getMainLooper()), mLockoutResetDispatcher,
                mHalResultController);
    }

    @Test
    public void getAuthenticatorId_doesNotCrashWhenIdNotFound() {
        assertEquals(0, mFingerprint21.getAuthenticatorId(0 /* sensorId */, 111 /* userId */));
        waitForIdle();
    }

    @Test
    public void halServiceDied_resetsScheduler() {
        // It's difficult to test the linkToDeath --> serviceDied path, so let's just invoke
        // serviceDied directly.
        mFingerprint21.serviceDied(0 /* cookie */);
        waitForIdle();
        verify(mScheduler).reset();
    }

    private static class TestableFingerprint21 extends Fingerprint21 {

        TestableFingerprint21(@NonNull Context context,
                @NonNull FingerprintStateCallback fingerprintStateCallback,
                @NonNull FingerprintSensorPropertiesInternal sensorProps,
                @NonNull BiometricScheduler scheduler, @NonNull Handler handler,
                @NonNull LockoutResetDispatcher lockoutResetDispatcher,
                @NonNull HalResultController controller) {
            super(context, fingerprintStateCallback, sensorProps, scheduler, handler,
                    lockoutResetDispatcher, controller);
        }

        @Override
        synchronized IBiometricsFingerprint getDaemon() {
            return mock(IBiometricsFingerprint.class);
        }
    }
}
