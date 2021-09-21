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
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInProgressOffset;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.media.AudioAttributes;
import android.os.Process;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.MathUtils;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.biometrics.UdfpsController;
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

import com.airbnb.lottie.LottieAnimationView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Controls when to show the LockIcon affordance (lock/unlocked icon or circle) on lock screen.
 *
 * For devices with UDFPS, the lock icon will show at the sensor location. Else, the lock
 * icon will show a set distance from the bottom of the device.
 */
@StatusBarComponent.StatusBarScope
public class LockIconViewController extends ViewController<LockIconView> implements Dumpable {
    private static final float sDefaultDensity =
            (float) DisplayMetrics.DENSITY_DEVICE_STABLE / (float) DisplayMetrics.DENSITY_DEFAULT;
    private static final int sLockIconRadiusPx = (int) (sDefaultDensity * 36);
    private static final float sDistAboveKgBottomAreaPx = sDefaultDensity * 12;
    private static final AudioAttributes VIBRATION_SONIFICATION_ATTRIBUTES =
            new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build();

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

    @NonNull private LottieAnimationView mAodFp;

    @NonNull private final AnimatedVectorDrawable mFpToUnlockIcon;
    @NonNull private final AnimatedVectorDrawable mLockToUnlockIcon;
    @NonNull private final Drawable mLockIcon;
    @NonNull private final Drawable mUnlockIcon;
    @NonNull private CharSequence mUnlockedLabel;
    @NonNull private CharSequence mLockedLabel;
    @Nullable private final Vibrator mVibrator;
    @Nullable private final AuthRippleController mAuthRippleController;

    private boolean mIsDozing;
    private boolean mIsBouncerShowing;
    private boolean mRunningFPS;
    private boolean mCanDismissLockScreen;
    private boolean mQsExpanded;
    private int mStatusBarState;
    private boolean mIsKeyguardShowing;
    private boolean mUserUnlockedWithBiometric;
    private Runnable mCancelDelayedUpdateVisibilityRunnable;
    private Runnable mOnGestureDetectedRunnable;

    private boolean mUdfpsSupported;
    private float mHeightPixels;
    private float mWidthPixels;
    private int mBottomPadding; // in pixels

    private boolean mShowUnlockIcon;
    private boolean mShowLockIcon;

    // for udfps when strong auth is required or unlocked on AOD
    private boolean mShowAODFpIcon;
    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;
    private float mInterpolatedDarkAmount;

    private boolean mDownDetected;
    private boolean mDetectedLongPress;
    private final Rect mSensorTouchLocation = new Rect();

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
            @NonNull @Main DelayableExecutor executor,
            @Nullable Vibrator vibrator,
            @Nullable AuthRippleController authRippleController
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
        mVibrator = vibrator;
        mAuthRippleController = authRippleController;

