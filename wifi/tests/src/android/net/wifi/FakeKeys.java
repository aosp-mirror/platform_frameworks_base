/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * A class containing test certificates and private keys.
 */
public class FakeKeys {
    private static final String CA_CERT0_STRING = "-----BEGIN CERTIFICATE-----\n" +
            "MIIDKDCCAhCgAwIBAgIJAILlFdwzLVurMA0GCSqGSIb3DQEBCwUAMBIxEDAOBgNV\n" +
            "BAMTB0VBUCBDQTEwHhcNMTYwMTEyMTE1MDE1WhcNMjYwMTA5MTE1MDE1WjASMRAw\n" +
            "DgYDVQQDEwdFQVAgQ0ExMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA\n" +
            "znAPUz26Msae4ws43czR41/J2QtrSIZUKmVUsVumDbYHrPNvTXKSMXAcewORDQYX\n" +
            "RqvHvpn8CscB1+oGXZvHwxj4zV0WKoK2zeXkau3vcyl3HIKupJfq2TEACefVjj0t\n" +
            "JW+X35PGWp9/H5zIUNVNVjS7Ums84IvKhRB8512PB9UyHagXYVX5GWpAcVpyfrlR\n" +
            "FI9Qdhh+Pbk0uyktdbf/CdfgHOoebrTtwRljM0oDtX+2Cv6j0wBK7hD8pPvf1+uy\n" +
            "GzczigAU/4Kw7eZqydf9B+5RupR+IZipX41xEiIrKRwqi517WWzXcjaG2cNbf451\n" +
            "xpH5PnV3i1tq04jMGQUzFwIDAQABo4GAMH4wHQYDVR0OBBYEFIwX4vs8BiBcScod\n" +
            "5noZHRM8E4+iMEIGA1UdIwQ7MDmAFIwX4vs8BiBcScod5noZHRM8E4+ioRakFDAS\n" +
            "MRAwDgYDVQQDEwdFQVAgQ0ExggkAguUV3DMtW6swDAYDVR0TBAUwAwEB/zALBgNV\n" +
            "HQ8EBAMCAQYwDQYJKoZIhvcNAQELBQADggEBAFfQqOTA7Rv7K+luQ7pnas4BYwHE\n" +
            "9GEP/uohv6KOy0TGQFbrRTjFoLVNB9BZ1ymMDZ0/TIwIUc7wi7a8t5mEqYH153wW\n" +
            "aWooiSjyLLhuI4sNrNCOtisdBq2r2MFXt6h0mAQYOPv8R8K7/fgSxGFqzhyNmmVL\n" +
            "1qBJldx34SpwsTALQVPb4hGwJzZfr1PcpEQx6xMnTl8xEWZE3Ms99uaUxbQqIwRu\n" +
            "LgAOkNCmY2m89VhzaHJ1uV85AdM/tD+Ysmlnnjt9LRCejbBipjIGjOXrg1JP+lxV\n" +
            "muM4vH+P/mlmxsPPz0d65b+EGmJZpoLkO/tdNNvCYzjJpTEWpEsO6NMhKYo=\n" +
            "-----END CERTIFICATE-----\n";
    public static final X509Certificate CA_CERT0 = loadCertificate(CA_CERT0_STRING);

