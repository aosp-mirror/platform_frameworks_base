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

package com.android.server.timezonedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.os.ShellCommand;
import android.os.SystemClock;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringTokenizer;

/**
 * An event from the location_time_zone_manager service (AKA the location-based time zone detection
 * algorithm). An event can represent a new time zone recommendation, an algorithm status change, or
 * both.
 *
 * <p>Events have the following properties:
 *
 * <ul>
 *     <li>{@code algorithmStatus}: The current status of the location-based time zone detection
 *     algorithm.</li>
 *     <li>{@code suggestion}: The latest time zone suggestion, if there is one.</li>
 *     <li>{@code debugInfo} contains debugging metadata associated with the suggestion. This is
 *     used to record why the event exists and how information contained within it was obtained.
 *     This information exists only to aid in debugging and therefore is used by
 *     {@link #toString()}, but it is not for use in detection logic and is not considered in
 *     {@link #hashCode()} or {@link #equals(Object)}.
 *     </li>
 * </ul>
 */
public final class LocationAlgorithmEvent {

    @NonNull private final LocationTimeZoneAlgorithmStatus mAlgorithmStatus;
    @Nullable private final GeolocationTimeZoneSuggestion mSuggestion;
    @Nullable private ArrayList<String> mDebugInfo;

    /** Creates a new instance. */
    public LocationAlgorithmEvent(
            @NonNull LocationTimeZoneAlgorithmStatus algorithmStatus,
            @Nullable GeolocationTimeZoneSuggestion suggestion) {
        mAlgorithmStatus = Objects.requireNonNull(algorithmStatus);
        mSuggestion = suggestion;
    }

    /**
     * Returns the status of the location time zone detector algorithm.
     */
    @NonNull
    public LocationTimeZoneAlgorithmStatus getAlgorithmStatus() {
        return mAlgorithmStatus;
    }

    /**
     * Returns the latest location algorithm suggestion. See {@link LocationAlgorithmEvent} for
     * details.
     */
    @Nullable
    public GeolocationTimeZoneSuggestion getSuggestion() {
        return mSuggestion;
    }

    /** Returns debug information. See {@link LocationAlgorithmEvent} for details. */
    @NonNull
    public List<String> getDebugInfo() {
        return mDebugInfo == null
                ? Collections.emptyList() : Collections.unmodifiableList(mDebugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging. The
     * information is present in {@link #toString()} but is not considered for
     * {@link #equals(Object)} and {@link #hashCode()}.
     */
    public void addDebugInfo(String... debugInfos) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>();
        }
        mDebugInfo.addAll(Arrays.asList(debugInfos));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LocationAlgorithmEvent that = (LocationAlgorithmEvent) o;
        return mAlgorithmStatus.equals(that.mAlgorithmStatus)
                && Objects.equals(mSuggestion, that.mSuggestion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAlgorithmStatus, mSuggestion);
    }

    @Override
    public String toString() {
        return "LocationAlgorithmEvent{"
                + "mAlgorithmStatus=" + mAlgorithmStatus
                + ", mSuggestion=" + mSuggestion
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }

    static LocationAlgorithmEvent parseCommandLineArg(@NonNull ShellCommand cmd) {
        String suggestionString = null;
        LocationTimeZoneAlgorithmStatus algorithmStatus = null;
        String opt;
        while ((opt = cmd.getNextArg()) != null) {
            switch (opt) {
                case "--status": {
                    algorithmStatus = LocationTimeZoneAlgorithmStatus.parseCommandlineArg(
                            cmd.getNextArgRequired());
                    break;
                }
                case "--suggestion": {
                    suggestionString  = cmd.getNextArgRequired();
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }

        if (algorithmStatus == null) {
            throw new IllegalArgumentException("Missing --status");
        }

        GeolocationTimeZoneSuggestion suggestion = null;
        if (suggestionString != null) {
            List<String> zoneIds = parseZoneIds(suggestionString);
            long elapsedRealtimeMillis = SystemClock.elapsedRealtime();
            if (zoneIds == null) {
                suggestion = GeolocationTimeZoneSuggestion.createUncertainSuggestion(
                        elapsedRealtimeMillis);
            } else {
                suggestion = GeolocationTimeZoneSuggestion.createCertainSuggestion(
                        elapsedRealtimeMillis, zoneIds);
            }
        }

        LocationAlgorithmEvent event = new LocationAlgorithmEvent(algorithmStatus, suggestion);
        event.addDebugInfo("Command line injection");
        return event;
    }

    private static List<String> parseZoneIds(String zoneIdsString) {
        if ("UNCERTAIN".equals(zoneIdsString)) {
            return null;
        } else if ("EMPTY".equals(zoneIdsString)) {
            return Collections.emptyList();
        } else {
            ArrayList<String> zoneIds = new ArrayList<>();
            StringTokenizer tokenizer = new StringTokenizer(zoneIdsString, ",");
            while (tokenizer.hasMoreTokens()) {
                zoneIds.add(tokenizer.nextToken());
            }
            return zoneIds;
        }
    }

    static void printCommandLineOpts(@NonNull PrintWriter pw) {
        pw.println("Location algorithm event options:");
        pw.println("  --status {LocationTimeZoneAlgorithmStatus toString() format}");
        pw.println("  [--suggestion {UNCERTAIN|EMPTY|<Olson ID>+}]");
        pw.println();
        pw.println("See " + LocationAlgorithmEvent.class.getName() + " for more information");
    }
}
