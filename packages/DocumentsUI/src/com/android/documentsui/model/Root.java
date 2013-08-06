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

package com.android.documentsui.model;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.RootColumns;

import com.android.documentsui.R;
import com.android.documentsui.RecentsProvider;

/**
 * Representation of a root under a storage backend.
 */
public class Root {
    public String rootId;
    public int rootType;
    public Uri uri;
    public Drawable icon;
    public String title;
    public String summary;
    public boolean isRecents;

    public static Root buildRecentOpen(Context context) {
        final PackageManager pm = context.getPackageManager();
        final Root root = new Root();
        root.rootId = null;
        root.rootType = DocumentsContract.ROOT_TYPE_SHORTCUT;
        root.uri = RecentsProvider.buildRecentOpen();
        root.icon = context.getResources().getDrawable(R.drawable.ic_dir);
        root.title = context.getString(R.string.root_recent);
        root.summary = null;
        root.isRecents = true;
        return root;
    }

    public static Root fromCursor(
            Context context, DocumentsProviderInfo info, Cursor cursor) {
        final PackageManager pm = context.getPackageManager();

        final Root root = new Root();
        root.rootId = cursor.getString(cursor.getColumnIndex(RootColumns.ROOT_ID));
        root.rootType = cursor.getInt(cursor.getColumnIndex(RootColumns.ROOT_TYPE));
        root.uri = DocumentsContract.buildDocumentUri(
                info.providerInfo.authority, root.rootId, DocumentsContract.ROOT_DOC_ID);
        root.icon = info.providerInfo.loadIcon(pm);
        root.title = info.providerInfo.loadLabel(pm).toString();
        root.summary = null;

        final int icon = cursor.getInt(cursor.getColumnIndex(RootColumns.ICON));
        if (icon != 0) {
            try {
                root.icon = pm.getResourcesForApplication(info.providerInfo.applicationInfo)
                        .getDrawable(icon);
            } catch (NotFoundException e) {
                throw new RuntimeException(e);
            } catch (NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        final String title = cursor.getString(cursor.getColumnIndex(RootColumns.TITLE));
        if (title != null) {
            root.title = title;
        }

        root.summary = cursor.getString(cursor.getColumnIndex(RootColumns.SUMMARY));
        root.isRecents = false;

        return root;
    }
}
