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

import libcore.icu.ICU;
import libcore.icu.LocaleData;

/**
 * Utility class for producing strings with formatted date/time.
 *
 * <p>Most callers should avoid supplying their own format strings to this
 * class' {@code format} methods and rely on the correctly localized ones
 * supplied by the system. This class' factory methods return
 * appropriately-localized {@link java.text.DateFormat} instances, suitable
 * for both formatting and parsing dates. For the canonical documentation
 * of format strings, see {@link java.text.SimpleDateFormat}.
 *
 * <p>In cases where the system does not provide a suitable pattern,
 * this class offers the {@link #getBestDateTimePattern} method.
 *
 * <p>The {@code format} methods in this class implement a subset of Unicode
 * <a href="http://www.unicode.org/reports/tr35/#Date_Format_Patterns">UTS #35</a> patterns.
 * The subset currently supported by this class includes the following format characters:
 * {@code acdEHhLKkLMmsyz}. Up to API level 17, only {@code adEhkMmszy} were supported.
 * Note that this class incorrectly implements {@code k} as if it were {@code H} for backwards
 * compatibility.
 *
 * <p>See {@link java.text.SimpleDateFormat} for more documentation
 * about patterns, or if you need a more complete or correct implementation.
 * Note that the non-{@code format} methods in this class are implemented by
 * {@code SimpleDateFormat}.
 */
public class DateFormat {
    /** @deprecated Use a literal {@code '} instead. */
    @Deprecated
    public  static final char    QUOTE                  =    '\'';

    /** @deprecated Use a literal {@code 'a'} instead. */
    @Deprecated
    public  static final char    AM_PM                  =    'a';

    /** @deprecated Use a literal {@code 'a'} instead; 'A' was always equivalent to 'a'. */
    @Deprecated
    public  static final char    CAPITAL_AM_PM          =    'A';

    /** @deprecated Use a literal {@code 'd'} instead. */
    @Deprecated
    public  static final char    DATE                   =    'd';

    /** @deprecated Use a literal {@code 'E'} instead. */
    @Deprecated
    public  static final char    DAY                    =    'E';

    /** @deprecated Use a literal {@code 'h'} instead. */
    @Deprecated
    public  static final char    HOUR                   =    'h';

    /**
     * @deprecated Use a literal {@code 'H'} (for compatibility with {@link SimpleDateFormat}
     * and Unicode) or {@code 'k'} (for compatibility with Android releases up to and including
     * Jelly Bean MR-1) instead. Note that the two are incompatible.
     */
    @Deprecated
    public  static final char    HOUR_OF_DAY            =    'k';

    /** @deprecated Use a literal {@code 'm'} instead. */
    @Deprecated
    public  static final char    MINUTE                 =    'm';

    /** @deprecated Use a literal {@code 'M'} instead. */
    @Deprecated
    public  static final char    MONTH                  =    'M';

    /** @deprecated Use a literal {@code 'L'} instead. */
    @Deprecated
    public  static final char    STANDALONE_MONTH       =    'L';

    /** @deprecated Use a literal {@code 's'} instead. */
    @Deprecated
    public  static final char    SECONDS                =    's';

    /** @deprecated Use a literal {@code 'z'} instead. */
    @Deprecated
    public  static final char    TIME_ZONE              =    'z';

