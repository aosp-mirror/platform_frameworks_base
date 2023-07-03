/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.annotation.Nullable;
import android.app.time.UnixEpochTime;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A time signal from a network time source like NTP.
 *
 * <p>{@code unixEpochTime} is the suggested time. The {@code unixEpochTime.value} is the number of
 * milliseconds elapsed since 1/1/1970 00:00:00 UTC according to the Unix time system. The {@code
 * unixEpochTime.referenceTimeMillis} is the value of the elapsed realtime clock when the {@code
 * unixEpochTime.value} was established. Note that the elapsed realtime clock is considered accurate
 * but it is volatile, so time suggestions cannot be persisted across device resets.
 *
 * <p>{@code uncertaintyMillis} is an indication of error bounds associated the time. This is a
 * positive value, and the correct Unix epoch time is <em>likely</em> to be within the bounds +/-
 * the {@code uncertaintyMillis}. The Unix epoch time is not guaranteed to be within these bounds.
 *
 * <p>{@code debugInfo} contains debugging metadata associated with the suggestion. This is used to
 * record why the suggestion exists and how it was entered. This information exists only to aid in
 * debugging and therefore is used by {@link #toString()}, but it is not for use in detection
 * logic and is not considered in {@link #hashCode()} or {@link #equals(Object)}.
 */
public final class NetworkTimeSuggestion {

    @NonNull private final UnixEpochTime mUnixEpochTime;
    private final int mUncertaintyMillis;
    @Nullable private ArrayList<String> mDebugInfo;

    /**
     * Create a {@link NetworkTimeSuggestion} with the supplied property values.
     *
     * <p>See {@link NetworkTimeSuggestion} for property details.
     */
    public NetworkTimeSuggestion(@NonNull UnixEpochTime unixEpochTime, int uncertaintyMillis) {
        mUnixEpochTime = Objects.requireNonNull(unixEpochTime);
        if (uncertaintyMillis < 0) {
            throw new IllegalArgumentException("uncertaintyMillis < 0");
        }
        mUncertaintyMillis = uncertaintyMillis;
    }

    /** See {@link NetworkTimeSuggestion} for property details. */
    @NonNull
    public UnixEpochTime getUnixEpochTime() {
        return mUnixEpochTime;
    }

    /** See {@link NetworkTimeSuggestion} for property details. */
    public int getUncertaintyMillis() {
        return mUncertaintyMillis;
    }

    /** See {@link NetworkTimeSuggestion} for information about {@code debugInfo}. */
    @NonNull
    public List<String> getDebugInfo() {
        return mDebugInfo == null
                ? Collections.emptyList() : Collections.unmodifiableList(mDebugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging. The
     * information is present in {@link #toString()} but is not considered for {@link
     * #equals(Object)} and {@link #hashCode()}.
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
        if (!(o instanceof NetworkTimeSuggestion)) {
            return false;
        }
        NetworkTimeSuggestion that = (NetworkTimeSuggestion) o;
        return mUnixEpochTime.equals(that.mUnixEpochTime)
                && mUncertaintyMillis == that.mUncertaintyMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUnixEpochTime, mUncertaintyMillis);
    }

    @Override
    public String toString() {
        return "NetworkTimeSuggestion{"
                + "mUnixEpochTime=" + mUnixEpochTime
                + ", mUncertaintyMillis=" + mUncertaintyMillis
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }

    /** Parses command line args to create a {@link NetworkTimeSuggestion}. */
    public static NetworkTimeSuggestion parseCommandLineArg(@NonNull ShellCommand cmd)
            throws IllegalArgumentException {
        Long elapsedRealtimeMillis = null;
        Long unixEpochTimeMillis = null;
        Integer uncertaintyMillis = null;
        String opt;
        while ((opt = cmd.getNextArg()) != null) {
            switch (opt) {
                case "--reference_time":
                case "--elapsed_realtime": {
                    elapsedRealtimeMillis = Long.parseLong(cmd.getNextArgRequired());
                    break;
                }
                case "--unix_epoch_time": {
                    unixEpochTimeMillis = Long.parseLong(cmd.getNextArgRequired());
                    break;
                }
                case "--uncertainty_millis": {
                    uncertaintyMillis = Integer.parseInt(cmd.getNextArgRequired());
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }

        if (elapsedRealtimeMillis == null) {
            throw new IllegalArgumentException("No elapsedRealtimeMillis specified.");
        }
        if (unixEpochTimeMillis == null) {
            throw new IllegalArgumentException("No unixEpochTimeMillis specified.");
        }
        if (uncertaintyMillis == null) {
            throw new IllegalArgumentException("No uncertaintyMillis specified.");
        }

        UnixEpochTime timeSignal = new UnixEpochTime(elapsedRealtimeMillis, unixEpochTimeMillis);
        NetworkTimeSuggestion networkTimeSuggestion =
                new NetworkTimeSuggestion(timeSignal, uncertaintyMillis);
        networkTimeSuggestion.addDebugInfo("Command line injection");
        return networkTimeSuggestion;
    }

    /** Prints the command line args needed to create a {@link NetworkTimeSuggestion}. */
    public static void printCommandLineOpts(PrintWriter pw) {
        pw.printf("%s suggestion options:\n", "Network");
        pw.println("  --elapsed_realtime <elapsed realtime millis> - the elapsed realtime millis"
                + " when unix epoch time was read");
        pw.println("  --unix_epoch_time <Unix epoch time millis>");
        pw.println("  --uncertainty_millis <Uncertainty millis> - a positive error bound (+/-)"
                + " estimate for unix epoch time");
        pw.println();
        pw.println("See " + NetworkTimeSuggestion.class.getName() + " for more information");
    }
}
