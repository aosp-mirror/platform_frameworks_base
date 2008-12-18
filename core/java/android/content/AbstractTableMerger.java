/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Debug;
import static android.provider.SyncConstValue._SYNC_ACCOUNT;
import static android.provider.SyncConstValue._SYNC_DIRTY;
import static android.provider.SyncConstValue._SYNC_ID;
import static android.provider.SyncConstValue._SYNC_LOCAL_ID;
import static android.provider.SyncConstValue._SYNC_MARK;
import static android.provider.SyncConstValue._SYNC_VERSION;
import android.text.TextUtils;
import android.util.Log;

/**
 * @hide
 */
public abstract class AbstractTableMerger
{
    private ContentValues mValues;

    protected SQLiteDatabase mDb;
    protected String mTable;
    protected Uri mTableURL;
    protected String mDeletedTable;
    protected Uri mDeletedTableURL;
    static protected ContentValues mSyncMarkValues;
    static private boolean TRACE;

    static {
        mSyncMarkValues = new ContentValues();
        mSyncMarkValues.put(_SYNC_MARK, 1);
        TRACE = false;
    }

    private static final String TAG = "AbstractTableMerger";
    private static final String[] syncDirtyProjection =
            new String[] {_SYNC_DIRTY, "_id", _SYNC_ID, _SYNC_VERSION};
    private static final String[] syncIdAndVersionProjection =
            new String[] {_SYNC_ID, _SYNC_VERSION};

    private volatile boolean mIsMergeCancelled;

    private static final String SELECT_MARKED = _SYNC_MARK + "> 0 and " + _SYNC_ACCOUNT + "=?";

    private static final String SELECT_BY_ID_AND_ACCOUNT =
            _SYNC_ID +"=? and " + _SYNC_ACCOUNT + "=?";

    private static final String SELECT_UNSYNCED = ""
            + _SYNC_DIRTY + " > 0 and (" + _SYNC_ACCOUNT + "=? or " + _SYNC_ACCOUNT + " is null)";

    public AbstractTableMerger(SQLiteDatabase database,
            String table, Uri tableURL, String deletedTable,
            Uri deletedTableURL)
    {
        mDb = database;
        mTable = table;
        mTableURL = tableURL;
        mDeletedTable = deletedTable;
        mDeletedTableURL = deletedTableURL;
        mValues = new ContentValues();
    }

    public abstract void insertRow(ContentProvider diffs,
            Cursor diffsCursor);
    public abstract void updateRow(long localPersonID,
            ContentProvider diffs, Cursor diffsCursor);
    public abstract void resolveRow(long localPersonID,
            String syncID, ContentProvider diffs, Cursor diffsCursor);

    /**
     * This is called when it is determined that a row should be deleted from the
     * ContentProvider. The localCursor is on a table from the local ContentProvider
     * and its current position is of the row that should be deleted. The localCursor
     * contains the complete projection of the table.
     * <p>
     * It is the responsibility of the implementation of this method to ensure that the cursor
     * points to the next row when this method returns, either by calling Cursor.deleteRow() or
     * Cursor.next().
     *
     * @param localCursor The Cursor into the local table, which points to the row that
     *   is to be deleted.
     */
    public void deleteRow(Cursor localCursor) {
        localCursor.deleteRow();
    }

    /**
     * After {@link #merge} has completed, this method is called to send
     * notifications to {@link android.database.ContentObserver}s of changes
     * to the containing {@link ContentProvider}.  These notifications likely
     * do not want to request a sync back to the network.
     */
    protected abstract void notifyChanges();

    private static boolean findInCursor(Cursor cursor, int column, String id) {
        while (!cursor.isAfterLast() && !cursor.isNull(column)) {
            int comp = id.compareTo(cursor.getString(column));
            if (comp > 0) {
                cursor.moveToNext();
                continue;
            }
            return comp == 0;
        }
        return false;
    }

