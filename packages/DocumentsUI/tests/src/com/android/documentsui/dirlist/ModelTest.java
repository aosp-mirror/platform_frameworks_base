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
import android.database.MergeCursor;
import android.provider.DocumentsContract.Document;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.documentsui.DirectoryResult;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.Shared;
import com.android.documentsui.State;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

@SmallTest
public class ModelTest extends AndroidTestCase {

    private static final int ITEM_COUNT = 10;
    private static final String AUTHORITY = "test_authority";

    private static final String[] COLUMNS = new String[]{
        RootCursorWrapper.COLUMN_AUTHORITY,
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_FLAGS,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_MIME_TYPE
    };

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

    private Cursor cursor;
    private Context context;
    private Model model;
    private TestContentProvider provider;

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
        model = new Model();
        model.addUpdateListener(new DummyListener());
        model.update(r);
    }

    // Tests that the model is properly emptied out after a null update.
    public void testNullUpdate() {
        model.update(null);

        assertTrue(model.isEmpty());
        assertEquals(0, model.getItemCount());
        assertEquals(0, model.getModelIds().length);
    }

    // Tests that the item count is correct.
    public void testItemCount() {
        assertEquals(ITEM_COUNT, model.getItemCount());
    }

    // Tests multiple authorities with clashing document IDs.
    public void testModelIdIsUnique() {
        MatrixCursor cIn1 = new MatrixCursor(COLUMNS);
        MatrixCursor cIn2 = new MatrixCursor(COLUMNS);

        // Make two sets of items with the same IDs, under different authorities.
        final String AUTHORITY0 = "auth0";
        final String AUTHORITY1 = "auth1";

        for (int i = 0; i < ITEM_COUNT; ++i) {
            MatrixCursor.RowBuilder row0 = cIn1.newRow();
            row0.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY0);
            row0.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));

            MatrixCursor.RowBuilder row1 = cIn2.newRow();
            row1.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY1);
            row1.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
        }

        Cursor cIn = new MergeCursor(new Cursor[] { cIn1, cIn2 });

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
        String[] ids = model.getModelIds();
        assertEquals(ITEM_COUNT, ids.length);
        for (int i = 0; i < ITEM_COUNT; ++i) {
            Cursor c = model.getItem(ids[i]);
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
            assertTrue(Shared.compareToIgnoreCaseNullable(names.get(i), names.get(i+1)) <= 0);
        }
    }

    // Tests sorting by item size.
    public void testSort_sizes() {
        DirectoryResult r = new DirectoryResult();
        r.cursor = cursor;
        r.sortOrder = State.SORT_ORDER_SIZE;
        model.update(r);

        BitSet seen = new BitSet(ITEM_COUNT);
        int previousSize = Integer.MAX_VALUE;
        for (String id: model.getModelIds()) {
            Cursor c = model.getItem(id);
            seen.set(c.getPosition());
            // Check sort order - descending numerical
            int size = DocumentInfo.getCursorInt(c, Document.COLUMN_SIZE);
            assertTrue(previousSize >= size);
            previousSize = size;
        }
        // Check that all items were accounted for.
        assertEquals(ITEM_COUNT, seen.cardinality());
    }

    // Tests that directories and files are properly bucketed when sorting by size
    public void testSort_sizesWithBucketing() {
        MatrixCursor c = new MatrixCursor(COLUMNS);

        for (int i = 0; i < ITEM_COUNT; ++i) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY);
            row.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
            row.add(Document.COLUMN_SIZE, i);
            // Interleave directories and text files.
            String mimeType =(i % 2 == 0) ? Document.MIME_TYPE_DIR : "text/*";
            row.add(Document.COLUMN_MIME_TYPE, mimeType);
        }

        DirectoryResult r = new DirectoryResult();
        r.cursor = c;
        r.sortOrder = State.SORT_ORDER_SIZE;
        model.update(r);

        boolean seenAllDirs = false;
        int previousSize = Integer.MAX_VALUE;
        BitSet seen = new BitSet(ITEM_COUNT);
        // Iterate over items in sort order. Once we've encountered a document (i.e. not a
        // directory), all subsequent items must also be documents. That is, all directories are
        // bucketed at the front of the list, sorted by size, followed by documents, sorted by size.
        for (String id: model.getModelIds()) {
            Cursor cOut = model.getItem(id);
            seen.set(cOut.getPosition());

            String mimeType = DocumentInfo.getCursorString(cOut, Document.COLUMN_MIME_TYPE);
            if (seenAllDirs) {
                assertFalse(Document.MIME_TYPE_DIR.equals(mimeType));
            } else {
                if (!Document.MIME_TYPE_DIR.equals(mimeType)) {
                    seenAllDirs = true;
                    // Reset the previous size seen, because documents are bucketed separately by
                    // the sort.
                    previousSize = Integer.MAX_VALUE;
                }
            }
            // Check sort order - descending numerical
            int size = DocumentInfo.getCursorInt(c, Document.COLUMN_SIZE);
            assertTrue(previousSize >= size);
            previousSize = size;
        }

        // Check that all items were accounted for.
        assertEquals(ITEM_COUNT, seen.cardinality());
    }

    public void testSort_time() {
        final int DL_COUNT = 3;
        MatrixCursor c = new MatrixCursor(COLUMNS);
        Set<String> currentDownloads = new HashSet<>();

        // Add some files
        for (int i = 0; i < ITEM_COUNT; i++) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY);
            row.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
            row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis());
        }
        // Add some current downloads (no timestamp)
        for (int i = ITEM_COUNT; i < ITEM_COUNT + DL_COUNT; i++) {
            MatrixCursor.RowBuilder row = c.newRow();
            String id = Integer.toString(i);
            row.add(RootCursorWrapper.COLUMN_AUTHORITY, AUTHORITY);
            row.add(Document.COLUMN_DOCUMENT_ID, id);
            currentDownloads.add(id);
        }

        DirectoryResult r = new DirectoryResult();
        r.cursor = c;
        r.sortOrder = State.SORT_ORDER_LAST_MODIFIED;
        model.update(r);

        String[] ids = model.getModelIds();

        // Check that all items were accounted for
        assertEquals(ITEM_COUNT + DL_COUNT, ids.length);

        // Check that active downloads are sorted to the top.
        for (int i = 0; i < DL_COUNT; i++) {
            assertTrue(currentDownloads.contains(ids[i]));
        }
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
        String[] ids = model.getModelIds();
        Selection s = new Selection();
        // Construct a selection of the given positions.
        for (int p: positions) {
            s.add(ids[p]);
        }
        return s;
    }

    private static class DummyListener implements Model.UpdateListener {
        public void onModelUpdate(Model model) {}
        public void onModelUpdateFailed(Exception e) {}
    }
}
