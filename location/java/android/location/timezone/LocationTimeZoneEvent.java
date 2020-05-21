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

package android.location.timezone;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An event containing location time zone information.
 *
 * @hide
 */
public final class LocationTimeZoneEvent implements Parcelable {

    @IntDef({ EVENT_TYPE_UNKNOWN, EVENT_TYPE_SUCCESS, EVENT_TYPE_SUCCESS })
    @interface EventType {}

    /** Uninitialized value for {@link #mEventType} */
    private static final int EVENT_TYPE_UNKNOWN = 0;

    /**
     * Indicates there was a permanent failure. This is not generally expected, and probably means a
     * required backend service is no longer supported / available.
     */
    public static final int EVENT_TYPE_PERMANENT_FAILURE = 1;

    /**
     * Indicates a successful geolocation time zone detection event. {@link #mTimeZoneIds} will be
     * non-null but can legitimately be empty, e.g. for disputed areas, oceans.
     */
    public static final int EVENT_TYPE_SUCCESS = 2;

    /**
     * Indicates the time zone is not known because there was a (temporary) error, e.g. when
     * detecting location, or when resolving the location to a time zone.
     */
    public static final int EVENT_TYPE_UNCERTAIN = 3;

    private static final int EVENT_TYPE_MAX = EVENT_TYPE_UNCERTAIN;

    @EventType
    private final int mEventType;

    @NonNull
    private final List<String> mTimeZoneIds;

    private final long mElapsedRealtimeNanos;

    private LocationTimeZoneEvent(@EventType int eventType, @NonNull List<String> timeZoneIds,
            long elapsedRealtimeNanos) {
        mEventType = checkValidEventType(eventType);
        mTimeZoneIds = immutableList(timeZoneIds);

        boolean emptyTimeZoneIdListExpected = eventType != EVENT_TYPE_SUCCESS;
        if (emptyTimeZoneIdListExpected && !timeZoneIds.isEmpty()) {
            throw new IllegalStateException(
                    "timeZoneIds must only have values when eventType is success");
        }

        mElapsedRealtimeNanos = elapsedRealtimeNanos;
    }

    /**
     * Returns the time of this fix, in elapsed real-time since system boot.
     *
     * <p>This value can be reliably compared to {@link
     * android.os.SystemClock#elapsedRealtimeNanos}, to calculate the age of a fix and to compare
     * {@link LocationTimeZoneEvent} fixes. This is reliable because elapsed real-time is guaranteed
     * monotonic for each system boot and continues to increment even when the system is in deep
     * sleep.
     *
     * @return elapsed real-time of fix, in nanoseconds since system boot.
     */
    public long getElapsedRealtimeNanos() {
        return mElapsedRealtimeNanos;
    }

    /**
     * Returns the event type.
     */
    @Nullable
    public @EventType int getEventType() {
        return mEventType;
    }

    /**
     * Gets the time zone IDs of this event. Contains zero or more IDs for a successful lookup.
     * The value is undefined for an unsuccessful lookup. See also {@link #getEventType()}.
     */
    @NonNull
    public List<String> getTimeZoneIds() {
        return mTimeZoneIds;
    }

    @Override
    public String toString() {
        return "LocationTimeZoneEvent{"
                + "mEventType=" + mEventType
                + ", mTimeZoneIds=" + mTimeZoneIds
                + ", mElapsedRealtimeNanos=" + mElapsedRealtimeNanos
                + '}';
    }

    public static final @NonNull Parcelable.Creator<LocationTimeZoneEvent> CREATOR =
            new Parcelable.Creator<LocationTimeZoneEvent>() {
                @Override
                public LocationTimeZoneEvent createFromParcel(Parcel in) {
                    int eventType = in.readInt();
                    @SuppressWarnings("unchecked")
                    ArrayList<String> timeZoneIds =
                            (ArrayList<String>) in.readArrayList(null /* classLoader */);
                    long elapsedRealtimeNanos = in.readLong();
                    return new LocationTimeZoneEvent(eventType, timeZoneIds, elapsedRealtimeNanos);
                }

                @Override
                public LocationTimeZoneEvent[] newArray(int size) {
                    return new LocationTimeZoneEvent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mEventType);
        parcel.writeList(mTimeZoneIds);
        parcel.writeLong(mElapsedRealtimeNanos);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LocationTimeZoneEvent that = (LocationTimeZoneEvent) o;
        return mEventType == that.mEventType
                && mElapsedRealtimeNanos == that.mElapsedRealtimeNanos
                && mTimeZoneIds.equals(that.mTimeZoneIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mEventType, mTimeZoneIds, mElapsedRealtimeNanos);
    }

    /** @hide */
    public static final class Builder {

        private @EventType int mEventType = EVENT_TYPE_UNKNOWN;
        private @NonNull List<String> mTimeZoneIds = Collections.emptyList();
        private long mElapsedRealtimeNanos;

        public Builder() {
        }

        /**
         * Sets the contents of this from the supplied instance.
         */
        public Builder(@NonNull LocationTimeZoneEvent ltz) {
            mEventType = ltz.mEventType;
            mTimeZoneIds = ltz.mTimeZoneIds;
            mElapsedRealtimeNanos = ltz.mElapsedRealtimeNanos;
        }

        /**
         * Set the time zone ID of this fix.
         */
        public Builder setEventType(@EventType int eventType) {
            checkValidEventType(eventType);
            mEventType = eventType;
            return this;
        }

        /**
         * Sets the time zone IDs of this fix.
         */
        public Builder setTimeZoneIds(@NonNull List<String> timeZoneIds) {
            mTimeZoneIds = Objects.requireNonNull(timeZoneIds);
            return this;
        }

        /**
         * Sets the time of this fix, in elapsed real-time since system boot.
         *
         * @param time elapsed real-time of fix, in nanoseconds since system boot.
         */
        public Builder setElapsedRealtimeNanos(long time) {
            mElapsedRealtimeNanos = time;
            return this;
        }

        /**
         * Builds a {@link LocationTimeZoneEvent} instance.
         */
        public LocationTimeZoneEvent build() {
            return new LocationTimeZoneEvent(this.mEventType, this.mTimeZoneIds,
                    this.mElapsedRealtimeNanos);
        }
    }

    private static int checkValidEventType(int eventType) {
        if (eventType <= EVENT_TYPE_UNKNOWN || eventType > EVENT_TYPE_MAX) {
            throw new IllegalStateException("eventType " + eventType + " unknown");
        }
        return eventType;
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
