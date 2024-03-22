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
import android.hardware.SensorManager;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.EventLog;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.Clock;
import com.android.server.EventLogTags;
import com.android.server.display.brightness.BrightnessEvent;
import com.android.server.display.brightness.LightSensorController;
import com.android.server.display.brightness.clamper.BrightnessClamperController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Manages the associated display brightness when in auto-brightness mode. This is also
 * responsible for managing the brightness lux-nits mapping strategies. Internally also listens to
 * the LightSensor and adjusts the system brightness in case of changes in the surrounding lux.
 */
public class AutomaticBrightnessController {
    private static final String TAG = "AutomaticBrightnessController";

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

    // Debounce for sampling user-initiated changes in display brightness to ensure
    // the user is satisfied with the result before storing the sample.
    private static final int BRIGHTNESS_ADJUSTMENT_SAMPLE_DEBOUNCE_MILLIS = 10000;

    private static final int MSG_BRIGHTNESS_ADJUSTMENT_SAMPLE = 1;
    private static final int MSG_INVALIDATE_CURRENT_SHORT_TERM_MODEL = 2;
    private static final int MSG_UPDATE_FOREGROUND_APP = 3;
    private static final int MSG_UPDATE_FOREGROUND_APP_SYNC = 4;
    private static final int MSG_RUN_UPDATE = 5;
    private static final int MSG_INVALIDATE_PAUSED_SHORT_TERM_MODEL = 6;

    // Callbacks for requesting updates to the display's power state
    private final Callbacks mCallbacks;

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

    // Configuration object for determining thresholds to change brightness dynamically
    private final HysteresisLevels mScreenBrightnessThresholds;
    private final HysteresisLevels mScreenBrightnessThresholdsIdle;

    private boolean mLoggingEnabled;
    // The currently accepted nominal ambient light level.
    private float mAmbientLux;
    // True if mAmbientLux holds a valid value.
    private boolean mAmbientLuxValid;
    // The last brightness value prior to passing the darkening or brightening threshold.
    private float mPreThresholdBrightness;

    // The screen brightness threshold at which to brighten or darken the screen.
    private float mScreenBrighteningThreshold;
    private float mScreenDarkeningThreshold;

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
    private final BrightnessClamperController mBrightnessClamperController;

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

    private final Clock mClock;
    private final Injector mInjector;

    private final LightSensorController mLightSensorController;

    AutomaticBrightnessController(Callbacks callbacks, Looper looper, SensorManager sensorManager,
            SparseArray<BrightnessMappingStrategy> brightnessMappingStrategyMap,
            float brightnessMin, float brightnessMax, float dozeScaleFactor,
            HysteresisLevels screenBrightnessThresholds,
            HysteresisLevels screenBrightnessThresholdsIdle, Context context,
            BrightnessRangeController brightnessModeController,
            BrightnessThrottler brightnessThrottler, float userLux, float userNits,
            int displayId, LightSensorController.LightSensorControllerConfig config,
            BrightnessClamperController brightnessClamperController) {
        this(new Injector(), callbacks, looper,
                brightnessMappingStrategyMap, brightnessMin, brightnessMax, dozeScaleFactor,
                screenBrightnessThresholds,
                screenBrightnessThresholdsIdle, context, brightnessModeController,
                brightnessThrottler, userLux, userNits,
                new LightSensorController(sensorManager, looper, displayId, config),
                brightnessClamperController
        );
    }

