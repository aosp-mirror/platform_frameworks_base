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

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.Shared.TAG;
import static com.android.internal.util.Preconditions.checkState;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.model.RootInfo;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import libcore.io.IoUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Cache of known storage backends and their roots.
 */
public class RootsCache {
    public static final Uri sNotificationUri = Uri.parse(
            "content://com.android.documentsui.roots/");

    private final Context mContext;
    private final ContentObserver mObserver;
    private OnCacheUpdateListener mCacheUpdateListener;

    private final RootInfo mRecentsRoot;

    private final Object mLock = new Object();
    private final CountDownLatch mFirstLoad = new CountDownLatch(1);

    @GuardedBy("mLock")
    private Multimap<String, RootInfo> mRoots = ArrayListMultimap.create();
    @GuardedBy("mLock")
    private HashSet<String> mStoppedAuthorities = new HashSet<>();

    @GuardedBy("mObservedAuthorities")
    private final HashSet<String> mObservedAuthorities = new HashSet<>();

    public RootsCache(Context context) {
        mContext = context;
        mObserver = new RootsChangedObserver();

        // Create a new anonymous "Recents" RootInfo. It's a faker.
        mRecentsRoot = new RootInfo() {{
                // Special root for recents
                derivedIcon = R.drawable.ic_root_recent;
                derivedType = RootInfo.TYPE_RECENTS;
                flags = Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD
                        | Root.FLAG_SUPPORTS_CREATE;
                title = mContext.getString(R.string.root_recent);
                availableBytes = -1;
            }};
    }

