/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.test.ActivityUnitTestCase;
import android.util.ArraySet;
import android.util.Pair;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;

import com.android.org.conscrypt.TrustedCertificateStore;

public class NetworkSecurityConfigTests extends ActivityUnitTestCase<Activity> {

    public NetworkSecurityConfigTests() {
        super(Activity.class);
    }

    // SHA-256 of the G2 intermediate CA for android.com (as of 10/2015).
    private static final byte[] G2_SPKI_SHA256
            = hexToBytes("ec722969cb64200ab6638f68ac538e40abab5b19a6485661042a1061c4612776");

    private static final byte[] TEST_CA_BYTES
            = hexToBytes(
                    "3082036130820249a003020102020900bd54597d6750ea62300d06092a86"
                    + "4886f70d01010b05003047310b3009060355040613025553310b30090603"
                    + "5504080c0243413110300e060355040a0c07416e64726f69643119301706"
                    + "035504030c104e53436f6e6669672054657374204341301e170d31363032"
                    + "32343030313130325a170d3136303332353030313130325a3047310b3009"
                    + "060355040613025553310b300906035504080c0243413110300e06035504"
                    + "0a0c07416e64726f69643119301706035504030c104e53436f6e66696720"
                    + "5465737420434130820122300d06092a864886f70d01010105000382010f"
                    + "003082010a0282010100e15ce8fd5794029841e760d68d6e0159c9c67630"
                    + "089775bc728d83dae7e29e23fe5f6e113b789f4c5b22f052300ec6d5faa5"
                    + "724432e7bac96682792ef6e9617c939c4329dce8788cbdf3a11b621fac9e"
                    + "2edbec2d7e5e07296bbb544b89263137a6a31573a2362e05ca8ff9c886bf"
                    + "52df4ff93c45475145a40a83f2670e23669220a5a4bf2c6860edb78d3022"
                    + "192fb5dc5e8c118f70870f89da292dfe522751462f020ed556653c8b07f8"
                    + "89712a6e8196c457a637439e3073d7d917ab55aa51a146826367f7b5922a"
                    + "64fb2f95099de21eb98341fa76faa79ffbda123fe5b8adc614b16174e8b0"
                    + "dfdac2bbc4d526d2487ad2b009d53996ec23ffbd732112efa66b02030100"
                    + "01a350304e301d0603551d0e04160414f66e1a95486c879edd60a5756bc2"
                    + "f1f4677e128e301f0603551d23041830168014f66e1a95486c879edd60a5"
                    + "756bc2f1f4677e128e300c0603551d13040530030101ff300d06092a8648"
                    + "86f70d01010b05000382010100d2856130dccae24e5f8901900d94bc642f"
                    + "85466ab7cfa1066399077a168cd4b56603a9e2af9d2e58aec13101e338a4"
                    + "8e95e9c7a84d7991f0d381d4965eaada1b80fbbd8277445f449babe64f53"
                    + "ba625387460b592a1a97b14b8251115e6610350021a6e716ae22b905f8d4"
                    + "eae24e668e71b12ab51fd2f2bb600e074487dec720c3db14dbca504844b6"
                    + "933bb0248283ea95464747689c37d706d4839c7d0e9bd86abf98ddce5d36"
                    + "8b38bfe5062353e28d5be378827fade1caa6bba3df9cd9ebf83d839eae52"
                    + "780181f31973f15f982686ba6d899f7b644fd1f26c8ebb99f4c986faaf4c"
                    + "1b9e3d9d391943ce3fb9fa2e631bd66b8ef3d47fd85acf09ea3a30f15f");

    private static final X509Certificate TEST_CA_CERT;

