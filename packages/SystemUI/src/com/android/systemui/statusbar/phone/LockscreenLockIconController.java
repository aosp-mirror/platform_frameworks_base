/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.systemui.statusbar.phone.LockIcon.STATE_BIOMETRICS_ERROR;
import static com.android.systemui.statusbar.phone.LockIcon.STATE_LOCKED;
import static com.android.systemui.statusbar.phone.LockIcon.STATE_LOCK_OPEN;
import static com.android.systemui.statusbar.phone.LockIcon.STATE_SCANNING_FACE;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.hardware.biometrics.BiometricSourceType;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator.WakeUpListener;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Controls the {@link LockIcon} in the lockscreen. */
@Singleton
public class LockscreenLockIconController {

    private final LockscreenGestureLogger mLockscreenGestureLogger;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final LockPatternUtils mLockPatternUtils;
    private final ShadeController mShadeController;
    private final AccessibilityController mAccessibilityController;
    private final KeyguardIndicationController mKeyguardIndicationController;
    private final StatusBarStateController mStatusBarStateController;
    private final ConfigurationController mConfigurationController;
    private final NotificationWakeUpCoordinator mNotificationWakeUpCoordinator;
    private final KeyguardBypassController mKeyguardBypassController;
    private final Optional<DockManager> mDockManager;
    private final KeyguardStateController mKeyguardStateController;
    private final Resources mResources;
    private final HeadsUpManagerPhone mHeadsUpManagerPhone;
    private boolean mKeyguardShowing;
    private boolean mKeyguardJustShown;
    private boolean mBlockUpdates;
    private boolean mPulsing;
    private boolean mDozing;
    private boolean mSimLocked;
    private boolean mTransientBiometricsError;
    private boolean mDocked;
    private boolean mWakeAndUnlockRunning;
    private boolean mShowingLaunchAffordance;
    private boolean mBouncerShowingScrimmed;
    private int mStatusBarState = StatusBarState.SHADE;
    private LockIcon mLockIcon;

    private View.OnAttachStateChangeListener mOnAttachStateChangeListener =
            new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            mStatusBarStateController.addCallback(mSBStateListener);
            mConfigurationController.addCallback(mConfigurationListener);
            mNotificationWakeUpCoordinator.addListener(mWakeUpListener);
            mKeyguardUpdateMonitor.registerCallback(mUpdateMonitorCallback);
            mKeyguardStateController.addCallback(mKeyguardMonitorCallback);

            mDockManager.ifPresent(dockManager -> dockManager.addListener(mDockEventListener));

            mSimLocked = mKeyguardUpdateMonitor.isSimPinSecure();
            mConfigurationListener.onThemeChanged();
            update();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mStatusBarStateController.removeCallback(mSBStateListener);
            mConfigurationController.removeCallback(mConfigurationListener);
            mNotificationWakeUpCoordinator.removeListener(mWakeUpListener);
            mKeyguardUpdateMonitor.removeCallback(mUpdateMonitorCallback);
            mKeyguardStateController.removeCallback(mKeyguardMonitorCallback);

