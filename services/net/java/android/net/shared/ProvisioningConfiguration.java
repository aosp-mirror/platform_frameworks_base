/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.shared;

import android.annotation.Nullable;
import android.net.INetd;
import android.net.Network;
import android.net.ProvisioningConfigurationParcelable;
import android.net.StaticIpConfiguration;
import android.net.apf.ApfCapabilities;
import android.net.ip.IIpClient;

import java.util.Objects;
import java.util.StringJoiner;

/**
 * This class encapsulates parameters to be passed to
 * IpClient#startProvisioning(). A defensive copy is made by IpClient
 * and the values specified herein are in force until IpClient#stop()
 * is called.
 *
 * Example use:
 *
 *     final ProvisioningConfiguration config =
 *             new ProvisioningConfiguration.Builder()
 *                     .withPreDhcpAction()
 *                     .withProvisioningTimeoutMs(36 * 1000)
 *                     .build();
 *     mIpClient.startProvisioning(config.toStableParcelable());
 *     ...
 *     mIpClient.stop();
 *
 * The specified provisioning configuration will only be active until
 * IIpClient#stop() is called. Future calls to IIpClient#startProvisioning()
 * must specify the configuration again.
 * @hide
 */
public class ProvisioningConfiguration {
    // TODO: Delete this default timeout once those callers that care are
    // fixed to pass in their preferred timeout.
    //
    // We pick 36 seconds so we can send DHCP requests at
    //
    //     t=0, t=2, t=6, t=14, t=30
    //
    // allowing for 10% jitter.
    private static final int DEFAULT_TIMEOUT_MS = 36 * 1000;

    /**
     * Builder to create a {@link ProvisioningConfiguration}.
     */
    public static class Builder {
        protected ProvisioningConfiguration mConfig = new ProvisioningConfiguration();

        /**
         * Specify that the configuration should not enable IPv4. It is enabled by default.
         */
        public Builder withoutIPv4() {
            mConfig.mEnableIPv4 = false;
            return this;
        }

        /**
         * Specify that the configuration should not enable IPv6. It is enabled by default.
         */
        public Builder withoutIPv6() {
            mConfig.mEnableIPv6 = false;
            return this;
        }

        /**
         * Specify that the configuration should not use a MultinetworkPolicyTracker. It is used
         * by default.
         */
        public Builder withoutMultinetworkPolicyTracker() {
            mConfig.mUsingMultinetworkPolicyTracker = false;
            return this;
        }

        /**
         * Specify that the configuration should not use a IpReachabilityMonitor. It is used by
         * default.
         */
        public Builder withoutIpReachabilityMonitor() {
            mConfig.mUsingIpReachabilityMonitor = false;
            return this;
        }

        /**
         * Identical to {@link #withPreDhcpAction(int)}, using a default timeout.
         * @see #withPreDhcpAction(int)
         */
        public Builder withPreDhcpAction() {
            mConfig.mRequestedPreDhcpActionMs = DEFAULT_TIMEOUT_MS;
            return this;
        }

        /**
         * Specify that {@link IpClientCallbacks#onPreDhcpAction()} should be called. Clients must
         * call {@link IIpClient#completedPreDhcpAction()} when the callback called. This behavior
         * is disabled by default.
         * @param dhcpActionTimeoutMs Timeout for clients to call completedPreDhcpAction().
         */
        public Builder withPreDhcpAction(int dhcpActionTimeoutMs) {
            mConfig.mRequestedPreDhcpActionMs = dhcpActionTimeoutMs;
            return this;
        }

        /**
         * Specify the initial provisioning configuration.
         */
        public Builder withInitialConfiguration(InitialConfiguration initialConfig) {
            mConfig.mInitialConfig = initialConfig;
            return this;
        }

        /**
         * Specify a static configuration for provisioning.
         */
        public Builder withStaticConfiguration(StaticIpConfiguration staticConfig) {
            mConfig.mStaticIpConfig = staticConfig;
            return this;
        }

