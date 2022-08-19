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

import static android.app.StatusBarManager.SESSION_KEYGUARD;
import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;

import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_BIOMETRIC;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_EXTENDED_ACCESS;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_NONE_SECURITY;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_PASSWORD;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_SIM;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_PRIMARY;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_SECONDARY_USER;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_WORK_PROFILE;
import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricSourceType;
import android.metrics.LogMaker;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityContainer.BouncerUiEvent;
import com.android.keyguard.KeyguardSecurityContainer.SecurityCallback;
import com.android.keyguard.KeyguardSecurityContainer.SwipeListener;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.biometrics.SidefpsController;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.GlobalSettings;

import java.util.Optional;

import javax.inject.Inject;

/** Controller for {@link KeyguardSecurityContainer} */
@KeyguardBouncerScope
public class KeyguardSecurityContainerController extends ViewController<KeyguardSecurityContainer>
        implements KeyguardSecurityView {

    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardSecurityView";

    private final AdminSecondaryLockScreenController mAdminSecondaryLockScreenController;
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardSecurityModel mSecurityModel;
    private final MetricsLogger mMetricsLogger;
    private final UiEventLogger mUiEventLogger;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardSecurityViewFlipperController mSecurityViewFlipperController;
    private final SecurityCallback mSecurityCallback;
    private final ConfigurationController mConfigurationController;
    private final FalsingCollector mFalsingCollector;
    private final FalsingManager mFalsingManager;
    private final UserSwitcherController mUserSwitcherController;
    private final GlobalSettings mGlobalSettings;
    private final FeatureFlags mFeatureFlags;
    private final SessionTracker mSessionTracker;
    private final Optional<SidefpsController> mSidefpsController;

    private int mLastOrientation = Configuration.ORIENTATION_UNDEFINED;

    private SecurityMode mCurrentSecurityMode = SecurityMode.Invalid;
    private UserSwitcherController.UserSwitchCallback mUserSwitchCallback =
            () -> showPrimarySecurityScreen(false);

    @VisibleForTesting
    final Gefingerpoken mGlobalTouchListener = new Gefingerpoken() {
        private MotionEvent mTouchDown;
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            // Do just a bit of our own falsing. People should only be tapping on the input, not
            // swiping.
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // If we're in one handed mode, the user can tap on the opposite side of the screen
                // to move the bouncer across. In that case, inhibit the falsing (otherwise the taps
                // to move the bouncer to each screen side can end up closing it instead).
                if (mView.isTouchOnTheOtherSideOfSecurity(ev)) {
                    mFalsingCollector.avoidGesture();
                }

                if (mTouchDown != null) {
                    mTouchDown.recycle();
                    mTouchDown = null;
                }
                mTouchDown = MotionEvent.obtain(ev);
            } else if (mTouchDown != null) {
                if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mTouchDown.recycle();
                    mTouchDown = null;
                }
            }
            return false;
        }
    };

    private KeyguardSecurityCallback mKeyguardSecurityCallback = new KeyguardSecurityCallback() {
        public void userActivity() {
            if (mSecurityCallback != null) {
                mSecurityCallback.userActivity();
            }
        }

        @Override
        public void onUserInput() {
            mUpdateMonitor.cancelFaceAuth();
        }

        @Override
        public void dismiss(boolean authenticated, int targetId,
                SecurityMode expectedSecurityMode) {
            dismiss(authenticated, targetId, /* bypassSecondaryLockScreen */ false,
                    expectedSecurityMode);
        }

        @Override
        public void dismiss(boolean authenticated, int targetId,
                boolean bypassSecondaryLockScreen, SecurityMode expectedSecurityMode) {
            mSecurityCallback.dismiss(authenticated, targetId, bypassSecondaryLockScreen,
                    expectedSecurityMode);
        }

        public boolean isVerifyUnlockOnly() {
            return false;
        }

        public void reportUnlockAttempt(int userId, boolean success, int timeoutMs) {
            int bouncerSide = SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED__SIDE__DEFAULT;
            if (mView.isSidedSecurityMode()) {
                bouncerSide = mView.isSecurityLeftAligned()
                        ? SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED__SIDE__LEFT
                        : SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED__SIDE__RIGHT;
            }

            if (success) {
                SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED,
                        SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED__RESULT__SUCCESS,
                        bouncerSide);
                mLockPatternUtils.reportSuccessfulPasswordAttempt(userId);
                // Force a garbage collection in an attempt to erase any lockscreen password left in
                // memory. Do it asynchronously with a 5-sec delay to avoid making the keyguard
                // dismiss animation janky.
                ThreadUtils.postOnBackgroundThread(() -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) { }
                    System.gc();
                    System.runFinalization();
                    System.gc();
                });
            } else {
                SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED,
                        SysUiStatsLog.KEYGUARD_BOUNCER_PASSWORD_ENTERED__RESULT__FAILURE,
                        bouncerSide);
                reportFailedUnlockAttempt(userId, timeoutMs);
            }
            mMetricsLogger.write(new LogMaker(MetricsEvent.BOUNCER)
                    .setType(success ? MetricsEvent.TYPE_SUCCESS : MetricsEvent.TYPE_FAILURE));
            mUiEventLogger.log(success ? BouncerUiEvent.BOUNCER_PASSWORD_SUCCESS
                            : BouncerUiEvent.BOUNCER_PASSWORD_FAILURE, getSessionId());
        }

        public void reset() {
            mSecurityCallback.reset();
        }

        public void onCancelClicked() {
            mSecurityCallback.onCancelClicked();
        }
    };


    private SwipeListener mSwipeListener = new SwipeListener() {
        @Override
        public void onSwipeUp() {
            if (!mUpdateMonitor.isFaceDetectionRunning()) {
                mUpdateMonitor.requestFaceAuth(true, FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER);
                mKeyguardSecurityCallback.userActivity();
                showMessage(null, null);
            }
            if (mUpdateMonitor.isFaceEnrolled()) {
                mUpdateMonitor.requestActiveUnlock(
                        ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT,
                        "swipeUpOnBouncer");
            }
        }
    };
    private ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onThemeChanged() {
                    reloadColors();
                }

                @Override
                public void onUiModeChanged() {
                    reloadColors();
                }
            };
    private boolean mBouncerVisible = false;
    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onDevicePolicyManagerStateChanged() {
                    showPrimarySecurityScreen(false);
                }

                @Override
                public void onBiometricRunningStateChanged(boolean running,
                        BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == FINGERPRINT) {
                        updateSideFpsVisibility();
                    }
                }

                @Override
                public void onStrongAuthStateChanged(int userId) {
                    updateSideFpsVisibility();
                }
            };

    private KeyguardSecurityContainerController(KeyguardSecurityContainer view,
            AdminSecondaryLockScreenController.Factory adminSecondaryLockScreenControllerFactory,
            LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardSecurityModel keyguardSecurityModel,
            MetricsLogger metricsLogger,
            UiEventLogger uiEventLogger,
            KeyguardStateController keyguardStateController,
            SecurityCallback securityCallback,
            KeyguardSecurityViewFlipperController securityViewFlipperController,
            ConfigurationController configurationController,
            FalsingCollector falsingCollector,
            FalsingManager falsingManager,
            UserSwitcherController userSwitcherController,
            FeatureFlags featureFlags,
            GlobalSettings globalSettings,
            SessionTracker sessionTracker,
            Optional<SidefpsController> sidefpsController) {
        super(view);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = keyguardUpdateMonitor;
        mSecurityModel = keyguardSecurityModel;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mKeyguardStateController = keyguardStateController;
        mSecurityCallback = securityCallback;
        mSecurityViewFlipperController = securityViewFlipperController;
        mAdminSecondaryLockScreenController = adminSecondaryLockScreenControllerFactory.create(
                mKeyguardSecurityCallback);
        mConfigurationController = configurationController;
        mLastOrientation = getResources().getConfiguration().orientation;
        mFalsingCollector = falsingCollector;
        mFalsingManager = falsingManager;
        mUserSwitcherController = userSwitcherController;
        mFeatureFlags = featureFlags;
        mGlobalSettings = globalSettings;
        mSessionTracker = sessionTracker;
        mSidefpsController = sidefpsController;
    }

    @Override
    public void onInit() {
        mSecurityViewFlipperController.init();
    }

    @Override
    protected void onViewAttached() {
        mUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mView.setSwipeListener(mSwipeListener);
        mView.addMotionEventListener(mGlobalTouchListener);
        mConfigurationController.addCallback(mConfigurationListener);
        mUserSwitcherController.addUserSwitchCallback(mUserSwitchCallback);
    }

    @Override
    protected void onViewDetached() {
        mUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mConfigurationController.removeCallback(mConfigurationListener);
        mView.removeMotionEventListener(mGlobalTouchListener);
        mUserSwitcherController.removeUserSwitchCallback(mUserSwitchCallback);
    }

    /** */
    public void onPause() {
        mAdminSecondaryLockScreenController.hide();
        if (mCurrentSecurityMode != SecurityMode.None) {
            getCurrentSecurityController().onPause();
        }
        mView.onPause();
        // It might happen that onStartingToHide is not called when the device is locked while on
        // bouncer.
        setBouncerVisible(false);
    }

    private void updateSideFpsVisibility() {
        if (!mSidefpsController.isPresent()) {
            return;
        }
        if (mBouncerVisible
                && getResources().getBoolean(R.bool.config_show_sidefps_hint_on_bouncer)
                && mUpdateMonitor.isFingerprintDetectionRunning()
                && !mUpdateMonitor.userNeedsStrongAuth()) {
            mSidefpsController.get().show();
        } else {
            mSidefpsController.get().hide();
        }
    }

    /**
     * Shows the primary security screen for the user. This will be either the multi-selector
     * or the user's security method.
     * @param turningOff true if the device is being turned off
     */
    public void showPrimarySecurityScreen(boolean turningOff) {
        SecurityMode securityMode = whitelistIpcs(() -> mSecurityModel.getSecurityMode(
                KeyguardUpdateMonitor.getCurrentUser()));
        if (DEBUG) Log.v(TAG, "showPrimarySecurityScreen(turningOff=" + turningOff + ")");
        showSecurityScreen(securityMode);
    }

    @Override
    public void showPromptReason(int reason) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            if (reason != PROMPT_REASON_NONE) {
                Log.i(TAG, "Strong auth required, reason: " + reason);
            }
            getCurrentSecurityController().showPromptReason(reason);
        }
    }

    public void showMessage(CharSequence message, ColorStateList colorState) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            getCurrentSecurityController().showMessage(message, colorState);
        }
    }

    public SecurityMode getCurrentSecurityMode() {
        return mCurrentSecurityMode;
    }

    /**
     * Potentially dismiss the current security screen, after validating that all device
     * security has been unlocked. Otherwise show the next screen.
     */
    public void dismiss(boolean authenticated, int targetUserId,
            SecurityMode expectedSecurityMode) {
        mKeyguardSecurityCallback.dismiss(authenticated, targetUserId, expectedSecurityMode);
    }

    public void reset() {
        mView.reset();
        mSecurityViewFlipperController.reset();
    }

    public CharSequence getTitle() {
        return mView.getTitle();
    }

    @Override
    public void onResume(int reason) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            int state = SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SHOWN;
            if (mView.isSidedSecurityMode()) {
                state = mView.isSecurityLeftAligned()
                        ? SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SHOWN_LEFT
                        : SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SHOWN_RIGHT;
            }
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED, state);

            getCurrentSecurityController().onResume(reason);
            updateSideFpsVisibility();
        }
        mView.onResume(
                mSecurityModel.getSecurityMode(KeyguardUpdateMonitor.getCurrentUser()),
                mKeyguardStateController.isFaceAuthEnabled());
    }

    public void startAppearAnimation() {
        if (mCurrentSecurityMode != SecurityMode.None) {
            mView.startAppearAnimation(mCurrentSecurityMode);
            getCurrentSecurityController().startAppearAnimation();
        }
    }

    public boolean startDisappearAnimation(Runnable onFinishRunnable) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            mView.startDisappearAnimation(mCurrentSecurityMode);
            return getCurrentSecurityController().startDisappearAnimation(onFinishRunnable);
        }

        return false;
    }

    public void onStartingToHide() {
        if (mCurrentSecurityMode != SecurityMode.None) {
            getCurrentSecurityController().onStartingToHide();
        }
        setBouncerVisible(false);
    }

    /** Called when the bouncer changes visibility. */
    public void onBouncerVisibilityChanged(@View.Visibility int visibility) {
        setBouncerVisible(visibility == View.VISIBLE);
    }

    private void setBouncerVisible(boolean visible) {
        mBouncerVisible = visible;
        updateSideFpsVisibility();
    }

    /**
     * Shows the next security screen if there is one.
     * @param authenticated true if the user entered the correct authentication
     * @param targetUserId a user that needs to be the foreground user at the finish (if called)
     *     completion.
     * @param bypassSecondaryLockScreen true if the user is allowed to bypass the secondary
     *     secondary lock screen requirement, if any.
     * @param expectedSecurityMode SecurityMode that is invoking this request. SecurityMode.Invalid
     *      indicates that no check should be done
     * @return true if keyguard is done
     */
    public boolean showNextSecurityScreenOrFinish(boolean authenticated, int targetUserId,
            boolean bypassSecondaryLockScreen, SecurityMode expectedSecurityMode) {

        if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish(" + authenticated + ")");
        if (expectedSecurityMode != SecurityMode.Invalid
                && expectedSecurityMode != getCurrentSecurityMode()) {
            Log.w(TAG, "Attempted to invoke showNextSecurityScreenOrFinish with securityMode "
                    + expectedSecurityMode + ", but current mode is " + getCurrentSecurityMode());
            return false;
        }

        boolean finish = false;
        boolean strongAuth = false;
        int eventSubtype = -1;
        BouncerUiEvent uiEvent = BouncerUiEvent.UNKNOWN;
        if (mUpdateMonitor.getUserHasTrust(targetUserId)) {
            finish = true;
            eventSubtype = BOUNCER_DISMISS_EXTENDED_ACCESS;
            uiEvent = BouncerUiEvent.BOUNCER_DISMISS_EXTENDED_ACCESS;
        } else if (mUpdateMonitor.getUserUnlockedWithBiometric(targetUserId)) {
            finish = true;
            eventSubtype = BOUNCER_DISMISS_BIOMETRIC;
            uiEvent = BouncerUiEvent.BOUNCER_DISMISS_BIOMETRIC;
        } else if (SecurityMode.None == getCurrentSecurityMode()) {
            SecurityMode securityMode = mSecurityModel.getSecurityMode(targetUserId);
            if (SecurityMode.None == securityMode) {
                finish = true; // no security required
                eventSubtype = BOUNCER_DISMISS_NONE_SECURITY;
                uiEvent = BouncerUiEvent.BOUNCER_DISMISS_NONE_SECURITY;
            } else {
                showSecurityScreen(securityMode); // switch to the alternate security view
            }
        } else if (authenticated) {
            switch (getCurrentSecurityMode()) {
                case Pattern:
                case Password:
                case PIN:
                    strongAuth = true;
                    finish = true;
                    eventSubtype = BOUNCER_DISMISS_PASSWORD;
                    uiEvent = BouncerUiEvent.BOUNCER_DISMISS_PASSWORD;
                    break;

                case SimPin:
                case SimPuk:
                    // Shortcut for SIM PIN/PUK to go to directly to user's security screen or home
                    SecurityMode securityMode = mSecurityModel.getSecurityMode(targetUserId);
                    if (securityMode == SecurityMode.None && mLockPatternUtils.isLockScreenDisabled(
                            KeyguardUpdateMonitor.getCurrentUser())) {
                        finish = true;
                        eventSubtype = BOUNCER_DISMISS_SIM;
                        uiEvent = BouncerUiEvent.BOUNCER_DISMISS_SIM;
                    } else {
                        showSecurityScreen(securityMode);
                    }
                    break;

                default:
                    Log.v(TAG, "Bad security screen " + getCurrentSecurityMode()
                            + ", fail safe");
                    showPrimarySecurityScreen(false);
                    break;
            }
        }
        // Check for device admin specified additional security measures.
        if (finish && !bypassSecondaryLockScreen) {
            Intent secondaryLockscreenIntent =
                    mUpdateMonitor.getSecondaryLockscreenRequirement(targetUserId);
            if (secondaryLockscreenIntent != null) {
                mAdminSecondaryLockScreenController.show(secondaryLockscreenIntent);
                return false;
            }
        }
        if (eventSubtype != -1) {
            mMetricsLogger.write(new LogMaker(MetricsProto.MetricsEvent.BOUNCER)
                    .setType(MetricsProto.MetricsEvent.TYPE_DISMISS).setSubtype(eventSubtype));
        }
        if (uiEvent != BouncerUiEvent.UNKNOWN) {
            mUiEventLogger.log(uiEvent, getSessionId());
        }
        if (finish) {
            mSecurityCallback.finish(strongAuth, targetUserId);
        }
        return finish;
    }

    public boolean needsInput() {
        return getCurrentSecurityController().needsInput();
    }

    /**
     * Switches to the given security view unless it's already being shown, in which case
     * this is a no-op.
     *
     * @param securityMode
     */
    @VisibleForTesting
    void showSecurityScreen(SecurityMode securityMode) {
        if (DEBUG) Log.d(TAG, "showSecurityScreen(" + securityMode + ")");

        if (securityMode == SecurityMode.Invalid || securityMode == mCurrentSecurityMode) {
            return;
        }

        KeyguardInputViewController<KeyguardInputView> oldView = getCurrentSecurityController();

        // Emulate Activity life cycle
        if (oldView != null) {
            oldView.onPause();
        }

        KeyguardInputViewController<KeyguardInputView> newView = changeSecurityMode(securityMode);
        if (newView != null) {
            newView.onResume(KeyguardSecurityView.VIEW_REVEALED);
            mSecurityViewFlipperController.show(newView);
            configureMode();
        }

        mSecurityCallback.onSecurityModeChanged(
                securityMode, newView != null && newView.needsInput());
    }

    /**
     * Returns whether the given security view should be used in a "one handed" way. This can be
     * used to change how the security view is drawn (e.g. take up less of the screen, and align to
     * one side).
     */
    private boolean canUseOneHandedBouncer() {
        if (!(mCurrentSecurityMode == SecurityMode.Pattern
                || mCurrentSecurityMode == SecurityMode.PIN)) {
            return false;
        }

        return getResources().getBoolean(R.bool.can_use_one_handed_bouncer);
    }

    private boolean canDisplayUserSwitcher() {
        return mFeatureFlags.isEnabled(Flags.BOUNCER_USER_SWITCHER);
    }

    private void configureMode() {
        boolean useSimSecurity = mCurrentSecurityMode == SecurityMode.SimPin
                || mCurrentSecurityMode == SecurityMode.SimPuk;
        int mode = KeyguardSecurityContainer.MODE_DEFAULT;
        if (canDisplayUserSwitcher() && !useSimSecurity) {
            mode = KeyguardSecurityContainer.MODE_USER_SWITCHER;
        } else if (canUseOneHandedBouncer()) {
            mode = KeyguardSecurityContainer.MODE_ONE_HANDED;
        }

        mView.initMode(mode, mGlobalSettings, mFalsingManager, mUserSwitcherController);
    }

    public void reportFailedUnlockAttempt(int userId, int timeoutMs) {
        // +1 for this time
        final int failedAttempts = mLockPatternUtils.getCurrentFailedPasswordAttempts(userId) + 1;

        if (DEBUG) Log.d(TAG, "reportFailedPatternAttempt: #" + failedAttempts);

        final DevicePolicyManager dpm = mLockPatternUtils.getDevicePolicyManager();
        final int failedAttemptsBeforeWipe =
                dpm.getMaximumFailedPasswordsForWipe(null, userId);

        final int remainingBeforeWipe = failedAttemptsBeforeWipe > 0
                ? (failedAttemptsBeforeWipe - failedAttempts)
                : Integer.MAX_VALUE; // because DPM returns 0 if no restriction
        if (remainingBeforeWipe < LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE) {
            // The user has installed a DevicePolicyManager that requests a user/profile to be wiped
            // N attempts. Once we get below the grace period, we post this dialog every time as a
            // clear warning until the deletion fires.
            // Check which profile has the strictest policy for failed password attempts
            final int expiringUser = dpm.getProfileWithMinimumFailedPasswordsForWipe(userId);
            int userType = USER_TYPE_PRIMARY;
            if (expiringUser == userId) {
                // TODO: http://b/23522538
                if (expiringUser != UserHandle.USER_SYSTEM) {
                    userType = USER_TYPE_SECONDARY_USER;
                }
            } else if (expiringUser != UserHandle.USER_NULL) {
                userType = USER_TYPE_WORK_PROFILE;
            } // If USER_NULL, which shouldn't happen, leave it as USER_TYPE_PRIMARY
            if (remainingBeforeWipe > 0) {
                mView.showAlmostAtWipeDialog(failedAttempts, remainingBeforeWipe, userType);
            } else {
                // Too many attempts. The device will be wiped shortly.
                Slog.i(TAG, "Too many unlock attempts; user " + expiringUser + " will be wiped!");
                mView.showWipeDialog(failedAttempts, userType);
            }
        }
        mLockPatternUtils.reportFailedPasswordAttempt(userId);
        if (timeoutMs > 0) {
            mLockPatternUtils.reportPasswordLockout(timeoutMs, userId);
            mView.showTimeoutDialog(userId, timeoutMs, mLockPatternUtils,
                    mSecurityModel.getSecurityMode(userId));
        }
    }

    private KeyguardInputViewController<KeyguardInputView> getCurrentSecurityController() {
        return mSecurityViewFlipperController
                .getSecurityView(mCurrentSecurityMode, mKeyguardSecurityCallback);
    }

    private KeyguardInputViewController<KeyguardInputView> changeSecurityMode(
            SecurityMode securityMode) {
        mCurrentSecurityMode = securityMode;
        return getCurrentSecurityController();
    }

    /**
     * Apply keyguard configuration from the currently active resources. This can be called when the
     * device configuration changes, to re-apply some resources that are qualified on the device
     * configuration.
     */
    public void updateResources() {
        int newOrientation = getResources().getConfiguration().orientation;
        if (newOrientation != mLastOrientation) {
            mLastOrientation = newOrientation;
            configureMode();
        }
    }

    private @Nullable InstanceId getSessionId() {
        return mSessionTracker.getSessionId(SESSION_KEYGUARD);
    }

    /** Update keyguard position based on a tapped X coordinate. */
    public void updateKeyguardPosition(float x) {
        mView.updatePositionByTouchX(x);
    }

    private void reloadColors() {
        mSecurityViewFlipperController.reloadColors();
        mView.reloadColors();
    }

    static class Factory {

        private final KeyguardSecurityContainer mView;
        private final AdminSecondaryLockScreenController.Factory
                mAdminSecondaryLockScreenControllerFactory;
        private final LockPatternUtils mLockPatternUtils;
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final KeyguardSecurityModel mKeyguardSecurityModel;
        private final MetricsLogger mMetricsLogger;
        private final UiEventLogger mUiEventLogger;
        private final KeyguardStateController mKeyguardStateController;
        private final KeyguardSecurityViewFlipperController mSecurityViewFlipperController;
        private final ConfigurationController mConfigurationController;
        private final FalsingCollector mFalsingCollector;
        private final FalsingManager mFalsingManager;
        private final GlobalSettings mGlobalSettings;
        private final FeatureFlags mFeatureFlags;
        private final UserSwitcherController mUserSwitcherController;
        private final SessionTracker mSessionTracker;
        private final Optional<SidefpsController> mSidefpsController;

        @Inject
        Factory(KeyguardSecurityContainer view,
                AdminSecondaryLockScreenController.Factory
                        adminSecondaryLockScreenControllerFactory,
                LockPatternUtils lockPatternUtils,
                KeyguardUpdateMonitor keyguardUpdateMonitor,
                KeyguardSecurityModel keyguardSecurityModel,
                MetricsLogger metricsLogger,
                UiEventLogger uiEventLogger,
                KeyguardStateController keyguardStateController,
                KeyguardSecurityViewFlipperController securityViewFlipperController,
                ConfigurationController configurationController,
                FalsingCollector falsingCollector,
                FalsingManager falsingManager,
                UserSwitcherController userSwitcherController,
                FeatureFlags featureFlags,
                GlobalSettings globalSettings,
                SessionTracker sessionTracker,
                Optional<SidefpsController> sidefpsController) {
            mView = view;
            mAdminSecondaryLockScreenControllerFactory = adminSecondaryLockScreenControllerFactory;
            mLockPatternUtils = lockPatternUtils;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mKeyguardSecurityModel = keyguardSecurityModel;
            mMetricsLogger = metricsLogger;
            mUiEventLogger = uiEventLogger;
            mKeyguardStateController = keyguardStateController;
            mSecurityViewFlipperController = securityViewFlipperController;
            mConfigurationController = configurationController;
            mFalsingCollector = falsingCollector;
            mFalsingManager = falsingManager;
            mFeatureFlags = featureFlags;
            mGlobalSettings = globalSettings;
            mUserSwitcherController = userSwitcherController;
            mSessionTracker = sessionTracker;
            mSidefpsController = sidefpsController;
        }

        public KeyguardSecurityContainerController create(
                SecurityCallback securityCallback) {
            return new KeyguardSecurityContainerController(mView,
                    mAdminSecondaryLockScreenControllerFactory, mLockPatternUtils,
                    mKeyguardUpdateMonitor, mKeyguardSecurityModel, mMetricsLogger, mUiEventLogger,
                    mKeyguardStateController, securityCallback, mSecurityViewFlipperController,
                    mConfigurationController, mFalsingCollector, mFalsingManager,
                    mUserSwitcherController, mFeatureFlags, mGlobalSettings, mSessionTracker,
                    mSidefpsController);
        }
    }
}
