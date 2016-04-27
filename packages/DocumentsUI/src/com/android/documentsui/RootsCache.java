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

import android.content.BroadcastReceiver.PendingResult;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.model.RootInfo;
import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

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

    private static final String TAG = "RootsCache";

    private final Context mContext;
    private final ContentObserver mObserver;

    private final RootInfo mRecentsRoot;

    private final Object mLock = new Object();
    private final CountDownLatch mFirstLoad = new CountDownLatch(1);

    @GuardedBy("mLock")
    private boolean mFirstLoadDone;
    @GuardedBy("mLock")
    private PendingResult mBootCompletedResult;

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
            if (uri == null) {
                Log.w(TAG, "Received onChange event for null uri. Skipping.");
                return;
            }
            if (DEBUG) Log.d(TAG, "Updating roots due to change at " + uri);
            updateAuthorityAsync(uri.getAuthority());
        }
    }

    /**
     * Gather roots from all known storage providers.
     */
    public void updateAsync(boolean forceRefreshAll) {

        // NOTE: This method is called when the UI language changes.
        // For that reason we update our RecentsRoot to reflect
        // the current language.
        mRecentsRoot.title = mContext.getString(R.string.root_recent);

        // Nothing else about the root should ever change.
        assert(mRecentsRoot.authority == null);
        assert(mRecentsRoot.rootId == null);
        assert(mRecentsRoot.derivedIcon == R.drawable.ic_root_recent);
        assert(mRecentsRoot.derivedType == RootInfo.TYPE_RECENTS);
        assert(mRecentsRoot.flags == (Root.FLAG_LOCAL_ONLY
                | Root.FLAG_SUPPORTS_IS_CHILD
                | Root.FLAG_SUPPORTS_CREATE));
        assert(mRecentsRoot.availableBytes == -1);

        new UpdateTask(forceRefreshAll, null)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Gather roots from storage providers belonging to given package name.
     */
    public void updatePackageAsync(String packageName) {
        new UpdateTask(false, packageName).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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

    public void setBootCompletedResult(PendingResult result) {
        synchronized (mLock) {
            // Quickly check if we've already finished loading, otherwise hang
            // out until first pass is finished.
            if (mFirstLoadDone) {
                result.finish();
            } else {
                mBootCompletedResult = result;
            }
        }
    }

    /**
     * Block until the first {@link UpdateTask} pass has finished.
     *
     * @return {@code true} if cached roots is ready to roll, otherwise
     *         {@code false} if we timed out while waiting.
     */
    private boolean waitForFirstLoad() {
        boolean success = false;
        try {
            success = mFirstLoad.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        if (!success) {
            Log.w(TAG, "Timeout waiting for first update");
        }
        return success;
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
                mRoots.putAll(authority, loadRootsForAuthority(resolver, authority, true));
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
            mRoots.putAll(authority, loadRootsForAuthority(resolver, authority, true));
            mStoppedAuthorities.remove(authority);
        }
    }

    private class UpdateTask extends AsyncTask<Void, Void, Void> {
        private final boolean mForceRefreshAll;
        private final String mForceRefreshPackage;

        private final Multimap<String, RootInfo> mTaskRoots = ArrayListMultimap.create();
        private final HashSet<String> mTaskStoppedAuthorities = new HashSet<>();

        /**
         * Create task to update roots cache.
         *
         * @param forceRefreshAll when true, all previously cached values for
         *            all packages should be ignored.
         * @param forceRefreshPackage when non-null, all previously cached
         *            values for this specific package should be ignored.
         */
        public UpdateTask(boolean forceRefreshAll, String forceRefreshPackage) {
            mForceRefreshAll = forceRefreshAll;
            mForceRefreshPackage = forceRefreshPackage;
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
            if (DEBUG)
                Log.d(TAG, "Update found " + mTaskRoots.size() + " roots in " + delta + "ms");
            synchronized (mLock) {
                mFirstLoadDone = true;
                if (mBootCompletedResult != null) {
                    mBootCompletedResult.finish();
                    mBootCompletedResult = null;
                }
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
                if (DEBUG) Log.d(TAG, "Ignoring stopped authority " + info.authority);
                mTaskStoppedAuthorities.add(info.authority);
                return;
            }

            final boolean forceRefresh = mForceRefreshAll
                    || Objects.equals(info.packageName, mForceRefreshPackage);
            mTaskRoots.putAll(info.authority, loadRootsForAuthority(mContext.getContentResolver(),
                    info.authority, forceRefresh));
        }
    }

    /**
     * Bring up requested provider and query for all active roots.
     */
    private Collection<RootInfo> loadRootsForAuthority(ContentResolver resolver, String authority,
            boolean forceRefresh) {
        if (DEBUG) Log.d(TAG, "Loading roots for " + authority);

        synchronized (mObservedAuthorities) {
            if (mObservedAuthorities.add(authority)) {
                // Watch for any future updates
                final Uri rootsUri = DocumentsContract.buildRootsUri(authority);
                mContext.getContentResolver().registerContentObserver(rootsUri, true, mObserver);
            }
        }

        final Uri rootsUri = DocumentsContract.buildRootsUri(authority);
        if (!forceRefresh) {
            // Look for roots data that we might have cached for ourselves in the
            // long-lived system process.
            final Bundle systemCache = resolver.getCache(rootsUri);
            if (systemCache != null) {
                if (DEBUG) Log.d(TAG, "System cache hit for " + authority);
                return systemCache.getParcelableArrayList(TAG);
            }
        }

        final ArrayList<RootInfo> roots = new ArrayList<>();
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

        // Cache these freshly parsed roots over in the long-lived system
        // process, in case our process goes away. The system takes care of
        // invalidating the cache if the package or Uri changes.
        final Bundle systemCache = new Bundle();
        systemCache.putParcelableArrayList(TAG, roots);
        resolver.putCache(rootsUri, systemCache);

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
                mRoots.putAll(authority,
                        loadRootsForAuthority(mContext.getContentResolver(), authority, false));
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

    /**
     * Returns the default root for the specified state.
     */
    public RootInfo getDefaultRootBlocking(State state) {
        for (RootInfo root : getMatchingRoots(getRootsBlocking(), state)) {
            if (root.isDownloads()) {
                return root;
            }
        }
        return mRecentsRoot;
    }

    @VisibleForTesting
    static List<RootInfo> getMatchingRoots(Collection<RootInfo> roots, State state) {
        final List<RootInfo> matching = new ArrayList<>();
        for (RootInfo root : roots) {

            if (DEBUG) Log.d(TAG, "Evaluating " + root);

            if (state.action == State.ACTION_CREATE && !root.supportsCreate()) {
                if (DEBUG) Log.d(TAG, "Excluding read-only root because: ACTION_CREATE.");
                continue;
            }

            if (state.action == State.ACTION_PICK_COPY_DESTINATION
                    && !root.supportsCreate()) {
                if (DEBUG) Log.d(
                        TAG, "Excluding read-only root because: ACTION_PICK_COPY_DESTINATION.");
                continue;
            }

            if (state.action == State.ACTION_OPEN_TREE && !root.supportsChildren()) {
                if (DEBUG) Log.d(
                        TAG, "Excluding root !supportsChildren because: ACTION_OPEN_TREE.");
                continue;
            }

            if (!state.showAdvanced && root.isAdvanced()) {
                if (DEBUG) Log.d(TAG, "Excluding root because: unwanted advanced device.");
                continue;
            }

            if (state.localOnly && !root.isLocalOnly()) {
                if (DEBUG) Log.d(TAG, "Excluding root because: unwanted non-local device.");
                continue;
            }

            if (state.directoryCopy && root.isDownloads()) {
                if (DEBUG) Log.d(
                        TAG, "Excluding downloads root because: unsupported directory copy.");
                continue;
            }

            if (state.action == State.ACTION_OPEN && root.isEmpty()) {
                if (DEBUG) Log.d(TAG, "Excluding empty root because: ACTION_OPEN.");
                continue;
            }

            if (state.action == State.ACTION_GET_CONTENT && root.isEmpty()) {
                if (DEBUG) Log.d(TAG, "Excluding empty root because: ACTION_GET_CONTENT.");
                continue;
            }

            final boolean overlap =
                    MimePredicate.mimeMatches(root.derivedMimeTypes, state.acceptMimes) ||
                    MimePredicate.mimeMatches(state.acceptMimes, root.derivedMimeTypes);
            if (!overlap) {
                if (DEBUG) Log.d(
                        TAG, "Excluding root because: unsupported content types > "
                        + state.acceptMimes);
                continue;
            }

            if (state.excludedAuthorities.contains(root.authority)) {
                if (DEBUG) Log.d(TAG, "Excluding root because: owned by calling package.");
                continue;
            }

            if (DEBUG) Log.d(TAG, "Including " + root);
            matching.add(root);
        }
        return matching;
    }
}
