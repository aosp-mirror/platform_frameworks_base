/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telecomm;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Simple data container encapsulating a request to some entity to
 * create a new {@link Connection}.
 */
public final class ConnectionRequest implements Parcelable {

    // TODO: Token to limit recursive invocations
    private final PhoneAccountHandle mAccountHandle;
    private final Uri mHandle;
    private final int mHandlePresentation;
    private final Bundle mExtras;
    private final int mVideoState;

    /**
     * @param accountHandle The accountHandle which should be used to place the call.
     * @param handle The handle (e.g., phone number) to which the {@link Connection} is to connect.
     * @param handlePresentation The {@link PropertyPresentation} which controls how the handle
     *         is shown.
     * @param extras Application-specific extra data.
     * @param videoState Determines the video state for the connection.
     */
    public ConnectionRequest(
            PhoneAccountHandle accountHandle,
            Uri handle,
            int handlePresentation,
            Bundle extras,
            int videoState) {
        mAccountHandle = accountHandle;
        mHandle = handle;
        mHandlePresentation = handlePresentation;
        mExtras = extras;
        mVideoState = videoState;
    }

    private ConnectionRequest(Parcel in) {
        mAccountHandle = in.readParcelable(getClass().getClassLoader());
        mHandle = in.readParcelable(getClass().getClassLoader());
        mHandlePresentation = in.readInt();
        mExtras = in.readParcelable(getClass().getClassLoader());
        mVideoState = in.readInt();
    }

    /**
     * The account which should be used to place the call.
     */
    public PhoneAccountHandle getAccountHandle() { return mAccountHandle; }

    /**
     * The handle (e.g., phone number) to which the {@link Connection} is to connect.
     */
    public Uri getHandle() { return mHandle; }

    /**
     * The {@link PropertyPresentation} which controls how the handle is shown.
     */
    public int getHandlePresentation() { return mHandlePresentation; }

    /**
     * Application-specific extra data. Used for passing back information from an incoming
     * call {@code Intent}, and for any proprietary extensions arranged between a client
     * and servant {@code ConnectionService} which agree on a vocabulary for such data.
     */
    public Bundle getExtras() { return mExtras; }

    /**
     * Describes the video states supported by the client requesting the connection.
     * Valid values: {@link VideoProfile.VideoState#AUDIO_ONLY},
     * {@link VideoProfile.VideoState#BIDIRECTIONAL},
     * {@link VideoProfile.VideoState#TX_ENABLED},
     * {@link VideoProfile.VideoState#RX_ENABLED}.
     *
     * @return The video state for the connection.
     */
    public int getVideoState() {
        return mVideoState;
    }

    @Override
    public String toString() {
        return String.format("ConnectionRequest %s %s",
                mHandle == null
                        ? Uri.EMPTY
                        : Connection.toLogSafePhoneNumber(mHandle.toString()),
                mExtras == null ? "" : mExtras);
    }

    public static final Creator<ConnectionRequest> CREATOR = new Creator<ConnectionRequest> () {
        @Override
        public ConnectionRequest createFromParcel(Parcel source) {
            return new ConnectionRequest(source);
        }

        @Override
        public ConnectionRequest[] newArray(int size) {
            return new ConnectionRequest[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeParcelable(mAccountHandle, 0);
        destination.writeParcelable(mHandle, 0);
        destination.writeInt(mHandlePresentation);
        destination.writeParcelable(mExtras, 0);
        destination.writeInt(mVideoState);
    }
}
