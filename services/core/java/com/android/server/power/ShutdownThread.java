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


package com.android.server.power;

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothManager;
import android.media.AudioAttributes;
import android.nfc.NfcAdapter;
import android.nfc.INfcAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.FileUtils;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.os.SystemVibrator;
import android.os.storage.IMountService;
import android.os.storage.IMountShutdownObserver;
import android.system.ErrnoException;
import android.system.Os;

import com.android.internal.telephony.ITelephony;
import com.android.server.pm.PackageManagerService;

import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public final class ShutdownThread extends Thread {
    // constants
    private static final String TAG = "ShutdownThread";
    private static final int PHONE_STATE_POLL_SLEEP_MSEC = 500;
    // maximum time we wait for the shutdown broadcast before going on.
    private static final int MAX_BROADCAST_TIME = 10*1000;
    private static final int MAX_SHUTDOWN_WAIT_TIME = 20*1000;
    private static final int MAX_RADIO_WAIT_TIME = 12*1000;
    private static final int MAX_UNCRYPT_WAIT_TIME = 15*60*1000;
    // constants for progress bar. the values are roughly estimated based on timeout.
    private static final int BROADCAST_STOP_PERCENT = 2;
    private static final int ACTIVITY_MANAGER_STOP_PERCENT = 4;
    private static final int PACKAGE_MANAGER_STOP_PERCENT = 6;
    private static final int RADIO_STOP_PERCENT = 18;
    private static final int MOUNT_SERVICE_STOP_PERCENT = 20;

    // length of vibration before shutting down
    private static final int SHUTDOWN_VIBRATE_MS = 500;

    // state tracking
    private static Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;

    private static boolean mReboot;
    private static boolean mRebootSafeMode;
    private static boolean mRebootHasProgressBar;
    private static String mReason;

    // Provides shutdown assurance in case the system_server is killed
    public static final String SHUTDOWN_ACTION_PROPERTY = "sys.shutdown.requested";

    // Indicates whether we are rebooting into safe mode
    public static final String REBOOT_SAFEMODE_PROPERTY = "persist.sys.safemode";
    public static final String RO_SAFEMODE_PROPERTY = "ro.sys.safemode";

    // Indicates whether we should stay in safe mode until ro.build.date.utc is newer than this
    public static final String AUDIT_SAFEMODE_PROPERTY = "persist.sys.audit_safemode";

    // static instance of this thread
    private static final ShutdownThread sInstance = new ShutdownThread();

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    private final Object mActionDoneSync = new Object();
    private boolean mActionDone;
    private Context mContext;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mCpuWakeLock;
    private PowerManager.WakeLock mScreenWakeLock;
    private Handler mHandler;

    private static AlertDialog sConfirmDialog;
    private ProgressDialog mProgressDialog;

    private ShutdownThread() {
    }

    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param reason code to pass to android_reboot() (e.g. "userrequested"), or null.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void shutdown(final Context context, String reason, boolean confirm) {
        mReboot = false;
        mRebootSafeMode = false;
        mReason = reason;
        shutdownInner(context, confirm);
    }

    static void shutdownInner(final Context context, boolean confirm) {
        // ensure that only one thread is trying to power down.
        // any additional calls are just returned
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
        }

        final int longPressBehavior = context.getResources().getInteger(
                        com.android.internal.R.integer.config_longPressOnPowerBehavior);
        final int resourceId = mRebootSafeMode
                ? com.android.internal.R.string.reboot_safemode_confirm
                : (longPressBehavior == 2
                        ? com.android.internal.R.string.shutdown_confirm_question
                        : com.android.internal.R.string.shutdown_confirm);

        Log.d(TAG, "Notifying thread to start shutdown longPressBehavior=" + longPressBehavior);

        if (confirm) {
            final CloseDialogReceiver closer = new CloseDialogReceiver(context);
            if (sConfirmDialog != null) {
                sConfirmDialog.dismiss();
            }
            sConfirmDialog = new AlertDialog.Builder(context)
                    .setTitle(mRebootSafeMode
                            ? com.android.internal.R.string.reboot_safemode_title
                            : com.android.internal.R.string.power_off)
                    .setMessage(resourceId)
                    .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            beginShutdownSequence(context);
                        }
                    })
                    .setNegativeButton(com.android.internal.R.string.no, null)
                    .create();
            closer.dialog = sConfirmDialog;
            sConfirmDialog.setOnDismissListener(closer);
            sConfirmDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            sConfirmDialog.show();
        } else {
            beginShutdownSequence(context);
        }
    }

    private static class CloseDialogReceiver extends BroadcastReceiver
            implements DialogInterface.OnDismissListener {
        private Context mContext;
        public Dialog dialog;

        CloseDialogReceiver(Context context) {
            mContext = context;
            IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            dialog.cancel();
        }

        public void onDismiss(DialogInterface unused) {
            mContext.unregisterReceiver(this);
        }
    }

    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void reboot(final Context context, String reason, boolean confirm) {
        mReboot = true;
        mRebootSafeMode = false;
        mRebootHasProgressBar = false;
        mReason = reason;
        shutdownInner(context, confirm);
    }

    /**
     * Request a reboot into safe mode.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void rebootSafeMode(final Context context, boolean confirm) {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (um.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) {
            return;
        }

        mReboot = true;
        mRebootSafeMode = true;
        mRebootHasProgressBar = false;
        mReason = null;
        shutdownInner(context, confirm);
    }

    private static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Shutdown sequence already running, returning.");
                return;
            }
            sIsStarted = true;
        }

        // Throw up a system dialog to indicate the device is rebooting / shutting down.
        ProgressDialog pd = new ProgressDialog(context);

        // Path 1: Reboot to recovery for update
        //   Condition: mReason == REBOOT_RECOVERY_UPDATE
        //
        //  Path 1a: uncrypt needed
        //   Condition: if /cache/recovery/uncrypt_file exists but
        //              /cache/recovery/block.map doesn't.
        //   UI: determinate progress bar (mRebootHasProgressBar == True)
        //
        // * Path 1a is expected to be removed once the GmsCore shipped on
        //   device always calls uncrypt prior to reboot.
        //
        //  Path 1b: uncrypt already done
        //   UI: spinning circle only (no progress bar)
        //
        // Path 2: Reboot to recovery for factory reset
        //   Condition: mReason == REBOOT_RECOVERY
        //   UI: spinning circle only (no progress bar)
        //
        // Path 3: Regular reboot / shutdown
        //   Condition: Otherwise
        //   UI: spinning circle only (no progress bar)
        if (PowerManager.REBOOT_RECOVERY_UPDATE.equals(mReason)) {
            // We need the progress bar if uncrypt will be invoked during the
            // reboot, which might be time-consuming.
            mRebootHasProgressBar = RecoverySystem.UNCRYPT_PACKAGE_FILE.exists()
                    && !(RecoverySystem.BLOCK_MAP_FILE.exists());
            pd.setTitle(context.getText(com.android.internal.R.string.reboot_to_update_title));
            if (mRebootHasProgressBar) {
                pd.setMax(100);
                pd.setProgress(0);
                pd.setIndeterminate(false);
                pd.setProgressNumberFormat(null);
                pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setMessage(context.getText(
                            com.android.internal.R.string.reboot_to_update_prepare));
            } else {
                pd.setIndeterminate(true);
                pd.setMessage(context.getText(
                            com.android.internal.R.string.reboot_to_update_reboot));
            }
        } else if (PowerManager.REBOOT_RECOVERY.equals(mReason)) {
            // Factory reset path. Set the dialog message accordingly.
            pd.setTitle(context.getText(com.android.internal.R.string.reboot_to_reset_title));
            pd.setMessage(context.getText(
                        com.android.internal.R.string.reboot_to_reset_message));
            pd.setIndeterminate(true);
        } else {
            pd.setTitle(context.getText(com.android.internal.R.string.power_off));
            pd.setMessage(context.getText(com.android.internal.R.string.shutdown_progress));
            pd.setIndeterminate(true);
        }
        pd.setCancelable(false);
        pd.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);

        pd.show();

        sInstance.mProgressDialog = pd;
        sInstance.mContext = context;
        sInstance.mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);

        // make sure we never fall asleep again
        sInstance.mCpuWakeLock = null;
        try {
            sInstance.mCpuWakeLock = sInstance.mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, TAG + "-cpu");
            sInstance.mCpuWakeLock.setReferenceCounted(false);
            sInstance.mCpuWakeLock.acquire();
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to acquire wake lock", e);
            sInstance.mCpuWakeLock = null;
        }

        // also make sure the screen stays on for better user experience
        sInstance.mScreenWakeLock = null;
        if (sInstance.mPowerManager.isScreenOn()) {
            try {
                sInstance.mScreenWakeLock = sInstance.mPowerManager.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK, TAG + "-screen");
                sInstance.mScreenWakeLock.setReferenceCounted(false);
                sInstance.mScreenWakeLock.acquire();
            } catch (SecurityException e) {
                Log.w(TAG, "No permission to acquire wake lock", e);
                sInstance.mScreenWakeLock = null;
            }
        }

        // start the thread that initiates shutdown
        sInstance.mHandler = new Handler() {
        };
        sInstance.start();
    }

    void actionDone() {
        synchronized (mActionDoneSync) {
            mActionDone = true;
            mActionDoneSync.notifyAll();
        }
    }

    /**
     * Makes sure we handle the shutdown gracefully.
     * Shuts off power regardless of radio and bluetooth state if the alloted time has passed.
     */
    public void run() {
        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // We don't allow apps to cancel this, so ignore the result.
                actionDone();
            }
        };

        /*
         * Write a system property in case the system_server reboots before we
         * get to the actual hardware restart. If that happens, we'll retry at
         * the beginning of the SystemServer startup.
         */
        {
            String reason = (mReboot ? "1" : "0") + (mReason != null ? mReason : "");
            SystemProperties.set(SHUTDOWN_ACTION_PROPERTY, reason);
        }

        /*
         * If we are rebooting into safe mode, write a system property
         * indicating so.
         */
        if (mRebootSafeMode) {
            SystemProperties.set(REBOOT_SAFEMODE_PROPERTY, "1");
        }

        Log.i(TAG, "Sending shutdown broadcast...");

        // First send the high-level shut down broadcast.
        mActionDone = false;
        Intent intent = new Intent(Intent.ACTION_SHUTDOWN);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendOrderedBroadcastAsUser(intent,
                UserHandle.ALL, null, br, mHandler, 0, null, null);

        final long endTime = SystemClock.elapsedRealtime() + MAX_BROADCAST_TIME;
        synchronized (mActionDoneSync) {
            while (!mActionDone) {
                long delay = endTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown broadcast timed out");
                    break;
                } else if (mRebootHasProgressBar) {
                    int status = (int)((MAX_BROADCAST_TIME - delay) * 1.0 *
                            BROADCAST_STOP_PERCENT / MAX_BROADCAST_TIME);
                    sInstance.setRebootProgress(status, null);
                }
                try {
                    mActionDoneSync.wait(Math.min(delay, PHONE_STATE_POLL_SLEEP_MSEC));
                } catch (InterruptedException e) {
                }
            }
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(BROADCAST_STOP_PERCENT, null);
        }

        Log.i(TAG, "Shutting down activity manager...");

        final IActivityManager am =
            ActivityManagerNative.asInterface(ServiceManager.checkService("activity"));
        if (am != null) {
            try {
                am.shutdown(MAX_BROADCAST_TIME);
            } catch (RemoteException e) {
            }
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(ACTIVITY_MANAGER_STOP_PERCENT, null);
        }

        Log.i(TAG, "Shutting down package manager...");

        final PackageManagerService pm = (PackageManagerService)
            ServiceManager.getService("package");
        if (pm != null) {
            pm.shutdown();
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(PACKAGE_MANAGER_STOP_PERCENT, null);
        }

        // Shutdown radios.
        shutdownRadios(MAX_RADIO_WAIT_TIME);
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(RADIO_STOP_PERCENT, null);
        }

        // Shutdown MountService to ensure media is in a safe state
        IMountShutdownObserver observer = new IMountShutdownObserver.Stub() {
            public void onShutDownComplete(int statusCode) throws RemoteException {
                Log.w(TAG, "Result code " + statusCode + " from MountService.shutdown");
                actionDone();
            }
        };

        Log.i(TAG, "Shutting down MountService");

        // Set initial variables and time out time.
        mActionDone = false;
        final long endShutTime = SystemClock.elapsedRealtime() + MAX_SHUTDOWN_WAIT_TIME;
        synchronized (mActionDoneSync) {
            try {
                final IMountService mount = IMountService.Stub.asInterface(
                        ServiceManager.checkService("mount"));
                if (mount != null) {
                    mount.shutdown(observer);
                } else {
                    Log.w(TAG, "MountService unavailable for shutdown");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception during MountService shutdown", e);
            }
            while (!mActionDone) {
                long delay = endShutTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown wait timed out");
                    break;
                } else if (mRebootHasProgressBar) {
                    int status = (int)((MAX_SHUTDOWN_WAIT_TIME - delay) * 1.0 *
                            (MOUNT_SERVICE_STOP_PERCENT - RADIO_STOP_PERCENT) /
                            MAX_SHUTDOWN_WAIT_TIME);
                    status += RADIO_STOP_PERCENT;
                    sInstance.setRebootProgress(status, null);
                }
                try {
                    mActionDoneSync.wait(Math.min(delay, PHONE_STATE_POLL_SLEEP_MSEC));
                } catch (InterruptedException e) {
                }
            }
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(MOUNT_SERVICE_STOP_PERCENT, null);

            // If it's to reboot to install an update and uncrypt hasn't been
            // done yet, trigger it now.
            uncrypt();
        }

        rebootOrShutdown(mContext, mReboot, mReason);
    }

    private void setRebootProgress(final int progress, final CharSequence message) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mProgressDialog != null) {
                    mProgressDialog.setProgress(progress);
                    if (message != null) {
                        mProgressDialog.setMessage(message);
                    }
                }
            }
        });
    }

    private void shutdownRadios(final int timeout) {
        // If a radio is wedged, disabling it may hang so we do this work in another thread,
        // just in case.
        final long endTime = SystemClock.elapsedRealtime() + timeout;
        final boolean[] done = new boolean[1];
        Thread t = new Thread() {
            public void run() {
                boolean nfcOff;
                boolean bluetoothOff;
                boolean radioOff;

                final INfcAdapter nfc =
                        INfcAdapter.Stub.asInterface(ServiceManager.checkService("nfc"));
                final ITelephony phone =
                        ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
                final IBluetoothManager bluetooth =
                        IBluetoothManager.Stub.asInterface(ServiceManager.checkService(
                                BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE));

                try {
                    nfcOff = nfc == null ||
                             nfc.getState() == NfcAdapter.STATE_OFF;
                    if (!nfcOff) {
                        Log.w(TAG, "Turning off NFC...");
                        nfc.disable(false); // Don't persist new state
                    }
                } catch (RemoteException ex) {
                Log.e(TAG, "RemoteException during NFC shutdown", ex);
                    nfcOff = true;
                }

                try {
                    bluetoothOff = bluetooth == null || !bluetooth.isEnabled();
                    if (!bluetoothOff) {
                        Log.w(TAG, "Disabling Bluetooth...");
                        bluetooth.disable(false);  // disable but don't persist new state
                    }
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
                    bluetoothOff = true;
                }

                try {
                    radioOff = phone == null || !phone.needMobileRadioShutdown();
                    if (!radioOff) {
                        Log.w(TAG, "Turning off cellular radios...");
                        phone.shutdownMobileRadios();
                    }
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during radio shutdown", ex);
                    radioOff = true;
                }

                Log.i(TAG, "Waiting for NFC, Bluetooth and Radio...");

                long delay = endTime - SystemClock.elapsedRealtime();
                while (delay > 0) {
                    if (mRebootHasProgressBar) {
                        int status = (int)((timeout - delay) * 1.0 *
                                (RADIO_STOP_PERCENT - PACKAGE_MANAGER_STOP_PERCENT) / timeout);
                        status += PACKAGE_MANAGER_STOP_PERCENT;
                        sInstance.setRebootProgress(status, null);
                    }

                    if (!bluetoothOff) {
                        try {
                            bluetoothOff = !bluetooth.isEnabled();
                        } catch (RemoteException ex) {
                            Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
                            bluetoothOff = true;
                        }
                        if (bluetoothOff) {
                            Log.i(TAG, "Bluetooth turned off.");
                        }
                    }
                    if (!radioOff) {
                        try {
                            radioOff = !phone.needMobileRadioShutdown();
                        } catch (RemoteException ex) {
                            Log.e(TAG, "RemoteException during radio shutdown", ex);
                            radioOff = true;
                        }
                        if (radioOff) {
                            Log.i(TAG, "Radio turned off.");
                        }
                    }
                    if (!nfcOff) {
                        try {
                            nfcOff = nfc.getState() == NfcAdapter.STATE_OFF;
                        } catch (RemoteException ex) {
                            Log.e(TAG, "RemoteException during NFC shutdown", ex);
                            nfcOff = true;
                        }
                        if (nfcOff) {
                            Log.i(TAG, "NFC turned off.");
                        }
                    }

                    if (radioOff && bluetoothOff && nfcOff) {
                        Log.i(TAG, "NFC, Radio and Bluetooth shutdown complete.");
                        done[0] = true;
                        break;
                    }
                    SystemClock.sleep(PHONE_STATE_POLL_SLEEP_MSEC);

                    delay = endTime - SystemClock.elapsedRealtime();
                }
            }
        };

        t.start();
        try {
            t.join(timeout);
        } catch (InterruptedException ex) {
        }
        if (!done[0]) {
            Log.w(TAG, "Timed out waiting for NFC, Radio and Bluetooth shutdown.");
        }
    }

    /**
     * Do not call this directly. Use {@link #reboot(Context, String, boolean)}
     * or {@link #shutdown(Context, boolean)} instead.
     *
     * @param context Context used to vibrate or null without vibration
     * @param reboot true to reboot or false to shutdown
     * @param reason reason for reboot/shutdown
     */
    public static void rebootOrShutdown(final Context context, boolean reboot, String reason) {
        if (reboot) {
            Log.i(TAG, "Rebooting, reason: " + reason);
            PowerManagerService.lowLevelReboot(reason);
            Log.e(TAG, "Reboot failed, will attempt shutdown instead");
            reason = null;
        } else if (SHUTDOWN_VIBRATE_MS > 0 && context != null) {
            // vibrate before shutting down
            Vibrator vibrator = new SystemVibrator(context);
            try {
                vibrator.vibrate(SHUTDOWN_VIBRATE_MS, VIBRATION_ATTRIBUTES);
            } catch (Exception e) {
                // Failure to vibrate shouldn't interrupt shutdown.  Just log it.
                Log.w(TAG, "Failed to vibrate during shutdown.", e);
            }

            // vibrator is asynchronous so we need to wait to avoid shutting down too soon.
            try {
                Thread.sleep(SHUTDOWN_VIBRATE_MS);
            } catch (InterruptedException unused) {
            }
        }

        // Shutdown power
        Log.i(TAG, "Performing low-level shutdown...");
        PowerManagerService.lowLevelShutdown(reason);
    }

    private void uncrypt() {
        Log.i(TAG, "Calling uncrypt and monitoring the progress...");

        final RecoverySystem.ProgressListener progressListener =
                new RecoverySystem.ProgressListener() {
            @Override
            public void onProgress(int status) {
                if (status >= 0 && status < 100) {
                    // Scale down to [MOUNT_SERVICE_STOP_PERCENT, 100).
                    status = (int)(status * (100.0 - MOUNT_SERVICE_STOP_PERCENT) / 100);
                    status += MOUNT_SERVICE_STOP_PERCENT;
                    CharSequence msg = mContext.getText(
                            com.android.internal.R.string.reboot_to_update_package);
                    sInstance.setRebootProgress(status, msg);
                } else if (status == 100) {
                    CharSequence msg = mContext.getText(
                            com.android.internal.R.string.reboot_to_update_reboot);
                    sInstance.setRebootProgress(status, msg);
                } else {
                    // Ignored
                }
            }
        };

        final boolean[] done = new boolean[1];
        done[0] = false;
        Thread t = new Thread() {
            @Override
            public void run() {
                RecoverySystem rs = (RecoverySystem) mContext.getSystemService(
                        Context.RECOVERY_SERVICE);
                String filename = null;
                try {
                    filename = FileUtils.readTextFile(RecoverySystem.UNCRYPT_PACKAGE_FILE, 0, null);
                    rs.processPackage(mContext, new File(filename), progressListener);
                } catch (IOException e) {
                    Log.e(TAG, "Error uncrypting file", e);
                }
                done[0] = true;
            }
        };
        t.start();

        try {
            t.join(MAX_UNCRYPT_WAIT_TIME);
        } catch (InterruptedException unused) {
        }
        if (!done[0]) {
            Log.w(TAG, "Timed out waiting for uncrypt.");
        }
    }
}
