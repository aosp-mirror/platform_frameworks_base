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

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Documents;
import android.util.Log;
import android.util.Pair;

import com.android.documentsui.model.Document;
import com.android.documentsui.model.DocumentsProviderInfo;
import com.android.documentsui.model.DocumentsProviderInfo.Icon;
import com.android.documentsui.model.Root;
import com.android.internal.annotations.GuardedBy;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * Cache of known storage backends and their roots.
 */
public class RootsCache {

    // TODO: cache roots in local provider to avoid spinning up backends
    // TODO: root updates should trigger UI refresh

    private final Context mContext;

    /** Map from authority to cached info */
    private HashMap<String, DocumentsProviderInfo> mProviders = Maps.newHashMap();
    /** Map from (authority+rootId) to cached info */
    private HashMap<Pair<String, String>, Root> mRoots = Maps.newHashMap();

    public ArrayList<Root> mRootsList = Lists.newArrayList();

    private Root mRecentsRoot;

    public RootsCache(Context context) {
        mContext = context;
        update();
    }

    /**
     * Gather roots from all known storage providers.
     */
    @GuardedBy("ActivityThread")
    public void update() {
        mProviders.clear();
        mRoots.clear();
        mRootsList.clear();

        {
            // Create special root for recents
            final Root root = Root.buildRecents(mContext);
            mRootsList.add(root);
            mRecentsRoot = root;
        }

        // Query for other storage backends
        final PackageManager pm = mContext.getPackageManager();
        final List<ProviderInfo> providers = pm.queryContentProviders(
                null, -1, PackageManager.GET_META_DATA);
        for (ProviderInfo providerInfo : providers) {
            if (providerInfo.metaData != null && providerInfo.metaData.containsKey(
                    DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {
                final DocumentsProviderInfo info = DocumentsProviderInfo.parseInfo(
                        mContext, providerInfo);
                if (info == null) {
                    Log.w(TAG, "Missing info for " + providerInfo);
                    continue;
                }

                mProviders.put(info.providerInfo.authority, info);

                try {
                    // TODO: remove deprecated customRoots flag
                    // TODO: populate roots on background thread, and cache results
                    final Uri uri = DocumentsContract.buildRootsUri(providerInfo.authority);
                    final Cursor cursor = mContext.getContentResolver()
                            .query(uri, null, null, null, null);
                    try {
                        while (cursor.moveToNext()) {
                            final Root root = Root.fromCursor(mContext, info, cursor);
                            mRoots.put(Pair.create(info.providerInfo.authority, root.rootId), root);
                            mRootsList.add(root);
                        }
                    } finally {
                        cursor.close();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load some roots from " + info.providerInfo.authority
                            + ": " + e);
                }
            }
        }
    }

    @GuardedBy("ActivityThread")
    public DocumentsProviderInfo findProvider(String authority) {
        return mProviders.get(authority);
    }

    @GuardedBy("ActivityThread")
    public Root findRoot(String authority, String rootId) {
        return mRoots.get(Pair.create(authority, rootId));
    }

    @GuardedBy("ActivityThread")
    public Root findRoot(Document doc) {
        final String authority = doc.uri.getAuthority();
        final String rootId = DocumentsContract.getRootId(doc.uri);
        return findRoot(authority, rootId);
    }

    @GuardedBy("ActivityThread")
    public Root getRecentsRoot() {
        return mRecentsRoot;
    }

    @GuardedBy("ActivityThread")
    public Collection<Root> getRoots() {
        return mRootsList;
    }

    @GuardedBy("ActivityThread")
    public Drawable resolveDocumentIcon(Context context, String authority, String mimeType) {
        // Custom icons take precedence
        final DocumentsProviderInfo info = mProviders.get(authority);
        if (info != null) {
            for (Icon icon : info.customIcons) {
                if (MimePredicate.mimeMatches(icon.mimeType, mimeType)) {
                    return icon.icon;
                }
            }
        }

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
