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

import org.junit.Test;

/**
 * Unit test harness for {@link android.net.wifi.p2p.nsd.WifiP2pUpnpServiceRequest}
 */
@SmallTest
public class WifiP2pUpnpServiceRequestTest {

    @Test
    public void testNewInstance() throws Exception {
        WifiP2pUpnpServiceRequest request = null;

        // Create a service discovery request to search all UPnP services.
        request = WifiP2pUpnpServiceRequest.newInstance();

        // Create a service discovery request to search specified UPnP services.
        request = WifiP2pUpnpServiceRequest.newInstance("ssdp:all");

        // failure case due to null target string
        try {
            request = WifiP2pUpnpServiceRequest.newInstance(null);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }
    }
}
