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

import android.database.Cursor;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public class MtpDatabaseTest extends AndroidTestCase {
    private final String[] COLUMN_NAMES = new String[] {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        MtpDatabase.COLUMN_DEVICE_ID,
        MtpDatabase.COLUMN_STORAGE_ID,
        MtpDatabase.COLUMN_OBJECT_HANDLE,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SUMMARY,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_ICON,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    };

    private final TestResources resources = new TestResources();

    @Override
    public void tearDown() {
        MtpDatabase.deleteDatabase(getContext());
    }

    public void testPutRootDocument() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 1, "Device", "Storage", 1000, 2000, ""),
                new MtpRoot(0, 2, "Device", "Storage", 1000, 2000, ""),
                new MtpRoot(0, 3, "Device", "/@#%&<>Storage", 1000, 2000,"")
        });

        final Cursor cursor = database.queryChildDocuments(COLUMN_NAMES);
        assertEquals(3, cursor.getCount());

        cursor.moveToNext();
        assertEquals("documentId", 1, cursor.getInt(0));
        assertEquals("deviceId", 0, cursor.getInt(1));
        assertEquals("storageId", 1, cursor.getInt(2));
        assertTrue("objectHandle", cursor.isNull(3));
        assertEquals("mimeType", DocumentsContract.Document.MIME_TYPE_DIR, cursor.getString(4));
        assertEquals("displayName", "Device Storage", cursor.getString(5));
        assertTrue("summary", cursor.isNull(6));
        assertTrue("lastModified", cursor.isNull(7));
        assertTrue("icon", cursor.isNull(8));
        assertEquals("flag", 0, cursor.getInt(9));
        assertEquals("size", 1000, cursor.getInt(10));

        cursor.moveToNext();
        assertEquals("documentId", 2, cursor.getInt(0));
        assertEquals("displayName", "Device Storage", cursor.getString(5));

        cursor.moveToNext();
        assertEquals("documentId", 3, cursor.getInt(0));
        assertEquals("displayName", "Device /@#%&<>Storage", cursor.getString(5));

        cursor.close();
    }

    public void testPutDocument() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final MtpObjectInfo.Builder builder = new MtpObjectInfo.Builder();
        builder.setObjectHandle(100);
        builder.setName("test.txt");
        builder.setStorageId(5);
        builder.setFormat(MtpConstants.FORMAT_TEXT);
        builder.setCompressedSize(1000);
        database.putDocument(0, builder.build());

        final Cursor cursor = database.queryChildDocuments(COLUMN_NAMES);
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals("documentId", 1, cursor.getInt(0));
        assertEquals("deviceId", 0, cursor.getInt(1));
        assertEquals("storageId", 5, cursor.getInt(2));
        assertEquals("objectHandle", 100, cursor.getInt(3));
        assertEquals("mimeType", "text/plain", cursor.getString(4));
        assertEquals("displayName", "test.txt", cursor.getString(5));
        assertTrue("summary", cursor.isNull(6));
        assertTrue("lastModified", cursor.isNull(7));
        assertTrue("icon", cursor.isNull(8));
        assertEquals(
                "flag",
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
                cursor.getInt(9));
        assertEquals("size", 1000, cursor.getInt(10));
    }

    public void testRestoreIdForRootDocuments() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabase.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
                new MtpRoot(0, 101, "Device", "Storage B", 0, 0, "")
        });

        {
            final Cursor cursor = database.queryChildDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("storageId", 100, cursor.getInt(1));
            assertEquals("name", "Device Storage A", cursor.getString(2));
            cursor.moveToNext();
            assertEquals("documentId", 2, cursor.getInt(0));
            assertEquals("storageId", 101, cursor.getInt(1));
            assertEquals("name", "Device Storage B", cursor.getString(2));
            cursor.close();
        }

        database.clearMapping();

        {
            final Cursor cursor = database.queryChildDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertTrue("storageId", cursor.isNull(1));
            assertEquals("name", "Device Storage A", cursor.getString(2));
            cursor.moveToNext();
            assertEquals("documentId", 2, cursor.getInt(0));
            assertTrue("storageId", cursor.isNull(1));
            assertEquals("name", "Device Storage B", cursor.getString(2));
            cursor.close();
        }

        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage A", 0, 0, ""),
                new MtpRoot(0, 202, "Device", "Storage C", 0, 0, "")
        });

        {
            final Cursor cursor = database.queryChildDocuments(columns);
            assertEquals(3, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertTrue("storageId", cursor.isNull(1));
            assertEquals("name", "Device Storage A", cursor.getString(2));
            cursor.moveToNext();
            assertEquals("documentId", 2, cursor.getInt(0));
            assertTrue("storageId", cursor.isNull(1));
            assertEquals("name", "Device Storage B", cursor.getString(2));
            cursor.moveToNext();
            assertEquals("documentId", 4, cursor.getInt(0));
            assertEquals("storageId", 202, cursor.getInt(1));
            assertEquals("name", "Device Storage C", cursor.getString(2));
            cursor.close();
        }

        database.resolveRootDocuments(0);

        {
            final Cursor cursor = database.queryChildDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("storageId", 200, cursor.getInt(1));
            assertEquals("name", "Device Storage A", cursor.getString(2));
            cursor.moveToNext();
            assertEquals("documentId", 4, cursor.getInt(0));
            assertEquals("storageId", 202, cursor.getInt(1));
            assertEquals("name", "Device Storage C", cursor.getString(2));
            cursor.close();
        }
    }

    public void testRestoreIdForDifferentDevices() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabase.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage", 0, 0, "")
        });
        database.putRootDocuments(1, resources, new MtpRoot[] {
                new MtpRoot(1, 100, "Device", "Storage", 0, 0, "")
        });

        {
            final Cursor cursor = database.queryChildDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("storageId", 100, cursor.getInt(1));
            assertEquals("name", "Device Storage", cursor.getString(2));
            cursor.moveToNext();
            assertEquals("documentId", 2, cursor.getInt(0));
            assertEquals("storageId", 100, cursor.getInt(1));
            assertEquals("name", "Device Storage", cursor.getString(2));
            cursor.close();
        }

        database.clearMapping();

        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 0, 0, "")
        });
        database.putRootDocuments(1, resources, new MtpRoot[] {
                new MtpRoot(1, 300, "Device", "Storage", 0, 0, "")
        });
        database.resolveRootDocuments(0);
        database.resolveRootDocuments(1);
        {
            final Cursor cursor = database.queryChildDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("storageId", 200, cursor.getInt(1));
            assertEquals("name", "Device Storage", cursor.getString(2));
            cursor.moveToNext();
            assertEquals("documentId", 2, cursor.getInt(0));
            assertEquals("storageId", 300, cursor.getInt(1));
            assertEquals("name", "Device Storage", cursor.getString(2));
            cursor.close();
        }
    }

    public void testClearMtpIdentifierBeforeResolveRootDocuments() {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabase.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage", 0, 0, ""),
        });
        database.clearMapping();
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 0, 0, ""),
        });
        database.clearMapping();
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 300, "Device", "Storage", 0, 0, ""),
        });
        database.resolveRootDocuments(0);
        {
            final Cursor cursor = database.queryChildDocuments(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("storageId", 300, cursor.getInt(1));
            assertEquals("name", "Device Storage", cursor.getString(2));
            cursor.close();
        }
    }

    public void testPutSameNameRootsAfterClearing() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabase.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage", 0, 0, ""),
        });
        database.clearMapping();
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 0, 0, ""),
                new MtpRoot(0, 201, "Device", "Storage", 0, 0, ""),
        });
        database.resolveRootDocuments(0);
        {
            final Cursor cursor = database.queryChildDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 2, cursor.getInt(0));
            assertEquals("storageId", 200, cursor.getInt(1));
            assertEquals("name", "Device Storage", cursor.getString(2));
            cursor.moveToNext();
            assertEquals("documentId", 3, cursor.getInt(0));
            assertEquals("storageId", 201, cursor.getInt(1));
            assertEquals("name", "Device Storage", cursor.getString(2));
            cursor.close();
        }
    }
}
