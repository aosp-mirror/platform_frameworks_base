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
     * should use createManager to create/initialize a copy
     * @hide
     */
    public MbmsDownloadManager(Context context) {
        mContext = context;
    }

    public static MbmsDownloadManager createManager(Context context,
            IMbmsDownloadManagerListener listener, String downloadAppName) {
//        MbmsDownloadManager mdm = context.getSystemService(Context.MBMS_DOWNLOAD_SERVICE);
//        if (mdm == null) return mdm;
//        mdm.initialize(listener, downloadAppName,
//                SubscriptionManager.getDefaultSubscriptionId());
//        return mdm;
        return null;
    }

    public static MbmsDownloadManager createManager(Context context,
            IMbmsDownloadManagerListener listener, String downloadAppName, int subId) {
//        MbmsDownloadManager mdm = context.getSystemService(Context.MBMS_DOWNLOAD_SERVICE);
//        if (mdm == null) return mdm;
//        mdm.initialize(listener, downloadAppName, subId);
//        return mdm;
        return null;
    }

    private void initialize(IMbmsDownloadManagerListener listener, String downloadAppName,
            int subId) {
        // assert all empty and set
    }

    /**
     * Gets the list of files published for download.
     * They may occur at times far in the future.
     * servicesClasses lets the app filter on types of files and is opaque data between
     *     the app and the carrier
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
     */
    public DownloadRequest download(DownloadRequest downloadRequest, DownloadListener listener) {
        return null;
    }

    public List<DownloadRequest> listPendingDownloads() {
        return null;
    }

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
     */
    public void resetDownloadKnowledge(DownloadRequest downloadRequest) {
    }

    public void dispose() {
    }
}
