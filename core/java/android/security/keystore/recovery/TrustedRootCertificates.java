/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.security.keystore.recovery;

import static android.security.keystore.recovery.X509CertificateParsingUtils.decodeBase64Cert;

import android.annotation.NonNull;
import android.util.ArrayMap;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Trusted root certificates for use by the
 * {@link android.security.keystore.recovery.RecoveryController}. These certificates are used to
 * verify the public keys of remote secure hardware modules. This is to prevent AOSP backing up keys
 * to untrusted devices.
 *
 * @hide
 */
public final class TrustedRootCertificates {

    public static final String GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_ALIAS =
            "GoogleCloudKeyVaultServiceV1";
    /**
     * Certificate used for client-side end-to-end encryption tests.
     * When recovery controller is initialized with the certificate, recovery snapshots will only
     * contain application keys started with {@link #INSECURE_KEY_ALIAS_PREFIX}.
     * Recovery snapshot will only be created if device is unlocked with password started with
     * {@link #INSECURE_PASSWORD_PREFIX}.
     *
     * @hide
     */
    public static final String TEST_ONLY_INSECURE_CERTIFICATE_ALIAS =
            "TEST_ONLY_INSECURE_CERTIFICATE_ALIAS";

    /**
     * TODO: Add insecure certificate to TestApi.
     * @hide
     */
    public static @NonNull X509Certificate getTestOnlyInsecureCertificate() {
        return parseBase64Certificate(TEST_ONLY_INSECURE_CERTIFICATE_BASE64);
    }
    /**
     * Keys, which alias starts with the prefix are not protected if
     * recovery agent uses {@link #TEST_ONLY_INSECURE_CERTIFICATE_ALIAS} root certificate.
     * @hide
     */
    public static final String INSECURE_KEY_ALIAS_PREFIX =
            "INSECURE_KEY_ALIAS_KEY_MATERIAL_IS_NOT_PROTECTED_";
    /**
     * Prefix for insecure passwords with length 14.
     * Passwords started with the prefix are not protected if recovery agent uses
     * {@link #TEST_ONLY_INSECURE_CERTIFICATE_ALIAS} root certificate.
     * @hide
     */
    public static final String INSECURE_PASSWORD_PREFIX =
            "INSECURE_PSWD_";

