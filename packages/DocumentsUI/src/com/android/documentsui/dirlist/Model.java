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

package com.android.documentsui.dirlist;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.State.SORT_ORDER_DISPLAY_NAME;
import static com.android.documentsui.State.SORT_ORDER_LAST_MODIFIED;
import static com.android.documentsui.State.SORT_ORDER_SIZE;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.database.Cursor;
import android.os.Bundle;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.DirectoryResult;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.Shared;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The data model for the current loaded directory.
 */
@VisibleForTesting
public class Model {
    private static final String TAG = "Model";

    private boolean mIsLoading;
    private List<UpdateListener> mUpdateListeners = new ArrayList<>();
    @Nullable private Cursor mCursor;
    private int mCursorCount;
    /** Maps Model ID to cursor positions, for looking up items by Model ID. */
    private Map<String, Integer> mPositions = new HashMap<>();
    /**
     * A sorted array of model IDs for the files currently in the Model.  Sort order is determined
     * by {@link #mSortOrder}
     */
    private List<String> mIds = new ArrayList<>();
    private int mSortOrder = SORT_ORDER_DISPLAY_NAME;

    @Nullable String info;
    @Nullable String error;

    /**
     * Generates a Model ID for a cursor entry that refers to a document. The Model ID is a unique
     * string that can be used to identify the document referred to by the cursor.
     *
     * @param c A cursor that refers to a document.
     */
    private static String createModelId(Cursor c) {
        // TODO: Maybe more efficient to use just the document ID, in cases where there is only one
        // authority (which should be the majority of cases).
        return createModelId(
                getCursorString(c, RootCursorWrapper.COLUMN_AUTHORITY),
                getCursorString(c, Document.COLUMN_DOCUMENT_ID));
    }

    /**
     * Generates a Model ID for a cursor entry that refers to a document. The Model ID is a unique
     * string that can be used to identify the document referred to by the cursor.
     *
     * @param c A cursor that refers to a document.
     */
    static String createModelId(String authority, String docId) {
        return authority + "|" + docId;
    }

    private void notifyUpdateListeners() {
        for (UpdateListener listener: mUpdateListeners) {
            listener.onModelUpdate(this);
        }
    }

    private void notifyUpdateListeners(Exception e) {
        for (UpdateListener listener: mUpdateListeners) {
            listener.onModelUpdateFailed(e);
        }
    }

    void update(DirectoryResult result) {
        if (DEBUG) Log.i(TAG, "Updating model with new result set.");

        if (result == null) {
            mCursor = null;
            mCursorCount = 0;
            mIds.clear();
            mPositions.clear();
            info = null;
            error = null;
            mIsLoading = false;
            notifyUpdateListeners();
            return;
        }

        if (result.exception != null) {
            Log.e(TAG, "Error while loading directory contents", result.exception);
            notifyUpdateListeners(result.exception);
            return;
        }

        mCursor = result.cursor;
        mCursorCount = mCursor.getCount();
        mSortOrder = result.sortOrder;

        updateModelData();

        final Bundle extras = mCursor.getExtras();
        if (extras != null) {
            info = extras.getString(DocumentsContract.EXTRA_INFO);
            error = extras.getString(DocumentsContract.EXTRA_ERROR);
            mIsLoading = extras.getBoolean(DocumentsContract.EXTRA_LOADING, false);
        }

        notifyUpdateListeners();
    }

    @VisibleForTesting
    int getItemCount() {
        return mCursorCount;
    }

