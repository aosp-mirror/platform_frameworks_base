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

package com.android.server.connectivity;

import static android.net.ConnectivityManager.NetworkCallback;
import static android.net.ipsec.ike.SaProposal.DH_GROUP_2048_BIT_MODP;
import static android.net.ipsec.ike.SaProposal.DH_GROUP_3072_BIT_MODP;
import static android.net.ipsec.ike.SaProposal.DH_GROUP_4096_BIT_MODP;
import static android.net.ipsec.ike.SaProposal.DH_GROUP_CURVE_25519;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CBC;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CTR;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_8;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_CHACHA20_POLY1305;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_AES_CMAC_96;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_AES_XCBC_96;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_AES_128;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_AES_192;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_AES_256;
import static android.net.ipsec.ike.SaProposal.KEY_LEN_UNUSED;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_AES128_CMAC;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_SHA2_384;
import static android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512;

import android.annotation.NonNull;
import android.content.Context;
import android.net.Ikev2VpnProfile;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.IpSecAlgorithm;
import android.net.IpSecTransform;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.eap.EapSessionConfig;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionConfiguration;
import android.net.ipsec.ike.ChildSessionParams;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeIdentification;
import android.net.ipsec.ike.IkeIpv4AddrIdentification;
import android.net.ipsec.ike.IkeIpv6AddrIdentification;
import android.net.ipsec.ike.IkeKeyIdIdentification;
import android.net.ipsec.ike.IkeRfc822AddrIdentification;
import android.net.ipsec.ike.IkeSaProposal;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionConfiguration;
import android.net.ipsec.ike.IkeSessionConnectionInfo;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.net.ipsec.ike.exceptions.IkeException;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.system.OsConstants;
import android.util.Log;

import com.android.internal.net.VpnProfile;
import com.android.internal.util.HexDump;
import com.android.net.module.util.IpRange;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Utility class to build and convert IKEv2/IPsec parameters.
 *
 * @hide
 */
public class VpnIkev2Utils {
    private static final String TAG = VpnIkev2Utils.class.getSimpleName();

    static IkeSessionParams buildIkeSessionParams(
            @NonNull Context context, @NonNull Ikev2VpnProfile profile, @NonNull Network network) {
        final IkeIdentification localId = parseIkeIdentification(profile.getUserIdentity());
        final IkeIdentification remoteId = parseIkeIdentification(profile.getServerAddr());

        final IkeSessionParams.Builder ikeOptionsBuilder =
                new IkeSessionParams.Builder(context)
                        .setServerHostname(profile.getServerAddr())
                        .setNetwork(network)
                        .addIkeOption(IkeSessionParams.IKE_OPTION_MOBIKE)
                        .setLocalIdentification(localId)
                        .setRemoteIdentification(remoteId);
        setIkeAuth(profile, ikeOptionsBuilder);

        for (final IkeSaProposal ikeProposal : getIkeSaProposals()) {
            ikeOptionsBuilder.addSaProposal(ikeProposal);
        }

        return ikeOptionsBuilder.build();
    }

    static ChildSessionParams buildChildSessionParams(List<String> allowedAlgorithms) {
        final TunnelModeChildSessionParams.Builder childOptionsBuilder =
                new TunnelModeChildSessionParams.Builder();

        for (final ChildSaProposal childProposal : getChildSaProposals(allowedAlgorithms)) {
            childOptionsBuilder.addSaProposal(childProposal);
        }

        childOptionsBuilder.addInternalAddressRequest(OsConstants.AF_INET);
        childOptionsBuilder.addInternalAddressRequest(OsConstants.AF_INET6);
        childOptionsBuilder.addInternalDnsServerRequest(OsConstants.AF_INET);
        childOptionsBuilder.addInternalDnsServerRequest(OsConstants.AF_INET6);

        return childOptionsBuilder.build();
    }

