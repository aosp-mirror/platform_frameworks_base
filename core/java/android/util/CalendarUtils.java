/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.Calendar.CalendarCache;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;

import java.util.Formatter;
import java.util.HashSet;
import java.util.Locale;

/**
 * A class containing utility methods related to Calendar apps.
 *
 * @hide
 */
public class CalendarUtils {
    private static final boolean DEBUG = false;
    private static final String TAG = "CalendarUtils";

    /**
     * This class contains methods specific to reading and writing time zone
     * values.
     */
    public static class TimeZoneUtils {
        private static final String[] TIMEZONE_TYPE_ARGS = { CalendarCache.TIMEZONE_KEY_TYPE };
        private static final String[] TIMEZONE_INSTANCES_ARGS =
                { CalendarCache.TIMEZONE_KEY_INSTANCES };

        private static StringBuilder mSB = new StringBuilder(50);
        private static Formatter mF = new Formatter(mSB, Locale.getDefault());
        private volatile static boolean mFirstTZRequest = true;
        private volatile static boolean mTZQueryInProgress = false;

        private volatile static boolean mUseHomeTZ = false;
        private volatile static String mHomeTZ = Time.getCurrentTimezone();

        private static HashSet<Runnable> mTZCallbacks = new HashSet<Runnable>();
        private static int mToken = 1;
        private static AsyncTZHandler mHandler;

        // The name of the shared preferences file. This name must be maintained for historical
        // reasons, as it's what PreferenceManager assigned the first time the file was created.
        private final String mPrefsName;

        /**
         * This is the key used for writing whether or not a home time zone should
         * be used in the Calendar app to the Calendar Preferences.
         */
        public static final String KEY_HOME_TZ_ENABLED = "preferences_home_tz_enabled";
        /**
         * This is the key used for writing the time zone that should be used if
         * home time zones are enabled for the Calendar app.
         */
        public static final String KEY_HOME_TZ = "preferences_home_tz";

        /**
         * This is a helper class for handling the async queries and updates for the
         * time zone settings in Calendar.
         */
        private class AsyncTZHandler extends AsyncQueryHandler {
            public AsyncTZHandler(ContentResolver cr) {
                super(cr);
            }

            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                synchronized (mTZCallbacks) {
                    boolean writePrefs = false;
                    // Check the values in the db
                    int keyColumn = cursor.getColumnIndexOrThrow(CalendarCache.KEY);
                    int valueColumn = cursor.getColumnIndexOrThrow(CalendarCache.VALUE);
                    while(cursor.moveToNext()) {
                        String key = cursor.getString(keyColumn);
                        String value = cursor.getString(valueColumn);
                        if (TextUtils.equals(key, CalendarCache.TIMEZONE_KEY_TYPE)) {
                            boolean useHomeTZ = !TextUtils.equals(
                                    value, CalendarCache.TIMEZONE_TYPE_AUTO);
                            if (useHomeTZ != mUseHomeTZ) {
                                writePrefs = true;
                                mUseHomeTZ = useHomeTZ;
                            }
                        } else if (TextUtils.equals(
                                key, CalendarCache.TIMEZONE_KEY_INSTANCES_PREVIOUS)) {
                            if (!TextUtils.isEmpty(value) && !TextUtils.equals(mHomeTZ, value)) {
                                writePrefs = true;
                                mHomeTZ = value;
                            }
                        }
                    }
                    cursor.close();
                    if (writePrefs) {
                        SharedPreferences prefs = getSharedPreferences((Context)cookie, mPrefsName);
                        // Write the prefs
                        setSharedPreference(prefs, KEY_HOME_TZ_ENABLED, mUseHomeTZ);
                        setSharedPreference(prefs, KEY_HOME_TZ, mHomeTZ);
                    }

                    mTZQueryInProgress = false;
                    for (Runnable callback : mTZCallbacks) {
                        if (callback != null) {
                            callback.run();
                        }
                    }
                    mTZCallbacks.clear();
                }
            }
        }

        /**
         * The name of the file where the shared prefs for Calendar are stored
         * must be provided. All activities within an app should provide the
         * same preferences name or behavior may become erratic.
         *
         * @param prefsName
         */
        public TimeZoneUtils(String prefsName) {
            mPrefsName = prefsName;
        }

        /**
         * Formats a date or a time range according to the local conventions.
         *
         * This formats a date/time range using Calendar's time zone and the
         * local conventions for the region of the device.
         *
         * If the {@link DateUtils#FORMAT_UTC} flag is used it will pass in
         * the UTC time zone instead.
         *
         * @param context the context is required only if the time is shown
         * @param startMillis the start time in UTC milliseconds
         * @param endMillis the end time in UTC milliseconds
         * @param flags a bit mask of options See
         * {@link DateUtils#formatDateRange(Context, Formatter, long, long, int, String) formatDateRange}
         * @return a string containing the formatted date/time range.
         */
        public String formatDateRange(Context context, long startMillis,
                long endMillis, int flags) {
            String date;
            String tz;
            if ((flags & DateUtils.FORMAT_UTC) != 0) {
                tz = Time.TIMEZONE_UTC;
            } else {
                tz = getTimeZone(context, null);
            }
            synchronized (mSB) {
                mSB.setLength(0);
                date = DateUtils.formatDateRange(context, mF, startMillis, endMillis, flags,
                        tz).toString();
            }
            return date;
        }

