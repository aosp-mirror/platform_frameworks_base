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
        MtpDatabase.COLUMN_FULL_PATH,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SUMMARY,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_ICON,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    };

    @Override
    public void tearDown() {
        MtpDatabase.deleteDatabase(getContext());
    }

    public void testPutRootDocument() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final MtpRoot root = new MtpRoot(
                0,
                1,
                "Device A",
                "Storage",
                1000,
                2000,
                "");
        database.putRootDocument(root);

        final MtpRoot duplicatedNameRoot = new MtpRoot(
                0,
                2,
                "Device A",
                "Storage",
                1000,
                2000,
                "");
        database.putRootDocument(duplicatedNameRoot);

        final MtpRoot strangeNameRoot = new MtpRoot(
                0,
                3,
                "Device A",
                "/@#%&<>Storage",
                1000,
                2000,
                "");
        database.putRootDocument(strangeNameRoot);

        final Cursor cursor = database.queryChildDocuments(COLUMN_NAMES);
        assertEquals(3, cursor.getCount());

        cursor.moveToNext();
        assertEquals("documentId", 1, cursor.getInt(0));
        assertEquals("deviceId", 0, cursor.getInt(1));
        assertEquals("storageId", 1, cursor.getInt(2));
        assertTrue("objectHandle", cursor.isNull(3));
        assertEquals("fullPath", "/0/Storage", cursor.getString(4));
        assertEquals("mimeType", DocumentsContract.Document.MIME_TYPE_DIR, cursor.getString(5));
        assertEquals("displayName", "Storage", cursor.getString(6));
        assertTrue("summary", cursor.isNull(7));
        assertTrue("lastModified", cursor.isNull(8));
        assertTrue("icon", cursor.isNull(9));
        assertEquals("flag", 0, cursor.getInt(10));
        assertEquals("size", 1000, cursor.getInt(11));

        cursor.moveToNext();
        assertEquals("documentId", 2, cursor.getInt(0));
        assertEquals("fullPath", "/0/Storage", cursor.getString(4));

        cursor.moveToNext();
        assertEquals("documentId", 3, cursor.getInt(0));
        assertEquals("fullPath", "/0/%2F%40%23%25%26%3C%3EStorage", cursor.getString(4));
    }

    public void testPutDocument() throws Exception {
        final MtpDatabase database = new MtpDatabase(getContext());
        final MtpObjectInfo.Builder builder = new MtpObjectInfo.Builder();
        builder.setObjectHandle(100);
        builder.setName("test.txt");
        builder.setStorageId(5);
        builder.setFormat(MtpConstants.FORMAT_TEXT);
        builder.setCompressedSize(1000);
        database.putDocument(0, "/0/Storage", builder.build());

        final Cursor cursor = database.queryChildDocuments(COLUMN_NAMES);
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals("documentId", 1, cursor.getInt(0));
        assertEquals("deviceId", 0, cursor.getInt(1));
        assertEquals("storageId", 5, cursor.getInt(2));
        assertEquals("objectHandle", 100, cursor.getInt(3));
        assertEquals("fullPath", "/0/Storage/test.txt", cursor.getString(4));
        assertEquals("mimeType", "text/plain", cursor.getString(5));
        assertEquals("displayName", "test.txt", cursor.getString(6));
        assertTrue("summary", cursor.isNull(7));
        assertTrue("lastModified", cursor.isNull(8));
        assertTrue("icon", cursor.isNull(9));
        assertEquals(
                "flag",
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
                cursor.getInt(10));
        assertEquals("size", 1000, cursor.getInt(11));
    }
}
