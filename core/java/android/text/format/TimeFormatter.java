/*
 * Based on the UCB version of strftime.c with the copyright notice appearing below.
 */

/*
** Copyright (c) 1989 The Regents of the University of California.
** All rights reserved.
**
** Redistribution and use in source and binary forms are permitted
** provided that the above copyright notice and this paragraph are
** duplicated in all such forms and that any documentation,
** advertising materials, and other materials related to such
** distribution and use acknowledge that the software was developed
** by the University of California, Berkeley. The name of the
** University may not be used to endorse or promote products derived
** from this software without specific prior written permission.
** THIS SOFTWARE IS PROVIDED "AS IS" AND WITHOUT ANY EXPRESS OR
** IMPLIED WARRANTIES, INCLUDING, WITHOUT LIMITATION, THE IMPLIED
** WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE.
*/
package android.text.format;

import android.content.res.Resources;
import android.icu.text.DateFormatSymbols;
import android.icu.text.DecimalFormatSymbols;

import com.android.i18n.timezone.WallTime;
import com.android.i18n.timezone.ZoneInfoData;

import java.nio.CharBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Formatter;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Formatting logic for {@link Time}. Contains a port of Bionic's broken strftime_tz to Java.
 *
 * <p>This class is not thread safe.
 */
class TimeFormatter {
    // An arbitrary value outside the range representable by a char.
    private static final int FORCE_LOWER_CASE = -1;

    private static final int SECSPERMIN = 60;
    private static final int MINSPERHOUR = 60;
    private static final int DAYSPERWEEK = 7;
    private static final int MONSPERYEAR = 12;
    private static final int HOURSPERDAY = 24;
    private static final int DAYSPERLYEAR = 366;
    private static final int DAYSPERNYEAR = 365;

    /**
     * The Locale for which the cached symbols and formats have been loaded.
     */
    private static Locale sLocale;
    private static DateFormatSymbols sDateFormatSymbols;
    private static DecimalFormatSymbols sDecimalFormatSymbols;
    private static String sTimeOnlyFormat;
    private static String sDateOnlyFormat;
    private static String sDateTimeFormat;

    private final DateFormatSymbols dateFormatSymbols;
    private final DecimalFormatSymbols decimalFormatSymbols;
    private final String dateTimeFormat;
    private final String timeOnlyFormat;
    private final String dateOnlyFormat;

    private StringBuilder outputBuilder;
    private Formatter numberFormatter;

    public TimeFormatter() {
        synchronized (TimeFormatter.class) {
            Locale locale = Locale.getDefault();

            if (sLocale == null || !(locale.equals(sLocale))) {
                sLocale = locale;
                sDateFormatSymbols = DateFormat.getIcuDateFormatSymbols(locale);
                sDecimalFormatSymbols = DecimalFormatSymbols.getInstance(locale);

                Resources r = Resources.getSystem();
                sTimeOnlyFormat = r.getString(com.android.internal.R.string.time_of_day);
                sDateOnlyFormat = r.getString(com.android.internal.R.string.month_day_year);
                sDateTimeFormat = r.getString(com.android.internal.R.string.date_and_time);
            }

            this.dateFormatSymbols = sDateFormatSymbols;
            this.decimalFormatSymbols = sDecimalFormatSymbols;
            this.dateTimeFormat = sDateTimeFormat;
            this.timeOnlyFormat = sTimeOnlyFormat;
            this.dateOnlyFormat = sDateOnlyFormat;
        }
    }

