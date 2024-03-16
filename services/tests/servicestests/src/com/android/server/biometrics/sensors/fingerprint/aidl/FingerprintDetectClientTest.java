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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.function.Consumer;

@Presubmit
@SmallTest
public class FingerprintDetectClientTest {

    private static final int USER_ID = 8;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock
    private AuthenticationStateListeners mAuthenticationStateListeners;
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
    private IUdfpsOverlayController mUdfpsOverlayController;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    private AidlResponseHandler mAidlResponseHandler;
    @Captor
    private ArgumentCaptor<OperationContextExt> mOperationContextCaptor;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mContextInjector;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mStartHalConsumerCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationStartedInfo> mAuthenticationStartedCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationStoppedInfo> mAuthenticationStoppedCaptor;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setup() {
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
    }

    @Test
    public void detectNoContext_v1() throws RemoteException {
        final FingerprintDetectClient client = createClient(1);

        client.start(mCallback);

        verify(mHal).detectInteraction();
        verify(mHal, never()).detectInteractionWithContext(any());
    }

    @Test
    public void subscribeContextAndStartHal() throws RemoteException {
        final FingerprintDetectClient client = createClient();
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(), any());

        mStartHalConsumerCaptor.getValue().accept(
                mOperationContextCaptor.getValue().toAidlContext());
        final ArgumentCaptor<OperationContext> captor =
                ArgumentCaptor.forClass(OperationContext.class);
        verify(mHal).detectInteractionWithContext(captor.capture());
        OperationContext opContext = captor.getValue();

        assertThat(opContext).isSameInstanceAs(
                mOperationContextCaptor.getValue().toAidlContext());
        mContextInjector.getValue().accept(opContext);
        verify(mHal).onContextChanged(same(opContext));

        client.stopHalOperation();
        verify(mBiometricContext).unsubscribe(same(mOperationContextCaptor.getValue()));
    }

    @Test
    public void testWhenListenerIsNull() {
        final AidlSession aidl = new AidlSession(0, mHal, USER_ID, mAidlResponseHandler);
        final FingerprintDetectClient client =  new FingerprintDetectClient(mContext, () -> aidl,
                mToken, 6 /* requestId */, null /* listener */,
                new FingerprintAuthenticateOptions.Builder()
                        .setUserId(2)
                        .setSensorId(1)
                        .setOpPackageName("a-test")
                        .build(),
                mBiometricLogger, mBiometricContext, mAuthenticationStateListeners,
                mUdfpsOverlayController, true /* isStrongBiometric */);
        client.start(mCallback);
        client.onInteractionDetected();

        verify(mCallback).onClientFinished(eq(client), anyBoolean());
    }

    private FingerprintDetectClient createClient() throws RemoteException {
        return createClient(200 /* version */);
    }

    private FingerprintDetectClient createClient(int version) throws RemoteException {
        when(mHal.getInterfaceVersion()).thenReturn(version);

        final AidlSession aidl = new AidlSession(version, mHal, USER_ID, mAidlResponseHandler);
        return new FingerprintDetectClient(mContext, () -> aidl, mToken,
                6 /* requestId */, mClientMonitorCallbackConverter,
                new FingerprintAuthenticateOptions.Builder()
                        .setUserId(2)
                        .setSensorId(1)
                        .setOpPackageName("a-test")
                        .build(),
                mBiometricLogger, mBiometricContext, mAuthenticationStateListeners,
                mUdfpsOverlayController, true /* isStrongBiometric */);
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationStartedAndStopped()
            throws RemoteException {
        final FingerprintDetectClient client = createClient();
        client.start(mCallback);
        verify(mAuthenticationStateListeners).onAuthenticationStarted(
                mAuthenticationStartedCaptor.capture());

        assertThat(mAuthenticationStartedCaptor.getValue()).isEqualTo(
                new AuthenticationStartedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_KEYGUARD).build()
        );

        client.stopHalOperation();
        verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_KEYGUARD).build()
        );
    }
}
