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

import static android.provider.DocumentsContract.ACTION_DOCUMENT_ROOT_CHANGED;
import static android.provider.DocumentsContract.EXTRA_AUTHORITY;
import static android.provider.DocumentsContract.EXTRA_ROOTS;
import static android.provider.DocumentsContract.EXTRA_THUMBNAIL_SIZE;
import static android.provider.DocumentsContract.METHOD_CREATE_DOCUMENT;
import static android.provider.DocumentsContract.METHOD_DELETE_DOCUMENT;
import static android.provider.DocumentsContract.METHOD_GET_ROOTS;
import static android.provider.DocumentsContract.METHOD_RENAME_DOCUMENT;
import static android.provider.DocumentsContract.getDocId;
import static android.provider.DocumentsContract.getSearchQuery;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.DocumentRoot;
import android.provider.DocumentsContract.Documents;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * Base class for a document provider. A document provider should extend this
 * class and implement the abstract methods.
 * <p>
 * Each document provider expresses one or more "roots" which each serve as the
 * top-level of a tree. For example, a root could represent an account, or a
 * physical storage device. Under each root, documents are referenced by
 * {@link DocumentColumns#DOC_ID}, which must not change once returned.
 * <p>
 * Documents can be either an openable file (with a specific MIME type), or a
 * directory containing additional documents (with the
 * {@link Documents#MIME_TYPE_DIR} MIME type). Each document can have different
 * capabilities, as described by {@link DocumentColumns#FLAGS}. The same
 * {@link DocumentColumns#DOC_ID} can be included in multiple directories.
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

    private static final int MATCH_DOCUMENT = 1;
    private static final int MATCH_CHILDREN = 2;
    private static final int MATCH_SEARCH = 3;

    private String mAuthority;

    private UriMatcher mMatcher;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        mAuthority = info.authority;

        mMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mMatcher.addURI(mAuthority, "docs/*", MATCH_DOCUMENT);
        mMatcher.addURI(mAuthority, "docs/*/children", MATCH_CHILDREN);
        mMatcher.addURI(mAuthority, "docs/*/search", MATCH_SEARCH);

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
     * Return list of all document roots provided by this document provider.
     * When this list changes, a provider must call
     * {@link #notifyDocumentRootsChanged()}.
     */
    public abstract List<DocumentRoot> getDocumentRoots();

    /**
     * Create and return a new document. A provider must allocate a new
     * {@link DocumentColumns#DOC_ID} to represent the document, which must not
     * change once returned.
     *
     * @param docId the parent directory to create the new document under.
     * @param mimeType the MIME type associated with the new document.
     * @param displayName the display name of the new document.
     */
    @SuppressWarnings("unused")
    public String createDocument(String docId, String mimeType, String displayName)
            throws FileNotFoundException {
        throw new UnsupportedOperationException("Create not supported");
    }

    /**
     * Rename the given document.
     *
     * @param docId the document to rename.
     * @param displayName the new display name.
     */
    @SuppressWarnings("unused")
    public void renameDocument(String docId, String displayName) throws FileNotFoundException {
        throw new UnsupportedOperationException("Rename not supported");
    }

    /**
     * Delete the given document.
     *
     * @param docId the document to delete.
     */
    @SuppressWarnings("unused")
    public void deleteDocument(String docId) throws FileNotFoundException {
        throw new UnsupportedOperationException("Delete not supported");
    }

    /**
     * Return metadata for the given document. A provider should avoid making
     * network requests to keep this request fast.
     *
     * @param docId the document to return.
     */
    public abstract Cursor queryDocument(String docId) throws FileNotFoundException;

    /**
     * Return the children of the given document which is a directory.
     *
     * @param docId the directory to return children for.
     */
    public abstract Cursor queryDocumentChildren(String docId) throws FileNotFoundException;

    /**
     * Return documents that that match the given query, starting the search at
     * the given directory.
     *
     * @param docId the directory to start search at.
     */
    @SuppressWarnings("unused")
    public Cursor querySearch(String docId, String query) throws FileNotFoundException {
        throw new UnsupportedOperationException("Search not supported");
    }

    /**
     * Return MIME type for the given document. Must match the value of
     * {@link DocumentColumns#MIME_TYPE} for this document.
     */
    public String getType(String docId) throws FileNotFoundException {
        final Cursor cursor = queryDocument(docId);
        try {
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(DocumentColumns.MIME_TYPE));
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
     * @see Documents#FLAG_SUPPORTS_THUMBNAIL
     */
    @SuppressWarnings("unused")
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        throw new UnsupportedOperationException("Thumbnails not supported");
    }

    @Override
    public final Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        try {
            switch (mMatcher.match(uri)) {
                case MATCH_DOCUMENT:
                    return queryDocument(getDocId(uri));
                case MATCH_CHILDREN:
                    return queryDocumentChildren(getDocId(uri));
                case MATCH_SEARCH:
                    return querySearch(getDocId(uri), getSearchQuery(uri));
                default:
                    throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Failed during query", e);
            return null;
        }
    }

    @Override
    public final String getType(Uri uri) {
        try {
            switch (mMatcher.match(uri)) {
                case MATCH_DOCUMENT:
                    return getType(getDocId(uri));
                default:
                    return null;
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Failed during getType", e);
            return null;
        }
    }

    @Override
    public final Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert not supported");
    }

    @Override
    public final int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Delete not supported");
    }

    @Override
    public final int update(
            Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update not supported");
    }

    @Override
    public final Bundle callFromPackage(
            String callingPackage, String method, String arg, Bundle extras) {
        if (!method.startsWith("android:")) {
            // Let non-platform methods pass through
            return super.callFromPackage(callingPackage, method, arg, extras);
        }

        // Platform operations require the caller explicitly hold manage
        // permission; Uri permissions don't extend management operations.
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_DOCUMENTS, "Document management");

        final Bundle out = new Bundle();
        try {
            if (METHOD_GET_ROOTS.equals(method)) {
                final List<DocumentRoot> roots = getDocumentRoots();
                out.putParcelableList(EXTRA_ROOTS, roots);

            } else if (METHOD_CREATE_DOCUMENT.equals(method)) {
                final String docId = extras.getString(DocumentColumns.DOC_ID);
                final String mimeType = extras.getString(DocumentColumns.MIME_TYPE);
                final String displayName = extras.getString(DocumentColumns.DISPLAY_NAME);

                // TODO: issue Uri grant towards caller
                final String newDocId = createDocument(docId, mimeType, displayName);
                out.putString(DocumentColumns.DOC_ID, newDocId);

            } else if (METHOD_RENAME_DOCUMENT.equals(method)) {
                final String docId = extras.getString(DocumentColumns.DOC_ID);
                final String displayName = extras.getString(DocumentColumns.DISPLAY_NAME);
                renameDocument(docId, displayName);

            } else if (METHOD_DELETE_DOCUMENT.equals(method)) {
                final String docId = extras.getString(DocumentColumns.DOC_ID);
                deleteDocument(docId);

            } else {
                throw new UnsupportedOperationException("Method not supported " + method);
            }
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Failed call " + method, e);
        }
        return out;
    }

    @Override
    public final ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openDocument(getDocId(uri), mode, null);
    }

    @Override
    public final ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return openDocument(getDocId(uri), mode, signal);
    }

    @Override
    public final AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {
        if (opts != null && opts.containsKey(EXTRA_THUMBNAIL_SIZE)) {
            final Point sizeHint = opts.getParcelable(EXTRA_THUMBNAIL_SIZE);
            return openDocumentThumbnail(getDocId(uri), sizeHint, null);
        } else {
            return super.openTypedAssetFile(uri, mimeTypeFilter, opts);
        }
    }

    @Override
    public final AssetFileDescriptor openTypedAssetFile(
            Uri uri, String mimeTypeFilter, Bundle opts, CancellationSignal signal)
            throws FileNotFoundException {
        if (opts != null && opts.containsKey(EXTRA_THUMBNAIL_SIZE)) {
            final Point sizeHint = opts.getParcelable(EXTRA_THUMBNAIL_SIZE);
            return openDocumentThumbnail(getDocId(uri), sizeHint, signal);
        } else {
            return super.openTypedAssetFile(uri, mimeTypeFilter, opts, signal);
        }
    }

    /**
     * Notify system that {@link #getDocumentRoots()} has changed, usually due to an
     * account or device change.
     */
    public void notifyDocumentRootsChanged() {
        final Intent intent = new Intent(ACTION_DOCUMENT_ROOT_CHANGED);
        intent.putExtra(EXTRA_AUTHORITY, mAuthority);
        getContext().sendBroadcast(intent);
    }
}
