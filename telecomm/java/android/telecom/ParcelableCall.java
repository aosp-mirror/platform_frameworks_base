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

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.telecom.Call.Details.CallDirection;

import com.android.internal.telecom.IVideoProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Information about a call that is used between InCallService and Telecom.
 * @hide
 */
public final class ParcelableCall implements Parcelable {

    public static class ParcelableCallBuilder {
        private String mId;
        private int mState;
        private DisconnectCause mDisconnectCause;
        private List<String> mCannedSmsResponses;
        private int mCapabilities;
        private int mProperties;
        private int mSupportedAudioRoutes;
        private long mConnectTimeMillis;
        private Uri mHandle;
        private int mHandlePresentation;
        private String mCallerDisplayName;
        private int mCallerDisplayNamePresentation;
        private GatewayInfo mGatewayInfo;
        private PhoneAccountHandle mAccountHandle;
        private boolean mIsVideoCallProviderChanged;
        private IVideoProvider mVideoCallProvider;
        private boolean mIsRttCallChanged;
        private ParcelableRttCall mRttCall;
        private String mParentCallId;
        private List<String> mChildCallIds;
        private StatusHints mStatusHints;
        private int mVideoState;
        private List<String> mConferenceableCallIds;
        private Bundle mIntentExtras;
        private Bundle mExtras;
        private long mCreationTimeMillis;
        private int mCallDirection;
        private int mCallerNumberVerificationStatus;
        private String mContactDisplayName;
        private String mActiveChildCallId;

        public ParcelableCallBuilder setId(String id) {
            mId = id;
            return this;
        }

        public ParcelableCallBuilder setState(int state) {
            mState = state;
            return this;
        }

        public ParcelableCallBuilder setDisconnectCause(DisconnectCause disconnectCause) {
            mDisconnectCause = disconnectCause;
            return this;
        }

        public ParcelableCallBuilder setCannedSmsResponses(List<String> cannedSmsResponses) {
            mCannedSmsResponses = cannedSmsResponses;
            return this;
        }

        public ParcelableCallBuilder setCapabilities(int capabilities) {
            mCapabilities = capabilities;
            return this;
        }

        public ParcelableCallBuilder setProperties(int properties) {
            mProperties = properties;
            return this;
        }

        public ParcelableCallBuilder setSupportedAudioRoutes(int supportedAudioRoutes) {
            mSupportedAudioRoutes = supportedAudioRoutes;
            return this;
        }

        public ParcelableCallBuilder setConnectTimeMillis(long connectTimeMillis) {
            mConnectTimeMillis = connectTimeMillis;
            return this;
        }

        public ParcelableCallBuilder setHandle(Uri handle) {
            mHandle = handle;
            return this;
        }

        public ParcelableCallBuilder setHandlePresentation(int handlePresentation) {
            mHandlePresentation = handlePresentation;
            return this;
        }

        public ParcelableCallBuilder setCallerDisplayName(String callerDisplayName) {
            mCallerDisplayName = callerDisplayName;
            return this;
        }

        public ParcelableCallBuilder setCallerDisplayNamePresentation(
                int callerDisplayNamePresentation) {
            mCallerDisplayNamePresentation = callerDisplayNamePresentation;
            return this;
        }

        public ParcelableCallBuilder setGatewayInfo(GatewayInfo gatewayInfo) {
            mGatewayInfo = gatewayInfo;
            return this;
        }

        public ParcelableCallBuilder setAccountHandle(PhoneAccountHandle accountHandle) {
            mAccountHandle = accountHandle;
            return this;
        }

        public ParcelableCallBuilder setIsVideoCallProviderChanged(
                boolean isVideoCallProviderChanged) {
            mIsVideoCallProviderChanged = isVideoCallProviderChanged;
            return this;
        }

        public ParcelableCallBuilder setVideoCallProvider(IVideoProvider videoCallProvider) {
            mVideoCallProvider = videoCallProvider;
            return this;
        }

        public ParcelableCallBuilder setIsRttCallChanged(boolean isRttCallChanged) {
            mIsRttCallChanged = isRttCallChanged;
            return this;
        }

        public ParcelableCallBuilder setRttCall(ParcelableRttCall rttCall) {
            mRttCall = rttCall;
            return this;
        }

        public ParcelableCallBuilder setParentCallId(String parentCallId) {
            mParentCallId = parentCallId;
            return this;
        }

