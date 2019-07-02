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
 * limitations under the License.
 */

package android.net.wifi.hotspot2.omadm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.omadm.PpsMoParser}.
 */
@SmallTest
public class PpsMoParserTest {
    private static final String VALID_PPS_MO_XML_FILE = "assets/pps/PerProviderSubscription.xml";
    private static final String PPS_MO_XML_FILE_DUPLICATE_HOMESP =
            "assets/pps/PerProviderSubscription_DuplicateHomeSP.xml";
    private static final String PPS_MO_XML_FILE_DUPLICATE_VALUE =
            "assets/pps/PerProviderSubscription_DuplicateValue.xml";
    private static final String PPS_MO_XML_FILE_MISSING_VALUE =
            "assets/pps/PerProviderSubscription_MissingValue.xml";
    private static final String PPS_MO_XML_FILE_MISSING_NAME =
            "assets/pps/PerProviderSubscription_MissingName.xml";
    private static final String PPS_MO_XML_FILE_INVALID_NODE =
            "assets/pps/PerProviderSubscription_InvalidNode.xml";
    private static final String PPS_MO_XML_FILE_INVALID_NAME =
            "assets/pps/PerProviderSubscription_InvalidName.xml";

    /**
     * Read the content of the given resource file into a String.
     *
     * @param filename String name of the file
     * @return String
     * @throws IOException
     */
    private String loadResourceFile(String filename) throws IOException {
        InputStream in = getClass().getClassLoader().getResourceAsStream(filename);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append("\n");
        }

