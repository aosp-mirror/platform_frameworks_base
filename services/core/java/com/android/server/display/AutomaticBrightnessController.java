/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.server.display.BrightnessMappingStrategy.INVALID_LUX;
import static com.android.server.display.config.DisplayBrightnessMappingConfig.autoBrightnessModeToString;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.os.BackgroundThread;
import com.android.server.EventLogTags;
import com.android.server.display.brightness.BrightnessEvent;
import com.android.server.display.config.HysteresisLevels;
import com.android.server.display.feature.DisplayManagerFlags;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.TimeUnit;

/**
 * Manages the associated display brightness when in auto-brightness mode. This is also
 * responsible for managing the brightness lux-nits mapping strategies. Internally also listens to
 * the LightSensor and adjusts the system brightness in case of changes in the surrounding lux.
 */
public class AutomaticBrightnessController {
    private static final String TAG = "AutomaticBrightnessController";

    private static final boolean DEBUG_PRETEND_LIGHT_SENSOR_ABSENT = false;

    public static final int AUTO_BRIGHTNESS_ENABLED = 1;
    public static final int AUTO_BRIGHTNESS_DISABLED = 2;
    public static final int AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE = 3;

    @IntDef(prefix = { "AUTO_BRIGHTNESS_MODE_" }, value = {
            AUTO_BRIGHTNESS_MODE_DEFAULT,
            AUTO_BRIGHTNESS_MODE_IDLE,
            AUTO_BRIGHTNESS_MODE_DOZE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AutomaticBrightnessMode{}

    public static final int AUTO_BRIGHTNESS_MODE_DEFAULT = 0;
    public static final int AUTO_BRIGHTNESS_MODE_IDLE = 1;
    public static final int AUTO_BRIGHTNESS_MODE_DOZE = 2;
    public static final int AUTO_BRIGHTNESS_MODE_MAX = AUTO_BRIGHTNESS_MODE_DOZE;

    // How long the current sensor reading is assumed to be valid beyond the current time.
    // This provides a bit of prediction, as well as ensures that the weight for the last sample is
    // non-zero, which in turn ensures that the total weight is non-zero.
    private static final long AMBIENT_LIGHT_PREDICTION_TIME_MILLIS = 100;

    // Debounce for sampling user-initiated changes in display brightness to ensure
    // the user is satisfied with the result before storing the sample.
    private static final int BRIGHTNESS_ADJUSTMENT_SAMPLE_DEBOUNCE_MILLIS = 10000;

    private static final int MSG_UPDATE_AMBIENT_LUX = 1;
    private static final int MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE = 2;
    private static final int MSG_INVALIDATE_CURRENT_SHORT_TERM_MODEL = 3;
    private static final int MSG_UPDATE_FOREGROUND_APP = 4;
    private static final int MSG_UPDATE_FOREGROUND_APP_SYNC = 5;
    private static final int MSG_RUN_UPDATE = 6;
    private static final int MSG_INVALIDATE_PAUSED_SHORT_TERM_MODEL = 7;

    // Callbacks for requesting updates to the display's power state
    private final Callbacks mCallbacks;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The light sensor, or null if not available or needed.
    private final Sensor mLightSensor;

    // The mapper to translate ambient lux to screen brightness in the range [0, 1.0].
    @NonNull
    private BrightnessMappingStrategy mCurrentBrightnessMapper;

    // A map of Brightness Mapping Strategies indexed by AutomaticBrightnessMode
    private final SparseArray<BrightnessMappingStrategy> mBrightnessMappingStrategyMap;

    // The minimum and maximum screen brightnesses.
    private final float mScreenBrightnessRangeMinimum;
    private final float mScreenBrightnessRangeMaximum;

    // How much to scale doze brightness by (should be (0, 1.0]).
    private final float mDozeScaleFactor;

    // Initial light sensor event rate in milliseconds.
    private final int mInitialLightSensorRate;

    // Steady-state light sensor event rate in milliseconds.
    private final int mNormalLightSensorRate;

    // The current light sensor event rate in milliseconds.
    private int mCurrentLightSensorRate;

    // Stability requirements in milliseconds for accepting a new brightness level.  This is used
    // for debouncing the light sensor.  Different constants are used to debounce the light sensor
    // when adapting to brighter or darker environments.  This parameter controls how quickly
    // brightness changes occur in response to an observed change in light level that exceeds the
    // hysteresis threshold.
    private final long mBrighteningLightDebounceConfig;
    private final long mDarkeningLightDebounceConfig;
    private final long mBrighteningLightDebounceConfigIdle;
    private final long mDarkeningLightDebounceConfigIdle;

    // If true immediately after the screen is turned on the controller will try to adjust the
    // brightness based on the current sensor reads. If false, the controller will collect more data
    // and only then decide whether to change brightness.
    private final boolean mResetAmbientLuxAfterWarmUpConfig;

    // Period of time in which to consider light samples for a short/long-term estimate of ambient
    // light in milliseconds.
    private final int mAmbientLightHorizonLong;
    private final int mAmbientLightHorizonShort;

    // The intercept used for the weighting calculation. This is used in order to keep all possible
    // weighting values positive.
    private final int mWeightingIntercept;

    // Configuration object for determining thresholds to change brightness dynamically
    private final HysteresisLevels mAmbientBrightnessThresholds;
    private final HysteresisLevels mScreenBrightnessThresholds;
    private final HysteresisLevels mAmbientBrightnessThresholdsIdle;
    private final HysteresisLevels mScreenBrightnessThresholdsIdle;

    private boolean mLoggingEnabled;

    // Amount of time to delay auto-brightness after screen on while waiting for
    // the light sensor to warm-up in milliseconds.
    // May be 0 if no warm-up is required.
    private int mLightSensorWarmUpTimeConfig;

    // Set to true if the light sensor is enabled.
    private boolean mLightSensorEnabled;

    // The time when the light sensor was enabled.
    private long mLightSensorEnableTime;

    // The currently accepted nominal ambient light level.
    private float mAmbientLux = INVALID_LUX;

    // The last calculated ambient light level (long time window).
    private float mSlowAmbientLux;

    // The last calculated ambient light level (short time window).
    private float mFastAmbientLux;

    // The last ambient lux value prior to passing the darkening or brightening threshold.
    private float mPreThresholdLux;

    // True if mAmbientLux holds a valid value.
    private boolean mAmbientLuxValid;

    // The ambient light level threshold at which to brighten or darken the screen.
    private float mAmbientBrighteningThreshold;
    private float mAmbientDarkeningThreshold;

    // The last brightness value prior to passing the darkening or brightening threshold.
    private float mPreThresholdBrightness;

    // The screen brightness threshold at which to brighten or darken the screen.
    private float mScreenBrighteningThreshold;
    private float mScreenDarkeningThreshold;
    // The most recent light sample.
    private float mLastObservedLux;

    // The time of the most light recent sample.
    private long mLastObservedLuxTime;

    // The number of light samples collected since the light sensor was enabled.
    private int mRecentLightSamples;

    // A ring buffer containing all of the recent ambient light sensor readings.
    private AmbientLightRingBuffer mAmbientLightRingBuffer;

    // The handler
    private AutomaticBrightnessHandler mHandler;

    // The screen brightness level that has been chosen by the auto-brightness
    // algorithm.  The actual brightness should ramp towards this value.
    // We preserve this value even when we stop using the light sensor so
    // that we can quickly revert to the previous auto-brightness level
    // while the light sensor warms up.
    // Use PowerManager.BRIGHTNESS_INVALID_FLOAT if there is no current auto-brightness value
    // available.
    private float mScreenAutoBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;

    // The screen brightness level before clamping and throttling. This value needs to be stored
    // for concurrent displays mode and passed to the additional displays which will do their own
    // clamping and throttling.
    private float mRawScreenAutoBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;

    // The current display policy. This is useful, for example,  for knowing when we're dozing,
    // where the light sensor may not be available.
    private int mDisplayPolicy = DisplayPowerRequest.POLICY_OFF;

    private int mDisplayState = Display.STATE_UNKNOWN;

    // True if the normal brightness should be forced while device is dozing.
    private boolean mUseNormalBrightnessForDoze;

    // True if we are collecting a brightness adjustment sample, along with some data
    // for the initial state of the sample.
    private boolean mBrightnessAdjustmentSamplePending;
    private float mBrightnessAdjustmentSampleOldLux;
    private float mBrightnessAdjustmentSampleOldBrightness;

    // The short term models, current and previous. Eg, we might use the "paused" one to save out
    // the interactive short term model when switching to idle screen brightness mode, and
    // vice-versa.
    private final ShortTermModel mShortTermModel;
    private final ShortTermModel mPausedShortTermModel;

    // Controls Brightness range (including High Brightness Mode).
    private final BrightnessRangeController mBrightnessRangeController;

    // Throttles (caps) maximum allowed brightness
    private final BrightnessThrottler mBrightnessThrottler;
    private boolean mIsBrightnessThrottled;

    // Context-sensitive brightness configurations require keeping track of the foreground app's
    // package name and category, which is done by registering a TaskStackListener to call back to
    // us onTaskStackChanged, and then using the ActivityTaskManager to get the foreground app's
    // package name and PackageManager to get its category (so might as well cache them).
    private String mForegroundAppPackageName;
    private String mPendingForegroundAppPackageName;
    private @ApplicationInfo.Category int mForegroundAppCategory;
    private @ApplicationInfo.Category int mPendingForegroundAppCategory;
    private TaskStackListenerImpl mTaskStackListener;
    private IActivityTaskManager mActivityTaskManager;
    private PackageManager mPackageManager;
    private Context mContext;
    private int mState = AUTO_BRIGHTNESS_DISABLED;

    private Clock mClock;
    private final Injector mInjector;

    private final DisplayManagerFlags mDisplayManagerFlags;

    AutomaticBrightnessController(Callbacks callbacks, Looper looper,
            SensorManager sensorManager, Sensor lightSensor,
            SparseArray<BrightnessMappingStrategy> brightnessMappingStrategyMap,
            int lightSensorWarmUpTime, float brightnessMin, float brightnessMax,
            float dozeScaleFactor, int lightSensorRate, int initialLightSensorRate,
            long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
            long brighteningLightDebounceConfigIdle, long darkeningLightDebounceConfigIdle,
            boolean resetAmbientLuxAfterWarmUpConfig, HysteresisLevels ambientBrightnessThresholds,
            HysteresisLevels screenBrightnessThresholds,
            HysteresisLevels ambientBrightnessThresholdsIdle,
            HysteresisLevels screenBrightnessThresholdsIdle, Context context,
            BrightnessRangeController brightnessModeController,
            BrightnessThrottler brightnessThrottler, int ambientLightHorizonShort,
            int ambientLightHorizonLong, float userLux, float userNits,
            DisplayManagerFlags displayManagerFlags) {
        this(new Injector(), callbacks, looper, sensorManager, lightSensor,
                brightnessMappingStrategyMap, lightSensorWarmUpTime, brightnessMin, brightnessMax,
                dozeScaleFactor, lightSensorRate, initialLightSensorRate,
                brighteningLightDebounceConfig, darkeningLightDebounceConfig,
                brighteningLightDebounceConfigIdle, darkeningLightDebounceConfigIdle,
                resetAmbientLuxAfterWarmUpConfig, ambientBrightnessThresholds,
                screenBrightnessThresholds, ambientBrightnessThresholdsIdle,
                screenBrightnessThresholdsIdle, context, brightnessModeController,
                brightnessThrottler, ambientLightHorizonShort, ambientLightHorizonLong, userLux,
                userNits, displayManagerFlags
        );
    }

    @VisibleForTesting
    AutomaticBrightnessController(Injector injector, Callbacks callbacks, Looper looper,
            SensorManager sensorManager, Sensor lightSensor,
            SparseArray<BrightnessMappingStrategy> brightnessMappingStrategyMap,
            int lightSensorWarmUpTime, float brightnessMin, float brightnessMax,
            float dozeScaleFactor, int lightSensorRate, int initialLightSensorRate,
            long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
            long brighteningLightDebounceConfigIdle, long darkeningLightDebounceConfigIdle,
            boolean resetAmbientLuxAfterWarmUpConfig, HysteresisLevels ambientBrightnessThresholds,
            HysteresisLevels screenBrightnessThresholds,
            HysteresisLevels ambientBrightnessThresholdsIdle,
            HysteresisLevels screenBrightnessThresholdsIdle, Context context,
            BrightnessRangeController brightnessRangeController,
            BrightnessThrottler brightnessThrottler, int ambientLightHorizonShort,
            int ambientLightHorizonLong, float userLux, float userNits,
            DisplayManagerFlags displayManagerFlags) {
        mInjector = injector;
        mClock = injector.createClock(displayManagerFlags.offloadControlsDozeAutoBrightness());
        mContext = context;
        mCallbacks = callbacks;
        mSensorManager = sensorManager;
        mCurrentBrightnessMapper = brightnessMappingStrategyMap.get(AUTO_BRIGHTNESS_MODE_DEFAULT);
        mScreenBrightnessRangeMinimum = brightnessMin;
        mScreenBrightnessRangeMaximum = brightnessMax;
        mLightSensorWarmUpTimeConfig = lightSensorWarmUpTime;
        mDozeScaleFactor = dozeScaleFactor;
        mNormalLightSensorRate = lightSensorRate;
        mInitialLightSensorRate = initialLightSensorRate;
        mCurrentLightSensorRate = -1;
        mBrighteningLightDebounceConfig = brighteningLightDebounceConfig;
        mDarkeningLightDebounceConfig = darkeningLightDebounceConfig;
        mBrighteningLightDebounceConfigIdle = brighteningLightDebounceConfigIdle;
        mDarkeningLightDebounceConfigIdle = darkeningLightDebounceConfigIdle;
        mResetAmbientLuxAfterWarmUpConfig = resetAmbientLuxAfterWarmUpConfig;
        mAmbientLightHorizonLong = ambientLightHorizonLong;
        mAmbientLightHorizonShort = ambientLightHorizonShort;
        mWeightingIntercept = ambientLightHorizonLong;
        mAmbientBrightnessThresholds = ambientBrightnessThresholds;
        mAmbientBrightnessThresholdsIdle = ambientBrightnessThresholdsIdle;
        mScreenBrightnessThresholds = screenBrightnessThresholds;
        mScreenBrightnessThresholdsIdle = screenBrightnessThresholdsIdle;
        mShortTermModel = new ShortTermModel();
        mPausedShortTermModel = new ShortTermModel();
        mHandler = new AutomaticBrightnessHandler(looper);
        mAmbientLightRingBuffer =
            new AmbientLightRingBuffer(mNormalLightSensorRate, mAmbientLightHorizonLong, mClock);

        if (!DEBUG_PRETEND_LIGHT_SENSOR_ABSENT) {
            mLightSensor = lightSensor;
        }

        mActivityTaskManager = ActivityTaskManager.getService();
        mPackageManager = mContext.getPackageManager();
        mTaskStackListener = new TaskStackListenerImpl();
        mForegroundAppPackageName = null;
        mPendingForegroundAppPackageName = null;
        mForegroundAppCategory = ApplicationInfo.CATEGORY_UNDEFINED;
        mPendingForegroundAppCategory = ApplicationInfo.CATEGORY_UNDEFINED;
        mBrightnessRangeController = brightnessRangeController;
        mBrightnessThrottler = brightnessThrottler;
        mBrightnessMappingStrategyMap = brightnessMappingStrategyMap;
        mDisplayManagerFlags = displayManagerFlags;

        // Use the given short-term model
        if (userNits != BrightnessMappingStrategy.INVALID_NITS) {
            setScreenBrightnessByUser(userLux, getBrightnessFromNits(userNits));
        }
    }

    /**
     * Enable/disable logging.
     *
     * @param loggingEnabled
     *      Whether logging should be on/off.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setLoggingEnabled(boolean loggingEnabled) {
        if (mLoggingEnabled == loggingEnabled) {
            return false;
        }
        for (int i = 0; i < mBrightnessMappingStrategyMap.size(); i++) {
            mBrightnessMappingStrategyMap.valueAt(i).setLoggingEnabled(loggingEnabled);
        }
        mLoggingEnabled = loggingEnabled;
        return true;
    }

    public float getAutomaticScreenBrightness() {
        return getAutomaticScreenBrightness(null);
    }

    /**
     * @param brightnessEvent Holds details about how the brightness is calculated.
     *
     * @return The current automatic brightness recommended value. Populates brightnessEvent
     *         parameters with details about how the brightness was calculated.
     */
    public float getAutomaticScreenBrightness(BrightnessEvent brightnessEvent) {
        if (brightnessEvent != null) {
            brightnessEvent.setLux(
                    mAmbientLuxValid ? mAmbientLux : PowerManager.BRIGHTNESS_INVALID_FLOAT);
            brightnessEvent.setPreThresholdLux(mPreThresholdLux);
            brightnessEvent.setPreThresholdBrightness(mPreThresholdBrightness);
            brightnessEvent.setRecommendedBrightness(mScreenAutoBrightness);
            brightnessEvent.setFlags(brightnessEvent.getFlags()
                    | (!mAmbientLuxValid ? BrightnessEvent.FLAG_INVALID_LUX : 0)
                    | (shouldApplyDozeScaleFactor() ? BrightnessEvent.FLAG_DOZE_SCALE : 0));
            brightnessEvent.setAutoBrightnessMode(getMode());
        }

        if (!mAmbientLuxValid) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }
        if (shouldApplyDozeScaleFactor()) {
            return mScreenAutoBrightness * mDozeScaleFactor;
        }
        return mScreenAutoBrightness;
    }

    public float getRawAutomaticScreenBrightness() {
        return mRawScreenAutoBrightness;
    }

    public boolean hasValidAmbientLux() {
        return mAmbientLuxValid;
    }

    public float getAutomaticScreenBrightnessAdjustment() {
        return mCurrentBrightnessMapper.getAutoBrightnessAdjustment();
    }

    public void configure(int state, @Nullable BrightnessConfiguration configuration,
            float brightness, boolean userChangedBrightness, float adjustment,
            boolean userChangedAutoBrightnessAdjustment, int displayPolicy, int displayState,
            boolean useNormalBrightnessForDoze, boolean shouldResetShortTermModel) {
        mState = state;
        boolean changed = setBrightnessConfiguration(configuration, shouldResetShortTermModel);
        changed |= setDisplayPolicy(displayPolicy);
        mDisplayState = displayState;
        mUseNormalBrightnessForDoze = useNormalBrightnessForDoze;
        if (userChangedAutoBrightnessAdjustment) {
            changed |= setAutoBrightnessAdjustment(adjustment);
        }
        final boolean enable = mState == AUTO_BRIGHTNESS_ENABLED;
        if (userChangedBrightness && enable) {
            // Update the brightness curve with the new user control point. It's critical this
            // happens after we update the autobrightness adjustment since it may reset it.
            changed |= setScreenBrightnessByUser(brightness);
        }
        final boolean userInitiatedChange =
                userChangedBrightness || userChangedAutoBrightnessAdjustment;
        if (userInitiatedChange && enable) {
            prepareBrightnessAdjustmentSample();
        }
        changed |= setLightSensorEnabled(enable);

        if (mIsBrightnessThrottled != mBrightnessThrottler.isThrottled()) {
            // Maximum brightness has changed, so recalculate display brightness.
            mIsBrightnessThrottled = mBrightnessThrottler.isThrottled();
            changed = true;
        }

        if (changed) {
            updateAutoBrightness(false /*sendUpdate*/, userInitiatedChange);
        }
    }

    public void stop() {
        setLightSensorEnabled(false);
    }

    public boolean hasUserDataPoints() {
        return mCurrentBrightnessMapper.hasUserDataPoints();
    }

    // Used internally to establish whether we have deviated from the default config.
    public boolean isDefaultConfig() {
        return mCurrentBrightnessMapper.getMode() == AUTO_BRIGHTNESS_MODE_DEFAULT
                && mCurrentBrightnessMapper.isDefaultConfig();
    }

    // Called from APIs to get the configuration.
    public BrightnessConfiguration getDefaultConfig() {
        return mBrightnessMappingStrategyMap.get(AUTO_BRIGHTNESS_MODE_DEFAULT).getDefaultConfig();
    }

    /**
     * Force recalculate of the state of automatic brightness.
     */
    public void update() {
        mHandler.sendEmptyMessage(MSG_RUN_UPDATE);
    }

    float getAmbientLux() {
        return mAmbientLux;
    }

    float getSlowAmbientLux() {
        return mSlowAmbientLux;
    }

    float getFastAmbientLux() {
        return mFastAmbientLux;
    }

    private boolean setDisplayPolicy(int policy) {
        if (mDisplayPolicy == policy) {
            return false;
        }
        final int oldPolicy = mDisplayPolicy;
        mDisplayPolicy = policy;
        if (mLoggingEnabled) {
            Slog.d(TAG, "Display policy transitioning from " + oldPolicy + " to " + policy);
        }
        if (!isInteractivePolicy(policy) && isInteractivePolicy(oldPolicy) && !isInIdleMode()) {
            mHandler.sendEmptyMessageDelayed(MSG_INVALIDATE_CURRENT_SHORT_TERM_MODEL,
                    mCurrentBrightnessMapper.getShortTermModelTimeout());
        } else if (isInteractivePolicy(policy) && !isInteractivePolicy(oldPolicy)) {
            mHandler.removeMessages(MSG_INVALIDATE_CURRENT_SHORT_TERM_MODEL);
        }
        return true;
    }

    private static boolean isInteractivePolicy(int policy) {
        return policy == DisplayPowerRequest.POLICY_BRIGHT
                || policy == DisplayPowerRequest.POLICY_DIM;
    }

    private boolean setScreenBrightnessByUser(float brightness) {
        if (!mAmbientLuxValid) {
            // If we don't have a valid ambient lux then we don't have a valid brightness anyway,
            // and we can't use this data to add a new control point to the short-term model.
            return false;
        }
        return setScreenBrightnessByUser(mAmbientLux, brightness);
    }

    private boolean setScreenBrightnessByUser(float lux, float brightness) {
        if (lux == INVALID_LUX || Float.isNaN(brightness)) {
            return false;
        }
        mCurrentBrightnessMapper.addUserDataPoint(lux, brightness);
        mShortTermModel.setUserBrightness(lux, brightness);
        return true;
    }

    public void resetShortTermModel() {
        mCurrentBrightnessMapper.clearUserDataPoints();
        mShortTermModel.reset();
        Slog.i(TAG, "Resetting short term model");
    }

    public boolean setBrightnessConfiguration(BrightnessConfiguration configuration,
            boolean shouldResetShortTermModel) {
        if (mBrightnessMappingStrategyMap.get(AUTO_BRIGHTNESS_MODE_DEFAULT)
                .setBrightnessConfiguration(configuration)) {
            if (!isInIdleMode() && shouldResetShortTermModel) {
                resetShortTermModel();
            }
            return true;
        }
        return false;
    }

    /**
     * @return The auto-brightness mode of the current mapping strategy. Different modes use
     * different brightness curves.
     */
    @AutomaticBrightnessController.AutomaticBrightnessMode
    public int getMode() {
        return mCurrentBrightnessMapper.getMode();
    }

    /**
     * @return The preset for this mapping strategy. Presets are used on devices that allow users
     * to choose from a set of predefined options in display auto-brightness settings.
     */
    public int getPreset() {
        return mCurrentBrightnessMapper.getPreset();
    }

    public boolean isInIdleMode() {
        return mCurrentBrightnessMapper.getMode() == AUTO_BRIGHTNESS_MODE_IDLE;
    }

    public void dump(PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.increaseIndent();
        pw.println();
        pw.println("Automatic Brightness Controller Configuration:");
        pw.println("----------------------------------------------");
        ipw.println("mState=" + configStateToString(mState));
        ipw.println("mScreenBrightnessRangeMinimum=" + mScreenBrightnessRangeMinimum);
        ipw.println("mScreenBrightnessRangeMaximum=" + mScreenBrightnessRangeMaximum);
        ipw.println("mDozeScaleFactor=" + mDozeScaleFactor);
        ipw.println("mInitialLightSensorRate=" + mInitialLightSensorRate);
        ipw.println("mNormalLightSensorRate=" + mNormalLightSensorRate);
        ipw.println("mLightSensorWarmUpTimeConfig=" + mLightSensorWarmUpTimeConfig);
        ipw.println("mBrighteningLightDebounceConfig=" + mBrighteningLightDebounceConfig);
        ipw.println("mDarkeningLightDebounceConfig=" + mDarkeningLightDebounceConfig);
        ipw.println("mBrighteningLightDebounceConfigIdle=" + mBrighteningLightDebounceConfigIdle);
        ipw.println("mDarkeningLightDebounceConfigIdle=" + mDarkeningLightDebounceConfigIdle);
        ipw.println("mResetAmbientLuxAfterWarmUpConfig=" + mResetAmbientLuxAfterWarmUpConfig);
        ipw.println("mAmbientLightHorizonLong=" + mAmbientLightHorizonLong);
        ipw.println("mAmbientLightHorizonShort=" + mAmbientLightHorizonShort);
        ipw.println("mWeightingIntercept=" + mWeightingIntercept);

        pw.println();
        pw.println("Automatic Brightness Controller State:");
        pw.println("--------------------------------------");
        ipw.println("mLightSensor=" + mLightSensor);
        ipw.println("mLightSensorEnabled=" + mLightSensorEnabled);
        ipw.println("mLightSensorEnableTime=" + TimeUtils.formatUptime(mLightSensorEnableTime));
        ipw.println("mCurrentLightSensorRate=" + mCurrentLightSensorRate);
        ipw.println("mAmbientLux=" + mAmbientLux);
        ipw.println("mAmbientLuxValid=" + mAmbientLuxValid);
        ipw.println("mPreThresholdLux=" + mPreThresholdLux);
        ipw.println("mPreThresholdBrightness=" + mPreThresholdBrightness);
        ipw.println("mAmbientBrighteningThreshold=" + mAmbientBrighteningThreshold);
        ipw.println("mAmbientDarkeningThreshold=" + mAmbientDarkeningThreshold);
        ipw.println("mScreenBrighteningThreshold=" + mScreenBrighteningThreshold);
        ipw.println("mScreenDarkeningThreshold=" + mScreenDarkeningThreshold);
        ipw.println("mLastObservedLux=" + mLastObservedLux);
        ipw.println("mLastObservedLuxTime=" + TimeUtils.formatUptime(mLastObservedLuxTime));
        ipw.println("mRecentLightSamples=" + mRecentLightSamples);
        ipw.println("mAmbientLightRingBuffer=" + mAmbientLightRingBuffer);
        ipw.println("mScreenAutoBrightness=" + mScreenAutoBrightness);
        ipw.println("mDisplayPolicy=" + DisplayPowerRequest.policyToString(mDisplayPolicy));
        ipw.println("mShortTermModel=");

        mShortTermModel.dump(ipw);
        ipw.println("mPausedShortTermModel=");
        mPausedShortTermModel.dump(ipw);

        ipw.println();
        ipw.println("mBrightnessAdjustmentSamplePending=" + mBrightnessAdjustmentSamplePending);
        ipw.println("mBrightnessAdjustmentSampleOldLux=" + mBrightnessAdjustmentSampleOldLux);
        ipw.println("mBrightnessAdjustmentSampleOldBrightness="
                + mBrightnessAdjustmentSampleOldBrightness);
        ipw.println("mForegroundAppPackageName=" + mForegroundAppPackageName);
        ipw.println("mPendingForegroundAppPackageName=" + mPendingForegroundAppPackageName);
        ipw.println("mForegroundAppCategory=" + mForegroundAppCategory);
        ipw.println("mPendingForegroundAppCategory=" + mPendingForegroundAppCategory);
        ipw.println("Current mode="
                + autoBrightnessModeToString(mCurrentBrightnessMapper.getMode()));

        for (int i = 0; i < mBrightnessMappingStrategyMap.size(); i++) {
            ipw.println();
            ipw.println("Mapper for mode "
                    + autoBrightnessModeToString(mBrightnessMappingStrategyMap.keyAt(i)) + ":");
            mBrightnessMappingStrategyMap.valueAt(i).dump(ipw,
                    mBrightnessRangeController.getNormalBrightnessMax());
        }

        ipw.println();
        ipw.println("mAmbientBrightnessThresholds=" + mAmbientBrightnessThresholds);
        ipw.println("mAmbientBrightnessThresholdsIdle=" + mAmbientBrightnessThresholdsIdle);
        ipw.println("mScreenBrightnessThresholds=" + mScreenBrightnessThresholds);
        ipw.println("mScreenBrightnessThresholdsIdle=" + mScreenBrightnessThresholdsIdle);
    }

    public float[] getLastSensorValues() {
        return mAmbientLightRingBuffer.getAllLuxValues();
    }

    public long[] getLastSensorTimestamps() {
        return mAmbientLightRingBuffer.getAllTimestamps();
    }

    private String configStateToString(int state) {
        switch (state) {
        case AUTO_BRIGHTNESS_ENABLED:
            return "AUTO_BRIGHTNESS_ENABLED";
        case AUTO_BRIGHTNESS_DISABLED:
            return "AUTO_BRIGHTNESS_DISABLED";
        case AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE:
            return "AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE";
        default:
            return String.valueOf(state);
        }
    }

    private boolean setLightSensorEnabled(boolean enable) {
        if (enable) {
            if (!mLightSensorEnabled) {
                mLightSensorEnabled = true;
                mLightSensorEnableTime = mClock.uptimeMillis();
                mCurrentLightSensorRate = mInitialLightSensorRate;
                registerForegroundAppUpdater();
                mSensorManager.registerListener(mLightSensorListener, mLightSensor,
                        mCurrentLightSensorRate * 1000, mHandler);
                return true;
            }
        } else if (mLightSensorEnabled) {
            mLightSensorEnabled = false;
            mAmbientLuxValid = !mResetAmbientLuxAfterWarmUpConfig;
            if (!mAmbientLuxValid) {
                mPreThresholdLux = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            }
            mScreenAutoBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mRawScreenAutoBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mPreThresholdBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mRecentLightSamples = 0;
            mAmbientLightRingBuffer.clear();
            mCurrentLightSensorRate = -1;
            mHandler.removeMessages(MSG_UPDATE_AMBIENT_LUX);
            unregisterForegroundAppUpdater();
            mSensorManager.unregisterListener(mLightSensorListener);
        }
        return false;
    }

    private void handleLightSensorEvent(long time, float lux) {
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "ALS", (int) lux);
        mHandler.removeMessages(MSG_UPDATE_AMBIENT_LUX);

        if (mAmbientLightRingBuffer.size() == 0) {
            // switch to using the steady-state sample rate after grabbing the initial light sample
            adjustLightSensorRate(mNormalLightSensorRate);
        }
        applyLightSensorMeasurement(time, lux);
        updateAmbientLux(time);
    }

