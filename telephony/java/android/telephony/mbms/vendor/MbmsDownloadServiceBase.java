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

import android.os.RemoteException;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.FileInfo;
import android.telephony.mbms.IDownloadCallback;
import android.telephony.mbms.IMbmsDownloadManagerCallback;
import android.telephony.mbms.MbmsException;

import java.util.List;

/**
 * Base class for MbmsDownloadService. The middleware should extend this base class rather than
 * the aidl stub for compatibility
 * @hide
 * TODO: future systemapi
 */
public class MbmsDownloadServiceBase extends IMbmsDownloadService.Stub {
    /**
     * Initialize the download service for this app and subId, registering the listener.
     *
     * May throw an {@link IllegalArgumentException} or a {@link SecurityException}
     *
     * @param listener The callback to use to communicate with the app.
     * @param subscriptionId The subscription ID to use.
     */
    @Override
    public void initialize(int subscriptionId,
            IMbmsDownloadManagerCallback listener) throws RemoteException {
    }

    /**
     * Registers serviceClasses of interest with the appName/subId key.
     * Starts async fetching data on streaming services of matching classes to be reported
     * later via {@link IMbmsDownloadManagerCallback#fileServicesUpdated(List)}
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
     * @return One of {@link MbmsException#SUCCESS},
     *         {@link MbmsException#ERROR_MIDDLEWARE_NOT_YET_READY},
     *         {@link MbmsException#ERROR_CONCURRENT_SERVICE_LIMIT_REACHED}
     */
    @Override
    public int getFileServices(int subscriptionId, List<String> serviceClasses)
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
     * {@link MbmsException#ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT} here.
     *
     * @param subscriptionId The subscription id the download is operating under.
     * @param rootDirectoryPath The path to the app's temp file root directory.
     * @return {@link MbmsException#SUCCESS},
     *         {@link MbmsException#ERROR_MIDDLEWARE_NOT_YET_READY},
     *         {@link MbmsException#ERROR_CANNOT_CHANGE_TEMP_FILE_ROOT},
     *         or {@link MbmsException#ERROR_CONCURRENT_SERVICE_LIMIT_REACHED}
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
     * @param listener A listener through which the middleware can provide progress updates to
     *                 the app while both are still running.
     * @return TODO: enumerate possible return values
     */
    @Override
    public int download(DownloadRequest downloadRequest, IDownloadCallback listener)
            throws RemoteException {
        return 0;
    }

    @Override
    public List<DownloadRequest> listPendingDownloads(int subscriptionId)
            throws RemoteException {
        return null;
    }

    /**
     * Issues a request to cancel the specified download request.
     *
     * If the middleware is unable to cancel the request for whatever reason, it should return
     * synchronously with an error. If this method returns {@link MbmsException#SUCCESS}, the app
     * will no longer be expecting any more file-completed intents from the middleware for this
     * {@link DownloadRequest}.
     * @param downloadRequest The request to cancel
     * @return {@link MbmsException#SUCCESS},
     *         {@link MbmsException#ERROR_UNKNOWN_DOWNLOAD_REQUEST},
     *         {@link MbmsException#ERROR_MIDDLEWARE_NOT_YET_READY}
     */
    @Override
    public int cancelDownload(DownloadRequest downloadRequest) throws RemoteException {
        return 0;
    }

    @Override
    public int getDownloadStatus(DownloadRequest downloadRequest, FileInfo fileInfo)
            throws RemoteException {
        return 0;
    }

    @Override
    public void resetDownloadKnowledge(DownloadRequest downloadRequest)
            throws RemoteException {
    }

    /**
     * Signals that the app wishes to dispose of the session identified by the
     * {@code subscriptionId} argument and the caller's uid. No notification back to the
     * app is required for this operation, and the corresponding callback provided via
     * {@link #initialize(int, IMbmsDownloadManagerCallback)} should no longer be used
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
}
