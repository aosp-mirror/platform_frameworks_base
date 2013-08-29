/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.externalstorage;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Point;
import android.media.ExifInterface;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.DocumentRoot;
import android.provider.DocumentsContract.Documents;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ExternalStorageProvider extends DocumentsProvider {
    private static final String TAG = "ExternalStorage";

    // docId format: root:path/to/file

    private static final String[] SUPPORTED_COLUMNS = new String[] {
            DocumentColumns.DOC_ID, DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE,
            DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS
    };

    private ArrayList<DocumentRoot> mRoots;
    private HashMap<String, DocumentRoot> mTagToRoot;
    private HashMap<String, File> mTagToPath;

    @Override
    public boolean onCreate() {
        mRoots = Lists.newArrayList();
        mTagToRoot = Maps.newHashMap();
        mTagToPath = Maps.newHashMap();

        // TODO: support multiple storage devices

        try {
            final String tag = "primary";
            final File path = Environment.getExternalStorageDirectory();
            mTagToPath.put(tag, path);

            final DocumentRoot root = new DocumentRoot();
            root.docId = getDocIdForFile(path);
            root.rootType = DocumentRoot.ROOT_TYPE_DEVICE_ADVANCED;
            root.title = getContext().getString(R.string.root_internal_storage);
            root.icon = R.drawable.ic_pdf;
            root.flags = DocumentRoot.FLAG_LOCAL_ONLY;
            mRoots.add(root);
            mTagToRoot.put(tag, root);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        }

        return true;
    }

    private String getDocIdForFile(File file) throws FileNotFoundException {
        String path = file.getAbsolutePath();

        // Find the most-specific root path
        Map.Entry<String, File> mostSpecific = null;
        for (Map.Entry<String, File> root : mTagToPath.entrySet()) {
            final String rootPath = root.getValue().getPath();
            if (path.startsWith(rootPath) && (mostSpecific == null
                    || rootPath.length() > mostSpecific.getValue().getPath().length())) {
                mostSpecific = root;
            }
        }

        if (mostSpecific == null) {
            throw new FileNotFoundException("Failed to find root that contains " + path);
        }

        // Start at first char of path under root
        final String rootPath = mostSpecific.getValue().getPath();
        if (rootPath.equals(path)) {
            path = "";
        } else if (rootPath.endsWith("/")) {
            path = path.substring(rootPath.length());
        } else {
            path = path.substring(rootPath.length() + 1);
        }

        return mostSpecific.getKey() + ':' + path;
    }

    private File getFileForDocId(String docId) throws FileNotFoundException {
        final int splitIndex = docId.indexOf(':', 1);
        final String tag = docId.substring(0, splitIndex);
        final String path = docId.substring(splitIndex + 1);

        File target = mTagToPath.get(tag);
        if (target == null) {
            throw new FileNotFoundException("No root for " + tag);
        }
        target = new File(target, path);
        if (!target.exists()) {
            throw new FileNotFoundException("Missing file for " + docId + " at " + target);
        }
        return target;
    }

    private void includeFile(MatrixCursor result, String docId, File file)
            throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;

        if (file.isDirectory()) {
            flags |= Documents.FLAG_SUPPORTS_SEARCH;
        }
        if (file.isDirectory() && file.canWrite()) {
            flags |= Documents.FLAG_SUPPORTS_CREATE;
        }
        if (file.canWrite()) {
            flags |= Documents.FLAG_SUPPORTS_WRITE;
            flags |= Documents.FLAG_SUPPORTS_RENAME;
            flags |= Documents.FLAG_SUPPORTS_DELETE;
        }

        final String displayName = file.getName();
        final String mimeType = getTypeForFile(file);
        if (mimeType.startsWith("image/")) {
            flags |= Documents.FLAG_SUPPORTS_THUMBNAIL;
        }

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, displayName);
        row.offer(DocumentColumns.SIZE, file.length());
        row.offer(DocumentColumns.MIME_TYPE, mimeType);
        row.offer(DocumentColumns.LAST_MODIFIED, file.lastModified());
        row.offer(DocumentColumns.FLAGS, flags);
    }

    @Override
    public List<DocumentRoot> getDocumentRoots() {
        // Update free space
        for (String tag : mTagToRoot.keySet()) {
            final DocumentRoot root = mTagToRoot.get(tag);
            final File path = mTagToPath.get(tag);
            root.availableBytes = path.getFreeSpace();
        }
        return mRoots;
    }

    @Override
    public String createDocument(String docId, String mimeType, String displayName)
            throws FileNotFoundException {
        final File parent = getFileForDocId(docId);
        displayName = validateDisplayName(mimeType, displayName);

        final File file = new File(parent, displayName);
        if (Documents.MIME_TYPE_DIR.equals(mimeType)) {
            if (!file.mkdir()) {
                throw new IllegalStateException("Failed to mkdir " + file);
            }
        } else {
            try {
                if (!file.createNewFile()) {
                    throw new IllegalStateException("Failed to touch " + file);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to touch " + file + ": " + e);
            }
        }
        return getDocIdForFile(file);
    }

    @Override
    public void renameDocument(String docId, String displayName) throws FileNotFoundException {
        final File file = getFileForDocId(docId);
        final File newFile = new File(file.getParentFile(), displayName);
        if (!file.renameTo(newFile)) {
            throw new IllegalStateException("Failed to rename " + docId);
        }
        // TODO: update any outstanding grants
    }

    @Override
    public void deleteDocument(String docId) throws FileNotFoundException {
        final File file = getFileForDocId(docId);
        if (!file.delete()) {
            throw new IllegalStateException("Failed to delete " + file);
        }
    }

    @Override
    public Cursor queryDocument(String docId) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(SUPPORTED_COLUMNS);
        includeFile(result, docId, null);
        return result;
    }

    @Override
    public Cursor queryDocumentChildren(String docId) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(SUPPORTED_COLUMNS);
        final File parent = getFileForDocId(docId);
        for (File file : parent.listFiles()) {
            includeFile(result, null, file);
        }
        return result;
    }

    @Override
    public Cursor querySearch(String docId, String query) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(SUPPORTED_COLUMNS);
        final File parent = getFileForDocId(docId);

        final LinkedList<File> pending = new LinkedList<File>();
        pending.add(parent);
        while (!pending.isEmpty() && result.getCount() < 20) {
            final File file = pending.removeFirst();
            if (file.isDirectory()) {
                for (File child : file.listFiles()) {
                    pending.add(child);
                }
            } else {
                if (file.getName().toLowerCase().contains(query)) {
                    includeFile(result, null, file);
                }
            }
        }
        return result;
    }

    @Override
    public String getType(String docId) throws FileNotFoundException {
        final File file = getFileForDocId(docId);
        return getTypeForFile(file);
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        final File file = getFileForDocId(docId);
        return ParcelFileDescriptor.open(file, ContentResolver.modeToMode(null, mode));
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(docId);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_READ_ONLY);

        try {
            final ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            final long[] thumb = exif.getThumbnailRange();
            if (thumb != null) {
                return new AssetFileDescriptor(pfd, thumb[0], thumb[1]);
            }
        } catch (IOException e) {
        }

        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    private static String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return Documents.MIME_TYPE_DIR;
        } else {
            return getTypeForName(file.getName());
        }
    }

    private static String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }

        return "application/octet-stream";
    }

    private static String validateDisplayName(String mimeType, String displayName) {
        if (Documents.MIME_TYPE_DIR.equals(mimeType)) {
            return displayName;
        } else {
            // Try appending meaningful extension if needed
            if (!mimeType.equals(getTypeForName(displayName))) {
                final String extension = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mimeType);
                if (extension != null) {
                    displayName += "." + extension;
                }
            }

            return displayName;
        }
    }
}
