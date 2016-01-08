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

final class ListDocumentHolder extends DocumentHolder {
    final TextView mTitle;
    final TextView mSummary;
    final TextView mDate;
    final TextView mSize;
    final ImageView mIconMime;
    final ImageView mIconThumb;
    final ImageView mIconCheck;
    final IconHelper mIconHelper;

    public ListDocumentHolder(Context context, ViewGroup parent, IconHelper iconHelper) {
        super(context, parent, R.layout.item_doc_list);

        mTitle = (TextView) itemView.findViewById(android.R.id.title);
        mSummary = (TextView) itemView.findViewById(android.R.id.summary);
        mDate = (TextView) itemView.findViewById(R.id.date);
        mSize = (TextView) itemView.findViewById(R.id.size);
        mIconMime = (ImageView) itemView.findViewById(R.id.icon_mime);
        mIconThumb = (ImageView) itemView.findViewById(R.id.icon_thumb);
        mIconCheck = (ImageView) itemView.findViewById(R.id.icon_check);

        mIconHelper = iconHelper;
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        float checkAlpha = selected ? 1f : 0f;

        mIconCheck.animate().alpha(checkAlpha).start();
        mIconMime.animate().alpha(1f - checkAlpha).start();
        mIconThumb.animate().alpha(1f - checkAlpha).start();
    }

    /**
     * Bind this view to the given document for display.
     * @param cursor Pointing to the item to be bound.
     * @param modelId The model ID of the item.
     * @param state Current display state.
     */
    @Override
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
        final String docSummary = getCursorString(cursor, Document.COLUMN_SUMMARY);
        final long docSize = getCursorLong(cursor, Document.COLUMN_SIZE);

        mIconHelper.stopLoading(mIconThumb);

        mIconMime.animate().cancel();
        mIconMime.setAlpha(1f);
        mIconThumb.animate().cancel();
        mIconThumb.setAlpha(0f);

        final Uri uri = DocumentsContract.buildDocumentUri(docAuthority, docId);
        mIconHelper.loadThumbnail(uri, docMimeType, docFlags, docIcon, mIconThumb, mIconMime);

        mTitle.setText(docDisplayName);
        mTitle.setVisibility(View.VISIBLE);

        if (docSummary != null) {
            mSummary.setText(docSummary);
            mSummary.setVisibility(View.VISIBLE);
        } else {
            mSummary.setVisibility(View.INVISIBLE);
        }

        if (docLastModified == -1) {
            mDate.setText(null);
        } else {
            mDate.setText(Shared.formatTime(mContext, docLastModified));
        }

        if (!state.showSize || Document.MIME_TYPE_DIR.equals(docMimeType) || docSize == -1) {
            mSize.setVisibility(View.GONE);
        } else {
            mSize.setVisibility(View.VISIBLE);
            mSize.setText(Formatter.formatFileSize(mContext, docSize));
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        final float iconAlpha = enabled ? 1f : 0.5f;
        mIconMime.setAlpha(iconAlpha);
        mIconThumb.setAlpha(iconAlpha);
    }
}
