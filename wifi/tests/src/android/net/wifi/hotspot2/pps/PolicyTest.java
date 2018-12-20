/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.wifi.hotspot2.pps;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.util.Base64;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.pps.Policy}.
 */
@SmallTest
public class PolicyTest {
    private static final int MAX_NUMBER_OF_EXCLUDED_SSIDS = 128;
    private static final int MAX_SSID_BYTES = 32;
    private static final int MAX_PORT_STRING_BYTES = 64;

    /**
     * Helper function for creating a {@link Policy} for testing.
     *
     * @return {@link Policy}
     */
    private static Policy createPolicy() {
        Policy policy = new Policy();
        policy.setMinHomeDownlinkBandwidth(123);
        policy.setMinHomeUplinkBandwidth(345);
        policy.setMinRoamingDownlinkBandwidth(567);
        policy.setMinRoamingUplinkBandwidth(789);
        policy.setExcludedSsidList(new String[] {"ssid1", "ssid2"});
        Map<Integer, String> requiredProtoPortMap = new HashMap<>();
        requiredProtoPortMap.put(12, "23,342,123");
        requiredProtoPortMap.put(23, "789,372,1235");
        policy.setRequiredProtoPortMap(requiredProtoPortMap);
        policy.setMaximumBssLoadValue(12);

        List<Policy.RoamingPartner> preferredRoamingPartnerList = new ArrayList<>();
        Policy.RoamingPartner partner1 = new Policy.RoamingPartner();
        partner1.setFqdn("partner1.com");
        partner1.setFqdnExactMatch(true);
        partner1.setPriority(12);
        partner1.setCountries("us,jp");
        Policy.RoamingPartner partner2 = new Policy.RoamingPartner();
        partner2.setFqdn("partner2.com");
        partner2.setFqdnExactMatch(false);
        partner2.setPriority(42);
        partner2.setCountries("ca,fr");
        preferredRoamingPartnerList.add(partner1);
        preferredRoamingPartnerList.add(partner2);
        policy.setPreferredRoamingPartnerList(preferredRoamingPartnerList);

        UpdateParameter policyUpdate = new UpdateParameter();
        policyUpdate.setUpdateIntervalInMinutes(1712);
        policyUpdate.setUpdateMethod(UpdateParameter.UPDATE_METHOD_OMADM);
        policyUpdate.setRestriction(UpdateParameter.UPDATE_RESTRICTION_HOMESP);
        policyUpdate.setServerUri("policy.update.com");
        policyUpdate.setUsername("username");
        policyUpdate.setBase64EncodedPassword(
                Base64.encodeToString("password".getBytes(), Base64.DEFAULT));
        policyUpdate.setTrustRootCertUrl("trust.cert.com");
        policyUpdate.setTrustRootCertSha256Fingerprint(new byte[32]);
        policy.setPolicyUpdate(policyUpdate);

        return policy;
    }

    /**
     * Helper function for verifying Policy after parcel write then read.
     * @param policyToWrite
     * @throws Exception
     */
    private static void verifyParcel(Policy policyToWrite) throws Exception {
        Parcel parcel = Parcel.obtain();
        policyToWrite.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        Policy policyFromRead = Policy.CREATOR.createFromParcel(parcel);
        assertTrue(policyFromRead.equals(policyToWrite));
    }

    /**
     * Verify parcel read/write for an empty Policy.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithEmptyPolicy() throws Exception {
        verifyParcel(new Policy());
    }

    /**
     * Verify parcel read/write for a Policy with all fields set.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithFullPolicy() throws Exception {
        verifyParcel(createPolicy());
    }

    /**
     * Verify parcel read/write for a Policy without protocol port map.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutProtoPortMap() throws Exception {
        Policy policy = createPolicy();
        policy.setRequiredProtoPortMap(null);
        verifyParcel(policy);
    }

    /**
     * Verify parcel read/write for a Policy without preferred roaming partner list.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutPreferredRoamingPartnerList() throws Exception {
        Policy policy = createPolicy();
        policy.setPreferredRoamingPartnerList(null);
        verifyParcel(policy);
    }

    /**
     * Verify parcel read/write for a Policy without policy update parameters.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutPolicyUpdate() throws Exception {
        Policy policy = createPolicy();
        policy.setPolicyUpdate(null);
        verifyParcel(policy);
    }

    /**
     * Verify that policy created using copy constructor with null source should be the same
     * as the policy created using default constructor.
     *
     * @throws Exception
     */
    @Test
    public void verifyCopyConstructionWithNullSource() throws Exception {
        Policy copyPolicy = new Policy(null);
        Policy defaultPolicy = new Policy();
        assertTrue(defaultPolicy.equals(copyPolicy));
    }

