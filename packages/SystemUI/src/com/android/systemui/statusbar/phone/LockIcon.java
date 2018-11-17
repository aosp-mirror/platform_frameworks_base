/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.graphics.ColorUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;

/**
 * Manages the different states and animations of the unlock icon.
 */
public class LockIcon extends KeyguardAffordanceView implements OnUserInfoChangedListener {

    private static final int FP_DRAW_OFF_TIMEOUT = 800;

    private static final int STATE_LOCKED = 0;
    private static final int STATE_LOCK_OPEN = 1;
    private static final int STATE_FACE_UNLOCK = 2;
    private static final int STATE_FINGERPRINT = 3;
    private static final int STATE_FINGERPRINT_ERROR = 4;
    private static final boolean HOLLOW_PILL = SystemProperties
            .getBoolean("persist.sysui.hollow_pill", false);

    private int mLastState = 0;
    private boolean mLastDeviceInteractive;
    private boolean mTransientFpError;
    private boolean mDeviceInteractive;
    private boolean mScreenOn;
    private boolean mLastScreenOn;
    private Drawable mUserAvatarIcon;
    private final UnlockMethodCache mUnlockMethodCache;
    private AccessibilityController mAccessibilityController;
    private boolean mHasFingerPrintIcon;
    private boolean mHasFaceUnlockIcon;
    private int mDensity;

    private final Runnable mDrawOffTimeout = () -> update(true /* forceUpdate */);
    private float mDarkAmount;

    public LockIcon(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUnlockMethodCache = UnlockMethodCache.getInstance(context);
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        mUserAvatarIcon = picture;
        update();
    }

    public void setTransientFpError(boolean transientFpError) {
        mTransientFpError = transientFpError;
        update();
    }

    public void setDeviceInteractive(boolean deviceInteractive) {
        mDeviceInteractive = deviceInteractive;
        update();
    }

