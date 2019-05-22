/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.location;

import android.content.Context;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.StatsLog;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * A utility class to hold GNSS configuration properties.
 *
 * The trigger to load/reload the configuration parameters should be managed by the class
 * that owns an instance of this class.
 *
 * Instances of this class are not thread-safe and should either be used from a single thread
 * or with external synchronization when used by multiple threads.
 */
class GnssConfiguration {
    private static final String TAG = "GnssConfiguration";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String DEBUG_PROPERTIES_FILE = "/etc/gps_debug.conf";

    // config.xml properties
    private static final String CONFIG_SUPL_HOST = "SUPL_HOST";
    private static final String CONFIG_SUPL_PORT = "SUPL_PORT";
    private static final String CONFIG_C2K_HOST = "C2K_HOST";
    private static final String CONFIG_C2K_PORT = "C2K_PORT";
    private static final String CONFIG_SUPL_VER = "SUPL_VER";
    private static final String CONFIG_SUPL_MODE = "SUPL_MODE";
    private static final String CONFIG_SUPL_ES = "SUPL_ES";
    private static final String CONFIG_LPP_PROFILE = "LPP_PROFILE";
    private static final String CONFIG_A_GLONASS_POS_PROTOCOL_SELECT =
            "A_GLONASS_POS_PROTOCOL_SELECT";
    private static final String CONFIG_USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL =
            "USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL";
    private static final String CONFIG_GPS_LOCK = "GPS_LOCK";
    private static final String CONFIG_ES_EXTENSION_SEC = "ES_EXTENSION_SEC";
    public static final String CONFIG_NFW_PROXY_APPS = "NFW_PROXY_APPS";

    // Limit on NI emergency mode time extension after emergency sessions ends
    private static final int MAX_EMERGENCY_MODE_EXTENSION_SECONDS = 300;  // 5 minute maximum

    // Persist property for LPP_PROFILE
    static final String LPP_PROFILE = "persist.sys.gps.lpp";

    // Represents an HAL interface version. Instances of this class are created in the JNI layer
    // and returned through native methods.
    private static class HalInterfaceVersion {
        final int mMajor;
        final int mMinor;

        HalInterfaceVersion(int major, int minor) {
            mMajor = major;
            mMinor = minor;
        }
    }

    /**
     * Properties loaded from PROPERTIES_FILE.
     */
    private Properties mProperties;

    private int mEsExtensionSec = 0;

    private final Context mContext;

    GnssConfiguration(Context context) {
        mContext = context;
        mProperties = new Properties();
    }

    /**
     * Returns the full set of properties loaded.
     */
    Properties getProperties() {
        return mProperties;
    }

    /**
     * Returns the value of config parameter ES_EXTENSION_SEC. The value is range checked
     * and constrained to min/max limits.
     */
    int getEsExtensionSec() {
        return mEsExtensionSec;
    }

    /**
     * Returns the value of config parameter SUPL_HOST or {@code null} if no value is
     * provided.
     */
    String getSuplHost() {
        return mProperties.getProperty(CONFIG_SUPL_HOST);
    }

    /**
     * Returns the value of config parameter SUPL_PORT or {@code defaultPort} if no value is
     * provided or if there is an error parsing the configured value.
     */
    int getSuplPort(int defaultPort) {
        return getIntConfig(CONFIG_SUPL_PORT, defaultPort);
    }

    /**
     * Returns the value of config parameter C2K_HOST or {@code null} if no value is
     * provided.
     */
    String getC2KHost() {
        return mProperties.getProperty(CONFIG_C2K_HOST);
    }

    /**
     * Returns the value of config parameter C2K_PORT or {@code defaultPort} if no value is
     * provided or if there is an error parsing the configured value.
     */
    int getC2KPort(int defaultPort) {
        return getIntConfig(CONFIG_C2K_PORT, defaultPort);
    }

    /**
     * Returns the value of config parameter SUPL_MODE or {@code defaultMode} if no value is
     * provided or if there is an error parsing the configured value.
     */
    int getSuplMode(int defaultMode) {
        return getIntConfig(CONFIG_SUPL_MODE, defaultMode);
    }

    /**
     * Returns the value of config parameter SUPL_ES or {@code defaultSuplEs} if no value is
     * provided or if there is an error parsing the configured value.
     */
    int getSuplEs(int defaulSuplEs) {
        return getIntConfig(CONFIG_SUPL_ES, defaulSuplEs);
    }

    /**
     * Returns the value of config parameter LPP_PROFILE or {@code null} if no value is
     * provided.
     */
    String getLppProfile() {
        return mProperties.getProperty(CONFIG_LPP_PROFILE);
    }

