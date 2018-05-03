/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.content;

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.provider.MediaStore;
import android.provider.MetadataReader;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * A helper class for {@link android.provider.DocumentsProvider} to perform file operations on local
 * files.
 */
public abstract class FileSystemProvider extends DocumentsProvider {

    private static final String TAG = "FileSystemProvider";

    private static final boolean LOG_INOTIFY = false;

    private String[] mDefaultProjection;

    @GuardedBy("mObservers")
    private final ArrayMap<File, DirectoryObserver> mObservers = new ArrayMap<>();

    private Handler mHandler;

    private static final String MIMETYPE_JPEG = "image/jpeg";
    private static final String MIMETYPE_JPG = "image/jpg";
    private static final String MIMETYPE_OCTET_STREAM = "application/octet-stream";

    protected abstract File getFileForDocId(String docId, boolean visible)
            throws FileNotFoundException;

    protected abstract String getDocIdForFile(File file) throws FileNotFoundException;

    protected abstract Uri buildNotificationUri(String docId);

    /**
     * Callback indicating that the given document has been modified. This gives
     * the provider a hook to invalidate cached data, such as {@code sdcardfs}.
     */
    protected void onDocIdChanged(String docId) {
        // Default is no-op
    }

    @Override
    public boolean onCreate() {
        throw new UnsupportedOperationException(
                "Subclass should override this and call onCreate(defaultDocumentProjection)");
    }

    @CallSuper
    protected void onCreate(String[] defaultProjection) {
        mHandler = new Handler();
        mDefaultProjection = defaultProjection;
    }

    @Override
    public boolean isChildDocument(String parentDocId, String docId) {
        try {
            final File parent = getFileForDocId(parentDocId).getCanonicalFile();
            final File doc = getFileForDocId(docId).getCanonicalFile();
            return FileUtils.contains(parent, doc);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to determine if " + docId + " is child of " + parentDocId + ": " + e);
        }
    }

    @Override
    public @Nullable Bundle getDocumentMetadata(String documentId)
            throws FileNotFoundException {
        File file = getFileForDocId(documentId);

        if (!file.exists()) {
            throw new FileNotFoundException("Can't find the file for documentId: " + documentId);
        }

        if (!file.isFile()) {
            Log.w(TAG, "Can't stream non-regular file. Returning empty metadata.");
            return null;
        }

        if (!file.canRead()) {
            Log.w(TAG, "Can't stream non-readable file. Returning empty metadata.");
            return null;
        }

        String mimeType = getTypeForFile(file);
        if (!MetadataReader.isSupportedMimeType(mimeType)) {
            return null;
        }

        InputStream stream = null;
        try {
            Bundle metadata = new Bundle();
            stream = new FileInputStream(file.getAbsolutePath());
            MetadataReader.getMetadata(metadata, stream, mimeType, null);
            return metadata;
        } catch (IOException e) {
            Log.e(TAG, "An error occurred retrieving the metadata", e);
            return null;
        } finally {
            IoUtils.closeQuietly(stream);
        }
    }

    protected final List<String> findDocumentPath(File parent, File doc)
            throws FileNotFoundException {

        if (!doc.exists()) {
            throw new FileNotFoundException(doc + " is not found.");
        }

        if (!FileUtils.contains(parent, doc)) {
            throw new FileNotFoundException(doc + " is not found under " + parent);
        }

        LinkedList<String> path = new LinkedList<>();
        while (doc != null && FileUtils.contains(parent, doc)) {
            path.addFirst(getDocIdForFile(doc));

            doc = doc.getParentFile();
        }

        return path;
    }

