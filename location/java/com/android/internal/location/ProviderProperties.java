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

    private final boolean mHasNetworkRequirement;
    private final boolean mHasSatelliteRequirement;
    private final boolean mHasCellRequirement;
    private final boolean mHasMonetaryCost;
    private final boolean mHasAltitudeSupport;
    private final boolean mHasSpeedSupport;
    private final boolean mHasBearingSupport;
    private final @PowerRequirement int mPowerRequirement;
    private final @Accuracy int mAccuracy;

    public ProviderProperties(boolean hasNetworkRequirement, boolean hasSatelliteRequirement,
            boolean hasCellRequirement, boolean hasMonetaryCost, boolean hasAltitudeSupport,
            boolean hasSpeedSupport, boolean hasBearingSupport,
            @PowerRequirement int powerRequirement, @Accuracy int accuracy) {
        mHasNetworkRequirement = hasNetworkRequirement;
        mHasSatelliteRequirement = hasSatelliteRequirement;
        mHasCellRequirement = hasCellRequirement;
        mHasMonetaryCost = hasMonetaryCost;
        mHasAltitudeSupport = hasAltitudeSupport;
        mHasSpeedSupport = hasSpeedSupport;
        mHasBearingSupport = hasBearingSupport;
        mPowerRequirement = Preconditions.checkArgumentInRange(powerRequirement, Criteria.POWER_LOW,
                Criteria.POWER_HIGH, "powerRequirement");
        mAccuracy = Preconditions.checkArgumentInRange(accuracy, Criteria.ACCURACY_FINE,
                Criteria.ACCURACY_COARSE, "accuracy");
    }

    /**
     * True if provider requires access to a data network (e.g., the Internet).
     */
    public boolean hasNetworkRequirement() {
        return mHasNetworkRequirement;
    }

    /**
     * True if the provider requires access to a satellite-based positioning system (e.g., GPS).
     */
    public boolean hasSatelliteRequirement() {
        return mHasSatelliteRequirement;
    }

    /**
     * True if the provider requires access to a cellular network (e.g., for cell tower IDs).
     */
    public boolean hasCellRequirement() {
        return mHasCellRequirement;
    }

    /**
     * True if this provider may result in a monetary charge to the user. Network usage is not
     * considered a monetary cost.
     */
    public boolean hasMonetaryCost() {
        return mHasMonetaryCost;
    }

    /**
     * True if the provider is able to provide altitude under at least some conditions.
     */
    public boolean hasAltitudeSupport() {
        return mHasAltitudeSupport;
    }

    /**
     * True if the provider is able to provide speed under at least some conditions.
     */
    public boolean hasSpeedSupport() {
        return mHasSpeedSupport;
    }

    /**
     * True if the provider is able to provide bearing under at least some conditions.
     */
    public boolean hasBearingSupport() {
        return mHasBearingSupport;
    }

    /**
     * Power requirement for this provider.
     */
    public @PowerRequirement int getPowerRequirement() {
        return mPowerRequirement;
    }

    /**
     * Constant describing the horizontal accuracy returned
     * by this provider.
     */
    public @Accuracy int getAccuracy() {
        return mAccuracy;
    }

    public static final Parcelable.Creator<ProviderProperties> CREATOR =
            new Parcelable.Creator<ProviderProperties>() {
                @Override
                public ProviderProperties createFromParcel(Parcel in) {
                    return new ProviderProperties(
                            /* hasNetworkRequirement= */ in.readBoolean(),
                            /* hasSatelliteRequirement= */ in.readBoolean(),
                            /* hasCellRequirement= */ in.readBoolean(),
                            /* hasMonetaryCost= */ in.readBoolean(),
                            /* hasAltitudeSupport= */ in.readBoolean(),
                            /* hasSpeedSupport= */ in.readBoolean(),
                            /* hasBearingSupport= */ in.readBoolean(),
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
        parcel.writeBoolean(mHasNetworkRequirement);
        parcel.writeBoolean(mHasSatelliteRequirement);
        parcel.writeBoolean(mHasCellRequirement);
        parcel.writeBoolean(mHasMonetaryCost);
        parcel.writeBoolean(mHasAltitudeSupport);
        parcel.writeBoolean(mHasSpeedSupport);
        parcel.writeBoolean(mHasBearingSupport);
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
        return mHasNetworkRequirement == that.mHasNetworkRequirement
                && mHasSatelliteRequirement == that.mHasSatelliteRequirement
                && mHasCellRequirement == that.mHasCellRequirement
                && mHasMonetaryCost == that.mHasMonetaryCost
                && mHasAltitudeSupport == that.mHasAltitudeSupport
                && mHasSpeedSupport == that.mHasSpeedSupport
                && mHasBearingSupport == that.mHasBearingSupport
                && mPowerRequirement == that.mPowerRequirement
                && mAccuracy == that.mAccuracy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mHasNetworkRequirement, mHasSatelliteRequirement, mHasCellRequirement,
                mHasMonetaryCost, mHasAltitudeSupport, mHasSpeedSupport, mHasBearingSupport,
                mPowerRequirement, mAccuracy);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("ProviderProperties[");
        b.append("power=").append(powerToString(mPowerRequirement)).append(", ");
        b.append("accuracy=").append(accuracyToString(mAccuracy));
        if (mHasNetworkRequirement || mHasSatelliteRequirement || mHasCellRequirement) {
            b.append(", requires=");
            if (mHasNetworkRequirement) {
                b.append("network,");
            }
            if (mHasSatelliteRequirement) {
                b.append("satellite,");
            }
            if (mHasCellRequirement) {
                b.append("cell,");
            }
            b.setLength(b.length() - 1);
        }
        if (mHasMonetaryCost) {
            b.append(", hasMonetaryCost");
        }
        if (mHasBearingSupport || mHasSpeedSupport || mHasAltitudeSupport) {
            b.append(", supports=[");
            if (mHasBearingSupport) {
                b.append("bearing, ");
            }
            if (mHasSpeedSupport) {
                b.append("speed, ");
            }
            if (mHasAltitudeSupport) {
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