    public void setScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        update();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final int density = newConfig.densityDpi;
        if (density != mDensity) {
            mDensity = density;
            update();
        }
    }

    public void update() {
        update(false /* force */);
    }

    public void update(boolean force) {
        int state = getState();
        boolean anyFingerprintIcon = state == STATE_FINGERPRINT || state == STATE_FINGERPRINT_ERROR;
        mHasFaceUnlockIcon = state == STATE_FACE_UNLOCK;
        if (state != mLastState || mDeviceInteractive != mLastDeviceInteractive
                || mScreenOn != mLastScreenOn || force) {
            int iconAnimRes =
                getAnimationResForTransition(mLastState, state, mLastDeviceInteractive,
                    mDeviceInteractive, mLastScreenOn, mScreenOn);
            boolean isAnim = iconAnimRes != -1;
            if (iconAnimRes == R.drawable.lockscreen_fingerprint_draw_off_animation) {
                anyFingerprintIcon = true;
            } else if (iconAnimRes == R.drawable.trusted_state_to_error_animation) {
                anyFingerprintIcon = true;
            } else if (iconAnimRes == R.drawable.error_to_trustedstate_animation) {
                anyFingerprintIcon = true;
            }

            Drawable icon;
            if (isAnim) {
                // Load the animation resource.
                icon = mContext.getDrawable(iconAnimRes);
            } else {
                // Load the static icon resource based on the current state.
                icon = getIconForState(state, mScreenOn, mDeviceInteractive);
            }

            final AnimatedVectorDrawable animation = icon instanceof AnimatedVectorDrawable
                    ? (AnimatedVectorDrawable) icon
                    : null;
            setImageDrawable(icon, false);
            updateDarkTint();
            if (mHasFaceUnlockIcon) {
                announceForAccessibility(getContext().getString(
                    R.string.accessibility_scanning_face));
            }

            mHasFingerPrintIcon = anyFingerprintIcon;
            if (animation != null && isAnim) {
                animation.forceAnimationOnUI();
                animation.start();
            }

            if (iconAnimRes == R.drawable.lockscreen_fingerprint_draw_off_animation) {
                removeCallbacks(mDrawOffTimeout);
                postDelayed(mDrawOffTimeout, FP_DRAW_OFF_TIMEOUT);
            } else {
                removeCallbacks(mDrawOffTimeout);
            }

            mLastState = state;
            mLastDeviceInteractive = mDeviceInteractive;
            mLastScreenOn = mScreenOn;
        }

        updateClickability();
    }

    private void updateClickability() {
        if (mAccessibilityController == null) {
            return;
        }
        boolean clickToUnlock = mAccessibilityController.isAccessibilityEnabled();
        boolean clickToForceLock = mUnlockMethodCache.isTrustManaged()
                && !clickToUnlock;
        boolean longClickToForceLock = mUnlockMethodCache.isTrustManaged()
                && !clickToForceLock;
        setClickable(clickToForceLock || clickToUnlock);
        setLongClickable(longClickToForceLock);
        setFocusable(mAccessibilityController.isAccessibilityEnabled());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mHasFingerPrintIcon) {
            AccessibilityNodeInfo.AccessibilityAction unlock
                    = new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    getContext().getString(R.string.accessibility_unlock_without_fingerprint));
            info.addAction(unlock);
            info.setHintText(getContext().getString(
                    R.string.accessibility_waiting_for_fingerprint));
        } else if (mHasFaceUnlockIcon){
            //Avoid 'button' to be spoken for scanning face
            info.setClassName(LockIcon.class.getName());
            info.setContentDescription(getContext().getString(
                R.string.accessibility_scanning_face));
        }
    }

    public void setAccessibilityController(AccessibilityController accessibilityController) {
        mAccessibilityController = accessibilityController;
    }

    private Drawable getIconForState(int state, boolean screenOn, boolean deviceInteractive) {
        int iconRes;
        switch (state) {
            case STATE_FINGERPRINT:
            case STATE_LOCKED:
                iconRes = R.drawable.ic_lock;
                break;
            case STATE_LOCK_OPEN:
                if (mUnlockMethodCache.isTrustManaged() && mUnlockMethodCache.isTrusted()
                    && mUserAvatarIcon != null) {
                    return mUserAvatarIcon;
                } else {
                    iconRes = R.drawable.ic_lock_open;
                }
                break;
            case STATE_FACE_UNLOCK:
                iconRes = R.drawable.ic_face_unlock;
                break;
            case STATE_FINGERPRINT_ERROR:
                iconRes = R.drawable.ic_fingerprint_error;
                break;
            default:
                throw new IllegalArgumentException();
        }

        if (HOLLOW_PILL && deviceInteractive) {
            switch (state) {
                case STATE_FINGERPRINT:
                case STATE_LOCK_OPEN:
                case STATE_LOCKED:
                case STATE_FACE_UNLOCK:
                    iconRes = R.drawable.ic_home_button_outline;
            }
        }

        return mContext.getDrawable(iconRes);
    }

    private int getAnimationResForTransition(int oldState, int newState,
            boolean oldDeviceInteractive, boolean deviceInteractive,
            boolean oldScreenOn, boolean screenOn) {
        if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_ERROR) {
            return R.drawable.lockscreen_fingerprint_fp_to_error_state_animation;
        } else if (oldState == STATE_LOCK_OPEN && newState == STATE_FINGERPRINT_ERROR) {
            return R.drawable.trusted_state_to_error_animation;
        } else if (oldState == STATE_FINGERPRINT_ERROR && newState == STATE_LOCK_OPEN) {
            return R.drawable.error_to_trustedstate_animation;
        } else if (oldState == STATE_FINGERPRINT_ERROR && newState == STATE_FINGERPRINT) {
            return R.drawable.lockscreen_fingerprint_error_state_to_fp_animation;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_LOCK_OPEN
                && !mUnlockMethodCache.isTrusted()) {
            return R.drawable.lockscreen_fingerprint_draw_off_animation;
        } else if (newState == STATE_FINGERPRINT && (!oldScreenOn && screenOn && deviceInteractive
                || screenOn && !oldDeviceInteractive && deviceInteractive)) {
            return R.drawable.lockscreen_fingerprint_draw_on_animation;
        } else {
            return -1;
        }
    }

    private int getState() {
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        boolean fingerprintRunning = updateMonitor.isFingerprintDetectionRunning();
        boolean unlockingAllowed = updateMonitor.isUnlockingWithBiometricAllowed();
        if (mTransientFpError) {
            return STATE_FINGERPRINT_ERROR;
        } else if (mUnlockMethodCache.canSkipBouncer()) {
            return STATE_LOCK_OPEN;
        } else if (mUnlockMethodCache.isFaceUnlockRunning()) {
            return STATE_FACE_UNLOCK;
        } else if (fingerprintRunning && unlockingAllowed) {
            return STATE_FINGERPRINT;
        } else {
            return STATE_LOCKED;
        }
    }

    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        updateDarkTint();
    }

    private void updateDarkTint() {
        Drawable drawable = getDrawable().mutate();
        int color = ColorUtils.blendARGB(Color.TRANSPARENT, Color.WHITE, mDarkAmount);
        drawable.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
    }
}
