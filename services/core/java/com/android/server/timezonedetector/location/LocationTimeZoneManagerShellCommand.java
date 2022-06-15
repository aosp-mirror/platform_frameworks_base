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
import static android.app.time.LocationTimeZoneManager.SERVICE_NAME;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_DUMP_STATE;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_RECORD_PROVIDER_STATES;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_SEND_PROVIDER_TEST_COMMAND;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_START;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_STOP;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEM_TIME;

import static com.android.server.timedetector.ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS;
import static com.android.server.timedetector.ServerFlags.KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ_MILLIS;
import static com.android.server.timedetector.ServerFlags.KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_MILLIS;
import static com.android.server.timedetector.ServerFlags.KEY_PRIMARY_LOCATION_TIME_ZONE_PROVIDER_MODE_OVERRIDE;
import static com.android.server.timedetector.ServerFlags.KEY_SECONDARY_LOCATION_TIME_ZONE_PROVIDER_MODE_OVERRIDE;
import static com.android.server.timezonedetector.ServiceConfigAccessor.PROVIDER_MODE_DISABLED;
import static com.android.server.timezonedetector.ServiceConfigAccessor.PROVIDER_MODE_ENABLED;
import static com.android.server.timezonedetector.ServiceConfigAccessor.PROVIDER_MODE_SIMULATED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_UNKNOWN;