    private static final String CA_CERT1_STRING = "-----BEGIN CERTIFICATE-----\n" +
            "MIIDKDCCAhCgAwIBAgIJAOM5SzKO2pzCMA0GCSqGSIb3DQEBCwUAMBIxEDAOBgNV\n" +
            "BAMTB0VBUCBDQTAwHhcNMTYwMTEyMDAxMDQ3WhcNMjYwMTA5MDAxMDQ3WjASMRAw\n" +
            "DgYDVQQDEwdFQVAgQ0EwMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA\n" +
            "89ug+IEKVQXnJGKg5g4uVHg6J/8iRUxR5k2eH5o03hrJNMfN2D+cBe/wCiZcnWbI\n" +
            "GbGZACWm2nQth2wy9Zgm2LOd3b4ocrHYls3XLq6Qb5Dd7a0JKU7pdGufiNVEkrmF\n" +
            "EB+N64wgwH4COTvCiN4erp5kyJwkfqAl2xLkZo0C464c9XoyQOXbmYD9A8v10wZu\n" +
            "jyNsEo7Nr2USyw+qhjWSbFbEirP77Tvx+7pJQJwdtk1V9Tn73T2dGF2WHYejei9S\n" +
            "mcWpdIUqsu9etYH+zDmtu7I1xlkwiaVsNr2+D+qaCJyOYqrDTKVNK5nmbBPXDWZc\n" +
            "NoDbTOoqquX7xONpq9M6jQIDAQABo4GAMH4wHQYDVR0OBBYEFAZ3A2S4qJZZwuNY\n" +
            "wkJ6mAdc0gVdMEIGA1UdIwQ7MDmAFAZ3A2S4qJZZwuNYwkJ6mAdc0gVdoRakFDAS\n" +
            "MRAwDgYDVQQDEwdFQVAgQ0EwggkA4zlLMo7anMIwDAYDVR0TBAUwAwEB/zALBgNV\n" +
            "HQ8EBAMCAQYwDQYJKoZIhvcNAQELBQADggEBAHmdMwEhtys4d0E+t7owBmoVR+lU\n" +
            "hMCcRtWs8YKX5WIM2kTweT0h/O1xwE1mWmRv/IbDAEb8od4BjAQLhIcolStr2JaO\n" +
            "9ZzyxjOnNzqeErh/1DHDbb/moPpqfeJ8YiEz7nH/YU56Q8iCPO7TsgS0sNNE7PfN\n" +
            "IUsBW0yHRgpQ4OxWmiZG2YZWiECRzAC0ecPzo59N5iH4vLQIMTMYquiDeMPQnn1e\n" +
            "NDGxG8gCtDKIaS6tMg3a28MvWB094pr2ETou8O1C8Ji0Y4hE8QJmSdT7I4+GZjgW\n" +
            "g94DZ5RiL7sdp3vC48CXOmeT61YBIvhGUsE1rPhXqkpqQ3Z3C4TFF0jXZZc=\n" +
            "-----END CERTIFICATE-----\n";
    public static final X509Certificate CA_CERT1 = loadCertificate(CA_CERT1_STRING);

