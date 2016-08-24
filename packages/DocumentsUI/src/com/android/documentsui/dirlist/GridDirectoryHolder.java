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

import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.content.Context;
import android.database.Cursor;
import android.provider.DocumentsContract.Document;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.documentsui.R;
import com.android.documentsui.State;

final class GridDirectoryHolder extends DocumentHolder {
    final TextView mTitle;
    private ImageView mIconCheck;
    private ImageView mIconMime;

    public GridDirectoryHolder(Context context, ViewGroup parent) {
        super(context, parent, R.layout.item_dir_grid);

        mTitle = (TextView) itemView.findViewById(android.R.id.title);
        mIconMime = (ImageView) itemView.findViewById(R.id.icon_mime_sm);
        mIconCheck = (ImageView) itemView.findViewById(R.id.icon_check);
    }

    @Override
    public void setSelected(boolean selected, boolean animate) {
        super.setSelected(selected, animate);
        float checkAlpha = selected ? 1f : 0f;

        if (animate) {
            mIconCheck.animate().alpha(checkAlpha).start();
            mIconMime.animate().alpha(1f - checkAlpha).start();
        } else {
            mIconCheck.setAlpha(checkAlpha);
            mIconMime.setAlpha(1f - checkAlpha);
        }
    }

    /**
     * Bind this view to the given document for display.
     * @param cursor Pointing to the item to be bound.
     * @param modelId The model ID of the item.
     * @param state Current display state.
     */
    public void bind(Cursor cursor, String modelId, State state) {
        assert(cursor != null);

        this.modelId = modelId;

        final String docDisplayName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
        mTitle.setText(docDisplayName, TextView.BufferType.SPANNABLE);

    }
}
