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

import android.annotation.Nullable;
import android.os.FileUtils;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Class for watchlist config operations, like setting watchlist, query if a domain
 * exists in watchlist.
 */
class WatchlistConfig {
    private static final String TAG = "WatchlistConfig";

    // Watchlist config that pushed by ConfigUpdater.
    private static final String NETWORK_WATCHLIST_DB_PATH =
            "/data/misc/network_watchlist/network_watchlist.xml";
    private static final String NETWORK_WATCHLIST_DB_FOR_TEST_PATH =
            "/data/misc/network_watchlist/network_watchlist_for_test.xml";

    private static class XmlTags {
        private static final String WATCHLIST_CONFIG = "watchlist-config";
        private static final String SHA256_DOMAIN = "sha256-domain";
        private static final String CRC32_DOMAIN = "crc32-domain";
        private static final String SHA256_IP = "sha256-ip";
        private static final String CRC32_IP = "crc32-ip";
        private static final String HASH = "hash";
    }

    private static class CrcShaDigests {
        public final HarmfulCrcs crc32s;
        public final HarmfulDigests sha256Digests;

        CrcShaDigests(HarmfulCrcs crc32s, HarmfulDigests sha256Digests) {
            this.crc32s = crc32s;
            this.sha256Digests = sha256Digests;
        }
    }

    /*
     * This is always true unless watchlist is being set by adb command, then it will be false
     * until next reboot.
     */
    private boolean mIsSecureConfig = true;

    private final static WatchlistConfig sInstance = new WatchlistConfig();
    private File mXmlFile;

    private volatile CrcShaDigests mDomainDigests;
    private volatile CrcShaDigests mIpDigests;

    public static WatchlistConfig getInstance() {
        return sInstance;
    }

    private WatchlistConfig() {
        this(new File(NETWORK_WATCHLIST_DB_PATH));
    }

    @VisibleForTesting
    protected WatchlistConfig(File xmlFile) {
        mXmlFile = xmlFile;
        reloadConfig();
    }

    /**
     * Reload watchlist by reading config file.
     */
    public void reloadConfig() {
        if (!mXmlFile.exists()) {
            // No config file
            return;
        }
        try (FileInputStream stream = new FileInputStream(mXmlFile)){
            final List<byte[]> crc32DomainList = new ArrayList<>();
            final List<byte[]> sha256DomainList = new ArrayList<>();
            final List<byte[]> crc32IpList = new ArrayList<>();
            final List<byte[]> sha256IpList = new ArrayList<>();

            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
            parser.nextTag();
            parser.require(XmlPullParser.START_TAG, null, XmlTags.WATCHLIST_CONFIG);
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                String tagName = parser.getName();
                switch (tagName) {
                    case XmlTags.CRC32_DOMAIN:
                        parseHashes(parser, tagName, crc32DomainList);
                        break;
                    case XmlTags.CRC32_IP:
                        parseHashes(parser, tagName, crc32IpList);
                        break;
                    case XmlTags.SHA256_DOMAIN:
                        parseHashes(parser, tagName, sha256DomainList);
                        break;
                    case XmlTags.SHA256_IP:
                        parseHashes(parser, tagName, sha256IpList);
                        break;
                    default:
                        Log.w(TAG, "Unknown element: " + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                }
            }
            parser.require(XmlPullParser.END_TAG, null, XmlTags.WATCHLIST_CONFIG);
            mDomainDigests = new CrcShaDigests(new HarmfulCrcs(crc32DomainList),
                    new HarmfulDigests(sha256DomainList));
            mIpDigests = new CrcShaDigests(new HarmfulCrcs(crc32IpList),
                    new HarmfulDigests(sha256IpList));
            Log.i(TAG, "Reload watchlist done");
        } catch (IllegalStateException | NullPointerException | NumberFormatException |
                XmlPullParserException | IOException | IndexOutOfBoundsException e) {
            Slog.e(TAG, "Failed parsing xml", e);
        }
    }

