/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.RequiresFeature;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Defines downlink and uplink capacity of a network in kbps
 * @hide
 */
@SystemApi
public final class CarrierBandwidth implements Parcelable {
    /**
     * Any field that is not reported shall be set to INVALID
     */
    public static final int INVALID = -1;

    /**
     * Estimated downlink capacity in kbps of the primary carrier.
     * This bandwidth estimate shall be the estimated maximum sustainable link bandwidth.
     * This will be {@link #INVALID} if the network is not connected
     */
    private int mPrimaryDownlinkCapacityKbps;

    /**
     * Estimated uplink capacity in kbps of the primary carrier.
     * This bandwidth estimate shall be the estimated maximum sustainable link bandwidth.
     * This will be {@link #INVALID} if the network is not connected
     */
    private int mPrimaryUplinkCapacityKbps;

    /**
     * Estimated downlink capacity in kbps of the secondary carrier in a dual connected network.
     * This bandwidth estimate shall be the estimated maximum sustainable link bandwidth.
     * This will be {@link #INVALID} if the network is not connected
     */
    private int mSecondaryDownlinkCapacityKbps;

    /**
     * Estimated uplink capacity in kbps of the secondary carrier in a dual connected network.
     * This bandwidth estimate shall be the estimated maximum sustainable link bandwidth.
     * This will be {@link #INVALID} if the network is not connected
     */
    private int mSecondaryUplinkCapacityKbps;

    /** @hide **/
    public CarrierBandwidth(Parcel in) {
        mPrimaryDownlinkCapacityKbps = in.readInt();
        mPrimaryUplinkCapacityKbps = in.readInt();
        mSecondaryDownlinkCapacityKbps = in.readInt();
        mSecondaryUplinkCapacityKbps = in.readInt();
    }

    /** @hide **/
    public CarrierBandwidth() {
        mPrimaryDownlinkCapacityKbps = INVALID;
        mPrimaryUplinkCapacityKbps = INVALID;
        mSecondaryDownlinkCapacityKbps = INVALID;
        mSecondaryUplinkCapacityKbps = INVALID;
    }

    /**
     * Constructor.
     *
     * @param primaryDownlinkCapacityKbps Estimated downlink capacity in kbps of
     *        the primary carrier.
     * @param primaryUplinkCapacityKbps Estimated uplink capacity in kbps of
     *        the primary carrier.
     * @param secondaryDownlinkCapacityKbps Estimated downlink capacity in kbps of
     *        the secondary carrier
     * @param secondaryUplinkCapacityKbps Estimated uplink capacity in kbps of
     *        the secondary carrier
     */
    public CarrierBandwidth(int primaryDownlinkCapacityKbps, int primaryUplinkCapacityKbps,
            int secondaryDownlinkCapacityKbps, int secondaryUplinkCapacityKbps) {
        mPrimaryDownlinkCapacityKbps = primaryDownlinkCapacityKbps;
        mPrimaryUplinkCapacityKbps = primaryUplinkCapacityKbps;
        mSecondaryDownlinkCapacityKbps = secondaryDownlinkCapacityKbps;
        mSecondaryUplinkCapacityKbps = secondaryUplinkCapacityKbps;
    }

    /**
     * Retrieves the upstream bandwidth for the primary network in Kbps.  This always only refers to
     * the estimated first hop transport bandwidth.
     * This will be {@link #INVALID} if the network is not connected
     *
     * @return The estimated first hop upstream (device to network) bandwidth.
     */
    public int getPrimaryDownlinkCapacityKbps() {
        return mPrimaryDownlinkCapacityKbps;
    }

    /**
     * Retrieves the downstream bandwidth for the primary network in Kbps.  This always only refers
     * to the estimated first hop transport bandwidth.
     * This will be {@link #INVALID} if the network is not connected
     *
     * @return The estimated first hop downstream (network to device) bandwidth.
     */
    public int getPrimaryUplinkCapacityKbps() {
        return mPrimaryUplinkCapacityKbps;
    }

    /**
     * Retrieves the upstream bandwidth for the secondary network in Kbps.  This always only refers
     * to the estimated first hop transport bandwidth.
     * <p/>
     * This will be {@link #INVALID} if either are the case:
     * <ol>
     *  <li>The network is not connected</li>
     *  <li>The device does not support
     * {@link android.telephony.TelephonyManager#CAPABILITY_SECONDARY_LINK_BANDWIDTH_VISIBLE}.</li>
     * </ol>
     *
     * @return The estimated first hop upstream (device to network) bandwidth.
     */
    @RequiresFeature(
            enforcement = "android.telephony.TelephonyManager#isRadioInterfaceCapabilitySupported",
            value = TelephonyManager.CAPABILITY_SECONDARY_LINK_BANDWIDTH_VISIBLE)
    public int getSecondaryDownlinkCapacityKbps() {
        return mSecondaryDownlinkCapacityKbps;
    }

    /**
     * Retrieves the downstream bandwidth for the secondary network in Kbps.  This always only
     * refers to the estimated first hop transport bandwidth.
     * <p/>
     * This will be {@link #INVALID} if either are the case:
     * <ol>
     *  <li>The network is not connected</li>
     *  <li>The device does not support
     * {@link android.telephony.TelephonyManager#CAPABILITY_SECONDARY_LINK_BANDWIDTH_VISIBLE}.</li>
     * </ol>
     * @return The estimated first hop downstream (network to device) bandwidth.
     */
    @RequiresFeature(
            enforcement = "android.telephony.TelephonyManager#isRadioInterfaceCapabilitySupported",
            value = TelephonyManager.CAPABILITY_SECONDARY_LINK_BANDWIDTH_VISIBLE)
    public int getSecondaryUplinkCapacityKbps() {
        return mSecondaryUplinkCapacityKbps;
    }

    @NonNull
    @Override
    public String toString() {
        return "CarrierBandwidth: {primaryDownlinkCapacityKbps=" + mPrimaryDownlinkCapacityKbps
                + " primaryUplinkCapacityKbps=" + mPrimaryUplinkCapacityKbps
                + " secondaryDownlinkCapacityKbps=" + mSecondaryDownlinkCapacityKbps
                + " secondaryUplinkCapacityKbps=" + mSecondaryUplinkCapacityKbps
                + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mPrimaryDownlinkCapacityKbps,
                mPrimaryUplinkCapacityKbps,
                mSecondaryDownlinkCapacityKbps,
                mSecondaryUplinkCapacityKbps);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == null || !(o instanceof CallQuality) || hashCode() != o.hashCode()) {
            return false;
        }
        if (this == o) {
            return true;
        }
        CarrierBandwidth s = (CarrierBandwidth) o;
        return (mPrimaryDownlinkCapacityKbps == s.mPrimaryDownlinkCapacityKbps
                && mPrimaryUplinkCapacityKbps == s.mPrimaryUplinkCapacityKbps
                && mSecondaryDownlinkCapacityKbps == s.mSecondaryDownlinkCapacityKbps
                && mSecondaryDownlinkCapacityKbps == s.mSecondaryDownlinkCapacityKbps);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPrimaryDownlinkCapacityKbps);
        dest.writeInt(mPrimaryUplinkCapacityKbps);
        dest.writeInt(mSecondaryDownlinkCapacityKbps);
        dest.writeInt(mSecondaryUplinkCapacityKbps);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CarrierBandwidth> CREATOR =
            new Parcelable.Creator() {
            public CarrierBandwidth createFromParcel(Parcel in) {
                return new CarrierBandwidth(in);
            }

            public CarrierBandwidth[] newArray(int size) {
                return new CarrierBandwidth[size];
            }
    };
}
