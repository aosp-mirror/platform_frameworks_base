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

import static com.android.systemui.classifier.Classifier.LOCK_ICON;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.android.settingslib.Utils;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Controls when to show the LockIcon affordance (lock/unlocked icon or circle) on lock screen.
 *
 * This view will only be shown if the user has UDFPS or FaceAuth enrolled
 */
@StatusBarComponent.StatusBarScope
public class LockIconViewController extends ViewController<LockIconView> implements Dumpable {
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final KeyguardViewController mKeyguardViewController;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final FalsingManager mFalsingManager;
    @NonNull private final AuthController mAuthController;
    @NonNull private final AccessibilityManager mAccessibilityManager;
    @NonNull private final ConfigurationController mConfigurationController;
    @NonNull private final DelayableExecutor mExecutor;
    private boolean mUdfpsEnrolled;

    @NonNull private final Drawable mUnlockIcon;
    @NonNull private final Drawable mLockIcon;
    @NonNull private final CharSequence mUnlockedLabel;
    @NonNull private final CharSequence mLockedLabel;

    private boolean mIsDozing;
    private boolean mIsBouncerShowing;
    private boolean mRunningFPS;
    private boolean mCanDismissLockScreen;
    private boolean mQsExpanded;
    private int mStatusBarState;
    private boolean mIsKeyguardShowing;
    private boolean mUserUnlockedWithBiometric;
    private Runnable mCancelDelayedUpdateVisibilityRunnable;

    private boolean mHasUdfps;
    private float mHeightPixels;
    private float mWidthPixels;
    private float mDensity;
    private int mIndicationBottomPadding;

    private boolean mShowUnlockIcon;
    private boolean mShowLockIcon;

    @Inject
    public LockIconViewController(
            @Nullable LockIconView view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull KeyguardViewController keyguardViewController,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull FalsingManager falsingManager,
            @NonNull AuthController authController,
            @NonNull DumpManager dumpManager,
            @NonNull AccessibilityManager accessibilityManager,
            @NonNull ConfigurationController configurationController,
            @NonNull @Main DelayableExecutor executor
    ) {
        super(view);
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mAuthController = authController;
        mKeyguardViewController = keyguardViewController;
        mKeyguardStateController = keyguardStateController;
        mFalsingManager = falsingManager;
        mAccessibilityManager = accessibilityManager;
        mConfigurationController = configurationController;
        mExecutor = executor;

        final Context context = view.getContext();
        mUnlockIcon = new InsetDrawable(context.getResources().getDrawable(
                com.android.internal.R.drawable.ic_lock_open, context.getTheme()),
                context.getResources().getDimensionPixelSize(
                        com.android.systemui.R.dimen.udfps_unlock_icon_inset));
        mLockIcon = new InsetDrawable(context.getResources().getDrawable(
                com.android.internal.R.drawable.ic_lock, context.getTheme()),
                context.getResources().getDimensionPixelSize(
                        com.android.systemui.R.dimen.udfps_unlock_icon_inset));
        mUnlockedLabel = context.getResources().getString(R.string.accessibility_unlock_button);
        mLockedLabel = context.getResources().getString(R.string.accessibility_lock_icon);
        dumpManager.registerDumpable("LockIconViewController", this);
    }

    @Override
    protected void onInit() {
        mView.setAccessibilityDelegate(mAccessibilityDelegate);
    }

    @Override
    protected void onViewAttached() {
        // we check this here instead of onInit since the FingerprintManager + FaceManager may not
        // have started up yet onInit
        mHasUdfps = mAuthController.getUdfpsSensorLocation() != null;

        updateConfiguration();
        updateKeyguardShowing();
        mUserUnlockedWithBiometric = false;
        mIsBouncerShowing = mKeyguardViewController.isBouncerShowing();
        mIsDozing = mStatusBarStateController.isDozing();
        mRunningFPS = mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
        mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();
        mStatusBarState = mStatusBarStateController.getState();

        updateColors();
        mConfigurationController.addCallback(mConfigurationListener);

        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        mAccessibilityManager.addTouchExplorationStateChangeListener(
                mTouchExplorationStateChangeListener);

        updateVisibility();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mKeyguardStateController.removeCallback(mKeyguardStateCallback);
        mAccessibilityManager.removeTouchExplorationStateChangeListener(
                mTouchExplorationStateChangeListener);

        if (mCancelDelayedUpdateVisibilityRunnable != null) {
            mCancelDelayedUpdateVisibilityRunnable.run();
            mCancelDelayedUpdateVisibilityRunnable = null;
        }
    }

