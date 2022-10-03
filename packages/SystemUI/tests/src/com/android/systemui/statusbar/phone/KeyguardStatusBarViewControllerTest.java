/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;


import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_SYSTEM_INFO;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.keyguard.CarrierTextController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserInfoTracker;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherController;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherFeatureController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardStatusBarViewControllerTest extends SysuiTestCase {
    @Mock
    private CarrierTextController mCarrierTextController;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private SystemStatusAnimationScheduler mAnimationScheduler;
    @Mock
    private BatteryController mBatteryController;
    @Mock
    private UserInfoController mUserInfoController;
    @Mock
    private StatusBarIconController mStatusBarIconController;
    @Mock
    private StatusBarIconController.TintedIconManager.Factory mIconManagerFactory;
    @Mock
    private StatusBarIconController.TintedIconManager mIconManager;
    @Mock
    private BatteryMeterViewController mBatteryMeterViewController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private BiometricUnlockController mBiometricUnlockController;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private StatusBarContentInsetsProvider mStatusBarContentInsetsProvider;
    @Mock
    private UserManager mUserManager;
    @Captor
    private ArgumentCaptor<ConfigurationListener> mConfigurationListenerCaptor;
    @Captor
    private ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardCallbackCaptor;
    @Mock
    private StatusBarUserSwitcherFeatureController mStatusBarUserSwitcherFeatureController;
    @Mock
    private StatusBarUserSwitcherController mStatusBarUserSwitcherController;
    @Mock
    private StatusBarUserInfoTracker mStatusBarUserInfoTracker;
    @Mock private SecureSettings mSecureSettings;
    @Mock private CommandQueue mCommandQueue;

    private TestNotificationPanelViewStateProvider mNotificationPanelViewStateProvider;
    private KeyguardStatusBarView mKeyguardStatusBarView;
    private KeyguardStatusBarViewController mController;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() throws Exception {
        mNotificationPanelViewStateProvider = new TestNotificationPanelViewStateProvider();

        MockitoAnnotations.initMocks(this);

        when(mIconManagerFactory.create(any(), any())).thenReturn(mIconManager);

        allowTestableLooperAsMainThread();
        TestableLooper.get(this).runWithLooper(() -> {
            mKeyguardStatusBarView =
                    spy((KeyguardStatusBarView) LayoutInflater.from(mContext)
                            .inflate(R.layout.keyguard_status_bar, null));
            when(mKeyguardStatusBarView.getDisplay()).thenReturn(mContext.getDisplay());
        });

        mController = createController();
    }

    private KeyguardStatusBarViewController createController() {
        return new KeyguardStatusBarViewController(
                mKeyguardStatusBarView,
                mCarrierTextController,
                mConfigurationController,
                mAnimationScheduler,
                mBatteryController,
                mUserInfoController,
                mStatusBarIconController,
                mIconManagerFactory,
                mBatteryMeterViewController,
                mNotificationPanelViewStateProvider,
                mKeyguardStateController,
                mKeyguardBypassController,
                mKeyguardUpdateMonitor,
                mBiometricUnlockController,
                mStatusBarStateController,
                mStatusBarContentInsetsProvider,
                mUserManager,
                mStatusBarUserSwitcherFeatureController,
                mStatusBarUserSwitcherController,
                mStatusBarUserInfoTracker,
                mSecureSettings,
                mCommandQueue,
                mFakeExecutor
        );
    }

    @Test
    public void onViewAttached_callbacksRegistered() {
        mController.onViewAttached();

        verify(mConfigurationController).addCallback(any());
        verify(mAnimationScheduler).addCallback(any());
        verify(mUserInfoController).addCallback(any());
        verify(mCommandQueue).addCallback(any());
        verify(mStatusBarIconController).addIconGroup(any());
        verify(mUserManager).isUserSwitcherEnabled(anyBoolean());
    }

    @Test
    public void onConfigurationChanged_updatesUserSwitcherVisibility() {
        mController.onViewAttached();
        verify(mConfigurationController).addCallback(mConfigurationListenerCaptor.capture());
        clearInvocations(mUserManager);
        clearInvocations(mKeyguardStatusBarView);

        mConfigurationListenerCaptor.getValue().onConfigChanged(null);
        verify(mUserManager).isUserSwitcherEnabled(anyBoolean());
        verify(mKeyguardStatusBarView).setUserSwitcherEnabled(anyBoolean());
    }

    @Test
    public void onKeyguardVisibilityChanged_updatesUserSwitcherVisibility() {
        mController.onViewAttached();
        verify(mKeyguardUpdateMonitor).registerCallback(mKeyguardCallbackCaptor.capture());
        clearInvocations(mUserManager);
        clearInvocations(mKeyguardStatusBarView);

        mKeyguardCallbackCaptor.getValue().onKeyguardVisibilityChanged(true);
        verify(mUserManager).isUserSwitcherEnabled(anyBoolean());
        verify(mKeyguardStatusBarView).setUserSwitcherEnabled(anyBoolean());
    }

    @Test
    public void onViewDetached_callbacksUnregistered() {
        // Set everything up first.
        mController.onViewAttached();

        mController.onViewDetached();

        verify(mConfigurationController).removeCallback(any());
        verify(mAnimationScheduler).removeCallback(any());
        verify(mUserInfoController).removeCallback(any());
        verify(mCommandQueue).removeCallback(any());
        verify(mStatusBarIconController).removeIconGroup(any());
    }

    @Test
    public void setBatteryListening_true_callbackAdded() {
        mController.setBatteryListening(true);

        verify(mBatteryController).addCallback(any());
    }

    @Test
    public void setBatteryListening_false_callbackRemoved() {
        // First set to true so that we know setting to false is a change in state.
        mController.setBatteryListening(true);

        mController.setBatteryListening(false);

        verify(mBatteryController).removeCallback(any());
    }

    @Test
    public void setBatteryListening_trueThenTrue_callbackAddedOnce() {
        mController.setBatteryListening(true);
        mController.setBatteryListening(true);

        verify(mBatteryController).addCallback(any());
    }

    @Test
    public void updateTopClipping_viewClippingUpdated() {
        int viewTop = 20;
        mKeyguardStatusBarView.setTop(viewTop);
        int notificationPanelTop = 30;

        mController.updateTopClipping(notificationPanelTop);

        assertThat(mKeyguardStatusBarView.getClipBounds().top).isEqualTo(
                notificationPanelTop - viewTop);
    }

    @Test
    public void setNotTopClipping_viewClippingUpdatedToZero() {
        // Start out with some amount of top clipping.
        mController.updateTopClipping(50);
        assertThat(mKeyguardStatusBarView.getClipBounds().top).isGreaterThan(0);

        mController.setNoTopClipping();

        assertThat(mKeyguardStatusBarView.getClipBounds().top).isEqualTo(0);
    }

    @Test
    public void updateViewState_alphaAndVisibilityGiven_viewUpdated() {
        // Verify the initial values so we know the method triggers changes.
        assertThat(mKeyguardStatusBarView.getAlpha()).isEqualTo(1f);
        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.VISIBLE);

        float newAlpha = 0.5f;
        int newVisibility = View.INVISIBLE;
        mController.updateViewState(newAlpha, newVisibility);

        assertThat(mKeyguardStatusBarView.getAlpha()).isEqualTo(newAlpha);
        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(newVisibility);
    }

    @Test
    public void updateViewState_paramVisibleButIsDisabled_viewIsInvisible() {
        mController.onViewAttached();
        setDisableSystemIcons(true);

        mController.updateViewState(1f, View.VISIBLE);

        // Since we're disabled, we stay invisible
        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateViewState_notKeyguardState_nothingUpdated() {
        mController.onViewAttached();
        updateStateToNotKeyguard();

        float oldAlpha = mKeyguardStatusBarView.getAlpha();

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getAlpha()).isEqualTo(oldAlpha);
    }

    @Test
    public void updateViewState_bypassEnabledAndShouldListenForFace_viewHidden() {
        mController.onViewAttached();
        updateStateToKeyguard();
        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.VISIBLE);

        when(mKeyguardUpdateMonitor.shouldListenForFace()).thenReturn(true);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        onFinishedGoingToSleep();

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateViewState_bypassNotEnabled_viewShown() {
        mController.onViewAttached();
        updateStateToKeyguard();

        when(mKeyguardUpdateMonitor.shouldListenForFace()).thenReturn(true);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(false);
        onFinishedGoingToSleep();

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateViewState_shouldNotListenForFace_viewShown() {
        mController.onViewAttached();
        updateStateToKeyguard();

        when(mKeyguardUpdateMonitor.shouldListenForFace()).thenReturn(false);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        onFinishedGoingToSleep();

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateViewState_panelExpandedHeightZero_viewHidden() {
        mController.onViewAttached();
        updateStateToKeyguard();

        mNotificationPanelViewStateProvider.setPanelViewExpandedHeight(0);

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateViewState_dragProgressOne_viewHidden() {
        mController.onViewAttached();
        updateStateToKeyguard();

        mNotificationPanelViewStateProvider.setLockscreenShadeDragProgress(1f);

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateViewState_disableSystemInfoFalse_viewShown() {
        mController.onViewAttached();
        updateStateToKeyguard();
        setDisableSystemInfo(false);

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateViewState_disableSystemInfoTrue_viewHidden() {
        mController.onViewAttached();
        updateStateToKeyguard();
        setDisableSystemInfo(true);

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateViewState_disableSystemIconsFalse_viewShown() {
        mController.onViewAttached();
        updateStateToKeyguard();
        setDisableSystemIcons(false);

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateViewState_disableSystemIconsTrue_viewHidden() {
        mController.onViewAttached();
        updateStateToKeyguard();
        setDisableSystemIcons(true);

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void setAlpha_explicitAlpha_setsExplicitAlpha() {
        mController.onViewAttached();
        updateStateToKeyguard();

        mController.setAlpha(0.5f);

        assertThat(mKeyguardStatusBarView.getAlpha()).isEqualTo(0.5f);
    }

    @Test
    public void setAlpha_explicitAlpha_thenMinusOneAlpha_setsAlphaBasedOnDefaultCriteria() {
        mController.onViewAttached();
        updateStateToKeyguard();

        mController.setAlpha(0.5f);
        mController.setAlpha(-1f);

        assertThat(mKeyguardStatusBarView.getAlpha()).isGreaterThan(0);
        assertThat(mKeyguardStatusBarView.getAlpha()).isNotEqualTo(0.5f);
    }

    // TODO(b/195442899): Add more tests for #updateViewState once CLs are finalized.

    @Test
    public void updateForHeadsUp_headsUpShouldBeVisible_viewHidden() {
        mController.onViewAttached();
        updateStateToKeyguard();
        mKeyguardStatusBarView.setVisibility(View.VISIBLE);

        mNotificationPanelViewStateProvider.setShouldHeadsUpBeVisible(true);
        mController.updateForHeadsUp(/* animate= */ false);

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void updateForHeadsUp_headsUpShouldNotBeVisible_viewShown() {
        mController.onViewAttached();
        updateStateToKeyguard();

        // Start with the opposite state.
        mNotificationPanelViewStateProvider.setShouldHeadsUpBeVisible(true);
        mController.updateForHeadsUp(/* animate= */ false);

        mNotificationPanelViewStateProvider.setShouldHeadsUpBeVisible(false);
        mController.updateForHeadsUp(/* animate= */ false);

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testNewUserSwitcherDisablesAvatar_newUiOn() {
        // GIVEN the status bar user switcher chip is enabled
        when(mStatusBarUserSwitcherFeatureController.isStatusBarUserSwitcherFeatureEnabled())
                .thenReturn(true);

        // WHEN the controller is created
        mController = createController();

        // THEN keyguard status bar view avatar is disabled
        assertThat(mKeyguardStatusBarView.isKeyguardUserAvatarEnabled()).isFalse();
    }

    @Test
    public void testNewUserSwitcherDisablesAvatar_newUiOff() {
        // GIVEN the status bar user switcher chip is disabled
        when(mStatusBarUserSwitcherFeatureController.isStatusBarUserSwitcherFeatureEnabled())
                .thenReturn(false);

        // WHEN the controller is created
        mController = createController();

        // THEN keyguard status bar view avatar is enabled
        assertThat(mKeyguardStatusBarView.isKeyguardUserAvatarEnabled()).isTrue();
    }

    @Test
    public void testBlockedIcons_obeysSettingForVibrateIcon_settingOff() {
        String str = mContext.getString(com.android.internal.R.string.status_bar_volume);

        // GIVEN the setting is off
        when(mSecureSettings.getInt(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 0))
                .thenReturn(0);

        // WHEN CollapsedStatusBarFragment builds the blocklist
        mController.updateBlockedIcons();

        // THEN status_bar_volume SHOULD be present in the list
        boolean contains = mController.getBlockedIcons().contains(str);
        assertTrue(contains);
    }

    @Test
    public void testBlockedIcons_obeysSettingForVibrateIcon_settingOn() {
        String str = mContext.getString(com.android.internal.R.string.status_bar_volume);

        // GIVEN the setting is ON
        when(mSecureSettings.getIntForUser(Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON, 0,
                UserHandle.USER_CURRENT))
                .thenReturn(1);

        // WHEN CollapsedStatusBarFragment builds the blocklist
        mController.updateBlockedIcons();

        // THEN status_bar_volume SHOULD NOT be present in the list
        boolean contains = mController.getBlockedIcons().contains(str);
        assertFalse(contains);
    }

    private void updateStateToNotKeyguard() {
        updateStatusBarState(SHADE);
    }

    private void updateStateToKeyguard() {
        updateStatusBarState(KEYGUARD);
    }

    private void updateStatusBarState(int state) {
        ArgumentCaptor<StatusBarStateController.StateListener> statusBarStateListenerCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(mStatusBarStateController).addCallback(statusBarStateListenerCaptor.capture());
        StatusBarStateController.StateListener callback = statusBarStateListenerCaptor.getValue();

        callback.onStateChanged(state);
    }

    @Test
    public void animateKeyguardStatusBarIn_isDisabled_viewStillHidden() {
        mController.onViewAttached();
        updateStateToKeyguard();
        setDisableSystemInfo(true);
        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);

        mController.animateKeyguardStatusBarIn();

        // Since we're disabled, we don't actually animate in and stay invisible
        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    /**
     * Calls {@link com.android.keyguard.KeyguardUpdateMonitorCallback#onFinishedGoingToSleep(int)}
     * to ensure values are updated properly.
     */
    private void onFinishedGoingToSleep() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateCallbackCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        verify(mKeyguardUpdateMonitor).registerCallback(keyguardUpdateCallbackCaptor.capture());
        KeyguardUpdateMonitorCallback callback = keyguardUpdateCallbackCaptor.getValue();

        callback.onFinishedGoingToSleep(0);
    }

    private void setDisableSystemInfo(boolean disabled) {
        CommandQueue.Callbacks callback = getCommandQueueCallback();
        int disabled1 = disabled ? DISABLE_SYSTEM_INFO : 0;
        callback.disable(mContext.getDisplayId(), disabled1, 0, false);
    }

    private void setDisableSystemIcons(boolean disabled) {
        CommandQueue.Callbacks callback = getCommandQueueCallback();
        int disabled2 = disabled ? DISABLE2_SYSTEM_ICONS : 0;
        callback.disable(mContext.getDisplayId(), 0, disabled2, false);
    }

    private CommandQueue.Callbacks getCommandQueueCallback() {
        ArgumentCaptor<CommandQueue.Callbacks> captor =
                ArgumentCaptor.forClass(CommandQueue.Callbacks.class);
        verify(mCommandQueue).addCallback(captor.capture());
        return captor.getValue();
    }

    private static class TestNotificationPanelViewStateProvider
            implements NotificationPanelViewController.NotificationPanelViewStateProvider {

        TestNotificationPanelViewStateProvider() {}

        private float mPanelViewExpandedHeight = 100f;
        private boolean mShouldHeadsUpBeVisible = false;
        private float mLockscreenShadeDragProgress = 0f;

        @Override
        public float getPanelViewExpandedHeight() {
            return mPanelViewExpandedHeight;
        }

        @Override
        public boolean shouldHeadsUpBeVisible() {
            return mShouldHeadsUpBeVisible;
        }

        @Override
        public float getLockscreenShadeDragProgress() {
            return mLockscreenShadeDragProgress;
        }

        public void setPanelViewExpandedHeight(float panelViewExpandedHeight) {
            this.mPanelViewExpandedHeight = panelViewExpandedHeight;
        }

        public void setShouldHeadsUpBeVisible(boolean shouldHeadsUpBeVisible) {
            this.mShouldHeadsUpBeVisible = shouldHeadsUpBeVisible;
        }

        public void setLockscreenShadeDragProgress(float lockscreenShadeDragProgress) {
            this.mLockscreenShadeDragProgress = lockscreenShadeDragProgress;
        }
    }
}