    @VisibleForTesting
    AutomaticBrightnessController(Injector injector, Callbacks callbacks, Looper looper,
            SparseArray<BrightnessMappingStrategy> brightnessMappingStrategyMap,
            float brightnessMin, float brightnessMax, float dozeScaleFactor,
            HysteresisLevels screenBrightnessThresholds,
            HysteresisLevels screenBrightnessThresholdsIdle, Context context,
            BrightnessRangeController brightnessRangeController,
            BrightnessThrottler brightnessThrottler, float userLux, float userNits,
            LightSensorController lightSensorController,
            BrightnessClamperController brightnessClamperController) {
        mInjector = injector;
        mClock = injector.createClock();
        mContext = context;
        mCallbacks = callbacks;
        mCurrentBrightnessMapper = brightnessMappingStrategyMap.get(AUTO_BRIGHTNESS_MODE_DEFAULT);
        mScreenBrightnessRangeMinimum = brightnessMin;
        mScreenBrightnessRangeMaximum = brightnessMax;
        mDozeScaleFactor = dozeScaleFactor;
        mScreenBrightnessThresholds = screenBrightnessThresholds;
        mScreenBrightnessThresholdsIdle = screenBrightnessThresholdsIdle;
        mShortTermModel = new ShortTermModel();
        mPausedShortTermModel = new ShortTermModel();
        mHandler = new AutomaticBrightnessHandler(looper);
        mLightSensorController = lightSensorController;
        mLightSensorController.setListener(this::setAmbientLux);
        mActivityTaskManager = ActivityTaskManager.getService();
        mPackageManager = mContext.getPackageManager();
        mTaskStackListener = new TaskStackListenerImpl();
        mForegroundAppPackageName = null;
        mPendingForegroundAppPackageName = null;
        mForegroundAppCategory = ApplicationInfo.CATEGORY_UNDEFINED;
        mPendingForegroundAppCategory = ApplicationInfo.CATEGORY_UNDEFINED;
        mBrightnessRangeController = brightnessRangeController;
        mBrightnessClamperController = brightnessClamperController;
        mBrightnessThrottler = brightnessThrottler;
        mBrightnessMappingStrategyMap = brightnessMappingStrategyMap;

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
            brightnessEvent.setPreThresholdBrightness(mPreThresholdBrightness);
            brightnessEvent.setRecommendedBrightness(mScreenAutoBrightness);
            brightnessEvent.setFlags(brightnessEvent.getFlags()
                    | (!mAmbientLuxValid ? BrightnessEvent.FLAG_INVALID_LUX : 0)
                    | (shouldApplyDozeScaleFactor() ? BrightnessEvent.FLAG_DOZE_SCALE : 0));
            brightnessEvent.setAutoBrightnessMode(getMode());
            mLightSensorController.updateBrightnessEvent(brightnessEvent);
        }

