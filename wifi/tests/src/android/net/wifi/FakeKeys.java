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
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * A class containing test certificates.
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
}
