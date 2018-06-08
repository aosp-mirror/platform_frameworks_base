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
 * limitations under the License
 */

package android.net.wifi.hotspot2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.wifi.EAPConstants;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.util.Base64;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.PasspointConfiguration}.
 */
@SmallTest
public class PasspointConfigurationTest {
    private static final int MAX_URL_BYTES = 1023;
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
    private static PasspointConfiguration createConfig() {
        PasspointConfiguration config = new PasspointConfiguration();
        config.setHomeSp(createHomeSp());
        config.setCredential(createCredential());
        config.setPolicy(createPolicy());
        config.setSubscriptionUpdate(createSubscriptionUpdate());
        Map<String, byte[]> trustRootCertList = new HashMap<>();
        trustRootCertList.put("trustRoot.cert1.com",
                new byte[CERTIFICATE_FINGERPRINT_BYTES]);
        trustRootCertList.put("trustRoot.cert2.com",
                new byte[CERTIFICATE_FINGERPRINT_BYTES]);
        config.setTrustRootCertList(trustRootCertList);
        config.setUpdateIdentifier(1);
        config.setCredentialPriority(120);
        config.setSubscriptionCreationTimeInMillis(231200);
        config.setSubscriptionExpirationTimeInMillis(2134232);
        config.setSubscriptionType("Gold");
        config.setUsageLimitUsageTimePeriodInMinutes(3600);
        config.setUsageLimitStartTimeInMillis(124214213);
        config.setUsageLimitDataLimit(14121);
        config.setUsageLimitTimeLimitInMinutes(78912);
        return config;
    }

    /**
     * Verify parcel write and read consistency for the given configuration.
     *
     * @param writeConfig The configuration to verify
     * @throws Exception
     */
    private static void verifyParcel(PasspointConfiguration writeConfig) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeConfig.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        PasspointConfiguration readConfig =
                PasspointConfiguration.CREATOR.createFromParcel(parcel);
        assertTrue(readConfig.equals(writeConfig));
    }

    /**
     * Verify parcel read/write for a default configuration.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithDefault() throws Exception {
        verifyParcel(new PasspointConfiguration());
    }

    /**
     * Verify parcel read/write for a configuration that contained the full configuration.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithFullConfiguration() throws Exception {
        verifyParcel(createConfig());
    }

    /**
     * Verify parcel read/write for a configuration that doesn't contain HomeSP.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutHomeSP() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setHomeSp(null);
        verifyParcel(config);
    }

    /**
     * Verify parcel read/write for a configuration that doesn't contain Credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutCredential() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setCredential(null);
        verifyParcel(config);
    }

    /**
     * Verify parcel read/write for a configuration that doesn't contain Policy.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutPolicy() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setPolicy(null);
        verifyParcel(config);
    }

    /**
     * Verify parcel read/write for a configuration that doesn't contain subscription update.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutSubscriptionUpdate() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setSubscriptionUpdate(null);
        verifyParcel(config);
    }

    /**
     * Verify parcel read/write for a configuration that doesn't contain trust root certificate
     * list.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutTrustRootCertList() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setTrustRootCertList(null);
        verifyParcel(config);
    }

    /**
     * Verify that a default/empty configuration is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateDefaultConfig() throws Exception {
        PasspointConfiguration config = new PasspointConfiguration();
        assertFalse(config.validate());
    }

    /**
     * Verify that a configuration contained all fields is valid.
     *
     * @throws Exception
     */
    @Test
    public void validateFullConfig() throws Exception {
        PasspointConfiguration config = createConfig();
        assertTrue(config.validate());
    }

    /**
     * Verify that a configuration without Credential is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateConfigWithoutCredential() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setCredential(null);
        assertFalse(config.validate());
    }

    /**
     * Verify that a configuration without HomeSP is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateConfigWithoutHomeSp() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setHomeSp(null);
        assertFalse(config.validate());
    }

    /**
     * Verify that a configuration without Policy is valid, since Policy configurations
     * are optional (applied for Hotspot 2.0 Release only).
     *
     * @throws Exception
     */
    @Test
    public void validateConfigWithoutPolicy() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setPolicy(null);
        assertTrue(config.validate());
    }

    /**
     * Verify that a configuration without subscription update is valid, since subscription
     * update configurations are optional (applied for Hotspot 2.0 Release only).
     *
     * @throws Exception
     */
    @Test
    public void validateConfigWithoutSubscriptionUpdate() throws Exception {
        PasspointConfiguration config = createConfig();
        config.setSubscriptionUpdate(null);
        assertTrue(config.validate());
    }

    /**
     * Verify that a configuration with a trust root certificate URL exceeding the max size
     * is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateConfigWithInvalidTrustRootCertUrl() throws Exception {
        PasspointConfiguration config = createConfig();
        byte[] rawUrlBytes = new byte[MAX_URL_BYTES + 1];
        Map<String, byte[]> trustRootCertList = new HashMap<>();
        Arrays.fill(rawUrlBytes, (byte) 'a');
        trustRootCertList.put(new String(rawUrlBytes, StandardCharsets.UTF_8),
                new byte[CERTIFICATE_FINGERPRINT_BYTES]);
        config.setTrustRootCertList(trustRootCertList);
        assertFalse(config.validate());

        trustRootCertList = new HashMap<>();
        trustRootCertList.put(null, new byte[CERTIFICATE_FINGERPRINT_BYTES]);
        config.setTrustRootCertList(trustRootCertList);
        assertFalse(config.validate());
    }

    /**
     * Verify that a configuration with an invalid trust root certificate fingerprint is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateConfigWithInvalidTrustRootCertFingerprint() throws Exception {
        PasspointConfiguration config = createConfig();
        Map<String, byte[]> trustRootCertList = new HashMap<>();
        trustRootCertList.put("test.cert.com", new byte[CERTIFICATE_FINGERPRINT_BYTES + 1]);
        config.setTrustRootCertList(trustRootCertList);
        assertFalse(config.validate());

        trustRootCertList = new HashMap<>();
        trustRootCertList.put("test.cert.com", new byte[CERTIFICATE_FINGERPRINT_BYTES - 1]);
        config.setTrustRootCertList(trustRootCertList);
        assertFalse(config.validate());

        trustRootCertList = new HashMap<>();
        trustRootCertList.put("test.cert.com", null);
        config.setTrustRootCertList(trustRootCertList);
        assertFalse(config.validate());
    }

    /**
     * Verify that copy constructor works when pass in a null source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithNullSource() throws Exception {
        PasspointConfiguration copyConfig = new PasspointConfiguration(null);
        PasspointConfiguration defaultConfig = new PasspointConfiguration();
        assertTrue(copyConfig.equals(defaultConfig));
    }

    /**
     * Verify that copy constructor works when pass in a valid source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithValidSource() throws Exception {
        PasspointConfiguration sourceConfig = createConfig();
        PasspointConfiguration copyConfig = new PasspointConfiguration(sourceConfig);
        assertTrue(copyConfig.equals(sourceConfig));
    }
}
