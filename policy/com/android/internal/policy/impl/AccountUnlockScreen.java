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

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

import android.accounts.AccountsServiceConstants;
import android.accounts.IAccountsService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.Editable;
import android.text.InputFilter;
import android.text.LoginFilter;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * When the user forgets their password a bunch of times, we fall back on their
 * account's login/password to unlock the phone (and reset their lock pattern).
 *
 * <p>This class is useful only on platforms that support the
 * IAccountsService.
 */
public class AccountUnlockScreen extends RelativeLayout implements KeyguardScreen,
        View.OnClickListener, ServiceConnection, TextWatcher {
    private static final String LOCK_PATTERN_PACKAGE = "com.android.settings";
    private static final String LOCK_PATTERN_CLASS =
            "com.android.settings.ChooseLockPattern";

    /**
     * The amount of millis to stay awake once this screen detects activity
     */
    private static final int AWAKE_POKE_MILLIS = 30000;

    private final KeyguardScreenCallback mCallback;
    private final LockPatternUtils mLockPatternUtils;
    private IAccountsService mAccountsService;

    private TextView mTopHeader;
    private TextView mInstructions;
    private EditText mLogin;
    private EditText mPassword;
    private Button mOk;
    private Button mEmergencyCall;

    /**
     * AccountUnlockScreen constructor.
     *
     * @throws IllegalStateException if the IAccountsService is not
     * available on the current platform.
     */
    public AccountUnlockScreen(Context context,
            KeyguardScreenCallback callback,
            LockPatternUtils lockPatternUtils) {
        super(context);
        mCallback = callback;
        mLockPatternUtils = lockPatternUtils;

        LayoutInflater.from(context).inflate(
                R.layout.keyguard_screen_glogin_unlock, this, true);

        mTopHeader = (TextView) findViewById(R.id.topHeader);

        mInstructions = (TextView) findViewById(R.id.instructions);

        mLogin = (EditText) findViewById(R.id.login);
        mLogin.setFilters(new InputFilter[] { new LoginFilter.UsernameFilterGeneric() } );
        mLogin.addTextChangedListener(this);

        mPassword = (EditText) findViewById(R.id.password);
        mPassword.addTextChangedListener(this);

        mOk = (Button) findViewById(R.id.ok);
        mOk.setOnClickListener(this);

        mEmergencyCall = (Button) findViewById(R.id.emergencyCall);
        mEmergencyCall.setOnClickListener(this);

        Log.v("AccountUnlockScreen", "debug: Connecting to accounts service");
        final boolean connected = mContext.bindService(AccountsServiceConstants.SERVICE_INTENT,
                this, Context.BIND_AUTO_CREATE);
        if (!connected) {
            Log.v("AccountUnlockScreen", "debug: Couldn't connect to accounts service");
            throw new IllegalStateException("couldn't bind to accounts service");
        }
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

    }

    /** {@inheritDoc} */
    public void onResume() {
        // start fresh
        mLogin.setText("");
        mPassword.setText("");
        mLogin.requestFocus();
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        mContext.unbindService(this);
    }

    /** {@inheritDoc} */
    public void onClick(View v) {
        mCallback.pokeWakelock();
        if (v == mOk) {
            if (checkPassword()) {
                // clear out forgotten password
                mLockPatternUtils.setPermanentlyLocked(false);

                // launch the 'choose lock pattern' activity so
                // the user can pick a new one if they want to
                Intent intent = new Intent();
                intent.setClassName(LOCK_PATTERN_PACKAGE, LOCK_PATTERN_CLASS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);

                // close the keyguard
                mCallback.keyguardDone(true);
            } else {
                mInstructions.setText(R.string.lockscreen_glogin_invalid_input);
                mPassword.setText("");
            }
        }

        if (v == mEmergencyCall) {
            mCallback.takeEmergencyCallAction();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            mCallback.goToLockScreen();
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
    private String findIntendedAccount(String username) {
        String[] accounts = null;
        try {
            accounts = mAccountsService.getAccounts();
        } catch (RemoteException e) {
            return null;
        }
        if (accounts == null) {
            return null;
        }

        // Try to figure out which account they meant if they
        // typed only the username (and not the domain), or got
        // the case wrong.

        String bestAccount = null;
        int bestScore = 0;
        for (String a: accounts) {
            int score = 0;
            if (username.equals(a)) {
                score = 4;
            } else if (username.equalsIgnoreCase(a)) {
                score = 3;
            } else if (username.indexOf('@') < 0) {
                int i = a.indexOf('@');
                if (i >= 0) {
                    String aUsername = a.substring(0, i);
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

    private boolean checkPassword() {
        final String login = mLogin.getText().toString();
        final String password = mPassword.getText().toString();
        try {
            String account = findIntendedAccount(login);
            if (account == null) {
                return false;
            }
            return mAccountsService.shouldUnlock(account, password);
        } catch (RemoteException e) {
            return false;
        }
    }

    /** {@inheritDoc} */
    public void onServiceConnected(ComponentName name, IBinder service) {
        Log.v("AccountUnlockScreen", "debug: About to grab as interface");
        mAccountsService = IAccountsService.Stub.asInterface(service);
    }

    /** {@inheritDoc} */
    public void onServiceDisconnected(ComponentName name) {
        mAccountsService = null;
    }
}
