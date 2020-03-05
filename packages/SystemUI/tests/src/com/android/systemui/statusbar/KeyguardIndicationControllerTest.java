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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.graphics.Color;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.BatteryManager;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserManager;
import android.view.ViewGroup;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IBatteryStats;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.Utils;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyguardIndicationControllerTest extends SysuiTestCase {

    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private ViewGroup mIndicationArea;
    @Mock
    private KeyguardStateController mKeyguardStateController;
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
    @Captor
    private ArgumentCaptor<DockManager.AlignmentStateListener> mAlignmentListener;
    private KeyguardIndicationTextView mTextView;

    private KeyguardIndicationController mController;
    private WakeLockFake.Builder mWakeLockBuilder;
    private WakeLockFake mWakeLock;
    private Instrumentation mInstrumentation;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mTextView = new KeyguardIndicationTextView(mContext);

        mContext.addMockSystemService(Context.DEVICE_POLICY_SERVICE, mDevicePolicyManager);
        mContext.addMockSystemService(UserManager.class, mUserManager);
        mContext.addMockSystemService(Context.TRUST_SERVICE, mock(TrustManager.class));
        mContext.addMockSystemService(Context.FINGERPRINT_SERVICE, mock(FingerprintManager.class));

        when(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mKeyguardUpdateMonitor.isScreenOn()).thenReturn(true);
        when(mKeyguardUpdateMonitor.isUserUnlocked(anyInt())).thenReturn(true);
        when(mIndicationArea.findViewById(R.id.keyguard_indication_text)).thenReturn(mTextView);

        mWakeLock = new WakeLockFake();
        mWakeLockBuilder = new WakeLockFake.Builder(mContext);
        mWakeLockBuilder.setWakeLock(mWakeLock);
    }

    private void createController() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mController = new KeyguardIndicationController(mContext, mWakeLockBuilder,
                mKeyguardStateController, mStatusBarStateController, mKeyguardUpdateMonitor,
                mDockManager, mIBatteryStats);
        mController.setIndicationArea(mIndicationArea);
        mController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        clearInvocations(mIBatteryStats);
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

        assertThat(mTextView.getText()).isEqualTo(
                mContext.getResources().getString(R.string.dock_alignment_slow_charging));
        assertThat(mTextView.getCurrentTextColor()).isEqualTo(
                Utils.getColorError(mContext).getDefaultColor());
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

        assertThat(mTextView.getText()).isEqualTo(
                mContext.getResources().getString(R.string.dock_alignment_not_charging));
        assertThat(mTextView.getCurrentTextColor()).isEqualTo(
                Utils.getColorError(mContext).getDefaultColor());
    }

    @Test
    public void onAlignmentStateChanged_whileDozing_showsSlowChargingIndication() {
        mInstrumentation.runOnMainSync(() -> {
            createController();
            verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
            mController.setVisible(true);
            mController.setDozing(true);

            mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_POOR);
        });
        mInstrumentation.waitForIdleSync();

        assertThat(mTextView.getText()).isEqualTo(
                mContext.getResources().getString(R.string.dock_alignment_slow_charging));
        assertThat(mTextView.getCurrentTextColor()).isEqualTo(
                Utils.getColorError(mContext).getDefaultColor());
    }

    @Test
    public void onAlignmentStateChanged_whileDozing_showsNotChargingIndication() {
        mInstrumentation.runOnMainSync(() -> {
            createController();
            verify(mDockManager).addAlignmentStateListener(mAlignmentListener.capture());
            mController.setVisible(true);
            mController.setDozing(true);

            mAlignmentListener.getValue().onAlignmentStateChanged(DockManager.ALIGN_STATE_TERRIBLE);
        });
        mInstrumentation.waitForIdleSync();

        assertThat(mTextView.getText()).isEqualTo(
                mContext.getResources().getString(R.string.dock_alignment_not_charging));
        assertThat(mTextView.getCurrentTextColor()).isEqualTo(
                Utils.getColorError(mContext).getDefaultColor());
    }

    @Test
    public void transientIndication_holdsWakeLock_whenDozing() {
        createController();

        mController.setDozing(true);
        mController.showTransientIndication("Test");

        assertTrue(mWakeLock.isHeld());
    }

    @Test
    public void transientIndication_releasesWakeLock_afterHiding() {
        createController();

        mController.setDozing(true);
        mController.showTransientIndication("Test");
        mController.hideTransientIndication();

        assertFalse(mWakeLock.isHeld());
    }

    @Test
    public void transientIndication_releasesWakeLock_afterHidingDelayed() throws Throwable {
        mInstrumentation.runOnMainSync(() -> {
            createController();

            mController.setDozing(true);
            mController.showTransientIndication("Test");
            mController.hideTransientIndicationDelayed(0);
        });
        mInstrumentation.waitForIdleSync();

        Boolean[] held = new Boolean[1];
        mInstrumentation.runOnMainSync(() -> {
            held[0] = mWakeLock.isHeld();
        });
        assertFalse("WakeLock expected: RELEASED, was: HELD", held[0]);
    }

    @Test
    public void transientIndication_visibleWhenDozing() {
        createController();

        mController.setVisible(true);
        mController.showTransientIndication("Test");
        mController.setDozing(true);

        assertThat(mTextView.getText()).isEqualTo("Test");
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
        assertThat(mTextView.getText()).isEqualTo(message);
        mController.setDozing(true);

        assertThat(mTextView.getText()).isNotEqualTo(message);
    }

    @Test
    public void transientIndication_visibleWhenDozing_unlessSwipeUp_fromError() {
        createController();
        String message = mContext.getString(R.string.keyguard_unlock);

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(FaceManager.FACE_ERROR_TIMEOUT,
                "A message", BiometricSourceType.FACE);

        assertThat(mTextView.getText()).isEqualTo(message);
        mController.setDozing(true);

        assertThat(mTextView.getText()).isNotEqualTo(message);
    }

    @Test
    public void transientIndication_swipeUpToRetry() {
        createController();
        String message = mContext.getString(R.string.keyguard_retry);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);

        mController.setVisible(true);
        mController.getKeyguardCallback().onBiometricError(FaceManager.FACE_ERROR_TIMEOUT,
                "A message", BiometricSourceType.FACE);

        verify(mStatusBarKeyguardViewManager).showBouncerMessage(eq(message), any());
    }

    @Test
    public void updateMonitor_listenerUpdatesIndication() {
        createController();
        String restingIndication = "Resting indication";
        reset(mKeyguardUpdateMonitor);

        mController.setVisible(true);
        assertThat(mTextView.getText()).isEqualTo(
                mContext.getString(com.android.internal.R.string.lockscreen_storage_locked));

        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        when(mKeyguardUpdateMonitor.isUserUnlocked(anyInt())).thenReturn(true);
        mController.setRestingIndication(restingIndication);
        assertThat(mTextView.getText()).isEqualTo(mController.getTrustGrantedIndication());

        reset(mKeyguardUpdateMonitor);
        when(mKeyguardUpdateMonitor.isUserUnlocked(anyInt())).thenReturn(true);
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(false);
        mController.onUnlockedChanged();
        assertThat(mTextView.getText()).isEqualTo(restingIndication);
    }

    @Test
    public void onRefreshBatteryInfo_computesChargingTime() throws RemoteException {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                80 /* level */, BatteryManager.BATTERY_PLUGGED_WIRELESS, 100 /* health */,
                0 /* maxChargingWattage */);

        mController.getKeyguardCallback().onRefreshBatteryInfo(status);
        verify(mIBatteryStats).computeChargeTimeRemaining();
    }

    @Test
    public void onRefreshBatteryInfo_computesChargingTime_onlyWhenCharging()
            throws RemoteException {
        createController();
        BatteryStatus status = new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                80 /* level */, 0 /* plugged */, 100 /* health */,
                0 /* maxChargingWattage */);

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
        mController.setDozing(true);
        mController.setDozing(false);
        verify(mIBatteryStats, never()).computeChargeTimeRemaining();
    }

    @Test
    public void updateMonitor_listener() {
        createController();
        verify(mKeyguardStateController).addCallback(eq(mController));
        verify(mStatusBarStateController).addCallback(eq(mController));
        verify(mKeyguardUpdateMonitor, times(2)).registerCallback(any());
    }

    @Test
    public void unlockMethodCache_listenerUpdatesPluggedIndication() {
        createController();
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        mController.setPowerPluggedIn(true);
        mController.setVisible(true);
        String powerIndication = mController.computePowerIndication();
        String pluggedIndication = mContext.getString(R.string.keyguard_indication_trust_unlocked);
        pluggedIndication = mContext.getString(
                R.string.keyguard_indication_trust_unlocked_plugged_in,
                pluggedIndication, powerIndication);
        assertThat(mTextView.getText()).isEqualTo(pluggedIndication);
    }
}
