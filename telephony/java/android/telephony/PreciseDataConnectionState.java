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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.net.LinkProperties;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.data.ApnSetting;

import java.util.Objects;


/**
 * Contains precise data connection state.
 *
 * The following data connection information is included in returned PreciseDataConnectionState:
 *
 * <ul>
 *   <li>Data connection state.
 *   <li>Network type of the connection.
 *   <li>APN types.
 *   <li>APN.
 *   <li>The properties of the network link.
 *   <li>Data connection fail cause.
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class PreciseDataConnectionState implements Parcelable {

    private @TelephonyManager.DataState int mState = TelephonyManager.DATA_UNKNOWN;
    private @TelephonyManager.NetworkType int mNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    private @DataFailCause.FailCause int mFailCause = DataFailCause.NONE;
    private @ApnSetting.ApnType int mAPNTypes = ApnSetting.TYPE_NONE;
    private String mAPN = "";
    private LinkProperties mLinkProperties = null;

    /**
     * Constructor
     *
     * @hide
     */
    @UnsupportedAppUsage
    public PreciseDataConnectionState(@TelephonyManager.DataState int state,
                                      @TelephonyManager.NetworkType int networkType,
                                      @ApnSetting.ApnType int apnTypes, String apn,
                                      LinkProperties linkProperties,
                                      @DataFailCause.FailCause int failCause) {
        mState = state;
        mNetworkType = networkType;
        mAPNTypes = apnTypes;
        mAPN = apn;
        mLinkProperties = linkProperties;
        mFailCause = failCause;
    }

    /**
     * Empty Constructor
     *
     * @hide
     */
    public PreciseDataConnectionState() {
    }

    /**
     * Construct a PreciseDataConnectionState object from the given parcel.
     *
     * @hide
     */
    private PreciseDataConnectionState(Parcel in) {
        mState = in.readInt();
        mNetworkType = in.readInt();
        mAPNTypes = in.readInt();
        mAPN = in.readString();
        mLinkProperties = (LinkProperties)in.readParcelable(null);
        mFailCause = in.readInt();
    }

    /**
     * Returns the state of data connection that supported the apn types returned by
     * {@link #getDataConnectionApnTypeBitMask()}
     */
    public @TelephonyManager.DataState int getDataConnectionState() {
        return mState;
    }

    /**
     * Returns the network type associated with this data connection.
     * @hide
     */
    public @TelephonyManager.NetworkType int getDataConnectionNetworkType() {
        return mNetworkType;
    }

    /**
     * Returns the data connection APN types supported by this connection and triggers
     * {@link PreciseDataConnectionState} change.
     */
    public @ApnSetting.ApnType int getDataConnectionApnTypeBitMask() {
        return mAPNTypes;
    }

    /**
     * Returns APN {@link ApnSetting} of this data connection.
     */
    @Nullable
    public String getDataConnectionApn() {
        return mAPN;
    }

    /**
     * Get the properties of the network link {@link LinkProperties}.
     * @hide
     */
    @UnsupportedAppUsage
    public LinkProperties getDataConnectionLinkProperties() {
        return mLinkProperties;
    }

    /**
     * Returns data connection fail cause, in case there was a failure.
     */
    public @DataFailCause.FailCause int getDataConnectionFailCause() {
        return mFailCause;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mState);
        out.writeInt(mNetworkType);
        out.writeInt(mAPNTypes);
        out.writeString(mAPN);
        out.writeParcelable(mLinkProperties, flags);
        out.writeInt(mFailCause);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PreciseDataConnectionState> CREATOR
            = new Parcelable.Creator<PreciseDataConnectionState>() {

        public PreciseDataConnectionState createFromParcel(Parcel in) {
            return new PreciseDataConnectionState(in);
        }

        public PreciseDataConnectionState[] newArray(int size) {
            return new PreciseDataConnectionState[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mState, mNetworkType, mAPNTypes, mAPN, mLinkProperties,
                mFailCause);
    }

    @Override
    public boolean equals(Object obj) {

        if (!(obj instanceof PreciseDataConnectionState)) {
            return false;
        }

        PreciseDataConnectionState other = (PreciseDataConnectionState) obj;
        return Objects.equals(mAPN, other.mAPN) && mAPNTypes == other.mAPNTypes
                && mFailCause == other.mFailCause
                && Objects.equals(mLinkProperties, other.mLinkProperties)
                && mNetworkType == other.mNetworkType
                && mState == other.mState;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("Data Connection state: " + mState);
        sb.append(", Network type: " + mNetworkType);
        sb.append(", APN types: " + ApnSetting.getApnTypesStringFromBitmask(mAPNTypes));
        sb.append(", APN: " + mAPN);
        sb.append(", Link properties: " + mLinkProperties);
        sb.append(", Fail cause: " + DataFailCause.toString(mFailCause));

        return sb.toString();
    }
}
