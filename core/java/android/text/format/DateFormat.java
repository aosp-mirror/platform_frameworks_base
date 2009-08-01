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

package android.text.format;

import android.content.Context;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;

import com.android.internal.R;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

/**
    Utility class for producing strings with formatted date/time.

    <p>
    This class takes as inputs a format string and a representation of a date/time.
    The format string controls how the output is generated.
    </p>
    <p>
    Formatting characters may be repeated in order to get more detailed representations
    of that field.  For instance, the format character &apos;M&apos; is used to
    represent the month.  Depending on how many times that character is repeated
    you get a different representation.
    </p>
    <p>
    For the month of September:<br/>
    M -&gt; 9<br/>
    MM -&gt; 09<br/>
    MMM -&gt; Sep<br/>
    MMMM -&gt; September
    </p>
    <p>
    The effects of the duplication vary depending on the nature of the field.
    See the notes on the individual field formatters for details.  For purely numeric
    fields such as <code>HOUR</code> adding more copies of the designator will
    zero-pad the value to that number of characters.
    </p>
    <p>
    For 7 minutes past the hour:<br/>
    m -&gt; 7<br/>
    mm -&gt; 07<br/>
    mmm -&gt; 007<br/>
    mmmm -&gt; 0007
    </p>
    <p>
    Examples for April 6, 1970 at 3:23am:<br/>
    &quot;MM/dd/yy h:mmaa&quot; -&gt; &quot;04/06/70 3:23am&quot<br/>
    &quot;MMM dd, yyyy h:mmaa&quot; -&gt; &quot;Apr 6, 1970 3:23am&quot<br/>
    &quot;MMMM dd, yyyy h:mmaa&quot; -&gt; &quot;April 6, 1970 3:23am&quot<br/>
    &quot;E, MMMM dd, yyyy h:mmaa&quot; -&gt; &quot;Mon, April 6, 1970 3:23am&<br/>
    &quot;EEEE, MMMM dd, yyyy h:mmaa&quot; -&gt; &quot;Monday, April 6, 1970 3:23am&quot;<br/>
    &quot;&apos;Noteworthy day: &apos;M/d/yy&quot; -&gt; &quot;Noteworthy day: 4/6/70&quot;
 */

public class DateFormat {
    /**
        Text in the format string that should be copied verbatim rather that
        interpreted as formatting codes must be surrounded by the <code>QUOTE</code>
        character.  If you need to embed a literal <code>QUOTE</code> character in
        the output text then use two in a row.
     */
    public  static final char    QUOTE                  =    '\'';
    
    /**
        This designator indicates whether the <code>HOUR</code> field is before
        or after noon.  The output is lower-case.
     
        Examples:
        a -> a or p
        aa -> am or pm
     */
    public  static final char    AM_PM                  =    'a';

    /**
        This designator indicates whether the <code>HOUR</code> field is before
        or after noon.  The output is capitalized.
     
        Examples:
        A -> A or P
        AA -> AM or PM
     */
    public  static final char    CAPITAL_AM_PM          =    'A';

    /**
        This designator indicates the day of the month.
         
        Examples for the 9th of the month:
        d -> 9
        dd -> 09
     */
    public  static final char    DATE                   =    'd';

    /**
        This designator indicates the name of the day of the week.
     
        Examples for Sunday:
        E -> Sun
        EEEE -> Sunday
     */
    public  static final char    DAY                    =    'E';

    /**
        This designator indicates the hour of the day in 12 hour format.
     
        Examples for 3pm:
        h -> 3
        hh -> 03
     */
    public  static final char    HOUR                   =    'h';

    /**
        This designator indicates the hour of the day in 24 hour format.
     
        Example for 3pm:
        k -> 15

        Examples for midnight:
        k -> 0
        kk -> 00
     */
    public  static final char    HOUR_OF_DAY            =    'k';

    /**
        This designator indicates the minute of the hour.
     
        Examples for 7 minutes past the hour:
        m -> 7
        mm -> 07
     */
    public  static final char    MINUTE                 =    'm';

    /**
        This designator indicates the month of the year
     
        Examples for September:
        M -> 9
        MM -> 09
        MMM -> Sep
        MMMM -> September
     */
    public  static final char    MONTH                  =    'M';

    /**
        This designator indicates the seconds of the minute.
     
        Examples for 7 seconds past the minute:
        s -> 7
        ss -> 07
     */
    public  static final char    SECONDS                =    's';

