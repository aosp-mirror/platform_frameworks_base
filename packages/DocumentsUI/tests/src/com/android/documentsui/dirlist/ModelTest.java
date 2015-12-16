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
import com.android.documentsui.State;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

@SmallTest
public class ModelTest extends AndroidTestCase {

    private static final int ITEM_COUNT = 10;
    private static final String AUTHORITY = "test_authority";
    private static final String[] COLUMNS = new String[]{
        RootCursorWrapper.COLUMN_AUTHORITY,
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_FLAGS,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE
    };
    private static Cursor cursor;

    private Context context;
    private Model model;
    private TestContentProvider provider;
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

    public void setUp() {
        setupTestContext();

        Random rand = new Random();

        MatrixCursor c = new MatrixCursor(COLUMNS);
        for (int i = 0; i < ITEM_COUNT; ++i) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY);
            row.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
            row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE);
            // Generate random document names and sizes. This forces the model's internal sort code
            // to actually do something.
            row.add(Document.COLUMN_DISPLAY_NAME, NAMES[i]);
            row.add(Document.COLUMN_SIZE, rand.nextInt());
        }
        cursor = c;

        DirectoryResult r = new DirectoryResult();
        r.cursor = cursor;

        // Instantiate the model with a dummy view adapter and listener that (for now) do nothing.
        model = new Model(context, new DummyAdapter());
        model.addUpdateListener(new DummyListener());
        model.update(r);
    }

    // Tests that the model is properly emptied out after a null update.
    public void testNullUpdate() {
        model.update(null);

        assertTrue(model.isEmpty());
        assertEquals(0, model.getItemCount());
        assertEquals(0, model.getModelIds().size());
    }

    // Tests that the item count is correct.
    public void testItemCount() {
        assertEquals(ITEM_COUNT, model.getItemCount());
    }

    // Tests multiple authorities with clashing document IDs.
    public void testModelIdIsUnique() {
        MatrixCursor cIn = new MatrixCursor(COLUMNS);

        // Make two sets of items with the same IDs, under different authorities.
        final String AUTHORITY0 = "auth0";
        final String AUTHORITY1 = "auth1";
        for (int i = 0; i < ITEM_COUNT; ++i) {
            MatrixCursor.RowBuilder row0 = cIn.newRow();
            row0.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY0);
            row0.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));

            MatrixCursor.RowBuilder row1 = cIn.newRow();
            row1.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY1);
            row1.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
        }

        // Update the model, then make sure it contains all the expected items.
        DirectoryResult r = new DirectoryResult();
        r.cursor = cIn;
        model.update(r);

        assertEquals(ITEM_COUNT * 2, model.getItemCount());
        BitSet b0 = new BitSet(ITEM_COUNT);
        BitSet b1 = new BitSet(ITEM_COUNT);

        for (String id: model.getModelIds()) {
            Cursor cOut = model.getItem(id);
            String authority =
                    DocumentInfo.getCursorString(cOut, RootCursorWrapper.COLUMN_AUTHORITY);
            String docId = DocumentInfo.getCursorString(cOut, Document.COLUMN_DOCUMENT_ID);

            switch (authority) {
                case AUTHORITY0:
                    b0.set(Integer.parseInt(docId));
                    break;
                case AUTHORITY1:
                    b1.set(Integer.parseInt(docId));
                    break;
                default:
                    fail("Unrecognized authority string");
            }
        }

        assertEquals(ITEM_COUNT, b0.cardinality());
        assertEquals(ITEM_COUNT, b1.cardinality());
    }

    // Tests the base case for Model.getItem.
    public void testGetItem() {
        List<String> ids = model.getModelIds();
        assertEquals(ITEM_COUNT, ids.size());
        for (int i = 0; i < ITEM_COUNT; ++i) {
            Cursor c = model.getItem(ids.get(i));
            assertEquals(i, c.getPosition());
        }
    }

    // Tests sorting by item name.
    public void testSort_names() {
        BitSet seen = new BitSet(ITEM_COUNT);
        List<String> names = new ArrayList<>();

        DirectoryResult r = new DirectoryResult();
        r.cursor = cursor;
        r.sortOrder = State.SORT_ORDER_DISPLAY_NAME;
        model.update(r);

        for (String id: model.getModelIds()) {
            Cursor c = model.getItem(id);
            seen.set(c.getPosition());
            names.add(DocumentInfo.getCursorString(c, Document.COLUMN_DISPLAY_NAME));
        }

        assertEquals(ITEM_COUNT, seen.cardinality());
        for (int i = 0; i < names.size()-1; ++i) {
            assertTrue(DocumentInfo.compareToIgnoreCaseNullable(names.get(i), names.get(i+1)) <= 0);
        }
    }

    // Tests sorting by item size.
    public void testSort_sizes() {
        BitSet seen = new BitSet(ITEM_COUNT);
        List<Integer> sizes = new ArrayList<>();

        DirectoryResult r = new DirectoryResult();
        r.cursor = cursor;
        r.sortOrder = State.SORT_ORDER_SIZE;
        model.update(r);

        for (String id: model.getModelIds()) {
            Cursor c = model.getItem(id);
            seen.set(c.getPosition());
            sizes.add(DocumentInfo.getCursorInt(c, Document.COLUMN_SIZE));
        }

        assertEquals(ITEM_COUNT, seen.cardinality());
        for (int i = 0; i < sizes.size()-1; ++i) {
            // Note: sizes are sorted descending.
            assertTrue(sizes.get(i) >= sizes.get(i+1));
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
        List<String> ids = model.getModelIds();
        Selection s = new Selection();
        // Construct a selection of the given positions.
        for (int p: positions) {
            s.add(ids.get(p));
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