    /**
     * The implementation of {@link TimeMigrationUtils#formatMillisWithFixedFormat(long)} for
     * 2038-safe formatting with the pattern "%Y-%m-%d %H:%M:%S" and including the historic
     * incorrect digit localization behavior.
     */
    String formatMillisWithFixedFormat(long timeMillis) {
        // This method is deliberately not a general purpose replacement for format(String,
        // ZoneInfoData.WallTime, ZoneInfoData): It hard-codes the pattern used; many of the
        // pattern characters supported by Time.format() have unusual behavior which would make
        // using java.time.format or similar packages difficult. It would be a lot of work to share
        // behavior and many internal Android usecases can be covered by this common pattern
        // behavior.

        // No need to worry about overflow / underflow: long millis is representable by Instant and
        // LocalDateTime with room to spare.
        Instant instant = Instant.ofEpochMilli(timeMillis);

        // Date/times are calculated in the current system default time zone.
        LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

        // You'd think it would be as simple as:
        // DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", locale);
        // return formatter.format(localDateTime);
        // but we retain Time's behavior around digits.

        StringBuilder stringBuilder = new StringBuilder(19);

        // This effectively uses the US locale because number localization is handled separately
        // (see below).
        stringBuilder.append(localDateTime.getYear());
        stringBuilder.append('-');
        append2DigitNumber(stringBuilder, localDateTime.getMonthValue());
        stringBuilder.append('-');
        append2DigitNumber(stringBuilder, localDateTime.getDayOfMonth());
        stringBuilder.append(' ');
        append2DigitNumber(stringBuilder, localDateTime.getHour());
        stringBuilder.append(':');
        append2DigitNumber(stringBuilder, localDateTime.getMinute());
        stringBuilder.append(':');
        append2DigitNumber(stringBuilder, localDateTime.getSecond());

        String result = stringBuilder.toString();
        return localizeDigits(result);
    }

    /** Zero-pads value as needed to achieve a 2-digit number. */
    private static void append2DigitNumber(StringBuilder builder, int value) {
        if (value < 10) {
            builder.append('0');
        }
        builder.append(value);
    }

    /**
     * Format the specified {@code wallTime} using {@code pattern}. The output is returned.
     */
    public String format(String pattern, WallTime wallTime,
            ZoneInfoData zoneInfoData) {
        try {
            StringBuilder stringBuilder = new StringBuilder();

            outputBuilder = stringBuilder;
            // This uses the US locale because number localization is handled separately (see below)
            // and locale sensitive strings are output directly using outputBuilder.
            numberFormatter = new Formatter(stringBuilder, Locale.US);

            formatInternal(pattern, wallTime, zoneInfoData);
            String result = stringBuilder.toString();
            // The localizeDigits() behavior is the source of a bug since some formats are defined
            // as being in ASCII and not localized.
            return localizeDigits(result);
        } finally {
            outputBuilder = null;
            numberFormatter = null;
        }
    }

