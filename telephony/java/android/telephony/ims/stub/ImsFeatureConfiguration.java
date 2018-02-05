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

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ims.feature.ImsFeature;
import android.util.ArraySet;

import java.util.Set;

/**
 * Container class for IMS Feature configuration. This class contains the features that the
 * ImsService supports, which are defined in {@link ImsFeature.FeatureType}.
 * @hide
 */
public class ImsFeatureConfiguration implements Parcelable {
    /**
     * Features that this ImsService supports.
     */
    private final Set<Integer> mFeatures;

    /**
     * Creates an ImsFeatureConfiguration with the features
     */
    public static class Builder {
            ImsFeatureConfiguration mConfig;
        public Builder() {
            mConfig = new ImsFeatureConfiguration();
        }

        /**
         * @param feature A feature defined in {@link ImsFeature.FeatureType} that this service
         *         supports.
         * @return a {@link Builder} to continue constructing the ImsFeatureConfiguration.
         */
        public Builder addFeature(@ImsFeature.FeatureType int feature) {
            mConfig.addFeature(feature);
            return this;
        }

        public ImsFeatureConfiguration build() {
            return mConfig;
        }
    }

    /**
     * Creates with all registration features empty.
     *
     * Consider using the provided {@link Builder} to create this configuration instead.
     */
    public ImsFeatureConfiguration() {
        mFeatures = new ArraySet<>();
    }

    /**
     * Configuration of the ImsService, which describes which features the ImsService supports
     * (for registration).
     * @param features an array of feature integers defined in {@link ImsFeature} that describe
     * which features this ImsService supports.
     */
    public ImsFeatureConfiguration(int[] features) {
        mFeatures = new ArraySet<>();

        if (features != null) {
            for (int i : features) {
                mFeatures.add(i);
            }
        }
    }

    /**
     * @return an int[] containing the features that this ImsService supports.
     */
    public int[] getServiceFeatures() {
        return mFeatures.stream().mapToInt(i->i).toArray();
    }

    void addFeature(int feature) {
        mFeatures.add(feature);
    }

    protected ImsFeatureConfiguration(Parcel in) {
        int[] features = in.createIntArray();
        if (features != null) {
            mFeatures = new ArraySet<>(features.length);
            for(Integer i : features) {
                mFeatures.add(i);
            }
        } else {
            mFeatures = new ArraySet<>();
        }
    }

    public static final Creator<ImsFeatureConfiguration> CREATOR
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
        dest.writeIntArray(mFeatures.stream().mapToInt(i->i).toArray());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImsFeatureConfiguration)) return false;

        ImsFeatureConfiguration
                that = (ImsFeatureConfiguration) o;

        return mFeatures.equals(that.mFeatures);
    }

    @Override
    public int hashCode() {
        return mFeatures.hashCode();
    }
}
