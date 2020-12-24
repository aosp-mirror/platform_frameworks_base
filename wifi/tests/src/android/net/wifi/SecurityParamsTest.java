/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.wifi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.GroupCipher;
import android.net.wifi.WifiConfiguration.GroupMgmtCipher;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.PairwiseCipher;
import android.net.wifi.WifiConfiguration.Protocol;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.BitSet;

/**
 * Unit tests for {@link android.net.wifi.WifiInfo}.
 */
@SmallTest
public class SecurityParamsTest {

    private void verifySecurityParams(SecurityParams params,
            int expectedSecurityType,
            int[] expectedAllowedKeyManagement,
            int[] expectedAllowedProtocols,
            int[] expectedAllowedAuthAlgorithms,
            int[] expectedAllowedPairwiseCiphers,
            int[] expectedAllowedGroupCiphers,
            boolean expectedRequirePmf) {
        assertTrue(params.isSecurityType(expectedSecurityType));
        assertEquals(expectedSecurityType, params.getSecurityType());
        for (int b: expectedAllowedKeyManagement) {
            assertTrue(params.getAllowedKeyManagement().get(b));
        }
        for (int b: expectedAllowedProtocols) {
            assertTrue(params.getAllowedProtocols().get(b));
        }
        for (int b: expectedAllowedAuthAlgorithms) {
            assertTrue(params.getAllowedAuthAlgorithms().get(b));
        }
        for (int b: expectedAllowedPairwiseCiphers) {
            assertTrue(params.getAllowedPairwiseCiphers().get(b));
        }
        for (int b: expectedAllowedGroupCiphers) {
            assertTrue(params.getAllowedGroupCiphers().get(b));
        }
        assertEquals(expectedRequirePmf, params.isRequirePmf());
    }

    /** Verify the security params created by security type. */
    @Test
    public void testSecurityTypeCreator() throws Exception {
        int[] securityTypes = new int[] {
                WifiConfiguration.SECURITY_TYPE_WAPI_CERT,
                WifiConfiguration.SECURITY_TYPE_WAPI_PSK,
                WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT,
                WifiConfiguration.SECURITY_TYPE_OWE,
                WifiConfiguration.SECURITY_TYPE_SAE,
                WifiConfiguration.SECURITY_TYPE_OSEN,
                WifiConfiguration.SECURITY_TYPE_EAP,
                WifiConfiguration.SECURITY_TYPE_PSK,
                WifiConfiguration.SECURITY_TYPE_OPEN,
                WifiConfiguration.SECURITY_TYPE_PASSPOINT_R1_R2,
                WifiConfiguration.SECURITY_TYPE_PASSPOINT_R3,
        };

        for (int type: securityTypes) {
            assertEquals(type,
                    SecurityParams.createSecurityParamsBySecurityType(type).getSecurityType());
        }
    }

