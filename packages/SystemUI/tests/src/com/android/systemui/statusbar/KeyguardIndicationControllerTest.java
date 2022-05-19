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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_DEFAULT;
import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;
import static android.content.pm.UserInfo.FLAG_MANAGED_PROFILE;

import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_ALIGNMENT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BATTERY;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_DISCLOSURE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_LOGOUT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_OWNER_INFO;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_RESTING;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_TRANSIENT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_TRUST;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_USER_LOCKED;

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
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyResourcesManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.graphics.Color;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.BatteryManager;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.ViewGroup;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.app.IBatteryStats;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.KeyguardIndication;
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardIndicationControllerTest extends SysuiTestCase {

    private static final String ORGANIZATION_NAME = "organization";

    private static final ComponentName DEVICE_OWNER_COMPONENT = new ComponentName("com.android.foo",
            "bar");

    private static final int TEST_STRING_RES = R.string.keyguard_indication_trust_unlocked;

    private String mKeyguardTryFingerprintMsg;
    private String mDisclosureWithOrganization;
    private String mDisclosureGeneric;
    private String mFinancedDisclosureWithOrganization;

    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private DevicePolicyResourcesManager mDevicePolicyResourcesManager;
    @Mock
    private ViewGroup mIndicationArea;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private KeyguardIndicationTextView mIndicationAreaBottom;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private IBatteryStats mIBatteryStats;
    @Mock
    private DockManager mDockManager;
    @Mock
    private KeyguardIndicationRotateTextViewController mRotateTextViewController;
    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private IActivityManager mIActivityManager;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private ScreenLifecycle mScreenLifecycle;
    @Captor
    private ArgumentCaptor<DockManager.AlignmentStateListener> mAlignmentListener;
    @Captor
    private ArgumentCaptor<StatusBarStateController.StateListener> mStatusBarStateListenerCaptor;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;
    @Captor
    private ArgumentCaptor<KeyguardIndication> mKeyguardIndicationCaptor;
    @Captor
    private ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardUpdateMonitorCallbackCaptor;
    @Captor
    private ArgumentCaptor<KeyguardStateController.Callback> mKeyguardStateControllerCallbackCaptor;
    private KeyguardStateController.Callback mKeyguardStateControllerCallback;
    private KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback;
    private StatusBarStateController.StateListener mStatusBarStateListener;
    private BroadcastReceiver mBroadcastReceiver;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    private TestableLooper mTestableLooper;

    private KeyguardIndicationTextView mTextView; // AOD text

    private KeyguardIndicationController mController;
    private WakeLockFake.Builder mWakeLockBuilder;
    private WakeLockFake mWakeLock;
    private Instrumentation mInstrumentation;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mTestableLooper = TestableLooper.get(this);
        mTextView = new KeyguardIndicationTextView(mContext);
        mTextView.setAnimationsEnabled(false);

        mContext.addMockSystemService(Context.DEVICE_POLICY_SERVICE, mDevicePolicyManager);
        mContext.addMockSystemService(UserManager.class, mUserManager);
        mContext.addMockSystemService(Context.TRUST_SERVICE, mock(TrustManager.class));
        mContext.addMockSystemService(Context.FINGERPRINT_SERVICE, mock(FingerprintManager.class));
        mKeyguardTryFingerprintMsg = mContext.getString(R.string.keyguard_try_fingerprint);
        mDisclosureWithOrganization = mContext.getString(R.string.do_disclosure_with_name,
                ORGANIZATION_NAME);
        mDisclosureGeneric = mContext.getString(R.string.do_disclosure_generic);
        mFinancedDisclosureWithOrganization = mContext.getString(
                R.string.do_financed_disclosure_with_name, ORGANIZATION_NAME);

        when(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mScreenLifecycle.getScreenState()).thenReturn(ScreenLifecycle.SCREEN_ON);
        when(mKeyguardUpdateMonitor.isUserUnlocked(anyInt())).thenReturn(true);

        when(mIndicationArea.findViewById(R.id.keyguard_indication_text_bottom))
                .thenReturn(mIndicationAreaBottom);
        when(mIndicationArea.findViewById(R.id.keyguard_indication_text)).thenReturn(mTextView);

        when(mDevicePolicyManager.getResources()).thenReturn(mDevicePolicyResourcesManager);
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                .thenReturn(DEVICE_OWNER_COMPONENT);
        when(mDevicePolicyManager.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_DEFAULT);
        when(mDevicePolicyResourcesManager.getString(anyString(), any()))
                .thenReturn(mDisclosureGeneric);
        when(mDevicePolicyResourcesManager.getString(anyString(), any(), anyString()))
                .thenReturn(mDisclosureWithOrganization);

        mWakeLock = new WakeLockFake();
        mWakeLockBuilder = new WakeLockFake.Builder(mContext);
        mWakeLockBuilder.setWakeLock(mWakeLock);
    }

    @After
    public void tearDown() throws Exception {
        mTextView.setAnimationsEnabled(true);
        if (mController != null) {
            mController.destroy();
            mController = null;
        }
    }

    private void createController() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mController = new KeyguardIndicationController(
                mContext,
                mTestableLooper.getLooper(),
                mWakeLockBuilder,
                mKeyguardStateController, mStatusBarStateController, mKeyguardUpdateMonitor,
                mDockManager, mBroadcastDispatcher, mDevicePolicyManager, mIBatteryStats,
                mUserManager, mExecutor, mExecutor,  mFalsingManager, mLockPatternUtils,
                mScreenLifecycle, mIActivityManager, mKeyguardBypassController);
        mController.init();
        mController.setIndicationArea(mIndicationArea);
        verify(mStatusBarStateController).addCallback(mStatusBarStateListenerCaptor.capture());
        mStatusBarStateListener = mStatusBarStateListenerCaptor.getValue();
        verify(mBroadcastDispatcher).registerReceiver(mBroadcastReceiverCaptor.capture(), any());
        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
        mController.mRotateTextViewController = mRotateTextViewController;
        mController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        clearInvocations(mIBatteryStats);

        verify(mKeyguardStateController).addCallback(
                mKeyguardStateControllerCallbackCaptor.capture());
        mKeyguardStateControllerCallback = mKeyguardStateControllerCallbackCaptor.getValue();

        verify(mKeyguardUpdateMonitor).registerCallback(
                mKeyguardUpdateMonitorCallbackCaptor.capture());
        mKeyguardUpdateMonitorCallback = mKeyguardUpdateMonitorCallbackCaptor.getValue();

        mExecutor.runAllReady();
        reset(mRotateTextViewController);
    }

    @Test
    public void createController_addsAlignmentListener() {
        createController();

        verify(mDockManager).addAlignmentStateListener(
                any(DockManager.AlignmentStateListener.class));
    }

    @Test
    public void onAlignmentStateChanged_showsSlowChargingIndication() {
        mInstrumentation.runOnMainSync(() -> {
            createController();
            verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
            mController.setVisible(true);

            mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_POOR);
        });
        mInstrumentation.waitForIdleSync();
        mTestableLooper.processAllMessages();

        verifyIndicationMessage(INDICATION_TYPE_ALIGNMENT,
                mContext.getResources().getString(R.string.dock_alignment_slow_charging));
        assertThat(mKeyguardIndicationCaptor.getValue().getTextColor().getDefaultColor())
                .isEqualTo(mContext.getColor(R.color.misalignment_text_color));
    }

    @Test
    public void onAlignmentStateChanged_showsNotChargingIndication() {
        mInstrumentation.runOnMainSync(() -> {
            createController();
            verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
            mController.setVisible(true);

            mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_TERRIBLE);
        });
        mInstrumentation.waitForIdleSync();
        mTestableLooper.processAllMessages();

        verifyIndicationMessage(INDICATION_TYPE_ALIGNMENT,
                mContext.getResources().getString(R.string.dock_alignment_not_charging));
        assertThat(mKeyguardIndicationCaptor.getValue().getTextColor().getDefaultColor())
                .isEqualTo(mContext.getColor(R.color.misalignment_text_color));
    }

    @Test
    public void onAlignmentStateChanged_whileDozing_showsSlowChargingIndication() {
        mInstrumentation.runOnMainSync(() -> {
            createController();
            verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
            mController.setVisible(true);
            mStatusBarStateListener.onDozingChanged(true);

            mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_POOR);
        });
        mInstrumentation.waitForIdleSync();
        mTestableLooper.processAllMessages();

        assertThat(mTextView.getText()).isEqualTo(
                mContext.getResources().getString(R.string.dock_alignment_slow_charging));
        assertThat(mTextView.getCurrentTextColor()).isEqualTo(
                mContext.getColor(R.color.misalignment_text_color));
    }

    @Test
    public void onAlignmentStateChanged_whileDozing_showsNotChargingIndication() {
        mInstrumentation.runOnMainSync(() -> {
            createController();
            verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
            mController.setVisible(true);
            mStatusBarStateListener.onDozingChanged(true);

            mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_TERRIBLE);
        });
        mInstrumentation.waitForIdleSync();
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
                KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_RECOGNIZED, message,
                BiometricSourceType.FACE);
        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, message);
        reset(mRotateTextViewController);
        mStatusBarStateListener.onDozingChanged(true);

        assertThat(mTextView.getText()).isNotEqualTo(message);
    }

    @Test
    public void transientIndication_visibleWhenDozing_unlessSwipeUp_fromError() {
        createController();
        String message = mContext.getString(R.string.keyguard_unlock);

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(FaceManager.FACE_ERROR_TIMEOUT,
                "A message", BiometricSourceType.FACE);

        verifyIndicationMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE, message);
        mStatusBarStateListener.onDozingChanged(true);

        assertThat(mTextView.getText()).isNotEqualTo(message);
    }

    @Test
    public void transientIndication_visibleWhenDozing_ignoresFingerprintCancellation() {
        createController();

        mController.setVisible(true);
        reset(mRotateTextViewController);
        mController.getKeyguardCallback().onBiometricError(
                FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED, "foo",
                BiometricSourceType.FINGERPRINT);
        mController.getKeyguardCallback().onBiometricError(
                FingerprintManager.FINGERPRINT_ERROR_CANCELED, "bar",
                BiometricSourceType.FINGERPRINT);

        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);
        verifyNoMessage(INDICATION_TYPE_TRANSIENT);
    }

    @Test
    public void transientIndication_swipeUpToRetry() {
        createController();
        String message = mContext.getString(R.string.keyguard_retry);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        when(mKeyguardUpdateMonitor.isFaceEnrolled()).thenReturn(true);

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(FaceManager.FACE_ERROR_TIMEOUT,
                "A message", BiometricSourceType.FACE);

        verify(mStatusBarKeyguardViewManager).showBouncerMessage(eq(message), any());
    }

    @Test
    public void faceErrorTimeout_whenFingerprintEnrolled_doesNotShowMessage() {
        createController();
        when(mKeyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(
                0)).thenReturn(true);
        String message = "A message";

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(
                FaceManager.FACE_ERROR_TIMEOUT, message, BiometricSourceType.FACE);
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE);
    }

    @Test
    public void doNotSendFaceHelpMessages_fingerprintEnrolled() {
        createController();

        // GIVEN fingerprint enrolled
        when(mKeyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(
                0)).thenReturn(true);

        // WHEN help messages received
        final String helpString = "helpString";
        final int[] msgIds = new int[]{
                BiometricFaceConstants.FACE_ACQUIRED_FACE_OBSCURED,
                BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_RIGHT,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_LEFT,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_HIGH,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_LOW,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK
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

        // GIVEN fingerprint NOT enrolled
        when(mKeyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(
                0)).thenReturn(false);

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
                BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT,
                BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK
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
    public void updateMonitor_listenerUpdatesIndication() {
        createController();
        String restingIndication = "Resting indication";
        reset(mKeyguardUpdateMonitor);

        mController.setVisible(true);
        verifyIndicationMessage(INDICATION_TYPE_USER_LOCKED,
                mContext.getString(com.android.internal.R.string.lockscreen_storage_locked));

        reset(mRotateTextViewController);
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        when(mKeyguardUpdateMonitor.isUserUnlocked(anyInt())).thenReturn(true);
        mController.setRestingIndication(restingIndication);
        verifyHideIndication(INDICATION_TYPE_USER_LOCKED);
        verifyIndicationMessage(INDICATION_TYPE_RESTING, restingIndication);

        reset(mRotateTextViewController);
        reset(mKeyguardUpdateMonitor);
        when(mKeyguardUpdateMonitor.isUserUnlocked(anyInt())).thenReturn(true);
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(false);
        mKeyguardStateControllerCallback.onUnlockedChanged();
        verifyIndicationMessage(INDICATION_TYPE_RESTING, restingIndication);
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
    public void onRefreshBatteryInfo_chargingWithOverheat_presentChargingLimited() {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                80 /* level */, BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.BATTERY_HEALTH_OVERHEAT, 0 /* maxChargingWattage */,
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
    public void onRefreshBatteryInfo_pluggedWithOverheat_presentChargingLimited() {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_DISCHARGING,
                80 /* level */, BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.BATTERY_HEALTH_OVERHEAT, 0 /* maxChargingWattage */,
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
    public void onRefreshBatteryInfo_fullChargedWithOverheat_presentChargingLimited() {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                100 /* level */, BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.BATTERY_HEALTH_OVERHEAT, 0 /* maxChargingWattage */,
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
    public void onRefreshBatteryInfo_fullChargedWithoutOverheat_presentCharged() {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                100 /* level */, BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.BATTERY_HEALTH_GOOD, 0 /* maxChargingWattage */,
                true /* present */);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        mController.setVisible(true);

        verifyIndicationMessage(
                INDICATION_TYPE_BATTERY,
                mContext.getString(R.string.keyguard_charged));
    }

    @Test
    public void onRefreshBatteryInfo_dozing_dischargingWithOverheat_presentBatteryPercentage() {
        createController();
        mController.setVisible(true);
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_DISCHARGING,
                90 /* level */, 0 /* plugged */, BatteryManager.BATTERY_HEALTH_OVERHEAT,
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
        mController.getKeyguardCallback().showTrustGrantedMessage(trustGrantedMsg);

        verifyHideIndication(INDICATION_TYPE_TRUST);

        // WHEN trust is granted
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        mKeyguardUpdateMonitorCallback.onTrustChanged(KeyguardUpdateMonitor.getCurrentUser());

        // THEN verify the trust granted message shows
        verifyIndicationMessage(
                INDICATION_TYPE_TRUST,
                trustGrantedMsg);
    }

    @Test
    public void onTrustGrantedMessageDoesShowsOnTrustGranted() {
        createController();
        mController.setVisible(true);

        // GIVEN trust is granted
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);

        // WHEN the showTrustGranted message is called
        final String trustGrantedMsg = "testing trust granted message";
        mController.getKeyguardCallback().showTrustGrantedMessage(trustGrantedMsg);

        // THEN verify the trust granted message shows
        verifyIndicationMessage(
                INDICATION_TYPE_TRUST,
                trustGrantedMsg);
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

    private void verifyNoMessage(int type) {
        if (type == INDICATION_TYPE_TRANSIENT) {
            verify(mRotateTextViewController, never()).showTransient(anyString());
        } else {
            verify(mRotateTextViewController, never()).updateIndication(eq(type),
                    anyObject(), anyBoolean());
        }
    }
}
