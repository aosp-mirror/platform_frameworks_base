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

import android.content.Context;
import android.database.Cursor;
import android.widget.Space;

import com.android.documentsui.R;
import com.android.documentsui.State;

final class EmptyDocumentHolder extends DocumentHolder {
    public EmptyDocumentHolder(Context context) {
        super(context, new Space(context));

        // Per UX spec, this puts a bigger gap between the folders and documents in the grid.
        final int gridMargin = context.getResources().getDimensionPixelSize(R.dimen.grid_item_margin);
        itemView.setMinimumHeight(gridMargin * 2);
    }

    public void bind(Cursor cursor, String modelId, State state) {
        // Nothing to bind.
        return;
    }
}
