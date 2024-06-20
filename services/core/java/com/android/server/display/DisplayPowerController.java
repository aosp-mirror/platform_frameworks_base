/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display;

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DOZE;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE;
import static com.android.server.display.config.DisplayBrightnessMappingConfig.autoBrightnessPresetToString;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal.DisplayOffloadSession;
import android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.FloatProperty;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.util.MutableFloat;
import android.util.MutableInt;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.RingBuffer;
import com.android.server.LocalServices;
import com.android.server.am.BatteryStatsService;
import com.android.server.display.RampAnimator.DualRampAnimator;
import com.android.server.display.brightness.BrightnessEvent;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.BrightnessUtils;
import com.android.server.display.brightness.DisplayBrightnessController;
import com.android.server.display.brightness.clamper.BrightnessClamperController;
import com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy2;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategyConstants;
import com.android.server.display.color.ColorDisplayService.ColorDisplayServiceInternal;
import com.android.server.display.color.ColorDisplayService.ReduceBrightColorsListener;
import com.android.server.display.config.HysteresisLevels;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.layout.Layout;
import com.android.server.display.state.DisplayStateController;
import com.android.server.display.utils.DebugUtils;
import com.android.server.display.utils.SensorUtils;
import com.android.server.display.whitebalance.DisplayWhiteBalanceController;
import com.android.server.display.whitebalance.DisplayWhiteBalanceFactory;
import com.android.server.display.whitebalance.DisplayWhiteBalanceSettings;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Controls the power state of the display.
 *
 * Handles the proximity sensor, light sensor, and animations between states
 * including the screen off animation.
 *
 * This component acts independently of the rest of the power manager service.
 * In particular, it does not share any state and it only communicates
 * via asynchronous callbacks to inform the power manager that something has
 * changed.
 *
 * Everything this class does internally is serialized on its handler although
 * it may be accessed by other threads from the outside.
 *
 * Note that the power manager service guarantees that it will hold a suspend
 * blocker as long as the display is not ready.  So most of the work done here
 * does not need to worry about holding a suspend blocker unless it happens
 * independently of the display ready signal.
 *
 * For debugging, you can make the color fade and brightness animations run
 * slower by changing the "animator duration scale" option in Development Settings.
 */
