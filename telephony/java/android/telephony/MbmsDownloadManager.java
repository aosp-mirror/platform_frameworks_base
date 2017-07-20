/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.FileInfo;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.IDownloadProgressListener;
import android.telephony.mbms.IMbmsDownloadManagerCallback;
import android.telephony.mbms.MbmsDownloadManagerCallback;
import android.telephony.mbms.MbmsDownloadReceiver;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.MbmsTempFileProvider;
import android.telephony.mbms.MbmsUtils;
import android.telephony.mbms.vendor.IMbmsDownloadService;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

/** @hide */
public class MbmsDownloadManager {
    private static final String LOG_TAG = MbmsDownloadManager.class.getSimpleName();

    public static final String MBMS_DOWNLOAD_SERVICE_ACTION =
            "android.telephony.action.EmbmsDownload";
    /**
     * The MBMS middleware should send this when a download of single file has completed or
     * failed. Mandatory extras are
     * {@link #EXTRA_RESULT}
     * {@link #EXTRA_FILE_INFO}
     * {@link #EXTRA_REQUEST}
     * {@link #EXTRA_TEMP_LIST}
     * {@link #EXTRA_FINAL_URI}
     *
     * TODO: future systemapi
     */
    public static final String ACTION_DOWNLOAD_RESULT_INTERNAL =
            "android.telephony.mbms.action.DOWNLOAD_RESULT_INTERNAL";

    /**
     * The MBMS middleware should send this when it wishes to request {@code content://} URIs to
     * serve as temp files for downloads or when it wishes to resume paused downloads. Mandatory
     * extras are
     * {@link #EXTRA_REQUEST}
     *
     * Optional extras are
     * {@link #EXTRA_FD_COUNT} (0 if not present)
     * {@link #EXTRA_PAUSED_LIST} (empty if not present)
     *
     * TODO: future systemapi
     */
    public static final String ACTION_FILE_DESCRIPTOR_REQUEST =
            "android.telephony.mbms.action.FILE_DESCRIPTOR_REQUEST";

    /**
     * The MBMS middleware should send this when it wishes to clean up temp  files in the app's
     * filesystem. Mandatory extras are:
     * {@link #EXTRA_TEMP_FILES_IN_USE}
     *
     * TODO: future systemapi
     */
    public static final String ACTION_CLEANUP =
            "android.telephony.mbms.action.CLEANUP";

    /**
     * Integer extra indicating the result code of the download. One of
     * {@link #RESULT_SUCCESSFUL}, {@link #RESULT_EXPIRED}, or {@link #RESULT_CANCELLED}.
     * TODO: Not systemapi.
     */
    public static final String EXTRA_RESULT = "android.telephony.mbms.extra.RESULT";

    /**
     * Extra containing the {@link android.telephony.mbms.FileInfo} for which the download result
     * is for. Must not be null.
     * TODO: Not systemapi.
     */
    public static final String EXTRA_FILE_INFO = "android.telephony.mbms.extra.FILE_INFO";

    /**
     * Extra containing the {@link DownloadRequest} for which the download result or file
     * descriptor request is for. Must not be null.
     * TODO: future systemapi (here and and all extras) except the three for the app intent
     */
    public static final String EXTRA_REQUEST = "android.telephony.mbms.extra.REQUEST";

    /**
     * Extra containing a {@link List} of {@link Uri}s that were used as temp files for this
     * completed file. These {@link Uri}s should have scheme {@code file://}, and the temp
     * files will be deleted upon receipt of the intent.
     * May be null.
     */
    public static final String EXTRA_TEMP_LIST = "android.telephony.mbms.extra.TEMP_LIST";

    /**
     * Extra containing a single {@link Uri} indicating the path to the temp file in which the
     * decoded downloaded file resides. Must not be null.
     */
    public static final String EXTRA_FINAL_URI = "android.telephony.mbms.extra.FINAL_URI";

    /**
     * Extra containing an integer indicating the number of temp files requested.
     */
    public static final String EXTRA_FD_COUNT = "android.telephony.mbms.extra.FD_COUNT";

    /**
     * Extra containing a list of {@link Uri}s that the middleware is requesting access to via
     * {@link #ACTION_FILE_DESCRIPTOR_REQUEST} in order to resume downloading. These {@link Uri}s
     * should have scheme {@code file://}.
     */
    public static final String EXTRA_PAUSED_LIST = "android.telephony.mbms.extra.PAUSED_LIST";

