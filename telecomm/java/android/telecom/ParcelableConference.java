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

import com.android.internal.telecom.IVideoProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parcelable representation of a conference connection.
 * @hide
 */
public final class ParcelableConference implements Parcelable {

    public static final class Builder {
        private final PhoneAccountHandle mPhoneAccount;
        private final int mState;
        private int mConnectionCapabilities;
        private int mConnectionProperties;
        private List<String> mConnectionIds = Collections.emptyList();
        private long mConnectTimeMillis = Conference.CONNECT_TIME_NOT_SPECIFIED;
        private IVideoProvider mVideoProvider;
        private int mVideoState = VideoProfile.STATE_AUDIO_ONLY;
        private StatusHints mStatusHints;
        private Bundle mExtras;
        private long mConnectElapsedTimeMillis = Conference.CONNECT_TIME_NOT_SPECIFIED;
        private Uri mAddress;
        private int mAddressPresentation = TelecomManager.PRESENTATION_UNKNOWN;
        private String mCallerDisplayName;
        private int mCallerDisplayNamePresentation = TelecomManager.PRESENTATION_UNKNOWN;;
        private DisconnectCause mDisconnectCause;
        private boolean mRingbackRequested;
        private int mCallDirection = Call.Details.DIRECTION_UNKNOWN;

        public Builder(
                PhoneAccountHandle phoneAccount,
                int state) {
            mPhoneAccount = phoneAccount;
            mState = state;
        }

        public Builder setDisconnectCause(DisconnectCause cause) {
            mDisconnectCause = cause;
            return this;
        }

        public Builder setRingbackRequested(boolean requested) {
            mRingbackRequested = requested;
            return this;
        }

        public Builder setCallerDisplayName(String callerDisplayName,
                @TelecomManager.Presentation int callerDisplayNamePresentation) {
            mCallerDisplayName = callerDisplayName;
            mCallerDisplayNamePresentation = callerDisplayNamePresentation;
            return this;
        }

        public Builder setAddress(Uri address,
                @TelecomManager.Presentation int addressPresentation) {
            mAddress = address;
            mAddressPresentation = addressPresentation;
            return this;
        }

        public Builder setExtras(Bundle extras) {
            mExtras = extras;
            return this;
        }

        public Builder setStatusHints(StatusHints hints) {
            mStatusHints = hints;
            return this;
        }

        public Builder setConnectTimeMillis(long connectTimeMillis, long connectElapsedTimeMillis) {
            mConnectTimeMillis = connectTimeMillis;
            mConnectElapsedTimeMillis = connectElapsedTimeMillis;
            return this;
        }

        public Builder setVideoAttributes(IVideoProvider provider,
                @VideoProfile.VideoState int videoState) {
            mVideoProvider = provider;
            mVideoState = videoState;
            return this;
        }

        public Builder setConnectionIds(List<String> connectionIds) {
            mConnectionIds = connectionIds;
            return this;
        }

        public Builder setConnectionProperties(int properties) {
            mConnectionProperties = properties;
            return this;
        }

        public Builder setConnectionCapabilities(int capabilities) {
            mConnectionCapabilities = capabilities;
            return this;
        }

        public Builder setCallDirection(int callDirection) {
            mCallDirection = callDirection;
            return this;
        }

        public ParcelableConference build() {
            return new ParcelableConference(mPhoneAccount, mState, mConnectionCapabilities,
                    mConnectionProperties, mConnectionIds, mVideoProvider, mVideoState,
                    mConnectTimeMillis, mConnectElapsedTimeMillis, mStatusHints, mExtras, mAddress,
                    mAddressPresentation, mCallerDisplayName, mCallerDisplayNamePresentation,
                    mDisconnectCause, mRingbackRequested, mCallDirection);
        }
    }


    private final PhoneAccountHandle mPhoneAccount;
    private final int mState;
    private final int mConnectionCapabilities;
    private final int mConnectionProperties;
    private final List<String> mConnectionIds;
    private final long mConnectTimeMillis;
    private final IVideoProvider mVideoProvider;
    private final int mVideoState;
    private final StatusHints mStatusHints;
    private final Bundle mExtras;
    private final long mConnectElapsedTimeMillis;
    private final Uri mAddress;
    private final int mAddressPresentation;
    private final String mCallerDisplayName;
    private final int mCallerDisplayNamePresentation;
    private final DisconnectCause mDisconnectCause;
    private final boolean mRingbackRequested;
    private final int mCallDirection;

    private ParcelableConference(
            PhoneAccountHandle phoneAccount,
            int state,
            int connectionCapabilities,
            int connectionProperties,
            List<String> connectionIds,
            IVideoProvider videoProvider,
            int videoState,
            long connectTimeMillis,
            long connectElapsedTimeMillis,
            StatusHints statusHints,
            Bundle extras,
            Uri address,
            int addressPresentation,
            String callerDisplayName,
            int callerDisplayNamePresentation,
            DisconnectCause disconnectCause,
            boolean ringbackRequested,
            int callDirection) {
        mPhoneAccount = phoneAccount;
        mState = state;
        mConnectionCapabilities = connectionCapabilities;
        mConnectionProperties = connectionProperties;
        mConnectionIds = connectionIds;
        mVideoProvider = videoProvider;
        mVideoState = videoState;
        mConnectTimeMillis = connectTimeMillis;
        mStatusHints = statusHints;
        mExtras = extras;
        mConnectElapsedTimeMillis = connectElapsedTimeMillis;
        mAddress = address;
        mAddressPresentation = addressPresentation;
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = callerDisplayNamePresentation;
        mDisconnectCause = disconnectCause;
        mRingbackRequested = ringbackRequested;
        mCallDirection = callDirection;
    }

