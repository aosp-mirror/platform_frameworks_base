/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.timedetector.TimeSuggestionHelper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ShellCommand;
import android.os.TimestampedValue;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * A time signal from an External source.
 *
 * <p>External time suggestions are for use in situations where the Android device is part of a
 * wider network of devices that are required to use a single time source, and where authority for
 * the time is external to the Android device. For example, for the Android Auto use case where the
 * Android device is part of a wider in-car network of devices that should display the same time.
 *
 * <p>Android allows for a single external source for time. If there are several external sources
 * then it is left to the caller to prioritize / filter accordingly to ensure consistency.
 *
 * <p>External is one of several time "origins" that the Android platform supports. Stock Android
 * allows for configuration of which origins can be used and the prioritization between them. Until
 * an external suggestion is made, the Android device may use its own RTC to initialize the system
 * clock during boot, and then accept suggestions from the configured origins.
 *
 * <p>The creator of an external suggestion is expected to be separate Android process, e.g. a
 * process integrating with the external time source via a HAL or local network. The creator must
 * capture the elapsed realtime reference clock, e.g. via {@link SystemClock#elapsedRealtime()},
 * when the Unix epoch time is first obtained (usually under a wakelock). This enables Android to
 * adjust for latency introduced between suggestion creation and eventual use. Adjustments for other
 * sources of latency, i.e. those before the external time suggestion is created, must be handled by
 * the creator.
 *
 * <p>{@code elapsedRealtimeMillis} and {@code suggestionMillis} represent the suggested time.
 * {@code suggestionMillis} is the number of milliseconds elapsed since 1/1/1970 00:00:00 UTC
 * according to the Unix time scale. {@code elapsedRealtimeMillis} is the value of the elapsed
 * realtime clock when {@code suggestionMillis} was established. Note that the elapsed realtime
 * clock is considered accurate but it is volatile, so time suggestions cannot be persisted across
 * device resets.
 *
 * <p>{@code debugInfo} contains debugging metadata associated with the suggestion. This is used to
 * record why the suggestion exists and how it was entered. This information exists only to aid in
 * debugging and therefore is used by {@link #toString()}, but it is not for use in detection logic
 * and is not considered in {@link #hashCode()} or {@link #equals(Object)}.
 *
 * @hide
 */
@SystemApi
public final class ExternalTimeSuggestion implements Parcelable {

    public static final @NonNull Creator<ExternalTimeSuggestion> CREATOR =
            new Creator<ExternalTimeSuggestion>() {
                public ExternalTimeSuggestion createFromParcel(Parcel in) {
                    TimeSuggestionHelper helper = TimeSuggestionHelper.handleCreateFromParcel(
                            ExternalTimeSuggestion.class, in);
                    return new ExternalTimeSuggestion(helper);
                }

                public ExternalTimeSuggestion[] newArray(int size) {
                    return new ExternalTimeSuggestion[size];
                }
            };

    @NonNull private final TimeSuggestionHelper mTimeSuggestionHelper;

    /**
     * Creates a time suggestion cross-referenced to the elapsed realtime clock. See {@link
     * ExternalTimeSuggestion} for more details.
     *
     * @param elapsedRealtimeMillis the elapsed realtime clock reference for the suggestion
     * @param suggestionMillis      the suggested time in milliseconds since the start of the
     *                              Unix epoch
     */
    public ExternalTimeSuggestion(@ElapsedRealtimeLong long elapsedRealtimeMillis,
            @CurrentTimeMillisLong long suggestionMillis) {
        mTimeSuggestionHelper = new TimeSuggestionHelper(ExternalTimeSuggestion.class,
                new TimestampedValue<>(elapsedRealtimeMillis, suggestionMillis));
    }

    private ExternalTimeSuggestion(@NonNull TimeSuggestionHelper helper) {
        mTimeSuggestionHelper = Objects.requireNonNull(helper);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mTimeSuggestionHelper.handleWriteToParcel(dest, flags);
    }

    /**
     * {@hide}
     */
    @NonNull
    public TimestampedValue<Long> getUnixEpochTime() {
        return mTimeSuggestionHelper.getUnixEpochTime();
    }

    /**
     * Returns information that can be useful for debugging / logging. See {@link #addDebugInfo}.
     */
    @NonNull
    public List<String> getDebugInfo() {
        return mTimeSuggestionHelper.getDebugInfo();
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging. The
     * information is present in {@link #toString()} but is not considered for {@link
     * #equals(Object)} and {@link #hashCode()}.
     */
    public void addDebugInfo(@NonNull String... debugInfos) {
        mTimeSuggestionHelper.addDebugInfo(debugInfos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExternalTimeSuggestion that = (ExternalTimeSuggestion) o;
        return mTimeSuggestionHelper.handleEquals(that.mTimeSuggestionHelper);
    }

    @Override
    public int hashCode() {
        return mTimeSuggestionHelper.hashCode();
    }

    @Override
    public String toString() {
        return mTimeSuggestionHelper.handleToString();
    }

    /** @hide */
    public static ExternalTimeSuggestion parseCommandLineArg(@NonNull ShellCommand cmd)
            throws IllegalArgumentException {
        return new ExternalTimeSuggestion(
                TimeSuggestionHelper.handleParseCommandLineArg(ExternalTimeSuggestion.class, cmd));
    }

    /** @hide */
    public static void printCommandLineOpts(PrintWriter pw) {
        TimeSuggestionHelper.handlePrintCommandLineOpts(
                pw, "External", ExternalTimeSuggestion.class);
    }
}
