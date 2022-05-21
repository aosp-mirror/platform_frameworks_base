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

import android.app.ActivityManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.SystemClock;
import android.service.trust.TrustAgentService;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.MathUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.android.keyguard.KeyguardSecurityContainer.SecurityCallback;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.dagger.KeyguardBouncerScope;
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.util.ViewController;

import java.io.File;

import javax.inject.Inject;

/** Controller for a {@link KeyguardHostView}. */
@KeyguardBouncerScope
public class KeyguardHostViewController extends ViewController<KeyguardHostView> {
    private static final String TAG = "KeyguardViewBase";
    public static final boolean DEBUG = KeyguardConstants.DEBUG;
    // Whether the volume keys should be handled by keyguard. If true, then
    // they will be handled here for specific media types such as music, otherwise
    // the audio service will bring up the volume dialog.
    private static final boolean KEYGUARD_MANAGES_VOLUME = false;

    private static final String ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key";

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardSecurityContainerController mKeyguardSecurityContainerController;
    private final TelephonyManager mTelephonyManager;
    private final ViewMediatorCallback mViewMediatorCallback;
    private final AudioManager mAudioManager;

    private ActivityStarter.OnDismissAction mDismissAction;
    private Runnable mCancelAction;
    private int mTranslationY;

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTrustGrantedWithFlags(int flags, int userId) {
                    if (userId != KeyguardUpdateMonitor.getCurrentUser()) return;
                    boolean bouncerVisible = mView.isVisibleToUser();
                    boolean temporaryAndRenewable =
                            (flags & TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE)
                            != 0;
                    boolean initiatedByUser =
                            (flags & TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER) != 0;
                    boolean dismissKeyguard =
                            (flags & TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD) != 0;

                    if (initiatedByUser || dismissKeyguard) {
                        if ((mViewMediatorCallback.isScreenOn() || temporaryAndRenewable)
                                && (bouncerVisible || dismissKeyguard)) {
                            if (!bouncerVisible) {
                                // The trust agent dismissed the keyguard without the user proving
                                // that they are present (by swiping up to show the bouncer). That's
                                // fine if the user proved presence via some other way to the trust
                                //agent.
                                Log.i(TAG, "TrustAgent dismissed Keyguard.");
                            }
                            mSecurityCallback.dismiss(false /* authenticated */, userId,
                                    /* bypassSecondaryLockScreen */ false);
                        } else {
                            mViewMediatorCallback.playTrustedSound();
                        }
                    }
                }
            };

    private final SecurityCallback mSecurityCallback = new SecurityCallback() {

        @Override
        public boolean dismiss(boolean authenticated, int targetUserId,
                boolean bypassSecondaryLockScreen) {
            return mKeyguardSecurityContainerController.showNextSecurityScreenOrFinish(
                    authenticated, targetUserId, bypassSecondaryLockScreen);
        }

        @Override
        public void userActivity() {
            mViewMediatorCallback.userActivity();
        }

        @Override
        public void onSecurityModeChanged(SecurityMode securityMode, boolean needsInput) {
            mViewMediatorCallback.setNeedsInput(needsInput);
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
            // If there's a pending runnable because the user interacted with a widget
            // and we're leaving keyguard, then run it.
            boolean deferKeyguardDone = false;
            if (mDismissAction != null) {
                deferKeyguardDone = mDismissAction.onDismiss();
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
        public void reset() {
            mViewMediatorCallback.resetKeyguard();
        }

        @Override
        public void onCancelClicked() {
            mViewMediatorCallback.onCancelClicked();
        }
    };

    private OnKeyListener mOnKeyListener = (v, keyCode, event) -> interceptMediaKey(event);

    @Inject
    public KeyguardHostViewController(KeyguardHostView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            AudioManager audioManager,
            TelephonyManager telephonyManager,
            ViewMediatorCallback viewMediatorCallback,
            KeyguardSecurityContainerController.Factory
                    keyguardSecurityContainerControllerFactory) {
        super(view);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mAudioManager = audioManager;
        mTelephonyManager = telephonyManager;
        mViewMediatorCallback = viewMediatorCallback;
        mKeyguardSecurityContainerController = keyguardSecurityContainerControllerFactory.create(
                mSecurityCallback);
    }

    /** Initialize the Controller. */
    public void onInit() {
        mKeyguardSecurityContainerController.init();
        updateResources();
    }

    @Override
    protected void onViewAttached() {
        mView.setViewMediatorCallback(mViewMediatorCallback);
        // Update ViewMediator with the current input method requirements
        mViewMediatorCallback.setNeedsInput(mKeyguardSecurityContainerController.needsInput());
        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
        mView.setOnKeyListener(mOnKeyListener);
        mKeyguardSecurityContainerController.showPrimarySecurityScreen(false);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardUpdateMonitor.removeCallback(mUpdateCallback);
        mView.setOnKeyListener(null);
    }

     /** Called before this view is being removed. */
    public void cleanUp() {
        mKeyguardSecurityContainerController.onPause();
    }

    public void resetSecurityContainer() {
        mKeyguardSecurityContainerController.reset();
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     * @param targetUserId a user that needs to be the foreground user at the dismissal completion.
     * @return True if the keyguard is done.
     */
    public boolean dismiss(int targetUserId) {
        return mSecurityCallback.dismiss(false, targetUserId, false);
    }

    /**
     * Called when the Keyguard is actively shown on the screen.
     */
    public void onResume() {
        if (DEBUG) Log.d(TAG, "screen on, instance " + Integer.toHexString(hashCode()));
        mKeyguardSecurityContainerController.onResume(KeyguardSecurityView.SCREEN_ON);
        mView.requestFocus();
    }

    public CharSequence getAccessibilityTitleForCurrentMode() {
        return mKeyguardSecurityContainerController.getTitle();
    }

    /**
     * Starts the animation when the Keyguard gets shown.
     */
    public void appear(int statusBarHeight) {
        // We might still be collapsed and the view didn't have time to layout yet or still
        // be small, let's wait on the predraw to do the animation in that case.
        if (mView.getHeight() != 0 && mView.getHeight() != statusBarHeight) {
            mKeyguardSecurityContainerController.startAppearAnimation();
        } else {
            mView.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            mView.getViewTreeObserver().removeOnPreDrawListener(this);
                            mKeyguardSecurityContainerController.startAppearAnimation();
                            return true;
                        }
                    });
            mView.requestLayout();
        }
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
    public void showPromptReason(int reason) {
        mKeyguardSecurityContainerController.showPromptReason(reason);
    }

    public void showMessage(CharSequence message, ColorStateList colorState) {
        mKeyguardSecurityContainerController.showMessage(message, colorState);
    }

    public void showErrorMessage(CharSequence customMessage) {
        showMessage(customMessage, Utils.getColorError(mView.getContext()));
    }

    /**
     * Sets an action to run when keyguard finishes.
     *
     * @param action
     */
    public void setOnDismissAction(ActivityStarter.OnDismissAction action, Runnable cancelAction) {
        if (mCancelAction != null) {
            mCancelAction.run();
            mCancelAction = null;
        }
        mDismissAction = action;
        mCancelAction = cancelAction;
    }

    public void cancelDismissAction() {
        setOnDismissAction(null, null);
    }

    public void startDisappearAnimation(Runnable finishRunnable) {
        if (!mKeyguardSecurityContainerController.startDisappearAnimation(finishRunnable)
                && finishRunnable != null) {
            finishRunnable.run();
        }
    }

    /**
     * Called when the Keyguard is not actively shown anymore on the screen.
     */
    public void onPause() {
        if (DEBUG) {
            Log.d(TAG, String.format("screen off, instance %s at %s",
                    Integer.toHexString(hashCode()), SystemClock.uptimeMillis()));
        }
        mKeyguardSecurityContainerController.showPrimarySecurityScreen(true);
        mKeyguardSecurityContainerController.onPause();
        mView.clearFocus();
    }

    /**
     * Called when the view needs to be shown.
     */
    public void showPrimarySecurityScreen() {
        if (DEBUG) Log.d(TAG, "show()");
        mKeyguardSecurityContainerController.showPrimarySecurityScreen(false);
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

    /**
     * When bouncer was visible and is starting to become hidden.
     */
    public void onStartingToHide() {
        mKeyguardSecurityContainerController.onStartingToHide();
    }

    public boolean hasDismissActions() {
        return mDismissAction != null || mCancelAction != null;
    }

    public SecurityMode getCurrentSecurityMode() {
        return mKeyguardSecurityContainerController.getCurrentSecurityMode();
    }

    public int getTop() {
        int top = mView.getTop();
        // The password view has an extra top padding that should be ignored.
        if (getCurrentSecurityMode() == SecurityMode.Password) {
            View messageArea = mView.findViewById(R.id.keyguard_message_area);
            top += messageArea.getTop();
        }
        return top;
    }

    public boolean handleBackKey() {
        if (mKeyguardSecurityContainerController.getCurrentSecurityMode()
                != SecurityMode.None) {
            mKeyguardSecurityContainerController.dismiss(
                    false, KeyguardUpdateMonitor.getCurrentUser());
            return true;
        }
        return false;
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
     * @return true if the current bouncer is password
     */
    public boolean dispatchBackKeyEventPreIme() {
        if (mKeyguardSecurityContainerController.getCurrentSecurityMode()
                == SecurityMode.Password) {
            return true;
        }
        return false;
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
                    if (mTelephonyManager != null &&
                            mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                        return true;  // suppress key event
                    }
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

    public void finish(boolean strongAuth, int currentUser) {
        mSecurityCallback.finish(strongAuth, currentUser);
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

        if (mKeyguardSecurityContainerController != null) {
            mKeyguardSecurityContainerController.updateResources();
        }
    }

    /** Update keyguard position based on a tapped X coordinate. */
    public void updateKeyguardPosition(float x) {
        if (mKeyguardSecurityContainerController != null) {
            mKeyguardSecurityContainerController.updateKeyguardPosition(x);
        }
    }
}
