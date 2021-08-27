/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.location.provider;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Location provider properties.
 */
public final class ProviderProperties implements Parcelable {

    /**
     * A constant indicating low power usage.
     */
    public static final int POWER_USAGE_LOW = 1;

    /**
     * A constant indicating a medium power usage.
     */
    public static final int POWER_USAGE_MEDIUM = 2;

    /**
     * A constant indicating high power usage.
     */
    public static final int POWER_USAGE_HIGH = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "POWER_USAGE_", value = {POWER_USAGE_LOW, POWER_USAGE_MEDIUM,
            POWER_USAGE_HIGH})
    public @interface PowerUsage {}

    /**
     * A constant indicating a finer location accuracy.
     */
    public static final int ACCURACY_FINE = 1;

    /**
     * A constant indicating a coarser location accuracy.
     */
    public static final int ACCURACY_COARSE = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ACCURACY_", value = {ACCURACY_FINE, ACCURACY_COARSE})
    public @interface Accuracy {}

    private final boolean mHasNetworkRequirement;
    private final boolean mHasSatelliteRequirement;
    private final boolean mHasCellRequirement;
    private final boolean mHasMonetaryCost;
    private final boolean mHasAltitudeSupport;
    private final boolean mHasSpeedSupport;
    private final boolean mHasBearingSupport;
    private final @PowerUsage int mPowerUsage;
    private final @Accuracy int mAccuracy;

    private ProviderProperties(boolean hasNetworkRequirement, boolean hasSatelliteRequirement,
            boolean hasCellRequirement, boolean hasMonetaryCost, boolean hasAltitudeSupport,
            boolean hasSpeedSupport, boolean hasBearingSupport,
            @PowerUsage int powerUsage, @Accuracy int accuracy) {
        mHasNetworkRequirement = hasNetworkRequirement;
        mHasSatelliteRequirement = hasSatelliteRequirement;
        mHasCellRequirement = hasCellRequirement;
        mHasMonetaryCost = hasMonetaryCost;
        mHasAltitudeSupport = hasAltitudeSupport;
        mHasSpeedSupport = hasSpeedSupport;
        mHasBearingSupport = hasBearingSupport;
        mPowerUsage = powerUsage;
        mAccuracy = accuracy;
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
     * Power usage for this provider.
     */
    public @PowerUsage int getPowerUsage() {
        return mPowerUsage;
    }

    /**
     * Rough location accuracy for this provider, primarily with respect to horizontal location
     * accuracy.
     */
    public @Accuracy int getAccuracy() {
        return mAccuracy;
    }

    public static final @NonNull Creator<ProviderProperties> CREATOR =
            new Creator<ProviderProperties>() {
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
                            /* powerUsage= */ in.readInt(),
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
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeBoolean(mHasNetworkRequirement);
        parcel.writeBoolean(mHasSatelliteRequirement);
        parcel.writeBoolean(mHasCellRequirement);
        parcel.writeBoolean(mHasMonetaryCost);
        parcel.writeBoolean(mHasAltitudeSupport);
        parcel.writeBoolean(mHasSpeedSupport);
        parcel.writeBoolean(mHasBearingSupport);
        parcel.writeInt(mPowerUsage);
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
                && mPowerUsage == that.mPowerUsage
                && mAccuracy == that.mAccuracy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mHasNetworkRequirement, mHasSatelliteRequirement, mHasCellRequirement,
                mHasMonetaryCost, mHasAltitudeSupport, mHasSpeedSupport, mHasBearingSupport,
                mPowerUsage, mAccuracy);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("ProviderProperties[");
        b.append("powerUsage=").append(powerToString(mPowerUsage)).append(", ");
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
                b.append("bearing,");
            }
            if (mHasSpeedSupport) {
                b.append("speed,");
            }
            if (mHasAltitudeSupport) {
                b.append("altitude,");
            }
            b.setLength(b.length() - 1);
            b.append("]");
        }
        b.append("]");
        return b.toString();
    }

    private static String powerToString(@PowerUsage int power) {
        switch (power) {
            case POWER_USAGE_LOW:
                return "Low";
            case POWER_USAGE_MEDIUM:
                return "Medium";
            case POWER_USAGE_HIGH:
                return "High";
            default:
                throw new AssertionError();
        }
    }

    private static String accuracyToString(@Accuracy int accuracy) {
        switch (accuracy) {
            case ACCURACY_COARSE:
                return "Coarse";
            case ACCURACY_FINE:
                return "Fine";
            default:
                throw new AssertionError();
        }
    }

    /**
     * Builder for ProviderProperties.
     */
    public static final class Builder {

        private boolean mHasNetworkRequirement;
        private boolean mHasSatelliteRequirement;
        private boolean mHasCellRequirement;
        private boolean mHasMonetaryCost;
        private boolean mHasAltitudeSupport;
        private boolean mHasSpeedSupport;
        private boolean mHasBearingSupport;
        private @PowerUsage int mPowerUsage;
        private @Accuracy int mAccuracy;

        public Builder() {
            mHasNetworkRequirement = false;
            mHasSatelliteRequirement = false;
            mHasCellRequirement = false;
            mHasMonetaryCost = false;
            mHasAltitudeSupport = false;
            mHasSpeedSupport = false;
            mHasBearingSupport = false;
            mPowerUsage = POWER_USAGE_HIGH;
            mAccuracy = ACCURACY_COARSE;
        }

        public Builder(@NonNull ProviderProperties providerProperties) {
            mHasNetworkRequirement = providerProperties.mHasNetworkRequirement;
            mHasSatelliteRequirement = providerProperties.mHasSatelliteRequirement;
            mHasCellRequirement = providerProperties.mHasCellRequirement;
            mHasMonetaryCost = providerProperties.mHasMonetaryCost;
            mHasAltitudeSupport = providerProperties.mHasAltitudeSupport;
            mHasSpeedSupport = providerProperties.mHasSpeedSupport;
            mHasBearingSupport = providerProperties.mHasBearingSupport;
            mPowerUsage = providerProperties.mPowerUsage;
            mAccuracy = providerProperties.mAccuracy;
        }

        /**
         * Sets whether a provider requires network access. False by default.
         */
        public @NonNull Builder setHasNetworkRequirement(boolean requiresNetwork) {
            mHasNetworkRequirement = requiresNetwork;
            return this;
        }

        /**
         * Sets whether a provider requires satellite access. False by default.
         */
        public @NonNull Builder setHasSatelliteRequirement(boolean requiresSatellite) {
            mHasSatelliteRequirement = requiresSatellite;
            return this;
        }

        /**
         * Sets whether a provider requires cell tower access. False by default.
         */
        public @NonNull Builder setHasCellRequirement(boolean requiresCell) {
            mHasCellRequirement = requiresCell;
            return this;
        }

        /**
         * Sets whether a provider has a monetary cost. False by default.
         */
        public @NonNull Builder setHasMonetaryCost(boolean monetaryCost) {
            mHasMonetaryCost = monetaryCost;
            return this;
        }

        /**
         * Sets whether a provider can provide altitude information. False by default.
         */
        public @NonNull Builder setHasAltitudeSupport(boolean supportsAltitude) {
            mHasAltitudeSupport = supportsAltitude;
            return this;
        }

        /**
         * Sets whether a provider can provide speed information. False by default.
         */
        public @NonNull Builder setHasSpeedSupport(boolean supportsSpeed) {
            mHasSpeedSupport = supportsSpeed;
            return this;
        }

        /**
         * Sets whether a provider can provide bearing information. False by default.
         */
        public @NonNull Builder setHasBearingSupport(boolean supportsBearing) {
            mHasBearingSupport = supportsBearing;
            return this;
        }

        /**
         * Sets a very rough bucket of provider power usage. {@link #POWER_USAGE_HIGH} by default.
         */
        public @NonNull Builder setPowerUsage(@PowerUsage int powerUsage) {
            mPowerUsage = Preconditions.checkArgumentInRange(powerUsage, POWER_USAGE_LOW,
                    POWER_USAGE_HIGH, "powerUsage");
            return this;
        }

        /**
         * Sets a very rough bucket of provider location accuracy. {@link #ACCURACY_COARSE} by
         * default.
         */
        public @NonNull Builder setAccuracy(@Accuracy int accuracy) {
            mAccuracy = Preconditions.checkArgumentInRange(accuracy, ACCURACY_FINE,
                    ACCURACY_COARSE, "accuracy");
            return this;
        }

        /**
         * Builds a new ProviderProperties.
         */
        public @NonNull ProviderProperties build() {
            return new ProviderProperties(mHasNetworkRequirement, mHasSatelliteRequirement,
                    mHasCellRequirement, mHasMonetaryCost, mHasAltitudeSupport, mHasSpeedSupport,
                    mHasBearingSupport, mPowerUsage, mAccuracy);
        }
    }
}
