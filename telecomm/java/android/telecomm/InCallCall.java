/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import android.telephony.DisconnectCause;

import java.util.Date;
import java.util.UUID;

/**
 * Information about a call that is used between InCallService and Telecomm.
 */
public final class InCallCall implements Parcelable {
    private final String mId;
    private final CallState mState;
    private final int mDisconnectCause;
    private final int mCapabilities;
    private final long mConnectTimeMillis;
    private final Uri mHandle;
    private final GatewayInfo mGatewayInfo;
    private final CallServiceDescriptor mCurrentCallServiceDescriptor;
    private final CallServiceDescriptor mHandoffCallServiceDescriptor;

    /** @hide */
    public InCallCall(
            String id,
            CallState state,
            int disconnectCause,
            int capabilities,
            long connectTimeMillis,
            Uri handle,
            GatewayInfo gatewayInfo,
            CallServiceDescriptor descriptor,
            CallServiceDescriptor handoffDescriptor) {
        mId = id;
        mState = state;
        mDisconnectCause = disconnectCause;
        mCapabilities = capabilities;
        mConnectTimeMillis = connectTimeMillis;
        mHandle = handle;
        mGatewayInfo = gatewayInfo;
        mCurrentCallServiceDescriptor = descriptor;
        mHandoffCallServiceDescriptor = handoffDescriptor;
    }

    /** The unique ID of the call. */
    public String getId() {
        return mId;
    }

    /** The current state of the call. */
    public CallState getState() {
        return mState;
    }

    /**
     * Reason for disconnection, values are defined in {@link DisconnectCause}. Valid when call
     * state is {@link CallState#DISCONNECTED}.
     */
    public int getDisconnectCause() {
        return mDisconnectCause;
    }

    // Bit mask of actions a call supports, values are defined in {@link CallCapabilities}.
    public int getCapabilities() {
        return mCapabilities;
    }

    /** The time that the call switched to the active state. */
    public long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    /** The endpoint to which the call is connected. */
    public Uri getHandle() {
        return mHandle;
    }

    /** Gateway information for the call. */
    public GatewayInfo getGatewayInfo() {
        return mGatewayInfo;
    }

    /** The descriptor for the call service currently routing this call. */
    public CallServiceDescriptor getCurrentCallServiceDescriptor() {
        return mCurrentCallServiceDescriptor;
    }

    /**
     * The descriptor for the call service that this call is being switched to, null if handoff is
     * not in progress.
     */
    public CallServiceDescriptor getHandoffCallServiceDescriptor() {
        return mHandoffCallServiceDescriptor;
    }

    /** Responsible for creating InCallCall objects for deserialized Parcels. */
    public static final Parcelable.Creator<InCallCall> CREATOR =
            new Parcelable.Creator<InCallCall> () {
        @Override
        public InCallCall createFromParcel(Parcel source) {
            String id = source.readString();
            CallState state = CallState.valueOf(source.readString());
            int disconnectCause = source.readInt();
            int capabilities = source.readInt();
            long connectTimeMillis = source.readLong();
            ClassLoader classLoader = InCallCall.class.getClassLoader();
            Uri handle = source.readParcelable(classLoader);
            GatewayInfo gatewayInfo = source.readParcelable(classLoader);
            CallServiceDescriptor descriptor = source.readParcelable(classLoader);
            CallServiceDescriptor handoffDescriptor = source.readParcelable(classLoader);
            return new InCallCall(id, state, disconnectCause, capabilities, connectTimeMillis,
                    handle, gatewayInfo, descriptor, handoffDescriptor);
        }

        @Override
        public InCallCall[] newArray(int size) {
            return new InCallCall[size];
        }
    };

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes InCallCall object into a Parcel. */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(mId);
        destination.writeString(mState.name());
        destination.writeInt(mDisconnectCause);
        destination.writeInt(mCapabilities);
        destination.writeLong(mConnectTimeMillis);
        destination.writeParcelable(mHandle, 0);
        destination.writeParcelable(mGatewayInfo, 0);
        destination.writeParcelable(mCurrentCallServiceDescriptor, 0);
        destination.writeParcelable(mHandoffCallServiceDescriptor, 0);
    }
}
