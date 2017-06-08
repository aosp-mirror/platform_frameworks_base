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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.IDownloadCallback;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.DownloadStatus;
import android.telephony.mbms.IMbmsDownloadManagerCallback;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.MbmsUtils;
import android.telephony.mbms.vendor.IMbmsDownloadService;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CountDownLatch;

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
     * {@link #EXTRA_INFO}
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
     */
    public static final String EXTRA_RESULT = "android.telephony.mbms.extra.RESULT";

    /**
     * Extra containing the {@link android.telephony.mbms.FileInfo} for which the download result
     * is for. Must not be null.
     * TODO: future systemapi (here and and all extras) except the two for the app intent
     */
    public static final String EXTRA_INFO = "android.telephony.mbms.extra.INFO";

    /**
     * Extra containing the {@link DownloadRequest} for which the download result or file
     * descriptor request is for. Must not be null.
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
     * Extra containing a list of {@link Uri}s indicating temp files which the middleware is
     * still using.
     */
    public static final String EXTRA_TEMP_FILES_IN_USE =
            "android.telephony.mbms.extra.TEMP_FILES_IN_USE";

    /**
     * Extra containing a single {@link Uri} indicating the location of the successfully
     * downloaded file. Set on the intent provided via
     * {@link android.telephony.mbms.DownloadRequest.Builder#setAppIntent(Intent)}.
     * Will always be set to a non-null value if {@link #EXTRA_RESULT} is set to
     * {@link #RESULT_SUCCESSFUL}.
     */
    public static final String EXTRA_COMPLETED_FILE_URI =
            "android.telephony.mbms.extra.COMPLETED_FILE_URI";

    public static final int RESULT_SUCCESSFUL = 1;
    public static final int RESULT_CANCELLED  = 2;
    public static final int RESULT_EXPIRED    = 3;
    // TODO - more results!

    private static final long BIND_TIMEOUT_MS = 3000;

    private final Context mContext;
    private int mSubId = INVALID_SUBSCRIPTION_ID;

    private IMbmsDownloadService mService;
    private final IMbmsDownloadManagerCallback mCallback;
    private final String mDownloadAppName;

    private MbmsDownloadManager(Context context, IMbmsDownloadManagerCallback callback,
            String downloadAppName, int subId) {
        mContext = context;
        mCallback = callback;
        mDownloadAppName = downloadAppName;
        mSubId = subId;
    }

    /**
     * Create a new MbmsDownloadManager using the system default data subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit.  This
     * may throw an Illegal ArgumentException or RemoteException.
     *
     * @hide
     */
    public static MbmsDownloadManager createManager(Context context,
            IMbmsDownloadManagerCallback listener, String downloadAppName)
            throws MbmsException {
        MbmsDownloadManager mdm = new MbmsDownloadManager(context, listener, downloadAppName,
                SubscriptionManager.getDefaultSubscriptionId());
        mdm.bindAndInitialize();
        return mdm;
    }

    /**
     * Create a new MbmsDownloadManager using the given subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit.  This
     * may throw an Illegal ArgumentException or RemoteException.
     *
     * @hide
     */

    public static MbmsDownloadManager createManager(Context context,
            IMbmsDownloadManagerCallback listener, String downloadAppName, int subId)
            throws MbmsException {
        MbmsDownloadManager mdm = new MbmsDownloadManager(context, listener, downloadAppName,
                subId);
        mdm.bindAndInitialize();
        return mdm;
    }

    private void bindAndInitialize() throws MbmsException {
        // TODO: fold binding for download and streaming into a common utils class.
        final CountDownLatch latch = new CountDownLatch(1);
        ServiceConnection bindListener = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mService = IMbmsDownloadService.Stub.asInterface(service);
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mService = null;
            }
        };

        Intent bindIntent = new Intent();
        bindIntent.setComponent(MbmsUtils.toComponentName(
                MbmsUtils.getMiddlewareServiceInfo(mContext, MBMS_DOWNLOAD_SERVICE_ACTION)));

        // Kick off the binding, and synchronously wait until binding is complete
        mContext.bindService(bindIntent, bindListener, Context.BIND_AUTO_CREATE);

        MbmsUtils.waitOnLatchWithTimeout(latch, BIND_TIMEOUT_MS);

        // TODO: initialize
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


    /**
     * Requests a future download.
     * returns a token which may be used to cancel a download.
     * downloadListener is an optional callback object which can be used to get progress reports
     *     of a currently occuring download.  Note this can only run while the calling app
     *     is running, so future downloads will simply result in resultIntents being sent
     *     for completed or errored-out downloads.  A NULL indicates no callbacks are needed.
     *
     * May throw an IllegalArgumentException or RemoteExcpetion.
     *
     * Asynchronous errors through the listener include any of the errors
     */
    public DownloadRequest download(DownloadRequest request, IDownloadCallback listener) {
        request.setAppName(mDownloadAppName);
        try {
            mService.download(request, listener);
        } catch (RemoteException e) {
            mService = null;
        }
        return request;
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
     * Resets middleware knowledge regarding this download request.
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
        try {
            if (mService != null) {
                mService.dispose(mDownloadAppName, mSubId);
            } else {
                Log.i(LOG_TAG, "Service already dead");
            }
        } catch (RemoteException e) {
            // Ignore
            Log.i(LOG_TAG, "Remote exception while disposing of service");
        }
    }
}
