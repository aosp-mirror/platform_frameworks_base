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

    private static boolean sCached = false;

    /** Map from authority to cached info */
    private static HashMap<String, DocumentsProviderInfo> sProviders = Maps.newHashMap();
    /** Map from (authority+rootId) to cached info */
    private static HashMap<Pair<String, String>, Root> sRoots = Maps.newHashMap();

    public static ArrayList<Root> sRootsList = Lists.newArrayList();

    private static Root sRecentsRoot;

    /**
     * Gather roots from all known storage providers.
     */
    private static void ensureCache(Context context) {
        if (sCached) return;
        sCached = true;

        sProviders.clear();
        sRoots.clear();
        sRootsList.clear();

        {
            // Create special root for recents
            final Root root = Root.buildRecents(context);
            sRootsList.add(root);
            sRecentsRoot = root;
        }

        // Query for other storage backends
        final PackageManager pm = context.getPackageManager();
        final List<ProviderInfo> providers = pm.queryContentProviders(
                null, -1, PackageManager.GET_META_DATA);
        for (ProviderInfo providerInfo : providers) {
            if (providerInfo.metaData != null && providerInfo.metaData.containsKey(
                    DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {
                final DocumentsProviderInfo info = DocumentsProviderInfo.parseInfo(
                        context, providerInfo);
                if (info == null) {
                    Log.w(TAG, "Missing info for " + providerInfo);
                    continue;
                }

                sProviders.put(info.providerInfo.authority, info);

                try {
                    // TODO: remove deprecated customRoots flag
                    // TODO: populate roots on background thread, and cache results
                    final Uri uri = DocumentsContract.buildRootsUri(providerInfo.authority);
                    final Cursor cursor = context.getContentResolver()
                            .query(uri, null, null, null, null);
                    try {
                        while (cursor.moveToNext()) {
                            final Root root = Root.fromCursor(context, info, cursor);
                            sRoots.put(Pair.create(info.providerInfo.authority, root.rootId), root);
                            sRootsList.add(root);
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
    public static DocumentsProviderInfo findProvider(Context context, String authority) {
        ensureCache(context);
        return sProviders.get(authority);
    }

    @GuardedBy("ActivityThread")
    public static Root findRoot(Context context, String authority, String rootId) {
        ensureCache(context);
        return sRoots.get(Pair.create(authority, rootId));
    }

    @GuardedBy("ActivityThread")
    public static Root findRoot(Context context, Document doc) {
        final String authority = doc.uri.getAuthority();
        final String rootId = DocumentsContract.getRootId(doc.uri);
        return findRoot(context, authority, rootId);
    }

    @GuardedBy("ActivityThread")
    public static Root getRecentsRoot(Context context) {
        ensureCache(context);
        return sRecentsRoot;
    }

    @GuardedBy("ActivityThread")
    public static Collection<Root> getRoots(Context context) {
        ensureCache(context);
        return sRootsList;
    }

    @GuardedBy("ActivityThread")
    public static Drawable resolveDocumentIcon(Context context, String authority, String mimeType) {
        // Custom icons take precedence
        ensureCache(context);
        final DocumentsProviderInfo info = sProviders.get(authority);
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
