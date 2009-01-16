package android.content;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.SortedSet;

/** Unit test for {@link android.content.AbstractTableMerger}. */
public class AbstractTableMergerTest extends AndroidTestCase {
    MockSyncableContentProvider mRealProvider;
    MockSyncableContentProvider mTempProvider;
    MockTableMerger mMerger;
    MockSyncContext mSyncContext;

    static final String TABLE_NAME = "items";
    static final String DELETED_TABLE_NAME = "deleted_items";
    static final Uri CONTENT_URI = Uri.parse("content://testdata");
    static final Uri TABLE_URI = Uri.withAppendedPath(CONTENT_URI, TABLE_NAME);
    static final Uri DELETED_TABLE_URI = Uri.withAppendedPath(CONTENT_URI, DELETED_TABLE_NAME);

    private final String ACCOUNT = "account@goo.com";

    private final ArrayList<Expectation> mExpectations = Lists.newArrayList();

    static class Expectation {
        enum Type {
            UPDATE,
            INSERT,
            DELETE,
            RESOLVE
        }

        Type mType;
        ContentValues mValues;
        Long mLocalRowId;

        Expectation(Type type, Long localRowId, ContentValues values) {
            mType = type;
            mValues = values;
            mLocalRowId = localRowId;
            if (type == Type.DELETE) {
                assertNull(values);
            } else {
                assertFalse(values.containsKey("_id"));
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSyncContext = new MockSyncContext();
        mRealProvider = new MockSyncableContentProvider();
        mTempProvider = mRealProvider.getTemporaryInstance();
        mMerger = new MockTableMerger(mRealProvider.getDatabase(),
                TABLE_NAME, TABLE_URI, DELETED_TABLE_NAME, DELETED_TABLE_URI);
        mExpectations.clear();
    }

    ContentValues newValues(String data, String syncId, String syncAccount,
            String syncTime, String syncVersion, Long syncLocalId) {
        ContentValues values = new ContentValues();
        if (data != null) values.put("data", data);
        if (syncTime != null) values.put("_sync_time", syncTime);
        if (syncVersion != null) values.put("_sync_version", syncVersion);
        if (syncId != null) values.put("_sync_id", syncId);
        if (syncAccount != null) values.put("_sync_account", syncAccount);
        values.put("_sync_local_id", syncLocalId);
        values.put("_sync_dirty", 0);
        return values;
    }

    ContentValues newDeletedValues(String syncId, String syncAccount, String syncVersion,
            Long syncLocalId) {
        ContentValues values = new ContentValues();
        if (syncVersion != null) values.put("_sync_version", syncVersion);
        if (syncId != null) values.put("_sync_id", syncId);
        if (syncAccount != null) values.put("_sync_account", syncAccount);
        if (syncLocalId != null) values.put("_sync_local_id", syncLocalId);
        return values;
    }

    ContentValues newModifyData(String data) {
        ContentValues values = new ContentValues();
        values.put("data", data);
        values.put("_sync_dirty", 1);
        return values;
    }

    // Want to test adding, changing, deleting entries to a provider that has extra entries
    // before and after the entries being changed.
    public void testInsert() {
        // add rows to the real provider
        // add new row to the temp provider
        final ContentValues row1 = newValues("d1", "si1", ACCOUNT, "st1", "sv1", null);
        mTempProvider.insert(TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.INSERT, null /* syncLocalId */, row1));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    public void testUpdateWithLocalId() {
        // add rows to the real provider
        // add new row to the temp provider that matches an unsynced row in the real provider
        final ContentValues row1 = newValues("d1", "si1", ACCOUNT, "st1", "sv1", 11L);
        mTempProvider.insert(TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.UPDATE, 11L, row1));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    public void testUpdateWithoutLocalId() {
        // add rows to the real provider
        Uri i1 = mRealProvider.insert(TABLE_URI,
                newValues("d1", "si1", ACCOUNT, "st1", "sv1", null));

        // add new row to the temp provider that matches an unsynced row in the real provider
        final ContentValues row1 = newValues("d2", "si1", ACCOUNT, "st2", "sv2", null);
        mTempProvider.insert(TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.UPDATE, ContentUris.parseId(i1), row1));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    public void testResolve() {
        // add rows to the real provider
        Uri i1 = mRealProvider.insert(TABLE_URI,
                newValues("d1", "si1", ACCOUNT, "st1", "sv1", null));
        mRealProvider.update(TABLE_URI, newModifyData("d2"), null, null);

        // add row to the temp provider that matches a dirty, synced row in the real provider
        final ContentValues row1 = newValues("d3", "si1", ACCOUNT, "st2", "sv2", null);
        mTempProvider.insert(TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.RESOLVE, ContentUris.parseId(i1), row1));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    public void testResolveWithLocalId() {
        // add rows to the real provider
        Uri i1 = mRealProvider.insert(TABLE_URI,
                newValues("d1", "si1", ACCOUNT, "st1", "sv1", null));
        mRealProvider.update(TABLE_URI, newModifyData("d2"), null, null);

        // add row to the temp provider that matches a dirty, synced row in the real provider
        ContentValues row1 = newValues("d2", "si1", ACCOUNT, "st2", "sv2", ContentUris.parseId(i1));
        mTempProvider.insert(TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.UPDATE, ContentUris.parseId(i1), row1));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    public void testDeleteRowAfterDelete() {
        // add rows to the real provider
        Uri i1 = mRealProvider.insert(TABLE_URI,
                newValues("d1", "si1", ACCOUNT, "st1", "sv1", null));

        // add a deleted record to the temp provider
        ContentValues row1 = newDeletedValues(null, null, null, ContentUris.parseId(i1));
        mTempProvider.insert(DELETED_TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.DELETE, ContentUris.parseId(i1), null));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    public void testDeleteRowAfterInsert() {
        // add rows to the real provider
        Uri i1 = mRealProvider.insert(TABLE_URI, newModifyData("d1"));

        // add a deleted record to the temp provider
        ContentValues row1 = newDeletedValues(null, null, null, ContentUris.parseId(i1));
        mTempProvider.insert(DELETED_TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.DELETE, ContentUris.parseId(i1), null));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    public void testDeleteRowAfterUpdate() {
        // add rows to the real provider
        Uri i1 = mRealProvider.insert(TABLE_URI,
                newValues("d1", "si1", ACCOUNT, "st1", "sv1", null));

        // add a deleted record to the temp provider
        ContentValues row1 = newDeletedValues("si1", ACCOUNT, "sv1", ContentUris.parseId(i1));
        mTempProvider.insert(DELETED_TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.DELETE, ContentUris.parseId(i1), null));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    public void testDeleteRowFromServer() {
        // add rows to the real provider
        Uri i1 = mRealProvider.insert(TABLE_URI,
                newValues("d1", "si1", ACCOUNT, "st1", "sv1", null));

        // add a deleted record to the temp provider
        ContentValues row1 = newDeletedValues("si1", ACCOUNT, "sv1", null);
        mTempProvider.insert(DELETED_TABLE_URI, row1);

        // add expected callbacks to merger
        mExpectations.add(new Expectation(Expectation.Type.DELETE, ContentUris.parseId(i1), null));

        // run merger
        SyncResult syncResult = new SyncResult();
        mMerger.mergeServerDiffs(mSyncContext, ACCOUNT, mTempProvider, syncResult);

        // check that all expectations were met
        assertEquals("not all expectations were met", 0, mExpectations.size());
    }

    class MockTableMerger extends AbstractTableMerger {
        public MockTableMerger(SQLiteDatabase database, String table, Uri tableURL,
                String deletedTable, Uri deletedTableURL) {
            super(database, table, tableURL, deletedTable, deletedTableURL);
        }

        public void insertRow(ContentProvider diffs, Cursor diffsCursor) {
            Expectation expectation = mExpectations.remove(0);
            checkExpectation(expectation,
                    Expectation.Type.INSERT, null /* syncLocalId */, diffsCursor);
        }

        public void updateRow(long localPersonID, ContentProvider diffs, Cursor diffsCursor) {
            Expectation expectation = mExpectations.remove(0);
            checkExpectation(expectation, Expectation.Type.UPDATE, localPersonID, diffsCursor);
        }

        public void resolveRow(long localPersonID, String syncID, ContentProvider diffs,
                Cursor diffsCursor) {
            Expectation expectation = mExpectations.remove(0);
            checkExpectation(expectation, Expectation.Type.RESOLVE, localPersonID, diffsCursor);
        }

        @Override
        public void deleteRow(Cursor cursor) {
            Expectation expectation = mExpectations.remove(0);
            assertEquals(expectation.mType, Expectation.Type.DELETE);
            assertNotNull(expectation.mLocalRowId);
            final long localRowId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
            assertEquals((long)expectation.mLocalRowId, localRowId);
            cursor.moveToNext();
            mDb.delete(TABLE_NAME, "_id=" + localRowId, null);
        }

        protected void notifyChanges() {
            throw new UnsupportedOperationException();
        }

        void checkExpectation(Expectation expectation,
                Expectation.Type actualType, Long localRowId,
                Cursor cursor) {
            assertEquals(expectation.mType, actualType);
            assertEquals(expectation.mLocalRowId, localRowId);

            final SortedSet<String> actualKeys = Sets.newSortedSet(cursor.getColumnNames());
            final SortedSet<String> expectedKeys = Sets.newSortedSet();
            for (Map.Entry<String, Object> entry : expectation.mValues.valueSet()) {
                expectedKeys.add(entry.getKey());
            }
            actualKeys.remove("_id");
            actualKeys.remove("_sync_mark");
            actualKeys.remove("_sync_local_id");
            expectedKeys.remove("_sync_local_id");
            expectedKeys.remove("_id");
            assertEquals("column mismatch",
                    TextUtils.join(",", expectedKeys), TextUtils.join(",", actualKeys));

//            if (localRowId != null) {
//                assertEquals((long) localRowId,
//                        cursor.getLong(cursor.getColumnIndexOrThrow("_sync_local_id")));
//            } else {
//                assertTrue("unexpected _sync_local_id, "
//                        + cursor.getLong(cursor.getColumnIndexOrThrow("_sync_local_id")),
//                        cursor.isNull(cursor.getColumnIndexOrThrow("_sync_local_id")));
//            }

            for (String name : cursor.getColumnNames()) {
                if ("_id".equals(name)) {
                    continue;
                }
                if (cursor.isNull(cursor.getColumnIndexOrThrow(name))) {
                    assertNull(expectation.mValues.getAsString(name));
                } else {
                    String actualValue =
                            cursor.getString(cursor.getColumnIndexOrThrow(name));
                    assertEquals("mismatch on column " + name,
                            expectation.mValues.getAsString(name), actualValue);
                }
            }
        }
    }

    class MockSyncableContentProvider extends SyncableContentProvider {
        SQLiteDatabase mDb;
        boolean mIsTemporary;
        boolean mContainsDiffs;

        private final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

        private static final int MATCHER_ITEMS = 0;
        private static final int MATCHER_DELETED_ITEMS = 1;

        public MockSyncableContentProvider() {
            mIsTemporary = false;
            setContainsDiffs(false);
            sURIMatcher.addURI(CONTENT_URI.getAuthority(), "items", MATCHER_ITEMS);
            sURIMatcher.addURI(CONTENT_URI.getAuthority(), "deleted_items", MATCHER_DELETED_ITEMS);

            mDb = SQLiteDatabase.create(null);
            mDb.execSQL("CREATE TABLE items ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "data TEXT, "
                    + "_sync_time TEXT, "
                    + "_sync_version TEXT, "
                    + "_sync_id TEXT, "
                    + "_sync_local_id INTEGER, "
                    + "_sync_dirty INTEGER NOT NULL DEFAULT 0, "
                    + "_sync_account TEXT, "
                    + "_sync_mark INTEGER)");

            mDb.execSQL("CREATE TABLE deleted_items ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "_sync_version TEXT, "
                    + "_sync_id TEXT, "
                    + "_sync_local_id INTEGER, "
                    + "_sync_account TEXT, "
                    + "_sync_mark INTEGER)");
        }

        public boolean onCreate() {
            throw new UnsupportedOperationException();
        }

        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            int match = sURIMatcher.match(uri);
            switch (match) {
                case MATCHER_ITEMS:
                    return mDb.query(TABLE_NAME, projection, selection, selectionArgs,
                            null, null, sortOrder);
                case MATCHER_DELETED_ITEMS:
                    return mDb.query(DELETED_TABLE_NAME, projection, selection, selectionArgs,
                            null, null, sortOrder);
                default:
                    throw new UnsupportedOperationException("Cannot query URL: " + uri);
            }
        }

        public String getType(Uri uri) {
            throw new UnsupportedOperationException();
        }

        public Uri insert(Uri uri, ContentValues values) {
            int match = sURIMatcher.match(uri);
            switch (match) {
                case MATCHER_ITEMS: {
                    long id = mDb.insert(TABLE_NAME, "_id", values);
                    return CONTENT_URI.buildUpon().appendPath(String.valueOf(id)).build();
                }
                case MATCHER_DELETED_ITEMS: {
                    long id = mDb.insert(DELETED_TABLE_NAME, "_id", values);
                    return CONTENT_URI.buildUpon().appendPath(String.valueOf(id)).build();
                }
                default:
                    throw new UnsupportedOperationException("Cannot query URL: " + uri);
            }
        }

        public int delete(Uri uri, String selection, String[] selectionArgs) {
            int match = sURIMatcher.match(uri);
            switch (match) {
                case MATCHER_ITEMS:
                    return mDb.delete(TABLE_NAME, selection, selectionArgs);
                case MATCHER_DELETED_ITEMS:
                    return mDb.delete(DELETED_TABLE_NAME, selection, selectionArgs);
                default:
                    throw new UnsupportedOperationException("Cannot query URL: " + uri);
            }
        }

        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            int match = sURIMatcher.match(uri);
            switch (match) {
                case MATCHER_ITEMS:
                    return mDb.update(TABLE_NAME, values, selection, selectionArgs);
                case MATCHER_DELETED_ITEMS:
                    return mDb.update(DELETED_TABLE_NAME, values, selection, selectionArgs);
                default:
                    throw new UnsupportedOperationException("Cannot query URL: " + uri);
            }
        }