    private static void setIkeAuth(
            @NonNull Ikev2VpnProfile profile, @NonNull IkeSessionParams.Builder builder) {
        switch (profile.getType()) {
            case VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS:
                final EapSessionConfig eapConfig =
                        new EapSessionConfig.Builder()
                                .setEapMsChapV2Config(profile.getUsername(), profile.getPassword())
                                .build();
                builder.setAuthEap(profile.getServerRootCaCert(), eapConfig);
                break;
            case VpnProfile.TYPE_IKEV2_IPSEC_PSK:
                builder.setAuthPsk(profile.getPresharedKey());
                break;
            case VpnProfile.TYPE_IKEV2_IPSEC_RSA:
                builder.setAuthDigitalSignature(
                        profile.getServerRootCaCert(),
                        profile.getUserCert(),
                        profile.getRsaPrivateKey());
                break;
            default:
                throw new IllegalArgumentException("Unknown auth method set");
        }
    }

    private static List<IkeSaProposal> getIkeSaProposals() {
        // TODO: Add ability to filter this when IKEv2 API is made Public API
        final List<IkeSaProposal> proposals = new ArrayList<>();

        final IkeSaProposal.Builder normalModeBuilder = new IkeSaProposal.Builder();

        // Add normal mode encryption algorithms
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CTR, KEY_LEN_AES_256);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_256);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CTR, KEY_LEN_AES_192);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_192);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CTR, KEY_LEN_AES_128);
        normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, KEY_LEN_AES_128);

        // Authentication/Integrity Algorithms
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_512_256);
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_384_192);
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128);
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_AES_XCBC_96);
        normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_AES_CMAC_96);

        // Add AEAD options
        final IkeSaProposal.Builder aeadBuilder = new IkeSaProposal.Builder();
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_CHACHA20_POLY1305, KEY_LEN_UNUSED);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_256);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_256);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_256);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_192);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_192);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_192);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_128);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_128);
        aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_128);

        // Add dh, prf for both builders
        for (final IkeSaProposal.Builder builder : Arrays.asList(normalModeBuilder, aeadBuilder)) {
            builder.addDhGroup(DH_GROUP_4096_BIT_MODP);

            // Curve25519 has the same security strength as MODP 3072 and cost less bytes
            builder.addDhGroup(DH_GROUP_CURVE_25519);

            builder.addDhGroup(DH_GROUP_3072_BIT_MODP);
            builder.addDhGroup(DH_GROUP_2048_BIT_MODP);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_512);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_384);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_SHA2_256);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_AES128_XCBC);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_AES128_CMAC);
            builder.addPseudorandomFunction(PSEUDORANDOM_FUNCTION_HMAC_SHA1);
        }

        proposals.add(normalModeBuilder.build());
        proposals.add(aeadBuilder.build());
        return proposals;
    }

    /** Builds a child SA proposal based on the allowed IPsec algorithms */
    private static List<ChildSaProposal> getChildSaProposals(List<String> allowedAlgorithms) {
        final List<ChildSaProposal> proposals = new ArrayList<>();

        final List<Integer> aesKeyLenOptions =
                Arrays.asList(KEY_LEN_AES_256, KEY_LEN_AES_192, KEY_LEN_AES_128);

        // Add non-AEAD options
        if (Ikev2VpnProfile.hasNormalModeAlgorithms(allowedAlgorithms)) {
            final ChildSaProposal.Builder normalModeBuilder = new ChildSaProposal.Builder();

            // Encryption Algorithms:
            // AES-CBC and AES_CTR are currently the only supported encryption algorithms.
            for (int len : aesKeyLenOptions) {
                if (allowedAlgorithms.contains(IpSecAlgorithm.CRYPT_AES_CTR)) {
                    normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CTR, len);
                }
                if (allowedAlgorithms.contains(IpSecAlgorithm.CRYPT_AES_CBC)) {
                    normalModeBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_CBC, len);
                }
            }

            // Authentication/Integrity Algorithms:
            // Guaranteed by Ikev2VpnProfile constructor to contain at least one of these.
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_HMAC_SHA512)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_512_256);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_HMAC_SHA384)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_384_192);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_HMAC_SHA256)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_AES_XCBC)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_AES_XCBC_96);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_AES_CMAC)) {
                normalModeBuilder.addIntegrityAlgorithm(INTEGRITY_ALGORITHM_AES_CMAC_96);
            }

            ChildSaProposal proposal = normalModeBuilder.build();
            if (proposal.getIntegrityAlgorithms().isEmpty()) {
                // Should be impossible; Verified in Ikev2VpnProfile.
                Log.wtf(TAG, "Missing integrity algorithm when buildling Child SA proposal");
            } else {
                proposals.add(normalModeBuilder.build());
            }
        }

        // Add AEAD options
        if (Ikev2VpnProfile.hasAeadAlgorithms(allowedAlgorithms)) {
            final ChildSaProposal.Builder aeadBuilder = new ChildSaProposal.Builder();

            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_CRYPT_CHACHA20_POLY1305)) {
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_CHACHA20_POLY1305, KEY_LEN_UNUSED);
            }
            if (allowedAlgorithms.contains(IpSecAlgorithm.AUTH_CRYPT_AES_GCM)) {
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_256);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_256);
                aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_256);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_192);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_192);
                aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_192);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_16, KEY_LEN_AES_128);
                aeadBuilder.addEncryptionAlgorithm(
                        ENCRYPTION_ALGORITHM_AES_GCM_12, KEY_LEN_AES_128);
                aeadBuilder.addEncryptionAlgorithm(ENCRYPTION_ALGORITHM_AES_GCM_8, KEY_LEN_AES_128);
            }

            proposals.add(aeadBuilder.build());
        }

        return proposals;
    }

    static class IkeSessionCallbackImpl implements IkeSessionCallback {
        private final String mTag;
        private final Vpn.IkeV2VpnRunnerCallback mCallback;
        private final int mToken;

        IkeSessionCallbackImpl(String tag, Vpn.IkeV2VpnRunnerCallback callback, int token) {
            mTag = tag;
            mCallback = callback;
            mToken = token;
        }

        @Override
        public void onOpened(@NonNull IkeSessionConfiguration ikeSessionConfig) {
            Log.d(mTag, "IkeOpened for token " + mToken);
            mCallback.onIkeOpened(mToken, ikeSessionConfig);
        }

        @Override
        public void onClosed() {
            Log.d(mTag, "IkeClosed for token " + mToken);
            mCallback.onSessionLost(mToken, null); // Server requested session closure. Retry?
        }

        @Override
        public void onClosedExceptionally(@NonNull IkeException exception) {
            Log.d(mTag, "IkeClosedExceptionally for token " + mToken, exception);
            mCallback.onSessionLost(mToken, exception);
        }

        @Override
        public void onError(@NonNull IkeProtocolException exception) {
            Log.d(mTag, "IkeError for token " + mToken, exception);
            // Non-fatal, log and continue.
        }

        @Override
        public void onIkeSessionConnectionInfoChanged(
                @NonNull IkeSessionConnectionInfo connectionInfo) {
            Log.d(mTag, "onIkeSessionConnectionInfoChanged for token " + mToken);
            mCallback.onIkeConnectionInfoChanged(mToken, connectionInfo);
        }
    }

    static class ChildSessionCallbackImpl implements ChildSessionCallback {
        private final String mTag;
        private final Vpn.IkeV2VpnRunnerCallback mCallback;
        private final int mToken;

        ChildSessionCallbackImpl(String tag, Vpn.IkeV2VpnRunnerCallback callback, int token) {
            mTag = tag;
            mCallback = callback;
            mToken = token;
        }

        @Override
        public void onOpened(@NonNull ChildSessionConfiguration childConfig) {
            Log.d(mTag, "ChildOpened for token " + mToken);
            mCallback.onChildOpened(mToken, childConfig);
        }

        @Override
        public void onClosed() {
            Log.d(mTag, "ChildClosed for token " + mToken);
            mCallback.onSessionLost(mToken, null);
        }

        @Override
        public void onClosedExceptionally(@NonNull IkeException exception) {
            Log.d(mTag, "ChildClosedExceptionally for token " + mToken, exception);
            mCallback.onSessionLost(mToken, exception);
        }

        @Override
        public void onIpSecTransformCreated(@NonNull IpSecTransform transform, int direction) {
            Log.d(mTag, "ChildTransformCreated; Direction: " + direction + "; token " + mToken);
            mCallback.onChildTransformCreated(mToken, transform, direction);
        }

        @Override
        public void onIpSecTransformDeleted(@NonNull IpSecTransform transform, int direction) {
            // Nothing to be done; no references to the IpSecTransform are held by the
            // Ikev2VpnRunner (or this callback class), and this transform will be closed by the
            // IKE library.
            Log.d(mTag, "ChildTransformDeleted; Direction: " + direction + "; for token " + mToken);
        }

        @Override
        public void onIpSecTransformsMigrated(
                @NonNull IpSecTransform inIpSecTransform,
                @NonNull IpSecTransform outIpSecTransform) {
            Log.d(mTag, "ChildTransformsMigrated; token " + mToken);
            mCallback.onChildMigrated(mToken, inIpSecTransform, outIpSecTransform);
        }
    }

    static class Ikev2VpnNetworkCallback extends NetworkCallback {
        private final String mTag;
        private final Vpn.IkeV2VpnRunnerCallback mCallback;
        private final ExecutorService mExecutor;

        Ikev2VpnNetworkCallback(String tag, Vpn.IkeV2VpnRunnerCallback callback,
                ExecutorService executor) {
            mTag = tag;
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            Log.d(mTag, "onAvailable called for network: " + network);
            mExecutor.execute(() -> mCallback.onDefaultNetworkChanged(network));
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network,
                @NonNull NetworkCapabilities networkCapabilities) {
            Log.d(mTag, "NC changed for net " + network + " : " + networkCapabilities);
            mExecutor.execute(
                    () -> mCallback.onDefaultNetworkCapabilitiesChanged(networkCapabilities));
        }

        @Override
        public void onLinkPropertiesChanged(@NonNull Network network,
                @NonNull LinkProperties linkProperties) {
            Log.d(mTag, "LP changed for net " + network + " : " + linkProperties);
            mExecutor.execute(
                    () -> mCallback.onDefaultNetworkLinkPropertiesChanged(linkProperties));
        }

        @Override
        public void onLost(@NonNull Network network) {
            Log.d(mTag, "onLost called for network: " + network);
            mExecutor.execute(() -> mCallback.onDefaultNetworkLost(network));
        }
    }

    /**
     * Identity parsing logic using similar logic to open source implementations of IKEv2
     *
     * <p>This method does NOT support using type-prefixes (eg 'fqdn:' or 'keyid'), or ASN.1 encoded
     * identities.
     */
    private static IkeIdentification parseIkeIdentification(@NonNull String identityStr) {
        // TODO: Add identity formatting to public API javadocs.
        if (identityStr.contains("@")) {
            if (identityStr.startsWith("@#")) {
                // KEY_ID
                final String hexStr = identityStr.substring(2);
                return new IkeKeyIdIdentification(HexDump.hexStringToByteArray(hexStr));
            } else if (identityStr.startsWith("@@")) {
                // RFC822 (USER_FQDN)
                return new IkeRfc822AddrIdentification(identityStr.substring(2));
            } else if (identityStr.startsWith("@")) {
                // FQDN
                return new IkeFqdnIdentification(identityStr.substring(1));
            } else {
                // RFC822 (USER_FQDN)
                return new IkeRfc822AddrIdentification(identityStr);
            }
        } else if (InetAddresses.isNumericAddress(identityStr)) {
            final InetAddress addr = InetAddresses.parseNumericAddress(identityStr);
            if (addr instanceof Inet4Address) {
                // IPv4
                return new IkeIpv4AddrIdentification((Inet4Address) addr);
            } else if (addr instanceof Inet6Address) {
                // IPv6
                return new IkeIpv6AddrIdentification((Inet6Address) addr);
            } else {
                throw new IllegalArgumentException("IP version not supported");
            }
        } else {
            if (identityStr.contains(":")) {
                // KEY_ID
                return new IkeKeyIdIdentification(identityStr.getBytes());
            } else {
                // FQDN
                return new IkeFqdnIdentification(identityStr);
            }
        }
    }

    static Collection<RouteInfo> getRoutesFromTrafficSelectors(
            List<IkeTrafficSelector> trafficSelectors) {
        final HashSet<RouteInfo> routes = new HashSet<>();

        for (final IkeTrafficSelector selector : trafficSelectors) {
            for (final IpPrefix prefix :
                    new IpRange(selector.startingAddress, selector.endingAddress).asIpPrefixes()) {
                routes.add(new RouteInfo(prefix, null /*gateway*/, null /*iface*/,
                        RouteInfo.RTN_UNICAST));
            }
        }

        return routes;
    }
}
