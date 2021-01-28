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
import static android.app.time.LocationTimeZoneManager.PRIMARY_PROVIDER_NAME;
import static android.app.time.LocationTimeZoneManager.PROVIDER_MODE_OVERRIDE_DISABLED;
import static android.app.time.LocationTimeZoneManager.PROVIDER_MODE_OVERRIDE_NONE;
import static android.app.time.LocationTimeZoneManager.PROVIDER_MODE_OVERRIDE_SIMULATED;
import static android.app.time.LocationTimeZoneManager.SECONDARY_PROVIDER_NAME;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_DUMP_STATE;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_RECORD_PROVIDER_STATES;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_SEND_PROVIDER_TEST_COMMAND;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_SET_PROVIDER_MODE_OVERRIDE;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_START;
import static android.app.time.LocationTimeZoneManager.SHELL_COMMAND_STOP;

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
import android.os.Bundle;
import android.os.ShellCommand;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.ProviderStateEnum;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Implements the shell command interface for {@link LocationTimeZoneManagerService}. */
class LocationTimeZoneManagerShellCommand extends ShellCommand {

    private static final List<String> VALID_PROVIDER_NAMES =
            Arrays.asList(PRIMARY_PROVIDER_NAME, SECONDARY_PROVIDER_NAME);

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
            case SHELL_COMMAND_SET_PROVIDER_MODE_OVERRIDE: {
                return runSetProviderModeOverride();
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
        pw.println("Location Time Zone Manager (location_time_zone_manager) commands for tests:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.printf("  %s\n", SHELL_COMMAND_START);
        pw.println("    Starts the location_time_zone_manager, creating time zone providers.");
        pw.printf("  %s\n", SHELL_COMMAND_STOP);
        pw.println("    Stops the location_time_zone_manager, destroying time zone providers.");
        pw.printf("  %s <provider name> <mode>\n", SHELL_COMMAND_SET_PROVIDER_MODE_OVERRIDE);
        pw.println("    Sets a provider into a test mode next time the service started.");
        pw.printf("    Values: %s|%s|%s\n", PROVIDER_MODE_OVERRIDE_NONE,
                PROVIDER_MODE_OVERRIDE_DISABLED, PROVIDER_MODE_OVERRIDE_SIMULATED);
        pw.printf("  %s (true|false)\n", SHELL_COMMAND_RECORD_PROVIDER_STATES);
        pw.printf("    Enables / disables provider state recording mode. See also %s. The default"
                + " state is always \"false\".\n", SHELL_COMMAND_DUMP_STATE);
        pw.println("    Note: When enabled, this mode consumes memory and it is only intended for"
                + " testing.");
        pw.println("     It should be disabled after use, or the device can be rebooted to"
                + " reset the mode to disabled.");
        pw.println("     Disabling (or enabling repeatedly) clears any existing stored states.");
        pw.printf("  %s [%s]\n", SHELL_COMMAND_DUMP_STATE, DUMP_STATE_OPTION_PROTO);
        pw.println("    Dumps Location Time Zone Manager state for tests as text or binary proto"
                + " form.");
        pw.println("    See the LocationTimeZoneManagerServiceStateProto definition for details.");
        pw.printf("  %s <provider name> <test command>\n",
                SHELL_COMMAND_SEND_PROVIDER_TEST_COMMAND);
        pw.println("    Passes a test command to the named provider.");
        pw.println();
        pw.printf("<provider name> = One of %s\n", VALID_PROVIDER_NAMES);
        pw.println();
        pw.printf("%s details:\n", SHELL_COMMAND_SEND_PROVIDER_TEST_COMMAND);
        pw.println();
        pw.println("Provider <test command> encoding:");
        pw.println();
        TestCommand.printShellCommandEncodingHelp(pw);
        pw.println();
        pw.println("Simulated provider mode can be used to test the system server behavior or to"
                + " reproduce bugs without the complexity of using real providers.");
        pw.println();
        pw.println("The test commands for simulated providers are:");
        SimulatedLocationTimeZoneProviderProxy.printTestCommandShellHelp(pw);
        pw.println();
        pw.println("Test commands cannot currently be passed to real provider implementations.");
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

    private int runSetProviderModeOverride() {
        PrintWriter outPrintWriter = getOutPrintWriter();
        try {
            String providerName = getNextArgRequired();
            String modeOverride = getNextArgRequired();
            outPrintWriter.println("Setting provider mode override for " + providerName
                    + " to " + modeOverride);
            mService.setProviderModeOverride(providerName, modeOverride);
        } catch (RuntimeException e) {
            reportError(e);
            return 1;
        }
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

        String providerName;
        TestCommand testCommand;
        try {
            providerName = validateProviderName(getNextArgRequired());
            testCommand = createTestCommandFromNextShellArg();
        } catch (RuntimeException e) {
            reportError(e);
            return 1;
        }

        outPrintWriter.println("Injecting testCommand=" + testCommand
                + " to providerName=" + providerName);
        try {
            Bundle result = mService.handleProviderTestCommand(providerName, testCommand);
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

    @NonNull
    static String validateProviderName(@NonNull String value) {
        if (!VALID_PROVIDER_NAMES.contains(value)) {
            throw new IllegalArgumentException("Unknown provider name=" + value);
        }
        return value;
    }
}
