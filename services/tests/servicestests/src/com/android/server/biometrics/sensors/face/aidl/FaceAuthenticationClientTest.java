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

import static android.adaptiveauth.Flags.FLAG_REPORT_BIOMETRIC_AUTH_ATTEMPTS;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_CANCELED;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_START;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_LOCKOUT;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.common.AuthenticateReason;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.WakeReason;
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticateOptions;
import android.os.IBinder;
import android.os.PowerManager;
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
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationStateListeners;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.UsageStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Presubmit
@SmallTest
public class FaceAuthenticationClientTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final int USER_ID = 12;
    private static final long OP_ID = 32;
    private static final int WAKE_REASON = WakeReason.LIFT;
    private static final int AUTH_REASON = AuthenticateReason.Face.ASSISTANT_VISIBLE;

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
    private ClientMonitorCallbackConverter mClientMonitorCallbackConverter;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private UsageStats mUsageStats;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    private AuthenticationStateListeners mAuthenticationStateListeners;
    @Mock
    private AidlResponseHandler mAidlResponseHandler;
    @Mock
    private ActivityTaskManager mActivityTaskManager;
    @Mock
    private ICancellationSignal mCancellationSignal;
    @Mock
    private AuthSessionCoordinator mAuthSessionCoordinator;
    @Mock
    private BiometricManager mBiometricManager;
    @Mock
    private LockoutTracker mLockoutTracker;
    @Captor
    private ArgumentCaptor<OperationContextExt> mOperationContextCaptor;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mContextInjector;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mStartHalConsumerCaptor;
    @Captor
    private ArgumentCaptor<FaceAuthenticateOptions> mFaceAuthenticateOptionsCaptor;
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

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setup() {
        mContext.addMockSystemService(BiometricManager.class, mBiometricManager);
        mContext.getOrCreateTestableResources().addOverride(R.string.face_error_hw_not_available,
                "hw not available");
        mContext.getOrCreateTestableResources().addOverride(R.string.face_acquired_too_dark,
                "too dark");
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
        when(mBiometricContext.getAuthSessionCoordinator()).thenReturn(mAuthSessionCoordinator);
    }

    @Test
    public void authNoContext_v1() throws RemoteException {
        final FaceAuthenticationClient client = createClient(1);
        client.start(mCallback);

        verify(mHal).authenticate(eq(OP_ID));
        verify(mHal, never()).authenticateWithContext(anyLong(), any());
    }

    @Test
    public void authWithContext_v2() throws RemoteException {
        final FaceAuthenticationClient client = createClient(2);
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(),
                mFaceAuthenticateOptionsCaptor.capture());

        mStartHalConsumerCaptor.getValue().accept(mOperationContextCaptor.getValue().toAidlContext(
                mFaceAuthenticateOptionsCaptor.getValue()));
        InOrder order = inOrder(mHal, mBiometricContext);
        order.verify(mBiometricContext).updateContext(
                mOperationContextCaptor.capture(), anyBoolean());

        final OperationContext aidlContext = mOperationContextCaptor.getValue().toAidlContext();

        order.verify(mHal).authenticateWithContext(eq(OP_ID), same(aidlContext));
        assertThat(aidlContext.wakeReason).isEqualTo(WAKE_REASON);
        assertThat(aidlContext.authenticateReason.getFaceAuthenticateReason())
                .isEqualTo(AUTH_REASON);
        verify(mHal, never()).authenticate(anyLong());
    }

    @Test
    public void testLockoutEndsOperation() throws RemoteException {
        final FaceAuthenticationClient client = createClient(2);
        client.start(mCallback);
        client.onLockoutPermanent();

        verify(mClientMonitorCallbackConverter).onError(anyInt(), anyInt(),
                eq(FACE_ERROR_LOCKOUT_PERMANENT), anyInt());
        verify(mCallback).onClientFinished(client, false);
    }

    @Test
    public void testTemporaryLockoutEndsOperation() throws RemoteException {
        final FaceAuthenticationClient client = createClient(2);
        client.start(mCallback);
        client.onLockoutTimed(1000);

        verify(mClientMonitorCallbackConverter).onError(anyInt(), anyInt(),
                eq(FACE_ERROR_LOCKOUT), anyInt());
        verify(mCallback).onClientFinished(client, false);
    }

    @Test
    public void subscribeContextAndStartHal() throws RemoteException {
        final FaceAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mBiometricContext).subscribe(mOperationContextCaptor.capture(),
                mStartHalConsumerCaptor.capture(), mContextInjector.capture(), any());

        mStartHalConsumerCaptor.getValue().accept(
                mOperationContextCaptor.getValue().toAidlContext());
        final ArgumentCaptor<OperationContext> captor =
                ArgumentCaptor.forClass(OperationContext.class);

        verify(mHal).authenticateWithContext(eq(OP_ID), captor.capture());

        OperationContext opContext = captor.getValue();

        assertThat(opContext).isSameInstanceAs(
                mOperationContextCaptor.getValue().toAidlContext());

        mContextInjector.getValue().accept(opContext);

        verify(mHal).onContextChanged(same(opContext));

        client.stopHalOperation();

        verify(mBiometricContext).unsubscribe(same(mOperationContextCaptor.getValue()));
    }

    @Test
    public void cancelsAuthWhenNotInForeground() throws Exception {
        final ActivityManager.RunningTaskInfo topTask = new ActivityManager.RunningTaskInfo();
        topTask.topActivity = new ComponentName("other", "thing");
        when(mActivityTaskManager.getTasks(anyInt())).thenReturn(List.of(topTask));
        when(mHal.authenticateWithContext(anyLong(), any())).thenReturn(mCancellationSignal);

        final FaceAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAuthenticated(new Face("friendly", 1 /* faceId */, 2 /* deviceId */),
                true /* authenticated */, new ArrayList<>());

        verify(mCancellationSignal, never()).cancel();
        verify(mClientMonitorCallbackConverter)
                .onError(anyInt(), anyInt(), eq(BIOMETRIC_ERROR_CANCELED), anyInt());
    }

    @Test
    public void testOnAuthenticatedFalseWhenListenerIsNull() throws RemoteException {
        final FaceAuthenticationClient client = createClientWithNullListener();
        client.start(mCallback);
        client.onAuthenticated(new Face("friendly", 1 /* faceId */, 2 /* deviceId */),
                false /* authenticated */, new ArrayList<>());

        verify(mCallback).onClientFinished(client, true);
    }

    @Test
    public void testOnAuthenticatedTrueWhenListenerIsNull() throws RemoteException {
        final FaceAuthenticationClient client = createClientWithNullListener();
        client.start(mCallback);
        client.onAuthenticated(new Face("friendly", 1 /* faceId */, 2 /* deviceId */),
                true /* authenticated */, new ArrayList<>());

        verify(mCallback).onClientFinished(client, true);
    }

    @Test
    public void authWithNoLockout() throws RemoteException {
        when(mLockoutTracker.getLockoutModeForUser(anyInt())).thenReturn(
                LockoutTracker.LOCKOUT_NONE);

        final FaceAuthenticationClient client = createClientWithLockoutTracker(mLockoutTracker);
        client.start(mCallback);

        verify(mHal).authenticate(OP_ID);
    }

    @Test
    public void authWithLockout() throws RemoteException {
        when(mLockoutTracker.getLockoutModeForUser(anyInt())).thenReturn(
                LockoutTracker.LOCKOUT_PERMANENT);

        final FaceAuthenticationClient client = createClientWithLockoutTracker(mLockoutTracker);
        client.start(mCallback);

        verify(mClientMonitorCallbackConverter).onError(anyInt(), anyInt(),
                eq(BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT), anyInt());
        verify(mHal, never()).authenticate(anyInt());
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationStartedAndStopped()
            throws RemoteException {
        final FaceAuthenticationClient client = createClient();
        client.start(mCallback);
        verify(mAuthenticationStateListeners).onAuthenticationStarted(
                mAuthenticationStartedCaptor.capture());

        assertThat(mAuthenticationStartedCaptor.getValue()).isEqualTo(
                new AuthenticationStartedInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP).build()
        );
        client.stopHalOperation();

        verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP).build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationAcquired_onAuthenticationHelp()
            throws RemoteException {
        final FaceAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAcquired(FACE_ACQUIRED_START, 0);

        InOrder inOrder = inOrder(mAuthenticationStateListeners);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationAcquired(
                mAuthenticationAcquiredCaptor.capture());
        assertThat(mAuthenticationAcquiredCaptor.getValue()).isEqualTo(
                new AuthenticationAcquiredInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP, FACE_ACQUIRED_START)
                        .build()
        );

        client.onAcquired(FACE_ACQUIRED_TOO_DARK, 0);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationAcquired(
                mAuthenticationAcquiredCaptor.capture());
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationHelp(
                mAuthenticationHelpCaptor.capture());

        assertThat(mAuthenticationAcquiredCaptor.getValue()).isEqualTo(
                new AuthenticationAcquiredInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP, FACE_ACQUIRED_TOO_DARK)
                        .build()
        );
        assertThat(mAuthenticationHelpCaptor.getValue()).isEqualTo(
                new AuthenticationHelpInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP,
                        mContext.getString(R.string.face_acquired_too_dark),
                        FACE_ACQUIRED_TOO_DARK)
                        .build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationError()
            throws RemoteException {
        final FaceAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0);

        InOrder inOrder = inOrder(mAuthenticationStateListeners);
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationError(
                mAuthenticationErrorCaptor.capture());
        inOrder.verify(mAuthenticationStateListeners).onAuthenticationStopped(
                mAuthenticationStoppedCaptor.capture());

        assertThat(mAuthenticationErrorCaptor.getValue()).isEqualTo(
                new AuthenticationErrorInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP,
                        mContext.getString(R.string.face_error_hw_not_available),
                        BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE).build()
        );
        assertThat(mAuthenticationStoppedCaptor.getValue()).isEqualTo(
                new AuthenticationStoppedInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP).build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationSucceeded()
            throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_REPORT_BIOMETRIC_AUTH_ATTEMPTS);
        final FaceAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAuthenticated(new Face("friendly", 1 /* faceId */, 2 /* deviceId */),
                true /* authenticated */, new ArrayList<>());

        verify(mAuthenticationStateListeners).onAuthenticationSucceeded(
                mAuthenticationSucceededCaptor.capture());

        assertThat(mAuthenticationSucceededCaptor.getValue()).isEqualTo(
                new AuthenticationSucceededInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP, true, USER_ID)
                        .build()
        );
    }

    @Test
    public void testAuthenticationStateListeners_onAuthenticationFailed() throws RemoteException {
        mSetFlagsRule.enableFlags(FLAG_REPORT_BIOMETRIC_AUTH_ATTEMPTS);
        final FaceAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAuthenticated(new Face("friendly", 1 /* faceId */, 2 /* deviceId */),
                false /* authenticated */, new ArrayList<>());

        verify(mAuthenticationStateListeners).onAuthenticationFailed(
                mAuthenticationFailedCaptor.capture());

        assertThat(mAuthenticationFailedCaptor.getValue()).isEqualTo(
                new AuthenticationFailedInfo.Builder(BiometricSourceType.FACE,
                        BiometricRequestConstants.REASON_AUTH_BP, USER_ID)
                        .build()
        );
    }

    @Test
    public void testResetLockoutOnAuthSuccess_nonBiometricPrompt() throws RemoteException {
        FaceAuthenticationClient client = createClient(false);
        client.start(mCallback);
        client.onAuthenticated(new Face("friendly", 1 /* faceId */,
                2 /* deviceId */), true /* authenticated */, createHardwareAuthToken());

        verify(mBiometricManager).resetLockoutTimeBound(eq(mToken), eq(mContext.getOpPackageName()),
                anyInt(), anyInt(), any());
    }

    @Test
    public void testNoResetLockoutOnAuthFailure_nonBiometricPrompt() throws RemoteException {
        FaceAuthenticationClient client = createClient(false);
        client.start(mCallback);
        client.onAuthenticated(new Face("friendly", 1 /* faceId */,
                2 /* deviceId */), false /* authenticated */, createHardwareAuthToken());

        verify(mBiometricManager, never()).resetLockoutTimeBound(eq(mToken),
                eq(mContext.getOpPackageName()), anyInt(), anyInt(), any());
    }

    @Test
    public void testNoResetLockoutOnAuthSuccess_BiometricPrompt() throws RemoteException {
        FaceAuthenticationClient client = createClient(true);
        client.start(mCallback);
        client.onAuthenticated(new Face("friendly", 1 /* faceId */,
                2 /* deviceId */), true /* authenticated */, createHardwareAuthToken());

        verify(mBiometricManager, never()).resetLockoutTimeBound(eq(mToken),
                eq(mContext.getOpPackageName()), anyInt(), anyInt(), any());
    }

    @Test
    public void testCancelAuth_whenClientWaitingForCookie() throws RemoteException {
        final FaceAuthenticationClient client = createClient(true);
        client.waitForCookie(mCallback);
        client.cancel();

        verify(mCallback).onClientFinished(client, false);
    }

    private FaceAuthenticationClient createClient() throws RemoteException {
        return createClient(2 /* version */, mClientMonitorCallbackConverter,
                false /* allowBackgroundAuthentication */, true /* isBiometricPrompt */,
                null /* lockoutTracker */);
    }

    private FaceAuthenticationClient createClient(boolean isBiometricPrompt)
            throws RemoteException {
        return createClient(2 /* version */, mClientMonitorCallbackConverter,
                true /* allowBackgroundAuthentication */, isBiometricPrompt,
                null /* lockoutTracker */);
    }

    private FaceAuthenticationClient createClientWithNullListener() throws RemoteException {
        return createClient(2 /* version */, null /* listener */,
                false /* allowBackgroundAuthentication */, true /* isBiometricPrompt */,
                null /* lockoutTracker */);
    }

    private FaceAuthenticationClient createClient(int version) throws RemoteException {
        return createClient(version, mClientMonitorCallbackConverter,
                false /* allowBackgroundAuthentication */, true /* isBiometricPrompt */,
                null /* lockoutTracker */);
    }

    private FaceAuthenticationClient createClientWithLockoutTracker(LockoutTracker lockoutTracker)
            throws RemoteException {
        return createClient(0 /* version */,
                mClientMonitorCallbackConverter,
                true /* allowBackgroundAuthentication */,
                true /* isBiometricPrompt */,
                lockoutTracker);
    }

    private FaceAuthenticationClient createClient(int version,
            ClientMonitorCallbackConverter listener,
            boolean allowBackgroundAuthentication,
            boolean isBiometricPrompt,
            LockoutTracker lockoutTracker) throws RemoteException {
        when(mHal.getInterfaceVersion()).thenReturn(version);

        final AidlSession aidl = new AidlSession(version, mHal, USER_ID, mAidlResponseHandler);
        final FaceAuthenticateOptions options = new FaceAuthenticateOptions.Builder()
                .setOpPackageName("test-owner")
                .setUserId(USER_ID)
                .setSensorId(9)
                .setWakeReason(PowerManager.WAKE_REASON_LIFT)
                .setAuthenticateReason(
                        FaceAuthenticateOptions.AUTHENTICATE_REASON_ASSISTANT_VISIBLE)
                .build();
        return new FaceAuthenticationClient(mContext, () -> aidl, mToken,
                2 /* requestId */, listener, OP_ID,
                false /* restricted */, options, isBiometricPrompt ? 4 : 0 /* cookie */,
                false /* requireConfirmation */,
                mBiometricLogger, mBiometricContext, true /* isStrongBiometric */,
                mUsageStats, lockoutTracker, allowBackgroundAuthentication,
                null /* sensorPrivacyManager */, 0 /* biometricStrength */,
                mAuthenticationStateListeners) {
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
