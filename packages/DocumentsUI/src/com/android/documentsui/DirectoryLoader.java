/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_DISPLAY_NAME;
import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_LAST_MODIFIED;
import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_SIZE;

import android.content.AsyncTaskLoader;
import android.content.ContentProviderClient;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract.Document;

import libcore.io.IoUtils;

class DirectoryResult implements AutoCloseable {
    ContentProviderClient client;
    Cursor cursor;
    Exception exception;

    @Override
    public void close() {
        IoUtils.closeQuietly(cursor);
        ContentProviderClient.closeQuietly(client);
        cursor = null;
        client = null;
    }
}

public class DirectoryLoader extends AsyncTaskLoader<DirectoryResult> {
    private final ForceLoadContentObserver mObserver = new ForceLoadContentObserver();

    private final String mRootId;
    private final Uri mUri;
    private final int mSortOrder;

    private CancellationSignal mSignal;
    private DirectoryResult mResult;

    public DirectoryLoader(Context context, String rootId, Uri uri, int sortOrder) {
        super(context);
        mRootId = rootId;
        mUri = uri;
        mSortOrder = sortOrder;
    }

    @Override
    public final DirectoryResult loadInBackground() {
        synchronized (this) {
            if (isLoadInBackgroundCanceled()) {
                throw new OperationCanceledException();
            }
            mSignal = new CancellationSignal();
        }
        final DirectoryResult result = new DirectoryResult();
        final String authority = mUri.getAuthority();
        try {
            result.client = getContext()
                    .getContentResolver().acquireUnstableContentProviderClient(authority);
            final Cursor cursor = result.client.query(
                    mUri, null, null, null, getQuerySortOrder(mSortOrder), mSignal);
            final Cursor withRoot = new RootCursorWrapper(mUri.getAuthority(), mRootId, cursor, -1);
            final Cursor sorted = new SortingCursorWrapper(withRoot, mSortOrder);

            result.cursor = sorted;
            result.cursor.registerContentObserver(mObserver);
        } catch (Exception e) {
            result.exception = e;
            ContentProviderClient.closeQuietly(result.client);
        } finally {
            synchronized (this) {
                mSignal = null;
            }
        }
        return result;
    }

    @Override
    public void cancelLoadInBackground() {
        super.cancelLoadInBackground();

        synchronized (this) {
            if (mSignal != null) {
                mSignal.cancel();
            }
        }
    }

    @Override
    public void deliverResult(DirectoryResult result) {
        if (isReset()) {
            IoUtils.closeQuietly(result);
            return;
        }
        DirectoryResult oldResult = mResult;
        mResult = result;

        if (isStarted()) {
            super.deliverResult(result);
        }

        if (oldResult != null && oldResult != result) {
            IoUtils.closeQuietly(oldResult);
        }
    }

    @Override
    protected void onStartLoading() {
        if (mResult != null) {
            deliverResult(mResult);
        }
        if (takeContentChanged() || mResult == null) {
            forceLoad();
        }
    }

    @Override
    protected void onStopLoading() {
        cancelLoad();
    }

    @Override
    public void onCanceled(DirectoryResult result) {
        IoUtils.closeQuietly(result);
    }

    @Override
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();

        IoUtils.closeQuietly(mResult);
        mResult = null;

        getContext().getContentResolver().unregisterContentObserver(mObserver);
    }

    public static String getQuerySortOrder(int sortOrder) {
        switch (sortOrder) {
            case SORT_ORDER_DISPLAY_NAME:
                return Document.COLUMN_DISPLAY_NAME + " ASC";
            case SORT_ORDER_LAST_MODIFIED:
                return Document.COLUMN_LAST_MODIFIED + " DESC";
            case SORT_ORDER_SIZE:
                return Document.COLUMN_SIZE + " DESC";
            default:
                return null;
        }
    }
}
