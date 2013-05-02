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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * The contract between a storage backend and the platform. Contains definitions
 * for the supported URIs and columns.
 */
public final class DocumentsContract {
    private static final String TAG = "Documents";

    // content://com.example/docs/0/
    // content://com.example/docs/0/contents/
    // content://com.example/search/?query=pony

    /**
     * MIME type of a document which is a directory that may contain additional
     * documents.
     *
     * @see #buildContentsUri(Uri)
     */
    public static final String MIME_TYPE_DIRECTORY = "vnd.android.cursor.dir/doc";

    /**
     * {@link DocumentColumns#GUID} value representing the root directory of a
     * storage backend.
     */
    public static final String ROOT_GUID = "0";

    /**
     * Flag indicating that a document is a directory that supports creation of
     * new files within it.
     *
     * @see DocumentColumns#FLAGS
     * @see #buildContentsUri(Uri)
     */
    public static final int FLAG_SUPPORTS_CREATE = 1;

    /**
     * Flag indicating that a document is renamable.
     *
     * @see DocumentColumns#FLAGS
     * @see #renameDocument(ContentResolver, Uri, String)
     */
    public static final int FLAG_SUPPORTS_RENAME = 1 << 1;

    /**
     * Flag indicating that a document can be represented as a thumbnail.
     *
     * @see DocumentColumns#FLAGS
     * @see #getThumbnail(ContentResolver, Uri, Point)
     */
    public static final int FLAG_SUPPORTS_THUMBNAIL = 1 << 2;

    /**
     * Optimal dimensions for a document thumbnail request, stored as a
     * {@link Point} object. This is only a hint, and the returned thumbnail may
     * have different dimensions.
     */
    public static final String EXTRA_THUMBNAIL_SIZE = "thumbnail_size";

    private static final String PATH_DOCS = "docs";
    private static final String PATH_CONTENTS = "contents";
    private static final String PATH_SEARCH = "search";

    private static final String PARAM_QUERY = "query";

    /**
     * Build URI representing the given {@link DocumentColumns#GUID} in a
     * storage backend.
     */
    public static Uri buildDocumentUri(String authority, String guid) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_DOCS).appendPath(guid).build();
    }

    /**
     * Build URI representing a search for matching documents in a storage
     * backend.
     */
    public static Uri buildSearchUri(String authority, String query) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath(PATH_SEARCH).appendQueryParameter(PARAM_QUERY, query).build();
    }

    /**
     * Build URI representing the contents of the given directory in a storage
     * backend. The given document must be {@link #MIME_TYPE_DIRECTORY}.
     */
    public static Uri buildContentsUri(Uri documentUri) {
        return documentUri.buildUpon().appendPath(PATH_CONTENTS).build();
    }

    /**
     * These are standard columns for document URIs. Storage backend providers
     * <em>must</em> support at least these columns when queried.
     *
     * @see Intent#ACTION_OPEN_DOCUMENT
     * @see Intent#ACTION_CREATE_DOCUMENT
     */
    public interface DocumentColumns extends OpenableColumns {
        /**
         * The globally unique ID for a document within a storage backend.
         * Values <em>must</em> never change once returned.
         * <p>
         * Type: STRING
         *
         * @see DocumentsContract#ROOT_GUID
         */
        public static final String GUID = "guid";

        /**
         * MIME type of a document, matching the value returned by
         * {@link ContentResolver#getType(android.net.Uri)}.
         * <p>
         * Type: STRING
         *
         * @see DocumentsContract#MIME_TYPE_DIRECTORY
         */
        public static final String MIME_TYPE = "mime_type";

        /**
         * Timestamp when a document was last modified, in milliseconds since
         * January 1, 1970 00:00:00.0 UTC.
         * <p>
         * Type: INTEGER (long)
         *
         * @see System#currentTimeMillis()
         */
        public static final String LAST_MODIFIED = "last_modified";

        /**
         * Flags that apply to a specific document.
         * <p>
         * Type: INTEGER (int)
         */
        public static final String FLAGS = "flags";
    }

    /**
     * Return thumbnail representing the document at the given URI. Callers are
     * responsible for their own caching. Given document must have
     * {@link #FLAG_SUPPORTS_THUMBNAIL} set.
     *
     * @return decoded thumbnail, or {@code null} if problem was encountered.
     */
    public static Bitmap getThumbnail(ContentResolver resolver, Uri documentUri, Point size) {
        final Bundle opts = new Bundle();
        opts.putParcelable(EXTRA_THUMBNAIL_SIZE, size);

        InputStream is = null;
        try {
            is = new AssetFileDescriptor.AutoCloseInputStream(
                    resolver.openTypedAssetFileDescriptor(documentUri, "image/*", opts));
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            Log.w(TAG, "Failed to load thumbnail for " + documentUri + ": " + e);
            return null;
        } finally {
            IoUtils.closeQuietly(is);
        }
    }

    /**
     * Rename the document at the given URI. Given document must have
     * {@link #FLAG_SUPPORTS_RENAME} set.
     *
     * @return if rename was successful.
     */
    public static boolean renameDocument(
            ContentResolver resolver, Uri documentUri, String displayName) {
        final ContentValues values = new ContentValues();
        values.put(DocumentColumns.DISPLAY_NAME, displayName);
        return (resolver.update(documentUri, values, null, null) == 1);
    }
}
