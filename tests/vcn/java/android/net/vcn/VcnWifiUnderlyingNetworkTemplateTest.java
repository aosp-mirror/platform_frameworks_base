/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.net.vcn;

import static android.net.vcn.VcnUnderlyingNetworkTemplate.NETWORK_QUALITY_ANY;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.NETWORK_QUALITY_OK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class VcnWifiUnderlyingNetworkTemplateTest {
    private static final String SSID = "TestWifi";
    private static final int INVALID_NETWORK_QUALITY = -1;

    // Package private for use in VcnGatewayConnectionConfigTest
    static VcnWifiUnderlyingNetworkTemplate getTestNetworkPriority() {
        return new VcnWifiUnderlyingNetworkTemplate.Builder()
                .setNetworkQuality(NETWORK_QUALITY_OK)
                .setAllowMetered(true /* allowMetered */)
                .setSsid(SSID)
                .build();
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnWifiUnderlyingNetworkTemplate networkPriority = getTestNetworkPriority();
        assertEquals(NETWORK_QUALITY_OK, networkPriority.getNetworkQuality());
        assertTrue(networkPriority.allowMetered());
        assertEquals(SSID, networkPriority.getSsid());
    }

    @Test
    public void testBuilderAndGettersForDefaultValues() {
        final VcnWifiUnderlyingNetworkTemplate networkPriority =
                new VcnWifiUnderlyingNetworkTemplate.Builder().build();
        assertEquals(NETWORK_QUALITY_ANY, networkPriority.getNetworkQuality());
        assertFalse(networkPriority.allowMetered());
        assertNull(SSID, networkPriority.getSsid());
    }

    @Test
    public void testBuildWithInvalidNetworkQuality() {
        try {
            new VcnWifiUnderlyingNetworkTemplate.Builder()
                    .setNetworkQuality(INVALID_NETWORK_QUALITY);
            fail("Expected to fail due to the invalid network quality");
        } catch (Exception expected) {
        }
    }

    @Test
    public void testPersistableBundle() {
        final VcnWifiUnderlyingNetworkTemplate networkPriority = getTestNetworkPriority();
        assertEquals(
                networkPriority,
                VcnUnderlyingNetworkTemplate.fromPersistableBundle(
                        networkPriority.toPersistableBundle()));
    }
}
