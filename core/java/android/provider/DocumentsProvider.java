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

import static android.provider.DocumentsContract.EXTRA_THUMBNAIL_SIZE;
import static android.provider.DocumentsContract.METHOD_CREATE_DOCUMENT;
import static android.provider.DocumentsContract.METHOD_DELETE_DOCUMENT;
import static android.provider.DocumentsContract.getDocumentId;
import static android.provider.DocumentsContract.getRootId;
import static android.provider.DocumentsContract.getSearchDocumentsQuery;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;

/**
 * Base class for a document provider. A document provider should extend this
 * class and implement the abstract methods.
 * <p>
 * Each document provider expresses one or more "roots" which each serve as the
 * top-level of a tree. For example, a root could represent an account, or a
 * physical storage device. Under each root, documents are referenced by
 * {@link Document#COLUMN_DOCUMENT_ID}, which must not change once returned.
 * <p>
 * Documents can be either an openable file (with a specific MIME type), or a
 * directory containing additional documents (with the
 * {@link Document#MIME_TYPE_DIR} MIME type). Each document can have different
 * capabilities, as described by {@link Document#COLUMN_FLAGS}. The same
 * {@link Document#COLUMN_DOCUMENT_ID} can be included in multiple directories.
 * <p>
 * Document providers must be protected with the
 * {@link android.Manifest.permission#MANAGE_DOCUMENTS} permission, which can
 * only be requested by the system. The system-provided UI then issues narrow
 * Uri permission grants for individual documents when the user explicitly picks
 * documents.
 *
 * @see Intent#ACTION_OPEN_DOCUMENT
 * @see Intent#ACTION_CREATE_DOCUMENT
 */
public abstract class DocumentsProvider extends ContentProvider {
    private static final String TAG = "DocumentsProvider";

    private static final int MATCH_ROOTS = 1;
    private static final int MATCH_ROOT = 2;
    private static final int MATCH_RECENT = 3;
    private static final int MATCH_SEARCH = 4;
    private static final int MATCH_DOCUMENT = 5;
    private static final int MATCH_CHILDREN = 6;

    private String mAuthority;

    private UriMatcher mMatcher;

    /**
     * Implementation is provided by the parent class.
     */
    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        mAuthority = info.authority;

        mMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mMatcher.addURI(mAuthority, "root", MATCH_ROOTS);
        mMatcher.addURI(mAuthority, "root/*", MATCH_ROOT);
        mMatcher.addURI(mAuthority, "root/*/recent", MATCH_RECENT);
        mMatcher.addURI(mAuthority, "root/*/search", MATCH_SEARCH);
        mMatcher.addURI(mAuthority, "document/*", MATCH_DOCUMENT);
        mMatcher.addURI(mAuthority, "document/*/children", MATCH_CHILDREN);

        // Sanity check our setup
        if (!info.exported) {
            throw new SecurityException("Provider must be exported");
        }
        if (!info.grantUriPermissions) {
            throw new SecurityException("Provider must grantUriPermissions");
        }
        if (!android.Manifest.permission.MANAGE_DOCUMENTS.equals(info.readPermission)
                || !android.Manifest.permission.MANAGE_DOCUMENTS.equals(info.writePermission)) {
            throw new SecurityException("Provider must be protected by MANAGE_DOCUMENTS");
        }

