/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.stats;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IStatsCompanionService;
import android.os.IStatsManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatsLogEventWrapper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.KernelWakelockReader;
import com.android.internal.os.KernelWakelockStats;
import com.android.server.SystemService;

import java.util.Map;

/**
 * Helper service for statsd (the native stats management service in cmds/statsd/).
 * Used for registering and receiving alarms on behalf of statsd.
 *
 * @hide
 */
public class StatsCompanionService extends IStatsCompanionService.Stub {
    static final String TAG = "StatsCompanionService";
    static final boolean DEBUG = true;

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    @GuardedBy("sStatsdLock")
    private static IStatsManager sStatsd;
    private static final Object sStatsdLock = new Object();

    private final PendingIntent mAnomalyAlarmIntent;
    private final PendingIntent mPollingAlarmIntent;
    private final BroadcastReceiver mAppUpdateReceiver;
    private final BroadcastReceiver mUserUpdateReceiver;

    public StatsCompanionService(Context context) {
        super();
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        mAnomalyAlarmIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(mContext, AnomalyAlarmReceiver.class), 0);
        mPollingAlarmIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(mContext, PollingAlarmReceiver.class), 0);
        mAppUpdateReceiver = new AppUpdateReceiver();
        mUserUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                synchronized (sStatsdLock) {
                    sStatsd = fetchStatsdService();
                    if (sStatsd == null) {
                        Slog.w(TAG, "Could not access statsd");
                        return;
                    }
                    try {
                        // Pull the latest state of UID->app name, version mapping.
                        // Needed since the new user basically has a version of every app.
                        informAllUidsLocked(context);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to inform statsd latest update of all apps", e);
                        forgetEverything();
                    }
                }
            }
        };
        Slog.w(TAG, "Registered receiver for ACTION_PACKAGE_REPLACE AND ADDED.");
    }

    private final static int[] toIntArray(List<Integer> list) {
        int[] ret = new int[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    // Assumes that sStatsdLock is held.
    private final void informAllUidsLocked(Context context) throws RemoteException {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        PackageManager pm = context.getPackageManager();
        final List<UserInfo> users = um.getUsers(true);
        if (DEBUG) {
            Slog.w(TAG, "Iterating over " + users.size() + " profiles.");
        }

        List<Integer> uids = new ArrayList();
        List<Integer> versions = new ArrayList();
        List<String> apps = new ArrayList();

        // Add in all the apps for every user/profile.
        for (UserInfo profile : users) {
            List<PackageInfo> pi = pm.getInstalledPackagesAsUser(0, profile.id);
            for (int j = 0; j < pi.size(); j++) {
                if (pi.get(j).applicationInfo != null) {
                    uids.add(pi.get(j).applicationInfo.uid);
                    versions.add(pi.get(j).versionCode);
                    apps.add(pi.get(j).packageName);
                }
            }
        }
        sStatsd.informAllUidData(toIntArray(uids), toIntArray(versions), apps.toArray(new
                String[apps.size()]));
        if (DEBUG) {
            Slog.w(TAG, "Sent data for " + uids.size() + " apps");
        }
    }

    public final static class AppUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /**
             * App updates actually consist of REMOVE, ADD, and then REPLACE broadcasts. To avoid
             * waste, we ignore the REMOVE and ADD broadcasts that contain the replacing flag.
             * If we can't find the value for EXTRA_REPLACING, we default to false.
             */
            if (!intent.getAction().equals(Intent.ACTION_PACKAGE_REPLACED)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return; // Keep only replacing or normal add and remove.
            }
            Slog.i(TAG, "StatsCompanionService noticed an app was updated.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of anomaly alarm firing");
                    return;
                }
                try {
                    if (intent.getAction().equals(Intent.ACTION_PACKAGE_REMOVED)) {
                        Bundle b = intent.getExtras();
                        int uid = b.getInt(Intent.EXTRA_UID);
                        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        if (!replacing) {
                            // Don't bother sending an update if we're right about to get another
                            // intent for the new version that's added.
                            PackageManager pm = context.getPackageManager();
                            String app = intent.getData().getSchemeSpecificPart();
                            sStatsd.informOnePackageRemoved(app, uid);
                        }
                    } else {
                        PackageManager pm = context.getPackageManager();
                        Bundle b = intent.getExtras();
                        int uid = b.getInt(Intent.EXTRA_UID);
                        String app = intent.getData().getSchemeSpecificPart();
                        PackageInfo pi = pm.getPackageInfo(app, PackageManager.MATCH_ANY_USER);
                        sStatsd.informOnePackage(app, uid, pi.versionCode);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to inform statsd of an app update", e);
                }
            }
        }
    }

    public final static class AnomalyAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.i(TAG, "StatsCompanionService believes an anomaly has occurred.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of anomaly alarm firing");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informAnomalyAlarmFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of anomaly alarm firing", e);
                }
            }
            // AlarmManager releases its own wakelock here.
        }
    }

    public final static class PollingAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Slog.d(TAG, "Time to poll something.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of polling alarm firing");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informPollAlarmFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of polling alarm firing", e);
                }
            }
            // AlarmManager releases its own wakelock here.
        }
    }

    @Override // Binder call
    public void setAnomalyAlarm(long timestampMs) {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Setting anomaly alarm for " + timestampMs);
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using RTC, not RTC_WAKEUP, so if device is asleep, will only fire when it awakens.
            // This alarm is inexact, leaving its exactness completely up to the OS optimizations.
            // AlarmManager will automatically cancel any previous mAnomalyAlarmIntent alarm.
            mAlarmManager.set(AlarmManager.RTC, timestampMs, mAnomalyAlarmIntent);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelAnomalyAlarm() {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Cancelling anomaly alarm");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mAnomalyAlarmIntent);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void setPollingAlarms(long timestampMs, long intervalMs) {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Setting polling alarm for " + timestampMs
                + " every " + intervalMs + "ms");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using RTC, not RTC_WAKEUP, so if device is asleep, will only fire when it awakens.
            // This alarm is inexact, leaving its exactness completely up to the OS optimizations.
            // TODO: totally inexact means that stats per bucket could be quite off. Is this okay?
            mAlarmManager.setRepeating(AlarmManager.RTC, timestampMs, intervalMs,
                    mPollingAlarmIntent);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelPollingAlarms() {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Cancelling polling alarm");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mPollingAlarmIntent);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    // These values must be kept in sync with cmd/statsd/StatsPullerManager.h.
    // TODO: pull the constant from stats_events.proto instead
    private static final int PULL_CODE_KERNEL_WAKELOCKS = 20;

    private final KernelWakelockReader mKernelWakelockReader = new KernelWakelockReader();
    private final KernelWakelockStats mTmpWakelockStats = new KernelWakelockStats();

    @Override // Binder call
    public StatsLogEventWrapper[] pullData(int pullCode) {
        enforceCallingPermission();
        if (DEBUG) {
            Slog.d(TAG, "Pulling " + pullCode);
        }

        List<StatsLogEventWrapper> ret = new ArrayList<>();
        switch (pullCode) {
            case PULL_CODE_KERNEL_WAKELOCKS: {
                final KernelWakelockStats wakelockStats =
                        mKernelWakelockReader.readKernelWakelockStats(mTmpWakelockStats);
                for (Map.Entry<String, KernelWakelockStats.Entry> ent : wakelockStats.entrySet()) {
                    String name = ent.getKey();
                    KernelWakelockStats.Entry kws = ent.getValue();
                    StatsLogEventWrapper e = new StatsLogEventWrapper(101, 4);
                    e.writeInt(kws.mCount);
                    e.writeInt(kws.mVersion);
                    e.writeLong(kws.mTotalTime);
                    e.writeString(name);
                    ret.add(e);
                }
                break;
            }
            default:
                Slog.w(TAG, "No such pollable data as " + pullCode);
                return null;
        }
        return ret.toArray(new StatsLogEventWrapper[ret.size()]);
    }

    @Override // Binder call
    public void statsdReady() {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "learned that statsdReady");
        sayHiToStatsd(); // tell statsd that we're ready too and link to it
    }

    private void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforceCallingPermission(android.Manifest.permission.STATSCOMPANION, null);
    }

    // Lifecycle and related code

    /**
     * Fetches the statsd IBinder service
     */
    private static IStatsManager fetchStatsdService() {
        return IStatsManager.Stub.asInterface(ServiceManager.getService("stats"));
    }

    public static final class Lifecycle extends SystemService {
        private StatsCompanionService mStatsCompanionService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mStatsCompanionService = new StatsCompanionService(getContext());
            try {
                publishBinderService(Context.STATS_COMPANION_SERVICE, mStatsCompanionService);
                if (DEBUG) Slog.d(TAG, "Published " + Context.STATS_COMPANION_SERVICE);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to publishBinderService", e);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
            if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                mStatsCompanionService.systemReady();
            }
        }
    }

    /**
     * Now that the android system is ready, StatsCompanion is ready too, so inform statsd.
     */
    private void systemReady() {
        if (DEBUG) Slog.d(TAG, "Learned that systemReady");
        sayHiToStatsd();
    }

    /**
     * Tells statsd that statscompanion is ready. If the binder call returns, link to statsd.
     */
    private void sayHiToStatsd() {
        synchronized (sStatsdLock) {
            if (sStatsd != null) {
                Slog.e(TAG, "Trying to fetch statsd, but it was already fetched",
                        new IllegalStateException("sStatsd is not null when being fetched"));
                return;
            }
            sStatsd = fetchStatsdService();
            if (sStatsd == null) {
                Slog.w(TAG, "Could not access statsd");
                return;
            }
            if (DEBUG) Slog.d(TAG, "Saying hi to statsd");
            try {
                sStatsd.statsCompanionReady();
                // If the statsCompanionReady two-way binder call returns, link to statsd.
                try {
                    sStatsd.asBinder().linkToDeath(new StatsdDeathRecipient(), 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath(StatsdDeathRecipient) failed", e);
                    forgetEverything();
                }
                // Setup broadcast receiver for updates
                IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
                filter.addAction(Intent.ACTION_PACKAGE_ADDED);
                filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                filter.addDataScheme("package");
                mContext.registerReceiverAsUser(mAppUpdateReceiver, UserHandle.ALL, filter, null,
                        null);

                // Setup receiver for user initialize (which happens once for a new user) and
                // if a user is removed.
                filter = new IntentFilter(Intent.ACTION_USER_INITIALIZE);
                filter.addAction(Intent.ACTION_USER_REMOVED);
                mContext.registerReceiverAsUser(mUserUpdateReceiver, UserHandle.ALL,
                        filter, null, null);

                // Pull the latest state of UID->app name, version mapping when statsd starts.
                informAllUidsLocked(mContext);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to inform statsd that statscompanion is ready", e);
                forgetEverything();
            }
        }
    }

    private class StatsdDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Slog.i(TAG, "Statsd is dead - erase all my knowledge.");
            forgetEverything();
        }
    }

    private void forgetEverything() {
        synchronized (sStatsdLock) {
            sStatsd = null;
            mContext.unregisterReceiver(mAppUpdateReceiver);
            mContext.unregisterReceiver(mUserUpdateReceiver);
            cancelAnomalyAlarm();
            cancelPollingAlarms();
        }
    }

}