    private static final String GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_BASE64 = ""
            + "MIIFDzCCAvegAwIBAgIQbNdueU2o0vM9gGq4N6bhjzANBgkqhkiG9w0BAQsFADAx"
            + "MS8wLQYDVQQDEyZHb29nbGUgQ2xvdWQgS2V5IFZhdWx0IFNlcnZpY2UgUm9vdCBD"
            + "QTAeFw0xODA1MDcxODI0MDJaFw0zODA1MDgxOTI0MDJaMDExLzAtBgNVBAMTJkdv"
            + "b2dsZSBDbG91ZCBLZXkgVmF1bHQgU2VydmljZSBSb290IENBMIICIjANBgkqhkiG"
            + "9w0BAQEFAAOCAg8AMIICCgKCAgEArUgzu+4o9yl22eql1BiGBq3gWXooh2ql3J+v"
            + "Vuzf/ThjzdIg0xkkkw/NAFxYFi49Eo1fa/hf8wCIoAqCEs1lD6tE3cCD3T3+EQPq"
            + "uh6CB2KmZDJ6mPnXvVUlUuFr0O2MwZkwylqBETzK0x5NCHgL/p47vkjhHx6LqVao"
            + "bigKlHxszvVi4fkt/qq7KW3YTVxhwdLGEab+OqSfwMxdBLhMfE0K0dvFt8bs8yJA"
            + "F04DJsMbRChFFBpT17Z0u53iIAAu5qVQhKrQXiIAwgboZqd+JkHLXU1fJeVT5WJO"
            + "JgoJFWHkdWkHta4mSYlS72J1Q927JD1JdET1kFtH+EDtYAtx7x7F9xAAbb2tMITw"
            + "s/wwd2rAzZTX/kxRbDlXVLToU05LFYPr+dFV1wvXmi0jlkIxnhdaVBqWC93p528U"
            + "iUcLpib+HVzMWGdYI3G1NOa/lTp0c8LcbJjapiiVneRQJ3cIqDPOSEnEq40hyZd1"
            + "jx3JnOxJMwHs8v4s9GIlb3BcOmDvA/Mu09xEMKwpHBm4TFDKXeGHOWha7ccWEECb"
            + "yO5ncu6XuN2iyz9S+TuMyjZBE552p6Pu5gEC2xk+qab0NGDTHdLKLbyWn3IxdmBH"
            + "yTr7iPCqmpyHngkC/pbGfvGusc5BpBugsBtlz67m4RWLJ72yAeVPO/ly/8w4orNs"
            + "GWjn3s0CAwEAAaMjMCEwDgYDVR0PAQH/BAQDAgGGMA8GA1UdEwEB/wQFMAMBAf8w"
            + "DQYJKoZIhvcNAQELBQADggIBAGiWlu+4qyxgPb6RsA0mwR7V21UJ9rEpYhSN+ARp"
            + "TWGiI22RCJSGK0ZrPGeFQzE2BpnVRdmLTV5jf9JUStjHoPvNYFnwLTJ0E2e9Olj8"
            + "MrHrAucAUFLhl4woWz0kU/X0EB1j6Y2SXrAaZPiMMpq8BKj3mH1MbV4stZ0kiHUp"
            + "Zu6PEmrojYG7FKKN30na2xXfiOfl2JusVsyHDqmUn/HjTh6zASKqE6hxE+FJRl2V"
            + "Q4dcr4SviHtdbimMy2LghLnZ4FE4XhJgRnw9TeRV5C9Sn7pmnAA5X0C8ZXhXvfvr"
            + "dx4fL3UKlk1Lqlb5skxoK1R9wwr+aNIO+cuR8JA5DmEDWFw5Budh/uWWZlBTyVW2"
            + "ybbTB6tkmOc8c08XOgxBaKrsXALmJcluabjmN1jp81ae1epeN31jJ4N5IE5aq7Xb"
            + "TFmKkwpgTTvJmqCR2XzWujlvdbdjfiABliWsnLzLQCP8eZwcM4LA5UK3f1ktHolr"
            + "1OI9etSOkebE2py8LPYBJWlX36tRAagZhU/NoyOtvhRzq9rb3rbf96APEHKUFsXG"
            + "9nBEd2BUKZghLKPf+JNCU/2pOGx0jdMcf+K+a1DeG0YzGYMRkFvpN3hvHYrJdByL"
            + "3kSP3UtD0H2g8Ps7gRLELG2HODxbSn8PV3XtuSvxVanA6uyaaS3AZ6SxeVLvmw50"
            + "7aYI";

