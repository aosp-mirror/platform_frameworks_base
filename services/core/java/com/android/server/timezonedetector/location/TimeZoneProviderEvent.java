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

package com.android.server.timezonedetector.location;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.service.timezone.TimeZoneProviderService;
import android.service.timezone.TimeZoneProviderSuggestion;

import java.util.Objects;

/**
 * An event from a {@link TimeZoneProviderService}.
 */
final class TimeZoneProviderEvent {

    @IntDef(prefix = "EVENT_TYPE_",
            value = { EVENT_TYPE_PERMANENT_FAILURE, EVENT_TYPE_SUGGESTION, EVENT_TYPE_UNCERTAIN })
    public @interface EventType {}

    /**
     * The provider failed permanently. See {@link
     * TimeZoneProviderService#reportPermanentFailure(Throwable)}
     */
    public static final int EVENT_TYPE_PERMANENT_FAILURE = 1;

    /**
     * The provider made a suggestion. See {@link
     * TimeZoneProviderService#reportSuggestion(TimeZoneProviderSuggestion)}
     */
    public static final int EVENT_TYPE_SUGGESTION = 2;

    /**
     * The provider was uncertain about the time zone. See {@link
     * TimeZoneProviderService#reportUncertain()}
     */
    public static final int EVENT_TYPE_UNCERTAIN = 3;

    private static final TimeZoneProviderEvent UNCERTAIN_EVENT =
            new TimeZoneProviderEvent(EVENT_TYPE_UNCERTAIN, null, null);

    @EventType
    private final int mType;

    @Nullable
    private final TimeZoneProviderSuggestion mSuggestion;

    @Nullable
    private final String mFailureCause;

    private TimeZoneProviderEvent(@EventType int type,
            @Nullable TimeZoneProviderSuggestion suggestion,
            @Nullable String failureCause) {
        mType = type;
        mSuggestion = suggestion;
        mFailureCause = failureCause;
    }

    /** Returns a event of type {@link #EVENT_TYPE_SUGGESTION}. */
    public static TimeZoneProviderEvent createSuggestionEvent(
            @NonNull TimeZoneProviderSuggestion suggestion) {
        return new TimeZoneProviderEvent(EVENT_TYPE_SUGGESTION,
                Objects.requireNonNull(suggestion), null);
    }

    /** Returns a event of type {@link #EVENT_TYPE_UNCERTAIN}. */
    public static TimeZoneProviderEvent createUncertainEvent() {
        return UNCERTAIN_EVENT;
    }

    /** Returns a event of type {@link #EVENT_TYPE_PERMANENT_FAILURE}. */
    public static TimeZoneProviderEvent createPermanentFailureEvent(@NonNull String cause) {
        return new TimeZoneProviderEvent(EVENT_TYPE_PERMANENT_FAILURE, null,
                Objects.requireNonNull(cause));
    }

    /**
     * Returns the event type.
     */
    public @EventType int getType() {
        return mType;
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

    @Override
    public String toString() {
        return "TimeZoneProviderEvent{"
                + "mType=" + mType
                + ", mSuggestion=" + mSuggestion
                + ", mFailureCause=" + mFailureCause
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
        TimeZoneProviderEvent that = (TimeZoneProviderEvent) o;
        return mType == that.mType
                && Objects.equals(mSuggestion, that.mSuggestion)
                && Objects.equals(mFailureCause, that.mFailureCause);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mSuggestion, mFailureCause);
    }
}
