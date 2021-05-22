/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import android.os.PackageTagsList;
import android.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.util.Set;

/**
 * Provides accessors and listeners for all location related settings.
 */
public abstract class SettingsHelper {

    /**
     * Listener for user-specific settings changes.
     */
    public interface UserSettingChangedListener {
        /**
         * Called when setting changes.
         */
        void onSettingChanged(int userId);
    }

    /**
     * Listener for global settings changes.
     */
    public interface GlobalSettingChangedListener extends UserSettingChangedListener {
        /**
         * Called when setting changes.
         */
        void onSettingChanged();

        @Override
        default void onSettingChanged(int userId) {
            onSettingChanged();
        }
    }

    /**
     * Retrieve if location is enabled or not.
     */
    public abstract boolean isLocationEnabled(int userId);

    /**
     * Set location enabled for a user.
     */
    public abstract void setLocationEnabled(boolean enabled, int userId);

    /**
     * Add a listener for changes to the location enabled setting. Callbacks occur on an unspecified
     * thread.
     */
    public abstract void addOnLocationEnabledChangedListener(UserSettingChangedListener listener);

    /**
     * Remove a listener for changes to the location enabled setting.
     */
    public abstract void removeOnLocationEnabledChangedListener(
            UserSettingChangedListener listener);

    /**
     * Retrieve the background throttle interval.
     */
    public abstract long getBackgroundThrottleIntervalMs();

    /**
     * Add a listener for changes to the background throttle interval. Callbacks occur on an
     * unspecified thread.
     */
    public abstract void addOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener);

    /**
     * Remove a listener for changes to the background throttle interval.
     */
    public abstract void removeOnBackgroundThrottleIntervalChangedListener(
            GlobalSettingChangedListener listener);

    /**
     * Check if the given package is blacklisted for location access.
     */
    public abstract boolean isLocationPackageBlacklisted(int userId, String packageName);

    /**
     * Add a listener for changes to the location package blacklist. Callbacks occur on an
     * unspecified thread.
     */
    public abstract void addOnLocationPackageBlacklistChangedListener(
            UserSettingChangedListener listener);

    /**
     * Remove a listener for changes to the location package blacklist.
     */
    public abstract void removeOnLocationPackageBlacklistChangedListener(
            UserSettingChangedListener listener);

    /**
     * Retrieve the background throttle package whitelist.
     */
    public abstract Set<String> getBackgroundThrottlePackageWhitelist();

    /**
     * Add a listener for changes to the background throttle package whitelist. Callbacks occur on
     * an unspecified thread.
     */
    public abstract void addOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener);

    /**
     * Remove a listener for changes to the background throttle package whitelist.
     */
    public abstract void removeOnBackgroundThrottlePackageWhitelistChangedListener(
            GlobalSettingChangedListener listener);

    /**
     * Retrieve the gnss measurements full tracking enabled setting.
     */
    public abstract boolean isGnssMeasurementsFullTrackingEnabled();

    /**
     * Add a listener for changes to the background throttle package whitelist. Callbacks occur on
     * an unspecified thread.
     */
    public abstract void addOnGnssMeasurementsFullTrackingEnabledChangedListener(
            GlobalSettingChangedListener listener);

    /**
     * Remove a listener for changes to the background throttle package whitelist.
     */
    public abstract void removeOnGnssMeasurementsFullTrackingEnabledChangedListener(
            GlobalSettingChangedListener listener);

    /**
     * Retrieve the ignore location settings package+tags allowlist setting.
     */
    public abstract PackageTagsList getIgnoreSettingsAllowlist();

    /**
     * Add a listener for changes to the ignore settings package whitelist. Callbacks occur on an
     * unspecified thread.
     */
    public abstract void addIgnoreSettingsAllowlistChangedListener(
            GlobalSettingChangedListener listener);

    /**
     * Remove a listener for changes to the ignore settings package whitelist.
     */
    public abstract void removeIgnoreSettingsAllowlistChangedListener(
            GlobalSettingChangedListener listener);

    /**
     * Retrieve the background throttling proximity alert interval.
     */
    public abstract long getBackgroundThrottleProximityAlertIntervalMs();

    /**
     * Retrieve the accuracy for coarsening location, ie, the grid size used for snap-to-grid
     * coarsening.
     */
    public abstract float getCoarseLocationAccuracyM();

    /**
     * Dump info for debugging.
     */
    public abstract void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args);
}
