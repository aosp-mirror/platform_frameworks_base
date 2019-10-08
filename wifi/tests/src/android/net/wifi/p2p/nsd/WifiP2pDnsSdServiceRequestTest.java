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
 * Unit test harness for {@link android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest}
 */
@SmallTest
public class WifiP2pDnsSdServiceRequestTest {

    private static final String SERVICE_NAME = "MyPrinter";
    private static final String SERVICE_TYPE = "_ipp._tcp";

    @Test
    public void testNewInstance() throws Exception {
        WifiP2pDnsSdServiceRequest request = null;

        // default new instance
        request = WifiP2pDnsSdServiceRequest.newInstance();

        // set service type
        request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE);

        // set service type
        request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_NAME, SERVICE_TYPE);

        // failure case due to null service type
        try {
            request = WifiP2pDnsSdServiceRequest.newInstance(null);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }

        // failure case due to null service name
        try {
            request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_NAME, null);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }

        // failure case due to null service type
        try {
            request = WifiP2pDnsSdServiceRequest.newInstance(null, SERVICE_TYPE);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }

    }
}