    private String localizeDigits(String s) {
        if (decimalFormatSymbols.getZeroDigit() == '0') {
            return s;
        }

        int length = s.length();
        int offsetToLocalizedDigits = decimalFormatSymbols.getZeroDigit() - '0';
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; ++i) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                ch += offsetToLocalizedDigits;
            }
            result.append(ch);
        }
        return result.toString();
    }

    /**
     * Format the specified {@code wallTime} using {@code pattern}. The output is written to
     * {@link #outputBuilder}.
     */
    private void formatInternal(String pattern, WallTime wallTime,
            ZoneInfoData zoneInfoData) {
        CharBuffer formatBuffer = CharBuffer.wrap(pattern);
        while (formatBuffer.remaining() > 0) {
            boolean outputCurrentChar = true;
            char currentChar = formatBuffer.get(formatBuffer.position());
            if (currentChar == '%') {
                outputCurrentChar = handleToken(formatBuffer, wallTime, zoneInfoData);
            }
            if (outputCurrentChar) {
                outputBuilder.append(formatBuffer.get(formatBuffer.position()));
            }
            formatBuffer.position(formatBuffer.position() + 1);
        }
    }

    private boolean handleToken(CharBuffer formatBuffer, WallTime wallTime,
            ZoneInfoData zoneInfoData) {

        // The char at formatBuffer.position() is expected to be '%' at this point.
        int modifier = 0;
        while (formatBuffer.remaining() > 1) {
            // Increment the position then get the new current char.
            formatBuffer.position(formatBuffer.position() + 1);
            char currentChar = formatBuffer.get(formatBuffer.position());
            switch (currentChar) {
                case 'A':
                    modifyAndAppend(
                        (wallTime.getWeekDay() < 0 || wallTime.getWeekDay() >= DAYSPERWEEK)
                            ? "?"
                            : dateFormatSymbols.getWeekdays(DateFormatSymbols.FORMAT,
                                DateFormatSymbols.WIDE)[wallTime.getWeekDay() + 1],
                            modifier);
                    return false;
                case 'a':
                    modifyAndAppend(
                        (wallTime.getWeekDay() < 0 || wallTime.getWeekDay() >= DAYSPERWEEK)
                            ? "?"
                            : dateFormatSymbols.getWeekdays(DateFormatSymbols.FORMAT,
                                DateFormatSymbols.ABBREVIATED)[wallTime.getWeekDay() + 1],
                            modifier);
                    return false;
                case 'B':
                    if (modifier == '-') {
                        modifyAndAppend(
                            (wallTime.getMonth() < 0 || wallTime.getMonth() >= MONSPERYEAR)
                                ? "?"
                                : dateFormatSymbols.getMonths(DateFormatSymbols.STANDALONE,
                                    DateFormatSymbols.WIDE)[wallTime.getMonth()],
                                modifier);
                    } else {
                        modifyAndAppend(
                            (wallTime.getMonth() < 0 || wallTime.getMonth() >= MONSPERYEAR)
                                ? "?"
                                : dateFormatSymbols.getMonths(DateFormatSymbols.FORMAT,
                                    DateFormatSymbols.WIDE)[wallTime.getMonth()],
                                modifier);
                    }
                    return false;
                case 'b':
                case 'h':
                    modifyAndAppend((wallTime.getMonth() < 0 || wallTime.getMonth() >= MONSPERYEAR)
                            ? "?"
                            : dateFormatSymbols.getMonths(DateFormatSymbols.FORMAT,
                                DateFormatSymbols.ABBREVIATED)[wallTime.getMonth()],
                            modifier);
                    return false;
                case 'C':
                    outputYear(wallTime.getYear(), true, false, modifier);
                    return false;
                case 'c':
                    formatInternal(dateTimeFormat, wallTime, zoneInfoData);
                    return false;
                case 'D':
                    formatInternal("%m/%d/%y", wallTime, zoneInfoData);
                    return false;
                case 'd':
                    numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getMonthDay());
                    return false;
                case 'E':
                case 'O':
                    // C99 locale modifiers are not supported.
                    continue;
                case '_':
                case '-':
                case '0':
                case '^':
                case '#':
                    modifier = currentChar;
                    continue;
                case 'e':
                    numberFormatter.format(getFormat(modifier, "%2d", "%2d", "%d", "%02d"),
                            wallTime.getMonthDay());
                    return false;
                case 'F':
                    formatInternal("%Y-%m-%d", wallTime, zoneInfoData);
                    return false;
                case 'H':
                    numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getHour());
                    return false;
                case 'I':
                    int hour = (wallTime.getHour() % 12 != 0) ? (wallTime.getHour() % 12) : 12;
                    numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), hour);
                    return false;
                case 'j':
                    int yearDay = wallTime.getYearDay() + 1;
                    numberFormatter.format(getFormat(modifier, "%03d", "%3d", "%d", "%03d"),
                            yearDay);
                    return false;
                case 'k':
                    numberFormatter.format(getFormat(modifier, "%2d", "%2d", "%d", "%02d"),
                            wallTime.getHour());
                    return false;
                case 'l':
                    int n2 = (wallTime.getHour() % 12 != 0) ? (wallTime.getHour() % 12) : 12;
                    numberFormatter.format(getFormat(modifier, "%2d", "%2d", "%d", "%02d"), n2);
                    return false;
                case 'M':
                    numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getMinute());
                    return false;
                case 'm':
                    numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getMonth() + 1);
                    return false;
                case 'n':
                    outputBuilder.append('\n');
                    return false;
                case 'p':
                    modifyAndAppend((wallTime.getHour() >= (HOURSPERDAY / 2))
                            ? dateFormatSymbols.getAmPmStrings()[1]
                            : dateFormatSymbols.getAmPmStrings()[0], modifier);
                    return false;
                case 'P':
                    modifyAndAppend((wallTime.getHour() >= (HOURSPERDAY / 2))
                            ? dateFormatSymbols.getAmPmStrings()[1]
                            : dateFormatSymbols.getAmPmStrings()[0], FORCE_LOWER_CASE);
                    return false;
                case 'R':
                    formatInternal("%H:%M", wallTime, zoneInfoData);
                    return false;
                case 'r':
                    formatInternal("%I:%M:%S %p", wallTime, zoneInfoData);
                    return false;
                case 'S':
                    numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getSecond());
                    return false;
                case 's':
                    int timeInSeconds = wallTime.mktime(zoneInfoData);
                    outputBuilder.append(Integer.toString(timeInSeconds));
                    return false;
                case 'T':
                    formatInternal("%H:%M:%S", wallTime, zoneInfoData);
                    return false;
                case 't':
                    outputBuilder.append('\t');
                    return false;
                case 'U':
                    numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            (wallTime.getYearDay() + DAYSPERWEEK - wallTime.getWeekDay())
                                    / DAYSPERWEEK);
                    return false;
                case 'u':
                    int day = (wallTime.getWeekDay() == 0) ? DAYSPERWEEK : wallTime.getWeekDay();
                    numberFormatter.format("%d", day);
                    return false;
                case 'V':   /* ISO 8601 week number */
                case 'G':   /* ISO 8601 year (four digits) */
                case 'g':   /* ISO 8601 year (two digits) */
                {
                    int year = wallTime.getYear();
                    int yday = wallTime.getYearDay();
                    int wday = wallTime.getWeekDay();
                    int w;
                    while (true) {
                        int len = isLeap(year) ? DAYSPERLYEAR : DAYSPERNYEAR;
                        // What yday (-3 ... 3) does the ISO year begin on?
                        int bot = ((yday + 11 - wday) % DAYSPERWEEK) - 3;
                        // What yday does the NEXT ISO year begin on?
                        int top = bot - (len % DAYSPERWEEK);
                        if (top < -3) {
                            top += DAYSPERWEEK;
                        }
                        top += len;
                        if (yday >= top) {
                            ++year;
                            w = 1;
                            break;
                        }
                        if (yday >= bot) {
                            w = 1 + ((yday - bot) / DAYSPERWEEK);
                            break;
                        }
                        --year;
                        yday += isLeap(year) ? DAYSPERLYEAR : DAYSPERNYEAR;
                    }
                    if (currentChar == 'V') {
                        numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), w);
                    } else if (currentChar == 'g') {
                        outputYear(year, false, true, modifier);
                    } else {
                        outputYear(year, true, true, modifier);
                    }
                    return false;
                }
                case 'v':
                    formatInternal("%e-%b-%Y", wallTime, zoneInfoData);
                    return false;
                case 'W':
                    int n = (wallTime.getYearDay() + DAYSPERWEEK - (
                                    wallTime.getWeekDay() != 0 ? (wallTime.getWeekDay() - 1)
                                            : (DAYSPERWEEK - 1))) / DAYSPERWEEK;
                    numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), n);
                    return false;
                case 'w':
                    numberFormatter.format("%d", wallTime.getWeekDay());
                    return false;
                case 'X':
                    formatInternal(timeOnlyFormat, wallTime, zoneInfoData);
                    return false;
                case 'x':
                    formatInternal(dateOnlyFormat, wallTime, zoneInfoData);
                    return false;
                case 'y':
                    outputYear(wallTime.getYear(), false, true, modifier);
                    return false;
                case 'Y':
                    outputYear(wallTime.getYear(), true, true, modifier);
                    return false;
                case 'Z':
                    if (wallTime.getIsDst() < 0) {
                        return false;
                    }
                    boolean isDst = wallTime.getIsDst() != 0;
                    modifyAndAppend(TimeZone.getTimeZone(zoneInfoData.getID())
                            .getDisplayName(isDst, TimeZone.SHORT), modifier);
                    return false;
                case 'z': {
                    if (wallTime.getIsDst() < 0) {
                        return false;
                    }
                    int diff = wallTime.getGmtOffset();
                    char sign;
                    if (diff < 0) {
                        sign = '-';
                        diff = -diff;
                    } else {
                        sign = '+';
                    }
                    outputBuilder.append(sign);
                    diff /= SECSPERMIN;
                    diff = (diff / MINSPERHOUR) * 100 + (diff % MINSPERHOUR);
                    numberFormatter.format(getFormat(modifier, "%04d", "%4d", "%d", "%04d"), diff);
                    return false;
                }
                case '+':
                    formatInternal("%a %b %e %H:%M:%S %Z %Y", wallTime, zoneInfoData);
                    return false;
                case '%':
                    // If conversion char is undefined, behavior is undefined. Print out the
                    // character itself.
                default:
                    return true;
            }
        }
        return true;
    }

    private void modifyAndAppend(CharSequence str, int modifier) {
        switch (modifier) {
            case FORCE_LOWER_CASE:
                for (int i = 0; i < str.length(); i++) {
                    outputBuilder.append(brokenToLower(str.charAt(i)));
                }
                break;
            case '^':
                for (int i = 0; i < str.length(); i++) {
                    outputBuilder.append(brokenToUpper(str.charAt(i)));
                }
                break;
            case '#':
                for (int i = 0; i < str.length(); i++) {
                    char c = str.charAt(i);
                    if (brokenIsUpper(c)) {
                        c = brokenToLower(c);
                    } else if (brokenIsLower(c)) {
                        c = brokenToUpper(c);
                    }
                    outputBuilder.append(c);
                }
                break;
            default:
                outputBuilder.append(str);
        }
    }

    private void outputYear(int value, boolean outputTop, boolean outputBottom, int modifier) {
        int lead;
        int trail;

        final int DIVISOR = 100;
        trail = value % DIVISOR;
        lead = value / DIVISOR + trail / DIVISOR;
        trail %= DIVISOR;
        if (trail < 0 && lead > 0) {
            trail += DIVISOR;
            --lead;
        } else if (lead < 0 && trail > 0) {
            trail -= DIVISOR;
            ++lead;
        }
        if (outputTop) {
            if (lead == 0 && trail < 0) {
                outputBuilder.append("-0");
            } else {
                numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), lead);
            }
        }
        if (outputBottom) {
            int n = ((trail < 0) ? -trail : trail);
            numberFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), n);
        }
    }

    private static String getFormat(int modifier, String normal, String underscore, String dash,
            String zero) {
        switch (modifier) {
            case '_':
                return underscore;
            case '-':
                return dash;
            case '0':
                return zero;
        }
        return normal;
    }

    private static boolean isLeap(int year) {
        return (((year) % 4) == 0 && (((year) % 100) != 0 || ((year) % 400) == 0));
    }

    /**
     * A broken implementation of {@link Character#isUpperCase(char)} that assumes ASCII codes in
     * order to be compatible with the old native implementation.
     */
    private static boolean brokenIsUpper(char toCheck) {
        return toCheck >= 'A' && toCheck <= 'Z';
    }

    /**
     * A broken implementation of {@link Character#isLowerCase(char)} that assumes ASCII codes in
     * order to be compatible with the old native implementation.
     */
    private static boolean brokenIsLower(char toCheck) {
        return toCheck >= 'a' && toCheck <= 'z';
    }

    /**
     * A broken implementation of {@link Character#toLowerCase(char)} that assumes ASCII codes in
     * order to be compatible with the old native implementation.
     */
    private static char brokenToLower(char input) {
        if (input >= 'A' && input <= 'Z') {
            return (char) (input - 'A' + 'a');
        }
        return input;
    }

    /**
     * A broken implementation of {@link Character#toUpperCase(char)} that assumes ASCII codes in
     * order to be compatible with the old native implementation.
     */
    private static char brokenToUpper(char input) {
        if (input >= 'a' && input <= 'z') {
            return (char) (input - 'a' + 'A');
        }
        return input;
    }

}
