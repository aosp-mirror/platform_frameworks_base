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

package android.provider;

import android.net.Uri;

/**
 * Exposes constants used to interact with the download manager's
 * content provider.
 * The constants URI ... STATUS are the names of columns in the downloads table.
 *
 * @hide
 */
// For 1.0 the download manager can't deal with abuse from untrusted apps, so
// this API is hidden.
public final class Downloads implements BaseColumns {
    private Downloads() {}
    /**
     * The content:// URI for the data table in the provider
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://downloads/download");

    /**
     * Broadcast Action: this is sent by the download manager to the app
     * that had initiated a download when that download completes. The
     * download's content: uri is specified in the intent's data.
     */
    public static final String DOWNLOAD_COMPLETED_ACTION =
            "android.intent.action.DOWNLOAD_COMPLETED";

    /**
     * Broadcast Action: this is sent by the download manager to the app
     * that had initiated a download when the user selects the notification
     * associated with that download. The download's content: uri is specified
     * in the intent's data if the click is associated with a single download,
     * or Downloads.CONTENT_URI if the notification is associated with
     * multiple downloads.
     * Note: this is not currently sent for downloads that have completed
     * successfully.
     */
    public static final String NOTIFICATION_CLICKED_ACTION =
            "android.intent.action.DOWNLOAD_NOTIFICATION_CLICKED";

    /**
     * The name of the column containing the URI of the data being downloaded.
     * <P>Type: TEXT</P>
     * <P>Owner can Init/Read</P>
     */
    public static final String URI = "uri";

    /**
     * The name of the column containing the HTTP method to use for this
     * download. See the METHOD_* constants for a list of legal values.
     * <P>Type: INTEGER</P>
     * <P>Owner can Init/Read</P>
     */
    public static final String METHOD = "method";

    /**
     * The name of the column containing the entity to be sent with the
     * request of this download. Only use for methods that support sending
     * entities, i.e. POST.
     * <P>Type: TEXT</P>
     * <P>Owner can Init</P>
     */
    public static final String ENTITY = "entity";

    /**
     * The name of the column containing the flags that indicates whether
     * the initiating application is capable of verifying the integrity of
     * the downloaded file. When this flag is set, the download manager
     * performs downloads and reports success even in some situations where
     * it can't guarantee that the download has completed (e.g. when doing
     * a byte-range request without an ETag, or when it can't determine
     * whether a download fully completed).
     * <P>Type: BOOLEAN</P>
     * <P>Owner can Init/Read</P>
     */
    public static final String NO_INTEGRITY = "no_integrity";

    /**
     * The name of the column containing the filename that the initiating
     * application recommends. When possible, the download manager will attempt
     * to use this filename, or a variation, as the actual name for the file.
     * <P>Type: TEXT</P>
     * <P>Owner can Init/Read</P>
     */
    public static final String FILENAME_HINT = "hint";

    /**
     * The name of the column containing the filename where the downloaded data
     * was actually stored.
     * <P>Type: TEXT</P>
     * <P>Owner can Read</P>
     * <P>UI can Read</P>
     */
    public static final String FILENAME = "_data";

    /**
     * The name of the column containing the MIME type of the downloaded data.
     * <P>Type: TEXT</P>
     * <P>Owner can Init/Read</P>
     * <P>UI can Read</P>
     */
    public static final String MIMETYPE = "mimetype";

    /**
     * The name of the column containing the flag that controls the destination
     * of the download. See the DESTINATION_* constants for a list of legal values.
     * <P>Type: INTEGER</P>
     * <P>Owner can Init/Read</P>
     * <P>UI can Read</P>
     */
    public static final String DESTINATION = "destination";

    /**
     * The name of the column containing the flags that controls whether
     * the download must be saved with the filename used for OTA updates.
     * Must be used with INTERNAL, and the initiating application must hold the
     * android.permission.DOWNLOAD_OTA_UPDATE permission.
     * <P>Type: BOOLEAN</P>
     * <P>Owner can Init/Read</P>
     * <P>UI can Read</P>
     */
    public static final String OTA_UPDATE = "otaupdate";