    /**
     * Extra containing a list of {@link android.telephony.mbms.UriPathPair}s, used in the
     * response to {@link #ACTION_FILE_DESCRIPTOR_REQUEST}. These are temp files that are meant
     * to be used for new file downloads.
     */
    public static final String EXTRA_FREE_URI_LIST = "android.telephony.mbms.extra.FREE_URI_LIST";

    /**
     * Extra containing a list of {@link android.telephony.mbms.UriPathPair}s, used in the
     * response to {@link #ACTION_FILE_DESCRIPTOR_REQUEST}. These
     * {@link android.telephony.mbms.UriPathPair}s contain {@code content://} URIs that provide
     * access to previously paused downloads.
     */
    public static final String EXTRA_PAUSED_URI_LIST =
            "android.telephony.mbms.extra.PAUSED_URI_LIST";

    /**
     * Extra containing a string that points to the middleware's knowledge of where the temp file
     * root for the app is. The path should be a canonical path as returned by
     * {@link File#getCanonicalPath()}
     */
    public static final String EXTRA_TEMP_FILE_ROOT =
            "android.telephony.mbms.extra.TEMP_FILE_ROOT";

    /**
     * Extra containing a list of {@link Uri}s indicating temp files which the middleware is
     * still using.
     */
    public static final String EXTRA_TEMP_FILES_IN_USE =
            "android.telephony.mbms.extra.TEMP_FILES_IN_USE";

    /**
     * Extra containing an instance of {@link android.telephony.mbms.ServiceInfo}, used by
     * file-descriptor requests and cleanup requests to specify which service they want to
     * request temp files or clean up temp files for, respectively.
     */
    public static final String EXTRA_SERVICE_INFO =
            "android.telephony.mbms.extra.SERVICE_INFO";

    /**
     * Extra containing a single {@link Uri} indicating the location of the successfully
     * downloaded file. Set on the intent provided via
     * {@link android.telephony.mbms.DownloadRequest.Builder#setAppIntent(Intent)}.
     * Will always be set to a non-null value if {@link #EXTRA_RESULT} is set to
     * {@link #RESULT_SUCCESSFUL}.
     * TODO: Not systemapi.
     */
    public static final String EXTRA_COMPLETED_FILE_URI =
            "android.telephony.mbms.extra.COMPLETED_FILE_URI";

