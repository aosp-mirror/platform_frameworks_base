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

package com.android.internal.widget;

import android.os.Parcel;
import android.os.Parcelable;
import android.service.gatekeeper.GateKeeperResponse;
import android.util.Slog;

/**
 * Response object for a ILockSettings credential verification request.
 * @hide
 */
public final class VerifyCredentialResponse implements Parcelable {

    public static final int RESPONSE_ERROR = -1;
    public static final int RESPONSE_OK = 0;
    public static final int RESPONSE_RETRY = 1;

    public static final VerifyCredentialResponse OK = new VerifyCredentialResponse();
    public static final VerifyCredentialResponse ERROR
            = new VerifyCredentialResponse(RESPONSE_ERROR, 0, null);
    private static final String TAG = "VerifyCredentialResponse";

    private int mResponseCode;
    private byte[] mPayload;
    private int mTimeout;

    public static final Parcelable.Creator<VerifyCredentialResponse> CREATOR
            = new Parcelable.Creator<VerifyCredentialResponse>() {
        @Override
        public VerifyCredentialResponse createFromParcel(Parcel source) {
            int responseCode = source.readInt();
            VerifyCredentialResponse response = new VerifyCredentialResponse(responseCode, 0, null);
            if (responseCode == RESPONSE_RETRY) {
                response.setTimeout(source.readInt());
            } else if (responseCode == RESPONSE_OK) {
                int size = source.readInt();
                if (size > 0) {
                    byte[] payload = new byte[size];
                    source.readByteArray(payload);
                    response.setPayload(payload);
                }
            }
            return response;
        }

        @Override
        public VerifyCredentialResponse[] newArray(int size) {
            return new VerifyCredentialResponse[size];
        }

    };

    public VerifyCredentialResponse() {
        mResponseCode = RESPONSE_OK;
        mPayload = null;
    }


    public VerifyCredentialResponse(byte[] payload) {
        mPayload = payload;
        mResponseCode = RESPONSE_OK;
    }

    public VerifyCredentialResponse(int timeout) {
        mTimeout = timeout;
        mResponseCode = RESPONSE_RETRY;
        mPayload = null;
    }

    private VerifyCredentialResponse(int responseCode, int timeout, byte[] payload) {
        mResponseCode = responseCode;
        mTimeout = timeout;
        mPayload = payload;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mResponseCode);
        if (mResponseCode == RESPONSE_RETRY) {
            dest.writeInt(mTimeout);
        } else if (mResponseCode == RESPONSE_OK) {
            if (mPayload != null) {
                dest.writeInt(mPayload.length);
                dest.writeByteArray(mPayload);
            } else {
                dest.writeInt(0);
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public byte[] getPayload() {
        return mPayload;
    }

    public int getTimeout() {
        return mTimeout;
    }

    public int getResponseCode() {
        return mResponseCode;
    }

    private void setTimeout(int timeout) {
        mTimeout = timeout;
    }

    private void setPayload(byte[] payload) {
        mPayload = payload;
    }

    public VerifyCredentialResponse stripPayload() {
        return new VerifyCredentialResponse(mResponseCode, mTimeout, new byte[0]);
    }

    public static VerifyCredentialResponse fromGateKeeperResponse(
            GateKeeperResponse gateKeeperResponse) {
        VerifyCredentialResponse response;
        int responseCode = gateKeeperResponse.getResponseCode();
        if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
            response = new VerifyCredentialResponse(gateKeeperResponse.getTimeout());
        } else if (responseCode == GateKeeperResponse.RESPONSE_OK) {
            byte[] token = gateKeeperResponse.getPayload();
            if (token == null) {
                // something's wrong if there's no payload with a challenge
                Slog.e(TAG, "verifyChallenge response had no associated payload");
                response = VerifyCredentialResponse.ERROR;
            } else {
                response = new VerifyCredentialResponse(token);
            }
        } else {
            response = VerifyCredentialResponse.ERROR;
        }
        return response;
    }
}
