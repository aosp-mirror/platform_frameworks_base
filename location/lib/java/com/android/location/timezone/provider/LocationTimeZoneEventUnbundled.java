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

package com.android.location.timezone.provider;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.SystemClock;

import com.android.internal.location.timezone.LocationTimeZoneEvent;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An event from a {@link LocationTimeZoneProviderBase} sent while determining a device's time zone
 * using its location.
 */
public final class LocationTimeZoneEventUnbundled {

    @IntDef({ EVENT_TYPE_PERMANENT_FAILURE, EVENT_TYPE_SUCCESS, EVENT_TYPE_UNCERTAIN })
    @interface EventType {}

    /**
     * Indicates there was a permanent failure. This is not generally expected, and probably means a
     * required backend service has been turned down, or the client is unreasonably old.
     */
    public static final int EVENT_TYPE_PERMANENT_FAILURE =
            LocationTimeZoneEvent.EVENT_TYPE_PERMANENT_FAILURE;

    /**
     * Indicates a successful geolocation time zone detection event. {@link #getTimeZoneIds()} will
     * be non-null but can legitimately be empty, e.g. for disputed areas, oceans.
     */
    public static final int EVENT_TYPE_SUCCESS = LocationTimeZoneEvent.EVENT_TYPE_SUCCESS;

    /**
     * Indicates the time zone is not known because of an expected runtime state or error, e.g. when
     * the provider is unable to detect location, or there was a problem when resolving the location
     * to a time zone.
     */
    public static final int EVENT_TYPE_UNCERTAIN = LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN;

    @NonNull
    private final LocationTimeZoneEvent mDelegate;

    private LocationTimeZoneEventUnbundled(@NonNull LocationTimeZoneEvent delegate) {
        mDelegate = Objects.requireNonNull(delegate);
    }

    /**
     * Returns the event type.
     */
    public @EventType int getEventType() {
        return mDelegate.getEventType();
    }

    /**
     * Gets the time zone IDs of this event. Contains zero or more IDs for a successful lookup.
     * The value is undefined for an unsuccessful lookup. See also {@link #getEventType()}.
     */
    @NonNull
    public List<String> getTimeZoneIds() {
        return mDelegate.getTimeZoneIds();
    }

    /**
     * Returns the information from this as a {@link LocationTimeZoneEvent}.
     * @hide
     */
    @NonNull
    public LocationTimeZoneEvent getInternalLocationTimeZoneEvent() {
        return mDelegate;
    }

    @Override
    public String toString() {
        return "LocationTimeZoneEventUnbundled{"
                + "mDelegate=" + mDelegate
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LocationTimeZoneEventUnbundled that = (LocationTimeZoneEventUnbundled) o;
        return mDelegate.equals(that.mDelegate);
    }

    @Override
    public int hashCode() {
        return mDelegate.hashCode();
    }

    /**
     * A builder of {@link LocationTimeZoneEventUnbundled} instances.
     */
    public static final class Builder {

        private @EventType int mEventType;
        private @NonNull List<String> mTimeZoneIds = Collections.emptyList();

        /**
         * Set the time zone ID of this event.
         */
        @NonNull
        public Builder setEventType(@EventType int eventType) {
            checkValidEventType(eventType);
            mEventType = eventType;
            return this;
        }

        /**
         * Sets the time zone IDs of this event.
         */
        @NonNull
        public Builder setTimeZoneIds(@NonNull List<String> timeZoneIds) {
            mTimeZoneIds = Objects.requireNonNull(timeZoneIds);
            return this;
        }

        /**
         * Builds a {@link LocationTimeZoneEventUnbundled} instance.
         */
        @NonNull
        public LocationTimeZoneEventUnbundled build() {
            final int internalEventType = this.mEventType;
            LocationTimeZoneEvent event = new LocationTimeZoneEvent.Builder()
                    .setEventType(internalEventType)
                    .setTimeZoneIds(mTimeZoneIds)
                    .setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())
                    .build();
            return new LocationTimeZoneEventUnbundled(event);
        }
    }

    private static int checkValidEventType(int eventType) {
        if (eventType != EVENT_TYPE_SUCCESS
                && eventType != EVENT_TYPE_UNCERTAIN
                && eventType != EVENT_TYPE_PERMANENT_FAILURE) {
            throw new IllegalStateException("eventType=" + eventType);
        }
        return eventType;
    }
}
