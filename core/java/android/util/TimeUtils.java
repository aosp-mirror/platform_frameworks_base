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

import org.apache.harmony.luni.internal.util.ZoneInfoDB;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.TimeZone;
import java.util.Date;

import com.android.internal.util.XmlUtils;

/**
 * A class containing utility methods related to time zones.
 */
public class TimeUtils {
    private static final String TAG = "TimeUtils";

    /**
     * Tries to return a time zone that would have had the specified offset
     * and DST value at the specified moment in the specified country.
     * Returns null if no suitable zone could be found.
     */
    public static TimeZone getTimeZone(int offset, boolean dst, long when, String country) {
        if (country == null) {
            return null;
        }

        TimeZone best = null;

        Resources r = Resources.getSystem();
        XmlResourceParser parser = r.getXml(com.android.internal.R.xml.time_zones_by_country);
        Date d = new Date(when);

        TimeZone current = TimeZone.getDefault();
        String currentName = current.getID();
        int currentOffset = current.getOffset(when);
        boolean currentDst = current.inDaylightTime(d);

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
                        String maybe = parser.getText();

                        // If the current time zone is from the right country
                        // and meets the other known properties, keep it
                        // instead of changing to another one.

                        if (maybe.equals(currentName)) {
                            if (currentOffset == offset && currentDst == dst) {
                                return current;
                            }
                        }

                        // Otherwise, take the first zone from the right
                        // country that has the correct current offset and DST.
                        // (Keep iterating instead of returning in case we
                        // haven't encountered the current time zone yet.)

                        if (best == null) {
                            TimeZone tz = TimeZone.getTimeZone(maybe);

                            if (tz.getOffset(when) == offset &&
                                tz.inDaylightTime(d) == dst) {
                                best = tz;
                            }
                        }
                    }
                }
            }
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Got exception while getting preferred time zone.", e);
        } catch (IOException e) {
            Log.e(TAG, "Got exception while getting preferred time zone.", e);
        } finally {
            parser.close();
        }

        return best;
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
}
