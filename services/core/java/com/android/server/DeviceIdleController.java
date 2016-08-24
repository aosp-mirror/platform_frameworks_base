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
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.DisplayManager;
import android.location.LocationRequest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.INetworkPolicyManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.IMaintenanceActivityListener;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCommand;
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

    private static final int EVENT_BUFFER_SIZE = 100;

    private AlarmManager mAlarmManager;
    private IBatteryStats mBatteryStats;
    private PowerManagerInternal mLocalPowerManager;
    private PowerManager mPowerManager;
    private ConnectivityService mConnectivityService;
    private AlarmManagerService.LocalService mLocalAlarmManager;
    private INetworkPolicyManager mNetworkPolicyManager;
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mMotionSensor;
    private LocationManager mLocationManager;
    private LocationRequest mLocationRequest;
    private Intent mIdleIntent;
    private Intent mLightIdleIntent;
    private Display mCurDisplay;
    private AnyMotionDetector mAnyMotionDetector;
    private boolean mLightEnabled;
    private boolean mDeepEnabled;
    private boolean mForceIdle;
    private boolean mNetworkConnected;
    private boolean mScreenOn;
    private boolean mCharging;
    private boolean mNotMoving;
    private boolean mLocating;
    private boolean mLocated;
    private boolean mHasGps;
    private boolean mHasNetworkLocation;
    private Location mLastGenericLocation;
    private Location mLastGpsLocation;

    /** Device is currently active. */
    private static final int STATE_ACTIVE = 0;
    /** Device is inactive (screen off, no motion) and we are waiting to for idle. */
    private static final int STATE_INACTIVE = 1;
    /** Device is past the initial inactive period, and waiting for the next idle period. */
    private static final int STATE_IDLE_PENDING = 2;
    /** Device is currently sensing motion. */
    private static final int STATE_SENSING = 3;
    /** Device is currently finding location (and may still be sensing). */
    private static final int STATE_LOCATING = 4;
    /** Device is in the idle state, trying to stay asleep as much as possible. */
    private static final int STATE_IDLE = 5;
    /** Device is in the idle state, but temporarily out of idle to do regular maintenance. */
    private static final int STATE_IDLE_MAINTENANCE = 6;
    private static String stateToString(int state) {
        switch (state) {
            case STATE_ACTIVE: return "ACTIVE";
            case STATE_INACTIVE: return "INACTIVE";
            case STATE_IDLE_PENDING: return "IDLE_PENDING";
            case STATE_SENSING: return "SENSING";
            case STATE_LOCATING: return "LOCATING";
            case STATE_IDLE: return "IDLE";
            case STATE_IDLE_MAINTENANCE: return "IDLE_MAINTENANCE";
            default: return Integer.toString(state);
        }
    }

    /** Device is currently active. */
    private static final int LIGHT_STATE_ACTIVE = 0;
    /** Device is inactive (screen off) and we are waiting to for the first light idle. */
    private static final int LIGHT_STATE_INACTIVE = 1;
    /** Device is about to go idle for the first time, wait for current work to complete. */
    private static final int LIGHT_STATE_PRE_IDLE = 3;
    /** Device is in the light idle state, trying to stay asleep as much as possible. */
    private static final int LIGHT_STATE_IDLE = 4;
    /** Device is in the light idle state, we want to go in to idle maintenance but are
     * waiting for network connectivity before doing so. */
    private static final int LIGHT_STATE_WAITING_FOR_NETWORK = 5;
    /** Device is in the light idle state, but temporarily out of idle to do regular maintenance. */
    private static final int LIGHT_STATE_IDLE_MAINTENANCE = 6;
    /** Device light idle state is overriden, now applying deep doze state. */
    private static final int LIGHT_STATE_OVERRIDE = 7;
    private static String lightStateToString(int state) {
        switch (state) {
            case LIGHT_STATE_ACTIVE: return "ACTIVE";
            case LIGHT_STATE_INACTIVE: return "INACTIVE";
            case LIGHT_STATE_PRE_IDLE: return "PRE_IDLE";
            case LIGHT_STATE_IDLE: return "IDLE";
            case LIGHT_STATE_WAITING_FOR_NETWORK: return "WAITING_FOR_NETWORK";
            case LIGHT_STATE_IDLE_MAINTENANCE: return "IDLE_MAINTENANCE";
            case LIGHT_STATE_OVERRIDE: return "OVERRIDE";
            default: return Integer.toString(state);
        }
    }

    private int mState;
    private int mLightState;

    private long mInactiveTimeout;
    private long mNextAlarmTime;
    private long mNextIdlePendingDelay;
    private long mNextIdleDelay;
    private long mNextLightIdleDelay;
    private long mNextLightAlarmTime;
    private long mNextSensingTimeoutAlarmTime;
    private long mCurIdleBudget;
    private long mMaintenanceStartTime;

    private int mActiveIdleOpCount;
    private PowerManager.WakeLock mActiveIdleWakeLock;
    private boolean mJobsActive;
    private boolean mAlarmsActive;
    private boolean mReportedMaintenanceActivity;

    public final AtomicFile mConfigFile;

    private final RemoteCallbackList<IMaintenanceActivityListener> mMaintenanceActivityListeners =
            new RemoteCallbackList<IMaintenanceActivityListener>();

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
     * App IDs that have been white-listed by the user to opt out of power save restrictions.
     */
    private final SparseBooleanArray mPowerSaveWhitelistUserAppIds = new SparseBooleanArray();

    /**
     * Current app IDs that are in the user power save white list.  This array can
     * be shared with others because it will not be modified once set.
     */
    private int[] mPowerSaveWhitelistUserAppIdArray = new int[0];

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

    private static final int EVENT_NULL = 0;
    private static final int EVENT_NORMAL = 1;
    private static final int EVENT_LIGHT_IDLE = 2;
    private static final int EVENT_LIGHT_MAINTENANCE = 3;
    private static final int EVENT_DEEP_IDLE = 4;
    private static final int EVENT_DEEP_MAINTENANCE = 5;

    private final int[] mEventCmds = new int[EVENT_BUFFER_SIZE];
    private final long[] mEventTimes = new long[EVENT_BUFFER_SIZE];

    private void addEvent(int cmd) {
        if (mEventCmds[0] != cmd) {
            System.arraycopy(mEventCmds, 0, mEventCmds, 1, EVENT_BUFFER_SIZE - 1);
            System.arraycopy(mEventTimes, 0, mEventTimes, 1, EVENT_BUFFER_SIZE - 1);
            mEventCmds[0] = cmd;
            mEventTimes[0] = SystemClock.elapsedRealtime();
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ConnectivityManager.CONNECTIVITY_ACTION: {
                    updateConnectivityState(intent);
                } break;
                case Intent.ACTION_BATTERY_CHANGED: {
                    synchronized (DeviceIdleController.this) {
                        int plugged = intent.getIntExtra("plugged", 0);
                        updateChargingLocked(plugged != 0);
                    }
                } break;
                case Intent.ACTION_PACKAGE_REMOVED: {
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        Uri data = intent.getData();
                        String ssp;
                        if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                            removePowerSaveWhitelistAppInternal(ssp);
                        }
                    }
                } break;
            }
        }
    };

    private final AlarmManager.OnAlarmListener mLightAlarmListener
            = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            synchronized (DeviceIdleController.this) {
                stepLightIdleStateLocked("s:alarm");
            }
        }
    };

    private final AlarmManager.OnAlarmListener mSensingTimeoutAlarmListener
            = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            if (mState == STATE_SENSING) {
                synchronized (DeviceIdleController.this) {
                    becomeInactiveIfAppropriateLocked();
                }
            }
        }
    };

    private final AlarmManager.OnAlarmListener mDeepAlarmListener
            = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            synchronized (DeviceIdleController.this) {
                stepIdleStateLocked("s:alarm");
            }
        }
    };

    private final BroadcastReceiver mIdleStartedDoneReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // When coming out of a deep idle, we will add in some delay before we allow
            // the system to settle down and finish the maintenance window.  This is
            // to give a chance for any pending work to be scheduled.
            if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(intent.getAction())) {
                mHandler.sendEmptyMessageDelayed(MSG_FINISH_IDLE_OP,
                        mConstants.MIN_DEEP_MAINTENANCE_TIME);
            } else {
                mHandler.sendEmptyMessageDelayed(MSG_FINISH_IDLE_OP,
                        mConstants.MIN_LIGHT_MAINTENANCE_TIME);
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

    private final class MotionListener extends TriggerEventListener
            implements SensorEventListener {

        boolean active = false;

        @Override
        public void onTrigger(TriggerEvent event) {
            synchronized (DeviceIdleController.this) {
                active = false;
                motionLocked();
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            synchronized (DeviceIdleController.this) {
                mSensorManager.unregisterListener(this, mMotionSensor);
                active = false;
                motionLocked();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        public boolean registerLocked() {
            boolean success;
            if (mMotionSensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
                success = mSensorManager.requestTriggerSensor(mMotionListener, mMotionSensor);
            } else {
                success = mSensorManager.registerListener(
                        mMotionListener, mMotionSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (success) {
                active = true;
            } else {
                Slog.e(TAG, "Unable to register for " + mMotionSensor);
            }
            return success;
        }

        public void unregisterLocked() {
            if (mMotionSensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
                mSensorManager.cancelTriggerSensor(mMotionListener, mMotionSensor);
            } else {
                mSensorManager.unregisterListener(mMotionListener);
            }
            active = false;
        }
    }
    private final MotionListener mMotionListener = new MotionListener();

    private final LocationListener mGenericLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            synchronized (DeviceIdleController.this) {
                receivedGenericLocationLocked(location);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private final LocationListener mGpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            synchronized (DeviceIdleController.this) {
                receivedGpsLocationLocked(location);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the DeviceIdleController lock.
     */
    private final class Constants extends ContentObserver {
        // Key names stored in the settings value.
        private static final String KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT
                = "light_after_inactive_to";
        private static final String KEY_LIGHT_PRE_IDLE_TIMEOUT = "light_pre_idle_to";
        private static final String KEY_LIGHT_IDLE_TIMEOUT = "light_idle_to";
        private static final String KEY_LIGHT_IDLE_FACTOR = "light_idle_factor";
        private static final String KEY_LIGHT_MAX_IDLE_TIMEOUT = "light_max_idle_to";
        private static final String KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET
                = "light_idle_maintenance_min_budget";
        private static final String KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET
                = "light_idle_maintenance_max_budget";
        private static final String KEY_MIN_LIGHT_MAINTENANCE_TIME = "min_light_maintenance_time";
        private static final String KEY_MIN_DEEP_MAINTENANCE_TIME = "min_deep_maintenance_time";
        private static final String KEY_INACTIVE_TIMEOUT = "inactive_to";
        private static final String KEY_SENSING_TIMEOUT = "sensing_to";
        private static final String KEY_LOCATING_TIMEOUT = "locating_to";
        private static final String KEY_LOCATION_ACCURACY = "location_accuracy";
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
        private static final String KEY_NOTIFICATION_WHITELIST_DURATION =
                "notification_whitelist_duration";

        /**
         * This is the time, after becoming inactive, that we go in to the first
         * light-weight idle mode.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT
         */
        public long LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT;

        /**
         * This is amount of time we will wait from the point where we decide we would
         * like to go idle until we actually do, while waiting for jobs and other current
         * activity to finish.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LIGHT_PRE_IDLE_TIMEOUT
         */
        public long LIGHT_PRE_IDLE_TIMEOUT;

        /**
         * This is the initial time that we will run in idle maintenance mode.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LIGHT_IDLE_TIMEOUT
         */
        public long LIGHT_IDLE_TIMEOUT;

        /**
         * Scaling factor to apply to the light idle mode time each time we complete a cycle.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LIGHT_IDLE_FACTOR
         */
        public float LIGHT_IDLE_FACTOR;

        /**
         * This is the maximum time we will run in idle maintenence mode.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LIGHT_MAX_IDLE_TIMEOUT
         */
        public long LIGHT_MAX_IDLE_TIMEOUT;

        /**
         * This is the minimum amount of time we want to make available for maintenance mode
         * when lightly idling.  That is, we will always have at least this amount of time
         * available maintenance before timing out and cutting off maintenance mode.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET
         */
        public long LIGHT_IDLE_MAINTENANCE_MIN_BUDGET;

        /**
         * This is the maximum amount of time we want to make available for maintenance mode
         * when lightly idling.  That is, if the system isn't using up its minimum maintenance
         * budget and this time is being added to the budget reserve, this is the maximum
         * reserve size we will allow to grow and thus the maximum amount of time we will
         * allow for the maintenance window.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET
         */
        public long LIGHT_IDLE_MAINTENANCE_MAX_BUDGET;

        /**
         * This is the minimum amount of time that we will stay in maintenance mode after
         * a light doze.  We have this minimum to allow various things to respond to switching
         * in to maintenance mode and scheduling their work -- otherwise we may
         * see there is nothing to do (no jobs pending) and go out of maintenance
         * mode immediately.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MIN_LIGHT_MAINTENANCE_TIME
         */
        public long MIN_LIGHT_MAINTENANCE_TIME;

        /**
         * This is the minimum amount of time that we will stay in maintenance mode after
         * a full doze.  We have this minimum to allow various things to respond to switching
         * in to maintenance mode and scheduling their work -- otherwise we may
         * see there is nothing to do (no jobs pending) and go out of maintenance
         * mode immediately.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MIN_DEEP_MAINTENANCE_TIME
         */
        public long MIN_DEEP_MAINTENANCE_TIME;

        /**
         * This is the time, after becoming inactive, at which we start looking at the
         * motion sensor to determine if the device is being left alone.  We don't do this
         * immediately after going inactive just because we don't want to be continually running
         * the motion sensor whenever the screen is off.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_INACTIVE_TIMEOUT
         */
        public long INACTIVE_TIMEOUT;

        /**
         * If we don't receive a callback from AnyMotion in this amount of time +
         * {@link #LOCATING_TIMEOUT}, we will change from
         * STATE_SENSING to STATE_INACTIVE, and any AnyMotion callbacks while not in STATE_SENSING
         * will be ignored.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_SENSING_TIMEOUT
         */
        public long SENSING_TIMEOUT;

        /**
         * This is how long we will wait to try to get a good location fix before going in to
         * idle mode.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LOCATING_TIMEOUT
         */
        public long LOCATING_TIMEOUT;

        /**
         * The desired maximum accuracy (in meters) we consider the location to be good enough to go
         * on to idle.  We will be trying to get an accuracy fix at least this good or until
         * {@link #LOCATING_TIMEOUT} expires.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_LOCATION_ACCURACY
         */
        public float LOCATION_ACCURACY;

        /**
         * This is the time, after seeing motion, that we wait after becoming inactive from
         * that until we start looking for motion again.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_MOTION_INACTIVE_TIMEOUT
         */
        public long MOTION_INACTIVE_TIMEOUT;

        /**
         * This is the time, after the inactive timeout elapses, that we will wait looking
         * for motion until we truly consider the device to be idle.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_IDLE_AFTER_INACTIVE_TIMEOUT
         */
        public long IDLE_AFTER_INACTIVE_TIMEOUT;

        /**
         * This is the initial time, after being idle, that we will allow ourself to be back
         * in the IDLE_MAINTENANCE state allowing the system to run normally until we return to
         * idle.
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

        /**
         * Amount of time we would like to whitelist an app that is handling a
         * {@link android.app.PendingIntent} triggered by a {@link android.app.Notification}.
         * @see Settings.Global#DEVICE_IDLE_CONSTANTS
         * @see #KEY_NOTIFICATION_WHITELIST_DURATION
         */
        public long NOTIFICATION_WHITELIST_DURATION;

        private final ContentResolver mResolver;
        private final boolean mHasWatch;
        private final KeyValueListParser mParser = new KeyValueListParser(',');

        public Constants(Handler handler, ContentResolver resolver) {
            super(handler);
            mResolver = resolver;
            mHasWatch = getContext().getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_WATCH);
            mResolver.registerContentObserver(Settings.Global.getUriFor(
                    mHasWatch ? Settings.Global.DEVICE_IDLE_CONSTANTS_WATCH
                              : Settings.Global.DEVICE_IDLE_CONSTANTS),
                    false, this);
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
                            mHasWatch ? Settings.Global.DEVICE_IDLE_CONSTANTS_WATCH
                                      : Settings.Global.DEVICE_IDLE_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad device idle settings", e);
                }

                LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT = mParser.getLong(
                        KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT,
                        !COMPRESS_TIME ? 5 * 60 * 1000L : 15 * 1000L);
                LIGHT_PRE_IDLE_TIMEOUT = mParser.getLong(KEY_LIGHT_PRE_IDLE_TIMEOUT,
                        !COMPRESS_TIME ? 10 * 60 * 1000L : 30 * 1000L);
                LIGHT_IDLE_TIMEOUT = mParser.getLong(KEY_LIGHT_IDLE_TIMEOUT,
                        !COMPRESS_TIME ? 5 * 60 * 1000L : 15 * 1000L);
                LIGHT_IDLE_FACTOR = mParser.getFloat(KEY_LIGHT_IDLE_FACTOR,
                        2f);
                LIGHT_MAX_IDLE_TIMEOUT = mParser.getLong(KEY_LIGHT_MAX_IDLE_TIMEOUT,
                        !COMPRESS_TIME ? 15 * 60 * 1000L : 60 * 1000L);
                LIGHT_IDLE_MAINTENANCE_MIN_BUDGET = mParser.getLong(
                        KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET,
                        !COMPRESS_TIME ? 1 * 60 * 1000L : 15 * 1000L);
                LIGHT_IDLE_MAINTENANCE_MAX_BUDGET = mParser.getLong(
                        KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET,
                        !COMPRESS_TIME ? 5 * 60 * 1000L : 30 * 1000L);
                MIN_LIGHT_MAINTENANCE_TIME = mParser.getLong(
                        KEY_MIN_LIGHT_MAINTENANCE_TIME,
                        !COMPRESS_TIME ? 5 * 1000L : 1 * 1000L);
                MIN_DEEP_MAINTENANCE_TIME = mParser.getLong(
                        KEY_MIN_DEEP_MAINTENANCE_TIME,
                        !COMPRESS_TIME ? 30 * 1000L : 5 * 1000L);
                long inactiveTimeoutDefault = (mHasWatch ? 15 : 30) * 60 * 1000L;
                INACTIVE_TIMEOUT = mParser.getLong(KEY_INACTIVE_TIMEOUT,
                        !COMPRESS_TIME ? inactiveTimeoutDefault : (inactiveTimeoutDefault / 10));
                SENSING_TIMEOUT = mParser.getLong(KEY_SENSING_TIMEOUT,
                        !DEBUG ? 4 * 60 * 1000L : 60 * 1000L);
                LOCATING_TIMEOUT = mParser.getLong(KEY_LOCATING_TIMEOUT,
                        !DEBUG ? 30 * 1000L : 15 * 1000L);
                LOCATION_ACCURACY = mParser.getFloat(KEY_LOCATION_ACCURACY, 20);
                MOTION_INACTIVE_TIMEOUT = mParser.getLong(KEY_MOTION_INACTIVE_TIMEOUT,
                        !COMPRESS_TIME ? 10 * 60 * 1000L : 60 * 1000L);
                long idleAfterInactiveTimeout = (mHasWatch ? 15 : 30) * 60 * 1000L;
                IDLE_AFTER_INACTIVE_TIMEOUT = mParser.getLong(KEY_IDLE_AFTER_INACTIVE_TIMEOUT,
                        !COMPRESS_TIME ? idleAfterInactiveTimeout
                                       : (idleAfterInactiveTimeout / 10));
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
                NOTIFICATION_WHITELIST_DURATION = mParser.getLong(
                        KEY_NOTIFICATION_WHITELIST_DURATION, 30 * 1000L);
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings:");

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_PRE_IDLE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(LIGHT_PRE_IDLE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(LIGHT_IDLE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_FACTOR); pw.print("=");
            pw.print(LIGHT_IDLE_FACTOR);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_MAX_IDLE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(LIGHT_MAX_IDLE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET); pw.print("=");
            TimeUtils.formatDuration(LIGHT_IDLE_MAINTENANCE_MIN_BUDGET, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET); pw.print("=");
            TimeUtils.formatDuration(LIGHT_IDLE_MAINTENANCE_MAX_BUDGET, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MIN_LIGHT_MAINTENANCE_TIME); pw.print("=");
            TimeUtils.formatDuration(MIN_LIGHT_MAINTENANCE_TIME, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MIN_DEEP_MAINTENANCE_TIME); pw.print("=");
            TimeUtils.formatDuration(MIN_DEEP_MAINTENANCE_TIME, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_INACTIVE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(INACTIVE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_SENSING_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(SENSING_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LOCATING_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(LOCATING_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LOCATION_ACCURACY); pw.print("=");
            pw.print(LOCATION_ACCURACY); pw.print("m");
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

            pw.print("    "); pw.print(KEY_NOTIFICATION_WHITELIST_DURATION); pw.print("=");
            TimeUtils.formatDuration(NOTIFICATION_WHITELIST_DURATION, pw);
            pw.println();
        }
    }

    private Constants mConstants;

    @Override
    public void onAnyMotionResult(int result) {
        if (DEBUG) Slog.d(TAG, "onAnyMotionResult(" + result + ")");
        if (result != AnyMotionDetector.RESULT_UNKNOWN) {
            synchronized (this) {
                cancelSensingTimeoutAlarmLocked();
            }
        }
        if (result == AnyMotionDetector.RESULT_MOVED) {
            if (DEBUG) Slog.d(TAG, "RESULT_MOVED received.");
            synchronized (this) {
                handleMotionDetectedLocked(mConstants.INACTIVE_TIMEOUT, "sense_motion");
            }
        } else if (result == AnyMotionDetector.RESULT_STATIONARY) {
            if (DEBUG) Slog.d(TAG, "RESULT_STATIONARY received.");
            if (mState == STATE_SENSING) {
                // If we are currently sensing, it is time to move to locating.
                synchronized (this) {
                    mNotMoving = true;
                    stepIdleStateLocked("s:stationary");
                }
            } else if (mState == STATE_LOCATING) {
                // If we are currently locating, note that we are not moving and step
                // if we have located the position.
                synchronized (this) {
                    mNotMoving = true;
                    if (mLocated) {
                        stepIdleStateLocked("s:stationary");
                    }
                }
            }
        }
    }

    static final int MSG_WRITE_CONFIG = 1;
    static final int MSG_REPORT_IDLE_ON = 2;
    static final int MSG_REPORT_IDLE_ON_LIGHT = 3;
    static final int MSG_REPORT_IDLE_OFF = 4;
    static final int MSG_REPORT_ACTIVE = 5;
    static final int MSG_TEMP_APP_WHITELIST_TIMEOUT = 6;
    static final int MSG_REPORT_MAINTENANCE_ACTIVITY = 7;
    static final int MSG_FINISH_IDLE_OP = 8;

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
                case MSG_REPORT_IDLE_ON:
                case MSG_REPORT_IDLE_ON_LIGHT: {
                    EventLogTags.writeDeviceIdleOnStart();
                    final boolean deepChanged;
                    final boolean lightChanged;
                    if (msg.what == MSG_REPORT_IDLE_ON) {
                        deepChanged = mLocalPowerManager.setDeviceIdleMode(true);
                        lightChanged = mLocalPowerManager.setLightDeviceIdleMode(false);
                    } else {
                        deepChanged = mLocalPowerManager.setDeviceIdleMode(false);
                        lightChanged = mLocalPowerManager.setLightDeviceIdleMode(true);
                    }
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(true);
                        mBatteryStats.noteDeviceIdleMode(msg.what == MSG_REPORT_IDLE_ON
                                ? BatteryStats.DEVICE_IDLE_MODE_DEEP
                                : BatteryStats.DEVICE_IDLE_MODE_LIGHT, null, Process.myUid());
                    } catch (RemoteException e) {
                    }
                    if (deepChanged) {
                        getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL);
                    }
                    if (lightChanged) {
                        getContext().sendBroadcastAsUser(mLightIdleIntent, UserHandle.ALL);
                    }
                    EventLogTags.writeDeviceIdleOnComplete();
                } break;
                case MSG_REPORT_IDLE_OFF: {
                    EventLogTags.writeDeviceIdleOffStart("unknown");
                    final boolean deepChanged = mLocalPowerManager.setDeviceIdleMode(false);
                    final boolean lightChanged = mLocalPowerManager.setLightDeviceIdleMode(false);
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(false);
                        mBatteryStats.noteDeviceIdleMode(BatteryStats.DEVICE_IDLE_MODE_OFF,
                                null, Process.myUid());
                    } catch (RemoteException e) {
                    }
                    if (deepChanged) {
                        incActiveIdleOps();
                        getContext().sendOrderedBroadcastAsUser(mIdleIntent, UserHandle.ALL,
                                null, mIdleStartedDoneReceiver, null, 0, null, null);
                    }
                    if (lightChanged) {
                        incActiveIdleOps();
                        getContext().sendOrderedBroadcastAsUser(mLightIdleIntent, UserHandle.ALL,
                                null, mIdleStartedDoneReceiver, null, 0, null, null);
                    }
                    // Always start with one active op for the message being sent here.
                    // Now we are done!
                    decActiveIdleOps();
                    EventLogTags.writeDeviceIdleOffComplete();
                } break;
                case MSG_REPORT_ACTIVE: {
                    String activeReason = (String)msg.obj;
                    int activeUid = msg.arg1;
                    EventLogTags.writeDeviceIdleOffStart(
                            activeReason != null ? activeReason : "unknown");
                    final boolean deepChanged = mLocalPowerManager.setDeviceIdleMode(false);
                    final boolean lightChanged = mLocalPowerManager.setLightDeviceIdleMode(false);
                    try {
                        mNetworkPolicyManager.setDeviceIdleMode(false);
                        mBatteryStats.noteDeviceIdleMode(BatteryStats.DEVICE_IDLE_MODE_OFF,
                                activeReason, activeUid);
                    } catch (RemoteException e) {
                    }
                    if (deepChanged) {
                        getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL);
                    }
                    if (lightChanged) {
                        getContext().sendBroadcastAsUser(mLightIdleIntent, UserHandle.ALL);
                    }
                    EventLogTags.writeDeviceIdleOffComplete();
                } break;
                case MSG_TEMP_APP_WHITELIST_TIMEOUT: {
                    int uid = msg.arg1;
                    checkTempAppWhitelistTimeout(uid);
                } break;
                case MSG_REPORT_MAINTENANCE_ACTIVITY: {
                    boolean active = (msg.arg1 == 1);
                    final int size = mMaintenanceActivityListeners.beginBroadcast();
                    try {
                        for (int i = 0; i < size; i++) {
                            try {
                                mMaintenanceActivityListeners.getBroadcastItem(i)
                                        .onMaintenanceActivityChanged(active);
                            } catch (RemoteException ignored) {
                            }
                        }
                    } finally {
                        mMaintenanceActivityListeners.finishBroadcast();
                    }
                } break;
                case MSG_FINISH_IDLE_OP: {
                    decActiveIdleOps();
                } break;
            }
        }
    }

    final MyHandler mHandler;

    BinderService mBinderService;

    private final class BinderService extends IDeviceIdleController.Stub {
        @Override public void addPowerSaveWhitelistApp(String name) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            long ident = Binder.clearCallingIdentity();
            try {
                addPowerSaveWhitelistAppInternal(name);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override public void removePowerSaveWhitelistApp(String name) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            long ident = Binder.clearCallingIdentity();
            try {
                removePowerSaveWhitelistAppInternal(name);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override public String[] getSystemPowerWhitelistExceptIdle() {
            return getSystemPowerWhitelistExceptIdleInternal();
        }

        @Override public String[] getSystemPowerWhitelist() {
            return getSystemPowerWhitelistInternal();
        }

        @Override public String[] getUserPowerWhitelist() {
            return getUserPowerWhitelistInternal();
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

        @Override public int[] getAppIdUserWhitelist() {
            return getAppIdUserWhitelistInternal();
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
            addPowerSaveTempWhitelistAppChecked(packageName, duration, userId, reason);
        }

        @Override public long addPowerSaveTempWhitelistAppForMms(String packageName,
                int userId, String reason) throws RemoteException {
            long duration = mConstants.MMS_TEMP_APP_WHITELIST_DURATION;
            addPowerSaveTempWhitelistAppChecked(packageName, duration, userId, reason);
            return duration;
        }

        @Override public long addPowerSaveTempWhitelistAppForSms(String packageName,
                int userId, String reason) throws RemoteException {
            long duration = mConstants.SMS_TEMP_APP_WHITELIST_DURATION;
            addPowerSaveTempWhitelistAppChecked(packageName, duration, userId, reason);
            return duration;
        }

        @Override public void exitIdle(String reason) {
            getContext().enforceCallingOrSelfPermission(Manifest.permission.DEVICE_POWER,
                    null);
            long ident = Binder.clearCallingIdentity();
            try {
                exitIdleInternal(reason);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override public boolean registerMaintenanceActivityListener(
                IMaintenanceActivityListener listener) {
            return DeviceIdleController.this.registerMaintenanceActivityListener(listener);
        }

        @Override public void unregisterMaintenanceActivityListener(
                IMaintenanceActivityListener listener) {
            DeviceIdleController.this.unregisterMaintenanceActivityListener(listener);
        }

        @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            DeviceIdleController.this.dump(fd, pw, args);
        }

        @Override public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ResultReceiver resultReceiver) {
            (new Shell()).exec(this, in, out, err, args, resultReceiver);
        }
    }

    public final class LocalService {
        public void addPowerSaveTempWhitelistAppDirect(int appId, long duration, boolean sync,
                String reason) {
            addPowerSaveTempWhitelistAppDirectInternal(0, appId, duration, sync, reason);
        }

        public long getNotificationWhitelistDuration() {
            return mConstants.NOTIFICATION_WHITELIST_DURATION;
        }

        public void setNetworkPolicyTempWhitelistCallback(Runnable callback) {
            setNetworkPolicyTempWhitelistCallbackInternal(callback);
        }

        public void setJobsActive(boolean active) {
            DeviceIdleController.this.setJobsActive(active);
        }

        // Up-call from alarm manager.
        public void setAlarmsActive(boolean active) {
            DeviceIdleController.this.setAlarmsActive(active);
        }

        /**
         * Returns the array of app ids whitelisted by user. Take care not to
         * modify this, as it is a reference to the original copy. But the reference
         * can change when the list changes, so it needs to be re-acquired when
         * {@link PowerManager#ACTION_POWER_SAVE_WHITELIST_CHANGED} is sent.
         */
        public int[] getPowerSaveWhitelistUserAppIds() {
            return DeviceIdleController.this.getPowerSaveWhitelistUserAppIds();
        }
    }

    public DeviceIdleController(Context context) {
        super(context);
        mConfigFile = new AtomicFile(new File(getSystemDir(), "deviceidle.xml"));
        mHandler = new MyHandler(BackgroundThread.getHandler().getLooper());
    }

    int[] getPowerSaveWhitelistUserAppIds() {
        synchronized (this) {
            return mPowerSaveWhitelistUserAppIdArray;
        }
    }

    private static File getSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    @Override
    public void onStart() {
        final PackageManager pm = getContext().getPackageManager();

        synchronized (this) {
            mLightEnabled = mDeepEnabled = getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_enableAutoPowerModes);
            SystemConfig sysConfig = SystemConfig.getInstance();
            ArraySet<String> allowPowerExceptIdle = sysConfig.getAllowInPowerSaveExceptIdle();
            for (int i=0; i<allowPowerExceptIdle.size(); i++) {
                String pkg = allowPowerExceptIdle.valueAt(i);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg,
                            PackageManager.MATCH_SYSTEM_ONLY);
                    int appid = UserHandle.getAppId(ai.uid);
                    mPowerSaveWhitelistAppsExceptIdle.put(ai.packageName, appid);
                    mPowerSaveWhitelistSystemAppIdsExceptIdle.put(appid, true);
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
            ArraySet<String> allowPower = sysConfig.getAllowInPowerSave();
            for (int i=0; i<allowPower.size(); i++) {
                String pkg = allowPower.valueAt(i);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg,
                            PackageManager.MATCH_SYSTEM_ONLY);
                    int appid = UserHandle.getAppId(ai.uid);
                    // These apps are on both the whitelist-except-idle as well
                    // as the full whitelist, so they apply in all cases.
                    mPowerSaveWhitelistAppsExceptIdle.put(ai.packageName, appid);
                    mPowerSaveWhitelistSystemAppIdsExceptIdle.put(appid, true);
                    mPowerSaveWhitelistApps.put(ai.packageName, appid);
                    mPowerSaveWhitelistSystemAppIds.put(appid, true);
                } catch (PackageManager.NameNotFoundException e) {
                }
            }

            mConstants = new Constants(mHandler, getContext().getContentResolver());

            readConfigFileLocked();
            updateWhitelistAppIdsLocked();

            mNetworkConnected = true;
            mScreenOn = true;
            // Start out assuming we are charging.  If we aren't, we will at least get
            // a battery update the next time the level drops.
            mCharging = true;
            mState = STATE_ACTIVE;
            mLightState = LIGHT_STATE_ACTIVE;
            mInactiveTimeout = mConstants.INACTIVE_TIMEOUT;
        }

        mBinderService = new BinderService();
        publishBinderService(Context.DEVICE_IDLE_CONTROLLER, mBinderService);
        publishLocalService(LocalService.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            synchronized (this) {
                mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
                mBatteryStats = BatteryStatsService.getService();
                mLocalPowerManager = getLocalService(PowerManagerInternal.class);
                mPowerManager = getContext().getSystemService(PowerManager.class);
                mActiveIdleWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "deviceidle_maint");
                mActiveIdleWakeLock.setReferenceCounted(false);
                mConnectivityService = (ConnectivityService)ServiceManager.getService(
                        Context.CONNECTIVITY_SERVICE);
                mLocalAlarmManager = getLocalService(AlarmManagerService.LocalService.class);
                mNetworkPolicyManager = INetworkPolicyManager.Stub.asInterface(
                        ServiceManager.getService(Context.NETWORK_POLICY_SERVICE));
                mDisplayManager = (DisplayManager) getContext().getSystemService(
                        Context.DISPLAY_SERVICE);
                mSensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
                int sigMotionSensorId = getContext().getResources().getInteger(
                        com.android.internal.R.integer.config_autoPowerModeAnyMotionSensor);
                if (sigMotionSensorId > 0) {
                    mMotionSensor = mSensorManager.getDefaultSensor(sigMotionSensorId, true);
                }
                if (mMotionSensor == null && getContext().getResources().getBoolean(
                        com.android.internal.R.bool.config_autoPowerModePreferWristTilt)) {
                    mMotionSensor = mSensorManager.getDefaultSensor(
                            Sensor.TYPE_WRIST_TILT_GESTURE, true);
                }
                if (mMotionSensor == null) {
                    // As a last ditch, fall back to SMD.
                    mMotionSensor = mSensorManager.getDefaultSensor(
                            Sensor.TYPE_SIGNIFICANT_MOTION, true);
                }

                if (getContext().getResources().getBoolean(
                        com.android.internal.R.bool.config_autoPowerModePrefetchLocation)) {
                    mLocationManager = (LocationManager) getContext().getSystemService(
                            Context.LOCATION_SERVICE);
                    mLocationRequest = new LocationRequest()
                        .setQuality(LocationRequest.ACCURACY_FINE)
                        .setInterval(0)
                        .setFastestInterval(0)
                        .setNumUpdates(1);
                }

                float angleThreshold = getContext().getResources().getInteger(
                        com.android.internal.R.integer.config_autoPowerModeThresholdAngle) / 100f;
                mAnyMotionDetector = new AnyMotionDetector(
                        (PowerManager) getContext().getSystemService(Context.POWER_SERVICE),
                        mHandler, mSensorManager, this, angleThreshold);

                mIdleIntent = new Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                mIdleIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                mLightIdleIntent = new Intent(PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED);
                mLightIdleIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);

                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_BATTERY_CHANGED);
                getContext().registerReceiver(mReceiver, filter);

                filter = new IntentFilter();
                filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                filter.addDataScheme("package");
                getContext().registerReceiver(mReceiver, filter);

                filter = new IntentFilter();
                filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
                getContext().registerReceiver(mReceiver, filter);

                mLocalPowerManager.setDeviceIdleWhitelist(mPowerSaveWhitelistAllAppIdArray);
                mLocalAlarmManager.setDeviceIdleUserWhitelist(mPowerSaveWhitelistUserAppIdArray);
                mDisplayManager.registerDisplayListener(mDisplayListener, null);
                updateDisplayLocked();
            }
            updateConnectivityState(null);
        }
    }

    public boolean addPowerSaveWhitelistAppInternal(String name) {
        synchronized (this) {
            try {
                ApplicationInfo ai = getContext().getPackageManager().getApplicationInfo(name,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES);
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

    public boolean getPowerSaveWhitelistAppInternal(String name) {
        synchronized (this) {
            return mPowerSaveWhitelistUserApps.containsKey(name);
        }
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

    public String[] getUserPowerWhitelistInternal() {
        synchronized (this) {
            int size = mPowerSaveWhitelistUserApps.size();
            String[] apps = new String[size];
            for (int i = 0; i < mPowerSaveWhitelistUserApps.size(); i++) {
                apps[i] = mPowerSaveWhitelistUserApps.keyAt(i);
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

    public int[] getAppIdUserWhitelistInternal() {
        synchronized (this) {
            return mPowerSaveWhitelistUserAppIdArray;
        }
    }

    public int[] getAppIdTempWhitelistInternal() {
        synchronized (this) {
            return mTempWhitelistAppIdArray;
        }
    }

    void addPowerSaveTempWhitelistAppChecked(String packageName, long duration,
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
            addPowerSaveTempWhitelistAppInternal(callingUid,
                    packageName, duration, userId, true, reason);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Adds an app to the temporary whitelist and resets the endTime for granting the
     * app an exemption to access network and acquire wakelocks.
     */
    void addPowerSaveTempWhitelistAppInternal(int callingUid, String packageName,
            long duration, int userId, boolean sync, String reason) {
        try {
            int uid = getContext().getPackageManager().getPackageUidAsUser(packageName, userId);
            int appId = UserHandle.getAppId(uid);
            addPowerSaveTempWhitelistAppDirectInternal(callingUid, appId, duration, sync, reason);
        } catch (NameNotFoundException e) {
        }
    }

    /**
     * Adds an app to the temporary whitelist and resets the endTime for granting the
     * app an exemption to access network and acquire wakelocks.
     */
    void addPowerSaveTempWhitelistAppDirectInternal(int callingUid, int appId,
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
                Slog.d(TAG, "Adding AppId " + appId + " to temp whitelist. New entry: " + newEntry);
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
        if (DEBUG) {
            Slog.d(TAG, "postTempActiveTimeoutMessage: uid=" + uid + ", delay=" + delay);
        }
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_TEMP_APP_WHITELIST_TIMEOUT, uid, 0),
                delay);
    }

    void checkTempAppWhitelistTimeout(int uid) {
        final long timeNow = SystemClock.elapsedRealtime();
        if (DEBUG) {
            Slog.d(TAG, "checkTempAppWhitelistTimeout: uid=" + uid + ", timeNow=" + timeNow);
        }
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
                if (DEBUG) {
                    Slog.d(TAG, "Time to remove UID " + uid + ": " + entry.first.value);
                }
                postTempActiveTimeoutMessage(uid, entry.first.value - timeNow);
            }
        }
    }

    public void exitIdleInternal(String reason) {
        synchronized (this) {
            becomeActiveLocked(reason, Binder.getCallingUid());
        }
    }

    void updateConnectivityState(Intent connIntent) {
        ConnectivityService cm;
        synchronized (this) {
            cm = mConnectivityService;
        }
        if (cm == null) {
            return;
        }
        // Note: can't call out to ConnectivityService with our lock held.
        NetworkInfo ni = cm.getActiveNetworkInfo();
        synchronized (this) {
            boolean conn;
            if (ni == null) {
                conn = false;
            } else {
                if (connIntent == null) {
                    conn = ni.isConnected();
                } else {
                    final int networkType =
                            connIntent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE,
                                    ConnectivityManager.TYPE_NONE);
                    if (ni.getType() != networkType) {
                        return;
                    }
                    conn = !connIntent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY,
                            false);
                }
            }
            if (conn != mNetworkConnected) {
                mNetworkConnected = conn;
                if (conn && mLightState == LIGHT_STATE_WAITING_FOR_NETWORK) {
                    stepLightIdleStateLocked("network");
                }
            }
        }
    }

    void updateDisplayLocked() {
        mCurDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        // We consider any situation where the display is showing something to be it on,
        // because if there is anything shown we are going to be updating it at some
        // frequency so can't be allowed to go into deep sleeps.
        boolean screenOn = mCurDisplay.getState() == Display.STATE_ON;
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
        Message msg = mHandler.obtainMessage(MSG_REPORT_ACTIVE, activeUid, 0, activeReason);
        mHandler.sendMessage(msg);
    }

    void becomeActiveLocked(String activeReason, int activeUid) {
        if (DEBUG) Slog.i(TAG, "becomeActiveLocked, reason = " + activeReason);
        if (mState != STATE_ACTIVE || mLightState != STATE_ACTIVE) {
            EventLogTags.writeDeviceIdle(STATE_ACTIVE, activeReason);
            EventLogTags.writeDeviceIdleLight(LIGHT_STATE_ACTIVE, activeReason);
            scheduleReportActiveLocked(activeReason, activeUid);
            mState = STATE_ACTIVE;
            mLightState = LIGHT_STATE_ACTIVE;
            mInactiveTimeout = mConstants.INACTIVE_TIMEOUT;
            mCurIdleBudget = 0;
            mMaintenanceStartTime = 0;
            resetIdleManagementLocked();
            resetLightIdleManagementLocked();
            addEvent(EVENT_NORMAL);
        }
    }

    void becomeInactiveIfAppropriateLocked() {
        if (DEBUG) Slog.d(TAG, "becomeInactiveIfAppropriateLocked()");
        if ((!mScreenOn && !mCharging) || mForceIdle) {
            // Screen has turned off; we are now going to become inactive and start
            // waiting to see if we will ultimately go idle.
            if (mState == STATE_ACTIVE && mDeepEnabled) {
                mState = STATE_INACTIVE;
                if (DEBUG) Slog.d(TAG, "Moved from STATE_ACTIVE to STATE_INACTIVE");
                resetIdleManagementLocked();
                scheduleAlarmLocked(mInactiveTimeout, false);
                EventLogTags.writeDeviceIdle(mState, "no activity");
            }
            if (mLightState == LIGHT_STATE_ACTIVE && mLightEnabled) {
                mLightState = LIGHT_STATE_INACTIVE;
                if (DEBUG) Slog.d(TAG, "Moved from LIGHT_STATE_ACTIVE to LIGHT_STATE_INACTIVE");
                resetLightIdleManagementLocked();
                scheduleLightAlarmLocked(mConstants.LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT);
                EventLogTags.writeDeviceIdleLight(mLightState, "no activity");
            }
        }
    }

    void resetIdleManagementLocked() {
        mNextIdlePendingDelay = 0;
        mNextIdleDelay = 0;
        mNextLightIdleDelay = 0;
        cancelAlarmLocked();
        cancelSensingTimeoutAlarmLocked();
        cancelLocatingLocked();
        stopMonitoringMotionLocked();
        mAnyMotionDetector.stop();
    }

    void resetLightIdleManagementLocked() {
        cancelLightAlarmLocked();
    }

    void exitForceIdleLocked() {
        if (mForceIdle) {
            mForceIdle = false;
            if (mScreenOn || mCharging) {
                becomeActiveLocked("exit-force", Process.myUid());
            }
        }
    }

    void stepLightIdleStateLocked(String reason) {
        if (mLightState == LIGHT_STATE_OVERRIDE) {
            // If we are already in deep device idle mode, then
            // there is nothing left to do for light mode.
            return;
        }

        if (DEBUG) Slog.d(TAG, "stepLightIdleStateLocked: mLightState=" + mLightState);
        EventLogTags.writeDeviceIdleLightStep();

        switch (mLightState) {
            case LIGHT_STATE_INACTIVE:
                mCurIdleBudget = mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET;
                // Reset the upcoming idle delays.
                mNextLightIdleDelay = mConstants.LIGHT_IDLE_TIMEOUT;
                mMaintenanceStartTime = 0;
                if (!isOpsInactiveLocked()) {
                    // We have some active ops going on...  give them a chance to finish
                    // before going in to our first idle.
                    mLightState = LIGHT_STATE_PRE_IDLE;
                    EventLogTags.writeDeviceIdleLight(mLightState, reason);
                    scheduleLightAlarmLocked(mConstants.LIGHT_PRE_IDLE_TIMEOUT);
                    break;
                }
                // Nothing active, fall through to immediately idle.
            case LIGHT_STATE_PRE_IDLE:
            case LIGHT_STATE_IDLE_MAINTENANCE:
                if (mMaintenanceStartTime != 0) {
                    long duration = SystemClock.elapsedRealtime() - mMaintenanceStartTime;
                    if (duration < mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET) {
                        // We didn't use up all of our minimum budget; add this to the reserve.
                        mCurIdleBudget += (mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET-duration);
                    } else {
                        // We used more than our minimum budget; this comes out of the reserve.
                        mCurIdleBudget -= (duration-mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET);
                    }
                }
                mMaintenanceStartTime = 0;
                scheduleLightAlarmLocked(mNextLightIdleDelay);
                mNextLightIdleDelay = Math.min(mConstants.LIGHT_MAX_IDLE_TIMEOUT,
                        (long)(mNextLightIdleDelay * mConstants.LIGHT_IDLE_FACTOR));
                if (mNextLightIdleDelay < mConstants.LIGHT_IDLE_TIMEOUT) {
                    mNextLightIdleDelay = mConstants.LIGHT_IDLE_TIMEOUT;
                }
                if (DEBUG) Slog.d(TAG, "Moved to LIGHT_STATE_IDLE.");
                mLightState = LIGHT_STATE_IDLE;
                EventLogTags.writeDeviceIdleLight(mLightState, reason);
                addEvent(EVENT_LIGHT_IDLE);
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_ON_LIGHT);
                break;
            case LIGHT_STATE_IDLE:
            case LIGHT_STATE_WAITING_FOR_NETWORK:
                if (mNetworkConnected || mLightState == LIGHT_STATE_WAITING_FOR_NETWORK) {
                    // We have been idling long enough, now it is time to do some work.
                    mActiveIdleOpCount = 1;
                    mActiveIdleWakeLock.acquire();
                    mMaintenanceStartTime = SystemClock.elapsedRealtime();
                    if (mCurIdleBudget < mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET) {
                        mCurIdleBudget = mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET;
                    } else if (mCurIdleBudget > mConstants.LIGHT_IDLE_MAINTENANCE_MAX_BUDGET) {
                        mCurIdleBudget = mConstants.LIGHT_IDLE_MAINTENANCE_MAX_BUDGET;
                    }
                    scheduleLightAlarmLocked(mCurIdleBudget);
                    if (DEBUG) Slog.d(TAG,
                            "Moved from LIGHT_STATE_IDLE to LIGHT_STATE_IDLE_MAINTENANCE.");
                    mLightState = LIGHT_STATE_IDLE_MAINTENANCE;
                    EventLogTags.writeDeviceIdleLight(mLightState, reason);
                    addEvent(EVENT_LIGHT_MAINTENANCE);
                    mHandler.sendEmptyMessage(MSG_REPORT_IDLE_OFF);
                } else {
                    // We'd like to do maintenance, but currently don't have network
                    // connectivity...  let's try to wait until the network comes back.
                    // We'll only wait for another full idle period, however, and then give up.
                    scheduleLightAlarmLocked(mNextLightIdleDelay);
                    if (DEBUG) Slog.d(TAG, "Moved to LIGHT_WAITING_FOR_NETWORK.");
                    mLightState = LIGHT_STATE_WAITING_FOR_NETWORK;
                    EventLogTags.writeDeviceIdleLight(mLightState, reason);
                }
                break;
        }
    }

    void stepIdleStateLocked(String reason) {
        if (DEBUG) Slog.d(TAG, "stepIdleStateLocked: mState=" + mState);
        EventLogTags.writeDeviceIdleStep();

        final long now = SystemClock.elapsedRealtime();
        if ((now+mConstants.MIN_TIME_TO_ALARM) > mAlarmManager.getNextWakeFromIdleTime()) {
            // Whoops, there is an upcoming alarm.  We don't actually want to go idle.
            if (mState != STATE_ACTIVE) {
                becomeActiveLocked("alarm", Process.myUid());
                becomeInactiveIfAppropriateLocked();
            }
            return;
        }

        switch (mState) {
            case STATE_INACTIVE:
                // We have now been inactive long enough, it is time to start looking
                // for motion and sleep some more while doing so.
                startMonitoringMotionLocked();
                scheduleAlarmLocked(mConstants.IDLE_AFTER_INACTIVE_TIMEOUT, false);
                // Reset the upcoming idle delays.
                mNextIdlePendingDelay = mConstants.IDLE_PENDING_TIMEOUT;
                mNextIdleDelay = mConstants.IDLE_TIMEOUT;
                mState = STATE_IDLE_PENDING;
                if (DEBUG) Slog.d(TAG, "Moved from STATE_INACTIVE to STATE_IDLE_PENDING.");
                EventLogTags.writeDeviceIdle(mState, reason);
                break;
            case STATE_IDLE_PENDING:
                mState = STATE_SENSING;
                if (DEBUG) Slog.d(TAG, "Moved from STATE_IDLE_PENDING to STATE_SENSING.");
                EventLogTags.writeDeviceIdle(mState, reason);
                scheduleSensingTimeoutAlarmLocked(mConstants.SENSING_TIMEOUT);
                cancelLocatingLocked();
                mNotMoving = false;
                mLocated = false;
                mLastGenericLocation = null;
                mLastGpsLocation = null;
                mAnyMotionDetector.checkForAnyMotion();
                break;
            case STATE_SENSING:
                cancelSensingTimeoutAlarmLocked();
                mState = STATE_LOCATING;
                if (DEBUG) Slog.d(TAG, "Moved from STATE_SENSING to STATE_LOCATING.");
                EventLogTags.writeDeviceIdle(mState, reason);
                scheduleAlarmLocked(mConstants.LOCATING_TIMEOUT, false);
                if (mLocationManager != null
                        && mLocationManager.getProvider(LocationManager.NETWORK_PROVIDER) != null) {
                    mLocationManager.requestLocationUpdates(mLocationRequest,
                            mGenericLocationListener, mHandler.getLooper());
                    mLocating = true;
                } else {
                    mHasNetworkLocation = false;
                }
                if (mLocationManager != null
                        && mLocationManager.getProvider(LocationManager.GPS_PROVIDER) != null) {
                    mHasGps = true;
                    mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 5,
                            mGpsLocationListener, mHandler.getLooper());
                    mLocating = true;
                } else {
                    mHasGps = false;
                }
                // If we have a location provider, we're all set, the listeners will move state
                // forward.
                if (mLocating) {
                    break;
                }

                // Otherwise, we have to move from locating into idle maintenance.
            case STATE_LOCATING:
                cancelAlarmLocked();
                cancelLocatingLocked();
                mAnyMotionDetector.stop();

            case STATE_IDLE_MAINTENANCE:
                scheduleAlarmLocked(mNextIdleDelay, true);
                if (DEBUG) Slog.d(TAG, "Moved to STATE_IDLE. Next alarm in " + mNextIdleDelay +
                        " ms.");
                mNextIdleDelay = (long)(mNextIdleDelay * mConstants.IDLE_FACTOR);
                if (DEBUG) Slog.d(TAG, "Setting mNextIdleDelay = " + mNextIdleDelay);
                mNextIdleDelay = Math.min(mNextIdleDelay, mConstants.MAX_IDLE_TIMEOUT);
                if (mNextIdleDelay < mConstants.IDLE_TIMEOUT) {
                    mNextIdleDelay = mConstants.IDLE_TIMEOUT;
                }
                mState = STATE_IDLE;
                if (mLightState != LIGHT_STATE_OVERRIDE) {
                    mLightState = LIGHT_STATE_OVERRIDE;
                    cancelLightAlarmLocked();
                }
                EventLogTags.writeDeviceIdle(mState, reason);
                addEvent(EVENT_DEEP_IDLE);
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_ON);
                break;
            case STATE_IDLE:
                // We have been idling long enough, now it is time to do some work.
                mActiveIdleOpCount = 1;
                mActiveIdleWakeLock.acquire();
                scheduleAlarmLocked(mNextIdlePendingDelay, false);
                if (DEBUG) Slog.d(TAG, "Moved from STATE_IDLE to STATE_IDLE_MAINTENANCE. " +
                        "Next alarm in " + mNextIdlePendingDelay + " ms.");
                mMaintenanceStartTime = SystemClock.elapsedRealtime();
                mNextIdlePendingDelay = Math.min(mConstants.MAX_IDLE_PENDING_TIMEOUT,
                        (long)(mNextIdlePendingDelay * mConstants.IDLE_PENDING_FACTOR));
                if (mNextIdlePendingDelay < mConstants.IDLE_PENDING_TIMEOUT) {
                    mNextIdlePendingDelay = mConstants.IDLE_PENDING_TIMEOUT;
                }
                mState = STATE_IDLE_MAINTENANCE;
                EventLogTags.writeDeviceIdle(mState, reason);
                addEvent(EVENT_DEEP_MAINTENANCE);
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_OFF);
                break;
        }
    }

    void incActiveIdleOps() {
        synchronized (this) {
            mActiveIdleOpCount++;
        }
    }

    void decActiveIdleOps() {
        synchronized (this) {
            mActiveIdleOpCount--;
            if (mActiveIdleOpCount <= 0) {
                exitMaintenanceEarlyIfNeededLocked();
                mActiveIdleWakeLock.release();
            }
        }
    }

    void setJobsActive(boolean active) {
        synchronized (this) {
            mJobsActive = active;
            reportMaintenanceActivityIfNeededLocked();
            if (!active) {
                exitMaintenanceEarlyIfNeededLocked();
            }
        }
    }

    void setAlarmsActive(boolean active) {
        synchronized (this) {
            mAlarmsActive = active;
            if (!active) {
                exitMaintenanceEarlyIfNeededLocked();
            }
        }
    }

    boolean registerMaintenanceActivityListener(IMaintenanceActivityListener listener) {
        synchronized (this) {
            mMaintenanceActivityListeners.register(listener);
            return mReportedMaintenanceActivity;
        }
    }

    void unregisterMaintenanceActivityListener(IMaintenanceActivityListener listener) {
        synchronized (this) {
            mMaintenanceActivityListeners.unregister(listener);
        }
    }

    void reportMaintenanceActivityIfNeededLocked() {
        boolean active = mJobsActive;
        if (active == mReportedMaintenanceActivity) {
            return;
        }
        mReportedMaintenanceActivity = active;
        Message msg = mHandler.obtainMessage(MSG_REPORT_MAINTENANCE_ACTIVITY,
                mReportedMaintenanceActivity ? 1 : 0, 0);
        mHandler.sendMessage(msg);
    }

    boolean isOpsInactiveLocked() {
        return mActiveIdleOpCount <= 0 && !mJobsActive && !mAlarmsActive;
    }

    void exitMaintenanceEarlyIfNeededLocked() {
        if (mState == STATE_IDLE_MAINTENANCE || mLightState == LIGHT_STATE_IDLE_MAINTENANCE
                || mLightState == LIGHT_STATE_PRE_IDLE) {
            if (isOpsInactiveLocked()) {
                final long now = SystemClock.elapsedRealtime();
                if (DEBUG) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Exit: start=");
                    TimeUtils.formatDuration(mMaintenanceStartTime, sb);
                    sb.append(" now=");
                    TimeUtils.formatDuration(now, sb);
                    Slog.d(TAG, sb.toString());
                }
                if (mState == STATE_IDLE_MAINTENANCE) {
                    stepIdleStateLocked("s:early");
                } else if (mLightState == LIGHT_STATE_PRE_IDLE) {
                    stepLightIdleStateLocked("s:predone");
                } else {
                    stepLightIdleStateLocked("s:early");
                }
            }
        }
    }

    void motionLocked() {
        if (DEBUG) Slog.d(TAG, "motionLocked()");
        // The motion sensor will have been disabled at this point
        handleMotionDetectedLocked(mConstants.MOTION_INACTIVE_TIMEOUT, "motion");
    }

    void handleMotionDetectedLocked(long timeout, String type) {
        // The device is not yet active, so we want to go back to the pending idle
        // state to wait again for no motion.  Note that we only monitor for motion
        // after moving out of the inactive state, so no need to worry about that.
        boolean becomeInactive = false;
        if (mState != STATE_ACTIVE) {
            scheduleReportActiveLocked(type, Process.myUid());
            mState = STATE_ACTIVE;
            mInactiveTimeout = timeout;
            mCurIdleBudget = 0;
            mMaintenanceStartTime = 0;
            EventLogTags.writeDeviceIdle(mState, type);
            addEvent(EVENT_NORMAL);
            becomeInactive = true;
        }
        if (mLightState == LIGHT_STATE_OVERRIDE) {
            // We went out of light idle mode because we had started deep idle mode...  let's
            // now go back and reset things so we resume light idling if appropriate.
            mLightState = STATE_ACTIVE;
            EventLogTags.writeDeviceIdleLight(mLightState, type);
            becomeInactive = true;
        }
        if (becomeInactive) {
            becomeInactiveIfAppropriateLocked();
        }
    }

    void receivedGenericLocationLocked(Location location) {
        if (mState != STATE_LOCATING) {
            cancelLocatingLocked();
            return;
        }
        if (DEBUG) Slog.d(TAG, "Generic location: " + location);
        mLastGenericLocation = new Location(location);
        if (location.getAccuracy() > mConstants.LOCATION_ACCURACY && mHasGps) {
            return;
        }
        mLocated = true;
        if (mNotMoving) {
            stepIdleStateLocked("s:location");
        }
    }

    void receivedGpsLocationLocked(Location location) {
        if (mState != STATE_LOCATING) {
            cancelLocatingLocked();
            return;
        }
        if (DEBUG) Slog.d(TAG, "GPS location: " + location);
        mLastGpsLocation = new Location(location);
        if (location.getAccuracy() > mConstants.LOCATION_ACCURACY) {
            return;
        }
        mLocated = true;
        if (mNotMoving) {
            stepIdleStateLocked("s:gps");
        }
    }

    void startMonitoringMotionLocked() {
        if (DEBUG) Slog.d(TAG, "startMonitoringMotionLocked()");
        if (mMotionSensor != null && !mMotionListener.active) {
            mMotionListener.registerLocked();
        }
    }

    void stopMonitoringMotionLocked() {
        if (DEBUG) Slog.d(TAG, "stopMonitoringMotionLocked()");
        if (mMotionSensor != null && mMotionListener.active) {
            mMotionListener.unregisterLocked();
        }
    }

    void cancelAlarmLocked() {
        if (mNextAlarmTime != 0) {
            mNextAlarmTime = 0;
            mAlarmManager.cancel(mDeepAlarmListener);
        }
    }

    void cancelLightAlarmLocked() {
        if (mNextLightAlarmTime != 0) {
            mNextLightAlarmTime = 0;
            mAlarmManager.cancel(mLightAlarmListener);
        }
    }

    void cancelLocatingLocked() {
        if (mLocating) {
            mLocationManager.removeUpdates(mGenericLocationListener);
            mLocationManager.removeUpdates(mGpsLocationListener);
            mLocating = false;
        }
    }

    void cancelSensingTimeoutAlarmLocked() {
        if (mNextSensingTimeoutAlarmTime != 0) {
            mNextSensingTimeoutAlarmTime = 0;
            mAlarmManager.cancel(mSensingTimeoutAlarmListener);
        }
    }

    void scheduleAlarmLocked(long delay, boolean idleUntil) {
        if (DEBUG) Slog.d(TAG, "scheduleAlarmLocked(" + delay + ", " + idleUntil + ")");
        if (mMotionSensor == null) {
            // If there is no motion sensor on this device, then we won't schedule
            // alarms, because we can't determine if the device is not moving.  This effectively
            // turns off normal execution of device idling, although it is still possible to
            // manually poke it by pretending like the alarm is going off.
            return;
        }
        mNextAlarmTime = SystemClock.elapsedRealtime() + delay;
        if (idleUntil) {
            mAlarmManager.setIdleUntil(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mNextAlarmTime, "DeviceIdleController.deep", mDeepAlarmListener, mHandler);
        } else {
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mNextAlarmTime, "DeviceIdleController.deep", mDeepAlarmListener, mHandler);
        }
    }

    void scheduleLightAlarmLocked(long delay) {
        if (DEBUG) Slog.d(TAG, "scheduleLightAlarmLocked(" + delay + ")");
        mNextLightAlarmTime = SystemClock.elapsedRealtime() + delay;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mNextLightAlarmTime, "DeviceIdleController.light", mLightAlarmListener, mHandler);
    }

    void scheduleSensingTimeoutAlarmLocked(long delay) {
        if (DEBUG) Slog.d(TAG, "scheduleSensingAlarmLocked(" + delay + ")");
        mNextSensingTimeoutAlarmTime = SystemClock.elapsedRealtime() + delay;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, mNextSensingTimeoutAlarmTime,
            "DeviceIdleController.sensing", mSensingTimeoutAlarmListener, mHandler);
    }

    private static int[] buildAppIdArray(ArrayMap<String, Integer> systemApps,
            ArrayMap<String, Integer> userApps, SparseBooleanArray outAppIds) {
        outAppIds.clear();
        if (systemApps != null) {
            for (int i = 0; i < systemApps.size(); i++) {
                outAppIds.put(systemApps.valueAt(i), true);
            }
        }
        if (userApps != null) {
            for (int i = 0; i < userApps.size(); i++) {
                outAppIds.put(userApps.valueAt(i), true);
            }
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
        mPowerSaveWhitelistUserAppIdArray = buildAppIdArray(null,
                mPowerSaveWhitelistUserApps, mPowerSaveWhitelistUserAppIds);
        if (mLocalPowerManager != null) {
            if (DEBUG) {
                Slog.d(TAG, "Setting wakelock whitelist to "
                        + Arrays.toString(mPowerSaveWhitelistAllAppIdArray));
            }
            mLocalPowerManager.setDeviceIdleWhitelist(mPowerSaveWhitelistAllAppIdArray);
        }
        if (mLocalAlarmManager != null) {
            if (DEBUG) {
                Slog.d(TAG, "Setting alarm whitelist to "
                        + Arrays.toString(mPowerSaveWhitelistUserAppIdArray));
            }
            mLocalAlarmManager.setDeviceIdleUserWhitelist(mPowerSaveWhitelistUserAppIdArray);
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
        getContext().sendBroadcastAsUser(intent, UserHandle.SYSTEM);
    }

    private void reportTempWhitelistChangedLocked() {
        Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        getContext().sendBroadcastAsUser(intent, UserHandle.SYSTEM);
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
                            ApplicationInfo ai = pm.getApplicationInfo(name,
                                    PackageManager.MATCH_UNINSTALLED_PACKAGES);
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

    static void dumpHelp(PrintWriter pw) {
        pw.println("Device idle controller (deviceidle) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  step [light|deep]");
        pw.println("    Immediately step to next state, without waiting for alarm.");
        pw.println("  force-idle [light|deep]");
        pw.println("    Force directly into idle mode, regardless of other device state.");
        pw.println("  force-inactive");
        pw.println("    Force to be inactive, ready to freely step idle states.");
        pw.println("  unforce");
        pw.println("    Resume normal functioning after force-idle or force-inactive.");
        pw.println("  get [light|deep|force|screen|charging|network]");
        pw.println("    Retrieve the current given state.");
        pw.println("  disable [light|deep|all]");
        pw.println("    Completely disable device idle mode.");
        pw.println("  enable [light|deep|all]");
        pw.println("    Re-enable device idle mode after it had previously been disabled.");
        pw.println("  enabled [light|deep|all]");
        pw.println("    Print 1 if device idle mode is currently enabled, else 0.");
        pw.println("  whitelist");
        pw.println("    Print currently whitelisted apps.");
        pw.println("  whitelist [package ...]");
        pw.println("    Add (prefix with +) or remove (prefix with -) packages.");
        pw.println("  tempwhitelist");
        pw.println("    Print packages that are temporarily whitelisted.");
        pw.println("  tempwhitelist [-u] [package ..]");
        pw.println("    Temporarily place packages in whitelist for 10 seconds.");
    }

    class Shell extends ShellCommand {
        int userId = UserHandle.USER_SYSTEM;

        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            dumpHelp(pw);
        }
    }

    int onShellCommand(Shell shell, String cmd) {
        PrintWriter pw = shell.getOutPrintWriter();
        if ("step".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                long token = Binder.clearCallingIdentity();
                String arg = shell.getNextArg();
                try {
                    if (arg == null || "deep".equals(arg)) {
                        stepIdleStateLocked("s:shell");
                        pw.print("Stepped to deep: ");
                        pw.println(stateToString(mState));
                    } else if ("light".equals(arg)) {
                        stepLightIdleStateLocked("s:shell");
                        pw.print("Stepped to light: "); pw.println(lightStateToString(mLightState));
                    } else {
                        pw.println("Unknown idle mode: " + arg);
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if ("force-idle".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                long token = Binder.clearCallingIdentity();
                String arg = shell.getNextArg();
                try {
                    if (arg == null || "deep".equals(arg)) {
                        if (!mDeepEnabled) {
                            pw.println("Unable to go deep idle; not enabled");
                            return -1;
                        }
                        mForceIdle = true;
                        becomeInactiveIfAppropriateLocked();
                        int curState = mState;
                        while (curState != STATE_IDLE) {
                            stepIdleStateLocked("s:shell");
                            if (curState == mState) {
                                pw.print("Unable to go deep idle; stopped at ");
                                pw.println(stateToString(mState));
                                exitForceIdleLocked();
                                return -1;
                            }
                            curState = mState;
                        }
                        pw.println("Now forced in to deep idle mode");
                    } else if ("light".equals(arg)) {
                        mForceIdle = true;
                        becomeInactiveIfAppropriateLocked();
                        int curLightState = mLightState;
                        while (curLightState != LIGHT_STATE_IDLE) {
                            stepIdleStateLocked("s:shell");
                            if (curLightState == mLightState) {
                                pw.print("Unable to go light idle; stopped at ");
                                pw.println(lightStateToString(mLightState));
                                exitForceIdleLocked();
                                return -1;
                            }
                            curLightState = mLightState;
                        }
                        pw.println("Now forced in to light idle mode");
                    } else {
                        pw.println("Unknown idle mode: " + arg);
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if ("force-inactive".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                long token = Binder.clearCallingIdentity();
                try {
                    mForceIdle = true;
                    becomeInactiveIfAppropriateLocked();
                    pw.print("Light state: ");
                    pw.print(lightStateToString(mLightState));
                    pw.print(", deep state: ");
                    pw.println(stateToString(mState));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if ("unforce".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                long token = Binder.clearCallingIdentity();
                try {
                    exitForceIdleLocked();
                    pw.print("Light state: ");
                    pw.print(lightStateToString(mLightState));
                    pw.print(", deep state: ");
                    pw.println(stateToString(mState));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if ("get".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                String arg = shell.getNextArg();
                if (arg != null) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        switch (arg) {
                            case "light": pw.println(lightStateToString(mLightState)); break;
                            case "deep": pw.println(stateToString(mState)); break;
                            case "force": pw.println(mForceIdle); break;
                            case "screen": pw.println(mScreenOn); break;
                            case "charging": pw.println(mCharging); break;
                            case "network": pw.println(mNetworkConnected); break;
                            default: pw.println("Unknown get option: " + arg); break;
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } else {
                    pw.println("Argument required");
                }
            }
        } else if ("disable".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                long token = Binder.clearCallingIdentity();
                String arg = shell.getNextArg();
                try {
                    boolean becomeActive = false;
                    boolean valid = false;
                    if (arg == null || "deep".equals(arg) || "all".equals(arg)) {
                        valid = true;
                        if (mDeepEnabled) {
                            mDeepEnabled = false;
                            becomeActive = true;
                            pw.println("Deep idle mode disabled");
                        }
                    }
                    if (arg == null || "light".equals(arg) || "all".equals(arg)) {
                        valid = true;
                        if (mLightEnabled) {
                            mLightEnabled = false;
                            becomeActive = true;
                            pw.println("Light idle mode disabled");
                        }
                    }
                    if (becomeActive) {
                        becomeActiveLocked((arg == null ? "all" : arg) + "-disabled",
                                Process.myUid());
                    }
                    if (!valid) {
                        pw.println("Unknown idle mode: " + arg);
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if ("enable".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                long token = Binder.clearCallingIdentity();
                String arg = shell.getNextArg();
                try {
                    boolean becomeInactive = false;
                    boolean valid = false;
                    if (arg == null || "deep".equals(arg) || "all".equals(arg)) {
                        valid = true;
                        if (!mDeepEnabled) {
                            mDeepEnabled = true;
                            becomeInactive = true;
                            pw.println("Deep idle mode enabled");
                        }
                    }
                    if (arg == null || "light".equals(arg) || "all".equals(arg)) {
                        valid = true;
                        if (!mLightEnabled) {
                            mLightEnabled = true;
                            becomeInactive = true;
                            pw.println("Light idle mode enable");
                        }
                    }
                    if (becomeInactive) {
                        becomeInactiveIfAppropriateLocked();
                    }
                    if (!valid) {
                        pw.println("Unknown idle mode: " + arg);
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if ("enabled".equals(cmd)) {
            synchronized (this) {
                String arg = shell.getNextArg();
                if (arg == null || "all".equals(arg)) {
                    pw.println(mDeepEnabled && mLightEnabled ? "1" : 0);
                } else if ("deep".equals(arg)) {
                    pw.println(mDeepEnabled ? "1" : 0);
                } else if ("light".equals(arg)) {
                    pw.println(mLightEnabled ? "1" : 0);
                } else {
                    pw.println("Unknown idle mode: " + arg);
                }
            }
        } else if ("whitelist".equals(cmd)) {
            long token = Binder.clearCallingIdentity();
            try {
                String arg = shell.getNextArg();
                if (arg != null) {
                    getContext().enforceCallingOrSelfPermission(
                            android.Manifest.permission.DEVICE_POWER, null);
                    do {
                        if (arg.length() < 1 || (arg.charAt(0) != '-'
                                && arg.charAt(0) != '+' && arg.charAt(0) != '=')) {
                            pw.println("Package must be prefixed with +, -, or =: " + arg);
                            return -1;
                        }
                        char op = arg.charAt(0);
                        String pkg = arg.substring(1);
                        if (op == '+') {
                            if (addPowerSaveWhitelistAppInternal(pkg)) {
                                pw.println("Added: " + pkg);
                            } else {
                                pw.println("Unknown package: " + pkg);
                            }
                        } else if (op == '-') {
                            if (removePowerSaveWhitelistAppInternal(pkg)) {
                                pw.println("Removed: " + pkg);
                            }
                        } else {
                            pw.println(getPowerSaveWhitelistAppInternal(pkg));
                        }
                    } while ((arg=shell.getNextArg()) != null);
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
        } else if ("tempwhitelist".equals(cmd)) {
            String opt;
            while ((opt=shell.getNextOption()) != null) {
                if ("-u".equals(opt)) {
                    opt = shell.getNextArg();
                    if (opt == null) {
                        pw.println("-u requires a user number");
                        return -1;
                    }
                    shell.userId = Integer.parseInt(opt);
                }
            }
            String arg = shell.getNextArg();
            if (arg != null) {
                try {
                    addPowerSaveTempWhitelistAppChecked(arg, 10000L, shell.userId, "shell");
                } catch (RemoteException re) {
                    pw.println("Failed: " + re);
                }
            } else {
                dumpTempWhitelistSchedule(pw, false);
            }
        } else {
            return shell.handleDefaultCommands(cmd);
        }
        return 0;
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
            int userId = UserHandle.USER_SYSTEM;
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
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    return;
                } else {
                    Shell shell = new Shell();
                    shell.userId = userId;
                    String[] newArgs = new String[args.length-i];
                    System.arraycopy(args, i, newArgs, 0, args.length-i);
                    shell.exec(mBinderService, null, fd, null, newArgs, new ResultReceiver(null));
                    return;
                }
            }
        }

        synchronized (this) {
            mConstants.dump(pw);

            if (mEventCmds[0] != EVENT_NULL) {
                pw.println("  Idling history:");
                long now = SystemClock.elapsedRealtime();
                for (int i=EVENT_BUFFER_SIZE-1; i>=0; i--) {
                    int cmd = mEventCmds[i];
                    if (cmd == EVENT_NULL) {
                        continue;
                    }
                    String label;
                    switch (mEventCmds[i]) {
                        case EVENT_NORMAL:              label = "     normal"; break;
                        case EVENT_LIGHT_IDLE:          label = " light-idle"; break;
                        case EVENT_LIGHT_MAINTENANCE:   label = "light-maint"; break;
                        case EVENT_DEEP_IDLE:           label = "  deep-idle"; break;
                        case EVENT_DEEP_MAINTENANCE:    label = " deep-maint"; break;
                        default:                        label = "         ??"; break;
                    }
                    pw.print("    ");
                    pw.print(label);
                    pw.print(": ");
                    TimeUtils.formatDuration(mEventTimes[i], now, pw);;
                    pw.println();
                }
            }

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
            size = mPowerSaveWhitelistUserAppIds.size();
            if (size > 0) {
                pw.println("  Whitelist user app ids:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.print(mPowerSaveWhitelistUserAppIds.keyAt(i));
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
            dumpTempWhitelistSchedule(pw, true);

            size = mTempWhitelistAppIdArray != null ? mTempWhitelistAppIdArray.length : 0;
            if (size > 0) {
                pw.println("  Temp whitelist app ids:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.print(mTempWhitelistAppIdArray[i]);
                    pw.println();
                }
            }

            pw.print("  mLightEnabled="); pw.print(mLightEnabled);
            pw.print("  mDeepEnabled="); pw.println(mDeepEnabled);
            pw.print("  mForceIdle="); pw.println(mForceIdle);
            pw.print("  mMotionSensor="); pw.println(mMotionSensor);
            pw.print("  mCurDisplay="); pw.println(mCurDisplay);
            pw.print("  mScreenOn="); pw.println(mScreenOn);
            pw.print("  mNetworkConnected="); pw.println(mNetworkConnected);
            pw.print("  mCharging="); pw.println(mCharging);
            pw.print("  mMotionActive="); pw.println(mMotionListener.active);
            pw.print("  mNotMoving="); pw.println(mNotMoving);
            pw.print("  mLocating="); pw.print(mLocating); pw.print(" mHasGps=");
                    pw.print(mHasGps); pw.print(" mHasNetwork=");
                    pw.print(mHasNetworkLocation); pw.print(" mLocated="); pw.println(mLocated);
            if (mLastGenericLocation != null) {
                pw.print("  mLastGenericLocation="); pw.println(mLastGenericLocation);
            }
            if (mLastGpsLocation != null) {
                pw.print("  mLastGpsLocation="); pw.println(mLastGpsLocation);
            }
            pw.print("  mState="); pw.print(stateToString(mState));
            pw.print(" mLightState=");
            pw.println(lightStateToString(mLightState));
            pw.print("  mInactiveTimeout="); TimeUtils.formatDuration(mInactiveTimeout, pw);
            pw.println();
            if (mActiveIdleOpCount != 0) {
                pw.print("  mActiveIdleOpCount="); pw.println(mActiveIdleOpCount);
            }
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
            if (mNextLightIdleDelay != 0) {
                pw.print("  mNextIdleDelay=");
                TimeUtils.formatDuration(mNextLightIdleDelay, pw);
                pw.println();
            }
            if (mNextLightAlarmTime != 0) {
                pw.print("  mNextLightAlarmTime=");
                TimeUtils.formatDuration(mNextLightAlarmTime, SystemClock.elapsedRealtime(), pw);
                pw.println();
            }
            if (mCurIdleBudget != 0) {
                pw.print("  mCurIdleBudget=");
                TimeUtils.formatDuration(mCurIdleBudget, pw);
                pw.println();
            }
            if (mMaintenanceStartTime != 0) {
                pw.print("  mMaintenanceStartTime=");
                TimeUtils.formatDuration(mMaintenanceStartTime, SystemClock.elapsedRealtime(), pw);
                pw.println();
            }
            if (mJobsActive) {
                pw.print("  mJobsActive="); pw.println(mJobsActive);
            }
            if (mAlarmsActive) {
                pw.print("  mAlarmsActive="); pw.println(mAlarmsActive);
            }
        }
    }

    void dumpTempWhitelistSchedule(PrintWriter pw, boolean printTitle) {
        final int size = mTempWhitelistAppIdEndTimes.size();
        if (size > 0) {
            String prefix = "";
            if (printTitle) {
                pw.println("  Temp whitelist schedule:");
                prefix = "    ";
            }
            final long timeNow = SystemClock.elapsedRealtime();
            for (int i = 0; i < size; i++) {
                pw.print(prefix);
                pw.print("UID=");
                pw.print(mTempWhitelistAppIdEndTimes.keyAt(i));
                pw.print(": ");
                Pair<MutableLong, String> entry = mTempWhitelistAppIdEndTimes.valueAt(i);
                TimeUtils.formatDuration(entry.first.value, timeNow, pw);
                pw.print(" - ");
                pw.println(entry.second);
            }
        }
    }
 }
