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
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that represents one freeze period which repeats <em>annually</em>. A freeze period has
 * two {@link java.time#MonthDay} values that define the start and end dates of the period, both
 * inclusive. If the end date is earlier than the start date, the period is considered wrapped
 * around the year-end. As far as freeze period is concerned, leap year is disregarded and February
 * 29th should be treated as if it were February 28th: so a freeze starting or ending on February
 * 28th is identical to a freeze starting or ending on February 29th. When calulating the length of
 * a freeze or the distance bewteen two freee periods, February 29th is also ignored.
 *
 * @see SystemUpdatePolicy#setFreezePeriods
 */
public class FreezePeriod {
    private static final String TAG = "FreezePeriod";

    private static final int SENTINEL_YEAR = 2001;
    static final int DAYS_IN_YEAR = 365; // 365 since SENTINEL_YEAR is not a leap year

    private final MonthDay mStart;
    private final MonthDay mEnd;

    /*
     * Start and end dates represented by number of days since the beginning of the year.
     * They are internal representations of mStart and mEnd with normalized Leap year days
     * (Feb 29 == Feb 28 == 59th day of year). All internal calclations are based on
     * these two values so that leap year days are disregarded.
     */
    private final int mStartDay; // [1, 365]
    private final int mEndDay; // [1, 365]

    /**
     * Creates a freeze period by its start and end dates. If the end date is earlier than the start
     * date, the freeze period is considered wrapping year-end.
     */
    public FreezePeriod(MonthDay start, MonthDay end) {
        mStart = start;
        mStartDay = mStart.atYear(SENTINEL_YEAR).getDayOfYear();
        mEnd = end;
        mEndDay = mEnd.atYear(SENTINEL_YEAR).getDayOfYear();
    }

    /**
     * Returns the start date (inclusive) of this freeze period.
     */
    public MonthDay getStart() {
        return mStart;
    }

    /**
     * Returns the end date (inclusive) of this freeze period.
     */
    public MonthDay getEnd() {
        return mEnd;
    }

    /**
     * @hide
     */
    private FreezePeriod(int startDay, int endDay) {
        mStartDay = startDay;
        mStart = dayOfYearToMonthDay(startDay);
        mEndDay = endDay;
        mEnd = dayOfYearToMonthDay(endDay);
    }

    /** @hide */
    int getLength() {
        return getEffectiveEndDay() - mStartDay + 1;
    }

    /** @hide */
    boolean isWrapped() {
        return mEndDay < mStartDay;
    }

    /**
     * Returns the effective end day, taking wrapping around year-end into consideration
     * @hide
     */
    int getEffectiveEndDay() {
        if (!isWrapped()) {
            return mEndDay;
        } else {
            return mEndDay + DAYS_IN_YEAR;
        }
    }

    /** @hide */
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

    /** @hide */
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
     * @hide
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
        final LocalDate startDate = LocalDate.ofYearDay(SENTINEL_YEAR, mStartDay).withYear(
                now.getYear() + startYearAdjustment);
        final LocalDate endDate = LocalDate.ofYearDay(SENTINEL_YEAR, mEndDay).withYear(
                now.getYear() + endYearAdjustment);
        return new Pair<>(startDate, endDate);
    }

    @Override
    public String toString() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd");
        return LocalDate.ofYearDay(SENTINEL_YEAR, mStartDay).format(formatter) + " - "
                + LocalDate.ofYearDay(SENTINEL_YEAR, mEndDay).format(formatter);
    }

    /** @hide */
    private static MonthDay dayOfYearToMonthDay(int dayOfYear) {
        LocalDate date = LocalDate.ofYearDay(SENTINEL_YEAR, dayOfYear);
        return MonthDay.of(date.getMonth(), date.getDayOfMonth());
    }

    /**
     * Treat the supplied date as in a non-leap year and return its day of year.
     * @hide
     */
    private static int dayOfYearDisregardLeapYear(LocalDate date) {
        return date.withYear(SENTINEL_YEAR).getDayOfYear();
    }

    /**
     * Compute the number of days between first (inclusive) and second (exclusive),
     * treating all years in between as non-leap.
     * @hide
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
    static List<FreezePeriod> canonicalizePeriods(List<FreezePeriod> intervals) {
        boolean[] taken = new boolean[DAYS_IN_YEAR];
        // First convert the intervals into flat array
        for (FreezePeriod interval : intervals) {
            for (int i = interval.mStartDay; i <= interval.getEffectiveEndDay(); i++) {
                taken[(i - 1) % DAYS_IN_YEAR] = true;
            }
        }
        // Then reconstruct intervals from the array
        List<FreezePeriod> result = new ArrayList<>();
        int i = 0;
        while (i < DAYS_IN_YEAR) {
            if (!taken[i]) {
                i++;
                continue;
            }
            final int intervalStart = i + 1;
            while (i < DAYS_IN_YEAR && taken[i]) i++;
            result.add(new FreezePeriod(intervalStart, i));
        }
        // Check if the last entry can be merged to the first entry to become one single
        // wrapped interval
        final int lastIndex = result.size() - 1;
        if (lastIndex > 0 && result.get(lastIndex).mEndDay == DAYS_IN_YEAR
                && result.get(0).mStartDay == 1) {
            FreezePeriod wrappedInterval = new FreezePeriod(result.get(lastIndex).mStartDay,
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
    static void validatePeriods(List<FreezePeriod> periods) {
        List<FreezePeriod> allPeriods = FreezePeriod.canonicalizePeriods(periods);
        if (allPeriods.size() != periods.size()) {
            throw SystemUpdatePolicy.ValidationFailedException.duplicateOrOverlapPeriods();
        }
        for (int i = 0; i < allPeriods.size(); i++) {
            FreezePeriod current = allPeriods.get(i);
            if (current.getLength() > SystemUpdatePolicy.FREEZE_PERIOD_MAX_LENGTH) {
                throw SystemUpdatePolicy.ValidationFailedException.freezePeriodTooLong("Freeze "
                        + "period " + current + " is too long: " + current.getLength() + " days");
            }
            FreezePeriod previous = i > 0 ? allPeriods.get(i - 1)
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
    static void validateAgainstPreviousFreezePeriod(List<FreezePeriod> periods,
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
        List<FreezePeriod> allPeriods = FreezePeriod.canonicalizePeriods(periods);
        // Given current time now, find the freeze period that's either current, or the one
        // that's immediately afterwards. For the later case, it might be after the year-end,
        // but this can only happen if there is only one freeze period.
        FreezePeriod curOrNextFreezePeriod = allPeriods.get(0);
        for (FreezePeriod interval : allPeriods) {
            if (interval.contains(now)
                    || interval.mStartDay > FreezePeriod.dayOfYearDisregardLeapYear(now)) {
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
        long separation = FreezePeriod.distanceWithoutLeapYear(curOrNextFreezeDates.first,
                prevPeriodEnd) - 1;
        if (separation > 0) {
            // Two intervals do not overlap, check separation
            if (separation < SystemUpdatePolicy.FREEZE_PERIOD_MIN_SEPARATION) {
                throw ValidationFailedException.combinedPeriodTooClose("Previous freeze period "
                        + "too close to new period: " + separation + ", " + periodsDescription);
            }
        } else {
            // Two intervals overlap, check combined length
            long length = FreezePeriod.distanceWithoutLeapYear(curOrNextFreezeDates.second,
                    prevPeriodStart) + 1;
            if (length > SystemUpdatePolicy.FREEZE_PERIOD_MAX_LENGTH) {
                throw ValidationFailedException.combinedPeriodTooLong("Combined freeze period "
                        + "exceeds maximum days: " + length + ", " + periodsDescription);
            }
        }
    }
}
