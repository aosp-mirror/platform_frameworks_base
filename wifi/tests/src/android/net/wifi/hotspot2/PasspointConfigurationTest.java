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
import android.net.wifi.hotspot2.pps.HomeSP;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Base64;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.PasspointConfiguration}.
 */
@SmallTest
public class PasspointConfigurationTest {

    /**
     * Utility function for creating a {@link android.net.wifi.hotspot2.pps.HomeSP}.
     *
     * @return {@link android.net.wifi.hotspot2.pps.HomeSP}
     */
    private static HomeSP createHomeSp() {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        homeSp.friendlyName = "friendly name";
        homeSp.roamingConsortiumOIs = new long[] {0x55, 0x66};
        return homeSp;
    }

    /**
     * Utility function for creating a {@link android.net.wifi.hotspot2.pps.Credential}.
     *
     * @return {@link android.net.wifi.hotspot2.pps.Credential}
     */
    private static Credential createCredential() {
        Credential cred = new Credential();
        cred.realm = "realm";
        cred.userCredential = null;
        cred.certCredential = null;
        cred.simCredential = new Credential.SimCredential();
        cred.simCredential.imsi = "1234*";
        cred.simCredential.eapType = EAPConstants.EAP_SIM;
        cred.caCertificate = null;
        cred.clientCertificateChain = null;
        cred.clientPrivateKey = null;
        return cred;
    }

    /**
     * Helper function for creating a {@link Policy} for testing.
     *
     * @return {@link Policy}
     */
    private static Policy createPolicy() {
        Policy policy = new Policy();
        policy.minHomeDownlinkBandwidth = 123;
        policy.minHomeUplinkBandwidth = 345;
        policy.minRoamingDownlinkBandwidth = 567;
        policy.minRoamingUplinkBandwidth = 789;
        policy.maximumBssLoadValue = 12;
        policy.excludedSsidList = new String[] {"ssid1", "ssid2"};
        policy.requiredProtoPortMap = new HashMap<>();
        policy.requiredProtoPortMap.put(12, "23,342,123");
        policy.requiredProtoPortMap.put(23, "789,372,1235");

        policy.preferredRoamingPartnerList = new ArrayList<>();
        Policy.RoamingPartner partner1 = new Policy.RoamingPartner();
        partner1.fqdn = "partner1.com";
        partner1.fqdnExactMatch = true;
        partner1.priority = 12;
        partner1.countries = "us,jp";
        Policy.RoamingPartner partner2 = new Policy.RoamingPartner();
        partner2.fqdn = "partner2.com";
        partner2.fqdnExactMatch = false;
        partner2.priority = 42;
        partner2.countries = "ca,fr";
        policy.preferredRoamingPartnerList.add(partner1);
        policy.preferredRoamingPartnerList.add(partner2);

        policy.policyUpdate = new UpdateParameter();
        policy.policyUpdate.updateIntervalInMinutes = 1712;
        policy.policyUpdate.updateMethod = UpdateParameter.UPDATE_METHOD_OMADM;
        policy.policyUpdate.restriction = UpdateParameter.UPDATE_RESTRICTION_HOMESP;
        policy.policyUpdate.serverUri = "policy.update.com";
        policy.policyUpdate.username = "username";
        policy.policyUpdate.base64EncodedPassword =
                Base64.encodeToString("password".getBytes(), Base64.DEFAULT);
        policy.policyUpdate.trustRootCertUrl = "trust.cert.com";
        policy.policyUpdate.trustRootCertSha256Fingerprint = new byte[32];

        return policy;
    }

    /**
     * Helper function for creating a {@link PasspointConfiguration} for testing.
     *
     * @return {@link PasspointConfiguration}
     */
    private static PasspointConfiguration createConfig() {
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = createHomeSp();
        config.credential = createCredential();
        config.policy = createPolicy();
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
        config.homeSp = null;
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
        config.credential = null;
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
        config.policy = null;
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
        config.credential = null;
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
        config.homeSp = null;
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
        config.policy = null;
        assertTrue(config.validate());
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