    /**
     * Scan over the incoming cursor data, generate Model IDs for each row, and sort the IDs
     * according to the current sort order.
     */
    private void updateModelData() {
        int[] positions = new int[mCursorCount];
        mIds.clear();
        String[] stringValues = new String[mCursorCount];
        long[] longValues = null;

        if (mSortOrder == SORT_ORDER_LAST_MODIFIED || mSortOrder == SORT_ORDER_SIZE) {
            longValues = new long[mCursorCount];
        }

        mCursor.moveToPosition(-1);
        for (int pos = 0; pos < mCursorCount; ++pos) {
            mCursor.moveToNext();
            positions[pos] = pos;
            mIds.add(createModelId(mCursor));

            switch(mSortOrder) {
                case SORT_ORDER_DISPLAY_NAME:
                    final String mimeType = getCursorString(mCursor, Document.COLUMN_MIME_TYPE);
                    final String displayName = getCursorString(
                            mCursor, Document.COLUMN_DISPLAY_NAME);
                    if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                        stringValues[pos] = Shared.DIR_PREFIX + displayName;
                    } else {
                        stringValues[pos] = displayName;
                    }
                    break;
                case SORT_ORDER_LAST_MODIFIED:
                    longValues[pos] = getLastModified(mCursor);
                    stringValues[pos] = getCursorString(mCursor, Document.COLUMN_MIME_TYPE);
                    break;
                case SORT_ORDER_SIZE:
                    longValues[pos] = getCursorLong(mCursor, Document.COLUMN_SIZE);
                    stringValues[pos] = getCursorString(mCursor, Document.COLUMN_MIME_TYPE);
                    break;
            }
        }

        switch (mSortOrder) {
            case SORT_ORDER_DISPLAY_NAME:
                binarySort(stringValues, positions, mIds);
                break;
            case SORT_ORDER_LAST_MODIFIED:
            case SORT_ORDER_SIZE:
                binarySort(longValues, stringValues, positions, mIds);
                break;
        }

