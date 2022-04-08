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
import android.telephony.mbms.FileInfo;
import android.telephony.mbms.IDownloadProgressListener;
import android.telephony.mbms.IDownloadStatusListener;
import android.telephony.mbms.IMbmsDownloadSessionCallback;

/**
 * @hide
 */
interface IMbmsDownloadService
{
    int initialize(int subId, IMbmsDownloadSessionCallback listener);

    int requestUpdateFileServices(int subId, in List<String> serviceClasses);

    int setTempFileRootDirectory(int subId, String rootDirectoryPath);

    int download(in DownloadRequest downloadRequest);

    int addStatusListener(in DownloadRequest downloadRequest,
        IDownloadStatusListener listener);

    int removeStatusListener(in DownloadRequest downloadRequest,
        IDownloadStatusListener listener);

    int addProgressListener(in DownloadRequest downloadRequest,
        IDownloadProgressListener listener);

    int removeProgressListener(in DownloadRequest downloadRequest,
        IDownloadProgressListener listener);

    List<DownloadRequest> listPendingDownloads(int subscriptionId);

    int cancelDownload(in DownloadRequest downloadRequest);

    int requestDownloadState(in DownloadRequest downloadRequest, in FileInfo fileInfo);

    int resetDownloadKnowledge(in DownloadRequest downloadRequest);

    void dispose(int subId);
}
