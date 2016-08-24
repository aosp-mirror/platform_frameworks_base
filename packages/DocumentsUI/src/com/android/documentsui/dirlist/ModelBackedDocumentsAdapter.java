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

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.State.MODE_GRID;
import static com.android.documentsui.State.MODE_LIST;
import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.database.Cursor;
import android.provider.DocumentsContract.Document;
import android.util.Log;
import android.util.SparseArray;
import android.view.ViewGroup;

import com.android.documentsui.State;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapts from dirlist.Model to something RecyclerView understands.
 */
final class ModelBackedDocumentsAdapter extends DocumentsAdapter {

    private static final String TAG = "ModelBackedDocuments";
    public static final int ITEM_TYPE_DOCUMENT = 1;
    public static final int ITEM_TYPE_DIRECTORY = 2;

    // Provides access to information needed when creating and view holders. This
    // isn't an ideal pattern (more transitive dependency stuff) but good enough for now.
    private final Environment mEnv;
    private final IconHelper mIconHelper;  // a transitive dependency of the holders.

    /**
     * An ordered list of model IDs. This is the data structure that determines what shows up in
     * the UI, and where.
     */
    private List<String> mModelIds = new ArrayList<>();

    // List of files that have been deleted. Some transient directory updates
    // may happen while files are being deleted. During this time we don't
    // want once-hidden files to be re-shown. We only remove
    // items from this list when we get a model update where the model
    // does not contain a corresponding id. This ensures hidden entries
    // don't momentarily re-appear if we get intermediate updates from
    // the file system.
    private Set<String> mHiddenIds = new HashSet<>();

    public ModelBackedDocumentsAdapter(Environment env, IconHelper iconHelper) {
        mEnv = env;
        mIconHelper = iconHelper;
    }

    @Override
    public DocumentHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        DocumentHolder holder = null;
        final State state = mEnv.getDisplayState();
        switch (state.derivedMode) {
            case MODE_GRID:
                switch (viewType) {
                    case ITEM_TYPE_DIRECTORY:
                        holder = new GridDirectoryHolder(mEnv.getContext(), parent);
                        break;
                    case ITEM_TYPE_DOCUMENT:
                        holder = new GridDocumentHolder(mEnv.getContext(), parent, mIconHelper);
                        break;
                    default:
                        throw new IllegalStateException("Unsupported layout type.");
                }
                break;
            case MODE_LIST:
                holder = new ListDocumentHolder(mEnv.getContext(), parent, mIconHelper);
                break;
            default:
                throw new IllegalStateException("Unsupported layout mode.");
        }

        mEnv.initDocumentHolder(holder);
        return holder;
    }

    @Override
    public void onBindViewHolder(DocumentHolder holder, int position, List<Object> payload) {
        if (payload.contains(SELECTION_CHANGED_MARKER)) {
            final boolean selected = mEnv.isSelected(mModelIds.get(position));
            holder.setSelected(selected, true);
        } else {
            onBindViewHolder(holder, position);
        }
    }

    @Override
    public void onBindViewHolder(DocumentHolder holder, int position) {
        String modelId = mModelIds.get(position);
        Cursor cursor = mEnv.getModel().getItem(modelId);
        holder.bind(cursor, modelId, mEnv.getDisplayState());

        final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);

        boolean enabled = mEnv.isDocumentEnabled(docMimeType, docFlags);
        boolean selected = mEnv.isSelected(modelId);
        if (!enabled) {
            assert(!selected);
        }
        holder.setEnabled(enabled);
        holder.setSelected(mEnv.isSelected(modelId), false);

        mEnv.onBindDocumentHolder(holder, cursor);
    }

    @Override
    public int getItemCount() {
        return mModelIds.size();
    }

    @Override
    public void onModelUpdate(Model model) {
        if (DEBUG && mHiddenIds.size() > 0) {
            Log.d(TAG, "Updating model with hidden ids: " + mHiddenIds);
        }

        String[] modelIds = model.getModelIds();
        mModelIds = new ArrayList<>(modelIds.length);
        for (String id : modelIds) {
            if (!mHiddenIds.contains(id)) {
                mModelIds.add(id);
            } else {
                if (DEBUG) Log.d(TAG, "Omitting hidden id from model during update: " + id);
            }
        }

        // Finally remove any hidden ids that aren't present in the model.
        // This assumes that model updates represent a complete set of files.
        mHiddenIds.retainAll(mModelIds);
    }

    @Override
    public void onModelUpdateFailed(Exception e) {
        Log.w(TAG, "Model update failed.", e);
        mModelIds.clear();
    }

    @Override
    public String getModelId(int adapterPosition) {
        return mModelIds.get(adapterPosition);
    }

    @Override
    public SparseArray<String> hide(String... ids) {
        if (DEBUG) Log.d(TAG, "Hiding ids: " + ids);
        Set<String> toHide = Sets.newHashSet(ids);

        // Proceed backwards through the list of items, because each removal causes the
        // positions of all subsequent items to change.
        SparseArray<String> hiddenItems = new SparseArray<>();
        for (int i = mModelIds.size() - 1; i >= 0; --i) {
            String id = mModelIds.get(i);
            if (toHide.contains(id)) {
                mHiddenIds.add(id);
                hiddenItems.put(i, mModelIds.remove(i));
                notifyItemRemoved(i);
            }
        }

        return hiddenItems;
    }

    @Override
    public List<String> getModelIds() {
        return mModelIds;
    }

    @Override
    public int getItemViewType(int position) {
        return isDirectory(mEnv.getModel(), position)
                ? ITEM_TYPE_DIRECTORY
                : ITEM_TYPE_DOCUMENT;
    }

    @Override
    public void onItemSelectionChanged(String id) {
        int position = mModelIds.indexOf(id);

        if (position >= 0) {
            notifyItemChanged(position, SELECTION_CHANGED_MARKER);
        } else {
            Log.w(TAG, "Item change notification received for unknown item: " + id);
        }
    }
}
