/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package android.net.http;

import android.net.http.SslCertificate;
import android.test.suitebuilder.annotation.LargeTest;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import junit.framework.TestCase;

public class SslCertificateTest extends TestCase {

    /**
     * Problematic certificate from Issue 1597
     * http://code.google.com/p/android/issues/detail?id=1597
     */
    private static final String Issue1597Certificate =
        "-----BEGIN CERTIFICATE-----\n"+
        "MIIBnjCCAQegAwIBAgIFAKvN774wDQYJKoZIhvcNAQEFBQAwADAeFw0yNzA5MjQw\n"+
        "MDAwMDFaFw0zNzA5MjQwMDAwMDFaMAAwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJ\n"+
        "AoGBAMNAaUSKw3stg6UHx6bWHNn0T5WR39UB43EZqdhhM0hnfpzwAzNs1T3jOAzF\n"+
        "OtgcX/XVt2Exc1vnwwuiJfvtPtBtQVsNu7wfk45cTUF45axBr4v8oFq7DOHCvs2C\n"+
        "pBDnw/v9PoOihuBamOjzRPL+oVhVfzEqEOILnZD1qEeVJn4RAgMBAAGjJDAiMCAG\n"+
        "A1UdEQQZMBeGD2h0dHBzOi8vMS4xLjEuMYcEAQEBATANBgkqhkiG9w0BAQUFAAOB\n"+
        "gQA7CMJylEjCR9CjztZUMLOutLe64RNhMq9iKgbDfJwYrcgvUNOxjrCdFW66lE9N\n"+
        "TDscc4zS2kpV41vcVYiGwabCNUPi2P6zfFSpYmGqwwu1NoEayqGPdDMrgCnMXVYV\n"+
        "X7HoVif4IdGvjFQrYcyU2VWSWBq6IGMVCR6RkC2YWnnNhw==\n"+
        "-----END CERTIFICATE-----\n";

    @LargeTest
    public void testSslCertificateWithEmptyIssuer() throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate x509Certificate = (X509Certificate)
            certificateFactory.generateCertificate(new ByteArrayInputStream(Issue1597Certificate.getBytes()));
        assertEquals(x509Certificate.getIssuerDN().getName(), "");
        SslCertificate sslCertificate = new SslCertificate(x509Certificate);
        assertEquals(sslCertificate.getIssuedBy().getDName(), "");
    }
}
