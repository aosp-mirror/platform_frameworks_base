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

import android.os.Environment;
import android.util.AtomicFile;
import android.util.Log;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A util class to do watchlist settings operations, like setting watchlist, query if a domain
 * exists in watchlist.
 */
class WatchlistSettings {
    private static final String TAG = "WatchlistSettings";

    // Settings xml will be stored in /data/system/network_watchlist/watchlist_settings.xml
    static final String SYSTEM_WATCHLIST_DIR = "network_watchlist";

    private static final String WATCHLIST_XML_FILE = "watchlist_settings.xml";

    private static class XmlTags {
        private static final String WATCHLIST_SETTINGS = "watchlist-settings";
        private static final String SHA256_DOMAIN = "sha256-domain";
        private static final String CRC32_DOMAIN = "crc32-domain";
        private static final String SHA256_IP = "sha256-ip";
        private static final String CRC32_IP = "crc32-ip";
        private static final String HASH = "hash";
    }

    private static WatchlistSettings sInstance = new WatchlistSettings();
    private final AtomicFile mXmlFile;
    private final Object mLock = new Object();
    private HarmfulDigests mCrc32DomainDigests = new HarmfulDigests(new ArrayList<>());
    private HarmfulDigests mSha256DomainDigests = new HarmfulDigests(new ArrayList<>());
    private HarmfulDigests mCrc32IpDigests = new HarmfulDigests(new ArrayList<>());
    private HarmfulDigests mSha256IpDigests = new HarmfulDigests(new ArrayList<>());

    public static synchronized WatchlistSettings getInstance() {
        return sInstance;
    }

    private WatchlistSettings() {
        this(getSystemWatchlistFile(WATCHLIST_XML_FILE));
    }

    @VisibleForTesting
    protected WatchlistSettings(File xmlFile) {
        mXmlFile = new AtomicFile(xmlFile);
        readSettingsLocked();
    }

    static File getSystemWatchlistFile(String filename) {
        final File dataSystemDir = Environment.getDataSystemDirectory();
        final File systemWatchlistDir = new File(dataSystemDir, SYSTEM_WATCHLIST_DIR);
        systemWatchlistDir.mkdirs();
        return new File(systemWatchlistDir, filename);
    }

