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
import android.util.MathUtils;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.os.BackgroundThread;
import com.android.server.EventLogTags;
import com.android.server.display.DisplayPowerController.BrightnessEvent;

import java.io.PrintWriter;

class AutomaticBrightnessController {
    private static final String TAG = "AutomaticBrightnessController";

    private static final boolean DEBUG_PRETEND_LIGHT_SENSOR_ABSENT = false;

    public static final int AUTO_BRIGHTNESS_ENABLED = 1;
    public static final int AUTO_BRIGHTNESS_DISABLED = 2;
    public static final int AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE = 3;

    // How long the current sensor reading is assumed to be valid beyond the current time.
    // This provides a bit of prediction, as well as ensures that the weight for the last sample is
    // non-zero, which in turn ensures that the total weight is non-zero.
    private static final long AMBIENT_LIGHT_PREDICTION_TIME_MILLIS = 100;

    // Debounce for sampling user-initiated changes in display brightness to ensure
    // the user is satisfied with the result before storing the sample.
    private static final int BRIGHTNESS_ADJUSTMENT_SAMPLE_DEBOUNCE_MILLIS = 10000;

    private static final int MSG_UPDATE_AMBIENT_LUX = 1;
    private static final int MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE = 2;
    private static final int MSG_INVALIDATE_SHORT_TERM_MODEL = 3;
    private static final int MSG_UPDATE_FOREGROUND_APP = 4;
    private static final int MSG_UPDATE_FOREGROUND_APP_SYNC = 5;
    private static final int MSG_RUN_UPDATE = 6;

    // Callbacks for requesting updates to the display's power state
    private final Callbacks mCallbacks;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The light sensor, or null if not available or needed.
    private final Sensor mLightSensor;

    // The mapper to translate ambient lux to screen brightness in the range [0, 1.0].
    @Nullable
    private BrightnessMappingStrategy mCurrentBrightnessMapper;
    private final BrightnessMappingStrategy mInteractiveModeBrightnessMapper;
    private final BrightnessMappingStrategy mIdleModeBrightnessMapper;

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
    private float mAmbientLux;

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

    // The current display policy. This is useful, for example,  for knowing when we're dozing,
    // where the light sensor may not be available.
    private int mDisplayPolicy = DisplayPowerRequest.POLICY_OFF;

    // True if we are collecting a brightness adjustment sample, along with some data
    // for the initial state of the sample.
    private boolean mBrightnessAdjustmentSamplePending;
    private float mBrightnessAdjustmentSampleOldLux;
    private float mBrightnessAdjustmentSampleOldBrightness;

    // When the short term model is invalidated, we don't necessarily reset it (i.e. clear the
    // user's adjustment) immediately, but wait for a drastic enough change in the ambient light.
    // The anchor determines what were the light levels when the user has set their preference, and
    // we use a relative threshold to determine when to revert to the OEM curve.
    private boolean mShortTermModelValid;
    private float mShortTermModelAnchor;

    // Controls High Brightness Mode.
    private HighBrightnessModeController mHbmController;

    // Throttles (caps) maximum allowed brightness
    private BrightnessThrottler mBrightnessThrottler;
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

    AutomaticBrightnessController(Callbacks callbacks, Looper looper,
            SensorManager sensorManager, Sensor lightSensor,
            BrightnessMappingStrategy interactiveModeBrightnessMapper,
            int lightSensorWarmUpTime, float brightnessMin, float brightnessMax,
            float dozeScaleFactor, int lightSensorRate, int initialLightSensorRate,
            long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
            boolean resetAmbientLuxAfterWarmUpConfig, HysteresisLevels ambientBrightnessThresholds,
            HysteresisLevels screenBrightnessThresholds, Context context,
            HighBrightnessModeController hbmController, BrightnessThrottler brightnessThrottler,
            BrightnessMappingStrategy idleModeBrightnessMapper, int ambientLightHorizonShort,
            int ambientLightHorizonLong) {
        this(new Injector(), callbacks, looper, sensorManager, lightSensor,
                interactiveModeBrightnessMapper,
                lightSensorWarmUpTime, brightnessMin, brightnessMax, dozeScaleFactor,
                lightSensorRate, initialLightSensorRate, brighteningLightDebounceConfig,
                darkeningLightDebounceConfig, resetAmbientLuxAfterWarmUpConfig,
                ambientBrightnessThresholds, screenBrightnessThresholds, context,
                hbmController, brightnessThrottler, idleModeBrightnessMapper,
                ambientLightHorizonShort, ambientLightHorizonLong
        );
    }

