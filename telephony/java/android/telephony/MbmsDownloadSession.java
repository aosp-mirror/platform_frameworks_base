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

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.DownloadProgressListener;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.DownloadStatusListener;
import android.telephony.mbms.FileInfo;
import android.telephony.mbms.InternalDownloadProgressListener;
import android.telephony.mbms.InternalDownloadSessionCallback;
import android.telephony.mbms.InternalDownloadStatusListener;
import android.telephony.mbms.MbmsDownloadReceiver;
import android.telephony.mbms.MbmsDownloadSessionCallback;
import android.telephony.mbms.MbmsErrors;
import android.telephony.mbms.MbmsTempFileProvider;
import android.telephony.mbms.MbmsUtils;
import android.telephony.mbms.vendor.IMbmsDownloadService;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class provides functionality for file download over MBMS.
 */
public class MbmsDownloadSession implements AutoCloseable {
    private static final String LOG_TAG = MbmsDownloadSession.class.getSimpleName();

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
     * Metadata key that specifies the component name of the service to bind to for file-download.
     * @hide
     */
    @TestApi
    public static final String MBMS_DOWNLOAD_SERVICE_OVERRIDE_METADATA =
            "mbms-download-service-override";

    /**
     * Integer extra that Android will attach to the intent supplied via
     * {@link android.telephony.mbms.DownloadRequest.Builder#setAppIntent(Intent)}
     * Indicates the result code of the download. One of
     * {@link #RESULT_SUCCESSFUL}, {@link #RESULT_EXPIRED}, {@link #RESULT_CANCELLED},
     * {@link #RESULT_IO_ERROR}, {@link #RESULT_DOWNLOAD_FAILURE}, {@link #RESULT_OUT_OF_STORAGE},
     * {@link #RESULT_SERVICE_ID_NOT_DEFINED}, or {@link #RESULT_FILE_ROOT_UNREACHABLE}.
     *
     * This extra may also be used by the middleware when it is sending intents to the app.
     */
    public static final String EXTRA_MBMS_DOWNLOAD_RESULT =
            "android.telephony.extra.MBMS_DOWNLOAD_RESULT";

    /**
     * {@link FileInfo} extra that Android will attach to the intent supplied via
     * {@link android.telephony.mbms.DownloadRequest.Builder#setAppIntent(Intent)}
     * Indicates the file for which the download result is for. Never null.
     *
     * This extra may also be used by the middleware when it is sending intents to the app.
     */
    public static final String EXTRA_MBMS_FILE_INFO = "android.telephony.extra.MBMS_FILE_INFO";

    /**
     * {@link Uri} extra that Android will attach to the intent supplied via
     * {@link android.telephony.mbms.DownloadRequest.Builder#setAppIntent(Intent)}
     * Indicates the location of the successfully downloaded file within the directory that the
     * app provided via the builder.
     *
     * Will always be set to a non-null value if
     * {@link #EXTRA_MBMS_DOWNLOAD_RESULT} is set to {@link #RESULT_SUCCESSFUL}.
     */
    public static final String EXTRA_MBMS_COMPLETED_FILE_URI =
            "android.telephony.extra.MBMS_COMPLETED_FILE_URI";

    /**
     * Extra containing the {@link DownloadRequest} for which the download result or file
     * descriptor request is for. Must not be null.
     */
    public static final String EXTRA_MBMS_DOWNLOAD_REQUEST =
            "android.telephony.extra.MBMS_DOWNLOAD_REQUEST";

