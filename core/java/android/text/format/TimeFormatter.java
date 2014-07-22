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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.Locale;
import java.util.TimeZone;
import libcore.icu.LocaleData;
import libcore.util.ZoneInfo;

/**
 * Formatting logic for {@link Time}. Contains a port of Bionic's broken strftime_tz to Java. The
 * main issue with this implementation is the treatment of characters as ASCII, despite returning
 * localized (UTF-16) strings from the LocaleData.
 *
 * <p>This class is not thread safe.
 */
class TimeFormatter {
    // An arbitrary value outside the range representable by a byte / ASCII character code.
    private static final int FORCE_LOWER_CASE = 0x100;

    private static final int SECSPERMIN = 60;
    private static final int MINSPERHOUR = 60;
    private static final int DAYSPERWEEK = 7;
    private static final int MONSPERYEAR = 12;
    private static final int HOURSPERDAY = 24;
    private static final int DAYSPERLYEAR = 366;
    private static final int DAYSPERNYEAR = 365;

    /**
     * The Locale for which the cached LocaleData and formats have been loaded.
     */
    private static Locale sLocale;
    private static LocaleData sLocaleData;
    private static String sTimeOnlyFormat;
    private static String sDateOnlyFormat;
    private static String sDateTimeFormat;

    private final LocaleData localeData;
    private final String dateTimeFormat;
    private final String timeOnlyFormat;
    private final String dateOnlyFormat;
    private final Locale locale;

    private StringBuilder outputBuilder;
    private Formatter outputFormatter;

    public TimeFormatter() {
        synchronized (TimeFormatter.class) {
            Locale locale = Locale.getDefault();

            if (sLocale == null || !(locale.equals(sLocale))) {
                sLocale = locale;
                sLocaleData = LocaleData.get(locale);

                Resources r = Resources.getSystem();
                sTimeOnlyFormat = r.getString(com.android.internal.R.string.time_of_day);
                sDateOnlyFormat = r.getString(com.android.internal.R.string.month_day_year);
                sDateTimeFormat = r.getString(com.android.internal.R.string.date_and_time);
            }

            this.dateTimeFormat = sDateTimeFormat;
            this.timeOnlyFormat = sTimeOnlyFormat;
            this.dateOnlyFormat = sDateOnlyFormat;
            this.locale = locale;
            localeData = sLocaleData;
        }
    }

    /**
     * Format the specified {@code wallTime} using {@code pattern}. The output is returned.
     */
    public String format(String pattern, ZoneInfo.WallTime wallTime, ZoneInfo zoneInfo) {
        try {
            StringBuilder stringBuilder = new StringBuilder();

            outputBuilder = stringBuilder;
            outputFormatter = new Formatter(stringBuilder, locale);

            formatInternal(pattern, wallTime, zoneInfo);
            String result = stringBuilder.toString();
            // This behavior is the source of a bug since some formats are defined as being
            // in ASCII. Generally localization is very broken.
            if (localeData.zeroDigit != '0') {
                result = localizeDigits(result);
            }
            return result;
        } finally {
            outputBuilder = null;
            outputFormatter = null;
        }
    }

    private String localizeDigits(String s) {
        int length = s.length();
        int offsetToLocalizedDigits = localeData.zeroDigit - '0';
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
    private void formatInternal(String pattern, ZoneInfo.WallTime wallTime, ZoneInfo zoneInfo) {
        // Convert to ASCII bytes to be compatible with old implementation behavior.
        byte[] bytes = pattern.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length == 0) {
            return;
        }

        ByteBuffer formatBuffer = ByteBuffer.wrap(bytes);
        while (formatBuffer.remaining() > 0) {
            boolean outputCurrentByte = true;
            char currentByteAsChar = convertToChar(formatBuffer.get(formatBuffer.position()));
            if (currentByteAsChar == '%') {
                outputCurrentByte = handleToken(formatBuffer, wallTime, zoneInfo);
            }
            if (outputCurrentByte) {
                currentByteAsChar = convertToChar(formatBuffer.get(formatBuffer.position()));
                outputBuilder.append(currentByteAsChar);
            }

            formatBuffer.position(formatBuffer.position() + 1);
        }
    }

