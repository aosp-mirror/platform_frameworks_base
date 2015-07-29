/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract.Root;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.IOException;

@SmallTest
public class MtpDocumentsProviderTest extends AndroidTestCase {
    private ContentResolver mResolver;
    private MtpDocumentsProvider mProvider;
    private MtpManagerMock mMtpManager;

    @Override
    public void setUp() {
        mResolver = new ContentResolver();
        mMtpManager = new MtpManagerMock(getContext());
        mProvider = new MtpDocumentsProvider();
        mProvider.onCreateForTesting(mMtpManager, mResolver);
    }

    public void testOpenAndCloseDevice() throws Exception {
        mMtpManager.addValidDevice(0);
        assertEquals(0, mResolver.changeCount);

        mProvider.openDevice(0);
        assertEquals(1, mResolver.changeCount);

        mProvider.closeDevice(0);
        assertEquals(2, mResolver.changeCount);

        int exceptionCounter = 0;
        try {
            mProvider.openDevice(1);
        } catch (IOException error) {
            exceptionCounter++;
        }
        assertEquals(2, mResolver.changeCount);
        try {
            mProvider.closeDevice(1);
        } catch (IOException error) {
            exceptionCounter++;
        }
        assertEquals(2, mResolver.changeCount);
        assertEquals(2, exceptionCounter);
    }

    public void testCloseAllDevices() throws IOException {
        mMtpManager.addValidDevice(0);
        mProvider.onCreateForTesting(mMtpManager, mResolver);

        mProvider.closeAllDevices();
        assertEquals(0, mResolver.changeCount);

        mProvider.openDevice(0);
        assertEquals(1, mResolver.changeCount);

        mProvider.closeAllDevices();
        assertEquals(2, mResolver.changeCount);
    }

    public void testQueryRoots() throws Exception {
        mMtpManager.addValidDevice(0);
        mMtpManager.addValidDevice(1);
        mMtpManager.setRoots(0, new MtpRoot[] {
                new MtpRoot(
                        1 /* storageId */,
                        "Storage A" /* volume description */,
                        1024 /* free space */,
                        2048 /* total space */,
                        "" /* no volume identifier */)
        });
        mMtpManager.setRoots(1, new MtpRoot[] {
                new MtpRoot(
                        1 /* storageId */,
                        "Storage B" /* volume description */,
                        2048 /* free space */,
                        4096 /* total space */,
                        "Identifier B" /* no volume identifier */)
        });
        mProvider.onCreateForTesting(mMtpManager, mResolver);
        assertEquals(0, mProvider.queryRoots(null).getCount());

        {
            mProvider.openDevice(0);
            final Cursor cursor = mProvider.queryRoots(null);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("0:1", cursor.getString(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD, cursor.getInt(1));
            // TODO: Add storage icon for MTP devices.
            assertTrue(cursor.isNull(2) /* icon */);
            assertEquals("Storage A", cursor.getString(3));
            assertEquals("0:1:0", cursor.getString(4));
            assertEquals(1024, cursor.getInt(5));
        }

        {
            mProvider.openDevice(1);
            final Cursor cursor = mProvider.queryRoots(null);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            cursor.moveToNext();
            assertEquals("1:1", cursor.getString(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD, cursor.getInt(1));
            // TODO: Add storage icon for MTP devices.
            assertTrue(cursor.isNull(2) /* icon */);
            assertEquals("Storage B", cursor.getString(3));
            assertEquals("1:1:0", cursor.getString(4));
            assertEquals(2048, cursor.getInt(5));
        }

        {
            mProvider.closeAllDevices();
            final Cursor cursor = mProvider.queryRoots(null);
            assertEquals(0, cursor.getCount());
        }
    }

    public void testQueryRoots_error() throws IOException {
        mMtpManager.addValidDevice(0);
        mMtpManager.addValidDevice(1);
        // Not set roots for device 0 so that MtpManagerMock#getRoots throws IOException.
        mMtpManager.setRoots(1, new MtpRoot[] {
                new MtpRoot(
                        1 /* storageId */,
                        "Storage B" /* volume description */,
                        2048 /* free space */,
                        4096 /* total space */,
                        "Identifier B" /* no volume identifier */)
        });
        mProvider.onCreateForTesting(mMtpManager, mResolver);
        {
            mProvider.openDevice(0);
            mProvider.openDevice(1);
            final Cursor cursor = mProvider.queryRoots(null);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("1:1", cursor.getString(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD, cursor.getInt(1));
            // TODO: Add storage icon for MTP devices.
            assertTrue(cursor.isNull(2) /* icon */);
            assertEquals("Storage B", cursor.getString(3));
            assertEquals("1:1:0", cursor.getString(4));
            assertEquals(2048, cursor.getInt(5));
        }
    }

    private static class ContentResolver extends MockContentResolver {
        int changeCount = 0;

        @Override
        public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
            changeCount++;
        }
    }
}
