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
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.DownloadStateCallback;
import android.telephony.mbms.FileInfo;
import android.telephony.mbms.DownloadRequest;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

/**
 * This class provides functionality for file download over MBMS.
 * @hide
 */
public class MbmsDownloadManager {
    private static final String LOG_TAG = MbmsDownloadManager.class.getSimpleName();

    /**
     * Service action which must be handled by the middleware implementing the MBMS file download
     * interface.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String MBMS_DOWNLOAD_SERVICE_ACTION =
            "android.telephony.action.EmbmsDownload";

    /**
     * Integer extra that Android will attach to the intent supplied via
     * {@link android.telephony.mbms.DownloadRequest.Builder#setAppIntent(Intent)}
     * Indicates the result code of the download. One of
     * {@link #RESULT_SUCCESSFUL}, {@link #RESULT_EXPIRED}, {@link #RESULT_CANCELLED}, or
     * {@link #RESULT_IO_ERROR}.
     *
     * This extra may also be used by the middleware when it is sending intents to the app.
     */
    public static final String EXTRA_RESULT = "android.telephony.mbms.extra.RESULT";

    /**
     * {@link FileInfo} extra that Android will attach to the intent supplied via
     * {@link android.telephony.mbms.DownloadRequest.Builder#setAppIntent(Intent)}
     * Indicates the file for which the download result is for. Never null.
     *
     * This extra may also be used by the middleware when it is sending intents to the app.
     */
    public static final String EXTRA_FILE_INFO = "android.telephony.mbms.extra.FILE_INFO";

    /**
     * {@link Uri} extra that Android will attach to the intent supplied via
     * {@link android.telephony.mbms.DownloadRequest.Builder#setAppIntent(Intent)}
     * Indicates the location of the successfully
     * downloaded file. Will always be set to a non-null value if {@link #EXTRA_RESULT} is set to
     * {@link #RESULT_SUCCESSFUL}.
     */
    public static final String EXTRA_COMPLETED_FILE_URI =
            "android.telephony.mbms.extra.COMPLETED_FILE_URI";

    /**
     * The default directory name for all MBMS temp files. If you call
     * {@link #download(DownloadRequest, DownloadStateCallback)} without first calling
     * {@link #setTempFileRootDirectory(File)}, this directory will be created for you under the
     * path returned by {@link Context#getFilesDir()}.
     */
    public static final String DEFAULT_TOP_LEVEL_TEMP_DIRECTORY = "androidMbmsTempFileRoot";

    /**
     * Indicates that the download was successful.
     */
    public static final int RESULT_SUCCESSFUL = 1;

    /**
     * Indicates that the download was cancelled via {@link #cancelDownload(DownloadRequest)}.
     */
    public static final int RESULT_CANCELLED = 2;

    /**
     * Indicates that the download will not be completed due to the expiration of its download
     * window on the carrier's network.
     */
    public static final int RESULT_EXPIRED = 3;