    private boolean handleToken(ByteBuffer formatBuffer, ZoneInfo.WallTime wallTime,
            ZoneInfo zoneInfo) {

        // The byte at formatBuffer.position() is expected to be '%' at this point.
        int modifier = 0;
        while (formatBuffer.remaining() > 1) {
            // Increment the position then get the new current byte.
            formatBuffer.position(formatBuffer.position() + 1);
            char currentByteAsChar = convertToChar(formatBuffer.get(formatBuffer.position()));
            switch (currentByteAsChar) {
                case 'A':
                    modifyAndAppend((wallTime.getWeekDay() < 0
                                    || wallTime.getWeekDay() >= DAYSPERWEEK)
                                    ? "?" : localeData.longWeekdayNames[wallTime.getWeekDay() + 1],
                            modifier);
                    return false;
                case 'a':
                    modifyAndAppend((wallTime.getWeekDay() < 0
                                    || wallTime.getWeekDay() >= DAYSPERWEEK)
                                    ? "?" : localeData.shortWeekdayNames[wallTime.getWeekDay() + 1],
                            modifier);
                    return false;
                case 'B':
                    if (modifier == '-') {
                        modifyAndAppend((wallTime.getMonth() < 0
                                        || wallTime.getMonth() >= MONSPERYEAR)
                                        ? "?"
                                        : localeData.longStandAloneMonthNames[wallTime.getMonth()],
                                modifier);
                    } else {
                        modifyAndAppend((wallTime.getMonth() < 0
                                        || wallTime.getMonth() >= MONSPERYEAR)
                                        ? "?" : localeData.longMonthNames[wallTime.getMonth()],
                                modifier);
                    }
                    return false;
                case 'b':
                case 'h':
                    modifyAndAppend((wallTime.getMonth() < 0 || wallTime.getMonth() >= MONSPERYEAR)
                                    ? "?" : localeData.shortMonthNames[wallTime.getMonth()],
                            modifier);
                    return false;
                case 'C':
                    outputYear(wallTime.getYear(), true, false, modifier);
                    return false;
                case 'c':
                    formatInternal(dateTimeFormat, wallTime, zoneInfo);
                    return false;
                case 'D':
                    formatInternal("%m/%d/%y", wallTime, zoneInfo);
                    return false;
                case 'd':
                    outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
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
                    modifier = currentByteAsChar;
                    continue;
                case 'e':
                    outputFormatter.format(getFormat(modifier, "%2d", "%2d", "%d", "%02d"),
                            wallTime.getMonthDay());
                    return false;
                case 'F':
                    formatInternal("%Y-%m-%d", wallTime, zoneInfo);
                    return false;
                case 'H':
                    outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getHour());
                    return false;
                case 'I':
                    int hour = (wallTime.getHour() % 12 != 0) ? (wallTime.getHour() % 12) : 12;
                    outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), hour);
                    return false;
                case 'j':
                    int yearDay = wallTime.getYearDay() + 1;
                    outputFormatter.format(getFormat(modifier, "%03d", "%3d", "%d", "%03d"),
                            yearDay);
                    return false;
                case 'k':
                    outputFormatter.format(getFormat(modifier, "%2d", "%2d", "%d", "%02d"),
                            wallTime.getHour());
                    return false;
                case 'l':
                    int n2 = (wallTime.getHour() % 12 != 0) ? (wallTime.getHour() % 12) : 12;
                    outputFormatter.format(getFormat(modifier, "%2d", "%2d", "%d", "%02d"), n2);
                    return false;
                case 'M':
                    outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getMinute());
                    return false;
                case 'm':
                    outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getMonth() + 1);
                    return false;
                case 'n':
                    modifyAndAppend("\n", modifier);
                    return false;
                case 'p':
                    modifyAndAppend((wallTime.getHour() >= (HOURSPERDAY / 2)) ? localeData.amPm[1]
                            : localeData.amPm[0], modifier);
                    return false;
                case 'P':
                    modifyAndAppend((wallTime.getHour() >= (HOURSPERDAY / 2)) ? localeData.amPm[1]
                            : localeData.amPm[0], FORCE_LOWER_CASE);
                    return false;
                case 'R':
                    formatInternal("%H:%M", wallTime, zoneInfo);
                    return false;
                case 'r':
                    formatInternal("%I:%M:%S %p", wallTime, zoneInfo);
                    return false;
                case 'S':
                    outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            wallTime.getSecond());
                    return false;
                case 's':
                    int timeInSeconds = wallTime.mktime(zoneInfo);
                    modifyAndAppend(Integer.toString(timeInSeconds), modifier);
                    return false;
                case 'T':
                    formatInternal("%H:%M:%S", wallTime, zoneInfo);
                    return false;
                case 't':
                    modifyAndAppend("\t", modifier);
                    return false;
                case 'U':
                    outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"),
                            (wallTime.getYearDay() + DAYSPERWEEK - wallTime.getWeekDay())
                                    / DAYSPERWEEK);
                    return false;
                case 'u':
                    int day = (wallTime.getWeekDay() == 0) ? DAYSPERWEEK : wallTime.getWeekDay();
                    outputFormatter.format("%d", day);
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
                    if (currentByteAsChar == 'V') {
                        outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), w);
                    } else if (currentByteAsChar == 'g') {
                        outputYear(year, false, true, modifier);
                    } else {
                        outputYear(year, true, true, modifier);
                    }
                    return false;
                }
                case 'v':
                    formatInternal("%e-%b-%Y", wallTime, zoneInfo);
                    return false;
                case 'W':
                    int n = (wallTime.getYearDay() + DAYSPERWEEK - (
                                    wallTime.getWeekDay() != 0 ? (wallTime.getWeekDay() - 1)
                                            : (DAYSPERWEEK - 1))) / DAYSPERWEEK;
                    outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), n);
                    return false;
                case 'w':
                    outputFormatter.format("%d", wallTime.getWeekDay());
                    return false;
                case 'X':
                    formatInternal(timeOnlyFormat, wallTime, zoneInfo);
                    return false;
                case 'x':
                    formatInternal(dateOnlyFormat, wallTime, zoneInfo);
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
                    modifyAndAppend(zoneInfo.getDisplayName(isDst, TimeZone.SHORT), modifier);
                    return false;
                case 'z': {
                    if (wallTime.getIsDst() < 0) {
                        return false;
                    }
                    int diff = wallTime.getGmtOffset();
                    String sign;
                    if (diff < 0) {
                        sign = "-";
                        diff = -diff;
                    } else {
                        sign = "+";
                    }
                    modifyAndAppend(sign, modifier);
                    diff /= SECSPERMIN;
                    diff = (diff / MINSPERHOUR) * 100 + (diff % MINSPERHOUR);
                    outputFormatter.format(getFormat(modifier, "%04d", "%4d", "%d", "%04d"), diff);
                    return false;
                }
                case '+':
                    formatInternal("%a %b %e %H:%M:%S %Z %Y", wallTime, zoneInfo);
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
                modifyAndAppend("-0", modifier);
            } else {
                outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), lead);
            }
        }
        if (outputBottom) {
            int n = ((trail < 0) ? -trail : trail);
            outputFormatter.format(getFormat(modifier, "%02d", "%2d", "%d", "%02d"), n);
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
     * A broken implementation of {@link Character#isUpperCase(char)} that assumes ASCII in order to
     * be compatible with the old native implementation.
     */
    private static boolean brokenIsUpper(char toCheck) {
        return toCheck >= 'A' && toCheck <= 'Z';
    }

    /**
     * A broken implementation of {@link Character#isLowerCase(char)} that assumes ASCII in order to
     * be compatible with the old native implementation.
     */
    private static boolean brokenIsLower(char toCheck) {
        return toCheck >= 'a' && toCheck <= 'z';
    }

    /**
     * A broken implementation of {@link Character#toLowerCase(char)} that assumes ASCII in order to
     * be compatible with the old native implementation.
     */
    private static char brokenToLower(char input) {
        if (input >= 'A' && input <= 'Z') {
            return (char) (input - 'A' + 'a');
        }
        return input;
    }

    /**
     * A broken implementation of {@link Character#toUpperCase(char)} that assumes ASCII in order to
     * be compatible with the old native implementation.
     */
    private static char brokenToUpper(char input) {
        if (input >= 'a' && input <= 'z') {
            return (char) (input - 'a' + 'A');
        }
        return input;
    }

    /**
     * Safely convert a byte containing an ASCII character to a char, even for character codes
     * > 127.
     */
    private static char convertToChar(byte b) {
        return (char) (b & 0xFF);
    }
}
