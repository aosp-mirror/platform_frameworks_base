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

/**
 * @hide
 */
public class StreamingService {

    public final static int STATE_STOPPED = 1;
    public final static int STATE_STARTED = 2;
    public final static int STATE_STALLED = 3;

    /**
     */
    StreamingService(StreamingServiceInfo streamingServiceInfo,
            IStreamingServiceCallback listener) {
    }

    /**
     * Retreive the Uri used to play this stream.
     *
     * This may throw a RemoteException.
     */
    public Uri getPlaybackUri() {
        return null;
    }

    /**
     * Retreive the info for this StreamingService.
     */
    public StreamingServiceInfo getInfo() {
        return null;
    }

    /**
     * Retreive the current state of this stream.
     *
     * This may throw a RemoteException.
     */
    public int getState() {
        return STATE_STOPPED;
    }

    /**
     * Stop streaming this service.  Terminal.
     *
     * This may throw a RemoteException.
     */
    public void stopStreaming() {
    }

    public void dispose() {
    }

    public static final Parcelable.Creator<StreamingService> CREATOR =
            new Parcelable.Creator<StreamingService>() {
        @Override
        public StreamingService createFromParcel(Parcel in) {
            return new StreamingService(in);
        }

        @Override
        public StreamingService[] newArray(int size) {
            return new StreamingService[size];
        }
    };

    private StreamingService(Parcel in) {
    }

    public void writeToParcel(Parcel dest, int flags) {
    }

    public int describeContents() {
        return 0;
    }
}

