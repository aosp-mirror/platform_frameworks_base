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

package android.app.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.time.UnixEpochTime;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * A time signal from a manual (user provided) source.
 *
 * <p>See {@link TimeSuggestionHelper} for property information.
 *
 * @hide
 */
public final class ManualTimeSuggestion implements Parcelable {

    public static final @NonNull Creator<ManualTimeSuggestion> CREATOR =
            new Creator<ManualTimeSuggestion>() {
                public ManualTimeSuggestion createFromParcel(Parcel in) {
                    TimeSuggestionHelper helper = TimeSuggestionHelper.handleCreateFromParcel(
                            ManualTimeSuggestion.class, in);
                    return new ManualTimeSuggestion(helper);
                }

                public ManualTimeSuggestion[] newArray(int size) {
                    return new ManualTimeSuggestion[size];
                }
            };

    @NonNull private final TimeSuggestionHelper mTimeSuggestionHelper;

    public ManualTimeSuggestion(@NonNull UnixEpochTime unixEpochTime) {
        mTimeSuggestionHelper = new TimeSuggestionHelper(ManualTimeSuggestion.class, unixEpochTime);
    }

    private ManualTimeSuggestion(@NonNull TimeSuggestionHelper helper) {
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
    public UnixEpochTime getUnixEpochTime() {
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
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ManualTimeSuggestion that = (ManualTimeSuggestion) o;
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
    public static ManualTimeSuggestion parseCommandLineArg(@NonNull ShellCommand cmd)
            throws IllegalArgumentException {
        return new ManualTimeSuggestion(
                TimeSuggestionHelper.handleParseCommandLineArg(ManualTimeSuggestion.class, cmd));
    }

    /** @hide */
    public static void printCommandLineOpts(PrintWriter pw) {
        TimeSuggestionHelper.handlePrintCommandLineOpts(pw, "Manual", ManualTimeSuggestion.class);
    }
}
