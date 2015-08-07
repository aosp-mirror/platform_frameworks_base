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

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.DisplayManager;
import android.net.INetworkPolicyManager;
import android.net.Uri;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.MutableLong;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Keeps track of device idleness and drives low power mode based on that.
 */
public class DeviceIdleController extends SystemService
        implements AnyMotionDetector.DeviceIdleCallback {
    private static final String TAG = "DeviceIdleController";

    private static final boolean DEBUG = false;

    private static final boolean COMPRESS_TIME = false;

    private static final String ACTION_STEP_IDLE_STATE =
            "com.android.server.device_idle.STEP_IDLE_STATE";

    private AlarmManager mAlarmManager;
    private IBatteryStats mBatteryStats;
    private PowerManagerInternal mLocalPowerManager;
    private INetworkPolicyManager mNetworkPolicyManager;
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mSigMotionSensor;
    private PendingIntent mSensingAlarmIntent;
    private PendingIntent mAlarmIntent;
    private Intent mIdleIntent;
    private Display mCurDisplay;
    private AnyMotionDetector mAnyMotionDetector;
    private boolean mEnabled;
    private boolean mForceIdle;
    private boolean mScreenOn;
    private boolean mCharging;
    private boolean mSigMotionActive;

    /** Device is currently active. */
    private static final int STATE_ACTIVE = 0;
    /** Device is inactve (screen off, no motion) and we are waiting to for idle. */
    private static final int STATE_INACTIVE = 1;
    /** Device is past the initial inactive period, and waiting for the next idle period. */
    private static final int STATE_IDLE_PENDING = 2;
    /** Device is currently sensing motion. */
    private static final int STATE_SENSING = 3;
    /** Device is in the idle state, trying to stay asleep as much as possible. */
    private static final int STATE_IDLE = 4;
    /** Device is in the idle state, but temporarily out of idle to do regular maintenance. */
    private static final int STATE_IDLE_MAINTENANCE = 5;
    private static String stateToString(int state) {
        switch (state) {
            case STATE_ACTIVE: return "ACTIVE";
            case STATE_INACTIVE: return "INACTIVE";
            case STATE_IDLE_PENDING: return "IDLE_PENDING";
            case STATE_SENSING: return "SENSING";
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
     * Package names the system has white-listed to opt out of power save restrictions,
     * except for device idle mode.
     */
    private final ArrayMap<String, Integer> mPowerSaveWhitelistAppsExceptIdle = new ArrayMap<>();

    /**
     * Package names the system has white-listed to opt out of power save restrictions for
     * all modes.
     */
    private final ArrayMap<String, Integer> mPowerSaveWhitelistApps = new ArrayMap<>();

    /**
     * Package names the user has white-listed to opt out of power save restrictions.
     */
    private final ArrayMap<String, Integer> mPowerSaveWhitelistUserApps = new ArrayMap<>();

    /**
     * App IDs of built-in system apps that have been white-listed except for idle modes.
     */
    private final SparseBooleanArray mPowerSaveWhitelistSystemAppIdsExceptIdle
            = new SparseBooleanArray();

    /**
     * App IDs of built-in system apps that have been white-listed.
     */
    private final SparseBooleanArray mPowerSaveWhitelistSystemAppIds = new SparseBooleanArray();

    /**
     * App IDs that have been white-listed to opt out of power save restrictions, except
     * for device idle modes.
     */
    private final SparseBooleanArray mPowerSaveWhitelistExceptIdleAppIds = new SparseBooleanArray();

    /**
     * Current app IDs that are in the complete power save white list, but shouldn't be
     * excluded from idle modes.  This array can be shared with others because it will not be
     * modified once set.
     */
    private int[] mPowerSaveWhitelistExceptIdleAppIdArray = new int[0];

    /**
     * App IDs that have been white-listed to opt out of power save restrictions.
     */
    private final SparseBooleanArray mPowerSaveWhitelistAllAppIds = new SparseBooleanArray();

    /**
     * Current app IDs that are in the complete power save white list.  This array can
     * be shared with others because it will not be modified once set.
     */
    private int[] mPowerSaveWhitelistAllAppIdArray = new int[0];

    /**
     * List of end times for UIDs that are temporarily marked as being allowed to access
     * the network and acquire wakelocks. Times are in milliseconds.
     */
    private final SparseArray<Pair<MutableLong, String>> mTempWhitelistAppIdEndTimes
            = new SparseArray<>();

    /**
     * Callback to the NetworkPolicyManagerService to tell it that the temp whitelist has changed.
     */
    Runnable mNetworkPolicyTempWhitelistCallback;

    /**
     * Current app IDs of temporarily whitelist apps for high-priority messages.
     */
    private int[] mTempWhitelistAppIdArray = new int[0];

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

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the DeviceIdleController lock.
     */
    private final class Constants extends ContentObserver {
        // Key names stored in the settings value.
        private static final String KEY_INACTIVE_TIMEOUT = "inactive_to";
        private static final String KEY_SENSING_TIMEOUT = "sensing_to";
        private static final String KEY_MOTION_INACTIVE_TIMEOUT = "motion_inactive_to";
        private static final String KEY_IDLE_AFTER_INACTIVE_TIMEOUT = "idle_after_inactive_to";
        private static final String KEY_IDLE_PENDING_TIMEOUT = "idle_pending_to";
        private static final String KEY_MAX_IDLE_PENDING_TIMEOUT = "max_idle_pending_to";
        private static final String KEY_IDLE_PENDING_FACTOR = "idle_pending_factor";
        private static final String KEY_IDLE_TIMEOUT = "idle_to";
        private static final String KEY_MAX_IDLE_TIMEOUT = "max_idle_to";
        private static final String KEY_IDLE_FACTOR = "idle_factor";
        private static final String KEY_MIN_TIME_TO_ALARM = "min_time_to_alarm";
        private static final String KEY_MAX_TEMP_APP_WHITELIST_DURATION =
                "max_temp_app_whitelist_duration";
        private static final String KEY_MMS_TEMP_APP_WHITELIST_DURATION =
                "mms_temp_app_whitelist_duration";
        private static final String KEY_SMS_TEMP_APP_WHITELIST_DURATION =
                "sms_temp_app_whitelist_duration";

        /**
         * This is the time, after becoming inactive, at which we start looking at the
         * motion sensor to determine if the device is being left alone.  We don't do this
         * immediately after going inactive just because we don't want to be continually running
         * the significant motion sensor whenever the screen is off.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_INACTIVE_TIMEOUT
         */
        public long INACTIVE_TIMEOUT;

        /**
         * If we don't receive a callback from AnyMotion in this amount of time, we will change from
         * STATE_SENSING to STATE_INACTIVE, and any AnyMotion callbacks while not in STATE_SENSING
         * will be ignored.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_SENSING_TIMEOUT
         */
        public long SENSING_TIMEOUT;

        /**
         * This is the time, after seeing motion, that we wait after becoming inactive from
         * that until we start looking for motion again.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MOTION_INACTIVE_TIMEOUT
         */
        public long MOTION_INACTIVE_TIMEOUT;

        /**
         * This is the time, after the inactive timeout elapses, that we will wait looking
         * for significant motion until we truly consider the device to be idle.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_IDLE_AFTER_INACTIVE_TIMEOUT
         */
        public long IDLE_AFTER_INACTIVE_TIMEOUT;

        /**
         * This is the initial time, after being idle, that we will allow ourself to be back
         * in the IDLE_PENDING state allowing the system to run normally until we return to idle.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_IDLE_PENDING_TIMEOUT
         */
        public long IDLE_PENDING_TIMEOUT;

        /**
         * Maximum pending idle timeout (time spent running) we will be allowed to use.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MAX_IDLE_PENDING_TIMEOUT
         */
        public long MAX_IDLE_PENDING_TIMEOUT;

        /**
         * Scaling factor to apply to current pending idle timeout each time we cycle through
         * that state.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_IDLE_PENDING_FACTOR
         */
        public float IDLE_PENDING_FACTOR;

        /**
         * This is the initial time that we want to sit in the idle state before waking up
         * again to return to pending idle and allowing normal work to run.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_IDLE_TIMEOUT
         */
        public long IDLE_TIMEOUT;

        /**
         * Maximum idle duration we will be allowed to use.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MAX_IDLE_TIMEOUT
         */
        public long MAX_IDLE_TIMEOUT;

        /**
         * Scaling factor to apply to current idle timeout each time we cycle through that state.
          * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_IDLE_FACTOR
         */
        public float IDLE_FACTOR;

        /**
         * This is the minimum time we will allow until the next upcoming alarm for us to
         * actually go in to idle mode.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MIN_TIME_TO_ALARM
         */
        public long MIN_TIME_TO_ALARM;

        /**
         * Max amount of time to temporarily whitelist an app when it receives a high priority
         * tickle.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MAX_TEMP_APP_WHITELIST_DURATION
         */
        public long MAX_TEMP_APP_WHITELIST_DURATION;

        /**
         * Amount of time we would like to whitelist an app that is receiving an MMS.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MMS_TEMP_APP_WHITELIST_DURATION
         */
        public long MMS_TEMP_APP_WHITELIST_DURATION;

        /**
         * Amount of time we would like to whitelist an app that is receiving an SMS.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_SMS_TEMP_APP_WHITELIST_DURATION
         */
        public long SMS_TEMP_APP_WHITELIST_DURATION;

        private final ContentResolver mResolver;
        private final KeyValueListParser mParser = new KeyValueListParser(',');

        public Constants(Handler handler, ContentResolver resolver) {
            super(handler);
            mResolver = resolver;
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.DEVICE_IDLE_CONSTANTS), false, this);
            updateConstants();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            synchronized (DeviceIdleController.this) {
                try {
                    mParser.setString(Settings.Global.getString(mResolver,
                            Settings.Global.DEVICE_IDLE_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad device idle settings", e);
                }

                INACTIVE_TIMEOUT = mParser.getLong(KEY_INACTIVE_TIMEOUT,
                        !COMPRESS_TIME ? 30 * 60 * 1000L : 3 * 60 * 1000L);
                SENSING_TIMEOUT = mParser.getLong(KEY_SENSING_TIMEOUT,
                        !DEBUG ? 5 * 60 * 1000L : 60 * 1000L);
                MOTION_INACTIVE_TIMEOUT = mParser.getLong(KEY_MOTION_INACTIVE_TIMEOUT,
                        !COMPRESS_TIME ? 10 * 60 * 1000L : 60 * 1000L);
                IDLE_AFTER_INACTIVE_TIMEOUT = mParser.getLong(KEY_IDLE_AFTER_INACTIVE_TIMEOUT,
                        !COMPRESS_TIME ? 30 * 60 * 1000L : 3 * 60 * 1000L);
                IDLE_PENDING_TIMEOUT = mParser.getLong(KEY_IDLE_PENDING_TIMEOUT,
                        !COMPRESS_TIME ? 5 * 60 * 1000L : 30 * 1000L);
                MAX_IDLE_PENDING_TIMEOUT = mParser.getLong(KEY_MAX_IDLE_PENDING_TIMEOUT,
                        !COMPRESS_TIME ? 10 * 60 * 1000L : 60 * 1000L);
                IDLE_PENDING_FACTOR = mParser.getFloat(KEY_IDLE_PENDING_FACTOR,
                        2f);
                IDLE_TIMEOUT = mParser.getLong(KEY_IDLE_TIMEOUT,
                        !COMPRESS_TIME ? 60 * 60 * 1000L : 6 * 60 * 1000L);
                MAX_IDLE_TIMEOUT = mParser.getLong(KEY_MAX_IDLE_TIMEOUT,
                        !COMPRESS_TIME ? 6 * 60 * 60 * 1000L : 30 * 60 * 1000L);
                IDLE_FACTOR = mParser.getFloat(KEY_IDLE_FACTOR,
                        2f);
                MIN_TIME_TO_ALARM = mParser.getLong(KEY_MIN_TIME_TO_ALARM,
                        !COMPRESS_TIME ? 60 * 60 * 1000L : 6 * 60 * 1000L);
                MAX_TEMP_APP_WHITELIST_DURATION = mParser.getLong(
                        KEY_MAX_TEMP_APP_WHITELIST_DURATION, 5 * 60 * 1000L);
                MMS_TEMP_APP_WHITELIST_DURATION = mParser.getLong(
                        KEY_MMS_TEMP_APP_WHITELIST_DURATION, 60 * 1000L);
                SMS_TEMP_APP_WHITELIST_DURATION = mParser.getLong(
                        KEY_SMS_TEMP_APP_WHITELIST_DURATION, 20 * 1000L);
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings:");

            pw.print("    "); pw.print(KEY_INACTIVE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(INACTIVE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_SENSING_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(SENSING_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MOTION_INACTIVE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(MOTION_INACTIVE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_IDLE_AFTER_INACTIVE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(IDLE_AFTER_INACTIVE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_IDLE_PENDING_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(IDLE_PENDING_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MAX_IDLE_PENDING_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(MAX_IDLE_PENDING_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_IDLE_PENDING_FACTOR); pw.print("=");
            pw.println(IDLE_PENDING_FACTOR);

            pw.print("    "); pw.print(KEY_IDLE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(IDLE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MAX_IDLE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(MAX_IDLE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_IDLE_FACTOR); pw.print("=");
            pw.println(IDLE_FACTOR);

            pw.print("    "); pw.print(KEY_MIN_TIME_TO_ALARM); pw.print("=");
            TimeUtils.formatDuration(MIN_TIME_TO_ALARM, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MAX_TEMP_APP_WHITELIST_DURATION); pw.print("=");
            TimeUtils.formatDuration(MAX_TEMP_APP_WHITELIST_DURATION, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MMS_TEMP_APP_WHITELIST_DURATION); pw.print("=");
            TimeUtils.formatDuration(MMS_TEMP_APP_WHITELIST_DURATION, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_SMS_TEMP_APP_WHITELIST_DURATION); pw.print("=");
            TimeUtils.formatDuration(SMS_TEMP_APP_WHITELIST_DURATION, pw);
            pw.println();
        }
    }

    private Constants mConstants;

    @Override
    public void onAnyMotionResult(int result) {
        if (DEBUG) Slog.d(TAG, "onAnyMotionResult(" + result + ")");
        if (mState == STATE_SENSING) {
            if (result == AnyMotionDetector.RESULT_STATIONARY) {
                if (DEBUG) Slog.d(TAG, "RESULT_STATIONARY received.");
                synchronized (this) {
                    stepIdleStateLocked();
                }
            } else if (result == AnyMotionDetector.RESULT_MOVED) {
                if (DEBUG) Slog.d(TAG, "RESULT_MOVED received.");
                synchronized (this) {
                    EventLogTags.writeDeviceIdle(mState, "sense_moved");
                    enterInactiveStateLocked();
                }
            }
        }
    }

    static final int MSG_WRITE_CONFIG = 1;
    static final int MSG_REPORT_IDLE_ON = 2;
    static final int MSG_REPORT_IDLE_OFF = 3;
    static final int MSG_REPORT_ACTIVE = 4;
    static final int MSG_TEMP_APP_WHITELIST_TIMEOUT = 5;

    final class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }

        @Override public void handleMessage(Message msg) {
            if (DEBUG) Slog.d(TAG, "handleMessage(" + msg.what + ")");
            switch (msg.what) {
                case MSG_WRITE_CONFIG: {
                    handleWriteConfigFile();
                } break;
                case MSG_REPORT_IDLE_ON: {
                    EventLogTags.writeDeviceIdleOnStart();
                    mLocalPowerManager.setDeviceIdleMode(true);
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(true);
                        mBatteryStats.noteDeviceIdleMode(true, null, Process.myUid());
                    } catch (RemoteException e) {
                    }
                    getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL);
                    EventLogTags.writeDeviceIdleOnComplete();
                } break;
                case MSG_REPORT_IDLE_OFF: {
                    EventLogTags.writeDeviceIdleOffStart("unknown");
                    mLocalPowerManager.setDeviceIdleMode(false);
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(false);
                        mBatteryStats.noteDeviceIdleMode(false, null, Process.myUid());
                    } catch (RemoteException e) {
                    }
                    getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL);
                    EventLogTags.writeDeviceIdleOffComplete();
                } break;
                case MSG_REPORT_ACTIVE: {
                    String activeReason = (String)msg.obj;
                    int activeUid = msg.arg1;
                    boolean needBroadcast = msg.arg2 != 0;
                    EventLogTags.writeDeviceIdleOffStart(
                            activeReason != null ? activeReason : "unknown");
                    mLocalPowerManager.setDeviceIdleMode(false);
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(false);
                        mBatteryStats.noteDeviceIdleMode(false, activeReason, activeUid);
                    } catch (RemoteException e) {
                    }
                    if (needBroadcast) {
                        getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL);
                    }
                    EventLogTags.writeDeviceIdleOffComplete();
                } break;
                case MSG_TEMP_APP_WHITELIST_TIMEOUT: {
                    int uid = msg.arg1;
                    checkTempAppWhitelistTimeout(uid);
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

        @Override public String[] getSystemPowerWhitelistExceptIdle() {
            return getSystemPowerWhitelistExceptIdleInternal();
        }

        @Override public String[] getSystemPowerWhitelist() {
            return getSystemPowerWhitelistInternal();
        }

        @Override public String[] getFullPowerWhitelistExceptIdle() {
            return getFullPowerWhitelistExceptIdleInternal();
        }

        @Override public String[] getFullPowerWhitelist() {
            return getFullPowerWhitelistInternal();
        }

        @Override public int[] getAppIdWhitelistExceptIdle() {
            return getAppIdWhitelistExceptIdleInternal();
        }

        @Override public int[] getAppIdWhitelist() {
            return getAppIdWhitelistInternal();
        }

        @Override public int[] getAppIdTempWhitelist() {
            return getAppIdTempWhitelistInternal();
        }

        @Override public boolean isPowerSaveWhitelistExceptIdleApp(String name) {
            return isPowerSaveWhitelistExceptIdleAppInternal(name);
        }

        @Override public boolean isPowerSaveWhitelistApp(String name) {
            return isPowerSaveWhitelistAppInternal(name);
        }

        @Override public void addPowerSaveTempWhitelistApp(String packageName, long duration,
                int userId, String reason) throws RemoteException {
            getContext().enforceCallingPermission(
                    Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                    "No permission to change device idle whitelist");
            final int callingUid = Binder.getCallingUid();
            userId = ActivityManagerNative.getDefault().handleIncomingUser(
                    Binder.getCallingPid(),
                    callingUid,
                    userId,
                    /*allowAll=*/ false,
                    /*requireFull=*/ false,
                    "addPowerSaveTempWhitelistApp", null);
            final long token = Binder.clearCallingIdentity();
            try {
                DeviceIdleController.this.addPowerSaveTempWhitelistAppInternal(callingUid,
                        packageName, duration, userId, true, reason);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override public long addPowerSaveTempWhitelistAppForMms(String packageName,
                int userId, String reason) throws RemoteException {
            long duration = mConstants.MMS_TEMP_APP_WHITELIST_DURATION;
            addPowerSaveTempWhitelistApp(packageName, duration, userId, reason);
            return duration;
        }

        @Override public long addPowerSaveTempWhitelistAppForSms(String packageName,
                int userId, String reason) throws RemoteException {
            long duration = mConstants.SMS_TEMP_APP_WHITELIST_DURATION;
            addPowerSaveTempWhitelistApp(packageName, duration, userId, reason);
            return duration;
        }

        @Override public void exitIdle(String reason) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            exitIdleInternal(reason);
        }

        @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            DeviceIdleController.this.dump(fd, pw, args);
        }
    }

    public final class LocalService {
        public void addPowerSaveTempWhitelistAppDirect(int appId, long duration, boolean sync,
                String reason) {
            addPowerSaveTempWhitelistAppDirectInternal(0, appId, duration, sync, reason);
        }

        public void setNetworkPolicyTempWhitelistCallback(Runnable callback) {
            setNetworkPolicyTempWhitelistCallbackInternal(callback);
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
            mEnabled = getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_enableAutoPowerModes);
            SystemConfig sysConfig = SystemConfig.getInstance();
            ArraySet<String> allowPowerExceptIdle = sysConfig.getAllowInPowerSaveExceptIdle();
            for (int i=0; i<allowPowerExceptIdle.size(); i++) {
                String pkg = allowPowerExceptIdle.valueAt(i);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if ((ai.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        int appid = UserHandle.getAppId(ai.uid);
                        mPowerSaveWhitelistAppsExceptIdle.put(ai.packageName, appid);
                        mPowerSaveWhitelistSystemAppIdsExceptIdle.put(appid, true);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
            ArraySet<String> allowPower = sysConfig.getAllowInPowerSave();
            for (int i=0; i<allowPower.size(); i++) {
                String pkg = allowPower.valueAt(i);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if ((ai.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        int appid = UserHandle.getAppId(ai.uid);
                        // These apps are on both the whitelist-except-idle as well
                        // as the full whitelist, so they apply in all cases.
                        mPowerSaveWhitelistAppsExceptIdle.put(ai.packageName, appid);
                        mPowerSaveWhitelistSystemAppIdsExceptIdle.put(appid, true);
                        mPowerSaveWhitelistApps.put(ai.packageName, appid);
                        mPowerSaveWhitelistSystemAppIds.put(appid, true);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }

            mConstants = new Constants(mHandler, getContext().getContentResolver());

            readConfigFileLocked();
            updateWhitelistAppIdsLocked();

            mScreenOn = true;
            // Start out assuming we are charging.  If we aren't, we will at least get
            // a battery update the next time the level drops.
            mCharging = true;
            mState = STATE_ACTIVE;
            mInactiveTimeout = mConstants.INACTIVE_TIMEOUT;
        }

        publishBinderService(Context.DEVICE_IDLE_CONTROLLER, new BinderService());
        publishLocalService(LocalService.class, new LocalService());
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
                mAnyMotionDetector = new AnyMotionDetector(
                        mAlarmManager,
                        (PowerManager) getContext().getSystemService(Context.POWER_SERVICE),
                        mHandler, mSensorManager, this);

                Intent intent = new Intent(ACTION_STEP_IDLE_STATE)
                        .setPackage("android")
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mAlarmIntent = PendingIntent.getBroadcast(getContext(), 0, intent, 0);

                Intent intentSensing = new Intent(ACTION_STEP_IDLE_STATE)
                        .setPackage("android")
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mSensingAlarmIntent = PendingIntent.getBroadcast(getContext(), 0, intentSensing, 0);

                mIdleIntent = new Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                mIdleIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);

                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_BATTERY_CHANGED);
                filter.addAction(ACTION_STEP_IDLE_STATE);
                getContext().registerReceiver(mReceiver, filter);

                mLocalPowerManager.setDeviceIdleWhitelist(mPowerSaveWhitelistAllAppIdArray);

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

    public String[] getSystemPowerWhitelistExceptIdleInternal() {
        synchronized (this) {
            int size = mPowerSaveWhitelistAppsExceptIdle.size();
            String[] apps = new String[size];
            for (int i = 0; i < size; i++) {
                apps[i] = mPowerSaveWhitelistAppsExceptIdle.keyAt(i);
            }
            return apps;
        }
    }

    public String[] getSystemPowerWhitelistInternal() {
        synchronized (this) {
            int size = mPowerSaveWhitelistApps.size();
            String[] apps = new String[size];
            for (int i = 0; i < size; i++) {
                apps[i] = mPowerSaveWhitelistApps.keyAt(i);
            }
            return apps;
        }
    }

    public String[] getFullPowerWhitelistExceptIdleInternal() {
        synchronized (this) {
            int size = mPowerSaveWhitelistAppsExceptIdle.size() + mPowerSaveWhitelistUserApps.size();
            String[] apps = new String[size];
            int cur = 0;
            for (int i = 0; i < mPowerSaveWhitelistAppsExceptIdle.size(); i++) {
                apps[cur] = mPowerSaveWhitelistAppsExceptIdle.keyAt(i);
                cur++;
            }
            for (int i = 0; i < mPowerSaveWhitelistUserApps.size(); i++) {
                apps[cur] = mPowerSaveWhitelistUserApps.keyAt(i);
                cur++;
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

    public boolean isPowerSaveWhitelistExceptIdleAppInternal(String packageName) {
        synchronized (this) {
            return mPowerSaveWhitelistAppsExceptIdle.containsKey(packageName)
                    || mPowerSaveWhitelistUserApps.containsKey(packageName);
        }
    }

    public boolean isPowerSaveWhitelistAppInternal(String packageName) {
        synchronized (this) {
            return mPowerSaveWhitelistApps.containsKey(packageName)
                    || mPowerSaveWhitelistUserApps.containsKey(packageName);
        }
    }

    public int[] getAppIdWhitelistExceptIdleInternal() {
        synchronized (this) {
            return mPowerSaveWhitelistExceptIdleAppIdArray;
        }
    }

    public int[] getAppIdWhitelistInternal() {
        synchronized (this) {
            return mPowerSaveWhitelistAllAppIdArray;
        }
    }

    public int[] getAppIdTempWhitelistInternal() {
        synchronized (this) {
            return mTempWhitelistAppIdArray;
        }
    }

    /**
     * Adds an app to the temporary whitelist and resets the endTime for granting the
     * app an exemption to access network and acquire wakelocks.
     */
    public void addPowerSaveTempWhitelistAppInternal(int callingUid, String packageName,
            long duration, int userId, boolean sync, String reason) {
        try {
            int uid = getContext().getPackageManager().getPackageUid(packageName, userId);
            int appId = UserHandle.getAppId(uid);
            addPowerSaveTempWhitelistAppDirectInternal(callingUid, appId, duration, sync, reason);
        } catch (NameNotFoundException e) {
        }
    }

    /**
     * Adds an app to the temporary whitelist and resets the endTime for granting the
     * app an exemption to access network and acquire wakelocks.
     */
    public void addPowerSaveTempWhitelistAppDirectInternal(int callingUid, int appId,
            long duration, boolean sync, String reason) {
        final long timeNow = SystemClock.elapsedRealtime();
        Runnable networkPolicyTempWhitelistCallback = null;
        synchronized (this) {
            int callingAppId = UserHandle.getAppId(callingUid);
            if (callingAppId >= Process.FIRST_APPLICATION_UID) {
                if (!mPowerSaveWhitelistSystemAppIds.get(callingAppId)) {
                    throw new SecurityException("Calling app " + UserHandle.formatUid(callingUid)
                            + " is not on whitelist");
                }
            }
            duration = Math.min(duration, mConstants.MAX_TEMP_APP_WHITELIST_DURATION);
            Pair<MutableLong, String> entry = mTempWhitelistAppIdEndTimes.get(appId);
            final boolean newEntry = entry == null;
            // Set the new end time
            if (newEntry) {
                entry = new Pair<>(new MutableLong(0), reason);
                mTempWhitelistAppIdEndTimes.put(appId, entry);
            }
            entry.first.value = timeNow + duration;
            if (DEBUG) {
                Slog.d(TAG, "Adding AppId " + appId + " to temp whitelist");
            }
            if (newEntry) {
                // No pending timeout for the app id, post a delayed message
                try {
                    mBatteryStats.noteEvent(BatteryStats.HistoryItem.EVENT_TEMP_WHITELIST_START,
                            reason, appId);
                } catch (RemoteException e) {
                }
                postTempActiveTimeoutMessage(appId, duration);
                updateTempWhitelistAppIdsLocked();
                if (mNetworkPolicyTempWhitelistCallback != null) {
                    if (!sync) {
                        mHandler.post(mNetworkPolicyTempWhitelistCallback);
                    } else {
                        networkPolicyTempWhitelistCallback = mNetworkPolicyTempWhitelistCallback;
                    }
                }
                reportTempWhitelistChangedLocked();
            }
        }
        if (networkPolicyTempWhitelistCallback != null) {
            networkPolicyTempWhitelistCallback.run();
        }
    }

    public void setNetworkPolicyTempWhitelistCallbackInternal(Runnable callback) {
        synchronized (this) {
            mNetworkPolicyTempWhitelistCallback = callback;
        }
    }

    private void postTempActiveTimeoutMessage(int uid, long delay) {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_TEMP_APP_WHITELIST_TIMEOUT, uid, 0),
                delay);
    }

    void checkTempAppWhitelistTimeout(int uid) {
        final long timeNow = SystemClock.elapsedRealtime();
        synchronized (this) {
            Pair<MutableLong, String> entry = mTempWhitelistAppIdEndTimes.get(uid);
            if (entry == null) {
                // Nothing to do
                return;
            }
            if (timeNow >= entry.first.value) {
                mTempWhitelistAppIdEndTimes.delete(uid);
                if (DEBUG) {
                    Slog.d(TAG, "Removing UID " + uid + " from temp whitelist");
                }
                updateTempWhitelistAppIdsLocked();
                if (mNetworkPolicyTempWhitelistCallback != null) {
                    mHandler.post(mNetworkPolicyTempWhitelistCallback);
                }
                reportTempWhitelistChangedLocked();
                try {
                    mBatteryStats.noteEvent(BatteryStats.HistoryItem.EVENT_TEMP_WHITELIST_FINISH,
                            entry.second, uid);
                } catch (RemoteException e) {
                }
            } else {
                // Need more time
                postTempActiveTimeoutMessage(uid, entry.first.value - timeNow);
            }
        }
    }

    public void exitIdleInternal(String reason) {
        synchronized (this) {
            becomeActiveLocked(reason, Binder.getCallingUid());
        }
    }

    void updateDisplayLocked() {
        mCurDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        // We consider any situation where the display is showing something to be it on,
        // because if there is anything shown we are going to be updating it at some
        // frequency so can't be allowed to go into deep sleeps.
        boolean screenOn = mCurDisplay.getState() != Display.STATE_OFF;;
        if (DEBUG) Slog.d(TAG, "updateDisplayLocked: screenOn=" + screenOn);
        if (!screenOn && mScreenOn) {
            mScreenOn = false;
            if (!mForceIdle) {
                becomeInactiveIfAppropriateLocked();
            }
        } else if (screenOn) {
            mScreenOn = true;
            if (!mForceIdle) {
                becomeActiveLocked("screen", Process.myUid());
            }
        }
    }

    void updateChargingLocked(boolean charging) {
        if (DEBUG) Slog.i(TAG, "updateChargingLocked: charging=" + charging);
        if (!charging && mCharging) {
            mCharging = false;
            if (!mForceIdle) {
                becomeInactiveIfAppropriateLocked();
            }
        } else if (charging) {
            mCharging = charging;
            if (!mForceIdle) {
                becomeActiveLocked("charging", Process.myUid());
            }
        }
    }

    void scheduleReportActiveLocked(String activeReason, int activeUid) {
        Message msg = mHandler.obtainMessage(MSG_REPORT_ACTIVE, activeUid,
                mState == STATE_IDLE ? 1 : 0, activeReason);
        mHandler.sendMessage(msg);
    }

    void becomeActiveLocked(String activeReason, int activeUid) {
        if (DEBUG) Slog.i(TAG, "becomeActiveLocked, reason = " + activeReason);
        if (mState != STATE_ACTIVE) {
            EventLogTags.writeDeviceIdle(STATE_ACTIVE, activeReason);
            scheduleReportActiveLocked(activeReason, activeUid);
            mState = STATE_ACTIVE;
            mInactiveTimeout = mConstants.INACTIVE_TIMEOUT;
            mNextIdlePendingDelay = 0;
            mNextIdleDelay = 0;
            cancelAlarmLocked();
            stopMonitoringSignificantMotion();
        }
    }

    void becomeInactiveIfAppropriateLocked() {
        if (DEBUG) Slog.d(TAG, "becomeInactiveIfAppropriateLocked()");
        if (((!mScreenOn && !mCharging) || mForceIdle) && mEnabled && mState == STATE_ACTIVE) {
            // Screen has turned off; we are now going to become inactive and start
            // waiting to see if we will ultimately go idle.
            mState = STATE_INACTIVE;
            if (DEBUG) Slog.d(TAG, "Moved from STATE_ACTIVE to STATE_INACTIVE");
            mNextIdlePendingDelay = 0;
            mNextIdleDelay = 0;
            scheduleAlarmLocked(mInactiveTimeout, false);
            EventLogTags.writeDeviceIdle(mState, "no activity");
        }
    }

    /**
     * This is called when we've failed to receive a callback from AnyMotionDetector
     * within the DEFAULT_SENSING_TIMEOUT, to return to STATE_INACTIVE.
     */
    void enterInactiveStateLocked() {
        mInactiveTimeout = mConstants.INACTIVE_TIMEOUT;
        becomeInactiveIfAppropriateLocked();
    }

    void exitForceIdleLocked() {
        if (mForceIdle) {
            mForceIdle = false;
            if (mScreenOn || mCharging) {
                becomeActiveLocked("exit-force-idle", Process.myUid());
            }
        }
    }

    void stepIdleStateLocked() {
        if (DEBUG) Slog.d(TAG, "stepIdleStateLocked: mState=" + mState);
        EventLogTags.writeDeviceIdleStep();

        final long now = SystemClock.elapsedRealtime();
        if ((now+mConstants.MIN_TIME_TO_ALARM) > mAlarmManager.getNextWakeFromIdleTime()) {
            // Whoops, there is an upcoming alarm.  We don't actually want to go idle.
            if (mState != STATE_ACTIVE) {
                becomeActiveLocked("alarm", Process.myUid());
            }
            return;
        }

        switch (mState) {
            case STATE_INACTIVE:
                // We have now been inactive long enough, it is time to start looking
                // for significant motion and sleep some more while doing so.
                startMonitoringSignificantMotion();
                scheduleAlarmLocked(mConstants.IDLE_AFTER_INACTIVE_TIMEOUT, false);
                // Reset the upcoming idle delays.
                mNextIdlePendingDelay = mConstants.IDLE_PENDING_TIMEOUT;
                mNextIdleDelay = mConstants.IDLE_TIMEOUT;
                mState = STATE_IDLE_PENDING;
                if (DEBUG) Slog.d(TAG, "Moved from STATE_INACTIVE to STATE_IDLE_PENDING.");
                EventLogTags.writeDeviceIdle(mState, "step");
                break;
            case STATE_IDLE_PENDING:
                mState = STATE_SENSING;
                if (DEBUG) Slog.d(TAG, "Moved from STATE_IDLE_PENDING to STATE_SENSING.");
                scheduleSensingAlarmLocked(mConstants.SENSING_TIMEOUT);
                mAnyMotionDetector.checkForAnyMotion();
                break;
            case STATE_SENSING:
                cancelSensingAlarmLocked();
            case STATE_IDLE_MAINTENANCE:
                scheduleAlarmLocked(mNextIdleDelay, true);
                if (DEBUG) Slog.d(TAG, "Moved to STATE_IDLE. Next alarm in " + mNextIdleDelay +
                        " ms.");
                mNextIdleDelay = (long)(mNextIdleDelay * mConstants.IDLE_FACTOR);
                if (DEBUG) Slog.d(TAG, "Setting mNextIdleDelay = " + mNextIdleDelay);
                mNextIdleDelay = Math.min(mNextIdleDelay, mConstants.MAX_IDLE_TIMEOUT);
                mState = STATE_IDLE;
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_ON);
                break;
            case STATE_IDLE:
                // We have been idling long enough, now it is time to do some work.
                scheduleAlarmLocked(mNextIdlePendingDelay, false);
                if (DEBUG) Slog.d(TAG, "Moved from STATE_IDLE to STATE_IDLE_MAINTENANCE. " +
                        "Next alarm in " + mNextIdlePendingDelay + " ms.");
                mNextIdlePendingDelay = Math.min(mConstants.MAX_IDLE_PENDING_TIMEOUT,
                        (long)(mNextIdlePendingDelay * mConstants.IDLE_PENDING_FACTOR));
                mState = STATE_IDLE_MAINTENANCE;
                EventLogTags.writeDeviceIdle(mState, "step");
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_OFF);
                break;
        }
    }

    void significantMotionLocked() {
        if (DEBUG) Slog.d(TAG, "significantMotionLocked()");
        // When the sensor goes off, its trigger is automatically removed.
        mSigMotionActive = false;
        // The device is not yet active, so we want to go back to the pending idle
        // state to wait again for no motion.  Note that we only monitor for significant
        // motion after moving out of the inactive state, so no need to worry about that.
        if (mState != STATE_ACTIVE) {
            scheduleReportActiveLocked("motion", Process.myUid());
            mState = STATE_ACTIVE;
            mInactiveTimeout = mConstants.MOTION_INACTIVE_TIMEOUT;
            EventLogTags.writeDeviceIdle(mState, "motion");
            becomeInactiveIfAppropriateLocked();
        }
    }

    void startMonitoringSignificantMotion() {
        if (DEBUG) Slog.d(TAG, "startMonitoringSignificantMotion()");
        if (mSigMotionSensor != null && !mSigMotionActive) {
            mSensorManager.requestTriggerSensor(mSigMotionListener, mSigMotionSensor);
            mSigMotionActive = true;
        }
    }

    void stopMonitoringSignificantMotion() {
        if (DEBUG) Slog.d(TAG, "stopMonitoringSignificantMotion()");
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

    void cancelSensingAlarmLocked() {
        if (DEBUG) Slog.d(TAG, "cancelSensingAlarmLocked()");
        mAlarmManager.cancel(mSensingAlarmIntent);
    }

    void scheduleAlarmLocked(long delay, boolean idleUntil) {
        if (DEBUG) Slog.d(TAG, "scheduleAlarmLocked(" + delay + ", " + idleUntil + ")");
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

    void scheduleSensingAlarmLocked(long delay) {
      if (DEBUG) Slog.d(TAG, "scheduleSensingAlarmLocked(" + delay + ")");
      mNextAlarmTime = SystemClock.elapsedRealtime() + delay;
      mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
          mNextAlarmTime, mSensingAlarmIntent);
    }

    private static int[] buildAppIdArray(ArrayMap<String, Integer> systemApps,
            ArrayMap<String, Integer> userApps, SparseBooleanArray outAppIds) {
        outAppIds.clear();
        for (int i=0; i<systemApps.size(); i++) {
            outAppIds.put(systemApps.valueAt(i), true);
        }
        for (int i=0; i<userApps.size(); i++) {
            outAppIds.put(userApps.valueAt(i), true);
        }
        int size = outAppIds.size();
        int[] appids = new int[size];
        for (int i = 0; i < size; i++) {
            appids[i] = outAppIds.keyAt(i);
        }
        return appids;
    }

    private void updateWhitelistAppIdsLocked() {
        mPowerSaveWhitelistExceptIdleAppIdArray = buildAppIdArray(mPowerSaveWhitelistAppsExceptIdle,
                mPowerSaveWhitelistUserApps, mPowerSaveWhitelistExceptIdleAppIds);
        mPowerSaveWhitelistAllAppIdArray = buildAppIdArray(mPowerSaveWhitelistApps,
                mPowerSaveWhitelistUserApps, mPowerSaveWhitelistAllAppIds);
        if (mLocalPowerManager != null) {
            if (DEBUG) {
                Slog.d(TAG, "Setting wakelock whitelist to "
                        + Arrays.toString(mPowerSaveWhitelistAllAppIdArray));
            }
            mLocalPowerManager.setDeviceIdleWhitelist(mPowerSaveWhitelistAllAppIdArray);
        }
    }

    private void updateTempWhitelistAppIdsLocked() {
        final int size = mTempWhitelistAppIdEndTimes.size();
        if (mTempWhitelistAppIdArray.length != size) {
            mTempWhitelistAppIdArray = new int[size];
        }
        for (int i = 0; i < size; i++) {
            mTempWhitelistAppIdArray[i] = mTempWhitelistAppIdEndTimes.keyAt(i);
        }
        if (mLocalPowerManager != null) {
            if (DEBUG) {
                Slog.d(TAG, "Setting wakelock temp whitelist to "
                        + Arrays.toString(mTempWhitelistAppIdArray));
            }
            mLocalPowerManager.setDeviceIdleTempWhitelist(mTempWhitelistAppIdArray);
        }
    }

    private void reportPowerSaveWhitelistChangedLocked() {
        Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        getContext().sendBroadcastAsUser(intent, UserHandle.OWNER);
    }

    private void reportTempWhitelistChangedLocked() {
        Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        getContext().sendBroadcastAsUser(intent, UserHandle.OWNER);
    }

    void readConfigFileLocked() {
        if (DEBUG) Slog.d(TAG, "Reading config from " + mConfigFile.getBaseFile());
        mPowerSaveWhitelistUserApps.clear();
        FileInputStream stream;
        try {
            stream = mConfigFile.openRead();
        } catch (FileNotFoundException e) {
            return;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
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
                out.setOutput(memStream, StandardCharsets.UTF_8.name());
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
        pw.println("  force-idle");
        pw.println("    Force directly into idle mode, regardless of other device state.");
        pw.println("    Use \"step\" to get out.");
        pw.println("  disable");
        pw.println("    Completely disable device idle mode.");
        pw.println("  enable");
        pw.println("    Re-enable device idle mode after it had previously been disabled.");
        pw.println("  enabled");
        pw.println("    Print 1 if device idle mode is currently enabled, else 0.");
        pw.println("  whitelist");
        pw.println("    Print currently whitelisted apps.");
        pw.println("  whitelist [package ...]");
        pw.println("    Add (prefix with +) or remove (prefix with -) packages.");
        pw.println("  tempwhitelist [package ..]");
        pw.println("    Temporarily place packages in whitelist for 10 seconds.");
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
            int userId = UserHandle.USER_OWNER;
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-u".equals(arg)) {
                    i++;
                    if (i < args.length) {
                        arg = args[i];
                        userId = Integer.parseInt(arg);
                    }
                } else if ("-a".equals(arg)) {
                    // Ignore, we always dump all.
                } else if ("step".equals(arg)) {
                    synchronized (this) {
                        long token = Binder.clearCallingIdentity();
                        try {
                            exitForceIdleLocked();
                            stepIdleStateLocked();
                            pw.print("Stepped to: "); pw.println(stateToString(mState));
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                    return;
                } else if ("force-idle".equals(arg)) {
                    synchronized (this) {
                        long token = Binder.clearCallingIdentity();
                        try {
                            if (!mEnabled) {
                                pw.println("Unable to go idle; not enabled");
                                return;
                            }
                            mForceIdle = true;
                            becomeInactiveIfAppropriateLocked();
                            int curState = mState;
                            while (curState != STATE_IDLE) {
                                stepIdleStateLocked();
                                if (curState == mState) {
                                    pw.print("Unable to go idle; stopped at ");
                                    pw.println(stateToString(mState));
                                    exitForceIdleLocked();
                                    return;
                                }
                                curState = mState;
                            }
                            pw.println("Now forced in to idle mode");
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                    return;
                } else if ("disable".equals(arg)) {
                    synchronized (this) {
                        long token = Binder.clearCallingIdentity();
                        try {
                            if (mEnabled) {
                                mEnabled = false;
                                becomeActiveLocked("disabled", Process.myUid());
                                pw.println("Idle mode disabled");
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                    return;
                } else if ("enable".equals(arg)) {
                    synchronized (this) {
                        long token = Binder.clearCallingIdentity();
                        try {
                            exitForceIdleLocked();
                            if (!mEnabled) {
                                mEnabled = true;
                                becomeInactiveIfAppropriateLocked();
                                pw.println("Idle mode enabled");
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                    return;
                } else if ("enabled".equals(arg)) {
                    synchronized (this) {
                        pw.println(mEnabled ? "1" : " 0");
                    }
                    return;
                } else if ("whitelist".equals(arg)) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        i++;
                        if (i < args.length) {
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
                        } else {
                            synchronized (this) {
                                for (int j=0; j<mPowerSaveWhitelistAppsExceptIdle.size(); j++) {
                                    pw.print("system-excidle,");
                                    pw.print(mPowerSaveWhitelistAppsExceptIdle.keyAt(j));
                                    pw.print(",");
                                    pw.println(mPowerSaveWhitelistAppsExceptIdle.valueAt(j));
                                }
                                for (int j=0; j<mPowerSaveWhitelistApps.size(); j++) {
                                    pw.print("system,");
                                    pw.print(mPowerSaveWhitelistApps.keyAt(j));
                                    pw.print(",");
                                    pw.println(mPowerSaveWhitelistApps.valueAt(j));
                                }
                                for (int j=0; j<mPowerSaveWhitelistUserApps.size(); j++) {
                                    pw.print("user,");
                                    pw.print(mPowerSaveWhitelistUserApps.keyAt(j));
                                    pw.print(",");
                                    pw.println(mPowerSaveWhitelistUserApps.valueAt(j));
                                }
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return;
                } else if ("tempwhitelist".equals(arg)) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        i++;
                        if (i >= args.length) {
                            pw.println("At least one package name must be specified");
                            return;
                        }
                        while (i < args.length) {
                            arg = args[i];
                            i++;
                            addPowerSaveTempWhitelistAppInternal(0, arg, 10000L, userId, true,
                                    "shell");
                            pw.println("Added: " + arg);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
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
            mConstants.dump(pw);

            int size = mPowerSaveWhitelistAppsExceptIdle.size();
            if (size > 0) {
                pw.println("  Whitelist (except idle) system apps:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.println(mPowerSaveWhitelistAppsExceptIdle.keyAt(i));
                }
            }
            size = mPowerSaveWhitelistApps.size();
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
            size = mPowerSaveWhitelistExceptIdleAppIds.size();
            if (size > 0) {
                pw.println("  Whitelist (except idle) all app ids:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.print(mPowerSaveWhitelistExceptIdleAppIds.keyAt(i));
                    pw.println();
                }
            }
            size = mPowerSaveWhitelistAllAppIds.size();
            if (size > 0) {
                pw.println("  Whitelist all app ids:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.print(mPowerSaveWhitelistAllAppIds.keyAt(i));
                    pw.println();
                }
            }
            size = mTempWhitelistAppIdEndTimes.size();
            if (size > 0) {
                pw.println("  Temp whitelist schedule:");
                final long timeNow = SystemClock.elapsedRealtime();
                for (int i = 0; i < size; i++) {
                    pw.print("    UID=");
                    pw.print(mTempWhitelistAppIdEndTimes.keyAt(i));
                    pw.print(": ");
                    Pair<MutableLong, String> entry = mTempWhitelistAppIdEndTimes.valueAt(i);
                    TimeUtils.formatDuration(entry.first.value, timeNow, pw);
                    pw.print(" - ");
                    pw.println(entry.second);
                }
            }
            size = mTempWhitelistAppIdArray != null ? mTempWhitelistAppIdArray.length : 0;
            if (size > 0) {
                pw.println("  Temp whitelist app ids:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.print(mTempWhitelistAppIdArray[i]);
                    pw.println();
                }
            }

            pw.print("  mEnabled="); pw.println(mEnabled);
            pw.print("  mForceIdle="); pw.println(mForceIdle);
            pw.print("  mSigMotionSensor="); pw.println(mSigMotionSensor);
            pw.print("  mCurDisplay="); pw.println(mCurDisplay);
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
