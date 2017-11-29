/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.mbms.vendor;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.MbmsDownloadSession;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.DownloadStateCallback;
import android.telephony.mbms.FileInfo;
import android.telephony.mbms.FileServiceInfo;
import android.telephony.mbms.IDownloadStateCallback;
import android.telephony.mbms.IMbmsDownloadSessionCallback;
import android.telephony.mbms.MbmsDownloadSessionCallback;
import android.telephony.mbms.MbmsErrors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for MbmsDownloadService. The middleware should return an instance of this object from
 * its {@link android.app.Service#onBind(Intent)} method.
 * @hide
 */
//@SystemApi
public class MbmsDownloadServiceBase extends IMbmsDownloadService.Stub {
    private final Map<IBinder, DownloadStateCallback> mDownloadCallbackBinderMap = new HashMap<>();
    private final Map<IBinder, DeathRecipient> mDownloadCallbackDeathRecipients = new HashMap<>();


    // Filters the DownloadStateCallbacks by its configuration from the app.
    private abstract static class FilteredDownloadStateCallback extends DownloadStateCallback {

        private final IDownloadStateCallback mCallback;
        public FilteredDownloadStateCallback(IDownloadStateCallback callback, int callbackFlags) {
            super(callbackFlags);
            mCallback = callback;
        }

        @Override
        public void onProgressUpdated(DownloadRequest request, FileInfo fileInfo,
                int currentDownloadSize, int fullDownloadSize, int currentDecodedSize,
                int fullDecodedSize) {
            if (!isFilterFlagSet(PROGRESS_UPDATES)) {
                return;
            }
            try {
                mCallback.onProgressUpdated(request, fileInfo, currentDownloadSize,
                        fullDownloadSize, currentDecodedSize, fullDecodedSize);
            } catch (RemoteException e) {
                onRemoteException(e);
            }
        }

        @Override
        public void onStateUpdated(DownloadRequest request, FileInfo fileInfo,
                @MbmsDownloadSession.DownloadStatus int state) {
            if (!isFilterFlagSet(STATE_UPDATES)) {
                return;
            }
            try {
                mCallback.onStateUpdated(request, fileInfo, state);
            } catch (RemoteException e) {
                onRemoteException(e);
            }
        }

        protected abstract void onRemoteException(RemoteException e);
    }

    /**
     * Initialize the download service for this app and subId, registering the listener.
     *
     * May throw an {@link IllegalArgumentException} or an {@link IllegalStateException}, which
     * will be intercepted and passed to the app as
     * {@link MbmsErrors.InitializationErrors#ERROR_UNABLE_TO_INITIALIZE}
     *
     * May return any value from {@link MbmsErrors.InitializationErrors}
     * or {@link MbmsErrors#SUCCESS}. Non-successful error codes will be passed to the app via
     * {@link IMbmsDownloadSessionCallback#onError(int, String)}.
     *
     * @param callback The callback to use to communicate with the app.
     * @param subscriptionId The subscription ID to use.
     */
    public int initialize(int subscriptionId, MbmsDownloadSessionCallback callback)
            throws RemoteException {
        return 0;
    }

    /**
     * Actual AIDL implementation -- hides the callback AIDL from the API.
     * @hide
     */
    @Override
    public final int initialize(final int subscriptionId,
            final IMbmsDownloadSessionCallback callback) throws RemoteException {
        final int uid = Binder.getCallingUid();
        callback.asBinder().linkToDeath(new DeathRecipient() {
            @Override
            public void binderDied() {
                onAppCallbackDied(uid, subscriptionId);
            }
        }, 0);

        return initialize(subscriptionId, new MbmsDownloadSessionCallback() {
            @Override
            public void onError(int errorCode, String message) {
                try {
                    callback.onError(errorCode, message);
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }

            @Override
            public void onFileServicesUpdated(List<FileServiceInfo> services) {
                try {
                    callback.onFileServicesUpdated(services);
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }

            @Override
            public void onMiddlewareReady() {
                try {
                    callback.onMiddlewareReady();
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }
        });
    }

    /**
     * Registers serviceClasses of interest with the appName/subId key.
     * Starts async fetching data on streaming services of matching classes to be reported
     * later via {@link IMbmsDownloadSessionCallback#onFileServicesUpdated(List)}
     *
     * Note that subsequent calls with the same uid and subId will replace
     * the service class list.
     *
     * May throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     * @param serviceClasses The service classes that the app wishes to get info on. The strings
     *                       may contain arbitrary data as negotiated between the app and the
     *                       carrier.
     * @return One of {@link MbmsErrors#SUCCESS} or
     *         {@link MbmsErrors.GeneralErrors#ERROR_MIDDLEWARE_NOT_YET_READY},
     */
    @Override
    public int requestUpdateFileServices(int subscriptionId, List<String> serviceClasses)
            throws RemoteException {
        return 0;
    }

    /**
     * Sets the temp file root directory for this app/subscriptionId combination. The middleware
     * should persist {@code rootDirectoryPath} and send it back when sending intents to the
     * app's {@link android.telephony.mbms.MbmsDownloadReceiver}.
     *
     * If the calling app (as identified by the calling UID) currently has any pending download
     * requests that have not been canceled, the middleware must return
     * {@link MbmsErrors.DownloadErrors#ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT} here.
     *
     * @param subscriptionId The subscription id the download is operating under.
     * @param rootDirectoryPath The path to the app's temp file root directory.
     * @return {@link MbmsErrors#SUCCESS},
     *         {@link MbmsErrors.GeneralErrors#ERROR_MIDDLEWARE_NOT_YET_READY} or
     *         {@link MbmsErrors.DownloadErrors#ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT}
     */
    @Override
    public int setTempFileRootDirectory(int subscriptionId,
            String rootDirectoryPath) throws RemoteException {
        return 0;
    }

    /**
     * Issues a request to download a set of files.
     *
     * The middleware should expect that {@link #setTempFileRootDirectory(int, String)} has been
     * called for this app between when the app was installed and when this method is called. If
     * this is not the case, an {@link IllegalStateException} may be thrown.
     *
     * @param downloadRequest An object describing the set of files to be downloaded.
     * @return Any error from {@link MbmsErrors.GeneralErrors}
     *         or {@link MbmsErrors#SUCCESS}
     */
    @Override
    public int download(DownloadRequest downloadRequest) throws RemoteException {
        return 0;
    }

    /**
     * Registers a download state callbacks for the provided {@link DownloadRequest}.
     *
     * This method is called by the app when it wants to request updates on the progress or
     * status of the download.
     *
     * If the middleware is not aware of a download having been requested with the provided
     *
     * {@link DownloadRequest} in the past,
     * {@link MbmsErrors.DownloadErrors#ERROR_UNKNOWN_DOWNLOAD_REQUEST}
     * must be returned.
     *
     * @param downloadRequest The {@link DownloadRequest} that was used to initiate the download
     *                        for which progress updates are being requested.
     * @param callback The callback object to use.
     */
    public int registerStateCallback(DownloadRequest downloadRequest,
            DownloadStateCallback callback) throws RemoteException {
        return 0;
    }

    /**
     * Actual AIDL implementation -- hides the callback AIDL from the API.
     * @hide
     */
    @Override
    public final int registerStateCallback(final DownloadRequest downloadRequest,
            final IDownloadStateCallback callback, int flags) throws RemoteException {
        final int uid = Binder.getCallingUid();
        DeathRecipient deathRecipient = new DeathRecipient() {
            @Override
            public void binderDied() {
                onAppCallbackDied(uid, downloadRequest.getSubscriptionId());
                mDownloadCallbackBinderMap.remove(callback.asBinder());
                mDownloadCallbackDeathRecipients.remove(callback.asBinder());
            }
        };
        mDownloadCallbackDeathRecipients.put(callback.asBinder(), deathRecipient);
        callback.asBinder().linkToDeath(deathRecipient, 0);

        DownloadStateCallback exposedCallback = new FilteredDownloadStateCallback(callback, flags) {
            @Override
            protected void onRemoteException(RemoteException e) {
                onAppCallbackDied(uid, downloadRequest.getSubscriptionId());
            }
        };

        mDownloadCallbackBinderMap.put(callback.asBinder(), exposedCallback);

        return registerStateCallback(downloadRequest, exposedCallback);
    }

    /**
     * Un-registers a download state callbacks for the provided {@link DownloadRequest}.
     *
     * This method is called by the app when it no longer wants to request updates on the
     * download.
     *
     * If the middleware is not aware of a download having been requested with the provided
     * {@link DownloadRequest} in the past,
     * {@link MbmsErrors.DownloadErrors#ERROR_UNKNOWN_DOWNLOAD_REQUEST}
     * must be returned.
     *
     * @param downloadRequest The {@link DownloadRequest} that was used to register the callback
     * @param callback The callback object that
     *                 {@link #registerStateCallback(DownloadRequest, DownloadStateCallback)}
     *                 was called with.
     */
    public int unregisterStateCallback(DownloadRequest downloadRequest,
            DownloadStateCallback callback) throws RemoteException {
        return 0;
    }

    /**
     * Actual AIDL implementation -- hides the callback AIDL from the API.
     * @hide
     */
    @Override
    public final int unregisterStateCallback(
            final DownloadRequest downloadRequest, final IDownloadStateCallback callback)
            throws RemoteException {
        DeathRecipient deathRecipient =
                mDownloadCallbackDeathRecipients.remove(callback.asBinder());
        if (deathRecipient == null) {
            throw new IllegalArgumentException("Unknown callback");
        }

        callback.asBinder().unlinkToDeath(deathRecipient, 0);

        DownloadStateCallback exposedCallback =
                mDownloadCallbackBinderMap.remove(callback.asBinder());
        if (exposedCallback == null) {
            throw new IllegalArgumentException("Unknown callback");
        }

        return unregisterStateCallback(downloadRequest, exposedCallback);
    }

    /**
     * Returns a list of pending {@link DownloadRequest}s that originated from the calling
     * application, identified by its uid. A pending request is one that was issued via
     * {@link #download(DownloadRequest)} but not cancelled through
     * {@link #cancelDownload(DownloadRequest)}.
     * The middleware must return a non-null result synchronously or throw an exception
     * inheriting from {@link RuntimeException}.
     * @return A list, possibly empty, of {@link DownloadRequest}s
     */
    @Override
    public @NonNull List<DownloadRequest> listPendingDownloads(int subscriptionId)
            throws RemoteException {
        return null;
    }

    /**
     * Issues a request to cancel the specified download request.
     *
     * If the middleware is unable to cancel the request for whatever reason, it should return
     * synchronously with an error. If this method returns {@link MbmsErrors#SUCCESS}, the app
     * will no longer be expecting any more file-completed intents from the middleware for this
     * {@link DownloadRequest}.
     * @param downloadRequest The request to cancel
     * @return {@link MbmsErrors#SUCCESS},
     *         {@link MbmsErrors.DownloadErrors#ERROR_UNKNOWN_DOWNLOAD_REQUEST},
     *         {@link MbmsErrors.GeneralErrors#ERROR_MIDDLEWARE_NOT_YET_READY}
     */
    @Override
    public int cancelDownload(DownloadRequest downloadRequest) throws RemoteException {
        return 0;
    }

    /**
     * Gets information about the status of a file pending download.
     *
     * If the middleware has not yet been properly initialized or if it has no records of the
     * file indicated by {@code fileInfo} being associated with {@code downloadRequest},
     * {@link MbmsDownloadSession#STATUS_UNKNOWN} must be returned.
     *
     * @param downloadRequest The download request to query.
     * @param fileInfo The particular file within the request to get information on.
     * @return The status of the download.
     */
    @Override
    public int getDownloadStatus(DownloadRequest downloadRequest, FileInfo fileInfo)
            throws RemoteException {
        return 0;
    }

    /**
     * Resets the middleware's knowledge of previously-downloaded files in this download request.
     *
     * When this method is called, the middleware must attempt to re-download all the files
     * specified by the {@link DownloadRequest}, even if the files have not changed on the server.
     * In addition, current in-progress downloads must not be interrupted.
     *
     * If the middleware is not aware of the specified download request, return
     * {@link MbmsErrors.DownloadErrors#ERROR_UNKNOWN_DOWNLOAD_REQUEST}.
     *
     * @param downloadRequest The request to re-download files for.
     */
    @Override
    public int resetDownloadKnowledge(DownloadRequest downloadRequest)
            throws RemoteException {
        return 0;
    }

    /**
     * Signals that the app wishes to dispose of the session identified by the
     * {@code subscriptionId} argument and the caller's uid. No notification back to the
     * app is required for this operation, and the corresponding callback provided via
     * {@link #initialize(int, IMbmsDownloadSessionCallback)} should no longer be used
     * after this method has been called by the app.
     *
     * Any download requests issued by the app should remain in effect until the app calls
     * {@link #cancelDownload(DownloadRequest)} on another session.
     *
     * May throw an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     */
    @Override
    public void dispose(int subscriptionId) throws RemoteException {
    }

    /**
     * Indicates that the app identified by the given UID and subscription ID has died.
     * @param uid the UID of the app, as returned by {@link Binder#getCallingUid()}.
     * @param subscriptionId The subscription ID the app is using.
     */
    public void onAppCallbackDied(int uid, int subscriptionId) {
    }
}
