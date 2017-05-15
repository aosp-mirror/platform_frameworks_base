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
import android.telephony.mbms.MbmsException;

import java.util.List;

/**
 * @hide
 * TODO: future systemapi
 */
public class MbmsStreamingServiceBase extends IMbmsStreamingService.Stub {
    /**
     * Initialize streaming service for this app and subId, registering the listener.
     *
     * @param listener The callback to use to communicate with the app.
     * @param appName The app name as negotiated with the wireless carrier.
     * @param subscriptionId The subscription ID to use.
     * @return {@link MbmsException#SUCCESS}, {@link MbmsException#ERROR_ALREADY_INITIALIZED}, or
     *         {@link MbmsException#ERROR_APP_PERMISSIONS_NOT_GRANTED}
     */
    @Override
    public int initialize(IMbmsStreamingManagerCallback listener, String appName,
            int subscriptionId) throws RemoteException {
        return 0;
    }

    /**
     * Registers serviceClasses of interest with the appName/subId key.
     * Starts async fetching data on streaming services of matching classes to be reported
     * later via {@link IMbmsStreamingManagerCallback#streamingServicesUpdated(List)}
     *
     * Note that subsequent calls with the same uid, appName and subId will replace
     * the service class list.
     *
     * @param appName The app name as negotiated with the wireless carrier.
     * @param subscriptionId The subscription id to use.
     * @param serviceClasses The service classes that the app wishes to get info on. The strings
     *                       may contain arbitrary data as negotiated between the app and the
     *                       carrier.
     * @return One of {@link MbmsException#SUCCESS},
     *         {@link MbmsException#ERROR_MIDDLEWARE_NOT_BOUND},
     *         {@link MbmsException#ERROR_NOT_YET_INITIALIZED}, or
     *         {@link MbmsException#ERROR_CONCURRENT_SERVICE_LIMIT_REACHED}
     */
    @Override
    public int getStreamingServices(String appName, int subscriptionId,
            List<String> serviceClasses) throws RemoteException {
        return 0;
    }

    @Override
    public int startStreaming(String appName, int subId,
            String serviceId, IStreamingServiceCallback listener) throws RemoteException {
        return 0;
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
