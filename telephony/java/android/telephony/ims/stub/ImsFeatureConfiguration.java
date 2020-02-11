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
 * limitations under the License
 */

package android.telephony.ims.stub;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ims.feature.ImsFeature;
import android.util.ArraySet;

import java.util.Set;

/**
 * Container class for IMS Feature configuration. This class contains the features that the
 * ImsService supports, which are defined in {@link ImsFeature} as
 * {@link ImsFeature#FEATURE_EMERGENCY_MMTEL}, {@link ImsFeature#FEATURE_MMTEL}, and
 * {@link ImsFeature#FEATURE_RCS}.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class ImsFeatureConfiguration implements Parcelable {

    public static final class FeatureSlotPair {
        /**
         * SIM slot that this feature is associated with.
         */
        public final int slotId;
        /**
         * The feature that this slotId supports. Supported values are
         * {@link ImsFeature#FEATURE_EMERGENCY_MMTEL}, {@link ImsFeature#FEATURE_MMTEL}, and
         * {@link ImsFeature#FEATURE_RCS}.
         */
        public final @ImsFeature.FeatureType int featureType;

        /**
         * A mapping from slotId to IMS Feature type.
         * @param slotId the SIM slot ID associated with this feature.
         * @param featureType The feature that this slotId supports. Supported values are
         * {@link ImsFeature#FEATURE_EMERGENCY_MMTEL}, {@link ImsFeature#FEATURE_MMTEL}, and
         * {@link ImsFeature#FEATURE_RCS}.
         */
        public FeatureSlotPair(int slotId, @ImsFeature.FeatureType int featureType) {
            this.slotId = slotId;
            this.featureType = featureType;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            FeatureSlotPair that = (FeatureSlotPair) o;

            if (slotId != that.slotId) return false;
            return featureType == that.featureType;
        }

        @Override
        public int hashCode() {
            int result = slotId;
            result = 31 * result + featureType;
            return result;
        }

        @NonNull
        @Override
        public String toString() {
            return "{s=" + slotId + ", f=" + ImsFeature.FEATURE_LOG_MAP.get(featureType) + "}";
        }
    }

    /**
     * Features that this ImsService supports.
     */
    private final Set<FeatureSlotPair> mFeatures;

    /**
     * Builder for {@link ImsFeatureConfiguration}.
     */
    public static class Builder {
            ImsFeatureConfiguration mConfig;
        public Builder() {
            mConfig = new ImsFeatureConfiguration();
        }

        /**
         * Adds an IMS feature associated with a SIM slot ID.
         * @param slotId The slot ID associated with the IMS feature.
         * @param featureType The feature that the slot ID supports. Supported values are
         * {@link ImsFeature#FEATURE_EMERGENCY_MMTEL}, {@link ImsFeature#FEATURE_MMTEL}, and
         * {@link ImsFeature#FEATURE_RCS}.
         * @return a {@link Builder} to continue constructing the ImsFeatureConfiguration.
         */
        public Builder addFeature(int slotId, @ImsFeature.FeatureType int featureType) {
            mConfig.addFeature(slotId, featureType);
            return this;
        }

        public ImsFeatureConfiguration build() {
            return mConfig;
        }
    }

    /**
     * Creates with all registration features empty.
     * @hide
     */
    public ImsFeatureConfiguration() {
        mFeatures = new ArraySet<>();
    }

    /**
     * Configuration of the ImsService, which describes which features the ImsService supports
     * (for registration).
     * @param features a set of {@link FeatureSlotPair}s that describe which features this
     *         ImsService supports.
     * @hide
     */
    public ImsFeatureConfiguration(Set<FeatureSlotPair> features) {
        mFeatures = new ArraySet<>();

        if (features != null) {
            mFeatures.addAll(features);
        }
    }

    /**
     * @return a set of supported slot ID to feature type pairs contained within a
     * {@link FeatureSlotPair}.
     */
    public Set<FeatureSlotPair> getServiceFeatures() {
        return new ArraySet<>(mFeatures);
    }

    /**
     * @hide
     */
    void addFeature(int slotId, int feature) {
        mFeatures.add(new FeatureSlotPair(slotId, feature));
    }

    /** @hide */
    protected ImsFeatureConfiguration(Parcel in) {
        int featurePairLength = in.readInt();
        // length
        mFeatures = new ArraySet<>(featurePairLength);
        for (int i = 0; i < featurePairLength; i++) {
            // pair of reads for each entry (slotId->featureType)
            mFeatures.add(new FeatureSlotPair(in.readInt(), in.readInt()));
        }
    }

    public static final @android.annotation.NonNull Creator<ImsFeatureConfiguration> CREATOR
            = new Creator<ImsFeatureConfiguration>() {
        @Override
        public ImsFeatureConfiguration createFromParcel(Parcel in) {
            return new ImsFeatureConfiguration(in);
        }

        @Override
        public ImsFeatureConfiguration[] newArray(int size) {
            return new ImsFeatureConfiguration[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        FeatureSlotPair[] featureSlotPairs = new FeatureSlotPair[mFeatures.size()];
        mFeatures.toArray(featureSlotPairs);
        // length of list
        dest.writeInt(featureSlotPairs.length);
        // then pairs of integers for each entry (slotId->featureType).
        for (FeatureSlotPair featureSlotPair : featureSlotPairs) {
            dest.writeInt(featureSlotPair.slotId);
            dest.writeInt(featureSlotPair.featureType);
        }
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImsFeatureConfiguration)) return false;

        ImsFeatureConfiguration
                that = (ImsFeatureConfiguration) o;

        return mFeatures.equals(that.mFeatures);
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return mFeatures.hashCode();
    }
}
