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
import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_REQUIRED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class VcnCellUnderlyingNetworkTemplateTest extends VcnUnderlyingNetworkTemplateTestBase {
    private static final Set<String> ALLOWED_PLMN_IDS = new HashSet<>();
    private static final Set<Integer> ALLOWED_CARRIER_IDS = new HashSet<>();

    // Package private for use in VcnGatewayConnectionConfigTest
    static VcnCellUnderlyingNetworkTemplate getTestNetworkTemplate() {
        return new VcnCellUnderlyingNetworkTemplate.Builder()
                .setMetered(MATCH_FORBIDDEN)
                .setMinUpstreamBandwidthKbps(
                        TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS,
                        TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS)
                .setMinDownstreamBandwidthKbps(
                        TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS,
                        TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS)
                .setOperatorPlmnIds(ALLOWED_PLMN_IDS)
                .setSimSpecificCarrierIds(ALLOWED_CARRIER_IDS)
                .setRoaming(MATCH_FORBIDDEN)
                .setOpportunistic(MATCH_REQUIRED)
                .build();
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnCellUnderlyingNetworkTemplate networkPriority = getTestNetworkTemplate();
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
        assertEquals(ALLOWED_PLMN_IDS, networkPriority.getOperatorPlmnIds());
        assertEquals(ALLOWED_CARRIER_IDS, networkPriority.getSimSpecificCarrierIds());
        assertEquals(MATCH_FORBIDDEN, networkPriority.getRoaming());
        assertEquals(MATCH_REQUIRED, networkPriority.getOpportunistic());
    }

    @Test
    public void testBuilderAndGettersForDefaultValues() {
        final VcnCellUnderlyingNetworkTemplate networkPriority =
                new VcnCellUnderlyingNetworkTemplate.Builder().build();
        assertEquals(MATCH_ANY, networkPriority.getMetered());

        // Explicitly expect 0, as documented in Javadoc on setter methods.
        assertEquals(0, networkPriority.getMinEntryUpstreamBandwidthKbps());
        assertEquals(0, networkPriority.getMinExitUpstreamBandwidthKbps());
        assertEquals(0, networkPriority.getMinEntryDownstreamBandwidthKbps());
        assertEquals(0, networkPriority.getMinExitDownstreamBandwidthKbps());

        assertEquals(new HashSet<String>(), networkPriority.getOperatorPlmnIds());
        assertEquals(new HashSet<Integer>(), networkPriority.getSimSpecificCarrierIds());
        assertEquals(MATCH_ANY, networkPriority.getRoaming());
        assertEquals(MATCH_ANY, networkPriority.getOpportunistic());
    }

    @Test
    public void testBuilderRequiresStricterEntryCriteria() {
        try {
            new VcnCellUnderlyingNetworkTemplate.Builder()
                    .setMinUpstreamBandwidthKbps(
                            TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS,
                            TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS);

            fail("Expected IAE for exit threshold > entry threshold");
        } catch (IllegalArgumentException expected) {
        }

        try {
            new VcnCellUnderlyingNetworkTemplate.Builder()
                    .setMinDownstreamBandwidthKbps(
                            TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS,
                            TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS);

            fail("Expected IAE for exit threshold > entry threshold");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testPersistableBundle() {
        final VcnCellUnderlyingNetworkTemplate networkPriority = getTestNetworkTemplate();
        assertEquals(
                networkPriority,
                VcnUnderlyingNetworkTemplate.fromPersistableBundle(
                        networkPriority.toPersistableBundle()));
    }
}
