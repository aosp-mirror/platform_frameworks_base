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

import static android.hardware.biometrics.BiometricManager.Authenticators;
import static android.hardware.biometrics.BiometricManager.BIOMETRIC_MULTI_SENSOR_FACE_THEN_FINGERPRINT;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorProperties;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.os.Bundle;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.inject.Provider;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthControllerTest extends SysuiTestCase {

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private IBiometricSysuiReceiver mReceiver;
    @Mock
    private AuthDialog mDialog1;
    @Mock
    private AuthDialog mDialog2;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityTaskManager mActivityTaskManager;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private FaceManager mFaceManager;
    @Mock
    private UdfpsController mUdfpsController;
    @Captor
    ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback> mAuthenticatorsRegisteredCaptor;

    private TestableAuthController mAuthController;

    @Before
    public void setup() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        TestableContext context = spy(mContext);

        when(context.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .thenReturn(true);

        when(mDialog1.getOpPackageName()).thenReturn("Dialog1");
        when(mDialog2.getOpPackageName()).thenReturn("Dialog2");

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);
        when(mDialog2.isAllowDeviceCredentials()).thenReturn(false);

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

        mAuthController = new TestableAuthController(context, mCommandQueue,
                mStatusBarStateController, mActivityTaskManager, mFingerprintManager, mFaceManager,
                () -> mUdfpsController);

        mAuthController.start();
        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(
                mAuthenticatorsRegisteredCaptor.capture());
        mAuthenticatorsRegisteredCaptor.getValue().onAllAuthenticatorsRegistered(props);
    }

    // Callback tests

    @Test
    public void testSendsReasonUserCanceled_whenDismissedByUserCancel() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED,
                null /* credentialAttestation */);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_USER_CANCEL),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonNegative_whenDismissedByButtonNegative() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE,
                null /* credentialAttestation */);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_NEGATIVE),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonConfirmed_whenDismissedByButtonPositive() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BUTTON_POSITIVE,
                null /* credentialAttestation */);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonConfirmNotRequired_whenDismissedByAuthenticated() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED,
                null /* credentialAttestation */);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonError_whenDismissedByError() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_ERROR,
                null /* credentialAttestation */);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_ERROR),
                eq(null) /* credentialAttestation */);
    }

    @Test
    public void testSendsReasonServerRequested_whenDismissedByServer() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_BY_SYSTEM_SERVER,
                null /* credentialAttestation */);
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
                credentialAttestation);
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
        mAuthController.onBiometricAuthenticated();
        verify(mDialog1).onAuthenticationSucceeded();
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenBiometricRejected() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_NONE,
                BiometricConstants.BIOMETRIC_PAUSED_REJECTED,
                0 /* vendorCode */);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onAuthenticationFailed(captor.capture());

        assertEquals(captor.getValue(), mContext.getString(R.string.biometric_not_recognized));
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenBiometricTimedOut() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = BiometricConstants.BIOMETRIC_ERROR_TIMEOUT;
        final int vendorCode = 0;
        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onAuthenticationFailed(captor.capture());

        assertEquals(captor.getValue(), FaceManager.getErrorString(mContext, error, vendorCode));
    }

    @Test
    public void testOnHelpInvoked_whenSystemRequested() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final String helpMessage = "help";
        mAuthController.onBiometricHelp(helpMessage);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onHelp(captor.capture());

        assertEquals(captor.getValue(), helpMessage);
    }

    @Test
    public void testOnErrorInvoked_whenSystemRequested() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = 1;
        final int vendorCode = 0;
        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onError(captor.capture());

        assertEquals(captor.getValue(), FaceManager.getErrorString(mContext, error, vendorCode));
    }

    @Test
    public void testErrorLockout_whenCredentialAllowed_AnimatesToCredentialUI() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(true);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1, never()).onError(anyString());
        verify(mDialog1).animateToCredentialUI();
    }

    @Test
    public void testErrorLockoutPermanent_whenCredentialAllowed_AnimatesToCredentialUI() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(true);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1, never()).onError(anyString());
        verify(mDialog1).animateToCredentialUI();
    }

    @Test
    public void testErrorLockout_whenCredentialNotAllowed_sendsOnError() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1).onError(eq(FaceManager.getErrorString(mContext, error, vendorCode)));
        verify(mDialog1, never()).animateToCredentialUI();
    }

    @Test
    public void testErrorLockoutPermanent_whenCredentialNotAllowed_sendsOnError() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        final int error = BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
        final int vendorCode = 0;

        when(mDialog1.isAllowDeviceCredentials()).thenReturn(false);

        mAuthController.onBiometricError(BiometricAuthenticator.TYPE_FACE, error, vendorCode);
        verify(mDialog1).onError(eq(FaceManager.getErrorString(mContext, error, vendorCode)));
        verify(mDialog1, never()).animateToCredentialUI();
    }

    @Test
    public void testHideAuthenticationDialog_invokesDismissFromSystemServer() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.hideAuthenticationDialog();
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
                credentialAttestation);
        verify(mReceiver).onDialogDismissed(
                eq(BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED),
                AdditionalMatchers.aryEq(credentialAttestation));

        mAuthController.hideAuthenticationDialog();
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
            savedState.putInt(
                    AuthDialog.KEY_CONTAINER_STATE, AuthContainerView.STATE_SHOWING);
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
            savedState.putInt(
                    AuthDialog.KEY_CONTAINER_STATE, AuthContainerView.STATE_SHOWING);
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
    public void testClientNotified_whenTaskStackChangesDuringAuthentication() throws Exception {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);

        List<ActivityManager.RunningTaskInfo> tasks = new ArrayList<>();
        ActivityManager.RunningTaskInfo taskInfo = mock(ActivityManager.RunningTaskInfo.class);
        taskInfo.topActivity = mock(ComponentName.class);
        when(taskInfo.topActivity.getPackageName()).thenReturn("other_package");
        tasks.add(taskInfo);
        when(mActivityTaskManager.getTasks(anyInt())).thenReturn(tasks);

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
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED,
                null /* credentialAttestation */);
        mAuthController.onTryAgainPressed();
    }

    @Test
    public void testDoesNotCrash_whenDeviceCredentialPressedAfterDismissal() {
        showDialog(new int[] {1} /* sensorIds */, false /* credentialAllowed */);
        mAuthController.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED,
                null /* credentialAttestation */);
        mAuthController.onDeviceCredentialPressed();
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

    // Helpers

    private void showDialog(int[] sensorIds, boolean credentialAllowed) {
        mAuthController.showAuthenticationDialog(createTestPromptInfo(),
                mReceiver /* receiver */,
                sensorIds,
                credentialAllowed,
                true /* requireConfirmation */,
                0 /* userId */,
                "testPackage",
                0 /* operationId */,
                BIOMETRIC_MULTI_SENSOR_FACE_THEN_FINGERPRINT);
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

        TestableAuthController(Context context, CommandQueue commandQueue,
                StatusBarStateController statusBarStateController,
                ActivityTaskManager activityTaskManager,
                FingerprintManager fingerprintManager,
                FaceManager faceManager,
                Provider<UdfpsController> udfpsControllerFactory) {
            super(context, commandQueue, statusBarStateController, activityTaskManager,
                    fingerprintManager, faceManager, udfpsControllerFactory);
        }

        @Override
        protected AuthDialog buildDialog(PromptInfo promptInfo,
                boolean requireConfirmation, int userId, int[] sensorIds, boolean credentialAllowed,
                String opPackageName, boolean skipIntro, long operationId,
                @BiometricManager.BiometricMultiSensorMode int multiSensorConfig) {

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

