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

package com.android.documentsui.testing;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.os.AsyncTask;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "TestDocumentsProvider";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
    };

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    @Override
    public boolean onCreate() {
        resetRoots();
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));

        RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, "local");
        row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY);
        row.add(Root.COLUMN_TITLE, "TEST-Local");
        row.add(Root.COLUMN_SUMMARY, "TEST-LocalSummary");
        row.add(Root.COLUMN_DOCUMENT_ID, "doc:local");

        row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, "create");
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, "TEST-Create");
        row.add(Root.COLUMN_DOCUMENT_ID, "doc:create");

        return result;
    }

    private Map<String, Doc> mDocs = new HashMap<>();

    private Doc mLocalRoot;
    private Doc mCreateRoot;

    private Doc buildDoc(String docId, String displayName, String mimeType) {
        final Doc doc = new Doc();
        doc.docId = docId;
        doc.displayName = displayName;
        doc.mimeType = mimeType;
        mDocs.put(doc.docId, doc);
        return doc;
    }

    public void resetRoots() {
        Log.d(TAG, "resetRoots()");

        mDocs.clear();

        mLocalRoot = buildDoc("doc:local", null, Document.MIME_TYPE_DIR);

        mCreateRoot = buildDoc("doc:create", null, Document.MIME_TYPE_DIR);
        mCreateRoot.flags = Document.FLAG_DIR_SUPPORTS_CREATE;

        {
            Doc file1 = buildDoc("doc:file1", "FILE1", "mime1/file1");
            file1.contents = "fileone".getBytes();
            file1.flags = Document.FLAG_SUPPORTS_WRITE;
            mLocalRoot.children.add(file1);
            mCreateRoot.children.add(file1);
        }

        {
            Doc file2 = buildDoc("doc:file2", "FILE2", "mime2/file2");
            file2.contents = "filetwo".getBytes();
            file2.flags = Document.FLAG_SUPPORTS_WRITE;
            mLocalRoot.children.add(file2);
            mCreateRoot.children.add(file2);
        }

        Doc dir1 = buildDoc("doc:dir1", "DIR1", Document.MIME_TYPE_DIR);
        mLocalRoot.children.add(dir1);

        {
            Doc file3 = buildDoc("doc:file3", "FILE3", "mime3/file3");
            file3.contents = "filethree".getBytes();
            file3.flags = Document.FLAG_SUPPORTS_WRITE;
            dir1.children.add(file3);
        }

        Doc dir2 = buildDoc("doc:dir2", "DIR2", Document.MIME_TYPE_DIR);
        mCreateRoot.children.add(dir2);

        {
            Doc file4 = buildDoc("doc:file4", "FILE4", "mime4/file4");
            file4.contents = "filefour".getBytes();
            file4.flags = Document.FLAG_SUPPORTS_WRITE;
            dir2.children.add(file4);
        }
    }

    private static class Doc {
        public String docId;
        public int flags;
        public String displayName;
        public long size;
        public String mimeType;
        public long lastModified;
        public byte[] contents;
        public List<Doc> children = new ArrayList<>();

        public void include(MatrixCursor result) {
            final RowBuilder row = result.newRow();
            row.add(Document.COLUMN_DOCUMENT_ID, docId);
            row.add(Document.COLUMN_DISPLAY_NAME, displayName);
            row.add(Document.COLUMN_SIZE, size);
            row.add(Document.COLUMN_MIME_TYPE, mimeType);
            row.add(Document.COLUMN_FLAGS, flags);
            row.add(Document.COLUMN_LAST_MODIFIED, lastModified);
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        for (Doc doc : mDocs.get(parentDocumentId).children) {
            if (doc.docId.equals(documentId)) {
                return true;
            }
            if (Document.MIME_TYPE_DIR.equals(doc.mimeType)) {
                return isChildDocument(doc.docId, documentId);
            }
        }
        return false;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        final String docId = "doc:" + System.currentTimeMillis();
        final Doc doc = buildDoc(docId, displayName, mimeType);
        doc.flags = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_RENAME;
        mDocs.get(parentDocumentId).children.add(doc);
        return docId;
    }

    @Override
    public String renameDocument(String documentId, String displayName)
            throws FileNotFoundException {
        mDocs.get(documentId).displayName = displayName;
        return null;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        mDocs.remove(documentId);
        for (Doc doc : mDocs.values()) {
            doc.children.remove(documentId);
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        mDocs.get(documentId).include(result);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        for (Doc doc : mDocs.get(parentDocumentId).children) {
            doc.include(result);
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        final Doc doc = mDocs.get(documentId);
        if (doc == null) {
            throw new FileNotFoundException();
        }
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (mode.contains("w")) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    synchronized (doc) {
                        try {
                            final InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(
                                    pipe[0]);
                            doc.contents = readFullyNoClose(is);
                            is.close();
                            doc.notifyAll();
                        } catch (IOException e) {
                            Log.w(TAG, "Failed to stream", e);
                        }
                    }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
            return pipe[1];
        } else {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    synchronized (doc) {
                        try {
                            final OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(
                                    pipe[1]);
                            while (doc.contents == null) {
                                doc.wait();
                            }
                            os.write(doc.contents);
                            os.close();
                        } catch (IOException e) {
                            Log.w(TAG, "Failed to stream", e);
                        } catch (InterruptedException e) {
                            Log.w(TAG, "Interuppted", e);
                        }
                    }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
            return pipe[0];
        }
    }

    private static byte[] readFullyNoClose(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}