        /**
         * Specify ApfCapabilities.
         */
        public Builder withApfCapabilities(ApfCapabilities apfCapabilities) {
            mConfig.mApfCapabilities = apfCapabilities;
            return this;
        }

        /**
         * Specify the timeout to use for provisioning.
         */
        public Builder withProvisioningTimeoutMs(int timeoutMs) {
            mConfig.mProvisioningTimeoutMs = timeoutMs;
            return this;
        }

        /**
         * Specify that IPv6 address generation should use a random MAC address.
         */
        public Builder withRandomMacAddress() {
            mConfig.mIPv6AddrGenMode = INetd.IPV6_ADDR_GEN_MODE_EUI64;
            return this;
        }

        /**
         * Specify that IPv6 address generation should use a stable MAC address.
         */
        public Builder withStableMacAddress() {
            mConfig.mIPv6AddrGenMode = INetd.IPV6_ADDR_GEN_MODE_STABLE_PRIVACY;
            return this;
        }

        /**
         * Specify the network to use for provisioning.
         */
        public Builder withNetwork(Network network) {
            mConfig.mNetwork = network;
            return this;
        }

        /**
         * Specify the display name that the IpClient should use.
         */
        public Builder withDisplayName(String displayName) {
            mConfig.mDisplayName = displayName;
            return this;
        }

        /**
         * Build the configuration using previously specified parameters.
         */
        public ProvisioningConfiguration build() {
            return new ProvisioningConfiguration(mConfig);
        }
    }

    public boolean mEnableIPv4 = true;
    public boolean mEnableIPv6 = true;
    public boolean mUsingMultinetworkPolicyTracker = true;
    public boolean mUsingIpReachabilityMonitor = true;
    public int mRequestedPreDhcpActionMs;
    public InitialConfiguration mInitialConfig;
    public StaticIpConfiguration mStaticIpConfig;
    public ApfCapabilities mApfCapabilities;
    public int mProvisioningTimeoutMs = DEFAULT_TIMEOUT_MS;
    public int mIPv6AddrGenMode = INetd.IPV6_ADDR_GEN_MODE_STABLE_PRIVACY;
    public Network mNetwork = null;
    public String mDisplayName = null;

    public ProvisioningConfiguration() {} // used by Builder

    public ProvisioningConfiguration(ProvisioningConfiguration other) {
        mEnableIPv4 = other.mEnableIPv4;
        mEnableIPv6 = other.mEnableIPv6;
        mUsingMultinetworkPolicyTracker = other.mUsingMultinetworkPolicyTracker;
        mUsingIpReachabilityMonitor = other.mUsingIpReachabilityMonitor;
        mRequestedPreDhcpActionMs = other.mRequestedPreDhcpActionMs;
        mInitialConfig = InitialConfiguration.copy(other.mInitialConfig);
        mStaticIpConfig = other.mStaticIpConfig == null
                ? null
                : new StaticIpConfiguration(other.mStaticIpConfig);
        mApfCapabilities = other.mApfCapabilities;
        mProvisioningTimeoutMs = other.mProvisioningTimeoutMs;
        mIPv6AddrGenMode = other.mIPv6AddrGenMode;
        mNetwork = other.mNetwork;
        mDisplayName = other.mDisplayName;
    }

    /**
     * Create a ProvisioningConfigurationParcelable from this ProvisioningConfiguration.
     */
    public ProvisioningConfigurationParcelable toStableParcelable() {
        final ProvisioningConfigurationParcelable p = new ProvisioningConfigurationParcelable();
        p.enableIPv4 = mEnableIPv4;
        p.enableIPv6 = mEnableIPv6;
        p.usingMultinetworkPolicyTracker = mUsingMultinetworkPolicyTracker;
        p.usingIpReachabilityMonitor = mUsingIpReachabilityMonitor;
        p.requestedPreDhcpActionMs = mRequestedPreDhcpActionMs;
        p.initialConfig = mInitialConfig == null ? null : mInitialConfig.toStableParcelable();
        p.staticIpConfig = IpConfigurationParcelableUtil.toStableParcelable(mStaticIpConfig);
        p.apfCapabilities = IpConfigurationParcelableUtil.toStableParcelable(mApfCapabilities);
        p.provisioningTimeoutMs = mProvisioningTimeoutMs;
        p.ipv6AddrGenMode = mIPv6AddrGenMode;
        p.network = mNetwork;
        p.displayName = mDisplayName;
        return p;
    }

