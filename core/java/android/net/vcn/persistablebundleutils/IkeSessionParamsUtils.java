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
import android.net.eap.EapSessionConfig;
import android.net.ipsec.ike.IkeSaProposal;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeSessionParams.ConfigRequestIpv4PcscfServer;
import android.net.ipsec.ike.IkeSessionParams.ConfigRequestIpv6PcscfServer;
import android.net.ipsec.ike.IkeSessionParams.IkeAuthConfig;
import android.net.ipsec.ike.IkeSessionParams.IkeAuthDigitalSignLocalConfig;
import android.net.ipsec.ike.IkeSessionParams.IkeAuthDigitalSignRemoteConfig;
import android.net.ipsec.ike.IkeSessionParams.IkeAuthEapConfig;
import android.net.ipsec.ike.IkeSessionParams.IkeAuthPskConfig;
import android.net.ipsec.ike.IkeSessionParams.IkeConfigRequest;
import android.os.PersistableBundle;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.net.InetAddress;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Abstract utility class to convert IkeSessionParams to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class IkeSessionParamsUtils {
    private static final String SERVER_HOST_NAME_KEY = "SERVER_HOST_NAME_KEY";
    private static final String SA_PROPOSALS_KEY = "SA_PROPOSALS_KEY";
    private static final String LOCAL_ID_KEY = "LOCAL_ID_KEY";
    private static final String REMOTE_ID_KEY = "REMOTE_ID_KEY";
    private static final String LOCAL_AUTH_KEY = "LOCAL_AUTH_KEY";
    private static final String REMOTE_AUTH_KEY = "REMOTE_AUTH_KEY";
    private static final String CONFIG_REQUESTS_KEY = "CONFIG_REQUESTS_KEY";
    private static final String RETRANS_TIMEOUTS_KEY = "RETRANS_TIMEOUTS_KEY";
    private static final String HARD_LIFETIME_SEC_KEY = "HARD_LIFETIME_SEC_KEY";
    private static final String SOFT_LIFETIME_SEC_KEY = "SOFT_LIFETIME_SEC_KEY";
    private static final String DPD_DELAY_SEC_KEY = "DPD_DELAY_SEC_KEY";
    private static final String NATT_KEEPALIVE_DELAY_SEC_KEY = "NATT_KEEPALIVE_DELAY_SEC_KEY";
    private static final String IKE_OPTIONS_KEY = "IKE_OPTIONS_KEY";

    private static final Set<Integer> IKE_OPTIONS = new ArraySet<>();

    static {
        IKE_OPTIONS.add(IkeSessionParams.IKE_OPTION_ACCEPT_ANY_REMOTE_ID);
        IKE_OPTIONS.add(IkeSessionParams.IKE_OPTION_EAP_ONLY_AUTH);
        IKE_OPTIONS.add(IkeSessionParams.IKE_OPTION_MOBIKE);
        IKE_OPTIONS.add(IkeSessionParams.IKE_OPTION_FORCE_PORT_4500);
        IKE_OPTIONS.add(IkeSessionParams.IKE_OPTION_INITIAL_CONTACT);
    }

    /** Serializes an IkeSessionParams to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(@NonNull IkeSessionParams params) {
        if (params.getNetwork() != null || params.getIke3gppExtension() != null) {
            throw new IllegalStateException(
                    "Cannot convert a IkeSessionParams with a caller configured network or with"
                            + " 3GPP extension enabled");
        }

        final PersistableBundle result = new PersistableBundle();

        result.putString(SERVER_HOST_NAME_KEY, params.getServerHostname());

        final PersistableBundle saProposalBundle =
                PersistableBundleUtils.fromList(
                        params.getSaProposals(), IkeSaProposalUtils::toPersistableBundle);
        result.putPersistableBundle(SA_PROPOSALS_KEY, saProposalBundle);

        result.putPersistableBundle(
                LOCAL_ID_KEY,
                IkeIdentificationUtils.toPersistableBundle(params.getLocalIdentification()));
        result.putPersistableBundle(
                REMOTE_ID_KEY,
                IkeIdentificationUtils.toPersistableBundle(params.getRemoteIdentification()));

        result.putPersistableBundle(
                LOCAL_AUTH_KEY, AuthConfigUtils.toPersistableBundle(params.getLocalAuthConfig()));
        result.putPersistableBundle(
                REMOTE_AUTH_KEY, AuthConfigUtils.toPersistableBundle(params.getRemoteAuthConfig()));

        final List<ConfigRequest> reqList = new ArrayList<>();
        for (IkeConfigRequest req : params.getConfigurationRequests()) {
            reqList.add(new ConfigRequest(req));
        }
        final PersistableBundle configReqListBundle =
                PersistableBundleUtils.fromList(reqList, ConfigRequest::toPersistableBundle);
        result.putPersistableBundle(CONFIG_REQUESTS_KEY, configReqListBundle);

        result.putIntArray(RETRANS_TIMEOUTS_KEY, params.getRetransmissionTimeoutsMillis());
        result.putInt(HARD_LIFETIME_SEC_KEY, params.getHardLifetimeSeconds());
        result.putInt(SOFT_LIFETIME_SEC_KEY, params.getSoftLifetimeSeconds());
        result.putInt(DPD_DELAY_SEC_KEY, params.getDpdDelaySeconds());
        result.putInt(NATT_KEEPALIVE_DELAY_SEC_KEY, params.getNattKeepAliveDelaySeconds());

        // TODO: b/185941731 Make sure IkeSessionParamsUtils is automatically updated when a new
        // IKE_OPTION is defined in IKE module and added in the IkeSessionParams
        final List<Integer> enabledIkeOptions = new ArrayList<>();
        for (int option : IKE_OPTIONS) {
            if (params.hasIkeOption(option)) {
                enabledIkeOptions.add(option);
            }
        }

        final int[] optionArray = enabledIkeOptions.stream().mapToInt(i -> i).toArray();
        result.putIntArray(IKE_OPTIONS_KEY, optionArray);

        return result;
    }

    /** Constructs an IkeSessionParams by deserializing a PersistableBundle. */
    @NonNull
    public static IkeSessionParams fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle is null");

        final IkeSessionParams.Builder builder = new IkeSessionParams.Builder();

        builder.setServerHostname(in.getString(SERVER_HOST_NAME_KEY));

        PersistableBundle proposalBundle = in.getPersistableBundle(SA_PROPOSALS_KEY);
        Objects.requireNonNull(in, "SA Proposals was null");
        List<IkeSaProposal> saProposals =
                PersistableBundleUtils.toList(
                        proposalBundle, IkeSaProposalUtils::fromPersistableBundle);
        for (IkeSaProposal proposal : saProposals) {
            builder.addSaProposal(proposal);
        }

        builder.setLocalIdentification(
                IkeIdentificationUtils.fromPersistableBundle(
                        in.getPersistableBundle(LOCAL_ID_KEY)));
        builder.setRemoteIdentification(
                IkeIdentificationUtils.fromPersistableBundle(
                        in.getPersistableBundle(REMOTE_ID_KEY)));

        AuthConfigUtils.setBuilderByReadingPersistableBundle(
                in.getPersistableBundle(LOCAL_AUTH_KEY),
                in.getPersistableBundle(REMOTE_AUTH_KEY),
                builder);

        builder.setRetransmissionTimeoutsMillis(in.getIntArray(RETRANS_TIMEOUTS_KEY));
        builder.setLifetimeSeconds(
                in.getInt(HARD_LIFETIME_SEC_KEY), in.getInt(SOFT_LIFETIME_SEC_KEY));
        builder.setDpdDelaySeconds(in.getInt(DPD_DELAY_SEC_KEY));
        builder.setNattKeepAliveDelaySeconds(in.getInt(NATT_KEEPALIVE_DELAY_SEC_KEY));

        final PersistableBundle configReqListBundle = in.getPersistableBundle(CONFIG_REQUESTS_KEY);
        Objects.requireNonNull(configReqListBundle, "Config request list was null");
        final List<ConfigRequest> reqList =
                PersistableBundleUtils.toList(configReqListBundle, ConfigRequest::new);
        for (ConfigRequest req : reqList) {
            switch (req.type) {
                case ConfigRequest.IPV4_P_CSCF_ADDRESS:
                    if (req.address == null) {
                        builder.addPcscfServerRequest(AF_INET);
                    } else {
                        builder.addPcscfServerRequest(req.address);
                    }
                    break;
                case ConfigRequest.IPV6_P_CSCF_ADDRESS:
                    if (req.address == null) {
                        builder.addPcscfServerRequest(AF_INET6);
                    } else {
                        builder.addPcscfServerRequest(req.address);
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unrecognized config request type: " + req.type);
            }
        }

        // Clear IKE Options that are by default enabled
        for (int option : IKE_OPTIONS) {
            builder.removeIkeOption(option);
        }

        final int[] optionArray = in.getIntArray(IKE_OPTIONS_KEY);
        for (int option : optionArray) {
            builder.addIkeOption(option);
        }

        return builder.build();
    }

    private static final class AuthConfigUtils {
        private static final int IKE_AUTH_METHOD_PSK = 1;
        private static final int IKE_AUTH_METHOD_PUB_KEY_SIGNATURE = 2;
        private static final int IKE_AUTH_METHOD_EAP = 3;

        private static final String AUTH_METHOD_KEY = "AUTH_METHOD_KEY";

        @NonNull
        public static PersistableBundle toPersistableBundle(@NonNull IkeAuthConfig authConfig) {
            if (authConfig instanceof IkeAuthPskConfig) {
                IkeAuthPskConfig config = (IkeAuthPskConfig) authConfig;
                return IkeAuthPskConfigUtils.toPersistableBundle(
                        config, createPersistableBundle(IKE_AUTH_METHOD_PSK));
            } else if (authConfig instanceof IkeAuthDigitalSignLocalConfig) {
                IkeAuthDigitalSignLocalConfig config = (IkeAuthDigitalSignLocalConfig) authConfig;
                return IkeAuthDigitalSignConfigUtils.toPersistableBundle(
                        config, createPersistableBundle(IKE_AUTH_METHOD_PUB_KEY_SIGNATURE));
            } else if (authConfig instanceof IkeAuthDigitalSignRemoteConfig) {
                IkeAuthDigitalSignRemoteConfig config = (IkeAuthDigitalSignRemoteConfig) authConfig;
                return IkeAuthDigitalSignConfigUtils.toPersistableBundle(
                        config, createPersistableBundle(IKE_AUTH_METHOD_PUB_KEY_SIGNATURE));
            } else if (authConfig instanceof IkeAuthEapConfig) {
                IkeAuthEapConfig config = (IkeAuthEapConfig) authConfig;
                return IkeAuthEapConfigUtils.toPersistableBundle(
                        config, createPersistableBundle(IKE_AUTH_METHOD_EAP));
            } else {
                throw new IllegalStateException("Invalid IkeAuthConfig subclass");
            }
        }

        private static PersistableBundle createPersistableBundle(int type) {
            final PersistableBundle result = new PersistableBundle();
            result.putInt(AUTH_METHOD_KEY, type);
            return result;
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle localAuthBundle,
                @NonNull PersistableBundle remoteAuthBundle,
                @NonNull IkeSessionParams.Builder builder) {
            Objects.requireNonNull(localAuthBundle, "localAuthBundle was null");
            Objects.requireNonNull(remoteAuthBundle, "remoteAuthBundle was null");

            final int localMethodType = localAuthBundle.getInt(AUTH_METHOD_KEY);
            final int remoteMethodType = remoteAuthBundle.getInt(AUTH_METHOD_KEY);
            switch (localMethodType) {
                case IKE_AUTH_METHOD_PSK:
                    if (remoteMethodType != IKE_AUTH_METHOD_PSK) {
                        throw new IllegalArgumentException(
                                "Expect remote auth method to be PSK based, but was "
                                        + remoteMethodType);
                    }
                    IkeAuthPskConfigUtils.setBuilderByReadingPersistableBundle(
                            localAuthBundle, remoteAuthBundle, builder);
                    return;
                case IKE_AUTH_METHOD_PUB_KEY_SIGNATURE:
                    if (remoteMethodType != IKE_AUTH_METHOD_PUB_KEY_SIGNATURE) {
                        throw new IllegalArgumentException(
                                "Expect remote auth method to be digital signature based, but was "
                                        + remoteMethodType);
                    }
                    IkeAuthDigitalSignConfigUtils.setBuilderByReadingPersistableBundle(
                            localAuthBundle, remoteAuthBundle, builder);
                    return;
                case IKE_AUTH_METHOD_EAP:
                    if (remoteMethodType != IKE_AUTH_METHOD_PUB_KEY_SIGNATURE) {
                        throw new IllegalArgumentException(
                                "When using EAP for local authentication, expect remote auth"
                                        + " method to be digital signature based, but was "
                                        + remoteMethodType);
                    }
                    IkeAuthEapConfigUtils.setBuilderByReadingPersistableBundle(
                            localAuthBundle, remoteAuthBundle, builder);
                    return;
                default:
                    throw new IllegalArgumentException(
                            "Invalid EAP method type " + localMethodType);
            }
        }
    }

    private static final class IkeAuthPskConfigUtils {
        private static final String PSK_KEY = "PSK_KEY";

        @NonNull
        public static PersistableBundle toPersistableBundle(
                @NonNull IkeAuthPskConfig config, @NonNull PersistableBundle result) {
            result.putPersistableBundle(
                    PSK_KEY, PersistableBundleUtils.fromByteArray(config.getPsk()));
            return result;
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle localAuthBundle,
                @NonNull PersistableBundle remoteAuthBundle,
                @NonNull IkeSessionParams.Builder builder) {
            Objects.requireNonNull(localAuthBundle, "localAuthBundle was null");
            Objects.requireNonNull(remoteAuthBundle, "remoteAuthBundle was null");

            final PersistableBundle localPskBundle = localAuthBundle.getPersistableBundle(PSK_KEY);
            final PersistableBundle remotePskBundle =
                    remoteAuthBundle.getPersistableBundle(PSK_KEY);
            Objects.requireNonNull(localAuthBundle, "Local PSK was null");
            Objects.requireNonNull(remoteAuthBundle, "Remote PSK was null");

            final byte[] localPsk = PersistableBundleUtils.toByteArray(localPskBundle);
            final byte[] remotePsk = PersistableBundleUtils.toByteArray(remotePskBundle);
            if (!Arrays.equals(localPsk, remotePsk)) {
                throw new IllegalArgumentException("Local PSK and remote PSK are different");
            }
            builder.setAuthPsk(localPsk);
        }
    }

    private static class IkeAuthDigitalSignConfigUtils {
        private static final String END_CERT_KEY = "END_CERT_KEY";
        private static final String INTERMEDIATE_CERTS_KEY = "INTERMEDIATE_CERTS_KEY";
        private static final String PRIVATE_KEY_KEY = "PRIVATE_KEY_KEY";
        private static final String TRUST_CERT_KEY = "TRUST_CERT_KEY";

        @NonNull
        public static PersistableBundle toPersistableBundle(
                @NonNull IkeAuthDigitalSignLocalConfig config, @NonNull PersistableBundle result) {
            try {
                result.putPersistableBundle(
                        END_CERT_KEY,
                        PersistableBundleUtils.fromByteArray(
                                config.getClientEndCertificate().getEncoded()));

                final List<X509Certificate> certList = config.getIntermediateCertificates();
                final List<byte[]> encodedCertList = new ArrayList<>(certList.size());
                for (X509Certificate cert : certList) {
                    encodedCertList.add(cert.getEncoded());
                }

                final PersistableBundle certsBundle =
                        PersistableBundleUtils.fromList(
                                encodedCertList, PersistableBundleUtils::fromByteArray);
                result.putPersistableBundle(INTERMEDIATE_CERTS_KEY, certsBundle);
            } catch (CertificateEncodingException e) {
                throw new IllegalArgumentException("Fail to encode certificate");
            }

            // TODO: b/170670506 Consider putting PrivateKey in Android KeyStore
            result.putPersistableBundle(
                    PRIVATE_KEY_KEY,
                    PersistableBundleUtils.fromByteArray(config.getPrivateKey().getEncoded()));
            return result;
        }

        @NonNull
        public static PersistableBundle toPersistableBundle(
                @NonNull IkeAuthDigitalSignRemoteConfig config, @NonNull PersistableBundle result) {
            try {
                X509Certificate caCert = config.getRemoteCaCert();
                if (caCert != null) {
                    result.putPersistableBundle(
                            TRUST_CERT_KEY,
                            PersistableBundleUtils.fromByteArray(caCert.getEncoded()));
                }
            } catch (CertificateEncodingException e) {
                throw new IllegalArgumentException("Fail to encode the certificate");
            }

            return result;
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle localAuthBundle,
                @NonNull PersistableBundle remoteAuthBundle,
                @NonNull IkeSessionParams.Builder builder) {
            Objects.requireNonNull(localAuthBundle, "localAuthBundle was null");
            Objects.requireNonNull(remoteAuthBundle, "remoteAuthBundle was null");

            // Deserialize localAuth
            final PersistableBundle endCertBundle =
                    localAuthBundle.getPersistableBundle(END_CERT_KEY);
            Objects.requireNonNull(endCertBundle, "End cert was null");
            final byte[] encodedCert = PersistableBundleUtils.toByteArray(endCertBundle);
            final X509Certificate endCert = CertUtils.certificateFromByteArray(encodedCert);

            final PersistableBundle certsBundle =
                    localAuthBundle.getPersistableBundle(INTERMEDIATE_CERTS_KEY);
            Objects.requireNonNull(certsBundle, "Intermediate certs was null");
            final List<byte[]> encodedCertList =
                    PersistableBundleUtils.toList(certsBundle, PersistableBundleUtils::toByteArray);
            final List<X509Certificate> certList = new ArrayList<>(encodedCertList.size());
            for (byte[] encoded : encodedCertList) {
                certList.add(CertUtils.certificateFromByteArray(encoded));
            }

            final PersistableBundle privateKeyBundle =
                    localAuthBundle.getPersistableBundle(PRIVATE_KEY_KEY);
            Objects.requireNonNull(privateKeyBundle, "PrivateKey bundle was null");
            final PrivateKey privateKey =
                    CertUtils.privateKeyFromByteArray(
                            PersistableBundleUtils.toByteArray(privateKeyBundle));

            // Deserialize remoteAuth
            final PersistableBundle trustCertBundle =
                    remoteAuthBundle.getPersistableBundle(TRUST_CERT_KEY);

            X509Certificate caCert = null;
            if (trustCertBundle != null) {
                final byte[] encodedCaCert = PersistableBundleUtils.toByteArray(trustCertBundle);
                caCert = CertUtils.certificateFromByteArray(encodedCaCert);
            }

            builder.setAuthDigitalSignature(caCert, endCert, certList, privateKey);
        }
    }

    private static final class IkeAuthEapConfigUtils {
        private static final String EAP_CONFIG_KEY = "EAP_CONFIG_KEY";

        @NonNull
        public static PersistableBundle toPersistableBundle(
                @NonNull IkeAuthEapConfig config, @NonNull PersistableBundle result) {
            result.putPersistableBundle(
                    EAP_CONFIG_KEY,
                    EapSessionConfigUtils.toPersistableBundle(config.getEapConfig()));
            return result;
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle localAuthBundle,
                @NonNull PersistableBundle remoteAuthBundle,
                @NonNull IkeSessionParams.Builder builder) {
            // Deserialize localAuth
            final PersistableBundle eapBundle =
                    localAuthBundle.getPersistableBundle(EAP_CONFIG_KEY);
            Objects.requireNonNull(eapBundle, "EAP Config was null");
            final EapSessionConfig eapConfig =
                    EapSessionConfigUtils.fromPersistableBundle(eapBundle);

            // Deserialize remoteAuth
            final PersistableBundle trustCertBundle =
                    remoteAuthBundle.getPersistableBundle(
                            IkeAuthDigitalSignConfigUtils.TRUST_CERT_KEY);

            X509Certificate serverCaCert = null;
            if (trustCertBundle != null) {
                final byte[] encodedCaCert = PersistableBundleUtils.toByteArray(trustCertBundle);
                serverCaCert = CertUtils.certificateFromByteArray(encodedCaCert);
            }
            builder.setAuthEap(serverCaCert, eapConfig);
        }
    }

    private static final class ConfigRequest {
        private static final int IPV4_P_CSCF_ADDRESS = 1;
        private static final int IPV6_P_CSCF_ADDRESS = 2;

        private static final String TYPE_KEY = "type";
        private static final String ADDRESS_KEY = "address";

        public final int type;

        // Null when it is an empty request
        @Nullable public final InetAddress address;

        ConfigRequest(IkeConfigRequest config) {
            if (config instanceof ConfigRequestIpv4PcscfServer) {
                type = IPV4_P_CSCF_ADDRESS;
                address = ((ConfigRequestIpv4PcscfServer) config).getAddress();
            } else if (config instanceof ConfigRequestIpv6PcscfServer) {
                type = IPV6_P_CSCF_ADDRESS;
                address = ((ConfigRequestIpv6PcscfServer) config).getAddress();
            } else {
                throw new IllegalStateException("Unknown TunnelModeChildConfigRequest");
            }
        }

        ConfigRequest(PersistableBundle in) {
            Objects.requireNonNull(in, "PersistableBundle was null");

            type = in.getInt(TYPE_KEY);

            String addressStr = in.getString(ADDRESS_KEY);
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
            if (address != null) {
                result.putString(ADDRESS_KEY, address.getHostAddress());
            }

            return result;
        }
    }
}