        protected boolean isTemporary() {
            return mIsTemporary;
        }

        public void close() {
            throw new UnsupportedOperationException();
        }

        protected void bootstrapDatabase(SQLiteDatabase db) {
            throw new UnsupportedOperationException();
        }

        protected boolean upgradeDatabase(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new UnsupportedOperationException();
        }

        protected void onDatabaseOpened(SQLiteDatabase db) {
            throw new UnsupportedOperationException();
        }

        public MockSyncableContentProvider getTemporaryInstance() {
            MockSyncableContentProvider temp = new MockSyncableContentProvider();
            temp.mIsTemporary = true;
            temp.setContainsDiffs(true);
            return temp;
        }

        public SQLiteDatabase getDatabase() {
            return mDb;
        }

        public boolean getContainsDiffs() {
            return mContainsDiffs;
        }

        public void setContainsDiffs(boolean containsDiffs) {
            mContainsDiffs = containsDiffs;
        }

        protected Iterable<? extends AbstractTableMerger> getMergers() {
            throw new UnsupportedOperationException();
        }

        public boolean changeRequiresLocalSync(Uri uri) {
            throw new UnsupportedOperationException();
        }

        public void onSyncStart(SyncContext context, String account) {
            throw new UnsupportedOperationException();
        }

        public void onSyncStop(SyncContext context, boolean success) {
            throw new UnsupportedOperationException();
        }

