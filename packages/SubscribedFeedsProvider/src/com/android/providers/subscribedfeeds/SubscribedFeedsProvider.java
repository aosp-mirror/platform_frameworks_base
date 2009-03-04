/*
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.providers.subscribedfeeds;

import android.content.UriMatcher;
import android.content.*;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.SubscribedFeeds;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

/**
 * Manages a list of feeds for which this client is interested in receiving
 * change notifications.
 */
public class SubscribedFeedsProvider extends AbstractSyncableContentProvider {
    private static final String TAG = "SubscribedFeedsProvider";
    private static final String DATABASE_NAME = "subscribedfeeds.db";
    private static final int DATABASE_VERSION = 10;

    private static final int FEEDS = 1;
    private static final int FEED_ID = 2;
    private static final int DELETED_FEEDS = 3;
    private static final int ACCOUNTS = 4;

    private static final Map<String, String> ACCOUNTS_PROJECTION_MAP;

    private static final UriMatcher sURLMatcher =
            new UriMatcher(UriMatcher.NO_MATCH);

    private static String sFeedsTable = "feeds";
    private static Uri sFeedsUrl =
            Uri.parse("content://subscribedfeeds/feeds/");
    private static String sDeletedFeedsTable = "_deleted_feeds";
    private static Uri sDeletedFeedsUrl =
            Uri.parse("content://subscribedfeeds/deleted_feeds/");

    public SubscribedFeedsProvider() {
        super(DATABASE_NAME, DATABASE_VERSION, sFeedsUrl);
    }

    static {
        sURLMatcher.addURI("subscribedfeeds", "feeds", FEEDS);
        sURLMatcher.addURI("subscribedfeeds", "feeds/#", FEED_ID);
        sURLMatcher.addURI("subscribedfeeds", "deleted_feeds", DELETED_FEEDS);
        sURLMatcher.addURI("subscribedfeeds", "accounts", ACCOUNTS);
    }

    @Override
    protected boolean upgradeDatabase(SQLiteDatabase db,
            int oldVersion, int newVersion) {
        Log.w(TAG, "Upgrading database from version " + oldVersion +
                " to " + newVersion +
                ", which will destroy all old data");
        db.execSQL("DROP TRIGGER IF EXISTS feed_cleanup");
        db.execSQL("DROP TABLE IF EXISTS _deleted_feeds");
        db.execSQL("DROP TABLE IF EXISTS feeds");
        bootstrapDatabase(db);
        return false; // this was lossy
    }

    @Override
    protected void bootstrapDatabase(SQLiteDatabase db) {
        super.bootstrapDatabase(db);
        db.execSQL("CREATE TABLE feeds (" +
                    "_id INTEGER PRIMARY KEY," +
                    "_sync_account TEXT," + // From the sync source
                    "_sync_id TEXT," + // From the sync source
                    "_sync_time TEXT," + // From the sync source
                    "_sync_version TEXT," + // From the sync source
                    "_sync_local_id INTEGER," + // Used while syncing,
                                                // never stored persistently
                    "_sync_dirty INTEGER," + // if syncable, set if the record
                                             // has local, unsynced, changes
                    "_sync_mark INTEGER," + // Used to filter out new rows
                    "feed TEXT," +
                    "authority TEXT," +
                    "service TEXT" +
                    ");");

        // Trigger to completely remove feeds data when they're deleted
        db.execSQL("CREATE TRIGGER feed_cleanup DELETE ON feeds " +
                    "WHEN old._sync_id is not null " +
                    "BEGIN " +
                        "INSERT INTO _deleted_feeds " +
                            "(_sync_id, _sync_account, _sync_version) " +
                            "VALUES (old._sync_id, old._sync_account, " +
                            "old._sync_version);" +
                    "END");

        db.execSQL("CREATE TABLE _deleted_feeds (" +
                    "_sync_version TEXT," + // From the sync source
                    "_sync_id TEXT," +
                    (isTemporary() ? "_sync_local_id INTEGER," : "") + // Used while syncing,
                    "_sync_account TEXT," +
                    "_sync_mark INTEGER, " + // Used to filter out new rows
                    "UNIQUE(_sync_id))");
    }

    @Override
    protected void onDatabaseOpened(SQLiteDatabase db) {
        db.markTableSyncable("feeds", "_deleted_feeds");
    }

