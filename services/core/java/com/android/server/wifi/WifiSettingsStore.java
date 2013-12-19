/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wifi;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/* Tracks persisted settings for Wi-Fi and airplane mode interaction */
final class WifiSettingsStore {
    /* Values tracked in Settings.Global.WIFI_ON */
    private static final int WIFI_DISABLED                      = 0;
    private static final int WIFI_ENABLED                       = 1;
    /* Wifi enabled while in airplane mode */
    private static final int WIFI_ENABLED_AIRPLANE_OVERRIDE     = 2;
    /* Wifi disabled due to airplane mode on */
    private static final int WIFI_DISABLED_AIRPLANE_ON          = 3;

    /* Persisted state that tracks the wifi & airplane interaction from settings */
    private int mPersistWifiState = WIFI_DISABLED;
    /* Tracks current airplane mode state */
    private boolean mAirplaneModeOn = false;

    /* Tracks the setting of scan being available even when wi-fi is turned off
     */
    private boolean mScanAlwaysAvailable;

    private final Context mContext;

    /* Tracks if we have checked the saved wi-fi state after boot */
    private boolean mCheckSavedStateAtBoot = false;

    WifiSettingsStore(Context context) {
        mContext = context;
        mAirplaneModeOn = getPersistedAirplaneModeOn();
        mPersistWifiState = getPersistedWifiState();
        mScanAlwaysAvailable = getPersistedScanAlwaysAvailable();
    }

    synchronized boolean isWifiToggleEnabled() {
        if (!mCheckSavedStateAtBoot) {
            mCheckSavedStateAtBoot = true;
            if (testAndClearWifiSavedState()) return true;
        }

        if (mAirplaneModeOn) {
            return mPersistWifiState == WIFI_ENABLED_AIRPLANE_OVERRIDE;
        } else {
            return mPersistWifiState != WIFI_DISABLED;
        }
    }

    /**
     * Returns true if airplane mode is currently on.
     * @return {@code true} if airplane mode is on.
     */
    synchronized boolean isAirplaneModeOn() {
       return mAirplaneModeOn;
    }

    synchronized boolean isScanAlwaysAvailable() {
        return mScanAlwaysAvailable;
    }

    synchronized boolean handleWifiToggled(boolean wifiEnabled) {
        // Can Wi-Fi be toggled in airplane mode ?
        if (mAirplaneModeOn && !isAirplaneToggleable()) {
            return false;
        }

        if (wifiEnabled) {
            if (mAirplaneModeOn) {
                persistWifiState(WIFI_ENABLED_AIRPLANE_OVERRIDE);
            } else {
                persistWifiState(WIFI_ENABLED);
            }
        } else {
            // When wifi state is disabled, we do not care
            // if airplane mode is on or not. The scenario of
            // wifi being disabled due to airplane mode being turned on
            // is handled handleAirplaneModeToggled()
            persistWifiState(WIFI_DISABLED);
        }
        return true;
    }

    synchronized boolean handleAirplaneModeToggled() {
        // Is Wi-Fi sensitive to airplane mode changes ?
        if (!isAirplaneSensitive()) {
            return false;
        }

        mAirplaneModeOn = getPersistedAirplaneModeOn();
        if (mAirplaneModeOn) {
            // Wifi disabled due to airplane on
            if (mPersistWifiState == WIFI_ENABLED) {
                persistWifiState(WIFI_DISABLED_AIRPLANE_ON);
            }
        } else {
            /* On airplane mode disable, restore wifi state if necessary */
            if (testAndClearWifiSavedState() ||
                    mPersistWifiState == WIFI_ENABLED_AIRPLANE_OVERRIDE) {
                persistWifiState(WIFI_ENABLED);
            }
        }
        return true;
    }

    synchronized void handleWifiScanAlwaysAvailableToggled() {
        mScanAlwaysAvailable = getPersistedScanAlwaysAvailable();
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mPersistWifiState " + mPersistWifiState);
        pw.println("mAirplaneModeOn " + mAirplaneModeOn);
    }

    private void persistWifiState(int state) {
        final ContentResolver cr = mContext.getContentResolver();
        mPersistWifiState = state;
        Settings.Global.putInt(cr, Settings.Global.WIFI_ON, state);
    }

    /* Does Wi-Fi need to be disabled when airplane mode is on ? */
    private boolean isAirplaneSensitive() {
        String airplaneModeRadios = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_RADIOS);
        return airplaneModeRadios == null
                || airplaneModeRadios.contains(Settings.Global.RADIO_WIFI);
    }

    /* Is Wi-Fi allowed to be re-enabled while airplane mode is on ? */
    private boolean isAirplaneToggleable() {
        String toggleableRadios = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS);
        return toggleableRadios != null
                && toggleableRadios.contains(Settings.Global.RADIO_WIFI);
    }

     /* After a reboot, we restore wi-fi to be on if it was turned off temporarily for tethering.
      * The settings app tracks the saved state, but the framework has to check it at boot to
      * make sure the wi-fi is turned on in case it was turned off for the purpose of tethering.
      *
      * Note that this is not part of the regular WIFI_ON setting because this only needs to
      * be controlled through the settings app and not the Wi-Fi public API.
      */
    private boolean testAndClearWifiSavedState() {
        final ContentResolver cr = mContext.getContentResolver();
        int wifiSavedState = 0;
        try {
            wifiSavedState = Settings.Global.getInt(cr, Settings.Global.WIFI_SAVED_STATE);
            if(wifiSavedState == 1)
                Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 0);
        } catch (Settings.SettingNotFoundException e) {
            ;
        }
        return (wifiSavedState == 1);
    }

    private int getPersistedWifiState() {
        final ContentResolver cr = mContext.getContentResolver();
        try {
            return Settings.Global.getInt(cr, Settings.Global.WIFI_ON);
        } catch (Settings.SettingNotFoundException e) {
            Settings.Global.putInt(cr, Settings.Global.WIFI_ON, WIFI_DISABLED);
            return WIFI_DISABLED;
        }
    }

    private boolean getPersistedAirplaneModeOn() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private boolean getPersistedScanAlwaysAvailable() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE,
                0) == 1;
    }
}
