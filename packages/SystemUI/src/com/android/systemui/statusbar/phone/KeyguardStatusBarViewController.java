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

import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.ANIMATING_IN;
import static com.android.systemui.statusbar.events.SystemStatusAnimationSchedulerKt.ANIMATING_OUT;

import android.animation.ValueAnimator;
import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.android.keyguard.CarrierTextController;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.statusbar.events.SystemStatusAnimationCallback;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.util.ViewController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/** View Controller for {@link com.android.systemui.statusbar.phone.KeyguardStatusBarView}. */
public class KeyguardStatusBarViewController extends ViewController<KeyguardStatusBarView> {
    private final CarrierTextController mCarrierTextController;
    private final ConfigurationController mConfigurationController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final BatteryController mBatteryController;
    private final UserInfoController mUserInfoController;
    private final StatusBarIconController mStatusBarIconController;
    private final StatusBarIconController.TintedIconManager.Factory mTintedIconManagerFactory;
    private final BatteryMeterViewController mBatteryMeterViewController;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.loadDimens();
                }

                @Override
                public void onOverlayChanged() {
                    KeyguardStatusBarViewController.this.onThemeChanged();
                    mView.onOverlayChanged();
                }

                @Override
                public void onThemeChanged() {
                    KeyguardStatusBarViewController.this.onThemeChanged();
                }
            };

    private final SystemStatusAnimationCallback mAnimationCallback =
            new SystemStatusAnimationCallback() {
                @Override
                public void onSystemChromeAnimationStart() {
                    mView.onSystemChromeAnimationStart(
                            mAnimationScheduler.getAnimationState() == ANIMATING_OUT);
                }

                @Override
                public void onSystemChromeAnimationEnd() {
                    mView.onSystemChromeAnimationEnd(
                            mAnimationScheduler.getAnimationState() == ANIMATING_IN);
                }

                @Override
                public void onSystemChromeAnimationUpdate(@NonNull ValueAnimator anim) {
                    mView.onSystemChromeAnimationUpdate((float) anim.getAnimatedValue());
                }
            };

    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
                    mView.onBatteryLevelChanged(charging);
                }
            };

    private final UserInfoController.OnUserInfoChangedListener mOnUserInfoChangedListener =
            (name, picture, userAccount) -> mView.onUserInfoChanged(picture);

    private final List<String> mBlockedIcons;

    private boolean mBatteryListening;
    private StatusBarIconController.TintedIconManager mTintedIconManager;

    @Inject
    public KeyguardStatusBarViewController(
            KeyguardStatusBarView view,
            CarrierTextController carrierTextController,
            ConfigurationController configurationController,
            SystemStatusAnimationScheduler animationScheduler,
            BatteryController batteryController,
            UserInfoController userInfoController,
            StatusBarIconController statusBarIconController,
            StatusBarIconController.TintedIconManager.Factory tintedIconManagerFactory,
            BatteryMeterViewController batteryMeterViewController) {
        super(view);
        mCarrierTextController = carrierTextController;
        mConfigurationController = configurationController;
        mAnimationScheduler = animationScheduler;
        mBatteryController = batteryController;
        mUserInfoController = userInfoController;
        mStatusBarIconController = statusBarIconController;
        mTintedIconManagerFactory = tintedIconManagerFactory;
        mBatteryMeterViewController = batteryMeterViewController;

        Resources r = getResources();
        mBlockedIcons = Collections.unmodifiableList(Arrays.asList(
                r.getString(com.android.internal.R.string.status_bar_volume),
                r.getString(com.android.internal.R.string.status_bar_alarm_clock),
                r.getString(com.android.internal.R.string.status_bar_call_strength)));
    }

    @Override
    protected void onInit() {
        super.onInit();
        mCarrierTextController.init();
        mBatteryMeterViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
        mAnimationScheduler.addCallback(mAnimationCallback);
        mUserInfoController.addCallback(mOnUserInfoChangedListener);
        if (mTintedIconManager == null) {
            mTintedIconManager =
                    mTintedIconManagerFactory.create(mView.findViewById(R.id.statusIcons));
            mTintedIconManager.setBlockList(mBlockedIcons);
            mStatusBarIconController.addIconGroup(mTintedIconManager);
        }
        onThemeChanged();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        mAnimationScheduler.removeCallback(mAnimationCallback);
        mUserInfoController.removeCallback(mOnUserInfoChangedListener);
        if (mTintedIconManager != null) {
            mStatusBarIconController.removeIconGroup(mTintedIconManager);
        }
    }

    /** Should be called when the theme changes. */
    public void onThemeChanged() {
        mView.onThemeChanged(mTintedIconManager);
    }

    /** Sets whether this controller should listen to battery updates. */
    public void setBatteryListening(boolean listening) {
        if (listening == mBatteryListening) {
            return;
        }
        mBatteryListening = listening;
        if (mBatteryListening) {
            mBatteryController.addCallback(mBatteryStateChangeCallback);
        } else {
            mBatteryController.removeCallback(mBatteryStateChangeCallback);
        }
    }

    /** */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusBarView:");
        pw.println("  mBatteryListening: " + mBatteryListening);
        mView.dump(fd, pw, args);
    }
}
