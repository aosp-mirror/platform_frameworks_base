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

package com.android.documentsui;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Provider with thousands of files for testing loading time of directories in DocumentsUI.
 * It doesn't support any file operations.
 */
public class StressProvider extends DocumentsProvider {

    public static final String DEFAULT_AUTHORITY = "com.android.documentsui.stressprovider";

    // Empty root.
    public static final String STRESS_ROOT_0_ID = "STRESS_ROOT_0";

    // Root with thousands of items.
    public static final String STRESS_ROOT_1_ID = "STRESS_ROOT_1";

    private static final String STRESS_ROOT_0_DOC_ID = "STRESS_ROOT_0_DOC";
    private static final String STRESS_ROOT_1_DOC_ID = "STRESS_ROOT_1_DOC";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private String mAuthority = DEFAULT_AUTHORITY;
    private ArrayList<String> mIds = new ArrayList<>();

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        mAuthority = info.authority;
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        mIds = new ArrayList();
        for (int i = 0; i < 10000; i++) {
            mIds.add(createRandomId(i));
        }
        mIds.add(STRESS_ROOT_0_DOC_ID);
        mIds.add(STRESS_ROOT_1_DOC_ID);
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(DEFAULT_ROOT_PROJECTION);
        includeRoot(result, STRESS_ROOT_0_ID, STRESS_ROOT_0_DOC_ID);
        includeRoot(result, STRESS_ROOT_1_ID, STRESS_ROOT_1_DOC_ID);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(DEFAULT_DOCUMENT_PROJECTION);
        includeDocument(result, documentId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(DEFAULT_DOCUMENT_PROJECTION);
        if (STRESS_ROOT_1_DOC_ID.equals(parentDocumentId)) {
            for (String id : mIds) {
                includeDocument(result, id);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        throw new UnsupportedOperationException();
    }

    private void includeRoot(MatrixCursor result, String rootId, String docId) {
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, rootId);
        row.add(Root.COLUMN_FLAGS, 0);
        row.add(Root.COLUMN_TITLE, rootId);
        row.add(Root.COLUMN_DOCUMENT_ID, docId);
    }

    private void includeDocument(MatrixCursor result, String id) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, id);
        row.add(Document.COLUMN_DISPLAY_NAME, id);
        row.add(Document.COLUMN_SIZE, 0);
        row.add(Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        row.add(Document.COLUMN_FLAGS, 0);
        row.add(Document.COLUMN_LAST_MODIFIED, null);
    }

    private static String getDocumentIdForFile(File file) {
        return file.getAbsolutePath();
    }

    private String createRandomId(int index) {
        final Random random = new Random(index);
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            builder.append((char) (random.nextInt(96) + 32));
        }
        builder.append(index);  // Append a number to guarantee uniqueness.
        return builder.toString();
    }
}