final class DisplayPowerController implements AutomaticBrightnessController.Callbacks,
        DisplayWhiteBalanceController.Callbacks, DisplayPowerControllerInterface {
    private static final String SCREEN_ON_BLOCKED_TRACE_NAME = "Screen on blocked";
    private static final String SCREEN_OFF_BLOCKED_TRACE_NAME = "Screen off blocked";

    private static final String TAG = "DisplayPowerController2";
    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.DisplayPowerController2 DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);
    private static final String SCREEN_ON_BLOCKED_BY_DISPLAYOFFLOAD_TRACE_NAME =
            "Screen on blocked by displayoffload";

    // If true, uses the color fade on animation.
    // We might want to turn this off if we cannot get a guarantee that the screen
    // actually turns on and starts showing new content after the call to set the
    // screen state returns.  Playing the animation can also be somewhat slow.
    private static final boolean USE_COLOR_FADE_ON_ANIMATION = false;

    private static final float SCREEN_ANIMATION_RATE_MINIMUM = 0.0f;

    private static final int COLOR_FADE_ON_ANIMATION_DURATION_MILLIS = 250;
    private static final int COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS = 400;

    private static final int MSG_UPDATE_POWER_STATE = 1;
    private static final int MSG_SCREEN_ON_UNBLOCKED = 2;
    private static final int MSG_SCREEN_OFF_UNBLOCKED = 3;
    private static final int MSG_CONFIGURE_BRIGHTNESS = 4;
    private static final int MSG_SET_TEMPORARY_BRIGHTNESS = 5;
    private static final int MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT = 6;
    private static final int MSG_STOP = 7;
    private static final int MSG_UPDATE_BRIGHTNESS = 8;
    private static final int MSG_UPDATE_RBC = 9;
    private static final int MSG_BRIGHTNESS_RAMP_DONE = 10;
    private static final int MSG_STATSD_HBM_BRIGHTNESS = 11;
    private static final int MSG_SWITCH_USER = 12;
    private static final int MSG_BOOT_COMPLETED = 13;
    private static final int MSG_SWITCH_AUTOBRIGHTNESS_MODE = 14;
    private static final int MSG_SET_DWBC_COLOR_OVERRIDE = 15;
    private static final int MSG_SET_DWBC_LOGGING_ENABLED = 16;
    private static final int MSG_SET_BRIGHTNESS_FROM_OFFLOAD = 17;
    private static final int MSG_OFFLOADING_SCREEN_ON_UNBLOCKED = 18;



    private static final int BRIGHTNESS_CHANGE_STATSD_REPORT_INTERVAL_MS = 500;


    // State machine constants for tracking initial brightness ramp skipping when enabled.
    private static final int RAMP_STATE_SKIP_NONE = 0;
    private static final int RAMP_STATE_SKIP_INITIAL = 1;
    private static final int RAMP_STATE_SKIP_AUTOBRIGHT = 2;

    private static final int REPORTED_TO_POLICY_UNREPORTED = -1;
    private static final int REPORTED_TO_POLICY_SCREEN_OFF = 0;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_ON = 1;
    private static final int REPORTED_TO_POLICY_SCREEN_ON = 2;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_OFF = 3;

    private static final int RINGBUFFER_MAX = 100;
    private static final int RINGBUFFER_RBC_MAX = 20;

    private static final float[] BRIGHTNESS_RANGE_BOUNDARIES = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 50, 60, 70, 80,
        90, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1200,
        1400, 1600, 1800, 2000, 2250, 2500, 2750, 3000};
    private static final int[] BRIGHTNESS_RANGE_INDEX = {
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_UNKNOWN,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_0_1,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_1_2,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_2_3,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_3_4,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_4_5,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_5_6,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_6_7,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_7_8,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_8_9,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_9_10,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_10_20,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_20_30,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_30_40,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_40_50,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_50_60,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_60_70,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_70_80,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_80_90,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_90_100,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_100_200,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_200_300,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_300_400,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_400_500,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_500_600,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_600_700,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_700_800,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_800_900,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_900_1000,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_1000_1200,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_1200_1400,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_1400_1600,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_1600_1800,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_1800_2000,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_2000_2250,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_2250_2500,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_2500_2750,
        FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_2750_3000,
    };

    private final String mTag;

    private final Object mLock = new Object();

    private final Context mContext;

    // Our handler.
    private final DisplayControllerHandler mHandler;

    // Battery stats.
    @Nullable
    private final IBatteryStats mBatteryStats;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The window manager policy.
    private final WindowManagerPolicy mWindowManagerPolicy;

    // The display blanker.
    private final DisplayBlanker mBlanker;

    // The LogicalDisplay tied to this DisplayPowerController2.
    private final LogicalDisplay mLogicalDisplay;

    // The ID of the LogicalDisplay tied to this DisplayPowerController2.
    private final int mDisplayId;

    // The ID of the display which this display follows for brightness purposes.
    private int mLeadDisplayId = Layout.NO_LEAD_DISPLAY;

    // The unique ID of the primary display device currently tied to this logical display
    private String mUniqueDisplayId;

    // Tracker for brightness changes.
    @Nullable
    private final BrightnessTracker mBrightnessTracker;

    // Tracker for brightness settings changes.
    private final SettingsObserver mSettingsObserver;

    // The doze screen brightness.
    private final float mScreenBrightnessDozeConfig;

    // True if auto-brightness should be used.
    private boolean mUseSoftwareAutoBrightnessConfig;

    // Whether or not the color fade on screen on / off is enabled.
    private final boolean mColorFadeEnabled;

    @GuardedBy("mCachedBrightnessInfo")
    private final CachedBrightnessInfo mCachedBrightnessInfo = new CachedBrightnessInfo();

    private DisplayDevice mDisplayDevice;

    // True if we should fade the screen while turning it off, false if we should play
    // a stylish color fade animation instead.
    private final boolean mColorFadeFadesConfig;

    // True if we need to fake a transition to off when coming out of a doze state.
    // Some display hardware will blank itself when coming out of doze in order to hide
    // artifacts. For these displays we fake a transition into OFF so that policy can appropriately
    // blank itself and begin an appropriate power on animation.
    private final boolean mDisplayBlanksAfterDozeConfig;

    // True if there are only buckets of brightness values when the display is in the doze state,
    // rather than a full range of values. If this is true, then we'll avoid animating the screen
    // brightness since it'd likely be multiple jarring brightness transitions instead of just one
    // to reach the final state.
    private final boolean mBrightnessBucketsInDozeConfig;

    private final Clock mClock;
    private final Injector mInjector;

    // Maximum time a ramp animation can take.
    private long mBrightnessRampIncreaseMaxTimeMillis;
    private long mBrightnessRampDecreaseMaxTimeMillis;

    // Maximum time a ramp animation can take in idle mode.
    private long mBrightnessRampIncreaseMaxTimeIdleMillis;
    private long mBrightnessRampDecreaseMaxTimeIdleMillis;

    // The pending power request.
    // Initially null until the first call to requestPowerState.
    @GuardedBy("mLock")
    private DisplayPowerRequest mPendingRequestLocked;

    // True if the pending power request or wait for negative proximity flag
    // has been changed since the last update occurred.
    @GuardedBy("mLock")
    private boolean mPendingRequestChangedLocked;

    // Set to true when the important parts of the pending power request have been applied.
    // The important parts are mainly the screen state.  Brightness changes may occur
    // concurrently.
    @GuardedBy("mLock")
    private boolean mDisplayReadyLocked;

    // Set to true if a power state update is required.
    @GuardedBy("mLock")
    private boolean mPendingUpdatePowerStateLocked;

    /* The following state must only be accessed by the handler thread. */

    // The currently requested power state.
    // The power controller will progressively update its internal state to match
    // the requested power state.  Initially null until the first update.
    private DisplayPowerRequest mPowerRequest;

    // The current power state.
    // Must only be accessed on the handler thread.
    private DisplayPowerState mPowerState;



    // The currently active screen on unblocker.  This field is non-null whenever
    // we are waiting for a callback to release it and unblock the screen.
    private ScreenOnUnblocker mPendingScreenOnUnblocker;
    private ScreenOffUnblocker mPendingScreenOffUnblocker;
    private Runnable mPendingScreenOnUnblockerByDisplayOffload;

    // True if we were in the process of turning off the screen.
    // This allows us to recover more gracefully from situations where we abort
    // turning off the screen.
    private boolean mPendingScreenOff;

    // The elapsed real time when the screen on was blocked.
    private long mScreenOnBlockStartRealTime;
    private long mScreenOffBlockStartRealTime;
    private long mScreenOnBlockByDisplayOffloadStartRealTime;

    // Screen state we reported to policy. Must be one of REPORTED_TO_POLICY_* fields.
    private int mReportedScreenStateToPolicy = REPORTED_TO_POLICY_UNREPORTED;

    // Used to deduplicate the displayoffload blocking screen on logic. One block per turning on.
    // This value is reset when screen on is reported or the blocking is cancelled.
    private boolean mScreenTurningOnWasBlockedByDisplayOffload;

    // If the last recorded screen state was dozing or not.
    private boolean mDozing;

    private boolean mAppliedDimming;

    private boolean mAppliedThrottling;

    // Reason for which the brightness was last changed. See {@link BrightnessReason} for more
    // information.
    // At the time of this writing, this value is changed within updatePowerState() only, which is
    // limited to the thread used by DisplayControllerHandler.
    @VisibleForTesting
    final BrightnessReason mBrightnessReason = new BrightnessReason();
    private final BrightnessReason mBrightnessReasonTemp = new BrightnessReason();

    // Brightness animation ramp rates in brightness units per second
    private float mBrightnessRampRateFastDecrease;
    private float mBrightnessRampRateFastIncrease;
    private float mBrightnessRampRateSlowDecrease;
    private float mBrightnessRampRateSlowIncrease;
    private float mBrightnessRampRateSlowDecreaseIdle;
    private float mBrightnessRampRateSlowIncreaseIdle;

    // Report HBM brightness change to StatsD
    private int mDisplayStatsId;
    private float mLastStatsBrightness = PowerManager.BRIGHTNESS_MIN;

    // Whether or not to skip the initial brightness ramps into STATE_ON.
    private final boolean mSkipScreenOnBrightnessRamp;

    // Display white balance components.
    // Critical methods must be called on DPC2 handler thread.
    @Nullable
    private final DisplayWhiteBalanceSettings mDisplayWhiteBalanceSettings;
    @Nullable
    private final DisplayWhiteBalanceController mDisplayWhiteBalanceController;

    @Nullable
    private final ColorDisplayServiceInternal mCdsi;
    private float[] mNitsRange;

    private final BrightnessRangeController mBrightnessRangeController;

    private final BrightnessThrottler mBrightnessThrottler;

    private final BrightnessClamperController mBrightnessClamperController;

    private final Runnable mOnBrightnessChangeRunnable;

    private final BrightnessEvent mLastBrightnessEvent;
    private final BrightnessEvent mTempBrightnessEvent;

    private final DisplayBrightnessController mDisplayBrightnessController;

    // Keeps a record of brightness changes for dumpsys.
    private RingBuffer<BrightnessEvent> mBrightnessEventRingBuffer;

    // Keeps a record of rbc changes for dumpsys.
    private final RingBuffer<BrightnessEvent> mRbcEventRingBuffer =
            new RingBuffer<>(BrightnessEvent.class, RINGBUFFER_RBC_MAX);

    // Controls and tracks all the wakelocks that are acquired/released by the system. Also acts as
    // a medium of communication between this class and the PowerManagerService.
    private final WakelockController mWakelockController;

    // Tracks and manages the proximity state of the associated display.
    private final DisplayPowerProximityStateController mDisplayPowerProximityStateController;

    // Tracks and manages the display state of the associated display.
    private final DisplayStateController mDisplayStateController;


    // Responsible for evaluating and tracking the automatic brightness relevant states.
    // Todo: This is a temporary workaround. Ideally DPC2 should never talk to the strategies
    private final AutomaticBrightnessStrategy2 mAutomaticBrightnessStrategy;

    // A record of state for skipping brightness ramps.
    private int mSkipRampState = RAMP_STATE_SKIP_NONE;

    // The first autobrightness value set when entering RAMP_STATE_SKIP_INITIAL.
    private float mInitialAutoBrightness;

    // The controller for the automatic brightness level.
    @Nullable
    private AutomaticBrightnessController mAutomaticBrightnessController;

    // The controller for the sensor used to estimate ambient lux while the display is off.
    @Nullable
    private ScreenOffBrightnessSensorController mScreenOffBrightnessSensorController;

    private Sensor mLightSensor;
    private Sensor mScreenOffBrightnessSensor;

    private boolean mIsRbcActive;

    // Animators.
    private ObjectAnimator mColorFadeOnAnimator;
    private ObjectAnimator mColorFadeOffAnimator;
    private DualRampAnimator<DisplayPowerState> mScreenBrightnessRampAnimator;

    // True if this DisplayPowerController2 has been stopped and should no longer be running.
    private boolean mStopped;

    private DisplayDeviceConfig mDisplayDeviceConfig;

    private boolean mIsEnabled;
    private boolean mIsInTransition;
    private boolean mIsDisplayInternal;

    // The id of the thermal brightness throttling policy that should be used.
    private String mThermalBrightnessThrottlingDataId;

    // DPCs following the brightness of this DPC. This is used in concurrent displays mode - there
    // is one lead display, the additional displays follow the brightness value of the lead display.
    @GuardedBy("mLock")
    private SparseArray<DisplayPowerControllerInterface> mDisplayBrightnessFollowers =
            new SparseArray();

    private boolean mBootCompleted;
    private final DisplayManagerFlags mFlags;

    private DisplayOffloadSession mDisplayOffloadSession;

    // Used to scale the brightness in doze mode
    private float mDozeScaleFactor;

    /**
     * Creates the display power controller.
     */
    DisplayPowerController(Context context, Injector injector,
            DisplayPowerCallbacks callbacks, Handler handler,
            SensorManager sensorManager, DisplayBlanker blanker, LogicalDisplay logicalDisplay,
            BrightnessTracker brightnessTracker, BrightnessSetting brightnessSetting,
            Runnable onBrightnessChangeRunnable, HighBrightnessModeMetadata hbmMetadata,
            boolean bootCompleted, DisplayManagerFlags flags) {
        mFlags = flags;
        mInjector = injector != null ? injector : new Injector();
        mClock = mInjector.getClock();
        mLogicalDisplay = logicalDisplay;
        mDisplayId = mLogicalDisplay.getDisplayIdLocked();
        mSensorManager = sensorManager;
        mHandler = new DisplayControllerHandler(handler.getLooper());
        mDisplayDeviceConfig = logicalDisplay.getPrimaryDisplayDeviceLocked()
                .getDisplayDeviceConfig();
        mIsEnabled = logicalDisplay.isEnabledLocked();
        mIsInTransition = logicalDisplay.isInTransitionLocked();
        mIsDisplayInternal = logicalDisplay.getPrimaryDisplayDeviceLocked()
                .getDisplayDeviceInfoLocked().type == Display.TYPE_INTERNAL;
        mWakelockController = mInjector.getWakelockController(mDisplayId, callbacks);
        mDisplayPowerProximityStateController = mInjector.getDisplayPowerProximityStateController(
                mWakelockController, mDisplayDeviceConfig, mHandler.getLooper(),
                () -> updatePowerState(), mDisplayId, mSensorManager);
        mDisplayStateController = new DisplayStateController(mDisplayPowerProximityStateController);
        mTag = TAG + "[" + mDisplayId + "]";
        mThermalBrightnessThrottlingDataId =
                logicalDisplay.getDisplayInfoLocked().thermalBrightnessThrottlingDataId;
        mDisplayDevice = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        mUniqueDisplayId = logicalDisplay.getPrimaryDisplayDeviceLocked().getUniqueId();
        mDisplayStatsId = mUniqueDisplayId.hashCode();

        mLastBrightnessEvent = new BrightnessEvent(mDisplayId);
        mTempBrightnessEvent = new BrightnessEvent(mDisplayId);

        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mBatteryStats = BatteryStatsService.getService();
        } else {
            mBatteryStats = null;
        }

        mSettingsObserver = new SettingsObserver(mHandler);
        mWindowManagerPolicy = LocalServices.getService(WindowManagerPolicy.class);
        mBlanker = blanker;
        mContext = context;
        mBrightnessTracker = brightnessTracker;
        mOnBrightnessChangeRunnable = onBrightnessChangeRunnable;

        PowerManager pm = context.getSystemService(PowerManager.class);

        final Resources resources = context.getResources();

        // DOZE AND DIM SETTINGS
        mScreenBrightnessDozeConfig = BrightnessUtils.clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DOZE));
        loadBrightnessRampRates();
        mSkipScreenOnBrightnessRamp = resources.getBoolean(
                R.bool.config_skipScreenOnBrightnessRamp);
        mDozeScaleFactor = context.getResources().getFraction(
                R.fraction.config_screenAutoBrightnessDozeScaleFactor,
                1, 1);

        Runnable modeChangeCallback = () -> {
            sendUpdatePowerState();
            postBrightnessChangeRunnable();
            // TODO(b/192258832): Switch the HBMChangeCallback to a listener pattern.
            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.update();
            }
        };

        HighBrightnessModeController hbmController = createHbmControllerLocked(hbmMetadata,
                modeChangeCallback);
        mBrightnessThrottler = createBrightnessThrottlerLocked();

        mBrightnessRangeController = mInjector.getBrightnessRangeController(hbmController,
                modeChangeCallback, mDisplayDeviceConfig, mHandler, flags,
                mDisplayDevice.getDisplayTokenLocked(),
                mDisplayDevice.getDisplayDeviceInfoLocked());

        mDisplayBrightnessController =
                new DisplayBrightnessController(context, null,
                        mDisplayId, mLogicalDisplay.getDisplayInfoLocked().brightnessDefault,
                        brightnessSetting, () -> postBrightnessChangeRunnable(),
                        new HandlerExecutor(mHandler), flags);

        mBrightnessClamperController = mInjector.getBrightnessClamperController(
                mHandler, modeChangeCallback::run,
                new BrightnessClamperController.DisplayDeviceData(
                        mUniqueDisplayId,
                        mThermalBrightnessThrottlingDataId,
                        logicalDisplay.getPowerThrottlingDataIdLocked(),
                        mDisplayDeviceConfig), mContext, flags, mSensorManager);
        // Seed the cached brightness
        saveBrightnessInfo(getScreenBrightnessSetting());
        mAutomaticBrightnessStrategy =
                mDisplayBrightnessController.getAutomaticBrightnessStrategy();

        DisplayWhiteBalanceSettings displayWhiteBalanceSettings = null;
        DisplayWhiteBalanceController displayWhiteBalanceController = null;
        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            try {
                displayWhiteBalanceController = mInjector.getDisplayWhiteBalanceController(
                        mHandler, mSensorManager, resources);
                displayWhiteBalanceSettings = new DisplayWhiteBalanceSettings(mContext, mHandler);
                displayWhiteBalanceSettings.setCallbacks(this);
                displayWhiteBalanceController.setCallbacks(this);
            } catch (Exception e) {
                Slog.e(mTag, "failed to set up display white-balance: " + e);
            }
        }
        mDisplayWhiteBalanceSettings = displayWhiteBalanceSettings;
        mDisplayWhiteBalanceController = displayWhiteBalanceController;

        loadNitsRange(resources);

        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mCdsi = LocalServices.getService(ColorDisplayServiceInternal.class);
            if (mCdsi != null) {
                boolean active = mCdsi.setReduceBrightColorsListener(
                        new ReduceBrightColorsListener() {
                            @Override
                            public void onReduceBrightColorsActivationChanged(boolean activated,
                                    boolean userInitiated) {
                                applyReduceBrightColorsSplineAdjustment();

                            }

                            @Override
                            public void onReduceBrightColorsStrengthChanged(int strength) {
                                applyReduceBrightColorsSplineAdjustment();
                            }
                        });
                if (active) {
                    applyReduceBrightColorsSplineAdjustment();
                }
            }
        } else {
            mCdsi = null;
        }

        setUpAutoBrightness(context, handler);

        mColorFadeEnabled = mInjector.isColorFadeEnabled()
                && !resources.getBoolean(
                  com.android.internal.R.bool.config_displayColorFadeDisabled);
        mColorFadeFadesConfig = resources.getBoolean(
                R.bool.config_animateScreenLights);

        mDisplayBlanksAfterDozeConfig = resources.getBoolean(
                R.bool.config_displayBlanksAfterDoze);

        mBrightnessBucketsInDozeConfig = resources.getBoolean(
                R.bool.config_displayBrightnessBucketsInDoze);

        mBootCompleted = bootCompleted;
    }

    private void applyReduceBrightColorsSplineAdjustment() {
        mHandler.obtainMessage(MSG_UPDATE_RBC).sendToTarget();
        sendUpdatePowerState();
    }

    private void handleRbcChanged() {
        if (mAutomaticBrightnessController == null) {
            return;
        }

        float[] adjustedNits = new float[mNitsRange.length];
        for (int i = 0; i < mNitsRange.length; i++) {
            adjustedNits[i] = mCdsi.getReduceBrightColorsAdjustedBrightnessNits(mNitsRange[i]);
        }
        mIsRbcActive = mCdsi.isReduceBrightColorsActivated();
        mAutomaticBrightnessController.recalculateSplines(mIsRbcActive, adjustedNits);
    }

    /**
     * Returns true if the proximity sensor screen-off function is available.
     */
    @Override
    public boolean isProximitySensorAvailable() {
        return mDisplayPowerProximityStateController.isProximitySensorAvailable();
    }

    /**
     * Get the {@link BrightnessChangeEvent}s for the specified user.
     *
     * @param userId         userId to fetch data for
     * @param includePackage if false will null out the package name in events
     */
    @Nullable
    @Override
    public ParceledListSlice<BrightnessChangeEvent> getBrightnessEvents(
            @UserIdInt int userId, boolean includePackage) {
        if (mBrightnessTracker == null) {
            return null;
        }
        return mBrightnessTracker.getEvents(userId, includePackage);
    }

    @Override
    public void onSwitchUser(@UserIdInt int newUserId, int userSerial, float newBrightness) {
        Message msg = mHandler.obtainMessage(MSG_SWITCH_USER, newUserId, userSerial, newBrightness);
        mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
    }

    private void handleOnSwitchUser(@UserIdInt int newUserId, int userSerial, float newBrightness) {
        Slog.i(mTag, "Switching user newUserId=" + newUserId + " userSerial=" + userSerial
                + " newBrightness=" + newBrightness);
        handleBrightnessModeChange();
        if (mBrightnessTracker != null) {
            mBrightnessTracker.onSwitchUser(newUserId);
        }
        setBrightness(newBrightness, userSerial);

        // Don't treat user switches as user initiated change.
        mDisplayBrightnessController.setAndNotifyCurrentScreenBrightness(newBrightness);

        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.resetShortTermModel();
        }
        sendUpdatePowerState();
    }

    @Nullable
    @Override
    public ParceledListSlice<AmbientBrightnessDayStats> getAmbientBrightnessStats(
            @UserIdInt int userId) {
        if (mBrightnessTracker == null) {
            return null;
        }
        return mBrightnessTracker.getAmbientBrightnessStats(userId);
    }

    /**
     * Persist the brightness slider events and ambient brightness stats to disk.
     */
    @Override
    public void persistBrightnessTrackerState() {
        if (mBrightnessTracker != null) {
            mBrightnessTracker.persistBrightnessTrackerState();
        }
    }

    /**
     * Requests a new power state.
     * The controller makes a copy of the provided object and then
     * begins adjusting the power state to match what was requested.
     *
     * @param request                  The requested power state.
     * @param waitForNegativeProximity If true, issues a request to wait for
     *                                 negative proximity before turning the screen back on,
     *                                 assuming the screen
     *                                 was turned off by the proximity sensor.
     * @return True if display is ready, false if there are important changes that must
     * be made asynchronously (such as turning the screen on), in which case the caller
     * should grab a wake lock, watch for {@link DisplayPowerCallbacks#onStateChanged()}
     * then try the request again later until the state converges.
     */
    public boolean requestPowerState(DisplayPowerRequest request,
            boolean waitForNegativeProximity) {
        if (DEBUG) {
            Slog.d(mTag, "requestPowerState: "
                    + request + ", waitForNegativeProximity=" + waitForNegativeProximity);
        }

        synchronized (mLock) {
            if (mStopped) {
                return true;
            }

            boolean changed = mDisplayPowerProximityStateController
                    .setPendingWaitForNegativeProximityLocked(waitForNegativeProximity);

            if (mPendingRequestLocked == null) {
                mPendingRequestLocked = new DisplayPowerRequest(request);
                changed = true;
            } else if (!mPendingRequestLocked.equals(request)) {
                mPendingRequestLocked.copyFrom(request);
                changed = true;
            }

            if (changed) {
                mDisplayReadyLocked = false;
                if (!mPendingRequestChangedLocked) {
                    mPendingRequestChangedLocked = true;
                    sendUpdatePowerStateLocked();
                }
            }

            return mDisplayReadyLocked;
        }
    }

    @Override
    public void overrideDozeScreenState(int displayState, @Display.StateReason int reason) {
        Slog.i(TAG, "New offload doze override: " + Display.stateToString(displayState));
        mHandler.postAtTime(() -> {
            if (mDisplayOffloadSession == null
                    || !(DisplayOffloadSession.isSupportedOffloadState(displayState)
                            || displayState == Display.STATE_UNKNOWN)) {
                return;
            }
            mDisplayStateController.overrideDozeScreenState(displayState, reason);
            updatePowerState();
        }, mClock.uptimeMillis());
    }

    @Override
    public void setDisplayOffloadSession(DisplayOffloadSession session) {
        if (session == mDisplayOffloadSession) {
            return;
        }
        unblockScreenOnByDisplayOffload();
        mDisplayOffloadSession = session;
    }

    @Override
    public BrightnessConfiguration getDefaultBrightnessConfiguration() {
        if (mAutomaticBrightnessController == null) {
            return null;
        }
        return mAutomaticBrightnessController.getDefaultConfig();
    }

    /**
     * Notified when the display is changed. We use this to apply any changes that might be needed
     * when displays get swapped on foldable devices.  For example, different brightness properties
     * of each display need to be properly reflected in AutomaticBrightnessController.
     *
     * Make sure DisplayManagerService.mSyncRoot lock is held when this is called
     */
    @Override
    public void onDisplayChanged(HighBrightnessModeMetadata hbmMetadata, int leadDisplayId) {
        mLeadDisplayId = leadDisplayId;
        final DisplayDevice device = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        if (device == null) {
            Slog.wtf(mTag, "Display Device is null in DisplayPowerController2 for display: "
                    + mLogicalDisplay.getDisplayIdLocked());
            return;
        }

        final String uniqueId = device.getUniqueId();
        final DisplayDeviceConfig config = device.getDisplayDeviceConfig();
        final IBinder token = device.getDisplayTokenLocked();
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        final boolean isEnabled = mLogicalDisplay.isEnabledLocked();
        final boolean isInTransition = mLogicalDisplay.isInTransitionLocked();
        final boolean isDisplayInternal = mLogicalDisplay.getPrimaryDisplayDeviceLocked() != null
                && mLogicalDisplay.getPrimaryDisplayDeviceLocked()
                .getDisplayDeviceInfoLocked().type == Display.TYPE_INTERNAL;
        final String thermalBrightnessThrottlingDataId =
                mLogicalDisplay.getDisplayInfoLocked().thermalBrightnessThrottlingDataId;
        final String powerThrottlingDataId =
                mLogicalDisplay.getPowerThrottlingDataIdLocked();

        mHandler.postAtTime(() -> {
            boolean changed = false;

            if (mIsEnabled != isEnabled || mIsInTransition != isInTransition) {
                changed = true;
                mIsEnabled = isEnabled;
                mIsInTransition = isInTransition;
            }

            if (mDisplayDevice != device) {
                changed = true;
                mDisplayDevice = device;
                mUniqueDisplayId = uniqueId;
                mDisplayStatsId = mUniqueDisplayId.hashCode();
                mDisplayDeviceConfig = config;
                mThermalBrightnessThrottlingDataId = thermalBrightnessThrottlingDataId;
                loadFromDisplayDeviceConfig(token, info, hbmMetadata);
                mDisplayPowerProximityStateController.notifyDisplayDeviceChanged(config);

                // Since the underlying display-device changed, we really don't know the
                // last command that was sent to change it's state. Let's assume it is unknown so
                // that we trigger a change immediately.
                mPowerState.resetScreenState();
            } else if (!Objects.equals(mThermalBrightnessThrottlingDataId,
                    thermalBrightnessThrottlingDataId)) {
                changed = true;
                mThermalBrightnessThrottlingDataId = thermalBrightnessThrottlingDataId;
                mBrightnessThrottler.loadThermalBrightnessThrottlingDataFromDisplayDeviceConfig(
                        config.getThermalBrightnessThrottlingDataMapByThrottlingId(),
                        config.getTempSensor(),
                        mThermalBrightnessThrottlingDataId,
                        mUniqueDisplayId);
            }

            mIsDisplayInternal = isDisplayInternal;
            // using local variables here, when mBrightnessThrottler is removed,
            // mThermalBrightnessThrottlingDataId could be removed as well
            // changed = true will be not needed - clampers are maintaining their state and
            // will call updatePowerState if needed.
            mBrightnessClamperController.onDisplayChanged(
                    new BrightnessClamperController.DisplayDeviceData(uniqueId,
                        thermalBrightnessThrottlingDataId, powerThrottlingDataId, config));

            if (changed) {
                updatePowerState();
            }
        }, mClock.uptimeMillis());
    }

    /**
     * Unregisters all listeners and interrupts all running threads; halting future work.
     *
     * This method should be called when the DisplayPowerController2 is no longer in use; i.e. when
     * the {@link #mDisplayId display} has been removed.
     */
    @Override
    public void stop() {
        synchronized (mLock) {
            clearDisplayBrightnessFollowersLocked();

            mStopped = true;
            Message msg = mHandler.obtainMessage(MSG_STOP);
            mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());

            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.stop();
            }

            mDisplayBrightnessController.stop();

            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        }
    }

    private void loadFromDisplayDeviceConfig(IBinder token, DisplayDeviceInfo info,
            HighBrightnessModeMetadata hbmMetadata) {
        // All properties that depend on the associated DisplayDevice and the DDC must be
        // updated here.
        loadBrightnessRampRates();
        loadNitsRange(mContext.getResources());
        setUpAutoBrightness(mContext, mHandler);
        reloadReduceBrightColours();
        setAnimatorRampSpeeds(/* isIdleMode= */ false);

        mBrightnessRangeController.loadFromConfig(hbmMetadata, token, info, mDisplayDeviceConfig);
        mBrightnessThrottler.loadThermalBrightnessThrottlingDataFromDisplayDeviceConfig(
                mDisplayDeviceConfig.getThermalBrightnessThrottlingDataMapByThrottlingId(),
                mDisplayDeviceConfig.getTempSensor(),
                mThermalBrightnessThrottlingDataId, mUniqueDisplayId);
    }

    private void sendUpdatePowerState() {
        synchronized (mLock) {
            sendUpdatePowerStateLocked();
        }
    }

    @GuardedBy("mLock")
    private void sendUpdatePowerStateLocked() {
        if (!mStopped && !mPendingUpdatePowerStateLocked) {
            mPendingUpdatePowerStateLocked = true;
            Message msg = mHandler.obtainMessage(MSG_UPDATE_POWER_STATE);
            mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
        }
    }

    private void initialize(int displayState) {
        mPowerState = mInjector.getDisplayPowerState(mBlanker,
                mColorFadeEnabled ? new ColorFade(mDisplayId) : null, mDisplayId, displayState);

        if (mColorFadeEnabled) {
            mColorFadeOnAnimator = ObjectAnimator.ofFloat(
                    mPowerState, DisplayPowerState.COLOR_FADE_LEVEL, 0.0f, 1.0f);
            mColorFadeOnAnimator.setDuration(COLOR_FADE_ON_ANIMATION_DURATION_MILLIS);
            mColorFadeOnAnimator.addListener(mAnimatorListener);

            mColorFadeOffAnimator = ObjectAnimator.ofFloat(
                    mPowerState, DisplayPowerState.COLOR_FADE_LEVEL, 1.0f, 0.0f);
            mColorFadeOffAnimator.setDuration(COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS);
            mColorFadeOffAnimator.addListener(mAnimatorListener);
        }

        mScreenBrightnessRampAnimator = mInjector.getDualRampAnimator(mPowerState,
                DisplayPowerState.SCREEN_BRIGHTNESS_FLOAT,
                DisplayPowerState.SCREEN_SDR_BRIGHTNESS_FLOAT);
        setAnimatorRampSpeeds(mAutomaticBrightnessController != null
                && mAutomaticBrightnessController.isInIdleMode());
        mScreenBrightnessRampAnimator.setListener(mRampAnimatorListener);

        noteScreenState(mPowerState.getScreenState(), Display.STATE_REASON_DEFAULT_POLICY);
        noteScreenBrightness(mPowerState.getScreenBrightness());

        // Initialize all of the brightness tracking state
        final float brightness = mDisplayBrightnessController.convertToAdjustedNits(
                mPowerState.getScreenBrightness());
        if (mBrightnessTracker != null && brightness >= PowerManager.BRIGHTNESS_MIN) {
            mBrightnessTracker.start(brightness);
        }

        BrightnessSetting.BrightnessSettingListener brightnessSettingListener = brightnessValue -> {
            Message msg = mHandler.obtainMessage(MSG_UPDATE_BRIGHTNESS, brightnessValue);
            mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
        };
        mDisplayBrightnessController
                .registerBrightnessSettingChangeListener(brightnessSettingListener);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
        if (mFlags.areAutoBrightnessModesEnabled()) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FOR_ALS),
                    /* notifyForDescendants= */ false, mSettingsObserver, UserHandle.USER_CURRENT);
        }
        handleBrightnessModeChange();
    }

    private void setUpAutoBrightness(Context context, Handler handler) {
        mUseSoftwareAutoBrightnessConfig = mDisplayDeviceConfig.isAutoBrightnessAvailable();

        if (!mUseSoftwareAutoBrightnessConfig) {
            return;
        }

        SparseArray<BrightnessMappingStrategy> brightnessMappers = new SparseArray<>();

        BrightnessMappingStrategy defaultModeBrightnessMapper =
                mInjector.getDefaultModeBrightnessMapper(context, mDisplayDeviceConfig,
                        mDisplayWhiteBalanceController);
        brightnessMappers.append(AUTO_BRIGHTNESS_MODE_DEFAULT,
                defaultModeBrightnessMapper);

        final boolean isIdleScreenBrightnessEnabled = context.getResources().getBoolean(
                R.bool.config_enableIdleScreenBrightnessMode);
        if (isIdleScreenBrightnessEnabled) {
            BrightnessMappingStrategy idleModeBrightnessMapper =
                    BrightnessMappingStrategy.create(context, mDisplayDeviceConfig,
                            AUTO_BRIGHTNESS_MODE_IDLE,
                            mDisplayWhiteBalanceController);
            if (idleModeBrightnessMapper != null) {
                brightnessMappers.append(AUTO_BRIGHTNESS_MODE_IDLE,
                        idleModeBrightnessMapper);
            }
        }

        BrightnessMappingStrategy dozeModeBrightnessMapper =
                BrightnessMappingStrategy.create(context, mDisplayDeviceConfig,
                        AUTO_BRIGHTNESS_MODE_DOZE, mDisplayWhiteBalanceController);
        if (mFlags.areAutoBrightnessModesEnabled() && dozeModeBrightnessMapper != null) {
            brightnessMappers.put(AUTO_BRIGHTNESS_MODE_DOZE, dozeModeBrightnessMapper);
        }

        float userLux = BrightnessMappingStrategy.INVALID_LUX;
        float userNits = BrightnessMappingStrategy.INVALID_NITS;
        if (mAutomaticBrightnessController != null) {
            userLux = mAutomaticBrightnessController.getUserLux();
            userNits = mAutomaticBrightnessController.getUserNits();
        }

        if (defaultModeBrightnessMapper != null) {
            // Ambient Lux - Active Mode Brightness Thresholds
            HysteresisLevels ambientBrightnessThresholds =
                    mDisplayDeviceConfig.getAmbientBrightnessHysteresis();

            // Display - Active Mode Brightness Thresholds
            HysteresisLevels screenBrightnessThresholds =
                    mDisplayDeviceConfig.getScreenBrightnessHysteresis();

            // Ambient Lux - Idle Screen Brightness Thresholds
            HysteresisLevels ambientBrightnessThresholdsIdle =
                    mDisplayDeviceConfig.getAmbientBrightnessIdleHysteresis();

            // Display - Idle Screen Brightness Thresholds
            HysteresisLevels screenBrightnessThresholdsIdle =
                    mDisplayDeviceConfig.getScreenBrightnessIdleHysteresis();

            long brighteningLightDebounce = mDisplayDeviceConfig
                    .getAutoBrightnessBrighteningLightDebounce();
            long darkeningLightDebounce = mDisplayDeviceConfig
                    .getAutoBrightnessDarkeningLightDebounce();
            long brighteningLightDebounceIdle = mDisplayDeviceConfig
                    .getAutoBrightnessBrighteningLightDebounceIdle();
            long darkeningLightDebounceIdle = mDisplayDeviceConfig
                    .getAutoBrightnessDarkeningLightDebounceIdle();
            boolean autoBrightnessResetAmbientLuxAfterWarmUp = context.getResources().getBoolean(
                    R.bool.config_autoBrightnessResetAmbientLuxAfterWarmUp);

            int lightSensorWarmUpTimeConfig = context.getResources().getInteger(
                    R.integer.config_lightSensorWarmupTime);
            int lightSensorRate = context.getResources().getInteger(
                    R.integer.config_autoBrightnessLightSensorRate);
            int initialLightSensorRate = context.getResources().getInteger(
                    R.integer.config_autoBrightnessInitialLightSensorRate);
            if (initialLightSensorRate == -1) {
                initialLightSensorRate = lightSensorRate;
            } else if (initialLightSensorRate > lightSensorRate) {
                Slog.w(mTag, "Expected config_autoBrightnessInitialLightSensorRate ("
                        + initialLightSensorRate + ") to be less than or equal to "
                        + "config_autoBrightnessLightSensorRate (" + lightSensorRate + ").");
            }

            loadAmbientLightSensor();
            // BrightnessTracker should only use one light sensor, we want to use the light sensor
            // from the default display and not e.g. temporary displays when switching layouts.
            if (mBrightnessTracker != null && mDisplayId == Display.DEFAULT_DISPLAY) {
                mBrightnessTracker.setLightSensor(mLightSensor);
            }

            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.stop();
            }
            mAutomaticBrightnessController = mInjector.getAutomaticBrightnessController(
                    this, handler.getLooper(), mSensorManager, mLightSensor,
                    brightnessMappers, lightSensorWarmUpTimeConfig, PowerManager.BRIGHTNESS_MIN,
                    PowerManager.BRIGHTNESS_MAX, mDozeScaleFactor, lightSensorRate,
                    initialLightSensorRate, brighteningLightDebounce, darkeningLightDebounce,
                    brighteningLightDebounceIdle, darkeningLightDebounceIdle,
                    autoBrightnessResetAmbientLuxAfterWarmUp, ambientBrightnessThresholds,
                    screenBrightnessThresholds, ambientBrightnessThresholdsIdle,
                    screenBrightnessThresholdsIdle, mContext, mBrightnessRangeController,
                    mBrightnessThrottler, mDisplayDeviceConfig.getAmbientHorizonShort(),
                    mDisplayDeviceConfig.getAmbientHorizonLong(), userLux, userNits,
                    mBrightnessClamperController, mFlags);
            mDisplayBrightnessController.setUpAutoBrightness(
                    mAutomaticBrightnessController, mSensorManager, mDisplayDeviceConfig, mHandler,
                    defaultModeBrightnessMapper, mIsEnabled, mLeadDisplayId);
            mBrightnessEventRingBuffer =
                    new RingBuffer<>(BrightnessEvent.class, RINGBUFFER_MAX);
            if (!mFlags.isRefactorDisplayPowerControllerEnabled()) {
                if (mScreenOffBrightnessSensorController != null) {
                    mScreenOffBrightnessSensorController.stop();
                    mScreenOffBrightnessSensorController = null;
                }
                loadScreenOffBrightnessSensor();
                int[] sensorValueToLux =
                        mDisplayDeviceConfig.getScreenOffBrightnessSensorValueToLux();
                if (mScreenOffBrightnessSensor != null && sensorValueToLux != null) {
                    mScreenOffBrightnessSensorController =
                            mInjector.getScreenOffBrightnessSensorController(
                                    mSensorManager,
                                    mScreenOffBrightnessSensor,
                                    mHandler,
                                    SystemClock::uptimeMillis,
                                    sensorValueToLux,
                                    defaultModeBrightnessMapper);
                }
            }
        } else {
            mUseSoftwareAutoBrightnessConfig = false;
        }
    }

    private void loadBrightnessRampRates() {
        mBrightnessRampRateFastDecrease = mDisplayDeviceConfig.getBrightnessRampFastDecrease();
        mBrightnessRampRateFastIncrease = mDisplayDeviceConfig.getBrightnessRampFastIncrease();
        mBrightnessRampRateSlowDecrease = mDisplayDeviceConfig.getBrightnessRampSlowDecrease();
        mBrightnessRampRateSlowIncrease = mDisplayDeviceConfig.getBrightnessRampSlowIncrease();
        mBrightnessRampRateSlowDecreaseIdle =
                mDisplayDeviceConfig.getBrightnessRampSlowDecreaseIdle();
        mBrightnessRampRateSlowIncreaseIdle =
                mDisplayDeviceConfig.getBrightnessRampSlowIncreaseIdle();
        mBrightnessRampDecreaseMaxTimeMillis =
                mDisplayDeviceConfig.getBrightnessRampDecreaseMaxMillis();
        mBrightnessRampIncreaseMaxTimeMillis =
                mDisplayDeviceConfig.getBrightnessRampIncreaseMaxMillis();
        mBrightnessRampDecreaseMaxTimeIdleMillis =
                mDisplayDeviceConfig.getBrightnessRampDecreaseMaxIdleMillis();
        mBrightnessRampIncreaseMaxTimeIdleMillis =
                mDisplayDeviceConfig.getBrightnessRampIncreaseMaxIdleMillis();
    }

    private void loadNitsRange(Resources resources) {
        if (mDisplayDeviceConfig != null && mDisplayDeviceConfig.getNits() != null) {
            mNitsRange = mDisplayDeviceConfig.getNits();
        } else {
            Slog.w(mTag, "Screen brightness nits configuration is unavailable; falling back");
            mNitsRange = BrightnessMappingStrategy.getFloatArray(resources
                    .obtainTypedArray(R.array.config_screenBrightnessNits));
        }
    }

    private void reloadReduceBrightColours() {
        if (mCdsi != null && mCdsi.isReduceBrightColorsActivated()) {
            applyReduceBrightColorsSplineAdjustment();
        }
    }

    @Override
    public void setAutomaticScreenBrightnessMode(
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode) {
        Message msg = mHandler.obtainMessage();
        msg.what = MSG_SWITCH_AUTOBRIGHTNESS_MODE;
        msg.arg1 = mode;
        mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
    }

    private void setAnimatorRampSpeeds(boolean isIdle) {
        if (mScreenBrightnessRampAnimator == null) {
            return;
        }
        if (mFlags.isAdaptiveTone1Enabled() && isIdle) {
            mScreenBrightnessRampAnimator.setAnimationTimeLimits(
                    mBrightnessRampIncreaseMaxTimeIdleMillis,
                    mBrightnessRampDecreaseMaxTimeIdleMillis);
        } else {
            mScreenBrightnessRampAnimator.setAnimationTimeLimits(
                    mBrightnessRampIncreaseMaxTimeMillis,
                    mBrightnessRampDecreaseMaxTimeMillis);
        }
    }

    private final Animator.AnimatorListener mAnimatorListener = new Animator.AnimatorListener() {
        @Override
        public void onAnimationStart(Animator animation) {
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            sendUpdatePowerState();
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }

        @Override
        public void onAnimationCancel(Animator animation) {
        }
    };

    private final RampAnimator.Listener mRampAnimatorListener = new RampAnimator.Listener() {
        @Override
        public void onAnimationEnd() {
            sendUpdatePowerState();
            Message msg = mHandler.obtainMessage(MSG_BRIGHTNESS_RAMP_DONE);
            mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
        }
    };

    /** Clean up all resources that are accessed via the {@link #mHandler} thread. */
    private void cleanupHandlerThreadAfterStop() {
        mDisplayPowerProximityStateController.cleanup();
        mBrightnessRangeController.stop();
        mBrightnessThrottler.stop();
        mBrightnessClamperController.stop();
        mHandler.removeCallbacksAndMessages(null);

        // Release any outstanding wakelocks we're still holding because of pending messages.
        mWakelockController.releaseAll();

        final float brightness = mPowerState != null
                ? mPowerState.getScreenBrightness()
                : PowerManager.BRIGHTNESS_MIN;
        reportStats(brightness);

        if (mPowerState != null) {
            mPowerState.stop();
            mPowerState = null;
        }

        if (!mFlags.isRefactorDisplayPowerControllerEnabled()
                && mScreenOffBrightnessSensorController != null) {
            mScreenOffBrightnessSensorController.stop();
        }

        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.setEnabled(false);
        }
    }

    // Call from handler thread
    private void updatePowerState() {
        Trace.traceBegin(Trace.TRACE_TAG_POWER,
                "DisplayPowerController#updatePowerState");
        updatePowerStateInternal();
        Trace.traceEnd(Trace.TRACE_TAG_POWER);
    }

    private void updatePowerStateInternal() {
        // Update the power state request.
        boolean mustNotify = false;
        final int previousPolicy;
        boolean mustInitialize = false;
        mBrightnessReasonTemp.set(null);
        mTempBrightnessEvent.reset();
        SparseArray<DisplayPowerControllerInterface> displayBrightnessFollowers;
        synchronized (mLock) {
            if (mStopped) {
                return;
            }
            mPendingUpdatePowerStateLocked = false;
            if (mPendingRequestLocked == null) {
                return; // wait until first actual power request
            }

            if (mPowerRequest == null) {
                mPowerRequest = new DisplayPowerRequest(mPendingRequestLocked);
                mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();
                mPendingRequestChangedLocked = false;
                mustInitialize = true;
                // Assume we're on and bright until told otherwise, since that's the state we turn
                // on in.
                previousPolicy = DisplayPowerRequest.POLICY_BRIGHT;
            } else if (mPendingRequestChangedLocked) {
                previousPolicy = mPowerRequest.policy;
                mPowerRequest.copyFrom(mPendingRequestLocked);
                mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();
                mPendingRequestChangedLocked = false;
                mDisplayReadyLocked = false;
            } else {
                previousPolicy = mPowerRequest.policy;
            }

            mustNotify = !mDisplayReadyLocked;

            displayBrightnessFollowers = mDisplayBrightnessFollowers.clone();
        }

        final Pair<Integer, Integer> stateAndReason =
                mDisplayStateController
                        .updateDisplayState(mPowerRequest, mIsEnabled, mIsInTransition);
        int state = stateAndReason.first;

        // Initialize things the first time the power state is changed.
        if (mustInitialize) {
            initialize(readyToUpdateDisplayState() ? state : Display.STATE_UNKNOWN);
        }

        if (mFlags.isOffloadDozeOverrideHoldsWakelockEnabled()) {
            // Sometimes, a display-state change can come without an associated PowerRequest,
            // as with DisplayOffload.  For those cases, we have to make sure to also mark the
            // display as "not ready" so that we can inform power-manager when the state-change is
            // complete.
            if (mPowerState.getScreenState() != state) {
                final boolean wasReady;
                synchronized (mLock) {
                    wasReady = mDisplayReadyLocked;
                    mDisplayReadyLocked = false;
                    mustNotify = true;
                }

                if (wasReady) {
                    // If we went from ready to not-ready from the state-change (instead of a
                    // PowerRequest) there's a good chance that nothing is keeping PowerManager
                    // from suspending. Grab the unfinished business suspend blocker to keep the
                    // device awake until the display-state change goes into effect.
                    mWakelockController.acquireWakelock(
                            WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS);
                }
            }
        }

        // Animate the screen state change unless already animating.
        // The transition may be deferred, so after this point we will use the
        // actual state instead of the desired one.
        animateScreenStateChange(
                state, /* reason= */ stateAndReason.second,
                mDisplayStateController.shouldPerformScreenOffTransition());
        state = mPowerState.getScreenState();

        DisplayBrightnessState displayBrightnessState = mDisplayBrightnessController
                .updateBrightness(mPowerRequest, state, mDisplayOffloadSession);
        float brightnessState = displayBrightnessState.getBrightness();
        float rawBrightnessState = displayBrightnessState.getBrightness();
        mBrightnessReasonTemp.set(displayBrightnessState.getBrightnessReason());
        boolean slowChange = displayBrightnessState.isSlowChange();
        // custom transition duration
        float customAnimationRate = displayBrightnessState.getCustomAnimationRate();
        int brightnessAdjustmentFlags = displayBrightnessState.getBrightnessAdjustmentFlag();
        final boolean userSetBrightnessChanged =
                mDisplayBrightnessController.getIsUserSetScreenBrightnessUpdated();
        if (displayBrightnessState.getBrightnessEvent() != null) {
            mTempBrightnessEvent.copyFrom(displayBrightnessState.getBrightnessEvent());
        }

        boolean allowAutoBrightnessWhileDozing =
                mDisplayBrightnessController.isAllowAutoBrightnessWhileDozing();

        if (!mFlags.isRefactorDisplayPowerControllerEnabled()) {
            // Set up the ScreenOff controller used when coming out of SCREEN_OFF and the ALS sensor
            // doesn't yet have a valid lux value to use with auto-brightness.
            if (mScreenOffBrightnessSensorController != null) {
                mScreenOffBrightnessSensorController
                        .setLightSensorEnabled(displayBrightnessState.getShouldUseAutoBrightness()
                        && mIsEnabled && (state == Display.STATE_OFF
                        || (state == Display.STATE_DOZE && !allowAutoBrightnessWhileDozing))
                        && mLeadDisplayId == Layout.NO_LEAD_DISPLAY);
            }
        }

        // Take note if the short term model was already active before applying the current
        // request changes.
        final boolean wasShortTermModelActive =
                mAutomaticBrightnessStrategy.isShortTermModelActive();
        boolean userInitiatedChange = displayBrightnessState.isUserInitiatedChange();

        if (!mFlags.isRefactorDisplayPowerControllerEnabled()) {
            // Switch to doze auto-brightness mode if needed
            if (mFlags.areAutoBrightnessModesEnabled() && mAutomaticBrightnessController != null
                    && !mAutomaticBrightnessController.isInIdleMode()) {
                // Set sendUpdate to false, we're already in updatePowerState() so there's no need
                // to trigger it again
                mAutomaticBrightnessController.switchMode(Display.isDozeState(state)
                        ? AUTO_BRIGHTNESS_MODE_DOZE : AUTO_BRIGHTNESS_MODE_DEFAULT,
                        /* sendUpdate= */ false);
            }

            mAutomaticBrightnessStrategy.setAutoBrightnessState(state,
                    allowAutoBrightnessWhileDozing, mBrightnessReasonTemp.getReason(),
                    mPowerRequest.policy,
                    mDisplayBrightnessController.getLastUserSetScreenBrightness(),
                    userSetBrightnessChanged);

            // If the brightness is already set then it's been overridden by something other than
            // the user, or is a temporary adjustment.
            userInitiatedChange = (Float.isNaN(brightnessState))
                    && (mAutomaticBrightnessStrategy.getAutoBrightnessAdjustmentChanged()
                    || userSetBrightnessChanged);
        }


        final int autoBrightnessState = mAutomaticBrightnessStrategy.isAutoBrightnessEnabled()
                ? AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED
                : mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff()
                        ? AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE
                        : AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED;

        mBrightnessRangeController.setAutoBrightnessEnabled(autoBrightnessState);

        boolean updateScreenBrightnessSetting =
                displayBrightnessState.shouldUpdateScreenBrightnessSetting();
        float currentBrightnessSetting = mDisplayBrightnessController.getCurrentBrightness();

        if (!mFlags.isRefactorDisplayPowerControllerEnabled()) {
            // AutomaticBrightnessStrategy has higher priority than OffloadBrightnessStrategy
            if (Float.isNaN(brightnessState)
                    || mBrightnessReasonTemp.getReason() == BrightnessReason.REASON_OFFLOAD) {
                if (mAutomaticBrightnessStrategy.isAutoBrightnessEnabled()) {
                    brightnessState = mAutomaticBrightnessStrategy.getAutomaticScreenBrightness(
                            mTempBrightnessEvent);
                    if (BrightnessUtils.isValidBrightnessValue(brightnessState)
                            || brightnessState == PowerManager.BRIGHTNESS_OFF_FLOAT) {
                        rawBrightnessState = mAutomaticBrightnessController
                                .getRawAutomaticScreenBrightness();
                        // slowly adapt to auto-brightness
                        // TODO(b/253226419): slowChange should be decided by
                        // strategy.updateBrightness
                        slowChange = mAutomaticBrightnessStrategy.hasAppliedAutoBrightness()
                                && !mAutomaticBrightnessStrategy
                                .getAutoBrightnessAdjustmentChanged();
                        brightnessAdjustmentFlags =
                                mAutomaticBrightnessStrategy
                                        .getAutoBrightnessAdjustmentReasonsFlags();
                        updateScreenBrightnessSetting = currentBrightnessSetting != brightnessState;
                        mAutomaticBrightnessStrategy.setAutoBrightnessApplied(true);
                        mBrightnessReasonTemp.setReason(BrightnessReason.REASON_AUTOMATIC);
                        if (mScreenOffBrightnessSensorController != null) {
                            mScreenOffBrightnessSensorController.setLightSensorEnabled(false);
                        }
                        setBrightnessFromOffload(PowerManager.BRIGHTNESS_INVALID_FLOAT);
                    } else {
                        mAutomaticBrightnessStrategy.setAutoBrightnessApplied(false);
                        // Restore the lower-priority brightness strategy
                        brightnessState = displayBrightnessState.getBrightness();
                    }
                }
            } else {
                mAutomaticBrightnessStrategy.setAutoBrightnessApplied(false);
            }
        }

        if (!Float.isNaN(brightnessState)) {
            brightnessState = clampScreenBrightness(brightnessState);
        }

        if (Display.isDozeState(state)) {
            // TODO(b/329676661): Introduce a config property to choose between this brightness
            //  strategy and DOZE_DEFAULT
            // On some devices, when auto-brightness is disabled and the device is dozing, we use
            // the current brightness setting scaled by the doze scale factor
            if ((Float.isNaN(brightnessState)
                    || displayBrightnessState.getDisplayBrightnessStrategyName()
                    .equals(DisplayBrightnessStrategyConstants.FALLBACK_BRIGHTNESS_STRATEGY_NAME))
                    && mFlags.isDisplayOffloadEnabled()
                    && mDisplayOffloadSession != null
                    && (mAutomaticBrightnessController == null
                    || !mAutomaticBrightnessStrategy.shouldUseAutoBrightness())) {
                rawBrightnessState = getDozeBrightnessForOffload();
                brightnessState = clampScreenBrightness(rawBrightnessState);
                mBrightnessReasonTemp.setReason(BrightnessReason.REASON_DOZE_MANUAL);
                mTempBrightnessEvent.setFlags(
                        mTempBrightnessEvent.getFlags() | BrightnessEvent.FLAG_DOZE_SCALE);
            }

            // Use default brightness when dozing unless overridden.
            if (Float.isNaN(brightnessState) && Display.isDozeState(state)
                    && !mDisplayBrightnessController.isAllowAutoBrightnessWhileDozingConfig()) {
                rawBrightnessState = mScreenBrightnessDozeConfig;
                brightnessState = clampScreenBrightness(rawBrightnessState);
                mBrightnessReasonTemp.setReason(BrightnessReason.REASON_DOZE_DEFAULT);
            }
        }

        if (!mFlags.isRefactorDisplayPowerControllerEnabled()) {
            // The ALS is not available yet - use the screen off sensor to determine the initial
            // brightness
            if (Float.isNaN(brightnessState)
                    && mAutomaticBrightnessStrategy.isAutoBrightnessEnabled()
                    && mScreenOffBrightnessSensorController != null) {
                rawBrightnessState =
                        mScreenOffBrightnessSensorController.getAutomaticScreenBrightness();
                brightnessState = rawBrightnessState;
                if (BrightnessUtils.isValidBrightnessValue(brightnessState)) {
                    brightnessState = clampScreenBrightness(brightnessState);
                    updateScreenBrightnessSetting =
                            mDisplayBrightnessController.getCurrentBrightness()
                                    != brightnessState;
                    mBrightnessReasonTemp.setReason(
                            BrightnessReason.REASON_SCREEN_OFF_BRIGHTNESS_SENSOR);
                }
            }
        }

        // Apply manual brightness.
        if (Float.isNaN(brightnessState) && !mFlags.isRefactorDisplayPowerControllerEnabled()) {
            rawBrightnessState = currentBrightnessSetting;
            brightnessState = clampScreenBrightness(rawBrightnessState);
            if (brightnessState != currentBrightnessSetting) {
                // The manually chosen screen brightness is outside of the currently allowed
                // range (i.e., high-brightness-mode), make sure we tell the rest of the system
                // by updating the setting.
                updateScreenBrightnessSetting = true;
            }
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_MANUAL);
        }

        float ambientLux = mAutomaticBrightnessController == null ? 0
                : mAutomaticBrightnessController.getAmbientLux();
        for (int i = 0; i < displayBrightnessFollowers.size(); i++) {
            DisplayPowerControllerInterface follower = displayBrightnessFollowers.valueAt(i);
            follower.setBrightnessToFollow(rawBrightnessState,
                    mDisplayBrightnessController.convertToNits(rawBrightnessState),
                    ambientLux, slowChange);
        }

        // Now that a desired brightness has been calculated, apply brightness throttling. The
        // dimming and low power transformations that follow can only dim brightness further.
        //
        // We didn't do this earlier through brightness clamping because we need to know both
        // unthrottled (unclamped/ideal) and throttled brightness levels for subsequent operations.
        // Note throttling effectively changes the allowed brightness range, so, similarly to HBM,
        // we broadcast this change through setting.
        final float unthrottledBrightnessState = brightnessState;
        DisplayBrightnessState clampedState = mBrightnessClamperController.clamp(mPowerRequest,
                brightnessState, slowChange, /* displayState= */ state);

        brightnessState = clampedState.getBrightness();
        slowChange = clampedState.isSlowChange();
        // faster rate wins, at this point customAnimationRate == -1, strategy does not control
        // customAnimationRate. Should be revisited if strategy start setting this value
        customAnimationRate = Math.max(customAnimationRate, clampedState.getCustomAnimationRate());
        mBrightnessReasonTemp.addModifier(clampedState.getBrightnessReason().getModifier());

        if (updateScreenBrightnessSetting) {
            // Tell the rest of the system about the new brightness in case we had to change it
            // for things like auto-brightness or high-brightness-mode. Note that we do this
            // only considering maxBrightness (ignoring brightness modifiers like low power or dim)
            // so that the slider accurately represents the full possible range,
            // even if they range changes what it means in absolute terms.
            mDisplayBrightnessController.updateScreenBrightnessSetting(
                    MathUtils.constrain(unthrottledBrightnessState,
                            clampedState.getMinBrightness(), clampedState.getMaxBrightness()));
        }

        // The current brightness to use has been calculated at this point, and HbmController should
        // be notified so that it can accurately calculate HDR or HBM levels. We specifically do it
        // here instead of having HbmController listen to the brightness setting because certain
        // brightness sources (such as an app override) are not saved to the setting, but should be
        // reflected in HBM calculations.
        mBrightnessRangeController.onBrightnessChanged(brightnessState, unthrottledBrightnessState,
                mBrightnessClamperController.getBrightnessMaxReason());

        // Animate the screen brightness when the screen is on or dozing.
        // Skip the animation when the screen is off or suspended.
        boolean brightnessAdjusted = false;
        final boolean brightnessIsTemporary =
                (mBrightnessReasonTemp.getReason() == BrightnessReason.REASON_TEMPORARY)
                        || mAutomaticBrightnessStrategy
                        .isTemporaryAutoBrightnessAdjustmentApplied();
        if (!mPendingScreenOff) {
            if (mSkipScreenOnBrightnessRamp) {
                if (state == Display.STATE_ON) {
                    if (mSkipRampState == RAMP_STATE_SKIP_NONE && mDozing) {
                        mInitialAutoBrightness = brightnessState;
                        mSkipRampState = RAMP_STATE_SKIP_INITIAL;
                    } else if (mSkipRampState == RAMP_STATE_SKIP_INITIAL
                            && mUseSoftwareAutoBrightnessConfig
                            && !BrightnessSynchronizer.floatEquals(brightnessState,
                            mInitialAutoBrightness)) {
                        mSkipRampState = RAMP_STATE_SKIP_AUTOBRIGHT;
                    } else if (mSkipRampState == RAMP_STATE_SKIP_AUTOBRIGHT) {
                        mSkipRampState = RAMP_STATE_SKIP_NONE;
                    }
                } else {
                    mSkipRampState = RAMP_STATE_SKIP_NONE;
                }
            }

            final boolean initialRampSkip = (state == Display.STATE_ON && mSkipRampState
                    != RAMP_STATE_SKIP_NONE) || mDisplayPowerProximityStateController
                    .shouldSkipRampBecauseOfProximityChangeToNegative();
            // While dozing, sometimes the brightness is split into buckets. Rather than animating
            // through the buckets, which is unlikely to be smooth in the first place, just jump
            // right to the suggested brightness.
            final boolean hasBrightnessBuckets =
                    Display.isDozeState(state) && mBrightnessBucketsInDozeConfig;
            // If the color fade is totally covering the screen then we can change the backlight
            // level without it being a noticeable jump since any actual content isn't yet visible.
            final boolean isDisplayContentVisible =
                    mColorFadeEnabled && mPowerState.getColorFadeLevel() == 1.0f;
            // We only want to animate the brightness if it is between 0.0f and 1.0f.
            // brightnessState can contain the values -1.0f and NaN, which we do not want to
            // animate to. To avoid this, we check the value first.
            // If the brightnessState is off (-1.0f) we still want to animate to the minimum
            // brightness (0.0f) to accommodate for LED displays, which can appear bright to the
            // user even when the display is all black. We also clamp here in case some
            // transformations to the brightness have pushed it outside of the currently
            // allowed range.
            float animateValue = clampScreenBrightness(brightnessState);

            // If there are any HDR layers on the screen, we have a special brightness value that we
            // use instead. We still preserve the calculated brightness for Standard Dynamic Range
            // (SDR) layers, but the main brightness value will be the one for HDR.
            float sdrAnimateValue = animateValue;
            // TODO(b/216365040): The decision to prevent HBM for HDR in low power mode should be
            // done in HighBrightnessModeController.
            if (mBrightnessRangeController.getHighBrightnessMode()
                    == BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR
                    && (mBrightnessReasonTemp.getModifier() & BrightnessReason.MODIFIER_DIMMED) == 0
                    && (mBrightnessReasonTemp.getModifier() & BrightnessReason.MODIFIER_LOW_POWER)
                    == 0) {
                // We want to scale HDR brightness level with the SDR level, we also need to restore
                // SDR brightness immediately when entering dim or low power mode.
                animateValue = mBrightnessRangeController.getHdrBrightnessValue();
                customAnimationRate = Math.max(customAnimationRate,
                        mBrightnessRangeController.getHdrTransitionRate());
                mBrightnessReasonTemp.addModifier(BrightnessReason.MODIFIER_HDR);
            }

            // if doze or suspend state is requested, we want to finish brightnes animation fast
            // to allow state animation to start
            if (mPowerRequest.policy == POLICY_DOZE
                    && (mPowerRequest.dozeScreenState == Display.STATE_UNKNOWN  // dozing
                    || mPowerRequest.dozeScreenState == Display.STATE_DOZE_SUSPEND
                    || mPowerRequest.dozeScreenState == Display.STATE_ON_SUSPEND)) {
                customAnimationRate = DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;
                slowChange = false;
            }

            final float currentBrightness = mPowerState.getScreenBrightness();
            final float currentSdrBrightness = mPowerState.getSdrScreenBrightness();

            if (BrightnessUtils.isValidBrightnessValue(animateValue)
                    && (animateValue != currentBrightness
                    || sdrAnimateValue != currentSdrBrightness)) {
                boolean skipAnimation = initialRampSkip || hasBrightnessBuckets
                        || !isDisplayContentVisible || brightnessIsTemporary;
                final boolean isHdrOnlyChange = BrightnessSynchronizer.floatEquals(
                        sdrAnimateValue, currentSdrBrightness);
                if (mFlags.isFastHdrTransitionsEnabled() && !skipAnimation && isHdrOnlyChange) {
                    // SDR brightness is unchanged, so animate quickly as this is only impacting
                    // a likely minority amount of display content
                    // ie, the highlights of an HDR video or UltraHDR image
                    // Ideally we'd do this as fast as possible (ie, skip the animation entirely),
                    // but this requires display support and would need an entry in the
                    // display configuration. For now just do the fast animation
                    slowChange = false;
                }
                if (skipAnimation) {
                    animateScreenBrightness(animateValue, sdrAnimateValue,
                            SCREEN_ANIMATION_RATE_MINIMUM);
                } else if (customAnimationRate > 0) {
                    animateScreenBrightness(animateValue, sdrAnimateValue,
                            customAnimationRate, /* ignoreAnimationLimits = */true);
                } else {
                    boolean isIncreasing = animateValue > currentBrightness;
                    final float rampSpeed;
                    final boolean idle = mAutomaticBrightnessController != null
                            && mAutomaticBrightnessController.isInIdleMode();
                    if (isIncreasing && slowChange) {
                        rampSpeed = idle ? mBrightnessRampRateSlowIncreaseIdle
                                : mBrightnessRampRateSlowIncrease;
                    } else if (isIncreasing && !slowChange) {
                        rampSpeed = mBrightnessRampRateFastIncrease;
                    } else if (!isIncreasing && slowChange) {
                        rampSpeed = idle ? mBrightnessRampRateSlowDecreaseIdle
                                : mBrightnessRampRateSlowDecrease;
                    } else {
                        rampSpeed = mBrightnessRampRateFastDecrease;
                    }
                    animateScreenBrightness(animateValue, sdrAnimateValue, rampSpeed);
                }
            }

            notifyBrightnessTrackerChanged(brightnessState, userInitiatedChange,
                    wasShortTermModelActive, mAutomaticBrightnessStrategy.isAutoBrightnessEnabled(),
                    brightnessIsTemporary, displayBrightnessState.getShouldUseAutoBrightness());

            // We save the brightness info *after* the brightness setting has been changed and
            // adjustments made so that the brightness info reflects the latest value.
            brightnessAdjusted = saveBrightnessInfo(getScreenBrightnessSetting(),
                    animateValue, clampedState);
        } else {
            brightnessAdjusted = saveBrightnessInfo(getScreenBrightnessSetting(), clampedState);
        }

        // Only notify if the brightness adjustment is not temporary (i.e. slider has been released)
        if (brightnessAdjusted && !brightnessIsTemporary) {
            postBrightnessChangeRunnable();
        }

        // Log any changes to what is currently driving the brightness setting.
        if (!mBrightnessReasonTemp.equals(mBrightnessReason) || brightnessAdjustmentFlags != 0) {
            Slog.v(mTag, "Brightness [" + brightnessState + "] reason changing to: '"
                    + mBrightnessReasonTemp.toString(brightnessAdjustmentFlags)
                    + "', previous reason: '" + mBrightnessReason + "'.");
            mBrightnessReason.set(mBrightnessReasonTemp);
        } else if (mBrightnessReasonTemp.getReason() == BrightnessReason.REASON_MANUAL
                && userSetBrightnessChanged) {
            Slog.v(mTag, "Brightness [" + brightnessState + "] manual adjustment.");
        }


        // Log brightness events when a detail of significance has changed. Generally this is the
        // brightness itself changing, but also includes data like HBM cap, thermal throttling
        // brightness cap, RBC state, etc.
        mTempBrightnessEvent.setTime(System.currentTimeMillis());
        mTempBrightnessEvent.setBrightness(brightnessState);
        mTempBrightnessEvent.setPhysicalDisplayId(mUniqueDisplayId);
        mTempBrightnessEvent.setDisplayState(state);
        mTempBrightnessEvent.setDisplayPolicy(mPowerRequest.policy);
        mTempBrightnessEvent.setReason(mBrightnessReason);
        mTempBrightnessEvent.setHbmMax(mBrightnessRangeController.getCurrentBrightnessMax());
        mTempBrightnessEvent.setHbmMode(mBrightnessRangeController.getHighBrightnessMode());
        mTempBrightnessEvent.setFlags(mTempBrightnessEvent.getFlags()
                | (mIsRbcActive ? BrightnessEvent.FLAG_RBC : 0)
                | (mPowerRequest.lowPowerMode ? BrightnessEvent.FLAG_LOW_POWER_MODE : 0));
        mTempBrightnessEvent.setRbcStrength(mCdsi != null
                ? mCdsi.getReduceBrightColorsStrength() : -1);
        mTempBrightnessEvent.setPowerFactor(mPowerRequest.screenLowPowerBrightnessFactor);
        mTempBrightnessEvent.setWasShortTermModelActive(wasShortTermModelActive);
        mTempBrightnessEvent.setDisplayBrightnessStrategyName(displayBrightnessState
                .getDisplayBrightnessStrategyName());
        mTempBrightnessEvent.setAutomaticBrightnessEnabled(
                displayBrightnessState.getShouldUseAutoBrightness());
        // Temporary is what we use during slider interactions. We avoid logging those so that
        // we don't spam logcat when the slider is being used.
        boolean tempToTempTransition =
                mTempBrightnessEvent.getReason().getReason() == BrightnessReason.REASON_TEMPORARY
                        && mLastBrightnessEvent.getReason().getReason()
                        == BrightnessReason.REASON_TEMPORARY;
        // Purely for dumpsys;
        final boolean isRbcEvent =
                mLastBrightnessEvent.isRbcEnabled() != mTempBrightnessEvent.isRbcEnabled();

        if ((!mTempBrightnessEvent.equalsMainData(mLastBrightnessEvent) && !tempToTempTransition)
                || brightnessAdjustmentFlags != 0) {
            mTempBrightnessEvent.setInitialBrightness(mLastBrightnessEvent.getBrightness());
            mLastBrightnessEvent.copyFrom(mTempBrightnessEvent);
            BrightnessEvent newEvent = new BrightnessEvent(mTempBrightnessEvent);
            // Adjustment flags (and user-set flag) only get added after the equality checks since
            // they are transient.
            newEvent.setAdjustmentFlags(brightnessAdjustmentFlags);
            newEvent.setFlags(newEvent.getFlags() | (userSetBrightnessChanged
                    ? BrightnessEvent.FLAG_USER_SET : 0));
            Slog.i(mTag, newEvent.toString(/* includeTime= */ false));

            if (userSetBrightnessChanged
                    || newEvent.getReason().getReason() != BrightnessReason.REASON_TEMPORARY) {
                logBrightnessEvent(newEvent, unthrottledBrightnessState);
            }
            if (mBrightnessEventRingBuffer != null) {
                mBrightnessEventRingBuffer.append(newEvent);
            }
            if (isRbcEvent) {
                mRbcEventRingBuffer.append(newEvent);
            }

        }

        // Update display white-balance.
        if (mDisplayWhiteBalanceController != null) {
            if (state == Display.STATE_ON && mDisplayWhiteBalanceSettings.isEnabled()) {
                mDisplayWhiteBalanceController.setEnabled(true);
                mDisplayWhiteBalanceController.updateDisplayColorTemperature();
            } else {
                mDisplayWhiteBalanceController.setEnabled(false);
            }
        }

        // Determine whether the display is ready for use in the newly requested state.
        // Note that we do not wait for the brightness ramp animation to complete before
        // reporting the display is ready because we only need to ensure the screen is in the
        // right power state even as it continues to converge on the desired brightness.
        final boolean ready = mPendingScreenOnUnblocker == null
                && mPendingScreenOnUnblockerByDisplayOffload == null
                && (!mColorFadeEnabled || (!mColorFadeOnAnimator.isStarted()
                        && !mColorFadeOffAnimator.isStarted()))
                && mPowerState.waitUntilClean(mCleanListener);
        final boolean finished = ready
                && !mScreenBrightnessRampAnimator.isAnimating();

        // Notify policy about screen turned on.
        if (ready && state != Display.STATE_OFF
                && mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_TURNING_ON) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_ON);
            mWindowManagerPolicy.screenTurnedOn(mDisplayId);
        }

        // Grab a wake lock if we have unfinished business.
        if (!finished) {
            mWakelockController.acquireWakelock(WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS);
        }

        // Notify the power manager when ready.
        if (ready && mustNotify) {
            // Send state change.
            synchronized (mLock) {
                if (!mPendingRequestChangedLocked) {
                    mDisplayReadyLocked = true;

                    if (DEBUG) {
                        Slog.d(mTag, "Display ready!");
                    }
                }
            }
            sendOnStateChangedWithWakelock();
        }

        // Release the wake lock when we have no unfinished business.
        if (finished) {
            mWakelockController.releaseWakelock(WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS);
        }

        // Record if dozing for future comparison.
        mDozing = state != Display.STATE_ON;

        if (previousPolicy != mPowerRequest.policy) {
            logDisplayPolicyChanged(mPowerRequest.policy);
        }
    }

    private void setDwbcOverride(float cct) {
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.setAmbientColorTemperatureOverride(cct);
            // The ambient color temperature override is only applied when the ambient color
            // temperature changes or is updated, so it doesn't necessarily change the screen color
            // temperature immediately. So, let's make it!
            // We can call this directly, since we're already on the handler thread.
            updatePowerState();
        }
    }

    private void setDwbcStrongMode(int arg) {
        if (mDisplayWhiteBalanceController != null) {
            final boolean isIdle = (arg == AUTO_BRIGHTNESS_MODE_IDLE);
            mDisplayWhiteBalanceController.setStrongModeEnabled(isIdle);
        }
    }

    private void setDwbcLoggingEnabled(int arg) {
        if (mDisplayWhiteBalanceController != null) {
            final boolean enabled = (arg == 1);
            mDisplayWhiteBalanceController.setLoggingEnabled(enabled);
            mDisplayWhiteBalanceSettings.setLoggingEnabled(enabled);
        }
    }

    @Override
    public void updateBrightness() {
        sendUpdatePowerState();
    }

    /**
     * Ignores the proximity sensor until the sensor state changes, but only if the sensor is
     * currently enabled and forcing the screen to be dark.
     */
    @Override
    public void ignoreProximitySensorUntilChanged() {
        mDisplayPowerProximityStateController.ignoreProximitySensorUntilChanged();
    }

    @Override
    public void setBrightnessConfiguration(BrightnessConfiguration c,
            boolean shouldResetShortTermModel) {
        Message msg = mHandler.obtainMessage(MSG_CONFIGURE_BRIGHTNESS,
                shouldResetShortTermModel ? 1 : 0, /* unused */ 0, c);
        msg.sendToTarget();
    }

    @Override
    public void setTemporaryBrightness(float brightness) {
        Message msg = mHandler.obtainMessage(MSG_SET_TEMPORARY_BRIGHTNESS,
                Float.floatToIntBits(brightness), 0 /*unused*/);
        msg.sendToTarget();
    }

    @Override
    public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
        Message msg = mHandler.obtainMessage(MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT,
                Float.floatToIntBits(adjustment), 0 /*unused*/);
        msg.sendToTarget();
    }

    @Override
    public void setBrightnessFromOffload(float brightness) {
        Message msg = mHandler.obtainMessage(MSG_SET_BRIGHTNESS_FROM_OFFLOAD,
                Float.floatToIntBits(brightness), 0 /*unused*/);
        mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
    }

    @Override
    public float[] getAutoBrightnessLevels(
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode) {
        int preset = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FOR_ALS,
                Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL, UserHandle.USER_CURRENT);
        return mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(mode, preset);
    }

    @Override
    public float[] getAutoBrightnessLuxLevels(
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode) {
        int preset = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FOR_ALS,
                Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL, UserHandle.USER_CURRENT);
        return mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(mode, preset);
    }

    @Override
    public BrightnessInfo getBrightnessInfo() {
        synchronized (mCachedBrightnessInfo) {
            return new BrightnessInfo(
                    mCachedBrightnessInfo.brightness.value,
                    mCachedBrightnessInfo.adjustedBrightness.value,
                    mCachedBrightnessInfo.brightnessMin.value,
                    mCachedBrightnessInfo.brightnessMax.value,
                    mCachedBrightnessInfo.hbmMode.value,
                    mCachedBrightnessInfo.hbmTransitionPoint.value,
                    mCachedBrightnessInfo.brightnessMaxReason.value);
        }
    }

    @Override
    public void onBootCompleted() {
        Message msg = mHandler.obtainMessage(MSG_BOOT_COMPLETED);
        mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
    }

    private boolean saveBrightnessInfo(float brightness) {
        return saveBrightnessInfo(brightness, /* state= */ null);
    }

    private boolean saveBrightnessInfo(float brightness, @Nullable DisplayBrightnessState state) {
        return saveBrightnessInfo(brightness, brightness, state);
    }

    private boolean saveBrightnessInfo(float brightness, float adjustedBrightness,
            @Nullable DisplayBrightnessState state) {
        synchronized (mCachedBrightnessInfo) {
            float stateMax = state != null ? state.getMaxBrightness() : PowerManager.BRIGHTNESS_MAX;
            float stateMin = state != null ? state.getMinBrightness() : PowerManager.BRIGHTNESS_MAX;
            final float minBrightness = Math.max(stateMin, Math.min(
                    mBrightnessRangeController.getCurrentBrightnessMin(), stateMax));
            final float maxBrightness = Math.min(
                    mBrightnessRangeController.getCurrentBrightnessMax(), stateMax);
            boolean changed = false;

            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.brightness,
                            brightness);
            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.adjustedBrightness,
                            adjustedBrightness);
            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.brightnessMin,
                            minBrightness);
            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.brightnessMax,
                            maxBrightness);
            changed |=
                    mCachedBrightnessInfo.checkAndSetInt(mCachedBrightnessInfo.hbmMode,
                            mBrightnessRangeController.getHighBrightnessMode());
            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.hbmTransitionPoint,
                            mBrightnessRangeController.getTransitionPoint());
            changed |=
                    mCachedBrightnessInfo.checkAndSetInt(mCachedBrightnessInfo.brightnessMaxReason,
                            mBrightnessClamperController.getBrightnessMaxReason());
            return changed;
        }
    }

    void postBrightnessChangeRunnable() {
        if (!mHandler.hasCallbacks(mOnBrightnessChangeRunnable)) {
            mHandler.post(mOnBrightnessChangeRunnable);
        }
    }

    private HighBrightnessModeController createHbmControllerLocked(
            HighBrightnessModeMetadata hbmMetadata, Runnable modeChangeCallback) {
        final DisplayDeviceConfig ddConfig = mDisplayDevice.getDisplayDeviceConfig();
        final IBinder displayToken = mDisplayDevice.getDisplayTokenLocked();
        final String displayUniqueId = mDisplayDevice.getUniqueId();
        final DisplayDeviceConfig.HighBrightnessModeData hbmData =
                ddConfig != null ? ddConfig.getHighBrightnessModeData() : null;
        final DisplayDeviceInfo info = mDisplayDevice.getDisplayDeviceInfoLocked();
        return mInjector.getHighBrightnessModeController(mHandler, info.width, info.height,
                displayToken, displayUniqueId, PowerManager.BRIGHTNESS_MIN,
                PowerManager.BRIGHTNESS_MAX, hbmData, (sdrBrightness, maxDesiredHdrSdrRatio) ->
                        mDisplayDeviceConfig.getHdrBrightnessFromSdr(sdrBrightness,
                                maxDesiredHdrSdrRatio), modeChangeCallback, hbmMetadata, mContext);
    }

    private BrightnessThrottler createBrightnessThrottlerLocked() {
        final DisplayDevice device = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        final DisplayDeviceConfig ddConfig = device.getDisplayDeviceConfig();
        return new BrightnessThrottler(mHandler,
                () -> {
                    sendUpdatePowerState();
                    postBrightnessChangeRunnable();
                }, mUniqueDisplayId,
                mLogicalDisplay.getDisplayInfoLocked().thermalBrightnessThrottlingDataId,
                ddConfig);
    }

    private void blockScreenOn() {
        if (mPendingScreenOnUnblocker == null) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
            mPendingScreenOnUnblocker = new ScreenOnUnblocker();
            mScreenOnBlockStartRealTime = SystemClock.elapsedRealtime();
            Slog.i(mTag, "Blocking screen on until initial contents have been drawn.");
        }
    }

    private void unblockScreenOn() {
        if (mPendingScreenOnUnblocker != null) {
            mPendingScreenOnUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - mScreenOnBlockStartRealTime;
            Slog.i(mTag, "Unblocked screen on after " + delay + " ms");
            Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
        }
    }

    private void blockScreenOff() {
        if (mPendingScreenOffUnblocker == null) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, SCREEN_OFF_BLOCKED_TRACE_NAME, 0);
            mPendingScreenOffUnblocker = new ScreenOffUnblocker();
            mScreenOffBlockStartRealTime = SystemClock.elapsedRealtime();
            Slog.i(mTag, "Blocking screen off");
        }
    }

    private void unblockScreenOff() {
        if (mPendingScreenOffUnblocker != null) {
            mPendingScreenOffUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - mScreenOffBlockStartRealTime;
            Slog.i(mTag, "Unblocked screen off after " + delay + " ms");
            Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, SCREEN_OFF_BLOCKED_TRACE_NAME, 0);
        }
    }

    private void blockScreenOnByDisplayOffload(DisplayOffloadSession displayOffloadSession) {
        if (mPendingScreenOnUnblockerByDisplayOffload != null || displayOffloadSession == null) {
            return;
        }
        mScreenTurningOnWasBlockedByDisplayOffload = true;

        Trace.asyncTraceBegin(
                Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_BY_DISPLAYOFFLOAD_TRACE_NAME, 0);
        mScreenOnBlockByDisplayOffloadStartRealTime = SystemClock.elapsedRealtime();

        mPendingScreenOnUnblockerByDisplayOffload =
                () -> onDisplayOffloadUnblockScreenOn(displayOffloadSession);
        if (!displayOffloadSession.blockScreenOn(mPendingScreenOnUnblockerByDisplayOffload)) {
            mPendingScreenOnUnblockerByDisplayOffload = null;
            long delay =
                    SystemClock.elapsedRealtime() - mScreenOnBlockByDisplayOffloadStartRealTime;
            Slog.w(mTag, "Tried blocking screen on for offloading but failed. So, end trace after "
                    + delay + " ms.");
            Trace.asyncTraceEnd(
                    Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_BY_DISPLAYOFFLOAD_TRACE_NAME, 0);
            return;
        }
        Slog.i(mTag, "Blocking screen on for offloading.");
    }

    private void onDisplayOffloadUnblockScreenOn(DisplayOffloadSession displayOffloadSession) {
        Message msg = mHandler.obtainMessage(MSG_OFFLOADING_SCREEN_ON_UNBLOCKED,
                displayOffloadSession);
        mHandler.sendMessage(msg);
    }

    private void unblockScreenOnByDisplayOffload() {
        if (mPendingScreenOnUnblockerByDisplayOffload == null) {
            return;
        }
        mPendingScreenOnUnblockerByDisplayOffload = null;
        long delay = SystemClock.elapsedRealtime() - mScreenOnBlockByDisplayOffloadStartRealTime;
        Slog.i(mTag, "Unblocked screen on for offloading after " + delay + " ms");
        Trace.asyncTraceEnd(
                Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_BY_DISPLAYOFFLOAD_TRACE_NAME, 0);
    }

    private boolean setScreenState(int state, @Display.StateReason int reason) {
        return setScreenState(state, reason, false /*reportOnly*/);
    }

    private boolean setScreenState(int state, @Display.StateReason int reason, boolean reportOnly) {
        final boolean isOff = (state == Display.STATE_OFF);
        final boolean isOn = (state == Display.STATE_ON);
        final boolean changed = mPowerState.getScreenState() != state;

        // If the screen is turning on, give displayoffload a chance to do something before the
        // screen actually turns on.
        // TODO(b/316941732): add tests for this displayoffload screen-on blocker.
        if (isOn && changed && !mScreenTurningOnWasBlockedByDisplayOffload) {
            blockScreenOnByDisplayOffload(mDisplayOffloadSession);
        } else if (!isOn && mScreenTurningOnWasBlockedByDisplayOffload) {
            // No longer turning screen on, so unblock previous screen on blocking immediately.
            unblockScreenOnByDisplayOffload();
            mScreenTurningOnWasBlockedByDisplayOffload = false;
        }

        if (changed || mReportedScreenStateToPolicy == REPORTED_TO_POLICY_UNREPORTED) {
            // If we are trying to turn screen off, give policy a chance to do something before we
            // actually turn the screen off.
            if (isOff && !mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()) {
                if (mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_ON
                        || mReportedScreenStateToPolicy == REPORTED_TO_POLICY_UNREPORTED) {
                    setReportedScreenState(REPORTED_TO_POLICY_SCREEN_TURNING_OFF);
                    blockScreenOff();
                    mWindowManagerPolicy.screenTurningOff(mDisplayId, mPendingScreenOffUnblocker);
                    unblockScreenOff();
                } else if (mPendingScreenOffUnblocker != null) {
                    // Abort doing the state change until screen off is unblocked.
                    return false;
                }
            }

            if (!reportOnly && changed && readyToUpdateDisplayState()
                    && mPendingScreenOffUnblocker == null
                    && mPendingScreenOnUnblockerByDisplayOffload == null) {
                Trace.traceCounter(Trace.TRACE_TAG_POWER, "ScreenState", state);

                String propertyKey = "debug.tracing.screen_state";
                String propertyValue = String.valueOf(state);
                try {
                    // TODO(b/153319140) remove when we can get this from the above trace invocation
                    SystemProperties.set(propertyKey, propertyValue);
                } catch (RuntimeException e) {
                    Slog.e(mTag, "Failed to set a system property: key=" + propertyKey
                            + " value=" + propertyValue + " " + e.getMessage());
                }

                mPowerState.setScreenState(state, reason);
                // Tell battery stats about the transition.
                noteScreenState(state, reason);
            }
        }

        // Tell the window manager policy when the screen is turned off or on unless it's due
        // to the proximity sensor.  We temporarily block turning the screen on until the
        // window manager is ready by leaving a black surface covering the screen.
        // This surface is essentially the final state of the color fade animation and
        // it is only removed once the window manager tells us that the activity has
        // finished drawing underneath.
        if (isOff && mReportedScreenStateToPolicy != REPORTED_TO_POLICY_SCREEN_OFF
                && !mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_OFF);
            unblockScreenOn();
            mWindowManagerPolicy.screenTurnedOff(mDisplayId, mIsInTransition);
        } else if (!isOff
                && mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_TURNING_OFF) {

            // We told policy already that screen was turning off, but now we changed our minds.
            // Complete the full state transition on -> turningOff -> off.
            unblockScreenOff();
            mWindowManagerPolicy.screenTurnedOff(mDisplayId, mIsInTransition);
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_OFF);
        }
        if (!isOff
                && (mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_OFF
                || mReportedScreenStateToPolicy == REPORTED_TO_POLICY_UNREPORTED)) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_TURNING_ON);
            if (mPowerState.getColorFadeLevel() == 0.0f) {
                blockScreenOn();
            } else {
                unblockScreenOn();
            }
            mWindowManagerPolicy.screenTurningOn(mDisplayId, mPendingScreenOnUnblocker);
        }

        // Return true if the screen isn't blocked.
        return mPendingScreenOnUnblocker == null
                && mPendingScreenOnUnblockerByDisplayOffload == null;
    }

    private void setReportedScreenState(int state) {
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "ReportedScreenStateToPolicy", state);
        mReportedScreenStateToPolicy = state;
        if (state == REPORTED_TO_POLICY_SCREEN_ON) {
            mScreenTurningOnWasBlockedByDisplayOffload = false;
        }
    }

    private void loadAmbientLightSensor() {
        final int fallbackType = mDisplayId == Display.DEFAULT_DISPLAY
                ? Sensor.TYPE_LIGHT : SensorUtils.NO_FALLBACK;
        mLightSensor = SensorUtils.findSensor(mSensorManager,
                mDisplayDeviceConfig.getAmbientLightSensor(), fallbackType);
    }

    private void loadScreenOffBrightnessSensor() {
        mScreenOffBrightnessSensor = SensorUtils.findSensor(mSensorManager,
                mDisplayDeviceConfig.getScreenOffBrightnessSensor(), SensorUtils.NO_FALLBACK);
    }

    private float clampScreenBrightness(float value) {
        if (Float.isNaN(value)) {
            value = PowerManager.BRIGHTNESS_MIN;
        }
        return MathUtils.constrain(value, mBrightnessRangeController.getCurrentBrightnessMin(),
                mBrightnessRangeController.getCurrentBrightnessMax());
    }

    private void animateScreenBrightness(float target, float sdrTarget, float rate) {
        animateScreenBrightness(target, sdrTarget, rate, /* ignoreAnimationLimits = */false);
    }

    private void animateScreenBrightness(float target, float sdrTarget, float rate,
            boolean ignoreAnimationLimits) {
        if (DEBUG) {
            Slog.d(mTag, "Animating brightness: target=" + target + ", sdrTarget=" + sdrTarget
                    + ", rate=" + rate);
        }
        if (mScreenBrightnessRampAnimator.animateTo(target, sdrTarget, rate,
                ignoreAnimationLimits)) {
            Trace.traceCounter(Trace.TRACE_TAG_POWER, "TargetScreenBrightness", (int) target);

            String propertyKey = "debug.tracing.screen_brightness";
            String propertyValue = String.valueOf(target);
            try {
                // TODO(b/153319140) remove when we can get this from the above trace invocation
                SystemProperties.set(propertyKey, propertyValue);
            } catch (RuntimeException e) {
                Slog.e(mTag, "Failed to set a system property: key=" + propertyKey
                        + " value=" + propertyValue + " " + e.getMessage());
            }

            noteScreenBrightness(target);
        }
    }

    private void animateScreenStateChange(
            int target, @Display.StateReason int reason, boolean performScreenOffTransition) {
        // If there is already an animation in progress, don't interfere with it.
        if (mColorFadeEnabled
                && (mColorFadeOnAnimator.isStarted() || mColorFadeOffAnimator.isStarted())) {
            if (target != Display.STATE_ON) {
                return;
            }
            // If display state changed to on, proceed and stop the color fade and turn screen on.
            mPendingScreenOff = false;
        }

        if (mDisplayBlanksAfterDozeConfig
                && Display.isDozeState(mPowerState.getScreenState())
                && !Display.isDozeState(target)) {
            // Skip the screen off animation and add a black surface to hide the
            // contents of the screen.
            mPowerState.prepareColorFade(mContext,
                    mColorFadeFadesConfig ? ColorFade.MODE_FADE : ColorFade.MODE_WARM_UP);
            if (mColorFadeOffAnimator != null) {
                mColorFadeOffAnimator.end();
            }
            // Some display hardware will blank itself on the transition between doze and non-doze
            // but still on display states. In this case we want to report to policy that the
            // display has turned off so it can prepare the appropriate power on animation, but we
            // don't want to actually transition to the fully off state since that takes
            // significantly longer to transition from.
            setScreenState(Display.STATE_OFF, reason, target != Display.STATE_OFF /*reportOnly*/);
        }

        // If we were in the process of turning off the screen but didn't quite
        // finish.  Then finish up now to prevent a jarring transition back
        // to screen on if we skipped blocking screen on as usual.
        if (mPendingScreenOff && target != Display.STATE_OFF) {
            setScreenState(Display.STATE_OFF, reason);
            mPendingScreenOff = false;
            mPowerState.dismissColorFadeResources();
        }

        if (target == Display.STATE_ON) {
            // Want screen on.  The contents of the screen may not yet
            // be visible if the color fade has not been dismissed because
            // its last frame of animation is solid black.
            if (!setScreenState(Display.STATE_ON, reason)) {
                return; // screen on blocked
            }
            if (USE_COLOR_FADE_ON_ANIMATION && mColorFadeEnabled && mPowerRequest.isBrightOrDim()) {
                // Perform screen on animation.
                if (mPowerState.getColorFadeLevel() == 1.0f) {
                    mPowerState.dismissColorFade();
                } else if (mPowerState.prepareColorFade(mContext,
                        mColorFadeFadesConfig
                                ? ColorFade.MODE_FADE : ColorFade.MODE_WARM_UP)) {
                    mColorFadeOnAnimator.start();
                } else {
                    mColorFadeOnAnimator.end();
                }
            } else {
                // Skip screen on animation.
                mPowerState.setColorFadeLevel(1.0f);
                mPowerState.dismissColorFade();
            }
        } else if (target == Display.STATE_DOZE) {
            // Want screen dozing.
            // Wait for brightness animation to complete beforehand when entering doze
            // from screen on to prevent a perceptible jump because brightness may operate
            // differently when the display is configured for dozing.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() == Display.STATE_ON) {
                return;
            }

            // Set screen state.
            if (!setScreenState(Display.STATE_DOZE, reason)) {
                return; // screen on blocked
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else if (target == Display.STATE_DOZE_SUSPEND) {
            // Want screen dozing and suspended.
            // Wait for brightness animation to complete beforehand unless already
            // suspended because we may not be able to change it after suspension.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() != Display.STATE_DOZE_SUSPEND) {
                return;
            }

            // If not already suspending, temporarily set the state to doze until the
            // screen on is unblocked, then suspend.
            if (mPowerState.getScreenState() != Display.STATE_DOZE_SUSPEND) {
                if (!setScreenState(Display.STATE_DOZE, reason)) {
                    return; // screen on blocked
                }
                setScreenState(Display.STATE_DOZE_SUSPEND, reason); // already on so can't block
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else if (target == Display.STATE_ON_SUSPEND) {
            // Want screen full-power and suspended.
            // Wait for brightness animation to complete beforehand unless already
            // suspended because we may not be able to change it after suspension.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() != Display.STATE_ON_SUSPEND) {
                return;
            }

            // If not already suspending, temporarily set the state to on until the
            // screen on is unblocked, then suspend.
            if (mPowerState.getScreenState() != Display.STATE_ON_SUSPEND) {
                if (!setScreenState(Display.STATE_ON, reason)) {
                    return;
                }
                setScreenState(Display.STATE_ON_SUSPEND, reason);
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else {
            // Want screen off.
            mPendingScreenOff = true;
            if (!mColorFadeEnabled) {
                mPowerState.setColorFadeLevel(0.0f);
            }

            if (mPowerState.getColorFadeLevel() == 0.0f) {
                // Turn the screen off.
                // A black surface is already hiding the contents of the screen.
                setScreenState(Display.STATE_OFF, reason);
                mPendingScreenOff = false;
                mPowerState.dismissColorFadeResources();
            } else if (performScreenOffTransition
                    && mPowerState.prepareColorFade(mContext,
                    mColorFadeFadesConfig
                            ? ColorFade.MODE_FADE : ColorFade.MODE_COOL_DOWN)
                    && mPowerState.getScreenState() != Display.STATE_OFF) {
                // Perform the screen off animation.
                mColorFadeOffAnimator.start();
            } else {
                // Skip the screen off animation and add a black surface to hide the
                // contents of the screen.
                mColorFadeOffAnimator.end();
            }
        }
    }

    private final Runnable mCleanListener = this::sendUpdatePowerState;

    private void sendOnStateChangedWithWakelock() {
        boolean wakeLockAcquired = mWakelockController.acquireWakelock(
                WakelockController.WAKE_LOCK_STATE_CHANGED);
        if (wakeLockAcquired) {
            mHandler.post(mWakelockController.getOnStateChangedRunnable());
        }
    }

    private void logDisplayPolicyChanged(int newPolicy) {
        LogMaker log = new LogMaker(MetricsEvent.DISPLAY_POLICY);
        log.setType(MetricsEvent.TYPE_UPDATE);
        log.setSubtype(newPolicy);
        MetricsLogger.action(log);
    }

    private void handleSettingsChange() {
        mDisplayBrightnessController
                .setPendingScreenBrightness(mDisplayBrightnessController
                        .getScreenBrightnessSetting());
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        sendUpdatePowerState();
    }

    private void handleBrightnessModeChange() {
        final int screenBrightnessModeSetting = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);
        mAutomaticBrightnessStrategy.setUseAutoBrightness(screenBrightnessModeSetting
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
    }


    @Override
    public float getScreenBrightnessSetting() {
        return mDisplayBrightnessController.getScreenBrightnessSetting();
    }

    @Override
    public float getDozeBrightnessForOffload() {
        return mDisplayBrightnessController.getCurrentBrightness() * mDozeScaleFactor;
    }

    @Override
    public void setBrightness(float brightness) {
        mDisplayBrightnessController.setBrightness(clampScreenBrightness(brightness));
    }

    @Override
    public void setBrightness(float brightness, int userSerial) {
        mDisplayBrightnessController.setBrightness(clampScreenBrightness(brightness), userSerial);
    }

    @Override
    public int getDisplayId() {
        return mDisplayId;
    }

    @Override
    public int getLeadDisplayId() {
        return mLeadDisplayId;
    }

    @Override
    public void setBrightnessToFollow(float leadDisplayBrightness, float nits, float ambientLux,
            boolean slowChange) {
        mBrightnessRangeController.onAmbientLuxChange(ambientLux);
        if (nits == BrightnessMappingStrategy.INVALID_NITS) {
            mDisplayBrightnessController.setBrightnessToFollow(leadDisplayBrightness, slowChange);
        } else {
            float brightness = mDisplayBrightnessController.getBrightnessFromNits(nits);
            if (BrightnessUtils.isValidBrightnessValue(brightness)) {
                mDisplayBrightnessController.setBrightnessToFollow(brightness, slowChange);
            } else {
                // The device does not support nits
                mDisplayBrightnessController.setBrightnessToFollow(leadDisplayBrightness,
                        slowChange);
            }
        }
        sendUpdatePowerState();
    }

    private void notifyBrightnessTrackerChanged(float brightness, boolean userInitiated,
            boolean wasShortTermModelActive, boolean autobrightnessEnabled,
            boolean brightnessIsTemporary, boolean shouldUseAutoBrightness) {

        final float brightnessInNits =
                mDisplayBrightnessController.convertToAdjustedNits(brightness);
        // Don't report brightness to brightnessTracker:
        // If brightness is temporary (ie the slider has not been released)
        // or if we are in idle screen brightness mode.
        // or display is not on
        // or we shouldn't be using autobrightness
        // or the nits is invalid.
        if (brightnessIsTemporary
                || mAutomaticBrightnessController == null
                || mAutomaticBrightnessController.isInIdleMode()
                || !autobrightnessEnabled
                || mBrightnessTracker == null
                || !shouldUseAutoBrightness
                || brightnessInNits < 0.0f) {
            return;
        }

        if (userInitiated && (mAutomaticBrightnessController == null
                || !mAutomaticBrightnessController.hasValidAmbientLux())) {
            // If we don't have a valid lux reading we can't report a valid
            // slider event so notify as if the system changed the brightness.
            userInitiated = false;
        }

        // We only want to track changes on devices that can actually map the display backlight
        // values into a physical brightness unit since the value provided by the API is in
        // nits and not using the arbitrary backlight units.
        final float powerFactor = mPowerRequest.lowPowerMode
                ? mPowerRequest.screenLowPowerBrightnessFactor
                : 1.0f;
        mBrightnessTracker.notifyBrightnessChanged(brightnessInNits, userInitiated,
                powerFactor, wasShortTermModelActive,
                mAutomaticBrightnessController.isDefaultConfig(), mUniqueDisplayId,
                mAutomaticBrightnessController.getLastSensorValues(),
                mAutomaticBrightnessController.getLastSensorTimestamps());
    }

    @Override
    public void addDisplayBrightnessFollower(DisplayPowerControllerInterface follower) {
        synchronized (mLock) {
            mDisplayBrightnessFollowers.append(follower.getDisplayId(), follower);
            sendUpdatePowerStateLocked();
        }
    }

    @Override
    public void removeDisplayBrightnessFollower(DisplayPowerControllerInterface follower) {
        synchronized (mLock) {
            mDisplayBrightnessFollowers.remove(follower.getDisplayId());
            mHandler.postAtTime(() -> follower.setBrightnessToFollow(
                    PowerManager.BRIGHTNESS_INVALID_FLOAT, BrightnessMappingStrategy.INVALID_NITS,
                    /* ambientLux= */ 0, /* slowChange= */ false), mClock.uptimeMillis());
        }
    }

    @GuardedBy("mLock")
    private void clearDisplayBrightnessFollowersLocked() {
        for (int i = 0; i < mDisplayBrightnessFollowers.size(); i++) {
            DisplayPowerControllerInterface follower = mDisplayBrightnessFollowers.valueAt(i);
            mHandler.postAtTime(() -> follower.setBrightnessToFollow(
                    PowerManager.BRIGHTNESS_INVALID_FLOAT, BrightnessMappingStrategy.INVALID_NITS,
                    /* ambientLux= */ 0, /* slowChange= */ false), mClock.uptimeMillis());
        }
        mDisplayBrightnessFollowers.clear();
    }

    @Override
    public void dump(final PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Display Power Controller:");
            pw.println("  mDisplayId=" + mDisplayId);
            pw.println("  mLeadDisplayId=" + mLeadDisplayId);
            pw.println("  mLightSensor=" + mLightSensor);
            pw.println("  mDisplayBrightnessFollowers=" + mDisplayBrightnessFollowers);

            pw.println();
            pw.println("Display Power Controller Locked State:");
            pw.println("  mDisplayReadyLocked=" + mDisplayReadyLocked);
            pw.println("  mPendingRequestLocked=" + mPendingRequestLocked);
            pw.println("  mPendingRequestChangedLocked=" + mPendingRequestChangedLocked);
            pw.println("  mPendingUpdatePowerStateLocked=" + mPendingUpdatePowerStateLocked);
        }

        pw.println();
        pw.println("Display Power Controller Configuration:");
        pw.println("  mScreenBrightnessDozeConfig=" + mScreenBrightnessDozeConfig);
        pw.println("  mUseSoftwareAutoBrightnessConfig=" + mUseSoftwareAutoBrightnessConfig);
        pw.println("  mSkipScreenOnBrightnessRamp=" + mSkipScreenOnBrightnessRamp);
        pw.println("  mColorFadeFadesConfig=" + mColorFadeFadesConfig);
        pw.println("  mColorFadeEnabled=" + mColorFadeEnabled);
        pw.println("  mIsDisplayInternal=" + mIsDisplayInternal);
        synchronized (mCachedBrightnessInfo) {
            pw.println("  mCachedBrightnessInfo.brightness="
                    + mCachedBrightnessInfo.brightness.value);
            pw.println("  mCachedBrightnessInfo.adjustedBrightness="
                    + mCachedBrightnessInfo.adjustedBrightness.value);
            pw.println("  mCachedBrightnessInfo.brightnessMin="
                    + mCachedBrightnessInfo.brightnessMin.value);
            pw.println("  mCachedBrightnessInfo.brightnessMax="
                    + mCachedBrightnessInfo.brightnessMax.value);
            pw.println("  mCachedBrightnessInfo.hbmMode=" + mCachedBrightnessInfo.hbmMode.value);
            pw.println("  mCachedBrightnessInfo.hbmTransitionPoint="
                    + mCachedBrightnessInfo.hbmTransitionPoint.value);
            pw.println("  mCachedBrightnessInfo.brightnessMaxReason ="
                    + mCachedBrightnessInfo.brightnessMaxReason.value);
        }
        pw.println("  mDisplayBlanksAfterDozeConfig=" + mDisplayBlanksAfterDozeConfig);
        pw.println("  mBrightnessBucketsInDozeConfig=" + mBrightnessBucketsInDozeConfig);
        pw.println("  mDozeScaleFactor=" + mDozeScaleFactor);
        mHandler.runWithScissors(() -> dumpLocal(pw), 1000);
    }

    private void dumpLocal(PrintWriter pw) {
        pw.println();
        pw.println("Display Power Controller Thread State:");
        pw.println("  mPowerRequest=" + mPowerRequest);
        pw.println("  mBrightnessReason=" + mBrightnessReason);
        pw.println("  mAppliedDimming=" + mAppliedDimming);
        pw.println("  mAppliedThrottling=" + mAppliedThrottling);
        pw.println("  mDozing=" + mDozing);
        pw.println("  mSkipRampState=" + skipRampStateToString(mSkipRampState));
        pw.println("  mScreenOnBlockStartRealTime=" + mScreenOnBlockStartRealTime);
        pw.println("  mScreenOffBlockStartRealTime=" + mScreenOffBlockStartRealTime);
        pw.println("  mPendingScreenOnUnblocker=" + mPendingScreenOnUnblocker);
        pw.println("  mPendingScreenOffUnblocker=" + mPendingScreenOffUnblocker);
        pw.println("  mPendingScreenOff=" + mPendingScreenOff);
        pw.println("  mReportedToPolicy="
                + reportedToPolicyToString(mReportedScreenStateToPolicy));
        pw.println("  mIsRbcActive=" + mIsRbcActive);
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
        mAutomaticBrightnessStrategy.dump(ipw);

        if (mScreenBrightnessRampAnimator != null) {
            pw.println("  mScreenBrightnessRampAnimator.isAnimating()="
                    + mScreenBrightnessRampAnimator.isAnimating());
        }

        if (mColorFadeOnAnimator != null) {
            pw.println("  mColorFadeOnAnimator.isStarted()="
                    + mColorFadeOnAnimator.isStarted());
        }
        if (mColorFadeOffAnimator != null) {
            pw.println("  mColorFadeOffAnimator.isStarted()="
                    + mColorFadeOffAnimator.isStarted());
        }

        if (mPowerState != null) {
            mPowerState.dump(pw);
        }

        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.dump(pw);
            dumpBrightnessEvents(pw);
        }

        dumpRbcEvents(pw);

        if (mScreenOffBrightnessSensorController != null) {
            mScreenOffBrightnessSensorController.dump(pw);
        }

        if (mBrightnessRangeController != null) {
            mBrightnessRangeController.dump(pw);
        }

        if (mBrightnessThrottler != null) {
            mBrightnessThrottler.dump(pw);
        }

        pw.println();
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.dump(pw);
            mDisplayWhiteBalanceSettings.dump(pw);
        }

        pw.println();

        if (mWakelockController != null) {
            mWakelockController.dumpLocal(pw);
        }

        pw.println();
        if (mDisplayBrightnessController != null) {
            mDisplayBrightnessController.dump(pw);
        }

        pw.println();
        if (mDisplayStateController != null) {
            mDisplayStateController.dumpsys(pw);
        }

        pw.println();
        if (mBrightnessClamperController != null) {
            mBrightnessClamperController.dump(ipw);
        }
    }


    private static String reportedToPolicyToString(int state) {
        switch (state) {
            case REPORTED_TO_POLICY_SCREEN_OFF:
                return "REPORTED_TO_POLICY_SCREEN_OFF";
            case REPORTED_TO_POLICY_SCREEN_TURNING_ON:
                return "REPORTED_TO_POLICY_SCREEN_TURNING_ON";
            case REPORTED_TO_POLICY_SCREEN_ON:
                return "REPORTED_TO_POLICY_SCREEN_ON";
            default:
                return Integer.toString(state);
        }
    }

    private static String skipRampStateToString(int state) {
        switch (state) {
            case RAMP_STATE_SKIP_NONE:
                return "RAMP_STATE_SKIP_NONE";
            case RAMP_STATE_SKIP_INITIAL:
                return "RAMP_STATE_SKIP_INITIAL";
            case RAMP_STATE_SKIP_AUTOBRIGHT:
                return "RAMP_STATE_SKIP_AUTOBRIGHT";
            default:
                return Integer.toString(state);
        }
    }

    private void dumpBrightnessEvents(PrintWriter pw) {
        int size = mBrightnessEventRingBuffer.size();
        if (size < 1) {
            pw.println("No Automatic Brightness Adjustments");
            return;
        }

        pw.println("Automatic Brightness Adjustments Last " + size + " Events: ");
        BrightnessEvent[] eventArray = mBrightnessEventRingBuffer.toArray();
        for (int i = 0; i < mBrightnessEventRingBuffer.size(); i++) {
            pw.println("  " + eventArray[i].toString());
        }
    }

    private void dumpRbcEvents(PrintWriter pw) {
        int size = mRbcEventRingBuffer.size();
        if (size < 1) {
            pw.println("No Reduce Bright Colors Adjustments");
            return;
        }

        pw.println("Reduce Bright Colors Adjustments Last " + size + " Events: ");
        BrightnessEvent[] eventArray = mRbcEventRingBuffer.toArray();
        for (int i = 0; i < mRbcEventRingBuffer.size(); i++) {
            pw.println("  " + eventArray[i]);
        }
    }


    private void noteScreenState(int screenState, @Display.StateReason int reason) {
        // Log screen state change with display id
        FrameworkStatsLog.write(FrameworkStatsLog.SCREEN_STATE_CHANGED_V2,
                screenState, mDisplayStatsId, reason);
        if (mBatteryStats != null) {
            try {
                // TODO(multi-display): make this multi-display
                mBatteryStats.noteScreenState(screenState);
            } catch (RemoteException e) {
                // same process
            }
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void noteScreenBrightness(float brightness) {
        if (mBatteryStats != null) {
            try {
                // TODO(brightnessfloat): change BatteryStats to use float
                int brightnessInt = mFlags.isBrightnessIntRangeUserPerceptionEnabled()
                        ? BrightnessSynchronizer.brightnessFloatToIntSetting(mContext, brightness)
                        : BrightnessSynchronizer.brightnessFloatToInt(brightness);
                mBatteryStats.noteScreenBrightness(brightnessInt);
            } catch (RemoteException e) {
                // same process
            }
        }
    }

    private void reportStats(float brightness) {
        if (mLastStatsBrightness == brightness) {
            return;
        }

        float hbmTransitionPoint = PowerManager.BRIGHTNESS_MAX;
        synchronized (mCachedBrightnessInfo) {
            if (mCachedBrightnessInfo.hbmTransitionPoint == null) {
                return;
            }
            hbmTransitionPoint = mCachedBrightnessInfo.hbmTransitionPoint.value;
        }

        final boolean aboveTransition = brightness > hbmTransitionPoint;
        final boolean oldAboveTransition = mLastStatsBrightness > hbmTransitionPoint;

        if (aboveTransition || oldAboveTransition) {
            mLastStatsBrightness = brightness;
            mHandler.removeMessages(MSG_STATSD_HBM_BRIGHTNESS);
            if (aboveTransition != oldAboveTransition) {
                // report immediately
                logHbmBrightnessStats(brightness, mDisplayStatsId);
            } else {
                // delay for rate limiting
                Message msg = mHandler.obtainMessage();
                msg.what = MSG_STATSD_HBM_BRIGHTNESS;
                msg.arg1 = Float.floatToIntBits(brightness);
                msg.arg2 = mDisplayStatsId;
                mHandler.sendMessageAtTime(msg, mClock.uptimeMillis()
                        + BRIGHTNESS_CHANGE_STATSD_REPORT_INTERVAL_MS);
            }
        }
    }

    private void logHbmBrightnessStats(float brightness, int displayStatsId) {
        synchronized (mHandler) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.DISPLAY_HBM_BRIGHTNESS_CHANGED, displayStatsId, brightness);
        }
    }

    // Return bucket index of range_[left]_[right] where
    // left <= nits < right
    private int nitsToRangeIndex(float nits) {
        for (int i = 0; i < BRIGHTNESS_RANGE_BOUNDARIES.length; i++) {
            if (nits < BRIGHTNESS_RANGE_BOUNDARIES[i]) {
                return BRIGHTNESS_RANGE_INDEX[i];
            }
        }
        return FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__BUCKET_INDEX__RANGE_3000_INF;
    }

    private int convertBrightnessReasonToStatsEnum(int brightnessReason) {
        switch(brightnessReason) {
            case BrightnessReason.REASON_UNKNOWN:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_UNKNOWN;
            case BrightnessReason.REASON_MANUAL:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_MANUAL;
            case BrightnessReason.REASON_DOZE:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_DOZE;
            case BrightnessReason.REASON_DOZE_DEFAULT:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_DOZE_DEFAULT;
            case BrightnessReason.REASON_AUTOMATIC:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_AUTOMATIC;
            case BrightnessReason.REASON_SCREEN_OFF:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_SCREEN_OFF;
            case BrightnessReason.REASON_OVERRIDE:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_OVERRIDE;
            case BrightnessReason.REASON_TEMPORARY:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_TEMPORARY;
            case BrightnessReason.REASON_BOOST:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_BOOST;
            case BrightnessReason.REASON_SCREEN_OFF_BRIGHTNESS_SENSOR:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_SCREEN_OFF_BRIGHTNESS_SENSOR;
            case BrightnessReason.REASON_FOLLOWER:
                return FrameworkStatsLog
                    .DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_FOLLOWER;
        }
        return FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__ENTIRE_REASON__REASON_UNKNOWN;
    }

    private void logBrightnessEvent(BrightnessEvent event, float unmodifiedBrightness) {
        int modifier = event.getReason().getModifier();
        int flags = event.getFlags();
        // It's easier to check if the brightness is at maximum level using the brightness
        // value untouched by any modifiers
        boolean brightnessIsMax = unmodifiedBrightness == event.getHbmMax();
        float brightnessInNits =
                mDisplayBrightnessController.convertToAdjustedNits(event.getBrightness());
        float appliedLowPowerMode = event.isLowPowerModeSet() ? event.getPowerFactor() : -1f;
        int appliedRbcStrength  = event.isRbcEnabled() ? event.getRbcStrength() : -1;
        float appliedHbmMaxNits =
                event.getHbmMode() == BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF
                ? -1f : mDisplayBrightnessController.convertToAdjustedNits(event.getHbmMax());
        // thermalCapNits set to -1 if not currently capping max brightness
        float appliedThermalCapNits =
                event.getThermalMax() == PowerManager.BRIGHTNESS_MAX
                ? -1f : mDisplayBrightnessController.convertToAdjustedNits(event.getThermalMax());
        if (mIsDisplayInternal) {
            FrameworkStatsLog.write(FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED,
                    mDisplayBrightnessController
                            .convertToAdjustedNits(event.getInitialBrightness()),
                    brightnessInNits,
                    event.getLux(),
                    event.getPhysicalDisplayId(),
                    event.wasShortTermModelActive(),
                    appliedLowPowerMode,
                    appliedRbcStrength,
                    appliedHbmMaxNits,
                    appliedThermalCapNits,
                    event.isAutomaticBrightnessEnabled(),
                    FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__REASON__REASON_MANUAL,
                    convertBrightnessReasonToStatsEnum(event.getReason().getReason()),
                    nitsToRangeIndex(brightnessInNits),
                    brightnessIsMax,
                    event.getHbmMode() == BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT,
                    event.getHbmMode() == BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR,
                    (modifier & BrightnessReason.MODIFIER_LOW_POWER) > 0,
                    mBrightnessClamperController.getBrightnessMaxReason(),
                    // TODO: (flc) add brightnessMinReason here too.
                    (modifier & BrightnessReason.MODIFIER_DIMMED) > 0,
                    event.isRbcEnabled(),
                    (flags & BrightnessEvent.FLAG_INVALID_LUX) > 0,
                    (flags & BrightnessEvent.FLAG_DOZE_SCALE) > 0,
                    (flags & BrightnessEvent.FLAG_USER_SET) > 0,
                    event.getAutoBrightnessMode() == AUTO_BRIGHTNESS_MODE_IDLE,
                    (flags & BrightnessEvent.FLAG_LOW_POWER_MODE) > 0);
        }
    }

    /**
     * Indicates whether the display state is ready to update. If this is the default display, we
     * want to update it right away so that we can draw the boot animation on it. If it is not
     * the default display, drawing the boot animation on it would look incorrect, so we need
     * to wait until boot is completed.
     * @return True if the display state is ready to update
     */
    private boolean readyToUpdateDisplayState() {
        return mDisplayId == Display.DEFAULT_DISPLAY || mBootCompleted;
    }

    private final class DisplayControllerHandler extends Handler {
        DisplayControllerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_POWER_STATE:
                    updatePowerState();
                    break;

                case MSG_SCREEN_ON_UNBLOCKED:
                    if (mPendingScreenOnUnblocker == msg.obj) {
                        unblockScreenOn();
                        updatePowerState();
                    }
                    break;
                case MSG_SCREEN_OFF_UNBLOCKED:
                    if (mPendingScreenOffUnblocker == msg.obj) {
                        unblockScreenOff();
                        updatePowerState();
                    }
                    break;
                case MSG_OFFLOADING_SCREEN_ON_UNBLOCKED:
                    if (mDisplayOffloadSession == msg.obj) {
                        unblockScreenOnByDisplayOffload();
                        updatePowerState();
                    }
                    break;
                case MSG_CONFIGURE_BRIGHTNESS:
                    BrightnessConfiguration brightnessConfiguration =
                            (BrightnessConfiguration) msg.obj;
                    mAutomaticBrightnessStrategy.setBrightnessConfiguration(brightnessConfiguration,
                            msg.arg1 == 1);
                    if (mBrightnessTracker != null) {
                        mBrightnessTracker
                                .setShouldCollectColorSample(brightnessConfiguration != null
                                        && brightnessConfiguration.shouldCollectColorSamples());
                    }
                    updatePowerState();
                    break;

                case MSG_SET_TEMPORARY_BRIGHTNESS:
                    // TODO: Should we have a a timeout for the temporary brightness?
                    mDisplayBrightnessController
                            .setTemporaryBrightness(Float.intBitsToFloat(msg.arg1));
                    updatePowerState();
                    break;

                case MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT:
                    mAutomaticBrightnessStrategy
                            .setTemporaryAutoBrightnessAdjustment(Float.intBitsToFloat(msg.arg1));
                    updatePowerState();
                    break;

                case MSG_STOP:
                    cleanupHandlerThreadAfterStop();
                    break;

                case MSG_UPDATE_BRIGHTNESS:
                    if (mStopped) {
                        return;
                    }
                    handleSettingsChange();
                    break;

                case MSG_UPDATE_RBC:
                    handleRbcChanged();
                    break;

                case MSG_BRIGHTNESS_RAMP_DONE:
                    if (mPowerState != null) {
                        final float brightness = mPowerState.getScreenBrightness();
                        reportStats(brightness);
                    }
                    break;

                case MSG_STATSD_HBM_BRIGHTNESS:
                    logHbmBrightnessStats(Float.intBitsToFloat(msg.arg1), msg.arg2);
                    break;

                case MSG_SWITCH_USER:
                    float newBrightness = msg.obj instanceof Float ? (float) msg.obj
                            : PowerManager.BRIGHTNESS_INVALID_FLOAT;
                    handleOnSwitchUser(msg.arg1, msg.arg2, newBrightness);
                    break;

                case MSG_BOOT_COMPLETED:
                    mBootCompleted = true;
                    updatePowerState();
                    break;

                case MSG_SWITCH_AUTOBRIGHTNESS_MODE:
                    boolean isIdle = msg.arg1 == AUTO_BRIGHTNESS_MODE_IDLE;
                    if (mAutomaticBrightnessController != null) {
                        mAutomaticBrightnessController.switchMode(msg.arg1, /* sendUpdate= */ true);
                        setAnimatorRampSpeeds(isIdle);
                    }
                    setDwbcStrongMode(msg.arg1);
                    break;

                case MSG_SET_DWBC_COLOR_OVERRIDE:
                    final float cct = Float.intBitsToFloat(msg.arg1);
                    setDwbcOverride(cct);
                    break;

                case MSG_SET_DWBC_LOGGING_ENABLED:
                    setDwbcLoggingEnabled(msg.arg1);
                    break;
                case MSG_SET_BRIGHTNESS_FROM_OFFLOAD:
                    if (mDisplayBrightnessController.setBrightnessFromOffload(
                            Float.intBitsToFloat(msg.arg1))) {
                        updatePowerState();
                    }
                    break;
            }
        }
    }


    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE))) {
                mHandler.postAtTime(() -> {
                    handleBrightnessModeChange();
                    updatePowerState();
                }, mClock.uptimeMillis());
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_FOR_ALS))) {
                int preset = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_FOR_ALS,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL,
                        UserHandle.USER_CURRENT);
                Slog.i(mTag, "Setting up auto-brightness for preset "
                        + autoBrightnessPresetToString(preset));
                setUpAutoBrightness(mContext, mHandler);
                sendUpdatePowerState();
            } else {
                handleSettingsChange();
            }
        }
    }

    private final class ScreenOnUnblocker implements WindowManagerPolicy.ScreenOnListener {
        @Override
        public void onScreenOn() {
            Message msg = mHandler.obtainMessage(MSG_SCREEN_ON_UNBLOCKED, this);
            mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
        }
    }

    private final class ScreenOffUnblocker implements WindowManagerPolicy.ScreenOffListener {
        @Override
        public void onScreenOff() {
            Message msg = mHandler.obtainMessage(MSG_SCREEN_OFF_UNBLOCKED, this);
            mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
        }
    }

    @Override
    public void setAutoBrightnessLoggingEnabled(boolean enabled) {
        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.setLoggingEnabled(enabled);
        }
    }

    @Override // DisplayWhiteBalanceController.Callbacks
    public void updateWhiteBalance() {
        sendUpdatePowerState();
    }

    @Override
    public void setDisplayWhiteBalanceLoggingEnabled(boolean enabled) {
        Message msg = mHandler.obtainMessage();
        msg.what = MSG_SET_DWBC_LOGGING_ENABLED;
        msg.arg1 = enabled ? 1 : 0;
        msg.sendToTarget();
    }

    @Override
    public void setAmbientColorTemperatureOverride(float cct) {
        Message msg = mHandler.obtainMessage();
        msg.what = MSG_SET_DWBC_COLOR_OVERRIDE;
        msg.arg1 = Float.floatToIntBits(cct);
        msg.sendToTarget();
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
        Clock getClock() {
            return SystemClock::uptimeMillis;
        }

        DisplayPowerState getDisplayPowerState(DisplayBlanker blanker, ColorFade colorFade,
                int displayId, int displayState) {
            return new DisplayPowerState(blanker, colorFade, displayId, displayState);
        }

        DualRampAnimator<DisplayPowerState> getDualRampAnimator(DisplayPowerState dps,
                FloatProperty<DisplayPowerState> firstProperty,
                FloatProperty<DisplayPowerState> secondProperty) {
            return new DualRampAnimator(dps, firstProperty, secondProperty);
        }

        WakelockController getWakelockController(int displayId,
                DisplayPowerCallbacks displayPowerCallbacks) {
            return new WakelockController(displayId, displayPowerCallbacks);
        }

        DisplayPowerProximityStateController getDisplayPowerProximityStateController(
                WakelockController wakelockController, DisplayDeviceConfig displayDeviceConfig,
                Looper looper, Runnable nudgeUpdatePowerState,
                int displayId, SensorManager sensorManager) {
            return new DisplayPowerProximityStateController(wakelockController, displayDeviceConfig,
                    looper, nudgeUpdatePowerState,
                    displayId, sensorManager, /* injector= */ null);
        }

        AutomaticBrightnessController getAutomaticBrightnessController(
                AutomaticBrightnessController.Callbacks callbacks, Looper looper,
                SensorManager sensorManager, Sensor lightSensor,
                SparseArray<BrightnessMappingStrategy> brightnessMappingStrategyMap,
                int lightSensorWarmUpTime, float brightnessMin, float brightnessMax,
                float dozeScaleFactor, int lightSensorRate, int initialLightSensorRate,
                long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
                long brighteningLightDebounceConfigIdle, long darkeningLightDebounceConfigIdle,
                boolean resetAmbientLuxAfterWarmUpConfig,
                HysteresisLevels ambientBrightnessThresholds,
                HysteresisLevels screenBrightnessThresholds,
                HysteresisLevels ambientBrightnessThresholdsIdle,
                HysteresisLevels screenBrightnessThresholdsIdle, Context context,
                BrightnessRangeController brightnessModeController,
                BrightnessThrottler brightnessThrottler, int ambientLightHorizonShort,
                int ambientLightHorizonLong, float userLux, float userNits,
                BrightnessClamperController brightnessClamperController,
                DisplayManagerFlags displayManagerFlags) {

            return new AutomaticBrightnessController(callbacks, looper, sensorManager, lightSensor,
                    brightnessMappingStrategyMap, lightSensorWarmUpTime, brightnessMin,
                    brightnessMax, dozeScaleFactor, lightSensorRate, initialLightSensorRate,
                    brighteningLightDebounceConfig, darkeningLightDebounceConfig,
                    brighteningLightDebounceConfigIdle, darkeningLightDebounceConfigIdle,
                    resetAmbientLuxAfterWarmUpConfig, ambientBrightnessThresholds,
                    screenBrightnessThresholds, ambientBrightnessThresholdsIdle,
                    screenBrightnessThresholdsIdle, context, brightnessModeController,
                    brightnessThrottler, ambientLightHorizonShort, ambientLightHorizonLong, userLux,
                    userNits, displayManagerFlags);
        }

        BrightnessMappingStrategy getDefaultModeBrightnessMapper(Context context,
                DisplayDeviceConfig displayDeviceConfig,
                DisplayWhiteBalanceController displayWhiteBalanceController) {
            return BrightnessMappingStrategy.create(context, displayDeviceConfig,
                    AUTO_BRIGHTNESS_MODE_DEFAULT, displayWhiteBalanceController);
        }

        ScreenOffBrightnessSensorController getScreenOffBrightnessSensorController(
                SensorManager sensorManager,
                Sensor lightSensor,
                Handler handler,
                ScreenOffBrightnessSensorController.Clock clock,
                int[] sensorValueToLux,
                BrightnessMappingStrategy brightnessMapper) {
            return new ScreenOffBrightnessSensorController(
                    sensorManager,
                    lightSensor,
                    handler,
                    clock,
                    sensorValueToLux,
                    brightnessMapper
            );
        }

        HighBrightnessModeController getHighBrightnessModeController(Handler handler, int width,
                int height, IBinder displayToken, String displayUniqueId, float brightnessMin,
                float brightnessMax, DisplayDeviceConfig.HighBrightnessModeData hbmData,
                HighBrightnessModeController.HdrBrightnessDeviceConfig hdrBrightnessCfg,
                Runnable hbmChangeCallback, HighBrightnessModeMetadata hbmMetadata,
                Context context) {
            return new HighBrightnessModeController(handler, width, height, displayToken,
                    displayUniqueId, brightnessMin, brightnessMax, hbmData, hdrBrightnessCfg,
                    hbmChangeCallback, hbmMetadata, context);
        }

        BrightnessRangeController getBrightnessRangeController(
                HighBrightnessModeController hbmController, Runnable modeChangeCallback,
                DisplayDeviceConfig displayDeviceConfig, Handler handler,
                DisplayManagerFlags flags, IBinder displayToken, DisplayDeviceInfo info) {
            return new BrightnessRangeController(hbmController,
                    modeChangeCallback, displayDeviceConfig, handler, flags, displayToken, info);
        }

        BrightnessClamperController getBrightnessClamperController(Handler handler,
                BrightnessClamperController.ClamperChangeListener clamperChangeListener,
                BrightnessClamperController.DisplayDeviceData data, Context context,
                DisplayManagerFlags flags, SensorManager sensorManager) {

            return new BrightnessClamperController(handler, clamperChangeListener, data, context,
                    flags, sensorManager);
        }

        DisplayWhiteBalanceController getDisplayWhiteBalanceController(Handler handler,
                SensorManager sensorManager, Resources resources) {
            return DisplayWhiteBalanceFactory.create(handler,
                    sensorManager, resources);
        }

        boolean isColorFadeEnabled() {
            return !ActivityManager.isLowRamDeviceStatic();
        }
    }

    static class CachedBrightnessInfo {
        public MutableFloat brightness = new MutableFloat(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        public MutableFloat adjustedBrightness =
                new MutableFloat(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        public MutableFloat brightnessMin =
                new MutableFloat(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        public MutableFloat brightnessMax =
                new MutableFloat(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        public MutableInt hbmMode = new MutableInt(BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF);
        public MutableFloat hbmTransitionPoint =
                new MutableFloat(HighBrightnessModeController.HBM_TRANSITION_POINT_INVALID);
        public MutableInt brightnessMaxReason =
                new MutableInt(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE);

        public boolean checkAndSetFloat(MutableFloat mf, float f) {
            if (mf.value != f) {
                mf.value = f;
                return true;
            }
            return false;
        }

        public boolean checkAndSetInt(MutableInt mi, int i) {
            if (mi.value != i) {
                mi.value = i;
                return true;
            }
            return false;
        }
    }
}
