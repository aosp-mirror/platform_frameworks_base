/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.power;

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.policyToString;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_DISPLAY_GROUPS_TURNED_OFF;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_DISPLAY_GROUP_REMOVED;
import static android.os.PowerManager.WAKE_REASON_DISPLAY_GROUP_ADDED;
import static android.os.PowerManager.WAKE_REASON_DISPLAY_GROUP_TURNED_ON;
import static android.os.PowerManagerInternal.MODE_DEVICE_IDLE;
import static android.os.PowerManagerInternal.MODE_DISPLAY_INACTIVE;
import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;
import static android.os.PowerManagerInternal.isInteractive;
import static android.os.PowerManagerInternal.wakefulnessToString;

import static com.android.internal.util.LatencyTracker.ACTION_TURN_ON_SCREEN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.SynchronousUserSwitchObserver;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.SensorManager;
import android.hardware.SystemSensorManager;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.power.Boost;
import android.hardware.power.Mode;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.BatterySaverPolicyConfig;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IWakeLockCallback;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelDuration;
import android.os.PowerManager;
import android.os.PowerManager.GoToSleepReason;
import android.os.PowerManager.ServiceType;
import android.os.PowerManager.WakeReason;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.service.dreams.DreamManagerInternal;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.sysprop.InitProperties;
import android.util.KeyValueListParser;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.LatencyTracker;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.LockGuard;
import com.android.server.RescueParty;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.UserspaceRebootLogger;
import com.android.server.Watchdog;
import com.android.server.am.BatteryStatsService;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.batterysaver.BatterySaverController;
import com.android.server.power.batterysaver.BatterySaverPolicy;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.power.batterysaver.BatterySavingStats;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * The power manager service is responsible for coordinating power management
 * functions on the device.
 */