    private class RootsChangedObserver extends ContentObserver {
        public RootsChangedObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (DEBUG) Log.d(TAG, "Updating roots due to change at " + uri);
            updateAuthorityAsync(uri.getAuthority());
        }
    }

    static interface OnCacheUpdateListener {
        void onCacheUpdate();
    }

    /**
     * Gather roots from all known storage providers.
     */
    public void updateAsync() {
        // Verifying an assumption about the recents root being immutable.
        if (DEBUG) {
            checkState(mRecentsRoot.authority == null);
            checkState(mRecentsRoot.rootId == null);
            checkState(mRecentsRoot.derivedIcon == R.drawable.ic_root_recent);
            checkState(mRecentsRoot.derivedType == RootInfo.TYPE_RECENTS);
            checkState(mRecentsRoot.flags == (Root.FLAG_LOCAL_ONLY
                    | Root.FLAG_SUPPORTS_IS_CHILD
                    | Root.FLAG_SUPPORTS_CREATE));
            checkState(mRecentsRoot.title == mContext.getString(R.string.root_recent));
            checkState(mRecentsRoot.availableBytes == -1);
        }
        new UpdateTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Gather roots from storage providers belonging to given package name.
     */
    public void updatePackageAsync(String packageName) {
        new UpdateTask(packageName).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Gather roots from storage providers belonging to given authority.
     */
    public void updateAuthorityAsync(String authority) {
        final ProviderInfo info = mContext.getPackageManager().resolveContentProvider(authority, 0);
        if (info != null) {
            updatePackageAsync(info.packageName);
        }
    }

    private void waitForFirstLoad() {
        boolean success = false;
        try {
            success = mFirstLoad.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        if (!success) {
            Log.w(TAG, "Timeout waiting for first update");
        }
    }

    /**
     * Load roots from authorities that are in stopped state. Normal
     * {@link UpdateTask} passes ignore stopped applications.
     */
    private void loadStoppedAuthorities() {
        final ContentResolver resolver = mContext.getContentResolver();
        synchronized (mLock) {
            for (String authority : mStoppedAuthorities) {
                if (DEBUG) Log.d(TAG, "Loading stopped authority " + authority);
                mRoots.putAll(authority, loadRootsForAuthority(resolver, authority));
            }
            mStoppedAuthorities.clear();
        }
    }

    /**
     * Load roots from a stopped authority. Normal {@link UpdateTask} passes
     * ignore stopped applications.
     */
    private void loadStoppedAuthority(String authority) {
        final ContentResolver resolver = mContext.getContentResolver();
        synchronized (mLock) {
            if (!mStoppedAuthorities.contains(authority)) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Loading stopped authority " + authority);
            }
            mRoots.putAll(authority, loadRootsForAuthority(resolver, authority));
            mStoppedAuthorities.remove(authority);
        }
    }

    private class UpdateTask extends AsyncTask<Void, Void, Void> {
        private final String mFilterPackage;

        private final Multimap<String, RootInfo> mTaskRoots = ArrayListMultimap.create();
        private final HashSet<String> mTaskStoppedAuthorities = new HashSet<>();

        /**
         * Update all roots.
         */
        public UpdateTask() {
            this(null);
        }

        /**
         * Only update roots belonging to given package name. Other roots will
         * be copied from cached {@link #mRoots} values.
         */
        public UpdateTask(String filterPackage) {
            mFilterPackage = filterPackage;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final long start = SystemClock.elapsedRealtime();

            if (mFilterPackage != null) {
                // Need at least first load, since we're going to be using
                // previously cached values for non-matching packages.
                waitForFirstLoad();
            }

            mTaskRoots.put(mRecentsRoot.authority, mRecentsRoot);

            final ContentResolver resolver = mContext.getContentResolver();
            final PackageManager pm = mContext.getPackageManager();

            // Pick up provider with action string
            final Intent intent = new Intent(DocumentsContract.PROVIDER_INTERFACE);
            final List<ResolveInfo> providers = pm.queryIntentContentProviders(intent, 0);
            for (ResolveInfo info : providers) {
                handleDocumentsProvider(info.providerInfo);
            }

            final long delta = SystemClock.elapsedRealtime() - start;
            if (DEBUG)
                Log.d(TAG, "Update found " + mTaskRoots.size() + " roots in " + delta + "ms");
            synchronized (mLock) {
                mRoots = mTaskRoots;
                mStoppedAuthorities = mTaskStoppedAuthorities;
            }
            mFirstLoad.countDown();
            resolver.notifyChange(sNotificationUri, null, false);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (mCacheUpdateListener != null) {
                mCacheUpdateListener.onCacheUpdate();
            }
        }

        private void handleDocumentsProvider(ProviderInfo info) {
            // Ignore stopped packages for now; we might query them
            // later during UI interaction.
            if ((info.applicationInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
                if (DEBUG) Log.d(TAG, "Ignoring stopped authority " + info.authority);
                mTaskStoppedAuthorities.add(info.authority);
                return;
            }

            // Try using cached roots if filtering
            boolean cacheHit = false;
            if (mFilterPackage != null && !mFilterPackage.equals(info.packageName)) {
                synchronized (mLock) {
                    if (mTaskRoots.putAll(info.authority, mRoots.get(info.authority))) {
                        if (DEBUG) Log.d(TAG, "Used cached roots for " + info.authority);
                        cacheHit = true;
                    }
                }
            }

            // Cache miss, or loading everything
            if (!cacheHit) {
                mTaskRoots.putAll(info.authority,
                        loadRootsForAuthority(mContext.getContentResolver(), info.authority));
            }
        }
    }

    /**
     * Bring up requested provider and query for all active roots.
     */
    private Collection<RootInfo> loadRootsForAuthority(ContentResolver resolver, String authority) {
        if (DEBUG) Log.d(TAG, "Loading roots for " + authority);

        synchronized (mObservedAuthorities) {
            if (mObservedAuthorities.add(authority)) {
                // Watch for any future updates
                final Uri rootsUri = DocumentsContract.buildRootsUri(authority);
                mContext.getContentResolver().registerContentObserver(rootsUri, true, mObserver);
            }
        }

        final List<RootInfo> roots = new ArrayList<>();
        final Uri rootsUri = DocumentsContract.buildRootsUri(authority);

        ContentProviderClient client = null;
        Cursor cursor = null;
        try {
            client = DocumentsApplication.acquireUnstableProviderOrThrow(resolver, authority);
            cursor = client.query(rootsUri, null, null, null, null);
            while (cursor.moveToNext()) {
                final RootInfo root = RootInfo.fromRootsCursor(authority, cursor);
                roots.add(root);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load some roots from " + authority + ": " + e);
        } finally {
            IoUtils.closeQuietly(cursor);
            ContentProviderClient.releaseQuietly(client);
        }
        return roots;
    }

    /**
     * Return the requested {@link RootInfo}, but only loading the roots for the
     * requested authority. This is useful when we want to load fast without
     * waiting for all the other roots to come back.
     */
    public RootInfo getRootOneshot(String authority, String rootId) {
        synchronized (mLock) {
            RootInfo root = getRootLocked(authority, rootId);
            if (root == null) {
                mRoots.putAll(
                        authority, loadRootsForAuthority(mContext.getContentResolver(), authority));
                root = getRootLocked(authority, rootId);
            }
            return root;
        }
    }

    public RootInfo getRootBlocking(String authority, String rootId) {
        waitForFirstLoad();
        loadStoppedAuthorities();
        synchronized (mLock) {
            return getRootLocked(authority, rootId);
        }
    }

    private RootInfo getRootLocked(String authority, String rootId) {
        for (RootInfo root : mRoots.get(authority)) {
            if (Objects.equals(root.rootId, rootId)) {
                return root;
            }
        }
        return null;
    }

    public boolean isIconUniqueBlocking(RootInfo root) {
        waitForFirstLoad();
        loadStoppedAuthorities();
        synchronized (mLock) {
            final int rootIcon = root.derivedIcon != 0 ? root.derivedIcon : root.icon;
            for (RootInfo test : mRoots.get(root.authority)) {
                if (Objects.equals(test.rootId, root.rootId)) {
                    continue;
                }
                final int testIcon = test.derivedIcon != 0 ? test.derivedIcon : test.icon;
                if (testIcon == rootIcon) {
                    return false;
                }
            }
            return true;
        }
    }

    public RootInfo getRecentsRoot() {
        return mRecentsRoot;
    }

    public boolean isRecentsRoot(RootInfo root) {
        return mRecentsRoot.equals(root);
    }

    public Collection<RootInfo> getRootsBlocking() {
        waitForFirstLoad();
        loadStoppedAuthorities();
        synchronized (mLock) {
            return mRoots.values();
        }
    }

    public Collection<RootInfo> getMatchingRootsBlocking(State state) {
        waitForFirstLoad();
        loadStoppedAuthorities();
        synchronized (mLock) {
            return getMatchingRoots(mRoots.values(), state);
        }
    }

    /**
     * Returns a list of roots for the specified authority. If not found, then
     * an empty list is returned.
     */
    public Collection<RootInfo> getRootsForAuthorityBlocking(String authority) {
        waitForFirstLoad();
        loadStoppedAuthority(authority);
        synchronized (mLock) {
            final Collection<RootInfo> roots = mRoots.get(authority);
            return roots != null ? roots : Collections.<RootInfo>emptyList();
        }
    }

    public void setOnCacheUpdateListener(OnCacheUpdateListener cacheUpdateListener) {
        mCacheUpdateListener = cacheUpdateListener;
    }

    @VisibleForTesting
    static List<RootInfo> getMatchingRoots(Collection<RootInfo> roots, State state) {
        final List<RootInfo> matching = new ArrayList<>();
        for (RootInfo root : roots) {
            // Exclude read-only devices when creating
            if (state.action == State.ACTION_CREATE && !root.supportsCreate()) continue;
            if (state.action == State.ACTION_PICK_COPY_DESTINATION
                    && !root.supportsCreate()) continue;
            // Exclude roots that don't support directory picking
            if (state.action == State.ACTION_OPEN_TREE && !root.supportsChildren()) continue;
            // Exclude advanced devices when not requested
            if (!state.showAdvanced && root.isAdvanced()) continue;
            // Exclude non-local devices when local only
            if (state.localOnly && !root.isLocalOnly()) continue;
            // Exclude downloads roots as it doesn't support directory creation (actually
            // we just don't show them).
            // TODO: Add flag to check the root supports directory creation.
            if (state.directoryCopy && !root.isDownloads()) continue;

            // Only show empty roots when creating, or in browse mode.
            if (root.isEmpty() && (state.action == State.ACTION_OPEN
                    || state.action == State.ACTION_GET_CONTENT)) {
                if (DEBUG) Log.i(TAG, "Skipping empty root: " + root);
                continue;
            }

            // Only include roots that serve requested content
            final boolean overlap =
                    MimePredicate.mimeMatches(root.derivedMimeTypes, state.acceptMimes) ||
                    MimePredicate.mimeMatches(state.acceptMimes, root.derivedMimeTypes);
            if (!overlap) {
                continue;
            }

            // Exclude roots from the calling package.
            if (state.excludedAuthorities.contains(root.authority)) {
                if (DEBUG) Log.d(
                        TAG, "Excluding root " + root.authority + " from calling package.");
                continue;
            }

            if (DEBUG) Log.d(
                    TAG, "Including root " + root + " in roots list.");
            matching.add(root);
        }
        return matching;
    }
}
