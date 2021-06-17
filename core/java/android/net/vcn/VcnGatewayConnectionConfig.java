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

import static android.net.ipsec.ike.IkeSessionParams.IKE_OPTION_MOBIKE;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ipsec.ike.IkeTunnelConnectionParams;
import android.net.vcn.persistablebundleutils.TunnelConnectionParamsUtils;
import android.os.PersistableBundle;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * This class represents a configuration for a connection to a Virtual Carrier Network gateway.
 *
 * <p>Each VcnGatewayConnectionConfig represents a single logical connection to a carrier gateway,
 * and may provide one or more telephony services (as represented by network capabilities). Each
 * gateway is expected to provide mobility for a given session as the device roams across {@link
 * Network}s.
 *
 * <p>A VCN connection based on this configuration will be brought up dynamically based on device
 * settings, and filed NetworkRequests. Underlying Networks must provide INTERNET connectivity, and
 * must be part of the subscription group under which this configuration is registered (see {@link
 * VcnManager#setVcnConfig}).
 *
 * <p>As an abstraction of a cellular network, services that can be provided by a VCN network are
 * limited to services provided by cellular networks:
 *
 * <ul>
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_MMS}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_SUPL}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_DUN}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_FOTA}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_IMS}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_CBS}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_IA}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_RCS}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_XCAP}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_EIMS}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_INTERNET}
 *   <li>{@link NetworkCapabilities#NET_CAPABILITY_MCX}
 * </ul>
 */
public final class VcnGatewayConnectionConfig {
    // TODO: Use MIN_MTU_V6 once it is public, @hide
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int MIN_MTU_V6 = 1280;

    /**
     * The set of allowed capabilities for exposed capabilities.
     *
     * @hide
     */
    public static final Set<Integer> ALLOWED_CAPABILITIES;

    static {
        Set<Integer> allowedCaps = new ArraySet<>();
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_MMS);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_SUPL);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_DUN);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_FOTA);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_IMS);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_CBS);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_IA);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_RCS);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_XCAP);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_EIMS);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        allowedCaps.add(NetworkCapabilities.NET_CAPABILITY_MCX);

        ALLOWED_CAPABILITIES = Collections.unmodifiableSet(allowedCaps);
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"NET_CAPABILITY_"},
            value = {
                NetworkCapabilities.NET_CAPABILITY_MMS,
                NetworkCapabilities.NET_CAPABILITY_SUPL,
                NetworkCapabilities.NET_CAPABILITY_DUN,
                NetworkCapabilities.NET_CAPABILITY_FOTA,
                NetworkCapabilities.NET_CAPABILITY_IMS,
                NetworkCapabilities.NET_CAPABILITY_CBS,
                NetworkCapabilities.NET_CAPABILITY_IA,
                NetworkCapabilities.NET_CAPABILITY_RCS,
                NetworkCapabilities.NET_CAPABILITY_XCAP,
                NetworkCapabilities.NET_CAPABILITY_EIMS,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_MCX,
            })
    public @interface VcnSupportedCapability {}

    private static final int DEFAULT_MAX_MTU = 1500;

    /**
     * The maximum number of retry intervals that may be specified.
     *
     * <p>Limited to ensure an upper bound on config sizes.
     */
    private static final int MAX_RETRY_INTERVAL_COUNT = 10;

    /**
     * The minimum allowable repeating retry interval
     *
     * <p>To ensure the device is not constantly being woken up, this retry interval MUST be greater
     * than this value.
     *
     * @see {@link Builder#setRetryIntervalsMillis()}
     */
    private static final long MINIMUM_REPEATING_RETRY_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);

    private static final long[] DEFAULT_RETRY_INTERVALS_MS =
            new long[] {
                TimeUnit.SECONDS.toMillis(1),
                TimeUnit.SECONDS.toMillis(2),
                TimeUnit.SECONDS.toMillis(5),
                TimeUnit.SECONDS.toMillis(30),
                TimeUnit.MINUTES.toMillis(1),
                TimeUnit.MINUTES.toMillis(5),
                TimeUnit.MINUTES.toMillis(15)
            };
    private static final String GATEWAY_CONNECTION_NAME_KEY = "mGatewayConnectionName";
    @NonNull private final String mGatewayConnectionName;

    private static final String TUNNEL_CONNECTION_PARAMS_KEY = "mTunnelConnectionParams";
    @NonNull private IkeTunnelConnectionParams mTunnelConnectionParams;

    private static final String EXPOSED_CAPABILITIES_KEY = "mExposedCapabilities";
    @NonNull private final SortedSet<Integer> mExposedCapabilities;

    private static final String MAX_MTU_KEY = "mMaxMtu";
    private final int mMaxMtu;

    private static final String RETRY_INTERVAL_MS_KEY = "mRetryIntervalsMs";
    @NonNull private final long[] mRetryIntervalsMs;

    /** Builds a VcnGatewayConnectionConfig with the specified parameters. */
    private VcnGatewayConnectionConfig(
            @NonNull String gatewayConnectionName,
            @NonNull IkeTunnelConnectionParams tunnelConnectionParams,
            @NonNull Set<Integer> exposedCapabilities,
            @NonNull long[] retryIntervalsMs,
            @IntRange(from = MIN_MTU_V6) int maxMtu) {
        mGatewayConnectionName = gatewayConnectionName;
        mTunnelConnectionParams = tunnelConnectionParams;
        mExposedCapabilities = new TreeSet(exposedCapabilities);
        mRetryIntervalsMs = retryIntervalsMs;
        mMaxMtu = maxMtu;

        validate();
    }

    /** @hide */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public VcnGatewayConnectionConfig(@NonNull PersistableBundle in) {
        final PersistableBundle tunnelConnectionParamsBundle =
                in.getPersistableBundle(TUNNEL_CONNECTION_PARAMS_KEY);
        Objects.requireNonNull(
                tunnelConnectionParamsBundle, "tunnelConnectionParamsBundle was null");

        final PersistableBundle exposedCapsBundle =
                in.getPersistableBundle(EXPOSED_CAPABILITIES_KEY);

        mGatewayConnectionName = in.getString(GATEWAY_CONNECTION_NAME_KEY);
        mTunnelConnectionParams =
                TunnelConnectionParamsUtils.fromPersistableBundle(tunnelConnectionParamsBundle);
        mExposedCapabilities = new TreeSet<>(PersistableBundleUtils.toList(
                exposedCapsBundle, PersistableBundleUtils.INTEGER_DESERIALIZER));
        mRetryIntervalsMs = in.getLongArray(RETRY_INTERVAL_MS_KEY);
        mMaxMtu = in.getInt(MAX_MTU_KEY);

        validate();
    }

    private void validate() {
        Objects.requireNonNull(mGatewayConnectionName, "gatewayConnectionName was null");
        Objects.requireNonNull(mTunnelConnectionParams, "tunnel connection parameter was null");

        Preconditions.checkArgument(
                mExposedCapabilities != null && !mExposedCapabilities.isEmpty(),
                "exposedCapsBundle was null or empty");
        for (Integer cap : getAllExposedCapabilities()) {
            checkValidCapability(cap);
        }

        Objects.requireNonNull(mRetryIntervalsMs, "retryIntervalsMs was null");
        validateRetryInterval(mRetryIntervalsMs);

        Preconditions.checkArgument(
                mMaxMtu >= MIN_MTU_V6, "maxMtu must be at least IPv6 min MTU (1280)");
    }

    private static void checkValidCapability(int capability) {
        Preconditions.checkArgument(
                ALLOWED_CAPABILITIES.contains(capability),
                "NetworkCapability " + capability + "out of range");
    }

    private static void validateRetryInterval(@Nullable long[] retryIntervalsMs) {
        Preconditions.checkArgument(
                retryIntervalsMs != null
                        && retryIntervalsMs.length > 0
                        && retryIntervalsMs.length <= MAX_RETRY_INTERVAL_COUNT,
                "retryIntervalsMs was null, empty or exceed max interval count");

        final long repeatingInterval = retryIntervalsMs[retryIntervalsMs.length - 1];
        if (repeatingInterval < MINIMUM_REPEATING_RETRY_INTERVAL_MS) {
            throw new IllegalArgumentException(
                    "Repeating retry interval was too short, must be a minimum of 15 minutes: "
                            + repeatingInterval);
        }
    }

    /**
     * Returns the configured Gateway Connection name.
     *
     * <p>This name is used by the configuring apps to distinguish between
     * VcnGatewayConnectionConfigs configured on a single {@link VcnConfig}. This will be used as
     * the identifier in VcnStatusCallback invocations.
     *
     * @see VcnManager.VcnStatusCallback#onGatewayConnectionError
     */
    @NonNull
    public String getGatewayConnectionName() {
        return mGatewayConnectionName;
    }

    /**
     * Returns tunnel connection parameters.
     *
     * @hide
     */
    @NonNull
    public IkeTunnelConnectionParams getTunnelConnectionParams() {
        return mTunnelConnectionParams;
    }

    /**
     * Returns all exposed capabilities.
     *
     * <p>The returned integer-value capabilities will not contain duplicates, and will be sorted in
     * ascending numerical order.
     *
     * @see Builder#addExposedCapability(int)
     * @see Builder#removeExposedCapability(int)
     */
    @NonNull
    public int[] getExposedCapabilities() {
        // Sorted set guarantees ordering
        return ArrayUtils.convertToIntArray(new ArrayList<>(mExposedCapabilities));
    }

    /**
     * Returns all exposed capabilities.
     *
     * <p>Left to prevent the need to make major changes while changes are actively in flight.
     *
     * @deprecated use getExposedCapabilities() instead
     * @hide
     */
    @Deprecated
    @NonNull
    public Set<Integer> getAllExposedCapabilities() {
        return Collections.unmodifiableSet(mExposedCapabilities);
    }

    /**
     * Retrieves the configured retry intervals.
     *
     * @see Builder#setRetryIntervalsMillis(long[])
     */
    @NonNull
    public long[] getRetryIntervalsMillis() {
        return Arrays.copyOf(mRetryIntervalsMs, mRetryIntervalsMs.length);
    }

    /**
     * Retrieves the maximum MTU allowed for this Gateway Connection.
     *
     * @see Builder#setMaxMtu(int)
     */
    @IntRange(from = MIN_MTU_V6)
    public int getMaxMtu() {
        return mMaxMtu;
    }

    /**
     * Converts this config to a PersistableBundle.
     *
     * @hide
     */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = new PersistableBundle();

        final PersistableBundle tunnelConnectionParamsBundle =
                TunnelConnectionParamsUtils.toPersistableBundle(mTunnelConnectionParams);
        final PersistableBundle exposedCapsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mExposedCapabilities),
                        PersistableBundleUtils.INTEGER_SERIALIZER);

        result.putString(GATEWAY_CONNECTION_NAME_KEY, mGatewayConnectionName);
        result.putPersistableBundle(TUNNEL_CONNECTION_PARAMS_KEY, tunnelConnectionParamsBundle);
        result.putPersistableBundle(EXPOSED_CAPABILITIES_KEY, exposedCapsBundle);
        result.putLongArray(RETRY_INTERVAL_MS_KEY, mRetryIntervalsMs);
        result.putInt(MAX_MTU_KEY, mMaxMtu);

        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mGatewayConnectionName,
                mExposedCapabilities,
                Arrays.hashCode(mRetryIntervalsMs),
                mMaxMtu);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof VcnGatewayConnectionConfig)) {
            return false;
        }

        final VcnGatewayConnectionConfig rhs = (VcnGatewayConnectionConfig) other;
        return mGatewayConnectionName.equals(rhs.mGatewayConnectionName)
                && mExposedCapabilities.equals(rhs.mExposedCapabilities)
                && Arrays.equals(mRetryIntervalsMs, rhs.mRetryIntervalsMs)
                && mMaxMtu == rhs.mMaxMtu;
    }

    /**
     * This class is used to incrementally build {@link VcnGatewayConnectionConfig} objects.
     */
    public static final class Builder {
        @NonNull private final String mGatewayConnectionName;
        @NonNull private final IkeTunnelConnectionParams mTunnelConnectionParams;
        @NonNull private final Set<Integer> mExposedCapabilities = new ArraySet();
        @NonNull private long[] mRetryIntervalsMs = DEFAULT_RETRY_INTERVALS_MS;
        private int mMaxMtu = DEFAULT_MAX_MTU;

        // TODO: (b/175829816) Consider VCN-exposed capabilities that may be transport dependent.
        //       Consider the case where the VCN might only expose MMS on WiFi, but defer to MMS
        //       when on Cell.

        /**
         * Construct a Builder object.
         *
         * @param gatewayConnectionName the String GatewayConnection name for this
         *     VcnGatewayConnectionConfig. Each VcnGatewayConnectionConfig within a {@link
         *     VcnConfig} must be given a unique name. This name is used by the caller to
         *     distinguish between VcnGatewayConnectionConfigs configured on a single {@link
         *     VcnConfig}. This will be used as the identifier in VcnStatusCallback invocations.
         * @param tunnelConnectionParams the IKE tunnel connection configuration
         * @throws IllegalArgumentException if the provided IkeTunnelConnectionParams is not
         *     configured to support MOBIKE
         * @see IkeTunnelConnectionParams
         * @see VcnManager.VcnStatusCallback#onGatewayConnectionError
         */
        public Builder(
                @NonNull String gatewayConnectionName,
                @NonNull IkeTunnelConnectionParams tunnelConnectionParams) {
            Objects.requireNonNull(gatewayConnectionName, "gatewayConnectionName was null");
            Objects.requireNonNull(tunnelConnectionParams, "tunnelConnectionParams was null");
            if (!tunnelConnectionParams.getIkeSessionParams().hasIkeOption(IKE_OPTION_MOBIKE)) {
                throw new IllegalArgumentException(
                        "MOBIKE must be configured for the provided IkeSessionParams");
            }

            mGatewayConnectionName = gatewayConnectionName;
            mTunnelConnectionParams = tunnelConnectionParams;
        }

        /**
         * Add a capability that this VCN Gateway Connection will support.
         *
         * @param exposedCapability the app-facing capability to be exposed by this VCN Gateway
         *     Connection (i.e., the capabilities that this VCN Gateway Connection will support).
         * @return this {@link Builder} instance, for chaining
         * @see VcnGatewayConnectionConfig for a list of capabilities may be exposed by a Gateway
         *     Connection
         */
        @NonNull
        public Builder addExposedCapability(@VcnSupportedCapability int exposedCapability) {
            checkValidCapability(exposedCapability);

            mExposedCapabilities.add(exposedCapability);
            return this;
        }

        /**
         * Remove a capability that this VCN Gateway Connection will support.
         *
         * @param exposedCapability the app-facing capability to not be exposed by this VCN Gateway
         *     Connection (i.e., the capabilities that this VCN Gateway Connection will support)
         * @return this {@link Builder} instance, for chaining
         * @see VcnGatewayConnectionConfig for a list of capabilities may be exposed by a Gateway
         *     Connection
         */
        @NonNull
        @SuppressLint("BuilderSetStyle") // For consistency with NetCaps.Builder add/removeCap
        public Builder removeExposedCapability(@VcnSupportedCapability int exposedCapability) {
            checkValidCapability(exposedCapability);

            mExposedCapabilities.remove(exposedCapability);
            return this;
        }

        /**
         * Set the retry interval between VCN establishment attempts upon successive failures.
         *
         * <p>The last retry interval will be repeated until safe mode is entered, or a connection
         * is successfully established, at which point the retry timers will be reset. For power
         * reasons, the last (repeated) retry interval MUST be at least 15 minutes.
         *
         * <p>Retry intervals MAY be subject to system power saving modes. That is to say that if
         * the system enters a power saving mode, the retry may not occur until the device leaves
         * the specified power saving mode. Intervals are sequential, and intervals will NOT be
         * skipped if system power saving results in delaying retries (even if it exceed multiple
         * retry intervals).
         *
         * <p>Each Gateway Connection will retry according to the retry intervals configured, but if
         * safe mode is enabled, all Gateway Connection(s) will be disabled.
         *
         * @param retryIntervalsMs an array of between 1 and 10 millisecond intervals after which
         *     the VCN will attempt to retry a session initiation. The last (repeating) retry
         *     interval must be at least 15 minutes. Defaults to: {@code [1s, 2s, 5s, 30s, 1m, 5m,
         *     15m]}
         * @return this {@link Builder} instance, for chaining
         * @see VcnManager for additional discussion on fail-safe mode
         */
        @NonNull
        public Builder setRetryIntervalsMillis(@NonNull long[] retryIntervalsMs) {
            validateRetryInterval(retryIntervalsMs);

            mRetryIntervalsMs = retryIntervalsMs;
            return this;
        }

        /**
         * Sets the maximum MTU allowed for this VCN Gateway Connection.
         *
         * <p>This MTU is applied to the VCN Gateway Connection exposed Networks, and represents the
         * MTU of the virtualized network.
         *
         * <p>The system may reduce the MTU below the maximum specified based on signals such as the
         * MTU of the underlying networks (and adjusted for Gateway Connection overhead).
         *
         * @param maxMtu the maximum MTU allowed for this Gateway Connection. Must be greater than
         *     the IPv6 minimum MTU of 1280. Defaults to 1500.
         * @return this {@link Builder} instance, for chaining
         */
        @NonNull
        public Builder setMaxMtu(@IntRange(from = MIN_MTU_V6) int maxMtu) {
            Preconditions.checkArgument(
                    maxMtu >= MIN_MTU_V6, "maxMtu must be at least IPv6 min MTU (1280)");

            mMaxMtu = maxMtu;
            return this;
        }

        /**
         * Builds and validates the VcnGatewayConnectionConfig.
         *
         * @return an immutable VcnGatewayConnectionConfig instance
         */
        @NonNull
        public VcnGatewayConnectionConfig build() {
            return new VcnGatewayConnectionConfig(
                    mGatewayConnectionName,
                    mTunnelConnectionParams,
                    mExposedCapabilities,
                    mRetryIntervalsMs,
                    mMaxMtu);
        }
    }
}
