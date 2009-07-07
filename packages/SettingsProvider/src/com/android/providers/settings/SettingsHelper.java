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

import java.util.Locale;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.backup.BackupDataInput;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentService;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.IHardwareService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

public class SettingsHelper {
    private static final String TAG = "SettingsHelper";

    private Context mContext;
    private AudioManager mAudioManager;
    private IContentService mContentService;
    private static final String[] PROVIDERS = { "gmail-ls", "calendar", "contacts" };

    private boolean mSilent;
    private boolean mVibrate;

    public SettingsHelper(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context
                .getSystemService(Context.AUDIO_SERVICE);
        mContentService = ContentResolver.getContentService();
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
        }
        return true;
    }

    private void setGpsLocation(String value) {
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
            IHardwareService hardware = IHardwareService.Stub
                    .asInterface(ServiceManager.getService("hardware"));
            if (hardware != null) {
                hardware.setBacklights(brightness);
            }
        } catch (RemoteException doe) {

        }
    }

    private void setRingerMode() {
        if (mSilent) {
            mAudioManager.setRingerMode(mVibrate ? AudioManager.RINGER_MODE_VIBRATE :
                AudioManager.RINGER_MODE_SILENT);
        } else {
            mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            mAudioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER,
                    mVibrate ? AudioManager.VIBRATE_SETTING_ON
                            : AudioManager.VIBRATE_SETTING_OFF);
        }
    }

    byte[] getSyncProviders() {
        byte[] sync = new byte[1 + PROVIDERS.length];
        try {
            sync[0] = (byte) (mContentService.getListenForNetworkTickles() ? 1 : 0);
            for (int i = 0; i < PROVIDERS.length; i++) {
                sync[i + 1] = (byte) 
                        (mContentService.getSyncProviderAutomatically(PROVIDERS[i]) ? 1 : 0);
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Unable to backup sync providers");
            return sync;
        }
        return sync;
    }

    void setSyncProviders(BackupDataInput backup) {
        byte[] sync = new byte[backup.getDataSize()];

        try {
            backup.readEntityData(sync, 0, sync.length);
            mContentService.setListenForNetworkTickles(sync[0] == 1);
            for (int i = 0; i < PROVIDERS.length; i++) {
                mContentService.setSyncProviderAutomatically(PROVIDERS[i], sync[i + 1] > 0);
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Unable to restore sync providers");
        } catch (java.io.IOException ioe) {
            Log.w(TAG, "Unable to read sync settings");
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
    void setLocaleData(byte[] data) {
        // Check if locale was set by the user:
        Configuration conf = mContext.getResources().getConfiguration();
        Locale loc = conf.locale;
        if (conf.userSetLocale) return; // Don't change if user set it in the SetupWizard

        final String[] availableLocales = mContext.getAssets().getLocales();
        String localeCode = new String(data);
        String language = new String(data, 0, 2);
        String country = data.length > 4 ? new String(data, 3, 2) : "";
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
}
