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
 * A time signal from an External source.
 *
 * External time suggestions are for use in situations where the Android device is part of a wider
 * network of devices that are required to use a single time source, and where authority for the
 * time is external to the Android device. For example, for the Android Auto use case where the
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
 * when the UTC time is first obtained (usually under a wakelock). This enables Android to adjust
 * for latency introduced between suggestion creation and eventual use. Adjustments for other
 * sources of latency, i.e. those before the external time suggestion is created, must be handled
 * by the creator.
 *
 * <p>{@code utcTime} is the suggested time. The {@code utcTime.value} is the number of milliseconds
 * elapsed since 1/1/1970 00:00:00 UTC. The {@code utcTime.referenceTimeMillis} is the value of the
 * elapsed realtime clock when the {@code utcTime.value} was established.
 * Note that the elapsed realtime clock is considered accurate but it is volatile, so time
 * suggestions cannot be persisted across device resets.
 *
 * <p>{@code debugInfo} contains debugging metadata associated with the suggestion. This is used to
 * record why the suggestion exists and how it was entered. This information exists only to aid in
 * debugging and therefore is used by {@link #toString()}, but it is not for use in detection
 * logic and is not considered in {@link #hashCode()} or {@link #equals(Object)}.
 *
 * @hide
 */
public final class ExternalTimeSuggestion implements Parcelable {

    public static final @NonNull Creator<ExternalTimeSuggestion> CREATOR =
            new Creator<ExternalTimeSuggestion>() {
                public ExternalTimeSuggestion createFromParcel(Parcel in) {
                    return ExternalTimeSuggestion.createFromParcel(in);
                }

                public ExternalTimeSuggestion[] newArray(int size) {
                    return new ExternalTimeSuggestion[size];
                }
            };

    @NonNull private final TimestampedValue<Long> mUtcTime;
    @Nullable private ArrayList<String> mDebugInfo;

    public ExternalTimeSuggestion(@NonNull TimestampedValue<Long> utcTime) {
        mUtcTime = Objects.requireNonNull(utcTime);
        Objects.requireNonNull(utcTime.getValue());
    }

    private static ExternalTimeSuggestion createFromParcel(Parcel in) {
        TimestampedValue<Long> utcTime = in.readParcelable(null /* classLoader */);
        ExternalTimeSuggestion suggestion = new ExternalTimeSuggestion(utcTime);
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
        ExternalTimeSuggestion that = (ExternalTimeSuggestion) o;
        return Objects.equals(mUtcTime, that.mUtcTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUtcTime);
    }

    @Override
    public String toString() {
        return "ExternalTimeSuggestion{"
                + "mUtcTime=" + mUtcTime
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }
}
