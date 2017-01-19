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

import android.net.wifi.hotspot2.omadm.PPSMOParser;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSP;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;

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

/**
 * Unit tests for {@link android.net.wifi.hotspot2.omadm.PPSMOParser}.
 */
@SmallTest
public class PPSMOParserTest {
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

        PasspointConfiguration config = new PasspointConfiguration();
        config.updateIdentifier = 12;
        config.credentialPriority = 99;

        // AAA Server trust root.
        config.trustRootCertList = new HashMap<>();
        byte[] certFingerprint = new byte[32];
        Arrays.fill(certFingerprint, (byte) 0x1f);
        config.trustRootCertList.put("server1.trust.root.com", certFingerprint);

        // Subscription update.
        config.subscriptionUpdate = new UpdateParameter();
        config.subscriptionUpdate.updateIntervalInMinutes = 120;
        config.subscriptionUpdate.updateMethod = UpdateParameter.UPDATE_METHOD_SSP;
        config.subscriptionUpdate.restriction = UpdateParameter.UPDATE_RESTRICTION_ROAMING_PARTNER;
        config.subscriptionUpdate.serverUri = "subscription.update.com";
        config.subscriptionUpdate.username = "subscriptionUser";
        config.subscriptionUpdate.base64EncodedPassword = "subscriptionPass";
        config.subscriptionUpdate.trustRootCertUrl = "subscription.update.cert.com";
        config.subscriptionUpdate.trustRootCertSha256Fingerprint = new byte[32];
        Arrays.fill(config.subscriptionUpdate.trustRootCertSha256Fingerprint, (byte) 0x1f);

        // Subscription parameters.
        config.subscriptionCreationTimeInMs = format.parse("2016-02-01T10:00:00Z").getTime();
        config.subscriptionExpirationTimeInMs = format.parse("2016-03-01T10:00:00Z").getTime();
        config.subscriptionType = "Gold";
        config.usageLimitDataLimit = 921890;
        config.usageLimitStartTimeInMs = format.parse("2016-12-01T10:00:00Z").getTime();
        config.usageLimitTimeLimitInMinutes = 120;
        config.usageLimitUsageTimePeriodInMinutes = 99910;

        // HomeSP configuration.
        config.homeSp = new HomeSP();
        config.homeSp.friendlyName = "Century House";
        config.homeSp.fqdn = "mi6.co.uk";
        config.homeSp.roamingConsortiumOIs = new long[] {0x112233L, 0x445566L};
        config.homeSp.iconUrl = "icon.test.com";
        config.homeSp.homeNetworkIds = new HashMap<>();
        config.homeSp.homeNetworkIds.put("TestSSID", 0x12345678L);
        config.homeSp.homeNetworkIds.put("NullHESSID", null);
        config.homeSp.matchAllOIs = new long[] {0x11223344};
        config.homeSp.matchAnyOIs = new long[] {0x55667788};
        config.homeSp.otherHomePartners = new String[] {"other.fqdn.com"};

        // Credential configuration.
        config.credential = new Credential();
        config.credential.creationTimeInMs = format.parse("2016-01-01T10:00:00Z").getTime();
        config.credential.expirationTimeInMs = format.parse("2016-02-01T10:00:00Z").getTime();
        config.credential.realm = "shaken.stirred.com";
        config.credential.checkAAAServerCertStatus = true;
        config.credential.userCredential = new Credential.UserCredential();
        config.credential.userCredential.username = "james";
        config.credential.userCredential.password = "Ym9uZDAwNw==";
        config.credential.userCredential.machineManaged = true;
        config.credential.userCredential.softTokenApp = "TestApp";
        config.credential.userCredential.ableToShare = true;
        config.credential.userCredential.eapType = 21;
        config.credential.userCredential.nonEapInnerMethod = "MS-CHAP-V2";
        config.credential.certCredential = new Credential.CertificateCredential();
        config.credential.certCredential.certType = "x509v3";
        config.credential.certCredential.certSha256FingerPrint = new byte[32];
        Arrays.fill(config.credential.certCredential.certSha256FingerPrint, (byte)0x1f);
        config.credential.simCredential = new Credential.SimCredential();
        config.credential.simCredential.imsi = "imsi";
        config.credential.simCredential.eapType = 24;

