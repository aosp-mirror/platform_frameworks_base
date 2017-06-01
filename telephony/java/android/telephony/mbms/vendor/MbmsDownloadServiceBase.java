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
import android.telephony.mbms.DownloadStatus;
import android.telephony.mbms.IDownloadCallback;
import android.telephony.mbms.IMbmsDownloadManagerCallback;

import java.util.List;

/**
 * Base class for MbmsDownloadService. The middleware should extend this base class rather than
 * the aidl stub for compatibility
 * @hide
 * TODO: future systemapi
 */
public class MbmsDownloadServiceBase extends IMbmsDownloadService.Stub {
    @Override
    public void initialize(String appName, int subId, IMbmsDownloadManagerCallback listener)
            throws RemoteException {
    }

    @Override
    public int getFileServices(String appName, int subId, List<String> serviceClasses) throws
            RemoteException {
        return 0;
    }

    @Override
    public int download(DownloadRequest downloadRequest, IDownloadCallback listener)
            throws RemoteException {
        return 0;
    }

    @Override
    public List<DownloadRequest> listPendingDownloads(String appName, int subscriptionId)
            throws RemoteException {
        return null;
    }

    @Override
    public int cancelDownload(DownloadRequest downloadRequest) throws RemoteException {
        return 0;
    }

    @Override
    public DownloadStatus getDownloadStatus(DownloadRequest downloadRequest)
            throws RemoteException {
        return null;
    }

    @Override
    public void resetDownloadKnowledge(DownloadRequest downloadRequest)
            throws RemoteException {
    }

    @Override
    public void dispose(String appName, int subscriptionId) throws RemoteException {
    }
}
