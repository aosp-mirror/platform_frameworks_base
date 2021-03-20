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

import android.hardware.biometrics.BiometricSourceType;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.util.ViewController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Controls when to show the DisabledUdfpsView to unlock the device on the lockscreen.
 * If the device is not authenticated, the bouncer will show.
 *
 * This tap target will only show when:
 * - User has UDFPS enrolled
 * - UDFPS is currently unavailable see {@link KeyguardUpdateMonitor#shouldListenForUdfps}
 */
@SysUISingleton
public class DisabledUdfpsController extends ViewController<DisabledUdfpsView> implements Dumpable {
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final KeyguardViewController mKeyguardViewController;
    @NonNull private final StatusBarStateController mStatusBarStateController;

    private boolean mIsDozing;
    private boolean mIsBouncerShowing;
    private boolean mIsKeyguardShowing;
    private boolean mRunningFPS;
    private boolean mAuthenticated;

    private boolean mShowButton;

    public DisabledUdfpsController(
            @NonNull DisabledUdfpsView view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull AuthController authController,
            @NonNull KeyguardViewController keyguardViewController
    ) {
        super(view);
        mView.setOnClickListener(mOnClickListener);
        mView.setSensorProperties(authController.getUdfpsProps().get(0));

        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardViewController = keyguardViewController;
    }

    @Override
    protected void onViewAttached() {
        mIsBouncerShowing = mKeyguardViewController.isBouncerShowing();
        mIsKeyguardShowing = mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
        mIsDozing = mStatusBarStateController.isDozing();
        mRunningFPS = mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
        mAuthenticated = false;
        updateButtonVisibility();

        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
    }

    /**
     * Call when this controller is no longer needed. This will remove the view from its parent.
     */
    public void destroy() {
        if (mView != null && mView.getParent() != null) {
            ((ViewGroup) mView.getParent()).removeView(mView);
        }
    }

    private void updateButtonVisibility() {
        mShowButton = !mAuthenticated && !mIsDozing && mIsKeyguardShowing
                && !mIsBouncerShowing && !mRunningFPS;
        if (mShowButton) {
            mView.setVisibility(View.VISIBLE);
        } else {
            mView.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("DisabledUdfpsController state:");
        pw.println("  mShowBouncerButton: " + mShowButton);
        pw.println("  mIsDozing: " + mIsDozing);
        pw.println("  mIsKeyguardShowing: " + mIsKeyguardShowing);
        pw.println("  mIsBouncerShowing: " + mIsBouncerShowing);
        pw.println("  mRunningFPS: " + mRunningFPS);
        pw.println("  mAuthenticated: " + mAuthenticated);
    }

    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mKeyguardViewController.showBouncer(/* scrim */ true);
        }
    };

    private StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mIsKeyguardShowing = newState == StatusBarState.KEYGUARD;
                    updateButtonVisibility();
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    mIsDozing = isDozing;
                    updateButtonVisibility();
                }
            };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardBouncerChanged(boolean bouncer) {
                    mIsBouncerShowing = bouncer;
                    updateButtonVisibility();
                }

                @Override
                public void onBiometricRunningStateChanged(boolean running,
                        BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == FINGERPRINT) {
                        mRunningFPS = running;
                    }
                    mAuthenticated &= !mRunningFPS;
                    updateButtonVisibility();
                }

                @Override
                public void onBiometricAuthenticated(int userId,
                        BiometricSourceType biometricSourceType, boolean isStrongBiometric) {
                    mAuthenticated = true;
                    updateButtonVisibility();
                }

                @Override
                public void onUserUnlocked() {
                    mAuthenticated = true;
                    updateButtonVisibility();
                }
            };
}
