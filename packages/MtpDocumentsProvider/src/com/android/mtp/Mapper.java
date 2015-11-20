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

import static com.android.mtp.MtpDatabaseConstants.*;

import android.content.ContentValues;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.HashMap;
import java.util.Map;

import static com.android.mtp.MtpDatabase.strings;


/**
 * Mapping operations for MtpDatabase.
 * Also see the comments of {@link MtpDatabase}.
 */
class Mapper {
    private final MtpDatabase mDatabase;

    /**
     * Mapping mode for roots/documents where we start adding child documents.
     * Methods operate the state needs to be synchronized.
     */
    private final Map<String, Integer> mMappingMode = new HashMap<>();

    Mapper(MtpDatabase database) {
        mDatabase = database;
    }

    /**
     * Invokes {@link #startAddingDocuments} for root documents.
     * @param deviceId Device ID.
     */
    synchronized void startAddingRootDocuments(int deviceId) {
        final String mappingStateKey = getRootDocumentsMappingStateKey(deviceId);
        Preconditions.checkState(!mMappingMode.containsKey(mappingStateKey));
        mMappingMode.put(
                mappingStateKey,
                startAddingDocuments(
                        SELECTION_ROOT_DOCUMENTS, Integer.toString(deviceId)));
    }

    /**
     * Invokes {@link #startAddingDocuments} for child of specific documents.
     * @param parentDocumentId Document ID for parent document.
     */
    @VisibleForTesting
    synchronized void startAddingChildDocuments(String parentDocumentId) {
        final String mappingStateKey = getChildDocumentsMappingStateKey(parentDocumentId);
        Preconditions.checkState(!mMappingMode.containsKey(mappingStateKey));
        mMappingMode.put(
                mappingStateKey,
                startAddingDocuments(SELECTION_CHILD_DOCUMENTS, parentDocumentId));
    }

    /**
     * Puts root information to database.
     * @param deviceId Device ID
     * @param resources Resources required to localize root name.
     * @param roots List of root information.
     * @return If roots are added or removed from the database.
     */
    synchronized boolean putRootDocuments(int deviceId, Resources resources, MtpRoot[] roots) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            final boolean heuristic;
            final String mapColumn;
            final String key = getRootDocumentsMappingStateKey(deviceId);
            Preconditions.checkState(mMappingMode.containsKey(key));
            switch (mMappingMode.get(key)) {
                case MAP_BY_MTP_IDENTIFIER:
                    heuristic = false;
                    mapColumn = COLUMN_STORAGE_ID;
                    break;
                case MAP_BY_NAME:
                    heuristic = true;
                    mapColumn = Document.COLUMN_DISPLAY_NAME;
                    break;
                default:
                    throw new Error("Unexpected map mode.");
            }
            final ContentValues[] valuesList = new ContentValues[roots.length];
            for (int i = 0; i < roots.length; i++) {
                if (roots[i].mDeviceId != deviceId) {
                    throw new IllegalArgumentException();
                }
                valuesList[i] = new ContentValues();
                MtpDatabase.getRootDocumentValues(valuesList[i], resources, roots[i]);
            }
            final boolean changed = putDocuments(
                    valuesList,
                    SELECTION_ROOT_DOCUMENTS,
                    Integer.toString(deviceId),
                    heuristic,
                    mapColumn);
            final ContentValues values = new ContentValues();
            int i = 0;
            for (final MtpRoot root : roots) {
                // Use the same value for the root ID and the corresponding document ID.
                final String documentId = valuesList[i++].getAsString(Document.COLUMN_DOCUMENT_ID);
                // If it fails to insert/update documents, the document ID will be set with -1.
                // In this case we don't insert/update root extra information neither.
                if (documentId == null) {
                    continue;
                }
                values.put(Root.COLUMN_ROOT_ID, documentId);
                values.put(
                        Root.COLUMN_FLAGS,
                        Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE);
                values.put(Root.COLUMN_AVAILABLE_BYTES, root.mFreeSpace);
                values.put(Root.COLUMN_CAPACITY_BYTES, root.mMaxCapacity);
                values.put(Root.COLUMN_MIME_TYPES, "");
                database.replace(TABLE_ROOT_EXTRA, null, values);
            }
            database.setTransactionSuccessful();
            return changed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Puts document information to database.
     * @param deviceId Device ID
     * @param parentId Parent document ID.
     * @param documents List of document information.
     */
    synchronized void putChildDocuments(int deviceId, String parentId, MtpObjectInfo[] documents) {
        final boolean heuristic;
        final String mapColumn;
        final String key = getChildDocumentsMappingStateKey(parentId);
        Preconditions.checkState(mMappingMode.containsKey(key));
        switch (mMappingMode.get(key)) {
            case MAP_BY_MTP_IDENTIFIER:
                heuristic = false;
                mapColumn = COLUMN_OBJECT_HANDLE;
                break;
            case MAP_BY_NAME:
                heuristic = true;
                mapColumn = Document.COLUMN_DISPLAY_NAME;
                break;
            default:
                throw new Error("Unexpected map mode.");
        }
        final ContentValues[] valuesList = new ContentValues[documents.length];
        for (int i = 0; i < documents.length; i++) {
            valuesList[i] = new ContentValues();
            MtpDatabase.getChildDocumentValues(
                    valuesList[i], deviceId, parentId, documents[i]);
        }
        putDocuments(
                valuesList, SELECTION_CHILD_DOCUMENTS, parentId, heuristic, mapColumn);
    }

