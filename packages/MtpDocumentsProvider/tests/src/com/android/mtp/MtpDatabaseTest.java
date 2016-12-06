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
import android.media.MediaFile;
import android.media.MediaFile.MediaFileType;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.FileNotFoundException;
import java.util.Arrays;

import static android.provider.DocumentsContract.Document.*;
import static com.android.mtp.MtpDatabase.strings;
import static com.android.mtp.MtpDatabaseConstants.*;
import static com.android.mtp.TestUtil.OPERATIONS_SUPPORTED;

@SmallTest
public class MtpDatabaseTest extends AndroidTestCase {
    private static final String[] COLUMN_NAMES = new String[] {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        MtpDatabaseConstants.COLUMN_DEVICE_ID,
        MtpDatabaseConstants.COLUMN_STORAGE_ID,
        MtpDatabaseConstants.COLUMN_OBJECT_HANDLE,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SUMMARY,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_ICON,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        MtpDatabaseConstants.COLUMN_DOCUMENT_TYPE
    };

    private final TestResources resources = new TestResources();
    MtpDatabase mDatabase;

    @Override
    public void setUp() {
        mDatabase = new MtpDatabase(getContext(), MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
    }

    @Override
    public void tearDown() {
        mDatabase.close();
        mDatabase = null;
    }

    private static int getInt(Cursor cursor, String columnName) {
        return cursor.getInt(cursor.getColumnIndex(columnName));
    }

    private static boolean isNull(Cursor cursor, String columnName) {
        return cursor.isNull(cursor.getColumnIndex(columnName));
    }

    private static String getString(Cursor cursor, String columnName) {
        return cursor.getString(cursor.getColumnIndex(columnName));
    }

    public void testPutSingleStorageDocuments() throws Exception {
        addTestDevice();

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 1, "Storage", 1000, 2000, "")
        });
        mDatabase.getMapper().stopAddingDocuments("1");

        {
            final Cursor cursor = mDatabase.queryRootDocuments(COLUMN_NAMES);
            assertEquals(1, cursor.getCount());

            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(0, getInt(cursor, COLUMN_DEVICE_ID));
            assertEquals(1, getInt(cursor, COLUMN_STORAGE_ID));
            assertTrue(isNull(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals(
                    DocumentsContract.Document.MIME_TYPE_DIR, getString(cursor, COLUMN_MIME_TYPE));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            assertTrue(isNull(cursor, COLUMN_SUMMARY));
            assertTrue(isNull(cursor, COLUMN_LAST_MODIFIED));
            assertEquals(R.drawable.ic_root_mtp, getInt(cursor, COLUMN_ICON));
            assertEquals(Document.FLAG_DIR_SUPPORTS_CREATE, getInt(cursor, COLUMN_FLAGS));
            assertEquals(1000, getInt(cursor, COLUMN_SIZE));
            assertEquals(
                    MtpDatabaseConstants.DOCUMENT_TYPE_STORAGE,
                    getInt(cursor, COLUMN_DOCUMENT_TYPE));

            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(resources, new String [] {
                    Root.COLUMN_ROOT_ID,
                    Root.COLUMN_FLAGS,
                    Root.COLUMN_ICON,
                    Root.COLUMN_TITLE,
                    Root.COLUMN_SUMMARY,
                    Root.COLUMN_DOCUMENT_ID,
                    Root.COLUMN_AVAILABLE_BYTES,
                    Root.COLUMN_CAPACITY_BYTES
            });
            assertEquals(1, cursor.getCount());

            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(
                    Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY,
                    getInt(cursor, Root.COLUMN_FLAGS));
            assertEquals(R.drawable.ic_root_mtp, getInt(cursor, Root.COLUMN_ICON));
            assertEquals("Device Storage", getString(cursor, Root.COLUMN_TITLE));
            assertTrue(isNull(cursor, Root.COLUMN_SUMMARY));
            assertEquals(1, getInt(cursor, Root.COLUMN_DOCUMENT_ID));
            assertEquals(1000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            assertEquals(2000, getInt(cursor, Root.COLUMN_CAPACITY_BYTES));

            cursor.close();
        }
    }

    public void testPutStorageDocuments() throws Exception {
        addTestDevice();

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 1, "Storage", 1000, 2000, ""),
                new MtpRoot(0, 2, "Storage", 2000, 4000, ""),
                new MtpRoot(0, 3, "/@#%&<>Storage", 3000, 6000,"")
        });

        {
            final Cursor cursor = mDatabase.queryRootDocuments(COLUMN_NAMES);
            assertEquals(3, cursor.getCount());

            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(0, getInt(cursor, COLUMN_DEVICE_ID));
            assertEquals(1, getInt(cursor, COLUMN_STORAGE_ID));
            assertTrue(isNull(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, getString(cursor, COLUMN_MIME_TYPE));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            assertTrue(isNull(cursor, COLUMN_SUMMARY));
            assertTrue(isNull(cursor, COLUMN_LAST_MODIFIED));
            assertEquals(R.drawable.ic_root_mtp, getInt(cursor, COLUMN_ICON));
            assertEquals(Document.FLAG_DIR_SUPPORTS_CREATE, getInt(cursor, COLUMN_FLAGS));
            assertEquals(1000, getInt(cursor, COLUMN_SIZE));
            assertEquals(
                    MtpDatabaseConstants.DOCUMENT_TYPE_STORAGE, getInt(cursor, COLUMN_DOCUMENT_TYPE));

            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.moveToNext();
            assertEquals(4, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals("/@#%&<>Storage", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.close();
        }
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
        addTestDevice();
        addTestStorage("1");

        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(101, "image.jpg", MtpConstants.FORMAT_EXIF_JPEG, 2 * 1024 * 1024),
                createDocument(102, "music.mp3", MtpConstants.FORMAT_MP3, 3 * 1024 * 1024)
        }, new long[] { 1024L, 2L * 1024L * 1024L, 3L * 1024L * 1024L});

        final Cursor cursor = mDatabase.queryChildDocuments(COLUMN_NAMES, "2");
        assertEquals(3, cursor.getCount());

        cursor.moveToNext();
        assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
        assertEquals(0, getInt(cursor, COLUMN_DEVICE_ID));
        assertEquals(0, getInt(cursor, COLUMN_STORAGE_ID));
        assertEquals(100, getInt(cursor, COLUMN_OBJECT_HANDLE));
        assertEquals("text/plain", getString(cursor, COLUMN_MIME_TYPE));
        assertEquals("note.txt", getString(cursor, COLUMN_DISPLAY_NAME));
        assertTrue(isNull(cursor, COLUMN_SUMMARY));
        assertTrue(isNull(cursor, COLUMN_LAST_MODIFIED));
        assertTrue(isNull(cursor, COLUMN_ICON));
        assertEquals(
                COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
                cursor.getInt(9));
        assertEquals(1024, getInt(cursor, COLUMN_SIZE));
        assertEquals(
                MtpDatabaseConstants.DOCUMENT_TYPE_OBJECT, getInt(cursor, COLUMN_DOCUMENT_TYPE));

        cursor.moveToNext();
        assertEquals(4, getInt(cursor, COLUMN_DOCUMENT_ID));
        assertEquals(0, getInt(cursor, COLUMN_DEVICE_ID));
        assertEquals(0, getInt(cursor, COLUMN_STORAGE_ID));
        assertEquals(101, getInt(cursor, COLUMN_OBJECT_HANDLE));
        assertEquals("image/jpeg", getString(cursor, COLUMN_MIME_TYPE));
        assertEquals("image.jpg", getString(cursor, COLUMN_DISPLAY_NAME));
        assertTrue(isNull(cursor, COLUMN_SUMMARY));
        assertTrue(isNull(cursor, COLUMN_LAST_MODIFIED));
        assertTrue(isNull(cursor, COLUMN_ICON));
        assertEquals(
                COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
                cursor.getInt(9));
        assertEquals(2 * 1024 * 1024, getInt(cursor, COLUMN_SIZE));
        assertEquals(
                MtpDatabaseConstants.DOCUMENT_TYPE_OBJECT, getInt(cursor, COLUMN_DOCUMENT_TYPE));

        cursor.moveToNext();
        assertEquals(5, getInt(cursor, COLUMN_DOCUMENT_ID));
        assertEquals(0, getInt(cursor, COLUMN_DEVICE_ID));
        assertEquals(0, getInt(cursor, COLUMN_STORAGE_ID));
        assertEquals(102, getInt(cursor, COLUMN_OBJECT_HANDLE));
        assertEquals("audio/mpeg", getString(cursor, COLUMN_MIME_TYPE));
        assertEquals("music.mp3", getString(cursor, COLUMN_DISPLAY_NAME));
        assertTrue(isNull(cursor, COLUMN_SUMMARY));
        assertTrue(isNull(cursor, COLUMN_LAST_MODIFIED));
        assertTrue(isNull(cursor, COLUMN_ICON));
        assertEquals(
                COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
                cursor.getInt(9));
        assertEquals(3 * 1024 * 1024, getInt(cursor, COLUMN_SIZE));
        assertEquals(
                MtpDatabaseConstants.DOCUMENT_TYPE_OBJECT, getInt(cursor, COLUMN_DOCUMENT_TYPE));

        cursor.close();
    }

    public void testPutChildDocuments_operationsSupported() throws Exception {
        addTestDevice();
        addTestStorage("1");

        // Put a document with empty supported operations.
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", new int[0], new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024)
        }, new long[] { 1024L });
        mDatabase.getMapper().stopAddingDocuments("2");

        try (final Cursor cursor =
                mDatabase.queryChildDocuments(strings(Document.COLUMN_FLAGS), "2")) {
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(0, cursor.getInt(0));
        }

        // Put a document with writable operations.
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", new int[] {
                MtpConstants.OPERATION_SEND_OBJECT,
                MtpConstants.OPERATION_SEND_OBJECT_INFO,
        }, new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024)
        }, new long[] { 1024L });
        mDatabase.getMapper().stopAddingDocuments("2");

        try (final Cursor cursor =
                mDatabase.queryChildDocuments(strings(Document.COLUMN_FLAGS), "2")) {
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(Document.FLAG_SUPPORTS_WRITE, cursor.getInt(0));
        }

        // Put a document with deletable operations.
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", new int[] {
                MtpConstants.OPERATION_DELETE_OBJECT
        }, new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024)
        }, new long[] { 1024L });
        mDatabase.getMapper().stopAddingDocuments("2");

        try (final Cursor cursor =
                mDatabase.queryChildDocuments(strings(Document.COLUMN_FLAGS), "2")) {
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(Document.FLAG_SUPPORTS_DELETE, cursor.getInt(0));
        }
    }

    public void testRestoreIdForRootDocuments() throws Exception {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };

        // Add device and two storages.
        addTestDevice();
        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage A", 1000, 0, ""),
                new MtpRoot(0, 101, "Storage B", 1001, 0, "")
        });

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(100, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage A", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(101, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage B", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        // Clear mapping and add a device.
        mDatabase.getMapper().clearMapping();
        addTestDevice();

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(0, cursor.getCount());
            cursor.close();
        }

        // Add two storages, but one's name is different from previous one.
        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 200, "Storage A", 2000, 0, ""),
                new MtpRoot(0, 202, "Storage C", 2002, 0, "")
        });
        mDatabase.getMapper().stopAddingDocuments("1");

        {
            // After compeleting mapping, Storage A can be obtained with new storage ID.
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage A", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(4, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(202, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage C", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }
    }

    public void testRestoreIdForChildDocuments() throws Exception {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_OBJECT_HANDLE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };

        addTestDevice();
        addTestStorage("1");

        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(101, "image.jpg", MtpConstants.FORMAT_EXIF_JPEG, 2 * 1024 * 1024),
                createDocument(102, "music.mp3", MtpConstants.FORMAT_MP3, 3 * 1024 * 1024)
        }, new long[] { 1024L, 2L * 1024L * 1024L, 3L * 1024L * 1024L});
        mDatabase.getMapper().clearMapping();

        addTestDevice();
        addTestStorage("1");

        {
            // Don't return objects that lost MTP object handles.
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "2");
            assertEquals(0, cursor.getCount());
            cursor.close();
        }

        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(203, "video.mp4", MtpConstants.FORMAT_MP4_CONTAINER, 1024),
        }, new long[] { 1024L, 1024L });
        mDatabase.getMapper().stopAddingDocuments("2");

        {
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "2");
            assertEquals(2, cursor.getCount());

            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals("note.txt", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.moveToNext();
            assertEquals(6, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(203, getInt(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals("video.mp4", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.close();
        }
    }

    public void testRestoreIdForDifferentDevices() throws Exception {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };
        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device A", "Device key A", true, new MtpRoot[0], null, null));
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                1, "Device B", "Device key B", true, new MtpRoot[0], null, null));
        mDatabase.getMapper().stopAddingDocuments(null);

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage", 0, 0, "")
        });
        mDatabase.getMapper().putStorageDocuments("2", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(1, 100, "Storage", 0, 0, "")
        });

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(100, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(4, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(100, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(resources, rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(0, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(0, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }

        mDatabase.getMapper().clearMapping();

        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device A", "Device key A", true, new MtpRoot[0], null, null));
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                1, "Device B", "Device key B", true, new MtpRoot[0], null, null));
        mDatabase.getMapper().stopAddingDocuments(null);

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 200, "Storage", 2000, 0, "")
        });
        mDatabase.getMapper().putStorageDocuments("2", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(1, 300, "Storage", 3000, 0, "")
        });
        mDatabase.getMapper().stopAddingDocuments("1");
        mDatabase.getMapper().stopAddingDocuments("2");

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(4, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(300, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(resources, rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(2000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(3000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }
    }

    public void testRestoreIdForDifferentParents() throws Exception {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_OBJECT_HANDLE
        };

        // Add device, storage, and two directories.
        addTestDevice();
        addTestStorage("1");
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(50, "A", MtpConstants.FORMAT_ASSOCIATION, 0),
                createDocument(51, "B", MtpConstants.FORMAT_ASSOCIATION, 0),
        }, new long[] { 0L, 0L });
        mDatabase.getMapper().stopAddingDocuments("2");

        // Put note.txt in each directory.
        mDatabase.getMapper().startAddingDocuments("3");
        mDatabase.getMapper().startAddingDocuments("4");
        mDatabase.getMapper().putChildDocuments(0, "3", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        }, new long[] { 1024L });
        mDatabase.getMapper().putChildDocuments(0, "4", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(101, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        }, new long[] { 1024L });

        // Clear mapping.
        mDatabase.getMapper().clearMapping();

        // Add device, storage, and two directories again.
        addTestDevice();
        addTestStorage("1");
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(50, "A", MtpConstants.FORMAT_ASSOCIATION, 0),
                createDocument(51, "B", MtpConstants.FORMAT_ASSOCIATION, 0),
        }, new long[] { 0L, 0L });
        mDatabase.getMapper().stopAddingDocuments("2");

        // Add note.txt in each directory again.
        mDatabase.getMapper().startAddingDocuments("3");
        mDatabase.getMapper().startAddingDocuments("4");
        mDatabase.getMapper().putChildDocuments(0, "3", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        }, new long[] { 1024L });
        mDatabase.getMapper().putChildDocuments(0, "4", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(201, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        }, new long[] { 1024L });
        mDatabase.getMapper().stopAddingDocuments("3");
        mDatabase.getMapper().stopAddingDocuments("4");

        // Check if the two note.txt are mapped correctly.
        {
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "3");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(5, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_OBJECT_HANDLE));
            cursor.close();
        }
        {
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "4");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(6, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(201, getInt(cursor, COLUMN_OBJECT_HANDLE));
            cursor.close();
        }
    }

    public void testClearMtpIdentifierBeforeResolveRootDocuments() throws Exception {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };

        addTestDevice();

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage", 0, 0, ""),
        });
        mDatabase.getMapper().clearMapping();

        addTestDevice();

        try (final Cursor cursor = mDatabase.queryRoots(resources, rootColumns)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("1", getString(cursor, Root.COLUMN_ROOT_ID));
        }

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 200, "Storage", 2000, 0, ""),
        });
        mDatabase.getMapper().clearMapping();

        addTestDevice();

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 300, "Storage", 3000, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("1");

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(300, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }
        {
            final Cursor cursor = mDatabase.queryRoots(resources, rootColumns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(3000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }
    }

    public void testPutSameNameRootsAfterClearing() throws Exception {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };

        // Add a device and a storage.
        addTestDevice();
        addTestStorage("1");

        // Disconnect devices.
        mDatabase.getMapper().clearMapping();

        // Add a device and two storages that has same name.
        addTestDevice();
        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 200, "Storage", 2000, 0, ""),
                new MtpRoot(0, 201, "Storage", 2001, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("1");

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());

            // First storage reuse document ID of previous storage.
            cursor.moveToNext();
            // One reuses exisitng document ID 1.
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));

            // Second one has new document ID.
            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(201, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.close();
        }
    }

    public void testReplaceExistingRoots() throws Exception {
        addTestDevice();

        // The client code should be able to replace existing rows with new information.
        // Add one.
        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("1");
        // Replace it.
        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage B", 1000, 1000, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("1");
        {
            final String[] columns = new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    MtpDatabaseConstants.COLUMN_STORAGE_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            };
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(100, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Storage B", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }
        {
            final String[] columns = new String[] {
                    Root.COLUMN_ROOT_ID,
                    Root.COLUMN_TITLE,
                    Root.COLUMN_AVAILABLE_BYTES
            };
            final Cursor cursor = mDatabase.queryRoots(resources, columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals("Device Storage B", getString(cursor, Root.COLUMN_TITLE));
            assertEquals(1000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }
    }

    public void testFailToReplaceExisitingUnmappedRoots() throws Exception {
        // The client code should not be able to replace rows before resolving 'unmapped' rows.
        // Add one.
        addTestDevice();
        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().clearMapping();

        addTestDevice();
        try (final Cursor oldCursor =
                mDatabase.queryRoots(resources, strings(Root.COLUMN_ROOT_ID))) {
            assertEquals(1, oldCursor.getCount());
            oldCursor.moveToNext();
            assertEquals("1", getString(oldCursor, Root.COLUMN_ROOT_ID));

            // Add one.
            mDatabase.getMapper().startAddingDocuments("1");
            mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                    new MtpRoot(0, 101, "Storage B", 1000, 1000, ""),
            });
            // Add one more before resolving unmapped documents.
            mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                    new MtpRoot(0, 102, "Storage B", 1000, 1000, ""),
            });
            mDatabase.getMapper().stopAddingDocuments("1");

            // Because the roots shares the same name, the roots should have new IDs.
            try (final Cursor newCursor = mDatabase.queryChildDocuments(
                    strings(Document.COLUMN_DOCUMENT_ID), "1")) {
                assertEquals(2, newCursor.getCount());
                newCursor.moveToNext();
                assertFalse(oldCursor.getString(0).equals(newCursor.getString(0)));
                newCursor.moveToNext();
                assertFalse(oldCursor.getString(0).equals(newCursor.getString(0)));
            }
        }
    }

    public void testQueryDocuments() throws Exception {
        addTestDevice();
        addTestStorage("1");

        final Cursor cursor = mDatabase.queryDocument("2", strings(Document.COLUMN_DISPLAY_NAME));
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals("Storage", getString(cursor, Document.COLUMN_DISPLAY_NAME));
        cursor.close();
    }

    public void testQueryRoots() throws Exception {
        // Add device document.
        addTestDevice();

        // It the device does not have storages, it shows a device root.
        {
            final Cursor cursor = mDatabase.queryRoots(resources, strings(Root.COLUMN_TITLE));
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("Device", cursor.getString(0));
            cursor.close();
        }

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage A", 0, 0, "")
        });
        mDatabase.getMapper().stopAddingDocuments("1");

        // It the device has single storage, it shows a storage root.
        {
            final Cursor cursor = mDatabase.queryRoots(resources, strings(Root.COLUMN_TITLE));
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("Device Storage A", cursor.getString(0));
            cursor.close();
        }

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage A", 0, 0, ""),
                new MtpRoot(0, 101, "Storage B", 0, 0, "")
        });
        mDatabase.getMapper().stopAddingDocuments("1");

        // It the device has multiple storages, it shows a device root.
        {
            final Cursor cursor = mDatabase.queryRoots(resources, strings(Root.COLUMN_TITLE));
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("Device", cursor.getString(0));
            cursor.close();
        }
    }

    public void testGetParentId() throws FileNotFoundException {
        addTestDevice();

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putStorageDocuments("1", OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("1");

        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        }, new long[] { 1024L });
        mDatabase.getMapper().stopAddingDocuments("2");

        assertEquals("2", mDatabase.getParentIdentifier("3").mDocumentId);
    }

    public void testDeleteDocument() throws Exception {
        addTestDevice();
        addTestStorage("1");

        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(200, "dir", MtpConstants.FORMAT_ASSOCIATION, 1024),
        }, new long[] { 1024L });
        mDatabase.getMapper().stopAddingDocuments("2");

        mDatabase.getMapper().startAddingDocuments("3");
        mDatabase.getMapper().putChildDocuments(0, "3", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        }, new long[] { 1024L });
        mDatabase.getMapper().stopAddingDocuments("3");

        mDatabase.deleteDocument("3");

        {
            // Do not query deleted documents.
            final Cursor cursor =
                    mDatabase.queryChildDocuments(strings(Document.COLUMN_DOCUMENT_ID), "2");
            assertEquals(0, cursor.getCount());
            cursor.close();
        }

        {
            // Child document should be deleted also.
            final Cursor cursor =
                    mDatabase.queryDocument("4", strings(Document.COLUMN_DOCUMENT_ID));
            assertEquals(0, cursor.getCount());
            cursor.close();
        }
    }

    public void testPutNewDocument() throws Exception {
        addTestDevice();
        addTestStorage("1");

        assertEquals(
                "3",
                mDatabase.putNewDocument(
                        0, "2", OPERATIONS_SUPPORTED,
                        createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                        1024L));

        {
            final Cursor cursor =
                    mDatabase.queryChildDocuments(strings(Document.COLUMN_DOCUMENT_ID), "2");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("3", cursor.getString(0));
            cursor.close();
        }

        // The new document should not be mapped with existing invalidated document.
        mDatabase.getMapper().clearMapping();
        addTestDevice();
        addTestStorage("1");

        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.putNewDocument(
                0, "2", OPERATIONS_SUPPORTED,
                createDocument(201, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                1024L);
        mDatabase.getMapper().stopAddingDocuments("2");

        {
            final Cursor cursor =
                    mDatabase.queryChildDocuments(strings(Document.COLUMN_DOCUMENT_ID), "2");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("4", cursor.getString(0));
            cursor.close();
        }
    }

    public void testGetDocumentIdForDevice() throws Exception {
        addTestDevice();
        assertEquals("1", mDatabase.getDocumentIdForDevice(0));
    }

    public void testGetClosedDevice() throws Exception {
        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device", null /* deviceKey */, /* opened is */ false, new MtpRoot[0], null,
                null));
        mDatabase.getMapper().stopAddingDocuments(null);

        final String[] columns = new String [] {
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        };
        try (final Cursor cursor = mDatabase.queryRoots(resources, columns)) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToNext());
            assertEquals(1, cursor.getLong(0));
            assertEquals("Device", cursor.getString(1));
            assertTrue(cursor.isNull(2));
        }
    }

    public void testMappingWithoutKey() throws FileNotFoundException {
        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device", null /* device key */, /* opened is */ true, new MtpRoot[0], null,
                null));
        mDatabase.getMapper().stopAddingDocuments(null);

        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device", null /* device key */, /* opened is */ true, new MtpRoot[0], null,
                null));
        mDatabase.getMapper().stopAddingDocuments(null);

        try (final Cursor cursor =
                mDatabase.queryRoots(resources, strings(DocumentsContract.Root.COLUMN_ROOT_ID))) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToNext());
            assertEquals(1, cursor.getLong(0));
        }
    }

    public void testMappingFailsWithoutKey() throws FileNotFoundException {
        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device", null /* device key */, /* opened is */ true, new MtpRoot[0], null,
                null));
        mDatabase.getMapper().stopAddingDocuments(null);

        // MTP identifier is cleared here. Mapping no longer works without device key.
        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().stopAddingDocuments(null);

        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device", null /* device key */, /* opened is */ true, new MtpRoot[0], null,
                null));
        mDatabase.getMapper().stopAddingDocuments(null);

        try (final Cursor cursor =
                mDatabase.queryRoots(resources, strings(DocumentsContract.Root.COLUMN_ROOT_ID))) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToNext());
            assertEquals(2, cursor.getLong(0));
        }
    }

    public void testUpdateDocumentWithoutChange() throws FileNotFoundException {
        mDatabase.getMapper().startAddingDocuments(null);
        assertTrue(mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device", "device_key", /* opened is */ true, new MtpRoot[0], null,
                null)));
        assertFalse(mDatabase.getMapper().stopAddingDocuments(null));

        mDatabase.getMapper().startAddingDocuments(null);
        assertFalse(mDatabase.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device", "device_key", /* opened is */ true, new MtpRoot[0], null,
                null)));
        assertFalse(mDatabase.getMapper().stopAddingDocuments(null));
    }

    public void testSetBootCount() {
        assertEquals(0, mDatabase.getLastBootCount());
        mDatabase.setLastBootCount(10);
        assertEquals(10, mDatabase.getLastBootCount());
        try {
            mDatabase.setLastBootCount(-1);
            fail();
        } catch (IllegalArgumentException e) {}
    }

    public void testCleanDatabase() throws FileNotFoundException {
        // Add tree.
        addTestDevice();
        addTestStorage("1");
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(100, "apple.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(101, "orange.txt", MtpConstants.FORMAT_TEXT, 1024),
        }, new long[] { 1024L, 1024L });
        mDatabase.getMapper().stopAddingDocuments("2");

        // Disconnect the device.
        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().stopAddingDocuments(null);

        // Clean database.
        mDatabase.cleanDatabase(new Uri[] {
                DocumentsContract.buildDocumentUri(MtpDocumentsProvider.AUTHORITY, "3")
        });

        // Add tree again.
        addTestDevice();
        addTestStorage("1");
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
                createDocument(100, "apple.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(101, "orange.txt", MtpConstants.FORMAT_TEXT, 1024),
        }, new long[] { 1024L, 1024L });
        mDatabase.getMapper().stopAddingDocuments("2");

        try (final Cursor cursor = mDatabase.queryChildDocuments(
                strings(COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), "2")) {
            assertEquals(2, cursor.getCount());

            // Persistent uri uses the same ID.
            cursor.moveToNext();
            assertEquals("3", cursor.getString(0));
            assertEquals("apple.txt", cursor.getString(1));

            // Others does not.
            cursor.moveToNext();
            assertEquals("5", cursor.getString(0));
            assertEquals("orange.txt", cursor.getString(1));
        }
    }

    public void testFormatCodeForMpeg() throws FileNotFoundException {
        addTestDevice();
        addTestStorage("1");
        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(0, "2", OPERATIONS_SUPPORTED, new MtpObjectInfo[] {
            createDocument(100, "audio.m4a", MtpConstants.FORMAT_MPEG, 1000),
            createDocument(101, "video.m4v", MtpConstants.FORMAT_MPEG, 1000),
            createDocument(102, "unknown.mp4", MtpConstants.FORMAT_MPEG, 1000),
            createDocument(103, "inconsistent.txt", MtpConstants.FORMAT_MPEG, 1000),
            createDocument(104, "noext", MtpConstants.FORMAT_UNDEFINED, 1000),
        }, new long[] { 1000L, 1000L, 1000L, 1000L, 1000L });
        mDatabase.getMapper().stopAddingDocuments("2");
        try (final Cursor cursor = mDatabase.queryChildDocuments(
                strings(COLUMN_DISPLAY_NAME,  COLUMN_MIME_TYPE),
                "2")) {
            assertEquals(5, cursor.getCount());
            cursor.moveToNext();
            assertEquals("audio.m4a", cursor.getString(0));
            assertEquals("audio/mp4", cursor.getString(1));
            cursor.moveToNext();
            assertEquals("video.m4v", cursor.getString(0));
            assertEquals("video/mp4", cursor.getString(1));
            cursor.moveToNext();
            // Assume that the file is video as we don't have any hints to find out if the file is
            // video or audio.
            assertEquals("unknown.mp4", cursor.getString(0));
            assertEquals("video/mp4", cursor.getString(1));
            // Don't return mime type that is inconsistent with format code.
            cursor.moveToNext();
            assertEquals("inconsistent.txt", cursor.getString(0));
            assertEquals("video/mp4", cursor.getString(1));
            cursor.moveToNext();
            assertEquals("noext", cursor.getString(0));
            assertEquals("application/octet-stream", cursor.getString(1));
        }
    }

    private void addTestDevice() throws FileNotFoundException {
        TestUtil.addTestDevice(mDatabase);
    }

    private void addTestStorage(String parentId) throws FileNotFoundException {
        TestUtil.addTestStorage(mDatabase, parentId);
    }
}
