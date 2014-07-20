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

import static com.android.documentsui.DocumentsActivity.TAG;
import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_LAST_MODIFIED;

import android.app.ActivityManager;
import android.content.AsyncTaskLoader;
import android.content.ContentProviderClient;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.documentsui.DocumentsActivity.State;
import com.android.documentsui.model.RootInfo;
import com.google.android.collect.Maps;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractFuture;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class RecentLoader extends AsyncTaskLoader<DirectoryResult> {
    private static final boolean LOGD = true;

    // TODO: clean up cursor ownership so background thread doesn't traverse
    // previously returned cursors for filtering/sorting; this currently races
    // with the UI thread.

    private static final int MAX_OUTSTANDING_RECENTS = 4;
    private static final int MAX_OUTSTANDING_RECENTS_SVELTE = 2;

    /**
     * Time to wait for first pass to complete before returning partial results.
     */
    private static final int MAX_FIRST_PASS_WAIT_MILLIS = 500;

    /** Maximum documents from a single root. */
    private static final int MAX_DOCS_FROM_ROOT = 64;

    /** Ignore documents older than this age. */
    private static final long REJECT_OLDER_THAN = 45 * DateUtils.DAY_IN_MILLIS;

    /** MIME types that should always be excluded from recents. */
    private static final String[] RECENT_REJECT_MIMES = new String[] { Document.MIME_TYPE_DIR };

    private final Semaphore mQueryPermits;

    private final RootsCache mRoots;
    private final State mState;

    private final HashMap<RootInfo, RecentTask> mTasks = Maps.newHashMap();

    private final int mSortOrder = State.SORT_ORDER_LAST_MODIFIED;

    private CountDownLatch mFirstPassLatch;
    private volatile boolean mFirstPassDone;

    private DirectoryResult mResult;

    // TODO: create better transfer of ownership around cursor to ensure its
    // closed in all edge cases.

    public class RecentTask extends AbstractFuture<Cursor> implements Runnable, Closeable {
        public final String authority;
        public final String rootId;

        private Cursor mWithRoot;

        public RecentTask(String authority, String rootId) {
            this.authority = authority;
            this.rootId = rootId;
        }

        @Override
        public void run() {
            if (isCancelled()) return;

            try {
                mQueryPermits.acquire();
            } catch (InterruptedException e) {
                return;
            }

            try {
                runInternal();
            } finally {
                mQueryPermits.release();
            }
        }

        public void runInternal() {
            ContentProviderClient client = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        getContext().getContentResolver(), authority);

                final Uri uri = DocumentsContract.buildRecentDocumentsUri(authority, rootId);
                final Cursor cursor = client.query(
                        uri, null, null, null, DirectoryLoader.getQuerySortOrder(mSortOrder));
                mWithRoot = new RootCursorWrapper(authority, rootId, cursor, MAX_DOCS_FROM_ROOT);

            } catch (RemoteException e) {
                Log.w(TAG, "Failed to load " + authority + ", " + rootId, e);
            } catch (UnsupportedOperationException e) {
                Log.w(TAG, "Recents not supported " + authority + ", " + rootId, e);
            } finally {
                ContentProviderClient.releaseQuietly(client);
            }

            set(mWithRoot);

            mFirstPassLatch.countDown();
            if (mFirstPassDone) {
                onContentChanged();
            }
        }

        @Override
        public void close() throws IOException {
            IoUtils.closeQuietly(mWithRoot);
        }
    }

    public RecentLoader(Context context, RootsCache roots, State state) {
        super(context);
        mRoots = roots;
        mState = state;

        // Keep clients around on high-RAM devices, since we'd be spinning them
        // up moments later to fetch thumbnails anyway.
        final ActivityManager am = (ActivityManager) getContext().getSystemService(
                Context.ACTIVITY_SERVICE);
        mQueryPermits = new Semaphore(
                am.isLowRamDevice() ? MAX_OUTSTANDING_RECENTS_SVELTE : MAX_OUTSTANDING_RECENTS);
    }

    @Override
    public DirectoryResult loadInBackground() {
        if (mFirstPassLatch == null) {
            // First time through we kick off all the recent tasks, and wait
            // around to see if everyone finishes quickly.

            final Collection<RootInfo> roots = mRoots.getMatchingRootsBlocking(mState);
            for (RootInfo root : roots) {
                if ((root.flags & Root.FLAG_SUPPORTS_RECENTS) != 0) {
                    final RecentTask task = new RecentTask(root.authority, root.rootId);
                    mTasks.put(root, task);
                }
            }

            mFirstPassLatch = new CountDownLatch(mTasks.size());
            for (RecentTask task : mTasks.values()) {
                ProviderExecutor.forAuthority(task.authority).execute(task);
            }

            try {
                mFirstPassLatch.await(MAX_FIRST_PASS_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                mFirstPassDone = true;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        final long rejectBefore = System.currentTimeMillis() - REJECT_OLDER_THAN;

        // Collect all finished tasks
        boolean allDone = true;
        List<Cursor> cursors = Lists.newArrayList();
        for (RecentTask task : mTasks.values()) {
            if (task.isDone()) {
                try {
                    final Cursor cursor = task.get();
                    if (cursor == null) continue;

                    final FilteringCursorWrapper filtered = new FilteringCursorWrapper(
                            cursor, mState.acceptMimes, RECENT_REJECT_MIMES, rejectBefore) {
                        @Override
                        public void close() {
                            // Ignored, since we manage cursor lifecycle internally
                        }
                    };
                    cursors.add(filtered);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    // We already logged on other side
                }
            } else {
                allDone = false;
            }
        }

        if (LOGD) {
            Log.d(TAG, "Found " + cursors.size() + " of " + mTasks.size() + " recent queries done");
        }

        final DirectoryResult result = new DirectoryResult();
        result.sortOrder = SORT_ORDER_LAST_MODIFIED;

        // Hint to UI if we're still loading
        final Bundle extras = new Bundle();
        if (!allDone) {
            extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
        }

        final Cursor merged;
        if (cursors.size() > 0) {
            merged = new MergeCursor(cursors.toArray(new Cursor[cursors.size()]));
        } else {
            // Return something when nobody is ready
            merged = new MatrixCursor(new String[0]);
        }

        final SortingCursorWrapper sorted = new SortingCursorWrapper(merged, result.sortOrder) {
            @Override
            public Bundle getExtras() {
                return extras;
            }
        };

        result.cursor = sorted;

        return result;
    }

    @Override
    public void cancelLoadInBackground() {
        super.cancelLoadInBackground();
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

        for (RecentTask task : mTasks.values()) {
            IoUtils.closeQuietly(task);
        }

        IoUtils.closeQuietly(mResult);
        mResult = null;
    }
}