        final Context context = view.getContext();
        mAodFp = mView.findViewById(R.id.lock_udfps_aod_fp);
        mMaxBurnInOffsetX = context.getResources()
                .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x);
        mMaxBurnInOffsetY = context.getResources()
                .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);

        mUnlockIcon = mView.getContext().getResources().getDrawable(
            R.drawable.ic_unlock,
            mView.getContext().getTheme());
        mLockIcon = mView.getContext().getResources().getDrawable(
                R.anim.lock_to_unlock,
                mView.getContext().getTheme());
        mFpToUnlockIcon = (AnimatedVectorDrawable) mView.getContext().getResources().getDrawable(
                R.anim.fp_to_unlock, mView.getContext().getTheme());
        mLockToUnlockIcon = (AnimatedVectorDrawable) mView.getContext().getResources().getDrawable(
                R.anim.lock_to_unlock,
                mView.getContext().getTheme());
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
        updateIsUdfpsEnrolled();
        updateConfiguration();
        updateKeyguardShowing();
        mUserUnlockedWithBiometric = false;

        mIsBouncerShowing = mKeyguardViewController.isBouncerShowing();
        mIsDozing = mStatusBarStateController.isDozing();
        mInterpolatedDarkAmount = mStatusBarStateController.getDozeAmount();
        mRunningFPS = mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
        mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();
        mStatusBarState = mStatusBarStateController.getState();

        updateColors();
        mConfigurationController.addCallback(mConfigurationListener);

        mAuthController.addCallback(mAuthControllerCallback);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        mDownDetected = false;
        updateBurnInOffsets();
        updateVisibility();
    }

    @Override
    protected void onViewDetached() {
        mAuthController.removeCallback(mAuthControllerCallback);
        mConfigurationController.removeCallback(mConfigurationListener);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mKeyguardStateController.removeCallback(mKeyguardStateCallback);

        if (mCancelDelayedUpdateVisibilityRunnable != null) {
            mCancelDelayedUpdateVisibilityRunnable.run();
            mCancelDelayedUpdateVisibilityRunnable = null;
        }
    }

    public float getTop() {
        return mView.getLocationTop();
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

        if (!mIsKeyguardShowing && !mIsDozing) {
            mView.setVisibility(View.INVISIBLE);
            return;
        }

        boolean wasShowingFpIcon = mUdfpsEnrolled && !mShowUnlockIcon && !mShowLockIcon;
        boolean wasShowingLockIcon = mShowLockIcon;
        boolean wasShowingUnlockIcon = mShowUnlockIcon;
        mShowLockIcon = !mCanDismissLockScreen && !mUserUnlockedWithBiometric && isLockScreen()
            && (!mUdfpsEnrolled || !mRunningFPS);
        mShowUnlockIcon = mCanDismissLockScreen && isLockScreen();
        mShowAODFpIcon = mIsDozing && mUdfpsEnrolled && !mRunningFPS;

        final CharSequence prevContentDescription = mView.getContentDescription();
        if (mShowLockIcon) {
            mView.setImageDrawable(mLockIcon);
            mView.setVisibility(View.VISIBLE);
            mView.setContentDescription(mLockedLabel);
        } else if (mShowUnlockIcon) {
            if (!wasShowingUnlockIcon) {
                if (wasShowingFpIcon) {
                    mView.setImageDrawable(mFpToUnlockIcon);
                    mFpToUnlockIcon.forceAnimationOnUI();
                    mFpToUnlockIcon.start();
                } else if (wasShowingLockIcon) {
                    mView.setImageDrawable(mLockToUnlockIcon);
                    mLockToUnlockIcon.forceAnimationOnUI();
                    mLockToUnlockIcon.start();
                } else {
                    mView.setImageDrawable(mUnlockIcon);
                }
            }
            mView.setVisibility(View.VISIBLE);
            mView.setContentDescription(mUnlockedLabel);
        } else if (mShowAODFpIcon) {
            mView.setImageDrawable(null);
            mView.setContentDescription(null);
            mAodFp.setVisibility(View.VISIBLE);
            mAodFp.setContentDescription(mCanDismissLockScreen ? mUnlockedLabel : mLockedLabel);
            mView.setVisibility(View.VISIBLE);
        } else {
            mView.setVisibility(View.INVISIBLE);
            mView.setContentDescription(null);
        }

        if (!mShowAODFpIcon) {
            mAodFp.setVisibility(View.INVISIBLE);
            mAodFp.setContentDescription(null);
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
            if (isClickable()) {
                if (mShowLockIcon) {
                    info.addAction(mAccessibilityAuthenticateHint);
                } else if (mShowUnlockIcon) {
                    info.addAction(mAccessibilityEnterHint);
                }
            }
        }
    };

    private boolean isLockScreen() {
        return !mIsDozing
                && !mIsBouncerShowing
                && !mQsExpanded
                && mStatusBarState == StatusBarState.KEYGUARD;
    }

    private void updateKeyguardShowing() {
        mIsKeyguardShowing = mKeyguardStateController.isShowing()
                && !mKeyguardStateController.isKeyguardGoingAway();
    }

    private void updateColors() {
        mView.updateColorAndBackgroundVisibility(mUdfpsSupported);
    }

    private void updateConfiguration() {
        final DisplayMetrics metrics = mView.getContext().getResources().getDisplayMetrics();
        mWidthPixels = metrics.widthPixels;
        mHeightPixels = metrics.heightPixels;
        mBottomPadding = mView.getContext().getResources().getDimensionPixelSize(
                R.dimen.lock_icon_margin_bottom);

        mUnlockedLabel = mView.getContext().getResources().getString(
                R.string.accessibility_unlock_button);
        mLockedLabel = mView.getContext()
                .getResources().getString(R.string.accessibility_lock_icon);

        updateLockIconLocation();
    }

    private void updateLockIconLocation() {
        if (mUdfpsSupported) {
            FingerprintSensorPropertiesInternal props = mAuthController.getUdfpsProps().get(0);
            final SensorLocationInternal location = props.getLocation();
            mView.setCenterLocation(new PointF(location.sensorLocationX, location.sensorLocationY),
                    location.sensorRadius);
        } else {
            mView.setCenterLocation(
                    new PointF(mWidthPixels / 2,
                        mHeightPixels - mBottomPadding - sDistAboveKgBottomAreaPx
                            - sLockIconRadiusPx), sLockIconRadiusPx);
        }

        mView.getHitRect(mSensorTouchLocation);
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mUdfpsSupported: " + mUdfpsSupported);
        pw.println("mUdfpsEnrolled: " + mUdfpsEnrolled);
        pw.println("mIsKeyguardShowing: " + mIsKeyguardShowing);
        pw.println(" mShowUnlockIcon: " + mShowUnlockIcon);
        pw.println(" mShowLockIcon: " + mShowLockIcon);
        pw.println(" mShowAODFpIcon: " + mShowAODFpIcon);
        pw.println("  mIsDozing: " + mIsDozing);
        pw.println("  mIsBouncerShowing: " + mIsBouncerShowing);
        pw.println("  mUserUnlockedWithBiometric: " + mUserUnlockedWithBiometric);
        pw.println("  mRunningFPS: " + mRunningFPS);
        pw.println("  mCanDismissLockScreen: " + mCanDismissLockScreen);
        pw.println("  mStatusBarState: " + StatusBarState.toShortString(mStatusBarState));
        pw.println("  mQsExpanded: " + mQsExpanded);
        pw.println("  mInterpolatedDarkAmount: " + mInterpolatedDarkAmount);

        if (mView != null) {
            mView.dump(fd, pw, args);
        }
    }

    /** Every minute, update the aod icon's burn in offset */
    public void dozeTimeTick() {
        updateBurnInOffsets();
    }

    private void updateBurnInOffsets() {
        float offsetX = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                        - mMaxBurnInOffsetX, mInterpolatedDarkAmount);
        float offsetY = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                        - mMaxBurnInOffsetY, mInterpolatedDarkAmount);
        float progress = MathUtils.lerp(0f, getBurnInProgressOffset(), mInterpolatedDarkAmount);

        mAodFp.setTranslationX(offsetX);
        mAodFp.setTranslationY(offsetY);
        mAodFp.setProgress(progress);
        mAodFp.setAlpha(255 * mInterpolatedDarkAmount);
    }

    private void updateIsUdfpsEnrolled() {
        boolean wasUdfpsSupported = mUdfpsSupported;
        boolean wasUdfpsEnrolled = mUdfpsEnrolled;

        mUdfpsSupported = mAuthController.getUdfpsSensorLocation() != null;
        mUdfpsEnrolled = mKeyguardUpdateMonitor.isUdfpsEnrolled();
        if (wasUdfpsSupported != mUdfpsSupported || wasUdfpsEnrolled != mUdfpsEnrolled) {
            updateVisibility();
        }
    }

    private StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    mInterpolatedDarkAmount = eased;
                    updateBurnInOffsets();
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    mIsDozing = isDozing;
                    updateBurnInOffsets();
                    updateIsUdfpsEnrolled();
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
            // Reset values in case biometrics were removed (ie: pin/pattern/password => swipe).
            // If biometrics were removed, local vars mCanDismissLockScreen and
            // mUserUnlockedWithBiometric may not be updated.
            mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();
            updateKeyguardShowing();
            if (mIsKeyguardShowing) {
                mUserUnlockedWithBiometric =
                    mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(
                        KeyguardUpdateMonitor.getCurrentUser());
            }
            updateIsUdfpsEnrolled();
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
            updateColors();
        }
    };

    private final GestureDetector mGestureDetector =
            new GestureDetector(new SimpleOnGestureListener() {
                public boolean onDown(MotionEvent e) {
                    mDetectedLongPress = false;
                    if (!isClickable()) {
                        mDownDetected = false;
                        return false;
                    }

                    // intercept all following touches until we see MotionEvent.ACTION_CANCEL UP or
                    // MotionEvent.ACTION_UP (see #onTouchEvent)
                    if (mVibrator != null && !mDownDetected) {
                        mVibrator.vibrate(
                                Process.myUid(),
                                getContext().getOpPackageName(),
                                UdfpsController.EFFECT_CLICK,
                                "lockIcon-onDown",
                                VIBRATION_SONIFICATION_ATTRIBUTES);
                    }

                    mDownDetected = true;
                    return true;
                }

                public void onLongPress(MotionEvent e) {
                    if (!wasClickableOnDownEvent()) {
                        return;
                    }
                    mDetectedLongPress = true;

                    if (onAffordanceClick() && mVibrator != null) {
                        // only vibrate if the click went through and wasn't intercepted by falsing
                        mVibrator.vibrate(
                                Process.myUid(),
                                getContext().getOpPackageName(),
                                UdfpsController.EFFECT_CLICK,
                                "lockIcon-onLongPress",
                                VIBRATION_SONIFICATION_ATTRIBUTES);
                    }
                }

                public boolean onSingleTapUp(MotionEvent e) {
                    if (!wasClickableOnDownEvent()) {
                        return false;
                    }
                    onAffordanceClick();
                    return true;
                }

                public boolean onFling(MotionEvent e1, MotionEvent e2,
                        float velocityX, float velocityY) {
                    if (!wasClickableOnDownEvent()) {
                        return false;
                    }
                    onAffordanceClick();
                    return true;
                }

                private boolean wasClickableOnDownEvent() {
                    return mDownDetected;
                }

                /**
                 * Whether we tried to launch the affordance.
                 *
                 * If falsing intercepts the click, returns false.
                 */
                private boolean onAffordanceClick() {
                    if (mFalsingManager.isFalseTouch(LOCK_ICON)) {
                        return false;
                    }

                    // pre-emptively set to true to hide view
                    mIsBouncerShowing = true;
                    if (mUdfpsSupported && mShowUnlockIcon && mAuthRippleController != null) {
                        mAuthRippleController.showRipple(FINGERPRINT);
                    }
                    updateVisibility();
                    if (mOnGestureDetectedRunnable != null) {
                        mOnGestureDetectedRunnable.run();
                    }
                    mKeyguardViewController.showBouncer(/* scrim */ true);
                    return true;
                }
            });

    /**
     * Send touch events to this view and handles it if the touch is within this view and we are
     * in a 'clickable' state
     * @return whether to intercept the touch event
     */
    public boolean onTouchEvent(MotionEvent event, Runnable onGestureDetectedRunnable) {
        if (mSensorTouchLocation.contains((int) event.getX(), (int) event.getY())
                && (mView.getVisibility() == View.VISIBLE
                || mAodFp.getVisibility() == View.VISIBLE)) {
            mOnGestureDetectedRunnable = onGestureDetectedRunnable;
            mGestureDetector.onTouchEvent(event);
        }

        // we continue to intercept all following touches until we see MotionEvent.ACTION_CANCEL UP
        // or MotionEvent.ACTION_UP. this is to avoid passing the touch to NPV
        // after the lock icon disappears on device entry
        if (mDownDetected) {
            if (event.getAction() == MotionEvent.ACTION_CANCEL
                    || event.getAction() == MotionEvent.ACTION_UP) {
                mDownDetected = false;
            }
            return true;
        }
        return false;
    }

    private boolean isClickable() {
        return mUdfpsSupported || mShowUnlockIcon;
    }

    /**
     * Set the alpha of this view.
     */
    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
    }

    private final AuthController.Callback mAuthControllerCallback = new AuthController.Callback() {
        @Override
        public void onAllAuthenticatorsRegistered() {
            updateIsUdfpsEnrolled();
            updateConfiguration();
        }
    };
}