    /**
        This designator indicates the offset of the timezone from GMT.
     
        Example for US/Pacific timezone:
        z -> -0800
        zz -> PST
     */
    public  static final char    TIME_ZONE              =    'z';

    /**
        This designator indicates the year.
     
        Examples for 2006
        y -> 06
        yyyy -> 2006
     */
    public  static final char    YEAR                   =    'y';


    private static final Object sLocaleLock = new Object();
    private static Locale sIs24HourLocale;
    private static boolean sIs24Hour;


    /**
     * Returns true if user preference is set to 24-hour format.
     * @param context the context to use for the content resolver
     * @return true if 24 hour time format is selected, false otherwise.
     */
    public static boolean is24HourFormat(Context context) {
        String value = Settings.System.getString(context.getContentResolver(),
                Settings.System.TIME_12_24);

        if (value == null) {
            Locale locale = context.getResources().getConfiguration().locale;

            synchronized (sLocaleLock) {
                if (sIs24HourLocale != null && sIs24HourLocale.equals(locale)) {
                    return sIs24Hour;
                }
            }

            java.text.DateFormat natural =
                java.text.DateFormat.getTimeInstance(
                    java.text.DateFormat.LONG, locale);

            if (natural instanceof SimpleDateFormat) {
                SimpleDateFormat sdf = (SimpleDateFormat) natural;
                String pattern = sdf.toPattern();

                if (pattern.indexOf('H') >= 0) {
                    value = "24";
                } else {
                    value = "12";
                }
            } else {
                value = "12";
            }

            synchronized (sLocaleLock) {
                sIs24HourLocale = locale;
                sIs24Hour = !value.equals("12");
            }
        }

        boolean b24 =  !(value == null || value.equals("12"));
        return b24;
    }

    /**
     * Returns a {@link java.text.DateFormat} object that can format the time according
     * to the current locale and the user's 12-/24-hour clock preference.
     * @param context the application context
     * @return the {@link java.text.DateFormat} object that properly formats the time.
     */
    public static final java.text.DateFormat getTimeFormat(Context context) {
        boolean b24 = is24HourFormat(context);
        int res;

        if (b24) {
            res = R.string.twenty_four_hour_time_format;
        } else {
            res = R.string.twelve_hour_time_format;
        }

        return new java.text.SimpleDateFormat(context.getString(res));
    }

    /**
     * Returns a {@link java.text.DateFormat} object that can format the date 
     * in short form (such as 12/31/1999) according
     * to the current locale and the user's date-order preference.
     * @param context the application context
     * @return the {@link java.text.DateFormat} object that properly formats the date.
     */
    public static final java.text.DateFormat getDateFormat(Context context) {
        String value = Settings.System.getString(context.getContentResolver(),
                Settings.System.DATE_FORMAT);

        return getDateFormatForSetting(context, value);
    }

    /**
     * Returns a {@link java.text.DateFormat} object to format the date
     * as if the date format setting were set to <code>value</code>,
     * including null to use the locale's default format.
     * @param context the application context
     * @param value the date format setting string to interpret for
     *              the current locale
     * @hide
     */
    public static java.text.DateFormat getDateFormatForSetting(Context context,
                                                               String value) {
        String format = getDateFormatStringForSetting(context, value);

        return new java.text.SimpleDateFormat(format);
    }

    private static String getDateFormatStringForSetting(Context context, String value) {
        if (value != null) {
            int month = value.indexOf('M');
            int day = value.indexOf('d');
            int year = value.indexOf('y');

            if (month >= 0 && day >= 0 && year >= 0) {
                String template = context.getString(R.string.numeric_date_template);
                if (year < month && year < day) {
                    if (month < day) {
                        value = String.format(template, "yyyy", "MM", "dd");
                    } else {
                        value = String.format(template, "yyyy", "dd", "MM");
                    }
                } else if (month < day) {
                    if (day < year) {
                        value = String.format(template, "MM", "dd", "yyyy");
                    } else { // unlikely
                        value = String.format(template, "MM", "yyyy", "dd");
                    }
                } else { // day < month
                    if (month < year) {
                        value = String.format(template, "dd", "MM", "yyyy");
                    } else { // unlikely
                        value = String.format(template, "dd", "yyyy", "MM");
                    }
                }

                return value;
            }
        }

        /*
         * The setting is not set; use the default.
         * We use a resource string here instead of just DateFormat.SHORT
         * so that we get a four-digit year instead a two-digit year.
         */
        value = context.getString(R.string.numeric_date_format);
        return value;
    }
    
