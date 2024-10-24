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

import static android.os.PowerExemptionManager.REASON_SHELL;
import static android.os.PowerExemptionManager.REASON_UNKNOWN;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE;
import static android.os.Process.INVALID_UID;

import android.Manifest;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.ConnectivityManager;
import android.net.INetworkPolicyManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.PowerExemptionManager.TempAllowListType;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WearModeManagerInternal;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.MutableLong;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.modules.expresslog.Counter;
import com.android.server.am.BatteryStatsService;
import com.android.server.deviceidle.ConstraintController;
import com.android.server.deviceidle.DeviceIdleConstraintTracker;
import com.android.server.deviceidle.Flags;
import com.android.server.deviceidle.IDeviceIdleConstraint;
import com.android.server.deviceidle.TvConstraintController;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.utils.UserSettingDeviceConfigMediator;
import com.android.server.wm.ActivityTaskManagerInternal;

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
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Keeps track of device idleness and drives low power mode based on that.
 *
 * Test: atest com.android.server.DeviceIdleControllerTest
 *
 * Current idling state machine (as of Android Q). This can be visualized using Graphviz:
   <pre>

   digraph {
     subgraph cluster_legend {
       label="Legend"

       wakeup_alarm [label="Entering this state requires a wakeup alarm",color=red,shape=box]
       nonwakeup_alarm [
         label="This state can be entered from a non-wakeup alarm",color=blue,shape=oval
       ]
       no_alarm [label="This state doesn't require an alarm",color=black,shape=diamond]
     }

     subgraph deep {
       label="deep";

       STATE_ACTIVE [
         label="STATE_ACTIVE\nScreen on OR charging OR alarm going off soon\n"
             + "OR active emergency call",
         color=black,shape=diamond
       ]
       STATE_INACTIVE [
         label="STATE_INACTIVE\nScreen off AND not charging AND no active emergency call",
         color=black,shape=diamond
       ]
       STATE_QUICK_DOZE_DELAY [
         label="STATE_QUICK_DOZE_DELAY\n"
             + "Screen off AND not charging AND no active emergency call\n"
             + "Location, motion detection, and significant motion monitoring turned off",
         color=black,shape=diamond
       ]
       STATE_IDLE_PENDING [
         label="STATE_IDLE_PENDING\nSignificant motion monitoring turned on",
         color=red,shape=box
       ]
       STATE_SENSING [label="STATE_SENSING\nMonitoring for ANY motion",color=red,shape=box]
       STATE_LOCATING [
         label="STATE_LOCATING\nRequesting location, motion monitoring still on",
         color=red,shape=box
       ]
       STATE_IDLE [
         label="STATE_IDLE\nLocation and motion detection turned off\n"
             + "Significant motion monitoring state unchanged",
         color=red,shape=box
       ]
       STATE_IDLE_MAINTENANCE [label="STATE_IDLE_MAINTENANCE\n",color=red,shape=box]

       STATE_ACTIVE -> STATE_INACTIVE [
         label="becomeInactiveIfAppropriateLocked() AND Quick Doze not enabled"
       ]
       STATE_ACTIVE -> STATE_QUICK_DOZE_DELAY [
         label="becomeInactiveIfAppropriateLocked() AND Quick Doze enabled"
       ]

       STATE_INACTIVE -> STATE_ACTIVE [
         label="handleMotionDetectedLocked(), becomeActiveLocked()"
       ]
       STATE_INACTIVE -> STATE_IDLE_PENDING [label="stepIdleStateLocked()"]
       STATE_INACTIVE -> STATE_QUICK_DOZE_DELAY [
         label="becomeInactiveIfAppropriateLocked() AND Quick Doze enabled"
       ]

       STATE_IDLE_PENDING -> STATE_ACTIVE [
         label="handleMotionDetectedLocked(), becomeActiveLocked()"
       ]
       STATE_IDLE_PENDING -> STATE_SENSING [label="stepIdleStateLocked()"]
       STATE_IDLE_PENDING -> STATE_QUICK_DOZE_DELAY [
         label="becomeInactiveIfAppropriateLocked() AND Quick Doze enabled"
       ]

       STATE_SENSING -> STATE_ACTIVE [
         label="handleMotionDetectedLocked(), becomeActiveLocked()"
       ]
       STATE_SENSING -> STATE_LOCATING [label="stepIdleStateLocked()"]
       STATE_SENSING -> STATE_QUICK_DOZE_DELAY [
         label="becomeInactiveIfAppropriateLocked() AND Quick Doze enabled"
       ]
       STATE_SENSING -> STATE_IDLE [
         label="stepIdleStateLocked()\n"
             + "No Location Manager OR (no Network provider AND no GPS provider)"
       ]

       STATE_LOCATING -> STATE_ACTIVE [
         label="handleMotionDetectedLocked(), becomeActiveLocked()"
       ]
       STATE_LOCATING -> STATE_QUICK_DOZE_DELAY [
         label="becomeInactiveIfAppropriateLocked() AND Quick Doze enabled"
       ]
       STATE_LOCATING -> STATE_IDLE [label="stepIdleStateLocked()"]

       STATE_QUICK_DOZE_DELAY -> STATE_ACTIVE [
         label="handleMotionDetectedLocked(), becomeActiveLocked()"
       ]
       STATE_QUICK_DOZE_DELAY -> STATE_IDLE [label="stepIdleStateLocked()"]

       STATE_IDLE -> STATE_ACTIVE [label="handleMotionDetectedLocked(), becomeActiveLocked()"]
       STATE_IDLE -> STATE_IDLE_MAINTENANCE [label="stepIdleStateLocked()"]

       STATE_IDLE_MAINTENANCE -> STATE_ACTIVE [
         label="handleMotionDetectedLocked(), becomeActiveLocked()"
       ]
       STATE_IDLE_MAINTENANCE -> STATE_IDLE [
         label="stepIdleStateLocked(), exitMaintenanceEarlyIfNeededLocked()"
       ]
     }

     subgraph light {
       label="light"

       LIGHT_STATE_ACTIVE [
         label="LIGHT_STATE_ACTIVE\n"
             + "Screen on OR charging OR alarm going off soon OR active emergency call",
         color=black,shape=diamond
       ]
       LIGHT_STATE_INACTIVE [
         label="LIGHT_STATE_INACTIVE\nScreen off AND not charging AND no active emergency call",
         color=black,shape=diamond
       ]
       LIGHT_STATE_IDLE [label="LIGHT_STATE_IDLE\n",color=red,shape=box]
       LIGHT_STATE_WAITING_FOR_NETWORK [
         label="LIGHT_STATE_WAITING_FOR_NETWORK\n"
             + "Coming out of LIGHT_STATE_IDLE, waiting for network",
         color=black,shape=diamond
       ]
       LIGHT_STATE_IDLE_MAINTENANCE [
         label="LIGHT_STATE_IDLE_MAINTENANCE\n",color=red,shape=box
       ]
       LIGHT_STATE_OVERRIDE [
         label="LIGHT_STATE_OVERRIDE\nDevice in deep doze, light no longer changing states"
       ]

       LIGHT_STATE_ACTIVE -> LIGHT_STATE_INACTIVE [
         label="becomeInactiveIfAppropriateLocked()"
       ]
       LIGHT_STATE_ACTIVE -> LIGHT_STATE_OVERRIDE [label="deep goes to STATE_IDLE"]

       LIGHT_STATE_INACTIVE -> LIGHT_STATE_ACTIVE [label="becomeActiveLocked()"]
       LIGHT_STATE_INACTIVE -> LIGHT_STATE_IDLE [label="some time transpires"]
       LIGHT_STATE_INACTIVE -> LIGHT_STATE_OVERRIDE [label="deep goes to STATE_IDLE"]

       LIGHT_STATE_IDLE -> LIGHT_STATE_ACTIVE [label="becomeActiveLocked()"]
       LIGHT_STATE_IDLE -> LIGHT_STATE_WAITING_FOR_NETWORK [label="no network"]
       LIGHT_STATE_IDLE -> LIGHT_STATE_IDLE_MAINTENANCE
       LIGHT_STATE_IDLE -> LIGHT_STATE_OVERRIDE [label="deep goes to STATE_IDLE"]

       LIGHT_STATE_WAITING_FOR_NETWORK -> LIGHT_STATE_ACTIVE [label="becomeActiveLocked()"]
       LIGHT_STATE_WAITING_FOR_NETWORK -> LIGHT_STATE_IDLE_MAINTENANCE
       LIGHT_STATE_WAITING_FOR_NETWORK -> LIGHT_STATE_OVERRIDE [
         label="deep goes to STATE_IDLE"
       ]

       LIGHT_STATE_IDLE_MAINTENANCE -> LIGHT_STATE_ACTIVE [label="becomeActiveLocked()"]
       LIGHT_STATE_IDLE_MAINTENANCE -> LIGHT_STATE_IDLE [
         label="stepLightIdleStateLocked(), exitMaintenanceEarlyIfNeededLocked()"
       ]
       LIGHT_STATE_IDLE_MAINTENANCE -> LIGHT_STATE_OVERRIDE [label="deep goes to STATE_IDLE"]

       LIGHT_STATE_OVERRIDE -> LIGHT_STATE_ACTIVE [
         label="handleMotionDetectedLocked(), becomeActiveLocked()"
       ]
     }
   }
   </pre>
 */
