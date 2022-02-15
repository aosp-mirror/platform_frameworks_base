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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Objects;

/**
 * Encapsulates a reported event from a {@link TimeZoneProviderService}.
 *
 * @hide
 */
public final class TimeZoneProviderEvent implements Parcelable {

    @IntDef(prefix = "EVENT_TYPE_",
            value = { EVENT_TYPE_PERMANENT_FAILURE, EVENT_TYPE_SUGGESTION, EVENT_TYPE_UNCERTAIN })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    public @interface EventType {}

    /**
     * The provider failed permanently. See {@link
     * TimeZoneProviderService#reportPermanentFailure(Throwable)}
     */
    public static final @EventType int EVENT_TYPE_PERMANENT_FAILURE = 1;

    /**
     * The provider made a suggestion. See {@link
     * TimeZoneProviderService#reportSuggestion(TimeZoneProviderSuggestion)}
     */
    public static final @EventType int EVENT_TYPE_SUGGESTION = 2;

    /**
     * The provider was uncertain about the time zone. See {@link
     * TimeZoneProviderService#reportUncertain()}
     */
    public static final @EventType int EVENT_TYPE_UNCERTAIN = 3;

    private final @EventType int mType;

    @ElapsedRealtimeLong
    private final long mCreationElapsedMillis;

    @Nullable
    private final TimeZoneProviderSuggestion mSuggestion;

    @Nullable
    private final String mFailureCause;

    private TimeZoneProviderEvent(@EventType int type,
            @ElapsedRealtimeLong long creationElapsedMillis,
            @Nullable TimeZoneProviderSuggestion suggestion,
            @Nullable String failureCause) {
        mType = type;
        mCreationElapsedMillis = creationElapsedMillis;
        mSuggestion = suggestion;
        mFailureCause = failureCause;
    }

    /** Returns a event of type {@link #EVENT_TYPE_SUGGESTION}. */
    public static TimeZoneProviderEvent createSuggestionEvent(
            @ElapsedRealtimeLong long creationElapsedMillis,
            @NonNull TimeZoneProviderSuggestion suggestion) {
        return new TimeZoneProviderEvent(EVENT_TYPE_SUGGESTION, creationElapsedMillis,
                Objects.requireNonNull(suggestion), null);
    }

    /** Returns a event of type {@link #EVENT_TYPE_UNCERTAIN}. */
    public static TimeZoneProviderEvent createUncertainEvent(
            @ElapsedRealtimeLong long creationElapsedMillis) {
        return new TimeZoneProviderEvent(EVENT_TYPE_UNCERTAIN, creationElapsedMillis, null, null);
    }

    /** Returns a event of type {@link #EVENT_TYPE_PERMANENT_FAILURE}. */
    public static TimeZoneProviderEvent createPermanentFailureEvent(
            @ElapsedRealtimeLong long creationElapsedMillis,
            @NonNull String cause) {
        return new TimeZoneProviderEvent(EVENT_TYPE_PERMANENT_FAILURE, creationElapsedMillis, null,
                Objects.requireNonNull(cause));
    }

    /**
     * Returns the event type.
     */
    public @EventType int getType() {
        return mType;
    }

    /** Returns the time according to the elapsed realtime clock when the event was created. */
    @ElapsedRealtimeLong
    public long getCreationElapsedMillis() {
        return mCreationElapsedMillis;
    }

    /**
     * Returns the suggestion. Populated when {@link #getType()} is {@link #EVENT_TYPE_SUGGESTION}.
     */
    @Nullable
    public TimeZoneProviderSuggestion getSuggestion() {
        return mSuggestion;
    }

    /**
     * Returns the failure cauese. Populated when {@link #getType()} is {@link
     * #EVENT_TYPE_PERMANENT_FAILURE}.
     */
    @Nullable
    public String getFailureCause() {
        return mFailureCause;
    }

    public static final @NonNull Creator<TimeZoneProviderEvent> CREATOR =
            new Creator<TimeZoneProviderEvent>() {
                @Override
                public TimeZoneProviderEvent createFromParcel(Parcel in) {
                    int type = in.readInt();
                    long creationElapsedMillis = in.readLong();
                    TimeZoneProviderSuggestion suggestion =
                            in.readParcelable(getClass().getClassLoader(), android.service.timezone.TimeZoneProviderSuggestion.class);
                    String failureCause = in.readString8();
                    return new TimeZoneProviderEvent(
                            type, creationElapsedMillis, suggestion, failureCause);
                }

                @Override
                public TimeZoneProviderEvent[] newArray(int size) {
                    return new TimeZoneProviderEvent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeLong(mCreationElapsedMillis);
        parcel.writeParcelable(mSuggestion, 0);
        parcel.writeString8(mFailureCause);
    }

    @Override
    public String toString() {
        return "TimeZoneProviderEvent{"
                + "mType=" + mType
                + ", mCreationElapsedMillis=" + Duration.ofMillis(mCreationElapsedMillis).toString()
                + ", mSuggestion=" + mSuggestion
                + ", mFailureCause=" + mFailureCause
                + '}';
    }

    /**
     * Similar to {@link #equals} except this methods checks for equivalence, not equality.
     * i.e. two {@link #EVENT_TYPE_UNCERTAIN} and {@link #EVENT_TYPE_PERMANENT_FAILURE} events are
     * always equivalent, two {@link #EVENT_TYPE_SUGGESTION} events are equivalent if they suggest
     * the same time zones.
     */
    @SuppressWarnings("ReferenceEquality")
    public boolean isEquivalentTo(@Nullable TimeZoneProviderEvent other) {
        if (this == other) {
            return true;
        }
        if (other == null || mType != other.mType) {
            return false;
        }
        if (mType == EVENT_TYPE_SUGGESTION) {
            return mSuggestion.isEquivalentTo(other.getSuggestion());
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeZoneProviderEvent that = (TimeZoneProviderEvent) o;
        return mType == that.mType
                && mCreationElapsedMillis == that.mCreationElapsedMillis
                && Objects.equals(mSuggestion, that.mSuggestion)
                && Objects.equals(mFailureCause, that.mFailureCause);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mCreationElapsedMillis, mSuggestion, mFailureCause);
    }
}
