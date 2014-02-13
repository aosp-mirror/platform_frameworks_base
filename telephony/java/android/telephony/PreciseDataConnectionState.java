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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.net.LinkProperties;

/**
 * Contains precise data connection state.
 *
 * The following data connection information is included in returned PreciseDataConnectionState:
 *
 * <ul>
 *   <li>Data connection state.
 *   <li>Network type of the connection.
 *   <li>APN type.
 *   <li>APN.
 *   <li>Data connection change reason.
 *   <li>The properties of the network link.
 *   <li>Data connection fail cause.
 * </ul>
 *
 * @hide
 */
public class PreciseDataConnectionState implements Parcelable {

    private int mState = TelephonyManager.DATA_UNKNOWN;
    private int mNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    private String mAPNType = "";
    private String mAPN = "";
    private String mReason = "";
    private LinkProperties mLinkProperties = null;
    private String mFailCause = "";

    /**
     * Constructor
     *
     * @hide
     */
    public PreciseDataConnectionState(int state, int networkType,
            String apnType, String apn, String reason,
            LinkProperties linkProperties, String failCause) {
        mState = state;
        mNetworkType = networkType;
        mAPNType = apnType;
        mAPN = apn;
        mReason = reason;
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
     */
    private PreciseDataConnectionState(Parcel in) {
        mState = in.readInt();
        mNetworkType = in.readInt();
        mAPNType = in.readString();
        mAPN = in.readString();
        mReason = in.readString();
        mLinkProperties = (LinkProperties)in.readParcelable(null);
        mFailCause = in.readString();
    }

    /**
     * Get data connection state
     *
     * @see TelephonyManager#DATA_UNKNOWN
     * @see TelephonyManager#DATA_DISCONNECTED
     * @see TelephonyManager#DATA_CONNECTING
     * @see TelephonyManager#DATA_CONNECTED
     * @see TelephonyManager#DATA_SUSPENDED
     */
    public int getDataConnectionState() {
        return mState;
    }

    /**
     * Get data connection network type
     *
     * @see TelephonyManager#NETWORK_TYPE_UNKNOWN
     * @see TelephonyManager#NETWORK_TYPE_GPRS
     * @see TelephonyManager#NETWORK_TYPE_EDGE
     * @see TelephonyManager#NETWORK_TYPE_UMTS
     * @see TelephonyManager#NETWORK_TYPE_CDMA
     * @see TelephonyManager#NETWORK_TYPE_EVDO_0
     * @see TelephonyManager#NETWORK_TYPE_EVDO_A
     * @see TelephonyManager#NETWORK_TYPE_1xRTT
     * @see TelephonyManager#NETWORK_TYPE_HSDPA
     * @see TelephonyManager#NETWORK_TYPE_HSUPA
     * @see TelephonyManager#NETWORK_TYPE_HSPA
     * @see TelephonyManager#NETWORK_TYPE_IDEN
     * @see TelephonyManager#NETWORK_TYPE_EVDO_B
     * @see TelephonyManager#NETWORK_TYPE_LTE
     * @see TelephonyManager#NETWORK_TYPE_EHRPD
     * @see TelephonyManager#NETWORK_TYPE_HSPAP
     */
    public int getDataConnectionNetworkType() {
        return mNetworkType;
    }

    /**
     * Get data connection APN type
     */
    public String getDataConnectionAPNType() {
        return mAPNType;
    }

    /**
     * Get data connection APN.
     */
    public String getDataConnectionAPN() {
        return mAPN;
    }

    /**
     * Get data connection change reason.
     */
    public String getDataConnectionChangeReason() {
        return mReason;
    }

    /**
     * Get the properties of the network link.
     */
    public LinkProperties getDataConnectionLinkProperties() {
        return mLinkProperties;
    }

    /**
     * Get data connection fail cause, in case there was a failure.
     */
    public String getDataConnectionFailCause() {
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
        out.writeString(mAPNType);
        out.writeString(mAPN);
        out.writeString(mReason);
        out.writeParcelable(mLinkProperties, flags);
        out.writeString(mFailCause);
    }

    public static final Parcelable.Creator<PreciseDataConnectionState> CREATOR
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
        final int prime = 31;
        int result = 1;
        result = prime * result + mState;
        result = prime * result + mNetworkType;
        result = prime * result + ((mAPNType == null) ? 0 : mAPNType.hashCode());
        result = prime * result + ((mAPN == null) ? 0 : mAPN.hashCode());
        result = prime * result + ((mReason == null) ? 0 : mReason.hashCode());
        result = prime * result + ((mLinkProperties == null) ? 0 : mLinkProperties.hashCode());
        result = prime * result + ((mFailCause == null) ? 0 : mFailCause.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PreciseDataConnectionState other = (PreciseDataConnectionState) obj;
        if (mAPN == null) {
            if (other.mAPN != null) {
                return false;
            }
        } else if (!mAPN.equals(other.mAPN)) {
            return false;
        }
        if (mAPNType == null) {
            if (other.mAPNType != null) {
                return false;
            }
        } else if (!mAPNType.equals(other.mAPNType)) {
            return false;
        }
        if (mFailCause == null) {
            if (other.mFailCause != null) {
                return false;
            }
        } else if (!mFailCause.equals(other.mFailCause)) {
            return false;
        }
        if (mLinkProperties == null) {
            if (other.mLinkProperties != null) {
                return false;
            }
        } else if (!mLinkProperties.equals(other.mLinkProperties)) {
            return false;
        }
        if (mNetworkType != other.mNetworkType) {
            return false;
        }
        if (mReason == null) {
            if (other.mReason != null) {
                return false;
            }
        } else if (!mReason.equals(other.mReason)) {
            return false;
        }
        if (mState != other.mState) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("Data Connection state: " + mState);
        sb.append(", Network type: " + mNetworkType);
        sb.append(", APN type: " + mAPNType);
        sb.append(", APN: " + mAPN);
        sb.append(", Change reason: " + mReason);
        sb.append(", Link properties: " + mLinkProperties);
        sb.append(", Fail cause: " + mFailCause);

        return sb.toString();
    }
}
