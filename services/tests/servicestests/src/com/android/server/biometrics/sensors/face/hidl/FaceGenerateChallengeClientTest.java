/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.OptionalUint64;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@SmallTest
public class FaceGenerateChallengeClientTest {

    private static final String TAG = "FaceGenerateChallengeClientTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;
    private static final long START_TIME = 5000;
    private static final long CHALLENGE = 200;

    private final Context mContext = ApplicationProvider.getApplicationContext();

    @Mock
    private IBiometricsFace mIBiometricsFace;
    @Mock
    private IFaceServiceReceiver mClientReceiver;
    @Mock
    private IFaceServiceReceiver mOtherReceiver;
    @Mock
    private ClientMonitorCallback mMonitorCallback;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;

    private FaceGenerateChallengeClient mClient;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        final OptionalUint64 challenge = new OptionalUint64();
        challenge.value = CHALLENGE;
        when(mIBiometricsFace.generateChallenge(anyInt())).thenReturn(challenge);

        mClient = new FaceGenerateChallengeClient(mContext, () -> mIBiometricsFace, new Binder(),
                new ClientMonitorCallbackConverter(mClientReceiver), USER_ID,
                TAG, SENSOR_ID, mBiometricLogger, mBiometricContext , START_TIME);
    }

    @Test
    public void getCreatedAt() {
        assertEquals(START_TIME, mClient.getCreatedAt());
    }

    @Test
    public void reuseResult_whenNotReady() throws Exception {
        mClient.reuseResult(mOtherReceiver);
        verify(mOtherReceiver, never()).onChallengeGenerated(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void reuseResult_whenReady() throws Exception {
        mClient.start(mMonitorCallback);
        mClient.reuseResult(mOtherReceiver);
        verify(mOtherReceiver).onChallengeGenerated(eq(SENSOR_ID), eq(USER_ID), eq(CHALLENGE));
    }

    @Test
    public void reuseResult_whenReallyReady() throws Exception {
        mClient.reuseResult(mOtherReceiver);
        mClient.start(mMonitorCallback);
        verify(mOtherReceiver).onChallengeGenerated(eq(SENSOR_ID), eq(USER_ID), eq(CHALLENGE));
    }
}
