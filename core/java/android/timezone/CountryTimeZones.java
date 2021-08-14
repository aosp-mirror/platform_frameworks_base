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

package android.timezone;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.icu.util.TimeZone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Information about a country's time zones.
 *
 * @hide
 */
public final class CountryTimeZones {

    /**
     * A mapping to a time zone ID with some associated metadata.
     *
     * @hide
     */
    public static final class TimeZoneMapping {

        @NonNull
        private com.android.i18n.timezone.CountryTimeZones.TimeZoneMapping mDelegate;

        TimeZoneMapping(com.android.i18n.timezone.CountryTimeZones.TimeZoneMapping delegate) {
            this.mDelegate = Objects.requireNonNull(delegate);
        }

        /**
         * Returns the ID for this mapping. The ID is a tzdb time zone identifier like
         * "America/Los_Angeles" that can be used with methods such as {@link
         * TimeZone#getFrozenTimeZone(String)}. See {@link #getTimeZone()} which returns a frozen
         * {@link TimeZone} object.
         */
        @NonNull
        public String getTimeZoneId() {
            return mDelegate.getTimeZoneId();
        }

        /**
         * Returns a frozen {@link TimeZone} object for this mapping.
         */
        @NonNull
        public TimeZone getTimeZone() {
            return mDelegate.getTimeZone();
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TimeZoneMapping that = (TimeZoneMapping) o;
            return this.mDelegate.equals(that.mDelegate);
        }

        @Override
        public int hashCode() {
            return this.mDelegate.hashCode();
        }

        @Override
        public String toString() {
            return mDelegate.toString();
        }
    }

    /**
     * The result of lookup up a time zone using offset information (and possibly more).
     *
     * @hide
     */
    public static final class OffsetResult {

        private final TimeZone mTimeZone;
        private final boolean mIsOnlyMatch;

        /** Creates an instance with the supplied information. */
        public OffsetResult(@NonNull TimeZone timeZone, boolean isOnlyMatch) {
            mTimeZone = Objects.requireNonNull(timeZone);
            mIsOnlyMatch = isOnlyMatch;
        }

        /**
         * Returns a time zone that matches the supplied criteria.
         */
        @NonNull
        public TimeZone getTimeZone() {
            return mTimeZone;
        }

        /**
         * Returns {@code true} if there is only one matching time zone for the supplied criteria.
         */
        public boolean isOnlyMatch() {
            return mIsOnlyMatch;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            OffsetResult that = (OffsetResult) o;
            return mIsOnlyMatch == that.mIsOnlyMatch
                    && mTimeZone.getID().equals(that.mTimeZone.getID());
        }

        @Override
        public int hashCode() {
            return Objects.hash(mTimeZone, mIsOnlyMatch);
        }

        @Override
        public String toString() {
            return "OffsetResult{"
                    + "mTimeZone(ID)=" + mTimeZone.getID()
                    + ", mIsOnlyMatch=" + mIsOnlyMatch
                    + '}';
        }
    }

    @NonNull
    private final com.android.i18n.timezone.CountryTimeZones mDelegate;

    CountryTimeZones(com.android.i18n.timezone.CountryTimeZones delegate) {
        mDelegate = delegate;
    }

    /**
     * Returns true if the ISO code for the country is a case-insensitive match for the one
     * supplied.
     */
    public boolean matchesCountryCode(@NonNull String countryIso) {
        return mDelegate.matchesCountryCode(countryIso);
    }

    /**
     * Returns the default time zone ID for the country. Can return {@code null} in cases when no
     * data is available or the time zone ID was not recognized.
     */
    @Nullable
    public String getDefaultTimeZoneId() {
        return mDelegate.getDefaultTimeZoneId();
    }

    /**
     * Returns the default time zone for the country. Can return {@code null} in cases when no data
     * is available or the time zone ID was not recognized.
     */
    @Nullable
    public TimeZone getDefaultTimeZone() {
        return mDelegate.getDefaultTimeZone();
    }

    /**
     * Qualifier for a country's default time zone. {@code true} indicates that the country's
     * default time zone would be a good choice <em>generally</em> when there's no UTC offset
     * information available. This will only be {@code true} in countries with multiple zones where
     * a large majority of the population is covered by only one of them.
     */
    public boolean isDefaultTimeZoneBoosted() {
        return mDelegate.isDefaultTimeZoneBoosted();
    }

