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
import android.util.Log;
import android.util.TimeFormatException;

import java.util.Calendar;
import java.util.HashMap;

/**
 * Event recurrence utility functions.
 */
public class EventRecurrence {
    private static String TAG = "EventRecur";

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

    public Time      startDate;     // set by setStartDate(), not parse()

    public int       freq;          // SECONDLY, MINUTELY, etc.
    public String    until;
    public int       count;
    public int       interval;
    public int       wkst;          // SU, MO, TU, etc.

    /* lists with zero entries may be null references */
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

    /** maps a part string to a parser object */
    private static HashMap<String,PartParser> sParsePartMap;
    static {
        sParsePartMap = new HashMap<String,PartParser>();
        sParsePartMap.put("FREQ", new ParseFreq());
        sParsePartMap.put("UNTIL", new ParseUntil());
        sParsePartMap.put("COUNT", new ParseCount());
        sParsePartMap.put("INTERVAL", new ParseInterval());
        sParsePartMap.put("BYSECOND", new ParseBySecond());
        sParsePartMap.put("BYMINUTE", new ParseByMinute());
        sParsePartMap.put("BYHOUR", new ParseByHour());
        sParsePartMap.put("BYDAY", new ParseByDay());
        sParsePartMap.put("BYMONTHDAY", new ParseByMonthDay());
        sParsePartMap.put("BYYEARDAY", new ParseByYearDay());
        sParsePartMap.put("BYWEEKNO", new ParseByWeekNo());
        sParsePartMap.put("BYMONTH", new ParseByMonth());
        sParsePartMap.put("BYSETPOS", new ParseBySetPos());
        sParsePartMap.put("WKST", new ParseWkst());
    }

    /* values for bit vector that keeps track of what we have already seen */
    private static final int PARSED_FREQ = 1 << 0;
    private static final int PARSED_UNTIL = 1 << 1;
    private static final int PARSED_COUNT = 1 << 2;
    private static final int PARSED_INTERVAL = 1 << 3;
    private static final int PARSED_BYSECOND = 1 << 4;
    private static final int PARSED_BYMINUTE = 1 << 5;
    private static final int PARSED_BYHOUR = 1 << 6;
    private static final int PARSED_BYDAY = 1 << 7;
    private static final int PARSED_BYMONTHDAY = 1 << 8;
    private static final int PARSED_BYYEARDAY = 1 << 9;
    private static final int PARSED_BYWEEKNO = 1 << 10;
    private static final int PARSED_BYMONTH = 1 << 11;
    private static final int PARSED_BYSETPOS = 1 << 12;
    private static final int PARSED_WKST = 1 << 13;

    /** maps a FREQ value to an integer constant */
    private static final HashMap<String,Integer> sParseFreqMap = new HashMap<String,Integer>();
    static {
        sParseFreqMap.put("SECONDLY", SECONDLY);
        sParseFreqMap.put("MINUTELY", MINUTELY);
        sParseFreqMap.put("HOURLY", HOURLY);
        sParseFreqMap.put("DAILY", DAILY);
        sParseFreqMap.put("WEEKLY", WEEKLY);
        sParseFreqMap.put("MONTHLY", MONTHLY);
        sParseFreqMap.put("YEARLY", YEARLY);
    }

    /** maps a two-character weekday string to an integer constant */
    private static final HashMap<String,Integer> sParseWeekdayMap = new HashMap<String,Integer>();
    static {
        sParseWeekdayMap.put("SU", SU);
        sParseWeekdayMap.put("MO", MO);
        sParseWeekdayMap.put("TU", TU);
        sParseWeekdayMap.put("WE", WE);
        sParseWeekdayMap.put("TH", TH);
        sParseWeekdayMap.put("FR", FR);
        sParseWeekdayMap.put("SA", SA);
    }

    /** If set, allow lower-case recurrence rule strings.  Minor performance impact. */
    private static final boolean ALLOW_LOWER_CASE = false;

    /** If set, validate the value of UNTIL parts.  Minor performance impact. */
    private static final boolean VALIDATE_UNTIL = false;

    /** If set, require that only one of {UNTIL,COUNT} is present.  Breaks compat w/ old parser. */
    private static final boolean ONLY_ONE_UNTIL_COUNT = false;


    /**
     * Thrown when a recurrence string provided can not be parsed according
     * to RFC2445.
     */
    public static class InvalidFormatException extends RuntimeException {
        InvalidFormatException(String s) {
            super(s);
        }
    }

