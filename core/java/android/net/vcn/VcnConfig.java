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
package android.net.vcn;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class represents a configuration for a Virtual Carrier Network.
 *
 * @hide
 */
public final class VcnConfig implements Parcelable {
    @NonNull private static final String TAG = VcnConfig.class.getSimpleName();

    private VcnConfig() {
        validate();
    }
    // TODO: Implement getters, validators, etc

    /**
     * Validates this configuration.
     *
     * @hide
     */
    private void validate() {
        // TODO: implement validation logic
    }

    // Parcelable methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {}

    @NonNull
    public static final Parcelable.Creator<VcnConfig> CREATOR =
            new Parcelable.Creator<VcnConfig>() {
                @NonNull
                public VcnConfig createFromParcel(Parcel in) {
                    // TODO: Ensure all methods are pulled from the parcels
                    return new VcnConfig();
                }

                @NonNull
                public VcnConfig[] newArray(int size) {
                    return new VcnConfig[size];
                }
            };

    /** This class is used to incrementally build {@link VcnConfig} objects. */
    public static class Builder {
        // TODO: Implement this builder

        /**
         * Builds and validates the VcnConfig.
         *
         * @return an immutable VcnConfig instance
         */
        @NonNull
        public VcnConfig build() {
            return new VcnConfig();
        }
    }
}
