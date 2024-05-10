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

package android.hardware.fingerprint;

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_UNABLE_TO_PROCESS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class FingerprintManagerTest {
    private static final int USER_ID = 9;
    private static final String PACKAGE_NAME = "finger.food.test";
    private static final String ATTRIBUTION_TAG = "taz";

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private IFingerprintService mService;
    @Mock
    private FingerprintManager.AuthenticationCallback mAuthCallback;
    @Mock
    private FingerprintManager.EnrollmentCallback mEnrollCallback;
    @Mock
    private FingerprintManager.FingerprintDetectionCallback mFingerprintDetectionCallback;

    @Captor
    private ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback> mCaptor;
    @Captor
    private ArgumentCaptor<FingerprintAuthenticateOptions> mOptionsCaptor;

    private List<FingerprintSensorPropertiesInternal> mProps;
    private TestLooper mLooper;
    private Handler mHandler;
    private FingerprintManager mFingerprintManager;

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());

        when(mContext.getMainLooper()).thenReturn(mLooper.getLooper());
        when(mContext.getOpPackageName()).thenReturn(PACKAGE_NAME);
        when(mContext.getAttributionTag()).thenReturn(ATTRIBUTION_TAG);
        when(mContext.getApplicationInfo()).thenReturn(new ApplicationInfo());
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(anyInt())).thenReturn("string");

        mFingerprintManager = new FingerprintManager(mContext, mService);
        mProps = List.of(new FingerprintSensorPropertiesInternal(
                0 /* sensorId */,
                FingerprintSensorProperties.STRENGTH_STRONG,
                1 /* maxEnrollmentsPerUser */,
                new ArrayList<>() /* componentInfo */,
                FingerprintSensorProperties.TYPE_UNKNOWN,
                true /* halControlsIllumination */,
                true /* resetLockoutRequiresHardwareAuthToken */,
                new ArrayList<>() /* sensorLocations */));
    }

    @Test
    public void getSensorPropertiesInternal_noBinderCalls() throws RemoteException {
        verify(mService).addAuthenticatorsRegisteredCallback(mCaptor.capture());

        mCaptor.getValue().onAllAuthenticatorsRegistered(mProps);
        List<FingerprintSensorPropertiesInternal> actual =
                mFingerprintManager.getSensorPropertiesInternal();

        assertThat(actual).containsExactlyElementsIn(mProps);
        verify(mService, never()).getSensorPropertiesInternal(any());
    }

    @Test
    public void authenticate_withOptions() throws Exception {
        mFingerprintManager.authenticate(null, new CancellationSignal(), mAuthCallback, mHandler,
                new FingerprintAuthenticateOptions.Builder()
                        .setUserId(USER_ID)
                        .setOpPackageName("some.thing")
                        .setAttributionTag(null)
                        .build());

        verify(mService).authenticate(any(IBinder.class), eq(0L),
                any(IFingerprintServiceReceiver.class), mOptionsCaptor.capture());

        assertThat(mOptionsCaptor.getValue()).isEqualTo(
                new FingerprintAuthenticateOptions.Builder()
                        .setUserId(USER_ID)
                        .setOpPackageName(PACKAGE_NAME)
                        .setAttributionTag(ATTRIBUTION_TAG)
                        .build()
        );
    }

    @Test
    public void authenticate_errorWhenUnavailable() throws Exception {
        when(mService.authenticate(any(), anyLong(), any(), any()))
                .thenThrow(new RemoteException());

        mFingerprintManager.authenticate(null, new CancellationSignal(),
                mAuthCallback, mHandler,
                new FingerprintAuthenticateOptions.Builder().build());

        verify(mAuthCallback).onAuthenticationError(eq(FINGERPRINT_ERROR_HW_UNAVAILABLE), any());
    }

    @Test
    public void enrollment_errorWhenHardwareAuthTokenIsNull() throws RemoteException {
        verify(mService).addAuthenticatorsRegisteredCallback(mCaptor.capture());

        mCaptor.getValue().onAllAuthenticatorsRegistered(mProps);
        mFingerprintManager.enroll(null, new CancellationSignal(), USER_ID,
                mEnrollCallback, FingerprintManager.ENROLL_ENROLL);

        verify(mEnrollCallback).onEnrollmentError(eq(FINGERPRINT_ERROR_UNABLE_TO_PROCESS),
                anyString());
        verify(mService, never()).enroll(any(), any(), anyInt(), any(), anyString(), anyInt());
    }

    @Test
    public void detectClient_onError() throws RemoteException {
        ArgumentCaptor<IFingerprintServiceReceiver> argumentCaptor =
                ArgumentCaptor.forClass(IFingerprintServiceReceiver.class);

        mFingerprintManager.detectFingerprint(new CancellationSignal(),
                mFingerprintDetectionCallback,
                new FingerprintAuthenticateOptions.Builder().build());

        verify(mService).detectFingerprint(any(), argumentCaptor.capture(), any());

        argumentCaptor.getValue().onError(5 /* error */, 0 /* vendorCode */);
        mLooper.dispatchAll();

        verify(mFingerprintDetectionCallback).onDetectionError(anyInt());
    }
}
