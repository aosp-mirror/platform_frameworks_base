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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentRoot;
import android.provider.DocumentsContract.Documents;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Objects;
import com.google.android.collect.Lists;

import java.util.List;

/**
 * Cache of known storage backends and their roots.
 */
public class RootsCache {

    // TODO: cache roots in local provider to avoid spinning up backends
    // TODO: root updates should trigger UI refresh

    private final Context mContext;

    public List<DocumentRoot> mRoots = Lists.newArrayList();

    private DocumentRoot mRecentsRoot;

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
            final DocumentRoot root = new DocumentRoot();
            root.rootType = DocumentRoot.ROOT_TYPE_SHORTCUT;
            root.docId = null;
            root.icon = R.drawable.ic_dir;
            root.title = mContext.getString(R.string.root_recent);
            root.summary = null;
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
                final ContentProviderClient client = resolver
                        .acquireUnstableContentProviderClient(info.authority);
                try {
                    final List<DocumentRoot> roots = DocumentsContract.getDocumentRoots(client);
                    for (DocumentRoot root : roots) {
                        root.authority = info.authority;
                    }
                    mRoots.addAll(roots);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load some roots from " + info.authority + ": " + e);
                } finally {
                    ContentProviderClient.closeQuietly(client);
                }
            }
        }
    }

    public DocumentRoot findRoot(Uri uri) {
        final String authority = uri.getAuthority();
        final String docId = DocumentsContract.getDocId(uri);
        for (DocumentRoot root : mRoots) {
            if (Objects.equal(root.authority, authority) && Objects.equal(root.docId, docId)) {
                return root;
            }
        }
        return null;
    }

    @GuardedBy("ActivityThread")
    public DocumentRoot getRecentsRoot() {
        return mRecentsRoot;
    }

    @GuardedBy("ActivityThread")
    public boolean isRecentsRoot(DocumentRoot root) {
        return mRecentsRoot == root;
    }

    @GuardedBy("ActivityThread")
    public List<DocumentRoot> getRoots() {
        return mRoots;
    }

    @GuardedBy("ActivityThread")
    public static Drawable resolveDocumentIcon(Context context, String mimeType) {
        if (Documents.MIME_TYPE_DIR.equals(mimeType)) {
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
