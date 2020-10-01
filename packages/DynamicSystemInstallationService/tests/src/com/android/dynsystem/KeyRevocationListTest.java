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

    private static String sBlocklistJsonString;

    @BeforeClass
    public static void setUpClass() throws Exception {
        sContext = InstrumentationRegistry.getInstrumentation().getContext();
        sBlocklistJsonString =
                sContext.getString(com.android.dynsystem.tests.R.string.blocklist_json_string);
    }

    @Test
    @SmallTest
    public void testFromJsonString() throws JSONException {
        KeyRevocationList blocklist;
        blocklist = KeyRevocationList.fromJsonString(sBlocklistJsonString);
        Assert.assertNotNull(blocklist);
        Assert.assertFalse(blocklist.mEntries.isEmpty());
        blocklist = KeyRevocationList.fromJsonString("{}");
        Assert.assertNotNull(blocklist);
        Assert.assertTrue(blocklist.mEntries.isEmpty());
    }

    @Test
    @SmallTest
    public void testFromUrl() throws IOException, JSONException {
        URLConnection mockConnection = mock(URLConnection.class);
        doReturn(new ByteArrayInputStream(sBlocklistJsonString.getBytes()))
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

        KeyRevocationList blocklist = KeyRevocationList.fromUrl(mockUrl);
        Assert.assertNotNull(blocklist);
        Assert.assertFalse(blocklist.mEntries.isEmpty());

        blocklist = null;
        try {
            blocklist = KeyRevocationList.fromUrl(mockBadUrl);
            // Up should throw, down should be unreachable
            Assert.fail("Expected IOException not thrown");
        } catch (IOException e) {
            // This is expected, do nothing
        }
        Assert.assertNull(blocklist);
    }

    @Test
    @SmallTest
    public void testIsRevoked() {
        KeyRevocationList blocklist = new KeyRevocationList();
        blocklist.addEntry("key1", "REVOKED", "reason for key1");

        KeyRevocationList.RevocationStatus revocationStatus =
                blocklist.getRevocationStatusForKey("key1");
        Assert.assertNotNull(revocationStatus);
        Assert.assertEquals(revocationStatus.mReason, "reason for key1");

        revocationStatus = blocklist.getRevocationStatusForKey("key2");
        Assert.assertNull(revocationStatus);

        Assert.assertTrue(blocklist.isRevoked("key1"));
        Assert.assertFalse(blocklist.isRevoked("key2"));
    }
}
