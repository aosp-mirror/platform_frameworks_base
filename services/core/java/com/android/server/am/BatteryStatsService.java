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
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.wifi.IWifiManager;
import android.net.wifi.WifiActivityEnergyInfo;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFormatException;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.IntArray;
import android.util.Slog;

import android.util.TimeUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsHelper;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;
import com.android.server.FgThread;
import com.android.server.LocalServices;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.
 */
public final class BatteryStatsService extends IBatteryStats.Stub
        implements PowerManagerInternal.LowPowerModeListener {
    static final String TAG = "BatteryStatsService";

    static IBatteryStats sService;
    final BatteryStatsImpl mStats;
    final BatteryStatsHandler mHandler;
    Context mContext;
    PowerManagerInternal mPowerManagerInternal;

    final int UPDATE_CPU = 0x01;
    final int UPDATE_WIFI = 0x02;
    final int UPDATE_RADIO = 0x04;
    final int UPDATE_BT = 0x08;
    final int UPDATE_ALL = UPDATE_CPU | UPDATE_WIFI | UPDATE_RADIO | UPDATE_BT;

    class BatteryStatsHandler extends Handler implements BatteryStatsImpl.ExternalStatsSync {
        public static final int MSG_SYNC_EXTERNAL_STATS = 1;
        public static final int MSG_WRITE_TO_DISK = 2;
        private int mUpdateFlags = 0;
        private IntArray mUidsToRemove = new IntArray();

        public BatteryStatsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SYNC_EXTERNAL_STATS:
                    final int updateFlags;
                    synchronized (this) {
                        removeMessages(MSG_SYNC_EXTERNAL_STATS);
                        updateFlags = mUpdateFlags;
                        mUpdateFlags = 0;
                    }
                    updateExternalStats((String)msg.obj, updateFlags);

                    // other parts of the system could be calling into us
                    // from mStats in order to report of changes. We must grab the mStats
                    // lock before grabbing our own or we'll end up in a deadlock.
                    synchronized (mStats) {
                        synchronized (this) {
                            final int numUidsToRemove = mUidsToRemove.size();
                            for (int i = 0; i < numUidsToRemove; i++) {
                                mStats.removeIsolatedUidLocked(mUidsToRemove.get(i));
                            }
                        }
                        mUidsToRemove.clear();
                    }
                    break;

                case MSG_WRITE_TO_DISK:
                    updateExternalStats("write", UPDATE_ALL);
                    synchronized (mStats) {
                        mStats.writeAsyncLocked();
                    }
                    break;
            }
        }

        @Override
        public void scheduleSync(String reason) {
            synchronized (this) {
                scheduleSyncLocked(reason, UPDATE_ALL);
            }
        }

        @Override
        public void scheduleWifiSync(String reason) {
            synchronized (this) {
                scheduleSyncLocked(reason, UPDATE_WIFI);
            }
        }

        @Override
        public void scheduleCpuSyncDueToRemovedUid(int uid) {
            synchronized (this) {
                scheduleSyncLocked("remove-uid", UPDATE_CPU);
                mUidsToRemove.add(uid);
            }
        }

        private void scheduleSyncLocked(String reason, int updateFlags) {
            if (mUpdateFlags == 0) {
                sendMessage(Message.obtain(this, MSG_SYNC_EXTERNAL_STATS, reason));
            }
            mUpdateFlags |= updateFlags;
        }
    }

    BatteryStatsService(File systemDir, Handler handler) {
        // Our handler here will be accessing the disk, use a different thread than
        // what the ActivityManagerService gave us (no I/O on that one!).
        mHandler = new BatteryStatsHandler(FgThread.getHandler().getLooper());

        // BatteryStatsImpl expects the ActivityManagerService handler, so pass that one through.
        mStats = new BatteryStatsImpl(systemDir, handler, mHandler);
    }
    
    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(BatteryStats.SERVICE_NAME, asBinder());
        mStats.setNumSpeedSteps(new PowerProfile(mContext).getNumSpeedSteps());
        mStats.setRadioScanningTimeout(mContext.getResources().getInteger(
                com.android.internal.R.integer.config_radioScanningTimeout)
                * 1000L);
        mStats.setPowerProfile(new PowerProfile(context));
    }

    /**
     * At the time when the constructor runs, the power manager has not yet been
     * initialized.  So we initialize the low power observer later.
     */
    public void initPowerManagement() {
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mPowerManagerInternal.registerLowPowerModeObserver(this);
        mStats.notePowerSaveMode(mPowerManagerInternal.getLowPowerModeEnabled());
        (new WakeupReasonThread()).start();
    }

    public void shutdown() {
        Slog.w("BatteryStats", "Writing battery stats before shutdown...");

        updateExternalStats("shutdown", UPDATE_ALL);
        synchronized (mStats) {
            mStats.shutdownLocked();
        }
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
    public void onLowPowerModeChanged(boolean enabled) {
        synchronized (mStats) {
            mStats.notePowerSaveMode(enabled);
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
        mHandler.sendEmptyMessage(BatteryStatsHandler.MSG_WRITE_TO_DISK);
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
        }
    }

    void noteProcessCrash(String name, int uid) {
        synchronized (mStats) {
            mStats.noteProcessCrashLocked(name, uid);
        }
    }

    void noteProcessAnr(String name, int uid) {
        synchronized (mStats) {
            mStats.noteProcessAnrLocked(name, uid);
        }
    }

    void noteProcessState(String name, int uid, int state) {
        synchronized (mStats) {
            mStats.noteProcessStateLocked(name, uid, state);
        }
    }

    void noteProcessFinish(String name, int uid) {
        synchronized (mStats) {
            mStats.noteProcessFinishLocked(name, uid);
        }
    }

    // Public interface...

    public byte[] getStatistics() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        //Slog.i("foo", "SENDING BATTERY INFO:");
        //mStats.dumpLocked(new LogPrinter(Log.INFO, "foo", Log.LOG_ID_SYSTEM));
        Parcel out = Parcel.obtain();
        updateExternalStats("get-stats", UPDATE_ALL);
        synchronized (mStats) {
            mStats.writeToParcel(out, 0);
        }
        byte[] data = out.marshall();
        out.recycle();
        return data;
    }

    public ParcelFileDescriptor getStatisticsStream() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        //Slog.i("foo", "SENDING BATTERY INFO:");
        //mStats.dumpLocked(new LogPrinter(Log.INFO, "foo", Log.LOG_ID_SYSTEM));
        Parcel out = Parcel.obtain();
        updateExternalStats("get-stats", UPDATE_ALL);
        synchronized (mStats) {
            mStats.writeToParcel(out, 0);
        }
        byte[] data = out.marshall();
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
        }
    }

    public void noteSyncFinish(String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteSyncFinishLocked(name, uid);
        }
    }

    public void noteJobStart(String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteJobStartLocked(name, uid);
        }
    }

    public void noteJobFinish(String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteJobFinishLocked(name, uid);
        }
    }

    public void noteAlarmStart(String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAlarmStartLocked(name, uid);
        }
    }

    public void noteAlarmFinish(String name, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAlarmFinishLocked(name, uid);
        }
    }

    public void noteStartWakelock(int uid, int pid, String name, String historyName, int type,
            boolean unimportantForLogging) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartWakeLocked(uid, pid, name, historyName, type, unimportantForLogging,
                    SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
        }
    }

    public void noteStopWakelock(int uid, int pid, String name, String historyName, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopWakeLocked(uid, pid, name, historyName, type,
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

    public void noteStartSensor(int uid, int sensor) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartSensorLocked(uid, sensor);
        }
    }
    
    public void noteStopSensor(int uid, int sensor) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopSensorLocked(uid, sensor);
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

    public void noteStartGps(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartGpsLocked(uid);
        }
    }
    
    public void noteStopGps(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopGpsLocked(uid);
        }
    }
        
    public void noteScreenState(int state) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteScreenStateLocked(state);
        }
    }
    
    public void noteScreenBrightness(int brightness) {
        enforceCallingPermission();
        synchronized (mStats) {
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

    public void noteMobileRadioPowerState(int powerState, long timestampNs) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteMobileRadioPowerState(powerState, timestampNs);
        }
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
    
    public void notePhoneDataConnectionState(int dataType, boolean hasData) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneDataConnectionStateLocked(dataType, hasData);
        }
    }

    public void notePhoneState(int state) {
        enforceCallingPermission();
        int simState = TelephonyManager.getDefault().getSimState();
        synchronized (mStats) {
            mStats.notePhoneStateLocked(state, simState);
        }
    }

    public void noteWifiOn() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiOnLocked();
        }
    }
    
    public void noteWifiOff() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiOffLocked();
        }
    }

    public void noteStartAudio(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAudioOnLocked(uid);
        }
    }

    public void noteStopAudio(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAudioOffLocked(uid);
        }
    }

    public void noteStartVideo(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteVideoOnLocked(uid);
        }
    }

    public void noteStopVideo(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteVideoOffLocked(uid);
        }
    }

    public void noteResetAudio() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetAudioLocked();
        }
    }

    public void noteResetVideo() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetVideoLocked();
        }
    }

    public void noteFlashlightOn(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFlashlightOnLocked(uid);
        }
    }

    public void noteFlashlightOff(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFlashlightOffLocked(uid);
        }
    }

    public void noteStartCamera(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteCameraOnLocked(uid);
        }
    }

    public void noteStopCamera(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteCameraOffLocked(uid);
        }
    }

    public void noteResetCamera() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetCameraLocked();
        }
    }

    public void noteResetFlashlight() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteResetFlashlightLocked();
        }
    }

    @Override
    public void noteWifiRadioPowerState(int powerState, long tsNanos) {
        enforceCallingPermission();

        // There was a change in WiFi power state.
        // Collect data now for the past activity.
        synchronized (mStats) {
            if (mStats.isOnBattery()) {
                final String type = (powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH ||
                        powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM) ? "active"
                        : "inactive";
                mHandler.scheduleWifiSync("wifi-data: " + type);
            }
            mStats.noteWifiRadioPowerState(powerState, tsNanos);
        }
    }

    public void noteWifiRunning(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiRunningLocked(ws);
        }
    }

    public void noteWifiRunningChanged(WorkSource oldWs, WorkSource newWs) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiRunningChangedLocked(oldWs, newWs);
        }
    }

    public void noteWifiStopped(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiStoppedLocked(ws);
        }
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

    public void noteWifiMulticastEnabledFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiMulticastEnabledFromSourceLocked(ws);
        }
    }

    @Override
    public void noteWifiMulticastDisabledFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiMulticastDisabledFromSourceLocked(ws);
        }
    }

    @Override
    public void noteNetworkInterfaceType(String iface, int networkType) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteNetworkInterfaceTypeLocked(iface, networkType);
        }
    }

    @Override
    public void noteNetworkStatsEnabled() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteNetworkStatsEnabledLocked();
        }
    }

    @Override
    public void noteDeviceIdleMode(boolean enabled, String activeReason, int activeUid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteDeviceIdleModeLocked(enabled, activeReason, activeUid);
        }
    }

    public void notePackageInstalled(String pkgName, int versionCode) {
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

    public boolean isOnBattery() {
        return mStats.isOnBattery();
    }

    @Override
    public void setBatteryState(final int status, final int health, final int plugType,
                                final int level, final int temp, final int volt) {
        enforceCallingPermission();

        // BatteryService calls us here and we may update external state. It would be wrong
        // to block such a low level service like BatteryService on external stats like WiFi.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mStats) {
                    final boolean onBattery = plugType == BatteryStatsImpl.BATTERY_PLUGGED_NONE;
                    if (mStats.isOnBattery() == onBattery) {
                        // The battery state has not changed, so we don't need to sync external
                        // stats immediately.
                        mStats.setBatteryStateLocked(status, health, plugType, level, temp, volt);
                        return;
                    }
                }

                // Sync external stats first as the battery has changed states. If we don't sync
                // immediately here, we may not collect the relevant data later.
                updateExternalStats("battery-state", UPDATE_ALL);
                synchronized (mStats) {
                    mStats.setBatteryStateLocked(status, health, plugType, level, temp, volt);
                }
            }
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
        pw.println("  [--checkin] [--history] [--history-start] [--charged] [-c]");
        pw.println("  [--daily] [--reset] [--write] [--new-daily] [--read-daily] [-h] [<package.name>]");
        pw.println("  --checkin: format output for a checkin report.");
        pw.println("  --history: show only history data.");
        pw.println("  --history-start <num>: show only history data starting at given time offset.");
        pw.println("  --charged: only output data since last charged.");
        pw.println("  --daily: only output full daily data.");
        pw.println("  --reset: reset the stats, clearing all current data.");
        pw.println("  --write: force write current collected stats to disk.");
        pw.println("  --new-daily: immediately create and write new daily stats record.");
        pw.println("  --read-daily: read-load last written daily stats.");
        pw.println("  <package.name>: optional name of package to filter output by.");
        pw.println("  -h: print this help text.");
        pw.println("Battery stats (batterystats) commands:");
        pw.println("  enable|disable <option>");
        pw.println("    Enable or disable a running option.  Option state is not saved across boots.");
        pw.println("    Options are:");
        pw.println("      full-history: include additional detailed events in battery history:");
        pw.println("          wake_lock_in, alarms and proc events");
        pw.println("      no-auto-reset: don't automatically reset stats when unplugged");
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
        } else {
            pw.println("Unknown enable/disable option: " + args[i]);
            dumpHelp(pw);
            return -1;
        }
        return i;
    }


    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump BatteryStats from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        int flags = 0;
        boolean useCheckinFormat = false;
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
                    historyStart = Long.parseLong(args[i]);
                    writeData = true;
                } else if ("-c".equals(arg)) {
                    useCheckinFormat = true;
                    flags |= BatteryStats.DUMP_INCLUDE_HISTORY;
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
                    updateExternalStats("dump", UPDATE_ALL);
                } else if ("--write".equals(arg)) {
                    updateExternalStats("dump", UPDATE_ALL);
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
                } else if ("-a".equals(arg)) {
                    flags |= BatteryStats.DUMP_VERBOSE;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                    return;
                } else {
                    // Not an option, last argument must be a package name.
                    try {
                        reqUid = mContext.getPackageManager().getPackageUid(arg,
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
            updateExternalStats("dump", UPDATE_ALL);
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

        if (useCheckinFormat) {
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(0);
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
                                        null, mStats.mHandler, null);
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
            synchronized (mStats) {
                mStats.dumpCheckinLocked(mContext, pw, apps, flags, historyStart);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
        } else {
            synchronized (mStats) {
                mStats.dumpLocked(mContext, pw, flags, reqUid, historyStart);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
        }
    }

    // Objects for extracting data from external sources.
    private final Object mExternalStatsLock = new Object();

    @GuardedBy("mExternalStatsLock")
    private IWifiManager mWifiManager;

    // WiFi keeps an accumulated total of stats, unlike Bluetooth.
    // Keep the last WiFi stats so we can compute a delta.
    @GuardedBy("mExternalStatsLock")
    private WifiActivityEnergyInfo mLastInfo = new WifiActivityEnergyInfo(0, 0, 0, 0, 0, 0);

    @GuardedBy("mExternalStatsLock")
    private WifiActivityEnergyInfo pullWifiEnergyInfoLocked() {
        if (mWifiManager == null) {
            mWifiManager = IWifiManager.Stub.asInterface(
                    ServiceManager.getService(Context.WIFI_SERVICE));
            if (mWifiManager == null) {
                return null;
            }
        }

        try {
            // We read the data even if we are not on battery. This is so that we keep the
            // correct delta from when we should start reading (aka when we are on battery).
            WifiActivityEnergyInfo info = mWifiManager.reportActivityInfo();
            if (info != null && info.isValid()) {
                if (info.mControllerEnergyUsed < 0 || info.mControllerIdleTimeMs < 0 ||
                        info.mControllerRxTimeMs < 0 || info.mControllerTxTimeMs < 0) {
                    Slog.wtf(TAG, "Reported WiFi energy data is invalid: " + info);
                    return null;
                }

                final long timePeriodMs = info.mTimestamp - mLastInfo.mTimestamp;
                final long lastIdleMs = mLastInfo.mControllerIdleTimeMs;
                final long lastTxMs = mLastInfo.mControllerTxTimeMs;
                final long lastRxMs = mLastInfo.mControllerRxTimeMs;
                final long lastEnergy = mLastInfo.mControllerEnergyUsed;

                // We will modify the last info object to be the delta, and store the new
                // WifiActivityEnergyInfo object as our last one.
                final WifiActivityEnergyInfo result = mLastInfo;
                result.mTimestamp = info.getTimeStamp();
                result.mStackState = info.getStackState();

                // These times seem to be the most reliable.
                result.mControllerTxTimeMs = info.mControllerTxTimeMs - lastTxMs;
                result.mControllerRxTimeMs = info.mControllerRxTimeMs - lastRxMs;

                // WiFi calculates the idle time as a difference from the on time and the various
                // Rx + Tx times. There seems to be some missing time there because this sometimes
                // becomes negative. Just cap it at 0 and move on.
                // b/21613534
                result.mControllerIdleTimeMs = Math.max(0, info.mControllerIdleTimeMs - lastIdleMs);
                result.mControllerEnergyUsed =
                        Math.max(0, info.mControllerEnergyUsed - lastEnergy);

                if (result.mControllerTxTimeMs < 0 ||
                        result.mControllerRxTimeMs < 0) {
                    // The stats were reset by the WiFi system (which is why our delta is negative).
                    // Returns the unaltered stats.
                    result.mControllerEnergyUsed = info.mControllerEnergyUsed;
                    result.mControllerRxTimeMs = info.mControllerRxTimeMs;
                    result.mControllerTxTimeMs = info.mControllerTxTimeMs;
                    result.mControllerIdleTimeMs = info.mControllerIdleTimeMs;

                    Slog.v(TAG, "WiFi energy data was reset, new WiFi energy data is " + result);
                }

                // There is some accuracy error in reports so allow 30 milliseconds of error.
                final long SAMPLE_ERROR_MILLIS = 30;
                final long totalTimeMs = result.mControllerIdleTimeMs + result.mControllerRxTimeMs +
                        result.mControllerTxTimeMs;
                if (totalTimeMs > timePeriodMs + SAMPLE_ERROR_MILLIS) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Total time ");
                    TimeUtils.formatDuration(totalTimeMs, sb);
                    sb.append(" is longer than sample period ");
                    TimeUtils.formatDuration(timePeriodMs, sb);
                    sb.append(".\n");
                    sb.append("Previous WiFi snapshot: ").append("idle=");
                    TimeUtils.formatDuration(lastIdleMs, sb);
                    sb.append(" rx=");
                    TimeUtils.formatDuration(lastRxMs, sb);
                    sb.append(" tx=");
                    TimeUtils.formatDuration(lastTxMs, sb);
                    sb.append(" e=").append(lastEnergy);
                    sb.append("\n");
                    sb.append("Current WiFi snapshot: ").append("idle=");
                    TimeUtils.formatDuration(info.mControllerIdleTimeMs, sb);
                    sb.append(" rx=");
                    TimeUtils.formatDuration(info.mControllerRxTimeMs, sb);
                    sb.append(" tx=");
                    TimeUtils.formatDuration(info.mControllerTxTimeMs, sb);
                    sb.append(" e=").append(info.mControllerEnergyUsed);
                    Slog.wtf(TAG, sb.toString());
                }

                mLastInfo = info;
                return result;
            }
        } catch (RemoteException e) {
            // Nothing to report, WiFi is dead.
        }
        return null;
    }

    @GuardedBy("mExternalStatsLock")
    private BluetoothActivityEnergyInfo pullBluetoothEnergyInfoLocked() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            BluetoothActivityEnergyInfo info = adapter.getControllerActivityEnergyInfo(
                    BluetoothAdapter.ACTIVITY_ENERGY_INFO_REFRESHED);
            if (info != null && info.isValid()) {
                if (info.getControllerEnergyUsed() < 0 || info.getControllerIdleTimeMillis() < 0 ||
                        info.getControllerRxTimeMillis() < 0 || info.getControllerTxTimeMillis() < 0) {
                    Slog.wtf(TAG, "Bluetooth energy data is invalid: " + info);
                }
                return info;
            }
        }
        return null;
    }

    /**
     * Fetches data from external sources (WiFi controller, bluetooth chipset) and updates
     * batterystats with that information.
     *
     * We first grab a lock specific to this method, then once all the data has been collected,
     * we grab the mStats lock and update the data.
     *
     * @param reason The reason why this collection was requested. Useful for debugging.
     * @param updateFlags Which external stats to update. Can be a combination of
     *                    {@link #UPDATE_CPU}, {@link #UPDATE_RADIO}, {@link #UPDATE_WIFI},
     *                    and {@link #UPDATE_BT}.
     */
    void updateExternalStats(final String reason, final int updateFlags) {
        synchronized (mExternalStatsLock) {
            if (mContext == null) {
                // We haven't started yet (which means the BatteryStatsImpl object has
                // no power profile. Don't consume data we can't compute yet.
                return;
            }

            if (BatteryStatsImpl.DEBUG_ENERGY) {
                Slog.d(TAG, "Updating external stats: reason=" + reason);
            }

            WifiActivityEnergyInfo wifiEnergyInfo = null;
            if ((updateFlags & UPDATE_WIFI) != 0) {
                wifiEnergyInfo = pullWifiEnergyInfoLocked();
            }

            BluetoothActivityEnergyInfo bluetoothEnergyInfo = null;
            if ((updateFlags & UPDATE_BT) != 0) {
                // We only pull bluetooth stats when we have to, as we are not distributing its
                // use amongst apps and the sampling frequency does not matter.
                bluetoothEnergyInfo = pullBluetoothEnergyInfoLocked();
            }

            synchronized (mStats) {
                final long elapsedRealtime = SystemClock.elapsedRealtime();
                final long uptime = SystemClock.uptimeMillis();
                if (mStats.mRecordAllHistory) {
                    mStats.addHistoryEventLocked(elapsedRealtime, uptime,
                            BatteryStats.HistoryItem.EVENT_COLLECT_EXTERNAL_STATS, reason, 0);
                }

                if ((updateFlags & UPDATE_CPU) != 0) {
                    mStats.updateCpuTimeLocked();
                    mStats.updateKernelWakelocksLocked();
                }

                if ((updateFlags & UPDATE_RADIO) != 0) {
                    mStats.updateMobileRadioStateLocked(elapsedRealtime);
                }

                if ((updateFlags & UPDATE_WIFI) != 0) {
                    mStats.updateWifiStateLocked(wifiEnergyInfo);
                }

                if ((updateFlags & UPDATE_BT) != 0) {
                    mStats.updateBluetoothStateLocked(bluetoothEnergyInfo);
                }
            }
        }
    }
}