    private void parseHashes(XmlPullParser parser, String tagName, List<byte[]> hashList)
            throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, tagName);
        // Get all the hashes for this tag
        while (parser.nextTag() == XmlPullParser.START_TAG) {
            parser.require(XmlPullParser.START_TAG, null, XmlTags.HASH);
            byte[] hash = HexDump.hexStringToByteArray(parser.nextText());
            parser.require(XmlPullParser.END_TAG, null, XmlTags.HASH);
            hashList.add(hash);
        }
        parser.require(XmlPullParser.END_TAG, null, tagName);
    }

    public boolean containsDomain(String domain) {
        final CrcShaDigests domainDigests = mDomainDigests;
        if (domainDigests == null) {
            // mDomainDigests is not initialized
            return false;
        }
        // First it does a quick CRC32 check.
        final int crc32 = getCrc32(domain);
        if (!domainDigests.crc32s.contains(crc32)) {
            return false;
        }
        // Now we do a slow SHA256 check.
        final byte[] sha256 = getSha256(domain);
        return domainDigests.sha256Digests.contains(sha256);
    }

    public boolean containsIp(String ip) {
        final CrcShaDigests ipDigests = mIpDigests;
        if (ipDigests == null) {
            // mIpDigests is not initialized
            return false;
        }
        // First it does a quick CRC32 check.
        final int crc32 = getCrc32(ip);
        if (!ipDigests.crc32s.contains(crc32)) {
            return false;
        }
        // Now we do a slow SHA256 check.
        final byte[] sha256 = getSha256(ip);
        return ipDigests.sha256Digests.contains(sha256);
    }


    /** Get CRC32 of a string
     */
    private int getCrc32(String str) {
        final CRC32 crc = new CRC32();
        crc.update(str.getBytes());
        return (int) crc.getValue();
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

    public boolean isConfigSecure() {
        return mIsSecureConfig;
    }

    @Nullable
    /**
     * Get watchlist config SHA-256 digest.
     * Return null if watchlist config does not exist.
     */
    public byte[] getWatchlistConfigHash() {
        if (!mXmlFile.exists()) {
            return null;
        }
        try {
            return DigestUtils.getSha256Hash(mXmlFile);
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.e(TAG, "Unable to get watchlist config hash", e);
        }
        return null;
    }

    /**
     * This method will copy temporary test config and temporary override network watchlist config
     * in memory. When device is rebooted, temporary test config will be removed, and system will
     * use back the original watchlist config.
     * Also, as temporary network watchlist config is not secure, we will mark it as insecure
     * config and will be applied to testOnly applications only.
     */
    public void setTestMode(InputStream testConfigInputStream) throws IOException {
        Log.i(TAG, "Setting watchlist testing config");
        // Copy test config
        FileUtils.copyToFileOrThrow(testConfigInputStream,
                new File(NETWORK_WATCHLIST_DB_FOR_TEST_PATH));
        // Mark config as insecure, so it will be applied to testOnly applications only
        mIsSecureConfig = false;
        // Reload watchlist config using test config file
        mXmlFile = new File(NETWORK_WATCHLIST_DB_FOR_TEST_PATH);
        reloadConfig();
    }

    public void removeTestModeConfig() {
        try {
            final File f = new File(NETWORK_WATCHLIST_DB_FOR_TEST_PATH);
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to delete test config");
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final byte[] hash = getWatchlistConfigHash();
        pw.println("Watchlist config hash: " + (hash != null ? HexDump.toHexString(hash) : null));
        pw.println("Domain CRC32 digest list:");
        // mDomainDigests won't go from non-null to null so it's safe
        if (mDomainDigests != null) {
            mDomainDigests.crc32s.dump(fd, pw, args);
        }
        pw.println("Domain SHA256 digest list:");
        if (mDomainDigests != null) {
            mDomainDigests.sha256Digests.dump(fd, pw, args);
        }
        pw.println("Ip CRC32 digest list:");
        // mIpDigests won't go from non-null to null so it's safe
        if (mIpDigests != null) {
            mIpDigests.crc32s.dump(fd, pw, args);
        }
        pw.println("Ip SHA256 digest list:");
        if (mIpDigests != null) {
            mIpDigests.sha256Digests.dump(fd, pw, args);
        }
    }
}
