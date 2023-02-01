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

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.server.vcn.util.PersistableBundleUtils.INTEGER_DESERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.INTEGER_SERIALIZER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * This class represents a configuration for a Virtual Carrier Network.
 *
 * <p>Each {@link VcnGatewayConnectionConfig} instance added represents a connection that will be
 * brought up on demand based on active {@link NetworkRequest}(s).
 *
 * @see VcnManager for more information on the Virtual Carrier Network feature
 */
public final class VcnConfig implements Parcelable {
    @NonNull private static final String TAG = VcnConfig.class.getSimpleName();

    private static final Set<Integer> ALLOWED_TRANSPORTS = new ArraySet<>();

    static {
        ALLOWED_TRANSPORTS.add(TRANSPORT_WIFI);
        ALLOWED_TRANSPORTS.add(TRANSPORT_CELLULAR);
    }

    private static final String PACKAGE_NAME_KEY = "mPackageName";
    @NonNull private final String mPackageName;

    private static final String GATEWAY_CONNECTION_CONFIGS_KEY = "mGatewayConnectionConfigs";
    @NonNull private final Set<VcnGatewayConnectionConfig> mGatewayConnectionConfigs;

    private static final Set<Integer> RESTRICTED_TRANSPORTS_DEFAULT =
            Collections.singleton(TRANSPORT_WIFI);
    private static final String RESTRICTED_TRANSPORTS_KEY = "mRestrictedTransports";
    @NonNull private final Set<Integer> mRestrictedTransports;

    private static final String IS_TEST_MODE_PROFILE_KEY = "mIsTestModeProfile";
    private final boolean mIsTestModeProfile;

    private VcnConfig(
            @NonNull String packageName,
            @NonNull Set<VcnGatewayConnectionConfig> gatewayConnectionConfigs,
            @NonNull Set<Integer> restrictedTransports,
            boolean isTestModeProfile) {
        mPackageName = packageName;
        mGatewayConnectionConfigs =
                Collections.unmodifiableSet(new ArraySet<>(gatewayConnectionConfigs));
        mRestrictedTransports = Collections.unmodifiableSet(new ArraySet<>(restrictedTransports));
        mIsTestModeProfile = isTestModeProfile;

        validate();
    }

    /**
     * Deserializes a VcnConfig from a PersistableBundle.
     *
     * @hide
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public VcnConfig(@NonNull PersistableBundle in) {
        mPackageName = in.getString(PACKAGE_NAME_KEY);

        final PersistableBundle gatewayConnectionConfigsBundle =
                in.getPersistableBundle(GATEWAY_CONNECTION_CONFIGS_KEY);
        mGatewayConnectionConfigs =
                new ArraySet<>(
                        PersistableBundleUtils.toList(
                                gatewayConnectionConfigsBundle, VcnGatewayConnectionConfig::new));

        final PersistableBundle restrictedTransportsBundle =
                in.getPersistableBundle(RESTRICTED_TRANSPORTS_KEY);
        if (restrictedTransportsBundle == null) {
            // RESTRICTED_TRANSPORTS_KEY was added in U and does not exist in VcnConfigs created in
            // older platforms
            mRestrictedTransports = RESTRICTED_TRANSPORTS_DEFAULT;
        } else {
            mRestrictedTransports =
                    new ArraySet<Integer>(
                            PersistableBundleUtils.toList(
                                    restrictedTransportsBundle, INTEGER_DESERIALIZER));
        }

        mIsTestModeProfile = in.getBoolean(IS_TEST_MODE_PROFILE_KEY);

        validate();
    }

    private void validate() {
        Objects.requireNonNull(mPackageName, "packageName was null");
        Preconditions.checkCollectionNotEmpty(
                mGatewayConnectionConfigs, "gatewayConnectionConfigs was empty");

        final Iterator<Integer> iterator = mRestrictedTransports.iterator();
        while (iterator.hasNext()) {
            final int transport = iterator.next();
            if (!ALLOWED_TRANSPORTS.contains(transport)) {
                iterator.remove();
                Log.w(
                        TAG,
                        "Found invalid transport "
                                + transport
                                + " which might be from a new version of VcnConfig");
            }
        }
    }

    /**
     * Retrieve the package name of the provisioning app.
     *
     * @hide
     */
    @NonNull
    public String getProvisioningPackageName() {
        return mPackageName;
    }

    /** Retrieves the set of configured GatewayConnection(s). */
    @NonNull
    public Set<VcnGatewayConnectionConfig> getGatewayConnectionConfigs() {
        return Collections.unmodifiableSet(mGatewayConnectionConfigs);
    }

    /**
     * Retrieve the transports that will be restricted by the VCN.
     *
     * @see Builder#setRestrictedUnderlyingNetworkTransports(Set)
     */
    @NonNull
    public Set<Integer> getRestrictedUnderlyingNetworkTransports() {
        return Collections.unmodifiableSet(mRestrictedTransports);
    }

    /**
     * Returns whether or not this VcnConfig is restricted to test networks.
     *
     * @hide
     */
    public boolean isTestModeProfile() {
        return mIsTestModeProfile;
    }

    /**
     * Serializes this object to a PersistableBundle.
     *
     * @hide
     */
    @NonNull
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = new PersistableBundle();

        result.putString(PACKAGE_NAME_KEY, mPackageName);

