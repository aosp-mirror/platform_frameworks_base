/*
** Copyright 2017, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.telephony.mbms.vendor;

import android.app.PendingIntent;
import android.net.Uri;
import android.telephony.mbms.DownloadRequest;
import android.telephony.mbms.DownloadStatus;
import android.telephony.mbms.IMbmsDownloadManagerCallback;
import android.telephony.mbms.IDownloadCallback;

/**
 * The interface the opaque MbmsStreamingService will satisfy.
 * @hide
 */
interface IMbmsDownloadService
{
    /**
     * Initialize download service
     * Registers this listener, subId with this appName
     *
     * No return value.  Async errors may be reported, but none expected (not doing anything yet).
     */
    void initialize(String appName, int subId, IMbmsDownloadManagerCallback listener);

    /**
     * - Registers serviceClasses of interest with the uid/appName/subId key.
     * - Starts asynch fetching data on download services of matching classes to be reported
     * later by callback.
     *
     * Note that subsequent calls with the same callback, appName, subId and uid will replace
     * the service class list.
     */
    int getFileServices(String appName, int subId, in List<String> serviceClasses);

    /**
     * should move the params into a DownloadRequest parcelable
     */
    int download(in DownloadRequest downloadRequest, IDownloadCallback listener);

    List<DownloadRequest> listPendingDownloads(String appName, int subscriptionId);

    int cancelDownload(in DownloadRequest downloadRequest);

    DownloadStatus getDownloadStatus(in DownloadRequest downloadRequest);

    /*
     * named this for 2 reasons:
     *  1 don't want 'State' here as it conflicts with 'Status' of the previous function
     *  2 want to perfect typing 'Knowledge'
     */
    void resetDownloadKnowledge(in DownloadRequest downloadRequest);

    /**
     * End of life for this MbmsDownloadManager.
     * Any pending downloads remain in affect and may start up independently in the future.
     */
    void dispose(String appName, int subId);
}
