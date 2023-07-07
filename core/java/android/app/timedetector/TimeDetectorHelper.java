/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.os.Build;

import java.time.Instant;

/**
 * A utility class for fundamental time detector-related logic that doesn't need to communicate with
 * the time detector service, i.e. because it can use SDK APIs or hard-coded facts, and doesn't need
 * permissions or singleton state. Putting logic here avoids the need to expose binder-based calls
 * or to duplicate code to share related logic (since android.app.timedetector classes are visible
 * to all processes).
 *
 * @hide
 */
// Not final for easy replacement / mocking during tests.
public class TimeDetectorHelper {

    /**
     * See {@link #getManualDateSelectionYearMin()}. Chosen to produce Unix epoch times be greater
     * than {@link #MANUAL_SUGGESTION_LOWER_BOUND}.
     */
    private static final int MANUAL_SUGGESTION_YEAR_MIN = 2015;

    /**
     * The maximum gregorian calendar year to allow for manual date selection on devices unlikely to
     * have Y2038 issues. This serves as a sensible UI-enforced limit though the system server may
     * support a larger upper bound. Users besides future archeologists are unlikely to need higher
     * values, for a few years at least.
     */
    private static final int MANUAL_SUGGESTION_YEAR_MAX_WITHOUT_Y2038_ISSUE = 2100;

    /**
     * The maximum gregorian calendar year to allow for manual date selection on devices that may
     * have Y2038 issues. This serves as a sensible UI-enforced limit though the system server may
     * support a larger upper bound. That is, the signed 32-bit milliseconds value is
     * 03:14:07 UTC on 19 January 2038, but this constant means users can only enter dates up to
     * 2037-12-31. See {@link #MANUAL_SUGGESTION_YEAR_MAX_WITH_Y2038_ISSUE}.
     *
     * <p>Note: This UI limit also doesn't prevent devices reaching the Y2038 roll-over time through
     * the natural passage of time, it just prevents users potentially causing issues in the years
     * leading up to it accidentally via the UI.
     */
    private static final int MANUAL_SUGGESTION_YEAR_MAX_WITH_Y2038_ISSUE = 2037;

    /**
     * The upper bound for valid suggestions when the Y2038 issue is a risk. This is the instant
     * when the Y2038 issue occurs.
     */
    private static final Instant SUGGESTION_UPPER_BOUND_WITH_Y2038_ISSUE =
            Instant.ofEpochMilli(1000L * Integer.MAX_VALUE);

    /**
     * The upper bound for valid suggestions when the Y2038 issue is not a risk. This values means
     * there is no practical upper bound.
     *
     * <p>Make sure this value remains in the value representable as a signed int64 Unix epoch
     * millis value as in various places {@link Instant#toEpochMilli()} is called, and that throws
     * an exception if the value is too large.
     */
    private static final Instant SUGGESTION_UPPER_BOUND_WIITHOUT_Y2038_ISSUE =
            Instant.ofEpochMilli(Long.MAX_VALUE);

    /** See {@link #getManualSuggestionLowerBound()}. */
    private static final Instant MANUAL_SUGGESTION_LOWER_BOUND =
            Instant.ofEpochMilli(1415491200000L); // Nov 5, 2014, 0:00 UTC

    /**
     * The lowest value in Unix epoch milliseconds that is considered a valid automatic suggestion.
     * See also {@link #MANUAL_SUGGESTION_LOWER_BOUND}.
     *
     * <p>Note that this is a default value. The lower value enforced can be overridden to be
     * lower in the system server with flags for testing.
     */
    private static final Instant AUTO_SUGGESTION_LOWER_BOUND_DEFAULT = Instant.ofEpochMilli(
            Long.max(android.os.Environment.getRootDirectory().lastModified(), Build.TIME));

    /** The singleton instance of this class. */
    public static final TimeDetectorHelper INSTANCE = new TimeDetectorHelper();

    /** Constructor present for subclassing in tests. Use {@link #INSTANCE} in production code. */
    protected TimeDetectorHelper() {}

    /**
     * Returns the minimum gregorian calendar year to offer for manual date selection. This serves
     * as a sensible UI-enforced lower limit, the system server may support a smaller lower bound.
     */
    public int getManualDateSelectionYearMin() {
        return MANUAL_SUGGESTION_YEAR_MIN;
    }

    /**
     * Returns the maximum gregorian calendar year to offer for manual date selection. This serves
     * as a sensible UI-enforced lower limit, the system server may support a larger upper bound.
     */
    public int getManualDateSelectionYearMax() {
        return getDeviceHasY2038Issue()
                ? MANUAL_SUGGESTION_YEAR_MAX_WITH_Y2038_ISSUE
                : MANUAL_SUGGESTION_YEAR_MAX_WITHOUT_Y2038_ISSUE;
    }

    /**
     * Returns the lowest value in Unix epoch milliseconds that is considered a valid manual
     * suggestion. For historical reasons Android has a different lower limit for manual input than
     * automatic. This may change in the future to align with automatic suggestions, but has been
     * kept initially to avoid breaking manual tests that are hard-coded with old dates real users
     * will never want to use.
     */
    @NonNull
    public Instant getManualSuggestionLowerBound() {
        return MANUAL_SUGGESTION_LOWER_BOUND;
    }

    /**
     * Returns the lowest value in Unix epoch milliseconds that is considered a valid automatic
     * suggestion. See also {@link #MANUAL_SUGGESTION_LOWER_BOUND}.
     *
     * <p>Note that this is a default value. The lower value enforced can be overridden to be
     * different in the system server with server flags.
     */
    @NonNull
    public Instant getAutoSuggestionLowerBoundDefault() {
        return AUTO_SUGGESTION_LOWER_BOUND_DEFAULT;
    }

    /** Returns the upper bound to enforce for all time suggestions (manual and automatic). */
    @NonNull
    public Instant getSuggestionUpperBound() {
        return getDeviceHasY2038Issue()
                ? SUGGESTION_UPPER_BOUND_WITH_Y2038_ISSUE
                : SUGGESTION_UPPER_BOUND_WIITHOUT_Y2038_ISSUE;
    }

    /**
     * Returns {@code true} if the device may be at risk of time_t overflow (because bionic
     * defines time_t as a 32-bit signed integer for 32-bit processes).
     */
    private boolean getDeviceHasY2038Issue() {
        return Build.SUPPORTED_32_BIT_ABIS.length > 0;
    }
}
