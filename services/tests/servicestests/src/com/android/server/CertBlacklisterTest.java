/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.content.Intent;
import android.test.AndroidTestCase;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;

import libcore.io.IoUtils;

/**
 * Tests for {@link com.android.server.CertBlacklister}
 */
public class CertBlacklisterTest extends AndroidTestCase {

    private static final String DENYLIST_ROOT = System.getenv("ANDROID_DATA") + "/misc/keychain/";

    public static final String PUBKEY_PATH = DENYLIST_ROOT + "pubkey_blacklist.txt";
    public static final String SERIAL_PATH = DENYLIST_ROOT + "serial_blacklist.txt";

    public static final String PUBKEY_KEY = "pubkey_blacklist";
    public static final String SERIAL_KEY = "serial_blacklist";

    private void overrideSettings(String key, String value) throws Exception {
        Settings.Secure.putString(mContext.getContentResolver(), key, value);
        Thread.sleep(1000);
    }

    public void testClearBlacklistPubkey() throws Exception {
        // clear the gservices setting for a clean slate
        overrideSettings(PUBKEY_KEY, "");
        // read the contents of the pubkey denylist
        String blacklist = IoUtils.readFileAsString(PUBKEY_PATH);
        // Verify that it's empty
        assertEquals("", blacklist);
    }

    public void testSetBlacklistPubkey() throws Exception {
        // build a new thing to denylist
        String badPubkey = "7ccabd7db47e94a5759901b6a7dfd45d1c091ccc";
        // add the gservices override
        overrideSettings(PUBKEY_KEY, badPubkey);
        // check the contents again
        String blacklist = IoUtils.readFileAsString(PUBKEY_PATH);
        // make sure that we're equal to the string we sent out
        assertEquals(badPubkey, blacklist);
    }

    public void testChangeBlacklistPubkey() throws Exception {
        String badPubkey = "6ccabd7db47e94a5759901b6a7dfd45d1c091ccc";
        overrideSettings(PUBKEY_KEY, badPubkey);
        badPubkey = "6ccabd7db47e94a5759901b6a7dfd45d1c091cce";
        overrideSettings(PUBKEY_KEY, badPubkey);
        String blacklist = IoUtils.readFileAsString(PUBKEY_PATH);
        assertEquals(badPubkey, blacklist);
    }

    public void testMultiBlacklistPubkey() throws Exception {
        String badPubkey = "6ccabd7db47e94a5759901b6a7dfd45d1c091ccc,6ccabd7db47e94a5759901b6a7dfd45d1c091ccd";
        overrideSettings(PUBKEY_KEY, badPubkey);
        String blacklist = IoUtils.readFileAsString(PUBKEY_PATH);
        assertEquals(badPubkey, blacklist);
    }

    public void testInvalidMultiBlacklistPubkey() throws Exception {
        String badPubkey = "6ccabd7db47e94a5759901b6a7dfd45d1c091ccc,ZZZZZ,6ccabd7db47e94a5759901b6a7dfd45d1c091ccd";
        overrideSettings(PUBKEY_KEY, badPubkey);
        String blacklist = IoUtils.readFileAsString(PUBKEY_PATH);
        assertEquals(badPubkey, blacklist);
    }

    public void testInvalidCharsBlacklistPubkey() throws Exception {
        String badPubkey = "\n6ccabd7db47e94a5759901b6a7dfd45d1c091ccc,-ZZZZZ,+6ccabd7db47e94a5759901b6a7dfd45d1c091ccd";
        overrideSettings(PUBKEY_KEY, badPubkey);
        String blacklist = IoUtils.readFileAsString(PUBKEY_PATH);
        assertEquals(badPubkey, blacklist);
    }

    public void testLotsOfBlacklistedPubkeys() throws Exception {
        StringBuilder bl = new StringBuilder();
        for (int i=0; i < 1000; i++) {
            bl.append("6ccabd7db47e94a5759901b6a7dfd45d1c091ccc,");
        }
        overrideSettings(PUBKEY_KEY, bl.toString());
        String blacklist = IoUtils.readFileAsString(PUBKEY_PATH);
        assertEquals(bl.toString(), blacklist);
    }

    public void testClearBlacklistSerial() throws Exception {
        // clear the gservices setting for a clean slate
        overrideSettings(SERIAL_KEY, "");
        // read the contents of the pubkey denylist
        String blacklist = IoUtils.readFileAsString(SERIAL_PATH);
        // Verify that it's empty
        assertEquals("", blacklist);
    }

    public void testSetBlacklistSerial() throws Exception {
        // build a new thing to denylist
        String badSerial = "22e514121e61c643b1e9b06bd4b9f7d0";
        // add the gservices override
        overrideSettings(SERIAL_KEY, badSerial);
        // check the contents again
        String blacklist = IoUtils.readFileAsString(SERIAL_PATH);
        // make sure that we're equal to the string we sent out
        assertEquals(badSerial, blacklist);
    }

    public void testChangeBlacklistSerial() throws Exception {
        String badSerial = "22e514121e61c643b1e9b06bd4b9f7d0";
        overrideSettings(SERIAL_KEY, badSerial);
        badSerial = "22e514121e61c643b1e9b06bd4b9f7d1";
        overrideSettings(SERIAL_KEY, badSerial);
        String blacklist = IoUtils.readFileAsString(SERIAL_PATH);
        assertEquals(badSerial, blacklist);
    }

    public void testMultiBlacklistSerial() throws Exception {
        String badSerial = "22e514121e61c643b1e9b06bd4b9f7d0,22e514121e61c643b1e9b06bd4b9f7d1";
        overrideSettings(SERIAL_KEY, badSerial);
        String blacklist = IoUtils.readFileAsString(SERIAL_PATH);
        assertEquals(badSerial, blacklist);
    }

    public void testInvalidMultiBlacklistSerial() throws Exception {
        String badSerial = "22e514121e61c643b1e9b06bd4b9f7d0,ZZZZ,22e514121e61c643b1e9b06bd4b9f7d1";
        overrideSettings(SERIAL_KEY, badSerial);
        String blacklist = IoUtils.readFileAsString(SERIAL_PATH);
        assertEquals(badSerial, blacklist);
    }

    public void testInvalidCharsBlacklistSerial() throws Exception {
        String badSerial = "\n22e514121e61c643b1e9b06bd4b9f7d0,-ZZZZ,+22e514121e61c643b1e9b06bd4b9f7d1";
        overrideSettings(SERIAL_KEY, badSerial);
        String blacklist = IoUtils.readFileAsString(SERIAL_PATH);
        assertEquals(badSerial, blacklist);
    }

    public void testLotsOfBlacklistedSerials() throws Exception {
        StringBuilder bl = new StringBuilder();
        for (int i=0; i < 1000; i++) {
            bl.append("22e514121e61c643b1e9b06bd4b9f7d0,");
        }
        overrideSettings(SERIAL_KEY, bl.toString());
        String blacklist = IoUtils.readFileAsString(SERIAL_PATH);
        assertEquals(bl.toString(), blacklist);
    }
}
