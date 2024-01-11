/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.vcn.routeselection;

import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.PRIORITY_INVALID;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.vcn.VcnGatewayConnectionConfig;
import android.os.PersistableBundle;

import org.junit.Before;
import org.junit.Test;

public class UnderlyingNetworkEvaluatorTest extends NetworkEvaluationTestBase {
    private PersistableBundleWrapper mCarrierConfig;

    @Before
    public void setUp() {
        super.setUp();
        mCarrierConfig = new PersistableBundleWrapper(new PersistableBundle());
    }

    private UnderlyingNetworkEvaluator newUnderlyingNetworkEvaluator() {
        return new UnderlyingNetworkEvaluator(
                mVcnContext,
                mNetwork,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);
    }

    @Test
    public void testInitializedEvaluator() throws Exception {
        final UnderlyingNetworkEvaluator evaluator = newUnderlyingNetworkEvaluator();

        assertFalse(evaluator.isValid());
        assertEquals(mNetwork, evaluator.getNetwork());
        assertEquals(PRIORITY_INVALID, evaluator.getPriorityClass());

        try {
            evaluator.getNetworkRecord();
            fail("Expected to fail because evaluator is not valid");
        } catch (Exception expected) {
        }
    }

    @Test
    public void testValidEvaluator() {
        final UnderlyingNetworkEvaluator evaluator = newUnderlyingNetworkEvaluator();
        evaluator.setNetworkCapabilities(
                CELL_NETWORK_CAPABILITIES,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);
        evaluator.setLinkProperties(
                LINK_PROPERTIES,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);
        evaluator.setIsBlocked(
                false /* isBlocked */,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);

        final UnderlyingNetworkRecord expectedRecord =
                new UnderlyingNetworkRecord(
                        mNetwork,
                        CELL_NETWORK_CAPABILITIES,
                        LINK_PROPERTIES,
                        false /* isBlocked */);

        assertTrue(evaluator.isValid());
        assertEquals(mNetwork, evaluator.getNetwork());
        assertEquals(2, evaluator.getPriorityClass());
        assertEquals(expectedRecord, evaluator.getNetworkRecord());
    }
}
