/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;
import static android.content.pm.UserInfo.FLAG_MANAGED_PROFILE;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_TIMEOUT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_AVAILABLE;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_RECOGNIZED;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_ADAPTIVE_AUTH;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_ALIGNMENT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BATTERY;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_DISCLOSURE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_LOGOUT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_OWNER_INFO;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_TRANSIENT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_TRUST;
import static com.android.systemui.keyguard.ScreenLifecycle.SCREEN_OFF;
import static com.android.systemui.keyguard.ScreenLifecycle.SCREEN_TURNING_ON;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.UserInfo;
import android.graphics.Color;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.os.BatteryManager;
import android.os.RemoteException;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.keyguard.TrustGrantFlags;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.KeyguardIndication;
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController;
import com.android.systemui.res.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class KeyguardIndicationControllerTest extends KeyguardIndicationControllerBaseTest {
    @Test
    public void afterFaceLockout_skipShowingFaceNotRecognized() {
        createController();
        onFaceLockoutError("lockout");
        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE, "lockout");
        clearInvocations(mRotateTextViewController);

        // WHEN face sends an onBiometricHelp BIOMETRIC_HELP_FACE_NOT_RECOGNIZED (face fail)
        mKeyguardUpdateMonitorCallback.onBiometricHelp(
                BIOMETRIC_HELP_FACE_NOT_RECOGNIZED,
                "Face not recognized",
                BiometricSourceType.FACE);
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE); // no updated message
    }

    @Test
    public void createController_setIndicationAreaAgain_destroysPreviousRotateTextViewController() {
        // GIVEN a controller with a mocked rotate text view controlller
        final KeyguardIndicationRotateTextViewController mockedRotateTextViewController =
                mock(KeyguardIndicationRotateTextViewController.class);
        createController();
        mController.mRotateTextViewController = mockedRotateTextViewController;

        // WHEN a new indication area is set
        mController.setIndicationArea(mIndicationArea);

        // THEN the previous rotateTextViewController is destroyed
        verify(mockedRotateTextViewController).destroy();
    }

    @Test
    public void createController_addsAlignmentListener() {
        createController();

        verify(mDockManager).addAlignmentStateListener(
                any(DockManager.AlignmentStateListener.class));
    }

    @Test
    public void onAlignmentStateChanged_showsSlowChargingIndication() {
        createController();
        verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
        mController.setVisible(true);

        mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_POOR);
        mTestableLooper.processAllMessages();

        verifyIndicationMessage(INDICATION_TYPE_ALIGNMENT,
                mContext.getResources().getString(R.string.dock_alignment_slow_charging));
        assertThat(mKeyguardIndicationCaptor.getValue().getTextColor().getDefaultColor())
                .isEqualTo(mContext.getColor(R.color.misalignment_text_color));
    }

    @Test
    public void onAlignmentStateChanged_showsNotChargingIndication() {
        createController();
        verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
        mController.setVisible(true);

        mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_TERRIBLE);
        mTestableLooper.processAllMessages();

        verifyIndicationMessage(INDICATION_TYPE_ALIGNMENT,
                mContext.getResources().getString(R.string.dock_alignment_not_charging));
        assertThat(mKeyguardIndicationCaptor.getValue().getTextColor().getDefaultColor())
                .isEqualTo(mContext.getColor(R.color.misalignment_text_color));
    }

    @FlakyTest(bugId = 279944472)
    @Test
    public void onAlignmentStateChanged_whileDozing_showsSlowChargingIndication() {
        createController();
        verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
        mController.setVisible(true);
        mStatusBarStateListener.onDozingChanged(true);

        mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_POOR);
        mTestableLooper.processAllMessages();

        assertThat(mTextView.getText()).isEqualTo(
                mContext.getResources().getString(R.string.dock_alignment_slow_charging));
        assertThat(mTextView.getCurrentTextColor()).isEqualTo(
                mContext.getColor(R.color.misalignment_text_color));
    }

    @Test
    public void onAlignmentStateChanged_whileDozing_showsNotChargingIndication() {
        createController();
        verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
        mController.setVisible(true);
        mStatusBarStateListener.onDozingChanged(true);

        mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_TERRIBLE);
        mTestableLooper.processAllMessages();

        assertThat(mTextView.getText()).isEqualTo(
                mContext.getResources().getString(R.string.dock_alignment_not_charging));
        assertThat(mTextView.getCurrentTextColor()).isEqualTo(
                mContext.getColor(R.color.misalignment_text_color));
    }

    @Test
    public void disclosure_unmanaged() {
        createController();
        mController.setVisible(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        when(mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile()).thenReturn(false);
        reset(mRotateTextViewController);

        sendUpdateDisclosureBroadcast();
        mExecutor.runAllReady();

        verifyHideIndication(INDICATION_TYPE_DISCLOSURE);
    }

    @Test
    public void disclosure_deviceOwner_noOrganizationName() {
        createController();
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(null);
        sendUpdateDisclosureBroadcast();
        mController.setVisible(true);
        mExecutor.runAllReady();

        verifyIndicationMessage(INDICATION_TYPE_DISCLOSURE, mDisclosureGeneric);
    }

    @Test
    public void disclosure_orgOwnedDeviceWithManagedProfile_noOrganizationName() {
        createController();
        mController.setVisible(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile()).thenReturn(true);
        when(mUserManager.getProfiles(anyInt())).thenReturn(Collections.singletonList(
                new UserInfo(10, /* name */ null, /* flags */ FLAG_MANAGED_PROFILE)));
        when(mDevicePolicyManager.getOrganizationNameForUser(eq(10))).thenReturn(null);
        sendUpdateDisclosureBroadcast();
        mExecutor.runAllReady();

        verifyIndicationMessage(INDICATION_TYPE_DISCLOSURE, mDisclosureGeneric);
    }

    @Test
    public void disclosure_deviceOwner_withOrganizationName() {
        createController();
        mController.setVisible(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(ORGANIZATION_NAME);
        sendUpdateDisclosureBroadcast();
        mExecutor.runAllReady();

        verifyIndicationMessage(INDICATION_TYPE_DISCLOSURE, mDisclosureWithOrganization);
    }

    @Test
    public void disclosure_orgOwnedDeviceWithManagedProfile_withOrganizationName() {
        createController();
        mController.setVisible(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile()).thenReturn(true);
        when(mUserManager.getProfiles(anyInt())).thenReturn(Collections.singletonList(
                new UserInfo(10, /* name */ null, FLAG_MANAGED_PROFILE)));
        when(mDevicePolicyManager.getOrganizationNameForUser(eq(10))).thenReturn(ORGANIZATION_NAME);
        sendUpdateDisclosureBroadcast();
        mExecutor.runAllReady();

        verifyIndicationMessage(INDICATION_TYPE_DISCLOSURE, mDisclosureWithOrganization);
    }

    @Test
    public void disclosure_updateOnTheFly() {
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        createController();
        mController.setVisible(true);

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(null);
        sendUpdateDisclosureBroadcast();
        mExecutor.runAllReady();

        verifyIndicationMessage(INDICATION_TYPE_DISCLOSURE, mDisclosureGeneric);
        reset(mRotateTextViewController);

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(ORGANIZATION_NAME);
        sendUpdateDisclosureBroadcast();
        mExecutor.runAllReady();

        verifyIndicationMessage(INDICATION_TYPE_DISCLOSURE, mDisclosureWithOrganization);
        reset(mRotateTextViewController);

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        sendUpdateDisclosureBroadcast();
        mExecutor.runAllReady();

        verifyHideIndication(INDICATION_TYPE_DISCLOSURE);
    }

    @Test
    public void disclosure_deviceOwner_financedDeviceWithOrganizationName() {
        createController();
        mController.setVisible(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn(ORGANIZATION_NAME);
        when(mDevicePolicyManager.isFinancedDevice()).thenReturn(true);
        // TODO(b/259908270): remove
        when(mDevicePolicyManager.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_FINANCED);
        sendUpdateDisclosureBroadcast();
        mExecutor.runAllReady();
        mController.setVisible(true);

        verifyIndicationMessage(INDICATION_TYPE_DISCLOSURE, mFinancedDisclosureWithOrganization);
    }

    @Test
    public void transientIndication_holdsWakeLock_whenDozing() {
        // GIVEN animations are enabled and text is visible
        mTextView.setAnimationsEnabled(true);
        createController();
        mController.setVisible(true);

        // WHEN transient text is shown
        mStatusBarStateListener.onDozingChanged(true);
        mController.showTransientIndication(TEST_STRING_RES);

        // THEN wake lock is held while the animation is running
        assertTrue("WakeLock expected: HELD, was: RELEASED", mWakeLock.isHeld());
    }

    @Test
    public void transientIndication_releasesWakeLock_whenDozing() {
        // GIVEN animations aren't enabled
        mTextView.setAnimationsEnabled(false);
        createController();
        mController.setVisible(true);

        // WHEN we show the transient indication
        mStatusBarStateListener.onDozingChanged(true);
        mController.showTransientIndication(TEST_STRING_RES);

        // THEN wake lock is RELEASED, not held
        assertFalse("WakeLock expected: RELEASED, was: HELD", mWakeLock.isHeld());
    }

    @Test
    public void transientIndication_visibleWhenDozing() {
        createController();
        mController.setVisible(true);

        mStatusBarStateListener.onDozingChanged(true);
        mController.showTransientIndication(TEST_STRING_RES);

        assertThat(mTextView.getText()).isEqualTo(
                mContext.getResources().getString(TEST_STRING_RES));
        assertThat(mTextView.getCurrentTextColor()).isEqualTo(Color.WHITE);
        assertThat(mTextView.getAlpha()).isEqualTo(1f);
    }

    @Test
    public void transientIndication_visibleWhenDozing_unlessSwipeUp_fromHelp() {
        createController();
        String message = "A message";

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricHelp(
                BIOMETRIC_HELP_FACE_NOT_RECOGNIZED, message,
                BiometricSourceType.FACE);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, message);
        reset(mRotateTextViewController);
        mStatusBarStateListener.onDozingChanged(true);

        assertThat(mTextView.getText()).isNotEqualTo(message);
    }

    @Test
    public void transientIndication_visibleWhenWokenUp() {
        createController();
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        final String message = "helpMsg";

        // GIVEN screen is off
        when(mScreenLifecycle.getScreenState()).thenReturn(SCREEN_OFF);

        // WHEN fingeprint help message received
        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricHelp(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                message, BiometricSourceType.FINGERPRINT);

        // THEN message isn't shown right away
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);

        // WHEN the screen turns on
        mScreenObserver.onScreenTurnedOn();
        mTestableLooper.processAllMessages();

        // THEN the message is shown
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, message);
    }

    @Test
    public void onBiometricHelp_coEx_faceFailure() {
        createController();

        // GIVEN unlocking with fingerprint is possible and allowed
        fingerprintUnlockIsPossibleAndAllowed();

        String message = "A message";
        mController.setVisible(true);

        // WHEN there's a face not recognized message
        mController.getKeyguardCallback().onBiometricHelp(
                BIOMETRIC_HELP_FACE_NOT_RECOGNIZED,
                message,
                BiometricSourceType.FACE);

        // THEN show sequential messages such as: 'face not recognized' and
        // 'try fingerprint instead'
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE,
                mContext.getString(R.string.keyguard_face_failed));
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_suggest_fingerprint));
    }

    @Test
    public void onBiometricHelp_coEx_faceUnavailable() {
        createController();

        // GIVEN unlocking with fingerprint is possible and allowed
        fingerprintUnlockIsPossibleAndAllowed();

        String message = "A message";
        mController.setVisible(true);

        // WHEN there's a face unavailable message
        mController.getKeyguardCallback().onBiometricHelp(
                BIOMETRIC_HELP_FACE_NOT_AVAILABLE,
                message,
                BiometricSourceType.FACE);

        // THEN show sequential messages such as: 'face unlock unavailable' and
        // 'try fingerprint instead'
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE,
                message);
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_suggest_fingerprint));
    }


    @Test
    public void onBiometricHelp_coEx_faceUnavailable_fpNotAllowed() {
        createController();

        // GIVEN unlocking with fingerprint is possible but not allowed
        setupFingerprintUnlockPossible(true);
        when(mKeyguardUpdateMonitor.isUnlockingWithFingerprintAllowed())
                .thenReturn(false);

        String message = "A message";
        mController.setVisible(true);

        // WHEN there's a face unavailable message
        mController.getKeyguardCallback().onBiometricHelp(
                BIOMETRIC_HELP_FACE_NOT_AVAILABLE,
                message,
                BiometricSourceType.FACE);

        // THEN show sequential messages such as: 'face unlock unavailable' and
        // 'try fingerprint instead'
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE,
                message);
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void onBiometricHelp_coEx_fpFailure_faceAlreadyUnlocked() {
        createController();

        // GIVEN face has already unlocked the device
        when(mKeyguardUpdateMonitor.isCurrentUserUnlockedWithFace()).thenReturn(true);

        String message = "A message";
        mController.setVisible(true);

        // WHEN there's a fingerprint not recognized message
        mController.getKeyguardCallback().onBiometricHelp(
                BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                message,
                BiometricSourceType.FINGERPRINT);

        // THEN show sequential messages such as: 'Unlocked by face' and
        // 'Swipe up to open'
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE,
                mContext.getString(R.string.keyguard_face_successful_unlock));
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void onBiometricHelp_coEx_fpFailure_trustAgentAlreadyUnlocked() {
        createController();

        // GIVEN trust agent has already unlocked the device
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);

        String message = "A message";
        mController.setVisible(true);

        // WHEN there's a fingerprint not recognized message
        mController.getKeyguardCallback().onBiometricHelp(
                BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                message,
                BiometricSourceType.FINGERPRINT);

        // THEN show sequential messages such as: 'Kept unlocked by TrustAgent' and
        // 'Swipe up to open'
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE,
                mContext.getString(R.string.keyguard_indication_trust_unlocked));
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void onBiometricHelp_coEx_fpFailure_trustAgentUnlocked_emptyTrustGrantedMessage() {
        createController();

        // GIVEN trust agent has already unlocked the device & trust granted message is empty
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        mController.showTrustGrantedMessage(false, "");

        String message = "A message";
        mController.setVisible(true);

        // WHEN there's a fingerprint not recognized message
        mController.getKeyguardCallback().onBiometricHelp(
                BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                message,
                BiometricSourceType.FINGERPRINT);

        // THEN show action to unlock (ie: 'Swipe up to open')
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void transientIndication_visibleWhenDozing_unlessSwipeUp_fromError() {
        createController();
        String message = mContext.getString(R.string.keyguard_unlock);

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(FACE_ERROR_TIMEOUT,
                "A message", BiometricSourceType.FACE);

        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, message);
        mStatusBarStateListener.onDozingChanged(true);

        assertThat(mTextView.getText()).isNotEqualTo(message);
    }

    @Test
    public void transientIndication_visibleWhenDozing_ignoresFingerprintErrorMsg() {
        createController();
        mController.setVisible(true);
        reset(mRotateTextViewController);

        // WHEN a fingerprint error user cancelled message is received
        mController.getKeyguardCallback().onBiometricError(
                BiometricFingerprintConstants.FINGERPRINT_ERROR_USER_CANCELED, "foo",
                BiometricSourceType.FINGERPRINT);

        // THEN no message is shown
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);
        verifyNoMessage(INDICATION_TYPE_TRANSIENT);
    }

    @Test
    public void transientIndication_swipeUpToRetry() {
        createController();
        String message = mContext.getString(R.string.keyguard_retry);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        when(mKeyguardUpdateMonitor.isFaceEnabledAndEnrolled()).thenReturn(true);
        when(mKeyguardUpdateMonitor.getIsFaceAuthenticated()).thenReturn(false);

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(FACE_ERROR_TIMEOUT,
                "A message", BiometricSourceType.FACE);

        verify(mStatusBarKeyguardViewManager).setKeyguardMessage(eq(message), any(), any());
    }

    @Test
    public void transientIndication_swipeUpToRetry_faceAuthenticated() {
        createController();
        String message = mContext.getString(R.string.keyguard_retry);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        when(mKeyguardUpdateMonitor.getIsFaceAuthenticated()).thenReturn(true);
        when(mKeyguardUpdateMonitor.isFaceEnabledAndEnrolled()).thenReturn(true);

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(FACE_ERROR_TIMEOUT,
                "A message", BiometricSourceType.FACE);

        verify(mStatusBarKeyguardViewManager, never()).setKeyguardMessage(
                eq(message), any(), any());
    }

    @Test
    public void faceErrorTimeout_whenFingerprintEnrolled_doesNotShowMessage() {
        createController();
        fingerprintUnlockIsPossibleAndAllowed();
        String message = "A message";

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(
                FACE_ERROR_TIMEOUT, message, BiometricSourceType.FACE);
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);
    }

    @Test
    public void sendFaceHelpMessages_fingerprintEnrolled() {
        createController();
        mController.mCoExAcquisitionMsgIdsToShowCallback.accept(
                Set.of(
                        BiometricFaceConstants.FACE_ACQUIRED_MOUTH_COVERING_DETECTED,
                        BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED
                )
        );

        // GIVEN unlocking with fingerprint is possible and allowed
        fingerprintUnlockIsPossibleAndAllowed();

        // WHEN help messages received that are allowed to show
        final String helpString = "helpString";
        final int[] msgIds = new int[]{
                BiometricFaceConstants.FACE_ACQUIRED_MOUTH_COVERING_DETECTED,
                BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED
        };
        Set<CharSequence> messages = new HashSet<>();
        for (int msgId : msgIds) {
            final String message = helpString + msgId;
            messages.add(message);
            mKeyguardUpdateMonitorCallback.onBiometricHelp(
                    msgId, message, BiometricSourceType.FACE);
        }

        // THEN FACE_ACQUIRED_MOUTH_COVERING_DETECTED and DARK_GLASSES help messages shown
        verifyIndicationMessages(INDICATION_TYPE_BIOMETRIC_MESSAGE,
                messages);
    }

    @Test
    public void doNotSendMostFaceHelpMessages_fingerprintEnrolled() {
        createController();

        // GIVEN unlocking with fingerprint is possible and allowed
        fingerprintUnlockIsPossibleAndAllowed();

        // WHEN help messages received that aren't supposed to show
        final String helpString = "helpString";
        final int[] msgIds = new int[]{
                BiometricFaceConstants.FACE_ACQUIRED_FACE_OBSCURED,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_RIGHT,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_LEFT,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_HIGH,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_LOW,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT
        };
        for (int msgId : msgIds) {
            mKeyguardUpdateMonitorCallback.onBiometricHelp(
                    msgId,  helpString + msgId, BiometricSourceType.FACE);
        }

        // THEN no messages shown
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);
    }

    @Test
    public void sendAllFaceHelpMessages_fingerprintNotEnrolled() {
        createController();

        // GIVEN fingerprint NOT possible
        fingerprintUnlockIsNotPossible();

        // WHEN help messages received
        final Set<CharSequence> helpStrings = new HashSet<>();
        final String helpString = "helpString";
        final int[] msgIds = new int[]{
                BiometricFaceConstants.FACE_ACQUIRED_FACE_OBSCURED,
                BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_RIGHT,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_LEFT,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_HIGH,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_LOW,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT
        };
        for (int msgId : msgIds) {
            final String numberedHelpString = helpString + msgId;
            mKeyguardUpdateMonitorCallback.onBiometricHelp(
                    msgId,  numberedHelpString, BiometricSourceType.FACE);
            helpStrings.add(numberedHelpString);
        }

        // THEN message shown for each call
        verifyIndicationMessages(INDICATION_TYPE_BIOMETRIC_MESSAGE, helpStrings);
    }

    @Test
    public void sendTooDarkFaceHelpMessages_onTimeout_noFpEnrolled() {
        createController();

        // GIVEN fingerprint not possible
        fingerprintUnlockIsNotPossible();

        // WHEN help message received and deferred message is valid
        final String helpString = "helpMsg";
        when(mFaceHelpMessageDeferral.getDeferredMessage()).thenReturn(helpString);
        when(mFaceHelpMessageDeferral.shouldDefer(FACE_ACQUIRED_TOO_DARK)).thenReturn(true);
        mKeyguardUpdateMonitorCallback.onBiometricHelp(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK,
                helpString,
                BiometricSourceType.FACE
        );

        // THEN help message not shown yet
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);

        // WHEN face timeout error received
        mKeyguardUpdateMonitorCallback.onBiometricError(FACE_ERROR_TIMEOUT, "face timeout",
                BiometricSourceType.FACE);

        // THEN the low light message shows with suggestion to swipe up to unlock
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, helpString);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void sendTooDarkFaceHelpMessages_onTimeout_fingerprintEnrolled() {
        createController();

        // GIVEN unlocking with fingerprint is possible and allowed
        fingerprintUnlockIsPossibleAndAllowed();

        // WHEN help message received and deferredMessage is valid
        final String helpString = "helpMsg";
        when(mFaceHelpMessageDeferral.getDeferredMessage()).thenReturn(helpString);
        when(mFaceHelpMessageDeferral.shouldDefer(FACE_ACQUIRED_TOO_DARK)).thenReturn(true);
        mKeyguardUpdateMonitorCallback.onBiometricHelp(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK,
                helpString,
                BiometricSourceType.FACE
        );

        // THEN help message not shown yet
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);

        // WHEN face timeout error received
        mKeyguardUpdateMonitorCallback.onBiometricError(FACE_ERROR_TIMEOUT, "face timeout",
                BiometricSourceType.FACE);

        // THEN the low light message shows and suggests trying fingerprint
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, helpString);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_suggest_fingerprint));
    }

    @Test
    public void indicationAreaHidden_untilBatteryInfoArrives() {
        createController();
        // level of -1 indicates missing info
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_UNKNOWN,
                -1 /* level */, BatteryManager.BATTERY_PLUGGED_WIRELESS, 100 /* health */,
                0 /* maxChargingWattage */, true /* present */);

        mController.setVisible(true);
        mStatusBarStateListener.onDozingChanged(true);
        reset(mIndicationArea);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        // VISIBLE is always called first
        verify(mIndicationArea).setVisibility(VISIBLE);
        verify(mIndicationArea).setVisibility(GONE);
    }

    @Test
    public void onRefreshBatteryInfo_computesChargingTime() throws RemoteException {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                80 /* level */, BatteryManager.BATTERY_PLUGGED_WIRELESS, 100 /* health */,
                0 /* maxChargingWattage */, true /* present */);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        verify(mIBatteryStats).computeChargeTimeRemaining();
    }

    @Test
    public void onRefreshBatteryInfo_computesChargingTime_onlyWhenCharging()
            throws RemoteException {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                80 /* level */, 0 /* plugged */, 100 /* health */,
                0 /* maxChargingWattage */, true /* present */);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        verify(mIBatteryStats, never()).computeChargeTimeRemaining();
    }

    /**
     * Regression test.
     * We should not make calls to the system_process when updating the doze state.
     */
    @Test
    public void setDozing_noIBatteryCalls() throws RemoteException {
        createController();
        mController.setVisible(true);
        mStatusBarStateListener.onDozingChanged(true);
        mStatusBarStateListener.onDozingChanged(false);
        verify(mIBatteryStats, never()).computeChargeTimeRemaining();
    }

    @Test
    public void registersKeyguardStateCallback() {
        createController();
        verify(mKeyguardStateController).addCallback(any());
    }

    @Test
    public void unlockMethodCache_listenerUpdatesPluggedIndication() {
        createController();
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        mController.setPowerPluggedIn(true);
        mController.setVisible(true);

        verifyIndicationMessage(
                INDICATION_TYPE_TRUST,
                mContext.getString(R.string.keyguard_indication_trust_unlocked));
    }

    @Test
    public void onRefreshBatteryInfo_chargingWithLongLife_presentChargingLimited() {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                80 /* level */, BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE, 0 /* maxChargingWattage */,
                true /* present */);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        mController.setVisible(true);

        verifyIndicationMessage(
                INDICATION_TYPE_BATTERY,
                mContext.getString(
                        R.string.keyguard_plugged_in_charging_limited,
                        NumberFormat.getPercentInstance().format(80 / 100f)));
    }

    @Test
    public void onRefreshBatteryInfo_fullChargedWithLongLife_presentChargingLimited() {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                100 /* level */, BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE, 0 /* maxChargingWattage */,
                true /* present */);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        mController.setVisible(true);

        verifyIndicationMessage(
                INDICATION_TYPE_BATTERY,
                mContext.getString(
                        R.string.keyguard_plugged_in_charging_limited,
                        NumberFormat.getPercentInstance().format(100 / 100f)));
    }

    @Test
    public void onRefreshBatteryInfo_fullChargedWithoutLongLife_presentCharged() {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                100 /* level */, BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.CHARGING_POLICY_DEFAULT, 0 /* maxChargingWattage */,
                true /* present */);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        mController.setVisible(true);

        verifyIndicationMessage(
                INDICATION_TYPE_BATTERY,
                mContext.getString(R.string.keyguard_charged));
    }

    @Test
    public void onRefreshBatteryInfo_dozing_dischargingWithLongLife_presentBatteryPercentage() {
        createController();
        mController.setVisible(true);
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_DISCHARGING,
                90 /* level */, 0 /* plugged */, BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE,
                0 /* maxChargingWattage */, true /* present */);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        mStatusBarStateListener.onDozingChanged(true);

        String percentage = NumberFormat.getPercentInstance().format(90 / 100f);
        assertThat(mTextView.getText()).isEqualTo(percentage);
    }

    @Test
    public void onRequireUnlockForNfc_showsRequireUnlockForNfcIndication() {
        createController();
        mController.setVisible(true);
        String message = mContext.getString(R.string.require_unlock_for_nfc);
        mController.getKeyguardCallback().onRequireUnlockForNfc();

        verifyTransientMessage(message);
    }

    @Test
    public void testEmptyOwnerInfoHidesIndicationArea() {
        createController();

        // GIVEN the owner info is set to an empty string & keyguard is showing
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mLockPatternUtils.getDeviceOwnerInfo()).thenReturn("");

        // WHEN asked to update the indication area
        mController.setVisible(true);
        mExecutor.runAllReady();

        // THEN the owner info should be hidden
        verifyHideIndication(INDICATION_TYPE_OWNER_INFO);
    }

    @Test
    public void testOnKeyguardShowingChanged_notShowing_resetsMessages() {
        createController();

        // GIVEN keyguard isn't showing
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        // WHEN keyguard showing changed called
        mKeyguardStateControllerCallback.onKeyguardShowingChanged();

        // THEN messages are reset
        verify(mRotateTextViewController).clearMessages();
        assertThat(mTextView.getText()).isEqualTo("");
    }

    @Test
    public void testOnKeyguardShowingChanged_showing_updatesPersistentMessages() {
        createController();
        mController.setVisible(true);
        mExecutor.runAllReady();
        reset(mRotateTextViewController);

        // GIVEN keyguard is showing and not dozing
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mController.setVisible(true);
        mExecutor.runAllReady();
        reset(mRotateTextViewController);

        // WHEN keyguard showing changed called
        mKeyguardStateControllerCallback.onKeyguardShowingChanged();
        mExecutor.runAllReady();

        // THEN persistent messages are updated (in this case, most messages are hidden since
        // no info is provided) - verify that this happens
        verify(mRotateTextViewController).hideIndication(INDICATION_TYPE_DISCLOSURE);
        verify(mRotateTextViewController).hideIndication(INDICATION_TYPE_OWNER_INFO);
        verify(mRotateTextViewController).hideIndication(INDICATION_TYPE_BATTERY);
        verify(mRotateTextViewController).hideIndication(INDICATION_TYPE_TRUST);
        verify(mRotateTextViewController).hideIndication(INDICATION_TYPE_ALIGNMENT);
        verify(mRotateTextViewController).hideIndication(INDICATION_TYPE_LOGOUT);
    }

    @Test
    public void onTrustGrantedMessageDoesNotShowUntilTrustGranted() {
        createController();
        mController.setVisible(true);
        reset(mRotateTextViewController);

        // GIVEN a trust granted message but trust isn't granted
        final String trustGrantedMsg = "testing trust granted message";
        mController.getKeyguardCallback().onTrustGrantedForCurrentUser(
                false, false, new TrustGrantFlags(0), trustGrantedMsg);

        verifyHideIndication(INDICATION_TYPE_TRUST);

        // WHEN trust is granted
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        mKeyguardUpdateMonitorCallback.onTrustChanged(getCurrentUser());

        // THEN verify the trust granted message shows
        verifyIndicationMessage(
                INDICATION_TYPE_TRUST,
                trustGrantedMsg);
    }

    @Test
    public void onTrustGrantedMessageShowsOnTrustGranted() {
        createController();
        mController.setVisible(true);

        // GIVEN trust is granted
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);

        // WHEN the showTrustGranted method is called
        final String trustGrantedMsg = "testing trust granted message";
        mController.getKeyguardCallback().onTrustGrantedForCurrentUser(
                false, false, new TrustGrantFlags(0), trustGrantedMsg);

        // THEN verify the trust granted message shows
        verifyIndicationMessage(
                INDICATION_TYPE_TRUST,
                trustGrantedMsg);
    }

    @Test
    public void onTrustGrantedMessage_nullMessage_showsDefaultMessage() {
        createController();
        mController.setVisible(true);

        // GIVEN trust is granted
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);

        // WHEN the showTrustGranted method is called with a null message
        mController.getKeyguardCallback().onTrustGrantedForCurrentUser(
                false, false, new TrustGrantFlags(0), null);

        // THEN verify the default trust granted message shows
        verifyIndicationMessage(
                INDICATION_TYPE_TRUST,
                getContext().getString(R.string.keyguard_indication_trust_unlocked));
    }

    @Test
    public void onTrustGrantedMessage_emptyString_showsNoMessage() {
        createController();
        mController.setVisible(true);

        // GIVEN trust is granted
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);

        // WHEN the showTrustGranted method is called with an EMPTY string
        mController.getKeyguardCallback().onTrustGrantedForCurrentUser(
                false, false, new TrustGrantFlags(0), "");

        // THEN verify NO trust message is shown
        verifyNoMessage(INDICATION_TYPE_TRUST);
    }

    @Test
    public void coEx_faceSuccess_showsPressToOpen() {
        // GIVEN bouncer isn't showing, can skip bouncer, udfps is supported, no a11y enabled
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(true);
        when(mAccessibilityManager.isEnabled()).thenReturn(false);
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(false);
        createController();
        mController.setVisible(true);

        // WHEN face auth succeeds
        when(mKeyguardUpdateMonitor.getIsFaceAuthenticated()).thenReturn(true);
        mController.getKeyguardCallback().onBiometricAuthenticated(0,
                BiometricSourceType.FACE, false);

        // THEN 'face unlocked' then 'press unlock icon to open' message show
        String unlockedByFace = mContext.getString(R.string.keyguard_face_successful_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, unlockedByFace);
        String pressToOpen = mContext.getString(R.string.keyguard_unlock_press);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP, pressToOpen);
    }

    @Test
    public void coEx_faceSuccess_touchExplorationEnabled_showsFaceUnlockedSwipeToOpen() {
        // GIVEN bouncer isn't showing, can skip bouncer, udfps is supported, a11y enabled
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(true);
        when(mAccessibilityManager.isEnabled()).thenReturn(true);
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(true);
        createController();
        mController.setVisible(true);

        // WHEN face authenticated
        when(mKeyguardUpdateMonitor.getIsFaceAuthenticated()).thenReturn(true);
        mController.getKeyguardCallback().onBiometricAuthenticated(0,
                BiometricSourceType.FACE, false);

        // THEN show 'face unlocked' and 'swipe up to open' messages
        String unlockedByFace = mContext.getString(R.string.keyguard_face_successful_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, unlockedByFace);
        String swipeUpToOpen = mContext.getString(R.string.keyguard_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP, swipeUpToOpen);
    }

    @Test
    public void coEx_faceSuccess_a11yEnabled_showsFaceUnlockedSwipeToOpen() {
        // GIVEN bouncer isn't showing, can skip bouncer, udfps is supported, a11y is enabled
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(true);
        when(mAccessibilityManager.isEnabled()).thenReturn(true);
        createController();
        mController.setVisible(true);

        // WHEN face auth is successful
        when(mKeyguardUpdateMonitor.getIsFaceAuthenticated()).thenReturn(true);
        mController.getKeyguardCallback().onBiometricAuthenticated(0,
                BiometricSourceType.FACE, false);

        // THEN show 'face unlocked' and 'swipe up to open' messages
        String unlockedByFace = mContext.getString(R.string.keyguard_face_successful_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, unlockedByFace);
        String swipeUpToOpen = mContext.getString(R.string.keyguard_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP, swipeUpToOpen);
    }

    @Test
    public void faceOnly_faceSuccess_showsFaceUnlockedSwipeToOpen() {
        // GIVEN bouncer isn't showing, can skip bouncer, no udfps supported
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(false);
        createController();
        mController.setVisible(true);

        // WHEN face auth is successful
        when(mKeyguardUpdateMonitor.getIsFaceAuthenticated()).thenReturn(true);
        mController.getKeyguardCallback().onBiometricAuthenticated(0,
                BiometricSourceType.FACE, false);

        // THEN show 'face unlocked' and 'swipe up to open' messages
        String unlockedByFace = mContext.getString(R.string.keyguard_face_successful_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, unlockedByFace);
        String swipeUpToOpen = mContext.getString(R.string.keyguard_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP, swipeUpToOpen);
    }

    @Test
    public void udfpsOnly_a11yEnabled_showsSwipeToOpen() {
        // GIVEN bouncer isn't showing, can skip bouncer, udfps is supported, a11y is enabled
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(true);
        when(mAccessibilityManager.isEnabled()).thenReturn(true);
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(true);
        createController();
        mController.setVisible(true);

        // WHEN showActionToUnlock
        mController.showActionToUnlock();

        // THEN show 'swipe up to open' message
        String swipeToOpen = mContext.getString(R.string.keyguard_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, swipeToOpen);
    }

    @Test
    public void udfpsOnly_showsPressToOpen() {
        // GIVEN bouncer isn't showing, udfps is supported, a11y is NOT enabled, can skip bouncer
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(true);
        when(mAccessibilityManager.isEnabled()).thenReturn(false);
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(false);
        createController();
        mController.setVisible(true);

        // WHEN showActionToUnlock
        mController.showActionToUnlock();

        // THEN show 'press unlock icon to open' message
        String pressToOpen = mContext.getString(R.string.keyguard_unlock_press);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, pressToOpen);
    }

    @Test
    public void canSkipBouncer_noSecurity_showSwipeToUnlockHint() {
        // GIVEN bouncer isn't showing, can skip bouncer, no security (udfps isn't supported,
        // face wasn't authenticated)
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsSupported()).thenReturn(false);
        createController();
        mController.setVisible(true);

        // WHEN showActionToUnlock
        mController.showActionToUnlock();

        // THEN show 'swipe up to open' message
        String swipeToOpen = mContext.getString(R.string.keyguard_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, swipeToOpen);
    }

    @Test
    public void cannotSkipBouncer_showSwipeToUnlockHint() {
        // GIVEN bouncer isn't showing and cannot skip bouncer
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(false);
        createController();
        mController.setVisible(true);

        // WHEN showActionToUnlock
        mController.showActionToUnlock();

        // THEN show 'swipe up to open' message
        String swipeToOpen = mContext.getString(R.string.keyguard_unlock);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, swipeToOpen);
    }

    @Test
    public void faceOnAcquired_processFrame() {
        createController();

        // WHEN face sends an acquired message
        final int acquireInfo = 1;
        mKeyguardUpdateMonitorCallback.onBiometricAcquired(BiometricSourceType.FACE, acquireInfo);

        // THEN face help message deferral should process the acquired frame
        verify(mFaceHelpMessageDeferral).processFrame(acquireInfo);
    }

    @Test
    public void fingerprintOnAcquired_noProcessFrame() {
        createController();

        // WHEN fingerprint sends an acquired message
        mKeyguardUpdateMonitorCallback.onBiometricAcquired(BiometricSourceType.FINGERPRINT, 1);

        // THEN face help message deferral should NOT process any acquired frames
        verify(mFaceHelpMessageDeferral, never()).processFrame(anyInt());
    }

    @Test
    public void onBiometricHelp_fingerprint_faceHelpMessageDeferralDoesNothing() {
        createController();

        // WHEN fingerprint sends an onBiometricHelp
        mKeyguardUpdateMonitorCallback.onBiometricHelp(
                1,
                "placeholder",
                BiometricSourceType.FINGERPRINT);

        // THEN face help message deferral is NOT: reset, updated, or checked for shouldDefer
        verify(mFaceHelpMessageDeferral, never()).reset();
        verify(mFaceHelpMessageDeferral, never()).updateMessage(anyInt(), anyString());
        verify(mFaceHelpMessageDeferral, never()).shouldDefer(anyInt());
    }

    @Test
    public void onBiometricFailed_resetFaceHelpMessageDeferral() {
        createController();

        // WHEN face sends an onBiometricAuthFailed
        mKeyguardUpdateMonitorCallback.onBiometricAuthFailed(BiometricSourceType.FACE);

        // THEN face help message deferral is reset
        verify(mFaceHelpMessageDeferral).reset();
    }

    @Test
    public void onBiometricError_resetFaceHelpMessageDeferral() {
        createController();

        // WHEN face has an error
        mKeyguardUpdateMonitorCallback.onBiometricError(4, "string",
                BiometricSourceType.FACE);

        // THEN face help message deferral is reset
        verify(mFaceHelpMessageDeferral).reset();
    }

    @Test
    public void onBiometricHelp_faceAcquiredInfo_faceHelpMessageDeferral() {
        createController();

        // WHEN face sends an onBiometricHelp BIOMETRIC_HELP_FACE_NOT_RECOGNIZED
        final int msgId = 1;
        final String helpString = "test";
        mKeyguardUpdateMonitorCallback.onBiometricHelp(
                msgId,
                "test",
                BiometricSourceType.FACE);

        // THEN face help message deferral is NOT reset and message IS updated
        verify(mFaceHelpMessageDeferral, never()).reset();
        verify(mFaceHelpMessageDeferral).updateMessage(msgId, helpString);
    }


    @Test
    public void onBiometricError_faceLockedOutFirstTime_showsThePassedInMessage() {
        createController();
        onFaceLockoutError("first lockout");

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE, "first lockout");
    }

    @Test
    public void onBiometricError_faceLockedOutFirstTimeAndFpAllowed_showsTheFpFollowupMessage() {
        createController();
        fingerprintUnlockIsPossibleAndAllowed();
        onFaceLockoutError("first lockout");

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_suggest_fingerprint));
    }

    @Test
    public void onBiometricError_faceLockedOutFirstTimeAndFpNotAllowed_showsDefaultFollowup() {
        createController();
        fingerprintUnlockIsNotPossible();
        onFaceLockoutError("first lockout");

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void onBiometricError_faceLockedOutSecondTimeInSession_showsUnavailableMessage() {
        createController();
        onFaceLockoutError("first lockout");
        clearInvocations(mRotateTextViewController);

        onFaceLockoutError("second lockout");

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE,
                mContext.getString(R.string.keyguard_face_unlock_unavailable));
    }

    @Test
    public void onBiometricError_faceLockedOutSecondTimeOnBouncer_showsUnavailableMessage() {
        createController();
        onFaceLockoutError("first lockout");
        clearInvocations(mRotateTextViewController);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);

        onFaceLockoutError("second lockout");

        verify(mStatusBarKeyguardViewManager)
                .setKeyguardMessage(
                        eq(mContext.getString(R.string.keyguard_face_unlock_unavailable)),
                        any(),
                        any()
                );
    }

    @Test
    public void onBiometricError_faceLockedOutSecondTimeButUdfpsActive_showsNoMessage() {
        createController();
        onFaceLockoutError("first lockout");
        clearInvocations(mRotateTextViewController);

        when(mAuthController.isUdfpsFingerDown()).thenReturn(true);
        onFaceLockoutError("second lockout");

        verifyNoMoreInteractions(mRotateTextViewController);
    }

    @Test
    public void onBiometricError_faceLockedOutAgainAndFpAllowed_showsTheFpFollowupMessage() {
        createController();
        fingerprintUnlockIsPossibleAndAllowed();
        onFaceLockoutError("first lockout");
        clearInvocations(mRotateTextViewController);

        onFaceLockoutError("second lockout");

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_suggest_fingerprint));
    }

    @Test
    public void onBiometricError_faceLockedOutAgainAndFpNotAllowed_showsDefaultFollowup() {
        createController();
        fingerprintUnlockIsNotPossible();
        onFaceLockoutError("first lockout");
        clearInvocations(mRotateTextViewController);

        onFaceLockoutError("second lockout");

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void onBiometricError_whenFaceLockoutReset_onLockOutError_showsPassedInMessage() {
        createController();
        onFaceLockoutError("first lockout");
        clearInvocations(mRotateTextViewController);
        when(mKeyguardUpdateMonitor.isFaceLockedOut()).thenReturn(false);
        mKeyguardUpdateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FACE);

        onFaceLockoutError("second lockout");

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE, "second lockout");
    }

    @Test
    public void onFpLockoutStateChanged_whenFpIsLockedOut_showsPersistentMessage() {
        createController();
        mController.setVisible(true);
        when(mKeyguardUpdateMonitor.isFingerprintLockedOut()).thenReturn(true);

        mKeyguardUpdateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FINGERPRINT);

        verifyIndicationShown(INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void onFpLockoutStateChanged_whenFpIsNotLockedOut_showsPersistentMessage() {
        createController();
        mController.setVisible(true);
        clearInvocations(mRotateTextViewController);
        when(mKeyguardUpdateMonitor.isFingerprintLockedOut()).thenReturn(false);

        mKeyguardUpdateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FINGERPRINT);

        verifyHideIndication(INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE);
    }

    @Test
    public void onVisibilityChange_showsPersistentMessage_ifFpIsLockedOut() {
        createController();
        mController.setVisible(false);
        when(mKeyguardUpdateMonitor.isFingerprintLockedOut()).thenReturn(true);
        mKeyguardUpdateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FINGERPRINT);
        clearInvocations(mRotateTextViewController);

        mController.setVisible(true);

        verifyIndicationShown(INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void onBiometricError_whenFaceIsLocked_onMultipleLockOutErrors_showUnavailableMessage() {
        createController();
        onFaceLockoutError("first lockout");
        clearInvocations(mRotateTextViewController);
        when(mKeyguardUpdateMonitor.isFaceLockedOut()).thenReturn(true);
        mKeyguardUpdateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FACE);

        onFaceLockoutError("second lockout");

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE,
                mContext.getString(R.string.keyguard_face_unlock_unavailable));
    }

    @Test
    public void onBiometricError_screenIsTurningOn_faceLockedOutFpIsNotAvailable_showsMessage() {
        createController();
        screenIsTurningOn();
        fingerprintUnlockIsNotPossible();

        onFaceLockoutError("lockout error");
        verifyNoMoreInteractions(mRotateTextViewController);

        mScreenObserver.onScreenTurnedOn();

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE,
                "lockout error");
        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_unlock));
    }

    @Test
    public void onBiometricError_screenIsTurningOn_faceLockedOutFpIsAvailable_showsMessage() {
        createController();
        screenIsTurningOn();
        fingerprintUnlockIsPossibleAndAllowed();

        onFaceLockoutError("lockout error");
        verifyNoMoreInteractions(mRotateTextViewController);

        mScreenObserver.onScreenTurnedOn();

        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE,
                "lockout error");
        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                mContext.getString(R.string.keyguard_suggest_fingerprint));
    }

    @Test
    public void faceErrorMessageDroppedBecauseFingerprintMessageShowing() {
        createController();
        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricHelp(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                "fp not recognized", BiometricSourceType.FINGERPRINT);
        clearInvocations(mRotateTextViewController);

        onFaceLockoutError("lockout");
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);
    }

    @Test
    public void faceUnlockedMessageShowsEvenWhenFingerprintMessageShowing() {
        createController();
        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricHelp(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                "fp not recognized", BiometricSourceType.FINGERPRINT);
        clearInvocations(mRotateTextViewController);

        when(mKeyguardUpdateMonitor.getIsFaceAuthenticated()).thenReturn(true);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser()))
                .thenReturn(true);
        mController.getKeyguardCallback().onBiometricAuthenticated(0,
                BiometricSourceType.FACE, false);
        verifyIndicationMessage(
                INDICATION_TYPE_BIOMETRIC_MESSAGE,
                mContext.getString(R.string.keyguard_face_successful_unlock));
    }

    @Test
    public void trustGrantedMessageShowsEvenWhenFingerprintMessageShowing() {
        createController();
        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricHelp(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                "fp not recognized", BiometricSourceType.FINGERPRINT);
        clearInvocations(mRotateTextViewController);

        // GIVEN trust is granted
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);

        // WHEN the showTrustGranted method is called
        final String trustGrantedMsg = "testing trust granted message after fp message";
        mController.getKeyguardCallback().onTrustGrantedForCurrentUser(
                false, false, new TrustGrantFlags(0), trustGrantedMsg);

        // THEN verify the trust granted message shows
        verifyIndicationMessage(
                INDICATION_TYPE_TRUST,
                trustGrantedMsg);
    }

    @Test
    public void updateAdaptiveAuthMessage_whenNotLockedByAdaptiveAuth_doesNotShowMsg() {
        // When the device is not locked by adaptive auth
        when(mKeyguardUpdateMonitor.isDeviceLockedByAdaptiveAuth(getCurrentUser()))
                .thenReturn(false);
        createController();
        mController.setVisible(true);

        // Verify that the adaptive auth message does not show
        verifyNoMessage(INDICATION_TYPE_ADAPTIVE_AUTH);
    }

    @Test
    public void updateAdaptiveAuthMessage_whenLockedByAdaptiveAuth_cannotSkipBouncer_showsMsg() {
        // When the device is locked by adaptive auth, and the user cannot skip bouncer
        when(mKeyguardUpdateMonitor.isDeviceLockedByAdaptiveAuth(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser())).thenReturn(false);
        createController();
        mController.setVisible(true);

        // Verify that the adaptive auth message shows
        String message = mContext.getString(R.string.keyguard_indication_after_adaptive_auth_lock);
        verifyIndicationMessage(INDICATION_TYPE_ADAPTIVE_AUTH, message);
    }

    @Test
    public void updateAdaptiveAuthMessage_whenLockedByAdaptiveAuth_canSkipBouncer_doesNotShowMsg() {
        createController();
        mController.setVisible(true);

        // When the device is locked by adaptive auth, but the device unlocked state changes and the
        // user can skip bouncer
        when(mKeyguardUpdateMonitor.isDeviceLockedByAdaptiveAuth(getCurrentUser()))
                .thenReturn(true);
        when(mKeyguardUpdateMonitor.getUserCanSkipBouncer(getCurrentUser())).thenReturn(true);
        mKeyguardStateControllerCallback.onUnlockedChanged();

        // Verify that the adaptive auth message does not show
        verifyNoMessage(INDICATION_TYPE_ADAPTIVE_AUTH);
    }

    private void screenIsTurningOn() {
        when(mScreenLifecycle.getScreenState()).thenReturn(SCREEN_TURNING_ON);
    }

    private void sendUpdateDisclosureBroadcast() {
        mBroadcastReceiver.onReceive(mContext, new Intent());
    }

    private void verifyIndicationMessages(int type, Set<CharSequence> messages) {
        verify(mRotateTextViewController, times(messages.size())).updateIndication(eq(type),
                mKeyguardIndicationCaptor.capture(), anyBoolean());
        List<KeyguardIndication> kis = mKeyguardIndicationCaptor.getAllValues();

        for (KeyguardIndication ki : kis) {
            final CharSequence msg = ki.getMessage();
            assertTrue(messages.contains(msg)); // check message is shown
            messages.remove(msg);
        }
        assertThat(messages.size()).isEqualTo(0); // check that all messages accounted for (removed)
    }

    private void verifyIndicationMessage(int type, String message) {
        verify(mRotateTextViewController).updateIndication(eq(type),
                mKeyguardIndicationCaptor.capture(), anyBoolean());
        assertThat(mKeyguardIndicationCaptor.getValue().getMessage())
                .isEqualTo(message);
    }

    private void verifyHideIndication(int type) {
        if (type == INDICATION_TYPE_TRANSIENT) {
            verify(mRotateTextViewController).hideTransient();
            verify(mRotateTextViewController, never()).showTransient(anyString());
        } else {
            verify(mRotateTextViewController).hideIndication(type);
            verify(mRotateTextViewController, never()).updateIndication(eq(type),
                    anyObject(), anyBoolean());
        }
    }

    private void verifyTransientMessage(String message) {
        verify(mRotateTextViewController).showTransient(eq(message));
    }

    private void fingerprintUnlockIsNotPossible() {
        setupFingerprintUnlockPossible(false);
    }

    private void fingerprintUnlockIsPossibleAndAllowed() {
        setupFingerprintUnlockPossible(true);
        when(mKeyguardUpdateMonitor.isUnlockingWithFingerprintAllowed()).thenReturn(true);
    }

    private void setupFingerprintUnlockPossible(boolean possible) {
        when(mKeyguardUpdateMonitor
                .isUnlockWithFingerprintPossible(getCurrentUser()))
                .thenReturn(possible);
    }

    private int getCurrentUser() {
        return mCurrentUserId;
    }

    private void onFaceLockoutError(String errMsg) {
        mKeyguardUpdateMonitorCallback.onBiometricError(FACE_ERROR_LOCKOUT_PERMANENT,
                errMsg,
                BiometricSourceType.FACE);
    }
}
