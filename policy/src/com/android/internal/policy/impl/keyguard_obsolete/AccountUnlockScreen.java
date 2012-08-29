/*
 * Copyright (C) 2008 The Android Open Source Project
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
import com.android.internal.widget.LockPatternUtils;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OperationCanceledException;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.AccountManagerCallback;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputFilter;
import android.text.LoginFilter;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.os.Bundle;

import java.io.IOException;

/**
 * When the user forgets their password a bunch of times, we fall back on their
 * account's login/password to unlock the phone (and reset their lock pattern).
 */
public class AccountUnlockScreen extends RelativeLayout implements KeyguardScreen,
        View.OnClickListener, TextWatcher {
    private static final String LOCK_PATTERN_PACKAGE = "com.android.settings";
    private static final String LOCK_PATTERN_CLASS = LOCK_PATTERN_PACKAGE + ".ChooseLockGeneric";

    /**
     * The amount of millis to stay awake once this screen detects activity
     */
    private static final int AWAKE_POKE_MILLIS = 30000;

    private KeyguardScreenCallback mCallback;
    private LockPatternUtils mLockPatternUtils;
    private KeyguardUpdateMonitor mUpdateMonitor;

    private TextView mTopHeader;
    private TextView mInstructions;
    private EditText mLogin;
    private EditText mPassword;
    private Button mOk;

    /**
     * Shown while making asynchronous check of password.
     */
    private ProgressDialog mCheckingDialog;
    private KeyguardStatusViewManager mKeyguardStatusViewManager;

    /**
     * AccountUnlockScreen constructor.
     * @param configuration
     * @param updateMonitor
     */
    public AccountUnlockScreen(Context context, Configuration configuration,
            KeyguardUpdateMonitor updateMonitor, KeyguardScreenCallback callback,
            LockPatternUtils lockPatternUtils) {
        super(context);
        mCallback = callback;
        mLockPatternUtils = lockPatternUtils;

        LayoutInflater.from(context).inflate(
                R.layout.keyguard_screen_glogin_unlock, this, true);

        mTopHeader = (TextView) findViewById(R.id.topHeader);
        mTopHeader.setText(mLockPatternUtils.isPermanentlyLocked() ?
                R.string.lockscreen_glogin_too_many_attempts :
                R.string.lockscreen_glogin_forgot_pattern);

        mInstructions = (TextView) findViewById(R.id.instructions);

        mLogin = (EditText) findViewById(R.id.login);
        mLogin.setFilters(new InputFilter[] { new LoginFilter.UsernameFilterGeneric() } );
        mLogin.addTextChangedListener(this);

        mPassword = (EditText) findViewById(R.id.password);
        mPassword.addTextChangedListener(this);

        mOk = (Button) findViewById(R.id.ok);
        mOk.setOnClickListener(this);

        mUpdateMonitor = updateMonitor;

        mKeyguardStatusViewManager = new KeyguardStatusViewManager(this, updateMonitor,
                lockPatternUtils, callback, true);
    }

    public void afterTextChanged(Editable s) {
    }

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
        mCallback.pokeWakelock(AWAKE_POKE_MILLIS);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction,
            Rect previouslyFocusedRect) {
        // send focus to the login field
        return mLogin.requestFocus(direction, previouslyFocusedRect);
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return true;
    }

    /** {@inheritDoc} */
    public void onPause() {
        mKeyguardStatusViewManager.onPause();
    }

    /** {@inheritDoc} */
    public void onResume() {
        // start fresh
        mLogin.setText("");
        mPassword.setText("");
        mLogin.requestFocus();
        mKeyguardStatusViewManager.onResume();
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        if (mCheckingDialog != null) {
            mCheckingDialog.hide();
        }
        mUpdateMonitor.removeCallback(this); // this must be first
        mCallback = null;
        mLockPatternUtils = null;
        mUpdateMonitor = null;
    }

    /** {@inheritDoc} */
    public void onClick(View v) {
        mCallback.pokeWakelock();
        if (v == mOk) {
            asyncCheckPassword();
        }
    }

    private void postOnCheckPasswordResult(final boolean success) {
        // ensure this runs on UI thread
        mLogin.post(new Runnable() {
            public void run() {
                if (success) {
                    // clear out forgotten password
                    mLockPatternUtils.setPermanentlyLocked(false);
                    mLockPatternUtils.setLockPatternEnabled(false);
                    mLockPatternUtils.saveLockPattern(null);

                    // launch the 'choose lock pattern' activity so
                    // the user can pick a new one if they want to
                    Intent intent = new Intent();
                    intent.setClassName(LOCK_PATTERN_PACKAGE, LOCK_PATTERN_CLASS);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(intent);
                    mCallback.reportSuccessfulUnlockAttempt();

                    // close the keyguard
                    mCallback.keyguardDone(true);
                } else {
                    mInstructions.setText(R.string.lockscreen_glogin_invalid_input);
                    mPassword.setText("");
                    mCallback.reportFailedUnlockAttempt();
                }
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (mLockPatternUtils.isPermanentlyLocked()) {
                mCallback.goToLockScreen();
            } else {
                mCallback.forgotPattern(false);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Given the string the user entered in the 'username' field, find
     * the stored account that they probably intended.  Prefer, in order:
     *
     *   - an exact match for what was typed, or
     *   - a case-insensitive match for what was typed, or
     *   - if they didn't include a domain, an exact match of the username, or
     *   - if they didn't include a domain, a case-insensitive
     *     match of the username.
     *
     * If there is a tie for the best match, choose neither --
     * the user needs to be more specific.
     *
     * @return an account name from the database, or null if we can't
     * find a single best match.
     */
    private Account findIntendedAccount(String username) {
        Account[] accounts = AccountManager.get(mContext).getAccountsByType("com.google");

        // Try to figure out which account they meant if they
        // typed only the username (and not the domain), or got
        // the case wrong.

        Account bestAccount = null;
        int bestScore = 0;
        for (Account a: accounts) {
            int score = 0;
            if (username.equals(a.name)) {
                score = 4;
            } else if (username.equalsIgnoreCase(a.name)) {
                score = 3;
            } else if (username.indexOf('@') < 0) {
                int i = a.name.indexOf('@');
                if (i >= 0) {
                    String aUsername = a.name.substring(0, i);
                    if (username.equals(aUsername)) {
                        score = 2;
                    } else if (username.equalsIgnoreCase(aUsername)) {
                        score = 1;
                    }
                }
            }
            if (score > bestScore) {
                bestAccount = a;
                bestScore = score;
            } else if (score == bestScore) {
                bestAccount = null;
            }
        }
        return bestAccount;
    }

    private void asyncCheckPassword() {
        mCallback.pokeWakelock(AWAKE_POKE_MILLIS);
        final String login = mLogin.getText().toString();
        final String password = mPassword.getText().toString();
        Account account = findIntendedAccount(login);
        if (account == null) {
            postOnCheckPasswordResult(false);
            return;
        }
        getProgressDialog().show();
        Bundle options = new Bundle();
        options.putString(AccountManager.KEY_PASSWORD, password);
        AccountManager.get(mContext).confirmCredentials(account, options, null /* activity */,
                new AccountManagerCallback<Bundle>() {
            public void run(AccountManagerFuture<Bundle> future) {
                try {
                    mCallback.pokeWakelock(AWAKE_POKE_MILLIS);
                    final Bundle result = future.getResult();
                    final boolean verified = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
                    postOnCheckPasswordResult(verified);
                } catch (OperationCanceledException e) {
                    postOnCheckPasswordResult(false);
                } catch (IOException e) {
                    postOnCheckPasswordResult(false);
                } catch (AuthenticatorException e) {
                    postOnCheckPasswordResult(false);
                } finally {
                    mLogin.post(new Runnable() {
                        public void run() {
                            getProgressDialog().hide();
                        }
                    });
                }
            }
        }, null /* handler */);
    }

    private Dialog getProgressDialog() {
        if (mCheckingDialog == null) {
            mCheckingDialog = new ProgressDialog(mContext);
            mCheckingDialog.setMessage(
                    mContext.getString(R.string.lockscreen_glogin_checking_password));
            mCheckingDialog.setIndeterminate(true);
            mCheckingDialog.setCancelable(false);
            mCheckingDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        return mCheckingDialog;
    }
}