    @VisibleForTesting
    AutomaticBrightnessController(Injector injector, Callbacks callbacks, Looper looper,
            SensorManager sensorManager, Sensor lightSensor,
            BrightnessMappingStrategy interactiveModeBrightnessMapper,
            int lightSensorWarmUpTime, float brightnessMin, float brightnessMax,
            float dozeScaleFactor, int lightSensorRate, int initialLightSensorRate,
            long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
            boolean resetAmbientLuxAfterWarmUpConfig, HysteresisLevels ambientBrightnessThresholds,
            HysteresisLevels screenBrightnessThresholds, Context context,
            HighBrightnessModeController hbmController, BrightnessThrottler brightnessThrottler,
            BrightnessMappingStrategy idleModeBrightnessMapper, int ambientLightHorizonShort,
            int ambientLightHorizonLong) {
        mInjector = injector;
        mClock = injector.createClock();
        mContext = context;
        mCallbacks = callbacks;
        mSensorManager = sensorManager;
        mCurrentBrightnessMapper = interactiveModeBrightnessMapper;
        mScreenBrightnessRangeMinimum = brightnessMin;
        mScreenBrightnessRangeMaximum = brightnessMax;
        mLightSensorWarmUpTimeConfig = lightSensorWarmUpTime;
        mDozeScaleFactor = dozeScaleFactor;
        mNormalLightSensorRate = lightSensorRate;
        mInitialLightSensorRate = initialLightSensorRate;
        mCurrentLightSensorRate = -1;
        mBrighteningLightDebounceConfig = brighteningLightDebounceConfig;
        mDarkeningLightDebounceConfig = darkeningLightDebounceConfig;
        mResetAmbientLuxAfterWarmUpConfig = resetAmbientLuxAfterWarmUpConfig;
        mAmbientLightHorizonLong = ambientLightHorizonLong;
        mAmbientLightHorizonShort = ambientLightHorizonShort;
        mWeightingIntercept = ambientLightHorizonLong;
        mAmbientBrightnessThresholds = ambientBrightnessThresholds;
        mScreenBrightnessThresholds = screenBrightnessThresholds;
        mShortTermModelValid = true;
        mShortTermModelAnchor = -1;
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
        mHbmController = hbmController;
        mBrightnessThrottler = brightnessThrottler;
        mInteractiveModeBrightnessMapper = interactiveModeBrightnessMapper;
        mIdleModeBrightnessMapper = idleModeBrightnessMapper;
        // Initialize to active (normal) screen brightness mode
        switchToInteractiveScreenBrightnessMode();
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
        if (mInteractiveModeBrightnessMapper != null) {
            mInteractiveModeBrightnessMapper.setLoggingEnabled(loggingEnabled);
        }
        if (mIdleModeBrightnessMapper != null) {
            mIdleModeBrightnessMapper.setLoggingEnabled(loggingEnabled);
        }
        mLoggingEnabled = loggingEnabled;
        return true;
    }

    public float getAutomaticScreenBrightness() {
        return getAutomaticScreenBrightness(null);
    }