    static {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Certificate cert = factory.generateCertificate(new ByteArrayInputStream(TEST_CA_BYTES));
            TEST_CA_CERT = (X509Certificate) cert;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(
                    s.charAt(i + 1), 16));
        }
        return data;
    }


    /**
     * Return a NetworkSecurityConfig that has an empty TrustAnchor set. This should always cause a
     * SSLHandshakeException when used for a connection.
     */
    private NetworkSecurityConfig getEmptyConfig() {
        return new NetworkSecurityConfig.Builder().build();
    }

    private NetworkSecurityConfig getSystemStoreConfig() {
        return new NetworkSecurityConfig.Builder()
                .addCertificatesEntryRef(
                        new CertificatesEntryRef(SystemCertificateSource.getInstance(), false))
                .build();
    }

    public void testEmptyConfig() throws Exception {
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        ConfigSource testSource =
                new TestConfigSource(domainMap, getEmptyConfig());
        SSLContext context = TestUtils.getSSLContext(testSource);
        TestUtils.assertConnectionFails(context, "android.com", 443);
    }

    public void testEmptyPerNetworkSecurityConfig() throws Exception {
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), getEmptyConfig()));
        NetworkSecurityConfig defaultConfig = getSystemStoreConfig();
        SSLContext context = TestUtils.getSSLContext(new TestConfigSource(domainMap, defaultConfig));
        TestUtils.assertConnectionFails(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "google.com", 443);
    }

    public void testBadPin() throws Exception {
        ArraySet<Pin> pins = new ArraySet<Pin>();
        pins.add(new Pin("SHA-256", new byte[0]));
        NetworkSecurityConfig domain = new NetworkSecurityConfig.Builder()
                .setPinSet(new PinSet(pins, Long.MAX_VALUE))
                .addCertificatesEntryRef(
                        new CertificatesEntryRef(SystemCertificateSource.getInstance(), false))
                .build();
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), domain));
        SSLContext context
                = TestUtils.getSSLContext(new TestConfigSource(domainMap, getSystemStoreConfig()));
        TestUtils.assertConnectionFails(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "google.com", 443);
    }

    public void testGoodPin() throws Exception {
        ArraySet<Pin> pins = new ArraySet<Pin>();
        pins.add(new Pin("SHA-256", G2_SPKI_SHA256));
        NetworkSecurityConfig domain = new NetworkSecurityConfig.Builder()
                .setPinSet(new PinSet(pins, Long.MAX_VALUE))
                .addCertificatesEntryRef(
                        new CertificatesEntryRef(SystemCertificateSource.getInstance(), false))
                .build();
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), domain));
        SSLContext context
                = TestUtils.getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
    }

    public void testOverridePins() throws Exception {
        // Use a bad pin + granting the system CA store the ability to override pins.
        ArraySet<Pin> pins = new ArraySet<Pin>();
        pins.add(new Pin("SHA-256", new byte[0]));
        NetworkSecurityConfig domain = new NetworkSecurityConfig.Builder()
                .setPinSet(new PinSet(pins, Long.MAX_VALUE))
                .addCertificatesEntryRef(
                        new CertificatesEntryRef(SystemCertificateSource.getInstance(), true))
                .build();
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), domain));
        SSLContext context
                = TestUtils.getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
    }

    public void testMostSpecificNetworkSecurityConfig() throws Exception {
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), getEmptyConfig()));
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("developer.android.com", false), getSystemStoreConfig()));
        SSLContext context
                = TestUtils.getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        TestUtils.assertConnectionFails(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
    }

    public void testSubdomainIncluded() throws Exception {
        // First try connecting to a subdomain of a domain entry that includes subdomains.
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), getSystemStoreConfig()));
        SSLContext context
                = TestUtils.getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
        // Now try without including subdomains.
        domainMap = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", false), getSystemStoreConfig()));
        context = TestUtils.getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        TestUtils.assertConnectionFails(context, "developer.android.com", 443);
    }

    public void testConfigBuilderUsesParents() throws Exception {
        // Check that a builder with a parent uses the parent's values when non is set.
        NetworkSecurityConfig config = new NetworkSecurityConfig.Builder()
                .setParent(NetworkSecurityConfig
                        .getDefaultBuilder(TestUtils.makeApplicationInfo()))
                .build();
        assert(!config.getTrustAnchors().isEmpty());
    }

    public void testConfigBuilderParentLoop() throws Exception {
        NetworkSecurityConfig.Builder config1 = new NetworkSecurityConfig.Builder();
        NetworkSecurityConfig.Builder config2 = new NetworkSecurityConfig.Builder();
        config1.setParent(config2);
        try {
            config2.setParent(config1);
            fail("Loop in NetworkSecurityConfig parents");
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testWithUrlConnection() throws Exception {
        ArraySet<Pin> pins = new ArraySet<Pin>();
        pins.add(new Pin("SHA-256", G2_SPKI_SHA256));
        NetworkSecurityConfig domain = new NetworkSecurityConfig.Builder()
                .setPinSet(new PinSet(pins, Long.MAX_VALUE))
                .addCertificatesEntryRef(
                        new CertificatesEntryRef(SystemCertificateSource.getInstance(), false))
                .build();
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), domain));
        SSLContext context
                = TestUtils.getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "developer.android.com", 443);
        TestUtils.assertUrlConnectionFails(context, "google.com", 443);
    }

    public void testUserAddedCaOptIn() throws Exception {
        TrustedCertificateStore store = new TrustedCertificateStore();
        try {
            // Install the test CA.
            store.installCertificate(TEST_CA_CERT);
            NetworkSecurityConfig preNConfig =
                    NetworkSecurityConfig
                    .getDefaultBuilder(TestUtils.makeApplicationInfo(Build.VERSION_CODES.M))
                    .build();
            NetworkSecurityConfig nConfig =
                    NetworkSecurityConfig
                    .getDefaultBuilder(TestUtils.makeApplicationInfo(Build.VERSION_CODES.N))
                    .build();
            ApplicationInfo privInfo = TestUtils.makeApplicationInfo(Build.VERSION_CODES.M);
            privInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
            NetworkSecurityConfig privConfig =
                    NetworkSecurityConfig
                    .getDefaultBuilder(privInfo)
                    .build();
            Set<TrustAnchor> preNAnchors = preNConfig.getTrustAnchors();
            Set<TrustAnchor> nAnchors = nConfig.getTrustAnchors();
            Set<TrustAnchor> privAnchors = privConfig.getTrustAnchors();
            Set<X509Certificate> preNCerts = new HashSet<X509Certificate>();
            for (TrustAnchor anchor : preNAnchors) {
                preNCerts.add(anchor.certificate);
            }
            Set<X509Certificate> nCerts = new HashSet<X509Certificate>();
            for (TrustAnchor anchor : nAnchors) {
                nCerts.add(anchor.certificate);
            }
            Set<X509Certificate> privCerts = new HashSet<X509Certificate>();
            for (TrustAnchor anchor : privAnchors) {
                privCerts.add(anchor.certificate);
            }
            assertTrue(preNCerts.contains(TEST_CA_CERT));
            assertFalse(nCerts.contains(TEST_CA_CERT));
            assertFalse(privCerts.contains(TEST_CA_CERT));
        } finally {
            // Delete the user added CA. We don't know the alias so just delete them all.
            for (String alias : store.aliases()) {
                if (store.isUser(alias)) {
                    try {
                        store.deleteCertificateEntry(alias);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}
