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

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_TOO_FAST;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_TIMEOUT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintEnrollOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.CallbackWithProbe;
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.log.Probe;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.BiometricUtils;
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
public class FingerprintEnrollClientTest {

    private static final int ENROLL_SOURCE = FingerprintEnrollOptions.ENROLL_REASON_SUW;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final byte[] HAT = new byte[69];
    private static final int USER_ID = 8;
    private static final long REQUEST_ID = 9;
    private static final int POINTER_ID = 3;
    private static final int TOUCH_X = 8;
    private static final int TOUCH_Y = 20;
    private static final float TOUCH_MAJOR = 4.4f;
    private static final float TOUCH_MINOR = 5.5f;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);

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
    private BiometricUtils<Fingerprint> mBiometricUtils;
    @Mock
    private IUdfpsOverlayController mUdfpsOverlayController;
    @Mock
    private AuthenticationStateListeners mAuthenticationStateListeners;
    @Mock
    private FingerprintSensorPropertiesInternal mSensorProps;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    private AidlResponseHandler mAidlResponseHandler;
    @Mock
    private Probe mLuxProbe;
    @Captor
    private ArgumentCaptor<OperationContextExt> mOperationContextCaptor;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mContextInjector;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mStartHalConsumerCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationAcquiredInfo> mAuthenticationAcquiredCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationErrorInfo> mAuthenticationErrorCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationHelpInfo> mAuthenticationHelpCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationStartedInfo> mAuthenticationStartedCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationStoppedInfo> mAuthenticationStoppedCaptor;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setup() {
        when(mBiometricLogger.getAmbientLightProbe(anyBoolean())).thenAnswer(i ->
                new CallbackWithProbe<>(mLuxProbe, i.getArgument(0)));
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
        mContext.getOrCreateTestableResources().addOverride(
                R.string.fingerprint_acquired_too_fast, "too fast");
        mContext.getOrCreateTestableResources().addOverride(
                R.string.fingerprint_error_timeout, "timeout");
    }

    @Test
    public void enrollNoContext_v1() throws RemoteException {
        final FingerprintEnrollClient client = createClient(1);

        client.start(mCallback);

        verify(mHal).enroll(any());
        verify(mHal, never()).enrollWithContext(any(), any());
    }

    @Test
    public void pointerUp_v1() throws RemoteException {
        final FingerprintEnrollClient client = createClient(1);
        client.start(mCallback);

        PointerContext pc = new PointerContext();
        pc.pointerId = POINTER_ID;
        client.onPointerUp(pc);

        verify(mHal).onPointerUp(eq(POINTER_ID));
        verify(mHal, never()).onPointerUpWithContext(any());
    }

    @Test
    public void pointerDown_v1() throws RemoteException {
        final FingerprintEnrollClient client = createClient(1);
        client.start(mCallback);

        PointerContext pc = new PointerContext();
        pc.pointerId = POINTER_ID;
        pc.x = TOUCH_X;
        pc.y = TOUCH_Y;
        pc.minor = TOUCH_MINOR;
        pc.major = TOUCH_MAJOR;
        client.onPointerDown(pc);

        verify(mHal).onPointerDown(eq(POINTER_ID), eq(TOUCH_X), eq(TOUCH_Y), eq(TOUCH_MINOR),
                eq(TOUCH_MAJOR));
        verify(mHal, never()).onPointerDownWithContext(any());
    }

    @Test
    public void pointerUpWithContext_v2() throws RemoteException {
        final FingerprintEnrollClient client = createClient(2);
        client.start(mCallback);

        PointerContext pc = new PointerContext();
        pc.pointerId = POINTER_ID;
        client.onPointerUp(pc);

        verify(mHal).onPointerUpWithContext(eq(pc));
        verify(mHal, never()).onPointerUp(anyInt());
    }

    @Test
    public void pointerDownWithContext_v2() throws RemoteException {
        final FingerprintEnrollClient client = createClient(2);
        client.start(mCallback);

        PointerContext pc = new PointerContext();
        pc.pointerId = POINTER_ID;
        pc.x = TOUCH_X;
        pc.y = TOUCH_Y;
        pc.minor = TOUCH_MINOR;
        pc.major = TOUCH_MAJOR;
        client.onPointerDown(pc);

        verify(mHal).onPointerDownWithContext(eq(pc));
        verify(mHal, never()).onPointerDown(anyInt(), anyInt(), anyInt(), anyFloat(), anyFloat());
    }

    @Test
    public void luxProbeWhenStarted() throws RemoteException {
        final FingerprintEnrollClient client = createClient();
        client.start(mCallback);

        verify(mLuxProbe).enable();

        client.onAcquired(2, 0);

        PointerContext pc = new PointerContext();
        client.onPointerUp(pc);
        client.onPointerDown(pc);
        verify(mLuxProbe, never()).disable();
        verify(mLuxProbe, never()).destroy();

        client.onEnrollResult(new Fingerprint("f", 30 /* fingerId */, 14 /* deviceId */),
                0 /* remaining */);

        verify(mLuxProbe).destroy();
    }

    @Test
    public void subscribeContextAndStartHal() throws RemoteException {
        final FingerprintEnrollClient client = createClient();
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(), any());

        mStartHalConsumerCaptor.getValue().accept(
                mOperationContextCaptor.getValue().toAidlContext());
        final ArgumentCaptor<OperationContext> captor =
                ArgumentCaptor.forClass(OperationContext.class);
        verify(mHal).enrollWithContext(any(), captor.capture());
        OperationContext opContext = captor.getValue();

        mContextInjector.getValue().accept(
                mOperationContextCaptor.getValue().toAidlContext());
        verify(mHal).onContextChanged(same(opContext));

        client.stopHalOperation();
        verify(mBiometricContext).unsubscribe(same(mOperationContextCaptor.getValue()));
    }

    @Test
    public void showHideOverlay_cancel() throws RemoteException {
        showHideOverlay(AcquisitionClient::cancel);
    }

    @Test
    public void showHideOverlay_stop() throws RemoteException {
        showHideOverlay(FingerprintEnrollClient::stopHalOperation);
    }

    @Test
    public void showHideOverlay_error() throws RemoteException {
        showHideOverlay(c -> c.onError(0, 0));
        verify(mCallback).onClientFinished(any(), eq(false));
    }

    @Test
    public void showHideOverlay_result() throws RemoteException {
        showHideOverlay(
                c -> c.onEnrollResult(new Fingerprint("", 1, 1), 0));
    }

    @Test
    public void testEnrollWithReasonLogsMetric() throws RemoteException {
        final FingerprintEnrollClient client = createClient(4);
        client.start(mCallback);
        client.onEnrollResult(new Fingerprint("fingerprint", 1 /* faceId */, 20 /* deviceId */), 0);

        verify(mBiometricLogger).logOnEnrolled(anyInt(), anyLong(), anyBoolean(),
                eq(BiometricsProtoEnums.ENROLLMENT_SOURCE_SUW));
    }

    private void showHideOverlay(
            Consumer<FingerprintEnrollClient> block) throws RemoteException {
        final FingerprintEnrollClient client = createClient();

        client.start(mCallback);

        verify(mUdfpsOverlayController).showUdfpsOverlay(eq(REQUEST_ID), anyInt(), anyInt(), any());
        verify(mAuthenticationStateListeners).onAuthenticationStarted(
                mAuthenticationStartedCaptor.capture());

        assertThat(mAuthenticationStartedCaptor.getValue()).isEqualTo(
                new AuthenticationStartedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_ENROLL_ENROLLING).build()
        );

        block.accept(client);

        verify(mUdfpsOverlayController).hideUdfpsOverlay(anyInt());
        verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_ENROLL_ENROLLING).build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationAcquired_onAuthenticationHelp()
            throws RemoteException {
        final FingerprintEnrollClient client = createClient();
        client.start(mCallback);
        client.onAcquired(FINGERPRINT_ACQUIRED_START, 0);
        client.onAcquired(FINGERPRINT_ACQUIRED_TOO_FAST, 0);

        InOrder inOrder = inOrder(mAuthenticationStateListeners);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationAcquired(
                mAuthenticationAcquiredCaptor.capture());
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationHelp(
                mAuthenticationHelpCaptor.capture());

        assertThat(mAuthenticationAcquiredCaptor.getValue()).isEqualTo(
                new AuthenticationAcquiredInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_ENROLL_ENROLLING,
                        FINGERPRINT_ACQUIRED_TOO_FAST).build()
        );
        assertThat(mAuthenticationHelpCaptor.getValue()).isEqualTo(
                new AuthenticationHelpInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_ENROLL_ENROLLING,
                        mContext.getString(R.string.fingerprint_acquired_too_fast),
                        FINGERPRINT_ACQUIRED_TOO_FAST)
                        .build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onError()
            throws RemoteException {
        final FingerprintEnrollClient client = createClient();
        client.start(mCallback);
        client.onError(FINGERPRINT_ERROR_TIMEOUT, 0);

        InOrder inOrder = inOrder(mAuthenticationStateListeners);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationError(
                mAuthenticationErrorCaptor.capture());
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationErrorCaptor.getValue()).isEqualTo(
                new AuthenticationErrorInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_ENROLL_ENROLLING,
                        mContext.getString(R.string.fingerprint_error_timeout),
                        FINGERPRINT_ERROR_TIMEOUT).build()
        );
        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_ENROLL_ENROLLING).build()
        );
    }

    private FingerprintEnrollClient createClient() throws RemoteException {
        return createClient(500);
    }

    private FingerprintEnrollClient createClient(int version) throws RemoteException {
        when(mHal.getInterfaceVersion()).thenReturn(version);

        final AidlSession aidl = new AidlSession(version, mHal, USER_ID, mAidlResponseHandler);
        return new FingerprintEnrollClient(mContext, () -> aidl, mToken, REQUEST_ID,
        mClientMonitorCallbackConverter, 0 /* userId */,
        HAT, "owner", mBiometricUtils, 8 /* sensorId */,
        mBiometricLogger, mBiometricContext, mSensorProps, mUdfpsOverlayController,
                mAuthenticationStateListeners, 6 /* maxTemplatesPerUser */,
        FingerprintManager.ENROLL_ENROLL, (new FingerprintEnrollOptions.Builder())
                .setEnrollReason(ENROLL_SOURCE).build()
        );
    }
}
