/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.logcat;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.ILogd;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.logcat.ILogcatManagerService;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Service responsible for managing the access to Logcat.
 */
public final class LogcatManagerService extends SystemService {

    private static final String TAG = "LogcatManagerService";
    private final Context mContext;
    private final BinderService mBinderService;
    private final ExecutorService mThreadExecutor;
    private ILogd mLogdService;
    private @NonNull ActivityManager mActivityManager;
    private ActivityManagerInternal mActivityManagerInternal;
    private static final int MAX_UID_IMPORTANCE_COUNT_LISTENER = 2;
    private static final String TARGET_PACKAGE_NAME = "android";
    private static final String TARGET_ACTIVITY_NAME =
            "com.android.server.logcat.LogAccessDialogActivity";
    private static final String EXTRA_UID = "com.android.server.logcat.uid";
    private static final String EXTRA_GID = "com.android.server.logcat.gid";
    private static final String EXTRA_PID = "com.android.server.logcat.pid";
    private static final String EXTRA_FD = "com.android.server.logcat.fd";

    private final class BinderService extends ILogcatManagerService.Stub {
        @Override
        public void startThread(int uid, int gid, int pid, int fd) {
            mThreadExecutor.execute(new LogdMonitor(uid, gid, pid, fd, true));
        }

        @Override
        public void finishThread(int uid, int gid, int pid, int fd) {
            // TODO This thread will be used to notify the AppOpsManager that
            // the logd data access is finished.
            mThreadExecutor.execute(new LogdMonitor(uid, gid, pid, fd, false));
        }

        @Override
        public void approve(int uid, int gid, int pid, int fd) {
            try {
                Slog.d(TAG, "Allow logd access for uid: " + uid);
                getLogdService().approve(uid, gid, pid, fd);
            } catch (RemoteException e) {
                Slog.e(TAG, "Fails to call remote functions", e);
            }
        }

        @Override
        public void decline(int uid, int gid, int pid, int fd) {
            try {
                Slog.d(TAG, "Decline logd access for uid: " + uid);
                getLogdService().decline(uid, gid, pid, fd);
            } catch (RemoteException e) {
                Slog.e(TAG, "Fails to call remote functions", e);
            }
        }
    }

    private ILogd getLogdService() {
        synchronized (LogcatManagerService.this) {
            if (mLogdService == null) {
                LogcatManagerService.this.addLogdService();
            }
            return mLogdService;
        }
    }

    /**
     * Returns the package name.
     * If we cannot retrieve the package name, it returns null and we decline the full device log
     * access
     */
    private String getPackageName(int uid, int gid, int pid, int fd) {

        final ActivityManagerInternal activityManagerInternal =
                LocalServices.getService(ActivityManagerInternal.class);
        if (activityManagerInternal != null) {
            String packageName = activityManagerInternal.getPackageNameByPid(pid);
            if (packageName != null) {
                return packageName;
            }
        }

        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            // Decline the logd access if PackageManager is null
            Slog.e(TAG, "PackageManager is null, declining the logd access");
            return null;
        }

        String[] packageNames = pm.getPackagesForUid(uid);

        if (ArrayUtils.isEmpty(packageNames)) {
            // Decline the logd access if the app name is unknown
            Slog.e(TAG, "Unknown calling package name, declining the logd access");
            return null;
        }

        String firstPackageName = packageNames[0];

        if (firstPackageName == null || firstPackageName.isEmpty()) {
            // Decline the logd access if the package name from uid is unknown
            Slog.e(TAG, "Unknown calling package name, declining the logd access");
            return null;
        }

        return firstPackageName;

    }

    private void declineLogdAccess(int uid, int gid, int pid, int fd) {
        try {
            getLogdService().decline(uid, gid, pid, fd);
        } catch (RemoteException e) {
            Slog.e(TAG, "Fails to call remote functions", e);
        }
    }

    private static String getClientInfo(int uid, int gid, int pid, int fd) {
        return "UID=" + Integer.toString(uid) + " GID=" + Integer.toString(gid) + " PID="
                + Integer.toString(pid) + " FD=" + Integer.toString(fd);
    }

    private class LogdMonitor implements Runnable {

        private final int mUid;
        private final int mGid;
        private final int mPid;
        private final int mFd;
        private final boolean mStart;

        /**
         * For starting a thread, the start value is true.
         * For finishing a thread, the start value is false.
         */
        LogdMonitor(int uid, int gid, int pid, int fd, boolean start) {
            mUid = uid;
            mGid = gid;
            mPid = pid;
            mFd = fd;
            mStart = start;
        }

        /**
         * LogdMonitor generates a prompt for users.
         * The users decide whether the logd access is allowed.
         */
        @Override
        public void run() {
            if (mLogdService == null) {
                LogcatManagerService.this.addLogdService();
            }

            if (mStart) {

                ActivityManagerInternal ami = LocalServices.getService(
                        ActivityManagerInternal.class);
                boolean isCallerInstrumented = ami.isUidCurrentlyInstrumented(mUid);

                // The instrumented apks only run for testing, so we don't check user permission.
                if (isCallerInstrumented) {
                    try {
                        getLogdService().approve(mUid, mGid, mPid, mFd);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Fails to call remote functions", e);
                    }
                    return;
                }

                final int procState = LocalServices.getService(ActivityManagerInternal.class)
                        .getUidProcessState(mUid);
                // If the process is foreground and we can retrieve the package name, show a dialog
                // for user consent
                if (procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                    String packageName = getPackageName(mUid, mGid, mPid, mFd);
                    if (packageName != null) {
                        final Intent mIntent = createIntent(packageName, mUid, mGid, mPid, mFd);
                        mContext.startActivityAsUser(mIntent, UserHandle.SYSTEM);
                        return;
                    }
                }

                /**
                 * If the process is background or cannot retrieve the package name,
                 * decline the logd access.
                 **/
                declineLogdAccess(mUid, mGid, mPid, mFd);
                return;
            }
        }
    }

    public LogcatManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new BinderService();
        mThreadExecutor = Executors.newCachedThreadPool();
        mActivityManager = context.getSystemService(ActivityManager.class);
    }

    @Override
    public void onStart() {
        try {
            publishBinderService("logcat", mBinderService);
        } catch (Throwable t) {
            Slog.e(TAG, "Could not start the LogcatManagerService.", t);
        }
    }

    private void addLogdService() {
        mLogdService = ILogd.Stub.asInterface(ServiceManager.getService("logd"));
    }

    /**
     * Create the Intent for LogAccessDialogActivity.
     */
    public Intent createIntent(String targetPackageName, int uid, int gid, int pid, int fd) {
        final Intent intent = new Intent();

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, targetPackageName);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_GID, gid);
        intent.putExtra(EXTRA_PID, pid);
        intent.putExtra(EXTRA_FD, fd);

        intent.setComponent(new ComponentName(TARGET_PACKAGE_NAME, TARGET_ACTIVITY_NAME));

        return intent;
    }
}
