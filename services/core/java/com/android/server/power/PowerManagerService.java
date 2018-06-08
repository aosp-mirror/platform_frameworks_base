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

import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;

import android.annotation.IntDef;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.SensorManager;
import android.hardware.SystemSensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.hardware.power.V1_0.PowerHint;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
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
import android.provider.Settings.Global;
import android.provider.Settings.SettingNotFoundException;
import android.service.dreams.DreamManagerInternal;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.KeyValueListParser;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.EventLogTags;
import com.android.server.LockGuard;
import com.android.server.RescueParty;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.am.BatteryStatsService;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.batterysaver.BatterySaverController;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.power.batterysaver.BatterySavingStats;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

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

    // Summarizes the state of all active wakelocks.
    private static final int WAKE_LOCK_CPU = 1 << 0;
    private static final int WAKE_LOCK_SCREEN_BRIGHT = 1 << 1;
    private static final int WAKE_LOCK_SCREEN_DIM = 1 << 2;
    private static final int WAKE_LOCK_BUTTON_BRIGHT = 1 << 3;
    private static final int WAKE_LOCK_PROXIMITY_SCREEN_OFF = 1 << 4;
    private static final int WAKE_LOCK_STAY_AWAKE = 1 << 5; // only set if already awake
    private static final int WAKE_LOCK_DOZE = 1 << 6;
    private static final int WAKE_LOCK_DRAW = 1 << 7;

    // Summarizes the user activity state.
    private static final int USER_ACTIVITY_SCREEN_BRIGHT = 1 << 0;
    private static final int USER_ACTIVITY_SCREEN_DIM = 1 << 1;
    private static final int USER_ACTIVITY_SCREEN_DREAM = 1 << 2;

    // Default timeout in milliseconds.  This is only used until the settings
    // provider populates the actual default value (R.integer.def_screen_off_timeout).
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT = 15 * 1000;
    private static final int DEFAULT_SLEEP_TIMEOUT = -1;

    // Screen brightness boost timeout.
    // Hardcoded for now until we decide what the right policy should be.
    // This should perhaps be a setting.
    private static final int SCREEN_BRIGHTNESS_BOOST_TIMEOUT = 5 * 1000;

    // How long a partial wake lock must be held until we consider it a long wake lock.
    static final long MIN_LONG_WAKE_CHECK_INTERVAL = 60*1000;

    // Power features defined in hardware/libhardware/include/hardware/power.h.
    private static final int POWER_FEATURE_DOUBLE_TAP_TO_WAKE = 1;

    // Default setting for double tap to wake.
    private static final int DEFAULT_DOUBLE_TAP_TO_WAKE = 0;

    // System property indicating that the screen should remain off until an explicit user action
    private static final String SYSTEM_PROPERTY_QUIESCENT = "ro.boot.quiescent";

    // System Property indicating that retail demo mode is currently enabled.
    private static final String SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED = "sys.retaildemo.enabled";

    // Possible reasons for shutting down for use in data/misc/reboot/last_shutdown_reason
    private static final String REASON_SHUTDOWN = "shutdown";
    private static final String REASON_REBOOT = "reboot";
    private static final String REASON_USERREQUESTED = "shutdown,userrequested";
    private static final String REASON_THERMAL_SHUTDOWN = "shutdown,thermal";
    private static final String REASON_LOW_BATTERY = "shutdown,battery";
    private static final String REASON_BATTERY_THERMAL_STATE = "shutdown,thermal,battery";

    private static final String TRACE_SCREEN_ON = "Screen turning on";

    /** If turning screen on takes more than this long, we show a warning on logcat. */
    private static final int SCREEN_ON_LATENCY_WARNING_MS = 200;

    /** Constants for {@link #shutdownOrRebootInternal} */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HALT_MODE_SHUTDOWN, HALT_MODE_REBOOT, HALT_MODE_REBOOT_SAFE_MODE})
    public @interface HaltMode {}
    private static final int HALT_MODE_SHUTDOWN = 0;
    private static final int HALT_MODE_REBOOT = 1;
    private static final int HALT_MODE_REBOOT_SAFE_MODE = 2;

    // Persistent property for last reboot reason
    private static final String LAST_REBOOT_PROPERTY = "persist.sys.boot.reason";

    private final Context mContext;
    private final ServiceThread mHandlerThread;
    private final PowerManagerHandler mHandler;
    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private final BatterySaverPolicy mBatterySaverPolicy;
    private final BatterySaverController mBatterySaverController;
    private final BatterySaverStateMachine mBatterySaverStateMachine;
    private final BatterySavingStats mBatterySavingStats;

    private LightsManager mLightsManager;
    private BatteryManagerInternal mBatteryManagerInternal;
    private DisplayManagerInternal mDisplayManagerInternal;
    private IBatteryStats mBatteryStats;
    private IAppOpsService mAppOps;
    private WindowManagerPolicy mPolicy;
    private Notifier mNotifier;
    private WirelessChargerDetector mWirelessChargerDetector;
    private SettingsObserver mSettingsObserver;
    private DreamManagerInternal mDreamManager;
    private Light mAttentionLight;

    private final Object mLock = LockGuard.installNewLock(LockGuard.INDEX_POWER);

    // A bitfield that indicates what parts of the power state have
    // changed and need to be recalculated.
    private int mDirty;

    // Indicates whether the device is awake or asleep or somewhere in between.
    // This is distinct from the screen power state, which is managed separately.
    private int mWakefulness;
    private boolean mWakefulnessChanging;

    // True if the sandman has just been summoned for the first time since entering the
    // dreaming or dozing state.  Indicates whether a new dream should begin.
    private boolean mSandmanSummoned;

    // True if MSG_SANDMAN has been scheduled.
    private boolean mSandmanScheduled;

    // Table of all suspend blockers.
    // There should only be a few of these.
    private final ArrayList<SuspendBlocker> mSuspendBlockers = new ArrayList<SuspendBlocker>();

    // Table of all wake locks acquired by applications.
    private final ArrayList<WakeLock> mWakeLocks = new ArrayList<WakeLock>();

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
    private long mLastWakeTime;
    private long mLastSleepTime;

    // Timestamp of the last call to user activity.
    private long mLastUserActivityTime;
    private long mLastUserActivityTimeNoChangeLights;

    // Timestamp of last interactive power hint.
    private long mLastInteractivePowerHintTime;

    // Timestamp of the last screen brightness boost.
    private long mLastScreenBrightnessBoostTime;
    private boolean mScreenBrightnessBoostInProgress;

    // A bitfield that summarizes the effect of the user activity timer.
    private int mUserActivitySummary;

    // The desired display power state.  The actual state may lag behind the
    // requested because it is updated asynchronously by the display power controller.
    private final DisplayPowerRequest mDisplayPowerRequest = new DisplayPowerRequest();

    // True if the display power state has been fully applied, which means the display
    // is actually on or actually off or whatever was requested.
    private boolean mDisplayReady;

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

    // True if boot completed occurred.  We keep the screen on until this happens.
    private boolean mBootCompleted;

    // Runnables that should be triggered on boot completed
    private Runnable[] mBootCompletedRunnables;

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

    // The sleep timeout setting value in milliseconds.
    private long mSleepTimeoutSetting;

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

    // Screen brightness setting limits.
    private int mScreenBrightnessSettingMinimum;
    private int mScreenBrightnessSettingMaximum;
    private int mScreenBrightnessSettingDefault;

    // The screen brightness setting, from 0 to 255.
    // Use -1 if no value has been set.
    private int mScreenBrightnessSetting;

    // The screen brightness mode.
    // One of the Settings.System.SCREEN_BRIGHTNESS_MODE_* constants.
    private int mScreenBrightnessModeSetting;

    // The screen brightness setting override from the window manager
    // to allow the current foreground activity to override the brightness.
    // Use -1 to disable.
    private int mScreenBrightnessOverrideFromWindowManager = -1;

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

    // Set of app ids that we will always respect the wake locks for.
    int[] mDeviceIdleWhitelist = new int[0];

    // Set of app ids that are temporarily allowed to acquire wakelocks due to high-pri message
    int[] mDeviceIdleTempWhitelist = new int[0];

    private final SparseArray<UidState> mUidState = new SparseArray<>();

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

    private final class ForegroundProfileObserver extends SynchronousUserSwitchObserver {
        @Override
        public void onUserSwitching(int newUserId) throws RemoteException {}

        @Override
        public void onForegroundProfileSwitch(@UserIdInt int newProfileId) throws RemoteException {
            final long now = SystemClock.uptimeMillis();
            synchronized(mLock) {
                mForegroundProfile = newProfileId;
                maybeUpdateForegroundProfileLastActivityLocked(now);
            }
        }
    }

    // User id corresponding to activity the user is currently interacting with.
    private @UserIdInt int mForegroundProfile;

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

        public ProfilePowerState(@UserIdInt int userId, long screenOffTimeout) {
            mUserId = userId;
            mScreenOffTimeout = screenOffTimeout;
            // Not accurate but at least won't cause immediate locking of the profile.
            mLastUserActivityTime = SystemClock.uptimeMillis();
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

    final Constants mConstants;

    private native void nativeInit();

    private static native void nativeAcquireSuspendBlocker(String name);
    private static native void nativeReleaseSuspendBlocker(String name);
    private static native void nativeSetInteractive(boolean enable);
    private static native void nativeSetAutoSuspend(boolean enable);
    private static native void nativeSendPowerHint(int hintId, int data);
    private static native void nativeSetFeature(int featureId, int data);

    public PowerManagerService(Context context) {
        super(context);
        mContext = context;
        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DISPLAY, false /*allowIo*/);
        mHandlerThread.start();
        mHandler = new PowerManagerHandler(mHandlerThread.getLooper());
        mConstants = new Constants(mHandler);
        mAmbientDisplayConfiguration = new AmbientDisplayConfiguration(mContext);

        mBatterySavingStats = new BatterySavingStats(mLock);
        mBatterySaverPolicy = new BatterySaverPolicy(mLock, mContext, mBatterySavingStats);
        mBatterySaverController = new BatterySaverController(mLock, mContext,
                BackgroundThread.get().getLooper(), mBatterySaverPolicy, mBatterySavingStats);
        mBatterySaverStateMachine = new BatterySaverStateMachine(
                mLock, mContext, mBatterySaverController);

        synchronized (mLock) {
            mWakeLockSuspendBlocker = createSuspendBlockerLocked("PowerManagerService.WakeLocks");
            mDisplaySuspendBlocker = createSuspendBlockerLocked("PowerManagerService.Display");
            mDisplaySuspendBlocker.acquire();
            mHoldingDisplaySuspendBlocker = true;
            mHalAutoSuspendModeEnabled = false;
            mHalInteractiveModeEnabled = true;

            mWakefulness = WAKEFULNESS_AWAKE;

            sQuiescent = SystemProperties.get(SYSTEM_PROPERTY_QUIESCENT, "0").equals("1");

            nativeInit();
            nativeSetAutoSuspend(false);
            nativeSetInteractive(true);
            nativeSetFeature(POWER_FEATURE_DOUBLE_TAP_TO_WAKE, 0);
        }
    }

    @VisibleForTesting
    PowerManagerService(Context context, BatterySaverPolicy batterySaverPolicy) {
        super(context);

        mContext = context;
        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DISPLAY, false /*allowIo*/);
        mHandlerThread.start();
        mHandler = new PowerManagerHandler(mHandlerThread.getLooper());
        mConstants = new Constants(mHandler);
        mAmbientDisplayConfiguration = new AmbientDisplayConfiguration(mContext);
        mDisplaySuspendBlocker = null;
        mWakeLockSuspendBlocker = null;

        mBatterySavingStats = new BatterySavingStats(mLock);
        mBatterySaverPolicy = batterySaverPolicy;
        mBatterySaverController = new BatterySaverController(mLock, context,
                BackgroundThread.getHandler().getLooper(), batterySaverPolicy, mBatterySavingStats);
        mBatterySaverStateMachine = new BatterySaverStateMachine(
                mLock, mContext, mBatterySaverController);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.POWER_SERVICE, new BinderService());
        publishLocalService(PowerManagerInternal.class, new LocalService());

        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler);
    }

    @Override
    public void onBootPhase(int phase) {
        synchronized (mLock) {
            if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                incrementBootCount();

            } else if (phase == PHASE_BOOT_COMPLETED) {
                final long now = SystemClock.uptimeMillis();
                mBootCompleted = true;
                mDirty |= DIRTY_BOOT_COMPLETED;

                mBatterySaverStateMachine.onBootCompleted();
                userActivityNoUpdateLocked(
                        now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
                updatePowerStateLocked();

                if (!ArrayUtils.isEmpty(mBootCompletedRunnables)) {
                    Slog.d(TAG, "Posting " + mBootCompletedRunnables.length + " delayed runnables");
                    for (Runnable r : mBootCompletedRunnables) {
                        BackgroundThread.getHandler().post(r);
                    }
                }
                mBootCompletedRunnables = null;
            }
        }
    }

    public void systemReady(IAppOpsService appOps) {
        synchronized (mLock) {
            mSystemReady = true;
            mAppOps = appOps;
            mDreamManager = getLocalService(DreamManagerInternal.class);
            mDisplayManagerInternal = getLocalService(DisplayManagerInternal.class);
            mPolicy = getLocalService(WindowManagerPolicy.class);
            mBatteryManagerInternal = getLocalService(BatteryManagerInternal.class);

            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mScreenBrightnessSettingMinimum = pm.getMinimumScreenBrightnessSetting();
            mScreenBrightnessSettingMaximum = pm.getMaximumScreenBrightnessSetting();
            mScreenBrightnessSettingDefault = pm.getDefaultScreenBrightnessSetting();

            SensorManager sensorManager = new SystemSensorManager(mContext, mHandler.getLooper());

            // The notifier runs on the system server's main looper so as not to interfere
            // with the animations and other critical functions of the power manager.
            mBatteryStats = BatteryStatsService.getService();
            mNotifier = new Notifier(Looper.getMainLooper(), mContext, mBatteryStats,
                    createSuspendBlockerLocked("PowerManagerService.Broadcasts"), mPolicy);

            mWirelessChargerDetector = new WirelessChargerDetector(sensorManager,
                    createSuspendBlockerLocked("PowerManagerService.WirelessChargerDetector"),
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
        IVrManager vrManager = (IVrManager) getBinderService(Context.VR_SERVICE);
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

    private void readConfigurationLocked() {
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
                nativeSetFeature(POWER_FEATURE_DOUBLE_TAP_TO_WAKE, mDoubleTapWakeEnabled ? 1 : 0);
            }
        }

        final String retailDemoValue = UserManager.isDeviceInDemoMode(mContext) ? "1" : "0";
        if (!retailDemoValue.equals(SystemProperties.get(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED))) {
            SystemProperties.set(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED, retailDemoValue);
        }

        mScreenBrightnessModeSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);

        mDirty |= DIRTY_SETTINGS;
    }

    private void postAfterBootCompleted(Runnable r) {
        if (mBootCompleted) {
            BackgroundThread.getHandler().post(r);
        } else {
            Slog.d(TAG, "Delaying runnable until system is booted");
            mBootCompletedRunnables = ArrayUtils.appendElement(Runnable.class,
                    mBootCompletedRunnables, r);
        }
    }

    private void handleSettingsChangedLocked() {
        updateSettingsLocked();
        updatePowerStateLocked();
    }

    private void acquireWakeLockInternal(IBinder lock, int flags, String tag, String packageName,
            WorkSource ws, String historyTag, int uid, int pid) {
        synchronized (mLock) {
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
                if (!wakeLock.hasSameProperties(flags, tag, ws, uid, pid)) {
                    // Update existing wake lock.  This shouldn't happen but is harmless.
                    notifyWakeLockChangingLocked(wakeLock, flags, tag, packageName,
                            uid, pid, ws, historyTag);
                    wakeLock.updateProperties(flags, tag, packageName, ws, historyTag, uid, pid);
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
                wakeLock = new WakeLock(lock, flags, tag, packageName, ws, historyTag, uid, pid,
                        state);
                try {
                    lock.linkToDeath(wakeLock, 0);
                } catch (RemoteException ex) {
                    throw new IllegalArgumentException("Wake lock is already dead.");
                }
                mWakeLocks.add(wakeLock);
                setWakeLockDisabledStateLocked(wakeLock);
                notifyAcquire = true;
            }

            applyWakeLockFlagsOnAcquireLocked(wakeLock, uid);
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

    private void applyWakeLockFlagsOnAcquireLocked(WakeLock wakeLock, int uid) {
        if ((wakeLock.mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0
                && isScreenLock(wakeLock)) {
            String opPackageName;
            int opUid;
            if (wakeLock.mWorkSource != null && wakeLock.mWorkSource.getName(0) != null) {
                opPackageName = wakeLock.mWorkSource.getName(0);
                opUid = wakeLock.mWorkSource.get(0);
            } else {
                opPackageName = wakeLock.mPackageName;
                opUid = wakeLock.mWorkSource != null ? wakeLock.mWorkSource.get(0)
                        : wakeLock.mOwnerUid;
            }
            wakeUpNoUpdateLocked(SystemClock.uptimeMillis(), wakeLock.mTag, opUid,
                    opPackageName, opUid);
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

            wakeLock.mLock.unlinkToDeath(wakeLock, 0);
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

    private void applyWakeLockFlagsOnReleaseLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.ON_AFTER_RELEASE) != 0
                && isScreenLock(wakeLock)) {
            userActivityNoUpdateLocked(SystemClock.uptimeMillis(),
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
                        ws, historyTag);
                wakeLock.mHistoryTag = historyTag;
                wakeLock.updateWorkSource(ws);
            }
        }
    }

    private int findWakeLockIndexLocked(IBinder lock) {
        final int count = mWakeLocks.size();
        for (int i = 0; i < count; i++) {
            if (mWakeLocks.get(i).mLock == lock) {
                return i;
            }
        }
        return -1;
    }

    private void notifyWakeLockAcquiredLocked(WakeLock wakeLock) {
        if (mSystemReady && !wakeLock.mDisabled) {
            wakeLock.mNotifiedAcquired = true;
            mNotifier.onWakeLockAcquired(wakeLock.mFlags, wakeLock.mTag, wakeLock.mPackageName,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource,
                    wakeLock.mHistoryTag);
            restartNofifyLongTimerLocked(wakeLock);
        }
    }

    private void enqueueNotifyLongMsgLocked(long time) {
        mNotifyLongScheduled = time;
        Message msg = mHandler.obtainMessage(MSG_CHECK_FOR_LONG_WAKELOCKS);
        msg.setAsynchronous(true);
        mHandler.sendMessageAtTime(msg, time);
    }

    private void restartNofifyLongTimerLocked(WakeLock wakeLock) {
        wakeLock.mAcquireTime = SystemClock.uptimeMillis();
        if ((wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK)
                == PowerManager.PARTIAL_WAKE_LOCK && mNotifyLongScheduled == 0) {
            enqueueNotifyLongMsgLocked(wakeLock.mAcquireTime + MIN_LONG_WAKE_CHECK_INTERVAL);
        }
    }

    private void notifyWakeLockLongStartedLocked(WakeLock wakeLock) {
        if (mSystemReady && !wakeLock.mDisabled) {
            wakeLock.mNotifiedLong = true;
            mNotifier.onLongPartialWakeLockStart(wakeLock.mTag, wakeLock.mOwnerUid,
                    wakeLock.mWorkSource, wakeLock.mHistoryTag);
        }
    }

    private void notifyWakeLockLongFinishedLocked(WakeLock wakeLock) {
        if (wakeLock.mNotifiedLong) {
            wakeLock.mNotifiedLong = false;
            mNotifier.onLongPartialWakeLockFinish(wakeLock.mTag, wakeLock.mOwnerUid,
                    wakeLock.mWorkSource, wakeLock.mHistoryTag);
        }
    }

    private void notifyWakeLockChangingLocked(WakeLock wakeLock, int flags, String tag,
            String packageName, int uid, int pid, WorkSource ws, String historyTag) {
        if (mSystemReady && wakeLock.mNotifiedAcquired) {
            mNotifier.onWakeLockChanging(wakeLock.mFlags, wakeLock.mTag, wakeLock.mPackageName,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource,
                    wakeLock.mHistoryTag, flags, tag, packageName, uid, pid, ws, historyTag);
            notifyWakeLockLongFinishedLocked(wakeLock);
            // Changing the wake lock will count as releasing the old wake lock(s) and
            // acquiring the new ones...  we do this because otherwise once a wakelock
            // becomes long, if we just continued to treat it as long we can get in to
            // situations where we spam battery stats with every following change to it.
            restartNofifyLongTimerLocked(wakeLock);
        }
    }

    private void notifyWakeLockReleasedLocked(WakeLock wakeLock) {
        if (mSystemReady && wakeLock.mNotifiedAcquired) {
            wakeLock.mNotifiedAcquired = false;
            wakeLock.mAcquireTime = 0;
            mNotifier.onWakeLockReleased(wakeLock.mFlags, wakeLock.mTag,
                    wakeLock.mPackageName, wakeLock.mOwnerUid, wakeLock.mOwnerPid,
                    wakeLock.mWorkSource, wakeLock.mHistoryTag);
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
    private void userActivityFromNative(long eventTime, int event, int flags) {
        userActivityInternal(eventTime, event, flags, Process.SYSTEM_UID);
    }

    private void userActivityInternal(long eventTime, int event, int flags, int uid) {
        synchronized (mLock) {
            if (userActivityNoUpdateLocked(eventTime, event, flags, uid)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean userActivityNoUpdateLocked(long eventTime, int event, int flags, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "userActivityNoUpdateLocked: eventTime=" + eventTime
                    + ", event=" + event + ", flags=0x" + Integer.toHexString(flags)
                    + ", uid=" + uid);
        }

        if (eventTime < mLastSleepTime || eventTime < mLastWakeTime
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "userActivity");
        try {
            if (eventTime > mLastInteractivePowerHintTime) {
                powerHintInternal(PowerHint.INTERACTION, 0);
                mLastInteractivePowerHintTime = eventTime;
            }

            mNotifier.onUserActivity(event, uid);

            if (mUserInactiveOverrideFromWindowManager) {
                mUserInactiveOverrideFromWindowManager = false;
                mOverriddenTimeout = -1;
            }

            if (mWakefulness == WAKEFULNESS_ASLEEP
                    || mWakefulness == WAKEFULNESS_DOZING
                    || (flags & PowerManager.USER_ACTIVITY_FLAG_INDIRECT) != 0) {
                return false;
            }

            maybeUpdateForegroundProfileLastActivityLocked(eventTime);

            if ((flags & PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS) != 0) {
                if (eventTime > mLastUserActivityTimeNoChangeLights
                        && eventTime > mLastUserActivityTime) {
                    mLastUserActivityTimeNoChangeLights = eventTime;
                    mDirty |= DIRTY_USER_ACTIVITY;
                    if (event == PowerManager.USER_ACTIVITY_EVENT_BUTTON) {
                        mDirty |= DIRTY_QUIESCENT;
                    }

                    return true;
                }
            } else {
                if (eventTime > mLastUserActivityTime) {
                    mLastUserActivityTime = eventTime;
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

    private void maybeUpdateForegroundProfileLastActivityLocked(long eventTime) {
        final ProfilePowerState profile = mProfilePowerState.get(mForegroundProfile);
        if (profile != null && eventTime > profile.mLastUserActivityTime) {
            profile.mLastUserActivityTime = eventTime;
        }
    }

    private void wakeUpInternal(long eventTime, String reason, int uid, String opPackageName,
            int opUid) {
        synchronized (mLock) {
            if (wakeUpNoUpdateLocked(eventTime, reason, uid, opPackageName, opUid)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean wakeUpNoUpdateLocked(long eventTime, String reason, int reasonUid,
            String opPackageName, int opUid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "wakeUpNoUpdateLocked: eventTime=" + eventTime + ", uid=" + reasonUid);
        }

        if (eventTime < mLastSleepTime || mWakefulness == WAKEFULNESS_AWAKE
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, TRACE_SCREEN_ON, 0);

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "wakeUp");
        try {
            switch (mWakefulness) {
                case WAKEFULNESS_ASLEEP:
                    Slog.i(TAG, "Waking up from sleep (uid=" + reasonUid + " reason=" + reason
                            + ")...");
                    break;
                case WAKEFULNESS_DREAMING:
                    Slog.i(TAG, "Waking up from dream (uid=" + reasonUid + " reason=" + reason
                            + ")...");
                    break;
                case WAKEFULNESS_DOZING:
                    Slog.i(TAG, "Waking up from dozing (uid=" + reasonUid + " reason=" + reason
                            + ")...");
                    break;
            }

            mLastWakeTime = eventTime;
            setWakefulnessLocked(WAKEFULNESS_AWAKE, 0);

            mNotifier.onWakeUp(reason, reasonUid, opPackageName, opUid);
            userActivityNoUpdateLocked(
                    eventTime, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, reasonUid);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    private void goToSleepInternal(long eventTime, int reason, int flags, int uid) {
        synchronized (mLock) {
            if (goToSleepNoUpdateLocked(eventTime, reason, flags, uid)) {
                updatePowerStateLocked();
            }
        }
    }

    // This method is called goToSleep for historical reasons but we actually start
    // dozing before really going to sleep.
    @SuppressWarnings("deprecation")
    private boolean goToSleepNoUpdateLocked(long eventTime, int reason, int flags, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "goToSleepNoUpdateLocked: eventTime=" + eventTime
                    + ", reason=" + reason + ", flags=" + flags + ", uid=" + uid);
        }

        if (eventTime < mLastWakeTime
                || mWakefulness == WAKEFULNESS_ASLEEP
                || mWakefulness == WAKEFULNESS_DOZING
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "goToSleep");
        try {
            switch (reason) {
                case PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN:
                    Slog.i(TAG, "Going to sleep due to device administration policy "
                            + "(uid " + uid +")...");
                    break;
                case PowerManager.GO_TO_SLEEP_REASON_TIMEOUT:
                    Slog.i(TAG, "Going to sleep due to screen timeout (uid " + uid +")...");
                    break;
                case PowerManager.GO_TO_SLEEP_REASON_LID_SWITCH:
                    Slog.i(TAG, "Going to sleep due to lid switch (uid " + uid +")...");
                    break;
                case PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON:
                    Slog.i(TAG, "Going to sleep due to power button (uid " + uid +")...");
                    break;
                case PowerManager.GO_TO_SLEEP_REASON_SLEEP_BUTTON:
                    Slog.i(TAG, "Going to sleep due to sleep button (uid " + uid +")...");
                    break;
                case PowerManager.GO_TO_SLEEP_REASON_HDMI:
                    Slog.i(TAG, "Going to sleep due to HDMI standby (uid " + uid +")...");
                    break;
                case PowerManager.GO_TO_SLEEP_REASON_ACCESSIBILITY:
                    Slog.i(TAG, "Going to sleep by an accessibility service request (uid "
                            + uid +")...");
                    break;
                default:
                    Slog.i(TAG, "Going to sleep by application request (uid " + uid +")...");
                    reason = PowerManager.GO_TO_SLEEP_REASON_APPLICATION;
                    break;
            }

            mLastSleepTime = eventTime;
            mSandmanSummoned = true;
            setWakefulnessLocked(WAKEFULNESS_DOZING, reason);

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

            // Skip dozing if requested.
            if ((flags & PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE) != 0) {
                reallyGoToSleepNoUpdateLocked(eventTime, uid);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    private void napInternal(long eventTime, int uid) {
        synchronized (mLock) {
            if (napNoUpdateLocked(eventTime, uid)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean napNoUpdateLocked(long eventTime, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "napNoUpdateLocked: eventTime=" + eventTime + ", uid=" + uid);
        }

        if (eventTime < mLastWakeTime || mWakefulness != WAKEFULNESS_AWAKE
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "nap");
        try {
            Slog.i(TAG, "Nap time (uid " + uid +")...");

            mSandmanSummoned = true;
            setWakefulnessLocked(WAKEFULNESS_DREAMING, 0);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    // Done dozing, drop everything and go to sleep.
    private boolean reallyGoToSleepNoUpdateLocked(long eventTime, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "reallyGoToSleepNoUpdateLocked: eventTime=" + eventTime
                    + ", uid=" + uid);
        }

        if (eventTime < mLastWakeTime || mWakefulness == WAKEFULNESS_ASLEEP
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "reallyGoToSleep");
        try {
            Slog.i(TAG, "Sleeping (uid " + uid +")...");

            setWakefulnessLocked(WAKEFULNESS_ASLEEP, PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
        return true;
    }

    @VisibleForTesting
    void setWakefulnessLocked(int wakefulness, int reason) {
        if (mWakefulness != wakefulness) {
            mWakefulness = wakefulness;
            mWakefulnessChanging = true;
            mDirty |= DIRTY_WAKEFULNESS;
            if (mNotifier != null) {
                mNotifier.onWakefulnessChangeStarted(wakefulness, reason);
            }
        }
    }

    /**
     * Logs the time the device would have spent awake before user activity timeout,
     * had the system not been told the user was inactive.
     */
    private void logSleepTimeoutRecapturedLocked() {
        final long now = SystemClock.uptimeMillis();
        final long savedWakeTimeMs = mOverriddenTimeout - now;
        if (savedWakeTimeMs >= 0) {
            EventLogTags.writePowerSoftSleepRequested(savedWakeTimeMs);
            mOverriddenTimeout = -1;
        }
    }

    private void logScreenOn() {
        Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, TRACE_SCREEN_ON, 0);

        final int latencyMs = (int) (SystemClock.uptimeMillis() - mLastWakeTime);

        LogMaker log = new LogMaker(MetricsEvent.SCREEN);
        log.setType(MetricsEvent.TYPE_OPEN);
        log.setSubtype(0); // not user initiated
        log.setLatency(latencyMs); // How long it took.
        MetricsLogger.action(log);
        EventLogTags.writePowerScreenState(1, 0, 0, 0, latencyMs);

        if (latencyMs >= SCREEN_ON_LATENCY_WARNING_MS) {
            Slog.w(TAG, "Screen on took " + latencyMs+ " ms");
        }
    }

    private void finishWakefulnessChangeIfNeededLocked() {
        if (mWakefulnessChanging && mDisplayReady) {
            if (mWakefulness == WAKEFULNESS_DOZING
                    && (mWakeLockSummary & WAKE_LOCK_DOZE) == 0) {
                return; // wait until dream has enabled dozing
            }
            if (mWakefulness == WAKEFULNESS_DOZING || mWakefulness == WAKEFULNESS_ASLEEP) {
                logSleepTimeoutRecapturedLocked();
            }
            if (mWakefulness == WAKEFULNESS_AWAKE) {
                logScreenOn();
            }
            mWakefulnessChanging = false;
            mNotifier.onWakefulnessChangeFinished();
        }
    }

    /**
     * Updates the global power state based on dirty bits recorded in mDirty.
     *
     * This is the main function that performs power state transitions.
     * We centralize them here so that we can recompute the power state completely
     * each time something important changes, and ensure that we do it the same
     * way each time.  The point is to gather all of the transition logic here.
     */
    private void updatePowerStateLocked() {
        if (!mSystemReady || mDirty == 0) {
            return;
        }
        if (!Thread.holdsLock(mLock)) {
            Slog.wtf(TAG, "Power manager lock was not held when calling updatePowerStateLocked");
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "updatePowerState");
        try {
            // Phase 0: Basic state updates.
            updateIsPoweredLocked(mDirty);
            updateStayOnLocked(mDirty);
            updateScreenBrightnessBoostLocked(mDirty);

            // Phase 1: Update wakefulness.
            // Loop because the wake lock and user activity computations are influenced
            // by changes in wakefulness.
            final long now = SystemClock.uptimeMillis();
            int dirtyPhase2 = 0;
            for (;;) {
                int dirtyPhase1 = mDirty;
                dirtyPhase2 |= dirtyPhase1;
                mDirty = 0;

                updateWakeLockSummaryLocked(dirtyPhase1);
                updateUserActivitySummaryLocked(now, dirtyPhase1);
                if (!updateWakefulnessLocked(dirtyPhase1)) {
                    break;
                }
            }

            // Phase 2: Lock profiles that became inactive/not kept awake.
            updateProfilesLocked(now);

            // Phase 3: Update display power state.
            final boolean displayBecameReady = updateDisplayPowerStateLocked(dirtyPhase2);

            // Phase 4: Update dream state (depends on display ready signal).
            updateDreamLocked(dirtyPhase2, displayBecameReady);

            // Phase 5: Send notifications, if needed.
            finishWakefulnessChangeIfNeededLocked();

            // Phase 6: Update suspend blocker.
            // Because we might release the last suspend blocker here, we need to make sure
            // we finished everything else first!
            updateSuspendBlockerLocked();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    /**
     * Check profile timeouts and notify profiles that should be locked.
     */
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
    private void updateIsPoweredLocked(int dirty) {
        if ((dirty & DIRTY_BATTERY_STATE) != 0) {
            final boolean wasPowered = mIsPowered;
            final int oldPlugType = mPlugType;
            final boolean oldLevelLow = mBatteryLevelLow;
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
                final long now = SystemClock.uptimeMillis();
                if (shouldWakeUpWhenPluggedOrUnpluggedLocked(wasPowered, oldPlugType,
                        dockedOnWirelessCharger)) {
                    wakeUpNoUpdateLocked(now, "android.server.power:POWER", Process.SYSTEM_UID,
                            mContext.getOpPackageName(), Process.SYSTEM_UID);
                }
                userActivityNoUpdateLocked(
                        now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);

                // only play charging sounds if boot is completed so charging sounds don't play
                // with potential notification sounds
                if (mBootCompleted) {
                    if (mIsPowered && !BatteryManager.isPlugWired(oldPlugType)
                            && BatteryManager.isPlugWired(mPlugType)) {
                        mNotifier.onWiredChargingStarted();
                    } else if (dockedOnWirelessCharger) {
                        mNotifier.onWirelessChargingStarted(mBatteryLevel);
                    }
                }
            }

            mBatterySaverStateMachine.setBatteryStatus(mIsPowered, mBatteryLevel, mBatteryLevelLow);
        }
    }

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
        if (mIsPowered && mWakefulness == WAKEFULNESS_DREAMING) {
            return false;
        }

        // Don't wake while theater mode is enabled.
        if (mTheaterModeEnabled && !mWakeUpWhenPluggedOrUnpluggedInTheaterModeConfig) {
            return false;
        }

        // On Always On Display, SystemUI shows the charging indicator
        if (mAlwaysOnEnabled && mWakefulness == WAKEFULNESS_DOZING) {
            return false;
        }

        // Otherwise wake up!
        return true;
    }

    /**
     * Updates the value of mStayOn.
     * Sets DIRTY_STAY_ON if a change occurred.
     */
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
     * This function must have no other side-effects.
     */
    @SuppressWarnings("deprecation")
    private void updateWakeLockSummaryLocked(int dirty) {
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_WAKEFULNESS)) != 0) {
            mWakeLockSummary = 0;

            final int numProfiles = mProfilePowerState.size();
            for (int i = 0; i < numProfiles; i++) {
                mProfilePowerState.valueAt(i).mWakeLockSummary = 0;
            }

            final int numWakeLocks = mWakeLocks.size();
            for (int i = 0; i < numWakeLocks; i++) {
                final WakeLock wakeLock = mWakeLocks.get(i);
                final int wakeLockFlags = getWakeLockSummaryFlags(wakeLock);
                mWakeLockSummary |= wakeLockFlags;
                for (int j = 0; j < numProfiles; j++) {
                    final ProfilePowerState profile = mProfilePowerState.valueAt(j);
                    if (wakeLockAffectsUser(wakeLock, profile.mUserId)) {
                        profile.mWakeLockSummary |= wakeLockFlags;
                    }
                }
            }

            mWakeLockSummary = adjustWakeLockSummaryLocked(mWakeLockSummary);
            for (int i = 0; i < numProfiles; i++) {
                final ProfilePowerState profile = mProfilePowerState.valueAt(i);
                profile.mWakeLockSummary = adjustWakeLockSummaryLocked(profile.mWakeLockSummary);
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockSummaryLocked: mWakefulness="
                        + PowerManagerInternal.wakefulnessToString(mWakefulness)
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            }
        }
    }

    private int adjustWakeLockSummaryLocked(int wakeLockSummary) {
        // Cancel wake locks that make no sense based on the current state.
        if (mWakefulness != WAKEFULNESS_DOZING) {
            wakeLockSummary &= ~(WAKE_LOCK_DOZE | WAKE_LOCK_DRAW);
        }
        if (mWakefulness == WAKEFULNESS_ASLEEP
                || (wakeLockSummary & WAKE_LOCK_DOZE) != 0) {
            wakeLockSummary &= ~(WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_SCREEN_DIM
                    | WAKE_LOCK_BUTTON_BRIGHT);
            if (mWakefulness == WAKEFULNESS_ASLEEP) {
                wakeLockSummary &= ~WAKE_LOCK_PROXIMITY_SCREEN_OFF;
            }
        }

        // Infer implied wake locks where necessary based on the current state.
        if ((wakeLockSummary & (WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_SCREEN_DIM)) != 0) {
            if (mWakefulness == WAKEFULNESS_AWAKE) {
                wakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_STAY_AWAKE;
            } else if (mWakefulness == WAKEFULNESS_DREAMING) {
                wakeLockSummary |= WAKE_LOCK_CPU;
            }
        }
        if ((wakeLockSummary & WAKE_LOCK_DRAW) != 0) {
            wakeLockSummary |= WAKE_LOCK_CPU;
        }

        return wakeLockSummary;
    }

    /** Get wake lock summary flags that correspond to the given wake lock. */
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
                final int uid = wakeLock.mWorkSource.get(k);
                if (userId == UserHandle.getUserId(uid)) {
                    return true;
                }
            }

            final ArrayList<WorkChain> workChains = wakeLock.mWorkSource.getWorkChains();
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
            final long now = SystemClock.uptimeMillis();
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
    private void updateUserActivitySummaryLocked(long now, int dirty) {
        // Update the status of the user activity timeout timer.
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY
                | DIRTY_WAKEFULNESS | DIRTY_SETTINGS)) != 0) {
            mHandler.removeMessages(MSG_USER_ACTIVITY_TIMEOUT);

            long nextTimeout = 0;
            if (mWakefulness == WAKEFULNESS_AWAKE
                    || mWakefulness == WAKEFULNESS_DREAMING
                    || mWakefulness == WAKEFULNESS_DOZING) {
                final long sleepTimeout = getSleepTimeoutLocked();
                final long screenOffTimeout = getScreenOffTimeoutLocked(sleepTimeout);
                final long screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
                final boolean userInactiveOverride = mUserInactiveOverrideFromWindowManager;
                final long nextProfileTimeout = getNextProfileTimeoutLocked(now);

                mUserActivitySummary = 0;
                if (mLastUserActivityTime >= mLastWakeTime) {
                    nextTimeout = mLastUserActivityTime
                            + screenOffTimeout - screenDimDuration;
                    if (now < nextTimeout) {
                        mUserActivitySummary = USER_ACTIVITY_SCREEN_BRIGHT;
                    } else {
                        nextTimeout = mLastUserActivityTime + screenOffTimeout;
                        if (now < nextTimeout) {
                            mUserActivitySummary = USER_ACTIVITY_SCREEN_DIM;
                        }
                    }
                }
                if (mUserActivitySummary == 0
                        && mLastUserActivityTimeNoChangeLights >= mLastWakeTime) {
                    nextTimeout = mLastUserActivityTimeNoChangeLights + screenOffTimeout;
                    if (now < nextTimeout) {
                        if (mDisplayPowerRequest.policy == DisplayPowerRequest.POLICY_BRIGHT
                                || mDisplayPowerRequest.policy == DisplayPowerRequest.POLICY_VR) {
                            mUserActivitySummary = USER_ACTIVITY_SCREEN_BRIGHT;
                        } else if (mDisplayPowerRequest.policy == DisplayPowerRequest.POLICY_DIM) {
                            mUserActivitySummary = USER_ACTIVITY_SCREEN_DIM;
                        }
                    }
                }

                if (mUserActivitySummary == 0) {
                    if (sleepTimeout >= 0) {
                        final long anyUserActivity = Math.max(mLastUserActivityTime,
                                mLastUserActivityTimeNoChangeLights);
                        if (anyUserActivity >= mLastWakeTime) {
                            nextTimeout = anyUserActivity + sleepTimeout;
                            if (now < nextTimeout) {
                                mUserActivitySummary = USER_ACTIVITY_SCREEN_DREAM;
                            }
                        }
                    } else {
                        mUserActivitySummary = USER_ACTIVITY_SCREEN_DREAM;
                        nextTimeout = -1;
                    }
                }

                if (mUserActivitySummary != USER_ACTIVITY_SCREEN_DREAM && userInactiveOverride) {
                    if ((mUserActivitySummary &
                            (USER_ACTIVITY_SCREEN_BRIGHT | USER_ACTIVITY_SCREEN_DIM)) != 0) {
                        // Device is being kept awake by recent user activity
                        if (nextTimeout >= now && mOverriddenTimeout == -1) {
                            // Save when the next timeout would have occurred
                            mOverriddenTimeout = nextTimeout;
                        }
                    }
                    mUserActivitySummary = USER_ACTIVITY_SCREEN_DREAM;
                    nextTimeout = -1;
                }

                if (nextProfileTimeout > 0) {
                    nextTimeout = Math.min(nextTimeout, nextProfileTimeout);
                }

                if (mUserActivitySummary != 0 && nextTimeout >= 0) {
                    scheduleUserInactivityTimeout(nextTimeout);
                }
            } else {
                mUserActivitySummary = 0;
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateUserActivitySummaryLocked: mWakefulness="
                        + PowerManagerInternal.wakefulnessToString(mWakefulness)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary)
                        + ", nextTimeout=" + TimeUtils.formatUptime(nextTimeout));
            }
        }
    }

    private void scheduleUserInactivityTimeout(long timeMs) {
        final Message msg = mHandler.obtainMessage(MSG_USER_ACTIVITY_TIMEOUT);
        msg.setAsynchronous(true);
        mHandler.sendMessageAtTime(msg, timeMs);
    }

    /**
     * Finds the next profile timeout time or returns -1 if there are no profiles to be locked.
     */
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

    private long getSleepTimeoutLocked() {
        final long timeout = mSleepTimeoutSetting;
        if (timeout <= 0) {
            return -1;
        }
        return Math.max(timeout, mMinimumScreenOffTimeoutConfig);
    }

    private long getScreenOffTimeoutLocked(long sleepTimeout) {
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
        return Math.max(timeout, mMinimumScreenOffTimeoutConfig);
    }

    private long getScreenDimDurationLocked(long screenOffTimeout) {
        return Math.min(mMaximumScreenDimDurationConfig,
                (long)(screenOffTimeout * mMaximumScreenDimRatioConfig));
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
    private boolean updateWakefulnessLocked(int dirty) {
        boolean changed = false;
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_BOOT_COMPLETED
                | DIRTY_WAKEFULNESS | DIRTY_STAY_ON | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_DOCK_STATE)) != 0) {
            if (mWakefulness == WAKEFULNESS_AWAKE && isItBedTimeYetLocked()) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateWakefulnessLocked: Bed time...");
                }
                final long time = SystemClock.uptimeMillis();
                if (shouldNapAtBedTimeLocked()) {
                    changed = napNoUpdateLocked(time, Process.SYSTEM_UID);
                } else {
                    changed = goToSleepNoUpdateLocked(time,
                            PowerManager.GO_TO_SLEEP_REASON_TIMEOUT, 0, Process.SYSTEM_UID);
                }
            }
        }
        return changed;
    }

    /**
     * Returns true if the device should automatically nap and start dreaming when the user
     * activity timeout has expired and it's bedtime.
     */
    private boolean shouldNapAtBedTimeLocked() {
        return mDreamsActivateOnSleepSetting
                || (mDreamsActivateOnDockSetting
                        && mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED);
    }

    /**
     * Returns true if the device should go to sleep now.
     * Also used when exiting a dream to determine whether we should go back
     * to being fully awake or else go to sleep for good.
     */
    private boolean isItBedTimeYetLocked() {
        return mBootCompleted && !isBeingKeptAwakeLocked();
    }

    /**
     * Returns true if the device is being kept awake by a wake lock, user activity
     * or the stay on while powered setting.  We also keep the phone awake when
     * the proximity sensor returns a positive result so that the device does not
     * lock while in a phone call.  This function only controls whether the device
     * will go to sleep or dream which is independent of whether it will be allowed
     * to suspend.
     */
    private boolean isBeingKeptAwakeLocked() {
        return mStayOn
                || mProximityPositive
                || (mWakeLockSummary & WAKE_LOCK_STAY_AWAKE) != 0
                || (mUserActivitySummary & (USER_ACTIVITY_SCREEN_BRIGHT
                        | USER_ACTIVITY_SCREEN_DIM)) != 0
                || mScreenBrightnessBoostInProgress;
    }

    /**
     * Determines whether to post a message to the sandman to update the dream state.
     */
    private void updateDreamLocked(int dirty, boolean displayBecameReady) {
        if ((dirty & (DIRTY_WAKEFULNESS
                | DIRTY_USER_ACTIVITY
                | DIRTY_WAKE_LOCKS
                | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS
                | DIRTY_IS_POWERED
                | DIRTY_STAY_ON
                | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_BATTERY_STATE)) != 0 || displayBecameReady) {
            if (mDisplayReady) {
                scheduleSandmanLocked();
            }
        }
    }

    private void scheduleSandmanLocked() {
        if (!mSandmanScheduled) {
            mSandmanScheduled = true;
            Message msg = mHandler.obtainMessage(MSG_SANDMAN);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Called when the device enters or exits a dreaming or dozing state.
     *
     * We do this asynchronously because we must call out of the power manager to start
     * the dream and we don't want to hold our lock while doing so.  There is a risk that
     * the device will wake or go to sleep in the meantime so we have to handle that case.
     */
    private void handleSandman() { // runs on handler thread
        // Handle preconditions.
        final boolean startDreaming;
        final int wakefulness;
        synchronized (mLock) {
            mSandmanScheduled = false;
            wakefulness = mWakefulness;
            if (mSandmanSummoned && mDisplayReady) {
                startDreaming = canDreamLocked() || canDozeLocked();
                mSandmanSummoned = false;
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
                mDreamManager.stopDream(false /*immediate*/);
                mDreamManager.startDream(wakefulness == WAKEFULNESS_DOZING);
            }
            isDreaming = mDreamManager.isDreaming();
        } else {
            isDreaming = false;
        }

        // Update dream state.
        synchronized (mLock) {
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
            if (mSandmanSummoned || mWakefulness != wakefulness) {
                return; // wait for next cycle
            }

            // Determine whether the dream should continue.
            if (wakefulness == WAKEFULNESS_DREAMING) {
                if (isDreaming && canDreamLocked()) {
                    if (mDreamsBatteryLevelDrainCutoffConfig >= 0
                            && mBatteryLevel < mBatteryLevelWhenDreamStarted
                                    - mDreamsBatteryLevelDrainCutoffConfig
                            && !isBeingKeptAwakeLocked()) {
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
                if (isItBedTimeYetLocked()) {
                    goToSleepNoUpdateLocked(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_TIMEOUT, 0, Process.SYSTEM_UID);
                    updatePowerStateLocked();
                } else {
                    wakeUpNoUpdateLocked(SystemClock.uptimeMillis(), "android.server.power:DREAM",
                            Process.SYSTEM_UID, mContext.getOpPackageName(), Process.SYSTEM_UID);
                    updatePowerStateLocked();
                }
            } else if (wakefulness == WAKEFULNESS_DOZING) {
                if (isDreaming) {
                    return; // continue dozing
                }

                // Doze has ended or will be stopped.  Update the power state.
                reallyGoToSleepNoUpdateLocked(SystemClock.uptimeMillis(), Process.SYSTEM_UID);
                updatePowerStateLocked();
            }
        }

        // Stop dream.
        if (isDreaming) {
            mDreamManager.stopDream(false /*immediate*/);
        }
    }

    /**
     * Returns true if the device is allowed to dream in its current state.
     */
    private boolean canDreamLocked() {
        if (mWakefulness != WAKEFULNESS_DREAMING
                || !mDreamsSupportedConfig
                || !mDreamsEnabledSetting
                || !mDisplayPowerRequest.isBrightOrDim()
                || mDisplayPowerRequest.isVr()
                || (mUserActivitySummary & (USER_ACTIVITY_SCREEN_BRIGHT
                        | USER_ACTIVITY_SCREEN_DIM | USER_ACTIVITY_SCREEN_DREAM)) == 0
                || !mBootCompleted) {
            return false;
        }
        if (!isBeingKeptAwakeLocked()) {
            if (!mIsPowered && !mDreamsEnabledOnBatteryConfig) {
                return false;
            }
            if (!mIsPowered
                    && mDreamsBatteryLevelMinimumWhenNotPoweredConfig >= 0
                    && mBatteryLevel < mDreamsBatteryLevelMinimumWhenNotPoweredConfig) {
                return false;
            }
            if (mIsPowered
                    && mDreamsBatteryLevelMinimumWhenPoweredConfig >= 0
                    && mBatteryLevel < mDreamsBatteryLevelMinimumWhenPoweredConfig) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the device is allowed to doze in its current state.
     */
    private boolean canDozeLocked() {
        return mWakefulness == WAKEFULNESS_DOZING;
    }

    /**
     * Updates the display power state asynchronously.
     * When the update is finished, mDisplayReady will be set to true.  The display
     * controller posts a message to tell us when the actual display power state
     * has been updated so we come back here to double-check and finish up.
     *
     * This function recalculates the display power state each time.
     *
     * @return True if the display became ready.
     */
    private boolean updateDisplayPowerStateLocked(int dirty) {
        final boolean oldDisplayReady = mDisplayReady;
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_WAKEFULNESS
                | DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS | DIRTY_SCREEN_BRIGHTNESS_BOOST | DIRTY_VR_MODE_CHANGED |
                DIRTY_QUIESCENT)) != 0) {
            mDisplayPowerRequest.policy = getDesiredScreenPolicyLocked();

            // Determine appropriate screen brightness and auto-brightness adjustments.
            final boolean autoBrightness;
            final int screenBrightnessOverride;
            if (!mBootCompleted) {
                // Keep the brightness steady during boot. This requires the
                // bootloader brightness and the default brightness to be identical.
                autoBrightness = false;
                screenBrightnessOverride = mScreenBrightnessSettingDefault;
            } else if (isValidBrightness(mScreenBrightnessOverrideFromWindowManager)) {
                autoBrightness = false;
                screenBrightnessOverride = mScreenBrightnessOverrideFromWindowManager;
            } else {
                autoBrightness = (mScreenBrightnessModeSetting ==
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                screenBrightnessOverride = -1;
            }

            // Update display power request.
            mDisplayPowerRequest.screenBrightnessOverride = screenBrightnessOverride;
            mDisplayPowerRequest.useAutoBrightness = autoBrightness;
            mDisplayPowerRequest.useProximitySensor = shouldUseProximitySensorLocked();
            mDisplayPowerRequest.boostScreenBrightness = shouldBoostScreenBrightness();

            updatePowerRequestFromBatterySaverPolicy(mDisplayPowerRequest);

            if (mDisplayPowerRequest.policy == DisplayPowerRequest.POLICY_DOZE) {
                mDisplayPowerRequest.dozeScreenState = mDozeScreenStateOverrideFromDreamManager;
                if ((mWakeLockSummary & WAKE_LOCK_DRAW) != 0
                        && !mDrawWakeLockOverrideFromSidekick) {
                    if (mDisplayPowerRequest.dozeScreenState == Display.STATE_DOZE_SUSPEND) {
                        mDisplayPowerRequest.dozeScreenState = Display.STATE_DOZE;
                    }
                    if (mDisplayPowerRequest.dozeScreenState == Display.STATE_ON_SUSPEND) {
                        mDisplayPowerRequest.dozeScreenState = Display.STATE_ON;
                    }
                }
                mDisplayPowerRequest.dozeScreenBrightness =
                        mDozeScreenBrightnessOverrideFromDreamManager;
            } else {
                mDisplayPowerRequest.dozeScreenState = Display.STATE_UNKNOWN;
                mDisplayPowerRequest.dozeScreenBrightness = PowerManager.BRIGHTNESS_DEFAULT;
            }

            mDisplayReady = mDisplayManagerInternal.requestPowerState(mDisplayPowerRequest,
                    mRequestWaitForNegativeProximity);
            mRequestWaitForNegativeProximity = false;

            if ((dirty & DIRTY_QUIESCENT) != 0) {
                sQuiescent = false;
            }
            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateDisplayPowerStateLocked: mDisplayReady=" + mDisplayReady
                        + ", policy=" + mDisplayPowerRequest.policy
                        + ", mWakefulness=" + mWakefulness
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary)
                        + ", mBootCompleted=" + mBootCompleted
                        + ", screenBrightnessOverride=" + screenBrightnessOverride
                        + ", useAutoBrightness=" + autoBrightness
                        + ", mScreenBrightnessBoostInProgress=" + mScreenBrightnessBoostInProgress
                        + ", mIsVrModeEnabled= " + mIsVrModeEnabled
                        + ", sQuiescent=" + sQuiescent);
            }
        }
        return mDisplayReady && !oldDisplayReady;
    }

    private void updateScreenBrightnessBoostLocked(int dirty) {
        if ((dirty & DIRTY_SCREEN_BRIGHTNESS_BOOST) != 0) {
            if (mScreenBrightnessBoostInProgress) {
                final long now = SystemClock.uptimeMillis();
                mHandler.removeMessages(MSG_SCREEN_BRIGHTNESS_BOOST_TIMEOUT);
                if (mLastScreenBrightnessBoostTime > mLastSleepTime) {
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
                mNotifier.onScreenBrightnessBoostChanged();
                userActivityNoUpdateLocked(now,
                        PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
            }
        }
    }

    private boolean shouldBoostScreenBrightness() {
        return !mIsVrModeEnabled && mScreenBrightnessBoostInProgress;
    }

    private static boolean isValidBrightness(int value) {
        return value >= 0 && value <= 255;
    }

    @VisibleForTesting
    int getDesiredScreenPolicyLocked() {
        if (mWakefulness == WAKEFULNESS_ASLEEP || sQuiescent) {
            return DisplayPowerRequest.POLICY_OFF;
        }

        if (mWakefulness == WAKEFULNESS_DOZING) {
            if ((mWakeLockSummary & WAKE_LOCK_DOZE) != 0) {
                return DisplayPowerRequest.POLICY_DOZE;
            }
            if (mDozeAfterScreenOff) {
                return DisplayPowerRequest.POLICY_OFF;
            }
            // Fall through and preserve the current screen policy if not configured to
            // doze after screen off.  This causes the screen off transition to be skipped.
        }

        // It is important that POLICY_VR check happens after the wakefulness checks above so
        // that VR-mode does not prevent displays from transitioning to the correct state when
        // dozing or sleeping.
        if (mIsVrModeEnabled) {
            return DisplayPowerRequest.POLICY_VR;
        }

        if ((mWakeLockSummary & WAKE_LOCK_SCREEN_BRIGHT) != 0
                || (mUserActivitySummary & USER_ACTIVITY_SCREEN_BRIGHT) != 0
                || !mBootCompleted
                || mScreenBrightnessBoostInProgress) {
            return DisplayPowerRequest.POLICY_BRIGHT;
        }

        return DisplayPowerRequest.POLICY_DIM;
    }

    private final DisplayManagerInternal.DisplayPowerCallbacks mDisplayPowerCallbacks =
            new DisplayManagerInternal.DisplayPowerCallbacks() {
        private int mDisplayState = Display.STATE_UNKNOWN;

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
                mDirty |= DIRTY_PROXIMITY_POSITIVE;
                userActivityNoUpdateLocked(SystemClock.uptimeMillis(),
                        PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
                updatePowerStateLocked();
            }
        }

        @Override
        public void onDisplayStateChange(int state) {
            // This method is only needed to support legacy display blanking behavior
            // where the display's power state is coupled to suspend or to the power HAL.
            // The order of operations matters here.
            synchronized (mLock) {
                if (mDisplayState != state) {
                    mDisplayState = state;
                    if (state == Display.STATE_OFF) {
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
        }

        @Override
        public void acquireSuspendBlocker() {
            mDisplaySuspendBlocker.acquire();
        }

        @Override
        public void releaseSuspendBlocker() {
            mDisplaySuspendBlocker.release();
        }

        @Override
        public String toString() {
            synchronized (this) {
                return "state=" + Display.stateToString(mDisplayState);
            }
        }
    };

    private boolean shouldUseProximitySensorLocked() {
        return !mIsVrModeEnabled && (mWakeLockSummary & WAKE_LOCK_PROXIMITY_SCREEN_OFF) != 0;
    }

    /**
     * Updates the suspend blocker that keeps the CPU alive.
     *
     * This function must have no other side-effects.
     */
    private void updateSuspendBlockerLocked() {
        final boolean needWakeLockSuspendBlocker = ((mWakeLockSummary & WAKE_LOCK_CPU) != 0);
        final boolean needDisplaySuspendBlocker = needDisplaySuspendBlockerLocked();
        final boolean autoSuspend = !needDisplaySuspendBlocker;
        final boolean interactive = mDisplayPowerRequest.isBrightOrDim();

        // Disable auto-suspend if needed.
        // FIXME We should consider just leaving auto-suspend enabled forever since
        // we already hold the necessary wakelocks.
        if (!autoSuspend && mDecoupleHalAutoSuspendModeFromDisplayConfig) {
            setHalAutoSuspendModeLocked(false);
        }

        // First acquire suspend blockers if needed.
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
            if (interactive || mDisplayReady) {
                setHalInteractiveModeLocked(interactive);
            }
        }

        // Then release suspend blockers if needed.
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
     * Return true if we must keep a suspend blocker active on behalf of the display.
     * We do so if the screen is on or is in transition between states.
     */
    private boolean needDisplaySuspendBlockerLocked() {
        if (!mDisplayReady) {
            return true;
        }
        if (mDisplayPowerRequest.isBrightOrDim()) {
            // If we asked for the screen to be on but it is off due to the proximity
            // sensor then we may suspend but only if the configuration allows it.
            // On some hardware it may not be safe to suspend because the proximity
            // sensor may not be correctly configured as a wake-up source.
            if (!mDisplayPowerRequest.useProximitySensor || !mProximityPositive
                    || !mSuspendWhenScreenOffDueToProximityConfig) {
                return true;
            }
        }
        if (mScreenBrightnessBoostInProgress) {
            return true;
        }
        // Let the system suspend if the screen is off or dozing.
        return false;
    }

    private void setHalAutoSuspendModeLocked(boolean enable) {
        if (enable != mHalAutoSuspendModeEnabled) {
            if (DEBUG) {
                Slog.d(TAG, "Setting HAL auto-suspend mode to " + enable);
            }
            mHalAutoSuspendModeEnabled = enable;
            Trace.traceBegin(Trace.TRACE_TAG_POWER, "setHalAutoSuspend(" + enable + ")");
            try {
                nativeSetAutoSuspend(enable);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            }
        }
    }

    private void setHalInteractiveModeLocked(boolean enable) {
        if (enable != mHalInteractiveModeEnabled) {
            if (DEBUG) {
                Slog.d(TAG, "Setting HAL interactive mode to " + enable);
            }
            mHalInteractiveModeEnabled = enable;
            Trace.traceBegin(Trace.TRACE_TAG_POWER, "setHalInteractive(" + enable + ")");
            try {
                nativeSetInteractive(enable);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            }
        }
    }

    private boolean isInteractiveInternal() {
        synchronized (mLock) {
            return PowerManagerInternal.isInteractive(mWakefulness);
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

    private void handleBatteryStateChangedLocked() {
        mDirty |= DIRTY_BATTERY_STATE;
        updatePowerStateLocked();
    }

    private void shutdownOrRebootInternal(final @HaltMode int haltMode, final boolean confirm,
            final String reason, boolean wait) {
        if (mHandler == null || !mSystemReady) {
            if (RescueParty.isAttemptingFactoryReset()) {
                // If we're stuck in a really low-level reboot loop, and a
                // rescue party is trying to prompt the user for a factory data
                // reset, we must GET TO DA CHOPPA!
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

    @VisibleForTesting
    void updatePowerRequestFromBatterySaverPolicy(DisplayPowerRequest displayPowerRequest) {
        PowerSaveState state = mBatterySaverPolicy.
                getBatterySaverPolicy(ServiceType.SCREEN_BRIGHTNESS,
                        mBatterySaverController.isEnabled());
        displayPowerRequest.lowPowerMode = state.batterySaverEnabled;
        displayPowerRequest.screenLowPowerBrightnessFactor = state.brightnessFactor;
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
                    mProfilePowerState.put(userId, new ProfilePowerState(userId, timeMs));
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
                if (mDeviceIdleMode) {
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
                if (mDeviceIdleMode && state.mNumWakeLocks > 0) {
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

    private boolean setWakeLockDisabledStateLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK)
                == PowerManager.PARTIAL_WAKE_LOCK) {
            boolean disabled = false;
            final int appid = UserHandle.getAppId(wakeLock.mOwnerUid);
            if (appid >= Process.FIRST_APPLICATION_UID) {
                // Cached inactive processes are never allowed to hold wake locks.
                if (mConstants.NO_CACHED_WAKE_LOCKS) {
                    disabled = !wakeLock.mUidState.mActive &&
                            wakeLock.mUidState.mProcState
                                    != ActivityManager.PROCESS_STATE_NONEXISTENT &&
                            wakeLock.mUidState.mProcState > ActivityManager.PROCESS_STATE_RECEIVER;
                }
                if (mDeviceIdleMode) {
                    // If we are in idle mode, we will also ignore all partial wake locks that are
                    // for application uids that are not whitelisted.
                    final UidState state = wakeLock.mUidState;
                    if (Arrays.binarySearch(mDeviceIdleWhitelist, appid) < 0 &&
                            Arrays.binarySearch(mDeviceIdleTempWhitelist, appid) < 0 &&
                            state.mProcState != ActivityManager.PROCESS_STATE_NONEXISTENT &&
                            state.mProcState >
                                    ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
                        disabled = true;
                    }
                }
            }
            if (wakeLock.mDisabled != disabled) {
                wakeLock.mDisabled = disabled;
                return true;
            }
        }
        return false;
    }

    private boolean isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() {
        return mMaximumScreenOffTimeoutFromDeviceAdmin >= 0
                && mMaximumScreenOffTimeoutFromDeviceAdmin < Long.MAX_VALUE;
    }

    private void setAttentionLightInternal(boolean on, int color) {
        Light light;
        synchronized (mLock) {
            if (!mSystemReady) {
                return;
            }
            light = mAttentionLight;
        }

        // Control light outside of lock.
        light.setFlashing(color, Light.LIGHT_FLASH_HARDWARE, (on ? 3 : 0), 0);
    }

    private void setDozeAfterScreenOffInternal(boolean on) {
        synchronized (mLock) {
            mDozeAfterScreenOff = on;
        }
    }

    private void boostScreenBrightnessInternal(long eventTime, int uid) {
        synchronized (mLock) {
            if (!mSystemReady || mWakefulness == WAKEFULNESS_ASLEEP
                    || eventTime < mLastScreenBrightnessBoostTime) {
                return;
            }

            Slog.i(TAG, "Brightness boost activated (uid " + uid +")...");
            mLastScreenBrightnessBoostTime = eventTime;
            if (!mScreenBrightnessBoostInProgress) {
                mScreenBrightnessBoostInProgress = true;
                mNotifier.onScreenBrightnessBoostChanged();
            }
            mDirty |= DIRTY_SCREEN_BRIGHTNESS_BOOST;

            userActivityNoUpdateLocked(eventTime,
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

    private void setScreenBrightnessOverrideFromWindowManagerInternal(int brightness) {
        synchronized (mLock) {
            if (mScreenBrightnessOverrideFromWindowManager != brightness) {
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

    private void powerHintInternal(int hintId, int data) {
        // Maybe filter the event.
        switch (hintId) {
            case PowerHint.LAUNCH: // 1: activate launch boost 0: deactivate.
                if (data == 1 && mBatterySaverController.isLaunchBoostDisabled()) {
                    return;
                }
                break;
        }

        nativeSendPowerHint(hintId, data);
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
            reason = reason + ",quiescent";
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

    private void dumpInternal(PrintWriter pw) {
        pw.println("POWER MANAGER (dumpsys power)\n");

        final WirelessChargerDetector wcd;
        synchronized (mLock) {
            pw.println("Power Manager State:");
            mConstants.dump(pw);
            pw.println("  mDirty=0x" + Integer.toHexString(mDirty));
            pw.println("  mWakefulness=" + PowerManagerInternal.wakefulnessToString(mWakefulness));
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
            pw.println("  mHalAutoSuspendModeEnabled=" + mHalAutoSuspendModeEnabled);
            pw.println("  mHalInteractiveModeEnabled=" + mHalInteractiveModeEnabled);
            pw.println("  mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            pw.print("  mNotifyLongScheduled=");
            if (mNotifyLongScheduled == 0) {
                pw.print("(none)");
            } else {
                TimeUtils.formatDuration(mNotifyLongScheduled, SystemClock.uptimeMillis(), pw);
            }
            pw.println();
            pw.print("  mNotifyLongDispatched=");
            if (mNotifyLongDispatched == 0) {
                pw.print("(none)");
            } else {
                TimeUtils.formatDuration(mNotifyLongDispatched, SystemClock.uptimeMillis(), pw);
            }
            pw.println();
            pw.print("  mNotifyLongNextCheck=");
            if (mNotifyLongNextCheck == 0) {
                pw.print("(none)");
            } else {
                TimeUtils.formatDuration(mNotifyLongNextCheck, SystemClock.uptimeMillis(), pw);
            }
            pw.println();
            pw.println("  mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary));
            pw.println("  mRequestWaitForNegativeProximity=" + mRequestWaitForNegativeProximity);
            pw.println("  mSandmanScheduled=" + mSandmanScheduled);
            pw.println("  mSandmanSummoned=" + mSandmanSummoned);
            pw.println("  mBatteryLevelLow=" + mBatteryLevelLow);
            pw.println("  mLightDeviceIdleMode=" + mLightDeviceIdleMode);
            pw.println("  mDeviceIdleMode=" + mDeviceIdleMode);
            pw.println("  mDeviceIdleWhitelist=" + Arrays.toString(mDeviceIdleWhitelist));
            pw.println("  mDeviceIdleTempWhitelist=" + Arrays.toString(mDeviceIdleTempWhitelist));
            pw.println("  mLastWakeTime=" + TimeUtils.formatUptime(mLastWakeTime));
            pw.println("  mLastSleepTime=" + TimeUtils.formatUptime(mLastSleepTime));
            pw.println("  mLastUserActivityTime=" + TimeUtils.formatUptime(mLastUserActivityTime));
            pw.println("  mLastUserActivityTimeNoChangeLights="
                    + TimeUtils.formatUptime(mLastUserActivityTimeNoChangeLights));
            pw.println("  mLastInteractivePowerHintTime="
                    + TimeUtils.formatUptime(mLastInteractivePowerHintTime));
            pw.println("  mLastScreenBrightnessBoostTime="
                    + TimeUtils.formatUptime(mLastScreenBrightnessBoostTime));
            pw.println("  mScreenBrightnessBoostInProgress="
                    + mScreenBrightnessBoostInProgress);
            pw.println("  mDisplayReady=" + mDisplayReady);
            pw.println("  mHoldingWakeLockSuspendBlocker=" + mHoldingWakeLockSuspendBlocker);
            pw.println("  mHoldingDisplaySuspendBlocker=" + mHoldingDisplaySuspendBlocker);

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
            pw.println("  mScreenOffTimeoutSetting=" + mScreenOffTimeoutSetting);
            pw.println("  mSleepTimeoutSetting=" + mSleepTimeoutSetting);
            pw.println("  mMaximumScreenOffTimeoutFromDeviceAdmin="
                    + mMaximumScreenOffTimeoutFromDeviceAdmin + " (enforced="
                    + isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() + ")");
            pw.println("  mStayOnWhilePluggedInSetting=" + mStayOnWhilePluggedInSetting);
            pw.println("  mScreenBrightnessSetting=" + mScreenBrightnessSetting);
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
            pw.println("  mScreenBrightnessSettingMinimum=" + mScreenBrightnessSettingMinimum);
            pw.println("  mScreenBrightnessSettingMaximum=" + mScreenBrightnessSettingMaximum);
            pw.println("  mScreenBrightnessSettingDefault=" + mScreenBrightnessSettingDefault);
            pw.println("  mDoubleTapWakeEnabled=" + mDoubleTapWakeEnabled);
            pw.println("  mIsVrModeEnabled=" + mIsVrModeEnabled);
            pw.println("  mForegroundProfile=" + mForegroundProfile);

            final long sleepTimeout = getSleepTimeoutLocked();
            final long screenOffTimeout = getScreenOffTimeoutLocked(sleepTimeout);
            final long screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
            pw.println();
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

            wcd = mWirelessChargerDetector;
        }

        if (wcd != null) {
            wcd.dump(pw);
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final WirelessChargerDetector wcd;
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            mConstants.dumpProto(proto);
            proto.write(PowerManagerServiceDumpProto.DIRTY, mDirty);
            proto.write(PowerManagerServiceDumpProto.WAKEFULNESS, mWakefulness);
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
            proto.write(
                    PowerManagerServiceDumpProto.IS_HAL_AUTO_SUSPEND_MODE_ENABLED,
                    mHalAutoSuspendModeEnabled);
            proto.write(
                    PowerManagerServiceDumpProto.IS_HAL_AUTO_INTERACTIVE_MODE_ENABLED,
                    mHalInteractiveModeEnabled);

            final long activeWakeLocksToken = proto.start(PowerManagerServiceDumpProto.ACTIVE_WAKE_LOCKS);
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

            final long userActivityToken = proto.start(PowerManagerServiceDumpProto.USER_ACTIVITY);
            proto.write(
                    PowerManagerServiceDumpProto.UserActivityProto.IS_SCREEN_BRIGHT,
                    (mUserActivitySummary & USER_ACTIVITY_SCREEN_BRIGHT) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.UserActivityProto.IS_SCREEN_DIM,
                    (mUserActivitySummary & USER_ACTIVITY_SCREEN_DIM) != 0);
            proto.write(
                    PowerManagerServiceDumpProto.UserActivityProto.IS_SCREEN_DREAM,
                    (mUserActivitySummary & USER_ACTIVITY_SCREEN_DREAM) != 0);
            proto.end(userActivityToken);

            proto.write(
                    PowerManagerServiceDumpProto.IS_REQUEST_WAIT_FOR_NEGATIVE_PROXIMITY,
                    mRequestWaitForNegativeProximity);
            proto.write(PowerManagerServiceDumpProto.IS_SANDMAN_SCHEDULED, mSandmanScheduled);
            proto.write(PowerManagerServiceDumpProto.IS_SANDMAN_SUMMONED, mSandmanSummoned);
            proto.write(PowerManagerServiceDumpProto.IS_BATTERY_LEVEL_LOW, mBatteryLevelLow);
            proto.write(PowerManagerServiceDumpProto.IS_LIGHT_DEVICE_IDLE_MODE, mLightDeviceIdleMode);
            proto.write(PowerManagerServiceDumpProto.IS_DEVICE_IDLE_MODE, mDeviceIdleMode);

            for (int id : mDeviceIdleWhitelist) {
                proto.write(PowerManagerServiceDumpProto.DEVICE_IDLE_WHITELIST, id);
            }
            for (int id : mDeviceIdleTempWhitelist) {
                proto.write(PowerManagerServiceDumpProto.DEVICE_IDLE_TEMP_WHITELIST, id);
            }

            proto.write(PowerManagerServiceDumpProto.LAST_WAKE_TIME_MS, mLastWakeTime);
            proto.write(PowerManagerServiceDumpProto.LAST_SLEEP_TIME_MS, mLastSleepTime);
            proto.write(PowerManagerServiceDumpProto.LAST_USER_ACTIVITY_TIME_MS, mLastUserActivityTime);
            proto.write(
                    PowerManagerServiceDumpProto.LAST_USER_ACTIVITY_TIME_NO_CHANGE_LIGHTS_MS,
                    mLastUserActivityTimeNoChangeLights);
            proto.write(
                    PowerManagerServiceDumpProto.LAST_INTERACTIVE_POWER_HINT_TIME_MS,
                    mLastInteractivePowerHintTime);
            proto.write(
                    PowerManagerServiceDumpProto.LAST_SCREEN_BRIGHTNESS_BOOST_TIME_MS,
                    mLastScreenBrightnessBoostTime);
            proto.write(
                    PowerManagerServiceDumpProto.IS_SCREEN_BRIGHTNESS_BOOST_IN_PROGRESS,
                    mScreenBrightnessBoostInProgress);
            proto.write(PowerManagerServiceDumpProto.IS_DISPLAY_READY, mDisplayReady);
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
                            .SETTING_MINIMUM,
                    mScreenBrightnessSettingMinimum);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ScreenBrightnessSettingLimitsProto
                            .SETTING_MAXIMUM,
                    mScreenBrightnessSettingMaximum);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.ScreenBrightnessSettingLimitsProto
                            .SETTING_DEFAULT,
                    mScreenBrightnessSettingDefault);
            proto.end(screenBrightnessSettingLimitsToken);

            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.IS_DOUBLE_TAP_WAKE_ENABLED,
                    mDoubleTapWakeEnabled);
            proto.write(
                    PowerServiceSettingsAndConfigurationDumpProto.IS_VR_MODE_ENABLED,
                    mIsVrModeEnabled);
            proto.end(settingsAndConfigurationToken);

            final long sleepTimeout = getSleepTimeoutLocked();
            final long screenOffTimeout = getScreenOffTimeoutLocked(sleepTimeout);
            final long screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
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

            mHandler.getLooper().writeToProto(proto, PowerManagerServiceDumpProto.LOOPER);

            for (WakeLock wl : mWakeLocks) {
                wl.writeToProto(proto, PowerManagerServiceDumpProto.WAKE_LOCKS);
            }

            for (SuspendBlocker sb : mSuspendBlockers) {
                sb.writeToProto(proto, PowerManagerServiceDumpProto.SUSPEND_BLOCKERS);
            }
            wcd = mWirelessChargerDetector;
        }

        if (wcd != null) {
            wcd.writeToProto(proto, PowerManagerServiceDumpProto.WIRELESS_CHARGER_DETECTOR);
        }
        proto.flush();
    }

    private SuspendBlocker createSuspendBlockerLocked(String name) {
        SuspendBlocker suspendBlocker = new SuspendBlockerImpl(name);
        mSuspendBlockers.add(suspendBlocker);
        return suspendBlocker;
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

    private final class BatteryReceiver extends BroadcastReceiver {
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

    private final class UserSwitchedReceiver extends BroadcastReceiver {
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
            powerHintInternal(PowerHint.VR_MODE, enabled ? 1 : 0);

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
     * Handler for asynchronous operations performed by the power manager.
     */
    private final class PowerManagerHandler extends Handler {
        public PowerManagerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_ACTIVITY_TIMEOUT:
                    handleUserActivityTimeout();
                    break;
                case MSG_SANDMAN:
                    handleSandman();
                    break;
                case MSG_SCREEN_BRIGHTNESS_BOOST_TIMEOUT:
                    handleScreenBrightnessBoostTimeout();
                    break;
                case MSG_CHECK_FOR_LONG_WAKELOCKS:
                    checkForLongWakeLocks();
                    break;
            }
        }
    }

    /**
     * Represents a wake lock that has been acquired by an application.
     */
    private final class WakeLock implements IBinder.DeathRecipient {
        public final IBinder mLock;
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

        public WakeLock(IBinder lock, int flags, String tag, String packageName,
                WorkSource workSource, String historyTag, int ownerUid, int ownerPid,
                UidState uidState) {
            mLock = lock;
            mFlags = flags;
            mTag = tag;
            mPackageName = packageName;
            mWorkSource = copyWorkSource(workSource);
            mHistoryTag = historyTag;
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
            mUidState = uidState;
        }

        @Override
        public void binderDied() {
            PowerManagerService.this.handleWakeLockDeath(this);
        }

        public boolean hasSameProperties(int flags, String tag, WorkSource workSource,
                int ownerUid, int ownerPid) {
            return mFlags == flags
                    && mTag.equals(tag)
                    && hasSameWorkSource(workSource)
                    && mOwnerUid == ownerUid
                    && mOwnerPid == ownerPid;
        }

        public void updateProperties(int flags, String tag, String packageName,
                WorkSource workSource, String historyTag, int ownerUid, int ownerPid) {
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
        }

        public boolean hasSameWorkSource(WorkSource workSource) {
            return Objects.equals(mWorkSource, workSource);
        }

        public void updateWorkSource(WorkSource workSource) {
            mWorkSource = copyWorkSource(workSource);
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
                TimeUtils.formatDuration(mAcquireTime-SystemClock.uptimeMillis(), sb);
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

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            final long wakeLockToken = proto.start(fieldId);
            proto.write(WakeLockProto.LOCK_LEVEL, (mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK));
            proto.write(WakeLockProto.TAG, mTag);

            final long wakeLockFlagsToken = proto.start(WakeLockProto.FLAGS);
            proto.write(WakeLockProto.WakeLockFlagsProto.IS_ACQUIRE_CAUSES_WAKEUP,
                    (mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP)!=0);
            proto.write(WakeLockProto.WakeLockFlagsProto.IS_ON_AFTER_RELEASE,
                    (mFlags & PowerManager.ON_AFTER_RELEASE)!=0);
            proto.end(wakeLockFlagsToken);

            proto.write(WakeLockProto.IS_DISABLED, mDisabled);
            if (mNotifiedAcquired) {
                proto.write(WakeLockProto.ACQ_MS, mAcquireTime);
            }
            proto.write(WakeLockProto.IS_NOTIFIED_LONG, mNotifiedLong);
            proto.write(WakeLockProto.UID, mOwnerUid);
            proto.write(WakeLockProto.PID, mOwnerPid);

            if (mWorkSource != null) {
                mWorkSource.writeToProto(proto, WakeLockProto.WORK_SOURCE);
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
                    nativeReleaseSuspendBlocker(mName);
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
                    nativeAcquireSuspendBlocker(mName);
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
                    nativeReleaseSuspendBlocker(mName);
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

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
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

    private final class BinderService extends IPowerManager.Stub {
        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new PowerManagerShellCommand(this)).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }

        @Override // Binder call
        public void acquireWakeLockWithUid(IBinder lock, int flags, String tag,
                String packageName, int uid) {
            if (uid < 0) {
                uid = Binder.getCallingUid();
            }
            acquireWakeLock(lock, flags, tag, packageName, new WorkSource(uid), null);
        }

        @Override // Binder call
        public void powerHint(int hintId, int data) {
            if (!mSystemReady) {
                // Service not ready yet, so who the heck cares about power hints, bah.
                return;
            }
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
            powerHintInternal(hintId, data);
        }

        @Override // Binder call
        public void acquireWakeLock(IBinder lock, int flags, String tag, String packageName,
                WorkSource ws, String historyTag) {
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

            final int uid = Binder.getCallingUid();
            final int pid = Binder.getCallingPid();
            final long ident = Binder.clearCallingIdentity();
            try {
                acquireWakeLockInternal(lock, flags, tag, packageName, ws, historyTag, uid, pid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
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
        public void updateWakeLockUids(IBinder lock, int[] uids) {
            WorkSource ws = null;

            if (uids != null) {
                ws = new WorkSource();
                // XXX should WorkSource have a way to set uids as an int[] instead of adding them
                // one at a time?
                for (int i = 0; i < uids.length; i++) {
                    ws.add(uids[i]);
                }
            }
            updateWakeLockWorkSource(lock, ws, null);
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
        public boolean isWakeLockLevelSupported(int level) {
            final long ident = Binder.clearCallingIdentity();
            try {
                return isWakeLockLevelSupportedInternal(level);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void userActivity(long eventTime, int event, int flags) {
            final long now = SystemClock.uptimeMillis();
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
                userActivityInternal(eventTime, event, flags, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void wakeUp(long eventTime, String reason, String opPackageName) {
            if (eventTime > SystemClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                wakeUpInternal(eventTime, reason, uid, opPackageName, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void goToSleep(long eventTime, int reason, int flags) {
            if (eventTime > SystemClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                goToSleepInternal(eventTime, reason, flags, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void nap(long eventTime) {
            if (eventTime > SystemClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                napInternal(eventTime, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
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
                return mBatterySaverPolicy.getBatterySaverPolicy(
                        serviceType, mBatterySaverController.isEnabled());
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean setPowerSaveMode(boolean enabled) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
            final long ident = Binder.clearCallingIdentity();
            try {
                return setLowPowerModeInternal(enabled);
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
                return getLastShutdownReasonInternal(LAST_REBOOT_PROPERTY);
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
        public void reboot(boolean confirm, String reason, boolean wait) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);
            if (PowerManager.REBOOT_RECOVERY.equals(reason)
                    || PowerManager.REBOOT_RECOVERY_UPDATE.equals(reason)) {
                mContext.enforceCallingOrSelfPermission(android.Manifest.permission.RECOVERY, null);
            }

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

            final long ident = Binder.clearCallingIdentity();
            try {
                shutdownOrRebootInternal(HALT_MODE_REBOOT_SAFE_MODE, confirm,
                        PowerManager.REBOOT_SAFE_MODE, wait);
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
                        Settings.getPackageNameForUid(mContext, uid), true)) {
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
        public void boostScreenBrightness(long eventTime) {
            if (eventTime > SystemClock.uptimeMillis()) {
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

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            final long ident = Binder.clearCallingIdentity();

            boolean isDumpProto = false;
            for (String arg : args) {
                if (arg.equals("--proto")) {
                    isDumpProto = true;
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
    }

    @VisibleForTesting
    // lastRebootReasonProperty argument to permit testing
    int getLastShutdownReasonInternal(String lastRebootReasonProperty) {
        String line = SystemProperties.get(lastRebootReasonProperty);
        if (line == null) {
            return PowerManager.SHUTDOWN_REASON_UNKNOWN;
        }
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

    private final class LocalService extends PowerManagerInternal {
        @Override
        public void setScreenBrightnessOverrideFromWindowManager(int screenBrightness) {
            if (screenBrightness < PowerManager.BRIGHTNESS_DEFAULT
                    || screenBrightness > PowerManager.BRIGHTNESS_ON) {
                screenBrightness = PowerManager.BRIGHTNESS_DEFAULT;
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
            return mBatterySaverPolicy.getBatterySaverPolicy(serviceType,
                    mBatterySaverController.isEnabled());
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
        public void powerHint(int hintId, int data) {
            powerHintInternal(hintId, data);
        }
    }
}
