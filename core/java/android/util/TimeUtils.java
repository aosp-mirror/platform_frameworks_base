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

package android.util;

import android.os.SystemClock;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import libcore.util.TimeZoneFinder;
import libcore.util.ZoneInfoDB;

/**
 * A class containing utility methods related to time zones.
 */
public class TimeUtils {
    /** @hide */ public TimeUtils() {}
    private static final boolean DBG = false;
    private static final String TAG = "TimeUtils";

    /** Cached results of getTimeZonesWithUniqueOffsets */
    private static final Object sLastUniqueLockObj = new Object();
    private static List<String> sLastUniqueZoneOffsets = null;
    private static String sLastUniqueCountry = null;

    /** {@hide} */
    private static SimpleDateFormat sLoggingFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Tries to return a time zone that would have had the specified offset
     * and DST value at the specified moment in the specified country.
     * Returns null if no suitable zone could be found.
     */
    public static java.util.TimeZone getTimeZone(
            int offset, boolean dst, long when, String country) {

        android.icu.util.TimeZone icuTimeZone = getIcuTimeZone(offset, dst, when, country);
        // We must expose a java.util.TimeZone here for API compatibility because this is a public
        // API method.
        return icuTimeZone != null ? java.util.TimeZone.getTimeZone(icuTimeZone.getID()) : null;
    }

    /**
     * Tries to return a frozen ICU time zone that would have had the specified offset
     * and DST value at the specified moment in the specified country.
     * Returns null if no suitable zone could be found.
     */
    private static android.icu.util.TimeZone getIcuTimeZone(
            int offset, boolean dst, long when, String country) {
        if (country == null) {
            return null;
        }

        android.icu.util.TimeZone bias = android.icu.util.TimeZone.getDefault();
        return TimeZoneFinder.getInstance()
                .lookupTimeZoneByCountryAndOffset(country, offset, dst, when, bias);
    }

