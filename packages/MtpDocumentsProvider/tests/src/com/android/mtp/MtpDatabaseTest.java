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
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.FileNotFoundException;

import static android.provider.DocumentsContract.Document.*;
import static com.android.mtp.MtpDatabase.strings;
import static com.android.mtp.MtpDatabaseConstants.*;

@SmallTest
public class MtpDatabaseTest extends AndroidTestCase {
    private final String[] COLUMN_NAMES = new String[] {
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

    public void testPutRootDocuments() throws Exception {
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 1, "Device", "Storage", 1000, 2000, ""),
                new MtpRoot(0, 2, "Device", "Storage", 2000, 4000, ""),
                new MtpRoot(0, 3, "Device", "/@#%&<>Storage", 3000, 6000,"")
        });

        {
            final Cursor cursor = mDatabase.queryRootDocuments(COLUMN_NAMES);
            assertEquals(3, cursor.getCount());

            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(0, getInt(cursor, COLUMN_DEVICE_ID));
            assertEquals(1, getInt(cursor, COLUMN_STORAGE_ID));
            assertTrue(isNull(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, getString(cursor, COLUMN_MIME_TYPE));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            assertTrue(isNull(cursor, COLUMN_SUMMARY));
            assertTrue(isNull(cursor, COLUMN_LAST_MODIFIED));
            assertEquals(R.drawable.ic_root_mtp, getInt(cursor, COLUMN_ICON));
            assertEquals(0, getInt(cursor, COLUMN_FLAGS));
            assertEquals(1000, getInt(cursor, COLUMN_SIZE));
            assertEquals(
                    MtpDatabaseConstants.DOCUMENT_TYPE_STORAGE, getInt(cursor, COLUMN_DOCUMENT_TYPE));

            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals("Device /@#%&<>Storage", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(new String [] {
                    Root.COLUMN_ROOT_ID,
                    Root.COLUMN_FLAGS,
                    Root.COLUMN_ICON,
                    Root.COLUMN_TITLE,
                    Root.COLUMN_SUMMARY,
                    Root.COLUMN_DOCUMENT_ID,
                    Root.COLUMN_AVAILABLE_BYTES,
                    Root.COLUMN_CAPACITY_BYTES
            });
            assertEquals(3, cursor.getCount());

            cursor.moveToNext();
            assertEquals(1, cursor.getInt(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertEquals(R.drawable.ic_root_mtp, cursor.getInt(2));
            assertEquals("Device Storage", cursor.getString(3));
            assertTrue(cursor.isNull(4));
            assertEquals(1, cursor.getInt(5));
            assertEquals(1000, cursor.getInt(6));
            assertEquals(2000, cursor.getInt(7));

            cursor.moveToNext();
            assertEquals(2, cursor.getInt(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertEquals(R.drawable.ic_root_mtp, cursor.getInt(2));
            assertEquals("Device Storage", cursor.getString(3));
            assertTrue(cursor.isNull(4));
            assertEquals(2, cursor.getInt(5));
            assertEquals(2000, cursor.getInt(6));
            assertEquals(4000, cursor.getInt(7));

            cursor.moveToNext();
            assertEquals(3, cursor.getInt(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertEquals(R.drawable.ic_root_mtp, cursor.getInt(2));
            assertEquals("Device /@#%&<>Storage", cursor.getString(3));
            assertTrue(cursor.isNull(4));
            assertEquals(3, cursor.getInt(5));
            assertEquals(3000, cursor.getInt(6));
            assertEquals(6000, cursor.getInt(7));

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
        mDatabase.getMapper().startAddingDocuments("parentId");
        mDatabase.getMapper().putChildDocuments(0, "parentId", new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(101, "image.jpg", MtpConstants.FORMAT_EXIF_JPEG, 2 * 1024 * 1024),
                createDocument(102, "music.mp3", MtpConstants.FORMAT_MP3, 3 * 1024 * 1024)
        });

        final Cursor cursor = mDatabase.queryChildDocuments(COLUMN_NAMES, "parentId");
        assertEquals(3, cursor.getCount());

        cursor.moveToNext();
        assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
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
        assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
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
        assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
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

    public void testRestoreIdForRootDocuments() throws Exception {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };

        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 1000, 0, ""),
                new MtpRoot(0, 101, "Device", "Storage B", 1001, 0, "")
        });

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(100, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage A", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(101, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage B", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(1000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(1001, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }

        mDatabase.getMapper().clearMapping();

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertTrue(isNull(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage A", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertTrue(isNull(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage B", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(1000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(1001, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }

        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage A", 2000, 0, ""),
                new MtpRoot(0, 202, "Device", "Storage C", 2002, 0, "")
        });

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(3, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertTrue(isNull(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage A", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertTrue(isNull(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage B", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(4, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(202, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage C", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(rootColumns);
            assertEquals(3, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(1000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(1001, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.moveToNext();
            assertEquals(4, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(2002, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }

        mDatabase.getMapper().stopAddingDocuments("deviceDocId");

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage A", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(4, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(202, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage C", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(2000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.moveToNext();
            assertEquals(4, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(2002, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }
    }

    public void testRestoreIdForChildDocuments() throws Exception {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_OBJECT_HANDLE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        mDatabase.getMapper().startAddingDocuments("parentId");
        mDatabase.getMapper().putChildDocuments(0, "parentId", new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(101, "image.jpg", MtpConstants.FORMAT_EXIF_JPEG, 2 * 1024 * 1024),
                createDocument(102, "music.mp3", MtpConstants.FORMAT_MP3, 3 * 1024 * 1024)
        });
        mDatabase.getMapper().clearMapping();

        {
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "parentId");
            assertEquals(3, cursor.getCount());

            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertTrue(isNull(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals("note.txt", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertTrue(isNull(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals("image.jpg", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertTrue(isNull(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals("music.mp3", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.close();
        }

        mDatabase.getMapper().startAddingDocuments("parentId");
        mDatabase.getMapper().putChildDocuments(0, "parentId", new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                createDocument(203, "video.mp4", MtpConstants.FORMAT_MP4_CONTAINER, 1024),
        });

        {
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "parentId");
            assertEquals(4, cursor.getCount());

            cursor.moveToPosition(3);
            assertEquals(5, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(203, getInt(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals("video.mp4", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.close();
        }

        mDatabase.getMapper().stopAddingDocuments("parentId");

        {
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "parentId");
            assertEquals(2, cursor.getCount());

            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_OBJECT_HANDLE));
            assertEquals("note.txt", getString(cursor, COLUMN_DISPLAY_NAME));

            cursor.moveToNext();
            assertEquals(5, getInt(cursor, COLUMN_DOCUMENT_ID));
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
        mDatabase.getMapper().startAddingDocuments("deviceDocIdA");
        mDatabase.getMapper().startAddingDocuments("deviceDocIdB");
        mDatabase.getMapper().putRootDocuments("deviceDocIdA", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage", 0, 0, "")
        });
        mDatabase.getMapper().putRootDocuments("deviceDocIdB", resources, new MtpRoot[] {
                new MtpRoot(1, 100, "Device", "Storage", 0, 0, "")
        });

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(100, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(100, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(rootColumns);
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

        mDatabase.getMapper().startAddingDocuments("deviceDocIdA");
        mDatabase.getMapper().startAddingDocuments("deviceDocIdB");
        mDatabase.getMapper().putRootDocuments("deviceDocIdA", resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 2000, 0, "")
        });
        mDatabase.getMapper().putRootDocuments("deviceDocIdB", resources, new MtpRoot[] {
                new MtpRoot(1, 300, "Device", "Storage", 3000, 0, "")
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocIdA");
        mDatabase.getMapper().stopAddingDocuments("deviceDocIdB");

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(300, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }

        {
            final Cursor cursor = mDatabase.queryRoots(rootColumns);
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

        mDatabase.getMapper().startAddingDocuments("parentId1");
        mDatabase.getMapper().startAddingDocuments("parentId2");
        mDatabase.getMapper().putChildDocuments(0, "parentId1", new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        mDatabase.getMapper().putChildDocuments(0, "parentId2", new MtpObjectInfo[] {
                createDocument(101, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        mDatabase.getMapper().clearMapping();

        mDatabase.getMapper().startAddingDocuments("parentId1");
        mDatabase.getMapper().startAddingDocuments("parentId2");
        mDatabase.getMapper().putChildDocuments(0, "parentId1", new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        mDatabase.getMapper().putChildDocuments(0, "parentId2", new MtpObjectInfo[] {
                createDocument(201, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        mDatabase.getMapper().stopAddingDocuments("parentId1");

        {
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "parentId1");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_OBJECT_HANDLE));
            cursor.close();
        }
        {
            final Cursor cursor = mDatabase.queryChildDocuments(columns, "parentId2");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertTrue(isNull(cursor, COLUMN_OBJECT_HANDLE));
            cursor.close();
        }
    }

    public void testClearMtpIdentifierBeforeResolveRootDocuments() {
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };

        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage", 0, 0, ""),
        });
        mDatabase.getMapper().clearMapping();

        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 2000, 0, ""),
        });
        mDatabase.getMapper().clearMapping();

        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 300, "Device", "Storage", 3000, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(300, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }
        {
            final Cursor cursor = mDatabase.queryRoots(rootColumns);
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
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };

        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage", 0, 0, ""),
        });
        mDatabase.getMapper().clearMapping();

        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 2000, 0, ""),
                new MtpRoot(0, 201, "Device", "Storage", 2001, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");

        {
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(200, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.moveToNext();
            assertEquals(3, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(201, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }
        {
            final Cursor cursor = mDatabase.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals(2, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(2000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.moveToNext();
            assertEquals(3, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(2001, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }
    }

    public void testReplaceExistingRoots() {
        // The client code should be able to replace existing rows with new information.
        // Add one.
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");
        // Replace it.
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage B", 1000, 1000, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");
        {
            final String[] columns = new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    MtpDatabaseConstants.COLUMN_STORAGE_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            };
            final Cursor cursor = mDatabase.queryRootDocuments(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, COLUMN_DOCUMENT_ID));
            assertEquals(100, getInt(cursor, COLUMN_STORAGE_ID));
            assertEquals("Device Storage B", getString(cursor, COLUMN_DISPLAY_NAME));
            cursor.close();
        }
        {
            final String[] columns = new String[] {
                    Root.COLUMN_ROOT_ID,
                    Root.COLUMN_AVAILABLE_BYTES
            };
            final Cursor cursor = mDatabase.queryRoots(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals(1, getInt(cursor, Root.COLUMN_ROOT_ID));
            assertEquals(1000, getInt(cursor, Root.COLUMN_AVAILABLE_BYTES));
            cursor.close();
        }
    }

    public void testFailToReplaceExisitingUnmappedRoots() {
        // The client code should not be able to replace rows before resolving 'unmapped' rows.
        // Add one.
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().clearMapping();
        final Cursor oldCursor = mDatabase.queryRoots(strings(Root.COLUMN_ROOT_ID));
        assertEquals(1, oldCursor.getCount());

        // Add one.
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 101, "Device", "Storage B", 1000, 1000, ""),
        });
        // Add one more before resolving unmapped documents.
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 102, "Device", "Storage B", 1000, 1000, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");

        // Because the roots shares the same name, the roots should have new IDs.
        final Cursor newCursor = mDatabase.queryRoots(strings(Root.COLUMN_ROOT_ID));
        assertEquals(2, newCursor.getCount());
        oldCursor.moveToNext();
        newCursor.moveToNext();
        assertFalse(oldCursor.getString(0).equals(newCursor.getString(0)));
        newCursor.moveToNext();
        assertFalse(oldCursor.getString(0).equals(newCursor.getString(0)));

        oldCursor.close();
        newCursor.close();
    }

    public void testQueryDocument() {
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");

        final Cursor cursor = mDatabase.queryDocument("1", strings(Document.COLUMN_DISPLAY_NAME));
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals("Device Storage A", cursor.getString(0));
        cursor.close();
    }

    public void testGetParentId() throws FileNotFoundException {
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putChildDocuments(
                0,
                "1",
                new MtpObjectInfo[] {
                        createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                });
        mDatabase.getMapper().stopAddingDocuments("1");

        assertEquals("1", mDatabase.getParentId("2"));
    }

    public void testDeleteDocument() {
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");

        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.getMapper().putChildDocuments(
                0,
                "1",
                new MtpObjectInfo[] {
                        createDocument(200, "dir", MtpConstants.FORMAT_ASSOCIATION, 1024),
                });
        mDatabase.getMapper().stopAddingDocuments("1");

        mDatabase.getMapper().startAddingDocuments("2");
        mDatabase.getMapper().putChildDocuments(
                0,
                "2",
                new MtpObjectInfo[] {
                        createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
                });
        mDatabase.getMapper().stopAddingDocuments("2");

        mDatabase.deleteDocument("2");

        {
            // Do not query deleted documents.
            final Cursor cursor =
                    mDatabase.queryChildDocuments(strings(Document.COLUMN_DOCUMENT_ID), "1");
            assertEquals(0, cursor.getCount());
            cursor.close();
        }

        {
            // Child document should be deleted also.
            final Cursor cursor =
                    mDatabase.queryDocument("3", strings(Document.COLUMN_DOCUMENT_ID));
            assertEquals(0, cursor.getCount());
            cursor.close();
        }
    }

    public void testPutNewDocument() {
        mDatabase.getMapper().startAddingDocuments("deviceDocId");
        mDatabase.getMapper().putRootDocuments("deviceDocId", resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
        });
        mDatabase.getMapper().stopAddingDocuments("deviceDocId");

        assertEquals(
                "2",
                mDatabase.putNewDocument(
                        0, "1", createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024)));

        {
            final Cursor cursor =
                    mDatabase.queryChildDocuments(strings(Document.COLUMN_DOCUMENT_ID), "1");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("2", cursor.getString(0));
            cursor.close();
        }

        // The new document should not be mapped with existing invalidated document.
        mDatabase.getMapper().clearMapping();
        mDatabase.getMapper().startAddingDocuments("1");
        mDatabase.putNewDocument(
                0,
                "1",
                createDocument(201, "note.txt", MtpConstants.FORMAT_TEXT, 1024));
        mDatabase.getMapper().stopAddingDocuments("1");

        {
            final Cursor cursor =
                    mDatabase.queryChildDocuments(strings(Document.COLUMN_DOCUMENT_ID), "1");
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("3", cursor.getString(0));
            cursor.close();
        }
    }

    public void testGetDocumentIdForDevice() {
        mDatabase.getMapper().startAddingDocuments(null);
        mDatabase.getMapper().putDeviceDocument(
                new MtpDeviceRecord(100, "Device", true, new MtpRoot[0]));
        mDatabase.getMapper().stopAddingDocuments(null);
        assertEquals("1", mDatabase.getDocumentIdForDevice(100));
    }
}
