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
import android.os.RemoteException;
import android.telephony.DisconnectCause;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.android.internal.telecomm.IVideoProvider;

/**
 * Information about a call that is used between InCallService and Telecomm.
 * @hide
 */
public final class ParcelableCall implements Parcelable {
    private final String mId;
    private final int mState;
    private final int mDisconnectCauseCode;
    private final String mDisconnectCauseMsg;
    private final List<String> mCannedSmsResponses;
    private final int mCapabilities;
    private final long mConnectTimeMillis;
    private final Uri mHandle;
    private final int mHandlePresentation;
    private final String mCallerDisplayName;
    private final int mCallerDisplayNamePresentation;
    private final GatewayInfo mGatewayInfo;
    private final PhoneAccountHandle mAccountHandle;
    private final IVideoProvider mVideoCallProvider;
    private InCallService.VideoCall mVideoCall;
    private final String mParentCallId;
    private final List<String> mChildCallIds;
    private final StatusHints mStatusHints;
    private final int mVideoState;
    private final List<String> mConferenceableCallIds;
    private final Bundle mExtras;

    public ParcelableCall(
            String id,
            int state,
            int disconnectCauseCode,
            String disconnectCauseMsg,
            List<String> cannedSmsResponses,
            int capabilities,
            long connectTimeMillis,
            Uri handle,
            int handlePresentation,
            String callerDisplayName,
            int callerDisplayNamePresentation,
            GatewayInfo gatewayInfo,
            PhoneAccountHandle accountHandle,
            IVideoProvider videoCallProvider,
            String parentCallId,
            List<String> childCallIds,
            StatusHints statusHints,
            int videoState,
            List<String> conferenceableCallIds,
            Bundle extras) {
        mId = id;
        mState = state;
        mDisconnectCauseCode = disconnectCauseCode;
        mDisconnectCauseMsg = disconnectCauseMsg;
        mCannedSmsResponses = cannedSmsResponses;
        mCapabilities = capabilities;
        mConnectTimeMillis = connectTimeMillis;
        mHandle = handle;
        mHandlePresentation = handlePresentation;
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = callerDisplayNamePresentation;
        mGatewayInfo = gatewayInfo;
        mAccountHandle = accountHandle;
        mVideoCallProvider = videoCallProvider;
        mParentCallId = parentCallId;
        mChildCallIds = childCallIds;
        mStatusHints = statusHints;
        mVideoState = videoState;
        mConferenceableCallIds = Collections.unmodifiableList(conferenceableCallIds);
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
     * Reason for disconnection, values are defined in {@link DisconnectCause}. Valid when call
     * state is {@link CallState#DISCONNECTED}.
     */
    public int getDisconnectCauseCode() {
        return mDisconnectCauseCode;
    }

    /**
     * Further optional textual information about the reason for disconnection. Valid when call
     * state is {@link CallState#DISCONNECTED}.
     */
    public String getDisconnectCauseMsg() {
        return mDisconnectCauseMsg;
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

    /** The time that the call switched to the active state. */
    public long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    /** The endpoint to which the call is connected. */
    public Uri getHandle() {
        return mHandle;
    }

    /** The {@link PropertyPresentation} which controls how the handle is shown. */
    public int getHandlePresentation() {
        return mHandlePresentation;
    }

    /** The endpoint to which the call is connected. */
    public String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /** The {@link PropertyPresentation} which controls how the caller display name is shown. */
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
    public InCallService.VideoCall getVideoCall() {
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
     * Any extras to pass with the call
     *
     * @return a bundle of extras
     */
    public Bundle getExtras() {
        return mExtras;
    }

    /** Responsible for creating ParcelableCall objects for deserialized Parcels. */
    public static final Parcelable.Creator<ParcelableCall> CREATOR =
            new Parcelable.Creator<ParcelableCall> () {
        @Override
        public ParcelableCall createFromParcel(Parcel source) {
            ClassLoader classLoader = ParcelableCall.class.getClassLoader();
            String id = source.readString();
            int state = source.readInt();
            int disconnectCauseCode = source.readInt();
            String disconnectCauseMsg = source.readString();
            List<String> cannedSmsResponses = new ArrayList<>();
            source.readList(cannedSmsResponses, classLoader);
            int capabilities = source.readInt();
            long connectTimeMillis = source.readLong();
            Uri handle = source.readParcelable(classLoader);
            int handlePresentation = source.readInt();
            String callerDisplayName = source.readString();
            int callerDisplayNamePresentation = source.readInt();
            GatewayInfo gatewayInfo = source.readParcelable(classLoader);
            PhoneAccountHandle accountHandle = source.readParcelable(classLoader);
            IVideoProvider videoCallProvider =
                    IVideoProvider.Stub.asInterface(source.readStrongBinder());
            String parentCallId = source.readString();
            List<String> childCallIds = new ArrayList<>();
            source.readList(childCallIds, classLoader);
            StatusHints statusHints = source.readParcelable(classLoader);
            int videoState = source.readInt();
            List<String> conferenceableCallIds = new ArrayList<>();
            source.readList(conferenceableCallIds, classLoader);
            Bundle extras = source.readParcelable(classLoader);
            return new ParcelableCall(id, state, disconnectCauseCode, disconnectCauseMsg,
                    cannedSmsResponses, capabilities, connectTimeMillis, handle, handlePresentation,
                    callerDisplayName, callerDisplayNamePresentation, gatewayInfo,
                    accountHandle, videoCallProvider, parentCallId, childCallIds, statusHints,
                    videoState, conferenceableCallIds, extras);
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
        destination.writeInt(mDisconnectCauseCode);
        destination.writeString(mDisconnectCauseMsg);
        destination.writeList(mCannedSmsResponses);
        destination.writeInt(mCapabilities);
        destination.writeLong(mConnectTimeMillis);
        destination.writeParcelable(mHandle, 0);
        destination.writeInt(mHandlePresentation);
        destination.writeString(mCallerDisplayName);
        destination.writeInt(mCallerDisplayNamePresentation);
        destination.writeParcelable(mGatewayInfo, 0);
        destination.writeParcelable(mAccountHandle, 0);
        destination.writeStrongBinder(
                mVideoCallProvider != null ? mVideoCallProvider.asBinder() : null);
        destination.writeString(mParentCallId);
        destination.writeList(mChildCallIds);
        destination.writeParcelable(mStatusHints, 0);
        destination.writeInt(mVideoState);
        destination.writeList(mConferenceableCallIds);
        destination.writeParcelable(mExtras, 0);
    }

    @Override
    public String toString() {
        return String.format("[%s, parent:%s, children:%s]", mId, mParentCallId, mChildCallIds);
    }
}