        public ParcelableCallBuilder setChildCallIds(List<String> childCallIds) {
            mChildCallIds = childCallIds;
            return this;
        }

        public ParcelableCallBuilder setStatusHints(StatusHints statusHints) {
            mStatusHints = statusHints;
            return this;
        }

        public ParcelableCallBuilder setVideoState(int videoState) {
            mVideoState = videoState;
            return this;
        }

        public ParcelableCallBuilder setConferenceableCallIds(
                List<String> conferenceableCallIds) {
            mConferenceableCallIds = conferenceableCallIds;
            return this;
        }

        public ParcelableCallBuilder setIntentExtras(Bundle intentExtras) {
            mIntentExtras = intentExtras;
            return this;
        }

        public ParcelableCallBuilder setExtras(Bundle extras) {
            mExtras = extras;
            return this;
        }

        public ParcelableCallBuilder setCreationTimeMillis(long creationTimeMillis) {
            mCreationTimeMillis = creationTimeMillis;
            return this;
        }

        public ParcelableCallBuilder setCallDirection(int callDirection) {
            mCallDirection = callDirection;
            return this;
        }

        public ParcelableCallBuilder setCallerNumberVerificationStatus(
                int callerNumberVerificationStatus) {
            mCallerNumberVerificationStatus = callerNumberVerificationStatus;
            return this;
        }

        public ParcelableCallBuilder setContactDisplayName(String contactDisplayName) {
            mContactDisplayName = contactDisplayName;
            return this;
        }

        public ParcelableCallBuilder setActiveChildCallId(String activeChildCallId) {
            mActiveChildCallId = activeChildCallId;
            return this;
        }

        public ParcelableCall createParcelableCall() {
            return new ParcelableCall(
                    mId,
                    mState,
                    mDisconnectCause,
                    mCannedSmsResponses,
                    mCapabilities,
                    mProperties,
                    mSupportedAudioRoutes,
                    mConnectTimeMillis,
                    mHandle,
                    mHandlePresentation,
                    mCallerDisplayName,
                    mCallerDisplayNamePresentation,
                    mGatewayInfo,
                    mAccountHandle,
                    mIsVideoCallProviderChanged,
                    mVideoCallProvider,
                    mIsRttCallChanged,
                    mRttCall,
                    mParentCallId,
                    mChildCallIds,
                    mStatusHints,
                    mVideoState,
                    mConferenceableCallIds,
                    mIntentExtras,
                    mExtras,
                    mCreationTimeMillis,
                    mCallDirection,
                    mCallerNumberVerificationStatus,
                    mContactDisplayName,
                    mActiveChildCallId);
        }

        public static ParcelableCallBuilder fromParcelableCall(ParcelableCall parcelableCall) {
            ParcelableCallBuilder newBuilder = new ParcelableCallBuilder();
            newBuilder.mId = parcelableCall.mId;
            newBuilder.mState = parcelableCall.mState;
            newBuilder.mDisconnectCause = parcelableCall.mDisconnectCause;
            newBuilder.mCannedSmsResponses = parcelableCall.mCannedSmsResponses;
            newBuilder.mCapabilities = parcelableCall.mCapabilities;
            newBuilder.mProperties = parcelableCall.mProperties;
            newBuilder.mSupportedAudioRoutes = parcelableCall.mSupportedAudioRoutes;
            newBuilder.mConnectTimeMillis = parcelableCall.mConnectTimeMillis;
            newBuilder.mHandle = parcelableCall.mHandle;
            newBuilder.mHandlePresentation = parcelableCall.mHandlePresentation;
            newBuilder.mCallerDisplayName = parcelableCall.mCallerDisplayName;
            newBuilder.mCallerDisplayNamePresentation =
                    parcelableCall.mCallerDisplayNamePresentation;
            newBuilder.mGatewayInfo = parcelableCall.mGatewayInfo;
            newBuilder.mAccountHandle = parcelableCall.mAccountHandle;
            newBuilder.mIsVideoCallProviderChanged = parcelableCall.mIsVideoCallProviderChanged;
            newBuilder.mVideoCallProvider = parcelableCall.mVideoCallProvider;
            newBuilder.mIsRttCallChanged = parcelableCall.mIsRttCallChanged;
            newBuilder.mRttCall = parcelableCall.mRttCall;
            newBuilder.mParentCallId = parcelableCall.mParentCallId;
            newBuilder.mChildCallIds = parcelableCall.mChildCallIds;
            newBuilder.mStatusHints = parcelableCall.mStatusHints;
            newBuilder.mVideoState = parcelableCall.mVideoState;
            newBuilder.mConferenceableCallIds = parcelableCall.mConferenceableCallIds;
            newBuilder.mIntentExtras = parcelableCall.mIntentExtras;
            newBuilder.mExtras = parcelableCall.mExtras;
            newBuilder.mCreationTimeMillis = parcelableCall.mCreationTimeMillis;
            newBuilder.mCallDirection = parcelableCall.mCallDirection;
            newBuilder.mCallerNumberVerificationStatus =
                    parcelableCall.mCallerNumberVerificationStatus;
            newBuilder.mContactDisplayName = parcelableCall.mContactDisplayName;
            newBuilder.mActiveChildCallId = parcelableCall.mActiveChildCallId;
            return newBuilder;
        }
    }

