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

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.policy.impl.LockPatternKeyguardView.UnlockMode;
import com.android.internal.telephony.IccCard;
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

    private boolean mScreenOn = false;
    private boolean mEnableFallback = false; // assume no fallback UI until we know better

    private boolean mShowLockBeforeUnlock = false;

    /**
     * The current {@link KeyguardScreen} will use this to communicate back to us.
     */
    KeyguardScreenCallback mKeyguardScreenCallback;


    private boolean mRequiresSim;


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
            updateScreen(mMode, false);
        }
    };

    private LockScreenWidgetCallback mWidgetCallback = new LockScreenWidgetCallback() {
        public void userActivity(View self) {
            mKeyguardScreenCallback.pokeWakelock(TRANSPORT_USERACTIVITY_TIMEOUT);
        }

        public void requestShow(View view) {
            if (DEBUG) Log.v(TAG, "View " + view + " requested show transports");
            view.setVisibility(View.VISIBLE);
        }

        public void requestHide(View view) {
            if (DEBUG) Log.v(TAG, "View " + view + " requested hide transports");
            view.setVisibility(View.GONE);
        }
    };

    /**
     * @return Whether we are stuck on the lock screen because the sim is
     *   missing.
     */
    private boolean stuckOnLockScreenBecauseSimMissing() {
        return mRequiresSim
                && (!mUpdateMonitor.isDeviceProvisioned())
                && (mUpdateMonitor.getSimState() == IccCard.State.ABSENT ||
                    mUpdateMonitor.getSimState() == IccCard.State.PERM_DISABLED);
    }

    /**
     * @param context Used to inflate, and create views.
     * @param updateMonitor Knows the state of the world, and passed along to each
     *   screen so they can use the knowledge, and also register for callbacks
     *   on dynamic information.
     * @param lockPatternUtils Used to look up state of lock pattern.
     */
    public LockPatternKeyguardView(
            Context context,
            KeyguardUpdateMonitor updateMonitor,
            LockPatternUtils lockPatternUtils,
            KeyguardWindowController controller) {
        super(context);

        mConfiguration = context.getResources().getConfiguration();
        mEnableFallback = false;
        mRequiresSim = TextUtils.isEmpty(SystemProperties.get("keyguard.no_require_sim"));
        mUpdateMonitor = updateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mWindowController = controller;

        mKeyguardScreenCallback = new KeyguardScreenCallback() {

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
                final IccCard.State simState = mUpdateMonitor.getSimState();
                if (stuckOnLockScreenBecauseSimMissing()
                         || (simState == IccCard.State.PUK_REQUIRED
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
                removeCallbacks(mRecreateRunnable);
                post(mRecreateRunnable);
            }

            public void takeEmergencyCallAction() {
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
                } else if (usingPattern && mEnableFallback) {
                    if (failedAttempts == failedAttemptWarning) {
                        showAlmostAtAccountLoginDialog();
                    } else if (failedAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET) {
                        mLockPatternUtils.setPermanentlyLocked(true);
                        updateScreen(mMode, false);
                    }
                } else {
                    final boolean showTimeout =
                        (failedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) == 0;
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
                    Log.w(TAG, "no unlock screen when trying to enable fallback");
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
        updateScreen(getInitialMode(), false);
    }

    @Override
    public void onScreenTurnedOff() {
        mScreenOn = false;
        mForgotPattern = false;
        if (mMode == Mode.LockScreen) {
            ((KeyguardScreen) mLockScreen).onPause();
        } else {
            ((KeyguardScreen) mUnlockScreen).onPause();
        }
    }

    @Override
    public void onScreenTurnedOn() {
        mScreenOn = true;
        if (mMode == Mode.LockScreen) {
            ((KeyguardScreen) mLockScreen).onResume();
        } else {
            ((KeyguardScreen) mUnlockScreen).onResume();
        }
    }

    private void recreateLockScreen() {
        if (mLockScreen != null) {
            if (mLockScreen.getVisibility() == View.VISIBLE) {
                ((KeyguardScreen) mLockScreen).onPause();
            }
            ((KeyguardScreen) mLockScreen).cleanUp();
            removeView(mLockScreen);
        }

        mLockScreen = createLockScreen();
        mLockScreen.setVisibility(View.INVISIBLE);
        addView(mLockScreen);
    }

    private void recreateUnlockScreen(UnlockMode unlockMode) {
        if (mUnlockScreen != null) {
            if (mUnlockScreen.getVisibility() == View.VISIBLE) {
                ((KeyguardScreen) mUnlockScreen).onPause();
            }
            ((KeyguardScreen) mUnlockScreen).cleanUp();
            removeView(mUnlockScreen);
        }

        mUnlockScreen = createUnlockScreenFor(unlockMode);
        mUnlockScreen.setVisibility(View.INVISIBLE);
        addView(mUnlockScreen);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mRecreateRunnable);
        super.onDetachedFromWindow();
    }

    protected void onConfigurationChanged(Configuration newConfig) {
        Resources resources = getResources();
        mShowLockBeforeUnlock = resources.getBoolean(R.bool.config_enableLockBeforeUnlockScreen);
        mConfiguration = newConfig;
        if (DEBUG_CONFIGURATION) Log.v(TAG, "**** re-creating lock screen since config changed");
        updateScreen(mMode, true /* force */);
    }

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
                && (mUpdateMonitor.getSimState() != IccCard.State.PUK_REQUIRED)) {
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
    }

    private boolean isSecure() {
        UnlockMode unlockMode = getUnlockMode();
        boolean secure = false;
        switch (unlockMode) {
            case Pattern:
                secure = mLockPatternUtils.isLockPatternEnabled();
                break;
            case SimPin:
                secure = mUpdateMonitor.getSimState() == IccCard.State.PIN_REQUIRED;
                break;
            case SimPuk:
                secure = mUpdateMonitor.getSimState() == IccCard.State.PUK_REQUIRED;
                break;
            case Account:
                secure = true;
                break;
            case Password:
                secure = mLockPatternUtils.isLockPasswordEnabled();
                break;
            default:
                throw new IllegalStateException("unknown unlock mode " + unlockMode);
        }
        return secure;
    }

    private void updateScreen(Mode mode, boolean force) {

        if (DEBUG_CONFIGURATION) Log.v(TAG, "**** UPDATE SCREEN: mode=" + mode
                + " last mode=" + mMode, new RuntimeException());

        mMode = mode;

        // Re-create the lock screen if necessary
        if (mode == Mode.LockScreen || mShowLockBeforeUnlock) {
            if (force || mLockScreen == null) {
                recreateLockScreen();
            }
        }

        // Re-create the unlock screen if necessary. This is primarily required to properly handle
        // SIM state changes. This typically happens when this method is called by reset()
        if (mode == Mode.UnlockScreen) {
            final UnlockMode unlockMode = getUnlockMode();
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
        mUnlockScreenMode = unlockMode;
        return unlockView;
    }

    private void initializeTransportControlView(View view) {
        com.android.internal.widget.TransportControlView tcv =
                (TransportControlView) view.findViewById(R.id.transport);
        if (tcv == null) {
            if (DEBUG) Log.w(TAG, "Couldn't find transport control widget");
        } else {
            tcv.setVisibility(View.GONE); // hide tcv until we get the callback below to show it.
            tcv.setCallback(mWidgetCallback);
        }
    }

    /**
     * Given the current state of things, what should be the initial mode of
     * the lock screen (lock or unlock).
     */
    private Mode getInitialMode() {
        final IccCard.State simState = mUpdateMonitor.getSimState();
        if (stuckOnLockScreenBecauseSimMissing() ||
                (simState == IccCard.State.PUK_REQUIRED &&
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
        final IccCard.State simState = mUpdateMonitor.getSimState();
        UnlockMode currentMode;
        if (simState == IccCard.State.PIN_REQUIRED) {
            currentMode = UnlockMode.SimPin;
        } else if (simState == IccCard.State.PUK_REQUIRED) {
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
                    // "forgot pattern" button is only available in the pattern mode...
                    if (mForgotPattern || mLockPatternUtils.isPermanentlyLocked()) {
                        currentMode = UnlockMode.Account;
                    } else {
                        currentMode = UnlockMode.Pattern;
                    }
                    break;
                default:
                   throw new IllegalStateException("Unknown unlock mode:" + mode);
            }
        }
        return currentMode;
    }

    private void showDialog(String title, String message) {
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
}

