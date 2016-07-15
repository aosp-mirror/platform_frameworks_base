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
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.os.UserHandle;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.provider.MediaStore;
import android.provider.Settings;
import android.support.provider.DocumentArchiveHelper;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.List;

public class ExternalStorageProvider extends DocumentsProvider {
    private static final String TAG = "ExternalStorage";

    private static final boolean DEBUG = false;
    private static final boolean LOG_INOTIFY = false;

    public static final String AUTHORITY = "com.android.externalstorage.documents";

    private static final Uri BASE_URI =
            new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).build();

    // docId format: root:path/to/file

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private static class RootInfo {
        public String rootId;
        public int flags;
        public String title;
        public String docId;
        public File visiblePath;
        public File path;
        public boolean reportAvailableBytes = true;
    }

    private static final String ROOT_ID_PRIMARY_EMULATED = "primary";
    private static final String ROOT_ID_HOME = "home";

    private StorageManager mStorageManager;
    private Handler mHandler;
    private DocumentArchiveHelper mArchiveHelper;

    private final Object mRootsLock = new Object();

    @GuardedBy("mRootsLock")
    private ArrayMap<String, RootInfo> mRoots = new ArrayMap<>();

    @GuardedBy("mObservers")
    private ArrayMap<File, DirectoryObserver> mObservers = new ArrayMap<>();

    @Override
    public boolean onCreate() {
        mStorageManager = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
        mHandler = new Handler();
        mArchiveHelper = new DocumentArchiveHelper(this, (char) 0);

        updateVolumes();
        return true;
    }

    public void updateVolumes() {
        synchronized (mRootsLock) {
            updateVolumesLocked();
        }
    }

    private void updateVolumesLocked() {
        mRoots.clear();

        VolumeInfo primaryVolume = null;
        final int userId = UserHandle.myUserId();
        final List<VolumeInfo> volumes = mStorageManager.getVolumes();
        for (VolumeInfo volume : volumes) {
            if (!volume.isMountedReadable()) continue;

            final String rootId;
            final String title;
            if (volume.getType() == VolumeInfo.TYPE_EMULATED) {
                // We currently only support a single emulated volume mounted at
                // a time, and it's always considered the primary
                if (DEBUG) Log.d(TAG, "Found primary volume: " + volume);
                rootId = ROOT_ID_PRIMARY_EMULATED;

                if (VolumeInfo.ID_EMULATED_INTERNAL.equals(volume.getId())) {
                    // This is basically the user's primary device storage.
                    // Use device name for the volume since this is likely same thing
                    // the user sees when they mount their phone on another device.
                    String deviceName = Settings.Global.getString(
                            getContext().getContentResolver(), Settings.Global.DEVICE_NAME);

                    // Device name should always be set. In case it isn't, though,
                    // fall back to a localized "Internal Storage" string.
                    title = !TextUtils.isEmpty(deviceName)
                            ? deviceName
                            : getContext().getString(R.string.root_internal_storage);
                } else {
                    // This should cover all other storage devices, like an SD card
                    // or USB OTG drive plugged in. Using getBestVolumeDescription()
                    // will give us a nice string like "Samsung SD card" or "SanDisk USB drive"
                    final VolumeInfo privateVol = mStorageManager.findPrivateForEmulated(volume);
                    title = mStorageManager.getBestVolumeDescription(privateVol);
                }
            } else if (volume.getType() == VolumeInfo.TYPE_PUBLIC) {
                rootId = volume.getFsUuid();
                title = mStorageManager.getBestVolumeDescription(volume);
            } else {
                // Unsupported volume; ignore
                continue;
            }

            if (TextUtils.isEmpty(rootId)) {
                Log.d(TAG, "Missing UUID for " + volume.getId() + "; skipping");
                continue;
            }
            if (mRoots.containsKey(rootId)) {
                Log.w(TAG, "Duplicate UUID " + rootId + " for " + volume.getId() + "; skipping");
                continue;
            }

            final RootInfo root = new RootInfo();
            mRoots.put(rootId, root);

            root.rootId = rootId;
            root.flags = Root.FLAG_LOCAL_ONLY
                    | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD;

            final DiskInfo disk = volume.getDisk();
            if (DEBUG) Log.d(TAG, "Disk for root " + rootId + " is " + disk);
            if (disk != null && disk.isSd()) {
                root.flags |= Root.FLAG_REMOVABLE_SD;
            } else if (disk != null && disk.isUsb()) {
                root.flags |= Root.FLAG_REMOVABLE_USB;
            }

            if (volume.isPrimary()) {
                // save off the primary volume for subsequent "Home" dir initialization.
                primaryVolume = volume;
                root.flags |= Root.FLAG_ADVANCED;
            }
            // Dunno when this would NOT be the case, but never hurts to be correct.
            if (volume.isMountedWritable()) {
                root.flags |= Root.FLAG_SUPPORTS_CREATE;
            }
            root.title = title;
            if (volume.getType() == VolumeInfo.TYPE_PUBLIC) {
                root.flags |= Root.FLAG_HAS_SETTINGS;
            }
            if (volume.isVisibleForRead(userId)) {
                root.visiblePath = volume.getPathForUser(userId);
            } else {
                root.visiblePath = null;
            }
            root.path = volume.getInternalPathForUser(userId);
            try {
                root.docId = getDocIdForFile(root.path);
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }

        // Finally, if primary storage is available we add the "Documents" directory.
        // If I recall correctly the actual directory is created on demand
        // by calling either getPathForUser, or getInternalPathForUser.
        if (primaryVolume != null && primaryVolume.isVisible()) {
            final RootInfo root = new RootInfo();
            root.rootId = ROOT_ID_HOME;
            mRoots.put(root.rootId, root);
            root.title = getContext().getString(R.string.root_documents);

            // Only report bytes on *volumes*...as a matter of policy.
            root.reportAvailableBytes = false;
            root.flags = Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_SEARCH
                    | Root.FLAG_SUPPORTS_IS_CHILD;

            // Dunno when this would NOT be the case, but never hurts to be correct.
            if (primaryVolume.isMountedWritable()) {
                root.flags |= Root.FLAG_SUPPORTS_CREATE;
            }

            // Create the "Documents" directory on disk (don't use the localized title).
            root.visiblePath = new File(
                    primaryVolume.getPathForUser(userId), Environment.DIRECTORY_DOCUMENTS);
            root.path = new File(
                    primaryVolume.getInternalPathForUser(userId), Environment.DIRECTORY_DOCUMENTS);
            try {
                root.docId = getDocIdForFile(root.path);
            } catch (FileNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }

        Log.d(TAG, "After updating volumes, found " + mRoots.size() + " active roots");

        // Note this affects content://com.android.externalstorage.documents/root/39BD-07C5
        // as well as content://com.android.externalstorage.documents/document/*/children,
        // so just notify on content://com.android.externalstorage.documents/.
        getContext().getContentResolver().notifyChange(BASE_URI, null, false);
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }


    private String getDocIdForFile(File file) throws FileNotFoundException {
        return getDocIdForFileMaybeCreate(file, false);
    }

    private String getDocIdForFileMaybeCreate(File file, boolean createNewDir)
            throws FileNotFoundException {
        String path = file.getAbsolutePath();

        // Find the most-specific root path
        String mostSpecificId = null;
        String mostSpecificPath = null;
        synchronized (mRootsLock) {
            for (int i = 0; i < mRoots.size(); i++) {
                final String rootId = mRoots.keyAt(i);
                final String rootPath = mRoots.valueAt(i).path.getAbsolutePath();
                if (path.startsWith(rootPath) && (mostSpecificPath == null
                        || rootPath.length() > mostSpecificPath.length())) {
                    mostSpecificId = rootId;
                    mostSpecificPath = rootPath;
                }
            }
        }

        if (mostSpecificPath == null) {
            throw new FileNotFoundException("Failed to find root that contains " + path);
        }

        // Start at first char of path under root
        final String rootPath = mostSpecificPath;
        if (rootPath.equals(path)) {
            path = "";
        } else if (rootPath.endsWith("/")) {
            path = path.substring(rootPath.length());
        } else {
            path = path.substring(rootPath.length() + 1);
        }

        if (!file.exists() && createNewDir) {
            Log.i(TAG, "Creating new directory " + file);
            if (!file.mkdir()) {
                Log.e(TAG, "Could not create directory " + file);
            }
        }

        return mostSpecificId + ':' + path;
    }

    private File getFileForDocId(String docId) throws FileNotFoundException {
        return getFileForDocId(docId, false);
    }

    private File getFileForDocId(String docId, boolean visible) throws FileNotFoundException {
        final int splitIndex = docId.indexOf(':', 1);
        final String tag = docId.substring(0, splitIndex);
        final String path = docId.substring(splitIndex + 1);

        RootInfo root;
        synchronized (mRootsLock) {
            root = mRoots.get(tag);
        }
        if (root == null) {
            throw new FileNotFoundException("No root for " + tag);
        }

        File target = visible ? root.visiblePath : root.path;
        if (target == null) {
            return null;
        }
        if (!target.exists()) {
            target.mkdirs();
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
        if (mArchiveHelper.isSupportedArchiveType(mimeType)) {
            flags |= Document.FLAG_ARCHIVE;
        }

        final String displayName = file.getName();
        if (mimeType.startsWith("image/")) {
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(DocumentArchiveHelper.COLUMN_LOCAL_FILE_PATH, file.getPath());

        // Only publish dates reasonably after epoch
        long lastModified = file.lastModified();
        if (lastModified > 31536000000L) {
            row.add(Document.COLUMN_LAST_MODIFIED, lastModified);
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        synchronized (mRootsLock) {
            for (RootInfo root : mRoots.values()) {
                final RowBuilder row = result.newRow();
                row.add(Root.COLUMN_ROOT_ID, root.rootId);
                row.add(Root.COLUMN_FLAGS, root.flags);
                row.add(Root.COLUMN_TITLE, root.title);
                row.add(Root.COLUMN_DOCUMENT_ID, root.docId);
                row.add(Root.COLUMN_AVAILABLE_BYTES,
                        root.reportAvailableBytes ? root.path.getFreeSpace() : -1);
            }
        }
        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocId, String docId) {
        try {
            if (mArchiveHelper.isArchivedDocument(docId)) {
                return mArchiveHelper.isChildDocument(parentDocId, docId);
            }
            // Archives do not contain regular files.
            if (mArchiveHelper.isArchivedDocument(parentDocId)) {
                return false;
            }

            final File parent = getFileForDocId(parentDocId).getCanonicalFile();
            final File doc = getFileForDocId(docId).getCanonicalFile();
            return FileUtils.contains(parent, doc);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to determine if " + docId + " is child of " + parentDocId + ": " + e);
        }
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
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
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
    public String renameDocument(String docId, String displayName) throws FileNotFoundException {
        // Since this provider treats renames as generating a completely new
        // docId, we're okay with letting the MIME type change.
        displayName = FileUtils.buildValidFatFilename(displayName);

        final File before = getFileForDocId(docId);
        final File after = new File(before.getParentFile(), displayName);
        if (after.exists()) {
            throw new IllegalStateException("Already exists " + after);
        }
        if (!before.renameTo(after)) {
            throw new IllegalStateException("Failed to rename to " + after);
        }
        final String afterDocId = getDocIdForFile(after);
        if (!TextUtils.equals(docId, afterDocId)) {
            return afterDocId;
        } else {
            return null;
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

        if (visibleFile != null) {
            final ContentResolver resolver = getContext().getContentResolver();
            final Uri externalUri = MediaStore.Files.getContentUri("external");

            // Remove media store entries for any files inside this directory, using
            // path prefix match. Logic borrowed from MtpDatabase.
            if (isDirectory) {
                final String path = visibleFile.getAbsolutePath() + "/";
                resolver.delete(externalUri,
                        "_data LIKE ?1 AND lower(substr(_data,1,?2))=lower(?3)",
                        new String[] { path + "%", Integer.toString(path.length()), path });
            }

            // Remove media store entry for this exact file.
            final String path = visibleFile.getAbsolutePath();
            resolver.delete(externalUri,
                    "_data LIKE ?1 AND lower(_data)=lower(?2)",
                    new String[] { path, path });
        }
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
            String targetParentDocumentId)
            throws FileNotFoundException {
        final File before = getFileForDocId(sourceDocumentId);
        final File after = new File(getFileForDocId(targetParentDocumentId), before.getName());

        if (after.exists()) {
            throw new IllegalStateException("Already exists " + after);
        }
        if (!before.renameTo(after)) {
            throw new IllegalStateException("Failed to move to " + after);
        }
        return getDocIdForFile(after);
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        if (mArchiveHelper.isArchivedDocument(documentId)) {
            return mArchiveHelper.queryDocument(documentId, projection);
        }

        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (mArchiveHelper.isArchivedDocument(parentDocumentId) ||
                mArchiveHelper.isSupportedArchiveType(getDocumentType(parentDocumentId))) {
            return mArchiveHelper.queryChildDocuments(parentDocumentId, projection, sortOrder);
        }

        final File parent = getFileForDocId(parentDocumentId);
        final MatrixCursor result = new DirectoryCursor(
                resolveDocumentProjection(projection), parentDocumentId, parent);
        for (File file : parent.listFiles()) {
            includeFile(result, null, file);
        }
        return result;
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));

        final File parent;
        synchronized (mRootsLock) {
            parent = mRoots.get(rootId).path;
        }

        final LinkedList<File> pending = new LinkedList<File>();
        pending.add(parent);
        while (!pending.isEmpty() && result.getCount() < 24) {
            final File file = pending.removeFirst();
            if (file.isDirectory()) {
                for (File child : file.listFiles()) {
                    pending.add(child);
                }
            }
            if (file.getName().toLowerCase().contains(query)) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        if (mArchiveHelper.isArchivedDocument(documentId)) {
            return mArchiveHelper.getDocumentType(documentId);
        }

        final File file = getFileForDocId(documentId);
        return getTypeForFile(file);
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (mArchiveHelper.isArchivedDocument(documentId)) {
            return mArchiveHelper.openDocument(documentId, mode, signal);
        }

        final File file = getFileForDocId(documentId);
        final File visibleFile = getFileForDocId(documentId, true);

        final int pfdMode = ParcelFileDescriptor.parseMode(mode);
        if (pfdMode == ParcelFileDescriptor.MODE_READ_ONLY || visibleFile == null) {
            return ParcelFileDescriptor.open(file, pfdMode);
        } else {
            try {
                // When finished writing, kick off media scanner
                return ParcelFileDescriptor.open(file, pfdMode, mHandler, new OnCloseListener() {
                    @Override
                    public void onClose(IOException e) {
                        final Intent intent = new Intent(
                                Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        intent.setData(Uri.fromFile(visibleFile));
                        getContext().sendBroadcast(intent);
                    }
                });
            } catch (IOException e) {
                throw new FileNotFoundException("Failed to open for writing: " + e);
            }
        }
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, CancellationSignal signal)
            throws FileNotFoundException {
        if (mArchiveHelper.isArchivedDocument(documentId)) {
            return mArchiveHelper.openDocumentThumbnail(documentId, sizeHint, signal);
        }

        final File file = getFileForDocId(documentId);
        return DocumentsContract.openImageThumbnail(file);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ", 160);
        synchronized (mRootsLock) {
            for (int i = 0; i < mRoots.size(); i++) {
                final RootInfo root = mRoots.valueAt(i);
                pw.println("Root{" + root.rootId + "}:");
                pw.increaseIndent();
                pw.printPair("flags", DebugUtils.flagsToString(Root.class, "FLAG_", root.flags));
                pw.println();
                pw.printPair("title", root.title);
                pw.printPair("docId", root.docId);
                pw.println();
                pw.printPair("path", root.path);
                pw.printPair("visiblePath", root.visiblePath);
                pw.decreaseIndent();
                pw.println();
            }
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle bundle = super.call(method, arg, extras);
        if (bundle == null && !TextUtils.isEmpty(method)) {
            switch (method) {
                case "getDocIdForFileCreateNewDir": {
                    getContext().enforceCallingPermission(
                            android.Manifest.permission.MANAGE_DOCUMENTS, null);
                    if (TextUtils.isEmpty(arg)) {
                        return null;
                    }
                    try {
                        final String docId = getDocIdForFileMaybeCreate(new File(arg), true);
                        bundle = new Bundle();
                        bundle.putString("DOC_ID", docId);
                    } catch (FileNotFoundException e) {
                        Log.w(TAG, "file '" + arg + "' not found");
                        return null;
                    }
                    break;
                }
                default:
                    Log.w(TAG, "unknown method passed to call(): " + method);
            }
        }
        return bundle;
    }

    private static String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            return getTypeForName(file.getName());
        }
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

            final Uri notifyUri = DocumentsContract.buildChildDocumentsUri(
                    AUTHORITY, docId);
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
