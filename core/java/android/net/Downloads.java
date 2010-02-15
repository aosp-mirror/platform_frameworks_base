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
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.util.Log;

import java.io.File;

/**
 * The Download Manager
 *
 * @pending
 */
public final class Downloads {

    public static final class ByUri {

        /** @hide */
        private ByUri() {}

        /**
          * Initiate a download where the download will be tracked by its URI.
          * @pending
          */
        public static boolean startDownloadByUri(
                Context context,
                String url,
                boolean showDownload,
                boolean allowRoaming,
                String title,
                String notification_package,
                String notification_class) {
            ContentResolver cr = context.getContentResolver();

            // Tell download manager to start downloading update.
            ContentValues values = new ContentValues();
            values.put(android.provider.Downloads.Impl.COLUMN_URI, url);
            values.put(android.provider.Downloads.Impl.COLUMN_VISIBILITY,
                       showDownload ? android.provider.Downloads.Impl.VISIBILITY_VISIBLE
                       : android.provider.Downloads.Impl.VISIBILITY_HIDDEN);
            if (title != null) {
                values.put(android.provider.Downloads.Impl.COLUMN_TITLE, title);
            }
            values.put(android.provider.Downloads.Impl.COLUMN_APP_DATA, url);
            values.put(android.provider.Downloads.Impl.COLUMN_DESTINATION,
                       allowRoaming ? android.provider.Downloads.Impl.DESTINATION_CACHE_PARTITION :
                       android.provider.Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING);
            values.put(android.provider.Downloads.Impl.COLUMN_NO_INTEGRITY, true);  // Don't check ETag
            if (notification_package != null && notification_class != null) {
                values.put(android.provider.Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE, notification_package);
                values.put(android.provider.Downloads.Impl.COLUMN_NOTIFICATION_CLASS, notification_class);
            }

            if (cr.insert(android.provider.Downloads.Impl.CONTENT_URI, values) == null) {
                return false;
            }
            return true;
        }

        public static final class StatusInfo {
            public boolean completed = false;
            /** The filename of the active download. */
            public String filename = null;
            /** An opaque id for the download */
            public long id = -1;
            /** An opaque status code for the download */
            public int statusCode = -1;
            /** Approximate number of bytes downloaded so far, for debugging purposes. */
            public long bytesSoFar = -1;
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
        private static final int getStatusOfDownload(
                Cursor c,
                long redownload_threshold) {
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
         * Gets a Cursor pointing to the download(s) of the current system update.
         * @hide
         */
        private static final Cursor getCurrentOtaDownloads(Context context, String url) {
            return context.getContentResolver().query(
                    android.provider.Downloads.Impl.CONTENT_URI,
                    DOWNLOADS_PROJECTION,
                    android.provider.Downloads.Impl.COLUMN_APP_DATA + "='" + url.replace("'", "''") + "'",
                    null,
                    null);
        }

        /**
         * Returns a StatusInfo with the result of trying to download the
         * given URL.  Returns null if no attempts have been made.
         * @pending
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
                c.close();
            }
            return result;
        }

        /**
         * Query where clause for general querying.
         * @hide
         */
        private static final String QUERY_WHERE_CLAUSE =
                android.provider.Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE + "=? AND " +
                android.provider.Downloads.Impl.COLUMN_NOTIFICATION_CLASS + "=?";

        /**
         * Delete all the downloads for a package/class pair.
         * @pending
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
         * @pending
         */
        public static final int getProgressColumnId() {
            return 0;
        }

        /**
         * @pending
         */
        public static final int getProgressColumnCurrentBytes() {
            return 1;
        }

        /**
         * @pending
         */
        public static final int getProgressColumnTotalBytes() {
            return 2;
        }

        /** @hide */
        private static final String[] PROJECTION = {
            BaseColumns._ID, android.provider.Downloads.Impl.COLUMN_CURRENT_BYTES, android.provider.Downloads.Impl.COLUMN_TOTAL_BYTES
        };

        /**
         * @pending
         */
        public static final Cursor getProgressCursor(Context context, long id) {
            Uri downloadUri = Uri.withAppendedPath(android.provider.Downloads.Impl.CONTENT_URI, String.valueOf(id));
            return context.getContentResolver().query(downloadUri, PROJECTION, null, null, null);
        }
    }

    /**
     * @hide
     */
    private Downloads() {}
}