    private void applyLightSensorMeasurement(long time, float lux) {
        mRecentLightSamples++;
        mAmbientLightRingBuffer.prune(time - mAmbientLightHorizonLong);
        mAmbientLightRingBuffer.push(time, lux);
        // Remember this sample value.
        mLastObservedLux = lux;
        mLastObservedLuxTime = time;
    }

    private void adjustLightSensorRate(int lightSensorRate) {
        // if the light sensor rate changed, update the sensor listener
        if (lightSensorRate != mCurrentLightSensorRate) {
            if (mLoggingEnabled) {
                Slog.d(TAG, "adjustLightSensorRate: " +
                        "previousRate=" + mCurrentLightSensorRate + ", " +
                        "currentRate=" + lightSensorRate);
            }
            mCurrentLightSensorRate = lightSensorRate;
            mSensorManager.unregisterListener(mLightSensorListener);
            mSensorManager.registerListener(mLightSensorListener, mLightSensor,
                    lightSensorRate * 1000, mHandler);
        }
    }

    private boolean setAutoBrightnessAdjustment(float adjustment) {
        return mCurrentBrightnessMapper.setAutoBrightnessAdjustment(adjustment);
    }

    private void setAmbientLux(float lux) {
        if (mLoggingEnabled) {
            Slog.d(TAG, "setAmbientLux(" + lux + ")");
        }
        if (lux < 0) {
            Slog.w(TAG, "Ambient lux was negative, ignoring and setting to 0");
            lux = 0;
        }
        mAmbientLux = lux;
        if (isInIdleMode()) {
            mAmbientBrighteningThreshold =
                    mAmbientBrightnessThresholdsIdle.getBrighteningThreshold(lux);
            mAmbientDarkeningThreshold =
                    mAmbientBrightnessThresholdsIdle.getDarkeningThreshold(lux);
        } else {
            mAmbientBrighteningThreshold =
                    mAmbientBrightnessThresholds.getBrighteningThreshold(lux);
            mAmbientDarkeningThreshold =
                    mAmbientBrightnessThresholds.getDarkeningThreshold(lux);
        }
        mBrightnessRangeController.onAmbientLuxChange(mAmbientLux);

        // If the short term model was invalidated and the change is drastic enough, reset it.
        mShortTermModel.maybeReset(mAmbientLux);
    }

