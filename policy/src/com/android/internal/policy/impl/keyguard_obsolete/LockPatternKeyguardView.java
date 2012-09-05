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

package com.android.internal.policy.impl.keyguard_obsolete;

import com.android.internal.R;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockScreenWidgetCallback;
import com.android.internal.widget.TransportControlView;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import java.io.IOException;


/**
 * The host view for all of the screens of the pattern unlock screen.  There are
 * two {@link Mode}s of operation, lock and unlock.  This will show the appropriate
 * screen, and listen for callbacks via
 * {@link com.android.internal.policy.impl.KeyguardScreenCallback}
 * from the current screen.
 *
 * This view, in turn, communicates back to
 * {@link com.android.internal.policy.impl.KeyguardViewManager}
 * via its {@link com.android.internal.policy.impl.KeyguardViewCallback}, as appropriate.
 */
public class LockPatternKeyguardView extends KeyguardViewBase {

    private static final int TRANSPORT_USERACTIVITY_TIMEOUT = 10000;

    static final boolean DEBUG_CONFIGURATION = false;

    // time after launching EmergencyDialer before the screen goes blank.
    private static final int EMERGENCY_CALL_TIMEOUT = 10000;

    // intent action for launching emergency dialer activity.
    static final String ACTION_EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL";

    private static final boolean DEBUG = false;
    private static final String TAG = "LockPatternKeyguardView";

    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardWindowController mWindowController;

    private View mLockScreen;
    private View mUnlockScreen;

    private boolean mScreenOn;
    private boolean mWindowFocused = false;
    private boolean mEnableFallback = false; // assume no fallback UI until we know better

    private boolean mShowLockBeforeUnlock = false;

    // Interface to a biometric sensor that can optionally be used to unlock the device
    private BiometricSensorUnlock mBiometricUnlock;
    private final Object mBiometricUnlockStartupLock = new Object();
    // Long enough to stay visible while dialer comes up
    // Short enough to not be visible if the user goes back immediately
    private final int BIOMETRIC_AREA_EMERGENCY_DIALER_TIMEOUT = 1000;

    private boolean mRequiresSim;
    // True if the biometric unlock should not be displayed.  For example, if there is an overlay on
    // lockscreen or the user is plugging in / unplugging the device.
    private boolean mSuppressBiometricUnlock;
    //True if a dialog is currently displaying on top of this window
    //Unlike other overlays, this does not close with a power button cycle
    private boolean mHasDialog = false;
    //True if this device is currently plugged in
    private boolean mPluggedIn;
    // True the first time lockscreen is showing after boot
    private static boolean sIsFirstAppearanceAfterBoot = true;

    // The music control widget
    private TransportControlView mTransportControlView;

    private Parcelable mSavedState;

    /**
     * Either a lock screen (an informational keyguard screen), or an unlock
     * screen (a means for unlocking the device) is shown at any given time.
     */
    enum Mode {
        LockScreen,
        UnlockScreen
    }

    /**
     * The different types screens available for {@link Mode#UnlockScreen}.
     * @see com.android.internal.policy.impl.LockPatternKeyguardView#getUnlockMode()
     */
    enum UnlockMode {

        /**
         * Unlock by drawing a pattern.
         */
        Pattern,

        /**
         * Unlock by entering a sim pin.
         */
        SimPin,

        /**
         * Unlock by entering a sim puk.
         */
        SimPuk,

        /**
         * Unlock by entering an account's login and password.
         */
        Account,

        /**
         * Unlock by entering a password or PIN
         */
        Password,

        /**
         * Unknown (uninitialized) value
         */
        Unknown
    }

    /**
     * The current mode.
     */
    private Mode mMode = Mode.LockScreen;

    /**
     * Keeps track of what mode the current unlock screen is (cached from most recent computation in
     * {@link #getUnlockMode}).
     */
    private UnlockMode mUnlockScreenMode = UnlockMode.Unknown;

    private boolean mForgotPattern;

    /**
     * If true, it means we are in the process of verifying that the user
     * can get past the lock screen per {@link #verifyUnlock()}
     */
    private boolean mIsVerifyUnlockOnly = false;

    /**
     * Used to lookup the state of the lock pattern
     */
    private final LockPatternUtils mLockPatternUtils;

    /**
     * The current configuration.
     */
    private Configuration mConfiguration;

    private Runnable mRecreateRunnable = new Runnable() {
        public void run() {
            Mode mode = mMode;
            // If we were previously in a locked state but now it's Unknown, it means the phone
            // was previously locked because of SIM state and has since been resolved. This
            // bit of code checks this condition and dismisses keyguard.
            boolean dismissAfterCreation = false;
            if (mode == Mode.UnlockScreen && getUnlockMode() == UnlockMode.Unknown) {
                if (DEBUG) Log.v(TAG, "Switch to Mode.LockScreen because SIM unlocked");
                mode = Mode.LockScreen;
                dismissAfterCreation = true;
            }
            updateScreen(mode, true);
            restoreWidgetState();
            if (dismissAfterCreation) {
                mKeyguardScreenCallback.keyguardDone(false);
            }
        }
    };

