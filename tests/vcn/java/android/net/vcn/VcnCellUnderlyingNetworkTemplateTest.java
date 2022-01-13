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
import static android.net.vcn.VcnUnderlyingNetworkTemplate.NETWORK_QUALITY_ANY;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.NETWORK_QUALITY_OK;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class VcnCellUnderlyingNetworkTemplateTest {
    private static final Set<String> ALLOWED_PLMN_IDS = new HashSet<>();
    private static final Set<Integer> ALLOWED_CARRIER_IDS = new HashSet<>();

    // Package private for use in VcnGatewayConnectionConfigTest
    static VcnCellUnderlyingNetworkTemplate getTestNetworkTemplate() {
        return new VcnCellUnderlyingNetworkTemplate.Builder()
                .setNetworkQuality(NETWORK_QUALITY_OK)
                .setMetered(MATCH_FORBIDDEN)
                .setOperatorPlmnIds(ALLOWED_PLMN_IDS)
                .setSimSpecificCarrierIds(ALLOWED_CARRIER_IDS)
                .setRoaming(MATCH_FORBIDDEN)
                .setOpportunistic(MATCH_REQUIRED)
                .build();
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnCellUnderlyingNetworkTemplate networkPriority = getTestNetworkTemplate();
        assertEquals(NETWORK_QUALITY_OK, networkPriority.getNetworkQuality());
        assertEquals(MATCH_FORBIDDEN, networkPriority.getMetered());
        assertEquals(ALLOWED_PLMN_IDS, networkPriority.getOperatorPlmnIds());
        assertEquals(ALLOWED_CARRIER_IDS, networkPriority.getSimSpecificCarrierIds());
        assertEquals(MATCH_FORBIDDEN, networkPriority.getRoaming());
        assertEquals(MATCH_REQUIRED, networkPriority.getOpportunistic());
    }

    @Test
    public void testBuilderAndGettersForDefaultValues() {
        final VcnCellUnderlyingNetworkTemplate networkPriority =
                new VcnCellUnderlyingNetworkTemplate.Builder().build();
        assertEquals(NETWORK_QUALITY_ANY, networkPriority.getNetworkQuality());
        assertEquals(MATCH_ANY, networkPriority.getMetered());
        assertEquals(new HashSet<String>(), networkPriority.getOperatorPlmnIds());
        assertEquals(new HashSet<Integer>(), networkPriority.getSimSpecificCarrierIds());
        assertEquals(MATCH_ANY, networkPriority.getRoaming());
        assertEquals(MATCH_ANY, networkPriority.getOpportunistic());
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