    /** @deprecated Use a literal {@code 'y'} instead. */
    @Deprecated
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
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.LONG, locale);

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
                sIs24Hour = value.equals("24");
            }

            return sIs24Hour;
        }

        return value.equals("24");
    }

    /**
     * Returns the best possible localized form of the given skeleton for the given
     * locale. A skeleton is similar to, and uses the same format characters as, a Unicode
     * <a href="http://www.unicode.org/reports/tr35/#Date_Format_Patterns">UTS #35</a>
     * pattern.
     *
     * <p>One difference is that order is irrelevant. For example, "MMMMd" will return
     * "MMMM d" in the {@code en_US} locale, but "d. MMMM" in the {@code de_CH} locale.
     *
     * <p>Note also in that second example that the necessary punctuation for German was
     * added. For the same input in {@code es_ES}, we'd have even more extra text:
     * "d 'de' MMMM".
     *
     * <p>This method will automatically correct for grammatical necessity. Given the
     * same "MMMMd" input, this method will return "d LLLL" in the {@code fa_IR} locale,
     * where stand-alone months are necessary. Lengths are preserved where meaningful,
     * so "Md" would give a different result to "MMMd", say, except in a locale such as
     * {@code ja_JP} where there is only one length of month.
     *
     * <p>This method will only return patterns that are in CLDR, and is useful whenever
     * you know what elements you want in your format string but don't want to make your
     * code specific to any one locale.
     *
     * @param locale the locale into which the skeleton should be localized
     * @param skeleton a skeleton as described above
     * @return a string pattern suitable for use with {@link java.text.SimpleDateFormat}.
     */
    public static String getBestDateTimePattern(Locale locale, String skeleton) {
        return ICU.getBestDateTimePattern(skeleton, locale.toString());
    }

    /**
     * Returns a {@link java.text.DateFormat} object that can format the time according
     * to the current locale and the user's 12-/24-hour clock preference.
     * @param context the application context
     * @return the {@link java.text.DateFormat} object that properly formats the time.
     */
    public static java.text.DateFormat getTimeFormat(Context context) {
        return new java.text.SimpleDateFormat(getTimeFormatString(context));
    }

    /**
     * Returns a String pattern that can be used to format the time according
     * to the current locale and the user's 12-/24-hour clock preference.
     * @param context the application context
     * @hide
     */
    public static String getTimeFormatString(Context context) {
        LocaleData d = LocaleData.get(context.getResources().getConfiguration().locale);
        return is24HourFormat(context) ? d.timeFormat24 : d.timeFormat12;
    }

    /**
     * Returns a {@link java.text.DateFormat} object that can format the date
     * in short form (such as 12/31/1999) according
     * to the current locale and the user's date-order preference.
     * @param context the application context
     * @return the {@link java.text.DateFormat} object that properly formats the date.
     */
    public static java.text.DateFormat getDateFormat(Context context) {
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

        // The setting is not set; use the locale's default.
        LocaleData d = LocaleData.get(context.getResources().getConfiguration().locale);
        return d.shortDateFormat4;
    }

    /**
     * Returns a {@link java.text.DateFormat} object that can format the date
     * in long form (such as {@code Monday, January 3, 2000}) for the current locale.
     * @param context the application context
     * @return the {@link java.text.DateFormat} object that formats the date in long form.
     */
    public static java.text.DateFormat getLongDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG);
    }

    /**
     * Returns a {@link java.text.DateFormat} object that can format the date
     * in medium form (such as {@code Jan 3, 2000}) for the current locale.
     * @param context the application context
     * @return the {@link java.text.DateFormat} object that formats the date in long form.
     */
    public static java.text.DateFormat getMediumDateFormat(Context context) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
    }

    /**
     * Gets the current date format stored as a char array. The array will contain
     * 3 elements ({@link #DATE}, {@link #MONTH}, and {@link #YEAR}) in the order
     * specified by the user's format preference.  Note that this order is
     * <i>only</i> appropriate for all-numeric dates; spelled-out (MEDIUM and LONG)
     * dates will generally contain other punctuation, spaces, or words,
     * not just the day, month, and year, and not necessarily in the same
     * order returned here.
     */
    public static char[] getDateFormatOrder(Context context) {
        return ICU.getDateFormatOrder(getDateFormatString(context));
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
    public static CharSequence format(CharSequence inFormat, long inTimeInMillis) {
        return format(inFormat, new Date(inTimeInMillis));
    }

    /**
     * Given a format string and a {@link java.util.Date} object, returns a CharSequence containing
     * the requested date.
     * @param inFormat the format string, as described in {@link android.text.format.DateFormat}
     * @param inDate the date to format
     * @return a {@link CharSequence} containing the requested text
     */
    public static CharSequence format(CharSequence inFormat, Date inDate) {
        Calendar c = new GregorianCalendar();
        c.setTime(inDate);
        return format(inFormat, c);
    }

    /**
     * Indicates whether the specified format string contains seconds.
     *
     * Always returns false if the input format is null.
     *
     * @param inFormat the format string, as described in {@link android.text.format.DateFormat}
     *
     * @return true if the format string contains {@link #SECONDS}, false otherwise
     *
     * @hide
     */
    public static boolean hasSeconds(CharSequence inFormat) {
        return hasDesignator(inFormat, SECONDS);
    }

    /**
     * Test if a format string contains the given designator. Always returns
     * {@code false} if the input format is {@code null}.
     *
     * @hide
     */
    public static boolean hasDesignator(CharSequence inFormat, char designator) {
        if (inFormat == null) return false;

        final int length = inFormat.length();

        int c;
        int count;

        for (int i = 0; i < length; i += count) {
            count = 1;
            c = inFormat.charAt(i);

            if (c == QUOTE) {
                count = skipQuotedText(inFormat, i, length);
            } else if (c == designator) {
                return true;
            }
        }

        return false;
    }

    private static int skipQuotedText(CharSequence s, int i, int len) {
        if (i + 1 < len && s.charAt(i + 1) == QUOTE) {
            return 2;
        }

        int count = 1;
        // skip leading quote
        i++;

        while (i < len) {
            char c = s.charAt(i);

            if (c == QUOTE) {
                count++;
                //  QUOTEQUOTE -> QUOTE
                if (i + 1 < len && s.charAt(i + 1) == QUOTE) {
                    i++;
                } else {
                    break;
                }
            } else {
                i++;
                count++;
            }
        }

        return count;
    }

    /**
     * Given a format string and a {@link java.util.Calendar} object, returns a CharSequence
     * containing the requested date.
     * @param inFormat the format string, as described in {@link android.text.format.DateFormat}
     * @param inDate the date to format
     * @return a {@link CharSequence} containing the requested text
     */
    public static CharSequence format(CharSequence inFormat, Calendar inDate) {
        SpannableStringBuilder s = new SpannableStringBuilder(inFormat);
        int count;

        LocaleData localeData = LocaleData.get(Locale.getDefault());

        int len = inFormat.length();

        for (int i = 0; i < len; i += count) {
            count = 1;
            int c = s.charAt(i);

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
                case 'A':
                case 'a':
                    replacement = localeData.amPm[inDate.get(Calendar.AM_PM) - Calendar.AM];
                    break;
                case 'd':
                    replacement = zeroPad(inDate.get(Calendar.DATE), count);
                    break;
                case 'c':
                case 'E':
                    replacement = getDayOfWeekString(localeData,
                                                     inDate.get(Calendar.DAY_OF_WEEK), count, c);
                    break;
                case 'K': // hour in am/pm (0-11)
                case 'h': // hour in am/pm (1-12)
                    {
                        int hour = inDate.get(Calendar.HOUR);
                        if (c == 'h' && hour == 0) {
                            hour = 12;
                        }
                        replacement = zeroPad(hour, count);
                    }
                    break;
                case 'H': // hour in day (0-23)
                case 'k': // hour in day (1-24) [but see note below]
                    {
                        int hour = inDate.get(Calendar.HOUR_OF_DAY);
                        // Historically on Android 'k' was interpreted as 'H', which wasn't
                        // implemented, so pretty much all callers that want to format 24-hour
                        // times are abusing 'k'. http://b/8359981.
                        if (false && c == 'k' && hour == 0) {
                            hour = 24;
                        }
                        replacement = zeroPad(hour, count);
                    }
                    break;
                case 'L':
                case 'M':
                    replacement = getMonthString(localeData,
                                                 inDate.get(Calendar.MONTH), count, c);
                    break;
                case 'm':
                    replacement = zeroPad(inDate.get(Calendar.MINUTE), count);
                    break;
                case 's':
                    replacement = zeroPad(inDate.get(Calendar.SECOND), count);
                    break;
                case 'y':
                    replacement = getYearString(inDate.get(Calendar.YEAR), count);
                    break;
                case 'z':
                    replacement = getTimeZoneString(inDate, count);
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

        if (inFormat instanceof Spanned) {
            return new SpannedString(s);
        } else {
            return s.toString();
        }
    }

    private static String getDayOfWeekString(LocaleData ld, int day, int count, int kind) {
        boolean standalone = (kind == 'c');
        if (count == 5) {
            return standalone ? ld.tinyStandAloneWeekdayNames[day] : ld.tinyWeekdayNames[day];
        } else if (count == 4) {
            return standalone ? ld.longStandAloneWeekdayNames[day] : ld.longWeekdayNames[day];
        } else {
            return standalone ? ld.shortStandAloneWeekdayNames[day] : ld.shortWeekdayNames[day];
        }
    }

    private static String getMonthString(LocaleData ld, int month, int count, int kind) {
        boolean standalone = (kind == 'L');
        if (count == 5) {
            return standalone ? ld.tinyStandAloneMonthNames[month] : ld.tinyMonthNames[month];
        } else if (count == 4) {
            return standalone ? ld.longStandAloneMonthNames[month] : ld.longMonthNames[month];
        } else if (count == 3) {
            return standalone ? ld.shortStandAloneMonthNames[month] : ld.shortMonthNames[month];
        } else {
            // Calendar.JANUARY == 0, so add 1 to month.
            return zeroPad(month+1, count);
        }
    }

    private static String getTimeZoneString(Calendar inDate, int count) {
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

    private static String formatZoneOffset(int offset, int count) {
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

    private static String getYearString(int year, int count) {
        return (count <= 2) ? zeroPad(year % 100, 2)
                            : String.format(Locale.getDefault(), "%d", year);
    }

    private static int appendQuotedText(SpannableStringBuilder s, int i, int len) {
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

    private static String zeroPad(int inValue, int inMinDigits) {
        return String.format(Locale.getDefault(), "%0" + inMinDigits + "d", inValue);
    }
}
