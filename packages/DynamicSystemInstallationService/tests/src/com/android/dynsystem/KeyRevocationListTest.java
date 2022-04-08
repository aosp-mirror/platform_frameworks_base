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

package com.android.dynsystem;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.json.JSONException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * A test for KeyRevocationList.java
 */
@RunWith(AndroidJUnit4.class)
public class KeyRevocationListTest {

    private static final String TAG = "KeyRevocationListTest";

    private static Context sContext;

    private static String sBlacklistJsonString;

    @BeforeClass
    public static void setUpClass() throws Exception {
        sContext = InstrumentationRegistry.getInstrumentation().getContext();
        sBlacklistJsonString =
                sContext.getString(com.android.dynsystem.tests.R.string.blacklist_json_string);
    }

    @Test
    @SmallTest
    public void testFromJsonString() throws JSONException {
        KeyRevocationList blacklist;
        blacklist = KeyRevocationList.fromJsonString(sBlacklistJsonString);
        Assert.assertNotNull(blacklist);
        Assert.assertFalse(blacklist.mEntries.isEmpty());
        blacklist = KeyRevocationList.fromJsonString("{}");
        Assert.assertNotNull(blacklist);
        Assert.assertTrue(blacklist.mEntries.isEmpty());
    }

    @Test
    @SmallTest
    public void testFromUrl() throws IOException, JSONException {
        URLConnection mockConnection = mock(URLConnection.class);
        doReturn(new ByteArrayInputStream(sBlacklistJsonString.getBytes()))
                .when(mockConnection).getInputStream();
        URL mockUrl = new URL(
                "http",     // protocol
                "foo.bar",  // host
                80,         // port
                "baz",      // file
                new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) {
                        return mockConnection;
                    }
                });
        URL mockBadUrl = new URL(
                "http",     // protocol
                "foo.bar",  // host
                80,         // port
                "baz",      // file
                new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) throws IOException {
                        throw new IOException();
                    }
                });

        KeyRevocationList blacklist = KeyRevocationList.fromUrl(mockUrl);
        Assert.assertNotNull(blacklist);
        Assert.assertFalse(blacklist.mEntries.isEmpty());

        blacklist = null;
        try {
            blacklist = KeyRevocationList.fromUrl(mockBadUrl);
            // Up should throw, down should be unreachable
            Assert.fail("Expected IOException not thrown");
        } catch (IOException e) {
            // This is expected, do nothing
        }
        Assert.assertNull(blacklist);
    }

    @Test
    @SmallTest
    public void testIsRevoked() {
        KeyRevocationList blacklist = new KeyRevocationList();
        blacklist.addEntry("key1", "REVOKED", "reason for key1");

        KeyRevocationList.RevocationStatus revocationStatus =
                blacklist.getRevocationStatusForKey("key1");
        Assert.assertNotNull(revocationStatus);
        Assert.assertEquals(revocationStatus.mReason, "reason for key1");

        revocationStatus = blacklist.getRevocationStatusForKey("key2");
        Assert.assertNull(revocationStatus);

        Assert.assertTrue(blacklist.isRevoked("key1"));
        Assert.assertFalse(blacklist.isRevoked("key2"));
    }
}
