/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.DisplayManager;
import android.net.INetworkPolicyManager;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.Xml;
import android.view.Display;

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.AtomicFile;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.am.BatteryStatsService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Keeps track of device idleness and drives low power mode based on that.
 */
public class DeviceIdleController extends SystemService {
    private static final String TAG = "DeviceIdleController";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String SERVICE_NAME = "deviceidle";

    private static final String ACTION_STEP_IDLE_STATE =
            "com.android.server.device_idle.STEP_IDLE_STATE";

    // TODO: These need to be moved to system settings.

    /**
     * This is the time, after becoming inactive, at which we start looking at the
     * motion sensor to determine if the device is being left alone.  We don't do this
     * immediately after going inactive just because we don't want to be continually running
     * the significant motion sensor whenever the screen is off.
     */
    private static final long DEFAULT_INACTIVE_TIMEOUT = !DEBUG ? 30*60*1000L
            : 2 * 60 * 1000L;
    /**
     * This is the time, after seeing motion, that we wait after becoming inactive from
     * that until we start looking for motion again.
     */
    private static final long DEFAULT_MOTION_INACTIVE_TIMEOUT = !DEBUG ? 10*60*1000L
            : 60 * 1000L;
    /**
     * This is the time, after the inactive timeout elapses, that we will wait looking
     * for significant motion until we truly consider the device to be idle.
     */
    private static final long DEFAULT_IDLE_AFTER_INACTIVE_TIMEOUT = !DEBUG ? 30*60*1000L
            : 2 * 60 * 1000L;
    /**
     * This is the initial time, after being idle, that we will allow ourself to be back
     * in the IDLE_PENDING state allowing the system to run normally until we return to idle.
     */
    private static final long DEFAULT_IDLE_PENDING_TIMEOUT = 5*60*1000L;
    /**
     * Maximum pending idle timeout (time spent running) we will be allowed to use.
     */
    private static final long DEFAULT_MAX_IDLE_PENDING_TIMEOUT = 10*60*1000L;
    /**
     * Scaling factor to apply to current pending idle timeout each time we cycle through
     * that state.
     */
    private static final float DEFAULT_IDLE_PENDING_FACTOR = 2f;
    /**
     * This is the initial time that we want to sit in the idle state before waking up
     * again to return to pending idle and allowing normal work to run.
     */
    private static final long DEFAULT_IDLE_TIMEOUT = !DEBUG ? 60*60*1000L
            : 5 * 60 * 1000L;
    /**
     * Maximum idle duration we will be allowed to use.
     */
    private static final long DEFAULT_MAX_IDLE_TIMEOUT = !DEBUG ? 6*60*60*1000L
            : 10 * 60 * 1000L;
    /**
     * Scaling factor to apply to current idle timeout each time we cycle through that state.
     */
    private static final float DEFAULT_IDLE_FACTOR = 2f;
    /**
     * This is the minimum time we will allow until the next upcoming alarm for us to
     * actually go in to idle mode.
     */
    private static final long DEFAULT_MIN_TIME_TO_ALARM = !DEBUG ? 60*60*1000L
            : 5 * 60 * 1000L;

    private AlarmManager mAlarmManager;
    private IBatteryStats mBatteryStats;
    private PowerManagerInternal mLocalPowerManager;
    private INetworkPolicyManager mNetworkPolicyManager;
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mSigMotionSensor;
    private PendingIntent mAlarmIntent;
    private Intent mIdleIntent;
    private Display mCurDisplay;
    private boolean mIdleDisabled;
    private boolean mScreenOn;
    private boolean mCharging;
    private boolean mSigMotionActive;

