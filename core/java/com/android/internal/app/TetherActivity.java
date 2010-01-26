/*
 * Copyright (C) 2007 Google Inc.
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

package com.android.internal.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IMountService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.widget.Toast;
import android.util.Log;

/**
 * This activity is shown to the user for him/her to connect/disconnect a Tether
 * connection.  It will display notification when a suitable connection is made
 * to allow the tether to be setup.  A second notification will be show when a
 * tether is active, allowing the user to manage tethered connections.
 */
public class TetherActivity extends AlertActivity implements
        DialogInterface.OnClickListener {

    private static final int POSITIVE_BUTTON = AlertDialog.BUTTON1;

    /* Used to detect when the USB cable is unplugged, so we can call finish() */
    private BroadcastReceiver mTetherReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == ConnectivityManager.ACTION_TETHER_STATE_CHANGED) {
                handleTetherStateChanged(intent);
            }
        }
    };

    private boolean mWantTethering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // determine if we advertise tethering or untethering
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm.getTetheredIfaces().length > 0) {
            mWantTethering = false;
        } else if (cm.getTetherableIfaces().length > 0) {
            mWantTethering = true;
        } else {
            finish();
            return;
        }

        // Set up the "dialog"
        if (mWantTethering == true) {
            mAlertParams.mIconId = com.android.internal.R.drawable.ic_dialog_usb;
            mAlertParams.mTitle = getString(com.android.internal.R.string.tether_title);
            mAlertParams.mMessage = getString(com.android.internal.R.string.tether_message);
            mAlertParams.mPositiveButtonText =
                    getString(com.android.internal.R.string.tether_button);
            mAlertParams.mPositiveButtonListener = this;
            mAlertParams.mNegativeButtonText =
                    getString(com.android.internal.R.string.tether_button_cancel);
            mAlertParams.mNegativeButtonListener = this;
        } else {
            mAlertParams.mIconId = com.android.internal.R.drawable.ic_dialog_usb;
            mAlertParams.mTitle = getString(com.android.internal.R.string.tether_stop_title);
            mAlertParams.mMessage = getString(com.android.internal.R.string.tether_stop_message);
            mAlertParams.mPositiveButtonText =
                    getString(com.android.internal.R.string.tether_stop_button);
            mAlertParams.mPositiveButtonListener = this;
            mAlertParams.mNegativeButtonText =
                    getString(com.android.internal.R.string.tether_stop_button_cancel);
            mAlertParams.mNegativeButtonListener = this;
        }
        setupAlert();
    }

    @Override
    protected void onResume() {
        super.onResume();

        registerReceiver(mTetherReceiver, new IntentFilter(
                ConnectivityManager.ACTION_TETHER_STATE_CHANGED));
    }

    @Override
    protected void onPause() {
        super.onPause();

        unregisterReceiver(mTetherReceiver);
    }

    /**
     * {@inheritDoc}
     */
    public void onClick(DialogInterface dialog, int which) {

        if (which == POSITIVE_BUTTON) {
            ConnectivityManager connManager =
                    (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            // start/stop tethering
            if (mWantTethering) {
                if (!connManager.tether("ppp0")) {
                    showTetheringError();
                }
            } else {
                if (!connManager.untether("ppp0")) {
                    showUnTetheringError();
                }
            }
        }
        // No matter what, finish the activity
        finish();
    }

    private void handleTetherStateChanged(Intent intent) {
        finish();
    }

    private void showTetheringError() {
        Toast.makeText(this, com.android.internal.R.string.tether_error_message,
                Toast.LENGTH_LONG).show();
    }

    private void showUnTetheringError() {
        Toast.makeText(this, com.android.internal.R.string.tether_stop_error_message,
                Toast.LENGTH_LONG).show();
    }

}
