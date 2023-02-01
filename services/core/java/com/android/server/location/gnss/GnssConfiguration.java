/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

import android.content.Context;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.FrameworkStatsLog;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
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
public class GnssConfiguration {
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
    static final String CONFIG_NFW_PROXY_APPS = "NFW_PROXY_APPS";
    private static final String CONFIG_ENABLE_PSDS_PERIODIC_DOWNLOAD =
            "ENABLE_PSDS_PERIODIC_DOWNLOAD";
    private static final String CONFIG_ENABLE_ACTIVE_SIM_EMERGENCY_SUPL =
            "ENABLE_ACTIVE_SIM_EMERGENCY_SUPL";
    static final String CONFIG_LONGTERM_PSDS_SERVER_1 = "LONGTERM_PSDS_SERVER_1";
    static final String CONFIG_LONGTERM_PSDS_SERVER_2 = "LONGTERM_PSDS_SERVER_2";
    static final String CONFIG_LONGTERM_PSDS_SERVER_3 = "LONGTERM_PSDS_SERVER_3";
    static final String CONFIG_NORMAL_PSDS_SERVER = "NORMAL_PSDS_SERVER";
    static final String CONFIG_REALTIME_PSDS_SERVER = "REALTIME_PSDS_SERVER";
    // Limit on NI emergency mode time extension after emergency sessions ends
    private static final int MAX_EMERGENCY_MODE_EXTENSION_SECONDS = 300;  // 5 minute maximum

    // Persist property for LPP_PROFILE
    static final String LPP_PROFILE = "persist.sys.gps.lpp";

    // Represents an HAL interface version. Instances of this class are created in the JNI layer
    // and returned through native methods.
    static class HalInterfaceVersion {
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
    private final Properties mProperties;

    private int mEsExtensionSec = 0;

    private final Context mContext;

