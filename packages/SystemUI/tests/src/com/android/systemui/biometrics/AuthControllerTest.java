/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricManager.Authenticators;
import static android.hardware.biometrics.BiometricManager.BIOMETRIC_MULTI_SENSOR_FINGERPRINT_AND_FACE;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.inject.Provider;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthControllerTest extends SysuiTestCase {

    private static final long REQUEST_ID = 22;

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private IBiometricSysuiReceiver mReceiver;
    @Mock
    private IBiometricContextListener mContextListener;
    @Mock
    private AuthDialog mDialog1;
    @Mock
    private AuthDialog mDialog2;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private ActivityTaskManager mActivityTaskManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private FaceManager mFaceManager;
    @Mock
    private UdfpsController mUdfpsController;
    @Mock
    private SideFpsController mSideFpsController;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    private UserManager mUserManager;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private InteractionJankMonitor mInteractionJankMonitor;
    @Captor
    private ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback> mAuthenticatorsRegisteredCaptor;
    @Captor
    private ArgumentCaptor<BiometricStateListener> mBiometricStateCaptor;
    @Captor
    private ArgumentCaptor<StatusBarStateController.StateListener> mStatusBarStateListenerCaptor;
    @Captor
    private ArgumentCaptor<WakefulnessLifecycle.Observer> mWakefullnessObserverCaptor;

    private TestableContext mContextSpy;
    private Execution mExecution;
    private TestableLooper mTestableLooper;
    private Handler mHandler;
    private DelayableExecutor mBackgroundExecutor;
    private TestableAuthController mAuthController;

    @Mock
    private VibratorHelper mVibratorHelper;

    @Before
    public void setup() throws RemoteException {
        mContextSpy = spy(mContext);
        mExecution = new FakeExecution();
        mTestableLooper = TestableLooper.get(this);
        mHandler = new Handler(mTestableLooper.getLooper());
        mBackgroundExecutor = new FakeExecutor(new FakeSystemClock());

        when(mContextSpy.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(true);

        when(mDialog1.getOpPackageName()).thenReturn("Dialog1");
        when(mDialog2.getOpPackageName()).thenReturn("Dialog2");

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);
        when(mDialog2.isAllowDeviceCredentials()).thenReturn(false);

        when(mDialog1.getRequestId()).thenReturn(REQUEST_ID);
        when(mDialog2.getRequestId()).thenReturn(REQUEST_ID);

        when(mDisplayManager.getStableDisplaySize()).thenReturn(new Point());

        when(mFingerprintManager.isHardwareDetected()).thenReturn(true);

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        FingerprintSensorPropertiesInternal prop = new FingerprintSensorPropertiesInternal(
                1 /* sensorId */,
                SensorProperties.STRENGTH_STRONG,
                1 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequireHardwareAuthToken */);
        List<FingerprintSensorPropertiesInternal> props = new ArrayList<>();
        props.add(prop);
        when(mFingerprintManager.getSensorPropertiesInternal()).thenReturn(props);

        mAuthController = new TestableAuthController(mContextSpy, mExecution, mCommandQueue,
                mActivityTaskManager, mWindowManager, mFingerprintManager, mFaceManager,
                () -> mUdfpsController, () -> mSideFpsController, mStatusBarStateController,
                mVibratorHelper);

        mAuthController.start();
        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(
                mAuthenticatorsRegisteredCaptor.capture());

        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(WAKEFULNESS_AWAKE);
        verify(mStatusBarStateController).addCallback(mStatusBarStateListenerCaptor.capture());
        verify(mWakefulnessLifecycle).addObserver(mWakefullnessObserverCaptor.capture());

        mAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(props);

        // Ensures that the operations posted on the handler get executed.
        mTestableLooper.processAllMessages();
    }

    // Callback tests

    @Test
    public void testRegistersBiometricStateListener_afterAllAuthenticatorsAreRegistered()
            throws RemoteException {
        // This test is sensitive to prior FingerprintManager interactions.
        reset(mFingerprintManager);

        when(mVibratorHelper.hasVibrator()).thenReturn(true);

        // This test requires an uninitialized AuthController.
        AuthController authController = new TestableAuthController(mContextSpy, mExecution,
                mCommandQueue, mActivityTaskManager, mWindowManager, mFingerprintManager,
                mFaceManager, () -> mUdfpsController, () -> mSideFpsController,
                mStatusBarStateController, mVibratorHelper);
        authController.start();

        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(
                mAuthenticatorsRegisteredCaptor.capture());
        mTestableLooper.processAllMessages();

        verify(mFingerprintManager, never()).registerBiometricStateListener(any());

        mAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(new ArrayList<>());
        mTestableLooper.processAllMessages();

        verify(mFingerprintManager).registerBiometricStateListener(any());
    }

    @Test
    public void testDoesNotCrash_afterEnrollmentsChangedForUnknownSensor() throws RemoteException {
        // This test is sensitive to prior FingerprintManager interactions.
        reset(mFingerprintManager);

        when(mVibratorHelper.hasVibrator()).thenReturn(true);

        // This test requires an uninitialized AuthController.
        AuthController authController = new TestableAuthController(mContextSpy, mExecution,
                mCommandQueue, mActivityTaskManager, mWindowManager, mFingerprintManager,
                mFaceManager, () -> mUdfpsController, () -> mSideFpsController,
                mStatusBarStateController, mVibratorHelper);
        authController.start();

        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(
                mAuthenticatorsRegisteredCaptor.capture());

        // Emulates a device with no authenticators (empty list).
        mAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(new ArrayList<>());
        mTestableLooper.processAllMessages();

        verify(mFingerprintManager).registerBiometricStateListener(
                mBiometricStateCaptor.capture());

        // Enrollments changed for an unknown sensor.
        mBiometricStateCaptor.getValue().onEnrollmentsChanged(0 /* userId */,
                0xbeef /* sensorId */, true /* hasEnrollments */);
        mTestableLooper.processAllMessages();

        // Nothing should crash.
    }

    @Test
    public void testSendsReasonUserCanceled_whenDismissedByUserCancel() throws Exception {
        showDialog(new int[]{1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED,
                null, /* credentialAttestation */
                mAuthController.mCurrentDialog.getRequestId());
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_USER_CANCEL),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonNegative_whenDismissedByButtonNegative() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE,
                null, /* credentialAttestation */
                mAuthController.mCurrentDialog.getRequestId());
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_NEGATIVE),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonConfirmed_whenDismissedByButtonPositive() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BUTTON_POSITIVE,
                null, /* credentialAttestation */
                mAuthController.mCurrentDialog.getRequestId());
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonConfirmNotRequired_whenDismissedByAuthenticated() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED,
                null, /* credentialAttestation */
                mAuthController.mCurrentDialog.getRequestId());
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonError_whenDismissedByError() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_ERROR,
                null, /* credentialAttestation */
                mAuthController.mCurrentDialog.getRequestId());
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_ERROR),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonServerRequested_whenDismissedByServer() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BY_SYSTEM_SERVER,
                null, /* credentialAttestation */
                mAuthController.mCurrentDialog.getRequestId());
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonCredentialConfirmed_whenDeviceCredentialAuthenticated()
            throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        final byte[] credentialAttestation = generateRandomHAT();

        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_CREDENTIAL_AUTHENTICATED,
                credentialAttestation, mAuthController.mCurrentDialog.getRequestId());
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED),
                AdditionalMatchers.aryEq(credentialAttestation));
    }

    // Statusbar tests

    @Test
    public void testShowInvoked_whenSystemRequested() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        verify(mDialog1).show(any(), any());
    }

    @Test
    public void testOnAuthenticationSucceededInvoked_whenSystemRequested() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onBiometricAuthenticated(TYPE_FINGERPRINT);
        verify(mDialog1).onAuthenticationSucceeded(eq(TYPE_FINGERPRINT));
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenBiometricRejected() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_NONE;
        mAuthController.onBiometricError(modality,
                BiometricConstants.BIOMETRIC_PAUSED_REJECTED,
                0 /* vendorCode */);

        ArgumentCaptor<Integer> modalityCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onAuthenticationFailed(modalityCaptor.capture(), messageCaptor.capture());

        assertEquals(modalityCaptor.getValue().intValue(), modality);
        assertEquals(messageCaptor.getValue(),
                mContext.getString(R.string.biometric_not_recognized));
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenBiometricTimedOut() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_FACE;
        final int error = BiometricConstants.BIOMETRIC_ERROR_TIMEOUT;
        final int vendorCode = 0;
        mAuthController.onBiometricError(modality, error, vendorCode);

        ArgumentCaptor<Integer> modalityCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onAuthenticationFailed(modalityCaptor.capture(), messageCaptor.capture());

        assertEquals(modalityCaptor.getValue().intValue(), modality);
        assertEquals(messageCaptor.getValue(),
                FaceManager.getErrorString(mContext, error, vendorCode));
    }

    @Test
    public void testOnHelpInvoked_whenSystemRequested() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_IRIS;
        final String helpMessage = "help";
        mAuthController.onBiometricHelp(modality, helpMessage);

        ArgumentCaptor<Integer> modalityCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onHelp(modalityCaptor.capture(), messageCaptor.capture());

        assertEquals(modalityCaptor.getValue().intValue(), modality);
        assertEquals(messageCaptor.getValue(), helpMessage);
    }

    @Test
    public void testOnErrorInvoked_whenSystemRequested() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_FACE;
        final int error = 1;
        final int vendorCode = 0;
        mAuthController.onBiometricError(modality, error, vendorCode);

        ArgumentCaptor<Integer> modalityCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onError(modalityCaptor.capture(), messageCaptor.capture());

        assertEquals(modalityCaptor.getValue().intValue(), modality);
        assertEquals(messageCaptor.getValue(),
                FaceManager.getErrorString(mContext, error, vendorCode));
    }

    @Test
    public void testErrorLockout_whenCredentialAllowed_AnimatesToCredentialUI() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(true);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1, never()).onError(anyInt(), anyString());
        verify(mDialog1).animateToCredentialUI();
    }

    @Test
    public void testErrorLockoutPermanent_whenCredentialAllowed_AnimatesToCredentialUI() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(true);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1, never()).onError(anyInt(), anyString());
        verify(mDialog1).animateToCredentialUI();
    }

    @Test
    public void testErrorLockout_whenCredentialNotAllowed_sendsOnError() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_FACE;
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);

        mAuthController.onBiometricError(modality, error, vendorCode);
        verify(mDialog1).onError(
                eq(modality), eq(FaceManager.getErrorString(mContext, error, vendorCode)));
        verify(mDialog1, never()).animateToCredentialUI();
    }

    @Test
    public void testErrorLockoutPermanent_whenCredentialNotAllowed_sendsOnError() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_FACE;
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);

        mAuthController.onBiometricError(modality, error, vendorCode);
        verify(mDialog1).onError(
                eq(modality), eq(FaceManager.getErrorString(mContext, error, vendorCode)));
        verify(mDialog1, never()).animateToCredentialUI();
    }

    @Test
    public void testHideAuthenticationDialog_invokesDismissFromSystemServer() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        mAuthController.hideAuthenticationDialog(REQUEST_ID + 1);
        verify(mDialog1, never()).dismissFromSystemServer();
        assertThat(mAuthController.mCurrentDialog).isSameInstanceAs(mDialog1);

        mAuthController.hideAuthenticationDialog(REQUEST_ID);
        verify(mDialog1).dismissFromSystemServer();

        // In this case, BiometricService sends the error to the client immediately, without
        // doing a round trip to SystemUI.
        assertNull(mAuthController.mCurrentDialog);
    }

    // Corner case tests

    @Test
    public void testCancelAuthentication_whenCredentialConfirmed_doesntCrash() throws Exception {
        // It's possible that before the client is notified that credential is confirmed, the client
        // requests to cancel authentication.
        //
        // Test that the following sequence of events does not crash SystemUI:
        // 1) Credential is confirmed
        // 2) Client cancels authentication

        showDialog(new int[0] /* sensorIds */, true /* credentialAllowed */);
        verify(mDialog1).show(any(), any());

        final byte[] credentialAttestation = generateRandomHAT();

        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_CREDENTIAL_AUTHENTICATED,
                credentialAttestation, mAuthController.mCurrentDialog.getRequestId());
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED),
                AdditionalMatchers.aryEq(credentialAttestation));

        mAuthController.hideAuthenticationDialog(REQUEST_ID);
    }

    @Test
    public void testShowNewDialog_beforeOldDialogDismissed_SkipsAnimations() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        verify(mDialog1).show(any(), any());

        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        // First dialog should be dismissed without animation
        verify(mDialog1).dismissWithoutCallback(eq(false) /* animate */);

        // Second dialog should be shown without animation
        verify(mDialog2).show(any(), any());
    }

    @Test
    public void testConfigurationPersists_whenOnConfigurationChanged() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        verify(mDialog1).show(any(), any());

        // Return that the UI is in "showing" state
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            Bundle savedState = (Bundle) args[0];
            savedState.putBoolean(AuthDialog.KEY_CONTAINER_GOING_AWAY, false);
            return null; // onSaveState returns void
        }).when(mDialog1).onSaveState(any());

        mAuthController.onConfigurationChanged(new Configuration());

        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
        verify(mDialog1).onSaveState(captor.capture());

        // Old dialog doesn't animate
        verify(mDialog1).dismissWithoutCallback(eq(false /* animate */));

        // Saved state is restored into new dialog
        ArgumentCaptor<Bundle> captor2 = ArgumentCaptor.forClass(Bundle.class);
        verify(mDialog2).show(any(), captor2.capture());

        // TODO: This should check all values we want to save/restore
        assertEquals(captor.getValue(), captor2.getValue());
    }

    @Test
    public void testConfigurationPersists_whenBiometricFallbackToCredential() {
        showDialog(new int[] {1} /* sensorIds */, true /* credentialAllowed */);
        verify(mDialog1).show(any(), any());

        // Pretend that the UI is now showing device credential UI.
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            Bundle savedState = (Bundle) args[0];
            savedState.putBoolean(AuthDialog.KEY_CONTAINER_GOING_AWAY, false);
            savedState.putBoolean(AuthDialog.KEY_CREDENTIAL_SHOWING, true);
            return null; // onSaveState returns void
        }).when(mDialog1).onSaveState(any());

        mAuthController.onConfigurationChanged(new Configuration());

        // Check that the new dialog was initialized to the credential UI.
        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
        verify(mDialog2).show(any(), captor.capture());
        assertEquals(Authenticators.DEVICE_CREDENTIAL,
                mAuthController.mLastBiometricPromptInfo.getAuthenticators());
    }

    @Test
    public void testClientNotified_whenTaskStackChangesDuringShow() throws Exception {
        switchTask("other_package");
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        mTestableLooper.processAllMessages();

        assertNull(mAuthController.mCurrentDialog);
        assertNull(mAuthController.mReceiver);
        verify(mDialog1).dismissWithoutCallback(true /* animate */);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_USER_CANCEL),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testClientNotified_whenTaskStackChangesDuringAuthentication() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        switchTask("other_package");

        mAuthController.mTaskStackListener.onTaskStackChanged();
        mTestableLooper.processAllMessages();

        assertNull(mAuthController.mCurrentDialog);
        assertNull(mAuthController.mReceiver);
        verify(mDialog1).dismissWithoutCallback(true /* animate */);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_USER_CANCEL),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testDoesNotCrash_whenTryAgainPressedAfterDismissal() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final long requestID = mAuthController.mCurrentDialog.getRequestId();
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED,
                null, /* credentialAttestation */requestID);
        mAuthController.onTryAgainPressed(requestID);
    }

    @Test
    public void testDoesNotCrash_whenDeviceCredentialPressedAfterDismissal() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final long requestID = mAuthController.mCurrentDialog.getRequestId();
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED,
                null /* credentialAttestation */, requestID);
        mAuthController.onDeviceCredentialPressed(requestID);
    }

    @Test
    public void testActionCloseSystemDialogs_dismissesDialogIfShowing() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mAuthController.mBroadcastReceiver.onReceive(mContext, intent);
        mTestableLooper.processAllMessages();

        assertNull(mAuthController.mCurrentDialog);
        assertNull(mAuthController.mReceiver);
        verify(mDialog1).dismissWithoutCallback(true /* animate */);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_USER_CANCEL),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testOnAodInterrupt() {
        final int pos = 10;
        final float majorMinor = 5f;
        mAuthController.onAodInterrupt(pos, pos, majorMinor, majorMinor);
        verify(mUdfpsController).onAodInterrupt(eq(pos), eq(pos), eq(majorMinor), eq(majorMinor));
    }

    @Test
    public void testSubscribesToOrientationChangesOnStart() {
        verify(mDisplayManager).registerDisplayListener(any(), eq(mHandler), anyLong());
    }

    @Test
    public void testOnBiometricPromptShownCallback() {
        // GIVEN a callback is registered
        AuthController.Callback callback = mock(AuthController.Callback.class);
        mAuthController.addCallback(callback);

        // WHEN dialog is shown
        showDialog(new int[]{1} /* sensorIds */, false /* credentialAllowed */);

        // THEN callback should be received
        verify(callback).onBiometricPromptShown();
    }

    @Test
    public void testOnBiometricPromptDismissedCallback() {
        // GIVEN a callback is registered
        AuthController.Callback callback = mock(AuthController.Callback.class);
        mAuthController.addCallback(callback);

        // WHEN dialog is shown and then dismissed
        showDialog(new int[]{1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED,
                null /* credentialAttestation */,
                mAuthController.mCurrentDialog.getRequestId());

        // THEN callback should be received
        verify(callback).onBiometricPromptDismissed();
    }

    @Test
    public void testForwardsDozeEvents() throws RemoteException {
        when(mStatusBarStateController.isDozing()).thenReturn(true);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(WAKEFULNESS_AWAKE);
        mAuthController.setBiometicContextListener(mContextListener);

        mStatusBarStateListenerCaptor.getValue().onDozingChanged(true);
        mStatusBarStateListenerCaptor.getValue().onDozingChanged(false);

        InOrder order = inOrder(mContextListener);
        order.verify(mContextListener, times(2)).onDozeChanged(eq(true), eq(true));
        order.verify(mContextListener).onDozeChanged(eq(false), eq(true));
        order.verifyNoMoreInteractions();
    }

    @Test
    public void testForwardsWakeEvents() throws RemoteException {
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(WAKEFULNESS_AWAKE);
        mAuthController.setBiometicContextListener(mContextListener);

        mWakefullnessObserverCaptor.getValue().onStartedGoingToSleep();
        mWakefullnessObserverCaptor.getValue().onFinishedGoingToSleep();
        mWakefullnessObserverCaptor.getValue().onStartedWakingUp();
        mWakefullnessObserverCaptor.getValue().onFinishedWakingUp();
        mWakefullnessObserverCaptor.getValue().onPostFinishedWakingUp();

        InOrder order = inOrder(mContextListener);
        order.verify(mContextListener).onDozeChanged(eq(false), eq(true));
        order.verify(mContextListener).onDozeChanged(eq(false), eq(false));
        order.verify(mContextListener).onDozeChanged(eq(false), eq(true));
        order.verifyNoMoreInteractions();
    }

    @Test
    public void testGetFingerprintSensorLocationChanges_differentRotations() {
        // GIVEN fp default location and mocked device dimensions
        // Rotation 0, where "o" is the location of the FP sensor, if x or y = 0, it's the edge of
        // the screen which is why a 1x1 width and height is represented by a 2x2 grid below:
        //   [* o]
        //   [* *]
        Point fpDefaultLocation = new Point(1, 0);
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = 1;
        displayInfo.logicalHeight = 1;

        // WHEN the rotation is 0, THEN no rotation applied
        displayInfo.rotation = Surface.ROTATION_0;
        assertEquals(
                fpDefaultLocation,
                mAuthController.rotateToCurrentOrientation(
                        new Point(fpDefaultLocation), displayInfo)
        );

        // WHEN the rotation is 270, THEN rotation is applied
        //   [* *]
        //   [* o]
        displayInfo.rotation = Surface.ROTATION_270;
        assertEquals(
                new Point(1, 1),
                mAuthController.rotateToCurrentOrientation(
                        new Point(fpDefaultLocation), displayInfo)
        );

        // WHEN the rotation is 180, THEN rotation is applied
        //   [* *]
        //   [o *]
        displayInfo.rotation = Surface.ROTATION_180;
        assertEquals(
                new Point(0, 1),
                mAuthController.rotateToCurrentOrientation(
                        new Point(fpDefaultLocation), displayInfo)
        );

        // WHEN the rotation is 90, THEN rotation is applied
        //   [o *]
        //   [* *]
        displayInfo.rotation = Surface.ROTATION_90;
        assertEquals(
                new Point(0, 0),
                mAuthController.rotateToCurrentOrientation(
                        new Point(fpDefaultLocation), displayInfo)
        );
    }

    @Test
    public void testGetFingerprintSensorLocationChanges_rotateRectangle() {
        // GIVEN fp default location and mocked device dimensions
        // Rotation 0, where "o" is the location of the FP sensor, if x or y = 0, it's the edge of
        // the screen.
        //   [* * o *]
        //   [* * * *]
        Point fpDefaultLocation = new Point(2, 0);
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = 3;
        displayInfo.logicalHeight = 1;

        // WHEN the rotation is 0, THEN no rotation applied
        displayInfo.rotation = Surface.ROTATION_0;
        assertEquals(
                fpDefaultLocation,
                mAuthController.rotateToCurrentOrientation(
                        new Point(fpDefaultLocation), displayInfo)
        );

        // WHEN the rotation is 180, THEN rotation is applied
        //   [* * * *]
        //   [* o * *]
        displayInfo.rotation = Surface.ROTATION_180;
        assertEquals(
                new Point(1, 1),
                mAuthController.rotateToCurrentOrientation(
                        new Point(fpDefaultLocation), displayInfo)
        );

        // Rotation 270 & 90 have swapped logical width and heights
        displayInfo.logicalWidth = 1;
        displayInfo.logicalHeight = 3;

        // WHEN the rotation is 270, THEN rotation is applied
        //   [* *]
        //   [* *]
        //   [* o]
        //   [* *]
        displayInfo.rotation = Surface.ROTATION_270;
        assertEquals(
                new Point(1, 2),
                mAuthController.rotateToCurrentOrientation(
                        new Point(fpDefaultLocation), displayInfo)
        );

        // WHEN the rotation is 90, THEN rotation is applied
        //   [* *]
        //   [o *]
        //   [* *]
        //   [* *]
        displayInfo.rotation = Surface.ROTATION_90;
        assertEquals(
                new Point(0, 1),
                mAuthController.rotateToCurrentOrientation(
                        new Point(fpDefaultLocation), displayInfo)
        );
    }

    private void showDialog(int[] sensorIds, boolean credentialAllowed) {
        mAuthController.showAuthenticationDialog(createTestPromptInfo(),
                mReceiver /* receiver */,
                sensorIds,
                credentialAllowed,
                true /* requireConfirmation */,
                0 /* userId */,
                0 /* operationId */,
                "testPackage",
                REQUEST_ID,
                BIOMETRIC_MULTI_SENSOR_FINGERPRINT_AND_FACE);
    }

    private void switchTask(String packageName) {
        final List<ActivityManager.RunningTaskInfo> tasks = new ArrayList<>();
        final ActivityManager.RunningTaskInfo taskInfo =
                mock(ActivityManager.RunningTaskInfo.class);
        taskInfo.topActivity = mock(ComponentName.class);
        when(taskInfo.topActivity.getPackageName()).thenReturn(packageName);
        tasks.add(taskInfo);
        when(mActivityTaskManager.getTasks(anyInt())).thenReturn(tasks);
    }

    private PromptInfo createTestPromptInfo() {
        PromptInfo promptInfo = new PromptInfo();

        promptInfo.setTitle("Title");
        promptInfo.setSubtitle("Subtitle");
        promptInfo.setDescription("Description");
        promptInfo.setNegativeButtonText("Negative Button");

        // RequireConfirmation is a hint to BiometricService. This can be forced to be required
        // by user settings, and should be tested in BiometricService.
        promptInfo.setConfirmationRequested(true);

        return promptInfo;
    }

    private byte[] generateRandomHAT() {
        byte[] HAT = new byte[69];
        Random random = new Random();
        random.nextBytes(HAT);
        return HAT;
    }

    private final class TestableAuthController extends AuthController {
        private int mBuildCount = 0;
        private PromptInfo mLastBiometricPromptInfo;

        TestableAuthController(Context context,
                Execution execution,
                CommandQueue commandQueue,
                ActivityTaskManager activityTaskManager,
                WindowManager windowManager,
                FingerprintManager fingerprintManager,
                FaceManager faceManager,
                Provider<UdfpsController> udfpsControllerFactory,
                Provider<SideFpsController> sidefpsControllerFactory,
                StatusBarStateController statusBarStateController,
                VibratorHelper vibratorHelper) {
            super(context, execution, commandQueue, activityTaskManager, windowManager,
                    fingerprintManager, faceManager, udfpsControllerFactory,
                    sidefpsControllerFactory, mDisplayManager, mWakefulnessLifecycle,
                    mUserManager, mLockPatternUtils, statusBarStateController,
                    mInteractionJankMonitor, mHandler, mBackgroundExecutor, vibratorHelper);
        }

        @Override
        protected AuthDialog buildDialog(DelayableExecutor bgExecutor, PromptInfo promptInfo,
                boolean requireConfirmation, int userId, int[] sensorIds,
                String opPackageName, boolean skipIntro, long operationId, long requestId,
                @BiometricManager.BiometricMultiSensorMode int multiSensorConfig,
                WakefulnessLifecycle wakefulnessLifecycle, UserManager userManager,
                LockPatternUtils lockPatternUtils) {

            mLastBiometricPromptInfo = promptInfo;

            AuthDialog dialog;
            if (mBuildCount == 0) {
                dialog = mDialog1;
            } else if (mBuildCount == 1) {
                dialog = mDialog2;
            } else {
                dialog = null;
            }
            mBuildCount++;
            return dialog;
        }
    }
}

