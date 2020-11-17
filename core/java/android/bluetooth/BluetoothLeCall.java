/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package android.bluetooth;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelUuid;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.UUID;

/**
 * Representation of Call
 *
 * @hide
 */
public final class BluetoothLeCall implements Parcelable {

    /** @hide */
    @IntDef(prefix = "STATE_", value = {
            STATE_INCOMING,
            STATE_DIALING,
            STATE_ALERTING,
            STATE_ACTIVE,
            STATE_LOCALLY_HELD,
            STATE_REMOTELY_HELD,
            STATE_LOCALLY_AND_REMOTELY_HELD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    /**
     * A remote party is calling (incoming call).
     *
     * @hide
     */
    public static final int STATE_INCOMING = 0x00;

    /**
     * The process to call the remote party has started but the remote party is not
     * being alerted (outgoing call).
     *
     * @hide
     */
    public static final int STATE_DIALING = 0x01;

    /**
     * A remote party is being alerted (outgoing call).
     *
     * @hide
     */
    public static final int STATE_ALERTING = 0x02;

    /**
     * The call is in an active conversation.
     *
     * @hide
     */
    public static final int STATE_ACTIVE = 0x03;

    /**
     * The call is connected but held locally. “Locally Held” implies that either
     * the server or the client can affect the state.
     *
     * @hide
     */
    public static final int STATE_LOCALLY_HELD = 0x04;

    /**
     * The call is connected but held remotely. “Remotely Held” means that the state
     * is controlled by the remote party of a call.
     *
     * @hide
     */
    public static final int STATE_REMOTELY_HELD = 0x05;

    /**
     * The call is connected but held both locally and remotely.
     *
     * @hide
     */
    public static final int STATE_LOCALLY_AND_REMOTELY_HELD = 0x06;

    /**
     * Whether the call direction is outgoing.
     *
     * @hide
     */
    public static final int FLAG_OUTGOING_CALL = 0x00000001;

    /**
     * Whether the call URI and Friendly Name are withheld by server.
     *
     * @hide
     */
    public static final int FLAG_WITHHELD_BY_SERVER = 0x00000002;

    /**
     * Whether the call URI and Friendly Name are withheld by network.
     *
     * @hide
     */
    public static final int FLAG_WITHHELD_BY_NETWORK = 0x00000004;

    /** Unique UUID that identifies this call */
    private UUID mUuid;

    /** Remote Caller URI */
    private String mUri;

    /** Caller friendly name */
    private String mFriendlyName;

    /** Call state */
    private @State int mState;

    /** Call flags */
    private int mCallFlags;

    /** @hide */
    public BluetoothLeCall(@NonNull BluetoothLeCall that) {
        mUuid = new UUID(that.getUuid().getMostSignificantBits(),
                that.getUuid().getLeastSignificantBits());
        mUri = that.mUri;
        mFriendlyName = that.mFriendlyName;
        mState = that.mState;
        mCallFlags = that.mCallFlags;
    }

    /** @hide */
    public BluetoothLeCall(@NonNull UUID uuid, @NonNull String uri, @NonNull String friendlyName,
            @State int state, int callFlags) {
        mUuid = uuid;
        mUri = uri;
        mFriendlyName = friendlyName;
        mState = state;
        mCallFlags = callFlags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        BluetoothLeCall that = (BluetoothLeCall) o;
        return mUuid.equals(that.mUuid) && mUri.equals(that.mUri)
                && mFriendlyName.equals(that.mFriendlyName) && mState == that.mState
                && mCallFlags == that.mCallFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUuid, mUri, mFriendlyName, mState, mCallFlags);
    }

    /**
     * Returns a string representation of this BluetoothLeCall.
     *
     * <p>
     * Currently this is the UUID.
     *
     * @return string representation of this BluetoothLeCall
     */
    @Override
    public String toString() {
        return mUuid.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(new ParcelUuid(mUuid), 0);
        out.writeString(mUri);
        out.writeString(mFriendlyName);
        out.writeInt(mState);
        out.writeInt(mCallFlags);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BluetoothLeCall> CREATOR =
    						    new Parcelable.Creator<BluetoothLeCall>() {
        public BluetoothLeCall createFromParcel(Parcel in) {
            return new BluetoothLeCall(in);
        }

        public BluetoothLeCall[] newArray(int size) {
            return new BluetoothLeCall[size];
        }
    };

    private BluetoothLeCall(Parcel in) {
        mUuid = ((ParcelUuid) in.readParcelable(null)).getUuid();
        mUri = in.readString();
        mFriendlyName = in.readString();
        mState = in.readInt();
        mCallFlags = in.readInt();
    }

    /**
     * Returns an UUID of this BluetoothLeCall.
     *
     * <p>
     * An UUID is unique identifier of a BluetoothLeCall.
     *
     * @return UUID of this BluetoothLeCall
     * @hide
     */
    public @NonNull UUID getUuid() {
        return mUuid;
    }

    /**
     * Returns a URI of the remote party of this BluetoothLeCall.
     *
     * @return string representation of this BluetoothLeCall
     * @hide
     */
    public @NonNull String getUri() {
        return mUri;
    }

    /**
     * Returns a friendly name of the call.
     *
     * @return friendly name representation of this BluetoothLeCall
     * @hide
     */
    public @NonNull String getFriendlyName() {
        return mFriendlyName;
    }

    /**
     * Returns the call state.
     *
     * @return the state of this BluetoothLeCall
     * @hide
     */
    public @State int getState() {
        return mState;
    }

    /**
     * Returns the call flags.
     *
     * @return call flags
     * @hide
     */
    public int getCallFlags() {
        return mCallFlags;
    }

    /**
     * Whether the call direction is incoming.
     *
     * @return true if incoming call, false otherwise
     * @hide
     */
    public boolean isIncomingCall() {
        return (mCallFlags & FLAG_OUTGOING_CALL) == 0;
    }
}
