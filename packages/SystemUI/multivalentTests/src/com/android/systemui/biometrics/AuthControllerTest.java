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

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotSame;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Point;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorProperties;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserManager;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.domain.interactor.LogContextInteractor;
import com.android.systemui.biometrics.domain.interactor.PromptCredentialInteractor;
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor;
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel;
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel;
import com.android.systemui.keyguard.WakefulnessLifecycle;
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
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
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
    private AuthDialogPanelInteractionDetector mPanelInteractionDetector;
    @Mock
    private UserManager mUserManager;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private LogContextInteractor mLogContextInteractor;
    @Mock
    private UdfpsLogger mUdfpsLogger;
    @Mock
    private InteractionJankMonitor mInteractionJankMonitor;
    @Mock
    private PromptCredentialInteractor mBiometricPromptCredentialInteractor;
    @Mock
    private PromptSelectorInteractor mPromptSelectionInteractor;
    @Mock
    private CredentialViewModel mCredentialViewModel;
    @Mock
    private PromptViewModel mPromptViewModel;
    @Mock
    private UdfpsUtils mUdfpsUtils;

    @Captor
    private ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback> mFpAuthenticatorsRegisteredCaptor;
    @Captor
    private ArgumentCaptor<IFaceAuthenticatorsRegisteredCallback> mFaceAuthenticatorsRegisteredCaptor;
    @Captor
    private ArgumentCaptor<BiometricStateListener> mBiometricStateCaptor;
    @Captor
    private ArgumentCaptor<Integer> mModalityCaptor;
    @Captor
    private ArgumentCaptor<String> mMessageCaptor;
    @Mock
    private Resources mResources;
    @Mock
    private VibratorHelper mVibratorHelper;

    private TestableContext mContextSpy;
    private Execution mExecution;
    private TestableLooper mTestableLooper;
    private Handler mHandler;
    private DelayableExecutor mBackgroundExecutor;
    private TestableAuthController mAuthController;

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
        when(mFaceManager.isHardwareDetected()).thenReturn(true);

        final List<ComponentInfoInternal> fpComponentInfo = List.of(
                new ComponentInfoInternal("faceSensor" /* componentId */,
                        "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                        "00000001" /* serialNumber */, "" /* softwareVersion */));
        final List<ComponentInfoInternal> faceComponentInfo = List.of(
                new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                        "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                        "vendor/version/revision" /* softwareVersion */));

        final List<FingerprintSensorPropertiesInternal> fpProps = List.of(
                new FingerprintSensorPropertiesInternal(
                        1 /* sensorId */,
                        SensorProperties.STRENGTH_STRONG,
                        1 /* maxEnrollmentsPerUser */,
                        fpComponentInfo,
                        FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                        true /* resetLockoutRequireHardwareAuthToken */));
        when(mFingerprintManager.getSensorPropertiesInternal()).thenReturn(fpProps);

        final List<FaceSensorPropertiesInternal> faceProps = List.of(
                new FaceSensorPropertiesInternal(
                        2 /* sensorId */,
                        SensorProperties.STRENGTH_STRONG,
                        1 /* maxEnrollmentsPerUser */,
                        faceComponentInfo,
                        FaceSensorProperties.TYPE_RGB,
                        true /* supportsFaceDetection */,
                        true /* supportsSelfIllumination */,
                        true /* resetLockoutRequireHardwareAuthToken */));
        when(mFaceManager.getSensorPropertiesInternal()).thenReturn(faceProps);

        mAuthController = new TestableAuthController(mContextSpy);

        mAuthController.start();
        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(
                mFpAuthenticatorsRegisteredCaptor.capture());
        verify(mFaceManager).addAuthenticatorsRegisteredCallback(
                mFaceAuthenticatorsRegisteredCaptor.capture());

        mFpAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(fpProps);
        mFaceAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(faceProps);

        // Ensures that the operations posted on the handler get executed.
        waitForIdleSync();
    }

    // Callback tests

    @Test
    public void testRegistersBiometricStateListener_afterAllAuthenticatorsAreRegistered()
            throws RemoteException {
        // This test is sensitive to prior FingerprintManager interactions.
        reset(mFingerprintManager);
        reset(mFaceManager);

        // This test requires an uninitialized AuthController.
        AuthController authController = new TestableAuthController(mContextSpy);
        authController.start();

        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(
                mFpAuthenticatorsRegisteredCaptor.capture());
        verify(mFaceManager).addAuthenticatorsRegisteredCallback(
                mFaceAuthenticatorsRegisteredCaptor.capture());
        waitForIdleSync();

        verify(mFingerprintManager, never()).registerBiometricStateListener(any());
        verify(mFaceManager, never()).registerBiometricStateListener(any());

        mFpAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(List.of());
        mFaceAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(List.of());
        waitForIdleSync();

        verify(mFingerprintManager).registerBiometricStateListener(any());
        verify(mFaceManager).registerBiometricStateListener(any());
    }

    @Test
    public void testDoesNotCrash_afterEnrollmentsChangedForUnknownSensor() throws RemoteException {
        // This test is sensitive to prior FingerprintManager interactions.
        reset(mFingerprintManager);
        reset(mFaceManager);

        // This test requires an uninitialized AuthController.
        AuthController authController = new TestableAuthController(mContextSpy);
        authController.start();

        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(
                mFpAuthenticatorsRegisteredCaptor.capture());
        verify(mFaceManager).addAuthenticatorsRegisteredCallback(
                mFaceAuthenticatorsRegisteredCaptor.capture());

        // Emulates a device with no authenticators (empty list).
        mFpAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(List.of());
        mFaceAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(List.of());
        waitForIdleSync();

        verify(mFingerprintManager).registerBiometricStateListener(
                mBiometricStateCaptor.capture());
        verify(mFaceManager).registerBiometricStateListener(
                mBiometricStateCaptor.capture());

        // Enrollments changed for an unknown sensor.
        for (BiometricStateListener listener : mBiometricStateCaptor.getAllValues()) {
            listener.onEnrollmentsChanged(0 /* userId */,
                    0xbeef /* sensorId */, true /* hasEnrollments */);
        }
        waitForIdleSync();

        // Nothing should crash.
    }

    @Test
    public void testFaceAuthEnrollmentStatus() throws RemoteException {
        final int userId = 0;

        reset(mFaceManager);
        mAuthController.start();

        verify(mFaceManager).addAuthenticatorsRegisteredCallback(
                mFaceAuthenticatorsRegisteredCaptor.capture());

        mFaceAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(
                mFaceManager.getSensorPropertiesInternal());
        mTestableLooper.processAllMessages();

        verify(mFaceManager).registerBiometricStateListener(
                mBiometricStateCaptor.capture());

        assertFalse(mAuthController.isFaceAuthEnrolled(userId));

        // Enrollments changed for an unknown sensor.
        for (BiometricStateListener listener : mBiometricStateCaptor.getAllValues()) {
            listener.onEnrollmentsChanged(userId,
                    2 /* sensorId */, true /* hasEnrollments */);
        }
        mTestableLooper.processAllMessages();

        assertTrue(mAuthController.isFaceAuthEnrolled(userId));
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
        verify(mDialog1).show(any());
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

        verify(mDialog1).onAuthenticationFailed(mModalityCaptor.capture(), mMessageCaptor.capture());

        assertEquals(mModalityCaptor.getValue().intValue(), modality);
        assertEquals(mMessageCaptor.getValue(),
                mContext.getString(R.string.biometric_not_recognized));
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenBiometricReEnrollRequired() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_FACE;
        mAuthController.onBiometricError(modality,
                BiometricConstants.BIOMETRIC_ERROR_RE_ENROLL,
                0 /* vendorCode */);

        verify(mDialog1).onAuthenticationFailed(mModalityCaptor.capture(),
                mMessageCaptor.capture());

        assertThat(mModalityCaptor.getValue()).isEqualTo(modality);
        assertThat(mMessageCaptor.getValue()).isEqualTo(mContext.getString(
                R.string.face_recalibrate_notification_content));
    }

    @Test
    public void testOnAuthenticationFailedInvoked_coex_whenFaceAuthRejected_withPaused() {
        testOnAuthenticationFailedInvoked_coex_whenFaceAuthRejected(
                BiometricConstants.BIOMETRIC_PAUSED_REJECTED);
    }

    @Test
    public void testOnAuthenticationFailedInvoked_coex_whenFaceAuthRejected_withTimeout() {
        testOnAuthenticationFailedInvoked_coex_whenFaceAuthRejected(
                BiometricConstants.BIOMETRIC_ERROR_TIMEOUT);
    }

    private void testOnAuthenticationFailedInvoked_coex_whenFaceAuthRejected(int error) {
        final int modality = BiometricAuthenticator.TYPE_FACE;
        final int userId = 0;

        enrollFingerprintAndFace(userId);

        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        mAuthController.onBiometricError(modality, error, 0 /* vendorCode */);

        verify(mDialog1).onAuthenticationFailed(mModalityCaptor.capture(), mMessageCaptor.capture());

        assertThat(mModalityCaptor.getValue().intValue()).isEqualTo(modality);
        assertThat(mMessageCaptor.getValue()).isEqualTo(
                mContext.getString(R.string.fingerprint_dialog_use_fingerprint_instead));
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenFingerprintAuthRejected() {
        final int modality = BiometricAuthenticator.TYPE_FINGERPRINT;
        final int userId = 0;

        enrollFingerprintAndFace(userId);

        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        mAuthController.onBiometricError(modality,
                BiometricConstants.BIOMETRIC_PAUSED_REJECTED,
                0 /* vendorCode */);

        verify(mDialog1).onAuthenticationFailed(mModalityCaptor.capture(), mMessageCaptor.capture());

        assertThat(mModalityCaptor.getValue().intValue()).isEqualTo(modality);
        assertThat(mMessageCaptor.getValue()).isEqualTo(
                mContext.getString(R.string.fingerprint_error_not_match));
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenBiometricTimedOut() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_FACE;
        final int error = BiometricConstants.BIOMETRIC_ERROR_TIMEOUT;
        final int vendorCode = 0;
        mAuthController.onBiometricError(modality, error, vendorCode);

        verify(mDialog1).onAuthenticationFailed(mModalityCaptor.capture(), mMessageCaptor.capture());

        assertThat(mModalityCaptor.getValue().intValue()).isEqualTo(modality);
        assertThat(mMessageCaptor.getValue()).isEqualTo(
                mContext.getString(R.string.biometric_not_recognized));
    }

    @Test
    public void testOnHelpInvoked_whenSystemRequested() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_IRIS;
        final String helpMessage = "help";
        mAuthController.onBiometricHelp(modality, helpMessage);

        verify(mDialog1).onHelp(mModalityCaptor.capture(), mMessageCaptor.capture());

        assertThat(mModalityCaptor.getValue().intValue()).isEqualTo(modality);
        assertThat(mMessageCaptor.getValue()).isEqualTo(helpMessage);
    }

    @Test
    public void testOnErrorInvoked_whenSystemRequested() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int modality = BiometricAuthenticator.TYPE_FACE;
        final int error = 1;
        final int vendorCode = 0;
        mAuthController.onBiometricError(modality, error, vendorCode);

        verify(mDialog1).onError(mModalityCaptor.capture(), mMessageCaptor.capture());

        assertThat(mModalityCaptor.getValue().intValue()).isEqualTo(modality);
        assertThat(mMessageCaptor.getValue()).isEqualTo(
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
        verify(mDialog1).animateToCredentialUI(eq(true));
    }

    @Test
    public void testErrorLockoutPermanent_whenCredentialAllowed_AnimatesToCredentialUI() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(true);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1, never()).onError(anyInt(), anyString());
        verify(mDialog1).animateToCredentialUI(eq(true));
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
        verify(mDialog1, never()).animateToCredentialUI(eq(true));
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
        verify(mDialog1, never()).animateToCredentialUI(eq(true));
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
        verify(mDialog1).show(any());

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
        verify(mDialog1).show(any());

        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        // First dialog should be dismissed without animation
        verify(mDialog1).dismissWithoutCallback(eq(false) /* animate */);

        // Second dialog should be shown without animation
        verify(mDialog2).show(any());
    }

    @Test
    public void testClientNotified_whenTaskStackChangesDuringShow() throws Exception {
        switchTask("other_package");
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        waitForIdleSync();

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
        waitForIdleSync();

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
        waitForIdleSync();

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
    public void testOnBiometricPromptDismissedCallback_hideAuthenticationDialog() {
        // GIVEN a callback is registered
        AuthController.Callback callback = mock(AuthController.Callback.class);
        mAuthController.addCallback(callback);

        // WHEN dialog is shown and then dismissed
        showDialog(new int[]{1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.hideAuthenticationDialog(mAuthController.mCurrentDialog.getRequestId());

        // THEN callback should be received
        verify(callback).onBiometricPromptDismissed();
    }

    @Test
    public void testSubscribesToLogContext() {
        mAuthController.setBiometricContextListener(mContextListener);
        verify(mLogContextInteractor).addBiometricContextListener(same(mContextListener));
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

    @Test
    public void testUpdateFingerprintLocation_defaultPointChanges_whenConfigChanges() {
        when(mContextSpy.getResources()).thenReturn(mResources);

        doReturn(500).when(mResources)
                .getDimensionPixelSize(eq(com.android.systemui.res.R.dimen
                        .physical_fingerprint_sensor_center_screen_location_y));
        mAuthController.onConfigChanged(null /* newConfig */);

        final Point firstFpLocation = mAuthController.getFingerprintSensorLocation();

        doReturn(1000).when(mResources)
                .getDimensionPixelSize(eq(com.android.systemui.res.R.dimen
                        .physical_fingerprint_sensor_center_screen_location_y));
        mAuthController.onConfigChanged(null /* newConfig */);

        assertNotSame(firstFpLocation, mAuthController.getFingerprintSensorLocation());
    }

    @Test
    public void testCloseDialog_whenGlobalActionsMenuShown() throws Exception {
        showDialog(new int[]{1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.handleShowGlobalActionsMenu();
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_USER_CANCEL),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testShowDialog_whenOwnerNotInForeground() {
        PromptInfo promptInfo = createTestPromptInfo();
        promptInfo.setAllowBackgroundAuthentication(false);
        switchTask("other_package");
        mAuthController.showAuthenticationDialog(promptInfo,
                mReceiver /* receiver */,
                new int[]{1} /* sensorIds */,
                false /* credentialAllowed */,
                true /* requireConfirmation */,
                0 /* userId */,
                0 /* operationId */,
                "testPackage",
                REQUEST_ID);

        assertNull(mAuthController.mCurrentDialog);
        verify(mDialog1, never()).show(any());
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
                REQUEST_ID);
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

    private void enrollFingerprintAndFace(final int userId) {

        // Enroll fingerprint
        verify(mFingerprintManager).registerBiometricStateListener(
                mBiometricStateCaptor.capture());
        assertFalse(mAuthController.isFingerprintEnrolled(userId));

        mBiometricStateCaptor.getValue().onEnrollmentsChanged(userId,
                1 /* sensorId */, true /* hasEnrollments */);
        waitForIdleSync();

        assertTrue(mAuthController.isFingerprintEnrolled(userId));

        // Enroll face
        verify(mFaceManager).registerBiometricStateListener(
                mBiometricStateCaptor.capture());
        assertFalse(mAuthController.isFaceAuthEnrolled(userId));

        mBiometricStateCaptor.getValue().onEnrollmentsChanged(userId,
                2 /* sensorId */, true /* hasEnrollments */);
        waitForIdleSync();

        assertTrue(mAuthController.isFaceAuthEnrolled(userId));
    }

    private final class TestableAuthController extends AuthController {
        private int mBuildCount = 0;
        private PromptInfo mLastBiometricPromptInfo;

        TestableAuthController(Context context) {
            super(context, null /* applicationCoroutineScope */,
                    mExecution, mCommandQueue, mActivityTaskManager, mWindowManager,
                    mFingerprintManager, mFaceManager, () -> mUdfpsController,
                    () -> mSideFpsController, mDisplayManager, mWakefulnessLifecycle,
                    mPanelInteractionDetector, mUserManager, mLockPatternUtils, () -> mUdfpsLogger,
                    () -> mLogContextInteractor,
                    () -> mBiometricPromptCredentialInteractor,
                    () -> mPromptSelectionInteractor, () -> mCredentialViewModel,
                    () -> mPromptViewModel, mInteractionJankMonitor, mHandler, mBackgroundExecutor,
                    mUdfpsUtils, mVibratorHelper);
        }

        @Override
        protected AuthDialog buildDialog(DelayableExecutor bgExecutor, PromptInfo promptInfo,
                boolean requireConfirmation, int userId, int[] sensorIds,
                String opPackageName, boolean skipIntro, long operationId, long requestId,
                WakefulnessLifecycle wakefulnessLifecycle,
                AuthDialogPanelInteractionDetector panelInteractionDetector,
                UserManager userManager,
                LockPatternUtils lockPatternUtils, PromptViewModel viewModel) {

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

    @Override
    protected void waitForIdleSync() {
        mTestableLooper.processAllMessages();
    }
}
