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

import android.app.ActivityManagerInternal;
import android.app.AlertDialog;
import android.app.BroadcastOptions;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.ProgressDialog;
import android.app.admin.SecurityLog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemVibrator;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.vibrator.persistence.VibrationXmlParser;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.TimingsTraceLog;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.crashrecovery.CrashRecoveryHelper;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ShutdownThread extends Thread {
    // constants
    private static final boolean DEBUG = false;
    private static final String TAG = "ShutdownThread";
    private static final int ACTION_DONE_POLL_WAIT_MS = 500;
    private static final int RADIOS_STATE_POLL_SLEEP_MS = 100;
    // maximum time we wait for the shutdown broadcast before going on.
    private static final int MAX_BROADCAST_TIME = 10 * 1000;
    private static final int MAX_CHECK_POINTS_DUMP_WAIT_TIME = 10 * 1000;
    private static final int MAX_RADIO_WAIT_TIME = 12 * 1000;
    private static final int MAX_UNCRYPT_WAIT_TIME = 15 * 60 * 1000;
    // constants for progress bar. the values are roughly estimated based on timeout.
    private static final int BROADCAST_STOP_PERCENT = 2;
    private static final int ACTIVITY_MANAGER_STOP_PERCENT = 4;
    private static final int PACKAGE_MANAGER_STOP_PERCENT = 6;
    private static final int RADIO_STOP_PERCENT = 18;
    private static final int MOUNT_SERVICE_STOP_PERCENT = 20;

    // length of vibration before shutting down
    @VisibleForTesting static final int DEFAULT_SHUTDOWN_VIBRATE_MS = 500;

    // state tracking
    private static final Object sIsStartedGuard = new Object();
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

    // static instance of this thread
    private static final ShutdownThread sInstance = new ShutdownThread();

    // Metrics that will be reported to tron after reboot
    private static final ArrayMap<String, Long> TRON_METRICS = new ArrayMap<>();

    // File to use for saving shutdown metrics
    private static final String METRICS_FILE_BASENAME = "/data/system/shutdown-metrics";
    // File to use for saving shutdown check points
    private static final String CHECK_POINTS_FILE_BASENAME =
            "/data/system/shutdown-checkpoints/checkpoints";

    // Metrics names to be persisted in shutdown-metrics file
    private static String METRIC_SYSTEM_SERVER = "shutdown_system_server";
    private static String METRIC_SEND_BROADCAST = "shutdown_send_shutdown_broadcast";
    private static String METRIC_AM = "shutdown_activity_manager";
    private static String METRIC_PM = "shutdown_package_manager";
    private static String METRIC_RADIOS = "shutdown_radios";
    private static String METRIC_RADIO = "shutdown_radio";
    private static String METRIC_SHUTDOWN_TIME_START = "begin_shutdown";

    private final Injector mInjector;

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
        this(new Injector());
    }

    @VisibleForTesting
    ShutdownThread(Injector injector) {
        mInjector = injector;
    }

    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog. This must be a context
     *                suitable for displaying UI (aka Themable).
     * @param reason code to pass to android_reboot() (e.g. "userrequested"), or null.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void shutdown(final Context context, String reason, boolean confirm) {
        mReboot = false;
        mRebootSafeMode = false;
        mReason = reason;
        shutdownInner(context, confirm);
    }

    private static void shutdownInner(final Context context, boolean confirm) {
        // ShutdownThread is called from many places, so best to verify here that the context passed
        // in is themed.
        context.assertRuntimeOverlayThemable();

        // ensure that only one thread is trying to power down.
        // any additional calls are just returned
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                if (DEBUG) {
                    Log.d(TAG, "Request to shutdown already running, returning.");
                }
                return;
            }
        }

        // Add checkpoint for this shutdown attempt. The user might still cancel the dialog, but
        // this point preserves the system trace of the trigger point of the ShutdownThread.
        ShutdownCheckPoints.recordCheckPoint(/* reason= */ null);

        final int longPressBehavior = context.getResources().getInteger(
                        com.android.internal.R.integer.config_longPressOnPowerBehavior);
        final int resourceId = mRebootSafeMode
                ? com.android.internal.R.string.reboot_safemode_confirm
                : (longPressBehavior == 2
                        ? com.android.internal.R.string.shutdown_confirm_question
                        : com.android.internal.R.string.shutdown_confirm);

        if (DEBUG) {
            Log.d(TAG, "Notifying thread to start shutdown longPressBehavior=" + longPressBehavior);
        }

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
            context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
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
     * @param context Context used to display the shutdown progress dialog. This must be a context
     *                suitable for displaying UI (aka Themable).
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
     * @param context Context used to display the shutdown progress dialog. This must be a context
     *                suitable for displaying UI (aka Themable).
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

    private static ProgressDialog showShutdownDialog(Context context) {
        // Throw up a system dialog to indicate the device is rebooting / shutting down.
        ProgressDialog pd = new ProgressDialog(context);

        // Path 1: Reboot to recovery for update
        //   Condition: mReason startswith REBOOT_RECOVERY_UPDATE
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

        // mReason could be "recovery-update" or "recovery-update,quiescent".
        if (mReason != null && mReason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
            // We need the progress bar if uncrypt will be invoked during the
            // reboot, which might be time-consuming.
            mRebootHasProgressBar = RecoverySystem.UNCRYPT_PACKAGE_FILE.exists()
                    && !(RecoverySystem.BLOCK_MAP_FILE.exists());
            pd.setTitle(context.getText(com.android.internal.R.string.reboot_to_update_title));
            if (mRebootHasProgressBar) {
                pd.setMax(100);
                pd.setProgress(0);
                pd.setIndeterminate(false);
                boolean showPercent = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_showPercentageTextDuringRebootToUpdate);
                if (!showPercent) {
                    pd.setProgressPercentFormat(null);
                }
                pd.setProgressNumberFormat(null);
                pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setMessage(context.getText(
                            com.android.internal.R.string.reboot_to_update_prepare));
            } else {
                if (showSysuiReboot()) {
                    return null;
                }
                pd.setIndeterminate(true);
                pd.setMessage(context.getText(
                            com.android.internal.R.string.reboot_to_update_reboot));
            }
        } else if (mReason != null && mReason.equals(PowerManager.REBOOT_RECOVERY)) {
            if (CrashRecoveryHelper.isRecoveryTriggeredReboot()) {
                // We're not actually doing a factory reset yet; we're rebooting
                // to ask the user if they'd like to reset, so give them a less
                // scary dialog message.
                pd.setTitle(context.getText(com.android.internal.R.string.power_off));
                pd.setMessage(context.getText(com.android.internal.R.string.shutdown_progress));
                pd.setIndeterminate(true);
            } else if (showSysuiReboot()) {
                return null;
            } else {
                // Factory reset path. Set the dialog message accordingly.
                pd.setTitle(context.getText(com.android.internal.R.string.reboot_to_reset_title));
                pd.setMessage(context.getText(
                            com.android.internal.R.string.reboot_to_reset_message));
                pd.setIndeterminate(true);
            }
        } else {
            if (showSysuiReboot()) {
                return null;
            }
            pd.setTitle(context.getText(com.android.internal.R.string.power_off));
            pd.setMessage(context.getText(com.android.internal.R.string.shutdown_progress));
            pd.setIndeterminate(true);
        }
        pd.setCancelable(false);
        pd.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);

        pd.show();
        return pd;
    }

    private static boolean showSysuiReboot() {
        if (DEBUG) {
            Log.d(TAG, "Attempting to use SysUI shutdown UI");
        }
        try {
            StatusBarManagerInternal service = LocalServices.getService(
                    StatusBarManagerInternal.class);
            if (service.showShutdownUi(mReboot, mReason)) {
                // Sysui will handle shutdown UI.
                if (DEBUG) {
                    Log.d(TAG, "SysUI handling shutdown UI");
                }
                return true;
            }
        } catch (Exception e) {
            // If anything went wrong, ignore it and use fallback ui
        }
        if (DEBUG) {
            Log.d(TAG, "SysUI is unavailable");
        }
        return false;
    }

    private static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                if (DEBUG) {
                    Log.d(TAG, "Shutdown sequence already running, returning.");
                }
                return;
            }
            sIsStarted = true;
        }

        sInstance.mProgressDialog = showShutdownDialog(context);
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

        if (SecurityLog.isLoggingEnabled()) {
            SecurityLog.writeEvent(SecurityLog.TAG_OS_SHUTDOWN);
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
     * Shuts off power regardless of radio state if the allotted time has passed.
     */
    public void run() {
        TimingsTraceLog shutdownTimingLog = newTimingsLog();
        shutdownTimingLog.traceBegin("SystemServerShutdown");
        metricShutdownStart();
        metricStarted(METRIC_SYSTEM_SERVER);

        // Notify SurfaceFlinger that the device is shutting down.
        // Transaction traces should be captured at this stage.
        SurfaceControl.notifyShutdown();

        // Start dumping check points for this shutdown in a separate thread.
        Thread dumpCheckPointsThread = ShutdownCheckPoints.newDumpThread(
                new File(CHECK_POINTS_FILE_BASENAME));
        dumpCheckPointsThread.start();

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

        shutdownTimingLog.traceBegin("DumpPreRebootInfo");
        try {
            Slog.i(TAG, "Logging pre-reboot information...");
            PreRebootLogger.log(mContext);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to log pre-reboot information", e);
        }
        shutdownTimingLog.traceEnd(); // DumpPreRebootInfo

        metricStarted(METRIC_SEND_BROADCAST);
        shutdownTimingLog.traceBegin("SendShutdownBroadcast");
        Log.i(TAG, "Sending shutdown broadcast...");

        // First send the high-level shut down broadcast.
        mActionDone = false;
        Intent intent = new Intent(Intent.ACTION_SHUTDOWN);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        final Bundle opts = BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                .toBundle();
        final ActivityManagerInternal activityManagerInternal = LocalServices.getService(
                ActivityManagerInternal.class);
        activityManagerInternal.broadcastIntentWithCallback(intent,
                new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode, String data,
                            Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                        mHandler.post(ShutdownThread.this::actionDone);
                    }
                }, null, UserHandle.USER_ALL, null, null, opts);

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
                    mActionDoneSync.wait(Math.min(delay, ACTION_DONE_POLL_WAIT_MS));
                } catch (InterruptedException e) {
                }
            }
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(BROADCAST_STOP_PERCENT, null);
        }
        shutdownTimingLog.traceEnd(); // SendShutdownBroadcast
        metricEnded(METRIC_SEND_BROADCAST);

        Log.i(TAG, "Shutting down activity manager...");
        shutdownTimingLog.traceBegin("ShutdownActivityManager");
        metricStarted(METRIC_AM);

        final IActivityManager am =
                IActivityManager.Stub.asInterface(ServiceManager.checkService("activity"));
        if (am != null) {
            try {
                am.shutdown(MAX_BROADCAST_TIME);
            } catch (RemoteException e) {
            }
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(ACTIVITY_MANAGER_STOP_PERCENT, null);
        }
        shutdownTimingLog.traceEnd();// ShutdownActivityManager
        metricEnded(METRIC_AM);

        Log.i(TAG, "Shutting down package manager...");
        shutdownTimingLog.traceBegin("ShutdownPackageManager");
        metricStarted(METRIC_PM);

        final PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        if (pm != null) {
            pm.shutdown();
        }
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(PACKAGE_MANAGER_STOP_PERCENT, null);
        }
        shutdownTimingLog.traceEnd(); // ShutdownPackageManager
        metricEnded(METRIC_PM);

        // Shutdown radios.
        shutdownTimingLog.traceBegin("ShutdownRadios");
        metricStarted(METRIC_RADIOS);
        shutdownRadios(MAX_RADIO_WAIT_TIME);
        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(RADIO_STOP_PERCENT, null);
        }
        shutdownTimingLog.traceEnd(); // ShutdownRadios
        metricEnded(METRIC_RADIOS);

        if (mRebootHasProgressBar) {
            sInstance.setRebootProgress(MOUNT_SERVICE_STOP_PERCENT, null);

            // If it's to reboot to install an update and uncrypt hasn't been
            // done yet, trigger it now.
            uncrypt();
        }

        // Wait for the check points dump thread to finish, or kill it if not finished in time.
        shutdownTimingLog.traceBegin("ShutdownCheckPointsDumpWait");
        try {
            dumpCheckPointsThread.join(MAX_CHECK_POINTS_DUMP_WAIT_TIME);
        } catch (InterruptedException ex) {
        }
        shutdownTimingLog.traceEnd(); // ShutdownCheckPointsDumpWait

        shutdownTimingLog.traceEnd(); // SystemServerShutdown
        metricEnded(METRIC_SYSTEM_SERVER);
        saveMetrics(mReboot, mReason);
        // Remaining work will be done by init, including vold shutdown
        rebootOrShutdown(mContext, mReboot, mReason);
    }

    private static TimingsTraceLog newTimingsLog() {
        return new TimingsTraceLog("ShutdownTiming", Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private static void metricStarted(String metricKey) {
        synchronized (TRON_METRICS) {
            TRON_METRICS.put(metricKey, -1 * SystemClock.elapsedRealtime());
        }
    }

    private static void metricEnded(String metricKey) {
        synchronized (TRON_METRICS) {
            TRON_METRICS
                    .put(metricKey, SystemClock.elapsedRealtime() + TRON_METRICS.get(metricKey));
        }
    }

    private static void metricShutdownStart() {
        synchronized (TRON_METRICS) {
            TRON_METRICS.put(METRIC_SHUTDOWN_TIME_START, System.currentTimeMillis());
        }
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
                TimingsTraceLog shutdownTimingsTraceLog = newTimingsLog();
                boolean radioOff;

                TelephonyManager telephonyManager = mContext.getSystemService(
                        TelephonyManager.class);

                radioOff = telephonyManager == null
                        || !telephonyManager.isAnyRadioPoweredOn();
                if (!radioOff) {
                    Log.w(TAG, "Turning off cellular radios...");
                    metricStarted(METRIC_RADIO);
                    telephonyManager.shutdownAllRadios();
                }

                Log.i(TAG, "Waiting for Radio...");

                long delay = endTime - SystemClock.elapsedRealtime();
                while (delay > 0) {
                    if (mRebootHasProgressBar) {
                        int status = (int)((timeout - delay) * 1.0 *
                                (RADIO_STOP_PERCENT - PACKAGE_MANAGER_STOP_PERCENT) / timeout);
                        status += PACKAGE_MANAGER_STOP_PERCENT;
                        sInstance.setRebootProgress(status, null);
                    }

                    if (!radioOff) {
                        radioOff = !telephonyManager.isAnyRadioPoweredOn();
                        if (radioOff) {
                            Log.i(TAG, "Radio turned off.");
                            metricEnded(METRIC_RADIO);
                            shutdownTimingsTraceLog
                                    .logDuration("ShutdownRadio", TRON_METRICS.get(METRIC_RADIO));
                        }
                    }

                    if (radioOff) {
                        Log.i(TAG, "Radio shutdown complete.");
                        done[0] = true;
                        break;
                    }
                    SystemClock.sleep(RADIOS_STATE_POLL_SLEEP_MS);
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
            Log.w(TAG, "Timed out waiting for Radio shutdown.");
        }
    }

    /**
     * Do not call this directly. Use {@link #reboot(Context, String, boolean)}
     * or {@link #shutdown(Context, String, boolean)} instead.
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
        } else if (context != null) {
            // vibrate before shutting down
            try {
                sInstance.playShutdownVibration(context);
            } catch (Exception e) {
                // Failure to vibrate shouldn't interrupt shutdown.  Just log it.
                Log.w(TAG, "Failed to vibrate during shutdown.", e);
            }

        }
        // Shutdown power
        Log.i(TAG, "Performing low-level shutdown...");
        PowerManagerService.lowLevelShutdown(reason);
    }

    /**
     * Plays a vibration for shutdown. Along with playing a shutdown vibration, this method also
     * sleeps the current Thread for some time, to allow the vibration to finish before the device
     * shuts down.
     */
    @VisibleForTesting // For testing vibrations without shutting down device
    void playShutdownVibration(Context context) {
        if (mInjector.isShutdownVibrationDisabled(context)) {
            Log.i(TAG, "Vibration disabled in config");
            return;
        }

        Vibrator vibrator = mInjector.getVibrator(context);
        if (!vibrator.hasVibrator()) {
            return;
        }

        VibrationEffect vibrationEffect = getValidShutdownVibration(context, vibrator);
        vibrator.vibrate(
                vibrationEffect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));

        // vibrator is asynchronous so we have to wait to avoid shutting down too soon.
        long vibrationDuration = vibrationEffect.getDuration();
        // A negative vibration duration may indicate a vibration effect whose duration is not
        // known by the system (e.g. pre-baked effects). In that case, use the default shutdown
        // vibration duration.
        mInjector.sleep(vibrationDuration < 0 ? DEFAULT_SHUTDOWN_VIBRATE_MS : vibrationDuration);
    }

    private static void saveMetrics(boolean reboot, String reason) {
        StringBuilder metricValue = new StringBuilder();
        metricValue.append("reboot:");
        metricValue.append(reboot ? "y" : "n");
        metricValue.append(",").append("reason:").append(reason);
        final int metricsSize = TRON_METRICS.size();
        for (int i = 0; i < metricsSize; i++) {
            final String name = TRON_METRICS.keyAt(i);
            final long value = TRON_METRICS.valueAt(i);
            if (value < 0) {
                Log.e(TAG, "metricEnded wasn't called for " + name);
                continue;
            }
            metricValue.append(',').append(name).append(':').append(value);
        }
        File tmp = new File(METRICS_FILE_BASENAME + ".tmp");
        boolean saved = false;
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(metricValue.toString().getBytes(StandardCharsets.UTF_8));
            saved = true;
        } catch (IOException e) {
            Log.e(TAG,"Cannot save shutdown metrics", e);
        }
        if (saved) {
            tmp.renameTo(new File(METRICS_FILE_BASENAME + ".txt"));
        }
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
            final int uncryptTimeoutError = 100;
            String timeoutMessage = String.format("uncrypt_time: %d\n" + "uncrypt_error: %d\n",
                    MAX_UNCRYPT_WAIT_TIME / 1000, uncryptTimeoutError);
            try {
                FileUtils.stringToFile(RecoverySystem.UNCRYPT_STATUS_FILE, timeoutMessage);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write timeout message to uncrypt status", e);
            }
        }
    }

    /**
     * Provides a {@link VibrationEffect} to be used for shutdown.
     *
     * <p>The vibration to be played is derived from the shutdown vibration file (which the device
     * should specify at `com.android.internal.R.string.config_defaultShutdownVibrationFile`). A
     * fallback vibration maybe used in one of these conditions:
     *      <ul>
     *          <li>A vibration file has not been specified, or if the specified file does not exist
     *          <li>If the content of the file does not represent a valid serialization of a
     *              {@link VibrationEffect}
     *          <li>If the {@link VibrationEffect} specified in the file is not suitable for
     *              a shutdown vibration (such as indefinite vibrations)
     *      </ul>
     */
    private VibrationEffect getValidShutdownVibration(Context context, Vibrator vibrator) {
        VibrationEffect parsedEffect = parseVibrationEffectFromFile(
                mInjector.getDefaultShutdownVibrationEffectFilePath(context),
                vibrator);

        if (parsedEffect == null) {
            return createDefaultVibrationEffect();
        }

        long parsedEffectDuration = parsedEffect.getDuration();
        if (parsedEffectDuration == Long.MAX_VALUE) {
            // This means that the effect does not have a defined end.
            // Since we don't want to vibrate forever while trying to shutdown, we ignore this
            // parsed effect and use the default one instead.
            Log.w(TAG, "The parsed shutdown vibration is indefinite.");
            return createDefaultVibrationEffect();
        }

        return parsedEffect;
    }

    private static VibrationEffect parseVibrationEffectFromFile(
            String filePath, Vibrator vibrator) {
        if (!TextUtils.isEmpty(filePath)) {
            try {
                return VibrationXmlParser.parseDocument(new FileReader(filePath)).resolve(vibrator);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing default shutdown vibration effect.", e);
            }
        }
        return null;
    }

    private static VibrationEffect createDefaultVibrationEffect() {
        return VibrationEffect.createOneShot(
                DEFAULT_SHUTDOWN_VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE);
    }

    /** Utility class to inject instances, for easy testing. */
    @VisibleForTesting
    static class Injector {
        public Vibrator getVibrator(Context context) {
            return new SystemVibrator(context);
        }

        public void sleep(long durationMs) {
            try {
                Thread.sleep(durationMs);
            } catch (InterruptedException unused) {
                // this is not critical and does not require logging.
            }
        }

        public String getDefaultShutdownVibrationEffectFilePath(Context context) {
            return context.getResources().getString(
                    com.android.internal.R.string.config_defaultShutdownVibrationFile);
        }

        public boolean isShutdownVibrationDisabled(Context context) {
            boolean disabledInConfig = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_disableShutdownVibrationInZen);
            boolean isZenMode = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF)
                    != Settings.Global.ZEN_MODE_OFF;
            return disabledInConfig && isZenMode;
        }
    }
}