    private final String mId;
    private final int mState;
    private final DisconnectCause mDisconnectCause;
    private final List<String> mCannedSmsResponses;
    private final int mCapabilities;
    private final int mProperties;
    private final int mSupportedAudioRoutes;
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
    private final boolean mIsRttCallChanged;
    private final ParcelableRttCall mRttCall;
    private final String mParentCallId;
    private final List<String> mChildCallIds;
    private final StatusHints mStatusHints;
    private final int mVideoState;
    private final List<String> mConferenceableCallIds;
    private final Bundle mIntentExtras;
    private final Bundle mExtras;
    private final long mCreationTimeMillis;
    private final int mCallDirection;
    private final int mCallerNumberVerificationStatus;
    private final String mContactDisplayName;
    private final String mActiveChildCallId; // Only valid for CDMA conferences

    public ParcelableCall(
            String id,
            int state,
            DisconnectCause disconnectCause,
            List<String> cannedSmsResponses,
            int capabilities,
            int properties,
            int supportedAudioRoutes,
            long connectTimeMillis,
            Uri handle,
            int handlePresentation,
            String callerDisplayName,
            int callerDisplayNamePresentation,
            GatewayInfo gatewayInfo,
            PhoneAccountHandle accountHandle,
            boolean isVideoCallProviderChanged,
            IVideoProvider videoCallProvider,
            boolean isRttCallChanged,
            ParcelableRttCall rttCall,
            String parentCallId,
            List<String> childCallIds,
            StatusHints statusHints,
            int videoState,
            List<String> conferenceableCallIds,
            Bundle intentExtras,
            Bundle extras,
            long creationTimeMillis,
            int callDirection,
            int callerNumberVerificationStatus,
            String contactDisplayName,
            String activeChildCallId
    ) {
        mId = id;
        mState = state;
        mDisconnectCause = disconnectCause;
        mCannedSmsResponses = cannedSmsResponses;
        mCapabilities = capabilities;
        mProperties = properties;
        mSupportedAudioRoutes = supportedAudioRoutes;
        mConnectTimeMillis = connectTimeMillis;
        mHandle = handle;
        mHandlePresentation = handlePresentation;
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = callerDisplayNamePresentation;
        mGatewayInfo = gatewayInfo;
        mAccountHandle = accountHandle;
        mIsVideoCallProviderChanged = isVideoCallProviderChanged;
        mVideoCallProvider = videoCallProvider;
        mIsRttCallChanged = isRttCallChanged;
        mRttCall = rttCall;
        mParentCallId = parentCallId;
        mChildCallIds = childCallIds;
        mStatusHints = statusHints;
        mVideoState = videoState;
        mConferenceableCallIds = Collections.unmodifiableList(conferenceableCallIds);
        mIntentExtras = intentExtras;
        mExtras = extras;
        mCreationTimeMillis = creationTimeMillis;
        mCallDirection = callDirection;
        mCallerNumberVerificationStatus = callerNumberVerificationStatus;
        mContactDisplayName = contactDisplayName;
        mActiveChildCallId = activeChildCallId;
    }

    /** The unique ID of the call. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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

    /** Bitmask of supported routes of the call */
    public int getSupportedAudioRoutes() {
        return mSupportedAudioRoutes;
    }

    /** The time that the call switched to the active state. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    /** The endpoint to which the call is connected. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     *
     * @param callingPackageName the package name of the calling InCallService.
     * @param targetSdkVersion the target SDK version of the calling InCallService.
     * @return The video call.
     */
    public VideoCallImpl getVideoCallImpl(String callingPackageName, int targetSdkVersion) {
        if (mVideoCall == null && mVideoCallProvider != null) {
            try {
                mVideoCall = new VideoCallImpl(mVideoCallProvider, callingPackageName,
                        targetSdkVersion);
            } catch (RemoteException ignored) {
                // Ignore RemoteException.
            }
        }

        return mVideoCall;
    }