    public void onMergeCancelled() {
        mIsMergeCancelled = true;
    }

    /**
     * Carry out a merge of the given diffs, and add the results to
     * the given MergeResult.  If we are the first merge to find
     * client-side diffs, we'll use the given ContentProvider to
     * construct a temporary instance to hold them.
     */
    public void merge(final SyncContext context,
            final String account,
            final SyncableContentProvider serverDiffs,
            TempProviderSyncResult result,
            SyncResult syncResult, SyncableContentProvider temporaryInstanceFactory) {
        mIsMergeCancelled = false;
        if (serverDiffs != null) {
            if (!mDb.isDbLockedByCurrentThread()) {
                throw new IllegalStateException("this must be called from within a DB transaction");
            }
            mergeServerDiffs(context, account, serverDiffs, syncResult);
            notifyChanges();
        }

        if (result != null) {
            findLocalChanges(result, temporaryInstanceFactory, account, syncResult);
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "merge complete");
    }

    private void mergeServerDiffs(SyncContext context,
            String account, SyncableContentProvider serverDiffs, SyncResult syncResult) {
        boolean diffsArePartial = serverDiffs.getContainsDiffs();
        // mark the current rows so that we can distinguish these from new
        // inserts that occur during the merge
        mDb.update(mTable, mSyncMarkValues, null, null);
        if (mDeletedTable != null) {
            mDb.update(mDeletedTable, mSyncMarkValues, null, null);
        }

        // load the local database entries, so we can merge them with the server
        final String[] accountSelectionArgs = new String[]{account};
        Cursor localCursor = mDb.query(mTable, syncDirtyProjection,
                SELECT_MARKED, accountSelectionArgs, null, null,
                mTable + "." + _SYNC_ID);
        Cursor deletedCursor;
        if (mDeletedTable != null) {
            deletedCursor = mDb.query(mDeletedTable, syncIdAndVersionProjection,
                    SELECT_MARKED, accountSelectionArgs, null, null,
                    mDeletedTable + "." + _SYNC_ID);
        } else {
            deletedCursor =
                    mDb.rawQuery("select 'a' as _sync_id, 'b' as _sync_version limit 0", null);
        }

        // Apply updates and insertions from the server
        Cursor diffsCursor = serverDiffs.query(mTableURL,
                null, null, null, mTable + "." + _SYNC_ID);
        int deletedSyncIDColumn = deletedCursor.getColumnIndexOrThrow(_SYNC_ID);
        int deletedSyncVersionColumn = deletedCursor.getColumnIndexOrThrow(_SYNC_VERSION);
        int serverSyncIDColumn = diffsCursor.getColumnIndexOrThrow(_SYNC_ID);
        int serverSyncVersionColumn = diffsCursor.getColumnIndexOrThrow(_SYNC_VERSION);
        int serverSyncLocalIdColumn = diffsCursor.getColumnIndexOrThrow(_SYNC_LOCAL_ID);

        String lastSyncId = null;
        int diffsCount = 0;
        int localCount = 0;
        localCursor.moveToFirst();
        deletedCursor.moveToFirst();
        while (diffsCursor.moveToNext()) {
            if (mIsMergeCancelled) {
                localCursor.close();
                deletedCursor.close();
                diffsCursor.close();
                return;
            }
            mDb.yieldIfContended();
            String serverSyncId = diffsCursor.getString(serverSyncIDColumn);
            String serverSyncVersion = diffsCursor.getString(serverSyncVersionColumn);
            long localPersonID = 0;
            String localSyncVersion = null;

            diffsCount++;
            context.setStatusText("Processing " + diffsCount + "/"
                    + diffsCursor.getCount());
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "processing server entry " +
                    diffsCount + ", " + serverSyncId);

            if (TRACE) {
                if (diffsCount == 10) {
                    Debug.startMethodTracing("atmtrace");
                }
                if (diffsCount == 20) {
                    Debug.stopMethodTracing();
                }
            }

            boolean conflict = false;
            boolean update = false;
            boolean insert = false;

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "found event with serverSyncID " + serverSyncId);
            }
            if (TextUtils.isEmpty(serverSyncId)) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.e(TAG, "server entry doesn't have a serverSyncID");
                }
                continue;
            }

            // It is possible that the sync adapter wrote the same record multiple times,
            // e.g. if the same record came via multiple feeds. If this happens just ignore
            // the duplicate records.
            if (serverSyncId.equals(lastSyncId)) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "skipping record with duplicate remote server id " + lastSyncId);
                }
                continue;
            }
            lastSyncId = serverSyncId;

            String localSyncID = null;
            boolean localSyncDirty = false;

            while (!localCursor.isAfterLast()) {
                if (mIsMergeCancelled) {
                    localCursor.deactivate();
                    deletedCursor.deactivate();
                    diffsCursor.deactivate();
                    return;
                }
                localCount++;
                localSyncID = localCursor.getString(2);

                // If the local record doesn't have a _sync_id then
                // it is new. Ignore it for now, we will send an insert
                // the the server later.
                if (TextUtils.isEmpty(localSyncID)) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "local record " +
                                localCursor.getLong(1) +
                                " has no _sync_id, ignoring");
                    }
                    localCursor.moveToNext();
                    localSyncID = null;
                    continue;
                }

                int comp = serverSyncId.compareTo(localSyncID);

                // the local DB has a record that the server doesn't have
                if (comp > 0) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "local record " +
                                localCursor.getLong(1) +
                                " has _sync_id " + localSyncID +
                                " that is < server _sync_id " + serverSyncId);
                    }
                    if (diffsArePartial) {
                        localCursor.moveToNext();
                    } else {
                        deleteRow(localCursor);
                        if (mDeletedTable != null) {
                            mDb.delete(mDeletedTable, _SYNC_ID +"=?", new String[] {localSyncID});
                        }
                        syncResult.stats.numDeletes++;
                        mDb.yieldIfContended();
                    }
                    localSyncID = null;
                    continue;
                }

                // the server has a record that the local DB doesn't have
                if (comp < 0) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "local record " +
                                localCursor.getLong(1) +
                                " has _sync_id " + localSyncID +
                                " that is > server _sync_id " + serverSyncId);
                    }
                    localSyncID = null;
                }

                // the server and the local DB both have this record
                if (comp == 0) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "local record " +
                                localCursor.getLong(1) +
                                " has _sync_id " + localSyncID +
                                " that matches the server _sync_id");
                    }
                    localSyncDirty = localCursor.getInt(0) != 0;
                    localPersonID = localCursor.getLong(1);
                    localSyncVersion = localCursor.getString(3);
                    localCursor.moveToNext();
                }

                break;
            }

            // If this record is in the deleted table then update the server version
            // in the deleted table, if necessary, and then ignore it here.
            // We will send a deletion indication to the server down a
            // little further.
            if (findInCursor(deletedCursor, deletedSyncIDColumn, serverSyncId)) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "remote record " + serverSyncId + " is in the deleted table");
                }
                final String deletedSyncVersion = deletedCursor.getString(deletedSyncVersionColumn);
                if (!TextUtils.equals(deletedSyncVersion, serverSyncVersion)) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "setting version of deleted record " + serverSyncId + " to "
                                + serverSyncVersion);
                    }
                    ContentValues values = new ContentValues();
                    values.put(_SYNC_VERSION, serverSyncVersion);
                    mDb.update(mDeletedTable, values, "_sync_id=?", new String[]{serverSyncId});
                }
                continue;
            }

            // If the _sync_local_id is set and > -1 in the diffsCursor
            // then this record corresponds to a local record that was just
            // inserted into the server and the _sync_local_id is the row id
            // of the local record. Set these fields so that the next check
            // treats this record as an update, which will allow the
            // merger to update the record with the server's sync id
            long serverLocalSyncId =
                    diffsCursor.isNull(serverSyncLocalIdColumn)
                            ? -1
                            : diffsCursor.getLong(serverSyncLocalIdColumn);
            if (serverLocalSyncId > -1) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "the remote record with sync id "
                        + serverSyncId + " has a local sync id, "
                        + serverLocalSyncId);
                localSyncID = serverSyncId;
                localSyncDirty = false;
                localPersonID = serverLocalSyncId;
                localSyncVersion = null;
            }

            if (!TextUtils.isEmpty(localSyncID)) {
                // An existing server item has changed
                boolean recordChanged = (localSyncVersion == null) ||
                        !serverSyncVersion.equals(localSyncVersion);
                if (recordChanged) {
                    if (localSyncDirty) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG,
                                    "remote record " +
                                            serverSyncId +
                                    " conflicts with local _sync_id " +
                                    localSyncID + ", local _id " +
                                    localPersonID);
                        }
                        conflict = true;
                    } else {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                             Log.v(TAG,
                                     "remote record " +
                                             serverSyncId +
                                     " updates local _sync_id " +
                                     localSyncID + ", local _id " +
                                     localPersonID);
                         }
                         update = true;
                    }
                }
            } else {
                // the local db doesn't know about this record so add it
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "remote record "
                            + serverSyncId + " is new, inserting");
                }
                insert = true;
            }

            if (update) {
                updateRow(localPersonID, serverDiffs, diffsCursor);
                syncResult.stats.numUpdates++;
            } else if (conflict) {
                resolveRow(localPersonID, serverSyncId, serverDiffs,
                        diffsCursor);
                syncResult.stats.numUpdates++;
            } else if (insert) {
                insertRow(serverDiffs, diffsCursor);
                syncResult.stats.numInserts++;
            }
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "processed " + diffsCount +
                " server entries");

        // If tombstones aren't in use delete any remaining local rows that
        // don't have corresponding server rows. Keep the rows that don't
        // have a sync id since those were created locally and haven't been
        // synced to the server yet.
        if (!diffsArePartial) {
            while (!localCursor.isAfterLast() &&
                    !TextUtils.isEmpty(localCursor.getString(2))) {
                if (mIsMergeCancelled) {
                    localCursor.deactivate();
                    deletedCursor.deactivate();
                    diffsCursor.deactivate();
                    return;
                }
                localCount++;
                final String localSyncId = localCursor.getString(2);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG,
                            "deleting local record " +
                                    localCursor.getLong(1) +
                                    " _sync_id " + localSyncId);
                }
                deleteRow(localCursor);
                if (mDeletedTable != null) {
                    mDb.delete(mDeletedTable, _SYNC_ID + "=?", new String[] {localSyncId});
                }
                syncResult.stats.numDeletes++;
                mDb.yieldIfContended();
            }
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "checked " + localCount +
                " local entries");
        diffsCursor.deactivate();
        localCursor.deactivate();
        deletedCursor.deactivate();

        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "applying deletions from the server");

        // Apply deletions from the server
        if (mDeletedTableURL != null) {
            diffsCursor = serverDiffs.query(mDeletedTableURL, null, null, null, null);
            serverSyncIDColumn = diffsCursor.getColumnIndexOrThrow(_SYNC_ID);

            while (diffsCursor.moveToNext()) {
                if (mIsMergeCancelled) {
                    diffsCursor.deactivate();
                    return;
                }
                // delete all rows that match each element in the diffsCursor
                fullyDeleteRowsWithSyncId(diffsCursor.getString(serverSyncIDColumn), account,
                        syncResult);
                mDb.yieldIfContended();
            }
            diffsCursor.deactivate();
        }
    }

    private void fullyDeleteRowsWithSyncId(String syncId, String account, SyncResult syncResult) {
        final String[] selectionArgs = new String[]{syncId, account};
        // delete the rows explicitly so that the delete operation can be overridden
        Cursor c = mDb.query(mTable, new String[]{"_id"}, SELECT_BY_ID_AND_ACCOUNT,
                selectionArgs, null, null, null);
        try {
            c.moveToFirst();
            while (!c.isAfterLast()) {
                deleteRow(c); // advances the cursor
                syncResult.stats.numDeletes++;
            }
        } finally {
            c.deactivate();
        }
        if (mDeletedTable != null) {
            mDb.delete(mDeletedTable, SELECT_BY_ID_AND_ACCOUNT, selectionArgs);
        }
    }

    /**
     * Converts cursor into a Map, using the correct types for the values.
     */
    protected void cursorRowToContentValues(Cursor cursor, ContentValues map) {
        DatabaseUtils.cursorRowToContentValues(cursor, map);
    }

    /**
     * Finds local changes, placing the results in the given result object.
     * @param temporaryInstanceFactory As an optimization for the case
     * where there are no client-side diffs, mergeResult may initially
     * have no {@link android.content.TempProviderSyncResult#tempContentProvider}.  If this is
     * the first in the sequence of AbstractTableMergers to find
     * client-side diffs, it will use the given ContentProvider to
     * create a temporary instance and store its {@link
     * ContentProvider} in the mergeResult.
     * @param account
     * @param syncResult
     */
    private void findLocalChanges(TempProviderSyncResult mergeResult,
            SyncableContentProvider temporaryInstanceFactory, String account,
            SyncResult syncResult) {
        SyncableContentProvider clientDiffs = mergeResult.tempContentProvider;
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "generating client updates");

        final String[] accountSelectionArgs = new String[]{account};

        // Generate the client updates and insertions
        // Create a cursor for dirty records
        Cursor localChangesCursor = mDb.query(mTable, null, SELECT_UNSYNCED, accountSelectionArgs,
                null, null, null);
        long numInsertsOrUpdates = localChangesCursor.getCount();
        while (localChangesCursor.moveToNext()) {
            if (mIsMergeCancelled) {
                localChangesCursor.close();
                return;
            }
            if (clientDiffs == null) {
                clientDiffs = temporaryInstanceFactory.getTemporaryInstance();
            }
            mValues.clear();
            cursorRowToContentValues(localChangesCursor, mValues);
            mValues.remove("_id");
            DatabaseUtils.cursorLongToContentValues(localChangesCursor, "_id", mValues,
                    _SYNC_LOCAL_ID);
            clientDiffs.insert(mTableURL, mValues);
        }
        localChangesCursor.close();

        // Generate the client deletions
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "generating client deletions");
        long numEntries = DatabaseUtils.queryNumEntries(mDb, mTable);
        long numDeletedEntries = 0;
        if (mDeletedTable != null) {
            Cursor deletedCursor = mDb.query(mDeletedTable,
                    syncIdAndVersionProjection,
                    _SYNC_ACCOUNT + "=? AND " + _SYNC_ID + " IS NOT NULL", accountSelectionArgs,
                    null, null, mDeletedTable + "." + _SYNC_ID);

            numDeletedEntries = deletedCursor.getCount();
            while (deletedCursor.moveToNext()) {
                if (mIsMergeCancelled) {
                    deletedCursor.close();
                    return;
                }
                if (clientDiffs == null) {
                    clientDiffs = temporaryInstanceFactory.getTemporaryInstance();
                }
                mValues.clear();
                DatabaseUtils.cursorRowToContentValues(deletedCursor, mValues);
                clientDiffs.insert(mDeletedTableURL, mValues);
            }
            deletedCursor.close();
        }

        if (clientDiffs != null) {
            mergeResult.tempContentProvider = clientDiffs;
        }
        syncResult.stats.numDeletes += numDeletedEntries;
        syncResult.stats.numUpdates += numInsertsOrUpdates;
        syncResult.stats.numEntries += numEntries;
    }
}
