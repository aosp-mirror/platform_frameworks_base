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

import android.content.res.Resources;
import android.content.res.XmlResourceParser;

import libcore.util.ZoneInfoDB;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.TimeZone;
import java.util.Date;

import com.android.internal.util.XmlUtils;

/**
 * A class containing utility methods related to time zones.
 */
public class TimeUtils {
    /** @hide */ public TimeUtils() {}
    private static final boolean DBG = false;
    private static final String TAG = "TimeUtils";

    /** Cached results of getTineZones */
    private static final Object sLastLockObj = new Object();
    private static ArrayList<TimeZone> sLastZones = null;
    private static String sLastCountry = null;

    /** Cached results of getTimeZonesWithUniqueOffsets */
    private static final Object sLastUniqueLockObj = new Object();
    private static ArrayList<TimeZone> sLastUniqueZoneOffsets = null;
    private static String sLastUniqueCountry = null;


    /**
     * Tries to return a time zone that would have had the specified offset
     * and DST value at the specified moment in the specified country.
     * Returns null if no suitable zone could be found.
     */
    public static TimeZone getTimeZone(int offset, boolean dst, long when, String country) {
        TimeZone best = null;

        Resources r = Resources.getSystem();
        XmlResourceParser parser = r.getXml(com.android.internal.R.xml.time_zones_by_country);
        Date d = new Date(when);

        TimeZone current = TimeZone.getDefault();
        String currentName = current.getID();
        int currentOffset = current.getOffset(when);
        boolean currentDst = current.inDaylightTime(d);

        for (TimeZone tz : getTimeZones(country)) {
            // If the current time zone is from the right country
            // and meets the other known properties, keep it
            // instead of changing to another one.

            if (tz.getID().equals(currentName)) {
                if (currentOffset == offset && currentDst == dst) {
                    return current;
                }
            }

            // Otherwise, take the first zone from the right
            // country that has the correct current offset and DST.
            // (Keep iterating instead of returning in case we
            // haven't encountered the current time zone yet.)

            if (best == null) {
                if (tz.getOffset(when) == offset &&
                    tz.inDaylightTime(d) == dst) {
                    best = tz;
                }
            }
        }

        return best;
    }

    /**
     * Return list of unique time zones for the country. Do not modify
     *
     * @param country to find
     * @return list of unique time zones, maybe empty but never null. Do not modify.
     * @hide
     */
    public static ArrayList<TimeZone> getTimeZonesWithUniqueOffsets(String country) {
        synchronized(sLastUniqueLockObj) {
            if ((country != null) && country.equals(sLastUniqueCountry)) {
                if (DBG) {
                    Log.d(TAG, "getTimeZonesWithUniqueOffsets(" +
                            country + "): return cached version");
                }
                return sLastUniqueZoneOffsets;
            }
        }

        Collection<TimeZone> zones = getTimeZones(country);
        ArrayList<TimeZone> uniqueTimeZones = new ArrayList<TimeZone>();
        for (TimeZone zone : zones) {
            // See if we already have this offset,
            // Using slow but space efficient and these are small.
            boolean found = false;
            for (int i = 0; i < uniqueTimeZones.size(); i++) {
                if (uniqueTimeZones.get(i).getRawOffset() == zone.getRawOffset()) {
                    found = true;
                    break;
                }
            }
            if (found == false) {
                if (DBG) {
                    Log.d(TAG, "getTimeZonesWithUniqueOffsets: add unique offset=" +
                            zone.getRawOffset() + " zone.getID=" + zone.getID());
                }
                uniqueTimeZones.add(zone);
            }
        }

        synchronized(sLastUniqueLockObj) {
            // Cache the last result
            sLastUniqueZoneOffsets = uniqueTimeZones;
            sLastUniqueCountry = country;

            return sLastUniqueZoneOffsets;
        }
    }

    /**
     * Returns the time zones for the country, which is the code
     * attribute of the timezone element in time_zones_by_country.xml. Do not modify.
     *
     * @param country is a two character country code.
     * @return TimeZone list, maybe empty but never null. Do not modify.
     * @hide
     */
    public static ArrayList<TimeZone> getTimeZones(String country) {
        synchronized (sLastLockObj) {
            if ((country != null) && country.equals(sLastCountry)) {
                if (DBG) Log.d(TAG, "getTimeZones(" + country + "): return cached version");
                return sLastZones;
            }
        }

        ArrayList<TimeZone> tzs = new ArrayList<TimeZone>();

        if (country == null) {
            if (DBG) Log.d(TAG, "getTimeZones(null): return empty list");
            return tzs;
        }

        Resources r = Resources.getSystem();
        XmlResourceParser parser = r.getXml(com.android.internal.R.xml.time_zones_by_country);

        try {
            XmlUtils.beginDocument(parser, "timezones");

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null || !(element.equals("timezone"))) {
                    break;
                }

                String code = parser.getAttributeValue(null, "code");

                if (country.equals(code)) {
                    if (parser.next() == XmlPullParser.TEXT) {
                        String zoneIdString = parser.getText();
                        TimeZone tz = TimeZone.getTimeZone(zoneIdString);
                        if (tz.getID().startsWith("GMT") == false) {
                            // tz.getID doesn't start not "GMT" so its valid
                            tzs.add(tz);
                            if (DBG) {
                                Log.d(TAG, "getTimeZone('" + country + "'): found tz.getID=="
                                    + ((tz != null) ? tz.getID() : "<no tz>"));
                            }
                        }
                    }
                }
            }
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Got xml parser exception getTimeZone('" + country + "'): e=", e);
        } catch (IOException e) {
            Log.e(TAG, "Got IO exception getTimeZone('" + country + "'): e=", e);
        } finally {
            parser.close();
        }

        synchronized(sLastLockObj) {
            // Cache the last result;
            sLastZones = tzs;
            sLastCountry = country;
            return sLastZones;
        }
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
        return ZoneInfoDB.getVersion();
    }

    /** @hide Field length that can hold 999 days of time */
    public static final int HUNDRED_DAY_FIELD_LEN = 19;

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 60 * 60;
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    private static final Object sFormatSync = new Object();
    private static char[] sFormatStr = new char[HUNDRED_DAY_FIELD_LEN+5];

    static private int accumField(int amt, int suffix, boolean always, int zeropad) {
        if (amt > 99 || (always && zeropad >= 3)) {
            return 3+suffix;
        }
        if (amt > 9 || (always && zeropad >= 2)) {
            return 2+suffix;
        }
        if (always || amt > 0) {
            return 1+suffix;
        }
        return 0;
    }

    static private int printField(char[] formatStr, int amt, char suffix, int pos,
            boolean always, int zeropad) {
        if (always || amt > 0) {
            final int startPos = pos;
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

        if (seconds > SECONDS_PER_DAY) {
            days = seconds / SECONDS_PER_DAY;
            seconds -= days * SECONDS_PER_DAY;
        }
        if (seconds > SECONDS_PER_HOUR) {
            hours = seconds / SECONDS_PER_HOUR;
            seconds -= hours * SECONDS_PER_HOUR;
        }
        if (seconds > SECONDS_PER_MINUTE) {
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
        pos = printField(formatStr, days, 'd', pos, false, 0);
        pos = printField(formatStr, hours, 'h', pos, pos != start, zeropad ? 2 : 0);
        pos = printField(formatStr, minutes, 'm', pos, pos != start, zeropad ? 2 : 0);
        pos = printField(formatStr, seconds, 's', pos, pos != start, zeropad ? 2 : 0);
        pos = printField(formatStr, millis, 'm', pos, true, (zeropad && pos != start) ? 3 : 0);
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
}