    private LockScreenWidgetCallback mWidgetCallback = new LockScreenWidgetCallback() {
        public void userActivity(View self) {
            mKeyguardScreenCallback.pokeWakelock(TRANSPORT_USERACTIVITY_TIMEOUT);
        }

        public void requestShow(View view) {
            if (DEBUG) Log.v(TAG, "View " + view + " requested show transports");
            view.setVisibility(View.VISIBLE);

            // TODO: examine all widgets to derive clock status
            mUpdateMonitor.reportClockVisible(false);

            // If there's not a bg protection view containing the transport, then show a black
            // background. Otherwise, allow the normal background to show.
            if (findViewById(R.id.transport_bg_protect) == null) {
                // TODO: We should disable the wallpaper instead
                setBackgroundColor(0xff000000);
            } else {
                resetBackground();
            }
        }

        public void requestHide(View view) {
            if (DEBUG) Log.v(TAG, "View " + view + " requested hide transports");
            view.setVisibility(View.GONE);

            // TODO: examine all widgets to derive clock status
            mUpdateMonitor.reportClockVisible(true);
            resetBackground();
        }

        public boolean isVisible(View self) {
            // TODO: this should be up to the lockscreen to determine if the view
            // is currently showing. The idea is it can be used for the widget to
            // avoid doing work if it's not visible. For now just returns the view's
            // actual visibility.
            return self.getVisibility() == View.VISIBLE;
        }
    };

    /**
     * @return Whether we are stuck on the lock screen because the sim is
     *   missing.
     */
    private boolean stuckOnLockScreenBecauseSimMissing() {
        return mRequiresSim
                && (!mUpdateMonitor.isDeviceProvisioned())
                && (mUpdateMonitor.getSimState() == IccCardConstants.State.ABSENT ||
                    mUpdateMonitor.getSimState() == IccCardConstants.State.PERM_DISABLED);
    }

    /**
     * The current {@link KeyguardScreen} will use this to communicate back to us.
     */
    KeyguardScreenCallback mKeyguardScreenCallback = new KeyguardScreenCallback() {

        public void goToLockScreen() {
            mForgotPattern = false;
            if (mIsVerifyUnlockOnly) {
                // navigating away from unlock screen during verify mode means
                // we are done and the user failed to authenticate.
                mIsVerifyUnlockOnly = false;
                getCallback().keyguardDone(false);
            } else {
                updateScreen(Mode.LockScreen, false);
            }
        }

        public void goToUnlockScreen() {
            final IccCardConstants.State simState = mUpdateMonitor.getSimState();
            if (stuckOnLockScreenBecauseSimMissing()
                     || (simState == IccCardConstants.State.PUK_REQUIRED
                         && !mLockPatternUtils.isPukUnlockScreenEnable())){
                // stuck on lock screen when sim missing or
                // puk'd but puk unlock screen is disabled
                return;
            }
            if (!isSecure()) {
                getCallback().keyguardDone(true);
            } else {
                updateScreen(Mode.UnlockScreen, false);
            }
        }

        public void forgotPattern(boolean isForgotten) {
            if (mEnableFallback) {
                mForgotPattern = isForgotten;
                updateScreen(Mode.UnlockScreen, false);
            }
        }

        public boolean isSecure() {
            return LockPatternKeyguardView.this.isSecure();
        }

        public boolean isVerifyUnlockOnly() {
            return mIsVerifyUnlockOnly;
        }

        public void recreateMe(Configuration config) {
            if (DEBUG) Log.v(TAG, "recreateMe()");
            removeCallbacks(mRecreateRunnable);
            post(mRecreateRunnable);
        }

        public void takeEmergencyCallAction() {
            mSuppressBiometricUnlock = true;

            if (mBiometricUnlock != null) {
                if (mBiometricUnlock.isRunning()) {
                    // Continue covering backup lock until dialer comes up or call is resumed
                    mBiometricUnlock.show(BIOMETRIC_AREA_EMERGENCY_DIALER_TIMEOUT);
                }

                // We must ensure the biometric unlock is stopped when emergency call is pressed
                mBiometricUnlock.stop();
            }

            pokeWakelock(EMERGENCY_CALL_TIMEOUT);
            if (TelephonyManager.getDefault().getCallState()
                    == TelephonyManager.CALL_STATE_OFFHOOK) {
                mLockPatternUtils.resumeCall();
            } else {
                Intent intent = new Intent(ACTION_EMERGENCY_DIAL);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                getContext().startActivity(intent);
            }
        }

        public void pokeWakelock() {
            getCallback().pokeWakelock();
        }

        public void pokeWakelock(int millis) {
            getCallback().pokeWakelock(millis);
        }

        public void keyguardDone(boolean authenticated) {
            getCallback().keyguardDone(authenticated);
            mSavedState = null; // clear state so we re-establish when locked again
        }

        public void keyguardDoneDrawing() {
            // irrelevant to keyguard screen, they shouldn't be calling this
        }

        public void reportFailedUnlockAttempt() {
            mUpdateMonitor.reportFailedAttempt();
            final int failedAttempts = mUpdateMonitor.getFailedAttempts();
            if (DEBUG) Log.d(TAG, "reportFailedPatternAttempt: #" + failedAttempts +
                " (enableFallback=" + mEnableFallback + ")");

            final boolean usingPattern = mLockPatternUtils.getKeyguardStoredPasswordQuality()
                    == DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;

            final int failedAttemptsBeforeWipe = mLockPatternUtils.getDevicePolicyManager()
                    .getMaximumFailedPasswordsForWipe(null);

            final int failedAttemptWarning = LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET
                    - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;

            final int remainingBeforeWipe = failedAttemptsBeforeWipe > 0 ?
                    (failedAttemptsBeforeWipe - failedAttempts)
                    : Integer.MAX_VALUE; // because DPM returns 0 if no restriction

            if (remainingBeforeWipe < LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE) {
                // If we reach this code, it means the user has installed a DevicePolicyManager
                // that requests device wipe after N attempts.  Once we get below the grace
                // period, we'll post this dialog every time as a clear warning until the
                // bombshell hits and the device is wiped.
                if (remainingBeforeWipe > 0) {
                    showAlmostAtWipeDialog(failedAttempts, remainingBeforeWipe);
                } else {
                    // Too many attempts. The device will be wiped shortly.
                    Slog.i(TAG, "Too many unlock attempts; device will be wiped!");
                    showWipeDialog(failedAttempts);
                }
            } else {
                boolean showTimeout =
                    (failedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) == 0;
                if (usingPattern && mEnableFallback) {
                    if (failedAttempts == failedAttemptWarning) {
                        showAlmostAtAccountLoginDialog();
                        showTimeout = false; // don't show both dialogs
                    } else if (failedAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET) {
                        mLockPatternUtils.setPermanentlyLocked(true);
                        updateScreen(mMode, false);
                        // don't show timeout dialog because we show account unlock screen next
                        showTimeout = false;
                    }
                }
                if (showTimeout) {
                    showTimeoutDialog();
                }
            }
            mLockPatternUtils.reportFailedPasswordAttempt();
        }

        public boolean doesFallbackUnlockScreenExist() {
            return mEnableFallback;
        }

        public void reportSuccessfulUnlockAttempt() {
            mLockPatternUtils.reportSuccessfulPasswordAttempt();
        }
    };