    @Override
    public String createDocument(String docId, String mimeType, String displayName)
            throws FileNotFoundException {
        displayName = FileUtils.buildValidFatFilename(displayName);

        final File parent = getFileForDocId(docId);
        if (!parent.isDirectory()) {
            throw new IllegalArgumentException("Parent document isn't a directory");
        }

        final File file = FileUtils.buildUniqueFile(parent, mimeType, displayName);
        final String childId;
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            if (!file.mkdir()) {
                throw new IllegalStateException("Failed to mkdir " + file);
            }
            childId = getDocIdForFile(file);
            onDocIdChanged(childId);
            addFolderToMediaStore(getFileForDocId(childId, true));
        } else {
            try {
                if (!file.createNewFile()) {
                    throw new IllegalStateException("Failed to touch " + file);
                }
                childId = getDocIdForFile(file);
                onDocIdChanged(childId);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to touch " + file + ": " + e);
            }
        }

        return childId;
    }

    private void addFolderToMediaStore(@Nullable File visibleFolder) {
        // visibleFolder is null if we're adding a folder to external thumb drive or SD card.
        if (visibleFolder != null) {
            assert (visibleFolder.isDirectory());

            final long token = Binder.clearCallingIdentity();

            try {
                final ContentResolver resolver = getContext().getContentResolver();
                final Uri uri = MediaStore.Files.getDirectoryUri("external");
                ContentValues values = new ContentValues();
                values.put(MediaStore.Files.FileColumns.DATA, visibleFolder.getAbsolutePath());
                resolver.insert(uri, values);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override
    public String renameDocument(String docId, String displayName) throws FileNotFoundException {
        // Since this provider treats renames as generating a completely new
        // docId, we're okay with letting the MIME type change.
        displayName = FileUtils.buildValidFatFilename(displayName);

        final File before = getFileForDocId(docId);
        final File after = FileUtils.buildUniqueFile(before.getParentFile(), displayName);
        if (!before.renameTo(after)) {
            throw new IllegalStateException("Failed to rename to " + after);
        }

        final String afterDocId = getDocIdForFile(after);
        onDocIdChanged(docId);
        onDocIdChanged(afterDocId);

        final File beforeVisibleFile = getFileForDocId(docId, true);
        final File afterVisibleFile = getFileForDocId(afterDocId, true);
        moveInMediaStore(beforeVisibleFile, afterVisibleFile);

        if (!TextUtils.equals(docId, afterDocId)) {
            scanFile(afterVisibleFile);
            return afterDocId;
        } else {
            return null;
        }
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
            String targetParentDocumentId)
            throws FileNotFoundException {
        final File before = getFileForDocId(sourceDocumentId);
        final File after = new File(getFileForDocId(targetParentDocumentId), before.getName());
        final File visibleFileBefore = getFileForDocId(sourceDocumentId, true);

        if (after.exists()) {
            throw new IllegalStateException("Already exists " + after);
        }
        if (!before.renameTo(after)) {
            throw new IllegalStateException("Failed to move to " + after);
        }

        final String docId = getDocIdForFile(after);
        onDocIdChanged(sourceDocumentId);
        onDocIdChanged(docId);
        moveInMediaStore(visibleFileBefore, getFileForDocId(docId, true));

        return docId;
    }

    private void moveInMediaStore(@Nullable File oldVisibleFile, @Nullable File newVisibleFile) {
        // visibleFolders are null if we're moving a document in external thumb drive or SD card.
        //
        // They should be all null or not null at the same time. File#renameTo() doesn't work across
        // volumes so an exception will be thrown before calling this method.
        if (oldVisibleFile != null && newVisibleFile != null) {
            final long token = Binder.clearCallingIdentity();

            try {
                final ContentResolver resolver = getContext().getContentResolver();
                final Uri externalUri = newVisibleFile.isDirectory()
                        ? MediaStore.Files.getDirectoryUri("external")
                        : MediaStore.Files.getContentUri("external");

                ContentValues values = new ContentValues();
                values.put(MediaStore.Files.FileColumns.DATA, newVisibleFile.getAbsolutePath());

                // Logic borrowed from MtpDatabase.
                // note - we are relying on a special case in MediaProvider.update() to update
                // the paths for all children in the case where this is a directory.
                final String path = oldVisibleFile.getAbsolutePath();
                resolver.update(externalUri,
                        values,
                        "_data LIKE ? AND lower(_data)=lower(?)",
                        new String[]{path, path});
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override
    public void deleteDocument(String docId) throws FileNotFoundException {
        final File file = getFileForDocId(docId);
        final File visibleFile = getFileForDocId(docId, true);

        final boolean isDirectory = file.isDirectory();
        if (isDirectory) {
            FileUtils.deleteContents(file);
        }
        if (!file.delete()) {
            throw new IllegalStateException("Failed to delete " + file);
        }

        onDocIdChanged(docId);
        removeFromMediaStore(visibleFile, isDirectory);
    }

    private void removeFromMediaStore(@Nullable File visibleFile, boolean isFolder)
            throws FileNotFoundException {
        // visibleFolder is null if we're removing a document from external thumb drive or SD card.
        if (visibleFile != null) {
            final long token = Binder.clearCallingIdentity();

            try {
                final ContentResolver resolver = getContext().getContentResolver();
                final Uri externalUri = MediaStore.Files.getContentUri("external");

                // Remove media store entries for any files inside this directory, using
                // path prefix match. Logic borrowed from MtpDatabase.
                if (isFolder) {
                    final String path = visibleFile.getAbsolutePath() + "/";
                    resolver.delete(externalUri,
                            "_data LIKE ?1 AND lower(substr(_data,1,?2))=lower(?3)",
                            new String[]{path + "%", Integer.toString(path.length()), path});
                }

                // Remove media store entry for this exact file.
                final String path = visibleFile.getAbsolutePath();
                resolver.delete(externalUri,
                        "_data LIKE ?1 AND lower(_data)=lower(?2)",
                        new String[]{path, path});
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveProjection(projection));
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {

        final File parent = getFileForDocId(parentDocumentId);
        final MatrixCursor result = new DirectoryCursor(
                resolveProjection(projection), parentDocumentId, parent);
        for (File file : parent.listFiles()) {
            includeFile(result, null, file);
        }
        return result;
    }

    /**
     * Searches documents under the given folder.
     *
     * To avoid runtime explosion only returns the at most 23 items.
     *
     * @param folder the root folder where recursive search begins
     * @param query the search condition used to match file names
     * @param projection projection of the returned cursor
     * @param exclusion absolute file paths to exclude from result
     * @return cursor containing search result
     * @throws FileNotFoundException when root folder doesn't exist or search fails
     */
    protected final Cursor querySearchDocuments(
            File folder, String query, String[] projection, Set<String> exclusion)
            throws FileNotFoundException {

        query = query.toLowerCase();
        final MatrixCursor result = new MatrixCursor(resolveProjection(projection));
        final LinkedList<File> pending = new LinkedList<>();
        pending.add(folder);
        while (!pending.isEmpty() && result.getCount() < 24) {
            final File file = pending.removeFirst();
            if (file.isDirectory()) {
                for (File child : file.listFiles()) {
                    pending.add(child);
                }
            }
            if (file.getName().toLowerCase().contains(query)
                    && !exclusion.contains(file.getAbsolutePath())) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        return getTypeForFile(file);
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final File visibleFile = getFileForDocId(documentId, true);

        final int pfdMode = ParcelFileDescriptor.parseMode(mode);
        if (pfdMode == ParcelFileDescriptor.MODE_READ_ONLY || visibleFile == null) {
            return ParcelFileDescriptor.open(file, pfdMode);
        } else {
            try {
                // When finished writing, kick off media scanner
                return ParcelFileDescriptor.open(
                        file, pfdMode, mHandler, (IOException e) -> {
                            onDocIdChanged(documentId);
                            scanFile(visibleFile);
                        });
            } catch (IOException e) {
                throw new FileNotFoundException("Failed to open for writing: " + e);
            }
        }
    }

    private void scanFile(File visibleFile) {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(visibleFile));
        getContext().sendBroadcast(intent);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, CancellationSignal signal)
            throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        return DocumentsContract.openImageThumbnail(file);
    }

    protected RowBuilder includeFile(MatrixCursor result, String docId, File file)
            throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;

        if (file.canWrite()) {
            if (file.isDirectory()) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
                flags |= Document.FLAG_SUPPORTS_DELETE;
                flags |= Document.FLAG_SUPPORTS_RENAME;
                flags |= Document.FLAG_SUPPORTS_MOVE;
            } else {
                flags |= Document.FLAG_SUPPORTS_WRITE;
                flags |= Document.FLAG_SUPPORTS_DELETE;
                flags |= Document.FLAG_SUPPORTS_RENAME;
                flags |= Document.FLAG_SUPPORTS_MOVE;
            }
        }

        final String mimeType = getTypeForFile(file);
        final String displayName = file.getName();
        if (mimeType.startsWith("image/")) {
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        if (typeSupportsMetadata(mimeType)) {
            flags |= Document.FLAG_SUPPORTS_METADATA;
        }

        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_FLAGS, flags);

        // Only publish dates reasonably after epoch
        long lastModified = file.lastModified();
        if (lastModified > 31536000000L) {
            row.add(Document.COLUMN_LAST_MODIFIED, lastModified);
        }

        // Return the row builder just in case any subclass want to add more stuff to it.
        return row;
    }

    private static String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            return getTypeForName(file.getName());
        }
    }

    protected boolean typeSupportsMetadata(String mimeType) {
        return MetadataReader.isSupportedMimeType(mimeType);
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

        return MIMETYPE_OCTET_STREAM;
    }

    protected final File getFileForDocId(String docId) throws FileNotFoundException {
        return getFileForDocId(docId, false);
    }

    private String[] resolveProjection(String[] projection) {
        return projection == null ? mDefaultProjection : projection;
    }

    private void startObserving(File file, Uri notifyUri) {
        synchronized (mObservers) {
            DirectoryObserver observer = mObservers.get(file);
            if (observer == null) {
                observer = new DirectoryObserver(
                        file, getContext().getContentResolver(), notifyUri);
                observer.startWatching();
                mObservers.put(file, observer);
            }
            observer.mRefCount++;

            if (LOG_INOTIFY) Log.d(TAG, "after start: " + observer);
        }
    }

    private void stopObserving(File file) {
        synchronized (mObservers) {
            DirectoryObserver observer = mObservers.get(file);
            if (observer == null) return;

            observer.mRefCount--;
            if (observer.mRefCount == 0) {
                mObservers.remove(file);
                observer.stopWatching();
            }

            if (LOG_INOTIFY) Log.d(TAG, "after stop: " + observer);
        }
    }

    private static class DirectoryObserver extends FileObserver {
        private static final int NOTIFY_EVENTS = ATTRIB | CLOSE_WRITE | MOVED_FROM | MOVED_TO
                | CREATE | DELETE | DELETE_SELF | MOVE_SELF;

        private final File mFile;
        private final ContentResolver mResolver;
        private final Uri mNotifyUri;

        private int mRefCount = 0;

        public DirectoryObserver(File file, ContentResolver resolver, Uri notifyUri) {
            super(file.getAbsolutePath(), NOTIFY_EVENTS);
            mFile = file;
            mResolver = resolver;
            mNotifyUri = notifyUri;
        }

        @Override
        public void onEvent(int event, String path) {
            if ((event & NOTIFY_EVENTS) != 0) {
                if (LOG_INOTIFY) Log.d(TAG, "onEvent() " + event + " at " + path);
                mResolver.notifyChange(mNotifyUri, null, false);
            }
        }

        @Override
        public String toString() {
            return "DirectoryObserver{file=" + mFile.getAbsolutePath() + ", ref=" + mRefCount + "}";
        }
    }

    private class DirectoryCursor extends MatrixCursor {
        private final File mFile;

        public DirectoryCursor(String[] columnNames, String docId, File file) {
            super(columnNames);

            final Uri notifyUri = buildNotificationUri(docId);
            setNotificationUri(getContext().getContentResolver(), notifyUri);

            mFile = file;
            startObserving(mFile, notifyUri);
        }

        @Override
        public void close() {
            super.close();
            stopObserving(mFile);
        }
    }
}
