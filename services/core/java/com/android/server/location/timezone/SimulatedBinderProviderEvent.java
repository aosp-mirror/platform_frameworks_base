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

package com.android.server.location.timezone;

import static com.android.server.location.timezone.LocationTimeZoneManagerService.PRIMARY_PROVIDER_NAME;
import static com.android.server.location.timezone.LocationTimeZoneManagerService.SECONDARY_PROVIDER_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.service.timezone.TimeZoneProviderSuggestion;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An event used for simulating real binder proxy behavior using a {@link
 * SimulatedLocationTimeZoneProviderProxy}.
 */
final class SimulatedBinderProviderEvent {

    private static final List<String> VALID_PROVIDER_NAMES =
            Arrays.asList(PRIMARY_PROVIDER_NAME, SECONDARY_PROVIDER_NAME);

    static final int INJECTED_EVENT_TYPE_ON_BIND = 1;
    static final int INJECTED_EVENT_TYPE_ON_UNBIND = 2;
    static final int INJECTED_EVENT_TYPE_LOCATION_TIME_ZONE_EVENT = 3;


    @NonNull private final String mProviderName;
    private final int mType;
    @Nullable private final TimeZoneProviderEvent mTimeZoneProviderEvent;

    private SimulatedBinderProviderEvent(@NonNull String providerName, int eventType,
            @Nullable TimeZoneProviderEvent timeZoneProviderEvent) {
        this.mProviderName = Objects.requireNonNull(providerName);
        this.mType = eventType;
        this.mTimeZoneProviderEvent = timeZoneProviderEvent;
    }

    @NonNull
    String getProviderName() {
        return mProviderName;
    }

    @Nullable
    TimeZoneProviderEvent getTimeZoneProviderEvent() {
        return mTimeZoneProviderEvent;
    }

    int getType() {
        return mType;
    }

    /** Prints the command line options that {@link #createFromArgs(ShellCommand)} understands. */
    static void printCommandLineOpts(PrintWriter pw) {
        pw.println("Simulated provider binder event:");
        pw.println();
        pw.println("<provider name> [onBind|onUnbind|timeZoneProviderEvent"
                + " <location time zone event args>]");
        pw.println();
        pw.println("<provider name> = " + VALID_PROVIDER_NAMES);
        pw.println("<time zone provider event args> ="
                + " [PERMANENT_FAILURE|UNCERTAIN|SUGGESTION <time zone ids>*]");
    }

    /**
     * Constructs a {@link SimulatedBinderProviderEvent} from the arguments of {@code shellCommand}.
     */
    static SimulatedBinderProviderEvent createFromArgs(ShellCommand shellCommand) {
        String providerName = shellCommand.getNextArgRequired();
        if (!VALID_PROVIDER_NAMES.contains(providerName)) {
            throw new IllegalArgumentException("Unknown provider name=" + providerName);
        }
        String injectedEvent = shellCommand.getNextArgRequired();
        switch (injectedEvent) {
            case "onBind": {
                return new SimulatedBinderProviderEvent(
                        providerName, INJECTED_EVENT_TYPE_ON_BIND, null);
            }
            case "onUnbind": {
                return new SimulatedBinderProviderEvent(
                        providerName, INJECTED_EVENT_TYPE_ON_UNBIND, null);
            }
            case "timeZoneProviderEvent": {
                TimeZoneProviderEvent event = parseTimeZoneProviderEventArgs(shellCommand);
                return new SimulatedBinderProviderEvent(providerName,
                        INJECTED_EVENT_TYPE_LOCATION_TIME_ZONE_EVENT, event);
            }
            default: {
                throw new IllegalArgumentException("Unknown simulated event type=" + injectedEvent);
            }
        }
    }

    private static TimeZoneProviderEvent parseTimeZoneProviderEventArgs(ShellCommand shellCommand) {
        TimeZoneProviderEvent event;
        String eventTypeString = shellCommand.getNextArgRequired();
        switch (eventTypeString.toUpperCase()) {
            case "PERMANENT_FAILURE": {
                event = TimeZoneProviderEvent.createPermanentFailureEvent("Simulated");
                break;
            }
            case "UNCERTAIN": {
                event = TimeZoneProviderEvent.createUncertainEvent();
                break;
            }
            case "SUGGESTION": {
                TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                        .setElapsedRealtimeMillis(SystemClock.elapsedRealtime())
                        .setTimeZoneIds(parseTimeZoneArgs(shellCommand))
                        .build();
                event = TimeZoneProviderEvent.createSuggestionEvent(suggestion);
                break;
            }
            default: {
                throw new IllegalArgumentException("Error: Unknown eventType: " + eventTypeString);
            }
        }
        return event;
    }

    private static List<String> parseTimeZoneArgs(ShellCommand shellCommand) {
        List<String> timeZoneIds = new ArrayList<>();
        String timeZoneId;
        while ((timeZoneId = shellCommand.getNextArg()) != null) {
            timeZoneIds.add(timeZoneId);
        }
        return timeZoneIds;
    }

    @Override
    public String toString() {
        return "SimulatedBinderProviderEvent{"
                + "mProviderName=" + mProviderName
                + ", mType=" + mType
                + ", mTimeZoneProviderEvent=" + mTimeZoneProviderEvent
                + '}';
    }
}
