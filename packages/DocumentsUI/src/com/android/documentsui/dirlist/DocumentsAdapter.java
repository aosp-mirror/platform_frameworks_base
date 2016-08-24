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
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.SparseArray;

import com.android.documentsui.State;

import java.util.List;

/**
 * DocumentsAdapter provides glue between a directory Model, and RecylcerView. We've
 * abstracted this a bit in order to decompose some specialized support
 * for adding dummy layout objects (@see SectionBreakDocumentsAdapter). Handling of the
 * dummy layout objects was error prone when interspersed with the core mode / adapter code.
 *
 * @see ModelBackedDocumentsAdapter
 * @see SectionBreakDocumentsAdapter
 */
abstract class DocumentsAdapter
        extends RecyclerView.Adapter<DocumentHolder>
        implements Model.UpdateListener {

    // Payloads for notifyItemChange to distinguish between selection and other events.
    static final String SELECTION_CHANGED_MARKER = "Selection-Changed";

    /**
     * Returns a list of model IDs of items currently in the adapter. Excludes items that are
     * currently hidden (see {@link #hide(String...)}).
     *
     * @return A list of Model IDs.
     */
    abstract List<String> getModelIds();

    /**
     * Triggers item-change notifications by stable ID (as opposed to position).
     * Passing an unrecognized ID will result in a warning in logcat, but no other error.
     */
    abstract void onItemSelectionChanged(String id);

    /**
     * @return The model ID of the item at the given adapter position.
     */
    abstract String getModelId(int position);

    /**
     * Hides a set of items from the associated RecyclerView.
     *
     * @param ids The Model IDs of the items to hide.
     * @return A SparseArray that maps the hidden IDs to their old positions. This can be used
     *         to {@link #unhide} the items if necessary.
     */
    abstract public SparseArray<String> hide(String... ids);

    /**
     * Returns a class that yields the span size for a particular element. This is
     * primarily useful in {@link SectionBreakDocumentsAdapterWrapper} where
     * we adjust sizes.
     */
    GridLayoutManager.SpanSizeLookup createSpanSizeLookup() {
        throw new UnsupportedOperationException();
    }

    static boolean isDirectory(Cursor cursor) {
        final String mimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        return Document.MIME_TYPE_DIR.equals(mimeType);
    }

    boolean isDirectory(Model model, int position) {
        String modelId = getModelIds().get(position);
        Cursor cursor = model.getItem(modelId);
        return isDirectory(cursor);
    }

    /**
     * Environmental access for View adapter implementations.
     */
    interface Environment {
        Context getContext();
        int getColumnCount();
        State getDisplayState();
        boolean isSelected(String id);
        Model getModel();
        boolean isDocumentEnabled(String mimeType, int flags);
        void initDocumentHolder(DocumentHolder holder);
        void onBindDocumentHolder(DocumentHolder holder, Cursor cursor);
    }
}