    /**
     * Parse an iCalendar/RFC2445 recur type according to Section 4.3.10.  The string is
     * parsed twice, by the old and new parsers, and the results are compared.
     * <p>
     * TODO: this will go away, and what is now parse2() will simply become parse().
     */
    public void parse(String recur) {
        InvalidFormatException newExcep = null;
        try {
            parse2(recur);
        } catch (InvalidFormatException ife) {
            newExcep = ife;
        }

        boolean oldThrew = false;
        try {
            EventRecurrence check = new EventRecurrence();
            check.parseNative(recur);
            if (newExcep == null) {
                // Neither threw, check to see if results match.
                if (!equals(check)) {
                    throw new InvalidFormatException("Recurrence rule parse does not match [" +
                            recur + "]");
                }
            }
        } catch (InvalidFormatException ife) {
            oldThrew = true;
            if (newExcep == null) {
                // Old threw, but new didn't.  Log a warning, but don't throw.
                Log.d(TAG, "NOTE: old parser rejected [" + recur + "]: " + ife.getMessage());
            }
        }

        if (newExcep != null) {
            if (!oldThrew) {
                // New threw, but old didn't.  Log a warning and throw the exception.
                Log.d(TAG, "NOTE: new parser rejected [" + recur + "]: " + newExcep.getMessage());
            }
            throw newExcep;
        }
    }

    native void parseNative(String recur);

    public void setStartDate(Time date) {
        startDate = date;
    }

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
     * @param day one the internal constants SU, MO, etc.
     * @return the two-letter string for the day ("SU", "MO", etc.)
     *
     * @throws IllegalArgumentException Thrown if the day argument is not one of
     * the defined day constants.
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