    /**
     * The name of the columns containing the flag that controls whether
     * files with private/inernal/system MIME types can be downloaded.
     * <P>Type: BOOLEAN</P>
     * <P>Owner can Init/Read</P>
     */
    public static final String NO_SYSTEM_FILES = "no_system";

    /**
     * The name of the column containing the flags that controls whether the
     * download is displayed by the UI. See the VISIBILITY_* constants for
     * a list of legal values.
     * <P>Type: INTEGER</P>
     * <P>Owner can Init/Read/Write</P>
     * <P>UI can Read/Write (only for entries that are visible)</P>
     */
    public static final String VISIBILITY = "visibility";

    /**
     * The name of the column containing the command associated with the
     * download. After a download is initiated, this is the only column that
     * applications can modify. See the CONTROL_* constants for a list of legal
     * values. Note: doesn't do anything in 1.0. The API will be hooked up
     * in a future version, and is provided here as an indication of things
     * to come.
     * <P>Type: INTEGER</P>
     * <P>Owner can Init/Read/Write</P>
     * <P>UI can Init/Read/Write</P>
     * @hide
     */
    public static final String CONTROL = "control";

    /**
     * The name of the column containing the current status of the download.
     * Applications can read this to follow the progress of each download. See
     * the STATUS_* constants for a list of legal values.
     * <P>Type: INTEGER</P>
     * <P>Owner can Read</P>
     * <P>UI can Read</P>
     */
    public static final String STATUS = "status";

    /**
     * The name of the column containing the date at which some interesting
     * status changed in the download. Stored as a System.currentTimeMillis()
     * value.
     * <P>Type: BIGINT</P>
     * <P>Owner can Read</P>
     * <P>UI can Read</P>
     */
    public static final String LAST_MODIFICATION = "lastmod";

    /**
     * The name of the column containing the number of consecutive connections
     * that have failed.
     * <P>Type: INTEGER</P>
     */
    public static final String FAILED_CONNECTIONS = "numfailed";

    /**
     * The name of the column containing the package name of the application
     * that initiating the download. The download manager will send
     * notifications to a component in this package when the download completes.
     * <P>Type: TEXT</P>
     * <P>Owner can Init/Read</P>
     * <P>UI can Read</P>
     */
    public static final String NOTIFICATION_PACKAGE = "notificationpackage";

    /**
     * The name of the column containing the component name of the class that
     * will receive notifications associated with the download. The
     * package/class combination is passed to
     * Intent.setClassName(String,String).
     * <P>Type: TEXT</P>
     * <P>Owner can Init/Read</P>
     * <P>UI can Read</P>
     */
    public static final String NOTIFICATION_CLASS = "notificationclass";

    /**
     * If extras are specified when requesting a download they will be provided in the intent that
     * is sent to the specified class and package when a download has finished.
     */
    public static final String NOTIFICATION_EXTRAS = "notificationextras";

    /**
     * The name of the column contain the values of the cookie to be used for
     * the download. This is used directly as the value for the Cookie: HTTP
     * header that gets sent with the request.
     * <P>Type: TEXT</P>
     * <P>Owner can Init</P>
     */
    public static final String COOKIE_DATA = "cookiedata";

    /**
     * The name of the column containing the user agent that the initiating
     * application wants the download manager to use for this download.
     * <P>Type: TEXT</P>
     * <P>Owner can Init</P>
     */
    public static final String USER_AGENT = "useragent";

    /**
     * The name of the column containing the referer (sic) that the initiating
     * application wants the download manager to use for this download.
     * <P>Type: TEXT</P>
     * <P>Owner can Init</P>
     */
    public static final String REFERER = "referer";

    /**
     * The name of the column containing the total size of the file being
     * downloaded.
     * <P>Type: INTEGER</P>
     * <P>Owner can Read</P>
     * <P>UI can Read</P>
     */
    public static final String TOTAL_BYTES = "total_bytes";

    /**
     * The name of the column containing the size of the part of the file that
     * has been downloaded so far.
     * <P>Type: INTEGER</P>
     * <P>Owner can Read</P>
     * <P>UI can Read</P>
     */
    public static final String CURRENT_BYTES = "current_bytes";

