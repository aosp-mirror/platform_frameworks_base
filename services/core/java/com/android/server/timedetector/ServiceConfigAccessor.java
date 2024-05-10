/*
 * Copyright 2021 The Android Open Source Project
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
package com.android.server.timedetector;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.TimeConfiguration;

import com.android.server.timezonedetector.StateChangeListener;

/**
 * An interface that provides access to service configuration for time detection. This hides
 * how configuration is split between static, compile-time config, dynamic server-pushed flags and
 * user settings. It provides listeners to signal when values that affect different components have
 * changed.
 */
public interface ServiceConfigAccessor {

    /**
     * Adds a listener that will be invoked when {@link ConfigurationInternal} may have changed.
     * The listener is invoked on the main thread.
     */
    void addConfigurationInternalChangeListener(@NonNull StateChangeListener listener);

    /**
     * Removes a listener previously added via {@link
     * #addConfigurationInternalChangeListener(StateChangeListener)}.
     */
    void removeConfigurationInternalChangeListener(@NonNull StateChangeListener listener);

    /**
     * Returns a snapshot of the {@link ConfigurationInternal} for the current user. This is only a
     * snapshot so callers must use {@link
     * #addConfigurationInternalChangeListener(StateChangeListener)} to be notified when it
     * changes.
     */
    @NonNull
    ConfigurationInternal getCurrentUserConfigurationInternal();

    /**
     * Updates the configuration properties that control a device's time behavior.
     *
     * <p>This method returns {@code true} if the configuration was changed, {@code false}
     * otherwise.
     *
     * @param bypassUserPolicyChecks {@code true} for device policy manager use cases where device
     *   policy restrictions that should apply to actual users can be ignored
     */
    boolean updateConfiguration(
            @UserIdInt int userId, @NonNull TimeConfiguration requestedConfiguration,
            boolean bypassUserPolicyChecks);

    /**
     * Returns a snapshot of the configuration that controls time detector behavior for the
     * specified user.
     */
    @NonNull
    ConfigurationInternal getConfigurationInternal(@UserIdInt int userId);
}
