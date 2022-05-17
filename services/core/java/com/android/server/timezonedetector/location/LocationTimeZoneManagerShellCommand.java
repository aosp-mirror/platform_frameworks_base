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
package com.android.server.timezonedetector.location;

import static android.app.time.LocationTimeZoneManager.DUMP_STATE_OPTION_PROTO;
import static android.app.time.LocationTimeZoneManager.NULL_PACKAGE_NAME_TOKEN;
import static android.app.time.LocationTimeZoneManager.SERVICE_NAME;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_CLEAR_RECORDED_PROVIDER_STATES;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_DUMP_STATE;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_START;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_START_WITH_TEST_PROVIDERS;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_STOP;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEM_TIME;

import static com.android.server.timedetector.ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS;
import static com.android.server.timedetector.ServerFlags.KEY_LTZP_EVENT_FILTERING_AGE_THRESHOLD_MILLIS;
import static com.android.server.timedetector.ServerFlags.KEY_LTZP_INITIALIZATION_TIMEOUT_FUZZ_MILLIS;
import static com.android.server.timedetector.ServerFlags.KEY_LTZP_INITIALIZATION_TIMEOUT_MILLIS;
import static com.android.server.timedetector.ServerFlags.KEY_PRIMARY_LTZP_MODE_OVERRIDE;
import static com.android.server.timedetector.ServerFlags.KEY_SECONDARY_LTZP_MODE_OVERRIDE;
import static com.android.server.timezonedetector.ServiceConfigAccessor.PROVIDER_MODE_DISABLED;
import static com.android.server.timezonedetector.ServiceConfigAccessor.PROVIDER_MODE_ENABLED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_UNKNOWN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_PROVIDERS_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_STOPPED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.time.GeolocationTimeZoneSuggestionProto;
import android.app.time.LocationTimeZoneManagerProto;
import android.app.time.LocationTimeZoneManagerServiceStateProto;
import android.app.time.TimeZoneProviderStateProto;
import android.app.timezonedetector.TimeZoneDetector;
import android.os.ShellCommand;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.ProviderStateEnum;
import com.android.server.timezonedetector.location.LocationTimeZoneProviderController.State;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/** Implements the shell command interface for {@link LocationTimeZoneManagerService}. */
class LocationTimeZoneManagerShellCommand extends ShellCommand {

    private final LocationTimeZoneManagerService mService;

    LocationTimeZoneManagerShellCommand(LocationTimeZoneManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case SHELL_COMMAND_START: {
                return runStart();
            }
            case SHELL_COMMAND_START_WITH_TEST_PROVIDERS: {
                return runStartWithTestProviders();
            }
            case SHELL_COMMAND_STOP: {
                return runStop();
            }
            case SHELL_COMMAND_CLEAR_RECORDED_PROVIDER_STATES: {
                return runClearRecordedProviderStates();
            }
            case SHELL_COMMAND_DUMP_STATE: {
                return runDumpControllerState();
            }
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.printf("Location Time Zone Manager (%s) commands for tests:\n", SERVICE_NAME);
        pw.printf("  help\n");
        pw.printf("    Print this help text.\n");
        pw.printf("  %s\n", SHELL_COMMAND_START);
        pw.printf("    Starts the service, creating location time zone providers.\n");
        pw.printf("  %s <primary package name|%2$s> <secondary package name|%2$s>"
                        + " <record states>\n",
                SHELL_COMMAND_START_WITH_TEST_PROVIDERS, NULL_PACKAGE_NAME_TOKEN);
        pw.printf("    Starts the service with test provider packages configured / provider"
                + " permission checks disabled.\n");
        pw.printf("    <record states> - true|false, determines whether state recording is enabled."
                + "\n");
        pw.printf("    See %s and %s.\n", SHELL_COMMAND_DUMP_STATE,
                SHELL_COMMAND_CLEAR_RECORDED_PROVIDER_STATES);
        pw.printf("  %s\n", SHELL_COMMAND_STOP);
        pw.printf("    Stops the service, destroying location time zone providers.\n");
        pw.printf("  %s\n", SHELL_COMMAND_CLEAR_RECORDED_PROVIDER_STATES);
        pw.printf("    Clears recorded provider state. See also %s and %s.\n",
                SHELL_COMMAND_START_WITH_TEST_PROVIDERS, SHELL_COMMAND_DUMP_STATE);
        pw.printf("    Note: This is only intended for use during testing.\n");
        pw.printf("  %s [%s]\n", SHELL_COMMAND_DUMP_STATE, DUMP_STATE_OPTION_PROTO);
        pw.printf("    Dumps service state for tests as text or binary proto form.\n");
        pw.printf("    See the LocationTimeZoneManagerServiceStateProto definition for details.\n");
        pw.println();
        pw.printf("This service is also affected by the following device_config flags in the"
                + " %s namespace:\n", NAMESPACE_SYSTEM_TIME);
        pw.printf("  %s\n", KEY_PRIMARY_LTZP_MODE_OVERRIDE);
        pw.printf("    Overrides the mode of the primary provider. Values=%s|%s\n",
                PROVIDER_MODE_DISABLED, PROVIDER_MODE_ENABLED);
        pw.printf("  %s\n", KEY_SECONDARY_LTZP_MODE_OVERRIDE);
        pw.printf("    Overrides the mode of the secondary provider. Values=%s|%s\n",
                PROVIDER_MODE_DISABLED, PROVIDER_MODE_ENABLED);
        pw.printf("  %s\n", KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS);
        pw.printf("    Sets the amount of time the service waits when uncertain before making an"
                + " 'uncertain' suggestion to the time zone detector.\n");
        pw.printf("  %s\n", KEY_LTZP_INITIALIZATION_TIMEOUT_MILLIS);
        pw.printf("    Sets the initialization time passed to the providers.\n");
        pw.printf("  %s\n", KEY_LTZP_INITIALIZATION_TIMEOUT_FUZZ_MILLIS);
        pw.printf("    Sets the amount of extra time added to the providers' initialization time."
                + "\n");
        pw.printf("  %s\n", KEY_LTZP_EVENT_FILTERING_AGE_THRESHOLD_MILLIS);
        pw.printf("    Sets the amount of time that must pass between equivalent LTZP events before"
                + " they will be reported to the system server.\n");
        pw.println();
        pw.printf("Typically, use '%s' to stop the service before setting individual"
                + " flags and '%s' after to restart it.\n",
                SHELL_COMMAND_STOP, SHELL_COMMAND_START);
        pw.println();
        pw.printf("See \"adb shell cmd device_config\" for more information on setting flags.\n");
        pw.println();
        pw.printf("Also see \"adb shell cmd %s help\" for higher-level location time zone"
                + " commands / settings.\n", TimeZoneDetector.SHELL_COMMAND_SERVICE_NAME);
        pw.println();
    }