    /**
     * Indicates that the download will not be completed due to an I/O error incurred while
     * writing to temp files. This commonly indicates that the device is out of storage space,
     * but may indicate other conditions as well (such as an SD card being removed).
     */
    public static final int RESULT_IO_ERROR = 4;
    // TODO - more results!

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATUS_UNKNOWN, STATUS_ACTIVELY_DOWNLOADING, STATUS_PENDING_DOWNLOAD,
            STATUS_PENDING_REPAIR, STATUS_PENDING_DOWNLOAD_WINDOW})
    public @interface DownloadStatus {}

    /**
     * Indicates that the middleware has no information on the file.
     */
    public static final int STATUS_UNKNOWN = 0;

    /**
     * Indicates that the file is actively downloading.
     */
    public static final int STATUS_ACTIVELY_DOWNLOADING = 1;

    /**
     * TODO: I don't know...
     */
    public static final int STATUS_PENDING_DOWNLOAD = 2;

    /**
     * Indicates that the file is being repaired after the download being interrupted.
     */
    public static final int STATUS_PENDING_REPAIR = 3;

    /**
     * Indicates that the file is waiting to download because its download window has not yet
     * started.
     */
    public static final int STATUS_PENDING_DOWNLOAD_WINDOW = 4;

    private static AtomicBoolean sIsInitialized = new AtomicBoolean(false);

    private final Context mContext;
    private int mSubscriptionId = INVALID_SUBSCRIPTION_ID;
    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            sendErrorToApp(MbmsException.ERROR_MIDDLEWARE_LOST, "Received death notification");
        }
    };

    private AtomicReference<IMbmsDownloadService> mService = new AtomicReference<>(null);
    private final MbmsDownloadManagerCallback mCallback;

    private MbmsDownloadManager(Context context, MbmsDownloadManagerCallback callback, int subId) {
        mContext = context;
        mCallback = callback;
        mSubscriptionId = subId;
    }

    /**
     * Create a new MbmsDownloadManager using the system default data subscription ID.
     * See {@link #create(Context, MbmsDownloadManagerCallback, int)}
     *
     * @hide
     */
    public static MbmsDownloadManager create(Context context,
            MbmsDownloadManagerCallback listener)
            throws MbmsException {
        return create(context, listener, SubscriptionManager.getDefaultSubscriptionId());
    }

    /**
     * Create a new MbmsDownloadManager using the given subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit. The instance of
     * {@link MbmsDownloadManager} that is returned will not be ready for use until
     * {@link MbmsDownloadManagerCallback#middlewareReady()} is called on the provided callback.
     * If you attempt to use the manager before it is ready, a {@link MbmsException} will be thrown.
     *
     * This also may throw an {@link IllegalArgumentException} or an {@link IllegalStateException}.
     *
     * You may only have one instance of {@link MbmsDownloadManager} per UID. If you call this
     * method while there is an active instance of {@link MbmsDownloadManager} in your process
     * (in other words, one that has not had {@link #dispose()} called on it), this method will
     * throw an {@link MbmsException}. If you call this method in a different process
     * running under the same UID, an error will be indicated via
     * {@link MbmsDownloadManagerCallback#error(int, String)}.
     *
     * Note that initialization may fail asynchronously. If you wish to try again after you
     * receive such an asynchronous error, you must call dispose() on the instance of
     * {@link MbmsDownloadManager} that you received before calling this method again.
     *
     * @param context The instance of {@link Context} to use
     * @param listener A callback to get asynchronous error messages and file service updates.
     * @param subscriptionId The data subscription ID to use
     * @hide
     */
    public static MbmsDownloadManager create(Context context,
            MbmsDownloadManagerCallback listener, int subscriptionId)
            throws MbmsException {
        if (!sIsInitialized.compareAndSet(false, true)) {
            throw new MbmsException(MbmsException.InitializationErrors.ERROR_DUPLICATE_INITIALIZE);
        }
        MbmsDownloadManager mdm = new MbmsDownloadManager(context, listener, subscriptionId);
        try {
            mdm.bindAndInitialize();
        } catch (MbmsException e) {
            sIsInitialized.set(false);
            throw e;
        }
        return mdm;
    }

    private void bindAndInitialize() throws MbmsException {
        MbmsUtils.startBinding(mContext, MBMS_DOWNLOAD_SERVICE_ACTION,
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        IMbmsDownloadService downloadService =
                                IMbmsDownloadService.Stub.asInterface(service);
                        int result;
                        try {
                            result = downloadService.initialize(mSubscriptionId, mCallback);
                        } catch (RemoteException e) {
                            Log.e(LOG_TAG, "Service died before initialization");
                            sIsInitialized.set(false);
                            return;
                        } catch (RuntimeException e) {
                            Log.e(LOG_TAG, "Runtime exception during initialization");
                            sendErrorToApp(
                                    MbmsException.InitializationErrors.ERROR_UNABLE_TO_INITIALIZE,
                                    e.toString());
                            sIsInitialized.set(false);
                            return;
                        }
                        if (result != MbmsException.SUCCESS) {
                            sendErrorToApp(result, "Error returned during initialization");
                            sIsInitialized.set(false);
                            return;
                        }
                        try {
                            downloadService.asBinder().linkToDeath(mDeathRecipient, 0);
                        } catch (RemoteException e) {
                            sendErrorToApp(MbmsException.ERROR_MIDDLEWARE_LOST,
                                    "Middleware lost during initialization");
                            sIsInitialized.set(false);
                            return;
                        }
                        mService.set(downloadService);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        sIsInitialized.set(false);
                        mService.set(null);
                    }
                });
    }

    /**
     * An inspection API to retrieve the list of available
     * {@link android.telephony.mbms.FileServiceInfo}s currently being advertised.
     * The results are returned asynchronously via a call to
     * {@link MbmsDownloadManagerCallback#fileServicesUpdated(List)}
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
     *                  {@link MbmsDownloadManagerCallback#fileServicesUpdated(List)} callbacks
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
     * {@link #download(DownloadRequest, DownloadStateCallback)}, the framework
     * will default to a directory formed by the concatenation of the app's files directory and
     * {@link MbmsDownloadManager#DEFAULT_TOP_LEVEL_TEMP_DIRECTORY}.
     *
     * Before calling this method, the app must cancel all of its pending
     * {@link DownloadRequest}s via {@link #cancelDownload(DownloadRequest)}. If this is not done,
     * an {@link MbmsException} will be thrown with code
     * {@link MbmsException.DownloadErrors#ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT} unless the
     * provided directory is the same as what has been previously configured.
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
     * Retrieves the currently configured temp file root directory. Returns the file that was
     * configured via {@link #setTempFileRootDirectory(File)} or the default directory
     * {@link #download(DownloadRequest, DownloadStateCallback)} was called without ever setting
     * the temp file root. If neither method has been called since the last time the app's shared
     * preferences were reset, returns null.
     *
     * @return A {@link File} pointing to the configured temp file directory, or null if not yet
     *         configured.
     */
    public @Nullable File getTempFileRootDirectory() {
        SharedPreferences prefs = mContext.getSharedPreferences(
                MbmsTempFileProvider.TEMP_FILE_ROOT_PREF_FILE_NAME, 0);
        String path = prefs.getString(MbmsTempFileProvider.TEMP_FILE_ROOT_PREF_NAME, null);
        if (path != null) {
            return new File(path);
        }
        return null;
    }

    /**
     * Requests a download of a file that is available via multicast.
     *
     * May throw an {@link IllegalArgumentException}
     *
     * If {@link #setTempFileRootDirectory(File)} has not called after the app has been installed,
     * this method will create a directory at the default location defined at
     * {@link MbmsDownloadManager#DEFAULT_TOP_LEVEL_TEMP_DIRECTORY} and store that as the temp
     * file root directory.
     *
     * Asynchronous errors through the listener include any of the errors
     *
     * @param request The request that specifies what should be downloaded
     * @param progressListener Optional listener that will be provided progress updates
     *                         if the app is running. If {@code null}, no callbacks will be
     *                         provided.
     */
    public void download(DownloadRequest request, @Nullable DownloadStateCallback progressListener)
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
                    DEFAULT_TOP_LEVEL_TEMP_DIRECTORY);
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
     * {@link #download(DownloadRequest, DownloadStateCallback)} but not cancelled through
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
        } catch (RemoteException e) {
            // Ignore
            Log.i(LOG_TAG, "Remote exception while disposing of service");
        } finally {
            mService.set(null);
            sIsInitialized.set(false);
        }
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

    private void sendErrorToApp(int errorCode, String message) {
        try {
            mCallback.error(errorCode, message);
        } catch (RemoteException e) {
            // Ignore, should not happen locally.
        }
    }
}
