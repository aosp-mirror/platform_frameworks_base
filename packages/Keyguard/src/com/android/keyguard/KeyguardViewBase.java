/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.media.IAudioService;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.keyguard.KeyguardSecurityContainer.SecurityCallback;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;

import java.io.File;

/**
 * Base class for keyguard view.  {@link #reset} is where you should
 * reset the state of your view.  Use the {@link KeyguardViewCallback} via
 * {@link #getCallback()} to send information back (such as poking the wake lock,
 * or finishing the keyguard).
 *
 * Handles intercepting of media keys that still work when the keyguard is
 * showing.
 */
public abstract class KeyguardViewBase extends FrameLayout implements SecurityCallback {

    private AudioManager mAudioManager;
    private TelephonyManager mTelephonyManager = null;
    protected ViewMediatorCallback mViewMediatorCallback;
    protected LockPatternUtils mLockPatternUtils;
    private OnDismissAction mDismissAction;

    // Whether the volume keys should be handled by keyguard. If true, then
    // they will be handled here for specific media types such as music, otherwise
    // the audio service will bring up the volume dialog.
    private static final boolean KEYGUARD_MANAGES_VOLUME = false;
    public static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardViewBase";

    private KeyguardSecurityContainer mSecurityContainer;

    public KeyguardViewBase(Context context) {
        this(context, null);
    }