    public GnssConfiguration(Context context) {
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
    public int getEsExtensionSec() {
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
    public int getSuplEs(int defaultSuplEs) {
        return getIntConfig(CONFIG_SUPL_ES, defaultSuplEs);
    }

    /**
     * Returns the value of config parameter LPP_PROFILE or {@code null} if no value is
     * provided.
     */
    String getLppProfile() {
        return mProperties.getProperty(CONFIG_LPP_PROFILE);
    }

    /**
     * Returns the list of proxy apps from the value of config parameter NFW_PROXY_APPS.
     */
    List<String> getProxyApps() {
        // Space separated list of Android proxy app package names.
        String proxyAppsStr = mProperties.getProperty(CONFIG_NFW_PROXY_APPS);
        if (TextUtils.isEmpty(proxyAppsStr)) {
            return Collections.emptyList();
        }

        String[] proxyAppsArray = proxyAppsStr.trim().split("\\s+");
        if (proxyAppsArray.length == 0) {
            return Collections.emptyList();
        }

        return Arrays.asList(proxyAppsArray);
    }

    /**
     * Returns true if PSDS periodic download is enabled, false otherwise.
     */
    boolean isPsdsPeriodicDownloadEnabled() {
        return getBooleanConfig(CONFIG_ENABLE_PSDS_PERIODIC_DOWNLOAD, false);
    }

    /**
     * Returns true if during an emergency call, the GnssConfiguration of the activeSubId will be
     * injected for the emergency SUPL flow; Returns false otherwise. Default false if not set.
     */
    boolean isActiveSimEmergencySuplEnabled() {
        return getBooleanConfig(CONFIG_ENABLE_ACTIVE_SIM_EMERGENCY_SUPL, false);
    }

    /**
     * Returns true if a long-term PSDS server is configured.
     */
    boolean isLongTermPsdsServerConfigured() {
        return (mProperties.getProperty(CONFIG_LONGTERM_PSDS_SERVER_1) != null
                || mProperties.getProperty(CONFIG_LONGTERM_PSDS_SERVER_2) != null
                || mProperties.getProperty(CONFIG_LONGTERM_PSDS_SERVER_3) != null);
    }

    /**
     * Updates the GNSS HAL satellite denylist.
     */
    void setSatelliteBlocklist(int[] constellations, int[] svids) {
        native_set_satellite_blocklist(constellations, svids);
    }

    HalInterfaceVersion getHalInterfaceVersion() {
        return native_get_gnss_configuration_version();
    }

    interface SetCarrierProperty {
        boolean set(int value);
    }

    /**
     * Loads the GNSS properties from carrier config file followed by the properties from
     * gps debug config file, and injects the GNSS properties into the HAL.
     */
    void reloadGpsProperties() {
        reloadGpsProperties(/* inEmergency= */ false, /* activeSubId= */ -1);
    }

    /**
     * Loads the GNSS properties from carrier config file followed by the properties from
     * gps debug config file, and injects the GNSS properties into the HAL.
     */
    void reloadGpsProperties(boolean inEmergency, int activeSubId) {
        if (DEBUG) {
            Log.d(TAG,
                    "Reset GPS properties, previous size = " + mProperties.size() + ", inEmergency:"
                            + inEmergency + ", activeSubId=" + activeSubId);
        }
        loadPropertiesFromCarrierConfig(inEmergency, activeSubId);

        if (isSimAbsent(mContext)) {
            // Use the default SIM's LPP profile when SIM is absent.
            String lpp_prof = SystemProperties.get(LPP_PROFILE);
            if (!TextUtils.isEmpty(lpp_prof)) {
                // override default value of this if lpp_prof is not empty
                mProperties.setProperty(CONFIG_LPP_PROFILE, lpp_prof);
            }
        }

        /*
         * Overlay carrier properties from a debug configuration file.
         */
        loadPropertiesFromGpsDebugConfig(mProperties);
        mEsExtensionSec = getRangeCheckedConfigEsExtensionSec();

        logConfigurations();

        final HalInterfaceVersion gnssConfigurationIfaceVersion = getHalInterfaceVersion();
        if (gnssConfigurationIfaceVersion != null) {
            // Set to a range checked value.
            if (isConfigEsExtensionSecSupported(gnssConfigurationIfaceVersion)
                    && !native_set_es_extension_sec(mEsExtensionSec)) {
                Log.e(TAG, "Unable to set " + CONFIG_ES_EXTENSION_SEC + ": " + mEsExtensionSec);
            }

            Map<String, SetCarrierProperty> map = new HashMap<String, SetCarrierProperty>();

            map.put(CONFIG_SUPL_VER, GnssConfiguration::native_set_supl_version);
            map.put(CONFIG_SUPL_MODE, GnssConfiguration::native_set_supl_mode);

            if (isConfigSuplEsSupported(gnssConfigurationIfaceVersion)) {
                map.put(CONFIG_SUPL_ES, GnssConfiguration::native_set_supl_es);
            }

            map.put(CONFIG_LPP_PROFILE, GnssConfiguration::native_set_lpp_profile);
            map.put(CONFIG_A_GLONASS_POS_PROTOCOL_SELECT,
                    GnssConfiguration::native_set_gnss_pos_protocol_select);
            map.put(CONFIG_USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL,
                    GnssConfiguration::native_set_emergency_supl_pdn);

            if (isConfigGpsLockSupported(gnssConfigurationIfaceVersion)) {
                map.put(CONFIG_GPS_LOCK, GnssConfiguration::native_set_gps_lock);
            }

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
        FrameworkStatsLog.write(FrameworkStatsLog.GNSS_CONFIGURATION_REPORTED,
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
    void loadPropertiesFromCarrierConfig(boolean inEmergency, int activeSubId) {
        CarrierConfigManager configManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            return;
        }

        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (inEmergency && activeSubId >= 0) {
            subId = activeSubId;
        }
        PersistableBundle configs = SubscriptionManager.isValidSubscriptionId(subId)
                ? configManager.getConfigForSubId(subId) : configManager.getConfig();
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

    private boolean getBooleanConfig(String configParameter, boolean defaultValue) {
        String valueString = mProperties.getProperty(configParameter);
        if (TextUtils.isEmpty(valueString)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(valueString);
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

    private static boolean isSimAbsent(Context context) {
        TelephonyManager phone = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        return phone.getSimState() == TelephonyManager.SIM_STATE_ABSENT;
    }

    private static native HalInterfaceVersion native_get_gnss_configuration_version();

    private static native boolean native_set_supl_version(int version);

    private static native boolean native_set_supl_mode(int mode);

    private static native boolean native_set_supl_es(int es);

    private static native boolean native_set_lpp_profile(int lppProfile);

    private static native boolean native_set_gnss_pos_protocol_select(int gnssPosProtocolSelect);

    private static native boolean native_set_gps_lock(int gpsLock);

    private static native boolean native_set_emergency_supl_pdn(int emergencySuplPdn);

    private static native boolean native_set_satellite_blocklist(int[] constellations, int[] svIds);

    private static native boolean native_set_es_extension_sec(int emergencyExtensionSeconds);
}
