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

package android.net.wifi;

import static android.os.Environment.getDataMiscDirectory;

import android.annotation.Nullable;
import android.net.MacAddress;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility class to convert the legacy softap.conf file format to the new XML format.
 * Note:
 * <li>This should be modified by the OEM if they want to migrate configuration for existing
 * devices for new softap features supported by AOSP in Android 11.
 * For ex: client allowlist/blocklist feature was already supported by some OEM's before Android 10
 * while AOSP only supported it in Android 11. </li>
 * <li>Most of this class was copied over from WifiApConfigStore class in Android 10 and
 * SoftApStoreData class in Android 11</li>
 * @hide
 */
public final class SoftApConfToXmlMigrationUtil {
    private static final String TAG = "SoftApConfToXmlMigrationUtil";

    /**
     * Directory to read the wifi config store files from under.
     */
    private static final String LEGACY_WIFI_STORE_DIRECTORY_NAME = "wifi";
    /**
     * The legacy Softap config file which contained key/value pairs.
     */
    private static final String LEGACY_AP_CONFIG_FILE = "softap.conf";

    /**
     * Pre-apex wifi shared folder.
     */
    private static File getLegacyWifiSharedDirectory() {
        return new File(getDataMiscDirectory(), LEGACY_WIFI_STORE_DIRECTORY_NAME);
    }

    /* @hide constants copied from WifiConfiguration */
    /**
     * 2GHz band.
     */
    private static final int WIFICONFIG_AP_BAND_2GHZ = 0;
    /**
     * 5GHz band.
     */
    private static final int WIFICONFIG_AP_BAND_5GHZ = 1;
    /**
     * Device is allowed to choose the optimal band (2Ghz or 5Ghz) based on device capability,
     * operating country code and current radio conditions.
     */
    private static final int WIFICONFIG_AP_BAND_ANY = -1;
    /**
     * Convert band from WifiConfiguration into SoftApConfiguration
     *
     * @param wifiConfigBand band encoded as WIFICONFIG_AP_BAND_xxxx
     * @return band as encoded as SoftApConfiguration.BAND_xxx
     */
    @VisibleForTesting
    public static int convertWifiConfigBandToSoftApConfigBand(int wifiConfigBand) {
        switch (wifiConfigBand) {
            case WIFICONFIG_AP_BAND_2GHZ:
                return SoftApConfiguration.BAND_2GHZ;
            case WIFICONFIG_AP_BAND_5GHZ:
                return SoftApConfiguration.BAND_5GHZ;
            case WIFICONFIG_AP_BAND_ANY:
                return SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_5GHZ;
            default:
                return SoftApConfiguration.BAND_2GHZ;
        }
    }

