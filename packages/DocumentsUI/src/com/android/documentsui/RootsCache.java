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
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.util.Log;

import com.android.documentsui.model.RootInfo;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Objects;
import com.google.android.collect.Lists;

import libcore.io.IoUtils;

import java.util.List;

/**
 * Cache of known storage backends and their roots.
 */
public class RootsCache {

    // TODO: cache roots in local provider to avoid spinning up backends
    // TODO: root updates should trigger UI refresh

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

        {
            // Create special root for recents
            final RootInfo root = new RootInfo();
            root.rootType = Root.ROOT_TYPE_SHORTCUT;
            root.icon = R.drawable.ic_dir;
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

                // TODO: remove deprecated customRoots flag
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
    public static Drawable resolveDocumentIcon(Context context, String mimeType) {
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            return context.getResources().getDrawable(R.drawable.ic_dir);
        } else {
            final PackageManager pm = context.getPackageManager();
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setType(mimeType);

            final ResolveInfo activityInfo = pm.resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (activityInfo != null) {
                return activityInfo.loadIcon(pm);
            } else {
                return null;
            }
        }
    }
}
