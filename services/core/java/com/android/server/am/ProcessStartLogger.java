package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.AppGlobals;
import android.app.admin.SecurityLog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.os.Process.ProcessStartResult;
import android.util.Slog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;

/**
 * A class that logs process start information (including APK hash) to the security log.
 */
class ProcessStartLogger {
    private static final String CLASS_NAME = "ProcessStartLogger";
    private static final String TAG = TAG_WITH_CLASS_NAME ? CLASS_NAME : TAG_AM;

    final HandlerThread mHandlerProcessLoggingThread;
    Handler mHandlerProcessLogging;
    // Should only access in mHandlerProcessLoggingThread
    final HashMap<String, String> mProcessLoggingApkHashes;

    ProcessStartLogger() {
        mHandlerProcessLoggingThread = new HandlerThread(CLASS_NAME,
                Process.THREAD_PRIORITY_BACKGROUND);
        mProcessLoggingApkHashes = new HashMap();
    }

    void logIfNeededLocked(ProcessRecord app, ProcessStartResult startResult) {
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }
        if (!mHandlerProcessLoggingThread.isAlive()) {
            mHandlerProcessLoggingThread.start();
            mHandlerProcessLogging = new Handler(mHandlerProcessLoggingThread.getLooper());
        }
        mHandlerProcessLogging.post(new ProcessLoggingRunnable(app, startResult,
                System.currentTimeMillis()));
    }

    void registerListener(Context context) {
        IntentFilter packageChangedFilter = new IntentFilter();
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        || Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                    int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            getSendingUserId());
                    String packageName = intent.getData().getSchemeSpecificPart();
                    try {
                        ApplicationInfo info = AppGlobals.getPackageManager().getApplicationInfo(
                                packageName, 0, userHandle);
                        invaildateCache(info.sourceDir);
                    } catch (RemoteException e) {
                    }
                }
            }
        }, packageChangedFilter);
    }

    private void invaildateCache(final String apkPath) {
        if (mHandlerProcessLogging != null) {
            mHandlerProcessLogging.post(new Runnable() {
                @Override
                public void run() {
                    mProcessLoggingApkHashes.remove(apkPath);
                }
            });
        }
    }

    private class ProcessLoggingRunnable implements Runnable {

        private final ProcessRecord app;
        private final Process.ProcessStartResult startResult;
        private final long startTimestamp;

        public ProcessLoggingRunnable(ProcessRecord app, Process.ProcessStartResult startResult,
                long startTimestamp){
            this.app = app;
            this.startResult = startResult;
            this.startTimestamp = startTimestamp;
        }

        @Override
        public void run() {
            String apkHash = computeStringHashOfApk(app);
            SecurityLog.writeEvent(SecurityLog.TAG_APP_PROCESS_START,
                    app.processName,
                    startTimestamp,
                    app.uid,
                    startResult.pid,
                    app.info.seinfo,
                    apkHash);
        }

        private String computeStringHashOfApk(ProcessRecord app){
            final String apkFile = app.info.sourceDir;
            if(apkFile == null) {
                return "No APK";
            }
            String apkHash = mProcessLoggingApkHashes.get(apkFile);
            if (apkHash == null) {
                try {
                    byte[] hash = computeHashOfApkFile(apkFile);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < hash.length; i++) {
                        sb.append(String.format("%02x", hash[i]));
                    }
                    apkHash = sb.toString();
                    mProcessLoggingApkHashes.put(apkFile, apkHash);
                } catch (IOException | NoSuchAlgorithmException e) {
                    Slog.w(TAG, "computeStringHashOfApk() failed", e);
                }
            }
            return apkHash != null ? apkHash : "Failed to count APK hash";
        }

        private byte[] computeHashOfApkFile(String packageArchiveLocation)
                throws IOException, NoSuchAlgorithmException {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream input = new FileInputStream(new File(packageArchiveLocation));
            byte[] buffer = new byte[65536];
            int size;
            while((size = input.read(buffer)) > 0) {
                md.update(buffer, 0, size);
            }
            input.close();
            return md.digest();
        }
    }
}