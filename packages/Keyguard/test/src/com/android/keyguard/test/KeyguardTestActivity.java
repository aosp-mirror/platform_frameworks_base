/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard.test;

import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManagerPolicy;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView.Cell;

import java.util.List;

public class KeyguardTestActivity extends Activity implements OnClickListener {
    private static final String KEYGUARD_PACKAGE = "com.android.keyguard";
    private static final String KEYGUARD_CLASS = "com.android.keyguard.KeyguardService";
    private static final String TAG = "LockScreenTestActivity";
    private static final int MODE_NONE = 0;
    private static final int MODE_PIN = 1;
    private static final int MODE_PASSWORD = 2;
    private static final int MODE_PATTERN = 3;
    private static final int MODE_SIM_PIN = 4;
    private static final int MODE_SIM_PUK = 5;
    private static final String SECURITY_MODE = "security_mode";
    Handler mHandler = new Handler();

    IKeyguardService mService = null;

    KeyguardShowCallback mKeyguardShowCallback = new KeyguardShowCallback();
    KeyguardExitCallback mKeyguardExitCallback = new KeyguardExitCallback();

    RemoteServiceConnection mConnection;
    private boolean mSentSystemReady;

    class KeyguardShowCallback extends IKeyguardShowCallback.Stub {

        @Override
        public void onShown(IBinder windowToken) throws RemoteException {
            Log.v(TAG, "Keyguard is shown, windowToken = " + windowToken);
        }
    }

    class KeyguardExitCallback extends IKeyguardExitCallback.Stub {

        @Override
        public void onKeyguardExitResult(final boolean success) throws RemoteException {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(KeyguardTestActivity.this)
                    .setMessage("Result: " + success)
                    .setPositiveButton("OK", null)
                    .show();
                }
            });
        }
    };

    private class RemoteServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.v(TAG, "onServiceConnected()");
            mService = IKeyguardService.Stub.asInterface(service);
            try {
                mService.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        new AlertDialog.Builder(KeyguardTestActivity.this)
                            .setMessage("Oops! Keygued died")
                            .setPositiveButton("OK", null)
                            .show();
                    }
                }, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Couldn't linkToDeath");
                e.printStackTrace();
            }
//            try {
//                mService.onSystemReady();
//            } catch (RemoteException e) {
//                Log.v(TAG, "Remote service died trying to call onSystemReady");
//                e.printStackTrace();
//            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.v(TAG, "onServiceDisconnected()");
            mService = null;
        }
    };

    private void bindService() {
        if (mConnection == null) {
            mConnection = new RemoteServiceConnection();
            Intent intent = new Intent();
            intent.setClassName(KEYGUARD_PACKAGE, KEYGUARD_CLASS);
            Log.v(TAG, "BINDING SERVICE: " + KEYGUARD_CLASS);
            if (!bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
                Log.v(TAG, "FAILED TO BIND TO KEYGUARD!");
            }
        } else {
            Log.v(TAG, "Service already bound");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.keyguard_test_activity);
        final int[] buttons = {
                R.id.on_screen_turned_off, R.id.on_screen_turned_on,
                R.id.do_keyguard, R.id.verify_unlock
        };
        for (int i = 0; i < buttons.length; i++) {
            findViewById(buttons[i]).setOnClickListener(this);
        }
        Log.v(TAG, "Binding service...");
        bindService();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SECURITY_MODE, mSecurityMode);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        setMode(savedInstanceState.getInt(SECURITY_MODE));
    }

