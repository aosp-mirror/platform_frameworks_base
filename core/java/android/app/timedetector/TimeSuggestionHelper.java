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

package android.app.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.time.UnixEpochTime;
import android.os.Parcel;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A delegate class to support time suggestion classes that could diverge in the future. This class
 * exists purely for code re-use and provides support methods. It avoids class inheritance
 * deliberately to allow each suggestion to evolve in different directions later without affecting
 * SDK APIs.
 *
 * <p>{@code unixEpochTime} is the suggested time. The {@code unixEpochTime.value} is the number of
 * milliseconds elapsed since 1/1/1970 00:00:00 UTC according to the Unix time system. The {@code
 * unixEpochTime.referenceTimeMillis} is the value of the elapsed realtime clock when the {@code
 * unixEpochTime.value} was established. Note that the elapsed realtime clock is considered accurate
 * but it is volatile, so time suggestions cannot be persisted across device resets.
 *
 * <p>{@code debugInfo} contains debugging metadata associated with the suggestion. This is used to
 * record why the suggestion exists and how it was entered. This information exists only to aid in
 * debugging and therefore is used by {@link #toString()}, but it is not for use in detection
 * logic and is not considered in {@link #hashCode()} or {@link #equals(Object)}.
 *
 * @hide
 */
public final class TimeSuggestionHelper {

    @NonNull private final Class<?> mHelpedClass;
    @NonNull private final UnixEpochTime mUnixEpochTime;
    @Nullable private ArrayList<String> mDebugInfo;

    /** Creates a helper for the specified class, containing the supplied properties. */
    public TimeSuggestionHelper(@NonNull Class<?> helpedClass,
            @NonNull UnixEpochTime unixEpochTime) {
        mHelpedClass = Objects.requireNonNull(helpedClass);
        mUnixEpochTime = Objects.requireNonNull(unixEpochTime);
    }

    /** See {@link TimeSuggestionHelper} for property details. */
    @NonNull
    public UnixEpochTime getUnixEpochTime() {
        return mUnixEpochTime;
    }

    /** See {@link TimeSuggestionHelper} for information about {@code debugInfo}. */
    @NonNull
    public List<String> getDebugInfo() {
        return mDebugInfo == null
                ? Collections.emptyList() : Collections.unmodifiableList(mDebugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging.
     *
     * <p>See {@link TimeSuggestionHelper} for more information about {@code debugInfo}.
     */
    public void addDebugInfo(@NonNull String debugInfo) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>();
        }
        mDebugInfo.add(debugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging. The
     * information is present in {@link #toString()} but is not considered for
     * {@link #equals(Object)} and {@link #hashCode()}.
     */
    public void addDebugInfo(String... debugInfos) {
        addDebugInfo(Arrays.asList(debugInfos));
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging.
     *
     * <p>See {@link TimeSuggestionHelper} for more information about {@code debugInfo}.
     */
    public void addDebugInfo(@NonNull List<String> debugInfo) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>(debugInfo.size());
        }
        mDebugInfo.addAll(debugInfo);
    }

    /**
     * Implemented in case users call this insteam of {@link #handleEquals(TimeSuggestionHelper)}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeSuggestionHelper that = (TimeSuggestionHelper) o;
        return handleEquals(that);
    }

    /** Used to implement {@link Object#equals(Object)}. */
    public boolean handleEquals(TimeSuggestionHelper o) {
        return Objects.equals(mHelpedClass, o.mHelpedClass)
                && Objects.equals(mUnixEpochTime, o.mUnixEpochTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUnixEpochTime);
    }

    /** Used to implement {@link Object#toString()}. */
    public String handleToString() {
        return mHelpedClass.getSimpleName() + "{"
                + "mUnixEpochTime=" + mUnixEpochTime
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }

    /** Constructs a helper with suggestion state from a Parcel. */
    public static TimeSuggestionHelper handleCreateFromParcel(@NonNull Class<?> helpedClass,
            @NonNull Parcel in) {
        @SuppressWarnings("unchecked")
        UnixEpochTime unixEpochTime =
                in.readParcelable(null /* classLoader */, UnixEpochTime.class);
        TimeSuggestionHelper suggestionHelper =
                new TimeSuggestionHelper(helpedClass, unixEpochTime);
        suggestionHelper.mDebugInfo = in.readArrayList(null /* classLoader */, String.class);
        return suggestionHelper;
    }

    /** Writes the helper suggestion state to a Parcel. */
    public void handleWriteToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mUnixEpochTime, 0);
        dest.writeList(mDebugInfo);
    }

    /** Parses command line args to create a {@link TimeSuggestionHelper}. */
    public static TimeSuggestionHelper handleParseCommandLineArg(
            @NonNull Class<?> helpedClass, @NonNull ShellCommand cmd)
            throws IllegalArgumentException {
        Long elapsedRealtimeMillis = null;
        Long unixEpochTimeMillis = null;
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
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }

        if (elapsedRealtimeMillis == null) {
            throw new IllegalArgumentException("No referenceTimeMillis specified.");
        }
        if (unixEpochTimeMillis == null) {
            throw new IllegalArgumentException("No unixEpochTimeMillis specified.");
        }

        UnixEpochTime timeSignal = new UnixEpochTime(elapsedRealtimeMillis, unixEpochTimeMillis);
        TimeSuggestionHelper suggestionHelper = new TimeSuggestionHelper(helpedClass, timeSignal);
        suggestionHelper.addDebugInfo("Command line injection");
        return suggestionHelper;
    }

    /** Prints the command line args needed to create a {@link TimeSuggestionHelper}. */
    public static void handlePrintCommandLineOpts(
            @NonNull PrintWriter pw, @NonNull String typeName, @NonNull Class<?> clazz) {
        pw.printf("%s suggestion options:\n", typeName);
        pw.println("  --elapsed_realtime <elapsed realtime millis> - the elapsed realtime millis"
                + " when unix epoch time was read");
        pw.println("  --unix_epoch_time <Unix epoch time millis>");
        pw.println();
        pw.println("See " + clazz.getName() + " for more information");
    }
}