    private int runStart() {
        try {
            mService.start();
        } catch (RuntimeException e) {
            reportError(e);
            return 1;
        }
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Service started");
        return 0;
    }

    private int runStartWithTestProviders() {
        String testPrimaryProviderPackageName = parseProviderPackageName(getNextArgRequired());
        String testSecondaryProviderPackageName = parseProviderPackageName(getNextArgRequired());
        boolean recordProviderStateChanges = Boolean.parseBoolean(getNextArgRequired());

        try {
            mService.startWithTestProviders(testPrimaryProviderPackageName,
                    testSecondaryProviderPackageName, recordProviderStateChanges);
        } catch (RuntimeException e) {
            reportError(e);
            return 1;
        }
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Service started (test mode)");
        return 0;
    }

    private int runStop() {
        try {
            mService.stop();
        } catch (RuntimeException e) {
            reportError(e);
            return 1;
        }
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Service stopped");
        return 0;
    }

    private int runClearRecordedProviderStates() {
        try {
            mService.clearRecordedProviderStates();
        } catch (IllegalStateException e) {
            reportError(e);
            return 2;
        }
        return 0;
    }

    private int runDumpControllerState() {
        LocationTimeZoneManagerServiceState state;
        try {
            state = mService.getStateForTests();
        } catch (RuntimeException e) {
            reportError(e);
            return 1;
        }

        if (state == null) {
            // Controller is stopped.
            return 0;
        }

        DualDumpOutputStream outputStream;
        boolean useProto = Objects.equals(DUMP_STATE_OPTION_PROTO, getNextOption());
        if (useProto) {
            FileDescriptor outFd = getOutFileDescriptor();
            outputStream = new DualDumpOutputStream(new ProtoOutputStream(outFd));
        } else {
            outputStream = new DualDumpOutputStream(
                    new IndentingPrintWriter(getOutPrintWriter(), "  "));
        }
        if (state.getLastSuggestion() != null) {
            GeolocationTimeZoneSuggestion lastSuggestion = state.getLastSuggestion();
            long lastSuggestionToken = outputStream.start(
                    "last_suggestion", LocationTimeZoneManagerServiceStateProto.LAST_SUGGESTION);
            for (String zoneId : lastSuggestion.getZoneIds()) {
                outputStream.write(
                        "zone_ids" , GeolocationTimeZoneSuggestionProto.ZONE_IDS, zoneId);
            }
            for (String debugInfo : lastSuggestion.getDebugInfo()) {
                outputStream.write(
                        "debug_info", GeolocationTimeZoneSuggestionProto.DEBUG_INFO, debugInfo);
            }
            outputStream.end(lastSuggestionToken);
        }

        writeControllerStates(outputStream, state.getControllerStates());
        writeProviderStates(outputStream, state.getPrimaryProviderStates(),
                "primary_provider_states",
                LocationTimeZoneManagerServiceStateProto.PRIMARY_PROVIDER_STATES);
        writeProviderStates(outputStream, state.getSecondaryProviderStates(),
                "secondary_provider_states",
                LocationTimeZoneManagerServiceStateProto.SECONDARY_PROVIDER_STATES);
        outputStream.flush();

        return 0;
    }

