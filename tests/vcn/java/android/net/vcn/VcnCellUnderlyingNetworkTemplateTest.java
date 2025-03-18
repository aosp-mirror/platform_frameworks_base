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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnCellUnderlyingNetworkTemplateTest extends VcnUnderlyingNetworkTemplateTestBase {
    private static final Set<String> ALLOWED_PLMN_IDS = new HashSet<>();
    private static final Set<Integer> ALLOWED_CARRIER_IDS = new HashSet<>();

    // Public for use in UnderlyingNetworkControllerTest
    public static VcnCellUnderlyingNetworkTemplate.Builder getTestNetworkTemplateBuilder() {
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
                .setOpportunistic(MATCH_REQUIRED);
    }

    // Package private for use in VcnGatewayConnectionConfigTest
    static VcnCellUnderlyingNetworkTemplate getTestNetworkTemplate() {
        return getTestNetworkTemplateBuilder().build();
    }

    private void setAllCapabilities(
            VcnCellUnderlyingNetworkTemplate.Builder builder, int matchCriteria) {
        builder.setCbs(matchCriteria);
        builder.setDun(matchCriteria);
        builder.setIms(matchCriteria);
        builder.setInternet(matchCriteria);
        builder.setMms(matchCriteria);
        builder.setRcs(matchCriteria);
    }

    private void verifyAllCapabilities(
            VcnCellUnderlyingNetworkTemplate template,
            int expectMatchCriteriaforNonInternet,
            int expectMatchCriteriaforInternet) {
        assertEquals(expectMatchCriteriaforNonInternet, template.getCbs());
        assertEquals(expectMatchCriteriaforNonInternet, template.getDun());
        assertEquals(expectMatchCriteriaforNonInternet, template.getIms());
        assertEquals(expectMatchCriteriaforNonInternet, template.getMms());
        assertEquals(expectMatchCriteriaforNonInternet, template.getRcs());

        assertEquals(expectMatchCriteriaforInternet, template.getInternet());
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnCellUnderlyingNetworkTemplate.Builder builder = getTestNetworkTemplateBuilder();
        setAllCapabilities(builder, MATCH_REQUIRED);

        final VcnCellUnderlyingNetworkTemplate networkPriority = builder.build();

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

        verifyAllCapabilities(networkPriority, MATCH_REQUIRED, MATCH_REQUIRED);
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

        verifyAllCapabilities(networkPriority, MATCH_ANY, MATCH_REQUIRED);
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
    public void testBuildFailWithoutRequiredCapabilities() {
        try {
            new VcnCellUnderlyingNetworkTemplate.Builder().setInternet(MATCH_ANY).build();

            fail("Expected IAE for missing required capabilities");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testEqualsWithDifferentCapabilities() {
        final VcnCellUnderlyingNetworkTemplate left =
                new VcnCellUnderlyingNetworkTemplate.Builder().setDun(MATCH_REQUIRED).build();
        final VcnCellUnderlyingNetworkTemplate right =
                new VcnCellUnderlyingNetworkTemplate.Builder().setMms(MATCH_REQUIRED).build();
        assertNotEquals(left, right);
    }

    @Test
    public void testPersistableBundle() {
        final VcnCellUnderlyingNetworkTemplate networkPriority = getTestNetworkTemplate();
        assertEquals(
                networkPriority,
                VcnUnderlyingNetworkTemplate.fromPersistableBundle(
                        networkPriority.toPersistableBundle()));
    }

    @Test
    public void testPersistableBundleWithCapabilities() {
        final VcnCellUnderlyingNetworkTemplate.Builder builder = getTestNetworkTemplateBuilder();
        setAllCapabilities(builder, MATCH_REQUIRED);

        final VcnCellUnderlyingNetworkTemplate networkPriority = builder.build();
        assertEquals(
                networkPriority,
                VcnUnderlyingNetworkTemplate.fromPersistableBundle(
                        networkPriority.toPersistableBundle()));
    }
}
