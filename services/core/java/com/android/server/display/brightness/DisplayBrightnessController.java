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

package com.android.server.display.brightness;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.util.IndentingPrintWriter;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.BrightnessMappingStrategy;
import com.android.server.display.BrightnessSetting;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.brightness.strategy.AutoBrightnessFallbackStrategy;
import com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy2;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;
import com.android.server.display.feature.DisplayManagerFlags;

import java.io.PrintWriter;

/**
 * Deploys different DozeBrightnessStrategy to choose the current brightness for a specified
 * display. Applies the chosen brightness.
 */
public final class DisplayBrightnessController {

    // The ID of the display tied to this DisplayBrightnessController
    private final int mDisplayId;

    // The lock which is to be used to synchronize the resources being used in this class
    private final Object mLock = new Object();

    // The default screen brightness to be used when no value is available in BrightnessSetting.
    private final float mScreenBrightnessDefault;

    // This is used to persist the changes happening to the brightness.
    private final BrightnessSetting mBrightnessSetting;

    // A runnable to update the clients registered via DisplayManagerGlobal
    // .EVENT_DISPLAY_BRIGHTNESS_CHANGED about the brightness change. Called when
    // mCurrentScreenBrightness is updated.
    private Runnable mOnBrightnessChangeRunnable;

    // The screen brightness that has changed but not taken effect yet. If this is different
    // from the current screen brightness then this is coming from something other than us
    // and should be considered a user interaction.
    @GuardedBy("mLock")
    private float mPendingScreenBrightness;

    // The last observed screen brightness, either set by us or by the settings app on
    // behalf of the user.
    @GuardedBy("mLock")
    private float mCurrentScreenBrightness;

    // The last brightness that was set by the user and not temporary. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when a brightness has yet to be recorded.
    @GuardedBy("mLock")
    private float mLastUserSetScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;

    // Represents if the system has adjusted the brightness based on the user suggested value. Will
    // be false if the brightness change is coming from a non-user source
    private boolean mUserSetScreenBrightnessUpdated;

    // The listener which is to be notified everytime there is a change in the brightness in the
    // BrightnessSetting.
    private BrightnessSetting.BrightnessSettingListener mBrightnessSettingListener;

    // Selects an appropriate strategy based on the request provided by the clients.
    @GuardedBy("mLock")
    private DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;

    // Currently selected DisplayBrightnessStrategy.
    @GuardedBy("mLock")
    private DisplayBrightnessStrategy mDisplayBrightnessStrategy;

    // The executor on which the mOnBrightnessChangeRunnable is executed. This ensures that the
    // callback is not executed in sync and is not blocking the thread from which it is called.
    private final HandlerExecutor mBrightnessChangeExecutor;

    // True if we want to persist the brightness value in nits even if the underlying display
    // device changes.
    private final boolean mPersistBrightnessNitsForDefaultDisplay;

    // The controller for the automatic brightness level.
    // TODO(b/265415257): Move to the automatic brightness strategy
    @Nullable
    @VisibleForTesting
    AutomaticBrightnessController mAutomaticBrightnessController;

    /**
     * The constructor of DisplayBrightnessController.
     */
    public DisplayBrightnessController(Context context, Injector injector, int displayId,
            float defaultScreenBrightness, BrightnessSetting brightnessSetting,
            Runnable onBrightnessChangeRunnable, HandlerExecutor brightnessChangeExecutor,
            DisplayManagerFlags flags) {
        if (injector == null) {
            injector = new Injector();
        }
        mDisplayId = displayId;
        // TODO: b/186428377 update brightness setting when display changes
        mBrightnessSetting = brightnessSetting;
        mPendingScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mScreenBrightnessDefault = BrightnessUtils.clampAbsoluteBrightness(defaultScreenBrightness);
        mCurrentScreenBrightness = getScreenBrightnessSetting();
        mOnBrightnessChangeRunnable = onBrightnessChangeRunnable;
        mDisplayBrightnessStrategySelector = injector.getDisplayBrightnessStrategySelector(context,
                displayId, flags);
        mBrightnessChangeExecutor = brightnessChangeExecutor;
        mPersistBrightnessNitsForDefaultDisplay = context.getResources().getBoolean(
                com.android.internal.R.bool.config_persistBrightnessNitsForDefaultDisplay);
    }

