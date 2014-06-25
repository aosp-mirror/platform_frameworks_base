/*
 * Copyright 2013, The Android Open Source Project
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

import java.util.Date;

/**
 * A parcelable holder class of Call information data. This class is intended for transferring call
 * information from Telecomm to call services and thus is read-only.
 * TODO(santoscordon): Need final public-facing comments in this file.
 */
public final class CallInfo implements Parcelable {

    /**
     * Unique identifier for the call.
     */
    private final String mId;

    /**
     * The state of the call.
     */
    private final CallState mState;

    /**
     * Endpoint to which the call is connected.
     * This could be the dialed value for outgoing calls or the caller id of incoming calls.
     */
    private final Uri mHandle;

    /**
     * Gateway information for the call.
     */
    private final GatewayInfo mGatewayInfo;

    /**
     * Subscription information for the call.
     */
    private final Subscription mSubscription;

    /**
     * Additional information that can be persisted.
     */
    private final Bundle mExtras;

    /** The descriptor for the call service currently routing this call. */
    private final CallServiceDescriptor mCurrentCallServiceDescriptor;

    public CallInfo(String id, CallState state, Uri handle) {
        this(id, state, handle, null, null, Bundle.EMPTY, null);
    }

    /**
     * Persists handle of the other party of this call.
     *
     * @param id The unique ID of the call.
     * @param state The state of the call.
     * @param handle The handle to the other party in this call.
     * @param gatewayInfo Gateway information pertaining to this call.
     * @param subscription Subscription information pertaining to this call.
     * @param extras Additional information that can be persisted.
     * @param currentCallServiceDescriptor The descriptor for the call service currently routing
     *         this call.
     *
     * @hide
     */
    public CallInfo(
            String id,
            CallState state,
            Uri handle,
            GatewayInfo gatewayInfo,
            Subscription subscription,
            Bundle extras,
            CallServiceDescriptor currentCallServiceDescriptor) {
        mId = id;
        mState = state;
        mHandle = handle;
        mGatewayInfo = gatewayInfo;
        mSubscription = subscription;
        mExtras = extras;
        mCurrentCallServiceDescriptor = currentCallServiceDescriptor;
    }

    public String getId() {
        return mId;
    }

    public CallState getState() {
        return mState;
    }

    public Uri getHandle() {
        return mHandle;
    }

    /**
     * @return The actual handle this call is associated with. This is used by call services to
     * correctly indicate in their UI what handle the user is actually calling, and by other
     * telecomm components that require the user-dialed handle to function.
     */
    public Uri getOriginalHandle() {
        if (mGatewayInfo != null) {
            return mGatewayInfo.getOriginalHandle();
        }
        return getHandle();
    }

    public GatewayInfo getGatewayInfo() {
        return mGatewayInfo;
    }

    public Subscription getSubscription() {
        return mSubscription;
    }

    public Bundle getExtras() {
        return mExtras;
    }

    public CallServiceDescriptor getCurrentCallServiceDescriptor() {
        return mCurrentCallServiceDescriptor;
    }

    /**
     * Responsible for creating CallInfo objects for deserialized Parcels.
     */
    public static final Parcelable.Creator<CallInfo> CREATOR = new Parcelable.Creator<CallInfo> () {
        @Override
        public CallInfo createFromParcel(Parcel source) {
            String id = source.readString();
            CallState state = CallState.valueOf(source.readString());
            Uri handle = Uri.CREATOR.createFromParcel(source);

            GatewayInfo gatewayInfo = readProviderInfoIfExists(source, GatewayInfo.CREATOR);
            Subscription subscription = readProviderInfoIfExists(source, Subscription.CREATOR);

            ClassLoader classLoader = CallInfo.class.getClassLoader();
            Bundle extras = source.readParcelable(classLoader);
            CallServiceDescriptor descriptor = source.readParcelable(classLoader);
            return new CallInfo(id, state, handle, gatewayInfo, subscription, extras, descriptor);
        }

        @Override
        public CallInfo[] newArray(int size) {
            return new CallInfo[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes CallInfo object into a serializeable Parcel.
     */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(mId);
        destination.writeString(mState.name());
        mHandle.writeToParcel(destination, 0);

        writeProviderInfoIfExists(destination, mGatewayInfo);
        writeProviderInfoIfExists(destination, mSubscription);

        destination.writeParcelable(mExtras, 0);
        destination.writeParcelable(mCurrentCallServiceDescriptor, 0);
    }

    /**
     * Helper function to write provider information (either GatewayInfo or Subscription) to
     * parcel. Will write a false byte if the information does not exist.
     */
    private void writeProviderInfoIfExists(Parcel destination, Parcelable provider) {
        if (provider != null) {
            destination.writeByte((byte) 1);
            provider.writeToParcel(destination, 0);
        } else {
            destination.writeByte((byte) 0);
        }
    }

    /**
     * Helper function to read provider information (either GatewayInfo or Subscription) from
     * parcel.
     */
    private static <T> T readProviderInfoIfExists(Parcel source,
            Parcelable.Creator<T> creator) {
        if (source.readByte() != 0) {
            return creator.createFromParcel(source);
        }
        return null;
    }
}
