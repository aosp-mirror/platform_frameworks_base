/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.app.IBatteryStats;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.LocalServices;
import com.android.server.am.BatteryStatsService;
import com.android.server.display.whitebalance.DisplayWhiteBalanceController;
import com.android.server.display.whitebalance.DisplayWhiteBalanceFactory;
import com.android.server.display.whitebalance.DisplayWhiteBalanceSettings;
import com.android.server.lights.LightsManager;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.List;

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
        DisplayWhiteBalanceController.Callbacks {
    private static final String TAG = "DisplayPowerController";
    private static final String SCREEN_ON_BLOCKED_TRACE_NAME = "Screen on blocked";
    private static final String SCREEN_OFF_BLOCKED_TRACE_NAME = "Screen off blocked";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT = false;

    // If true, uses the color fade on animation.
    // We might want to turn this off if we cannot get a guarantee that the screen
    // actually turns on and starts showing new content after the call to set the
    // screen state returns.  Playing the animation can also be somewhat slow.
    private static final boolean USE_COLOR_FADE_ON_ANIMATION = false;

    // The minimum reduction in brightness when dimmed.
    private static final int SCREEN_DIM_MINIMUM_REDUCTION = 10;

    private static final int COLOR_FADE_ON_ANIMATION_DURATION_MILLIS = 250;
    private static final int COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS = 400;

    private static final int MSG_UPDATE_POWER_STATE = 1;
    private static final int MSG_PROXIMITY_SENSOR_DEBOUNCED = 2;
    private static final int MSG_SCREEN_ON_UNBLOCKED = 3;
    private static final int MSG_SCREEN_OFF_UNBLOCKED = 4;
    private static final int MSG_CONFIGURE_BRIGHTNESS = 5;
    private static final int MSG_SET_TEMPORARY_BRIGHTNESS = 6;
    private static final int MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT = 7;

    private static final int PROXIMITY_UNKNOWN = -1;
    private static final int PROXIMITY_NEGATIVE = 0;
    private static final int PROXIMITY_POSITIVE = 1;

    // Proximity sensor debounce delay in milliseconds for positive or negative transitions.
    private static final int PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY = 0;
    private static final int PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY = 250;

    // Trigger proximity if distance is less than 5 cm.
    private static final float TYPICAL_PROXIMITY_THRESHOLD = 5.0f;

    // State machine constants for tracking initial brightness ramp skipping when enabled.
    private static final int RAMP_STATE_SKIP_NONE = 0;
    private static final int RAMP_STATE_SKIP_INITIAL = 1;
    private static final int RAMP_STATE_SKIP_AUTOBRIGHT = 2;

    private static final int REPORTED_TO_POLICY_SCREEN_OFF = 0;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_ON = 1;
    private static final int REPORTED_TO_POLICY_SCREEN_ON = 2;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_OFF = 3;

    private final Object mLock = new Object();

    private final Context mContext;

    // Our handler.
    private final DisplayControllerHandler mHandler;

    // Asynchronous callbacks into the power manager service.
    // Only invoked from the handler thread while no locks are held.
    private final DisplayPowerCallbacks mCallbacks;

    // Battery stats.
    private final IBatteryStats mBatteryStats;

    // The lights service.
    private final LightsManager mLights;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The window manager policy.
    private final WindowManagerPolicy mWindowManagerPolicy;

    // The display blanker.
    private final DisplayBlanker mBlanker;

    // Tracker for brightness changes.
    private final BrightnessTracker mBrightnessTracker;

    // Tracker for brightness settings changes.
    private final SettingsObserver mSettingsObserver;

    // The proximity sensor, or null if not available or needed.
    private Sensor mProximitySensor;

    // The doze screen brightness.
    private final int mScreenBrightnessDozeConfig;

    // The dim screen brightness.
    private final int mScreenBrightnessDimConfig;

    // The minimum allowed brightness.
    private final int mScreenBrightnessRangeMinimum;

    // The maximum allowed brightness.
    private final int mScreenBrightnessRangeMaximum;

    // The default screen brightness.
    private final int mScreenBrightnessDefault;

    // The minimum allowed brightness while in VR.
    private final int mScreenBrightnessForVrRangeMinimum;

    // The maximum allowed brightness while in VR.
    private final int mScreenBrightnessForVrRangeMaximum;

    // The default screen brightness for VR.
    private final int mScreenBrightnessForVrDefault;

    // True if auto-brightness should be used.
    private boolean mUseSoftwareAutoBrightnessConfig;

    // True if should use light sensor to automatically determine doze screen brightness.
    private final boolean mAllowAutoBrightnessWhileDozingConfig;

    // Whether or not the color fade on screen on / off is enabled.
    private final boolean mColorFadeEnabled;

    // True if we should fade the screen while turning it off, false if we should play
    // a stylish color fade animation instead.
    private boolean mColorFadeFadesConfig;

    // True if we need to fake a transition to off when coming out of a doze state.
    // Some display hardware will blank itself when coming out of doze in order to hide
    // artifacts. For these displays we fake a transition into OFF so that policy can appropriately
    // blank itself and begin an appropriate power on animation.
    private boolean mDisplayBlanksAfterDozeConfig;

    // True if there are only buckets of brightness values when the display is in the doze state,
    // rather than a full range of values. If this is true, then we'll avoid animating the screen
    // brightness since it'd likely be multiple jarring brightness transitions instead of just one
    // to reach the final state.
    private boolean mBrightnessBucketsInDozeConfig;

    // The pending power request.
    // Initially null until the first call to requestPowerState.
    // Guarded by mLock.
    private DisplayPowerRequest mPendingRequestLocked;

    // True if a request has been made to wait for the proximity sensor to go negative.
    // Guarded by mLock.
    private boolean mPendingWaitForNegativeProximityLocked;

    // True if the pending power request or wait for negative proximity flag
    // has been changed since the last update occurred.
    // Guarded by mLock.
    private boolean mPendingRequestChangedLocked;

    // Set to true when the important parts of the pending power request have been applied.
    // The important parts are mainly the screen state.  Brightness changes may occur
    // concurrently.
    // Guarded by mLock.
    private boolean mDisplayReadyLocked;

    // Set to true if a power state update is required.
    // Guarded by mLock.
    private boolean mPendingUpdatePowerStateLocked;

    /* The following state must only be accessed by the handler thread. */

    // The currently requested power state.
    // The power controller will progressively update its internal state to match
    // the requested power state.  Initially null until the first update.
    private DisplayPowerRequest mPowerRequest;

    // The current power state.
    // Must only be accessed on the handler thread.
    private DisplayPowerState mPowerState;

    // True if the device should wait for negative proximity sensor before
    // waking up the screen.  This is set to false as soon as a negative
    // proximity sensor measurement is observed or when the device is forced to
    // go to sleep by the user.  While true, the screen remains off.
    private boolean mWaitingForNegativeProximity;

    // The actual proximity sensor threshold value.
    private float mProximityThreshold;

    // Set to true if the proximity sensor listener has been registered
    // with the sensor manager.
    private boolean mProximitySensorEnabled;

    // The debounced proximity sensor state.
    private int mProximity = PROXIMITY_UNKNOWN;

    // The raw non-debounced proximity sensor state.
    private int mPendingProximity = PROXIMITY_UNKNOWN;
    private long mPendingProximityDebounceTime = -1; // -1 if fully debounced

    // True if the screen was turned off because of the proximity sensor.
    // When the screen turns on again, we report user activity to the power manager.
    private boolean mScreenOffBecauseOfProximity;

    // The currently active screen on unblocker.  This field is non-null whenever
    // we are waiting for a callback to release it and unblock the screen.
    private ScreenOnUnblocker mPendingScreenOnUnblocker;
    private ScreenOffUnblocker mPendingScreenOffUnblocker;

    // True if we were in the process of turning off the screen.
    // This allows us to recover more gracefully from situations where we abort
    // turning off the screen.
    private boolean mPendingScreenOff;

    // True if we have unfinished business and are holding a suspend blocker.
    private boolean mUnfinishedBusiness;

    // The elapsed real time when the screen on was blocked.
    private long mScreenOnBlockStartRealTime;
    private long mScreenOffBlockStartRealTime;

    // Screen state we reported to policy. Must be one of REPORTED_TO_POLICY_SCREEN_* fields.
    private int mReportedScreenStateToPolicy;

    // If the last recorded screen state was dozing or not.
    private boolean mDozing;

    // Remembers whether certain kinds of brightness adjustments
    // were recently applied so that we can decide how to transition.
    private boolean mAppliedAutoBrightness;
    private boolean mAppliedDimming;
    private boolean mAppliedLowPower;
    private boolean mAppliedScreenBrightnessOverride;
    private boolean mAppliedTemporaryBrightness;
    private boolean mAppliedTemporaryAutoBrightnessAdjustment;
    private boolean mAppliedBrightnessBoost;

    // Reason for which the brightness was last changed. See {@link BrightnessReason} for more
    // information.
    // At the time of this writing, this value is changed within updatePowerState() only, which is
    // limited to the thread used by DisplayControllerHandler.
    private BrightnessReason mBrightnessReason = new BrightnessReason();
    private BrightnessReason mBrightnessReasonTemp = new BrightnessReason();

    // Brightness animation ramp rates in brightness units per second
    private final int mBrightnessRampRateFast;
    private final int mBrightnessRampRateSlow;

    // Whether or not to skip the initial brightness ramps into STATE_ON.
    private final boolean mSkipScreenOnBrightnessRamp;

    // Display white balance components.
    @Nullable
    private final DisplayWhiteBalanceSettings mDisplayWhiteBalanceSettings;
    @Nullable
    private final DisplayWhiteBalanceController mDisplayWhiteBalanceController;

    // A record of state for skipping brightness ramps.
    private int mSkipRampState = RAMP_STATE_SKIP_NONE;

    // The first autobrightness value set when entering RAMP_STATE_SKIP_INITIAL.
    private int mInitialAutoBrightness;

    // The controller for the automatic brightness level.
    private AutomaticBrightnessController mAutomaticBrightnessController;

    // The mapper between ambient lux, display backlight values, and display brightness.
    @Nullable
    private BrightnessMappingStrategy mBrightnessMapper;

    // The current brightness configuration.
    @Nullable
    private BrightnessConfiguration mBrightnessConfiguration;

    // The last brightness that was set by the user and not temporary. Set to -1 when a brightness
    // has yet to be recorded.
    private int mLastUserSetScreenBrightness;

    // The screen brightenss setting has changed but not taken effect yet. If this is different
    // from the current screen brightness setting then this is coming from something other than us
    // and should be considered a user interaction.
    private int mPendingScreenBrightnessSetting;

    // The last observed screen brightness setting, either set by us or by the settings app on
    // behalf of the user.
    private int mCurrentScreenBrightnessSetting;

    // The temporary screen brightness. Typically set when a user is interacting with the
    // brightness slider but hasn't settled on a choice yet. Set to -1 when there's no temporary
    // brightness set.
    private int mTemporaryScreenBrightness;

    // The current screen brightness while in VR mode.
    private int mScreenBrightnessForVr;

    // The last auto brightness adjustment that was set by the user and not temporary. Set to
    // Float.NaN when an auto-brightness adjustment hasn't been recorded yet.
    private float mAutoBrightnessAdjustment;

    // The pending auto brightness adjustment that will take effect on the next power state update.
    private float mPendingAutoBrightnessAdjustment;

    // The temporary auto brightness adjustment. Typically set when a user is interacting with the
    // adjustment slider but hasn't settled on a choice yet. Set to Float.NaN when there's no
    // temporary adjustment set.
    private float mTemporaryAutoBrightnessAdjustment;

    // Animators.
    private ObjectAnimator mColorFadeOnAnimator;
    private ObjectAnimator mColorFadeOffAnimator;
    private RampAnimator<DisplayPowerState> mScreenBrightnessRampAnimator;

    /**
     * Creates the display power controller.
     */
    public DisplayPowerController(Context context,
            DisplayPowerCallbacks callbacks, Handler handler,
            SensorManager sensorManager, DisplayBlanker blanker) {
        mHandler = new DisplayControllerHandler(handler.getLooper());
        mBrightnessTracker = new BrightnessTracker(context, null);
        mSettingsObserver = new SettingsObserver(mHandler);
        mCallbacks = callbacks;

        mBatteryStats = BatteryStatsService.getService();
        mLights = LocalServices.getService(LightsManager.class);
        mSensorManager = sensorManager;
        mWindowManagerPolicy = LocalServices.getService(WindowManagerPolicy.class);
        mBlanker = blanker;
        mContext = context;

        final Resources resources = context.getResources();
        final int screenBrightnessSettingMinimum = clampAbsoluteBrightness(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum));

        mScreenBrightnessDozeConfig = clampAbsoluteBrightness(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDoze));

        mScreenBrightnessDimConfig = clampAbsoluteBrightness(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim));

        mScreenBrightnessRangeMinimum =
                Math.min(screenBrightnessSettingMinimum, mScreenBrightnessDimConfig);

        mScreenBrightnessRangeMaximum = clampAbsoluteBrightness(resources.getInteger(
                    com.android.internal.R.integer.config_screenBrightnessSettingMaximum));
        mScreenBrightnessDefault = clampAbsoluteBrightness(resources.getInteger(
                    com.android.internal.R.integer.config_screenBrightnessSettingDefault));

        mScreenBrightnessForVrRangeMinimum = clampAbsoluteBrightness(resources.getInteger(
                    com.android.internal.R.integer.config_screenBrightnessForVrSettingMinimum));
        mScreenBrightnessForVrRangeMaximum = clampAbsoluteBrightness(resources.getInteger(
                    com.android.internal.R.integer.config_screenBrightnessForVrSettingMaximum));
        mScreenBrightnessForVrDefault = clampAbsoluteBrightness(resources.getInteger(
                    com.android.internal.R.integer.config_screenBrightnessForVrSettingDefault));

        mUseSoftwareAutoBrightnessConfig = resources.getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);

        mAllowAutoBrightnessWhileDozingConfig = resources.getBoolean(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing);

        mBrightnessRampRateFast = resources.getInteger(
                com.android.internal.R.integer.config_brightness_ramp_rate_fast);
        mBrightnessRampRateSlow = resources.getInteger(
                com.android.internal.R.integer.config_brightness_ramp_rate_slow);
        mSkipScreenOnBrightnessRamp = resources.getBoolean(
                com.android.internal.R.bool.config_skipScreenOnBrightnessRamp);

        if (mUseSoftwareAutoBrightnessConfig) {
            final float dozeScaleFactor = resources.getFraction(
                    com.android.internal.R.fraction.config_screenAutoBrightnessDozeScaleFactor,
                    1, 1);

            int[] ambientBrighteningThresholds = resources.getIntArray(
                    com.android.internal.R.array.config_ambientBrighteningThresholds);
            int[] ambientDarkeningThresholds = resources.getIntArray(
                    com.android.internal.R.array.config_ambientDarkeningThresholds);
            int[] ambientThresholdLevels = resources.getIntArray(
                    com.android.internal.R.array.config_ambientThresholdLevels);
            HysteresisLevels ambientBrightnessThresholds = new HysteresisLevels(
                    ambientBrighteningThresholds, ambientDarkeningThresholds,
                    ambientThresholdLevels);

            int[] screenBrighteningThresholds = resources.getIntArray(
                    com.android.internal.R.array.config_screenBrighteningThresholds);
            int[] screenDarkeningThresholds = resources.getIntArray(
                    com.android.internal.R.array.config_screenDarkeningThresholds);
            int[] screenThresholdLevels = resources.getIntArray(
                    com.android.internal.R.array.config_screenThresholdLevels);
            HysteresisLevels screenBrightnessThresholds = new HysteresisLevels(
                    screenBrighteningThresholds, screenDarkeningThresholds, screenThresholdLevels);

            long brighteningLightDebounce = resources.getInteger(
                    com.android.internal.R.integer.config_autoBrightnessBrighteningLightDebounce);
            long darkeningLightDebounce = resources.getInteger(
                    com.android.internal.R.integer.config_autoBrightnessDarkeningLightDebounce);
            boolean autoBrightnessResetAmbientLuxAfterWarmUp = resources.getBoolean(
                    com.android.internal.R.bool.config_autoBrightnessResetAmbientLuxAfterWarmUp);

            int lightSensorWarmUpTimeConfig = resources.getInteger(
                    com.android.internal.R.integer.config_lightSensorWarmupTime);
            int lightSensorRate = resources.getInteger(
                    com.android.internal.R.integer.config_autoBrightnessLightSensorRate);
            int initialLightSensorRate = resources.getInteger(
                    com.android.internal.R.integer.config_autoBrightnessInitialLightSensorRate);
            if (initialLightSensorRate == -1) {
                initialLightSensorRate = lightSensorRate;
            } else if (initialLightSensorRate > lightSensorRate) {
                Slog.w(TAG, "Expected config_autoBrightnessInitialLightSensorRate ("
                        + initialLightSensorRate + ") to be less than or equal to "
                        + "config_autoBrightnessLightSensorRate (" + lightSensorRate + ").");
            }
            int shortTermModelTimeout = resources.getInteger(
                    com.android.internal.R.integer.config_autoBrightnessShortTermModelTimeout);

            String lightSensorType = resources.getString(
                    com.android.internal.R.string.config_displayLightSensorType);
            Sensor lightSensor = findDisplayLightSensor(lightSensorType);

            mBrightnessMapper = BrightnessMappingStrategy.create(resources);
            if (mBrightnessMapper != null) {
                mAutomaticBrightnessController = new AutomaticBrightnessController(this,
                        handler.getLooper(), sensorManager, lightSensor, mBrightnessMapper,
                        lightSensorWarmUpTimeConfig, mScreenBrightnessRangeMinimum,
                        mScreenBrightnessRangeMaximum, dozeScaleFactor, lightSensorRate,
                        initialLightSensorRate, brighteningLightDebounce, darkeningLightDebounce,
                        autoBrightnessResetAmbientLuxAfterWarmUp, ambientBrightnessThresholds,
                        screenBrightnessThresholds, shortTermModelTimeout,
                        context.getPackageManager());
            } else {
                mUseSoftwareAutoBrightnessConfig = false;
            }
        }

        mColorFadeEnabled = !ActivityManager.isLowRamDeviceStatic();
        mColorFadeFadesConfig = resources.getBoolean(
                com.android.internal.R.bool.config_animateScreenLights);

        mDisplayBlanksAfterDozeConfig = resources.getBoolean(
                com.android.internal.R.bool.config_displayBlanksAfterDoze);

        mBrightnessBucketsInDozeConfig = resources.getBoolean(
                com.android.internal.R.bool.config_displayBrightnessBucketsInDoze);

        if (!DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT) {
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (mProximitySensor != null) {
                mProximityThreshold = Math.min(mProximitySensor.getMaximumRange(),
                        TYPICAL_PROXIMITY_THRESHOLD);
            }
        }

        mCurrentScreenBrightnessSetting = getScreenBrightnessSetting();
        mScreenBrightnessForVr = getScreenBrightnessForVrSetting();
        mAutoBrightnessAdjustment = getAutoBrightnessAdjustmentSetting();
        mTemporaryScreenBrightness = -1;
        mPendingScreenBrightnessSetting = -1;
        mTemporaryAutoBrightnessAdjustment = Float.NaN;
        mPendingAutoBrightnessAdjustment = Float.NaN;

        DisplayWhiteBalanceSettings displayWhiteBalanceSettings = null;
        DisplayWhiteBalanceController displayWhiteBalanceController = null;
        try {
            displayWhiteBalanceSettings = new DisplayWhiteBalanceSettings(mContext, mHandler);
            displayWhiteBalanceController = DisplayWhiteBalanceFactory.create(mHandler,
                    mSensorManager, resources);
            displayWhiteBalanceSettings.setCallbacks(this);
            displayWhiteBalanceController.setCallbacks(this);
        } catch (Exception e) {
            Slog.e(TAG, "failed to set up display white-balance: " + e);
        }
        mDisplayWhiteBalanceSettings = displayWhiteBalanceSettings;
        mDisplayWhiteBalanceController = displayWhiteBalanceController;
    }

    private Sensor findDisplayLightSensor(String sensorType) {
        if (!TextUtils.isEmpty(sensorType)) {
            List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
            for (int i = 0; i < sensors.size(); i++) {
                Sensor sensor = sensors.get(i);
                if (sensorType.equals(sensor.getStringType())) {
                    return sensor;
                }
            }
        }
        return mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    /**
     * Returns true if the proximity sensor screen-off function is available.
     */
    public boolean isProximitySensorAvailable() {
        return mProximitySensor != null;
    }

    /**
     * Get the {@link BrightnessChangeEvent}s for the specified user.
     * @param userId userId to fetch data for
     * @param includePackage if false will null out the package name in events
     */
    public ParceledListSlice<BrightnessChangeEvent> getBrightnessEvents(
            @UserIdInt int userId, boolean includePackage) {
        return mBrightnessTracker.getEvents(userId, includePackage);
    }

    public void onSwitchUser(@UserIdInt int newUserId) {
        handleSettingsChange(true /* userSwitch */);
        mBrightnessTracker.onSwitchUser(newUserId);
    }

    public ParceledListSlice<AmbientBrightnessDayStats> getAmbientBrightnessStats(
            @UserIdInt int userId) {
        return mBrightnessTracker.getAmbientBrightnessStats(userId);
    }

    /**
     * Persist the brightness slider events and ambient brightness stats to disk.
     */
    public void persistBrightnessTrackerState() {
        mBrightnessTracker.persistBrightnessTrackerState();
    }

    /**
     * Requests a new power state.
     * The controller makes a copy of the provided object and then
     * begins adjusting the power state to match what was requested.
     *
     * @param request The requested power state.
     * @param waitForNegativeProximity If true, issues a request to wait for
     * negative proximity before turning the screen back on, assuming the screen
     * was turned off by the proximity sensor.
     * @return True if display is ready, false if there are important changes that must
     * be made asynchronously (such as turning the screen on), in which case the caller
     * should grab a wake lock, watch for {@link DisplayPowerCallbacks#onStateChanged()}
     * then try the request again later until the state converges.
     */
    public boolean requestPowerState(DisplayPowerRequest request,
            boolean waitForNegativeProximity) {
        if (DEBUG) {
            Slog.d(TAG, "requestPowerState: "
                    + request + ", waitForNegativeProximity=" + waitForNegativeProximity);
        }

        synchronized (mLock) {
            boolean changed = false;

            if (waitForNegativeProximity
                    && !mPendingWaitForNegativeProximityLocked) {
                mPendingWaitForNegativeProximityLocked = true;
                changed = true;
            }

            if (mPendingRequestLocked == null) {
                mPendingRequestLocked = new DisplayPowerRequest(request);
                changed = true;
            } else if (!mPendingRequestLocked.equals(request)) {
                mPendingRequestLocked.copyFrom(request);
                changed = true;
            }

            if (changed) {
                mDisplayReadyLocked = false;
            }

            if (changed && !mPendingRequestChangedLocked) {
                mPendingRequestChangedLocked = true;
                sendUpdatePowerStateLocked();
            }

            return mDisplayReadyLocked;
        }
    }

    public BrightnessConfiguration getDefaultBrightnessConfiguration() {
        if (mAutomaticBrightnessController == null) {
            return null;
        }
        return mAutomaticBrightnessController.getDefaultConfig();
    }

    private void sendUpdatePowerState() {
        synchronized (mLock) {
            sendUpdatePowerStateLocked();
        }
    }

    private void sendUpdatePowerStateLocked() {
        if (!mPendingUpdatePowerStateLocked) {
            mPendingUpdatePowerStateLocked = true;
            Message msg = mHandler.obtainMessage(MSG_UPDATE_POWER_STATE);
            mHandler.sendMessage(msg);
        }
    }

    private void initialize() {
        // Initialize the power state object for the default display.
        // In the future, we might manage multiple displays independently.
        mPowerState = new DisplayPowerState(mBlanker,
                mColorFadeEnabled ? new ColorFade(Display.DEFAULT_DISPLAY) : null);

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

        mScreenBrightnessRampAnimator = new RampAnimator<DisplayPowerState>(
                mPowerState, DisplayPowerState.SCREEN_BRIGHTNESS);
        mScreenBrightnessRampAnimator.setListener(mRampAnimatorListener);

        // Initialize screen state for battery stats.
        try {
            mBatteryStats.noteScreenState(mPowerState.getScreenState());
            mBatteryStats.noteScreenBrightness(mPowerState.getScreenBrightness());
        } catch (RemoteException ex) {
            // same process
        }

        // Initialize all of the brightness tracking state
        final float brightness = convertToNits(mPowerState.getScreenBrightness());
        if (brightness >= 0.0f) {
            mBrightnessTracker.start(brightness);
        }

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FOR_VR),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
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
        }
    };

    private void updatePowerState() {
        // Update the power state request.
        final boolean mustNotify;
        final int previousPolicy;
        boolean mustInitialize = false;
        int brightnessAdjustmentFlags = 0;
        mBrightnessReasonTemp.set(null);

        synchronized (mLock) {
            mPendingUpdatePowerStateLocked = false;
            if (mPendingRequestLocked == null) {
                return; // wait until first actual power request
            }

            if (mPowerRequest == null) {
                mPowerRequest = new DisplayPowerRequest(mPendingRequestLocked);
                mWaitingForNegativeProximity = mPendingWaitForNegativeProximityLocked;
                mPendingWaitForNegativeProximityLocked = false;
                mPendingRequestChangedLocked = false;
                mustInitialize = true;
                // Assume we're on and bright until told otherwise, since that's the state we turn
                // on in.
                previousPolicy = DisplayPowerRequest.POLICY_BRIGHT;
            } else if (mPendingRequestChangedLocked) {
                previousPolicy = mPowerRequest.policy;
                mPowerRequest.copyFrom(mPendingRequestLocked);
                mWaitingForNegativeProximity |= mPendingWaitForNegativeProximityLocked;
                mPendingWaitForNegativeProximityLocked = false;
                mPendingRequestChangedLocked = false;
                mDisplayReadyLocked = false;
            } else {
                previousPolicy = mPowerRequest.policy;
            }

            mustNotify = !mDisplayReadyLocked;
        }

        // Initialize things the first time the power state is changed.
        if (mustInitialize) {
            initialize();
        }

        // Compute the basic display state using the policy.
        // We might override this below based on other factors.
        int state;
        int brightness = PowerManager.BRIGHTNESS_DEFAULT;
        boolean performScreenOffTransition = false;
        switch (mPowerRequest.policy) {
            case DisplayPowerRequest.POLICY_OFF:
                state = Display.STATE_OFF;
                performScreenOffTransition = true;
                break;
            case DisplayPowerRequest.POLICY_DOZE:
                if (mPowerRequest.dozeScreenState != Display.STATE_UNKNOWN) {
                    state = mPowerRequest.dozeScreenState;
                } else {
                    state = Display.STATE_DOZE;
                }
                if (!mAllowAutoBrightnessWhileDozingConfig) {
                    brightness = mPowerRequest.dozeScreenBrightness;
                    mBrightnessReasonTemp.setReason(BrightnessReason.REASON_DOZE);
                }
                break;
            case DisplayPowerRequest.POLICY_VR:
                state = Display.STATE_VR;
                break;
            case DisplayPowerRequest.POLICY_DIM:
            case DisplayPowerRequest.POLICY_BRIGHT:
            default:
                state = Display.STATE_ON;
                break;
        }
        assert(state != Display.STATE_UNKNOWN);

        // Apply the proximity sensor.
        if (mProximitySensor != null) {
            if (mPowerRequest.useProximitySensor && state != Display.STATE_OFF) {
                setProximitySensorEnabled(true);
                if (!mScreenOffBecauseOfProximity
                        && mProximity == PROXIMITY_POSITIVE) {
                    mScreenOffBecauseOfProximity = true;
                    sendOnProximityPositiveWithWakelock();
                }
            } else if (mWaitingForNegativeProximity
                    && mScreenOffBecauseOfProximity
                    && mProximity == PROXIMITY_POSITIVE
                    && state != Display.STATE_OFF) {
                setProximitySensorEnabled(true);
            } else {
                setProximitySensorEnabled(false);
                mWaitingForNegativeProximity = false;
            }
            if (mScreenOffBecauseOfProximity
                    && mProximity != PROXIMITY_POSITIVE) {
                mScreenOffBecauseOfProximity = false;
                sendOnProximityNegativeWithWakelock();
            }
        } else {
            mWaitingForNegativeProximity = false;
        }
        if (mScreenOffBecauseOfProximity) {
            state = Display.STATE_OFF;
        }

        // Animate the screen state change unless already animating.
        // The transition may be deferred, so after this point we will use the
        // actual state instead of the desired one.
        final int oldState = mPowerState.getScreenState();
        animateScreenStateChange(state, performScreenOffTransition);
        state = mPowerState.getScreenState();

        // Use zero brightness when screen is off.
        if (state == Display.STATE_OFF) {
            brightness = PowerManager.BRIGHTNESS_OFF;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_SCREEN_OFF);
            mLights.getLight(LightsManager.LIGHT_ID_BUTTONS).setBrightness(brightness);
        }

        // Disable button lights when dozing
        if (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) {
            mLights.getLight(LightsManager.LIGHT_ID_BUTTONS)
                    .setBrightness(PowerManager.BRIGHTNESS_OFF);
        }

        // Always use the VR brightness when in the VR state.
        if (state == Display.STATE_VR) {
            brightness = mScreenBrightnessForVr;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_VR);
        }

        if (brightness < 0 && mPowerRequest.screenBrightnessOverride > 0) {
            brightness = mPowerRequest.screenBrightnessOverride;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_OVERRIDE);
            mAppliedScreenBrightnessOverride = true;
        } else {
            mAppliedScreenBrightnessOverride = false;
        }

        final boolean autoBrightnessEnabledInDoze =
                mAllowAutoBrightnessWhileDozingConfig && Display.isDozeState(state);
        final boolean autoBrightnessEnabled = mPowerRequest.useAutoBrightness
                    && (state == Display.STATE_ON || autoBrightnessEnabledInDoze)
                    && brightness < 0
                    && mAutomaticBrightnessController != null;

        final boolean userSetBrightnessChanged = updateUserSetScreenBrightness();

        // Use the temporary screen brightness if there isn't an override, either from
        // WindowManager or based on the display state.
        if (mTemporaryScreenBrightness > 0) {
            brightness = mTemporaryScreenBrightness;
            mAppliedTemporaryBrightness = true;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_TEMPORARY);
        } else {
            mAppliedTemporaryBrightness = false;
        }

        final boolean autoBrightnessAdjustmentChanged = updateAutoBrightnessAdjustment();
        if (autoBrightnessAdjustmentChanged) {
            mTemporaryAutoBrightnessAdjustment = Float.NaN;
        }

        // Use the autobrightness adjustment override if set.
        final float autoBrightnessAdjustment;
        if (!Float.isNaN(mTemporaryAutoBrightnessAdjustment)) {
            autoBrightnessAdjustment = mTemporaryAutoBrightnessAdjustment;
            brightnessAdjustmentFlags = BrightnessReason.ADJUSTMENT_AUTO_TEMP;
            mAppliedTemporaryAutoBrightnessAdjustment = true;
        } else {
            autoBrightnessAdjustment = mAutoBrightnessAdjustment;
            brightnessAdjustmentFlags = BrightnessReason.ADJUSTMENT_AUTO;
            mAppliedTemporaryAutoBrightnessAdjustment = false;
        }

        // Apply brightness boost.
        // We do this here after deciding whether auto-brightness is enabled so that we don't
        // disable the light sensor during this temporary state.  That way when boost ends we will
        // be able to resume normal auto-brightness behavior without any delay.
        if (mPowerRequest.boostScreenBrightness
                && brightness != PowerManager.BRIGHTNESS_OFF) {
            brightness = PowerManager.BRIGHTNESS_ON;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_BOOST);
            mAppliedBrightnessBoost = true;
        } else {
            mAppliedBrightnessBoost = false;
        }

        // If the brightness is already set then it's been overridden by something other than the
        // user, or is a temporary adjustment.
        boolean userInitiatedChange = brightness < 0
                && (autoBrightnessAdjustmentChanged || userSetBrightnessChanged);

        boolean hadUserBrightnessPoint = false;
        // Configure auto-brightness.
        if (mAutomaticBrightnessController != null) {
            hadUserBrightnessPoint = mAutomaticBrightnessController.hasUserDataPoints();
            mAutomaticBrightnessController.configure(autoBrightnessEnabled,
                    mBrightnessConfiguration,
                    mLastUserSetScreenBrightness / (float) PowerManager.BRIGHTNESS_ON,
                    userSetBrightnessChanged, autoBrightnessAdjustment,
                    autoBrightnessAdjustmentChanged, mPowerRequest.policy);
        }

        // Apply auto-brightness.
        boolean slowChange = false;
        if (brightness < 0) {
            float newAutoBrightnessAdjustment = autoBrightnessAdjustment;
            if (autoBrightnessEnabled) {
                brightness = mAutomaticBrightnessController.getAutomaticScreenBrightness();
                newAutoBrightnessAdjustment =
                        mAutomaticBrightnessController.getAutomaticScreenBrightnessAdjustment();
            }

            if (brightness >= 0) {
                // Use current auto-brightness value and slowly adjust to changes.
                brightness = clampScreenBrightness(brightness);
                if (mAppliedAutoBrightness && !autoBrightnessAdjustmentChanged) {
                    slowChange = true; // slowly adapt to auto-brightness
                }
                // Tell the rest of the system about the new brightness. Note that we do this
                // before applying the low power or dim transformations so that the slider
                // accurately represents the full possible range, even if they range changes what
                // it means in absolute terms.
                putScreenBrightnessSetting(brightness);
                mAppliedAutoBrightness = true;
                mBrightnessReasonTemp.setReason(BrightnessReason.REASON_AUTOMATIC);
            } else {
                mAppliedAutoBrightness = false;
            }
            if (autoBrightnessAdjustment != newAutoBrightnessAdjustment) {
                // If the autobrightness controller has decided to change the adjustment value
                // used, make sure that's reflected in settings.
                putAutoBrightnessAdjustmentSetting(newAutoBrightnessAdjustment);
            } else {
                // Adjustment values resulted in no change
                brightnessAdjustmentFlags = 0;
            }
        } else {
            mAppliedAutoBrightness = false;
            brightnessAdjustmentFlags = 0;
        }

        // Use default brightness when dozing unless overridden.
        if (brightness < 0 && Display.isDozeState(state)) {
            brightness = mScreenBrightnessDozeConfig;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_DOZE_DEFAULT);
        }

        // Apply manual brightness.
        if (brightness < 0) {
            brightness = clampScreenBrightness(mCurrentScreenBrightnessSetting);
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_MANUAL);
        }

        // Apply dimming by at least some minimum amount when user activity
        // timeout is about to expire.
        if (mPowerRequest.policy == DisplayPowerRequest.POLICY_DIM) {
            if (brightness > mScreenBrightnessRangeMinimum) {
                brightness = Math.max(Math.min(brightness - SCREEN_DIM_MINIMUM_REDUCTION,
                        mScreenBrightnessDimConfig), mScreenBrightnessRangeMinimum);
                mBrightnessReasonTemp.addModifier(BrightnessReason.MODIFIER_DIMMED);
            }
            if (!mAppliedDimming) {
                slowChange = false;
            }
            mAppliedDimming = true;
        } else if (mAppliedDimming) {
            slowChange = false;
            mAppliedDimming = false;
        }

        // If low power mode is enabled, scale brightness by screenLowPowerBrightnessFactor
        // as long as it is above the minimum threshold.
        if (mPowerRequest.lowPowerMode) {
            if (brightness > mScreenBrightnessRangeMinimum) {
                final float brightnessFactor =
                        Math.min(mPowerRequest.screenLowPowerBrightnessFactor, 1);
                final int lowPowerBrightness = (int) (brightness * brightnessFactor);
                brightness = Math.max(lowPowerBrightness, mScreenBrightnessRangeMinimum);
                mBrightnessReasonTemp.addModifier(BrightnessReason.MODIFIER_LOW_POWER);
            }
            if (!mAppliedLowPower) {
                slowChange = false;
            }
            mAppliedLowPower = true;
        } else if (mAppliedLowPower) {
            slowChange = false;
            mAppliedLowPower = false;
        }

        // Animate the screen brightness when the screen is on or dozing.
        // Skip the animation when the screen is off or suspended or transition to/from VR.
        if (!mPendingScreenOff) {
            if (mSkipScreenOnBrightnessRamp) {
                if (state == Display.STATE_ON) {
                    if (mSkipRampState == RAMP_STATE_SKIP_NONE && mDozing) {
                        mInitialAutoBrightness = brightness;
                        mSkipRampState = RAMP_STATE_SKIP_INITIAL;
                    } else if (mSkipRampState == RAMP_STATE_SKIP_INITIAL
                            && mUseSoftwareAutoBrightnessConfig
                            && brightness != mInitialAutoBrightness) {
                        mSkipRampState = RAMP_STATE_SKIP_AUTOBRIGHT;
                    } else if (mSkipRampState == RAMP_STATE_SKIP_AUTOBRIGHT) {
                        mSkipRampState = RAMP_STATE_SKIP_NONE;
                    }
                } else {
                    mSkipRampState = RAMP_STATE_SKIP_NONE;
                }
            }

            final boolean wasOrWillBeInVr =
                    (state == Display.STATE_VR || oldState == Display.STATE_VR);
            final boolean initialRampSkip =
                    state == Display.STATE_ON && mSkipRampState != RAMP_STATE_SKIP_NONE;
            // While dozing, sometimes the brightness is split into buckets. Rather than animating
            // through the buckets, which is unlikely to be smooth in the first place, just jump
            // right to the suggested brightness.
            final boolean hasBrightnessBuckets =
                    Display.isDozeState(state) && mBrightnessBucketsInDozeConfig;
            // If the color fade is totally covering the screen then we can change the backlight
            // level without it being a noticeable jump since any actual content isn't yet visible.
            final boolean isDisplayContentVisible =
                    mColorFadeEnabled && mPowerState.getColorFadeLevel() == 1.0f;
            final boolean brightnessIsTemporary =
                    mAppliedTemporaryBrightness || mAppliedTemporaryAutoBrightnessAdjustment;
            if (initialRampSkip || hasBrightnessBuckets
                    || wasOrWillBeInVr || !isDisplayContentVisible || brightnessIsTemporary) {
                animateScreenBrightness(brightness, 0);
            } else {
                animateScreenBrightness(brightness,
                        slowChange ? mBrightnessRampRateSlow : mBrightnessRampRateFast);
            }

            if (!brightnessIsTemporary) {
                if (userInitiatedChange && (mAutomaticBrightnessController == null
                        || !mAutomaticBrightnessController.hasValidAmbientLux())) {
                    // If we don't have a valid lux reading we can't report a valid
                    // slider event so notify as if the system changed the brightness.
                    userInitiatedChange = false;
                }
                notifyBrightnessChanged(brightness, userInitiatedChange, hadUserBrightnessPoint);
            }

        }

        // Log any changes to what is currently driving the brightness setting.
        if (!mBrightnessReasonTemp.equals(mBrightnessReason) || brightnessAdjustmentFlags != 0) {
            Slog.v(TAG, "Brightness [" + brightness + "] reason changing to: '"
                    + mBrightnessReasonTemp.toString(brightnessAdjustmentFlags)
                    + "', previous reason: '" + mBrightnessReason + "'.");
            mBrightnessReason.set(mBrightnessReasonTemp);
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
        final boolean ready = mPendingScreenOnUnblocker == null &&
                (!mColorFadeEnabled ||
                        (!mColorFadeOnAnimator.isStarted() && !mColorFadeOffAnimator.isStarted()))
                && mPowerState.waitUntilClean(mCleanListener);
        final boolean finished = ready
                && !mScreenBrightnessRampAnimator.isAnimating();

        // Notify policy about screen turned on.
        if (ready && state != Display.STATE_OFF
                && mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_TURNING_ON) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_ON);
            mWindowManagerPolicy.screenTurnedOn();
        }

        // Grab a wake lock if we have unfinished business.
        if (!finished && !mUnfinishedBusiness) {
            if (DEBUG) {
                Slog.d(TAG, "Unfinished business...");
            }
            mCallbacks.acquireSuspendBlocker();
            mUnfinishedBusiness = true;
        }

        // Notify the power manager when ready.
        if (ready && mustNotify) {
            // Send state change.
            synchronized (mLock) {
                if (!mPendingRequestChangedLocked) {
                    mDisplayReadyLocked = true;

                    if (DEBUG) {
                        Slog.d(TAG, "Display ready!");
                    }
                }
            }
            sendOnStateChangedWithWakelock();
        }

        // Release the wake lock when we have no unfinished business.
        if (finished && mUnfinishedBusiness) {
            if (DEBUG) {
                Slog.d(TAG, "Finished business...");
            }
            mUnfinishedBusiness = false;
            mCallbacks.releaseSuspendBlocker();
        }

        // Record if dozing for future comparison.
        mDozing = state != Display.STATE_ON;

        if (previousPolicy != mPowerRequest.policy) {
            logDisplayPolicyChanged(mPowerRequest.policy);
        }
    }

    @Override
    public void updateBrightness() {
        sendUpdatePowerState();
    }

    public void setBrightnessConfiguration(BrightnessConfiguration c) {
        Message msg = mHandler.obtainMessage(MSG_CONFIGURE_BRIGHTNESS, c);
        msg.sendToTarget();
    }

    public void setTemporaryBrightness(int brightness) {
        Message msg = mHandler.obtainMessage(MSG_SET_TEMPORARY_BRIGHTNESS,
                brightness, 0 /*unused*/);
        msg.sendToTarget();
    }

    public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
        Message msg = mHandler.obtainMessage(MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT,
                Float.floatToIntBits(adjustment), 0 /*unused*/);
        msg.sendToTarget();
    }

    private void blockScreenOn() {
        if (mPendingScreenOnUnblocker == null) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
            mPendingScreenOnUnblocker = new ScreenOnUnblocker();
            mScreenOnBlockStartRealTime = SystemClock.elapsedRealtime();
            Slog.i(TAG, "Blocking screen on until initial contents have been drawn.");
        }
    }

    private void unblockScreenOn() {
        if (mPendingScreenOnUnblocker != null) {
            mPendingScreenOnUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - mScreenOnBlockStartRealTime;
            Slog.i(TAG, "Unblocked screen on after " + delay + " ms");
            Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
        }
    }

    private void blockScreenOff() {
        if (mPendingScreenOffUnblocker == null) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, SCREEN_OFF_BLOCKED_TRACE_NAME, 0);
            mPendingScreenOffUnblocker = new ScreenOffUnblocker();
            mScreenOffBlockStartRealTime = SystemClock.elapsedRealtime();
            Slog.i(TAG, "Blocking screen off");
        }
    }

    private void unblockScreenOff() {
        if (mPendingScreenOffUnblocker != null) {
            mPendingScreenOffUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - mScreenOffBlockStartRealTime;
            Slog.i(TAG, "Unblocked screen off after " + delay + " ms");
            Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, SCREEN_OFF_BLOCKED_TRACE_NAME, 0);
        }
    }

    private boolean setScreenState(int state) {
        return setScreenState(state, false /*reportOnly*/);
    }

    private boolean setScreenState(int state, boolean reportOnly) {
        final boolean isOff = (state == Display.STATE_OFF);
        if (mPowerState.getScreenState() != state) {

            // If we are trying to turn screen off, give policy a chance to do something before we
            // actually turn the screen off.
            if (isOff && !mScreenOffBecauseOfProximity) {
                if (mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_ON) {
                    setReportedScreenState(REPORTED_TO_POLICY_SCREEN_TURNING_OFF);
                    blockScreenOff();
                    mWindowManagerPolicy.screenTurningOff(mPendingScreenOffUnblocker);
                    unblockScreenOff();
                } else if (mPendingScreenOffUnblocker != null) {
                    // Abort doing the state change until screen off is unblocked.
                    return false;
                }
            }

            if (!reportOnly) {
                Trace.traceCounter(Trace.TRACE_TAG_POWER, "ScreenState", state);
                mPowerState.setScreenState(state);
                // Tell battery stats about the transition.
                try {
                    mBatteryStats.noteScreenState(state);
                } catch (RemoteException ex) {
                    // same process
                }
            }
        }

        // Tell the window manager policy when the screen is turned off or on unless it's due
        // to the proximity sensor.  We temporarily block turning the screen on until the
        // window manager is ready by leaving a black surface covering the screen.
        // This surface is essentially the final state of the color fade animation and
        // it is only removed once the window manager tells us that the activity has
        // finished drawing underneath.
        if (isOff && mReportedScreenStateToPolicy != REPORTED_TO_POLICY_SCREEN_OFF
                && !mScreenOffBecauseOfProximity) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_OFF);
            unblockScreenOn();
            mWindowManagerPolicy.screenTurnedOff();
        } else if (!isOff
                && mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_TURNING_OFF) {

            // We told policy already that screen was turning off, but now we changed our minds.
            // Complete the full state transition on -> turningOff -> off.
            unblockScreenOff();
            mWindowManagerPolicy.screenTurnedOff();
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_OFF);
        }
        if (!isOff && mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_OFF) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_TURNING_ON);
            if (mPowerState.getColorFadeLevel() == 0.0f) {
                blockScreenOn();
            } else {
                unblockScreenOn();
            }
            mWindowManagerPolicy.screenTurningOn(mPendingScreenOnUnblocker);
        }

        // Return true if the screen isn't blocked.
        return mPendingScreenOnUnblocker == null;
    }

    private void setReportedScreenState(int state) {
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "ReportedScreenStateToPolicy", state);
        mReportedScreenStateToPolicy = state;
    }

    private int clampScreenBrightnessForVr(int value) {
        return MathUtils.constrain(
                value, mScreenBrightnessForVrRangeMinimum, mScreenBrightnessForVrRangeMaximum);
    }

    private int clampScreenBrightness(int value) {
        return MathUtils.constrain(
                value, mScreenBrightnessRangeMinimum, mScreenBrightnessRangeMaximum);
    }

    private void animateScreenBrightness(int target, int rate) {
        if (DEBUG) {
            Slog.d(TAG, "Animating brightness: target=" + target +", rate=" + rate);
        }
        if (mScreenBrightnessRampAnimator.animateTo(target, rate)) {
            Trace.traceCounter(Trace.TRACE_TAG_POWER, "TargetScreenBrightness", target);
            try {
                mBatteryStats.noteScreenBrightness(target);
            } catch (RemoteException ex) {
                // same process
            }
        }
    }

    private void animateScreenStateChange(int target, boolean performScreenOffTransition) {
        // If there is already an animation in progress, don't interfere with it.
        if (mColorFadeEnabled &&
                (mColorFadeOnAnimator.isStarted() || mColorFadeOffAnimator.isStarted())) {
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
            setScreenState(Display.STATE_OFF, target != Display.STATE_OFF /*reportOnly*/);
        }

        // If we were in the process of turning off the screen but didn't quite
        // finish.  Then finish up now to prevent a jarring transition back
        // to screen on if we skipped blocking screen on as usual.
        if (mPendingScreenOff && target != Display.STATE_OFF) {
            setScreenState(Display.STATE_OFF);
            mPendingScreenOff = false;
            mPowerState.dismissColorFadeResources();
        }

        if (target == Display.STATE_ON) {
            // Want screen on.  The contents of the screen may not yet
            // be visible if the color fade has not been dismissed because
            // its last frame of animation is solid black.
            if (!setScreenState(Display.STATE_ON)) {
                return; // screen on blocked
            }
            if (USE_COLOR_FADE_ON_ANIMATION && mColorFadeEnabled && mPowerRequest.isBrightOrDim()) {
                // Perform screen on animation.
                if (mPowerState.getColorFadeLevel() == 1.0f) {
                    mPowerState.dismissColorFade();
                } else if (mPowerState.prepareColorFade(mContext,
                        mColorFadeFadesConfig ?
                                ColorFade.MODE_FADE :
                                        ColorFade.MODE_WARM_UP)) {
                    mColorFadeOnAnimator.start();
                } else {
                    mColorFadeOnAnimator.end();
                }
            } else {
                // Skip screen on animation.
                mPowerState.setColorFadeLevel(1.0f);
                mPowerState.dismissColorFade();
            }
        } else if (target == Display.STATE_VR) {
            // Wait for brightness animation to complete beforehand when entering VR
            // from screen on to prevent a perceptible jump because brightness may operate
            // differently when the display is configured for dozing.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() == Display.STATE_ON) {
                return;
            }

            // Set screen state.
            if (!setScreenState(Display.STATE_VR)) {
                return; // screen on blocked
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
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
            if (!setScreenState(Display.STATE_DOZE)) {
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
                if (!setScreenState(Display.STATE_DOZE)) {
                    return; // screen on blocked
                }
                setScreenState(Display.STATE_DOZE_SUSPEND); // already on so can't block
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
                if (!setScreenState(Display.STATE_ON)) {
                    return;
                }
                setScreenState(Display.STATE_ON_SUSPEND);
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
                setScreenState(Display.STATE_OFF);
                mPendingScreenOff = false;
                mPowerState.dismissColorFadeResources();
            } else if (performScreenOffTransition
                    && mPowerState.prepareColorFade(mContext,
                            mColorFadeFadesConfig ?
                                    ColorFade.MODE_FADE : ColorFade.MODE_COOL_DOWN)
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

    private final Runnable mCleanListener = new Runnable() {
        @Override
        public void run() {
            sendUpdatePowerState();
        }
    };

    private void setProximitySensorEnabled(boolean enable) {
        if (enable) {
            if (!mProximitySensorEnabled) {
                // Register the listener.
                // Proximity sensor state already cleared initially.
                mProximitySensorEnabled = true;
                mSensorManager.registerListener(mProximitySensorListener, mProximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            }
        } else {
            if (mProximitySensorEnabled) {
                // Unregister the listener.
                // Clear the proximity sensor state for next time.
                mProximitySensorEnabled = false;
                mProximity = PROXIMITY_UNKNOWN;
                mPendingProximity = PROXIMITY_UNKNOWN;
                mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                mSensorManager.unregisterListener(mProximitySensorListener);
                clearPendingProximityDebounceTime(); // release wake lock (must be last)
            }
        }
    }

    private void handleProximitySensorEvent(long time, boolean positive) {
        if (mProximitySensorEnabled) {
            if (mPendingProximity == PROXIMITY_NEGATIVE && !positive) {
                return; // no change
            }
            if (mPendingProximity == PROXIMITY_POSITIVE && positive) {
                return; // no change
            }

            // Only accept a proximity sensor reading if it remains
            // stable for the entire debounce delay.  We hold a wake lock while
            // debouncing the sensor.
            mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
            if (positive) {
                mPendingProximity = PROXIMITY_POSITIVE;
                setPendingProximityDebounceTime(
                        time + PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY); // acquire wake lock
            } else {
                mPendingProximity = PROXIMITY_NEGATIVE;
                setPendingProximityDebounceTime(
                        time + PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY); // acquire wake lock
            }

            // Debounce the new sensor reading.
            debounceProximitySensor();
        }
    }

    private void debounceProximitySensor() {
        if (mProximitySensorEnabled
                && mPendingProximity != PROXIMITY_UNKNOWN
                && mPendingProximityDebounceTime >= 0) {
            final long now = SystemClock.uptimeMillis();
            if (mPendingProximityDebounceTime <= now) {
                // Sensor reading accepted.  Apply the change then release the wake lock.
                mProximity = mPendingProximity;
                updatePowerState();
                clearPendingProximityDebounceTime(); // release wake lock (must be last)
            } else {
                // Need to wait a little longer.
                // Debounce again later.  We continue holding a wake lock while waiting.
                Message msg = mHandler.obtainMessage(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                mHandler.sendMessageAtTime(msg, mPendingProximityDebounceTime);
            }
        }
    }

    private void clearPendingProximityDebounceTime() {
        if (mPendingProximityDebounceTime >= 0) {
            mPendingProximityDebounceTime = -1;
            mCallbacks.releaseSuspendBlocker(); // release wake lock
        }
    }

    private void setPendingProximityDebounceTime(long debounceTime) {
        if (mPendingProximityDebounceTime < 0) {
            mCallbacks.acquireSuspendBlocker(); // acquire wake lock
        }
        mPendingProximityDebounceTime = debounceTime;
    }

    private void sendOnStateChangedWithWakelock() {
        mCallbacks.acquireSuspendBlocker();
        mHandler.post(mOnStateChangedRunnable);
    }

    private void logDisplayPolicyChanged(int newPolicy) {
        LogMaker log = new LogMaker(MetricsEvent.DISPLAY_POLICY);
        log.setType(MetricsEvent.TYPE_UPDATE);
        log.setSubtype(newPolicy);
        MetricsLogger.action(log);
    }

    private void handleSettingsChange(boolean userSwitch) {
        mPendingScreenBrightnessSetting = getScreenBrightnessSetting();
        if (userSwitch) {
            // Don't treat user switches as user initiated change.
            mCurrentScreenBrightnessSetting = mPendingScreenBrightnessSetting;
            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.resetShortTermModel();
            }
        }
        mPendingAutoBrightnessAdjustment = getAutoBrightnessAdjustmentSetting();
        // We don't bother with a pending variable for VR screen brightness since we just
        // immediately adapt to it.
        mScreenBrightnessForVr = getScreenBrightnessForVrSetting();
        sendUpdatePowerState();
    }

    private float getAutoBrightnessAdjustmentSetting() {
        final float adj = Settings.System.getFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.0f, UserHandle.USER_CURRENT);
        return Float.isNaN(adj) ? 0.0f : clampAutoBrightnessAdjustment(adj);
    }

    private int getScreenBrightnessSetting() {
        final int brightness = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, mScreenBrightnessDefault,
                UserHandle.USER_CURRENT);
        return clampAbsoluteBrightness(brightness);
    }

    private int getScreenBrightnessForVrSetting() {
        final int brightness = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FOR_VR, mScreenBrightnessForVrDefault,
                UserHandle.USER_CURRENT);
        return clampScreenBrightnessForVr(brightness);
    }

    private void putScreenBrightnessSetting(int brightness) {
        mCurrentScreenBrightnessSetting = brightness;
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, brightness, UserHandle.USER_CURRENT);
    }

    private void putAutoBrightnessAdjustmentSetting(float adjustment) {
        mAutoBrightnessAdjustment = adjustment;
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, adjustment, UserHandle.USER_CURRENT);
    }

    private boolean updateAutoBrightnessAdjustment() {
        if (Float.isNaN(mPendingAutoBrightnessAdjustment)) {
            return false;
        }
        if (mAutoBrightnessAdjustment == mPendingAutoBrightnessAdjustment) {
            mPendingAutoBrightnessAdjustment = Float.NaN;
            return false;
        }
        mAutoBrightnessAdjustment = mPendingAutoBrightnessAdjustment;
        mPendingAutoBrightnessAdjustment = Float.NaN;
        return true;
    }

    private boolean updateUserSetScreenBrightness() {
        if (mPendingScreenBrightnessSetting < 0) {
            return false;
        }
        if (mCurrentScreenBrightnessSetting == mPendingScreenBrightnessSetting) {
            mPendingScreenBrightnessSetting = -1;
            mTemporaryScreenBrightness = -1;
            return false;
        }
        mCurrentScreenBrightnessSetting = mPendingScreenBrightnessSetting;
        mLastUserSetScreenBrightness = mPendingScreenBrightnessSetting;
        mPendingScreenBrightnessSetting = -1;
        mTemporaryScreenBrightness = -1;
        return true;
    }

    private void notifyBrightnessChanged(int brightness, boolean userInitiated,
            boolean hadUserDataPoint) {
        final float brightnessInNits = convertToNits(brightness);
        if (mPowerRequest.useAutoBrightness && brightnessInNits >= 0.0f
                && mAutomaticBrightnessController != null) {
            // We only want to track changes on devices that can actually map the display backlight
            // values into a physical brightness unit since the value provided by the API is in
            // nits and not using the arbitrary backlight units.
            final float powerFactor = mPowerRequest.lowPowerMode
                    ? mPowerRequest.screenLowPowerBrightnessFactor
                    : 1.0f;
            mBrightnessTracker.notifyBrightnessChanged(brightnessInNits, userInitiated,
                    powerFactor, hadUserDataPoint,
                    mAutomaticBrightnessController.isDefaultConfig());
        }
    }

    private float convertToNits(int backlight) {
        if (mBrightnessMapper != null) {
            return mBrightnessMapper.convertToNits(backlight);
        } else {
            return -1.0f;
        }
    }

    private final Runnable mOnStateChangedRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onStateChanged();
            mCallbacks.releaseSuspendBlocker();
        }
    };

    private void sendOnProximityPositiveWithWakelock() {
        mCallbacks.acquireSuspendBlocker();
        mHandler.post(mOnProximityPositiveRunnable);
    }

    private final Runnable mOnProximityPositiveRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onProximityPositive();
            mCallbacks.releaseSuspendBlocker();
        }
    };

    private void sendOnProximityNegativeWithWakelock() {
        mCallbacks.acquireSuspendBlocker();
        mHandler.post(mOnProximityNegativeRunnable);
    }

    private final Runnable mOnProximityNegativeRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onProximityNegative();
            mCallbacks.releaseSuspendBlocker();
        }
    };

    public void dump(final PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Display Power Controller Locked State:");
            pw.println("  mDisplayReadyLocked=" + mDisplayReadyLocked);
            pw.println("  mPendingRequestLocked=" + mPendingRequestLocked);
            pw.println("  mPendingRequestChangedLocked=" + mPendingRequestChangedLocked);
            pw.println("  mPendingWaitForNegativeProximityLocked="
                    + mPendingWaitForNegativeProximityLocked);
            pw.println("  mPendingUpdatePowerStateLocked=" + mPendingUpdatePowerStateLocked);
        }

        pw.println();
        pw.println("Display Power Controller Configuration:");
        pw.println("  mScreenBrightnessDozeConfig=" + mScreenBrightnessDozeConfig);
        pw.println("  mScreenBrightnessDimConfig=" + mScreenBrightnessDimConfig);
        pw.println("  mScreenBrightnessRangeMinimum=" + mScreenBrightnessRangeMinimum);
        pw.println("  mScreenBrightnessRangeMaximum=" + mScreenBrightnessRangeMaximum);
        pw.println("  mScreenBrightnessDefault=" + mScreenBrightnessDefault);
        pw.println("  mScreenBrightnessForVrRangeMinimum=" + mScreenBrightnessForVrRangeMinimum);
        pw.println("  mScreenBrightnessForVrRangeMaximum=" + mScreenBrightnessForVrRangeMaximum);
        pw.println("  mScreenBrightnessForVrDefault=" + mScreenBrightnessForVrDefault);
        pw.println("  mUseSoftwareAutoBrightnessConfig=" + mUseSoftwareAutoBrightnessConfig);
        pw.println("  mAllowAutoBrightnessWhileDozingConfig=" +
                mAllowAutoBrightnessWhileDozingConfig);
        pw.println("  mBrightnessRampRateFast=" + mBrightnessRampRateFast);
        pw.println("  mBrightnessRampRateSlow=" + mBrightnessRampRateSlow);
        pw.println("  mSkipScreenOnBrightnessRamp=" + mSkipScreenOnBrightnessRamp);
        pw.println("  mColorFadeFadesConfig=" + mColorFadeFadesConfig);
        pw.println("  mColorFadeEnabled=" + mColorFadeEnabled);
        pw.println("  mDisplayBlanksAfterDozeConfig=" + mDisplayBlanksAfterDozeConfig);
        pw.println("  mBrightnessBucketsInDozeConfig=" + mBrightnessBucketsInDozeConfig);

        mHandler.runWithScissors(new Runnable() {
            @Override
            public void run() {
                dumpLocal(pw);
            }
        }, 1000);
    }

    private void dumpLocal(PrintWriter pw) {
        pw.println();
        pw.println("Display Power Controller Thread State:");
        pw.println("  mPowerRequest=" + mPowerRequest);
        pw.println("  mUnfinishedBusiness=" + mUnfinishedBusiness);
        pw.println("  mWaitingForNegativeProximity=" + mWaitingForNegativeProximity);
        pw.println("  mProximitySensor=" + mProximitySensor);
        pw.println("  mProximitySensorEnabled=" + mProximitySensorEnabled);
        pw.println("  mProximityThreshold=" + mProximityThreshold);
        pw.println("  mProximity=" + proximityToString(mProximity));
        pw.println("  mPendingProximity=" + proximityToString(mPendingProximity));
        pw.println("  mPendingProximityDebounceTime="
                + TimeUtils.formatUptime(mPendingProximityDebounceTime));
        pw.println("  mScreenOffBecauseOfProximity=" + mScreenOffBecauseOfProximity);
        pw.println("  mLastUserSetScreenBrightness=" + mLastUserSetScreenBrightness);
        pw.println("  mCurrentScreenBrightnessSetting=" + mCurrentScreenBrightnessSetting);
        pw.println("  mPendingScreenBrightnessSetting=" + mPendingScreenBrightnessSetting);
        pw.println("  mTemporaryScreenBrightness=" + mTemporaryScreenBrightness);
        pw.println("  mAutoBrightnessAdjustment=" + mAutoBrightnessAdjustment);
        pw.println("  mBrightnessReason=" + mBrightnessReason);
        pw.println("  mTemporaryAutoBrightnessAdjustment=" + mTemporaryAutoBrightnessAdjustment);
        pw.println("  mPendingAutoBrightnessAdjustment=" + mPendingAutoBrightnessAdjustment);
        pw.println("  mScreenBrightnessForVr=" + mScreenBrightnessForVr);
        pw.println("  mAppliedAutoBrightness=" + mAppliedAutoBrightness);
        pw.println("  mAppliedDimming=" + mAppliedDimming);
        pw.println("  mAppliedLowPower=" + mAppliedLowPower);
        pw.println("  mAppliedScreenBrightnessOverride=" + mAppliedScreenBrightnessOverride);
        pw.println("  mAppliedTemporaryBrightness=" + mAppliedTemporaryBrightness);
        pw.println("  mDozing=" + mDozing);
        pw.println("  mSkipRampState=" + skipRampStateToString(mSkipRampState));
        pw.println("  mInitialAutoBrightness=" + mInitialAutoBrightness);
        pw.println("  mScreenOnBlockStartRealTime=" + mScreenOnBlockStartRealTime);
        pw.println("  mScreenOffBlockStartRealTime=" + mScreenOffBlockStartRealTime);
        pw.println("  mPendingScreenOnUnblocker=" + mPendingScreenOnUnblocker);
        pw.println("  mPendingScreenOffUnblocker=" + mPendingScreenOffUnblocker);
        pw.println("  mPendingScreenOff=" + mPendingScreenOff);
        pw.println("  mReportedToPolicy=" +
                reportedToPolicyToString(mReportedScreenStateToPolicy));

        if (mScreenBrightnessRampAnimator != null) {
            pw.println("  mScreenBrightnessRampAnimator.isAnimating()=" +
                    mScreenBrightnessRampAnimator.isAnimating());
        }

        if (mColorFadeOnAnimator != null) {
            pw.println("  mColorFadeOnAnimator.isStarted()=" +
                    mColorFadeOnAnimator.isStarted());
        }
        if (mColorFadeOffAnimator != null) {
            pw.println("  mColorFadeOffAnimator.isStarted()=" +
                    mColorFadeOffAnimator.isStarted());
        }

        if (mPowerState != null) {
            mPowerState.dump(pw);
        }

        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.dump(pw);
        }

        if (mBrightnessTracker != null) {
            pw.println();
            mBrightnessTracker.dump(pw);
        }

        pw.println();
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.dump(pw);
            mDisplayWhiteBalanceSettings.dump(pw);
        }
    }

    private static String proximityToString(int state) {
        switch (state) {
            case PROXIMITY_UNKNOWN:
                return "Unknown";
            case PROXIMITY_NEGATIVE:
                return "Negative";
            case PROXIMITY_POSITIVE:
                return "Positive";
            default:
                return Integer.toString(state);
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

    private static int clampAbsoluteBrightness(int value) {
        return MathUtils.constrain(value, PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON);
    }

    private static float clampAutoBrightnessAdjustment(float value) {
        return MathUtils.constrain(value, -1.0f, 1.0f);
    }

    private final class DisplayControllerHandler extends Handler {
        public DisplayControllerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_POWER_STATE:
                    updatePowerState();
                    break;

                case MSG_PROXIMITY_SENSOR_DEBOUNCED:
                    debounceProximitySensor();
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
                case MSG_CONFIGURE_BRIGHTNESS:
                    mBrightnessConfiguration = (BrightnessConfiguration)msg.obj;
                    updatePowerState();
                    break;

                case MSG_SET_TEMPORARY_BRIGHTNESS:
                    // TODO: Should we have a a timeout for the temporary brightness?
                    mTemporaryScreenBrightness = msg.arg1;
                    updatePowerState();
                    break;

                case MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT:
                    mTemporaryAutoBrightnessAdjustment = Float.intBitsToFloat(msg.arg1);
                    updatePowerState();
                    break;
            }
        }
    }

    private final SensorEventListener mProximitySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mProximitySensorEnabled) {
                final long time = SystemClock.uptimeMillis();
                final float distance = event.values[0];
                boolean positive = distance >= 0.0f && distance < mProximityThreshold;
                handleProximitySensorEvent(time, positive);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };


    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            handleSettingsChange(false /* userSwitch */);
        }
    }

    private final class ScreenOnUnblocker implements WindowManagerPolicy.ScreenOnListener {
        @Override
        public void onScreenOn() {
            Message msg = mHandler.obtainMessage(MSG_SCREEN_ON_UNBLOCKED, this);
            mHandler.sendMessage(msg);
        }
    }

    private final class ScreenOffUnblocker implements WindowManagerPolicy.ScreenOffListener {
        @Override
        public void onScreenOff() {
            Message msg = mHandler.obtainMessage(MSG_SCREEN_OFF_UNBLOCKED, this);
            mHandler.sendMessage(msg);
        }
    }

    void setAutoBrightnessLoggingEnabled(boolean enabled) {
        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.setLoggingEnabled(enabled);
        }
    }

    @Override // DisplayWhiteBalanceController.Callbacks
    public void updateWhiteBalance() {
        sendUpdatePowerState();
    }

    void setDisplayWhiteBalanceLoggingEnabled(boolean enabled) {
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.setLoggingEnabled(enabled);
            mDisplayWhiteBalanceSettings.setLoggingEnabled(enabled);
        }
    }

    void setAmbientColorTemperatureOverride(float cct) {
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.setAmbientColorTemperatureOverride(cct);
            // The ambient color temperature override is only applied when the ambient color
            // temperature changes or is updated, so it doesn't necessarily change the screen color
            // temperature immediately. So, let's make it!
            sendUpdatePowerState();
        }
    }

    /**
     * Stores data about why the brightness was changed.  Made up of one main
     * {@code BrightnessReason.REASON_*} reason and various {@code BrightnessReason.MODIFIER_*}
     * modifiers.
     */
    private final class BrightnessReason {
        static final int REASON_UNKNOWN = 0;
        static final int REASON_MANUAL = 1;
        static final int REASON_DOZE = 2;
        static final int REASON_DOZE_DEFAULT = 3;
        static final int REASON_AUTOMATIC = 4;
        static final int REASON_SCREEN_OFF = 5;
        static final int REASON_VR = 6;
        static final int REASON_OVERRIDE = 7;
        static final int REASON_TEMPORARY = 8;
        static final int REASON_BOOST = 9;
        static final int REASON_MAX = REASON_BOOST;

        static final int MODIFIER_DIMMED = 0x1;
        static final int MODIFIER_LOW_POWER = 0x2;
        static final int MODIFIER_MASK = 0x3;

        // ADJUSTMENT_*
        // These things can happen at any point, even if the main brightness reason doesn't
        // fundamentally change, so they're not stored.

        // Auto-brightness adjustment factor changed
        static final int ADJUSTMENT_AUTO_TEMP = 0x1;
        // Temporary adjustment to the auto-brightness adjustment factor.
        static final int ADJUSTMENT_AUTO = 0x2;

        // One of REASON_*
        public int reason;
        // Any number of MODIFIER_*
        public int modifier;

        public void set(BrightnessReason other) {
            setReason(other == null ? REASON_UNKNOWN : other.reason);
            setModifier(other == null ? 0 : other.modifier);
        }

        public void setReason(int reason) {
            if (reason < REASON_UNKNOWN || reason > REASON_MAX) {
                Slog.w(TAG, "brightness reason out of bounds: " + reason);
            } else {
                this.reason = reason;
            }
        }

        public void setModifier(int modifier) {
            if ((modifier & ~MODIFIER_MASK) != 0) {
                Slog.w(TAG, "brightness modifier out of bounds: 0x"
                        + Integer.toHexString(modifier));
            } else {
                this.modifier = modifier;
            }
        }

        public void addModifier(int modifier) {
            setModifier(modifier | this.modifier);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof BrightnessReason)) {
                return false;
            }
            BrightnessReason other = (BrightnessReason) obj;
            return other.reason == reason && other.modifier == modifier;
        }

        @Override
        public String toString() {
            return toString(0);
        }

        public String toString(int adjustments) {
            final StringBuilder sb = new StringBuilder();
            sb.append(reasonToString(reason));
            sb.append(" [");
            if ((adjustments & ADJUSTMENT_AUTO_TEMP) != 0) {
                sb.append(" temp_adj");
            }
            if ((adjustments & ADJUSTMENT_AUTO) != 0) {
                sb.append(" auto_adj");
            }
            if ((modifier & MODIFIER_LOW_POWER) != 0) {
                sb.append(" low_pwr");
            }
            if ((modifier & MODIFIER_DIMMED) != 0) {
                sb.append(" dim");
            }
            int strlen = sb.length();
            if (sb.charAt(strlen - 1) == '[') {
                sb.setLength(strlen - 2);
            } else {
                sb.append(" ]");
            }
            return sb.toString();
        }

        private String reasonToString(int reason) {
            switch (reason) {
                case REASON_MANUAL: return "manual";
                case REASON_DOZE: return "doze";
                case REASON_DOZE_DEFAULT: return "doze_default";
                case REASON_AUTOMATIC: return "automatic";
                case REASON_SCREEN_OFF: return "screen_off";
                case REASON_VR: return "vr";
                case REASON_OVERRIDE: return "override";
                case REASON_TEMPORARY: return "temporary";
                case REASON_BOOST: return "boost";
                default: return Integer.toString(reason);
            }
        }
    }
}
