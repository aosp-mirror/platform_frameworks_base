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
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.android.documentsui.BaseActivity.DocumentContext;
import com.android.documentsui.DirectoryResult;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * The data model for the current loaded directory.
 */
@VisibleForTesting
public class Model implements DocumentContext {
    private static final String TAG = "Model";
    private RecyclerView.Adapter<?> mViewAdapter;
    private Context mContext;
    private int mCursorCount;
    private boolean mIsLoading;
    @GuardedBy("mPendingDelete")
    private Boolean mPendingDelete = false;
    @GuardedBy("mPendingDelete")
    private SparseBooleanArray mMarkedForDeletion = new SparseBooleanArray();
    private Model.UpdateListener mUpdateListener;
    @Nullable private Cursor mCursor;
    @Nullable String info;
    @Nullable String error;
    private HashMap<String, Integer> mPositions = new HashMap<>();

    Model(Context context, RecyclerView.Adapter<?> viewAdapter) {
        mContext = context;
        mViewAdapter = viewAdapter;
    }

    void update(DirectoryResult result) {
        if (DEBUG) Log.i(TAG, "Updating model with new result set.");

        if (result == null) {
            mCursor = null;
            mCursorCount = 0;
            info = null;
            error = null;
            mIsLoading = false;
            mUpdateListener.onModelUpdate(this);
            return;
        }

        if (result.exception != null) {
            Log.e(TAG, "Error while loading directory contents", result.exception);
            mUpdateListener.onModelUpdateFailed(result.exception);
            return;
        }

        mCursor = result.cursor;
        mCursorCount = mCursor.getCount();

        updatePositions();

        final Bundle extras = mCursor.getExtras();
        if (extras != null) {
            info = extras.getString(DocumentsContract.EXTRA_INFO);
            error = extras.getString(DocumentsContract.EXTRA_ERROR);
            mIsLoading = extras.getBoolean(DocumentsContract.EXTRA_LOADING, false);
        }

        mUpdateListener.onModelUpdate(this);
    }

    int getItemCount() {
        synchronized(mPendingDelete) {
            return mCursorCount - mMarkedForDeletion.size();
        }
    }

    /**
     * Update the ModelId-position map.
     */
    private void updatePositions() {
        mPositions.clear();
        mCursor.moveToPosition(-1);
        for (int pos = 0; pos < mCursorCount; ++pos) {
            mCursor.moveToNext();
            // TODO(stable-id): factor the model ID construction code.
            String modelId = getCursorString(mCursor, RootCursorWrapper.COLUMN_AUTHORITY) +
                    "|" + getCursorString(mCursor, Document.COLUMN_DOCUMENT_ID);
            mPositions.put(modelId, pos);
        }
    }

    @Nullable Cursor getItem(String modelId) {
        Integer pos = mPositions.get(modelId);
        if (pos != null) {
            mCursor.moveToPosition(pos);
            return mCursor;
        }
        return null;
    }

    Cursor getItem(int position) {
        synchronized(mPendingDelete) {
            // Items marked for deletion are masked out of the UI.  To do this, for every marked
            // item whose position is less than the requested item position, advance the requested
            // position by 1.
            final int originalPos = position;
            final int size = mMarkedForDeletion.size();
            for (int i = 0; i < size; ++i) {
                // It'd be more concise, but less efficient, to iterate over positions while calling
                // mMarkedForDeletion.get.  Instead, iterate over deleted entries.
                if (mMarkedForDeletion.keyAt(i) <= position && mMarkedForDeletion.valueAt(i)) {
                    ++position;
                }
            }

            if (DEBUG && position != originalPos) {
                Log.d(TAG, "Item position adjusted for deletion.  Original: " + originalPos
                        + "  Adjusted: " + position);
            }

            if (position >= mCursorCount) {
                throw new IndexOutOfBoundsException("Attempt to retrieve " + position + " of " +
                        mCursorCount + " items");
            }

            mCursor.moveToPosition(position);
            return mCursor;
        }
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
        for (int i = 0; i < size; i++) {
            final Cursor cursor = getItem(items.get(i));
            checkNotNull(cursor, "Cursor cannot be null.");
            final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
            docs.add(doc);
        }
        return docs;
    }

