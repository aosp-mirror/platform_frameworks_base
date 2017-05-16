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
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.telephony.mbms.vendor.IMbmsStreamingService;
import android.util.Log;

/**
 * @hide
 */
public class StreamingService {
    private static final String LOG_TAG = "MbmsStreamingService";
    public final static int STATE_STOPPED = 1;
    public final static int STATE_STARTED = 2;
    public final static int STATE_STALLED = 3;

    private final String mAppName;
    private final int mSubscriptionId;
    private final IMbmsStreamingService mService;
    private final StreamingServiceInfo mServiceInfo;
    private final IStreamingServiceCallback mCallback;
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
     * {@link MbmsException#ERROR_UNKNOWN_REMOTE_EXCEPTION}
     * @return The {@link Uri} to pass to the streaming client.
     */
    public Uri getPlaybackUri() throws MbmsException {
        try {
            return mService.getPlaybackUri(mAppName, mSubscriptionId, mServiceInfo.getServiceId());
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Caught remote exception calling getPlaybackUri: " + e);
            throw new MbmsException(MbmsException.ERROR_UNKNOWN_REMOTE_EXCEPTION);
        }
    }

    /**
     * Retreive the info for this StreamingService.
     */
    public StreamingServiceInfo getInfo() {
        return mServiceInfo;
    }

    /**
     * Stop streaming this service.  Terminal.
     *
     * This may throw a RemoteException.
     */
    public void stopStreaming() {
    }

    public void dispose() throws MbmsException {
        try {
            mService.disposeStream(mAppName, mSubscriptionId, mServiceInfo.getServiceId());
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Caught remote exception calling disposeStream: " + e);
            throw new MbmsException(MbmsException.ERROR_UNKNOWN_REMOTE_EXCEPTION);
        }
    }
}