    /**
     * Load AP configuration from legacy persistent storage.
     * Note: This is deprecated and only used for migrating data once on reboot.
     */
    private static SoftApConfiguration loadFromLegacyFile(InputStream fis) {
        SoftApConfiguration config = null;
        DataInputStream in = null;
        try {
            SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
            in = new DataInputStream(new BufferedInputStream(fis));

            int version = in.readInt();
            if (version < 1 || version > 3) {
                Log.e(TAG, "Bad version on hotspot configuration file");
                return null;
            }
            configBuilder.setSsid(in.readUTF());

            if (version >= 2) {
                int band = in.readInt();
                int channel = in.readInt();
                if (channel == 0) {
                    configBuilder.setBand(
                            convertWifiConfigBandToSoftApConfigBand(band));
                } else {
                    configBuilder.setChannel(channel,
                            convertWifiConfigBandToSoftApConfigBand(band));
                }
            }
            if (version >= 3) {
                configBuilder.setHiddenSsid(in.readBoolean());
            }
            int authType = in.readInt();
            if (authType == WifiConfiguration.KeyMgmt.WPA2_PSK) {
                configBuilder.setPassphrase(in.readUTF(),
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            }
            config = configBuilder.build();
        } catch (IOException e) {
            Log.e(TAG, "Error reading hotspot configuration ",  e);
            config = null;
        } catch (IllegalArgumentException ie) {
            Log.e(TAG, "Invalid hotspot configuration ", ie);
            config = null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing hotspot configuration during read", e);
                }
            }
        }
        // NOTE: OEM's should add their customized parsing code here.
        return config;
    }

    // This is the version that Android 11 released with.
    private static final int CONFIG_STORE_DATA_VERSION = 3;

    private static final String XML_TAG_DOCUMENT_HEADER = "WifiConfigStoreData";
    private static final String XML_TAG_VERSION = "Version";
    private static final String XML_TAG_SECTION_HEADER_SOFTAP = "SoftAp";
    private static final String XML_TAG_SSID = "SSID";
    private static final String XML_TAG_BSSID = "Bssid";
    private static final String XML_TAG_CHANNEL = "Channel";
    private static final String XML_TAG_HIDDEN_SSID = "HiddenSSID";
    private static final String XML_TAG_SECURITY_TYPE = "SecurityType";
    private static final String XML_TAG_AP_BAND = "ApBand";
    private static final String XML_TAG_PASSPHRASE = "Passphrase";
    private static final String XML_TAG_MAX_NUMBER_OF_CLIENTS = "MaxNumberOfClients";
    private static final String XML_TAG_AUTO_SHUTDOWN_ENABLED = "AutoShutdownEnabled";
    private static final String XML_TAG_SHUTDOWN_TIMEOUT_MILLIS = "ShutdownTimeoutMillis";
    private static final String XML_TAG_CLIENT_CONTROL_BY_USER = "ClientControlByUser";
    private static final String XML_TAG_BLOCKED_CLIENT_LIST = "BlockedClientList";
    private static final String XML_TAG_ALLOWED_CLIENT_LIST = "AllowedClientList";
    public static final String XML_TAG_CLIENT_MACADDRESS = "ClientMacAddress";

    private static byte[] convertConfToXml(SoftApConfiguration softApConf) {
        try {
            final XmlSerializer out = new FastXmlSerializer();
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            out.setOutput(outputStream, StandardCharsets.UTF_8.name());

            // Header for the XML file.
            out.startDocument(null, true);
            out.startTag(null, XML_TAG_DOCUMENT_HEADER);
            XmlUtils.writeValueXml(CONFIG_STORE_DATA_VERSION, XML_TAG_VERSION, out);
            out.startTag(null, XML_TAG_SECTION_HEADER_SOFTAP);

            // SoftAp conf
            XmlUtils.writeValueXml(softApConf.getSsid(), XML_TAG_SSID, out);
            if (softApConf.getBssid() != null) {
                XmlUtils.writeValueXml(softApConf.getBssid().toString(), XML_TAG_BSSID, out);
            }
            XmlUtils.writeValueXml(softApConf.getBand(), XML_TAG_AP_BAND, out);
            XmlUtils.writeValueXml(softApConf.getChannel(), XML_TAG_CHANNEL, out);
            XmlUtils.writeValueXml(softApConf.isHiddenSsid(), XML_TAG_HIDDEN_SSID, out);
            XmlUtils.writeValueXml(softApConf.getSecurityType(), XML_TAG_SECURITY_TYPE, out);
            if (softApConf.getSecurityType() != SoftApConfiguration.SECURITY_TYPE_OPEN) {
                XmlUtils.writeValueXml(softApConf.getPassphrase(), XML_TAG_PASSPHRASE, out);
            }
            XmlUtils.writeValueXml(softApConf.getMaxNumberOfClients(),
                    XML_TAG_MAX_NUMBER_OF_CLIENTS, out);
            XmlUtils.writeValueXml(softApConf.isClientControlByUserEnabled(),
                    XML_TAG_CLIENT_CONTROL_BY_USER, out);
            XmlUtils.writeValueXml(softApConf.isAutoShutdownEnabled(),
                    XML_TAG_AUTO_SHUTDOWN_ENABLED, out);
            XmlUtils.writeValueXml(softApConf.getShutdownTimeoutMillis(),
                    XML_TAG_SHUTDOWN_TIMEOUT_MILLIS, out);
            out.startTag(null, XML_TAG_BLOCKED_CLIENT_LIST);
            for (MacAddress mac: softApConf.getBlockedClientList()) {
                XmlUtils.writeValueXml(mac.toString(), XML_TAG_CLIENT_MACADDRESS, out);
            }
            out.endTag(null, XML_TAG_BLOCKED_CLIENT_LIST);
            out.startTag(null, XML_TAG_ALLOWED_CLIENT_LIST);
            for (MacAddress mac: softApConf.getAllowedClientList()) {
                XmlUtils.writeValueXml(mac.toString(), XML_TAG_CLIENT_MACADDRESS, out);
            }
            out.endTag(null, XML_TAG_ALLOWED_CLIENT_LIST);

            // Footer for the XML file.
            out.endTag(null, XML_TAG_SECTION_HEADER_SOFTAP);
            out.endTag(null, XML_TAG_DOCUMENT_HEADER);
            out.endDocument();

            return outputStream.toByteArray();
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "Failed to convert softap conf to XML", e);
            return null;
        }
    }

    private SoftApConfToXmlMigrationUtil() { }

    /**
     * Read the legacy /data/misc/wifi/softap.conf file format and convert to the new XML
     * format understood by WifiConfigStore.
     * Note: Used for unit testing.
     */
    @VisibleForTesting
    @Nullable
    public static InputStream convert(InputStream fis) {
        SoftApConfiguration softApConf = loadFromLegacyFile(fis);
        if (softApConf == null) return null;

        byte[] xmlBytes = convertConfToXml(softApConf);
        if (xmlBytes == null) return null;

        return new ByteArrayInputStream(xmlBytes);
    }

    /**
     * Read the legacy /data/misc/wifi/softap.conf file format and convert to the new XML
     * format understood by WifiConfigStore.
     */
    @Nullable
    public static InputStream convert() {
        File file = new File(getLegacyWifiSharedDirectory(), LEGACY_AP_CONFIG_FILE);
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (fis == null) return null;
        return convert(fis);
    }

    /**
     * Remove the legacy /data/misc/wifi/softap.conf file.
     */
    @Nullable
    public static void remove() {
        File file = new File(getLegacyWifiSharedDirectory(), LEGACY_AP_CONFIG_FILE);
        file.delete();
    }
}
