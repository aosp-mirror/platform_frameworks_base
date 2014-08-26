/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * A service that collects, aggregates, and persists application usage data.
 * This data can be queried by apps that have been granted permission by AppOps.
 */
public class UsageStatsService extends SystemService implements
        UserUsageStatsService.StatsUpdatedListener {
    static final String TAG = "UsageStatsService";

    static final boolean DEBUG = false;
    private static final long TEN_SECONDS = 10 * 1000;
    private static final long TWENTY_MINUTES = 20 * 60 * 1000;
    private static final long TWO_MINUTES = 2 * 60 * 1000;
    private static final long FLUSH_INTERVAL = DEBUG ? TEN_SECONDS : TWENTY_MINUTES;
    private static final long END_TIME_DELAY = DEBUG ? 0 : TWO_MINUTES;

    // Handler message types.
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_FLUSH_TO_DISK = 1;
    static final int MSG_REMOVE_USER = 2;

    private final Object mLock = new Object();
    Handler mHandler;
    AppOpsManager mAppOps;
    UserManager mUserManager;

    private final SparseArray<UserUsageStatsService> mUserState = new SparseArray<>();
    private File mUsageStatsDir;

    public UsageStatsService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        mHandler = new H(BackgroundThread.get().getLooper());

        File systemDataDir = new File(Environment.getDataDirectory(), "system");
        mUsageStatsDir = new File(systemDataDir, "usagestats");
        mUsageStatsDir.mkdirs();
        if (!mUsageStatsDir.exists()) {
            throw new IllegalStateException("Usage stats directory does not exist: "
                    + mUsageStatsDir.getAbsolutePath());
        }

        getContext().registerReceiver(new UserRemovedReceiver(),
                new IntentFilter(Intent.ACTION_USER_REMOVED));

        synchronized (mLock) {
            cleanUpRemovedUsersLocked();
        }

        publishLocalService(UsageStatsManagerInternal.class, new LocalService());
        publishBinderService(Context.USAGE_STATS_SERVICE, new BinderService());
    }

    private class UserRemovedReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.getAction().equals(Intent.ACTION_USER_REMOVED)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId >= 0) {
                    mHandler.obtainMessage(MSG_REMOVE_USER, userId, 0).sendToTarget();
                }
            }
        }
    }

    @Override
    public void onStatsUpdated() {
        mHandler.sendEmptyMessageDelayed(MSG_FLUSH_TO_DISK, FLUSH_INTERVAL);
    }

    private void cleanUpRemovedUsersLocked() {
        final List<UserInfo> users = mUserManager.getUsers(true);
        if (users == null || users.size() == 0) {
            throw new IllegalStateException("There can't be no users");
        }

        ArraySet<String> toDelete = new ArraySet<>();
        String[] fileNames = mUsageStatsDir.list();
        if (fileNames == null) {
            // No users to delete.
            return;
        }

        toDelete.addAll(Arrays.asList(fileNames));

        final int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            final UserInfo userInfo = users.get(i);
            toDelete.remove(Integer.toString(userInfo.id));
        }

        final int deleteCount = toDelete.size();
        for (int i = 0; i < deleteCount; i++) {
            deleteRecursively(new File(mUsageStatsDir, toDelete.valueAt(i)));
        }
    }

    private static void deleteRecursively(File f) {
        File[] files = f.listFiles();
        if (files != null) {
            for (File subFile : files) {
                deleteRecursively(subFile);
            }
        }

        if (!f.delete()) {
            Slog.e(TAG, "Failed to delete " + f);
        }
    }

    private UserUsageStatsService getUserDataAndInitializeIfNeededLocked(int userId) {
        UserUsageStatsService service = mUserState.get(userId);
        if (service == null) {
            service = new UserUsageStatsService(userId,
                    new File(mUsageStatsDir, Integer.toString(userId)), this);
            service.init();
            mUserState.put(userId, service);
        }
        return service;
    }

    /**
     * Called by the Bunder stub
     */
    void shutdown() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_REPORT_EVENT);
            flushToDiskLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    void reportEvent(UsageEvents.Event event, int userId) {
        synchronized (mLock) {
            final UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId);
            service.reportEvent(event);
        }
    }

    /**
     * Called by the Binder stub.
     */
    void flushToDisk() {
        synchronized (mLock) {
            flushToDiskLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    void removeUser(int userId) {
        synchronized (mLock) {
            Slog.i(TAG, "Removing user " + userId + " and all data.");
            mUserState.remove(userId);
            cleanUpRemovedUsersLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    List<UsageStats> queryUsageStats(int userId, int bucketType, long beginTime, long endTime) {
        final long timeNow = System.currentTimeMillis();
        if (beginTime > timeNow) {
            return null;
        }

        synchronized (mLock) {
            UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId);
            return service.queryUsageStats(bucketType, beginTime, endTime);
        }
    }

    /**
     * Called by the Binder stub.
     */
    UsageEvents queryEvents(int userId, long beginTime, long endTime) {
        final long timeNow = System.currentTimeMillis();

        // Adjust the endTime so that we don't query for the latest events.
        // This is to prevent apps from making decision based on what app launched them,
        // etc.
        endTime = Math.min(endTime, timeNow - END_TIME_DELAY);

        if (beginTime > endTime) {
            return null;
        }

        synchronized (mLock) {
            UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId);
            return service.queryEvents(beginTime, endTime);
        }
    }

    private void flushToDiskLocked() {
        final int userCount = mUserState.size();
        for (int i = 0; i < userCount; i++) {
            UserUsageStatsService service = mUserState.valueAt(i);
            service.persistActiveStats();
        }

        mHandler.removeMessages(MSG_FLUSH_TO_DISK);
    }

    class H extends Handler {
        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_EVENT:
                    reportEvent((UsageEvents.Event) msg.obj, msg.arg1);
                    break;

                case MSG_FLUSH_TO_DISK:
                    flushToDisk();
                    break;

                case MSG_REMOVE_USER:
                    removeUser(msg.arg1);
                    break;

                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    private class BinderService extends IUsageStatsManager.Stub {

        private boolean hasPermission(String callingPackage) {
            final int mode = mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS,
                    Binder.getCallingUid(), callingPackage);
            if (mode == AppOpsManager.MODE_DEFAULT) {
                // The default behavior here is to check if PackageManager has given the app
                // permission.
                return getContext().checkCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        }

        @Override
        public ParceledListSlice<UsageStats> queryUsageStats(int bucketType, long beginTime,
                long endTime, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                final List<UsageStats> results = UsageStatsService.this.queryUsageStats(
                        userId, bucketType, beginTime, endTime);
                if (results != null) {
                    return new ParceledListSlice<>(results);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return null;
        }

        @Override
        public UsageEvents queryEvents(long beginTime, long endTime, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEvents(userId, beginTime, endTime);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /**
     * This local service implementation is primarily used by ActivityManagerService.
     * ActivityManagerService will call these methods holding the 'am' lock, which means we
     * shouldn't be doing any IO work or other long running tasks in these methods.
     */
    private class LocalService extends UsageStatsManagerInternal {

        @Override
        public void reportEvent(ComponentName component, int userId,
                long timeStamp, int eventType) {
            if (component == null) {
                Slog.w(TAG, "Event reported without a component name");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = component.getPackageName();
            event.mClass = component.getClassName();
            event.mTimeStamp = timeStamp;
            event.mEventType = eventType;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void prepareShutdown() {
            // This method *WILL* do IO work, but we must block until it is finished or else
            // we might not shutdown cleanly. This is ok to do with the 'am' lock held, because
            // we are shutting down.
            shutdown();
        }
    }
}
