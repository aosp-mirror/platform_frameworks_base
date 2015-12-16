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

import android.annotation.Nullable;
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
    private static final String[] EMPTY_ARGS = new String[0];
    private final MtpDatabase mDatabase;

    /**
     * Mapping mode for a parent. The key is document ID of parent, or null for root documents.
     * Methods operate the state needs to be synchronized.
     * TODO: Replace this with unboxing int map.
     */
    private final Map<String, Integer> mMappingMode = new HashMap<>();

    Mapper(MtpDatabase database) {
        mDatabase = database;
    }

    /**
     * Puts device information to database.
     * @return If device is added to the database.
     */
    synchronized boolean putDeviceDocument(MtpDeviceRecord device) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        Preconditions.checkState(mMappingMode.containsKey(/* no parent for root */ null));
        database.beginTransaction();
        try {
            final ContentValues[] valuesList = new ContentValues[1];
            final ContentValues[] extraValuesList = new ContentValues[1];
            valuesList[0] = new ContentValues();
            extraValuesList[0] = new ContentValues();
            MtpDatabase.getDeviceDocumentValues(valuesList[0], extraValuesList[0], device);
            final boolean changed = putDocuments(
                    valuesList,
                    extraValuesList,
                    COLUMN_PARENT_DOCUMENT_ID + " IS NULL",
                    EMPTY_ARGS,
                    /* heuristic */ false,
                    COLUMN_DEVICE_ID);
            database.setTransactionSuccessful();
            return changed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Puts root information to database.
     * @param parentDocumentId Document ID of device document.
     * @param resources Resources required to localize root name.
     * @param roots List of root information.
     * @return If roots are added or removed from the database.
     */
    synchronized boolean putStorageDocuments(
            String parentDocumentId, Resources resources, MtpRoot[] roots) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            final boolean heuristic;
            final String mapColumn;
            Preconditions.checkState(mMappingMode.containsKey(parentDocumentId));
            switch (mMappingMode.get(parentDocumentId)) {
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
            final ContentValues[] extraValuesList = new ContentValues[roots.length];
            for (int i = 0; i < roots.length; i++) {
                valuesList[i] = new ContentValues();
                extraValuesList[i] = new ContentValues();
                MtpDatabase.getStorageDocumentValues(
                        valuesList[i], extraValuesList[i], resources, parentDocumentId, roots[i]);
            }
            final boolean changed = putDocuments(
                    valuesList,
                    extraValuesList,
                    COLUMN_PARENT_DOCUMENT_ID + "=?",
                    strings(parentDocumentId),
                    heuristic,
                    mapColumn);

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
        Preconditions.checkState(mMappingMode.containsKey(parentId));
        switch (mMappingMode.get(parentId)) {
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
            MtpDatabase.getObjectDocumentValues(
                    valuesList[i], deviceId, parentId, documents[i]);
        }
        putDocuments(
                valuesList,
                null,
                COLUMN_PARENT_DOCUMENT_ID + "=?",
                strings(parentId),
                heuristic,
                mapColumn);
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
     * @param parentDocumentId Parent document ID or NULL for root documents.
     */
    void startAddingDocuments(@Nullable String parentDocumentId) {
        Preconditions.checkState(!mMappingMode.containsKey(parentDocumentId));
        final String selection;
        final String[] args;
        if (parentDocumentId != null) {
            selection = COLUMN_PARENT_DOCUMENT_ID + " = ?";
            args = strings(parentDocumentId);
        } else {
            selection = COLUMN_PARENT_DOCUMENT_ID + " IS NULL";
            args = EMPTY_ARGS;
        }

        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            // Delete all pending rows.
            mDatabase.deleteDocumentsAndRootsRecursively(
                    selection + " AND " + COLUMN_ROW_STATE + "=?",
                    DatabaseUtils.appendSelectionArgs(args, strings(ROW_STATE_PENDING)));

            // Set all documents as invalidated.
            final ContentValues values = new ContentValues();
            values.put(COLUMN_ROW_STATE, ROW_STATE_INVALIDATED);
            database.update(TABLE_DOCUMENTS, values, selection, args);

            // If we have rows that does not have MTP identifier, do heuristic mapping by name.
            final boolean useNameForResolving = DatabaseUtils.queryNumEntries(
                    database,
                    TABLE_DOCUMENTS,
                    selection + " AND " + COLUMN_STORAGE_ID + " IS NULL",
                    args) > 0;
            database.setTransactionSuccessful();
            mMappingMode.put(
                    parentDocumentId, useNameForResolving ? MAP_BY_NAME : MAP_BY_MTP_IDENTIFIER);
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Puts the documents into the database.
     * If the mapping mode is not heuristic, it just adds the rows to the database or updates the
     * existing rows with the new values. If the mapping mode is heuristic, it adds some new rows as
     * 'pending' state when that rows may be corresponding to existing 'invalidated' rows. Then
     * {@link #stopAddingDocuments(String)} turns the pending rows into 'valid'
     * rows. If the methods adds rows to database, it updates valueList with correct document ID.
     *
     * @param valuesList Values for documents to be stored in the database.
     * @param rootExtraValuesList Values for root extra to be stored in the database.
     * @param selection SQL where closure to select rows that shares the same parent.
     * @param args Argument for selection SQL.
     * @param heuristic Whether the mapping mode is heuristic.
     * @return Whether the method adds new rows.
     */
    private boolean putDocuments(
            ContentValues[] valuesList,
            @Nullable ContentValues[] rootExtraValuesList,
            String selection,
            String[] args,
            boolean heuristic,
            String mappingKey) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        boolean added = false;
        database.beginTransaction();
        try {
            for (int i = 0; i < valuesList.length; i++) {
                final ContentValues values = valuesList[i];
                final ContentValues rootExtraValues;
                if (rootExtraValuesList != null) {
                    rootExtraValues = rootExtraValuesList[i];
                } else {
                    rootExtraValues = null;
                }
                final Cursor candidateCursor = database.query(
                        TABLE_DOCUMENTS,
                        strings(Document.COLUMN_DOCUMENT_ID),
                        selection + " AND " +
                        COLUMN_ROW_STATE + "=? AND " +
                        mappingKey + "=?",
                        DatabaseUtils.appendSelectionArgs(
                                args,
                                strings(ROW_STATE_INVALIDATED, values.getAsString(mappingKey))),
                        null,
                        null,
                        null,
                        "1");
                try {
                    final long rowId;
                    if (candidateCursor.getCount() == 0) {
                        rowId = database.insert(TABLE_DOCUMENTS, null, values);
                        added = true;
                    } else if (!heuristic) {
                        candidateCursor.moveToNext();
                        rowId = candidateCursor.getLong(0);
                        database.update(
                                TABLE_DOCUMENTS,
                                values,
                                SELECTION_DOCUMENT_ID,
                                strings(rowId));
                    } else {
                        values.put(COLUMN_ROW_STATE, ROW_STATE_PENDING);
                        rowId = database.insertOrThrow(TABLE_DOCUMENTS, null, values);
                    }
                    // Document ID is a primary integer key of the table. So the returned row
                    // IDs should be same with the document ID.
                    values.put(Document.COLUMN_DOCUMENT_ID, rowId);
                    if (rootExtraValues != null) {
                        rootExtraValues.put(Root.COLUMN_ROOT_ID, rowId);
                        database.replace(TABLE_ROOT_EXTRA, null, rootExtraValues);
                    }
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
     * @param parentId Parent document ID or null for root documents.
     * @return Whether the methods adds or removed visible rows.
     */
    boolean stopAddingDocuments(@Nullable String parentId) {
        Preconditions.checkState(mMappingMode.containsKey(parentId));
        final String selection;
        final String[] args;
        if (parentId != null) {
            selection = COLUMN_PARENT_DOCUMENT_ID + "=?";
            args = strings(parentId);
        } else {
            selection = COLUMN_PARENT_DOCUMENT_ID + " IS NULL";
            args = EMPTY_ARGS;
        }
        final String groupKey;
        switch (mMappingMode.get(parentId)) {
            case MAP_BY_MTP_IDENTIFIER:
                groupKey = parentId != null ? COLUMN_OBJECT_HANDLE : COLUMN_STORAGE_ID;
                break;
            case MAP_BY_NAME:
                groupKey = Document.COLUMN_DISPLAY_NAME;
                break;
            default:
                throw new Error("Unexpected mapping state.");
        }
        mMappingMode.remove(parentId);
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
                    args,
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
                    DatabaseUtils.appendSelectionArgs(strings(ROW_STATE_INVALIDATED), args))) {
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
                    DatabaseUtils.appendSelectionArgs(strings(ROW_STATE_PENDING), args)) != 0) {
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
        try {
            if (cursor.getCount() == 0) {
                return;
            }
            cursor.moveToNext();
            DatabaseUtils.cursorRowToContentValues(cursor, values);
        } finally {
            cursor.close();
        }
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
}