        /**
         * Writes a new home time zone to the db.
         *
         * Updates the home time zone in the db asynchronously and updates
         * the local cache. Sending a time zone of
         * {@link CalendarCache#TIMEZONE_TYPE_AUTO} will cause it to be set
         * to the device's time zone. null or empty tz will be ignored.
         *
         * @param context The calling activity
         * @param timeZone The time zone to set Calendar to, or
         * {@link CalendarCache#TIMEZONE_TYPE_AUTO}
         */
        public void setTimeZone(Context context, String timeZone) {
            if (TextUtils.isEmpty(timeZone)) {
                if (DEBUG) {
                    Log.d(TAG, "Empty time zone, nothing to be done.");
                }
                return;
            }
            boolean updatePrefs = false;
            synchronized (mTZCallbacks) {
                if (CalendarCache.TIMEZONE_TYPE_AUTO.equals(timeZone)) {
                    if (mUseHomeTZ) {
                        updatePrefs = true;
                    }
                    mUseHomeTZ = false;
                } else {
                    if (!mUseHomeTZ || !TextUtils.equals(mHomeTZ, timeZone)) {
                        updatePrefs = true;
                    }
                    mUseHomeTZ = true;
                    mHomeTZ = timeZone;
                }
            }
            if (updatePrefs) {
                // Write the prefs
                SharedPreferences prefs = getSharedPreferences(context, mPrefsName);
                setSharedPreference(prefs, KEY_HOME_TZ_ENABLED, mUseHomeTZ);
                setSharedPreference(prefs, KEY_HOME_TZ, mHomeTZ);

                // Update the db
                ContentValues values = new ContentValues();
                if (mHandler != null) {
                    mHandler.cancelOperation(mToken);
                }

                mHandler = new AsyncTZHandler(context.getContentResolver());

                // skip 0 so query can use it
                if (++mToken == 0) {
                    mToken = 1;
                }

                // Write the use home tz setting
                values.put(CalendarCache.VALUE, mUseHomeTZ ? CalendarCache.TIMEZONE_TYPE_HOME
                        : CalendarCache.TIMEZONE_TYPE_AUTO);
                mHandler.startUpdate(mToken, null, CalendarCache.URI, values, CalendarCache.WHERE,
                        TIMEZONE_TYPE_ARGS);

                // If using a home tz write it to the db
                if (mUseHomeTZ) {
                    ContentValues values2 = new ContentValues();
                    values2.put(CalendarCache.VALUE, mHomeTZ);
                    mHandler.startUpdate(mToken, null, CalendarCache.URI, values2,
                            CalendarCache.WHERE, TIMEZONE_INSTANCES_ARGS);
                }
            }
        }

        /**
         * Gets the time zone that Calendar should be displayed in
         *
         * This is a helper method to get the appropriate time zone for Calendar. If this
         * is the first time this method has been called it will initiate an asynchronous
         * query to verify that the data in preferences is correct. The callback supplied
         * will only be called if this query returns a value other than what is stored in
         * preferences and should cause the calling activity to refresh anything that
         * depends on calling this method.
         *
         * @param context The calling activity
         * @param callback The runnable that should execute if a query returns new values
         * @return The string value representing the time zone Calendar should display
         */
        public String getTimeZone(Context context, Runnable callback) {
            synchronized (mTZCallbacks){
                if (mFirstTZRequest) {
                    mTZQueryInProgress = true;
                    mFirstTZRequest = false;

                    SharedPreferences prefs = getSharedPreferences(context, mPrefsName);
                    mUseHomeTZ = prefs.getBoolean(KEY_HOME_TZ_ENABLED, false);
                    mHomeTZ = prefs.getString(KEY_HOME_TZ, Time.getCurrentTimezone());

                    // When the async query returns it should synchronize on
                    // mTZCallbacks, update mUseHomeTZ, mHomeTZ, and the
                    // preferences, set mTZQueryInProgress to false, and call all
                    // the runnables in mTZCallbacks.
                    if (mHandler == null) {
                        mHandler = new AsyncTZHandler(context.getContentResolver());
                    }
                    mHandler.startQuery(0, context, CalendarCache.URI, CalendarCache.POJECTION,
                            null, null, null);
                }
                if (mTZQueryInProgress) {
                    mTZCallbacks.add(callback);
                }
            }
            return mUseHomeTZ ? mHomeTZ : Time.getCurrentTimezone();
        }

        /**
         * Forces a query of the database to check for changes to the time zone.
         * This should be called if another app may have modified the db. If a
         * query is already in progress the callback will be added to the list
         * of callbacks to be called when it returns.
         *
         * @param context The calling activity
         * @param callback The runnable that should execute if a query returns
         *            new values
         */
        public void forceDBRequery(Context context, Runnable callback) {
            synchronized (mTZCallbacks){
                if (mTZQueryInProgress) {
                    mTZCallbacks.add(callback);
                    return;
                }
                mFirstTZRequest = true;
                getTimeZone(context, callback);
            }
        }
    }

        /**
         * A helper method for writing a String value to the preferences
         * asynchronously.
         *
         * @param context A context with access to the correct preferences
         * @param key The preference to write to
         * @param value The value to write
         */
        public static void setSharedPreference(SharedPreferences prefs, String key, String value) {
//            SharedPreferences prefs = getSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(key, value);
            editor.apply();
        }

        /**
         * A helper method for writing a boolean value to the preferences
         * asynchronously.
         *
         * @param context A context with access to the correct preferences
         * @param key The preference to write to
         * @param value The value to write
         */
        public static void setSharedPreference(SharedPreferences prefs, String key, boolean value) {
//            SharedPreferences prefs = getSharedPreferences(context, prefsName);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(key, value);
            editor.apply();
        }

        /** Return a properly configured SharedPreferences instance */
        public static SharedPreferences getSharedPreferences(Context context, String prefsName) {
            return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        }
}