    /**
     * The name of the column containing the entity tag for the response.
     * <P>Type: TEXT</P>
     * @hide
     */
    public static final String ETAG = "etag";

    /**
     * The name of the column containing the UID of the application that
     * initiated the download.
     * <P>Type: INTEGER</P>
     * @hide
     */
    public static final String UID = "uid";

    /**
     * The name of the column where the initiating application can provide the
     * UID of another application that is allowed to access this download. If
     * multiple applications share the same UID, all those applications will be
     * allowed to access this download. This column can be updated after the
     * download is initiated.
     * <P>Type: INTEGER</P>
     * <P>Owner can Init/Read/Write</P>
     */
    public static final String OTHER_UID = "otheruid";

    /**
     * The name of the column where the initiating application can provided the
     * title of this download. The title will be displayed ito the user in the
     * list of downloads.
     * <P>Type: TEXT</P>
     * <P>Owner can Init/Read/Write</P>
     * <P>UI can Read</P>
     */
    public static final String TITLE = "title";

    /**
     * The name of the column where the initiating application can provide the
     * description of this download. The description will be displayed to the
     * user in the list of downloads.
     * <P>Type: TEXT</P>
     * <P>Owner can Init/Read/Write</P>
     * <P>UI can Read</P>
     */
    public static final String DESCRIPTION = "description";

    /**
     * The name of the column where the download manager indicates whether the
     * media scanner was notified about this download.
     * <P>Type: BOOLEAN</P>
     * @hide
     */
    public static final String MEDIA_SCANNED = "scanned";

    /*
     * Lists the destinations that an application can specify for a download.
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
    public static final int DESTINATION_EXTERNAL = 0;

    /**
     * This download will be saved to the download manager's private
     * partition. This is the behavior used by applications that want to
     * download private files that are used and deleted soon after they
     * get downloaded. All file types are allowed, and only the initiating
     * application can access the file (indirectly through a content
     * provider).
     */
    public static final int DESTINATION_CACHE_PARTITION = 1;

    /**
     * This download will be saved to the download manager's private
     * partition and will be purged as necessary to make space. This is
     * for private files (similar to CACHE_PARTITION) that aren't deleted
     * immediately after they are used, and are kept around by the download
     * manager as long as space is available.
     */
    public static final int DESTINATION_CACHE_PARTITION_PURGEABLE = 2;

    /**
     * This download will be saved to the download manager's cache
     * on the shared data partition. Use CACHE_PARTITION_PURGEABLE instead.
     */
    public static final int DESTINATION_DATA_CACHE = 3;

    /* (not javadoc)
     * This download will be saved to a file specified by the initiating
     * applications.
     * @hide
     */
    //public static final int DESTINATION_PROVIDER = 4;

    /*
     * Lists the commands that an application can set to control an ongoing
     * download. Note: those aren't working.
     */

    /**
     * This download can run
     * @hide
     */
    public static final int CONTROL_RUN = 0;

    /**
     * This download must pause (might be restarted)
     * @hide
     */
    public static final int CONTROL_PAUSE = 1;

    /**
     * This download must abort (will never be restarted)
     * @hide
     */
    public static final int CONTROL_STOP = 2;

    /*
     * Lists the states that the download manager can set on a download
     * to notify applications of the download progress.
     * The codes follow the HTTP families:<br>
     * 1xx: informational<br>
     * 2xx: success<br>
     * 3xx: redirects (not used by the download manager)<br>
     * 4xx: client errors<br>
     * 5xx: server errors
     */

    /**
     * Returns whether the status is informational (i.e. 1xx).
     */
    public static boolean isStatusInformational(int status) {
        return (status >= 100 && status < 200);
    }

    /**
     * Returns whether the download is suspended. (i.e. whether the download
     * won't complete without some action from outside the download
     * manager).
     */
    public static boolean isStatusSuspended(int status) {
        return (status == STATUS_PENDING_PAUSED || status == STATUS_RUNNING_PAUSED);
    }

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
     * Returns whether the status is a client error (i.e. 4xx).
     */
    public static boolean isStatusClientError(int status) {
        return (status >= 400 && status < 500);
    }

