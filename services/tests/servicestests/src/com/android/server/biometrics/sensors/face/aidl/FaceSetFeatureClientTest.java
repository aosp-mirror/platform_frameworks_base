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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.face.ISession;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.TestableContext;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
public class FaceSetFeatureClientTest {
    private static final String TAG = "FaceSetFeatureClientTest";
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
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;

    private final int mFeature = BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION;
    private final boolean mEnabled = true;
    private final byte[] mHardwareAuthToken = new byte[69];
    private FaceSetFeatureClient mClient;
    TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    @Before
    public void setUp() {
        when(mAidlSession.getSession()).thenReturn(mSession);

        mClient = new FaceSetFeatureClient(mContext, () -> mAidlSession, mToken, mListener, USER_ID,
                TAG, SENSOR_ID, mBiometricLogger, mBiometricContext, mFeature, mEnabled,
                mHardwareAuthToken);
    }

    @Test
    public void setFeature_onFeatureSet() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onFeatureSet(true);
            return null;
        }).when(mSession).setFeature(any(),
                eq(AidlConversionUtils.convertFrameworkToAidlFeature(mFeature)), eq(mEnabled));
        mClient.start(mCallback);

        verify(mSession).setFeature(any(),
                eq(AidlConversionUtils.convertFrameworkToAidlFeature(mFeature)),
                eq(mEnabled));
        verify(mListener).onFeatureSet(true, mFeature);
        verify(mCallback).onClientFinished(mClient, true);
    }

    @Test
    public void setFeature_onError() throws RemoteException {
        doAnswer(invocation -> {
            mClient.onError(0, 0);
            return null;
        }).when(mSession).setFeature(any(),
                eq(AidlConversionUtils.convertFrameworkToAidlFeature(mFeature)),
                eq(mEnabled));
        mClient.start(mCallback);

        verify(mSession).setFeature(any(),
                eq(AidlConversionUtils.convertFrameworkToAidlFeature(mFeature)),
                eq(mEnabled));
        verify(mListener).onFeatureSet(false, mFeature);
        verify(mCallback).onClientFinished(mClient, false);
    }
}
