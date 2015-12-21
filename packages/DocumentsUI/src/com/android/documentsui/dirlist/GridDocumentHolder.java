/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.documentsui.R;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.Shared;
import com.android.documentsui.State;

final class GridDocumentHolder extends DocumentHolder {
    private static boolean mHideTitles;

    public GridDocumentHolder(
            Context context, ViewGroup parent, IconHelper thumbnailLoader, int viewType) {
        super(context, parent, R.layout.item_doc_grid, thumbnailLoader);
    }

    /**
     * Bind this view to the given document for display.
     * @param cursor Pointing to the item to be bound.
     * @param modelId The model ID of the item.
     * @param state Current display state.
     */
    public void bind(Cursor cursor, String modelId, State state) {
        this.modelId = modelId;

        checkNotNull(cursor, "Cursor cannot be null.");

        final String docAuthority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
        final String docId = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
        final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        final String docDisplayName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
        final long docLastModified = getCursorLong(cursor, Document.COLUMN_LAST_MODIFIED);
        final int docIcon = getCursorInt(cursor, Document.COLUMN_ICON);
        final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
        final long docSize = getCursorLong(cursor, Document.COLUMN_SIZE);

        final TextView title = (TextView) itemView.findViewById(android.R.id.title);
        final TextView date = (TextView) itemView.findViewById(R.id.date);
        final TextView size = (TextView) itemView.findViewById(R.id.size);
        final ImageView iconMime = (ImageView) itemView.findViewById(R.id.icon_mime);
        final ImageView iconThumb = (ImageView) itemView.findViewById(R.id.icon_thumb);

        mIconHelper.stopLoading(iconThumb);

        iconMime.animate().cancel();
        iconMime.setAlpha(1f);
        iconThumb.animate().cancel();
        iconThumb.setAlpha(0f);

        final Uri uri = DocumentsContract.buildDocumentUri(docAuthority, docId);
        mIconHelper.loadThumbnail(uri, docMimeType, docFlags, docIcon, iconThumb, iconMime);

        if (mHideTitles) {
            title.setVisibility(View.GONE);
        } else {
            title.setText(docDisplayName);
            title.setVisibility(View.VISIBLE);
        }

        if (docLastModified == -1) {
            date.setText(null);
        } else {
            date.setText(Shared.formatTime(mContext, docLastModified));
        }

        if (!state.showSize || Document.MIME_TYPE_DIR.equals(docMimeType) || docSize == -1) {
            size.setVisibility(View.GONE);
        } else {
            size.setVisibility(View.VISIBLE);
            size.setText(Formatter.formatFileSize(mContext, docSize));
        }
    }

    /**
     * Sets whether to hide titles on subsequently created GridDocumentHolder items.
     * @param hideTitles
     */
    public static void setHideTitles(boolean hideTitles) {
        mHideTitles = hideTitles;
    }
}