            mDockManager.ifPresent(dockManager -> dockManager.removeListener(mDockEventListener));
        }
    };

    private final StatusBarStateController.StateListener mSBStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    setDozing(isDozing);
                }

                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    if (mLockIcon != null) {
                        mLockIcon.setDozeAmount(eased);
                    }
                }

                @Override
                public void onStateChanged(int newState) {
                    setStatusBarState(newState);
                }
            };

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        private int mDensity;

        @Override
        public void onThemeChanged() {
            if (mLockIcon == null) {
                return;
            }

            TypedArray typedArray = mLockIcon.getContext().getTheme().obtainStyledAttributes(
                    null, new int[]{ R.attr.wallpaperTextColor }, 0, 0);
            int iconColor = typedArray.getColor(0, Color.WHITE);
            typedArray.recycle();
            mLockIcon.onThemeChange(iconColor);
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            if (mLockIcon == null) {
                return;
            }

            ViewGroup.LayoutParams lp = mLockIcon.getLayoutParams();
            if (lp == null) {
                return;
            }
            lp.width = mLockIcon.getResources().getDimensionPixelSize(R.dimen.keyguard_lock_width);
            lp.height = mLockIcon.getResources().getDimensionPixelSize(
                    R.dimen.keyguard_lock_height);
            mLockIcon.setLayoutParams(lp);
            update(true /* force */);
        }

        @Override
        public void onLocaleListChanged() {
            if (mLockIcon == null) {
                return;
            }

            mLockIcon.setContentDescription(
                    mLockIcon.getResources().getText(R.string.accessibility_unlock_button));
            update(true /* force */);
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            final int density = newConfig.densityDpi;
            if (density != mDensity) {
                mDensity = density;
                update();
            }
        }
    };

    private final WakeUpListener mWakeUpListener = new WakeUpListener() {
        @Override
        public void onPulseExpansionChanged(boolean expandingChanged) {
        }

        @Override
        public void onFullyHiddenChanged(boolean isFullyHidden) {
            if (mKeyguardBypassController.getBypassEnabled()) {
                boolean changed = updateIconVisibility();
                if (changed) {
                    update();
                }
            }
        }
    };

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onSimStateChanged(int subId, int slotId, int simState) {
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

    private final DockManager.DockEventListener mDockEventListener =
            event -> {
                boolean docked =
                        event == DockManager.STATE_DOCKED || event == DockManager.STATE_DOCKED_HIDE;
                if (docked != mDocked) {
                    mDocked = docked;
                    update();
                }
            };

    private final KeyguardStateController.Callback mKeyguardMonitorCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    boolean force = false;
                    boolean wasShowing = mKeyguardShowing;
                    mKeyguardShowing = mKeyguardStateController.isShowing();
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
                    if (!mKeyguardStateController.isKeyguardFadingAway()) {
                        if (mBlockUpdates) {
                            mBlockUpdates = false;
                            update(true /* force */);
                        }
                    }
                }

                @Override
                public void onUnlockedChanged() {
                    update();
                }
            };

    private final View.AccessibilityDelegate mAccessibilityDelegate =
            new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                        AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    boolean fingerprintRunning =
                            mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
                    // Only checking if unlocking with Biometric is allowed (no matter strong or
                    // non-strong as long as primary auth, i.e. PIN/pattern/password, is not
                    // required), so it's ok to pass true for isStrongBiometric to
                    // isUnlockingWithBiometricAllowed() to bypass the check of whether non-strong
                    // biometric is allowed
                    boolean unlockingAllowed = mKeyguardUpdateMonitor
                            .isUnlockingWithBiometricAllowed(true /* isStrongBiometric */);
                    if (fingerprintRunning && unlockingAllowed) {
                        AccessibilityNodeInfo.AccessibilityAction unlock =
                                new AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK,
                                mResources.getString(
                                        R.string.accessibility_unlock_without_fingerprint));
                        info.addAction(unlock);
                        info.setHintText(mResources.getString(
                                R.string.accessibility_waiting_for_fingerprint));
                    } else if (getState() == STATE_SCANNING_FACE) {
                        //Avoid 'button' to be spoken for scanning face
                        info.setClassName(LockIcon.class.getName());
                        info.setContentDescription(mResources.getString(
                                R.string.accessibility_scanning_face));
                    }
                }
            };
    private int mLastState;

    @Inject
    public LockscreenLockIconController(LockscreenGestureLogger lockscreenGestureLogger,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            LockPatternUtils lockPatternUtils,
            ShadeController shadeController,
            AccessibilityController accessibilityController,
            KeyguardIndicationController keyguardIndicationController,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            @Nullable DockManager dockManager,
            KeyguardStateController keyguardStateController,
            @Main Resources resources,
            HeadsUpManagerPhone headsUpManagerPhone) {
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mShadeController = shadeController;
        mAccessibilityController = accessibilityController;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarStateController = statusBarStateController;
        mConfigurationController = configurationController;
        mNotificationWakeUpCoordinator = notificationWakeUpCoordinator;
        mKeyguardBypassController = keyguardBypassController;
        mDockManager = dockManager == null ? Optional.empty() : Optional.of(dockManager);
        mKeyguardStateController = keyguardStateController;
        mResources = resources;
        mHeadsUpManagerPhone = headsUpManagerPhone;

        mKeyguardIndicationController.setLockIconController(this);
    }

    /**
     * Associate the controller with a {@link LockIcon}
     *
     * TODO: change to an init method and inject the view.
     */
    public void attach(LockIcon lockIcon) {
        mLockIcon = lockIcon;

        mLockIcon.setOnClickListener(this::handleClick);
        mLockIcon.setOnLongClickListener(this::handleLongClick);
        mLockIcon.setAccessibilityDelegate(mAccessibilityDelegate);
        mLockIcon.setStateProvider(this::getState);

        if (mLockIcon.isAttachedToWindow()) {
            mOnAttachStateChangeListener.onViewAttachedToWindow(mLockIcon);
        }
        mLockIcon.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
        setStatusBarState(mStatusBarStateController.getState());
    }

    public LockIcon getView() {
        return mLockIcon;
    }

    /**
     * Called whenever the scrims become opaque, transparent or semi-transparent.
     */
    public void onScrimVisibilityChanged(Integer scrimsVisible) {
        if (mWakeAndUnlockRunning
                && scrimsVisible == ScrimController.TRANSPARENT) {
            mWakeAndUnlockRunning = false;
            update();
        }
    }

    /**
     * Propagate {@link StatusBar} pulsing state.
     */
    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
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
        if (isUnlock && mKeyguardBypassController.getBypassEnabled() && canBlockUpdates()) {
            // We don't want the icon to change while we are unlocking
            mBlockUpdates = true;
        }
        update();
    }

    /**
     * When we're launching an affordance, like double pressing power to open camera.
     */
    public void onShowingLaunchAffordanceChanged(Boolean showing) {
        mShowingLaunchAffordance = showing;
        update();
    }

    /** Sets whether the bouncer is showing. */
    public void setBouncerShowingScrimmed(boolean bouncerShowing) {
        mBouncerShowingScrimmed = bouncerShowing;
        if (mKeyguardBypassController.getBypassEnabled()) {
            update();
        }
    }

    /**
     * Animate padlock opening when bouncer challenge is solved.
     */
    public void onBouncerPreHideAnimation() {
        update();
    }

    /**
     * If we're currently presenting an authentication error message.
     */
    public void setTransientBiometricsError(boolean transientBiometricsError) {
        mTransientBiometricsError = transientBiometricsError;
        update();
    }

    private boolean handleLongClick(View view) {
        mLockscreenGestureLogger.write(MetricsProto.MetricsEvent.ACTION_LS_LOCK,
                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
        mKeyguardIndicationController.showTransientIndication(
                R.string.keyguard_indication_trust_disabled);
        mKeyguardUpdateMonitor.onLockIconPressed();
        mLockPatternUtils.requireCredentialEntry(KeyguardUpdateMonitor.getCurrentUser());

        return true;
    }


    private void handleClick(View view) {
        if (!mAccessibilityController.isAccessibilityEnabled()) {
            return;
        }
        mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */);
    }

    private void update() {
        update(false /* force */);
    }

    private void update(boolean force) {
        int state = getState();
        boolean shouldUpdate = mLastState != state || force;
        if (mBlockUpdates && canBlockUpdates()) {
            shouldUpdate = false;
        }
        if (shouldUpdate && mLockIcon != null) {
            mLockIcon.update(mLastState, mPulsing, mDozing, mKeyguardJustShown);
        }
        mLastState = state;
        mKeyguardJustShown = false;
        updateIconVisibility();
        updateClickability();
    }

    private int getState() {
        if ((mKeyguardStateController.canDismissLockScreen() || !mKeyguardShowing
                || mKeyguardStateController.isKeyguardGoingAway()) && !mSimLocked) {
            return STATE_LOCK_OPEN;
        } else if (mTransientBiometricsError) {
            return STATE_BIOMETRICS_ERROR;
        } else if (mKeyguardUpdateMonitor.isFaceDetectionRunning() && !mPulsing) {
            return STATE_SCANNING_FACE;
        } else {
            return STATE_LOCKED;
        }
    }

    private boolean canBlockUpdates() {
        return mKeyguardShowing || mKeyguardStateController.isKeyguardFadingAway();
    }

    private void setDozing(boolean isDozing) {
        mDozing = isDozing;
        update();
    }

    /** Set the StatusBarState. */
    private void setStatusBarState(int statusBarState) {
        mStatusBarState = statusBarState;
        updateIconVisibility();
    }

    /**
     * Update the icon visibility
     * @return true if the visibility changed
     */
    private boolean updateIconVisibility() {
        boolean onAodNotPulsingOrDocked = mDozing && (!mPulsing || mDocked);
        boolean invisible = onAodNotPulsingOrDocked || mWakeAndUnlockRunning
                || mShowingLaunchAffordance;
        if (mKeyguardBypassController.getBypassEnabled() && !mBouncerShowingScrimmed) {
            if ((mHeadsUpManagerPhone.isHeadsUpGoingAway()
                    || mHeadsUpManagerPhone.hasPinnedHeadsUp()
                    || mStatusBarState == StatusBarState.KEYGUARD)
                    && !mNotificationWakeUpCoordinator.getNotificationsFullyHidden()) {
                invisible = true;
            }
        }

        if (mLockIcon == null) {
            return false;
        }

        return mLockIcon.updateIconVisibility(!invisible);
    }

    private void updateClickability() {
        if (mAccessibilityController == null) {
            return;
        }
        boolean canLock = mKeyguardStateController.isMethodSecure()
                && mKeyguardStateController.canDismissLockScreen();
        boolean clickToUnlock = mAccessibilityController.isAccessibilityEnabled();
        if (mLockIcon != null) {
            mLockIcon.setClickable(clickToUnlock);
            mLockIcon.setLongClickable(canLock && !clickToUnlock);
            mLockIcon.setFocusable(mAccessibilityController.isAccessibilityEnabled());
        }
    }

}
