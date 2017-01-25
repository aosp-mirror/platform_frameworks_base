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

package android.net.wifi.hotspot2.pps;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.pps.HomeSP}.
 */
@SmallTest
public class HomeSPTest {

    /**
     * Helper function for creating a map of home network IDs for testing.
     *
     * @return Map of home network IDs
     */
    private static Map<String, Long> createHomeNetworkIds() {
        Map<String, Long> homeNetworkIds = new HashMap<>();
        homeNetworkIds.put("ssid", 0x1234L);
        homeNetworkIds.put("nullhessid", null);
        return homeNetworkIds;
    }

    /**
     * Helper function for creating a HomeSP for testing.
     *
     * @param homeNetworkIds The map of home network IDs associated with HomeSP
     * @return {@link HomeSP}
     */
    private static HomeSP createHomeSp(Map<String, Long> homeNetworkIds) {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        homeSp.friendlyName = "friendly name";
        homeSp.iconUrl = "icon.url";
        homeSp.homeNetworkIds = homeNetworkIds;
        homeSp.matchAllOIs = new long[] {0x11L, 0x22L};
        homeSp.matchAnyOIs = new long[] {0x33L, 0x44L};
        homeSp.otherHomePartners = new String[] {"partner1", "partner2"};
        homeSp.roamingConsortiumOIs = new long[] {0x55, 0x66};
        return homeSp;
    }

    /**
     * Helper function for creating a HomeSP with home network IDs for testing.
     *
     * @return {@link HomeSP}
     */
    private static HomeSP createHomeSpWithHomeNetworkIds() {
        return createHomeSp(createHomeNetworkIds());
    }

    /**
     * Helper function for creating a HomeSP without home network IDs for testing.
     *
     * @return {@link HomeSP}
     */
    private static HomeSP createHomeSpWithoutHomeNetworkIds() {
        return createHomeSp(null);
    }

    /**
     * Helper function for verifying HomeSP after parcel write then read.
     * @param writeHomeSp
     * @throws Exception
     */
    private static void verifyParcel(HomeSP writeHomeSp) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeHomeSp.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        HomeSP readHomeSp = HomeSP.CREATOR.createFromParcel(parcel);
        assertTrue(readHomeSp.equals(writeHomeSp));
    }

    /**
     * Verify parcel read/write for an empty HomeSP.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithEmptyHomeSP() throws Exception {
        verifyParcel(new HomeSP());
    }

    /**
     * Verify parcel read/write for a HomeSP containing Home Network IDs.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithHomeNetworkIds() throws Exception {
        verifyParcel(createHomeSpWithHomeNetworkIds());
    }

    /**
     * Verify parcel read/write for a HomeSP without Home Network IDs.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutHomeNetworkIds() throws Exception {
        verifyParcel(createHomeSpWithoutHomeNetworkIds());
    }

    /**
     * Verify that a HomeSP is valid when both FQDN and Friendly Name
     * are provided.
     *
     * @throws Exception
     */
    @Test
    public void validateValidHomeSP() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        homeSp.friendlyName = "friendly name";
        assertTrue(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is not valid when FQDN is not provided
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithoutFqdn() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.friendlyName = "friendly name";
        assertFalse(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is not valid when Friendly Name is not provided
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithoutFriendlyName() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        assertFalse(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is valid when the optional Roaming Consortium OIs are
     * provided.
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithRoamingConsoritums() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        homeSp.friendlyName = "friendly name";
        homeSp.roamingConsortiumOIs = new long[] {0x55, 0x66};
        assertTrue(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is valid when the optional Home Network IDs are
     * provided.
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithHomeNetworkIds() throws Exception {
        HomeSP homeSp = createHomeSpWithHomeNetworkIds();
        assertTrue(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is valid when the optional Home Network IDs are
     * not provided.
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithoutHomeNetworkIds() throws Exception {
        HomeSP homeSp = createHomeSpWithoutHomeNetworkIds();
        assertTrue(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is invalid when the optional Home Network IDs
     * contained an invalid SSID (exceeding maximum number of bytes).
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithInvalidHomeNetworkIds() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        homeSp.friendlyName = "friendly name";
        homeSp.homeNetworkIds = new HashMap<>();
        byte[] rawSsidBytes = new byte[33];
        Arrays.fill(rawSsidBytes, (byte) 'a');
        homeSp.homeNetworkIds.put(
                StringFactory.newStringFromBytes(rawSsidBytes, StandardCharsets.UTF_8), 0x1234L);
        assertFalse(homeSp.validate());
    }

    /**
     * Verify that copy constructor works when pass in a null source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorFromNullSource() throws Exception {
        HomeSP copySp = new HomeSP(null);
        HomeSP defaultSp = new HomeSP();
        assertTrue(copySp.equals(defaultSp));
    }

    /**
     * Verify that copy constructor works when pass in a valid source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorFromValidSource() throws Exception {
        HomeSP sourceSp = createHomeSpWithHomeNetworkIds();
        HomeSP copySp = new HomeSP(sourceSp);
        assertTrue(copySp.equals(sourceSp));
    }
}
