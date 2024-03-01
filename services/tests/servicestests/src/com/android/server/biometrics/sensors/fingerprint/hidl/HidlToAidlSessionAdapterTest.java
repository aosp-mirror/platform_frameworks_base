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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.keymaster.HardwareAuthToken;
import android.hardware.keymaster.Timestamp;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.fingerprint.aidl.AidlResponseHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class HidlToAidlSessionAdapterTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private IBiometricsFingerprint mSession;
    @Mock
    private AidlResponseHandler mAidlResponseHandler;
    @Mock
    private HardwareAuthToken mHardwareAuthToken;

    private final long mChallenge = 100L;
    private final int mUserId = 0;
    private HidlToAidlSessionAdapter mHidlToAidlSessionAdapter;

    @Before
    public void setUp() {
        mHidlToAidlSessionAdapter = new HidlToAidlSessionAdapter(() -> mSession, mUserId,
                mAidlResponseHandler);
        mHardwareAuthToken.timestamp = new Timestamp();
        mHardwareAuthToken.mac = new byte[10];
    }

    @Test
    public void testGenerateChallenge() throws RemoteException {
        when(mSession.preEnroll()).thenReturn(mChallenge);
        mHidlToAidlSessionAdapter.generateChallenge();

        verify(mSession).preEnroll();
        verify(mAidlResponseHandler).onChallengeGenerated(mChallenge);
    }

    @Test
    public void testRevokeChallenge() throws RemoteException {
        mHidlToAidlSessionAdapter.revokeChallenge(mChallenge);

        verify(mSession).postEnroll();
        verify(mAidlResponseHandler).onChallengeRevoked(0L);
    }

    @Test
    public void testEnroll() throws RemoteException {
        final ICancellationSignal cancellationSignal =
                mHidlToAidlSessionAdapter.enroll(mHardwareAuthToken);

        verify(mSession).enroll(any(), anyInt(), eq(HidlToAidlSessionAdapter.ENROLL_TIMEOUT_SEC));

        cancellationSignal.cancel();

        verify(mSession).cancel();
    }

    @Test
    public void testAuthenticate() throws RemoteException {
        final int operationId = 2;
        final ICancellationSignal cancellationSignal = mHidlToAidlSessionAdapter.authenticate(
                operationId);

        verify(mSession).authenticate(operationId, mUserId);

        cancellationSignal.cancel();

        verify(mSession).cancel();
    }

    @Test
    public void testDetectInteraction() throws RemoteException {
        final ICancellationSignal cancellationSignal = mHidlToAidlSessionAdapter
                .detectInteraction();

        verify(mSession).authenticate(0 /* operationId */, mUserId);

        cancellationSignal.cancel();

        verify(mSession).cancel();
    }

    @Test
    public void testEnumerateEnrollments() throws RemoteException {
        mHidlToAidlSessionAdapter.enumerateEnrollments();

        verify(mSession).enumerate();
    }

    @Test
    public void testRemoveEnrollment() throws RemoteException {
        final int[] enrollmentIds = new int[]{1};
        mHidlToAidlSessionAdapter.removeEnrollments(enrollmentIds);

        verify(mSession).remove(mUserId, enrollmentIds[0]);
    }

    @Test
    public void testRemoveMultipleEnrollments() throws RemoteException {
        final int[] enrollmentIds = new int[]{1, 2};
        mHidlToAidlSessionAdapter.removeEnrollments(enrollmentIds);

        verify(mSession).remove(mUserId, 0);
    }

    @Test
    public void testResetLockout() throws RemoteException {
        mHidlToAidlSessionAdapter.resetLockout(mHardwareAuthToken);

        verify(mAidlResponseHandler).onLockoutCleared();
    }
}
