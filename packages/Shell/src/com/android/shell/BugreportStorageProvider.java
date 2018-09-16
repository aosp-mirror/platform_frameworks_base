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

package com.android.shell;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import com.android.internal.content.FileSystemProvider;

import java.io.File;
import java.io.FileNotFoundException;

public class BugreportStorageProvider extends FileSystemProvider {
    private static final String AUTHORITY = "com.android.shell.documents";
    private static final String DOC_ID_ROOT = "bugreport";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private File mRoot;

    @Override
    public boolean onCreate() {
        super.onCreate(DEFAULT_DOCUMENT_PROJECTION);
        mRoot = new File(getContext().getFilesDir(), "bugreports");
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, DOC_ID_ROOT);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY);
        row.add(Root.COLUMN_ICON, android.R.mipmap.sym_def_app_icon);
        row.add(Root.COLUMN_TITLE, getContext().getString(R.string.bugreport_storage_title));
        row.add(Root.COLUMN_DOCUMENT_ID, DOC_ID_ROOT);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final Cursor c = super.queryChildDocuments(parentDocumentId, projection, sortOrder);
        final Bundle extras = new Bundle();
        extras.putCharSequence(DocumentsContract.EXTRA_INFO,
                getContext().getText(R.string.bugreport_confirm));
        c.setExtras(extras);
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        if (DOC_ID_ROOT.equals(documentId)) {
            final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
            includeDefaultDocument(result);
            return result;
        } else {
            return super.queryDocument(documentId, projection);
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw new FileNotFoundException("Failed to open: " + documentId + ", mode = " + mode);
        }
        return ParcelFileDescriptor.open(getFileForDocId(documentId),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    protected Uri buildNotificationUri(String docId) {
        return DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    @Override
    protected String getDocIdForFile(File file) {
        return DOC_ID_ROOT + ":" + file.getName();
    }

    @Override
    protected File getFileForDocId(String documentId, boolean visible)
            throws FileNotFoundException {
        if (DOC_ID_ROOT.equals(documentId)) {
            return mRoot;
        } else {
            final int splitIndex = documentId.indexOf(':', 1);
            final String name = documentId.substring(splitIndex + 1);
            if (splitIndex == -1 || !DOC_ID_ROOT.equals(documentId.substring(0, splitIndex)) ||
                    !FileUtils.isValidExtFilename(name)) {
                throw new FileNotFoundException("Invalid document ID: " + documentId);
            }
            final File file = new File(mRoot, name);
            if (!file.exists()) {
                throw new FileNotFoundException("File not found: " + documentId);
            }
            return file;
        }
    }

    @Override
    protected RowBuilder includeFile(MatrixCursor result, String docId, File file)
            throws FileNotFoundException {
        RowBuilder row = super.includeFile(result, docId, file);
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE);
        return row;
    }

    private void includeDefaultDocument(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, DOC_ID_ROOT);
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        row.add(Document.COLUMN_DISPLAY_NAME, mRoot.getName());
        row.add(Document.COLUMN_LAST_MODIFIED, mRoot.lastModified());
        row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_LAST_MODIFIED);
    }
}
