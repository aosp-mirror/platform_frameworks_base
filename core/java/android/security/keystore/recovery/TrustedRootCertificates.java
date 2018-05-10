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
            + "MIIFJjCCAw6gAwIBAgIJAIobXsJlzhNdMA0GCSqGSIb3DQEBDQUAMCAxHjAcBgNV"
            + "BAMMFUdvb2dsZSBDcnlwdEF1dGhWYXVsdDAeFw0xODAyMDIxOTM5MTRaFw0zODAx"
            + "MjgxOTM5MTRaMCAxHjAcBgNVBAMMFUdvb2dsZSBDcnlwdEF1dGhWYXVsdDCCAiIw"
            + "DQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK2OT5i40/H7LINg/lq/0G0hR65P"
            + "Q4Mud3OnuVt6UIYV2T18+v6qW1yJd5FcnND/ZKPau4aUAYklqJuSVjOXQD0BjgS2"
            + "98Xa4dSn8Ci1rUR+5tdmrxqbYUdT2ZvJIUMMR6fRoqi+LlAbKECrV+zYQTyLU68w"
            + "V66hQpAButjJKiZzkXjmKLfJ5IWrNEn17XM988rk6qAQn/BYCCQGf3rQuJeksGmA"
            + "N1lJOwNYxmWUyouVwqwZthNEWqTuEyBFMkAT+99PXW7oVDc7oU5cevuihxQWNTYq"
            + "viGB8cck6RW3cmqrDSaJF/E+N0cXFKyYC7FDcggt6k3UrxNKTuySdDEa8+2RTQqU"
            + "Y9npxBlQE+x9Ig56OI1BG3bSBsGdPgjpyHadZeh2tgk+oqlGsSsum24YxaxuSysT"
            + "Qfcu/XhyfUXavfmGrBOXerTzIl5oBh/F5aHTV85M2tYEG0qsPPvSpZAWtdJ/2rca"
            + "OxvhwOL+leZKr8McjXVR00lBsRuKXX4nTUMwya09CO3QHFPFZtZvqjy2HaMOnVLQ"
            + "I6b6dHEfmsHybzVOe3yPEoFQSU9UhUdmi71kwwoanPD3j9fJHmXTx4PzYYBRf1ZE"
            + "o+uPgMPk7CDKQFZLjnR40z1uzu3O8aZ3AKZzP+j7T4XQKJLQLmllKtPgLgNdJyib"
            + "2Glg7QhXH/jBTL6hAgMBAAGjYzBhMB0GA1UdDgQWBBSbZfrqOYH54EJpkdKMZjMc"
            + "z/Hp+DAfBgNVHSMEGDAWgBSbZfrqOYH54EJpkdKMZjMcz/Hp+DAPBgNVHRMBAf8E"
            + "BTADAQH/MA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQ0FAAOCAgEAKh9nm/vW"
            + "glMWp3vcCwWwJW286ecREDlI+CjGh5h+f2N4QRrXd/tKE3qQJWCqGx8sFfIUjmI7"
            + "KYdsC2gyQ2cA2zl0w7pB2QkuqE6zVbnh1D17Hwl19IMyAakFaM9ad4/EoH7oQmqX"
            + "nF/f5QXGZw4kf1HcgKgoCHWXjqR8MqHOcXR8n6WFqxjzJf1jxzi6Yo2dZ7PJbnE6"
            + "+kHIJuiCpiHL75v5g1HM41gT3ddFFSrn88ThNPWItT5Z8WpFjryVzank2Yt02LLl"
            + "WqZg9IC375QULc5B58NMnaiVJIDJQ8zoNgj1yaxqtUMnJX570lotO2OXe4ec9aCQ"
            + "DIJ84YLM/qStFdeZ9416E80dchskbDG04GuVJKlzWjxAQNMRFhyaPUSBTLLg+kwP"
            + "t9+AMmc+A7xjtFQLZ9fBYHOBsndJOmeSQeYeckl+z/1WQf7DdwXn/yijon7mxz4z"
            + "cCczfKwTJTwBh3wR5SQr2vQm7qaXM87qxF8PCAZrdZaw5I80QwkgTj0WTZ2/GdSw"
            + "d3o5SyzzBAjpwtG+4bO/BD9h9wlTsHpT6yWOZs4OYAKU5ykQrncI8OyavMggArh3"
            + "/oM58v0orUWINtIc2hBlka36PhATYQiLf+AiWKnwhCaaHExoYKfQlMtXBodNvOK8"
            + "xqx69x05q/qbHKEcTHrsss630vxrp1niXvA=";

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
     *
     * TODO: Change it to the production certificate root CA before the final launch.
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
