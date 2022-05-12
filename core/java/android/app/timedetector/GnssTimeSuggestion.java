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

package android.app.timedetector;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ShellCommand;
import android.os.TimestampedValue;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * A time signal from a GNSS source.
 *
 * <p>See {@link TimeSuggestionHelper} for property information.
 *
 * @hide
 */
public final class GnssTimeSuggestion implements Parcelable {

    public static final @NonNull Creator<GnssTimeSuggestion> CREATOR =
            new Creator<GnssTimeSuggestion>() {
                public GnssTimeSuggestion createFromParcel(Parcel in) {
                    TimeSuggestionHelper helper = TimeSuggestionHelper.handleCreateFromParcel(
                            GnssTimeSuggestion.class, in);
                    return new GnssTimeSuggestion(helper);
                }

                public GnssTimeSuggestion[] newArray(int size) {
                    return new GnssTimeSuggestion[size];
                }
            };

    @NonNull private final TimeSuggestionHelper mTimeSuggestionHelper;

    public GnssTimeSuggestion(@NonNull TimestampedValue<Long> unixEpochTime) {
        mTimeSuggestionHelper = new TimeSuggestionHelper(GnssTimeSuggestion.class, unixEpochTime);
    }

    private GnssTimeSuggestion(@NonNull TimeSuggestionHelper helper) {
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

    @NonNull
    public TimestampedValue<Long> getUnixEpochTime() {
        return mTimeSuggestionHelper.getUnixEpochTime();
    }

    @NonNull
    public List<String> getDebugInfo() {
        return mTimeSuggestionHelper.getDebugInfo();
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging. The
     * information is present in {@link #toString()} but is not considered for
     * {@link #equals(Object)} and {@link #hashCode()}.
     */
    public void addDebugInfo(String... debugInfos) {
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
        GnssTimeSuggestion that = (GnssTimeSuggestion) o;
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

    /** Parses command line args to create a {@link GnssTimeSuggestion}. */
    public static GnssTimeSuggestion parseCommandLineArg(@NonNull ShellCommand cmd)
            throws IllegalArgumentException {
        TimeSuggestionHelper suggestionHelper =
                TimeSuggestionHelper.handleParseCommandLineArg(GnssTimeSuggestion.class, cmd);
        return new GnssTimeSuggestion(suggestionHelper);
    }

    /** Prints the command line args needed to create a {@link GnssTimeSuggestion}. */
    public static void printCommandLineOpts(PrintWriter pw) {
        TimeSuggestionHelper.handlePrintCommandLineOpts(pw, "GNSS", GnssTimeSuggestion.class);
    }
}
