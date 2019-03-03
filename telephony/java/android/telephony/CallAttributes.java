/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.TelephonyManager.NetworkType;

import java.util.Objects;

/**
 * Contains information about a call's attributes as passed up from the HAL. If there are multiple
 * ongoing calls, the CallAttributes will pertain to the call in the foreground.
 * @hide
 */
@SystemApi
public class CallAttributes implements Parcelable {
    private PreciseCallState mPreciseCallState;
    @NetworkType
    private int mNetworkType; // TelephonyManager.NETWORK_TYPE_* ints
    private CallQuality mCallQuality;


    public CallAttributes(PreciseCallState state, @NetworkType int networkType,
            CallQuality callQuality) {
        this.mPreciseCallState = state;
        this.mNetworkType = networkType;
        this.mCallQuality = callQuality;
    }

    @Override
    public String toString() {
        return "mPreciseCallState=" + mPreciseCallState + " mNetworkType=" + mNetworkType
                + " mCallQuality=" + mCallQuality;
    }

    private CallAttributes(Parcel in) {
        this.mPreciseCallState = in.readParcelable(PreciseCallState.class.getClassLoader());
        this.mNetworkType = in.readInt();
        this.mCallQuality = in.readParcelable(CallQuality.class.getClassLoader());
    }

    // getters
    /**
     * Returns the {@link PreciseCallState} of the call.
     */
    public PreciseCallState getPreciseCallState() {
        return mPreciseCallState;
    }

    /**
     * Returns the {@link TelephonyManager#NetworkType} of the call.
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
     * @see TelephonyManager#NETWORK_TYPE_GSM
     * @see TelephonyManager#NETWORK_TYPE_TD_SCDMA
     * @see TelephonyManager#NETWORK_TYPE_IWLAN
     * @see TelephonyManager#NETWORK_TYPE_LTE_CA
     * @see TelephonyManager#NETWORK_TYPE_NR
     */
    @NetworkType
    public int getNetworkType() {
        return mNetworkType;
    }

    /**
     * Returns the {#link CallQuality} of the call.
     */
    public CallQuality getCallQuality() {
        return mCallQuality;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPreciseCallState, mNetworkType, mCallQuality);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof CallAttributes) || hashCode() != o.hashCode()) {
            return false;
        }

        if (this == o) {
            return true;
        }

        CallAttributes s = (CallAttributes) o;

        return (Objects.equals(mPreciseCallState, s.mPreciseCallState)
                && mNetworkType == s.mNetworkType
                && Objects.equals(mCallQuality, s.mCallQuality));
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public @Parcelable.ContentsFlags int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel dest, @Parcelable.WriteFlags int flags) {
        dest.writeParcelable(mPreciseCallState, flags);
        dest.writeInt(mNetworkType);
        dest.writeParcelable(mCallQuality, flags);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CallAttributes> CREATOR = new Parcelable.Creator() {
        public CallAttributes createFromParcel(Parcel in) {
            return new CallAttributes(in);
        }

        public CallAttributes[] newArray(int size) {
            return new CallAttributes[size];
        }
    };
}
