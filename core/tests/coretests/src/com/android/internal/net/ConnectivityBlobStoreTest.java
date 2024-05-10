/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConnectivityBlobStoreTest {
    private static final String DATABASE_FILENAME = "ConnectivityBlobStore.db";
    private static final String TEST_NAME = "TEST_NAME";
    private static final byte[] TEST_BLOB = new byte[] {(byte) 10, (byte) 90, (byte) 45, (byte) 12};

    private Context mContext;
    private File mFile;

    private ConnectivityBlobStore createConnectivityBlobStore() {
        return new ConnectivityBlobStore(mFile);
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mFile = mContext.getDatabasePath(DATABASE_FILENAME);
    }

    @After
    public void tearDown() throws Exception {
        mContext.deleteDatabase(DATABASE_FILENAME);
    }

    @Test
    public void testFileCreateDelete() {
        assertFalse(mFile.exists());
        createConnectivityBlobStore();
        assertTrue(mFile.exists());

        assertTrue(mContext.deleteDatabase(DATABASE_FILENAME));
        assertFalse(mFile.exists());
    }

    @Test
    public void testPutAndGet() throws Exception {
        final ConnectivityBlobStore connectivityBlobStore = createConnectivityBlobStore();
        assertNull(connectivityBlobStore.get(TEST_NAME));

        assertTrue(connectivityBlobStore.put(TEST_NAME, TEST_BLOB));
        assertArrayEquals(TEST_BLOB, connectivityBlobStore.get(TEST_NAME));

        // Test replacement
        final byte[] newBlob = new byte[] {(byte) 15, (byte) 20};
        assertTrue(connectivityBlobStore.put(TEST_NAME, newBlob));
        assertArrayEquals(newBlob, connectivityBlobStore.get(TEST_NAME));
    }

    @Test
    public void testRemove() throws Exception {
        final ConnectivityBlobStore connectivityBlobStore = createConnectivityBlobStore();
        assertNull(connectivityBlobStore.get(TEST_NAME));
        assertFalse(connectivityBlobStore.remove(TEST_NAME));

        assertTrue(connectivityBlobStore.put(TEST_NAME, TEST_BLOB));
        assertArrayEquals(TEST_BLOB, connectivityBlobStore.get(TEST_NAME));

        assertTrue(connectivityBlobStore.remove(TEST_NAME));
        assertNull(connectivityBlobStore.get(TEST_NAME));

        // Removing again returns false
        assertFalse(connectivityBlobStore.remove(TEST_NAME));
    }

    @Test
    public void testMultipleNames() throws Exception {
        final String name1 = TEST_NAME + "1";
        final String name2 = TEST_NAME + "2";
        final ConnectivityBlobStore connectivityBlobStore = createConnectivityBlobStore();

        assertNull(connectivityBlobStore.get(name1));
        assertNull(connectivityBlobStore.get(name2));
        assertFalse(connectivityBlobStore.remove(name1));
        assertFalse(connectivityBlobStore.remove(name2));

        assertTrue(connectivityBlobStore.put(name1, TEST_BLOB));
        assertTrue(connectivityBlobStore.put(name2, TEST_BLOB));
        assertArrayEquals(TEST_BLOB, connectivityBlobStore.get(name1));
        assertArrayEquals(TEST_BLOB, connectivityBlobStore.get(name2));

        // Replace the blob for name1 only.
        final byte[] newBlob = new byte[] {(byte) 16, (byte) 21};
        assertTrue(connectivityBlobStore.put(name1, newBlob));
        assertArrayEquals(newBlob, connectivityBlobStore.get(name1));

        assertTrue(connectivityBlobStore.remove(name1));
        assertNull(connectivityBlobStore.get(name1));
        assertArrayEquals(TEST_BLOB, connectivityBlobStore.get(name2));

        assertFalse(connectivityBlobStore.remove(name1));
        assertTrue(connectivityBlobStore.remove(name2));
        assertNull(connectivityBlobStore.get(name2));
        assertFalse(connectivityBlobStore.remove(name2));
    }

    @Test
    public void testList() throws Exception {
        final String[] unsortedNames = new String[] {
                TEST_NAME + "1",
                TEST_NAME + "2",
                TEST_NAME + "0",
                "NON_MATCHING_PREFIX",
                "MATCHING_SUFFIX_" + TEST_NAME
        };
        // Expected to match and discard the prefix and be in increasing sorted order.
        final String[] expected = new String[] {
                "0",
                "1",
                "2"
        };
        final ConnectivityBlobStore connectivityBlobStore = createConnectivityBlobStore();

        for (int i = 0; i < unsortedNames.length; i++) {
            assertTrue(connectivityBlobStore.put(unsortedNames[i], TEST_BLOB));
        }
        final String[] actual = connectivityBlobStore.list(TEST_NAME /* prefix */);
        assertArrayEquals(expected, actual);
    }

    @Test
    public void testList_underscoreInPrefix() throws Exception {
        final String prefix = TEST_NAME + "_";
        final String[] unsortedNames = new String[] {
                prefix + "000",
                TEST_NAME + "123",
        };
        // The '_' in the prefix should not be treated as a wildcard so the only match is "000".
        final String[] expected = new String[] {"000"};
        final ConnectivityBlobStore connectivityBlobStore = createConnectivityBlobStore();

        for (int i = 0; i < unsortedNames.length; i++) {
            assertTrue(connectivityBlobStore.put(unsortedNames[i], TEST_BLOB));
        }
        final String[] actual = connectivityBlobStore.list(prefix);
        assertArrayEquals(expected, actual);
    }

    @Test
    public void testList_percentInPrefix() throws Exception {
        final String prefix = "%" + TEST_NAME + "%";
        final String[] unsortedNames = new String[] {
                TEST_NAME + "12345",
                prefix + "0",
                "abc" + TEST_NAME + "987",
        };
        // The '%' in the prefix should not be treated as a wildcard so the only match is "0".
        final String[] expected = new String[] {"0"};
        final ConnectivityBlobStore connectivityBlobStore = createConnectivityBlobStore();

        for (int i = 0; i < unsortedNames.length; i++) {
            assertTrue(connectivityBlobStore.put(unsortedNames[i], TEST_BLOB));
        }
        final String[] actual = connectivityBlobStore.list(prefix);
        assertArrayEquals(expected, actual);
    }
}
