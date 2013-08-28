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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.OnCloseListener;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.util.Preconditions;
import com.google.android.collect.Lists;

import libcore.io.ErrnoException;
import libcore.io.IoBridge;
import libcore.io.IoUtils;
import libcore.io.Libcore;

import java.io.FileDescriptor;
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

    // content://com.example/docs/12/
    // content://com.example/docs/12/children/
    // content://com.example/docs/12/search/?query=pony

    private DocumentsContract() {
    }

    /** {@hide} */
    public static final String META_DATA_DOCUMENT_PROVIDER = "android.content.DOCUMENT_PROVIDER";

    /** {@hide} */
    public static final String ACTION_MANAGE_DOCUMENTS = "android.provider.action.MANAGE_DOCUMENTS";

    /** {@hide} */
    public static final String
            ACTION_DOCUMENT_ROOT_CHANGED = "android.provider.action.DOCUMENT_ROOT_CHANGED";

    /**
     * Constants for individual documents.
     */
    public final static class Documents {
        private Documents() {
        }

        /**
         * MIME type of a document which is a directory that may contain additional
         * documents.
         */
        public static final String MIME_TYPE_DIR = "vnd.android.doc/dir";

        /**
         * Flag indicating that a document is a directory that supports creation of
         * new files within it.
         *
         * @see DocumentColumns#FLAGS
         */
        public static final int FLAG_SUPPORTS_CREATE = 1;

        /**
         * Flag indicating that a document is renamable.
         *
         * @see DocumentColumns#FLAGS
         */
        public static final int FLAG_SUPPORTS_RENAME = 1 << 1;

        /**
         * Flag indicating that a document is deletable.
         *
         * @see DocumentColumns#FLAGS
         */
        public static final int FLAG_SUPPORTS_DELETE = 1 << 2;

        /**
         * Flag indicating that a document can be represented as a thumbnail.
         *
         * @see DocumentColumns#FLAGS
         */
        public static final int FLAG_SUPPORTS_THUMBNAIL = 1 << 3;

        /**
         * Flag indicating that a document is a directory that supports search.
         *
         * @see DocumentColumns#FLAGS
         */
        public static final int FLAG_SUPPORTS_SEARCH = 1 << 4;

        /**
         * Flag indicating that a document supports writing.
         *
         * @see DocumentColumns#FLAGS
         */
        public static final int FLAG_SUPPORTS_WRITE = 1 << 5;

        /**
         * Flag indicating that a document is a directory that prefers its contents
         * be shown in a larger format grid. Usually suitable when a directory
         * contains mostly pictures.
         *
         * @see DocumentColumns#FLAGS
         */
        public static final int FLAG_PREFERS_GRID = 1 << 6;
    }

    /**
     * Extra boolean flag included in a directory {@link Cursor#getExtras()}
     * indicating that a document provider is still loading data. For example, a
     * provider has returned some results, but is still waiting on an
     * outstanding network request.
     *
     * @see ContentResolver#notifyChange(Uri, android.database.ContentObserver,
     *      boolean)
     */
    public static final String EXTRA_LOADING = "loading";

    /**
     * Extra string included in a directory {@link Cursor#getExtras()}
     * providing an informational message that should be shown to a user. For
     * example, a provider may wish to indicate that not all documents are
     * available.
     */
    public static final String EXTRA_INFO = "info";

    /**
     * Extra string included in a directory {@link Cursor#getExtras()} providing
     * an error message that should be shown to a user. For example, a provider
     * may wish to indicate that a network error occurred. The user may choose
     * to retry, resulting in a new query.
     */
    public static final String EXTRA_ERROR = "error";

    /** {@hide} */
    public static final String METHOD_GET_ROOTS = "android:getRoots";
    /** {@hide} */
    public static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
    /** {@hide} */
    public static final String METHOD_RENAME_DOCUMENT = "android:renameDocument";
    /** {@hide} */
    public static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";

    /** {@hide} */
    public static final String EXTRA_AUTHORITY = "authority";
    /** {@hide} */
    public static final String EXTRA_PACKAGE_NAME = "packageName";
    /** {@hide} */
    public static final String EXTRA_URI = "uri";
    /** {@hide} */
    public static final String EXTRA_ROOTS = "roots";
    /** {@hide} */
    public static final String EXTRA_THUMBNAIL_SIZE = "thumbnail_size";

    private static final String PATH_DOCS = "docs";
    private static final String PATH_CHILDREN = "children";
    private static final String PATH_SEARCH = "search";

    private static final String PARAM_QUERY = "query";

    /**
     * Build Uri representing the given {@link DocumentColumns#DOC_ID} in a
     * document provider.
     */
    public static Uri buildDocumentUri(String authority, String docId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_DOCS).appendPath(docId).build();
    }

    /**
     * Build Uri representing the contents of the given directory in a document
     * provider. The given document must be {@link Documents#MIME_TYPE_DIR}.
     *
     * @hide
     */
    public static Uri buildChildrenUri(String authority, String docId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath(PATH_DOCS).appendPath(docId).appendPath(PATH_CHILDREN).build();
    }

    /**
     * Build Uri representing a search for matching documents under a specific
     * directory in a document provider. The given document must have
     * {@link Documents#FLAG_SUPPORTS_SEARCH}.
     *
     * @hide
     */
    public static Uri buildSearchUri(String authority, String docId, String query) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath(PATH_DOCS).appendPath(docId).appendPath(PATH_SEARCH)
                .appendQueryParameter(PARAM_QUERY, query).build();
    }

    /**
     * Extract the {@link DocumentColumns#DOC_ID} from the given Uri.
     */
    public static String getDocId(Uri documentUri) {
        final List<String> paths = documentUri.getPathSegments();
        if (paths.size() < 2) {
            throw new IllegalArgumentException("Not a document: " + documentUri);
        }
        if (!PATH_DOCS.equals(paths.get(0))) {
            throw new IllegalArgumentException("Not a document: " + documentUri);
        }
        return paths.get(1);
    }

    /** {@hide} */
    public static String getSearchQuery(Uri documentUri) {
        return documentUri.getQueryParameter(PARAM_QUERY);
    }

    /**
     * Standard columns for document queries. Document providers <em>must</em>
     * support at least these columns when queried.
     */
    public interface DocumentColumns extends OpenableColumns {
        /**
         * Unique ID for a document. Values <em>must</em> never change once
         * returned, since they may used for long-term Uri permission grants.
         * <p>
         * Type: STRING
         */
        public static final String DOC_ID = "doc_id";

        /**
         * MIME type of a document.
         * <p>
         * Type: STRING
         *
         * @see Documents#MIME_TYPE_DIR
         */
        public static final String MIME_TYPE = "mime_type";

        /**
         * Timestamp when a document was last modified, in milliseconds since
         * January 1, 1970 00:00:00.0 UTC, or {@code null} if unknown. Document
         * providers can update this field using events from
         * {@link OnCloseListener} or other reliable
         * {@link ParcelFileDescriptor} transports.
         * <p>
         * Type: INTEGER (long)
         *
         * @see System#currentTimeMillis()
         */
        public static final String LAST_MODIFIED = "last_modified";

        /**
         * Specific icon resource for a document, or {@code null} to resolve
         * default using {@link #MIME_TYPE}.
         * <p>
         * Type: INTEGER (int)
         */
        public static final String ICON = "icon";

        /**
         * Summary for a document, or {@code null} to omit.
         * <p>
         * Type: STRING
         */
        public static final String SUMMARY = "summary";

        /**
         * Flags that apply to a specific document.
         * <p>
         * Type: INTEGER (int)
         */
        public static final String FLAGS = "flags";
    }

    /**
     * Metadata about a specific root of documents.
     */
    public final static class DocumentRoot implements Parcelable {
        /**
         * Root that represents a storage service, such as a cloud-based
         * service.
         *
         * @see #rootType
         */
        public static final int ROOT_TYPE_SERVICE = 1;

        /**
         * Root that represents a shortcut to content that may be available
         * elsewhere through another storage root.
         *
         * @see #rootType
         */
        public static final int ROOT_TYPE_SHORTCUT = 2;

        /**
         * Root that represents a physical storage device.
         *
         * @see #rootType
         */
        public static final int ROOT_TYPE_DEVICE = 3;

        /**
         * Root that represents a physical storage device that should only be
         * displayed to advanced users.
         *
         * @see #rootType
         */
        public static final int ROOT_TYPE_DEVICE_ADVANCED = 4;

        /**
         * Flag indicating that at least one directory under this root supports
         * creating content.
         *
         * @see #flags
         */
        public static final int FLAG_SUPPORTS_CREATE = 1;

        /**
         * Flag indicating that this root offers content that is strictly local
         * on the device. That is, no network requests are made for the content.
         *
         * @see #flags
         */
        public static final int FLAG_LOCAL_ONLY = 1 << 1;

        /** {@hide} */
        public String authority;

        /**
         * Root type, use for clustering.
         *
         * @see #ROOT_TYPE_SERVICE
         * @see #ROOT_TYPE_DEVICE
         */
        public int rootType;

        /**
         * Flags for this root.
         *
         * @see #FLAG_LOCAL_ONLY
         */
        public int flags;

        /**
         * Icon resource ID for this root.
         */
        public int icon;

        /**
         * Title for this root.
         */
        public String title;

        /**
         * Summary for this root. May be {@code null}.
         */
        public String summary;

        /**
         * Document which is a directory that represents the top of this root.
         * Must not be {@code null}.
         *
         * @see DocumentColumns#DOC_ID
         */
        public String docId;

        /**
         * Document which is a directory representing recently modified
         * documents under this root. This directory should return at most two
         * dozen documents modified within the last 90 days. May be {@code null}
         * if this root doesn't support recents.
         *
         * @see DocumentColumns#DOC_ID
         */
        public String recentDocId;

        /**
         * Number of free bytes of available in this root, or -1 if unknown or
         * unbounded.
         */
        public long availableBytes;

        /**
         * Set of MIME type filters describing the content offered by this root,
         * or {@code null} to indicate that all MIME types are supported. For
         * example, a provider only supporting audio and video might set this to
         * {@code ["audio/*", "video/*"]}.
         */
        public String[] mimeTypes;

        public DocumentRoot() {
        }

        /** {@hide} */
        public DocumentRoot(Parcel in) {
            rootType = in.readInt();
            flags = in.readInt();
            icon = in.readInt();
            title = in.readString();
            summary = in.readString();
            docId = in.readString();
            recentDocId = in.readString();
            availableBytes = in.readLong();
            mimeTypes = in.readStringArray();
        }

        /** {@hide} */
        public Drawable loadIcon(Context context) {
            if (icon != 0) {
                if (authority != null) {
                    final PackageManager pm = context.getPackageManager();
                    final ProviderInfo info = pm.resolveContentProvider(authority, 0);
                    if (info != null) {
                        return pm.getDrawable(info.packageName, icon, info.applicationInfo);
                    }
                } else {
                    return context.getResources().getDrawable(icon);
                }
            }
            return null;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            Preconditions.checkNotNull(docId);

            dest.writeInt(rootType);
            dest.writeInt(flags);
            dest.writeInt(icon);
            dest.writeString(title);
            dest.writeString(summary);
            dest.writeString(docId);
            dest.writeString(recentDocId);
            dest.writeLong(availableBytes);
            dest.writeStringArray(mimeTypes);
        }

        public static final Creator<DocumentRoot> CREATOR = new Creator<DocumentRoot>() {
            @Override
            public DocumentRoot createFromParcel(Parcel in) {
                return new DocumentRoot(in);
            }

            @Override
            public DocumentRoot[] newArray(int size) {
                return new DocumentRoot[size];
            }
        };
    }

    /**
     * Return list of all documents that the calling package has "open." These
     * are Uris matching {@link DocumentsContract} to which persistent
     * read/write access has been granted, usually through
     * {@link Intent#ACTION_OPEN_DOCUMENT} or
     * {@link Intent#ACTION_CREATE_DOCUMENT}.
     *
     * @see Context#grantUriPermission(String, Uri, int)
     * @see ContentResolver#getIncomingUriPermissionGrants(int, int)
     */
    public static Uri[] getOpenDocuments(Context context) {
        final int openedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_PERSIST_GRANT_URI_PERMISSION;
        final Uri[] uris = context.getContentResolver()
                .getIncomingUriPermissionGrants(openedFlags, openedFlags);

        // Filter to only include document providers
        final PackageManager pm = context.getPackageManager();
        final List<Uri> result = Lists.newArrayList();
        for (Uri uri : uris) {
            final ProviderInfo info = pm.resolveContentProvider(
                    uri.getAuthority(), PackageManager.GET_META_DATA);
            if (info.metaData.containsKey(META_DATA_DOCUMENT_PROVIDER)) {
                result.add(uri);
            }
        }

        return result.toArray(new Uri[result.size()]);
    }

    /**
     * Return thumbnail representing the document at the given URI. Callers are
     * responsible for their own in-memory caching. Given document must have
     * {@link Documents#FLAG_SUPPORTS_THUMBNAIL} set.
     *
     * @return decoded thumbnail, or {@code null} if problem was encountered.
     * @hide
     */
    public static Bitmap getThumbnail(ContentResolver resolver, Uri documentUri, Point size) {
        final Bundle openOpts = new Bundle();
        openOpts.putParcelable(DocumentsContract.EXTRA_THUMBNAIL_SIZE, size);

        AssetFileDescriptor afd = null;
        try {
            afd = resolver.openTypedAssetFileDescriptor(documentUri, "image/*", openOpts);

            final FileDescriptor fd = afd.getFileDescriptor();
            final long offset = afd.getStartOffset();
            final long length = afd.getDeclaredLength();

            // Some thumbnails might be a region inside a larger file, such as
            // an EXIF thumbnail. Since BitmapFactory aggressively seeks around
            // the entire file, we read the region manually.
            byte[] region = null;
            if (offset > 0 && length <= 64 * KB_IN_BYTES) {
                region = new byte[(int) length];
                Libcore.os.lseek(fd, offset, SEEK_SET);
                if (IoBridge.read(fd, region, 0, region.length) != region.length) {
                    region = null;
                }
            }

            // We requested a rough thumbnail size, but the remote size may have
            // returned something giant, so defensively scale down as needed.
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            if (region != null) {
                BitmapFactory.decodeByteArray(region, 0, region.length, opts);
            } else {
                BitmapFactory.decodeFileDescriptor(fd, null, opts);
            }

            final int widthSample = opts.outWidth / size.x;
            final int heightSample = opts.outHeight / size.y;

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.min(widthSample, heightSample);
            Log.d(TAG, "Decoding with sample size " + opts.inSampleSize);
            if (region != null) {
                return BitmapFactory.decodeByteArray(region, 0, region.length, opts);
            } else {
                return BitmapFactory.decodeFileDescriptor(fd, null, opts);
            }
        } catch (ErrnoException e) {
            Log.w(TAG, "Failed to load thumbnail for " + documentUri + ": " + e);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "Failed to load thumbnail for " + documentUri + ": " + e);
            return null;
        } finally {
            IoUtils.closeQuietly(afd);
        }
    }

    /** {@hide} */
    public static List<DocumentRoot> getDocumentRoots(ContentProviderClient client) {
        try {
            final Bundle out = client.call(METHOD_GET_ROOTS, null, null);
            final List<DocumentRoot> roots = out.getParcelableArrayList(EXTRA_ROOTS);
            return roots;
        } catch (Exception e) {
            Log.w(TAG, "Failed to get roots", e);
            return null;
        }
    }

    /**
     * Create a new document under the given parent document with MIME type and
     * display name.
     *
     * @param docId document with {@link Documents#FLAG_SUPPORTS_CREATE}
     * @param mimeType MIME type of new document
     * @param displayName name of new document
     * @return newly created document, or {@code null} if failed
     * @hide
     */
    public static String createDocument(
            ContentProviderClient client, String docId, String mimeType, String displayName) {
        final Bundle in = new Bundle();
        in.putString(DocumentColumns.DOC_ID, docId);
        in.putString(DocumentColumns.MIME_TYPE, mimeType);
        in.putString(DocumentColumns.DISPLAY_NAME, displayName);

        try {
            final Bundle out = client.call(METHOD_CREATE_DOCUMENT, null, in);
            return out.getString(DocumentColumns.DOC_ID);
        } catch (Exception e) {
            Log.w(TAG, "Failed to create document", e);
            return null;
        }
    }

    /**
     * Rename the given document.
     *
     * @param docId document with {@link Documents#FLAG_SUPPORTS_RENAME}
     * @return document which may have changed due to rename, or {@code null} if
     *         rename failed.
     * @hide
     */
    public static String renameDocument(
            ContentProviderClient client, String docId, String displayName) {
        final Bundle in = new Bundle();
        in.putString(DocumentColumns.DOC_ID, docId);
        in.putString(DocumentColumns.DISPLAY_NAME, displayName);

        try {
            final Bundle out = client.call(METHOD_RENAME_DOCUMENT, null, in);
            return out.getString(DocumentColumns.DOC_ID);
        } catch (Exception e) {
            Log.w(TAG, "Failed to rename document", e);
            return null;
        }
    }

    /**
     * Delete the given document.
     *
     * @param docId document with {@link Documents#FLAG_SUPPORTS_DELETE}
     * @hide
     */
    public static boolean deleteDocument(ContentProviderClient client, String docId) {
        final Bundle in = new Bundle();
        in.putString(DocumentColumns.DOC_ID, docId);

        try {
            client.call(METHOD_DELETE_DOCUMENT, null, in);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete document", e);
            return false;
        }
    }
}