    public float getTop() {
        return mView.getLocationTop();
    }

    private boolean onAffordanceClick() {
        if (mFalsingManager.isFalseTouch(LOCK_ICON)) {
            return false;
        }

        // pre-emptively set to true to hide view
        mIsBouncerShowing = true;
        updateVisibility();
        mKeyguardViewController.showBouncer(/* scrim */ true);
        return true;
    }

    /**
     * Set whether qs is expanded. When QS is expanded, don't show a DisabledUdfps affordance.
     */
    public void setQsExpanded(boolean expanded) {
        mQsExpanded = expanded;
        updateVisibility();
    }

    private void updateVisibility() {
        if (mCancelDelayedUpdateVisibilityRunnable != null) {
            mCancelDelayedUpdateVisibilityRunnable.run();
            mCancelDelayedUpdateVisibilityRunnable = null;
        }

        if (!mIsKeyguardShowing) {
            mView.setVisibility(View.INVISIBLE);
            return;
        }

        mShowLockIcon = !mCanDismissLockScreen && !mUserUnlockedWithBiometric && isLockScreen()
            && (!mUdfpsEnrolled || !mRunningFPS);
        mShowUnlockIcon = mCanDismissLockScreen && isLockScreen();

        updateClickListener();
        final CharSequence prevContentDescription = mView.getContentDescription();
        if (mShowLockIcon) {
            mView.setImageDrawable(mLockIcon);
            mView.setVisibility(View.VISIBLE);
            mView.setContentDescription(mLockedLabel);
        } else if (mShowUnlockIcon) {
            mView.setImageDrawable(mUnlockIcon);
            mView.setVisibility(View.VISIBLE);
            mView.setContentDescription(mUnlockedLabel);
        } else {
            mView.setVisibility(View.INVISIBLE);
            mView.setContentDescription(null);
        }
        if (!Objects.equals(prevContentDescription, mView.getContentDescription())
                && mView.getContentDescription() != null) {
            mView.announceForAccessibility(mView.getContentDescription());
        }
    }

