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
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import com.android.internal.util.Preconditions;

import java.io.FileNotFoundException;
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
     *
     * @return If device is added to the database.
     * @throws FileNotFoundException
     */
    synchronized boolean putDeviceDocument(MtpDeviceRecord device) throws FileNotFoundException {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            final ContentValues[] valuesList = new ContentValues[1];
            final ContentValues[] extraValuesList = new ContentValues[1];
            valuesList[0] = new ContentValues();
            extraValuesList[0] = new ContentValues();
            MtpDatabase.getDeviceDocumentValues(valuesList[0], extraValuesList[0], device);
            final boolean changed = putDocuments(
                    null,
                    valuesList,
                    extraValuesList,
                    COLUMN_PARENT_DOCUMENT_ID + " IS NULL",
                    EMPTY_ARGS,
                    COLUMN_DEVICE_ID);
            database.setTransactionSuccessful();
            return changed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Puts root information to database.
     *
     * @param parentDocumentId Document ID of device document.
     * @param roots List of root information.
     * @return If roots are added or removed from the database.
     * @throws FileNotFoundException
     */
    synchronized boolean putStorageDocuments(String parentDocumentId, MtpRoot[] roots)
            throws FileNotFoundException {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            final String mapColumn;
            Preconditions.checkState(mMappingMode.containsKey(parentDocumentId));
            switch (mMappingMode.get(parentDocumentId)) {
                case MAP_BY_MTP_IDENTIFIER:
                    mapColumn = COLUMN_STORAGE_ID;
                    break;
                case MAP_BY_NAME:
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
                        valuesList[i], extraValuesList[i], parentDocumentId, roots[i]);
            }
            final boolean changed = putDocuments(
                    parentDocumentId,
                    valuesList,
                    extraValuesList,
                    COLUMN_PARENT_DOCUMENT_ID + "=?",
                    strings(parentDocumentId),
                    mapColumn);

            database.setTransactionSuccessful();
            return changed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Puts document information to database.
     *
     * @param deviceId Device ID
     * @param parentId Parent document ID.
     * @param documents List of document information.
     * @throws FileNotFoundException
     */
    synchronized void putChildDocuments(int deviceId, String parentId, MtpObjectInfo[] documents)
            throws FileNotFoundException {
        final String mapColumn;
        Preconditions.checkState(mMappingMode.containsKey(parentId));
        switch (mMappingMode.get(parentId)) {
            case MAP_BY_MTP_IDENTIFIER:
                mapColumn = COLUMN_OBJECT_HANDLE;
                break;
            case MAP_BY_NAME:
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
                parentId,
                valuesList,
                null,
                COLUMN_PARENT_DOCUMENT_ID + "=?",
                strings(parentId),
                mapColumn);
    }

    void clearMapping() {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
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
     * @throws FileNotFoundException
     */
    void startAddingDocuments(@Nullable String parentDocumentId) throws FileNotFoundException {
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
            getParentOrHaltMapping(parentDocumentId);
            Preconditions.checkState(!mMappingMode.containsKey(parentDocumentId));

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
     * @param parentId Parent document ID.
     * @param valuesList Values for documents to be stored in the database.
     * @param rootExtraValuesList Values for root extra to be stored in the database.
     * @param selection SQL where closure to select rows that shares the same parent.
     * @param args Argument for selection SQL.
     * @return Whether it adds at least one new row that is not mapped with existing document ID.
     * @throws FileNotFoundException When parentId is not registered in the database.
     */
    private boolean putDocuments(
            String parentId,
            ContentValues[] valuesList,
            @Nullable ContentValues[] rootExtraValuesList,
            String selection,
            String[] args,
            String mappingKey) throws FileNotFoundException {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        boolean added = false;
        database.beginTransaction();
        try {
            getParentOrHaltMapping(parentId);
            Preconditions.checkState(mMappingMode.containsKey(parentId));
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
                    } else {
                        candidateCursor.moveToNext();
                        rowId = candidateCursor.getLong(0);
                        database.update(
                                TABLE_DOCUMENTS,
                                values,
                                SELECTION_DOCUMENT_ID,
                                strings(rowId));
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
     *
     * @param parentId Parent document ID or null for root documents.
     * @return Whether the methods adds or removed visible rows.
     * @throws FileNotFoundException
     */
    boolean stopAddingDocuments(@Nullable String parentId) throws FileNotFoundException {
        final String selection;
        final String[] args;
        if (parentId != null) {
            selection = COLUMN_PARENT_DOCUMENT_ID + "=?";
            args = strings(parentId);
        } else {
            selection = COLUMN_PARENT_DOCUMENT_ID + " IS NULL";
            args = EMPTY_ARGS;
        }

        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            getParentOrHaltMapping(parentId);
            Preconditions.checkState(mMappingMode.containsKey(parentId));
            mMappingMode.remove(parentId);

            boolean changed = false;
            // Delete all invalidated rows that cannot be mapped.
            if (mDatabase.deleteDocumentsAndRootsRecursively(
                    COLUMN_ROW_STATE + " = ? AND " + selection,
                    DatabaseUtils.appendSelectionArgs(strings(ROW_STATE_INVALIDATED), args))) {
                changed = true;
            }

            database.setTransactionSuccessful();
            return changed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Returns the parent identifier from parent document ID if the parent ID is found in the
     * database. Otherwise it halts mapping and throws FileNotFoundException.
     *
     * @param parentId Parent document ID
     * @return Parent identifier
     * @throws FileNotFoundException
     */
    private @Nullable Identifier getParentOrHaltMapping(
            @Nullable String parentId) throws FileNotFoundException {
        if (parentId == null) {
            return null;
        }
        try {
            return mDatabase.createIdentifier(parentId);
        } catch (FileNotFoundException error) {
            mMappingMode.remove(parentId);
            throw error;
        }
    }
}