public final class PowerManagerService extends SystemService
        implements Watchdog.Monitor {
    private static final String TAG = "PowerManagerService";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SPEW = DEBUG && true;

    // Message: Sent when a user activity timeout occurs to update the power state.
    private static final int MSG_USER_ACTIVITY_TIMEOUT = 1;
    // Message: Sent when the device enters or exits a dreaming or dozing state.
    private static final int MSG_SANDMAN = 2;
    // Message: Sent when the screen brightness boost expires.
    private static final int MSG_SCREEN_BRIGHTNESS_BOOST_TIMEOUT = 3;
    // Message: Polling to look for long held wake locks.
    private static final int MSG_CHECK_FOR_LONG_WAKELOCKS = 4;
    // Message: Sent when an attentive timeout occurs to update the power state.
    private static final int MSG_ATTENTIVE_TIMEOUT = 5;

    // Dirty bit: mWakeLocks changed
    private static final int DIRTY_WAKE_LOCKS = 1 << 0;
    // Dirty bit: mWakefulness changed
    private static final int DIRTY_WAKEFULNESS = 1 << 1;
    // Dirty bit: user activity was poked or may have timed out
    private static final int DIRTY_USER_ACTIVITY = 1 << 2;
    // Dirty bit: actual display power state was updated asynchronously
    private static final int DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED = 1 << 3;
    // Dirty bit: mBootCompleted changed
    private static final int DIRTY_BOOT_COMPLETED = 1 << 4;
    // Dirty bit: settings changed
    private static final int DIRTY_SETTINGS = 1 << 5;
    // Dirty bit: mIsPowered changed
    private static final int DIRTY_IS_POWERED = 1 << 6;
    // Dirty bit: mStayOn changed
    private static final int DIRTY_STAY_ON = 1 << 7;
    // Dirty bit: battery state changed
    private static final int DIRTY_BATTERY_STATE = 1 << 8;
    // Dirty bit: proximity state changed
    private static final int DIRTY_PROXIMITY_POSITIVE = 1 << 9;
    // Dirty bit: dock state changed
    private static final int DIRTY_DOCK_STATE = 1 << 10;
    // Dirty bit: brightness boost changed
    private static final int DIRTY_SCREEN_BRIGHTNESS_BOOST = 1 << 11;
    // Dirty bit: sQuiescent changed
    private static final int DIRTY_QUIESCENT = 1 << 12;
    // Dirty bit: VR Mode enabled changed
    private static final int DIRTY_VR_MODE_CHANGED = 1 << 13;
    // Dirty bit: attentive timer may have timed out
    private static final int DIRTY_ATTENTIVE = 1 << 14;
    // Dirty bit: display group wakefulness has changed
    private static final int DIRTY_DISPLAY_GROUP_WAKEFULNESS = 1 << 16;

    // Summarizes the state of all active wakelocks.
    static final int WAKE_LOCK_CPU = 1 << 0;
    static final int WAKE_LOCK_SCREEN_BRIGHT = 1 << 1;
    static final int WAKE_LOCK_SCREEN_DIM = 1 << 2;
    static final int WAKE_LOCK_BUTTON_BRIGHT = 1 << 3;
    static final int WAKE_LOCK_PROXIMITY_SCREEN_OFF = 1 << 4;
    static final int WAKE_LOCK_STAY_AWAKE = 1 << 5; // only set if already awake
    static final int WAKE_LOCK_DOZE = 1 << 6;
    static final int WAKE_LOCK_DRAW = 1 << 7;

    // Summarizes the user activity state.
    static final int USER_ACTIVITY_SCREEN_BRIGHT = 1 << 0;
    static final int USER_ACTIVITY_SCREEN_DIM = 1 << 1;
    static final int USER_ACTIVITY_SCREEN_DREAM = 1 << 2;

    // Default timeout in milliseconds.  This is only used until the settings
    // provider populates the actual default value (R.integer.def_screen_off_timeout).
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT = 15 * 1000;
    private static final int DEFAULT_SLEEP_TIMEOUT = -1;

    // Screen brightness boost timeout.
    // Hardcoded for now until we decide what the right policy should be.
    // This should perhaps be a setting.
    private static final int SCREEN_BRIGHTNESS_BOOST_TIMEOUT = 5 * 1000;

    // Float.NaN cannot be stored in config.xml so -2 is used instead
    private static final float INVALID_BRIGHTNESS_IN_CONFIG = -2f;

    // How long a partial wake lock must be held until we consider it a long wake lock.
    static final long MIN_LONG_WAKE_CHECK_INTERVAL = 60*1000;

    // Default setting for double tap to wake.
    private static final int DEFAULT_DOUBLE_TAP_TO_WAKE = 0;

    // System property indicating that the screen should remain off until an explicit user action
    private static final String SYSTEM_PROPERTY_QUIESCENT = "ro.boot.quiescent";

    // System Property indicating that retail demo mode is currently enabled.
    private static final String SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED = "sys.retaildemo.enabled";

    // System property for last reboot reason
    private static final String SYSTEM_PROPERTY_REBOOT_REASON = "sys.boot.reason";

    // Possible reasons for shutting down or reboot for use in
    // SYSTEM_PROPERTY_REBOOT_REASON(sys.boot.reason) which is set by bootstat
    private static final String REASON_SHUTDOWN = "shutdown";
    private static final String REASON_REBOOT = "reboot";
    private static final String REASON_USERREQUESTED = "shutdown,userrequested";
    private static final String REASON_THERMAL_SHUTDOWN = "shutdown,thermal";
    private static final String REASON_LOW_BATTERY = "shutdown,battery";
    private static final String REASON_BATTERY_THERMAL_STATE = "shutdown,thermal,battery";

    static final String TRACE_SCREEN_ON = "Screen turning on";

    /** If turning screen on takes more than this long, we show a warning on logcat. */
    private static final int SCREEN_ON_LATENCY_WARNING_MS = 200;

    /** Constants for {@link #shutdownOrRebootInternal} */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HALT_MODE_SHUTDOWN, HALT_MODE_REBOOT, HALT_MODE_REBOOT_SAFE_MODE})
    public @interface HaltMode {}
    private static final int HALT_MODE_SHUTDOWN = 0;
    private static final int HALT_MODE_REBOOT = 1;
    private static final int HALT_MODE_REBOOT_SAFE_MODE = 2;

    /**
     * How stale we'll allow the enhanced discharge prediction values to get before considering them
     * invalid.
     */
    private static final long ENHANCED_DISCHARGE_PREDICTION_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * The minimum amount of time between sending consequent
     * {@link PowerManager#ACTION_ENHANCED_DISCHARGE_PREDICTION_CHANGED} broadcasts.
     */
    private static final long ENHANCED_DISCHARGE_PREDICTION_BROADCAST_MIN_DELAY_MS = 60 * 1000L;

    private final Context mContext;
    private final ServiceThread mHandlerThread;
    private final Handler mHandler;
    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private final BatterySaverController mBatterySaverController;
    private final BatterySaverPolicy mBatterySaverPolicy;
    private final BatterySaverStateMachine mBatterySaverStateMachine;
    private final BatterySavingStats mBatterySavingStats;
    private final LowPowerStandbyController mLowPowerStandbyController;
    private final AttentionDetector mAttentionDetector;
    private final FaceDownDetector mFaceDownDetector;
    private final ScreenUndimDetector mScreenUndimDetector;
    private final BinderService mBinderService;
    private final LocalService mLocalService;
    private final NativeWrapper mNativeWrapper;
    private final SystemPropertiesWrapper mSystemProperties;
    private final Clock mClock;
    private final Injector mInjector;

    private AppOpsManager mAppOpsManager;
    private LightsManager mLightsManager;
    private BatteryManagerInternal mBatteryManagerInternal;
    private DisplayManagerInternal mDisplayManagerInternal;
    private IBatteryStats mBatteryStats;
    private WindowManagerPolicy mPolicy;
    private Notifier mNotifier;
    private WirelessChargerDetector mWirelessChargerDetector;
    private SettingsObserver mSettingsObserver;
    private DreamManagerInternal mDreamManager;
    private LogicalLight mAttentionLight;

    private final InattentiveSleepWarningController mInattentiveSleepWarningOverlayController;
    private final AmbientDisplaySuppressionController mAmbientDisplaySuppressionController;

    private final Object mLock = LockGuard.installNewLock(LockGuard.INDEX_POWER);

    // A bitfield that indicates what parts of the power state have
    // changed and need to be recalculated.
    private int mDirty;

    // Indicates whether the device is awake or asleep or somewhere in between.
    // This is distinct from the screen power state, which is managed separately.
    // Do not access directly; always use {@link #setWakefulness} and {@link getWakefulness}.
    private int mWakefulnessRaw;
    private boolean mWakefulnessChanging;

    // True if MSG_SANDMAN has been scheduled.
    private boolean mSandmanScheduled;

    // Table of all suspend blockers.
    // There should only be a few of these.
    private final ArrayList<SuspendBlocker> mSuspendBlockers = new ArrayList<>();

    // Table of all wake locks acquired by applications.
    private final ArrayList<WakeLock> mWakeLocks = new ArrayList<>();

    // A bitfield that summarizes the state of all active wakelocks.
    private int mWakeLockSummary;

    // Have we scheduled a message to check for long wake locks?  This is when we will check.
    private long mNotifyLongScheduled;

    // Last time we checked for long wake locks.
    private long mNotifyLongDispatched;

    // The time we decided to do next long check.
    private long mNotifyLongNextCheck;

    // If true, instructs the display controller to wait for the proximity sensor to
    // go negative before turning the screen on.
    private boolean mRequestWaitForNegativeProximity;

    // Timestamp of the last time the device was awoken or put to sleep.
    private long mLastGlobalWakeTime;
    private long mLastGlobalSleepTime;

    // Last reason the device went to sleep.
    private @WakeReason int mLastGlobalWakeReason;
    private @GoToSleepReason int mLastGlobalSleepReason;

    // Timestamp of last time power boost interaction was sent.
    private long mLastInteractivePowerHintTime;

    // Timestamp of the last screen brightness boost.
    private long mLastScreenBrightnessBoostTime;
    private boolean mScreenBrightnessBoostInProgress;

    private final PowerGroupWakefulnessChangeListener mPowerGroupWakefulnessChangeListener;

    // The suspend blocker used to keep the CPU alive while the device is booting.
    private final SuspendBlocker mBootingSuspendBlocker;

    // True if the wake lock suspend blocker has been acquired.
    private boolean mHoldingBootingSuspendBlocker;

    // The suspend blocker used to keep the CPU alive when an application has acquired
    // a wake lock.
    private final SuspendBlocker mWakeLockSuspendBlocker;

    // True if the wake lock suspend blocker has been acquired.
    private boolean mHoldingWakeLockSuspendBlocker;

    // The suspend blocker used to keep the CPU alive when the display is on, the
    // display is getting ready or there is user activity (in which case the display
    // must be on).
    private final SuspendBlocker mDisplaySuspendBlocker;

    // True if the display suspend blocker has been acquired.
    private boolean mHoldingDisplaySuspendBlocker;

    // True if systemReady() has been called.
    private boolean mSystemReady;

    // True if boot completed occurred. We keep the screen on until this happens.
    // The screen will be off if we are in quiescent mode.
    private boolean mBootCompleted;

    // True if auto-suspend mode is enabled.
    // Refer to autosuspend.h.
    private boolean mHalAutoSuspendModeEnabled;

    // True if interactive mode is enabled.
    // Refer to power.h.
    private boolean mHalInteractiveModeEnabled;

    // True if the device is plugged into a power source.
    private boolean mIsPowered;

    // The current plug type, such as BatteryManager.BATTERY_PLUGGED_WIRELESS.
    private int mPlugType;

    // The current battery level percentage.
    private int mBatteryLevel;

    // True if updatePowerStateLocked() is already in progress.
    // TODO(b/215518989): Remove this once transactions are in place
    private boolean mUpdatePowerStateInProgress;

    /**
     * The lock that should be held when interacting with {@link #mEnhancedDischargeTimeElapsed},
     * {@link #mLastEnhancedDischargeTimeUpdatedElapsed}, and
     * {@link #mEnhancedDischargePredictionIsPersonalized}.
     */
    private final Object mEnhancedDischargeTimeLock = new Object();

    /**
     * The time (in the elapsed realtime timebase) at which the battery level will reach 0%. This
     * is provided as an enhanced estimate and only valid if
     * {@link #mLastEnhancedDischargeTimeUpdatedElapsed} is greater than 0.
     */
    @GuardedBy("mEnhancedDischargeTimeLock")
    private long mEnhancedDischargeTimeElapsed;

    /**
     * Timestamp (in the elapsed realtime timebase) of last update to enhanced battery estimate
     * data.
     */
    @GuardedBy("mEnhancedDischargeTimeLock")
    private long mLastEnhancedDischargeTimeUpdatedElapsed;

    /**
     * Whether or not the current enhanced discharge prediction is personalized to the user.
     */
    @GuardedBy("mEnhancedDischargeTimeLock")
    private boolean mEnhancedDischargePredictionIsPersonalized;

    // The battery level percentage at the time the dream started.
    // This is used to terminate a dream and go to sleep if the battery is
    // draining faster than it is charging and the user activity timeout has expired.
    private int mBatteryLevelWhenDreamStarted;

    // The current dock state.
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    // True to decouple auto-suspend mode from the display state.
    private boolean mDecoupleHalAutoSuspendModeFromDisplayConfig;

    // True to decouple interactive mode from the display state.
    private boolean mDecoupleHalInteractiveModeFromDisplayConfig;

    // True if the device should wake up when plugged or unplugged.
    private boolean mWakeUpWhenPluggedOrUnpluggedConfig;

    // True if the device should wake up when plugged or unplugged in theater mode.
    private boolean mWakeUpWhenPluggedOrUnpluggedInTheaterModeConfig;

    // True if the device should suspend when the screen is off due to proximity.
    private boolean mSuspendWhenScreenOffDueToProximityConfig;

    // Default value for attentive timeout.
    private int mAttentiveTimeoutConfig;

    // True if dreams are supported on this device.
    private boolean mDreamsSupportedConfig;

    // Default value for dreams enabled
    private boolean mDreamsEnabledByDefaultConfig;

    // Default value for dreams activate-on-sleep
    private boolean mDreamsActivatedOnSleepByDefaultConfig;

    // Default value for dreams activate-on-dock
    private boolean mDreamsActivatedOnDockByDefaultConfig;

    // True if dreams can run while not plugged in.
    private boolean mDreamsEnabledOnBatteryConfig;

    // Minimum battery level to allow dreaming when powered.
    // Use -1 to disable this safety feature.
    private int mDreamsBatteryLevelMinimumWhenPoweredConfig;

    // Minimum battery level to allow dreaming when not powered.
    // Use -1 to disable this safety feature.
    private int mDreamsBatteryLevelMinimumWhenNotPoweredConfig;

    // If the battery level drops by this percentage and the user activity timeout
    // has expired, then assume the device is receiving insufficient current to charge
    // effectively and terminate the dream.  Use -1 to disable this safety feature.
    private int mDreamsBatteryLevelDrainCutoffConfig;

    // True if dreams are enabled by the user.
    private boolean mDreamsEnabledSetting;

    // True if dreams should be activated on sleep.
    private boolean mDreamsActivateOnSleepSetting;

    // True if dreams should be activated on dock.
    private boolean mDreamsActivateOnDockSetting;

    // True if doze should not be started until after the screen off transition.
    private boolean mDozeAfterScreenOff;

    // The minimum screen off timeout, in milliseconds.
    private long mMinimumScreenOffTimeoutConfig;

    // The screen dim duration, in milliseconds.
    // This is subtracted from the end of the screen off timeout so the
    // minimum screen off timeout should be longer than this.
    private long mMaximumScreenDimDurationConfig;

    // The maximum screen dim time expressed as a ratio relative to the screen
    // off timeout.  If the screen off timeout is very short then we want the
    // dim timeout to also be quite short so that most of the time is spent on.
    // Otherwise the user won't get much screen on time before dimming occurs.
    private float mMaximumScreenDimRatioConfig;

    // Whether device supports double tap to wake.
    private boolean mSupportsDoubleTapWakeConfig;

    // The screen off timeout setting value in milliseconds.
    private long mScreenOffTimeoutSetting;

    // Default for attentive warning duration.
    private long mAttentiveWarningDurationConfig;

    // The sleep timeout setting value in milliseconds.
    private long mSleepTimeoutSetting;

    // How long to show a warning message to user before the device goes to sleep
    // after long user inactivity, even if wakelocks are held.
    private long mAttentiveTimeoutSetting;

    // The maximum allowable screen off timeout according to the device
    // administration policy.  Overrides other settings.
    private long mMaximumScreenOffTimeoutFromDeviceAdmin = Long.MAX_VALUE;

    // The stay on while plugged in setting.
    // A bitfield of battery conditions under which to make the screen stay on.
    private int mStayOnWhilePluggedInSetting;

    // True if the device should stay on.
    private boolean mStayOn;

    // True if the lights should stay off until an explicit user action.
    private static boolean sQuiescent;

    // True if the proximity sensor reads a positive result.
    private boolean mProximityPositive;

    // Indicates that we have already intercepted the power key to temporarily ignore the proximity
    // wake lock and turn the screen back on. This should get reset when prox reads 'far' again
    // (when {@link #mProximityPositive} is set to false).
    private boolean mInterceptedPowerKeyForProximity;

    // Screen brightness setting limits.
    public final float mScreenBrightnessMinimum;
    public final float mScreenBrightnessMaximum;
    public final float mScreenBrightnessDefault;
    public final float mScreenBrightnessDoze;
    public final float mScreenBrightnessDim;
    public final float mScreenBrightnessMinimumVr;
    public final float mScreenBrightnessMaximumVr;
    public final float mScreenBrightnessDefaultVr;

    // Value we store for tracking face down behavior.
    private boolean mIsFaceDown = false;
    private long mLastFlipTime = 0L;

    // The screen brightness mode.
    // One of the Settings.System.SCREEN_BRIGHTNESS_MODE_* constants.
    private int mScreenBrightnessModeSetting;

    // The screen brightness setting override from the window manager
    // to allow the current foreground activity to override the brightness.
    private float mScreenBrightnessOverrideFromWindowManager =
            PowerManager.BRIGHTNESS_INVALID_FLOAT;

    // The window manager has determined the user to be inactive via other means.
    // Set this to false to disable.
    private boolean mUserInactiveOverrideFromWindowManager;

    // The next possible user activity timeout after being explicitly told the user is inactive.
    // Set to -1 when not told the user is inactive since the last period spent dozing or asleep.
    private long mOverriddenTimeout = -1;

    // The user activity timeout override from the window manager
    // to allow the current foreground activity to override the user activity timeout.
    // Use -1 to disable.
    private long mUserActivityTimeoutOverrideFromWindowManager = -1;

    // The screen state to use while dozing.
    private int mDozeScreenStateOverrideFromDreamManager = Display.STATE_UNKNOWN;

    // The screen brightness to use while dozing.
    private int mDozeScreenBrightnessOverrideFromDreamManager = PowerManager.BRIGHTNESS_DEFAULT;

    private float mDozeScreenBrightnessOverrideFromDreamManagerFloat =
            PowerManager.BRIGHTNESS_INVALID_FLOAT;
    // Keep display state when dozing.
    private boolean mDrawWakeLockOverrideFromSidekick;

    // Time when we last logged a warning about calling userActivity() without permission.
    private long mLastWarningAboutUserActivityPermission = Long.MIN_VALUE;

    // True if the battery level is currently considered low.
    private boolean mBatteryLevelLow;

    // True if we are currently in device idle mode.
    private boolean mDeviceIdleMode;

    // True if we are currently in light device idle mode.
    private boolean mLightDeviceIdleMode;

    // Set of app ids that we will respect the wake locks for while in device idle mode.
    int[] mDeviceIdleWhitelist = new int[0];

    // Set of app ids that are temporarily allowed to acquire wakelocks due to high-pri message
    int[] mDeviceIdleTempWhitelist = new int[0];

    // Set of app ids that are allowed to acquire wakelocks while low power standby is active
    int[] mLowPowerStandbyAllowlist = new int[0];

    private boolean mLowPowerStandbyActive;

    private final SparseArray<UidState> mUidState = new SparseArray<>();

    // A mapping from DisplayGroup Id to PowerGroup. There is a 1-1 mapping between DisplayGroups
    // and PowerGroups. For simplicity the same ids are being used.
    @GuardedBy("mLock")
    private final SparseArray<PowerGroup> mPowerGroups = new SparseArray<>();

    // We are currently in the middle of a batch change of uids.
    private boolean mUidsChanging;

    // Some uids have actually changed while mUidsChanging was true.
    private boolean mUidsChanged;

    // True if theater mode is enabled
    private boolean mTheaterModeEnabled;

    // True if always on display is enabled
    private boolean mAlwaysOnEnabled;

    // True if double tap to wake is enabled
    private boolean mDoubleTapWakeEnabled;

    // True if we are currently in VR Mode.
    private boolean mIsVrModeEnabled;

    // True if we in the process of performing a forceSuspend
    private boolean mForceSuspendActive;

    // Transition to Doze is in progress.  We have transitioned to WAKEFULNESS_DOZING,
    // but the DreamService has not yet been told to start (it's an async process).
    private boolean mDozeStartInProgress;

    private final class PowerGroupWakefulnessChangeListener implements
            PowerGroup.PowerGroupListener {
        @GuardedBy("mLock")
        @Override
        public void onWakefulnessChangedLocked(int groupId, int wakefulness, long eventTime,
                int reason, int uid, int opUid, String opPackageName, String details) {
            if (wakefulness == WAKEFULNESS_AWAKE) {
                // Kick user activity to prevent newly awake group from timing out instantly.
                // The dream may end without user activity if the dream app crashes / is updated,
                // don't poke the user activity timer for these wakes.
                int flags = reason == PowerManager.WAKE_REASON_DREAM_FINISHED
                        ? PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS : 0;
                userActivityNoUpdateLocked(mPowerGroups.get(groupId), eventTime,
                        PowerManager.USER_ACTIVITY_EVENT_OTHER, flags, uid);
            }
            mDirty |= DIRTY_DISPLAY_GROUP_WAKEFULNESS;
            updateGlobalWakefulnessLocked(eventTime, reason, uid, opUid, opPackageName, details);
            mNotifier.onPowerGroupWakefulnessChanged(groupId, wakefulness, reason,
                    getGlobalWakefulnessLocked());
            updatePowerStateLocked();
        }
    }

    private final class DisplayGroupPowerChangeListener implements
            DisplayManagerInternal.DisplayGroupListener {

        static final int DISPLAY_GROUP_ADDED = 0;
        static final int DISPLAY_GROUP_REMOVED = 1;
        static final int DISPLAY_GROUP_CHANGED = 2;

        @Override
        public void onDisplayGroupAdded(int groupId) {
            synchronized (mLock) {
                if (mPowerGroups.contains(groupId)) {
                    Slog.e(TAG, "Tried to add already existing group:" + groupId);
                    return;
                }
                // For now, only the default group supports sandman (dream/AOD).
                final boolean supportsSandman = groupId == Display.DEFAULT_DISPLAY_GROUP;
                final PowerGroup powerGroup = new PowerGroup(
                        groupId,
                        mPowerGroupWakefulnessChangeListener,
                        mNotifier,
                        mDisplayManagerInternal,
                        WAKEFULNESS_AWAKE,
                        /* ready= */ false,
                        supportsSandman,
                        mClock.uptimeMillis());
                mPowerGroups.append(groupId, powerGroup);
                onPowerGroupEventLocked(DISPLAY_GROUP_ADDED, powerGroup);
            }
        }

        @Override
        public void onDisplayGroupRemoved(int groupId) {
            synchronized (mLock) {
                if (groupId == Display.DEFAULT_DISPLAY_GROUP) {
                    Slog.wtf(TAG, "Tried to remove default display group: " + groupId);
                    return;
                }
                if (!mPowerGroups.contains(groupId)) {
                    Slog.e(TAG, "Tried to remove non-existent group:" + groupId);
                    return;
                }
                onPowerGroupEventLocked(DISPLAY_GROUP_REMOVED, mPowerGroups.get(groupId));
            }
        }

        @Override
        public void onDisplayGroupChanged(int groupId) {
            synchronized (mLock) {
                if (!mPowerGroups.contains(groupId)) {
                    Slog.e(TAG, "Tried to change non-existent group: " + groupId);
                    return;
                }
                onPowerGroupEventLocked(DISPLAY_GROUP_CHANGED, mPowerGroups.get(groupId));
            }
        }
    }

    private final class ForegroundProfileObserver extends SynchronousUserSwitchObserver {
        @Override
        public void onUserSwitching(@UserIdInt int newUserId) throws RemoteException {
            synchronized (mLock) {
                mUserId = newUserId;
            }
        }

        @Override
        public void onForegroundProfileSwitch(@UserIdInt int newProfileId) throws RemoteException {
            final long now = mClock.uptimeMillis();
            synchronized (mLock) {
                mForegroundProfile = newProfileId;
                maybeUpdateForegroundProfileLastActivityLocked(now);
            }
        }
    }

    // User id corresponding to activity the user is currently interacting with.
    private @UserIdInt int mForegroundProfile;
    // User id of main profile for the current user (doesn't include managed profiles)
    private @UserIdInt int mUserId;

    // Per-profile state to track when a profile should be locked.
    private final SparseArray<ProfilePowerState> mProfilePowerState = new SparseArray<>();

    private static final class ProfilePowerState {
        // Profile user id.
        final @UserIdInt int mUserId;
        // Maximum time to lock set by admin.
        long mScreenOffTimeout;
        // Like top-level mWakeLockSummary, but only for wake locks that affect current profile.
        int mWakeLockSummary;
        // Last user activity that happened in an app running in the profile.
        long mLastUserActivityTime;
        // Whether profile has been locked last time it timed out.
        boolean mLockingNotified;

        public ProfilePowerState(@UserIdInt int userId, long screenOffTimeout, long now) {
            mUserId = userId;
            mScreenOffTimeout = screenOffTimeout;
            // Not accurate but at least won't cause immediate locking of the profile.
            mLastUserActivityTime = now;
        }
    }

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the PowerManagerService.mLock lock.
     */
    private final class Constants extends ContentObserver {
        // Key names stored in the settings value.
        private static final String KEY_NO_CACHED_WAKE_LOCKS = "no_cached_wake_locks";

        private static final boolean DEFAULT_NO_CACHED_WAKE_LOCKS = true;

        // Prevent processes that are cached from holding wake locks?
        public boolean NO_CACHED_WAKE_LOCKS = DEFAULT_NO_CACHED_WAKE_LOCKS;

        private ContentResolver mResolver;
        private final KeyValueListParser mParser = new KeyValueListParser(',');

        public Constants(Handler handler) {
            super(handler);
        }

        public void start(ContentResolver resolver) {
            mResolver = resolver;
            mResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.POWER_MANAGER_CONSTANTS), false, this);
            updateConstants();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            synchronized (mLock) {
                try {
                    mParser.setString(Settings.Global.getString(mResolver,
                            Settings.Global.POWER_MANAGER_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad alarm manager settings", e);
                }

                NO_CACHED_WAKE_LOCKS = mParser.getBoolean(KEY_NO_CACHED_WAKE_LOCKS,
                        DEFAULT_NO_CACHED_WAKE_LOCKS);
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings " + Settings.Global.POWER_MANAGER_CONSTANTS + ":");

            pw.print("    "); pw.print(KEY_NO_CACHED_WAKE_LOCKS); pw.print("=");
            pw.println(NO_CACHED_WAKE_LOCKS);
        }

        void dumpProto(ProtoOutputStream proto) {
            final long constantsToken = proto.start(PowerManagerServiceDumpProto.CONSTANTS);
            proto.write(PowerManagerServiceDumpProto.ConstantsProto.IS_NO_CACHED_WAKE_LOCKS,
                    NO_CACHED_WAKE_LOCKS);
            proto.end(constantsToken);
        }
    }

    /**
     * Wrapper around the static-native methods of PowerManagerService.
     *
     * This class exists to allow us to mock static native methods in our tests. If mocking static
     * methods becomes easier than this in the future, we can delete this class.
     */
    @VisibleForTesting
    public static class NativeWrapper {
        /** Wrapper for PowerManager.nativeInit */
        public void nativeInit(PowerManagerService service) {
            service.nativeInit();
        }

        /** Wrapper for PowerManager.nativeAcquireSuspectBlocker */
        public void nativeAcquireSuspendBlocker(String name) {
            PowerManagerService.nativeAcquireSuspendBlocker(name);
        }

        /** Wrapper for PowerManager.nativeReleaseSuspendBlocker */
        public void nativeReleaseSuspendBlocker(String name) {
            PowerManagerService.nativeReleaseSuspendBlocker(name);
        }

        /** Wrapper for PowerManager.nativeSetAutoSuspend */
        public void nativeSetAutoSuspend(boolean enable) {
            PowerManagerService.nativeSetAutoSuspend(enable);
        }

        /** Wrapper for PowerManager.nativeSetPowerBoost */
        public void nativeSetPowerBoost(int boost, int durationMs) {
            PowerManagerService.nativeSetPowerBoost(boost, durationMs);
        }

        /** Wrapper for PowerManager.nativeSetPowerMode */
        public boolean nativeSetPowerMode(int mode, boolean enabled) {
            return PowerManagerService.nativeSetPowerMode(mode, enabled);
        }

        /** Wrapper for PowerManager.nativeForceSuspend */
        public boolean nativeForceSuspend() {
            return PowerManagerService.nativeForceSuspend();
        }
    }

    /** Functional interface for providing time. */
    @VisibleForTesting
    interface Clock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();
    }

    @VisibleForTesting
    static class Injector {
        Notifier createNotifier(Looper looper, Context context, IBatteryStats batteryStats,
                SuspendBlocker suspendBlocker, WindowManagerPolicy policy,
                FaceDownDetector faceDownDetector, ScreenUndimDetector screenUndimDetector,
                Executor backgroundExecutor) {
            return new Notifier(
                    looper, context, batteryStats, suspendBlocker, policy, faceDownDetector,
                    screenUndimDetector, backgroundExecutor);
        }

        SuspendBlocker createSuspendBlocker(PowerManagerService service, String name) {
            SuspendBlocker suspendBlocker = service.new SuspendBlockerImpl(name);
            service.mSuspendBlockers.add(suspendBlocker);
            return suspendBlocker;
        }

        BatterySaverPolicy createBatterySaverPolicy(
                Object lock, Context context, BatterySavingStats batterySavingStats) {
            return new BatterySaverPolicy(lock, context, batterySavingStats);
        }

        BatterySaverController createBatterySaverController(
                Object lock, Context context, BatterySaverPolicy batterySaverPolicy,
                BatterySavingStats batterySavingStats) {
            return new BatterySaverController(lock, context, BackgroundThread.get().getLooper(),
                    batterySaverPolicy, batterySavingStats);
        }

        BatterySaverStateMachine createBatterySaverStateMachine(Object lock, Context context,
                BatterySaverController batterySaverController) {
            return new BatterySaverStateMachine(lock, context, batterySaverController);
        }

        NativeWrapper createNativeWrapper() {
            return new NativeWrapper();
        }

        WirelessChargerDetector createWirelessChargerDetector(
                SensorManager sensorManager, SuspendBlocker suspendBlocker, Handler handler) {
            return new WirelessChargerDetector(sensorManager, suspendBlocker, handler);
        }

        AmbientDisplayConfiguration createAmbientDisplayConfiguration(Context context) {
            return new AmbientDisplayConfiguration(context);
        }

        AmbientDisplaySuppressionController createAmbientDisplaySuppressionController(
                Context context) {
            return new AmbientDisplaySuppressionController(context);
        }

        InattentiveSleepWarningController createInattentiveSleepWarningController() {
            return new InattentiveSleepWarningController();
        }

        public SystemPropertiesWrapper createSystemPropertiesWrapper() {
            return new SystemPropertiesWrapper() {
                @Override
                public String get(String key, String def) {
                    return SystemProperties.get(key, def);
                }

                @Override
                public void set(String key, String val) {
                    SystemProperties.set(key, val);
                }
            };
        }

        Clock createClock() {
            return SystemClock::uptimeMillis;
        }

        /**
         * Handler for asynchronous operations performed by the power manager.
         */
        Handler createHandler(Looper looper, Handler.Callback callback) {
            return new Handler(looper, callback, /* async= */ true);
        }

        void invalidateIsInteractiveCaches() {
            PowerManager.invalidateIsInteractiveCaches();
        }

        LowPowerStandbyController createLowPowerStandbyController(Context context, Looper looper) {
            return new LowPowerStandbyController(context, looper, SystemClock::elapsedRealtime);
        }

        AppOpsManager createAppOpsManager(Context context) {
            return context.getSystemService(AppOpsManager.class);
        }
    }

    final Constants mConstants;

    private native void nativeInit();
    private static native void nativeAcquireSuspendBlocker(String name);
    private static native void nativeReleaseSuspendBlocker(String name);
    private static native void nativeSetAutoSuspend(boolean enable);
    private static native void nativeSetPowerBoost(int boost, int durationMs);
    private static native boolean nativeSetPowerMode(int mode, boolean enabled);
    private static native boolean nativeForceSuspend();

    public PowerManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    PowerManagerService(Context context, Injector injector) {
        super(context);

        mContext = context;
        mBinderService = new BinderService();
        mLocalService = new LocalService();
        mNativeWrapper = injector.createNativeWrapper();
        mSystemProperties = injector.createSystemPropertiesWrapper();
        mClock = injector.createClock();
        mInjector = injector;

        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DISPLAY, /* allowIo= */ false);
        mHandlerThread.start();
        mHandler = injector.createHandler(mHandlerThread.getLooper(),
                new PowerManagerHandlerCallback());
        mConstants = new Constants(mHandler);
        mAmbientDisplayConfiguration = mInjector.createAmbientDisplayConfiguration(context);
        mAmbientDisplaySuppressionController =
                mInjector.createAmbientDisplaySuppressionController(context);
        mAttentionDetector = new AttentionDetector(this::onUserAttention, mLock);
        mFaceDownDetector = new FaceDownDetector(this::onFlip);
        mScreenUndimDetector = new ScreenUndimDetector();

        mBatterySavingStats = new BatterySavingStats(mLock);
        mBatterySaverPolicy =
                mInjector.createBatterySaverPolicy(mLock, mContext, mBatterySavingStats);
        mBatterySaverController = mInjector.createBatterySaverController(mLock, mContext,
                mBatterySaverPolicy, mBatterySavingStats);
        mBatterySaverStateMachine = mInjector.createBatterySaverStateMachine(mLock, mContext,
                mBatterySaverController);

        mLowPowerStandbyController = mInjector.createLowPowerStandbyController(mContext,
                Looper.getMainLooper());
        mInattentiveSleepWarningOverlayController =
                mInjector.createInattentiveSleepWarningController();

        mAppOpsManager = injector.createAppOpsManager(mContext);

        mPowerGroupWakefulnessChangeListener = new PowerGroupWakefulnessChangeListener();

        // Save brightness values:
        // Get float values from config.
        // Store float if valid
        // Otherwise, get int values and convert to float and then store.
        final float min = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMinimumFloat);
        final float max = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMaximumFloat);
        final float def = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingDefaultFloat);
        final float doze = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessDozeFloat);
        final float dim = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessDimFloat);

        if (min == INVALID_BRIGHTNESS_IN_CONFIG || max == INVALID_BRIGHTNESS_IN_CONFIG
                || def == INVALID_BRIGHTNESS_IN_CONFIG) {
            mScreenBrightnessMinimum = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessSettingMinimum));
            mScreenBrightnessMaximum = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessSettingMaximum));
            mScreenBrightnessDefault = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessSettingDefault));
        } else {
            mScreenBrightnessMinimum = min;
            mScreenBrightnessMaximum = max;
            mScreenBrightnessDefault = def;
        }
        if (doze == INVALID_BRIGHTNESS_IN_CONFIG) {
            mScreenBrightnessDoze = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessDoze));
        } else {
            mScreenBrightnessDoze = doze;
        }
        if (dim == INVALID_BRIGHTNESS_IN_CONFIG) {
            mScreenBrightnessDim = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessDim));
        } else {
            mScreenBrightnessDim = dim;
        }

        final float vrMin = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingForVrMinimumFloat);
        final float vrMax = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingForVrMaximumFloat);
        final float vrDef = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingForVrDefaultFloat);
        if (vrMin == INVALID_BRIGHTNESS_IN_CONFIG || vrMax == INVALID_BRIGHTNESS_IN_CONFIG
                || vrDef == INVALID_BRIGHTNESS_IN_CONFIG) {
            mScreenBrightnessMinimumVr = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessForVrSettingMinimum));
            mScreenBrightnessMaximumVr = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessForVrSettingMaximum));
            mScreenBrightnessDefaultVr = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessForVrSettingDefault));
        } else {
            mScreenBrightnessMinimumVr = vrMin;
            mScreenBrightnessMaximumVr = vrMax;
            mScreenBrightnessDefaultVr = vrDef;
        }

        synchronized (mLock) {
            mBootingSuspendBlocker =
                    mInjector.createSuspendBlocker(this, "PowerManagerService.Booting");
            mWakeLockSuspendBlocker =
                    mInjector.createSuspendBlocker(this, "PowerManagerService.WakeLocks");
            mDisplaySuspendBlocker =
                    mInjector.createSuspendBlocker(this, "PowerManagerService.Display");
            if (mBootingSuspendBlocker != null) {
                mBootingSuspendBlocker.acquire();
                mHoldingBootingSuspendBlocker = true;
            }
            if (mDisplaySuspendBlocker != null) {
                mDisplaySuspendBlocker.acquire();
                mHoldingDisplaySuspendBlocker = true;
            }
            mHalAutoSuspendModeEnabled = false;
            mHalInteractiveModeEnabled = true;

            mWakefulnessRaw = WAKEFULNESS_AWAKE;
            sQuiescent = mSystemProperties.get(SYSTEM_PROPERTY_QUIESCENT, "0").equals("1")
                    || InitProperties.userspace_reboot_in_progress().orElse(false);

            mNativeWrapper.nativeInit(this);
            mNativeWrapper.nativeSetAutoSuspend(false);
            mNativeWrapper.nativeSetPowerMode(Mode.INTERACTIVE, true);
            mNativeWrapper.nativeSetPowerMode(Mode.DOUBLE_TAP_TO_WAKE, false);
            mInjector.invalidateIsInteractiveCaches();
        }
    }

    private void onFlip(boolean isFaceDown) {
        long millisUntilNormalTimeout = 0;
        synchronized (mLock) {
            if (!mBootCompleted) {
                return;
            }

            mIsFaceDown = isFaceDown;
            if (isFaceDown) {
                final long currentTime = mClock.uptimeMillis();
                mLastFlipTime = currentTime;
                final long sleepTimeout = getSleepTimeoutLocked(-1L);
                final long screenOffTimeout = getScreenOffTimeoutLocked(sleepTimeout, -1L);
                final PowerGroup powerGroup = mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP);
                millisUntilNormalTimeout =
                        powerGroup.getLastUserActivityTimeLocked() + screenOffTimeout - currentTime;
                userActivityInternal(Display.DEFAULT_DISPLAY, currentTime,
                        PowerManager.USER_ACTIVITY_EVENT_FACE_DOWN, /* flags= */0,
                        Process.SYSTEM_UID);
            }
        }
        if (isFaceDown) {
            mFaceDownDetector.setMillisSaved(millisUntilNormalTimeout);
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.POWER_SERVICE, mBinderService, /* allowIsolated= */ false,
                DUMP_FLAG_PRIORITY_DEFAULT | DUMP_FLAG_PRIORITY_CRITICAL);
        publishLocalService(PowerManagerInternal.class, mLocalService);

        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            systemReady();

        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            incrementBootCount();

        } else if (phase == PHASE_BOOT_COMPLETED) {
            synchronized (mLock) {
                final long now = mClock.uptimeMillis();
                mBootCompleted = true;
                mDirty |= DIRTY_BOOT_COMPLETED;

                mBatterySaverStateMachine.onBootCompleted();
                userActivityNoUpdateLocked(
                        now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);

                updatePowerStateLocked();
                if (sQuiescent) {
                    sleepPowerGroupLocked(mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP),
                            mClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_QUIESCENT,
                            Process.SYSTEM_UID);
                }

                mContext.getSystemService(DeviceStateManager.class).registerCallback(
                        new HandlerExecutor(mHandler), new DeviceStateListener());
            }
        }
    }

    private void systemReady() {
        synchronized (mLock) {
            mSystemReady = true;
            mDreamManager = getLocalService(DreamManagerInternal.class);
            mDisplayManagerInternal = getLocalService(DisplayManagerInternal.class);
            mPolicy = getLocalService(WindowManagerPolicy.class);
            mBatteryManagerInternal = getLocalService(BatteryManagerInternal.class);
            mAttentionDetector.systemReady(mContext);

            SensorManager sensorManager = new SystemSensorManager(mContext, mHandler.getLooper());

            // The notifier runs on the system server's main looper so as not to interfere
            // with the animations and other critical functions of the power manager.
            mBatteryStats = BatteryStatsService.getService();
            mNotifier = mInjector.createNotifier(Looper.getMainLooper(), mContext, mBatteryStats,
                    mInjector.createSuspendBlocker(this, "PowerManagerService.Broadcasts"),
                    mPolicy, mFaceDownDetector, mScreenUndimDetector,
                    BackgroundThread.getExecutor());

            mPowerGroups.append(Display.DEFAULT_DISPLAY_GROUP,
                    new PowerGroup(WAKEFULNESS_AWAKE, mPowerGroupWakefulnessChangeListener,
                            mNotifier, mDisplayManagerInternal, mClock.uptimeMillis()));
            DisplayGroupPowerChangeListener displayGroupPowerChangeListener =
                    new DisplayGroupPowerChangeListener();
            mDisplayManagerInternal.registerDisplayGroupListener(displayGroupPowerChangeListener);

            mWirelessChargerDetector = mInjector.createWirelessChargerDetector(sensorManager,
                    mInjector.createSuspendBlocker(
                            this, "PowerManagerService.WirelessChargerDetector"),
                    mHandler);
            mSettingsObserver = new SettingsObserver(mHandler);

            mLightsManager = getLocalService(LightsManager.class);
            mAttentionLight = mLightsManager.getLight(LightsManager.LIGHT_ID_ATTENTION);

            // Initialize display power management.
            mDisplayManagerInternal.initPowerManagement(
                    mDisplayPowerCallbacks, mHandler, sensorManager);

            try {
                final ForegroundProfileObserver observer = new ForegroundProfileObserver();
                ActivityManager.getService().registerUserSwitchObserver(observer, TAG);
            } catch (RemoteException e) {
                // Shouldn't happen since in-process.
            }

            mLowPowerStandbyController.systemReady();

            // Go.
            readConfigurationLocked();
            updateSettingsLocked();
            mDirty |= DIRTY_BATTERY_STATE;
            updatePowerStateLocked();
        }

        final ContentResolver resolver = mContext.getContentResolver();
        mConstants.start(resolver);

        mBatterySaverController.systemReady();
        mBatterySaverPolicy.systemReady();
        mFaceDownDetector.systemReady(mContext);
        mScreenUndimDetector.systemReady(mContext);

        // Register for settings changes.
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.SCREENSAVER_ENABLED),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.SCREEN_OFF_TIMEOUT),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.SLEEP_TIMEOUT),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ATTENTIVE_TIMEOUT),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.SCREEN_BRIGHTNESS_MODE),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.THEATER_MODE_ON),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.DOZE_ALWAYS_ON),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.DOUBLE_TAP_TO_WAKE),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.DEVICE_DEMO_MODE),
                false, mSettingsObserver, UserHandle.USER_SYSTEM);
        IVrManager vrManager = IVrManager.Stub.asInterface(getBinderService(Context.VR_SERVICE));
        if (vrManager != null) {
            try {
                vrManager.registerListener(mVrStateCallbacks);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register VR mode state listener: " + e);
            }
        }

        // Register for broadcasts from other components of the system.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(new BatteryReceiver(), filter, null, mHandler);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        mContext.registerReceiver(new DreamReceiver(), filter, null, mHandler);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(new UserSwitchedReceiver(), filter, null, mHandler);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DOCK_EVENT);
        mContext.registerReceiver(new DockReceiver(), filter, null, mHandler);
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void readConfigurationLocked() {
        final Resources resources = mContext.getResources();

        mDecoupleHalAutoSuspendModeFromDisplayConfig = resources.getBoolean(
                com.android.internal.R.bool.config_powerDecoupleAutoSuspendModeFromDisplay);
        mDecoupleHalInteractiveModeFromDisplayConfig = resources.getBoolean(
                com.android.internal.R.bool.config_powerDecoupleInteractiveModeFromDisplay);
        mWakeUpWhenPluggedOrUnpluggedConfig = resources.getBoolean(
                com.android.internal.R.bool.config_unplugTurnsOnScreen);
        mWakeUpWhenPluggedOrUnpluggedInTheaterModeConfig = resources.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromUnplug);
        mSuspendWhenScreenOffDueToProximityConfig = resources.getBoolean(
                com.android.internal.R.bool.config_suspendWhenScreenOffDueToProximity);
        mAttentiveTimeoutConfig = resources.getInteger(
                com.android.internal.R.integer.config_attentiveTimeout);
        mAttentiveWarningDurationConfig = resources.getInteger(
                com.android.internal.R.integer.config_attentiveWarningDuration);
        mDreamsSupportedConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsSupported);
        mDreamsEnabledByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);
        mDreamsActivatedOnSleepByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault);
        mDreamsActivatedOnDockByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault);
        mDreamsEnabledOnBatteryConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledOnBattery);
        mDreamsBatteryLevelMinimumWhenPoweredConfig = resources.getInteger(
                com.android.internal.R.integer.config_dreamsBatteryLevelMinimumWhenPowered);
        mDreamsBatteryLevelMinimumWhenNotPoweredConfig = resources.getInteger(
                com.android.internal.R.integer.config_dreamsBatteryLevelMinimumWhenNotPowered);
        mDreamsBatteryLevelDrainCutoffConfig = resources.getInteger(
                com.android.internal.R.integer.config_dreamsBatteryLevelDrainCutoff);
        mDozeAfterScreenOff = resources.getBoolean(
                com.android.internal.R.bool.config_dozeAfterScreenOffByDefault);
        mMinimumScreenOffTimeoutConfig = resources.getInteger(
                com.android.internal.R.integer.config_minimumScreenOffTimeout);
        mMaximumScreenDimDurationConfig = resources.getInteger(
                com.android.internal.R.integer.config_maximumScreenDimDuration);
        mMaximumScreenDimRatioConfig = resources.getFraction(
                com.android.internal.R.fraction.config_maximumScreenDimRatio, 1, 1);
        mSupportsDoubleTapWakeConfig = resources.getBoolean(
                com.android.internal.R.bool.config_supportDoubleTapWake);
    }

    @GuardedBy("mLock")
    private void updateSettingsLocked() {
        final ContentResolver resolver = mContext.getContentResolver();

        mDreamsEnabledSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ENABLED,
                mDreamsEnabledByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mDreamsActivateOnSleepSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                mDreamsActivatedOnSleepByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mDreamsActivateOnDockSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                mDreamsActivatedOnDockByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mScreenOffTimeoutSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_TIMEOUT,
                UserHandle.USER_CURRENT);
        mSleepTimeoutSetting = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SLEEP_TIMEOUT, DEFAULT_SLEEP_TIMEOUT,
                UserHandle.USER_CURRENT);
        mAttentiveTimeoutSetting = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.ATTENTIVE_TIMEOUT, mAttentiveTimeoutConfig,
                UserHandle.USER_CURRENT);
        mStayOnWhilePluggedInSetting = Settings.Global.getInt(resolver,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, BatteryManager.BATTERY_PLUGGED_AC);
        mTheaterModeEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.THEATER_MODE_ON, 0) == 1;
        mAlwaysOnEnabled = mAmbientDisplayConfiguration.alwaysOnEnabled(UserHandle.USER_CURRENT);

        if (mSupportsDoubleTapWakeConfig) {
            boolean doubleTapWakeEnabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.DOUBLE_TAP_TO_WAKE, DEFAULT_DOUBLE_TAP_TO_WAKE,
                            UserHandle.USER_CURRENT) != 0;
            if (doubleTapWakeEnabled != mDoubleTapWakeEnabled) {
                mDoubleTapWakeEnabled = doubleTapWakeEnabled;
                mNativeWrapper.nativeSetPowerMode(Mode.DOUBLE_TAP_TO_WAKE, mDoubleTapWakeEnabled);
            }
        }

        final String retailDemoValue = UserManager.isDeviceInDemoMode(mContext) ? "1" : "0";
        if (!retailDemoValue.equals(
                mSystemProperties.get(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED, null))) {
            mSystemProperties.set(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED, retailDemoValue);
        }

        mScreenBrightnessModeSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);

        mDirty |= DIRTY_SETTINGS;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void handleSettingsChangedLocked() {
        updateSettingsLocked();
        updatePowerStateLocked();
    }

    private void acquireWakeLockInternal(IBinder lock, int displayId, int flags, String tag,
            String packageName, WorkSource ws, String historyTag, int uid, int pid,
            @Nullable IWakeLockCallback callback) {

        boolean isCallerPrivileged = false;
        try {
            ApplicationInfo appInfo = mContext.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.ApplicationInfoFlags.of(0));
            isCallerPrivileged = appInfo.uid == uid && appInfo.isPrivilegedApp();
        } catch (PackageManager.NameNotFoundException e) {
            // assume app is not privileged
        }
        synchronized (mLock) {
            if (displayId != Display.INVALID_DISPLAY) {
                final DisplayInfo displayInfo =
                        mSystemReady ? mDisplayManagerInternal.getDisplayInfo(displayId) : null;
                if (displayInfo == null) {
                    Slog.wtf(TAG, "Tried to acquire wake lock for invalid display: " + displayId);
                    return;
                } else if (!displayInfo.hasAccess(uid)) {
                    throw new SecurityException("Caller does not have access to display");
                }
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "acquireWakeLockInternal: lock=" + Objects.hashCode(lock)
                        + ", flags=0x" + Integer.toHexString(flags)
                        + ", tag=\"" + tag + "\", ws=" + ws + ", uid=" + uid + ", pid=" + pid);
            }

            WakeLock wakeLock;
            int index = findWakeLockIndexLocked(lock);
            boolean notifyAcquire;
            if (index >= 0) {
                wakeLock = mWakeLocks.get(index);
                if (!wakeLock.hasSameProperties(flags, tag, ws, uid, pid, callback)) {
                    // Update existing wake lock.  This shouldn't happen but is harmless.
                    notifyWakeLockChangingLocked(wakeLock, flags, tag, packageName,
                            uid, pid, ws, historyTag, callback);
                    wakeLock.updateProperties(flags, tag, packageName, ws, historyTag, uid, pid,
                            callback);
                }
                notifyAcquire = false;
            } else {
                UidState state = mUidState.get(uid);
                if (state == null) {
                    state = new UidState(uid);
                    state.mProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;
                    mUidState.put(uid, state);
                }
                state.mNumWakeLocks++;
                wakeLock = new WakeLock(lock, displayId, flags, tag, packageName, ws, historyTag,
                        uid, pid, state, callback);
                mWakeLocks.add(wakeLock);
                setWakeLockDisabledStateLocked(wakeLock);
                notifyAcquire = true;
            }

            applyWakeLockFlagsOnAcquireLocked(wakeLock, isCallerPrivileged);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
            if (notifyAcquire) {
                // This needs to be done last so we are sure we have acquired the
                // kernel wake lock.  Otherwise we have a race where the system may
                // go to sleep between the time we start the accounting in battery
                // stats and when we actually get around to telling the kernel to
                // stay awake.
                notifyWakeLockAcquiredLocked(wakeLock);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isScreenLock(final WakeLock wakeLock) {
        switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
            case PowerManager.FULL_WAKE_LOCK:
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                return true;
        }
        return false;
    }

    private static WorkChain getFirstNonEmptyWorkChain(WorkSource workSource) {
        if (workSource.getWorkChains() == null) {
            return null;
        }

        for (WorkChain workChain: workSource.getWorkChains()) {
            if (workChain.getSize() > 0) {
                return workChain;
            }
        }

        return null;
    }

    private boolean isAcquireCausesWakeupFlagAllowed(String opPackageName, int opUid,
            boolean isCallerPrivileged) {
        if (opPackageName == null) {
            return false;
        }
        if (isCallerPrivileged) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "Allowing device wake-up for privileged app, call attributed to "
                        + opPackageName);
            }
            return true;
        }
        if (mAppOpsManager.checkOpNoThrow(AppOpsManager.OP_TURN_SCREEN_ON, opUid, opPackageName)
                == AppOpsManager.MODE_ALLOWED) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "Allowing device wake-up for app with special access " + opPackageName);
            }
            return true;
        }
        if (DEBUG_SPEW) {
            Slog.d(TAG, "Not allowing device wake-up for " + opPackageName);
        }
        return false;
    }

    @GuardedBy("mLock")
    private void applyWakeLockFlagsOnAcquireLocked(WakeLock wakeLock, boolean isCallerPrivileged) {
        if ((wakeLock.mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0
                && isScreenLock(wakeLock)) {
            String opPackageName;
            int opUid;
            if (wakeLock.mWorkSource != null && !wakeLock.mWorkSource.isEmpty()) {
                WorkSource workSource = wakeLock.mWorkSource;
                WorkChain workChain = getFirstNonEmptyWorkChain(workSource);
                if (workChain != null) {
                    opPackageName = workChain.getAttributionTag();
                    opUid = workChain.getAttributionUid();
                } else {
                    opPackageName = workSource.getPackageName(0) != null
                            ? workSource.getPackageName(0) : wakeLock.mPackageName;
                    opUid = workSource.getUid(0);
                }
            } else {
                opPackageName = wakeLock.mPackageName;
                opUid = wakeLock.mOwnerUid;
            }
            Integer powerGroupId = wakeLock.getPowerGroupId();
            // powerGroupId is null if the wakelock associated display is no longer available
            if (powerGroupId != null && isAcquireCausesWakeupFlagAllowed(opPackageName, opUid,
                    isCallerPrivileged)) {
                if (powerGroupId == Display.INVALID_DISPLAY_GROUP) {
                    // wake up all display groups
                    if (DEBUG_SPEW) {
                        Slog.d(TAG, "Waking up all power groups");
                    }
                    for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                        wakePowerGroupLocked(mPowerGroups.valueAt(idx), mClock.uptimeMillis(),
                                PowerManager.WAKE_REASON_APPLICATION, wakeLock.mTag, opUid,
                                opPackageName, opUid);
                    }
                    return;
                }
                if (mPowerGroups.contains(powerGroupId)) {
                    if (DEBUG_SPEW) {
                        Slog.d(TAG, "Waking up power group " + powerGroupId);
                    }
                    wakePowerGroupLocked(mPowerGroups.get(powerGroupId), mClock.uptimeMillis(),
                            PowerManager.WAKE_REASON_APPLICATION, wakeLock.mTag, opUid,
                            opPackageName, opUid);
                }
            }
        }
    }

    private void releaseWakeLockInternal(IBinder lock, int flags) {
        synchronized (mLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "releaseWakeLockInternal: lock=" + Objects.hashCode(lock)
                            + " [not found], flags=0x" + Integer.toHexString(flags));
                }
                return;
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            if (DEBUG_SPEW) {
                Slog.d(TAG, "releaseWakeLockInternal: lock=" + Objects.hashCode(lock)
                        + " [" + wakeLock.mTag + "], flags=0x" + Integer.toHexString(flags));
            }

            if ((flags & PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) != 0) {
                mRequestWaitForNegativeProximity = true;
            }

            wakeLock.unlinkToDeath();
            wakeLock.setDisabled(true);
            removeWakeLockLocked(wakeLock, index);
        }
    }

    private void handleWakeLockDeath(WakeLock wakeLock) {
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleWakeLockDeath: lock=" + Objects.hashCode(wakeLock.mLock)
                        + " [" + wakeLock.mTag + "]");
            }

            int index = mWakeLocks.indexOf(wakeLock);
            if (index < 0) {
                return;
            }

            removeWakeLockLocked(wakeLock, index);
        }
    }

    @GuardedBy("mLock")
    private void removeWakeLockLocked(WakeLock wakeLock, int index) {
        mWakeLocks.remove(index);
        UidState state = wakeLock.mUidState;
        state.mNumWakeLocks--;
        if (state.mNumWakeLocks <= 0 &&
                state.mProcState == ActivityManager.PROCESS_STATE_NONEXISTENT) {
            mUidState.remove(state.mUid);
        }
        notifyWakeLockReleasedLocked(wakeLock);

        applyWakeLockFlagsOnReleaseLocked(wakeLock);
        mDirty |= DIRTY_WAKE_LOCKS;
        updatePowerStateLocked();
    }

    @GuardedBy("mLock")
    private void applyWakeLockFlagsOnReleaseLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.ON_AFTER_RELEASE) != 0
                && isScreenLock(wakeLock)) {
            userActivityNoUpdateLocked(mClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS,
                    wakeLock.mOwnerUid);
        }
    }

    private void updateWakeLockWorkSourceInternal(IBinder lock, WorkSource ws, String historyTag,
            int callingUid) {
        synchronized (mLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateWakeLockWorkSourceInternal: lock=" + Objects.hashCode(lock)
                            + " [not found], ws=" + ws);
                }
                throw new IllegalArgumentException("Wake lock not active: " + lock
                        + " from uid " + callingUid);
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockWorkSourceInternal: lock=" + Objects.hashCode(lock)
                        + " [" + wakeLock.mTag + "], ws=" + ws);
            }

            if (!wakeLock.hasSameWorkSource(ws)) {
                notifyWakeLockChangingLocked(wakeLock, wakeLock.mFlags, wakeLock.mTag,
                        wakeLock.mPackageName, wakeLock.mOwnerUid, wakeLock.mOwnerPid,
                        ws, historyTag, null);
                wakeLock.mHistoryTag = historyTag;
                wakeLock.updateWorkSource(ws);
            }
        }
    }

    private void updateWakeLockCallbackInternal(IBinder lock, IWakeLockCallback callback,
            int callingUid) {
        synchronized (mLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateWakeLockCallbackInternal: lock=" + Objects.hashCode(lock)
                            + " [not found]");
                }
                throw new IllegalArgumentException("Wake lock not active: " + lock
                        + " from uid " + callingUid);
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockCallbackInternal: lock=" + Objects.hashCode(lock)
                        + " [" + wakeLock.mTag + "]");
            }

            if (!isSameCallback(callback, wakeLock.mCallback)) {
                notifyWakeLockChangingLocked(wakeLock, wakeLock.mFlags, wakeLock.mTag,
                        wakeLock.mPackageName, wakeLock.mOwnerUid, wakeLock.mOwnerPid,
                        wakeLock.mWorkSource, wakeLock.mHistoryTag, callback);
                wakeLock.mCallback = callback;
            }
        }
    }

    @GuardedBy("mLock")
    private int findWakeLockIndexLocked(IBinder lock) {
        final int count = mWakeLocks.size();
        for (int i = 0; i < count; i++) {
            if (mWakeLocks.get(i).mLock == lock) {
                return i;
            }
        }
        return -1;
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    WakeLock findWakeLockLocked(IBinder lock) {
        int index = findWakeLockIndexLocked(lock);
        if (index == -1) {
            return null;
        }
        return mWakeLocks.get(index);
    }

    @GuardedBy("mLock")
    private void notifyWakeLockAcquiredLocked(WakeLock wakeLock) {
        if (mSystemReady && !wakeLock.mDisabled) {
            wakeLock.mNotifiedAcquired = true;
            mNotifier.onWakeLockAcquired(wakeLock.mFlags, wakeLock.mTag, wakeLock.mPackageName,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource,
                    wakeLock.mHistoryTag, wakeLock.mCallback);
            restartNofifyLongTimerLocked(wakeLock);
        }
    }

    @GuardedBy("mLock")
    private void enqueueNotifyLongMsgLocked(long time) {
        mNotifyLongScheduled = time;
        Message msg = mHandler.obtainMessage(MSG_CHECK_FOR_LONG_WAKELOCKS);
        msg.setAsynchronous(true);
        mHandler.sendMessageAtTime(msg, time);
    }

    @GuardedBy("mLock")
    private void restartNofifyLongTimerLocked(WakeLock wakeLock) {
        wakeLock.mAcquireTime = mClock.uptimeMillis();
        if ((wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK)
                == PowerManager.PARTIAL_WAKE_LOCK && mNotifyLongScheduled == 0) {
            enqueueNotifyLongMsgLocked(wakeLock.mAcquireTime + MIN_LONG_WAKE_CHECK_INTERVAL);
        }
    }

    @GuardedBy("mLock")
    private void notifyWakeLockLongStartedLocked(WakeLock wakeLock) {
        if (mSystemReady && !wakeLock.mDisabled) {
            wakeLock.mNotifiedLong = true;
            mNotifier.onLongPartialWakeLockStart(wakeLock.mTag, wakeLock.mOwnerUid,
                    wakeLock.mWorkSource, wakeLock.mHistoryTag);
        }
    }

    @GuardedBy("mLock")
    private void notifyWakeLockLongFinishedLocked(WakeLock wakeLock) {
        if (wakeLock.mNotifiedLong) {
            wakeLock.mNotifiedLong = false;
            mNotifier.onLongPartialWakeLockFinish(wakeLock.mTag, wakeLock.mOwnerUid,
                    wakeLock.mWorkSource, wakeLock.mHistoryTag);
        }
    }

    @GuardedBy("mLock")
    private void notifyWakeLockChangingLocked(WakeLock wakeLock, int flags, String tag,
            String packageName, int uid, int pid, WorkSource ws, String historyTag,
            IWakeLockCallback callback) {
        if (mSystemReady && wakeLock.mNotifiedAcquired) {
            mNotifier.onWakeLockChanging(wakeLock.mFlags, wakeLock.mTag, wakeLock.mPackageName,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource,
                    wakeLock.mHistoryTag, wakeLock.mCallback, flags, tag, packageName, uid, pid, ws,
                    historyTag, callback);
            notifyWakeLockLongFinishedLocked(wakeLock);
            // Changing the wake lock will count as releasing the old wake lock(s) and
            // acquiring the new ones...  we do this because otherwise once a wakelock
            // becomes long, if we just continued to treat it as long we can get in to
            // situations where we spam battery stats with every following change to it.
            restartNofifyLongTimerLocked(wakeLock);
        }
    }

    @GuardedBy("mLock")
    private void notifyWakeLockReleasedLocked(WakeLock wakeLock) {
        if (mSystemReady && wakeLock.mNotifiedAcquired) {
            wakeLock.mNotifiedAcquired = false;
            wakeLock.mAcquireTime = 0;
            mNotifier.onWakeLockReleased(wakeLock.mFlags, wakeLock.mTag,
                    wakeLock.mPackageName, wakeLock.mOwnerUid, wakeLock.mOwnerPid,
                    wakeLock.mWorkSource, wakeLock.mHistoryTag, wakeLock.mCallback);
            notifyWakeLockLongFinishedLocked(wakeLock);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isWakeLockLevelSupportedInternal(int level) {
        synchronized (mLock) {
            switch (level) {
                case PowerManager.PARTIAL_WAKE_LOCK:
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                case PowerManager.FULL_WAKE_LOCK:
                case PowerManager.DOZE_WAKE_LOCK:
                case PowerManager.DRAW_WAKE_LOCK:
                    return true;

                case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                    return mSystemReady && mDisplayManagerInternal.isProximitySensorAvailable();

                default:
                    return false;
            }
        }
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void userActivityFromNative(long eventTime, int event, int displayId, int flags) {
        userActivityInternal(displayId, eventTime, event, flags, Process.SYSTEM_UID);
    }

    private void userActivityInternal(int displayId, long eventTime, int event, int flags,
            int uid) {
        synchronized (mLock) {
            if (displayId == Display.INVALID_DISPLAY) {
                if (userActivityNoUpdateLocked(eventTime, event, flags, uid)) {
                    updatePowerStateLocked();
                }
                return;
            }

            final DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(displayId);
            if (displayInfo == null) {
                return;
            }
            final int groupId = displayInfo.displayGroupId;
            if (groupId == Display.INVALID_DISPLAY_GROUP) {
                return;
            }
            if (userActivityNoUpdateLocked(mPowerGroups.get(groupId), eventTime, event, flags,
                    uid)) {
                updatePowerStateLocked();
            }
        }
    }

    private void onUserAttention() {
        synchronized (mLock) {
            if (userActivityNoUpdateLocked(mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP),
                    mClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_ATTENTION,
                    /* flags= */ 0,
                    Process.SYSTEM_UID)) {
                updatePowerStateLocked();
            }
        }
    }

    @GuardedBy("mLock")
    private boolean userActivityNoUpdateLocked(long eventTime, int event, int flags, int uid) {
        boolean updatePowerState = false;
        for (int idx = 0; idx < mPowerGroups.size(); idx++) {
            if (userActivityNoUpdateLocked(mPowerGroups.valueAt(idx), eventTime, event, flags,
                    uid)) {
                updatePowerState = true;
            }
        }

        return updatePowerState;
    }

    @GuardedBy("mLock")
    private boolean userActivityNoUpdateLocked(final PowerGroup powerGroup, long eventTime,
            int event, int flags, int uid) {
        final int groupId = powerGroup.getGroupId();
        if (DEBUG_SPEW) {
            Slog.d(TAG, "userActivityNoUpdateLocked: groupId=" + groupId
                    + ", eventTime=" + eventTime + ", event=" + event
                    + ", flags=0x" + Integer.toHexString(flags) + ", uid=" + uid);
        }

        if (eventTime < powerGroup.getLastSleepTimeLocked()
                || eventTime < powerGroup.getLastWakeTimeLocked() || !mSystemReady) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "userActivity");
        try {
            if (eventTime > mLastInteractivePowerHintTime) {
                setPowerBoostInternal(Boost.INTERACTION, 0);
                mLastInteractivePowerHintTime = eventTime;
            }

            mNotifier.onUserActivity(powerGroup.getGroupId(), event, uid);
            mAttentionDetector.onUserActivity(eventTime, event);

            if (mUserInactiveOverrideFromWindowManager) {
                mUserInactiveOverrideFromWindowManager = false;
                mOverriddenTimeout = -1;
            }
            final int wakefulness = powerGroup.getWakefulnessLocked();
            if (wakefulness == WAKEFULNESS_ASLEEP
                    || wakefulness == WAKEFULNESS_DOZING
                    || (flags & PowerManager.USER_ACTIVITY_FLAG_INDIRECT) != 0) {
                return false;
            }

            maybeUpdateForegroundProfileLastActivityLocked(eventTime);

            if ((flags & PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS) != 0) {
                if (eventTime > powerGroup.getLastUserActivityTimeNoChangeLightsLocked()
                        && eventTime > powerGroup.getLastUserActivityTimeLocked()) {
                    powerGroup.setLastUserActivityTimeNoChangeLightsLocked(eventTime);
                    mDirty |= DIRTY_USER_ACTIVITY;
                    if (event == PowerManager.USER_ACTIVITY_EVENT_BUTTON) {
                        mDirty |= DIRTY_QUIESCENT;
                    }

                    return true;
                }
            } else {
                if (eventTime > powerGroup.getLastUserActivityTimeLocked()) {
                    powerGroup.setLastUserActivityTimeLocked(eventTime);
                    mDirty |= DIRTY_USER_ACTIVITY;
                    if (event == PowerManager.USER_ACTIVITY_EVENT_BUTTON) {
                        mDirty |= DIRTY_QUIESCENT;
                    }
                    return true;
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return false;
    }

    @GuardedBy("mLock")
    private void maybeUpdateForegroundProfileLastActivityLocked(long eventTime) {
        final ProfilePowerState profile = mProfilePowerState.get(mForegroundProfile);
        if (profile != null && eventTime > profile.mLastUserActivityTime) {
            profile.mLastUserActivityTime = eventTime;
        }
    }

    @GuardedBy("mLock")
    private void wakePowerGroupLocked(final PowerGroup powerGroup, long eventTime,
            @WakeReason int reason, String details, int uid, String opPackageName, int opUid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "wakePowerGroupLocked: eventTime=" + eventTime
                    + ", groupId=" + powerGroup.getGroupId() + ", uid=" + uid);
        }
        if (mForceSuspendActive || !mSystemReady) {
            return;
        }
        powerGroup.wakeUpLocked(eventTime, reason, details, uid, opPackageName, opUid,
                LatencyTracker.getInstance(mContext));
    }

    @GuardedBy("mLock")
    private boolean dreamPowerGroupLocked(PowerGroup powerGroup, long eventTime, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "dreamPowerGroup: groupId=" + powerGroup.getGroupId() + ", eventTime="
                    + eventTime + ", uid=" + uid);
        }
        if (!mBootCompleted || !mSystemReady) {
            return false;
        }
        return powerGroup.dreamLocked(eventTime, uid);
    }

    @GuardedBy("mLock")
    private boolean dozePowerGroupLocked(final PowerGroup powerGroup, long eventTime,
            int reason, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "dozePowerGroup: eventTime=" + eventTime
                    + ", groupId=" + powerGroup.getGroupId() + ", reason=" + reason
                    + ", uid=" + uid);
        }

        if (!mSystemReady || !mBootCompleted) {
            return false;
        }

        return powerGroup.dozeLocked(eventTime, uid, reason);
    }

    @GuardedBy("mLock")
    private boolean sleepPowerGroupLocked(final PowerGroup powerGroup, long eventTime, int reason,
            int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "sleepPowerGroup: eventTime=" + eventTime + ", uid=" + uid);
        }
        if (!mBootCompleted || !mSystemReady) {
            return false;
        }

        return powerGroup.sleepLocked(eventTime, uid, reason);
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void setWakefulnessLocked(int groupId, int wakefulness, long eventTime, int uid, int reason,
            int opUid, String opPackageName, String details) {
        mPowerGroups.get(groupId).setWakefulnessLocked(wakefulness, eventTime, uid, reason, opUid,
                opPackageName, details);
    }

    @SuppressWarnings("deprecation")
    @GuardedBy("mLock")
    private void updateGlobalWakefulnessLocked(long eventTime, int reason, int uid,
            int opUid, String opPackageName, String details) {
        int newWakefulness = recalculateGlobalWakefulnessLocked();
        int currentWakefulness = getGlobalWakefulnessLocked();
        if (currentWakefulness == newWakefulness) {
            return;
        }

        // Phase 1: Handle pre-wakefulness change bookkeeping.
        final String traceMethodName;
        switch (newWakefulness) {
            case WAKEFULNESS_ASLEEP:
                traceMethodName = "reallyGoToSleep";
                Slog.i(TAG, "Sleeping (uid " + uid + ")...");
                // TODO(b/215518989): Remove this once transactions are in place
                if (currentWakefulness != WAKEFULNESS_DOZING) {
                    // in case we are going to sleep without dozing before
                    mLastGlobalSleepTime = eventTime;
                    mLastGlobalSleepReason = reason;
                }
                break;

            case WAKEFULNESS_AWAKE:
                traceMethodName = "wakeUp";
                Slog.i(TAG, "Waking up from "
                        + PowerManagerInternal.wakefulnessToString(currentWakefulness)
                        + " (uid=" + uid
                        + ", reason=" + PowerManager.wakeReasonToString(reason)
                        + ", details=" + details
                        + ")...");
                mLastGlobalWakeTime = eventTime;
                mLastGlobalWakeReason = reason;
                break;

            case WAKEFULNESS_DREAMING:
                traceMethodName = "nap";
                Slog.i(TAG, "Nap time (uid " + uid + ")...");
                break;

            case WAKEFULNESS_DOZING:
                traceMethodName = "goToSleep";
                Slog.i(TAG, "Going to sleep due to " + PowerManager.sleepReasonToString(reason)
                        + " (uid " + uid + ")...");

                mLastGlobalSleepTime = eventTime;
                mLastGlobalSleepReason = reason;
                mDozeStartInProgress = true;
                break;

            default:
                throw new IllegalArgumentException("Unexpected wakefulness: " + newWakefulness);
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, traceMethodName);
        try {
            // Phase 2: Handle wakefulness change and bookkeeping.
            // Under lock, invalidate before set ensures caches won't return stale values.
            mInjector.invalidateIsInteractiveCaches();
            mWakefulnessRaw = newWakefulness;
            mWakefulnessChanging = true;
            mDirty |= DIRTY_WAKEFULNESS;

            // This is only valid while we are in wakefulness dozing. Set to false otherwise.
            mDozeStartInProgress &= (newWakefulness == WAKEFULNESS_DOZING);

            if (mNotifier != null) {
                mNotifier.onWakefulnessChangeStarted(newWakefulness, reason, eventTime);
            }
            mAttentionDetector.onWakefulnessChangeStarted(newWakefulness);

            // Phase 3: Handle post-wakefulness change bookkeeping.
            switch (newWakefulness) {
                case WAKEFULNESS_AWAKE:
                    mNotifier.onWakeUp(reason, details, uid, opPackageName, opUid);
                    if (sQuiescent) {
                        mDirty |= DIRTY_QUIESCENT;
                    }
                    break;

                case WAKEFULNESS_ASLEEP:
                    // fallthrough
                case WAKEFULNESS_DOZING:
                    if (!isInteractive(currentWakefulness)) {
                        // TODO(b/215518989): remove this once transactions are in place
                        break;
                    }
                    // Report the number of wake locks that will be cleared by going to sleep.
                    int numWakeLocksCleared = 0;
                    final int numWakeLocks = mWakeLocks.size();
                    for (int i = 0; i < numWakeLocks; i++) {
                        final WakeLock wakeLock = mWakeLocks.get(i);
                        switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                            case PowerManager.FULL_WAKE_LOCK:
                            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                                numWakeLocksCleared += 1;
                                break;
                        }
                    }
                    EventLogTags.writePowerSleepRequested(numWakeLocksCleared);
                    break;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    int getGlobalWakefulnessLocked() {
        return mWakefulnessRaw;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    int getWakefulnessLocked(int groupId) {
        return mPowerGroups.get(groupId).getWakefulnessLocked();
    }

    /**
     * Returns the amalgamated wakefulness of all {@link PowerGroup PowerGroups}.
     *
     * <p>This will be the highest wakeful state of all {@link PowerGroup PowerGroups}; ordered
     * from highest to lowest:
     * <ol>
     *     <li>{@link PowerManagerInternal#WAKEFULNESS_AWAKE}
     *     <li>{@link PowerManagerInternal#WAKEFULNESS_DREAMING}
     *     <li>{@link PowerManagerInternal#WAKEFULNESS_DOZING}
     *     <li>{@link PowerManagerInternal#WAKEFULNESS_ASLEEP}
     * </ol>
     */
    @GuardedBy("mLock")
    int recalculateGlobalWakefulnessLocked() {
        int deviceWakefulness = WAKEFULNESS_ASLEEP;
        for (int i = 0; i < mPowerGroups.size(); i++) {
            final int wakefulness = mPowerGroups.valueAt(i).getWakefulnessLocked();
            if (wakefulness == WAKEFULNESS_AWAKE) {
                return WAKEFULNESS_AWAKE;
            } else if (wakefulness == WAKEFULNESS_DREAMING
                    && (deviceWakefulness == WAKEFULNESS_ASLEEP
                    || deviceWakefulness == WAKEFULNESS_DOZING)) {
                deviceWakefulness = WAKEFULNESS_DREAMING;
            } else if (wakefulness == WAKEFULNESS_DOZING
                    && deviceWakefulness == WAKEFULNESS_ASLEEP) {
                deviceWakefulness = WAKEFULNESS_DOZING;
            }
        }

        return deviceWakefulness;
    }

    @GuardedBy("mLock")
    void onPowerGroupEventLocked(int event, PowerGroup powerGroup) {
        final int groupId = powerGroup.getGroupId();
        if (event == DisplayGroupPowerChangeListener.DISPLAY_GROUP_REMOVED) {
            mPowerGroups.delete(groupId);
        }
        final int oldWakefulness = getGlobalWakefulnessLocked();
        final int newWakefulness = recalculateGlobalWakefulnessLocked();

        if (event == DisplayGroupPowerChangeListener.DISPLAY_GROUP_ADDED
                && newWakefulness == WAKEFULNESS_AWAKE) {
            // Kick user activity to prevent newly added group from timing out instantly.
            userActivityNoUpdateLocked(powerGroup, mClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER, /* flags= */ 0, Process.SYSTEM_UID);
        }

        if (oldWakefulness != newWakefulness) {
            final int reason;
            switch (newWakefulness) {
                case WAKEFULNESS_AWAKE:
                    reason = event == DisplayGroupPowerChangeListener.DISPLAY_GROUP_ADDED
                            ? WAKE_REASON_DISPLAY_GROUP_ADDED
                            : WAKE_REASON_DISPLAY_GROUP_TURNED_ON;
                    break;
                case WAKEFULNESS_DOZING:
                    reason = event == DisplayGroupPowerChangeListener.DISPLAY_GROUP_REMOVED
                            ? GO_TO_SLEEP_REASON_DISPLAY_GROUP_REMOVED
                            : GO_TO_SLEEP_REASON_DISPLAY_GROUPS_TURNED_OFF;
                    break;
                default:
                    reason = 0;
            }
            updateGlobalWakefulnessLocked(mClock.uptimeMillis(), reason, Process.SYSTEM_UID,
                    Process.SYSTEM_UID, mContext.getOpPackageName(), "groupId: " + groupId);
        }
        mDirty |= DIRTY_DISPLAY_GROUP_WAKEFULNESS;
        updatePowerStateLocked();
    }

    /**
     * Logs the time the device would have spent awake before user activity timeout,
     * had the system not been told the user was inactive.
     */
    @GuardedBy("mLock")
    private void logSleepTimeoutRecapturedLocked() {
        final long now = mClock.uptimeMillis();
        final long savedWakeTimeMs = mOverriddenTimeout - now;
        if (savedWakeTimeMs >= 0) {
            EventLogTags.writePowerSoftSleepRequested(savedWakeTimeMs);
            mOverriddenTimeout = -1;
        }
    }

    @GuardedBy("mLock")
    private void finishWakefulnessChangeIfNeededLocked() {
        if (mWakefulnessChanging && areAllPowerGroupsReadyLocked()) {
            if (getGlobalWakefulnessLocked() == WAKEFULNESS_DOZING
                    && (mWakeLockSummary & WAKE_LOCK_DOZE) == 0) {
                return; // wait until dream has enabled dozing
            } else {
                // Doze wakelock acquired (doze started) or device is no longer dozing.
                mDozeStartInProgress = false;
            }
            if (getGlobalWakefulnessLocked() == WAKEFULNESS_DOZING
                    || getGlobalWakefulnessLocked() == WAKEFULNESS_ASLEEP) {
                logSleepTimeoutRecapturedLocked();
            }
            mWakefulnessChanging = false;
            mNotifier.onWakefulnessChangeFinished();
        }
    }

    /**
     * Returns {@code true} if all {@link PowerGroup}s are ready, i.e. every display has its
     * requested state matching its actual state.
     */
    @GuardedBy("mLock")
    private boolean areAllPowerGroupsReadyLocked() {
        final int size = mPowerGroups.size();
        for (int i = 0; i < size; i++) {
            if (!mPowerGroups.valueAt(i).isReadyLocked()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Updates the global power state based on dirty bits recorded in mDirty.
     *
     * This is the main function that performs power state transitions.
     * We centralize them here so that we can recompute the power state completely
     * each time something important changes, and ensure that we do it the same
     * way each time.  The point is to gather all of the transition logic here.
     */
    @GuardedBy("mLock")
    private void updatePowerStateLocked() {
        if (!mSystemReady || mDirty == 0 || mUpdatePowerStateInProgress) {
            return;
        }
        if (!Thread.holdsLock(mLock)) {
            Slog.wtf(TAG, "Power manager lock was not held when calling updatePowerStateLocked");
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "updatePowerState");
        mUpdatePowerStateInProgress = true;
        try {
            // Phase 0: Basic state updates.
            updateIsPoweredLocked(mDirty);
            updateStayOnLocked(mDirty);
            updateScreenBrightnessBoostLocked(mDirty);

            // Phase 1: Update wakefulness.
            // Loop because the wake lock and user activity computations are influenced
            // by changes in wakefulness.
            final long now = mClock.uptimeMillis();
            int dirtyPhase2 = 0;
            for (;;) {
                int dirtyPhase1 = mDirty;
                dirtyPhase2 |= dirtyPhase1;
                mDirty = 0;

                updateWakeLockSummaryLocked(dirtyPhase1);
                updateUserActivitySummaryLocked(now, dirtyPhase1);
                updateAttentiveStateLocked(now, dirtyPhase1);
                if (!updateWakefulnessLocked(dirtyPhase1)) {
                    break;
                }
            }

            // Phase 2: Lock profiles that became inactive/not kept awake.
            updateProfilesLocked(now);

            // Phase 3: Update power state of all PowerGroups.
            final boolean powerGroupsBecameReady = updatePowerGroupsLocked(dirtyPhase2);

            // Phase 4: Update dream state (depends on power group ready signal).
            updateDreamLocked(dirtyPhase2, powerGroupsBecameReady);

            // Phase 5: Send notifications, if needed.
            finishWakefulnessChangeIfNeededLocked();

            // Phase 6: Update suspend blocker.
            // Because we might release the last suspend blocker here, we need to make sure
            // we finished everything else first!
            updateSuspendBlockerLocked();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
            mUpdatePowerStateInProgress = false;
        }
    }

    /**
     * Check profile timeouts and notify profiles that should be locked.
     */
    @GuardedBy("mLock")
    private void updateProfilesLocked(long now) {
        final int numProfiles = mProfilePowerState.size();
        for (int i = 0; i < numProfiles; i++) {
            final ProfilePowerState profile = mProfilePowerState.valueAt(i);
            if (isProfileBeingKeptAwakeLocked(profile, now)) {
                profile.mLockingNotified = false;
            } else if (!profile.mLockingNotified) {
                profile.mLockingNotified = true;
                mNotifier.onProfileTimeout(profile.mUserId);
            }
        }
    }

    @GuardedBy("mLock")
    private boolean isProfileBeingKeptAwakeLocked(ProfilePowerState profile, long now) {
        return (profile.mLastUserActivityTime + profile.mScreenOffTimeout > now)
                || (profile.mWakeLockSummary & WAKE_LOCK_STAY_AWAKE) != 0
                || (mProximityPositive &&
                    (profile.mWakeLockSummary & WAKE_LOCK_PROXIMITY_SCREEN_OFF) != 0);
    }

    /**
     * Updates the value of mIsPowered.
     * Sets DIRTY_IS_POWERED if a change occurred.
     */
    @GuardedBy("mLock")
    private void updateIsPoweredLocked(int dirty) {
        if ((dirty & DIRTY_BATTERY_STATE) != 0) {
            final boolean wasPowered = mIsPowered;
            final int oldPlugType = mPlugType;
            mIsPowered = mBatteryManagerInternal.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
            mPlugType = mBatteryManagerInternal.getPlugType();
            mBatteryLevel = mBatteryManagerInternal.getBatteryLevel();
            mBatteryLevelLow = mBatteryManagerInternal.getBatteryLevelLow();

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateIsPoweredLocked: wasPowered=" + wasPowered
                        + ", mIsPowered=" + mIsPowered
                        + ", oldPlugType=" + oldPlugType
                        + ", mPlugType=" + mPlugType
                        + ", mBatteryLevel=" + mBatteryLevel);
            }

            if (wasPowered != mIsPowered || oldPlugType != mPlugType) {
                mDirty |= DIRTY_IS_POWERED;

                // Update wireless dock detection state.
                final boolean dockedOnWirelessCharger = mWirelessChargerDetector.update(
                        mIsPowered, mPlugType);

                // Treat plugging and unplugging the devices as a user activity.
                // Users find it disconcerting when they plug or unplug the device
                // and it shuts off right away.
                // Some devices also wake the device when plugged or unplugged because
                // they don't have a charging LED.
                final long now = mClock.uptimeMillis();
                if (shouldWakeUpWhenPluggedOrUnpluggedLocked(wasPowered, oldPlugType,
                        dockedOnWirelessCharger)) {
                    wakePowerGroupLocked(mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP),
                            now, PowerManager.WAKE_REASON_PLUGGED_IN,
                            "android.server.power:PLUGGED:" + mIsPowered, Process.SYSTEM_UID,
                            mContext.getOpPackageName(), Process.SYSTEM_UID);
                }

                userActivityNoUpdateLocked(mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP), now,
                        PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);

                // only play charging sounds if boot is completed so charging sounds don't play
                // with potential notification sounds
                if (mBootCompleted) {
                    if (mIsPowered && !BatteryManager.isPlugWired(oldPlugType)
                            && BatteryManager.isPlugWired(mPlugType)) {
                        mNotifier.onWiredChargingStarted(mUserId);
                    } else if (dockedOnWirelessCharger) {
                        mNotifier.onWirelessChargingStarted(mBatteryLevel, mUserId);
                    }
                }
            }

            mBatterySaverStateMachine.setBatteryStatus(mIsPowered, mBatteryLevel, mBatteryLevelLow);
        }
    }

    @GuardedBy("mLock")
    private boolean shouldWakeUpWhenPluggedOrUnpluggedLocked(
            boolean wasPowered, int oldPlugType, boolean dockedOnWirelessCharger) {
        // Don't wake when powered unless configured to do so.
        if (!mWakeUpWhenPluggedOrUnpluggedConfig) {
            return false;
        }

        // Don't wake when undocked from wireless charger.
        // See WirelessChargerDetector for justification.
        if (wasPowered && !mIsPowered
                && oldPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            return false;
        }

        // Don't wake when docked on wireless charger unless we are certain of it.
        // See WirelessChargerDetector for justification.
        if (!wasPowered && mIsPowered
                && mPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS
                && !dockedOnWirelessCharger) {
            return false;
        }

        // If already dreaming and becoming powered, then don't wake.
        if (mIsPowered && getGlobalWakefulnessLocked() == WAKEFULNESS_DREAMING) {
            return false;
        }

        // Don't wake while theater mode is enabled.
        if (mTheaterModeEnabled && !mWakeUpWhenPluggedOrUnpluggedInTheaterModeConfig) {
            return false;
        }

        // On Always On Display, SystemUI shows the charging indicator
        if (mAlwaysOnEnabled && getGlobalWakefulnessLocked() == WAKEFULNESS_DOZING) {
            return false;
        }

        // Otherwise wake up!
        return true;
    }

    /**
     * Updates the value of mStayOn.
     * Sets DIRTY_STAY_ON if a change occurred.
     */
    @GuardedBy("mLock")
    private void updateStayOnLocked(int dirty) {
        if ((dirty & (DIRTY_BATTERY_STATE | DIRTY_SETTINGS)) != 0) {
            final boolean wasStayOn = mStayOn;
            if (mStayOnWhilePluggedInSetting != 0
                    && !isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()) {
                mStayOn = mBatteryManagerInternal.isPowered(mStayOnWhilePluggedInSetting);
            } else {
                mStayOn = false;
            }

            if (mStayOn != wasStayOn) {
                mDirty |= DIRTY_STAY_ON;
            }
        }
    }

    /**
     * Updates the value of mWakeLockSummary to summarize the state of all active wake locks.
     * Note that most wake-locks are ignored when the system is asleep.
     *
     * This function must have no other side effects.
     */
    @GuardedBy("mLock")
    private void updateWakeLockSummaryLocked(int dirty) {
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_WAKEFULNESS | DIRTY_DISPLAY_GROUP_WAKEFULNESS))
                != 0) {

            mWakeLockSummary = 0;

            final int numProfiles = mProfilePowerState.size();
            for (int i = 0; i < numProfiles; i++) {
                mProfilePowerState.valueAt(i).mWakeLockSummary = 0;
            }

            for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                mPowerGroups.valueAt(idx).setWakeLockSummaryLocked(0);
            }

            int invalidGroupWakeLockSummary = 0;
            final int numWakeLocks = mWakeLocks.size();
            for (int i = 0; i < numWakeLocks; i++) {
                final WakeLock wakeLock = mWakeLocks.get(i);
                final Integer groupId = wakeLock.getPowerGroupId();
                // a wakelock with an invalid group ID should affect all groups
                if (groupId == null || (groupId != Display.INVALID_DISPLAY_GROUP
                        && !mPowerGroups.contains(groupId))) {
                    continue;
                }

                final PowerGroup powerGroup = mPowerGroups.get(groupId);
                final int wakeLockFlags = getWakeLockSummaryFlags(wakeLock);
                mWakeLockSummary |= wakeLockFlags;

                if (groupId != Display.INVALID_DISPLAY_GROUP) {
                    int wakeLockSummary = powerGroup.getWakeLockSummaryLocked();
                    wakeLockSummary |= wakeLockFlags;
                    powerGroup.setWakeLockSummaryLocked(wakeLockSummary);
                } else {
                    invalidGroupWakeLockSummary |= wakeLockFlags;
                }

                for (int j = 0; j < numProfiles; j++) {
                    final ProfilePowerState profile = mProfilePowerState.valueAt(j);
                    if (wakeLockAffectsUser(wakeLock, profile.mUserId)) {
                        profile.mWakeLockSummary |= wakeLockFlags;
                    }
                }
            }

            for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                final PowerGroup powerGroup = mPowerGroups.valueAt(idx);
                final int wakeLockSummary = adjustWakeLockSummary(powerGroup.getWakefulnessLocked(),
                        invalidGroupWakeLockSummary | powerGroup.getWakeLockSummaryLocked());
                powerGroup.setWakeLockSummaryLocked(wakeLockSummary);
            }

            mWakeLockSummary = adjustWakeLockSummary(getGlobalWakefulnessLocked(),
                    mWakeLockSummary);

            for (int i = 0; i < numProfiles; i++) {
                final ProfilePowerState profile = mProfilePowerState.valueAt(i);
                profile.mWakeLockSummary = adjustWakeLockSummary(getGlobalWakefulnessLocked(),
                        profile.mWakeLockSummary);
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockSummaryLocked: mWakefulness="
                        + PowerManagerInternal.wakefulnessToString(getGlobalWakefulnessLocked())
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            }
        }
    }

    private static int adjustWakeLockSummary(int wakefulness, int wakeLockSummary) {
        // Cancel wake locks that make no sense based on the current state.
        if (wakefulness != WAKEFULNESS_DOZING) {
            wakeLockSummary &= ~(WAKE_LOCK_DOZE | WAKE_LOCK_DRAW);
        }
        if (wakefulness == WAKEFULNESS_ASLEEP
                || (wakeLockSummary & WAKE_LOCK_DOZE) != 0) {
            wakeLockSummary &= ~(WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_SCREEN_DIM
                    | WAKE_LOCK_BUTTON_BRIGHT);
            if (wakefulness == WAKEFULNESS_ASLEEP) {
                wakeLockSummary &= ~WAKE_LOCK_PROXIMITY_SCREEN_OFF;
            }
        }

        // Infer implied wake locks where necessary based on the current state.
        if ((wakeLockSummary & (WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_SCREEN_DIM)) != 0) {
            if (wakefulness == WAKEFULNESS_AWAKE) {
                wakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_STAY_AWAKE;
            } else if (wakefulness == WAKEFULNESS_DREAMING) {
                wakeLockSummary |= WAKE_LOCK_CPU;
            }
        }
        if ((wakeLockSummary & WAKE_LOCK_DRAW) != 0) {
            wakeLockSummary |= WAKE_LOCK_CPU;
        }

        return wakeLockSummary;
    }

    /** Get wake lock summary flags that correspond to the given wake lock. */
    @SuppressWarnings("deprecation")
    private int getWakeLockSummaryFlags(WakeLock wakeLock) {
        switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
            case PowerManager.PARTIAL_WAKE_LOCK:
                if (!wakeLock.mDisabled) {
                    // We only respect this if the wake lock is not disabled.
                    return WAKE_LOCK_CPU;
                }
                break;
            case PowerManager.FULL_WAKE_LOCK:
                return WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_BUTTON_BRIGHT;
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                return WAKE_LOCK_SCREEN_BRIGHT;
            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                return WAKE_LOCK_SCREEN_DIM;
            case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                return WAKE_LOCK_PROXIMITY_SCREEN_OFF;
            case PowerManager.DOZE_WAKE_LOCK:
                return WAKE_LOCK_DOZE;
            case PowerManager.DRAW_WAKE_LOCK:
                return WAKE_LOCK_DRAW;
        }
        return 0;
    }

    private boolean wakeLockAffectsUser(WakeLock wakeLock, @UserIdInt int userId) {
        if (wakeLock.mWorkSource != null) {
            for (int k = 0; k < wakeLock.mWorkSource.size(); k++) {
                final int uid = wakeLock.mWorkSource.getUid(k);
                if (userId == UserHandle.getUserId(uid)) {
                    return true;
                }
            }

            final List<WorkChain> workChains = wakeLock.mWorkSource.getWorkChains();
            if (workChains != null) {
                for (int k = 0; k < workChains.size(); k++) {
                    final int uid = workChains.get(k).getAttributionUid();
                    if (userId == UserHandle.getUserId(uid)) {
                        return true;
                    }
                }
            }
        }
        return userId == UserHandle.getUserId(wakeLock.mOwnerUid);
    }

    void checkForLongWakeLocks() {
        synchronized (mLock) {
            final long now = mClock.uptimeMillis();
            mNotifyLongDispatched = now;
            final long when = now - MIN_LONG_WAKE_CHECK_INTERVAL;
            long nextCheckTime = Long.MAX_VALUE;
            final int numWakeLocks = mWakeLocks.size();
            for (int i = 0; i < numWakeLocks; i++) {
                final WakeLock wakeLock = mWakeLocks.get(i);
                if ((wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK)
                        == PowerManager.PARTIAL_WAKE_LOCK) {
                    if (wakeLock.mNotifiedAcquired && !wakeLock.mNotifiedLong) {
                        if (wakeLock.mAcquireTime < when) {
                            // This wake lock has exceeded the long acquire time, report!
                            notifyWakeLockLongStartedLocked(wakeLock);
                        } else {
                            // This wake lock could still become a long one, at this time.
                            long checkTime = wakeLock.mAcquireTime + MIN_LONG_WAKE_CHECK_INTERVAL;
                            if (checkTime < nextCheckTime) {
                                nextCheckTime = checkTime;
                            }
                        }
                    }
                }
            }
            mNotifyLongScheduled = 0;
            mHandler.removeMessages(MSG_CHECK_FOR_LONG_WAKELOCKS);
            if (nextCheckTime != Long.MAX_VALUE) {
                mNotifyLongNextCheck = nextCheckTime;
                enqueueNotifyLongMsgLocked(nextCheckTime);
            } else {
                mNotifyLongNextCheck = 0;
            }
        }
    }

    /**
     * Updates the value of mUserActivitySummary to summarize the user requested
     * state of the system such as whether the screen should be bright or dim.
     * Note that user activity is ignored when the system is asleep.
     *
     * This function must have no other side-effects.
     */
    @GuardedBy("mLock")
    private void updateUserActivitySummaryLocked(long now, int dirty) {
        // Update the status of the user activity timeout timer.
        if ((dirty & (DIRTY_DISPLAY_GROUP_WAKEFULNESS | DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY
                | DIRTY_WAKEFULNESS | DIRTY_SETTINGS | DIRTY_ATTENTIVE)) == 0) {
            return;
        }
        mHandler.removeMessages(MSG_USER_ACTIVITY_TIMEOUT);

        final long attentiveTimeout = getAttentiveTimeoutLocked();
        final long sleepTimeout = getSleepTimeoutLocked(attentiveTimeout);
        long screenOffTimeout = getScreenOffTimeoutLocked(sleepTimeout,
                attentiveTimeout);
        final long screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
        screenOffTimeout =
                getScreenOffTimeoutWithFaceDownLocked(screenOffTimeout, screenDimDuration);
        final boolean userInactiveOverride = mUserInactiveOverrideFromWindowManager;
        long nextTimeout = -1;
        boolean hasUserActivitySummary = false;
        for (int idx = 0; idx < mPowerGroups.size(); idx++) {
            int groupUserActivitySummary = 0;
            long groupNextTimeout = 0;
            final PowerGroup powerGroup = mPowerGroups.valueAt(idx);
            final int wakefulness = powerGroup.getWakefulnessLocked();
            if (wakefulness != WAKEFULNESS_ASLEEP) {
                final long lastUserActivityTime = powerGroup.getLastUserActivityTimeLocked();
                final long lastUserActivityTimeNoChangeLights =
                        powerGroup.getLastUserActivityTimeNoChangeLightsLocked();
                if (lastUserActivityTime >= powerGroup.getLastWakeTimeLocked()) {
                    groupNextTimeout = lastUserActivityTime + screenOffTimeout - screenDimDuration;
                    if (now < groupNextTimeout) {
                        groupUserActivitySummary = USER_ACTIVITY_SCREEN_BRIGHT;
                    } else {
                        groupNextTimeout = lastUserActivityTime + screenOffTimeout;
                        if (now < groupNextTimeout) {
                            groupUserActivitySummary = USER_ACTIVITY_SCREEN_DIM;
                        }
                    }
                }
                if (groupUserActivitySummary == 0 && lastUserActivityTimeNoChangeLights
                        >= powerGroup.getLastWakeTimeLocked()) {
                    groupNextTimeout = lastUserActivityTimeNoChangeLights + screenOffTimeout;
                    if (now < groupNextTimeout) {
                        if (powerGroup.isPolicyBrightLocked() || powerGroup.isPolicyVrLocked()) {
                            groupUserActivitySummary = USER_ACTIVITY_SCREEN_BRIGHT;
                        } else if (powerGroup.isPolicyDimLocked()) {
                            groupUserActivitySummary = USER_ACTIVITY_SCREEN_DIM;
                        }
                    }
                }

                if (groupUserActivitySummary == 0) {
                    if (sleepTimeout >= 0) {
                        final long anyUserActivity = Math.max(lastUserActivityTime,
                                lastUserActivityTimeNoChangeLights);
                        if (anyUserActivity >= powerGroup.getLastWakeTimeLocked()) {
                            groupNextTimeout = anyUserActivity + sleepTimeout;
                            if (now < groupNextTimeout) {
                                groupUserActivitySummary = USER_ACTIVITY_SCREEN_DREAM;
                            }
                        }
                    } else {
                        groupUserActivitySummary = USER_ACTIVITY_SCREEN_DREAM;
                        groupNextTimeout = -1;
                    }
                }

                if (groupUserActivitySummary != USER_ACTIVITY_SCREEN_DREAM
                        && userInactiveOverride) {
                    if ((groupUserActivitySummary &
                            (USER_ACTIVITY_SCREEN_BRIGHT | USER_ACTIVITY_SCREEN_DIM)) != 0) {
                        // Device is being kept awake by recent user activity
                        if (mOverriddenTimeout == -1) {
                            // Save when the next timeout would have occurred
                            mOverriddenTimeout = groupNextTimeout;
                        }
                    }
                    groupUserActivitySummary = USER_ACTIVITY_SCREEN_DREAM;
                    groupNextTimeout = -1;
                }

                if ((groupUserActivitySummary & USER_ACTIVITY_SCREEN_BRIGHT) != 0
                        && (powerGroup.getWakeLockSummaryLocked()
                        & WAKE_LOCK_STAY_AWAKE) == 0) {
                    groupNextTimeout = mAttentionDetector.updateUserActivity(groupNextTimeout,
                            screenDimDuration);
                }

                if (isAttentiveTimeoutExpired(powerGroup, now)) {
                    groupUserActivitySummary = 0;
                    groupNextTimeout = -1;
                }

                hasUserActivitySummary |= groupUserActivitySummary != 0;

                if (nextTimeout == -1) {
                    nextTimeout = groupNextTimeout;
                } else if (groupNextTimeout != -1) {
                    nextTimeout = Math.min(nextTimeout, groupNextTimeout);
                }
            }

            powerGroup.setUserActivitySummaryLocked(groupUserActivitySummary);

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateUserActivitySummaryLocked: groupId=" + powerGroup.getGroupId()
                        + ", mWakefulness=" + wakefulnessToString(wakefulness)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(
                        groupUserActivitySummary)
                        + ", nextTimeout=" + TimeUtils.formatUptime(groupNextTimeout));
            }
        }

        final long nextProfileTimeout = getNextProfileTimeoutLocked(now);
        if (nextProfileTimeout > 0) {
            nextTimeout = Math.min(nextTimeout, nextProfileTimeout);
        }

        if (hasUserActivitySummary && nextTimeout >= 0) {
            scheduleUserInactivityTimeout(nextTimeout);
        }
    }

    private void scheduleUserInactivityTimeout(long timeMs) {
        final Message msg = mHandler.obtainMessage(MSG_USER_ACTIVITY_TIMEOUT);
        msg.setAsynchronous(true);
        mHandler.sendMessageAtTime(msg, timeMs);
    }

    private void scheduleAttentiveTimeout(long timeMs) {
        final Message msg = mHandler.obtainMessage(MSG_ATTENTIVE_TIMEOUT);
        msg.setAsynchronous(true);
        mHandler.sendMessageAtTime(msg, timeMs);
    }

    /**
     * Finds the next profile timeout time or returns -1 if there are no profiles to be locked.
     */
    @GuardedBy("mLock")
    private long getNextProfileTimeoutLocked(long now) {
        long nextTimeout = -1;
        final int numProfiles = mProfilePowerState.size();
        for (int i = 0; i < numProfiles; i++) {
            final ProfilePowerState profile = mProfilePowerState.valueAt(i);
            final long timeout = profile.mLastUserActivityTime + profile.mScreenOffTimeout;
            if (timeout > now && (nextTimeout == -1 || timeout < nextTimeout)) {
                nextTimeout = timeout;
            }
        }
        return nextTimeout;
    }

    @GuardedBy("mLock")
    private void updateAttentiveStateLocked(long now, int dirty) {
        long attentiveTimeout = getAttentiveTimeoutLocked();
        // Attentive state only applies to the default display group.
        long goToSleepTime = mPowerGroups.get(
                Display.DEFAULT_DISPLAY_GROUP).getLastUserActivityTimeLocked() + attentiveTimeout;
        long showWarningTime = goToSleepTime - mAttentiveWarningDurationConfig;

        boolean warningDismissed = maybeHideInattentiveSleepWarningLocked(now, showWarningTime);

        if (attentiveTimeout >= 0 && (warningDismissed
                || (dirty & (DIRTY_ATTENTIVE | DIRTY_STAY_ON | DIRTY_SCREEN_BRIGHTNESS_BOOST
                | DIRTY_PROXIMITY_POSITIVE | DIRTY_WAKEFULNESS | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS)) != 0)) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "Updating attentive state");
            }

            mHandler.removeMessages(MSG_ATTENTIVE_TIMEOUT);

            if (isBeingKeptFromInattentiveSleepLocked()) {
                return;
            }

            long nextTimeout = -1;

            if (now < showWarningTime) {
                nextTimeout = showWarningTime;
            } else if (now < goToSleepTime) {
                if (DEBUG) {
                    long timeToSleep = goToSleepTime - now;
                    Slog.d(TAG, "Going to sleep in " + timeToSleep
                            + "ms if there is no user activity");
                }
                mInattentiveSleepWarningOverlayController.show();
                nextTimeout = goToSleepTime;
            }

            if (nextTimeout >= 0) {
                scheduleAttentiveTimeout(nextTimeout);
            }
        }
    }

    @GuardedBy("mLock")
    private boolean maybeHideInattentiveSleepWarningLocked(long now, long showWarningTime) {
        long attentiveTimeout = getAttentiveTimeoutLocked();

        if (!mInattentiveSleepWarningOverlayController.isShown()) {
            return false;
        }

        if (getGlobalWakefulnessLocked() != WAKEFULNESS_AWAKE) {
            mInattentiveSleepWarningOverlayController.dismiss(false);
            return true;
        } else if (attentiveTimeout < 0 || isBeingKeptFromInattentiveSleepLocked()
                || now < showWarningTime) {
            mInattentiveSleepWarningOverlayController.dismiss(true);
            return true;
        }

        return false;
    }

    @GuardedBy("mLock")
    private boolean isAttentiveTimeoutExpired(final PowerGroup powerGroup, long now) {
        long attentiveTimeout = getAttentiveTimeoutLocked();
        // Attentive state only applies to the default display group.
        return powerGroup.getGroupId() == Display.DEFAULT_DISPLAY_GROUP && attentiveTimeout >= 0
                && now >= powerGroup.getLastUserActivityTimeLocked() + attentiveTimeout;
    }

    /**
     * Called when a user activity timeout has occurred.
     * Simply indicates that something about user activity has changed so that the new
     * state can be recomputed when the power state is updated.
     *
     * This function must have no other side-effects besides setting the dirty
     * bit and calling update power state.  Wakefulness transitions are handled elsewhere.
     */
    private void handleUserActivityTimeout() { // runs on handler thread
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleUserActivityTimeout");
            }

            mDirty |= DIRTY_USER_ACTIVITY;
            updatePowerStateLocked();
        }
    }

    private void handleAttentiveTimeout() { // runs on handler thread
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleAttentiveTimeout");
            }

            mDirty |= DIRTY_ATTENTIVE;
            updatePowerStateLocked();
        }
    }

    @GuardedBy("mLock")
    private long getAttentiveTimeoutLocked() {
        long timeout = mAttentiveTimeoutSetting;
        if (timeout <= 0) {
            return -1;
        }

        return Math.max(timeout, mMinimumScreenOffTimeoutConfig);
    }

    @GuardedBy("mLock")
    private long getSleepTimeoutLocked(long attentiveTimeout) {
        long timeout = mSleepTimeoutSetting;
        if (timeout <= 0) {
            return -1;
        }
        if (attentiveTimeout >= 0) {
            timeout = Math.min(timeout, attentiveTimeout);
        }
        return Math.max(timeout, mMinimumScreenOffTimeoutConfig);
    }

    @GuardedBy("mLock")
    private long getScreenOffTimeoutLocked(long sleepTimeout, long attentiveTimeout) {
        long timeout = mScreenOffTimeoutSetting;
        if (isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()) {
            timeout = Math.min(timeout, mMaximumScreenOffTimeoutFromDeviceAdmin);
        }
        if (mUserActivityTimeoutOverrideFromWindowManager >= 0) {
            timeout = Math.min(timeout, mUserActivityTimeoutOverrideFromWindowManager);
        }
        if (sleepTimeout >= 0) {
            timeout = Math.min(timeout, sleepTimeout);
        }
        if (attentiveTimeout >= 0) {
            timeout = Math.min(timeout, attentiveTimeout);
        }
        return Math.max(timeout, mMinimumScreenOffTimeoutConfig);
    }

    @GuardedBy("mLock")
    private long getScreenDimDurationLocked(long screenOffTimeout) {
        return Math.min(mMaximumScreenDimDurationConfig,
                (long)(screenOffTimeout * mMaximumScreenDimRatioConfig));
    }

    @GuardedBy("mLock")
    private long getScreenOffTimeoutWithFaceDownLocked(
            long screenOffTimeout, long screenDimDuration) {
        // If face down, we decrease the timeout to equal the dim duration so that the
        // device will go into a dim state.
        if (mIsFaceDown) {
            return Math.min(screenDimDuration, screenOffTimeout);
        }
        return screenOffTimeout;
    }

    /**
     * Updates the wakefulness of the device.
     *
     * This is the function that decides whether the device should start dreaming
     * based on the current wake locks and user activity state.  It may modify mDirty
     * if the wakefulness changes.
     *
     * Returns true if the wakefulness changed and we need to restart power state calculation.
     */
    @GuardedBy("mLock")
    private boolean updateWakefulnessLocked(int dirty) {
        boolean changed = false;
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_BOOT_COMPLETED
                | DIRTY_WAKEFULNESS | DIRTY_STAY_ON | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_DOCK_STATE | DIRTY_ATTENTIVE | DIRTY_SETTINGS
                | DIRTY_SCREEN_BRIGHTNESS_BOOST)) == 0) {
            return changed;
        }
        final long time = mClock.uptimeMillis();
        for (int idx = 0; idx < mPowerGroups.size(); idx++) {
            final PowerGroup powerGroup = mPowerGroups.valueAt(idx);
            if (!(powerGroup.getWakefulnessLocked() == WAKEFULNESS_AWAKE
                    && isItBedTimeYetLocked(powerGroup))) {
                continue;
            }
            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakefulnessLocked: Bed time for group "
                        + powerGroup.getGroupId());
            }
            if (isAttentiveTimeoutExpired(powerGroup, time)) {
                if (DEBUG) {
                    Slog.i(TAG, "Going to sleep now due to long user inactivity");
                }
                changed = sleepPowerGroupLocked(powerGroup, time,
                        PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE, Process.SYSTEM_UID);
            } else if (shouldNapAtBedTimeLocked()) {
                changed = dreamPowerGroupLocked(powerGroup, time, Process.SYSTEM_UID);
            } else {
                changed = dozePowerGroupLocked(powerGroup, time,
                        PowerManager.GO_TO_SLEEP_REASON_TIMEOUT, Process.SYSTEM_UID);
            }
        }
        return changed;
    }

    /**
     * Returns true if the device should automatically nap and start dreaming when the user
     * activity timeout has expired and it's bedtime.
     */
    @GuardedBy("mLock")
    private boolean shouldNapAtBedTimeLocked() {
        return mDreamsActivateOnSleepSetting
                || (mDreamsActivateOnDockSetting
                        && mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED);
    }

    /**
     * Returns true if the provided {@link PowerGroup} should go to sleep now.
     * Also used when exiting a dream to determine whether we should go back to being fully awake or
     * else go to sleep for good.
     */
    @GuardedBy("mLock")
    private boolean isItBedTimeYetLocked(PowerGroup powerGroup) {
        if (!mBootCompleted) {
            return false;
        }

        long now = mClock.uptimeMillis();
        if (isAttentiveTimeoutExpired(powerGroup, now)) {
            return !isBeingKeptFromInattentiveSleepLocked();
        } else {
            return !isBeingKeptAwakeLocked(powerGroup);
        }
    }

    /**
     * Returns true if the provided {@link PowerGroup} is being kept awake by a wake lock, user
     * activity or the stay on while powered setting.  We also keep the phone awake when the
     * proximity sensor returns a positive result so that the device does not lock while in a phone
     * call. This function only controls whether the device will go to sleep or dream which is
     * independent of whether it will be allowed to suspend.
     */
    @GuardedBy("mLock")
    private boolean isBeingKeptAwakeLocked(final PowerGroup powerGroup) {
        return mStayOn
                || mProximityPositive
                || (powerGroup.getWakeLockSummaryLocked() & WAKE_LOCK_STAY_AWAKE) != 0
                || (powerGroup.getUserActivitySummaryLocked() & (
                        USER_ACTIVITY_SCREEN_BRIGHT | USER_ACTIVITY_SCREEN_DIM)) != 0
                || mScreenBrightnessBoostInProgress;
    }

    /**
     * Returns true if the device is prevented from going into inattentive sleep. We also keep the
     * device awake when the proximity sensor returns a positive result so that the device does not
     * lock while in a phone call. This function only controls whether the device will go to sleep
     * which is independent of whether it will be allowed to suspend.
     */
    @GuardedBy("mLock")
    private boolean isBeingKeptFromInattentiveSleepLocked() {
        return mStayOn || mScreenBrightnessBoostInProgress || mProximityPositive || !mBootCompleted;
    }

    /**
     * Determines whether to post a message to the sandman to update the dream state.
     */
    @GuardedBy("mLock")
    private void updateDreamLocked(int dirty, boolean powerGroupBecameReady) {
        if ((dirty & (DIRTY_WAKEFULNESS
                | DIRTY_USER_ACTIVITY
                | DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED
                | DIRTY_ATTENTIVE
                | DIRTY_WAKE_LOCKS
                | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS
                | DIRTY_IS_POWERED
                | DIRTY_STAY_ON
                | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_BATTERY_STATE)) != 0 || powerGroupBecameReady) {
            if (areAllPowerGroupsReadyLocked()) {
                scheduleSandmanLocked();
            }
        }
    }

    @GuardedBy("mLock")
    private void scheduleSandmanLocked() {
        if (!mSandmanScheduled) {
            mSandmanScheduled = true;
            for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                final PowerGroup powerGroup = mPowerGroups.valueAt(idx);
                if (powerGroup.supportsSandmanLocked()) {
                    Message msg = mHandler.obtainMessage(MSG_SANDMAN);
                    msg.arg1 = powerGroup.getGroupId();
                    msg.setAsynchronous(true);
                    mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
                }
            }
        }
    }

    /**
     * Called when the device enters or exits a dreaming or dozing state.
     *
     * We do this asynchronously because we must call out of the power manager to start
     * the dream and we don't want to hold our lock while doing so.  There is a risk that
     * the device will wake or go to sleep in the meantime so we have to handle that case.
     */
    private void handleSandman(int groupId) { // runs on handler thread
        // Handle preconditions.
        final boolean startDreaming;
        final int wakefulness;
        synchronized (mLock) {
            mSandmanScheduled = false;
            if (!mPowerGroups.contains(groupId)) {
                // Group has been removed.
                return;
            }
            final PowerGroup powerGroup = mPowerGroups.get(groupId);
            wakefulness = powerGroup.getWakefulnessLocked();
            if ((wakefulness == WAKEFULNESS_DREAMING || wakefulness == WAKEFULNESS_DOZING) &&
                    powerGroup.isSandmanSummonedLocked() && powerGroup.isReadyLocked()) {
                startDreaming = canDreamLocked(powerGroup) || canDozeLocked(powerGroup);
                powerGroup.setSandmanSummonedLocked(/* isSandmanSummoned= */ false);
            } else {
                startDreaming = false;
            }
        }

        // Start dreaming if needed.
        // We only control the dream on the handler thread, so we don't need to worry about
        // concurrent attempts to start or stop the dream.
        final boolean isDreaming;
        if (mDreamManager != null) {
            // Restart the dream whenever the sandman is summoned.
            if (startDreaming) {
                mDreamManager.stopDream(/* immediate= */ false);
                mDreamManager.startDream(wakefulness == WAKEFULNESS_DOZING);
            }
            isDreaming = mDreamManager.isDreaming();
        } else {
            isDreaming = false;
        }

        // At this point, we either attempted to start the dream or no attempt will be made,
        // so stop holding the display suspend blocker for Doze.
        mDozeStartInProgress = false;

        // Update dream state.
        synchronized (mLock) {
            if (!mPowerGroups.contains(groupId)) {
                // Group has been removed.
                return;
            }

            // Remember the initial battery level when the dream started.
            if (startDreaming && isDreaming) {
                mBatteryLevelWhenDreamStarted = mBatteryLevel;
                if (wakefulness == WAKEFULNESS_DOZING) {
                    Slog.i(TAG, "Dozing...");
                } else {
                    Slog.i(TAG, "Dreaming...");
                }
            }

            // If preconditions changed, wait for the next iteration to determine
            // whether the dream should continue (or be restarted).
            final PowerGroup powerGroup = mPowerGroups.get(groupId);
            if (powerGroup.isSandmanSummonedLocked()
                    || powerGroup.getWakefulnessLocked() != wakefulness) {
                return; // wait for next cycle
            }

            // Determine whether the dream should continue.
            long now = mClock.uptimeMillis();
            if (wakefulness == WAKEFULNESS_DREAMING) {
                if (isDreaming && canDreamLocked(powerGroup)) {
                    if (mDreamsBatteryLevelDrainCutoffConfig >= 0
                            && mBatteryLevel < mBatteryLevelWhenDreamStarted
                                    - mDreamsBatteryLevelDrainCutoffConfig
                            && !isBeingKeptAwakeLocked(powerGroup)) {
                        // If the user activity timeout expired and the battery appears
                        // to be draining faster than it is charging then stop dreaming
                        // and go to sleep.
                        Slog.i(TAG, "Stopping dream because the battery appears to "
                                + "be draining faster than it is charging.  "
                                + "Battery level when dream started: "
                                + mBatteryLevelWhenDreamStarted + "%.  "
                                + "Battery level now: " + mBatteryLevel + "%.");
                    } else {
                        return; // continue dreaming
                    }
                }

                // Dream has ended or will be stopped.  Update the power state.
                if (isItBedTimeYetLocked(powerGroup)) {
                    if (isAttentiveTimeoutExpired(powerGroup, now)) {
                        sleepPowerGroupLocked(powerGroup, now,
                                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT, Process.SYSTEM_UID);
                    } else {
                        dozePowerGroupLocked(powerGroup, now,
                                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT, Process.SYSTEM_UID);
                    }
                } else {
                    wakePowerGroupLocked(powerGroup, now,
                            PowerManager.WAKE_REASON_DREAM_FINISHED,
                            "android.server.power:DREAM_FINISHED", Process.SYSTEM_UID,
                            mContext.getOpPackageName(), Process.SYSTEM_UID);
                }
            } else if (wakefulness == WAKEFULNESS_DOZING) {
                if (isDreaming) {
                    return; // continue dozing
                }

                // Doze has ended or will be stopped.  Update the power state.
                sleepPowerGroupLocked(powerGroup, now,  PowerManager.GO_TO_SLEEP_REASON_TIMEOUT,
                        Process.SYSTEM_UID);
            }
        }

        // Stop dream.
        if (isDreaming) {
            mDreamManager.stopDream(/* immediate= */ false);
        }
    }

    /**
     * Returns true if the {@code groupId} is allowed to dream in its current state.
     */
    @GuardedBy("mLock")
    private boolean canDreamLocked(final PowerGroup powerGroup) {
        if (!mBootCompleted
                || getGlobalWakefulnessLocked() != WAKEFULNESS_DREAMING
                || !mDreamsSupportedConfig
                || !mDreamsEnabledSetting
                || !(powerGroup.isBrightOrDimLocked())
                || powerGroup.isPolicyVrLocked()
                || (powerGroup.getUserActivitySummaryLocked() & (USER_ACTIVITY_SCREEN_BRIGHT
                | USER_ACTIVITY_SCREEN_DIM | USER_ACTIVITY_SCREEN_DREAM)) == 0) {
            return false;
        }
        if (!isBeingKeptAwakeLocked(powerGroup)) {
            if (!mIsPowered && !mDreamsEnabledOnBatteryConfig) {
                return false;
            }
            if (!mIsPowered
                    && mDreamsBatteryLevelMinimumWhenNotPoweredConfig >= 0
                    && mBatteryLevel < mDreamsBatteryLevelMinimumWhenNotPoweredConfig) {
                return false;
            }
            return !mIsPowered
                    || mDreamsBatteryLevelMinimumWhenPoweredConfig < 0
                    || mBatteryLevel >= mDreamsBatteryLevelMinimumWhenPoweredConfig;
        }
        return true;
    }

    /**
     * Returns true if the device is allowed to doze in its current state.
     */
    @GuardedBy("mLock")
    private boolean canDozeLocked(PowerGroup powerGroup) {
        return powerGroup.supportsSandmanLocked()
                && powerGroup.getWakefulnessLocked() == WAKEFULNESS_DOZING;
    }

    /**
     * Updates the state of all {@link PowerGroup}s asynchronously.
     * When the update is finished, the ready state of the {@link PowerGroup} will be updated.
     * The display controllers post a message to tell us when the actual display power state
     * has been updated so we come back here to double-check and finish up.
     *
     * This function recalculates the {@link PowerGroup} state each time.
     *
     * @return {@code true} if all {@link PowerGroup}s became ready; {@code false} otherwise
     */
    @GuardedBy("mLock")
    private boolean updatePowerGroupsLocked(int dirty) {
        final boolean oldPowerGroupsReady = areAllPowerGroupsReadyLocked();
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_WAKEFULNESS
                | DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS | DIRTY_SCREEN_BRIGHTNESS_BOOST | DIRTY_VR_MODE_CHANGED |
                DIRTY_QUIESCENT | DIRTY_DISPLAY_GROUP_WAKEFULNESS)) != 0) {
            if ((dirty & DIRTY_QUIESCENT) != 0) {
                if (areAllPowerGroupsReadyLocked()) {
                    sQuiescent = false;
                } else {
                    mDirty |= DIRTY_QUIESCENT;
                }
            }

            for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                final PowerGroup powerGroup = mPowerGroups.valueAt(idx);
                final int groupId = powerGroup.getGroupId();

                // Determine appropriate screen brightness and auto-brightness adjustments.
                final boolean autoBrightness;
                final float screenBrightnessOverride;
                if (!mBootCompleted) {
                    // Keep the brightness steady during boot. This requires the
                    // bootloader brightness and the default brightness to be identical.
                    autoBrightness = false;
                    screenBrightnessOverride = mScreenBrightnessDefault;
                } else if (isValidBrightness(mScreenBrightnessOverrideFromWindowManager)) {
                    autoBrightness = false;
                    screenBrightnessOverride = mScreenBrightnessOverrideFromWindowManager;
                } else {
                    autoBrightness = (mScreenBrightnessModeSetting
                            == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                    screenBrightnessOverride = PowerManager.BRIGHTNESS_INVALID_FLOAT;
                }
                boolean ready = powerGroup.updateLocked(screenBrightnessOverride, autoBrightness,
                        shouldUseProximitySensorLocked(), shouldBoostScreenBrightness(),
                        mDozeScreenStateOverrideFromDreamManager,
                        mDozeScreenBrightnessOverrideFromDreamManagerFloat,
                        mDrawWakeLockOverrideFromSidekick,
                        mBatterySaverPolicy.getBatterySaverPolicy(ServiceType.SCREEN_BRIGHTNESS),
                        sQuiescent, mDozeAfterScreenOff, mIsVrModeEnabled, mBootCompleted,
                        mScreenBrightnessBoostInProgress, mRequestWaitForNegativeProximity);
                int wakefulness = powerGroup.getWakefulnessLocked();
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateDisplayPowerStateLocked: displayReady=" + ready
                            + ", groupId=" + groupId
                            + ", policy=" + policyToString(powerGroup.getPolicyLocked())
                            + ", mWakefulness="
                            + PowerManagerInternal.wakefulnessToString(wakefulness)
                            + ", mWakeLockSummary=0x" + Integer.toHexString(
                            powerGroup.getWakeLockSummaryLocked())
                            + ", mUserActivitySummary=0x" + Integer.toHexString(
                            powerGroup.getUserActivitySummaryLocked())
                            + ", mBootCompleted=" + mBootCompleted
                            + ", screenBrightnessOverride=" + screenBrightnessOverride
                            + ", useAutoBrightness=" + autoBrightness
                            + ", mScreenBrightnessBoostInProgress="
                            + mScreenBrightnessBoostInProgress
                            + ", mIsVrModeEnabled= " + mIsVrModeEnabled
                            + ", sQuiescent=" + sQuiescent);
                }

                final boolean displayReadyStateChanged = powerGroup.setReadyLocked(ready);
                final boolean poweringOn = powerGroup.isPoweringOnLocked();
                if (ready && displayReadyStateChanged && poweringOn
                        && wakefulness == WAKEFULNESS_AWAKE) {
                    powerGroup.setIsPoweringOnLocked(false);
                    LatencyTracker.getInstance(mContext).onActionEnd(ACTION_TURN_ON_SCREEN);
                    Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, TRACE_SCREEN_ON, groupId);
                    final int latencyMs = (int) (mClock.uptimeMillis()
                            - powerGroup.getLastPowerOnTimeLocked());
                    if (latencyMs >= SCREEN_ON_LATENCY_WARNING_MS) {
                        Slog.w(TAG, "Screen on took " + latencyMs + " ms");
                    }
                }
            }
            mRequestWaitForNegativeProximity = false;
        }

        return areAllPowerGroupsReadyLocked() && !oldPowerGroupsReady;
    }

    @GuardedBy("mLock")
    private void updateScreenBrightnessBoostLocked(int dirty) {
        if ((dirty & DIRTY_SCREEN_BRIGHTNESS_BOOST) != 0) {
            if (mScreenBrightnessBoostInProgress) {
                final long now = mClock.uptimeMillis();
                mHandler.removeMessages(MSG_SCREEN_BRIGHTNESS_BOOST_TIMEOUT);
                if (mLastScreenBrightnessBoostTime > mLastGlobalSleepTime) {
                    final long boostTimeout = mLastScreenBrightnessBoostTime +
                            SCREEN_BRIGHTNESS_BOOST_TIMEOUT;
                    if (boostTimeout > now) {
                        Message msg = mHandler.obtainMessage(MSG_SCREEN_BRIGHTNESS_BOOST_TIMEOUT);
                        msg.setAsynchronous(true);
                        mHandler.sendMessageAtTime(msg, boostTimeout);
                        return;
                    }
                }
                mScreenBrightnessBoostInProgress = false;
                userActivityNoUpdateLocked(now,
                        PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
            }
        }
    }

    private boolean shouldBoostScreenBrightness() {
        return !mIsVrModeEnabled && mScreenBrightnessBoostInProgress;
    }

    private static boolean isValidBrightness(float value) {
        return value >= PowerManager.BRIGHTNESS_MIN && value <= PowerManager.BRIGHTNESS_MAX;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    int getDesiredScreenPolicyLocked(int groupId) {
        return mPowerGroups.get(groupId).getDesiredScreenPolicyLocked(sQuiescent,
                mDozeAfterScreenOff, mIsVrModeEnabled, mBootCompleted,
                mScreenBrightnessBoostInProgress);
    }

    private final DisplayManagerInternal.DisplayPowerCallbacks mDisplayPowerCallbacks =
            new DisplayManagerInternal.DisplayPowerCallbacks() {

        @Override
        public void onStateChanged() {
            synchronized (mLock) {
                mDirty |= DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED;
                updatePowerStateLocked();
            }
        }

        @Override
        public void onProximityPositive() {
            synchronized (mLock) {
                mProximityPositive = true;
                mDirty |= DIRTY_PROXIMITY_POSITIVE;
                updatePowerStateLocked();
            }
        }

        @Override
        public void onProximityNegative() {
            synchronized (mLock) {
                mProximityPositive = false;
                mInterceptedPowerKeyForProximity = false;
                mDirty |= DIRTY_PROXIMITY_POSITIVE;
                userActivityNoUpdateLocked(mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP),
                        mClock.uptimeMillis(), PowerManager.USER_ACTIVITY_EVENT_OTHER,
                        /* flags= */ 0, Process.SYSTEM_UID);
                updatePowerStateLocked();
            }
        }

        @Override
        public void onDisplayStateChange(boolean allInactive, boolean allOff) {
            // This method is only needed to support legacy display blanking behavior
            // where the display's power state is coupled to suspend or to the power HAL.
            // The order of operations matters here.
            synchronized (mLock) {
                setPowerModeInternal(MODE_DISPLAY_INACTIVE, allInactive);
                if (allOff) {
                    if (!mDecoupleHalInteractiveModeFromDisplayConfig) {
                        setHalInteractiveModeLocked(false);
                    }
                    if (!mDecoupleHalAutoSuspendModeFromDisplayConfig) {
                        setHalAutoSuspendModeLocked(true);
                    }
                } else {
                    if (!mDecoupleHalAutoSuspendModeFromDisplayConfig) {
                        setHalAutoSuspendModeLocked(false);
                    }
                    if (!mDecoupleHalInteractiveModeFromDisplayConfig) {
                        setHalInteractiveModeLocked(true);
                    }
                }
            }
        }

        @Override
        public void acquireSuspendBlocker() {
            mDisplaySuspendBlocker.acquire();
        }

        @Override
        public void releaseSuspendBlocker() {
            mDisplaySuspendBlocker.release();
        }
    };

    @GuardedBy("mLock")
    private boolean shouldUseProximitySensorLocked() {
        // Use default display group for proximity sensor.
        return !mIsVrModeEnabled
                && (mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP).getWakeLockSummaryLocked()
                        & WAKE_LOCK_PROXIMITY_SCREEN_OFF) != 0;
    }

    /**
     * Updates the suspend blocker that keeps the CPU alive.
     *
     * This function must have no other side-effects.
     */
    @GuardedBy("mLock")
    private void updateSuspendBlockerLocked() {
        final boolean needWakeLockSuspendBlocker = ((mWakeLockSummary & WAKE_LOCK_CPU) != 0);
        final boolean needDisplaySuspendBlocker = needSuspendBlockerLocked();
        final boolean autoSuspend = !needDisplaySuspendBlocker;
        boolean interactive = false;
        for (int idx = 0; idx < mPowerGroups.size() && !interactive; idx++) {
            interactive = mPowerGroups.valueAt(idx).isBrightOrDimLocked();
        }

        // Disable auto-suspend if needed.
        // FIXME We should consider just leaving auto-suspend enabled forever since
        // we already hold the necessary wakelocks.
        if (!autoSuspend && mDecoupleHalAutoSuspendModeFromDisplayConfig) {
            setHalAutoSuspendModeLocked(false);
        }

        // First acquire suspend blockers if needed.
        if (!mBootCompleted && !mHoldingBootingSuspendBlocker) {
            mBootingSuspendBlocker.acquire();
            mHoldingBootingSuspendBlocker = true;
        }
        if (needWakeLockSuspendBlocker && !mHoldingWakeLockSuspendBlocker) {
            mWakeLockSuspendBlocker.acquire();
            mHoldingWakeLockSuspendBlocker = true;
        }
        if (needDisplaySuspendBlocker && !mHoldingDisplaySuspendBlocker) {
            mDisplaySuspendBlocker.acquire();
            mHoldingDisplaySuspendBlocker = true;
        }

        // Inform the power HAL about interactive mode.
        // Although we could set interactive strictly based on the wakefulness
        // as reported by isInteractive(), it is actually more desirable to track
        // the display policy state instead so that the interactive state observed
        // by the HAL more accurately tracks transitions between AWAKE and DOZING.
        // Refer to getDesiredScreenPolicyLocked() for details.
        if (mDecoupleHalInteractiveModeFromDisplayConfig) {
            // When becoming non-interactive, we want to defer sending this signal
            // until the display is actually ready so that all transitions have
            // completed.  This is probably a good sign that things have gotten
            // too tangled over here...
            if (interactive || areAllPowerGroupsReadyLocked()) {
                setHalInteractiveModeLocked(interactive);
            }
        }

        // Then release suspend blockers if needed.
        if (mBootCompleted && mHoldingBootingSuspendBlocker) {
            mBootingSuspendBlocker.release();
            mHoldingBootingSuspendBlocker = false;
        }
        if (!needWakeLockSuspendBlocker && mHoldingWakeLockSuspendBlocker) {
            mWakeLockSuspendBlocker.release();
            mHoldingWakeLockSuspendBlocker = false;
        }
        if (!needDisplaySuspendBlocker && mHoldingDisplaySuspendBlocker) {
            mDisplaySuspendBlocker.release();
            mHoldingDisplaySuspendBlocker = false;
        }

        // Enable auto-suspend if needed.
        if (autoSuspend && mDecoupleHalAutoSuspendModeFromDisplayConfig) {
            setHalAutoSuspendModeLocked(true);
        }
    }

    /**
     * Return true if we must keep a suspend blocker active on behalf of a power group.
     */
    @GuardedBy("mLock")
    private boolean needSuspendBlockerLocked() {
        if (!areAllPowerGroupsReadyLocked()) {
            return true;
        }

        if (mScreenBrightnessBoostInProgress) {
            return true;
        }

        // When we transition to DOZING, we have to keep the display suspend blocker
        // up until the Doze service has a change to acquire the DOZE wakelock.
        // Here we wait for mWakefulnessChanging to become false since the wakefulness
        // transition to DOZING isn't considered "changed" until the doze wake lock is
        // acquired.
        if (getGlobalWakefulnessLocked() == WAKEFULNESS_DOZING && mDozeStartInProgress) {
            return true;
        }

        for (int idx = 0; idx < mPowerGroups.size(); idx++) {
            final PowerGroup powerGroup = mPowerGroups.valueAt(idx);
            if (powerGroup.needSuspendBlockerLocked(mProximityPositive,
                    mSuspendWhenScreenOffDueToProximityConfig)) {
                return true;
            }
        }

        // Let the system suspend if the screen is off or dozing.
        return false;
    }

    @GuardedBy("mLock")
    private void setHalAutoSuspendModeLocked(boolean enable) {
        if (enable != mHalAutoSuspendModeEnabled) {
            if (DEBUG) {
                Slog.d(TAG, "Setting HAL auto-suspend mode to " + enable);
            }
            mHalAutoSuspendModeEnabled = enable;
            Trace.traceBegin(Trace.TRACE_TAG_POWER, "setHalAutoSuspend(" + enable + ")");
            try {
                mNativeWrapper.nativeSetAutoSuspend(enable);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            }
        }
    }

    @GuardedBy("mLock")
    private void setHalInteractiveModeLocked(boolean enable) {
        if (enable != mHalInteractiveModeEnabled) {
            if (DEBUG) {
                Slog.d(TAG, "Setting HAL interactive mode to " + enable);
            }
            mHalInteractiveModeEnabled = enable;
            Trace.traceBegin(Trace.TRACE_TAG_POWER, "setHalInteractive(" + enable + ")");
            try {
                mNativeWrapper.nativeSetPowerMode(Mode.INTERACTIVE, enable);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            }
        }
    }

    private boolean isInteractiveInternal() {
        synchronized (mLock) {
            return PowerManagerInternal.isInteractive(getGlobalWakefulnessLocked());
        }
    }

    private boolean setLowPowerModeInternal(boolean enabled) {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "setLowPowerModeInternal " + enabled + " mIsPowered=" + mIsPowered);
            }
            if (mIsPowered) {
                return false;
            }

            mBatterySaverStateMachine.setBatterySaverEnabledManually(enabled);

            return true;
        }
    }

    boolean isDeviceIdleModeInternal() {
        synchronized (mLock) {
            return mDeviceIdleMode;
        }
    }

    boolean isLightDeviceIdleModeInternal() {
        synchronized (mLock) {
            return mLightDeviceIdleMode;
        }
    }

    @GuardedBy("mLock")
    private void handleBatteryStateChangedLocked() {
        mDirty |= DIRTY_BATTERY_STATE;
        updatePowerStateLocked();
    }

    private void shutdownOrRebootInternal(final @HaltMode int haltMode, final boolean confirm,
            @Nullable final String reason, boolean wait) {
        if (PowerManager.REBOOT_USERSPACE.equals(reason)) {
            if (!PowerManager.isRebootingUserspaceSupportedImpl()) {
                throw new UnsupportedOperationException(
                        "Attempted userspace reboot on a device that doesn't support it");
            }
            UserspaceRebootLogger.noteUserspaceRebootWasRequested();
        }
        if (mHandler == null || !mSystemReady) {
            if (RescueParty.isAttemptingFactoryReset()) {
                // If we're stuck in a really low-level reboot loop, and a
                // rescue party is trying to prompt the user for a factory data
                // reset, we must GET TO DA CHOPPA!
                // No check point from ShutdownCheckPoints will be dumped at this state.
                PowerManagerService.lowLevelReboot(reason);
            } else {
                throw new IllegalStateException("Too early to call shutdown() or reboot()");
            }
        }

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                synchronized (this) {
                    if (haltMode == HALT_MODE_REBOOT_SAFE_MODE) {
                        ShutdownThread.rebootSafeMode(getUiContext(), confirm);
                    } else if (haltMode == HALT_MODE_REBOOT) {
                        ShutdownThread.reboot(getUiContext(), reason, confirm);
                    } else {
                        ShutdownThread.shutdown(getUiContext(), reason, confirm);
                    }
                }
            }
        };

        // ShutdownThread must run on a looper capable of displaying the UI.
        Message msg = Message.obtain(UiThread.getHandler(), runnable);
        msg.setAsynchronous(true);
        UiThread.getHandler().sendMessage(msg);

        // PowerManager.reboot() is documented not to return so just wait for the inevitable.
        if (wait) {
            synchronized (runnable) {
                while (true) {
                    try {
                        runnable.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }

    private void crashInternal(final String message) {
        Thread t = new Thread("PowerManagerService.crash()") {
            @Override
            public void run() {
                throw new RuntimeException(message);
            }
        };
        try {
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Slog.wtf(TAG, e);
        }
    }

    void setStayOnSettingInternal(int val) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, val);
    }

    void setMaximumScreenOffTimeoutFromDeviceAdminInternal(@UserIdInt int userId, long timeMs) {
        if (userId < 0) {
            Slog.wtf(TAG, "Attempt to set screen off timeout for invalid user: " + userId);
            return;
        }
        synchronized (mLock) {
            // System-wide timeout
            if (userId == UserHandle.USER_SYSTEM) {
                mMaximumScreenOffTimeoutFromDeviceAdmin = timeMs;
            } else if (timeMs == Long.MAX_VALUE || timeMs == 0) {
                mProfilePowerState.delete(userId);
            } else {
                final ProfilePowerState profile = mProfilePowerState.get(userId);
                if (profile != null) {
                    profile.mScreenOffTimeout = timeMs;
                } else {
                    mProfilePowerState.put(userId, new ProfilePowerState(userId, timeMs,
                            mClock.uptimeMillis()));
                    // We need to recalculate wake locks for the new profile state.
                    mDirty |= DIRTY_WAKE_LOCKS;
                }
            }
            mDirty |= DIRTY_SETTINGS;
            updatePowerStateLocked();
        }
    }

    boolean setDeviceIdleModeInternal(boolean enabled) {
        synchronized (mLock) {
            if (mDeviceIdleMode == enabled) {
                return false;
            }
            mDeviceIdleMode = enabled;
            updateWakeLockDisabledStatesLocked();
            setPowerModeInternal(MODE_DEVICE_IDLE, mDeviceIdleMode || mLightDeviceIdleMode);
        }
        if (enabled) {
            EventLogTags.writeDeviceIdleOnPhase("power");
        } else {
            EventLogTags.writeDeviceIdleOffPhase("power");
        }
        return true;
    }

    boolean setLightDeviceIdleModeInternal(boolean enabled) {
        synchronized (mLock) {
            if (mLightDeviceIdleMode != enabled) {
                mLightDeviceIdleMode = enabled;
                setPowerModeInternal(MODE_DEVICE_IDLE, mDeviceIdleMode || mLightDeviceIdleMode);
                return true;
            }
            return false;
        }
    }

    void setDeviceIdleWhitelistInternal(int[] appids) {
        synchronized (mLock) {
            mDeviceIdleWhitelist = appids;
            if (mDeviceIdleMode) {
                updateWakeLockDisabledStatesLocked();
            }
        }
    }

    void setDeviceIdleTempWhitelistInternal(int[] appids) {
        synchronized (mLock) {
            mDeviceIdleTempWhitelist = appids;
            if (mDeviceIdleMode) {
                updateWakeLockDisabledStatesLocked();
            }
        }
    }

    void setLowPowerStandbyAllowlistInternal(int[] appids) {
        synchronized (mLock) {
            mLowPowerStandbyAllowlist = appids;
            if (mLowPowerStandbyActive) {
                updateWakeLockDisabledStatesLocked();
            }
        }
    }

    void setLowPowerStandbyActiveInternal(boolean active) {
        synchronized (mLock) {
            if (mLowPowerStandbyActive != active) {
                mLowPowerStandbyActive = active;
                updateWakeLockDisabledStatesLocked();
            }
        }
    }

    void startUidChangesInternal() {
        synchronized (mLock) {
            mUidsChanging = true;
        }
    }

    void finishUidChangesInternal() {
        synchronized (mLock) {
            mUidsChanging = false;
            if (mUidsChanged) {
                updateWakeLockDisabledStatesLocked();
                mUidsChanged = false;
            }
        }
    }

    @GuardedBy("mLock")
    private void handleUidStateChangeLocked() {
        if (mUidsChanging) {
            mUidsChanged = true;
        } else {
            updateWakeLockDisabledStatesLocked();
        }
    }

    void updateUidProcStateInternal(int uid, int procState) {
        synchronized (mLock) {
            UidState state = mUidState.get(uid);
            if (state == null) {
                state = new UidState(uid);
                mUidState.put(uid, state);
            }
            final boolean oldShouldAllow = state.mProcState
                    <= ActivityManager.PROCESS_STATE_RECEIVER;
            state.mProcState = procState;
            if (state.mNumWakeLocks > 0) {
                if (mDeviceIdleMode || mLowPowerStandbyActive) {
                    handleUidStateChangeLocked();
                } else if (!state.mActive && oldShouldAllow !=
                        (procState <= ActivityManager.PROCESS_STATE_RECEIVER)) {
                    // If this uid is not active, but the process state has changed such
                    // that we may still want to allow it to hold a wake lock, then take care of it.
                    handleUidStateChangeLocked();
                }
            }
        }
    }

    void uidGoneInternal(int uid) {
        synchronized (mLock) {
            final int index = mUidState.indexOfKey(uid);
            if (index >= 0) {
                UidState state = mUidState.valueAt(index);
                state.mProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;
                state.mActive = false;
                mUidState.removeAt(index);
                if ((mDeviceIdleMode || mLowPowerStandbyActive) && state.mNumWakeLocks > 0) {
                    handleUidStateChangeLocked();
                }
            }
        }
    }

    void uidActiveInternal(int uid) {
        synchronized (mLock) {
            UidState state = mUidState.get(uid);
            if (state == null) {
                state = new UidState(uid);
                state.mProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
                mUidState.put(uid, state);
            }
            state.mActive = true;
            if (state.mNumWakeLocks > 0) {
                handleUidStateChangeLocked();
            }
        }
    }

    void uidIdleInternal(int uid) {
        synchronized (mLock) {
            UidState state = mUidState.get(uid);
            if (state != null) {
                state.mActive = false;
                if (state.mNumWakeLocks > 0) {
                    handleUidStateChangeLocked();
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void updateWakeLockDisabledStatesLocked() {
        boolean changed = false;
        final int numWakeLocks = mWakeLocks.size();
        for (int i = 0; i < numWakeLocks; i++) {
            final WakeLock wakeLock = mWakeLocks.get(i);
            if ((wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK)
                    == PowerManager.PARTIAL_WAKE_LOCK) {
                if (setWakeLockDisabledStateLocked(wakeLock)) {
                    changed = true;
                    if (wakeLock.mDisabled) {
                        // This wake lock is no longer being respected.
                        notifyWakeLockReleasedLocked(wakeLock);
                    } else {
                        notifyWakeLockAcquiredLocked(wakeLock);
                    }
                }
            }
        }
        if (changed) {
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    @GuardedBy("mLock")
    private boolean setWakeLockDisabledStateLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK)
                == PowerManager.PARTIAL_WAKE_LOCK) {
            boolean disabled = false;
            final int appid = UserHandle.getAppId(wakeLock.mOwnerUid);
            if (appid >= Process.FIRST_APPLICATION_UID) {
                // Cached inactive processes are never allowed to hold wake locks.
                if (mConstants.NO_CACHED_WAKE_LOCKS) {
                    disabled = mForceSuspendActive
                            || (!wakeLock.mUidState.mActive && wakeLock.mUidState.mProcState
                                    != ActivityManager.PROCESS_STATE_NONEXISTENT &&
                            wakeLock.mUidState.mProcState > ActivityManager.PROCESS_STATE_RECEIVER);
                }
                if (mDeviceIdleMode) {
                    // If we are in idle mode, we will also ignore all partial wake locks that are
                    // for application uids that are not allowlisted.
                    final UidState state = wakeLock.mUidState;
                    if (Arrays.binarySearch(mDeviceIdleWhitelist, appid) < 0 &&
                            Arrays.binarySearch(mDeviceIdleTempWhitelist, appid) < 0 &&
                            state.mProcState != ActivityManager.PROCESS_STATE_NONEXISTENT &&
                            state.mProcState >
                                    ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
                        disabled = true;
                    }
                }
                if (mLowPowerStandbyActive) {
                    final UidState state = wakeLock.mUidState;
                    if (Arrays.binarySearch(mLowPowerStandbyAllowlist, appid) < 0
                            && state.mProcState != ActivityManager.PROCESS_STATE_NONEXISTENT
                            && state.mProcState > ActivityManager.PROCESS_STATE_BOUND_TOP) {
                        disabled = true;
                    }
                }
            }
            return wakeLock.setDisabled(disabled);
        }
        return false;
    }

    @GuardedBy("mLock")
    private boolean isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() {
        return mMaximumScreenOffTimeoutFromDeviceAdmin >= 0
                && mMaximumScreenOffTimeoutFromDeviceAdmin < Long.MAX_VALUE;
    }

    private void setAttentionLightInternal(boolean on, int color) {
        LogicalLight light;
        synchronized (mLock) {
            if (!mSystemReady) {
                return;
            }
            light = mAttentionLight;
        }

        // Control light outside of lock.
        if (light != null) {
            light.setFlashing(color, LogicalLight.LIGHT_FLASH_HARDWARE, (on ? 3 : 0), 0);
        }
    }

    private void setDozeAfterScreenOffInternal(boolean on) {
        synchronized (mLock) {
            mDozeAfterScreenOff = on;
        }
    }

    private void boostScreenBrightnessInternal(long eventTime, int uid) {
        synchronized (mLock) {
            if (!mSystemReady || getGlobalWakefulnessLocked() == WAKEFULNESS_ASLEEP
                    || eventTime < mLastScreenBrightnessBoostTime) {
                return;
            }

            Slog.i(TAG, "Brightness boost activated (uid " + uid +")...");
            mLastScreenBrightnessBoostTime = eventTime;
            mScreenBrightnessBoostInProgress = true;
            mDirty |= DIRTY_SCREEN_BRIGHTNESS_BOOST;

            userActivityNoUpdateLocked(mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP), eventTime,
                    PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, uid);
            updatePowerStateLocked();
        }
    }

    private boolean isScreenBrightnessBoostedInternal() {
        synchronized (mLock) {
            return mScreenBrightnessBoostInProgress;
        }
    }

    /**
     * Called when a screen brightness boost timeout has occurred.
     *
     * This function must have no other side-effects besides setting the dirty
     * bit and calling update power state.
     */
    private void handleScreenBrightnessBoostTimeout() { // runs on handler thread
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleScreenBrightnessBoostTimeout");
            }

            mDirty |= DIRTY_SCREEN_BRIGHTNESS_BOOST;
            updatePowerStateLocked();
        }
    }

    private void setScreenBrightnessOverrideFromWindowManagerInternal(float brightness) {
        synchronized (mLock) {
            if (!BrightnessSynchronizer.floatEquals(mScreenBrightnessOverrideFromWindowManager,
                    brightness)) {
                mScreenBrightnessOverrideFromWindowManager = brightness;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    private void setUserInactiveOverrideFromWindowManagerInternal() {
        synchronized (mLock) {
            mUserInactiveOverrideFromWindowManager = true;
            mDirty |= DIRTY_USER_ACTIVITY;
            updatePowerStateLocked();
        }
    }

    private void setUserActivityTimeoutOverrideFromWindowManagerInternal(long timeoutMillis) {
        synchronized (mLock) {
            if (mUserActivityTimeoutOverrideFromWindowManager != timeoutMillis) {
                mUserActivityTimeoutOverrideFromWindowManager = timeoutMillis;
                EventLogTags.writeUserActivityTimeoutOverride(timeoutMillis);
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    private void setDozeOverrideFromDreamManagerInternal(
            int screenState, int screenBrightness) {
        synchronized (mLock) {
            if (mDozeScreenStateOverrideFromDreamManager != screenState
                    || mDozeScreenBrightnessOverrideFromDreamManager != screenBrightness) {
                mDozeScreenStateOverrideFromDreamManager = screenState;
                mDozeScreenBrightnessOverrideFromDreamManager = screenBrightness;
                mDozeScreenBrightnessOverrideFromDreamManagerFloat =
                        BrightnessSynchronizer.brightnessIntToFloat(mDozeScreenBrightnessOverrideFromDreamManager);
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    private void setDrawWakeLockOverrideFromSidekickInternal(boolean keepState) {
        synchronized (mLock) {
            if (mDrawWakeLockOverrideFromSidekick != keepState) {
                mDrawWakeLockOverrideFromSidekick = keepState;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    @VisibleForTesting
    void setVrModeEnabled(boolean enabled) {
        mIsVrModeEnabled = enabled;
    }

    private void setPowerBoostInternal(int boost, int durationMs) {
        // Maybe filter the event.
        mNativeWrapper.nativeSetPowerBoost(boost, durationMs);
    }

    private boolean setPowerModeInternal(int mode, boolean enabled) {
        // Maybe filter the event.
        if (mode == Mode.LAUNCH && enabled && mBatterySaverController.isLaunchBoostDisabled()) {
            return false;
        }
        return mNativeWrapper.nativeSetPowerMode(mode, enabled);
    }

    @VisibleForTesting
    boolean wasDeviceIdleForInternal(long ms) {
        synchronized (mLock) {
            return mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP).getLastUserActivityTimeLocked()
                    + ms < mClock.uptimeMillis();
        }
    }

    @VisibleForTesting
    void onUserActivity() {
        synchronized (mLock) {
            mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP).setLastUserActivityTimeLocked(
                    mClock.uptimeMillis());
        }
    }

    private boolean forceSuspendInternal(int uid) {
        try {
            synchronized (mLock) {
                mForceSuspendActive = true;
                // Place the system in an non-interactive state
                for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                    sleepPowerGroupLocked(mPowerGroups.valueAt(idx), mClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_FORCE_SUSPEND, uid);
                }

                // Disable all the partial wake locks as well
                updateWakeLockDisabledStatesLocked();
            }

            Slog.i(TAG, "Force-Suspending (uid " + uid + ")...");
            boolean success = mNativeWrapper.nativeForceSuspend();
            if (!success) {
                Slog.i(TAG, "Force-Suspending failed in native.");
            }
            return success;
        } finally {
            synchronized (mLock) {
                mForceSuspendActive = false;
                // Re-enable wake locks once again.
                updateWakeLockDisabledStatesLocked();
            }
        }
    }

    /**
     * Low-level function turn the device off immediately, without trying
     * to be clean.  Most people should use {@link ShutdownThread} for a clean shutdown.
     *
     * @param reason code to pass to android_reboot() (e.g. "userrequested"), or null.
     */
    public static void lowLevelShutdown(String reason) {
        if (reason == null) {
            reason = "";
        }
        SystemProperties.set("sys.powerctl", "shutdown," + reason);
    }

    /**
     * Low-level function to reboot the device. On success, this
     * function doesn't return. If more than 20 seconds passes from
     * the time a reboot is requested, this method returns.
     *
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     */
    public static void lowLevelReboot(String reason) {
        if (reason == null) {
            reason = "";
        }

        // If the reason is "quiescent", it means that the boot process should proceed
        // without turning on the screen/lights.
        // The "quiescent" property is sticky, meaning that any number
        // of subsequent reboots should honor the property until it is reset.
        if (reason.equals(PowerManager.REBOOT_QUIESCENT)) {
            sQuiescent = true;
            reason = "";
        } else if (reason.endsWith("," + PowerManager.REBOOT_QUIESCENT)) {
            sQuiescent = true;
            reason = reason.substring(0,
                    reason.length() - PowerManager.REBOOT_QUIESCENT.length() - 1);
        }

        if (reason.equals(PowerManager.REBOOT_RECOVERY)
                || reason.equals(PowerManager.REBOOT_RECOVERY_UPDATE)) {
            reason = "recovery";
        }

        if (sQuiescent) {
            // Pass the optional "quiescent" argument to the bootloader to let it know
            // that it should not turn the screen/lights on.
            if (!"".equals(reason)) {
                reason += ",";
            }
            reason = reason + "quiescent";
        }

        SystemProperties.set("sys.powerctl", "reboot," + reason);
        try {
            Thread.sleep(20 * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Slog.wtf(TAG, "Unexpected return from lowLevelReboot!");
    }

    @Override // Watchdog.Monitor implementation
    public void monitor() {
        // Grab and release lock for watchdog monitor to detect deadlocks.
        synchronized (mLock) {
        }
    }

    @NeverCompile // Avoid size overhead of debugging code.
    private void dumpInternal(PrintWriter pw) {
        pw.println("POWER MANAGER (dumpsys power)\n");

        final WirelessChargerDetector wcd;
        final LowPowerStandbyController lowPowerStandbyController;
        synchronized (mLock) {
            pw.println("Power Manager State:");
            mConstants.dump(pw);
            pw.println("  mDirty=0x" + Integer.toHexString(mDirty));
            pw.println("  mWakefulness="
                    + PowerManagerInternal.wakefulnessToString(getGlobalWakefulnessLocked()));
            pw.println("  mWakefulnessChanging=" + mWakefulnessChanging);
            pw.println("  mIsPowered=" + mIsPowered);
            pw.println("  mPlugType=" + mPlugType);
            pw.println("  mBatteryLevel=" + mBatteryLevel);
            pw.println("  mBatteryLevelWhenDreamStarted=" + mBatteryLevelWhenDreamStarted);
            pw.println("  mDockState=" + mDockState);
            pw.println("  mStayOn=" + mStayOn);
            pw.println("  mProximityPositive=" + mProximityPositive);
            pw.println("  mBootCompleted=" + mBootCompleted);
            pw.println("  mSystemReady=" + mSystemReady);
            synchronized (mEnhancedDischargeTimeLock) {
                pw.println("  mEnhancedDischargeTimeElapsed=" + mEnhancedDischargeTimeElapsed);
                pw.println("  mLastEnhancedDischargeTimeUpdatedElapsed="
                        + mLastEnhancedDischargeTimeUpdatedElapsed);
                pw.println("  mEnhancedDischargePredictionIsPersonalized="
                        + mEnhancedDischargePredictionIsPersonalized);
            }
            pw.println("  mHalAutoSuspendModeEnabled=" + mHalAutoSuspendModeEnabled);
            pw.println("  mHalInteractiveModeEnabled=" + mHalInteractiveModeEnabled);
            pw.println("  mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            pw.print("  mNotifyLongScheduled=");
            if (mNotifyLongScheduled == 0) {
                pw.print("(none)");
            } else {
                TimeUtils.formatDuration(mNotifyLongScheduled, mClock.uptimeMillis(), pw);
            }
            pw.println();
            pw.print("  mNotifyLongDispatched=");
            if (mNotifyLongDispatched == 0) {
                pw.print("(none)");
            } else {
                TimeUtils.formatDuration(mNotifyLongDispatched, mClock.uptimeMillis(), pw);
            }
            pw.println();
            pw.print("  mNotifyLongNextCheck=");
            if (mNotifyLongNextCheck == 0) {
                pw.print("(none)");
            } else {
                TimeUtils.formatDuration(mNotifyLongNextCheck, mClock.uptimeMillis(), pw);
            }
            pw.println();
            pw.println("  mRequestWaitForNegativeProximity=" + mRequestWaitForNegativeProximity);
            pw.println("  mInterceptedPowerKeyForProximity="
                    + mInterceptedPowerKeyForProximity);
            pw.println("  mSandmanScheduled=" + mSandmanScheduled);
            pw.println("  mBatteryLevelLow=" + mBatteryLevelLow);
            pw.println("  mLightDeviceIdleMode=" + mLightDeviceIdleMode);
            pw.println("  mDeviceIdleMode=" + mDeviceIdleMode);
            pw.println("  mDeviceIdleWhitelist=" + Arrays.toString(mDeviceIdleWhitelist));
            pw.println("  mDeviceIdleTempWhitelist=" + Arrays.toString(mDeviceIdleTempWhitelist));
            pw.println("  mLowPowerStandbyActive=" + mLowPowerStandbyActive);
            pw.println("  mLastWakeTime=" + TimeUtils.formatUptime(mLastGlobalWakeTime));
            pw.println("  mLastSleepTime=" + TimeUtils.formatUptime(mLastGlobalSleepTime));
            pw.println("  mLastSleepReason=" + PowerManager.sleepReasonToString(
                    mLastGlobalSleepReason));
            pw.println("  mLastInteractivePowerHintTime="
                    + TimeUtils.formatUptime(mLastInteractivePowerHintTime));
            pw.println("  mLastScreenBrightnessBoostTime="
                    + TimeUtils.formatUptime(mLastScreenBrightnessBoostTime));
            pw.println("  mScreenBrightnessBoostInProgress="
                    + mScreenBrightnessBoostInProgress);
            pw.println("  mHoldingWakeLockSuspendBlocker=" + mHoldingWakeLockSuspendBlocker);
            pw.println("  mHoldingDisplaySuspendBlocker=" + mHoldingDisplaySuspendBlocker);
            pw.println("  mLastFlipTime=" + mLastFlipTime);
            pw.println("  mIsFaceDown=" + mIsFaceDown);

            pw.println();
            pw.println("Settings and Configuration:");
            pw.println("  mDecoupleHalAutoSuspendModeFromDisplayConfig="
                    + mDecoupleHalAutoSuspendModeFromDisplayConfig);
            pw.println("  mDecoupleHalInteractiveModeFromDisplayConfig="
                    + mDecoupleHalInteractiveModeFromDisplayConfig);
            pw.println("  mWakeUpWhenPluggedOrUnpluggedConfig="
                    + mWakeUpWhenPluggedOrUnpluggedConfig);
            pw.println("  mWakeUpWhenPluggedOrUnpluggedInTheaterModeConfig="
                    + mWakeUpWhenPluggedOrUnpluggedInTheaterModeConfig);
            pw.println("  mTheaterModeEnabled="
                    + mTheaterModeEnabled);
            pw.println("  mSuspendWhenScreenOffDueToProximityConfig="
                    + mSuspendWhenScreenOffDueToProximityConfig);
            pw.println("  mDreamsSupportedConfig=" + mDreamsSupportedConfig);
            pw.println("  mDreamsEnabledByDefaultConfig=" + mDreamsEnabledByDefaultConfig);
            pw.println("  mDreamsActivatedOnSleepByDefaultConfig="
                    + mDreamsActivatedOnSleepByDefaultConfig);
            pw.println("  mDreamsActivatedOnDockByDefaultConfig="
                    + mDreamsActivatedOnDockByDefaultConfig);
            pw.println("  mDreamsEnabledOnBatteryConfig="
                    + mDreamsEnabledOnBatteryConfig);
            pw.println("  mDreamsBatteryLevelMinimumWhenPoweredConfig="
                    + mDreamsBatteryLevelMinimumWhenPoweredConfig);
            pw.println("  mDreamsBatteryLevelMinimumWhenNotPoweredConfig="
                    + mDreamsBatteryLevelMinimumWhenNotPoweredConfig);
            pw.println("  mDreamsBatteryLevelDrainCutoffConfig="
                    + mDreamsBatteryLevelDrainCutoffConfig);
            pw.println("  mDreamsEnabledSetting=" + mDreamsEnabledSetting);
            pw.println("  mDreamsActivateOnSleepSetting=" + mDreamsActivateOnSleepSetting);
            pw.println("  mDreamsActivateOnDockSetting=" + mDreamsActivateOnDockSetting);
            pw.println("  mDozeAfterScreenOff=" + mDozeAfterScreenOff);
            pw.println("  mMinimumScreenOffTimeoutConfig=" + mMinimumScreenOffTimeoutConfig);
            pw.println("  mMaximumScreenDimDurationConfig=" + mMaximumScreenDimDurationConfig);
            pw.println("  mMaximumScreenDimRatioConfig=" + mMaximumScreenDimRatioConfig);
            pw.println("  mAttentiveTimeoutConfig=" + mAttentiveTimeoutConfig);
            pw.println("  mAttentiveTimeoutSetting=" + mAttentiveTimeoutSetting);
            pw.println("  mAttentiveWarningDurationConfig=" + mAttentiveWarningDurationConfig);
            pw.println("  mScreenOffTimeoutSetting=" + mScreenOffTimeoutSetting);
            pw.println("  mSleepTimeoutSetting=" + mSleepTimeoutSetting);
            pw.println("  mMaximumScreenOffTimeoutFromDeviceAdmin="
                    + mMaximumScreenOffTimeoutFromDeviceAdmin + " (enforced="
                    + isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() + ")");
            pw.println("  mStayOnWhilePluggedInSetting=" + mStayOnWhilePluggedInSetting);
            pw.println("  mScreenBrightnessModeSetting=" + mScreenBrightnessModeSetting);
            pw.println("  mScreenBrightnessOverrideFromWindowManager="
                    + mScreenBrightnessOverrideFromWindowManager);
            pw.println("  mUserActivityTimeoutOverrideFromWindowManager="
                    + mUserActivityTimeoutOverrideFromWindowManager);
            pw.println("  mUserInactiveOverrideFromWindowManager="
                    + mUserInactiveOverrideFromWindowManager);
            pw.println("  mDozeScreenStateOverrideFromDreamManager="
                    + mDozeScreenStateOverrideFromDreamManager);
            pw.println("  mDrawWakeLockOverrideFromSidekick=" + mDrawWakeLockOverrideFromSidekick);
            pw.println("  mDozeScreenBrightnessOverrideFromDreamManager="
                    + mDozeScreenBrightnessOverrideFromDreamManager);
            pw.println("  mScreenBrightnessMinimum=" + mScreenBrightnessMinimum);
            pw.println("  mScreenBrightnessMaximum=" + mScreenBrightnessMaximum);
            pw.println("  mScreenBrightnessDefault=" + mScreenBrightnessDefault);
            pw.println("  mDoubleTapWakeEnabled=" + mDoubleTapWakeEnabled);
            pw.println("  mIsVrModeEnabled=" + mIsVrModeEnabled);
            pw.println("  mForegroundProfile=" + mForegroundProfile);
            pw.println("  mUserId=" + mUserId);

            final long attentiveTimeout = getAttentiveTimeoutLocked();
            final long sleepTimeout = getSleepTimeoutLocked(attentiveTimeout);
            final long screenOffTimeout = getScreenOffTimeoutLocked(sleepTimeout, attentiveTimeout);
            final long screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
            pw.println();
            pw.println("Attentive timeout: " + attentiveTimeout + " ms");
            pw.println("Sleep timeout: " + sleepTimeout + " ms");
            pw.println("Screen off timeout: " + screenOffTimeout + " ms");
            pw.println("Screen dim duration: " + screenDimDuration + " ms");

            pw.println();
            pw.print("UID states (changing=");
            pw.print(mUidsChanging);
            pw.print(" changed=");
            pw.print(mUidsChanged);
            pw.println("):");
            for (int i=0; i<mUidState.size(); i++) {
                final UidState state = mUidState.valueAt(i);
                pw.print("  UID "); UserHandle.formatUid(pw, mUidState.keyAt(i));
                pw.print(": ");
                if (state.mActive) pw.print("  ACTIVE ");
                else pw.print("INACTIVE ");
                pw.print(" count=");
                pw.print(state.mNumWakeLocks);
                pw.print(" state=");
                pw.println(state.mProcState);
            }

            pw.println();
            pw.println("Looper state:");
            mHandler.getLooper().dump(new PrintWriterPrinter(pw), "  ");

            pw.println();
            pw.println("Wake Locks: size=" + mWakeLocks.size());
            for (WakeLock wl : mWakeLocks) {
                pw.println("  " + wl);
            }

            pw.println();
            pw.println("Suspend Blockers: size=" + mSuspendBlockers.size());
            for (SuspendBlocker sb : mSuspendBlockers) {
                pw.println("  " + sb);
            }

            pw.println();
            pw.println("Display Power: " + mDisplayPowerCallbacks);

            mBatterySaverPolicy.dump(pw);
            mBatterySaverStateMachine.dump(pw);
            mAttentionDetector.dump(pw);

            pw.println();
            final int numProfiles = mProfilePowerState.size();
            pw.println("Profile power states: size=" + numProfiles);
            for (int i = 0; i < numProfiles; i++) {
                final ProfilePowerState profile = mProfilePowerState.valueAt(i);
                pw.print("  mUserId=");
                pw.print(profile.mUserId);
                pw.print(" mScreenOffTimeout=");
                pw.print(profile.mScreenOffTimeout);
                pw.print(" mWakeLockSummary=");
                pw.print(profile.mWakeLockSummary);
                pw.print(" mLastUserActivityTime=");
                pw.print(profile.mLastUserActivityTime);
                pw.print(" mLockingNotified=");
                pw.println(profile.mLockingNotified);
            }

            pw.println("Display Group User Activity:");
            for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                final PowerGroup powerGroup = mPowerGroups.valueAt(idx);
                pw.println("  displayGroupId=" + powerGroup.getGroupId());
                pw.println("  userActivitySummary=0x" + Integer.toHexString(
                        powerGroup.getUserActivitySummaryLocked()));
                pw.println("  lastUserActivityTime=" + TimeUtils.formatUptime(
                        powerGroup.getLastUserActivityTimeLocked()));
                pw.println("  lastUserActivityTimeNoChangeLights=" + TimeUtils.formatUptime(
                        powerGroup.getLastUserActivityTimeNoChangeLightsLocked()));
                pw.println("  mWakeLockSummary=0x" + Integer.toHexString(
                        powerGroup.getWakeLockSummaryLocked()));
            }

            wcd = mWirelessChargerDetector;
        }

        if (wcd != null) {
            wcd.dump(pw);
        }

        if (mNotifier != null) {
            mNotifier.dump(pw);
        }

        mFaceDownDetector.dump(pw);

        mAmbientDisplaySuppressionController.dump(pw);

        mLowPowerStandbyController.dump(pw);
    }

    private void dumpProto(FileDescriptor fd) {
        final WirelessChargerDetector wcd;
        final LowPowerStandbyController lowPowerStandbyController;
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            mConstants.dumpProto(proto);
            proto.write(PowerManagerServiceDumpProto.DIRTY, mDirty);
            proto.write(PowerManagerServiceDumpProto.WAKEFULNESS, getGlobalWakefulnessLocked());
            proto.write(PowerManagerServiceDumpProto.IS_WAKEFULNESS_CHANGING, mWakefulnessChanging);
            proto.write(PowerManagerServiceDumpProto.IS_POWERED, mIsPowered);
            proto.write(PowerManagerServiceDumpProto.PLUG_TYPE, mPlugType);
            proto.write(PowerManagerServiceDumpProto.BATTERY_LEVEL, mBatteryLevel);
            proto.write(
                    PowerManagerServiceDumpProto.BATTERY_LEVEL_WHEN_DREAM_STARTED,
                    mBatteryLevelWhenDreamStarted);
            proto.write(PowerManagerServiceDumpProto.DOCK_STATE, mDockState);
            proto.write(PowerManagerServiceDumpProto.IS_STAY_ON, mStayOn);
            proto.write(PowerManagerServiceDumpProto.IS_PROXIMITY_POSITIVE, mProximityPositive);
            proto.write(PowerManagerServiceDumpProto.IS_BOOT_COMPLETED, mBootCompleted);
            proto.write(PowerManagerServiceDumpProto.IS_SYSTEM_READY, mSystemReady);
            synchronized (mEnhancedDischargeTimeLock) {
                proto.write(PowerManagerServiceDumpProto.ENHANCED_DISCHARGE_TIME_ELAPSED,
                        mEnhancedDischargeTimeElapsed);
                proto.write(
                        PowerManagerServiceDumpProto.LAST_ENHANCED_DISCHARGE_TIME_UPDATED_ELAPSED,
                        mLastEnhancedDischargeTimeUpdatedElapsed);
                proto.write(
                        PowerManagerServiceDumpProto.IS_ENHANCED_DISCHARGE_PREDICTION_PERSONALIZED,
                        mEnhancedDischargePredictionIsPersonalized);
            }
            proto.write(
                    PowerManagerServiceDumpProto.IS_HAL_AUTO_SUSPEND_MODE_ENABLED,
                    mHalAutoSuspendModeEnabled);
            proto.write(
                    PowerManagerServiceDumpProto.IS_HAL_AUTO_INTERACTIVE_MODE_ENABLED,
                    mHalInteractiveModeEnabled);

            final long activeWakeLocksToken = proto.start(
                    PowerManagerServiceDumpProto.ACTIVE_WAKE_LOCKS);
            proto.write(
                    PowerManagerServiceDumpProto.ActiveWakeLocksProto.IS_CPU,
                    (mWakeLockSummary & WAKE_LOCK_CPU) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.ActiveWakeLocksProto.IS_SCREEN_BRIGHT,
                    (mWakeLockSummary & WAKE_LOCK_SCREEN_BRIGHT) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.ActiveWakeLocksProto.IS_SCREEN_DIM,
                    (mWakeLockSummary & WAKE_LOCK_SCREEN_DIM) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.ActiveWakeLocksProto.IS_BUTTON_BRIGHT,
                    (mWakeLockSummary & WAKE_LOCK_BUTTON_BRIGHT) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.ActiveWakeLocksProto.IS_PROXIMITY_SCREEN_OFF,
                    (mWakeLockSummary & WAKE_LOCK_PROXIMITY_SCREEN_OFF) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.ActiveWakeLocksProto.IS_STAY_AWAKE,
                    (mWakeLockSummary & WAKE_LOCK_STAY_AWAKE) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.ActiveWakeLocksProto.IS_DOZE,
                    (mWakeLockSummary & WAKE_LOCK_DOZE) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.ActiveWakeLocksProto.IS_DRAW,
                    (mWakeLockSummary & WAKE_LOCK_DRAW) != 0);
            proto.end(activeWakeLocksToken);

            proto.write(PowerManagerServiceDumpProto.NOTIFY_LONG_SCHEDULED_MS, mNotifyLongScheduled);
            proto.write(PowerManagerServiceDumpProto.NOTIFY_LONG_DISPATCHED_MS, mNotifyLongDispatched);
            proto.write(PowerManagerServiceDumpProto.NOTIFY_LONG_NEXT_CHECK_MS, mNotifyLongNextCheck);

            for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                final PowerGroup powerGroup = mPowerGroups.valueAt(idx);
                final long userActivityToken = proto.start(
                        PowerManagerServiceDumpProto.USER_ACTIVITY);
                proto.write(PowerManagerServiceDumpProto.UserActivityProto.DISPLAY_GROUP_ID,
                        powerGroup.getGroupId());
                final long userActivitySummary = powerGroup.getUserActivitySummaryLocked();
                proto.write(PowerManagerServiceDumpProto.UserActivityProto.IS_SCREEN_BRIGHT,
                        (userActivitySummary & USER_ACTIVITY_SCREEN_BRIGHT) != 0);
                proto.write(PowerManagerServiceDumpProto.UserActivityProto.IS_SCREEN_DIM,
                        (userActivitySummary & USER_ACTIVITY_SCREEN_DIM) != 0);
                proto.write(PowerManagerServiceDumpProto.UserActivityProto.IS_SCREEN_DREAM,
                        (userActivitySummary & USER_ACTIVITY_SCREEN_DREAM) != 0);
                proto.write(
                        PowerManagerServiceDumpProto.UserActivityProto.LAST_USER_ACTIVITY_TIME_MS,
                        powerGroup.getLastUserActivityTimeLocked());
                proto.write(
                        PowerManagerServiceDumpProto.UserActivityProto.LAST_USER_ACTIVITY_TIME_NO_CHANGE_LIGHTS_MS,
                        powerGroup.getLastUserActivityTimeNoChangeLightsLocked());
                proto.end(userActivityToken);
            }

            proto.write(
                    PowerManagerServiceDumpProto.IS_REQUEST_WAIT_FOR_NEGATIVE_PROXIMITY,
                    mRequestWaitForNegativeProximity);
            proto.write(PowerManagerServiceDumpProto.IS_SANDMAN_SCHEDULED, mSandmanScheduled);
            proto.write(PowerManagerServiceDumpProto.IS_BATTERY_LEVEL_LOW, mBatteryLevelLow);
            proto.write(PowerManagerServiceDumpProto.IS_LIGHT_DEVICE_IDLE_MODE, mLightDeviceIdleMode);
            proto.write(PowerManagerServiceDumpProto.IS_DEVICE_IDLE_MODE, mDeviceIdleMode);

            for (int id : mDeviceIdleWhitelist) {
                proto.write(PowerManagerServiceDumpProto.DEVICE_IDLE_WHITELIST, id);
            }
            for (int id : mDeviceIdleTempWhitelist) {
                proto.write(PowerManagerServiceDumpProto.DEVICE_IDLE_TEMP_WHITELIST, id);
            }

            proto.write(PowerManagerServiceDumpProto.IS_LOW_POWER_STANDBY_ACTIVE,
                    mLowPowerStandbyActive);

            proto.write(PowerManagerServiceDumpProto.LAST_WAKE_TIME_MS, mLastGlobalWakeTime);
            proto.write(PowerManagerServiceDumpProto.LAST_SLEEP_TIME_MS, mLastGlobalSleepTime);
            proto.write(
                    PowerManagerServiceDumpProto.LAST_INTERACTIVE_POWER_HINT_TIME_MS,
                    mLastInteractivePowerHintTime);
            proto.write(
                    PowerManagerServiceDumpProto.LAST_SCREEN_BRIGHTNESS_BOOST_TIME_MS,
                    mLastScreenBrightnessBoostTime);
            proto.write(
                    PowerManagerServiceDumpProto.IS_SCREEN_BRIGHTNESS_BOOST_IN_PROGRESS,
                    mScreenBrightnessBoostInProgress);
            proto.write(
                    PowerManagerServiceDumpProto.IS_HOLDING_WAKE_LOCK_SUSPEND_BLOCKER,
                    mHoldingWakeLockSuspendBlocker);
            proto.write(
                    PowerManagerServiceDumpProto.IS_HOLDING_DISPLAY_SUSPEND_BLOCKER,
                    mHoldingDisplaySuspendBlocker);

            final long settingsAndConfigurationToken =
                    proto.start(PowerManagerServiceDumpProto.SETTINGS_AND_CONFIGURATION);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .IS_DECOUPLE_HAL_AUTO_SUSPEND_MODE_FROM_DISPLAY_CONFIG,
                    mDecoupleHalAutoSuspendModeFromDisplayConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .IS_DECOUPLE_HAL_INTERACTIVE_MODE_FROM_DISPLAY_CONFIG,
                    mDecoupleHalInteractiveModeFromDisplayConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .IS_WAKE_UP_WHEN_PLUGGED_OR_UNPLUGGED_CONFIG,
                    mWakeUpWhenPluggedOrUnpluggedConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .IS_WAKE_UP_WHEN_PLUGGED_OR_UNPLUGGED_IN_THEATER_MODE_CONFIG,
                    mWakeUpWhenPluggedOrUnpluggedInTheaterModeConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.IS_THEATER_MODE_ENABLED,
                    mTheaterModeEnabled);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .IS_SUSPEND_WHEN_SCREEN_OFF_DUE_TO_PROXIMITY_CONFIG,
                    mSuspendWhenScreenOffDueToProximityConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ARE_DREAMS_SUPPORTED_CONFIG,
                    mDreamsSupportedConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .ARE_DREAMS_ENABLED_BY_DEFAULT_CONFIG,
                    mDreamsEnabledByDefaultConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .ARE_DREAMS_ACTIVATED_ON_SLEEP_BY_DEFAULT_CONFIG,
                    mDreamsActivatedOnSleepByDefaultConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .ARE_DREAMS_ACTIVATED_ON_DOCK_BY_DEFAULT_CONFIG,
                    mDreamsActivatedOnDockByDefaultConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .ARE_DREAMS_ENABLED_ON_BATTERY_CONFIG,
                    mDreamsEnabledOnBatteryConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .DREAMS_BATTERY_LEVEL_MINIMUM_WHEN_POWERED_CONFIG,
                    mDreamsBatteryLevelMinimumWhenPoweredConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .DREAMS_BATTERY_LEVEL_MINIMUM_WHEN_NOT_POWERED_CONFIG,
                    mDreamsBatteryLevelMinimumWhenNotPoweredConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .DREAMS_BATTERY_LEVEL_DRAIN_CUTOFF_CONFIG,
                    mDreamsBatteryLevelDrainCutoffConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ARE_DREAMS_ENABLED_SETTING,
                    mDreamsEnabledSetting);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .ARE_DREAMS_ACTIVATE_ON_SLEEP_SETTING,
                    mDreamsActivateOnSleepSetting);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .ARE_DREAMS_ACTIVATE_ON_DOCK_SETTING,
                    mDreamsActivateOnDockSetting);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.IS_DOZE_AFTER_SCREEN_OFF_CONFIG,
                    mDozeAfterScreenOff);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .MINIMUM_SCREEN_OFF_TIMEOUT_CONFIG_MS,
                    mMinimumScreenOffTimeoutConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .MAXIMUM_SCREEN_DIM_DURATION_CONFIG_MS,
                    mMaximumScreenDimDurationConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.MAXIMUM_SCREEN_DIM_RATIO_CONFIG,
                    mMaximumScreenDimRatioConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.SCREEN_OFF_TIMEOUT_SETTING_MS,
                    mScreenOffTimeoutSetting);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.SLEEP_TIMEOUT_SETTING_MS,
                    mSleepTimeoutSetting);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ATTENTIVE_TIMEOUT_SETTING_MS,
                    mAttentiveTimeoutSetting);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ATTENTIVE_TIMEOUT_CONFIG_MS,
                    mAttentiveTimeoutConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .ATTENTIVE_WARNING_DURATION_CONFIG_MS,
                    mAttentiveWarningDurationConfig);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .MAXIMUM_SCREEN_OFF_TIMEOUT_FROM_DEVICE_ADMIN_MS,
                    // Clamp to int32
                    Math.min(mMaximumScreenOffTimeoutFromDeviceAdmin, Integer.MAX_VALUE));
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .IS_MAXIMUM_SCREEN_OFF_TIMEOUT_FROM_DEVICE_ADMIN_ENFORCED_LOCKED,
                    isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked());

            final long stayOnWhilePluggedInToken =
                    proto.start(
                            PowerServiceSettingsAndConfigurationDumpProto.STAY_ON_WHILE_PLUGGED_IN);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.StayOnWhilePluggedInProto
                            .IS_STAY_ON_WHILE_PLUGGED_IN_AC,
                    ((mStayOnWhilePluggedInSetting & BatteryManager.BATTERY_PLUGGED_AC) != 0));
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.StayOnWhilePluggedInProto
                            .IS_STAY_ON_WHILE_PLUGGED_IN_USB,
                    ((mStayOnWhilePluggedInSetting & BatteryManager.BATTERY_PLUGGED_USB) != 0));
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.StayOnWhilePluggedInProto
                            .IS_STAY_ON_WHILE_PLUGGED_IN_WIRELESS,
                    ((mStayOnWhilePluggedInSetting & BatteryManager.BATTERY_PLUGGED_WIRELESS)
                            != 0));
            proto.end(stayOnWhilePluggedInToken);

            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.SCREEN_BRIGHTNESS_MODE_SETTING,
                    mScreenBrightnessModeSetting);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .SCREEN_BRIGHTNESS_OVERRIDE_FROM_WINDOW_MANAGER,
                    mScreenBrightnessOverrideFromWindowManager);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .USER_ACTIVITY_TIMEOUT_OVERRIDE_FROM_WINDOW_MANAGER_MS,
                    mUserActivityTimeoutOverrideFromWindowManager);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .IS_USER_INACTIVE_OVERRIDE_FROM_WINDOW_MANAGER,
                    mUserInactiveOverrideFromWindowManager);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .DOZE_SCREEN_STATE_OVERRIDE_FROM_DREAM_MANAGER,
                    mDozeScreenStateOverrideFromDreamManager);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .DRAW_WAKE_LOCK_OVERRIDE_FROM_SIDEKICK,
                    mDrawWakeLockOverrideFromSidekick);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto
                            .DOZED_SCREEN_BRIGHTNESS_OVERRIDE_FROM_DREAM_MANAGER,
                    mDozeScreenBrightnessOverrideFromDreamManager);

            final long screenBrightnessSettingLimitsToken =
                    proto.start(
                            PowerServiceSettingsAndConfigurationDumpProto
                                    .SCREEN_BRIGHTNESS_SETTING_LIMITS);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ScreenBrightnessSettingLimitsProto
                            .SETTING_MINIMUM_FLOAT,
                    mScreenBrightnessMinimum);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ScreenBrightnessSettingLimitsProto
                            .SETTING_MAXIMUM_FLOAT,
                    mScreenBrightnessMaximum);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ScreenBrightnessSettingLimitsProto
                            .SETTING_DEFAULT_FLOAT,
                    mScreenBrightnessDefault);
            proto.end(screenBrightnessSettingLimitsToken);

            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.IS_DOUBLE_TAP_WAKE_ENABLED,
                    mDoubleTapWakeEnabled);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.IS_VR_MODE_ENABLED,
                    mIsVrModeEnabled);
            proto.end(settingsAndConfigurationToken);

            final long attentiveTimeout = getAttentiveTimeoutLocked();
            final long sleepTimeout = getSleepTimeoutLocked(attentiveTimeout);
            final long screenOffTimeout = getScreenOffTimeoutLocked(sleepTimeout, attentiveTimeout);
            final long screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
            proto.write(PowerManagerServiceDumpProto.ATTENTIVE_TIMEOUT_MS, attentiveTimeout);
            proto.write(PowerManagerServiceDumpProto.SLEEP_TIMEOUT_MS, sleepTimeout);
            proto.write(PowerManagerServiceDumpProto.SCREEN_OFF_TIMEOUT_MS, screenOffTimeout);
            proto.write(PowerManagerServiceDumpProto.SCREEN_DIM_DURATION_MS, screenDimDuration);
            proto.write(PowerManagerServiceDumpProto.ARE_UIDS_CHANGING, mUidsChanging);
            proto.write(PowerManagerServiceDumpProto.ARE_UIDS_CHANGED, mUidsChanged);

            for (int i = 0; i < mUidState.size(); i++) {
                final UidState state = mUidState.valueAt(i);
                final long uIDToken = proto.start(PowerManagerServiceDumpProto.UID_STATES);
                final int uid = mUidState.keyAt(i);
                proto.write(PowerManagerServiceDumpProto.UidStateProto.UID, uid);
                proto.write(PowerManagerServiceDumpProto.UidStateProto.UID_STRING, UserHandle.formatUid(uid));
                proto.write(PowerManagerServiceDumpProto.UidStateProto.IS_ACTIVE, state.mActive);
                proto.write(PowerManagerServiceDumpProto.UidStateProto.NUM_WAKE_LOCKS, state.mNumWakeLocks);
                proto.write(PowerManagerServiceDumpProto.UidStateProto.PROCESS_STATE,
                        ActivityManager.processStateAmToProto(state.mProcState));
                proto.end(uIDToken);
            }

            mBatterySaverStateMachine.dumpProto(proto,
                    PowerManagerServiceDumpProto.BATTERY_SAVER_STATE_MACHINE);

            mHandler.getLooper().dumpDebug(proto, PowerManagerServiceDumpProto.LOOPER);

            for (WakeLock wl : mWakeLocks) {
                wl.dumpDebug(proto, PowerManagerServiceDumpProto.WAKE_LOCKS);
            }

            for (SuspendBlocker sb : mSuspendBlockers) {
                sb.dumpDebug(proto, PowerManagerServiceDumpProto.SUSPEND_BLOCKERS);
            }

            wcd = mWirelessChargerDetector;
        }

        if (wcd != null) {
            wcd.dumpDebug(proto, PowerManagerServiceDumpProto.WIRELESS_CHARGER_DETECTOR);
        }

        mLowPowerStandbyController.dumpProto(proto,
                PowerManagerServiceDumpProto.LOW_POWER_STANDBY_CONTROLLER);

        proto.flush();
    }

    private void incrementBootCount() {
        synchronized (mLock) {
            int count;
            try {
                count = Settings.Global.getInt(
                        getContext().getContentResolver(), Settings.Global.BOOT_COUNT);
            } catch (SettingNotFoundException e) {
                count = 0;
            }
            Settings.Global.putInt(
                    getContext().getContentResolver(), Settings.Global.BOOT_COUNT, count + 1);
        }
    }

    private static WorkSource copyWorkSource(WorkSource workSource) {
        return workSource != null ? new WorkSource(workSource) : null;
    }

    @VisibleForTesting
    final class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                handleBatteryStateChangedLocked();
            }
        }
    }

    private final class DreamReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                scheduleSandmanLocked();
            }
        }
    }

    @VisibleForTesting
    final class UserSwitchedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                handleSettingsChangedLocked();
            }
        }
    }

    private final class DockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (mDockState != dockState) {
                    mDockState = dockState;
                    mDirty |= DIRTY_DOCK_STATE;
                    updatePowerStateLocked();
                }
            }
        }
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                handleSettingsChangedLocked();
            }
        }
    }

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            setPowerModeInternal(Mode.VR, enabled);

            synchronized (mLock) {
                if (mIsVrModeEnabled != enabled) {
                    setVrModeEnabled(enabled);
                    mDirty |= DIRTY_VR_MODE_CHANGED;
                    updatePowerStateLocked();
                }
            }
        }
    };

    /**
     * Callback for asynchronous operations performed by the power manager.
     */
    private final class PowerManagerHandlerCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_ACTIVITY_TIMEOUT:
                    handleUserActivityTimeout();
                    break;
                case MSG_SANDMAN:
                    handleSandman(msg.arg1);
                    break;
                case MSG_SCREEN_BRIGHTNESS_BOOST_TIMEOUT:
                    handleScreenBrightnessBoostTimeout();
                    break;
                case MSG_CHECK_FOR_LONG_WAKELOCKS:
                    checkForLongWakeLocks();
                    break;
                case MSG_ATTENTIVE_TIMEOUT:
                    handleAttentiveTimeout();
                    break;
            }

            return true;
        }
    }

    /**
     * Represents a wake lock that has been acquired by an application.
     */
    /* package */ final class WakeLock implements IBinder.DeathRecipient {
        public final IBinder mLock;
        public final int mDisplayId;
        public int mFlags;
        public String mTag;
        public final String mPackageName;
        public WorkSource mWorkSource;
        public String mHistoryTag;
        public final int mOwnerUid;
        public final int mOwnerPid;
        public final UidState mUidState;
        public long mAcquireTime;
        public boolean mNotifiedAcquired;
        public boolean mNotifiedLong;
        public boolean mDisabled;
        public IWakeLockCallback mCallback;

        public WakeLock(IBinder lock, int displayId, int flags, String tag, String packageName,
                WorkSource workSource, String historyTag, int ownerUid, int ownerPid,
                UidState uidState, @Nullable IWakeLockCallback callback) {
            mLock = lock;
            mDisplayId = displayId;
            mFlags = flags;
            mTag = tag;
            mPackageName = packageName;
            mWorkSource = copyWorkSource(workSource);
            mHistoryTag = historyTag;
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
            mUidState = uidState;
            mCallback = callback;
            linkToDeath();
        }

        @Override
        public void binderDied() {
            unlinkToDeath();
            PowerManagerService.this.handleWakeLockDeath(this);
        }

        private void linkToDeath() {
            try {
                mLock.linkToDeath(this, 0);
            } catch (RemoteException e) {
                throw new IllegalArgumentException("Wakelock.mLock is already dead.");
            }
        }

        @GuardedBy("mLock")
        void unlinkToDeath() {
            try {
                mLock.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Slog.wtf(TAG, "Failed to unlink Wakelock.mLock", e);
            }
        }

        public boolean setDisabled(boolean disabled) {
            if (mDisabled != disabled) {
                mDisabled = disabled;
                return true;
            } else {
                return false;
            }
        }
        public boolean hasSameProperties(int flags, String tag, WorkSource workSource,
                int ownerUid, int ownerPid, IWakeLockCallback callback) {
            return mFlags == flags
                    && mTag.equals(tag)
                    && hasSameWorkSource(workSource)
                    && mOwnerUid == ownerUid
                    && mOwnerPid == ownerPid;
        }

        public void updateProperties(int flags, String tag, String packageName,
                WorkSource workSource, String historyTag, int ownerUid, int ownerPid,
                IWakeLockCallback callback) {
            if (!mPackageName.equals(packageName)) {
                throw new IllegalStateException("Existing wake lock package name changed: "
                        + mPackageName + " to " + packageName);
            }
            if (mOwnerUid != ownerUid) {
                throw new IllegalStateException("Existing wake lock uid changed: "
                        + mOwnerUid + " to " + ownerUid);
            }
            if (mOwnerPid != ownerPid) {
                throw new IllegalStateException("Existing wake lock pid changed: "
                        + mOwnerPid + " to " + ownerPid);
            }
            mFlags = flags;
            mTag = tag;
            updateWorkSource(workSource);
            mHistoryTag = historyTag;
            mCallback = callback;
        }

        public boolean hasSameWorkSource(WorkSource workSource) {
            return Objects.equals(mWorkSource, workSource);
        }

        public void updateWorkSource(WorkSource workSource) {
            mWorkSource = copyWorkSource(workSource);
        }

        /** Returns the PowerGroup Id of this wakeLock or {@code null} if info not available.. */
        public Integer getPowerGroupId() {
            if (!mSystemReady || mDisplayId == Display.INVALID_DISPLAY) {
                return Display.INVALID_DISPLAY_GROUP;
            }

            final DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(mDisplayId);
            if (displayInfo != null) {
                return displayInfo.displayGroupId;
            }

            return null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getLockLevelString());
            sb.append(" '");
            sb.append(mTag);
            sb.append("'");
            sb.append(getLockFlagsString());
            if (mDisabled) {
                sb.append(" DISABLED");
            }
            if (mNotifiedAcquired) {
                sb.append(" ACQ=");
                TimeUtils.formatDuration(mAcquireTime-mClock.uptimeMillis(), sb);
            }
            if (mNotifiedLong) {
                sb.append(" LONG");
            }
            sb.append(" (uid=");
            sb.append(mOwnerUid);
            if (mOwnerPid != 0) {
                sb.append(" pid=");
                sb.append(mOwnerPid);
            }
            if (mWorkSource != null) {
                sb.append(" ws=");
                sb.append(mWorkSource);
            }
            sb.append(")");
            return sb.toString();
        }

        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long wakeLockToken = proto.start(fieldId);
            proto.write(WakeLockProto.LOCK_LEVEL, (mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK));
            proto.write(WakeLockProto.TAG, mTag);

            final long wakeLockFlagsToken = proto.start(WakeLockProto.FLAGS);
            proto.write(WakeLockProto.WakeLockFlagsProto.IS_ACQUIRE_CAUSES_WAKEUP,
                    (mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP)!=0);
            proto.write(WakeLockProto.WakeLockFlagsProto.IS_ON_AFTER_RELEASE,
                    (mFlags & PowerManager.ON_AFTER_RELEASE)!=0);
            proto.write(WakeLockProto.WakeLockFlagsProto.SYSTEM_WAKELOCK,
                    (mFlags & PowerManager.SYSTEM_WAKELOCK) != 0);
            proto.end(wakeLockFlagsToken);

            proto.write(WakeLockProto.IS_DISABLED, mDisabled);
            if (mNotifiedAcquired) {
                proto.write(WakeLockProto.ACQ_MS, mAcquireTime);
            }
            proto.write(WakeLockProto.IS_NOTIFIED_LONG, mNotifiedLong);
            proto.write(WakeLockProto.UID, mOwnerUid);
            proto.write(WakeLockProto.PID, mOwnerPid);

            if (mWorkSource != null) {
                mWorkSource.dumpDebug(proto, WakeLockProto.WORK_SOURCE);
            }
            proto.end(wakeLockToken);
        }

        @SuppressWarnings("deprecation")
        private String getLockLevelString() {
            switch (mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                case PowerManager.FULL_WAKE_LOCK:
                    return "FULL_WAKE_LOCK                ";
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                    return "SCREEN_BRIGHT_WAKE_LOCK       ";
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    return "SCREEN_DIM_WAKE_LOCK          ";
                case PowerManager.PARTIAL_WAKE_LOCK:
                    return "PARTIAL_WAKE_LOCK             ";
                case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                    return "PROXIMITY_SCREEN_OFF_WAKE_LOCK";
                case PowerManager.DOZE_WAKE_LOCK:
                    return "DOZE_WAKE_LOCK                ";
                case PowerManager.DRAW_WAKE_LOCK:
                    return "DRAW_WAKE_LOCK                ";
                default:
                    return "???                           ";
            }
        }

        private String getLockFlagsString() {
            String result = "";
            if ((mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                result += " ACQUIRE_CAUSES_WAKEUP";
            }
            if ((mFlags & PowerManager.ON_AFTER_RELEASE) != 0) {
                result += " ON_AFTER_RELEASE";
            }
            if ((mFlags & PowerManager.SYSTEM_WAKELOCK) != 0) {
                result += " SYSTEM_WAKELOCK";
            }
            return result;
        }
    }

    private final class SuspendBlockerImpl implements SuspendBlocker {
        private final String mName;
        private final String mTraceName;
        private int mReferenceCount;

        public SuspendBlockerImpl(String name) {
            mName = name;
            mTraceName = "SuspendBlocker (" + name + ")";
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mReferenceCount != 0) {
                    Slog.wtf(TAG, "Suspend blocker \"" + mName
                            + "\" was finalized without being released!");
                    mReferenceCount = 0;
                    mNativeWrapper.nativeReleaseSuspendBlocker(mName);
                    Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, mTraceName, 0);
                }
            } finally {
                super.finalize();
            }
        }

        @Override
        public void acquire() {
            synchronized (this) {
                mReferenceCount += 1;
                if (mReferenceCount == 1) {
                    if (DEBUG_SPEW) {
                        Slog.d(TAG, "Acquiring suspend blocker \"" + mName + "\".");
                    }
                    Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, mTraceName, 0);
                    mNativeWrapper.nativeAcquireSuspendBlocker(mName);
                }
            }
        }

        @Override
        public void release() {
            synchronized (this) {
                mReferenceCount -= 1;
                if (mReferenceCount == 0) {
                    if (DEBUG_SPEW) {
                        Slog.d(TAG, "Releasing suspend blocker \"" + mName + "\".");
                    }
                    mNativeWrapper.nativeReleaseSuspendBlocker(mName);
                    Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, mTraceName, 0);
                } else if (mReferenceCount < 0) {
                    Slog.wtf(TAG, "Suspend blocker \"" + mName
                            + "\" was released without being acquired!", new Throwable());
                    mReferenceCount = 0;
                }
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return mName + ": ref count=" + mReferenceCount;
            }
        }

        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long sbToken = proto.start(fieldId);
            synchronized (this) {
                proto.write(SuspendBlockerProto.NAME, mName);
                proto.write(SuspendBlockerProto.REFERENCE_COUNT, mReferenceCount);
            }
            proto.end(sbToken);
        }
    }

    static final class UidState {
        final int mUid;
        int mNumWakeLocks;
        int mProcState;
        boolean mActive;

        UidState(int uid) {
            mUid = uid;
        }
    }

    @VisibleForTesting
    final class BinderService extends IPowerManager.Stub {
        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new PowerManagerShellCommand(this)).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }

        @Override // Binder call
        public void acquireWakeLockWithUid(IBinder lock, int flags, String tag,
                String packageName, int uid, int displayId, IWakeLockCallback callback) {
            if (uid < 0) {
                uid = Binder.getCallingUid();
            }
            acquireWakeLock(lock, flags, tag, packageName, new WorkSource(uid), null,
                    displayId, callback);
        }

        @Override // Binder call
        public void setPowerBoost(int boost, int durationMs) {
            if (!mSystemReady) {
                // Service not ready yet, so who the heck cares about power hints, bah.
                return;
            }
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
            setPowerBoostInternal(boost, durationMs);
        }

        @Override // Binder call
        public void setPowerMode(int mode, boolean enabled) {
            if (!mSystemReady) {
                // Service not ready yet, so who the heck cares about power hints, bah.
                return;
            }
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
            setPowerModeInternal(mode, enabled); // Intentionally ignore return value
        }

        @Override // Binder call
        public boolean setPowerModeChecked(int mode, boolean enabled) {
            if (!mSystemReady) {
                // Service not ready yet, so who the heck cares about power hints, bah.
                return false;
            }
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
            return setPowerModeInternal(mode, enabled);
        }

        @Override // Binder call
        public void acquireWakeLock(IBinder lock, int flags, String tag, String packageName,
                WorkSource ws, String historyTag, int displayId,
                @Nullable IWakeLockCallback callback) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }
            if (packageName == null) {
                throw new IllegalArgumentException("packageName must not be null");
            }
            PowerManager.validateWakeLockParameters(flags, tag);

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
            if ((flags & PowerManager.DOZE_WAKE_LOCK) != 0) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
            }
            if (ws != null && !ws.isEmpty()) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.UPDATE_DEVICE_STATS, null);
            } else {
                ws = null;
            }

            int uid = Binder.getCallingUid();
            int pid = Binder.getCallingPid();

            if ((flags & PowerManager.SYSTEM_WAKELOCK) != 0) {
                mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER,
                        null);
                WorkSource workSource = new WorkSource(Binder.getCallingUid(), packageName);
                if (ws != null && !ws.isEmpty()) {
                    workSource.add(ws);
                }
                ws = workSource;

                uid = Process.myUid();
                pid = Process.myPid();
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                acquireWakeLockInternal(lock, displayId, flags, tag, packageName, ws, historyTag,
                        uid, pid, callback);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void acquireWakeLockAsync(IBinder lock, int flags, String tag, String packageName,
                WorkSource ws, String historyTag) {
            acquireWakeLock(lock, flags, tag, packageName, ws, historyTag, Display.INVALID_DISPLAY,
                    null);
        }

        @Override // Binder call
        public void releaseWakeLock(IBinder lock, int flags) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                releaseWakeLockInternal(lock, flags);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void releaseWakeLockAsync(IBinder lock, int flags) {
            releaseWakeLock(lock, flags);
        }

        @Override // Binder call
        public void updateWakeLockUids(IBinder lock, int[] uids) {
            WorkSource ws = null;

            if (uids != null) {
                ws = new WorkSource();
                // XXX should WorkSource have a way to set uids as an int[] instead of adding them
                // one at a time?
                for (int uid : uids) {
                    ws.add(uid);
                }
            }
            updateWakeLockWorkSource(lock, ws, null);
        }

        @Override // Binder call
        public void updateWakeLockUidsAsync(IBinder lock, int[] uids) {
            updateWakeLockUids(lock, uids);
        }

        @Override // Binder call
        public void updateWakeLockWorkSource(IBinder lock, WorkSource ws, String historyTag) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
            if (ws != null && !ws.isEmpty()) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.UPDATE_DEVICE_STATS, null);
            } else {
                ws = null;
            }

            final int callingUid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                updateWakeLockWorkSourceInternal(lock, ws, historyTag, callingUid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void updateWakeLockCallback(IBinder lock, IWakeLockCallback callback) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);

            final int callingUid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                updateWakeLockCallbackInternal(lock, callback, callingUid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isWakeLockLevelSupported(int level) {
            final long ident = Binder.clearCallingIdentity();
            try {
                return isWakeLockLevelSupportedInternal(level);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void userActivity(int displayId, long eventTime, int event, int flags) {
            final long now = mClock.uptimeMillis();
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                    != PackageManager.PERMISSION_GRANTED
                    && mContext.checkCallingOrSelfPermission(
                            android.Manifest.permission.USER_ACTIVITY)
                            != PackageManager.PERMISSION_GRANTED) {
                // Once upon a time applications could call userActivity().
                // Now we require the DEVICE_POWER permission.  Log a warning and ignore the
                // request instead of throwing a SecurityException so we don't break old apps.
                synchronized (mLock) {
                    if (now >= mLastWarningAboutUserActivityPermission + (5 * 60 * 1000)) {
                        mLastWarningAboutUserActivityPermission = now;
                        Slog.w(TAG, "Ignoring call to PowerManager.userActivity() because the "
                                + "caller does not have DEVICE_POWER or USER_ACTIVITY "
                                + "permission.  Please fix your app!  "
                                + " pid=" + Binder.getCallingPid()
                                + " uid=" + Binder.getCallingUid());
                    }
                }
                return;
            }

            if (eventTime > now) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                userActivityInternal(displayId, eventTime, event, flags, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void wakeUp(long eventTime, @WakeReason int reason, String details,
                String opPackageName) {
            if (eventTime > mClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (!mBootCompleted && sQuiescent) {
                        mDirty |= DIRTY_QUIESCENT;
                        updatePowerStateLocked();
                        return;
                    }
                    wakePowerGroupLocked(mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP), eventTime,
                            reason, details, uid, opPackageName, uid);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void goToSleep(long eventTime, int reason, int flags) {
            if (eventTime > mClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    PowerGroup defaultPowerGroup = mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP);
                    if ((flags & PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE) != 0) {
                        sleepPowerGroupLocked(defaultPowerGroup, eventTime, reason, uid);
                    } else {
                        dozePowerGroupLocked(defaultPowerGroup, eventTime, reason, uid);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void nap(long eventTime) {
            if (eventTime > mClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    dreamPowerGroupLocked(mPowerGroups.get(Display.DEFAULT_DISPLAY_GROUP),
                            eventTime, uid);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public float getBrightnessConstraint(int constraint) {
            switch (constraint) {
                case PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM:
                    return mScreenBrightnessMinimum;
                case PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM:
                    return mScreenBrightnessMaximum;
                case PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT:
                    return mScreenBrightnessDefault;
                case PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DIM:
                    return mScreenBrightnessDim;
                case PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DOZE:
                    return mScreenBrightnessDoze;
                case PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM_VR:
                    return mScreenBrightnessMinimumVr;
                case PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM_VR:
                    return mScreenBrightnessMaximumVr;
                case PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT_VR:
                    return mScreenBrightnessDefaultVr;
                default:
                    return PowerManager.BRIGHTNESS_INVALID_FLOAT;
            }
        }

        @Override // Binder call
        public boolean isInteractive() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return isInteractiveInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isPowerSaveMode() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return mBatterySaverController.isEnabled();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        // Binder call
        public PowerSaveState getPowerSaveState(@ServiceType int serviceType) {
            final long ident = Binder.clearCallingIdentity();
            try {
                return mBatterySaverPolicy.getBatterySaverPolicy(serviceType);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean setPowerSaveModeEnabled(boolean enabled) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.POWER_SAVER)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return setLowPowerModeInternal(enabled);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public BatterySaverPolicyConfig getFullPowerSavePolicy() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return mBatterySaverStateMachine.getFullBatterySaverPolicy();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean setFullPowerSavePolicy(@NonNull BatterySaverPolicyConfig config) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.POWER_SAVER)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, "setFullPowerSavePolicy");
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return mBatterySaverStateMachine.setFullBatterySaverPolicy(config);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean setDynamicPowerSaveHint(boolean powerSaveHint, int disableThreshold) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.POWER_SAVER,
                    "updateDynamicPowerSavings");
            final long ident = Binder.clearCallingIdentity();
            try {
                final ContentResolver resolver = mContext.getContentResolver();
                boolean success = Settings.Global.putInt(resolver,
                        Settings.Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD,
                        disableThreshold);
                if (success) {
                    // abort updating if we weren't able to succeed on the threshold
                    success &= Settings.Global.putInt(resolver,
                            Settings.Global.DYNAMIC_POWER_SAVINGS_ENABLED,
                            powerSaveHint ? 1 : 0);
                }
                return success;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean setAdaptivePowerSavePolicy(@NonNull BatterySaverPolicyConfig config) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.POWER_SAVER)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, "setAdaptivePowerSavePolicy");
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return mBatterySaverStateMachine.setAdaptiveBatterySaverPolicy(config);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean setAdaptivePowerSaveEnabled(boolean enabled) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.POWER_SAVER)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, "setAdaptivePowerSaveEnabled");
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return mBatterySaverStateMachine.setAdaptiveBatterySaverEnabled(enabled);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public int getPowerSaveModeTrigger() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.AUTOMATIC_POWER_SAVE_MODE,
                        PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void setBatteryDischargePrediction(@NonNull ParcelDuration timeRemaining,
                boolean isPersonalized) {
            // Get current time before acquiring the lock so that the calculated end time is as
            // accurate as possible.
            final long nowElapsed = SystemClock.elapsedRealtime();
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.BATTERY_PREDICTION)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, "setBatteryDischargePrediction");
            }

            final long timeRemainingMs = timeRemaining.getDuration().toMillis();
            // A non-positive number means the battery should be dead right now...
            Preconditions.checkArgumentPositive(timeRemainingMs,
                    "Given time remaining is not positive: " + timeRemainingMs);

            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mIsPowered) {
                        throw new IllegalStateException(
                                "Discharge prediction can't be set while the device is charging");
                    }
                }

                final long broadcastDelayMs;
                synchronized (mEnhancedDischargeTimeLock) {
                    if (mLastEnhancedDischargeTimeUpdatedElapsed > nowElapsed) {
                        // Another later call made it into the block first. Keep the latest info.
                        return;
                    }
                    broadcastDelayMs = Math.max(0,
                            ENHANCED_DISCHARGE_PREDICTION_BROADCAST_MIN_DELAY_MS
                                    - (nowElapsed - mLastEnhancedDischargeTimeUpdatedElapsed));

                    // No need to persist the discharge prediction values since they'll most likely
                    // be wrong immediately after a reboot anyway.
                    mEnhancedDischargeTimeElapsed = nowElapsed + timeRemainingMs;
                    mEnhancedDischargePredictionIsPersonalized = isPersonalized;
                    mLastEnhancedDischargeTimeUpdatedElapsed = nowElapsed;
                }
                mNotifier.postEnhancedDischargePredictionBroadcast(broadcastDelayMs);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @GuardedBy("PowerManagerService.this.mEnhancedDischargeTimeLock")
        private boolean isEnhancedDischargePredictionValidLocked(long nowElapsed) {
            return mLastEnhancedDischargeTimeUpdatedElapsed > 0
                    && nowElapsed < mEnhancedDischargeTimeElapsed
                    && nowElapsed - mLastEnhancedDischargeTimeUpdatedElapsed
                    < ENHANCED_DISCHARGE_PREDICTION_TIMEOUT_MS;
        }

        @Override // Binder call
        public ParcelDuration getBatteryDischargePrediction() {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mIsPowered) {
                        return null;
                    }
                }
                synchronized (mEnhancedDischargeTimeLock) {
                    // Get current time after acquiring the lock so that the calculated duration
                    // is as accurate as possible.
                    final long nowElapsed = SystemClock.elapsedRealtime();
                    if (isEnhancedDischargePredictionValidLocked(nowElapsed)) {
                        return new ParcelDuration(mEnhancedDischargeTimeElapsed - nowElapsed);
                    }
                }
                return new ParcelDuration(mBatteryStats.computeBatteryTimeRemaining());
            } catch (RemoteException e) {
                // Shouldn't happen in-process.
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return null;
        }

        @Override // Binder call
        public boolean isBatteryDischargePredictionPersonalized() {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mEnhancedDischargeTimeLock) {
                    return isEnhancedDischargePredictionValidLocked(SystemClock.elapsedRealtime())
                            && mEnhancedDischargePredictionIsPersonalized;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isDeviceIdleMode() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return isDeviceIdleModeInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isLightDeviceIdleMode() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return isLightDeviceIdleModeInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        @RequiresPermission(anyOf = {
                android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
                android.Manifest.permission.DEVICE_POWER
        })
        public boolean isLowPowerStandbySupported() {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
                        "isLowPowerStandbySupported");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                return mLowPowerStandbyController.isSupported();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isLowPowerStandbyEnabled() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return mLowPowerStandbyController.isEnabled();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        @RequiresPermission(anyOf = {
                android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
                android.Manifest.permission.DEVICE_POWER
        })
        public void setLowPowerStandbyEnabled(boolean enabled) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
                        "setLowPowerStandbyEnabled");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                mLowPowerStandbyController.setEnabled(enabled);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        @RequiresPermission(anyOf = {
                android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
                android.Manifest.permission.DEVICE_POWER
        })
        public void setLowPowerStandbyActiveDuringMaintenance(boolean activeDuringMaintenance) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
                        "setLowPowerStandbyActiveDuringMaintenance");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                mLowPowerStandbyController.setActiveDuringMaintenance(activeDuringMaintenance);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        @RequiresPermission(anyOf = {
                android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
                android.Manifest.permission.DEVICE_POWER
        })
        public void forceLowPowerStandbyActive(boolean active) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                    != PackageManager.PERMISSION_GRANTED) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
                        "forceLowPowerStandbyActive");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                mLowPowerStandbyController.forceActive(active);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Gets the reason for the last time the phone had to reboot.
         *
         * @return The reason the phone last shut down as an int or
         * {@link PowerManager#SHUTDOWN_REASON_UNKNOWN} if the file could not be opened.
         */
        @Override // Binder call
        public int getLastShutdownReason() {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return getLastShutdownReasonInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public int getLastSleepReason() {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return getLastSleepReasonInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Reboots the device.
         *
         * @param confirm If true, shows a reboot confirmation dialog.
         * @param reason The reason for the reboot, or null if none.
         * @param wait If true, this call waits for the reboot to complete and does not return.
         */
        @Override // Binder call
        public void reboot(boolean confirm, @Nullable String reason, boolean wait) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);
            if (PowerManager.REBOOT_RECOVERY.equals(reason)
                    || PowerManager.REBOOT_RECOVERY_UPDATE.equals(reason)) {
                mContext.enforceCallingOrSelfPermission(android.Manifest.permission.RECOVERY, null);
            }

            ShutdownCheckPoints.recordCheckPoint(Binder.getCallingPid(), reason);
            final long ident = Binder.clearCallingIdentity();
            try {
                shutdownOrRebootInternal(HALT_MODE_REBOOT, confirm, reason, wait);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Reboots the device into safe mode
         *
         * @param confirm If true, shows a reboot confirmation dialog.
         * @param wait If true, this call waits for the reboot to complete and does not return.
         */
        @Override // Binder call
        public void rebootSafeMode(boolean confirm, boolean wait) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

            String reason = PowerManager.REBOOT_SAFE_MODE;
            ShutdownCheckPoints.recordCheckPoint(Binder.getCallingPid(), reason);
            final long ident = Binder.clearCallingIdentity();
            try {
                shutdownOrRebootInternal(HALT_MODE_REBOOT_SAFE_MODE, confirm, reason, wait);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Shuts down the device.
         *
         * @param confirm If true, shows a shutdown confirmation dialog.
         * @param wait If true, this call waits for the shutdown to complete and does not return.
         */
        @Override // Binder call
        public void shutdown(boolean confirm, String reason, boolean wait) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

            ShutdownCheckPoints.recordCheckPoint(Binder.getCallingPid(), reason);
            final long ident = Binder.clearCallingIdentity();
            try {
                shutdownOrRebootInternal(HALT_MODE_SHUTDOWN, confirm, reason, wait);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Crash the runtime (causing a complete restart of the Android framework).
         * Requires REBOOT permission.  Mostly for testing.  Should not return.
         */
        @Override // Binder call
        public void crash(String message) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                crashInternal(message);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Set the setting that determines whether the device stays on when plugged in.
         * The argument is a bit string, with each bit specifying a power source that,
         * when the device is connected to that source, causes the device to stay on.
         * See {@link android.os.BatteryManager} for the list of power sources that
         * can be specified. Current values include
         * {@link android.os.BatteryManager#BATTERY_PLUGGED_AC}
         * and {@link android.os.BatteryManager#BATTERY_PLUGGED_USB}
         *
         * Used by "adb shell svc power stayon ..."
         *
         * @param val an {@code int} containing the bits that specify which power sources
         * should cause the device to stay on.
         */
        @Override // Binder call
        public void setStayOnSetting(int val) {
            int uid = Binder.getCallingUid();
            // if uid is of root's, we permit this operation straight away
            if (uid != Process.ROOT_UID) {
                if (!Settings.checkAndNoteWriteSettingsOperation(mContext, uid,
                        Settings.getPackageNameForUid(mContext, uid), /* attributionTag= */ null,
                        /* throwException= */ true)) {
                    return;
                }
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                setStayOnSettingInternal(val);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Used by the phone application to make the attention LED flash when ringing.
         */
        @Override // Binder call
        public void setAttentionLight(boolean on, int color) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                setAttentionLightInternal(on, color);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void setDozeAfterScreenOff(boolean on) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                setDozeAfterScreenOffInternal(on);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isAmbientDisplayAvailable() {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_DREAM_STATE, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return mAmbientDisplayConfiguration.ambientDisplayAvailable();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void suppressAmbientDisplay(@NonNull String token, boolean suppress) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_DREAM_STATE, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                mAmbientDisplaySuppressionController.suppress(token, uid, suppress);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isAmbientDisplaySuppressedForToken(@NonNull String token) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_DREAM_STATE, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                return mAmbientDisplaySuppressionController.isSuppressed(token, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isAmbientDisplaySuppressedForTokenByApp(@NonNull String token, int appUid) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_DREAM_STATE, null);
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_DREAM_SUPPRESSION, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return isAmbientDisplayAvailable()
                        && mAmbientDisplaySuppressionController.isSuppressed(token, appUid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isAmbientDisplaySuppressed() {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_DREAM_STATE, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return mAmbientDisplaySuppressionController.isSuppressed();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void boostScreenBrightness(long eventTime) {
            if (eventTime > mClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                boostScreenBrightnessInternal(eventTime, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isScreenBrightnessBoosted() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return isScreenBrightnessBoostedInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // binder call
        public boolean forceSuspend() {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                return forceSuspendInternal(uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            final long ident = Binder.clearCallingIdentity();

            boolean isDumpProto = false;
            for (String arg : args) {
                if (arg.equals("--proto")) {
                    isDumpProto = true;
                    break;
                }
            }
            try {
                if (isDumpProto) {
                    dumpProto(fd);
                } else {
                    dumpInternal(pw);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Returns the tokens used to suppress ambient display by the calling app.
         *
         * <p>The calling app suppressed ambient display by calling
         * {@link #suppressAmbientDisplay(String, boolean)}.
         */
        public List<String> getAmbientDisplaySuppressionTokens() {
            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                return mAmbientDisplaySuppressionController.getSuppressionTokens(uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @VisibleForTesting
    BinderService getBinderServiceInstance() {
        return mBinderService;
    }

    @VisibleForTesting
    LocalService getLocalServiceInstance() {
        return mLocalService;
    }

    @VisibleForTesting
    int getLastShutdownReasonInternal() {
        String line = mSystemProperties.get(SYSTEM_PROPERTY_REBOOT_REASON, null);
        switch (line) {
            case REASON_SHUTDOWN:
                return PowerManager.SHUTDOWN_REASON_SHUTDOWN;
            case REASON_REBOOT:
                return PowerManager.SHUTDOWN_REASON_REBOOT;
            case REASON_USERREQUESTED:
                return PowerManager.SHUTDOWN_REASON_USER_REQUESTED;
            case REASON_THERMAL_SHUTDOWN:
                return PowerManager.SHUTDOWN_REASON_THERMAL_SHUTDOWN;
            case REASON_LOW_BATTERY:
                return PowerManager.SHUTDOWN_REASON_LOW_BATTERY;
            case REASON_BATTERY_THERMAL_STATE:
                return PowerManager.SHUTDOWN_REASON_BATTERY_THERMAL;
            default:
                return PowerManager.SHUTDOWN_REASON_UNKNOWN;
        }
    }

    @GoToSleepReason
    private int getLastSleepReasonInternal() {
        synchronized (mLock) {
            return mLastGlobalSleepReason;
        }
    }

    private PowerManager.WakeData getLastWakeupInternal() {
        synchronized (mLock) {
            return new PowerManager.WakeData(mLastGlobalWakeTime, mLastGlobalWakeReason,
                    mLastGlobalWakeTime - mLastGlobalSleepTime);
        }
    }

    private PowerManager.SleepData getLastGoToSleepInternal() {
        synchronized (mLock) {
            return new PowerManager.SleepData(mLastGlobalSleepTime, mLastGlobalSleepReason);
        }
    }

    /**
     * If the user presses power while the proximity sensor is enabled and keeping
     * the screen off, then turn the screen back on by telling display manager to
     * ignore the proximity sensor.  We don't turn off the proximity sensor because
     * we still want it to be reenabled if it's state changes.
     *
     * @return true if the proximity sensor was successfully ignored and we should
     * consume the key event.
     */
    private boolean interceptPowerKeyDownInternal(KeyEvent event) {
        synchronized (mLock) {
            // DisplayPowerController only reports proximity positive (near) if it's
            // positive and the proximity wasn't already being ignored. So it reliably
            // also tells us that we're not already ignoring the proximity sensor.

            if (mProximityPositive && !mInterceptedPowerKeyForProximity) {
                mDisplayManagerInternal.ignoreProximitySensorUntilChanged();
                mInterceptedPowerKeyForProximity = true;
                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    final class LocalService extends PowerManagerInternal {
        @Override
        public void setScreenBrightnessOverrideFromWindowManager(float screenBrightness) {
            if (screenBrightness < PowerManager.BRIGHTNESS_MIN
                    || screenBrightness > PowerManager.BRIGHTNESS_MAX) {
                screenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            }
            setScreenBrightnessOverrideFromWindowManagerInternal(screenBrightness);
        }

        @Override
        public void setDozeOverrideFromDreamManager(int screenState, int screenBrightness) {
            switch (screenState) {
                case Display.STATE_UNKNOWN:
                case Display.STATE_OFF:
                case Display.STATE_DOZE:
                case Display.STATE_DOZE_SUSPEND:
                case Display.STATE_ON_SUSPEND:
                case Display.STATE_ON:
                case Display.STATE_VR:
                    break;
                default:
                    screenState = Display.STATE_UNKNOWN;
                    break;
            }
            if (screenBrightness < PowerManager.BRIGHTNESS_DEFAULT
                    || screenBrightness > PowerManager.BRIGHTNESS_ON) {
                screenBrightness = PowerManager.BRIGHTNESS_DEFAULT;
            }
            setDozeOverrideFromDreamManagerInternal(screenState, screenBrightness);
        }

        @Override
        public void setUserInactiveOverrideFromWindowManager() {
            setUserInactiveOverrideFromWindowManagerInternal();
        }

        @Override
        public void setUserActivityTimeoutOverrideFromWindowManager(long timeoutMillis) {
            setUserActivityTimeoutOverrideFromWindowManagerInternal(timeoutMillis);
        }

        @Override
        public void setDrawWakeLockOverrideFromSidekick(boolean keepState) {
            setDrawWakeLockOverrideFromSidekickInternal(keepState);
        }

        @Override
        public void setMaximumScreenOffTimeoutFromDeviceAdmin(@UserIdInt int userId, long timeMs) {
            setMaximumScreenOffTimeoutFromDeviceAdminInternal(userId, timeMs);
        }

        @Override
        public PowerSaveState getLowPowerState(@ServiceType int serviceType) {
            return mBatterySaverPolicy.getBatterySaverPolicy(serviceType);
        }

        @Override
        public void registerLowPowerModeObserver(LowPowerModeListener listener) {
            mBatterySaverController.addListener(listener);
        }

        @Override
        public boolean setDeviceIdleMode(boolean enabled) {
            return setDeviceIdleModeInternal(enabled);
        }

        @Override
        public boolean setLightDeviceIdleMode(boolean enabled) {
            return setLightDeviceIdleModeInternal(enabled);
        }

        @Override
        public void setDeviceIdleWhitelist(int[] appids) {
            setDeviceIdleWhitelistInternal(appids);
        }

        @Override
        public void setDeviceIdleTempWhitelist(int[] appids) {
            setDeviceIdleTempWhitelistInternal(appids);
        }

        @Override
        public void setLowPowerStandbyAllowlist(int[] appids) {
            setLowPowerStandbyAllowlistInternal(appids);
        }

        @Override
        public void setLowPowerStandbyActive(boolean enabled) {
            setLowPowerStandbyActiveInternal(enabled);
        }

        @Override
        public void startUidChanges() {
            startUidChangesInternal();
        }

        @Override
        public void finishUidChanges() {
            finishUidChangesInternal();
        }

        @Override
        public void updateUidProcState(int uid, int procState) {
            updateUidProcStateInternal(uid, procState);
        }

        @Override
        public void uidGone(int uid) {
            uidGoneInternal(uid);
        }

        @Override
        public void uidActive(int uid) {
            uidActiveInternal(uid);
        }

        @Override
        public void uidIdle(int uid) {
            uidIdleInternal(uid);
        }

        @Override
        public void setPowerBoost(int boost, int durationMs) {
            setPowerBoostInternal(boost, durationMs);
        }

        @Override
        public void setPowerMode(int mode, boolean enabled) {
            setPowerModeInternal(mode, enabled);
        }

        @Override
        public boolean wasDeviceIdleFor(long ms) {
            return wasDeviceIdleForInternal(ms);
        }

        @Override
        public PowerManager.WakeData getLastWakeup() {
            return getLastWakeupInternal();
        }

        @Override
        public PowerManager.SleepData getLastGoToSleep() {
            return getLastGoToSleepInternal();
        }

        @Override
        public boolean interceptPowerKeyDown(KeyEvent event) {
            return interceptPowerKeyDownInternal(event);
        }
    }

    /**
     * Listens to changes in device state and updates the interactivity time.
     * Any changes to the device state are treated as user interactions.
     */
    class DeviceStateListener implements DeviceStateManager.DeviceStateCallback {
        private int mDeviceState = DeviceStateManager.INVALID_DEVICE_STATE;

        @Override
        public void onStateChanged(int deviceState) {
            if (mDeviceState != deviceState) {
                mDeviceState = deviceState;
                // Device-state interactions are applied to the default display so that they
                // are reflected only with the default power group.
                userActivityInternal(Display.DEFAULT_DISPLAY, mClock.uptimeMillis(),
                        PowerManager.USER_ACTIVITY_EVENT_DEVICE_STATE, /* flags= */0,
                        Process.SYSTEM_UID);
            }
        }
    };

    static boolean isSameCallback(IWakeLockCallback callback1,
            IWakeLockCallback callback2) {
        if (callback1 == callback2) {
            return true;
        }
        if (callback1 != null && callback2 != null
                && callback1.asBinder() == callback2.asBinder()) {
            return true;
        }
        return false;
    }
}
