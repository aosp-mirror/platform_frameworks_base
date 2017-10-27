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
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
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

    // Watchlist config that pushed by ConfigUpdater.
    private static final String NETWORK_WATCHLIST_DB_PATH =
            "/data/misc/network_watchlist/network_watchlist.xml";

    private static class XmlTags {
        private static final String WATCHLIST_SETTINGS = "watchlist-settings";
        private static final String SHA256_DOMAIN = "sha256-domain";
        private static final String CRC32_DOMAIN = "crc32-domain";
        private static final String SHA256_IP = "sha256-ip";
        private static final String CRC32_IP = "crc32-ip";
        private static final String HASH = "hash";
    }

    private static class CrcShaDigests {
        final HarmfulDigests crc32Digests;
        final HarmfulDigests sha256Digests;

        public CrcShaDigests(HarmfulDigests crc32Digests, HarmfulDigests sha256Digests) {
            this.crc32Digests = crc32Digests;
            this.sha256Digests = sha256Digests;
        }
    }

    private final static WatchlistSettings sInstance = new WatchlistSettings();
    private final AtomicFile mXmlFile;

    private volatile CrcShaDigests mDomainDigests;
    private volatile CrcShaDigests mIpDigests;

    public static WatchlistSettings getInstance() {
        return sInstance;
    }

    private WatchlistSettings() {
        this(new File(NETWORK_WATCHLIST_DB_PATH));
    }

    @VisibleForTesting
    protected WatchlistSettings(File xmlFile) {
        mXmlFile = new AtomicFile(xmlFile);
        reloadSettings();
    }

    public void reloadSettings() {
        try (FileInputStream stream = mXmlFile.openRead()){

            final List<byte[]> crc32DomainList = new ArrayList<>();
            final List<byte[]> sha256DomainList = new ArrayList<>();
            final List<byte[]> crc32IpList = new ArrayList<>();
            final List<byte[]> sha256IpList = new ArrayList<>();

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
            Log.i(TAG, "Reload watchlist done");
        } catch (IllegalStateException | NullPointerException | NumberFormatException |
                XmlPullParserException | IOException | IndexOutOfBoundsException e) {
            Slog.e(TAG, "Failed parsing xml", e);
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
     * Write network watchlist settings to memory.
     */
    public void writeSettingsToMemory(List<byte[]> newCrc32DomainList,
            List<byte[]> newSha256DomainList,
            List<byte[]> newCrc32IpList,
            List<byte[]> newSha256IpList) {
        mDomainDigests = new CrcShaDigests(new HarmfulDigests(newCrc32DomainList),
                new HarmfulDigests(newSha256DomainList));
        mIpDigests = new CrcShaDigests(new HarmfulDigests(newCrc32IpList),
                new HarmfulDigests(newSha256IpList));
    }

    public boolean containsDomain(String domain) {
        final CrcShaDigests domainDigests = mDomainDigests;
        if (domainDigests == null) {
            Slog.wtf(TAG, "domainDigests should not be null");
            return false;
        }
        // First it does a quick CRC32 check.
        final byte[] crc32 = getCrc32(domain);
        if (!domainDigests.crc32Digests.contains(crc32)) {
            return false;
        }
        // Now we do a slow SHA256 check.
        final byte[] sha256 = getSha256(domain);
        return domainDigests.sha256Digests.contains(sha256);
    }

    public boolean containsIp(String ip) {
        final CrcShaDigests ipDigests = mIpDigests;
        if (ipDigests == null) {
            Slog.wtf(TAG, "ipDigests should not be null");
            return false;
        }
        // First it does a quick CRC32 check.
        final byte[] crc32 = getCrc32(ip);
        if (!ipDigests.crc32Digests.contains(crc32)) {
            return false;
        }
        // Now we do a slow SHA256 check.
        final byte[] sha256 = getSha256(ip);
        return ipDigests.sha256Digests.contains(sha256);
    }


    /** Get CRC32 of a string
     *
     * TODO: Review if we should use CRC32 or other algorithms
     */
    private byte[] getCrc32(String str) {
        final CRC32 crc = new CRC32();
        crc.update(str.getBytes());
        final long tmp = crc.getValue();
        return new byte[]{(byte) (tmp >> 24 & 255), (byte) (tmp >> 16 & 255),
                (byte) (tmp >> 8 & 255), (byte) (tmp & 255)};
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
        mDomainDigests.crc32Digests.dump(fd, pw, args);
        pw.println("Domain SHA256 digest list:");
        mDomainDigests.sha256Digests.dump(fd, pw, args);
        pw.println("Ip CRC32 digest list:");
        mIpDigests.crc32Digests.dump(fd, pw, args);
        pw.println("Ip SHA256 digest list:");
        mIpDigests.sha256Digests.dump(fd, pw, args);
    }
}
