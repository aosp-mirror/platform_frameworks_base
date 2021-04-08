/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;

import static com.android.systemui.classifier.Classifier.DISABLED_UDFPS_AFFORDANCE;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.hardware.biometrics.BiometricSourceType;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.android.settingslib.Utils;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Controls when to show the DisabledUdfpsView affordance (unlock icon or circle) on lock screen.
 *
 * This view only exists when:
 * - User has UDFPS enrolled
 * - UDFPS is currently unavailable see {@link KeyguardUpdateMonitor#shouldListenForUdfps}
 */
@SysUISingleton
public class DisabledUdfpsController extends ViewController<DisabledUdfpsView> implements Dumpable {
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final KeyguardViewController mKeyguardViewController;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final FalsingManager mFalsingManager;
    @NonNull private final Drawable mButton;
    @NonNull private final Drawable mUnlockIcon;

    private boolean mIsDozing;
    private boolean mIsBouncerShowing;
    private boolean mRunningFPS;
    private boolean mCanDismissLockScreen;
    private boolean mQsExpanded;
    private int mStatusBarState;

    private boolean mShowButton;
    private boolean mShowUnlockIcon;

    public DisabledUdfpsController(
            @NonNull DisabledUdfpsView view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull AuthController authController,
            @NonNull KeyguardViewController keyguardViewController,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull FalsingManager falsingManager
    ) {
        super(view);
        mView.setOnClickListener(v -> onAffordanceClick());
        mView.setOnLongClickListener(v -> onAffordanceClick());
        mView.setSensorProperties(authController.getUdfpsProps().get(0));

        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardViewController = keyguardViewController;
        mKeyguardStateController = keyguardStateController;
        mFalsingManager = falsingManager;

        final Context context = view.getContext();
        mButton = context.getResources().getDrawable(
                com.android.systemui.R.drawable.circle_white, context.getTheme());
        mUnlockIcon = new InsetDrawable(context.getResources().getDrawable(
                com.android.internal.R.drawable.ic_lock_open, context.getTheme()),
                context.getResources().getDimensionPixelSize(
                        com.android.systemui.R.dimen.udfps_unlock_icon_inset));
    }

    @Override
    protected void onViewAttached() {
        mIsBouncerShowing = mKeyguardViewController.isBouncerShowing();
        mIsDozing = mStatusBarStateController.isDozing();
        mRunningFPS = mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
        mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();
        mStatusBarState = mStatusBarStateController.getState();
        mUnlockIcon.setTint(Utils.getColorAttrDefaultColor(mView.getContext(),
                R.attr.wallpaperTextColorAccent));
        updateVisibility();

        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mKeyguardStateController.removeCallback(mKeyguardStateCallback);
    }

    private boolean onAffordanceClick() {
        if (mFalsingManager.isFalseTouch(DISABLED_UDFPS_AFFORDANCE)) {
            return false;
        }
        mView.setVisibility(View.INVISIBLE);
        mKeyguardViewController.showBouncer(/* scrim */ true);
        return true;
    }

    /**
     * Call when this controller is no longer needed. This will remove the view from its parent.
     */
    public void destroy() {
        if (mView != null && mView.getParent() != null) {
            ((ViewGroup) mView.getParent()).removeView(mView);
        }
    }

    /**
     * Set whether qs is expanded. When QS is expanded, don't show a DisabledUdfps affordance.
     */
    public void setQsExpanded(boolean expanded) {
        mQsExpanded = expanded;
        updateVisibility();
    }

    private void updateVisibility() {
        mShowButton = !mCanDismissLockScreen && !mRunningFPS && isLockScreen();
        mShowUnlockIcon = mCanDismissLockScreen && isLockScreen();

        if (mShowButton) {
            mView.setImageDrawable(mButton);
            mView.setVisibility(View.VISIBLE);
        } else if (mShowUnlockIcon) {
            mView.setImageDrawable(mUnlockIcon);
            mView.setVisibility(View.VISIBLE);
        } else {
            mView.setVisibility(View.INVISIBLE);
        }
    }

    private boolean isLockScreen() {
        return !mIsDozing
                && !mIsBouncerShowing
                && !mQsExpanded
                && mStatusBarState == StatusBarState.KEYGUARD;
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("DisabledUdfpsController state:");
        pw.println("  mShowBouncerButton: " + mShowButton);
        pw.println("  mShowUnlockIcon: " + mShowUnlockIcon);
        pw.println("  mIsDozing: " + mIsDozing);
        pw.println("  mIsBouncerShowing: " + mIsBouncerShowing);
        pw.println("  mRunningFPS: " + mRunningFPS);
        pw.println("  mCanDismissLockScreen: " + mCanDismissLockScreen);
        pw.println("  mStatusBarState: " + StatusBarState.toShortString(mStatusBarState));
        pw.println("  mQsExpanded: " + mQsExpanded);
    }

    private StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    mIsDozing = isDozing;
                    updateVisibility();
                }

                @Override
                public void onStateChanged(int statusBarState) {
                    mStatusBarState = statusBarState;
                    updateVisibility();
                }
            };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardBouncerChanged(boolean bouncer) {
                    mIsBouncerShowing = bouncer;
                    updateVisibility();
                }

                @Override
                public void onBiometricRunningStateChanged(boolean running,
                        BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == FINGERPRINT) {
                        mRunningFPS = running;
                    }

                    updateVisibility();
                }
            };

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();
            updateVisibility();
        }
    };
}