    public KeyguardViewBase(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.keyguardDoneDrawing();
        }
    }

    /**
     * Sets an action to run when keyguard finishes.
     *
     * @param action
     */
    public void setOnDismissAction(OnDismissAction action) {
        mDismissAction = action;
    }

    @Override
    protected void onFinishInflate() {
        mSecurityContainer =
                (KeyguardSecurityContainer) findViewById(R.id.keyguard_security_container);
        mLockPatternUtils = new LockPatternUtils(mContext);
        mSecurityContainer.setLockPatternUtils(mLockPatternUtils);
        mSecurityContainer.setSecurityCallback(this);
        mSecurityContainer.showPrimarySecurityScreen(false);
        // mSecurityContainer.updateSecurityViews(false /* not bouncing */);
    }

    /**
     * Called when the view needs to be shown.
     */
    public void show() {
        if (DEBUG) Log.d(TAG, "show()");
        mSecurityContainer.showPrimarySecurityScreen(false);
    }

    /**
     *  Dismisses the keyguard by going to the next screen or making it gone.
     *
     *  @return True if the keyguard is done.
     */
    public boolean dismiss() {
        return dismiss(false);
    }

    protected void showBouncer(boolean show) {
        CharSequence what = getContext().getResources().getText(
                show ? R.string.keyguard_accessibility_show_bouncer
                        : R.string.keyguard_accessibility_hide_bouncer);
        announceForAccessibility(what);
        announceCurrentSecurityMethod();
    }

    public boolean handleBackKey() {
        if (mSecurityContainer.getCurrentSecuritySelection() == SecurityMode.Account) {
            // go back to primary screen
            mSecurityContainer.showPrimarySecurityScreen(false /*turningOff*/);
            return true;
        }
        if (mSecurityContainer.getCurrentSecuritySelection() != SecurityMode.None) {
            mSecurityContainer.dismiss(false);
            return true;
        }
        return false;
    }

    protected void announceCurrentSecurityMethod() {
        mSecurityContainer.announceCurrentSecurityMethod();
    }

    protected KeyguardSecurityContainer getSecurityContainer() {
        return mSecurityContainer;
    }

    @Override
    public boolean dismiss(boolean authenticated) {
        return mSecurityContainer.showNextSecurityScreenOrFinish(authenticated);
    }

    /**
     * Authentication has happened and it's time to dismiss keyguard. This function
     * should clean up and inform KeyguardViewMediator.
     */
    @Override
    public void finish() {
        // If the alternate unlock was suppressed, it can now be safely
        // enabled because the user has left keyguard.
        KeyguardUpdateMonitor.getInstance(mContext).setAlternateUnlockEnabled(true);

        // If there's a pending runnable because the user interacted with a widget
        // and we're leaving keyguard, then run it.
        boolean deferKeyguardDone = false;
        if (mDismissAction != null) {
            deferKeyguardDone = mDismissAction.onDismiss();
            mDismissAction = null;
        }
        if (mViewMediatorCallback != null) {
            if (deferKeyguardDone) {
                mViewMediatorCallback.keyguardDonePending();
            } else {
                mViewMediatorCallback.keyguardDone(true);
            }
        }
    }

    @Override
    public void onSecurityModeChanged(SecurityMode securityMode, boolean needsInput) {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.setNeedsInput(needsInput);
        }
    }

    public void userActivity() {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.userActivity();
        }
    }

    protected void onUserActivityTimeoutChanged() {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.onUserActivityTimeoutChanged();
        }
    }

    /**
     * Called when the Keyguard is not actively shown anymore on the screen.
     */
    public void onPause() {
        if (DEBUG) Log.d(TAG, String.format("screen off, instance %s at %s",
                Integer.toHexString(hashCode()), SystemClock.uptimeMillis()));
        // Once the screen turns off, we no longer consider this to be first boot and we want the
        // biometric unlock to start next time keyguard is shown.
        KeyguardUpdateMonitor.getInstance(mContext).setAlternateUnlockEnabled(true);
        mSecurityContainer.showPrimarySecurityScreen(true);
        mSecurityContainer.onPause();
        clearFocus();
    }

    /**
     * Called when the Keyguard is actively shown on the screen.
     */
    public void onResume() {
        if (DEBUG) Log.d(TAG, "screen on, instance " + Integer.toHexString(hashCode()));
        mSecurityContainer.showPrimarySecurityScreen(false);
        mSecurityContainer.onResume(KeyguardSecurityView.SCREEN_ON);
        requestFocus();
    }

    /**
     * Starts the animation when the Keyguard gets shown.
     */
    public void startAppearAnimation() {
        mSecurityContainer.startAppearAnimation();
    }

    public void startDisappearAnimation(Runnable finishRunnable) {
        if (!mSecurityContainer.startDisappearAnimation(finishRunnable) && finishRunnable != null) {
            finishRunnable.run();
        }
    }

    /**
     * Verify that the user can get past the keyguard securely.  This is called,
     * for example, when the phone disables the keyguard but then wants to launch
     * something else that requires secure access.
     *
     * The result will be propogated back via {@link KeyguardViewCallback#keyguardDone(boolean)}
     */
    public void verifyUnlock() {
        SecurityMode securityMode = mSecurityContainer.getSecurityMode();
        if (securityMode == KeyguardSecurityModel.SecurityMode.None) {
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.keyguardDone(true);
            }
        } else if (securityMode != KeyguardSecurityModel.SecurityMode.Pattern
                && securityMode != KeyguardSecurityModel.SecurityMode.PIN
                && securityMode != KeyguardSecurityModel.SecurityMode.Password) {
            // can only verify unlock when in pattern/password mode
            if (mViewMediatorCallback != null) {
                mViewMediatorCallback.keyguardDone(false);
            }
        } else {
            // otherwise, go to the unlock screen, see if they can verify it
            mSecurityContainer.verifyUnlock();
        }
    }

    /**
     * Called before this view is being removed.
     */
    abstract public void cleanUp();

    /**
     * Gets the desired user activity timeout in milliseconds, or -1 if the
     * default should be used.
     */
    abstract public long getUserActivityTimeout();

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (interceptMediaKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Allows the media keys to work when the keyguard is showing.
     * The media keys should be of no interest to the actual keyguard view(s),
     * so intercepting them here should not be of any harm.
     * @param event The key event
     * @return whether the event was consumed as a media key.
     */
    public boolean interceptMediaKey(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    /* Suppress PLAY/PAUSE toggle when phone is ringing or
                     * in-call to avoid music playback */
                    if (mTelephonyManager == null) {
                        mTelephonyManager = (TelephonyManager) getContext().getSystemService(
                                Context.TELEPHONY_SERVICE);
                    }
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
                        synchronized (this) {
                            if (mAudioManager == null) {
                                mAudioManager = (AudioManager) getContext().getSystemService(
                                        Context.AUDIO_SERVICE);
                            }
                        }
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
        synchronized (this) {
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) getContext().getSystemService(
                        Context.AUDIO_SERVICE);
            }
        }
        mAudioManager.dispatchMediaKeyEvent(keyEvent);
    }

    @Override
    public void dispatchSystemUiVisibilityChanged(int visibility) {
        super.dispatchSystemUiVisibilityChanged(visibility);

        if (!(mContext instanceof Activity)) {
            setSystemUiVisibility(STATUS_BAR_DISABLE_BACK);
        }
    }

    /**
     * In general, we enable unlocking the insecure keyguard with the menu key. However, there are
     * some cases where we wish to disable it, notably when the menu button placement or technology
     * is prone to false positives.
     *
     * @return true if the menu key should be enabled
     */
    private static final String ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key";
    private boolean shouldEnableMenuKey() {
        final Resources res = getResources();
        final boolean configDisabled = res.getBoolean(R.bool.config_disableMenuKeyInLockScreen);
        final boolean isTestHarness = ActivityManager.isRunningInTestHarness();
        final boolean fileOverride = (new File(ENABLE_MENU_KEY_FILE)).exists();
        return !configDisabled || isTestHarness || fileOverride;
    }

    public boolean handleMenuKey() {
        // The following enables the MENU key to work for testing automation
        if (shouldEnableMenuKey()) {
            dismiss();
            return true;
        }
        return false;
    }

    public void setViewMediatorCallback(ViewMediatorCallback viewMediatorCallback) {
        mViewMediatorCallback = viewMediatorCallback;
        // Update ViewMediator with the current input method requirements
        mViewMediatorCallback.setNeedsInput(mSecurityContainer.needsInput());
    }

    protected KeyguardActivityLauncher getActivityLauncher() {
        return mActivityLauncher;
    }

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {
        @Override
        Context getContext() {
            return mContext;
        }

        @Override
        void setOnDismissAction(OnDismissAction action) {
            KeyguardViewBase.this.setOnDismissAction(action);
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        @Override
        void requestDismissKeyguard() {
            KeyguardViewBase.this.dismiss(false);
        }
    };

    public void showAssistant() {
        final Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
          .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);

        if (intent == null) return;

        final ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                R.anim.keyguard_action_assist_enter, R.anim.keyguard_action_assist_exit,
                getHandler(), null);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mActivityLauncher.launchActivityWithAnimation(intent, false, opts.toBundle(), null, null);
    }

    public void launchCamera() {
        mActivityLauncher.launchCamera(getHandler(), null);
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
        mSecurityContainer.setLockPatternUtils(utils);
    }

    public SecurityMode getSecurityMode() {
        return mSecurityContainer.getSecurityMode();
    }

    protected abstract void onUserSwitching(boolean switching);

    protected abstract void onCreateOptions(Bundle options);

    protected abstract void onExternalMotionEvent(MotionEvent event);

}
