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
 * limitations under the License
 */
package com.android.settingslib.wifi;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.PasspointConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Provide utility functions for retrieving saved Wi-Fi configurations.
 */
public class WifiSavedConfigUtils {
    /**
     * Returns all the saved configurations on the device, including both Wi-Fi networks and
     * Passpoint profiles, represented by {@link AccessPoint}.
     *
     * @param context The application context
     * @param wifiManager An instance of {@link WifiManager}
     * @return List of {@link AccessPoint}
     */
    public static List<AccessPoint> getAllConfigs(Context context, WifiManager wifiManager) {
        List<AccessPoint> savedConfigs = new ArrayList<>();
        List<WifiConfiguration> savedNetworks = wifiManager.getConfiguredNetworks();
        for (WifiConfiguration network : savedNetworks) {
            // Configuration for Passpoint network is configured temporary by WifiService for
            // connection attempt only.  The underlying configuration is saved as Passpoint
            // configuration, which will be retrieved with WifiManager#getPasspointConfiguration
            // call below.
            if (network.isPasspoint()) {
                continue;
            }
            // Ephemeral networks are not saved to the persistent storage, ignore them.
            if (network.isEphemeral()) {
                continue;
            }
            savedConfigs.add(new AccessPoint(context, network));
        }
        try {
            List<PasspointConfiguration> savedPasspointConfigs =
                    wifiManager.getPasspointConfigurations();
            for (PasspointConfiguration config : savedPasspointConfigs) {
                savedConfigs.add(new AccessPoint(context, config));
            }
        } catch (UnsupportedOperationException e) {
            // Passpoint not supported.
        }
        return savedConfigs;
    }
}