    public IVideoProvider getVideoProvider() {
        return mVideoCallProvider;
    }

    public boolean getIsRttCallChanged() {
        return mIsRttCallChanged;
    }

    /**
     * RTT communication channel information
     * @return The ParcelableRttCall
     */
    public ParcelableRttCall getParcelableRttCall() {
        return mRttCall;
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

    /**
     * @return The time the call was created, in milliseconds since the epoch.
     */
    public long getCreationTimeMillis() {
        return mCreationTimeMillis;
    }

    /**
     * Indicates whether the call is an incoming or outgoing call.
     */
    public @CallDirection int getCallDirection() {
        return mCallDirection;
    }

    /**
     * Gets the verification status for the phone number of an incoming call as identified in
     * ATIS-1000082.
     * @return the verification status.
     */
    public @Connection.VerificationStatus int getCallerNumberVerificationStatus() {
        return mCallerNumberVerificationStatus;
    }

    /**
     * @return the name of the remote party as derived from a contacts DB lookup.
     */
    public @Nullable String getContactDisplayName() {
        return mContactDisplayName;
    }

    /**
     * @return On a CDMA conference with two participants, returns the ID of the child call that's
     *         currently active.
     */
    public @Nullable String getActiveChildCallId() {
        return mActiveChildCallId;
    }

    /** Responsible for creating ParcelableCall objects for deserialized Parcels. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static final @android.annotation.NonNull Parcelable.Creator<ParcelableCall> CREATOR =
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
            Uri handle = Uri.CREATOR.createFromParcel(source);
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
            int supportedAudioRoutes = source.readInt();
            boolean isRttCallChanged = source.readByte() == 1;
            ParcelableRttCall rttCall = source.readParcelable(classLoader);
            long creationTimeMillis = source.readLong();
            int callDirection = source.readInt();
            int callerNumberVerificationStatus = source.readInt();
            String contactDisplayName = source.readString();
            String activeChildCallId = source.readString();
            return new ParcelableCallBuilder()
                    .setId(id)
                    .setState(state)
                    .setDisconnectCause(disconnectCause)
                    .setCannedSmsResponses(cannedSmsResponses)
                    .setCapabilities(capabilities)
                    .setProperties(properties)
                    .setSupportedAudioRoutes(supportedAudioRoutes)
                    .setConnectTimeMillis(connectTimeMillis)
                    .setHandle(handle)
                    .setHandlePresentation(handlePresentation)
                    .setCallerDisplayName(callerDisplayName)
                    .setCallerDisplayNamePresentation(callerDisplayNamePresentation)
                    .setGatewayInfo(gatewayInfo)
                    .setAccountHandle(accountHandle)
                    .setIsVideoCallProviderChanged(isVideoCallProviderChanged)
                    .setVideoCallProvider(videoCallProvider)
                    .setIsRttCallChanged(isRttCallChanged)
                    .setRttCall(rttCall)
                    .setParentCallId(parentCallId)
                    .setChildCallIds(childCallIds)
                    .setStatusHints(statusHints)
                    .setVideoState(videoState)
                    .setConferenceableCallIds(conferenceableCallIds)
                    .setIntentExtras(intentExtras)
                    .setExtras(extras)
                    .setCreationTimeMillis(creationTimeMillis)
                    .setCallDirection(callDirection)
                    .setCallerNumberVerificationStatus(callerNumberVerificationStatus)
                    .setContactDisplayName(contactDisplayName)
                    .setActiveChildCallId(activeChildCallId)
                    .createParcelableCall();
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
        Uri.writeToParcel(destination, mHandle);
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
        destination.writeInt(mSupportedAudioRoutes);
        destination.writeByte((byte) (mIsRttCallChanged ? 1 : 0));
        destination.writeParcelable(mRttCall, 0);
        destination.writeLong(mCreationTimeMillis);
        destination.writeInt(mCallDirection);
        destination.writeInt(mCallerNumberVerificationStatus);
        destination.writeString(mContactDisplayName);
        destination.writeString(mActiveChildCallId);
    }

    @Override
    public String toString() {
        return String.format("[%s, parent:%s, children:%s]", mId, mParentCallId, mChildCallIds);
    }
}
