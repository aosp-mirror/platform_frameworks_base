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

import android.content.AsyncTaskLoader;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.util.Log;

import com.android.documentsui.DocumentsActivity.State;
import com.android.documentsui.model.RootInfo;
import com.google.android.collect.Maps;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractFuture;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class RecentLoader extends AsyncTaskLoader<DirectoryResult> {

    public static final int MAX_OUTSTANDING_RECENTS = 2;

    /**
     * Time to wait for first pass to complete before returning partial results.
     */
    public static final int MAX_FIRST_PASS_WAIT_MILLIS = 500;

    /**
     * Maximum documents from a single root.
     */
    public static final int MAX_DOCS_FROM_ROOT = 24;

    private static final ExecutorService sExecutor = buildExecutor();

    /**
     * Create a bounded thread pool for fetching recents; it creates threads as
     * needed (up to maximum) and reclaims them when finished.
     */
    private static ExecutorService buildExecutor() {
        // Create a bounded thread pool for fetching recents; it creates
        // threads as needed (up to maximum) and reclaims them when finished.
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                MAX_OUTSTANDING_RECENTS, MAX_OUTSTANDING_RECENTS, 10, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private final List<RootInfo> mRoots;
    private final String[] mAcceptMimes;

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

            final ContentResolver resolver = getContext().getContentResolver();
            final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                    authority);
            try {
                final Uri uri = DocumentsContract.buildRecentDocumentsUri(authority, rootId);
                final Cursor cursor = client.query(
                        uri, null, null, null, DirectoryLoader.getQuerySortOrder(mSortOrder));
                mWithRoot = new RootCursorWrapper(authority, rootId, cursor, MAX_DOCS_FROM_ROOT);
                set(mWithRoot);

                mFirstPassLatch.countDown();
                if (mFirstPassDone) {
                    onContentChanged();
                }

            } catch (Exception e) {
                setException(e);
            } finally {
                ContentProviderClient.closeQuietly(client);
            }
        }

        @Override
        public void close() throws IOException {
            IoUtils.closeQuietly(mWithRoot);
        }
    }

    public RecentLoader(Context context, List<RootInfo> roots, String[] acceptMimes) {
        super(context);
        mRoots = roots;
        mAcceptMimes = acceptMimes;
    }

    @Override
    public DirectoryResult loadInBackground() {
        if (mFirstPassLatch == null) {
            // First time through we kick off all the recent tasks, and wait
            // around to see if everyone finishes quickly.

            for (RootInfo root : mRoots) {
                if ((root.flags & Root.FLAG_SUPPORTS_RECENTS) != 0) {
                    final RecentTask task = new RecentTask(root.authority, root.rootId);
                    mTasks.put(root, task);
                }
            }

            mFirstPassLatch = new CountDownLatch(mTasks.size());
            for (RecentTask task : mTasks.values()) {
                sExecutor.execute(task);
            }

            try {
                mFirstPassLatch.await(MAX_FIRST_PASS_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                mFirstPassDone = true;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        // Collect all finished tasks
        List<Cursor> cursors = Lists.newArrayList();
        for (RecentTask task : mTasks.values()) {
            if (task.isDone()) {
                try {
                    final Cursor cursor = task.get();
                    final FilteringCursorWrapper filtered = new FilteringCursorWrapper(
                            cursor, mAcceptMimes) {
                        @Override
                        public void close() {
                            // Ignored, since we manage cursor lifecycle internally
                        }
                    };
                    cursors.add(filtered);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    Log.w(TAG, "Failed to load " + task.authority + ", " + task.rootId, e);
                }
            }
        }

        final DirectoryResult result = new DirectoryResult();
        result.sortOrder = SORT_ORDER_LAST_MODIFIED;

        if (cursors.size() > 0) {
            final MergeCursor merged = new MergeCursor(cursors.toArray(new Cursor[cursors.size()]));
            final SortingCursorWrapper sorted = new SortingCursorWrapper(merged, result.sortOrder);
            result.cursor = sorted;
        }
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
