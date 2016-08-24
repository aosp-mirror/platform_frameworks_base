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

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.documentsui.R;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.Shared;
import com.android.documentsui.State;

final class ListDocumentHolder extends DocumentHolder {
    final TextView mTitle;
    final LinearLayout mDetails;  // Container of date/size/summary
    final TextView mDate;
    final TextView mSize;
    final TextView mSummary;
    final ImageView mIconMime;
    final ImageView mIconThumb;
    final ImageView mIconCheck;
    final IconHelper mIconHelper;

    public ListDocumentHolder(Context context, ViewGroup parent, IconHelper iconHelper) {
        super(context, parent, R.layout.item_doc_list);

        mTitle = (TextView) itemView.findViewById(android.R.id.title);
        mDate = (TextView) itemView.findViewById(R.id.date);
        mSize = (TextView) itemView.findViewById(R.id.size);
        mSummary = (TextView) itemView.findViewById(android.R.id.summary);
        mIconMime = (ImageView) itemView.findViewById(R.id.icon_mime);
        mIconThumb = (ImageView) itemView.findViewById(R.id.icon_thumb);
        mIconCheck = (ImageView) itemView.findViewById(R.id.icon_check);
        // Warning: mDetails view doesn't exists in layout-sw720dp-land layout
        mDetails = (LinearLayout) itemView.findViewById(R.id.line2);

        mIconHelper = iconHelper;
    }

    @Override
    public void setSelected(boolean selected, boolean animate) {
        // We always want to make sure our check box disappears if we're not selected,
        // even if the item is disabled. But it should be an error (see assert below)
        // to be set to selected && be disabled.
        float checkAlpha = selected ? 1f : 0f;
        if (animate) {
            mIconCheck.animate().alpha(checkAlpha).start();
        } else {
            mIconCheck.setAlpha(checkAlpha);
        }

        if (!itemView.isEnabled()) {
            assert(!selected);
            return;
        }

        super.setSelected(selected, animate);

        if (animate) {
            mIconMime.animate().alpha(1f - checkAlpha).start();
            mIconThumb.animate().alpha(1f - checkAlpha).start();
        } else {
            mIconMime.setAlpha(1f - checkAlpha);
            mIconThumb.setAlpha(1f - checkAlpha);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        // Text colors enabled/disabled is handle via a color set.
        final float imgAlpha = enabled ? 1f : DISABLED_ALPHA;
        mIconMime.setAlpha(imgAlpha);
        mIconThumb.setAlpha(imgAlpha);
    }

    /**
     * Bind this view to the given document for display.
     * @param cursor Pointing to the item to be bound.
     * @param modelId The model ID of the item.
     * @param state Current display state.
     */
    @Override
    public void bind(Cursor cursor, String modelId, State state) {
        assert(cursor != null);

        this.modelId = modelId;

        final String docAuthority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
        final String docId = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
        final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        final String docDisplayName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
        final long docLastModified = getCursorLong(cursor, Document.COLUMN_LAST_MODIFIED);
        final int docIcon = getCursorInt(cursor, Document.COLUMN_ICON);
        final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
        final String docSummary = getCursorString(cursor, Document.COLUMN_SUMMARY);
        final long docSize = getCursorLong(cursor, Document.COLUMN_SIZE);
        final boolean isDirectory = Document.MIME_TYPE_DIR.equals(docMimeType);

        mIconHelper.stopLoading(mIconThumb);

        mIconMime.animate().cancel();
        mIconMime.setAlpha(1f);
        mIconThumb.animate().cancel();
        mIconThumb.setAlpha(0f);

        final Uri uri = DocumentsContract.buildDocumentUri(docAuthority, docId);
        mIconHelper.loadThumbnail(uri, docMimeType, docFlags, docIcon, mIconThumb, mIconMime, null);

        mTitle.setText(docDisplayName, TextView.BufferType.SPANNABLE);
        mTitle.setVisibility(View.VISIBLE);


        boolean hasDetails = false;
        if (isDirectory) {
            // Note, we don't show any details for any directory...ever.
            hasDetails = false;
        } else {
            if (docSummary != null) {
                hasDetails = true;
                mSummary.setText(docSummary);
                mSummary.setVisibility(View.VISIBLE);
            } else {
                mSummary.setVisibility(View.INVISIBLE);
            }

            if (docLastModified > 0) {
                hasDetails = true;
                mDate.setText(Shared.formatTime(mContext, docLastModified));
            } else {
                mDate.setText(null);
            }

            if (state.showSize && docSize > -1) {
                hasDetails = true;
                mSize.setVisibility(View.VISIBLE);
                mSize.setText(Formatter.formatFileSize(mContext, docSize));
            } else {
                mSize.setVisibility(View.GONE);
            }
        }

        // mDetails view doesn't exists in layout-sw720dp-land layout
        if (mDetails != null) {
            mDetails.setVisibility(hasDetails ? View.VISIBLE : View.GONE);
        }
    }
}
