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
import android.util.Log;

import com.android.documentsui.DocumentsActivity.State;
import com.android.documentsui.model.RootInfo;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Objects;
import com.google.android.collect.Lists;
import com.google.android.collect.Sets;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import libcore.io.IoUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Cache of known storage backends and their roots.
 */
public class RootsCache {
    private static final boolean LOGD = true;

    public static final Uri sNotificationUri = Uri.parse(
            "content://com.android.documentsui.roots/");

    private final Context mContext;
    private final ContentObserver mObserver;

    private final RootInfo mRecentsRoot = new RootInfo();

    private final Object mLock = new Object();
    private final CountDownLatch mFirstLoad = new CountDownLatch(1);

    @GuardedBy("mLock")
    private Multimap<String, RootInfo> mRoots = ArrayListMultimap.create();
    @GuardedBy("mLock")
    private HashSet<String> mStoppedAuthorities = Sets.newHashSet();

    @GuardedBy("mObservedAuthorities")
    private final HashSet<String> mObservedAuthorities = Sets.newHashSet();

    public RootsCache(Context context) {
        mContext = context;
        mObserver = new RootsChangedObserver();
    }

    private class RootsChangedObserver extends ContentObserver {
        public RootsChangedObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (LOGD) Log.d(TAG, "Updating roots due to change at " + uri);
            updateAuthorityAsync(uri.getAuthority());
        }
    }

    /**
     * Gather roots from all known storage providers.
     */
    public void updateAsync() {
        // Special root for recents
        mRecentsRoot.authority = null;
        mRecentsRoot.rootId = null;
        mRecentsRoot.icon = R.drawable.ic_root_recent;
        mRecentsRoot.flags = Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE;
        mRecentsRoot.title = mContext.getString(R.string.root_recent);
        mRecentsRoot.availableBytes = -1;

        new UpdateTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Gather roots from storage providers belonging to given package name.
     */
    public void updatePackageAsync(String packageName) {
        // Need at least first load, since we're going to be using previously
        // cached values for non-matching packages.
        waitForFirstLoad();
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
                if (LOGD) Log.d(TAG, "Loading stopped authority " + authority);
                mRoots.putAll(authority, loadRootsForAuthority(resolver, authority));
            }
            mStoppedAuthorities.clear();
        }
    }

    private class UpdateTask extends AsyncTask<Void, Void, Void> {
        private final String mFilterPackage;

        private final Multimap<String, RootInfo> mTaskRoots = ArrayListMultimap.create();
        private final HashSet<String> mTaskStoppedAuthorities = Sets.newHashSet();

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
            Log.d(TAG, "Update found " + mTaskRoots.size() + " roots in " + delta + "ms");
            synchronized (mLock) {
                mRoots = mTaskRoots;
                mStoppedAuthorities = mTaskStoppedAuthorities;
            }
            mFirstLoad.countDown();
            resolver.notifyChange(sNotificationUri, null, false);
            return null;
        }

        private void handleDocumentsProvider(ProviderInfo info) {
            // Ignore stopped packages for now; we might query them
            // later during UI interaction.
            if ((info.applicationInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
                if (LOGD) Log.d(TAG, "Ignoring stopped authority " + info.authority);
                mTaskStoppedAuthorities.add(info.authority);
                return;
            }

            // Try using cached roots if filtering
            boolean cacheHit = false;
            if (mFilterPackage != null && !mFilterPackage.equals(info.packageName)) {
                synchronized (mLock) {
                    if (mTaskRoots.putAll(info.authority, mRoots.get(info.authority))) {
                        if (LOGD) Log.d(TAG, "Used cached roots for " + info.authority);
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
        if (LOGD) Log.d(TAG, "Loading roots for " + authority);

        synchronized (mObservedAuthorities) {
            if (mObservedAuthorities.add(authority)) {
                // Watch for any future updates
                final Uri rootsUri = DocumentsContract.buildRootsUri(authority);
                mContext.getContentResolver().registerContentObserver(rootsUri, true, mObserver);
            }
        }

        final List<RootInfo> roots = Lists.newArrayList();
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
            if (Objects.equal(root.rootId, rootId)) {
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
                if (Objects.equal(test.rootId, root.rootId)) {
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
        return mRecentsRoot == root;
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

    @VisibleForTesting
    static List<RootInfo> getMatchingRoots(Collection<RootInfo> roots, State state) {
        final List<RootInfo> matching = Lists.newArrayList();
        for (RootInfo root : roots) {
            final boolean supportsCreate = (root.flags & Root.FLAG_SUPPORTS_CREATE) != 0;
            final boolean advanced = (root.flags & Root.FLAG_ADVANCED) != 0;
            final boolean localOnly = (root.flags & Root.FLAG_LOCAL_ONLY) != 0;
            final boolean empty = (root.flags & Root.FLAG_EMPTY) != 0;

            // Exclude read-only devices when creating
            if (state.action == State.ACTION_CREATE && !supportsCreate) continue;
            // Exclude advanced devices when not requested
            if (!state.showAdvanced && advanced) continue;
            // Exclude non-local devices when local only
            if (state.localOnly && !localOnly) continue;
            // Only show empty roots when creating
            if (state.action != State.ACTION_CREATE && empty) continue;

            // Only include roots that serve requested content
            final boolean overlap =
                    MimePredicate.mimeMatches(root.derivedMimeTypes, state.acceptMimes) ||
                    MimePredicate.mimeMatches(state.acceptMimes, root.derivedMimeTypes);
            if (!overlap) {
                continue;
            }

            matching.add(root);
        }
        return matching;
    }
}
