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

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Trace;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.telephony.IccCardConstants;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.phone.ScrimController.ScrimVisibility;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Manages the different states and animations of the unlock icon.
 */
public class LockIcon extends KeyguardAffordanceView implements OnUserInfoChangedListener,
        StatusBarStateController.StateListener, ConfigurationController.ConfigurationListener,
        UnlockMethodCache.OnUnlockMethodChangedListener,
        NotificationWakeUpCoordinator.WakeUpListener, ViewTreeObserver.OnPreDrawListener,
        OnHeadsUpChangedListener {

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
    private final KeyguardMonitor mKeyguardMonitor;
    private final KeyguardBypassController mBypassController;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final HeadsUpManagerPhone mHeadsUpManager;

    private int mLastState = 0;
    private boolean mForceUpdate;
    private boolean mTransientBiometricsError;
    private boolean mIsFaceUnlockState;
    private boolean mSimLocked;
    private int mDensity;
    private boolean mPulsing;
    private boolean mDozing;
    private boolean mDocked;
    private boolean mBlockUpdates;
    private int mIconColor;
    private float mDozeAmount;
    private boolean mBouncerShowingScrimmed;
    private boolean mWakeAndUnlockRunning;
    private boolean mKeyguardShowing;
    private boolean mShowingLaunchAffordance;
    private boolean mKeyguardJustShown;
    private boolean mUpdatePending;
    private boolean mBouncerPreHideAnimation;

    private final KeyguardMonitor.Callback mKeyguardMonitorCallback =
            new KeyguardMonitor.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    boolean force = false;
                    boolean wasShowing = mKeyguardShowing;
                    mKeyguardShowing = mKeyguardMonitor.isShowing();
                    if (!wasShowing && mKeyguardShowing && mBlockUpdates) {
                        mBlockUpdates = false;
                        force = true;
                    }
                    if (!wasShowing && mKeyguardShowing) {
                        mKeyguardJustShown = true;
                    }
                    update(force);
                }

                @Override
                public void onKeyguardFadingAwayChanged() {
                    if (!mKeyguardMonitor.isKeyguardFadingAway()) {
                        mBouncerPreHideAnimation = false;
                        if (mBlockUpdates) {
                            mBlockUpdates = false;
                            update(true /* force */);
                        }
                    }
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
                        update();
                    }
        }
    };

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onSimStateChanged(int subId, int slotId,
                        IccCardConstants.State simState) {
                    mSimLocked = mKeyguardUpdateMonitor.isSimPinSecure();
                    update();
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
            KeyguardBypassController bypassController,
            NotificationWakeUpCoordinator wakeUpCoordinator,
            KeyguardMonitor keyguardMonitor,
            @Nullable DockManager dockManager,
            HeadsUpManagerPhone headsUpManager) {
        super(context, attrs);
        mContext = context;
        mUnlockMethodCache = UnlockMethodCache.getInstance(context);
        mKeyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mAccessibilityController = accessibilityController;
        mConfigurationController = configurationController;
        mStatusBarStateController = statusBarStateController;
        mBypassController = bypassController;
        mWakeUpCoordinator = wakeUpCoordinator;
        mKeyguardMonitor = keyguardMonitor;
        mDockManager = dockManager;
        mHeadsUpManager = headsUpManager;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStatusBarStateController.addCallback(this);
        mConfigurationController.addCallback(this);
        mKeyguardMonitor.addCallback(mKeyguardMonitorCallback);
        mKeyguardUpdateMonitor.registerCallback(mUpdateMonitorCallback);
        mUnlockMethodCache.addListener(this);
        mWakeUpCoordinator.addListener(this);
        mSimLocked = mKeyguardUpdateMonitor.isSimPinSecure();
        if (mDockManager != null) {
            mDockManager.addListener(mDockEventListener);
        }
        onThemeChanged();
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStatusBarStateController.removeCallback(this);
        mConfigurationController.removeCallback(this);
        mKeyguardUpdateMonitor.removeCallback(mUpdateMonitorCallback);
        mKeyguardMonitor.removeCallback(mKeyguardMonitorCallback);
        mWakeUpCoordinator.removeListener(this);
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
        if (force) {
            mForceUpdate = true;
        }
        if (!mUpdatePending) {
            mUpdatePending = true;
            getViewTreeObserver().addOnPreDrawListener(this);
        }
    }

    @Override
    public boolean onPreDraw() {
        mUpdatePending = false;
        getViewTreeObserver().removeOnPreDrawListener(this);

        int state = getState();
        int lastState = mLastState;
        boolean keyguardJustShown = mKeyguardJustShown;
        mIsFaceUnlockState = state == STATE_SCANNING_FACE;
        mLastState = state;
        mKeyguardJustShown = false;

        boolean shouldUpdate = lastState != state || mForceUpdate;
        if (mBlockUpdates && canBlockUpdates()) {
            shouldUpdate = false;
        }
        if (shouldUpdate) {
            mForceUpdate = false;
            @LockAnimIndex final int lockAnimIndex = getAnimationIndexForTransition(lastState,
                    state, mPulsing, mDozing, keyguardJustShown);
            boolean isAnim = lockAnimIndex != -1;
            int iconRes = isAnim ? getThemedAnimationResId(lockAnimIndex) : getIconForState(state);

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
                                && doesAnimationLoop(lockAnimIndex)) {
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

        updateIconVisibility();
        updateClickability();

        return true;
    }

    /**
     * Update the icon visibility
     * @return true if the visibility changed
     */
    private boolean updateIconVisibility() {
        boolean onAodNotPulsingOrDocked = mDozing && (!mPulsing || mDocked);
        boolean invisible = onAodNotPulsingOrDocked || mWakeAndUnlockRunning
                || mShowingLaunchAffordance;
        if (mBypassController.getBypassEnabled() && !mBouncerShowingScrimmed) {
            if ((mHeadsUpManager.isHeadsUpGoingAway() || mHeadsUpManager.hasPinnedHeadsUp()
                    || mStatusBarStateController.getState() == StatusBarState.KEYGUARD)
                    && !mWakeUpCoordinator.getNotificationsFullyHidden()) {
                invisible = true;
            }
        }
        boolean wasInvisible = getVisibility() == INVISIBLE;
        if (invisible != wasInvisible) {
            setVisibility(invisible ? INVISIBLE : VISIBLE);
            animate().cancel();
            if (!invisible) {
                setScaleX(0);
                setScaleY(0);
                animate()
                        .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                        .scaleX(1)
                        .scaleY(1)
                        .withLayer()
                        .setDuration(233)
                        .start();
            }
            return true;
        }
        return false;
    }

    private boolean canBlockUpdates() {
        return mKeyguardShowing || mKeyguardMonitor.isKeyguardFadingAway();
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

    private boolean doesAnimationLoop(@LockAnimIndex int lockAnimIndex) {
        return lockAnimIndex == SCANNING;
    }

    private static int getAnimationIndexForTransition(int oldState, int newState, boolean pulsing,
            boolean dozing, boolean keyguardJustShown) {

        // Never animate when screen is off
        if (dozing && !pulsing) {
            return -1;
        }

        if (newState == STATE_BIOMETRICS_ERROR) {
            return ERROR;
        } else if (oldState != STATE_LOCK_OPEN && newState == STATE_LOCK_OPEN) {
            return UNLOCK;
        } else if (oldState == STATE_LOCK_OPEN && newState == STATE_LOCKED && !keyguardJustShown) {
            return LOCK;
        } else if (newState == STATE_SCANNING_FACE) {
            return SCANNING;
        }
        return -1;
    }

    @Override
    public void onFullyHiddenChanged(boolean isFullyHidden) {
        if (mBypassController.getBypassEnabled()) {
            boolean changed = updateIconVisibility();
            if (changed) {
                update();
            }
        }
    }

    public void setBouncerShowingScrimmed(boolean bouncerShowing) {
        mBouncerShowingScrimmed = bouncerShowing;
        if (mBypassController.getBypassEnabled()) {
            update();
        }
    }

    /**
     * Animate padlock opening when bouncer challenge is solved.
     */
    public void onBouncerPreHideAnimation() {
        mBouncerPreHideAnimation = true;
        update();
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ERROR, UNLOCK, LOCK, SCANNING})
    @interface LockAnimIndex {}
    private static final int ERROR = 0, UNLOCK = 1, LOCK = 2, SCANNING = 3;
    private static final int[][] LOCK_ANIM_RES_IDS = new int[][] {
            {
                    R.anim.lock_to_error,
                    R.anim.lock_unlock,
                    R.anim.lock_lock,
                    R.anim.lock_scanning
            },
            {
                    R.anim.lock_to_error_circular,
                    R.anim.lock_unlock_circular,
                    R.anim.lock_lock_circular,
                    R.anim.lock_scanning_circular
            },
            {
                    R.anim.lock_to_error_filled,
                    R.anim.lock_unlock_filled,
                    R.anim.lock_lock_filled,
                    R.anim.lock_scanning_filled
            },
            {
                    R.anim.lock_to_error_rounded,
                    R.anim.lock_unlock_rounded,
                    R.anim.lock_lock_rounded,
                    R.anim.lock_scanning_rounded
            },
    };

    private int getThemedAnimationResId(@LockAnimIndex int lockAnimIndex) {
        final String setting = TextUtils.emptyIfNull(
                Settings.Secure.getString(getContext().getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES));
        if (setting.contains("com.android.theme.icon_pack.circular.android")) {
            return LOCK_ANIM_RES_IDS[1][lockAnimIndex];
        } else if (setting.contains("com.android.theme.icon_pack.filled.android")) {
            return LOCK_ANIM_RES_IDS[2][lockAnimIndex];
        } else if (setting.contains("com.android.theme.icon_pack.rounded.android")) {
            return LOCK_ANIM_RES_IDS[3][lockAnimIndex];
        }
        return LOCK_ANIM_RES_IDS[0][lockAnimIndex];
    }

    private int getState() {
        KeyguardUpdateMonitor updateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        if ((mUnlockMethodCache.canSkipBouncer() || !mKeyguardShowing || mBouncerPreHideAnimation
                || mKeyguardMonitor.isKeyguardGoingAway()) && !mSimLocked) {
            return STATE_LOCK_OPEN;
        } else if (mTransientBiometricsError) {
            return STATE_BIOMETRICS_ERROR;
        } else if (updateMonitor.isFaceDetectionRunning() && !mPulsing) {
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
     * @param wakeAndUnlock are we wake and unlocking
     * @param isUnlock are we currently unlocking
     */
    public void onBiometricAuthModeChanged(boolean wakeAndUnlock, boolean isUnlock) {
        if (wakeAndUnlock) {
            mWakeAndUnlockRunning = true;
        }
        if (isUnlock && mBypassController.getBypassEnabled() && canBlockUpdates()) {
            // We don't want the icon to change while we are unlocking
            mBlockUpdates = true;
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
                && scrimsVisible == ScrimController.TRANSPARENT) {
            mWakeAndUnlockRunning = false;
            update();
        }
    }
}