    /**
     * Returns whether the status is a server error (i.e. 5xx).
     */
    public static boolean isStatusServerError(int status) {
        return (status >= 500 && status < 600);
    }

    /**
     * Returns whether the download has completed (either with success or
     * error).
     */
    public static boolean isStatusCompleted(int status) {
        return (status >= 200 && status < 300) || (status >= 400 && status < 600);
    }

    /**
     * This download hasn't stated yet
     */
    public static final int STATUS_PENDING = 190;

    /**
     * This download hasn't stated yet and is paused
     */
    public static final int STATUS_PENDING_PAUSED = 191;

    /**
     * This download has started
     */
    public static final int STATUS_RUNNING = 192;

    /**
     * This download has started and is paused
     */
    public static final int STATUS_RUNNING_PAUSED = 193;

    /**
     * This download has successfully completed.
     * Warning: there might be other status values that indicate success
     * in the future.
     * Use isSucccess() to capture the entire category.
     */
    public static final int STATUS_SUCCESS = 200;

    /**
     * This request couldn't be parsed. This is also used when processing
     * requests with unknown/unsupported URI schemes.
     */
    public static final int STATUS_BAD_REQUEST = 400;

    /**
     * The server returned an auth error.
     */
    public static final int STATUS_NOT_AUTHORIZED = 401;

    /**
     * This download can't be performed because the content type cannot be
     * handled.
     */
    public static final int STATUS_NOT_ACCEPTABLE = 406;

    /**
     * This download cannot be performed because the length cannot be
     * determined accurately. This is the code for the HTTP error "Length
     * Required", which is typically used when making requests that require
     * a content length but don't have one, and it is also used in the
     * client when a response is received whose length cannot be determined
     * accurately (therefore making it impossible to know when a download
     * completes).
     */
    public static final int STATUS_LENGTH_REQUIRED = 411;

    /**
     * This download was interrupted and cannot be resumed.
     * This is the code for the HTTP error "Precondition Failed", and it is
     * also used in situations where the client doesn't have an ETag at all.
     */
    public static final int STATUS_PRECONDITION_FAILED = 412;

    /**
     * This download was canceled
     */
    public static final int STATUS_CANCELED = 490;
    /**
     * @hide
     * Alternate spelling
     */
    public static final int STATUS_CANCELLED = 490;

    /**
     * This download has completed with an error.
     * Warning: there will be other status values that indicate errors in
     * the future. Use isStatusError() to capture the entire category.
     */
    public static final int STATUS_UNKNOWN_ERROR = 491;
    /**
     * @hide
     * Legacy name - use STATUS_UNKNOWN_ERROR
     */
    public static final int STATUS_ERROR = 491;

    /**
     * This download couldn't be completed because of a storage issue.
     * Typically, that's because the filesystem is missing or full.
     */
    public static final int STATUS_FILE_ERROR = 492;

    /**
     * This download couldn't be completed because of an HTTP
     * redirect code.
     */
    public static final int STATUS_UNHANDLED_REDIRECT = 493;

    /**
     * This download couldn't be completed because of an
     * unspecified unhandled HTTP code.
     */
    public static final int STATUS_UNHANDLED_HTTP_CODE = 494;

    /**
     * This download couldn't be completed because of an
     * error receiving or processing data at the HTTP level.
     */
    public static final int STATUS_HTTP_DATA_ERROR = 495;

    /**
     * This download couldn't be completed because of an
     * HttpException while setting up the request.
     */
    public static final int STATUS_HTTP_EXCEPTION = 496;

    /*
     * Lists the HTTP methods that the download manager can use.
     */

    /**
     * GET
     */
    public static final int METHOD_GET = 0;

    /**
     * POST
     */
    public static final int METHOD_POST = 1;

    /**
     * This download is visible but only shows in the notifications
     * while it's running (a separate download UI would still show it
     * after completion).
     */
    public static final int VISIBILITY_VISIBLE = 0;

    /**
     * This download is visible and shows in the notifications after
     * completion.
     */
    public static final int VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1;

    /**
     * This download doesn't show in the UI or in the notifications.
     */
    public static final int VISIBILITY_HIDDEN = 2;
}
