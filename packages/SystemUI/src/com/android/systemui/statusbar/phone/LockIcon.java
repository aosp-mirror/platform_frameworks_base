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
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
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
    private static final int STATE_SCANNING_FACE = 2;
    private static final int STATE_BIOMETRICS_ERROR = 3;

    private int mLastState = 0;
    private boolean mTransientBiometricsError;
    private boolean mScreenOn;
    private boolean mLastScreenOn;
    private final UnlockMethodCache mUnlockMethodCache;
    private AccessibilityController mAccessibilityController;
    private boolean mIsFaceUnlockState;
    private int mDensity;
    private boolean mPulsing;
    private boolean mDozing;
    private boolean mLastDozing;
    private boolean mLastPulsing;

    private final Runnable mDrawOffTimeout = () -> update(true /* forceUpdate */);
    private float mDarkAmount;

    public LockIcon(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray typedArray = context.getTheme().obtainStyledAttributes(
                attrs, new int[]{ R.attr.backgroundProtectedStyle }, 0, 0);
        mContext = new ContextThemeWrapper(context,
                typedArray.getResourceId(0, R.style.BackgroundProtectedStyle));
        typedArray.recycle();
        mUnlockMethodCache = UnlockMethodCache.getInstance(context);
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        update();
    }

    /**
     * If we're currently presenting an authentication error message.
     */
    public void setTransientBiometricsError(boolean transientBiometricsError) {
        mTransientBiometricsError = transientBiometricsError;
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
        mIsFaceUnlockState = state == STATE_SCANNING_FACE;
        if (state != mLastState || mLastDozing != mDozing || mLastPulsing != mPulsing
                || mLastScreenOn != mScreenOn || force) {
            int iconAnimRes = getAnimationResForTransition(mLastState, state, mLastPulsing,
                    mPulsing, mLastDozing, mDozing);
            boolean isAnim = iconAnimRes != -1;

            Drawable icon;
            if (isAnim) {
                // Load the animation resource.
                icon = mContext.getDrawable(iconAnimRes);
            } else {
                // Load the static icon resource based on the current state.
                icon = getIconForState(state);
            }

            final AnimatedVectorDrawable animation = icon instanceof AnimatedVectorDrawable
                    ? (AnimatedVectorDrawable) icon
                    : null;
            setImageDrawable(icon, false);
            updateDarkTint();
            if (mIsFaceUnlockState) {
                announceForAccessibility(getContext().getString(
                    R.string.accessibility_scanning_face));
            }

            if (animation != null && isAnim) {
                animation.forceAnimationOnUI();
                animation.clearAnimationCallbacks();
                animation.registerAnimationCallback(new Animatable2.AnimationCallback() {
                    @Override
                    public void onAnimationEnd(Drawable drawable) {
                        if (getDrawable() == animation && state == getState()
                                && doesAnimationLoop(iconAnimRes)) {
                            animation.start();
                        }
                    }
                });
                animation.start();
            }

            if (isAnim && !mLastScreenOn) {
                removeCallbacks(mDrawOffTimeout);
                postDelayed(mDrawOffTimeout, FP_DRAW_OFF_TIMEOUT);
            } else {
                removeCallbacks(mDrawOffTimeout);
            }

            mLastState = state;
            mLastScreenOn = mScreenOn;
            mLastDozing = mDozing;
            mLastPulsing = mPulsing;
        }

        setVisibility(mDozing && !mPulsing ? GONE : VISIBLE);
        updateClickability();
    }

    private void updateClickability() {
        if (mAccessibilityController == null) {
            return;
        }
        boolean canLock = mUnlockMethodCache.isMethodSecure()
                && mUnlockMethodCache.canSkipBouncer();
        boolean clickToUnlock = mAccessibilityController.isAccessibilityEnabled();
        boolean clickToForceLock = canLock && !clickToUnlock;
        boolean longClickToForceLock = canLock && !clickToForceLock;
        setClickable(clickToForceLock || clickToUnlock);
        setLongClickable(longClickToForceLock);
        setFocusable(mAccessibilityController.isAccessibilityEnabled());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        boolean fingerprintRunning = updateMonitor.isFingerprintDetectionRunning();
        boolean unlockingAllowed = updateMonitor.isUnlockingWithBiometricAllowed();
        if (fingerprintRunning && unlockingAllowed) {
            AccessibilityNodeInfo.AccessibilityAction unlock
                    = new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    getContext().getString(R.string.accessibility_unlock_without_fingerprint));
            info.addAction(unlock);
            info.setHintText(getContext().getString(
                    R.string.accessibility_waiting_for_fingerprint));
        } else if (mIsFaceUnlockState) {
            //Avoid 'button' to be spoken for scanning face
            info.setClassName(LockIcon.class.getName());
            info.setContentDescription(getContext().getString(
                R.string.accessibility_scanning_face));
        }
    }

    public void setAccessibilityController(AccessibilityController accessibilityController) {
        mAccessibilityController = accessibilityController;
    }

    private Drawable getIconForState(int state) {
        int iconRes;
        switch (state) {
            case STATE_LOCKED:
            // Scanning animation is a pulsing padlock. This means that the resting state is
            // just a padlock.
            case STATE_SCANNING_FACE:
            // Error animation also starts and ands on the padlock.
            case STATE_BIOMETRICS_ERROR:
                iconRes = com.android.internal.R.drawable.ic_lock;
                break;
            case STATE_LOCK_OPEN:
                iconRes = com.android.internal.R.drawable.ic_lock_open;
                break;
            default:
                throw new IllegalArgumentException();
        }

        return mContext.getDrawable(iconRes);
    }

    private boolean doesAnimationLoop(int resourceId) {
        return resourceId == com.android.internal.R.anim.lock_scanning;
    }

    private static int getAnimationResForTransition(int oldState, int newState,
            boolean wasPulsing, boolean pulsing,
            boolean wasDozing, boolean dozing) {

        // Never animate when screen is off
        if (dozing && !pulsing) {
            return -1;
        }

        boolean isError = oldState != STATE_BIOMETRICS_ERROR && newState == STATE_BIOMETRICS_ERROR;
        boolean justUnlocked = oldState != STATE_LOCK_OPEN && newState == STATE_LOCK_OPEN;
        boolean justLocked = oldState == STATE_LOCK_OPEN && newState == STATE_LOCKED;

        if (isError) {
            return com.android.internal.R.anim.lock_to_error;
        } else if (justUnlocked) {
            return com.android.internal.R.anim.lock_unlock;
        } else if (justLocked) {
            return com.android.internal.R.anim.lock_lock;
        } else if (newState == STATE_SCANNING_FACE) {
            return com.android.internal.R.anim.lock_scanning;
        } else if (!wasPulsing && pulsing) {
            return com.android.internal.R.anim.lock_in;
        }
        return -1;
    }

    private int getState() {
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        if (mTransientBiometricsError) {
            return STATE_BIOMETRICS_ERROR;
        } else if (mUnlockMethodCache.canSkipBouncer()) {
            return STATE_LOCK_OPEN;
        } else if (mUnlockMethodCache.isFaceUnlockRunning()
                || updateMonitor.isFaceDetectionRunning()) {
            return STATE_SCANNING_FACE;
        } else {
            return STATE_LOCKED;
        }
    }

    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        updateDarkTint();
    }

    /**
     * When keyguard is in pulsing (AOD2) state.
     * @param pulsing {@code true} when pulsing.
     * @param animated if transition should be animated.
     */
    public void setPulsing(boolean pulsing, boolean animated) {
        mPulsing = pulsing;
        update();
    }

    /**
     * Sets the dozing state of the keyguard.
     */
    public void setDozing(boolean dozing) {
        mDozing = dozing;
        update();
    }

    private void updateDarkTint() {
        Drawable drawable = getDrawable().mutate();
        int color = ColorUtils.blendARGB(Color.TRANSPARENT, Color.WHITE, mDarkAmount);
        drawable.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
    }
}