    /**
     * Verify that policy created using copy constructor with a valid source should be the
     * same as the source.
     *
     * @throws Exception
     */
    @Test
    public void verifyCopyConstructionWithFullPolicy() throws Exception {
        Policy policy = createPolicy();
        Policy copyPolicy = new Policy(policy);
        assertTrue(policy.equals(copyPolicy));
    }

    /**
     * Verify that a default policy (with no informatio) is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithDefault() throws Exception {
        Policy policy = new Policy();
        assertFalse(policy.validate());
    }

    /**
     * Verify that a policy created using {@link #createPolicy} is valid, since all fields are
     * filled in with valid values.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithFullPolicy() throws Exception {
        assertTrue(createPolicy().validate());
    }

    /**
     * Verify that a policy without policy update parameters is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithoutPolicyUpdate() throws Exception {
        Policy policy = createPolicy();
        policy.setPolicyUpdate(null);
        assertFalse(policy.validate());
    }

    /**
     * Verify that a policy with invalid policy update parameters is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithInvalidPolicyUpdate() throws Exception {
        Policy policy = createPolicy();
        policy.setPolicyUpdate(new UpdateParameter());
        assertFalse(policy.validate());
    }

    /**
     * Verify that a policy with a preferred roaming partner with FQDN not specified is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithRoamingPartnerWithoutFQDN() throws Exception {
        Policy policy = createPolicy();
        Policy.RoamingPartner partner = new Policy.RoamingPartner();
        partner.setFqdnExactMatch(true);
        partner.setPriority(12);
        partner.setCountries("us,jp");
        policy.getPreferredRoamingPartnerList().add(partner);
        assertFalse(policy.validate());
    }

    /**
     * Verify that a policy with a preferred roaming partner with countries not specified is
     * invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithRoamingPartnerWithoutCountries() throws Exception {
        Policy policy = createPolicy();
        Policy.RoamingPartner partner = new Policy.RoamingPartner();
        partner.setFqdn("test.com");
        partner.setFqdnExactMatch(true);
        partner.setPriority(12);
        policy.getPreferredRoamingPartnerList().add(partner);
        assertFalse(policy.validate());
    }

    /**
     * Verify that a policy with a proto-port tuple that contains an invalid port string is
     * invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithInvalidPortStringInProtoPortMap() throws Exception {
        Policy policy = createPolicy();
        byte[] rawPortBytes = new byte[MAX_PORT_STRING_BYTES + 1];
        policy.getRequiredProtoPortMap().put(
                324, new String(rawPortBytes, StandardCharsets.UTF_8));
        assertFalse(policy.validate());
    }

    /**
     * Verify that a policy with number of excluded SSIDs exceeded the max is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithSsidExclusionListSizeExceededMax() throws Exception {
        Policy policy = createPolicy();
        String[] excludedSsidList = new String[MAX_NUMBER_OF_EXCLUDED_SSIDS + 1];
        Arrays.fill(excludedSsidList, "ssid");
        policy.setExcludedSsidList(excludedSsidList);
        assertFalse(policy.validate());
    }

    /**
     * Verify that a policy with an invalid SSID in the excluded SSID list is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithInvalidSsid() throws Exception {
        Policy policy = createPolicy();
        byte[] rawSsidBytes = new byte[MAX_SSID_BYTES + 1];
        Arrays.fill(rawSsidBytes, (byte) 'a');
        String[] excludedSsidList = new String[] {
                new String(rawSsidBytes, StandardCharsets.UTF_8)};
        policy.setExcludedSsidList(excludedSsidList);
        assertFalse(policy.validate());
    }
}
