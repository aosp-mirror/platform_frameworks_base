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
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ZoneGetter {
    private static final String TAG = "ZoneGetter";

    private static final String XMLTAG_TIMEZONE = "timezone";

    public static final String KEY_ID = "id";  // value: String
    public static final String KEY_DISPLAYNAME = "name";  // value: String
    public static final String KEY_GMT = "gmt";  // value: String
    public static final String KEY_OFFSET = "offset";  // value: int (Integer)

    private final List<HashMap<String, Object>> mZones = new ArrayList<>();
    private final HashSet<String> mLocalZones = new HashSet<>();
    private final Date mNow = Calendar.getInstance().getTime();
    private final SimpleDateFormat mZoneNameFormatter = new SimpleDateFormat("zzzz");

    public List<HashMap<String, Object>> getZones(Context context) {
        for (String olsonId : TimeZoneNames.forLocale(Locale.getDefault())) {
            mLocalZones.add(olsonId);
        }
        try {
            XmlResourceParser xrp = context.getResources().getXml(R.xml.timezones);
            while (xrp.next() != XmlResourceParser.START_TAG) {
                continue;
            }
            xrp.next();
            while (xrp.getEventType() != XmlResourceParser.END_TAG) {
                while (xrp.getEventType() != XmlResourceParser.START_TAG) {
                    if (xrp.getEventType() == XmlResourceParser.END_DOCUMENT) {
                        return mZones;
                    }
                    xrp.next();
                }
                if (xrp.getName().equals(XMLTAG_TIMEZONE)) {
                    String olsonId = xrp.getAttributeValue(0);
                    addTimeZone(olsonId);
                }
                while (xrp.getEventType() != XmlResourceParser.END_TAG) {
                    xrp.next();
                }
                xrp.next();
            }
            xrp.close();
        } catch (XmlPullParserException xppe) {
            Log.e(TAG, "Ill-formatted timezones.xml file");
        } catch (java.io.IOException ioe) {
            Log.e(TAG, "Unable to read timezones.xml file");
        }
        return mZones;
    }

    private void addTimeZone(String olsonId) {
        // We always need the "GMT-07:00" string.
        final TimeZone tz = TimeZone.getTimeZone(olsonId);

        // For the display name, we treat time zones within the country differently
        // from other countries' time zones. So in en_US you'd get "Pacific Daylight Time"
        // but in de_DE you'd get "Los Angeles" for the same time zone.
        String displayName;
        if (mLocalZones.contains(olsonId)) {
            // Within a country, we just use the local name for the time zone.
            mZoneNameFormatter.setTimeZone(tz);
            displayName = mZoneNameFormatter.format(mNow);
        } else {
            // For other countries' time zones, we use the exemplar location.
            final String localeName = Locale.getDefault().toString();
            displayName = TimeZoneNames.getExemplarLocation(localeName, olsonId);
        }

        final HashMap<String, Object> map = new HashMap<>();
        map.put(KEY_ID, olsonId);
        map.put(KEY_DISPLAYNAME, displayName);
        map.put(KEY_GMT, getTimeZoneText(tz, false));
        map.put(KEY_OFFSET, tz.getOffset(mNow.getTime()));

        mZones.add(map);
    }

    public static String getTimeZoneText(TimeZone tz, boolean includeName) {
        Date now = new Date();

        // Use SimpleDateFormat to format the GMT+00:00 string.
        SimpleDateFormat gmtFormatter = new SimpleDateFormat("ZZZZ");
        gmtFormatter.setTimeZone(tz);
        String gmtString = gmtFormatter.format(now);

        // Ensure that the "GMT+" stays with the "00:00" even if the digits are RTL.
        BidiFormatter bidiFormatter = BidiFormatter.getInstance();
        Locale l = Locale.getDefault();
        boolean isRtl = TextUtils.getLayoutDirectionFromLocale(l) == View.LAYOUT_DIRECTION_RTL;
        gmtString = bidiFormatter.unicodeWrap(gmtString,
                isRtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR);

        if (!includeName) {
            return gmtString;
        }

        // Optionally append the time zone name.
        SimpleDateFormat zoneNameFormatter = new SimpleDateFormat("zzzz");
        zoneNameFormatter.setTimeZone(tz);
        String zoneNameString = zoneNameFormatter.format(now);

        // We don't use punctuation here to avoid having to worry about localizing that too!
        return gmtString + " " + zoneNameString;
    }
}