    /**
     * Returns a {@link java.text.DateFormat} object that can format the date
     * in long form (such as December 31, 1999) for the current locale.
     * @param context the application context
     * @return the {@link java.text.DateFormat} object that formats the date in long form.
     */
    public static final java.text.DateFormat getLongDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG);
    }

    /**
     * Returns a {@link java.text.DateFormat} object that can format the date
     * in medium form (such as Dec. 31, 1999) for the current locale.
     * @param context the application context
     * @return the {@link java.text.DateFormat} object that formats the date in long form.
     */
    public static final java.text.DateFormat getMediumDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
    }

    /**
     * Gets the current date format stored as a char array. The array will contain
     * 3 elements ({@link #DATE}, {@link #MONTH}, and {@link #YEAR}) in the order    
     * specified by the user's format preference.  Note that this order is
     * only appropriate for all-numeric dates; spelled-out (MEDIUM and LONG)
     * dates will generally contain other punctuation, spaces, or words,
     * not just the day, month, and year, and not necessarily in the same
     * order returned here.
     */    
    public static final char[] getDateFormatOrder(Context context) {
        char[] order = new char[] {DATE, MONTH, YEAR};
        String value = getDateFormatString(context);
        int index = 0;
        boolean foundDate = false;
        boolean foundMonth = false;
        boolean foundYear = false;

        for (char c : value.toCharArray()) {
            if (!foundDate && (c == DATE)) {
                foundDate = true;
                order[index] = DATE;
                index++;
            }

            if (!foundMonth && (c == MONTH)) {
                foundMonth = true;
                order[index] = MONTH;
                index++;
            }

            if (!foundYear && (c == YEAR)) {
                foundYear = true;
                order[index] = YEAR;
                index++;
            }
        }
        return order;
    }
    
    private static String getDateFormatString(Context context) {
        String value = Settings.System.getString(context.getContentResolver(),
                Settings.System.DATE_FORMAT);

        return getDateFormatStringForSetting(context, value);
    }

    /**
     * Given a format string and a time in milliseconds since Jan 1, 1970 GMT, returns a 
     * CharSequence containing the requested date.
     * @param inFormat the format string, as described in {@link android.text.format.DateFormat}
     * @param inTimeInMillis in milliseconds since Jan 1, 1970 GMT
     * @return a {@link CharSequence} containing the requested text
     */
    public static final CharSequence format(CharSequence inFormat, long inTimeInMillis) {
        return format(inFormat, new Date(inTimeInMillis));
    }

    /**
     * Given a format string and a {@link java.util.Date} object, returns a CharSequence containing
     * the requested date.
     * @param inFormat the format string, as described in {@link android.text.format.DateFormat}
     * @param inDate the date to format
     * @return a {@link CharSequence} containing the requested text
     */
    public static final CharSequence format(CharSequence inFormat, Date inDate) {
        Calendar    c = new GregorianCalendar();
        
        c.setTime(inDate);
        
        return format(inFormat, c);
    }

    /**
     * Given a format string and a {@link java.util.Calendar} object, returns a CharSequence 
     * containing the requested date.
     * @param inFormat the format string, as described in {@link android.text.format.DateFormat}
     * @param inDate the date to format
     * @return a {@link CharSequence} containing the requested text
     */
    public static final CharSequence format(CharSequence inFormat, Calendar inDate) {
        SpannableStringBuilder      s = new SpannableStringBuilder(inFormat);
        int             c;
        int             count;

        int len = inFormat.length();

        for (int i = 0; i < len; i += count) {
            int temp;

            count = 1;
            c = s.charAt(i);

            if (c == QUOTE) {
                count = appendQuotedText(s, i, len);
                len = s.length();
                continue;
            }

            while ((i + count < len) && (s.charAt(i + count) == c)) {
                count++;
            }

            String replacement;

            switch (c) {
                case AM_PM:
                    replacement = DateUtils.getAMPMString(inDate.get(Calendar.AM_PM));
                    break;
                                        
                case CAPITAL_AM_PM:
                    //FIXME: this is the same as AM_PM? no capital?
                    replacement = DateUtils.getAMPMString(inDate.get(Calendar.AM_PM));
                    break;
                
                case DATE:
                    replacement = zeroPad(inDate.get(Calendar.DATE), count);
                    break;
                    
                case DAY:
                    temp = inDate.get(Calendar.DAY_OF_WEEK);
                    replacement = DateUtils.getDayOfWeekString(temp,
                                                               count < 4 ? 
                                                               DateUtils.LENGTH_MEDIUM : 
                                                               DateUtils.LENGTH_LONG);
                    break;
                    
                case HOUR:
                    temp = inDate.get(Calendar.HOUR);

                    if (0 == temp)
                        temp = 12;
                    
                    replacement = zeroPad(temp, count);
                    break;
                    
                case HOUR_OF_DAY:
                    replacement = zeroPad(inDate.get(Calendar.HOUR_OF_DAY), count);
                    break;
                    
                case MINUTE:
                    replacement = zeroPad(inDate.get(Calendar.MINUTE), count);
                    break;
                    
                case MONTH:
                    replacement = getMonthString(inDate, count);
                    break;
                    
                case SECONDS:
                    replacement = zeroPad(inDate.get(Calendar.SECOND), count);
                    break;
                    
                case TIME_ZONE:
                    replacement = getTimeZoneString(inDate, count);
                    break;
                    
                case YEAR:
                    replacement = getYearString(inDate, count);
                    break;

                default:
                    replacement = null;
                    break;
            }
            
            if (replacement != null) {
                s.replace(i, i + count, replacement);
                count = replacement.length(); // CARE: count is used in the for loop above
                len = s.length();
            }
        }
        
        if (inFormat instanceof Spanned)
            return new SpannedString(s);
        else
            return s.toString();
    }
    
    private static final String getMonthString(Calendar inDate, int count) {
        int month = inDate.get(Calendar.MONTH);
        
        if (count >= 4)
            return DateUtils.getMonthString(month, DateUtils.LENGTH_LONG);
        else if (count == 3)
            return DateUtils.getMonthString(month, DateUtils.LENGTH_MEDIUM);
        else {
            // Calendar.JANUARY == 0, so add 1 to month.
            return zeroPad(month+1, count);
        }
    }
        
    private static final String getTimeZoneString(Calendar inDate, int count) {
        TimeZone tz = inDate.getTimeZone();
        
        if (count < 2) { // FIXME: shouldn't this be <= 2 ?
            return formatZoneOffset(inDate.get(Calendar.DST_OFFSET) +
                                    inDate.get(Calendar.ZONE_OFFSET),
                                    count);
        } else {
            boolean dst = inDate.get(Calendar.DST_OFFSET) != 0;
            return tz.getDisplayName(dst, TimeZone.SHORT);
        }
    }

    private static final String formatZoneOffset(int offset, int count) {
        offset /= 1000; // milliseconds to seconds
        StringBuilder tb = new StringBuilder();

        if (offset < 0) {
            tb.insert(0, "-");
            offset = -offset;
        } else {
            tb.insert(0, "+");
        }

        int hours = offset / 3600;
        int minutes = (offset % 3600) / 60;

        tb.append(zeroPad(hours, 2));
        tb.append(zeroPad(minutes, 2));
        return tb.toString();
    }
    
    private static final String getYearString(Calendar inDate, int count) {
        int year = inDate.get(Calendar.YEAR);
        return (count <= 2) ? zeroPad(year % 100, 2) : String.valueOf(year);
    }
   
    private static final int appendQuotedText(SpannableStringBuilder s, int i, int len) {
        if (i + 1 < len && s.charAt(i + 1) == QUOTE) {
            s.delete(i, i + 1);
            return 1;
        }

        int count = 0;

        // delete leading quote
        s.delete(i, i + 1);
        len--;

        while (i < len) {
            char c = s.charAt(i);

            if (c == QUOTE) {
                //  QUOTEQUOTE -> QUOTE
                if (i + 1 < len && s.charAt(i + 1) == QUOTE) {

                    s.delete(i, i + 1);
                    len--;
                    count++;
                    i++;
                } else {
                    //  Closing QUOTE ends quoted text copying
                    s.delete(i, i + 1);
                    break;
                }
            } else {
                i++;
                count++;
            }
        }

        return count;
    }

    private static final String zeroPad(int inValue, int inMinDigits) {
        String val = String.valueOf(inValue);

        if (val.length() < inMinDigits) {
            char[] buf = new char[inMinDigits];

            for (int i = 0; i < inMinDigits; i++)
                buf[i] = '0';

            val.getChars(0, val.length(), buf, inMinDigits - val.length());
            val = new String(buf);
        }
        return val;
    }
}
