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

package com.android.server.net.watchlist;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;

/**
 * runtest frameworks-services -c com.android.server.net.watchlist.WatchlistSettingsTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class WatchlistSettingsTests {

    private static final String TEST_XML_1 = "NetworkWatchlistTest/watchlist_settings_test1.xml";
    private static final String TEST_CC_DOMAIN = "test-cc-domain.com";
    private static final String TEST_CC_IP = "127.0.0.2";
    private static final String TEST_NOT_EXIST_CC_DOMAIN = "test-not-exist-cc-domain.com";
    private static final String TEST_NOT_EXIST_CC_IP = "1.2.3.4";
    private static final String TEST_SHA256_ONLY_DOMAIN = "test-cc-match-sha256-only.com";
    private static final String TEST_SHA256_ONLY_IP = "127.0.0.3";
    private static final String TEST_CRC32_ONLY_DOMAIN = "test-cc-match-crc32-only.com";
    private static final String TEST_CRC32_ONLY_IP = "127.0.0.4";

    private static final String TEST_NEW_CC_DOMAIN = "test-new-cc-domain.com";
    private static final byte[] TEST_NEW_CC_DOMAIN_SHA256 = HexDump.hexStringToByteArray(
            "B86F9D37425340B635F43D6BC2506630761ADA71F5E6BBDBCA4651C479F9FB43");
    private static final byte[] TEST_NEW_CC_DOMAIN_CRC32 = HexDump.hexStringToByteArray("76795BD3");

    private static final String TEST_NEW_CC_IP = "1.1.1.2";
    private static final byte[] TEST_NEW_CC_IP_SHA256 = HexDump.hexStringToByteArray(
            "721BAB5E313CF0CC76B10F9592F18B9D1B8996497501A3306A55B3AE9F1CC87C");
    private static final byte[] TEST_NEW_CC_IP_CRC32 = HexDump.hexStringToByteArray("940B8BEE");

    private Context mContext;
    private File mTestXmlFile;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mTestXmlFile =  new File(mContext.getFilesDir(), "test_watchlist_settings.xml");
        mTestXmlFile.delete();
    }

    @After
    public void tearDown() throws Exception {
        mTestXmlFile.delete();
    }

    @Test
    public void testWatchlistSettings_parsing() throws Exception {
        copyWatchlistSettingsXml(mContext, TEST_XML_1, mTestXmlFile);
        WatchlistSettings settings = new WatchlistSettings(mTestXmlFile);
        assertTrue(settings.containsDomain(TEST_CC_DOMAIN));
        assertTrue(settings.containsIp(TEST_CC_IP));
        assertFalse(settings.containsDomain(TEST_NOT_EXIST_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_NOT_EXIST_CC_IP));
        assertFalse(settings.containsDomain(TEST_SHA256_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_SHA256_ONLY_IP));
        assertFalse(settings.containsDomain(TEST_CRC32_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_CRC32_ONLY_IP));
    }

    @Test
    public void testWatchlistSettings_writeSettingsToDisk() throws Exception {
        copyWatchlistSettingsXml(mContext, TEST_XML_1, mTestXmlFile);
        WatchlistSettings settings = new WatchlistSettings(mTestXmlFile);
        settings.writeSettingsToDisk(Arrays.asList(TEST_NEW_CC_DOMAIN_CRC32),
                Arrays.asList(TEST_NEW_CC_DOMAIN_SHA256), Arrays.asList(TEST_NEW_CC_IP_CRC32),
                Arrays.asList(TEST_NEW_CC_IP_SHA256));
        // Ensure old watchlist is not in memory
        assertFalse(settings.containsDomain(TEST_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_CC_IP));
        assertFalse(settings.containsDomain(TEST_NOT_EXIST_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_NOT_EXIST_CC_IP));
        assertFalse(settings.containsDomain(TEST_SHA256_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_SHA256_ONLY_IP));
        assertFalse(settings.containsDomain(TEST_CRC32_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_CRC32_ONLY_IP));
        // Ensure new watchlist is in memory
        assertTrue(settings.containsDomain(TEST_NEW_CC_DOMAIN));
        assertTrue(settings.containsIp(TEST_NEW_CC_IP));
        // Reload settings from disk and test again
        settings = new WatchlistSettings(mTestXmlFile);
        // Ensure old watchlist is not in memory
        assertFalse(settings.containsDomain(TEST_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_CC_IP));
        assertFalse(settings.containsDomain(TEST_NOT_EXIST_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_NOT_EXIST_CC_IP));
        assertFalse(settings.containsDomain(TEST_SHA256_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_SHA256_ONLY_IP));
        assertFalse(settings.containsDomain(TEST_CRC32_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_CRC32_ONLY_IP));
        // Ensure new watchlist is in memory
        assertTrue(settings.containsDomain(TEST_NEW_CC_DOMAIN));
        assertTrue(settings.containsIp(TEST_NEW_CC_IP));
    }

    @Test
    public void testWatchlistSettings_writeSettingsToMemory() throws Exception {
        copyWatchlistSettingsXml(mContext, TEST_XML_1, mTestXmlFile);
        WatchlistSettings settings = new WatchlistSettings(mTestXmlFile);
        settings.writeSettingsToMemory(Arrays.asList(TEST_NEW_CC_DOMAIN_CRC32),
                Arrays.asList(TEST_NEW_CC_DOMAIN_SHA256), Arrays.asList(TEST_NEW_CC_IP_CRC32),
                Arrays.asList(TEST_NEW_CC_IP_SHA256));
        // Ensure old watchlist is not in memory
        assertFalse(settings.containsDomain(TEST_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_CC_IP));
        assertFalse(settings.containsDomain(TEST_NOT_EXIST_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_NOT_EXIST_CC_IP));
        assertFalse(settings.containsDomain(TEST_SHA256_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_SHA256_ONLY_IP));
        assertFalse(settings.containsDomain(TEST_CRC32_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_CRC32_ONLY_IP));
        // Ensure new watchlist is in memory
        assertTrue(settings.containsDomain(TEST_NEW_CC_DOMAIN));
        assertTrue(settings.containsIp(TEST_NEW_CC_IP));
        // Reload settings from disk and test again
        settings = new WatchlistSettings(mTestXmlFile);
        // Ensure old watchlist is in memory
        assertTrue(settings.containsDomain(TEST_CC_DOMAIN));
        assertTrue(settings.containsIp(TEST_CC_IP));
        assertFalse(settings.containsDomain(TEST_NOT_EXIST_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_NOT_EXIST_CC_IP));
        assertFalse(settings.containsDomain(TEST_SHA256_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_SHA256_ONLY_IP));
        assertFalse(settings.containsDomain(TEST_CRC32_ONLY_DOMAIN));
        assertFalse(settings.containsIp(TEST_CRC32_ONLY_IP));
        // Ensure new watchlist is not in memory
        assertFalse(settings.containsDomain(TEST_NEW_CC_DOMAIN));
        assertFalse(settings.containsIp(TEST_NEW_CC_IP));;
    }

    private static void copyWatchlistSettingsXml(Context context, String xmlAsset, File outFile)
            throws IOException {
        writeToFile(outFile, readAsset(context, xmlAsset));

    }

    private static String readAsset(Context context, String assetPath) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        context.getResources().getAssets().open(assetPath)))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private static void writeToFile(File path, String content)
            throws IOException {
        path.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(path)) {
            writer.write(content);
        }
    }
}
