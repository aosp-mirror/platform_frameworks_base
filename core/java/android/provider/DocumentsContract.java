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

package android.provider;

import static android.net.TrafficStats.KB_IN_BYTES;
import static android.system.OsConstants.SEEK_SET;

import android.annotation.Nullable;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.os.RemoteException;
import android.os.storage.StorageVolume;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Defines the contract between a documents provider and the platform.
 * <p>
 * To create a document provider, extend {@link DocumentsProvider}, which
 * provides a foundational implementation of this contract.
 * <p>
 * All client apps must hold a valid URI permission grant to access documents,
 * typically issued when a user makes a selection through
 * {@link Intent#ACTION_OPEN_DOCUMENT}, {@link Intent#ACTION_CREATE_DOCUMENT},
 * {@link Intent#ACTION_OPEN_DOCUMENT_TREE}, or
 * {@link StorageVolume#createAccessIntent(String) StorageVolume.createAccessIntent}.
 *
 * @see DocumentsProvider
 */
public final class DocumentsContract {
    private static final String TAG = "DocumentsContract";

    // content://com.example/root/
    // content://com.example/root/sdcard/
    // content://com.example/root/sdcard/recent/
    // content://com.example/root/sdcard/search/?query=pony
    // content://com.example/document/12/
    // content://com.example/document/12/children/
    // content://com.example/tree/12/document/24/
    // content://com.example/tree/12/document/24/children/

    private DocumentsContract() {
    }

    /**
     * Intent action used to identify {@link DocumentsProvider} instances. This
     * is used in the {@code <intent-filter>} of a {@code <provider>}.
     */
    public static final String PROVIDER_INTERFACE = "android.content.action.DOCUMENTS_PROVIDER";

    /** {@hide} */
    public static final String EXTRA_PACKAGE_NAME = "android.content.extra.PACKAGE_NAME";

    /** {@hide} */
    public static final String EXTRA_SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED";

    /** {@hide} */
    public static final String EXTRA_SHOW_FILESIZE = "android.content.extra.SHOW_FILESIZE";

    /** {@hide} */
    public static final String EXTRA_FANCY_FEATURES = "android.content.extra.FANCY";

    /** {@hide} */
    public static final String EXTRA_TARGET_URI = "android.content.extra.TARGET_URI";

    /**
     * Set this in a DocumentsUI intent to cause a package's own roots to be
     * excluded from the roots list.
     */
    public static final String EXTRA_EXCLUDE_SELF = "android.provider.extra.EXCLUDE_SELF";

    /**
     * Included in {@link AssetFileDescriptor#getExtras()} when returned
     * thumbnail should be rotated.
     *
     * @see MediaStore.Images.ImageColumns#ORIENTATION
     */
    public static final String EXTRA_ORIENTATION = "android.provider.extra.ORIENTATION";

    /**
     * Overrides the default prompt text in DocumentsUI when set in an intent.
     */
    public static final String EXTRA_PROMPT = "android.provider.extra.PROMPT";

    /** {@hide} */
    public static final String ACTION_MANAGE_DOCUMENT = "android.provider.action.MANAGE_DOCUMENT";

    /** {@hide} */
    public static final String ACTION_BROWSE = "android.provider.action.BROWSE";

    /** {@hide} */
    public static final String
            ACTION_DOCUMENT_ROOT_SETTINGS = "android.provider.action.DOCUMENT_ROOT_SETTINGS";

    /**
     * Buffer is large enough to rewind past any EXIF headers.
     */
    private static final int THUMBNAIL_BUFFER_SIZE = (int) (128 * KB_IN_BYTES);

    /** {@hide} */
    public static final String PACKAGE_DOCUMENTS_UI = "com.android.documentsui";

    /**
     * Constants related to a document, including {@link Cursor} column names
     * and flags.
     * <p>
     * A document can be either an openable stream (with a specific MIME type),
     * or a directory containing additional documents (with the
     * {@link #MIME_TYPE_DIR} MIME type). A directory represents the top of a
     * subtree containing zero or more documents, which can recursively contain
     * even more documents and directories.
     * <p>
     * All columns are <em>read-only</em> to client applications.
     */
    public final static class Document {
        private Document() {
        }

        /**
         * Unique ID of a document. This ID is both provided by and interpreted
         * by a {@link DocumentsProvider}, and should be treated as an opaque
         * value by client applications. This column is required.
         * <p>
         * Each document must have a unique ID within a provider, but that
         * single document may be included as a child of multiple directories.
         * <p>
         * A provider must always return durable IDs, since they will be used to
         * issue long-term URI permission grants when an application interacts
         * with {@link Intent#ACTION_OPEN_DOCUMENT} and
         * {@link Intent#ACTION_CREATE_DOCUMENT}.
         * <p>
         * Type: STRING
         */
        public static final String COLUMN_DOCUMENT_ID = "document_id";

        /**
         * Concrete MIME type of a document. For example, "image/png" or
         * "application/pdf" for openable files. A document can also be a
         * directory containing additional documents, which is represented with
         * the {@link #MIME_TYPE_DIR} MIME type. This column is required.
         * <p>
         * Type: STRING
         *
         * @see #MIME_TYPE_DIR
         */
        public static final String COLUMN_MIME_TYPE = "mime_type";

        /**
         * Display name of a document, used as the primary title displayed to a
         * user. This column is required.
         * <p>
         * Type: STRING
         */
        public static final String COLUMN_DISPLAY_NAME = OpenableColumns.DISPLAY_NAME;

        /**
         * Summary of a document, which may be shown to a user. This column is
         * optional, and may be {@code null}.
         * <p>
         * Type: STRING
         */
        public static final String COLUMN_SUMMARY = "summary";

        /**
         * Timestamp when a document was last modified, in milliseconds since
         * January 1, 1970 00:00:00.0 UTC. This column is required, and may be
         * {@code null} if unknown. A {@link DocumentsProvider} can update this
         * field using events from {@link OnCloseListener} or other reliable
         * {@link ParcelFileDescriptor} transports.
         * <p>
         * Type: INTEGER (long)
         *
         * @see System#currentTimeMillis()
         */
        public static final String COLUMN_LAST_MODIFIED = "last_modified";

        /**
         * Specific icon resource ID for a document. This column is optional,
         * and may be {@code null} to use a platform-provided default icon based
         * on {@link #COLUMN_MIME_TYPE}.
         * <p>
         * Type: INTEGER (int)
         */
        public static final String COLUMN_ICON = "icon";

        /**
         * Flags that apply to a document. This column is required.
         * <p>
         * Type: INTEGER (int)
         *
         * @see #FLAG_SUPPORTS_WRITE
         * @see #FLAG_SUPPORTS_DELETE
         * @see #FLAG_SUPPORTS_THUMBNAIL
         * @see #FLAG_DIR_PREFERS_GRID
         * @see #FLAG_DIR_PREFERS_LAST_MODIFIED
         * @see #FLAG_VIRTUAL_DOCUMENT
         * @see #FLAG_SUPPORTS_COPY
         * @see #FLAG_SUPPORTS_MOVE
         * @see #FLAG_SUPPORTS_REMOVE
         */
        public static final String COLUMN_FLAGS = "flags";

        /**
         * Size of a document, in bytes, or {@code null} if unknown. This column
         * is required.
         * <p>
         * Type: INTEGER (long)
         */
        public static final String COLUMN_SIZE = OpenableColumns.SIZE;

        /**
         * MIME type of a document which is a directory that may contain
         * additional documents.
         *
         * @see #COLUMN_MIME_TYPE
         */
        public static final String MIME_TYPE_DIR = "vnd.android.document/directory";

        /**
         * Flag indicating that a document can be represented as a thumbnail.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#getDocumentThumbnail(ContentResolver, Uri,
         *      Point, CancellationSignal)
         * @see DocumentsProvider#openDocumentThumbnail(String, Point,
         *      android.os.CancellationSignal)
         */
        public static final int FLAG_SUPPORTS_THUMBNAIL = 1;

        /**
         * Flag indicating that a document supports writing.
         * <p>
         * When a document is opened with {@link Intent#ACTION_OPEN_DOCUMENT},
         * the calling application is granted both
         * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} and
         * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION}. However, the actual
         * writability of a document may change over time, for example due to
         * remote access changes. This flag indicates that a document client can
         * expect {@link ContentResolver#openOutputStream(Uri)} to succeed.
         *
         * @see #COLUMN_FLAGS
         */
        public static final int FLAG_SUPPORTS_WRITE = 1 << 1;

        /**
         * Flag indicating that a document is deletable.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#deleteDocument(ContentResolver, Uri)
         * @see DocumentsProvider#deleteDocument(String)
         */
        public static final int FLAG_SUPPORTS_DELETE = 1 << 2;

        /**
         * Flag indicating that a document is a directory that supports creation
         * of new files within it. Only valid when {@link #COLUMN_MIME_TYPE} is
         * {@link #MIME_TYPE_DIR}.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsProvider#createDocument(String, String, String)
         */
        public static final int FLAG_DIR_SUPPORTS_CREATE = 1 << 3;

        /**
         * Flag indicating that a directory prefers its contents be shown in a
         * larger format grid. Usually suitable when a directory contains mostly
         * pictures. Only valid when {@link #COLUMN_MIME_TYPE} is
         * {@link #MIME_TYPE_DIR}.
         *
         * @see #COLUMN_FLAGS
         */
        public static final int FLAG_DIR_PREFERS_GRID = 1 << 4;

        /**
         * Flag indicating that a directory prefers its contents be sorted by
         * {@link #COLUMN_LAST_MODIFIED}. Only valid when
         * {@link #COLUMN_MIME_TYPE} is {@link #MIME_TYPE_DIR}.
         *
         * @see #COLUMN_FLAGS
         */
        public static final int FLAG_DIR_PREFERS_LAST_MODIFIED = 1 << 5;

        /**
         * Flag indicating that a document can be renamed.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#renameDocument(ContentResolver, Uri,
         *      String)
         * @see DocumentsProvider#renameDocument(String, String)
         */
        public static final int FLAG_SUPPORTS_RENAME = 1 << 6;

        /**
         * Flag indicating that a document can be copied to another location
         * within the same document provider.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#copyDocument(ContentResolver, Uri, Uri)
         * @see DocumentsProvider#copyDocument(String, String)
         */
        public static final int FLAG_SUPPORTS_COPY = 1 << 7;

        /**
         * Flag indicating that a document can be moved to another location
         * within the same document provider.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#moveDocument(ContentResolver, Uri, Uri, Uri)
         * @see DocumentsProvider#moveDocument(String, String, String)
         */
        public static final int FLAG_SUPPORTS_MOVE = 1 << 8;

        /**
         * Flag indicating that a document is virtual, and doesn't have byte
         * representation in the MIME type specified as {@link #COLUMN_MIME_TYPE}.
         *
         * @see #COLUMN_FLAGS
         * @see #COLUMN_MIME_TYPE
         * @see DocumentsProvider#openTypedDocument(String, String, Bundle,
         *      android.os.CancellationSignal)
         * @see DocumentsProvider#getDocumentStreamTypes(String, String)
         */
        public static final int FLAG_VIRTUAL_DOCUMENT = 1 << 9;

        /**
         * Flag indicating that a document can be removed from a parent.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#removeDocument(ContentResolver, Uri, Uri)
         * @see DocumentsProvider#removeDocument(String, String)
         */
        public static final int FLAG_SUPPORTS_REMOVE = 1 << 10;

        /**
         * Flag indicating that a document is an archive, and it's contents can be
         * obtained via {@link DocumentsProvider#queryChildDocuments}.
         * <p>
         * The <em>provider</em> support library offers utility classes to add common
         * archive support.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsProvider#queryChildDocuments(String, String[], String)
         * @hide
         */
        public static final int FLAG_ARCHIVE = 1 << 15;

        /**
         * Flag indicating that a document is not complete, likely its
         * contents are being downloaded. Partial files cannot be opened,
         * copied, moved in the UI. But they can be deleted and retried
         * if they represent a failed download.
         *
         * @see #COLUMN_FLAGS
         * @hide
         */
        public static final int FLAG_PARTIAL = 1 << 16;
    }

    /**
     * Constants related to a root of documents, including {@link Cursor} column
     * names and flags. A root is the start of a tree of documents, such as a
     * physical storage device, or an account. Each root starts at the directory
     * referenced by {@link Root#COLUMN_DOCUMENT_ID}, which can recursively
     * contain both documents and directories.
     * <p>
     * All columns are <em>read-only</em> to client applications.
     */
    public final static class Root {
        private Root() {
        }

        /**
         * Unique ID of a root. This ID is both provided by and interpreted by a
         * {@link DocumentsProvider}, and should be treated as an opaque value
         * by client applications. This column is required.
         * <p>
         * Type: STRING
         */
        public static final String COLUMN_ROOT_ID = "root_id";

        /**
         * Flags that apply to a root. This column is required.
         * <p>
         * Type: INTEGER (int)
         *
         * @see #FLAG_LOCAL_ONLY
         * @see #FLAG_SUPPORTS_CREATE
         * @see #FLAG_SUPPORTS_RECENTS
         * @see #FLAG_SUPPORTS_SEARCH
         */
        public static final String COLUMN_FLAGS = "flags";

        /**
         * Icon resource ID for a root. This column is required.
         * <p>
         * Type: INTEGER (int)
         */
        public static final String COLUMN_ICON = "icon";

        /**
         * Title for a root, which will be shown to a user. This column is
         * required. For a single storage service surfacing multiple accounts as
         * different roots, this title should be the name of the service.
         * <p>
         * Type: STRING
         */
        public static final String COLUMN_TITLE = "title";

        /**
         * Summary for this root, which may be shown to a user. This column is
         * optional, and may be {@code null}. For a single storage service
         * surfacing multiple accounts as different roots, this summary should
         * be the name of the account.
         * <p>
         * Type: STRING
         */
        public static final String COLUMN_SUMMARY = "summary";

        /**
         * Document which is a directory that represents the top directory of
         * this root. This column is required.
         * <p>
         * Type: STRING
         *
         * @see Document#COLUMN_DOCUMENT_ID
         */
        public static final String COLUMN_DOCUMENT_ID = "document_id";

        /**
         * Number of bytes available in this root. This column is optional, and
         * may be {@code null} if unknown or unbounded.
         * <p>
         * Type: INTEGER (long)
         */
        public static final String COLUMN_AVAILABLE_BYTES = "available_bytes";

        /**
         * Capacity of a root in bytes. This column is optional, and may be
         * {@code null} if unknown or unbounded.
         * <p>
         * Type: INTEGER (long)
         */
        public static final String COLUMN_CAPACITY_BYTES = "capacity_bytes";

        /**
         * MIME types supported by this root. This column is optional, and if
         * {@code null} the root is assumed to support all MIME types. Multiple
         * MIME types can be separated by a newline. For example, a root
         * supporting audio might return "audio/*\napplication/x-flac".
         * <p>
         * Type: STRING
         */
        public static final String COLUMN_MIME_TYPES = "mime_types";

        /** {@hide} */
        public static final String MIME_TYPE_ITEM = "vnd.android.document/root";

        /**
         * Flag indicating that at least one directory under this root supports
         * creating content. Roots with this flag will be shown when an
         * application interacts with {@link Intent#ACTION_CREATE_DOCUMENT}.
         *
         * @see #COLUMN_FLAGS
         */
        public static final int FLAG_SUPPORTS_CREATE = 1;

        /**
         * Flag indicating that this root offers content that is strictly local
         * on the device. That is, no network requests are made for the content.
         *
         * @see #COLUMN_FLAGS
         * @see Intent#EXTRA_LOCAL_ONLY
         */
        public static final int FLAG_LOCAL_ONLY = 1 << 1;

        /**
         * Flag indicating that this root can be queried to provide recently
         * modified documents.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#buildRecentDocumentsUri(String, String)
         * @see DocumentsProvider#queryRecentDocuments(String, String[])
         */
        public static final int FLAG_SUPPORTS_RECENTS = 1 << 2;

        /**
         * Flag indicating that this root supports search.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#buildSearchDocumentsUri(String, String,
         *      String)
         * @see DocumentsProvider#querySearchDocuments(String, String,
         *      String[])
         */
        public static final int FLAG_SUPPORTS_SEARCH = 1 << 3;

        /**
         * Flag indicating that this root supports testing parent child
         * relationships.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsProvider#isChildDocument(String, String)
         */
        public static final int FLAG_SUPPORTS_IS_CHILD = 1 << 4;

        /**
         * Flag indicating that this root is currently empty. This may be used
         * to hide the root when opening documents, but the root will still be
         * shown when creating documents and {@link #FLAG_SUPPORTS_CREATE} is
         * also set. If the value of this flag changes, such as when a root
         * becomes non-empty, you must send a content changed notification for
         * {@link DocumentsContract#buildRootsUri(String)}.
         *
         * @see #COLUMN_FLAGS
         * @see ContentResolver#notifyChange(Uri,
         *      android.database.ContentObserver, boolean)
         * @hide
         */
        public static final int FLAG_EMPTY = 1 << 16;

        /**
         * Flag indicating that this root should only be visible to advanced
         * users.
         *
         * @see #COLUMN_FLAGS
         * @hide
         */
        public static final int FLAG_ADVANCED = 1 << 17;

        /**
         * Flag indicating that this root has settings.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#ACTION_DOCUMENT_ROOT_SETTINGS
         * @hide
         */
        public static final int FLAG_HAS_SETTINGS = 1 << 18;

        /**
         * Flag indicating that this root is on removable SD card storage.
         *
         * @see #COLUMN_FLAGS
         * @hide
         */
        public static final int FLAG_REMOVABLE_SD = 1 << 19;

        /**
         * Flag indicating that this root is on removable USB storage.
         *
         * @see #COLUMN_FLAGS
         * @hide
         */
        public static final int FLAG_REMOVABLE_USB = 1 << 20;
    }

    /**
     * Optional boolean flag included in a directory {@link Cursor#getExtras()}
     * indicating that a document provider is still loading data. For example, a
     * provider has returned some results, but is still waiting on an
     * outstanding network request. The provider must send a content changed
     * notification when loading is finished.
     *
     * @see ContentResolver#notifyChange(Uri, android.database.ContentObserver,
     *      boolean)
     */
    public static final String EXTRA_LOADING = "loading";

    /**
     * Optional string included in a directory {@link Cursor#getExtras()}
     * providing an informational message that should be shown to a user. For
     * example, a provider may wish to indicate that not all documents are
     * available.
     */
    public static final String EXTRA_INFO = "info";

    /**
     * Optional string included in a directory {@link Cursor#getExtras()}
     * providing an error message that should be shown to a user. For example, a
     * provider may wish to indicate that a network error occurred. The user may
     * choose to retry, resulting in a new query.
     */
    public static final String EXTRA_ERROR = "error";

    /**
     * Optional result (I'm thinking boolean) answer to a question.
     * {@hide}
     */
    public static final String EXTRA_RESULT = "result";

    /** {@hide} */
    public static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
    /** {@hide} */
    public static final String METHOD_RENAME_DOCUMENT = "android:renameDocument";
    /** {@hide} */
    public static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";
    /** {@hide} */
    public static final String METHOD_COPY_DOCUMENT = "android:copyDocument";
    /** {@hide} */
    public static final String METHOD_MOVE_DOCUMENT = "android:moveDocument";
    /** {@hide} */
    public static final String METHOD_IS_CHILD_DOCUMENT = "android:isChildDocument";
    /** {@hide} */
    public static final String METHOD_REMOVE_DOCUMENT = "android:removeDocument";

    /** {@hide} */
    public static final String EXTRA_PARENT_URI = "parentUri";
    /** {@hide} */
    public static final String EXTRA_URI = "uri";

    private static final String PATH_ROOT = "root";
    private static final String PATH_RECENT = "recent";
    private static final String PATH_DOCUMENT = "document";
    private static final String PATH_CHILDREN = "children";
    private static final String PATH_SEARCH = "search";
    private static final String PATH_TREE = "tree";

    private static final String PARAM_QUERY = "query";
    private static final String PARAM_MANAGE = "manage";

    /**
     * Build URI representing the roots of a document provider. When queried, a
     * provider will return one or more rows with columns defined by
     * {@link Root}.
     *
     * @see DocumentsProvider#queryRoots(String[])
     */
    public static Uri buildRootsUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_ROOT).build();
    }

    /**
     * Build URI representing the given {@link Root#COLUMN_ROOT_ID} in a
     * document provider.
     *
     * @see #getRootId(Uri)
     */
    public static Uri buildRootUri(String authority, String rootId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_ROOT).appendPath(rootId).build();
    }

    /**
     * Builds URI for user home directory on external (local) storage.
     * {@hide}
     */
    public static Uri buildHomeUri() {
        // TODO: Avoid this type of interpackage copying. Added here to avoid
        // direct coupling, but not ideal.
        return DocumentsContract.buildRootUri("com.android.externalstorage.documents", "home");
    }

    /**
     * Build URI representing the recently modified documents of a specific root
     * in a document provider. When queried, a provider will return zero or more
     * rows with columns defined by {@link Document}.
     *
     * @see DocumentsProvider#queryRecentDocuments(String, String[])
     * @see #getRootId(Uri)
     */
    public static Uri buildRecentDocumentsUri(String authority, String rootId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_ROOT).appendPath(rootId)
                .appendPath(PATH_RECENT).build();
    }

    /**
     * Build URI representing access to descendant documents of the given
     * {@link Document#COLUMN_DOCUMENT_ID}.
     *
     * @see #getTreeDocumentId(Uri)
     */
    public static Uri buildTreeDocumentUri(String authority, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath(PATH_TREE).appendPath(documentId).build();
    }

    /**
     * Build URI representing the target {@link Document#COLUMN_DOCUMENT_ID} in
     * a document provider. When queried, a provider will return a single row
     * with columns defined by {@link Document}.
     *
     * @see DocumentsProvider#queryDocument(String, String[])
     * @see #getDocumentId(Uri)
     */
    public static Uri buildDocumentUri(String authority, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_DOCUMENT).appendPath(documentId).build();
    }

    /**
     * Build URI representing the target {@link Document#COLUMN_DOCUMENT_ID} in
     * a document provider. When queried, a provider will return a single row
     * with columns defined by {@link Document}.
     * <p>
     * However, instead of directly accessing the target document, the returned
     * URI will leverage access granted through a subtree URI, typically
     * returned by {@link Intent#ACTION_OPEN_DOCUMENT_TREE}. The target document
     * must be a descendant (child, grandchild, etc) of the subtree.
     * <p>
     * This is typically used to access documents under a user-selected
     * directory tree, since it doesn't require the user to separately confirm
     * each new document access.
     *
     * @param treeUri the subtree to leverage to gain access to the target
     *            document. The target directory must be a descendant of this
     *            subtree.
     * @param documentId the target document, which the caller may not have
     *            direct access to.
     * @see Intent#ACTION_OPEN_DOCUMENT_TREE
     * @see DocumentsProvider#isChildDocument(String, String)
     * @see #buildDocumentUri(String, String)
     */
    public static Uri buildDocumentUriUsingTree(Uri treeUri, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(treeUri.getAuthority()).appendPath(PATH_TREE)
                .appendPath(getTreeDocumentId(treeUri)).appendPath(PATH_DOCUMENT)
                .appendPath(documentId).build();
    }

    /** {@hide} */
    public static Uri buildDocumentUriMaybeUsingTree(Uri baseUri, String documentId) {
        if (isTreeUri(baseUri)) {
            return buildDocumentUriUsingTree(baseUri, documentId);
        } else {
            return buildDocumentUri(baseUri.getAuthority(), documentId);
        }
    }

    /**
     * Build URI representing the children of the target directory in a document
     * provider. When queried, a provider will return zero or more rows with
     * columns defined by {@link Document}.
     *
     * @param parentDocumentId the document to return children for, which must
     *            be a directory with MIME type of
     *            {@link Document#MIME_TYPE_DIR}.
     * @see DocumentsProvider#queryChildDocuments(String, String[], String)
     * @see #getDocumentId(Uri)
     */
    public static Uri buildChildDocumentsUri(String authority, String parentDocumentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath(PATH_DOCUMENT).appendPath(parentDocumentId).appendPath(PATH_CHILDREN)
                .build();
    }

    /**
     * Build URI representing the children of the target directory in a document
     * provider. When queried, a provider will return zero or more rows with
     * columns defined by {@link Document}.
     * <p>
     * However, instead of directly accessing the target directory, the returned
     * URI will leverage access granted through a subtree URI, typically
     * returned by {@link Intent#ACTION_OPEN_DOCUMENT_TREE}. The target
     * directory must be a descendant (child, grandchild, etc) of the subtree.
     * <p>
     * This is typically used to access documents under a user-selected
     * directory tree, since it doesn't require the user to separately confirm
     * each new document access.
     *
     * @param treeUri the subtree to leverage to gain access to the target
     *            document. The target directory must be a descendant of this
     *            subtree.
     * @param parentDocumentId the document to return children for, which the
     *            caller may not have direct access to, and which must be a
     *            directory with MIME type of {@link Document#MIME_TYPE_DIR}.
     * @see Intent#ACTION_OPEN_DOCUMENT_TREE
     * @see DocumentsProvider#isChildDocument(String, String)
     * @see #buildChildDocumentsUri(String, String)
     */
    public static Uri buildChildDocumentsUriUsingTree(Uri treeUri, String parentDocumentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(treeUri.getAuthority()).appendPath(PATH_TREE)
                .appendPath(getTreeDocumentId(treeUri)).appendPath(PATH_DOCUMENT)
                .appendPath(parentDocumentId).appendPath(PATH_CHILDREN).build();
    }

    /**
     * Build URI representing a search for matching documents under a specific
     * root in a document provider. When queried, a provider will return zero or
     * more rows with columns defined by {@link Document}.
     *
     * @see DocumentsProvider#querySearchDocuments(String, String, String[])
     * @see #getRootId(Uri)
     * @see #getSearchDocumentsQuery(Uri)
     */
    public static Uri buildSearchDocumentsUri(
            String authority, String rootId, String query) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath(PATH_ROOT).appendPath(rootId).appendPath(PATH_SEARCH)
                .appendQueryParameter(PARAM_QUERY, query).build();
    }

    /**
     * Test if the given URI represents a {@link Document} backed by a
     * {@link DocumentsProvider}.
     *
     * @see #buildDocumentUri(String, String)
     * @see #buildDocumentUriUsingTree(Uri, String)
     */
    public static boolean isDocumentUri(Context context, @Nullable Uri uri) {
        if (isContentUri(uri) && isDocumentsProvider(context, uri.getAuthority())) {
            final List<String> paths = uri.getPathSegments();
            if (paths.size() == 2) {
                return PATH_DOCUMENT.equals(paths.get(0));
            } else if (paths.size() == 4) {
                return PATH_TREE.equals(paths.get(0)) && PATH_DOCUMENT.equals(paths.get(2));
            }
        }
        return false;
    }

    /** {@hide} */
    public static boolean isRootUri(Context context, @Nullable Uri uri) {
        if (isContentUri(uri) && isDocumentsProvider(context, uri.getAuthority())) {
            final List<String> paths = uri.getPathSegments();
            return (paths.size() == 2 && PATH_ROOT.equals(paths.get(0)));
        }
        return false;
    }

    /** {@hide} */
    public static boolean isContentUri(@Nullable Uri uri) {
        return uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme());
    }

    /**
     * Test if the given URI represents a {@link Document} tree.
     *
     * @see #buildTreeDocumentUri(String, String)
     * @see #getTreeDocumentId(Uri)
     */
    public static boolean isTreeUri(Uri uri) {
        final List<String> paths = uri.getPathSegments();
        return (paths.size() >= 2 && PATH_TREE.equals(paths.get(0)));
    }

    private static boolean isDocumentsProvider(Context context, String authority) {
        final Intent intent = new Intent(PROVIDER_INTERFACE);
        final List<ResolveInfo> infos = context.getPackageManager()
                .queryIntentContentProviders(intent, 0);
        for (ResolveInfo info : infos) {
            if (authority.equals(info.providerInfo.authority)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract the {@link Root#COLUMN_ROOT_ID} from the given URI.
     */
    public static String getRootId(Uri rootUri) {
        final List<String> paths = rootUri.getPathSegments();
        if (paths.size() >= 2 && PATH_ROOT.equals(paths.get(0))) {
            return paths.get(1);
        }
        throw new IllegalArgumentException("Invalid URI: " + rootUri);
    }

    /**
     * Extract the {@link Document#COLUMN_DOCUMENT_ID} from the given URI.
     *
     * @see #isDocumentUri(Context, Uri)
     */
    public static String getDocumentId(Uri documentUri) {
        final List<String> paths = documentUri.getPathSegments();
        if (paths.size() >= 2 && PATH_DOCUMENT.equals(paths.get(0))) {
            return paths.get(1);
        }
        if (paths.size() >= 4 && PATH_TREE.equals(paths.get(0))
                && PATH_DOCUMENT.equals(paths.get(2))) {
            return paths.get(3);
        }
        throw new IllegalArgumentException("Invalid URI: " + documentUri);
    }

    /**
     * Extract the via {@link Document#COLUMN_DOCUMENT_ID} from the given URI.
     */
    public static String getTreeDocumentId(Uri documentUri) {
        final List<String> paths = documentUri.getPathSegments();
        if (paths.size() >= 2 && PATH_TREE.equals(paths.get(0))) {
            return paths.get(1);
        }
        throw new IllegalArgumentException("Invalid URI: " + documentUri);
    }

    /**
     * Extract the search query from a URI built by
     * {@link #buildSearchDocumentsUri(String, String, String)}.
     */
    public static String getSearchDocumentsQuery(Uri searchDocumentsUri) {
        return searchDocumentsUri.getQueryParameter(PARAM_QUERY);
    }

    /** {@hide} */
    public static Uri setManageMode(Uri uri) {
        return uri.buildUpon().appendQueryParameter(PARAM_MANAGE, "true").build();
    }

    /** {@hide} */
    public static boolean isManageMode(Uri uri) {
        return uri.getBooleanQueryParameter(PARAM_MANAGE, false);
    }

    /**
     * Return thumbnail representing the document at the given URI. Callers are
     * responsible for their own in-memory caching.
     *
     * @param documentUri document to return thumbnail for, which must have
     *            {@link Document#FLAG_SUPPORTS_THUMBNAIL} set.
     * @param size optimal thumbnail size desired. A provider may return a
     *            thumbnail of a different size, but never more than double the
     *            requested size.
     * @param signal signal used to indicate if caller is no longer interested
     *            in the thumbnail.
     * @return decoded thumbnail, or {@code null} if problem was encountered.
     * @see DocumentsProvider#openDocumentThumbnail(String, Point,
     *      android.os.CancellationSignal)
     */
    public static Bitmap getDocumentThumbnail(
            ContentResolver resolver, Uri documentUri, Point size, CancellationSignal signal) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                documentUri.getAuthority());
        try {
            return getDocumentThumbnail(client, documentUri, size, signal);
        } catch (Exception e) {
            if (!(e instanceof OperationCanceledException)) {
                Log.w(TAG, "Failed to load thumbnail for " + documentUri + ": " + e);
            }
            return null;
        } finally {
            ContentProviderClient.releaseQuietly(client);
        }
    }

    /** {@hide} */
    public static Bitmap getDocumentThumbnail(
            ContentProviderClient client, Uri documentUri, Point size, CancellationSignal signal)
            throws RemoteException, IOException {
        final Bundle openOpts = new Bundle();
        openOpts.putParcelable(ContentResolver.EXTRA_SIZE, size);

        AssetFileDescriptor afd = null;
        Bitmap bitmap = null;
        try {
            afd = client.openTypedAssetFileDescriptor(documentUri, "image/*", openOpts, signal);

            final FileDescriptor fd = afd.getFileDescriptor();
            final long offset = afd.getStartOffset();

            // Try seeking on the returned FD, since it gives us the most
            // optimal decode path; otherwise fall back to buffering.
            BufferedInputStream is = null;
            try {
                Os.lseek(fd, offset, SEEK_SET);
            } catch (ErrnoException e) {
                is = new BufferedInputStream(new FileInputStream(fd), THUMBNAIL_BUFFER_SIZE);
                is.mark(THUMBNAIL_BUFFER_SIZE);
            }

            // We requested a rough thumbnail size, but the remote size may have
            // returned something giant, so defensively scale down as needed.
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            if (is != null) {
                BitmapFactory.decodeStream(is, null, opts);
            } else {
                BitmapFactory.decodeFileDescriptor(fd, null, opts);
            }

            final int widthSample = opts.outWidth / size.x;
            final int heightSample = opts.outHeight / size.y;

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.min(widthSample, heightSample);
            if (is != null) {
                is.reset();
                bitmap = BitmapFactory.decodeStream(is, null, opts);
            } else {
                try {
                    Os.lseek(fd, offset, SEEK_SET);
                } catch (ErrnoException e) {
                    e.rethrowAsIOException();
                }
                bitmap = BitmapFactory.decodeFileDescriptor(fd, null, opts);
            }

            // Transform the bitmap if requested. We use a side-channel to
            // communicate the orientation, since EXIF thumbnails don't contain
            // the rotation flags of the original image.
            final Bundle extras = afd.getExtras();
            final int orientation = (extras != null) ? extras.getInt(EXTRA_ORIENTATION, 0) : 0;
            if (orientation != 0) {
                final int width = bitmap.getWidth();
                final int height = bitmap.getHeight();

                final Matrix m = new Matrix();
                m.setRotate(orientation, width / 2, height / 2);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, m, false);
            }
        } finally {
            IoUtils.closeQuietly(afd);
        }

        return bitmap;
    }

    /**
     * Create a new document with given MIME type and display name.
     *
     * @param parentDocumentUri directory with
     *            {@link Document#FLAG_DIR_SUPPORTS_CREATE}
     * @param mimeType MIME type of new document
     * @param displayName name of new document
     * @return newly created document, or {@code null} if failed
     */
    public static Uri createDocument(ContentResolver resolver, Uri parentDocumentUri,
            String mimeType, String displayName) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                parentDocumentUri.getAuthority());
        try {
            return createDocument(client, parentDocumentUri, mimeType, displayName);
        } catch (Exception e) {
            Log.w(TAG, "Failed to create document", e);
            return null;
        } finally {
            ContentProviderClient.releaseQuietly(client);
        }
    }

    /** {@hide} */
    public static Uri createDocument(ContentProviderClient client, Uri parentDocumentUri,
            String mimeType, String displayName) throws RemoteException {
        final Bundle in = new Bundle();
        in.putParcelable(DocumentsContract.EXTRA_URI, parentDocumentUri);
        in.putString(Document.COLUMN_MIME_TYPE, mimeType);
        in.putString(Document.COLUMN_DISPLAY_NAME, displayName);

        final Bundle out = client.call(METHOD_CREATE_DOCUMENT, null, in);
        return out.getParcelable(DocumentsContract.EXTRA_URI);
    }

    /** {@hide} */
    public static boolean isChildDocument(ContentProviderClient client, Uri parentDocumentUri,
            Uri childDocumentUri) throws RemoteException {

        final Bundle in = new Bundle();
        in.putParcelable(DocumentsContract.EXTRA_URI, parentDocumentUri);
        in.putParcelable(DocumentsContract.EXTRA_TARGET_URI, childDocumentUri);

        final Bundle out = client.call(METHOD_IS_CHILD_DOCUMENT, null, in);
        if (out == null) {
            throw new RemoteException("Failed to get a reponse from isChildDocument query.");
        }
        if (!out.containsKey(DocumentsContract.EXTRA_RESULT)) {
            throw new RemoteException("Response did not include result field..");
        }
        return out.getBoolean(DocumentsContract.EXTRA_RESULT);
    }

    /**
     * Change the display name of an existing document.
     * <p>
     * If the underlying provider needs to create a new
     * {@link Document#COLUMN_DOCUMENT_ID} to represent the updated display
     * name, that new document is returned and the original document is no
     * longer valid. Otherwise, the original document is returned.
     *
     * @param documentUri document with {@link Document#FLAG_SUPPORTS_RENAME}
     * @param displayName updated name for document
     * @return the existing or new document after the rename, or {@code null} if
     *         failed.
     */
    public static Uri renameDocument(ContentResolver resolver, Uri documentUri,
            String displayName) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                documentUri.getAuthority());
        try {
            return renameDocument(client, documentUri, displayName);
        } catch (Exception e) {
            Log.w(TAG, "Failed to rename document", e);
            return null;
        } finally {
            ContentProviderClient.releaseQuietly(client);
        }
    }

    /** {@hide} */
    public static Uri renameDocument(ContentProviderClient client, Uri documentUri,
            String displayName) throws RemoteException {
        final Bundle in = new Bundle();
        in.putParcelable(DocumentsContract.EXTRA_URI, documentUri);
        in.putString(Document.COLUMN_DISPLAY_NAME, displayName);

        final Bundle out = client.call(METHOD_RENAME_DOCUMENT, null, in);
        final Uri outUri = out.getParcelable(DocumentsContract.EXTRA_URI);
        return (outUri != null) ? outUri : documentUri;
    }

    /**
     * Delete the given document.
     *
     * @param documentUri document with {@link Document#FLAG_SUPPORTS_DELETE}
     * @return if the document was deleted successfully.
     */
    public static boolean deleteDocument(ContentResolver resolver, Uri documentUri) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                documentUri.getAuthority());
        try {
            deleteDocument(client, documentUri);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete document", e);
            return false;
        } finally {
            ContentProviderClient.releaseQuietly(client);
        }
    }

    /** {@hide} */
    public static void deleteDocument(ContentProviderClient client, Uri documentUri)
            throws RemoteException {
        final Bundle in = new Bundle();
        in.putParcelable(DocumentsContract.EXTRA_URI, documentUri);

        client.call(METHOD_DELETE_DOCUMENT, null, in);
    }

    /**
     * Copies the given document.
     *
     * @param sourceDocumentUri document with {@link Document#FLAG_SUPPORTS_COPY}
     * @param targetParentDocumentUri document which will become a parent of the source
     *         document's copy.
     * @return the copied document, or {@code null} if failed.
     */
    public static Uri copyDocument(ContentResolver resolver, Uri sourceDocumentUri,
            Uri targetParentDocumentUri) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                sourceDocumentUri.getAuthority());
        try {
            return copyDocument(client, sourceDocumentUri, targetParentDocumentUri);
        } catch (Exception e) {
            Log.w(TAG, "Failed to copy document", e);
            return null;
        } finally {
            ContentProviderClient.releaseQuietly(client);
        }
    }

    /** {@hide} */
    public static Uri copyDocument(ContentProviderClient client, Uri sourceDocumentUri,
            Uri targetParentDocumentUri) throws RemoteException {
        final Bundle in = new Bundle();
        in.putParcelable(DocumentsContract.EXTRA_URI, sourceDocumentUri);
        in.putParcelable(DocumentsContract.EXTRA_TARGET_URI, targetParentDocumentUri);

        final Bundle out = client.call(METHOD_COPY_DOCUMENT, null, in);
        return out.getParcelable(DocumentsContract.EXTRA_URI);
    }

    /**
     * Moves the given document under a new parent.
     *
     * @param sourceDocumentUri document with {@link Document#FLAG_SUPPORTS_MOVE}
     * @param sourceParentDocumentUri parent document of the document to move.
     * @param targetParentDocumentUri document which will become a new parent of the source
     *         document.
     * @return the moved document, or {@code null} if failed.
     */
    public static Uri moveDocument(ContentResolver resolver, Uri sourceDocumentUri,
            Uri sourceParentDocumentUri, Uri targetParentDocumentUri) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                sourceDocumentUri.getAuthority());
        try {
            return moveDocument(client, sourceDocumentUri, sourceParentDocumentUri,
                    targetParentDocumentUri);
        } catch (Exception e) {
            Log.w(TAG, "Failed to move document", e);
            return null;
        } finally {
            ContentProviderClient.releaseQuietly(client);
        }
    }

    /** {@hide} */
    public static Uri moveDocument(ContentProviderClient client, Uri sourceDocumentUri,
            Uri sourceParentDocumentUri, Uri targetParentDocumentUri) throws RemoteException {
        final Bundle in = new Bundle();
        in.putParcelable(DocumentsContract.EXTRA_URI, sourceDocumentUri);
        in.putParcelable(DocumentsContract.EXTRA_PARENT_URI, sourceParentDocumentUri);
        in.putParcelable(DocumentsContract.EXTRA_TARGET_URI, targetParentDocumentUri);

        final Bundle out = client.call(METHOD_MOVE_DOCUMENT, null, in);
        return out.getParcelable(DocumentsContract.EXTRA_URI);
    }

    /**
     * Removes the given document from a parent directory.
     *
     * <p>In contrast to {@link #deleteDocument} it requires specifying the parent.
     * This method is especially useful if the document can be in multiple parents.
     *
     * @param documentUri document with {@link Document#FLAG_SUPPORTS_REMOVE}
     * @param parentDocumentUri parent document of the document to remove.
     * @return true if the document was removed successfully.
     */
    public static boolean removeDocument(ContentResolver resolver, Uri documentUri,
            Uri parentDocumentUri) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                documentUri.getAuthority());
        try {
            removeDocument(client, documentUri, parentDocumentUri);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove document", e);
            return false;
        } finally {
            ContentProviderClient.releaseQuietly(client);
        }
    }

    /** {@hide} */
    public static void removeDocument(ContentProviderClient client, Uri documentUri,
            Uri parentDocumentUri) throws RemoteException {
        final Bundle in = new Bundle();
        in.putParcelable(DocumentsContract.EXTRA_URI, documentUri);
        in.putParcelable(DocumentsContract.EXTRA_PARENT_URI, parentDocumentUri);

        client.call(METHOD_REMOVE_DOCUMENT, null, in);
    }

    /**
     * Open the given image for thumbnail purposes, using any embedded EXIF
     * thumbnail if available, and providing orientation hints from the parent
     * image.
     *
     * @hide
     */
    public static AssetFileDescriptor openImageThumbnail(File file) throws FileNotFoundException {
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_READ_ONLY);
        Bundle extras = null;

        try {
            final ExifInterface exif = new ExifInterface(file.getAbsolutePath());

            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    extras = new Bundle(1);
                    extras.putInt(EXTRA_ORIENTATION, 90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    extras = new Bundle(1);
                    extras.putInt(EXTRA_ORIENTATION, 180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    extras = new Bundle(1);
                    extras.putInt(EXTRA_ORIENTATION, 270);
                    break;
            }

            final long[] thumb = exif.getThumbnailRange();
            if (thumb != null) {
                return new AssetFileDescriptor(pfd, thumb[0], thumb[1], extras);
            }
        } catch (IOException e) {
        }

        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH, extras);
    }
}
