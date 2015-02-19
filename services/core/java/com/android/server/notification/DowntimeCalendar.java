/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;

import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.DowntimeInfo;
import android.util.ArraySet;

public class DowntimeCalendar {

    private final ArraySet<Integer> mDays = new ArraySet<Integer>();
    private final Calendar mCalendar = Calendar.getInstance();

    private DowntimeInfo mInfo;

    @Override
    public String toString() {
        return "DowntimeCalendar[mDays=" + mDays + "]";
    }

    public void setDowntimeInfo(DowntimeInfo info) {
        if (Objects.equals(mInfo, info)) return;
        mInfo = info;
        updateDays();
    }

    public long nextDowntimeStart(long time) {
        if (mInfo == null || mDays.size() == 0) return Long.MAX_VALUE;
        final long start = getTime(time, mInfo.startHour, mInfo.startMinute);
        for (int i = 0; i < Calendar.SATURDAY; i++) {
            final long t = addDays(start, i);
            if (t > time && isInDowntime(t)) {
                return t;
            }
        }
        return Long.MAX_VALUE;
    }

    public void setTimeZone(TimeZone tz) {
        mCalendar.setTimeZone(tz);
    }

    public long getNextTime(long now, int hr, int min) {
        final long time = getTime(now, hr, min);
        return time <= now ? addDays(time, 1) : time;
    }

    private long getTime(long millis, int hour, int min) {
        mCalendar.setTimeInMillis(millis);
        mCalendar.set(Calendar.HOUR_OF_DAY, hour);
        mCalendar.set(Calendar.MINUTE, min);
        mCalendar.set(Calendar.SECOND, 0);
        mCalendar.set(Calendar.MILLISECOND, 0);
        return mCalendar.getTimeInMillis();
    }

    public boolean isInDowntime(long time) {
        if (mInfo == null || mDays.size() == 0) return false;
        final long start = getTime(time, mInfo.startHour, mInfo.startMinute);
        long end = getTime(time, mInfo.endHour, mInfo.endMinute);
        if (end <= start) {
            end = addDays(end, 1);
        }
        return isInDowntime(-1, time, start, end) || isInDowntime(0, time, start, end);
    }

    private boolean isInDowntime(int daysOffset, long time, long start, long end) {
        final int n = Calendar.SATURDAY;
        final int day = ((getDayOfWeek(time) - 1) + (daysOffset % n) + n) % n + 1;
        start = addDays(start, daysOffset);
        end = addDays(end, daysOffset);
        return mDays.contains(day) && time >= start && time < end;
    }

    private int getDayOfWeek(long time) {
        mCalendar.setTimeInMillis(time);
        return mCalendar.get(Calendar.DAY_OF_WEEK);
    }

    private void updateDays() {
        mDays.clear();
        if (mInfo != null) {
            final int[] days = ZenModeConfig.tryParseDays(mInfo.mode);
            for (int i = 0; days != null && i < days.length; i++) {
                mDays.add(days[i]);
            }
        }
    }

    private long addDays(long time, int days) {
        mCalendar.setTimeInMillis(time);
        mCalendar.add(Calendar.DATE, days);
        return mCalendar.getTimeInMillis();
    }
}