import android.annotation.NonNull;
import android.app.time.GeolocationTimeZoneSuggestionProto;
import android.app.time.LocationTimeZoneManagerProto;
import android.app.time.LocationTimeZoneManagerServiceStateProto;
import android.app.time.TimeZoneProviderStateProto;
import android.app.timezonedetector.TimeZoneDetector;
import android.os.Bundle;
import android.os.ShellCommand;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.ProviderStateEnum;

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
            case SHELL_COMMAND_STOP: {
                return runStop();
            }
            case SHELL_COMMAND_SEND_PROVIDER_TEST_COMMAND: {
                return runSendProviderTestCommand();
            }
            case SHELL_COMMAND_RECORD_PROVIDER_STATES: {
                return runRecordProviderStates();
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
        pw.printf("  %s\n", SHELL_COMMAND_STOP);
        pw.printf("    Stops the service, destroying location time zone providers.\n");
        pw.printf("  %s (true|false)\n", SHELL_COMMAND_RECORD_PROVIDER_STATES);
        pw.printf("    Enables / disables provider state recording mode. See also %s. The default"
                + " state is always \"false\".\n", SHELL_COMMAND_DUMP_STATE);
        pw.printf("    Note: When enabled, this mode consumes memory and it is only intended for"
                + " testing.\n");
        pw.printf("    It should be disabled after use, or the device can be rebooted to"
                + " reset the mode to disabled.\n");
        pw.printf("    Disabling (or enabling repeatedly) clears any existing stored states.\n");
        pw.printf("  %s [%s]\n", SHELL_COMMAND_DUMP_STATE, DUMP_STATE_OPTION_PROTO);
        pw.printf("    Dumps service state for tests as text or binary proto form.\n");
        pw.printf("    See the LocationTimeZoneManagerServiceStateProto definition for details.\n");
        pw.printf("  %s <provider index> <test command>\n",
                SHELL_COMMAND_SEND_PROVIDER_TEST_COMMAND);
        pw.printf("    Passes a test command to the named provider.\n");
        pw.println();
        pw.printf("<provider index> = 0 (primary), 1 (secondary)\n");
        pw.println();
        pw.printf("%s details:\n", SHELL_COMMAND_SEND_PROVIDER_TEST_COMMAND);
        pw.println();
        pw.printf("Provider <test command> encoding:\n");
        pw.println();
        TestCommand.printShellCommandEncodingHelp(pw);
        pw.println();
        pw.printf("Simulated provider mode can be used to test the system server behavior or to"
                + " reproduce bugs without the complexity of using real providers.\n");
        pw.println();
        pw.printf("The test commands for simulated providers are:\n");
        SimulatedLocationTimeZoneProviderProxy.printTestCommandShellHelp(pw);
        pw.println();
        pw.printf("Test commands cannot currently be passed to real provider implementations.\n");
        pw.println();
        pw.printf("This service is also affected by the following device_config flags in the"
                + " %s namespace:\n", NAMESPACE_SYSTEM_TIME);
        pw.printf("  %s\n", KEY_PRIMARY_LOCATION_TIME_ZONE_PROVIDER_MODE_OVERRIDE);
        pw.printf("    Overrides the mode of the primary provider. Values=%s|%s|%s\n",
                PROVIDER_MODE_DISABLED, PROVIDER_MODE_ENABLED, PROVIDER_MODE_SIMULATED);
        pw.printf("  %s\n", KEY_SECONDARY_LOCATION_TIME_ZONE_PROVIDER_MODE_OVERRIDE);
        pw.printf("    Overrides the mode of the secondary provider. Values=%s|%s|%s\n",
                PROVIDER_MODE_DISABLED, PROVIDER_MODE_ENABLED, PROVIDER_MODE_SIMULATED);
        pw.printf("  %s\n", KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS);
        pw.printf("    Sets the amount of time the service waits when uncertain before making an"
                + " 'uncertain' suggestion to the time zone detector.\n");
        pw.printf("  %s\n", KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_MILLIS);
        pw.printf("    Sets the initialization time passed to the providers.\n");
        pw.printf("  %s\n", KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ_MILLIS);
        pw.printf("    Sets the amount of extra time added to the providers' initialization time."
                + "\n");
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

    private int runRecordProviderStates() {
        PrintWriter outPrintWriter = getOutPrintWriter();
        boolean enabled;
        try {
            String nextArg = getNextArgRequired();
            enabled = Boolean.parseBoolean(nextArg);
        } catch (RuntimeException e) {
            reportError(e);
            return 1;
        }

        outPrintWriter.println("Setting provider state recording to " + enabled);
        try {
            mService.setProviderStateRecordingEnabled(enabled);
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

        writeProviderStates(outputStream, state.getPrimaryProviderStates(),
                "primary_provider_states",
                LocationTimeZoneManagerServiceStateProto.PRIMARY_PROVIDER_STATES);
        writeProviderStates(outputStream, state.getSecondaryProviderStates(),
                "secondary_provider_states",
                LocationTimeZoneManagerServiceStateProto.SECONDARY_PROVIDER_STATES);
        outputStream.flush();

        return 0;
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

    private int runSendProviderTestCommand() {
        PrintWriter outPrintWriter = getOutPrintWriter();

        int providerIndex;
        TestCommand testCommand;
        try {
            providerIndex = parseProviderIndex(getNextArgRequired());
            testCommand = createTestCommandFromNextShellArg();
        } catch (RuntimeException e) {
            reportError(e);
            return 1;
        }

        outPrintWriter.println("Injecting testCommand=" + testCommand
                + " to providerIndex=" + providerIndex);
        try {
            Bundle result = mService.handleProviderTestCommand(providerIndex, testCommand);
            outPrintWriter.println(result);
        } catch (RuntimeException e) {
            reportError(e);
            return 2;
        }
        return 0;
    }

    @NonNull
    private TestCommand createTestCommandFromNextShellArg() {
        return TestCommand.createFromShellCommandArgs(this);
    }

    private void reportError(Throwable e) {
        PrintWriter errPrintWriter = getErrPrintWriter();
        errPrintWriter.println("Error: ");
        e.printStackTrace(errPrintWriter);
    }

    private static int parseProviderIndex(@NonNull String providerIndexString) {
        int providerIndex = Integer.parseInt(providerIndexString);
        if (providerIndex < 0 || providerIndex > 1) {
            throw new IllegalArgumentException(providerIndexString);
        }
        return providerIndex;
    }
}
