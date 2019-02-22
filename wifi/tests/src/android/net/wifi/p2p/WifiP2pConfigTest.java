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

package android.net.wifi.p2p;

import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit test harness for {@link android.net.wifi.p2p.WifiP2pConfig}
 */
@SmallTest
public class WifiP2pConfigTest {
    /**
     * Check network name setter
     */
    @Test
    public void testBuilderInvalidNetworkName() throws Exception {
        WifiP2pConfig.Builder b = new WifiP2pConfig.Builder();

        // sunny case
        try {
            b.setNetworkName("DIRECT-ab-Hello");
        } catch (IllegalArgumentException e) {
            fail("Unexpected IllegalArgumentException");
        }

        // sunny case, no trailing string
        try {
            b.setNetworkName("DIRECT-WR");
        } catch (IllegalArgumentException e) {
            fail("Unexpected IllegalArgumentException");
        }

        // less than 9 characters.
        try {
            b.setNetworkName("DIRECT-z");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }

        // not starts with DIRECT-xy.
        try {
            b.setNetworkName("ABCDEFGHIJK");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }

        // not starts with uppercase DIRECT-xy
        try {
            b.setNetworkName("direct-ab");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }

        // x and y are not selected from upper case letters, lower case letters or
        // numbers.
        try {
            b.setNetworkName("direct-a?");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) { }
    }
}
