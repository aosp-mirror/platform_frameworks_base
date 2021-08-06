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


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;

import androidx.test.filters.SmallTest;

import com.android.keyguard.CarrierTextController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.UserInfoController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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

    private KeyguardStatusBarView mKeyguardStatusBarView;
    private KeyguardStatusBarViewController mController;

    @Before
    public void setup() throws Exception {
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
                mBatteryMeterViewController
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
}
