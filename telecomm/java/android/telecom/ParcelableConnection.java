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
import java.util.List;

/**
 * Information about a connection that is used between Telecom and the ConnectionService.
 * This is used to send initial Connection information to Telecom when the connection is
 * first created.
 * @hide
 */
public final class ParcelableConnection implements Parcelable {
    private final PhoneAccountHandle mPhoneAccount;
    private final int mState;
    private final int mConnectionCapabilities;
    private final int mConnectionProperties;
    private final Uri mAddress;
    private final int mAddressPresentation;
    private final String mCallerDisplayName;
    private final int mCallerDisplayNamePresentation;
    private final IVideoProvider mVideoProvider;
    private final int mVideoState;
    private final boolean mRingbackRequested;
    private final boolean mIsVoipAudioMode;
    private final long mConnectTimeMillis;
    private final StatusHints mStatusHints;
    private final DisconnectCause mDisconnectCause;
    private final List<String> mConferenceableConnectionIds;
    private final Bundle mExtras;

    /** @hide */
    public ParcelableConnection(
            PhoneAccountHandle phoneAccount,
            int state,
            int capabilities,
            int properties,
            Uri address,
            int addressPresentation,
            String callerDisplayName,
            int callerDisplayNamePresentation,
            IVideoProvider videoProvider,
            int videoState,
            boolean ringbackRequested,
            boolean isVoipAudioMode,
            long connectTimeMillis,
            StatusHints statusHints,
            DisconnectCause disconnectCause,
            List<String> conferenceableConnectionIds,
            Bundle extras) {
        mPhoneAccount = phoneAccount;
        mState = state;
        mConnectionCapabilities = capabilities;
        mConnectionProperties = properties;
        mAddress = address;
        mAddressPresentation = addressPresentation;
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = callerDisplayNamePresentation;
        mVideoProvider = videoProvider;
        mVideoState = videoState;
        mRingbackRequested = ringbackRequested;
        mIsVoipAudioMode = isVoipAudioMode;
        mConnectTimeMillis = connectTimeMillis;
        mStatusHints = statusHints;
        mDisconnectCause = disconnectCause;
        mConferenceableConnectionIds = conferenceableConnectionIds;
        mExtras = extras;
    }

    public PhoneAccountHandle getPhoneAccount() {
        return mPhoneAccount;
    }

    public int getState() {
        return mState;
    }

    /**
     * Returns the current connection capabilities bit-mask.  Connection capabilities are defined as
     * {@code CAPABILITY_*} constants in {@link Connection}.
     *
     * @return Bit-mask containing capabilities of the connection.
     */
    public int getConnectionCapabilities() {
        return mConnectionCapabilities;
    }

    /**
     * Returns the current connection properties bit-mask.  Connection properties are defined as
     * {@code PROPERTY_*} constants in {@link Connection}.
     *
     * @return Bit-mask containing properties of the connection.
     */
    public int getConnectionProperties() {
        return mConnectionProperties;
    }

    public Uri getHandle() {
        return mAddress;
    }

    public int getHandlePresentation() {
        return mAddressPresentation;
    }

    public String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    public int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    public IVideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    public int getVideoState() {
        return mVideoState;
    }

    public boolean isRingbackRequested() {
        return mRingbackRequested;
    }

    public boolean getIsVoipAudioMode() {
        return mIsVoipAudioMode;
    }

    public long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    public final StatusHints getStatusHints() {
        return mStatusHints;
    }

    public final DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    public final List<String> getConferenceableConnectionIds() {
        return mConferenceableConnectionIds;
    }

    public final Bundle getExtras() {
        return mExtras;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("ParcelableConnection [act:")
                .append(mPhoneAccount)
                .append("], state:")
                .append(mState)
                .append(", capabilities:")
                .append(Connection.capabilitiesToString(mConnectionCapabilities))
                .append(", properties:")
                .append(Connection.propertiesToString(mConnectionProperties))
                .append(", extras:")
                .append(mExtras)
                .toString();
    }

    public static final Parcelable.Creator<ParcelableConnection> CREATOR =
            new Parcelable.Creator<ParcelableConnection> () {
        @Override
        public ParcelableConnection createFromParcel(Parcel source) {
            ClassLoader classLoader = ParcelableConnection.class.getClassLoader();

            PhoneAccountHandle phoneAccount = source.readParcelable(classLoader);
            int state = source.readInt();
            int capabilities = source.readInt();
            Uri address = source.readParcelable(classLoader);
            int addressPresentation = source.readInt();
            String callerDisplayName = source.readString();
            int callerDisplayNamePresentation = source.readInt();
            IVideoProvider videoCallProvider =
                    IVideoProvider.Stub.asInterface(source.readStrongBinder());
            int videoState = source.readInt();
            boolean ringbackRequested = source.readByte() == 1;
            boolean audioModeIsVoip = source.readByte() == 1;
            long connectTimeMillis = source.readLong();
            StatusHints statusHints = source.readParcelable(classLoader);
            DisconnectCause disconnectCause = source.readParcelable(classLoader);
            List<String> conferenceableConnectionIds = new ArrayList<>();
            source.readStringList(conferenceableConnectionIds);
            Bundle extras = Bundle.setDefusable(source.readBundle(classLoader), true);
            int properties = source.readInt();

            return new ParcelableConnection(
                    phoneAccount,
                    state,
                    capabilities,
                    properties,
                    address,
                    addressPresentation,
                    callerDisplayName,
                    callerDisplayNamePresentation,
                    videoCallProvider,
                    videoState,
                    ringbackRequested,
                    audioModeIsVoip,
                    connectTimeMillis,
                    statusHints,
                    disconnectCause,
                    conferenceableConnectionIds,
                    extras);
        }

        @Override
        public ParcelableConnection[] newArray(int size) {
            return new ParcelableConnection[size];
        }
    };

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes ParcelableConnection object into a Parcel. */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeParcelable(mPhoneAccount, 0);
        destination.writeInt(mState);
        destination.writeInt(mConnectionCapabilities);
        destination.writeParcelable(mAddress, 0);
        destination.writeInt(mAddressPresentation);
        destination.writeString(mCallerDisplayName);
        destination.writeInt(mCallerDisplayNamePresentation);
        destination.writeStrongBinder(
                mVideoProvider != null ? mVideoProvider.asBinder() : null);
        destination.writeInt(mVideoState);
        destination.writeByte((byte) (mRingbackRequested ? 1 : 0));
        destination.writeByte((byte) (mIsVoipAudioMode ? 1 : 0));
        destination.writeLong(mConnectTimeMillis);
        destination.writeParcelable(mStatusHints, 0);
        destination.writeParcelable(mDisconnectCause, 0);
        destination.writeStringList(mConferenceableConnectionIds);
        destination.writeBundle(mExtras);
        destination.writeInt(mConnectionProperties);
    }
}