        if (!mAmbientLuxValid) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }
        if (shouldApplyDozeScaleFactor()) {
            return mScreenAutoBrightness * mDozeScaleFactor;
        }
        return mScreenAutoBrightness;
    }

    float getRawAutomaticScreenBrightness() {
        return mRawScreenAutoBrightness;
    }

    /**
     * Get the automatic screen brightness based on the last observed lux reading. Used e.g. when
     * entering doze - we disable the light sensor, invalidate the lux, but we still need to set
     * the initial brightness in doze mode.
     */
    public float getAutomaticScreenBrightnessBasedOnLastObservedLux(
            BrightnessEvent brightnessEvent) {
        float lastObservedLux = mLightSensorController.getLastObservedLux();
        if (lastObservedLux == INVALID_LUX) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }

        float brightness = mCurrentBrightnessMapper.getBrightness(lastObservedLux,
                mForegroundAppPackageName, mForegroundAppCategory);
        if (shouldApplyDozeScaleFactor()) {
            brightness *= mDozeScaleFactor;
        }

        if (brightnessEvent != null) {
            brightnessEvent.setLux(lastObservedLux);
            brightnessEvent.setRecommendedBrightness(brightness);
            brightnessEvent.setFlags(brightnessEvent.getFlags()
                    | (lastObservedLux == INVALID_LUX ? BrightnessEvent.FLAG_INVALID_LUX : 0)
                    | (shouldApplyDozeScaleFactor() ? BrightnessEvent.FLAG_DOZE_SCALE : 0));
            brightnessEvent.setAutoBrightnessMode(getMode());
        }
        return brightness;
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
            boolean shouldResetShortTermModel) {
        mState = state;
        boolean changed = setBrightnessConfiguration(configuration, shouldResetShortTermModel);
        changed |= setDisplayPolicy(displayPolicy);
        mDisplayState = displayState;
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
        mLightSensorController.stop();
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

    public boolean isInIdleMode() {
        return mCurrentBrightnessMapper.getMode() == AUTO_BRIGHTNESS_MODE_IDLE;
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Automatic Brightness Controller Configuration:");
        pw.println("  mState=" + configStateToString(mState));
        pw.println("  mScreenBrightnessRangeMinimum=" + mScreenBrightnessRangeMinimum);
        pw.println("  mScreenBrightnessRangeMaximum=" + mScreenBrightnessRangeMaximum);
        pw.println("  mDozeScaleFactor=" + mDozeScaleFactor);
        pw.println();
        pw.println("Automatic Brightness Controller State:");
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mAmbientLuxValid=" + mAmbientLuxValid);
        pw.println("  mPreThresholdBrightness=" + mPreThresholdBrightness);
        pw.println("  mScreenBrighteningThreshold=" + mScreenBrighteningThreshold);
        pw.println("  mScreenDarkeningThreshold=" + mScreenDarkeningThreshold);
        pw.println("  mScreenAutoBrightness=" + mScreenAutoBrightness);
        pw.println("  mDisplayPolicy=" + DisplayPowerRequest.policyToString(mDisplayPolicy));
        pw.println("  mShortTermModel=");
        mShortTermModel.dump(pw);
        pw.println("  mPausedShortTermModel=");
        mPausedShortTermModel.dump(pw);

        pw.println();
        pw.println("  mBrightnessAdjustmentSamplePending=" + mBrightnessAdjustmentSamplePending);
        pw.println("  mBrightnessAdjustmentSampleOldLux=" + mBrightnessAdjustmentSampleOldLux);
        pw.println("  mBrightnessAdjustmentSampleOldBrightness="
                + mBrightnessAdjustmentSampleOldBrightness);
        pw.println("  mForegroundAppPackageName=" + mForegroundAppPackageName);
        pw.println("  mPendingForegroundAppPackageName=" + mPendingForegroundAppPackageName);
        pw.println("  mForegroundAppCategory=" + mForegroundAppCategory);
        pw.println("  mPendingForegroundAppCategory=" + mPendingForegroundAppCategory);
        pw.println("  Current mode="
                + autoBrightnessModeToString(mCurrentBrightnessMapper.getMode()));

        for (int i = 0; i < mBrightnessMappingStrategyMap.size(); i++) {
            pw.println();
            pw.println("  Mapper for mode "
                    + autoBrightnessModeToString(mBrightnessMappingStrategyMap.keyAt(i)) + ":");
            mBrightnessMappingStrategyMap.valueAt(i).dump(pw,
                    mBrightnessRangeController.getNormalBrightnessMax());
        }

        pw.println();
        pw.println("  mScreenBrightnessThresholds=");
        mScreenBrightnessThresholds.dump(pw);
        pw.println("  mScreenBrightnessThresholdsIdle=");
        mScreenBrightnessThresholdsIdle.dump(pw);

        pw.println();
        mLightSensorController.dump(pw);
    }

    public float[] getLastSensorValues() {
        return mLightSensorController.getLastSensorValues();
    }

    public long[] getLastSensorTimestamps() {
        return mLightSensorController.getLastSensorTimestamps();
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
        if (enable && mLightSensorController.enableLightSensorIfNeeded()) {
            registerForegroundAppUpdater();
            return true;
        } else if (!enable && mLightSensorController.disableLightSensorIfNeeded()) {
            mScreenAutoBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mRawScreenAutoBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mPreThresholdBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mAmbientLuxValid = mLightSensorController.hasValidAmbientLux();
            unregisterForegroundAppUpdater();
        }
        return false;
    }

    private boolean setAutoBrightnessAdjustment(float adjustment) {
        return mCurrentBrightnessMapper.setAutoBrightnessAdjustment(adjustment);
    }

    private void setAmbientLux(float lux) {
        // called by LightSensorController.setAmbientLux
        mAmbientLuxValid = true;
        mAmbientLux = lux;
        mBrightnessRangeController.onAmbientLuxChange(mAmbientLux);
        mBrightnessClamperController.onAmbientLuxChange(mAmbientLux);

        // If the short term model was invalidated and the change is drastic enough, reset it.
        mShortTermModel.maybeReset(mAmbientLux);
        updateAutoBrightness(true /* sendUpdate */, false /* isManuallySet */);
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
                            + "brightness=" + mScreenAutoBrightness);
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

        update();
    }

    void switchMode(@AutomaticBrightnessMode int mode) {
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
            mLightSensorController.setIdleMode(isInIdleMode());
        } else {
            resetShortTermModel();
            mCurrentBrightnessMapper = mBrightnessMappingStrategyMap.get(mode);
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
        // Apply the doze scale factor if the display is in doze. We shouldn't rely on the display
        // policy here - the screen might turn on while the policy is POLICY_DOZE and in this
        // situation, we shouldn't apply the doze scale factor. We also don't apply the doze scale
        // factor if we have a designated brightness curve for doze.
        return Display.isDozeState(mDisplayState) && getMode() != AUTO_BRIGHTNESS_MODE_DOZE;
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

        void dump(PrintWriter pw) {
            pw.println(this);
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

    public static class Injector {
        public Handler getBackgroundThreadHandler() {
            return BackgroundThread.getHandler();
        }

        Clock createClock() {
            return Clock.SYSTEM_CLOCK;
        }
    }
}
