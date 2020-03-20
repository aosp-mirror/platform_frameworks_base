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
import android.os.Parcel;
import android.os.Parcelable;
import android.os.TimestampedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A time signal from a network time source like NTP.
 *
 * <p>{@code utcTime} contains the suggested time. The {@code utcTime.value} is the number of
 * milliseconds elapsed since 1/1/1970 00:00:00 UTC. The {@code utcTime.referenceTimeMillis} is the
 * value of the elapsed realtime clock when the {@code utcTime.value} was established.
 * Note that the elapsed realtime clock is considered accurate but it is volatile, so time
 * suggestions cannot be persisted across device resets.
 *
 * <p>{@code debugInfo} contains debugging metadata associated with the suggestion. This is used to
 * record why the suggestion exists and how it was determined. This information exists only to aid
 * in debugging and therefore is used by {@link #toString()}, but it is not for use in detection
 * logic and is not considered in {@link #hashCode()} or {@link #equals(Object)}.
 *
 * @hide
 */
public final class NetworkTimeSuggestion implements Parcelable {

    public static final @NonNull Creator<NetworkTimeSuggestion> CREATOR =
            new Creator<NetworkTimeSuggestion>() {
                public NetworkTimeSuggestion createFromParcel(Parcel in) {
                    return NetworkTimeSuggestion.createFromParcel(in);
                }

                public NetworkTimeSuggestion[] newArray(int size) {
                    return new NetworkTimeSuggestion[size];
                }
            };

    @NonNull private final TimestampedValue<Long> mUtcTime;
    @Nullable private ArrayList<String> mDebugInfo;

    public NetworkTimeSuggestion(@NonNull TimestampedValue<Long> utcTime) {
        mUtcTime = Objects.requireNonNull(utcTime);
        Objects.requireNonNull(utcTime.getValue());
    }

    private static NetworkTimeSuggestion createFromParcel(Parcel in) {
        TimestampedValue<Long> utcTime = in.readParcelable(null /* classLoader */);
        NetworkTimeSuggestion suggestion = new NetworkTimeSuggestion(utcTime);
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
        dest.writeParcelable(mUtcTime, 0);
        dest.writeList(mDebugInfo);
    }

    @NonNull
    public TimestampedValue<Long> getUtcTime() {
        return mUtcTime;
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
        NetworkTimeSuggestion that = (NetworkTimeSuggestion) o;
        return Objects.equals(mUtcTime, that.mUtcTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUtcTime);
    }

    @Override
    public String toString() {
        return "NetworkTimeSuggestion{"
                + "mUtcTime=" + mUtcTime
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }
}
