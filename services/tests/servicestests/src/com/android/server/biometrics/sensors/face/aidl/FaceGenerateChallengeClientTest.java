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

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.face.ISession;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.test.suitebuilder.annotation.SmallTest;

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
public class FaceGenerateChallengeClientTest {
    private static final String TAG = "FaceGenerateChallengeClientTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;
    private static final long CHALLENGE = 200;

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

    private FaceGenerateChallengeClient mClient;

    @Before
    public void setUp() throws RemoteException {
        when(mAidlSession.getSession()).thenReturn(mSession);
        doAnswer(invocation -> {
            mClient.onChallengeGenerated(SENSOR_ID, USER_ID, CHALLENGE);
            return null;
        }).when(mSession).generateChallenge();
    }

    @Test
    public void generateChallenge() throws RemoteException {
        createClient(mListener);
        mClient.start(mCallback);

        verify(mListener).onChallengeGenerated(SENSOR_ID, USER_ID, CHALLENGE);
        verify(mCallback).onClientFinished(mClient, true);
    }

    @Test
    public void generateChallenge_nullListener() {
        createClient(null);
        mClient.start(mCallback);

        verify(mCallback).onClientFinished(mClient, false);
    }

    private void createClient(ClientMonitorCallbackConverter listener) {
        mClient = new FaceGenerateChallengeClient(mContext, () -> mAidlSession, mToken, listener,
                USER_ID, TAG, SENSOR_ID, mBiometricLogger, mBiometricContext);
    }
}