    /**
     * Returns {@code true} if the country has at least one time zone that uses UTC at the given
     * time. This is an efficient check when trying to validate received UTC offset information.
     * For example, there are situations when a detected zero UTC offset cannot be distinguished
     * from "no information available" or a corrupted signal. This method is useful because checking
     * offset information for large countries is relatively expensive but it is generally only the
     * countries close to the prime meridian that use UTC at <em>any</em> time of the year.
     *
     * @param whenMillis the time the offset information is for in milliseconds since the beginning
     *     of the Unix epoch
     */
    public boolean hasUtcZone(long whenMillis) {
        return mDelegate.hasUtcZone(whenMillis);
    }

    /**
     * Returns a time zone for the country, if there is one, that matches the supplied properties.
     * If there are multiple matches and the {@code bias} is one of them then it is returned,
     * otherwise an arbitrary match is returned based on the {@link
     * #getEffectiveTimeZoneMappingsAt(long)} ordering.
     *
     * @param whenMillis the UTC time to match against
     * @param bias the time zone to prefer, can be {@code null} to indicate there is no preference
     * @param totalOffsetMillis the offset from UTC at {@code whenMillis}
     * @param isDst the Daylight Savings Time state at {@code whenMillis}. {@code true} means DST,
     *     {@code false} means not DST
     * @return an {@link OffsetResult} with information about a matching zone, or {@code null} if
     *     there is no match
     */
    @Nullable
    public OffsetResult lookupByOffsetWithBias(long whenMillis, @Nullable TimeZone bias,
            int totalOffsetMillis, boolean isDst) {
        com.android.i18n.timezone.CountryTimeZones.OffsetResult delegateOffsetResult =
                mDelegate.lookupByOffsetWithBias(
                        whenMillis, bias, totalOffsetMillis, isDst);
        return delegateOffsetResult == null ? null :
                new OffsetResult(
                        delegateOffsetResult.getTimeZone(), delegateOffsetResult.isOnlyMatch());
    }

    /**
     * Returns a time zone for the country, if there is one, that matches the supplied properties.
     * If there are multiple matches and the {@code bias} is one of them then it is returned,
     * otherwise an arbitrary match is returned based on the {@link
     * #getEffectiveTimeZoneMappingsAt(long)} ordering.
     *
     * @param whenMillis the UTC time to match against
     * @param bias the time zone to prefer, can be {@code null} to indicate there is no preference
     * @param totalOffsetMillis the offset from UTC at {@code whenMillis}
     * @return an {@link OffsetResult} with information about a matching zone, or {@code null} if
     *     there is no match
     */
    @Nullable
    public OffsetResult lookupByOffsetWithBias(long whenMillis, @Nullable TimeZone bias,
            int totalOffsetMillis) {
        com.android.i18n.timezone.CountryTimeZones.OffsetResult delegateOffsetResult =
                mDelegate.lookupByOffsetWithBias(whenMillis, bias, totalOffsetMillis);
        return delegateOffsetResult == null ? null :
                new OffsetResult(
                        delegateOffsetResult.getTimeZone(), delegateOffsetResult.isOnlyMatch());
    }

    /**
     * Returns an immutable, ordered list of time zone mappings for the country in an undefined but
     * "priority" order, filtered so that only "effective" time zone IDs are returned. An
     * "effective" time zone is one that differs from another time zone used in the country after
     * {@code whenMillis}. The list can be empty if there were no zones configured or the configured
     * zone IDs were not recognized.
     */
    @NonNull
    public List<TimeZoneMapping> getEffectiveTimeZoneMappingsAt(long whenMillis) {
        List<com.android.i18n.timezone.CountryTimeZones.TimeZoneMapping> delegateList =
                mDelegate.getEffectiveTimeZoneMappingsAt(whenMillis);

        List<TimeZoneMapping> toReturn = new ArrayList<>(delegateList.size());
        for (com.android.i18n.timezone.CountryTimeZones.TimeZoneMapping delegateMapping
                : delegateList) {
            toReturn.add(new TimeZoneMapping(delegateMapping));
        }
        return Collections.unmodifiableList(toReturn);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CountryTimeZones that = (CountryTimeZones) o;
        return mDelegate.equals(that.mDelegate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDelegate);
    }

    @Override
    public String toString() {
        return mDelegate.toString();
    }
}