    /**
     * Returns the list of proxy apps from the value of config parameter NFW_PROXY_APPS or
     * {@Collections.EMPTY_LIST} if no value is provided.
     */
    List<String> getProxyApps() {
        // Space separated list of Android proxy app package names.
        String proxyAppsStr = mProperties.getProperty(CONFIG_NFW_PROXY_APPS);
        if (TextUtils.isEmpty(proxyAppsStr)) {
            return Collections.EMPTY_LIST;
        }

        String[] proxyAppsArray = proxyAppsStr.trim().split("\\s+");
        if (proxyAppsArray.length == 0) {
            return Collections.EMPTY_LIST;
        }

        ArrayList proxyApps = new ArrayList(proxyAppsArray.length);
        for (String proxyApp : proxyAppsArray) {
            proxyApps.add(proxyApp);
        }

        return proxyApps;
    }

    /**
     * Updates the GNSS HAL satellite blacklist.
     */
    void setSatelliteBlacklist(int[] constellations, int[] svids) {
        native_set_satellite_blacklist(constellations, svids);
    }

    interface SetCarrierProperty {
        boolean set(int value);
    }

    /**
     * Loads the GNSS properties from carrier config file followed by the properties from
     * gps debug config file.
     */
    void reloadGpsProperties() {
        if (DEBUG) Log.d(TAG, "Reset GPS properties, previous size = " + mProperties.size());
        loadPropertiesFromCarrierConfig();

        String lpp_prof = SystemProperties.get(LPP_PROFILE);
        if (!TextUtils.isEmpty(lpp_prof)) {
            // override default value of this if lpp_prof is not empty
            mProperties.setProperty(CONFIG_LPP_PROFILE, lpp_prof);
        }
        /*
         * Overlay carrier properties from a debug configuration file.
         */
        loadPropertiesFromGpsDebugConfig(mProperties);

        mEsExtensionSec = getRangeCheckedConfigEsExtensionSec();

        logConfigurations();

        final HalInterfaceVersion gnssConfigurationIfaceVersion =
                native_get_gnss_configuration_version();
        if (gnssConfigurationIfaceVersion != null) {
            // Set to a range checked value.
            if (isConfigEsExtensionSecSupported(gnssConfigurationIfaceVersion)
                    && !native_set_es_extension_sec(mEsExtensionSec)) {
                Log.e(TAG, "Unable to set " + CONFIG_ES_EXTENSION_SEC + ": " + mEsExtensionSec);
            }

            Map<String, SetCarrierProperty> map = new HashMap<String, SetCarrierProperty>() {
                {
                    put(CONFIG_SUPL_VER, GnssConfiguration::native_set_supl_version);
                    put(CONFIG_SUPL_MODE, GnssConfiguration::native_set_supl_mode);

                    if (isConfigSuplEsSupported(gnssConfigurationIfaceVersion)) {
                        put(CONFIG_SUPL_ES, GnssConfiguration::native_set_supl_es);
                    }

                    put(CONFIG_LPP_PROFILE, GnssConfiguration::native_set_lpp_profile);
                    put(CONFIG_A_GLONASS_POS_PROTOCOL_SELECT,
                            GnssConfiguration::native_set_gnss_pos_protocol_select);
                    put(CONFIG_USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL,
                            GnssConfiguration::native_set_emergency_supl_pdn);

                    if (isConfigGpsLockSupported(gnssConfigurationIfaceVersion)) {
                        put(CONFIG_GPS_LOCK, GnssConfiguration::native_set_gps_lock);
                    }
                }
            };

            for (Entry<String, SetCarrierProperty> entry : map.entrySet()) {
                String propertyName = entry.getKey();
                String propertyValueString = mProperties.getProperty(propertyName);
                if (propertyValueString != null) {
                    try {
                        int propertyValueInt = Integer.decode(propertyValueString);
                        boolean result = entry.getValue().set(propertyValueInt);
                        if (!result) {
                            Log.e(TAG, "Unable to set " + propertyName);
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Unable to parse propertyName: " + propertyValueString);
                    }
                }
            }
        } else if (DEBUG) {
            Log.d(TAG, "Skipped configuration update because GNSS configuration in GPS HAL is not"
                    + " supported");
        }
    }

    private void logConfigurations() {
        StatsLog.write(StatsLog.GNSS_CONFIGURATION_REPORTED,
                getSuplHost(),
                getSuplPort(0),
                getC2KHost(),
                getC2KPort(0),
                getIntConfig(CONFIG_SUPL_VER, 0),
                getSuplMode(0),
                getSuplEs(0) == 1,
                getIntConfig(CONFIG_LPP_PROFILE, 0),
                getIntConfig(CONFIG_A_GLONASS_POS_PROTOCOL_SELECT, 0),
                getIntConfig(CONFIG_USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL, 0) == 1,
                getIntConfig(CONFIG_GPS_LOCK, 0),
                getEsExtensionSec(),
                mProperties.getProperty(CONFIG_NFW_PROXY_APPS));
    }

    /**
     * Loads GNSS properties from carrier config file.
     */
    void loadPropertiesFromCarrierConfig() {
        CarrierConfigManager configManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            return;
        }

        int ddSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        PersistableBundle configs = SubscriptionManager.isValidSubscriptionId(ddSubId)
                ? configManager.getConfigForSubId(ddSubId) : null;
        if (configs == null) {
            if (DEBUG) Log.d(TAG, "SIM not ready, use default carrier config.");
            configs = CarrierConfigManager.getDefaultConfig();
        }
        for (String configKey : configs.keySet()) {
            if (configKey.startsWith(CarrierConfigManager.Gps.KEY_PREFIX)) {
                String key = configKey
                        .substring(CarrierConfigManager.Gps.KEY_PREFIX.length())
                        .toUpperCase();
                Object value = configs.get(configKey);
                if (DEBUG) Log.d(TAG, "Gps config: " + key + " = " + value);
                if (value instanceof String) {
                    // Most GPS properties are of String type; convert so.
                    mProperties.setProperty(key, (String) value);
                } else if (value != null) {
                    mProperties.setProperty(key, value.toString());
                }
            }
        }
    }

