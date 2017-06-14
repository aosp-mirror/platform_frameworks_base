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

package android.telephony.mbms;

import android.net.Uri;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.telephony.mbms.vendor.IMbmsStreamingService;
import android.util.Log;

/**
 * @hide
 */
public class StreamingService {
    private static final String LOG_TAG = "MbmsStreamingService";

    /**
     * The state of a stream, reported via {@link StreamingServiceCallback#streamStateUpdated}
     */
    public final static int STATE_STOPPED = 1;
    public final static int STATE_STARTED = 2;
    public final static int STATE_STALLED = 3;

    /**
     * The method of transmission currently used for a stream,
     * reported via {@link StreamingServiceCallback#streamMethodUpdated}
     */
    public final static int BROADCAST_METHOD = 1;
    public final static int UNICAST_METHOD   = 2;

    private final String mAppName;
    private final int mSubscriptionId;
    private final StreamingServiceInfo mServiceInfo;
    private final IStreamingServiceCallback mCallback;

    private IMbmsStreamingService mService;
    /**
     * @hide
     */
    public StreamingService(String appName,
            int subscriptionId,
            IMbmsStreamingService service,
            StreamingServiceInfo streamingServiceInfo,
            IStreamingServiceCallback callback) {
        mAppName = appName;
        mSubscriptionId = subscriptionId;
        mService = service;
        mServiceInfo = streamingServiceInfo;
        mCallback = callback;
    }

    /**
     * Retreive the Uri used to play this stream.
     *
     * This may throw a {@link MbmsException} with the error code
     * {@link MbmsException#ERROR_SERVICE_LOST}
     *
     * @return The {@link Uri} to pass to the streaming client.
     */
    public Uri getPlaybackUri() throws MbmsException {
        if (mService == null) {
            throw new IllegalStateException("No streaming service attached");
        }

        try {
            return mService.getPlaybackUri(mAppName, mSubscriptionId, mServiceInfo.getServiceId());
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            throw new MbmsException(MbmsException.ERROR_SERVICE_LOST);
        }
    }

    /**
     * Retreive the info for this StreamingService.
     */
    public StreamingServiceInfo getInfo() {
        return mServiceInfo;
    }

    /**
     * Stop streaming this service.
     * This may throw a {@link MbmsException} with the error code
     * {@link MbmsException#ERROR_SERVICE_LOST}
     */
    public void stopStreaming() throws MbmsException {
        if (mService == null) {
            throw new IllegalStateException("No streaming service attached");
        }

        try {
            mService.stopStreaming(mAppName, mSubscriptionId, mServiceInfo.getServiceId());
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            throw new MbmsException(MbmsException.ERROR_SERVICE_LOST);
        }
    }

    public void dispose() throws MbmsException {
        if (mService == null) {
            throw new IllegalStateException("No streaming service attached");
        }

        try {
            mService.disposeStream(mAppName, mSubscriptionId, mServiceInfo.getServiceId());
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            throw new MbmsException(MbmsException.ERROR_SERVICE_LOST);
        }
    }
}

