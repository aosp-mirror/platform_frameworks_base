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

package android.app.time;

/**
 * Constants related to the LocationTimeZoneManager service that are used by shell commands and
 * tests.
 *
 * @hide
 */
public final class LocationTimeZoneManager {

    /**
     * The name of the primary location time zone provider, used for shell commands.
     */
    public static final String PRIMARY_PROVIDER_NAME = "primary";

    /**
     * The name of the secondary location time zone provider, used for shell commands.
     */
    public static final String SECONDARY_PROVIDER_NAME = "secondary";

    /**
     * The name of the service for shell commands
     */
    public static final String SHELL_COMMAND_SERVICE_NAME = "location_time_zone_manager";

    /**
     * Shell command that starts the service (after stop).
     */
    public static final String SHELL_COMMAND_START = "start";

    /**
     * Shell command that stops the service.
     */
    public static final String SHELL_COMMAND_STOP = "stop";

    /**
     * Shell command that sends test commands to a provider
     */
    public static final String SHELL_COMMAND_SEND_PROVIDER_TEST_COMMAND =
            "send_provider_test_command";

    /**
     * Simulated provider test command that simulates the bind succeeding.
     */
    public static final String SIMULATED_PROVIDER_TEST_COMMAND_ON_BIND = "on_bind";

    /**
     * Simulated provider test command that simulates the provider unbinding.
     */
    public static final String SIMULATED_PROVIDER_TEST_COMMAND_ON_UNBIND = "on_unbind";

    /**
     * Simulated provider test command that simulates the provider entering the "permanent failure"
     * state.
     */
    public static final String SIMULATED_PROVIDER_TEST_COMMAND_PERM_FAILURE = "perm_fail";

    /**
     * Simulated provider test command that simulates the provider entering the "success" (time
     * zone(s) detected) state.
     */
    public static final String SIMULATED_PROVIDER_TEST_COMMAND_SUCCESS = "success";

    /**
     * Argument for {@link #SIMULATED_PROVIDER_TEST_COMMAND_SUCCESS} to specify TZDB time zone IDs.
     */
    public static final String SIMULATED_PROVIDER_TEST_COMMAND_SUCCESS_ARG_KEY_TZ = "tz";

    /**
     * Simulated provider test command that simulates the provider entering the "uncertain"
     * state.
     */
    public static final String SIMULATED_PROVIDER_TEST_COMMAND_UNCERTAIN = "uncertain";

    private static final String SYSTEM_PROPERTY_KEY_PROVIDER_MODE_OVERRIDE_PREFIX =
            "persist.sys.geotz.";

    /**
     * The name of the system property that can be used to set the primary provider into test mode
     * (value = {@link #SYSTEM_PROPERTY_VALUE_PROVIDER_MODE_SIMULATED}) or disabled (value = {@link
     * #SYSTEM_PROPERTY_VALUE_PROVIDER_MODE_DISABLED}).
     */
    public static final String SYSTEM_PROPERTY_KEY_PROVIDER_MODE_OVERRIDE_PRIMARY =
            SYSTEM_PROPERTY_KEY_PROVIDER_MODE_OVERRIDE_PREFIX + PRIMARY_PROVIDER_NAME;

    /**
     * The name of the system property that can be used to set the secondary provider into test mode
     * (value = {@link #SYSTEM_PROPERTY_VALUE_PROVIDER_MODE_SIMULATED}) or disabled (value = {@link
     * #SYSTEM_PROPERTY_VALUE_PROVIDER_MODE_DISABLED}).
     */
    public static final String SYSTEM_PROPERTY_KEY_PROVIDER_MODE_OVERRIDE_SECONDARY =
            SYSTEM_PROPERTY_KEY_PROVIDER_MODE_OVERRIDE_PREFIX + SECONDARY_PROVIDER_NAME;

    /**
     * The value of the provider mode system property to put a provider into test mode.
     */
    public static final String SYSTEM_PROPERTY_VALUE_PROVIDER_MODE_SIMULATED = "simulated";

    /**
     * The value of the provider mode system property to put a provider into disabled mode.
     */
    public static final String SYSTEM_PROPERTY_VALUE_PROVIDER_MODE_DISABLED = "disabled";

    private LocationTimeZoneManager() {
        // No need to instantiate.
    }
}
