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

package android.service.timezone;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A suggestion from a {@link TimeZoneProviderService} containing zero or more time zones.
 *
 * @hide
 */
@SystemApi
public final class TimeZoneProviderSuggestion implements Parcelable {

    @NonNull
    private final List<String> mTimeZoneIds;

    @ElapsedRealtimeLong
    private final long mElapsedRealtimeMillis;

    private TimeZoneProviderSuggestion(@NonNull List<String> timeZoneIds,
            @ElapsedRealtimeLong long elapsedRealtimeMillis) {
        mTimeZoneIds = immutableList(timeZoneIds);
        mElapsedRealtimeMillis = elapsedRealtimeMillis;
    }

    /**
     * Returns the time of the suggestion in elapsed real-time since system boot. Where possible,
     * the time should be based on the time of the data used when determining time zone. For
     * example, if it was based on a {@link android.location.Location} then it should be the time
     * associated with that location.
     *
     * <p>This value is compared to {@link
     * android.os.SystemClock#elapsedRealtime()}, to calculate the age of a fix and to compare
     * {@link TimeZoneProviderSuggestion} instances.
     *
     * @return elapsed real-time of fix, in milliseconds
     */
    @ElapsedRealtimeLong
    public long getElapsedRealtimeMillis() {
        return mElapsedRealtimeMillis;
    }

    /**
     * Returns the zero or more time zone IDs for this suggestion.
     *
     * <p>Time zone IDs are TZDB IDs like "America/Los_Angeles" that would be accepted by {@link
     * java.util.TimeZone#getTimeZone(String)}.
     *
     * <p>Most often a suggestion will contain a single time zone ID but other possibilities are
     * valid. A suggestion with zero time zone IDs means the provider is certain there are no time
     * zones for the current location, e.g. for oceans, boundaries or disputed areas. A suggestion
     * with multiple IDs can occur on boundaries or disputed areas. The ordering should be in order
     * of likelihood if possible, but the time zone detection service may choose from any of the
     * zones suggested if it has other supporting information available.
     */
    @NonNull
    public List<String> getTimeZoneIds() {
        return mTimeZoneIds;
    }

    @Override
    public String toString() {
        return "TimeZoneProviderSuggestion{"
                + "mTimeZoneIds=" + mTimeZoneIds
                + ", mElapsedRealtimeMillis=" + mElapsedRealtimeMillis
                + "(" + Duration.ofMillis(mElapsedRealtimeMillis) + ")"
                + '}';
    }

    public static final @NonNull Creator<TimeZoneProviderSuggestion> CREATOR =
            new Creator<TimeZoneProviderSuggestion>() {
                @Override
                public TimeZoneProviderSuggestion createFromParcel(Parcel in) {
                    @SuppressWarnings("unchecked")
                    ArrayList<String> timeZoneIds =
                            (ArrayList<String>) in.readArrayList(null /* classLoader */);
                    long elapsedRealtimeMillis = in.readLong();
                    return new TimeZoneProviderSuggestion(timeZoneIds, elapsedRealtimeMillis);
                }

                @Override
                public TimeZoneProviderSuggestion[] newArray(int size) {
                    return new TimeZoneProviderSuggestion[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeList(mTimeZoneIds);
        parcel.writeLong(mElapsedRealtimeMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeZoneProviderSuggestion that = (TimeZoneProviderSuggestion) o;
        return mElapsedRealtimeMillis == that.mElapsedRealtimeMillis
                && mTimeZoneIds.equals(that.mTimeZoneIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTimeZoneIds, mElapsedRealtimeMillis);
    }

    /** A builder for {@link TimeZoneProviderSuggestion}. */
    public static final class Builder {

        private @NonNull List<String> mTimeZoneIds = Collections.emptyList();
        @ElapsedRealtimeLong
        private long mElapsedRealtimeMillis = SystemClock.elapsedRealtime();

        /**
         * Sets the time zone IDs of this suggestion.
         */
        @NonNull
        public Builder setTimeZoneIds(@NonNull List<String> timeZoneIds) {
            mTimeZoneIds = Objects.requireNonNull(timeZoneIds);
            return this;
        }

        /**
         * Sets the time of this suggestion, in elapsed real-time since system boot.
         */
        @NonNull
        public Builder setElapsedRealtimeMillis(@ElapsedRealtimeLong long time) {
            mElapsedRealtimeMillis = time;
            return this;
        }

        /**
         * Builds a {@link TimeZoneProviderSuggestion} instance.
         */
        @NonNull
        public TimeZoneProviderSuggestion build() {
            return new TimeZoneProviderSuggestion(mTimeZoneIds, mElapsedRealtimeMillis);
        }
    }

    @NonNull
    private static List<String> immutableList(@NonNull List<String> list) {
        Objects.requireNonNull(list);
        if (list.isEmpty()) {
            return Collections.emptyList();
        } else {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
    }
}
