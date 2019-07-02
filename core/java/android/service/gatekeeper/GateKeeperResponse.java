/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.service.gatekeeper;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Response object for a GateKeeper verification request.
 * @hide
 */
public final class GateKeeperResponse implements Parcelable {

    public static final int RESPONSE_ERROR = -1;
    public static final int RESPONSE_OK = 0;
    public static final int RESPONSE_RETRY = 1;

    private final int mResponseCode;

    private int mTimeout;
    private byte[] mPayload;
    private boolean mShouldReEnroll;

    /** Default constructor for response with generic response code **/
    private GateKeeperResponse(int responseCode) {
        mResponseCode = responseCode;
    }

    @VisibleForTesting
    public static GateKeeperResponse createGenericResponse(int responseCode) {
        return new GateKeeperResponse(responseCode);
    }

    private static GateKeeperResponse createRetryResponse(int timeout) {
        GateKeeperResponse response = new GateKeeperResponse(RESPONSE_RETRY);
        response.mTimeout = timeout;
        return response;
    }

    @VisibleForTesting
    public static GateKeeperResponse createOkResponse(byte[] payload, boolean shouldReEnroll) {
        GateKeeperResponse response = new GateKeeperResponse(RESPONSE_OK);
        response.mPayload = payload;
        response.mShouldReEnroll = shouldReEnroll;
        return response;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<GateKeeperResponse> CREATOR
            = new Parcelable.Creator<GateKeeperResponse>() {
        @Override
        public GateKeeperResponse createFromParcel(Parcel source) {
            int responseCode = source.readInt();
            final GateKeeperResponse response;
            if (responseCode == RESPONSE_RETRY) {
                response = createRetryResponse(source.readInt());
            } else if (responseCode == RESPONSE_OK) {
                final boolean shouldReEnroll = source.readInt() == 1;
                byte[] payload = null;
                int size = source.readInt();
                if (size > 0) {
                    payload = new byte[size];
                    source.readByteArray(payload);
                }
                response = createOkResponse(payload, shouldReEnroll);
            } else {
                response = createGenericResponse(responseCode);
            }
            return response;
        }

        @Override
        public GateKeeperResponse[] newArray(int size) {
            return new GateKeeperResponse[size];
        }

    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mResponseCode);
        if (mResponseCode == RESPONSE_RETRY) {
            dest.writeInt(mTimeout);
        } else if (mResponseCode == RESPONSE_OK) {
            dest.writeInt(mShouldReEnroll ? 1 : 0);
            if (mPayload != null) {
                dest.writeInt(mPayload.length);
                dest.writeByteArray(mPayload);
            } else {
                dest.writeInt(0);
            }
        }
    }

    public byte[] getPayload() {
        return mPayload;
    }

    public int getTimeout() {
        return mTimeout;
    }

    public boolean getShouldReEnroll() {
        return mShouldReEnroll;
    }

    public int getResponseCode() {
        return mResponseCode;
    }
}
