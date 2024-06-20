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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.common.AuthenticateReason;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.WakeReason;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.FaceAuthenticateOptions;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Vibrator;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.function.Consumer;

@Presubmit
@SmallTest
public class FaceDetectClientTest {

    private static final int USER_ID = 12;
    private static final int WAKE_REASON = WakeReason.POWER_BUTTON;
    private static final int AUTH_REASON = AuthenticateReason.Face.OCCLUDING_APP_REQUESTED;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock
    private ISession mHal;
    @Mock
    private IBinder mToken;
    @Mock
    private Vibrator mVibrator;
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
    private AuthenticationStateListeners mAuthenticationStateListeners;
    @Captor
    private ArgumentCaptor<OperationContextExt> mOperationContextCaptor;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mStartHalCaptor;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mContextInjector;
    @Captor
    private ArgumentCaptor<AuthenticationErrorInfo> mAuthenticationErrorCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationStartedInfo> mAuthenticationStartedCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationStoppedInfo> mAuthenticationStoppedCaptor;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setup() {
        mContext.addMockSystemService(Vibrator.class, mVibrator);
        mContext.getOrCreateTestableResources().addOverride(R.string.face_error_hw_not_available,
                "hw not available");
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
    }

    @Test
    public void detectNoContext_v1() throws RemoteException {
        final FaceDetectClient client = createClient(1);
        client.start(mCallback);

        verify(mHal).detectInteraction();
        verify(mHal, never()).detectInteractionWithContext(any());
    }

    @Test
    public void subscribeContextAndStartHal() throws RemoteException {
        final FaceDetectClient client = createClient();
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalCaptor.capture(), mContextInjector.capture(), any());

        mStartHalCaptor.getValue().accept(mOperationContextCaptor.getValue().toAidlContext());
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
    public void doesNotPlayHapticOnInteractionDetected() throws Exception {
        final FaceDetectClient client = createClient();
        client.start(mCallback);
        client.onInteractionDetected();
        client.stopHalOperation();

        verifyZeroInteractions(mVibrator);
    }

    private FaceDetectClient createClient() throws RemoteException {
        return createClient(100 /* version */);
    }

    private FaceDetectClient createClient(int version) throws RemoteException {
        when(mHal.getInterfaceVersion()).thenReturn(version);

        final AidlSession aidl = new AidlSession(version, mHal, USER_ID, mAidlResponseHandler);
        return new FaceDetectClient(mContext, () -> aidl, mToken,
                99 /* requestId */, mClientMonitorCallbackConverter,
                new FaceAuthenticateOptions.Builder()
                        .setUserId(USER_ID)
                        .setSensorId(5)
                        .setOpPackageName("own-it")
                        .setWakeReason(PowerManager.WAKE_REASON_POWER_BUTTON)
                        .setAuthenticateReason(
                                FaceAuthenticateOptions.AUTHENTICATE_REASON_OCCLUDING_APP_REQUESTED)
                        .build(),
                mBiometricLogger, mBiometricContext, mAuthenticationStateListeners,
                false /* isStrongBiometric */, null /* sensorPrivacyManager */);
    }

    @Test
    public void testAuthenticationStateListeners_onDetectionStartedAndStopped()
            throws RemoteException {
        final FaceDetectClient client = createClient();
        client.start(mCallback);
        verify(mAuthenticationStateListeners).onAuthenticationStarted(
                mAuthenticationStartedCaptor.capture());

        assertThat(mAuthenticationStartedCaptor.getValue()).isEqualTo(
                new AuthenticationStartedInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_KEYGUARD).build()
        );
        client.stopHalOperation();

        verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_KEYGUARD).build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onDetectionError()
            throws RemoteException {
        final FaceDetectClient client = createClient();
        client.start(mCallback);
        client.onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0);

        InOrder inOrder = inOrder(mAuthenticationStateListeners);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationError(
                mAuthenticationErrorCaptor.capture());
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationErrorCaptor.getValue()).isEqualTo(
                new AuthenticationErrorInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_KEYGUARD,
                        mContext.getString(R.string.face_error_hw_not_available),
                        BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE).build()
        );
        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_KEYGUARD).build()
        );
    }
}