    /** Device is currently active. */
    private static final int STATE_ACTIVE = 0;
    /** Device is inactve (screen off, no motion) and we are waiting to for idle. */
    private static final int STATE_INACTIVE = 1;
    /** Device is past the initial inactive period, and waiting for the next idle period. */
    private static final int STATE_IDLE_PENDING = 2;
    /** Device is in the idle state, trying to stay asleep as much as possible. */
    private static final int STATE_IDLE = 3;
    /** Device is in the idle state, but temporarily out of idle to do regular maintenance. */
    private static final int STATE_IDLE_MAINTENANCE = 4;
    private static String stateToString(int state) {
        switch (state) {
            case STATE_ACTIVE: return "ACTIVE";
            case STATE_INACTIVE: return "INACTIVE";
            case STATE_IDLE_PENDING: return "IDLE_PENDING";
            case STATE_IDLE: return "IDLE";
            case STATE_IDLE_MAINTENANCE: return "IDLE_MAINTENANCE";
            default: return Integer.toString(state);
        }
    }

    private int mState;

    private long mInactiveTimeout;
    private long mNextAlarmTime;
    private long mNextIdlePendingDelay;
    private long mNextIdleDelay;

    public final AtomicFile mConfigFile;

    /**
     * Package names the system has white-listed to opt out of power save restrictions.
     */
    private final ArrayMap<String, Integer> mPowerSaveWhitelistApps = new ArrayMap<>();

    /**
     * Package names the user has white-listed to opt out of power save restrictions.
     */
    private final ArrayMap<String, Integer> mPowerSaveWhitelistUserApps = new ArrayMap<>();

    /**
     * App IDs that have been white-listed to opt out of power save restrictions.
     */
    private final SparseBooleanArray mPowerSaveWhitelistAppIds = new SparseBooleanArray();

