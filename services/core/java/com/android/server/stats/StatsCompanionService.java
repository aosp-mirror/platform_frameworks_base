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

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.NetworkStats;
import android.net.wifi.IWifiManager;
import android.net.wifi.WifiActivityEnergyInfo;
import android.os.SystemClock;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IStatsCompanionService;
import android.os.IStatsManager;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatsLogEventWrapper;
import android.os.SynchronousResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.net.NetworkStatsFactory;
import com.android.internal.os.KernelWakelockReader;
import com.android.internal.os.KernelWakelockStats;
import com.android.internal.os.KernelCpuSpeedReader;
import com.android.internal.os.PowerProfile;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Helper service for statsd (the native stats management service in cmds/statsd/).
 * Used for registering and receiving alarms on behalf of statsd.
 *
 * @hide
 */
public class StatsCompanionService extends IStatsCompanionService.Stub {
    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;

    public static final String RESULT_RECEIVER_CONTROLLER_KEY = "controller_activity";

    static final String TAG = "StatsCompanionService";
    static final boolean DEBUG = true;
    public static final String ACTION_TRIGGER_COLLECTION =
        "com.android.server.stats.action.TRIGGER_COLLECTION";

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    @GuardedBy("sStatsdLock")
    private static IStatsManager sStatsd;
    private static final Object sStatsdLock = new Object();

    private final PendingIntent mAnomalyAlarmIntent;
    private final PendingIntent mPullingAlarmIntent;
    private final BroadcastReceiver mAppUpdateReceiver;
    private final BroadcastReceiver mUserUpdateReceiver;
    private final ShutdownEventReceiver mShutdownEventReceiver;
    private final KernelWakelockReader mKernelWakelockReader = new KernelWakelockReader();
    private final KernelWakelockStats mTmpWakelockStats = new KernelWakelockStats();
    private final KernelCpuSpeedReader[] mKernelCpuSpeedReaders;
    private IWifiManager mWifiManager = null;
    private TelephonyManager mTelephony = null;

