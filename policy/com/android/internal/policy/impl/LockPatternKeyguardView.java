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

import android.accounts.AccountsServiceConstants;
import android.accounts.IAccountsService;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import com.android.internal.telephony.SimCard;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.ColorFilter;
import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

/**
 * The host view for all of the screens of the pattern unlock screen.  There are
 * two {@link Mode}s of operation, lock and unlock.  This will show the appropriate
 * screen, and listen for callbacks via {@link com.android.internal.policy.impl.KeyguardScreenCallback
 * from the current screen.
 *
 * This view, in turn, communicates back to {@link com.android.internal.policy.impl.KeyguardViewManager}
 * via its {@link com.android.internal.policy.impl.KeyguardViewCallback}, as appropriate.
 */
public class LockPatternKeyguardView extends KeyguardViewBase {

    // intent action for launching emergency dialer activity.
    static final String ACTION_EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL";

    private static final boolean DEBUG = false;
    private static final String TAG = "LockPatternKeyguardView";

    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardWindowController mWindowController;
    
    private View mLockScreen;
    private View mUnlockScreen;

    private boolean mScreenOn = false;
    private boolean mHasAccount = false; // assume they don't have an account until we know better


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
         * Unlock by entering an account's login and password.
         */
        Account
    }

    /**
     * The current mode.
     */
    private Mode mMode = Mode.LockScreen;

    /**
     * Keeps track of what mode the current unlock screen is
     */
    private UnlockMode mUnlockScreenMode;

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
     * Used to fetch accounts from GLS.
     */
    private ServiceConnection mServiceConnection;

    /**
     * @return Whether we are stuck on the lock screen because the sim is
     *   missing.
     */
    private boolean stuckOnLockScreenBecauseSimMissing() {
        return mRequiresSim
                && (!mUpdateMonitor.isDeviceProvisioned())
                && (mUpdateMonitor.getSimState() == SimCard.State.ABSENT);
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
        
        asyncCheckForAccount();
        
        mRequiresSim =
                TextUtils.isEmpty(SystemProperties.get("keyguard.no_require_sim"));

        mUpdateMonitor = updateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mWindowController = controller;

        mMode = getInitialMode();
        
        mKeyguardScreenCallback = new KeyguardScreenCallback() {

            public void goToLockScreen() {
                if (mIsVerifyUnlockOnly) {
                    // navigating away from unlock screen during verify mode means
                    // we are done and the user failed to authenticate.
                    mIsVerifyUnlockOnly = false;
                    getCallback().keyguardDone(false);
                } else {
                    updateScreen(Mode.LockScreen);
                }
            }

            public void goToUnlockScreen() {
                final SimCard.State simState = mUpdateMonitor.getSimState();
                if (stuckOnLockScreenBecauseSimMissing()
                         || (simState == SimCard.State.PUK_REQUIRED)){
                    // stuck on lock screen when sim missing or puk'd
                    return;
                }
                if (!isSecure()) {
                    getCallback().keyguardDone(true);
                } else {
                    updateScreen(Mode.UnlockScreen);
                }
            }

            public boolean isSecure() {
                return LockPatternKeyguardView.this.isSecure();
            }

            public boolean isVerifyUnlockOnly() {
                return mIsVerifyUnlockOnly;
            }

            public void recreateMe() {
                recreateScreens();
            }

            public void takeEmergencyCallAction() {
                Intent intent = new Intent(ACTION_EMERGENCY_DIAL);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                getContext().startActivity(intent);
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

            public void reportFailedPatternAttempt() {
                mUpdateMonitor.reportFailedAttempt();
                final int failedAttempts = mUpdateMonitor.getFailedAttempts();
                if (mHasAccount && failedAttempts ==
                        (LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET 
                                - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT)) {
                    showAlmostAtAccountLoginDialog();
                } else if (mHasAccount
                        && failedAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET) {
                    mLockPatternUtils.setPermanentlyLocked(true);
                    updateScreen(mMode);
                } else if ((failedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT)
                        == 0) {
                    showTimeoutDialog();
                }
            }
            
            public boolean doesFallbackUnlockScreenExist() {
                return mHasAccount;
            }
        };

        /**
         * We'll get key events the current screen doesn't use. see
         * {@link KeyguardViewBase#onKeyDown(int, android.view.KeyEvent)}
         */
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        // wall paper background
        final BitmapDrawable drawable = (BitmapDrawable) context.getWallpaper();
        setBackgroundDrawable(
                new FastBitmapDrawable(drawable.getBitmap()));

        // create both the lock and unlock screen so they are quickly available
        // when the screen turns on
        mLockScreen = createLockScreen();
        addView(mLockScreen);
        final UnlockMode unlockMode = getUnlockMode();
        mUnlockScreen = createUnlockScreenFor(unlockMode);
        mUnlockScreenMode = unlockMode;
        addView(mUnlockScreen);
        updateScreen(mMode);
    }

    /** 
     * Asynchronously checks for at least one account. This will set mHasAccount
     * to true if an account is found.
     */
    private void asyncCheckForAccount() {
        
        mServiceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder service) {
                try {
                    IAccountsService accountsService = IAccountsService.Stub.asInterface(service);
                    String accounts[] = accountsService.getAccounts();
                    mHasAccount = (accounts.length > 0);
                } catch (RemoteException e) {
                    // Not much we can do here...
                    Log.e(TAG, "Gls died while attempting to get accounts: " + e);
                } finally {
                    getContext().unbindService(mServiceConnection);
                    mServiceConnection = null;
                }
            }

            public void onServiceDisconnected(ComponentName className) {
                // nothing to do here
            }
        };
        boolean status = getContext().bindService(AccountsServiceConstants.SERVICE_INTENT,
                mServiceConnection, Context.BIND_AUTO_CREATE);
        if (!status) Log.e(TAG, "Failed to bind to GLS while checking for account");
    }

    @Override
    public void reset() {
        mIsVerifyUnlockOnly = false;
        updateScreen(getInitialMode());
    }

    @Override
    public void onScreenTurnedOff() {
        mScreenOn = false;
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


    private void recreateScreens() {
        if (mLockScreen.getVisibility() == View.VISIBLE) {
            ((KeyguardScreen) mLockScreen).onPause();
        }
        ((KeyguardScreen) mLockScreen).cleanUp();
        removeViewInLayout(mLockScreen);

        mLockScreen = createLockScreen();
        mLockScreen.setVisibility(View.INVISIBLE);
        addView(mLockScreen);

        if (mUnlockScreen.getVisibility() == View.VISIBLE) {
            ((KeyguardScreen) mUnlockScreen).onPause();
        }
        ((KeyguardScreen) mUnlockScreen).cleanUp();
        removeViewInLayout(mUnlockScreen);

        final UnlockMode unlockMode = getUnlockMode();
        mUnlockScreen = createUnlockScreenFor(unlockMode);
        mUnlockScreen.setVisibility(View.INVISIBLE);
        mUnlockScreenMode = unlockMode;
        addView(mUnlockScreen);

        updateScreen(mMode);
    }


    @Override
    public void wakeWhenReadyTq(int keyCode) {
        if (DEBUG) Log.d(TAG, "onWakeKey");
        if (keyCode == KeyEvent.KEYCODE_MENU && isSecure() && (mMode == Mode.LockScreen)
                && (mUpdateMonitor.getSimState() != SimCard.State.PUK_REQUIRED)) {
            if (DEBUG) Log.d(TAG, "switching screens to unlock screen because wake key was MENU");
            updateScreen(Mode.UnlockScreen);
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
        } else if (mUnlockScreenMode != UnlockMode.Pattern) {
            // can only verify unlock when in pattern mode
            getCallback().keyguardDone(false);
        } else {
            // otherwise, go to the unlock screen, see if they can verify it
            mIsVerifyUnlockOnly = true;
            updateScreen(Mode.UnlockScreen);
        }
    }

    @Override
    public void cleanUp() {
        ((KeyguardScreen) mLockScreen).onPause();
        ((KeyguardScreen) mLockScreen).cleanUp();
        ((KeyguardScreen) mUnlockScreen).onPause();
        ((KeyguardScreen) mUnlockScreen).cleanUp();
    }

    private boolean isSecure() {
        UnlockMode unlockMode = getUnlockMode();
        if (unlockMode == UnlockMode.Pattern) {
            return mLockPatternUtils.isLockPatternEnabled();
        } else if (unlockMode == UnlockMode.SimPin) {
            return mUpdateMonitor.getSimState() == SimCard.State.PIN_REQUIRED
                        || mUpdateMonitor.getSimState() == SimCard.State.PUK_REQUIRED;
        } else if (unlockMode == UnlockMode.Account) {
            return true;
        } else {
            throw new IllegalStateException("unknown unlock mode " + unlockMode);
        }
    }

    private void updateScreen(final Mode mode) {

        mMode = mode;

        final View goneScreen = (mode == Mode.LockScreen) ? mUnlockScreen : mLockScreen;
        final View visibleScreen = (mode == Mode.LockScreen)
                ? mLockScreen : getUnlockScreenForCurrentUnlockMode();

        // do this before changing visibility so focus isn't requested before the input
        // flag is set
        mWindowController.setNeedsInput(((KeyguardScreen)visibleScreen).needsInput());
        

        if (mScreenOn) {
            if (goneScreen.getVisibility() == View.VISIBLE) {
                ((KeyguardScreen) goneScreen).onPause();
            }
            if (visibleScreen.getVisibility() != View.VISIBLE) {
                ((KeyguardScreen) visibleScreen).onResume();
            }
        }

        goneScreen.setVisibility(View.GONE);
        visibleScreen.setVisibility(View.VISIBLE);


        if (!visibleScreen.requestFocus()) {
            throw new IllegalStateException("keyguard screen must be able to take "
                    + "focus when shown " + visibleScreen.getClass().getCanonicalName());
        }
    }

    View createLockScreen() {
        return new LockScreen(
                mContext,
                mLockPatternUtils,
                mUpdateMonitor,
                mKeyguardScreenCallback);
    }

    View createUnlockScreenFor(UnlockMode unlockMode) {
        if (unlockMode == UnlockMode.Pattern) {
            return new UnlockScreen(
                    mContext,
                    mLockPatternUtils,
                    mUpdateMonitor,
                    mKeyguardScreenCallback,
                    mUpdateMonitor.getFailedAttempts());
        } else if (unlockMode == UnlockMode.SimPin) {
            return new SimUnlockScreen(
                    mContext,
                    mUpdateMonitor,
                    mKeyguardScreenCallback);
        } else if (unlockMode == UnlockMode.Account) {
            try {
                return new AccountUnlockScreen(
                        mContext,
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
        } else {
            throw new IllegalArgumentException("unknown unlock mode " + unlockMode);
        }
    }

    private View getUnlockScreenForCurrentUnlockMode() {
        final UnlockMode unlockMode = getUnlockMode();

        // if a screen exists for the correct mode, we're done
        if (unlockMode == mUnlockScreenMode) {
            return mUnlockScreen;
        }

        // remember the mode
        mUnlockScreenMode = unlockMode;

        // unlock mode has changed and we have an existing old unlock screen
        // to clean up
        if (mScreenOn && (mUnlockScreen.getVisibility() == View.VISIBLE)) {
            ((KeyguardScreen) mUnlockScreen).onPause();
        }
        ((KeyguardScreen) mUnlockScreen).cleanUp();
        removeViewInLayout(mUnlockScreen);

        // create the new one
        mUnlockScreen = createUnlockScreenFor(unlockMode);
        mUnlockScreen.setVisibility(View.INVISIBLE);
        addView(mUnlockScreen);
        return mUnlockScreen;
    }

    /**
     * Given the current state of things, what should be the initial mode of
     * the lock screen (lock or unlock).
     */
    private Mode getInitialMode() {
        final SimCard.State simState = mUpdateMonitor.getSimState();
        if (stuckOnLockScreenBecauseSimMissing() || (simState == SimCard.State.PUK_REQUIRED)) {
            return Mode.LockScreen;
        } else if (mUpdateMonitor.isKeyboardOpen() && isSecure()) {
            return Mode.UnlockScreen;
        } else {
            return Mode.LockScreen;
        }
    }

    /**
     * Given the current state of things, what should the unlock screen be?
     */
    private UnlockMode getUnlockMode() {
        final SimCard.State simState = mUpdateMonitor.getSimState();
        if (simState == SimCard.State.PIN_REQUIRED || simState == SimCard.State.PUK_REQUIRED) {
            return UnlockMode.SimPin;
        } else {
            return mLockPatternUtils.isPermanentlyLocked() ?
                    UnlockMode.Account:
                    UnlockMode.Pattern;
        }
    }

    private void showTimeoutDialog() {
        int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        String message = mContext.getString(
                R.string.lockscreen_too_many_failed_attempts_dialog_message,
                mUpdateMonitor.getFailedAttempts(),
                timeoutInSeconds);
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setTitle(null)
                .setMessage(message)
                .setNeutralButton(R.string.ok, null)
                .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        dialog.getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        dialog.show();
    }

    private void showAlmostAtAccountLoginDialog() {
        int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        String message = mContext.getString(
                R.string.lockscreen_failed_attempts_almost_glogin,
                LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT,
                LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT,
                timeoutInSeconds);
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setTitle(null)
                .setMessage(message)
                .setNeutralButton(R.string.ok, null)
                .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        dialog.getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        dialog.show();
    }

    /**
     * Used to put wallpaper on the background of the lock screen.  Centers it Horizontally and
     * vertically.
     */
    static private class FastBitmapDrawable extends Drawable {
        private Bitmap mBitmap;

        private FastBitmapDrawable(Bitmap bitmap) {
            mBitmap = bitmap;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(
                    mBitmap,
                    (getBounds().width() - mBitmap.getWidth()) / 2,
                    (getBounds().height() - mBitmap.getHeight()) / 2,
                    null);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
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