// TODO: Find a secure way to inject mock into keyguard...
//    @Override
//    public boolean onCreateOptionsMenu(Menu menu) {
//        MenuInflater inflater = getMenuInflater();
//        inflater.inflate(R.menu.optionmenu, menu);
//        return true;
//    }

    private void setMode(int mode) {
        mTestSimPin = false;
        mTestSimPuk = false;
        mLockPasswordEnabled = false;
        mLockPatternEnabled = false;
        switch(mode) {
            case MODE_NONE:
                mSecurityModeMock = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
                break;
            case MODE_PIN:
                mSecurityModeMock = DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
                mLockPasswordEnabled = true;
                break;
            case MODE_PASSWORD:
                mSecurityModeMock = DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
                mLockPasswordEnabled = true;
                break;
            case MODE_PATTERN:
                mSecurityModeMock = DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
                mLockPatternEnabled = true;
                break;
            case MODE_SIM_PIN:
                mTestSimPin = true;
                break;
            case MODE_SIM_PUK:
                mTestSimPuk = true;
                break;
        }
        mSecurityMode = mode;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.none_menu_item:
                setMode(MODE_NONE);
                break;
            case R.id.pin_menu_item:
                setMode(MODE_PIN);
                break;
            case R.id.password_menu_item:
                setMode(MODE_PASSWORD);
                break;
            case R.id.pattern_menu_item:
                setMode(MODE_PATTERN);
                break;
            case R.id.sim_pin_menu_item:
                setMode(MODE_SIM_PIN);
                break;
            case R.id.sim_puk_menu_item:
                setMode(MODE_SIM_PUK);
                break;
            case R.id.add_widget_item:
                startWidgetPicker();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        try {
            mService.doKeyguardTimeout(null);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote service died");
            e.printStackTrace();
        }
        return true;
    }

    private void startWidgetPicker() {
        startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
    }

    @Override
    public void onClick(View v) {
        try {
            switch (v.getId()) {
            case R.id.on_screen_turned_on:
                mService.onScreenTurnedOn(mKeyguardShowCallback);
                break;
            case R.id.on_screen_turned_off:
                mService.onScreenTurnedOff(WindowManagerPolicy.OFF_BECAUSE_OF_USER);
                break;
            case R.id.do_keyguard:
                if (!mSentSystemReady) {
                    mSentSystemReady = true;
                    mService.onSystemReady();
                }
                mService.doKeyguardTimeout(null);
                break;
            case R.id.verify_unlock:
                mService.doKeyguardTimeout(null);
                // Wait for keyguard to lock and then try this...
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mService.verifyUnlock(mKeyguardExitCallback);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed verifyUnlock()", e);
                        }
                    }
                }, 5000);
                break;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onClick(): Failed due to remote exeption", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (mService != null) {
                mService.setHidden(true);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Remote service died");
            e.printStackTrace();
        }
    }

    protected void onResume() {
        super.onResume();
        try {
            if (mService != null) {
                mService.setHidden(false);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Remote service died");
            e.printStackTrace();
        }
    }

    public int mSecurityModeMock;
    private boolean mTestSimPin;
    private boolean mTestSimPuk;
    private boolean mLockPasswordEnabled;
    public boolean mLockPatternEnabled;
    private int mSecurityMode;

    class LockPatternUtilsMock extends LockPatternUtils {
        private long mDeadline;
        public LockPatternUtilsMock(Context context) {
            super(context);
        }

        @Override
        public boolean checkPattern(List<Cell> pattern) {
            return pattern.size() > 4;
        }

        @Override
        public boolean checkPassword(String password) {
            return password.length() > 4;
        }
        @Override
        public long setLockoutAttemptDeadline() {
            final long deadline = SystemClock.elapsedRealtime() + FAILED_ATTEMPT_TIMEOUT_MS;
            mDeadline = deadline;
            return deadline;
        }
        @Override
        public boolean isLockScreenDisabled() {
            return false;
        }
        @Override
        public long getLockoutAttemptDeadline() {
            return mDeadline;
        }
        @Override
        public void reportFailedPasswordAttempt() {
            // Ignored
        }
        @Override
        public void reportSuccessfulPasswordAttempt() {
            // Ignored
        }
        @Override
        public boolean isLockPatternEnabled() {
            return mLockPatternEnabled;
        }

        @Override
        public boolean isLockPasswordEnabled() {
            return mLockPasswordEnabled;
        }

        @Override
        public int getKeyguardStoredPasswordQuality() {
            return mSecurityModeMock;
        }

        public boolean isSecure() {
            return mLockPatternEnabled || mLockPasswordEnabled || mTestSimPin || mTestSimPuk;
        }

    }
}
