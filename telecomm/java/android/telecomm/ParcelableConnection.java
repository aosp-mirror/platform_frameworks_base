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
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telecomm.IVideoProvider;

/**
 * Information about a connection that is used between Telecomm and the ConnectionService.
 * This is used to send initial Connection information to Telecomm when the connection is
 * first created.
 * @hide
 */
public final class ParcelableConnection implements Parcelable {
    private PhoneAccountHandle mPhoneAccount;
    private int mState;
    private int mCapabilities;
    private Uri mHandle;
    private int mHandlePresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private IVideoProvider mVideoProvider;
    private int mVideoState;

    /** @hide */
    public ParcelableConnection(
            PhoneAccountHandle phoneAccount,
            int state,
            int capabilities,
            Uri handle,
            int handlePresentation,
            String callerDisplayName,
            int callerDisplayNamePresentation,
            IVideoProvider videoProvider,
            int videoState) {
        mPhoneAccount = phoneAccount;
        mState = state;
        mCapabilities = capabilities;
        mHandle = handle;
        mHandlePresentation = handlePresentation;
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = callerDisplayNamePresentation;
        mVideoProvider = videoProvider;
        mVideoState = videoState;
    }

    public PhoneAccountHandle getPhoneAccount() {
        return mPhoneAccount;
    }

    public int getState() {
        return mState;
    }

    // Bit mask of actions a call supports, values are defined in {@link CallCapabilities}.
    public int getCapabilities() {
        return mCapabilities;
    }

    public Uri getHandle() {
        return mHandle;
    }

    public int getHandlePresentation() {
        return mHandlePresentation;
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

    @Override
    public String toString() {
        return new StringBuilder()
                .append("ParcelableConnection [act:")
                .append(mPhoneAccount)
                .append(", state:")
                .append(mState)
                .append(", capabilities:")
                .append(PhoneCapabilities.toString(mCapabilities))
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
            Uri handle = source.readParcelable(classLoader);
            int handlePresentation = source.readInt();
            String callerDisplayName = source.readString();
            int callerDisplayNamePresentation = source.readInt();
            IVideoProvider videoCallProvider =
                    IVideoProvider.Stub.asInterface(source.readStrongBinder());
            int videoState = source.readInt();

            return new ParcelableConnection(
                    phoneAccount,
                    state,
                    capabilities,
                    handle,
                    handlePresentation,
                    callerDisplayName,
                    callerDisplayNamePresentation,
                    videoCallProvider,
                    videoState);
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
        destination.writeInt(mCapabilities);
        destination.writeParcelable(mHandle, 0);
        destination.writeInt(mHandlePresentation);
        destination.writeString(mCallerDisplayName);
        destination.writeInt(mCallerDisplayNamePresentation);
        destination.writeStrongBinder(
                mVideoProvider != null ? mVideoProvider.asBinder() : null);
        destination.writeInt(mVideoState);
    }
}