    /**
     * @param context Used to inflate, and create views.
     * @param callback Keyguard callback object for pokewakelock(), etc.
     * @param updateMonitor Knows the state of the world, and passed along to each
     *   screen so they can use the knowledge, and also register for callbacks
     *   on dynamic information.
     * @param lockPatternUtils Used to look up state of lock pattern.
     */
    public LockPatternKeyguardView(
            Context context, KeyguardViewCallback callback, KeyguardUpdateMonitor updateMonitor,
            LockPatternUtils lockPatternUtils, KeyguardWindowController controller) {
        super(context, callback);

        mConfiguration = context.getResources().getConfiguration();
        mEnableFallback = false;
        mRequiresSim = TextUtils.isEmpty(SystemProperties.get("keyguard.no_require_sim"));
        mUpdateMonitor = updateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mWindowController = controller;
        mSuppressBiometricUnlock = sIsFirstAppearanceAfterBoot;
        sIsFirstAppearanceAfterBoot = false;
        mScreenOn = ((PowerManager)context.getSystemService(Context.POWER_SERVICE)).isScreenOn();
        mUpdateMonitor.registerCallback(mInfoCallback);

        /**
         * We'll get key events the current screen doesn't use. see
         * {@link KeyguardViewBase#onKeyDown(int, android.view.KeyEvent)}
         */
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        updateScreen(getInitialMode(), false);
        maybeEnableFallback(context);
    }

    private class AccountAnalyzer implements AccountManagerCallback<Bundle> {
        private final AccountManager mAccountManager;
        private final Account[] mAccounts;
        private int mAccountIndex;

        private AccountAnalyzer(AccountManager accountManager) {
            mAccountManager = accountManager;
            mAccounts = accountManager.getAccountsByType("com.google");
        }

        private void next() {
            // if we are ready to enable the fallback or if we depleted the list of accounts
            // then finish and get out
            if (mEnableFallback || mAccountIndex >= mAccounts.length) {
                if (mUnlockScreen == null) {
                    if (DEBUG) Log.w(TAG, "no unlock screen when trying to enable fallback");
                } else if (mUnlockScreen instanceof PatternUnlockScreen) {
                    ((PatternUnlockScreen)mUnlockScreen).setEnableFallback(mEnableFallback);
                }
                return;
            }

            // lookup the confirmCredentials intent for the current account
            mAccountManager.confirmCredentials(mAccounts[mAccountIndex], null, null, this, null);
        }

