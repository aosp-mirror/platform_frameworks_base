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
     * TimeZoneProviderService#reportUncertain(TimeZoneProviderStatus)}
     */
    public static final @EventType int EVENT_TYPE_UNCERTAIN = 3;

    private final @EventType int mType;

    @ElapsedRealtimeLong
    private final long mCreationElapsedMillis;

    // Populated when mType == EVENT_TYPE_SUGGESTION
    @Nullable
    private final TimeZoneProviderSuggestion mSuggestion;

    // Populated when mType == EVENT_TYPE_PERMANENT_FAILURE
    @Nullable
    private final String mFailureCause;

    // May be populated when EVENT_TYPE_SUGGESTION or EVENT_TYPE_UNCERTAIN
    @Nullable
    private final TimeZoneProviderStatus mTimeZoneProviderStatus;

    private TimeZoneProviderEvent(@EventType int type,
            @ElapsedRealtimeLong long creationElapsedMillis,
            @Nullable TimeZoneProviderSuggestion suggestion,
            @Nullable String failureCause,
            @Nullable TimeZoneProviderStatus timeZoneProviderStatus) {
        mType = validateEventType(type);
        mCreationElapsedMillis = creationElapsedMillis;
        mSuggestion = suggestion;
        mFailureCause = failureCause;
        mTimeZoneProviderStatus = timeZoneProviderStatus;

        // Confirm the type and the provider status agree.
        if (mType == EVENT_TYPE_PERMANENT_FAILURE && mTimeZoneProviderStatus != null) {
            throw new IllegalArgumentException(
                    "Unexpected status: mType=" + mType
                            + ", mTimeZoneProviderStatus=" + mTimeZoneProviderStatus);
        }
    }

    private static @EventType int validateEventType(@EventType int eventType) {
        if (eventType < EVENT_TYPE_PERMANENT_FAILURE || eventType > EVENT_TYPE_UNCERTAIN) {
            throw new IllegalArgumentException(Integer.toString(eventType));
        }
        return eventType;
    }

    /** Returns an event of type {@link #EVENT_TYPE_SUGGESTION}. */
    public static TimeZoneProviderEvent createSuggestionEvent(
            @ElapsedRealtimeLong long creationElapsedMillis,
            @NonNull TimeZoneProviderSuggestion suggestion,
            @Nullable TimeZoneProviderStatus providerStatus) {
        return new TimeZoneProviderEvent(EVENT_TYPE_SUGGESTION, creationElapsedMillis,
                Objects.requireNonNull(suggestion), null, providerStatus);
    }

    /** Returns an event of type {@link #EVENT_TYPE_UNCERTAIN}. */
    public static TimeZoneProviderEvent createUncertainEvent(
            @ElapsedRealtimeLong long creationElapsedMillis,
            @Nullable TimeZoneProviderStatus timeZoneProviderStatus) {

        return new TimeZoneProviderEvent(
                EVENT_TYPE_UNCERTAIN, creationElapsedMillis, null, null,
                timeZoneProviderStatus);
    }

    /** Returns an event of type {@link #EVENT_TYPE_PERMANENT_FAILURE}. */
    public static TimeZoneProviderEvent createPermanentFailureEvent(
            @ElapsedRealtimeLong long creationElapsedMillis,
            @NonNull String cause) {
        return new TimeZoneProviderEvent(EVENT_TYPE_PERMANENT_FAILURE, creationElapsedMillis, null,
                Objects.requireNonNull(cause), null);
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
     * Returns the failure cause. Populated when {@link #getType()} is {@link
     * #EVENT_TYPE_PERMANENT_FAILURE}.
     */
    @Nullable
    public String getFailureCause() {
        return mFailureCause;
    }

    /**
     * Returns the status of the time zone provider.  May be populated when {@link #getType()} is
     * {@link #EVENT_TYPE_UNCERTAIN} or {@link #EVENT_TYPE_SUGGESTION}, otherwise {@code null}.
     */
    @Nullable
    public TimeZoneProviderStatus getTimeZoneProviderStatus() {
        return mTimeZoneProviderStatus;
    }

    public static final @NonNull Creator<TimeZoneProviderEvent> CREATOR = new Creator<>() {
        @Override
        public TimeZoneProviderEvent createFromParcel(Parcel in) {
            int type = in.readInt();
            long creationElapsedMillis = in.readLong();
            TimeZoneProviderSuggestion suggestion = in.readParcelable(
                    getClass().getClassLoader(), TimeZoneProviderSuggestion.class);
            String failureCause = in.readString8();
            TimeZoneProviderStatus status = in.readParcelable(
                    getClass().getClassLoader(), TimeZoneProviderStatus.class);
            return new TimeZoneProviderEvent(
                    type, creationElapsedMillis, suggestion, failureCause, status);
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
        parcel.writeParcelable(mTimeZoneProviderStatus, 0);
    }

    @Override
    public String toString() {
        return "TimeZoneProviderEvent{"
                + "mType=" + mType
                + ", mCreationElapsedMillis=" + Duration.ofMillis(mCreationElapsedMillis).toString()
                + ", mSuggestion=" + mSuggestion
                + ", mFailureCause=" + mFailureCause
                + ", mTimeZoneProviderStatus=" + mTimeZoneProviderStatus
                + '}';
    }

    /**
     * Similar to {@link #equals} except this methods checks for equivalence, not equality.
     * i.e. two {@link #EVENT_TYPE_SUGGESTION} events are equivalent if they suggest
     * the same time zones and have the same provider status, two {@link #EVENT_TYPE_UNCERTAIN}
     * events are equivalent if they have the same provider status, and {@link
     * #EVENT_TYPE_PERMANENT_FAILURE} events are always equivalent (the nature of the failure is not
     * considered).
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
            return mSuggestion.isEquivalentTo(other.mSuggestion)
                    && Objects.equals(mTimeZoneProviderStatus, other.mTimeZoneProviderStatus);
        }
        return Objects.equals(mTimeZoneProviderStatus, other.mTimeZoneProviderStatus);
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
                && Objects.equals(mFailureCause, that.mFailureCause)
                && Objects.equals(mTimeZoneProviderStatus, that.mTimeZoneProviderStatus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mCreationElapsedMillis, mSuggestion, mFailureCause,
                mTimeZoneProviderStatus);
    }
}
