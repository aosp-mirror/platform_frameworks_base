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

import android.util.SparseArray;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * A skeletal {@link DocumentsAdapter} test double.
 */
public class TestDocumentsAdapter extends DocumentsAdapter {

    List<String> mModelIds = new ArrayList<>();

    public TestDocumentsAdapter(List<String> modelIds) {
        mModelIds = modelIds;
    }

    @Override
    public void onModelUpdate(Model model) {
    }

    @Override
    public void onModelUpdateFailed(Exception e) {
    }

    @Override
    List<String> getModelIds() {
        return mModelIds;
    }

    @Override
    void onItemSelectionChanged(String id) {
    }

    @Override
    String getModelId(int position) {
        return mModelIds.get(position);
    }

    @Override
    public SparseArray<String> hide(String... ids) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DocumentHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onBindViewHolder(DocumentHolder holder, int position) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getItemCount() {
        return mModelIds.size();
    }
}