        // Policy configuration.
        config.policy = new Policy();
        config.policy.preferredRoamingPartnerList = new ArrayList<>();
        Policy.RoamingPartner partner1 = new Policy.RoamingPartner();
        partner1.fqdn = "test1.fqdn.com";
        partner1.fqdnExactMatch = true;
        partner1.priority = 127;
        partner1.countries = "us,fr";
        Policy.RoamingPartner partner2 = new Policy.RoamingPartner();
        partner2.fqdn = "test2.fqdn.com";
        partner2.fqdnExactMatch = false;
        partner2.priority = 200;
        partner2.countries = "*";
        config.policy.preferredRoamingPartnerList.add(partner1);
        config.policy.preferredRoamingPartnerList.add(partner2);
        config.policy.minHomeDownlinkBandwidth = 23412;
        config.policy.minHomeUplinkBandwidth = 9823;
        config.policy.minRoamingDownlinkBandwidth = 9271;
        config.policy.minRoamingUplinkBandwidth = 2315;
        config.policy.excludedSsidList = new String[] {"excludeSSID"};
        config.policy.requiredProtoPortMap = new HashMap<>();
        config.policy.requiredProtoPortMap.put(12, "34,92,234");
        config.policy.maximumBssLoadValue = 23;
        config.policy.policyUpdate = new UpdateParameter();
        config.policy.policyUpdate.updateIntervalInMinutes = 120;
        config.policy.policyUpdate.updateMethod = UpdateParameter.UPDATE_METHOD_OMADM;
        config.policy.policyUpdate.restriction = UpdateParameter.UPDATE_RESTRICTION_HOMESP;
        config.policy.policyUpdate.serverUri = "policy.update.com";
        config.policy.policyUpdate.username = "updateUser";
        config.policy.policyUpdate.base64EncodedPassword = "updatePass";
        config.policy.policyUpdate.trustRootCertUrl = "update.cert.com";
        config.policy.policyUpdate.trustRootCertSha256Fingerprint = new byte[32];
        Arrays.fill(config.policy.policyUpdate.trustRootCertSha256Fingerprint, (byte) 0x1f);

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
        PasspointConfiguration actualConfig = PPSMOParser.parseMOText(ppsMoTree);
        assertTrue(actualConfig.equals(expectedConfig));
    }

    @Test
    public void parseNullPPSMOTree() throws Exception {
        assertEquals(null, PPSMOParser.parseMOText(null));
    }

    @Test
    public void parseEmptyPPSMOTree() throws Exception {
        assertEquals(null, PPSMOParser.parseMOText(new String()));
    }

    @Test
    public void parsePPSMOTreeWithDuplicateHomeSP() throws Exception {
        assertEquals(null, PPSMOParser.parseMOText(
                loadResourceFile(PPS_MO_XML_FILE_DUPLICATE_HOMESP)));
    }

    @Test
    public void parsePPSMOTreeWithDuplicateValue() throws Exception {
        assertEquals(null, PPSMOParser.parseMOText(
                loadResourceFile(PPS_MO_XML_FILE_DUPLICATE_VALUE)));
    }

    @Test
    public void parsePPSMOTreeWithMissingValue() throws Exception {
        assertEquals(null, PPSMOParser.parseMOText(
                loadResourceFile(PPS_MO_XML_FILE_MISSING_VALUE)));
    }

    @Test
    public void parsePPSMOTreeWithMissingName() throws Exception {
        assertEquals(null, PPSMOParser.parseMOText(
                loadResourceFile(PPS_MO_XML_FILE_MISSING_NAME)));
    }

    @Test
    public void parsePPSMOTreeWithInvalidNode() throws Exception {
        assertEquals(null, PPSMOParser.parseMOText(
                loadResourceFile(PPS_MO_XML_FILE_INVALID_NODE)));
    }

    @Test
    public void parsePPSMOTreeWithInvalidName() throws Exception {
        assertEquals(null, PPSMOParser.parseMOText(
                loadResourceFile(PPS_MO_XML_FILE_INVALID_NAME)));
    }
}







