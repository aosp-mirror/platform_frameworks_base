/*
 * Copyright 2017 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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

/**
 * runtest frameworks-services -c com.android.server.net.watchlist.WatchlistSettingsTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class WatchlistSettingsTests {

    private static final String TEST_XML_1 = "NetworkWatchlistTest/watchlist_settings_test1.xml";
    private static final String TEST_XML_2 = "NetworkWatchlistTest/watchlist_settings_test2.xml";
    private static final String HARD_CODED_SECRET_KEY = "1234567890ABCDEF1234567890ABCDEF"
            + "1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF";

    private Context mContext;
    private File mTestXmlFile;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mTestXmlFile = new File(mContext.getFilesDir(), "test_settings_config.xml");
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
        assertEquals(HARD_CODED_SECRET_KEY, HexDump.toHexString(settings.getPrivacySecretKey()));
        // Try again.
        assertEquals(HARD_CODED_SECRET_KEY, HexDump.toHexString(settings.getPrivacySecretKey()));
    }

    @Test
    public void testWatchlistSettings_parsingWithoutKey() throws Exception {
        copyWatchlistSettingsXml(mContext, TEST_XML_2, mTestXmlFile);
        WatchlistSettings settings = new WatchlistSettings(mTestXmlFile);
        final String tmpKey1 = HexDump.toHexString(settings.getPrivacySecretKey());
        assertNotEquals(HARD_CODED_SECRET_KEY, tmpKey1);
        assertEquals(96, tmpKey1.length());
        // Try again to make sure it's the same.
        assertEquals(tmpKey1, HexDump.toHexString(settings.getPrivacySecretKey()));
        // Create new settings object again to make sure it can get the new saved key.
        settings = new WatchlistSettings(mTestXmlFile);
        assertEquals(tmpKey1, HexDump.toHexString(settings.getPrivacySecretKey()));
    }

    @Test
    public void testWatchlistSettings_noExistingXml() throws Exception {
        WatchlistSettings settings = new WatchlistSettings(mTestXmlFile);
        final String tmpKey1 = HexDump.toHexString(settings.getPrivacySecretKey());
        assertNotEquals(HARD_CODED_SECRET_KEY, tmpKey1);
        assertEquals(96, tmpKey1.length());
        // Try again to make sure it's the same.
        assertEquals(tmpKey1, HexDump.toHexString(settings.getPrivacySecretKey()));
        // Create new settings object again to make sure it can get the new saved key.
        settings = new WatchlistSettings(mTestXmlFile);
        assertEquals(tmpKey1, HexDump.toHexString(settings.getPrivacySecretKey()));
        // Delete xml and generate key again, to make sure key is randomly generated.
        mTestXmlFile.delete();
        settings = new WatchlistSettings(mTestXmlFile);
        final String tmpKey2 = HexDump.toHexString(settings.getPrivacySecretKey());
        assertNotEquals(HARD_CODED_SECRET_KEY, tmpKey2);
        assertNotEquals(tmpKey1, tmpKey2);
        assertEquals(96, tmpKey2.length());
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
