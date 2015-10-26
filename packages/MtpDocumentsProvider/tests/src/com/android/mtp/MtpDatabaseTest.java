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

    public void testPutRootDocuments() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 1, "Device", "Storage", 1000, 2000, ""),
                new MtpRoot(0, 2, "Device", "Storage", 1000, 2000, ""),
                new MtpRoot(0, 3, "Device", "/@#%&<>Storage", 1000, 2000,"")
        });

        final Cursor cursor = database.queryRootDocuments(COLUMN_NAMES);
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

    private MtpObjectInfo createDocument(int objectHandle, String name, int format, int size) {
        final MtpObjectInfo.Builder builder = new MtpObjectInfo.Builder();
        builder.setObjectHandle(objectHandle);
        builder.setName(name);
        builder.setFormat(format);
        builder.setCompressedSize(size);
        return builder.build();
    }

    public void testPutChildDocuments() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());

        database.putChildDocuments(0, "parentId", new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(101, "image.jpg", MtpConstants.FORMAT_EXIF_JPEG, 2 * 1024 * 1024),
                createDocument(102, "music.mp3", MtpConstants.FORMAT_MP3, 3 * 1024 * 1024)
        });

        final Cursor cursor = database.queryChildDocuments(COLUMN_NAMES, "parentId");
        assertEquals(3, cursor.getCount());

        cursor.moveToNext();
        assertEquals("documentId", 1, cursor.getInt(0));
        assertEquals("deviceId", 0, cursor.getInt(1));
        assertEquals("storageId", 0, cursor.getInt(2));
        assertEquals("objectHandle", 100, cursor.getInt(3));
        assertEquals("mimeType", "text/plain", cursor.getString(4));
        assertEquals("displayName", "note.txt", cursor.getString(5));
        assertTrue("summary", cursor.isNull(6));
        assertTrue("lastModified", cursor.isNull(7));
        assertTrue("icon", cursor.isNull(8));
        assertEquals(
                "flag",
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
                cursor.getInt(9));
        assertEquals("size", 1024, cursor.getInt(10));

        cursor.moveToNext();
        assertEquals("documentId", 2, cursor.getInt(0));
        assertEquals("deviceId", 0, cursor.getInt(1));
        assertEquals("storageId", 0, cursor.getInt(2));
        assertEquals("objectHandle", 101, cursor.getInt(3));
        assertEquals("mimeType", "image/jpeg", cursor.getString(4));
        assertEquals("displayName", "image.jpg", cursor.getString(5));
        assertTrue("summary", cursor.isNull(6));
        assertTrue("lastModified", cursor.isNull(7));
        assertTrue("icon", cursor.isNull(8));
        assertEquals(
                "flag",
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
                cursor.getInt(9));
        assertEquals("size", 2 * 1024 * 1024, cursor.getInt(10));

        cursor.moveToNext();
        assertEquals("documentId", 3, cursor.getInt(0));
        assertEquals("deviceId", 0, cursor.getInt(1));
        assertEquals("storageId", 0, cursor.getInt(2));
        assertEquals("objectHandle", 102, cursor.getInt(3));
        assertEquals("mimeType", "audio/mpeg", cursor.getString(4));
        assertEquals("displayName", "music.mp3", cursor.getString(5));
        assertTrue("summary", cursor.isNull(6));
        assertTrue("lastModified", cursor.isNull(7));
        assertTrue("icon", cursor.isNull(8));
        assertEquals(
                "flag",
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
                cursor.getInt(9));
        assertEquals("size", 3 * 1024 * 1024, cursor.getInt(10));

        cursor.close();
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
            final Cursor cursor = database.queryRootDocuments(columns);
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
            final Cursor cursor = database.queryRootDocuments(columns);
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
            final Cursor cursor = database.queryRootDocuments(columns);
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
            final Cursor cursor = database.queryRootDocuments(columns);
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

    public void testRestoreIdForChildDocuments() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabase.COLUMN_OBJECT_HANDLE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        database.putChildDocuments(0, "parentId", new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(101, "image.jpg", MtpConstants.FORMAT_EXIF_JPEG, 2 * 1024 * 1024),
                createDocument(102, "music.mp3", MtpConstants.FORMAT_MP3, 3 * 1024 * 1024)
        });
        database.clearMapping();

        {
            final Cursor cursor = database.queryChildDocuments(columns, "parentId");
            assertEquals(3, cursor.getCount());

            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertTrue("objectHandle", cursor.isNull(1));
            assertEquals("name", "note.txt", cursor.getString(2));

            cursor.moveToNext();
            assertEquals("documentId", 2, cursor.getInt(0));
            assertTrue("objectHandle", cursor.isNull(1));
            assertEquals("name", "image.jpg", cursor.getString(2));

            cursor.moveToNext();
            assertEquals("documentId", 3, cursor.getInt(0));
            assertTrue("objectHandle", cursor.isNull(1));
            assertEquals("name", "music.mp3", cursor.getString(2));

            cursor.close();
        }

        database.putChildDocuments(0, "parentId", new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(203, "video.mp4", MtpConstants.FORMAT_MP4_CONTAINER, 1024),
        });

        {
            final Cursor cursor = database.queryChildDocuments(columns, "parentId");
            assertEquals(4, cursor.getCount());

            cursor.moveToPosition(3);
            assertEquals("documentId", 5, cursor.getInt(0));
            assertEquals("objectHandle", 203, cursor.getInt(1));
            assertEquals("name", "video.mp4", cursor.getString(2));

            cursor.close();
        }

        database.resolveChildDocuments("parentId");

        {
            final Cursor cursor = database.queryChildDocuments(columns, "parentId");
            assertEquals(2, cursor.getCount());

            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("objectHandle", 200, cursor.getInt(1));
            assertEquals("name", "note.txt", cursor.getString(2));

            cursor.moveToNext();
            assertEquals("documentId", 5, cursor.getInt(0));
            assertEquals("objectHandle", 203, cursor.getInt(1));
            assertEquals("name", "video.mp4", cursor.getString(2));
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
            final Cursor cursor = database.queryRootDocuments(columns);
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
            final Cursor cursor = database.queryRootDocuments(columns);
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

    public void testRestoreIdForDifferentParents() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabase.COLUMN_OBJECT_HANDLE
        };
        database.putChildDocuments(0, "parentId1", new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        database.putChildDocuments(0, "parentId2", new MtpObjectInfo[] {
                createDocument(101, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        database.clearMapping();
        database.putChildDocuments(0, "parentId1", new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        database.putChildDocuments(0, "parentId2", new MtpObjectInfo[] {
                createDocument(201, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        database.resolveChildDocuments("parentId1");

        {
            final Cursor cursor = database.queryChildDocuments(columns, "parentId1");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("objectHandle", 200, cursor.getInt(1));
            cursor.close();
        }
        {
            final Cursor cursor = database.queryChildDocuments(columns, "parentId2");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 2, cursor.getInt(0));
            assertTrue("objectHandle", cursor.isNull(1));
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
            final Cursor cursor = database.queryRootDocuments(columns);
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
            final Cursor cursor = database.queryRootDocuments(columns);
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
