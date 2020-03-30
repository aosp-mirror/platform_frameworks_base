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

package android.app.timezonedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A time zone suggestion from a manual (user provided) source.
 *
 * <p>{@code zoneId} contains the suggested time zone ID, e.g. "America/Los_Angeles".
 *
 * <p>{@code debugInfo} contains debugging metadata associated with the suggestion. This is used to
 * record why the suggestion exists and how it was entered. This information exists only to aid in
 * debugging and therefore is used by {@link #toString()}, but it is not for use in detection logic
 * and is not considered in {@link #hashCode()} or {@link #equals(Object)}.
 *
 * @hide
 */
public final class ManualTimeZoneSuggestion implements Parcelable {

    public static final @NonNull Creator<ManualTimeZoneSuggestion> CREATOR =
            new Creator<ManualTimeZoneSuggestion>() {
                public ManualTimeZoneSuggestion createFromParcel(Parcel in) {
                    return ManualTimeZoneSuggestion.createFromParcel(in);
                }

                public ManualTimeZoneSuggestion[] newArray(int size) {
                    return new ManualTimeZoneSuggestion[size];
                }
            };

    @NonNull private final String mZoneId;
    @Nullable private ArrayList<String> mDebugInfo;

    public ManualTimeZoneSuggestion(@NonNull String zoneId) {
        mZoneId = Objects.requireNonNull(zoneId);
    }

    private static ManualTimeZoneSuggestion createFromParcel(Parcel in) {
        String zoneId = in.readString();
        ManualTimeZoneSuggestion suggestion = new ManualTimeZoneSuggestion(zoneId);
        @SuppressWarnings("unchecked")
        ArrayList<String> debugInfo = (ArrayList<String>) in.readArrayList(null /* classLoader */);
        suggestion.mDebugInfo = debugInfo;
        return suggestion;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mZoneId);
        dest.writeList(mDebugInfo);
    }

    @NonNull
    public String getZoneId() {
        return mZoneId;
    }

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
        ManualTimeZoneSuggestion
                that = (ManualTimeZoneSuggestion) o;
        return Objects.equals(mZoneId, that.mZoneId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mZoneId);
    }

    @Override
    public String toString() {
        return "ManualTimeZoneSuggestion{"
                + "mZoneId=" + mZoneId
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }

    /** @hide */
    public static ManualTimeZoneSuggestion parseCommandLineArg(@NonNull ShellCommand cmd) {
        String zoneId = null;
        String opt;
        while ((opt = cmd.getNextArg()) != null) {
            switch (opt) {
                case "--zone_id": {
                    zoneId = cmd.getNextArgRequired();
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }
        ManualTimeZoneSuggestion suggestion = new ManualTimeZoneSuggestion(zoneId);
        suggestion.addDebugInfo("Command line injection");
        return suggestion;
    }

    /** @hide */
    public static void printCommandLineOpts(@NonNull PrintWriter pw) {
        pw.println("Manual suggestion options:");
        pw.println("  --zone_id <Olson ID>");
        pw.println();
        pw.println("See " + ManualTimeZoneSuggestion.class.getName() + " for more information");
    }
}
