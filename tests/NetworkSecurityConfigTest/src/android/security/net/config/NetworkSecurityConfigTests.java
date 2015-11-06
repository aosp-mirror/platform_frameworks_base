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
import android.test.ActivityUnitTestCase;
import android.util.ArraySet;
import android.util.Pair;
import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;

public class NetworkSecurityConfigTests extends ActivityUnitTestCase<Activity> {

    public NetworkSecurityConfigTests() {
        super(Activity.class);
    }

    // SHA-256 of the G2 intermediate CA for android.com (as of 10/2015).
    private static final byte[] G2_SPKI_SHA256
            = hexToBytes("ec722969cb64200ab6638f68ac538e40abab5b19a6485661042a1061c4612776");

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(
                    s.charAt(i + 1), 16));
        }
        return data;
    }

    private void assertConnectionFails(SSLContext context, String host, int port)
            throws Exception {
        try {
            Socket s = context.getSocketFactory().createSocket(host, port);
            s.getInputStream();
            fail("Expected connection to " + host + ":" + port + " to fail.");
        } catch (SSLHandshakeException expected) {
        }
    }

    private void assertConnectionSucceeds(SSLContext context, String host, int port)
            throws Exception {
        Socket s = context.getSocketFactory().createSocket(host, port);
        s.getInputStream();
    }

    private void assertUrlConnectionFails(SSLContext context, String host, int port)
            throws Exception {
        URL url = new URL("https://" + host + ":" + port);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setSSLSocketFactory(context.getSocketFactory());
        try {
            connection.getInputStream();
            fail("Connection to " + host + ":" + port + " expected to fail");
        } catch (SSLHandshakeException expected) {
            // ignored.
        }
    }

    private void assertUrlConnectionSucceeds(SSLContext context, String host, int port)
            throws Exception {
        URL url = new URL("https://" + host + ":" + port);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.getInputStream();
    }

    private SSLContext getSSLContext(ConfigSource source) throws Exception {
        ApplicationConfig config = new ApplicationConfig(source);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {config.getTrustManager()}, null);
        return context;
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
        SSLContext context = getSSLContext(testSource);
        assertConnectionFails(context, "android.com", 443);
    }

    public void testEmptyPerNetworkSecurityConfig() throws Exception {
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), getEmptyConfig()));
        NetworkSecurityConfig defaultConfig = getSystemStoreConfig();
        SSLContext context = getSSLContext(new TestConfigSource(domainMap, defaultConfig));
        assertConnectionFails(context, "android.com", 443);
        assertConnectionSucceeds(context, "google.com", 443);
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
                = getSSLContext(new TestConfigSource(domainMap, getSystemStoreConfig()));
        assertConnectionFails(context, "android.com", 443);
        assertConnectionSucceeds(context, "google.com", 443);
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
                = getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        assertConnectionSucceeds(context, "android.com", 443);
        assertConnectionSucceeds(context, "developer.android.com", 443);
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
                = getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        assertConnectionSucceeds(context, "android.com", 443);
    }

    public void testMostSpecificNetworkSecurityConfig() throws Exception {
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), getEmptyConfig()));
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("developer.android.com", false), getSystemStoreConfig()));
        SSLContext context
                = getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        assertConnectionFails(context, "android.com", 443);
        assertConnectionSucceeds(context, "developer.android.com", 443);
    }

    public void testSubdomainIncluded() throws Exception {
        // First try connecting to a subdomain of a domain entry that includes subdomains.
        ArraySet<Pair<Domain, NetworkSecurityConfig>> domainMap
                = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", true), getSystemStoreConfig()));
        SSLContext context
                = getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        assertConnectionSucceeds(context, "developer.android.com", 443);
        // Now try without including subdomains.
        domainMap = new ArraySet<Pair<Domain, NetworkSecurityConfig>>();
        domainMap.add(new Pair<Domain, NetworkSecurityConfig>(
                new Domain("android.com", false), getSystemStoreConfig()));
        context = getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        assertConnectionFails(context, "developer.android.com", 443);
    }

    public void testConfigBuilderUsesParents() throws Exception {
        // Check that a builder with a parent uses the parent's values when non is set.
        NetworkSecurityConfig config = new NetworkSecurityConfig.Builder()
                .setParent(NetworkSecurityConfig.getDefaultBuilder())
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
                = getSSLContext(new TestConfigSource(domainMap, getEmptyConfig()));
        assertUrlConnectionSucceeds(context, "android.com", 443);
        assertUrlConnectionSucceeds(context, "developer.android.com", 443);
        assertUrlConnectionFails(context, "google.com", 443);
    }
}
