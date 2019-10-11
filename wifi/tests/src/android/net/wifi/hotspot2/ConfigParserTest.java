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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.wifi.FakeKeys;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.ConfigParser}.
 */
@SmallTest
public class ConfigParserTest {
    /**
     * Hotspot 2.0 Release 1 installation file that contains a Passpoint profile and a
     * CA (Certificate Authority) X.509 certificate {@link FakeKeys#CA_CERT0}.
     */
    private static final String PASSPOINT_INSTALLATION_FILE_WITH_CA_CERT =
            "assets/hsr1/HSR1ProfileWithCACert.base64";
    private static final String PASSPOINT_INSTALLATION_FILE_WITH_UNENCODED_DATA =
            "assets/hsr1/HSR1ProfileWithCACert.conf";
    private static final String PASSPOINT_INSTALLATION_FILE_WITH_INVALID_PART =
            "assets/hsr1/HSR1ProfileWithNonBase64Part.base64";
    private static final String PASSPOINT_INSTALLATION_FILE_WITH_MISSING_BOUNDARY =
            "assets/hsr1/HSR1ProfileWithMissingBoundary.base64";
    private static final String PASSPOINT_INSTALLATION_FILE_WITH_INVALID_CONTENT_TYPE =
            "assets/hsr1/HSR1ProfileWithInvalidContentType.base64";
    private static final String PASSPOINT_INSTALLATION_FILE_WITHOUT_PROFILE =
            "assets/hsr1/HSR1ProfileWithoutProfile.base64";

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
     * XML file {@link #PASSPOINT_INSTALLATION_FILE_WITH_CA_CERT}.
     *
     * @return {@link PasspointConfiguration}
     */
    private PasspointConfiguration generateConfigurationFromProfile() {
        PasspointConfiguration config = new PasspointConfiguration();

        // HomeSP configuration.
        HomeSp homeSp = new HomeSp();
        homeSp.setFriendlyName("Century House");
        homeSp.setFqdn("mi6.co.uk");
        homeSp.setRoamingConsortiumOis(new long[] {0x112233L, 0x445566L});
        config.setHomeSp(homeSp);

        // Credential configuration.
        Credential credential = new Credential();
        credential.setRealm("shaken.stirred.com");
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setUsername("james");
        userCredential.setPassword("Ym9uZDAwNw==");
        userCredential.setEapType(21);
        userCredential.setNonEapInnerMethod("MS-CHAP-V2");
        credential.setUserCredential(userCredential);
        Credential.CertificateCredential certCredential = new Credential.CertificateCredential();
        certCredential.setCertType("x509v3");
        byte[] certSha256Fingerprint = new byte[32];
        Arrays.fill(certSha256Fingerprint, (byte)0x1f);
        certCredential.setCertSha256Fingerprint(certSha256Fingerprint);
        credential.setCertCredential(certCredential);
        Credential.SimCredential simCredential = new Credential.SimCredential();
        simCredential.setImsi("imsi");
        simCredential.setEapType(24);
        credential.setSimCredential(simCredential);
        credential.setCaCertificate(FakeKeys.CA_CERT0);
        config.setCredential(credential);
        return config;
    }

    /**
     * Verify a valid installation file is parsed successfully with the matching contents.
     *
     * @throws Exception
     */
    @Test
    public void parseConfigFile() throws Exception {
        String configStr = loadResourceFile(PASSPOINT_INSTALLATION_FILE_WITH_CA_CERT);
        PasspointConfiguration expectedConfig = generateConfigurationFromProfile();
        PasspointConfiguration actualConfig =
                ConfigParser.parsePasspointConfig(
                        "application/x-wifi-config", configStr.getBytes());
        assertTrue(actualConfig.equals(expectedConfig));
    }

    /**
     * Verify that parsing an installation file with invalid MIME type will fail.
     *
     * @throws Exception
     */
    @Test
    public void parseConfigFileWithInvalidMimeType() throws Exception {
        String configStr = loadResourceFile(PASSPOINT_INSTALLATION_FILE_WITH_CA_CERT);
        assertNull(ConfigParser.parsePasspointConfig(
                "application/wifi-config", configStr.getBytes()));
    }

    /**
     * Verify that parsing an un-encoded installation file will fail.
     *
     * @throws Exception
     */
    @Test
    public void parseConfigFileWithUnencodedData() throws Exception {
        String configStr = loadResourceFile(PASSPOINT_INSTALLATION_FILE_WITH_UNENCODED_DATA);
        assertNull(ConfigParser.parsePasspointConfig(
                "application/x-wifi-config", configStr.getBytes()));
    }

    /**
     * Verify that parsing an installation file that contains a non-base64 part will fail.
     *
     * @throws Exception
     */
    @Test
    public void parseConfigFileWithInvalidPart() throws Exception {
        String configStr = loadResourceFile(PASSPOINT_INSTALLATION_FILE_WITH_INVALID_PART);
        assertNull(ConfigParser.parsePasspointConfig(
                "application/x-wifi-config", configStr.getBytes()));
    }

    /**
     * Verify that parsing an installation file that contains a missing boundary string will fail.
     *
     * @throws Exception
     */
    @Test
    public void parseConfigFileWithMissingBoundary() throws Exception {
        String configStr = loadResourceFile(PASSPOINT_INSTALLATION_FILE_WITH_MISSING_BOUNDARY);
        assertNull(ConfigParser.parsePasspointConfig(
                "application/x-wifi-config", configStr.getBytes()));
    }

    /**
     * Verify that parsing an installation file that contains a MIME part with an invalid content
     * type will fail.
     *
     * @throws Exception
     */
    @Test
    public void parseConfigFileWithInvalidContentType() throws Exception {
        String configStr = loadResourceFile(PASSPOINT_INSTALLATION_FILE_WITH_INVALID_CONTENT_TYPE);
        assertNull(ConfigParser.parsePasspointConfig(
                "application/x-wifi-config", configStr.getBytes()));
    }

    /**
     * Verify that parsing an installation file that doesn't contain a Passpoint profile will fail.
     *
     * @throws Exception
     */
    @Test
    public void parseConfigFileWithoutPasspointProfile() throws Exception {
        String configStr = loadResourceFile(PASSPOINT_INSTALLATION_FILE_WITHOUT_PROFILE);
        assertNull(ConfigParser.parsePasspointConfig(
                "application/x-wifi-config", configStr.getBytes()));
    }
}