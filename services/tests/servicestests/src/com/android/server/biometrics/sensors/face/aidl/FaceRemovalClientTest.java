/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.Face;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

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

import java.util.HashMap;
import java.util.Map;

@Presubmit
@SmallTest
public class FaceRemovalClientTest {

    private static final int USER_ID = 12;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private ISession mHal;
    @Mock
    private IBinder mToken;
    @Mock
    private ClientMonitorCallbackConverter mClientMonitorCallbackConverter;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    private AidlResponseHandler mAidlResponseHandler;
    @Mock
    private BiometricUtils<Face> mUtils;
    @Mock
    private BiometricAuthenticator.Identifier mIdentifier;
    private Map<Integer, Long> mAuthenticatorIds = new HashMap<Integer, Long>();

    @Before
    public void setup() {
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
    }

    @Test
    public void testFaceRemovalClient() throws RemoteException {
        final int authenticatorId = 1;
        int[] authenticatorIds = new int[]{authenticatorId};
        final FaceRemovalClient client = createClient(1, authenticatorIds);
        when(mIdentifier.getBiometricId()).thenReturn(authenticatorId);
        client.start(mCallback);
        verify(mHal).removeEnrollments(authenticatorIds);
        client.onRemoved(mIdentifier, 0 /* remaining */);
        verify(mClientMonitorCallbackConverter).onRemoved(
                eq(mIdentifier) /* identifier */, eq(0) /* remaining */);
        verify(mCallback).onClientFinished(client, true);
    }

    @Test
    public void clientSendsErrorWhenHALFailsToRemoveEnrollment() throws RemoteException {
        final FaceRemovalClient client = createClient(1, new int[0]);
        client.start(mCallback);
        client.onRemoved(null, 0 /* remaining */);
        verify(mClientMonitorCallbackConverter).onError(eq(5) /* sensorId */, anyInt(),
                eq(BiometricConstants.BIOMETRIC_ERROR_UNABLE_TO_REMOVE), eq(0) /* vendorCode*/);
        verify(mCallback).onClientFinished(client, false);
    }

    private FaceRemovalClient createClient(int version, int[] biometricIds) throws RemoteException {
        when(mHal.getInterfaceVersion()).thenReturn(version);
        final AidlSession aidl = new AidlSession(version, mHal, USER_ID, mAidlResponseHandler);
        return new FaceRemovalClient(mContext, () -> aidl, mToken,
                mClientMonitorCallbackConverter, biometricIds, USER_ID,
                "own-it", mUtils /* utils */, 5 /* sensorId */, mBiometricLogger, mBiometricContext,
                mAuthenticatorIds);
    }
}
