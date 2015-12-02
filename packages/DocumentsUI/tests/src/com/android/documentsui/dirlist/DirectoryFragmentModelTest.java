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

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.DocumentsContract.Document;
import android.support.v7.widget.RecyclerView;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.ViewGroup;

import com.android.documentsui.DirectoryResult;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.model.DocumentInfo;

import java.util.ArrayList;
import java.util.List;

@SmallTest
public class DirectoryFragmentModelTest extends AndroidTestCase {

    // Item count must be an even number (see setUp below)
    private static final int ITEM_COUNT = 10;
    private static final String[] COLUMNS = new String[]{
        RootCursorWrapper.COLUMN_AUTHORITY,
        Document.COLUMN_DOCUMENT_ID
    };
    private static Cursor cursor;

    private Context mContext;
    private Model model;

    public void setUp() {
        setupTestContext();

        // Make two sets of documents under two different authorities but with identical document
        // IDs.
        MatrixCursor c = new MatrixCursor(COLUMNS);
        for (int i = 0; i < ITEM_COUNT/2; ++i) {
            MatrixCursor.RowBuilder row0 = c.newRow();
            row0.add(RootCursorWrapper.COLUMN_AUTHORITY, "authority0");
            row0.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));

            MatrixCursor.RowBuilder row1 = c.newRow();
            row1.add(RootCursorWrapper.COLUMN_AUTHORITY, "authority1");
            row1.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
}
        cursor = c;

        DirectoryResult r = new DirectoryResult();
        r.cursor = cursor;

        // Instantiate the model with a dummy view adapter and listener that (for now) do nothing.
        model = new Model(mContext, new DummyAdapter());
        model.addUpdateListener(new DummyListener());
        model.update(r);
    }

    // Tests that the item count is correct.
    public void testItemCount() {
        assertEquals(ITEM_COUNT, model.getItemCount());
    }

    // Tests that the item count is correct after a deletion.
    public void testItemCount_WithDeletion() {
        // Simulate deleting 2 files.
        delete(2, 4);

        assertEquals(ITEM_COUNT - 2, model.getItemCount());
    }

    // Tests that the item count is correct after a deletion is undone.
    public void testItemCount_WithUndoneDeletion() {
        // Simulate deleting 2 files.
        delete(0, 3);

        // Undo the deletion
        model.undoDeletion();
        assertEquals(ITEM_COUNT, model.getItemCount());
    }

    // Tests that the right things are marked for deletion.
    public void testMarkForDeletion() {
        delete(1, 3);

        List<DocumentInfo> docs = model.getDocumentsMarkedForDeletion();
        assertEquals(2, docs.size());
        assertEquals("1", docs.get(0).documentId);
        assertEquals("3", docs.get(1).documentId);
    }

    // Tests the base case for Model.getItem.
    public void testGetItem() {
        for (int i = 0; i < ITEM_COUNT; ++i) {
            cursor.moveToPosition(i);
            Cursor c = model.getItem(Model.createId(cursor));
            assertEquals(i, c.getPosition());
        }
    }

    // Tests that Model.getItem returns the right items after a deletion.
    public void testGetItem_WithDeletion() {
        // Simulate deleting 2 files.
        delete(2, 3);

        List<DocumentInfo> docs = getDocumentInfo(0, 1, 2);
        assertEquals("0", docs.get(0).documentId);
        assertEquals("1", docs.get(1).documentId);
        assertEquals("4", docs.get(2).documentId);
    }

    // Tests that Model.getItem returns the right items after a deletion is undone.
    public void testGetItem_WithCancelledDeletion() {
        delete(0, 1);
        model.undoDeletion();

        // Test that all documents are accounted for, in the right position.
        for (int i = 0; i < ITEM_COUNT; ++i) {
            assertEquals(Integer.toString(i), getDocumentInfo(i).get(0).documentId);
        }
    }

    private void setupTestContext() {
        final MockContentResolver resolver = new MockContentResolver();
        mContext = new ContextWrapper(getContext()) {
            @Override
            public ContentResolver getContentResolver() {
                return resolver;
            }
        };
    }

    private void delete(int... positions) {
//        model.markForDeletion(new Selection(positions));
    }

    private List<DocumentInfo> getDocumentInfo(int... positions) {
//        return model.getDocuments(new Selection(positions));
        return new ArrayList<>();
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