    private void loadPropertiesFromGpsDebugConfig(Properties properties) {
        try {
            File file = new File(DEBUG_PROPERTIES_FILE);
            FileInputStream stream = null;
            try {
                stream = new FileInputStream(file);
                properties.load(stream);
            } finally {
                IoUtils.closeQuietly(stream);
            }
        } catch (IOException e) {
            if (DEBUG) Log.d(TAG, "Could not open GPS configuration file " + DEBUG_PROPERTIES_FILE);
        }
    }

    private int getRangeCheckedConfigEsExtensionSec() {
        int emergencyExtensionSeconds = getIntConfig(CONFIG_ES_EXTENSION_SEC, 0);
        if (emergencyExtensionSeconds > MAX_EMERGENCY_MODE_EXTENSION_SECONDS) {
            Log.w(TAG, CONFIG_ES_EXTENSION_SEC + ": " + emergencyExtensionSeconds
                    + " too high, reset to " + MAX_EMERGENCY_MODE_EXTENSION_SECONDS);
            emergencyExtensionSeconds = MAX_EMERGENCY_MODE_EXTENSION_SECONDS;
        } else if (emergencyExtensionSeconds < 0) {
            Log.w(TAG, CONFIG_ES_EXTENSION_SEC + ": " + emergencyExtensionSeconds
                    + " is negative, reset to zero.");
            emergencyExtensionSeconds = 0;
        }
        return emergencyExtensionSeconds;
    }

    private int getIntConfig(String configParameter, int defaultValue) {
        String valueString = mProperties.getProperty(configParameter);
        if (TextUtils.isEmpty(valueString)) {
            return defaultValue;
        }
        try {
            return Integer.decode(valueString);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Unable to parse config parameter " + configParameter + " value: "
                    + valueString + ". Using default value: " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean isConfigEsExtensionSecSupported(
            HalInterfaceVersion gnssConfiguartionIfaceVersion) {
        // ES_EXTENSION_SEC is introduced in @2.0::IGnssConfiguration.hal
        return gnssConfiguartionIfaceVersion.mMajor >= 2;
    }

    private static boolean isConfigSuplEsSupported(
            HalInterfaceVersion gnssConfiguartionIfaceVersion) {
        // SUPL_ES is deprecated in @2.0::IGnssConfiguration.hal
        return gnssConfiguartionIfaceVersion.mMajor < 2;
    }

    private static boolean isConfigGpsLockSupported(
            HalInterfaceVersion gnssConfiguartionIfaceVersion) {
        // GPS_LOCK is deprecated in @2.0::IGnssConfiguration.hal
        return gnssConfiguartionIfaceVersion.mMajor < 2;
    }

    private static native HalInterfaceVersion native_get_gnss_configuration_version();

    private static native boolean native_set_supl_version(int version);

    private static native boolean native_set_supl_mode(int mode);

    private static native boolean native_set_supl_es(int es);

    private static native boolean native_set_lpp_profile(int lppProfile);

    private static native boolean native_set_gnss_pos_protocol_select(int gnssPosProtocolSelect);

    private static native boolean native_set_gps_lock(int gpsLock);

    private static native boolean native_set_emergency_supl_pdn(int emergencySuplPdn);

    private static native boolean native_set_satellite_blacklist(int[] constellations, int[] svIds);

    private static native boolean native_set_es_extension_sec(int emergencyExtensionSeconds);
}