    private float calculateAmbientLux(long now, long horizon) {
        if (mLoggingEnabled) {
            Slog.d(TAG, "calculateAmbientLux(" + now + ", " + horizon + ")");
        }
        final int N = mAmbientLightRingBuffer.size();
        if (N == 0) {
            Slog.e(TAG, "calculateAmbientLux: No ambient light readings available");
            return -1;
        }

        // Find the first measurement that is just outside of the horizon.
        int endIndex = 0;
        final long horizonStartTime = now - horizon;
        for (int i = 0; i < N-1; i++) {
            if (mAmbientLightRingBuffer.getTime(i + 1) <= horizonStartTime) {
                endIndex++;
            } else {
                break;
            }
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "calculateAmbientLux: selected endIndex=" + endIndex + ", point=(" +
                    mAmbientLightRingBuffer.getTime(endIndex) + ", " +
                    mAmbientLightRingBuffer.getLux(endIndex) + ")");
        }
        float sum = 0;
        float totalWeight = 0;
        long endTime = AMBIENT_LIGHT_PREDICTION_TIME_MILLIS;
        for (int i = N - 1; i >= endIndex; i--) {
            long eventTime = mAmbientLightRingBuffer.getTime(i);
            if (i == endIndex && eventTime < horizonStartTime) {
                // If we're at the final value, make sure we only consider the part of the sample
                // within our desired horizon.
                eventTime = horizonStartTime;
            }
            final long startTime = eventTime - now;
            float weight = calculateWeight(startTime, endTime);
            float lux = mAmbientLightRingBuffer.getLux(i);
            if (mLoggingEnabled) {
                Slog.d(TAG, "calculateAmbientLux: [" + startTime + ", " + endTime + "]: " +
                        "lux=" + lux + ", " +
                        "weight=" + weight);
            }
            totalWeight += weight;
            sum += lux * weight;
            endTime = startTime;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "calculateAmbientLux: " +
                    "totalWeight=" + totalWeight + ", " +
                    "newAmbientLux=" + (sum / totalWeight));
        }
        return sum / totalWeight;
    }

    private float calculateWeight(long startDelta, long endDelta) {
        return weightIntegral(endDelta) - weightIntegral(startDelta);
    }

    // Evaluates the integral of y = x + mWeightingIntercept. This is always positive for the
    // horizon we're looking at and provides a non-linear weighting for light samples.
    private float weightIntegral(long x) {
        return x * (x * 0.5f + mWeightingIntercept);
    }

    private long nextAmbientLightBrighteningTransition(long time) {
        final int N = mAmbientLightRingBuffer.size();
        long earliestValidTime = time;
        for (int i = N - 1; i >= 0; i--) {
            if (mAmbientLightRingBuffer.getLux(i) <= mAmbientBrighteningThreshold) {
                break;
            }
            earliestValidTime = mAmbientLightRingBuffer.getTime(i);
        }
        return earliestValidTime + (isInIdleMode()
                ? mBrighteningLightDebounceConfigIdle : mBrighteningLightDebounceConfig);
    }

    private long nextAmbientLightDarkeningTransition(long time) {
        final int N = mAmbientLightRingBuffer.size();
        long earliestValidTime = time;
        for (int i = N - 1; i >= 0; i--) {
            if (mAmbientLightRingBuffer.getLux(i) >= mAmbientDarkeningThreshold) {
                break;
            }
            earliestValidTime = mAmbientLightRingBuffer.getTime(i);
        }
        return earliestValidTime + (isInIdleMode()
                ? mDarkeningLightDebounceConfigIdle : mDarkeningLightDebounceConfig);
    }

    private void updateAmbientLux() {
        long time = mClock.getSensorEventScaleTime();
        mAmbientLightRingBuffer.prune(time - mAmbientLightHorizonLong);
        updateAmbientLux(time);
    }

    private void updateAmbientLux(long time) {
        // If the light sensor was just turned on then immediately update our initial
        // estimate of the current ambient light level.
        if (!mAmbientLuxValid) {
            final long timeWhenSensorWarmedUp =
                mLightSensorWarmUpTimeConfig + mLightSensorEnableTime;
            if (time < timeWhenSensorWarmedUp) {
                if (mLoggingEnabled) {
                    Slog.d(TAG, "updateAmbientLux: Sensor not ready yet: "
                            + "time=" + time + ", "
                            + "timeWhenSensorWarmedUp=" + timeWhenSensorWarmedUp);
                }
                mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX,
                        timeWhenSensorWarmedUp);
                return;
            }
            setAmbientLux(calculateAmbientLux(time, mAmbientLightHorizonShort));
            mAmbientLuxValid = true;
            if (mLoggingEnabled) {
                Slog.d(TAG, "updateAmbientLux: Initializing: " +
                        "mAmbientLightRingBuffer=" + mAmbientLightRingBuffer + ", " +
                        "mAmbientLux=" + mAmbientLux);
            }
            updateAutoBrightness(true /* sendUpdate */, false /* isManuallySet */);
        }

        long nextBrightenTransition = nextAmbientLightBrighteningTransition(time);
        long nextDarkenTransition = nextAmbientLightDarkeningTransition(time);
        // Essentially, we calculate both a slow ambient lux, to ensure there's a true long-term
        // change in lighting conditions, and a fast ambient lux to determine what the new
        // brightness situation is since the slow lux can be quite slow to converge.
        //
        // Note that both values need to be checked for sufficient change before updating the
        // proposed ambient light value since the slow value might be sufficiently far enough away
        // from the fast value to cause a recalculation while its actually just converging on
        // the fast value still.
        mSlowAmbientLux = calculateAmbientLux(time, mAmbientLightHorizonLong);
        mFastAmbientLux = calculateAmbientLux(time, mAmbientLightHorizonShort);

        if ((mSlowAmbientLux >= mAmbientBrighteningThreshold
                && mFastAmbientLux >= mAmbientBrighteningThreshold
                && nextBrightenTransition <= time)
                || (mSlowAmbientLux <= mAmbientDarkeningThreshold
                        && mFastAmbientLux <= mAmbientDarkeningThreshold
                        && nextDarkenTransition <= time)) {
            mPreThresholdLux = mAmbientLux;
            setAmbientLux(mFastAmbientLux);
            if (mLoggingEnabled) {
                Slog.d(TAG, "updateAmbientLux: "
                        + ((mFastAmbientLux > mPreThresholdLux) ? "Brightened" : "Darkened") + ": "
                        + "mAmbientBrighteningThreshold=" + mAmbientBrighteningThreshold + ", "
                        + "mAmbientDarkeningThreshold=" + mAmbientDarkeningThreshold + ", "
                        + "mAmbientLightRingBuffer=" + mAmbientLightRingBuffer + ", "
                        + "mAmbientLux=" + mAmbientLux);
            }
            updateAutoBrightness(true /* sendUpdate */, false /* isManuallySet */);
            nextBrightenTransition = nextAmbientLightBrighteningTransition(time);
            nextDarkenTransition = nextAmbientLightDarkeningTransition(time);
        }
        long nextTransitionTime = Math.min(nextDarkenTransition, nextBrightenTransition);
        // If one of the transitions is ready to occur, but the total weighted ambient lux doesn't
        // exceed the necessary threshold, then it's possible we'll get a transition time prior to
        // now. Rather than continually checking to see whether the weighted lux exceeds the
        // threshold, schedule an update for when we'd normally expect another light sample, which
        // should be enough time to decide whether we should actually transition to the new
        // weighted ambient lux or not.
        nextTransitionTime =
                nextTransitionTime > time ? nextTransitionTime : time + mNormalLightSensorRate;
        if (mLoggingEnabled) {
            Slog.d(TAG, "updateAmbientLux: Scheduling ambient lux update for " +
                    nextTransitionTime + TimeUtils.formatUptime(nextTransitionTime));
        }

        // The nextTransitionTime is computed as elapsedTime(Which also accounts for the time when
        // android was sleeping) as the main reference. However, handlers work on the uptime(Not
        // accounting for the time when android was sleeping)
        mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX,
                convertToUptime(nextTransitionTime));
    }

    private long convertToUptime(long time) {
        return time - mClock.getSensorEventScaleTime() + mClock.uptimeMillis();
    }

    private void updateAutoBrightness(boolean sendUpdate, boolean isManuallySet) {
        if (!mAmbientLuxValid) {
            return;
        }

        float value = mCurrentBrightnessMapper.getBrightness(mAmbientLux, mForegroundAppPackageName,
                mForegroundAppCategory);
        mRawScreenAutoBrightness = value;
        float newScreenAutoBrightness = clampScreenBrightness(value);

        // The min/max range can change for brightness due to HBM. See if the current brightness
        // value still falls within the current range (which could have changed).
        final boolean currentBrightnessWithinAllowedRange = BrightnessSynchronizer.floatEquals(
                mScreenAutoBrightness, clampScreenBrightness(mScreenAutoBrightness));
        // If screenAutoBrightness is set, we should have screen{Brightening,Darkening}Threshold,
        // in which case we ignore the new screen brightness if it doesn't differ enough from the
        // previous one.
        boolean withinThreshold = !Float.isNaN(mScreenAutoBrightness)
                && newScreenAutoBrightness > mScreenDarkeningThreshold
                && newScreenAutoBrightness < mScreenBrighteningThreshold;

        if (withinThreshold && !isManuallySet && currentBrightnessWithinAllowedRange) {
            if (mLoggingEnabled) {
                Slog.d(TAG, "ignoring newScreenAutoBrightness: "
                        + mScreenDarkeningThreshold + " < " + newScreenAutoBrightness
                        + " < " + mScreenBrighteningThreshold);
            }
            return;
        }
        if (!BrightnessSynchronizer.floatEquals(mScreenAutoBrightness,
                newScreenAutoBrightness)) {
            if (mLoggingEnabled) {
                Slog.d(TAG, "updateAutoBrightness: "
                        + "mScreenAutoBrightness=" + mScreenAutoBrightness + ", "
                        + "newScreenAutoBrightness=" + newScreenAutoBrightness);
            }
            if (!withinThreshold) {
                mPreThresholdBrightness = mScreenAutoBrightness;
            }
            mScreenAutoBrightness = newScreenAutoBrightness;
            if (isInIdleMode()) {
                mScreenBrighteningThreshold = clampScreenBrightness(
                        mScreenBrightnessThresholdsIdle.getBrighteningThreshold(
                                newScreenAutoBrightness));
                mScreenDarkeningThreshold = clampScreenBrightness(
                        mScreenBrightnessThresholdsIdle.getDarkeningThreshold(
                                newScreenAutoBrightness));
            } else {
                mScreenBrighteningThreshold = clampScreenBrightness(
                        mScreenBrightnessThresholds.getBrighteningThreshold(
                                newScreenAutoBrightness));
                mScreenDarkeningThreshold = clampScreenBrightness(
                        mScreenBrightnessThresholds.getDarkeningThreshold(newScreenAutoBrightness));
            }

            if (sendUpdate) {
                mCallbacks.updateBrightness();
            }
        }
    }

    // Clamps values with float range [0.0-1.0]
    private float clampScreenBrightness(float value) {
        final float minBrightness = Math.min(mBrightnessRangeController.getCurrentBrightnessMin(),
                mBrightnessThrottler.getBrightnessCap());
        final float maxBrightness = Math.min(mBrightnessRangeController.getCurrentBrightnessMax(),
                mBrightnessThrottler.getBrightnessCap());
        return MathUtils.constrain(value, minBrightness, maxBrightness);
    }

    private void prepareBrightnessAdjustmentSample() {
        if (!mBrightnessAdjustmentSamplePending) {
            mBrightnessAdjustmentSamplePending = true;
            mBrightnessAdjustmentSampleOldLux = mAmbientLuxValid ? mAmbientLux : -1;
            mBrightnessAdjustmentSampleOldBrightness = mScreenAutoBrightness;
        } else {
            mHandler.removeMessages(MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE);
        }

        mHandler.sendEmptyMessageDelayed(MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE,
                BRIGHTNESS_ADJUSTMENT_SAMPLE_DEBOUNCE_MILLIS);
    }

    private void cancelBrightnessAdjustmentSample() {
        if (mBrightnessAdjustmentSamplePending) {
            mBrightnessAdjustmentSamplePending = false;
            mHandler.removeMessages(MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE);
        }
    }

    private void collectBrightnessAdjustmentSample() {
        if (mBrightnessAdjustmentSamplePending) {
            mBrightnessAdjustmentSamplePending = false;
            if (mAmbientLuxValid && (mScreenAutoBrightness >= PowerManager.BRIGHTNESS_MIN
                    || mScreenAutoBrightness == PowerManager.BRIGHTNESS_OFF_FLOAT)) {
                if (mLoggingEnabled) {
                    Slog.d(TAG, "Auto-brightness adjustment changed by user: "
                            + "lux=" + mAmbientLux + ", "
                            + "brightness=" + mScreenAutoBrightness + ", "
                            + "ring=" + mAmbientLightRingBuffer);
                }

                EventLog.writeEvent(EventLogTags.AUTO_BRIGHTNESS_ADJ,
                        mBrightnessAdjustmentSampleOldLux,
                        mBrightnessAdjustmentSampleOldBrightness,
                        mAmbientLux,
                        mScreenAutoBrightness);
            }
        }
    }

    // Register a TaskStackListener to call back to us onTaskStackChanged, so we can update the
    // foreground app's package name and category and correct the brightness accordingly.
    private void registerForegroundAppUpdater() {
        try {
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
            // This will not get called until the foreground app changes for the first time, so
            // call it explicitly to get the current foreground app's info.
            updateForegroundApp();
        } catch (RemoteException e) {
            if (mLoggingEnabled) {
                Slog.e(TAG, "Failed to register foreground app updater: " + e);
            }
            // Nothing to do.
        }
    }

    private void unregisterForegroundAppUpdater() {
        try {
            mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            // Nothing to do.
        }
        mForegroundAppPackageName = null;
        mForegroundAppCategory = ApplicationInfo.CATEGORY_UNDEFINED;
    }

    // Set the foreground app's package name and category, so brightness can be corrected per app.
    private void updateForegroundApp() {
        if (mLoggingEnabled) {
            Slog.d(TAG, "Attempting to update foreground app");
        }
        // The ActivityTaskManager's lock tends to get contended, so this is done in a background
        // thread and applied via this thread's handler synchronously.
        mInjector.getBackgroundThreadHandler().post(new Runnable() {
            public void run() {
                try {
                    // The foreground app is the top activity of the focused tasks stack.
                    final RootTaskInfo info = mActivityTaskManager.getFocusedRootTaskInfo();
                    if (info == null || info.topActivity == null) {
                        return;
                    }
                    final String packageName = info.topActivity.getPackageName();
                    // If the app didn't change, there's nothing to do. Otherwise, we have to
                    // update the category and re-apply the brightness correction.
                    String currentForegroundAppPackageName = mForegroundAppPackageName;
                    if (currentForegroundAppPackageName != null
                            && currentForegroundAppPackageName.equals(packageName)) {
                        return;
                    }
                    mPendingForegroundAppPackageName = packageName;
                    mPendingForegroundAppCategory = ApplicationInfo.CATEGORY_UNDEFINED;
                    try {
                        ApplicationInfo app = mPackageManager.getApplicationInfo(packageName,
                                PackageManager.MATCH_ANY_USER);
                        mPendingForegroundAppCategory = app.category;
                    } catch (PackageManager.NameNotFoundException e) {
                        // Nothing to do
                    }
                    mHandler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP_SYNC);
                } catch (RemoteException e) {
                    // Nothing to do
                }
            }
        });
    }

    private void updateForegroundAppSync() {
        if (mLoggingEnabled) {
            Slog.d(TAG, "Updating foreground app: packageName=" + mPendingForegroundAppPackageName
                    + ", category=" + mPendingForegroundAppCategory);
        }
        mForegroundAppPackageName = mPendingForegroundAppPackageName;
        mPendingForegroundAppPackageName = null;
        mForegroundAppCategory = mPendingForegroundAppCategory;
        mPendingForegroundAppCategory = ApplicationInfo.CATEGORY_UNDEFINED;
        updateAutoBrightness(true /* sendUpdate */, false /* isManuallySet */);
    }

    private void switchModeAndShortTermModels(@AutomaticBrightnessMode int mode) {
        // Stash short term model
        ShortTermModel tempShortTermModel = new ShortTermModel();
        tempShortTermModel.set(mCurrentBrightnessMapper.getUserLux(),
                mCurrentBrightnessMapper.getUserBrightness(), /* valid= */ true);
        mHandler.removeMessages(MSG_INVALIDATE_PAUSED_SHORT_TERM_MODEL);
        // Send delayed timeout
        mHandler.sendEmptyMessageAtTime(MSG_INVALIDATE_PAUSED_SHORT_TERM_MODEL,
                mClock.uptimeMillis()
                        + mCurrentBrightnessMapper.getShortTermModelTimeout());

        Slog.i(TAG, "mPreviousShortTermModel: " + mPausedShortTermModel);
        // new brightness mapper
        mCurrentBrightnessMapper = mBrightnessMappingStrategyMap.get(mode);

        // if previous stm has been invalidated, and lux has drastically changed, just use
        // the new, reset stm.
        // if previous stm is still valid then revalidate it
        if (mPausedShortTermModel != null) {
            if (!mPausedShortTermModel.maybeReset(mAmbientLux)) {
                setScreenBrightnessByUser(mPausedShortTermModel.mAnchor,
                        mPausedShortTermModel.mBrightness);
            }
            mPausedShortTermModel.copyFrom(tempShortTermModel);
        }
    }

    /**
     * Responsible for switching the AutomaticBrightnessMode of the associated display. Also takes
     * care of resetting the short term model wherever required
     */
    public void switchMode(@AutomaticBrightnessMode int mode, boolean sendUpdate) {
        if (!mBrightnessMappingStrategyMap.contains(mode)) {
            return;
        }
        if (mCurrentBrightnessMapper.getMode() == mode) {
            return;
        }
        Slog.i(TAG, "Switching to mode " + autoBrightnessModeToString(mode));
        if (mode == AUTO_BRIGHTNESS_MODE_IDLE
                || mCurrentBrightnessMapper.getMode() == AUTO_BRIGHTNESS_MODE_IDLE) {
            switchModeAndShortTermModels(mode);
        } else {
            resetShortTermModel();
            mCurrentBrightnessMapper = mBrightnessMappingStrategyMap.get(mode);
        }
        if (sendUpdate) {
            update();
        } else {
            updateAutoBrightness(/* sendUpdate= */ false, /* isManuallySet= */ false);
        }
    }

    float getUserLux() {
        return mCurrentBrightnessMapper.getUserLux();
    }

    float getUserNits() {
        return convertToNits(mCurrentBrightnessMapper.getUserBrightness());
    }

    /**
     * Convert a brightness float scale value to a nit value. Adjustments, such as RBC, are not
     * applied. This is used when storing the brightness in nits for the default display and when
     * passing the brightness value to follower displays.
     *
     * @param brightness The float scale value
     * @return The nit value or {@link BrightnessMappingStrategy.INVALID_NITS} if no conversion is
     * possible.
     */
    public float convertToNits(float brightness) {
        return mCurrentBrightnessMapper.convertToNits(brightness);
    }

    /**
     * Convert a brightness float scale value to a nit value. Adjustments, such as RBC are applied.
     * This is used when sending the brightness value to
     * {@link com.android.server.display.BrightnessTracker}.
     *
     * @param brightness The float scale value
     * @return The nit value or {@link BrightnessMappingStrategy.INVALID_NITS} if no conversion is
     * possible.
     */
    public float convertToAdjustedNits(float brightness) {
        return mCurrentBrightnessMapper.convertToAdjustedNits(brightness);
    }

    /**
     * Convert a brightness nit value to a float scale value. It is assumed that the nit value
     * provided does not have adjustments, such as RBC, applied.
     *
     * @param nits The nit value
     * @return The float scale value or {@link PowerManager.BRIGHTNESS_INVALID_FLOAT} if no
     * conversion is possible.
     */
    public float getBrightnessFromNits(float nits) {
        return mCurrentBrightnessMapper.getBrightnessFromNits(nits);
    }

    public void recalculateSplines(boolean applyAdjustment, float[] adjustment) {
        mCurrentBrightnessMapper.recalculateSplines(applyAdjustment, adjustment);

        // If rbc is turned on, off or there is a change in strength, we want to reset the short
        // term model. Since the nits range at which brightness now operates has changed due to
        // RBC/strength change, any short term model based on the previous range should be
        // invalidated.
        resetShortTermModel();

        // When rbc is turned on, we want to accommodate this change in the short term model.
        if (applyAdjustment) {
            setScreenBrightnessByUser(getAutomaticScreenBrightness());
        }
    }

    private boolean shouldApplyDozeScaleFactor() {
        // We don't apply the doze scale factor if we have a designated brightness curve for doze.
        return (mDisplayManagerFlags.isNormalBrightnessForDozeParameterEnabled()
                ? (!mUseNormalBrightnessForDoze && mDisplayPolicy == POLICY_DOZE)
                        || Display.isDozeState(mDisplayState) : Display.isDozeState(mDisplayState))
                && getMode() != AUTO_BRIGHTNESS_MODE_DOZE;
    }

    private class ShortTermModel {
        // When the short term model is invalidated, we don't necessarily reset it (i.e. clear the
        // user's adjustment) immediately, but wait for a drastic enough change in the ambient
        // light.
        // The anchor determines what were the light levels when the user has set their preference,
        // and we use a relative threshold to determine when to revert to the OEM curve.
        private float mAnchor = INVALID_LUX;
        private float mBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        private boolean mIsValid = false;

        private void reset() {
            mAnchor = INVALID_LUX;
            mBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mIsValid = false;
        }

        private void invalidate() {
            mIsValid = false;
            if (mLoggingEnabled) {
                Slog.d(TAG, "ShortTermModel: invalidate user data");
            }
        }

        private void setUserBrightness(float lux, float brightness) {
            mAnchor = lux;
            mBrightness = brightness;
            mIsValid = true;
            if (mLoggingEnabled) {
                Slog.d(TAG, "ShortTermModel: anchor=" + mAnchor);
            }
        }

        private boolean maybeReset(float currentLux) {
            // If the short term model was invalidated and the change is drastic enough, reset it.
            // Otherwise, we revalidate it.
            if (!mIsValid && mAnchor != INVALID_LUX) {
                if (mCurrentBrightnessMapper.shouldResetShortTermModel(currentLux, mAnchor)) {
                    resetShortTermModel();
                } else {
                    mIsValid = true;
                }
                return mIsValid;
            }
            return false;
        }

        private void set(float anchor, float brightness, boolean valid) {
            mAnchor = anchor;
            mBrightness = brightness;
            mIsValid = valid;
        }
        private void copyFrom(ShortTermModel from) {
            mAnchor = from.mAnchor;
            mBrightness = from.mBrightness;
            mIsValid = from.mIsValid;
        }

        public String toString() {
            return "mAnchor: " + mAnchor
                    + "\n mBrightness: " + mBrightness
                    + "\n mIsValid: " + mIsValid;
        }

        void dump(IndentingPrintWriter ipw) {
            ipw.increaseIndent();
            ipw.println(this);
            ipw.decreaseIndent();
        }

    }

    private final class AutomaticBrightnessHandler extends Handler {
        public AutomaticBrightnessHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RUN_UPDATE:
                    updateAutoBrightness(true /*sendUpdate*/, false /*isManuallySet*/);
                    break;

                case MSG_UPDATE_AMBIENT_LUX:
                    updateAmbientLux();
                    break;

                case MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE:
                    collectBrightnessAdjustmentSample();
                    break;

                case MSG_INVALIDATE_CURRENT_SHORT_TERM_MODEL:
                    mShortTermModel.invalidate();
                    break;

                case MSG_UPDATE_FOREGROUND_APP:
                    updateForegroundApp();
                    break;

                case MSG_UPDATE_FOREGROUND_APP_SYNC:
                    updateForegroundAppSync();
                    break;

                case MSG_INVALIDATE_PAUSED_SHORT_TERM_MODEL:
                    mPausedShortTermModel.invalidate();
                    break;
            }
        }
    }

    private final SensorEventListener mLightSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mLightSensorEnabled) {
                // The time received from the sensor is in nano seconds, hence changing it to ms
                final long time = (mDisplayManagerFlags.offloadControlsDozeAutoBrightness())
                        ? TimeUnit.NANOSECONDS.toMillis(event.timestamp) : mClock.uptimeMillis();
                final float lux = event.values[0];
                handleLightSensorEvent(time, lux);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    // Call back whenever the tasks stack changes, which includes tasks being created, removed, and
    // moving to top.
    class TaskStackListenerImpl extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP);
        }
    }

    /** Callbacks to request updates to the display's power state. */
    interface Callbacks {
        void updateBrightness();
    }

    /** Functional interface for providing time. */
    @VisibleForTesting
    interface Clock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();

        /**
         * Gets the time on either the elapsedTime or the uptime scale, depending on how we
         * processing the events from the sensor
         */
        long getSensorEventScaleTime();
    }

    /**
     * A ring buffer of ambient light measurements sorted by time.
     *
     * Each entry consists of a timestamp and a lux measurement, and the overall buffer is sorted
     * from oldest to newest.
     */
    private static final class AmbientLightRingBuffer {
        // Proportional extra capacity of the buffer beyond the expected number of light samples
        // in the horizon
        private static final float BUFFER_SLACK = 1.5f;
        private float[] mRingLux;
        private long[] mRingTime;
        private int mCapacity;

        // The first valid element and the next open slot.
        // Note that if mCount is zero then there are no valid elements.
        private int mStart;
        private int mEnd;
        private int mCount;
        Clock mClock;

        public AmbientLightRingBuffer(long lightSensorRate, int ambientLightHorizon, Clock clock) {
            if (lightSensorRate <= 0) {
                throw new IllegalArgumentException("lightSensorRate must be above 0");
            }
            mCapacity = (int) Math.ceil(ambientLightHorizon * BUFFER_SLACK / lightSensorRate);
            mRingLux = new float[mCapacity];
            mRingTime = new long[mCapacity];
            mClock = clock;
        }

        public float getLux(int index) {
            return mRingLux[offsetOf(index)];
        }

        public float[] getAllLuxValues() {
            float[] values = new float[mCount];
            if (mCount == 0) {
                return values;
            }

            if (mStart < mEnd) {
                System.arraycopy(mRingLux, mStart, values, 0, mCount);
            } else {
                System.arraycopy(mRingLux, mStart, values, 0, mCapacity - mStart);
                System.arraycopy(mRingLux, 0, values, mCapacity - mStart, mEnd);
            }

            return values;
        }

        public long getTime(int index) {
            return mRingTime[offsetOf(index)];
        }

        public long[] getAllTimestamps() {
            long[] values = new long[mCount];
            if (mCount == 0) {
                return values;
            }

            if (mStart < mEnd) {
                System.arraycopy(mRingTime, mStart, values, 0, mCount);
            } else {
                System.arraycopy(mRingTime, mStart, values, 0, mCapacity - mStart);
                System.arraycopy(mRingTime, 0, values, mCapacity - mStart, mEnd);
            }

            return values;
        }

        public void push(long time, float lux) {
            int next = mEnd;
            if (mCount == mCapacity) {
                int newSize = mCapacity * 2;

                float[] newRingLux = new float[newSize];
                long[] newRingTime = new long[newSize];
                int length = mCapacity - mStart;
                System.arraycopy(mRingLux, mStart, newRingLux, 0, length);
                System.arraycopy(mRingTime, mStart, newRingTime, 0, length);
                if (mStart != 0) {
                    System.arraycopy(mRingLux, 0, newRingLux, length, mStart);
                    System.arraycopy(mRingTime, 0, newRingTime, length, mStart);
                }
                mRingLux = newRingLux;
                mRingTime = newRingTime;

                next = mCapacity;
                mCapacity = newSize;
                mStart = 0;
            }
            mRingTime[next] = time;
            mRingLux[next] = lux;
            mEnd = next + 1;
            if (mEnd == mCapacity) {
                mEnd = 0;
            }
            mCount++;
        }

        public void prune(long horizon) {
            if (mCount == 0) {
                return;
            }

            while (mCount > 1) {
                int next = mStart + 1;
                if (next >= mCapacity) {
                    next -= mCapacity;
                }
                if (mRingTime[next] > horizon) {
                    // Some light sensors only produce data upon a change in the ambient light
                    // levels, so we need to consider the previous measurement as the ambient light
                    // level for all points in time up until we receive a new measurement. Thus, we
                    // always want to keep the youngest element that would be removed from the
                    // buffer and just set its measurement time to the horizon time since at that
                    // point it is the ambient light level, and to remove it would be to drop a
                    // valid data point within our horizon.
                    break;
                }
                mStart = next;
                mCount -= 1;
            }

            if (mRingTime[mStart] < horizon) {
                mRingTime[mStart] = horizon;
            }
        }

        public int size() {
            return mCount;
        }

        public void clear() {
            mStart = 0;
            mEnd = 0;
            mCount = 0;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder();
            buf.append('[');
            for (int i = 0; i < mCount; i++) {
                final long next = i + 1 < mCount ? getTime(i + 1)
                        : mClock.getSensorEventScaleTime();
                if (i != 0) {
                    buf.append(", ");
                }
                buf.append(getLux(i));
                buf.append(" / ");
                buf.append(next - getTime(i));
                buf.append("ms");
            }
            buf.append(']');
            return buf.toString();
        }

        private int offsetOf(int index) {
            if (index >= mCount || index < 0) {
                throw new ArrayIndexOutOfBoundsException(index);
            }
            index += mStart;
            if (index >= mCapacity) {
                index -= mCapacity;
            }
            return index;
        }
    }

    private static class RealClock implements Clock {
        private final boolean mOffloadControlsDozeBrightness;

        RealClock(boolean offloadControlsDozeBrightness) {
            mOffloadControlsDozeBrightness = offloadControlsDozeBrightness;
        }

        @Override
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }

        public long getSensorEventScaleTime() {
            return (mOffloadControlsDozeBrightness)
                    ? SystemClock.elapsedRealtime() : uptimeMillis();
        }
    }

    public static class Injector {
        public Handler getBackgroundThreadHandler() {
            return BackgroundThread.getHandler();
        }

        Clock createClock(boolean offloadControlsDozeBrightness) {
            return new RealClock(offloadControlsDozeBrightness);
        }
    }
}