    public static final int RESULT_SUCCESSFUL = 1;
    public static final int RESULT_CANCELLED  = 2;
    public static final int RESULT_EXPIRED    = 3;
    // TODO - more results!

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATUS_UNKNOWN, STATUS_ACTIVELY_DOWNLOADING, STATUS_PENDING_DOWNLOAD,
            STATUS_PENDING_REPAIR, STATUS_PENDING_DOWNLOAD_WINDOW})
    public @interface DownloadStatus {}

    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_ACTIVELY_DOWNLOADING = 1;
    public static final int STATUS_PENDING_DOWNLOAD = 2;
    public static final int STATUS_PENDING_REPAIR = 3;
    public static final int STATUS_PENDING_DOWNLOAD_WINDOW = 4;

    private final Context mContext;
    private int mSubscriptionId = INVALID_SUBSCRIPTION_ID;

    private AtomicReference<IMbmsDownloadService> mService = new AtomicReference<>(null);
    private final IMbmsDownloadManagerCallback mCallback;

    private MbmsDownloadManager(Context context, IMbmsDownloadManagerCallback callback, int subId) {
        mContext = context;
        mCallback = callback;
        mSubscriptionId = subId;
    }

    /**
     * Create a new MbmsDownloadManager using the system default data subscription ID.
     * See {@link #create(Context, IMbmsDownloadManagerCallback, int)}
     *
     * @hide
     */
    public static MbmsDownloadManager create(Context context,
            IMbmsDownloadManagerCallback listener)
            throws MbmsException {
        return create(context, listener, SubscriptionManager.getDefaultSubscriptionId());
    }

    /**
     * Create a new MbmsDownloadManager using the given subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit. The instance of
     * {@link MbmsDownloadManager} that is returned will not be ready for use until
     * {@link IMbmsDownloadManagerCallback#middlewareReady()} is called on the provided callback.
     * If you attempt to use the manager before it is ready, a {@link MbmsException} will be thrown.
     *
     * This also may throw an {@link IllegalArgumentException} or a {@link MbmsException}.
     *
     * @param context The instance of {@link Context} to use
     * @param listener A callback to get asynchronous error messages and file service updates.
     * @param subscriptionId The data subscription ID to use
     * @hide
     */
    public static MbmsDownloadManager create(Context context,
            IMbmsDownloadManagerCallback listener, int subscriptionId)
            throws MbmsException {
        MbmsDownloadManager mdm = new MbmsDownloadManager(context, listener, subscriptionId);
        mdm.bindAndInitialize();
        return mdm;
    }

    private void bindAndInitialize() throws MbmsException {
        MbmsUtils.startBinding(mContext, MBMS_DOWNLOAD_SERVICE_ACTION,
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        IMbmsDownloadService downloadService =
                                IMbmsDownloadService.Stub.asInterface(service);
                        try {
                            downloadService.initialize(mSubscriptionId, mCallback);
                        } catch (RemoteException e) {
                            Log.e(LOG_TAG, "Service died before initialization");
                            return;
                        }
                        mService.set(downloadService);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        mService.set(null);
                    }
                });
    }

    /**
     * An inspection API to retrieve the list of available
     * {@link android.telephony.mbms.FileServiceInfo}s currently being advertised.
     * The results are returned asynchronously via a call to
     * {@link IMbmsDownloadManagerCallback#fileServicesUpdated(List)}
     *
     * The serviceClasses argument lets the app filter on types of programming and is opaque data
     * negotiated beforehand between the app and the carrier.
     *
     * This may throw an {@link MbmsException} containing one of the following errors:
     * {@link MbmsException#ERROR_MIDDLEWARE_NOT_BOUND}
     * {@link MbmsException#ERROR_MIDDLEWARE_LOST}
     *
     * Asynchronous error codes via the {@link MbmsDownloadManagerCallback#error(int, String)}
     * callback can include any of the errors except:
     * {@link MbmsException.StreamingErrors#ERROR_UNABLE_TO_START_SERVICE}
     *
     * @param classList A list of service classes which the app wishes to receive
     *                  {@link IMbmsDownloadManagerCallback#fileServicesUpdated(List)} callbacks
     *                  about. Subsequent calls to this method will replace this list of service
     *                  classes (i.e. the middleware will no longer send updates for services
     *                  matching classes only in the old list).
     */
    public void getFileServices(List<String> classList) throws MbmsException {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }
        try {
            int returnCode = downloadService.getFileServices(mSubscriptionId, classList);
            if (returnCode != MbmsException.SUCCESS) {
                throw new MbmsException(returnCode);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }
    }

    /**
     * Sets the temp file root for downloads.
     * All temp files created for the middleware to write to will be contained in the specified
     * directory. Applications that wish to specify a location only need to call this method once
     * as long their data is persisted in storage -- the argument will be stored both in a
     * local instance of {@link android.content.SharedPreferences} and by the middleware.
     *
     * If this method is not called at least once before calling
     * {@link #download(DownloadRequest, IDownloadCallback)}, the framework
     * will default to a directory formed by the concatenation of the app's files directory and
     * {@link android.telephony.mbms.MbmsTempFileProvider#DEFAULT_TOP_LEVEL_TEMP_DIRECTORY}.
     *
     * Before calling this method, the app must cancel all of its pending
     * {@link DownloadRequest}s via {@link #cancelDownload(DownloadRequest)}. If this is not done,
     * an {@link MbmsException} will be thrown with code
     * {@link MbmsException.DownloadErrors#ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT}
     *
     * The {@link File} supplied as a root temp file directory must already exist. If not, an
     * {@link IllegalArgumentException} will be thrown.
     * @param tempFileRootDirectory A directory to place temp files in.
     */
    public void setTempFileRootDirectory(@NonNull File tempFileRootDirectory)
            throws MbmsException {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }
        if (!tempFileRootDirectory.exists()) {
            throw new IllegalArgumentException("Provided directory does not exist");
        }
        if (!tempFileRootDirectory.isDirectory()) {
            throw new IllegalArgumentException("Provided File is not a directory");
        }
        String filePath;
        try {
            filePath = tempFileRootDirectory.getCanonicalPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to canonicalize the provided path: " + e);
        }

        try {
            int result = downloadService.setTempFileRootDirectory(mSubscriptionId, filePath);
            if (result != MbmsException.SUCCESS) {
                throw new MbmsException(result);
            }
        } catch (RemoteException e) {
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }

        SharedPreferences prefs = mContext.getSharedPreferences(
                MbmsTempFileProvider.TEMP_FILE_ROOT_PREF_FILE_NAME, 0);
        prefs.edit().putString(MbmsTempFileProvider.TEMP_FILE_ROOT_PREF_NAME, filePath).apply();
    }

    /**
     * Requests a download of a file that is available via multicast.
     *
     * downloadListener is an optional callback object which can be used to get progress reports
     *     of a currently occuring download.  Note this can only run while the calling app
     *     is running, so future downloads will simply result in resultIntents being sent
     *     for completed or errored-out downloads.  A NULL indicates no callbacks are needed.
     *
     * May throw an {@link IllegalArgumentException}
     *
     * If {@link #setTempFileRootDirectory(File)} has not called after the app has been installed,
     * this method will create a directory at the default location defined at
     * {@link MbmsTempFileProvider#DEFAULT_TOP_LEVEL_TEMP_DIRECTORY} and store that as the temp
     * file root directory.
     *
     * Asynchronous errors through the listener include any of the errors
     *
     * @param request The request that specifies what should be downloaded
     * @param progressListener Optional listener that will be provided progress updates
     *                         if the app is running.
     */
    public void download(DownloadRequest request, IDownloadProgressListener progressListener)
            throws MbmsException {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }

        // Check to see whether the app's set a temp root dir yet, and set it if not.
        SharedPreferences prefs = mContext.getSharedPreferences(
                MbmsTempFileProvider.TEMP_FILE_ROOT_PREF_FILE_NAME, 0);
        if (prefs.getString(MbmsTempFileProvider.TEMP_FILE_ROOT_PREF_NAME, null) == null) {
            File tempRootDirectory = new File(mContext.getFilesDir(),
                    MbmsTempFileProvider.DEFAULT_TOP_LEVEL_TEMP_DIRECTORY);
            tempRootDirectory.mkdirs();
            setTempFileRootDirectory(tempRootDirectory);
        }

        checkValidDownloadDestination(request);
        writeDownloadRequestToken(request);
        try {
            downloadService.download(request, progressListener);
        } catch (RemoteException e) {
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }
    }

    /**
     * Returns a list of pending {@link DownloadRequest}s that originated from this application.
     * A pending request is one that was issued via
     * {@link #download(DownloadRequest, IDownloadCallback)} but not cancelled through
     * {@link #cancelDownload(DownloadRequest)}.
     * @return A list, possibly empty, of {@link DownloadRequest}s
     */
    public @NonNull List<DownloadRequest> listPendingDownloads() throws MbmsException {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }

        try {
            return downloadService.listPendingDownloads(mSubscriptionId);
        } catch (RemoteException e) {
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }
    }

    /**
     * Attempts to cancel the specified {@link DownloadRequest}.
     *
     * If the middleware is not aware of the specified download request, an MbmsException will be
     * thrown with error code {@link MbmsException.DownloadErrors#ERROR_UNKNOWN_DOWNLOAD_REQUEST}.
     *
     * If this method returns without throwing an exception, you may assume that cancellation
     * was successful.
     * @param downloadRequest The download request that you wish to cancel.
     */
    public void cancelDownload(DownloadRequest downloadRequest) throws MbmsException {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }

        try {
            int result = downloadService.cancelDownload(downloadRequest);
            if (result != MbmsException.SUCCESS) {
                throw new MbmsException(result);
            }
        } catch (RemoteException e) {
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }
        deleteDownloadRequestToken(downloadRequest);
    }

    /**
     * Gets information about the status of a file pending download.
     *
     * If the middleware has not yet been properly initialized or if it has no records of the
     * file indicated by {@code fileInfo} being associated with {@code downloadRequest},
     * {@link #STATUS_UNKNOWN} will be returned.
     *
     * @param downloadRequest The download request to query.
     * @param fileInfo The particular file within the request to get information on.
     * @return The status of the download.
     */
    @DownloadStatus
    public int getDownloadStatus(DownloadRequest downloadRequest, FileInfo fileInfo)
            throws MbmsException {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }

        try {
            return downloadService.getDownloadStatus(downloadRequest, fileInfo);
        } catch (RemoteException e) {
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }
    }

    /**
     * Resets the middleware's knowledge of previously-downloaded files in this download request.
     *
     * Normally, the middleware keeps track of the hashes of downloaded files and won't re-download
     * files whose server-reported hash matches one of the already-downloaded files. This means
     * that if the file is accidentally deleted by the user or by the app, the middleware will
     * not try to download it again.
     * This method will reset the middleware's cache of hashes for the provided
     * {@link DownloadRequest}, so that previously downloaded content will be downloaded again
     * when available.
     * This will not interrupt in-progress downloads.
     *
     * If the middleware is not aware of the specified download request, an MbmsException will be
     * thrown with error code {@link MbmsException.DownloadErrors#ERROR_UNKNOWN_DOWNLOAD_REQUEST}.
     *
     * May throw a {@link MbmsException} with error code
     * @param downloadRequest The request to re-download files for.
     */
    public void resetDownloadKnowledge(DownloadRequest downloadRequest) throws MbmsException {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }

        try {
            int result = downloadService.resetDownloadKnowledge(downloadRequest);
            if (result != MbmsException.SUCCESS) {
                throw new MbmsException(result);
            }
        } catch (RemoteException e) {
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }
    }

    public void dispose() {
        try {
            IMbmsDownloadService downloadService = mService.get();
            if (downloadService == null) {
                Log.i(LOG_TAG, "Service already dead");
                return;
            }
            downloadService.dispose(mSubscriptionId);
            mService.set(null);
        } catch (RemoteException e) {
            // Ignore
            Log.i(LOG_TAG, "Remote exception while disposing of service");
        }
    }

    /**
     * Retrieves the {@link ComponentName} for the {@link android.content.BroadcastReceiver} that
     * the various intents from the middleware should be targeted towards.
     * @param uid The uid of the frontend app.
     * @return The component name of the receiver that the middleware should send its intents to,
     * or null if the app didn't declare it in the manifest.
     *
     * @hide
     * future systemapi
     */
    public static ComponentName getAppReceiverFromUid(Context context, int uid) {
        String[] packageNames = context.getPackageManager().getPackagesForUid(uid);
        if (packageNames == null) {
            return null;
        }

        for (String packageName : packageNames) {
            ComponentName candidate = new ComponentName(packageName,
                    MbmsDownloadReceiver.class.getCanonicalName());
            Intent queryIntent = new Intent();
            queryIntent.setComponent(candidate);
            List<ResolveInfo> receivers =
                    context.getPackageManager().queryBroadcastReceivers(queryIntent, 0);
            if (receivers != null && receivers.size() > 0) {
                return candidate;
            }
        }
        return null;
    }

    private void writeDownloadRequestToken(DownloadRequest request) {
        File token = getDownloadRequestTokenPath(request);
        if (!token.getParentFile().exists()) {
            token.getParentFile().mkdirs();
        }
        if (token.exists()) {
            Log.w(LOG_TAG, "Download token " + token.getName() + " already exists");
            return;
        }
        try {
            if (!token.createNewFile()) {
                throw new RuntimeException("Failed to create download token for request "
                        + request);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create download token for request " + request
                    + " due to IOException " + e);
        }
    }

    private void deleteDownloadRequestToken(DownloadRequest request) {
        File token = getDownloadRequestTokenPath(request);
        if (!token.isFile()) {
            Log.w(LOG_TAG, "Attempting to delete non-existent download token at " + token);
            return;
        }
        if (!token.delete()) {
            Log.w(LOG_TAG, "Couldn't delete download token at " + token);
        }
    }

    private File getDownloadRequestTokenPath(DownloadRequest request) {
        File tempFileLocation = MbmsUtils.getEmbmsTempFileDirForService(mContext,
                request.getFileServiceId());
        String downloadTokenFileName = request.getHash()
                + MbmsDownloadReceiver.DOWNLOAD_TOKEN_SUFFIX;
        return new File(tempFileLocation, downloadTokenFileName);
    }

    /**
     * Verifies the following:
     * If a request is multi-part,
     *     1. Destination Uri must exist and be a directory
     *     2. Directory specified must contain no files.
     * Otherwise
     *     1. The file specified by the destination Uri must not exist.
     */
    private void checkValidDownloadDestination(DownloadRequest request) {
        File toFile = new File(request.getDestinationUri().getSchemeSpecificPart());
        if (request.isMultipartDownload()) {
            if (!toFile.isDirectory()) {
                throw new IllegalArgumentException("Multipart download must specify valid " +
                        "destination directory.");
            }
            if (toFile.listFiles().length > 0) {
                throw new IllegalArgumentException("Destination directory must be clear of all " +
                        "files.");
            }
        } else {
            if (toFile.exists()) {
                throw new IllegalArgumentException("Destination file must not exist.");
            }
        }
    }
}