    private static final String CLIENT_CERT_STR = "-----BEGIN CERTIFICATE-----\n" +
            "MIIE/DCCAuQCAQEwDQYJKoZIhvcNAQELBQAwRDELMAkGA1UEBhMCVVMxCzAJBgNV\n" +
            "BAgMAkNBMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRAwDgYDVQQKDAdUZXN0aW5n\n" +
            "MB4XDTE2MDkzMDIwNTQyOFoXDTE3MDkzMDIwNTQyOFowRDELMAkGA1UEBhMCVVMx\n" +
            "CzAJBgNVBAgMAkNBMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRAwDgYDVQQKDAdU\n" +
            "ZXN0aW5nMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAnpmcbuaeHfnJ\n" +
            "k+2QNvxmdVFTawyFMNk0USCq5sexscwmxbewG/Rb8YnixwJWS44v2XkSujB67z5C\n" +
            "s2qudFEhRXKdEuC6idbAuA97KjipHh0AAniWMsyv61fvbgsUC0b0canx3LiDq81p\n" +
            "y28NNGmAvoazLZUZ4AhBRiwYZY6FKk723gmZoGbEIeG7J1dlXPusc1662rIjz4eU\n" +
            "zlmmlvqyHfNqnNk8L14Vug6Xh+lOEGN85xhu1YHAEKGrS89kZxs5rum/cZU8KH2V\n" +
            "v6eKnY03kxjiVLQtnLpm/7VUEoCMGHyruRj+p3my4+DgqMsmsH52RZCBsjyGlpbU\n" +
            "NOwOTIX6xh+Rqloduz4AnrMYYIiIw2s8g+2zJM7VbcVKx0fGS26BKdrxgrXWfmNE\n" +
            "nR0/REQ5AxDGw0jfTUvtdTkXAf+K4MDjcNLEZ+MA4rHfAfQWZtUR5BkHCQYxNpJk\n" +
            "pA0gyk+BpKdC4WdzI14NSWsu5sRCmBCFqH6BTOSEq/V1cNorBxNwLSSTwFFqUDqx\n" +
            "Y5nQLXygkJf9WHZWtSKeSjtOYgilz7UKzC2s3CsjmIyGFe+SwpuHJnuE4Uc8Z5Cb\n" +
            "bjNGHPzqL6XnmzZHJp7RF8kBdKdjGC7dCUltzOfICZeKlzOOq+Kw42T/nXjuXvpb\n" +
            "nkXNxg741Nwd6RecykXJbseFwm3EYxkCAwEAATANBgkqhkiG9w0BAQsFAAOCAgEA\n" +
            "Ga1mGwI9aXkL2fTPXO9YkAPzoGeX8aeuVYSQaSkNq+5vnogYCyAt3YDHjRG+ewTT\n" +
            "WbnPA991xRAPac+biJeXWmwvgGj0YuT7e79phAiGkTTnbAjFHGfYnBy/tI/v7btO\n" +
            "hRNElA5yTJ1m2fVbBEKXzMR83jrT9iyI+YLRN86zUZIaC86xxSbqnrdWN2jOK6MX\n" +
            "dS8Arp9tPQjC/4gW+2Ilxv68jiYh+5auWHQZVjppWVY//iu4mAbkq1pTwQEhZ8F8\n" +
            "Zrmh9DHh60hLFcfSuhIAwf/NMzppwdkjy1ruKVrpijhGKGp4OWu8nvOUgHSzxc7F\n" +
            "PwpVZ5N2Ku4L8MLO6BG2VasRJK7l17TzDXlfLZHJjkuryOFxVaQKt8ZNFgTOaCXS\n" +
            "E+gpTLksKU7riYckoiP4+H1sn9qcis0e8s4o/uf1UVc8GSdDw61ReGM5oZEDm1u8\n" +
            "H9x20QU6igLqzyBpqvCKv7JNgU1uB2PAODHH78zJiUfnKd1y+o+J1iWzaGj3EFji\n" +
            "T8AXksbTP733FeFXfggXju2dyBH+Z1S5BBTEOd1brWgXlHSAZGm97MKZ94r6/tkX\n" +
            "qfv3fCos0DKz0oV7qBxYS8wiYhzrRVxG6ITAoH8uuUVVQaZF+G4nJ2jEqNbfuKyX\n" +
            "ATQsVNjNNlDA0J33GobPMjT326wa4YAWMx8PI5PJZ3g=\n" +
            "-----END CERTIFICATE-----\n";
    public static final X509Certificate CLIENT_CERT = loadCertificate(CLIENT_CERT_STR);

