package com.android.mtp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import com.android.internal.annotations.VisibleForTesting;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Database for MTP objects.
 * The object handle which is identifier for object in MTP protocol is not stable over sessions.
 * When we resume the process, we need to remap our document ID with MTP's object handle.
 * The database object remembers the map of document ID and fullpath, and helps to remap object
 * handle and document ID by comparing fullpath.
 * TODO: Remove @VisibleForTesting annotation when we start to use this class.
 */
@VisibleForTesting
class MtpDatabase {
    private static final int VERSION = 1;
    private static final String NAME = "mtp";

    private static final String TABLE_MTP_DOCUMENTS = "MtpDocuments";

    static final String COLUMN_DEVICE_ID = "device_id";
    static final String COLUMN_STORAGE_ID = "storage_id";
    static final String COLUMN_OBJECT_HANDLE = "object_handle";

    private static class OpenHelper extends SQLiteOpenHelper {
        private static final String CREATE_TABLE_QUERY =
                "CREATE TABLE " + TABLE_MTP_DOCUMENTS + " (" +
                DocumentsContract.Document.COLUMN_DOCUMENT_ID +
                    " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COLUMN_DEVICE_ID + " INTEGER NOT NULL," +
                COLUMN_STORAGE_ID + " INTEGER NOT NULL," +
                COLUMN_OBJECT_HANDLE + " INTEGER," +
                DocumentsContract.Document.COLUMN_MIME_TYPE + " TEXT," +
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " TEXT NOT NULL," +
                DocumentsContract.Document.COLUMN_SUMMARY + " TEXT," +
                DocumentsContract.Document.COLUMN_LAST_MODIFIED + " INTEGER," +
                DocumentsContract.Document.COLUMN_ICON + " INTEGER," +
                DocumentsContract.Document.COLUMN_FLAGS + " INTEGER NOT NULL," +
                DocumentsContract.Document.COLUMN_SIZE + " INTEGER NOT NULL);";

        public OpenHelper(Context context) {
            super(context, NAME, null, VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(CREATE_TABLE_QUERY);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new UnsupportedOperationException();
        }
    }

    private final SQLiteDatabase database;

    @VisibleForTesting
    MtpDatabase(Context context) {
        final OpenHelper helper = new OpenHelper(context);
        database = helper.getWritableDatabase();
    }

    @VisibleForTesting
    static void deleteDatabase(Context context) {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(NAME));
    }

    @VisibleForTesting
    Cursor queryChildDocuments(String[] columnNames) {
        return database.query(TABLE_MTP_DOCUMENTS, columnNames, null, null, null, null, null);
    }

    @VisibleForTesting
    void putRootDocument(MtpRoot root) throws Exception {
        database.beginTransaction();
        try {
            final ContentValues values = new ContentValues();
            values.put(COLUMN_DEVICE_ID, root.mDeviceId);
            values.put(COLUMN_STORAGE_ID, root.mStorageId);
            values.putNull(COLUMN_OBJECT_HANDLE);
            values.put(Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            values.put(Document.COLUMN_DISPLAY_NAME, root.mDescription);
            values.putNull(Document.COLUMN_SUMMARY);
            values.putNull(Document.COLUMN_LAST_MODIFIED);
            values.putNull(Document.COLUMN_ICON);
            values.put(Document.COLUMN_FLAGS, 0);
            values.put(Document.COLUMN_SIZE,
                    (int) Math.min(root.mMaxCapacity - root.mFreeSpace, Integer.MAX_VALUE));
            if (database.insert(TABLE_MTP_DOCUMENTS, null, values) == -1) {
                throw new Exception("Failed to add root document.");
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    @VisibleForTesting
    void putDocument(int deviceId, MtpObjectInfo info) throws Exception {
        database.beginTransaction();
        try {
            final String mimeType = CursorHelper.formatTypeToMimeType(info.getFormat());

            int flag = 0;
            if (info.getProtectionStatus() == 0) {
                flag |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    flag |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
                }
            }
            if (info.getThumbCompressedSize() > 0) {
                flag |= DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL;
            }

            final ContentValues values = new ContentValues();
            values.put(COLUMN_DEVICE_ID, deviceId);
            values.put(COLUMN_STORAGE_ID, info.getStorageId());
            values.put(COLUMN_OBJECT_HANDLE, info.getObjectHandle());
            values.put(Document.COLUMN_MIME_TYPE, mimeType);
            values.put(Document.COLUMN_DISPLAY_NAME, info.getName());
            values.putNull(Document.COLUMN_SUMMARY);
            values.put(
                    Document.COLUMN_LAST_MODIFIED,
                    info.getDateModified() != 0 ? info.getDateModified() : null);
            values.putNull(Document.COLUMN_ICON);
            values.put(Document.COLUMN_FLAGS, flag);
            values.put(Document.COLUMN_SIZE, info.getCompressedSize());
            if (database.insert(TABLE_MTP_DOCUMENTS, null, values) == -1) {
                throw new Exception("Failed to add document.");
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    @VisibleForTesting
    private String escape(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
    }
}