    /**
     * Determines whether two integer arrays contain identical elements.
     * <p>
     * The native implementation over-allocated the arrays (and may have stuff left over from
     * a previous run), so we can't just check the arrays -- the separately-maintained count
     * field also matters.  We assume that a null array will have a count of zero, and that the
     * array can hold as many elements as the associated count indicates.
     * <p>
     * TODO: replace this with Arrays.equals() when the old parser goes away.
     */
    private static boolean arraysEqual(int[] array1, int count1, int[] array2, int count2) {
        if (count1 != count2) {
            return false;
        }

        for (int i = 0; i < count1; i++) {
            if (array1[i] != array2[i])
                return false;
        }

        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EventRecurrence)) {
            return false;
        }

        EventRecurrence er = (EventRecurrence) obj;
        return  (startDate == null ?
                        er.startDate == null : Time.compare(startDate, er.startDate) == 0) &&
                freq == er.freq &&
                (until == null ? er.until == null : until.equals(er.until)) &&
                count == er.count &&
                interval == er.interval &&
                wkst == er.wkst &&
                arraysEqual(bysecond, bysecondCount, er.bysecond, er.bysecondCount) &&
                arraysEqual(byminute, byminuteCount, er.byminute, er.byminuteCount) &&
                arraysEqual(byhour, byhourCount, er.byhour, er.byhourCount) &&
                arraysEqual(byday, bydayCount, er.byday, er.bydayCount) &&
                arraysEqual(bydayNum, bydayCount, er.bydayNum, er.bydayCount) &&
                arraysEqual(bymonthday, bymonthdayCount, er.bymonthday, er.bymonthdayCount) &&
                arraysEqual(byyearday, byyeardayCount, er.byyearday, er.byyeardayCount) &&
                arraysEqual(byweekno, byweeknoCount, er.byweekno, er.byweeknoCount) &&
                arraysEqual(bymonth, bymonthCount, er.bymonth, er.bymonthCount) &&
                arraysEqual(bysetpos, bysetposCount, er.bysetpos, er.bysetposCount);
    }

    @Override public int hashCode() {
        // We overrode equals, so we must override hashCode().  Nobody seems to need this though.
        throw new UnsupportedOperationException();
    }

    /**
     * Resets parser-modified fields to their initial state.  Does not alter startDate.
     * <p>
     * The original parser always set all of the "count" fields, "wkst", and "until",
     * essentially allowing the same object to be used multiple times by calling parse().
     * It's unclear whether this behavior was intentional.  For now, be paranoid and
     * preserve the existing behavior by resetting the fields.
     * <p>
     * We don't need to touch the integer arrays; they will either be ignored or
     * overwritten.  The "startDate" field is not set by the parser, so we ignore it here.
     */
    private void resetFields() {
        until = null;
        freq = count = interval = bysecondCount = byminuteCount = byhourCount =
            bydayCount = bymonthdayCount = byyeardayCount = byweeknoCount = bymonthCount =
            bysetposCount = 0;
    }

    /**
     * Parses an rfc2445 recurrence rule string into its component pieces.  Attempting to parse
     * malformed input will result in an EventRecurrence.InvalidFormatException.
     *
     * @param recur The recurrence rule to parse (in un-folded form).
     */
    void parse2(String recur) {
        /*
         * From RFC 2445 section 4.3.10:
         *
         * recur = "FREQ"=freq *(
         *       ; either UNTIL or COUNT may appear in a 'recur',
         *       ; but UNTIL and COUNT MUST NOT occur in the same 'recur'
         *
         *       ( ";" "UNTIL" "=" enddate ) /
         *       ( ";" "COUNT" "=" 1*DIGIT ) /
         *
         *       ; the rest of these keywords are optional,
         *       ; but MUST NOT occur more than once
         *
         *       ( ";" "INTERVAL" "=" 1*DIGIT )          /
         *       ( ";" "BYSECOND" "=" byseclist )        /
         *       ( ";" "BYMINUTE" "=" byminlist )        /
         *       ( ";" "BYHOUR" "=" byhrlist )           /
         *       ( ";" "BYDAY" "=" bywdaylist )          /
         *       ( ";" "BYMONTHDAY" "=" bymodaylist )    /
         *       ( ";" "BYYEARDAY" "=" byyrdaylist )     /
         *       ( ";" "BYWEEKNO" "=" bywknolist )       /
         *       ( ";" "BYMONTH" "=" bymolist )          /
         *       ( ";" "BYSETPOS" "=" bysplist )         /
         *       ( ";" "WKST" "=" weekday )              /
         *       ( ";" x-name "=" text )
         *       )
         *
         * Examples:
         *   FREQ=MONTHLY;INTERVAL=2;COUNT=10;BYDAY=1SU,-1SU
         *   FREQ=YEARLY;INTERVAL=4;BYMONTH=11;BYDAY=TU;BYMONTHDAY=2,3,4,5,6,7,8
         *
         * Strategy:
         * (1) Split the string at ';' boundaries to get an array of rule "parts".
         * (2) For each part, find substrings for left/right sides of '=' (name/value).
         * (3) Call a <name>-specific parsing function to parse the <value> into an
         *     output field.
         *
         * By keeping track of which names we've seen in a bit vector, we can verify the
         * constraints indicated above (FREQ appears first, none of them appear more than once --
         * though x-[name] would require special treatment), and we have either UNTIL or COUNT
         * but not both.
         *
         * In general, RFC 2445 property names (e.g. "FREQ") and enumerations ("TU") must
         * be handled in a case-insensitive fashion, but case may be significant for other
         * properties.  We don't have any case-sensitive values in RRULE, except possibly
         * for the custom "X-" properties, but we ignore those anyway.  Thus, we can trivially
         * convert the entire string to upper case and then use simple comparisons.
         *
         * Differences from previous version:
         * - allows lower-case property and enumeration values [optional]
         * - enforces that FREQ appears first
         * - enforces that only one of UNTIL and COUNT may be specified
         * - allows (but ignores) X-* parts
         * - improved validation on various values (e.g. UNTIL timestamps)
         * - error messages are more specific
         */

        /* TODO: replace with "if (freq != 0) throw" if nothing requires this */
        resetFields();

        int parseFlags = 0;
        String[] parts;
        if (ALLOW_LOWER_CASE) {
            parts = recur.toUpperCase().split(";");
        } else {
            parts = recur.split(";");
        }
        for (String part : parts) {
            int equalIndex = part.indexOf('=');
            if (equalIndex <= 0) {
                /* no '=' or no LHS */
                throw new InvalidFormatException("Missing LHS in " + part);
            }

            String lhs = part.substring(0, equalIndex);
            String rhs = part.substring(equalIndex + 1);
            if (rhs.length() == 0) {
                throw new InvalidFormatException("Missing RHS in " + part);
            }

            /*
             * In lieu of a "switch" statement that allows string arguments, we use a
             * map from strings to parsing functions.
             */
            PartParser parser = sParsePartMap.get(lhs);
            if (parser == null) {
                if (lhs.startsWith("X-")) {
                    //Log.d(TAG, "Ignoring custom part " + lhs);
                    continue;
                }
                throw new InvalidFormatException("Couldn't find parser for " + lhs);
            } else {
                int flag = parser.parsePart(rhs, this);
                if ((parseFlags & flag) != 0) {
                    throw new InvalidFormatException("Part " + lhs + " was specified twice");
                }
                if (parseFlags == 0 && flag != PARSED_FREQ) {
                    throw new InvalidFormatException("FREQ must be specified first");
                }
                parseFlags |= flag;
            }
        }

        // If not specified, week starts on Monday.
        if ((parseFlags & PARSED_WKST) == 0) {
            wkst = MO;
        }

        // FREQ is mandatory.
        if ((parseFlags & PARSED_FREQ) == 0) {
            throw new InvalidFormatException("Must specify a FREQ value");
        }

        // Can't have both UNTIL and COUNT.
        if ((parseFlags & (PARSED_UNTIL | PARSED_COUNT)) == (PARSED_UNTIL | PARSED_COUNT)) {
            if (ONLY_ONE_UNTIL_COUNT) {
                throw new InvalidFormatException("Must not specify both UNTIL and COUNT: " + recur);
            } else {
                Log.w(TAG, "Warning: rrule has both UNTIL and COUNT: " + recur);
            }
        }
    }

    /**
     * Base class for the RRULE part parsers.
     */
    abstract static class PartParser {
        /**
         * Parses a single part.
         *
         * @param value The right-hand-side of the part.
         * @param er The EventRecurrence into which the result is stored.
         * @return A bit value indicating which part was parsed.
         */
        public abstract int parsePart(String value, EventRecurrence er);

        /**
         * Parses an integer, with range-checking.
         *
         * @param str The string to parse.
         * @param minVal Minimum allowed value.
         * @param maxVal Maximum allowed value.
         * @param allowZero Is 0 allowed?
         * @return The parsed value.
         */
        public static int parseIntRange(String str, int minVal, int maxVal, boolean allowZero) {
            try {
                if (str.charAt(0) == '+') {
                    // Integer.parseInt does not allow a leading '+', so skip it manually.
                    str = str.substring(1);
                }
                int val = Integer.parseInt(str);
                if (val < minVal || val > maxVal || (val == 0 && !allowZero)) {
                    throw new InvalidFormatException("Integer value out of range: " + str);
                }
                return val;
            } catch (NumberFormatException nfe) {
                throw new InvalidFormatException("Invalid integer value: " + str);
            }
        }

        /**
         * Parses a comma-separated list of integers, with range-checking.
         *
         * @param listStr The string to parse.
         * @param minVal Minimum allowed value.
         * @param maxVal Maximum allowed value.
         * @param allowZero Is 0 allowed?
         * @return A new array with values, sized to hold the exact number of elements.
         */
        public static int[] parseNumberList(String listStr, int minVal, int maxVal,
                boolean allowZero) {
            int[] values;

            if (listStr.indexOf(",") < 0) {
                // Common case: only one entry, skip split() overhead.
                values = new int[1];
                values[0] = parseIntRange(listStr, minVal, maxVal, allowZero);
            } else {
                String[] valueStrs = listStr.split(",");
                int len = valueStrs.length;
                values = new int[len];
                for (int i = 0; i < len; i++) {
                    values[i] = parseIntRange(valueStrs[i], minVal, maxVal, allowZero);
                }
            }
            return values;
        }
   }

    /** parses FREQ={SECONDLY,MINUTELY,...} */
    private static class ParseFreq extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            Integer freq = sParseFreqMap.get(value);
            if (freq == null) {
                throw new InvalidFormatException("Invalid FREQ value: " + value);
            }
            er.freq = freq;
            return PARSED_FREQ;
        }
    }
    /** parses UNTIL=enddate, e.g. "19970829T021400" */
    private static class ParseUntil extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            if (VALIDATE_UNTIL) {
                try {
                    // Parse the time to validate it.  The result isn't retained.
                    Time until = new Time();
                    until.parse(value);
                } catch (TimeFormatException tfe) {
                    throw new InvalidFormatException("Invalid UNTIL value: " + value);
                }
            }
            er.until = value;
            return PARSED_UNTIL;
        }
    }
    /** parses COUNT=[non-negative-integer] */
    private static class ParseCount extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            er.count = parseIntRange(value, 0, Integer.MAX_VALUE, true);
            return PARSED_COUNT;
        }
    }
    /** parses INTERVAL=[non-negative-integer] */
    private static class ParseInterval extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            er.interval = parseIntRange(value, 1, Integer.MAX_VALUE, false);
            return PARSED_INTERVAL;
        }
    }
    /** parses BYSECOND=byseclist */
    private static class ParseBySecond extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] bysecond = parseNumberList(value, 0, 59, true);
            er.bysecond = bysecond;
            er.bysecondCount = bysecond.length;
            return PARSED_BYSECOND;
        }
    }
    /** parses BYMINUTE=byminlist */
    private static class ParseByMinute extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] byminute = parseNumberList(value, 0, 59, true);
            er.byminute = byminute;
            er.byminuteCount = byminute.length;
            return PARSED_BYMINUTE;
        }
    }
    /** parses BYHOUR=byhrlist */
    private static class ParseByHour extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] byhour = parseNumberList(value, 0, 23, true);
            er.byhour = byhour;
            er.byhourCount = byhour.length;
            return PARSED_BYHOUR;
        }
    }
    /** parses BYDAY=bywdaylist, e.g. "1SU,-1SU" */
    private static class ParseByDay extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] byday;
            int[] bydayNum;
            int bydayCount;

            if (value.indexOf(",") < 0) {
                /* only one entry, skip split() overhead */
                bydayCount = 1;
                byday = new int[1];
                bydayNum = new int[1];
                parseWday(value, byday, bydayNum, 0);
            } else {
                String[] wdays = value.split(",");
                int len = wdays.length;
                bydayCount = len;
                byday = new int[len];
                bydayNum = new int[len];
                for (int i = 0; i < len; i++) {
                    parseWday(wdays[i], byday, bydayNum, i);
                }
            }
            er.byday = byday;
            er.bydayNum = bydayNum;
            er.bydayCount = bydayCount;
            return PARSED_BYDAY;
        }

        /** parses [int]weekday, putting the pieces into parallel array entries */
        private static void parseWday(String str, int[] byday, int[] bydayNum, int index) {
            int wdayStrStart = str.length() - 2;
            String wdayStr;

            if (wdayStrStart > 0) {
                /* number is included; parse it out and advance to weekday */
                String numPart = str.substring(0, wdayStrStart);
                int num = parseIntRange(numPart, -53, 53, false);
                bydayNum[index] = num;
                wdayStr = str.substring(wdayStrStart);
            } else {
                /* just the weekday string */
                wdayStr = str;
            }
            Integer wday = sParseWeekdayMap.get(wdayStr);
            if (wday == null) {
                throw new InvalidFormatException("Invalid BYDAY value: " + str);
            }
            byday[index] = wday;
        }
    }
    /** parses BYMONTHDAY=bymodaylist */
    private static class ParseByMonthDay extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] bymonthday = parseNumberList(value, -31, 31, false);
            er.bymonthday = bymonthday;
            er.bymonthdayCount = bymonthday.length;
            return PARSED_BYMONTHDAY;
        }
    }
    /** parses BYYEARDAY=byyrdaylist */
    private static class ParseByYearDay extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] byyearday = parseNumberList(value, -366, 366, false);
            er.byyearday = byyearday;
            er.byyeardayCount = byyearday.length;
            return PARSED_BYYEARDAY;
        }
    }
    /** parses BYWEEKNO=bywknolist */
    private static class ParseByWeekNo extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] byweekno = parseNumberList(value, -53, 53, false);
            er.byweekno = byweekno;
            er.byweeknoCount = byweekno.length;
            return PARSED_BYWEEKNO;
        }
    }
    /** parses BYMONTH=bymolist */
    private static class ParseByMonth extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] bymonth = parseNumberList(value, 1, 12, false);
            er.bymonth = bymonth;
            er.bymonthCount = bymonth.length;
            return PARSED_BYMONTH;
        }
    }
    /** parses BYSETPOS=bysplist */
    private static class ParseBySetPos extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            int[] bysetpos = parseNumberList(value, Integer.MIN_VALUE, Integer.MAX_VALUE, true);
            er.bysetpos = bysetpos;
            er.bysetposCount = bysetpos.length;
            return PARSED_BYSETPOS;
        }
    }
    /** parses WKST={SU,MO,...} */
    private static class ParseWkst extends PartParser {
        @Override public int parsePart(String value, EventRecurrence er) {
            Integer wkst = sParseWeekdayMap.get(value);
            if (wkst == null) {
                throw new InvalidFormatException("Invalid WKST value: " + value);
            }
            er.wkst = wkst;
            return PARSED_WKST;
        }
    }
}
