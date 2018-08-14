/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.PersistableBundle;
import android.support.annotation.VisibleForTesting;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.Lifecycle;

/**
 * Preference controller for IMS status
 */
public abstract class AbstractImsStatusPreferenceController
        extends AbstractConnectivityPreferenceController {

    @VisibleForTesting
    static final String KEY_IMS_REGISTRATION_STATE = "ims_reg_state";

    private static final String[] CONNECTIVITY_INTENTS = {
            BluetoothAdapter.ACTION_STATE_CHANGED,
            ConnectivityManager.CONNECTIVITY_ACTION,
            WifiManager.LINK_CONFIGURATION_CHANGED_ACTION,
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
    };

    private Preference mImsStatus;

    public AbstractImsStatusPreferenceController(Context context,
            Lifecycle lifecycle) {
        super(context, lifecycle);
    }

    @Override
    public boolean isAvailable() {
        CarrierConfigManager configManager = mContext.getSystemService(CarrierConfigManager.class);
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        PersistableBundle config = null;
        if (configManager != null) {
            config = configManager.getConfigForSubId(subId);
        }
        return config != null && config.getBoolean(
                CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL);
    }

    @Override
    public String getPreferenceKey() {
        return KEY_IMS_REGISTRATION_STATE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mImsStatus = screen.findPreference(KEY_IMS_REGISTRATION_STATE);
        updateConnectivity();
    }

    @Override
    protected String[] getConnectivityIntents() {
        return CONNECTIVITY_INTENTS;
    }

    @Override
    protected void updateConnectivity() {
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (mImsStatus != null) {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            mImsStatus.setSummary((tm != null && tm.isImsRegistered(subId)) ?
                    R.string.ims_reg_status_registered : R.string.ims_reg_status_not_registered);
        }
    }
}
