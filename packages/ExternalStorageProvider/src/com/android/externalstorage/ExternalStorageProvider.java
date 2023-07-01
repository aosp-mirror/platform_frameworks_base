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

import static java.util.regex.Pattern.CASE_INSENSITIVE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.usage.StorageStatsManager;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.UriPermission;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.DiskInfo;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Path;
import android.provider.DocumentsContract.Root;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.FileSystemProvider;
import com.android.internal.util.IndentingPrintWriter;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Presents content of the shared (a.k.a. "external") storage.
 * <p>
 * Starting with Android 11 (R), restricts access to the certain sections of the shared storage:
 * {@code Android/data/}, {@code Android/obb/} and {@code Android/sandbox/}, that will be hidden in
 * the DocumentsUI by default.
 * See <a href="https://developer.android.com/about/versions/11/privacy/storage">
 * Storage updates in Android 11</a>.
 * <p>
 * Documents ID format: {@code root:path/to/file}.
 */
public class ExternalStorageProvider extends FileSystemProvider {
    private static final String TAG = "ExternalStorage";

    private static final boolean DEBUG = false;

    public static final String AUTHORITY = DocumentsContract.EXTERNAL_STORAGE_PROVIDER_AUTHORITY;

    private static final Uri BASE_URI =
            new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).build();

    /**
     * Regex for detecting {@code /Android/data/}, {@code /Android/obb/} and
     * {@code /Android/sandbox/} along with all their subdirectories and content.
     */
    private static final Pattern PATTERN_RESTRICTED_ANDROID_SUBTREES =
            Pattern.compile("^Android/(?:data|obb|sandbox)(?:/.+)?", CASE_INSENSITIVE);

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_QUERY_ARGS
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private static class RootInfo {
        public String rootId;
        public String volumeId;
        public UUID storageUuid;
        public int flags;
        public String title;
        public String docId;
        public File visiblePath;
        public File path;
        // TODO (b/157033915): Make getFreeBytes() faster
        public boolean reportAvailableBytes = false;
    }

    private static final String ROOT_ID_PRIMARY_EMULATED =
            DocumentsContract.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID;

    private static final String GET_DOCUMENT_URI_CALL = "get_document_uri";
    private static final String GET_MEDIA_URI_CALL = "get_media_uri";

    private StorageManager mStorageManager;
    private UserManager mUserManager;

    private final Object mRootsLock = new Object();

    @GuardedBy("mRootsLock")
    private ArrayMap<String, RootInfo> mRoots = new ArrayMap<>();

    @Override
    public boolean onCreate() {
        super.onCreate(DEFAULT_DOCUMENT_PROJECTION);

        mStorageManager = getContext().getSystemService(StorageManager.class);
        mUserManager = getContext().getSystemService(UserManager.class);

        updateVolumes();

        mStorageManager.registerListener(new StorageEventListener() {
                @Override
                public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
                    updateVolumes();
                }
            });

        return true;
    }

    private void enforceShellRestrictions() {
        if (UserHandle.getCallingAppId() == android.os.Process.SHELL_UID
                && mUserManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)) {
            throw new SecurityException(
                    "Shell user cannot access files for user " + UserHandle.myUserId());
        }
    }

    @Override
    protected int enforceReadPermissionInner(Uri uri,
            @NonNull AttributionSource attributionSource) throws SecurityException {
        enforceShellRestrictions();
        return super.enforceReadPermissionInner(uri, attributionSource);
    }

    @Override
    protected int enforceWritePermissionInner(Uri uri,
            @NonNull AttributionSource attributionSource) throws SecurityException {
        enforceShellRestrictions();
        return super.enforceWritePermissionInner(uri, attributionSource);
    }

    public void updateVolumes() {
        synchronized (mRootsLock) {
            updateVolumesLocked();
        }
    }

    @GuardedBy("mRootsLock")
    private void updateVolumesLocked() {
        mRoots.clear();

        final int userId = UserHandle.myUserId();
        final List<VolumeInfo> volumes = mStorageManager.getVolumes();
        for (VolumeInfo volume : volumes) {
            if (!volume.isMountedReadable() || volume.getMountUserId() != userId) continue;

            final String rootId;
            final String title;
            final UUID storageUuid;
            if (volume.getType() == VolumeInfo.TYPE_EMULATED) {
                // We currently only support a single emulated volume per user mounted at
                // a time, and it's always considered the primary
                if (DEBUG) Log.d(TAG, "Found primary volume: " + volume);
                rootId = ROOT_ID_PRIMARY_EMULATED;

                if (volume.isPrimaryEmulatedForUser(userId)) {
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
                    storageUuid = StorageManager.UUID_DEFAULT;
                } else {
                    // This should cover all other storage devices, like an SD card
                    // or USB OTG drive plugged in. Using getBestVolumeDescription()
                    // will give us a nice string like "Samsung SD card" or "SanDisk USB drive"
                    final VolumeInfo privateVol = mStorageManager.findPrivateForEmulated(volume);
                    title = mStorageManager.getBestVolumeDescription(privateVol);
                    storageUuid = StorageManager.convert(privateVol.fsUuid);
                }
            } else if (volume.getType() == VolumeInfo.TYPE_PUBLIC
                    || volume.getType() == VolumeInfo.TYPE_STUB) {
                rootId = volume.getFsUuid();
                title = mStorageManager.getBestVolumeDescription(volume);
                storageUuid = null;
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
            root.volumeId = volume.id;
            root.storageUuid = storageUuid;
            root.flags = Root.FLAG_LOCAL_ONLY
                    | Root.FLAG_SUPPORTS_SEARCH
                    | Root.FLAG_SUPPORTS_IS_CHILD;

            final DiskInfo disk = volume.getDisk();
            if (DEBUG) Log.d(TAG, "Disk for root " + rootId + " is " + disk);
            if (disk != null && disk.isSd()) {
                root.flags |= Root.FLAG_REMOVABLE_SD;
            } else if (disk != null && disk.isUsb()) {
                root.flags |= Root.FLAG_REMOVABLE_USB;
            }

            if (volume.getType() != VolumeInfo.TYPE_EMULATED
                    && volume.getType() != VolumeInfo.TYPE_STUB) {
                root.flags |= Root.FLAG_SUPPORTS_EJECT;
            }

            if (volume.isPrimary()) {
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
            if (volume.isVisibleForUser(userId)) {
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

        Log.d(TAG, "After updating volumes, found " + mRoots.size() + " active roots");

        // Note this affects content://com.android.externalstorage.documents/root/39BD-07C5
        // as well as content://com.android.externalstorage.documents/document/*/children,
        // so just notify on content://com.android.externalstorage.documents/.
        getContext().getContentResolver().notifyChange(BASE_URI, null, false);
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    /**
     * Mark {@code Android/data/}, {@code Android/obb/} and {@code Android/sandbox/} on the
     * integrated shared ("external") storage along with all their content and subdirectories as
     * hidden.
     */
    @Override
    protected boolean shouldHideDocument(@NonNull String documentId) {
        // Don't need to hide anything on USB drives.
        if (isOnRemovableUsbStorage(documentId)) {
            return false;
        }

        final String path = getPathFromDocId(documentId);
        return PATTERN_RESTRICTED_ANDROID_SUBTREES.matcher(path).matches();
    }

    /**
     * Check that the directory is the root of storage or blocked file from tree.
     * <p>
     * Note, that this is different from hidden documents: blocked documents <b>WILL</b> appear
     * the UI, but the user <b>WILL NOT</b> be able to select them.
     *
     * @param documentId the docId of the directory to be checked
     * @return true, should be blocked from tree. Otherwise, false.
     *
     * @see Document#FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE
     */
    @Override
    protected boolean shouldBlockDirectoryFromTree(@NonNull String documentId)
            throws FileNotFoundException {
        final File dir = getFileForDocId(documentId, false);
        // The file is null or it is not a directory
        if (dir == null || !dir.isDirectory()) {
            return false;
        }

        // Allow all directories on USB, including the root.
        if (isOnRemovableUsbStorage(documentId)) {
            return false;
        }

        // Get canonical(!) path. Note that this path will have neither leading nor training "/".
        // This the root's path will be just an empty string.
        final String path = getPathFromDocId(documentId);

        // Block the root of the storage
        if (path.isEmpty()) {
            return true;
        }

        // Block /Download/ and /Android/ folders from the tree.
        if (equalIgnoringCase(path, Environment.DIRECTORY_DOWNLOADS) ||
                equalIgnoringCase(path, Environment.DIRECTORY_ANDROID)) {
            return true;
        }

        // This shouldn't really make a difference, but just in case - let's block hidden
        // directories as well.
        if (shouldHideDocument(documentId)) {
            return true;
        }

        return false;
    }

    private boolean isOnRemovableUsbStorage(@NonNull String documentId) {
        final RootInfo rootInfo;
        try {
            rootInfo = getRootFromDocId(documentId);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Failed to determine rootInfo for docId\"" + documentId + '"');
            return false;
        }

        return (rootInfo.flags & Root.FLAG_REMOVABLE_USB) != 0;
    }

    @NonNull
    @Override
    protected String getDocIdForFile(@NonNull File file) throws FileNotFoundException {
        return getDocIdForFileMaybeCreate(file, false);
    }

    @NonNull
    private String getDocIdForFileMaybeCreate(@NonNull File file, boolean createNewDir)
            throws FileNotFoundException {
        String path = file.getAbsolutePath();

        // Find the most-specific root path
        boolean visiblePath = false;
        RootInfo mostSpecificRoot = getMostSpecificRootForPath(path, false);

        if (mostSpecificRoot == null) {
            // Try visible path if no internal path matches. MediaStore uses visible paths.
            visiblePath = true;
            mostSpecificRoot = getMostSpecificRootForPath(path, true);
        }

        if (mostSpecificRoot == null) {
            throw new FileNotFoundException("Failed to find root that contains " + path);
        }

        // Start at first char of path under root
        final String rootPath = visiblePath
                ? mostSpecificRoot.visiblePath.getAbsolutePath()
                : mostSpecificRoot.path.getAbsolutePath();
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

        return mostSpecificRoot.rootId + ':' + path;
    }

    private RootInfo getMostSpecificRootForPath(String path, boolean visible) {
        // Find the most-specific root path
        RootInfo mostSpecificRoot = null;
        String mostSpecificPath = null;
        synchronized (mRootsLock) {
            for (int i = 0; i < mRoots.size(); i++) {
                final RootInfo root = mRoots.valueAt(i);
                final File rootFile = visible ? root.visiblePath : root.path;
                if (rootFile != null) {
                    final String rootPath = rootFile.getAbsolutePath();
                    if (path.startsWith(rootPath) && (mostSpecificPath == null
                            || rootPath.length() > mostSpecificPath.length())) {
                        mostSpecificRoot = root;
                        mostSpecificPath = rootPath;
                    }
                }
            }
        }

        return mostSpecificRoot;
    }

    @Override
    protected File getFileForDocId(String docId, boolean visible) throws FileNotFoundException {
        return getFileForDocId(docId, visible, true);
    }

    private File getFileForDocId(String docId, boolean visible, boolean mustExist)
            throws FileNotFoundException {
        RootInfo root = getRootFromDocId(docId);
        return buildFile(root, docId, mustExist);
    }

    private Pair<RootInfo, File> resolveDocId(String docId) throws FileNotFoundException {
        RootInfo root = getRootFromDocId(docId);
        return Pair.create(root, buildFile(root, docId, /* mustExist */ true));
    }

    @VisibleForTesting
    static String getPathFromDocId(String docId) {
        final int splitIndex = docId.indexOf(':', 1);
        final String docIdPath = docId.substring(splitIndex + 1);

        // Canonicalize path and strip the leading "/"
        final String path;
        try {
            path = new File(docIdPath).getCanonicalPath().substring(1);
        } catch (IOException e) {
            Log.w(TAG, "Could not canonicalize \"" + docIdPath + '"');
            return "";
        }

        // Remove the trailing "/" as well.
        if (!path.isEmpty() && path.charAt(path.length() - 1) == '/') {
            return path.substring(0, path.length() - 1);
        } else {
            return path;
        }
    }

    private RootInfo getRootFromDocId(String docId) throws FileNotFoundException {
        final int splitIndex = docId.indexOf(':', 1);
        final String tag = docId.substring(0, splitIndex);

        RootInfo root;
        synchronized (mRootsLock) {
            root = mRoots.get(tag);
        }
        if (root == null) {
            throw new FileNotFoundException("No root for " + tag);
        }

        return root;
    }

    private File buildFile(RootInfo root, String docId, boolean mustExist)
            throws FileNotFoundException {
        final int splitIndex = docId.indexOf(':', 1);
        final String path = docId.substring(splitIndex + 1);

        File target = root.visiblePath != null ? root.visiblePath : root.path;
        if (target == null) {
            return null;
        }
        if (!target.exists()) {
            target.mkdirs();
        }
        try {
            target = new File(target, path).getCanonicalFile();
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to canonicalize path " + path);
        }

        if (mustExist && !target.exists()) {
            throw new FileNotFoundException("Missing file for " + docId + " at " + target);
        }
        return target;
    }

    @Override
    protected Uri buildNotificationUri(String docId) {
        return DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);
    }

    @Override
    protected void onDocIdChanged(String docId) {
        try {
            // Touch the visible path to ensure that any sdcardfs caches have
            // been updated to reflect underlying changes on disk.
            final File visiblePath = getFileForDocId(docId, true, false);
            if (visiblePath != null) {
                Os.access(visiblePath.getAbsolutePath(), OsConstants.F_OK);
            }
        } catch (FileNotFoundException | ErrnoException ignored) {
        }
    }

    @Override
    protected void onDocIdDeleted(String docId) {
        Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, docId);
        getContext().revokeUriPermission(uri, ~0);
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
                row.add(Root.COLUMN_QUERY_ARGS, SUPPORTED_QUERY_ARGS);

                long availableBytes = -1;
                if (root.reportAvailableBytes) {
                    if (root.storageUuid != null) {
                        try {
                            availableBytes = getContext()
                                    .getSystemService(StorageStatsManager.class)
                                    .getFreeBytes(root.storageUuid);
                        } catch (IOException e) {
                            Log.w(TAG, e);
                        }
                    } else {
                        availableBytes = root.path.getUsableSpace();
                    }
                }
                row.add(Root.COLUMN_AVAILABLE_BYTES, availableBytes);
            }
        }
        return result;
    }

    @Override
    public Path findDocumentPath(@Nullable String parentDocId, String childDocId)
            throws FileNotFoundException {
        final Pair<RootInfo, File> resolvedDocId = resolveDocId(childDocId);
        final RootInfo root = resolvedDocId.first;
        File child = resolvedDocId.second;

        final File rootFile = root.visiblePath != null ? root.visiblePath
                : root.path;
        final File parent = TextUtils.isEmpty(parentDocId)
                ? rootFile
                : getFileForDocId(parentDocId);

        return new Path(parentDocId == null ? root.rootId : null, findDocumentPath(parent, child));
    }

    private Uri getDocumentUri(String path, List<UriPermission> accessUriPermissions)
            throws FileNotFoundException {
        File doc = new File(path);

        final String docId = getDocIdForFile(doc);

        UriPermission docUriPermission = null;
        UriPermission treeUriPermission = null;
        for (UriPermission uriPermission : accessUriPermissions) {
            final Uri uri = uriPermission.getUri();
            if (AUTHORITY.equals(uri.getAuthority())) {
                boolean matchesRequestedDoc = false;
                if (DocumentsContract.isTreeUri(uri)) {
                    final String parentDocId = DocumentsContract.getTreeDocumentId(uri);
                    if (isChildDocument(parentDocId, docId)) {
                        treeUriPermission = uriPermission;
                        matchesRequestedDoc = true;
                    }
                } else {
                    final String candidateDocId = DocumentsContract.getDocumentId(uri);
                    if (Objects.equals(docId, candidateDocId)) {
                        docUriPermission = uriPermission;
                        matchesRequestedDoc = true;
                    }
                }

                if (matchesRequestedDoc && allowsBothReadAndWrite(uriPermission)) {
                    // This URI permission provides everything an app can get, no need to
                    // further check any other granted URI.
                    break;
                }
            }
        }

        // Full permission URI first.
        if (allowsBothReadAndWrite(treeUriPermission)) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUriPermission.getUri(), docId);
        }

        if (allowsBothReadAndWrite(docUriPermission)) {
            return docUriPermission.getUri();
        }

        // Then partial permission URI.
        if (treeUriPermission != null) {
            return DocumentsContract.buildDocumentUriUsingTree(treeUriPermission.getUri(), docId);
        }

        if (docUriPermission != null) {
            return docUriPermission.getUri();
        }

        throw new SecurityException("The app is not given any access to the document under path " +
                path + " with permissions granted in " + accessUriPermissions);
    }

    private static boolean allowsBothReadAndWrite(UriPermission permission) {
        return permission != null
                && permission.isReadPermission()
                && permission.isWritePermission();
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String[] projection, Bundle queryArgs)
            throws FileNotFoundException {
        final File parent;

        synchronized (mRootsLock) {
            RootInfo root = mRoots.get(rootId);
            parent = root.visiblePath != null ? root.visiblePath
                : root.path;
        }

        return querySearchDocuments(parent, projection, Collections.emptySet(), queryArgs);
    }

    @Override
    public void ejectRoot(String rootId) {
        final long token = Binder.clearCallingIdentity();
        RootInfo root = mRoots.get(rootId);
        if (root != null) {
            try {
                mStorageManager.unmount(root.volumeId);
            } catch (RuntimeException e) {
                throw new IllegalStateException(e);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /**
     * Print the state into the given stream.
     * Gets invoked when you run:
     * <pre>
     * adb shell dumpsys activity provider com.android.externalstorage/.ExternalStorageProvider
     * </pre>
     */
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
                case GET_DOCUMENT_URI_CALL: {
                    // All callers must go through MediaProvider
                    getContext().enforceCallingPermission(
                            android.Manifest.permission.WRITE_MEDIA_STORAGE, TAG);

                    final Uri fileUri = extras.getParcelable(DocumentsContract.EXTRA_URI);
                    final List<UriPermission> accessUriPermissions = extras
                            .getParcelableArrayList(DocumentsContract.EXTRA_URI_PERMISSIONS);

                    final String path = fileUri.getPath();
                    try {
                        final Bundle out = new Bundle();
                        final Uri uri = getDocumentUri(path, accessUriPermissions);
                        out.putParcelable(DocumentsContract.EXTRA_URI, uri);
                        return out;
                    } catch (FileNotFoundException e) {
                        throw new IllegalStateException("File in " + path + " is not found.", e);
                    }
                }
                case GET_MEDIA_URI_CALL: {
                    // All callers must go through MediaProvider
                    getContext().enforceCallingPermission(
                            android.Manifest.permission.WRITE_MEDIA_STORAGE, TAG);

                    final Uri documentUri = extras.getParcelable(DocumentsContract.EXTRA_URI);
                    final String docId = DocumentsContract.getDocumentId(documentUri);
                    try {
                        final Bundle out = new Bundle();
                        final Uri uri = Uri.fromFile(getFileForDocId(docId, true));
                        out.putParcelable(DocumentsContract.EXTRA_URI, uri);
                        return out;
                    } catch (FileNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                }
                default:
                    Log.w(TAG, "unknown method passed to call(): " + method);
            }
        }
        return bundle;
    }

    private static boolean equalIgnoringCase(@NonNull String a, @NonNull String b) {
        return TextUtils.equals(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT));
    }
}