    /**
     * The default directory name for all MBMS temp files. If you call
     * {@link #download(DownloadRequest)} without first calling
     * {@link #setTempFileRootDirectory(File)}, this directory will be created for you under the
     * path returned by {@link Context#getFilesDir()}.
     */
    public static final String DEFAULT_TOP_LEVEL_TEMP_DIRECTORY = "androidMbmsTempFileRoot";


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {RESULT_SUCCESSFUL, RESULT_CANCELLED, RESULT_EXPIRED, RESULT_IO_ERROR,
            RESULT_SERVICE_ID_NOT_DEFINED, RESULT_DOWNLOAD_FAILURE, RESULT_OUT_OF_STORAGE,
            RESULT_FILE_ROOT_UNREACHABLE}, prefix = { "RESULT_" })
    public @interface DownloadResultCode{}

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
     * writing to temp files.
     *
     * This is likely a transient error and another {@link DownloadRequest} should be sent to try
     * the download again.
     */
    public static final int RESULT_IO_ERROR = 4;

    /**
     * Indicates that the Service ID specified in the {@link DownloadRequest} is incorrect due to
     * the Id being incorrect, stale, expired, or similar.
     */
    public static final int RESULT_SERVICE_ID_NOT_DEFINED = 5;

    /**
     * Indicates that there was an error while processing downloaded files, such as a file repair or
     * file decoding error and is not due to a file I/O error.
     *
     * This is likely a transient error and another {@link DownloadRequest} should be sent to try
     * the download again.
     */
    public static final int RESULT_DOWNLOAD_FAILURE = 6;

    /**
     * Indicates that the file system is full and the {@link DownloadRequest} can not complete.
     * Either space must be made on the current file system or the temp file root location must be
     * changed to a location that is not full to download the temp files.
     */
    public static final int RESULT_OUT_OF_STORAGE = 7;

    /**
     * Indicates that the file root that was set is currently unreachable. This can happen if the
     * temp files are set to be stored on external storage and the SD card was removed, for example.
     * The temp file root should be changed before sending another DownloadRequest.
     */
    public static final int RESULT_FILE_ROOT_UNREACHABLE = 8;

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
     * Indicates that the file is actively being downloaded.
     */
    public static final int STATUS_ACTIVELY_DOWNLOADING = 1;

    /**
     * Indicates that the file is awaiting the next download or repair operations. When a more
     * precise status is known, the status will change to either {@link #STATUS_PENDING_REPAIR} or
     * {@link #STATUS_PENDING_DOWNLOAD_WINDOW}.
     */
    public static final int STATUS_PENDING_DOWNLOAD = 2;

    /**
     * Indicates that the file is awaiting file repair after the download has ended.
     */
    public static final int STATUS_PENDING_REPAIR = 3;

    /**
     * Indicates that the file is waiting to download because its download window has not yet
     * started and is scheduled for a future time.
     */
    public static final int STATUS_PENDING_DOWNLOAD_WINDOW = 4;

    private static final String DESTINATION_SANITY_CHECK_FILE_NAME = "destinationSanityCheckFile";

    private static final int MAX_SERVICE_ANNOUNCEMENT_SIZE = 10 * 1024; // 10KB

    private static AtomicBoolean sIsInitialized = new AtomicBoolean(false);

    private final Context mContext;
    private int mSubscriptionId = INVALID_SUBSCRIPTION_ID;
    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, "Received death notification");
        }
    };

    private AtomicReference<IMbmsDownloadService> mService = new AtomicReference<>(null);
    private ServiceConnection mServiceConnection;
    private final InternalDownloadSessionCallback mInternalCallback;
    private final Map<DownloadStatusListener, InternalDownloadStatusListener>
            mInternalDownloadStatusListeners = new HashMap<>();
    private final Map<DownloadProgressListener, InternalDownloadProgressListener>
            mInternalDownloadProgressListeners = new HashMap<>();

    private MbmsDownloadSession(Context context, Executor executor, int subscriptionId,
            MbmsDownloadSessionCallback callback) {
        mContext = context;
        mSubscriptionId = subscriptionId;
        mInternalCallback = new InternalDownloadSessionCallback(callback, executor);
    }

    /**
     * Create a new {@link MbmsDownloadSession} using the system default data subscription ID.
     * See {@link #create(Context, Executor, int, MbmsDownloadSessionCallback)}
     */
    public static MbmsDownloadSession create(@NonNull Context context,
            @NonNull Executor executor, @NonNull MbmsDownloadSessionCallback callback) {
        return create(context, executor, SubscriptionManager.getDefaultSubscriptionId(), callback);
    }

    /**
     * Create a new MbmsDownloadManager using the given subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit. The instance of
     * {@link MbmsDownloadSession} that is returned will not be ready for use until
     * {@link MbmsDownloadSessionCallback#onMiddlewareReady()} is called on the provided callback.
     * If you attempt to use the instance before it is ready, an {@link IllegalStateException}
     * will be thrown or an error will be delivered through
     * {@link MbmsDownloadSessionCallback#onError(int, String)}.
     *
     * This also may throw an {@link IllegalArgumentException}.
     *
     * You may only have one instance of {@link MbmsDownloadSession} per UID. If you call this
     * method while there is an active instance of {@link MbmsDownloadSession} in your process
     * (in other words, one that has not had {@link #close()} called on it), this method will
     * throw an {@link IllegalStateException}. If you call this method in a different process
     * running under the same UID, an error will be indicated via
     * {@link MbmsDownloadSessionCallback#onError(int, String)}.
     *
     * Note that initialization may fail asynchronously. If you wish to try again after you
     * receive such an asynchronous error, you must call {@link #close()} on the instance of
     * {@link MbmsDownloadSession} that you received before calling this method again.
     *
     * @param context The instance of {@link Context} to use
     * @param executor The executor on which you wish to execute callbacks.
     * @param subscriptionId The data subscription ID to use
     * @param callback A callback to get asynchronous error messages and file service updates.
     * @return A new instance of {@link MbmsDownloadSession}, or null if an error occurred during
     * setup.
     */
    public static @Nullable MbmsDownloadSession create(@NonNull Context context,
            @NonNull Executor executor, int subscriptionId,
            final @NonNull MbmsDownloadSessionCallback callback) {
        if (!sIsInitialized.compareAndSet(false, true)) {
            throw new IllegalStateException("Cannot have two active instances");
        }
        MbmsDownloadSession session =
                new MbmsDownloadSession(context, executor, subscriptionId, callback);
        final int result = session.bindAndInitialize();
        if (result != MbmsErrors.SUCCESS) {
            sIsInitialized.set(false);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onError(result, null);
                }
            });
            return null;
        }
        return session;
    }

    /**
     * Returns the maximum size of the service announcement descriptor that can be provided via
     * {@link #addServiceAnnouncement}
     * @return The maximum length of the byte array passed as an argument to
     *         {@link #addServiceAnnouncement}.
     */
    public static int getMaximumServiceAnnouncementSize() {
        return MAX_SERVICE_ANNOUNCEMENT_SIZE;
    }

    private int bindAndInitialize() {
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                IMbmsDownloadService downloadService =
                        IMbmsDownloadService.Stub.asInterface(service);
                int result;
                try {
                    result = downloadService.initialize(mSubscriptionId, mInternalCallback);
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Service died before initialization");
                    sIsInitialized.set(false);
                    return;
                } catch (RuntimeException e) {
                    Log.e(LOG_TAG, "Runtime exception during initialization");
                    sendErrorToApp(
                            MbmsErrors.InitializationErrors.ERROR_UNABLE_TO_INITIALIZE,
                            e.toString());
                    sIsInitialized.set(false);
                    return;
                }
                if (result == MbmsErrors.UNKNOWN) {
                    // Unbind and throw an obvious error
                    close();
                    throw new IllegalStateException("Middleware must not return an"
                            + " unknown error code");
                }
                if (result != MbmsErrors.SUCCESS) {
                    sendErrorToApp(result, "Error returned during initialization");
                    sIsInitialized.set(false);
                    return;
                }
                try {
                    downloadService.asBinder().linkToDeath(mDeathRecipient, 0);
                } catch (RemoteException e) {
                    sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST,
                            "Middleware lost during initialization");
                    sIsInitialized.set(false);
                    return;
                }
                mService.set(downloadService);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(LOG_TAG, "bindAndInitialize: Remote service disconnected");
                sIsInitialized.set(false);
                mService.set(null);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Log.w(LOG_TAG, "bindAndInitialize: Remote service returned null");
                sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST,
                        "Middleware service binding returned null");
                sIsInitialized.set(false);
                mService.set(null);
                mContext.unbindService(this);
            }
        };
        return MbmsUtils.startBinding(mContext, MBMS_DOWNLOAD_SERVICE_ACTION, mServiceConnection);
    }

    /**
     * An inspection API to retrieve the list of available
     * {@link android.telephony.mbms.FileServiceInfo}s currently being advertised.
     * The results are returned asynchronously via a call to
     * {@link MbmsDownloadSessionCallback#onFileServicesUpdated(List)}
     *
     * Asynchronous error codes via the {@link MbmsDownloadSessionCallback#onError(int, String)}
     * callback may include any of the errors that are not specific to the streaming use-case.
     *
     * May throw an {@link IllegalStateException} or {@link IllegalArgumentException}.
     *
     * @param classList A list of service classes which the app wishes to receive
     *                  {@link MbmsDownloadSessionCallback#onFileServicesUpdated(List)} callbacks
     *                  about. Subsequent calls to this method will replace this list of service
     *                  classes (i.e. the middleware will no longer send updates for services
     *                  matching classes only in the old list).
     *                  Values in this list should be negotiated with the wireless carrier prior
     *                  to using this API.
     */
    public void requestUpdateFileServices(@NonNull List<String> classList) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }
        try {
            int returnCode = downloadService.requestUpdateFileServices(mSubscriptionId, classList);
            if (returnCode == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (returnCode != MbmsErrors.SUCCESS) {
                sendErrorToApp(returnCode, null);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
        }
    }

    /**
     * Inform the middleware of a service announcement descriptor received from a group
     * communication server.
     *
     * When participating in a group call via the {@link MbmsGroupCallSession} API, applications may
     * receive a service announcement descriptor from the group call server that informs them of
     * files that may be relevant to users communicating on the group call.
     *
     * After supplying the service announcement descriptor received from the server to the
     * middleware via this API, applications will receive information on the available files via
     * {@link MbmsDownloadSessionCallback#onFileServicesUpdated}, and the available files will be
     * downloadable via {@link MbmsDownloadSession#download} like other files published via
     * {@link MbmsDownloadSessionCallback#onFileServicesUpdated}.
     *
     * Asynchronous error codes via the {@link MbmsDownloadSessionCallback#onError(int, String)}
     * callback may include any of the errors that are not specific to the streaming use-case.
     *
     * May throw an {@link IllegalStateException} when the middleware has not yet been bound,
     * or an {@link IllegalArgumentException} if the byte array is too large, or an
     * {@link UnsupportedOperationException} if the middleware has not implemented this method.
     *
     * @param contents The contents of the service announcement descriptor received from the
     *                     group call server. If the size of this array is greater than the value of
     *                     {@link #getMaximumServiceAnnouncementSize()}, an
     *                     {@link IllegalArgumentException} will be thrown.
     */
    public void addServiceAnnouncement(@NonNull byte[] contents) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }

        if (contents.length > MAX_SERVICE_ANNOUNCEMENT_SIZE) {
            throw new IllegalArgumentException("File too large");
        }

        try {
            int returnCode = downloadService.addServiceAnnouncement(
                    mSubscriptionId, contents);
            if (returnCode == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (returnCode != MbmsErrors.SUCCESS) {
                sendErrorToApp(returnCode, null);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
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
     * {@link #download(DownloadRequest)}, the framework
     * will default to a directory formed by the concatenation of the app's files directory and
     * {@link MbmsDownloadSession#DEFAULT_TOP_LEVEL_TEMP_DIRECTORY}.
     *
     * Before calling this method, the app must cancel all of its pending
     * {@link DownloadRequest}s via {@link #cancelDownload(DownloadRequest)}. If this is not done,
     * you will receive an asynchronous error with code
     * {@link MbmsErrors.DownloadErrors#ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT} unless the
     * provided directory is the same as what has been previously configured.
     *
     * The {@link File} supplied as a root temp file directory must already exist. If not, an
     * {@link IllegalArgumentException} will be thrown. In addition, as an additional correctness
     * check, an {@link IllegalArgumentException} will be thrown if you attempt to set the temp
     * file root directory to one of your data roots (the value of {@link Context#getDataDir()},
     * {@link Context#getFilesDir()}, or {@link Context#getCacheDir()}).
     * @param tempFileRootDirectory A directory to place temp files in.
     */
    public void setTempFileRootDirectory(@NonNull File tempFileRootDirectory) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }
        try {
            validateTempFileRootSanity(tempFileRootDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Got IOException checking directory sanity");
        }
        String filePath;
        try {
            filePath = tempFileRootDirectory.getCanonicalPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to canonicalize the provided path: " + e);
        }

        try {
            int result = downloadService.setTempFileRootDirectory(mSubscriptionId, filePath);
            if (result == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (result != MbmsErrors.SUCCESS) {
                sendErrorToApp(result, null);
                return;
            }
        } catch (RemoteException e) {
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
            return;
        }

        SharedPreferences prefs = mContext.getSharedPreferences(
                MbmsTempFileProvider.TEMP_FILE_ROOT_PREF_FILE_NAME, 0);
        prefs.edit().putString(MbmsTempFileProvider.TEMP_FILE_ROOT_PREF_NAME, filePath).apply();
    }

    private void validateTempFileRootSanity(File tempFileRootDirectory) throws IOException {
        if (!tempFileRootDirectory.exists()) {
            throw new IllegalArgumentException("Provided directory does not exist");
        }
        if (!tempFileRootDirectory.isDirectory()) {
            throw new IllegalArgumentException("Provided File is not a directory");
        }
        String canonicalTempFilePath = tempFileRootDirectory.getCanonicalPath();
        if (mContext.getDataDir().getCanonicalPath().equals(canonicalTempFilePath)) {
            throw new IllegalArgumentException("Temp file root cannot be your data dir");
        }
        if (mContext.getCacheDir().getCanonicalPath().equals(canonicalTempFilePath)) {
            throw new IllegalArgumentException("Temp file root cannot be your cache dir");
        }
        if (mContext.getFilesDir().getCanonicalPath().equals(canonicalTempFilePath)) {
            throw new IllegalArgumentException("Temp file root cannot be your files dir");
        }
    }
    /**
     * Retrieves the currently configured temp file root directory. Returns the file that was
     * configured via {@link #setTempFileRootDirectory(File)} or the default directory
     * {@link #download(DownloadRequest)} was called without ever
     * setting the temp file root. If neither method has been called since the last time the app's
     * shared preferences were reset, returns {@code null}.
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
     * Requests the download of a file or set of files that the carrier has indicated to be
     * available.
     *
     * May throw an {@link IllegalArgumentException}
     *
     * If {@link #setTempFileRootDirectory(File)} has not called after the app has been installed,
     * this method will create a directory at the default location defined at
     * {@link MbmsDownloadSession#DEFAULT_TOP_LEVEL_TEMP_DIRECTORY} and store that as the temp
     * file root directory.
     *
     * If the {@link DownloadRequest} has a destination that is not on the same filesystem as the
     * temp file directory provided via {@link #getTempFileRootDirectory()}, an
     * {@link IllegalArgumentException} will be thrown.
     *
     * Asynchronous errors through the callback may include any error not specific to the
     * streaming use-case.
     *
     * If no error is delivered via the callback after calling this method, that means that the
     * middleware has successfully started the download or scheduled the download, if the download
     * is at a future time.
     * @param request The request that specifies what should be downloaded.
     */
    public void download(@NonNull DownloadRequest request) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
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

        checkDownloadRequestDestination(request);

        try {
            int result = downloadService.download(request);
            if (result == MbmsErrors.SUCCESS) {
                writeDownloadRequestToken(request);
            } else {
                if (result == MbmsErrors.UNKNOWN) {
                    // Unbind and throw an obvious error
                    close();
                    throw new IllegalStateException("Middleware must not return an unknown"
                            + " error code");
                }
                sendErrorToApp(result, null);
            }
        } catch (RemoteException e) {
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
        }
    }

    /**
     * Returns a list of pending {@link DownloadRequest}s that originated from this application.
     * A pending request is one that was issued via
     * {@link #download(DownloadRequest)} but not cancelled through
     * {@link #cancelDownload(DownloadRequest)}.
     * @return A list, possibly empty, of {@link DownloadRequest}s
     */
    public @NonNull List<DownloadRequest> listPendingDownloads() {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }

        try {
            return downloadService.listPendingDownloads(mSubscriptionId);
        } catch (RemoteException e) {
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
            return Collections.emptyList();
        }
    }

    /**
     * Registers a download status listener for a {@link DownloadRequest} previously requested via
     * {@link #download(DownloadRequest)}. This callback will only be called as long as both this
     * app and the middleware are both running -- if either one stops, no further calls on the
     * provided {@link DownloadStatusListener} will be enqueued.
     *
     * If the middleware is not aware of the specified download request,
     * this method will throw an {@link IllegalArgumentException}.
     *
     * If the operation encountered an error, the error code will be delivered via
     * {@link MbmsDownloadSessionCallback#onError}.
     *
     * Repeated calls to this method for the same {@link DownloadRequest} will replace the
     * previously registered listener.
     *
     * @param request The {@link DownloadRequest} that you want updates on.
     * @param executor The {@link Executor} on which calls to {@code listener } should be executed.
     * @param listener The listener that should be called when the middleware has information to
     *                 share on the status download.
     */
    public void addStatusListener(@NonNull DownloadRequest request,
            @NonNull Executor executor, @NonNull DownloadStatusListener listener) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }

        InternalDownloadStatusListener internalListener =
                new InternalDownloadStatusListener(listener, executor);

        try {
            int result = downloadService.addStatusListener(request, internalListener);
            if (result == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (result != MbmsErrors.SUCCESS) {
                if (result == MbmsErrors.DownloadErrors.ERROR_UNKNOWN_DOWNLOAD_REQUEST) {
                    throw new IllegalArgumentException("Unknown download request.");
                }
                sendErrorToApp(result, null);
                return;
            }
        } catch (RemoteException e) {
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
            return;
        }
        mInternalDownloadStatusListeners.put(listener, internalListener);
    }

    /**
     * Un-register a listener previously registered via
     * {@link #addStatusListener(DownloadRequest, Executor, DownloadStatusListener)}. After
     * this method is called, no further calls will be enqueued on the {@link Executor}
     * provided upon registration, even if this method throws an exception.
     *
     * If the middleware is not aware of the specified download request,
     * this method will throw an {@link IllegalArgumentException}.
     *
     * If the operation encountered an error, the error code will be delivered via
     * {@link MbmsDownloadSessionCallback#onError}.
     *
     * @param request The {@link DownloadRequest} provided during registration
     * @param listener The listener provided during registration.
     */
    public void removeStatusListener(@NonNull DownloadRequest request,
            @NonNull DownloadStatusListener listener) {
        try {
            IMbmsDownloadService downloadService = mService.get();
            if (downloadService == null) {
                throw new IllegalStateException("Middleware not yet bound");
            }

            InternalDownloadStatusListener internalListener =
                    mInternalDownloadStatusListeners.get(listener);
            if (internalListener == null) {
                throw new IllegalArgumentException("Provided listener was never registered");
            }

            try {
                int result = downloadService.removeStatusListener(request, internalListener);
                if (result == MbmsErrors.UNKNOWN) {
                    // Unbind and throw an obvious error
                    close();
                    throw new IllegalStateException("Middleware must not return an"
                            + " unknown error code");
                }
                if (result != MbmsErrors.SUCCESS) {
                    if (result == MbmsErrors.DownloadErrors.ERROR_UNKNOWN_DOWNLOAD_REQUEST) {
                        throw new IllegalArgumentException("Unknown download request.");
                    }
                    sendErrorToApp(result, null);
                    return;
                }
            } catch (RemoteException e) {
                mService.set(null);
                sIsInitialized.set(false);
                sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
                return;
            }
        } finally {
            InternalDownloadStatusListener internalCallback =
                    mInternalDownloadStatusListeners.remove(listener);
            if (internalCallback != null) {
                internalCallback.stop();
            }
        }
    }

    /**
     * Registers a progress listener for a {@link DownloadRequest} previously requested via
     * {@link #download(DownloadRequest)}. This listener will only be called as long as both this
     * app and the middleware are both running -- if either one stops, no further calls on the
     * provided {@link DownloadProgressListener} will be enqueued.
     *
     * If the middleware is not aware of the specified download request,
     * this method will throw an {@link IllegalArgumentException}.
     *
     * If the operation encountered an error, the error code will be delivered via
     * {@link MbmsDownloadSessionCallback#onError}.
     *
     * Repeated calls to this method for the same {@link DownloadRequest} will replace the
     * previously registered listener.
     *
     * @param request The {@link DownloadRequest} that you want updates on.
     * @param executor The {@link Executor} on which calls to {@code listener} should be executed.
     * @param listener The listener that should be called when the middleware has information to
     *                 share on the progress of the download.
     */
    public void addProgressListener(@NonNull DownloadRequest request,
            @NonNull Executor executor, @NonNull DownloadProgressListener listener) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }

        InternalDownloadProgressListener internalListener =
                new InternalDownloadProgressListener(listener, executor);

        try {
            int result = downloadService.addProgressListener(request, internalListener);
            if (result == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (result != MbmsErrors.SUCCESS) {
                if (result == MbmsErrors.DownloadErrors.ERROR_UNKNOWN_DOWNLOAD_REQUEST) {
                    throw new IllegalArgumentException("Unknown download request.");
                }
                sendErrorToApp(result, null);
                return;
            }
        } catch (RemoteException e) {
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
            return;
        }
        mInternalDownloadProgressListeners.put(listener, internalListener);
    }

    /**
     * Un-register a listener previously registered via
     * {@link #addProgressListener(DownloadRequest, Executor, DownloadProgressListener)}. After
     * this method is called, no further callbacks will be enqueued on the {@link Handler}
     * provided upon registration, even if this method throws an exception.
     *
     * If the middleware is not aware of the specified download request,
     * this method will throw an {@link IllegalArgumentException}.
     *
     * If the operation encountered an error, the error code will be delivered via
     * {@link MbmsDownloadSessionCallback#onError}.
     *
     * @param request The {@link DownloadRequest} provided during registration
     * @param listener The listener provided during registration.
     */
    public void removeProgressListener(@NonNull DownloadRequest request,
            @NonNull DownloadProgressListener listener) {
        try {
            IMbmsDownloadService downloadService = mService.get();
            if (downloadService == null) {
                throw new IllegalStateException("Middleware not yet bound");
            }

            InternalDownloadProgressListener internalListener =
                    mInternalDownloadProgressListeners.get(listener);
            if (internalListener == null) {
                throw new IllegalArgumentException("Provided listener was never registered");
            }

            try {
                int result = downloadService.removeProgressListener(request, internalListener);
                if (result == MbmsErrors.UNKNOWN) {
                    // Unbind and throw an obvious error
                    close();
                    throw new IllegalStateException("Middleware must not"
                            + " return an unknown error code");
                }
                if (result != MbmsErrors.SUCCESS) {
                    if (result == MbmsErrors.DownloadErrors.ERROR_UNKNOWN_DOWNLOAD_REQUEST) {
                        throw new IllegalArgumentException("Unknown download request.");
                    }
                    sendErrorToApp(result, null);
                    return;
                }
            } catch (RemoteException e) {
                mService.set(null);
                sIsInitialized.set(false);
                sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
                return;
            }
        } finally {
            InternalDownloadProgressListener internalCallback =
                    mInternalDownloadProgressListeners.remove(listener);
            if (internalCallback != null) {
                internalCallback.stop();
            }
        }
    }

    /**
     * Attempts to cancel the specified {@link DownloadRequest}.
     *
     * If the operation encountered an error, the error code will be delivered via
     * {@link MbmsDownloadSessionCallback#onError}.
     *
     * @param downloadRequest The download request that you wish to cancel.
     */
    public void cancelDownload(@NonNull DownloadRequest downloadRequest) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }

        try {
            int result = downloadService.cancelDownload(downloadRequest);
            if (result == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (result != MbmsErrors.SUCCESS) {
                sendErrorToApp(result, null);
            } else {
                deleteDownloadRequestToken(downloadRequest);
            }
        } catch (RemoteException e) {
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
        }
    }

    /**
     * Requests information about the state of a file pending download.
     *
     * The state will be delivered as a callback via
     * {@link DownloadStatusListener#onStatusUpdated(DownloadRequest, FileInfo, int)}. If no such
     * callback has been registered via
     * {@link #addProgressListener(DownloadRequest, Executor, DownloadProgressListener)}, this
     * method will be a no-op.
     *
     * If the middleware has no record of the
     * file indicated by {@code fileInfo} being associated with {@code downloadRequest},
     * an {@link IllegalArgumentException} will be thrown.
     *
     * @param downloadRequest The download request to query.
     * @param fileInfo The particular file within the request to get information on.
     */
    public void requestDownloadState(DownloadRequest downloadRequest, FileInfo fileInfo) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }

        try {
            int result = downloadService.requestDownloadState(downloadRequest, fileInfo);
            if (result == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (result != MbmsErrors.SUCCESS) {
                if (result == MbmsErrors.DownloadErrors.ERROR_UNKNOWN_DOWNLOAD_REQUEST) {
                    throw new IllegalArgumentException("Unknown download request.");
                }
                if (result == MbmsErrors.DownloadErrors.ERROR_UNKNOWN_FILE_INFO) {
                    throw new IllegalArgumentException("Unknown file.");
                }
                sendErrorToApp(result, null);
            }
        } catch (RemoteException e) {
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
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
     * This is distinct from cancelling and re-issuing the download request -- if you cancel and
     * re-issue, the middleware will not clear its cache of download state information.
     *
     * If the middleware is not aware of the specified download request, an
     * {@link IllegalArgumentException} will be thrown.
     *
     * @param downloadRequest The request to re-download files for.
     */
    public void resetDownloadKnowledge(DownloadRequest downloadRequest) {
        IMbmsDownloadService downloadService = mService.get();
        if (downloadService == null) {
            throw new IllegalStateException("Middleware not yet bound");
        }

        try {
            int result = downloadService.resetDownloadKnowledge(downloadRequest);
            if (result == MbmsErrors.UNKNOWN) {
                // Unbind and throw an obvious error
                close();
                throw new IllegalStateException("Middleware must not return an unknown error code");
            }
            if (result != MbmsErrors.SUCCESS) {
                if (result == MbmsErrors.DownloadErrors.ERROR_UNKNOWN_DOWNLOAD_REQUEST) {
                    throw new IllegalArgumentException("Unknown download request.");
                }
                sendErrorToApp(result, null);
            }
        } catch (RemoteException e) {
            mService.set(null);
            sIsInitialized.set(false);
            sendErrorToApp(MbmsErrors.ERROR_MIDDLEWARE_LOST, null);
        }
    }

    /**
     * Terminates this instance.
     *
     * After this method returns,
     * no further callbacks originating from the middleware will be enqueued on the provided
     * instance of {@link MbmsDownloadSessionCallback}, but callbacks that have already been
     * enqueued will still be delivered.
     *
     * It is safe to call {@link #create(Context, Executor, int, MbmsDownloadSessionCallback)} to
     * obtain another instance of {@link MbmsDownloadSession} immediately after this method
     * returns.
     *
     * May throw an {@link IllegalStateException}
     */
    @Override
    public void close() {
        try {
            IMbmsDownloadService downloadService = mService.get();
            if (downloadService == null || mServiceConnection == null) {
                Log.i(LOG_TAG, "Service already dead");
                return;
            }
            downloadService.dispose(mSubscriptionId);
            mContext.unbindService(mServiceConnection);
        } catch (RemoteException e) {
            // Ignore
            Log.i(LOG_TAG, "Remote exception while disposing of service");
        } finally {
            mService.set(null);
            sIsInitialized.set(false);
            mServiceConnection = null;
            mInternalCallback.stop();
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
                        + request + ". Token location is " + token.getPath());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create download token for request " + request
                    + " due to IOException " + e + ". Attempted to write to " + token.getPath());
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

    private void checkDownloadRequestDestination(DownloadRequest request) {
        File downloadRequestDestination = new File(request.getDestinationUri().getPath());
        if (!downloadRequestDestination.isDirectory()) {
            throw new IllegalArgumentException("The destination path must be a directory");
        }
        // Check if the request destination is okay to use by attempting to rename an empty
        // file to there.
        File testFile = new File(MbmsTempFileProvider.getEmbmsTempFileDir(mContext),
                DESTINATION_SANITY_CHECK_FILE_NAME);
        File testFileDestination = new File(downloadRequestDestination,
                DESTINATION_SANITY_CHECK_FILE_NAME);

        try {
            if (!testFile.exists()) {
                testFile.createNewFile();
            }
            if (!testFile.renameTo(testFileDestination)) {
                throw new IllegalArgumentException("Destination provided in the download request " +
                        "is invalid -- files in the temp file directory cannot be directly moved " +
                        "there.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Got IOException while testing out the destination: "
                    + e);
        } finally {
            testFile.delete();
            testFileDestination.delete();
        }
    }

    private File getDownloadRequestTokenPath(DownloadRequest request) {
        File tempFileLocation = MbmsUtils.getEmbmsTempFileDirForService(mContext,
                request.getFileServiceId());
        String downloadTokenFileName = request.getHash()
                + MbmsDownloadReceiver.DOWNLOAD_TOKEN_SUFFIX;
        return new File(tempFileLocation, downloadTokenFileName);
    }

    private void sendErrorToApp(int errorCode, String message) {
        mInternalCallback.onError(errorCode, message);
    }
}
