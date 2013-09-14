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
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.util.Log;

import com.android.documentsui.DocumentsActivity.State;
import com.android.documentsui.model.RootInfo;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Objects;
import com.google.android.collect.Lists;

import libcore.io.IoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Cache of known storage backends and their roots.
 */
public class RootsCache {

    // TODO: cache roots in local provider to avoid spinning up backends
    // TODO: root updates should trigger UI refresh

    private static final boolean RECENTS_ENABLED = true;

    private final Context mContext;

    public List<RootInfo> mRoots = Lists.newArrayList();

    private RootInfo mRecentsRoot;

    public RootsCache(Context context) {
        mContext = context;
        update();
    }

    /**
     * Gather roots from all known storage providers.
     */
    @GuardedBy("ActivityThread")
    public void update() {
        mRoots.clear();

        if (RECENTS_ENABLED) {
            // Create special root for recents
            final RootInfo root = new RootInfo();
            root.rootType = Root.ROOT_TYPE_SHORTCUT;
            root.icon = R.drawable.ic_root_recent;
            root.flags = Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE;
            root.title = mContext.getString(R.string.root_recent);
            root.availableBytes = -1;

            mRoots.add(root);
            mRecentsRoot = root;
        }

        // Query for other storage backends
        final ContentResolver resolver = mContext.getContentResolver();
        final PackageManager pm = mContext.getPackageManager();
        final List<ProviderInfo> providers = pm.queryContentProviders(
                null, -1, PackageManager.GET_META_DATA);
        for (ProviderInfo info : providers) {
            if (info.metaData != null && info.metaData.containsKey(
                    DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {

                // TODO: populate roots on background thread, and cache results
                final Uri rootsUri = DocumentsContract.buildRootsUri(info.authority);
                final ContentProviderClient client = resolver
                        .acquireUnstableContentProviderClient(info.authority);
                Cursor cursor = null;
                try {
                    cursor = client.query(rootsUri, null, null, null, null);
                    while (cursor.moveToNext()) {
                        final RootInfo root = RootInfo.fromRootsCursor(info.authority, cursor);
                        mRoots.add(root);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load some roots from " + info.authority + ": " + e);
                } finally {
                    IoUtils.closeQuietly(cursor);
                    ContentProviderClient.closeQuietly(client);
                }
            }
        }

        Log.d(TAG, "Update found " + mRoots.size() + " roots");
    }

    @Deprecated
    public RootInfo findRoot(Uri uri) {
        final String authority = uri.getAuthority();
        final String docId = DocumentsContract.getDocumentId(uri);
        for (RootInfo root : mRoots) {
            if (Objects.equal(root.authority, authority) && Objects.equal(root.documentId, docId)) {
                return root;
            }
        }
        return null;
    }

    @GuardedBy("ActivityThread")
    public RootInfo getRoot(String authority, String rootId) {
        for (RootInfo root : mRoots) {
            if (Objects.equal(root.authority, authority) && Objects.equal(root.rootId, rootId)) {
                return root;
            }
        }
        return null;
    }

    @GuardedBy("ActivityThread")
    public boolean isIconUnique(RootInfo root) {
        for (RootInfo test : mRoots) {
            if (Objects.equal(test.authority, root.authority)) {
                if (Objects.equal(test.rootId, root.rootId)) {
                    continue;
                }
                if (test.icon == root.icon) {
                    return false;
                }
            }
        }
        return true;
    }

    @GuardedBy("ActivityThread")
    public RootInfo getRecentsRoot() {
        return mRecentsRoot;
    }

    @GuardedBy("ActivityThread")
    public boolean isRecentsRoot(RootInfo root) {
        return mRecentsRoot == root;
    }

    @GuardedBy("ActivityThread")
    public List<RootInfo> getRoots() {
        return mRoots;
    }

    @GuardedBy("ActivityThread")
    public List<RootInfo> getMatchingRoots(State state) {
        return getMatchingRoots(mRoots, state);
    }

    public static List<RootInfo> getMatchingRoots(List<RootInfo> roots, State state) {
        ArrayList<RootInfo> matching = Lists.newArrayList();
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
