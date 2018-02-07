/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.app.admin;

import android.app.admin.SystemUpdatePolicy.ValidationFailedException;
import android.util.Log;
import android.util.Pair;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * An interval representing one freeze period which repeats annually. We use the number of days
 * since the start of (non-leap) year to define the start and end dates of an interval, both
 * inclusive. If the end date is smaller than the start date, the interval is considered wrapped
 * around the year-end. As far as an interval is concerned, February 29th should be treated as
 * if it were February 28th: so an interval starting or ending on February 28th are not
 * distinguishable from an interval on February 29th. When calulating interval length or
 * distance between two dates, February 29th is also disregarded.
 *
 * @see SystemUpdatePolicy#setFreezePeriods
 * @hide
 */
public class FreezeInterval {
    private static final String TAG = "FreezeInterval";

    private static final int DUMMY_YEAR = 2001;
    static final int DAYS_IN_YEAR = 365; // 365 since DUMMY_YEAR is not a leap year

    final int mStartDay; // [1,365]
    final int mEndDay; // [1,365]

    FreezeInterval(int startDay, int endDay) {
        if (startDay < 1 || startDay > 365 || endDay < 1 || endDay > 365) {
            throw new RuntimeException("Bad dates for Interval: " + startDay + "," + endDay);
        }
        mStartDay = startDay;
        mEndDay = endDay;
    }

    int getLength() {
        return getEffectiveEndDay() - mStartDay + 1;
    }

    boolean isWrapped() {
        return mEndDay < mStartDay;
    }

    /**
     * Returns the effective end day, taking wrapping around year-end into consideration
     */
    int getEffectiveEndDay() {
        if (!isWrapped()) {
            return mEndDay;
        } else {
            return mEndDay + DAYS_IN_YEAR;
        }
    }

    boolean contains(LocalDate localDate) {
        final int daysOfYear = dayOfYearDisregardLeapYear(localDate);
        if (!isWrapped()) {
            // ---[start---now---end]---
            return (mStartDay <= daysOfYear) && (daysOfYear <= mEndDay);
        } else {
            //    ---end]---[start---now---
            // or ---now---end]---[start---
            return (mStartDay <= daysOfYear) || (daysOfYear <= mEndDay);
        }
    }

    boolean after(LocalDate localDate) {
        return mStartDay > dayOfYearDisregardLeapYear(localDate);
    }

    /**
     * Instantiate the current interval to real calendar dates, given a calendar date
     * {@code now}. If the interval contains now, the returned calendar dates should be the
     * current interval (in real calendar dates) that includes now. If the interval does not
     * include now, the returned dates represents the next future interval.
     * The result will always have the same month and dayOfMonth value as the non-instantiated
     * interval itself.
     */
    Pair<LocalDate, LocalDate> toCurrentOrFutureRealDates(LocalDate now) {
        final int nowDays = dayOfYearDisregardLeapYear(now);
        final int startYearAdjustment, endYearAdjustment;
        if (contains(now)) {
            // current interval
            if (mStartDay <= nowDays) {
                //    ----------[start---now---end]---
                // or ---end]---[start---now----------
                startYearAdjustment = 0;
                endYearAdjustment = isWrapped() ? 1 : 0;
            } else /* nowDays <= mEndDay */ {
                // or ---now---end]---[start----------
                startYearAdjustment = -1;
                endYearAdjustment = 0;
            }
        } else {
            // next interval
            if (mStartDay > nowDays) {
                //    ----------now---[start---end]---
                // or ---end]---now---[start----------
                startYearAdjustment = 0;
                endYearAdjustment = isWrapped() ? 1 : 0;
            } else /* mStartDay <= nowDays */ {
                // or ---[start---end]---now----------
                startYearAdjustment = 1;
                endYearAdjustment = 1;
            }
        }
        final LocalDate startDate = LocalDate.ofYearDay(DUMMY_YEAR, mStartDay).withYear(
                now.getYear() + startYearAdjustment);
        final LocalDate endDate = LocalDate.ofYearDay(DUMMY_YEAR, mEndDay).withYear(
                now.getYear() + endYearAdjustment);
        return new Pair<>(startDate, endDate);
    }

