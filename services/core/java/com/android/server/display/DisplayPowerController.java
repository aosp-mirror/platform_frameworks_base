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
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Handler;
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
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.LocalServices;
import com.android.server.am.BatteryStatsService;
import com.android.server.display.RampAnimator.DualRampAnimator;
import com.android.server.display.color.ColorDisplayService.ColorDisplayServiceInternal;
import com.android.server.display.color.ColorDisplayService.ReduceBrightColorsListener;
import com.android.server.display.whitebalance.DisplayWhiteBalanceController;
import com.android.server.display.whitebalance.DisplayWhiteBalanceFactory;
import com.android.server.display.whitebalance.DisplayWhiteBalanceSettings;
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
    private static final float SCREEN_DIM_MINIMUM_REDUCTION_FLOAT = 0.04f;
    private static final float SCREEN_ANIMATION_RATE_MINIMUM = 0.0f;

    private static final int COLOR_FADE_ON_ANIMATION_DURATION_MILLIS = 250;
    private static final int COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS = 400;

    private static final int MSG_UPDATE_POWER_STATE = 1;
    private static final int MSG_PROXIMITY_SENSOR_DEBOUNCED = 2;
    private static final int MSG_SCREEN_ON_UNBLOCKED = 3;
    private static final int MSG_SCREEN_OFF_UNBLOCKED = 4;
    private static final int MSG_CONFIGURE_BRIGHTNESS = 5;
    private static final int MSG_SET_TEMPORARY_BRIGHTNESS = 6;
    private static final int MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT = 7;
    private static final int MSG_IGNORE_PROXIMITY = 8;
    private static final int MSG_STOP = 9;
    private static final int MSG_UPDATE_BRIGHTNESS = 10;

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

    private static final int REPORTED_TO_POLICY_UNREPORTED = -1;
    private static final int REPORTED_TO_POLICY_SCREEN_OFF = 0;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_ON = 1;
    private static final int REPORTED_TO_POLICY_SCREEN_ON = 2;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_OFF = 3;

    private static final Uri BRIGHTNESS_FLOAT_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FLOAT);

    private final Object mLock = new Object();

    private final Context mContext;

    // Our handler.
    private final DisplayControllerHandler mHandler;

    // Asynchronous callbacks into the power manager service.
    // Only invoked from the handler thread while no locks are held.
    private final DisplayPowerCallbacks mCallbacks;

    // Battery stats.
    @Nullable
    private final IBatteryStats mBatteryStats;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The window manager policy.
    private final WindowManagerPolicy mWindowManagerPolicy;

    // The display blanker.
    private final DisplayBlanker mBlanker;

    // The LogicalDisplay tied to this DisplayPowerController.
    private final LogicalDisplay mLogicalDisplay;

    // The ID of the LogicalDisplay tied to this DisplayPowerController.
    private final int mDisplayId;

    // The unique ID of the primary display device currently tied to this logical display
    private String mUniqueDisplayId;

    // Tracker for brightness changes.
    @Nullable
    private final BrightnessTracker mBrightnessTracker;

    // Tracker for brightness settings changes.
    private final SettingsObserver mSettingsObserver;

    // The proximity sensor, or null if not available or needed.
    private Sensor mProximitySensor;

    // The doze screen brightness.
    private final float mScreenBrightnessDozeConfig;

    // The dim screen brightness.
    private final float mScreenBrightnessDimConfig;

    private final float mScreenBrightnessDefault;

    // The minimum allowed brightness while in VR.
    private final float mScreenBrightnessForVrRangeMinimum;

    // The maximum allowed brightness while in VR.
    private final float mScreenBrightnessForVrRangeMaximum;

    // The default screen brightness for VR.
    private final float mScreenBrightnessForVrDefault;

    // True if auto-brightness should be used.
    private boolean mUseSoftwareAutoBrightnessConfig;

    // True if should use light sensor to automatically determine doze screen brightness.
    private final boolean mAllowAutoBrightnessWhileDozingConfig;

    // Whether or not the color fade on screen on / off is enabled.
    private final boolean mColorFadeEnabled;

    @GuardedBy("mCachedBrightnessInfo")
    private final CachedBrightnessInfo mCachedBrightnessInfo = new CachedBrightnessInfo();

    private DisplayDevice mDisplayDevice;

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

    // True if the device should not take into account the proximity sensor
    // until either the proximity sensor state changes, or there is no longer a
    // request to listen to proximity sensor.
    private boolean mIgnoreProximityUntilChanged;

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

    // Screen state we reported to policy. Must be one of REPORTED_TO_POLICY_* fields.
    private int mReportedScreenStateToPolicy = REPORTED_TO_POLICY_UNREPORTED;

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
    private float mBrightnessRampRateFastDecrease;
    private float mBrightnessRampRateFastIncrease;
    private float mBrightnessRampRateSlowDecrease;
    private float mBrightnessRampRateSlowIncrease;


    // Whether or not to skip the initial brightness ramps into STATE_ON.
    private final boolean mSkipScreenOnBrightnessRamp;

    // Display white balance components.
    @Nullable
    private final DisplayWhiteBalanceSettings mDisplayWhiteBalanceSettings;
    @Nullable
    private final DisplayWhiteBalanceController mDisplayWhiteBalanceController;

    @Nullable
    private final ColorDisplayServiceInternal mCdsi;
    private float[] mNitsRange;

    private final HighBrightnessModeController mHbmController;

    private final BrightnessSetting mBrightnessSetting;

    private final Runnable mOnBrightnessChangeRunnable;

    // A record of state for skipping brightness ramps.
    private int mSkipRampState = RAMP_STATE_SKIP_NONE;

    // The first autobrightness value set when entering RAMP_STATE_SKIP_INITIAL.
    private float mInitialAutoBrightness;

    // The controller for the automatic brightness level.
    private AutomaticBrightnessController mAutomaticBrightnessController;

    private Sensor mLightSensor;

    // The mapper between ambient lux, display backlight values, and display brightness.
    @Nullable
    private BrightnessMappingStrategy mBrightnessMapper;

    // The current brightness configuration.
    @Nullable
    private BrightnessConfiguration mBrightnessConfiguration;

    // The last brightness that was set by the user and not temporary. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when a brightness has yet to be recorded.
    private float mLastUserSetScreenBrightness;

    // The screen brightness setting has changed but not taken effect yet. If this is different
    // from the current screen brightness setting then this is coming from something other than us
    // and should be considered a user interaction.
    private float mPendingScreenBrightnessSetting;

    // The last observed screen brightness setting, either set by us or by the settings app on
    // behalf of the user.
    private float mCurrentScreenBrightnessSetting;

    // The temporary screen brightness. Typically set when a user is interacting with the
    // brightness slider but hasn't settled on a choice yet. Set to
    // PowerManager.BRIGHNTESS_INVALID_FLOAT when there's no temporary brightness set.
    private float mTemporaryScreenBrightness;

    // The current screen brightness while in VR mode.
    private float mScreenBrightnessForVr;

    // The last auto brightness adjustment that was set by the user and not temporary. Set to
    // Float.NaN when an auto-brightness adjustment hasn't been recorded yet.
    private float mAutoBrightnessAdjustment;

    // The pending auto brightness adjustment that will take effect on the next power state update.
    private float mPendingAutoBrightnessAdjustment;

    // The temporary auto brightness adjustment. Typically set when a user is interacting with the
    // adjustment slider but hasn't settled on a choice yet. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when there's no temporary adjustment set.
    private float mTemporaryAutoBrightnessAdjustment;

    // Animators.
    private ObjectAnimator mColorFadeOnAnimator;
    private ObjectAnimator mColorFadeOffAnimator;
    private DualRampAnimator<DisplayPowerState> mScreenBrightnessRampAnimator;
    private BrightnessSetting.BrightnessSettingListener mBrightnessSettingListener;

    // True if this DisplayPowerController has been stopped and should no longer be running.
    private boolean mStopped;

    private DisplayDeviceConfig mDisplayDeviceConfig;

    /**
     * Creates the display power controller.
     */
    public DisplayPowerController(Context context,
            DisplayPowerCallbacks callbacks, Handler handler,
            SensorManager sensorManager, DisplayBlanker blanker, LogicalDisplay logicalDisplay,
            BrightnessTracker brightnessTracker, BrightnessSetting brightnessSetting,
            Runnable onBrightnessChangeRunnable) {
        mLogicalDisplay = logicalDisplay;
        mDisplayId = mLogicalDisplay.getDisplayIdLocked();
        mDisplayDevice = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        mUniqueDisplayId = logicalDisplay.getPrimaryDisplayDeviceLocked().getUniqueId();
        mHandler = new DisplayControllerHandler(handler.getLooper());

        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mBatteryStats = BatteryStatsService.getService();
        } else {
            mBatteryStats = null;
        }

        mSettingsObserver = new SettingsObserver(mHandler);
        mCallbacks = callbacks;
        mSensorManager = sensorManager;
        mWindowManagerPolicy = LocalServices.getService(WindowManagerPolicy.class);
        mBlanker = blanker;
        mContext = context;
        mBrightnessTracker = brightnessTracker;
        // TODO: b/186428377 update brightness setting when display changes
        mBrightnessSetting = brightnessSetting;
        mOnBrightnessChangeRunnable = onBrightnessChangeRunnable;

        PowerManager pm = context.getSystemService(PowerManager.class);

        final Resources resources = context.getResources();


        // DOZE AND DIM SETTINGS
        mScreenBrightnessDozeConfig = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DOZE));
        mScreenBrightnessDimConfig = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DIM));

        // NORMAL SCREEN SETTINGS
        mScreenBrightnessDefault = clampAbsoluteBrightness(
                mLogicalDisplay.getDisplayInfoLocked().brightnessDefault);

        // VR SETTINGS
        mScreenBrightnessForVrDefault = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT_VR));
        mScreenBrightnessForVrRangeMaximum = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM_VR));
        mScreenBrightnessForVrRangeMinimum = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM_VR));

        // Check the setting, but also verify that it is the default display. Only the default
        // display has an automatic brightness controller running.
        // TODO: b/179021925 - Fix to work with multiple displays
        mUseSoftwareAutoBrightnessConfig = resources.getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available)
                && mDisplayId == Display.DEFAULT_DISPLAY;

        mAllowAutoBrightnessWhileDozingConfig = resources.getBoolean(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing);

        mDisplayDeviceConfig = logicalDisplay.getPrimaryDisplayDeviceLocked()
                .getDisplayDeviceConfig();

        loadBrightnessRampRates();
        mSkipScreenOnBrightnessRamp = resources.getBoolean(
                com.android.internal.R.bool.config_skipScreenOnBrightnessRamp);

        mHbmController = createHbmController();

        // Seed the cached brightness
        saveBrightnessInfo(getScreenBrightnessSetting());

        setUpAutoBrightness(resources, handler);

        mColorFadeEnabled = !ActivityManager.isLowRamDeviceStatic();
        mColorFadeFadesConfig = resources.getBoolean(
                com.android.internal.R.bool.config_animateScreenLights);

        mDisplayBlanksAfterDozeConfig = resources.getBoolean(
                com.android.internal.R.bool.config_displayBlanksAfterDoze);

        mBrightnessBucketsInDozeConfig = resources.getBoolean(
                com.android.internal.R.bool.config_displayBrightnessBucketsInDoze);

        loadProximitySensor();

        mCurrentScreenBrightnessSetting = getScreenBrightnessSetting();
        mScreenBrightnessForVr = getScreenBrightnessForVrSetting();
        mAutoBrightnessAdjustment = getAutoBrightnessAdjustmentSetting();
        mTemporaryScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mPendingScreenBrightnessSetting = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mTemporaryAutoBrightnessAdjustment = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mPendingAutoBrightnessAdjustment = PowerManager.BRIGHTNESS_INVALID_FLOAT;

        DisplayWhiteBalanceSettings displayWhiteBalanceSettings = null;
        DisplayWhiteBalanceController displayWhiteBalanceController = null;
        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            try {
                displayWhiteBalanceSettings = new DisplayWhiteBalanceSettings(mContext, mHandler);
                displayWhiteBalanceController = DisplayWhiteBalanceFactory.create(mHandler,
                        mSensorManager, resources);
                displayWhiteBalanceSettings.setCallbacks(this);
                displayWhiteBalanceController.setCallbacks(this);
            } catch (Exception e) {
                Slog.e(TAG, "failed to set up display white-balance: " + e);
            }
        }
        mDisplayWhiteBalanceSettings = displayWhiteBalanceSettings;
        mDisplayWhiteBalanceController = displayWhiteBalanceController;

        loadNitsRange(resources);

        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mCdsi = LocalServices.getService(ColorDisplayServiceInternal.class);
            boolean active = mCdsi.setReduceBrightColorsListener(new ReduceBrightColorsListener() {
                @Override
                public void onReduceBrightColorsActivationChanged(boolean activated) {
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
        } else {
            mCdsi = null;
        }
    }

    private void applyReduceBrightColorsSplineAdjustment() {
        if (mBrightnessMapper == null) {
            Log.w(TAG, "No brightness mapping available to recalculate splines");
            return;
        }

        float[] adjustedNits = new float[mNitsRange.length];
        for (int i = 0; i < mNitsRange.length; i++) {
            adjustedNits[i] = mCdsi.getReduceBrightColorsAdjustedBrightnessNits(mNitsRange[i]);
        }
        mBrightnessMapper.recalculateSplines(mCdsi.isReduceBrightColorsActivated(), adjustedNits);
    }

    private Sensor findSensor(String sensorType, String sensorName, int fallbackType,
            boolean useFallback) {
        final boolean isNameSpecified = !TextUtils.isEmpty(sensorName);
        final boolean isTypeSpecified = !TextUtils.isEmpty(sensorType);
        List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        if (isNameSpecified || isTypeSpecified) {
            for (Sensor sensor : sensors) {
                if ((!isNameSpecified || sensorName.equals(sensor.getName()))
                        && (!isTypeSpecified || sensorType.equals(sensor.getStringType()))) {
                    return sensor;
                }
            }
        }
        if (useFallback) {
            return mSensorManager.getDefaultSensor(fallbackType);
        } else {
            return null;
        }
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
    @Nullable
    public ParceledListSlice<BrightnessChangeEvent> getBrightnessEvents(
            @UserIdInt int userId, boolean includePackage) {
        if (mBrightnessTracker == null) {
            return null;
        }
        return mBrightnessTracker.getEvents(userId, includePackage);
    }

    public void onSwitchUser(@UserIdInt int newUserId) {
        handleSettingsChange(true /* userSwitch */);
        if (mBrightnessTracker != null) {
            mBrightnessTracker.onSwitchUser(newUserId);
        }
    }

    @Nullable
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
            if (mStopped) {
                return true;
            }

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
                if (!mPendingRequestChangedLocked) {
                    mPendingRequestChangedLocked = true;
                    sendUpdatePowerStateLocked();
                }
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

    /**
     * Notified when the display is changed. We use this to apply any changes that might be needed
     * when displays get swapped on foldable devices.  For example, different brightness properties
     * of each display need to be properly reflected in AutomaticBrightnessController.
     */
    @GuardedBy("DisplayManagerService.mSyncRoot")
    public void onDisplayChanged() {
        final DisplayDevice device = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        if (device == null) {
            Slog.wtf(TAG, "Display Device is null in DisplayPowerController for display: "
                    + mLogicalDisplay.getDisplayIdLocked());
            return;
        }

        final String uniqueId = device.getUniqueId();
        final DisplayDeviceConfig config = device.getDisplayDeviceConfig();
        final IBinder token = device.getDisplayTokenLocked();
        mHandler.post(() -> {
            if (mDisplayDevice == device) {
                return;
            }
            mDisplayDevice = device;
            mUniqueDisplayId = uniqueId;
            mDisplayDeviceConfig = config;
            loadFromDisplayDeviceConfig(token);
        });
    }

    /**
     * Called when the displays are preparing to transition from one device state to another.
     * This process involves turning off some displays so we need updatePowerState() to run and
     * calculate the new state.
     */
    public void onDeviceStateTransition() {
        sendUpdatePowerState();
    }

    /**
     * Unregisters all listeners and interrupts all running threads; halting future work.
     *
     * This method should be called when the DisplayPowerController is no longer in use; i.e. when
     * the {@link #mDisplayId display} has been removed.
     */
    public void stop() {
        synchronized (mLock) {
            mStopped = true;
            Message msg = mHandler.obtainMessage(MSG_STOP);
            mHandler.sendMessage(msg);

            if (mDisplayWhiteBalanceController != null) {
                mDisplayWhiteBalanceController.setEnabled(false);
            }

            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.stop();
            }

            if (mBrightnessSetting != null) {
                mBrightnessSetting.unregisterListener(mBrightnessSettingListener);
            }

            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        }
    }

    private void loadFromDisplayDeviceConfig(IBinder token) {
        // All properties that depend on the associated DisplayDevice and the DDC must be
        // updated here.
        loadAmbientLightSensor();
        loadBrightnessRampRates();
        loadProximitySensor();
        loadNitsRange(mContext.getResources());
        setUpAutoBrightness(mContext.getResources(), mHandler);
        reloadReduceBrightColours();
        mHbmController.resetHbmData(token, mDisplayDeviceConfig.getHighBrightnessModeData());
    }

    private void sendUpdatePowerState() {
        synchronized (mLock) {
            sendUpdatePowerStateLocked();
        }
    }

    private void sendUpdatePowerStateLocked() {
        if (!mStopped && !mPendingUpdatePowerStateLocked) {
            mPendingUpdatePowerStateLocked = true;
            Message msg = mHandler.obtainMessage(MSG_UPDATE_POWER_STATE);
            mHandler.sendMessage(msg);
        }
    }

    private void initialize(int displayState) {
        mPowerState = new DisplayPowerState(mBlanker,
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

        mScreenBrightnessRampAnimator = new DualRampAnimator<>(mPowerState,
                DisplayPowerState.SCREEN_BRIGHTNESS_FLOAT,
                DisplayPowerState.SCREEN_SDR_BRIGHTNESS_FLOAT);
        mScreenBrightnessRampAnimator.setListener(mRampAnimatorListener);

        noteScreenState(mPowerState.getScreenState());
        noteScreenBrightness(mPowerState.getScreenBrightness());

        // Initialize all of the brightness tracking state
        final float brightness = convertToNits(mPowerState.getScreenBrightness());
        if (brightness >= PowerManager.BRIGHTNESS_MIN) {
            mBrightnessTracker.start(brightness);
        }
        mBrightnessSettingListener = brightnessValue -> {
            Message msg = mHandler.obtainMessage(MSG_UPDATE_BRIGHTNESS, brightnessValue);
            mHandler.sendMessage(msg);
        };

        mBrightnessSetting.registerListener(mBrightnessSettingListener);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
    }

    private void setUpAutoBrightness(Resources resources, Handler handler) {
        if (!mUseSoftwareAutoBrightnessConfig) {
            return;
        }

        mBrightnessMapper = BrightnessMappingStrategy.create(resources, mDisplayDeviceConfig);

        if (mBrightnessMapper != null) {
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

            loadAmbientLightSensor();

            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.stop();
            }
            mAutomaticBrightnessController = new AutomaticBrightnessController(this,
                    handler.getLooper(), mSensorManager, mLightSensor, mBrightnessMapper,
                    lightSensorWarmUpTimeConfig, PowerManager.BRIGHTNESS_MIN,
                    PowerManager.BRIGHTNESS_MAX, dozeScaleFactor, lightSensorRate,
                    initialLightSensorRate, brighteningLightDebounce, darkeningLightDebounce,
                    autoBrightnessResetAmbientLuxAfterWarmUp, ambientBrightnessThresholds,
                    screenBrightnessThresholds, mLogicalDisplay, mContext, mHbmController);
        } else {
            mUseSoftwareAutoBrightnessConfig = false;
        }
    }

    private void loadBrightnessRampRates() {
        mBrightnessRampRateFastDecrease = mDisplayDeviceConfig.getBrightnessRampFastDecrease();
        mBrightnessRampRateFastIncrease = mDisplayDeviceConfig.getBrightnessRampFastIncrease();
        mBrightnessRampRateSlowDecrease = mDisplayDeviceConfig.getBrightnessRampSlowDecrease();
        mBrightnessRampRateSlowIncrease = mDisplayDeviceConfig.getBrightnessRampSlowIncrease();
    }

    private void loadNitsRange(Resources resources) {
        if (mDisplayDeviceConfig != null && mDisplayDeviceConfig.getNits() != null) {
            mNitsRange = mDisplayDeviceConfig.getNits();
        } else {
            Slog.w(TAG, "Screen brightness nits configuration is unavailable; falling back");
            mNitsRange = BrightnessMappingStrategy.getFloatArray(resources
                    .obtainTypedArray(com.android.internal.R.array.config_screenBrightnessNits));
        }
    }

    private void reloadReduceBrightColours() {
        if (mCdsi != null && mCdsi.isReduceBrightColorsActivated()) {
            applyReduceBrightColorsSplineAdjustment();
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

    private final RampAnimator.Listener mRampAnimatorListener = this::sendUpdatePowerState;

    /** Clean up all resources that are accessed via the {@link #mHandler} thread. */
    private void cleanupHandlerThreadAfterStop() {
        setProximitySensorEnabled(false);
        mHbmController.stop();
        mHandler.removeCallbacksAndMessages(null);
        if (mUnfinishedBusiness) {
            mCallbacks.releaseSuspendBlocker();
            mUnfinishedBusiness = false;
        }
        if (mPowerState != null) {
            mPowerState.stop();
            mPowerState = null;
        }
    }

    private void updatePowerState() {
        // Update the power state request.
        final boolean mustNotify;
        final int previousPolicy;
        boolean mustInitialize = false;
        boolean shouldSaveBrightnessInfo = true;
        int brightnessAdjustmentFlags = 0;
        mBrightnessReasonTemp.set(null);
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
                updatePendingProximityRequestsLocked();
                mPendingRequestChangedLocked = false;
                mustInitialize = true;
                // Assume we're on and bright until told otherwise, since that's the state we turn
                // on in.
                previousPolicy = DisplayPowerRequest.POLICY_BRIGHT;
            } else if (mPendingRequestChangedLocked) {
                previousPolicy = mPowerRequest.policy;
                mPowerRequest.copyFrom(mPendingRequestLocked);
                updatePendingProximityRequestsLocked();
                mPendingRequestChangedLocked = false;
                mDisplayReadyLocked = false;
            } else {
                previousPolicy = mPowerRequest.policy;
            }

            mustNotify = !mDisplayReadyLocked;
        }

        // Compute the basic display state using the policy.
        // We might override this below based on other factors.
        // Initialise brightness as invalid.
        int state;
        float brightnessState = PowerManager.BRIGHTNESS_INVALID_FLOAT;
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
                    brightnessState = mPowerRequest.dozeScreenBrightness;
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

        // Initialize things the first time the power state is changed.
        if (mustInitialize) {
            initialize(state);
        }

        // Apply the proximity sensor.
        if (mProximitySensor != null) {
            if (mPowerRequest.useProximitySensor && state != Display.STATE_OFF) {
                // At this point the policy says that the screen should be on, but we've been
                // asked to listen to the prox sensor to adjust the display state, so lets make
                // sure the sensor is on.
                setProximitySensorEnabled(true);
                if (!mScreenOffBecauseOfProximity
                        && mProximity == PROXIMITY_POSITIVE
                        && !mIgnoreProximityUntilChanged) {
                    // Prox sensor already reporting "near" so we should turn off the screen.
                    // Also checked that we aren't currently set to ignore the proximity sensor
                    // temporarily.
                    mScreenOffBecauseOfProximity = true;
                    sendOnProximityPositiveWithWakelock();
                }
            } else if (mWaitingForNegativeProximity
                    && mScreenOffBecauseOfProximity
                    && mProximity == PROXIMITY_POSITIVE
                    && state != Display.STATE_OFF) {
                // The policy says that we should have the screen on, but it's off due to the prox
                // and we've been asked to wait until the screen is far from the user to turn it
                // back on. Let keep the prox sensor on so we can tell when it's far again.
                setProximitySensorEnabled(true);
            } else {
                // We haven't been asked to use the prox sensor and we're not waiting on the screen
                // to turn back on...so lets shut down the prox sensor.
                setProximitySensorEnabled(false);
                mWaitingForNegativeProximity = false;
            }

            if (mScreenOffBecauseOfProximity
                    && (mProximity != PROXIMITY_POSITIVE || mIgnoreProximityUntilChanged)) {
                // The screen *was* off due to prox being near, but now it's "far" so lets turn
                // the screen back on.  Also turn it back on if we've been asked to ignore the
                // prox sensor temporarily.
                mScreenOffBecauseOfProximity = false;
                sendOnProximityNegativeWithWakelock();
            }
        } else {
            mWaitingForNegativeProximity = false;
            mIgnoreProximityUntilChanged = false;
        }

        if (!mLogicalDisplay.isEnabled()
                || mLogicalDisplay.getPhase() == LogicalDisplay.DISPLAY_PHASE_LAYOUT_TRANSITION
                || mScreenOffBecauseOfProximity) {
            state = Display.STATE_OFF;
        }

        // Animate the screen state change unless already animating.
        // The transition may be deferred, so after this point we will use the
        // actual state instead of the desired one.
        final int oldState = mPowerState.getScreenState();
        animateScreenStateChange(state, performScreenOffTransition);
        state = mPowerState.getScreenState();

        if (state == Display.STATE_OFF) {
            brightnessState = PowerManager.BRIGHTNESS_OFF_FLOAT;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_SCREEN_OFF);
            shouldSaveBrightnessInfo = false;
        }

        // Always use the VR brightness when in the VR state.
        if (state == Display.STATE_VR) {
            brightnessState = mScreenBrightnessForVr;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_VR);
        }

        if ((Float.isNaN(brightnessState))
                && isValidBrightnessValue(mPowerRequest.screenBrightnessOverride)) {
            brightnessState = mPowerRequest.screenBrightnessOverride;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_OVERRIDE);
            mAppliedScreenBrightnessOverride = true;
        } else {
            mAppliedScreenBrightnessOverride = false;
        }

        final boolean autoBrightnessEnabledInDoze =
                mAllowAutoBrightnessWhileDozingConfig && Display.isDozeState(state);
        final boolean autoBrightnessEnabled = mPowerRequest.useAutoBrightness
                    && (state == Display.STATE_ON || autoBrightnessEnabledInDoze)
                    && Float.isNaN(brightnessState)
                    && mAutomaticBrightnessController != null;

        final boolean userSetBrightnessChanged = updateUserSetScreenBrightness();

        // Use the temporary screen brightness if there isn't an override, either from
        // WindowManager or based on the display state.
        if (isValidBrightnessValue(mTemporaryScreenBrightness)) {
            brightnessState = mTemporaryScreenBrightness;
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
                && brightnessState != PowerManager.BRIGHTNESS_OFF_FLOAT) {
            brightnessState = PowerManager.BRIGHTNESS_MAX;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_BOOST);
            mAppliedBrightnessBoost = true;
        } else {
            mAppliedBrightnessBoost = false;
        }

        // If the brightness is already set then it's been overridden by something other than the
        // user, or is a temporary adjustment.
        boolean userInitiatedChange = (Float.isNaN(brightnessState))
                && (autoBrightnessAdjustmentChanged || userSetBrightnessChanged);
        boolean hadUserBrightnessPoint = false;
        // Configure auto-brightness.
        if (mAutomaticBrightnessController != null) {
            hadUserBrightnessPoint = mAutomaticBrightnessController.hasUserDataPoints();
            mAutomaticBrightnessController.configure(autoBrightnessEnabled,
                    mBrightnessConfiguration,
                    mLastUserSetScreenBrightness,
                    userSetBrightnessChanged, autoBrightnessAdjustment,
                    autoBrightnessAdjustmentChanged, mPowerRequest.policy);
        }

        if (mBrightnessTracker != null) {
            mBrightnessTracker.setBrightnessConfiguration(mBrightnessConfiguration);
        }

        boolean updateScreenBrightnessSetting = false;

        // Apply auto-brightness.
        boolean slowChange = false;
        if (Float.isNaN(brightnessState)) {
            float newAutoBrightnessAdjustment = autoBrightnessAdjustment;
            if (autoBrightnessEnabled) {
                brightnessState = mAutomaticBrightnessController.getAutomaticScreenBrightness();
                newAutoBrightnessAdjustment =
                        mAutomaticBrightnessController.getAutomaticScreenBrightnessAdjustment();
            }
            if (isValidBrightnessValue(brightnessState)
                    || brightnessState == PowerManager.BRIGHTNESS_OFF_FLOAT) {
                // Use current auto-brightness value and slowly adjust to changes.
                brightnessState = clampScreenBrightness(brightnessState);
                if (mAppliedAutoBrightness && !autoBrightnessAdjustmentChanged) {
                    slowChange = true; // slowly adapt to auto-brightness
                }
                updateScreenBrightnessSetting = true;
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
        if ((Float.isNaN(brightnessState))
                && Display.isDozeState(state)) {
            brightnessState = mScreenBrightnessDozeConfig;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_DOZE_DEFAULT);
            shouldSaveBrightnessInfo = false;
        }

        // Apply manual brightness.
        if (Float.isNaN(brightnessState)) {
            brightnessState = clampScreenBrightness(mCurrentScreenBrightnessSetting);
            if (brightnessState != mCurrentScreenBrightnessSetting) {
                // The manually chosen screen brightness is outside of the currently allowed
                // range (i.e., high-brightness-mode), make sure we tell the rest of the system
                // by updating the setting.
                updateScreenBrightnessSetting = true;
            }
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_MANUAL);
        }

        // Save out the brightness info now that the brightness state for this iteration has been
        // finalized and before we send out notifications about the brightness changing.
        if (shouldSaveBrightnessInfo) {
            saveBrightnessInfo(brightnessState);

        }

        if (updateScreenBrightnessSetting) {
            // Tell the rest of the system about the new brightness in case we had to change it
            // for things like auto-brightness or high-brightness-mode. Note that we do this
            // before applying the low power or dim transformations so that the slider
            // accurately represents the full possible range, even if they range changes what
            // it means in absolute terms.
            putScreenBrightnessSetting(brightnessState, /* updateCurrent */ true);
        }

        // Apply dimming by at least some minimum amount when user activity
        // timeout is about to expire.
        if (mPowerRequest.policy == DisplayPowerRequest.POLICY_DIM) {
            if (brightnessState > PowerManager.BRIGHTNESS_MIN) {
                brightnessState = Math.max(
                        Math.min(brightnessState - SCREEN_DIM_MINIMUM_REDUCTION_FLOAT,
                                mScreenBrightnessDimConfig),
                        PowerManager.BRIGHTNESS_MIN);
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
            if (brightnessState > PowerManager.BRIGHTNESS_MIN) {
                final float brightnessFactor =
                        Math.min(mPowerRequest.screenLowPowerBrightnessFactor, 1);
                final float lowPowerBrightnessFloat = (brightnessState * brightnessFactor);
                brightnessState = Math.max(lowPowerBrightnessFloat, PowerManager.BRIGHTNESS_MIN);
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
            if (mHbmController.getHighBrightnessMode() == BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR
                    && ((mBrightnessReason.modifier & BrightnessReason.MODIFIER_DIMMED) == 0
                    || (mBrightnessReason.modifier & BrightnessReason.MODIFIER_LOW_POWER) == 0)) {
                animateValue = mHbmController.getHdrBrightnessValue();
            }

            final float currentBrightness = mPowerState.getScreenBrightness();
            final float currentSdrBrightness = mPowerState.getSdrScreenBrightness();
            if (isValidBrightnessValue(animateValue)
                    && (!BrightnessSynchronizer.floatEquals(animateValue, currentBrightness)
                    || !BrightnessSynchronizer.floatEquals(
                            sdrAnimateValue, currentSdrBrightness))) {
                if (initialRampSkip || hasBrightnessBuckets
                        || wasOrWillBeInVr || !isDisplayContentVisible || brightnessIsTemporary) {
                    animateScreenBrightness(animateValue, sdrAnimateValue,
                            SCREEN_ANIMATION_RATE_MINIMUM);
                } else {
                    boolean isIncreasing = animateValue > currentBrightness;
                    final float rampSpeed;
                    if (isIncreasing && slowChange) {
                        rampSpeed = mBrightnessRampRateSlowIncrease;
                    } else if (isIncreasing && !slowChange) {
                        rampSpeed = mBrightnessRampRateFastIncrease;
                    } else if (!isIncreasing && slowChange) {
                        rampSpeed = mBrightnessRampRateSlowDecrease;
                    } else {
                        rampSpeed = mBrightnessRampRateFastDecrease;
                    }
                    animateScreenBrightness(animateValue, sdrAnimateValue, rampSpeed);
                }
            }

            if (!brightnessIsTemporary) {
                if (userInitiatedChange && (mAutomaticBrightnessController == null
                        || !mAutomaticBrightnessController.hasValidAmbientLux())) {
                    // If we don't have a valid lux reading we can't report a valid
                    // slider event so notify as if the system changed the brightness.
                    userInitiatedChange = false;
                }
                notifyBrightnessChanged(brightnessState, userInitiatedChange,
                        hadUserBrightnessPoint);
            }

        }

        // Log any changes to what is currently driving the brightness setting.
        if (!mBrightnessReasonTemp.equals(mBrightnessReason) || brightnessAdjustmentFlags != 0) {
            Slog.v(TAG, "Brightness [" + brightnessState + "] reason changing to: '"
                    + mBrightnessReasonTemp.toString(brightnessAdjustmentFlags)
                    + "', previous reason: '" + mBrightnessReason + "'.");
            mBrightnessReason.set(mBrightnessReasonTemp);
        } else if (mBrightnessReasonTemp.reason == BrightnessReason.REASON_MANUAL
                && userSetBrightnessChanged) {
            Slog.v(TAG, "Brightness [" + brightnessState + "] manual adjustment.");
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
            mWindowManagerPolicy.screenTurnedOn(mDisplayId);
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

    /**
     * Ignores the proximity sensor until the sensor state changes, but only if the sensor is
     * currently enabled and forcing the screen to be dark.
     */
    public void ignoreProximitySensorUntilChanged() {
        mHandler.sendEmptyMessage(MSG_IGNORE_PROXIMITY);
    }

    public void setBrightnessConfiguration(BrightnessConfiguration c) {
        Message msg = mHandler.obtainMessage(MSG_CONFIGURE_BRIGHTNESS, c);
        msg.sendToTarget();
    }

    public void setTemporaryBrightness(float brightness) {
        Message msg = mHandler.obtainMessage(MSG_SET_TEMPORARY_BRIGHTNESS,
                Float.floatToIntBits(brightness), 0 /*unused*/);
        msg.sendToTarget();
    }

    public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
        Message msg = mHandler.obtainMessage(MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT,
                Float.floatToIntBits(adjustment), 0 /*unused*/);
        msg.sendToTarget();
    }

    public BrightnessInfo getBrightnessInfo() {
        synchronized (mCachedBrightnessInfo) {
            return new BrightnessInfo(
                    mCachedBrightnessInfo.brightness,
                    mCachedBrightnessInfo.brightnessMin,
                    mCachedBrightnessInfo.brightnessMax,
                    mCachedBrightnessInfo.hbmMode);
        }
    }

    private void saveBrightnessInfo(float brightness) {
        synchronized (mCachedBrightnessInfo) {
            mCachedBrightnessInfo.brightness = brightness;
            mCachedBrightnessInfo.brightnessMin = mHbmController.getCurrentBrightnessMin();
            mCachedBrightnessInfo.brightnessMax = mHbmController.getCurrentBrightnessMax();
            mCachedBrightnessInfo.hbmMode = mHbmController.getHighBrightnessMode();
        }
    }

    private HighBrightnessModeController createHbmController() {
        final DisplayDeviceConfig ddConfig =
                mLogicalDisplay.getPrimaryDisplayDeviceLocked().getDisplayDeviceConfig();
        final IBinder displayToken =
                mLogicalDisplay.getPrimaryDisplayDeviceLocked().getDisplayTokenLocked();
        final DisplayDeviceConfig.HighBrightnessModeData hbmData =
                ddConfig != null ? ddConfig.getHighBrightnessModeData() : null;
        return new HighBrightnessModeController(mHandler, displayToken, PowerManager.BRIGHTNESS_MIN,
                PowerManager.BRIGHTNESS_MAX, hbmData,
                () -> {
                    sendUpdatePowerStateLocked();
                    mHandler.post(mOnBrightnessChangeRunnable);
                });
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

        if (mPowerState.getScreenState() != state
                || mReportedScreenStateToPolicy == REPORTED_TO_POLICY_UNREPORTED) {
            // If we are trying to turn screen off, give policy a chance to do something before we
            // actually turn the screen off.
            if (isOff && !mScreenOffBecauseOfProximity) {
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

            if (!reportOnly && mPowerState.getScreenState() != state) {
                Trace.traceCounter(Trace.TRACE_TAG_POWER, "ScreenState", state);
                // TODO(b/153319140) remove when we can get this from the above trace invocation
                SystemProperties.set("debug.tracing.screen_state", String.valueOf(state));
                mPowerState.setScreenState(state);
                // Tell battery stats about the transition.
                noteScreenState(state);
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
            mWindowManagerPolicy.screenTurnedOff(mDisplayId);
        } else if (!isOff
                && mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_TURNING_OFF) {

            // We told policy already that screen was turning off, but now we changed our minds.
            // Complete the full state transition on -> turningOff -> off.
            unblockScreenOff();
            mWindowManagerPolicy.screenTurnedOff(mDisplayId);
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
        return mPendingScreenOnUnblocker == null;
    }

    private void setReportedScreenState(int state) {
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "ReportedScreenStateToPolicy", state);
        mReportedScreenStateToPolicy = state;
    }

    private void loadAmbientLightSensor() {
        DisplayDeviceConfig.SensorIdentifier lightSensor =
                mDisplayDeviceConfig.getAmbientLightSensor();
        String lightSensorName = lightSensor.name;
        String lightSensorType = lightSensor.type;
        mLightSensor = findSensor(lightSensorType, lightSensorName, Sensor.TYPE_LIGHT,
                mDisplayId == Display.DEFAULT_DISPLAY);
    }

    private void loadProximitySensor() {
        if (DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT) {
            return;
        }
        final DisplayDeviceConfig.SensorIdentifier proxSensor =
                mDisplayDeviceConfig.getProximitySensor();
        final String proxSensorName = proxSensor.name;
        final String proxSensorType = proxSensor.type;
        mProximitySensor = findSensor(proxSensorType, proxSensorName, Sensor.TYPE_PROXIMITY,
                mDisplayId == Display.DEFAULT_DISPLAY);
        if (mProximitySensor != null) {
            mProximityThreshold = Math.min(mProximitySensor.getMaximumRange(),
                    TYPICAL_PROXIMITY_THRESHOLD);
        }
    }

    private float clampScreenBrightnessForVr(float value) {
        return MathUtils.constrain(
                value, mScreenBrightnessForVrRangeMinimum,
                mScreenBrightnessForVrRangeMaximum);
    }

    private float clampScreenBrightness(float value) {
        if (Float.isNaN(value)) {
            value = PowerManager.BRIGHTNESS_MIN;
        }
        return MathUtils.constrain(value,
                mHbmController.getCurrentBrightnessMin(), mHbmController.getCurrentBrightnessMax());
    }

    // Checks whether the brightness is within the valid brightness range, not including the off or
    // invalid states.
    private boolean isValidBrightnessValue(float brightnessState) {
        return brightnessState >= PowerManager.BRIGHTNESS_MIN
                && brightnessState <= PowerManager.BRIGHTNESS_MAX;
    }

    private void animateScreenBrightness(float target, float sdrTarget, float rate) {
        if (DEBUG) {
            Slog.d(TAG, "Animating brightness: target=" + target + ", sdrTarget=" + sdrTarget
                    + ", rate=" + rate);
        }
        if (mScreenBrightnessRampAnimator.animateTo(target, sdrTarget, rate)) {
            Trace.traceCounter(Trace.TRACE_TAG_POWER, "TargetScreenBrightness", (int) target);
            // TODO(b/153319140) remove when we can get this from the above trace invocation
            SystemProperties.set("debug.tracing.screen_brightness", String.valueOf(target));
            noteScreenBrightness(target);
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

    private final Runnable mCleanListener = this::sendUpdatePowerState;

    private void setProximitySensorEnabled(boolean enable) {
        if (enable) {
            if (!mProximitySensorEnabled) {
                // Register the listener.
                // Proximity sensor state already cleared initially.
                mProximitySensorEnabled = true;
                mIgnoreProximityUntilChanged = false;
                mSensorManager.registerListener(mProximitySensorListener, mProximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            }
        } else {
            if (mProximitySensorEnabled) {
                // Unregister the listener.
                // Clear the proximity sensor state for next time.
                mProximitySensorEnabled = false;
                mProximity = PROXIMITY_UNKNOWN;
                mIgnoreProximityUntilChanged = false;
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
                if (mProximity != mPendingProximity) {
                    // if the status of the sensor changed, stop ignoring.
                    mIgnoreProximityUntilChanged = false;
                    Slog.i(TAG, "No longer ignoring proximity [" + mPendingProximity + "]");
                }
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
            setCurrentScreenBrightness(mPendingScreenBrightnessSetting);
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

    float getScreenBrightnessSetting() {
        float brightness = mBrightnessSetting.getBrightness();
        if (Float.isNaN(brightness)) {
            brightness = mScreenBrightnessDefault;
        }
        return clampAbsoluteBrightness(brightness);
    }

    private float getScreenBrightnessForVrSetting() {
        final float brightnessFloat = Settings.System.getFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT, mScreenBrightnessForVrDefault,
                UserHandle.USER_CURRENT);
        return clampScreenBrightnessForVr(brightnessFloat);
    }

    void putScreenBrightnessSetting(float brightnessValue) {
        putScreenBrightnessSetting(brightnessValue, false);
    }

    private void putScreenBrightnessSetting(float brightnessValue, boolean updateCurrent) {
        if (updateCurrent) {
            setCurrentScreenBrightness(brightnessValue);
        }
        mBrightnessSetting.setBrightness(brightnessValue);
    }

    private void setCurrentScreenBrightness(float brightnessValue) {
        if (brightnessValue != mCurrentScreenBrightnessSetting) {
            mCurrentScreenBrightnessSetting = brightnessValue;
            mHandler.post(mOnBrightnessChangeRunnable);
        }
    }

    private void putAutoBrightnessAdjustmentSetting(float adjustment) {
        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mAutoBrightnessAdjustment = adjustment;
            Settings.System.putFloatForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, adjustment,
                    UserHandle.USER_CURRENT);
        }
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
        if ((Float.isNaN(mPendingScreenBrightnessSetting)
                || mPendingScreenBrightnessSetting < 0.0f)) {
            return false;
        }
        if (BrightnessSynchronizer.floatEquals(
                mCurrentScreenBrightnessSetting, mPendingScreenBrightnessSetting)) {
            mPendingScreenBrightnessSetting = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mTemporaryScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            return false;
        }
        setCurrentScreenBrightness(mPendingScreenBrightnessSetting);
        mLastUserSetScreenBrightness = mPendingScreenBrightnessSetting;
        mPendingScreenBrightnessSetting = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mTemporaryScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        return true;
    }

    private void notifyBrightnessChanged(float brightness, boolean userInitiated,
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
                    mAutomaticBrightnessController.isDefaultConfig(), mUniqueDisplayId);
        }
    }

    private float convertToNits(float brightness) {
        if (mBrightnessMapper != null) {
            return mBrightnessMapper.convertToNits(brightness);
        } else {
            return -1.0f;
        }
    }

    private void updatePendingProximityRequestsLocked() {
        mWaitingForNegativeProximity |= mPendingWaitForNegativeProximityLocked;
        mPendingWaitForNegativeProximityLocked = false;

        if (mIgnoreProximityUntilChanged) {
            // Also, lets stop waiting for negative proximity if we're ignoring it.
            mWaitingForNegativeProximity = false;
        }
    }

    private void ignoreProximitySensorUntilChangedInternal() {
        if (!mIgnoreProximityUntilChanged
                && mPowerRequest.useProximitySensor
                && mProximity == PROXIMITY_POSITIVE) {
            // Only ignore if it is still reporting positive (near)
            mIgnoreProximityUntilChanged = true;
            Slog.i(TAG, "Ignoring proximity");
            updatePowerState();
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
            pw.println("Display Power Controller:");
            pw.println("  mDisplayId=" + mDisplayId);
            pw.println("  mLightSensor=" + mLightSensor);

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
        pw.println("  mScreenBrightnessRangeDefault=" + mScreenBrightnessDefault);
        pw.println("  mScreenBrightnessDozeConfig=" + mScreenBrightnessDozeConfig);
        pw.println("  mScreenBrightnessDimConfig=" + mScreenBrightnessDimConfig);
        pw.println("  mScreenBrightnessForVrRangeMinimum=" + mScreenBrightnessForVrRangeMinimum);
        pw.println("  mScreenBrightnessForVrRangeMaximum=" + mScreenBrightnessForVrRangeMaximum);
        pw.println("  mScreenBrightnessForVrDefault=" + mScreenBrightnessForVrDefault);
        pw.println("  mUseSoftwareAutoBrightnessConfig=" + mUseSoftwareAutoBrightnessConfig);
        pw.println("  mAllowAutoBrightnessWhileDozingConfig=" +
                mAllowAutoBrightnessWhileDozingConfig);
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
        pw.println("  mPendingScreenBrightnessSetting="
                + mPendingScreenBrightnessSetting);
        pw.println("  mTemporaryScreenBrightness=" + mTemporaryScreenBrightness);
        pw.println("  mAutoBrightnessAdjustment=" + mAutoBrightnessAdjustment);
        pw.println("  mBrightnessReason=" + mBrightnessReason);
        pw.println("  mTemporaryAutoBrightnessAdjustment=" + mTemporaryAutoBrightnessAdjustment);
        pw.println("  mPendingAutoBrightnessAdjustment=" + mPendingAutoBrightnessAdjustment);
        pw.println("  mScreenBrightnessForVrFloat=" + mScreenBrightnessForVr);
        pw.println("  mAppliedAutoBrightness=" + mAppliedAutoBrightness);
        pw.println("  mAppliedDimming=" + mAppliedDimming);
        pw.println("  mAppliedLowPower=" + mAppliedLowPower);
        pw.println("  mAppliedScreenBrightnessOverride=" + mAppliedScreenBrightnessOverride);
        pw.println("  mAppliedTemporaryBrightness=" + mAppliedTemporaryBrightness);
        pw.println("  mDozing=" + mDozing);
        pw.println("  mSkipRampState=" + skipRampStateToString(mSkipRampState));
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

        if (mHbmController != null) {
            mHbmController.dump(pw);
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

    private static float clampAbsoluteBrightness(float value) {
        return MathUtils.constrain(value, PowerManager.BRIGHTNESS_MIN,
                PowerManager.BRIGHTNESS_MAX);
    }

    private static float clampAutoBrightnessAdjustment(float value) {
        return MathUtils.constrain(value, -1.0f, 1.0f);
    }

    private void noteScreenState(int screenState) {
        if (mBatteryStats != null) {
            try {
                // TODO(multi-display): make this multi-display
                mBatteryStats.noteScreenState(screenState);
            } catch (RemoteException e) {
                // same process
            }
        }
    }

    private void noteScreenBrightness(float brightness) {
        if (mBatteryStats != null) {
            try {
                // TODO(brightnessfloat): change BatteryStats to use float
                mBatteryStats.noteScreenBrightness(BrightnessSynchronizer.brightnessFloatToInt(
                        brightness));
            } catch (RemoteException e) {
                // same process
            }
        }
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
                    mBrightnessConfiguration = (BrightnessConfiguration) msg.obj;
                    updatePowerState();
                    break;

                case MSG_SET_TEMPORARY_BRIGHTNESS:
                    // TODO: Should we have a a timeout for the temporary brightness?
                    mTemporaryScreenBrightness = Float.intBitsToFloat(msg.arg1);
                    updatePowerState();
                    break;

                case MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT:
                    mTemporaryAutoBrightnessAdjustment = Float.intBitsToFloat(msg.arg1);
                    updatePowerState();
                    break;

                case MSG_IGNORE_PROXIMITY:
                    ignoreProximitySensorUntilChangedInternal();
                    break;

                case MSG_STOP:
                    cleanupHandlerThreadAfterStop();
                    break;

                case MSG_UPDATE_BRIGHTNESS:
                    if (mStopped) {
                        return;
                    }
                    handleSettingsChange(false /*userSwitch*/);
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
        static final int MODIFIER_HDR = 0x4;
        static final int MODIFIER_MASK = MODIFIER_DIMMED | MODIFIER_LOW_POWER | MODIFIER_HDR;

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
            if ((modifier & MODIFIER_HDR) != 0) {
                sb.append(" hdr");
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

    static class CachedBrightnessInfo {
        public float brightness;
        public float brightnessMin;
        public float brightnessMax;
        public int hbmMode;
    }
}
