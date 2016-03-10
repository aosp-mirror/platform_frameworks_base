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
import android.support.v7.widget.RecyclerView;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.SparseArray;
import android.view.ViewGroup;

import com.android.documentsui.State;

import java.util.List;

@SmallTest
public class ModelBackedDocumentsAdapterTest extends AndroidTestCase {

    private static final String AUTHORITY = "test_authority";
    private static final String[] NAMES = new String[] {
            "4",
            "foo",
            "1",
            "bar",
            "*(Ljifl;a",
            "0",
            "baz",
            "2",
            "3",
            "%$%VD"
    };

    private TestModel mModel;
    private ModelBackedDocumentsAdapter mAdapter;

    public void setUp() {

        final Context testContext = TestContext.createStorageTestContext(getContext(), AUTHORITY);
        mModel = new TestModel(AUTHORITY);
        mModel.update(NAMES);

        DocumentsAdapter.Environment env = new TestEnvironment(testContext);

        mAdapter = new ModelBackedDocumentsAdapter(
                env, new IconHelper(testContext, State.MODE_GRID));
        mAdapter.onModelUpdate(mModel);
    }

    // Tests that the item count is correct.
    public void testItemCount() {
        assertEquals(mModel.getItemCount(), mAdapter.getItemCount());
    }

    // Tests that the item count is correct.
    public void testHide_ItemCount() {
        String[] ids = mModel.getModelIds();
        mAdapter.hide(ids[0], ids[1]);
        assertEquals(mModel.getItemCount() - 2, mAdapter.getItemCount());
    }

    private final class TestEnvironment implements DocumentsAdapter.Environment {
        private final Context testContext;

        private TestEnvironment(Context testContext) {
            this.testContext = testContext;
        }

        @Override
        public boolean isSelected(String id) {
            return false;
        }

        @Override
        public boolean isDocumentEnabled(String mimeType, int flags) {
            return true;
        }

        @Override
        public void initDocumentHolder(DocumentHolder holder) {}

        @Override
        public Model getModel() {
            return mModel;
        }

        @Override
        public State getDisplayState() {
            return null;
        }

        @Override
        public Context getContext() {
            return testContext;
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public void onBindDocumentHolder(DocumentHolder holder, Cursor cursor) {}
    }

    private static class DummyListener implements Model.UpdateListener {
        public void onModelUpdate(Model model) {}
        public void onModelUpdateFailed(Exception e) {}
    }

    private static class DummyAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        public int getItemCount() { return 0; }
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return null;
        }
    }
}
