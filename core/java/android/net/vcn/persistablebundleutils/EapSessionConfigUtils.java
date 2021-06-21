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


import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.net.eap.EapSessionConfig;
import android.net.eap.EapSessionConfig.EapAkaConfig;
import android.net.eap.EapSessionConfig.EapAkaPrimeConfig;
import android.net.eap.EapSessionConfig.EapMethodConfig;
import android.net.eap.EapSessionConfig.EapMsChapV2Config;
import android.net.eap.EapSessionConfig.EapSimConfig;
import android.net.eap.EapSessionConfig.EapTtlsConfig;
import android.net.eap.EapSessionConfig.EapUiccConfig;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Objects;

/**
 * Provides utility methods to convert EapSessionConfig to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class EapSessionConfigUtils {
    private static final String EAP_ID_KEY = "EAP_ID_KEY";
    private static final String EAP_SIM_CONFIG_KEY = "EAP_SIM_CONFIG_KEY";
    private static final String EAP_TTLS_CONFIG_KEY = "EAP_TTLS_CONFIG_KEY";
    private static final String EAP_AKA_CONFIG_KEY = "EAP_AKA_CONFIG_KEY";
    private static final String EAP_MSCHAP_V2_CONFIG_KEY = "EAP_MSCHAP_V2_CONFIG_KEY";
    private static final String EAP_AKA_PRIME_CONFIG_KEY = "EAP_AKA_PRIME_CONFIG_KEY";

    /** Serializes an EapSessionConfig to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(@NonNull EapSessionConfig config) {
        final PersistableBundle result = new PersistableBundle();

        result.putPersistableBundle(
                EAP_ID_KEY, PersistableBundleUtils.fromByteArray(config.getEapIdentity()));

        if (config.getEapSimConfig() != null) {
            result.putPersistableBundle(
                    EAP_SIM_CONFIG_KEY,
                    EapSimConfigUtils.toPersistableBundle(config.getEapSimConfig()));
        }

        if (config.getEapTtlsConfig() != null) {
            result.putPersistableBundle(
                    EAP_TTLS_CONFIG_KEY,
                    EapTtlsConfigUtils.toPersistableBundle(config.getEapTtlsConfig()));
        }

        if (config.getEapAkaConfig() != null) {
            result.putPersistableBundle(
                    EAP_AKA_CONFIG_KEY,
                    EapAkaConfigUtils.toPersistableBundle(config.getEapAkaConfig()));
        }

        if (config.getEapMsChapV2Config() != null) {
            result.putPersistableBundle(
                    EAP_MSCHAP_V2_CONFIG_KEY,
                    EapMsChapV2ConfigUtils.toPersistableBundle(config.getEapMsChapV2Config()));
        }

        if (config.getEapAkaPrimeConfig() != null) {
            result.putPersistableBundle(
                    EAP_AKA_PRIME_CONFIG_KEY,
                    EapAkaPrimeConfigUtils.toPersistableBundle(config.getEapAkaPrimeConfig()));
        }

        return result;
    }

    /** Constructs an EapSessionConfig by deserializing a PersistableBundle. */
    @NonNull
    public static EapSessionConfig fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");

        final EapSessionConfig.Builder builder = new EapSessionConfig.Builder();

        final PersistableBundle eapIdBundle = in.getPersistableBundle(EAP_ID_KEY);
        Objects.requireNonNull(eapIdBundle, "EAP ID was null");
        builder.setEapIdentity(PersistableBundleUtils.toByteArray(eapIdBundle));

        final PersistableBundle simBundle = in.getPersistableBundle(EAP_SIM_CONFIG_KEY);
        if (simBundle != null) {
            EapSimConfigUtils.setBuilderByReadingPersistableBundle(simBundle, builder);
        }

        final PersistableBundle ttlsBundle = in.getPersistableBundle(EAP_TTLS_CONFIG_KEY);
        if (ttlsBundle != null) {
            EapTtlsConfigUtils.setBuilderByReadingPersistableBundle(ttlsBundle, builder);
        }

        final PersistableBundle akaBundle = in.getPersistableBundle(EAP_AKA_CONFIG_KEY);
        if (akaBundle != null) {
            EapAkaConfigUtils.setBuilderByReadingPersistableBundle(akaBundle, builder);
        }

        final PersistableBundle msChapV2Bundle = in.getPersistableBundle(EAP_MSCHAP_V2_CONFIG_KEY);
        if (msChapV2Bundle != null) {
            EapMsChapV2ConfigUtils.setBuilderByReadingPersistableBundle(msChapV2Bundle, builder);
        }

        final PersistableBundle akaPrimeBundle = in.getPersistableBundle(EAP_AKA_PRIME_CONFIG_KEY);
        if (akaPrimeBundle != null) {
            EapAkaPrimeConfigUtils.setBuilderByReadingPersistableBundle(akaPrimeBundle, builder);
        }

        return builder.build();
    }

    private static class EapMethodConfigUtils {
        private static final String METHOD_TYPE = "METHOD_TYPE";

        /** Serializes an EapMethodConfig to a PersistableBundle. */
        @NonNull
        public static PersistableBundle toPersistableBundle(@NonNull EapMethodConfig config) {
            final PersistableBundle result = new PersistableBundle();
            result.putInt(METHOD_TYPE, config.getMethodType());
            return result;
        }
    }

    private static class EapUiccConfigUtils extends EapMethodConfigUtils {
        static final String SUB_ID_KEY = "SUB_ID_KEY";
        static final String APP_TYPE_KEY = "APP_TYPE_KEY";

        @NonNull
        protected static PersistableBundle toPersistableBundle(@NonNull EapUiccConfig config) {
            final PersistableBundle result = EapMethodConfigUtils.toPersistableBundle(config);
            result.putInt(SUB_ID_KEY, config.getSubId());
            result.putInt(APP_TYPE_KEY, config.getAppType());

            return result;
        }
    }

    private static final class EapSimConfigUtils extends EapUiccConfigUtils {
        @NonNull
        public static PersistableBundle toPersistableBundle(EapSimConfig config) {
            return EapUiccConfigUtils.toPersistableBundle(config);
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle in, @NonNull EapSessionConfig.Builder builder) {
            Objects.requireNonNull(in, "PersistableBundle was null");
            builder.setEapSimConfig(in.getInt(SUB_ID_KEY), in.getInt(APP_TYPE_KEY));
        }
    }

    private static class EapAkaConfigUtils extends EapUiccConfigUtils {
        @NonNull
        public static PersistableBundle toPersistableBundle(@NonNull EapAkaConfig config) {
            return EapUiccConfigUtils.toPersistableBundle(config);
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle in, @NonNull EapSessionConfig.Builder builder) {
            Objects.requireNonNull(in, "PersistableBundle was null");
            builder.setEapAkaConfig(in.getInt(SUB_ID_KEY), in.getInt(APP_TYPE_KEY));
        }
    }

    private static final class EapAkaPrimeConfigUtils extends EapAkaConfigUtils {
        private static final String NETWORK_NAME_KEY = "NETWORK_NAME_KEY";
        private static final String ALL_MISMATCHED_NETWORK_KEY = "ALL_MISMATCHED_NETWORK_KEY";

        @NonNull
        public static PersistableBundle toPersistableBundle(@NonNull EapAkaPrimeConfig config) {
            final PersistableBundle result = EapUiccConfigUtils.toPersistableBundle(config);
            result.putString(NETWORK_NAME_KEY, config.getNetworkName());
            result.putBoolean(ALL_MISMATCHED_NETWORK_KEY, config.allowsMismatchedNetworkNames());

            return result;
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle in, @NonNull EapSessionConfig.Builder builder) {
            Objects.requireNonNull(in, "PersistableBundle was null");
            builder.setEapAkaPrimeConfig(
                    in.getInt(SUB_ID_KEY),
                    in.getInt(APP_TYPE_KEY),
                    in.getString(NETWORK_NAME_KEY),
                    in.getBoolean(ALL_MISMATCHED_NETWORK_KEY));
        }
    }

    private static final class EapMsChapV2ConfigUtils extends EapMethodConfigUtils {
        private static final String USERNAME_KEY = "USERNAME_KEY";
        private static final String PASSWORD_KEY = "PASSWORD_KEY";

        @NonNull
        public static PersistableBundle toPersistableBundle(@NonNull EapMsChapV2Config config) {
            final PersistableBundle result = EapMethodConfigUtils.toPersistableBundle(config);
            result.putString(USERNAME_KEY, config.getUsername());
            result.putString(PASSWORD_KEY, config.getPassword());

            return result;
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle in, @NonNull EapSessionConfig.Builder builder) {
            Objects.requireNonNull(in, "PersistableBundle was null");
            builder.setEapMsChapV2Config(in.getString(USERNAME_KEY), in.getString(PASSWORD_KEY));
        }
    }

    private static final class EapTtlsConfigUtils extends EapMethodConfigUtils {
        private static final String TRUST_CERT_KEY = "TRUST_CERT_KEY";
        private static final String EAP_SESSION_CONFIG_KEY = "EAP_SESSION_CONFIG_KEY";

        @NonNull
        public static PersistableBundle toPersistableBundle(@NonNull EapTtlsConfig config) {
            final PersistableBundle result = EapMethodConfigUtils.toPersistableBundle(config);
            try {
                if (config.getServerCaCert() != null) {
                    final PersistableBundle caBundle =
                            PersistableBundleUtils.fromByteArray(
                                    config.getServerCaCert().getEncoded());
                    result.putPersistableBundle(TRUST_CERT_KEY, caBundle);
                }
            } catch (CertificateEncodingException e) {
                throw new IllegalStateException("Fail to encode the certificate");
            }

            result.putPersistableBundle(
                    EAP_SESSION_CONFIG_KEY,
                    EapSessionConfigUtils.toPersistableBundle(config.getInnerEapSessionConfig()));

            return result;
        }

        public static void setBuilderByReadingPersistableBundle(
                @NonNull PersistableBundle in, @NonNull EapSessionConfig.Builder builder) {
            Objects.requireNonNull(in, "PersistableBundle was null");

            final PersistableBundle caBundle = in.getPersistableBundle(TRUST_CERT_KEY);
            X509Certificate caCert = null;
            if (caBundle != null) {
                caCert =
                        CertUtils.certificateFromByteArray(
                                PersistableBundleUtils.toByteArray(caBundle));
            }

            final PersistableBundle eapSessionConfigBundle =
                    in.getPersistableBundle(EAP_SESSION_CONFIG_KEY);
            Objects.requireNonNull(eapSessionConfigBundle, "Inner EAP Session Config was null");
            final EapSessionConfig eapSessionConfig =
                    EapSessionConfigUtils.fromPersistableBundle(eapSessionConfigBundle);

            builder.setEapTtlsConfig(caCert, eapSessionConfig);
        }
    }
}
