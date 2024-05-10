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
import android.os.PowerManager;

import java.io.PrintWriter;

/**
 * An interface to manage the display's power state and brightness
 */
public interface DisplayPowerControllerInterface {
    int DEFAULT_USER_SERIAL = -1;
    /**
     * Notified when the display is changed.
     *
     * We use this to apply any changes that might be needed when displays get swapped on foldable
     * devices, when layouts change, etc.
     *
     * Must be called while holding the SyncRoot lock.
     *
     * @param hbmInfo The high brightness mode metadata, like
     *                remaining time and hbm events, for the corresponding
     *                physical display, to make sure we stay within the safety margins.
     * @param leadDisplayId The display who is considered our "leader" for things like brightness.
     */
    void onDisplayChanged(HighBrightnessModeMetadata hbmInfo, int leadDisplayId);

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
    default void setBrightness(float brightness) {
        setBrightness(brightness, DEFAULT_USER_SERIAL);
    }

    /**
     * Set the screen brightness of the associated display
     * @param brightness The value to which the brightness is to be set
     * @param userSerial The user for which the brightness value is to be set. Use userSerial = -1,
     * if brightness needs to be updated for the current user.
     */
    void setBrightness(float brightness, int userSerial);

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

    void overrideDozeScreenState(int displayState);

    void setDisplayOffloadSession(DisplayManagerInternal.DisplayOffloadSession session);

    /**
     * Sets up the temporary autobrightness adjustment when the user is yet to settle down to a
     * value.
     */
    void setTemporaryAutoBrightnessAdjustment(float adjustment);

    /**
     * Sets temporary brightness from the offload chip until we get a brightness value from
     * the light sensor.
     * @param brightness The brightness value between {@link PowerManager.BRIGHTNESS_MIN} and
     * {@link PowerManager.BRIGHTNESS_MAX}. Values outside of that range will be ignored.
     */
    void setBrightnessFromOffload(float brightness);

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

    /**
     * Get the ID of the display associated with this DPC.
     * @return The display ID
     */
    int getDisplayId();

    /**
     * Get the ID of the display that is the leader of this DPC.
     *
     * Note that this is different than the display associated with the DPC. The leader is another
     * display which we follow for things like brightness.
     *
     * Must be called while holding the SyncRoot lock.
     */
    int getLeadDisplayId();

    /**
     * Set the brightness to follow if this is an additional display in a set of concurrent
     * displays.
     * @param leadDisplayBrightness The brightness of the lead display in the set of concurrent
     *                              displays
     * @param nits The brightness value in nits if the device supports nits. Set to a negative
     *             number otherwise.
     * @param ambientLux The lux value that will be passed to {@link HighBrightnessModeController}
     * @param slowChange Indicates whether we should slowly animate to the given brightness value.
     */
    void setBrightnessToFollow(float leadDisplayBrightness, float nits, float ambientLux,
            boolean slowChange);

    /**
     * Add an additional display that will copy the brightness value from this display. This is used
     * when the device is in concurrent displays mode.
     * @param follower The DPC that should copy the brightness value from this DPC
     */
    void addDisplayBrightnessFollower(DisplayPowerControllerInterface follower);

    /**
     * Removes the given display from the list of brightness followers.
     * @param follower The DPC to remove from the followers list
     */
    void removeDisplayBrightnessFollower(DisplayPowerControllerInterface follower);

    /**
     * Indicate that boot has been completed and the screen is ready to update.
     */
    void onBootCompleted();
}