    @Override
    public Cursor getCursor() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Can't call getCursor from non-main thread.");
        }
        return mCursor;
    }

    List<DocumentInfo> getDocumentsMarkedForDeletion() {
        synchronized (mPendingDelete) {
            final int size = mMarkedForDeletion.size();
            List<DocumentInfo> docs =  new ArrayList<>(size);

            for (int i = 0; i < size; ++i) {
                final int position = mMarkedForDeletion.keyAt(i);
                checkState(position < mCursorCount);
                mCursor.moveToPosition(position);
                final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(mCursor);
                docs.add(doc);
            }
            return docs;
        }
    }

    /**
     * Marks the given files for deletion. This will remove them from the UI. Clients must then
     * call either {@link #undoDeletion()} or {@link #finalizeDeletion()} to cancel or confirm
     * the deletion, respectively. Only one deletion operation is allowed at a time.
     *
     * @param selected A selection representing the files to delete.
     */
    void markForDeletion(Selection selected) {
        synchronized (mPendingDelete) {
            mPendingDelete = true;
            // Only one deletion operation at a time.
            checkState(mMarkedForDeletion.size() == 0);
            // There should never be more to delete than what exists.
            checkState(mCursorCount >= selected.size());

            int[] positions = selected.getAll();
            Arrays.sort(positions);

            // Walk backwards through the set, since we're removing positions.
            // Otherwise, positions would change after the first modification.
            for (int p = positions.length - 1; p >= 0; p--) {
                mMarkedForDeletion.append(positions[p], true);
                mViewAdapter.notifyItemRemoved(positions[p]);
                if (DEBUG) Log.d(TAG, "Scheduled " + positions[p] + " for delete.");
            }
        }
    }

    /**
     * Cancels an ongoing deletion operation. All files currently marked for deletion will be
     * unmarked, and restored in the UI.  See {@link #markForDeletion(Selection)}.
     */
    void undoDeletion() {
        synchronized (mPendingDelete) {
            // Iterate over deleted items, temporarily marking them false in the deletion list, and
            // re-adding them to the UI.
            final int size = mMarkedForDeletion.size();
            for (int i = 0; i < size; ++i) {
                final int position = mMarkedForDeletion.keyAt(i);
                mMarkedForDeletion.put(position, false);
                mViewAdapter.notifyItemInserted(position);
            }
            resetDeleteData();
        }
    }

    private void resetDeleteData() {
        synchronized (mPendingDelete) {
            mPendingDelete = false;
            mMarkedForDeletion.clear();
        }
    }

    /**
     * Finalizes an ongoing deletion operation. All files currently marked for deletion will be
     * deleted.  See {@link #markForDeletion(Selection)}.
     *
     * @param view The view which will be used to interact with the user (e.g. surfacing
     * snackbars) for errors, info, etc.
     */
    void finalizeDeletion(DeletionListener listener) {
        synchronized (mPendingDelete) {
            if (mPendingDelete) {
                // Necessary to avoid b/25072545. Even when that's resolved, this
                // is a nice safe thing to day.
                mPendingDelete = false;
                final ContentResolver resolver = mContext.getContentResolver();
                DeleteFilesTask task = new DeleteFilesTask(resolver, listener);
                task.execute();
            }
        }
    }

    /**
     * A Task which collects the DocumentInfo for documents that have been marked for deletion,
     * and actually deletes them.
     */
    private class DeleteFilesTask extends AsyncTask<Void, Void, List<DocumentInfo>> {
        private ContentResolver mResolver;
        private DeletionListener mListener;

        /**
         * @param resolver A ContentResolver for performing the actual file deletions.
         * @param errorCallback A Runnable that is executed in the event that one or more errors
         *     occured while copying files.  Execution will occur on the UI thread.
         */
        public DeleteFilesTask(ContentResolver resolver, DeletionListener listener) {
            mResolver = resolver;
            mListener = listener;
        }

        @Override
        protected List<DocumentInfo> doInBackground(Void... params) {
            return getDocumentsMarkedForDeletion();
        }

        @Override
        protected void onPostExecute(List<DocumentInfo> docs) {
            boolean hadTrouble = false;
            for (DocumentInfo doc : docs) {
                if (!doc.isDeleteSupported()) {
                    Log.w(TAG, doc + " could not be deleted.  Skipping...");
                    hadTrouble = true;
                    continue;
                }

                ContentProviderClient client = null;
                try {
                    if (DEBUG) Log.d(TAG, "Deleting: " + doc.displayName);
                    client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        mResolver, doc.derivedUri.getAuthority());
                    DocumentsContract.deleteDocument(client, doc.derivedUri);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to delete " + doc);
                    hadTrouble = true;
                } finally {
                    ContentProviderClient.releaseQuietly(client);
                }
            }

            if (hadTrouble) {
                // TODO show which files failed? b/23720103
                mListener.onError();
                if (DEBUG) Log.d(TAG, "Deletion task completed.  Some deletions failed.");
            } else {
                if (DEBUG) Log.d(TAG, "Deletion task completed successfully.");
            }
            resetDeleteData();

            mListener.onCompletion();
        }
    }

    static class DeletionListener {
        /**
         * Called when deletion has completed (regardless of whether an error occurred).
         */
        void onCompletion() {}

        /**
         * Called at the end of a deletion operation that produced one or more errors.
         */
        void onError() {}
    }

    void addUpdateListener(UpdateListener listener) {
        checkState(mUpdateListener == null);
        mUpdateListener = listener;
    }

    static class UpdateListener {
        /**
         * Called when a successful update has occurred.
         */
        void onModelUpdate(Model model) {}

        /**
         * Called when an update has been attempted but failed.
         */
        void onModelUpdateFailed(Exception e) {}
    }
}
