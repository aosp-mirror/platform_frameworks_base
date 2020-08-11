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

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * This class represents a configuration for a Virtual Carrier Network.
 *
 * <p>Each {@link VcnGatewayConnectionConfig} instance added represents a connection that will be
 * brought up on demand based on active {@link NetworkRequest}(s).
 *
 * @see VcnManager for more information on the Virtual Carrier Network feature
 * @hide
 */
public final class VcnConfig implements Parcelable {
    @NonNull private static final String TAG = VcnConfig.class.getSimpleName();

    private static final String GATEWAY_CONNECTION_CONFIGS_KEY = "mGatewayConnectionConfigs";
    @NonNull private final Set<VcnGatewayConnectionConfig> mGatewayConnectionConfigs;

    private VcnConfig(@NonNull Set<VcnGatewayConnectionConfig> tunnelConfigs) {
        mGatewayConnectionConfigs = Collections.unmodifiableSet(tunnelConfigs);

        validate();
    }

    /**
     * Deserializes a VcnConfig from a PersistableBundle.
     *
     * @hide
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public VcnConfig(@NonNull PersistableBundle in) {
        final PersistableBundle gatewayConnectionConfigsBundle =
                in.getPersistableBundle(GATEWAY_CONNECTION_CONFIGS_KEY);
        mGatewayConnectionConfigs =
                new ArraySet<>(
                        PersistableBundleUtils.toList(
                                gatewayConnectionConfigsBundle, VcnGatewayConnectionConfig::new));

        validate();
    }

    private void validate() {
        Preconditions.checkCollectionNotEmpty(
                mGatewayConnectionConfigs, "gatewayConnectionConfigs");
    }

    /** Retrieves the set of configured tunnels. */
    @NonNull
    public Set<VcnGatewayConnectionConfig> getGatewayConnectionConfigs() {
        return Collections.unmodifiableSet(mGatewayConnectionConfigs);
    }

    /**
     * Serializes this object to a PersistableBundle.
     *
     * @hide
     */
    @NonNull
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = new PersistableBundle();

        final PersistableBundle gatewayConnectionConfigsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mGatewayConnectionConfigs),
                        VcnGatewayConnectionConfig::toPersistableBundle);
        result.putPersistableBundle(GATEWAY_CONNECTION_CONFIGS_KEY, gatewayConnectionConfigsBundle);

        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mGatewayConnectionConfigs);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof VcnConfig)) {
            return false;
        }

        final VcnConfig rhs = (VcnConfig) other;
        return mGatewayConnectionConfigs.equals(rhs.mGatewayConnectionConfigs);
    }

    // Parcelable methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(toPersistableBundle(), flags);
    }

    @NonNull
    public static final Parcelable.Creator<VcnConfig> CREATOR =
            new Parcelable.Creator<VcnConfig>() {
                @NonNull
                public VcnConfig createFromParcel(Parcel in) {
                    return new VcnConfig((PersistableBundle) in.readParcelable(null));
                }

                @NonNull
                public VcnConfig[] newArray(int size) {
                    return new VcnConfig[size];
                }
            };

    /** This class is used to incrementally build {@link VcnConfig} objects. */
    public static class Builder {
        @NonNull
        private final Set<VcnGatewayConnectionConfig> mGatewayConnectionConfigs = new ArraySet<>();

        /**
         * Adds a configuration for an individual gateway connection.
         *
         * @param gatewayConnectionConfig the configuration for an individual gateway connection
         * @return this {@link Builder} instance, for chaining
         */
        @NonNull
        public Builder addGatewayConnectionConfig(
                @NonNull VcnGatewayConnectionConfig gatewayConnectionConfig) {
            Objects.requireNonNull(gatewayConnectionConfig, "gatewayConnectionConfig was null");

            mGatewayConnectionConfigs.add(gatewayConnectionConfig);
            return this;
        }

        /**
         * Builds and validates the VcnConfig.
         *
         * @return an immutable VcnConfig instance
         */
        @NonNull
        public VcnConfig build() {
            return new VcnConfig(mGatewayConnectionConfigs);
        }
    }
}
