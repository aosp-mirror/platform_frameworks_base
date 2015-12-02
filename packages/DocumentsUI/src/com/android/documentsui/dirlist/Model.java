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
import android.util.SparseArray;

import com.android.documentsui.BaseActivity.DocumentContext;
import com.android.documentsui.DirectoryResult;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private Set<String> mMarkedForDeletion = new HashSet<>();
    private List<UpdateListener> mUpdateListeners = new ArrayList<>();
    @Nullable private Cursor mCursor;
    @Nullable String info;
    @Nullable String error;
    private HashMap<String, Integer> mPositions = new HashMap<>();

    Model(Context context, RecyclerView.Adapter<?> viewAdapter) {
        mContext = context;
        mViewAdapter = viewAdapter;
    }

    /**
     * Generates a Model ID for a cursor entry that refers to a document. The Model ID is a
     * unique string that can be used to identify the document referred to by the cursor.
     *
     * @param c A cursor that refers to a document.
     */
    public static String createId(Cursor c) {
        return getCursorString(c, RootCursorWrapper.COLUMN_AUTHORITY) +
                "|" + getCursorString(c, Document.COLUMN_DOCUMENT_ID);
    }

    /**
     * @return Model IDs for all known items in the model. Note that this will include items
     *         pending deletion.
     */
    public Set<String> getIds() {
        return mPositions.keySet();
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

        updatePositions();

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
            mPositions.put(Model.createId(mCursor), pos);
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

    @Override
    public Cursor getCursor() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Can't call getCursor from non-main thread.");
        }
        return mCursor;
    }

    List<DocumentInfo> getDocumentsMarkedForDeletion() {
        // TODO(stable-id): This could be just a plain old selection now.
        synchronized (mPendingDelete) {
            final int size = mMarkedForDeletion.size();
            List<DocumentInfo> docs =  new ArrayList<>(size);

            for (String id: mMarkedForDeletion) {
                Integer position = mPositions.get(id);
                checkState(position != null);
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

            // Adapter notifications must be sent in reverse order of adapter position.  This is
            // because each removal causes subsequent item adapter positions to change.
            SparseArray<String> ids = new SparseArray<>();
            for (int i = ids.size() - 1; i >= 0; i--) {
                int pos = ids.keyAt(i);
                mMarkedForDeletion.add(ids.get(pos));
                mViewAdapter.notifyItemRemoved(pos);
                if (DEBUG) Log.d(TAG, "Scheduled " + pos + " for delete.");
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
            for (String id: mMarkedForDeletion) {
                Integer pos= mPositions.get(id);
                checkNotNull(pos);
                mMarkedForDeletion.remove(id);
                mViewAdapter.notifyItemInserted(pos);
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
        mUpdateListeners.add(listener);
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
}
