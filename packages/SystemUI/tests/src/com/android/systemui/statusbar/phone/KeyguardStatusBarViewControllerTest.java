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


import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
    private FeatureFlags mFeatureFlags;
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

    private TestNotificationPanelViewStateProvider mNotificationPanelViewStateProvider;
    private KeyguardStatusBarView mKeyguardStatusBarView;
    private KeyguardStatusBarViewController mController;

    @Before
    public void setup() throws Exception {
        mNotificationPanelViewStateProvider = new TestNotificationPanelViewStateProvider();

        MockitoAnnotations.initMocks(this);

        allowTestableLooperAsMainThread();
        TestableLooper.get(this).runWithLooper(() -> {
            mKeyguardStatusBarView =
                    (KeyguardStatusBarView) LayoutInflater.from(mContext)
                            .inflate(R.layout.keyguard_status_bar, null);
        });

        mController = new KeyguardStatusBarViewController(
                mKeyguardStatusBarView,
                mCarrierTextController,
                mConfigurationController,
                mAnimationScheduler,
                mBatteryController,
                mUserInfoController,
                mStatusBarIconController,
                new StatusBarIconController.TintedIconManager.Factory(mFeatureFlags),
                mBatteryMeterViewController,
                mNotificationPanelViewStateProvider,
                mKeyguardStateController,
                mKeyguardBypassController,
                mKeyguardUpdateMonitor,
                mBiometricUnlockController,
                mStatusBarStateController,
                mStatusBarContentInsetsProvider
        );
    }

    @Test
    public void onViewAttached_callbacksRegistered() {
        mController.onViewAttached();

        verify(mConfigurationController).addCallback(any());
        verify(mAnimationScheduler).addCallback(any());
        verify(mUserInfoController).addCallback(any());
        verify(mStatusBarIconController).addIconGroup(any());
    }

    @Test
    public void onViewDetached_callbacksUnregistered() {
        // Set everything up first.
        mController.onViewAttached();

        mController.onViewDetached();

        verify(mConfigurationController).removeCallback(any());
        verify(mAnimationScheduler).removeCallback(any());
        verify(mUserInfoController).removeCallback(any());
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
    public void updateViewState_qsExpansionOne_viewHidden() {
        mController.onViewAttached();
        updateStateToKeyguard();

        mNotificationPanelViewStateProvider.setQsExpansionFraction(1f);

        mController.updateViewState();

        assertThat(mKeyguardStatusBarView.getVisibility()).isEqualTo(View.INVISIBLE);
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

    private static class TestNotificationPanelViewStateProvider
            implements NotificationPanelViewController.NotificationPanelViewStateProvider {

        TestNotificationPanelViewStateProvider() {}

        private float mPanelViewExpandedHeight = 100f;
        private float mQsExpansionFraction = 0f;
        private boolean mShouldHeadsUpBeVisible = false;

        @Override
        public float getPanelViewExpandedHeight() {
            return mPanelViewExpandedHeight;
        }

        @Override
        public float getQsExpansionFraction() {
            return mQsExpansionFraction;
        }

        @Override
        public boolean shouldHeadsUpBeVisible() {
            return mShouldHeadsUpBeVisible;
        }

        public void setPanelViewExpandedHeight(float panelViewExpandedHeight) {
            this.mPanelViewExpandedHeight = panelViewExpandedHeight;
        }

        public void setQsExpansionFraction(float qsExpansionFraction) {
            this.mQsExpansionFraction = qsExpansionFraction;
        }

        public void setShouldHeadsUpBeVisible(boolean shouldHeadsUpBeVisible) {
            this.mShouldHeadsUpBeVisible = shouldHeadsUpBeVisible;
        }
    }
}
