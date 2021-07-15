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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.gatekeeper.GateKeeperResponse;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Response object for a ILockSettings credential verification request.
 * @hide
 */
public final class VerifyCredentialResponse implements Parcelable {

    public static final int RESPONSE_ERROR = -1;
    public static final int RESPONSE_OK = 0;
    public static final int RESPONSE_RETRY = 1;
    @IntDef({RESPONSE_ERROR,
            RESPONSE_OK,
            RESPONSE_RETRY})
    @Retention(RetentionPolicy.SOURCE)
    @interface ResponseCode {}

    public static final VerifyCredentialResponse OK = new VerifyCredentialResponse.Builder()
            .build();
    public static final VerifyCredentialResponse ERROR = fromError();
    private static final String TAG = "VerifyCredentialResponse";

    private final @ResponseCode int mResponseCode;
    private final int mTimeout;
    @Nullable private final byte[] mGatekeeperHAT;
    private final long mGatekeeperPasswordHandle;

    public static final Parcelable.Creator<VerifyCredentialResponse> CREATOR
            = new Parcelable.Creator<VerifyCredentialResponse>() {
        @Override
        public VerifyCredentialResponse createFromParcel(Parcel source) {
            final @ResponseCode int responseCode = source.readInt();
            final int timeout = source.readInt();
            final byte[] gatekeeperHAT = source.createByteArray();
            long gatekeeperPasswordHandle = source.readLong();

            return new VerifyCredentialResponse(responseCode, timeout, gatekeeperHAT,
                    gatekeeperPasswordHandle);
        }

        @Override
        public VerifyCredentialResponse[] newArray(int size) {
            return new VerifyCredentialResponse[size];
        }
    };

    public static class Builder {
        @Nullable private byte[] mGatekeeperHAT;
        private long mGatekeeperPasswordHandle;

        /**
         * @param gatekeeperHAT Gatekeeper HardwareAuthToken, minted upon successful authentication.
         */
        public Builder setGatekeeperHAT(byte[] gatekeeperHAT) {
            mGatekeeperHAT = gatekeeperHAT;
            return this;
        }

        public Builder setGatekeeperPasswordHandle(long gatekeeperPasswordHandle) {
            mGatekeeperPasswordHandle = gatekeeperPasswordHandle;
            return this;
        }

        /**
         * Builds a VerifyCredentialResponse with {@link #RESPONSE_OK} and any other parameters
         * that were preveiously set.
         * @return
         */
        public VerifyCredentialResponse build() {
            return new VerifyCredentialResponse(RESPONSE_OK,
                    0 /* timeout */,
                    mGatekeeperHAT,
                    mGatekeeperPasswordHandle);
        }
    }

    /**
     * Since timeouts are always an error, provide a way to create the VerifyCredentialResponse
     * object directly. None of the other fields (Gatekeeper HAT, Gatekeeper Password, etc)
     * are valid in this case. Similarly, the response code will always be
     * {@link #RESPONSE_RETRY}.
     */
    public static VerifyCredentialResponse fromTimeout(int timeout) {
        return new VerifyCredentialResponse(RESPONSE_RETRY,
                timeout,
                null /* gatekeeperHAT */,
                0L /* gatekeeperPasswordHandle */);
    }

    /**
     * Since error (incorrect password) should never result in any of the other fields from
     * being populated, provide a default method to return a VerifyCredentialResponse.
     */
    public static VerifyCredentialResponse fromError() {
        return new VerifyCredentialResponse(RESPONSE_ERROR,
                0 /* timeout */,
                null /* gatekeeperHAT */,
                0L /* gatekeeperPasswordHandle */);
    }

    private VerifyCredentialResponse(@ResponseCode int responseCode, int timeout,
            @Nullable byte[] gatekeeperHAT, long gatekeeperPasswordHandle) {
        mResponseCode = responseCode;
        mTimeout = timeout;
        mGatekeeperHAT = gatekeeperHAT;
        mGatekeeperPasswordHandle = gatekeeperPasswordHandle;
    }

    public VerifyCredentialResponse stripPayload() {
        return new VerifyCredentialResponse(mResponseCode, mTimeout,
                null /* gatekeeperHAT */, 0L /* gatekeeperPasswordHandle */);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mResponseCode);
        dest.writeInt(mTimeout);
        dest.writeByteArray(mGatekeeperHAT);
        dest.writeLong(mGatekeeperPasswordHandle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Nullable
    public byte[] getGatekeeperHAT() {
        return mGatekeeperHAT;
    }

    public long getGatekeeperPasswordHandle() {
        return mGatekeeperPasswordHandle;
    }

    public boolean containsGatekeeperPasswordHandle() {
        return mGatekeeperPasswordHandle != 0L;
    }

    public int getTimeout() {
        return mTimeout;
    }

    public @ResponseCode int getResponseCode() {
        return mResponseCode;
    }

    public boolean isMatched() {
        return mResponseCode == RESPONSE_OK;
    }

    @Override
    public String toString() {
        return "Response: " + mResponseCode
                + ", GK HAT: " + (mGatekeeperHAT != null)
                + ", GK PW: " + (mGatekeeperPasswordHandle != 0L);
    }

    public static VerifyCredentialResponse fromGateKeeperResponse(
            GateKeeperResponse gateKeeperResponse) {
        int responseCode = gateKeeperResponse.getResponseCode();
        if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
            return fromTimeout(gateKeeperResponse.getTimeout());
        } else if (responseCode == GateKeeperResponse.RESPONSE_OK) {
            byte[] token = gateKeeperResponse.getPayload();
            if (token == null) {
                // something's wrong if there's no payload with a challenge
                Slog.e(TAG, "verifyChallenge response had no associated payload");
                return fromError();
            } else {
                return new VerifyCredentialResponse.Builder().setGatekeeperHAT(token).build();
            }
        } else {
            return fromError();
        }
    }
}
