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

import java.util.HashMap;
import java.util.Map;

/**
 * Unit test harness for {@link android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo}
 */
@SmallTest
public class WifiP2pDnsSdServiceInfoTest {

    private static final String INSTANCE_NAME = "MyPrinter";
    private static final String SERVICE_TYPE = "_ipp._tcp";
    private static final String TXTRECORD_PROP_AVAILABLE = "available";
    private static final String TXTRECORD_PROP_AVAILABLE_VISABLE = "visable";

    private Map<String, String> mTxtMap = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        mTxtMap.put(TXTRECORD_PROP_AVAILABLE, TXTRECORD_PROP_AVAILABLE_VISABLE);
    }

    /**
     * Verify newInstance API
     */
    @Test
    public void testNewInstance() throws Exception {
        WifiP2pDnsSdServiceInfo info = null;

        // the least arguments
        info = WifiP2pDnsSdServiceInfo.newInstance(
                INSTANCE_NAME,
                SERVICE_TYPE,
                null);

        // all arguments are given.
        info = WifiP2pDnsSdServiceInfo.newInstance(
                INSTANCE_NAME,
                SERVICE_TYPE,
                mTxtMap);

        // failure case due to no instance name.
        try {
            info = WifiP2pDnsSdServiceInfo.newInstance(
                    null,
                    SERVICE_TYPE,
                    null);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }

        // failure case due to no service type.
        try {
            info = WifiP2pDnsSdServiceInfo.newInstance(
                    INSTANCE_NAME,
                    null,
                    null);
            fail("should throw IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // expected exception.
        }
    }
}
