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
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

public class BugreportStorageProvider extends DocumentsProvider {
    private static final String DOC_ID_ROOT = "bugreport";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private File mRoot;

    @Override
    public boolean onCreate() {
        mRoot = new File(getContext().getFilesDir(), "bugreports");
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, DOC_ID_ROOT);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_ADVANCED);
        row.add(Root.COLUMN_ICON, android.R.mipmap.sym_def_app_icon);
        row.add(Root.COLUMN_TITLE, getContext().getString(R.string.bugreport_storage_title));
        row.add(Root.COLUMN_DOCUMENT_ID, DOC_ID_ROOT);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        if (DOC_ID_ROOT.equals(documentId)) {
            final RowBuilder row = result.newRow();
            row.add(Document.COLUMN_DOCUMENT_ID, documentId);
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
            row.add(Document.COLUMN_DISPLAY_NAME, mRoot.getName());
            row.add(Document.COLUMN_LAST_MODIFIED, mRoot.lastModified());
            row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_LAST_MODIFIED);
        } else {
            addFileRow(result, getFileForDocId(documentId));
        }
        return result;
    }

    @Override
    public Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        if (DOC_ID_ROOT.equals(parentDocumentId)) {
            final File[] files = mRoot.listFiles();
            if (files != null) {
                for (File file : files) {
                    addFileRow(result, file);
                }
            }
        }
        return result;
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
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (!getFileForDocId(documentId).delete()) {
            throw new FileNotFoundException("Failed to delete: " + documentId);
        }
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    private static String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1).toLowerCase();
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private String getDocIdForFile(File file) {
        return DOC_ID_ROOT + ":" + file.getName();
    }

    private File getFileForDocId(String documentId) throws FileNotFoundException {
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

    private void addFileRow(MatrixCursor result, File file) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, getDocIdForFile(file));
        row.add(Document.COLUMN_MIME_TYPE, getTypeForName(file.getName()));
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE);
        row.add(Document.COLUMN_SIZE, file.length());
    }
}
