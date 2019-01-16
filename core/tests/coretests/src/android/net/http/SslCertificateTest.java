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

import androidx.test.filters.LargeTest;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

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
        X509Certificate x509Certificate = generateCertificate(Issue1597Certificate);
        assertEquals("", x509Certificate.getSubjectDN().getName());
        SslCertificate sslCertificate = new SslCertificate(x509Certificate);
        assertEquals("", sslCertificate.getIssuedBy().getDName());
    }

    /**
     * Problematic certificate from Issue 41662
     * http://code.google.com/p/android/issues/detail?id=41662
     */
    private static final String Issue41662Certificate =
        "-----BEGIN CERTIFICATE-----\n"+
        "MIIG6jCCBdKgAwIBAgIESPx/LDANBgkqhkiG9w0BAQUFADCBrjESMBAGCgmSJomT\n"+
        "8ixkARkWAnJzMRUwEwYKCZImiZPyLGQBGRYFcG9zdGExEjAQBgoJkiaJk/IsZAEZ\n"+
        "FgJjYTEWMBQGA1UEAxMNQ29uZmlndXJhdGlvbjERMA8GA1UEAxMIU2VydmljZXMx\n"+
        "HDAaBgNVBAMTE1B1YmxpYyBLZXkgU2VydmljZXMxDDAKBgNVBAMTA0FJQTEWMBQG\n"+
        "A1UEAxMNUG9zdGEgQ0EgUm9vdDAeFw0wODEwMjAxNDExMzBaFw0yODEwMTQyMjAw\n"+
        "MDBaMIGrMRIwEAYKCZImiZPyLGQBGRYCcnMxFTATBgoJkiaJk/IsZAEZFgVwb3N0\n"+
        "YTESMBAGCgmSJomT8ixkARkWAmNhMRYwFAYDVQQDEw1Db25maWd1cmF0aW9uMREw\n"+
        "DwYDVQQDEwhTZXJ2aWNlczEcMBoGA1UEAxMTUHVibGljIEtleSBTZXJ2aWNlczEM\n"+
        "MAoGA1UEAxMDQUlBMRMwEQYDVQQDEwpQb3N0YSBDQSAxMIIBIjANBgkqhkiG9w0B\n"+
        "AQEFAAOCAQ8AMIIBCgKCAQEAl5msW5MdLW/2aDlezrjU3jW58MKrcMPHs2szlGdL\n"+
        "nsAcSyYFF1JbyA8iuqLp7mhvcTz9m4jK82XBz/1mPq8wJMU9ekGnLhgbKLGKXRBA\n"+
        "sY9wzCvwpweQV6ui4vr2eOkS1j9Mk7ikatH8tNiIzkNrTj3npDpZv1w4G37iwtpb\n"+
        "yjg+lkNIDY2nWV9roBsAZM8Lvbyi4vxP41YEQZ3hxaGGG0/RKHbugvGatgckxfin\n"+
        "4gpFG2mDhS9uafGgqnLHLwpxgBbi3g6+2TsxOKatTxwxx9/4MND1GjhxKTjDNYPl\n"+
        "5JHUvr9fcvQMxP21/jbO4EsCWG+F38R90kT37hFL3l1qiQIDAQABo4IDDzCCAwsw\n"+
        "DwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAQYwgcwGA1UdIASBxDCBwTCB\n"+
        "vgYLKwYBBAH6OAoyAQEwga4wMAYIKwYBBQUHAgEWJGh0dHA6Ly93d3cuY2EucG9z\n"+
        "dGEucnMvZG9rdW1lbnRhY2lqYTB6BggrBgEFBQcCAjBuGmxPdm8gamUgZWxla3Ry\n"+
        "b25za2kgc2VydGlmaWthdCBpemRhdmFja29nIChwcm9kdWtjaW9ub2cpIENBIHNl\n"+
        "cnZlcmEgU2VydGlmaWthY2lvbm9nIHRlbGEgUG9zdGU6ICJQb3N0YSBDQSAxIi4w\n"+
        "ggG8BgNVHR8EggGzMIIBrzCByaCBxqCBw6SBwDCBvTESMBAGCgmSJomT8ixkARkW\n"+
        "AnJzMRUwEwYKCZImiZPyLGQBGRYFcG9zdGExEjAQBgoJkiaJk/IsZAEZFgJjYTEW\n"+
        "MBQGA1UEAxMNQ29uZmlndXJhdGlvbjERMA8GA1UEAxMIU2VydmljZXMxHDAaBgNV\n"+
        "BAMTE1B1YmxpYyBLZXkgU2VydmljZXMxDDAKBgNVBAMTA0FJQTEWMBQGA1UEAxMN\n"+
        "UG9zdGEgQ0EgUm9vdDENMAsGA1UEAxMEQ1JMMTCB4KCB3aCB2oaBo2xkYXA6Ly9s\n"+
        "ZGFwLmNhLnBvc3RhLnJzL2NuPVBvc3RhJTIwQ0ElMjBSb290LGNuPUFJQSxjbj1Q\n"+
        "dWJsaWMlMjBLZXklMjBTZXJ2aWNlcyxjbj1TZXJ2aWNlcyxjbj1Db25maWd1cmF0\n"+
        "aW9uLGRjPWNhLGRjPXBvc3RhLGRjPXJzP2NlcnRpZmljYXRlUmV2b2NhdGlvbkxp\n"+
        "c3QlM0JiaW5hcnmGMmh0dHA6Ly9zZXJ0aWZpa2F0aS5jYS5wb3N0YS5ycy9jcmwv\n"+
        "UG9zdGFDQVJvb3QuY3JsMB8GA1UdIwQYMBaAFPLLjeI17xBDxNp7yvrriQOhIq+4\n"+
        "MB0GA1UdDgQWBBQuZ6cm1uhncOeq+pAsMLzXYWUfhjAZBgkqhkiG9n0HQQAEDDAK\n"+
        "GwRWNy4xAwIAgTANBgkqhkiG9w0BAQUFAAOCAQEAjpmoaebsvfjgwgCYArou/s8k\n"+
        "Tr50TUdcJYxAYmCFQp531E1F+qUCWM/7bZApqByR3+EUz8goI5O2Cp/6ISxTR1HC\n"+
        "Dn71ESg7/c8Bs2Obx0LGYPnlRPvw7LH31dYXpj4EMNAamhOfBXgY2htXHCd7daIe\n"+
        "thvNkqWGDzmcoaGw/2BMNadlYkdXxudDBaiPDFm27yR7fPRibjxwkQVknzFezX/y\n"+
        "46j+20LoGJ/IpneT209XzytiaqtZBy3yqz2qImVDqvn5doHw63LOUqt8vfDS1sbd\n"+
        "zi3acAmPK1nERdCMJYJEEGNiGbkbw2cghwLw/4eYGXlj1VLXD3GU42uBr8QftA==\n"+
        "-----END CERTIFICATE-----\n";

    @LargeTest
    public void testSslCertificateWithMultipleCN() throws Exception {
        X509Certificate x509Certificate = generateCertificate(Issue41662Certificate);
        String dn = x509Certificate.getSubjectDN().getName();
        assertTrue(dn, dn.contains("Posta CA 1"));
        assertTrue(dn, dn.contains("Configuration"));
        SslCertificate sslCertificate = new SslCertificate(x509Certificate);
        assertEquals(dn, "Posta CA 1", sslCertificate.getIssuedTo().getCName());
    }

    private static X509Certificate generateCertificate(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pem.getBytes()));
    }

}
