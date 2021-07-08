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
    public static final String SERVICE_NAME = "location_time_zone_manager";

    /**
     * A shell command that starts the service (after stop).
     */
    public static final String SHELL_COMMAND_START = "start";

    /**
     * A shell command that stops the service.
     */
    public static final String SHELL_COMMAND_STOP = "stop";

    /**
     * A shell command that clears recorded provider state information during tests.
     */
    public static final String SHELL_COMMAND_CLEAR_RECORDED_PROVIDER_STATES =
            "clear_recorded_provider_states";

    /**
     * A shell command that tells the service to dump its current state.
     */
    public static final String SHELL_COMMAND_DUMP_STATE = "dump_state";

    /**
     * Option for {@link #SHELL_COMMAND_DUMP_STATE} that tells it to dump state as a binary proto.
     */
    public static final String DUMP_STATE_OPTION_PROTO = "--proto";

    /** A shell command that starts the location_time_zone_manager with named test providers. */
    public static final String SHELL_COMMAND_START_WITH_TEST_PROVIDERS =
            "start_with_test_providers";

    /**
     * The token that can be passed to {@link #SHELL_COMMAND_START_WITH_TEST_PROVIDERS} to indicate
     * there is no provider.
     */
    public static final String NULL_PACKAGE_NAME_TOKEN = "@null";

    private LocationTimeZoneManager() {
        // No need to instantiate.
    }
}