    private void readSettingsLocked() {
        synchronized (mLock) {
            FileInputStream stream;
            try {
                stream = mXmlFile.openRead();
            } catch (FileNotFoundException e) {
                Log.i(TAG, "No watchlist settings: " + mXmlFile.getBaseFile().getAbsolutePath());
                return;
            }

            final List<byte[]> crc32DomainList = new ArrayList<>();
            final List<byte[]> sha256DomainList = new ArrayList<>();
            final List<byte[]> crc32IpList = new ArrayList<>();
            final List<byte[]> sha256IpList = new ArrayList<>();

            try {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, StandardCharsets.UTF_8.name());
                parser.nextTag();
                parser.require(XmlPullParser.START_TAG, null, XmlTags.WATCHLIST_SETTINGS);
                while (parser.nextTag() == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    switch (tagName) {
                        case XmlTags.CRC32_DOMAIN:
                            parseHash(parser, tagName, crc32DomainList);
                            break;
                        case XmlTags.CRC32_IP:
                            parseHash(parser, tagName, crc32IpList);
                            break;
                        case XmlTags.SHA256_DOMAIN:
                            parseHash(parser, tagName, sha256DomainList);
                            break;
                        case XmlTags.SHA256_IP:
                            parseHash(parser, tagName, sha256IpList);
                            break;
                        default:
                            Log.w(TAG, "Unknown element: " + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                    }
                }
                parser.require(XmlPullParser.END_TAG, null, XmlTags.WATCHLIST_SETTINGS);
                writeSettingsToMemory(crc32DomainList, sha256DomainList, crc32IpList, sha256IpList);
            } catch (IllegalStateException | NullPointerException | NumberFormatException |
                    XmlPullParserException | IOException | IndexOutOfBoundsException e) {
                Log.w(TAG, "Failed parsing " + e);
            } finally {
                try {
                    stream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void parseHash(XmlPullParser parser, String tagName, List<byte[]> hashSet)
            throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, tagName);
        while (parser.nextTag() == XmlPullParser.START_TAG) {
            parser.require(XmlPullParser.START_TAG, null, XmlTags.HASH);
            byte[] hash = HexDump.hexStringToByteArray(parser.nextText());
            parser.require(XmlPullParser.END_TAG, null, XmlTags.HASH);
            hashSet.add(hash);
        }
        parser.require(XmlPullParser.END_TAG, null, tagName);
    }

    /**
     * Write network watchlist settings to disk.
     * Adb should not use it, should use writeSettingsToMemory directly instead.
     */
    public void writeSettingsToDisk(List<byte[]> newCrc32DomainList,
            List<byte[]> newSha256DomainList,
            List<byte[]> newCrc32IpList,
            List<byte[]> newSha256IpList) {
        synchronized (mLock) {
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
                out.startTag(null, XmlTags.WATCHLIST_SETTINGS);

                writeHashSetToXml(out, XmlTags.SHA256_DOMAIN, newSha256DomainList);
                writeHashSetToXml(out, XmlTags.SHA256_IP, newSha256IpList);
                writeHashSetToXml(out, XmlTags.CRC32_DOMAIN, newCrc32DomainList);
                writeHashSetToXml(out, XmlTags.CRC32_IP, newCrc32IpList);

                out.endTag(null, XmlTags.WATCHLIST_SETTINGS);
                out.endDocument();
                mXmlFile.finishWrite(stream);
                writeSettingsToMemory(newCrc32DomainList, newSha256DomainList, newCrc32IpList,
                        newSha256IpList);
            } catch (IOException e) {
                Log.w(TAG, "Failed to write display settings, restoring backup.", e);
                mXmlFile.failWrite(stream);
            }
        }
    }

    /**
     * Write network watchlist settings to memory.
     */
    public void writeSettingsToMemory(List<byte[]> newCrc32DomainList,
            List<byte[]> newSha256DomainList,
            List<byte[]> newCrc32IpList,
            List<byte[]> newSha256IpList) {
        synchronized (mLock) {
            mCrc32DomainDigests = new HarmfulDigests(newCrc32DomainList);
            mCrc32IpDigests = new HarmfulDigests(newCrc32IpList);
            mSha256DomainDigests = new HarmfulDigests(newSha256DomainList);
            mSha256IpDigests = new HarmfulDigests(newSha256IpList);
        }
    }

    private static void writeHashSetToXml(XmlSerializer out, String tagName, List<byte[]> hashSet)
            throws IOException {
        out.startTag(null, tagName);
        for (byte[] hash : hashSet) {
            out.startTag(null, XmlTags.HASH);
            out.text(HexDump.toHexString(hash));
            out.endTag(null, XmlTags.HASH);
        }
        out.endTag(null, tagName);
    }

    public boolean containsDomain(String domain) {
        // First it does a quick CRC32 check.
        final byte[] crc32 = getCrc32(domain);
        if (!mCrc32DomainDigests.contains(crc32)) {
            return false;
        }
        // Now we do a slow SHA256 check.
        final byte[] sha256 = getSha256(domain);
        return mSha256DomainDigests.contains(sha256);
    }

    public boolean containsIp(String ip) {
        // First it does a quick CRC32 check.
        final byte[] crc32 = getCrc32(ip);
        if (!mCrc32IpDigests.contains(crc32)) {
            return false;
        }
        // Now we do a slow SHA256 check.
        final byte[] sha256 = getSha256(ip);
        return mSha256IpDigests.contains(sha256);
    }


    /** Get CRC32 of a string */
    private byte[] getCrc32(String str) {
        final CRC32 crc = new CRC32();
        crc.update(str.getBytes());
        final long tmp = crc.getValue();
        return new byte[]{(byte)(tmp >> 24 & 255), (byte)(tmp >> 16 & 255),
                (byte)(tmp >> 8 & 255), (byte)(tmp & 255)};
    }

    /** Get SHA256 of a string */
    private byte[] getSha256(String str) {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA256");
        } catch (NoSuchAlgorithmException e) {
            /* can't happen */
            return null;
        }
        messageDigest.update(str.getBytes());
        return messageDigest.digest();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Domain CRC32 digest list:");
        mCrc32DomainDigests.dump(fd, pw, args);
        pw.println("Domain SHA256 digest list:");
        mSha256DomainDigests.dump(fd, pw, args);
        pw.println("Ip CRC32 digest list:");
        mCrc32IpDigests.dump(fd, pw, args);
        pw.println("Ip SHA256 digest list:");
        mSha256IpDigests.dump(fd, pw, args);
    }
}
