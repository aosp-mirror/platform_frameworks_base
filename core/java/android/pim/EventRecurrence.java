/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.pim;

import android.text.TextUtils;
import android.text.format.Time;

import java.util.Calendar;

public class EventRecurrence
{
    /**
     * Thrown when a recurrence string provided can not be parsed according
     * to RFC2445.
     */
    public static class InvalidFormatException extends RuntimeException
    {
        InvalidFormatException(String s) {
            super(s);
        }
    }

    public EventRecurrence()
    {
        wkst = MO;
    }
    
    /**
     * Parse an iCalendar/RFC2445 recur type according to Section 4.3.10.
     */
    public native void parse(String recur);

    public void setStartDate(Time date) {
        startDate = date;
    }
    
    public static final int SECONDLY = 1;
    public static final int MINUTELY = 2;
    public static final int HOURLY = 3;
    public static final int DAILY = 4;
    public static final int WEEKLY = 5;
    public static final int MONTHLY = 6;
    public static final int YEARLY = 7;

    public static final int SU = 0x00010000;
    public static final int MO = 0x00020000;
    public static final int TU = 0x00040000;
    public static final int WE = 0x00080000;
    public static final int TH = 0x00100000;
    public static final int FR = 0x00200000;
    public static final int SA = 0x00400000;

    public Time      startDate;
    public int       freq;
    public String    until;
    public int       count;
    public int       interval;
    public int       wkst;

    public int[]     bysecond;
    public int       bysecondCount;
    public int[]     byminute;
    public int       byminuteCount;
    public int[]     byhour;
    public int       byhourCount;
    public int[]     byday;
    public int[]     bydayNum;
    public int       bydayCount;   
    public int[]     bymonthday;
    public int       bymonthdayCount;
    public int[]     byyearday;
    public int       byyeardayCount;
    public int[]     byweekno;
    public int       byweeknoCount;
    public int[]     bymonth;
    public int       bymonthCount;
    public int[]     bysetpos;
    public int       bysetposCount;

    /**
     * Converts one of the Calendar.SUNDAY constants to the SU, MO, etc.
     * constants.  btw, I think we should switch to those here too, to
     * get rid of this function, if possible.
     */
    public static int calendarDay2Day(int day)
    {
        switch (day)
        {
            case Calendar.SUNDAY:
                return SU;
            case Calendar.MONDAY:
                return MO;
            case Calendar.TUESDAY:
                return TU;
            case Calendar.WEDNESDAY:
                return WE;
            case Calendar.THURSDAY:
                return TH;
            case Calendar.FRIDAY:
                return FR;
            case Calendar.SATURDAY:
                return SA;
            default:
                throw new RuntimeException("bad day of week: " + day);
        }
    }
    
    public static int timeDay2Day(int day)
    {
        switch (day)
        {
            case Time.SUNDAY:
                return SU;
            case Time.MONDAY:
                return MO;
            case Time.TUESDAY:
                return TU;
            case Time.WEDNESDAY:
                return WE;
            case Time.THURSDAY:
                return TH;
            case Time.FRIDAY:
                return FR;
            case Time.SATURDAY:
                return SA;
            default:
                throw new RuntimeException("bad day of week: " + day);
        }
    }
    public static int day2TimeDay(int day)
    {
        switch (day)
        {
            case SU:
                return Time.SUNDAY;
            case MO:
                return Time.MONDAY;
            case TU:
                return Time.TUESDAY;
            case WE:
                return Time.WEDNESDAY;
            case TH:
                return Time.THURSDAY;
            case FR:
                return Time.FRIDAY;
            case SA:
                return Time.SATURDAY;
            default:
                throw new RuntimeException("bad day of week: " + day);
        }
    }

    /**
     * Converts one of the SU, MO, etc. constants to the Calendar.SUNDAY
     * constants.  btw, I think we should switch to those here too, to
     * get rid of this function, if possible.
     */
    public static int day2CalendarDay(int day)
    {
        switch (day)
        {
            case SU:
                return Calendar.SUNDAY;
            case MO:
                return Calendar.MONDAY;
            case TU:
                return Calendar.TUESDAY;
            case WE:
                return Calendar.WEDNESDAY;
            case TH:
                return Calendar.THURSDAY;
            case FR:
                return Calendar.FRIDAY;
            case SA:
                return Calendar.SATURDAY;
            default:
                throw new RuntimeException("bad day of week: " + day);
        }
    }
    