    /**
     * Updates the display brightness. This delegates the responsibility of selecting an appropriate
     * strategy to DisplayBrightnessStrategySelector, which is then applied to evaluate the
     * DisplayBrightnessState. In the future,
     * 1. This will account for clamping the brightness if needed.
     * 2. This will notify the system about the updated brightness
     *
     * @param displayPowerRequest The request to update the brightness
     * @param targetDisplayState  The target display state of the system
     */
    public DisplayBrightnessState updateBrightness(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int targetDisplayState,
            DisplayManagerInternal.DisplayOffloadSession displayOffloadSession) {
        DisplayBrightnessState state;
        synchronized (mLock) {
            mDisplayBrightnessStrategy = mDisplayBrightnessStrategySelector.selectStrategy(
                    constructStrategySelectionRequest(displayPowerRequest, targetDisplayState,
                            displayOffloadSession));
            state = mDisplayBrightnessStrategy
                        .updateBrightness(constructStrategyExecutionRequest(displayPowerRequest));
        }

        // This is a temporary measure until AutomaticBrightnessStrategy works as a traditional
        // strategy.
        // TODO: Remove when AutomaticBrightnessStrategy is populating the values directly.
        if (state != null) {
            state = addAutomaticBrightnessState(state);
        }
        return state;
    }

    /**
     * Sets the temporary brightness
     */
    public void setTemporaryBrightness(Float temporaryBrightness) {
        synchronized (mLock) {
            setTemporaryBrightnessLocked(temporaryBrightness);
        }
    }

    /**
     * Sets the brightness to follow
     */
    public void setBrightnessToFollow(float brightnessToFollow, boolean slowChange) {
        synchronized (mLock) {
            mDisplayBrightnessStrategySelector.getFollowerDisplayBrightnessStrategy()
                    .setBrightnessToFollow(brightnessToFollow, slowChange);
        }
    }

