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
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.widget.Toast;
import android.util.Log;

/**
 * This activity is shown to the user in two cases: when a connection is possible via
 * a usb tether and when any type of tether is connected.  In the connecting case
 * It allows them to start a USB tether.  In the Tethered/disconnecting case it
 * will disconnect all tethers.
 */
public class TetherActivity extends AlertActivity implements
        DialogInterface.OnClickListener {

    private static final int POSITIVE_BUTTON = AlertDialog.BUTTON1;

    // count of the number of tethered connections at activity create time.
    private int mTethered;

    /* Used to detect when the USB cable is unplugged, so we can call finish() */
    private BroadcastReceiver mTetherReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == ConnectivityManager.ACTION_TETHER_STATE_CHANGED) {
                handleTetherStateChanged(intent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // determine if we advertise tethering or untethering
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        mTethered = cm.getTetheredIfaces().length;
        int tetherable = cm.getTetherableIfaces().length;
        if ((mTethered == 0) && (tetherable == 0)) {
            finish();
            return;
        }

        // Set up the dialog
        // if we have a tethered connection we put up a "Do you want to Disconect" dialog
        // otherwise we must have a tetherable interface (else we'd return above)
        // and so we want to put up the "do you want to connect" dialog
        if (mTethered == 0) {
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
        int error = ConnectivityManager.TETHER_ERROR_NO_ERROR;

        if (which == POSITIVE_BUTTON) {
            ConnectivityManager cm =
                    (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            // start/stop tethering
            String[] tethered = cm.getTetheredIfaces();

            if (tethered.length == 0) {
                String[] tetherable = cm.getTetherableIfaces();
                String[] usbRegexs = cm.getTetherableUsbRegexs();
                for (String t : tetherable) {
                    for (String r : usbRegexs) {
                        if (t.matches(r)) {
                            error = cm.tether(t);
                            break;
                        }
                    }
                }
                showTetheringError(error);
            } else {
                for (String t : tethered) {
                    error = cm.untether(t);
                }
                showUnTetheringError(error);
            }
        }
        // No matter what, finish the activity
        finish();
    }

    private void handleTetherStateChanged(Intent intent) {
        // determine if we advertise tethering or untethering
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if (mTethered != cm.getTetheredIfaces().length) {
            finish();
        }
    }

    private void showTetheringError(int error) {
        switch(error) {
        case ConnectivityManager.TETHER_ERROR_NO_ERROR:
            return;
        default:
            Toast.makeText(this, com.android.internal.R.string.tether_error_message,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showUnTetheringError(int error) {
        switch(error) {
        case ConnectivityManager.TETHER_ERROR_NO_ERROR:
            return;
        default:
            Toast.makeText(this, com.android.internal.R.string.tether_stop_error_message,
                    Toast.LENGTH_LONG).show();
        }
    }
}
