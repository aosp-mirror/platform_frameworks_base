/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.util;

import java.util.Calendar;

/**
 * Helps answer common questions that come up when displaying a month in a
 * 6 row calendar grid format.
 *
 * Not thread safe.
 */
public class MonthDisplayHelper {

    // display pref
    private final int mWeekStartDay;

    // holds current month, year, helps compute display
    private Calendar mCalendar;

    // cached computed stuff that helps with display
    private int mNumDaysInMonth;
    private int mNumDaysInPrevMonth;
    private int mOffset;


    /**
     * @param year The year.
     * @param month The month.
     * @param weekStartDay What day of the week the week should start.
     */
    public MonthDisplayHelper(int year, int month, int weekStartDay) {

        if (weekStartDay < Calendar.SUNDAY || weekStartDay > Calendar.SATURDAY) {
            throw new IllegalArgumentException();
        }
        mWeekStartDay = weekStartDay;

        mCalendar = Calendar.getInstance();
        mCalendar.set(Calendar.YEAR, year);
        mCalendar.set(Calendar.MONTH, month);
        mCalendar.set(Calendar.DAY_OF_MONTH, 1);
        mCalendar.set(Calendar.HOUR_OF_DAY, 0);
        mCalendar.set(Calendar.MINUTE, 0);
        mCalendar.set(Calendar.SECOND, 0);
        mCalendar.getTimeInMillis();

        recalculate();
    }


    public MonthDisplayHelper(int year, int month) {
        this(year, month, Calendar.SUNDAY);
    }


    public int getYear() {
        return mCalendar.get(Calendar.YEAR);
    }

    public int getMonth() {
        return mCalendar.get(Calendar.MONTH);
    }


    public int getWeekStartDay() {
        return mWeekStartDay;
    }

    /**
     * @return The first day of the month using a constants such as
     *   {@link java.util.Calendar#SUNDAY}.
     */
    public int getFirstDayOfMonth() {
        return mCalendar.get(Calendar.DAY_OF_WEEK);
    }

    /**
     * @return The number of days in the month.
     */
    public int getNumberOfDaysInMonth() {
        return mNumDaysInMonth;
    }


    /**
     * @return The offset from displaying everything starting on the very first
     *   box.  For example, if the calendar is set to display the first day of
     *   the week as Sunday, and the month starts on a Wednesday, the offset is 3.
     */
    public int getOffset() {
        return mOffset;
    }


    /**
     * @param row Which row (0-5).
     * @return the digits of the month to display in one
     * of the 6 rows of a calendar month display.
     */
    public int[] getDigitsForRow(int row) {
        if (row < 0 || row > 5) {
            throw new IllegalArgumentException("row " + row
                    + " out of range (0-5)");
        }

        int [] result = new int[7];
        for (int column = 0; column < 7; column++) {
            result[column] = getDayAt(row, column);
        }

        return result;
    }

    /**
     * @param row The row, 0-5, starting from the top.
     * @param column The column, 0-6, starting from the left.
     * @return The day at a particular row, column
     */
    public int getDayAt(int row, int column) {

        if (row == 0 && column < mOffset) {
            return mNumDaysInPrevMonth + column - mOffset + 1;
        }

        int day = 7 * row + column - mOffset + 1;

        return (day > mNumDaysInMonth) ?
                day - mNumDaysInMonth : day;
    }

    /**
     * @return Which row day is in.
     */
    public int getRowOf(int day) {
        return (day + mOffset - 1) / 7;
    }

    /**
     * @return Which column day is in.
     */
    public int getColumnOf(int day) {
        return (day + mOffset - 1) % 7;
    }

    /**
     * Decrement the month.
     */
    public void previousMonth() {
        mCalendar.add(Calendar.MONTH, -1);
        recalculate();
    }

    /**
     * Increment the month.
     */
    public void nextMonth() {
        mCalendar.add(Calendar.MONTH, 1);
        recalculate();
    }

    /**
     * @return Whether the row and column fall within the month.
     */
    public boolean isWithinCurrentMonth(int row, int column) {

        if (row < 0 || column < 0 || row > 5 || column > 6) {
            return false;
        }

        if (row == 0 && column < mOffset) {
            return false;
        }

        int day = 7 * row + column - mOffset + 1;
        if (day > mNumDaysInMonth) {
            return false;
        }
        return true;
    }


    // helper method that recalculates cached values based on current month / year
    private void recalculate() {

        mNumDaysInMonth = mCalendar.getActualMaximum(Calendar.DAY_OF_MONTH);

        mCalendar.add(Calendar.MONTH, -1);
        mNumDaysInPrevMonth = mCalendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        mCalendar.add(Calendar.MONTH, 1);

        int firstDayOfMonth = getFirstDayOfMonth();
        int offset = firstDayOfMonth - mWeekStartDay;
        if (offset < 0) {
            offset += 7;
        }
        mOffset = offset;
    }
}
