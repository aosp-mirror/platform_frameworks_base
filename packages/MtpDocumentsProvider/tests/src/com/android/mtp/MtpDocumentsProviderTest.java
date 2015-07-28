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
    public void testOpenAndCloseDevice() throws Exception {
        final ContentResolver resolver = new ContentResolver();
        final MtpDocumentsProvider provider = new MtpDocumentsProvider();
        final MtpManagerMock mtpManager = new MtpManagerMock(getContext());
        mtpManager.addValidDevice(0);
        provider.onCreateForTesting(mtpManager, resolver);

        assertEquals(0, resolver.changeCount);

        provider.openDevice(0);
        assertEquals(1, resolver.changeCount);

        provider.closeDevice(0);
        assertEquals(2, resolver.changeCount);

        int exceptionCounter = 0;
        try {
            provider.openDevice(1);
        } catch (IOException error) {
            exceptionCounter++;
        }
        assertEquals(2, resolver.changeCount);
        try {
            provider.closeDevice(1);
        } catch (IOException error) {
            exceptionCounter++;
        }
        assertEquals(2, resolver.changeCount);
        assertEquals(2, exceptionCounter);
    }

    public void testCloseAllDevices() throws IOException {
        final ContentResolver resolver = new ContentResolver();
        final MtpDocumentsProvider provider = new MtpDocumentsProvider();
        final MtpManagerMock mtpManager = new MtpManagerMock(getContext());
        mtpManager.addValidDevice(0);
        provider.onCreateForTesting(mtpManager, resolver);

        provider.closeAllDevices();
        assertEquals(0, resolver.changeCount);

        provider.openDevice(0);
        assertEquals(1, resolver.changeCount);

        provider.closeAllDevices();
        assertEquals(2, resolver.changeCount);
    }

    public void testQueryRoots() throws Exception {
        final ContentResolver resolver = new ContentResolver();
        final MtpDocumentsProvider provider = new MtpDocumentsProvider();
        final MtpManagerMock mtpManager = new MtpManagerMock(getContext());
        mtpManager.addValidDevice(0);
        mtpManager.addValidDevice(1);
        mtpManager.setRoots(0, new MtpRoot[] {
                new MtpRoot(
                        1 /* storageId */,
                        "Storage A" /* volume description */,
                        1024 /* free space */,
                        2048 /* total space */,
                        "" /* no volume identifier */)
        });
        mtpManager.setRoots(1, new MtpRoot[] {
                new MtpRoot(
                        1 /* storageId */,
                        "Storage B" /* volume description */,
                        2048 /* free space */,
                        4096 /* total space */,
                        "Identifier B" /* no volume identifier */)
        });
        provider.onCreateForTesting(mtpManager, resolver);
        assertEquals(0, provider.queryRoots(null).getCount());

        {
            provider.openDevice(0);
            final Cursor cursor = provider.queryRoots(null);
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
            provider.openDevice(1);
            final Cursor cursor = provider.queryRoots(null);
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
            provider.closeAllDevices();
            final Cursor cursor = provider.queryRoots(null);
            assertEquals(0, cursor.getCount());
        }
    }

    public void testQueryRoots_error() throws IOException {
        final ContentResolver resolver = new ContentResolver();
        final MtpDocumentsProvider provider = new MtpDocumentsProvider();
        final MtpManagerMock mtpManager = new MtpManagerMock(getContext());
        mtpManager.addValidDevice(0);
        mtpManager.addValidDevice(1);
        // Not set roots for device 0 so that MtpManagerMock#getRoots throws IOException.
        mtpManager.setRoots(1, new MtpRoot[] {
                new MtpRoot(
                        1 /* storageId */,
                        "Storage B" /* volume description */,
                        2048 /* free space */,
                        4096 /* total space */,
                        "Identifier B" /* no volume identifier */)
        });
        provider.onCreateForTesting(mtpManager, resolver);
        {
            provider.openDevice(0);
            provider.openDevice(1);
            final Cursor cursor = provider.queryRoots(null);
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
