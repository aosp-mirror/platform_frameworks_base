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

package com.android.server.biometrics.sensors.face.hidl;

import static com.android.server.biometrics.sensors.face.hidl.HidlToAidlSessionAdapter.ENROLL_TIMEOUT_SEC;
import static com.android.server.biometrics.sensors.face.hidl.HidlToAidlSessionAdapter.CHALLENGE_TIMEOUT_SEC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.EnrollmentType;
import android.hardware.biometrics.face.Feature;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.biometrics.face.V1_0.OptionalUint64;
import android.hardware.biometrics.face.V1_0.Status;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.keymaster.HardwareAuthToken;
import android.hardware.keymaster.Timestamp;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.face.aidl.AidlConversionUtils;
import com.android.server.biometrics.sensors.face.aidl.AidlResponseHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Presubmit
@SmallTest
public class HidlToAidlSessionAdapterTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private IBiometricsFace mSession;
    @Mock
    FaceManager mFaceManager;
    @Mock
    private AidlResponseHandler mAidlResponseHandler;
    @Mock
    private HardwareAuthToken mHardwareAuthToken;
    @Mock
    private Clock mClock;

    private final long mChallenge = 100L;
    private HidlToAidlSessionAdapter mHidlToAidlSessionAdapter;
    private final Face mFace = new Face("face" /* name */, 1 /* faceId */, 0 /* deviceId */);
    private final int mFeature = BiometricFaceConstants.FEATURE_REQUIRE_REQUIRE_DIVERSITY;
    private final byte[] mFeatures = new byte[]{Feature.REQUIRE_ATTENTION};

    @Before
    public void setUp() throws RemoteException {
        final OptionalUint64 setCallbackResult = new OptionalUint64();
        setCallbackResult.value = 1;

        when(mSession.setCallback(any())).thenReturn(setCallbackResult);

        TestableContext testableContext = new TestableContext(
                InstrumentationRegistry.getInstrumentation().getContext());
        testableContext.addMockSystemService(FaceManager.class, mFaceManager);

        mHidlToAidlSessionAdapter = new HidlToAidlSessionAdapter(testableContext, () -> mSession,
                0 /* userId */, mAidlResponseHandler, mClock);
        mHardwareAuthToken.timestamp = new Timestamp();
        mHardwareAuthToken.mac = new byte[10];

        final OptionalUint64 generateChallengeResult = new OptionalUint64();
        generateChallengeResult.status = Status.OK;
        generateChallengeResult.value = mChallenge;

        when(mSession.generateChallenge(anyInt())).thenReturn(generateChallengeResult);
        when(mFaceManager.getEnrolledFaces(anyInt())).thenReturn(List.of(mFace));
    }

    @Test
    public void testGenerateChallengeCache() throws RemoteException {
        verify(mSession).setCallback(any());

        final ArgumentCaptor<Long> challengeCaptor = ArgumentCaptor.forClass(Long.class);

        mHidlToAidlSessionAdapter.generateChallenge();

        verify(mSession).generateChallenge(CHALLENGE_TIMEOUT_SEC);
        verify(mAidlResponseHandler).onChallengeGenerated(challengeCaptor.capture());
        assertThat(challengeCaptor.getValue()).isEqualTo(mChallenge);

        forwardTime(10 /* seconds */);
        mHidlToAidlSessionAdapter.generateChallenge();
        forwardTime(20 /* seconds */);
        mHidlToAidlSessionAdapter.generateChallenge();

        //Confirms that the challenge is cached and the hal method is not called again
        verifyNoMoreInteractions(mSession);
        verify(mAidlResponseHandler, times(3))
                .onChallengeGenerated(mChallenge);

        forwardTime(60 /* seconds */);
        mHidlToAidlSessionAdapter.generateChallenge();

        //HAL method called after challenge has timed out
        verify(mSession, times(2)).generateChallenge(CHALLENGE_TIMEOUT_SEC);
    }

    @Test
    public void testRevokeChallenge_waitsUntilEmpty() throws RemoteException {
        for (int i = 0; i < 3; i++) {
            mHidlToAidlSessionAdapter.generateChallenge();
            forwardTime(10 /* seconds */);
        }
        for (int i = 0; i < 3; i++) {
            mHidlToAidlSessionAdapter.revokeChallenge(0);
            forwardTime((i + 1) * 10 /* seconds */);
        }

        verify(mSession).revokeChallenge();
    }

    @Test
    public void testRevokeChallenge_timeout() throws RemoteException {
        mHidlToAidlSessionAdapter.generateChallenge();
        mHidlToAidlSessionAdapter.generateChallenge();
        forwardTime(700);
        mHidlToAidlSessionAdapter.generateChallenge();
        mHidlToAidlSessionAdapter.revokeChallenge(0);

        verify(mSession).revokeChallenge();
    }

    @Test
    public void testEnroll() throws RemoteException {
        ICancellationSignal cancellationSignal = mHidlToAidlSessionAdapter.enroll(
                mHardwareAuthToken, EnrollmentType.DEFAULT, mFeatures, null /* previewSurface */);
        ArgumentCaptor<ArrayList<Integer>> featureCaptor = ArgumentCaptor.forClass(ArrayList.class);

        verify(mSession).enroll(any(), eq(ENROLL_TIMEOUT_SEC), featureCaptor.capture());

        ArrayList<Integer> features = featureCaptor.getValue();

        assertThat(features).containsExactly(
                AidlConversionUtils.convertAidlToFrameworkFeature(mFeatures[0]));

        cancellationSignal.cancel();

        verify(mSession).cancel();
    }

    @Test
    public void testAuthenticate() throws RemoteException {
        final int operationId = 2;
        ICancellationSignal cancellationSignal = mHidlToAidlSessionAdapter.authenticate(
                operationId);

        verify(mSession).authenticate(operationId);

        cancellationSignal.cancel();

        verify(mSession).cancel();
    }

    @Test
    public void testDetectInteraction() throws RemoteException {
        ICancellationSignal cancellationSignal = mHidlToAidlSessionAdapter.detectInteraction();

        verify(mSession).authenticate(0);

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
        final int[] enrollments = new int[]{1};
        mHidlToAidlSessionAdapter.removeEnrollments(enrollments);

        verify(mSession).remove(enrollments[0]);
    }

    @Test
    public void testGetFeatures_onResultSuccess() throws RemoteException {
        final OptionalBool result = new OptionalBool();
        result.status = Status.OK;
        result.value = true;
        ArgumentCaptor<byte[]> featureRetrieved = ArgumentCaptor.forClass(byte[].class);

        when(mSession.getFeature(eq(mFeature), anyInt())).thenReturn(result);

        mHidlToAidlSessionAdapter.setFeature(mFeature);
        mHidlToAidlSessionAdapter.getFeatures();

        verify(mSession).getFeature(eq(mFeature), anyInt());
        verify(mAidlResponseHandler).onFeaturesRetrieved(featureRetrieved.capture());
        assertThat(featureRetrieved.getValue()[0]).isEqualTo(
                AidlConversionUtils.convertFrameworkToAidlFeature(mFeature));
    }

    @Test
    public void testGetFeatures_onResultFailed() throws RemoteException {
        final OptionalBool result = new OptionalBool();
        result.status = Status.OK;
        result.value = false;
        ArgumentCaptor<byte[]> featureRetrieved = ArgumentCaptor.forClass(byte[].class);

        when(mSession.getFeature(eq(mFeature), anyInt())).thenReturn(result);

        mHidlToAidlSessionAdapter.setFeature(mFeature);
        mHidlToAidlSessionAdapter.getFeatures();

        verify(mSession).getFeature(eq(mFeature), anyInt());
        verify(mAidlResponseHandler).onFeaturesRetrieved(featureRetrieved.capture());
        assertThat(featureRetrieved.getValue().length).isEqualTo(0);
    }

    @Test
    public void testGetFeatures_onStatusFailed() throws RemoteException {
        final OptionalBool result = new OptionalBool();
        result.status = Status.INTERNAL_ERROR;
        result.value = false;

        when(mSession.getFeature(eq(mFeature), anyInt())).thenReturn(result);

        mHidlToAidlSessionAdapter.setFeature(mFeature);
        mHidlToAidlSessionAdapter.getFeatures();

        verify(mSession).getFeature(eq(mFeature), anyInt());
        verify(mAidlResponseHandler, never()).onFeaturesRetrieved(any());
        verify(mAidlResponseHandler).onError(BiometricFaceConstants.FACE_ERROR_UNKNOWN, 0);
    }

    @Test
    public void testGetFeatures_featureNotSet() throws RemoteException {
        mHidlToAidlSessionAdapter.getFeatures();

        verify(mSession, never()).getFeature(eq(mFeature), anyInt());
        verify(mAidlResponseHandler, never()).onFeaturesRetrieved(any());
    }

    @Test
    public void testSetFeatureSuccessful() throws RemoteException {
        byte feature = Feature.REQUIRE_ATTENTION;
        boolean enabled = true;

        when(mSession.setFeature(anyInt(), anyBoolean(), any(), anyInt())).thenReturn(Status.OK);

        mHidlToAidlSessionAdapter.setFeature(mHardwareAuthToken, feature, enabled);

        verify(mAidlResponseHandler).onFeatureSet(feature);
    }

    @Test
    public void testSetFeatureFailed() throws RemoteException {
        byte feature = Feature.REQUIRE_ATTENTION;
        boolean enabled = true;

        when(mSession.setFeature(anyInt(), anyBoolean(), any(), anyInt()))
                .thenReturn(Status.INTERNAL_ERROR);

        mHidlToAidlSessionAdapter.setFeature(mHardwareAuthToken, feature, enabled);

        verify(mAidlResponseHandler).onError(BiometricFaceConstants.FACE_ERROR_UNKNOWN,
                0 /* vendorCode */);
    }

    @Test
    public void testGetAuthenticatorId() throws RemoteException {
        final long authenticatorId = 2L;
        final OptionalUint64 result = new OptionalUint64();
        result.status = Status.OK;
        result.value = authenticatorId;

        when(mSession.getAuthenticatorId()).thenReturn(result);

        mHidlToAidlSessionAdapter.getAuthenticatorId();

        verify(mSession).getAuthenticatorId();
        verify(mAidlResponseHandler).onAuthenticatorIdRetrieved(authenticatorId);
    }

    @Test
    public void testResetLockout() throws RemoteException {
        mHidlToAidlSessionAdapter.resetLockout(mHardwareAuthToken);

        ArgumentCaptor<ArrayList> hatCaptor = ArgumentCaptor.forClass(ArrayList.class);

        verify(mSession).resetLockout(hatCaptor.capture());

        assertThat(hatCaptor.getValue()).containsExactlyElementsIn(processHAT(mHardwareAuthToken));
    }

    private ArrayList<Byte> processHAT(HardwareAuthToken hat) {
        ArrayList<Byte> hardwareAuthToken = new ArrayList<>();
        for (byte b : HardwareAuthTokenUtils.toByteArray(hat)) {
            hardwareAuthToken.add(b);
        }
        return hardwareAuthToken;
    }

    private void forwardTime(long seconds) {
        when(mClock.millis()).thenReturn(seconds * 1000);
    }
}