    @Override
    protected Iterable<FeedMerger> getMergers() {
        return Collections.singletonList(new FeedMerger());
    }

    @Override
    public String getType(Uri url) {
        int match = sURLMatcher.match(url);
        switch (match) {
            case FEEDS:
                return SubscribedFeeds.Feeds.CONTENT_TYPE;
            case FEED_ID:
                return SubscribedFeeds.Feeds.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URL");
        }
    }

    @Override
    public Cursor queryInternal(Uri url, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();


        // Generate the body of the query
        int match = sURLMatcher.match(url);

        if (Config.LOGV) Log.v(TAG, "SubscribedFeedsProvider.query: url=" +
                url + ", match is " + match);

        switch (match) {
            case FEEDS:
                qb.setTables(sFeedsTable);
                break;
            case DELETED_FEEDS:
                if (!isTemporary()) {
                    throw new UnsupportedOperationException();
                }
                qb.setTables(sDeletedFeedsTable);
                break;
            case ACCOUNTS:
                qb.setTables(sFeedsTable);
                qb.setDistinct(true);
                qb.setProjectionMap(ACCOUNTS_PROJECTION_MAP);
                return qb.query(getDatabase(), projection, selection, selectionArgs,
                        SubscribedFeeds.Feeds._SYNC_ACCOUNT, null, sortOrder);
            case FEED_ID:
                qb.setTables(sFeedsTable);
                qb.appendWhere(sFeedsTable + "._id=");
                qb.appendWhere(url.getPathSegments().get(1));
                break;
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }

        // run the query
        return qb.query(getDatabase(), projection, selection, selectionArgs,
                null, null, sortOrder);
    }

