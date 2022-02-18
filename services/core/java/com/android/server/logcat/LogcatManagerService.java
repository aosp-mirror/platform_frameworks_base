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
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManagerInternal;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.ILogd;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.logcat.ILogcatManagerService;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.Arrays;
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
    private NotificationManager mNotificationManager;
    private @NonNull ActivityManager mActivityManager;
    private ActivityManagerInternal mActivityManagerInternal;
    private static final int MAX_UID_IMPORTANCE_COUNT_LISTENER = 2;
    private static int sUidImportanceListenerCount = 0;
    private static final int AID_SHELL_UID = 2000;

    // TODO This allowlist is just a temporary workaround for the tests:
    //      FrameworksServicesTests
    //      PlatformRuleTests
    // After adapting the test suites, the allowlist will be removed in
    // the upcoming bug fix patches.
    private static final String[] ALLOWABLE_TESTING_PACKAGES = {
            "android.platform.test.rule.tests",
            "com.android.frameworks.servicestests"
    };

    // TODO Same as the above ALLOWABLE_TESTING_PACKAGES.
    private boolean isAllowableTestingPackage(int uid) {
        PackageManager pm = mContext.getPackageManager();

        String[] packageNames = pm.getPackagesForUid(uid);

        if (ArrayUtils.isEmpty(packageNames)) {
            return false;
        }

        for (String name : packageNames) {
            Slog.e(TAG, "isAllowableTestingPackage: " + name);

            if (Arrays.asList(ALLOWABLE_TESTING_PACKAGES).contains(name)) {
                return true;
            }
        }

        return false;
    };

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
                getLogdService().approve(uid, gid, pid, fd);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void decline(int uid, int gid, int pid, int fd) {
            try {
                getLogdService().decline(uid, gid, pid, fd);
            } catch (RemoteException e) {
                e.printStackTrace();
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

    private String getBodyString(Context context, String callingPackage, int uid) {
        PackageManager pm = context.getPackageManager();
        try {
            return context.getString(
                com.android.internal.R.string.log_access_confirmation_body,
                pm.getApplicationInfoAsUser(callingPackage, PackageManager.MATCH_DIRECT_BOOT_AUTO,
                    UserHandle.getUserId(uid)).loadLabel(pm));
        } catch (NameNotFoundException e) {
            // App name is unknown.
            return null;
        }
    }

    private void sendNotification(int notificationId, String clientInfo, int uid, int gid, int pid,
            int fd) {

        final ActivityManagerInternal activityManagerInternal =
                LocalServices.getService(ActivityManagerInternal.class);

        PackageManager pm = mContext.getPackageManager();
        String packageName = activityManagerInternal.getPackageNameByPid(pid);
        if (packageName != null) {
            String notificationBody = getBodyString(mContext, packageName, uid);

            final Intent mIntent = LogAccessConfirmationActivity.createIntent(mContext,
                    packageName, null, uid, gid, pid, fd);

            if (notificationBody == null) {
                // Decline the logd access if the nofitication body is unknown
                Slog.e(TAG, "Unknown notification body, declining the logd access");
                declineLogdAccess(uid, gid, pid, fd);
                return;
            }

            // TODO Next version will replace notification with dialogue
            // per UX guidance.
            generateNotificationWithBodyContent(notificationId, clientInfo, notificationBody,
                    mIntent);
            return;

        }

        String[] packageNames = pm.getPackagesForUid(uid);

        if (ArrayUtils.isEmpty(packageNames)) {
            // Decline the logd access if the app name is unknown
            Slog.e(TAG, "Unknown calling package name, declining the logd access");
            declineLogdAccess(uid, gid, pid, fd);
            return;
        }

        String firstPackageName = packageNames[0];

        if (firstPackageName == null || firstPackageName.length() == 0) {
            // Decline the logd access if the package name from uid is unknown
            Slog.e(TAG, "Unknown calling package name, declining the logd access");
            declineLogdAccess(uid, gid, pid, fd);
            return;
        }

        String notificationBody = getBodyString(mContext, firstPackageName, uid);

        final Intent mIntent = LogAccessConfirmationActivity.createIntent(mContext,
                firstPackageName, null, uid, gid, pid, fd);

        if (notificationBody == null) {
            Slog.e(TAG, "Unknown notification body, declining the logd access");
            declineLogdAccess(uid, gid, pid, fd);
            return;
        }

        // TODO Next version will replace notification with dialogue
        // per UX guidance.
        generateNotificationWithBodyContent(notificationId, clientInfo,
                notificationBody, mIntent);
    }

    private void declineLogdAccess(int uid, int gid, int pid, int fd) {
        try {
            getLogdService().decline(uid, gid, pid, fd);
        } catch (RemoteException ex) {
            Slog.e(TAG, "Fails to call remote functions ", ex);
        }
    }

    private void generateNotificationWithBodyContent(int notificationId, String clientInfo,
            String notificationBody, Intent intent) {
        final Notification.Builder notificationBuilder = new Notification.Builder(
                mContext,
                SystemNotificationChannels.ACCESSIBILITY_SECURITY_POLICY);
        intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.setIdentifier(String.valueOf(notificationId) + clientInfo);
        intent.putExtra("body", notificationBody);

        notificationBuilder
            .setSmallIcon(R.drawable.ic_info)
            .setContentTitle(
                mContext.getString(R.string.log_access_confirmation_title))
            .setContentText(notificationBody)
            .setContentIntent(
                PendingIntent.getActivity(mContext, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE))
            .setTicker(mContext.getString(R.string.log_access_confirmation_title))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true);
        mNotificationManager.notify(notificationId, notificationBuilder.build());
    }

    /**
     * A class which watches an uid for background access and notifies the logdMonitor when
     * the package status becomes foreground (importance change)
     */
    private class UidImportanceListener implements ActivityManager.OnUidImportanceListener {
        private final int mExpectedUid;
        private final int mExpectedGid;
        private final int mExpectedPid;
        private final int mExpectedFd;
        private int mExpectedImportance;
        private int mCurrentImportance = RunningAppProcessInfo.IMPORTANCE_GONE;

        UidImportanceListener(int uid, int gid, int pid, int fd, int importance) {
            mExpectedUid = uid;
            mExpectedGid = gid;
            mExpectedPid = pid;
            mExpectedFd = fd;
            mExpectedImportance = importance;
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            if (uid == mExpectedUid) {
                mCurrentImportance = importance;

                /**
                 * 1) If the process status changes to foreground, send a notification
                 * for user consent.
                 * 2) If the process status remains background, we decline logd access request.
                 **/
                if (importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                    String clientInfo = getClientInfo(uid, mExpectedGid, mExpectedPid, mExpectedFd);
                    sendNotification(0, clientInfo, uid, mExpectedGid, mExpectedPid,
                            mExpectedFd);
                    mActivityManager.removeOnUidImportanceListener(this);

                    synchronized (LogcatManagerService.this) {
                        sUidImportanceListenerCount--;
                    }
                } else {
                    try {
                        getLogdService().decline(uid, mExpectedGid, mExpectedPid, mExpectedFd);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "Fails to call remote functions ", ex);
                    }
                }
            }
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
                        e.printStackTrace();
                    }
                    return;
                }

                // TODO Temporarily approve all the requests to unblock testing failures.
                try {
                    getLogdService().approve(mUid, mGid, mPid, mFd);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
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
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
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
}