        public String getSyncingAccount() {
            throw new UnsupportedOperationException();
        }

        public void merge(SyncContext context, SyncableContentProvider diffs,
                TempProviderSyncResult result, SyncResult syncResult) {
            throw new UnsupportedOperationException();
        }

        public void onSyncCanceled() {
            throw new UnsupportedOperationException();
        }

        public boolean isMergeCancelled() {
            return false;
        }

        protected int updateInternal(Uri url, ContentValues values, String selection,
                String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }

        protected int deleteInternal(Uri url, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }

        protected Uri insertInternal(Uri url, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        protected Cursor queryInternal(Uri url, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            throw new UnsupportedOperationException();
        }

        protected void onAccountsChanged(String[] accountsArray) {
            throw new UnsupportedOperationException();
        }

        protected void deleteRowsForRemovedAccounts(Map<String, Boolean> accounts, String table,
                String accountColumnName) {
            throw new UnsupportedOperationException();
        }

        public void wipeAccount(String account) {
            throw new UnsupportedOperationException();
        }

        public byte[] readSyncDataBytes(String account) {
            throw new UnsupportedOperationException();
        }

        public void writeSyncDataBytes(String account, byte[] data) {
            throw new UnsupportedOperationException();
        }
    }

    class MockSyncContext extends SyncContext {
        public MockSyncContext() {
            super(null);
        }

        @Override
        public void setStatusText(String message) {
        }
    }
}
