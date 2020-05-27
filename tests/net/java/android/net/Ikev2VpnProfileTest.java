/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.test.mock.MockContext;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.net.VpnProfile;
import com.android.org.bouncycastle.x509.X509V1CertificateGenerator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.security.auth.x500.X500Principal;

/** Unit tests for {@link Ikev2VpnProfile.Builder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class Ikev2VpnProfileTest {
    private static final String SERVER_ADDR_STRING = "1.2.3.4";
    private static final String IDENTITY_STRING = "Identity";
    private static final String USERNAME_STRING = "username";
    private static final String PASSWORD_STRING = "pa55w0rd";
    private static final String EXCL_LIST = "exclList";
    private static final byte[] PSK_BYTES = "preSharedKey".getBytes();
    private static final int TEST_MTU = 1300;

    private final MockContext mMockContext =
            new MockContext() {
                @Override
                public String getOpPackageName() {
                    return "fooPackage";
                }
            };
    private final ProxyInfo mProxy = new ProxyInfo(SERVER_ADDR_STRING, -1, EXCL_LIST);

    private X509Certificate mUserCert;
    private X509Certificate mServerRootCa;
    private PrivateKey mPrivateKey;

    @Before
    public void setUp() throws Exception {
        mServerRootCa = generateRandomCertAndKeyPair().cert;

        final CertificateAndKey userCertKey = generateRandomCertAndKeyPair();
        mUserCert = userCertKey.cert;
        mPrivateKey = userCertKey.key;
    }

    private Ikev2VpnProfile.Builder getBuilderWithDefaultOptions() {
        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(SERVER_ADDR_STRING, IDENTITY_STRING);

        builder.setBypassable(true);
        builder.setProxy(mProxy);
        builder.setMaxMtu(TEST_MTU);
        builder.setMetered(true);

        return builder;
    }

    @Test
    public void testBuildValidProfileWithOptions() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        final Ikev2VpnProfile profile = builder.build();
        assertNotNull(profile);

        // Check non-auth parameters correctly stored
        assertEquals(SERVER_ADDR_STRING, profile.getServerAddr());
        assertEquals(IDENTITY_STRING, profile.getUserIdentity());
        assertEquals(mProxy, profile.getProxyInfo());
        assertTrue(profile.isBypassable());
        assertTrue(profile.isMetered());
        assertEquals(TEST_MTU, profile.getMaxMtu());
        assertEquals(Ikev2VpnProfile.DEFAULT_ALGORITHMS, profile.getAllowedAlgorithms());
    }

    @Test
    public void testBuildUsernamePasswordProfile() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        final Ikev2VpnProfile profile = builder.build();
        assertNotNull(profile);

        assertEquals(USERNAME_STRING, profile.getUsername());
        assertEquals(PASSWORD_STRING, profile.getPassword());
        assertEquals(mServerRootCa, profile.getServerRootCaCert());

        assertNull(profile.getPresharedKey());
        assertNull(profile.getRsaPrivateKey());
        assertNull(profile.getUserCert());
    }

    @Test
    public void testBuildDigitalSignatureProfile() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthDigitalSignature(mUserCert, mPrivateKey, mServerRootCa);
        final Ikev2VpnProfile profile = builder.build();
        assertNotNull(profile);

        assertEquals(profile.getUserCert(), mUserCert);
        assertEquals(mPrivateKey, profile.getRsaPrivateKey());
        assertEquals(profile.getServerRootCaCert(), mServerRootCa);

        assertNull(profile.getPresharedKey());
        assertNull(profile.getUsername());
        assertNull(profile.getPassword());
    }

    @Test
    public void testBuildPresharedKeyProfile() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthPsk(PSK_BYTES);
        final Ikev2VpnProfile profile = builder.build();
        assertNotNull(profile);

        assertArrayEquals(PSK_BYTES, profile.getPresharedKey());

        assertNull(profile.getServerRootCaCert());
        assertNull(profile.getUsername());
        assertNull(profile.getPassword());
        assertNull(profile.getRsaPrivateKey());
        assertNull(profile.getUserCert());
    }

    @Test
    public void testBuildWithAllowedAlgorithmsAead() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();
        builder.setAuthPsk(PSK_BYTES);

        List<String> allowedAlgorithms = Arrays.asList(IpSecAlgorithm.AUTH_CRYPT_AES_GCM);
        builder.setAllowedAlgorithms(allowedAlgorithms);

        final Ikev2VpnProfile profile = builder.build();
        assertEquals(allowedAlgorithms, profile.getAllowedAlgorithms());
    }

    @Test
    public void testBuildWithAllowedAlgorithmsNormal() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();
        builder.setAuthPsk(PSK_BYTES);

        List<String> allowedAlgorithms =
                Arrays.asList(IpSecAlgorithm.AUTH_HMAC_SHA512, IpSecAlgorithm.CRYPT_AES_CBC);
        builder.setAllowedAlgorithms(allowedAlgorithms);

        final Ikev2VpnProfile profile = builder.build();
        assertEquals(allowedAlgorithms, profile.getAllowedAlgorithms());
    }

    @Test
    public void testSetAllowedAlgorithmsEmptyList() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        try {
            builder.setAllowedAlgorithms(new ArrayList<>());
            fail("Expected exception due to no valid algorithm set");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testSetAllowedAlgorithmsInvalidList() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();
        List<String> allowedAlgorithms = new ArrayList<>();

        try {
            builder.setAllowedAlgorithms(Arrays.asList(IpSecAlgorithm.AUTH_HMAC_SHA256));
            fail("Expected exception due to missing encryption");
        } catch (IllegalArgumentException expected) {
        }

        try {
            builder.setAllowedAlgorithms(Arrays.asList(IpSecAlgorithm.CRYPT_AES_CBC));
            fail("Expected exception due to missing authentication");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testSetAllowedAlgorithmsInsecureAlgorithm() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();
        List<String> allowedAlgorithms = new ArrayList<>();

        try {
            builder.setAllowedAlgorithms(Arrays.asList(IpSecAlgorithm.AUTH_HMAC_MD5));
            fail("Expected exception due to insecure algorithm");
        } catch (IllegalArgumentException expected) {
        }

        try {
            builder.setAllowedAlgorithms(Arrays.asList(IpSecAlgorithm.AUTH_HMAC_SHA1));
            fail("Expected exception due to insecure algorithm");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testBuildNoAuthMethodSet() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        try {
            builder.build();
            fail("Expected exception due to lack of auth method");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testBuildInvalidMtu() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        try {
            builder.setMaxMtu(500);
            fail("Expected exception due to too-small MTU");
        } catch (IllegalArgumentException expected) {
        }
    }

    private void verifyVpnProfileCommon(VpnProfile profile) {
        assertEquals(SERVER_ADDR_STRING, profile.server);
        assertEquals(IDENTITY_STRING, profile.ipsecIdentifier);
        assertEquals(mProxy, profile.proxy);
        assertTrue(profile.isBypassable);
        assertTrue(profile.isMetered);
        assertEquals(TEST_MTU, profile.maxMtu);
    }

    @Test
    public void testPskConvertToVpnProfile() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthPsk(PSK_BYTES);
        final VpnProfile profile = builder.build().toVpnProfile();

        verifyVpnProfileCommon(profile);
        assertEquals(Ikev2VpnProfile.encodeForIpsecSecret(PSK_BYTES), profile.ipsecSecret);

        // Check nothing else is set
        assertEquals("", profile.username);
        assertEquals("", profile.password);
        assertEquals("", profile.ipsecUserCert);
        assertEquals("", profile.ipsecCaCert);
    }

    @Test
    public void testUsernamePasswordConvertToVpnProfile() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        final VpnProfile profile = builder.build().toVpnProfile();

        verifyVpnProfileCommon(profile);
        assertEquals(USERNAME_STRING, profile.username);
        assertEquals(PASSWORD_STRING, profile.password);
        assertEquals(Ikev2VpnProfile.certificateToPemString(mServerRootCa), profile.ipsecCaCert);

        // Check nothing else is set
        assertEquals("", profile.ipsecUserCert);
        assertEquals("", profile.ipsecSecret);
    }

    @Test
    public void testRsaConvertToVpnProfile() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthDigitalSignature(mUserCert, mPrivateKey, mServerRootCa);
        final VpnProfile profile = builder.build().toVpnProfile();

        final String expectedSecret = Ikev2VpnProfile.PREFIX_INLINE
                + Ikev2VpnProfile.encodeForIpsecSecret(mPrivateKey.getEncoded());
        verifyVpnProfileCommon(profile);
        assertEquals(Ikev2VpnProfile.certificateToPemString(mUserCert), profile.ipsecUserCert);
        assertEquals(
                expectedSecret,
                profile.ipsecSecret);
        assertEquals(Ikev2VpnProfile.certificateToPemString(mServerRootCa), profile.ipsecCaCert);

        // Check nothing else is set
        assertEquals("", profile.username);
        assertEquals("", profile.password);
    }

    @Test
    public void testPskFromVpnProfileDiscardsIrrelevantValues() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthPsk(PSK_BYTES);
        final VpnProfile profile = builder.build().toVpnProfile();
        profile.username = USERNAME_STRING;
        profile.password = PASSWORD_STRING;
        profile.ipsecCaCert = Ikev2VpnProfile.certificateToPemString(mServerRootCa);
        profile.ipsecUserCert = Ikev2VpnProfile.certificateToPemString(mUserCert);

        final Ikev2VpnProfile result = Ikev2VpnProfile.fromVpnProfile(profile);
        assertNull(result.getUsername());
        assertNull(result.getPassword());
        assertNull(result.getUserCert());
        assertNull(result.getRsaPrivateKey());
        assertNull(result.getServerRootCaCert());
    }

    @Test
    public void testUsernamePasswordFromVpnProfileDiscardsIrrelevantValues() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        final VpnProfile profile = builder.build().toVpnProfile();
        profile.ipsecSecret = new String(PSK_BYTES);
        profile.ipsecUserCert = Ikev2VpnProfile.certificateToPemString(mUserCert);

        final Ikev2VpnProfile result = Ikev2VpnProfile.fromVpnProfile(profile);
        assertNull(result.getPresharedKey());
        assertNull(result.getUserCert());
        assertNull(result.getRsaPrivateKey());
    }

    @Test
    public void testRsaFromVpnProfileDiscardsIrrelevantValues() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthDigitalSignature(mUserCert, mPrivateKey, mServerRootCa);
        final VpnProfile profile = builder.build().toVpnProfile();
        profile.username = USERNAME_STRING;
        profile.password = PASSWORD_STRING;

        final Ikev2VpnProfile result = Ikev2VpnProfile.fromVpnProfile(profile);
        assertNull(result.getUsername());
        assertNull(result.getPassword());
        assertNull(result.getPresharedKey());
    }

    @Test
    public void testPskConversionIsLossless() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthPsk(PSK_BYTES);
        final Ikev2VpnProfile ikeProfile = builder.build();

        assertEquals(ikeProfile, Ikev2VpnProfile.fromVpnProfile(ikeProfile.toVpnProfile()));
    }

    @Test
    public void testUsernamePasswordConversionIsLossless() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthUsernamePassword(USERNAME_STRING, PASSWORD_STRING, mServerRootCa);
        final Ikev2VpnProfile ikeProfile = builder.build();

        assertEquals(ikeProfile, Ikev2VpnProfile.fromVpnProfile(ikeProfile.toVpnProfile()));
    }

    @Test
    public void testRsaConversionIsLossless() throws Exception {
        final Ikev2VpnProfile.Builder builder = getBuilderWithDefaultOptions();

        builder.setAuthDigitalSignature(mUserCert, mPrivateKey, mServerRootCa);
        final Ikev2VpnProfile ikeProfile = builder.build();

        assertEquals(ikeProfile, Ikev2VpnProfile.fromVpnProfile(ikeProfile.toVpnProfile()));
    }

    private static class CertificateAndKey {
        public final X509Certificate cert;
        public final PrivateKey key;

        CertificateAndKey(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }
    }

    private static CertificateAndKey generateRandomCertAndKeyPair() throws Exception {
        final Date validityBeginDate =
                new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1L));
        final Date validityEndDate =
                new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1L));

        // Generate a keypair
        final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(512);
        final KeyPair keyPair = keyPairGenerator.generateKeyPair();

        final X500Principal dnName = new X500Principal("CN=test.android.com");
        final X509V1CertificateGenerator certGen = new X509V1CertificateGenerator();
        certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()));
        certGen.setSubjectDN(dnName);
        certGen.setIssuerDN(dnName);
        certGen.setNotBefore(validityBeginDate);
        certGen.setNotAfter(validityEndDate);
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");

        final X509Certificate cert = certGen.generate(keyPair.getPrivate(), "AndroidOpenSSL");
        return new CertificateAndKey(cert, keyPair.getPrivate());
    }
}
