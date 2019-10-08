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

package android.net.wifi.p2p.nsd;

import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit test harness for {@link android.net.wifi.p2p.nsd.WifiP2pUpnpServiceInfo}
 */
@SmallTest
public class WifiP2pUpnpServiceInfoTest {

    private static final String UUID = "6859dede-8574-59ab-9332-123456789012";
    private static final String DEVICE = "aa:bb:cc:dd:ee:ff";

    private List<String> mServiceList = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        mServiceList.add("urn:schemas-upnp-org:service:ContentDirectory:1");
    }

    /**
     * Verify newInstance API
     */
    @Test
    public void testNewInstance() throws Exception {
        WifiP2pUpnpServiceInfo info = null;

        // the least arguments
        info = WifiP2pUpnpServiceInfo.newInstance(
                UUID, DEVICE, null);

        // all arguments are given.
        info = WifiP2pUpnpServiceInfo.newInstance(
                UUID, DEVICE, mServiceList);

        // failure case due to no UUID.
        try {
            info = WifiP2pUpnpServiceInfo.newInstance(
                    null, DEVICE, null);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }

        // failure case due to no device.
        try {
            info = WifiP2pUpnpServiceInfo.newInstance(
                    UUID,
                    null,
                    null);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }
    }
}