    public StatsCompanionService(Context context) {
        super();
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        mAnomalyAlarmIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(mContext, AnomalyAlarmReceiver.class), 0);
        mPullingAlarmIntent = PendingIntent.getBroadcast(
            mContext, 0, new Intent(mContext, PullingAlarmReceiver.class), 0);
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
        mShutdownEventReceiver = new ShutdownEventReceiver();
        Slog.w(TAG, "Registered receiver for ACTION_PACKAGE_REPLACE AND ADDED.");
        PowerProfile powerProfile = new PowerProfile(context);
        final int numClusters = powerProfile.getNumCpuClusters();
        mKernelCpuSpeedReaders = new KernelCpuSpeedReader[numClusters];
        int firstCpuOfCluster = 0;
        for (int i = 0; i < numClusters; i++) {
            final int numSpeedSteps = powerProfile.getNumSpeedStepsInCpuCluster(i);
            mKernelCpuSpeedReaders[i] = new KernelCpuSpeedReader(firstCpuOfCluster,
                            numSpeedSteps);
            firstCpuOfCluster += powerProfile.getNumCoresInCpuCluster(i);
        }
    }

    @Override
    public void sendBroadcast(String pkg, String cls) {
        mContext.sendBroadcastAsUser(new Intent(ACTION_TRIGGER_COLLECTION).setClassName(pkg, cls),
                UserHandle.SYSTEM);
    }

    private final static int[] toIntArray(List<Integer> list) {
        int[] ret = new int[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    private final static long[] toLongArray(List<Long> list) {
        long[] ret = new long[list.size()];
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
        List<Long> versions = new ArrayList();
        List<String> apps = new ArrayList();

        // Add in all the apps for every user/profile.
        for (UserInfo profile : users) {
            List<PackageInfo> pi = pm.getInstalledPackagesAsUser(0, profile.id);
            for (int j = 0; j < pi.size(); j++) {
                if (pi.get(j).applicationInfo != null) {
                    uids.add(pi.get(j).applicationInfo.uid);
                    versions.add(pi.get(j).getLongVersionCode());
                    apps.add(pi.get(j).packageName);
                }
            }
        }
        sStatsd.informAllUidData(toIntArray(uids), toLongArray(versions), apps.toArray(new
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
                    Slog.w(TAG, "Could not access statsd to inform it of an app update");
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
                        sStatsd.informOnePackage(app, uid, pi.getLongVersionCode());
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

    public final static class PullingAlarmReceiver extends BroadcastReceiver {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (DEBUG)
          Slog.d(TAG, "Time to poll something.");
        synchronized (sStatsdLock) {
          if (sStatsd == null) {
            Slog.w(TAG, "Could not access statsd to inform it of pulling alarm firing.");
            return;
          }
          try {
            // Two-way call to statsd to retain AlarmManager wakelock
            sStatsd.informPollAlarmFired();
          } catch (RemoteException e) {
            Slog.w(TAG, "Failed to inform statsd of pulling alarm firing.", e);
          }
        }
        // AlarmManager releases its own wakelock here.
      }
    }

    public final static class ShutdownEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /**
             * Skip immediately if intent is not relevant to device shutdown.
             */
            if (!intent.getAction().equals(Intent.ACTION_REBOOT)
                    && !intent.getAction().equals(Intent.ACTION_SHUTDOWN)) {
                return;
            }
            Slog.i(TAG, "StatsCompanionService noticed a shutdown.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of a shutdown event.");
                    return;
                }
                try {
                    sStatsd.writeDataToDisk();
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to inform statsd of a shutdown event.", e);
                }
            }
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
    public void setPullingAlarms(long timestampMs, long intervalMs) {
      enforceCallingPermission();
      if (DEBUG)
        Slog.d(TAG, "Setting pulling alarm for " + timestampMs + " every " + intervalMs + "ms");
      final long callingToken = Binder.clearCallingIdentity();
      try {
        // using RTC, not RTC_WAKEUP, so if device is asleep, will only fire when it awakens.
        // This alarm is inexact, leaving its exactness completely up to the OS optimizations.
        // TODO: totally inexact means that stats per bucket could be quite off. Is this okay?
        mAlarmManager.setRepeating(AlarmManager.RTC, timestampMs, intervalMs, mPullingAlarmIntent);
      } finally {
        Binder.restoreCallingIdentity(callingToken);
      }
    }

    @Override // Binder call
    public void cancelPullingAlarms() {
      enforceCallingPermission();
      if (DEBUG)
        Slog.d(TAG, "Cancelling pulling alarm");
      final long callingToken = Binder.clearCallingIdentity();
      try {
        mAlarmManager.cancel(mPullingAlarmIntent);
      } finally {
        Binder.restoreCallingIdentity(callingToken);
      }
    }

    private StatsLogEventWrapper[] addNetworkStats(int tag, NetworkStats stats, boolean withFGBG) {
        List<StatsLogEventWrapper> ret = new ArrayList<>();
        int size = stats.size();
        NetworkStats.Entry entry = new NetworkStats.Entry(); // For recycling
        for (int j = 0; j < size; j++) {
            stats.getValues(j, entry);
            StatsLogEventWrapper e = new StatsLogEventWrapper(tag, withFGBG ? 6 : 5);
            e.writeInt(entry.uid);
            if (withFGBG) {
                e.writeInt(entry.set);
            }
            e.writeLong(entry.rxBytes);
            e.writeLong(entry.rxPackets);
            e.writeLong(entry.txBytes);
            e.writeLong(entry.txPackets);
            ret.add(e);
        }
        return ret.toArray(new StatsLogEventWrapper[ret.size()]);
    }

    /**
     * Allows rollups per UID but keeping the set (foreground/background) slicing.
     * Adapted from groupedByUid in frameworks/base/core/java/android/net/NetworkStats.java
     */
    private NetworkStats rollupNetworkStatsByFGBG(NetworkStats stats) {
        final NetworkStats ret = new NetworkStats(stats.getElapsedRealtime(), 1);

        final NetworkStats.Entry entry = new NetworkStats.Entry();
        entry.iface = NetworkStats.IFACE_ALL;
        entry.tag = NetworkStats.TAG_NONE;
        entry.metered = NetworkStats.METERED_ALL;
        entry.roaming = NetworkStats.ROAMING_ALL;

        int size = stats.size();
        NetworkStats.Entry recycle = new NetworkStats.Entry(); // Used for retrieving values
        for (int i = 0; i < size; i++) {
            stats.getValues(i, recycle);

            // Skip specific tags, since already counted in TAG_NONE
            if (recycle.tag != NetworkStats.TAG_NONE) continue;

            entry.set = recycle.set; // Allows slicing by background/foreground
            entry.uid = recycle.uid;
            entry.rxBytes = recycle.rxBytes;
            entry.rxPackets = recycle.rxPackets;
            entry.txBytes = recycle.txBytes;
            entry.txPackets = recycle.txPackets;
            // Operations purposefully omitted since we don't use them for statsd.
            ret.combineValues(entry);
        }
        return ret;
    }

    /**
     * Helper method to extract the Parcelable controller info from a
     * SynchronousResultReceiver.
     */
    private static <T extends Parcelable> T awaitControllerInfo(
            @Nullable SynchronousResultReceiver receiver) {
        if (receiver == null) {
            return null;
        }

        try {
            final SynchronousResultReceiver.Result result =
                    receiver.awaitResult(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS);
            if (result.bundle != null) {
                // This is the final destination for the Bundle.
                result.bundle.setDefusable(true);

                final T data = result.bundle.getParcelable(
                        RESULT_RECEIVER_CONTROLLER_KEY);
                if (data != null) {
                    return data;
                }
            }
            Slog.e(TAG, "no controller energy info supplied for " + receiver.getName());
        } catch (TimeoutException e) {
            Slog.w(TAG, "timeout reading " + receiver.getName() + " stats");
        }
        return null;
    }

    /**
     *
     * Pulls wifi controller activity energy info from WiFiManager
     */
    @Override // Binder call
    public StatsLogEventWrapper[] pullData(int tagId) {
        enforceCallingPermission();
        if (DEBUG)
            Slog.d(TAG, "Pulling " + tagId);

        switch (tagId) {
            case StatsLog.WIFI_BYTES_TRANSFER: {
                long token = Binder.clearCallingIdentity();
                try {
                    // TODO: Consider caching the following call to get BatteryStatsInternal.
                    BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
                    String[] ifaces = bs.getWifiIfaces();
                    if (ifaces.length == 0) {
                        return null;
                    }
                    NetworkStatsFactory nsf = new NetworkStatsFactory();
                    // Combine all the metrics per Uid into one record.
                    NetworkStats stats = nsf.readNetworkStatsDetail(NetworkStats.UID_ALL, ifaces,
                            NetworkStats.TAG_NONE, null).groupedByUid();
                    return addNetworkStats(tagId, stats, false);
                } catch (java.io.IOException e) {
                    Slog.e(TAG, "Pulling netstats for wifi bytes has error", e);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                break;
            }
            case StatsLog.MOBILE_BYTES_TRANSFER: {
                long token = Binder.clearCallingIdentity();
                try {
                    BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
                    String[] ifaces = bs.getMobileIfaces();
                    if (ifaces.length == 0) {
                        return null;
                    }
                    NetworkStatsFactory nsf = new NetworkStatsFactory();
                    // Combine all the metrics per Uid into one record.
                    NetworkStats stats = nsf.readNetworkStatsDetail(NetworkStats.UID_ALL, ifaces,
                        NetworkStats.TAG_NONE, null).groupedByUid();
                    return addNetworkStats(tagId, stats, false);
                } catch (java.io.IOException e) {
                    Slog.e(TAG, "Pulling netstats for mobile bytes has error", e);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                break;
            }
            case StatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG: {
                long token = Binder.clearCallingIdentity();
                try {
                    BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
                    String[] ifaces = bs.getWifiIfaces();
                    if (ifaces.length == 0) {
                        return null;
                    }
                    NetworkStatsFactory nsf = new NetworkStatsFactory();
                    NetworkStats stats = rollupNetworkStatsByFGBG(
                            nsf.readNetworkStatsDetail(NetworkStats.UID_ALL, ifaces,
                            NetworkStats.TAG_NONE, null));
                    return addNetworkStats(tagId, stats, true);
                } catch (java.io.IOException e) {
                    Slog.e(TAG, "Pulling netstats for wifi bytes w/ fg/bg has error", e);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                break;
            }
            case StatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG: {
                long token = Binder.clearCallingIdentity();
                try {
                    BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
                    String[] ifaces = bs.getMobileIfaces();
                    if (ifaces.length == 0) {
                        return null;
                    }
                    NetworkStatsFactory nsf = new NetworkStatsFactory();
                    NetworkStats stats = rollupNetworkStatsByFGBG(
                            nsf.readNetworkStatsDetail(NetworkStats.UID_ALL, ifaces,
                            NetworkStats.TAG_NONE, null));
                    return addNetworkStats(tagId, stats, true);
                } catch (java.io.IOException e) {
                    Slog.e(TAG, "Pulling netstats for mobile bytes w/ fg/bg has error", e);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                break;
            }
            case StatsLog.KERNEL_WAKELOCK: {
                final KernelWakelockStats wakelockStats =
                        mKernelWakelockReader.readKernelWakelockStats(mTmpWakelockStats);
                List<StatsLogEventWrapper> ret = new ArrayList();
                for (Map.Entry<String, KernelWakelockStats.Entry> ent : wakelockStats.entrySet()) {
                    String name = ent.getKey();
                    KernelWakelockStats.Entry kws = ent.getValue();
                    StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, 4);
                    e.writeString(name);
                    e.writeInt(kws.mCount);
                    e.writeInt(kws.mVersion);
                    e.writeLong(kws.mTotalTime);
                    ret.add(e);
                }
                return ret.toArray(new StatsLogEventWrapper[ret.size()]);
            }
            case StatsLog.CPU_TIME_PER_FREQ: {
                List<StatsLogEventWrapper> ret = new ArrayList();
                for (int cluster = 0; cluster < mKernelCpuSpeedReaders.length; cluster++) {
                    long[] clusterTimeMs = mKernelCpuSpeedReaders[cluster].readAbsolute();
                    if (clusterTimeMs != null) {
                        for (int speed = clusterTimeMs.length - 1; speed >= 0; --speed) {
                            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, 3);
                            e.writeInt(tagId);
                            e.writeInt(speed);
                            e.writeLong(clusterTimeMs[speed]);
                            ret.add(e);
                        }
                    }
                }
                return ret.toArray(new StatsLogEventWrapper[ret.size()]);
            }
            case StatsLog.WIFI_ACTIVITY_ENERGY_INFO: {
                List<StatsLogEventWrapper> ret = new ArrayList();
                long token = Binder.clearCallingIdentity();
                if (mWifiManager == null) {
                    mWifiManager = IWifiManager.Stub.asInterface(ServiceManager.getService(
                            Context.WIFI_SERVICE));
                }
                if (mWifiManager != null) {
                    try {
                        SynchronousResultReceiver wifiReceiver = new SynchronousResultReceiver("wifi");
                        mWifiManager.requestActivityInfo(wifiReceiver);
                        final WifiActivityEnergyInfo wifiInfo = awaitControllerInfo(wifiReceiver);
                        StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, 6);
                        e.writeLong(wifiInfo.getTimeStamp());
                        e.writeInt(wifiInfo.getStackState());
                        e.writeLong(wifiInfo.getControllerTxTimeMillis());
                        e.writeLong(wifiInfo.getControllerRxTimeMillis());
                        e.writeLong(wifiInfo.getControllerIdleTimeMillis());
                        e.writeLong(wifiInfo.getControllerEnergyUsed());
                        ret.add(e);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Pulling wifiManager for wifi controller activity energy info has error", e);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                break;
            }
            case StatsLog.MODEM_ACTIVITY_INFO: {
                List<StatsLogEventWrapper> ret = new ArrayList();
                long token = Binder.clearCallingIdentity();
                if (mTelephony == null) {
                    mTelephony = TelephonyManager.from(mContext);
                }
                if (mTelephony != null) {
                    SynchronousResultReceiver modemReceiver = new SynchronousResultReceiver("telephony");
                    mTelephony.requestModemActivityInfo(modemReceiver);
                    final ModemActivityInfo modemInfo = awaitControllerInfo(modemReceiver);
                    StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, 6);
                    e.writeLong(modemInfo.getTimestamp());
                    e.writeLong(modemInfo.getSleepTimeMillis());
                    e.writeLong(modemInfo.getIdleTimeMillis());
                    e.writeLong(modemInfo.getTxTimeMillis()[0]);
                    e.writeLong(modemInfo.getTxTimeMillis()[1]);
                    e.writeLong(modemInfo.getTxTimeMillis()[2]);
                    e.writeLong(modemInfo.getTxTimeMillis()[3]);
                    e.writeLong(modemInfo.getTxTimeMillis()[4]);
                    e.writeLong(modemInfo.getRxTimeMillis());
                    e.writeLong(modemInfo.getEnergyUsed());
                    ret.add(e);
                }
                break;
            }
            case StatsLog.CPU_SUSPEND_TIME: {
                List<StatsLogEventWrapper> ret = new ArrayList();
                StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, 1);
                e.writeLong(SystemClock.elapsedRealtime());
                ret.add(e);
                break;
            }
            case StatsLog.CPU_IDLE_TIME: {
                List<StatsLogEventWrapper> ret = new ArrayList();
                StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, 1);
                e.writeLong(SystemClock.uptimeMillis());
                ret.add(e);
                break;
            }
            default:
                Slog.w(TAG, "No such tagId data as " + tagId);
                return null;
        }
        return null;
    }

    @Override // Binder call
    public void statsdReady() {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "learned that statsdReady");
        sayHiToStatsd(); // tell statsd that we're ready too and link to it
    }

    @Override
    public void triggerUidSnapshot() {
      enforceCallingPermission();
      synchronized (sStatsdLock) {
        try {
          informAllUidsLocked(mContext);
        } catch (RemoteException e) {
          Slog.e(TAG, "Failed to trigger uid snapshot.", e);
        }
      }
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
                // Setup broadcast receiver for updates.
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

                // Setup receiver for device reboots or shutdowns.
                filter = new IntentFilter(Intent.ACTION_REBOOT);
                filter.addAction(Intent.ACTION_SHUTDOWN);
                mContext.registerReceiverAsUser(
                        mShutdownEventReceiver, UserHandle.ALL, filter, null, null);
                final long token = Binder.clearCallingIdentity();
                try {
                    // Pull the latest state of UID->app name, version mapping when statsd starts.
                    informAllUidsLocked(mContext);
                } finally {
                    restoreCallingIdentity(token);
                }
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
            mContext.unregisterReceiver(mShutdownEventReceiver);
            cancelAnomalyAlarm();
            cancelPullingAlarms();
        }
    }

}