    @Override
    public String toString() {
        return (new StringBuffer())
                .append("account: ")
                .append(mPhoneAccount)
                .append(", state: ")
                .append(Connection.stateToString(mState))
                .append(", capabilities: ")
                .append(Connection.capabilitiesToString(mConnectionCapabilities))
                .append(", properties: ")
                .append(Connection.propertiesToString(mConnectionProperties))
                .append(", connectTime: ")
                .append(mConnectTimeMillis)
                .append(", children: ")
                .append(mConnectionIds)
                .append(", VideoState: ")
                .append(mVideoState)
                .append(", VideoProvider: ")
                .append(mVideoProvider)
                .append(", isRingbackRequested: ")
                .append(mRingbackRequested)
                .append(", disconnectCause: ")
                .append(mDisconnectCause)
                .append(", callDirection: ")
                .append(mCallDirection)
                .toString();
    }

    public PhoneAccountHandle getPhoneAccount() {
        return mPhoneAccount;
    }

    public int getState() {
        return mState;
    }

    public int getConnectionCapabilities() {
        return mConnectionCapabilities;
    }

    public int getConnectionProperties() {
        return mConnectionProperties;
    }

    public List<String> getConnectionIds() {
        return mConnectionIds;
    }

    public long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    public long getConnectElapsedTimeMillis() {
        return mConnectElapsedTimeMillis;
    }

    public IVideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    public int getVideoState() {
        return mVideoState;
    }

    public StatusHints getStatusHints() {
        return mStatusHints;
    }

    public Bundle getExtras() {
        return mExtras;
    }

    public Uri getHandle() {
        return mAddress;
    }

    public final DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    public boolean isRingbackRequested() {
        return mRingbackRequested;
    }

    public int getHandlePresentation() {
        return mAddressPresentation;
    }

    public int getCallDirection() {
        return mCallDirection;
    }

    public String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    public int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ParcelableConference> CREATOR =
            new Parcelable.Creator<ParcelableConference> () {
        @Override
        public ParcelableConference createFromParcel(Parcel source) {
            ClassLoader classLoader = ParcelableConference.class.getClassLoader();
            PhoneAccountHandle phoneAccount = source.readParcelable(classLoader);
            int state = source.readInt();
            int capabilities = source.readInt();
            List<String> connectionIds = new ArrayList<>(2);
            source.readList(connectionIds, classLoader);
            long connectTimeMillis = source.readLong();
            IVideoProvider videoCallProvider =
                    IVideoProvider.Stub.asInterface(source.readStrongBinder());
            int videoState = source.readInt();
            StatusHints statusHints = source.readParcelable(classLoader);
            Bundle extras = source.readBundle(classLoader);
            int properties = source.readInt();
            long connectElapsedTimeMillis = source.readLong();
            Uri address = source.readParcelable(classLoader);
            int addressPresentation = source.readInt();
            String callerDisplayName = source.readString();
            int callerDisplayNamePresentation = source.readInt();
            DisconnectCause disconnectCause = source.readParcelable(classLoader);
            boolean isRingbackRequested = source.readInt() == 1;
            int callDirection = source.readInt();

            return new ParcelableConference(phoneAccount, state, capabilities, properties,
                    connectionIds, videoCallProvider, videoState, connectTimeMillis,
                    connectElapsedTimeMillis, statusHints, extras, address, addressPresentation,
                    callerDisplayName, callerDisplayNamePresentation, disconnectCause,
                    isRingbackRequested, callDirection);
        }

        @Override
        public ParcelableConference[] newArray(int size) {
            return new ParcelableConference[size];
        }
    };

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes ParcelableConference object into a Parcel. */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeParcelable(mPhoneAccount, 0);
        destination.writeInt(mState);
        destination.writeInt(mConnectionCapabilities);
        destination.writeList(mConnectionIds);
        destination.writeLong(mConnectTimeMillis);
        destination.writeStrongBinder(
                mVideoProvider != null ? mVideoProvider.asBinder() : null);
        destination.writeInt(mVideoState);
        destination.writeParcelable(mStatusHints, 0);
        destination.writeBundle(mExtras);
        destination.writeInt(mConnectionProperties);
        destination.writeLong(mConnectElapsedTimeMillis);
        destination.writeParcelable(mAddress, 0);
        destination.writeInt(mAddressPresentation);
        destination.writeString(mCallerDisplayName);
        destination.writeInt(mCallerDisplayNamePresentation);
        destination.writeParcelable(mDisconnectCause, 0);
        destination.writeInt(mRingbackRequested ? 1 : 0);
        destination.writeInt(mCallDirection);
    }
}
