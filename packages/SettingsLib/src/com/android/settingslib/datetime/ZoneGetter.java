/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.settingslib.datetime;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import com.android.settingslib.R;

import libcore.icu.TimeZoneNames;

import org.xmlpull.v1.XmlPullParserException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

public class ZoneGetter {
    private static final String TAG = "ZoneGetter";

    private static final String XMLTAG_TIMEZONE = "timezone";

    public static final String KEY_ID = "id";  // value: String
    public static final String KEY_DISPLAYNAME = "name";  // value: String
    public static final String KEY_GMT = "gmt";  // value: String
    public static final String KEY_OFFSET = "offset";  // value: int (Integer)

    private ZoneGetter() {}

    public static String getTimeZoneOffsetAndName(TimeZone tz, Date now) {
        Locale locale = Locale.getDefault();
        String gmtString = getGmtOffsetString(locale, tz, now);
        String zoneNameString = getZoneLongName(locale, tz, now);
        if (zoneNameString == null) {
            return gmtString;
        }

        // We don't use punctuation here to avoid having to worry about localizing that too!
        return gmtString + " " + zoneNameString;
    }

    public static List<Map<String, Object>> getZonesList(Context context) {
        final Locale locale = Locale.getDefault();
        final Date now = new Date();

        // The display name chosen for each zone entry depends on whether the zone is one associated
        // with the country of the user's chosen locale. For "local" zones we prefer the "long name"
        // (e.g. "Europe/London" -> "British Summer Time" for people in the UK). For "non-local"
        // zones we prefer the exemplar location (e.g. "Europe/London" -> "London" for English
        // speakers from outside the UK). This heuristic is based on the fact that people are
        // typically familiar with their local timezones and exemplar locations don't always match
        // modern-day expectations for people living in the country covered. Large countries like
        // China that mostly use a single timezone (olson id: "Asia/Shanghai") may not live near
        // "Shanghai" and prefer the long name over the exemplar location. The only time we don't
        // follow this policy for local zones is when Android supplies multiple olson IDs to choose
        // from and the use of a zone's long name leads to ambiguity. For example, at the time of
        // writing Android lists 5 olson ids for Australia which collapse to 2 different zone names
        // in winter but 4 different zone names in summer. The ambiguity leads to the users
        // selecting the wrong olson ids.

        // Get the list of olson ids to display to the user.
        List<String> olsonIdsToDisplay = readTimezonesToDisplay(context);

        // Create a lookup of local zone IDs.
        Set<String> localZoneIds = new TreeSet<String>();
        for (String olsonId : TimeZoneNames.forLocale(locale)) {
            localZoneIds.add(olsonId);
        }

        // Work out whether the long names for the local entries that we would show by default would
        // be ambiguous.
        Set<String> localZoneNames = new TreeSet<String>();
        boolean localLongNamesAreAmbiguous = false;
        for (String olsonId : olsonIdsToDisplay) {
            if (localZoneIds.contains(olsonId)) {
                TimeZone tz = TimeZone.getTimeZone(olsonId);
                String zoneLongName = getZoneLongName(locale, tz, now);
                boolean longNameIsUnique = localZoneNames.add(zoneLongName);
                if (!longNameIsUnique) {
                    localLongNamesAreAmbiguous = true;
                    break;
                }
            }
        }

        // Generate the list of zone entries to return.
        List<Map<String, Object>> zones = new ArrayList<Map<String, Object>>();
        for (String olsonId : olsonIdsToDisplay) {
            final TimeZone tz = TimeZone.getTimeZone(olsonId);
            // Exemplar location display is the default. The only time we intend to display the long
            // name is when the olsonId is local AND long names are not ambiguous.
            boolean isLocalZoneId = localZoneIds.contains(olsonId);
            boolean preferLongName = isLocalZoneId && !localLongNamesAreAmbiguous;
            String displayName = getZoneDisplayName(locale, tz, now, preferLongName);

            String gmtOffsetString = getGmtOffsetString(locale, tz, now);
            int offsetMillis = tz.getOffset(now.getTime());
            Map<String, Object> displayEntry =
                    createDisplayEntry(tz, gmtOffsetString, displayName, offsetMillis);
            zones.add(displayEntry);
        }
        return zones;
    }

    private static Map<String, Object> createDisplayEntry(
            TimeZone tz, String gmtOffsetString, String displayName, int offsetMillis) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(KEY_ID, tz.getID());
        map.put(KEY_DISPLAYNAME, displayName);
        map.put(KEY_GMT, gmtOffsetString);
        map.put(KEY_OFFSET, offsetMillis);
        return map;
    }

    /**
     * Returns a name for the specific zone. If {@code preferLongName} is {@code true} then the
     * long display name for the timezone will be used, otherwise the exemplar location will be
     * preferred.
     */
    private static String getZoneDisplayName(Locale locale, TimeZone tz, Date now,
            boolean preferLongName) {
        String zoneNameString;
        if (preferLongName) {
            zoneNameString = getZoneLongName(locale, tz, now);
        } else {
            zoneNameString = getZoneExemplarLocation(locale, tz);
            if (zoneNameString == null || zoneNameString.isEmpty()) {
                // getZoneExemplarLocation can return null.
                zoneNameString = getZoneLongName(locale, tz, now);
            }
        }
        return zoneNameString;
    }

    private static String getZoneExemplarLocation(Locale locale, TimeZone tz) {
        return TimeZoneNames.getExemplarLocation(locale.toString(), tz.getID());
    }

    private static List<String> readTimezonesToDisplay(Context context) {
        List<String> olsonIds = new ArrayList<String>();
        try (XmlResourceParser xrp = context.getResources().getXml(R.xml.timezones)) {
            while (xrp.next() != XmlResourceParser.START_TAG) {
                continue;
            }
            xrp.next();
            while (xrp.getEventType() != XmlResourceParser.END_TAG) {
                while (xrp.getEventType() != XmlResourceParser.START_TAG) {
                    if (xrp.getEventType() == XmlResourceParser.END_DOCUMENT) {
                        return olsonIds;
                    }
                    xrp.next();
                }
                if (xrp.getName().equals(XMLTAG_TIMEZONE)) {
                    String olsonId = xrp.getAttributeValue(0);
                    olsonIds.add(olsonId);
                }
                while (xrp.getEventType() != XmlResourceParser.END_TAG) {
                    xrp.next();
                }
                xrp.next();
            }
        } catch (XmlPullParserException xppe) {
            Log.e(TAG, "Ill-formatted timezones.xml file");
        } catch (java.io.IOException ioe) {
            Log.e(TAG, "Unable to read timezones.xml file");
        }
        return olsonIds;
    }

    private static String getZoneLongName(Locale locale, TimeZone tz, Date now) {
        boolean daylight = tz.inDaylightTime(now);
        // This returns a name if it can, or will fall back to GMT+0X:00 format.
        return tz.getDisplayName(daylight, TimeZone.LONG, locale);
    }

    private static String getGmtOffsetString(Locale locale, TimeZone tz, Date now) {
        // Use SimpleDateFormat to format the GMT+00:00 string.
        SimpleDateFormat gmtFormatter = new SimpleDateFormat("ZZZZ");
        gmtFormatter.setTimeZone(tz);
        String gmtString = gmtFormatter.format(now);

        // Ensure that the "GMT+" stays with the "00:00" even if the digits are RTL.
        BidiFormatter bidiFormatter = BidiFormatter.getInstance();
        boolean isRtl = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL;
        gmtString = bidiFormatter.unicodeWrap(gmtString,
                isRtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR);
        return gmtString;
    }
}