    float getAutomaticScreenBrightness(BrightnessEvent brightnessEvent) {
        if (brightnessEvent != null) {
            brightnessEvent.lux =
                    mAmbientLuxValid ? mAmbientLux : PowerManager.BRIGHTNESS_INVALID_FLOAT;
            brightnessEvent.preThresholdLux = mPreThresholdLux;
            brightnessEvent.preThresholdBrightness = mPreThresholdBrightness;
            brightnessEvent.recommendedBrightness = mScreenAutoBrightness;
            brightnessEvent.flags |= (!mAmbientLuxValid ? BrightnessEvent.FLAG_INVALID_LUX : 0)
                    | (mDisplayPolicy == DisplayPowerRequest.POLICY_DOZE
                        ? BrightnessEvent.FLAG_DOZE_SCALE : 0);
        }

        if (!mAmbientLuxValid) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }
        if (mDisplayPolicy == DisplayPowerRequest.POLICY_DOZE) {
            return mScreenAutoBrightness * mDozeScaleFactor;
        }
        return mScreenAutoBrightness;
    }

    public boolean hasValidAmbientLux() {
        return mAmbientLuxValid;
    }

    public float getAutomaticScreenBrightnessAdjustment() {
        return mCurrentBrightnessMapper.getAutoBrightnessAdjustment();
    }

    public void configure(int state, @Nullable BrightnessConfiguration configuration,
            float brightness, boolean userChangedBrightness, float adjustment,
            boolean userChangedAutoBrightnessAdjustment, int displayPolicy) {
        mState = state;
        mHbmController.setAutoBrightnessEnabled(mState);
        // While dozing, the application processor may be suspended which will prevent us from
        // receiving new information from the light sensor. On some devices, we may be able to
        // switch to a wake-up light sensor instead but for now we will simply disable the sensor
        // and hold onto the last computed screen auto brightness.  We save the dozing flag for
        // debugging purposes.
        boolean dozing = (displayPolicy == DisplayPowerRequest.POLICY_DOZE);
        boolean changed = setBrightnessConfiguration(configuration);
        changed |= setDisplayPolicy(displayPolicy);
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
        if (userInitiatedChange && enable && !dozing) {
            prepareBrightnessAdjustmentSample();
        }
        changed |= setLightSensorEnabled(enable && !dozing);

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
        if (isInIdleMode()) {
            return false;
        }
        return mInteractiveModeBrightnessMapper.isDefaultConfig();
    }

    // Called from APIs to get the configuration.
    public BrightnessConfiguration getDefaultConfig() {
        return mInteractiveModeBrightnessMapper.getDefaultConfig();
    }

    /**
     * Force recalculate of the state of automatic brightness.
     */
    public void update() {
        mHandler.sendEmptyMessage(MSG_RUN_UPDATE);
    }

    @VisibleForTesting
    float getAmbientLux() {
        return mAmbientLux;
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
            mHandler.sendEmptyMessageDelayed(MSG_INVALIDATE_SHORT_TERM_MODEL,
                    mCurrentBrightnessMapper.getShortTermModelTimeout());
        } else if (isInteractivePolicy(policy) && !isInteractivePolicy(oldPolicy)) {
            mHandler.removeMessages(MSG_INVALIDATE_SHORT_TERM_MODEL);
        }
        return true;
    }

    private static boolean isInteractivePolicy(int policy) {
        return policy == DisplayPowerRequest.POLICY_BRIGHT
                || policy == DisplayPowerRequest.POLICY_DIM
                || policy == DisplayPowerRequest.POLICY_VR;
    }

    private boolean setScreenBrightnessByUser(float brightness) {
        if (!mAmbientLuxValid) {
            // If we don't have a valid ambient lux then we don't have a valid brightness anyway,
            // and we can't use this data to add a new control point to the short-term model.
            return false;
        }
        mCurrentBrightnessMapper.addUserDataPoint(mAmbientLux, brightness);
        mShortTermModelValid = true;
        mShortTermModelAnchor = mAmbientLux;
        if (mLoggingEnabled) {
            Slog.d(TAG, "ShortTermModel: anchor=" + mShortTermModelAnchor);
        }
        return true;
    }

    public void resetShortTermModel() {
        mCurrentBrightnessMapper.clearUserDataPoints();
        mShortTermModelValid = true;
        mShortTermModelAnchor = -1;
    }

    private void invalidateShortTermModel() {
        if (mLoggingEnabled) {
            Slog.d(TAG, "ShortTermModel: invalidate user data");
        }
        mShortTermModelValid = false;
    }

    public boolean setBrightnessConfiguration(BrightnessConfiguration configuration) {
        if (mInteractiveModeBrightnessMapper.setBrightnessConfiguration(configuration)) {
            if (!isInIdleMode()) {
                resetShortTermModel();
            }
            return true;
        }
        return false;
    }

    public boolean isInIdleMode() {
        return mCurrentBrightnessMapper.isForIdleMode();
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Automatic Brightness Controller Configuration:");
        pw.println("  mState=" + configStateToString(mState));
        pw.println("  mScreenBrightnessRangeMinimum=" + mScreenBrightnessRangeMinimum);
        pw.println("  mScreenBrightnessRangeMaximum=" + mScreenBrightnessRangeMaximum);
        pw.println("  mDozeScaleFactor=" + mDozeScaleFactor);
        pw.println("  mInitialLightSensorRate=" + mInitialLightSensorRate);
        pw.println("  mNormalLightSensorRate=" + mNormalLightSensorRate);
        pw.println("  mLightSensorWarmUpTimeConfig=" + mLightSensorWarmUpTimeConfig);
        pw.println("  mBrighteningLightDebounceConfig=" + mBrighteningLightDebounceConfig);
        pw.println("  mDarkeningLightDebounceConfig=" + mDarkeningLightDebounceConfig);
        pw.println("  mResetAmbientLuxAfterWarmUpConfig=" + mResetAmbientLuxAfterWarmUpConfig);
        pw.println("  mAmbientLightHorizonLong=" + mAmbientLightHorizonLong);
        pw.println("  mAmbientLightHorizonShort=" + mAmbientLightHorizonShort);
        pw.println("  mWeightingIntercept=" + mWeightingIntercept);

        pw.println();
        pw.println("Automatic Brightness Controller State:");
        pw.println("  mLightSensor=" + mLightSensor);
        pw.println("  mLightSensorEnabled=" + mLightSensorEnabled);
        pw.println("  mLightSensorEnableTime=" + TimeUtils.formatUptime(mLightSensorEnableTime));
        pw.println("  mCurrentLightSensorRate=" + mCurrentLightSensorRate);
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mAmbientLuxValid=" + mAmbientLuxValid);
        pw.println("  mPreThesholdLux=" + mPreThresholdLux);
        pw.println("  mPreThesholdBrightness=" + mPreThresholdBrightness);
        pw.println("  mAmbientBrighteningThreshold=" + mAmbientBrighteningThreshold);
        pw.println("  mAmbientDarkeningThreshold=" + mAmbientDarkeningThreshold);
        pw.println("  mScreenBrighteningThreshold=" + mScreenBrighteningThreshold);
        pw.println("  mScreenDarkeningThreshold=" + mScreenDarkeningThreshold);
        pw.println("  mLastObservedLux=" + mLastObservedLux);
        pw.println("  mLastObservedLuxTime=" + TimeUtils.formatUptime(mLastObservedLuxTime));
        pw.println("  mRecentLightSamples=" + mRecentLightSamples);
        pw.println("  mAmbientLightRingBuffer=" + mAmbientLightRingBuffer);
        pw.println("  mScreenAutoBrightness=" + mScreenAutoBrightness);
        pw.println("  mDisplayPolicy=" + DisplayPowerRequest.policyToString(mDisplayPolicy));
        pw.println("  mShortTermModelTimeout(active)="
                + mInteractiveModeBrightnessMapper.getShortTermModelTimeout());
        if (mIdleModeBrightnessMapper != null) {
            pw.println("  mShortTermModelTimeout(idle)="
                    + mIdleModeBrightnessMapper.getShortTermModelTimeout());
        }
        pw.println("  mShortTermModelAnchor=" + mShortTermModelAnchor);
        pw.println("  mShortTermModelValid=" + mShortTermModelValid);
        pw.println("  mBrightnessAdjustmentSamplePending=" + mBrightnessAdjustmentSamplePending);
        pw.println("  mBrightnessAdjustmentSampleOldLux=" + mBrightnessAdjustmentSampleOldLux);
        pw.println("  mBrightnessAdjustmentSampleOldBrightness="
                + mBrightnessAdjustmentSampleOldBrightness);
        pw.println("  mForegroundAppPackageName=" + mForegroundAppPackageName);
        pw.println("  mPendingForegroundAppPackageName=" + mPendingForegroundAppPackageName);
        pw.println("  mForegroundAppCategory=" + mForegroundAppCategory);
        pw.println("  mPendingForegroundAppCategory=" + mPendingForegroundAppCategory);
        pw.println("  Idle mode active=" + mCurrentBrightnessMapper.isForIdleMode());

        pw.println();
        pw.println("  mInteractiveMapper=");
        mInteractiveModeBrightnessMapper.dump(pw, mHbmController.getNormalBrightnessMax());
        if (mIdleModeBrightnessMapper != null) {
            pw.println("  mIdleMapper=");
            mIdleModeBrightnessMapper.dump(pw, mHbmController.getNormalBrightnessMax());
        }

        pw.println();
        mAmbientBrightnessThresholds.dump(pw);
        mScreenBrightnessThresholds.dump(pw);
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
        mAmbientBrighteningThreshold = mAmbientBrightnessThresholds.getBrighteningThreshold(lux);
        mAmbientDarkeningThreshold = mAmbientBrightnessThresholds.getDarkeningThreshold(lux);
        mHbmController.onAmbientLuxChange(mAmbientLux);

        // If the short term model was invalidated and the change is drastic enough, reset it.
        if (!mShortTermModelValid && mShortTermModelAnchor != -1) {
            if (mCurrentBrightnessMapper.shouldResetShortTermModel(
                    mAmbientLux, mShortTermModelAnchor)) {
                resetShortTermModel();
            } else {
                mShortTermModelValid = true;
            }
        }
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
        return earliestValidTime + mBrighteningLightDebounceConfig;
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
        return earliestValidTime + mDarkeningLightDebounceConfig;
    }

    private void updateAmbientLux() {
        long time = mClock.uptimeMillis();
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
        float slowAmbientLux = calculateAmbientLux(time, mAmbientLightHorizonLong);
        float fastAmbientLux = calculateAmbientLux(time, mAmbientLightHorizonShort);

        if ((slowAmbientLux >= mAmbientBrighteningThreshold
                && fastAmbientLux >= mAmbientBrighteningThreshold
                && nextBrightenTransition <= time)
                || (slowAmbientLux <= mAmbientDarkeningThreshold
                        && fastAmbientLux <= mAmbientDarkeningThreshold
                        && nextDarkenTransition <= time)) {
            mPreThresholdLux = mAmbientLux;
            setAmbientLux(fastAmbientLux);
            if (mLoggingEnabled) {
                Slog.d(TAG, "updateAmbientLux: "
                        + ((fastAmbientLux > mAmbientLux) ? "Brightened" : "Darkened") + ": "
                        + "mBrighteningLuxThreshold=" + mAmbientBrighteningThreshold + ", "
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
        mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX, nextTransitionTime);
    }

    private void updateAutoBrightness(boolean sendUpdate, boolean isManuallySet) {
        if (!mAmbientLuxValid) {
            return;
        }

        float value = mCurrentBrightnessMapper.getBrightness(mAmbientLux, mForegroundAppPackageName,
                mForegroundAppCategory);
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
            mScreenBrighteningThreshold = clampScreenBrightness(
                    mScreenBrightnessThresholds.getBrighteningThreshold(newScreenAutoBrightness));
            mScreenDarkeningThreshold = clampScreenBrightness(
                    mScreenBrightnessThresholds.getDarkeningThreshold(newScreenAutoBrightness));

            if (sendUpdate) {
                mCallbacks.updateBrightness();
            }
        }
    }

    // Clamps values with float range [0.0-1.0]
    private float clampScreenBrightness(float value) {
        final float minBrightness = Math.min(mHbmController.getCurrentBrightnessMin(),
                mBrightnessThrottler.getBrightnessCap());
        final float maxBrightness = Math.min(mHbmController.getCurrentBrightnessMax(),
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
                    if (mForegroundAppPackageName != null
                            && mForegroundAppPackageName.equals(packageName)) {
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

    void switchToIdleMode() {
        if (mIdleModeBrightnessMapper == null) {
            return;
        }
        if (mCurrentBrightnessMapper.isForIdleMode()) {
            return;
        }
        Slog.i(TAG, "Switching to Idle Screen Brightness Mode");
        mCurrentBrightnessMapper = mIdleModeBrightnessMapper;
        resetShortTermModel();
        update();
    }

    void switchToInteractiveScreenBrightnessMode() {
        if (!mCurrentBrightnessMapper.isForIdleMode()) {
            return;
        }
        Slog.i(TAG, "Switching to Interactive Screen Brightness Mode");
        mCurrentBrightnessMapper = mInteractiveModeBrightnessMapper;
        resetShortTermModel();
        update();
    }

    public float convertToNits(float brightness) {
        if (mCurrentBrightnessMapper != null) {
            return mCurrentBrightnessMapper.convertToNits(brightness);
        } else {
            return -1.0f;
        }
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

                case MSG_INVALIDATE_SHORT_TERM_MODEL:
                    invalidateShortTermModel();
                    break;

                case MSG_UPDATE_FOREGROUND_APP:
                    updateForegroundApp();
                    break;

                case MSG_UPDATE_FOREGROUND_APP_SYNC:
                    updateForegroundAppSync();
                    break;
            }
        }
    }

    private final SensorEventListener mLightSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mLightSensorEnabled) {
                final long time = mClock.uptimeMillis();
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

        public long getTime(int index) {
            return mRingTime[offsetOf(index)];
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
                final long next = i + 1 < mCount ? getTime(i + 1) : mClock.uptimeMillis();
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

    public static class Injector {
        public Handler getBackgroundThreadHandler() {
            return BackgroundThread.getHandler();
        }

        Clock createClock() {
            return SystemClock::uptimeMillis;
        }
    }
}