    /**
     * Current app IDs that are in the complete power save white list.  This array can
     * be shared with others because it will not be modified once set.
     */
    private int[] mPowerSaveWhitelistAppIdArray = new int[0];

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int plugged = intent.getIntExtra("plugged", 0);
                updateChargingLocked(plugged != 0);
            } else if (ACTION_STEP_IDLE_STATE.equals(intent.getAction())) {
                synchronized (DeviceIdleController.this) {
                    stepIdleStateLocked();
                }
            }
        }
    };

    private final DisplayManager.DisplayListener mDisplayListener
            = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {
        }

        @Override public void onDisplayRemoved(int displayId) {
        }

        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                synchronized (DeviceIdleController.this) {
                    updateDisplayLocked();
                }
            }
        }
    };

    private final TriggerEventListener mSigMotionListener = new TriggerEventListener() {
        @Override public void onTrigger(TriggerEvent event) {
            synchronized (DeviceIdleController.this) {
                significantMotionLocked();
            }
        }
    };

    static final int MSG_WRITE_CONFIG = 1;
    static final int MSG_REPORT_IDLE_ON = 2;
    static final int MSG_REPORT_IDLE_OFF = 3;
    static final int MSG_REPORT_ACTIVE = 4;

    final class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }

        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WRITE_CONFIG: {
                    handleWriteConfigFile();
                } break;
                case MSG_REPORT_IDLE_ON: {
                    mLocalPowerManager.setDeviceIdleMode(true);
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(true);
                        mBatteryStats.noteDeviceIdleMode(true, false, false);
                    } catch (RemoteException e) {
                    }
                    getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL);
                } break;
                case MSG_REPORT_IDLE_OFF: {
                    mLocalPowerManager.setDeviceIdleMode(false);
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(false);
                        mBatteryStats.noteDeviceIdleMode(false, false, false);
                    } catch (RemoteException e) {
                    }
                    getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL);
                } break;
                case MSG_REPORT_ACTIVE: {
                    boolean fromMotion = msg.arg1 != 0;
                    boolean needBroadcast = msg.arg2 != 0;
                    mLocalPowerManager.setDeviceIdleMode(false);
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(false);
                        mBatteryStats.noteDeviceIdleMode(false, !fromMotion, fromMotion);
                    } catch (RemoteException e) {
                    }
                    if (needBroadcast) {
                        getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL);
                    }
                } break;
            }
        }
    }

    final MyHandler mHandler;

    private final class BinderService extends IDeviceIdleController.Stub {
        @Override public void addPowerSaveWhitelistApp(String name) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            addPowerSaveWhitelistAppInternal(name);
        }

        @Override public void removePowerSaveWhitelistApp(String name) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            removePowerSaveWhitelistAppInternal(name);
        }

        @Override public String[] getSystemPowerWhitelist() {
            return getSystemPowerWhitelistInternal();
        }

        @Override public String[] getFullPowerWhitelist() {
            return getFullPowerWhitelistInternal();
        }

        @Override public int[] getAppIdWhitelist() {
            return getAppIdWhitelistInternal();
        }

        @Override public boolean isPowerSaveWhitelistApp(String name) {
            return isPowerSaveWhitelistAppInternal(name);
        }

        @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            DeviceIdleController.this.dump(fd, pw, args);
        }
    }

    public DeviceIdleController(Context context) {
        super(context);
        mConfigFile = new AtomicFile(new File(getSystemDir(), "deviceidle.xml"));
        mHandler = new MyHandler(BackgroundThread.getHandler().getLooper());
    }

    private static File getSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    @Override
    public void onStart() {
        final PackageManager pm = getContext().getPackageManager();

        synchronized (this) {
            SystemConfig sysConfig = SystemConfig.getInstance();
            ArraySet<String> allowPower = sysConfig.getAllowInPowerSave();
            for (int i=0; i<allowPower.size(); i++) {
                String pkg = allowPower.valueAt(i);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if ((ai.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        mPowerSaveWhitelistApps.put(ai.packageName,
                                UserHandle.getAppId(ai.uid));
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }

            readConfigFileLocked();
            updateWhitelistAppIdsLocked();

            mScreenOn = true;
            // Start out assuming we are charging.  If we aren't, we will at least get
            // a battery update the next time the level drops.
            mCharging = true;
            mState = STATE_ACTIVE;
            mInactiveTimeout = DEFAULT_INACTIVE_TIMEOUT;
        }

        publishBinderService(SERVICE_NAME, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            synchronized (this) {
                mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
                mBatteryStats = BatteryStatsService.getService();
                mLocalPowerManager = getLocalService(PowerManagerInternal.class);
                mNetworkPolicyManager = INetworkPolicyManager.Stub.asInterface(
                                    ServiceManager.getService(Context.NETWORK_POLICY_SERVICE));
                mDisplayManager = (DisplayManager) getContext().getSystemService(
                        Context.DISPLAY_SERVICE);
                mSensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
                mSigMotionSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);

                Intent intent = new Intent(ACTION_STEP_IDLE_STATE)
                        .setPackage("android")
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mAlarmIntent = PendingIntent.getBroadcast(getContext(), 0, intent, 0);

                mIdleIntent = new Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                mIdleIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_BATTERY_CHANGED);
                filter.addAction(ACTION_STEP_IDLE_STATE);
                getContext().registerReceiver(mReceiver, filter);

                mLocalPowerManager.setDeviceIdleWhitelist(mPowerSaveWhitelistAppIdArray);

                mDisplayManager.registerDisplayListener(mDisplayListener, null);
                updateDisplayLocked();
            }
        }
    }

    public boolean addPowerSaveWhitelistAppInternal(String name) {
        synchronized (this) {
            try {
                ApplicationInfo ai = getContext().getPackageManager().getApplicationInfo(name, 0);
                if (mPowerSaveWhitelistUserApps.put(name, UserHandle.getAppId(ai.uid)) == null) {
                    reportPowerSaveWhitelistChangedLocked();
                    updateWhitelistAppIdsLocked();
                    writeConfigFileLocked();
                }
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
    }

    public boolean removePowerSaveWhitelistAppInternal(String name) {
        synchronized (this) {
            if (mPowerSaveWhitelistUserApps.remove(name) != null) {
                reportPowerSaveWhitelistChangedLocked();
                updateWhitelistAppIdsLocked();
                writeConfigFileLocked();
                return true;
            }
        }
        return false;
    }

    public String[] getSystemPowerWhitelistInternal() {
        synchronized (this) {
            int size = mPowerSaveWhitelistApps.size();
            String[] apps = new String[size];
            for (int i = 0; i < mPowerSaveWhitelistApps.size(); i++) {
                apps[i] = mPowerSaveWhitelistApps.keyAt(i);
            }
            return apps;
        }
    }

    public String[] getFullPowerWhitelistInternal() {
        synchronized (this) {
            int size = mPowerSaveWhitelistApps.size() + mPowerSaveWhitelistUserApps.size();
            String[] apps = new String[size];
            int cur = 0;
            for (int i = 0; i < mPowerSaveWhitelistApps.size(); i++) {
                apps[cur] = mPowerSaveWhitelistApps.keyAt(i);
                cur++;
            }
            for (int i = 0; i < mPowerSaveWhitelistUserApps.size(); i++) {
                apps[cur] = mPowerSaveWhitelistUserApps.keyAt(i);
                cur++;
            }
            return apps;
        }
    }

    public boolean isPowerSaveWhitelistAppInternal(String packageName) {
        synchronized (this) {
            return mPowerSaveWhitelistApps.containsKey(packageName)
                    || mPowerSaveWhitelistUserApps.containsKey(packageName);
        }
    }

    public int[] getAppIdWhitelistInternal() {
        synchronized (this) {
            return mPowerSaveWhitelistAppIdArray;
        }
    }

    void updateDisplayLocked() {
        mCurDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        // We consider any situation where the display is showing something to be it on,
        // because if there is anything shown we are going to be updating it at some
        // frequency so can't be allowed to go into deep sleeps.
        boolean screenOn = mCurDisplay.getState() != Display.STATE_OFF;;
        if (!screenOn && mScreenOn) {
            mScreenOn = false;
            becomeInactiveIfAppropriateLocked();
        } else if (screenOn) {
            mScreenOn = true;
            becomeActiveLocked("screen");
        }
    }

    void updateChargingLocked(boolean charging) {
        if (!charging && mCharging) {
            mCharging = false;
            becomeInactiveIfAppropriateLocked();
        } else if (charging) {
            mCharging = charging;
            becomeActiveLocked("charging");
        }
    }

    void scheduleReportActiveLocked(boolean fromMotion) {
        Message msg = mHandler.obtainMessage(MSG_REPORT_ACTIVE, fromMotion ? 1 : 0,
                mState == STATE_IDLE ? 1 : 0);
        mHandler.sendMessage(msg);
    }

    void becomeActiveLocked(String reason) {
        if (mState != STATE_ACTIVE) {
            EventLogTags.writeDeviceIdle(STATE_ACTIVE, reason);
            scheduleReportActiveLocked(false);
            mState = STATE_ACTIVE;
            mInactiveTimeout = DEFAULT_INACTIVE_TIMEOUT;
            mNextIdlePendingDelay = 0;
            mNextIdleDelay = 0;
            cancelAlarmLocked();
            stopMonitoringSignificantMotion();
        }
    }

    void becomeInactiveIfAppropriateLocked() {
        if (!mScreenOn && !mCharging && !mIdleDisabled && mState == STATE_ACTIVE) {
            // Screen has turned off; we are now going to become inactive and start
            // waiting to see if we will ultimately go idle.
            mState = STATE_INACTIVE;
            mNextIdlePendingDelay = 0;
            mNextIdleDelay = 0;
            scheduleAlarmLocked(mInactiveTimeout, false);
            EventLogTags.writeDeviceIdle(mState, "no activity");
        }
    }

    void stepIdleStateLocked() {
        EventLogTags.writeDeviceIdleStep();

        final long now = SystemClock.elapsedRealtime();
        if ((now+DEFAULT_MIN_TIME_TO_ALARM) > mAlarmManager.getNextWakeFromIdleTime()) {
            // Whoops, there is an upcoming alarm.  We don't actually want to go idle.
            if (mState != STATE_ACTIVE) {
                becomeActiveLocked("alarm");
            }
            return;
        }

        switch (mState) {
            case STATE_INACTIVE:
                // We have now been inactive long enough, it is time to start looking
                // for significant motion and sleep some more while doing so.
                startMonitoringSignificantMotion();
                scheduleAlarmLocked(DEFAULT_IDLE_AFTER_INACTIVE_TIMEOUT, false);
                // Reset the upcoming idle delays.
                mNextIdlePendingDelay = DEFAULT_IDLE_PENDING_TIMEOUT;
                mNextIdleDelay = DEFAULT_IDLE_TIMEOUT;
                mState = STATE_IDLE_PENDING;
                EventLogTags.writeDeviceIdle(mState, "step");
                break;
            case STATE_IDLE_PENDING:
            case STATE_IDLE_MAINTENANCE:
                // We have been waiting to become idle, and now it is time!  This is the
                // only case where we want to use a wakeup alarm, because we do want to
                // drag the device out of its sleep state in this case to do the next
                // scheduled work.
                scheduleAlarmLocked(mNextIdleDelay, true);
                mNextIdleDelay = (long)(mNextIdleDelay*DEFAULT_IDLE_FACTOR);
                if (mNextIdleDelay > DEFAULT_MAX_IDLE_TIMEOUT) {
                    mNextIdleDelay = DEFAULT_MAX_IDLE_TIMEOUT;
                }
                mState = STATE_IDLE;
                EventLogTags.writeDeviceIdle(mState, "step");
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_ON);
                break;
            case STATE_IDLE:
                // We have been idling long enough, now it is time to do some work.
                scheduleAlarmLocked(mNextIdlePendingDelay, false);
                mNextIdlePendingDelay = (long)(mNextIdlePendingDelay*DEFAULT_IDLE_PENDING_FACTOR);
                if (mNextIdlePendingDelay > DEFAULT_MAX_IDLE_PENDING_TIMEOUT) {
                    mNextIdlePendingDelay = DEFAULT_MAX_IDLE_PENDING_TIMEOUT;
                }
                mState = STATE_IDLE_MAINTENANCE;
                EventLogTags.writeDeviceIdle(mState, "step");
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_OFF);
                break;
        }
    }

    void significantMotionLocked() {
        // When the sensor goes off, its trigger is automatically removed.
        mSigMotionActive = false;
        // The device is not yet active, so we want to go back to the pending idle
        // state to wait again for no motion.  Note that we only monitor for significant
        // motion after moving out of the inactive state, so no need to worry about that.
        if (mState != STATE_ACTIVE) {
            scheduleReportActiveLocked(true);
            mState = STATE_ACTIVE;
            mInactiveTimeout = DEFAULT_MOTION_INACTIVE_TIMEOUT;
            EventLogTags.writeDeviceIdle(mState, "motion");
            becomeInactiveIfAppropriateLocked();
        }
    }

    void startMonitoringSignificantMotion() {
        if (mSigMotionSensor != null && !mSigMotionActive) {
            mSensorManager.requestTriggerSensor(mSigMotionListener, mSigMotionSensor);
            mSigMotionActive = true;
        }
    }

    void stopMonitoringSignificantMotion() {
        if (mSigMotionActive) {
            mSensorManager.cancelTriggerSensor(mSigMotionListener, mSigMotionSensor);
            mSigMotionActive = false;
        }
    }

    void cancelAlarmLocked() {
        if (mNextAlarmTime != 0) {
            mNextAlarmTime = 0;
            mAlarmManager.cancel(mAlarmIntent);
        }
    }

    void scheduleAlarmLocked(long delay, boolean idleUntil) {
        if (mSigMotionSensor == null) {
            // If there is no significant motion sensor on this device, then we won't schedule
            // alarms, because we can't determine if the device is not moving.  This effectively
            // turns off normal exeuction of device idling, although it is still possible to
            // manually poke it by pretending like the alarm is going off.
            return;
        }
        mNextAlarmTime = SystemClock.elapsedRealtime() + delay;
        if (idleUntil) {
            mAlarmManager.setIdleUntil(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mNextAlarmTime, mAlarmIntent);
        } else {
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mNextAlarmTime, mAlarmIntent);
        }
    }

    private void updateWhitelistAppIdsLocked() {
        mPowerSaveWhitelistAppIds.clear();
        for (int i=0; i<mPowerSaveWhitelistApps.size(); i++) {
            mPowerSaveWhitelistAppIds.put(mPowerSaveWhitelistApps.valueAt(i), true);
        }
        for (int i=0; i<mPowerSaveWhitelistUserApps.size(); i++) {
            mPowerSaveWhitelistAppIds.put(mPowerSaveWhitelistUserApps.valueAt(i), true);
        }
        int size = mPowerSaveWhitelistAppIds.size();
        int[] appids = new int[size];
        for (int i = 0; i < size; i++) {
            appids[i] = mPowerSaveWhitelistAppIds.keyAt(i);
        }
        mPowerSaveWhitelistAppIdArray = appids;
        if (mLocalPowerManager != null) {
            mLocalPowerManager.setDeviceIdleWhitelist(mPowerSaveWhitelistAppIdArray);
        }
    }

    private void reportPowerSaveWhitelistChangedLocked() {
        Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        getContext().sendBroadcast(intent);
    }

    void readConfigFileLocked() {
        Slog.d(TAG, "Reading config from " + mConfigFile.getBaseFile());
        mPowerSaveWhitelistUserApps.clear();
        FileInputStream stream;
        try {
            stream = mConfigFile.openRead();
        } catch (FileNotFoundException e) {
            return;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);
            readConfigFileLocked(parser);
        } catch (XmlPullParserException e) {
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
            }
        }

    }

    private void readConfigFileLocked(XmlPullParser parser) {
        final PackageManager pm = getContext().getPackageManager();

        try {
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                throw new IllegalStateException("no start tag found");
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("wl")) {
                    String name = parser.getAttributeValue(null, "n");
                    if (name != null) {
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(name, 0);
                            mPowerSaveWhitelistUserApps.put(ai.packageName,
                                    UserHandle.getAppId(ai.uid));
                        } catch (PackageManager.NameNotFoundException e) {
                        }
                    }
                } else {
                    Slog.w(TAG, "Unknown element under <config>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

        } catch (IllegalStateException e) {
            Slog.w(TAG, "Failed parsing config " + e);
        } catch (NullPointerException e) {
            Slog.w(TAG, "Failed parsing config " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed parsing config " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Failed parsing config " + e);
        } catch (IOException e) {
            Slog.w(TAG, "Failed parsing config " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "Failed parsing config " + e);
        }
    }

    void writeConfigFileLocked() {
        mHandler.removeMessages(MSG_WRITE_CONFIG);
        mHandler.sendEmptyMessageDelayed(MSG_WRITE_CONFIG, 5000);
    }

    void handleWriteConfigFile() {
        final ByteArrayOutputStream memStream = new ByteArrayOutputStream();

        try {
            synchronized (this) {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(memStream, "utf-8");
                writeConfigFileLocked(out);
            }
        } catch (IOException e) {
        }

        synchronized (mConfigFile) {
            FileOutputStream stream = null;
            try {
                stream = mConfigFile.startWrite();
                memStream.writeTo(stream);
                stream.flush();
                FileUtils.sync(stream);
                stream.close();
                mConfigFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Error writing config file", e);
                mConfigFile.failWrite(stream);
            }
        }
    }

    void writeConfigFileLocked(XmlSerializer out) throws IOException {
        out.startDocument(null, true);
        out.startTag(null, "config");
        for (int i=0; i<mPowerSaveWhitelistUserApps.size(); i++) {
            String name = mPowerSaveWhitelistUserApps.keyAt(i);
            out.startTag(null, "wl");
            out.attribute(null, "n", name);
            out.endTag(null, "wl");
        }
        out.endTag(null, "config");
        out.endDocument();
    }

    private void dumpHelp(PrintWriter pw) {
        pw.println("Device idle controller (deviceidle) dump options:");
        pw.println("  [-h] [CMD]");
        pw.println("  -h: print this help text.");
        pw.println("Commands:");
        pw.println("  step");
        pw.println("    Immediately step to next state, without waiting for alarm.");
        pw.println("  disable");
        pw.println("    Completely disable device idle mode.");
        pw.println("  enable");
        pw.println("    Re-enable device idle mode after it had previously been disabled.");
        pw.println("  whitelist");
        pw.println("    Add (prefix with +) or remove (prefix with -) packages.");
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump DeviceIdleController from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // Ignore, we always dump all.
                } else if ("step".equals(arg)) {
                    synchronized (this) {
                        stepIdleStateLocked();
                        pw.print("Stepped to: "); pw.println(stateToString(mState));
                    }
                    return;
                } else if ("disable".equals(arg)) {
                    synchronized (this) {
                        if (!mIdleDisabled) {
                            mIdleDisabled = true;
                            becomeActiveLocked("disabled");
                            pw.println("Idle mode disabled");
                        }
                    }
                    return;
                } else if ("enable".equals(arg)) {
                    synchronized (this) {
                        if (mIdleDisabled) {
                            mIdleDisabled = false;
                            becomeInactiveIfAppropriateLocked();
                            pw.println("Idle mode enabled");
                        }
                    }
                    return;
                } else if ("whitelist".equals(arg)) {
                    i++;
                    while (i < args.length) {
                        arg = args[i];
                        i++;
                        if (arg.length() < 1 || (arg.charAt(0) != '-'
                                && arg.charAt(0) != '+')) {
                            pw.println("Package must be prefixed with + or -: " + arg);
                            return;
                        }
                        char op = arg.charAt(0);
                        String pkg = arg.substring(1);
                        if (op == '+') {
                            if (addPowerSaveWhitelistAppInternal(pkg)) {
                                pw.println("Added: " + pkg);
                            } else {
                                pw.println("Unknown package: " + pkg);
                            }
                        } else {
                            if (removePowerSaveWhitelistAppInternal(pkg)) {
                                pw.println("Removed: " + pkg);
                            }
                        }
                    }
                    return;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    return;
                } else {
                    pw.println("Unknown command: " + arg);
                    return;
                }
            }
        }

        synchronized (this) {
            int size = mPowerSaveWhitelistApps.size();
            if (size > 0) {
                pw.println("  Whitelist system apps:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.println(mPowerSaveWhitelistApps.keyAt(i));
                }
            }
            size = mPowerSaveWhitelistUserApps.size();
            if (size > 0) {
                pw.println("  Whitelist user apps:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.println(mPowerSaveWhitelistUserApps.keyAt(i));
                }
            }
            size = mPowerSaveWhitelistAppIds.size();
            if (size > 0) {
                pw.println("  Whitelist app uids:");
                for (int i = 0; i < size; i++) {
                    pw.print("    UID=");
                    pw.print(mPowerSaveWhitelistAppIds.keyAt(i));
                    pw.print(": ");
                    pw.print(mPowerSaveWhitelistAppIds.valueAt(i));
                    pw.println();
                }
            }
            pw.print("  mSigMotionSensor="); pw.println(mSigMotionSensor);
            pw.print("  mCurDisplay="); pw.println(mCurDisplay);
            pw.print("  mIdleDisabled="); pw.println(mIdleDisabled);
            pw.print("  mScreenOn="); pw.println(mScreenOn);
            pw.print("  mCharging="); pw.println(mCharging);
            pw.print("  mSigMotionActive="); pw.println(mSigMotionActive);
            pw.print("  mState="); pw.println(stateToString(mState));
            pw.print("  mInactiveTimeout="); TimeUtils.formatDuration(mInactiveTimeout, pw);
            pw.println();
            if (mNextAlarmTime != 0) {
                pw.print("  mNextAlarmTime=");
                TimeUtils.formatDuration(mNextAlarmTime, SystemClock.elapsedRealtime(), pw);
                pw.println();
            }
            if (mNextIdlePendingDelay != 0) {
                pw.print("  mNextIdlePendingDelay=");
                TimeUtils.formatDuration(mNextIdlePendingDelay, pw);
                pw.println();
            }
            if (mNextIdleDelay != 0) {
                pw.print("  mNextIdleDelay=");
                TimeUtils.formatDuration(mNextIdleDelay, pw);
                pw.println();
            }
        }
    }
}
