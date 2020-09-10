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

package android.net.wifi.hotspot2;

import android.net.wifi.EAPConstants;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.util.Base64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PasspointTestUtils {
    private static final int CERTIFICATE_FINGERPRINT_BYTES = 32;

    /**
     * Utility function for creating a {@link android.net.wifi.hotspot2.pps.HomeSP}.
     *
     * @return {@link android.net.wifi.hotspot2.pps.HomeSP}
     */
    private static HomeSp createHomeSp() {
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("fqdn");
        homeSp.setFriendlyName("friendly name");
        homeSp.setRoamingConsortiumOis(new long[] {0x55, 0x66});
        return homeSp;
    }

    /**
     * Utility function for creating a {@link android.net.wifi.hotspot2.pps.Credential}.
     *
     * @return {@link android.net.wifi.hotspot2.pps.Credential}
     */
    private static Credential createCredential() {
        Credential cred = new Credential();
        cred.setRealm("realm");
        cred.setUserCredential(null);
        cred.setCertCredential(null);
        cred.setSimCredential(new Credential.SimCredential());
        cred.getSimCredential().setImsi("1234*");
        cred.getSimCredential().setEapType(EAPConstants.EAP_SIM);
        cred.setCaCertificate(null);
        cred.setClientCertificateChain(null);
        cred.setClientPrivateKey(null);
        return cred;
    }

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
        policy.setMaximumBssLoadValue(12);
        policy.setExcludedSsidList(new String[] {"ssid1", "ssid2"});
        HashMap<Integer, String> requiredProtoPortMap = new HashMap<>();
        requiredProtoPortMap.put(12, "23,342,123");
        requiredProtoPortMap.put(23, "789,372,1235");
        policy.setRequiredProtoPortMap(requiredProtoPortMap);

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
        policyUpdate.setTrustRootCertSha256Fingerprint(
                new byte[CERTIFICATE_FINGERPRINT_BYTES]);
        policy.setPolicyUpdate(policyUpdate);

        return policy;
    }

    private static UpdateParameter createSubscriptionUpdate() {
        UpdateParameter subUpdate = new UpdateParameter();
        subUpdate.setUpdateIntervalInMinutes(9021);
        subUpdate.setUpdateMethod(UpdateParameter.UPDATE_METHOD_SSP);
        subUpdate.setRestriction(UpdateParameter.UPDATE_RESTRICTION_ROAMING_PARTNER);
        subUpdate.setServerUri("subscription.update.com");
        subUpdate.setUsername("subUsername");
        subUpdate.setBase64EncodedPassword(
                Base64.encodeToString("subPassword".getBytes(), Base64.DEFAULT));
        subUpdate.setTrustRootCertUrl("subscription.trust.cert.com");
        subUpdate.setTrustRootCertSha256Fingerprint(new byte[CERTIFICATE_FINGERPRINT_BYTES]);
        return subUpdate;
    }
    /**
     * Helper function for creating a {@link PasspointConfiguration} for testing.
     *
     * @return {@link PasspointConfiguration}
     */
    public static PasspointConfiguration createConfig() {
        PasspointConfiguration config = new PasspointConfiguration();
        config.setHomeSp(createHomeSp());
        config.setAaaServerTrustedNames(
                new String[] {"trusted.fqdn.com", "another-trusted.fqdn.com"});
        config.setCredential(createCredential());
        config.setPolicy(createPolicy());
        config.setSubscriptionUpdate(createSubscriptionUpdate());
        Map<String, byte[]> trustRootCertList = new HashMap<>();
        trustRootCertList.put("trustRoot.cert1.com",
                new byte[CERTIFICATE_FINGERPRINT_BYTES]);
        trustRootCertList.put("trustRoot.cert2.com",
                new byte[CERTIFICATE_FINGERPRINT_BYTES]);
        config.setTrustRootCertList(trustRootCertList);
        config.setCredentialPriority(120);
        config.setSubscriptionCreationTimeInMillis(231200);
        config.setSubscriptionExpirationTimeInMillis(2134232);
        config.setSubscriptionType("Gold");
        config.setUsageLimitUsageTimePeriodInMinutes(3600);
        config.setUsageLimitStartTimeInMillis(124214213);
        config.setUsageLimitDataLimit(14121);
        config.setUsageLimitTimeLimitInMinutes(78912);
        Map<String, String> friendlyNames = new HashMap<>();
        friendlyNames.put("en", "ServiceName1");
        friendlyNames.put("kr", "ServiceName2");
        config.setServiceFriendlyNames(friendlyNames);
        return config;
    }

    /**
     * Helper function for creating an R2 {@link PasspointConfiguration} for testing.
     *
     * @return {@link PasspointConfiguration}
     */
    public static PasspointConfiguration createR2Config() {
        PasspointConfiguration config = createConfig();
        config.setUpdateIdentifier(1234);
        return config;
    }
}
