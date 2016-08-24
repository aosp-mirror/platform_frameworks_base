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
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Provider with thousands of files for testing loading time of directories in DocumentsUI.
 * It doesn't support any file operations.
 */
public class StressProvider extends DocumentsProvider {

    public static final String DEFAULT_AUTHORITY = "com.android.documentsui.stressprovider";

    // Empty root.
    public static final String STRESS_ROOT_0_ID = "STRESS_ROOT_0";

    // Root with thousands of directories.
    public static final String STRESS_ROOT_1_ID = "STRESS_ROOT_1";

    // Root with hundreds of files.
    public static final String STRESS_ROOT_2_ID = "STRESS_ROOT_2";

    private static final String STRESS_ROOT_0_DOC_ID = "STRESS_ROOT_0_DOC";
    private static final String STRESS_ROOT_1_DOC_ID = "STRESS_ROOT_1_DOC";
    private static final String STRESS_ROOT_2_DOC_ID = "STRESS_ROOT_2_DOC";

    private static final int STRESS_ROOT_1_ITEMS = 10000;
    private static final int STRESS_ROOT_2_ITEMS = 300;

    private static final String MIME_TYPE_IMAGE = "image/jpeg";
    private static final long REFERENCE_TIMESTAMP = 1459159369359L;

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private String mAuthority = DEFAULT_AUTHORITY;

    // Map from a root document id to children document ids.
    private Map<String, ArrayList<StubDocument>> mChildDocuments = new HashMap<>();

    private Map<String, StubDocument> mDocuments = new HashMap<>();
    private Map<String, StubRoot> mRoots = new HashMap<>();

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        mAuthority = info.authority;
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        StubDocument document;

        ArrayList<StubDocument> children = new ArrayList<StubDocument>();
        mChildDocuments.put(STRESS_ROOT_1_DOC_ID, children);
        for (int i = 0; i < STRESS_ROOT_1_ITEMS; i++) {
            document = StubDocument.createDirectory(i);
            mDocuments.put(document.id, document);
            children.add(document);
        }

        children = new ArrayList<StubDocument>();
        mChildDocuments.put(STRESS_ROOT_2_DOC_ID, children);
        for (int i = 0; i < STRESS_ROOT_2_ITEMS; i++) {
            try {
                document = StubDocument.createFile(
                        getContext(), MIME_TYPE_IMAGE,
                        com.android.documentsui.perftests.R.raw.earth_small,
                        STRESS_ROOT_1_ITEMS + i);
            } catch (IOException e) {
                return false;
            }
            mDocuments.put(document.id, document);
            children.add(document);
        }

        mRoots.put(STRESS_ROOT_0_ID, new StubRoot(STRESS_ROOT_0_ID, STRESS_ROOT_0_DOC_ID));
        mRoots.put(STRESS_ROOT_1_ID, new StubRoot(STRESS_ROOT_1_ID, STRESS_ROOT_1_DOC_ID));
        mRoots.put(STRESS_ROOT_2_ID, new StubRoot(STRESS_ROOT_2_ID, STRESS_ROOT_2_DOC_ID));

        mDocuments.put(STRESS_ROOT_0_DOC_ID, StubDocument.createDirectory(STRESS_ROOT_0_DOC_ID));
        mDocuments.put(STRESS_ROOT_1_DOC_ID, StubDocument.createDirectory(STRESS_ROOT_1_DOC_ID));
        mDocuments.put(STRESS_ROOT_2_DOC_ID, StubDocument.createDirectory(STRESS_ROOT_2_DOC_ID));

        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(DEFAULT_ROOT_PROJECTION);
        for (StubRoot root : mRoots.values()) {
            includeRoot(result, root);
        }
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(DEFAULT_DOCUMENT_PROJECTION);
        final StubDocument document = mDocuments.get(documentId);
        includeDocument(result, document);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(DEFAULT_DOCUMENT_PROJECTION);
        final ArrayList<StubDocument> childDocuments = mChildDocuments.get(parentDocumentId);
        if (childDocuments != null) {
            for (StubDocument document : childDocuments) {
                includeDocument(result, document);
            }
        }
        return result;
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String docId, Point sizeHint,
            CancellationSignal signal)
            throws FileNotFoundException {
        final StubDocument document = mDocuments.get(docId);
        return getContext().getResources().openRawResourceFd(document.thumbnail);
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode,
            CancellationSignal signal)
            throws FileNotFoundException {
        throw new UnsupportedOperationException();
    }

    private void includeRoot(MatrixCursor result, StubRoot root) {
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, root.id);
        row.add(Root.COLUMN_FLAGS, 0);
        row.add(Root.COLUMN_TITLE, root.id);
        row.add(Root.COLUMN_DOCUMENT_ID, root.documentId);
    }

    private void includeDocument(MatrixCursor result, StubDocument document) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, document.id);
        row.add(Document.COLUMN_DISPLAY_NAME, document.id);
        row.add(Document.COLUMN_SIZE, document.size);
        row.add(Document.COLUMN_MIME_TYPE, document.mimeType);
        row.add(Document.COLUMN_FLAGS,
                document.thumbnail != -1 ? Document.FLAG_SUPPORTS_THUMBNAIL : 0);
        row.add(Document.COLUMN_LAST_MODIFIED, document.lastModified);
    }

    private static String getStubDocumentIdForFile(File file) {
        return file.getAbsolutePath();
    }

    private static class StubDocument {
        final String mimeType;
        final String id;
        final int size;
        final long lastModified;
        final int thumbnail;

        private StubDocument(String mimeType, String id, int size, long lastModified,
                int thumbnail) {
            this.mimeType = mimeType;
            this.id = id;
            this.size = size;
            this.lastModified = lastModified;
            this.thumbnail = thumbnail;
        }

        public static StubDocument createDirectory(int index) {
            return new StubDocument(
                    DocumentsContract.Document.MIME_TYPE_DIR, createRandomId(index), 0,
                    createRandomTime(index), -1);
        }

        public static StubDocument createDirectory(String id) {
            return new StubDocument(DocumentsContract.Document.MIME_TYPE_DIR, id, 0, 0, -1);
        }

        public static StubDocument createFile(Context context, String mimeType, int thumbnail,
                int index) throws IOException {
            return new StubDocument(
                    mimeType, createRandomId(index), createRandomSize(index),
                    createRandomTime(index), thumbnail);
        }

        private static String createRandomId(int index) {
            final Random random = new Random(index);
            final StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                builder.append((char) (random.nextInt(96) + 32));
            }
            builder.append(index);  // Append a number to guarantee uniqueness.
            return builder.toString();
        }

        private static int createRandomSize(int index) {
            final Random random = new Random(index);
            return random.nextInt(1024 * 1024 * 100);  // Up to 100 MB.
        }

        private static long createRandomTime(int index) {
            final Random random = new Random(index);
            // Up to 30 days backwards from REFERENCE_TIMESTAMP.
            return REFERENCE_TIMESTAMP - random.nextLong() % 1000L * 60 * 60 * 24 * 30;
        }
    }

    private static class StubRoot {
        final String id;
        final String documentId;

        public StubRoot(String id, String documentId) {
            this.id = id;
            this.documentId = documentId;
        }
    }
}
