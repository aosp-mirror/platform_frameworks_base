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
import android.provider.DocumentsContract.Root;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

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
        DocumentsContract.Document.COLUMN_SIZE
    };

    private final TestResources resources = new TestResources();

    public void testPutRootDocuments() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 1, "Device", "Storage", 1000, 2000, ""),
                new MtpRoot(0, 2, "Device", "Storage", 2000, 4000, ""),
                new MtpRoot(0, 3, "Device", "/@#%&<>Storage", 3000, 6000,"")
        });

        {
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

        {
            final Cursor cursor = database.queryRoots(new String [] {
                    Root.COLUMN_ROOT_ID,
                    Root.COLUMN_FLAGS,
                    Root.COLUMN_ICON,
                    Root.COLUMN_TITLE,
                    Root.COLUMN_SUMMARY,
                    Root.COLUMN_DOCUMENT_ID + "_",
                    Root.COLUMN_AVAILABLE_BYTES,
                    Root.COLUMN_CAPACITY_BYTES
            });
            assertEquals(3, cursor.getCount());

            cursor.moveToNext();
            assertEquals(1, cursor.getInt(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertTrue(cursor.isNull(2));
            assertEquals("Device Storage", cursor.getString(3));
            assertTrue(cursor.isNull(4));
            assertEquals(1, cursor.getInt(5));
            assertEquals(1000, cursor.getInt(6));
            assertEquals(2000, cursor.getInt(7));

            cursor.moveToNext();
            assertEquals(2, cursor.getInt(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertTrue(cursor.isNull(2));
            assertEquals("Device Storage", cursor.getString(3));
            assertTrue(cursor.isNull(4));
            assertEquals(2, cursor.getInt(5));
            assertEquals(2000, cursor.getInt(6));
            assertEquals(4000, cursor.getInt(7));

            cursor.moveToNext();
            assertEquals(3, cursor.getInt(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertTrue(cursor.isNull(2));
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
        final MtpDatabase database = new MtpDatabase(getContext());
        database.startAddingChildDocuments("parentId");
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
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };
        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 1000, 0, ""),
                new MtpRoot(0, 101, "Device", "Storage B", 1001, 0, "")
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

        {
            final Cursor cursor = database.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 1, cursor.getInt(0));
            assertEquals("availableBytes", 1000, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals("rootId", 2, cursor.getInt(0));
            assertEquals("availableBytes", 1001, cursor.getInt(1));
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

        {
            final Cursor cursor = database.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 1, cursor.getInt(0));
            assertEquals("availableBytes", 1000, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals("rootId", 2, cursor.getInt(0));
            assertEquals("availableBytes", 1001, cursor.getInt(1));
            cursor.close();
        }

        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage A", 2000, 0, ""),
                new MtpRoot(0, 202, "Device", "Storage C", 2002, 0, "")
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

        {
            final Cursor cursor = database.queryRoots(rootColumns);
            assertEquals(3, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 1, cursor.getInt(0));
            assertEquals("availableBytes", 1000, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals("rootId", 2, cursor.getInt(0));
            assertEquals("availableBytes", 1001, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals("rootId", 4, cursor.getInt(0));
            assertEquals("availableBytes", 2002, cursor.getInt(1));
            cursor.close();
        }

        database.stopAddingRootDocuments(0);

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

        {
            final Cursor cursor = database.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 1, cursor.getInt(0));
            assertEquals("availableBytes", 2000, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals("rootId", 4, cursor.getInt(0));
            assertEquals("availableBytes", 2002, cursor.getInt(1));
            cursor.close();
        }
    }

    public void testRestoreIdForChildDocuments() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_OBJECT_HANDLE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        database.startAddingChildDocuments("parentId");
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

        database.startAddingChildDocuments("parentId");
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

        database.stopAddingChildDocuments("parentId");

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
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };
        database.startAddingRootDocuments(0);
        database.startAddingRootDocuments(1);
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

        {
            final Cursor cursor = database.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 1, cursor.getInt(0));
            assertEquals("availableBytes", 0, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals("rootId", 2, cursor.getInt(0));
            assertEquals("availableBytes", 0, cursor.getInt(1));
            cursor.close();
        }

        database.clearMapping();

        database.startAddingRootDocuments(0);
        database.startAddingRootDocuments(1);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 2000, 0, "")
        });
        database.putRootDocuments(1, resources, new MtpRoot[] {
                new MtpRoot(1, 300, "Device", "Storage", 3000, 0, "")
        });
        database.stopAddingRootDocuments(0);
        database.stopAddingRootDocuments(1);

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

        {
            final Cursor cursor = database.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 1, cursor.getInt(0));
            assertEquals("availableBytes", 2000, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals("rootId", 2, cursor.getInt(0));
            assertEquals("availableBytes", 3000, cursor.getInt(1));
            cursor.close();
        }
    }

    public void testRestoreIdForDifferentParents() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_OBJECT_HANDLE
        };

        database.startAddingChildDocuments("parentId1");
        database.startAddingChildDocuments("parentId2");
        database.putChildDocuments(0, "parentId1", new MtpObjectInfo[] {
                createDocument(100, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        database.putChildDocuments(0, "parentId2", new MtpObjectInfo[] {
                createDocument(101, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        database.clearMapping();

        database.startAddingChildDocuments("parentId1");
        database.startAddingChildDocuments("parentId2");
        database.putChildDocuments(0, "parentId1", new MtpObjectInfo[] {
                createDocument(200, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        database.putChildDocuments(0, "parentId2", new MtpObjectInfo[] {
                createDocument(201, "note.txt", MtpConstants.FORMAT_TEXT, 1024),
        });
        database.stopAddingChildDocuments("parentId1");

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
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };

        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage", 0, 0, ""),
        });
        database.clearMapping();

        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 2000, 0, ""),
        });
        database.clearMapping();

        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 300, "Device", "Storage", 3000, 0, ""),
        });
        database.stopAddingRootDocuments(0);

        {
            final Cursor cursor = database.queryRootDocuments(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("storageId", 300, cursor.getInt(1));
            assertEquals("name", "Device Storage", cursor.getString(2));
            cursor.close();
        }
        {
            final Cursor cursor = database.queryRoots(rootColumns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 1, cursor.getInt(0));
            assertEquals("availableBytes", 3000, cursor.getInt(1));
            cursor.close();
        }
    }

    public void testPutSameNameRootsAfterClearing() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final String[] columns = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                MtpDatabaseConstants.COLUMN_STORAGE_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        final String[] rootColumns = new String[] {
                Root.COLUMN_ROOT_ID,
                Root.COLUMN_AVAILABLE_BYTES
        };

        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage", 0, 0, ""),
        });
        database.clearMapping();

        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 200, "Device", "Storage", 2000, 0, ""),
                new MtpRoot(0, 201, "Device", "Storage", 2001, 0, ""),
        });
        database.stopAddingRootDocuments(0);

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
        {
            final Cursor cursor = database.queryRoots(rootColumns);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 2, cursor.getInt(0));
            assertEquals("availableBytes", 2000, cursor.getInt(1));
            cursor.moveToNext();
            assertEquals("rootId", 3, cursor.getInt(0));
            assertEquals("availableBytes", 2001, cursor.getInt(1));
            cursor.close();
        }
    }

    public void testReplaceExistingRoots() {
        // The client code should be able to replace exisitng rows with new information.
        final MtpDatabase database = new MtpDatabase(getContext());
        // Add one.
        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
        });
        database.stopAddingRootDocuments(0);
        // Replace it.
        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage B", 1000, 1000, ""),
        });
        database.stopAddingRootDocuments(0);
        {
            final String[] columns = new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    MtpDatabaseConstants.COLUMN_STORAGE_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            };
            final Cursor cursor = database.queryRootDocuments(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("documentId", 1, cursor.getInt(0));
            assertEquals("storageId", 100, cursor.getInt(1));
            assertEquals("name", "Device Storage B", cursor.getString(2));
            cursor.close();
        }
        {
            final String[] columns = new String[] {
                    Root.COLUMN_ROOT_ID,
                    Root.COLUMN_AVAILABLE_BYTES
            };
            final Cursor cursor = database.queryRoots(columns);
            assertEquals(1, cursor.getCount());
            cursor.moveToNext();
            assertEquals("rootId", 1, cursor.getInt(0));
            assertEquals("availableBytes", 1000, cursor.getInt(1));
            cursor.close();
        }
    }

    public void _testFailToReplaceExisitingUnmappedRoots() {
        // The client code should not be able to replace rows before resolving 'unmapped' rows.
        final MtpDatabase database = new MtpDatabase(getContext());
        // Add one.
        database.startAddingRootDocuments(0);
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage A", 0, 0, ""),
        });
        database.clearMapping();
        // Add one.
        database.putRootDocuments(0, resources, new MtpRoot[] {
                new MtpRoot(0, 100, "Device", "Storage B", 1000, 1000, ""),
        });
        // Add one more before resolving unmapped documents.
        try {
            database.putRootDocuments(0, resources, new MtpRoot[] {
                    new MtpRoot(0, 100, "Device", "Storage B", 1000, 1000, ""),
            });
            fail();
        } catch (Throwable e) {
            assertTrue(e instanceof Error);
        }
    }
}