    @Override
    public Uri insertInternal(Uri url, ContentValues initialValues) {
        final SQLiteDatabase db = getDatabase();
        Uri resultUri = null;
        long rowID;

        int match = sURLMatcher.match(url);
        switch (match) {
            case FEEDS:
                ContentValues values = new ContentValues(initialValues);
                values.put(SubscribedFeeds.Feeds._SYNC_DIRTY, 1);
                rowID = db.insert(sFeedsTable, "feed", values);
                if (rowID > 0) {
                    resultUri = Uri.parse(
                            "content://subscribedfeeds/feeds/" + rowID);
                }
                break;

            case DELETED_FEEDS:
                if (!isTemporary()) {
                    throw new UnsupportedOperationException();
                }
                rowID = db.insert(sDeletedFeedsTable, "_sync_id",
                        initialValues);
                if (rowID > 0) {
                    resultUri = Uri.parse(
                            "content://subscribedfeeds/deleted_feeds/" + rowID);
                }
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        return resultUri;
    }

    @Override
    public int deleteInternal(Uri url, String userWhere, String[] whereArgs) {
        final SQLiteDatabase db = getDatabase();
        String changedItemId;

        switch (sURLMatcher.match(url)) {
            case FEEDS:
                changedItemId = null;
                break;
            case FEED_ID:
                changedItemId = url.getPathSegments().get(1);
                break;
            default:
                throw new UnsupportedOperationException(
                        "Cannot delete that URL: " + url);
        }

        String where = addIdToWhereClause(changedItemId, userWhere);
        return db.delete(sFeedsTable, where, whereArgs);
    }

    @Override
    public int updateInternal(Uri url, ContentValues initialValues,
            String userWhere, String[] whereArgs) {
        final SQLiteDatabase db = getDatabase();
        ContentValues values = new ContentValues(initialValues);
        values.put(SubscribedFeeds.Feeds._SYNC_DIRTY, 1);

        String changedItemId;
        switch (sURLMatcher.match(url)) {
            case FEEDS:
                changedItemId = null;
                break;

            case FEED_ID:
                changedItemId = url.getPathSegments().get(1);
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot update URL: " + url);
        }

        String where = addIdToWhereClause(changedItemId, userWhere);
        return db.update(sFeedsTable, values, where, whereArgs);
    }

    private static String addIdToWhereClause(String id, String where) {
        if (id != null) {
            StringBuilder whereSb = new StringBuilder("_id=");
            whereSb.append(id);
            if (!TextUtils.isEmpty(where)) {
                whereSb.append(" AND (");
                whereSb.append(where);
                whereSb.append(')');
            }
            return whereSb.toString();
        } else {
            return where;
        }
    }

    private class FeedMerger extends AbstractTableMerger {
        private ContentValues mValues = new ContentValues();
        FeedMerger() {
            super(getDatabase(), sFeedsTable, sFeedsUrl, sDeletedFeedsTable, sDeletedFeedsUrl);
        }

        @Override
        protected void notifyChanges() {
            getContext().getContentResolver().notifyChange(
                    sFeedsUrl, null /* data change observer */,
                    false /* do not sync to network */);
        }

        @Override
        public void insertRow(ContentProvider diffs, Cursor diffsCursor) {
            final SQLiteDatabase db = getDatabase();
            // We don't ever want to add entries from the server, instead
            // we want to tell the server to delete any entries we receive
            // from the server that aren't already known by the client.
            mValues.clear();
            DatabaseUtils.cursorStringToContentValues(diffsCursor,
                    SubscribedFeeds.Feeds._SYNC_ID, mValues);
            DatabaseUtils.cursorStringToContentValues(diffsCursor,
                    SubscribedFeeds.Feeds._SYNC_ACCOUNT, mValues);
            DatabaseUtils.cursorStringToContentValues(diffsCursor,
                    SubscribedFeeds.Feeds._SYNC_VERSION, mValues);
            db.replace(mDeletedTable, SubscribedFeeds.Feeds._SYNC_ID, mValues);
        }

        @Override
        public void updateRow(long localPersonID, ContentProvider diffs,
                Cursor diffsCursor) {
            updateOrResolveRow(localPersonID, null, diffs, diffsCursor, false);
        }

        @Override
        public void resolveRow(long localPersonID, String syncID,
                ContentProvider diffs, Cursor diffsCursor) {
            updateOrResolveRow(localPersonID, syncID, diffs, diffsCursor, true);
        }

        protected void updateOrResolveRow(long localPersonID, String syncID,
                ContentProvider diffs, Cursor diffsCursor, boolean conflicts) {
            mValues.clear();
            // only copy over the fields that the server owns
            DatabaseUtils.cursorStringToContentValues(diffsCursor,
                    SubscribedFeeds.Feeds._SYNC_ID, mValues);
            DatabaseUtils.cursorStringToContentValues(diffsCursor,
                    SubscribedFeeds.Feeds._SYNC_TIME, mValues);
            DatabaseUtils.cursorStringToContentValues(diffsCursor,
                    SubscribedFeeds.Feeds._SYNC_VERSION, mValues);
            mValues.put(SubscribedFeeds.Feeds._SYNC_DIRTY, conflicts ? 1 : 0);
            final SQLiteDatabase db = getDatabase();
            db.update(mTable, mValues,
                    SubscribedFeeds.Feeds._ID + '=' + localPersonID, null);
        }

        @Override
        public void deleteRow(Cursor localCursor) {
            // Since the client is the authority we don't actually delete
            // the row when the server says it has been deleted. Instead
            // we break the association with the server by clearing out
            // the id, time, and version, then we mark it dirty so that
            // it will be synced back to the server.
            long localPersonId = localCursor.getLong(localCursor.getColumnIndex(
                    SubscribedFeeds.Feeds._ID));
            mValues.clear();
            mValues.put(SubscribedFeeds.Feeds._SYNC_DIRTY, 1);
            mValues.put(SubscribedFeeds.Feeds._SYNC_ID, (String) null);
            mValues.put(SubscribedFeeds.Feeds._SYNC_TIME, (Long) null);
            mValues.put(SubscribedFeeds.Feeds._SYNC_VERSION, (String) null);
            final SQLiteDatabase db = getDatabase();
            db.update(mTable, mValues, SubscribedFeeds.Feeds._ID + '=' + localPersonId, null);
            localCursor.moveToNext();
        }
    }

    static {
        Map<String, String> map;

        map = new HashMap<String, String>();
        ACCOUNTS_PROJECTION_MAP = map;
        map.put(SubscribedFeeds.Accounts._COUNT, "COUNT(*) AS _count");
        map.put(SubscribedFeeds.Accounts._SYNC_ACCOUNT, SubscribedFeeds.Accounts._SYNC_ACCOUNT);
    }
}
