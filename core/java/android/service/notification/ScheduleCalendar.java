/*
 * Copyright (c) 2017 The Android Open Source Project
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

package android.service.notification;

import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.util.ArraySet;
import android.util.Log;

import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;

/**
 * @hide
 */
public class ScheduleCalendar {
    public static final String TAG = "ScheduleCalendar";
    public static final boolean DEBUG = Log.isLoggable("ConditionProviders", Log.DEBUG);
    private final ArraySet<Integer> mDays = new ArraySet<Integer>();
    private final Calendar mCalendar = Calendar.getInstance();

    private ScheduleInfo mSchedule;

    @Override
    public String toString() {
        return "ScheduleCalendar[mDays=" + mDays + ", mSchedule=" + mSchedule + "]";
    }

    /**
     * @return true if schedule will exit on alarm, else false
     */
    public boolean exitAtAlarm() {
        return mSchedule.exitAtAlarm;
    }

    /**
     * Sets schedule information
     */
    public void setSchedule(ScheduleInfo schedule) {
        if (Objects.equals(mSchedule, schedule)) return;
        mSchedule = schedule;
        updateDays();
    }

    /**
     * Sets next alarm of the schedule if the saved next alarm has passed or is further
     * in the future than given nextAlarm
     * @param now current time in milliseconds
     * @param nextAlarm time of next alarm in milliseconds
     */
    public void maybeSetNextAlarm(long now, long nextAlarm) {
        if (mSchedule != null && mSchedule.exitAtAlarm) {
            // alarm canceled
            if (nextAlarm == 0) {
                mSchedule.nextAlarm = 0;
            }
            // only allow alarms in the future
            if (nextAlarm > now) {
                // store earliest alarm
                if (mSchedule.nextAlarm == 0) {
                    mSchedule.nextAlarm = nextAlarm;
                } else {
                    mSchedule.nextAlarm = Math.min(mSchedule.nextAlarm, nextAlarm);
                }
            } else if (mSchedule.nextAlarm < now) {
                if (DEBUG) {
                    Log.d(TAG, "All alarms are in the past " + mSchedule.nextAlarm);
                }
                mSchedule.nextAlarm = 0;
            }
        }
    }

    /**
     * Set calendar time zone to tz
     * @param tz current time zone
     */
    public void setTimeZone(TimeZone tz) {
        mCalendar.setTimeZone(tz);
    }

    /**
     * @param now current time in milliseconds
     * @return next time this rule changes (starts or ends)
     */
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

    /**
     * @param time milliseconds since Epoch
     * @return true if time is within the schedule, else false
     */
    public boolean isInSchedule(long time) {
        if (mSchedule == null || mDays.size() == 0) return false;
        final long start = getTime(time, mSchedule.startHour, mSchedule.startMinute);
        long end = getTime(time, mSchedule.endHour, mSchedule.endMinute);
        if (end <= start) {
            end = addDays(end, 1);
        }
        return isInSchedule(-1, time, start, end) || isInSchedule(0, time, start, end);
    }

    /**
     * @param alarm milliseconds since Epoch
     * @param now milliseconds since Epoch
     * @return true if alarm and now is within the schedule, else false
     */
    public boolean isAlarmInSchedule(long alarm, long now) {
        if (mSchedule == null || mDays.size() == 0) return false;
        final long start = getTime(alarm, mSchedule.startHour, mSchedule.startMinute);
        long end = getTime(alarm, mSchedule.endHour, mSchedule.endMinute);
        if (end <= start) {
            end = addDays(end, 1);
        }
        return (isInSchedule(-1, alarm, start, end)
                && isInSchedule(-1, now, start, end))
                || (isInSchedule(0, alarm, start, end)
                && isInSchedule(0, now, start, end));
    }

    /**
     * @param time milliseconds since Epoch
     * @return true if should exit at time for next alarm, else false
     */
    public boolean shouldExitForAlarm(long time) {
        if (mSchedule == null) {
            return false;
        }
        return mSchedule.exitAtAlarm
                && mSchedule.nextAlarm != 0
                && time >= mSchedule.nextAlarm
                && isAlarmInSchedule(mSchedule.nextAlarm, time);
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
