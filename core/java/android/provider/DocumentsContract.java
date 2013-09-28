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
import static libcore.io.OsConstants.SEEK_SET;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.os.RemoteException;
import android.util.Log;

import libcore.io.ErrnoException;
import libcore.io.IoUtils;
import libcore.io.Libcore;

import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Defines the contract between a documents provider and the platform.
 * <p>
 * To create a document provider, extend {@link DocumentsProvider}, which
 * provides a foundational implementation of this contract.
 *
 * @see DocumentsProvider
 */
public final class DocumentsContract {
    private static final String TAG = "Documents";

    // content://com.example/root/
    // content://com.example/root/sdcard/
    // content://com.example/root/sdcard/recent/
    // content://com.example/root/sdcard/search/?query=pony
    // content://com.example/document/12/
    // content://com.example/document/12/children/

    private DocumentsContract() {
    }

    /** {@hide} */
    public static final String META_DATA_DOCUMENT_PROVIDER = "android.content.DOCUMENT_PROVIDER";

    /** {@hide} */
    public static final String ACTION_MANAGE_ROOT = "android.provider.action.MANAGE_ROOT";
    /** {@hide} */
    public static final String ACTION_MANAGE_DOCUMENT = "android.provider.action.MANAGE_DOCUMENT";

    /**
     * Buffer is large enough to rewind past any EXIF headers.
     */
    private static final int THUMBNAIL_BUFFER_SIZE = (int) (128 * KB_IN_BYTES);