    /**
     * Returns an immutable list of unique time zone IDs for the country.
     *
     * @param country to find
     * @return unmodifiable list of unique time zones, maybe empty but never null.
     * @hide
     */
    public static List<String> getTimeZoneIdsWithUniqueOffsets(String country) {
        synchronized(sLastUniqueLockObj) {
            if ((country != null) && country.equals(sLastUniqueCountry)) {
                if (DBG) {
                    Log.d(TAG, "getTimeZonesWithUniqueOffsets(" +
                            country + "): return cached version");
                }
                return sLastUniqueZoneOffsets;
            }
        }

        Collection<android.icu.util.TimeZone> zones = getIcuTimeZones(country);
        ArrayList<android.icu.util.TimeZone> uniqueTimeZones = new ArrayList<>();
        for (android.icu.util.TimeZone zone : zones) {
            // See if we already have this offset,
            // Using slow but space efficient and these are small.
            boolean found = false;
            for (int i = 0; i < uniqueTimeZones.size(); i++) {
                if (uniqueTimeZones.get(i).getRawOffset() == zone.getRawOffset()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (DBG) {
                    Log.d(TAG, "getTimeZonesWithUniqueOffsets: add unique offset=" +
                            zone.getRawOffset() + " zone.getID=" + zone.getID());
                }
                uniqueTimeZones.add(zone);
            }
        }

        synchronized(sLastUniqueLockObj) {
            // Cache the last result
            sLastUniqueZoneOffsets = extractZoneIds(uniqueTimeZones);
            sLastUniqueCountry = country;

            return sLastUniqueZoneOffsets;
        }
    }

    private static List<String> extractZoneIds(List<android.icu.util.TimeZone> timeZones) {
        List<String> ids = new ArrayList<>(timeZones.size());
        for (android.icu.util.TimeZone timeZone : timeZones) {
            ids.add(timeZone.getID());
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * Returns an immutable list of frozen ICU time zones for the country.
     *
     * @param countryIso is a two character country code.
     * @return TimeZone list, maybe empty but never null.
     * @hide
     */
    private static List<android.icu.util.TimeZone> getIcuTimeZones(String countryIso) {
        if (countryIso == null) {
            if (DBG) Log.d(TAG, "getIcuTimeZones(null): return empty list");
            return Collections.emptyList();
        }
        List<android.icu.util.TimeZone> timeZones =
                TimeZoneFinder.getInstance().lookupTimeZonesByCountry(countryIso);
        if (timeZones == null) {
            if (DBG) {
                Log.d(TAG, "getIcuTimeZones(" + countryIso
                        + "): returned null, converting to empty list");
            }
            return Collections.emptyList();
        }
        return timeZones;
    }

    /**
     * Returns a String indicating the version of the time zone database currently
     * in use.  The format of the string is dependent on the underlying time zone
     * database implementation, but will typically contain the year in which the database
     * was updated plus a letter from a to z indicating changes made within that year.
     *
     * <p>Time zone database updates should be expected to occur periodically due to
     * political and legal changes that cannot be anticipated in advance.  Therefore,
     * when computing the UTC time for a future event, applications should be aware that
     * the results may differ following a time zone database update.  This method allows
     * applications to detect that a database change has occurred, and to recalculate any
     * cached times accordingly.
     *
     * <p>The time zone database may be assumed to change only when the device runtime
     * is restarted.  Therefore, it is not necessary to re-query the database version
     * during the lifetime of an activity.
     */
    public static String getTimeZoneDatabaseVersion() {
        return ZoneInfoDB.getInstance().getVersion();
    }

    /** @hide Field length that can hold 999 days of time */
    public static final int HUNDRED_DAY_FIELD_LEN = 19;

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 60 * 60;
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    /** @hide */
    public static final long NANOS_PER_MS = 1000000;

    private static final Object sFormatSync = new Object();
    private static char[] sFormatStr = new char[HUNDRED_DAY_FIELD_LEN+10];
    private static char[] sTmpFormatStr = new char[HUNDRED_DAY_FIELD_LEN+10];

    static private int accumField(int amt, int suffix, boolean always, int zeropad) {
        if (amt > 999) {
            int num = 0;
            while (amt != 0) {
                num++;
                amt /= 10;
            }
            return num + suffix;
        } else {
            if (amt > 99 || (always && zeropad >= 3)) {
                return 3+suffix;
            }
            if (amt > 9 || (always && zeropad >= 2)) {
                return 2+suffix;
            }
            if (always || amt > 0) {
                return 1+suffix;
            }
        }
        return 0;
    }

    static private int printFieldLocked(char[] formatStr, int amt, char suffix, int pos,
            boolean always, int zeropad) {
        if (always || amt > 0) {
            final int startPos = pos;
            if (amt > 999) {
                int tmp = 0;
                while (amt != 0 && tmp < sTmpFormatStr.length) {
                    int dig = amt % 10;
                    sTmpFormatStr[tmp] = (char)(dig + '0');
                    tmp++;
                    amt /= 10;
                }
                tmp--;
                while (tmp >= 0) {
                    formatStr[pos] = sTmpFormatStr[tmp];
                    pos++;
                    tmp--;
                }
            } else {
                if ((always && zeropad >= 3) || amt > 99) {
                    int dig = amt/100;
                    formatStr[pos] = (char)(dig + '0');
                    pos++;
                    amt -= (dig*100);
                }
                if ((always && zeropad >= 2) || amt > 9 || startPos != pos) {
                    int dig = amt/10;
                    formatStr[pos] = (char)(dig + '0');
                    pos++;
                    amt -= (dig*10);
                }
                formatStr[pos] = (char)(amt + '0');
                pos++;
            }
            formatStr[pos] = suffix;
            pos++;
        }
        return pos;
    }

    private static int formatDurationLocked(long duration, int fieldLen) {
        if (sFormatStr.length < fieldLen) {
            sFormatStr = new char[fieldLen];
        }

        char[] formatStr = sFormatStr;

        if (duration == 0) {
            int pos = 0;
            fieldLen -= 1;
            while (pos < fieldLen) {
                formatStr[pos++] = ' ';
            }
            formatStr[pos] = '0';
            return pos+1;
        }

        char prefix;
        if (duration > 0) {
            prefix = '+';
        } else {
            prefix = '-';
            duration = -duration;
        }

        int millis = (int)(duration%1000);
        int seconds = (int) Math.floor(duration / 1000);
        int days = 0, hours = 0, minutes = 0;

        if (seconds >= SECONDS_PER_DAY) {
            days = seconds / SECONDS_PER_DAY;
            seconds -= days * SECONDS_PER_DAY;
        }
        if (seconds >= SECONDS_PER_HOUR) {
            hours = seconds / SECONDS_PER_HOUR;
            seconds -= hours * SECONDS_PER_HOUR;
        }
        if (seconds >= SECONDS_PER_MINUTE) {
            minutes = seconds / SECONDS_PER_MINUTE;
            seconds -= minutes * SECONDS_PER_MINUTE;
        }

        int pos = 0;

        if (fieldLen != 0) {
            int myLen = accumField(days, 1, false, 0);
            myLen += accumField(hours, 1, myLen > 0, 2);
            myLen += accumField(minutes, 1, myLen > 0, 2);
            myLen += accumField(seconds, 1, myLen > 0, 2);
            myLen += accumField(millis, 2, true, myLen > 0 ? 3 : 0) + 1;
            while (myLen < fieldLen) {
                formatStr[pos] = ' ';
                pos++;
                myLen++;
            }
        }

        formatStr[pos] = prefix;
        pos++;

        int start = pos;
        boolean zeropad = fieldLen != 0;
        pos = printFieldLocked(formatStr, days, 'd', pos, false, 0);
        pos = printFieldLocked(formatStr, hours, 'h', pos, pos != start, zeropad ? 2 : 0);
        pos = printFieldLocked(formatStr, minutes, 'm', pos, pos != start, zeropad ? 2 : 0);
        pos = printFieldLocked(formatStr, seconds, 's', pos, pos != start, zeropad ? 2 : 0);
        pos = printFieldLocked(formatStr, millis, 'm', pos, true, (zeropad && pos != start) ? 3 : 0);
        formatStr[pos] = 's';
        return pos + 1;
    }

    /** @hide Just for debugging; not internationalized. */
    public static void formatDuration(long duration, StringBuilder builder) {
        synchronized (sFormatSync) {
            int len = formatDurationLocked(duration, 0);
            builder.append(sFormatStr, 0, len);
        }
    }

    /** @hide Just for debugging; not internationalized. */
    public static void formatDuration(long duration, PrintWriter pw, int fieldLen) {
        synchronized (sFormatSync) {
            int len = formatDurationLocked(duration, fieldLen);
            pw.print(new String(sFormatStr, 0, len));
        }
    }

    /** @hide Just for debugging; not internationalized. */
    public static void formatDuration(long duration, PrintWriter pw) {
        formatDuration(duration, pw, 0);
    }

    /** @hide Just for debugging; not internationalized. */
    public static void formatDuration(long time, long now, PrintWriter pw) {
        if (time == 0) {
            pw.print("--");
            return;
        }
        formatDuration(time-now, pw, 0);
    }

    /** @hide Just for debugging; not internationalized. */
    public static String formatUptime(long time) {
        final long diff = time - SystemClock.uptimeMillis();
        if (diff > 0) {
            return time + " (in " + diff + " ms)";
        }
        if (diff < 0) {
            return time + " (" + -diff + " ms ago)";
        }
        return time + " (now)";
    }

    /**
     * Convert a System.currentTimeMillis() value to a time of day value like
     * that printed in logs. MM-DD HH:MM:SS.MMM
     *
     * @param millis since the epoch (1/1/1970)
     * @return String representation of the time.
     * @hide
     */
    public static String logTimeOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        if (millis >= 0) {
            c.setTimeInMillis(millis);
            return String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c);
        } else {
            return Long.toString(millis);
        }
    }

    /** {@hide} */
    public static String formatForLogging(long millis) {
        if (millis <= 0) {
            return "unknown";
        } else {
            return sLoggingFormat.format(new Date(millis));
        }
    }
}
