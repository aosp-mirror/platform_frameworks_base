/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.database.MatrixCursor;
import android.provider.DocumentsContract.Document;
import android.support.v7.widget.RecyclerView;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.ViewGroup;

import com.android.documentsui.DirectoryResult;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.State;

@SmallTest
public class SectionBreakDocumentsAdapterWrapperTest extends AndroidTestCase {

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
    private SectionBreakDocumentsAdapterWrapper mAdapter;

    public void setUp() {

        final Context testContext = TestContext.createStorageTestContext(getContext(), AUTHORITY);
        DocumentsAdapter.Environment env = new TestEnvironment(testContext);

        mModel = new TestModel(AUTHORITY);
        mAdapter = new SectionBreakDocumentsAdapterWrapper(
            env,
            new ModelBackedDocumentsAdapter(
                env, new IconHelper(testContext, State.MODE_GRID)));

        mModel.addUpdateListener(mAdapter);
    }

    // Tests that the item count is correct for a directory containing only subdirs.
    public void testItemCount_allDirs() {
        MatrixCursor c = new MatrixCursor(TestModel.COLUMNS);

        for (int i = 0; i < 5; ++i) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY);
            row.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
            row.add(Document.COLUMN_SIZE, i);
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        }
        DirectoryResult r = new DirectoryResult();
        r.cursor = c;
        r.sortOrder = State.SORT_ORDER_SIZE;
        mModel.update(r);

        assertEquals(mModel.getItemCount(), mAdapter.getItemCount());
    }

    // Tests that the item count is correct for a directory containing only files.
    public void testItemCount_allFiles() {
        mModel.update(NAMES);
        assertEquals(mModel.getItemCount(), mAdapter.getItemCount());
    }

    // Tests that the item count is correct for a directory containing files and subdirs.
    public void testItemCount_mixed() {
        MatrixCursor c = new MatrixCursor(TestModel.COLUMNS);

        for (int i = 0; i < 5; ++i) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY);
            row.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
            row.add(Document.COLUMN_SIZE, i);
            String mimeType =(i < 2) ? Document.MIME_TYPE_DIR : "text/*";
            row.add(Document.COLUMN_MIME_TYPE, mimeType);
        }
        DirectoryResult r = new DirectoryResult();
        r.cursor = c;
        r.sortOrder = State.SORT_ORDER_SIZE;
        mModel.update(r);

        assertEquals(mModel.getItemCount() + 1, mAdapter.getItemCount());
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
