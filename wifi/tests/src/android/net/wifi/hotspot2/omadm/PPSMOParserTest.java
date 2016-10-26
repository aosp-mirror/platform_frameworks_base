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
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;

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
    private PasspointConfiguration generateConfigurationFromPPSMOTree() {
        PasspointConfiguration config = new PasspointConfiguration();

        // HomeSP configuration.
        config.homeSp = new HomeSP();
        config.homeSp.friendlyName = "Century House";
        config.homeSp.fqdn = "mi6.co.uk";
        config.homeSp.roamingConsortiumOIs = new long[] {0x112233L, 0x445566L};

        // Credential configuration.
        config.credential = new Credential();
        config.credential.realm = "shaken.stirred.com";
        config.credential.userCredential = new Credential.UserCredential();
        config.credential.userCredential.username = "james";
        config.credential.userCredential.password = "Ym9uZDAwNw==";
        config.credential.userCredential.eapType = 21;
        config.credential.userCredential.nonEapInnerMethod = "MS-CHAP-V2";
        config.credential.certCredential = new Credential.CertificateCredential();
        config.credential.certCredential.certType = "x509v3";
        config.credential.certCredential.certSha256FingerPrint = new byte[32];
        Arrays.fill(config.credential.certCredential.certSha256FingerPrint, (byte)0x1f);
        config.credential.simCredential = new Credential.SimCredential();
        config.credential.simCredential.imsi = "imsi";
        config.credential.simCredential.eapType = 24;
        return config;
    }

    /**
     * Parse and verify all supported fields under PPS MO tree (currently only fields under
     * HomeSP and Credential subtree).
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