    private static final byte[] FAKE_RSA_KEY_1 = new byte[] {
            (byte) 0x30, (byte) 0x82, (byte) 0x02, (byte) 0x78, (byte) 0x02, (byte) 0x01,
            (byte) 0x00, (byte) 0x30, (byte) 0x0d, (byte) 0x06, (byte) 0x09, (byte) 0x2a,
            (byte) 0x86, (byte) 0x48, (byte) 0x86, (byte) 0xf7, (byte) 0x0d, (byte) 0x01,
            (byte) 0x01, (byte) 0x01, (byte) 0x05, (byte) 0x00, (byte) 0x04, (byte) 0x82,
            (byte) 0x02, (byte) 0x62, (byte) 0x30, (byte) 0x82, (byte) 0x02, (byte) 0x5e,
            (byte) 0x02, (byte) 0x01, (byte) 0x00, (byte) 0x02, (byte) 0x81, (byte) 0x81,
            (byte) 0x00, (byte) 0xce, (byte) 0x29, (byte) 0xeb, (byte) 0xf6, (byte) 0x5b,
            (byte) 0x25, (byte) 0xdc, (byte) 0xa1, (byte) 0xa6, (byte) 0x2c, (byte) 0x66,
            (byte) 0xcb, (byte) 0x20, (byte) 0x90, (byte) 0x27, (byte) 0x86, (byte) 0x8a,
            (byte) 0x44, (byte) 0x71, (byte) 0x50, (byte) 0xda, (byte) 0xd3, (byte) 0x02,
            (byte) 0x77, (byte) 0x55, (byte) 0xe9, (byte) 0xe8, (byte) 0x08, (byte) 0xf3,
            (byte) 0x36, (byte) 0x9a, (byte) 0xae, (byte) 0xab, (byte) 0x04, (byte) 0x6d,
            (byte) 0x00, (byte) 0x99, (byte) 0xbf, (byte) 0x7d, (byte) 0x0f, (byte) 0x67,
            (byte) 0x8b, (byte) 0x1d, (byte) 0xd4, (byte) 0x2b, (byte) 0x7c, (byte) 0xcb,
            (byte) 0xcd, (byte) 0x33, (byte) 0xc7, (byte) 0x84, (byte) 0x30, (byte) 0xe2,
            (byte) 0x45, (byte) 0x21, (byte) 0xb3, (byte) 0x75, (byte) 0xf5, (byte) 0x79,
            (byte) 0x02, (byte) 0xda, (byte) 0x50, (byte) 0xa3, (byte) 0x8b, (byte) 0xce,
            (byte) 0xc3, (byte) 0x8e, (byte) 0x0f, (byte) 0x25, (byte) 0xeb, (byte) 0x08,
            (byte) 0x2c, (byte) 0xdd, (byte) 0x1c, (byte) 0xcf, (byte) 0xff, (byte) 0x3b,
            (byte) 0xde, (byte) 0xb6, (byte) 0xaa, (byte) 0x2a, (byte) 0xa9, (byte) 0xc4,
            (byte) 0x8a, (byte) 0x24, (byte) 0x24, (byte) 0xe6, (byte) 0x29, (byte) 0x0d,
            (byte) 0x98, (byte) 0x4c, (byte) 0x32, (byte) 0xa1, (byte) 0x7b, (byte) 0x23,
            (byte) 0x2b, (byte) 0x42, (byte) 0x30, (byte) 0xee, (byte) 0x78, (byte) 0x08,
            (byte) 0x47, (byte) 0xad, (byte) 0xf2, (byte) 0x96, (byte) 0xd5, (byte) 0xf1,
            (byte) 0x62, (byte) 0x42, (byte) 0x2d, (byte) 0x35, (byte) 0x19, (byte) 0xb4,
            (byte) 0x3c, (byte) 0xc9, (byte) 0xc3, (byte) 0x5f, (byte) 0x03, (byte) 0x16,
            (byte) 0x3a, (byte) 0x23, (byte) 0xac, (byte) 0xcb, (byte) 0xce, (byte) 0x9e,
            (byte) 0x51, (byte) 0x2e, (byte) 0x6d, (byte) 0x02, (byte) 0x03, (byte) 0x01,
            (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x81, (byte) 0x80, (byte) 0x16,
            (byte) 0x59, (byte) 0xc3, (byte) 0x24, (byte) 0x1d, (byte) 0x33, (byte) 0x98,
            (byte) 0x9c, (byte) 0xc9, (byte) 0xc8, (byte) 0x2c, (byte) 0x88, (byte) 0xbf,
            (byte) 0x0a, (byte) 0x01, (byte) 0xce, (byte) 0xfb, (byte) 0x34, (byte) 0x7a,
            (byte) 0x58, (byte) 0x7a, (byte) 0xb0, (byte) 0xbf, (byte) 0xa6, (byte) 0xb2,
            (byte) 0x60, (byte) 0xbe, (byte) 0x70, (byte) 0x21, (byte) 0xf5, (byte) 0xfc,
            (byte) 0x85, (byte) 0x0d, (byte) 0x33, (byte) 0x58, (byte) 0xa1, (byte) 0xe5,
            (byte) 0x09, (byte) 0x36, (byte) 0x84, (byte) 0xb2, (byte) 0x04, (byte) 0x0a,
            (byte) 0x02, (byte) 0xd3, (byte) 0x88, (byte) 0x1f, (byte) 0x0c, (byte) 0x2b,
            (byte) 0x1d, (byte) 0xe9, (byte) 0x3d, (byte) 0xe7, (byte) 0x79, (byte) 0xf9,
            (byte) 0x32, (byte) 0x5c, (byte) 0x8a, (byte) 0x75, (byte) 0x49, (byte) 0x12,
            (byte) 0xe4, (byte) 0x05, (byte) 0x26, (byte) 0xd4, (byte) 0x2e, (byte) 0x9e,
            (byte) 0x1f, (byte) 0xcc, (byte) 0x54, (byte) 0xad, (byte) 0x33, (byte) 0x8d,
            (byte) 0x99, (byte) 0x00, (byte) 0xdc, (byte) 0xf5, (byte) 0xb4, (byte) 0xa2,
            (byte) 0x2f, (byte) 0xba, (byte) 0xe5, (byte) 0x62, (byte) 0x30, (byte) 0x6d,
            (byte) 0xe6, (byte) 0x3d, (byte) 0xeb, (byte) 0x24, (byte) 0xc2, (byte) 0xdc,
            (byte) 0x5f, (byte) 0xb7, (byte) 0x16, (byte) 0x35, (byte) 0xa3, (byte) 0x98,
            (byte) 0x98, (byte) 0xa8, (byte) 0xef, (byte) 0xe8, (byte) 0xc4, (byte) 0x96,
            (byte) 0x6d, (byte) 0x38, (byte) 0xab, (byte) 0x26, (byte) 0x6d, (byte) 0x30,
            (byte) 0xc2, (byte) 0xa0, (byte) 0x44, (byte) 0xe4, (byte) 0xff, (byte) 0x7e,
            (byte) 0xbe, (byte) 0x7c, (byte) 0x33, (byte) 0xa5, (byte) 0x10, (byte) 0xad,
            (byte) 0xd7, (byte) 0x1e, (byte) 0x13, (byte) 0x20, (byte) 0xb3, (byte) 0x1f,
            (byte) 0x41, (byte) 0x02, (byte) 0x41, (byte) 0x00, (byte) 0xf1, (byte) 0x89,
            (byte) 0x07, (byte) 0x0f, (byte) 0xe8, (byte) 0xcf, (byte) 0xab, (byte) 0x13,
            (byte) 0x2a, (byte) 0x8f, (byte) 0x88, (byte) 0x80, (byte) 0x11, (byte) 0x9a,
            (byte) 0x79, (byte) 0xb6, (byte) 0x59, (byte) 0x3a, (byte) 0x50, (byte) 0x6e,
            (byte) 0x57, (byte) 0x37, (byte) 0xab, (byte) 0x2a, (byte) 0xd2, (byte) 0xaa,
            (byte) 0xd9, (byte) 0x72, (byte) 0x73, (byte) 0xff, (byte) 0x8b, (byte) 0x47,
            (byte) 0x76, (byte) 0xdd, (byte) 0xdc, (byte) 0xf5, (byte) 0x97, (byte) 0x44,
            (byte) 0x3a, (byte) 0x78, (byte) 0xbe, (byte) 0x17, (byte) 0xb4, (byte) 0x22,
            (byte) 0x6f, (byte) 0xe5, (byte) 0x23, (byte) 0x70, (byte) 0x1d, (byte) 0x10,
            (byte) 0x5d, (byte) 0xba, (byte) 0x16, (byte) 0x81, (byte) 0xf1, (byte) 0x45,
            (byte) 0xce, (byte) 0x30, (byte) 0xb4, (byte) 0xab, (byte) 0x80, (byte) 0xe4,
            (byte) 0x98, (byte) 0x31, (byte) 0x02, (byte) 0x41, (byte) 0x00, (byte) 0xda,
            (byte) 0x82, (byte) 0x9d, (byte) 0x3f, (byte) 0xca, (byte) 0x2f, (byte) 0xe1,
            (byte) 0xd4, (byte) 0x86, (byte) 0x77, (byte) 0x48, (byte) 0xa6, (byte) 0xab,
            (byte) 0xab, (byte) 0x1c, (byte) 0x42, (byte) 0x5c, (byte) 0xd5, (byte) 0xc7,
            (byte) 0x46, (byte) 0x59, (byte) 0x91, (byte) 0x3f, (byte) 0xfc, (byte) 0xcc,
            (byte) 0xec, (byte) 0xc2, (byte) 0x40, (byte) 0x12, (byte) 0x2c, (byte) 0x8d,
            (byte) 0x1f, (byte) 0xa2, (byte) 0x18, (byte) 0x88, (byte) 0xee, (byte) 0x82,
            (byte) 0x4a, (byte) 0x5a, (byte) 0x5e, (byte) 0x88, (byte) 0x20, (byte) 0xe3,
            (byte) 0x7b, (byte) 0xe0, (byte) 0xd8, (byte) 0x3a, (byte) 0x52, (byte) 0x9a,
            (byte) 0x26, (byte) 0x6a, (byte) 0x04, (byte) 0xec, (byte) 0xe8, (byte) 0xb9,
            (byte) 0x48, (byte) 0x40, (byte) 0xe1, (byte) 0xe1, (byte) 0x83, (byte) 0xa6,
            (byte) 0x67, (byte) 0xa6, (byte) 0xfd, (byte) 0x02, (byte) 0x41, (byte) 0x00,
            (byte) 0x89, (byte) 0x72, (byte) 0x3e, (byte) 0xb0, (byte) 0x90, (byte) 0xfd,
            (byte) 0x4c, (byte) 0x0e, (byte) 0xd6, (byte) 0x13, (byte) 0x63, (byte) 0xcb,
            (byte) 0xed, (byte) 0x38, (byte) 0x88, (byte) 0xb6, (byte) 0x79, (byte) 0xc4,
            (byte) 0x33, (byte) 0x6c, (byte) 0xf6, (byte) 0xf8, (byte) 0xd8, (byte) 0xd0,
            (byte) 0xbf, (byte) 0x9d, (byte) 0x35, (byte) 0xac, (byte) 0x69, (byte) 0xd2,
            (byte) 0x2b, (byte) 0xc1, (byte) 0xf9, (byte) 0x24, (byte) 0x7b, (byte) 0xce,
            (byte) 0xcd, (byte) 0xcb, (byte) 0xa7, (byte) 0xb2, (byte) 0x7a, (byte) 0x0a,
            (byte) 0x27, (byte) 0x19, (byte) 0xc9, (byte) 0xaf, (byte) 0x0d, (byte) 0x21,
            (byte) 0x89, (byte) 0x88, (byte) 0x7c, (byte) 0xad, (byte) 0x9e, (byte) 0x8d,
            (byte) 0x47, (byte) 0x6d, (byte) 0x3f, (byte) 0xce, (byte) 0x7b, (byte) 0xa1,
            (byte) 0x74, (byte) 0xf1, (byte) 0xa0, (byte) 0xa1, (byte) 0x02, (byte) 0x41,
            (byte) 0x00, (byte) 0xd9, (byte) 0xa8, (byte) 0xf5, (byte) 0xfe, (byte) 0xce,
            (byte) 0xe6, (byte) 0x77, (byte) 0x6b, (byte) 0xfe, (byte) 0x2d, (byte) 0xe0,
            (byte) 0x1e, (byte) 0xb6, (byte) 0x2e, (byte) 0x12, (byte) 0x4e, (byte) 0x40,
            (byte) 0xaf, (byte) 0x6a, (byte) 0x7b, (byte) 0x37, (byte) 0x49, (byte) 0x2a,
            (byte) 0x96, (byte) 0x25, (byte) 0x83, (byte) 0x49, (byte) 0xd4, (byte) 0x0c,
            (byte) 0xc6, (byte) 0x78, (byte) 0x25, (byte) 0x24, (byte) 0x90, (byte) 0x90,
            (byte) 0x06, (byte) 0x15, (byte) 0x9e, (byte) 0xfe, (byte) 0xf9, (byte) 0xdf,
            (byte) 0x5b, (byte) 0xf3, (byte) 0x7e, (byte) 0x38, (byte) 0x70, (byte) 0xeb,
            (byte) 0x57, (byte) 0xd0, (byte) 0xd9, (byte) 0xa7, (byte) 0x0e, (byte) 0x14,
            (byte) 0xf7, (byte) 0x95, (byte) 0x68, (byte) 0xd5, (byte) 0xc8, (byte) 0xab,
            (byte) 0x9d, (byte) 0x3a, (byte) 0x2b, (byte) 0x51, (byte) 0xf9, (byte) 0x02,
            (byte) 0x41, (byte) 0x00, (byte) 0x96, (byte) 0xdf, (byte) 0xe9, (byte) 0x67,
            (byte) 0x6c, (byte) 0xdc, (byte) 0x90, (byte) 0x14, (byte) 0xb4, (byte) 0x1d,
            (byte) 0x22, (byte) 0x33, (byte) 0x4a, (byte) 0x31, (byte) 0xc1, (byte) 0x9d,
            (byte) 0x2e, (byte) 0xff, (byte) 0x9a, (byte) 0x2a, (byte) 0x95, (byte) 0x4b,
            (byte) 0x27, (byte) 0x74, (byte) 0xcb, (byte) 0x21, (byte) 0xc3, (byte) 0xd2,
            (byte) 0x0b, (byte) 0xb2, (byte) 0x46, (byte) 0x87, (byte) 0xf8, (byte) 0x28,
            (byte) 0x01, (byte) 0x8b, (byte) 0xd8, (byte) 0xb9, (byte) 0x4b, (byte) 0xcd,
            (byte) 0x9a, (byte) 0x96, (byte) 0x41, (byte) 0x0e, (byte) 0x36, (byte) 0x6d,
            (byte) 0x40, (byte) 0x42, (byte) 0xbc, (byte) 0xd9, (byte) 0xd3, (byte) 0x7b,
            (byte) 0xbc, (byte) 0xa7, (byte) 0x92, (byte) 0x90, (byte) 0xdd, (byte) 0xa1,
            (byte) 0x9c, (byte) 0xce, (byte) 0xa1, (byte) 0x87, (byte) 0x11, (byte) 0x51
    };
    public static final PrivateKey RSA_KEY1 = loadPrivateRSAKey(FAKE_RSA_KEY_1);

    private static X509Certificate loadCertificate(String blob) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            InputStream stream = new ByteArrayInputStream(blob.getBytes(StandardCharsets.UTF_8));

            return (X509Certificate) certFactory.generateCertificate(stream);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static PrivateKey loadPrivateRSAKey(byte[] fakeKey) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(fakeKey));
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
