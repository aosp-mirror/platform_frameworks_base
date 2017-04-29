/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.six.headers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.android.systemui.R;
import com.android.internal.util.six.SixUtils;

public class DaylightHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "DaylightHeaderProvider";
    private static final boolean DEBUG = false;

    private class DaylightHeaderInfo {
        public int mType = 0;
        public int mHour = -1;
        public int mDay = -1;
        public int mMonth = -1;
        public String mImage;
    }
    // default in SystemUI
    private static final String HEADER_PACKAGE_DEFAULT = "com.android.systemui";

    private Context mContext;
    private List<DaylightHeaderInfo> mHeadersList;
    private Resources mRes;
    private String mPackageName;
    private String mHeaderName;
    private String mSettingHeaderPackage;
    private PendingIntent mAlarmIntent;
    private boolean mRandomMode;
    private int mHeaderIndex;
    private boolean mLinearMode;
    private int mAlarmIntervalMinutes = 0;

    public DaylightHeaderProvider(Context context) {
        mContext = context;
    }

    @Override
    public String getName() {
        return "daylight";
    }

    @Override
    public void settingsChanged() {
        final String settingHeaderPackage = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK,
                UserHandle.USER_CURRENT);
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;

        if (customHeader) {
            stopAlarm();
            if (settingHeaderPackage == null) {
                loadDefaultHeaderPackage();
            } else if (mSettingHeaderPackage == null || !settingHeaderPackage.equals(mSettingHeaderPackage)) {
                mSettingHeaderPackage = settingHeaderPackage;
                loadCustomHeaderPackage();
            }
            startAlarm();
        }
    }

    @Override
    public void enableProvider() {
        settingsChanged();
    }

    @Override
    public void disableProvider() {
        stopAlarm();
   }

    private void stopAlarm() {
        if (mAlarmIntent != null) {
            final AlarmManager alarmManager = (AlarmManager) mContext
                    .getSystemService(Context.ALARM_SERVICE);
            if (DEBUG) Log.i(TAG, "stop alarm");
            alarmManager.cancel(mAlarmIntent);
        }
        mAlarmIntent = null;
    }

    private void startAlarm() {
        // TODO actually this should find out the next needed alarm
        // instead of forcing it every interval
        final Calendar c = Calendar.getInstance();
        final AlarmManager alarmManager = (AlarmManager) mContext
                .getSystemService(Context.ALARM_SERVICE);

        if (mAlarmIntent != null) {
            alarmManager.cancel(mAlarmIntent);
        }
        Intent intent = new Intent(StatusBarHeaderMachine.STATUS_BAR_HEADER_UPDATE_ACTION);
        mAlarmIntent = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        // make sure hourly alarm is aligned with hour
        if (mAlarmIntervalMinutes == 0) {
            c.add(Calendar.HOUR_OF_DAY, 1);
            c.set(Calendar.MINUTE, 0);
        }
        long alarmStart = c.getTimeInMillis();
        long interval = mAlarmIntervalMinutes == 0 ? AlarmManager.INTERVAL_HOUR : mAlarmIntervalMinutes * 60 * 1000;
        if (DEBUG) Log.i(TAG, "start alarm at " + new Date(alarmStart) + " with interval of " + interval);
        alarmManager.setInexactRepeating(AlarmManager.RTC, alarmStart, interval, mAlarmIntent);
    }

    private void loadCustomHeaderPackage() {
        int idx = mSettingHeaderPackage.indexOf("/");
        if (idx != -1) {
            String[] parts = mSettingHeaderPackage.split("/");
            mPackageName = parts[0];
            mHeaderName = parts[1];
        } else {
            mPackageName = mSettingHeaderPackage;
            mHeaderName = null;
        }
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
            loadHeaders();
        } catch (Exception e) {
            mRes = null;
        }
        if (mRes == null) {
            loadDefaultHeaderPackage();
        }
    }

    private void loadDefaultHeaderPackage() {
        mPackageName = HEADER_PACKAGE_DEFAULT;
        mHeaderName = null;
        mSettingHeaderPackage = mPackageName;
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
            loadHeaders();
        } catch (Exception e) {
            mRes = null;
        }
        if (mRes == null) {
        }
    }

    private void loadHeaders() throws XmlPullParserException, IOException {
        mHeadersList = new ArrayList<DaylightHeaderInfo>();
        InputStream in = null;
        XmlPullParser parser = null;

        try {
            if (mHeaderName == null) {
                in = mRes.getAssets().open("daylight_header.xml");
            } else {
                int idx = mHeaderName.lastIndexOf(".");
                String headerConfigFile = mHeaderName.substring(idx + 1) + ".xml";
                in = mRes.getAssets().open(headerConfigFile);
            }
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            parser = factory.newPullParser();
            parser.setInput(in, "UTF-8");
            loadResourcesFromXmlParser(parser);
        } finally {
            // Cleanup resources
            if (parser instanceof XmlResourceParser) {
                ((XmlResourceParser) parser).close();
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void loadResourcesFromXmlParser(XmlPullParser parser) throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        mRandomMode = false;
        mLinearMode = false;
        mAlarmIntervalMinutes = 0;
        do {
            if (eventType != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();
            // TODO support different hours for day headers
            if (name.equalsIgnoreCase("day_header")) {
                if (mRandomMode) {
                    continue;
                }
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mHour = -1;
                headerInfo.mType = 0;
                String day = parser.getAttributeValue(null, "day");
                if (day != null) {
                    headerInfo.mDay = Integer.valueOf(day);
                }
                String month = parser.getAttributeValue(null, "month");
                if (month != null) {
                    headerInfo.mMonth = Integer.valueOf(month);
                }
                String hour = parser.getAttributeValue(null, "hour");
                if (hour != null) {
                    headerInfo.mHour = Integer.valueOf(hour);
                }
                String image = parser.getAttributeValue(null, "image");
                if (image != null) {
                    headerInfo.mImage = image;
                }
                if (headerInfo.mImage != null && headerInfo.mDay != -1 && headerInfo.mMonth != -1) {
                    mHeadersList.add(headerInfo);
                }
            } else if (name.equalsIgnoreCase("hour_header")) {
                if (mRandomMode) {
                    continue;
                }
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mType = 1;
                String hour = parser.getAttributeValue(null, "hour");
                if (hour != null) {
                    headerInfo.mHour = Integer.valueOf(hour);
                }
                String image = parser.getAttributeValue(null, "image");
                if (image != null) {
                    headerInfo.mImage = image;
                }
                if (headerInfo.mImage != null && headerInfo.mHour != -1) {
                    mHeadersList.add(headerInfo);
                }
            } else if (name.equalsIgnoreCase("random_header") ||
                    name.equalsIgnoreCase("list_header")) {
                mRandomMode = name.equalsIgnoreCase("random_header");
                mLinearMode = name.equalsIgnoreCase("list_header");
                if (mRandomMode) {
                    if (DEBUG) Log.i(TAG, "Load random mode header pack");
                }
                if (mLinearMode) {
                    if (DEBUG) Log.i(TAG, "Load linear mode header pack");
                }
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mType = 2;
                String image = parser.getAttributeValue(null, "image");
                if (image != null) {
                    headerInfo.mImage = image;
                }
                if (headerInfo.mImage != null) {
                    mHeadersList.add(headerInfo);
                }
            } else if (name.equalsIgnoreCase("change_interval")) {
                String intervalMinutes = parser.getAttributeValue(null, "minutes");
                mAlarmIntervalMinutes = Integer.valueOf(intervalMinutes);
                if (DEBUG) Log.i(TAG, "set change interval to " + mAlarmIntervalMinutes);
            }
        } while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT);

        if (mRandomMode) {
            Collections.shuffle(mHeadersList);
        }
        if (!mLinearMode && !mRandomMode) {
            mAlarmIntervalMinutes = 0;
        }
    }

    /**
     * header with biggest hour
     */
    private DaylightHeaderInfo getLastHourHeader(List<DaylightHeaderInfo> headerList) {
        if (headerList == null || headerList.size() == 0) {
            return null;
        }
        Iterator<DaylightHeaderInfo> nextHeader = headerList.iterator();
        int hour = -1;
        DaylightHeaderInfo last = null;
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            if (header.mHour == -1) {
                continue;
            }
            if (last == null) {
                last = header;
                hour = last.mHour;
            } else if (header.mHour > hour) {
                last = header;
                hour = last.mHour;
            }
        }
        return last;
    }

    /**
     * header with lowest hour
     */
    private DaylightHeaderInfo getFirstHourHeader(List<DaylightHeaderInfo> headerList) {
        if (headerList == null || headerList.size() == 0) {
            return null;
        }
        Iterator<DaylightHeaderInfo> nextHeader = headerList.iterator();
        DaylightHeaderInfo first = null;
        int hour = -1;
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            if (header.mHour == -1) {
                continue;
            }
            if (first == null) {
                first = header;
                hour = first.mHour;
            } else if (header.mHour < hour) {
                first = header;
                hour = first.mHour;
            }
        }
        return first;
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
        if (!SixUtils.isAvailableApp(mPackageName, mContext)) {
            Log.w(TAG, "Header pack no longer available - loading default " + mPackageName);
            loadDefaultHeaderPackage();
        }
        if (mRes == null || mHeadersList == null || mHeadersList.size() == 0) {
            return null;
        }
        try {
            if (mRandomMode || mLinearMode) {
                DaylightHeaderInfo header = mHeadersList.get(mHeaderIndex);
                mHeaderIndex++;
                if (mHeaderIndex == mHeadersList.size()) {
                    if (mRandomMode) {
                        if (DEBUG) Log.i(TAG, "Shuffle random mode header pack");
                        Collections.shuffle(mHeadersList);
                    }
                    mHeaderIndex = 0;
                }
                if (DEBUG) Log.i(TAG, "Current header " + header.mImage);
                return mRes.getDrawable(mRes.getIdentifier(header.mImage, "drawable", mPackageName), null);
            }
            // first check day headers
            if (DEBUG) Log.i(TAG, "Check day headers");
            List<DaylightHeaderInfo> todayHeaders = getTodayHeaders(now);
            if (todayHeaders.size() != 0) {
                DaylightHeaderInfo first = getFirstHourHeader(todayHeaders);
                DaylightHeaderInfo last = getLastHourHeader(todayHeaders);
                // no day header with hour so just use one
                if (first == null || last == null) {
                    if (DEBUG) Log.i(TAG, "Current day header " + todayHeaders.get(0).mImage);
                    return mRes.getDrawable(mRes.getIdentifier(todayHeaders.get(0).mImage, "drawable", mPackageName), null);
                }
                DaylightHeaderInfo matching = getMatchingHeader(now, todayHeaders);
                if (DEBUG) Log.i(TAG, "Current day header " + matching.mImage);
                return mRes.getDrawable(mRes.getIdentifier(matching.mImage, "drawable", mPackageName), null);
            }
            if (DEBUG) Log.i(TAG, "Check hour headers");
            List<DaylightHeaderInfo> hourHeaders = getHourHeaders();
            if (hourHeaders.size() != 0) {
                DaylightHeaderInfo matching = getMatchingHeader(now, hourHeaders);
                if (DEBUG) Log.i(TAG, "Current hour header " + matching.mImage);
                return mRes.getDrawable(mRes.getIdentifier(matching.mImage, "drawable", mPackageName), null);
            }
        } catch(Resources.NotFoundException e) {
        }
        return null;
    }

    private boolean isItToday(final Calendar now, DaylightHeaderInfo headerInfo) {
        return now.get(Calendar.MONTH) +1 == headerInfo.mMonth && now
                    .get(Calendar.DAY_OF_MONTH) == headerInfo.mDay;
    }

    private List<DaylightHeaderInfo> getTodayHeaders(final Calendar now) {
        List<DaylightHeaderInfo> todayHeaders = new ArrayList<DaylightHeaderInfo>();
        Iterator<DaylightHeaderInfo> nextHeader = mHeadersList.iterator();
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            if (header.mType == 0) {
                if (isItToday(now, header)){
                    todayHeaders.add(header);
                }
            }
        }
        return todayHeaders;
    }

    private List<DaylightHeaderInfo> getHourHeaders() {
        List<DaylightHeaderInfo> hourHeaders = new ArrayList<DaylightHeaderInfo>();
        Iterator<DaylightHeaderInfo> nextHeader = mHeadersList.iterator();
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            if (header.mType == 1) {
                hourHeaders.add(header);
            }
        }
        return hourHeaders;
    }

    private DaylightHeaderInfo getMatchingHeader(final Calendar now, List<DaylightHeaderInfo> headerList) {
        DaylightHeaderInfo first = getFirstHourHeader(headerList);
        DaylightHeaderInfo last = getLastHourHeader(headerList);
        DaylightHeaderInfo prev = first;

        Iterator<DaylightHeaderInfo> nextHeader = headerList.iterator();
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            final int hour = now.get(Calendar.HOUR_OF_DAY);
            if (header.mHour > hour) {
                if (header == first) {
                    // if before first return last
                    return last;
                }
                // on the first bigger one return prev
                return prev;
            }
            prev = header;
        }
        return last;
    }
}
