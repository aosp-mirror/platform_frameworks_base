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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.util.ArraySet;
import android.util.Pair;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

public class XmlConfigTests extends AndroidTestCase {

    private final static String DEBUG_CA_SUBJ = "O=AOSP, CN=Test debug CA";

    public void testEmptyConfigFile() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.empty_config,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertFalse(appConfig.hasPerDomainConfigs());
        NetworkSecurityConfig config = appConfig.getConfigForHostname("");
        assertNotNull(config);
        // Check defaults.
        assertTrue(config.isCleartextTrafficPermitted());
        assertFalse(config.isHstsEnforced());
        assertFalse(config.getTrustAnchors().isEmpty());
        PinSet pinSet = config.getPins();
        assertTrue(pinSet.pins.isEmpty());
        // Try some connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "google.com", 443);
    }

    public void testEmptyAnchors() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.empty_trust,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertFalse(appConfig.hasPerDomainConfigs());
        NetworkSecurityConfig config = appConfig.getConfigForHostname("");
        assertNotNull(config);
        // Check defaults.
        assertTrue(config.isCleartextTrafficPermitted());
        assertFalse(config.isHstsEnforced());
        assertTrue(config.getTrustAnchors().isEmpty());
        PinSet pinSet = config.getPins();
        assertTrue(pinSet.pins.isEmpty());
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionFails(context, "android.com", 443);
        TestUtils.assertConnectionFails(context, "developer.android.com", 443);
        TestUtils.assertUrlConnectionFails(context, "google.com", 443);
    }

    public void testBasicDomainConfig() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.domain1,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        NetworkSecurityConfig config = appConfig.getConfigForHostname("");
        assertNotNull(config);
        // Check defaults.
        assertTrue(config.isCleartextTrafficPermitted());
        assertFalse(config.isHstsEnforced());
        assertTrue(config.getTrustAnchors().isEmpty());
        PinSet pinSet = config.getPins();
        assertTrue(pinSet.pins.isEmpty());
        // Check android.com.
        config = appConfig.getConfigForHostname("android.com");
        assertTrue(config.isCleartextTrafficPermitted());
        assertFalse(config.isHstsEnforced());
        assertFalse(config.getTrustAnchors().isEmpty());
        pinSet = config.getPins();
        assertTrue(pinSet.pins.isEmpty());
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionFails(context, "developer.android.com", 443);
        TestUtils.assertUrlConnectionFails(context, "google.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
        // Check that sockets created without the hostname fail with per-domain configs
        SSLSocket socket = (SSLSocket) context.getSocketFactory()
                .createSocket(InetAddress.getByName("android.com"), 443);
        try {
        socket.startHandshake();
        socket.getInputStream();
        fail();
        } catch (IOException expected) {
        }
    }

    public void testBasicPinning() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.pins1,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        // Check android.com.
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        PinSet pinSet = config.getPins();
        assertFalse(pinSet.pins.isEmpty());
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "google.com", 443);
    }

    public void testExpiredPin() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.expired_pin,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        // Check android.com.
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        PinSet pinSet = config.getPins();
        assertFalse(pinSet.pins.isEmpty());
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
    }

    public void testOverridesPins() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.override_pins,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        // Check android.com.
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        PinSet pinSet = config.getPins();
        assertFalse(pinSet.pins.isEmpty());
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
    }

    public void testBadPin() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.bad_pin,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        // Check android.com.
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        PinSet pinSet = config.getPins();
        assertFalse(pinSet.pins.isEmpty());
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionFails(context, "android.com", 443);
        TestUtils.assertUrlConnectionFails(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "google.com", 443);
    }

    public void testMultipleDomains() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.multiple_domains,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        assertTrue(config.isCleartextTrafficPermitted());
        assertFalse(config.isHstsEnforced());
        assertFalse(config.getTrustAnchors().isEmpty());
        PinSet pinSet = config.getPins();
        assertTrue(pinSet.pins.isEmpty());
        // Both android.com and google.com should use the same config
        NetworkSecurityConfig other = appConfig.getConfigForHostname("google.com");
        assertEquals(config, other);
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "google.com", 443);
        TestUtils.assertConnectionFails(context, "developer.android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
    }

    public void testMultipleDomainConfigs() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.multiple_configs,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        // Should be two different config objects
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        NetworkSecurityConfig other = appConfig.getConfigForHostname("google.com");
        MoreAsserts.assertNotEqual(config, other);
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "google.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
    }

    public void testIncludeSubdomains() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.subdomains,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "developer.android.com", 443);
        TestUtils.assertConnectionFails(context, "google.com", 443);
    }

    public void testAttributes() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.attributes,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertFalse(appConfig.hasPerDomainConfigs());
        NetworkSecurityConfig config = appConfig.getConfigForHostname("");
        assertTrue(config.isHstsEnforced());
        assertFalse(config.isCleartextTrafficPermitted());
    }

    public void testResourcePemCertificateSource() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.resource_anchors_pem,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        // Check android.com.
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        assertTrue(config.isCleartextTrafficPermitted());
        assertFalse(config.isHstsEnforced());
        assertEquals(2, config.getTrustAnchors().size());
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionFails(context, "developer.android.com", 443);
        TestUtils.assertUrlConnectionFails(context, "google.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
    }

    public void testResourceDerCertificateSource() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.resource_anchors_der,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        // Check android.com.
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        assertTrue(config.isCleartextTrafficPermitted());
        assertFalse(config.isHstsEnforced());
        assertEquals(2, config.getTrustAnchors().size());
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionFails(context, "developer.android.com", 443);
        TestUtils.assertUrlConnectionFails(context, "google.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
    }

    public void testNestedDomainConfigs() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.nested_domains,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        NetworkSecurityConfig parent = appConfig.getConfigForHostname("android.com");
        NetworkSecurityConfig child = appConfig.getConfigForHostname("developer.android.com");
        MoreAsserts.assertNotEqual(parent, child);
        MoreAsserts.assertEmpty(parent.getPins().pins);
        MoreAsserts.assertNotEmpty(child.getPins().pins);
        // Check that the child inherited the cleartext value and anchors.
        assertFalse(child.isCleartextTrafficPermitted());
        MoreAsserts.assertNotEmpty(child.getTrustAnchors());
        // Test connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
    }

    public void testNestedDomainConfigsOverride() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.nested_domains_override,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        NetworkSecurityConfig parent = appConfig.getConfigForHostname("android.com");
        NetworkSecurityConfig child = appConfig.getConfigForHostname("developer.android.com");
        MoreAsserts.assertNotEqual(parent, child);
        assertTrue(parent.isCleartextTrafficPermitted());
        assertFalse(child.isCleartextTrafficPermitted());
    }

    public void testDebugOverridesDisabled() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.debug_basic,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        NetworkSecurityConfig config = appConfig.getConfigForHostname("");
        Set<TrustAnchor> anchors = config.getTrustAnchors();
        MoreAsserts.assertEmpty(anchors);
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionFails(context, "android.com", 443);
        TestUtils.assertConnectionFails(context, "developer.android.com", 443);
    }

    public void testBasicDebugOverrides() throws Exception {
        ApplicationInfo info = TestUtils.makeApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.debug_basic, info);
        ApplicationConfig appConfig = new ApplicationConfig(source);
        NetworkSecurityConfig config = appConfig.getConfigForHostname("");
        Set<TrustAnchor> anchors = config.getTrustAnchors();
        MoreAsserts.assertNotEmpty(anchors);
        for (TrustAnchor anchor : anchors) {
            assertTrue(anchor.overridesPins);
        }
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
    }

    public void testDebugOverridesWithDomain() throws Exception {
        ApplicationInfo info = TestUtils.makeApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.debug_domain, info);
        ApplicationConfig appConfig = new ApplicationConfig(source);
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        Set<TrustAnchor> anchors = config.getTrustAnchors();
        boolean foundDebugCA = false;
        for (TrustAnchor anchor : anchors) {
            if (anchor.certificate.getSubjectDN().toString().equals(DEBUG_CA_SUBJ)) {
                foundDebugCA = true;
                assertTrue(anchor.overridesPins);
            }
        }
        assertTrue(foundDebugCA);
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
    }

    public void testDebugInherit() throws Exception {
        ApplicationInfo info = TestUtils.makeApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.debug_domain, info);
        ApplicationConfig appConfig = new ApplicationConfig(source);
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        Set<TrustAnchor> anchors = config.getTrustAnchors();
        boolean foundDebugCA = false;
        for (TrustAnchor anchor : anchors) {
            if (anchor.certificate.getSubjectDN().toString().equals(DEBUG_CA_SUBJ)) {
                foundDebugCA = true;
                assertTrue(anchor.overridesPins);
            }
        }
        assertTrue(foundDebugCA);
        assertTrue(anchors.size() > 1);
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
    }

    private void testBadConfig(int configId) throws Exception {
        try {
            XmlConfigSource source = new XmlConfigSource(getContext(), configId,
                    TestUtils.makeApplicationInfo());
            ApplicationConfig appConfig = new ApplicationConfig(source);
            appConfig.getConfigForHostname("android.com");
            fail("Bad config " + getContext().getResources().getResourceName(configId)
                    + " did not fail to parse");
        } catch (RuntimeException e) {
            MoreAsserts.assertAssignableFrom(XmlConfigSource.ParserException.class,
                    e.getCause());
        }
    }

    public void testBadConfig0() throws Exception {
        testBadConfig(R.xml.bad_config0);
    }

    public void testBadConfig1() throws Exception {
        testBadConfig(R.xml.bad_config1);
    }

    public void testBadConfig2() throws Exception {
        testBadConfig(R.xml.bad_config2);
    }

    public void testBadConfig3() throws Exception {
        testBadConfig(R.xml.bad_config3);
    }

    public void testBadConfig4() throws Exception {
        testBadConfig(R.xml.bad_config4);
    }

    public void testBadConfig5() throws Exception {
        testBadConfig(R.xml.bad_config4);
    }

    public void testTrustManagerKeystore() throws Exception {
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.bad_pin,
                TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        Provider provider = new NetworkSecurityConfigProvider();
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance("PKIX", provider);
        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        keystore.load(null);
        int i = 0;
        for (X509Certificate cert : SystemCertificateSource.getInstance().getCertificates()) {
            keystore.setEntry(String.valueOf(i),
                    new KeyStore.TrustedCertificateEntry(cert),
                    null);
            i++;
        }
        tmf.init(keystore);
        TrustManager[] tms = tmf.getTrustManagers();
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tms, null);
        TestUtils.assertConnectionSucceeds(context, "android.com" , 443);
    }

    public void testDebugDedup() throws Exception {
        ApplicationInfo info = TestUtils.makeApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        XmlConfigSource source = new XmlConfigSource(getContext(), R.xml.override_dedup, info);
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertTrue(appConfig.hasPerDomainConfigs());
        // Check android.com.
        NetworkSecurityConfig config = appConfig.getConfigForHostname("android.com");
        PinSet pinSet = config.getPins();
        assertFalse(pinSet.pins.isEmpty());
        // Check that all TrustAnchors come from the override pins debug source.
        for (TrustAnchor anchor : config.getTrustAnchors()) {
            assertTrue(anchor.overridesPins);
        }
        // Try connections.
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertUrlConnectionSucceeds(context, "android.com", 443);
    }

    public void testExtraDebugResource() throws Exception {
        ApplicationInfo info = TestUtils.makeApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        XmlConfigSource source =
                new XmlConfigSource(getContext(), R.xml.extra_debug_resource, info);
        ApplicationConfig appConfig = new ApplicationConfig(source);
        assertFalse(appConfig.hasPerDomainConfigs());
        NetworkSecurityConfig config = appConfig.getConfigForHostname("");
        MoreAsserts.assertNotEmpty(config.getTrustAnchors());

        // Check that the _debug file is ignored if debug is false.
        source = new XmlConfigSource(getContext(), R.xml.extra_debug_resource,
                TestUtils.makeApplicationInfo());
        appConfig = new ApplicationConfig(source);
        assertFalse(appConfig.hasPerDomainConfigs());
        config = appConfig.getConfigForHostname("");
        MoreAsserts.assertEmpty(config.getTrustAnchors());
    }

    public void testExtraDebugResourceIgnored() throws Exception {
        // Verify that parsing the extra debug config resource fails only when debugging is true.
        XmlConfigSource source =
                new XmlConfigSource(getContext(), R.xml.bad_extra_debug_resource,
                        TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        // Force parsing the config file.
        appConfig.getConfigForHostname("");

        ApplicationInfo info = TestUtils.makeApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        source = new XmlConfigSource(getContext(), R.xml.bad_extra_debug_resource, info);
        appConfig = new ApplicationConfig(source);
        try {
            appConfig.getConfigForHostname("");
            fail("Bad extra debug resource did not fail to parse");
        } catch (RuntimeException expected) {
        }
    }

    public void testDomainWhitespaceTrimming() throws Exception {
        XmlConfigSource source =
                new XmlConfigSource(getContext(), R.xml.domain_whitespace,
                        TestUtils.makeApplicationInfo());
        ApplicationConfig appConfig = new ApplicationConfig(source);
        NetworkSecurityConfig defaultConfig = appConfig.getConfigForHostname("");
        MoreAsserts.assertNotEqual(defaultConfig, appConfig.getConfigForHostname("developer.android.com"));
        MoreAsserts.assertNotEqual(defaultConfig, appConfig.getConfigForHostname("android.com"));
        SSLContext context = TestUtils.getSSLContext(source);
        TestUtils.assertConnectionSucceeds(context, "android.com", 443);
        TestUtils.assertConnectionSucceeds(context, "developer.android.com", 443);
    }
}
