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

import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.util.ArraySet;

import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;

public class ScheduleCalendar {
    private final ArraySet<Integer> mDays = new ArraySet<Integer>();
    private final Calendar mCalendar = Calendar.getInstance();

    private ScheduleInfo mSchedule;

    @Override
    public String toString() {
        return "ScheduleCalendar[mDays=" + mDays + ", mSchedule=" + mSchedule + "]";
    }

    public void setSchedule(ScheduleInfo schedule) {
        if (Objects.equals(mSchedule, schedule)) return;
        mSchedule = schedule;
        updateDays();
    }

    public void maybeSetNextAlarm(long now, long nextAlarm) {
        if (mSchedule != null) {
            if (mSchedule.exitAtAlarm
                    && (now > mSchedule.nextAlarm || nextAlarm < mSchedule.nextAlarm)) {
                mSchedule.nextAlarm = nextAlarm;
            }
        }
    }

    public void setTimeZone(TimeZone tz) {
        mCalendar.setTimeZone(tz);
    }

    public long getNextChangeTime(long now) {
        if (mSchedule == null) return 0;
        final long nextStart = getNextTime(now, mSchedule.startHour, mSchedule.startMinute);
        final long nextEnd = getNextTime(now, mSchedule.endHour, mSchedule.endMinute);
        long nextScheduleTime = Math.min(nextStart, nextEnd);

        return nextScheduleTime;
    }

    private long getNextTime(long now, int hr, int min) {
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

    public boolean isInSchedule(long time) {
        if (mSchedule == null || mDays.size() == 0) return false;
        final long start = getTime(time, mSchedule.startHour, mSchedule.startMinute);
        long end = getTime(time, mSchedule.endHour, mSchedule.endMinute);
        if (end <= start) {
            end = addDays(end, 1);
        }
        return isInSchedule(-1, time, start, end) || isInSchedule(0, time, start, end);
    }

    public boolean shouldExitForAlarm(long time) {
        return mSchedule.exitAtAlarm
                && mSchedule.nextAlarm != 0
                && time >= mSchedule.nextAlarm;
    }

    private boolean isInSchedule(int daysOffset, long time, long start, long end) {
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
        if (mSchedule != null && mSchedule.days != null) {
            for (int i = 0; i < mSchedule.days.length; i++) {
                mDays.add(mSchedule.days[i]);
            }
        }
    }

    private long addDays(long time, int days) {
        mCalendar.setTimeInMillis(time);
        mCalendar.add(Calendar.DATE, days);
        return mCalendar.getTimeInMillis();
    }
}
