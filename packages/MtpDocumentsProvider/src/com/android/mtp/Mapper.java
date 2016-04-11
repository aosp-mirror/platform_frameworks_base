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

import android.annotation.Nullable;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.FileNotFoundException;
import java.util.Set;

import static com.android.mtp.MtpDatabaseConstants.*;
import static com.android.mtp.MtpDatabase.strings;

/**
 * Mapping operations for MtpDatabase.
 * Also see the comments of {@link MtpDatabase}.
 */
class Mapper {
    private static final String[] EMPTY_ARGS = new String[0];
    private final MtpDatabase mDatabase;

    /**
     * IDs which currently Mapper operates mapping for.
     */
    private final Set<String> mInMappingIds = new ArraySet<>();

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
                    strings(COLUMN_DEVICE_ID, COLUMN_MAPPING_KEY));
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
    synchronized boolean putStorageDocuments(
            String parentDocumentId, int[] operationsSupported, MtpRoot[] roots)
            throws FileNotFoundException {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            final ContentValues[] valuesList = new ContentValues[roots.length];
            final ContentValues[] extraValuesList = new ContentValues[roots.length];
            for (int i = 0; i < roots.length; i++) {
                valuesList[i] = new ContentValues();
                extraValuesList[i] = new ContentValues();
                MtpDatabase.getStorageDocumentValues(
                        valuesList[i],
                        extraValuesList[i],
                        parentDocumentId,
                        operationsSupported,
                        roots[i]);
            }
            final boolean changed = putDocuments(
                    parentDocumentId,
                    valuesList,
                    extraValuesList,
                    COLUMN_PARENT_DOCUMENT_ID + " = ?",
                    strings(parentDocumentId),
                    strings(COLUMN_STORAGE_ID, Document.COLUMN_DISPLAY_NAME));

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
     * @param documentSizes 64-bit size of documents. MtpObjectInfo#getComporessedSize will be
     *     ignored because it does not contain 4GB> object size. Can be -1 if the size is unknown.
     * @throws FileNotFoundException
     */
    synchronized void putChildDocuments(
            int deviceId, String parentId,
            int[] operationsSupported,
            MtpObjectInfo[] documents,
            long[] documentSizes)
            throws FileNotFoundException {
        assert documents.length == documentSizes.length;
        final ContentValues[] valuesList = new ContentValues[documents.length];
        for (int i = 0; i < documents.length; i++) {
            valuesList[i] = new ContentValues();
            MtpDatabase.getObjectDocumentValues(
                    valuesList[i],
                    deviceId,
                    parentId,
                    operationsSupported,
                    documents[i],
                    documentSizes[i]);
        }
        putDocuments(
                parentId,
                valuesList,
                null,
                COLUMN_PARENT_DOCUMENT_ID + " = ?",
                strings(parentId),
                strings(COLUMN_OBJECT_HANDLE, Document.COLUMN_DISPLAY_NAME));
    }

    void clearMapping() {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            mInMappingIds.clear();
            // Disconnect all device rows.
            try {
                startAddingDocuments(null);
                stopAddingDocuments(null);
            } catch (FileNotFoundException exception) {
                Log.e(MtpDocumentsProvider.TAG, "Unexpected FileNotFoundException.", exception);
                throw new RuntimeException(exception);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Starts adding new documents.
     * It changes the direct child documents of the given document from VALID to INVALIDATED.
     * Note that it keeps DISCONNECTED documents as they are.
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
            Preconditions.checkState(!mInMappingIds.contains(parentDocumentId));

            // Set all valid documents as invalidated.
            final ContentValues values = new ContentValues();
            values.put(COLUMN_ROW_STATE, ROW_STATE_INVALIDATED);
            database.update(
                    TABLE_DOCUMENTS,
                    values,
                    selection + " AND " + COLUMN_ROW_STATE + " = ?",
                    DatabaseUtils.appendSelectionArgs(args, strings(ROW_STATE_VALID)));

            database.setTransactionSuccessful();
            mInMappingIds.add(parentDocumentId);
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
     * @return Whether the database content is changed.
     * @throws FileNotFoundException When parentId is not registered in the database.
     */
    private boolean putDocuments(
            String parentId,
            ContentValues[] valuesList,
            @Nullable ContentValues[] rootExtraValuesList,
            String selection,
            String[] args,
            String[] mappingKeys) throws FileNotFoundException {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        boolean changed = false;
        database.beginTransaction();
        try {
            getParentOrHaltMapping(parentId);
            Preconditions.checkState(mInMappingIds.contains(parentId));
            final ContentValues oldRowSnapshot = new ContentValues();
            final ContentValues newRowSnapshot = new ContentValues();
            for (int i = 0; i < valuesList.length; i++) {
                final ContentValues values = valuesList[i];
                final ContentValues rootExtraValues;
                if (rootExtraValuesList != null) {
                    rootExtraValues = rootExtraValuesList[i];
                } else {
                    rootExtraValues = null;
                }
                try (final Cursor candidateCursor =
                        queryCandidate(selection, args, mappingKeys, values)) {
                    final long rowId;
                    if (candidateCursor == null) {
                        rowId = database.insert(TABLE_DOCUMENTS, null, values);
                        changed = true;
                    } else {
                        candidateCursor.moveToNext();
                        rowId = candidateCursor.getLong(0);
                        if (!changed) {
                            mDatabase.writeRowSnapshot(String.valueOf(rowId), oldRowSnapshot);
                        }
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

                    if (!changed) {
                        mDatabase.writeRowSnapshot(String.valueOf(rowId), newRowSnapshot);
                        // Put row state as string because SQLite returns snapshot values as string.
                        oldRowSnapshot.put(COLUMN_ROW_STATE, String.valueOf(ROW_STATE_VALID));
                        if (!oldRowSnapshot.equals(newRowSnapshot)) {
                            changed = true;
                        }
                    }
                }
            }

            database.setTransactionSuccessful();
            return changed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Stops adding documents.
     * It handles 'invalidated' and 'disconnected' documents which we don't put corresponding
     * documents so far.
     * If the type adding document is 'device' or 'storage', the document may appear again
     * afterward. The method marks such documents as 'disconnected'. If the type of adding document
     * is 'object', it seems the documents are really removed from the remote MTP device. So the
     * method deletes the metadata from the database.
     *
     * @param parentId Parent document ID or null for root documents.
     * @return Whether the methods changes file metadata in database.
     * @throws FileNotFoundException
     */
    boolean stopAddingDocuments(@Nullable String parentId) throws FileNotFoundException {
        final String selection;
        final String[] args;
        if (parentId != null) {
            selection = COLUMN_PARENT_DOCUMENT_ID + " = ?";
            args = strings(parentId);
        } else {
            selection = COLUMN_PARENT_DOCUMENT_ID + " IS NULL";
            args = EMPTY_ARGS;
        }

        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            final Identifier parentIdentifier = getParentOrHaltMapping(parentId);
            Preconditions.checkState(mInMappingIds.contains(parentId));
            mInMappingIds.remove(parentId);

            boolean changed = false;
            // Delete/disconnect all invalidated/disconnected rows that cannot be mapped.
            // If parentIdentifier is null, added documents are devices.
            // if parentIdentifier is DOCUMENT_TYPE_DEVICE, added documents are storages.
            final boolean keepUnmatchedDocument =
                    parentIdentifier == null ||
                    parentIdentifier.mDocumentType == DOCUMENT_TYPE_DEVICE;
            if (keepUnmatchedDocument) {
                if (mDatabase.disconnectDocumentsRecursively(
                        COLUMN_ROW_STATE + " = ? AND " + selection,
                        DatabaseUtils.appendSelectionArgs(strings(ROW_STATE_INVALIDATED), args))) {
                    changed = true;
                }
            } else {
                if (mDatabase.deleteDocumentsAndRootsRecursively(
                        COLUMN_ROW_STATE + " IN (?, ?) AND " + selection,
                        DatabaseUtils.appendSelectionArgs(
                                strings(ROW_STATE_INVALIDATED, ROW_STATE_DISCONNECTED), args))) {
                    changed = true;
                }
            }

            database.setTransactionSuccessful();
            return changed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Cancels adding documents.
     * @param parentId
     */
    void cancelAddingDocuments(@Nullable String parentId) {
        final String selection;
        final String[] args;
        if (parentId != null) {
            selection = COLUMN_PARENT_DOCUMENT_ID + " = ?";
            args = strings(parentId);
        } else {
            selection = COLUMN_PARENT_DOCUMENT_ID + " IS NULL";
            args = EMPTY_ARGS;
        }

        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        database.beginTransaction();
        try {
            if (!mInMappingIds.contains(parentId)) {
                return;
            }
            mInMappingIds.remove(parentId);
            final ContentValues values = new ContentValues();
            values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
            mDatabase.getSQLiteDatabase().update(
                    TABLE_DOCUMENTS,
                    values,
                    selection + " AND " + COLUMN_ROW_STATE + " = ?",
                    DatabaseUtils.appendSelectionArgs(args, strings(ROW_STATE_INVALIDATED)));
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Queries candidate for each mappingKey, and returns the first cursor that includes a
     * candidate.
     *
     * @param selection Pre-selection for candidate.
     * @param args Arguments for selection.
     * @param mappingKeys List of mapping key columns.
     * @param values Values of document that Mapper tries to map.
     * @return Cursor for mapping candidate or null when Mapper does not find any candidate.
     */
    private @Nullable Cursor queryCandidate(
            String selection, String[] args, String[] mappingKeys, ContentValues values) {
        for (final String mappingKey : mappingKeys) {
            final Cursor candidateCursor = queryCandidate(selection, args, mappingKey, values);
            if (candidateCursor.getCount() == 0) {
                candidateCursor.close();
                continue;
            }
            return candidateCursor;
        }
        return null;
    }

    /**
     * Looks for mapping candidate with given mappingKey.
     *
     * @param selection Pre-selection for candidate.
     * @param args Arguments for selection.
     * @param mappingKey Column name of mapping key.
     * @param values Values of document that Mapper tries to map.
     * @return Cursor for mapping candidate.
     */
    private Cursor queryCandidate(
            String selection, String[] args, String mappingKey, ContentValues values) {
        final SQLiteDatabase database = mDatabase.getSQLiteDatabase();
        return database.query(
                TABLE_DOCUMENTS,
                strings(Document.COLUMN_DOCUMENT_ID),
                selection + " AND " +
                COLUMN_ROW_STATE + " IN (?, ?) AND " +
                mappingKey + " = ?",
                DatabaseUtils.appendSelectionArgs(
                        args,
                        strings(ROW_STATE_INVALIDATED,
                                ROW_STATE_DISCONNECTED,
                                values.getAsString(mappingKey))),
                null,
                null,
                null,
                "1");
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
            mInMappingIds.remove(parentId);
            throw error;
        }
    }
}