        final PersistableBundle gatewayConnectionConfigsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mGatewayConnectionConfigs),
                        VcnGatewayConnectionConfig::toPersistableBundle);
        result.putPersistableBundle(GATEWAY_CONNECTION_CONFIGS_KEY, gatewayConnectionConfigsBundle);

        final PersistableBundle restrictedTransportsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mRestrictedTransports), INTEGER_SERIALIZER);
        result.putPersistableBundle(RESTRICTED_TRANSPORTS_KEY, restrictedTransportsBundle);

        result.putBoolean(IS_TEST_MODE_PROFILE_KEY, mIsTestModeProfile);

        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mPackageName, mGatewayConnectionConfigs, mRestrictedTransports, mIsTestModeProfile);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof VcnConfig)) {
            return false;
        }

        final VcnConfig rhs = (VcnConfig) other;
        return mPackageName.equals(rhs.mPackageName)
                && mGatewayConnectionConfigs.equals(rhs.mGatewayConnectionConfigs)
                && mRestrictedTransports.equals(rhs.mRestrictedTransports)
                && mIsTestModeProfile == rhs.mIsTestModeProfile;
    }

    // Parcelable methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(toPersistableBundle(), flags);
    }

    @NonNull
    public static final Parcelable.Creator<VcnConfig> CREATOR =
            new Parcelable.Creator<VcnConfig>() {
                @NonNull
                public VcnConfig createFromParcel(Parcel in) {
                    return new VcnConfig((PersistableBundle) in.readParcelable(null, android.os.PersistableBundle.class));
                }

                @NonNull
                public VcnConfig[] newArray(int size) {
                    return new VcnConfig[size];
                }
            };

    /** This class is used to incrementally build {@link VcnConfig} objects. */
    public static final class Builder {
        @NonNull private final String mPackageName;

        @NonNull
        private final Set<VcnGatewayConnectionConfig> mGatewayConnectionConfigs = new ArraySet<>();

        @NonNull private final Set<Integer> mRestrictedTransports = new ArraySet<>();

        private boolean mIsTestModeProfile = false;

        public Builder(@NonNull Context context) {
            Objects.requireNonNull(context, "context was null");

            mPackageName = context.getOpPackageName();
            mRestrictedTransports.addAll(RESTRICTED_TRANSPORTS_DEFAULT);
        }

        /**
         * Adds a configuration for an individual gateway connection.
         *
         * @param gatewayConnectionConfig the configuration for an individual gateway connection
         * @return this {@link Builder} instance, for chaining
         * @throws IllegalArgumentException if a VcnGatewayConnectionConfig has already been set for
         *     this {@link VcnConfig} with the same GatewayConnection name (as returned via {@link
         *     VcnGatewayConnectionConfig#getGatewayConnectionName()}).
         */
        @NonNull
        public Builder addGatewayConnectionConfig(
                @NonNull VcnGatewayConnectionConfig gatewayConnectionConfig) {
            Objects.requireNonNull(gatewayConnectionConfig, "gatewayConnectionConfig was null");

            for (final VcnGatewayConnectionConfig vcnGatewayConnectionConfig :
                    mGatewayConnectionConfigs) {
                if (vcnGatewayConnectionConfig
                        .getGatewayConnectionName()
                        .equals(gatewayConnectionConfig.getGatewayConnectionName())) {
                    throw new IllegalArgumentException(
                            "GatewayConnection for specified name already exists");
                }
            }

            mGatewayConnectionConfigs.add(gatewayConnectionConfig);
            return this;
        }

        private void validateRestrictedTransportsOrThrow(Set<Integer> restrictedTransports) {
            Objects.requireNonNull(restrictedTransports, "transports was null");

            for (int transport : restrictedTransports) {
                if (!ALLOWED_TRANSPORTS.contains(transport)) {
                    throw new IllegalArgumentException("Invalid transport " + transport);
                }
            }
        }

        /**
         * Sets transports that will be restricted by the VCN.
         *
         * @param transports transports that will be restricted by VCN. Networks that include any
         *     of the transports will be marked as restricted. Only {@link
         *     NetworkCapabilities#TRANSPORT_WIFI} and {@link
         *     NetworkCapabilities#TRANSPORT_CELLULAR} are allowed. {@link
         *     NetworkCapabilities#TRANSPORT_WIFI} is marked restricted by default.
         * @return this {@link Builder} instance, for chaining
         * @throws IllegalArgumentException if the input contains unsupported transport types.
         */
        @NonNull
        public Builder setRestrictedUnderlyingNetworkTransports(@NonNull Set<Integer> transports) {
            validateRestrictedTransportsOrThrow(transports);

            mRestrictedTransports.clear();
            mRestrictedTransports.addAll(transports);
            return this;
        }

        /**
         * Restricts this VcnConfig to matching with test networks (only).
         *
         * <p>This method is for testing only, and must not be used by apps. Calling {@link
         * VcnManager#setVcnConfig(ParcelUuid, VcnConfig)} with a VcnConfig where test-network usage
         * is enabled will require the MANAGE_TEST_NETWORKS permission.
         *
         * @return this {@link Builder} instance, for chaining
         * @hide
         */
        @NonNull
        public Builder setIsTestModeProfile() {
            mIsTestModeProfile = true;
            return this;
        }

        /**
         * Builds and validates the VcnConfig.
         *
         * @return an immutable VcnConfig instance
         */
        @NonNull
        public VcnConfig build() {
            return new VcnConfig(
                    mPackageName,
                    mGatewayConnectionConfigs,
                    mRestrictedTransports,
                    mIsTestModeProfile);
        }
    }
}
