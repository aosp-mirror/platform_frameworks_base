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

import android.annotation.Nullable;
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
import android.os.Parcelable;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SynchronousResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.health.HealthStatsParceler;
import android.os.health.HealthStatsWriter;
import android.os.health.UidHealthStats;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
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
import com.android.server.LocalServices;
import com.android.server.ServiceThread;

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
import java.util.concurrent.TimeoutException;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.
 */
public final class BatteryStatsService extends IBatteryStats.Stub
        implements PowerManagerInternal.LowPowerModeListener,
        BatteryStatsImpl.PlatformIdleStateCallback {
    static final String TAG = "BatteryStatsService";

    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;

    // There is some accuracy error in wifi reports so allow some slop in the results.
    private static final long MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS = 750;

    private static IBatteryStats sService;

    final BatteryStatsImpl mStats;
    private final BatteryStatsHandler mHandler;
    private Context mContext;
    private IWifiManager mWifiManager;
    private TelephonyManager mTelephony;

    // Lock acquired when extracting data from external sources.
    private final Object mExternalStatsLock = new Object();

    // WiFi keeps an accumulated total of stats, unlike Bluetooth.
    // Keep the last WiFi stats so we can compute a delta.
    @GuardedBy("mExternalStatsLock")
    private WifiActivityEnergyInfo mLastInfo =
            new WifiActivityEnergyInfo(0, 0, 0, new long[]{0}, 0, 0, 0);

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
                    updateExternalStatsSync((String)msg.obj, updateFlags);

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
                    updateExternalStatsSync("write", UPDATE_ALL);
                    synchronized (mStats) {
                        mStats.writeAsyncLocked();
                    }
                    break;
            }
        }

        @Override
        public void scheduleSync(String reason, int updateFlags) {
            synchronized (this) {
                scheduleSyncLocked(reason, updateFlags);
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

    private native int getPlatformLowPowerStats(ByteBuffer outBuffer);
    private CharsetDecoder mDecoderStat = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("?");
    private ByteBuffer mUtf8BufferStat = ByteBuffer.allocateDirect(MAX_LOW_POWER_STATS_SIZE);
    private CharBuffer mUtf16BufferStat = CharBuffer.allocate(MAX_LOW_POWER_STATS_SIZE);
    private static final int MAX_LOW_POWER_STATS_SIZE = 512;

    @Override
    public String getPlatformLowPowerStats() {
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
    }

    BatteryStatsService(File systemDir, Handler handler) {
        // Our handler here will be accessing the disk, use a different thread than
        // what the ActivityManagerService gave us (no I/O on that one!).
        final ServiceThread thread = new ServiceThread("batterystats-sync",
                Process.THREAD_PRIORITY_DEFAULT, true);
        thread.start();
        mHandler = new BatteryStatsHandler(thread.getLooper());

        // BatteryStatsImpl expects the ActivityManagerService handler, so pass that one through.
        mStats = new BatteryStatsImpl(systemDir, handler, mHandler, this);
    }

    public void publish(Context context) {
        mContext = context;
        mStats.setRadioScanningTimeout(mContext.getResources().getInteger(
                com.android.internal.R.integer.config_radioScanningTimeout)
                * 1000L);
        mStats.setPowerProfile(new PowerProfile(context));
        ServiceManager.addService(BatteryStats.SERVICE_NAME, asBinder());
    }

    /**
     * At the time when the constructor runs, the power manager has not yet been
     * initialized.  So we initialize the low power observer later.
     */
    public void initPowerManagement() {
        final PowerManagerInternal powerMgr = LocalServices.getService(PowerManagerInternal.class);
        powerMgr.registerLowPowerModeObserver(this);
        mStats.notePowerSaveMode(powerMgr.getLowPowerModeEnabled());
        (new WakeupReasonThread()).start();
    }

    public void shutdown() {
        Slog.w("BatteryStats", "Writing battery stats before shutdown...");

        updateExternalStatsSync("shutdown", BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
        synchronized (mStats) {
            mStats.shutdownLocked();
        }

        // Shutdown the thread we made.
        mHandler.getLooper().quit();
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

    void noteProcessFinish(String name, int uid) {
        synchronized (mStats) {
            mStats.noteProcessFinishLocked(name, uid);
        }
    }

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
        updateExternalStatsSync("get-stats", BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
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
        updateExternalStatsSync("get-stats", BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
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

    public void noteLongPartialWakelockStart(String name, String historyName, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteLongPartialWakelockStart(name, historyName, uid);
        }
    }

    public void noteLongPartialWakelockFinish(String name, String historyName, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteLongPartialWakelockFinish(name, historyName, uid);
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

    public void noteMobileRadioPowerState(int powerState, long timestampNs, int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteMobileRadioPowerState(powerState, timestampNs, uid);
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
    public void noteWifiRadioPowerState(int powerState, long tsNanos, int uid) {
        enforceCallingPermission();

        // There was a change in WiFi power state.
        // Collect data now for the past activity.
        synchronized (mStats) {
            if (mStats.isOnBattery()) {
                final String type = (powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH ||
                        powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM) ? "active"
                        : "inactive";
                mHandler.scheduleSync("wifi-data: " + type,
                        BatteryStatsImpl.ExternalStatsSync.UPDATE_WIFI);
            }
            mStats.noteWifiRadioPowerState(powerState, tsNanos, uid);
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
    public void noteDeviceIdleMode(int mode, String activeReason, int activeUid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteDeviceIdleModeLocked(mode, activeReason, activeUid);
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

    @Override
    public void noteBleScanStarted(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteBluetoothScanStartedFromSourceLocked(ws);
        }
    }

    @Override
    public void noteBleScanStopped(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteBluetoothScanStoppedFromSourceLocked(ws);
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
    public void noteWifiControllerActivity(WifiActivityEnergyInfo info) {
        enforceCallingPermission();

        if (info == null || !info.isValid()) {
            Slog.e(TAG, "invalid wifi data given: " + info);
            return;
        }

        synchronized (mStats) {
            mStats.updateWifiStateLocked(info);
        }
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

        synchronized (mStats) {
            mStats.updateMobileRadioStateLocked(SystemClock.elapsedRealtime(), info);
        }
    }

    public boolean isOnBattery() {
        return mStats.isOnBattery();
    }

    @Override
    public void setBatteryState(final int status, final int health, final int plugType,
            final int level, final int temp, final int volt, final int chargeUAh) {
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
                        mStats.setBatteryStateLocked(status, health, plugType, level, temp, volt,
                                chargeUAh);
                        return;
                    }
                }

                // Sync external stats first as the battery has changed states. If we don't sync
                // immediately here, we may not collect the relevant data later.
                updateExternalStatsSync("battery-state", BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
                synchronized (mStats) {
                    mStats.setBatteryStateLocked(status, health, plugType, level, temp, volt,
                            chargeUAh);
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
        pw.println("  --checkin: generate output for a checkin report; will write (and clear) the");
        pw.println("             last old completed stats when they had been reset.");
        pw.println("  -c: write the current stats in checkin format.");
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
                    updateExternalStatsSync("dump", BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
                } else if ("--write".equals(arg)) {
                    updateExternalStatsSync("dump", BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
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
            updateExternalStatsSync("dump", BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
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
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                    PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_ALL);
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

    private WifiActivityEnergyInfo extractDelta(WifiActivityEnergyInfo latest) {
        final long timePeriodMs = latest.mTimestamp - mLastInfo.mTimestamp;
        final long lastIdleMs = mLastInfo.mControllerIdleTimeMs;
        final long lastTxMs = mLastInfo.mControllerTxTimeMs;
        final long lastRxMs = mLastInfo.mControllerRxTimeMs;
        final long lastEnergy = mLastInfo.mControllerEnergyUsed;

        // We will modify the last info object to be the delta, and store the new
        // WifiActivityEnergyInfo object as our last one.
        final WifiActivityEnergyInfo delta = mLastInfo;
        delta.mTimestamp = latest.getTimeStamp();
        delta.mStackState = latest.getStackState();

        final long txTimeMs = latest.mControllerTxTimeMs - lastTxMs;
        final long rxTimeMs = latest.mControllerRxTimeMs - lastRxMs;
        final long idleTimeMs = latest.mControllerIdleTimeMs - lastIdleMs;

        if (txTimeMs < 0 || rxTimeMs < 0) {
            // The stats were reset by the WiFi system (which is why our delta is negative).
            // Returns the unaltered stats.
            delta.mControllerEnergyUsed = latest.mControllerEnergyUsed;
            delta.mControllerRxTimeMs = latest.mControllerRxTimeMs;
            delta.mControllerTxTimeMs = latest.mControllerTxTimeMs;
            delta.mControllerIdleTimeMs = latest.mControllerIdleTimeMs;
            Slog.v(TAG, "WiFi energy data was reset, new WiFi energy data is " + delta);
        } else {
            final long totalActiveTimeMs = txTimeMs + rxTimeMs;
            long maxExpectedIdleTimeMs;
            if (totalActiveTimeMs > timePeriodMs) {
                // Cap the max idle time at zero since the active time consumed the whole time
                maxExpectedIdleTimeMs = 0;
                if (totalActiveTimeMs > timePeriodMs + MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Total Active time ");
                    TimeUtils.formatDuration(totalActiveTimeMs, sb);
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
                    TimeUtils.formatDuration(latest.mControllerIdleTimeMs, sb);
                    sb.append(" rx=");
                    TimeUtils.formatDuration(latest.mControllerRxTimeMs, sb);
                    sb.append(" tx=");
                    TimeUtils.formatDuration(latest.mControllerTxTimeMs, sb);
                    sb.append(" e=").append(latest.mControllerEnergyUsed);
                    Slog.wtf(TAG, sb.toString());
                }
            } else {
                maxExpectedIdleTimeMs = timePeriodMs - totalActiveTimeMs;
            }
            // These times seem to be the most reliable.
            delta.mControllerTxTimeMs = txTimeMs;
            delta.mControllerRxTimeMs = rxTimeMs;
            // WiFi calculates the idle time as a difference from the on time and the various
            // Rx + Tx times. There seems to be some missing time there because this sometimes
            // becomes negative. Just cap it at 0 and ensure that it is less than the expected idle
            // time from the difference in timestamps.
            // b/21613534
            delta.mControllerIdleTimeMs = Math.min(maxExpectedIdleTimeMs, Math.max(0, idleTimeMs));
            delta.mControllerEnergyUsed = Math.max(0, latest.mControllerEnergyUsed - lastEnergy);
        }

        mLastInfo = latest;
        return delta;
    }

    /**
     * Helper method to extract the Parcelable controller info from a
     * SynchronousResultReceiver.
     */
    private static <T extends Parcelable> T awaitControllerInfo(
            @Nullable SynchronousResultReceiver receiver) throws TimeoutException {
        if (receiver == null) {
            return null;
        }

        final SynchronousResultReceiver.Result result =
                receiver.awaitResult(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS);
        if (result.bundle != null) {
            // This is the final destination for the Bundle.
            result.bundle.setDefusable(true);

            final T data = result.bundle.getParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY);
            if (data != null) {
                return data;
            }
        }
        Slog.e(TAG, "no controller energy info supplied");
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
     *                    {@link BatteryStatsImpl.ExternalStatsSync#UPDATE_CPU},
     *                    {@link BatteryStatsImpl.ExternalStatsSync#UPDATE_RADIO},
     *                    {@link BatteryStatsImpl.ExternalStatsSync#UPDATE_WIFI},
     *                    and {@link BatteryStatsImpl.ExternalStatsSync#UPDATE_BT}.
     */
    void updateExternalStatsSync(final String reason, int updateFlags) {
        SynchronousResultReceiver wifiReceiver = null;
        SynchronousResultReceiver bluetoothReceiver = null;
        SynchronousResultReceiver modemReceiver = null;

        synchronized (mExternalStatsLock) {
            if (mContext == null) {
                // Don't do any work yet.
                return;
            }

            if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_WIFI) != 0) {
                if (mWifiManager == null) {
                    mWifiManager = IWifiManager.Stub.asInterface(
                            ServiceManager.getService(Context.WIFI_SERVICE));
                }

                if (mWifiManager != null) {
                    try {
                        wifiReceiver = new SynchronousResultReceiver();
                        mWifiManager.requestActivityInfo(wifiReceiver);
                    } catch (RemoteException e) {
                        // Oh well.
                    }
                }
            }

            if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_BT) != 0) {
                final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    bluetoothReceiver = new SynchronousResultReceiver();
                    adapter.requestControllerActivityEnergyInfo(bluetoothReceiver);
                }
            }

            if ((updateFlags & BatteryStatsImpl.ExternalStatsSync.UPDATE_RADIO) != 0) {
                if (mTelephony == null) {
                    mTelephony = TelephonyManager.from(mContext);
                }

                if (mTelephony != null) {
                    modemReceiver = new SynchronousResultReceiver();
                    mTelephony.requestModemActivityInfo(modemReceiver);
                }
            }

            WifiActivityEnergyInfo wifiInfo = null;
            BluetoothActivityEnergyInfo bluetoothInfo = null;
            ModemActivityInfo modemInfo = null;
            try {
                wifiInfo = awaitControllerInfo(wifiReceiver);
            } catch (TimeoutException e) {
                Slog.w(TAG, "Timeout reading wifi stats");
            }

            try {
                bluetoothInfo = awaitControllerInfo(bluetoothReceiver);
            } catch (TimeoutException e) {
                Slog.w(TAG, "Timeout reading bt stats");
            }

            try {
                modemInfo = awaitControllerInfo(modemReceiver);
            } catch (TimeoutException e) {
                Slog.w(TAG, "Timeout reading modem stats");
            }

            synchronized (mStats) {
                mStats.addHistoryEventLocked(
                        SystemClock.elapsedRealtime(),
                        SystemClock.uptimeMillis(),
                        BatteryStats.HistoryItem.EVENT_COLLECT_EXTERNAL_STATS,
                        reason, 0);

                mStats.updateCpuTimeLocked();
                mStats.updateKernelWakelocksLocked();

                if (wifiInfo != null) {
                    if (wifiInfo.isValid()) {
                        mStats.updateWifiStateLocked(extractDelta(wifiInfo));
                    } else {
                        Slog.e(TAG, "wifi info is invalid: " + wifiInfo);
                    }
                }

                if (bluetoothInfo != null) {
                    if (bluetoothInfo.isValid()) {
                        mStats.updateBluetoothStateLocked(bluetoothInfo);
                    } else {
                        Slog.e(TAG, "bluetooth info is invalid: " + bluetoothInfo);
                    }
                }

                if (modemInfo != null) {
                    if (modemInfo.isValid()) {
                        mStats.updateMobileRadioStateLocked(SystemClock.elapsedRealtime(),
                                modemInfo);
                    } else {
                        Slog.e(TAG, "modem info is invalid: " + modemInfo);
                    }
                }
            }
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
            updateExternalStatsSync("get-health-stats-for-uid",
                    BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
            synchronized (mStats) {
                return getHealthStatsForUidLocked(requestUid);
            }
        } catch (Exception ex) {
            Slog.d(TAG, "Crashed while writing for takeUidSnapshot(" + requestUid + ")", ex);
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
            updateExternalStatsSync("get-health-stats-for-uids",
                    BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL);
            synchronized (mStats) {
                final int N = requestUids.length;
                final HealthStatsParceler[] results = new HealthStatsParceler[N];
                for (i=0; i<N; i++) {
                    results[i] = getHealthStatsForUidLocked(requestUids[i]);
                }
                return results;
            }
        } catch (Exception ex) {
            Slog.d(TAG, "Crashed while writing for takeUidSnapshots("
                    + Arrays.toString(requestUids) + ") i=" + i, ex);
            throw ex;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
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

}
