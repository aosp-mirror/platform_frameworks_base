/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.providers.settings;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.backup.IBackupManager;
import android.content.Context;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.Locale;

public class SettingsHelper {
    private Context mContext;
    private AudioManager mAudioManager;

    public SettingsHelper(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context
                .getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Sets the property via a call to the appropriate API, if any, and returns
     * whether or not the setting should be saved to the database as well.
     * @param name the name of the setting
     * @param value the string value of the setting
     * @return whether to continue with writing the value to the database. In
     * some cases the data will be written by the call to the appropriate API,
     * and in some cases the property value needs to be modified before setting.
     */
    public boolean restoreValue(String name, String value) {
        if (Settings.System.SCREEN_BRIGHTNESS.equals(name)) {
            setBrightness(Integer.parseInt(value));
        } else if (Settings.System.SOUND_EFFECTS_ENABLED.equals(name)) {
            setSoundEffects(Integer.parseInt(value) == 1);
        } else if (Settings.Secure.LOCATION_PROVIDERS_ALLOWED.equals(name)) {
            setGpsLocation(value);
            return false;
        } else if (Settings.Secure.BACKUP_AUTO_RESTORE.equals(name)) {
            setAutoRestore(Integer.parseInt(value) == 1);
        } else if (isAlreadyConfiguredCriticalAccessibilitySetting(name)) {
            return false;
        }
        return true;
    }

    private boolean isAlreadyConfiguredCriticalAccessibilitySetting(String name) {
        // These are the critical accessibility settings that are required for a
        // blind user to be able to interact with the device. If these settings are
        // already configured, we will not overwrite them. If they are already set,
        // it means that the user has performed a global gesture to enable accessibility
        // and definitely needs these features working after the restore.
        if (Settings.Secure.ACCESSIBILITY_ENABLED.equals(name)
                || Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION.equals(name)
                || Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD.equals(name)
                || Settings.Secure.TOUCH_EXPLORATION_ENABLED.equals(name)) {
            return Settings.Secure.getInt(mContext.getContentResolver(), name, 0) != 0;
        } else if (Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES.equals(name)
                || Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.equals(name)) {
            return !TextUtils.isEmpty(Settings.Secure.getString(
                    mContext.getContentResolver(), name));
        }
        return false;
    }

    private void setAutoRestore(boolean enabled) {
        try {
            IBackupManager bm = IBackupManager.Stub.asInterface(
                    ServiceManager.getService(Context.BACKUP_SERVICE));
            if (bm != null) {
                bm.setAutoRestore(enabled);
            }
        } catch (RemoteException e) {}
    }

    private void setGpsLocation(String value) {
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)) {
            return;
        }
        final String GPS = LocationManager.GPS_PROVIDER;
        boolean enabled =
                GPS.equals(value) ||
                value.startsWith(GPS + ",") ||
                value.endsWith("," + GPS) ||
                value.contains("," + GPS + ",");
        Settings.Secure.setLocationProviderEnabled(
                mContext.getContentResolver(), GPS, enabled);
    }

    private void setSoundEffects(boolean enable) {
        if (enable) {
            mAudioManager.loadSoundEffects();
        } else {
            mAudioManager.unloadSoundEffects();
        }
    }

    private void setBrightness(int brightness) {
        try {
            IPowerManager power = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));
            if (power != null) {
                power.setTemporaryScreenBrightnessSettingOverride(brightness);
            }
        } catch (RemoteException doe) {

        }
    }

    byte[] getLocaleData() {
        Configuration conf = mContext.getResources().getConfiguration();
        final Locale loc = conf.locale;
        String localeString = loc.getLanguage();
        String country = loc.getCountry();
        if (!TextUtils.isEmpty(country)) {
            localeString += "_" + country;
        }
        return localeString.getBytes();
    }

    /**
     * Sets the locale specified. Input data is the equivalent of "ll_cc".getBytes(), where
     * "ll" is the language code and "cc" is the country code.
     * @param data the locale string in bytes.
     */
    void setLocaleData(byte[] data, int size) {
        // Check if locale was set by the user:
        Configuration conf = mContext.getResources().getConfiguration();
        Locale loc = conf.locale;
        // TODO: The following is not working as intended because the network is forcing a locale
        // change after registering. Need to find some other way to detect if the user manually
        // changed the locale
        if (conf.userSetLocale) return; // Don't change if user set it in the SetupWizard

        final String[] availableLocales = mContext.getAssets().getLocales();
        String localeCode = new String(data, 0, size);
        String language = new String(data, 0, 2);
        String country = size > 4 ? new String(data, 3, 2) : "";
        loc = null;
        for (int i = 0; i < availableLocales.length; i++) {
            if (availableLocales[i].equals(localeCode)) {
                loc = new Locale(language, country);
                break;
            }
        }
        if (loc == null) return; // Couldn't find the saved locale in this version of the software

        try {
            IActivityManager am = ActivityManagerNative.getDefault();
            Configuration config = am.getConfiguration();
            config.locale = loc;
            // indicate this isn't some passing default - the user wants this remembered
            config.userSetLocale = true;

            am.updateConfiguration(config);
        } catch (RemoteException e) {
            // Intentionally left blank
        }
    }

    /**
     * Informs the audio service of changes to the settings so that
     * they can be re-read and applied.
     */
    void applyAudioSettings() {
        AudioManager am = new AudioManager(mContext);
        am.reloadAudioSettings();
    }
}