        return builder.toString();
    }

    /**
     * Generate a {@link PasspointConfiguration} that matches the configuration specified in the
     * XML file {@link #VALID_PPS_MO_XML_FILE}.
     *
     * @return {@link PasspointConfiguration}
     */
    private PasspointConfiguration generateConfigurationFromPPSMOTree() throws Exception {
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        byte[] certFingerprint = new byte[32];
        Arrays.fill(certFingerprint, (byte) 0x1f);

        PasspointConfiguration config = new PasspointConfiguration();
        config.setUpdateIdentifier(12);
        config.setCredentialPriority(99);

        // AAA Server trust root.
        Map<String, byte[]> trustRootCertList = new HashMap<>();
        trustRootCertList.put("server1.trust.root.com", certFingerprint);
        config.setTrustRootCertList(trustRootCertList);

        // Subscription update.
        UpdateParameter subscriptionUpdate = new UpdateParameter();
        subscriptionUpdate.setUpdateIntervalInMinutes(120);
        subscriptionUpdate.setUpdateMethod(UpdateParameter.UPDATE_METHOD_SSP);
        subscriptionUpdate.setRestriction(UpdateParameter.UPDATE_RESTRICTION_ROAMING_PARTNER);
        subscriptionUpdate.setServerUri("subscription.update.com");
        subscriptionUpdate.setUsername("subscriptionUser");
        subscriptionUpdate.setBase64EncodedPassword("subscriptionPass");
        subscriptionUpdate.setTrustRootCertUrl("subscription.update.cert.com");
        subscriptionUpdate.setTrustRootCertSha256Fingerprint(certFingerprint);
        config.setSubscriptionUpdate(subscriptionUpdate);

        // Subscription parameters.
        config.setSubscriptionCreationTimeInMillis(format.parse("2016-02-01T10:00:00Z").getTime());
        config.setSubscriptionExpirationTimeInMillis(format.parse("2016-03-01T10:00:00Z").getTime());
        config.setSubscriptionType("Gold");
        config.setUsageLimitDataLimit(921890);
        config.setUsageLimitStartTimeInMillis(format.parse("2016-12-01T10:00:00Z").getTime());
        config.setUsageLimitTimeLimitInMinutes(120);
        config.setUsageLimitUsageTimePeriodInMinutes(99910);

        // HomeSP configuration.
        HomeSp homeSp = new HomeSp();
        homeSp.setFriendlyName("Century House");
        homeSp.setFqdn("mi6.co.uk");
        homeSp.setRoamingConsortiumOis(new long[] {0x112233L, 0x445566L});
        homeSp.setIconUrl("icon.test.com");
        Map<String, Long> homeNetworkIds = new HashMap<>();
        homeNetworkIds.put("TestSSID", 0x12345678L);
        homeNetworkIds.put("NullHESSID", null);
        homeSp.setHomeNetworkIds(homeNetworkIds);
        homeSp.setMatchAllOis(new long[] {0x11223344});
        homeSp.setMatchAnyOis(new long[] {0x55667788});
        homeSp.setOtherHomePartners(new String[] {"other.fqdn.com"});
        config.setHomeSp(homeSp);

        // Credential configuration.
        Credential credential = new Credential();
        credential.setCreationTimeInMillis(format.parse("2016-01-01T10:00:00Z").getTime());
        credential.setExpirationTimeInMillis(format.parse("2016-02-01T10:00:00Z").getTime());
        credential.setRealm("shaken.stirred.com");
        credential.setCheckAaaServerCertStatus(true);
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setUsername("james");
        userCredential.setPassword("Ym9uZDAwNw==");
        userCredential.setMachineManaged(true);
        userCredential.setSoftTokenApp("TestApp");
        userCredential.setAbleToShare(true);
        userCredential.setEapType(21);
        userCredential.setNonEapInnerMethod("MS-CHAP-V2");
        credential.setUserCredential(userCredential);
        Credential.CertificateCredential certCredential = new Credential.CertificateCredential();
        certCredential.setCertType("x509v3");
        certCredential.setCertSha256Fingerprint(certFingerprint);
        credential.setCertCredential(certCredential);
        Credential.SimCredential simCredential = new Credential.SimCredential();
        simCredential.setImsi("imsi");
        simCredential.setEapType(24);
        credential.setSimCredential(simCredential);
        config.setCredential(credential);

        // Policy configuration.
        Policy policy = new Policy();
        List<Policy.RoamingPartner> preferredRoamingPartnerList = new ArrayList<>();
        Policy.RoamingPartner partner1 = new Policy.RoamingPartner();
        partner1.setFqdn("test1.fqdn.com");
        partner1.setFqdnExactMatch(true);
        partner1.setPriority(127);
        partner1.setCountries("us,fr");
        Policy.RoamingPartner partner2 = new Policy.RoamingPartner();
        partner2.setFqdn("test2.fqdn.com");
        partner2.setFqdnExactMatch(false);
        partner2.setPriority(200);
        partner2.setCountries("*");
        preferredRoamingPartnerList.add(partner1);
        preferredRoamingPartnerList.add(partner2);
        policy.setPreferredRoamingPartnerList(preferredRoamingPartnerList);
        policy.setMinHomeDownlinkBandwidth(23412);
        policy.setMinHomeUplinkBandwidth(9823);
        policy.setMinRoamingDownlinkBandwidth(9271);
        policy.setMinRoamingUplinkBandwidth(2315);
        policy.setExcludedSsidList(new String[] {"excludeSSID"});
        Map<Integer, String> requiredProtoPortMap = new HashMap<>();
        requiredProtoPortMap.put(12, "34,92,234");
        policy.setRequiredProtoPortMap(requiredProtoPortMap);
        policy.setMaximumBssLoadValue(23);
        UpdateParameter policyUpdate = new UpdateParameter();
        policyUpdate.setUpdateIntervalInMinutes(120);
        policyUpdate.setUpdateMethod(UpdateParameter.UPDATE_METHOD_OMADM);
        policyUpdate.setRestriction(UpdateParameter.UPDATE_RESTRICTION_HOMESP);
        policyUpdate.setServerUri("policy.update.com");
        policyUpdate.setUsername("updateUser");
        policyUpdate.setBase64EncodedPassword("updatePass");
        policyUpdate.setTrustRootCertUrl("update.cert.com");
        policyUpdate.setTrustRootCertSha256Fingerprint(certFingerprint);
        policy.setPolicyUpdate(policyUpdate);
        config.setPolicy(policy);
        return config;
    }

    /**
     * Parse and verify all supported fields under PPS MO tree.
     *
     * @throws Exception
     */
    @Test
    public void parseValidPPSMOTree() throws Exception {
        String ppsMoTree = loadResourceFile(VALID_PPS_MO_XML_FILE);
        PasspointConfiguration expectedConfig = generateConfigurationFromPPSMOTree();
        PasspointConfiguration actualConfig = PpsMoParser.parseMoText(ppsMoTree);
        assertTrue(actualConfig.equals(expectedConfig));
    }

    @Test
    public void parseNullPPSMOTree() throws Exception {
        assertEquals(null, PpsMoParser.parseMoText(null));
    }

    @Test
    public void parseEmptyPPSMOTree() throws Exception {
        assertEquals(null, PpsMoParser.parseMoText(new String()));
    }

    @Test
    public void parsePPSMOTreeWithDuplicateHomeSP() throws Exception {
        assertEquals(null, PpsMoParser.parseMoText(
                loadResourceFile(PPS_MO_XML_FILE_DUPLICATE_HOMESP)));
    }

    @Test
    public void parsePPSMOTreeWithDuplicateValue() throws Exception {
        assertEquals(null, PpsMoParser.parseMoText(
                loadResourceFile(PPS_MO_XML_FILE_DUPLICATE_VALUE)));
    }

    @Test
    public void parsePPSMOTreeWithMissingValue() throws Exception {
        assertEquals(null, PpsMoParser.parseMoText(
                loadResourceFile(PPS_MO_XML_FILE_MISSING_VALUE)));
    }

    @Test
    public void parsePPSMOTreeWithMissingName() throws Exception {
        assertEquals(null, PpsMoParser.parseMoText(
                loadResourceFile(PPS_MO_XML_FILE_MISSING_NAME)));
    }

    @Test
    public void parsePPSMOTreeWithInvalidNode() throws Exception {
        assertEquals(null, PpsMoParser.parseMoText(
                loadResourceFile(PPS_MO_XML_FILE_INVALID_NODE)));
    }

    @Test
    public void parsePPSMOTreeWithInvalidName() throws Exception {
        assertEquals(null, PpsMoParser.parseMoText(
                loadResourceFile(PPS_MO_XML_FILE_INVALID_NAME)));
    }
}
