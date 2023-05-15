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

import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_BIOMETRIC;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_EXTENDED_ACCESS;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_NONE_SECURITY;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_PASSWORD;
import static com.android.keyguard.KeyguardSecurityContainer.BOUNCER_DISMISS_SIM;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_PRIMARY;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_SECONDARY_USER;
import static com.android.keyguard.KeyguardSecurityContainer.USER_TYPE_WORK_PROFILE;
import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricOverlayConstants;
import android.media.AudioManager;
import android.metrics.LogMaker;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.window.OnBackAnimationCallback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityContainer.BouncerUiEvent;
import com.android.keyguard.KeyguardSecurityContainer.SwipeListener;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.biometrics.SideFpsController;
import com.android.systemui.biometrics.SideFpsUiRequestSource;
import com.android.systemui.classifier.FalsingA11yDelegate;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.GlobalSettings;

import java.io.File;
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
    private final ConfigurationController mConfigurationController;
    private final FalsingCollector mFalsingCollector;
    private final FalsingManager mFalsingManager;
    private final UserSwitcherController mUserSwitcherController;
    private final GlobalSettings mGlobalSettings;
    private final FeatureFlags mFeatureFlags;
    private final SessionTracker mSessionTracker;
    private final Optional<SideFpsController> mSideFpsController;
    private final FalsingA11yDelegate mFalsingA11yDelegate;
    private final KeyguardFaceAuthInteractor mKeyguardFaceAuthInteractor;
    private int mTranslationY;
    // Whether the volume keys should be handled by keyguard. If true, then
    // they will be handled here for specific media types such as music, otherwise
    // the audio service will bring up the volume dialog.
    private static final boolean KEYGUARD_MANAGES_VOLUME = false;

    private static final String ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key";

    private final TelephonyManager mTelephonyManager;
    private final ViewMediatorCallback mViewMediatorCallback;
    private final AudioManager mAudioManager;
    private View.OnKeyListener mOnKeyListener = (v, keyCode, event) -> interceptMediaKey(event);
    private ActivityStarter.OnDismissAction mDismissAction;
    private Runnable mCancelAction;
    private boolean mWillRunDismissFromKeyguard;

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

        @Override
        public void onUserInput() {
            mKeyguardFaceAuthInteractor.onPrimaryBouncerUserInput();
            mUpdateMonitor.cancelFaceAuth();
        }

        @Override
        public void dismiss(boolean authenticated, int targetId,
                SecurityMode expectedSecurityMode) {
            dismiss(authenticated, targetId, /* bypassSecondaryLockScreen */ false,
                    expectedSecurityMode);
        }

        @Override
        public boolean dismiss(boolean authenticated, int targetId,
                boolean bypassSecondaryLockScreen, SecurityMode expectedSecurityMode) {
            return showNextSecurityScreenOrFinish(
                    authenticated, targetId, bypassSecondaryLockScreen, expectedSecurityMode);
        }

        @Override
        public void userActivity() {
            mViewMediatorCallback.userActivity();
        }

        @Override
        public boolean isVerifyUnlockOnly() {
            return false;
        }

        @Override
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

        @Override
        public void reset() {
            mViewMediatorCallback.resetKeyguard();
        }

        @Override
        public void onCancelClicked() {
            mViewMediatorCallback.onCancelClicked();
        }

        /**
         * Authentication has happened and it's time to dismiss keyguard. This function
         * should clean up and inform KeyguardViewMediator.
         *
         * @param strongAuth whether the user has authenticated with strong authentication like
         *                   pattern, password or PIN but not by trust agents or fingerprint
         * @param targetUserId a user that needs to be the foreground user at the dismissal
         *                    completion.
         */
        @Override
        public void finish(boolean strongAuth, int targetUserId) {
            if (!mKeyguardStateController.canDismissLockScreen() && !strongAuth) {
                Log.e(TAG,
                        "Tried to dismiss keyguard when lockscreen is not dismissible and user "
                                + "was not authenticated with a primary security method "
                                + "(pin/password/pattern).");
                return;
            }
            // If there's a pending runnable because the user interacted with a widget
            // and we're leaving keyguard, then run it.
            boolean deferKeyguardDone = false;
            mWillRunDismissFromKeyguard = false;
            if (mDismissAction != null) {
                deferKeyguardDone = mDismissAction.onDismiss();
                mWillRunDismissFromKeyguard = mDismissAction.willRunAnimationOnKeyguard();
                mDismissAction = null;
                mCancelAction = null;
            }
            if (mViewMediatorCallback != null) {
                if (deferKeyguardDone) {
                    mViewMediatorCallback.keyguardDonePending(strongAuth, targetUserId);
                } else {
                    mViewMediatorCallback.keyguardDone(strongAuth, targetUserId);
                }
            }
        }

        @Override
        public void onSecurityModeChanged(SecurityMode securityMode, boolean needsInput) {
            mViewMediatorCallback.setNeedsInput(needsInput);
        }
    };


    private final SwipeListener mSwipeListener = new SwipeListener() {
        @Override
        public void onSwipeUp() {
            if (!mUpdateMonitor.isFaceDetectionRunning()) {
                mKeyguardFaceAuthInteractor.onSwipeUpOnBouncer();
                boolean didFaceAuthRun = mUpdateMonitor.requestFaceAuth(
                        FaceAuthApiRequestReason.SWIPE_UP_ON_BOUNCER);
                mKeyguardSecurityCallback.userActivity();
                if (didFaceAuthRun) {
                    showMessage(/* message= */ null, /* colorState= */ null, /* animated= */ true);
                }
            }
            if (mUpdateMonitor.isFaceEnrolled()) {
                mUpdateMonitor.requestActiveUnlock(
                        ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT,
                        "swipeUpOnBouncer");
            }
        }
    };
    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onThemeChanged() {
                    reloadColors();
                    reset();
                }

                @Override
                public void onUiModeChanged() {
                    reloadColors();
                }

                @Override
                public void onDensityOrFontScaleChanged() {
                    KeyguardSecurityContainerController.this.onDensityOrFontScaleChanged();
                }
            };
    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTrustGrantedForCurrentUser(
                        boolean dismissKeyguard,
                        boolean newlyUnlocked,
                        TrustGrantFlags flags,
                        String message
                ) {
                    if (dismissKeyguard) {
                        if (!mView.isVisibleToUser()) {
                            // The trust agent dismissed the keyguard without the user proving
                            // that they are present (by swiping up to show the bouncer). That's
                            // fine if the user proved presence via some other way to the trust
                            // agent.
                            Log.i(TAG, "TrustAgent dismissed Keyguard.");
                        }
                        mKeyguardSecurityCallback.dismiss(
                                false /* authenticated */,
                                KeyguardUpdateMonitor.getCurrentUser(),
                                /* bypassSecondaryLockScreen */ false,
                                SecurityMode.Invalid
                        );
                    } else {
                        if (flags.isInitiatedByUser() || flags.dismissKeyguardRequested()) {
                            mViewMediatorCallback.playTrustedSound();
                        }
                    }
                }

                @Override
                public void onDevicePolicyManagerStateChanged() {
                    showPrimarySecurityScreen(false);
                }
            };

    @Inject
    public KeyguardSecurityContainerController(KeyguardSecurityContainer view,
            AdminSecondaryLockScreenController.Factory adminSecondaryLockScreenControllerFactory,
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
            Optional<SideFpsController> sideFpsController,
            FalsingA11yDelegate falsingA11yDelegate,
            TelephonyManager telephonyManager,
            ViewMediatorCallback viewMediatorCallback,
            AudioManager audioManager,
            KeyguardFaceAuthInteractor keyguardFaceAuthInteractor
    ) {
        super(view);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = keyguardUpdateMonitor;
        mSecurityModel = keyguardSecurityModel;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mKeyguardStateController = keyguardStateController;
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
        mSideFpsController = sideFpsController;
        mFalsingA11yDelegate = falsingA11yDelegate;
        mTelephonyManager = telephonyManager;
        mViewMediatorCallback = viewMediatorCallback;
        mAudioManager = audioManager;
        mKeyguardFaceAuthInteractor = keyguardFaceAuthInteractor;
    }

    @Override
    public void onInit() {
        mSecurityViewFlipperController.init();
        updateResources();
        configureMode();
    }

    @Override
    protected void onViewAttached() {
        mUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mView.setSwipeListener(mSwipeListener);
        mView.addMotionEventListener(mGlobalTouchListener);
        mConfigurationController.addCallback(mConfigurationListener);
        mUserSwitcherController.addUserSwitchCallback(mUserSwitchCallback);
        mView.setViewMediatorCallback(mViewMediatorCallback);
        // Update ViewMediator with the current input method requirements
        mViewMediatorCallback.setNeedsInput(needsInput());
        mView.setOnKeyListener(mOnKeyListener);
        showPrimarySecurityScreen(false);
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
        if (DEBUG) {
            Log.d(TAG, String.format("screen off, instance %s at %s",
                    Integer.toHexString(hashCode()), SystemClock.uptimeMillis()));
        }
        showPrimarySecurityScreen(true);
        mAdminSecondaryLockScreenController.hide();
        if (mCurrentSecurityMode != SecurityMode.None) {
            getCurrentSecurityController(controller -> controller.onPause());
        }
        mView.onPause();
        mView.clearFocus();
    }

    /**
     * Shows and hides the side finger print sensor animation.
     *
     * @param isVisible sets whether we show or hide the side fps animation
     */
    public void updateSideFpsVisibility(boolean isVisible) {
        if (!mSideFpsController.isPresent()) {
            return;
        }

        if (isVisible) {
            mSideFpsController.get().show(
                    SideFpsUiRequestSource.PRIMARY_BOUNCER,
                    BiometricOverlayConstants.REASON_AUTH_KEYGUARD
            );
        } else {
            mSideFpsController.get().hide(SideFpsUiRequestSource.PRIMARY_BOUNCER);
        }
    }

    /**
     * Shows the primary security screen for the user. This will be either the multi-selector
     * or the user's security method.
     * @param turningOff true if the device is being turned off
     */
    public void showPrimarySecurityScreen(boolean turningOff) {
        if (DEBUG) Log.d(TAG, "show()");
        SecurityMode securityMode = whitelistIpcs(() -> mSecurityModel.getSecurityMode(
                KeyguardUpdateMonitor.getCurrentUser()));
        if (DEBUG) Log.v(TAG, "showPrimarySecurityScreen(turningOff=" + turningOff + ")");
        showSecurityScreen(securityMode);
    }

    /**
     * Show a string explaining why the security view needs to be solved.
     *
     * @param reason a flag indicating which string should be shown, see
     *               {@link KeyguardSecurityView#PROMPT_REASON_NONE},
     *               {@link KeyguardSecurityView#PROMPT_REASON_RESTART},
     *               {@link KeyguardSecurityView#PROMPT_REASON_TIMEOUT}, and
     *               {@link KeyguardSecurityView#PROMPT_REASON_PREPARE_FOR_UPDATE}.
     */
    @Override
    public void showPromptReason(int reason) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            if (reason != PROMPT_REASON_NONE) {
                Log.i(TAG, "Strong auth required, reason: " + reason);
            }
            getCurrentSecurityController(controller -> controller.showPromptReason(reason));
        }
    }

    /** Set message of bouncer title. */
    public void showMessage(CharSequence message, ColorStateList colorState, boolean animated) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            getCurrentSecurityController(
                    controller -> controller.showMessage(message, colorState, animated));
        }
    }

    /**
     * Sets an action to run when keyguard finishes.
     *
     * @param action callback to be invoked when keyguard disappear animation completes.
     */
    public void setOnDismissAction(ActivityStarter.OnDismissAction action, Runnable cancelAction) {
        if (mCancelAction != null) {
            mCancelAction.run();
            mCancelAction = null;
        }
        mDismissAction = action;
        mCancelAction = cancelAction;
    }

    /**
     * @return whether dismiss action or cancel action has been set.
     */
    public boolean hasDismissActions() {
        return mDismissAction != null || mCancelAction != null;
    }

    /**
     * @return will the dismissal run from the keyguard layout (instead of from bouncer)
     */
    public boolean willRunDismissFromKeyguard() {
        return mWillRunDismissFromKeyguard;
    }

    /**
     * Remove any dismiss action or cancel action that was set.
     */
    public void cancelDismissAction() {
        setOnDismissAction(null, null);
    }

    /**
     * Potentially dismiss the current security screen, after validating that all device
     * security has been unlocked. Otherwise show the next screen.
     */
    public void dismiss(boolean authenticated, int targetUserId,
            SecurityMode expectedSecurityMode) {
        mKeyguardSecurityCallback.dismiss(authenticated, targetUserId, expectedSecurityMode);
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     * @param targetUserId a user that needs to be the foreground user at the dismissal completion.
     * @return True if the keyguard is done.
     */
    public boolean dismiss(int targetUserId) {
        return mKeyguardSecurityCallback.dismiss(false, targetUserId, false,
                getCurrentSecurityMode());
    }

    public SecurityMode getCurrentSecurityMode() {
        return mCurrentSecurityMode;
    }

    /**
     * @return the top of the corresponding view.
     */
    public int getTop() {
        int top = mView.getTop();
        // The password view has an extra top padding that should be ignored.
        if (getCurrentSecurityMode() == SecurityMode.Password) {
            View messageArea = mView.findViewById(R.id.keyguard_message_area);
            top += messageArea.getTop();
        }
        return top;
    }

    /** Set true if the view can be interacted with */
    public void setInteractable(boolean isInteractable) {
        mView.setInteractable(isInteractable);
    }

    /**
     * Dismiss keyguard due to a user unlock event.
     */
    public void finish(boolean strongAuth, int currentUser) {
        mKeyguardSecurityCallback.finish(strongAuth, currentUser);
    }

    /**
     * @return the text of the KeyguardMessageArea.
     */
    public CharSequence getTitle() {
        return mView.getTitle();
    }

    /**
     *  Resets the state of the views.
     */
    public void reset() {
        mView.reset();
        mSecurityViewFlipperController.reset();
    }

    @Override
    public void onResume(int reason) {
        if (DEBUG) Log.d(TAG, "screen on, instance " + Integer.toHexString(hashCode()));
        mView.requestFocus();
        if (mCurrentSecurityMode != SecurityMode.None) {
            int state = SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SHOWN;
            if (mView.isSidedSecurityMode()) {
                state = mView.isSecurityLeftAligned()
                        ? SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SHOWN_LEFT
                        : SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SHOWN_RIGHT;
            }
            SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED, state);


            getCurrentSecurityController(controller -> controller.onResume(reason));
        }
        mView.onResume(
                mSecurityModel.getSecurityMode(KeyguardUpdateMonitor.getCurrentUser()),
                mKeyguardStateController.isFaceAuthEnabled());
    }

    /** Sets an initial message that would override the default message */
    public void setInitialMessage() {
        CharSequence customMessage = mViewMediatorCallback.consumeCustomMessage();
        if (!TextUtils.isEmpty(customMessage)) {
            showMessage(customMessage, /* colorState= */ null, /* animated= */ false);
            return;
        }
        showPromptReason(mViewMediatorCallback.getBouncerPromptReason());
    }

    /**
     * Show the bouncer and start appear animations.
     *
     */
    public void appear() {
        // We might still be collapsed and the view didn't have time to layout yet or still
        // be small, let's wait on the predraw to do the animation in that case.
        mView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mView.getViewTreeObserver().removeOnPreDrawListener(this);
                        startAppearAnimation();
                        return true;
                    }
                });
        mView.requestLayout();
    }

    public void startAppearAnimation() {
        if (mCurrentSecurityMode != SecurityMode.None) {
            setAlpha(1f);
            mView.startAppearAnimation(mCurrentSecurityMode);
            getCurrentSecurityController(controller -> controller.startAppearAnimation());
        }
    }

    /** Set the alpha of the security container view */
    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
    }

    public boolean startDisappearAnimation(Runnable onFinishRunnable) {
        if (mCurrentSecurityMode != SecurityMode.None) {
            mView.startDisappearAnimation(mCurrentSecurityMode);
            getCurrentSecurityController(
                    controller -> {
                        boolean didRunAnimation = controller.startDisappearAnimation(
                                onFinishRunnable);
                        if (!didRunAnimation && onFinishRunnable != null) {
                            onFinishRunnable.run();
                        }
                    });
        }
        return true;
    }

    public void onStartingToHide() {
        if (mCurrentSecurityMode != SecurityMode.None) {
            getCurrentSecurityController(controller -> controller.onStartingToHide());
        }
    }

    /** Called when the bouncer changes visibility. */
    public void onBouncerVisibilityChanged(boolean isVisible) {
        if (!isVisible) {
            mView.resetScale();
        }
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
                    if (securityMode == SecurityMode.None || mLockPatternUtils.isLockScreenDisabled(
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
            mKeyguardSecurityCallback.finish(strongAuth, targetUserId);
        }
        return finish;
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    /**
     * @return the {@link OnBackAnimationCallback} to animate this view during a back gesture.
     */
    @NonNull
    public OnBackAnimationCallback getBackCallback() {
        return mView.getBackCallback();
    }

    /**
     * @return whether we should dispatch the back key event before Ime.
     */
    public boolean dispatchBackKeyEventPreIme() {
        return getCurrentSecurityMode() == SecurityMode.Password;
    }

    /**
     * Allows the media keys to work when the keyguard is showing.
     * The media keys should be of no interest to the actual keyguard view(s),
     * so intercepting them here should not be of any harm.
     * @param event The key event
     * @return whether the event was consumed as a media key.
     */
    public boolean interceptMediaKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    /* Suppress PLAY/PAUSE toggle when phone is ringing or
                     * in-call to avoid music playback */
                    if (mTelephonyManager != null
                            && mTelephonyManager.getCallState()
                            != TelephonyManager.CALL_STATE_IDLE) {
                        return true;  // suppress key event
                    }
                    return false;
                case KeyEvent.KEYCODE_MUTE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_STOP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_RECORD:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                    handleMediaKeyEvent(event);
                    return true;
                }

                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE: {
                    if (KEYGUARD_MANAGES_VOLUME) {
                        // Volume buttons should only function for music (local or remote).
                        // TODO: Actually handle MUTE.
                        mAudioManager.adjustSuggestedStreamVolume(
                                keyCode == KeyEvent.KEYCODE_VOLUME_UP
                                        ? AudioManager.ADJUST_RAISE
                                        : AudioManager.ADJUST_LOWER /* direction */,
                                AudioManager.STREAM_MUSIC /* stream */, 0 /* flags */);
                        // Don't execute default volume behavior
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MUTE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_STOP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_RECORD:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                    handleMediaKeyEvent(event);
                    return true;
                }
            }
        }
        return false;
    }


    private void handleMediaKeyEvent(KeyEvent keyEvent) {
        mAudioManager.dispatchMediaKeyEvent(keyEvent);
    }

    /**
     * In general, we enable unlocking the insecure keyguard with the menu key. However, there are
     * some cases where we wish to disable it, notably when the menu button placement or technology
     * is prone to false positives.
     *
     * @return true if the menu key should be enabled
     */
    public boolean shouldEnableMenuKey() {
        final Resources res = mView.getResources();
        final boolean configDisabled = res.getBoolean(R.bool.config_disableMenuKeyInLockScreen);
        final boolean isTestHarness = ActivityManager.isRunningInTestHarness();
        final boolean fileOverride = (new File(ENABLE_MENU_KEY_FILE)).exists();
        return !configDisabled || isTestHarness || fileOverride;
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

        getCurrentSecurityController(oldView -> oldView.onPause());

        mCurrentSecurityMode = securityMode;

        getCurrentSecurityController(
                newView -> {
                    newView.onResume(KeyguardSecurityView.VIEW_REVEALED);
                    mSecurityViewFlipperController.show(newView);
                    configureMode();
                    mKeyguardSecurityCallback.onSecurityModeChanged(
                            securityMode, newView != null && newView.needsInput());

                });
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

        mView.initMode(mode, mGlobalSettings, mFalsingManager, mUserSwitcherController,
                () -> showMessage(getContext().getString(R.string.keyguard_unlock_to_continue),
                        /* colorState= */ null, /* animated= */ true), mFalsingA11yDelegate);
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

    private void getCurrentSecurityController(
            KeyguardSecurityViewFlipperController.OnViewInflatedCallback onViewInflatedCallback) {
        mSecurityViewFlipperController
                .getSecurityView(mCurrentSecurityMode, mKeyguardSecurityCallback,
                        onViewInflatedCallback);
    }

    /**
     * Apply keyguard configuration from the currently active resources. This can be called when the
     * device configuration changes, to re-apply some resources that are qualified on the device
     * configuration.
     */
    public void updateResources() {
        int gravity;

        Resources resources = mView.getResources();

        if (resources.getBoolean(R.bool.can_use_one_handed_bouncer)) {
            gravity = resources.getInteger(
                    R.integer.keyguard_host_view_one_handed_gravity);
        } else {
            gravity = resources.getInteger(R.integer.keyguard_host_view_gravity);
        }

        mTranslationY = resources
                .getDimensionPixelSize(R.dimen.keyguard_host_view_translation_y);
        // Android SysUI uses a FrameLayout as the top-level, but Auto uses RelativeLayout.
        // We're just changing the gravity here though (which can't be applied to RelativeLayout),
        // so only attempt the update if mView is inside a FrameLayout.
        if (mView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mView.getLayoutParams();
            if (lp.gravity != gravity) {
                lp.gravity = gravity;
                mView.setLayoutParams(lp);
            }
        }

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
        reinflateViewFlipper(controller -> mView.reloadColors());
    }

    /** Handles density or font scale changes. */
    private void onDensityOrFontScaleChanged() {
        reinflateViewFlipper(controller -> mView.onDensityOrFontScaleChanged());
    }

    /**
     * Reinflate the view flipper child view.
     */
    public void reinflateViewFlipper(
            KeyguardSecurityViewFlipperController.OnViewInflatedCallback onViewInflatedListener) {
        mSecurityViewFlipperController.clearViews();
        mSecurityViewFlipperController.asynchronouslyInflateView(mCurrentSecurityMode,
                mKeyguardSecurityCallback, onViewInflatedListener);
    }

    /**
     * Fades and translates in/out the security screen.
     * Fades in as expansion approaches 0.
     * Animation duration is between 0.33f and 0.67f of panel expansion fraction.
     * @param fraction amount of the screen that should show.
     */
    public void setExpansion(float fraction) {
        float scaledFraction = BouncerPanelExpansionCalculator.showBouncerProgress(fraction);
        mView.setAlpha(MathUtils.constrain(1 - scaledFraction, 0f, 1f));
        mView.setTranslationY(scaledFraction * mTranslationY);
    }
}
