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

import static android.adaptiveauth.Flags.FLAG_REPORT_BIOMETRIC_AUTH_ATTEMPTS;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_CANCELED;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_TOO_FAST;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationState;
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
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
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutTracker;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Presubmit
@SmallTest
public class FingerprintAuthenticationClientTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final int SENSOR_ID = 4;
    private static final int USER_ID = 8;
    private static final long OP_ID = 7;
    private static final long REQUEST_ID = 88;
    private static final int POINTER_ID = 3;
    private static final int TOUCH_X = 8;
    private static final int TOUCH_Y = 20;
    private static final float TOUCH_MAJOR = 4.4f;
    private static final float TOUCH_MINOR = 5.5f;
    private static final int FINGER_UP = 111;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

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
    private BiometricManager mBiometricManager;
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
    private ActivityTaskManager mActivityTaskManager;
    @Mock
    private ICancellationSignal mCancellationSignal;
    @Mock
    private Probe mLuxProbe;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private Clock mClock;
    @Mock
    private LockoutTracker mLockoutTracker;
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
    private ArgumentCaptor<AuthenticationFailedInfo> mAuthenticationFailedCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationHelpInfo> mAuthenticationHelpCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationStartedInfo> mAuthenticationStartedCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationStoppedInfo> mAuthenticationStoppedCaptor;
    @Captor
    private ArgumentCaptor<AuthenticationSucceededInfo> mAuthenticationSucceededCaptor;

    private final TestLooper mLooper = new TestLooper();

    @Before
    public void setup() {
        mContext.addMockSystemService(BiometricManager.class, mBiometricManager);
        mContext.getOrCreateTestableResources().addOverride(
                R.string.fingerprint_error_hw_not_available, "hw not available");
        mContext.getOrCreateTestableResources().addOverride(
                R.string.fingerprint_error_lockout_permanent, "lockout permanent");
        mContext.getOrCreateTestableResources().addOverride(
                R.string.fingerprint_acquired_too_fast, "too fast");
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
        when(mBiometricLogger.getAmbientLightProbe(anyBoolean())).thenAnswer(i ->
                new CallbackWithProbe<>(mLuxProbe, i.getArgument(0)));
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
    }

    @Test
    public void authNoContext_v1() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1);
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(), any());

        mStartHalConsumerCaptor.getValue().accept(mOperationContextCaptor
                .getValue().toAidlContext());

        verify(mHal).authenticate(eq(OP_ID));
        verify(mHal, never()).authenticateWithContext(anyLong(), any());
    }

    @Test
    public void pointerUp_v1() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1);
        client.start(mCallback);

        PointerContext pc = new PointerContext();
        pc.pointerId = POINTER_ID;
        client.onPointerUp(pc);

        verify(mHal).onPointerUp(eq(POINTER_ID));
        verify(mHal, never()).onPointerUpWithContext(any());
    }

    @Test
    public void pointerDown_v1() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1);
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
        final FingerprintAuthenticationClient client = createClient(2);
        client.start(mCallback);

        PointerContext pc = new PointerContext();
        pc.pointerId = POINTER_ID;
        client.onPointerUp(pc);

        verify(mHal).onPointerUpWithContext(eq(pc));
        verify(mHal, never()).onPointerUp(eq(POINTER_ID));
    }

    @Test
    public void pointerDownWithContext_v2() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(2);
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
    public void luxProbeWhenAwake() throws RemoteException {
        when(mBiometricContext.isAwake()).thenReturn(false);
        when(mBiometricContext.isAod()).thenReturn(false);

        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(), any());

        mStartHalConsumerCaptor.getValue().accept(mOperationContextCaptor
                .getValue().toAidlContext());

        final ArgumentCaptor<OperationContext> captor =
                ArgumentCaptor.forClass(OperationContext.class);
        verify(mHal).authenticateWithContext(eq(OP_ID), captor.capture());
        OperationContext opContext = captor.getValue();

        assertThat(mOperationContextCaptor.getValue().toAidlContext())
                .isSameInstanceAs(opContext);

        mContextInjector.getValue().accept(opContext);
        verify(mLuxProbe, never()).enable();

        reset(mLuxProbe);
        when(mBiometricContext.isAwake()).thenReturn(true);

        mContextInjector.getValue().accept(opContext);
        verify(mLuxProbe).enable();
        verify(mLuxProbe, never()).disable();

        when(mBiometricContext.isAwake()).thenReturn(false);

        mContextInjector.getValue().accept(opContext);
        verify(mLuxProbe).disable();
    }

    @Test
    public void luxProbeEnabledOnStartWhenWake() throws RemoteException {
        luxProbeEnabledOnStart(true /* isAwake */);
    }

    @Test
    public void luxProbeNotEnabledOnStartWhenNotWake() throws RemoteException {
        luxProbeEnabledOnStart(false /* isAwake */);
    }

    private void luxProbeEnabledOnStart(boolean isAwake) throws RemoteException {
        when(mBiometricContext.isAwake()).thenReturn(isAwake);
        when(mBiometricContext.isAod()).thenReturn(false);
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(), any());

        mStartHalConsumerCaptor.getValue().accept(mOperationContextCaptor
                .getValue().toAidlContext());

        verify(mLuxProbe, isAwake ? times(1) : never()).enable();
    }

    @Test
    public void luxProbeDisabledOnAod() throws RemoteException {
        when(mBiometricContext.isAwake()).thenReturn(false);
        when(mBiometricContext.isAod()).thenReturn(true);
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(), any());

        mStartHalConsumerCaptor.getValue().accept(mOperationContextCaptor
                .getValue().toAidlContext());

        final ArgumentCaptor<OperationContext> captor =
                ArgumentCaptor.forClass(OperationContext.class);
        verify(mHal).authenticateWithContext(eq(OP_ID), captor.capture());
        OperationContext opContext = captor.getValue();

        assertThat(opContext).isSameInstanceAs(
                mOperationContextCaptor.getValue().toAidlContext());

        mContextInjector.getValue().accept(opContext);
        verify(mLuxProbe, never()).enable();
    }

    @Test
    public void luxProbeWhenFingerDown_unlessDestroyed() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("name", 2 /* enrollmentId */, SENSOR_ID),
                true /* authenticated */, new ArrayList<>());

        mLooper.moveTimeForward(10);
        mLooper.dispatchAll();
        verify(mLuxProbe).destroy();

        client.onAcquired(2, 0);
        verify(mLuxProbe, never()).enable();
    }

    @Test
    public void subscribeContextAndStartHal() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(), any());

        final OperationContextExt operationContext = mOperationContextCaptor.getValue();
        mStartHalConsumerCaptor.getValue().accept(operationContext.toAidlContext());
        final ArgumentCaptor<OperationContext> captor =
                ArgumentCaptor.forClass(OperationContext.class);

        verify(mHal).authenticateWithContext(eq(OP_ID), captor.capture());

        OperationContext opContext = captor.getValue();

        assertThat(opContext).isSameInstanceAs(operationContext.toAidlContext());

        opContext.operationState = new OperationState();
        opContext.operationState.setFingerprintOperationState(
                new OperationState.FingerprintOperationState());
        mContextInjector.getValue().accept(opContext);

        verify(mHal).onContextChanged(same(opContext));
        verify(mHal, times(2)).setIgnoreDisplayTouches(
                opContext.operationState.getFingerprintOperationState().isHardwareIgnoringTouches);

        client.stopHalOperation();

        verify(mBiometricContext).unsubscribe(same(operationContext));
    }

    @Test
    public void showHideOverlay_cancel() throws RemoteException {
        showHideOverlay(AuthenticationClient::cancel);
    }

    @Test
    public void showHideOverlay_stop() throws RemoteException {
        showHideOverlay(FingerprintAuthenticationClient::stopHalOperation);
    }

    @Test
    public void showHideOverlay_error() throws RemoteException {
        showHideOverlay(c -> c.onError(0, 0));
        verify(mCallback).onClientFinished(any(), eq(false));
    }

    @Test
    public void showHideOverlay_lockout() throws RemoteException {
        showHideOverlay(c -> c.onLockoutTimed(5000));
    }

    @Test
    public void showHideOverlay_lockoutPerm()
            throws RemoteException {
        showHideOverlay(FingerprintAuthenticationClient::onLockoutPermanent);
    }

    private void showHideOverlay(
            Consumer<FingerprintAuthenticationClient> block) throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();

        client.start(mCallback);

        verify(mUdfpsOverlayController).showUdfpsOverlay(eq(REQUEST_ID), anyInt(), anyInt(), any());
        verify(mAuthenticationStateListeners).onAuthenticationStarted(
                mAuthenticationStartedCaptor.capture());

        assertThat(mAuthenticationStartedCaptor.getValue()).isEqualTo(
                new AuthenticationStartedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP).build()
        );

        block.accept(client);

        verify(mUdfpsOverlayController).hideUdfpsOverlay(anyInt());
        verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP).build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationAcquired_onAuthenticationHelp()
            throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAcquired(FINGERPRINT_ACQUIRED_START, 0);

        InOrder inOrder = inOrder(mAuthenticationStateListeners);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationAcquired(
                mAuthenticationAcquiredCaptor.capture());

        assertThat(mAuthenticationAcquiredCaptor.getValue()).isEqualTo(
                new AuthenticationAcquiredInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP, FINGERPRINT_ACQUIRED_START)
                        .build()
        );

        client.onAcquired(FINGERPRINT_ACQUIRED_TOO_FAST, 0);

        inOrder.verify(mAuthenticationStateListeners).onAuthenticationAcquired(
                mAuthenticationAcquiredCaptor.capture());
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationHelp(
                mAuthenticationHelpCaptor.capture());

        assertThat(mAuthenticationAcquiredCaptor.getValue()).isEqualTo(
                new AuthenticationAcquiredInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP, FINGERPRINT_ACQUIRED_TOO_FAST)
                        .build()
        );
        assertThat(mAuthenticationHelpCaptor.getValue()).isEqualTo(
                new AuthenticationHelpInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP,
                        mContext.getString(R.string.fingerprint_acquired_too_fast),
                        FINGERPRINT_ACQUIRED_TOO_FAST)
                        .build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onError()
            throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0);

        InOrder inOrder = inOrder(mAuthenticationStateListeners);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationError(
                mAuthenticationErrorCaptor.capture());
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationErrorCaptor.getValue()).isEqualTo(
                new AuthenticationErrorInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP,
                        mContext.getString(R.string.fingerprint_error_hw_not_available),
                        FINGERPRINT_ERROR_HW_UNAVAILABLE).build()
        );
        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP).build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onLockoutPermanent()
            throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onLockoutPermanent();

        InOrder inOrder = inOrder(mAuthenticationStateListeners);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationError(
                mAuthenticationErrorCaptor.capture());
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationErrorCaptor.getValue()).isEqualTo(
                new AuthenticationErrorInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP,
                        mContext.getString(R.string.fingerprint_error_lockout_permanent),
                        FINGERPRINT_ERROR_LOCKOUT_PERMANENT).build()
        );
        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP).build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationSucceeded()
            throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_REPORT_BIOMETRIC_AUTH_ATTEMPTS);
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                2 /* deviceId */), true /* authenticated */, new ArrayList<>());

        verify(mAuthenticationStateListeners).onAuthenticationSucceeded(
                mAuthenticationSucceededCaptor.capture());
        assertThat(mAuthenticationSucceededCaptor.getValue()).isEqualTo(
                new AuthenticationSucceededInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP, true, USER_ID)
                        .build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationFailed() throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_REPORT_BIOMETRIC_AUTH_ATTEMPTS);
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                2 /* deviceId */), false /* authenticated */, new ArrayList<>());

        verify(mAuthenticationStateListeners).onAuthenticationFailed(
                mAuthenticationFailedCaptor.capture());
        assertThat(mAuthenticationFailedCaptor.getValue()).isEqualTo(
                new AuthenticationFailedInfo.Builder(BiometricSourceType.FINGERPRINT,
                        BiometricRequestConstants.REASON_AUTH_BP, USER_ID).build()
        );
    }

    @Test
    public void cancelsAuthWhenNotInForeground() throws Exception {
        final ActivityManager.RunningTaskInfo topTask = new ActivityManager.RunningTaskInfo();
        topTask.topActivity = new ComponentName("other", "thing");
        when(mActivityTaskManager.getTasks(anyInt())).thenReturn(List.of(topTask));

        final FingerprintAuthenticationClient client = createClientWithoutBackgroundAuth();
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */, 2 /* deviceId */),
                true /* authenticated */, new ArrayList<>());

        mLooper.moveTimeForward(10);
        mLooper.dispatchAll();
        verify(mCancellationSignal, never()).cancel();
        verify(mClientMonitorCallbackConverter)
                .onError(anyInt(), anyInt(), eq(BIOMETRIC_ERROR_CANCELED), anyInt());
    }

    @Test
    public void testResetLockoutOnAuthSuccess_nonBiometricPrompt() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1 /* version */,
                true /* allowBackgroundAuthentication */, false /* isBiometricPrompt */,
                mClientMonitorCallbackConverter, mLockoutTracker);
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                2 /* deviceId */), true /* authenticated */, createHardwareAuthToken());

        verify(mBiometricManager).resetLockoutTimeBound(eq(mToken), eq(mContext.getOpPackageName()),
                anyInt(), anyInt(), any());
    }

    @Test
    public void testNoResetLockoutOnAuthFailure_nonBiometricPrompt() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1 /* version */,
                true /* allowBackgroundAuthentication */, false /* isBiometricPrompt */,
                mClientMonitorCallbackConverter, mLockoutTracker);
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                2 /* deviceId */), false /* authenticated */, createHardwareAuthToken());

        verify(mBiometricManager, never()).resetLockoutTimeBound(eq(mToken),
                eq(mContext.getOpPackageName()), anyInt(), anyInt(), any());
    }

    @Test
    public void testNoResetLockoutOnAuthSuccess_BiometricPrompt() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1 /* version */,
                true /* allowBackgroundAuthentication */, true /* isBiometricPrompt */,
                mClientMonitorCallbackConverter, mLockoutTracker);
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                2 /* deviceId */), true /* authenticated */, createHardwareAuthToken());

        verify(mBiometricManager, never()).resetLockoutTimeBound(eq(mToken),
                eq(mContext.getOpPackageName()), anyInt(), anyInt(), any());
    }

    @Test
    public void testOnAuthenticatedFalseWhenListenerIsNull() throws RemoteException {
        final FingerprintAuthenticationClient client = createClientWithNullListener();
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                        2 /* deviceId */), false /* authenticated */, new ArrayList<>());

        verify(mCallback, never()).onClientFinished(eq(client), anyBoolean());
    }

    @Test
    public void testOnAuthenticatedTrueWhenListenerIsNull() throws RemoteException {
        final FingerprintAuthenticationClient client = createClientWithNullListener();
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                2 /* deviceId */), true /* authenticated */, new ArrayList<>());

        verify(mCallback).onClientFinished(client, true);
    }

    @Test
    public void testLockoutTracker_authSuccess() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1 /* version */,
                true /* allowBackgroundAuthentication */, false /* isBiometricPrompt */,
                mClientMonitorCallbackConverter, mLockoutTracker);
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                2 /* deviceId */), true /* authenticated */, createHardwareAuthToken());

        verify(mLockoutTracker).resetFailedAttemptsForUser(true, USER_ID);
        verify(mLockoutTracker, never()).addFailedAttemptForUser(anyInt());
    }

    @Test
    public void testLockoutTracker_authFailed() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1 /* version */,
                true /* allowBackgroundAuthentication */, false /* isBiometricPrompt */,
                mClientMonitorCallbackConverter, mLockoutTracker);
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */,
                2 /* deviceId */), false /* authenticated */, createHardwareAuthToken());

        verify(mLockoutTracker, never()).resetFailedAttemptsForUser(anyBoolean(), anyInt());
        verify(mLockoutTracker).addFailedAttemptForUser(USER_ID);
    }

    private FingerprintAuthenticationClient createClient() throws RemoteException {
        return createClient(100 /* version */, true /* allowBackgroundAuthentication */,
                true /* isBiometricPrompt */,
                mClientMonitorCallbackConverter, null);
    }

    private FingerprintAuthenticationClient createClientWithoutBackgroundAuth()
            throws RemoteException {
        return createClient(100 /* version */, false /* allowBackgroundAuthentication */,
                true /* isBiometricPrompt */, mClientMonitorCallbackConverter, null);
    }

    private FingerprintAuthenticationClient createClient(int version) throws RemoteException {
        return createClient(version, true /* allowBackgroundAuthentication */,
                true /* isBiometricPrompt */,
                mClientMonitorCallbackConverter, null);
    }

    private FingerprintAuthenticationClient createClientWithNullListener() throws RemoteException {
        return createClient(100 /* version */, true /* allowBackgroundAuthentication */,
                true /* isBiometricPrompt */,
                /* listener */null, null);
    }

    private FingerprintAuthenticationClient createClient(int version,
            boolean allowBackgroundAuthentication, boolean isBiometricPrompt,
            ClientMonitorCallbackConverter listener,
            LockoutTracker lockoutTracker)
            throws RemoteException {
        when(mHal.getInterfaceVersion()).thenReturn(version);

        final AidlSession aidl = new AidlSession(version, mHal, USER_ID, mAidlResponseHandler);
        final FingerprintAuthenticateOptions options = new FingerprintAuthenticateOptions.Builder()
                .setOpPackageName("test-owner")
                .setUserId(USER_ID)
                .setSensorId(SENSOR_ID)
                .build();
        return new FingerprintAuthenticationClient(mContext, () -> aidl, mToken, REQUEST_ID,
                listener, OP_ID, false /* restricted */, options,
                isBiometricPrompt ? 4 : 0 /* cookie */,
                false /* requireConfirmation */, mBiometricLogger, mBiometricContext,
                true /* isStrongBiometric */, null /* taskStackListener */, mUdfpsOverlayController,
                mAuthenticationStateListeners, allowBackgroundAuthentication, mSensorProps,
                0 /* biometricStrength */, lockoutTracker) {
            @Override
            protected ActivityTaskManager getActivityTaskManager() {
                return mActivityTaskManager;
            }
        };
    }

    private ArrayList<Byte> createHardwareAuthToken() {
        return new ArrayList<>(Collections.nCopies(69, Byte.valueOf("0")));
    }
}
