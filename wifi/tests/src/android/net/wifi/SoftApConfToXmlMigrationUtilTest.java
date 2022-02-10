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

package android.net.wifi;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Unit tests for {@link android.net.wifi.SoftApConfToXmlMigrationUtilTest}.
 */
@SmallTest
public class SoftApConfToXmlMigrationUtilTest {
    private static final String TEST_SSID = "SSID";
    private static final String TEST_PASSPHRASE = "TestPassphrase";
    private static final int TEST_CHANNEL = 0;
    private static final boolean TEST_HIDDEN = false;
    private static final int TEST_BAND = SoftApConfiguration.BAND_5GHZ;
    private static final int TEST_SECURITY = SoftApConfiguration.SECURITY_TYPE_WPA2_PSK;

    private static final String TEST_EXPECTED_XML_STRING =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    + "<WifiConfigStoreData>\n"
                    + "<int name=\"Version\" value=\"3\" />\n"
                    + "<SoftAp>\n"
                    + "<string name=\"SSID\">" + TEST_SSID + "</string>\n"
                    + "<int name=\"ApBand\" value=\"" + TEST_BAND + "\" />\n"
                    + "<int name=\"Channel\" value=\"" + TEST_CHANNEL + "\" />\n"
                    + "<boolean name=\"HiddenSSID\" value=\"" + TEST_HIDDEN + "\" />\n"
                    + "<int name=\"SecurityType\" value=\"" + TEST_SECURITY + "\" />\n"
                    + "<string name=\"Passphrase\">" + TEST_PASSPHRASE + "</string>\n"
                    + "<int name=\"MaxNumberOfClients\" value=\"0\" />\n"
                    + "<boolean name=\"ClientControlByUser\" value=\"false\" />\n"
                    + "<boolean name=\"AutoShutdownEnabled\" value=\"true\" />\n"
                    + "<long name=\"ShutdownTimeoutMillis\" value=\"-1\" />\n"
                    + "<BlockedClientList />\n"
                    + "<AllowedClientList />\n"
                    + "</SoftAp>\n"
                    + "</WifiConfigStoreData>\n";

    private byte[] createLegacyApConfFile(WifiConfiguration config) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outputStream);
        out.writeInt(3);
        out.writeUTF(config.SSID);
        out.writeInt(config.apBand);
        out.writeInt(config.apChannel);
        out.writeBoolean(config.hiddenSSID);
        int authType = config.getAuthType();
        out.writeInt(authType);
        if (authType != WifiConfiguration.KeyMgmt.NONE) {
            out.writeUTF(config.preSharedKey);
        }
        out.close();
        return outputStream.toByteArray();
    }

    /**
     * Generate a SoftApConfiguration based on the specified parameters.
     */
    private SoftApConfiguration setupApConfig(
            String ssid, String preSharedKey, int keyManagement, int band, int channel,
            boolean hiddenSSID) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid(ssid);
        configBuilder.setPassphrase(preSharedKey, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        if (channel == 0) {
            configBuilder.setBand(band);
        } else {
            configBuilder.setChannel(channel, band);
        }
        configBuilder.setHiddenSsid(hiddenSSID);
        return configBuilder.build();
    }

    /**
     * Generate a WifiConfiguration based on the specified parameters.
     */
    private WifiConfiguration setupWifiConfigurationApConfig(
            String ssid, String preSharedKey, int keyManagement, int band, int channel,
            boolean hiddenSSID) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = ssid;
        config.preSharedKey = preSharedKey;
        config.allowedKeyManagement.set(keyManagement);
        config.apBand = band;
        config.apChannel = channel;
        config.hiddenSSID = hiddenSSID;
        return config;
    }

    /**
     * Asserts that the WifiConfigurations equal to SoftApConfiguration.
     * This only compares the elements saved
     * for softAp used.
     */
    public static void assertWifiConfigurationEqualSoftApConfiguration(
            WifiConfiguration backup, SoftApConfiguration restore) {
        assertEquals(backup.SSID, restore.getSsid());
        assertEquals(backup.BSSID, restore.getBssid());
        assertEquals(SoftApConfToXmlMigrationUtil.convertWifiConfigBandToSoftApConfigBand(
                backup.apBand),
                restore.getBand());
        assertEquals(backup.apChannel, restore.getChannel());
        assertEquals(backup.preSharedKey, restore.getPassphrase());
        if (backup.getAuthType() == WifiConfiguration.KeyMgmt.WPA2_PSK) {
            assertEquals(SoftApConfiguration.SECURITY_TYPE_WPA2_PSK, restore.getSecurityType());
        } else {
            assertEquals(SoftApConfiguration.SECURITY_TYPE_OPEN, restore.getSecurityType());
        }
        assertEquals(backup.hiddenSSID, restore.isHiddenSsid());
    }

    /**
     * Note: This is a copy of {@link AtomicFile#readFully()} modified to use the passed in
     * {@link InputStream} which was returned using {@link AtomicFile#openRead()}.
     */
    private static byte[] readFully(InputStream stream) throws IOException {
        try {
            int pos = 0;
            int avail = stream.available();
            byte[] data = new byte[avail];
            while (true) {
                int amt = stream.read(data, pos, data.length - pos);
                if (amt <= 0) {
                    return data;
                }
                pos += amt;
                avail = stream.available();
                if (avail > data.length - pos) {
                    byte[] newData = new byte[pos + avail];
                    System.arraycopy(data, 0, newData, 0, pos);
                    data = newData;
                }
            }
        } finally {
            stream.close();
        }
    }

    /**
     * Tests conversion from legacy .conf file to XML file format.
     */
    @Test
    public void testConversion() throws Exception {
        WifiConfiguration backupConfig = setupWifiConfigurationApConfig(
                TEST_SSID,    /* SSID */
                TEST_PASSPHRASE,       /* preshared key */
                WifiConfiguration.KeyMgmt.WPA2_PSK,   /* key management */
                1,                 /* AP band (5GHz) */
                TEST_CHANNEL,                /* AP channel */
                TEST_HIDDEN            /* Hidden SSID */);
        SoftApConfiguration expectedConfig = setupApConfig(
                TEST_SSID,           /* SSID */
                TEST_PASSPHRASE,              /* preshared key */
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK,   /* security type */
                SoftApConfiguration.BAND_5GHZ, /* AP band (5GHz) */
                TEST_CHANNEL,                       /* AP channel */
                TEST_HIDDEN            /* Hidden SSID */);

        assertWifiConfigurationEqualSoftApConfiguration(backupConfig, expectedConfig);

        byte[] confBytes = createLegacyApConfFile(backupConfig);
        assertNotNull(confBytes);

        InputStream xmlStream = SoftApConfToXmlMigrationUtil.convert(
                new ByteArrayInputStream(confBytes));

        byte[] xmlBytes = readFully(xmlStream);
        assertNotNull(xmlBytes);

        assertEquals(TEST_EXPECTED_XML_STRING, new String(xmlBytes));
    }

}