    @Override
    public String toString() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd");
        return LocalDate.ofYearDay(DUMMY_YEAR, mStartDay).format(formatter) + " - "
                + LocalDate.ofYearDay(DUMMY_YEAR, mEndDay).format(formatter);
    }

    // Treat the supplied date as in a non-leap year and return its day of year.
    static int dayOfYearDisregardLeapYear(LocalDate date) {
        return date.withYear(DUMMY_YEAR).getDayOfYear();
    }

    /**
     * Compute the number of days between first (inclusive) and second (exclusive),
     * treating all years in between as non-leap.
     */
    public static int distanceWithoutLeapYear(LocalDate first, LocalDate second) {
        return dayOfYearDisregardLeapYear(first) - dayOfYearDisregardLeapYear(second)
                + DAYS_IN_YEAR * (first.getYear() - second.getYear());
    }

    /**
     * Sort, de-duplicate and merge an interval list
     *
     * Instead of using any fancy logic for merging intervals which has loads of corner cases,
     * simply flatten the interval onto a list of 365 calendar days and recreate the interval list
     * from that.
     *
     * This method should return a list of intervals with the following post-conditions:
     *     1. Interval.startDay in strictly ascending order
     *     2. No two intervals should overlap or touch
     *     3. At most one wrapped Interval remains, and it will be at the end of the list
     * @hide
     */
    protected static List<FreezeInterval> canonicalizeIntervals(List<FreezeInterval> intervals) {
        boolean[] taken = new boolean[DAYS_IN_YEAR];
        // First convert the intervals into flat array
        for (FreezeInterval interval : intervals) {
            for (int i = interval.mStartDay; i <= interval.getEffectiveEndDay(); i++) {
                taken[(i - 1) % DAYS_IN_YEAR] = true;
            }
        }
        // Then reconstruct intervals from the array
        List<FreezeInterval> result = new ArrayList<>();
        int i = 0;
        while (i < DAYS_IN_YEAR) {
            if (!taken[i]) {
                i++;
                continue;
            }
            final int intervalStart = i + 1;
            while (i < DAYS_IN_YEAR && taken[i]) i++;
            result.add(new FreezeInterval(intervalStart, i));
        }
        // Check if the last entry can be merged to the first entry to become one single
        // wrapped interval
        final int lastIndex = result.size() - 1;
        if (lastIndex > 0 && result.get(lastIndex).mEndDay == DAYS_IN_YEAR
                && result.get(0).mStartDay == 1) {
            FreezeInterval wrappedInterval = new FreezeInterval(result.get(lastIndex).mStartDay,
                    result.get(0).mEndDay);
            result.set(lastIndex, wrappedInterval);
            result.remove(0);
        }
        return result;
    }

    /**
     * Verifies if the supplied freeze periods satisfies the constraints set out in
     * {@link SystemUpdatePolicy#setFreezePeriods(List)}, and in particular, any single freeze
     * period cannot exceed {@link SystemUpdatePolicy#FREEZE_PERIOD_MAX_LENGTH} days, and two freeze
     * periods need to be at least {@link SystemUpdatePolicy#FREEZE_PERIOD_MIN_SEPARATION} days
     * apart.
     *
     * @hide
     */
    protected static void validatePeriods(List<FreezeInterval> periods) {
        List<FreezeInterval> allPeriods = FreezeInterval.canonicalizeIntervals(periods);
        if (allPeriods.size() != periods.size()) {
            throw SystemUpdatePolicy.ValidationFailedException.duplicateOrOverlapPeriods();
        }
        for (int i = 0; i < allPeriods.size(); i++) {
            FreezeInterval current = allPeriods.get(i);
            if (current.getLength() > SystemUpdatePolicy.FREEZE_PERIOD_MAX_LENGTH) {
                throw SystemUpdatePolicy.ValidationFailedException.freezePeriodTooLong("Freeze "
                        + "period " + current + " is too long: " + current.getLength() + " days");
            }
            FreezeInterval previous = i > 0 ? allPeriods.get(i - 1)
                    : allPeriods.get(allPeriods.size() - 1);
            if (previous != current) {
                final int separation;
                if (i == 0 && !previous.isWrapped()) {
                    // -->[current]---[-previous-]<---
                    separation = current.mStartDay
                            + (DAYS_IN_YEAR - previous.mEndDay) - 1;
                } else {
                    //    --[previous]<--->[current]---------
                    // OR ----prev---]<--->[current]---[prev-
                    separation = current.mStartDay - previous.mEndDay - 1;
                }
                if (separation < SystemUpdatePolicy.FREEZE_PERIOD_MIN_SEPARATION) {
                    throw SystemUpdatePolicy.ValidationFailedException.freezePeriodTooClose("Freeze"
                            + " periods " + previous + " and " + current + " are too close "
                            + "together: " + separation + " days apart");
                }
            }
        }
    }

    /**
     * Verifies that the current freeze periods are still legal, considering the previous freeze
     * periods the device went through. In particular, when combined with the previous freeze
     * period, the maximum freeze length or the minimum freeze separation should not be violated.
     *
     * @hide
     */
    protected static void validateAgainstPreviousFreezePeriod(List<FreezeInterval> periods,
            LocalDate prevPeriodStart, LocalDate prevPeriodEnd, LocalDate now) {
        if (periods.size() == 0 || prevPeriodStart == null || prevPeriodEnd == null) {
            return;
        }
        if (prevPeriodStart.isAfter(now) || prevPeriodEnd.isAfter(now)) {
            Log.w(TAG, "Previous period (" + prevPeriodStart + "," + prevPeriodEnd + ") is after"
                    + " current date " + now);
            // Clock was adjusted backwards. We can continue execution though, the separation
            // and length validation below still works under this condition.
        }
        List<FreezeInterval> allPeriods = FreezeInterval.canonicalizeIntervals(periods);
        // Given current time now, find the freeze period that's either current, or the one
        // that's immediately afterwards. For the later case, it might be after the year-end,
        // but this can only happen if there is only one freeze period.
        FreezeInterval curOrNextFreezePeriod = allPeriods.get(0);
        for (FreezeInterval interval : allPeriods) {
            if (interval.contains(now)
                    || interval.mStartDay > FreezeInterval.dayOfYearDisregardLeapYear(now)) {
                curOrNextFreezePeriod = interval;
                break;
            }
        }
        Pair<LocalDate, LocalDate> curOrNextFreezeDates = curOrNextFreezePeriod
                .toCurrentOrFutureRealDates(now);
        if (now.isAfter(curOrNextFreezeDates.first)) {
            curOrNextFreezeDates = new Pair<>(now, curOrNextFreezeDates.second);
        }
        if (curOrNextFreezeDates.first.isAfter(curOrNextFreezeDates.second)) {
            throw new IllegalStateException("Current freeze dates inverted: "
                    + curOrNextFreezeDates.first + "-" + curOrNextFreezeDates.second);
        }
        // Now validate [prevPeriodStart, prevPeriodEnd] against curOrNextFreezeDates
        final String periodsDescription = "Prev: " + prevPeriodStart + "," + prevPeriodEnd
                + "; cur: " + curOrNextFreezeDates.first + "," + curOrNextFreezeDates.second;
        long separation = FreezeInterval.distanceWithoutLeapYear(curOrNextFreezeDates.first,
                prevPeriodEnd) - 1;
        if (separation > 0) {
            // Two intervals do not overlap, check separation
            if (separation < SystemUpdatePolicy.FREEZE_PERIOD_MIN_SEPARATION) {
                throw ValidationFailedException.combinedPeriodTooClose("Previous freeze period "
                        + "too close to new period: " + separation + ", " + periodsDescription);
            }
        } else {
            // Two intervals overlap, check combined length
            long length = FreezeInterval.distanceWithoutLeapYear(curOrNextFreezeDates.second,
                    prevPeriodStart) + 1;
            if (length > SystemUpdatePolicy.FREEZE_PERIOD_MAX_LENGTH) {
                throw ValidationFailedException.combinedPeriodTooLong("Combined freeze period "
                        + "exceeds maximum days: " + length + ", " + periodsDescription);
            }
        }
    }
}
