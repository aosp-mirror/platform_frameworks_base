/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit test harness for {@link android.net.wifi.p2p.WifiP2pDevice}
 */
public class WifiP2pDeviceTest {

    /**
     * Check equals and hashCode consistency
     */
    @Test
    public void testEqualsWithHashCode() throws Exception {
        WifiP2pDevice dev_a = new WifiP2pDevice();
        dev_a.deviceAddress = new String("02:90:4c:a0:92:54");
        WifiP2pDevice dev_b = new WifiP2pDevice();
        dev_b.deviceAddress = new String("02:90:4c:a0:92:54");

        assertTrue(dev_a.equals(dev_b));
        assertEquals(dev_a.hashCode(), dev_b.hashCode());
    }
}
