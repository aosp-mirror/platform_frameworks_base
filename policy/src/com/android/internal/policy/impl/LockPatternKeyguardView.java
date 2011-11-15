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
import com.android.internal.policy.IFaceLockCallback;
import com.android.internal.policy.IFaceLockInterface;
import com.android.internal.telephony.IccCard;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockScreenWidgetCallback;
import com.android.internal.widget.LockScreenWidgetInterface;
import com.android.internal.widget.TransportControlView;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
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
public class LockPatternKeyguardView extends KeyguardViewBase implements Handler.Callback,
        KeyguardUpdateMonitor.InfoCallback {

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

    private volatile boolean mScreenOn = false;
    private volatile boolean mWindowFocused = false;
    private boolean mEnableFallback = false; // assume no fallback UI until we know better

    private boolean mShowLockBeforeUnlock = false;

    // The following were added to support FaceLock
    private IFaceLockInterface mFaceLockService;
    private boolean mBoundToFaceLockService = false;
    private View mFaceLockAreaView;

    private boolean mFaceLockServiceRunning = false;
    private final Object mFaceLockServiceRunningLock = new Object();
    private final Object mFaceLockStartupLock = new Object();

    private Handler mHandler;
    private final int MSG_SHOW_FACELOCK_AREA_VIEW = 0;
    private final int MSG_HIDE_FACELOCK_AREA_VIEW = 1;

    // Long enough to stay visible while dialer comes up
    // Short enough to not be visible if the user goes back immediately
    private final int FACELOCK_VIEW_AREA_EMERGENCY_DIALER_TIMEOUT = 1000;

    // Long enough to stay visible while the service starts
    // Short enough to not have to wait long for backup if service fails to start or crashes
    // The service can take a couple of seconds to start on the first try after boot
    private final int FACELOCK_VIEW_AREA_SERVICE_TIMEOUT = 3000;

    // So the user has a consistent amount of time when brought to the backup method from FaceLock
    private final int BACKUP_LOCK_TIMEOUT = 5000;

    // Needed to keep track of failed FaceUnlock attempts
    private int mFailedFaceUnlockAttempts = 0;
    private static final int FAILED_FACE_UNLOCK_ATTEMPTS_BEFORE_BACKUP = 15;

    /**
     * The current {@link KeyguardScreen} will use this to communicate back to us.
     */
    KeyguardScreenCallback mKeyguardScreenCallback;


    private boolean mRequiresSim;
    //True if we have some sort of overlay on top of the Lockscreen
    //Also true if we've activated a phone call, either emergency dialing or incoming
    //This resets when the phone is turned off with no current call
    private boolean mHasOverlay;


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
            updateScreen(mMode, true);
            restoreWidgetState();
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

    private TransportControlView mTransportControlView;

    private Parcelable mSavedState;

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

        mHandler = new Handler(this);
        mConfiguration = context.getResources().getConfiguration();
        mEnableFallback = false;
        mRequiresSim = TextUtils.isEmpty(SystemProperties.get("keyguard.no_require_sim"));
        mUpdateMonitor = updateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mWindowController = controller;
        mHasOverlay = false;

        mUpdateMonitor.registerInfoCallback(this);

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
                mHasOverlay = true;
                // FaceLock must be stopped if it is running when emergency call is pressed
                stopAndUnbindFromFaceLock();

                // Continue showing FaceLock area until dialer comes up or call is resumed
                if (usingFaceLock() && mFaceLockServiceRunning) {
                    showFaceLockAreaWithTimeout(FACELOCK_VIEW_AREA_EMERGENCY_DIALER_TIMEOUT);
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
                mFailedFaceUnlockAttempts = 0;
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
        post(mRecreateRunnable);
    }

    @Override
    public void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "screen off");
        mScreenOn = false;
        mForgotPattern = false;
        mHasOverlay = mUpdateMonitor.getPhoneState() != TelephonyManager.CALL_STATE_IDLE;
        if (mMode == Mode.LockScreen) {
            ((KeyguardScreen) mLockScreen).onPause();
        } else {
            ((KeyguardScreen) mUnlockScreen).onPause();
        }

        saveWidgetState();

        // When screen is turned off, need to unbind from FaceLock service if using FaceLock
        stopAndUnbindFromFaceLock();
    }

    /** When screen is turned on and focused, need to bind to FaceLock service if we are using
     *  FaceLock, but only if we're not dealing with a call
    */
    private void activateFaceLockIfAble() {
        final boolean tooManyFaceUnlockTries =
                (mFailedFaceUnlockAttempts >= FAILED_FACE_UNLOCK_ATTEMPTS_BEFORE_BACKUP);
        final int failedBackupAttempts = mUpdateMonitor.getFailedAttempts();
        final boolean backupIsTimedOut =
                (failedBackupAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT);
        if (tooManyFaceUnlockTries) Log.i(TAG, "tooManyFaceUnlockTries: " + tooManyFaceUnlockTries);
        if (mUpdateMonitor.getPhoneState() == TelephonyManager.CALL_STATE_IDLE
                && !mHasOverlay
                && !tooManyFaceUnlockTries
                && !backupIsTimedOut) {
            bindToFaceLock();
            // Show FaceLock area, but only for a little bit so lockpattern will become visible if
            // FaceLock fails to start or crashes
            if (usingFaceLock()) {
                showFaceLockAreaWithTimeout(FACELOCK_VIEW_AREA_SERVICE_TIMEOUT);
            }
        } else {
            hideFaceLockArea();
        }
    }

    @Override
    public void onScreenTurnedOn() {
        if (DEBUG) Log.d(TAG, "screen on");
        boolean runFaceLock = false;
        //Make sure to start facelock iff the screen is both on and focused
        synchronized(mFaceLockStartupLock) {
            mScreenOn = true;
            runFaceLock = mWindowFocused;
        }

        show();

        restoreWidgetState();

        if (runFaceLock) activateFaceLockIfAble();
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

    /** Unbind from facelock if something covers this window (such as an alarm)
     * bind to facelock if the lockscreen window just came into focus, and the screen is on
     */
    @Override
    public void onWindowFocusChanged (boolean hasWindowFocus) {
        if (DEBUG) Log.d(TAG, hasWindowFocus ? "focused" : "unfocused");
        boolean runFaceLock = false;
        //Make sure to start facelock iff the screen is both on and focused
        synchronized(mFaceLockStartupLock) {
            if(mScreenOn && !mWindowFocused) runFaceLock = hasWindowFocus;
            mWindowFocused = hasWindowFocus;
        }
        if(!hasWindowFocus) {
            mHasOverlay = true;
            stopAndUnbindFromFaceLock();
            hideFaceLockArea();
        } else if (runFaceLock) {
            activateFaceLockIfAble();
        }
    }

    @Override
    public void show() {
        if (mMode == Mode.LockScreen) {
            ((KeyguardScreen) mLockScreen).onResume();
        } else {
            ((KeyguardScreen) mUnlockScreen).onResume();
        }

        if (usingFaceLock() && !mHasOverlay) {
            // Note that show() gets called before the screen turns off to set it up for next time
            // it is turned on.  We don't want to set a timeout on the FaceLock area here because it
            // may be gone by the time the screen is turned on again.  We set the timeout when the
            // screen turns on instead.
            showFaceLockArea();
        } else {
            hideFaceLockArea();
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
        removeCallbacks(mRecreateRunnable);

        // When view is hidden, need to unbind from FaceLock service if we are using FaceLock
        // e.g., when device becomes unlocked
        stopAndUnbindFromFaceLock();

        super.onDetachedFromWindow();
    }

    protected void onConfigurationChanged(Configuration newConfig) {
        Resources resources = getResources();
        mShowLockBeforeUnlock = resources.getBoolean(R.bool.config_enableLockBeforeUnlockScreen);
        mConfiguration = newConfig;
        if (DEBUG_CONFIGURATION) Log.v(TAG, "**** re-creating lock screen since config changed");
        saveWidgetState();
        removeCallbacks(mRecreateRunnable);
        post(mRecreateRunnable);
    }

    //Ignore these events; they are implemented only because they come from the same interface
    @Override
    public void onRefreshBatteryInfo(boolean showBatteryInfo, boolean pluggedIn, int batteryLevel)
    {}
    @Override
    public void onTimeChanged() {}
    @Override
    public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {}
    @Override
    public void onRingerModeChanged(int state) {}

    @Override
    public void onClockVisibilityChanged() {
        int visFlags = getSystemUiVisibility() & ~View.STATUS_BAR_DISABLE_CLOCK;
        setSystemUiVisibility(visFlags
                | (mUpdateMonitor.isClockVisible() ? View.STATUS_BAR_DISABLE_CLOCK : 0));
    }

    @Override
    public void onDeviceProvisioned() {}

    //We need to stop faceunlock when a phonecall comes in
    @Override
    public void onPhoneStateChanged(int phoneState) {
        if (DEBUG) Log.d(TAG, "phone state: " + phoneState);
        if(phoneState == TelephonyManager.CALL_STATE_RINGING) {
            mHasOverlay = true;
            stopAndUnbindFromFaceLock();
            hideFaceLockArea();
        }
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
        mUpdateMonitor.removeCallback(this);
        if (mFaceLockService != null) {
            try {
                mFaceLockService.unregisterCallback(mFaceLockCallback);
            } catch (RemoteException e) {
                // Not much we can do
            }
            stopFaceLock();
            mFaceLockService = null;
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
                + " last mode=" + mMode + ", force = " + force, new RuntimeException());

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
                boolean restartFaceLock = stopFaceLockIfRunning();
                recreateUnlockScreen(unlockMode);
                if (restartFaceLock) activateFaceLockIfAble();
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
        initializeFaceLockAreaView(unlockView); // Only shows view if FaceLock is enabled

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

    // Everything below pertains to FaceLock - might want to separate this out

    // Indicates whether FaceLock is in use
    private boolean usingFaceLock() {
        return (mLockPatternUtils.usingBiometricWeak() &&
                mLockPatternUtils.isBiometricWeakInstalled());
    }

    // Takes care of FaceLock area when layout is created
    private void initializeFaceLockAreaView(View view) {
        if (usingFaceLock()) {
            mFaceLockAreaView = view.findViewById(R.id.faceLockAreaView);
            if (mFaceLockAreaView == null) {
                Log.e(TAG, "Layout does not have faceLockAreaView and FaceLock is enabled");
            }
        } else {
            mFaceLockAreaView = null; // Set to null if not using FaceLock
        }
    }

    // Stops FaceLock if it is running and reports back whether it was running or not
    private boolean stopFaceLockIfRunning() {
        if (usingFaceLock() && mBoundToFaceLockService) {
            stopAndUnbindFromFaceLock();
            return true;
        }
        return false;
    }

    // Handles covering or exposing FaceLock area on the client side when FaceLock starts or stops
    // This needs to be done in a handler because the call could be coming from a callback from the
    // FaceLock service that is in a thread that can't modify the UI
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
        case MSG_SHOW_FACELOCK_AREA_VIEW:
            if (mFaceLockAreaView != null) {
                mFaceLockAreaView.setVisibility(View.VISIBLE);
            }
            break;
        case MSG_HIDE_FACELOCK_AREA_VIEW:
            if (mFaceLockAreaView != null) {
                mFaceLockAreaView.setVisibility(View.INVISIBLE);
            }
            break;
        default:
            Log.w(TAG, "Unhandled message");
            return false;
        }
        return true;
    }

    // Removes show and hide messages from the message queue
    private void removeFaceLockAreaDisplayMessages() {
        mHandler.removeMessages(MSG_SHOW_FACELOCK_AREA_VIEW);
        mHandler.removeMessages(MSG_HIDE_FACELOCK_AREA_VIEW);
    }

    // Shows the FaceLock area immediately
    private void showFaceLockArea() {
        // Remove messages to prevent a delayed hide message from undo-ing the show
        removeFaceLockAreaDisplayMessages();
        mHandler.sendEmptyMessage(MSG_SHOW_FACELOCK_AREA_VIEW);
    }

    // Hides the FaceLock area immediately
    private void hideFaceLockArea() {
        // Remove messages to prevent a delayed show message from undo-ing the hide
        removeFaceLockAreaDisplayMessages();
        mHandler.sendEmptyMessage(MSG_HIDE_FACELOCK_AREA_VIEW);
    }

    // Shows the FaceLock area for a period of time
    private void showFaceLockAreaWithTimeout(long timeoutMillis) {
        showFaceLockArea();
        mHandler.sendEmptyMessageDelayed(MSG_HIDE_FACELOCK_AREA_VIEW, timeoutMillis);
    }

    // Binds to FaceLock service.  This call does not tell it to start, but it causes the service
    // to call the onServiceConnected callback, which then starts FaceLock.
    public void bindToFaceLock() {
        if (usingFaceLock()) {
            if (!mBoundToFaceLockService) {
                if (DEBUG) Log.d(TAG, "before bind to FaceLock service");
                mContext.bindService(new Intent(IFaceLockInterface.class.getName()),
                        mFaceLockConnection,
                        Context.BIND_AUTO_CREATE);
                if (DEBUG) Log.d(TAG, "after bind to FaceLock service");
                mBoundToFaceLockService = true;
            } else {
                Log.w(TAG, "Attempt to bind to FaceLock when already bound");
            }
        }
    }

    // Tells FaceLock to stop and then unbinds from the FaceLock service
    public void stopAndUnbindFromFaceLock() {
        if (usingFaceLock()) {
            stopFaceLock();

            if (mBoundToFaceLockService) {
                if (DEBUG) Log.d(TAG, "before unbind from FaceLock service");
                if (mFaceLockService != null) {
                    try {
                        mFaceLockService.unregisterCallback(mFaceLockCallback);
                    } catch (RemoteException e) {
                        // Not much we can do
                    }
                }
                mContext.unbindService(mFaceLockConnection);
                if (DEBUG) Log.d(TAG, "after unbind from FaceLock service");
                mBoundToFaceLockService = false;
            } else {
                // This is usually not an error when this happens.  Sometimes we will tell it to
                // unbind multiple times because it's called from both onWindowFocusChanged and
                // onDetachedFromWindow.
                if (DEBUG) Log.d(TAG, "Attempt to unbind from FaceLock when not bound");
            }
        }
    }

    private ServiceConnection mFaceLockConnection = new ServiceConnection() {
        // Completes connection, registers callback and starts FaceLock when service is bound
        @Override
        public void onServiceConnected(ComponentName className, IBinder iservice) {
            mFaceLockService = IFaceLockInterface.Stub.asInterface(iservice);
            if (DEBUG) Log.d(TAG, "Connected to FaceLock service");
            try {
                mFaceLockService.registerCallback(mFaceLockCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Caught exception connecting to FaceLock: " + e.toString());
                mFaceLockService = null;
                mBoundToFaceLockService = false;
                return;
            }

            if (mFaceLockAreaView != null) {
                startFaceLock(mFaceLockAreaView.getWindowToken(),
                        mFaceLockAreaView.getLeft(), mFaceLockAreaView.getTop(),
                        mFaceLockAreaView.getWidth(), mFaceLockAreaView.getHeight());
            }
        }

        // Cleans up if FaceLock service unexpectedly disconnects
        @Override
        public void onServiceDisconnected(ComponentName className) {
            synchronized(mFaceLockServiceRunningLock) {
                mFaceLockService = null;
                mFaceLockServiceRunning = false;
            }
            mBoundToFaceLockService = false;
            Log.w(TAG, "Unexpected disconnect from FaceLock service");
        }
    };

    // Tells the FaceLock service to start displaying its UI and perform recognition
    public void startFaceLock(IBinder windowToken, int x, int y, int h, int w)
    {
        if (usingFaceLock()) {
            synchronized (mFaceLockServiceRunningLock) {
                if (!mFaceLockServiceRunning) {
                    if (DEBUG) Log.d(TAG, "Starting FaceLock");
                    try {
                        mFaceLockService.startUi(windowToken, x, y, h, w);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Caught exception starting FaceLock: " + e.toString());
                        return;
                    }
                    mFaceLockServiceRunning = true;
                } else {
                    if (DEBUG) Log.w(TAG, "startFaceLock() attempted while running");
                }
            }
        }
    }

    // Tells the FaceLock service to stop displaying its UI and stop recognition
    public void stopFaceLock()
    {
        if (usingFaceLock()) {
            // Note that attempting to stop FaceLock when it's not running is not an issue.
            // FaceLock can return, which stops it and then we try to stop it when the
            // screen is turned off.  That's why we check.
            synchronized (mFaceLockServiceRunningLock) {
                if (mFaceLockServiceRunning) {
                    try {
                        if (DEBUG) Log.d(TAG, "Stopping FaceLock");
                        mFaceLockService.stopUi();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Caught exception stopping FaceLock: " + e.toString());
                    }
                    mFaceLockServiceRunning = false;
                }
            }
        }
    }

    // Implements the FaceLock service callback interface defined in AIDL
    private final IFaceLockCallback mFaceLockCallback = new IFaceLockCallback.Stub() {

        // Stops the FaceLock UI and indicates that the phone should be unlocked
        @Override
        public void unlock() {
            if (DEBUG) Log.d(TAG, "FaceLock unlock()");
            showFaceLockArea(); // Keep fallback covered
            stopFaceLock();

            mKeyguardScreenCallback.keyguardDone(true);
            mKeyguardScreenCallback.reportSuccessfulUnlockAttempt();
        }

        // Stops the FaceLock UI and exposes the backup method without unlocking
        // This means the user has cancelled out
        @Override
        public void cancel() {
            if (DEBUG) Log.d(TAG, "FaceLock cancel()");
            hideFaceLockArea(); // Expose fallback
            stopFaceLock();
            mKeyguardScreenCallback.pokeWakelock(BACKUP_LOCK_TIMEOUT);
        }

        // Stops the FaceLock UI and exposes the backup method without unlocking
        // This means FaceLock failed to recognize them
        @Override
        public void reportFailedAttempt() {
            if (DEBUG) Log.d(TAG, "FaceLock reportFailedAttempt()");
            mFailedFaceUnlockAttempts++;
            hideFaceLockArea(); // Expose fallback
            stopFaceLock();
            mKeyguardScreenCallback.pokeWakelock(BACKUP_LOCK_TIMEOUT);
        }

        // Removes the black area that covers the backup unlock method
        @Override
        public void exposeFallback() {
            if (DEBUG) Log.d(TAG, "FaceLock exposeFallback()");
            hideFaceLockArea(); // Expose fallback
        }

        // Allows the Face Unlock service to poke the wake lock to keep the lockscreen alive
        @Override
        public void pokeWakelock() {
            if (DEBUG) Log.d(TAG, "FaceLock pokeWakelock()");
            mKeyguardScreenCallback.pokeWakelock();
        }
    };
}
