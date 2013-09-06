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

import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.provider.DocumentsContract.Root;

import com.android.documentsui.IconUtils;
import com.android.documentsui.R;

import java.util.Objects;

/**
 * Representation of a {@link Root}.
 */
public class RootInfo {
    public String authority;
    public String rootId;
    public int rootType;
    public int flags;
    public int icon;
    public int localIcon;
    public String title;
    public String summary;
    public String documentId;
    public long availableBytes;
    public String[] mimeTypes;

    public static RootInfo fromRootsCursor(String authority, Cursor cursor) {
        final RootInfo root = new RootInfo();
        root.authority = authority;
        root.rootId = getCursorString(cursor, Root.COLUMN_ROOT_ID);
        root.rootType = getCursorInt(cursor, Root.COLUMN_ROOT_TYPE);
        root.flags = getCursorInt(cursor, Root.COLUMN_FLAGS);
        root.icon = getCursorInt(cursor, Root.COLUMN_ICON);
        root.title = getCursorString(cursor, Root.COLUMN_TITLE);
        root.summary = getCursorString(cursor, Root.COLUMN_SUMMARY);
        root.documentId = getCursorString(cursor, Root.COLUMN_DOCUMENT_ID);
        root.availableBytes = getCursorLong(cursor, Root.COLUMN_AVAILABLE_BYTES);

        final String raw = getCursorString(cursor, Root.COLUMN_MIME_TYPES);
        root.mimeTypes = (raw != null) ? raw.split("\n") : null;

        // TODO: remove these special case icons
        if ("com.android.externalstorage.documents".equals(authority)) {
            root.localIcon = R.drawable.ic_root_sdcard;
        }
        if ("com.android.providers.downloads.documents".equals(authority)) {
            root.localIcon = R.drawable.ic_root_download;
        }
        if ("com.android.providers.media.documents".equals(authority)) {
            if ("image".equals(root.rootId)) {
                root.localIcon = R.drawable.ic_doc_image;
            } else if ("audio".equals(root.rootId)) {
                root.localIcon = R.drawable.ic_doc_audio;
            }
        }

        return root;
    }

    public Drawable loadIcon(Context context) {
        if (localIcon != 0) {
            return context.getResources().getDrawable(localIcon);
        } else {
            return IconUtils.loadPackageIcon(context, authority, icon);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof RootInfo) {
            final RootInfo root = (RootInfo) o;
            return Objects.equals(authority, root.authority) && Objects.equals(rootId, root.rootId);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(authority, rootId);
    }

    public String getDirectoryString() {
        return (summary != null) ? summary : title;
    }
}
