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

import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;
import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.Trace;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.telephony.IccCardConstants;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.phone.ScrimController.ScrimVisibility;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Manages the different states and animations of the unlock icon.
 */
public class LockIcon extends KeyguardAffordanceView implements OnUserInfoChangedListener,
        StatusBarStateController.StateListener, ConfigurationController.ConfigurationListener,
        UnlockMethodCache.OnUnlockMethodChangedListener {

    private static final int STATE_LOCKED = 0;
    private static final int STATE_LOCK_OPEN = 1;
    private static final int STATE_SCANNING_FACE = 2;
    private static final int STATE_BIOMETRICS_ERROR = 3;
    private final ConfigurationController mConfigurationController;
    private final StatusBarStateController mStatusBarStateController;
    private final UnlockMethodCache mUnlockMethodCache;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final AccessibilityController mAccessibilityController;
    private final DockManager mDockManager;
    private final Handler mMainHandler;
    private final KeyguardMonitor mKeyguardMonitor;

    private int mLastState = 0;
    private boolean mTransientBiometricsError;
    private boolean mIsFaceUnlockState;
    private boolean mSimLocked;
    private int mDensity;
    private boolean mPulsing;
    private boolean mDozing;
    private boolean mBouncerVisible;
    private boolean mDocked;
    private boolean mLastDozing;
    private boolean mLastPulsing;
    private boolean mLastBouncerVisible;
    private int mIconColor;
    private float mDozeAmount;
    private int mIconRes;
    private boolean mWasPulsingOnThisFrame;
    private boolean mWakeAndUnlockRunning;
    private boolean mKeyguardShowing;
    private boolean mShowingLaunchAffordance;

    private final KeyguardMonitor.Callback mKeyguardMonitorCallback =
            new KeyguardMonitor.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    mKeyguardShowing = mKeyguardMonitor.isShowing();
                    update(false /* force */);
                }
            };
    private final DockManager.DockEventListener mDockEventListener =
            new DockManager.DockEventListener() {
                @Override
                public void onEvent(int event) {
                    boolean docked = event == DockManager.STATE_DOCKED
                            || event == DockManager.STATE_DOCKED_HIDE;
                    if (docked != mDocked) {
                        mDocked = docked;
                        update(true /* force */);
                    }
        }
    };

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onSimStateChanged(int subId, int slotId,
                        IccCardConstants.State simState) {
                    boolean oldSimLocked = mSimLocked;
                    mSimLocked = mKeyguardUpdateMonitor.isSimPinSecure();
                    update(oldSimLocked != mSimLocked);
                }

                @Override
                public void onKeyguardVisibilityChanged(boolean showing) {
                    update();
                }

                @Override
                public void onBiometricRunningStateChanged(boolean running,
                        BiometricSourceType biometricSourceType) {
                    update();
                }

                @Override
                public void onStrongAuthStateChanged(int userId) {
                    update();
                }
    };

    @Inject
    public LockIcon(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            AccessibilityController accessibilityController,
            KeyguardMonitor keyguardMonitor,
            @Nullable DockManager dockManager,
            @Named(MAIN_HANDLER_NAME) Handler mainHandler) {
        super(context, attrs);
        mContext = context;
        mUnlockMethodCache = UnlockMethodCache.getInstance(context);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mAccessibilityController = accessibilityController;
        mConfigurationController = configurationController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardMonitor = keyguardMonitor;
        mDockManager = dockManager;
        mMainHandler = mainHandler;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStatusBarStateController.addCallback(this);
        mConfigurationController.addCallback(this);
        mKeyguardMonitor.addCallback(mKeyguardMonitorCallback);
        mKeyguardUpdateMonitor.registerCallback(mUpdateMonitorCallback);
        mUnlockMethodCache.addListener(this);
        mSimLocked = mKeyguardUpdateMonitor.isSimPinSecure();
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
        }
        onThemeChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStatusBarStateController.removeCallback(this);
        mConfigurationController.removeCallback(this);
        mKeyguardUpdateMonitor.removeCallback(mUpdateMonitorCallback);
        mKeyguardMonitor.removeCallback(mKeyguardMonitorCallback);
        mUnlockMethodCache.removeListener(this);
        if (mDockManager != null) {
            mDockManager.removeListener(mDockEventListener);
        }
    }

    @Override
    public void onThemeChanged() {
        TypedArray typedArray = mContext.getTheme().obtainStyledAttributes(
                null, new int[]{ R.attr.wallpaperTextColor }, 0, 0);
        mIconColor = typedArray.getColor(0, Color.WHITE);
        typedArray.recycle();
        updateDarkTint();
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
                || mLastBouncerVisible != mBouncerVisible || force) {
            int iconAnimRes = getAnimationResForTransition(mLastState, state, mLastPulsing,
                    mPulsing, mLastDozing, mDozing, mBouncerVisible);
            boolean isAnim = iconAnimRes != -1;

            int iconRes = isAnim ? iconAnimRes : getIconForState(state);
            if (iconRes != mIconRes) {
                mIconRes = iconRes;

                Drawable icon = mContext.getDrawable(iconRes);
                final AnimatedVectorDrawable animation = icon instanceof AnimatedVectorDrawable
                        ? (AnimatedVectorDrawable) icon
                        : null;
                setImageDrawable(icon, false);
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
                            } else {
                                Trace.endAsyncSection("LockIcon#Animation", state);
                            }
                        }
                    });
                    Trace.beginAsyncSection("LockIcon#Animation", state);
                    animation.start();
                }
            }
            updateDarkTint();

            mLastState = state;
            mLastDozing = mDozing;
            mLastPulsing = mPulsing;
            mLastBouncerVisible = mBouncerVisible;
        }

        boolean onAodNotPulsingOrDocked = mDozing && (!mPulsing || mDocked);
        boolean invisible = onAodNotPulsingOrDocked || mWakeAndUnlockRunning
                || mShowingLaunchAffordance;
        setVisibility(invisible ? INVISIBLE : VISIBLE);
        updateClickability();
    }

    private void updateClickability() {
        if (mAccessibilityController == null) {
            return;
        }
        boolean canLock = mUnlockMethodCache.isMethodSecure()
                && mUnlockMethodCache.canSkipBouncer();
        boolean clickToUnlock = mAccessibilityController.isAccessibilityEnabled();
        setClickable(clickToUnlock);
        setLongClickable(canLock && !clickToUnlock);
        setFocusable(mAccessibilityController.isAccessibilityEnabled());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        boolean fingerprintRunning = mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
        boolean unlockingAllowed = mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed();
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

    private int getIconForState(int state) {
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

        return iconRes;
    }

    private boolean doesAnimationLoop(int resourceId) {
        return resourceId == com.android.internal.R.anim.lock_scanning;
    }

    private int getAnimationResForTransition(int oldState, int newState,
            boolean wasPulsing, boolean pulsing, boolean wasDozing, boolean dozing,
            boolean bouncerVisible) {

        // Never animate when screen is off
        if (dozing && !pulsing && !mWasPulsingOnThisFrame) {
            return -1;
        }

        boolean isError = oldState != STATE_BIOMETRICS_ERROR && newState == STATE_BIOMETRICS_ERROR;
        boolean justUnlocked = oldState != STATE_LOCK_OPEN && newState == STATE_LOCK_OPEN;
        boolean justLocked = oldState == STATE_LOCK_OPEN && newState == STATE_LOCKED;
        boolean nowPulsing = !wasPulsing && pulsing;
        boolean turningOn = wasDozing && !dozing && !mWasPulsingOnThisFrame;

        if (isError) {
            return com.android.internal.R.anim.lock_to_error;
        } else if (justUnlocked) {
            return com.android.internal.R.anim.lock_unlock;
        } else if (justLocked) {
            return com.android.internal.R.anim.lock_lock;
        } else if (newState == STATE_SCANNING_FACE && bouncerVisible) {
            return com.android.internal.R.anim.lock_scanning;
        } else if ((nowPulsing || turningOn) && newState != STATE_LOCK_OPEN) {
            return com.android.internal.R.anim.lock_in;
        }
        return -1;
    }

    private int getState() {
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        if (mTransientBiometricsError) {
            return STATE_BIOMETRICS_ERROR;
        } else if ((mUnlockMethodCache.canSkipBouncer() || !mKeyguardShowing) && !mSimLocked) {
            return STATE_LOCK_OPEN;
        } else if (updateMonitor.isFaceDetectionRunning()) {
            return STATE_SCANNING_FACE;
        } else {
            return STATE_LOCKED;
        }
    }

    @Override
    public void onDozeAmountChanged(float linear, float eased) {
        mDozeAmount = eased;
        updateDarkTint();
    }

    /**
     * When keyguard is in pulsing (AOD2) state.
     * @param pulsing {@code true} when pulsing.
     */
    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
        if (!mPulsing) {
            mWasPulsingOnThisFrame = true;
            mMainHandler.post(() -> {
                mWasPulsingOnThisFrame = false;
            });
        }
        update();
    }

    /**
     * Sets the dozing state of the keyguard.
     */
    @Override
    public void onDozingChanged(boolean dozing) {
        mDozing = dozing;
        update();
    }

    private void updateDarkTint() {
        int color = ColorUtils.blendARGB(mIconColor, Color.WHITE, mDozeAmount);
        setImageTintList(ColorStateList.valueOf(color));
    }

    /**
     * If bouncer is visible or not.
     */
    public void setBouncerVisible(boolean bouncerVisible) {
        if (mBouncerVisible == bouncerVisible) {
            return;
        }
        mBouncerVisible = bouncerVisible;
        update();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) {
            return;
        }
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_lock_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_lock_height);
        setLayoutParams(lp);
        update(true /* force */);
    }

    @Override
    public void onLocaleListChanged() {
        setContentDescription(getContext().getText(R.string.accessibility_unlock_button));
        update(true /* force */);
    }

    @Override
    public void onUnlockMethodStateChanged() {
        update();
    }

    /**
     * We need to hide the lock whenever there's a fingerprint unlock, otherwise you'll see the
     * icon on top of the black front scrim.
     */
    public void onBiometricAuthModeChanged(boolean wakeAndUnlock) {
        if (wakeAndUnlock) {
            mWakeAndUnlockRunning = true;
        }
        update();
    }

    /**
     * When we're launching an affordance, like double pressing power to open camera.
     */
    public void onShowingLaunchAffordanceChanged(boolean showing) {
        mShowingLaunchAffordance = showing;
        update();
    }

    /**
     * Called whenever the scrims become opaque, transparent or semi-transparent.
     */
    public void onScrimVisibilityChanged(@ScrimVisibility int scrimsVisible) {
        if (mWakeAndUnlockRunning
                && scrimsVisible == ScrimController.VISIBILITY_FULLY_TRANSPARENT) {
            mWakeAndUnlockRunning = false;
            update();
        }
    }
}
