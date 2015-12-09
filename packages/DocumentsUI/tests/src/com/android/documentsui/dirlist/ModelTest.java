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

import static android.test.MoreAsserts.assertNotEqual;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.v7.widget.RecyclerView;
import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.ViewGroup;

import com.android.documentsui.DirectoryResult;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@SmallTest
public class ModelTest extends AndroidTestCase {

    private static final int ITEM_COUNT = 10;
    private static final String AUTHORITY = "test_authority";
    private static final String[] COLUMNS = new String[]{
        RootCursorWrapper.COLUMN_AUTHORITY,
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_FLAGS
    };
    private static Cursor cursor;

    private Context context;
    private Model model;
    private TestContentProvider provider;

    public void setUp() {
        setupTestContext();

        MatrixCursor c = new MatrixCursor(COLUMNS);
        for (int i = 0; i < ITEM_COUNT; ++i) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY);
            row.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
            row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE);
        }
        cursor = c;

        DirectoryResult r = new DirectoryResult();
        r.cursor = cursor;

        // Instantiate the model with a dummy view adapter and listener that (for now) do nothing.
        model = new Model(context, new DummyAdapter());
        model.addUpdateListener(new DummyListener());
        model.update(r);
    }

    // Tests that the item count is correct.
    public void testItemCount() {
        assertEquals(ITEM_COUNT, model.getItemCount());
    }

    // Tests multiple authorities with clashing document IDs.
    public void testModelIdIsUnique() {
        MatrixCursor c0 = new MatrixCursor(COLUMNS);
        MatrixCursor c1 = new MatrixCursor(COLUMNS);


        // Make two sets of items with the same IDs, under different authorities.
        final String AUTHORITY0 = "auth0";
        final String AUTHORITY1 = "auth1";
        for (int i = 0; i < ITEM_COUNT; ++i) {
            MatrixCursor.RowBuilder row0 = c0.newRow();
            row0.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY0);
            row0.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));

            MatrixCursor.RowBuilder row1 = c1.newRow();
            row1.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY1);
            row1.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
        }

        for (int i = 0; i < ITEM_COUNT; ++i) {
            c0.moveToPosition(i);
            c1.moveToPosition(i);
            assertNotEqual(Model.createId(c0), Model.createId(c1));
        }
    }

    // Tests the base case for Model.getItem.
    public void testGetItem() {
        for (int i = 0; i < ITEM_COUNT; ++i) {
            cursor.moveToPosition(i);
            Cursor c = model.getItem(Model.createId(cursor));
            assertEquals(i, c.getPosition());
        }
    }

    // Tests that Model.delete works correctly.
    public void testDelete() throws Exception {
        // Simulate deleting 2 files.
        List<DocumentInfo> docsBefore = getDocumentInfo(2, 3);
        delete(2, 3);

        provider.assertWasDeleted(docsBefore.get(0));
        provider.assertWasDeleted(docsBefore.get(1));
    }

    private void setupTestContext() {
        final MockContentResolver resolver = new MockContentResolver();
        context = new ContextWrapper(getContext()) {
            @Override
            public ContentResolver getContentResolver() {
                return resolver;
            }
        };
        provider = new TestContentProvider();
        resolver.addProvider(AUTHORITY, provider);
    }

    private Selection positionToSelection(int... positions) {
        Selection s = new Selection();
        // Construct a selection of the given positions.
        for (int p: positions) {
            cursor.moveToPosition(p);
            s.add(Model.createId(cursor));
        }
        return s;
    }

    private void delete(int... positions) throws InterruptedException {
        Selection s = positionToSelection(positions);
        final CountDownLatch latch = new CountDownLatch(1);

        model.delete(
                s,
                new Model.DeletionListener() {
                    @Override
                    public void onError() {
                        latch.countDown();
                    }
                    @Override
                    void onCompletion() {
                        latch.countDown();
                    }
                });
        latch.await();
    }

    private List<DocumentInfo> getDocumentInfo(int... positions) {
        return model.getDocuments(positionToSelection(positions));
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

    private static class TestContentProvider extends MockContentProvider {
        List<Uri> mDeleted = new ArrayList<>();

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            // Intercept and log delete method calls.
            if (DocumentsContract.METHOD_DELETE_DOCUMENT.equals(method)) {
                final Uri documentUri = extras.getParcelable(DocumentsContract.EXTRA_URI);
                mDeleted.add(documentUri);
                return new Bundle();
            } else {
                return super.call(method, arg, extras);
            }
        }

        public void assertWasDeleted(DocumentInfo doc) {
            assertTrue(mDeleted.contains(doc.derivedUri));
        }
    }
}