    private static void writeControllerStates(DualDumpOutputStream outputStream,
            List<@State String> states) {
        for (@State String state : states) {
            outputStream.write("controller_states",
                    LocationTimeZoneManagerServiceStateProto.CONTROLLER_STATES,
                    convertControllerStateToProtoEnum(state));
        }
    }

    private static int convertControllerStateToProtoEnum(@State String state) {
        switch (state) {
            case STATE_PROVIDERS_INITIALIZING:
                return LocationTimeZoneManagerProto.CONTROLLER_STATE_PROVIDERS_INITIALIZING;
            case STATE_STOPPED:
                return LocationTimeZoneManagerProto.CONTROLLER_STATE_STOPPED;
            case STATE_INITIALIZING:
                return LocationTimeZoneManagerProto.CONTROLLER_STATE_INITIALIZING;
            case STATE_UNCERTAIN:
                return LocationTimeZoneManagerProto.CONTROLLER_STATE_UNCERTAIN;
            case STATE_CERTAIN:
                return LocationTimeZoneManagerProto.CONTROLLER_STATE_CERTAIN;
            case STATE_FAILED:
                return LocationTimeZoneManagerProto.CONTROLLER_STATE_FAILED;
            case STATE_DESTROYED:
                return LocationTimeZoneManagerProto.CONTROLLER_STATE_DESTROYED;
            case STATE_UNKNOWN:
            default:
                return LocationTimeZoneManagerProto.CONTROLLER_STATE_UNKNOWN;
        }
    }

    private static void writeProviderStates(DualDumpOutputStream outputStream,
            List<LocationTimeZoneProvider.ProviderState> providerStates, String fieldName,
            long fieldId) {
        for (LocationTimeZoneProvider.ProviderState providerState : providerStates) {
            long providerStateToken = outputStream.start(fieldName, fieldId);
            outputStream.write("state", TimeZoneProviderStateProto.STATE,
                    convertProviderStateEnumToProtoEnum(providerState.stateEnum));
            outputStream.end(providerStateToken);
        }
    }

    private static int convertProviderStateEnumToProtoEnum(@ProviderStateEnum int stateEnum) {
        switch (stateEnum) {
            case PROVIDER_STATE_UNKNOWN:
                return LocationTimeZoneManagerProto.TIME_ZONE_PROVIDER_STATE_UNKNOWN;
            case PROVIDER_STATE_STARTED_INITIALIZING:
                return LocationTimeZoneManagerProto.TIME_ZONE_PROVIDER_STATE_INITIALIZING;
            case PROVIDER_STATE_STARTED_CERTAIN:
                return LocationTimeZoneManagerProto.TIME_ZONE_PROVIDER_STATE_CERTAIN;
            case PROVIDER_STATE_STARTED_UNCERTAIN:
                return LocationTimeZoneManagerProto.TIME_ZONE_PROVIDER_STATE_UNCERTAIN;
            case PROVIDER_STATE_STOPPED:
                return LocationTimeZoneManagerProto.TIME_ZONE_PROVIDER_STATE_DISABLED;
            case PROVIDER_STATE_PERM_FAILED:
                return LocationTimeZoneManagerProto.TIME_ZONE_PROVIDER_STATE_PERM_FAILED;
            case PROVIDER_STATE_DESTROYED:
                return LocationTimeZoneManagerProto.TIME_ZONE_PROVIDER_STATE_DESTROYED;
            default: {
                throw new IllegalArgumentException("Unknown stateEnum=" + stateEnum);
            }
        }
    }

    private void reportError(@NonNull Throwable e) {
        PrintWriter errPrintWriter = getErrPrintWriter();
        errPrintWriter.println("Error: ");
        e.printStackTrace(errPrintWriter);
    }

    @Nullable
    private static String parseProviderPackageName(@NonNull String providerPackageNameString) {
        if (providerPackageNameString.equals(NULL_PACKAGE_NAME_TOKEN)) {
            return null;
        }
        return providerPackageNameString;
    }
}
