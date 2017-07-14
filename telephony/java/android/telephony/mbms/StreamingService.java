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

import android.annotation.IntDef;
import android.net.Uri;
import android.os.RemoteException;
import android.telephony.mbms.vendor.IMbmsStreamingService;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
public class StreamingService {
    private static final String LOG_TAG = "MbmsStreamingService";

    /**
     * The state of a stream, reported via {@link StreamingServiceCallback#streamStateUpdated}
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_STOPPED, STATE_STARTED, STATE_STALLED})
    public @interface StreamingState {}
    public final static int STATE_STOPPED = 1;
    public final static int STATE_STARTED = 2;
    public final static int STATE_STALLED = 3;

    /**
     * The reason for a stream state change, reported via
     * {@link StreamingServiceCallback#streamStateUpdated}
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({REASON_BY_USER_REQUEST, REASON_END_OF_SESSION, REASON_FREQUENCY_CONFLICT,
            REASON_OUT_OF_MEMORY, REASON_NOT_CONNECTED_TO_HOMECARRIER_LTE,
            REASON_LEFT_MBMS_BROADCAST_AREA})
    public @interface StreamingStateChangeReason {}

    /**
     * State changed due to a call to {@link #stopStreaming()} or
     * {@link android.telephony.MbmsStreamingManager#startStreaming(StreamingServiceInfo, StreamingServiceCallback)}
     */
    public static final int REASON_BY_USER_REQUEST = 1;

    /**
     * State changed due to the streaming session ending at the carrier.
     */
    public static final int REASON_END_OF_SESSION = 2;

    /**
     * State changed due to a frequency conflict with another requested stream.
     */
    public static final int REASON_FREQUENCY_CONFLICT = 3;

    /**
     * State changed due to the middleware running out of memory
     */
    public static final int REASON_OUT_OF_MEMORY = 4;

    /**
     * State changed due to the device leaving the home carrier's LTE network.
     */
    public static final int REASON_NOT_CONNECTED_TO_HOMECARRIER_LTE = 5;

    /**
     * State changed due to the device leaving the where this stream is being broadcast.
     */
    public static final int REASON_LEFT_MBMS_BROADCAST_AREA = 5;

    /**
     * The method of transmission currently used for a stream,
     * reported via {@link StreamingServiceCallback#streamMethodUpdated}
     */
    public final static int BROADCAST_METHOD = 1;
    public final static int UNICAST_METHOD   = 2;

    private final int mSubscriptionId;
    private final StreamingServiceInfo mServiceInfo;
    private final IStreamingServiceCallback mCallback;

    private IMbmsStreamingService mService;
    /**
     * @hide
     */
    public StreamingService(int subscriptionId,
            IMbmsStreamingService service,
            StreamingServiceInfo streamingServiceInfo,
            IStreamingServiceCallback callback) {
        mSubscriptionId = subscriptionId;
        mService = service;
        mServiceInfo = streamingServiceInfo;
        mCallback = callback;
    }

    /**
     * Retreive the Uri used to play this stream.
     *
     * This may throw a {@link MbmsException} with the error code
     * {@link MbmsException#ERROR_MIDDLEWARE_LOST}
     *
     * May also throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * @return The {@link Uri} to pass to the streaming client.
     */
    public Uri getPlaybackUri() throws MbmsException {
        if (mService == null) {
            throw new IllegalStateException("No streaming service attached");
        }

        try {
            return mService.getPlaybackUri(mSubscriptionId, mServiceInfo.getServiceId());
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
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
     * {@link MbmsException#ERROR_MIDDLEWARE_LOST}
     *
     * May also throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     */
    public void stopStreaming() throws MbmsException {
        if (mService == null) {
            throw new IllegalStateException("No streaming service attached");
        }

        try {
            mService.stopStreaming(mSubscriptionId, mServiceInfo.getServiceId());
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        }
    }

    /**
     * Disposes of this stream. Further operations on this object will fail with an
     * {@link IllegalStateException}.
     *
     * This may throw a {@link MbmsException} with the error code
     * {@link MbmsException#ERROR_MIDDLEWARE_LOST}
     * May also throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     */
    public void dispose() throws MbmsException {
        if (mService == null) {
            throw new IllegalStateException("No streaming service attached");
        }

        try {
            mService.disposeStream(mSubscriptionId, mServiceInfo.getServiceId());
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_LOST);
        } finally {
            mService = null;
        }
    }
}