    /**
     * Constants related to a document, including {@link Cursor} columns names
     * and flags.
     * <p>
     * A document can be either an openable file (with a specific MIME type), or
     * a directory containing additional documents (with the
     * {@link #MIME_TYPE_DIR} MIME type).
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
         * issue long-term Uri permission grants when an application interacts
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
         * @see DocumentsContract#createDocument(ContentResolver, Uri, String,
         *      String)
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
         * Flag indicating that document titles should be hidden when viewing
         * this directory in a larger format grid. For example, a directory
         * containing only images may want the image thumbnails to speak for
         * themselves. Only valid when {@link #COLUMN_MIME_TYPE} is
         * {@link #MIME_TYPE_DIR}.
         *
         * @see #COLUMN_FLAGS
         * @see #FLAG_DIR_PREFERS_GRID
         * @hide
         */
        public static final int FLAG_DIR_HIDE_GRID_TITLES = 1 << 16;
    }

    /**
     * Constants related to a root of documents, including {@link Cursor}
     * columns names and flags.
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
         * required.
         * <p>
         * Type: STRING
         */
        public static final String COLUMN_TITLE = "title";

        /**
         * Summary for this root, which may be shown to a user. This column is
         * optional, and may be {@code null}.
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
         * Flag indicating that this root can report recently modified
         * documents.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#buildRecentDocumentsUri(String, String)
         */
        public static final int FLAG_SUPPORTS_RECENTS = 1 << 2;

        /**
         * Flag indicating that this root supports search.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsProvider#querySearchDocuments(String, String,
         *      String[])
         */
        public static final int FLAG_SUPPORTS_SEARCH = 1 << 3;

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

    /** {@hide} */
    public static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
    /** {@hide} */
    public static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";

    /** {@hide} */
    public static final String EXTRA_THUMBNAIL_SIZE = "thumbnail_size";

    private static final String PATH_ROOT = "root";
    private static final String PATH_RECENT = "recent";
    private static final String PATH_DOCUMENT = "document";
    private static final String PATH_CHILDREN = "children";
    private static final String PATH_SEARCH = "search";

    private static final String PARAM_QUERY = "query";
    private static final String PARAM_MANAGE = "manage";

    /**
     * Build Uri representing the roots of a document provider. When queried, a
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
     * Build Uri representing the given {@link Root#COLUMN_ROOT_ID} in a
     * document provider.
     *
     * @see #getRootId(Uri)
     */
    public static Uri buildRootUri(String authority, String rootId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_ROOT).appendPath(rootId).build();
    }

    /**
     * Build Uri representing the recently modified documents of a specific root
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
     * Build Uri representing the given {@link Document#COLUMN_DOCUMENT_ID} in a
     * document provider. When queried, a provider will return a single row with
     * columns defined by {@link Document}.
     *
     * @see DocumentsProvider#queryDocument(String, String[])
     * @see #getDocumentId(Uri)
     */
    public static Uri buildDocumentUri(String authority, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_DOCUMENT).appendPath(documentId).build();
    }

    /**
     * Build Uri representing the children of the given directory in a document
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
     * Build Uri representing a search for matching documents under a specific
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
     * Test if the given Uri represents a {@link Document} backed by a
     * {@link DocumentsProvider}.
     */
    public static boolean isDocumentUri(Context context, Uri uri) {
        final List<String> paths = uri.getPathSegments();
        if (paths.size() < 2) {
            return false;
        }
        if (!PATH_DOCUMENT.equals(paths.get(0))) {
            return false;
        }

        final ProviderInfo info = context.getPackageManager()
                .resolveContentProvider(uri.getAuthority(), PackageManager.GET_META_DATA);
        if (info.metaData != null && info.metaData.containsKey(
                DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {
            return true;
        }
        return false;
    }

    /**
     * Extract the {@link Root#COLUMN_ROOT_ID} from the given Uri.
     */
    public static String getRootId(Uri rootUri) {
        final List<String> paths = rootUri.getPathSegments();
        if (paths.size() < 2) {
            throw new IllegalArgumentException("Not a root: " + rootUri);
        }
        if (!PATH_ROOT.equals(paths.get(0))) {
            throw new IllegalArgumentException("Not a root: " + rootUri);
        }
        return paths.get(1);
    }

    /**
     * Extract the {@link Document#COLUMN_DOCUMENT_ID} from the given Uri.
     */
    public static String getDocumentId(Uri documentUri) {
        final List<String> paths = documentUri.getPathSegments();
        if (paths.size() < 2) {
            throw new IllegalArgumentException("Not a document: " + documentUri);
        }
        if (!PATH_DOCUMENT.equals(paths.get(0))) {
            throw new IllegalArgumentException("Not a document: " + documentUri);
        }
        return paths.get(1);
    }

    /**
     * Extract the search query from a Uri built by
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
     * Return thumbnail representing the document at the given Uri. Callers are
     * responsible for their own in-memory caching.
     *
     * @param documentUri document to return thumbnail for, which must have
     *            {@link Document#FLAG_SUPPORTS_THUMBNAIL} set.
     * @param size optimal thumbnail size desired. A provider may return a
     *            thumbnail of a different size, but never more than double the
     *            requested size.
     * @param signal signal used to indicate that caller is no longer interested
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
        } catch (RemoteException e) {
            return null;
        } finally {
            ContentProviderClient.closeQuietly(client);
        }
    }

    /** {@hide} */
    public static Bitmap getDocumentThumbnail(
            ContentProviderClient client, Uri documentUri, Point size, CancellationSignal signal)
            throws RemoteException {
        final Bundle openOpts = new Bundle();
        openOpts.putParcelable(DocumentsContract.EXTRA_THUMBNAIL_SIZE, size);

        AssetFileDescriptor afd = null;
        try {
            afd = client.openTypedAssetFileDescriptor(documentUri, "image/*", openOpts, signal);

            final FileDescriptor fd = afd.getFileDescriptor();
            final long offset = afd.getStartOffset();

            // Try seeking on the returned FD, since it gives us the most
            // optimal decode path; otherwise fall back to buffering.
            BufferedInputStream is = null;
            try {
                Libcore.os.lseek(fd, offset, SEEK_SET);
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
            Log.d(TAG, "Decoding with sample size " + opts.inSampleSize);
            if (is != null) {
                is.reset();
                return BitmapFactory.decodeStream(is, null, opts);
            } else {
                try {
                    Libcore.os.lseek(fd, offset, SEEK_SET);
                } catch (ErrnoException e) {
                    e.rethrowAsIOException();
                }
                return BitmapFactory.decodeFileDescriptor(fd, null, opts);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to load thumbnail for " + documentUri + ": " + e);
            return null;
        } finally {
            IoUtils.closeQuietly(afd);
        }
    }

    /**
     * Create a new document with given MIME type and display name.
     *
     * @param parentDocumentUri directory with
     *            {@link Document#FLAG_DIR_SUPPORTS_CREATE}
     * @param mimeType MIME type of new document
     * @param displayName name of new document
     * @return newly created document, or {@code null} if failed
     * @hide
     */
    public static Uri createDocument(ContentResolver resolver, Uri parentDocumentUri,
            String mimeType, String displayName) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                parentDocumentUri.getAuthority());
        try {
            return createDocument(client, parentDocumentUri, mimeType, displayName);
        } finally {
            ContentProviderClient.closeQuietly(client);
        }
    }

    /** {@hide} */
    public static Uri createDocument(ContentProviderClient client, Uri parentDocumentUri,
            String mimeType, String displayName) {
        final Bundle in = new Bundle();
        in.putString(Document.COLUMN_DOCUMENT_ID, getDocumentId(parentDocumentUri));
        in.putString(Document.COLUMN_MIME_TYPE, mimeType);
        in.putString(Document.COLUMN_DISPLAY_NAME, displayName);

        try {
            final Bundle out = client.call(METHOD_CREATE_DOCUMENT, null, in);
            return buildDocumentUri(
                    parentDocumentUri.getAuthority(), out.getString(Document.COLUMN_DOCUMENT_ID));
        } catch (Exception e) {
            Log.w(TAG, "Failed to create document", e);
            return null;
        }
    }

    /**
     * Delete the given document.
     *
     * @param documentUri document with {@link Document#FLAG_SUPPORTS_DELETE}
     */
    public static boolean deleteDocument(ContentResolver resolver, Uri documentUri) {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                documentUri.getAuthority());
        try {
            return deleteDocument(client, documentUri);
        } finally {
            ContentProviderClient.closeQuietly(client);
        }
    }

    /** {@hide} */
    public static boolean deleteDocument(ContentProviderClient client, Uri documentUri) {
        final Bundle in = new Bundle();
        in.putString(Document.COLUMN_DOCUMENT_ID, getDocumentId(documentUri));

        try {
            final Bundle out = client.call(METHOD_DELETE_DOCUMENT, null, in);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete document", e);
            return false;
        }
    }
}