        // Populate the positions.
        mPositions.clear();
        for (int i = 0; i < mCursorCount; ++i) {
            mPositions.put(mIds.get(i), positions[i]);
        }
    }

    /**
     * Sorts model data. Takes three columns of index-corresponded data. The first column is the
     * sort key. Rows are sorted in ascending alphabetical order on the sort key. This code is based
     * on TimSort.binarySort().
     *
     * @param sortKey Data is sorted in ascending alphabetical order.
     * @param positions Cursor positions to be sorted.
     * @param ids Model IDs to be sorted.
     */
    private static void binarySort(String[] sortKey, int[] positions, List<String> ids) {
        final int count = positions.length;
        for (int start = 1; start < count; start++) {
            final int pivotPosition = positions[start];
            final String pivotValue = sortKey[start];
            final String pivotId = ids.get(start);

            int left = 0;
            int right = start;

            while (left < right) {
                int mid = (left + right) >>> 1;

                final String lhs = pivotValue;
                final String rhs = sortKey[mid];
                final int compare = Shared.compareToIgnoreCaseNullable(lhs, rhs);

                if (compare < 0) {
                    right = mid;
                } else {
                    left = mid + 1;
                }
            }

            int n = start - left;
            switch (n) {
                case 2:
                    positions[left + 2] = positions[left + 1];
                    sortKey[left + 2] = sortKey[left + 1];
                    ids.set(left + 2, ids.get(left + 1));
                case 1:
                    positions[left + 1] = positions[left];
                    sortKey[left + 1] = sortKey[left];
                    ids.set(left + 1, ids.get(left));
                    break;
                default:
                    System.arraycopy(positions, left, positions, left + 1, n);
                    System.arraycopy(sortKey, left, sortKey, left + 1, n);
                    for (int i = n; i >= 1; --i) {
                        ids.set(left + i, ids.get(left + i - 1));
                    }
            }

            positions[left] = pivotPosition;
            sortKey[left] = pivotValue;
            ids.set(left, pivotId);
        }
    }

    /**
     * Sorts model data. Takes four columns of index-corresponded data. The first column is the sort
     * key, and the second is an array of mime types. The rows are first bucketed by mime type
     * (directories vs documents) and then each bucket is sorted independently in descending
     * numerical order on the sort key. This code is based on TimSort.binarySort().
     *
     * @param sortKey Data is sorted in descending numerical order.
     * @param mimeTypes Corresponding mime types. Directories will be sorted ahead of documents.
     * @param positions Cursor positions to be sorted.
     * @param ids Model IDs to be sorted.
     */
    private static void binarySort(
            long[] sortKey, String[] mimeTypes, int[] positions, List<String> ids) {
        final int count = positions.length;
        for (int start = 1; start < count; start++) {
            final int pivotPosition = positions[start];
            final long pivotValue = sortKey[start];
            final String pivotMime = mimeTypes[start];
            final String pivotId = ids.get(start);

            int left = 0;
            int right = start;

            while (left < right) {
                int mid = ((left + right) >>> 1);

                // First bucket by mime type.  Directories always go in front.
                int compare = 0;
                final boolean lhsIsDir = Document.MIME_TYPE_DIR.equals(pivotMime);
                final boolean rhsIsDir = Document.MIME_TYPE_DIR.equals(mimeTypes[mid]);
                if (lhsIsDir && !rhsIsDir) {
                    compare = -1;
                } else if (!lhsIsDir && rhsIsDir) {
                    compare = 1;
                } else {
                    final long lhs = pivotValue;
                    final long rhs = sortKey[mid];
                    // Sort in descending numerical order. This matches legacy behaviour, which
                    // yields largest or most recent items on top.
                    compare = -Long.compare(lhs, rhs);
                }

                // If numerical comparison yields a tie, use document ID as a tie breaker.  This
                // will yield stable results even if incoming items are continually shuffling and
                // have identical numerical sort keys.  One common example of this scenario is seen
                // when sorting a set of active downloads by mod time.
                if (compare == 0) {
                    compare = pivotId.compareTo(ids.get(mid));
                }

                if (compare < 0) {
                    right = mid;
                } else {
                    left = mid + 1;
                }
            }

            int n = start - left;
            switch (n) {
                case 2:
                    positions[left + 2] = positions[left + 1];
                    sortKey[left + 2] = sortKey[left + 1];
                    mimeTypes[left + 2] = mimeTypes[left + 1];
                    ids.set(left + 2, ids.get(left + 1));
                case 1:
                    positions[left + 1] = positions[left];
                    sortKey[left + 1] = sortKey[left];
                    mimeTypes[left + 1] = mimeTypes[left];
                    ids.set(left + 1, ids.get(left));
                    break;
                default:
                    System.arraycopy(positions, left, positions, left + 1, n);
                    System.arraycopy(sortKey, left, sortKey, left + 1, n);
                    System.arraycopy(mimeTypes, left, mimeTypes, left + 1, n);
                    for (int i = n; i >= 1; --i) {
                        ids.set(left + i, ids.get(left + i - 1));
                    }
            }

            positions[left] = pivotPosition;
            sortKey[left] = pivotValue;
            mimeTypes[left] = pivotMime;
            ids.set(left, pivotId);
        }
    }

    /**
     * @return Timestamp for the given document. Some docs (e.g. active downloads) have a null
     * timestamp - these will be replaced with MAX_LONG so that such files get sorted to the top
     * when sorting by date.
     */
    long getLastModified(Cursor cursor) {
        long l = getCursorLong(mCursor, Document.COLUMN_LAST_MODIFIED);
        return (l == -1) ? Long.MAX_VALUE : l;
    }

    public @Nullable Cursor getItem(String modelId) {
        Integer pos = mPositions.get(modelId);
        if (pos != null) {
            mCursor.moveToPosition(pos);
            return mCursor;
        }
        return null;
    }

    boolean isEmpty() {
        return mCursorCount == 0;
    }

    boolean isLoading() {
        return mIsLoading;
    }

    List<DocumentInfo> getDocuments(Selection items) {
        final int size = (items != null) ? items.size() : 0;

        final List<DocumentInfo> docs =  new ArrayList<>(size);
        for (String modelId: items.getAll()) {
            final Cursor cursor = getItem(modelId);
            checkNotNull(cursor, "Cursor cannot be null.");
            final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
            docs.add(doc);
        }
        return docs;
    }

    void addUpdateListener(UpdateListener listener) {
        mUpdateListeners.add(listener);
    }

    void removeUpdateListener(UpdateListener listener) {
        mUpdateListeners.remove(listener);
    }

    static interface UpdateListener {
        /**
         * Called when a successful update has occurred.
         */
        void onModelUpdate(Model model);

        /**
         * Called when an update has been attempted but failed.
         */
        void onModelUpdateFailed(Exception e);
    }

    /**
     * @return An ordered array of model IDs representing the documents in the model. It is sorted
     *         according to the current sort order, which was set by the last model update.
     */
    public List<String> getModelIds() {
        return mIds;
    }
}
