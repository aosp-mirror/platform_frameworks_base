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
import static android.net.vcn.Flags.FLAG_SAFE_MODE_CONFIG;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_REQUIRED;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.FlaggedApi;
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
import com.android.internal.util.Preconditions;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
    /** @hide */
    public static final int MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET = -1;

    /** @hide */
    public static final int MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS = 120;

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

    /**
     * Perform mobility update to attempt recovery from suspected data stalls.
     *
     * <p>If set, the gateway connection will monitor the data stall detection of the VCN network.
     * When there is a suspected data stall, the gateway connection will attempt recovery by
     * performing a mobility update on the underlying IKE session.
     */
    public static final int VCN_GATEWAY_OPTION_ENABLE_DATA_STALL_RECOVERY_WITH_MOBILITY = 0;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"VCN_GATEWAY_OPTION_"},
            value = {
                VCN_GATEWAY_OPTION_ENABLE_DATA_STALL_RECOVERY_WITH_MOBILITY,
            })
    public @interface VcnGatewayOption {}

    private static final Set<Integer> ALLOWED_GATEWAY_OPTIONS = new ArraySet<>();

    static {
        ALLOWED_GATEWAY_OPTIONS.add(VCN_GATEWAY_OPTION_ENABLE_DATA_STALL_RECOVERY_WITH_MOBILITY);
    }

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

    /** @hide */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static final List<VcnUnderlyingNetworkTemplate> DEFAULT_UNDERLYING_NETWORK_TEMPLATES =
            new ArrayList<>();

    static {
        DEFAULT_UNDERLYING_NETWORK_TEMPLATES.add(
                new VcnCellUnderlyingNetworkTemplate.Builder()
                        .setOpportunistic(MATCH_REQUIRED)
                        .build());

        DEFAULT_UNDERLYING_NETWORK_TEMPLATES.add(
                new VcnWifiUnderlyingNetworkTemplate.Builder()
                        .build());

        DEFAULT_UNDERLYING_NETWORK_TEMPLATES.add(
                new VcnCellUnderlyingNetworkTemplate.Builder()
                        .build());
    }

    private static final String GATEWAY_CONNECTION_NAME_KEY = "mGatewayConnectionName";
    @NonNull private final String mGatewayConnectionName;

    private static final String TUNNEL_CONNECTION_PARAMS_KEY = "mTunnelConnectionParams";
    @NonNull private IkeTunnelConnectionParams mTunnelConnectionParams;

    private static final String EXPOSED_CAPABILITIES_KEY = "mExposedCapabilities";
    @NonNull private final SortedSet<Integer> mExposedCapabilities;

    /** @hide */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static final String UNDERLYING_NETWORK_TEMPLATES_KEY = "mUnderlyingNetworkTemplates";

    @NonNull private final List<VcnUnderlyingNetworkTemplate> mUnderlyingNetworkTemplates;

    private static final String MAX_MTU_KEY = "mMaxMtu";
    private final int mMaxMtu;

    private static final String RETRY_INTERVAL_MS_KEY = "mRetryIntervalsMs";
    @NonNull private final long[] mRetryIntervalsMs;

    private static final String MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS_KEY =
            "mMinUdpPort4500NatTimeoutSeconds";
    private final int mMinUdpPort4500NatTimeoutSeconds;

    private static final String IS_SAFE_MODE_DISABLED_KEY = "mIsSafeModeDisabled";
    private final boolean mIsSafeModeDisabled;

    private static final String GATEWAY_OPTIONS_KEY = "mGatewayOptions";
    @NonNull private final Set<Integer> mGatewayOptions;

    /** Builds a VcnGatewayConnectionConfig with the specified parameters. */
    private VcnGatewayConnectionConfig(
            @NonNull String gatewayConnectionName,
            @NonNull IkeTunnelConnectionParams tunnelConnectionParams,
            @NonNull Set<Integer> exposedCapabilities,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull long[] retryIntervalsMs,
            @IntRange(from = MIN_MTU_V6) int maxMtu,
            @NonNull int minUdpPort4500NatTimeoutSeconds,
            boolean isSafeModeDisabled,
            @NonNull Set<Integer> gatewayOptions) {
        mGatewayConnectionName = gatewayConnectionName;
        mTunnelConnectionParams = tunnelConnectionParams;
        mExposedCapabilities = new TreeSet(exposedCapabilities);
        mRetryIntervalsMs = retryIntervalsMs;
        mMaxMtu = maxMtu;
        mMinUdpPort4500NatTimeoutSeconds = minUdpPort4500NatTimeoutSeconds;
        mGatewayOptions = Collections.unmodifiableSet(new ArraySet(gatewayOptions));
        mIsSafeModeDisabled = isSafeModeDisabled;

        mUnderlyingNetworkTemplates = new ArrayList<>(underlyingNetworkTemplates);
        if (mUnderlyingNetworkTemplates.isEmpty()) {
            mUnderlyingNetworkTemplates.addAll(DEFAULT_UNDERLYING_NETWORK_TEMPLATES);
        }

        validate();
    }

    // Null check MUST be done for all new fields added to VcnGatewayConnectionConfig, to avoid
    // crashes when parsing PersistableBundle built on old platforms.
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

        final PersistableBundle networkTemplatesBundle =
                in.getPersistableBundle(UNDERLYING_NETWORK_TEMPLATES_KEY);

        if (networkTemplatesBundle == null) {
            // UNDERLYING_NETWORK_TEMPLATES_KEY was added in Android T. Thus
            // VcnGatewayConnectionConfig created on old platforms will not have this data and will
            // be assigned with the default value
            mUnderlyingNetworkTemplates = new ArrayList<>(DEFAULT_UNDERLYING_NETWORK_TEMPLATES);
        } else {
            mUnderlyingNetworkTemplates =
                    PersistableBundleUtils.toList(
                            networkTemplatesBundle,
                            VcnUnderlyingNetworkTemplate::fromPersistableBundle);
        }

        final PersistableBundle gatewayOptionsBundle = in.getPersistableBundle(GATEWAY_OPTIONS_KEY);

        if (gatewayOptionsBundle == null) {
            // GATEWAY_OPTIONS_KEY was added in Android U. Thus VcnGatewayConnectionConfig created
            // on old platforms will not have this data and will be assigned with the default value
            mGatewayOptions = Collections.emptySet();
        } else {
            mGatewayOptions =
                    new ArraySet<>(
                            PersistableBundleUtils.toList(
                                    gatewayOptionsBundle,
                                    PersistableBundleUtils.INTEGER_DESERIALIZER));
        }

        mRetryIntervalsMs = in.getLongArray(RETRY_INTERVAL_MS_KEY);
        mMaxMtu = in.getInt(MAX_MTU_KEY);
        mMinUdpPort4500NatTimeoutSeconds =
                in.getInt(
                        MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS_KEY,
                        MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET);
        mIsSafeModeDisabled = in.getBoolean(IS_SAFE_MODE_DISABLED_KEY);

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

        validateNetworkTemplateList(mUnderlyingNetworkTemplates);
        Objects.requireNonNull(mRetryIntervalsMs, "retryIntervalsMs was null");
        validateRetryInterval(mRetryIntervalsMs);

        Preconditions.checkArgument(
                mMaxMtu >= MIN_MTU_V6, "maxMtu must be at least IPv6 min MTU (1280)");

        Preconditions.checkArgument(
                mMinUdpPort4500NatTimeoutSeconds == MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET
                        || mMinUdpPort4500NatTimeoutSeconds
                                >= MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS,
                "minUdpPort4500NatTimeoutSeconds must be at least 120s");

        for (int option : mGatewayOptions) {
            validateGatewayOption(option);
        }
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

    private static void validateNetworkTemplateList(
            List<VcnUnderlyingNetworkTemplate> networkPriorityRules) {
        Objects.requireNonNull(networkPriorityRules, "networkPriorityRules is null");

        Set<VcnUnderlyingNetworkTemplate> existingRules = new ArraySet<>();
        for (VcnUnderlyingNetworkTemplate rule : networkPriorityRules) {
            Objects.requireNonNull(rule, "Found null value VcnUnderlyingNetworkTemplate");
            if (!existingRules.add(rule)) {
                throw new IllegalArgumentException("Found duplicate VcnUnderlyingNetworkTemplate");
            }
        }
    }

    private static void validateGatewayOption(int option) {
        if (!ALLOWED_GATEWAY_OPTIONS.contains(option)) {
            throw new IllegalArgumentException("Invalid vcn gateway option: " + option);
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
        final int[] caps = new int[mExposedCapabilities.size()];

        int i = 0;
        for (int c : mExposedCapabilities) {
            caps[i++] = c;
        }

        return caps;
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
     * Retrieve the VcnUnderlyingNetworkTemplate list, or a default list if it is not configured.
     *
     * @see Builder#setVcnUnderlyingNetworkPriorities(List)
     */
    @NonNull
    public List<VcnUnderlyingNetworkTemplate> getVcnUnderlyingNetworkPriorities() {
        return new ArrayList<>(mUnderlyingNetworkTemplates);
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
     * Retrieves the maximum supported IKEv2/IPsec NATT keepalive timeout.
     *
     * @see Builder#setMinUdpPort4500NatTimeoutSeconds(int)
     */
    public int getMinUdpPort4500NatTimeoutSeconds() {
        return mMinUdpPort4500NatTimeoutSeconds;
    }

    /**
     * Check whether safe mode is enabled
     *
     * @see Builder#setSafeModeEnabled(boolean)
     */
    @FlaggedApi(FLAG_SAFE_MODE_CONFIG)
    public boolean isSafeModeEnabled() {
        return !mIsSafeModeDisabled;
    }

    /**
     * Checks if the given VCN gateway option is enabled.
     *
     * @param option the option to check.
     * @throws IllegalArgumentException if the provided option is invalid.
     * @see Builder#addGatewayOption(int)
     * @see Builder#removeGatewayOption(int)
     */
    public boolean hasGatewayOption(@VcnGatewayOption int option) {
        validateGatewayOption(option);
        return mGatewayOptions.contains(option);
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
        final PersistableBundle networkTemplatesBundle =
                PersistableBundleUtils.fromList(
                        mUnderlyingNetworkTemplates,
                        VcnUnderlyingNetworkTemplate::toPersistableBundle);
        final PersistableBundle gatewayOptionsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mGatewayOptions),
                        PersistableBundleUtils.INTEGER_SERIALIZER);

        result.putString(GATEWAY_CONNECTION_NAME_KEY, mGatewayConnectionName);
        result.putPersistableBundle(TUNNEL_CONNECTION_PARAMS_KEY, tunnelConnectionParamsBundle);
        result.putPersistableBundle(EXPOSED_CAPABILITIES_KEY, exposedCapsBundle);
        result.putPersistableBundle(UNDERLYING_NETWORK_TEMPLATES_KEY, networkTemplatesBundle);
        result.putPersistableBundle(GATEWAY_OPTIONS_KEY, gatewayOptionsBundle);
        result.putLongArray(RETRY_INTERVAL_MS_KEY, mRetryIntervalsMs);
        result.putInt(MAX_MTU_KEY, mMaxMtu);
        result.putInt(MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS_KEY, mMinUdpPort4500NatTimeoutSeconds);
        result.putBoolean(IS_SAFE_MODE_DISABLED_KEY, mIsSafeModeDisabled);

        return result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mGatewayConnectionName,
                mTunnelConnectionParams,
                mExposedCapabilities,
                mUnderlyingNetworkTemplates,
                Arrays.hashCode(mRetryIntervalsMs),
                mMaxMtu,
                mMinUdpPort4500NatTimeoutSeconds,
                mIsSafeModeDisabled,
                mGatewayOptions);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof VcnGatewayConnectionConfig)) {
            return false;
        }

        final VcnGatewayConnectionConfig rhs = (VcnGatewayConnectionConfig) other;
        return mGatewayConnectionName.equals(rhs.mGatewayConnectionName)
                && mTunnelConnectionParams.equals(rhs.mTunnelConnectionParams)
                && mExposedCapabilities.equals(rhs.mExposedCapabilities)
                && mUnderlyingNetworkTemplates.equals(rhs.mUnderlyingNetworkTemplates)
                && Arrays.equals(mRetryIntervalsMs, rhs.mRetryIntervalsMs)
                && mMaxMtu == rhs.mMaxMtu
                && mMinUdpPort4500NatTimeoutSeconds == rhs.mMinUdpPort4500NatTimeoutSeconds
                && mIsSafeModeDisabled == rhs.mIsSafeModeDisabled
                && mGatewayOptions.equals(rhs.mGatewayOptions);
    }

    /**
     * This class is used to incrementally build {@link VcnGatewayConnectionConfig} objects.
     */
    public static final class Builder {
        @NonNull private final String mGatewayConnectionName;
        @NonNull private final IkeTunnelConnectionParams mTunnelConnectionParams;
        @NonNull private final Set<Integer> mExposedCapabilities = new ArraySet();

        @NonNull
        private final List<VcnUnderlyingNetworkTemplate> mUnderlyingNetworkTemplates =
                new ArrayList<>(DEFAULT_UNDERLYING_NETWORK_TEMPLATES);

        @NonNull private long[] mRetryIntervalsMs = DEFAULT_RETRY_INTERVALS_MS;
        private int mMaxMtu = DEFAULT_MAX_MTU;
        private int mMinUdpPort4500NatTimeoutSeconds = MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET;
        private boolean mIsSafeModeDisabled = false;

        @NonNull private final Set<Integer> mGatewayOptions = new ArraySet<>();

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
         * Set the list of templates to match underlying networks against, in high-to-low priority
         * order.
         *
         * <p>To select the VCN underlying network, the VCN connection will go through all the
         * network candidates and return a network matching the highest priority rule.
         *
         * <p>If multiple networks match the same rule, the VCN will prefer an already-selected
         * network as opposed to a new/unselected network. However, if both are new/unselected
         * networks, a network will be chosen arbitrarily amongst the networks matching the highest
         * priority rule.
         *
         * <p>If all networks fail to match the rules provided, a carrier-owned underlying network
         * will still be selected (if available, at random if necessary).
         *
         * @param underlyingNetworkTemplates a list of unique VcnUnderlyingNetworkTemplates that are
         *     ordered from most to least preferred, or an empty list to use the default
         *     prioritization. The default network prioritization order is Opportunistic cellular,
         *     Carrier WiFi and then Macro cellular.
         * @return this {@link Builder} instance, for chaining
         */
        @NonNull
        public Builder setVcnUnderlyingNetworkPriorities(
                @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates) {
            validateNetworkTemplateList(underlyingNetworkTemplates);

            mUnderlyingNetworkTemplates.clear();

            if (underlyingNetworkTemplates.isEmpty()) {
                mUnderlyingNetworkTemplates.addAll(DEFAULT_UNDERLYING_NETWORK_TEMPLATES);
            } else {
                mUnderlyingNetworkTemplates.addAll(underlyingNetworkTemplates);
            }

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
         * Sets the maximum supported IKEv2/IPsec NATT keepalive timeout.
         *
         * <p>This is used as a power-optimization hint for other IKEv2/IPsec use cases (e.g. VPNs,
         * or IWLAN) to reduce the necessary keepalive frequency, thus conserving power and data.
         *
         * @param minUdpPort4500NatTimeoutSeconds the maximum keepalive timeout supported by the VCN
         *     Gateway Connection, generally the minimum duration a NAT mapping is cached on the VCN
         *     Gateway.
         * @return this {@link Builder} instance, for chaining
         */
        @NonNull
        public Builder setMinUdpPort4500NatTimeoutSeconds(
                @IntRange(from = MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS)
                        int minUdpPort4500NatTimeoutSeconds) {
            Preconditions.checkArgument(
                    minUdpPort4500NatTimeoutSeconds >= MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS,
                    "Timeout must be at least 120s");

            mMinUdpPort4500NatTimeoutSeconds = minUdpPort4500NatTimeoutSeconds;
            return this;
        }

        /**
         * Enables the specified VCN gateway option.
         *
         * @param option the option to be enabled
         * @return this {@link Builder} instance, for chaining
         * @throws IllegalArgumentException if the provided option is invalid
         */
        @NonNull
        public Builder addGatewayOption(@VcnGatewayOption int option) {
            validateGatewayOption(option);
            mGatewayOptions.add(option);
            return this;
        }

        /**
         * Resets (disables) the specified VCN gateway option.
         *
         * @param option the option to be disabled
         * @return this {@link Builder} instance, for chaining
         * @throws IllegalArgumentException if the provided option is invalid
         */
        @NonNull
        public Builder removeGatewayOption(@VcnGatewayOption int option) {
            validateGatewayOption(option);
            mGatewayOptions.remove(option);
            return this;
        }

        /**
         * Enable/disable safe mode
         *
         * <p>If a VCN fails to provide connectivity within a system-provided timeout, it will enter
         * safe mode. In safe mode, the VCN Network will be torn down and the system will restore
         * connectivity by allowing underlying cellular or WiFi networks to be used as default. At
         * the same time, VCN will continue to retry until it succeeds.
         *
         * <p>When safe mode is disabled and VCN connection fails to provide connectivity, end users
         * might not have connectivity, and may not have access to carrier-owned underlying
         * networks.
         *
         * @param enabled whether safe mode should be enabled. Defaults to {@code true}
         */
        @FlaggedApi(FLAG_SAFE_MODE_CONFIG)
        @NonNull
        public Builder setSafeModeEnabled(boolean enabled) {
            mIsSafeModeDisabled = !enabled;
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
                    mUnderlyingNetworkTemplates,
                    mRetryIntervalsMs,
                    mMaxMtu,
                    mMinUdpPort4500NatTimeoutSeconds,
                    mIsSafeModeDisabled,
                    mGatewayOptions);
        }
    }
}
