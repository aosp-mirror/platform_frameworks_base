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

import android.content.pm.ParceledListSlice;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal;

import java.io.PrintWriter;

/**
 * An interface to manage the display's power state and brightness
 */
public interface DisplayPowerControllerInterface {

    /**
     * Notified when the display is changed.
     * We use this to apply any changes that might be needed
     * when displays get swapped on foldable devices.
     * We also pass the High brightness mode metadata like
     * remaining time and hbm events for the corresponding
     * physical display, to update the values correctly.
     */
    void onDisplayChanged(HighBrightnessModeMetadata hbmInfo);

    /**
     * Unregisters all listeners and interrupts all running threads; halting future work.
     *
     * This method should be called when the DisplayPowerController is no longer in use; i.e. when
     * the display has been removed.
     */
    void stop();

    /**
     * Used to update the display's BrightnessConfiguration
     * @param config The new BrightnessConfiguration
     */
    void setBrightnessConfiguration(BrightnessConfiguration config,
            boolean shouldResetShortTermModel);

    /**
     * Used to set the ambient color temperature of the Display
     * @param ambientColorTemperature The target ambientColorTemperature
     */
    void setAmbientColorTemperatureOverride(float ambientColorTemperature);

    /**
     * Used to decide the associated AutomaticBrightnessController's BrightnessMode
     * @param isIdle Flag which represents if the Idle BrightnessMode is to be set
     */
    void setAutomaticScreenBrightnessMode(boolean isIdle);

    /**
     * Used to enable/disable the logging of the WhileBalance associated entities
     * @param enabled Flag which represents if the logging is the be enabled
     */
    void setDisplayWhiteBalanceLoggingEnabled(boolean enabled);

    /**
     * Used to dump the state.
     * @param writer The PrintWriter used to dump the state.
     */
    void dump(PrintWriter writer);

    /**
     * Used to get the ambient brightness stats
     */
    ParceledListSlice<AmbientBrightnessDayStats> getAmbientBrightnessStats(int userId);

    /**
     * Get the default brightness configuration
     */
    BrightnessConfiguration getDefaultBrightnessConfiguration();

    /**
     * Set the screen brightness of the associated display
     * @param brightness The value to which the brightness is to be set
     */
    void setBrightness(float brightness);

    /**
     * Checks if the proximity sensor is available
     */
    boolean isProximitySensorAvailable();

    /**
     * Persist the brightness slider events and ambient brightness stats to disk.
     */
    void persistBrightnessTrackerState();

    /**
     * Ignores the proximity sensor until the sensor state changes, but only if the sensor is
     * currently enabled and forcing the screen to be dark.
     */
    void ignoreProximitySensorUntilChanged();

    /**
     * Requests a new power state.
     *
     * @param request The requested power state.
     * @param waitForNegativeProximity If true, issues a request to wait for
     * negative proximity before turning the screen back on,
     * assuming the screen was turned off by the proximity sensor.
     * @return True if display is ready, false if there are important changes that must
     * be made asynchronously.
     */
    boolean requestPowerState(DisplayManagerInternal.DisplayPowerRequest request,
            boolean waitForNegativeProximity);

    /**
     * Sets up the temporary autobrightness adjustment when the user is yet to settle down to a
     * value.
     */
    void setTemporaryAutoBrightnessAdjustment(float adjustment);

    /**
     * Gets the screen brightness setting
     */
    float getScreenBrightnessSetting();

    /**
     * Sets up the temporary brightness for the associated display
     */
    void setTemporaryBrightness(float brightness);

    /**
     * Gets the associated {@link BrightnessInfo}
     */
    BrightnessInfo getBrightnessInfo();

    /**
     * Get the {@link BrightnessChangeEvent}s for the specified user.
     */
    ParceledListSlice<BrightnessChangeEvent> getBrightnessEvents(int userId, boolean hasUsageStats);

    /**
     * Sets up the logging for the associated {@link AutomaticBrightnessController}
     * @param enabled Flag to represent if the logging is to be enabled
     */
    void setAutoBrightnessLoggingEnabled(boolean enabled);

    /**
     * Handles the changes to be done to update the brightness when the user is changed
     * @param newUserId The new userId
     */
    void onSwitchUser(int newUserId);
}