    /**
     * Stops adding root documents.
     * @param deviceId Device ID.
     * @return True if new rows are added/removed.
     */
    synchronized boolean stopAddingRootDocuments(int deviceId) {
        final String key = getRootDocumentsMappingStateKey(deviceId);
        Preconditions.checkState(mMappingMode.containsKey(key));
        switch (mMappingMode.get(key)) {
            case MAP_BY_MTP_IDENTIFIER:
                mMappingMode.remove(key);
                return stopAddingDocuments(
                        SELECTION_ROOT_DOCUMENTS,
                        Integer.toString(deviceId),
                        COLUMN_STORAGE_ID);
            case MAP_BY_NAME:
                mMappingMode.remove(key);
                return stopAddingDocuments(
                        SELECTION_ROOT_DOCUMENTS,
                        Integer.toString(deviceId),
                        Document.COLUMN_DISPLAY_NAME);
            default:
                throw new Error("Unexpected mapping state.");
        }
    }

    /**
     * Stops adding documents under the parent.
     * @param parentId Document ID of the parent.
     */
    synchronized void stopAddingChildDocuments(String parentId) {
        final String key = getChildDocumentsMappingStateKey(parentId);
        Preconditions.checkState(mMappingMode.containsKey(key));
        switch (mMappingMode.get(key)) {
            case MAP_BY_MTP_IDENTIFIER:
                stopAddingDocuments(
                        SELECTION_CHILD_DOCUMENTS,
                        parentId,
                        COLUMN_OBJECT_HANDLE);
                break;
            case MAP_BY_NAME:
                stopAddingDocuments(
                        SELECTION_CHILD_DOCUMENTS,
                        parentId,
                        Document.COLUMN_DISPLAY_NAME);
                break;
            default:
                throw new Error("Unexpected mapping state.");
        }
        mMappingMode.remove(key);
    }

