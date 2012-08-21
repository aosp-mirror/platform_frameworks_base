/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.location;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A Parcelable containing (legacy) location provider properties.
 * This object is just used inside the framework and system services.
 * @hide
 */
public final class ProviderProperties implements Parcelable {
    /**
     * True if provider requires access to a
     * data network (e.g., the Internet), false otherwise.
     */
    public final boolean mRequiresNetwork;

    /**
     * True if the provider requires access to a
     * satellite-based positioning system (e.g., GPS), false
     * otherwise.
     */
    public final boolean mRequiresSatellite;

    /**
     * True if the provider requires access to an appropriate
     * cellular network (e.g., to make use of cell tower IDs), false
     * otherwise.
     */
    public final boolean mRequiresCell;

    /**
     * True if the use of this provider may result in a
     * monetary charge to the user, false if use is free.  It is up to
     * each provider to give accurate information. Cell (network) usage
     * is not considered monetary cost.
     */
    public final boolean mHasMonetaryCost;

    /**
     * True if the provider is able to provide altitude
     * information, false otherwise.  A provider that reports altitude
     * under most circumstances but may occasionally not report it
     * should return true.
     */
    public final boolean mSupportsAltitude;

    /**
     * True if the provider is able to provide speed
     * information, false otherwise.  A provider that reports speed
     * under most circumstances but may occasionally not report it
     * should return true.
     */
    public final boolean mSupportsSpeed;

    /**
     * True if the provider is able to provide bearing
     * information, false otherwise.  A provider that reports bearing
     * under most circumstances but may occasionally not report it
     * should return true.
     */
    public final boolean mSupportsBearing;

    /**
     * Power requirement for this provider.
     *
     * @return the power requirement for this provider, as one of the
     * constants Criteria.POWER_*.
     */
    public final int mPowerRequirement;

    /**
     * Constant describing the horizontal accuracy returned
     * by this provider.
     *
     * @return the horizontal accuracy for this provider, as one of the
     * constants Criteria.ACCURACY_COARSE or Criteria.ACCURACY_FINE
     */
    public final int mAccuracy;

    public ProviderProperties(boolean mRequiresNetwork,
            boolean mRequiresSatellite, boolean mRequiresCell, boolean mHasMonetaryCost,
            boolean mSupportsAltitude, boolean mSupportsSpeed, boolean mSupportsBearing,
            int mPowerRequirement, int mAccuracy) {
        this.mRequiresNetwork = mRequiresNetwork;
        this.mRequiresSatellite = mRequiresSatellite;
        this.mRequiresCell = mRequiresCell;
        this.mHasMonetaryCost = mHasMonetaryCost;
        this.mSupportsAltitude = mSupportsAltitude;
        this.mSupportsSpeed = mSupportsSpeed;
        this.mSupportsBearing = mSupportsBearing;
        this.mPowerRequirement = mPowerRequirement;
        this.mAccuracy = mAccuracy;
    }

    public static final Parcelable.Creator<ProviderProperties> CREATOR =
            new Parcelable.Creator<ProviderProperties>() {
        @Override
        public ProviderProperties createFromParcel(Parcel in) {
            boolean requiresNetwork = in.readInt() == 1;
            boolean requiresSatellite = in.readInt() == 1;
            boolean requiresCell = in.readInt() == 1;
            boolean hasMonetaryCost = in.readInt() == 1;
            boolean supportsAltitude = in.readInt() == 1;
            boolean supportsSpeed = in.readInt() == 1;
            boolean supportsBearing = in.readInt() == 1;
            int powerRequirement = in.readInt();
            int accuracy = in.readInt();
            return new ProviderProperties(requiresNetwork, requiresSatellite,
                    requiresCell, hasMonetaryCost, supportsAltitude, supportsSpeed, supportsBearing,
                    powerRequirement, accuracy);
        }
        @Override
        public ProviderProperties[] newArray(int size) {
            return new ProviderProperties[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mRequiresNetwork ? 1 : 0);
        parcel.writeInt(mRequiresSatellite ? 1 : 0);
        parcel.writeInt(mRequiresCell ? 1 : 0);
        parcel.writeInt(mHasMonetaryCost ? 1 : 0);
        parcel.writeInt(mSupportsAltitude ? 1 : 0);
        parcel.writeInt(mSupportsSpeed ? 1 : 0);
        parcel.writeInt(mSupportsBearing ? 1 : 0);
        parcel.writeInt(mPowerRequirement);
        parcel.writeInt(mAccuracy);
    }
}
