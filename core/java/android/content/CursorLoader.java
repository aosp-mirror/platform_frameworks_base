/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.net.Uri;

/**
 * A loader that queries the {@link ContentResolver} and returns a {@link Cursor}.
 */
public class CursorLoader extends AsyncTaskLoader<Cursor> {
    Cursor mCursor;
    ForceLoadContentObserver mObserver;
    boolean mStopped;
    Uri mUri;
    String[] mProjection;
    String mSelection;
    String[] mSelectionArgs;
    String mSortOrder;

    /* Runs on a worker thread */
    @Override
    public Cursor loadInBackground() {
        Cursor cursor = getContext().getContentResolver().query(mUri, mProjection, mSelection,
                mSelectionArgs, mSortOrder);
        // Ensure the cursor window is filled
        if (cursor != null) {
            cursor.getCount();
            cursor.registerContentObserver(mObserver);
        }
        return cursor;
    }

    /* Runs on the UI thread */
    @Override
    public void deliverResult(Cursor cursor) {
        if (mStopped) {
            // An async query came in while the loader is stopped
            if (cursor != null) {
                cursor.close();
            }
            return;
        }
        Cursor oldCursor = mCursor;
        mCursor = cursor;
        super.deliverResult(cursor);

        if (oldCursor != null && !oldCursor.isClosed()) {
            oldCursor.close();
        }
    }

    public CursorLoader(Context context, Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        super(context);
        mObserver = new ForceLoadContentObserver();
        mUri = uri;
        mProjection = projection;
        mSelection = selection;
        mSelectionArgs = selectionArgs;
        mSortOrder = sortOrder;
    }

    /**
     * Starts an asynchronous load of the contacts list data. When the result is ready the callbacks
     * will be called on the UI thread. If a previous load has been completed and is still valid
     * the result may be passed to the callbacks immediately.
     *
     * Must be called from the UI thread
     */
    @Override
    public void startLoading() {
        mStopped = false;

        if (mCursor != null) {
            deliverResult(mCursor);
        } else {
            forceLoad();
        }
    }

    /**
     * Must be called from the UI thread
     */
    @Override
    public void stopLoading() {
        if (mCursor != null && !mCursor.isClosed()) {
            mCursor.close();
        }
        mCursor = null;

        // Attempt to cancel the current load task if possible.
        cancelLoad();

        // Make sure that any outstanding loads clean themselves up properly
        mStopped = true;
    }

    @Override
    public void onCancelled(Cursor cursor) {
        if (cursor != null && !cursor.isClosed()) {
            cursor.close();
        }
    }

    @Override
    public void destroy() {
        // Ensure the loader is stopped
        stopLoading();
    }

    public Uri getUri() {
        return mUri;
    }

    public void setUri(Uri uri) {
        mUri = uri;
    }

    public String[] getProjection() {
        return mProjection;
    }

    public void setProjection(String[] projection) {
        mProjection = projection;
    }

    public String getSelection() {
        return mSelection;
    }

    public void setSelection(String selection) {
        mSelection = selection;
    }

    public String[] getSelectionArgs() {
        return mSelectionArgs;
    }

    public void setSelectionArgs(String[] selectionArgs) {
        mSelectionArgs = selectionArgs;
    }

    public String getSortOrder() {
        return mSortOrder;
    }

    public void setSortOrder(String sortOrder) {
        mSortOrder = sortOrder;
    }
}