    @VisibleForTesting
    void clearMapping() {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            mDatabase.deleteDocumentsAndRootsRecursively(
                    COLUMN_ROW_STATE + " = ?", strings(ROW_STATE_PENDING));
            final ContentValues values = new ContentValues();
            values.putNull(COLUMN_OBJECT_HANDLE);
            values.putNull(COLUMN_STORAGE_ID);
            values.put(COLUMN_ROW_STATE, ROW_STATE_INVALIDATED);
            database.update(TABLE_DOCUMENTS, values, null, null);
            database.setTransactionSuccessful();
            mMappingMode.clear();
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Starts adding new documents.
     * The methods decides mapping mode depends on if all documents under the given parent have MTP
     * identifier or not. If all the documents have MTP identifier, it uses the identifier to find
     * a corresponding existing row. Otherwise it does heuristic.
     *
     * @param selection Query matches valid documents.
     * @param arg Argument for selection.
     * @return Mapping mode.
     */
    private int startAddingDocuments(String selection, String arg) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            // Delete all pending rows.
            mDatabase.deleteDocumentsAndRootsRecursively(
                    selection + " AND " + COLUMN_ROW_STATE + "=?", strings(arg, ROW_STATE_PENDING));

            // Set all documents as invalidated.
            final ContentValues values = new ContentValues();
            values.put(COLUMN_ROW_STATE, ROW_STATE_INVALIDATED);
            database.update(TABLE_DOCUMENTS, values, selection, new String[] { arg });

            // If we have rows that does not have MTP identifier, do heuristic mapping by name.
            final boolean useNameForResolving = DatabaseUtils.queryNumEntries(
                    database,
                    TABLE_DOCUMENTS,
                    selection + " AND " + COLUMN_STORAGE_ID + " IS NULL",
                    new String[] { arg }) > 0;
            database.setTransactionSuccessful();
            return useNameForResolving ? MAP_BY_NAME : MAP_BY_MTP_IDENTIFIER;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Puts the documents into the database.
     * If the mapping mode is not heuristic, it just adds the rows to the database or updates the
     * existing rows with the new values. If the mapping mode is heuristic, it adds some new rows as
     * 'pending' state when that rows may be corresponding to existing 'invalidated' rows. Then
     * {@link #stopAddingDocuments(String, String, String)} turns the pending rows into 'valid'
     * rows. If the methods adds rows to database, it updates valueList with correct document ID.
     *
     * @param valuesList Values for documents to be stored in the database.
     * @param selection SQL where closure to select rows that shares the same parent.
     * @param arg Argument for selection SQL.
     * @param heuristic Whether the mapping mode is heuristic.
     * @return Whether the method adds new rows.
     */
    private boolean putDocuments(
            ContentValues[] valuesList,
            String selection,
            String arg,
            boolean heuristic,
            String mappingKey) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        boolean added = false;
        database.beginTransaction();
        try {
            for (final ContentValues values : valuesList) {
                final Cursor candidateCursor = database.query(
                        TABLE_DOCUMENTS,
                        strings(Document.COLUMN_DOCUMENT_ID),
                        selection + " AND " +
                        COLUMN_ROW_STATE + "=? AND " +
                        mappingKey + "=?",
                        strings(arg, ROW_STATE_INVALIDATED, values.getAsString(mappingKey)),
                        null,
                        null,
                        null,
                        "1");
                try {
                    final long rowId;
                    if (candidateCursor.getCount() == 0) {
                        rowId = database.insert(TABLE_DOCUMENTS, null, values);
                        if (rowId == -1) {
                            throw new SQLiteException("Failed to put a document into database.");
                        }
                        added = true;
                    } else if (!heuristic) {
                        candidateCursor.moveToNext();
                        final String documentId = candidateCursor.getString(0);
                        rowId = database.update(
                                TABLE_DOCUMENTS,
                                values,
                                SELECTION_DOCUMENT_ID,
                                strings(documentId));
                    } else {
                        values.put(COLUMN_ROW_STATE, ROW_STATE_PENDING);
                        rowId = database.insert(TABLE_DOCUMENTS, null, values);
                    }
                    // Document ID is a primary integer key of the table. So the returned row
                    // IDs should be same with the document ID.
                    values.put(Document.COLUMN_DOCUMENT_ID, rowId);
                } finally {
                    candidateCursor.close();
                }
            }

            database.setTransactionSuccessful();
            return added;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Maps 'pending' document and 'invalidated' document that shares the same column of groupKey.
     * If the database does not find corresponding 'invalidated' document, it just removes
     * 'invalidated' document from the database.
     * @param selection Query to select rows for resolving.
     * @param arg Argument for selection SQL.
     * @param groupKey Column name used to find corresponding rows.
     * @return Whether the methods adds or removed visible rows.
     */
    private boolean stopAddingDocuments(String selection, String arg, String groupKey) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            // Get 1-to-1 mapping of invalidated document and pending document.
            final String invalidatedIdQuery = createStateFilter(
                    ROW_STATE_INVALIDATED, Document.COLUMN_DOCUMENT_ID);
            final String pendingIdQuery = createStateFilter(
                    ROW_STATE_PENDING, Document.COLUMN_DOCUMENT_ID);
            // SQL should be like:
            // SELECT group_concat(CASE WHEN raw_state = 1 THEN document_id ELSE NULL END),
            //        group_concat(CASE WHEN raw_state = 2 THEN document_id ELSE NULL END)
            // WHERE device_id = ? AND parent_document_id IS NULL
            // GROUP BY display_name
            // HAVING count(CASE WHEN raw_state = 1 THEN document_id ELSE NULL END) = 1 AND
            //        count(CASE WHEN raw_state = 2 THEN document_id ELSE NULL END) = 1
            final Cursor mergingCursor = database.query(
                    TABLE_DOCUMENTS,
                    new String[] {
                            "group_concat(" + invalidatedIdQuery + ")",
                            "group_concat(" + pendingIdQuery + ")"
                    },
                    selection,
                    strings(arg),
                    groupKey,
                    "count(" + invalidatedIdQuery + ") = 1 AND count(" + pendingIdQuery + ") = 1",
                    null);

            final ContentValues values = new ContentValues();
            while (mergingCursor.moveToNext()) {
                final String invalidatedId = mergingCursor.getString(0);
                final String pendingId = mergingCursor.getString(1);

                // Obtain the new values including the latest object handle from mapping row.
                getFirstRow(
                        TABLE_DOCUMENTS,
                        SELECTION_DOCUMENT_ID,
                        new String[] { pendingId },
                        values);
                values.remove(Document.COLUMN_DOCUMENT_ID);
                values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
                database.update(
                        TABLE_DOCUMENTS,
                        values,
                        SELECTION_DOCUMENT_ID,
                        new String[] { invalidatedId });

                getFirstRow(
                        TABLE_ROOT_EXTRA,
                        SELECTION_ROOT_ID,
                        new String[] { pendingId },
                        values);
                if (values.size() > 0) {
                    values.remove(Root.COLUMN_ROOT_ID);
                    database.update(
                            TABLE_ROOT_EXTRA,
                            values,
                            SELECTION_ROOT_ID,
                            new String[] { invalidatedId });
                }

                // Delete 'pending' row.
                mDatabase.deleteDocumentsAndRootsRecursively(
                        SELECTION_DOCUMENT_ID, new String[] { pendingId });
            }
            mergingCursor.close();

            boolean changed = false;

            // Delete all invalidated rows that cannot be mapped.
            if (mDatabase.deleteDocumentsAndRootsRecursively(
                    COLUMN_ROW_STATE + " = ? AND " + selection,
                    strings(ROW_STATE_INVALIDATED, arg))) {
                changed = true;
            }

            // The database cannot find old document ID for the pending rows.
            // Turn the all pending rows into valid state, which means the rows become to be
            // valid with new document ID.
            values.clear();
            values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
            if (database.update(
                    TABLE_DOCUMENTS,
                    values,
                    COLUMN_ROW_STATE + " = ? AND " + selection,
                    strings(ROW_STATE_PENDING, arg)) != 0) {
                changed = true;
            }
            database.setTransactionSuccessful();
            return changed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Obtains values of the first row for the query.
     * @param values ContentValues that the values are stored to.
     * @param table Target table.
     * @param selection Query to select rows.
     * @param args Argument for query.
     */
    private void getFirstRow(String table, String selection, String[] args, ContentValues values) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        values.clear();
        final Cursor cursor = database.query(table, null, selection, args, null, null, null, "1");
        if (cursor.getCount() == 0) {
            return;
        }
        cursor.moveToNext();
        DatabaseUtils.cursorRowToContentValues(cursor, values);
        cursor.close();
    }

    /**
     * Gets SQL expression that represents the given value or NULL depends on the row state.
     * You must pass static constants to this methods otherwise you may be suffered from SQL
     * injections.
     * @param state Expected row state.
     * @param a SQL value.
     * @return Expression that represents a if the row state is expected one, and represents NULL
     *     otherwise.
     */
    private static String createStateFilter(int state, String a) {
        return "CASE WHEN " + COLUMN_ROW_STATE + " = " + Integer.toString(state) +
                " THEN " + a + " ELSE NULL END";
    }

    /**
     * @param deviceId Device ID.
     * @return Key for {@link #mMappingMode}.
     */
    private static String getRootDocumentsMappingStateKey(int deviceId) {
        return "RootDocuments/" + deviceId;
    }

    /**
     * @param parentDocumentId Document ID for the parent document.
     * @return Key for {@link #mMappingMode}.
     */
    private static String getChildDocumentsMappingStateKey(String parentDocumentId) {
        return "ChildDocuments/" + parentDocumentId;
    }
}
