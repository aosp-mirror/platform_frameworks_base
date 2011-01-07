/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;

/**
 * The Download Manager
 *
 * @hide
 */
public final class Downloads {


    /**
     * Download status codes
     */

    /**
     * This download hasn't started yet
     */
    public static final int STATUS_PENDING = 190;

    /**
     * This download has started
     */
    public static final int STATUS_RUNNING = 192;

    /**
     * This download has successfully completed.
     * Warning: there might be other status values that indicate success
     * in the future.
     * Use isSucccess() to capture the entire category.
     */
    public static final int STATUS_SUCCESS = 200;

    /**
     * This download can't be performed because the content type cannot be
     * handled.
     */
    public static final int STATUS_NOT_ACCEPTABLE = 406;

    /**
     * This download has completed with an error.
     * Warning: there will be other status values that indicate errors in
     * the future. Use isStatusError() to capture the entire category.
     */
    public static final int STATUS_UNKNOWN_ERROR = 491;

    /**
     * This download couldn't be completed because of an HTTP
     * redirect response that the download manager couldn't
     * handle.
     */
    public static final int STATUS_UNHANDLED_REDIRECT = 493;

    /**
     * This download couldn't be completed due to insufficient storage
     * space.  Typically, this is because the SD card is full.
     */
    public static final int STATUS_INSUFFICIENT_SPACE_ERROR = 498;

    /**
     * This download couldn't be completed because no external storage
     * device was found.  Typically, this is because the SD card is not
     * mounted.
     */
    public static final int STATUS_DEVICE_NOT_FOUND_ERROR = 499;

    /**
     * Returns whether the status is a success (i.e. 2xx).
     */
    public static boolean isStatusSuccess(int status) {
        return (status >= 200 && status < 300);
    }

    /**
     * Returns whether the status is an error (i.e. 4xx or 5xx).
     */
    public static boolean isStatusError(int status) {
        return (status >= 400 && status < 600);
    }

    /**
     * Download destinations
     */

    /**
     * This download will be saved to the external storage. This is the
     * default behavior, and should be used for any file that the user
     * can freely access, copy, delete. Even with that destination,
     * unencrypted DRM files are saved in secure internal storage.
     * Downloads to the external destination only write files for which
     * there is a registered handler. The resulting files are accessible
     * by filename to all applications.
     */
    public static final int DOWNLOAD_DESTINATION_EXTERNAL = 1;

    /**
     * This download will be saved to the download manager's private
     * partition. This is the behavior used by applications that want to
     * download private files that are used and deleted soon after they
     * get downloaded. All file types are allowed, and only the initiating
     * application can access the file (indirectly through a content
     * provider). This requires the
     * android.permission.ACCESS_DOWNLOAD_MANAGER_ADVANCED permission.
     */
    public static final int DOWNLOAD_DESTINATION_CACHE = 2;

    /**
     * This download will be saved to the download manager's private
     * partition and will be purged as necessary to make space. This is
     * for private files (similar to CACHE_PARTITION) that aren't deleted
     * immediately after they are used, and are kept around by the download
     * manager as long as space is available.
     */
    public static final int DOWNLOAD_DESTINATION_CACHE_PURGEABLE = 3;


    /**
     * An invalid download id
     */
    public static final long DOWNLOAD_ID_INVALID = -1;


    /**
     * Broadcast Action: this is sent by the download manager to the app
     * that had initiated a download when that download completes. The
     * download's content: uri is specified in the intent's data.
     */
    public static final String ACTION_DOWNLOAD_COMPLETED =
            "android.intent.action.DOWNLOAD_COMPLETED";

    /**
     * If extras are specified when requesting a download they will be provided in the intent that
     * is sent to the specified class and package when a download has finished.
     * <P>Type: TEXT</P>
     * <P>Owner can Init</P>
     */
    public static final String COLUMN_NOTIFICATION_EXTRAS = "notificationextras";


    /**
     * Status class for a download
     */
    public static final class StatusInfo {
        public boolean completed = false;
        /** The filename of the active download. */
        public String filename = null;
        /** An opaque id for the download */
        public long id = DOWNLOAD_ID_INVALID;
        /** An opaque status code for the download */
        public int statusCode = -1;
        /** Approximate number of bytes downloaded so far, for debugging purposes. */
        public long bytesSoFar = -1;

        /**
         * Returns whether the download is completed
         * @return a boolean whether the download is complete.
         */
        public boolean isComplete() {
            return android.provider.Downloads.Impl.isStatusCompleted(statusCode);
        }

        /**
         * Returns whether the download is successful
         * @return a boolean whether the download is successful.
         */
        public boolean isSuccessful() {
            return android.provider.Downloads.Impl.isStatusSuccess(statusCode);
        }
    }

