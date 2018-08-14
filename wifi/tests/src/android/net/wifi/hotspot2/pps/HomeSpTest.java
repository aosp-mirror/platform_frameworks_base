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
import android.support.test.filters.SmallTest;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.pps.HomeSp}.
 */
@SmallTest
public class HomeSpTest {

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
     * Helper function for creating a HomeSp for testing.
     *
     * @param homeNetworkIds The map of home network IDs associated with HomeSp
     * @return {@link HomeSp}
     */
    private static HomeSp createHomeSp(Map<String, Long> homeNetworkIds) {
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("fqdn");
        homeSp.setFriendlyName("friendly name");
        homeSp.setIconUrl("icon.url");
        homeSp.setHomeNetworkIds(homeNetworkIds);
        homeSp.setMatchAllOis(new long[] {0x11L, 0x22L});
        homeSp.setMatchAnyOis(new long[] {0x33L, 0x44L});
        homeSp.setOtherHomePartners(new String[] {"partner1", "partner2"});
        homeSp.setRoamingConsortiumOis(new long[] {0x55, 0x66});
        return homeSp;
    }

    /**
     * Helper function for creating a HomeSp with home network IDs for testing.
     *
     * @return {@link HomeSp}
     */
    private static HomeSp createHomeSpWithHomeNetworkIds() {
        return createHomeSp(createHomeNetworkIds());
    }

    /**
     * Helper function for creating a HomeSp without home network IDs for testing.
     *
     * @return {@link HomeSp}
     */
    private static HomeSp createHomeSpWithoutHomeNetworkIds() {
        return createHomeSp(null);
    }

    /**
     * Helper function for verifying HomeSp after parcel write then read.
     * @param writeHomeSp
     * @throws Exception
     */
    private static void verifyParcel(HomeSp writeHomeSp) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeHomeSp.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        HomeSp readHomeSp = HomeSp.CREATOR.createFromParcel(parcel);
        assertTrue(readHomeSp.equals(writeHomeSp));
    }

    /**
     * Verify parcel read/write for an empty HomeSp.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithEmptyHomeSp() throws Exception {
        verifyParcel(new HomeSp());
    }

    /**
     * Verify parcel read/write for a HomeSp containing Home Network IDs.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithHomeNetworkIds() throws Exception {
        verifyParcel(createHomeSpWithHomeNetworkIds());
    }

    /**
     * Verify parcel read/write for a HomeSp without Home Network IDs.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithoutHomeNetworkIds() throws Exception {
        verifyParcel(createHomeSpWithoutHomeNetworkIds());
    }

    /**
     * Verify that a HomeSp is valid when both FQDN and Friendly Name
     * are provided.
     *
     * @throws Exception
     */
    @Test
    public void validateValidHomeSp() throws Exception {
        HomeSp homeSp = createHomeSpWithHomeNetworkIds();
        assertTrue(homeSp.validate());
    }

    /**
     * Verify that a HomeSp is not valid when FQDN is not provided
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithoutFqdn() throws Exception {
        HomeSp homeSp = createHomeSpWithHomeNetworkIds();
        homeSp.setFqdn(null);
        assertFalse(homeSp.validate());
    }

    /**
     * Verify that a HomeSp is not valid when Friendly Name is not provided
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithoutFriendlyName() throws Exception {
        HomeSp homeSp = createHomeSpWithHomeNetworkIds();
        homeSp.setFriendlyName(null);
        assertFalse(homeSp.validate());
    }

    /**
     * Verify that a HomeSp is valid when the optional Home Network IDs are
     * not provided.
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithoutHomeNetworkIds() throws Exception {
        HomeSp homeSp = createHomeSpWithoutHomeNetworkIds();
        assertTrue(homeSp.validate());
    }

    /**
     * Verify that a HomeSp is invalid when the optional Home Network IDs
     * contained an invalid SSID (exceeding maximum number of bytes).
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithInvalidHomeNetworkIds() throws Exception {
        HomeSp homeSp = createHomeSpWithoutHomeNetworkIds();
        // HomeNetworkID with SSID exceeding the maximum length.
        Map<String, Long> homeNetworkIds = new HashMap<>();
        byte[] rawSsidBytes = new byte[33];
        Arrays.fill(rawSsidBytes, (byte) 'a');
        homeNetworkIds.put(
                StringFactory.newStringFromBytes(rawSsidBytes, StandardCharsets.UTF_8), 0x1234L);
        homeSp.setHomeNetworkIds(homeNetworkIds);
        assertFalse(homeSp.validate());
    }

    /**
     * Verify that copy constructor works when pass in a null source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorFromNullSource() throws Exception {
        HomeSp copySp = new HomeSp(null);
        HomeSp defaultSp = new HomeSp();
        assertTrue(copySp.equals(defaultSp));
    }

    /**
     * Verify that copy constructor works when pass in a valid source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorFromValidSource() throws Exception {
        HomeSp sourceSp = createHomeSpWithHomeNetworkIds();
        HomeSp copySp = new HomeSp(sourceSp);
        assertTrue(copySp.equals(sourceSp));
    }
}
