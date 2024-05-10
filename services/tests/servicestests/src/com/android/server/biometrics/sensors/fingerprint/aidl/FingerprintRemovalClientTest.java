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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.fingerprint.Fingerprint;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Presubmit
@SmallTest
public class FingerprintRemovalClientTest {
    private static final String TAG = "FingerprintRemovalClientTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AidlSession mAidlSession;
    @Mock
    private ISession mSession;
    @Mock
    private IBinder mToken;
    @Mock
    private ClientMonitorCallbackConverter mListener;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    private Context mContext;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private BiometricUtils<Fingerprint> mBiometricUtils;
    @Mock
    private Map<Integer, Long> mAuthenticatorIds;

    private FingerprintRemovalClient mClient;
    private int[] mBiometricIds = new int[]{1, 2};

    @Before
    public void setUp() {
        when(mAidlSession.getSession()).thenReturn(mSession);

        mClient = new FingerprintRemovalClient(mContext, () -> mAidlSession, mToken, mListener,
                mBiometricIds, USER_ID, TAG, mBiometricUtils, SENSOR_ID,
                mBiometricLogger, mBiometricContext, mAuthenticatorIds);
    }

    @Test
    public void removalMultipleFingerprints() throws RemoteException {
        when(mBiometricUtils.getBiometricsForUser(any(), anyInt())).thenReturn(
                List.of(new Fingerprint("three", 3, 1)));
        doAnswer(invocation -> {
            mClient.onRemoved(new Fingerprint("one", 1, 1), 1);
            mClient.onRemoved(new Fingerprint("two", 2, 1), 0);
            return null;
        }).when(mSession).removeEnrollments(mBiometricIds);
        mClient.start(mCallback);

        verify(mSession).removeEnrollments(mBiometricIds);
        verify(mBiometricUtils, times(2)).removeBiometricForUser(eq(mContext),
                eq(USER_ID), anyInt());
        verifyNoMoreInteractions(mAuthenticatorIds);
        verify(mListener, times(2)).onRemoved(any(), anyInt());
        verify(mCallback).onClientFinished(mClient, true);
    }

    @Test
    public void removeFingerprint_nullIdentifier() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onRemoved(null, 0);
            return null;
        }).when(mSession).removeEnrollments(mBiometricIds);
        mClient.start(mCallback);

        verify(mSession).removeEnrollments(mBiometricIds);
        verify(mListener).onError(anyInt(), anyInt(), anyInt(), anyInt());
        verify(mCallback).onClientFinished(mClient, false);
    }

    @Test
    public void removeFingerprints_noFingerprintEnrolled() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onRemoved(new Fingerprint("one", 1, 1), 1);
            mClient.onRemoved(new Fingerprint("two", 2, 1), 0);
            return null;
        }).when(mSession).removeEnrollments(mBiometricIds);
        when(mBiometricUtils.getBiometricsForUser(any(), anyInt())).thenReturn(new ArrayList<>());

        mClient.start(mCallback);

        verify(mSession).removeEnrollments(mBiometricIds);
        verify(mBiometricUtils, times(2)).removeBiometricForUser(eq(mContext),
                eq(USER_ID), anyInt());
        verify(mAuthenticatorIds).put(USER_ID, 0L);
        verify(mListener, times(2)).onRemoved(any(), anyInt());
        verify(mCallback).onClientFinished(mClient, true);
    }
}
