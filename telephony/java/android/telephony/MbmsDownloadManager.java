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

import android.app.PendingIntent;
import android.content.Context;
import android.net.Uri;
import android.telephony.mbms.DownloadListener;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.DownloadStatus;
import android.telephony.mbms.FileServiceInfo;
import android.telephony.mbms.IMbmsDownloadManagerListener;

import java.util.List;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

/** @hide */
public class MbmsDownloadManager {
    private final Context mContext;
    private int mSubId = INVALID_SUBSCRIPTION_ID;

    /**
     * Create a new MbmsDownloadManager using the system default data subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit.  This
     * may throw an Illegal ArgumentException or RemoteException.
     *
     * @hide
     */
    public MbmsDownloadManager(Context context, IMbmsDownloadManagerListener listener,
            String downloadAppName) {
        mContext = context;
    }

    /**
     * Create a new MbmsDownloadManager using the given subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit.  This
     * may throw an Illegal ArgumentException or RemoteException.
     *
     * @hide
     */
    public MbmsDownloadManager(Context context, IMbmsDownloadManagerListener listener,
            String downloadAppName, int subId) {
        mContext = context;
    }

    /**
     * Gets the list of files published for download.
     * They may occur at times far in the future.
     * servicesClasses lets the app filter on types of files and is opaque data between
     *     the app and the carrier
     *
     * Multiple calls replace trhe list of serviceClasses of interest.
     *
     * May throw an IllegalArgumentException or RemoteException.
     *
     * Synchronous responses include
     * <li>SUCCESS</li>
     * <li>ERROR_MSDC_CONCURRENT_SERVICE_LIMIT_REACHED</li>
     *
     * Asynchronous errors through the listener include any of the errors except
     * <li>ERROR_MSDC_UNABLE_TO_)START_SERVICE</li>
     * <li>ERROR_MSDC_INVALID_SERVICE_ID</li>
     * <li>ERROR_MSDC_END_OF_SESSION</li>
     */
    public int getFileServices(List<String> serviceClasses) {
        return 0;
    }


    public static final String EXTRA_REQUEST         = "extraRequest";

    public static final int RESULT_SUCCESSFUL = 1;
    public static final int RESULT_CANCELLED  = 2;
    public static final int RESULT_EXPIRED    = 3;
    // TODO - more results!

    public static final String EXTRA_RESULT          = "extraResult";
    public static final String EXTRA_URI             = "extraDownloadedUri";

    /**
     * Requests a future download.
     * returns a token which may be used to cancel a download.
     * fileServiceInfo indicates what FileService to download from
     * source indicates which file to download from the given FileService.  This is
     *     an optional field - it may be null or empty to indicate download everything from
     *     the FileService.
     * destination is a file URI for where in the apps accessible storage locations to write
     *     the content.  This URI may be used to store temporary data and should not be
     *     accessed until the PendingIntent is called indicating success.
     * resultIntent is sent when each file is completed and when the request is concluded
     *     either via TTL expiration, cancel or error.
     *     This intent is sent with three extras: a {@link DownloadRequest} typed extra called
     *     {@link #EXTRA_REQUEST}, an Integer called {@link #EXTRA_RESULT} for the result code
     *     and a {@link Uri} called {@link #EXTRA_URI} to the resulting file (if successful).
     * downloadListener is an optional callback object which can be used to get progress reports
     *     of a currently occuring download.  Note this can only run while the calling app
     *     is running, so future downloads will simply result in resultIntents being sent
     *     for completed or errored-out downloads.  A NULL indicates no callbacks are needed.
     *
     * May throw an IllegalArgumentException or RemoteExcpetion.
     *
     * Asynchronous errors through the listener include any of the errors
     */
    public DownloadRequest download(DownloadRequest downloadRequest, DownloadListener listener) {
        return null;
    }

    /**
     * Returns a list DownloadRequests that originated from this application (UID).
     *
     * May throw a RemoteException.
     *
     * Asynchronous errors through the listener include any of the errors except
     * <li>ERROR_UNABLED_TO_START_SERVICE</li>
     * <li>ERROR_MSDC_INVALID_SERVICE_ID</li>
     * <li>ERROR_MSDC_END_OF_SESSION</li>
     */
    public List<DownloadRequest> listPendingDownloads() {
        return null;
    }

    /**
     * Attempts to cancel the specified DownloadRequest.
     *
     * May throw a RemoteException.
     *
     * Synchronous responses may include
     * <li>SUCCESS</li>
     * <li>ERROR_MSDC_CONCURRENT_SERVICE_LIMIT_REACHED</li>
     * <li>ERROR_MSDC_UNKNOWN_REQUEST</li>
     */
    public int cancelDownload(DownloadRequest downloadRequest) {
        return 0;
    }

    /**
     * Gets information about current and known upcoming downloads.
     *
     * Current is a straightforward count of the files being downloaded "now"
     * for some definition of now (may be racey).
     * Future downloads include counts of files with pending repair operations, counts of
     * files with future downloads and indication of scheduled download times with unknown
     * file details.
     *
     * May throw an IllegalArgumentException or RemoteException.
     *
     * If the DownloadRequest is unknown the results will be null.
     */
    public DownloadStatus getDownloadStatus(DownloadRequest downloadRequest) {
        return null;
    }

    /**
     * Resets middleware knowldge regarding this download request.
     *
     * This state consists of knowledge of what files have already been downloaded.
     * Normally the middleware won't download files who's hash matches previously downloaded
     * content, even if that content has since been deleted.  If this function is called
     * repeated content will be downloaded again when available.  This does not interrupt
     * in-progress downloads.
     *
     * May throw an IllegalArgumentException or RemoteException.
     *
     * <li>SUCCESS</li>
     * <li>ERROR_MSDC_CONCURRENT_SERVICE_LIMIT_REACHED</li>
     * <li>ERROR_MSDC_UNKNOWN_REQUEST</li>
     */
    public int resetDownloadKnowledge(DownloadRequest downloadRequest) {
        return 0;
    }

    public void dispose() {
    }
}