    /**
     * Create a ProvisioningConfiguration from a ProvisioningConfigurationParcelable.
     */
    public static ProvisioningConfiguration fromStableParcelable(
            @Nullable ProvisioningConfigurationParcelable p) {
        if (p == null) return null;
        final ProvisioningConfiguration config = new ProvisioningConfiguration();
        config.mEnableIPv4 = p.enableIPv4;
        config.mEnableIPv6 = p.enableIPv6;
        config.mUsingMultinetworkPolicyTracker = p.usingMultinetworkPolicyTracker;
        config.mUsingIpReachabilityMonitor = p.usingIpReachabilityMonitor;
        config.mRequestedPreDhcpActionMs = p.requestedPreDhcpActionMs;
        config.mInitialConfig = InitialConfiguration.fromStableParcelable(p.initialConfig);
        config.mStaticIpConfig = IpConfigurationParcelableUtil.fromStableParcelable(
                p.staticIpConfig);
        config.mApfCapabilities = IpConfigurationParcelableUtil.fromStableParcelable(
                p.apfCapabilities);
        config.mProvisioningTimeoutMs = p.provisioningTimeoutMs;
        config.mIPv6AddrGenMode = p.ipv6AddrGenMode;
        config.mNetwork = p.network;
        config.mDisplayName = p.displayName;
        return config;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", getClass().getSimpleName() + "{", "}")
                .add("mEnableIPv4: " + mEnableIPv4)
                .add("mEnableIPv6: " + mEnableIPv6)
                .add("mUsingMultinetworkPolicyTracker: " + mUsingMultinetworkPolicyTracker)
                .add("mUsingIpReachabilityMonitor: " + mUsingIpReachabilityMonitor)
                .add("mRequestedPreDhcpActionMs: " + mRequestedPreDhcpActionMs)
                .add("mInitialConfig: " + mInitialConfig)
                .add("mStaticIpConfig: " + mStaticIpConfig)
                .add("mApfCapabilities: " + mApfCapabilities)
                .add("mProvisioningTimeoutMs: " + mProvisioningTimeoutMs)
                .add("mIPv6AddrGenMode: " + mIPv6AddrGenMode)
                .add("mNetwork: " + mNetwork)
                .add("mDisplayName: " + mDisplayName)
                .toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ProvisioningConfiguration)) return false;
        final ProvisioningConfiguration other = (ProvisioningConfiguration) obj;
        return mEnableIPv4 == other.mEnableIPv4
                && mEnableIPv6 == other.mEnableIPv6
                && mUsingMultinetworkPolicyTracker == other.mUsingMultinetworkPolicyTracker
                && mUsingIpReachabilityMonitor == other.mUsingIpReachabilityMonitor
                && mRequestedPreDhcpActionMs == other.mRequestedPreDhcpActionMs
                && Objects.equals(mInitialConfig, other.mInitialConfig)
                && Objects.equals(mStaticIpConfig, other.mStaticIpConfig)
                && Objects.equals(mApfCapabilities, other.mApfCapabilities)
                && mProvisioningTimeoutMs == other.mProvisioningTimeoutMs
                && mIPv6AddrGenMode == other.mIPv6AddrGenMode
                && Objects.equals(mNetwork, other.mNetwork)
                && Objects.equals(mDisplayName, other.mDisplayName);
    }

    public boolean isValid() {
        return (mInitialConfig == null) || mInitialConfig.isValid();
    }
}
