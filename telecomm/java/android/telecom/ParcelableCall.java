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

package android.telecom;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.android.internal.telecom.IVideoProvider;

/**
 * Information about a call that is used between InCallService and Telecom.
 * @hide
 */
public final class ParcelableCall implements Parcelable {
    private final String mId;
    private final int mState;
    private final DisconnectCause mDisconnectCause;
    private final List<String> mCannedSmsResponses;
    private final int mCapabilities;
    private final int mProperties;
    private final long mConnectTimeMillis;
    private final Uri mHandle;
    private final int mHandlePresentation;
    private final String mCallerDisplayName;
    private final int mCallerDisplayNamePresentation;
    private final GatewayInfo mGatewayInfo;
    private final PhoneAccountHandle mAccountHandle;
    private final boolean mIsVideoCallProviderChanged;
    private final IVideoProvider mVideoCallProvider;
    private VideoCallImpl mVideoCall;
    private final String mParentCallId;
    private final List<String> mChildCallIds;
    private final StatusHints mStatusHints;
    private final int mVideoState;
    private final List<String> mConferenceableCallIds;
    private final Bundle mIntentExtras;
    private final Bundle mExtras;

    public ParcelableCall(
            String id,
            int state,
            DisconnectCause disconnectCause,
            List<String> cannedSmsResponses,
            int capabilities,
            int properties,
            long connectTimeMillis,
            Uri handle,
            int handlePresentation,
            String callerDisplayName,
            int callerDisplayNamePresentation,
            GatewayInfo gatewayInfo,
            PhoneAccountHandle accountHandle,
            boolean isVideoCallProviderChanged,
            IVideoProvider videoCallProvider,
            String parentCallId,
            List<String> childCallIds,
            StatusHints statusHints,
            int videoState,
            List<String> conferenceableCallIds,
            Bundle intentExtras,
            Bundle extras) {
        mId = id;
        mState = state;
        mDisconnectCause = disconnectCause;
        mCannedSmsResponses = cannedSmsResponses;
        mCapabilities = capabilities;
        mProperties = properties;
        mConnectTimeMillis = connectTimeMillis;
        mHandle = handle;
        mHandlePresentation = handlePresentation;
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = callerDisplayNamePresentation;
        mGatewayInfo = gatewayInfo;
        mAccountHandle = accountHandle;
        mIsVideoCallProviderChanged = isVideoCallProviderChanged;
        mVideoCallProvider = videoCallProvider;
        mParentCallId = parentCallId;
        mChildCallIds = childCallIds;
        mStatusHints = statusHints;
        mVideoState = videoState;
        mConferenceableCallIds = Collections.unmodifiableList(conferenceableCallIds);
        mIntentExtras = intentExtras;
        mExtras = extras;
    }

    /** The unique ID of the call. */
    public String getId() {
        return mId;
    }

    /** The current state of the call. */
    public int getState() {
        return mState;
    }

    /**
     * Reason for disconnection, as described by {@link android.telecomm.DisconnectCause}. Valid
     * when call state is {@link CallState#DISCONNECTED}.
     */
    public DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    /**
     * The set of possible text message responses when this call is incoming.
     */
    public List<String> getCannedSmsResponses() {
        return mCannedSmsResponses;
    }

    // Bit mask of actions a call supports, values are defined in {@link CallCapabilities}.
    public int getCapabilities() {
        return mCapabilities;
    }

    /** Bitmask of properties of the call. */
    public int getProperties() { return mProperties; }

    /** The time that the call switched to the active state. */
    public long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    /** The endpoint to which the call is connected. */
    public Uri getHandle() {
        return mHandle;
    }

    /**
     * The presentation requirements for the handle. See {@link TelecomManager} for valid values.
     */
    public int getHandlePresentation() {
        return mHandlePresentation;
    }

    /** The endpoint to which the call is connected. */
    public String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /**
     * The presentation requirements for the caller display name.
     * See {@link TelecomManager} for valid values.
     */
    public int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    /** Gateway information for the call. */
    public GatewayInfo getGatewayInfo() {
        return mGatewayInfo;
    }

    /** PhoneAccountHandle information for the call. */
    public PhoneAccountHandle getAccountHandle() {
        return mAccountHandle;
    }

    /**
     * Returns an object for remotely communicating through the video call provider's binder.

     * @return The video call.
     */
    public VideoCallImpl getVideoCallImpl() {
        if (mVideoCall == null && mVideoCallProvider != null) {
            try {
                mVideoCall = new VideoCallImpl(mVideoCallProvider);
            } catch (RemoteException ignored) {
                // Ignore RemoteException.
            }
        }

        return mVideoCall;
    }

    /**
     * The conference call to which this call is conferenced. Null if not conferenced.
     */
    public String getParentCallId() {
        return mParentCallId;
    }