    private final View.AccessibilityDelegate mAccessibilityDelegate =
            new View.AccessibilityDelegate() {
        private final AccessibilityNodeInfo.AccessibilityAction mAccessibilityAuthenticateHint =
                new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        getResources().getString(R.string.accessibility_authenticate_hint));
        private final AccessibilityNodeInfo.AccessibilityAction mAccessibilityEnterHint =
                new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        getResources().getString(R.string.accessibility_enter_hint));
        public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(v, info);
            if (mShowLockIcon) {
                info.addAction(mAccessibilityAuthenticateHint);
            } else if (mShowUnlockIcon) {
                info.addAction(mAccessibilityEnterHint);
            }
        }
    };

    private boolean isLockScreen() {
        return !mIsDozing
                && !mIsBouncerShowing
                && !mQsExpanded
                && mStatusBarState == StatusBarState.KEYGUARD;
    }

    private void updateClickListener() {
        if (mView != null) {
            if (mUdfpsEnrolled || mShowUnlockIcon) {
                mView.setOnClickListener(v -> onAffordanceClick());
                if (mAccessibilityManager.isTouchExplorationEnabled()) {
                    mView.setOnLongClickListener(null);
                    mView.setLongClickable(false);
                } else {
                    mView.setOnLongClickListener(v -> onAffordanceClick());
                }
            } else {
                mView.setOnClickListener(null);
                mView.setOnLongClickListener(null);
            }

        }
    }

    private void updateKeyguardShowing() {
        mIsKeyguardShowing = mKeyguardStateController.isShowing()
                && !mKeyguardStateController.isKeyguardGoingAway();
    }

    private void updateColors() {
        final int color = Utils.getColorAttrDefaultColor(mView.getContext(),
                R.attr.wallpaperTextColorAccent);
        mUnlockIcon.setTint(color);
        mLockIcon.setTint(color);
    }

    private void updateConfiguration() {
        final DisplayMetrics metrics = mView.getContext().getResources().getDisplayMetrics();
        mWidthPixels = metrics.widthPixels;
        mHeightPixels = metrics.heightPixels;
        mDensity = metrics.density;
        mIndicationBottomPadding = mView.getContext().getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_bottom_padding);
        updateLockIconLocation();
    }

    private void updateLockIconLocation() {
        if (mHasUdfps) {
            FingerprintSensorPropertiesInternal props = mAuthController.getUdfpsProps().get(0);
            mView.setLocation(new PointF(props.sensorLocationX, props.sensorLocationY),
                    props.sensorRadius);
        } else {
            final float distAboveKgBottomArea = 12 * mDensity;
            final float radius = 36 * mDensity;
            mView.setLocation(
                    new PointF(mWidthPixels / 2,
                        mHeightPixels - mIndicationBottomPadding - distAboveKgBottomArea - radius),
                    (int) radius);
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mUdfpsEnrolled: " + mUdfpsEnrolled);
        pw.println("mIsKeyguardShowing: " + mIsKeyguardShowing);
        pw.println(" mShowUnlockIcon: " + mShowUnlockIcon);
        pw.println(" mShowLockIcon: " + mShowLockIcon);
        pw.println("  mIsDozing: " + mIsDozing);
        pw.println("  mIsBouncerShowing: " + mIsBouncerShowing);
        pw.println("  mUserUnlockedWithBiometric: " + mUserUnlockedWithBiometric);
        pw.println("  mRunningFPS: " + mRunningFPS);
        pw.println("  mCanDismissLockScreen: " + mCanDismissLockScreen);
        pw.println("  mStatusBarState: " + StatusBarState.toShortString(mStatusBarState));
        pw.println("  mQsExpanded: " + mQsExpanded);

        if (mView != null) {
            mView.dump(fd, pw, args);
        }
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
                public void onKeyguardVisibilityChanged(boolean showing) {
                    // reset mIsBouncerShowing state in case it was preemptively set
                    // onAffordanceClick
                    mIsBouncerShowing = mKeyguardViewController.isBouncerShowing();
                    updateVisibility();
                }

                @Override
                public void onKeyguardBouncerChanged(boolean bouncer) {
                    mIsBouncerShowing = bouncer;
                    updateVisibility();
                }

                @Override
                public void onBiometricRunningStateChanged(boolean running,
                        BiometricSourceType biometricSourceType) {
                    mUserUnlockedWithBiometric =
                            mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(
                                    KeyguardUpdateMonitor.getCurrentUser());

                    if (biometricSourceType == FINGERPRINT) {
                        mRunningFPS = running;
                        if (!mRunningFPS) {
                            if (mCancelDelayedUpdateVisibilityRunnable != null) {
                                mCancelDelayedUpdateVisibilityRunnable.run();
                            }

                            // For some devices, auth is cancelled immediately on screen off but
                            // before dozing state is set. We want to avoid briefly showing the
                            // button in this case, so we delay updating the visibility by 50ms.
                            mCancelDelayedUpdateVisibilityRunnable =
                                    mExecutor.executeDelayed(() -> updateVisibility(), 50);
                        } else {
                            updateVisibility();
                        }
                    }
                }
            };

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();
            updateKeyguardShowing();
            updateVisibility();
        }

        @Override
        public void onKeyguardShowingChanged() {
            updateKeyguardShowing();
            mUdfpsEnrolled = mKeyguardUpdateMonitor.isUdfpsEnrolled();
            updateVisibility();
        }

        @Override
        public void onKeyguardFadingAwayChanged() {
            updateKeyguardShowing();
            updateVisibility();
        }
    };

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onUiModeChanged() {
            updateColors();
        }

        @Override
        public void onThemeChanged() {
            updateColors();
        }

        @Override
        public void onOverlayChanged() {
            updateColors();
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            updateConfiguration();
        }
    };

    private final AccessibilityManager.TouchExplorationStateChangeListener
            mTouchExplorationStateChangeListener = enabled -> updateClickListener();

    /**
     * Set the alpha of this view.
     */
    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
    }
}
