/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.webkit;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.ContentObserver;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;

import java.util.HashSet;

/**
 * A class to manage the interaction between the system setting 'Location &
 * Security - Share with Google' and the browser. When this setting is set
 * to true, we allow Geolocation for Google origins. When this setting is
 * set to false, we clear Geolocation permissions for Google origins.
 * @hide pending API council review
 */
class GoogleLocationSettingManager {
    // The application context.
    private Context mContext;
    // The observer used to listen to the system setting.
    private GoogleLocationSettingObserver mSettingObserver;

    // The value of the system setting that indicates true.
    private final static int sSystemSettingTrue = 1;
    // The value of the system setting that indicates false.
    private final static int sSystemSettingFalse = 0;
    // The value of the USE_LOCATION_FOR_SERVICES system setting last read
    // by the browser.
    private final static String LAST_READ_USE_LOCATION_FOR_SERVICES =
            "lastReadUseLocationForServices";
    // The Google origins we consider.
    private static HashSet<String> sGoogleOrigins;
    static {
        sGoogleOrigins = new HashSet<String>();
        // NOTE: DO NOT ADD A "/" AT THE END!
        sGoogleOrigins.add("http://www.google.com");
        sGoogleOrigins.add("http://www.google.co.uk");
    }

    GoogleLocationSettingManager(Context context) {
        mContext = context;
    }

    /**
     * Starts the manager. Checks whether the setting has changed and
     * installs an observer to listen for future changes.
     */
    public void start() {
        maybeApplySetting();

        mSettingObserver = new GoogleLocationSettingObserver();
        mSettingObserver.observe();
    }

    /**
     * Checks to see if the system setting has changed and if so,
     * updates the Geolocation permissions accordingly.
     */
    private void maybeApplySetting() {
        int setting = getSystemSetting();
        if (settingChanged(setting)) {
            applySetting(setting);
        }
    }

    /**
     * Gets the current system setting for 'Use location for Google services'.
     * @return The system setting.
     */
    private int getSystemSetting() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                                      Settings.Secure.USE_LOCATION_FOR_SERVICES,
                                      sSystemSettingFalse);
    }

    /**
     * Determines whether the supplied setting has changed from the last
     * value read by the browser.
     * @param setting The setting.
     * @return Whether the setting has changed from the last value read
     *     by the browser.
     */
    private boolean settingChanged(int setting) {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(mContext);
        // Default to false. If the system setting is false the first time it is ever read by the
        // browser, there's nothing to do.
        int lastReadSetting = sSystemSettingFalse;
        lastReadSetting = preferences.getInt(LAST_READ_USE_LOCATION_FOR_SERVICES,
                                             lastReadSetting);

        if (lastReadSetting == setting) {
            return false;
        }

        Editor editor = preferences.edit();
        editor.putInt(LAST_READ_USE_LOCATION_FOR_SERVICES, setting);
        editor.commit();
        return true;
    }

    /**
     * Applies the supplied setting to the Geolocation permissions.
     * @param setting The setting.
     */
    private void applySetting(int setting) {
        for (String origin : sGoogleOrigins) {
            if (setting == sSystemSettingTrue) {
                GeolocationPermissions.getInstance().allow(origin);
            } else {
                GeolocationPermissions.getInstance().clear(origin);
            }
        }
    }

    /**
     * This class implements an observer to listen for changes to the
     * system setting.
     */
    class GoogleLocationSettingObserver extends ContentObserver {
        GoogleLocationSettingObserver() {
            super(new Handler());
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.USE_LOCATION_FOR_SERVICES), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            maybeApplySetting();
        }
    }
}
