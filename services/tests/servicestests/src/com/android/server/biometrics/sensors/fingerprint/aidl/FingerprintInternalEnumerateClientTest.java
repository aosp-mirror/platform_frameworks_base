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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Presubmit
@SmallTest
public class FingerprintInternalEnumerateClientTest {
    private static final String TAG = "FingerprintInternalEnumerateClientTest";
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
    private ClientMonitorCallback mCallback;
    @Mock
    private Context mContext;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private BiometricUtils<Fingerprint> mBiometricUtils;

    private FingerprintInternalEnumerateClient mClient;

    private boolean mNotificationSent;

    @Before
    public void setUp() {
        when(mAidlSession.getSession()).thenReturn(mSession);

        List<Fingerprint> enrolled = new ArrayList<>();
        enrolled.add(new Fingerprint("one", 1, 1));
        mClient = spy(new FingerprintInternalEnumerateClient(mContext, () -> mAidlSession, mToken,
                USER_ID, TAG, enrolled, mBiometricUtils, SENSOR_ID, mBiometricLogger,
                mBiometricContext));

        mNotificationSent = false;
        doAnswer(invocation -> {
            mNotificationSent = true;
            return null;
        }).when(mClient).sendDanglingNotification(anyList());
    }

    @Test
    public void internalEnumerate_unknownTemplates() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onEnumerationResult(new Fingerprint("two", 2, 1), 1);
            mClient.onEnumerationResult(new Fingerprint("three", 3, 1), 0);
            return null;
        }).when(mSession).enumerateEnrollments();
        mClient.start(mCallback);

        verify(mSession).enumerateEnrollments();
        assertThat(mClient.getUnknownHALTemplates().stream()
                .flatMap(x -> Stream.of(x.getBiometricId()))
                .collect(Collectors.toList())).containsExactly(2, 3);
        assertThat(mNotificationSent).isTrue();
        verify(mBiometricUtils).removeBiometricForUser(mContext, USER_ID, 1);
        verify(mCallback).onClientFinished(mClient, true);
    }

    @Test
    public void internalEnumerate_noUnknownTemplates() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onEnumerationResult(new Fingerprint("one", 1, 1), 0);
            return null;
        }).when(mSession).enumerateEnrollments();
        mClient.start(mCallback);

        verify(mSession).enumerateEnrollments();
        assertThat(mClient.getUnknownHALTemplates().size()).isEqualTo(0);
        assertThat(mNotificationSent).isFalse();
        verify(mBiometricUtils, never()).removeBiometricForUser(any(), anyInt(), anyInt());
        verify(mCallback).onClientFinished(mClient, true);
    }
}
