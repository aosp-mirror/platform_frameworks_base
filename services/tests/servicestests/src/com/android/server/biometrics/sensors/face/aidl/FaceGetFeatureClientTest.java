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

package com.android.server.biometrics.sensors.face.aidl;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.face.ISession;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

@Presubmit
@SmallTest
public class FaceGetFeatureClientTest {
    private static final String TAG = "FaceGetFeatureClientTest";
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
    TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;

    private final int mFeature = BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION;
    private FaceGetFeatureClient mClient;

    @Before
    public void setUp() throws RemoteException {
        mClient = new FaceGetFeatureClient(mContext, () -> mAidlSession, mToken, mListener,
                USER_ID, TAG, SENSOR_ID, mBiometricLogger, mBiometricContext, mFeature);

        when(mAidlSession.getSession()).thenReturn(mSession);
        doAnswer(invocation -> {
            mClient.onFeatureGet(true, new byte[]{
                    AidlConversionUtils.convertFrameworkToAidlFeature(mFeature)});
            return null;
        }).when(mSession).getFeatures();
    }

    @Test
    public void getFeature() throws RemoteException {
        ArgumentCaptor<int[]> featuresToSend = ArgumentCaptor.forClass(int[].class);
        ArgumentCaptor<boolean[]> featureState = ArgumentCaptor.forClass(boolean[].class);
        mClient.start(mCallback);

        verify(mListener).onFeatureGet(eq(true), featuresToSend.capture(),
                featureState.capture());
        assertThat(featuresToSend.getValue()).asList().containsExactlyElementsIn(List.of(mFeature));
        assertThat(featureState.getValue()).asList().containsExactlyElementsIn(List.of(true));
    }
}
