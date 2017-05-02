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

import android.net.Uri;
import android.os.RemoteException;
import android.telephony.mbms.IMbmsStreamingManagerCallback;
import android.telephony.mbms.IStreamingServiceCallback;
import android.telephony.mbms.StreamingService;

import java.util.List;

/**
 * @hide
 * TODO: future systemapi
 */
public class MbmsStreamingServiceBase extends IMbmsStreamingService.Stub {

    @Override
    public int initialize(IMbmsStreamingManagerCallback listener, String appName, int subId)
            throws RemoteException {
        return 0;
    }

    @Override
    public int getStreamingServices(String appName, int subId, List<String> serviceClasses)
            throws RemoteException {
        return 0;
    }

    @Override
    public StreamingService startStreaming(String appName, int subId,
            String serviceId, IStreamingServiceCallback listener) throws RemoteException {
        return null;
    }

    @Override
    public int getActiveStreamingServices(String appName, int subId) throws RemoteException {
        return 0;
    }

    @Override
    public Uri getPlaybackUri(String appName, int subId, String serviceId) throws RemoteException {
        return null;
    }

    @Override
    public void switchStreams(String appName, int subId, String oldServiceId, String newServiceId)
            throws RemoteException {
    }

    @Override
    public int getState(String appName, int subId, String serviceId) throws RemoteException {
        return 0;
    }

    @Override
    public void stopStreaming(String appName, int subId, String serviceId) throws RemoteException {
    }

    @Override
    public void disposeStream(String appName, int subId, String serviceId) throws RemoteException {
    }

    @Override
    public void dispose(String appName, int subId) throws RemoteException {
    }
}
