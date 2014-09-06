/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.android.server.usage;

import android.app.usage.UsageStatsManager;

/**
 * A handy calendar object that knows nothing of Locale's or TimeZones. This simplifies
 * interval book-keeping. It is *NOT* meant to be used as a user-facing calendar, as it has
 * no concept of Locale or TimeZone.
 */
public class UnixCalendar {
    private static final long DAY_IN_MILLIS = 24 * 60 * 60 * 1000;
    private static final long WEEK_IN_MILLIS = 7 * DAY_IN_MILLIS;
    private static final long MONTH_IN_MILLIS = 30 * DAY_IN_MILLIS;
    private static final long YEAR_IN_MILLIS = 365 * DAY_IN_MILLIS;
    private long mTime;

    public UnixCalendar(long time) {
        mTime = time;
    }

    public void truncateToDay() {
        mTime -= mTime % DAY_IN_MILLIS;
    }

    public void truncateToWeek() {
        mTime -= mTime % WEEK_IN_MILLIS;
    }

    public void truncateToMonth() {
        mTime -= mTime % MONTH_IN_MILLIS;
    }

    public void truncateToYear() {
        mTime -= mTime % YEAR_IN_MILLIS;
    }

    public void addDays(int val) {
        mTime += val * DAY_IN_MILLIS;
    }

    public void addWeeks(int val) {
        mTime += val * WEEK_IN_MILLIS;
    }

    public void addMonths(int val) {
        mTime += val * MONTH_IN_MILLIS;
    }

    public void addYears(int val) {
        mTime += val * YEAR_IN_MILLIS;
    }

    public void setTimeInMillis(long time) {
        mTime = time;
    }

    public long getTimeInMillis() {
        return mTime;
    }

    public static void truncateTo(UnixCalendar calendar, int intervalType) {
        switch (intervalType) {
            case UsageStatsManager.INTERVAL_YEARLY:
                calendar.truncateToYear();
                break;

            case UsageStatsManager.INTERVAL_MONTHLY:
                calendar.truncateToMonth();
                break;

            case UsageStatsManager.INTERVAL_WEEKLY:
                calendar.truncateToWeek();
                break;

            case UsageStatsManager.INTERVAL_DAILY:
                calendar.truncateToDay();
                break;

            default:
                throw new UnsupportedOperationException("Can't truncate date to interval " +
                        intervalType);
        }
    }
}