public class DeviceIdleController extends SystemService
        implements AnyMotionDetector.DeviceIdleCallback {
    private static final String TAG = "DeviceIdleController";

    private static final String USER_ALLOWLIST_ADDITION_METRIC_ID =
            "battery.value_app_added_to_power_allowlist";

    private static final String USER_ALLOWLIST_REMOVAL_METRIC_ID =
            "battery.value_app_removed_from_power_allowlist";

    private static final boolean DEBUG = false;

    private static final boolean COMPRESS_TIME = false;

    private static final int EVENT_BUFFER_SIZE = 100;

    private AlarmManager mAlarmManager;
    private AlarmManagerInternal mLocalAlarmManager;
    private IBatteryStats mBatteryStats;
    private ActivityManagerInternal mLocalActivityManager;
    private ActivityTaskManagerInternal mLocalActivityTaskManager;
    private DeviceIdleInternal mLocalService;
    private PackageManagerInternal mPackageManagerInternal;
    private PowerManagerInternal mLocalPowerManager;
    private PowerManager mPowerManager;
    private INetworkPolicyManager mNetworkPolicyManager;
    private SensorManager mSensorManager;
    private final boolean mUseMotionSensor;
    private Sensor mMotionSensor;
    private final boolean mIsLocationPrefetchEnabled;
    @Nullable
    private LocationRequest mLocationRequest;
    private Intent mIdleIntent;
    private Bundle mIdleIntentOptions;
    private Intent mLightIdleIntent;
    private Bundle mLightIdleIntentOptions;
    private Intent mPowerSaveWhitelistChangedIntent;
    private Bundle mPowerSaveWhitelistChangedOptions;
    private Intent mPowerSaveTempWhitelistChangedIntent;
    private Bundle mPowerSaveTempWhilelistChangedOptions;
    private AnyMotionDetector mAnyMotionDetector;
    private final AppStateTrackerImpl mAppStateTracker;
    @GuardedBy("this")
    private boolean mLightEnabled;
    @GuardedBy("this")
    private boolean mDeepEnabled;
    @GuardedBy("this")
    private boolean mQuickDozeActivated;
    @GuardedBy("this")
    private boolean mQuickDozeActivatedWhileIdling;
    @GuardedBy("this")
    private boolean mForceIdle;
    @GuardedBy("this")
    private boolean mNetworkConnected;
    @GuardedBy("this")
    private boolean mScreenOn;
    @GuardedBy("this")
    private boolean mCharging;
    @GuardedBy("this")
    private boolean mNotMoving;
    @GuardedBy("this")
    private boolean mLocating;
    @GuardedBy("this")
    private boolean mLocated;
    @GuardedBy("this")
    private boolean mHasGps;
    @GuardedBy("this")
    private boolean mHasFusedLocation;
    @GuardedBy("this")
    private Location mLastGenericLocation;
    @GuardedBy("this")
    private Location mLastGpsLocation;
    @GuardedBy("this")
    private boolean mBatterySaverEnabled;
    @GuardedBy("this")
    private boolean mModeManagerRequestedQuickDoze;
    @GuardedBy("this")
    private boolean mIsOffBody;
    @GuardedBy("this")
    private boolean mForceModeManagerQuickDozeRequest;
    @GuardedBy("this")
    private boolean mForceModeManagerOffBodyState;

    /** Time in the elapsed realtime timebase when this listener last received a motion event. */
    @GuardedBy("this")
    private long mLastMotionEventElapsed;

    // Current locked state of the screen
    @GuardedBy("this")
    private boolean mScreenLocked;
    @GuardedBy("this")
    private int mNumBlockingConstraints = 0;

    /**
     * Constraints are the "handbrakes" that stop the device from moving into a lower state until
     * every one is released at the same time.
     *
     * @see #registerDeviceIdleConstraintInternal(IDeviceIdleConstraint, String, int)
     */
    private final ArrayMap<IDeviceIdleConstraint, DeviceIdleConstraintTracker>
            mConstraints = new ArrayMap<>();
    private ConstraintController mConstraintController;

    /** Device is currently active. */
    @VisibleForTesting
    static final int STATE_ACTIVE = 0;
    /** Device is inactive (screen off, no motion) and we are waiting to for idle. */
    @VisibleForTesting
    static final int STATE_INACTIVE = 1;
    /** Device is past the initial inactive period, and waiting for the next idle period. */
    @VisibleForTesting
    static final int STATE_IDLE_PENDING = 2;
    /** Device is currently sensing motion. */
    @VisibleForTesting
    static final int STATE_SENSING = 3;
    /** Device is currently finding location (and may still be sensing). */
    @VisibleForTesting
    static final int STATE_LOCATING = 4;
    /** Device is in the idle state, trying to stay asleep as much as possible. */
    @VisibleForTesting
    static final int STATE_IDLE = 5;
    /** Device is in the idle state, but temporarily out of idle to do regular maintenance. */
    @VisibleForTesting
    static final int STATE_IDLE_MAINTENANCE = 6;
    /**
     * Device is inactive and should go straight into idle (foregoing motion and location
     * monitoring), but allow some time for current work to complete first.
     */
    @VisibleForTesting
    static final int STATE_QUICK_DOZE_DELAY = 7;

    private static final int ACTIVE_REASON_UNKNOWN = 0;
    private static final int ACTIVE_REASON_MOTION = 1;
    private static final int ACTIVE_REASON_SCREEN = 2;
    private static final int ACTIVE_REASON_CHARGING = 3;
    private static final int ACTIVE_REASON_UNLOCKED = 4;
    private static final int ACTIVE_REASON_FROM_BINDER_CALL = 5;
    private static final int ACTIVE_REASON_FORCED = 6;
    private static final int ACTIVE_REASON_ALARM = 7;
    private static final int ACTIVE_REASON_EMERGENCY_CALL = 8;
    private static final int ACTIVE_REASON_MODE_MANAGER = 9;
    private static final int ACTIVE_REASON_ONBODY = 10;

    @VisibleForTesting
    static String stateToString(int state) {
        switch (state) {
            case STATE_ACTIVE: return "ACTIVE";
            case STATE_INACTIVE: return "INACTIVE";
            case STATE_IDLE_PENDING: return "IDLE_PENDING";
            case STATE_SENSING: return "SENSING";
            case STATE_LOCATING: return "LOCATING";
            case STATE_IDLE: return "IDLE";
            case STATE_IDLE_MAINTENANCE: return "IDLE_MAINTENANCE";
            case STATE_QUICK_DOZE_DELAY: return "QUICK_DOZE_DELAY";
            default: return Integer.toString(state);
        }
    }

    /** Device is currently active. */
    @VisibleForTesting
    static final int LIGHT_STATE_ACTIVE = 0;
    /** Device is inactive (screen off) and we are waiting to for the first light idle. */
    @VisibleForTesting
    static final int LIGHT_STATE_INACTIVE = 1;
    /** Device is in the light idle state, trying to stay asleep as much as possible. */
    @VisibleForTesting
    static final int LIGHT_STATE_IDLE = 4;
    /** Device is in the light idle state, we want to go in to idle maintenance but are
     * waiting for network connectivity before doing so. */
    @VisibleForTesting
    static final int LIGHT_STATE_WAITING_FOR_NETWORK = 5;
    /** Device is in the light idle state, but temporarily out of idle to do regular maintenance. */
    @VisibleForTesting
    static final int LIGHT_STATE_IDLE_MAINTENANCE = 6;
    /** Device light idle state is overridden, now applying deep doze state. */
    @VisibleForTesting
    static final int LIGHT_STATE_OVERRIDE = 7;

    @VisibleForTesting
    static String lightStateToString(int state) {
        switch (state) {
            case LIGHT_STATE_ACTIVE: return "ACTIVE";
            case LIGHT_STATE_INACTIVE: return "INACTIVE";
            case LIGHT_STATE_IDLE: return "IDLE";
            case LIGHT_STATE_WAITING_FOR_NETWORK: return "WAITING_FOR_NETWORK";
            case LIGHT_STATE_IDLE_MAINTENANCE: return "IDLE_MAINTENANCE";
            case LIGHT_STATE_OVERRIDE: return "OVERRIDE";
            default: return Integer.toString(state);
        }
    }

    @GuardedBy("this")
    private int mState;
    @GuardedBy("this")
    private int mLightState;

    @GuardedBy("this")
    private long mInactiveTimeout;
    @GuardedBy("this")
    private long mNextAlarmTime;
    @GuardedBy("this")
    private long mNextIdlePendingDelay;
    @GuardedBy("this")
    private long mNextIdleDelay;
    @GuardedBy("this")
    private long mNextLightIdleDelay;
    @GuardedBy("this")
    private long mNextLightIdleDelayFlex;
    @GuardedBy("this")
    private long mNextLightAlarmTime;
    @GuardedBy("this")
    private long mNextSensingTimeoutAlarmTime;

    /** How long a light idle maintenance window should last. */
    @GuardedBy("this")
    private long mCurLightIdleBudget;

    /**
     * Start time of the current (light or full) maintenance window, in the elapsed timebase. Valid
     * only if {@link #mState} == {@link #STATE_IDLE_MAINTENANCE} or
     * {@link #mLightState} == {@link #LIGHT_STATE_IDLE_MAINTENANCE}.
     */
    @GuardedBy("this")
    private long mMaintenanceStartTime;

    @GuardedBy("this")
    private int mActiveIdleOpCount;
    private PowerManager.WakeLock mActiveIdleWakeLock; // held when there are operations in progress
    private PowerManager.WakeLock mGoingIdleWakeLock;  // held when we are going idle so hardware
                                                       // (especially NetworkPolicyManager) can shut
                                                       // down.
    @GuardedBy("this")
    private boolean mJobsActive;
    @GuardedBy("this")
    private boolean mAlarmsActive;

    @GuardedBy("this")
    private int mActiveReason;

    public final AtomicFile mConfigFile;

    /**
     * Package names the system has white-listed to opt out of power save restrictions,
     * except for device idle modes (light and full doze).
     */
    private final ArrayMap<String, Integer> mPowerSaveWhitelistAppsExceptIdle = new ArrayMap<>();

    /**
     * Package names the user has white-listed using commandline option to opt out of
     * power save restrictions, except for device idle mode.
     */
    private final ArraySet<String> mPowerSaveWhitelistUserAppsExceptIdle = new ArraySet<>();

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
     * List of end times for app-IDs that are temporarily marked as being allowed to access
     * the network and acquire wakelocks. Times are in milliseconds.
     */
    @GuardedBy("this")
    private final SparseArray<Pair<MutableLong, String>> mTempWhitelistAppIdEndTimes
            = new SparseArray<>();

    private NetworkPolicyManagerInternal mNetworkPolicyManagerInternal;

    /**
     * Current app IDs of temporarily whitelist apps for high-priority messages.
     */
    private int[] mTempWhitelistAppIdArray = new int[0];

    /**
     * Apps in the system whitelist that have been taken out (probably because the user wanted to).
     * They can be restored back by calling restoreAppToSystemWhitelist(String).
     */
    private ArrayMap<String, Integer> mRemovedFromSystemWhitelistApps = new ArrayMap<>();

    private final ArraySet<DeviceIdleInternal.StationaryListener> mStationaryListeners =
            new ArraySet<>();

    private final ArraySet<PowerAllowlistInternal.TempAllowlistChangeListener>
            mTempAllowlistChangeListeners = new ArraySet<>();

    private static final int EVENT_NULL = 0;
    private static final int EVENT_NORMAL = 1;
    private static final int EVENT_LIGHT_IDLE = 2;
    private static final int EVENT_LIGHT_MAINTENANCE = 3;
    private static final int EVENT_DEEP_IDLE = 4;
    private static final int EVENT_DEEP_MAINTENANCE = 5;

    private final int[] mEventCmds = new int[EVENT_BUFFER_SIZE];
    private final long[] mEventTimes = new long[EVENT_BUFFER_SIZE];
    private final String[] mEventReasons = new String[EVENT_BUFFER_SIZE];

    private void addEvent(int cmd, String reason) {
        if (mEventCmds[0] != cmd) {
            System.arraycopy(mEventCmds, 0, mEventCmds, 1, EVENT_BUFFER_SIZE - 1);
            System.arraycopy(mEventTimes, 0, mEventTimes, 1, EVENT_BUFFER_SIZE - 1);
            System.arraycopy(mEventReasons, 0, mEventReasons, 1, EVENT_BUFFER_SIZE - 1);
            mEventCmds[0] = cmd;
            mEventTimes[0] = SystemClock.elapsedRealtime();
            mEventReasons[0] = reason;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ConnectivityManager.CONNECTIVITY_ACTION: {
                    updateConnectivityState(intent);
                } break;
                case Intent.ACTION_BATTERY_CHANGED: {
                    boolean present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
                    boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    synchronized (DeviceIdleController.this) {
                        updateChargingLocked(present && plugged);
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

    private final AlarmManager.OnAlarmListener mLightAlarmListener = () -> {
        if (DEBUG) {
            Slog.d(TAG, "Light progression alarm fired");
        }
        synchronized (DeviceIdleController.this) {
            stepLightIdleStateLocked("s:alarm");
        }
    };

    /** AlarmListener to start monitoring motion if there are registered stationary listeners. */
    private final AlarmManager.OnAlarmListener mMotionRegistrationAlarmListener = () -> {
        synchronized (DeviceIdleController.this) {
            if (mStationaryListeners.size() > 0) {
                startMonitoringMotionLocked();
                scheduleMotionTimeoutAlarmLocked();
            }
        }
    };

    private final AlarmManager.OnAlarmListener mMotionTimeoutAlarmListener = () -> {
        synchronized (DeviceIdleController.this) {
            if (!isStationaryLocked()) {
                // If the device keeps registering motion, then the alarm should be
                // rescheduled, so this shouldn't go off until the device is stationary.
                // This case may happen in a race condition (alarm goes off right before
                // motion is detected, but handleMotionDetectedLocked is called before
                // we enter this block).
                Slog.w(TAG, "motion timeout went off and device isn't stationary");
                return;
            }
        }
        postStationaryStatusUpdated();
    };

    private final AlarmManager.OnAlarmListener mSensingTimeoutAlarmListener
            = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            synchronized (DeviceIdleController.this) {
                if (mState == STATE_SENSING) {
                    // Restart the device idle progression in case the device moved but the screen
                    // didn't turn on.
                    becomeInactiveIfAppropriateLocked();
                }
            }
        }
    };

    @VisibleForTesting
    final AlarmManager.OnAlarmListener mDeepAlarmListener
            = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            synchronized (DeviceIdleController.this) {
                stepIdleStateLocked("s:alarm");
            }
        }
    };

    private final IIntentReceiver mIdleStartedDoneReceiver = new IIntentReceiver.Stub() {
        @Override
        public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky, int sendingUser) {
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

    private final BroadcastReceiver mInteractivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (DeviceIdleController.this) {
                updateInteractivityLocked();
            }
        }
    };

    private final EmergencyCallListener mEmergencyCallListener = new EmergencyCallListener();

    /** Post stationary status only to this listener. */
    private void postStationaryStatus(DeviceIdleInternal.StationaryListener listener) {
        mHandler.obtainMessage(MSG_REPORT_STATIONARY_STATUS, listener).sendToTarget();
    }

    /** Post stationary status to all registered listeners. */
    private void postStationaryStatusUpdated() {
        mHandler.sendEmptyMessage(MSG_REPORT_STATIONARY_STATUS);
    }

    @GuardedBy("this")
    private boolean isStationaryLocked() {
        final long now = mInjector.getElapsedRealtime();
        return mMotionListener.active
                // Listening for motion for long enough and last motion was long enough ago.
                && now - Math.max(mMotionListener.activatedTimeElapsed, mLastMotionEventElapsed)
                        >= mConstants.MOTION_INACTIVE_TIMEOUT;
    }

    @VisibleForTesting
    void registerStationaryListener(DeviceIdleInternal.StationaryListener listener) {
        synchronized (this) {
            if (!mStationaryListeners.add(listener)) {
                // Listener already registered.
                return;
            }
            postStationaryStatus(listener);
            if (mMotionListener.active) {
                if (!isStationaryLocked() && mStationaryListeners.size() == 1) {
                    // First listener to be registered and the device isn't stationary, so we
                    // need to register the alarm to report the device is stationary.
                    scheduleMotionTimeoutAlarmLocked();
                }
            } else {
                startMonitoringMotionLocked();
                scheduleMotionTimeoutAlarmLocked();
            }
        }
    }

    private void unregisterStationaryListener(DeviceIdleInternal.StationaryListener listener) {
        synchronized (this) {
            if (mStationaryListeners.remove(listener) && mStationaryListeners.size() == 0
                    // Motion detection is started when transitioning from INACTIVE to IDLE_PENDING
                    // and so doesn't need to be on for ACTIVE or INACTIVE states.
                    // Motion detection isn't needed when idling due to Quick Doze.
                    && (mState == STATE_ACTIVE || mState == STATE_INACTIVE
                            || mQuickDozeActivated)) {
                maybeStopMonitoringMotionLocked();
            }
        }
    }

    private void registerTempAllowlistChangeListener(
            @NonNull PowerAllowlistInternal.TempAllowlistChangeListener listener) {
        synchronized (this) {
            mTempAllowlistChangeListeners.add(listener);
        }
    }

    private void unregisterTempAllowlistChangeListener(
            @NonNull PowerAllowlistInternal.TempAllowlistChangeListener listener) {
        synchronized (this) {
            mTempAllowlistChangeListeners.remove(listener);
        }
    }

    @VisibleForTesting
    class ModeManagerQuickDozeRequestConsumer implements Consumer<Boolean> {
        @Override
        public void accept(Boolean enabled) {
            Slog.i(TAG, "Mode manager quick doze request: " + enabled);
            synchronized (DeviceIdleController.this) {
                if (!mForceModeManagerQuickDozeRequest
                        && mModeManagerRequestedQuickDoze != enabled) {
                    mModeManagerRequestedQuickDoze = enabled;
                    onModeManagerRequestChangedLocked();
                }
            }
        }

        @GuardedBy("DeviceIdleController.this")
        private void onModeManagerRequestChangedLocked() {
            // Get into quick doze faster when mode manager requests instead of taking
            // traditional multi-stage approach.
            maybeBecomeActiveOnModeManagerEventsLocked();
            updateQuickDozeFlagLocked();
        }
    }

    @VisibleForTesting
    class ModeManagerOffBodyStateConsumer implements Consumer<Boolean> {
        @Override
        public void accept(Boolean isOffBody) {
            Slog.i(TAG, "Offbody event from mode manager: " + isOffBody);
            synchronized (DeviceIdleController.this) {
                if (!mForceModeManagerOffBodyState && mIsOffBody != isOffBody) {
                    mIsOffBody = isOffBody;
                    onModeManagerOffBodyChangedLocked();
                }
            }
        }

        @GuardedBy("DeviceIdleController.this")
        private void onModeManagerOffBodyChangedLocked() {
            maybeBecomeActiveOnModeManagerEventsLocked();
        }
    }

    @GuardedBy("DeviceIdleController.this")
    private void maybeBecomeActiveOnModeManagerEventsLocked() {
        synchronized (DeviceIdleController.this) {
            if (mQuickDozeActivated) {
                // Quick doze is enabled so don't turn the device active.
                return;
            }
            // Fall through when quick doze is not requested.

            if (!mIsOffBody && !mForceIdle) {
                // Quick doze wasn't requested, doze wasn't forced and device is on body
                // so turn the device active.
                mActiveReason = ACTIVE_REASON_ONBODY;
                becomeActiveLocked("on_body", Process.myUid());
            }
        }
    }

    @VisibleForTesting
    final ModeManagerQuickDozeRequestConsumer mModeManagerQuickDozeRequestConsumer =
            new ModeManagerQuickDozeRequestConsumer();

    @VisibleForTesting
    final ModeManagerOffBodyStateConsumer mModeManagerOffBodyStateConsumer =
            new ModeManagerOffBodyStateConsumer();

    @VisibleForTesting
    final class MotionListener extends TriggerEventListener
            implements SensorEventListener {

        boolean active = false;

        /**
         * Time in the elapsed realtime timebase when this listener was activated. Only valid if
         * {@link #active} is true.
         */
        long activatedTimeElapsed;

        public boolean isActive() {
            return active;
        }

        @Override
        public void onTrigger(TriggerEvent event) {
            synchronized (DeviceIdleController.this) {
                // One_shot sensors (which call onTrigger) are unregistered when onTrigger is called
                active = false;
                motionLocked();
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            synchronized (DeviceIdleController.this) {
                // Since one_shot sensors are unregistered when onTrigger is called, unregister
                // listeners here so that the MotionListener is in a consistent state when it calls
                // out to motionLocked.
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
                activatedTimeElapsed = mInjector.getElapsedRealtime();
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
    @VisibleForTesting final MotionListener mMotionListener = new MotionListener();

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
    public final class Constants extends ContentObserver
            implements DeviceConfig.OnPropertiesChangedListener {
        // Key names stored in the settings value.
        private static final String KEY_FLEX_TIME_SHORT = "flex_time_short";
        private static final String KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT =
                "light_after_inactive_to";
        private static final String KEY_LIGHT_IDLE_TIMEOUT = "light_idle_to";
        private static final String KEY_LIGHT_IDLE_TIMEOUT_INITIAL_FLEX =
                "light_idle_to_initial_flex";
        private static final String KEY_LIGHT_IDLE_TIMEOUT_MAX_FLEX = "light_max_idle_to_flex";
        private static final String KEY_LIGHT_IDLE_FACTOR = "light_idle_factor";
        private static final String KEY_LIGHT_IDLE_INCREASE_LINEARLY =
                "light_idle_increase_linearly";
        private static final String KEY_LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS =
                "light_idle_linear_increase_factor_ms";
        private static final String KEY_LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS =
                "light_idle_flex_linear_increase_factor_ms";
        private static final String KEY_LIGHT_MAX_IDLE_TIMEOUT = "light_max_idle_to";
        private static final String KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET =
                "light_idle_maintenance_min_budget";
        private static final String KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET =
                "light_idle_maintenance_max_budget";
        private static final String KEY_MIN_LIGHT_MAINTENANCE_TIME = "min_light_maintenance_time";
        private static final String KEY_MIN_DEEP_MAINTENANCE_TIME = "min_deep_maintenance_time";
        private static final String KEY_INACTIVE_TIMEOUT = "inactive_to";
        private static final String KEY_SENSING_TIMEOUT = "sensing_to";
        private static final String KEY_LOCATING_TIMEOUT = "locating_to";
        private static final String KEY_LOCATION_ACCURACY = "location_accuracy";
        private static final String KEY_MOTION_INACTIVE_TIMEOUT = "motion_inactive_to";
        private static final String KEY_MOTION_INACTIVE_TIMEOUT_FLEX = "motion_inactive_to_flex";
        private static final String KEY_IDLE_AFTER_INACTIVE_TIMEOUT = "idle_after_inactive_to";
        private static final String KEY_IDLE_PENDING_TIMEOUT = "idle_pending_to";
        private static final String KEY_MAX_IDLE_PENDING_TIMEOUT = "max_idle_pending_to";
        private static final String KEY_IDLE_PENDING_FACTOR = "idle_pending_factor";
        private static final String KEY_QUICK_DOZE_DELAY_TIMEOUT = "quick_doze_delay_to";
        private static final String KEY_IDLE_TIMEOUT = "idle_to";
        private static final String KEY_MAX_IDLE_TIMEOUT = "max_idle_to";
        private static final String KEY_IDLE_FACTOR = "idle_factor";
        private static final String KEY_MIN_TIME_TO_ALARM = "min_time_to_alarm";
        private static final String KEY_MAX_TEMP_APP_ALLOWLIST_DURATION_MS =
                "max_temp_app_allowlist_duration_ms";
        private static final String KEY_MMS_TEMP_APP_ALLOWLIST_DURATION_MS =
                "mms_temp_app_allowlist_duration_ms";
        private static final String KEY_SMS_TEMP_APP_ALLOWLIST_DURATION_MS =
                "sms_temp_app_allowlist_duration_ms";
        private static final String KEY_NOTIFICATION_ALLOWLIST_DURATION_MS =
                "notification_allowlist_duration_ms";
        /**
         * Whether to wait for the user to unlock the device before causing screen-on to
         * exit doze. Default = true
         */
        private static final String KEY_WAIT_FOR_UNLOCK = "wait_for_unlock";
        private static final String KEY_USE_WINDOW_ALARMS = "use_window_alarms";
        private static final String KEY_USE_MODE_MANAGER = "use_mode_manager";

        private long mDefaultFlexTimeShort =
                !COMPRESS_TIME ? 60 * 1000L : 5 * 1000L;
        private long mDefaultLightIdleAfterInactiveTimeout =
                !COMPRESS_TIME ? 4 * 60 * 1000L : 30 * 1000L;
        private long mDefaultLightIdleTimeout =
                !COMPRESS_TIME ? 5 * 60 * 1000L : 15 * 1000L;
        private long mDefaultLightIdleTimeoutInitialFlex =
                !COMPRESS_TIME ? 60 * 1000L : 5 * 1000L;
        private long mDefaultLightIdleTimeoutMaxFlex =
                !COMPRESS_TIME ? 15 * 60 * 1000L : 60 * 1000L;
        private float mDefaultLightIdleFactor = 2f;
        private boolean mDefaultLightIdleIncreaseLinearly;
        private long mDefaultLightIdleLinearIncreaseFactorMs = mDefaultLightIdleTimeout;
        private long mDefaultLightIdleFlexLinearIncreaseFactorMs =
                mDefaultLightIdleTimeoutInitialFlex;
        private long mDefaultLightMaxIdleTimeout =
                !COMPRESS_TIME ? 15 * 60 * 1000L : 60 * 1000L;
        private long mDefaultLightIdleMaintenanceMinBudget =
                !COMPRESS_TIME ? 1 * 60 * 1000L : 15 * 1000L;
        private long mDefaultLightIdleMaintenanceMaxBudget =
                !COMPRESS_TIME ? 5 * 60 * 1000L : 30 * 1000L;
        private long mDefaultMinLightMaintenanceTime =
                !COMPRESS_TIME ? 5 * 1000L : 1 * 1000L;
        private long mDefaultMinDeepMaintenanceTime =
                !COMPRESS_TIME ? 30 * 1000L : 5 * 1000L;
        private long mDefaultInactiveTimeout =
                (30 * 60 * 1000L) / (!COMPRESS_TIME ? 1 : 10);
        private static final long DEFAULT_INACTIVE_TIMEOUT_SMALL_BATTERY =
                (60 * 1000L) / (!COMPRESS_TIME ? 1 : 10);
        private long mDefaultSensingTimeout =
                !COMPRESS_TIME ? 4 * 60 * 1000L : 60 * 1000L;
        private long mDefaultLocatingTimeout =
                !COMPRESS_TIME ? 30 * 1000L : 15 * 1000L;
        private float mDefaultLocationAccuracy = 20f;
        private long mDefaultMotionInactiveTimeout =
                !COMPRESS_TIME ? 10 * 60 * 1000L : 60 * 1000L;
        private long mDefaultMotionInactiveTimeoutFlex =
                !COMPRESS_TIME ? 60 * 1000L : 5 * 1000L;
        private long mDefaultIdleAfterInactiveTimeout =
                (30 * 60 * 1000L) / (!COMPRESS_TIME ? 1 : 10);
        private static final long DEFAULT_IDLE_AFTER_INACTIVE_TIMEOUT_SMALL_BATTERY =
                (60 * 1000L) / (!COMPRESS_TIME ? 1 : 10);
        private long mDefaultIdlePendingTimeout =
                !COMPRESS_TIME ? 5 * 60 * 1000L : 30 * 1000L;
        private long mDefaultMaxIdlePendingTimeout =
                !COMPRESS_TIME ? 10 * 60 * 1000L : 60 * 1000L;
        private float mDefaultIdlePendingFactor = 2f;
        private long mDefaultQuickDozeDelayTimeout =
                !COMPRESS_TIME ? 60 * 1000L : 15 * 1000L;
        private long mDefaultIdleTimeout =
                !COMPRESS_TIME ? 60 * 60 * 1000L : 6 * 60 * 1000L;
        private long mDefaultMaxIdleTimeout =
                !COMPRESS_TIME ? 6 * 60 * 60 * 1000L : 30 * 60 * 1000L;
        private float mDefaultIdleFactor = 2f;
        private long mDefaultMinTimeToAlarm =
                !COMPRESS_TIME ? 30 * 60 * 1000L : 6 * 60 * 1000L;
        private long mDefaultMaxTempAppAllowlistDurationMs = 5 * 60 * 1000L;
        private long mDefaultMmsTempAppAllowlistDurationMs = 60 * 1000L;
        private long mDefaultSmsTempAppAllowlistDurationMs = 20 * 1000L;
        private long mDefaultNotificationAllowlistDurationMs = 30 * 1000L;
        private boolean mDefaultWaitForUnlock = true;
        private boolean mDefaultUseWindowAlarms = true;
        private boolean mDefaultUseModeManager = false;

        /**
         * A somewhat short alarm window size that we will tolerate for various alarm timings.
         *
         * @see #KEY_FLEX_TIME_SHORT
         */
        public long FLEX_TIME_SHORT = mDefaultFlexTimeShort;

        /**
         * This is the time, after becoming inactive, that we go in to the first
         * light-weight idle mode.
         *
         * @see #KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT
         */
        public long LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT = mDefaultLightIdleAfterInactiveTimeout;

        /**
         * This is the initial time that we will run in light idle maintenance mode.
         *
         * @see #KEY_LIGHT_IDLE_TIMEOUT
         */
        public long LIGHT_IDLE_TIMEOUT = mDefaultLightIdleTimeout;

        /**
         * This is the initial alarm window size that we will tolerate for light idle maintenance
         * timing.
         *
         * @see #KEY_LIGHT_IDLE_TIMEOUT_MAX_FLEX
         * @see #mNextLightIdleDelayFlex
         */
        public long LIGHT_IDLE_TIMEOUT_INITIAL_FLEX = mDefaultLightIdleTimeoutInitialFlex;

        /**
         * This is the maximum value that {@link #mNextLightIdleDelayFlex} should take.
         *
         * @see #KEY_LIGHT_IDLE_TIMEOUT_INITIAL_FLEX
         */
        public long LIGHT_IDLE_TIMEOUT_MAX_FLEX = mDefaultLightIdleTimeoutMaxFlex;

        /**
         * Scaling factor to apply to the light idle mode time each time we complete a cycle.
         *
         * @see #KEY_LIGHT_IDLE_FACTOR
         */
        public float LIGHT_IDLE_FACTOR = mDefaultLightIdleFactor;

        /**
         * Whether to increase the light idle mode time linearly or exponentially.
         * If true, will increase linearly
         * (i.e. {@link #LIGHT_IDLE_TIMEOUT} + x * {@link #LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS}).
         * If false, will increase by exponentially
         * (i.e. {@link #LIGHT_IDLE_TIMEOUT} * ({@link #LIGHT_IDLE_FACTOR} ^ x)).
         * This will also impact how the light idle flex value
         * ({@link #LIGHT_IDLE_TIMEOUT_INITIAL_FLEX}) is increased (using
         * {@link #LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS} for the linear increase)..
         *
         * @see #KEY_LIGHT_IDLE_INCREASE_LINEARLY
         */
        public boolean LIGHT_IDLE_INCREASE_LINEARLY = mDefaultLightIdleIncreaseLinearly;

        /**
         * Amount of time to increase the light idle time by, if increasing it linearly.
         *
         * @see #KEY_LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS
         * @see #LIGHT_IDLE_INCREASE_LINEARLY
         */
        public long LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS = mDefaultLightIdleLinearIncreaseFactorMs;

        /**
         * Amount of time to increase the light idle flex time by, if increasing it linearly.
         *
         * @see #KEY_LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS
         * @see #LIGHT_IDLE_INCREASE_LINEARLY
         */
        public long LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS =
                mDefaultLightIdleFlexLinearIncreaseFactorMs;

        /**
         * This is the maximum time we will stay in light idle mode.
         *
         * @see #KEY_LIGHT_MAX_IDLE_TIMEOUT
         */
        public long LIGHT_MAX_IDLE_TIMEOUT = mDefaultLightMaxIdleTimeout;

        /**
         * This is the minimum amount of time we want to make available for maintenance mode
         * when lightly idling.  That is, we will always have at least this amount of time
         * available maintenance before timing out and cutting off maintenance mode.
         *
         * @see #KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET
         */
        public long LIGHT_IDLE_MAINTENANCE_MIN_BUDGET = mDefaultLightIdleMaintenanceMinBudget;

        /**
         * This is the maximum amount of time we want to make available for maintenance mode
         * when lightly idling.  That is, if the system isn't using up its minimum maintenance
         * budget and this time is being added to the budget reserve, this is the maximum
         * reserve size we will allow to grow and thus the maximum amount of time we will
         * allow for the maintenance window.
         *
         * @see #KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET
         */
        public long LIGHT_IDLE_MAINTENANCE_MAX_BUDGET = mDefaultLightIdleMaintenanceMaxBudget;

        /**
         * This is the minimum amount of time that we will stay in maintenance mode after
         * a light doze.  We have this minimum to allow various things to respond to switching
         * in to maintenance mode and scheduling their work -- otherwise we may
         * see there is nothing to do (no jobs pending) and go out of maintenance
         * mode immediately.
         *
         * @see #KEY_MIN_LIGHT_MAINTENANCE_TIME
         */
        public long MIN_LIGHT_MAINTENANCE_TIME = mDefaultMinLightMaintenanceTime;

        /**
         * This is the minimum amount of time that we will stay in maintenance mode after
         * a full doze.  We have this minimum to allow various things to respond to switching
         * in to maintenance mode and scheduling their work -- otherwise we may
         * see there is nothing to do (no jobs pending) and go out of maintenance
         * mode immediately.
         * @see #KEY_MIN_DEEP_MAINTENANCE_TIME
         */
        public long MIN_DEEP_MAINTENANCE_TIME = mDefaultMinDeepMaintenanceTime;

        /**
         * This is the time, after becoming inactive, at which we start looking at the
         * motion sensor to determine if the device is being left alone.  We don't do this
         * immediately after going inactive just because we don't want to be continually running
         * the motion sensor whenever the screen is off.
         * @see #KEY_INACTIVE_TIMEOUT
         */
        public long INACTIVE_TIMEOUT = mDefaultInactiveTimeout;

        /**
         * If we don't receive a callback from AnyMotion in this amount of time +
         * {@link #LOCATING_TIMEOUT}, we will change from
         * STATE_SENSING to STATE_INACTIVE, and any AnyMotion callbacks while not in STATE_SENSING
         * will be ignored.
         * @see #KEY_SENSING_TIMEOUT
         */
        public long SENSING_TIMEOUT = mDefaultSensingTimeout;

        /**
         * This is how long we will wait to try to get a good location fix before going in to
         * idle mode.
         * @see #KEY_LOCATING_TIMEOUT
         */
        public long LOCATING_TIMEOUT = mDefaultLocatingTimeout;

        /**
         * The desired maximum accuracy (in meters) we consider the location to be good enough to go
         * on to idle.  We will be trying to get an accuracy fix at least this good or until
         * {@link #LOCATING_TIMEOUT} expires.
         * @see #KEY_LOCATION_ACCURACY
         */
        public float LOCATION_ACCURACY = mDefaultLocationAccuracy;

        /**
         * This is the time, after seeing motion, that we wait after becoming inactive from
         * that until we start looking for motion again.
         *
         * @see #KEY_MOTION_INACTIVE_TIMEOUT
         */
        public long MOTION_INACTIVE_TIMEOUT = mDefaultMotionInactiveTimeout;

        /**
         * This is the alarm window size we will tolerate for motion detection timings.
         *
         * @see #KEY_MOTION_INACTIVE_TIMEOUT_FLEX
         */
        public long MOTION_INACTIVE_TIMEOUT_FLEX = mDefaultMotionInactiveTimeoutFlex;

        /**
         * This is the time, after the inactive timeout elapses, that we will wait looking
         * for motion until we truly consider the device to be idle.
         *
         * @see #KEY_IDLE_AFTER_INACTIVE_TIMEOUT
         */
        public long IDLE_AFTER_INACTIVE_TIMEOUT = mDefaultIdleAfterInactiveTimeout;

        /**
         * This is the initial time, after being idle, that we will allow ourself to be back
         * in the IDLE_MAINTENANCE state allowing the system to run normally until we return to
         * idle.
         * @see #KEY_IDLE_PENDING_TIMEOUT
         */
        public long IDLE_PENDING_TIMEOUT = mDefaultIdlePendingTimeout;

        /**
         * Maximum pending idle timeout (time spent running) we will be allowed to use.
         * @see #KEY_MAX_IDLE_PENDING_TIMEOUT
         */
        public long MAX_IDLE_PENDING_TIMEOUT = mDefaultMaxIdlePendingTimeout;

        /**
         * Scaling factor to apply to current pending idle timeout each time we cycle through
         * that state.
         * @see #KEY_IDLE_PENDING_FACTOR
         */
        public float IDLE_PENDING_FACTOR = mDefaultIdlePendingFactor;

        /**
         * This is amount of time we will wait from the point where we go into
         * STATE_QUICK_DOZE_DELAY until we actually go into STATE_IDLE, while waiting for jobs
         * and other current activity to finish.
         * @see #KEY_QUICK_DOZE_DELAY_TIMEOUT
         */
        public long QUICK_DOZE_DELAY_TIMEOUT = mDefaultQuickDozeDelayTimeout;

        /**
         * This is the initial time that we want to sit in the idle state before waking up
         * again to return to pending idle and allowing normal work to run.
         * @see #KEY_IDLE_TIMEOUT
         */
        public long IDLE_TIMEOUT = mDefaultIdleTimeout;

        /**
         * Maximum idle duration we will be allowed to use.
         * @see #KEY_MAX_IDLE_TIMEOUT
         */
        public long MAX_IDLE_TIMEOUT = mDefaultMaxIdleTimeout;

        /**
         * Scaling factor to apply to current idle timeout each time we cycle through that state.
         * @see #KEY_IDLE_FACTOR
         */
        public float IDLE_FACTOR = mDefaultIdleFactor;

        /**
         * This is the minimum time we will allow until the next upcoming alarm for us to
         * actually go in to idle mode.
         * @see #KEY_MIN_TIME_TO_ALARM
         */
        public long MIN_TIME_TO_ALARM = mDefaultMinTimeToAlarm;

        /**
         * Max amount of time to temporarily whitelist an app when it receives a high priority
         * tickle.
         *
         * @see #KEY_MAX_TEMP_APP_ALLOWLIST_DURATION_MS
         */
        public long MAX_TEMP_APP_ALLOWLIST_DURATION_MS = mDefaultMaxTempAppAllowlistDurationMs;

        /**
         * Amount of time we would like to whitelist an app that is receiving an MMS.
         * @see #KEY_MMS_TEMP_APP_ALLOWLIST_DURATION_MS
         */
        public long MMS_TEMP_APP_ALLOWLIST_DURATION_MS = mDefaultMmsTempAppAllowlistDurationMs;

        /**
         * Amount of time we would like to whitelist an app that is receiving an SMS.
         * @see #KEY_SMS_TEMP_APP_ALLOWLIST_DURATION_MS
         */
        public long SMS_TEMP_APP_ALLOWLIST_DURATION_MS = mDefaultSmsTempAppAllowlistDurationMs;

        /**
         * Amount of time we would like to whitelist an app that is handling a
         * {@link android.app.PendingIntent} triggered by a {@link android.app.Notification}.
         *
         * @see #KEY_NOTIFICATION_ALLOWLIST_DURATION_MS
         */
        public long NOTIFICATION_ALLOWLIST_DURATION_MS = mDefaultNotificationAllowlistDurationMs;

        public boolean WAIT_FOR_UNLOCK = mDefaultWaitForUnlock;

        /**
         * Whether to use window alarms. True to use window alarms (call AlarmManager.setWindow()).
         * False to use the legacy inexact alarms (call AlarmManager.set()).
         */
        public boolean USE_WINDOW_ALARMS = mDefaultUseWindowAlarms;

        /**
         * Whether to use an on/off body signal to affect state transition policy.
         */
        public boolean USE_MODE_MANAGER = mDefaultUseModeManager;

        private final ContentResolver mResolver;
        private final boolean mSmallBatteryDevice;
        private final UserSettingDeviceConfigMediator mUserSettingDeviceConfigMediator =
                new UserSettingDeviceConfigMediator.SettingsOverridesIndividualMediator(',');

        public Constants(Handler handler, ContentResolver resolver) {
            super(handler);
            mResolver = resolver;
            initDefault();
            mSmallBatteryDevice = ActivityManager.isSmallBatteryDevice();
            if (mSmallBatteryDevice) {
                INACTIVE_TIMEOUT = DEFAULT_INACTIVE_TIMEOUT_SMALL_BATTERY;
                IDLE_AFTER_INACTIVE_TIMEOUT = DEFAULT_IDLE_AFTER_INACTIVE_TIMEOUT_SMALL_BATTERY;
            }
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DEVICE_IDLE,
                    AppSchedulingModuleThread.getExecutor(), this);
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.DEVICE_IDLE_CONSTANTS),
                    false, this);
            // Load all the constants.
            updateSettingsConstantLocked();
            mUserSettingDeviceConfigMediator.setDeviceConfigProperties(
                    DeviceConfig.getProperties(DeviceConfig.NAMESPACE_DEVICE_IDLE));
            updateConstantsLocked();
        }

        private void initDefault() {
            final Resources res = getContext().getResources();

            mDefaultFlexTimeShort = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_flex_time_short_ms),
                    mDefaultFlexTimeShort);
            mDefaultLightIdleAfterInactiveTimeout = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_light_after_inactive_to_ms),
                    mDefaultLightIdleAfterInactiveTimeout);
            mDefaultLightIdleTimeout = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_light_idle_to_ms),
                    mDefaultLightIdleTimeout);
            mDefaultLightIdleTimeoutInitialFlex = getTimeout(
                    res.getInteger(
                            com.android.internal.R.integer.device_idle_light_idle_to_init_flex_ms),
                    mDefaultLightIdleTimeoutInitialFlex);
            mDefaultLightIdleTimeoutMaxFlex = getTimeout(
                    res.getInteger(
                            com.android.internal.R.integer.device_idle_light_idle_to_max_flex_ms),
                    mDefaultLightIdleTimeoutMaxFlex);
            mDefaultLightIdleFactor = res.getFloat(
                    com.android.internal.R.integer.device_idle_light_idle_factor);
            mDefaultLightIdleIncreaseLinearly = res.getBoolean(
                    com.android.internal.R.bool.device_idle_light_idle_increase_linearly);
            mDefaultLightIdleLinearIncreaseFactorMs = getTimeout(res.getInteger(
                    com.android.internal.R.integer
                            .device_idle_light_idle_linear_increase_factor_ms),
                    mDefaultLightIdleLinearIncreaseFactorMs);
            mDefaultLightIdleFlexLinearIncreaseFactorMs = getTimeout(res.getInteger(
                    com.android.internal.R.integer
                            .device_idle_light_idle_flex_linear_increase_factor_ms),
                    mDefaultLightIdleFlexLinearIncreaseFactorMs);
            mDefaultLightMaxIdleTimeout = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_light_max_idle_to_ms),
                    mDefaultLightMaxIdleTimeout);
            mDefaultLightIdleMaintenanceMinBudget = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_light_idle_maintenance_min_budget_ms
                    ), mDefaultLightIdleMaintenanceMinBudget);
            mDefaultLightIdleMaintenanceMaxBudget = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_light_idle_maintenance_max_budget_ms
                    ), mDefaultLightIdleMaintenanceMaxBudget);
            mDefaultMinLightMaintenanceTime = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_min_light_maintenance_time_ms),
                    mDefaultMinLightMaintenanceTime);
            mDefaultMinDeepMaintenanceTime = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_min_deep_maintenance_time_ms),
                    mDefaultMinDeepMaintenanceTime);
            mDefaultInactiveTimeout = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_inactive_to_ms),
                    mDefaultInactiveTimeout);
            mDefaultSensingTimeout = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_sensing_to_ms),
                    mDefaultSensingTimeout);
            mDefaultLocatingTimeout = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_locating_to_ms),
                    mDefaultLocatingTimeout);
            mDefaultLocationAccuracy = res.getFloat(
                    com.android.internal.R.integer.device_idle_location_accuracy);
            mDefaultMotionInactiveTimeout = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_motion_inactive_to_ms),
                    mDefaultMotionInactiveTimeout);
            mDefaultMotionInactiveTimeoutFlex = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_motion_inactive_to_flex_ms),
                    mDefaultMotionInactiveTimeoutFlex);
            mDefaultIdleAfterInactiveTimeout = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_idle_after_inactive_to_ms),
                    mDefaultIdleAfterInactiveTimeout);
            mDefaultIdlePendingTimeout = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_idle_pending_to_ms),
                    mDefaultIdlePendingTimeout);
            mDefaultMaxIdlePendingTimeout = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_max_idle_pending_to_ms),
                    mDefaultMaxIdlePendingTimeout);
            mDefaultIdlePendingFactor = res.getFloat(
                    com.android.internal.R.integer.device_idle_idle_pending_factor);
            mDefaultQuickDozeDelayTimeout = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_quick_doze_delay_to_ms),
                    mDefaultQuickDozeDelayTimeout);
            mDefaultIdleTimeout = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_idle_to_ms),
                    mDefaultIdleTimeout);
            mDefaultMaxIdleTimeout = getTimeout(
                    res.getInteger(com.android.internal.R.integer.device_idle_max_idle_to_ms),
                    mDefaultMaxIdleTimeout);
            mDefaultIdleFactor = res.getFloat(
                    com.android.internal.R.integer.device_idle_idle_factor);
            mDefaultMinTimeToAlarm = getTimeout(res.getInteger(
                    com.android.internal.R.integer.device_idle_min_time_to_alarm_ms),
                    mDefaultMinTimeToAlarm);
            mDefaultMaxTempAppAllowlistDurationMs = res.getInteger(
                    com.android.internal.R.integer.device_idle_max_temp_app_allowlist_duration_ms);
            mDefaultMmsTempAppAllowlistDurationMs = res.getInteger(
                    com.android.internal.R.integer.device_idle_mms_temp_app_allowlist_duration_ms);
            mDefaultSmsTempAppAllowlistDurationMs = res.getInteger(
                    com.android.internal.R.integer.device_idle_sms_temp_app_allowlist_duration_ms);
            mDefaultNotificationAllowlistDurationMs = res.getInteger(
                    com.android.internal.R.integer.device_idle_notification_allowlist_duration_ms);
            mDefaultWaitForUnlock = res.getBoolean(
                    com.android.internal.R.bool.device_idle_wait_for_unlock);
            mDefaultUseWindowAlarms = res.getBoolean(
                    com.android.internal.R.bool.device_idle_use_window_alarms);
            mDefaultUseModeManager = res.getBoolean(
                    com.android.internal.R.bool.device_idle_use_mode_manager);

            FLEX_TIME_SHORT = mDefaultFlexTimeShort;
            LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT = mDefaultLightIdleAfterInactiveTimeout;
            LIGHT_IDLE_TIMEOUT = mDefaultLightIdleTimeout;
            LIGHT_IDLE_TIMEOUT_INITIAL_FLEX = mDefaultLightIdleTimeoutInitialFlex;
            LIGHT_IDLE_TIMEOUT_MAX_FLEX = mDefaultLightIdleTimeoutMaxFlex;
            LIGHT_IDLE_FACTOR = mDefaultLightIdleFactor;
            LIGHT_IDLE_INCREASE_LINEARLY = mDefaultLightIdleIncreaseLinearly;
            LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS = mDefaultLightIdleLinearIncreaseFactorMs;
            LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS = mDefaultLightIdleFlexLinearIncreaseFactorMs;
            LIGHT_MAX_IDLE_TIMEOUT = mDefaultLightMaxIdleTimeout;
            LIGHT_IDLE_MAINTENANCE_MIN_BUDGET = mDefaultLightIdleMaintenanceMinBudget;
            LIGHT_IDLE_MAINTENANCE_MAX_BUDGET = mDefaultLightIdleMaintenanceMaxBudget;
            MIN_LIGHT_MAINTENANCE_TIME = mDefaultMinLightMaintenanceTime;
            MIN_DEEP_MAINTENANCE_TIME = mDefaultMinDeepMaintenanceTime;
            INACTIVE_TIMEOUT = mDefaultInactiveTimeout;
            SENSING_TIMEOUT = mDefaultSensingTimeout;
            LOCATING_TIMEOUT = mDefaultLocatingTimeout;
            LOCATION_ACCURACY = mDefaultLocationAccuracy;
            MOTION_INACTIVE_TIMEOUT = mDefaultMotionInactiveTimeout;
            MOTION_INACTIVE_TIMEOUT_FLEX = mDefaultMotionInactiveTimeoutFlex;
            IDLE_AFTER_INACTIVE_TIMEOUT = mDefaultIdleAfterInactiveTimeout;
            IDLE_PENDING_TIMEOUT = mDefaultIdlePendingTimeout;
            MAX_IDLE_PENDING_TIMEOUT = mDefaultMaxIdlePendingTimeout;
            IDLE_PENDING_FACTOR = mDefaultIdlePendingFactor;
            QUICK_DOZE_DELAY_TIMEOUT = mDefaultQuickDozeDelayTimeout;
            IDLE_TIMEOUT = mDefaultIdleTimeout;
            MAX_IDLE_TIMEOUT = mDefaultMaxIdleTimeout;
            IDLE_FACTOR = mDefaultIdleFactor;
            MIN_TIME_TO_ALARM = mDefaultMinTimeToAlarm;
            MAX_TEMP_APP_ALLOWLIST_DURATION_MS = mDefaultMaxTempAppAllowlistDurationMs;
            MMS_TEMP_APP_ALLOWLIST_DURATION_MS = mDefaultMmsTempAppAllowlistDurationMs;
            SMS_TEMP_APP_ALLOWLIST_DURATION_MS = mDefaultSmsTempAppAllowlistDurationMs;
            NOTIFICATION_ALLOWLIST_DURATION_MS = mDefaultNotificationAllowlistDurationMs;
            WAIT_FOR_UNLOCK = mDefaultWaitForUnlock;
            USE_WINDOW_ALARMS = mDefaultUseWindowAlarms;
            USE_MODE_MANAGER = mDefaultUseModeManager;
        }

        private long getTimeout(long defTimeout, long compTimeout) {
            return (!COMPRESS_TIME || defTimeout < compTimeout) ? defTimeout : compTimeout;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (DeviceIdleController.this) {
                updateSettingsConstantLocked();
                updateConstantsLocked();
            }
        }

        private void updateSettingsConstantLocked() {
            try {
                mUserSettingDeviceConfigMediator.setSettingsString(
                        Settings.Global.getString(mResolver,
                                Settings.Global.DEVICE_IDLE_CONSTANTS));
            } catch (IllegalArgumentException e) {
                // Failed to parse the settings string, log this and move on with previous values.
                Slog.e(TAG, "Bad device idle settings", e);
            }
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            synchronized (DeviceIdleController.this) {
                mUserSettingDeviceConfigMediator.setDeviceConfigProperties(properties);
                updateConstantsLocked();
            }
        }

        private void updateConstantsLocked() {
            if (mSmallBatteryDevice) return;
            FLEX_TIME_SHORT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_FLEX_TIME_SHORT, mDefaultFlexTimeShort);

            LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT,
                    mDefaultLightIdleAfterInactiveTimeout);

            LIGHT_IDLE_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_IDLE_TIMEOUT, mDefaultLightIdleTimeout);

            LIGHT_IDLE_TIMEOUT_INITIAL_FLEX = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_IDLE_TIMEOUT_INITIAL_FLEX,
                    mDefaultLightIdleTimeoutInitialFlex);

            LIGHT_IDLE_TIMEOUT_MAX_FLEX = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_IDLE_TIMEOUT_MAX_FLEX,
                    mDefaultLightIdleTimeoutMaxFlex);

            LIGHT_IDLE_FACTOR = Math.max(1, mUserSettingDeviceConfigMediator.getFloat(
                    KEY_LIGHT_IDLE_FACTOR, mDefaultLightIdleFactor));

            LIGHT_IDLE_INCREASE_LINEARLY = mUserSettingDeviceConfigMediator.getBoolean(
                    KEY_LIGHT_IDLE_INCREASE_LINEARLY,
                    mDefaultLightIdleIncreaseLinearly);

            LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS,
                    mDefaultLightIdleLinearIncreaseFactorMs);

            LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS,
                    mDefaultLightIdleFlexLinearIncreaseFactorMs);

            LIGHT_MAX_IDLE_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_MAX_IDLE_TIMEOUT, mDefaultLightMaxIdleTimeout);

            LIGHT_IDLE_MAINTENANCE_MIN_BUDGET = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET,
                    mDefaultLightIdleMaintenanceMinBudget);

            LIGHT_IDLE_MAINTENANCE_MAX_BUDGET = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET,
                    mDefaultLightIdleMaintenanceMaxBudget);

            MIN_LIGHT_MAINTENANCE_TIME = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MIN_LIGHT_MAINTENANCE_TIME,
                    mDefaultMinLightMaintenanceTime);

            MIN_DEEP_MAINTENANCE_TIME = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MIN_DEEP_MAINTENANCE_TIME,
                    mDefaultMinDeepMaintenanceTime);

            final long defaultInactiveTimeout = mSmallBatteryDevice
                    ? DEFAULT_INACTIVE_TIMEOUT_SMALL_BATTERY
                    : mDefaultInactiveTimeout;
            INACTIVE_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_INACTIVE_TIMEOUT, defaultInactiveTimeout);

            SENSING_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_SENSING_TIMEOUT, mDefaultSensingTimeout);

            LOCATING_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_LOCATING_TIMEOUT, mDefaultLocatingTimeout);

            LOCATION_ACCURACY = mUserSettingDeviceConfigMediator.getFloat(
                    KEY_LOCATION_ACCURACY, mDefaultLocationAccuracy);

            MOTION_INACTIVE_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MOTION_INACTIVE_TIMEOUT, mDefaultMotionInactiveTimeout);

            MOTION_INACTIVE_TIMEOUT_FLEX = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MOTION_INACTIVE_TIMEOUT_FLEX,
                    mDefaultMotionInactiveTimeoutFlex);

            final long defaultIdleAfterInactiveTimeout = mSmallBatteryDevice
                    ? DEFAULT_IDLE_AFTER_INACTIVE_TIMEOUT_SMALL_BATTERY
                    : mDefaultIdleAfterInactiveTimeout;
            IDLE_AFTER_INACTIVE_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_IDLE_AFTER_INACTIVE_TIMEOUT,
                    defaultIdleAfterInactiveTimeout);

            IDLE_PENDING_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_IDLE_PENDING_TIMEOUT, mDefaultIdlePendingTimeout);

            MAX_IDLE_PENDING_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MAX_IDLE_PENDING_TIMEOUT, mDefaultMaxIdlePendingTimeout);

            IDLE_PENDING_FACTOR = mUserSettingDeviceConfigMediator.getFloat(
                    KEY_IDLE_PENDING_FACTOR, mDefaultIdlePendingFactor);

            QUICK_DOZE_DELAY_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_QUICK_DOZE_DELAY_TIMEOUT, mDefaultQuickDozeDelayTimeout);

            IDLE_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_IDLE_TIMEOUT, mDefaultIdleTimeout);

            MAX_IDLE_TIMEOUT = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MAX_IDLE_TIMEOUT, mDefaultMaxIdleTimeout);

            IDLE_FACTOR = mUserSettingDeviceConfigMediator.getFloat(KEY_IDLE_FACTOR,
                    mDefaultIdleFactor);

            MIN_TIME_TO_ALARM = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MIN_TIME_TO_ALARM, mDefaultMinTimeToAlarm);

            MAX_TEMP_APP_ALLOWLIST_DURATION_MS = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MAX_TEMP_APP_ALLOWLIST_DURATION_MS,
                    mDefaultMaxTempAppAllowlistDurationMs);

            MMS_TEMP_APP_ALLOWLIST_DURATION_MS = mUserSettingDeviceConfigMediator.getLong(
                    KEY_MMS_TEMP_APP_ALLOWLIST_DURATION_MS,
                    mDefaultMmsTempAppAllowlistDurationMs);

            SMS_TEMP_APP_ALLOWLIST_DURATION_MS = mUserSettingDeviceConfigMediator.getLong(
                    KEY_SMS_TEMP_APP_ALLOWLIST_DURATION_MS,
                    mDefaultSmsTempAppAllowlistDurationMs);

            NOTIFICATION_ALLOWLIST_DURATION_MS = mUserSettingDeviceConfigMediator.getLong(
                    KEY_NOTIFICATION_ALLOWLIST_DURATION_MS,
                    mDefaultNotificationAllowlistDurationMs);

            WAIT_FOR_UNLOCK = mUserSettingDeviceConfigMediator.getBoolean(
                    KEY_WAIT_FOR_UNLOCK, mDefaultWaitForUnlock);

            USE_WINDOW_ALARMS = mUserSettingDeviceConfigMediator.getBoolean(
                    KEY_USE_WINDOW_ALARMS, mDefaultUseWindowAlarms);

            USE_MODE_MANAGER = mUserSettingDeviceConfigMediator.getBoolean(
                    KEY_USE_MODE_MANAGER, mDefaultUseModeManager);
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings:");

            pw.print("    "); pw.print(KEY_FLEX_TIME_SHORT); pw.print("=");
            TimeUtils.formatDuration(FLEX_TIME_SHORT, pw);
            pw.println();

            pw.print("    ");
            pw.print(KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT);
            pw.print("=");
            TimeUtils.formatDuration(LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(LIGHT_IDLE_TIMEOUT, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_TIMEOUT_INITIAL_FLEX); pw.print("=");
            TimeUtils.formatDuration(LIGHT_IDLE_TIMEOUT_INITIAL_FLEX, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_TIMEOUT_MAX_FLEX); pw.print("=");
            TimeUtils.formatDuration(LIGHT_IDLE_TIMEOUT_MAX_FLEX, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_FACTOR); pw.print("=");
            pw.print(LIGHT_IDLE_FACTOR);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_INCREASE_LINEARLY); pw.print("=");
            pw.print(LIGHT_IDLE_INCREASE_LINEARLY);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS);
            pw.print("=");
            pw.print(LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS);
            pw.println();

            pw.print("    "); pw.print(KEY_LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS);
            pw.print("=");
            pw.print(LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS);
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

            pw.print("    "); pw.print(KEY_MOTION_INACTIVE_TIMEOUT_FLEX); pw.print("=");
            TimeUtils.formatDuration(MOTION_INACTIVE_TIMEOUT_FLEX, pw);
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

            pw.print("    "); pw.print(KEY_QUICK_DOZE_DELAY_TIMEOUT); pw.print("=");
            TimeUtils.formatDuration(QUICK_DOZE_DELAY_TIMEOUT, pw);
            pw.println();

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

            pw.print("    "); pw.print(KEY_MAX_TEMP_APP_ALLOWLIST_DURATION_MS); pw.print("=");
            TimeUtils.formatDuration(MAX_TEMP_APP_ALLOWLIST_DURATION_MS, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_MMS_TEMP_APP_ALLOWLIST_DURATION_MS); pw.print("=");
            TimeUtils.formatDuration(MMS_TEMP_APP_ALLOWLIST_DURATION_MS, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_SMS_TEMP_APP_ALLOWLIST_DURATION_MS); pw.print("=");
            TimeUtils.formatDuration(SMS_TEMP_APP_ALLOWLIST_DURATION_MS, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_NOTIFICATION_ALLOWLIST_DURATION_MS); pw.print("=");
            TimeUtils.formatDuration(NOTIFICATION_ALLOWLIST_DURATION_MS, pw);
            pw.println();

            pw.print("    "); pw.print(KEY_WAIT_FOR_UNLOCK); pw.print("=");
            pw.println(WAIT_FOR_UNLOCK);

            pw.print("    "); pw.print(KEY_USE_WINDOW_ALARMS); pw.print("=");
            pw.println(USE_WINDOW_ALARMS);

            pw.print("    "); pw.print(KEY_USE_MODE_MANAGER); pw.print("=");
            pw.println(USE_MODE_MANAGER);
        }
    }

    private Constants mConstants;

    @Override
    public void onAnyMotionResult(int result) {
        if (DEBUG) Slog.d(TAG, "onAnyMotionResult(" + result + ")");
        synchronized (this) {
            if (result != AnyMotionDetector.RESULT_UNKNOWN) {
                cancelSensingTimeoutAlarmLocked();
            }
            if ((result == AnyMotionDetector.RESULT_MOVED)
                    || (result == AnyMotionDetector.RESULT_UNKNOWN)) {
                handleMotionDetectedLocked(mConstants.INACTIVE_TIMEOUT, "non_stationary");
            } else if (result == AnyMotionDetector.RESULT_STATIONARY) {
                if (mState == STATE_SENSING) {
                    // If we are currently sensing, it is time to move to locating.
                    mNotMoving = true;
                    stepIdleStateLocked("s:stationary");
                } else if (mState == STATE_LOCATING) {
                    // If we are currently locating, note that we are not moving and step
                    // if we have located the position.
                    mNotMoving = true;
                    if (mLocated) {
                        stepIdleStateLocked("s:stationary");
                    }
                }
            }
        }
    }

    private static final int MSG_WRITE_CONFIG = 1;
    private static final int MSG_REPORT_IDLE_ON = 2;
    private static final int MSG_REPORT_IDLE_ON_LIGHT = 3;
    private static final int MSG_REPORT_IDLE_OFF = 4;
    private static final int MSG_REPORT_ACTIVE = 5;
    private static final int MSG_TEMP_APP_WHITELIST_TIMEOUT = 6;
    @VisibleForTesting
    static final int MSG_REPORT_STATIONARY_STATUS = 7;
    private static final int MSG_FINISH_IDLE_OP = 8;
    private static final int MSG_SEND_CONSTRAINT_MONITORING = 10;
    private static final int MSG_REPORT_TEMP_APP_WHITELIST_CHANGED = 13;
    private static final int MSG_REPORT_TEMP_APP_WHITELIST_ADDED_TO_NPMS = 14;
    private static final int MSG_REPORT_TEMP_APP_WHITELIST_REMOVED_TO_NPMS = 15;

    final class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }

        @Override public void handleMessage(Message msg) {
            if (DEBUG) Slog.d(TAG, "handleMessage(" + msg.what + ")");
            switch (msg.what) {
                case MSG_WRITE_CONFIG: {
                    // Does not hold a wakelock. Just let this happen whenever.
                    handleWriteConfigFile();
                } break;
                case MSG_REPORT_IDLE_ON:
                case MSG_REPORT_IDLE_ON_LIGHT: {
                    // mGoingIdleWakeLock is held at this point
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
                        getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL,
                                null /* receiverPermission */, mIdleIntentOptions);
                    }
                    if (lightChanged) {
                        getContext().sendBroadcastAsUser(mLightIdleIntent, UserHandle.ALL,
                                null /* receiverPermission */, mLightIdleIntentOptions);
                    }
                    EventLogTags.writeDeviceIdleOnComplete();
                    mGoingIdleWakeLock.release();
                } break;
                case MSG_REPORT_IDLE_OFF: {
                    // mActiveIdleWakeLock is held at this point
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
                        mLocalActivityManager.broadcastIntentWithCallback(mIdleIntent,
                                mIdleStartedDoneReceiver, null, UserHandle.USER_ALL,
                                null, null, mIdleIntentOptions);
                    }
                    if (lightChanged) {
                        incActiveIdleOps();
                        mLocalActivityManager.broadcastIntentWithCallback(mLightIdleIntent,
                                mIdleStartedDoneReceiver, null, UserHandle.USER_ALL,
                                null, null, mLightIdleIntentOptions);
                    }
                    // Always start with one active op for the message being sent here.
                    // Now we are done!
                    decActiveIdleOps();
                    EventLogTags.writeDeviceIdleOffComplete();
                } break;
                case MSG_REPORT_ACTIVE: {
                    // The device is awake at this point, so no wakelock necessary.
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
                        getContext().sendBroadcastAsUser(mIdleIntent, UserHandle.ALL,
                                null /* receiverPermission */, mIdleIntentOptions);
                    }
                    if (lightChanged) {
                        getContext().sendBroadcastAsUser(mLightIdleIntent, UserHandle.ALL,
                                null /* receiverPermission */, mLightIdleIntentOptions);
                    }
                    EventLogTags.writeDeviceIdleOffComplete();
                } break;
                case MSG_TEMP_APP_WHITELIST_TIMEOUT: {
                    // TODO: What is keeping the device awake at this point? Does it need to be?
                    int uid = msg.arg1;
                    checkTempAppWhitelistTimeout(uid);
                } break;
                case MSG_FINISH_IDLE_OP: {
                    // mActiveIdleWakeLock is held at this point
                    decActiveIdleOps();
                } break;
                case MSG_REPORT_TEMP_APP_WHITELIST_CHANGED: {
                    final int uid = msg.arg1;
                    final boolean added = (msg.arg2 == 1);
                    PowerAllowlistInternal.TempAllowlistChangeListener[] listeners;
                    synchronized (DeviceIdleController.this) {
                        listeners = mTempAllowlistChangeListeners.toArray(
                                new PowerAllowlistInternal.TempAllowlistChangeListener[
                                        mTempAllowlistChangeListeners.size()]);
                    }
                    for (PowerAllowlistInternal.TempAllowlistChangeListener listener : listeners) {
                        if (added) {
                            listener.onAppAdded(uid);
                        } else {
                            listener.onAppRemoved(uid);
                        }
                    }
                } break;
                case MSG_REPORT_TEMP_APP_WHITELIST_ADDED_TO_NPMS: {
                    final int appId = msg.arg1;
                    final int reasonCode = msg.arg2;
                    final String reason = (String) msg.obj;
                    mNetworkPolicyManagerInternal.onTempPowerSaveWhitelistChange(appId, true,
                            reasonCode, reason);
                } break;
                case MSG_REPORT_TEMP_APP_WHITELIST_REMOVED_TO_NPMS: {
                    final int appId = msg.arg1;
                    mNetworkPolicyManagerInternal.onTempPowerSaveWhitelistChange(appId, false,
                            REASON_UNKNOWN, /* reason= */ null);
                } break;
                case MSG_SEND_CONSTRAINT_MONITORING: {
                    final IDeviceIdleConstraint constraint = (IDeviceIdleConstraint) msg.obj;
                    final boolean monitoring = (msg.arg1 == 1);
                    if (monitoring) {
                        constraint.startMonitoring();
                    } else {
                        constraint.stopMonitoring();
                    }
                } break;
                case MSG_REPORT_STATIONARY_STATUS: {
                    final DeviceIdleInternal.StationaryListener newListener =
                            (DeviceIdleInternal.StationaryListener) msg.obj;
                    final DeviceIdleInternal.StationaryListener[] listeners;
                    final boolean isStationary;
                    synchronized (DeviceIdleController.this) {
                        isStationary = isStationaryLocked();
                        if (newListener == null) {
                            // Only notify all listeners if we aren't directing to one listener.
                            listeners = mStationaryListeners.toArray(
                                    new DeviceIdleInternal.StationaryListener[
                                            mStationaryListeners.size()]);
                        } else {
                            listeners = null;
                        }
                    }
                    if (listeners != null) {
                        for (DeviceIdleInternal.StationaryListener listener : listeners) {
                            listener.onDeviceStationaryChanged(isStationary);
                        }
                    }
                    if (newListener != null) {
                        newListener.onDeviceStationaryChanged(isStationary);
                    }
                }
                break;
            }
        }
    }

    final MyHandler mHandler;

    BinderService mBinderService;

    private final class BinderService extends IDeviceIdleController.Stub {
        @Override public void addPowerSaveWhitelistApp(String name) {
            if (DEBUG) {
                Slog.i(TAG, "addPowerSaveWhitelistApp(name = " + name + ")");
            }
            addPowerSaveWhitelistApps(Collections.singletonList(name));
        }

        @Override
        public int addPowerSaveWhitelistApps(List<String> packageNames) {
            if (DEBUG) {
                Slog.i(TAG,
                        "addPowerSaveWhitelistApps(name = " + packageNames + ")");
            }
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            final long ident = Binder.clearCallingIdentity();
            try {
                return addPowerSaveWhitelistAppsInternal(packageNames);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override public void removePowerSaveWhitelistApp(String name) {
            if (DEBUG) {
                Slog.i(TAG, "removePowerSaveWhitelistApp(name = " + name + ")");
            }
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            final long ident = Binder.clearCallingIdentity();
            try {
                if (!removePowerSaveWhitelistAppInternal(name)
                        && mPowerSaveWhitelistAppsExceptIdle.containsKey(name)) {
                    throw new UnsupportedOperationException("Cannot remove system whitelisted app");
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override public void removeSystemPowerWhitelistApp(String name) {
            if (DEBUG) {
                Slog.d(TAG, "removeAppFromSystemWhitelist(name = " + name + ")");
            }
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            final long ident = Binder.clearCallingIdentity();
            try {
                removeSystemPowerWhitelistAppInternal(name);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override public void restoreSystemPowerWhitelistApp(String name) {
            if (DEBUG) {
                Slog.d(TAG, "restoreAppToSystemWhitelist(name = " + name + ")");
            }
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            final long ident = Binder.clearCallingIdentity();
            try {
                restoreSystemPowerWhitelistAppInternal(name);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public String[] getRemovedSystemPowerWhitelistApps() {
            return getRemovedSystemPowerWhitelistAppsInternal(
                    Binder.getCallingUid(), UserHandle.getCallingUserId());
        }

        @Override public String[] getSystemPowerWhitelistExceptIdle() {
            return getSystemPowerWhitelistExceptIdleInternal(
                    Binder.getCallingUid(), UserHandle.getCallingUserId());
        }

        @Override public String[] getSystemPowerWhitelist() {
            return getSystemPowerWhitelistInternal(
                    Binder.getCallingUid(), UserHandle.getCallingUserId());
        }

        @Override public String[] getUserPowerWhitelist() {
            return getUserPowerWhitelistInternal(
                    Binder.getCallingUid(), UserHandle.getCallingUserId());
        }

        @Override public String[] getFullPowerWhitelistExceptIdle() {
            return getFullPowerWhitelistExceptIdleInternal(
                    Binder.getCallingUid(), UserHandle.getCallingUserId());
        }

        @Override public String[] getFullPowerWhitelist() {
            return getFullPowerWhitelistInternal(
                    Binder.getCallingUid(), UserHandle.getCallingUserId());
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
            if (mPackageManagerInternal
                    .filterAppAccess(name, Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                return false;
            }
            return isPowerSaveWhitelistExceptIdleAppInternal(name);
        }

        @Override public boolean isPowerSaveWhitelistApp(String name) {
            if (mPackageManagerInternal
                    .filterAppAccess(name, Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                return false;
            }
            return isPowerSaveWhitelistAppInternal(name);
        }

        @Override
        public long whitelistAppTemporarily(String packageName, int userId,
                @ReasonCode int reasonCode, @Nullable String reason) throws RemoteException {
            // At least 10 seconds.
            long durationMs = Math.max(10_000L, mConstants.MAX_TEMP_APP_ALLOWLIST_DURATION_MS / 2);
            addPowerSaveTempAllowlistAppChecked(packageName, durationMs, userId, reasonCode,
                    reason);
            return durationMs;
        }

        @Override
        public void addPowerSaveTempWhitelistApp(String packageName, long duration, int userId,
                @ReasonCode int reasonCode, @Nullable String reason) throws RemoteException {
            addPowerSaveTempAllowlistAppChecked(packageName, duration, userId, reasonCode, reason);
        }

        @Override public long addPowerSaveTempWhitelistAppForMms(String packageName, int userId,
                @ReasonCode int reasonCode, @Nullable String reason) throws RemoteException {
            long durationMs = mConstants.MMS_TEMP_APP_ALLOWLIST_DURATION_MS;
            addPowerSaveTempAllowlistAppChecked(packageName, durationMs, userId, reasonCode,
                    reason);
            return durationMs;
        }

        @Override public long addPowerSaveTempWhitelistAppForSms(String packageName, int userId,
                @ReasonCode int reasonCode, @Nullable String reason) throws RemoteException {
            long durationMs = mConstants.SMS_TEMP_APP_ALLOWLIST_DURATION_MS;
            addPowerSaveTempAllowlistAppChecked(packageName, durationMs, userId, reasonCode,
                    reason);
            return durationMs;
        }

        @EnforcePermission(android.Manifest.permission.DEVICE_POWER)
        @Override public void exitIdle(String reason) {
            exitIdle_enforcePermission();
            final long ident = Binder.clearCallingIdentity();
            try {
                exitIdleInternal(reason);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            DeviceIdleController.this.dump(fd, pw, args);
        }

        @Override public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            (new Shell()).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    private class LocalService implements DeviceIdleInternal {
        @Override
        public void onConstraintStateChanged(IDeviceIdleConstraint constraint, boolean active) {
            synchronized (DeviceIdleController.this) {
                onConstraintStateChangedLocked(constraint, active);
            }
        }

        @Override
        public void registerDeviceIdleConstraint(IDeviceIdleConstraint constraint, String name,
                @IDeviceIdleConstraint.MinimumState int minState) {
            registerDeviceIdleConstraintInternal(constraint, name, minState);
        }

        @Override
        public void unregisterDeviceIdleConstraint(IDeviceIdleConstraint constraint) {
            unregisterDeviceIdleConstraintInternal(constraint);
        }

        @Override
        public void exitIdle(String reason) {
            exitIdleInternal(reason);
        }

        @Override
        public void addPowerSaveTempWhitelistApp(int callingUid, String packageName,
                long durationMs, int userId, boolean sync, @ReasonCode int reasonCode,
                @Nullable String reason) {
            addPowerSaveTempAllowlistAppInternal(callingUid, packageName, durationMs,
                    TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                    userId, sync, reasonCode, reason);
        }

        @Override
        public void addPowerSaveTempWhitelistApp(int callingUid, String packageName,
                long durationMs, @TempAllowListType int tempAllowListType, int userId, boolean sync,
                @ReasonCode int reasonCode, @Nullable String reason) {
            addPowerSaveTempAllowlistAppInternal(callingUid, packageName, durationMs,
                    tempAllowListType, userId, sync, reasonCode, reason);
        }

        @Override
        public void addPowerSaveTempWhitelistAppDirect(int uid, long durationMs,
                @TempAllowListType int tempAllowListType, boolean sync, @ReasonCode int reasonCode,
                @Nullable String reason, int callingUid) {
            addPowerSaveTempWhitelistAppDirectInternal(callingUid, uid, durationMs,
                    tempAllowListType, sync, reasonCode, reason);
        }

        // duration in milliseconds
        @Override
        public long getNotificationAllowlistDuration() {
            return mConstants.NOTIFICATION_ALLOWLIST_DURATION_MS;
        }

        @Override
        public void setJobsActive(boolean active) {
            DeviceIdleController.this.setJobsActive(active);
        }

        // Up-call from alarm manager.
        @Override
        public void setAlarmsActive(boolean active) {
            DeviceIdleController.this.setAlarmsActive(active);
        }

        /** Is the app on any of the power save whitelists, whether system or user? */
        @Override
        public boolean isAppOnWhitelist(int appid) {
            return DeviceIdleController.this.isAppOnWhitelistInternal(appid);
        }

        @Override
        public String[] getFullPowerWhitelistExceptIdle() {
            return DeviceIdleController.this.getFullPowerWhitelistInternalUnchecked();
        }

        /**
         * Returns the array of app ids whitelisted by user. Take care not to
         * modify this, as it is a reference to the original copy. But the reference
         * can change when the list changes, so it needs to be re-acquired when
         * {@link PowerManager#ACTION_POWER_SAVE_WHITELIST_CHANGED} is sent.
         */
        @Override
        public int[] getPowerSaveWhitelistUserAppIds() {
            return DeviceIdleController.this.getPowerSaveWhitelistUserAppIds();
        }

        @Override
        public int[] getPowerSaveTempWhitelistAppIds() {
            return DeviceIdleController.this.getAppIdTempWhitelistInternal();
        }

        @Override
        public void registerStationaryListener(StationaryListener listener) {
            DeviceIdleController.this.registerStationaryListener(listener);
        }

        @Override
        public void unregisterStationaryListener(StationaryListener listener) {
            DeviceIdleController.this.unregisterStationaryListener(listener);
        }

        @Override
        public @TempAllowListType int getTempAllowListType(@ReasonCode int reasonCode,
                @TempAllowListType int defaultType) {
            return DeviceIdleController.this.getTempAllowListType(reasonCode, defaultType);
        }
    }

    private class LocalPowerAllowlistService implements PowerAllowlistInternal {

        @Override
        public void registerTempAllowlistChangeListener(
                @NonNull TempAllowlistChangeListener listener) {
            DeviceIdleController.this.registerTempAllowlistChangeListener(listener);
        }

        @Override
        public void unregisterTempAllowlistChangeListener(
                @NonNull TempAllowlistChangeListener listener) {
            DeviceIdleController.this.unregisterTempAllowlistChangeListener(listener);
        }
    }

    private class EmergencyCallListener extends TelephonyCallback implements
            TelephonyCallback.OutgoingEmergencyCallListener,
            TelephonyCallback.CallStateListener {
        private volatile boolean mIsEmergencyCallActive;

        @Override
        public void onOutgoingEmergencyCall(EmergencyNumber placedEmergencyNumber,
                int subscriptionId) {
            mIsEmergencyCallActive = true;
            if (DEBUG) Slog.d(TAG, "onOutgoingEmergencyCall(): subId = " + subscriptionId);
            synchronized (DeviceIdleController.this) {
                mActiveReason = ACTIVE_REASON_EMERGENCY_CALL;
                becomeActiveLocked("emergency call", Process.myUid());
            }
        }

        @Override
        public void onCallStateChanged(int state) {
            if (DEBUG) Slog.d(TAG, "onCallStateChanged(): state is " + state);
            // An emergency call just finished
            if (state == TelephonyManager.CALL_STATE_IDLE && mIsEmergencyCallActive) {
                mIsEmergencyCallActive = false;
                synchronized (DeviceIdleController.this) {
                    becomeInactiveIfAppropriateLocked();
                }
            }
        }

        boolean isEmergencyCallActive() {
            return mIsEmergencyCallActive;
        }
    }

    static class Injector {
        private final Context mContext;
        private ConnectivityManager mConnectivityManager;
        private Constants mConstants;
        private LocationManager mLocationManager;

        Injector(Context ctx) {
            mContext = ctx.createAttributionContext(TAG);
        }

        AlarmManager getAlarmManager() {
            return mContext.getSystemService(AlarmManager.class);
        }

        AnyMotionDetector getAnyMotionDetector(Handler handler, SensorManager sm,
                AnyMotionDetector.DeviceIdleCallback callback, float angleThreshold) {
            return new AnyMotionDetector(getPowerManager(), handler, sm, callback, angleThreshold);
        }

        AppStateTrackerImpl getAppStateTracker(Context ctx, Looper looper) {
            return new AppStateTrackerImpl(ctx, looper);
        }

        ConnectivityManager getConnectivityManager() {
            if (mConnectivityManager == null) {
                mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
            }
            return mConnectivityManager;
        }

        Constants getConstants(DeviceIdleController controller, Handler handler,
                ContentResolver resolver) {
            if (mConstants == null) {
                mConstants = controller.new Constants(handler, resolver);
            }
            return mConstants;
        }

        /** Returns the current elapsed realtime in milliseconds. */
        long getElapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        LocationManager getLocationManager() {
            if (mLocationManager == null) {
                mLocationManager = mContext.getSystemService(LocationManager.class);
            }
            return mLocationManager;
        }

        MyHandler getHandler(DeviceIdleController controller) {
            return controller.new MyHandler(AppSchedulingModuleThread.getHandler().getLooper());
        }

        Sensor getMotionSensor() {
            final SensorManager sensorManager = getSensorManager();
            Sensor motionSensor = null;
            int sigMotionSensorId = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_autoPowerModeAnyMotionSensor);
            if (sigMotionSensorId > 0) {
                motionSensor = sensorManager.getDefaultSensor(sigMotionSensorId, true);
            }
            if (motionSensor == null && mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_autoPowerModePreferWristTilt)) {
                motionSensor = sensorManager.getDefaultSensor(
                        Sensor.TYPE_WRIST_TILT_GESTURE, true);
            }
            if (motionSensor == null) {
                // As a last ditch, fall back to SMD.
                motionSensor = sensorManager.getDefaultSensor(
                        Sensor.TYPE_SIGNIFICANT_MOTION, true);
            }
            return motionSensor;
        }

        PowerManager getPowerManager() {
            return mContext.getSystemService(PowerManager.class);
        }

        SensorManager getSensorManager() {
            return mContext.getSystemService(SensorManager.class);
        }

        TelephonyManager getTelephonyManager() {
            return mContext.getSystemService(TelephonyManager.class);
        }

        ConstraintController getConstraintController(Handler handler,
                DeviceIdleInternal localService) {
            if (mContext.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY)) {
                return new TvConstraintController(mContext, handler);
            }
            return null;
        }

        boolean isLocationPrefetchEnabled() {
            return !Flags.removeIdleLocation() && mContext.getResources().getBoolean(
                   com.android.internal.R.bool.config_autoPowerModePrefetchLocation);
        }

        boolean useMotionSensor() {
            return mContext.getResources().getBoolean(
                   com.android.internal.R.bool.config_autoPowerModeUseMotionSensor);
        }
    }

    private final Injector mInjector;

    private ActivityTaskManagerInternal.ScreenObserver mScreenObserver =
            new ActivityTaskManagerInternal.ScreenObserver() {
                @Override
                public void onAwakeStateChanged(boolean isAwake) { }

                @Override
                public void onKeyguardStateChanged(boolean isShowing) {
                    synchronized (DeviceIdleController.this) {
                        DeviceIdleController.this.keyguardShowingLocked(isShowing);
                    }
                }
            };

    @VisibleForTesting DeviceIdleController(Context context, Injector injector) {
        super(context);
        mInjector = injector;
        mConfigFile = new AtomicFile(new File(getSystemDir(), "deviceidle.xml"));
        mHandler = mInjector.getHandler(this);
        mAppStateTracker = mInjector.getAppStateTracker(context,
                AppSchedulingModuleThread.get().getLooper());
        LocalServices.addService(AppStateTracker.class, mAppStateTracker);
        mIsLocationPrefetchEnabled = mInjector.isLocationPrefetchEnabled();
        mUseMotionSensor = mInjector.useMotionSensor();
    }

    public DeviceIdleController(Context context) {
        this(context, new Injector(context));
    }

    boolean isAppOnWhitelistInternal(int appid) {
        synchronized (this) {
            return Arrays.binarySearch(mPowerSaveWhitelistAllAppIdArray, appid) >= 0;
        }
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
                    // On some devices (eg. HSUM), some apps may
                    // be not be pre-installed on user 0, but may be
                    // pre-installed on FULL users. Look for pre-installed system
                    // apps across all users to make sure they're properly
                    // allowlisted.
                    ApplicationInfo ai = pm.getApplicationInfo(pkg,
                            PackageManager.MATCH_ANY_USER | PackageManager.MATCH_SYSTEM_ONLY);
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
                    // On some devices (eg. HSUM), some apps may
                    // be not be pre-installed on user 0, but may be
                    // pre-installed on FULL users. Look for pre-installed system
                    // apps across all users to make sure they're properly
                    // allowlisted.
                    ApplicationInfo ai = pm.getApplicationInfo(pkg,
                            PackageManager.MATCH_ANY_USER | PackageManager.MATCH_SYSTEM_ONLY);
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

            mConstants = mInjector.getConstants(this, mHandler, getContext().getContentResolver());

            readConfigFileLocked();
            updateWhitelistAppIdsLocked();

            mNetworkConnected = true;
            mScreenOn = true;
            mScreenLocked = false;
            // Start out assuming we are charging.  If we aren't, we will at least get
            // a battery update the next time the level drops.
            mCharging = true;
            mActiveReason = ACTIVE_REASON_UNKNOWN;
            moveToStateLocked(STATE_ACTIVE, "boot");
            moveToLightStateLocked(LIGHT_STATE_ACTIVE, "boot");
            mInactiveTimeout = mConstants.INACTIVE_TIMEOUT;
        }

        mBinderService = new BinderService();
        publishBinderService(Context.DEVICE_IDLE_CONTROLLER, mBinderService);
        mLocalService = new LocalService();
        publishLocalService(DeviceIdleInternal.class, mLocalService);
        publishLocalService(PowerAllowlistInternal.class, new LocalPowerAllowlistService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            synchronized (this) {
                mAlarmManager = mInjector.getAlarmManager();
                mLocalAlarmManager = getLocalService(AlarmManagerInternal.class);
                mBatteryStats = BatteryStatsService.getService();
                mLocalActivityManager = getLocalService(ActivityManagerInternal.class);
                mLocalActivityTaskManager = getLocalService(ActivityTaskManagerInternal.class);
                mPackageManagerInternal = getLocalService(PackageManagerInternal.class);
                mLocalPowerManager = getLocalService(PowerManagerInternal.class);
                mPowerManager = mInjector.getPowerManager();
                mActiveIdleWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "deviceidle_maint");
                mActiveIdleWakeLock.setReferenceCounted(false);
                mGoingIdleWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "deviceidle_going_idle");
                mGoingIdleWakeLock.setReferenceCounted(true);
                mNetworkPolicyManager = INetworkPolicyManager.Stub.asInterface(
                        ServiceManager.getService(Context.NETWORK_POLICY_SERVICE));
                mNetworkPolicyManagerInternal = getLocalService(NetworkPolicyManagerInternal.class);
                mSensorManager = mInjector.getSensorManager();

                if (mUseMotionSensor) {
                    mMotionSensor = mInjector.getMotionSensor();
                }

                if (mIsLocationPrefetchEnabled) {
                    mLocationRequest = new LocationRequest.Builder(/*intervalMillis=*/ 0)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMaxUpdates(1)
                        .build();
                }

                mConstraintController = mInjector.getConstraintController(
                        mHandler, getLocalService(LocalService.class));
                if (mConstraintController != null) {
                    mConstraintController.start();
                }

                float angleThreshold = getContext().getResources().getInteger(
                        com.android.internal.R.integer.config_autoPowerModeThresholdAngle) / 100f;
                mAnyMotionDetector = mInjector.getAnyMotionDetector(mHandler, mSensorManager, this,
                        angleThreshold);

                mAppStateTracker.onSystemServicesReady();

                final Bundle mostRecentDeliveryOptions = BroadcastOptions.makeBasic()
                        .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                        .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                        .toBundle();

                mIdleIntent = new Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                mIdleIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                mLightIdleIntent = new Intent(PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED);
                mLightIdleIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                mIdleIntentOptions = mLightIdleIntentOptions = mostRecentDeliveryOptions;

                mPowerSaveWhitelistChangedIntent = new Intent(
                        PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
                mPowerSaveWhitelistChangedIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mPowerSaveTempWhitelistChangedIntent = new Intent(
                        PowerManager.ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED);
                mPowerSaveTempWhitelistChangedIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mPowerSaveWhitelistChangedOptions = mostRecentDeliveryOptions;
                mPowerSaveTempWhilelistChangedOptions = mostRecentDeliveryOptions;

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

                filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                getContext().registerReceiver(mInteractivityReceiver, filter);

                mLocalActivityManager.setDeviceIdleAllowlist(
                        mPowerSaveWhitelistAllAppIdArray, mPowerSaveWhitelistExceptIdleAppIdArray);
                mLocalPowerManager.setDeviceIdleWhitelist(mPowerSaveWhitelistAllAppIdArray);

                if (mConstants.USE_MODE_MANAGER) {
                    WearModeManagerInternal modeManagerInternal = LocalServices.getService(
                            WearModeManagerInternal.class);
                    if (modeManagerInternal != null) {
                        modeManagerInternal.addActiveStateChangeListener(
                                WearModeManagerInternal.QUICK_DOZE_REQUEST_IDENTIFIER,
                                AppSchedulingModuleThread.getExecutor(),
                                mModeManagerQuickDozeRequestConsumer);

                        modeManagerInternal.addActiveStateChangeListener(
                                WearModeManagerInternal.OFFBODY_STATE_ID,
                                AppSchedulingModuleThread.getExecutor(),
                                mModeManagerOffBodyStateConsumer
                        );
                    }
                }
                mLocalPowerManager.registerLowPowerModeObserver(ServiceType.QUICK_DOZE,
                        state -> {
                            synchronized (DeviceIdleController.this) {
                                mBatterySaverEnabled = state.batterySaverEnabled;
                                updateQuickDozeFlagLocked();
                            }
                        });
                mBatterySaverEnabled = mLocalPowerManager.getLowPowerState(
                        ServiceType.QUICK_DOZE).batterySaverEnabled;
                updateQuickDozeFlagLocked();

                mLocalActivityTaskManager.registerScreenObserver(mScreenObserver);

                mInjector.getTelephonyManager().registerTelephonyCallback(
                        AppSchedulingModuleThread.getExecutor(), mEmergencyCallListener);

                passWhiteListsToForceAppStandbyTrackerLocked();
                updateInteractivityLocked();
            }
            updateConnectivityState(null);
        }
    }

    @VisibleForTesting
    boolean hasMotionSensor() {
        return mUseMotionSensor && mMotionSensor != null;
    }

    private void registerDeviceIdleConstraintInternal(IDeviceIdleConstraint constraint,
            final String name, final int type) {
        final int minState;
        switch (type) {
            case IDeviceIdleConstraint.ACTIVE:
                minState = STATE_ACTIVE;
                break;
            case IDeviceIdleConstraint.SENSING_OR_ABOVE:
                minState = STATE_SENSING;
                break;
            default:
                Slog.wtf(TAG, "Registering device-idle constraint with invalid type: " + type);
                return;
        }
        synchronized (this) {
            if (mConstraints.containsKey(constraint)) {
                Slog.e(TAG, "Re-registering device-idle constraint: " + constraint + ".");
                return;
            }
            DeviceIdleConstraintTracker tracker = new DeviceIdleConstraintTracker(name, minState);
            mConstraints.put(constraint, tracker);
            updateActiveConstraintsLocked();
        }
    }

    private void unregisterDeviceIdleConstraintInternal(IDeviceIdleConstraint constraint) {
        synchronized (this) {
            // Artificially force the constraint to inactive to unblock anything waiting for it.
            onConstraintStateChangedLocked(constraint, /* active= */ false);

            // Let the constraint know that we are not listening to it any more.
            setConstraintMonitoringLocked(constraint, /* monitoring= */ false);
            mConstraints.remove(constraint);
        }
    }

    @GuardedBy("this")
    private void onConstraintStateChangedLocked(IDeviceIdleConstraint constraint, boolean active) {
        DeviceIdleConstraintTracker tracker = mConstraints.get(constraint);
        if (tracker == null) {
            Slog.e(TAG, "device-idle constraint " + constraint + " has not been registered.");
            return;
        }
        if (active != tracker.active && tracker.monitoring) {
            tracker.active = active;
            mNumBlockingConstraints += (tracker.active ? +1 : -1);
            if (mNumBlockingConstraints == 0) {
                if (mState == STATE_ACTIVE) {
                    becomeInactiveIfAppropriateLocked();
                } else if (mNextAlarmTime == 0 || mNextAlarmTime < SystemClock.elapsedRealtime()) {
                    stepIdleStateLocked("s:" + tracker.name);
                }
            }
        }
    }

    @GuardedBy("this")
    private void setConstraintMonitoringLocked(IDeviceIdleConstraint constraint, boolean monitor) {
        DeviceIdleConstraintTracker tracker = mConstraints.get(constraint);
        if (tracker.monitoring != monitor) {
            tracker.monitoring = monitor;
            updateActiveConstraintsLocked();
            // We send the callback on a separate thread instead of just relying on oneway as
            // the client could be in the system server with us and cause re-entry problems.
            mHandler.obtainMessage(MSG_SEND_CONSTRAINT_MONITORING,
                    /* monitoring= */ monitor ? 1 : 0,
                    /* <not used>= */ -1,
                    /* constraint= */ constraint).sendToTarget();
        }
    }

    @GuardedBy("this")
    private void updateActiveConstraintsLocked() {
        mNumBlockingConstraints = 0;
        for (int i = 0; i < mConstraints.size(); i++) {
            final IDeviceIdleConstraint constraint = mConstraints.keyAt(i);
            final DeviceIdleConstraintTracker tracker = mConstraints.valueAt(i);
            final boolean monitoring = (tracker.minState == mState);
            if (monitoring != tracker.monitoring) {
                setConstraintMonitoringLocked(constraint, monitoring);
                tracker.active = monitoring;
            }
            if (tracker.monitoring && tracker.active) {
                mNumBlockingConstraints++;
            }
        }
    }

    private int addPowerSaveWhitelistAppsInternal(List<String> pkgNames) {
        int numAdded = 0;
        int numErrors = 0;
        synchronized (this) {
            for (int i = pkgNames.size() - 1; i >= 0; --i) {
                final String name = pkgNames.get(i);
                if (name == null) {
                    numErrors++;
                    continue;
                }
                try {
                    ApplicationInfo ai = getContext().getPackageManager().getApplicationInfo(name,
                            PackageManager.MATCH_ANY_USER);
                    if (mPowerSaveWhitelistUserApps.put(name, UserHandle.getAppId(ai.uid))
                            == null) {
                        numAdded++;
                        Counter.logIncrement(USER_ALLOWLIST_ADDITION_METRIC_ID);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "Tried to add unknown package to power save whitelist: " + name);
                    numErrors++;
                }
            }
            if (numAdded > 0) {
                reportPowerSaveWhitelistChangedLocked();
                updateWhitelistAppIdsLocked();
                writeConfigFileLocked();
            }
        }
        return pkgNames.size() - numErrors;
    }

    public boolean removePowerSaveWhitelistAppInternal(String name) {
        synchronized (this) {
            if (mPowerSaveWhitelistUserApps.remove(name) != null) {
                reportPowerSaveWhitelistChangedLocked();
                updateWhitelistAppIdsLocked();
                writeConfigFileLocked();
                Counter.logIncrement(USER_ALLOWLIST_REMOVAL_METRIC_ID);
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

    void resetSystemPowerWhitelistInternal() {
        synchronized (this) {
            mPowerSaveWhitelistApps.putAll(mRemovedFromSystemWhitelistApps);
            mRemovedFromSystemWhitelistApps.clear();
            reportPowerSaveWhitelistChangedLocked();
            updateWhitelistAppIdsLocked();
            writeConfigFileLocked();
        }
    }

    public boolean restoreSystemPowerWhitelistAppInternal(String name) {
        synchronized (this) {
            if (!mRemovedFromSystemWhitelistApps.containsKey(name)) {
                return false;
            }
            mPowerSaveWhitelistApps.put(name, mRemovedFromSystemWhitelistApps.remove(name));
            reportPowerSaveWhitelistChangedLocked();
            updateWhitelistAppIdsLocked();
            writeConfigFileLocked();
            return true;
        }
    }

    public boolean removeSystemPowerWhitelistAppInternal(String name) {
        synchronized (this) {
            if (!mPowerSaveWhitelistApps.containsKey(name)) {
                return false;
            }
            mRemovedFromSystemWhitelistApps.put(name, mPowerSaveWhitelistApps.remove(name));
            reportPowerSaveWhitelistChangedLocked();
            updateWhitelistAppIdsLocked();
            writeConfigFileLocked();
            return true;
        }
    }

    public boolean addPowerSaveWhitelistExceptIdleInternal(String name) {
        synchronized (this) {
            try {
                final ApplicationInfo ai = getContext().getPackageManager().getApplicationInfo(name,
                        PackageManager.MATCH_ANY_USER);
                if (mPowerSaveWhitelistAppsExceptIdle.put(name, UserHandle.getAppId(ai.uid))
                        == null) {
                    mPowerSaveWhitelistUserAppsExceptIdle.add(name);
                    reportPowerSaveWhitelistChangedLocked();
                    mPowerSaveWhitelistExceptIdleAppIdArray = buildAppIdArray(
                            mPowerSaveWhitelistAppsExceptIdle, mPowerSaveWhitelistUserApps,
                            mPowerSaveWhitelistExceptIdleAppIds);

                    passWhiteListsToForceAppStandbyTrackerLocked();
                }
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
    }

    public void resetPowerSaveWhitelistExceptIdleInternal() {
        synchronized (this) {
            if (mPowerSaveWhitelistAppsExceptIdle.removeAll(
                    mPowerSaveWhitelistUserAppsExceptIdle)) {
                reportPowerSaveWhitelistChangedLocked();
                mPowerSaveWhitelistExceptIdleAppIdArray = buildAppIdArray(
                        mPowerSaveWhitelistAppsExceptIdle, mPowerSaveWhitelistUserApps,
                        mPowerSaveWhitelistExceptIdleAppIds);
                mPowerSaveWhitelistUserAppsExceptIdle.clear();

                passWhiteListsToForceAppStandbyTrackerLocked();
            }
        }
    }

    public boolean getPowerSaveWhitelistExceptIdleInternal(String name) {
        synchronized (this) {
            return mPowerSaveWhitelistAppsExceptIdle.containsKey(name);
        }
    }

    private String[] getSystemPowerWhitelistExceptIdleInternal(final int callingUid,
            final int callingUserId) {
        final String[] apps;
        synchronized (this) {
            int size = mPowerSaveWhitelistAppsExceptIdle.size();
            apps = new String[size];
            for (int i = 0; i < size; i++) {
                apps[i] = mPowerSaveWhitelistAppsExceptIdle.keyAt(i);
            }
        }
        return ArrayUtils.filter(apps, String[]::new,
                (pkg) -> !mPackageManagerInternal.filterAppAccess(pkg, callingUid, callingUserId));
    }

    private String[] getSystemPowerWhitelistInternal(final int callingUid,
            final int callingUserId) {
        final String[] apps;
        synchronized (this) {
            int size = mPowerSaveWhitelistApps.size();
            apps = new String[size];
            for (int i = 0; i < size; i++) {
                apps[i] = mPowerSaveWhitelistApps.keyAt(i);
            }
        }
        return ArrayUtils.filter(apps, String[]::new,
                (pkg) -> !mPackageManagerInternal.filterAppAccess(pkg, callingUid, callingUserId));
    }

    private String[] getRemovedSystemPowerWhitelistAppsInternal(final int callingUid,
            final int callingUserId) {
        final String[] apps;
        synchronized (this) {
            int size = mRemovedFromSystemWhitelistApps.size();
            apps = new String[size];
            for (int i = 0; i < size; i++) {
                apps[i] = mRemovedFromSystemWhitelistApps.keyAt(i);
            }
        }
        return ArrayUtils.filter(apps, String[]::new,
                (pkg) -> !mPackageManagerInternal.filterAppAccess(pkg, callingUid, callingUserId));
    }

    private String[] getUserPowerWhitelistInternal(final int callingUid, final int callingUserId) {
        final String[] apps;
        synchronized (this) {
            int size = mPowerSaveWhitelistUserApps.size();
            apps = new String[size];
            for (int i = 0; i < mPowerSaveWhitelistUserApps.size(); i++) {
                apps[i] = mPowerSaveWhitelistUserApps.keyAt(i);
            }
        }
        return ArrayUtils.filter(apps, String[]::new,
                (pkg) -> !mPackageManagerInternal.filterAppAccess(pkg, callingUid, callingUserId));
    }

    private String[] getFullPowerWhitelistExceptIdleInternal(final int callingUid,
            final int callingUserId) {
        final String[] apps;
        synchronized (this) {
            int size =
                    mPowerSaveWhitelistAppsExceptIdle.size() + mPowerSaveWhitelistUserApps.size();
            apps = new String[size];
            int cur = 0;
            for (int i = 0; i < mPowerSaveWhitelistAppsExceptIdle.size(); i++) {
                apps[cur] = mPowerSaveWhitelistAppsExceptIdle.keyAt(i);
                cur++;
            }
            for (int i = 0; i < mPowerSaveWhitelistUserApps.size(); i++) {
                apps[cur] = mPowerSaveWhitelistUserApps.keyAt(i);
                cur++;
            }
        }
        return ArrayUtils.filter(apps, String[]::new,
                (pkg) -> !mPackageManagerInternal.filterAppAccess(pkg, callingUid, callingUserId));
    }

    private String[] getFullPowerWhitelistInternal(final int callingUid, final int callingUserId) {
        return ArrayUtils.filter(getFullPowerWhitelistInternalUnchecked(), String[]::new,
                (pkg) -> !mPackageManagerInternal.filterAppAccess(pkg, callingUid, callingUserId));
    }

    private String[] getFullPowerWhitelistInternalUnchecked() {
        synchronized (this) {
            int size = mPowerSaveWhitelistApps.size() + mPowerSaveWhitelistUserApps.size();
            final String[] apps = new String[size];
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

    private @TempAllowListType int getTempAllowListType(@ReasonCode int reasonCode,
            @TempAllowListType int defaultType) {
        switch (reasonCode) {
            case PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA:
                return mLocalActivityManager.getPushMessagingOverQuotaBehavior();
            case PowerExemptionManager.REASON_DENIED:
                return TEMPORARY_ALLOW_LIST_TYPE_NONE;
            default:
                return defaultType;
        }
    }

    void addPowerSaveTempAllowlistAppChecked(String packageName, long duration,
            int userId, @ReasonCode int reasonCode, @Nullable String reason)
            throws RemoteException {
        getContext().enforceCallingOrSelfPermission(
                Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                "No permission to change device idle whitelist");
        final int callingUid = Binder.getCallingUid();
        userId = ActivityManager.getService().handleIncomingUser(
                Binder.getCallingPid(),
                callingUid,
                userId,
                /*allowAll=*/ false,
                /*requireFull=*/ false,
                "addPowerSaveTempWhitelistApp", null);
        final long token = Binder.clearCallingIdentity();
        try {
            @TempAllowListType int type = getTempAllowListType(reasonCode,
                    TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED);
            if (type != TEMPORARY_ALLOW_LIST_TYPE_NONE) {
                addPowerSaveTempAllowlistAppInternal(callingUid,
                        packageName, duration, type, userId, true, reasonCode, reason);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    void removePowerSaveTempAllowlistAppChecked(String packageName, int userId)
            throws RemoteException {
        getContext().enforceCallingOrSelfPermission(
                Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                "No permission to change device idle whitelist");
        final int callingUid = Binder.getCallingUid();
        userId = ActivityManager.getService().handleIncomingUser(
                Binder.getCallingPid(),
                callingUid,
                userId,
                /*allowAll=*/ false,
                /*requireFull=*/ false,
                "removePowerSaveTempWhitelistApp", null);
        final long token = Binder.clearCallingIdentity();
        try {
            removePowerSaveTempAllowlistAppInternal(packageName, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Adds an app to the temporary whitelist and resets the endTime for granting the
     * app an exemption to access network and acquire wakelocks.
     */
    void addPowerSaveTempAllowlistAppInternal(int callingUid, String packageName,
            long durationMs, @TempAllowListType int tempAllowListType, int userId, boolean sync,
            @ReasonCode int reasonCode, @Nullable String reason) {
        try {
            int uid = getContext().getPackageManager().getPackageUidAsUser(packageName, userId);
            addPowerSaveTempWhitelistAppDirectInternal(callingUid, uid, durationMs,
                    tempAllowListType, sync, reasonCode, reason);
        } catch (NameNotFoundException e) {
        }
    }

    /**
     * Adds an app to the temporary whitelist and resets the endTime for granting the
     * app an exemption to access network and acquire wakelocks.
     */
    void addPowerSaveTempWhitelistAppDirectInternal(int callingUid, int uid,
            long duration, @TempAllowListType int tempAllowListType, boolean sync,
            @ReasonCode int reasonCode, @Nullable String reason) {
        final long timeNow = SystemClock.elapsedRealtime();
        boolean informWhitelistChanged = false;
        int appId = UserHandle.getAppId(uid);
        synchronized (this) {
            duration = Math.min(duration, mConstants.MAX_TEMP_APP_ALLOWLIST_DURATION_MS);
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
                            reason, uid);
                } catch (RemoteException e) {
                }
                postTempActiveTimeoutMessage(uid, duration);
                updateTempWhitelistAppIdsLocked(uid, true, duration, tempAllowListType,
                        reasonCode, reason, callingUid);
                if (sync) {
                    informWhitelistChanged = true;
                } else {
                    // NPMS needs to update its state synchronously in certain situations so we
                    // can't have it use the TempAllowlistChangeListener path right now.
                    // TODO: see if there's a way to simplify/consolidate
                    mHandler.obtainMessage(MSG_REPORT_TEMP_APP_WHITELIST_ADDED_TO_NPMS, appId,
                            reasonCode, reason).sendToTarget();
                }
                reportTempWhitelistChangedLocked(uid, true);
            } else {
                // The uid is already temp allowlisted, only need to update AMS for temp allowlist
                // duration.
                if (mLocalActivityManager != null) {
                    mLocalActivityManager.updateDeviceIdleTempAllowlist(null, uid, true,
                            duration, tempAllowListType, reasonCode, reason, callingUid);
                }
            }
        }
        if (informWhitelistChanged) {
            mNetworkPolicyManagerInternal.onTempPowerSaveWhitelistChange(appId, true,
                    reasonCode, reason);
        }
    }

    /**
     * Removes an app from the temporary whitelist and notifies the observers.
     */
    private void removePowerSaveTempAllowlistAppInternal(String packageName, int userId) {
        try {
            final int uid = getContext().getPackageManager().getPackageUidAsUser(
                    packageName, userId);
            removePowerSaveTempWhitelistAppDirectInternal(uid);
        } catch (NameNotFoundException e) {
        }
    }

    private void removePowerSaveTempWhitelistAppDirectInternal(int uid) {
        final int appId = UserHandle.getAppId(uid);
        synchronized (this) {
            final int idx = mTempWhitelistAppIdEndTimes.indexOfKey(appId);
            if (idx < 0) {
                // Nothing else to do
                return;
            }
            final String reason = mTempWhitelistAppIdEndTimes.valueAt(idx).second;
            mTempWhitelistAppIdEndTimes.removeAt(idx);
            onAppRemovedFromTempWhitelistLocked(uid, reason);
        }
    }

    private void postTempActiveTimeoutMessage(int uid, long delay) {
        if (DEBUG) {
            Slog.d(TAG, "postTempActiveTimeoutMessage: uid=" + uid + ", delay=" + delay);
        }
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_TEMP_APP_WHITELIST_TIMEOUT, uid, 0), delay);
    }

    void checkTempAppWhitelistTimeout(int uid) {
        final long timeNow = SystemClock.elapsedRealtime();
        final int appId = UserHandle.getAppId(uid);
        if (DEBUG) {
            Slog.d(TAG, "checkTempAppWhitelistTimeout: uid=" + uid + ", timeNow=" + timeNow);
        }
        synchronized (this) {
            Pair<MutableLong, String> entry =
                    mTempWhitelistAppIdEndTimes.get(appId);
            if (entry == null) {
                // Nothing to do
                return;
            }
            if (timeNow >= entry.first.value) {
                mTempWhitelistAppIdEndTimes.delete(appId);
                onAppRemovedFromTempWhitelistLocked(uid, entry.second);
            } else {
                // Need more time
                if (DEBUG) {
                    Slog.d(TAG, "Time to remove uid " + uid + ": " + entry.first.value);
                }
                postTempActiveTimeoutMessage(uid, entry.first.value - timeNow);
            }
        }
    }

    @GuardedBy("this")
    private void onAppRemovedFromTempWhitelistLocked(int uid, @Nullable String reason) {
        if (DEBUG) {
            Slog.d(TAG, "Removing uid " + uid + " from temp whitelist");
        }
        final int appId = UserHandle.getAppId(uid);
        updateTempWhitelistAppIdsLocked(uid, false, 0, 0, REASON_UNKNOWN,
                reason, INVALID_UID);
        mHandler.obtainMessage(MSG_REPORT_TEMP_APP_WHITELIST_REMOVED_TO_NPMS, appId,
                /* unused= */ 0).sendToTarget();
        reportTempWhitelistChangedLocked(uid, false);
        try {
            mBatteryStats.noteEvent(BatteryStats.HistoryItem.EVENT_TEMP_WHITELIST_FINISH,
                    reason, appId);
        } catch (RemoteException e) {
        }
    }

    public void exitIdleInternal(String reason) {
        synchronized (this) {
            mActiveReason = ACTIVE_REASON_FROM_BINDER_CALL;
            becomeActiveLocked(reason, Binder.getCallingUid());
        }
    }

    @VisibleForTesting
    boolean isNetworkConnected() {
        synchronized (this) {
            return mNetworkConnected;
        }
    }

    void updateConnectivityState(Intent connIntent) {
        ConnectivityManager cm;
        synchronized (this) {
            cm = mInjector.getConnectivityManager();
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

    @VisibleForTesting
    boolean isScreenOn() {
        synchronized (this) {
            return mScreenOn;
        }
    }

    @GuardedBy("this")
    void updateInteractivityLocked() {
        // The interactivity state from the power manager tells us whether the display is
        // in a state that we need to keep things running so they will update at a normal
        // frequency.
        boolean screenOn = mPowerManager.isInteractive();
        if (DEBUG) Slog.d(TAG, "updateInteractivityLocked: screenOn=" + screenOn);
        if (!screenOn && mScreenOn) {
            mScreenOn = false;
            if (!mForceIdle) {
                becomeInactiveIfAppropriateLocked();
            }
        } else if (screenOn) {
            mScreenOn = true;
            if (!mForceIdle && (!mScreenLocked || !mConstants.WAIT_FOR_UNLOCK)) {
                mActiveReason = ACTIVE_REASON_SCREEN;
                becomeActiveLocked("screen", Process.myUid());
            }
        }
    }

    @VisibleForTesting
    boolean isCharging() {
        synchronized (this) {
            return mCharging;
        }
    }

    @GuardedBy("this")
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
                mActiveReason = ACTIVE_REASON_CHARGING;
                becomeActiveLocked("charging", Process.myUid());
            }
        }
    }

    @VisibleForTesting
    boolean isQuickDozeEnabled() {
        synchronized (this) {
            return mQuickDozeActivated;
        }
    }

    /** Calls to {@link #updateQuickDozeFlagLocked(boolean)} by considering appropriate signals. */
    @GuardedBy("this")
    private void updateQuickDozeFlagLocked() {
        if (mConstants.USE_MODE_MANAGER) {
            // Only disable the quick doze flag when mode manager request is false and
            // battery saver is off.
            updateQuickDozeFlagLocked(mModeManagerRequestedQuickDoze || mBatterySaverEnabled);
        } else {
            updateQuickDozeFlagLocked(mBatterySaverEnabled);
        }
    }

    /** Updates the quick doze flag and enters deep doze if appropriate. */
    @VisibleForTesting
    @GuardedBy("this")
    void updateQuickDozeFlagLocked(boolean enabled) {
        if (DEBUG) Slog.i(TAG, "updateQuickDozeFlagLocked: enabled=" + enabled);
        mQuickDozeActivated = enabled;
        mQuickDozeActivatedWhileIdling =
                mQuickDozeActivated && (mState == STATE_IDLE || mState == STATE_IDLE_MAINTENANCE);
        if (enabled) {
            // If Quick Doze is enabled, see if we should go straight into it.
            becomeInactiveIfAppropriateLocked();
        }
        // Going from Deep Doze to Light Idle (if quick doze becomes disabled) is tricky and
        // probably not worth the overhead, so leave in deep doze if that's the case until the
        // next natural time to come out of it.
    }


    /** Returns true if the screen is locked. */
    @VisibleForTesting
    boolean isKeyguardShowing() {
        synchronized (this) {
            return mScreenLocked;
        }
    }

    @VisibleForTesting
    @GuardedBy("this")
    void keyguardShowingLocked(boolean showing) {
        if (DEBUG) Slog.i(TAG, "keyguardShowing=" + showing);
        if (mScreenLocked != showing) {
            mScreenLocked = showing;
            if (mScreenOn && !mForceIdle && !mScreenLocked) {
                mActiveReason = ACTIVE_REASON_UNLOCKED;
                becomeActiveLocked("unlocked", Process.myUid());
            }
        }
    }

    @VisibleForTesting
    @GuardedBy("this")
    void scheduleReportActiveLocked(String activeReason, int activeUid) {
        Message msg = mHandler.obtainMessage(MSG_REPORT_ACTIVE, activeUid, 0, activeReason);
        mHandler.sendMessage(msg);
    }

    @GuardedBy("this")
    void becomeActiveLocked(String activeReason, int activeUid) {
        becomeActiveLocked(activeReason, activeUid, mConstants.INACTIVE_TIMEOUT, true);
    }

    @GuardedBy("this")
    private void becomeActiveLocked(String activeReason, int activeUid,
            long newInactiveTimeout, boolean changeLightIdle) {
        if (DEBUG) {
            Slog.i(TAG, "becomeActiveLocked, reason=" + activeReason
                    + ", changeLightIdle=" + changeLightIdle);
        }
        if (mState != STATE_ACTIVE || mLightState != LIGHT_STATE_ACTIVE) {
            moveToStateLocked(STATE_ACTIVE, activeReason);
            mInactiveTimeout = newInactiveTimeout;
            resetIdleManagementLocked();
            // Don't reset maintenance window start time if we're in a light idle maintenance window
            // because its used in the light idle budget calculation.
            if (mLightState != LIGHT_STATE_IDLE_MAINTENANCE) {
                mMaintenanceStartTime = 0;
            }

            if (changeLightIdle) {
                moveToLightStateLocked(LIGHT_STATE_ACTIVE, activeReason);
                resetLightIdleManagementLocked();
                // Only report active if light is also ACTIVE.
                scheduleReportActiveLocked(activeReason, activeUid);
                addEvent(EVENT_NORMAL, activeReason);
            }
        }
    }

    /** Must only be used in tests. */
    @VisibleForTesting
    void setDeepEnabledForTest(boolean enabled) {
        synchronized (this) {
            mDeepEnabled = enabled;
        }
    }

    /** Must only be used in tests. */
    @VisibleForTesting
    void setLightEnabledForTest(boolean enabled) {
        synchronized (this) {
            mLightEnabled = enabled;
        }
    }

    /** Sanity check to make sure DeviceIdleController and AlarmManager are on the same page. */
    @GuardedBy("this")
    private void verifyAlarmStateLocked() {
        if (mState == STATE_ACTIVE && mNextAlarmTime != 0) {
            Slog.wtf(TAG, "mState=ACTIVE but mNextAlarmTime=" + mNextAlarmTime);
        }
        if (mState != STATE_IDLE && mLocalAlarmManager.isIdling()) {
            Slog.wtf(TAG, "mState=" + stateToString(mState) + " but AlarmManager is idling");
        }
        if (mState == STATE_IDLE && !mLocalAlarmManager.isIdling()) {
            Slog.wtf(TAG, "mState=IDLE but AlarmManager is not idling");
        }
        if (mLightState == LIGHT_STATE_ACTIVE && mNextLightAlarmTime != 0) {
            Slog.wtf(TAG, "mLightState=ACTIVE but mNextLightAlarmTime is "
                    + TimeUtils.formatDuration(mNextLightAlarmTime - SystemClock.elapsedRealtime())
                    + " from now");
        }
    }

    @GuardedBy("this")
    void becomeInactiveIfAppropriateLocked() {
        verifyAlarmStateLocked();

        final boolean isScreenBlockingInactive =
                mScreenOn && (!mConstants.WAIT_FOR_UNLOCK || !mScreenLocked);
        final boolean isEmergencyCallActive = mEmergencyCallListener.isEmergencyCallActive();
        if (DEBUG) {
            Slog.d(TAG, "becomeInactiveIfAppropriateLocked():"
                    + " isScreenBlockingInactive=" + isScreenBlockingInactive
                    + " (mScreenOn=" + mScreenOn
                    + ", WAIT_FOR_UNLOCK=" + mConstants.WAIT_FOR_UNLOCK
                    + ", mScreenLocked=" + mScreenLocked + ")"
                    + " mCharging=" + mCharging
                    + " emergencyCall=" + isEmergencyCallActive
                    + " mForceIdle=" + mForceIdle
            );
        }
        if (!mForceIdle && (mCharging || isScreenBlockingInactive || isEmergencyCallActive)) {
            return;
        }
        // Become inactive and determine if we will ultimately go idle.
        if (mDeepEnabled) {
            if (mQuickDozeActivated) {
                if (mState == STATE_QUICK_DOZE_DELAY || mState == STATE_IDLE
                        || mState == STATE_IDLE_MAINTENANCE) {
                    // Already "idling". Don't want to restart the process.
                    // mLightState can't be LIGHT_STATE_ACTIVE if mState is any of these 3
                    // values, so returning here is safe.
                    return;
                }
                moveToStateLocked(STATE_QUICK_DOZE_DELAY, "no activity");
                // Make sure any motion sensing or locating is stopped.
                resetIdleManagementLocked();
                if (isUpcomingAlarmClock()) {
                    // If there's an upcoming AlarmClock alarm, we won't go into idle, so
                    // setting a wakeup alarm before the upcoming alarm is futile. Set the quick
                    // doze alarm to after the upcoming AlarmClock alarm.
                    scheduleAlarmLocked(
                            mAlarmManager.getNextWakeFromIdleTime() - mInjector.getElapsedRealtime()
                                    + mConstants.QUICK_DOZE_DELAY_TIMEOUT);
                } else {
                    // Wait a small amount of time in case something (eg: background service from
                    // recently closed app) needs to finish running.
                    scheduleAlarmLocked(mConstants.QUICK_DOZE_DELAY_TIMEOUT);
                }
            } else if (mState == STATE_ACTIVE) {
                moveToStateLocked(STATE_INACTIVE, "no activity");
                resetIdleManagementLocked();
                long delay = mInactiveTimeout;
                if (isUpcomingAlarmClock()) {
                    // If there's an upcoming AlarmClock alarm, we won't go into idle, so
                    // setting a wakeup alarm before the upcoming alarm is futile. Set the idle
                    // alarm to after the upcoming AlarmClock alarm.
                    scheduleAlarmLocked(
                            mAlarmManager.getNextWakeFromIdleTime() - mInjector.getElapsedRealtime()
                                    + delay);
                } else {
                    scheduleAlarmLocked(delay);
                }
            }
        }
        if (mLightState == LIGHT_STATE_ACTIVE && mLightEnabled) {
            moveToLightStateLocked(LIGHT_STATE_INACTIVE, "no activity");
            resetLightIdleManagementLocked();
            scheduleLightAlarmLocked(mConstants.LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT,
                    mConstants.FLEX_TIME_SHORT, true);
        }
    }

    @GuardedBy("this")
    private void resetIdleManagementLocked() {
        mNextIdlePendingDelay = 0;
        mNextIdleDelay = 0;
        mQuickDozeActivatedWhileIdling = false;
        cancelAlarmLocked();
        cancelSensingTimeoutAlarmLocked();
        cancelLocatingLocked();
        maybeStopMonitoringMotionLocked();
        mAnyMotionDetector.stop();
        updateActiveConstraintsLocked();
    }

    @GuardedBy("this")
    private void resetLightIdleManagementLocked() {
        mNextLightIdleDelay = mConstants.LIGHT_IDLE_TIMEOUT;
        mMaintenanceStartTime = 0;
        mNextLightIdleDelayFlex = mConstants.LIGHT_IDLE_TIMEOUT_INITIAL_FLEX;
        mCurLightIdleBudget = mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET;
        cancelLightAlarmLocked();
    }

    @GuardedBy("this")
    void exitForceIdleLocked() {
        if (mForceIdle) {
            mForceIdle = false;
            if (mScreenOn || mCharging) {
                mActiveReason = ACTIVE_REASON_FORCED;
                becomeActiveLocked("exit-force", Process.myUid());
            }
        }
    }

    /**
     * Must only be used in tests.
     *
     * This sets the state value directly and thus doesn't trigger any behavioral changes.
     */
    @VisibleForTesting
    void setLightStateForTest(int lightState) {
        synchronized (this) {
            mLightState = lightState;
        }
    }

    @VisibleForTesting
    int getLightState() {
        synchronized (this) {
            return mLightState;
        }
    }

    @GuardedBy("this")
    @VisibleForTesting
    @SuppressLint("WakelockTimeout")
    void stepLightIdleStateLocked(String reason) {
        if (mLightState == LIGHT_STATE_ACTIVE || mLightState == LIGHT_STATE_OVERRIDE) {
            // If we are already in deep device idle mode, then
            // there is nothing left to do for light mode.
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "stepLightIdleStateLocked: mLightState=" + lightStateToString(mLightState));
        }
        EventLogTags.writeDeviceIdleLightStep();

        if (mEmergencyCallListener.isEmergencyCallActive()) {
            // The emergency call should have raised the state to ACTIVE and kept it there,
            // so this method shouldn't be called. Don't proceed further.
            Slog.wtf(TAG, "stepLightIdleStateLocked called when emergency call is active");
            if (mLightState != LIGHT_STATE_ACTIVE) {
                mActiveReason = ACTIVE_REASON_EMERGENCY_CALL;
                becomeActiveLocked("emergency", Process.myUid());
            }
            return;
        }

        switch (mLightState) {
            case LIGHT_STATE_INACTIVE:
                mCurLightIdleBudget = mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET;
                // Reset the upcoming idle delays.
                mNextLightIdleDelay = mConstants.LIGHT_IDLE_TIMEOUT;
                mNextLightIdleDelayFlex = mConstants.LIGHT_IDLE_TIMEOUT_INITIAL_FLEX;
                mMaintenanceStartTime = 0;
                // Fall through to immediately idle.
            case LIGHT_STATE_IDLE_MAINTENANCE:
                if (mMaintenanceStartTime != 0) {
                    long duration = SystemClock.elapsedRealtime() - mMaintenanceStartTime;
                    if (duration < mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET) {
                        // We didn't use up all of our minimum budget; add this to the reserve.
                        mCurLightIdleBudget +=
                                (mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET - duration);
                    } else {
                        // We used more than our minimum budget; this comes out of the reserve.
                        mCurLightIdleBudget -=
                                (duration - mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET);
                    }
                }
                mMaintenanceStartTime = 0;
                scheduleLightAlarmLocked(mNextLightIdleDelay, mNextLightIdleDelayFlex, true);
                if (!mConstants.LIGHT_IDLE_INCREASE_LINEARLY) {
                    mNextLightIdleDelay = Math.min(mConstants.LIGHT_MAX_IDLE_TIMEOUT,
                            (long) (mNextLightIdleDelay * mConstants.LIGHT_IDLE_FACTOR));
                    mNextLightIdleDelayFlex = Math.min(mConstants.LIGHT_IDLE_TIMEOUT_MAX_FLEX,
                            (long) (mNextLightIdleDelayFlex * mConstants.LIGHT_IDLE_FACTOR));
                } else {
                    mNextLightIdleDelay = Math.min(mConstants.LIGHT_MAX_IDLE_TIMEOUT,
                            mNextLightIdleDelay + mConstants.LIGHT_IDLE_LINEAR_INCREASE_FACTOR_MS);
                    mNextLightIdleDelayFlex = Math.min(mConstants.LIGHT_IDLE_TIMEOUT_MAX_FLEX,
                            mNextLightIdleDelayFlex
                                    + mConstants.LIGHT_IDLE_FLEX_LINEAR_INCREASE_FACTOR_MS);
                }
                moveToLightStateLocked(LIGHT_STATE_IDLE, reason);
                addEvent(EVENT_LIGHT_IDLE, null);
                mGoingIdleWakeLock.acquire();
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_ON_LIGHT);
                break;
            case LIGHT_STATE_IDLE:
            case LIGHT_STATE_WAITING_FOR_NETWORK:
                if (mNetworkConnected || mLightState == LIGHT_STATE_WAITING_FOR_NETWORK) {
                    // We have been idling long enough, now it is time to do some work.
                    mActiveIdleOpCount = 1;
                    mActiveIdleWakeLock.acquire();
                    mMaintenanceStartTime = SystemClock.elapsedRealtime();
                    if (mCurLightIdleBudget < mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET) {
                        mCurLightIdleBudget = mConstants.LIGHT_IDLE_MAINTENANCE_MIN_BUDGET;
                    } else if (mCurLightIdleBudget > mConstants.LIGHT_IDLE_MAINTENANCE_MAX_BUDGET) {
                        mCurLightIdleBudget = mConstants.LIGHT_IDLE_MAINTENANCE_MAX_BUDGET;
                    }
                    scheduleLightAlarmLocked(mCurLightIdleBudget, mConstants.FLEX_TIME_SHORT, true);
                    moveToLightStateLocked(LIGHT_STATE_IDLE_MAINTENANCE, reason);
                    addEvent(EVENT_LIGHT_MAINTENANCE, null);
                    mHandler.sendEmptyMessage(MSG_REPORT_IDLE_OFF);
                } else {
                    // We'd like to do maintenance, but currently don't have network
                    // connectivity...  let's try to wait until the network comes back.
                    // We'll only wait for another full idle period, however, and then give up.
                    scheduleLightAlarmLocked(mNextLightIdleDelay,
                            mNextLightIdleDelayFlex / 2, true);
                    moveToLightStateLocked(LIGHT_STATE_WAITING_FOR_NETWORK, reason);
                }
                break;
        }
    }

    @VisibleForTesting
    int getState() {
        synchronized (this) {
            return mState;
        }
    }

    /**
     * Returns true if there's an upcoming AlarmClock alarm that is soon enough to prevent the
     * device from going into idle.
     */
    private boolean isUpcomingAlarmClock() {
        return mInjector.getElapsedRealtime() + mConstants.MIN_TIME_TO_ALARM
                >= mAlarmManager.getNextWakeFromIdleTime();
    }

    @VisibleForTesting
    @GuardedBy("this")
    void stepIdleStateLocked(String reason) {
        if (DEBUG) Slog.d(TAG, "stepIdleStateLocked: mState=" + mState);
        EventLogTags.writeDeviceIdleStep();

        if (mEmergencyCallListener.isEmergencyCallActive()) {
            // The emergency call should have raised the state to ACTIVE and kept it there,
            // so this method shouldn't be called. Don't proceed further.
            Slog.wtf(TAG, "stepIdleStateLocked called when emergency call is active");
            if (mState != STATE_ACTIVE) {
                mActiveReason = ACTIVE_REASON_EMERGENCY_CALL;
                becomeActiveLocked("emergency", Process.myUid());
            }
            return;
        }

        if (isUpcomingAlarmClock()) {
            // Whoops, there is an upcoming alarm.  We don't actually want to go idle.
            if (mState != STATE_ACTIVE) {
                mActiveReason = ACTIVE_REASON_ALARM;
                becomeActiveLocked("alarm", Process.myUid());
                becomeInactiveIfAppropriateLocked();
            }
            return;
        }

        if (mNumBlockingConstraints != 0 && !mForceIdle) {
            // We have some constraints from other parts of the system server preventing
            // us from moving to the next state.
            if (DEBUG) {
                Slog.i(TAG, "Cannot step idle state. Blocked by: " + mConstraints.values().stream()
                        .filter(x -> x.active)
                        .map(x -> x.name)
                        .collect(Collectors.joining(",")));
            }
            return;
        }

        switch (mState) {
            case STATE_INACTIVE:
                // We have now been inactive long enough, it is time to start looking
                // for motion and sleep some more while doing so.
                startMonitoringMotionLocked();
                long delay = mConstants.IDLE_AFTER_INACTIVE_TIMEOUT;
                scheduleAlarmLocked(delay);
                moveToStateLocked(STATE_IDLE_PENDING, reason);
                break;
            case STATE_IDLE_PENDING:
                cancelLocatingLocked();
                mLocated = false;
                mLastGenericLocation = null;
                mLastGpsLocation = null;
                moveToStateLocked(STATE_SENSING, reason);

                // Wait for open constraints and an accelerometer reading before moving on.
                if (mUseMotionSensor && mAnyMotionDetector.hasSensor()) {
                    scheduleSensingTimeoutAlarmLocked(mConstants.SENSING_TIMEOUT);
                    mNotMoving = false;
                    mAnyMotionDetector.checkForAnyMotion();
                    break;
                } else if (mNumBlockingConstraints != 0) {
                    cancelAlarmLocked();
                    break;
                }

                mNotMoving = true;
                // Otherwise, fall through and check this off the list of requirements.
            case STATE_SENSING:
                cancelSensingTimeoutAlarmLocked();
                moveToStateLocked(STATE_LOCATING, reason);
                if (mIsLocationPrefetchEnabled) {
                    scheduleAlarmLocked(mConstants.LOCATING_TIMEOUT);
                    LocationManager locationManager = mInjector.getLocationManager();
                    if (locationManager != null
                            && locationManager.getProvider(LocationManager.FUSED_PROVIDER)
                                    != null) {
                        mHasFusedLocation = true;
                        locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER,
                                mLocationRequest,
                                AppSchedulingModuleThread.getExecutor(),
                                mGenericLocationListener);
                        mLocating = true;
                    } else {
                        mHasFusedLocation = false;
                    }
                    if (locationManager != null
                            && locationManager.getProvider(LocationManager.GPS_PROVIDER) != null) {
                        mHasGps = true;
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                                1000, 5, mGpsLocationListener, mHandler.getLooper());
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
                } else {
                    mLocating = false;
                }

                // We're not doing any locating work, so move on to the next state.
            case STATE_LOCATING:
                cancelAlarmLocked();
                cancelLocatingLocked();
                mAnyMotionDetector.stop();

                // Intentional fallthrough -- time to go into IDLE state.
            case STATE_QUICK_DOZE_DELAY:
                // Reset the upcoming idle delays.
                mNextIdlePendingDelay = mConstants.IDLE_PENDING_TIMEOUT;
                mNextIdleDelay = mConstants.IDLE_TIMEOUT;

                // Everything is in place to go into IDLE state.
            case STATE_IDLE_MAINTENANCE:
                moveToStateLocked(STATE_IDLE, reason);
                scheduleAlarmLocked(mNextIdleDelay);
                if (DEBUG) Slog.d(TAG, "Moved to STATE_IDLE. Next alarm in " + mNextIdleDelay +
                        " ms.");
                mNextIdleDelay = (long)(mNextIdleDelay * mConstants.IDLE_FACTOR);
                if (DEBUG) Slog.d(TAG, "Setting mNextIdleDelay = " + mNextIdleDelay);
                mNextIdleDelay = Math.min(mNextIdleDelay, mConstants.MAX_IDLE_TIMEOUT);
                if (mNextIdleDelay < mConstants.IDLE_TIMEOUT) {
                    mNextIdleDelay = mConstants.IDLE_TIMEOUT;
                }
                if (mLightState != LIGHT_STATE_OVERRIDE) {
                    moveToLightStateLocked(LIGHT_STATE_OVERRIDE, "deep");
                    cancelLightAlarmLocked();
                }
                addEvent(EVENT_DEEP_IDLE, null);
                mGoingIdleWakeLock.acquire();
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_ON);
                break;
            case STATE_IDLE:
                // We have been idling long enough, now it is time to do some work.
                mActiveIdleOpCount = 1;
                mActiveIdleWakeLock.acquire();
                moveToStateLocked(STATE_IDLE_MAINTENANCE, reason);
                scheduleAlarmLocked(mNextIdlePendingDelay);
                if (DEBUG) Slog.d(TAG, "Moved from STATE_IDLE to STATE_IDLE_MAINTENANCE. " +
                        "Next alarm in " + mNextIdlePendingDelay + " ms.");
                mMaintenanceStartTime = SystemClock.elapsedRealtime();
                mNextIdlePendingDelay = Math.min(mConstants.MAX_IDLE_PENDING_TIMEOUT,
                        (long)(mNextIdlePendingDelay * mConstants.IDLE_PENDING_FACTOR));
                if (mNextIdlePendingDelay < mConstants.IDLE_PENDING_TIMEOUT) {
                    mNextIdlePendingDelay = mConstants.IDLE_PENDING_TIMEOUT;
                }
                addEvent(EVENT_DEEP_MAINTENANCE, null);
                mHandler.sendEmptyMessage(MSG_REPORT_IDLE_OFF);
                break;
        }
    }

    @GuardedBy("this")
    private void moveToLightStateLocked(int state, String reason) {
        if (DEBUG) {
            Slog.d(TAG, String.format("Moved from LIGHT_STATE_%s to LIGHT_STATE_%s.",
                    lightStateToString(mLightState), lightStateToString(state)));
        }
        mLightState = state;
        EventLogTags.writeDeviceIdleLight(mLightState, reason);
        // This is currently how to set the current state in a trace.
        Trace.traceCounter(Trace.TRACE_TAG_SYSTEM_SERVER, "DozeLightState", state);
    }

    @GuardedBy("this")
    private void moveToStateLocked(int state, String reason) {
        if (DEBUG) {
            Slog.d(TAG, String.format("Moved from STATE_%s to STATE_%s.",
                    stateToString(mState), stateToString(state)));
        }
        mState = state;
        EventLogTags.writeDeviceIdle(mState, reason);
        // This is currently how to set the current state in a trace.
        Trace.traceCounter(Trace.TRACE_TAG_SYSTEM_SERVER, "DozeDeepState", state);
        updateActiveConstraintsLocked();
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

    /** Must only be used in tests. */
    @VisibleForTesting
    void setActiveIdleOpsForTest(int count) {
        synchronized (this) {
            mActiveIdleOpCount = count;
        }
    }

    void setJobsActive(boolean active) {
        synchronized (this) {
            mJobsActive = active;
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

    @VisibleForTesting
    long getNextAlarmTime() {
        synchronized (this) {
            return mNextAlarmTime;
        }
    }

    @VisibleForTesting
    boolean isEmergencyCallActive() {
        return mEmergencyCallListener.isEmergencyCallActive();
    }

    @GuardedBy("this")
    boolean isOpsInactiveLocked() {
        return mActiveIdleOpCount <= 0 && !mJobsActive && !mAlarmsActive;
    }

    @GuardedBy("this")
    void exitMaintenanceEarlyIfNeededLocked() {
        if (mState == STATE_IDLE_MAINTENANCE || mLightState == LIGHT_STATE_IDLE_MAINTENANCE) {
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
                } else {
                    stepLightIdleStateLocked("s:early");
                }
            }
        }
    }

    @GuardedBy("this")
    void motionLocked() {
        if (DEBUG) Slog.d(TAG, "motionLocked()");
        mLastMotionEventElapsed = mInjector.getElapsedRealtime();
        handleMotionDetectedLocked(mConstants.MOTION_INACTIVE_TIMEOUT, "motion");
    }

    @GuardedBy("this")
    void handleMotionDetectedLocked(long timeout, String type) {
        if (mStationaryListeners.size() > 0) {
            postStationaryStatusUpdated();
            cancelMotionTimeoutAlarmLocked();
            // We need to re-register the motion listener, but we don't want the sensors to be
            // constantly active or to churn the CPU by registering too early, register after some
            // delay.
            scheduleMotionRegistrationAlarmLocked();
        }
        if (mQuickDozeActivated && !mQuickDozeActivatedWhileIdling) {
            // Don't exit idle due to motion if quick doze is enabled.
            // However, if the device started idling due to the normal progression (going through
            // all the states) and then had quick doze activated, come out briefly on motion so the
            // user can get slightly fresher content.
            return;
        }
        maybeStopMonitoringMotionLocked();
        // The device is not yet active, so we want to go back to the pending idle
        // state to wait again for no motion.  Note that we only monitor for motion
        // after moving out of the inactive state, so no need to worry about that.
        final boolean becomeInactive = mState != STATE_ACTIVE
                || mLightState == LIGHT_STATE_OVERRIDE;
        // We only want to change the IDLE state if it's OVERRIDE.
        becomeActiveLocked(type, Process.myUid(), timeout, mLightState == LIGHT_STATE_OVERRIDE);
        if (becomeInactive) {
            becomeInactiveIfAppropriateLocked();
        }
    }

    @GuardedBy("this")
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

    @GuardedBy("this")
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

    /**
     * Stops motion monitoring. Will not stop monitoring if there are registered stationary
     * listeners.
     */
    private void maybeStopMonitoringMotionLocked() {
        if (DEBUG) Slog.d(TAG, "maybeStopMonitoringMotionLocked()");
        if (mMotionSensor != null && mStationaryListeners.size() == 0) {
            if (mMotionListener.active) {
                mMotionListener.unregisterLocked();
                cancelMotionTimeoutAlarmLocked();
            }
            cancelMotionRegistrationAlarmLocked();
        }
    }

    @GuardedBy("this")
    void cancelAlarmLocked() {
        if (mNextAlarmTime != 0) {
            mNextAlarmTime = 0;
            mAlarmManager.cancel(mDeepAlarmListener);
        }
    }

    @GuardedBy("this")
    private void cancelLightAlarmLocked() {
        if (mNextLightAlarmTime != 0) {
            mNextLightAlarmTime = 0;
            mAlarmManager.cancel(mLightAlarmListener);
        }
    }

    @GuardedBy("this")
    void cancelLocatingLocked() {
        if (mLocating) {
            LocationManager locationManager = mInjector.getLocationManager();
            locationManager.removeUpdates(mGenericLocationListener);
            locationManager.removeUpdates(mGpsLocationListener);
            mLocating = false;
        }
    }

    private void cancelMotionTimeoutAlarmLocked() {
        mAlarmManager.cancel(mMotionTimeoutAlarmListener);
    }

    private void cancelMotionRegistrationAlarmLocked() {
        mAlarmManager.cancel(mMotionRegistrationAlarmListener);
    }

    @GuardedBy("this")
    void cancelSensingTimeoutAlarmLocked() {
        if (mNextSensingTimeoutAlarmTime != 0) {
            mNextSensingTimeoutAlarmTime = 0;
            mAlarmManager.cancel(mSensingTimeoutAlarmListener);
        }
    }

    @GuardedBy("this")
    @VisibleForTesting
    void scheduleAlarmLocked(long delay) {
        if (DEBUG) Slog.d(TAG, "scheduleAlarmLocked(" + delay + ", " + stateToString(mState) + ")");

        if (mUseMotionSensor && mMotionSensor == null
                && mState != STATE_QUICK_DOZE_DELAY
                && mState != STATE_IDLE
                && mState != STATE_IDLE_MAINTENANCE) {
            // If there is no motion sensor on this device, but we need one, then we won't schedule
            // alarms, because we can't determine if the device is not moving.  This effectively
            // turns off normal execution of device idling, although it is still possible to
            // manually poke it by pretending like the alarm is going off.
            // STATE_QUICK_DOZE_DELAY skips the motion sensing so if the state is past the motion
            // sensing stage (ie, is QUICK_DOZE_DELAY, IDLE, or IDLE_MAINTENANCE), then idling
            // can continue until the user interacts with the device.
            return;
        }
        mNextAlarmTime = SystemClock.elapsedRealtime() + delay;
        if (mState == STATE_IDLE) {
            mAlarmManager.setIdleUntil(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mNextAlarmTime, "DeviceIdleController.deep", mDeepAlarmListener, mHandler);
        } else if (mState == STATE_LOCATING) {
            // Use setExact so we don't keep the GPS active for too long.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mNextAlarmTime, "DeviceIdleController.deep", mDeepAlarmListener, mHandler);
        } else {
            if (mConstants.USE_WINDOW_ALARMS) {
                mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        mNextAlarmTime, mConstants.FLEX_TIME_SHORT,
                        "DeviceIdleController.deep", mDeepAlarmListener, mHandler);
            } else {
                mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        mNextAlarmTime, "DeviceIdleController.deep", mDeepAlarmListener, mHandler);
            }
        }
    }

    @GuardedBy("this")
    void scheduleLightAlarmLocked(long delay, long flex, boolean wakeup) {
        if (DEBUG) {
            Slog.d(TAG, "scheduleLightAlarmLocked(" + delay
                    + (mConstants.USE_WINDOW_ALARMS ? "/" + flex : "")
                    + ", wakeup=" + wakeup + ")");
        }
        mNextLightAlarmTime = mInjector.getElapsedRealtime() + delay;
        if (mConstants.USE_WINDOW_ALARMS) {
            mAlarmManager.setWindow(
                    wakeup ? AlarmManager.ELAPSED_REALTIME_WAKEUP : AlarmManager.ELAPSED_REALTIME,
                    mNextLightAlarmTime, flex,
                    "DeviceIdleController.light", mLightAlarmListener, mHandler);
        } else {
            mAlarmManager.set(
                    wakeup ? AlarmManager.ELAPSED_REALTIME_WAKEUP : AlarmManager.ELAPSED_REALTIME,
                    mNextLightAlarmTime,
                    "DeviceIdleController.light", mLightAlarmListener, mHandler);
        }
    }

    @VisibleForTesting
    long getNextLightAlarmTimeForTesting() {
        synchronized (this) {
            return mNextLightAlarmTime;
        }
    }

    private void scheduleMotionRegistrationAlarmLocked() {
        if (DEBUG) Slog.d(TAG, "scheduleMotionRegistrationAlarmLocked");
        long nextMotionRegistrationAlarmTime =
                mInjector.getElapsedRealtime() + mConstants.MOTION_INACTIVE_TIMEOUT / 2;
        if (mConstants.USE_WINDOW_ALARMS) {
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    nextMotionRegistrationAlarmTime, mConstants.MOTION_INACTIVE_TIMEOUT_FLEX,
                    "DeviceIdleController.motion_registration", mMotionRegistrationAlarmListener,
                    mHandler);
        } else {
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextMotionRegistrationAlarmTime,
                    "DeviceIdleController.motion_registration", mMotionRegistrationAlarmListener,
                    mHandler);
        }
    }

    private void scheduleMotionTimeoutAlarmLocked() {
        if (DEBUG) Slog.d(TAG, "scheduleMotionAlarmLocked");
        long nextMotionTimeoutAlarmTime =
                mInjector.getElapsedRealtime() + mConstants.MOTION_INACTIVE_TIMEOUT;
        if (mConstants.USE_WINDOW_ALARMS) {
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    nextMotionTimeoutAlarmTime,
                    mConstants.MOTION_INACTIVE_TIMEOUT_FLEX,
                    "DeviceIdleController.motion", mMotionTimeoutAlarmListener, mHandler);
        } else {
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextMotionTimeoutAlarmTime,
                    "DeviceIdleController.motion", mMotionTimeoutAlarmListener, mHandler);
        }
    }

    @GuardedBy("this")
    void scheduleSensingTimeoutAlarmLocked(long delay) {
        if (DEBUG) Slog.d(TAG, "scheduleSensingAlarmLocked(" + delay + ")");
        mNextSensingTimeoutAlarmTime = SystemClock.elapsedRealtime() + delay;
        if (mConstants.USE_WINDOW_ALARMS) {
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mNextSensingTimeoutAlarmTime,
                    mConstants.FLEX_TIME_SHORT,
                    "DeviceIdleController.sensing", mSensingTimeoutAlarmListener, mHandler);
        } else {
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, mNextSensingTimeoutAlarmTime,
                    "DeviceIdleController.sensing", mSensingTimeoutAlarmListener, mHandler);
        }
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
        if (mLocalActivityManager != null) {
            mLocalActivityManager.setDeviceIdleAllowlist(
                    mPowerSaveWhitelistAllAppIdArray, mPowerSaveWhitelistExceptIdleAppIdArray);
        }
        if (mLocalPowerManager != null) {
            if (DEBUG) {
                Slog.d(TAG, "Setting wakelock whitelist to "
                        + Arrays.toString(mPowerSaveWhitelistAllAppIdArray));
            }
            mLocalPowerManager.setDeviceIdleWhitelist(mPowerSaveWhitelistAllAppIdArray);
        }
        passWhiteListsToForceAppStandbyTrackerLocked();
    }

    /**
     * update temp allowlist.
     * @param uid uid to add or remove from temp allowlist.
     * @param adding true to add to temp allowlist, false to remove from temp allowlist.
     * @param durationMs duration in milliseconds to add to temp allowlist, only valid when
     *                   param adding is true.
     * @param type temp allowlist type defined at {@link TempAllowListType}
     * @prama reasonCode one of {@Link ReasonCode}
     * @param reason A human-readable reason for logging purposes.
     * @param callingUid the callingUid that setup this temp-allowlist, only valid when param adding
     *                   is true.
     */
    @GuardedBy("this")
    private void updateTempWhitelistAppIdsLocked(int uid, boolean adding, long durationMs,
            @TempAllowListType int type, @ReasonCode int reasonCode, @Nullable String reason,
            int callingUid) {
        final int size = mTempWhitelistAppIdEndTimes.size();
        if (mTempWhitelistAppIdArray.length != size) {
            mTempWhitelistAppIdArray = new int[size];
        }
        for (int i = 0; i < size; i++) {
            mTempWhitelistAppIdArray[i] = mTempWhitelistAppIdEndTimes.keyAt(i);
        }
        if (mLocalActivityManager != null) {
            if (DEBUG) {
                Slog.d(TAG, "Setting activity manager temp whitelist to "
                        + Arrays.toString(mTempWhitelistAppIdArray));
            }
            mLocalActivityManager.updateDeviceIdleTempAllowlist(mTempWhitelistAppIdArray, uid,
                    adding, durationMs, type, reasonCode, reason, callingUid);
        }
        if (mLocalPowerManager != null) {
            if (DEBUG) {
                Slog.d(TAG, "Setting wakelock temp whitelist to "
                        + Arrays.toString(mTempWhitelistAppIdArray));
            }
            mLocalPowerManager.setDeviceIdleTempWhitelist(mTempWhitelistAppIdArray);
        }
        passWhiteListsToForceAppStandbyTrackerLocked();
    }

    private void reportPowerSaveWhitelistChangedLocked() {
        getContext().sendBroadcastAsUser(mPowerSaveWhitelistChangedIntent, UserHandle.SYSTEM,
                null /* receiverPermission */,
                mPowerSaveWhitelistChangedOptions);
    }

    private void reportTempWhitelistChangedLocked(final int uid, final boolean added) {
        mHandler.obtainMessage(MSG_REPORT_TEMP_APP_WHITELIST_CHANGED, uid, added ? 1 : 0)
                .sendToTarget();
        getContext().sendBroadcastAsUser(mPowerSaveTempWhitelistChangedIntent, UserHandle.SYSTEM,
                null /* receiverPermission */,
                mPowerSaveTempWhilelistChangedOptions);
    }

    private void passWhiteListsToForceAppStandbyTrackerLocked() {
        mAppStateTracker.setPowerSaveExemptionListAppIds(
                mPowerSaveWhitelistExceptIdleAppIdArray,
                mPowerSaveWhitelistUserAppIdArray,
                mTempWhitelistAppIdArray);
    }

    @GuardedBy("this")
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

    @GuardedBy("this")
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
                switch (tagName) {
                    case "wl":
                        String name = parser.getAttributeValue(null, "n");
                        if (name != null) {
                            try {
                                ApplicationInfo ai = pm.getApplicationInfo(name,
                                        PackageManager.MATCH_ANY_USER);
                                mPowerSaveWhitelistUserApps.put(ai.packageName,
                                        UserHandle.getAppId(ai.uid));
                            } catch (PackageManager.NameNotFoundException e) {
                            }
                        }
                        break;
                    case "un-wl":
                        final String packageName = parser.getAttributeValue(null, "n");
                        if (mPowerSaveWhitelistApps.containsKey(packageName)) {
                            mRemovedFromSystemWhitelistApps.put(packageName,
                                    mPowerSaveWhitelistApps.remove(packageName));
                        }
                        break;
                    default:
                        Slog.w(TAG, "Unknown element under <config>: "
                                + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                        break;
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
        for (int i = 0; i < mRemovedFromSystemWhitelistApps.size(); i++) {
            out.startTag(null, "un-wl");
            out.attribute(null, "n", mRemovedFromSystemWhitelistApps.keyAt(i));
            out.endTag(null, "un-wl");
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
        pw.println(
                "    Resume normal functioning after force-idle or force-inactive or "
                        + "force-modemanager-quickdoze.");
        pw.println("  get [light|deep|force|screen|charging|network|offbody|forceoffbody]");
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
        pw.println("  sys-whitelist [package ...|reset]");
        pw.println("    Prefix the package with '-' to remove it from the system whitelist or '+'"
                + " to put it back in the system whitelist.");
        pw.println("    Note that only packages that were"
                + " earlier removed from the system whitelist can be added back.");
        pw.println("    reset will reset the whitelist to the original state");
        pw.println("    Prints the system whitelist if no arguments are specified");
        pw.println("  except-idle-whitelist [package ...|reset]");
        pw.println("    Prefix the package with '+' to add it to whitelist or "
                + "'=' to check if it is already whitelisted");
        pw.println("    [reset] will reset the whitelist to it's original state");
        pw.println("    Note that unlike <whitelist> cmd, "
                + "changes made using this won't be persisted across boots");
        pw.println("  tempwhitelist");
        pw.println("    Print packages that are temporarily whitelisted.");
        pw.println("  tempwhitelist [-u USER] [-d DURATION] [-r] [package]");
        pw.println("    Temporarily place package in whitelist for DURATION milliseconds.");
        pw.println("    If no DURATION is specified, 10 seconds is used");
        pw.println("    If [-r] option is used, then the package is removed from temp whitelist "
                + "and any [-d] is ignored");
        pw.println("  motion");
        pw.println("    Simulate a motion event to bring the device out of deep doze");
        pw.println("  force-modemanager-quickdoze [true|false]");
        pw.println("    Simulate mode manager request to enable (true) or disable (false) "
                + "quick doze. Mode manager changes will be ignored until unforce is called.");
        pw.println("  force-modemanager-offbody [true|false]");
        pw.println("    Force mode manager offbody state, this can be used to simulate "
                + "device being off-body (true) or on-body (false). Mode manager changes "
                + "will be ignored until unforce is called.");
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
                final long token = Binder.clearCallingIdentity();
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
        } else if ("force-active".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mForceIdle = true;
                    becomeActiveLocked("force-active", Process.myUid());
                    pw.print("Light state: ");
                    pw.print(lightStateToString(mLightState));
                    pw.print(", deep state: ");
                    pw.println(stateToString(mState));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if ("force-idle".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                final long token = Binder.clearCallingIdentity();
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
                            stepLightIdleStateLocked("s:shell");
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
                final long token = Binder.clearCallingIdentity();
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
                final long token = Binder.clearCallingIdentity();
                try {
                    exitForceIdleLocked();
                    pw.print("Light state: ");
                    pw.print(lightStateToString(mLightState));
                    pw.print(", deep state: ");
                    pw.println(stateToString(mState));
                    mForceModeManagerQuickDozeRequest = false;
                    pw.println("mForceModeManagerQuickDozeRequest: "
                            + mForceModeManagerQuickDozeRequest);
                    mForceModeManagerOffBodyState = false;
                    pw.println("mForceModeManagerOffBodyState: "
                            + mForceModeManagerOffBodyState);
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
                    final long token = Binder.clearCallingIdentity();
                    try {
                        switch (arg) {
                            case "light": pw.println(lightStateToString(mLightState)); break;
                            case "deep": pw.println(stateToString(mState)); break;
                            case "force": pw.println(mForceIdle); break;
                            case "quick": pw.println(mQuickDozeActivated); break;
                            case "screen": pw.println(mScreenOn); break;
                            case "charging": pw.println(mCharging); break;
                            case "network": pw.println(mNetworkConnected); break;
                            case "modemanagerquick":
                                pw.println(mModeManagerRequestedQuickDoze);
                                break;
                            case "forcemodemanagerquick":
                                pw.println(mForceModeManagerQuickDozeRequest);
                                break;
                            case "offbody": pw.println(mIsOffBody); break;
                            case "forceoffbody": pw.println(mForceModeManagerOffBodyState); break;
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
                final long token = Binder.clearCallingIdentity();
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
                        mActiveReason = ACTIVE_REASON_FORCED;
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
                final long token = Binder.clearCallingIdentity();
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
            String arg = shell.getNextArg();
            if (arg != null) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                final long token = Binder.clearCallingIdentity();
                try {
                    do {
                        if (arg.length() < 1 || (arg.charAt(0) != '-'
                                && arg.charAt(0) != '+' && arg.charAt(0) != '=')) {
                            pw.println("Package must be prefixed with +, -, or =: " + arg);
                            return -1;
                        }
                        char op = arg.charAt(0);
                        String pkg = arg.substring(1);
                        if (op == '+') {
                            if (addPowerSaveWhitelistAppsInternal(Collections.singletonList(pkg))
                                    == 1) {
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
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } else {
                if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                    return -1;
                }
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
        } else if ("tempwhitelist".equals(cmd)) {
            long duration = 10000;
            boolean removePkg = false;
            String opt;
            while ((opt=shell.getNextOption()) != null) {
                if ("-u".equals(opt)) {
                    opt = shell.getNextArg();
                    if (opt == null) {
                        pw.println("-u requires a user number");
                        return -1;
                    }
                    shell.userId = Integer.parseInt(opt);
                } else if ("-d".equals(opt)) {
                    opt = shell.getNextArg();
                    if (opt == null) {
                        pw.println("-d requires a duration");
                        return -1;
                    }
                    duration = Long.parseLong(opt);
                } else if ("-r".equals(opt)) {
                    removePkg = true;
                }
            }
            String arg = shell.getNextArg();
            if (arg != null) {
                try {
                    if (removePkg) {
                        removePowerSaveTempAllowlistAppChecked(arg, shell.userId);
                    } else {
                        addPowerSaveTempAllowlistAppChecked(arg, duration, shell.userId,
                                REASON_SHELL, "shell");
                    }
                } catch (Exception e) {
                    pw.println("Failed: " + e);
                    return -1;
                }
            } else if (removePkg) {
                pw.println("[-r] requires a package name");
                return -1;
            } else {
                if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                    return -1;
                }
                synchronized (this) {
                    dumpTempWhitelistScheduleLocked(pw, false);
                }
            }
        } else if ("except-idle-whitelist".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            final long token = Binder.clearCallingIdentity();
            try {
                String arg = shell.getNextArg();
                if (arg == null) {
                    pw.println("No arguments given");
                    return -1;
                } else if ("reset".equals(arg)) {
                    resetPowerSaveWhitelistExceptIdleInternal();
                } else {
                    do {
                        if (arg.length() < 1 || (arg.charAt(0) != '-'
                                && arg.charAt(0) != '+' && arg.charAt(0) != '=')) {
                            pw.println("Package must be prefixed with +, -, or =: " + arg);
                            return -1;
                        }
                        char op = arg.charAt(0);
                        String pkg = arg.substring(1);
                        if (op == '+') {
                            if (addPowerSaveWhitelistExceptIdleInternal(pkg)) {
                                pw.println("Added: " + pkg);
                            } else {
                                pw.println("Unknown package: " + pkg);
                            }
                        } else if (op == '=') {
                            pw.println(getPowerSaveWhitelistExceptIdleInternal(pkg));
                        } else {
                            pw.println("Unknown argument: " + arg);
                            return -1;
                        }
                    } while ((arg = shell.getNextArg()) != null);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } else if ("sys-whitelist".equals(cmd)) {
            String arg = shell.getNextArg();
            if (arg != null) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                final long token = Binder.clearCallingIdentity();
                try {
                    if ("reset".equals(arg)) {
                        resetSystemPowerWhitelistInternal();
                    } else {
                        do {
                            if (arg.length() < 1
                                    || (arg.charAt(0) != '-' && arg.charAt(0) != '+')) {
                                pw.println("Package must be prefixed with + or - " + arg);
                                return -1;
                            }
                            final char op = arg.charAt(0);
                            final String pkg = arg.substring(1);
                            switch (op) {
                                case '+':
                                    if (restoreSystemPowerWhitelistAppInternal(pkg)) {
                                        pw.println("Restored " + pkg);
                                    }
                                    break;
                                case '-':
                                    if (removeSystemPowerWhitelistAppInternal(pkg)) {
                                        pw.println("Removed " + pkg);
                                    }
                                    break;
                            }
                        } while ((arg = shell.getNextArg()) != null);
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } else {
                if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                    return -1;
                }
                synchronized (this) {
                    for (int j = 0; j < mPowerSaveWhitelistApps.size(); j++) {
                        pw.print(mPowerSaveWhitelistApps.keyAt(j));
                        pw.print(",");
                        pw.println(mPowerSaveWhitelistApps.valueAt(j));
                    }
                }
            }
        } else if ("motion".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            synchronized (this) {
                final long token = Binder.clearCallingIdentity();
                try {
                    motionLocked();
                    pw.print("Light state: ");
                    pw.print(lightStateToString(mLightState));
                    pw.print(", deep state: ");
                    pw.println(stateToString(mState));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else if ("force-modemanager-quickdoze".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            String arg = shell.getNextArg();

            if ("true".equalsIgnoreCase(arg) || "false".equalsIgnoreCase(arg)) {
                boolean enabled = Boolean.parseBoolean(arg);

                synchronized (DeviceIdleController.this) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mForceModeManagerQuickDozeRequest = true;
                        pw.println("mForceModeManagerQuickDozeRequest: "
                                + mForceModeManagerQuickDozeRequest);
                        mModeManagerRequestedQuickDoze = enabled;
                        pw.println("mModeManagerRequestedQuickDoze: "
                                + mModeManagerRequestedQuickDoze);
                        mModeManagerQuickDozeRequestConsumer.onModeManagerRequestChangedLocked();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } else {
                pw.println("Provide true or false argument after force-modemanager-quickdoze");
                return -1;
            }
        } else if ("force-modemanager-offbody".equals(cmd)) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                    null);
            String arg = shell.getNextArg();

            if ("true".equalsIgnoreCase(arg) || "false".equalsIgnoreCase(arg)) {
                boolean isOffBody = Boolean.parseBoolean(arg);

                synchronized (DeviceIdleController.this) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mForceModeManagerOffBodyState = true;
                        pw.println("mForceModeManagerOffBodyState: "
                                + mForceModeManagerOffBodyState);
                        mIsOffBody = isOffBody;
                        pw.println("mIsOffBody: " + mIsOffBody);
                        mModeManagerOffBodyStateConsumer.onModeManagerOffBodyChangedLocked();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            } else {
                pw.println("Provide true or false argument after force-modemanager-offbody");
                return -1;
            }
        } else {
            return shell.handleDefaultCommands(cmd);
        }
        return 0;
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

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
                    shell.exec(mBinderService, null, fd, null, newArgs, null,
                            new ResultReceiver(null));
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
                    TimeUtils.formatDuration(mEventTimes[i], now, pw);
                    if (mEventReasons[i] != null) {
                        pw.print(" (");
                        pw.print(mEventReasons[i]);
                        pw.print(")");
                    }
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
            size = mRemovedFromSystemWhitelistApps.size();
            if (size > 0) {
                pw.println("  Removed from whitelist system apps:");
                for (int i = 0; i < size; i++) {
                    pw.print("    ");
                    pw.println(mRemovedFromSystemWhitelistApps.keyAt(i));
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
            dumpTempWhitelistScheduleLocked(pw, true);

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
            pw.print("  mUseMotionSensor="); pw.print(mUseMotionSensor);
            if (mUseMotionSensor) {
                pw.print(" mMotionSensor="); pw.println(mMotionSensor);
            } else {
                pw.println();
            }
            pw.print("  mScreenOn="); pw.println(mScreenOn);
            pw.print("  mScreenLocked="); pw.println(mScreenLocked);
            pw.print("  mNetworkConnected="); pw.println(mNetworkConnected);
            pw.print("  mCharging="); pw.println(mCharging);
            pw.print("  activeEmergencyCall=");
            pw.println(mEmergencyCallListener.isEmergencyCallActive());
            if (mConstraints.size() != 0) {
                pw.println("  mConstraints={");
                for (int i = 0; i < mConstraints.size(); i++) {
                    final DeviceIdleConstraintTracker tracker = mConstraints.valueAt(i);
                    pw.print("    \""); pw.print(tracker.name); pw.print("\"=");
                    if (tracker.minState == mState) {
                        pw.println(tracker.active);
                    } else {
                        pw.print("ignored <mMinState="); pw.print(stateToString(tracker.minState));
                        pw.println(">");
                    }
                }
                pw.println("  }");
            }
            if (mUseMotionSensor || mStationaryListeners.size() > 0) {
                pw.print("  mMotionActive="); pw.println(mMotionListener.active);
                pw.print("  mNotMoving="); pw.println(mNotMoving);
                pw.print("  mMotionListener.activatedTimeElapsed=");
                pw.println(mMotionListener.activatedTimeElapsed);
                pw.print("  mLastMotionEventElapsed="); pw.println(mLastMotionEventElapsed);
                pw.print("  "); pw.print(mStationaryListeners.size());
                pw.println(" stationary listeners registered");
            }
            if (mIsLocationPrefetchEnabled) {
                pw.print("  mLocating="); pw.print(mLocating);
                pw.print(" mHasGps="); pw.print(mHasGps);
                pw.print(" mHasFused="); pw.print(mHasFusedLocation);
                pw.print(" mLocated="); pw.println(mLocated);
                if (mLastGenericLocation != null) {
                    pw.print("  mLastGenericLocation="); pw.println(mLastGenericLocation);
                }
                if (mLastGpsLocation != null) {
                    pw.print("  mLastGpsLocation="); pw.println(mLastGpsLocation);
                }
            } else {
                pw.println("  Location prefetching disabled");
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
                pw.print("  mNextLightIdleDelay=");
                TimeUtils.formatDuration(mNextLightIdleDelay, pw);
                if (mConstants.USE_WINDOW_ALARMS) {
                    pw.print(" (flex=");
                    TimeUtils.formatDuration(mNextLightIdleDelayFlex, pw);
                    pw.println(")");
                } else {
                    pw.println();
                }
            }
            if (mNextLightAlarmTime != 0) {
                pw.print("  mNextLightAlarmTime=");
                TimeUtils.formatDuration(mNextLightAlarmTime, SystemClock.elapsedRealtime(), pw);
                pw.println();
            }
            if (mCurLightIdleBudget != 0) {
                pw.print("  mCurLightIdleBudget=");
                TimeUtils.formatDuration(mCurLightIdleBudget, pw);
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
            if (mConstants.USE_MODE_MANAGER) {
                pw.print("  mModeManagerRequestedQuickDoze=");
                pw.println(mModeManagerRequestedQuickDoze);
                pw.print("  mIsOffBody=");
                pw.println(mIsOffBody);
            }
        }
    }

    @GuardedBy("this")
    void dumpTempWhitelistScheduleLocked(PrintWriter pw, boolean printTitle) {
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
