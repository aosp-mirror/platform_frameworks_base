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

import android.app.ProgressDialog;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.os.RemoteException;
import android.os.Power;
import android.os.ServiceManager;
import android.os.SystemClock;
import com.android.internal.telephony.ITelephony;
import android.util.Log;
import android.view.WindowManager;

 
final class ShutdownThread extends Thread {
    // constants
    private static final String TAG = "ShutdownThread";
    private static final int MAX_NUM_PHONE_STATE_READS = 16;
    private static final int PHONE_STATE_POLL_SLEEP_MSEC = 500;
    private static final ITelephony sPhone =
        ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
    private static final IBluetoothDevice sBluetooth =
        IBluetoothDevice.Stub.asInterface(ServiceManager.getService(Context.BLUETOOTH_SERVICE));


    // state tracking
    private static Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;
    
    // static instance of this thread
    private static final ShutdownThread sInstance = new ShutdownThread();
    
    private ShutdownThread() {
    }
 
    /** 
     * request a shutdown. 
     * 
     * @param context Context used to display the shutdown progress dialog.
     */
    public static void shutdownAfterDisablingRadio(final Context context, boolean confirm){
        // ensure that only one thread is trying to power down.
        // any additional calls are just returned
        synchronized (sIsStartedGuard){
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
        }

        Log.d(TAG, "Notifying thread to start radio shutdown");

        if (confirm) {
            final AlertDialog dialog = new AlertDialog.Builder(context)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(com.android.internal.R.string.power_off)
                    .setMessage(com.android.internal.R.string.shutdown_confirm)
                    .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            beginShutdownSequence(context);
                        }
                    })
                    .setNegativeButton(com.android.internal.R.string.no, null)
                    .create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            dialog.show();
        } else {
            beginShutdownSequence(context);
        }
    }

    private static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            sIsStarted = true;
        }

        // throw up an indeterminate system dialog to indicate radio is
        // shutting down.
        ProgressDialog pd = new ProgressDialog(context);
        pd.setTitle(context.getText(com.android.internal.R.string.power_off));
        pd.setMessage(context.getText(com.android.internal.R.string.shutdown_progress));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        pd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

        pd.show();

        // start the thread that initiates shutdown
        sInstance.start();
    }

    /**
     * Makes sure we handle the shutdown gracefully.
     * Shuts off power regardless of radio and bluetooth state if the alloted time has passed.
     */
    public void run() {
        boolean bluetoothOff;
        boolean radioOff;

        try {
            bluetoothOff = sBluetooth == null ||
                           sBluetooth.getBluetoothState() == BluetoothDevice.BLUETOOTH_STATE_OFF;
            if (!bluetoothOff) {
                sBluetooth.disable(false);  // disable but don't persist new state
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
            bluetoothOff = true;
        }

        try {
            radioOff = sPhone == null || !sPhone.isRadioOn();
            if (!radioOff) {
                sPhone.setRadio(false);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException during radio shutdown", ex);
            radioOff = true;
        }

        // Wait a max of 32 seconds for clean shutdown
        for (int i = 0; i < MAX_NUM_PHONE_STATE_READS; i++) {
            if (!bluetoothOff) {
                try {
                    bluetoothOff =
                            sBluetooth.getBluetoothState() == BluetoothDevice.BLUETOOTH_STATE_OFF;
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
                    bluetoothOff = true;
                }
            }
            if (!radioOff) {
                try {
                    radioOff = !sPhone.isRadioOn();
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during radio shutdown", ex);
                    radioOff = true;
                }
            }
            if (radioOff && bluetoothOff) {
                Log.d(TAG, "Radio and Bluetooth shutdown complete.");
                break;
            }
            SystemClock.sleep(PHONE_STATE_POLL_SLEEP_MSEC);
        }

        //shutdown power
        Log.d(TAG, "Shutting down power.");
        Power.shutdown();
    }
}