    private static final String TEST_ONLY_INSECURE_CERTIFICATE_BASE64 = ""
            + "MIIFMDCCAxigAwIBAgIJAIZ9/G8KQie9MA0GCSqGSIb3DQEBDQUAMCUxIzAhBgNV"
            + "BAMMGlRlc3QgT25seSBVbnNlY3VyZSBSb290IENBMB4XDTE4MDMyODAwMzIyM1oX"
            + "DTM4MDMyMzAwMzIyM1owJTEjMCEGA1UEAwwaVGVzdCBPbmx5IFVuc2VjdXJlIFJv"
            + "b3QgQ0EwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDGxFNzAEyzSPmw"
            + "E5gfuBXdXq++bl9Ep62V7Xn1UiejvmS+pRHT39pf/M7sl4Zr9ezanJTrFvf9+B85"
            + "VGehdsD32TgfEjThcqaoQCI6pKkHYsUo7FZ5n+G3eE8oabWRZJMVo3QDjnnFYp7z"
            + "20vnpjDofI2oQyxHcb/1yep+ca1+4lIvbUp/ybhNFqhRXAMcDXo7pyH38eUQ1JdK"
            + "Q/QlBbShpFEqx1Y6KilKfTDf7Wenqr67LkaEim//yLZjlHzn/BpuRTrpo+XmJZx1"
            + "P9CX9LGOXTtmsaCcYgD4yijOvV8aEsIJaf1kCIO558oH0oQc+0JG5aXeLN7BDlyZ"
            + "vH0RdSx5nQLS9kj2I6nthOw/q00/L+S6A0m5jyNZOAl1SY78p+wO0d9eHbqQzJwf"
            + "EsSq3qGAqlgQyyjp6oxHBqT9hZtN4rxw+iq0K1S4kmTLNF1FvmIB1BE+lNvvoGdY"
            + "5G0b6Pe4R5JFn9LV3C3PEmSYnae7iG0IQlKmRADIuvfJ7apWAVanJPJAAWh2Akfp"
            + "8Uxr02cHoY6o7vsEhJJOeMkipaBHThESm/XeFVubQzNfZ9gjQnB9ZX2v+lyj+WYZ"
            + "SAz3RuXx6TlLrmWccMpQDR1ibcgyyjLUtX3kwZl2OxmJXitjuD7xlxvAXYob15N+"
            + "K4xKHgxUDrbt2zU/tY0vgepAUg/xbwIDAQABo2MwYTAdBgNVHQ4EFgQUwyeNpYgs"
            + "XXYvh9z0/lFrja7sV+swHwYDVR0jBBgwFoAUwyeNpYgsXXYvh9z0/lFrja7sV+sw"
            + "DwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAYYwDQYJKoZIhvcNAQENBQAD"
            + "ggIBAGuOsvMN5SD3RIQnMJtBpcHNrxun+QFjPZFlYCLfIPrUkHpn5O1iIIq8tVLd"
            + "2V+12VKnToUEANsYBD3MP8XjP+6GZ7ZQ2rwLGvUABKSX4YXvmjEEXZUZp0y3tIV4"
            + "kUDlbACzguPneZDp5Qo7YWH4orgqzHkn0sD/ikO5XrAqmzc245ewJlrf+V11mjcu"
            + "ELfDrEejpPhi7Hk/ZNR0ftP737Hs/dNoCLCIaVNgYzBZhgo4kd220TeJu2ttW0XZ"
            + "ldyShtpcOmyWKBgVseixR6L/3sspPHyAPXkSuRo0Eh1xvzDKCg9ttb0qoacTlXMF"
            + "GkBpNzmVq67NWFGGa9UElift1mv6RfktPCAGZ+Ai8xUiKAUB0Eookpt/8gX9Senq"
            + "yP/jMxkxXmHWxUu8+KnLvj6WLrfftuuD7u3cfc7j5kkrheDz3O4h4477GnqL5wdo"
            + "9DuEsNc4FxJVz8Iy8RS6cJuW4pihYpM1Tyn7uopLnImpYzEY+R5aQqqr+q/A1diq"
            + "ogbEKPH6oUiqJUwq3nD70gPBUKJmIzS4vLwLouqUHEm1k/MgHV/BkEU0uVHszPFa"
            + "XUMMCHb0iT9P8LuZ7Ajer3SR/0TRVApCrk/6OV68e+6k/OFpM5kcZnNMD5ANyBri"
            + "Tsz3NrDwSw4i4+Dsfh6A9dB/cEghw4skLaBxnQLQIgVeqCzK";

    /**
     * The X509 certificate of the trusted root CA cert for the recoverable key store service.
     */
    private static final X509Certificate GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_CERTIFICATE =
            parseBase64Certificate(GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_BASE64);

    private static final int NUMBER_OF_ROOT_CERTIFICATES = 1;

    private static final ArrayMap<String, X509Certificate> ALL_ROOT_CERTIFICATES =
            constructRootCertificateMap();

    /**
     * Returns all available root certificates, keyed by alias.
     */
    public static @NonNull Map<String, X509Certificate> getRootCertificates() {
        return new ArrayMap(ALL_ROOT_CERTIFICATES);
    }

    /**
     * Gets a root certificate referenced by the given {@code alias}.
     *
     * @param alias the alias of the certificate
     * @return the certificate referenced by the alias, or null if such a certificate doesn't exist.
     */
    public static @NonNull X509Certificate getRootCertificate(String alias) {
        return ALL_ROOT_CERTIFICATES.get(alias);
    }

    private static ArrayMap<String, X509Certificate> constructRootCertificateMap() {
        ArrayMap<String, X509Certificate> certificates =
                new ArrayMap<>(NUMBER_OF_ROOT_CERTIFICATES);
        certificates.put(
                GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_ALIAS,
                GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_CERTIFICATE);
        return certificates;
    }

    private static X509Certificate parseBase64Certificate(String base64Certificate) {
        try {
            return decodeBase64Cert(base64Certificate);
        } catch (CertificateException e) {
            // Should not happen
            throw new RuntimeException(e);
        }
    }

    // Statics only
    private TrustedRootCertificates() {}
}
