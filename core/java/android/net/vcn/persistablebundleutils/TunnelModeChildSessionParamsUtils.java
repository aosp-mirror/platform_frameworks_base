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

package android.net.vcn.persistablebundleutils;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.InetAddresses;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.net.ipsec.ike.TunnelModeChildSessionParams.ConfigRequestIpv4Address;
import android.net.ipsec.ike.TunnelModeChildSessionParams.ConfigRequestIpv4DhcpServer;
import android.net.ipsec.ike.TunnelModeChildSessionParams.ConfigRequestIpv4DnsServer;
import android.net.ipsec.ike.TunnelModeChildSessionParams.ConfigRequestIpv4Netmask;
import android.net.ipsec.ike.TunnelModeChildSessionParams.ConfigRequestIpv6Address;
import android.net.ipsec.ike.TunnelModeChildSessionParams.ConfigRequestIpv6DnsServer;
import android.net.ipsec.ike.TunnelModeChildSessionParams.TunnelModeChildConfigRequest;
import android.os.PersistableBundle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provides utility methods to convert TunnelModeChildSessionParams to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class TunnelModeChildSessionParamsUtils {
    private static final String TAG = TunnelModeChildSessionParamsUtils.class.getSimpleName();

    private static final String INBOUND_TS_KEY = "INBOUND_TS_KEY";
    private static final String OUTBOUND_TS_KEY = "OUTBOUND_TS_KEY";
    private static final String SA_PROPOSALS_KEY = "SA_PROPOSALS_KEY";
    private static final String HARD_LIFETIME_SEC_KEY = "HARD_LIFETIME_SEC_KEY";
    private static final String SOFT_LIFETIME_SEC_KEY = "SOFT_LIFETIME_SEC_KEY";
    private static final String CONFIG_REQUESTS_KEY = "CONFIG_REQUESTS_KEY";

    private static class ConfigRequest {
        private static final int TYPE_IPV4_ADDRESS = 1;
        private static final int TYPE_IPV6_ADDRESS = 2;
        private static final int TYPE_IPV4_DNS = 3;
        private static final int TYPE_IPV6_DNS = 4;
        private static final int TYPE_IPV4_DHCP = 5;
        private static final int TYPE_IPV4_NETMASK = 6;

        private static final String TYPE_KEY = "type";
        private static final String VALUE_KEY = "address";
        private static final String IP6_PREFIX_LEN = "ip6PrefixLen";

        private static final int PREFIX_LEN_UNUSED = -1;

        public final int type;
        public final int ip6PrefixLen;

        // Null when it is an empty request
        @Nullable public final InetAddress address;

        ConfigRequest(TunnelModeChildConfigRequest config) {
            int prefixLen = PREFIX_LEN_UNUSED;

            if (config instanceof ConfigRequestIpv4Address) {
                type = TYPE_IPV4_ADDRESS;
                address = ((ConfigRequestIpv4Address) config).getAddress();
            } else if (config instanceof ConfigRequestIpv6Address) {
                type = TYPE_IPV6_ADDRESS;
                address = ((ConfigRequestIpv6Address) config).getAddress();
                if (address != null) {
                    prefixLen = ((ConfigRequestIpv6Address) config).getPrefixLength();
                }
            } else if (config instanceof ConfigRequestIpv4DnsServer) {
                type = TYPE_IPV4_DNS;
                address = null;
            } else if (config instanceof ConfigRequestIpv6DnsServer) {
                type = TYPE_IPV6_DNS;
                address = null;
            } else if (config instanceof ConfigRequestIpv4DhcpServer) {
                type = TYPE_IPV4_DHCP;
                address = null;
            } else if (config instanceof ConfigRequestIpv4Netmask) {
                type = TYPE_IPV4_NETMASK;
                address = null;
            } else {
                throw new IllegalStateException("Unknown TunnelModeChildConfigRequest");
            }

            ip6PrefixLen = prefixLen;
        }

        ConfigRequest(PersistableBundle in) {
            Objects.requireNonNull(in, "PersistableBundle was null");

            type = in.getInt(TYPE_KEY);
            ip6PrefixLen = in.getInt(IP6_PREFIX_LEN);

            String addressStr = in.getString(VALUE_KEY);
            if (addressStr == null) {
                address = null;
            } else {
                address = InetAddresses.parseNumericAddress(addressStr);
            }
        }

        @NonNull
        public PersistableBundle toPersistableBundle() {
            final PersistableBundle result = new PersistableBundle();

            result.putInt(TYPE_KEY, type);
            result.putInt(IP6_PREFIX_LEN, ip6PrefixLen);

            if (address != null) {
                result.putString(VALUE_KEY, address.getHostAddress());
            }

            return result;
        }
    }

    /** Serializes a TunnelModeChildSessionParams to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(
            @NonNull TunnelModeChildSessionParams params) {
        final PersistableBundle result = new PersistableBundle();

        final PersistableBundle saProposalBundle =
                PersistableBundleUtils.fromList(
                        params.getSaProposals(), ChildSaProposalUtils::toPersistableBundle);
        result.putPersistableBundle(SA_PROPOSALS_KEY, saProposalBundle);

        final PersistableBundle inTsBundle =
                PersistableBundleUtils.fromList(
                        params.getInboundTrafficSelectors(),
                        IkeTrafficSelectorUtils::toPersistableBundle);
        result.putPersistableBundle(INBOUND_TS_KEY, inTsBundle);

        final PersistableBundle outTsBundle =
                PersistableBundleUtils.fromList(
                        params.getOutboundTrafficSelectors(),
                        IkeTrafficSelectorUtils::toPersistableBundle);
        result.putPersistableBundle(OUTBOUND_TS_KEY, outTsBundle);

        result.putInt(HARD_LIFETIME_SEC_KEY, params.getHardLifetimeSeconds());
        result.putInt(SOFT_LIFETIME_SEC_KEY, params.getSoftLifetimeSeconds());

        final List<ConfigRequest> reqList = new ArrayList<>();
        for (TunnelModeChildConfigRequest req : params.getConfigurationRequests()) {
            reqList.add(new ConfigRequest(req));
        }
        final PersistableBundle configReqListBundle =
                PersistableBundleUtils.fromList(reqList, ConfigRequest::toPersistableBundle);
        result.putPersistableBundle(CONFIG_REQUESTS_KEY, configReqListBundle);

        return result;
    }

    private static List<IkeTrafficSelector> getTsFromPersistableBundle(
            PersistableBundle in, String key) {
        PersistableBundle tsBundle = in.getPersistableBundle(key);
        Objects.requireNonNull(tsBundle, "Value for key " + key + " was null");
        return PersistableBundleUtils.toList(
                tsBundle, IkeTrafficSelectorUtils::fromPersistableBundle);
    }

    /** Constructs a TunnelModeChildSessionParams by deserializing a PersistableBundle. */
    @NonNull
    public static TunnelModeChildSessionParams fromPersistableBundle(
            @NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");

        final TunnelModeChildSessionParams.Builder builder =
                new TunnelModeChildSessionParams.Builder();

        final PersistableBundle proposalBundle = in.getPersistableBundle(SA_PROPOSALS_KEY);
        Objects.requireNonNull(proposalBundle, "SA proposal was null");
        final List<ChildSaProposal> proposals =
                PersistableBundleUtils.toList(
                        proposalBundle, ChildSaProposalUtils::fromPersistableBundle);
        for (ChildSaProposal p : proposals) {
            builder.addSaProposal(p);
        }

        for (IkeTrafficSelector ts : getTsFromPersistableBundle(in, INBOUND_TS_KEY)) {
            builder.addInboundTrafficSelectors(ts);
        }

        for (IkeTrafficSelector ts : getTsFromPersistableBundle(in, OUTBOUND_TS_KEY)) {
            builder.addOutboundTrafficSelectors(ts);
        }

        builder.setLifetimeSeconds(
                in.getInt(HARD_LIFETIME_SEC_KEY), in.getInt(SOFT_LIFETIME_SEC_KEY));
        final PersistableBundle configReqListBundle = in.getPersistableBundle(CONFIG_REQUESTS_KEY);
        Objects.requireNonNull(configReqListBundle, "Config request list was null");
        final List<ConfigRequest> reqList =
                PersistableBundleUtils.toList(configReqListBundle, ConfigRequest::new);

        boolean hasIpv4AddressReq = false;
        boolean hasIpv4NetmaskReq = false;
        for (ConfigRequest req : reqList) {
            switch (req.type) {
                case ConfigRequest.TYPE_IPV4_ADDRESS:
                    hasIpv4AddressReq = true;
                    if (req.address == null) {
                        builder.addInternalAddressRequest(AF_INET);
                    } else {
                        builder.addInternalAddressRequest((Inet4Address) req.address);
                    }
                    break;
                case ConfigRequest.TYPE_IPV6_ADDRESS:
                    if (req.address == null) {
                        builder.addInternalAddressRequest(AF_INET6);
                    } else {
                        builder.addInternalAddressRequest(
                                (Inet6Address) req.address, req.ip6PrefixLen);
                    }
                    break;
                case ConfigRequest.TYPE_IPV4_NETMASK:
                    // Do not need to set netmask because it will be automatically set by the
                    // builder when an IPv4 internal address request is set.
                    hasIpv4NetmaskReq = true;
                    break;
                case ConfigRequest.TYPE_IPV4_DNS:
                    if (req.address != null) {
                        Log.w(TAG, "Requesting a specific IPv4 DNS server is unsupported");
                    }
                    builder.addInternalDnsServerRequest(AF_INET);
                    break;
                case ConfigRequest.TYPE_IPV6_DNS:
                    if (req.address != null) {
                        Log.w(TAG, "Requesting a specific IPv6 DNS server is unsupported");
                    }
                    builder.addInternalDnsServerRequest(AF_INET6);
                    break;
                case ConfigRequest.TYPE_IPV4_DHCP:
                    if (req.address != null) {
                        Log.w(TAG, "Requesting a specific IPv4 DHCP server is unsupported");
                    }
                    builder.addInternalDhcpServerRequest(AF_INET);
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unrecognized config request type: " + req.type);
            }
        }

        if (hasIpv4AddressReq != hasIpv4NetmaskReq) {
            Log.w(
                    TAG,
                    String.format(
                            "Expect IPv4 address request and IPv4 netmask request either both"
                                + " exist or both absent, but found hasIpv4AddressReq exists? %b,"
                                + " hasIpv4AddressReq exists? %b, ",
                            hasIpv4AddressReq, hasIpv4NetmaskReq));
        }

        return builder.build();
    }
}