    /**
     * The child call-IDs if this call is a conference call. Returns an empty list if this is not
     * a conference call or if the conference call contains no children.
     */
    public List<String> getChildCallIds() {
        return mChildCallIds;
    }

    public List<String> getConferenceableCallIds() {
        return mConferenceableCallIds;
    }

    /**
     * The status label and icon.
     *
     * @return Status hints.
     */
    public StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * The video state.
     * @return The video state of the call.
     */
    public int getVideoState() {
        return mVideoState;
    }

    /**
     * Any extras associated with this call.
     *
     * @return a bundle of extras
     */
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Extras passed in as part of the original call intent.
     *
     * @return The intent extras.
     */
    public Bundle getIntentExtras() {
        return mIntentExtras;
    }

    /**
     * Indicates to the receiver of the {@link ParcelableCall} whether a change has occurred in the
     * {@link android.telecom.InCallService.VideoCall} associated with this call.  Since
     * {@link #getVideoCall()} creates a new {@link VideoCallImpl}, it is useful to know whether
     * the provider has changed (which can influence whether it is accessed).
     *
     * @return {@code true} if the video call changed, {@code false} otherwise.
     */
    public boolean isVideoCallProviderChanged() {
        return mIsVideoCallProviderChanged;
    }

    /** Responsible for creating ParcelableCall objects for deserialized Parcels. */
    public static final Parcelable.Creator<ParcelableCall> CREATOR =
            new Parcelable.Creator<ParcelableCall> () {
        @Override
        public ParcelableCall createFromParcel(Parcel source) {
            ClassLoader classLoader = ParcelableCall.class.getClassLoader();
            String id = source.readString();
            int state = source.readInt();
            DisconnectCause disconnectCause = source.readParcelable(classLoader);
            List<String> cannedSmsResponses = new ArrayList<>();
            source.readList(cannedSmsResponses, classLoader);
            int capabilities = source.readInt();
            int properties = source.readInt();
            long connectTimeMillis = source.readLong();
            Uri handle = source.readParcelable(classLoader);
            int handlePresentation = source.readInt();
            String callerDisplayName = source.readString();
            int callerDisplayNamePresentation = source.readInt();
            GatewayInfo gatewayInfo = source.readParcelable(classLoader);
            PhoneAccountHandle accountHandle = source.readParcelable(classLoader);
            boolean isVideoCallProviderChanged = source.readByte() == 1;
            IVideoProvider videoCallProvider =
                    IVideoProvider.Stub.asInterface(source.readStrongBinder());
            String parentCallId = source.readString();
            List<String> childCallIds = new ArrayList<>();
            source.readList(childCallIds, classLoader);
            StatusHints statusHints = source.readParcelable(classLoader);
            int videoState = source.readInt();
            List<String> conferenceableCallIds = new ArrayList<>();
            source.readList(conferenceableCallIds, classLoader);
            Bundle intentExtras = source.readBundle(classLoader);
            Bundle extras = source.readBundle(classLoader);
            return new ParcelableCall(
                    id,
                    state,
                    disconnectCause,
                    cannedSmsResponses,
                    capabilities,
                    properties,
                    connectTimeMillis,
                    handle,
                    handlePresentation,
                    callerDisplayName,
                    callerDisplayNamePresentation,
                    gatewayInfo,
                    accountHandle,
                    isVideoCallProviderChanged,
                    videoCallProvider,
                    parentCallId,
                    childCallIds,
                    statusHints,
                    videoState,
                    conferenceableCallIds,
                    intentExtras,
                    extras);
        }

        @Override
        public ParcelableCall[] newArray(int size) {
            return new ParcelableCall[size];
        }
    };

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes ParcelableCall object into a Parcel. */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(mId);
        destination.writeInt(mState);
        destination.writeParcelable(mDisconnectCause, 0);
        destination.writeList(mCannedSmsResponses);
        destination.writeInt(mCapabilities);
        destination.writeInt(mProperties);
        destination.writeLong(mConnectTimeMillis);
        destination.writeParcelable(mHandle, 0);
        destination.writeInt(mHandlePresentation);
        destination.writeString(mCallerDisplayName);
        destination.writeInt(mCallerDisplayNamePresentation);
        destination.writeParcelable(mGatewayInfo, 0);
        destination.writeParcelable(mAccountHandle, 0);
        destination.writeByte((byte) (mIsVideoCallProviderChanged ? 1 : 0));
        destination.writeStrongBinder(
                mVideoCallProvider != null ? mVideoCallProvider.asBinder() : null);
        destination.writeString(mParentCallId);
        destination.writeList(mChildCallIds);
        destination.writeParcelable(mStatusHints, 0);
        destination.writeInt(mVideoState);
        destination.writeList(mConferenceableCallIds);
        destination.writeBundle(mIntentExtras);
        destination.writeBundle(mExtras);
    }

    @Override
    public String toString() {
        return String.format("[%s, parent:%s, children:%s]", mId, mParentCallId, mChildCallIds);
    }
}
