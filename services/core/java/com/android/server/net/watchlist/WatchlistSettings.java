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

import android.os.Environment;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.HexDump;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Class for handling watchlist settings operations, like getting differential privacy secret key.
 * Unlike WatchlistConfig, which will read configs that pushed from ConfigUpdater only, this class
 * can read and write all settings for watchlist operations.
 */
class WatchlistSettings {

    private static final String TAG = "WatchlistSettings";

    private static final String FILE_NAME = "watchlist_settings.xml";
    // Rappor requires min entropy input size = 48 bytes
    private static final int SECRET_KEY_LENGTH = 48;

    private final static WatchlistSettings sInstance = new WatchlistSettings();
    private final AtomicFile mXmlFile;

    private byte[] mPrivacySecretKey = null;

    public static WatchlistSettings getInstance() {
        return sInstance;
    }

    private WatchlistSettings() {
        this(getSystemWatchlistFile());
    }

    static File getSystemWatchlistFile() {
        return new File(Environment.getDataSystemDirectory(), FILE_NAME);
    }

    @VisibleForTesting
    protected WatchlistSettings(File xmlFile) {
        mXmlFile = new AtomicFile(xmlFile, "net-watchlist");
        reloadSettings();
        if (mPrivacySecretKey == null) {
            // Generate a new secret key and save settings
            mPrivacySecretKey = generatePrivacySecretKey();
            saveSettings();
        }
    }

    private void reloadSettings() {
        if (!mXmlFile.exists()) {
            // No settings config
            return;
        }
        try (FileInputStream stream = mXmlFile.openRead()){
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
            XmlUtils.beginDocument(parser, "network-watchlist-settings");
            final int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (parser.getName().equals("secret-key")) {
                    mPrivacySecretKey = parseSecretKey(parser);
                }
            }
            Slog.i(TAG, "Reload watchlist settings done");
        } catch (IllegalStateException | NullPointerException | NumberFormatException |
                XmlPullParserException | IOException | IndexOutOfBoundsException e) {
            Slog.e(TAG, "Failed parsing xml", e);
        }
    }

    private byte[] parseSecretKey(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "secret-key");
        byte[] key = HexDump.hexStringToByteArray(parser.nextText());
        parser.require(XmlPullParser.END_TAG, null, "secret-key");
        if (key == null || key.length != SECRET_KEY_LENGTH) {
            Log.e(TAG, "Unable to parse secret key");
            return null;
        }
        return key;
    }

    /**
     * Get DP secret key.
     * Make sure it is not exported or logged in anywhere.
     */
    synchronized byte[] getPrivacySecretKey() {
        final byte[] key = new byte[SECRET_KEY_LENGTH];
        System.arraycopy(mPrivacySecretKey, 0, key, 0, SECRET_KEY_LENGTH);
        return key;
    }

    private byte[] generatePrivacySecretKey() {
        final byte[] key = new byte[SECRET_KEY_LENGTH];
        (new SecureRandom()).nextBytes(key);
        return key;
    }

    private void saveSettings() {
        FileOutputStream stream;
        try {
            stream = mXmlFile.startWrite();
        } catch (IOException e) {
            Log.w(TAG, "Failed to write display settings: " + e);
            return;
        }
        try {
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, "network-watchlist-settings");
            out.startTag(null, "secret-key");
            out.text(HexDump.toHexString(mPrivacySecretKey));
            out.endTag(null, "secret-key");
            out.endTag(null, "network-watchlist-settings");
            out.endDocument();
            mXmlFile.finishWrite(stream);
        } catch (IOException e) {
            Log.w(TAG, "Failed to write display settings, restoring backup.", e);
            mXmlFile.failWrite(stream);
        }
    }
}