    /**
     * Converts one of the internal day constants (SU, MO, etc.) to the
     * two-letter string representing that constant.
     * 
     * @throws IllegalArgumentException Thrown if the day argument is not one of
     * the defined day constants.
     * 
     * @param day one the internal constants SU, MO, etc.
     * @return the two-letter string for the day ("SU", "MO", etc.)
     */
    private static String day2String(int day) {
        switch (day) {
        case SU:
            return "SU";
        case MO:
            return "MO";
        case TU:
            return "TU";
        case WE:
            return "WE";
        case TH:
            return "TH";
        case FR:
            return "FR";
        case SA:
            return "SA";
        default:
            throw new IllegalArgumentException("bad day argument: " + day);
        }
    }

    private static void appendNumbers(StringBuilder s, String label,
                                        int count, int[] values)
    {
        if (count > 0) {
            s.append(label);
            count--;
            for (int i=0; i<count; i++) {
                s.append(values[i]);
                s.append(",");
            }
            s.append(values[count]);
        }
    }

    private void appendByDay(StringBuilder s, int i)
    {
        int n = this.bydayNum[i];
        if (n != 0) {
            s.append(n);
        }

        String str = day2String(this.byday[i]);
        s.append(str);
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();

        s.append("FREQ=");
        switch (this.freq)
        {
            case SECONDLY:
                s.append("SECONDLY");
                break;
            case MINUTELY:
                s.append("MINUTELY");
                break;
            case HOURLY:
                s.append("HOURLY");
                break;
            case DAILY:
                s.append("DAILY");
                break;
            case WEEKLY:
                s.append("WEEKLY");
                break;
            case MONTHLY:
                s.append("MONTHLY");
                break;
            case YEARLY:
                s.append("YEARLY");
                break;
        }

        if (!TextUtils.isEmpty(this.until)) {
            s.append(";UNTIL=");
            s.append(until);
        }
        
        if (this.count != 0) {
            s.append(";COUNT=");
            s.append(this.count);
        }

        if (this.interval != 0) {
            s.append(";INTERVAL=");
            s.append(this.interval);
        }

        if (this.wkst != 0) {
            s.append(";WKST=");
            s.append(day2String(this.wkst));
        }

        appendNumbers(s, ";BYSECOND=", this.bysecondCount, this.bysecond);
        appendNumbers(s, ";BYMINUTE=", this.byminuteCount, this.byminute);
        appendNumbers(s, ";BYSECOND=", this.byhourCount, this.byhour);

        // day
        int count = this.bydayCount;
        if (count > 0) {
            s.append(";BYDAY=");
            count--;
            for (int i=0; i<count; i++) {
                appendByDay(s, i);
                s.append(",");
            }
            appendByDay(s, count);
        }

        appendNumbers(s, ";BYMONTHDAY=", this.bymonthdayCount, this.bymonthday);
        appendNumbers(s, ";BYYEARDAY=", this.byyeardayCount, this.byyearday);
        appendNumbers(s, ";BYWEEKNO=", this.byweeknoCount, this.byweekno);
        appendNumbers(s, ";BYMONTH=", this.bymonthCount, this.bymonth);
        appendNumbers(s, ";BYSETPOS=", this.bysetposCount, this.bysetpos);

        return s.toString();
    }
    
    public boolean repeatsOnEveryWeekDay() {
        if (this.freq != WEEKLY) {
            return false; 
        }
        
        int count = this.bydayCount;
        if (count != 5) {
            return false;
        }
        
        for (int i = 0 ; i < count ; i++) {
            int day = byday[i];
            if (day == SU || day == SA) {
                return false;
            }
        }
        
        return true;
    }
    
    public boolean repeatsMonthlyOnDayCount() {
        if (this.freq != MONTHLY) {
            return false;
        }
        
        if (bydayCount != 1 || bymonthdayCount != 0) {
            return false;
        }
        
        return true;
    }
}
