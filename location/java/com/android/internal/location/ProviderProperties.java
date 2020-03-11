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

import android.annotation.IntDef;
import android.location.Criteria;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Location provider properties.
 * @hide
 */
public final class ProviderProperties implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Criteria.POWER_LOW, Criteria.POWER_MEDIUM, Criteria.POWER_HIGH})
    public @interface PowerRequirement {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Criteria.ACCURACY_FINE, Criteria.ACCURACY_COARSE})
    public @interface Accuracy {}

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
     */
    @PowerRequirement
    public final int mPowerRequirement;

    /**
     * Constant describing the horizontal accuracy returned
     * by this provider.
     */
    @Accuracy
    public final int mAccuracy;

    public ProviderProperties(boolean requiresNetwork, boolean requiresSatellite,
            boolean requiresCell, boolean hasMonetaryCost, boolean supportsAltitude,
            boolean supportsSpeed, boolean supportsBearing, @PowerRequirement int powerRequirement,
            @Accuracy int accuracy) {
        mRequiresNetwork = requiresNetwork;
        mRequiresSatellite = requiresSatellite;
        mRequiresCell = requiresCell;
        mHasMonetaryCost = hasMonetaryCost;
        mSupportsAltitude = supportsAltitude;
        mSupportsSpeed = supportsSpeed;
        mSupportsBearing = supportsBearing;
        mPowerRequirement = Preconditions.checkArgumentInRange(powerRequirement, Criteria.POWER_LOW,
                Criteria.POWER_HIGH, "powerRequirement");
        mAccuracy = Preconditions.checkArgumentInRange(accuracy, Criteria.ACCURACY_FINE,
                Criteria.ACCURACY_COARSE, "accuracy");
    }

    public static final Parcelable.Creator<ProviderProperties> CREATOR =
            new Parcelable.Creator<ProviderProperties>() {
                @Override
                public ProviderProperties createFromParcel(Parcel in) {
                    return new ProviderProperties(
                            /* requiresNetwork= */ in.readBoolean(),
                            /* requiresSatellite= */ in.readBoolean(),
                            /* requiresCell= */ in.readBoolean(),
                            /* hasMonetaryCost= */ in.readBoolean(),
                            /* supportsAltitude= */ in.readBoolean(),
                            /* supportsSpeed= */ in.readBoolean(),
                            /* supportsBearing= */ in.readBoolean(),
                            /* powerRequirement= */ in.readInt(),
                            /* accuracy= */ in.readInt());
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
        parcel.writeBoolean(mRequiresNetwork);
        parcel.writeBoolean(mRequiresSatellite);
        parcel.writeBoolean(mRequiresCell);
        parcel.writeBoolean(mHasMonetaryCost);
        parcel.writeBoolean(mSupportsAltitude);
        parcel.writeBoolean(mSupportsSpeed);
        parcel.writeBoolean(mSupportsBearing);
        parcel.writeInt(mPowerRequirement);
        parcel.writeInt(mAccuracy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderProperties)) {
            return false;
        }
        ProviderProperties that = (ProviderProperties) o;
        return mRequiresNetwork == that.mRequiresNetwork
                && mRequiresSatellite == that.mRequiresSatellite
                && mRequiresCell == that.mRequiresCell
                && mHasMonetaryCost == that.mHasMonetaryCost
                && mSupportsAltitude == that.mSupportsAltitude
                && mSupportsSpeed == that.mSupportsSpeed
                && mSupportsBearing == that.mSupportsBearing
                && mPowerRequirement == that.mPowerRequirement
                && mAccuracy == that.mAccuracy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRequiresNetwork, mRequiresSatellite, mRequiresCell, mHasMonetaryCost,
                mSupportsAltitude, mSupportsSpeed, mSupportsBearing, mPowerRequirement, mAccuracy);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("ProviderProperties[");
        b.append("power=").append(powerToString(mPowerRequirement)).append(", ");
        b.append("accuracy=").append(accuracyToString(mAccuracy));
        if (mRequiresNetwork || mRequiresSatellite || mRequiresCell) {
            b.append(", requires=");
            if (mRequiresNetwork) {
                b.append("network,");
            }
            if (mRequiresSatellite) {
                b.append("satellite,");
            }
            if (mRequiresCell) {
                b.append("cell,");
            }
            b.setLength(b.length() - 1);
        }
        if (mHasMonetaryCost) {
            b.append(", hasMonetaryCost");
        }
        if (mSupportsBearing || mSupportsSpeed || mSupportsAltitude) {
            b.append(", supports=[");
            if (mSupportsBearing) {
                b.append("bearing, ");
            }
            if (mSupportsSpeed) {
                b.append("speed, ");
            }
            if (mSupportsAltitude) {
                b.append("altitude, ");
            }
            b.setLength(b.length() - 2);
            b.append("]");
        }
        b.append("]");
        return b.toString();
    }

    private static String powerToString(@PowerRequirement int power) {
        switch (power) {
            case Criteria.POWER_LOW:
                return "Low";
            case Criteria.POWER_MEDIUM:
                return "Medium";
            case Criteria.POWER_HIGH:
                return "High";
            default:
                return "???";
        }
    }

    private static String accuracyToString(@Accuracy int accuracy) {
        switch (accuracy) {
            case Criteria.ACCURACY_COARSE:
                return "Coarse";
            case Criteria.ACCURACY_FINE:
                return "Fine";
            default:
                return "???";
        }
    }
}
