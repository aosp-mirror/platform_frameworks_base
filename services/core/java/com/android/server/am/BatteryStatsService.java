/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

package com.android.server.am;

import android.bluetooth.BluetoothActivityEnergyInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.INetworkManagementEventObserver;
import android.net.NetworkCapabilities;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFormatException;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.os.WorkSource;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.GpsBatteryStats;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.connectivity.WifiBatteryStats;
import android.os.health.HealthStatsParceler;
import android.os.health.HealthStatsWriter;
import android.os.health.UidHealthStats;
import android.provider.Settings;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsHelper;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.RailStats;
import com.android.internal.os.RpmStats;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.ParseUtils;
import com.android.server.LocalServices;
import com.android.server.net.BaseNetworkObserver;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.
 */
public final class BatteryStatsService extends IBatteryStats.Stub
        implements PowerManagerInternal.LowPowerModeListener,
        BatteryStatsImpl.PlatformIdleStateCallback,
        BatteryStatsImpl.RailEnergyDataCallback {
    static final String TAG = "BatteryStatsService";
    static final boolean DBG = false;

    private static IBatteryStats sService;

    final BatteryStatsImpl mStats;
    private final BatteryStatsImpl.UserInfoProvider mUserManagerUserInfoProvider;
    private final Context mContext;
    private final BatteryExternalStatsWorker mWorker;

    private native void getLowPowerStats(RpmStats rpmStats);
    private native int getPlatformLowPowerStats(ByteBuffer outBuffer);
    private native int getSubsystemLowPowerStats(ByteBuffer outBuffer);
    private native void getRailEnergyPowerStats(RailStats railStats);
    private CharsetDecoder mDecoderStat = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("?");
    private ByteBuffer mUtf8BufferStat = ByteBuffer.allocateDirect(MAX_LOW_POWER_STATS_SIZE);
    private CharBuffer mUtf16BufferStat = CharBuffer.allocate(MAX_LOW_POWER_STATS_SIZE);
    private static final int MAX_LOW_POWER_STATS_SIZE = 4096;
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    @GuardedBy("mStats")
    private int mLastPowerStateFromRadio = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    @GuardedBy("mStats")
    private int mLastPowerStateFromWifi = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    private final INetworkManagementEventObserver mActivityChangeObserver =
            new BaseNetworkObserver() {
                @Override
                public void interfaceClassDataActivityChanged(int transportType, boolean active,
                        long tsNanos, int uid) {
                    final int powerState = active
                            ? DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                            : DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
                    final long timestampNanos;
                    if (tsNanos <= 0) {
                        timestampNanos = SystemClock.elapsedRealtimeNanos();
                    } else {
                        timestampNanos = tsNanos;
                    }

                    switch (transportType) {
                        case NetworkCapabilities.TRANSPORT_CELLULAR:
                            noteMobileRadioPowerState(powerState, timestampNanos, uid);
                            break;
                        case NetworkCapabilities.TRANSPORT_WIFI:
                            noteWifiRadioPowerState(powerState, timestampNanos, uid);
                            break;
                        default:
                            Slog.d(TAG, "Received unexpected transport in "
                                    + "interfaceClassDataActivityChanged unexpected type: "
                                    + transportType);
                    }
                }
            };
    /**
     * Replaces the information in the given rpmStats with up-to-date information.
     */
    @Override
    public void fillLowPowerStats(RpmStats rpmStats) {
        if (DBG) Slog.d(TAG, "begin getLowPowerStats");
        try {
            getLowPowerStats(rpmStats);
        } finally {
            if (DBG) Slog.d(TAG, "end getLowPowerStats");
        }
    }

    @Override
    public void fillRailDataStats(RailStats railStats) {
        if (DBG) Slog.d(TAG, "begin getRailEnergyPowerStats");
        try {
            getRailEnergyPowerStats(railStats);
        } finally {
            if (DBG) Slog.d(TAG, "end getRailEnergyPowerStats");
        }
    }

    @Override
    public String getPlatformLowPowerStats() {
        if (DBG) Slog.d(TAG, "begin getPlatformLowPowerStats");
        try {
            mUtf8BufferStat.clear();
            mUtf16BufferStat.clear();
            mDecoderStat.reset();
            int bytesWritten = getPlatformLowPowerStats(mUtf8BufferStat);
            if (bytesWritten < 0) {
                return null;
            } else if (bytesWritten == 0) {
                return "Empty";
            }
            mUtf8BufferStat.limit(bytesWritten);
            mDecoderStat.decode(mUtf8BufferStat, mUtf16BufferStat, true);
            mUtf16BufferStat.flip();
            return mUtf16BufferStat.toString();
        } finally {
            if (DBG) Slog.d(TAG, "end getPlatformLowPowerStats");
        }
    }

    @Override
    public String getSubsystemLowPowerStats() {
        if (DBG) Slog.d(TAG, "begin getSubsystemLowPowerStats");
        try {
            mUtf8BufferStat.clear();
            mUtf16BufferStat.clear();
            mDecoderStat.reset();
            int bytesWritten = getSubsystemLowPowerStats(mUtf8BufferStat);
            if (bytesWritten < 0) {
                return null;
            } else if (bytesWritten == 0) {
                return "Empty";
            }
            mUtf8BufferStat.limit(bytesWritten);
            mDecoderStat.decode(mUtf8BufferStat, mUtf16BufferStat, true);
            mUtf16BufferStat.flip();
            return mUtf16BufferStat.toString();
        } finally {
            if (DBG) Slog.d(TAG, "end getSubsystemLowPowerStats");
        }
    }

    BatteryStatsService(Context context, File systemDir, Handler handler) {
        // BatteryStatsImpl expects the ActivityManagerService handler, so pass that one through.
        mContext = context;
        mUserManagerUserInfoProvider = new BatteryStatsImpl.UserInfoProvider() {
            private UserManagerInternal umi;
            @Override
            public int[] getUserIds() {
                if (umi == null) {
                    umi = LocalServices.getService(UserManagerInternal.class);
                }
                return (umi != null) ? umi.getUserIds() : null;
            }
        };
        mHandlerThread = new HandlerThread("batterystats-handler");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mStats = new BatteryStatsImpl(systemDir, handler, this,
                this, mUserManagerUserInfoProvider);
        mWorker = new BatteryExternalStatsWorker(context, mStats);
        mStats.setExternalStatsSyncLocked(mWorker);
        mStats.setRadioScanningTimeoutLocked(mContext.getResources().getInteger(
                com.android.internal.R.integer.config_radioScanningTimeout) * 1000L);
        mStats.setPowerProfileLocked(new PowerProfile(context));
    }

    public void publish() {
        LocalServices.addService(BatteryStatsInternal.class, new LocalService());
        ServiceManager.addService(BatteryStats.SERVICE_NAME, asBinder());
    }

    public void systemServicesReady() {
        final INetworkManagementService nms = INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        try {
            nms.registerObserver(mActivityChangeObserver);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not register INetworkManagement event observer " + e);
        }
        mStats.systemServicesReady(mContext);

        final DataConnectionStats dataConnectionStats = new DataConnectionStats(mContext, mHandler);
        dataConnectionStats.startMonitoring();
    }

    private final class LocalService extends BatteryStatsInternal {
        @Override
        public String[] getWifiIfaces() {
            return mStats.getWifiIfaces().clone();
        }

        @Override
        public String[] getMobileIfaces() {
            return mStats.getMobileIfaces().clone();
        }

        @Override
        public void noteJobsDeferred(int uid, int numDeferred, long sinceLast) {
            if (DBG) Slog.d(TAG, "Jobs deferred " + uid + ": " + numDeferred + " " + sinceLast);
            BatteryStatsService.this.noteJobsDeferred(uid, numDeferred, sinceLast);
        }
    }

    private static void awaitUninterruptibly(Future<?> future) {
        while (true) {
            try {
                future.get();
                return;
            } catch (ExecutionException e) {
                return;
            } catch (InterruptedException e) {
                // Keep looping
            }
        }
    }

    private void syncStats(String reason, int flags) {
        awaitUninterruptibly(mWorker.scheduleSync(reason, flags));
    }

    /**
     * At the time when the constructor runs, the power manager has not yet been
     * initialized.  So we initialize the low power observer later.
     */
    public void initPowerManagement() {
        final PowerManagerInternal powerMgr = LocalServices.getService(PowerManagerInternal.class);
        powerMgr.registerLowPowerModeObserver(this);
        synchronized (mStats) {
            mStats.notePowerSaveModeLocked(
                    powerMgr.getLowPowerState(ServiceType.BATTERY_STATS)
                            .batterySaverEnabled);
        }
        (new WakeupReasonThread()).start();
    }

    public void shutdown() {
        Slog.w("BatteryStats", "Writing battery stats before shutdown...");

        syncStats("shutdown", BatteryExternalStatsWorker.UPDATE_ALL);

        synchronized (mStats) {
            mStats.shutdownLocked();
        }

        // Shutdown the thread we made.
        mWorker.shutdown();
    }

    public static IBatteryStats getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(BatteryStats.SERVICE_NAME);
        sService = asInterface(b);
        return sService;
    }

    @Override
    public int getServiceType() {
        return ServiceType.BATTERY_STATS;
    }

    @Override
    public void onLowPowerModeChanged(PowerSaveState result) {
        synchronized (mStats) {
            mStats.notePowerSaveModeLocked(result.batterySaverEnabled);
        }
    }

    /**
     * @return the current statistics object, which may be modified
     * to reflect events that affect battery usage.  You must lock the
     * stats object before doing anything with it.
     */
    public BatteryStatsImpl getActiveStatistics() {
        return mStats;
    }

    /**
     * Schedules a write to disk to occur. This will cause the BatteryStatsImpl
     * object to update with the latest info, then write to disk.
     */
    public void scheduleWriteToDisk() {
        mWorker.scheduleWrite();
    }

    // These are for direct use by the activity manager...

    /**
     * Remove a UID from the BatteryStats and BatteryStats' external dependencies.
     */
    void removeUid(int uid) {
        synchronized (mStats) {
            mStats.removeUidStatsLocked(uid);
        }
    }

    void onCleanupUser(int userId) {
        synchronized (mStats) {
            mStats.onCleanupUserLocked(userId);
        }
    }

    void onUserRemoved(int userId) {
        synchronized (mStats) {
            mStats.onUserRemovedLocked(userId);
        }
    }

    void addIsolatedUid(int isolatedUid, int appUid) {
        synchronized (mStats) {
            mStats.addIsolatedUidLocked(isolatedUid, appUid);
        }
    }

    void removeIsolatedUid(int isolatedUid, int appUid) {
        synchronized (mStats) {
            mStats.scheduleRemoveIsolatedUidLocked(isolatedUid, appUid);
        }
    }

    void noteProcessStart(String name, int uid) {
        synchronized (mStats) {
            mStats.noteProcessStartLocked(name, uid);
            FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                    FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__STARTED);
        }
    }

    void noteProcessCrash(String name, int uid) {
        synchronized (mStats) {
            mStats.noteProcessCrashLocked(name, uid);
            FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                    FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__CRASHED);
        }
    }

    void noteProcessAnr(String name, int uid) {
        synchronized (mStats) {
            mStats.noteProcessAnrLocked(name, uid);
        }
    }

    void noteProcessFinish(String name, int uid) {
        synchronized (mStats) {
            mStats.noteProcessFinishLocked(name, uid);
            FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                    FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__FINISHED);
        }
    }

    /** @param state Process state from ActivityManager.java. */
    void noteUidProcessState(int uid, int state) {
        synchronized (mStats) {
            mStats.noteUidProcessStateLocked(uid, state);
        }
    }

    // Public interface...

    public byte[] getStatistics() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        //Slog.i("foo", "SENDING BATTERY INFO:");
        //mStats.dumpLocked(new LogPrinter(Log.INFO, "foo", Log.LOG_ID_SYSTEM));
        Parcel out = Parcel.obtain();
        syncStats("get-stats", BatteryExternalStatsWorker.UPDATE_ALL);
        synchronized (mStats) {
            mStats.writeToParcel(out, 0);
        }
        byte[] data = out.marshall();
        out.recycle();
        return data;
    }

    public ParcelFileDescriptor getStatisticsStream() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        //Slog.i("foo", "SENDING BATTERY INFO:");
        //mStats.dumpLocked(new LogPrinter(Log.INFO, "foo", Log.LOG_ID_SYSTEM));
        Parcel out = Parcel.obtain();
        syncStats("get-stats", BatteryExternalStatsWorker.UPDATE_ALL);
        synchronized (mStats) {
            mStats.writeToParcel(out, 0);
        }
        byte[] data = out.marshall();
        if (DBG) Slog.d(TAG, "getStatisticsStream parcel size is:" + data.length);
        out.recycle();
        try {
            return ParcelFileDescriptor.fromData(data, "battery-stats");
        } catch (IOException e) {
            Slog.w(TAG, "Unable to create shared memory", e);
            return null;
        }
    }

    public boolean isCharging() {
        synchronized (mStats) {
            return mStats.isCharging();
        }
    }

    public long computeBatteryTimeRemaining() {
        synchronized (mStats) {
            long time = mStats.computeBatteryTimeRemaining(SystemClock.elapsedRealtime());
            return time >= 0 ? (time/1000) : time;
        }
    }

    public long computeChargeTimeRemaining() {
        synchronized (mStats) {
            long time = mStats.computeChargeTimeRemaining(SystemClock.elapsedRealtime());
            return time >= 0 ? (time/1000) : time;
        }
    }

    public void noteEvent(int code, String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteEventLocked(code, name, uid);
        }
    }

    public void noteSyncStart(String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteSyncStartLocked(name, uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SYNC_STATE_CHANGED, uid, null,
                    name, FrameworkStatsLog.SYNC_STATE_CHANGED__STATE__ON);
        }
    }

    public void noteSyncFinish(String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteSyncFinishLocked(name, uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SYNC_STATE_CHANGED, uid, null,
                    name, FrameworkStatsLog.SYNC_STATE_CHANGED__STATE__OFF);
        }
    }

    /** A scheduled job was started. */
    public void noteJobStart(String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteJobStartLocked(name, uid);
        }
    }

    /** A scheduled job was finished. */
    public void noteJobFinish(String name, int uid, int stopReason) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteJobFinishLocked(name, uid, stopReason);
        }
    }

    void noteJobsDeferred(int uid, int numDeferred, long sinceLast) {
        // No need to enforce calling permission, as it is called from an internal interface
        synchronized (mStats) {
            mStats.noteJobsDeferredLocked(uid, numDeferred, sinceLast);
        }
    }

    public void noteWakupAlarm(String name, int uid, WorkSource workSource, String tag) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWakupAlarmLocked(name, uid, workSource, tag);
        }
    }

    public void noteAlarmStart(String name, WorkSource workSource, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAlarmStartLocked(name, workSource, uid);
        }
    }

    public void noteAlarmFinish(String name, WorkSource workSource, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAlarmFinishLocked(name, workSource, uid);
        }
    }

    public void noteStartWakelock(int uid, int pid, String name, String historyName, int type,
            boolean unimportantForLogging) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartWakeLocked(uid, pid, null, name, historyName, type,
                    unimportantForLogging, SystemClock.elapsedRealtime(),
                    SystemClock.uptimeMillis());
        }
    }

    public void noteStopWakelock(int uid, int pid, String name, String historyName, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopWakeLocked(uid, pid, null, name, historyName, type,
                    SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
        }
    }

    public void noteStartWakelockFromSource(WorkSource ws, int pid, String name,
            String historyName, int type, boolean unimportantForLogging) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartWakeFromSourceLocked(ws, pid, name, historyName,
                    type, unimportantForLogging);
        }
    }

    public void noteChangeWakelockFromSource(WorkSource ws, int pid, String name,
            String historyName, int type, WorkSource newWs, int newPid, String newName,
            String newHistoryName, int newType, boolean newUnimportantForLogging) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteChangeWakelockFromSourceLocked(ws, pid, name, historyName, type,
                    newWs, newPid, newName, newHistoryName, newType, newUnimportantForLogging);
        }
    }

    public void noteStopWakelockFromSource(WorkSource ws, int pid, String name, String historyName,
            int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopWakeFromSourceLocked(ws, pid, name, historyName, type);
        }
    }

    @Override
    public void noteLongPartialWakelockStart(String name, String historyName, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteLongPartialWakelockStart(name, historyName, uid);
        }
    }

    @Override
    public void noteLongPartialWakelockStartFromSource(String name, String historyName,
            WorkSource workSource) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteLongPartialWakelockStartFromSource(name, historyName, workSource);
        }
    }

    @Override
    public void noteLongPartialWakelockFinish(String name, String historyName, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteLongPartialWakelockFinish(name, historyName, uid);
        }
    }

    @Override
    public void noteLongPartialWakelockFinishFromSource(String name, String historyName,
            WorkSource workSource) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteLongPartialWakelockFinishFromSource(name, historyName, workSource);
        }
    }

    public void noteStartSensor(int uid, int sensor) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartSensorLocked(uid, sensor);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SENSOR_STATE_CHANGED, uid, null,
                    sensor, FrameworkStatsLog.SENSOR_STATE_CHANGED__STATE__ON);
        }
    }

    public void noteStopSensor(int uid, int sensor) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopSensorLocked(uid, sensor);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SENSOR_STATE_CHANGED, uid, null,
                    sensor, FrameworkStatsLog.SENSOR_STATE_CHANGED__STATE__OFF);
        }
    }

    public void noteVibratorOn(int uid, long durationMillis) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteVibratorOnLocked(uid, durationMillis);
        }
    }

    public void noteVibratorOff(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteVibratorOffLocked(uid);
        }
    }

    @Override
    public void noteGpsChanged(WorkSource oldWs, WorkSource newWs) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteGpsChangedLocked(oldWs, newWs);
        }
    }

    public void noteGpsSignalQuality(int signalLevel) {
        synchronized (mStats) {
            mStats.noteGpsSignalQualityLocked(signalLevel);
        }
    }

    public void noteScreenState(int state) {
        enforceCallingPermission();
        if (DBG) Slog.d(TAG, "begin noteScreenState");
        synchronized (mStats) {
            FrameworkStatsLog.write(FrameworkStatsLog.SCREEN_STATE_CHANGED, state);

            mStats.noteScreenStateLocked(state);
        }
        if (DBG) Slog.d(TAG, "end noteScreenState");
    }

    public void noteScreenBrightness(int brightness) {
        enforceCallingPermission();
        synchronized (mStats) {
            FrameworkStatsLog.write(FrameworkStatsLog.SCREEN_BRIGHTNESS_CHANGED, brightness);
            mStats.noteScreenBrightnessLocked(brightness);
        }
    }

    public void noteUserActivity(int uid, int event) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteUserActivityLocked(uid, event);
        }
    }

    public void noteWakeUp(String reason, int reasonUid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWakeUpLocked(reason, reasonUid);
        }
    }

    public void noteInteractive(boolean interactive) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteInteractiveLocked(interactive);
        }
    }

    public void noteConnectivityChanged(int type, String extra) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteConnectivityChangedLocked(type, extra);
        }
    }

    public void noteMobileRadioPowerState(int powerState, long timestampNs, int uid) {
        enforceCallingPermission();

        final boolean update;
        synchronized (mStats) {
            // Ignore if no power state change.
            if (mLastPowerStateFromRadio == powerState) return;

            mLastPowerStateFromRadio = powerState;
            update = mStats.noteMobileRadioPowerStateLocked(powerState, timestampNs, uid);
        }

        if (update) {
            mWorker.scheduleSync("modem-data", BatteryExternalStatsWorker.UPDATE_RADIO);
        }
        FrameworkStatsLog.write_non_chained(
                FrameworkStatsLog.MOBILE_RADIO_POWER_STATE_CHANGED, uid, null, powerState);
    }

    public void notePhoneOn() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneOnLocked();
        }
    }

    public void notePhoneOff() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneOffLocked();
        }
    }

    public void notePhoneSignalStrength(SignalStrength signalStrength) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneSignalStrengthLocked(signalStrength);
        }
    }

    public void notePhoneDataConnectionState(int dataType, boolean hasData, int serviceType) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneDataConnectionStateLocked(dataType, hasData, serviceType);
        }
    }

    public void notePhoneState(int state) {
        enforceCallingPermission();
        int simState = mContext.getSystemService(TelephonyManager.class).getSimState();
        synchronized (mStats) {
            mStats.notePhoneStateLocked(state, simState);
        }
    }

    public void noteWifiOn() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiOnLocked();
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED,
                FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED__STATE__ON);
    }

    public void noteWifiOff() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiOffLocked();
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED,
                FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED__STATE__OFF);
    }

    public void noteStartAudio(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAudioOnLocked(uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, uid, null,
                    FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__ON);
        }
    }

    public void noteStopAudio(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAudioOffLocked(uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, uid, null,
                    FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__OFF);
        }
    }

    public void noteStartVideo(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteVideoOnLocked(uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED, uid,
                    null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__ON);
        }
    }

    public void noteStopVideo(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteVideoOffLocked(uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED, uid,
                    null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__OFF);
        }
    }

    public void noteResetAudio() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetAudioLocked();
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, -1, null,
                    FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__RESET);
        }
    }

    public void noteResetVideo() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetVideoLocked();
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED, -1,
                    null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__RESET);
        }
    }

    public void noteFlashlightOn(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFlashlightOnLocked(uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, uid,
                    null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__ON);
        }
    }

    public void noteFlashlightOff(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFlashlightOffLocked(uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, uid,
                    null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__OFF);
        }
    }

    public void noteStartCamera(int uid) {
        enforceCallingPermission();
        if (DBG) Slog.d(TAG, "begin noteStartCamera");
        synchronized (mStats) {
            mStats.noteCameraOnLocked(uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, uid, null,
                    FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__ON);
        }
        if (DBG) Slog.d(TAG, "end noteStartCamera");
    }

    public void noteStopCamera(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteCameraOffLocked(uid);
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, uid, null,
                    FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__OFF);
        }
    }

    public void noteResetCamera() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetCameraLocked();
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, -1, null,
                    FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__RESET);
        }
    }

    public void noteResetFlashlight() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetFlashlightLocked();
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, -1,
                    null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__RESET);
        }
    }

    @Override
    public void noteWifiRadioPowerState(int powerState, long tsNanos, int uid) {
        enforceCallingPermission();

        // There was a change in WiFi power state.
        // Collect data now for the past activity.
        synchronized (mStats) {
            // Ignore if no power state change.
            if (mLastPowerStateFromWifi == powerState) return;

            mLastPowerStateFromWifi = powerState;
            if (mStats.isOnBattery()) {
                final String type = (powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH ||
                        powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM) ? "active"
                        : "inactive";
                mWorker.scheduleSync("wifi-data: " + type, BatteryExternalStatsWorker.UPDATE_WIFI);
            }
            mStats.noteWifiRadioPowerState(powerState, tsNanos, uid);
        }
        FrameworkStatsLog.write_non_chained(
                FrameworkStatsLog.WIFI_RADIO_POWER_STATE_CHANGED, uid, null, powerState);
    }

    public void noteWifiRunning(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiRunningLocked(ws);
        }
        // TODO: Log WIFI_RUNNING_STATE_CHANGED in a better spot to include Hotspot too.
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                ws, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__ON);
    }

    public void noteWifiRunningChanged(WorkSource oldWs, WorkSource newWs) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiRunningChangedLocked(oldWs, newWs);
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                newWs, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__ON);
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                oldWs, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__OFF);
    }

    public void noteWifiStopped(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiStoppedLocked(ws);
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                ws, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__OFF);
    }

    public void noteWifiState(int wifiState, String accessPoint) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiStateLocked(wifiState, accessPoint);
        }
    }

    public void noteWifiSupplicantStateChanged(int supplState, boolean failedAuth) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiSupplicantStateChangedLocked(supplState, failedAuth);
        }
    }

    public void noteWifiRssiChanged(int newRssi) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiRssiChangedLocked(newRssi);
        }
    }

    public void noteFullWifiLockAcquired(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFullWifiLockAcquiredLocked(uid);
        }
    }

    public void noteFullWifiLockReleased(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFullWifiLockReleasedLocked(uid);
        }
    }

    public void noteWifiScanStarted(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiScanStartedLocked(uid);
        }
    }

    public void noteWifiScanStopped(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiScanStoppedLocked(uid);
        }
    }

    public void noteWifiMulticastEnabled(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiMulticastEnabledLocked(uid);
        }
    }

    public void noteWifiMulticastDisabled(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiMulticastDisabledLocked(uid);
        }
    }

    public void noteFullWifiLockAcquiredFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFullWifiLockAcquiredFromSourceLocked(ws);
        }
    }

    public void noteFullWifiLockReleasedFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFullWifiLockReleasedFromSourceLocked(ws);
        }
    }

    public void noteWifiScanStartedFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiScanStartedFromSourceLocked(ws);
        }
    }

    public void noteWifiScanStoppedFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiScanStoppedFromSourceLocked(ws);
        }
    }

    public void noteWifiBatchedScanStartedFromSource(WorkSource ws, int csph) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiBatchedScanStartedFromSourceLocked(ws, csph);
        }
    }

    public void noteWifiBatchedScanStoppedFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiBatchedScanStoppedFromSourceLocked(ws);
        }
    }

    @Override
    public void noteNetworkInterfaceForTransports(final String iface, int[] transportTypes) {
        enforceCallingPermission();
        mStats.noteNetworkInterfaceForTransports(iface, transportTypes);
    }

    @Override
    public void noteNetworkStatsEnabled() {
        enforceCallingPermission();
        // During device boot, qtaguid isn't enabled until after the inital
        // loading of battery stats. Now that they're enabled, take our initial
        // snapshot for future delta calculation.
        mWorker.scheduleSync("network-stats-enabled",
                BatteryExternalStatsWorker.UPDATE_RADIO | BatteryExternalStatsWorker.UPDATE_WIFI);
    }

    @Override
    public void noteDeviceIdleMode(int mode, String activeReason, int activeUid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteDeviceIdleModeLocked(mode, activeReason, activeUid);
        }
    }

    public void notePackageInstalled(String pkgName, long versionCode) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePackageInstalledLocked(pkgName, versionCode);
        }
    }

    public void notePackageUninstalled(String pkgName) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePackageUninstalledLocked(pkgName);
        }
    }

    @Override
    public void noteBleScanStarted(WorkSource ws, boolean isUnoptimized) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteBluetoothScanStartedFromSourceLocked(ws, isUnoptimized);
        }
    }

    @Override
    public void noteBleScanStopped(WorkSource ws, boolean isUnoptimized) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteBluetoothScanStoppedFromSourceLocked(ws, isUnoptimized);
        }
    }

    @Override
    public void noteResetBleScan() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetBluetoothScanLocked();
        }
    }

    @Override
    public void noteBleScanResults(WorkSource ws, int numNewResults) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteBluetoothScanResultsFromSourceLocked(ws, numNewResults);
        }
    }

    @Override
    public void noteWifiControllerActivity(WifiActivityEnergyInfo info) {
        enforceCallingPermission();

        if (info == null || !info.isValid()) {
            Slog.e(TAG, "invalid wifi data given: " + info);
            return;
        }

        mStats.updateWifiState(info);
    }

    @Override
    public void noteBluetoothControllerActivity(BluetoothActivityEnergyInfo info) {
        enforceCallingPermission();
        if (info == null || !info.isValid()) {
            Slog.e(TAG, "invalid bluetooth data given: " + info);
            return;
        }

        synchronized (mStats) {
            mStats.updateBluetoothStateLocked(info);
        }
    }

    @Override
    public void noteModemControllerActivity(ModemActivityInfo info) {
        enforceCallingPermission();

        if (info == null || !info.isValid()) {
            Slog.e(TAG, "invalid modem data given: " + info);
            return;
        }

        mStats.updateMobileRadioState(info);
    }

    public boolean isOnBattery() {
        return mStats.isOnBattery();
    }

    @Override
    public void setBatteryState(final int status, final int health, final int plugType,
            final int level, final int temp, final int volt, final int chargeUAh,
            final int chargeFullUAh, final long chargeTimeToFullSeconds) {
        enforceCallingPermission();

        // BatteryService calls us here and we may update external state. It would be wrong
        // to block such a low level service like BatteryService on external stats like WiFi.
        mWorker.scheduleRunnable(() -> {
            synchronized (mStats) {
                final boolean onBattery = BatteryStatsImpl.isOnBattery(plugType, status);
                if (mStats.isOnBattery() == onBattery) {
                    // The battery state has not changed, so we don't need to sync external
                    // stats immediately.
                    mStats.setBatteryStateLocked(status, health, plugType, level, temp, volt,
                            chargeUAh, chargeFullUAh, chargeTimeToFullSeconds);
                    return;
                }
            }

            // Sync external stats first as the battery has changed states. If we don't sync
            // before changing the state, we may not collect the relevant data later.
            // Order here is guaranteed since we're scheduling from the same thread and we are
            // using a single threaded executor.
            mWorker.scheduleSync("battery-state", BatteryExternalStatsWorker.UPDATE_ALL);
            mWorker.scheduleRunnable(() -> {
                synchronized (mStats) {
                    mStats.setBatteryStateLocked(status, health, plugType, level, temp, volt,
                            chargeUAh, chargeFullUAh, chargeTimeToFullSeconds);
                }
            });
        });
    }

    public long getAwakeTimeBattery() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        return mStats.getAwakeTimeBattery();
    }

    public long getAwakeTimePlugged() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        return mStats.getAwakeTimePlugged();
    }

    public void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    final class WakeupReasonThread extends Thread {
        private static final int MAX_REASON_SIZE = 512;
        private CharsetDecoder mDecoder;
        private ByteBuffer mUtf8Buffer;
        private CharBuffer mUtf16Buffer;

        WakeupReasonThread() {
            super("BatteryStats_wakeupReason");
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

            mDecoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("?");

            mUtf8Buffer = ByteBuffer.allocateDirect(MAX_REASON_SIZE);
            mUtf16Buffer = CharBuffer.allocate(MAX_REASON_SIZE);

            try {
                String reason;
                while ((reason = waitWakeup()) != null) {
                    synchronized (mStats) {
                        mStats.noteWakeupReasonLocked(reason);
                    }
                }
            } catch (RuntimeException e) {
                Slog.e(TAG, "Failure reading wakeup reasons", e);
            }
        }

        private String waitWakeup() {
            mUtf8Buffer.clear();
            mUtf16Buffer.clear();
            mDecoder.reset();

            int bytesWritten = nativeWaitWakeup(mUtf8Buffer);
            if (bytesWritten < 0) {
                return null;
            } else if (bytesWritten == 0) {
                return "unknown";
            }

            // Set the buffer's limit to the number of bytes written.
            mUtf8Buffer.limit(bytesWritten);

            // Decode the buffer from UTF-8 to UTF-16.
            // Unmappable characters will be replaced.
            mDecoder.decode(mUtf8Buffer, mUtf16Buffer, true);
            mUtf16Buffer.flip();

            // Create a String from the UTF-16 buffer.
            return mUtf16Buffer.toString();
        }
    }

    private static native int nativeWaitWakeup(ByteBuffer outBuffer);

    private void dumpHelp(PrintWriter pw) {
        pw.println("Battery stats (batterystats) dump options:");
        pw.println("  [--checkin] [--proto] [--history] [--history-start] [--charged] [-c]");
        pw.println("  [--daily] [--reset] [--write] [--new-daily] [--read-daily] [-h] [<package.name>]");
        pw.println("  --checkin: generate output for a checkin report; will write (and clear) the");
        pw.println("             last old completed stats when they had been reset.");
        pw.println("  -c: write the current stats in checkin format.");
        pw.println("  --proto: write the current aggregate stats (without history) in proto format.");
        pw.println("  --history: show only history data.");
        pw.println("  --history-start <num>: show only history data starting at given time offset.");
        pw.println("  --history-create-events <num>: create <num> of battery history events.");
        pw.println("  --charged: only output data since last charged.");
        pw.println("  --daily: only output full daily data.");
        pw.println("  --reset: reset the stats, clearing all current data.");
        pw.println("  --write: force write current collected stats to disk.");
        pw.println("  --new-daily: immediately create and write new daily stats record.");
        pw.println("  --read-daily: read-load last written daily stats.");
        pw.println("  --settings: dump the settings key/values related to batterystats");
        pw.println("  --cpu: dump cpu stats for debugging purpose");
        pw.println("  <package.name>: optional name of package to filter output by.");
        pw.println("  -h: print this help text.");
        pw.println("Battery stats (batterystats) commands:");
        pw.println("  enable|disable <option>");
        pw.println("    Enable or disable a running option.  Option state is not saved across boots.");
        pw.println("    Options are:");
        pw.println("      full-history: include additional detailed events in battery history:");
        pw.println("          wake_lock_in, alarms and proc events");
        pw.println("      no-auto-reset: don't automatically reset stats when unplugged");
        pw.println("      pretend-screen-off: pretend the screen is off, even if screen state changes");
    }

    private void dumpSettings(PrintWriter pw) {
        synchronized (mStats) {
            mStats.dumpConstantsLocked(pw);
        }
    }

    private void dumpCpuStats(PrintWriter pw) {
        synchronized (mStats) {
            mStats.dumpCpuStatsLocked(pw);
        }
    }

    private int doEnableOrDisable(PrintWriter pw, int i, String[] args, boolean enable) {
        i++;
        if (i >= args.length) {
            pw.println("Missing option argument for " + (enable ? "--enable" : "--disable"));
            dumpHelp(pw);
            return -1;
        }
        if ("full-wake-history".equals(args[i]) || "full-history".equals(args[i])) {
            synchronized (mStats) {
                mStats.setRecordAllHistoryLocked(enable);
            }
        } else if ("no-auto-reset".equals(args[i])) {
            synchronized (mStats) {
                mStats.setNoAutoReset(enable);
            }
        } else if ("pretend-screen-off".equals(args[i])) {
            synchronized (mStats) {
                mStats.setPretendScreenOff(enable);
            }
        } else {
            pw.println("Unknown enable/disable option: " + args[i]);
            dumpHelp(pw);
            return -1;
        }
        return i;
    }


    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        int flags = 0;
        boolean useCheckinFormat = false;
        boolean toProto = false;
        boolean isRealCheckin = false;
        boolean noOutput = false;
        boolean writeData = false;
        long historyStart = -1;
        int reqUid = -1;
        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("--checkin".equals(arg)) {
                    useCheckinFormat = true;
                    isRealCheckin = true;
                } else if ("--history".equals(arg)) {
                    flags |= BatteryStats.DUMP_HISTORY_ONLY;
                } else if ("--history-start".equals(arg)) {
                    flags |= BatteryStats.DUMP_HISTORY_ONLY;
                    i++;
                    if (i >= args.length) {
                        pw.println("Missing time argument for --history-since");
                        dumpHelp(pw);
                        return;
                    }
                    historyStart = ParseUtils.parseLong(args[i], 0);
                    writeData = true;
                } else if ("--history-create-events".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Missing events argument for --history-create-events");
                        dumpHelp(pw);
                        return;
                    }
                    final long events = ParseUtils.parseLong(args[i], 0);
                    synchronized (mStats) {
                        mStats.createFakeHistoryEvents(events);
                        pw.println("Battery history create events started.");
                        noOutput = true;
                    }
                } else if ("-c".equals(arg)) {
                    useCheckinFormat = true;
                    flags |= BatteryStats.DUMP_INCLUDE_HISTORY;
                } else if ("--proto".equals(arg)) {
                    toProto = true;
                } else if ("--charged".equals(arg)) {
                    flags |= BatteryStats.DUMP_CHARGED_ONLY;
                } else if ("--daily".equals(arg)) {
                    flags |= BatteryStats.DUMP_DAILY_ONLY;
                } else if ("--reset".equals(arg)) {
                    synchronized (mStats) {
                        mStats.resetAllStatsCmdLocked();
                        pw.println("Battery stats reset.");
                        noOutput = true;
                    }
                    mWorker.scheduleSync("dump", BatteryExternalStatsWorker.UPDATE_ALL);
                } else if ("--write".equals(arg)) {
                    syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);
                    synchronized (mStats) {
                        mStats.writeSyncLocked();
                        pw.println("Battery stats written.");
                        noOutput = true;
                    }
                } else if ("--new-daily".equals(arg)) {
                    synchronized (mStats) {
                        mStats.recordDailyStatsLocked();
                        pw.println("New daily stats written.");
                        noOutput = true;
                    }
                } else if ("--read-daily".equals(arg)) {
                    synchronized (mStats) {
                        mStats.readDailyStatsLocked();
                        pw.println("Last daily stats read.");
                        noOutput = true;
                    }
                } else if ("--enable".equals(arg) || "enable".equals(arg)) {
                    i = doEnableOrDisable(pw, i, args, true);
                    if (i < 0) {
                        return;
                    }
                    pw.println("Enabled: " + args[i]);
                    return;
                } else if ("--disable".equals(arg) || "disable".equals(arg)) {
                    i = doEnableOrDisable(pw, i, args, false);
                    if (i < 0) {
                        return;
                    }
                    pw.println("Disabled: " + args[i]);
                    return;
                } else if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("--settings".equals(arg)) {
                    dumpSettings(pw);
                    return;
                } else if ("--cpu".equals(arg)) {
                    dumpCpuStats(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    flags |= BatteryStats.DUMP_VERBOSE;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                    return;
                } else {
                    // Not an option, last argument must be a package name.
                    try {
                        reqUid = mContext.getPackageManager().getPackageUidAsUser(arg,
                                UserHandle.getCallingUserId());
                    } catch (PackageManager.NameNotFoundException e) {
                        pw.println("Unknown package: " + arg);
                        dumpHelp(pw);
                        return;
                    }
                }
            }
        }
        if (noOutput) {
            return;
        }

        long ident = Binder.clearCallingIdentity();
        try {
            if (BatteryStatsHelper.checkWifiOnly(mContext)) {
                flags |= BatteryStats.DUMP_DEVICE_WIFI_ONLY;
            }
            // Fetch data from external sources and update the BatteryStatsImpl object with them.
            syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (reqUid >= 0) {
            // By default, if the caller is only interested in a specific package, then
            // we only dump the aggregated data since charged.
            if ((flags&(BatteryStats.DUMP_HISTORY_ONLY|BatteryStats.DUMP_CHARGED_ONLY)) == 0) {
                flags |= BatteryStats.DUMP_CHARGED_ONLY;
                // Also if they are doing -c, we don't want history.
                flags &= ~BatteryStats.DUMP_INCLUDE_HISTORY;
            }
        }

        if (toProto) {
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                    PackageManager.MATCH_ANY_USER | PackageManager.MATCH_ALL);
            if (isRealCheckin) {
                // For a real checkin, first we want to prefer to use the last complete checkin
                // file if there is one.
                synchronized (mStats.mCheckinFile) {
                    if (mStats.mCheckinFile.exists()) {
                        try {
                            byte[] raw = mStats.mCheckinFile.readFully();
                            if (raw != null) {
                                Parcel in = Parcel.obtain();
                                in.unmarshall(raw, 0, raw.length);
                                in.setDataPosition(0);
                                BatteryStatsImpl checkinStats = new BatteryStatsImpl(
                                        null, mStats.mHandler, null, null,
                                        mUserManagerUserInfoProvider);
                                checkinStats.readSummaryFromParcel(in);
                                in.recycle();
                                checkinStats.dumpProtoLocked(
                                        mContext, fd, apps, flags, historyStart);
                                mStats.mCheckinFile.delete();
                                return;
                            }
                        } catch (IOException | ParcelFormatException e) {
                            Slog.w(TAG, "Failure reading checkin file "
                                    + mStats.mCheckinFile.getBaseFile(), e);
                        }
                    }
                }
            }
            if (DBG) Slog.d(TAG, "begin dumpProtoLocked from UID " + Binder.getCallingUid());
            synchronized (mStats) {
                mStats.dumpProtoLocked(mContext, fd, apps, flags, historyStart);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
            if (DBG) Slog.d(TAG, "end dumpProtoLocked");
        } else if (useCheckinFormat) {
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                    PackageManager.MATCH_ANY_USER | PackageManager.MATCH_ALL);
            if (isRealCheckin) {
                // For a real checkin, first we want to prefer to use the last complete checkin
                // file if there is one.
                synchronized (mStats.mCheckinFile) {
                    if (mStats.mCheckinFile.exists()) {
                        try {
                            byte[] raw = mStats.mCheckinFile.readFully();
                            if (raw != null) {
                                Parcel in = Parcel.obtain();
                                in.unmarshall(raw, 0, raw.length);
                                in.setDataPosition(0);
                                BatteryStatsImpl checkinStats = new BatteryStatsImpl(
                                        null, mStats.mHandler, null, null,
                                        mUserManagerUserInfoProvider);
                                checkinStats.readSummaryFromParcel(in);
                                in.recycle();
                                checkinStats.dumpCheckinLocked(mContext, pw, apps, flags,
                                        historyStart);
                                mStats.mCheckinFile.delete();
                                return;
                            }
                        } catch (IOException | ParcelFormatException e) {
                            Slog.w(TAG, "Failure reading checkin file "
                                    + mStats.mCheckinFile.getBaseFile(), e);
                        }
                    }
                }
            }
            if (DBG) Slog.d(TAG, "begin dumpCheckinLocked from UID " + Binder.getCallingUid());
            synchronized (mStats) {
                mStats.dumpCheckinLocked(mContext, pw, apps, flags, historyStart);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
            if (DBG) Slog.d(TAG, "end dumpCheckinLocked");
        } else {
            if (DBG) Slog.d(TAG, "begin dumpLocked from UID " + Binder.getCallingUid());
            synchronized (mStats) {
                mStats.dumpLocked(mContext, pw, flags, reqUid, historyStart);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
            if (DBG) Slog.d(TAG, "end dumpLocked");
        }
    }

    /**
     * Gets a snapshot of cellular stats
     * @hide
     */
    public CellularBatteryStats getCellularBatteryStats() {
        synchronized (mStats) {
            return mStats.getCellularBatteryStats();
        }
    }

    /**
     * Gets a snapshot of Wifi stats
     * @hide
     */
    public WifiBatteryStats getWifiBatteryStats() {
        synchronized (mStats) {
            return mStats.getWifiBatteryStats();
        }
    }

    /**
     * Gets a snapshot of Gps stats
     * @hide
     */
    public GpsBatteryStats getGpsBatteryStats() {
        synchronized (mStats) {
            return mStats.getGpsBatteryStats();
        }
    }

    /**
     * Gets a snapshot of the system health for a particular uid.
     */
    @Override
    public HealthStatsParceler takeUidSnapshot(int requestUid) {
        if (requestUid != Binder.getCallingUid()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.BATTERY_STATS, null);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            if (shouldCollectExternalStats()) {
                syncStats("get-health-stats-for-uids", BatteryExternalStatsWorker.UPDATE_ALL);
            }
            synchronized (mStats) {
                return getHealthStatsForUidLocked(requestUid);
            }
        } catch (Exception ex) {
            Slog.w(TAG, "Crashed while writing for takeUidSnapshot(" + requestUid + ")", ex);
            throw ex;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Gets a snapshot of the system health for a number of uids.
     */
    @Override
    public HealthStatsParceler[] takeUidSnapshots(int[] requestUids) {
        if (!onlyCaller(requestUids)) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.BATTERY_STATS, null);
        }
        long ident = Binder.clearCallingIdentity();
        int i=-1;
        try {
            if (shouldCollectExternalStats()) {
                syncStats("get-health-stats-for-uids", BatteryExternalStatsWorker.UPDATE_ALL);
            }
            synchronized (mStats) {
                final int N = requestUids.length;
                final HealthStatsParceler[] results = new HealthStatsParceler[N];
                for (i=0; i<N; i++) {
                    results[i] = getHealthStatsForUidLocked(requestUids[i]);
                }
                return results;
            }
        } catch (Exception ex) {
            if (DBG) Slog.d(TAG, "Crashed while writing for takeUidSnapshots("
                    + Arrays.toString(requestUids) + ") i=" + i, ex);
            throw ex;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean shouldCollectExternalStats() {
        return (SystemClock.elapsedRealtime() - mWorker.getLastCollectionTimeStamp())
                > mStats.getExternalStatsCollectionRateLimitMs();
    }

    /**
     * Returns whether the Binder.getCallingUid is the only thing in requestUids.
     */
    private static boolean onlyCaller(int[] requestUids) {
        final int caller = Binder.getCallingUid();
        final int N = requestUids.length;
        for (int i=0; i<N; i++) {
            if (requestUids[i] != caller) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets a HealthStatsParceler for the given uid. You should probably call
     * updateExternalStatsSync first.
     */
    HealthStatsParceler getHealthStatsForUidLocked(int requestUid) {
        final HealthStatsBatteryStatsWriter writer = new HealthStatsBatteryStatsWriter();
        final HealthStatsWriter uidWriter = new HealthStatsWriter(UidHealthStats.CONSTANTS);
        final BatteryStats.Uid uid = mStats.getUidStats().get(requestUid);
        if (uid != null) {
            writer.writeUid(uidWriter, mStats, uid);
        }
        return new HealthStatsParceler(uidWriter);
    }

    /**
     * Delay for sending ACTION_CHARGING after device is plugged in.
     *
     * @hide
     */
    public boolean setChargingStateUpdateDelayMillis(int delayMillis) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.POWER_SAVER, null);
        final long ident = Binder.clearCallingIdentity();

        try {
            final ContentResolver contentResolver = mContext.getContentResolver();
            return Settings.Global.putLong(contentResolver,
                    Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY,
                    delayMillis);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

}
