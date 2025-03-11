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

import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_ANY;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_FORBIDDEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnWifiUnderlyingNetworkTemplateTest extends VcnUnderlyingNetworkTemplateTestBase {
    private static final String SSID = "TestWifi";

    // Package private for use in VcnGatewayConnectionConfigTest
    static VcnWifiUnderlyingNetworkTemplate getTestNetworkTemplate() {
        return new VcnWifiUnderlyingNetworkTemplate.Builder()
                .setMetered(MATCH_FORBIDDEN)
                .setMinUpstreamBandwidthKbps(
                        TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS,
                        TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS)
                .setMinDownstreamBandwidthKbps(
                        TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS,
                        TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS)
                .setSsids(Set.of(SSID))
                .build();
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnWifiUnderlyingNetworkTemplate networkPriority = getTestNetworkTemplate();
        assertEquals(MATCH_FORBIDDEN, networkPriority.getMetered());
        assertEquals(
                TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS,
                networkPriority.getMinEntryUpstreamBandwidthKbps());
        assertEquals(
                TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                networkPriority.getMinExitUpstreamBandwidthKbps());
        assertEquals(
                TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS,
                networkPriority.getMinEntryDownstreamBandwidthKbps());
        assertEquals(
                TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                networkPriority.getMinExitDownstreamBandwidthKbps());
        assertEquals(Set.of(SSID), networkPriority.getSsids());
    }

    @Test
    public void testBuilderAndGettersForDefaultValues() {
        final VcnWifiUnderlyingNetworkTemplate networkPriority =
                new VcnWifiUnderlyingNetworkTemplate.Builder().build();
        assertEquals(MATCH_ANY, networkPriority.getMetered());

        // Explicitly expect 0, as documented in Javadoc on setter methods..
        assertEquals(0, networkPriority.getMinEntryUpstreamBandwidthKbps());
        assertEquals(0, networkPriority.getMinExitUpstreamBandwidthKbps());
        assertEquals(0, networkPriority.getMinEntryDownstreamBandwidthKbps());
        assertEquals(0, networkPriority.getMinExitDownstreamBandwidthKbps());

        assertTrue(networkPriority.getSsids().isEmpty());
    }

    @Test
    public void testBuilderRequiresStricterEntryCriteria() {
        try {
            new VcnWifiUnderlyingNetworkTemplate.Builder()
                    .setMinUpstreamBandwidthKbps(
                            TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                            TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS);

            fail("Expected IAE for exit threshold > entry threshold");
        } catch (IllegalArgumentException expected) {
        }

        try {
            new VcnWifiUnderlyingNetworkTemplate.Builder()
                    .setMinDownstreamBandwidthKbps(
                            TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                            TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS);

            fail("Expected IAE for exit threshold > entry threshold");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testPersistableBundle() {
        final VcnWifiUnderlyingNetworkTemplate networkPriority = getTestNetworkTemplate();
        assertEquals(
                networkPriority,
                VcnUnderlyingNetworkTemplate.fromPersistableBundle(
                        networkPriority.toPersistableBundle()));
    }
}