    /**
     * Sets the brightness from the offload session.
     * @return Whether the offload brightness has changed
     */
    public boolean setBrightnessFromOffload(float brightness) {
        synchronized (mLock) {
            if (mDisplayBrightnessStrategySelector.getOffloadBrightnessStrategy() != null
                    && !BrightnessSynchronizer.floatEquals(mDisplayBrightnessStrategySelector
                    .getOffloadBrightnessStrategy().getOffloadScreenBrightness(), brightness)) {
                mDisplayBrightnessStrategySelector.getOffloadBrightnessStrategy()
                        .setOffloadScreenBrightness(brightness);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a boolean flag indicating if the light sensor is to be used to decide the screen
     * brightness when dozing
     */
    public boolean isAllowAutoBrightnessWhileDozing() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.isAllowAutoBrightnessWhileDozing();
        }
    }

    /**
     * Returns the config value indicating the auto brightness while dozing is to be
     * allowed ot not. Note that this is a config value, but the actual status can differ from this.
     */
    public boolean isAllowAutoBrightnessWhileDozingConfig() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.isAllowAutoBrightnessWhileDozingConfig();
        }
    }

    /**
     * Sets the current screen brightness to the supplied value, and notifies all the listeners
     * requesting for change events on brightness change.
     */
    public void setAndNotifyCurrentScreenBrightness(float brightnessValue) {
        final boolean hasBrightnessChanged;
        synchronized (mLock) {
            hasBrightnessChanged = (brightnessValue != mCurrentScreenBrightness);
            setCurrentScreenBrightnessLocked(brightnessValue);
        }
        if (hasBrightnessChanged) {
            notifyCurrentScreenBrightness();
        }
    }

    /**
     * Returns the last observed screen brightness.
     */
    public float getCurrentBrightness() {
        synchronized (mLock) {
            return mCurrentScreenBrightness;
        }
    }

    /**
     * Returns the screen brightness which has changed but has not taken any effect so far.
     */
    public float getPendingScreenBrightness() {
        synchronized (mLock) {
            return mPendingScreenBrightness;
        }
    }

    /**
     * Sets the pending screen brightness setting, representing a value which is requested, but not
     * yet processed.
     * @param brightnessValue The value to which the pending screen brightness is to be set.
     */
    public void setPendingScreenBrightness(float brightnessValue) {
        synchronized (mLock) {
            mPendingScreenBrightness = brightnessValue;
        }
    }

    /**
     * Returns if the system has adjusted the brightness based on the user suggested value. Will
     * be false if the brightness change is coming from a non-user source.
     *
     * Todo: 294444204 This is a temporary workaround, and should be moved to the manual brightness
     * strategy once that is introduced
     */
    public boolean getIsUserSetScreenBrightnessUpdated() {
        return mUserSetScreenBrightnessUpdated;
    }

    /**
     * Registers the BrightnessSettingListener with the BrightnessSetting, which will be notified
     * everytime there is a change in the brightness.
     */
    public void registerBrightnessSettingChangeListener(
            BrightnessSetting.BrightnessSettingListener brightnessSettingListener) {
        mBrightnessSettingListener = brightnessSettingListener;
        mBrightnessSetting.registerListener(mBrightnessSettingListener);
    }

    /**
     * Returns the last user set brightness which is not temporary.
     */
    public float getLastUserSetScreenBrightness() {
        synchronized (mLock) {
            return mLastUserSetScreenBrightness;
        }
    }

    /**
     * Returns the current screen brightnessSetting which is responsible for saving the brightness
     * in the persistent store
     */
    public float getScreenBrightnessSetting() {
        float brightness = mBrightnessSetting.getBrightness();
        synchronized (mLock) {
            if (Float.isNaN(brightness)) {
                brightness = mScreenBrightnessDefault;
            }
            return BrightnessUtils.clampAbsoluteBrightness(brightness);
        }
    }

    /**
     * Notifies the brightnessSetting to persist the supplied brightness value.
     */
    public void setBrightness(float brightnessValue) {
        // Update the setting, which will eventually call back into DPC to have us actually
        // update the display with the new value.
        mBrightnessSetting.setBrightness(brightnessValue);
        if (mDisplayId == Display.DEFAULT_DISPLAY && mPersistBrightnessNitsForDefaultDisplay) {
            float nits = convertToNits(brightnessValue);
            if (nits >= 0) {
                mBrightnessSetting.setBrightnessNitsForDefaultDisplay(nits);
            }
        }
    }

    /**
     * Notifies the brightnessSetting to persist the supplied brightness value for a user.
     */
    public void setBrightness(float brightnessValue, int userSerial) {
        mBrightnessSetting.setUserSerial(userSerial);
        setBrightness(brightnessValue);
    }

    /**
     * Sets the current screen brightness, and notifies the BrightnessSetting about the change.
     */
    public void updateScreenBrightnessSetting(float brightnessValue) {
        synchronized (mLock) {
            if (!BrightnessUtils.isValidBrightnessValue(brightnessValue)
                    || brightnessValue == mCurrentScreenBrightness) {
                return;
            }
            setCurrentScreenBrightnessLocked(brightnessValue);
        }
        notifyCurrentScreenBrightness();
        setBrightness(brightnessValue);
    }

    /**
     * Sets up the auto brightness and the relevant state for the associated display
     */
    public void setUpAutoBrightness(AutomaticBrightnessController automaticBrightnessController,
            SensorManager sensorManager,
            DisplayDeviceConfig displayDeviceConfig, Handler handler,
            BrightnessMappingStrategy brightnessMappingStrategy, boolean isEnabled,
            int leadDisplayId) {
        setAutomaticBrightnessController(automaticBrightnessController);
        setUpAutoBrightnessFallbackStrategy(sensorManager, displayDeviceConfig, handler,
                brightnessMappingStrategy, isEnabled, leadDisplayId);
    }

    /**
     * TODO(b/253226419): Remove once auto-brightness is a fully-functioning strategy.
     */
    public AutomaticBrightnessStrategy2 getAutomaticBrightnessStrategy() {
        return mDisplayBrightnessStrategySelector.getAutomaticBrightnessStrategy();
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
        if (mAutomaticBrightnessController == null) {
            return BrightnessMappingStrategy.INVALID_NITS;
        }
        return mAutomaticBrightnessController.convertToNits(brightness);
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
        if (mAutomaticBrightnessController == null) {
            return BrightnessMappingStrategy.INVALID_NITS;
        }
        return mAutomaticBrightnessController.convertToAdjustedNits(brightness);
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
        if (mAutomaticBrightnessController == null) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }
        return mAutomaticBrightnessController.getBrightnessFromNits(nits);
    }

    /**
     * Stops the associated listeners when the display is stopped. Invoked when the {@link
     * #mDisplayId} is being removed.
     */
    public void stop() {
        if (mBrightnessSetting != null) {
            mBrightnessSetting.unregisterListener(mBrightnessSettingListener);
        }
        AutoBrightnessFallbackStrategy autoBrightnessFallbackStrategy =
                getAutoBrightnessFallbackStrategy();
        if (autoBrightnessFallbackStrategy != null) {
            autoBrightnessFallbackStrategy.stop();
        }
    }

    private AutoBrightnessFallbackStrategy getAutoBrightnessFallbackStrategy() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.getAutoBrightnessFallbackStrategy();
        }
    }

    /**
     * Used to dump the state.
     *
     * @param writer The PrintWriter used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println();
        writer.println("DisplayBrightnessController:");
        writer.println("  mDisplayId=: " + mDisplayId);
        writer.println("  mScreenBrightnessDefault=" + mScreenBrightnessDefault);
        writer.println("  mPersistBrightnessNitsForDefaultDisplay="
                + mPersistBrightnessNitsForDefaultDisplay);
        synchronized (mLock) {
            writer.println("  mPendingScreenBrightness=" + mPendingScreenBrightness);
            writer.println("  mCurrentScreenBrightness=" + mCurrentScreenBrightness);
            writer.println("  mLastUserSetScreenBrightness="
                    + mLastUserSetScreenBrightness);
            if (mDisplayBrightnessStrategy != null) {
                writer.println("  Last selected DisplayBrightnessStrategy= "
                        + mDisplayBrightnessStrategy.getName());
            }
            IndentingPrintWriter ipw = new IndentingPrintWriter(writer, " ");
            mDisplayBrightnessStrategySelector.dump(ipw);
        }
    }

    /**
     * We want to return true if the user has set the screen brightness.
     * RBC on, off, and intensity changes will return false.
     * Slider interactions whilst in RBC will return true, just as when in non-rbc.
     */
    @VisibleForTesting
    boolean updateUserSetScreenBrightness() {
        mUserSetScreenBrightnessUpdated = false;
        synchronized (mLock) {
            if (!BrightnessUtils.isValidBrightnessValue(mPendingScreenBrightness)) {
                return false;
            }
            if (mCurrentScreenBrightness == mPendingScreenBrightness) {
                mPendingScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
                setTemporaryBrightnessLocked(PowerManager.BRIGHTNESS_INVALID_FLOAT);
                return false;
            }
            setCurrentScreenBrightnessLocked(mPendingScreenBrightness);
            mLastUserSetScreenBrightness = mPendingScreenBrightness;
            mPendingScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            setTemporaryBrightnessLocked(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        }
        notifyCurrentScreenBrightness();
        mUserSetScreenBrightnessUpdated = true;
        return true;
    }

    @VisibleForTesting
    static class Injector {
        DisplayBrightnessStrategySelector getDisplayBrightnessStrategySelector(Context context,
                int displayId, DisplayManagerFlags flags) {
            return new DisplayBrightnessStrategySelector(context, /* injector= */ null, displayId,
                    flags);
        }
    }

    @VisibleForTesting
    BrightnessSetting.BrightnessSettingListener getBrightnessSettingListener() {
        return mBrightnessSettingListener;
    }

    /**
     * Returns the current selected DisplayBrightnessStrategy
     */
    @VisibleForTesting
    DisplayBrightnessStrategy getCurrentDisplayBrightnessStrategy() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategy;
        }
    }

    /**
     * Set the {@link AutomaticBrightnessController} which is needed to perform nit-to-float-scale
     * conversion.
     * @param automaticBrightnessController The ABC
     */
    @VisibleForTesting
    void setAutomaticBrightnessController(
            AutomaticBrightnessController automaticBrightnessController) {
        mAutomaticBrightnessController = automaticBrightnessController;
        getAutomaticBrightnessStrategy()
                .setAutomaticBrightnessController(automaticBrightnessController);
        loadNitBasedBrightnessSetting();
    }

    private void setUpAutoBrightnessFallbackStrategy(SensorManager sensorManager,
            DisplayDeviceConfig displayDeviceConfig, Handler handler,
            BrightnessMappingStrategy brightnessMappingStrategy, boolean isEnabled,
            int leadDisplayId) {
        AutoBrightnessFallbackStrategy autoBrightnessFallbackStrategy =
                getAutoBrightnessFallbackStrategy();
        if (autoBrightnessFallbackStrategy != null) {
            autoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(
                    sensorManager, displayDeviceConfig, handler, brightnessMappingStrategy,
                    isEnabled, leadDisplayId);
        }
    }

    /**
     * TODO(b/253226419): Remove once auto-brightness is a fully-functioning strategy.
     */
    private DisplayBrightnessState addAutomaticBrightnessState(DisplayBrightnessState state) {
        AutomaticBrightnessStrategy2 autoStrat = getAutomaticBrightnessStrategy();

        DisplayBrightnessState.Builder builder = DisplayBrightnessState.Builder.from(state);
        builder.setShouldUseAutoBrightness(
                autoStrat != null && autoStrat.shouldUseAutoBrightness());
        return builder.build();
    }

    @GuardedBy("mLock")
    private void setTemporaryBrightnessLocked(float temporaryBrightness) {
        mDisplayBrightnessStrategySelector.getTemporaryDisplayBrightnessStrategy()
                .setTemporaryScreenBrightness(temporaryBrightness);
    }

    @GuardedBy("mLock")
    private void setCurrentScreenBrightnessLocked(float brightnessValue) {
        if (brightnessValue != mCurrentScreenBrightness) {
            mCurrentScreenBrightness = brightnessValue;
        }
    }

    private void notifyCurrentScreenBrightness() {
        mBrightnessChangeExecutor.execute(mOnBrightnessChangeRunnable);
    }

    /**
     * Loads the brightness value. If this is the default display and the config says that we should
     * persist the nit value, the nit value for the default display will be loaded.
     */
    private void loadNitBasedBrightnessSetting() {
        float currentBrightnessSetting = Float.NaN;
        if (mDisplayId == Display.DEFAULT_DISPLAY && mPersistBrightnessNitsForDefaultDisplay) {
            float brightnessNitsForDefaultDisplay =
                    mBrightnessSetting.getBrightnessNitsForDefaultDisplay();
            if (brightnessNitsForDefaultDisplay >= 0) {
                float brightnessForDefaultDisplay = getBrightnessFromNits(
                        brightnessNitsForDefaultDisplay);
                if (BrightnessUtils.isValidBrightnessValue(brightnessForDefaultDisplay)) {
                    mBrightnessSetting.setBrightness(brightnessForDefaultDisplay);
                    currentBrightnessSetting = brightnessForDefaultDisplay;
                }
            }
        }

        if (Float.isNaN(currentBrightnessSetting)) {
            currentBrightnessSetting = getScreenBrightnessSetting();
        }

        synchronized (mLock) {
            mCurrentScreenBrightness = currentBrightnessSetting;
        }
    }

    private StrategySelectionRequest constructStrategySelectionRequest(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int targetDisplayState,
            DisplayManagerInternal.DisplayOffloadSession displayOffloadSession) {
        boolean userSetBrightnessChanged = updateUserSetScreenBrightness();
        float lastUserSetScreenBrightness;
        synchronized (mLock) {
            lastUserSetScreenBrightness = mLastUserSetScreenBrightness;
        }
        return new StrategySelectionRequest(displayPowerRequest, targetDisplayState,
                lastUserSetScreenBrightness, userSetBrightnessChanged, displayOffloadSession);
    }

    private StrategyExecutionRequest constructStrategyExecutionRequest(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest) {
        float currentScreenBrightness = getCurrentBrightness();
        return new StrategyExecutionRequest(displayPowerRequest, currentScreenBrightness,
                mUserSetScreenBrightnessUpdated);
    }
}