    /** Verify EAP params creator. */
    @Test
    public void testEapCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_EAP;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.WPA_EAP, KeyMgmt.IEEE8021X};
        int[] expectedAllowedProtocols = new int[] {};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {};
        int[] expectedAllowedGroupCiphers = new int[] {};
        boolean expectedRequirePmf = false;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify Passpoint R1/R2 params creator. */
    @Test
    public void testEapPasspointR1R2Creator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_PASSPOINT_R1_R2;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.WPA_EAP, KeyMgmt.IEEE8021X};
        int[] expectedAllowedProtocols = new int[] {};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {};
        int[] expectedAllowedGroupCiphers = new int[] {};
        boolean expectedRequirePmf = false;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify Passpoint R3 params creator. */
    @Test
    public void testEapPasspointR3Creator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_PASSPOINT_R3;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.WPA_EAP, KeyMgmt.IEEE8021X};
        int[] expectedAllowedProtocols = new int[] {};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {};
        int[] expectedAllowedGroupCiphers = new int[] {};
        boolean expectedRequirePmf = true;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify Enhanced Open params creator. */
    @Test
    public void testEnhancedOpenCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_OWE;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.OWE};
        int[] expectedAllowedProtocols = new int[] {Protocol.RSN};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {
                PairwiseCipher.CCMP, PairwiseCipher.GCMP_128, PairwiseCipher.GCMP_256};
        int[] expectedAllowedGroupCiphers = new int[] {
                GroupCipher.CCMP, GroupCipher.GCMP_128, GroupCipher.GCMP_256};
        boolean expectedRequirePmf = true;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify Open params creator. */
    @Test
    public void testOpenCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_OPEN;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.NONE};
        int[] expectedAllowedProtocols = new int[] {};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {};
        int[] expectedAllowedGroupCiphers = new int[] {};
        boolean expectedRequirePmf = false;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify OSEN params creator. */
    @Test
    public void testOsenCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_OSEN;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.OSEN};
        int[] expectedAllowedProtocols = new int[] {Protocol.OSEN};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {};
        int[] expectedAllowedGroupCiphers = new int[] {};
        boolean expectedRequirePmf = false;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify WAPI CERT params creator. */
    @Test
    public void testWapiCertCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_WAPI_CERT;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.WAPI_CERT};
        int[] expectedAllowedProtocols = new int[] {Protocol.WAPI};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {PairwiseCipher.SMS4};
        int[] expectedAllowedGroupCiphers = new int[] {GroupCipher.SMS4};
        boolean expectedRequirePmf = false;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify WAPI PSK params creator. */
    @Test
    public void testWapiPskCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_WAPI_PSK;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.WAPI_PSK};
        int[] expectedAllowedProtocols = new int[] {Protocol.WAPI};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {PairwiseCipher.SMS4};
        int[] expectedAllowedGroupCiphers = new int[] {GroupCipher.SMS4};
        boolean expectedRequirePmf = false;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify WEP params creator. */
    @Test
    public void testWepCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_WEP;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.NONE};
        int[] expectedAllowedProtocols = new int[] {};
        int[] expectedAllowedAuthAlgorithms = new int[] {AuthAlgorithm.OPEN, AuthAlgorithm.SHARED};
        int[] expectedAllowedPairwiseCiphers = new int[] {};
        int[] expectedAllowedGroupCiphers = new int[] {};
        boolean expectedRequirePmf = false;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify WPA3 Enterprise 192-bit params creator. */
    @Test
    public void testWpa3Enterprise192BitCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT;
        int[] expectedAllowedKeyManagement = new int[] {
                KeyMgmt.WPA_EAP, KeyMgmt.IEEE8021X, KeyMgmt.SUITE_B_192};
        int[] expectedAllowedProtocols = new int[] {Protocol.RSN};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {
                PairwiseCipher.GCMP_128, PairwiseCipher.GCMP_256};
        int[] expectedAllowedGroupCiphers = new int[] {GroupCipher.GCMP_128, GroupCipher.GCMP_256};
        boolean expectedRequirePmf = true;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);

        assertTrue(p.getAllowedGroupManagementCiphers().get(GroupMgmtCipher.BIP_GMAC_256));
    }

    /** Verify WPA3 Enterprise params creator. */
    @Test
    public void testWpa3EnterpriseCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.WPA_EAP, KeyMgmt.IEEE8021X};
        int[] expectedAllowedProtocols = new int[] {Protocol.RSN};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {
                PairwiseCipher.CCMP, PairwiseCipher.GCMP_256};
        int[] expectedAllowedGroupCiphers = new int[] {GroupCipher.CCMP, GroupCipher.GCMP_256};
        boolean expectedRequirePmf = true;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify WPA3 Personal params creator. */
    @Test
    public void testWpa3PersonalCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_SAE;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.SAE};
        int[] expectedAllowedProtocols = new int[] {Protocol.RSN};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {
                PairwiseCipher.CCMP, PairwiseCipher.GCMP_128, PairwiseCipher.GCMP_256};
        int[] expectedAllowedGroupCiphers = new int[] {
                GroupCipher.CCMP, GroupCipher.GCMP_128, GroupCipher.GCMP_256};
        boolean expectedRequirePmf = true;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify WPA2 Personal EAP params creator. */
    @Test
    public void testWpaWpa2PersonalCreator() throws Exception {
        int expectedSecurityType = WifiConfiguration.SECURITY_TYPE_PSK;
        int[] expectedAllowedKeyManagement = new int[] {KeyMgmt.WPA_PSK};
        int[] expectedAllowedProtocols = new int[] {};
        int[] expectedAllowedAuthAlgorithms = new int[] {};
        int[] expectedAllowedPairwiseCiphers = new int[] {};
        int[] expectedAllowedGroupCiphers = new int[] {};
        boolean expectedRequirePmf = false;
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                                expectedSecurityType);
        verifySecurityParams(p, expectedSecurityType,
                expectedAllowedKeyManagement, expectedAllowedProtocols,
                expectedAllowedAuthAlgorithms, expectedAllowedPairwiseCiphers,
                expectedAllowedGroupCiphers, expectedRequirePmf);
    }

    /** Verify setter/getter methods */
    @Test
    public void testCommonSetterGetter() throws Exception {
        SecurityParams params = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_PSK);

        // PSK setting
        BitSet allowedKeyManagement = new BitSet();
        allowedKeyManagement.set(KeyMgmt.WPA_PSK);

        BitSet allowedProtocols = new BitSet();
        allowedProtocols.set(Protocol.RSN);
        allowedProtocols.set(Protocol.WPA);

        BitSet allowedPairwiseCiphers = new BitSet();
        allowedPairwiseCiphers.set(PairwiseCipher.CCMP);
        allowedPairwiseCiphers.set(PairwiseCipher.TKIP);

        BitSet allowedGroupCiphers = new BitSet();
        allowedGroupCiphers.set(GroupCipher.CCMP);
        allowedGroupCiphers.set(GroupCipher.TKIP);
        allowedGroupCiphers.set(GroupCipher.WEP40);
        allowedGroupCiphers.set(GroupCipher.WEP104);

        assertEquals(allowedKeyManagement, params.getAllowedKeyManagement());
        assertTrue(params.getAllowedKeyManagement().get(KeyMgmt.WPA_PSK));

        assertEquals(allowedProtocols, params.getAllowedProtocols());
        assertTrue(params.getAllowedProtocols().get(Protocol.RSN));
        assertTrue(params.getAllowedProtocols().get(Protocol.WPA));

        assertEquals(allowedPairwiseCiphers, params.getAllowedPairwiseCiphers());
        assertTrue(params.getAllowedPairwiseCiphers().get(PairwiseCipher.CCMP));
        assertTrue(params.getAllowedPairwiseCiphers().get(PairwiseCipher.TKIP));

        assertEquals(allowedGroupCiphers, params.getAllowedGroupCiphers());
        assertTrue(params.getAllowedGroupCiphers().get(GroupCipher.CCMP));
        assertTrue(params.getAllowedGroupCiphers().get(GroupCipher.TKIP));
        assertTrue(params.getAllowedGroupCiphers().get(GroupCipher.WEP40));
        assertTrue(params.getAllowedGroupCiphers().get(GroupCipher.WEP104));

        params.setEnabled(false);
        assertFalse(params.isEnabled());
    }

    /** Verify SAE-specific methods */
    @Test
    public void testSaeMethods() throws Exception {
        SecurityParams p = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_SAE);

        assertFalse(p.isAddedByAutoUpgrade());
        p.setIsAddedByAutoUpgrade(true);
        assertTrue(p.isAddedByAutoUpgrade());

        assertFalse(p.isSaeH2eOnlyMode());
        p.enableSaeH2eOnlyMode(true);
        assertTrue(p.isSaeH2eOnlyMode());

        assertFalse(p.isSaePkOnlyMode());
        p.enableSaePkOnlyMode(true);
        assertTrue(p.isSaePkOnlyMode());
    }

    /** Verify copy constructor. */
    @Test
    public void testCopyConstructor() throws Exception {
        SecurityParams params = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_PSK);
        params.setEnabled(false);
        params.setIsAddedByAutoUpgrade(true);

        SecurityParams copiedParams = new SecurityParams(params);

        assertTrue(params.isSameSecurityType(copiedParams));
        assertEquals(params.getAllowedKeyManagement(), copiedParams.getAllowedKeyManagement());
        assertEquals(params.getAllowedProtocols(), copiedParams.getAllowedProtocols());
        assertEquals(params.getAllowedAuthAlgorithms(), copiedParams.getAllowedAuthAlgorithms());
        assertEquals(params.getAllowedPairwiseCiphers(), copiedParams.getAllowedPairwiseCiphers());
        assertEquals(params.getAllowedGroupCiphers(), copiedParams.getAllowedGroupCiphers());
        assertEquals(params.getAllowedGroupManagementCiphers(),
                copiedParams.getAllowedGroupManagementCiphers());
        assertEquals(params.getAllowedSuiteBCiphers(), copiedParams.getAllowedSuiteBCiphers());
        assertEquals(params.isRequirePmf(), copiedParams.isRequirePmf());
        assertEquals(params.isEnabled(), copiedParams.isEnabled());
        assertEquals(params.isSaeH2eOnlyMode(), copiedParams.isSaeH2eOnlyMode());
        assertEquals(params.isSaePkOnlyMode(), copiedParams.isSaePkOnlyMode());
        assertEquals(params.isAddedByAutoUpgrade(), copiedParams.isAddedByAutoUpgrade());
    }

    /** Check that two params are equal if and only if their types are the same. */
    @Test
    public void testEquals() {
        SecurityParams saeParams1 = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_SAE);
        SecurityParams saeParams2 = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_SAE);
        SecurityParams pskParams = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_PSK);
        assertEquals(saeParams1, saeParams2);
        assertNotEquals(saeParams1, pskParams);
    }

    /** Check that hash values are the same if and only if their types are the same. */
    @Test
    public void testHashCode() {
        SecurityParams saeParams1 = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_SAE);
        SecurityParams saeParams2 = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_SAE);
        SecurityParams pskParams = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_PSK);
        assertEquals(saeParams1.hashCode(), saeParams2.hashCode());
        assertNotEquals(saeParams1.hashCode(), pskParams.hashCode());
    }

    /** Verify open network check */
    @Test
    public void testIsOpenNetwork() {
        SecurityParams[] openSecurityParams = new SecurityParams[] {
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_OWE),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_OPEN),
        };
        for (SecurityParams p: openSecurityParams) {
            assertTrue(p.isOpenSecurityType());
        }

        SecurityParams[] nonOpenSecurityParams = new SecurityParams[] {
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_EAP),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_PASSPOINT_R1_R2),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_PASSPOINT_R3),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_OSEN),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_WAPI_PSK),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_WAPI_CERT),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_WEP),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_SAE),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_PSK),
        };
        for (SecurityParams p: nonOpenSecurityParams) {
            assertFalse(p.isOpenSecurityType());
        }
    }

    /** Verify enterprise network check */
    @Test
    public void testIsEnterpriseNetwork() {
        SecurityParams[] enterpriseSecurityParams = new SecurityParams[] {
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_EAP),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_PASSPOINT_R1_R2),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_PASSPOINT_R3),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_WAPI_CERT),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE),
        };
        for (SecurityParams p: enterpriseSecurityParams) {
            assertTrue(p.isEnterpriseSecurityType());
        }

        SecurityParams[] nonEnterpriseSecurityParams = new SecurityParams[] {
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_OWE),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_OPEN),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_OSEN),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_WAPI_PSK),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_WEP),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_SAE),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_PSK),
        };
        for (SecurityParams p: nonEnterpriseSecurityParams) {
            assertFalse(p.isEnterpriseSecurityType());
        }
    }

    /** Check that parcel marshalling/unmarshalling works */
    @Test
    public void testParcelMethods() {
        SecurityParams params = SecurityParams.createSecurityParamsBySecurityType(
                WifiConfiguration.SECURITY_TYPE_SAE);

        Parcel parcelW = Parcel.obtain();
        params.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);

        SecurityParams reParams = SecurityParams.createFromParcel(parcelR);
        assertEquals(params, reParams);
    }
}