        super.attachInfo(context, info);
    }

    /**
     * Create a new document and return its {@link Document#COLUMN_DOCUMENT_ID}.
     * A provider must allocate a new {@link Document#COLUMN_DOCUMENT_ID} to
     * represent the document, which must not change once returned.
     *
     * @param documentId the parent directory to create the new document under.
     * @param mimeType the MIME type associated with the new document.
     * @param displayName the display name of the new document.
     */
    @SuppressWarnings("unused")
    public String createDocument(String documentId, String mimeType, String displayName)
            throws FileNotFoundException {
        throw new UnsupportedOperationException("Create not supported");
    }

    /**
     * Delete the given document. Upon returning, any Uri permission grants for
     * the given document will be revoked. If additional documents were deleted
     * as a side effect of this call, such as documents inside a directory, the
     * implementor is responsible for revoking those permissions.
     *
     * @param documentId the document to delete.
     */
    @SuppressWarnings("unused")
    public void deleteDocument(String documentId) throws FileNotFoundException {
        throw new UnsupportedOperationException("Delete not supported");
    }

    public abstract Cursor queryRoots(String[] projection) throws FileNotFoundException;

    @SuppressWarnings("unused")
    public Cursor queryRecentDocuments(String rootId, String[] projection)
            throws FileNotFoundException {
        throw new UnsupportedOperationException("Recent not supported");
    }

    /**
     * Return metadata for the given document. A provider should avoid making
     * network requests to keep this request fast.
     *
     * @param documentId the document to return.
     */
    public abstract Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException;

    /**
     * Return the children of the given document which is a directory.
     *
     * @param parentDocumentId the directory to return children for.
     */
    public abstract Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException;

    /** {@hide} */
    @SuppressWarnings("unused")
    public Cursor queryChildDocumentsForManage(
            String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        throw new UnsupportedOperationException("Manage not supported");
    }

    /**
     * Return documents that that match the given query.
     *
     * @param rootId the root to search under.
     */
    @SuppressWarnings("unused")
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        throw new UnsupportedOperationException("Search not supported");
    }

    /**
     * Return MIME type for the given document. Must match the value of
     * {@link Document#COLUMN_MIME_TYPE} for this document.
     */
    public String getDocumentType(String documentId) throws FileNotFoundException {
        final Cursor cursor = queryDocument(documentId, null);
        try {
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE));
            } else {
                return null;
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }
    }

    /**
     * Open and return the requested document. A provider should return a
     * reliable {@link ParcelFileDescriptor} to detect when the remote caller
     * has finished reading or writing the document. A provider may return a
     * pipe or socket pair if the mode is exclusively
     * {@link ParcelFileDescriptor#MODE_READ_ONLY} or
     * {@link ParcelFileDescriptor#MODE_WRITE_ONLY}, but complex modes like
     * {@link ParcelFileDescriptor#MODE_READ_WRITE} require a normal file on
     * disk. If a provider blocks while downloading content, it should
     * periodically check {@link CancellationSignal#isCanceled()} to abort
     * abandoned open requests.
     *
     * @param docId the document to return.
     * @param mode the mode to open with, such as 'r', 'w', or 'rw'.
     * @param signal used by the caller to signal if the request should be
     *            cancelled.
     * @see ParcelFileDescriptor#open(java.io.File, int, android.os.Handler,
     *      OnCloseListener)
     * @see ParcelFileDescriptor#createReliablePipe()
     * @see ParcelFileDescriptor#createReliableSocketPair()
     */
    public abstract ParcelFileDescriptor openDocument(
            String docId, String mode, CancellationSignal signal) throws FileNotFoundException;

    /**
     * Open and return a thumbnail of the requested document. A provider should
     * return a thumbnail closely matching the hinted size, attempting to serve
     * from a local cache if possible. A provider should never return images
     * more than double the hinted size. If a provider performs expensive
     * operations to download or generate a thumbnail, it should periodically
     * check {@link CancellationSignal#isCanceled()} to abort abandoned
     * thumbnail requests.
     *
     * @param docId the document to return.
     * @param sizeHint hint of the optimal thumbnail dimensions.
     * @param signal used by the caller to signal if the request should be
     *            cancelled.
     * @see Document#FLAG_SUPPORTS_THUMBNAIL
     */
    @SuppressWarnings("unused")
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("Thumbnails not supported");
    }

    /**
     * Implementation is provided by the parent class. Cannot be overriden.
     *
     * @see #queryRoots(String[])
     * @see #queryRecentDocuments(String, String[])
     * @see #queryDocument(String, String[])
     * @see #queryChildDocuments(String, String[], String)
     * @see #querySearchDocuments(String, String, String[])
     */
    @Override
    public final Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        try {
            switch (mMatcher.match(uri)) {
                case MATCH_ROOTS:
                    return queryRoots(projection);
                case MATCH_RECENT:
                    return queryRecentDocuments(getRootId(uri), projection);
                case MATCH_SEARCH:
                    return querySearchDocuments(
                            getRootId(uri), getSearchDocumentsQuery(uri), projection);
                case MATCH_DOCUMENT:
                    return queryDocument(getDocumentId(uri), projection);
                case MATCH_CHILDREN:
                    if (DocumentsContract.isManageMode(uri)) {
                        return queryChildDocumentsForManage(
                                getDocumentId(uri), projection, sortOrder);
                    } else {
                        return queryChildDocuments(getDocumentId(uri), projection, sortOrder);
                    }
                default:
                    throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Failed during query", e);
            return null;
        }
    }

    /**
     * Implementation is provided by the parent class. Cannot be overriden.
     *
     * @see #getDocumentType(String)
     */
    @Override
    public final String getType(Uri uri) {
        try {
            switch (mMatcher.match(uri)) {
                case MATCH_ROOT:
                    return DocumentsContract.Root.MIME_TYPE_ITEM;
                case MATCH_DOCUMENT:
                    return getDocumentType(getDocumentId(uri));
                default:
                    return null;
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Failed during getType", e);
            return null;
        }
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overriden.
     *
     * @see #createDocument(String, String, String)
     */
    @Override
    public final Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert not supported");
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overriden.
     *
     * @see #deleteDocument(String)
     */
    @Override
    public final int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Delete not supported");
    }

    /**
     * Implementation is provided by the parent class. Throws by default, and
     * cannot be overriden.
     */
    @Override
    public final int update(
            Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update not supported");
    }

    /**
     * Implementation is provided by the parent class. Can be overridden to
     * provide additional functionality, but subclasses <em>must</em> always
     * call the superclass. If the superclass returns {@code null}, the subclass
     * may implement custom behavior.
     *
     * @see #openDocument(String, String, CancellationSignal)
     * @see #deleteDocument(String)
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        final Context context = getContext();

        if (!method.startsWith("android:")) {
            // Let non-platform methods pass through
            return super.call(method, arg, extras);
        }

        final String documentId = extras.getString(Document.COLUMN_DOCUMENT_ID);
        final Uri documentUri = DocumentsContract.buildDocumentUri(mAuthority, documentId);

        // Require that caller can manage given document
        final boolean callerHasManage =
                context.checkCallingOrSelfPermission(android.Manifest.permission.MANAGE_DOCUMENTS)
                == PackageManager.PERMISSION_GRANTED;
        if (!callerHasManage) {
            getContext().enforceCallingOrSelfUriPermission(
                    documentUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, method);
        }

        final Bundle out = new Bundle();
        try {
            if (METHOD_CREATE_DOCUMENT.equals(method)) {
                final String mimeType = extras.getString(Document.COLUMN_MIME_TYPE);
                final String displayName = extras.getString(Document.COLUMN_DISPLAY_NAME);

                final String newDocumentId = createDocument(documentId, mimeType, displayName);
                out.putString(Document.COLUMN_DOCUMENT_ID, newDocumentId);

                // Extend permission grant towards caller if needed
                if (!callerHasManage) {
                    final Uri newDocumentUri = DocumentsContract.buildDocumentUri(
                            mAuthority, newDocumentId);
                    context.grantUriPermission(getCallingPackage(), newDocumentUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                }

            } else if (METHOD_DELETE_DOCUMENT.equals(method)) {
                deleteDocument(documentId);

                // Document no longer exists, clean up any grants
                context.revokeUriPermission(documentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

            } else {
                throw new UnsupportedOperationException("Method not supported " + method);
            }
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Failed call " + method, e);
        }
        return out;
    }

    /**
     * Implementation is provided by the parent class.
     *
     * @see #openDocument(String, String, CancellationSignal)
     */
    @Override
    public final ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openDocument(getDocumentId(uri), mode, null);
    }

    /**
     * Implementation is provided by the parent class.
     *
     * @see #openDocument(String, String, CancellationSignal)
     */
    @Override
    public final ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return openDocument(getDocumentId(uri), mode, signal);
    }

    /**
     * Implementation is provided by the parent class.
     *
     * @see #openDocumentThumbnail(String, Point, CancellationSignal)
     */
    @Override
    public final AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {
        if (opts != null && opts.containsKey(EXTRA_THUMBNAIL_SIZE)) {
            final Point sizeHint = opts.getParcelable(EXTRA_THUMBNAIL_SIZE);
            return openDocumentThumbnail(getDocumentId(uri), sizeHint, null);
        } else {
            return super.openTypedAssetFile(uri, mimeTypeFilter, opts);
        }
    }

    /**
     * Implementation is provided by the parent class.
     *
     * @see #openDocumentThumbnail(String, Point, CancellationSignal)
     */
    @Override
    public final AssetFileDescriptor openTypedAssetFile(
            Uri uri, String mimeTypeFilter, Bundle opts, CancellationSignal signal)
            throws FileNotFoundException {
        if (opts != null && opts.containsKey(EXTRA_THUMBNAIL_SIZE)) {
            final Point sizeHint = opts.getParcelable(EXTRA_THUMBNAIL_SIZE);
            return openDocumentThumbnail(getDocumentId(uri), sizeHint, signal);
        } else {
            return super.openTypedAssetFile(uri, mimeTypeFilter, opts, signal);
        }
    }
}