    /**
     * Class to access initiate and query download by server uri
     */
    public static final class ByUri extends DownloadBase {
        /** @hide */
        private ByUri() {}

        /**
         * Query where clause by app data.
         * @hide
         */
        private static final String QUERY_WHERE_APP_DATA_CLAUSE =
                android.provider.Downloads.Impl.COLUMN_APP_DATA + "=?";

        /**
         * Gets a Cursor pointing to the download(s) of the current system update.
         * @hide
         */
        private static final Cursor getCurrentOtaDownloads(Context context, String url) {
            return context.getContentResolver().query(
                    android.provider.Downloads.Impl.CONTENT_URI,
                    DOWNLOADS_PROJECTION,
                    QUERY_WHERE_APP_DATA_CLAUSE,
                    new String[] {url},
                    null);
        }

        /**
         * Returns a StatusInfo with the result of trying to download the
         * given URL.  Returns null if no attempts have been made.
         */
        public static final StatusInfo getStatus(
                Context context,
                String url,
                long redownload_threshold) {
            StatusInfo result = null;
            boolean hasFailedDownload = false;
            long failedDownloadModificationTime = 0;
            Cursor c = getCurrentOtaDownloads(context, url);
            try {
                while (c != null && c.moveToNext()) {
                    if (result == null) {
                        result = new StatusInfo();
                    }
                    int status = getStatusOfDownload(c, redownload_threshold);
                    if (status == STATUS_DOWNLOADING_UPDATE ||
                        status == STATUS_DOWNLOADED_UPDATE) {
                        result.completed = (status == STATUS_DOWNLOADED_UPDATE);
                        result.filename = c.getString(DOWNLOADS_COLUMN_FILENAME);
                        result.id = c.getLong(DOWNLOADS_COLUMN_ID);
                        result.statusCode = c.getInt(DOWNLOADS_COLUMN_STATUS);
                        result.bytesSoFar = c.getLong(DOWNLOADS_COLUMN_CURRENT_BYTES);
                        return result;
                    }

                    long modTime = c.getLong(DOWNLOADS_COLUMN_LAST_MODIFICATION);
                    if (hasFailedDownload &&
                        modTime < failedDownloadModificationTime) {
                        // older than the one already in result; skip it.
                        continue;
                    }

                    hasFailedDownload = true;
                    failedDownloadModificationTime = modTime;
                    result.statusCode = c.getInt(DOWNLOADS_COLUMN_STATUS);
                    result.bytesSoFar = c.getLong(DOWNLOADS_COLUMN_CURRENT_BYTES);
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            return result;
        }

        /**
         * Query where clause for general querying.
         */
        private static final String QUERY_WHERE_CLAUSE =
                android.provider.Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE + "=? AND " +
                android.provider.Downloads.Impl.COLUMN_NOTIFICATION_CLASS + "=?";

        /**
         * Delete all the downloads for a package/class pair.
         */
        public static final void removeAllDownloadsByPackage(
                Context context,
                String notification_package,
                String notification_class) {
            context.getContentResolver().delete(
                    android.provider.Downloads.Impl.CONTENT_URI,
                    QUERY_WHERE_CLAUSE,
                    new String[] { notification_package, notification_class });
        }

        /**
         * The column for the id in the Cursor returned by
         * getProgressCursor()
         */
        public static final int getProgressColumnId() {
            return 0;
        }

        /**
         * The column for the current byte count in the Cursor returned by
         * getProgressCursor()
         */
        public static final int getProgressColumnCurrentBytes() {
            return 1;
        }

        /**
         * The column for the total byte count in the Cursor returned by
         * getProgressCursor()
         */
        public static final int getProgressColumnTotalBytes() {
            return 2;
        }

        /** @hide */
        private static final String[] PROJECTION = {
            BaseColumns._ID,
            android.provider.Downloads.Impl.COLUMN_CURRENT_BYTES,
            android.provider.Downloads.Impl.COLUMN_TOTAL_BYTES
        };

        /**
         * Returns a Cursor representing the progress of the download identified by the ID.
         */
        public static final Cursor getProgressCursor(Context context, long id) {
            Uri downloadUri = Uri.withAppendedPath(android.provider.Downloads.Impl.CONTENT_URI,
                    String.valueOf(id));
            return context.getContentResolver().query(downloadUri, PROJECTION, null, null, null);
        }
    }

    /**
     * Class to access downloads by opaque download id
     */
    public static final class ById extends DownloadBase {
        /** @hide */
        private ById() {}

        /**
         * Get the mime tupe of the download specified by the download id
         */
        public static String getMimeTypeForId(Context context, long downloadId) {
            ContentResolver cr = context.getContentResolver();

            String mimeType = null;
            Cursor downloadCursor = null;

            try {
                Uri downloadUri = getDownloadUri(downloadId);

                downloadCursor = cr.query(
                        downloadUri, new String[]{android.provider.Downloads.Impl.COLUMN_MIME_TYPE},
                        null, null, null);
                if (downloadCursor.moveToNext()) {
                    mimeType = downloadCursor.getString(0);
                }
            } finally {
                if (downloadCursor != null) downloadCursor.close();
            }
            return mimeType;
        }

        /**
         * Delete a download by Id
         */
        public static void deleteDownload(Context context, long downloadId) {
            ContentResolver cr = context.getContentResolver();

            String mimeType = null;

            Uri downloadUri = getDownloadUri(downloadId);

            cr.delete(downloadUri, null, null);
        }

        /**
         * Open a filedescriptor to a particular download
         */
        public static ParcelFileDescriptor openDownload(
                Context context, long downloadId, String mode)
            throws FileNotFoundException
        {
            ContentResolver cr = context.getContentResolver();

            String mimeType = null;

            Uri downloadUri = getDownloadUri(downloadId);

            return cr.openFileDescriptor(downloadUri, mode);
        }

        /**
         * Open a stream to a particular download
         */
        public static InputStream openDownloadStream(Context context, long downloadId)
                throws FileNotFoundException, IOException
        {
            ContentResolver cr = context.getContentResolver();

            String mimeType = null;

            Uri downloadUri = getDownloadUri(downloadId);

            return cr.openInputStream(downloadUri);
        }

        private static Uri getDownloadUri(long downloadId) {
            return Uri.parse(android.provider.Downloads.Impl.CONTENT_URI + "/" + downloadId);
        }

        /**
         * Returns a StatusInfo with the result of trying to download the
         * given URL.  Returns null if no attempts have been made.
         */
        public static final StatusInfo getStatus(
                Context context,
                long downloadId) {
            StatusInfo result = null;
            boolean hasFailedDownload = false;
            long failedDownloadModificationTime = 0;

            Uri downloadUri = getDownloadUri(downloadId);

            ContentResolver cr = context.getContentResolver();

            Cursor c = cr.query(downloadUri, DOWNLOADS_PROJECTION, null /* selection */,
                    null /* selection args */, null /* sort order */);
            try {
                if (c == null || !c.moveToNext()) {
                    return result;
                }

                if (result == null) {
                    result = new StatusInfo();
                }
                int status = getStatusOfDownload(c,0);
                if (status == STATUS_DOWNLOADING_UPDATE ||
                        status == STATUS_DOWNLOADED_UPDATE) {
                    result.completed = (status == STATUS_DOWNLOADED_UPDATE);
                    result.filename = c.getString(DOWNLOADS_COLUMN_FILENAME);
                    result.id = c.getLong(DOWNLOADS_COLUMN_ID);
                    result.statusCode = c.getInt(DOWNLOADS_COLUMN_STATUS);
                    result.bytesSoFar = c.getLong(DOWNLOADS_COLUMN_CURRENT_BYTES);
                    return result;
                }

                long modTime = c.getLong(DOWNLOADS_COLUMN_LAST_MODIFICATION);

                result.statusCode = c.getInt(DOWNLOADS_COLUMN_STATUS);
                result.bytesSoFar = c.getLong(DOWNLOADS_COLUMN_CURRENT_BYTES);
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            return result;
        }
    }


    /**
     * Base class with common functionality for the various download classes
     */
    public static class DownloadBase {
        /** @hide */
        DownloadBase() {}

        /**
          * Initiate a download where the download will be tracked by its URI.
          */
        public static long startDownloadByUri(
                Context context,
                String url,
                String cookieData,
                boolean showDownload,
                int downloadDestination,
                boolean allowRoaming,
                boolean skipIntegrityCheck,
                String title,
                String notification_package,
                String notification_class,
                String notification_extras) {
            ContentResolver cr = context.getContentResolver();

            // Tell download manager to start downloading update.
            ContentValues values = new ContentValues();
            values.put(android.provider.Downloads.Impl.COLUMN_URI, url);
            values.put(android.provider.Downloads.Impl.COLUMN_COOKIE_DATA, cookieData);
            values.put(android.provider.Downloads.Impl.COLUMN_VISIBILITY,
                       showDownload ? android.provider.Downloads.Impl.VISIBILITY_VISIBLE
                       : android.provider.Downloads.Impl.VISIBILITY_HIDDEN);
            if (title != null) {
                values.put(android.provider.Downloads.Impl.COLUMN_TITLE, title);
            }
            values.put(android.provider.Downloads.Impl.COLUMN_APP_DATA, url);


            // NOTE:  destination should be seperated from whether the download
            // can happen when roaming
            int destination = android.provider.Downloads.Impl.DESTINATION_EXTERNAL;
            switch (downloadDestination) {
                case DOWNLOAD_DESTINATION_EXTERNAL:
                    destination = android.provider.Downloads.Impl.DESTINATION_EXTERNAL;
                    break;
                case DOWNLOAD_DESTINATION_CACHE:
                    if (allowRoaming) {
                        destination = android.provider.Downloads.Impl.DESTINATION_CACHE_PARTITION;
                    } else {
                        destination =
                                android.provider.Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING;
                    }
                    break;
                case DOWNLOAD_DESTINATION_CACHE_PURGEABLE:
                    destination =
                            android.provider.Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE;
                    break;
            }
            values.put(android.provider.Downloads.Impl.COLUMN_DESTINATION, destination);
            values.put(android.provider.Downloads.Impl.COLUMN_NO_INTEGRITY,
                    skipIntegrityCheck);  // Don't check ETag
            if (notification_package != null && notification_class != null) {
                values.put(android.provider.Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE,
                        notification_package);
                values.put(android.provider.Downloads.Impl.COLUMN_NOTIFICATION_CLASS,
                        notification_class);

                if (notification_extras != null) {
                    values.put(android.provider.Downloads.Impl.COLUMN_NOTIFICATION_EXTRAS,
                            notification_extras);
                }
            }

            Uri downloadUri = cr.insert(android.provider.Downloads.Impl.CONTENT_URI, values);

            long downloadId = DOWNLOAD_ID_INVALID;
            if (downloadUri != null) {
                downloadId = Long.parseLong(downloadUri.getLastPathSegment());
            }
            return downloadId;
        }
    }

    /** @hide */
    private static final int STATUS_INVALID = 0;
    /** @hide */
    private static final int STATUS_DOWNLOADING_UPDATE = 3;
    /** @hide */
    private static final int STATUS_DOWNLOADED_UPDATE = 4;

    /**
     * Column projection for the query to the download manager. This must match
     * with the constants DOWNLOADS_COLUMN_*.
     * @hide
     */
    private static final String[] DOWNLOADS_PROJECTION = {
            BaseColumns._ID,
            android.provider.Downloads.Impl.COLUMN_APP_DATA,
            android.provider.Downloads.Impl.COLUMN_STATUS,
            android.provider.Downloads.Impl._DATA,
            android.provider.Downloads.Impl.COLUMN_LAST_MODIFICATION,
            android.provider.Downloads.Impl.COLUMN_CURRENT_BYTES,
    };

    /**
     * The column index for the ID.
     * @hide
     */
    private static final int DOWNLOADS_COLUMN_ID = 0;
    /**
     * The column index for the URI.
     * @hide
     */
    private static final int DOWNLOADS_COLUMN_URI = 1;
    /**
     * The column index for the status code.
     * @hide
     */
    private static final int DOWNLOADS_COLUMN_STATUS = 2;
    /**
     * The column index for the filename.
     * @hide
     */
    private static final int DOWNLOADS_COLUMN_FILENAME = 3;
    /**
     * The column index for the last modification time.
     * @hide
     */
    private static final int DOWNLOADS_COLUMN_LAST_MODIFICATION = 4;
    /**
     * The column index for the number of bytes downloaded so far.
     * @hide
     */
    private static final int DOWNLOADS_COLUMN_CURRENT_BYTES = 5;

    /**
     * Gets the status of a download.
     *
     * @param c A Cursor pointing to a download.  The URL column is assumed to be valid.
     * @return The status of the download.
     * @hide
     */
    private static final int getStatusOfDownload( Cursor c, long redownload_threshold) {
        int status = c.getInt(DOWNLOADS_COLUMN_STATUS);
        long realtime = SystemClock.elapsedRealtime();

        // TODO(dougz): special handling of 503, 404?  (eg, special
        // explanatory messages to user)

        if (!android.provider.Downloads.Impl.isStatusCompleted(status)) {
            // Check if it's stuck
            long modified = c.getLong(DOWNLOADS_COLUMN_LAST_MODIFICATION);
            long now = System.currentTimeMillis();
            if (now < modified || now - modified > redownload_threshold) {
                return STATUS_INVALID;
            }

            return STATUS_DOWNLOADING_UPDATE;
        }

        if (android.provider.Downloads.Impl.isStatusError(status)) {
            return STATUS_INVALID;
        }

        String filename = c.getString(DOWNLOADS_COLUMN_FILENAME);
        if (filename == null) {
            return STATUS_INVALID;
        }

        return STATUS_DOWNLOADED_UPDATE;
    }


    /**
     * @hide
     */
    private Downloads() {}
}
