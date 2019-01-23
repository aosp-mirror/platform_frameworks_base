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

import static com.android.internal.util.Preconditions.checkCollectionElementsNotNull;
import static com.android.internal.util.Preconditions.checkCollectionNotEmpty;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.content.ContentInterface;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.MimeTypeFilter;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.os.Parcelable;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Defines the contract between a documents provider and the platform.
 * <p>
 * To create a document provider, extend {@link DocumentsProvider}, which
 * provides a foundational implementation of this contract.
 * <p>
 * All client apps must hold a valid URI permission grant to access documents,
 * typically issued when a user makes a selection through
 * {@link Intent#ACTION_OPEN_DOCUMENT}, {@link Intent#ACTION_CREATE_DOCUMENT},
 * or {@link Intent#ACTION_OPEN_DOCUMENT_TREE}.
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
    @Deprecated
    public static final String EXTRA_PACKAGE_NAME = Intent.EXTRA_PACKAGE_NAME;

    /**
     * The value is decide whether to show advance mode or not.
     * If the value is true, the local/device storage root must be
     * visible in DocumentsUI.
     *
     * {@hide}
     */
    @SystemApi
    public static final String EXTRA_SHOW_ADVANCED = "android.provider.extra.SHOW_ADVANCED";

    /** {@hide} */
    public static final String EXTRA_TARGET_URI = "android.content.extra.TARGET_URI";

    /**
     * Key for {@link DocumentsProvider} to query display name is matched.
     * The match of display name is partial matching and case-insensitive.
     * Ex: The value is "o", the display name of the results will contain
     * both "foo" and "Open".
     *
     * @see DocumentsProvider#querySearchDocuments(String, String[],
     *      Bundle)
     */
    public static final String QUERY_ARG_DISPLAY_NAME = "android:query-arg-display-name";

    /**
     * Key for {@link DocumentsProvider} to query mime types is matched.
     * The value is a string array, it can support different mime types.
     * Each items will be treated as "OR" condition. Ex: {"image/*" ,
     * "video/*"}. The mime types of the results will contain both image
     * type and video type.
     *
     * @see DocumentsProvider#querySearchDocuments(String, String[],
     *      Bundle)
     */
    public static final String QUERY_ARG_MIME_TYPES = "android:query-arg-mime-types";

    /**
     * Key for {@link DocumentsProvider} to query the file size in bytes is
     * larger than the value.
     *
     * @see DocumentsProvider#querySearchDocuments(String, String[],
     *      Bundle)
     */
    public static final String QUERY_ARG_FILE_SIZE_OVER = "android:query-arg-file-size-over";

    /**
     * Key for {@link DocumentsProvider} to query the last modified time
     * is newer than the value. The unit is in milliseconds since
     * January 1, 1970 00:00:00.0 UTC.
     *
     * @see DocumentsProvider#querySearchDocuments(String, String[],
     *      Bundle)
     * @see Document#COLUMN_LAST_MODIFIED
     */
    public static final String QUERY_ARG_LAST_MODIFIED_AFTER =
            "android:query-arg-last-modified-after";

    /**
     * Key for {@link DocumentsProvider} to decide whether the files that
     * have been added to MediaStore should be excluded. If the value is
     * true, exclude them. Otherwise, include them.
     *
     * @see DocumentsProvider#querySearchDocuments(String, String[],
     *      Bundle)
     */
    public static final String QUERY_ARG_EXCLUDE_MEDIA = "android:query-arg-exclude-media";

    /**
     * Sets the desired initial location visible to user when file chooser is shown.
     *
     * <p>Applicable to {@link Intent} with actions:
     * <ul>
     *      <li>{@link Intent#ACTION_OPEN_DOCUMENT}</li>
     *      <li>{@link Intent#ACTION_CREATE_DOCUMENT}</li>
     *      <li>{@link Intent#ACTION_OPEN_DOCUMENT_TREE}</li>
     * </ul>
     *
     * <p>Location should specify a document URI or a tree URI with document ID. If
     * this URI identifies a non-directory, document navigator will attempt to use the parent
     * of the document as the initial location.
     *
     * <p>The initial location is system specific if this extra is missing or document navigator
     * failed to locate the desired initial location.
     */
    public static final String EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI";

    /**
     * Set this in a DocumentsUI intent to cause a package's own roots to be
     * excluded from the roots list.
     */
    public static final String EXTRA_EXCLUDE_SELF = "android.provider.extra.EXCLUDE_SELF";

    /**
     * An extra number of degrees that an image should be rotated during the
     * decode process to be presented correctly.
     *
     * @see AssetFileDescriptor#getExtras()
     * @see android.provider.MediaStore.Images.ImageColumns#ORIENTATION
     */
    public static final String EXTRA_ORIENTATION = "android.provider.extra.ORIENTATION";

    /**
     * Overrides the default prompt text in DocumentsUI when set in an intent.
     */
    public static final String EXTRA_PROMPT = "android.provider.extra.PROMPT";

    /**
     * Action of intent issued by DocumentsUI when user wishes to open/configure/manage a particular
     * document in the provider application.
     *
     * <p>When issued, the intent will include the URI of the document as the intent data.
     *
     * <p>A provider wishing to provide support for this action should do two things.
     * <li>Add an {@code <intent-filter>} matching this action.
     * <li>When supplying information in {@link DocumentsProvider#queryChildDocuments}, include
     * {@link Document#FLAG_SUPPORTS_SETTINGS} in the flags for each document that supports
     * settings.
     */
    public static final String
            ACTION_DOCUMENT_SETTINGS = "android.provider.action.DOCUMENT_SETTINGS";

    /**
     * The action to manage document in Downloads root in DocumentsUI.
     *  {@hide}
     */
    @SystemApi
    public static final String ACTION_MANAGE_DOCUMENT = "android.provider.action.MANAGE_DOCUMENT";

    /**
     * The action to launch the settings of this root.
     * {@hide}
     */
    @SystemApi
    public static final String
            ACTION_DOCUMENT_ROOT_SETTINGS = "android.provider.action.DOCUMENT_ROOT_SETTINGS";

    /** {@hide} */
    public static final String EXTERNAL_STORAGE_PROVIDER_AUTHORITY =
            "com.android.externalstorage.documents";

    /** {@hide} */
    public static final String EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID = "primary";

    /** {@hide} */
    public static final String PACKAGE_DOCUMENTS_UI = "com.android.documentsui";

    /**
     * Get string array identifies the type or types of metadata returned
     * using DocumentsContract#getDocumentMetadata.
     *
     * @see #getDocumentMetadata(ContentInterface, Uri)
     */
    public static final String METADATA_TYPES = "android:documentMetadataTypes";

    /**
     * Get Exif information using DocumentsContract#getDocumentMetadata.
     *
     * @see #getDocumentMetadata(ContentInterface, Uri)
     */
    public static final String METADATA_EXIF = "android:documentExif";

    /**
     * Get total count of all documents currently stored under the given
     * directory tree. Only valid for {@link Document#MIME_TYPE_DIR} documents.
     *
     * @see #getDocumentMetadata(ContentInterface, Uri)
     */
    public static final String METADATA_TREE_COUNT = "android:metadataTreeCount";

    /**
     * Get total size of all documents currently stored under the given
     * directory tree. Only valid for {@link Document#MIME_TYPE_DIR} documents.
     *
     * @see #getDocumentMetadata(ContentInterface, Uri)
     */
    public static final String METADATA_TREE_SIZE = "android:metadataTreeSize";

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
         * @see DocumentsContract#getDocumentThumbnail(ContentInterface, Uri,
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
         * @see DocumentsContract#deleteDocument(ContentInterface, Uri)
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
         * @see DocumentsContract#renameDocument(ContentInterface, Uri, String)
         * @see DocumentsProvider#renameDocument(String, String)
         */
        public static final int FLAG_SUPPORTS_RENAME = 1 << 6;

        /**
         * Flag indicating that a document can be copied to another location
         * within the same document provider.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#copyDocument(ContentInterface, Uri, Uri)
         * @see DocumentsProvider#copyDocument(String, String)
         */
        public static final int FLAG_SUPPORTS_COPY = 1 << 7;

        /**
         * Flag indicating that a document can be moved to another location
         * within the same document provider.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#moveDocument(ContentInterface, Uri, Uri, Uri)
         * @see DocumentsProvider#moveDocument(String, String, String)
         */
        public static final int FLAG_SUPPORTS_MOVE = 1 << 8;

        /**
         * Flag indicating that a document is virtual, and doesn't have byte
         * representation in the MIME type specified as {@link #COLUMN_MIME_TYPE}.
         *
         * <p><em>Virtual documents must have at least one alternative streamable
         * format via {@link DocumentsProvider#openTypedDocument}</em>
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
         * @see DocumentsContract#removeDocument(ContentInterface, Uri, Uri)
         * @see DocumentsProvider#removeDocument(String, String)
         */
        public static final int FLAG_SUPPORTS_REMOVE = 1 << 10;

        /**
         * Flag indicating that a document has settings that can be configured by user.
         *
         * @see #COLUMN_FLAGS
         * @see #ACTION_DOCUMENT_SETTINGS
         */
        public static final int FLAG_SUPPORTS_SETTINGS = 1 << 11;

        /**
         * Flag indicating that a Web link can be obtained for the document.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsProvider#createWebLinkIntent(String, Bundle)
         */
        public static final int FLAG_WEB_LINKABLE = 1 << 12;

        /**
         * Flag indicating that a document is not complete, likely its
         * contents are being downloaded. Partial files cannot be opened,
         * copied, moved in the UI. But they can be deleted and retried
         * if they represent a failed download.
         *
         * @see #COLUMN_FLAGS
         */
        public static final int FLAG_PARTIAL = 1 << 13;

        /**
         * Flag indicating that a document has available metadata that can be read
         * using DocumentsContract#getDocumentMetadata
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#getDocumentMetadata(ContentInterface, Uri)
         */
        public static final int FLAG_SUPPORTS_METADATA = 1 << 14;
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

        /**
         * Query arguments supported by this root. This column is optional
         * and related to {@link #COLUMN_FLAGS} and {@link #FLAG_SUPPORTS_SEARCH}.
         * If the flags include {@link #FLAG_SUPPORTS_SEARCH}, and the column is
         * {@code null}, the root is assumed to support {@link #QUERY_ARG_DISPLAY_NAME}
         * search of {@link Document#COLUMN_DISPLAY_NAME}. Multiple query arguments
         * can be separated by a newline. For example, a root supporting
         * {@link #QUERY_ARG_MIME_TYPES} and {@link #QUERY_ARG_DISPLAY_NAME} might
         * return "android:query-arg-mime-types\nandroid:query-arg-display-name".
         * <p>
         * Type: STRING
         * @see #COLUMN_FLAGS
         * @see #FLAG_SUPPORTS_SEARCH
         * @see #QUERY_ARG_DISPLAY_NAME
         * @see #QUERY_ARG_FILE_SIZE_OVER
         * @see #QUERY_ARG_LAST_MODIFIED_AFTER
         * @see #QUERY_ARG_MIME_TYPES
         * @see DocumentsProvider#querySearchDocuments(String, String[],
         *      Bundle)
         */
        public static final String COLUMN_QUERY_ARGS = "query_args";

        /**
         * MIME type for a root.
         */
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
         * @see DocumentsProvider#querySearchDocuments(String, String[],
         *      Bundle)
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
         * Flag indicating that this root can be ejected.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#ejectRoot(ContentInterface, Uri)
         * @see DocumentsProvider#ejectRoot(String)
         */
        public static final int FLAG_SUPPORTS_EJECT = 1 << 5;

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
         */
        public static final int FLAG_EMPTY = 1 << 6;

        /**
         * Flag indicating that this root should only be visible to advanced
         * users.
         *
         * @see #COLUMN_FLAGS
         * {@hide}
         */
        @SystemApi
        public static final int FLAG_ADVANCED = 1 << 16;

        /**
         * Flag indicating that this root has settings.
         *
         * @see #COLUMN_FLAGS
         * @see DocumentsContract#ACTION_DOCUMENT_ROOT_SETTINGS
         * {@hide}
         */
        @SystemApi
        public static final int FLAG_HAS_SETTINGS = 1 << 17;

        /**
         * Flag indicating that this root is on removable SD card storage.
         *
         * @see #COLUMN_FLAGS
         * {@hide}
         */
        @SystemApi
        public static final int FLAG_REMOVABLE_SD = 1 << 18;

        /**
         * Flag indicating that this root is on removable USB storage.
         *
         * @see #COLUMN_FLAGS
         * {@hide}
         */
        @SystemApi
        public static final int FLAG_REMOVABLE_USB = 1 << 19;
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
    @UnsupportedAppUsage
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
    public static final String METHOD_EJECT_ROOT = "android:ejectRoot";
    /** {@hide} */
    public static final String METHOD_FIND_DOCUMENT_PATH = "android:findDocumentPath";
    /** {@hide} */
    public static final String METHOD_CREATE_WEB_LINK_INTENT = "android:createWebLinkIntent";
    /** {@hide} */
    public static final String METHOD_GET_DOCUMENT_METADATA = "android:getDocumentMetadata";

    /** {@hide} */
    public static final String EXTRA_PARENT_URI = "parentUri";
    /** {@hide} */
    public static final String EXTRA_URI = "uri";
    /** {@hide} */
    public static final String EXTRA_URI_PERMISSIONS = "uriPermissions";

    /** {@hide} */
    public static final String EXTRA_OPTIONS = "options";

    private static final String PATH_ROOT = "root";
    private static final String PATH_RECENT = "recent";
    @UnsupportedAppUsage
    private static final String PATH_DOCUMENT = "document";
    private static final String PATH_CHILDREN = "children";
    private static final String PATH_SEARCH = "search";
    // TODO(b/72055774): make private again once ScopedAccessProvider is refactored
    /** {@hide} */
    @UnsupportedAppUsage
    public static final String PATH_TREE = "tree";

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
     * Check if the values match the query arguments.
     *
     * @param queryArgs the query arguments
     * @param displayName the display time to check against
     * @param mimeType the mime type to check against
     * @param lastModified the last modified time to check against
     * @param size the size to check against
     * @hide
     */
    public static boolean matchSearchQueryArguments(Bundle queryArgs, String displayName,
            String mimeType, long lastModified, long size) {
        if (queryArgs == null) {
            return true;
        }

        final String argDisplayName = queryArgs.getString(QUERY_ARG_DISPLAY_NAME, "");
        if (!argDisplayName.isEmpty()) {
            // TODO (118795812) : Enhance the search string handled in DocumentsProvider
            if (!displayName.toLowerCase().contains(argDisplayName.toLowerCase())) {
                return false;
            }
        }

        final long argFileSize = queryArgs.getLong(QUERY_ARG_FILE_SIZE_OVER, -1 /* defaultValue */);
        if (argFileSize != -1 && size < argFileSize) {
            return false;
        }

        final long argLastModified = queryArgs.getLong(QUERY_ARG_LAST_MODIFIED_AFTER,
                -1 /* defaultValue */);
        if (argLastModified != -1 && lastModified < argLastModified) {
            return false;
        }

        final String[] argMimeTypes = queryArgs.getStringArray(QUERY_ARG_MIME_TYPES);
        if (argMimeTypes != null && argMimeTypes.length > 0) {
            mimeType = Intent.normalizeMimeType(mimeType);
            for (String type : argMimeTypes) {
                if (MimeTypeFilter.matches(mimeType, Intent.normalizeMimeType(type))) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Get the handled query arguments from the query bundle. The handled arguments are
     * {@link DocumentsContract#QUERY_ARG_EXCLUDE_MEDIA},
     * {@link DocumentsContract#QUERY_ARG_DISPLAY_NAME},
     * {@link DocumentsContract#QUERY_ARG_MIME_TYPES},
     * {@link DocumentsContract#QUERY_ARG_FILE_SIZE_OVER} and
     * {@link DocumentsContract#QUERY_ARG_LAST_MODIFIED_AFTER}.
     *
     * @param queryArgs the query arguments to be parsed.
     * @return the handled query arguments
     * @hide
     */
    public static String[] getHandledQueryArguments(Bundle queryArgs) {
        if (queryArgs == null) {
            return new String[0];
        }

        final ArrayList<String> args = new ArrayList<>();

        if (queryArgs.keySet().contains(QUERY_ARG_EXCLUDE_MEDIA)) {
            args.add(QUERY_ARG_EXCLUDE_MEDIA);
        }

        if (queryArgs.keySet().contains(QUERY_ARG_DISPLAY_NAME)) {
            args.add(QUERY_ARG_DISPLAY_NAME);
        }

        if (queryArgs.keySet().contains(QUERY_ARG_FILE_SIZE_OVER)) {
            args.add(QUERY_ARG_FILE_SIZE_OVER);
        }

        if (queryArgs.keySet().contains(QUERY_ARG_LAST_MODIFIED_AFTER)) {
            args.add(QUERY_ARG_LAST_MODIFIED_AFTER);
        }

        if (queryArgs.keySet().contains(QUERY_ARG_MIME_TYPES)) {
            args.add(QUERY_ARG_MIME_TYPES);
        }
        return args.toArray(new String[0]);
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

    /**
     * Test if the given URI represents all roots of the authority
     * backed by {@link DocumentsProvider}.
     *
     * @see #buildRootsUri(String)
     */
    public static boolean isRootsUri(@NonNull Context context, @Nullable Uri uri) {
        Preconditions.checkNotNull(context, "context can not be null");
        return isRootUri(context, uri, 1 /* pathSize */);
    }

    /**
     * Test if the given URI represents specific root backed by {@link DocumentsProvider}.
     *
     * @see #buildRootUri(String, String)
     */
    public static boolean isRootUri(@NonNull Context context, @Nullable Uri uri) {
        Preconditions.checkNotNull(context, "context can not be null");
        return isRootUri(context, uri, 2 /* pathSize */);
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

    private static boolean isRootUri(Context context, @Nullable Uri uri, int pathSize) {
        if (isContentUri(uri) && isDocumentsProvider(context, uri.getAuthority())) {
            final List<String> paths = uri.getPathSegments();
            return (paths.size() == pathSize && PATH_ROOT.equals(paths.get(0)));
        }
        return false;
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

    /**
     * Extract the search query from a Bundle
     * {@link #QUERY_ARG_DISPLAY_NAME}.
     * {@hide}
     */
    public static String getSearchDocumentsQuery(@NonNull Bundle bundle) {
        Preconditions.checkNotNull(bundle, "bundle can not be null");
        return bundle.getString(QUERY_ARG_DISPLAY_NAME, "" /* defaultValue */);
    }

    /**
     * Build URI that append the query parameter {@link PARAM_MANAGE} to
     * enable the manage mode.
     * @see DocumentsProvider#queryChildDocumentsForManage(String parentDocId, String[], String)
     * {@hide}
     */
    @SystemApi
    public static Uri setManageMode(Uri uri) {
        return uri.buildUpon().appendQueryParameter(PARAM_MANAGE, "true").build();
    }

    /**
     * Extract the manage mode from a URI built by
     * {@link #setManageMode(Uri)}.
     * {@hide}
     */
    @SystemApi
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
    public static Bitmap getDocumentThumbnail(ContentInterface content, Uri documentUri, Point size,
            CancellationSignal signal) throws FileNotFoundException {
        try {
            return ContentResolver.loadThumbnail(content, documentUri, Point.convert(size), signal,
                    ImageDecoder.ALLOCATOR_SOFTWARE);
        } catch (Exception e) {
            if (!(e instanceof OperationCanceledException)) {
                Log.w(TAG, "Failed to load thumbnail for " + documentUri + ": " + e);
            }
            rethrowIfNecessary(e);
            return null;
        }
    }

    @Deprecated
    public static Bitmap getDocumentThumbnail(ContentResolver content, Uri documentUri, Point size,
            CancellationSignal signal) throws FileNotFoundException {
        return getDocumentThumbnail((ContentInterface) content, documentUri, size, signal);
    }

    /**
     * Create a new document with given MIME type and display name.
     *
     * @param parentDocumentUri directory with {@link Document#FLAG_DIR_SUPPORTS_CREATE}
     * @param mimeType MIME type of new document
     * @param displayName name of new document
     * @return newly created document, or {@code null} if failed
     */
    public static Uri createDocument(ContentInterface content, Uri parentDocumentUri,
            String mimeType, String displayName) throws FileNotFoundException {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, parentDocumentUri);
            in.putString(Document.COLUMN_MIME_TYPE, mimeType);
            in.putString(Document.COLUMN_DISPLAY_NAME, displayName);

            final Bundle out = content.call(parentDocumentUri.getAuthority(),
                    METHOD_CREATE_DOCUMENT, null, in);
            return out.getParcelable(DocumentsContract.EXTRA_URI);
        } catch (Exception e) {
            Log.w(TAG, "Failed to create document", e);
            rethrowIfNecessary(e);
            return null;
        }
    }

    @Deprecated
    public static Uri createDocument(ContentResolver content, Uri parentDocumentUri,
            String mimeType, String displayName) throws FileNotFoundException {
        return createDocument((ContentInterface) content, parentDocumentUri, mimeType, displayName);
    }

    /**
     * Test if a document is descendant (child, grandchild, etc) from the given
     * parent.
     *
     * @param parentDocumentUri parent to verify against.
     * @param childDocumentUri child to verify.
     * @return if given document is a descendant of the given parent.
     * @see Root#FLAG_SUPPORTS_IS_CHILD
     */
    public static boolean isChildDocument(@NonNull ContentInterface content,
            @NonNull Uri parentDocumentUri, @NonNull Uri childDocumentUri)
            throws FileNotFoundException {
        Preconditions.checkNotNull(content, "content can not be null");
        Preconditions.checkNotNull(parentDocumentUri, "parentDocumentUri can not be null");
        Preconditions.checkNotNull(childDocumentUri, "childDocumentUri can not be null");
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, parentDocumentUri);
            in.putParcelable(DocumentsContract.EXTRA_TARGET_URI, childDocumentUri);

            final Bundle out = content.call(parentDocumentUri.getAuthority(),
                    METHOD_IS_CHILD_DOCUMENT, null, in);
            if (out == null) {
                throw new RemoteException("Failed to get a response from isChildDocument query.");
            }
            if (!out.containsKey(DocumentsContract.EXTRA_RESULT)) {
                throw new RemoteException("Response did not include result field..");
            }
            return out.getBoolean(DocumentsContract.EXTRA_RESULT);
        } catch (Exception e) {
            Log.w(TAG, "Failed to create document", e);
            rethrowIfNecessary(e);
            return false;
        }
    }

    @Deprecated
    public static boolean isChildDocument(ContentResolver content, Uri parentDocumentUri,
            Uri childDocumentUri) throws FileNotFoundException {
        return isChildDocument((ContentInterface) content, parentDocumentUri, childDocumentUri);
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
    public static Uri renameDocument(ContentInterface content, Uri documentUri,
            String displayName) throws FileNotFoundException {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, documentUri);
            in.putString(Document.COLUMN_DISPLAY_NAME, displayName);

            final Bundle out = content.call(documentUri.getAuthority(),
                    METHOD_RENAME_DOCUMENT, null, in);
            final Uri outUri = out.getParcelable(DocumentsContract.EXTRA_URI);
            return (outUri != null) ? outUri : documentUri;
        } catch (Exception e) {
            Log.w(TAG, "Failed to rename document", e);
            rethrowIfNecessary(e);
            return null;
        }
    }

    @Deprecated
    public static Uri renameDocument(ContentResolver content, Uri documentUri,
            String displayName) throws FileNotFoundException {
        return renameDocument((ContentInterface) content, documentUri, displayName);
    }

    /**
     * Delete the given document.
     *
     * @param documentUri document with {@link Document#FLAG_SUPPORTS_DELETE}
     * @return if the document was deleted successfully.
     */
    public static boolean deleteDocument(ContentInterface content, Uri documentUri)
            throws FileNotFoundException {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, documentUri);

            content.call(documentUri.getAuthority(),
                    METHOD_DELETE_DOCUMENT, null, in);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete document", e);
            rethrowIfNecessary(e);
            return false;
        }
    }

    @Deprecated
    public static boolean deleteDocument(ContentResolver content, Uri documentUri)
            throws FileNotFoundException {
        return deleteDocument((ContentInterface) content, documentUri);
    }

    /**
     * Copies the given document.
     *
     * @param sourceDocumentUri document with {@link Document#FLAG_SUPPORTS_COPY}
     * @param targetParentDocumentUri document which will become a parent of the source
     *         document's copy.
     * @return the copied document, or {@code null} if failed.
     */
    public static Uri copyDocument(ContentInterface content, Uri sourceDocumentUri,
            Uri targetParentDocumentUri) throws FileNotFoundException {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, sourceDocumentUri);
            in.putParcelable(DocumentsContract.EXTRA_TARGET_URI, targetParentDocumentUri);

            final Bundle out = content.call(sourceDocumentUri.getAuthority(),
                    METHOD_COPY_DOCUMENT, null, in);
            return out.getParcelable(DocumentsContract.EXTRA_URI);
        } catch (Exception e) {
            Log.w(TAG, "Failed to copy document", e);
            rethrowIfNecessary(e);
            return null;
        }
    }

    @Deprecated
    public static Uri copyDocument(ContentResolver content, Uri sourceDocumentUri,
            Uri targetParentDocumentUri) throws FileNotFoundException {
        return copyDocument((ContentInterface) content, sourceDocumentUri, targetParentDocumentUri);
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
    public static Uri moveDocument(ContentInterface content, Uri sourceDocumentUri,
            Uri sourceParentDocumentUri, Uri targetParentDocumentUri) throws FileNotFoundException {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, sourceDocumentUri);
            in.putParcelable(DocumentsContract.EXTRA_PARENT_URI, sourceParentDocumentUri);
            in.putParcelable(DocumentsContract.EXTRA_TARGET_URI, targetParentDocumentUri);

            final Bundle out = content.call(sourceDocumentUri.getAuthority(),
                    METHOD_MOVE_DOCUMENT, null, in);
            return out.getParcelable(DocumentsContract.EXTRA_URI);
        } catch (Exception e) {
            Log.w(TAG, "Failed to move document", e);
            rethrowIfNecessary(e);
            return null;
        }
    }

    @Deprecated
    public static Uri moveDocument(ContentResolver content, Uri sourceDocumentUri,
            Uri sourceParentDocumentUri, Uri targetParentDocumentUri) throws FileNotFoundException {
        return moveDocument((ContentInterface) content, sourceDocumentUri, sourceParentDocumentUri,
                targetParentDocumentUri);
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
    public static boolean removeDocument(ContentInterface content, Uri documentUri,
            Uri parentDocumentUri) throws FileNotFoundException {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, documentUri);
            in.putParcelable(DocumentsContract.EXTRA_PARENT_URI, parentDocumentUri);

            content.call(documentUri.getAuthority(),
                    METHOD_REMOVE_DOCUMENT, null, in);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove document", e);
            rethrowIfNecessary(e);
            return false;
        }
    }

    @Deprecated
    public static boolean removeDocument(ContentResolver content, Uri documentUri,
            Uri parentDocumentUri) throws FileNotFoundException {
        return removeDocument((ContentInterface) content, documentUri, parentDocumentUri);
    }

    /**
     * Ejects the given root. It throws {@link IllegalStateException} when ejection failed.
     *
     * @param rootUri root with {@link Root#FLAG_SUPPORTS_EJECT} to be ejected
     */
    public static void ejectRoot(ContentInterface content, Uri rootUri) {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, rootUri);

            content.call(rootUri.getAuthority(),
                    METHOD_EJECT_ROOT, null, in);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    @Deprecated
    public static void ejectRoot(ContentResolver content, Uri rootUri) {
        ejectRoot((ContentInterface) content, rootUri);
    }

    /**
     * Returns metadata associated with the document. The type of metadata returned
     * is specific to the document type. For example the data returned for an image
     * file will likely consist primarily or solely of EXIF metadata.
     *
     * <p>The returned {@link Bundle} will contain zero or more entries depending
     * on the type of data supported by the document provider.
     *
     * <ol>
     * <li>A {@link DocumentsContract#METADATA_TYPES} containing a {@code String[]} value.
     *     The string array identifies the type or types of metadata returned. Each
     *     value in the can be used to access a {@link Bundle} of data
     *     containing that type of data.
     * <li>An entry each for each type of returned metadata. Each set of metadata is
     *     itself represented as a bundle and accessible via a string key naming
     *     the type of data.
     * </ol>
     *
     * <p>Example:
     * <p><pre><code>
     *     Bundle metadata = DocumentsContract.getDocumentMetadata(client, imageDocUri, tags);
     *     if (metadata.containsKey(DocumentsContract.METADATA_EXIF)) {
     *         Bundle exif = metadata.getBundle(DocumentsContract.METADATA_EXIF);
     *         int imageLength = exif.getInt(ExifInterface.TAG_IMAGE_LENGTH);
     *     }
     * </code></pre>
     *
     * @param documentUri a Document URI
     * @return a Bundle of Bundles.
     */
    public static @Nullable Bundle getDocumentMetadata(@NonNull ContentInterface content,
            @NonNull Uri documentUri) throws FileNotFoundException {
        Preconditions.checkNotNull(content, "content can not be null");
        Preconditions.checkNotNull(documentUri, "documentUri can not be null");
        try {
            final Bundle in = new Bundle();
            in.putParcelable(EXTRA_URI, documentUri);

            return content.call(documentUri.getAuthority(),
                    METHOD_GET_DOCUMENT_METADATA, null, in);
        } catch (Exception e) {
            Log.w(TAG, "Failed to get document metadata");
            rethrowIfNecessary(e);
            return null;
        }
    }

    @Deprecated
    public static Bundle getDocumentMetadata(ContentResolver content, Uri documentUri)
            throws FileNotFoundException {
        return getDocumentMetadata((ContentInterface) content, documentUri);
    }

    /**
     * Finds the canonical path from the top of the document tree.
     *
     * The {@link Path#getPath()} of the return value contains the document ID
     * of all documents along the path from the top the document tree to the
     * requested document, both inclusive.
     *
     * The {@link Path#getRootId()} of the return value returns {@code null}.
     *
     * @param treeUri treeUri of the document which path is requested.
     * @return the path of the document, or {@code null} if failed.
     * @see DocumentsProvider#findDocumentPath(String, String)
     */
    public static Path findDocumentPath(ContentInterface content, Uri treeUri)
            throws FileNotFoundException {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, treeUri);

            final Bundle out = content.call(treeUri.getAuthority(),
                    METHOD_FIND_DOCUMENT_PATH, null, in);
            return out.getParcelable(DocumentsContract.EXTRA_RESULT);
        } catch (Exception e) {
            Log.w(TAG, "Failed to find path", e);
            rethrowIfNecessary(e);
            return null;
        }
    }

    @Deprecated
    public static Path findDocumentPath(ContentResolver content, Uri treeUri)
            throws FileNotFoundException {
        return findDocumentPath((ContentInterface) content, treeUri);
    }

    /**
     * Creates an intent for obtaining a web link for the specified document.
     *
     * <p>Note, that due to internal limitations, if there is already a web link
     * intent created for the specified document but with different options,
     * then it may be overridden.
     *
     * <p>Providers are required to show confirmation UI for all new permissions granted
     * for the linked document.
     *
     * <p>If list of recipients is known, then it should be passed in options as
     * {@link Intent#EXTRA_EMAIL} as a list of email addresses. Note, that
     * this is just a hint for the provider, which can ignore the list. In either
     * case the provider is required to show a UI for letting the user confirm
     * any new permission grants.
     *
     * <p>Note, that the entire <code>options</code> bundle will be sent to the provider
     * backing the passed <code>uri</code>. Make sure that you trust the provider
     * before passing any sensitive information.
     *
     * <p>Since this API may show a UI, it cannot be called from background.
     *
     * <p>In order to obtain the Web Link use code like this:
     * <pre><code>
     * void onSomethingHappened() {
     *   IntentSender sender = DocumentsContract.createWebLinkIntent(<i>...</i>);
     *   if (sender != null) {
     *     startIntentSenderForResult(
     *         sender,
     *         WEB_LINK_REQUEST_CODE,
     *         null, 0, 0, 0, null);
     *   }
     * }
     *
     * <i>(...)</i>
     *
     * void onActivityResult(int requestCode, int resultCode, Intent data) {
     *   if (requestCode == WEB_LINK_REQUEST_CODE && resultCode == RESULT_OK) {
     *     Uri weblinkUri = data.getData();
     *     <i>...</i>
     *   }
     * }
     * </code></pre>
     *
     * @param uri uri for the document to create a link to.
     * @param options Extra information for generating the link.
     * @return an intent sender to obtain the web link, or null if the document
     *      is not linkable, or creating the intent sender failed.
     * @see DocumentsProvider#createWebLinkIntent(String, Bundle)
     * @see Intent#EXTRA_EMAIL
     */
    public static IntentSender createWebLinkIntent(ContentInterface content, Uri uri,
            Bundle options) throws FileNotFoundException {
        try {
            final Bundle in = new Bundle();
            in.putParcelable(DocumentsContract.EXTRA_URI, uri);

            // Options may be provider specific, so put them in a separate bundle to
            // avoid overriding the Uri.
            if (options != null) {
                in.putBundle(EXTRA_OPTIONS, options);
            }

            final Bundle out = content.call(uri.getAuthority(),
                    METHOD_CREATE_WEB_LINK_INTENT, null, in);
            return out.getParcelable(DocumentsContract.EXTRA_RESULT);
        } catch (Exception e) {
            Log.w(TAG, "Failed to create a web link intent", e);
            rethrowIfNecessary(e);
            return null;
        }
    }

    @Deprecated
    public static IntentSender createWebLinkIntent(ContentResolver content, Uri uri,
            Bundle options) throws FileNotFoundException {
        return createWebLinkIntent((ContentInterface) content, uri, options);
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

    private static void rethrowIfNecessary(Exception e) throws FileNotFoundException {
        // We only want to throw applications targetting O and above
        if (VMRuntime.getRuntime().getTargetSdkVersion() >= Build.VERSION_CODES.O) {
            if (e instanceof ParcelableException) {
                ((ParcelableException) e).maybeRethrow(FileNotFoundException.class);
            } else if (e instanceof RemoteException) {
                ((RemoteException) e).rethrowAsRuntimeException();
            } else if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
        }
    }

    /**
     * Holds a path from a document to a particular document under it. It
     * may also contains the root ID where the path resides.
     */
    public static final class Path implements Parcelable {

        private final @Nullable String mRootId;
        private final List<String> mPath;

        /**
         * Creates a Path.
         *
         * @param rootId the ID of the root. May be null.
         * @param path the list of document ID from the parent document at
         *          position 0 to the child document.
         */
        public Path(@Nullable String rootId, List<String> path) {
            checkCollectionNotEmpty(path, "path");
            checkCollectionElementsNotNull(path, "path");

            mRootId = rootId;
            mPath = path;
        }

        /**
         * Returns the root id or null if the calling package doesn't have
         * permission to access root information.
         */
        public @Nullable String getRootId() {
            return mRootId;
        }

        /**
         * Returns the path. The path is trimmed to the top of tree if
         * calling package doesn't have permission to access those
         * documents.
         */
        public List<String> getPath() {
            return mPath;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || !(o instanceof Path)) {
                return false;
            }
            Path path = (Path) o;
            return Objects.equals(mRootId, path.mRootId) &&
                    Objects.equals(mPath, path.mPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mRootId, mPath);
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("DocumentsContract.Path{")
                    .append("rootId=")
                    .append(mRootId)
                    .append(", path=")
                    .append(mPath)
                    .append("}")
                    .toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mRootId);
            dest.writeStringList(mPath);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Path> CREATOR = new Creator<Path>() {
            @Override
            public Path createFromParcel(Parcel in) {
                final String rootId = in.readString();
                final List<String> path = in.createStringArrayList();
                return new Path(rootId, path);
            }

            @Override
            public Path[] newArray(int size) {
                return new Path[size];
            }
        };
    }
}