        public void start() {
            mEnableFallback = false;
            mAccountIndex = 0;
            next();
        }

        public void run(AccountManagerFuture<Bundle> future) {
            try {
                Bundle result = future.getResult();
                if (result.getParcelable(AccountManager.KEY_INTENT) != null) {
                    mEnableFallback = true;
                }
            } catch (OperationCanceledException e) {
                // just skip the account if we are unable to query it
            } catch (IOException e) {
                // just skip the account if we are unable to query it
            } catch (AuthenticatorException e) {
                // just skip the account if we are unable to query it
            } finally {
                mAccountIndex++;
                next();
            }
        }
    }

    private void maybeEnableFallback(Context context) {
        // Ask the account manager if we have an account that can be used as a
        // fallback in case the user forgets his pattern.
        AccountAnalyzer accountAnalyzer = new AccountAnalyzer(AccountManager.get(context));
        accountAnalyzer.start();
    }


    // TODO:
    // This overloaded method was added to workaround a race condition in the framework between
    // notification for orientation changed, layout() and switching resources.  This code attempts
    // to avoid drawing the incorrect layout while things are in transition.  The method can just
    // be removed once the race condition is fixed. See bugs 2262578 and 2292713.
    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (DEBUG) Log.v(TAG, "*** dispatchDraw() time: " + SystemClock.elapsedRealtime());
        super.dispatchDraw(canvas);
    }

    @Override
    public void reset() {
        mIsVerifyUnlockOnly = false;
        mForgotPattern = false;
        if (DEBUG) Log.v(TAG, "reset()");
        post(mRecreateRunnable);
    }

    @Override
    public void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "screen off");
        mScreenOn = false;
        mForgotPattern = false;

        // Emulate activity life-cycle for both lock and unlock screen.
        if (mLockScreen != null) {
            ((KeyguardScreen) mLockScreen).onPause();
        }
        if (mUnlockScreen != null) {
            ((KeyguardScreen) mUnlockScreen).onPause();
        }

        saveWidgetState();

        if (mBiometricUnlock != null) {
            // The biometric unlock must stop when screen turns off.
            mBiometricUnlock.stop();
        }
    }

    @Override
    public void onScreenTurnedOn() {
        if (DEBUG) Log.d(TAG, "screen on");
        boolean startBiometricUnlock = false;
        // Start the biometric unlock if and only if the screen is both on and focused
        synchronized(mBiometricUnlockStartupLock) {
            mScreenOn = true;
            startBiometricUnlock = mWindowFocused;
        }

        show();

        restoreWidgetState();

        if (mBiometricUnlock != null && startBiometricUnlock) {
            maybeStartBiometricUnlock();
        }
    }

    private void saveWidgetState() {
        if (mTransportControlView != null) {
            if (DEBUG) Log.v(TAG, "Saving widget state");
            mSavedState = mTransportControlView.onSaveInstanceState();
        }
    }

    private void restoreWidgetState() {
        if (mTransportControlView != null) {
            if (DEBUG) Log.v(TAG, "Restoring widget state");
            if (mSavedState != null) {
                mTransportControlView.onRestoreInstanceState(mSavedState);
            }
        }
    }

    /**
     * Stop the biometric unlock if something covers this window (such as an alarm)
     * Start the biometric unlock if the lockscreen window just came into focus and the screen is on
     */
    @Override
    public void onWindowFocusChanged (boolean hasWindowFocus) {
        if (DEBUG) Log.d(TAG, hasWindowFocus ? "focused" : "unfocused");

        boolean startBiometricUnlock = false;
        // Start the biometric unlock if and only if the screen is both on and focused
        synchronized(mBiometricUnlockStartupLock) {
            if (mScreenOn && !mWindowFocused) startBiometricUnlock = hasWindowFocus;
            mWindowFocused = hasWindowFocus;
        }
        if (!hasWindowFocus) {
            if (mBiometricUnlock != null) {
                mSuppressBiometricUnlock = true;
                mBiometricUnlock.stop();
                mBiometricUnlock.hide();
            }
        } else {
            mHasDialog = false;
            if (mBiometricUnlock != null && startBiometricUnlock) {
                maybeStartBiometricUnlock();
            }
        }
    }

    @Override
    public void show() {
        // Emulate activity life-cycle for both lock and unlock screen.
        if (mLockScreen != null) {
            ((KeyguardScreen) mLockScreen).onResume();
        }
        if (mUnlockScreen != null) {
            ((KeyguardScreen) mUnlockScreen).onResume();
        }

        if (mBiometricUnlock != null && mSuppressBiometricUnlock) {
            mBiometricUnlock.hide();
        }
    }

    private void recreateLockScreen() {
        if (mLockScreen != null) {
            ((KeyguardScreen) mLockScreen).onPause();
            ((KeyguardScreen) mLockScreen).cleanUp();
            removeView(mLockScreen);
        }

        mLockScreen = createLockScreen();
        mLockScreen.setVisibility(View.INVISIBLE);
        addView(mLockScreen);
    }

    private void recreateUnlockScreen(UnlockMode unlockMode) {
        if (mUnlockScreen != null) {
            ((KeyguardScreen) mUnlockScreen).onPause();
            ((KeyguardScreen) mUnlockScreen).cleanUp();
            removeView(mUnlockScreen);
        }

        mUnlockScreen = createUnlockScreenFor(unlockMode);
        mUnlockScreen.setVisibility(View.INVISIBLE);
        addView(mUnlockScreen);
    }

    @Override
    protected void onDetachedFromWindow() {
        mUpdateMonitor.removeCallback(mInfoCallback);

        removeCallbacks(mRecreateRunnable);

        if (mBiometricUnlock != null) {
            // When view is hidden, we need to stop the biometric unlock
            // e.g., when device becomes unlocked
            mBiometricUnlock.stop();
        }

        super.onDetachedFromWindow();
    }

    protected void onConfigurationChanged(Configuration newConfig) {
        Resources resources = getResources();
        mShowLockBeforeUnlock = resources.getBoolean(R.bool.config_enableLockBeforeUnlockScreen);
        mConfiguration = newConfig;
        if (DEBUG_CONFIGURATION) Log.v(TAG, "**** re-creating lock screen since config changed");
        saveWidgetState();
        removeCallbacks(mRecreateRunnable);
        if (DEBUG) Log.v(TAG, "recreating lockscreen because config changed");
        post(mRecreateRunnable);
    }

    KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onRefreshBatteryInfo(KeyguardUpdateMonitor.BatteryStatus status) {
            // When someone plugs in or unplugs the device, we hide the biometric sensor area and
            // suppress its startup for the next onScreenTurnedOn().  Since plugging/unplugging
            // causes the screen to turn on, the biometric unlock would start if it wasn't
            // suppressed.
            //
            // However, if the biometric unlock is already running, we do not want to interrupt it.
            final boolean pluggedIn = status.isPluggedIn();
            if (mBiometricUnlock != null && mPluggedIn != pluggedIn
                    && !mBiometricUnlock.isRunning()) {
                mBiometricUnlock.stop();
                mBiometricUnlock.hide();
                mSuppressBiometricUnlock = true;
            }
            mPluggedIn = pluggedIn;
        }

        @Override
        public void onClockVisibilityChanged() {
            int visFlags = (getSystemUiVisibility() & ~View.STATUS_BAR_DISABLE_CLOCK)
                    | (mUpdateMonitor.isClockVisible() ? View.STATUS_BAR_DISABLE_CLOCK : 0);
            Log.v(TAG, "Set visibility on " + this + " to " + visFlags);
            setSystemUiVisibility(visFlags);
        }

        // We need to stop the biometric unlock when a phone call comes in
        @Override
        public void onPhoneStateChanged(int phoneState) {
            if (DEBUG) Log.d(TAG, "phone state: " + phoneState);
            if (mBiometricUnlock != null && phoneState == TelephonyManager.CALL_STATE_RINGING) {
                mSuppressBiometricUnlock = true;
                mBiometricUnlock.stop();
                mBiometricUnlock.hide();
            }
        }

        @Override
        public void onUserSwitched(int userId) {
            if (mBiometricUnlock != null) {
                mBiometricUnlock.stop();
            }
            mLockPatternUtils.setCurrentUser(userId);
            updateScreen(getInitialMode(), true);
        }
    };

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        // Do not let the screen to get locked while the user is disabled and touch
        // exploring. A blind user will need significantly more time to find and
        // interact with the lock screen views.
        AccessibilityManager accessibilityManager = AccessibilityManager.getInstance(mContext);
        if (accessibilityManager.isEnabled() && accessibilityManager.isTouchExplorationEnabled()) {
            getCallback().pokeWakelock();
        }
        return super.dispatchHoverEvent(event);
    }

    @Override
    public void wakeWhenReadyTq(int keyCode) {
        if (DEBUG) Log.d(TAG, "onWakeKey");
        if (keyCode == KeyEvent.KEYCODE_MENU && isSecure() && (mMode == Mode.LockScreen)
                && (mUpdateMonitor.getSimState() != IccCardConstants.State.PUK_REQUIRED)) {
            if (DEBUG) Log.d(TAG, "switching screens to unlock screen because wake key was MENU");
            updateScreen(Mode.UnlockScreen, false);
            getCallback().pokeWakelock();
        } else {
            if (DEBUG) Log.d(TAG, "poking wake lock immediately");
            getCallback().pokeWakelock();
        }
    }

    @Override
    public void verifyUnlock() {
        if (!isSecure()) {
            // non-secure keyguard screens are successfull by default
            getCallback().keyguardDone(true);
        } else if (mUnlockScreenMode != UnlockMode.Pattern
                && mUnlockScreenMode != UnlockMode.Password) {
            // can only verify unlock when in pattern/password mode
            getCallback().keyguardDone(false);
        } else {
            // otherwise, go to the unlock screen, see if they can verify it
            mIsVerifyUnlockOnly = true;
            updateScreen(Mode.UnlockScreen, false);
        }
    }

    @Override
    public void cleanUp() {
        if (mLockScreen != null) {
            ((KeyguardScreen) mLockScreen).onPause();
            ((KeyguardScreen) mLockScreen).cleanUp();
            this.removeView(mLockScreen);
            mLockScreen = null;
        }
        if (mUnlockScreen != null) {
            ((KeyguardScreen) mUnlockScreen).onPause();
            ((KeyguardScreen) mUnlockScreen).cleanUp();
            this.removeView(mUnlockScreen);
            mUnlockScreen = null;
        }
        mUpdateMonitor.removeCallback(this);
        if (mBiometricUnlock != null) {
            mBiometricUnlock.cleanUp();
        }
    }

    private boolean isSecure() {
        UnlockMode unlockMode = getUnlockMode();
        boolean secure = false;
        switch (unlockMode) {
            case Pattern:
                secure = mLockPatternUtils.isLockPatternEnabled();
                break;
            case SimPin:
                secure = mUpdateMonitor.getSimState() == IccCardConstants.State.PIN_REQUIRED;
                break;
            case SimPuk:
                secure = mUpdateMonitor.getSimState() == IccCardConstants.State.PUK_REQUIRED;
                break;
            case Account:
                secure = true;
                break;
            case Password:
                secure = mLockPatternUtils.isLockPasswordEnabled();
                break;
            case Unknown:
                // This means no security is set up
                break;
            default:
                throw new IllegalStateException("unknown unlock mode " + unlockMode);
        }
        return secure;
    }

    private void updateScreen(Mode mode, boolean force) {

        if (DEBUG_CONFIGURATION) Log.v(TAG, "**** UPDATE SCREEN: mode=" + mode
                + " last mode=" + mMode + ", force = " + force, new RuntimeException());

        mMode = mode;

        // Re-create the lock screen if necessary
        if (mode == Mode.LockScreen || mShowLockBeforeUnlock) {
            if (force || mLockScreen == null) {
                recreateLockScreen();
            }
        }

        // Re-create the unlock screen if necessary.
        final UnlockMode unlockMode = getUnlockMode();
        if (mode == Mode.UnlockScreen && unlockMode != UnlockMode.Unknown) {
            if (force || mUnlockScreen == null || unlockMode != mUnlockScreenMode) {
                recreateUnlockScreen(unlockMode);
            }
        }

        // visibleScreen should never be null
        final View goneScreen = (mode == Mode.LockScreen) ? mUnlockScreen : mLockScreen;
        final View visibleScreen = (mode == Mode.LockScreen) ? mLockScreen : mUnlockScreen;

        // do this before changing visibility so focus isn't requested before the input
        // flag is set
        mWindowController.setNeedsInput(((KeyguardScreen)visibleScreen).needsInput());

        if (DEBUG_CONFIGURATION) {
            Log.v(TAG, "Gone=" + goneScreen);
            Log.v(TAG, "Visible=" + visibleScreen);
        }

        if (mScreenOn) {
            if (goneScreen != null && goneScreen.getVisibility() == View.VISIBLE) {
                ((KeyguardScreen) goneScreen).onPause();
            }
            if (visibleScreen.getVisibility() != View.VISIBLE) {
                ((KeyguardScreen) visibleScreen).onResume();
            }
        }

        if (goneScreen != null) {
            goneScreen.setVisibility(View.GONE);
        }
        visibleScreen.setVisibility(View.VISIBLE);
        requestLayout();

        if (!visibleScreen.requestFocus()) {
            throw new IllegalStateException("keyguard screen must be able to take "
                    + "focus when shown " + visibleScreen.getClass().getCanonicalName());
        }
    }

    View createLockScreen() {
        View lockView = new LockScreen(
                mContext,
                mConfiguration,
                mLockPatternUtils,
                mUpdateMonitor,
                mKeyguardScreenCallback);
        initializeTransportControlView(lockView);
        return lockView;
    }

    View createUnlockScreenFor(UnlockMode unlockMode) {
        View unlockView = null;

        if (DEBUG) Log.d(TAG,
                "createUnlockScreenFor(" + unlockMode + "): mEnableFallback=" + mEnableFallback);

        if (unlockMode == UnlockMode.Pattern) {
            PatternUnlockScreen view = new PatternUnlockScreen(
                    mContext,
                    mConfiguration,
                    mLockPatternUtils,
                    mUpdateMonitor,
                    mKeyguardScreenCallback,
                    mUpdateMonitor.getFailedAttempts());
            view.setEnableFallback(mEnableFallback);
            unlockView = view;
        } else if (unlockMode == UnlockMode.SimPuk) {
            unlockView = new SimPukUnlockScreen(
                    mContext,
                    mConfiguration,
                    mUpdateMonitor,
                    mKeyguardScreenCallback,
                    mLockPatternUtils);
        } else if (unlockMode == UnlockMode.SimPin) {
            unlockView = new SimUnlockScreen(
                    mContext,
                    mConfiguration,
                    mUpdateMonitor,
                    mKeyguardScreenCallback,
                    mLockPatternUtils);
        } else if (unlockMode == UnlockMode.Account) {
            try {
                unlockView = new AccountUnlockScreen(
                        mContext,
                        mConfiguration,
                        mUpdateMonitor,
                        mKeyguardScreenCallback,
                        mLockPatternUtils);
            } catch (IllegalStateException e) {
                Log.i(TAG, "Couldn't instantiate AccountUnlockScreen"
                      + " (IAccountsService isn't available)");
                // TODO: Need a more general way to provide a
                // platform-specific fallback UI here.
                // For now, if we can't display the account login
                // unlock UI, just bring back the regular "Pattern" unlock mode.

                // (We do this by simply returning a regular UnlockScreen
                // here.  This means that the user will still see the
                // regular pattern unlock UI, regardless of the value of
                // mUnlockScreenMode or whether or not we're in the
                // "permanently locked" state.)
                return createUnlockScreenFor(UnlockMode.Pattern);
            }
        } else if (unlockMode == UnlockMode.Password) {
            unlockView = new PasswordUnlockScreen(
                    mContext,
                    mConfiguration,
                    mLockPatternUtils,
                    mUpdateMonitor,
                    mKeyguardScreenCallback);
        } else {
            throw new IllegalArgumentException("unknown unlock mode " + unlockMode);
        }
        initializeTransportControlView(unlockView);
        initializeBiometricUnlockView(unlockView);

        mUnlockScreenMode = unlockMode;
        return unlockView;
    }

    private void initializeTransportControlView(View view) {
        mTransportControlView = (TransportControlView) view.findViewById(R.id.transport);
        if (mTransportControlView == null) {
            if (DEBUG) Log.w(TAG, "Couldn't find transport control widget");
        } else {
            mUpdateMonitor.reportClockVisible(true);
            mTransportControlView.setVisibility(View.GONE); // hide until it requests being shown.
            mTransportControlView.setCallback(mWidgetCallback);
        }
    }

    /**
     * This returns false if there is any condition that indicates that the biometric unlock should
     * not be used before the next time the unlock screen is recreated.  In other words, if this
     * returns false there is no need to even construct the biometric unlock.
     */
    private boolean useBiometricUnlock() {
        final UnlockMode unlockMode = getUnlockMode();
        final boolean backupIsTimedOut = (mUpdateMonitor.getFailedAttempts() >=
                LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT);
        return (mLockPatternUtils.usingBiometricWeak() &&
                mLockPatternUtils.isBiometricWeakInstalled() &&
                !mUpdateMonitor.getMaxBiometricUnlockAttemptsReached() &&
                !backupIsTimedOut &&
                (unlockMode == UnlockMode.Pattern || unlockMode == UnlockMode.Password));
    }

    private void initializeBiometricUnlockView(View view) {
        boolean restartBiometricUnlock = false;

        if (mBiometricUnlock != null) {
            restartBiometricUnlock = mBiometricUnlock.stop();
        }

        // Prevents biometric unlock from coming up immediately after a phone call or if there
        // is a dialog on top of lockscreen. It is only updated if the screen is off because if the
        // screen is on it's either because of an orientation change, or when it first boots.
        // In both those cases, we don't want to override the current value of
        // mSuppressBiometricUnlock and instead want to use the previous value.
        if (!mScreenOn) {
            mSuppressBiometricUnlock =
                    mUpdateMonitor.getPhoneState() != TelephonyManager.CALL_STATE_IDLE
                    || mHasDialog;
        }

        // If the biometric unlock is not being used, we don't bother constructing it.  Then we can
        // simply check if it is null when deciding whether we should make calls to it.
        mBiometricUnlock = null;
        if (useBiometricUnlock()) {
            // TODO: make faceLockAreaView a more general biometricUnlockView
            // We will need to add our Face Unlock specific child views programmatically in
            // initializeView rather than having them in the XML files.
            View biometricUnlockView = view.findViewById(R.id.face_unlock_area_view);
            if (biometricUnlockView != null) {
                mBiometricUnlock = new FaceUnlock(mContext, mUpdateMonitor, mLockPatternUtils,
                        mKeyguardScreenCallback);
                mBiometricUnlock.initializeView(biometricUnlockView);

                // If this is being called because the screen turned off, we want to cover the
                // backup lock so it is covered when the screen turns back on.
                if (!mScreenOn) mBiometricUnlock.show(0);
            } else {
                Log.w(TAG, "Couldn't find biometric unlock view");
            }
        }

        if (mBiometricUnlock != null && restartBiometricUnlock) {
            maybeStartBiometricUnlock();
        }
    }

    /**
     * Given the current state of things, what should be the initial mode of
     * the lock screen (lock or unlock).
     */
    private Mode getInitialMode() {
        final IccCardConstants.State simState = mUpdateMonitor.getSimState();
        if (stuckOnLockScreenBecauseSimMissing() ||
                (simState == IccCardConstants.State.PUK_REQUIRED &&
                        !mLockPatternUtils.isPukUnlockScreenEnable())) {
            return Mode.LockScreen;
        } else {
            if (!isSecure() || mShowLockBeforeUnlock) {
                return Mode.LockScreen;
            } else {
                return Mode.UnlockScreen;
            }
        }
    }

    /**
     * Given the current state of things, what should the unlock screen be?
     */
    private UnlockMode getUnlockMode() {
        final IccCardConstants.State simState = mUpdateMonitor.getSimState();
        UnlockMode currentMode;
        if (simState == IccCardConstants.State.PIN_REQUIRED) {
            currentMode = UnlockMode.SimPin;
        } else if (simState == IccCardConstants.State.PUK_REQUIRED) {
            currentMode = UnlockMode.SimPuk;
        } else {
            final int mode = mLockPatternUtils.getKeyguardStoredPasswordQuality();
            switch (mode) {
                case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
                case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
                case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
                case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
                    currentMode = UnlockMode.Password;
                    break;
                case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                case DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED:
                    if (mLockPatternUtils.isLockPatternEnabled()) {
                        // "forgot pattern" button is only available in the pattern mode...
                        if (mForgotPattern || mLockPatternUtils.isPermanentlyLocked()) {
                            currentMode = UnlockMode.Account;
                        } else {
                            currentMode = UnlockMode.Pattern;
                        }
                    } else {
                        currentMode = UnlockMode.Unknown;
                    }
                    break;
                default:
                   throw new IllegalStateException("Unknown unlock mode:" + mode);
            }
        }
        return currentMode;
    }

    private void showDialog(String title, String message) {
        mHasDialog = true;
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton(R.string.ok, null)
            .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        dialog.show();
    }

    private void showTimeoutDialog() {
        int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        int messageId = R.string.lockscreen_too_many_failed_attempts_dialog_message;
        if (getUnlockMode() == UnlockMode.Password) {
            if(mLockPatternUtils.getKeyguardStoredPasswordQuality() ==
                DevicePolicyManager.PASSWORD_QUALITY_NUMERIC) {
                messageId = R.string.lockscreen_too_many_failed_pin_attempts_dialog_message;
            } else {
                messageId = R.string.lockscreen_too_many_failed_password_attempts_dialog_message;
            }
        }
        String message = mContext.getString(messageId, mUpdateMonitor.getFailedAttempts(),
                timeoutInSeconds);

        showDialog(null, message);
    }

    private void showAlmostAtAccountLoginDialog() {
        final int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        final int count = LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET
                - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;
        String message = mContext.getString(R.string.lockscreen_failed_attempts_almost_glogin,
                count, LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT, timeoutInSeconds);
        showDialog(null, message);
    }

    private void showAlmostAtWipeDialog(int attempts, int remaining) {
        int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        String message = mContext.getString(
                R.string.lockscreen_failed_attempts_almost_at_wipe, attempts, remaining);
        showDialog(null, message);
    }

    private void showWipeDialog(int attempts) {
        String message = mContext.getString(
                R.string.lockscreen_failed_attempts_now_wiping, attempts);
        showDialog(null, message);
    }

    /**
     * Used to put wallpaper on the background of the lock screen.  Centers it
     * Horizontally and pins the bottom (assuming that the lock screen is aligned
     * with the bottom, so the wallpaper should extend above the top into the
     * status bar).
     */
    static private class FastBitmapDrawable extends Drawable {
        private Bitmap mBitmap;
        private int mOpacity;

        private FastBitmapDrawable(Bitmap bitmap) {
            mBitmap = bitmap;
            mOpacity = mBitmap.hasAlpha() ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(
                    mBitmap,
                    (getBounds().width() - mBitmap.getWidth()) / 2,
                    (getBounds().height() - mBitmap.getHeight()),
                    null);
        }

        @Override
        public int getOpacity() {
            return mOpacity;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getIntrinsicWidth() {
            return mBitmap.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return mBitmap.getHeight();
        }

        @Override
        public int getMinimumWidth() {
            return mBitmap.getWidth();
        }

        @Override
        public int getMinimumHeight() {
            return mBitmap.getHeight();
        }
    }

    /**
     * Starts the biometric unlock if it should be started based on a number of factors including
     * the mSuppressBiometricUnlock flag.  If it should not be started, it hides the biometric
     * unlock area.
     */
    private void maybeStartBiometricUnlock() {
        if (mBiometricUnlock != null) {
            final boolean backupIsTimedOut = (mUpdateMonitor.getFailedAttempts() >=
                    LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT);
            if (!mSuppressBiometricUnlock
                    && mUpdateMonitor.getPhoneState() == TelephonyManager.CALL_STATE_IDLE
                    && !mUpdateMonitor.getMaxBiometricUnlockAttemptsReached()
                    && !backupIsTimedOut) {
                mBiometricUnlock.start();
            } else {
                mBiometricUnlock.hide();
            }
        }
    }
}
