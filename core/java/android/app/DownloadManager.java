/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.ConnectivityManager;
import android.net.NetworkPolicyManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.Downloads;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.util.Pair;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * The download manager is a system service that handles long-running HTTP downloads. Clients may
 * request that a URI be downloaded to a particular destination file. The download manager will
 * conduct the download in the background, taking care of HTTP interactions and retrying downloads
 * after failures or across connectivity changes and system reboots.
 * <p>
 * Apps that request downloads through this API should register a broadcast receiver for
 * {@link #ACTION_NOTIFICATION_CLICKED} to appropriately handle when the user clicks on a running
 * download in a notification or from the downloads UI.
 * <p>
 * Note that the application must have the {@link android.Manifest.permission#INTERNET}
 * permission to use this class.
 */
@SystemService(Context.DOWNLOAD_SERVICE)
public class DownloadManager {

    /**
     * An identifier for a particular download, unique across the system.  Clients use this ID to
     * make subsequent calls related to the download.
     */
    public final static String COLUMN_ID = Downloads.Impl._ID;

    /**
     * The client-supplied title for this download.  This will be displayed in system notifications.
     * Defaults to the empty string.
     */
    public final static String COLUMN_TITLE = Downloads.Impl.COLUMN_TITLE;

    /**
     * The client-supplied description of this download.  This will be displayed in system
     * notifications.  Defaults to the empty string.
     */
    public final static String COLUMN_DESCRIPTION = Downloads.Impl.COLUMN_DESCRIPTION;

    /**
     * URI to be downloaded.
     */
    public final static String COLUMN_URI = Downloads.Impl.COLUMN_URI;

    /**
     * Internet Media Type of the downloaded file.  If no value is provided upon creation, this will
     * initially be null and will be filled in based on the server's response once the download has
     * started.
     *
     * @see <a href="http://www.ietf.org/rfc/rfc1590.txt">RFC 1590, defining Media Types</a>
     */
    public final static String COLUMN_MEDIA_TYPE = "media_type";

    /**
     * Total size of the download in bytes.  This will initially be -1 and will be filled in once
     * the download starts.
     */
    public final static String COLUMN_TOTAL_SIZE_BYTES = "total_size";

    /**
     * Uri where downloaded file will be stored.  If a destination is supplied by client, that URI
     * will be used here.  Otherwise, the value will initially be null and will be filled in with a
     * generated URI once the download has started.
     */
    public final static String COLUMN_LOCAL_URI = "local_uri";

    /**
     * Path to the downloaded file on disk.
     * <p>
     * Note that apps may not have filesystem permissions to directly access
     * this path. Instead of trying to open this path directly, apps should use
     * {@link ContentResolver#openFileDescriptor(Uri, String)} to gain access.
     *
     * @deprecated apps should transition to using
     *             {@link ContentResolver#openFileDescriptor(Uri, String)}
     *             instead.
     */
    @Deprecated
    public final static String COLUMN_LOCAL_FILENAME = "local_filename";

    /**
     * Current status of the download, as one of the STATUS_* constants.
     */
    public final static String COLUMN_STATUS = Downloads.Impl.COLUMN_STATUS;

    /**
     * Provides more detail on the status of the download.  Its meaning depends on the value of
     * {@link #COLUMN_STATUS}.
     *
     * When {@link #COLUMN_STATUS} is {@link #STATUS_FAILED}, this indicates the type of error that
     * occurred.  If an HTTP error occurred, this will hold the HTTP status code as defined in RFC
     * 2616.  Otherwise, it will hold one of the ERROR_* constants.
     *
     * When {@link #COLUMN_STATUS} is {@link #STATUS_PAUSED}, this indicates why the download is
     * paused.  It will hold one of the PAUSED_* constants.
     *
     * If {@link #COLUMN_STATUS} is neither {@link #STATUS_FAILED} nor {@link #STATUS_PAUSED}, this
     * column's value is undefined.
     *
     * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec6.html#sec6.1.1">RFC 2616
     * status codes</a>
     */
    public final static String COLUMN_REASON = "reason";

    /**
     * Number of bytes download so far.
     */
    public final static String COLUMN_BYTES_DOWNLOADED_SO_FAR = "bytes_so_far";

    /**
     * Timestamp when the download was last modified, in {@link System#currentTimeMillis
     * System.currentTimeMillis()} (wall clock time in UTC).
     */
    public final static String COLUMN_LAST_MODIFIED_TIMESTAMP = "last_modified_timestamp";

    /**
     * The URI to the corresponding entry in MediaProvider for this downloaded entry. It is
     * used to delete the entries from MediaProvider database when it is deleted from the
     * downloaded list.
     */
    public static final String COLUMN_MEDIAPROVIDER_URI = Downloads.Impl.COLUMN_MEDIAPROVIDER_URI;

    /** @hide */
    @TestApi
    public static final String COLUMN_MEDIASTORE_URI = Downloads.Impl.COLUMN_MEDIASTORE_URI;

    /**
     * @hide
     */
    public final static String COLUMN_ALLOW_WRITE = Downloads.Impl.COLUMN_ALLOW_WRITE;

    /**
     * Value of {@link #COLUMN_STATUS} when the download is waiting to start.
     */
    public final static int STATUS_PENDING = 1 << 0;

    /**
     * Value of {@link #COLUMN_STATUS} when the download is currently running.
     */
    public final static int STATUS_RUNNING = 1 << 1;

    /**
     * Value of {@link #COLUMN_STATUS} when the download is waiting to retry or resume.
     */
    public final static int STATUS_PAUSED = 1 << 2;

    /**
     * Value of {@link #COLUMN_STATUS} when the download has successfully completed.
     */
    public final static int STATUS_SUCCESSFUL = 1 << 3;

    /**
     * Value of {@link #COLUMN_STATUS} when the download has failed (and will not be retried).
     */
    public final static int STATUS_FAILED = 1 << 4;

    /**
     * Value of COLUMN_ERROR_CODE when the download has completed with an error that doesn't fit
     * under any other error code.
     */
    public final static int ERROR_UNKNOWN = 1000;

    /**
     * Value of {@link #COLUMN_REASON} when a storage issue arises which doesn't fit under any
     * other error code. Use the more specific {@link #ERROR_INSUFFICIENT_SPACE} and
     * {@link #ERROR_DEVICE_NOT_FOUND} when appropriate.
     */
    public final static int ERROR_FILE_ERROR = 1001;

    /**
     * Value of {@link #COLUMN_REASON} when an HTTP code was received that download manager
     * can't handle.
     */
    public final static int ERROR_UNHANDLED_HTTP_CODE = 1002;

    /**
     * Value of {@link #COLUMN_REASON} when an error receiving or processing data occurred at
     * the HTTP level.
     */
    public final static int ERROR_HTTP_DATA_ERROR = 1004;

    /**
     * Value of {@link #COLUMN_REASON} when there were too many redirects.
     */
    public final static int ERROR_TOO_MANY_REDIRECTS = 1005;

    /**
     * Value of {@link #COLUMN_REASON} when there was insufficient storage space. Typically,
     * this is because the SD card is full.
     */
    public final static int ERROR_INSUFFICIENT_SPACE = 1006;

    /**
     * Value of {@link #COLUMN_REASON} when no external storage device was found. Typically,
     * this is because the SD card is not mounted.
     */
    public final static int ERROR_DEVICE_NOT_FOUND = 1007;

    /**
     * Value of {@link #COLUMN_REASON} when some possibly transient error occurred but we can't
     * resume the download.
     */
    public final static int ERROR_CANNOT_RESUME = 1008;

    /**
     * Value of {@link #COLUMN_REASON} when the requested destination file already exists (the
     * download manager will not overwrite an existing file).
     */
    public final static int ERROR_FILE_ALREADY_EXISTS = 1009;

    /**
     * Value of {@link #COLUMN_REASON} when the download has failed because of
     * {@link NetworkPolicyManager} controls on the requesting application.
     *
     * @hide
     */
    public final static int ERROR_BLOCKED = 1010;

    /**
     * Value of {@link #COLUMN_REASON} when the download is paused because some network error
     * occurred and the download manager is waiting before retrying the request.
     */
    public final static int PAUSED_WAITING_TO_RETRY = 1;

    /**
     * Value of {@link #COLUMN_REASON} when the download is waiting for network connectivity to
     * proceed.
     */
    public final static int PAUSED_WAITING_FOR_NETWORK = 2;

    /**
     * Value of {@link #COLUMN_REASON} when the download exceeds a size limit for downloads over
     * the mobile network and the download manager is waiting for a Wi-Fi connection to proceed.
     */
    public final static int PAUSED_QUEUED_FOR_WIFI = 3;

    /**
     * Value of {@link #COLUMN_REASON} when the download is paused for some other reason.
     */
    public final static int PAUSED_UNKNOWN = 4;

    /**
     * Broadcast intent action sent by the download manager when a download completes.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public final static String ACTION_DOWNLOAD_COMPLETE = "android.intent.action.DOWNLOAD_COMPLETE";

    /**
     * Broadcast intent action sent by the download manager when the user clicks on a running
     * download, either from a system notification or from the downloads UI.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public final static String ACTION_NOTIFICATION_CLICKED =
            "android.intent.action.DOWNLOAD_NOTIFICATION_CLICKED";

    /**
     * Intent action to launch an activity to display all downloads.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public final static String ACTION_VIEW_DOWNLOADS = "android.intent.action.VIEW_DOWNLOADS";

    /**
     * Intent extra included with {@link #ACTION_VIEW_DOWNLOADS} to start DownloadApp in
     * sort-by-size mode.
     */
    public final static String INTENT_EXTRAS_SORT_BY_SIZE =
            "android.app.DownloadManager.extra_sortBySize";

    /**
     * Intent extra included with {@link #ACTION_DOWNLOAD_COMPLETE} intents, indicating the ID (as a
     * long) of the download that just completed.
     */
    public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";

    /**
     * When clicks on multiple notifications are received, the following
     * provides an array of download ids corresponding to the download notification that was
     * clicked. It can be retrieved by the receiver of this
     * Intent using {@link android.content.Intent#getLongArrayExtra(String)}.
     */
    public static final String EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS = "extra_click_download_ids";

    /** {@hide} */
    @SystemApi
    public static final String ACTION_DOWNLOAD_COMPLETED =
            "android.intent.action.DOWNLOAD_COMPLETED";

    /**
     * columns to request from DownloadProvider.
     * @hide
     */
    @UnsupportedAppUsage
    public static final String[] UNDERLYING_COLUMNS = new String[] {
        Downloads.Impl._ID,
        Downloads.Impl._DATA + " AS " + COLUMN_LOCAL_FILENAME,
        Downloads.Impl.COLUMN_MEDIAPROVIDER_URI,
        Downloads.Impl.COLUMN_DESTINATION,
        Downloads.Impl.COLUMN_TITLE,
        Downloads.Impl.COLUMN_DESCRIPTION,
        Downloads.Impl.COLUMN_URI,
        Downloads.Impl.COLUMN_STATUS,
        Downloads.Impl.COLUMN_FILE_NAME_HINT,
        Downloads.Impl.COLUMN_MIME_TYPE + " AS " + COLUMN_MEDIA_TYPE,
        Downloads.Impl.COLUMN_TOTAL_BYTES + " AS " + COLUMN_TOTAL_SIZE_BYTES,
        Downloads.Impl.COLUMN_LAST_MODIFICATION + " AS " + COLUMN_LAST_MODIFIED_TIMESTAMP,
        Downloads.Impl.COLUMN_CURRENT_BYTES + " AS " + COLUMN_BYTES_DOWNLOADED_SO_FAR,
        Downloads.Impl.COLUMN_ALLOW_WRITE,
        /* add the following 'computed' columns to the cursor.
         * they are not 'returned' by the database, but their inclusion
         * eliminates need to have lot of methods in CursorTranslator
         */
        "'placeholder' AS " + COLUMN_LOCAL_URI,
        "'placeholder' AS " + COLUMN_REASON
    };

    /**
     * This class contains all the information necessary to request a new download. The URI is the
     * only required parameter.
     *
     * Note that the default download destination is a shared volume where the system might delete
     * your file if it needs to reclaim space for system use. If this is a problem, use a location
     * on external storage (see {@link #setDestinationUri(Uri)}.
     */
    public static class Request {
        /**
         * Bit flag for {@link #setAllowedNetworkTypes} corresponding to
         * {@link ConnectivityManager#TYPE_MOBILE}.
         */
        public static final int NETWORK_MOBILE = 1 << 0;

        /**
         * Bit flag for {@link #setAllowedNetworkTypes} corresponding to
         * {@link ConnectivityManager#TYPE_WIFI}.
         */
        public static final int NETWORK_WIFI = 1 << 1;

        /**
         * Bit flag for {@link #setAllowedNetworkTypes} corresponding to
         * {@link ConnectivityManager#TYPE_BLUETOOTH}.
         * @hide
         */
        @Deprecated
        public static final int NETWORK_BLUETOOTH = 1 << 2;

        @UnsupportedAppUsage
        private Uri mUri;
        private Uri mDestinationUri;
        private List<Pair<String, String>> mRequestHeaders = new ArrayList<Pair<String, String>>();
        private CharSequence mTitle;
        private CharSequence mDescription;
        private String mMimeType;
        private int mAllowedNetworkTypes = ~0; // default to all network types allowed
        private boolean mRoamingAllowed = true;
        private boolean mMeteredAllowed = true;
        private int mFlags = 0;
        private boolean mIsVisibleInDownloadsUi = true;
        private boolean mScannable = false;
        /** if a file is designated as a MediaScanner scannable file, the following value is
         * stored in the database column {@link Downloads.Impl#COLUMN_MEDIA_SCANNED}.
         */
        private static final int SCANNABLE_VALUE_YES = Downloads.Impl.MEDIA_NOT_SCANNED;
        // value of 1 is stored in the above column by DownloadProvider after it is scanned by
        // MediaScanner
        /** if a file is designated as a file that should not be scanned by MediaScanner,
         * the following value is stored in the database column
         * {@link Downloads.Impl#COLUMN_MEDIA_SCANNED}.
         */
        private static final int SCANNABLE_VALUE_NO = Downloads.Impl.MEDIA_NOT_SCANNABLE;

        /**
         * This download is visible but only shows in the notifications
         * while it's in progress.
         */
        public static final int VISIBILITY_VISIBLE = 0;

        /**
         * This download is visible and shows in the notifications while
         * in progress and after completion.
         */
        public static final int VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1;

        /**
         * This download doesn't show in the UI or in the notifications.
         */
        public static final int VISIBILITY_HIDDEN = 2;

        /**
         * This download shows in the notifications after completion ONLY.
         * It is usuable only with
         * {@link DownloadManager#addCompletedDownload(String, String,
         * boolean, String, String, long, boolean)}.
         */
        public static final int VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION = 3;

        /** can take any of the following values: {@link #VISIBILITY_HIDDEN}
         * {@link #VISIBILITY_VISIBLE_NOTIFY_COMPLETED}, {@link #VISIBILITY_VISIBLE},
         * {@link #VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION}
         */
        private int mNotificationVisibility = VISIBILITY_VISIBLE;

        /**
         * @param uri the HTTP or HTTPS URI to download.
         */
        public Request(Uri uri) {
            if (uri == null) {
                throw new NullPointerException();
            }
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                throw new IllegalArgumentException("Can only download HTTP/HTTPS URIs: " + uri);
            }
            mUri = uri;
        }

        Request(String uriString) {
            mUri = Uri.parse(uriString);
        }

        /**
         * Set the local destination for the downloaded file. Must be a file URI to a path on
         * external storage, and the calling application must have the WRITE_EXTERNAL_STORAGE
         * permission.
         * <p>
         * The downloaded file is not scanned by MediaScanner.
         * But it can be made scannable by calling {@link #allowScanningByMediaScanner()}.
         * <p>
         * By default, downloads are saved to a generated filename in the shared download cache and
         * may be deleted by the system at any time to reclaim space.
         *
         * @return this object
         */
        public Request setDestinationUri(Uri uri) {
            mDestinationUri = uri;
            return this;
        }

        /**
         * Set the local destination for the downloaded file to a path within
         * the application's external files directory (as returned by
         * {@link Context#getExternalFilesDir(String)}.
         * <p>
         * The downloaded file is not scanned by MediaScanner. But it can be
         * made scannable by calling {@link #allowScanningByMediaScanner()}.
         *
         * @param context the {@link Context} to use in determining the external
         *            files directory
         * @param dirType the directory type to pass to
         *            {@link Context#getExternalFilesDir(String)}
         * @param subPath the path within the external directory, including the
         *            destination filename
         * @return this object
         * @throws IllegalStateException If the external storage directory
         *             cannot be found or created.
         */
        public Request setDestinationInExternalFilesDir(Context context, String dirType,
                String subPath) {
            final File file = context.getExternalFilesDir(dirType);
            if (file == null) {
                throw new IllegalStateException("Failed to get external storage files directory");
            } else if (file.exists()) {
                if (!file.isDirectory()) {
                    throw new IllegalStateException(file.getAbsolutePath() +
                            " already exists and is not a directory");
                }
            } else {
                if (!file.mkdirs()) {
                    throw new IllegalStateException("Unable to create directory: "+
                            file.getAbsolutePath());
                }
            }
            setDestinationFromBase(file, subPath);
            return this;
        }

        /**
         * Set the local destination for the downloaded file to a path within
         * the public external storage directory (as returned by
         * {@link Environment#getExternalStoragePublicDirectory(String)}).
         * <p>
         * The downloaded file is not scanned by MediaScanner. But it can be
         * made scannable by calling {@link #allowScanningByMediaScanner()}.
         *
         * @param dirType the directory type to pass to {@link Environment#getExternalStoragePublicDirectory(String)}
         * @param subPath the path within the external directory, including the
         *            destination filename
         * @return this object
         * @throws IllegalStateException If the external storage directory
         *             cannot be found or created.
         */
        public Request setDestinationInExternalPublicDir(String dirType, String subPath) {
            File file = Environment.getExternalStoragePublicDirectory(dirType);
            if (file == null) {
                throw new IllegalStateException("Failed to get external storage public directory");
            } else if (file.exists()) {
                if (!file.isDirectory()) {
                    throw new IllegalStateException(file.getAbsolutePath() +
                            " already exists and is not a directory");
                }
            } else {
                if (!file.mkdirs()) {
                    throw new IllegalStateException("Unable to create directory: "+
                            file.getAbsolutePath());
                }
            }
            setDestinationFromBase(file, subPath);
            return this;
        }

        private void setDestinationFromBase(File base, String subPath) {
            if (subPath == null) {
                throw new NullPointerException("subPath cannot be null");
            }
            mDestinationUri = Uri.withAppendedPath(Uri.fromFile(base), subPath);
        }

        /**
         * If the file to be downloaded is to be scanned by MediaScanner, this method
         * should be called before {@link DownloadManager#enqueue(Request)} is called.
         */
        public void allowScanningByMediaScanner() {
            mScannable = true;
        }

        /**
         * Add an HTTP header to be included with the download request.  The header will be added to
         * the end of the list.
         * @param header HTTP header name
         * @param value header value
         * @return this object
         * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.2">HTTP/1.1
         *      Message Headers</a>
         */
        public Request addRequestHeader(String header, String value) {
            if (header == null) {
                throw new NullPointerException("header cannot be null");
            }
            if (header.contains(":")) {
                throw new IllegalArgumentException("header may not contain ':'");
            }
            if (value == null) {
                value = "";
            }
            mRequestHeaders.add(Pair.create(header, value));
            return this;
        }

        /**
         * Set the title of this download, to be displayed in notifications (if enabled).  If no
         * title is given, a default one will be assigned based on the download filename, once the
         * download starts.
         * @return this object
         */
        public Request setTitle(CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * Set a description of this download, to be displayed in notifications (if enabled)
         * @return this object
         */
        public Request setDescription(CharSequence description) {
            mDescription = description;
            return this;
        }

        /**
         * Set the MIME content type of this download.  This will override the content type declared
         * in the server's response.
         * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.7">HTTP/1.1
         *      Media Types</a>
         * @return this object
         */
        public Request setMimeType(String mimeType) {
            mMimeType = mimeType;
            return this;
        }

        /**
         * Control whether a system notification is posted by the download manager while this
         * download is running. If enabled, the download manager posts notifications about downloads
         * through the system {@link android.app.NotificationManager}. By default, a notification is
         * shown.
         *
         * If set to false, this requires the permission
         * android.permission.DOWNLOAD_WITHOUT_NOTIFICATION.
         *
         * @param show whether the download manager should show a notification for this download.
         * @return this object
         * @deprecated use {@link #setNotificationVisibility(int)}
         */
        @Deprecated
        public Request setShowRunningNotification(boolean show) {
            return (show) ? setNotificationVisibility(VISIBILITY_VISIBLE) :
                    setNotificationVisibility(VISIBILITY_HIDDEN);
        }

        /**
         * Control whether a system notification is posted by the download manager while this
         * download is running or when it is completed.
         * If enabled, the download manager posts notifications about downloads
         * through the system {@link android.app.NotificationManager}.
         * By default, a notification is shown only when the download is in progress.
         *<p>
         * It can take the following values: {@link #VISIBILITY_HIDDEN},
         * {@link #VISIBILITY_VISIBLE},
         * {@link #VISIBILITY_VISIBLE_NOTIFY_COMPLETED}.
         *<p>
         * If set to {@link #VISIBILITY_HIDDEN}, this requires the permission
         * android.permission.DOWNLOAD_WITHOUT_NOTIFICATION.
         *
         * @param visibility the visibility setting value
         * @return this object
         */
        public Request setNotificationVisibility(int visibility) {
            mNotificationVisibility = visibility;
            return this;
        }

        /**
         * Restrict the types of networks over which this download may proceed.
         * By default, all network types are allowed. Consider using
         * {@link #setAllowedOverMetered(boolean)} instead, since it's more
         * flexible.
         * <p>
         * As of {@link android.os.Build.VERSION_CODES#N}, setting only the
         * {@link #NETWORK_WIFI} flag here is equivalent to calling
         * {@link #setAllowedOverMetered(boolean)} with {@code false}.
         *
         * @param flags any combination of the NETWORK_* bit flags.
         * @return this object
         */
        public Request setAllowedNetworkTypes(int flags) {
            mAllowedNetworkTypes = flags;
            return this;
        }

        /**
         * Set whether this download may proceed over a roaming connection.  By default, roaming is
         * allowed.
         * @param allowed whether to allow a roaming connection to be used
         * @return this object
         */
        public Request setAllowedOverRoaming(boolean allowed) {
            mRoamingAllowed = allowed;
            return this;
        }

        /**
         * Set whether this download may proceed over a metered network
         * connection. By default, metered networks are allowed.
         *
         * @see ConnectivityManager#isActiveNetworkMetered()
         */
        public Request setAllowedOverMetered(boolean allow) {
            mMeteredAllowed = allow;
            return this;
        }

        /**
         * Specify that to run this download, the device needs to be plugged in.
         * This defaults to false.
         *
         * @param requiresCharging Whether or not the device is plugged in.
         * @see android.app.job.JobInfo.Builder#setRequiresCharging(boolean)
         */
        public Request setRequiresCharging(boolean requiresCharging) {
            if (requiresCharging) {
                mFlags |= Downloads.Impl.FLAG_REQUIRES_CHARGING;
            } else {
                mFlags &= ~Downloads.Impl.FLAG_REQUIRES_CHARGING;
            }
            return this;
        }

        /**
         * Specify that to run, the download needs the device to be in idle
         * mode. This defaults to false.
         * <p>
         * Idle mode is a loose definition provided by the system, which means
         * that the device is not in use, and has not been in use for some time.
         *
         * @param requiresDeviceIdle Whether or not the device need be within an
         *            idle maintenance window.
         * @see android.app.job.JobInfo.Builder#setRequiresDeviceIdle(boolean)
         */
        public Request setRequiresDeviceIdle(boolean requiresDeviceIdle) {
            if (requiresDeviceIdle) {
                mFlags |= Downloads.Impl.FLAG_REQUIRES_DEVICE_IDLE;
            } else {
                mFlags &= ~Downloads.Impl.FLAG_REQUIRES_DEVICE_IDLE;
            }
            return this;
        }

        /**
         * Set whether this download should be displayed in the system's Downloads UI. True by
         * default.
         * @param isVisible whether to display this download in the Downloads UI
         * @return this object
         */
        public Request setVisibleInDownloadsUi(boolean isVisible) {
            mIsVisibleInDownloadsUi = isVisible;
            return this;
        }

        /**
         * @return ContentValues to be passed to DownloadProvider.insert()
         */
        ContentValues toContentValues(String packageName) {
            ContentValues values = new ContentValues();
            assert mUri != null;
            values.put(Downloads.Impl.COLUMN_URI, mUri.toString());
            values.put(Downloads.Impl.COLUMN_IS_PUBLIC_API, true);
            values.put(Downloads.Impl.COLUMN_NOTIFICATION_PACKAGE, packageName);

            if (mDestinationUri != null) {
                values.put(Downloads.Impl.COLUMN_DESTINATION,
                        Downloads.Impl.DESTINATION_FILE_URI);
                values.put(Downloads.Impl.COLUMN_FILE_NAME_HINT,
                        mDestinationUri.toString());
            } else {
                values.put(Downloads.Impl.COLUMN_DESTINATION,
                        Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE);
            }
            // is the file supposed to be media-scannable?
            values.put(Downloads.Impl.COLUMN_MEDIA_SCANNED, (mScannable) ? SCANNABLE_VALUE_YES :
                    SCANNABLE_VALUE_NO);

            if (!mRequestHeaders.isEmpty()) {
                encodeHttpHeaders(values);
            }

            putIfNonNull(values, Downloads.Impl.COLUMN_TITLE, mTitle);
            putIfNonNull(values, Downloads.Impl.COLUMN_DESCRIPTION, mDescription);
            putIfNonNull(values, Downloads.Impl.COLUMN_MIME_TYPE, mMimeType);

            values.put(Downloads.Impl.COLUMN_VISIBILITY, mNotificationVisibility);
            values.put(Downloads.Impl.COLUMN_ALLOWED_NETWORK_TYPES, mAllowedNetworkTypes);
            values.put(Downloads.Impl.COLUMN_ALLOW_ROAMING, mRoamingAllowed);
            values.put(Downloads.Impl.COLUMN_ALLOW_METERED, mMeteredAllowed);
            values.put(Downloads.Impl.COLUMN_FLAGS, mFlags);
            values.put(Downloads.Impl.COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI, mIsVisibleInDownloadsUi);

            return values;
        }

        private void encodeHttpHeaders(ContentValues values) {
            int index = 0;
            for (Pair<String, String> header : mRequestHeaders) {
                String headerString = header.first + ": " + header.second;
                values.put(Downloads.Impl.RequestHeaders.INSERT_KEY_PREFIX + index, headerString);
                index++;
            }
        }

        private void putIfNonNull(ContentValues contentValues, String key, Object value) {
            if (value != null) {
                contentValues.put(key, value.toString());
            }
        }
    }

    /**
     * This class may be used to filter download manager queries.
     */
    public static class Query {
        /**
         * Constant for use with {@link #orderBy}
         * @hide
         */
        public static final int ORDER_ASCENDING = 1;

        /**
         * Constant for use with {@link #orderBy}
         * @hide
         */
        public static final int ORDER_DESCENDING = 2;

        private long[] mIds = null;
        private Integer mStatusFlags = null;
        private String mFilterString = null;
        private String mOrderByColumn = Downloads.Impl.COLUMN_LAST_MODIFICATION;
        private int mOrderDirection = ORDER_DESCENDING;
        private boolean mOnlyIncludeVisibleInDownloadsUi = false;

        /**
         * Include only the downloads with the given IDs.
         * @return this object
         */
        public Query setFilterById(long... ids) {
            mIds = ids;
            return this;
        }

        /**
         *
         * Include only the downloads that contains the given string in its name.
         * @return this object
         * @hide
         */
        public Query setFilterByString(@Nullable String filter) {
            mFilterString = filter;
            return this;
        }

        /**
         * Include only downloads with status matching any the given status flags.
         * @param flags any combination of the STATUS_* bit flags
         * @return this object
         */
        public Query setFilterByStatus(int flags) {
            mStatusFlags = flags;
            return this;
        }

        /**
         * Controls whether this query includes downloads not visible in the system's Downloads UI.
         * @param value if true, this query will only include downloads that should be displayed in
         *            the system's Downloads UI; if false (the default), this query will include
         *            both visible and invisible downloads.
         * @return this object
         * @hide
         */
        @UnsupportedAppUsage
        public Query setOnlyIncludeVisibleInDownloadsUi(boolean value) {
            mOnlyIncludeVisibleInDownloadsUi = value;
            return this;
        }

        /**
         * Change the sort order of the returned Cursor.
         *
         * @param column one of the COLUMN_* constants; currently, only
         *         {@link #COLUMN_LAST_MODIFIED_TIMESTAMP} and {@link #COLUMN_TOTAL_SIZE_BYTES} are
         *         supported.
         * @param direction either {@link #ORDER_ASCENDING} or {@link #ORDER_DESCENDING}
         * @return this object
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public Query orderBy(String column, int direction) {
            if (direction != ORDER_ASCENDING && direction != ORDER_DESCENDING) {
                throw new IllegalArgumentException("Invalid direction: " + direction);
            }

            if (column.equals(COLUMN_LAST_MODIFIED_TIMESTAMP)) {
                mOrderByColumn = Downloads.Impl.COLUMN_LAST_MODIFICATION;
            } else if (column.equals(COLUMN_TOTAL_SIZE_BYTES)) {
                mOrderByColumn = Downloads.Impl.COLUMN_TOTAL_BYTES;
            } else {
                throw new IllegalArgumentException("Cannot order by " + column);
            }
            mOrderDirection = direction;
            return this;
        }

        /**
         * Run this query using the given ContentResolver.
         * @param projection the projection to pass to ContentResolver.query()
         * @return the Cursor returned by ContentResolver.query()
         */
        Cursor runQuery(ContentResolver resolver, String[] projection, Uri baseUri) {
            Uri uri = baseUri;
            List<String> selectionParts = new ArrayList<String>();
            String[] selectionArgs = null;

            int whereArgsCount = (mIds == null) ? 0 : mIds.length;
            whereArgsCount = (mFilterString == null) ? whereArgsCount : whereArgsCount + 1;
            selectionArgs = new String[whereArgsCount];

            if (whereArgsCount > 0) {
                if (mIds != null) {
                    selectionParts.add(getWhereClauseForIds(mIds));
                    getWhereArgsForIds(mIds, selectionArgs);
                }

                if (mFilterString != null) {
                    selectionParts.add(Downloads.Impl.COLUMN_TITLE + " LIKE ?");
                    selectionArgs[selectionArgs.length - 1] = "%" + mFilterString + "%";
                }
            }

            if (mStatusFlags != null) {
                List<String> parts = new ArrayList<String>();
                if ((mStatusFlags & STATUS_PENDING) != 0) {
                    parts.add(statusClause("=", Downloads.Impl.STATUS_PENDING));
                }
                if ((mStatusFlags & STATUS_RUNNING) != 0) {
                    parts.add(statusClause("=", Downloads.Impl.STATUS_RUNNING));
                }
                if ((mStatusFlags & STATUS_PAUSED) != 0) {
                    parts.add(statusClause("=", Downloads.Impl.STATUS_PAUSED_BY_APP));
                    parts.add(statusClause("=", Downloads.Impl.STATUS_WAITING_TO_RETRY));
                    parts.add(statusClause("=", Downloads.Impl.STATUS_WAITING_FOR_NETWORK));
                    parts.add(statusClause("=", Downloads.Impl.STATUS_QUEUED_FOR_WIFI));
                }
                if ((mStatusFlags & STATUS_SUCCESSFUL) != 0) {
                    parts.add(statusClause("=", Downloads.Impl.STATUS_SUCCESS));
                }
                if ((mStatusFlags & STATUS_FAILED) != 0) {
                    parts.add("(" + statusClause(">=", 400)
                              + " AND " + statusClause("<", 600) + ")");
                }
                selectionParts.add(joinStrings(" OR ", parts));
            }

            if (mOnlyIncludeVisibleInDownloadsUi) {
                selectionParts.add(Downloads.Impl.COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI + " != '0'");
            }

            // only return rows which are not marked 'deleted = 1'
            selectionParts.add(Downloads.Impl.COLUMN_DELETED + " != '1'");

            String selection = joinStrings(" AND ", selectionParts);
            String orderDirection = (mOrderDirection == ORDER_ASCENDING ? "ASC" : "DESC");
            String orderBy = mOrderByColumn + " " + orderDirection;

            return resolver.query(uri, projection, selection, selectionArgs, orderBy);
        }

        private String joinStrings(String joiner, Iterable<String> parts) {
            StringBuilder builder = new StringBuilder();
            boolean first = true;
            for (String part : parts) {
                if (!first) {
                    builder.append(joiner);
                }
                builder.append(part);
                first = false;
            }
            return builder.toString();
        }

        private String statusClause(String operator, int value) {
            return Downloads.Impl.COLUMN_STATUS + operator + "'" + value + "'";
        }
    }

    private final ContentResolver mResolver;
    private final String mPackageName;

    private Uri mBaseUri = Downloads.Impl.CONTENT_URI;
    private boolean mAccessFilename;

    /**
     * @hide
     */
    public DownloadManager(Context context) {
        mResolver = context.getContentResolver();
        mPackageName = context.getPackageName();

        // Callers can access filename columns when targeting old platform
        // versions; otherwise we throw telling them it's deprecated.
        mAccessFilename = context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.N;
    }

    /**
     * Makes this object access the download provider through /all_downloads URIs rather than
     * /my_downloads URIs, for clients that have permission to do so.
     * @hide
     */
    @UnsupportedAppUsage
    public void setAccessAllDownloads(boolean accessAllDownloads) {
        if (accessAllDownloads) {
            mBaseUri = Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI;
        } else {
            mBaseUri = Downloads.Impl.CONTENT_URI;
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setAccessFilename(boolean accessFilename) {
        mAccessFilename = accessFilename;
    }

    /**
     * Enqueue a new download.  The download will start automatically once the download manager is
     * ready to execute it and connectivity is available.
     *
     * @param request the parameters specifying this download
     * @return an ID for the download, unique across the system.  This ID is used to make future
     * calls related to this download.
     */
    public long enqueue(Request request) {
        ContentValues values = request.toContentValues(mPackageName);
        Uri downloadUri = mResolver.insert(Downloads.Impl.CONTENT_URI, values);
        long id = Long.parseLong(downloadUri.getLastPathSegment());
        return id;
    }

    /**
     * Marks the specified download as 'to be deleted'. This is done when a completed download
     * is to be removed but the row was stored without enough info to delete the corresponding
     * metadata from Mediaprovider database. Actual cleanup of this row is done in DownloadService.
     *
     * @param ids the IDs of the downloads to be marked 'deleted'
     * @return the number of downloads actually updated
     * @hide
     */
    public int markRowDeleted(long... ids) {
        if (ids == null || ids.length == 0) {
            // called with nothing to remove!
            throw new IllegalArgumentException("input param 'ids' can't be null");
        }
        return mResolver.delete(mBaseUri, getWhereClauseForIds(ids), getWhereArgsForIds(ids));
    }

    /**
     * Cancel downloads and remove them from the download manager.  Each download will be stopped if
     * it was running, and it will no longer be accessible through the download manager.
     * If there is a downloaded file, partial or complete, it is deleted.
     *
     * @param ids the IDs of the downloads to remove
     * @return the number of downloads actually removed
     */
    public int remove(long... ids) {
        return markRowDeleted(ids);
    }

    /**
     * Query the download manager about downloads that have been requested.
     * @param query parameters specifying filters for this query
     * @return a Cursor over the result set of downloads, with columns consisting of all the
     * COLUMN_* constants.
     */
    public Cursor query(Query query) {
        return query(query, UNDERLYING_COLUMNS);
    }

    /** @hide */
    public Cursor query(Query query, String[] projection) {
        Cursor underlyingCursor = query.runQuery(mResolver, projection, mBaseUri);
        if (underlyingCursor == null) {
            return null;
        }
        return new CursorTranslator(underlyingCursor, mBaseUri, mAccessFilename);
    }

    /**
     * Open a downloaded file for reading.  The download must have completed.
     * @param id the ID of the download
     * @return a read-only {@link ParcelFileDescriptor}
     * @throws FileNotFoundException if the destination file does not already exist
     */
    public ParcelFileDescriptor openDownloadedFile(long id) throws FileNotFoundException {
        return mResolver.openFileDescriptor(getDownloadUri(id), "r");
    }

    /**
     * Returns the {@link Uri} of the given downloaded file id, if the file is
     * downloaded successfully. Otherwise, null is returned.
     *
     * @param id the id of the downloaded file.
     * @return the {@link Uri} of the given downloaded file id, if download was
     *         successful. null otherwise.
     */
    public Uri getUriForDownloadedFile(long id) {
        // to check if the file is in cache, get its destination from the database
        Query query = new Query().setFilterById(id);
        Cursor cursor = null;
        try {
            cursor = query(query);
            if (cursor == null) {
                return null;
            }
            if (cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_STATUS));
                if (DownloadManager.STATUS_SUCCESSFUL == status) {
                    return ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, id);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        // downloaded file not found or its status is not 'successfully completed'
        return null;
    }

    /**
     * Returns the media type of the given downloaded file id, if the file was
     * downloaded successfully. Otherwise, null is returned.
     *
     * @param id the id of the downloaded file.
     * @return the media type of the given downloaded file id, if download was successful. null
     * otherwise.
     */
    public String getMimeTypeForDownloadedFile(long id) {
        Query query = new Query().setFilterById(id);
        Cursor cursor = null;
        try {
            cursor = query(query);
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MEDIA_TYPE));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        // downloaded file not found or its status is not 'successfully completed'
        return null;
    }

    /**
     * Restart the given downloads, which must have already completed (successfully or not).  This
     * method will only work when called from within the download manager's process.
     * @param ids the IDs of the downloads
     * @hide
     */
    @UnsupportedAppUsage
    public void restartDownload(long... ids) {
        Cursor cursor = query(new Query().setFilterById(ids));
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                int status = cursor.getInt(cursor.getColumnIndex(COLUMN_STATUS));
                if (status != STATUS_SUCCESSFUL && status != STATUS_FAILED) {
                    throw new IllegalArgumentException("Cannot restart incomplete download: "
                            + cursor.getLong(cursor.getColumnIndex(COLUMN_ID)));
                }
            }
        } finally {
            cursor.close();
        }

        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, 0);
        values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, -1);
        values.putNull(Downloads.Impl._DATA);
        values.put(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_PENDING);
        values.put(Downloads.Impl.COLUMN_FAILED_CONNECTIONS, 0);
        mResolver.update(mBaseUri, values, getWhereClauseForIds(ids), getWhereArgsForIds(ids));
    }

    /**
     * Force the given downloads to proceed even if their size is larger than
     * {@link #getMaxBytesOverMobile(Context)}.
     *
     * @hide
     */
    public void forceDownload(long... ids) {
        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_PENDING);
        values.put(Downloads.Impl.COLUMN_CONTROL, Downloads.Impl.CONTROL_RUN);
        values.put(Downloads.Impl.COLUMN_BYPASS_RECOMMENDED_SIZE_LIMIT, 1);
        mResolver.update(mBaseUri, values, getWhereClauseForIds(ids), getWhereArgsForIds(ids));
    }

    /**
     * Returns maximum size, in bytes, of downloads that may go over a mobile connection; or null if
     * there's no limit
     *
     * @param context the {@link Context} to use for accessing the {@link ContentResolver}
     * @return maximum size, in bytes, of downloads that may go over a mobile connection; or null if
     * there's no limit
     */
    public static Long getMaxBytesOverMobile(Context context) {
        try {
            return Settings.Global.getLong(context.getContentResolver(),
                    Settings.Global.DOWNLOAD_MAX_BYTES_OVER_MOBILE);
        } catch (SettingNotFoundException exc) {
            return null;
        }
    }

    /**
     * Rename the given download if the download has completed
     *
     * @param context the {@link Context} to use in case need to update MediaProvider
     * @param id the downloaded id
     * @param displayName the new name to rename to
     * @return true if rename was successful, false otherwise
     * @hide
     */
    public boolean rename(Context context, long id, String displayName) {
        if (!FileUtils.isValidFatFilename(displayName)) {
            throw new SecurityException(displayName + " is not a valid filename");
        }

        Query query = new Query().setFilterById(id);
        Cursor cursor = null;
        String oldDisplayName = null;
        String mimeType = null;
        try {
            cursor = query(query);
            if (cursor == null) {
                return false;
            }
            if (cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_STATUS));
                if (DownloadManager.STATUS_SUCCESSFUL != status) {
                    return false;
                }
                oldDisplayName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE));
                mimeType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MEDIA_TYPE));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (oldDisplayName == null || mimeType == null) {
            throw new IllegalStateException(
                    "Document with id " + id + " does not exist");
        }

        final File parent = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);

        final File before = new File(parent, oldDisplayName);
        final File after = new File(parent, displayName);

        if (after.exists()) {
            throw new IllegalStateException("Already exists " + after);
        }
        if (!before.renameTo(after)) {
            throw new IllegalStateException("Failed to rename to " + after);
        }

        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_TITLE, displayName);
        values.put(Downloads.Impl._DATA, after.toString());
        values.putNull(Downloads.Impl.COLUMN_MEDIAPROVIDER_URI);
        long[] ids = {id};

        return (mResolver.update(mBaseUri, values, getWhereClauseForIds(ids),
                getWhereArgsForIds(ids)) == 1);
    }

    /**
     * Returns recommended maximum size, in bytes, of downloads that may go over a mobile
     * connection; or null if there's no recommended limit.  The user will have the option to bypass
     * this limit.
     *
     * @param context the {@link Context} to use for accessing the {@link ContentResolver}
     * @return recommended maximum size, in bytes, of downloads that may go over a mobile
     * connection; or null if there's no recommended limit.
     */
    public static Long getRecommendedMaxBytesOverMobile(Context context) {
        try {
            return Settings.Global.getLong(context.getContentResolver(),
                    Settings.Global.DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE);
        } catch (SettingNotFoundException exc) {
            return null;
        }
    }

    /** {@hide} */
    public static boolean isActiveNetworkExpensive(Context context) {
        // TODO: connect to NetworkPolicyManager
        return false;
    }

    /** {@hide} */
    public static long getActiveNetworkWarningBytes(Context context) {
        // TODO: connect to NetworkPolicyManager
        return -1;
    }

    /**
     * Adds a file to the downloads database system, so it could appear in Downloads App
     * (and thus become eligible for management by the Downloads App).
     * <p>
     * It is helpful to make the file scannable by MediaScanner by setting the param
     * isMediaScannerScannable to true. It makes the file visible in media managing
     * applications such as Gallery App, which could be a useful purpose of using this API.
     *
     * @param title the title that would appear for this file in Downloads App.
     * @param description the description that would appear for this file in Downloads App.
     * @param isMediaScannerScannable true if the file is to be scanned by MediaScanner. Files
     * scanned by MediaScanner appear in the applications used to view media (for example,
     * Gallery app).
     * @param mimeType mimetype of the file.
     * @param path absolute pathname to the file. The file should be world-readable, so that it can
     * be managed by the Downloads App and any other app that is used to read it (for example,
     * Gallery app to display the file, if the file contents represent a video/image).
     * @param length length of the downloaded file
     * @param showNotification true if a notification is to be sent, false otherwise
     * @return  an ID for the download entry added to the downloads app, unique across the system
     * This ID is used to make future calls related to this download.
     */
    public long addCompletedDownload(String title, String description,
            boolean isMediaScannerScannable, String mimeType, String path, long length,
            boolean showNotification) {
        return addCompletedDownload(title, description, isMediaScannerScannable, mimeType, path,
                length, showNotification, false, null, null);
    }

    /**
     * Adds a file to the downloads database system, so it could appear in Downloads App
     * (and thus become eligible for management by the Downloads App).
     * <p>
     * It is helpful to make the file scannable by MediaScanner by setting the param
     * isMediaScannerScannable to true. It makes the file visible in media managing
     * applications such as Gallery App, which could be a useful purpose of using this API.
     *
     * @param title the title that would appear for this file in Downloads App.
     * @param description the description that would appear for this file in Downloads App.
     * @param isMediaScannerScannable true if the file is to be scanned by MediaScanner. Files
     * scanned by MediaScanner appear in the applications used to view media (for example,
     * Gallery app).
     * @param mimeType mimetype of the file.
     * @param path absolute pathname to the file. The file should be world-readable, so that it can
     * be managed by the Downloads App and any other app that is used to read it (for example,
     * Gallery app to display the file, if the file contents represent a video/image).
     * @param length length of the downloaded file
     * @param showNotification true if a notification is to be sent, false otherwise
     * @param uri the original HTTP URI of the download
     * @param referer the HTTP Referer for the download
     * @return  an ID for the download entry added to the downloads app, unique across the system
     * This ID is used to make future calls related to this download.
     */
    public long addCompletedDownload(String title, String description,
            boolean isMediaScannerScannable, String mimeType, String path, long length,
            boolean showNotification, Uri uri, Uri referer) {
        return addCompletedDownload(title, description, isMediaScannerScannable, mimeType, path,
                length, showNotification, false, uri, referer);
    }

    /** {@hide} */
    public long addCompletedDownload(String title, String description,
            boolean isMediaScannerScannable, String mimeType, String path, long length,
            boolean showNotification, boolean allowWrite) {
        return addCompletedDownload(title, description, isMediaScannerScannable, mimeType, path,
                length, showNotification, allowWrite, null, null);
    }

    /** {@hide} */
    public long addCompletedDownload(String title, String description,
            boolean isMediaScannerScannable, String mimeType, String path, long length,
            boolean showNotification, boolean allowWrite, Uri uri, Uri referer) {
        // make sure the input args are non-null/non-zero
        validateArgumentIsNonEmpty("title", title);
        validateArgumentIsNonEmpty("description", description);
        validateArgumentIsNonEmpty("path", path);
        validateArgumentIsNonEmpty("mimeType", mimeType);
        if (length < 0) {
            throw new IllegalArgumentException(" invalid value for param: totalBytes");
        }

        // if there is already an entry with the given path name in downloads.db, return its id
        Request request;
        if (uri != null) {
            request = new Request(uri);
        } else {
            request = new Request(NON_DOWNLOADMANAGER_DOWNLOAD);
        }
        request.setTitle(title)
                .setDescription(description)
                .setMimeType(mimeType);
        if (referer != null) {
            request.addRequestHeader("Referer", referer.toString());
        }
        ContentValues values = request.toContentValues(null);
        values.put(Downloads.Impl.COLUMN_DESTINATION,
                Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD);
        values.put(Downloads.Impl._DATA, path);
        values.put(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_SUCCESS);
        values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, length);
        values.put(Downloads.Impl.COLUMN_MEDIA_SCANNED,
                (isMediaScannerScannable) ? Request.SCANNABLE_VALUE_YES :
                        Request.SCANNABLE_VALUE_NO);
        values.put(Downloads.Impl.COLUMN_VISIBILITY, (showNotification) ?
                Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION : Request.VISIBILITY_HIDDEN);
        values.put(Downloads.Impl.COLUMN_ALLOW_WRITE, allowWrite ? 1 : 0);
        Uri downloadUri = mResolver.insert(Downloads.Impl.CONTENT_URI, values);
        if (downloadUri == null) {
            return -1;
        }
        return Long.parseLong(downloadUri.getLastPathSegment());
    }

    private static final String NON_DOWNLOADMANAGER_DOWNLOAD =
            "non-dwnldmngr-download-dont-retry2download";

    private static void validateArgumentIsNonEmpty(String paramName, String val) {
        if (TextUtils.isEmpty(val)) {
            throw new IllegalArgumentException(paramName + " can't be null");
        }
    }

    /**
     * Get the DownloadProvider URI for the download with the given ID.
     *
     * @hide
     */
    public Uri getDownloadUri(long id) {
        return ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, id);
    }

    /**
     * Get a parameterized SQL WHERE clause to select a bunch of IDs.
     */
    @UnsupportedAppUsage
    static String getWhereClauseForIds(long[] ids) {
        StringBuilder whereClause = new StringBuilder();
        whereClause.append("(");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                whereClause.append("OR ");
            }
            whereClause.append(Downloads.Impl._ID);
            whereClause.append(" = ? ");
        }
        whereClause.append(")");
        return whereClause.toString();
    }

    /**
     * Get the selection args for a clause returned by {@link #getWhereClauseForIds(long[])}.
     */
    @UnsupportedAppUsage
    static String[] getWhereArgsForIds(long[] ids) {
        String[] whereArgs = new String[ids.length];
        return getWhereArgsForIds(ids, whereArgs);
    }

    /**
     * Get selection args for a clause returned by {@link #getWhereClauseForIds(long[])}
     * and write it to the supplied args array.
     */
    static String[] getWhereArgsForIds(long[] ids, String[] args) {
        assert(args.length >= ids.length);
        for (int i = 0; i < ids.length; i++) {
            args[i] = Long.toString(ids[i]);
        }
        return args;
    }


    /**
     * This class wraps a cursor returned by DownloadProvider -- the "underlying cursor" -- and
     * presents a different set of columns, those defined in the DownloadManager.COLUMN_* constants.
     * Some columns correspond directly to underlying values while others are computed from
     * underlying data.
     */
    private static class CursorTranslator extends CursorWrapper {
        private final Uri mBaseUri;
        private final boolean mAccessFilename;

        public CursorTranslator(Cursor cursor, Uri baseUri, boolean accessFilename) {
            super(cursor);
            mBaseUri = baseUri;
            mAccessFilename = accessFilename;
        }

        @Override
        public int getInt(int columnIndex) {
            return (int) getLong(columnIndex);
        }

        @Override
        public long getLong(int columnIndex) {
            if (getColumnName(columnIndex).equals(COLUMN_REASON)) {
                return getReason(super.getInt(getColumnIndex(Downloads.Impl.COLUMN_STATUS)));
            } else if (getColumnName(columnIndex).equals(COLUMN_STATUS)) {
                return translateStatus(super.getInt(getColumnIndex(Downloads.Impl.COLUMN_STATUS)));
            } else {
                return super.getLong(columnIndex);
            }
        }

        @Override
        public String getString(int columnIndex) {
            final String columnName = getColumnName(columnIndex);
            switch (columnName) {
                case COLUMN_LOCAL_URI:
                    return getLocalUri();
                case COLUMN_LOCAL_FILENAME:
                    if (!mAccessFilename) {
                        throw new SecurityException(
                                "COLUMN_LOCAL_FILENAME is deprecated;"
                                        + " use ContentResolver.openFileDescriptor() instead");
                    }
                default:
                    return super.getString(columnIndex);
            }
        }

        private String getLocalUri() {
            long destinationType = getLong(getColumnIndex(Downloads.Impl.COLUMN_DESTINATION));
            if (destinationType == Downloads.Impl.DESTINATION_FILE_URI ||
                    destinationType == Downloads.Impl.DESTINATION_EXTERNAL ||
                    destinationType == Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD) {
                String localPath = super.getString(getColumnIndex(COLUMN_LOCAL_FILENAME));
                if (localPath == null) {
                    return null;
                }
                return Uri.fromFile(new File(localPath)).toString();
            }

            // return content URI for cache download
            long downloadId = getLong(getColumnIndex(Downloads.Impl._ID));
            return ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, downloadId).toString();
        }

        private long getReason(int status) {
            switch (translateStatus(status)) {
                case STATUS_FAILED:
                    return getErrorCode(status);

                case STATUS_PAUSED:
                    return getPausedReason(status);

                default:
                    return 0; // arbitrary value when status is not an error
            }
        }

        private long getPausedReason(int status) {
            switch (status) {
                case Downloads.Impl.STATUS_WAITING_TO_RETRY:
                    return PAUSED_WAITING_TO_RETRY;

                case Downloads.Impl.STATUS_WAITING_FOR_NETWORK:
                    return PAUSED_WAITING_FOR_NETWORK;

                case Downloads.Impl.STATUS_QUEUED_FOR_WIFI:
                    return PAUSED_QUEUED_FOR_WIFI;

                default:
                    return PAUSED_UNKNOWN;
            }
        }

        private long getErrorCode(int status) {
            if ((400 <= status && status < Downloads.Impl.MIN_ARTIFICIAL_ERROR_STATUS)
                    || (500 <= status && status < 600)) {
                // HTTP status code
                return status;
            }

            switch (status) {
                case Downloads.Impl.STATUS_FILE_ERROR:
                    return ERROR_FILE_ERROR;

                case Downloads.Impl.STATUS_UNHANDLED_HTTP_CODE:
                case Downloads.Impl.STATUS_UNHANDLED_REDIRECT:
                    return ERROR_UNHANDLED_HTTP_CODE;

                case Downloads.Impl.STATUS_HTTP_DATA_ERROR:
                    return ERROR_HTTP_DATA_ERROR;

                case Downloads.Impl.STATUS_TOO_MANY_REDIRECTS:
                    return ERROR_TOO_MANY_REDIRECTS;

                case Downloads.Impl.STATUS_INSUFFICIENT_SPACE_ERROR:
                    return ERROR_INSUFFICIENT_SPACE;

                case Downloads.Impl.STATUS_DEVICE_NOT_FOUND_ERROR:
                    return ERROR_DEVICE_NOT_FOUND;

                case Downloads.Impl.STATUS_CANNOT_RESUME:
                    return ERROR_CANNOT_RESUME;

                case Downloads.Impl.STATUS_FILE_ALREADY_EXISTS_ERROR:
                    return ERROR_FILE_ALREADY_EXISTS;

                default:
                    return ERROR_UNKNOWN;
            }
        }

        private int translateStatus(int status) {
            switch (status) {
                case Downloads.Impl.STATUS_PENDING:
                    return STATUS_PENDING;

                case Downloads.Impl.STATUS_RUNNING:
                    return STATUS_RUNNING;

                case Downloads.Impl.STATUS_PAUSED_BY_APP:
                case Downloads.Impl.STATUS_WAITING_TO_RETRY:
                case Downloads.Impl.STATUS_WAITING_FOR_NETWORK:
                case Downloads.Impl.STATUS_QUEUED_FOR_WIFI:
                    return STATUS_PAUSED;

                case Downloads.Impl.STATUS_SUCCESS:
                    return STATUS_SUCCESSFUL;

                default:
                    assert Downloads.Impl.isStatusError(status);
                    return STATUS_FAILED;
            }
        }
    }
}
