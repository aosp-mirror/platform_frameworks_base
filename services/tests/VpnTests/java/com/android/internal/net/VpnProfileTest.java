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

package com.android.internal.net;

import static android.net.cts.util.IkeSessionTestUtils.CHILD_PARAMS;
import static android.net.cts.util.IkeSessionTestUtils.IKE_PARAMS_V4;

import static com.android.testutils.ParcelUtils.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.IpSecAlgorithm;
import android.net.ipsec.ike.IkeTunnelConnectionParams;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Unit tests for {@link VpnProfile}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VpnProfileTest {
    private static final String DUMMY_PROFILE_KEY = "Test";

    private static final int ENCODED_INDEX_AUTH_PARAMS_INLINE = 23;
    private static final int ENCODED_INDEX_RESTRICTED_TO_TEST_NETWORKS = 24;
    private static final int ENCODED_INDEX_EXCLUDE_LOCAL_ROUTE = 25;
    private static final int ENCODED_INDEX_REQUIRE_PLATFORM_VALIDATION = 26;
    private static final int ENCODED_INDEX_IKE_TUN_CONN_PARAMS = 27;
    private static final int ENCODED_INDEX_AUTOMATIC_NATT_KEEPALIVE_TIMER_ENABLED = 28;
    private static final int ENCODED_INDEX_AUTOMATIC_IP_VERSION_SELECTION_ENABLED = 29;

    @Test
    public void testDefaults() throws Exception {
        final VpnProfile p = new VpnProfile(DUMMY_PROFILE_KEY);

        assertEquals(DUMMY_PROFILE_KEY, p.key);
        assertEquals("", p.name);
        assertEquals(VpnProfile.TYPE_PPTP, p.type);
        assertEquals("", p.server);
        assertEquals("", p.username);
        assertEquals("", p.password);
        assertEquals("", p.dnsServers);
        assertEquals("", p.searchDomains);
        assertEquals("", p.routes);
        assertTrue(p.mppe);
        assertEquals("", p.l2tpSecret);
        assertEquals("", p.ipsecIdentifier);
        assertEquals("", p.ipsecSecret);
        assertEquals("", p.ipsecUserCert);
        assertEquals("", p.ipsecCaCert);
        assertEquals("", p.ipsecServerCert);
        assertEquals(null, p.proxy);
        assertTrue(p.getAllowedAlgorithms() != null && p.getAllowedAlgorithms().isEmpty());
        assertFalse(p.isBypassable);
        assertFalse(p.isMetered);
        assertEquals(1360, p.maxMtu);
        assertFalse(p.areAuthParamsInline);
        assertFalse(p.isRestrictedToTestNetworks);
        assertFalse(p.excludeLocalRoutes);
        assertFalse(p.requiresInternetValidation);
        assertFalse(p.automaticNattKeepaliveTimerEnabled);
        assertFalse(p.automaticIpVersionSelectionEnabled);
    }

    private VpnProfile getSampleIkev2Profile(String key) {
        final VpnProfile p = new VpnProfile(key, true /* isRestrictedToTestNetworks */,
                false /* excludesLocalRoutes */, true /* requiresPlatformValidation */,
                null /* ikeTunConnParams */, true /* mAutomaticNattKeepaliveTimerEnabled */,
                true /* automaticIpVersionSelectionEnabled */);

        p.name = "foo";
        p.type = VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS;
        p.server = "bar";
        p.username = "baz";
        p.password = "qux";
        p.dnsServers = "8.8.8.8";
        p.searchDomains = "";
        p.routes = "0.0.0.0/0";
        p.mppe = false;
        p.l2tpSecret = "";
        p.ipsecIdentifier = "quux";
        p.ipsecSecret = "quuz";
        p.ipsecUserCert = "corge";
        p.ipsecCaCert = "grault";
        p.ipsecServerCert = "garply";
        p.proxy = null;
        p.setAllowedAlgorithms(
                Arrays.asList(
                        IpSecAlgorithm.AUTH_CRYPT_AES_GCM,
                        IpSecAlgorithm.AUTH_CRYPT_CHACHA20_POLY1305,
                        IpSecAlgorithm.AUTH_HMAC_SHA512,
                        IpSecAlgorithm.CRYPT_AES_CBC));
        p.isBypassable = true;
        p.isMetered = true;
        p.maxMtu = 1350;
        p.areAuthParamsInline = true;

        // Not saved, but also not compared.
        p.saveLogin = true;

        return p;
    }

    private VpnProfile getSampleIkev2ProfileWithIkeTunConnParams(String key) {
        final VpnProfile p = new VpnProfile(key, true /* isRestrictedToTestNetworks */,
                false /* excludesLocalRoutes */, true /* requiresPlatformValidation */,
                new IkeTunnelConnectionParams(IKE_PARAMS_V4, CHILD_PARAMS),
                true /* mAutomaticNattKeepaliveTimerEnabled */,
                true /* automaticIpVersionSelectionEnabled */);

        p.name = "foo";
        p.server = "bar";
        p.dnsServers = "8.8.8.8";
        p.searchDomains = "";
        p.routes = "0.0.0.0/0";
        p.mppe = false;
        p.proxy = null;
        p.setAllowedAlgorithms(
                Arrays.asList(
                        IpSecAlgorithm.AUTH_CRYPT_AES_GCM,
                        IpSecAlgorithm.AUTH_CRYPT_CHACHA20_POLY1305,
                        IpSecAlgorithm.AUTH_HMAC_SHA512,
                        IpSecAlgorithm.CRYPT_AES_CBC));
        p.isBypassable = true;
        p.isMetered = true;
        p.maxMtu = 1350;
        p.areAuthParamsInline = true;

        // Not saved, but also not compared.
        p.saveLogin = true;

        return p;
    }

    @Test
    public void testEquals() {
        assertEquals(
                getSampleIkev2Profile(DUMMY_PROFILE_KEY), getSampleIkev2Profile(DUMMY_PROFILE_KEY));

        final VpnProfile modified = getSampleIkev2Profile(DUMMY_PROFILE_KEY);
        modified.maxMtu--;
        assertNotEquals(getSampleIkev2Profile(DUMMY_PROFILE_KEY), modified);
    }

    @Test
    public void testParcelUnparcel() {
        assertParcelSane(getSampleIkev2Profile(DUMMY_PROFILE_KEY), 28);
        assertParcelSane(getSampleIkev2ProfileWithIkeTunConnParams(DUMMY_PROFILE_KEY), 28);
    }

    @Test
    public void testEncodeDecodeWithIkeTunConnParams() {
        final VpnProfile profile = getSampleIkev2ProfileWithIkeTunConnParams(DUMMY_PROFILE_KEY);
        final VpnProfile decoded = VpnProfile.decode(DUMMY_PROFILE_KEY, profile.encode());
        assertEquals(profile, decoded);
    }

    @Test
    public void testEncodeDecode() {
        final VpnProfile profile = getSampleIkev2Profile(DUMMY_PROFILE_KEY);
        final VpnProfile decoded = VpnProfile.decode(DUMMY_PROFILE_KEY, profile.encode());
        assertEquals(profile, decoded);
    }

    @Test
    public void testEncodeDecodeTooManyValues() {
        final VpnProfile profile = getSampleIkev2Profile(DUMMY_PROFILE_KEY);
        final byte[] tooManyValues =
                (new String(profile.encode()) + VpnProfile.VALUE_DELIMITER + "invalid").getBytes();

        assertNull(VpnProfile.decode(DUMMY_PROFILE_KEY, tooManyValues));
    }

    private String getEncodedDecodedIkev2ProfileMissingValues(int... missingIndices) {
        // Sort to ensure when we remove, we can do it from greatest first.
        Arrays.sort(missingIndices);

        final String encoded = new String(getSampleIkev2Profile(DUMMY_PROFILE_KEY).encode());
        final List<String> parts =
                new ArrayList<>(Arrays.asList(encoded.split(VpnProfile.VALUE_DELIMITER)));

        // Remove from back first to ensure indexing is consistent.
        for (int i = missingIndices.length - 1; i >= 0; i--) {
            parts.remove(missingIndices[i]);
        }

        return String.join(VpnProfile.VALUE_DELIMITER, parts.toArray(new String[0]));
    }

    @Test
    public void testEncodeDecodeInvalidNumberOfValues() {
        final String tooFewValues =
                getEncodedDecodedIkev2ProfileMissingValues(
                        ENCODED_INDEX_AUTH_PARAMS_INLINE,
                        ENCODED_INDEX_RESTRICTED_TO_TEST_NETWORKS,
                        ENCODED_INDEX_EXCLUDE_LOCAL_ROUTE,
                        ENCODED_INDEX_REQUIRE_PLATFORM_VALIDATION,
                        ENCODED_INDEX_IKE_TUN_CONN_PARAMS,
                        ENCODED_INDEX_AUTOMATIC_NATT_KEEPALIVE_TIMER_ENABLED,
                        ENCODED_INDEX_AUTOMATIC_IP_VERSION_SELECTION_ENABLED
                        /* missingIndices */);

        assertNull(VpnProfile.decode(DUMMY_PROFILE_KEY, tooFewValues.getBytes()));
    }

    private String getEncodedDecodedIkev2ProfileWithtooFewValues() {
        return getEncodedDecodedIkev2ProfileMissingValues(
                ENCODED_INDEX_RESTRICTED_TO_TEST_NETWORKS,
                ENCODED_INDEX_EXCLUDE_LOCAL_ROUTE,
                ENCODED_INDEX_REQUIRE_PLATFORM_VALIDATION,
                ENCODED_INDEX_IKE_TUN_CONN_PARAMS,
                ENCODED_INDEX_AUTOMATIC_NATT_KEEPALIVE_TIMER_ENABLED,
                ENCODED_INDEX_AUTOMATIC_IP_VERSION_SELECTION_ENABLED /* missingIndices */);
    }

    @Test
    public void testEncodeDecodeMissingIsRestrictedToTestNetworks() {
        final String tooFewValues = getEncodedDecodedIkev2ProfileWithtooFewValues();

        // Verify decoding without isRestrictedToTestNetworks defaults to false
        final VpnProfile decoded = VpnProfile.decode(DUMMY_PROFILE_KEY, tooFewValues.getBytes());
        assertFalse(decoded.isRestrictedToTestNetworks);
    }

    @Test
    public void testEncodeDecodeMissingExcludeLocalRoutes() {
        final String tooFewValues = getEncodedDecodedIkev2ProfileWithtooFewValues();

        // Verify decoding without excludeLocalRoutes defaults to false
        final VpnProfile decoded = VpnProfile.decode(DUMMY_PROFILE_KEY, tooFewValues.getBytes());
        assertFalse(decoded.excludeLocalRoutes);
    }

    @Test
    public void testEncodeDecodeMissingRequiresValidation() {
        final String tooFewValues = getEncodedDecodedIkev2ProfileWithtooFewValues();

        // Verify decoding without requiresValidation defaults to false
        final VpnProfile decoded = VpnProfile.decode(DUMMY_PROFILE_KEY, tooFewValues.getBytes());
        assertFalse(decoded.requiresInternetValidation);
    }

    @Test
    public void testEncodeDecodeMissingAutomaticNattKeepaliveTimerEnabled() {
        final String tooFewValues = getEncodedDecodedIkev2ProfileWithtooFewValues();

        // Verify decoding without automaticNattKeepaliveTimerEnabled defaults to false
        final VpnProfile decoded = VpnProfile.decode(DUMMY_PROFILE_KEY, tooFewValues.getBytes());
        assertFalse(decoded.automaticNattKeepaliveTimerEnabled);
    }

    @Test
    public void testEncodeDecodeMissingAutomaticIpVersionSelectionEnabled() {
        final String tooFewValues = getEncodedDecodedIkev2ProfileWithtooFewValues();

        // Verify decoding without automaticIpVersionSelectionEnabled defaults to false
        final VpnProfile decoded = VpnProfile.decode(DUMMY_PROFILE_KEY, tooFewValues.getBytes());
        assertFalse(decoded.automaticIpVersionSelectionEnabled);
    }

    @Test
    public void testEncodeDecodeLoginsNotSaved() {
        final VpnProfile profile = getSampleIkev2Profile(DUMMY_PROFILE_KEY);
        profile.saveLogin = false;

        final VpnProfile decoded = VpnProfile.decode(DUMMY_PROFILE_KEY, profile.encode());
        assertNotEquals(profile, decoded);

        // Add the username/password back, everything else must be equal.
        decoded.username = profile.username;
        decoded.password = profile.password;
        assertEquals(profile, decoded);
    }

    @Test
    public void testClone() {
        final VpnProfile profile = getSampleIkev2Profile(DUMMY_PROFILE_KEY);
        final VpnProfile clone = profile.clone();
        assertEquals(profile, clone);
        assertNotSame(profile, clone);
    }
}
