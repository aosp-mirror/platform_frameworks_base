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
}
