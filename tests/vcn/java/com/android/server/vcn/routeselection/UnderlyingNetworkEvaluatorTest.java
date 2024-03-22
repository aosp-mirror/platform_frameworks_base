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

import static android.net.vcn.VcnManager.VCN_NETWORK_SELECTION_PENALTY_TIMEOUT_MINUTES_LIST_KEY;

import static com.android.server.vcn.routeselection.NetworkPriorityClassifier.PRIORITY_INVALID;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.IpSecTransform;
import android.net.vcn.VcnGatewayConnectionConfig;

import com.android.server.vcn.routeselection.NetworkMetricMonitor.NetworkMetricMonitorCallback;
import com.android.server.vcn.routeselection.UnderlyingNetworkEvaluator.Dependencies;
import com.android.server.vcn.routeselection.UnderlyingNetworkEvaluator.NetworkEvaluatorCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.concurrent.TimeUnit;

public class UnderlyingNetworkEvaluatorTest extends NetworkEvaluationTestBase {
    private static final int PENALTY_TIMEOUT_MIN = 10;
    private static final long PENALTY_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(PENALTY_TIMEOUT_MIN);

    @Mock private PersistableBundleWrapper mCarrierConfig;
    @Mock private IpSecPacketLossDetector mIpSecPacketLossDetector;
    @Mock private Dependencies mDependencies;
    @Mock private NetworkEvaluatorCallback mEvaluatorCallback;

    @Captor private ArgumentCaptor<NetworkMetricMonitorCallback> mMetricMonitorCbCaptor;

    private UnderlyingNetworkEvaluator mNetworkEvaluator;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        when(mDependencies.newIpSecPacketLossDetector(any(), any(), any(), any()))
                .thenReturn(mIpSecPacketLossDetector);

        when(mCarrierConfig.getIntArray(
                        eq(VCN_NETWORK_SELECTION_PENALTY_TIMEOUT_MINUTES_LIST_KEY), anyObject()))
                .thenReturn(new int[] {PENALTY_TIMEOUT_MIN});

        mNetworkEvaluator = newValidUnderlyingNetworkEvaluator();
    }

    private UnderlyingNetworkEvaluator newUnderlyingNetworkEvaluator() {
        return new UnderlyingNetworkEvaluator(
                mVcnContext,
                mNetwork,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig,
                mEvaluatorCallback,
                mDependencies);
    }

    private UnderlyingNetworkEvaluator newValidUnderlyingNetworkEvaluator() {
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

        return evaluator;
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

    private void checkSetSelectedNetwork(boolean isSelected) {
        mNetworkEvaluator.setIsSelected(
                isSelected,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);
        verify(mIpSecPacketLossDetector).setIsSelectedUnderlyingNetwork(isSelected);
    }

    @Test
    public void testSetIsSelected_selected() throws Exception {
        checkSetSelectedNetwork(true /* isSelectedExpected */);
    }

    @Test
    public void testSetIsSelected_unselected() throws Exception {
        checkSetSelectedNetwork(false /* isSelectedExpected */);
    }

    @Test
    public void testSetIpSecTransform_onSelectedNetwork() throws Exception {
        final IpSecTransform transform = makeDummyIpSecTransform();

        // Make the network selected
        mNetworkEvaluator.setIsSelected(
                true,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);
        mNetworkEvaluator.setInboundTransform(transform);

        verify(mIpSecPacketLossDetector).setInboundTransform(transform);
    }

    @Test
    public void testSetIpSecTransform_onUnSelectedNetwork() throws Exception {
        mNetworkEvaluator.setIsSelected(
                false,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);
        mNetworkEvaluator.setInboundTransform(makeDummyIpSecTransform());

        verify(mIpSecPacketLossDetector, never()).setInboundTransform(any());
    }

    @Test
    public void close() throws Exception {
        mNetworkEvaluator.close();

        verify(mIpSecPacketLossDetector).close();
        mTestLooper.moveTimeForward(PENALTY_TIMEOUT_MS);
        assertNull(mTestLooper.nextMessage());
    }

    private NetworkMetricMonitorCallback getMetricMonitorCbCaptor() throws Exception {
        verify(mDependencies)
                .newIpSecPacketLossDetector(any(), any(), any(), mMetricMonitorCbCaptor.capture());

        return mMetricMonitorCbCaptor.getValue();
    }

    private void checkPenalizeNetwork() throws Exception {
        assertFalse(mNetworkEvaluator.isPenalized());

        // Validation failed
        when(mIpSecPacketLossDetector.isValidationFailed()).thenReturn(true);
        getMetricMonitorCbCaptor().onValidationResultReceived();

        // Verify the evaluator is penalized
        assertTrue(mNetworkEvaluator.isPenalized());
        verify(mEvaluatorCallback).onEvaluationResultChanged();
    }

    @Test
    public void testRcvValidationResult_penalizeNetwork_penaltyTimeout() throws Exception {
        checkPenalizeNetwork();

        // Penalty timeout
        mTestLooper.moveTimeForward(PENALTY_TIMEOUT_MS);
        mTestLooper.dispatchAll();

        // Verify the evaluator is not penalized
        assertFalse(mNetworkEvaluator.isPenalized());
        verify(mEvaluatorCallback, times(2)).onEvaluationResultChanged();
    }

    @Test
    public void testRcvValidationResult_penalizeNetwork_passValidation() throws Exception {
        checkPenalizeNetwork();

        // Validation passed
        when(mIpSecPacketLossDetector.isValidationFailed()).thenReturn(false);
        getMetricMonitorCbCaptor().onValidationResultReceived();

        // Verify the evaluator is not penalized and penalty timeout is canceled
        assertFalse(mNetworkEvaluator.isPenalized());
        verify(mEvaluatorCallback, times(2)).onEvaluationResultChanged();
        mTestLooper.moveTimeForward(PENALTY_TIMEOUT_MS);
        assertNull(mTestLooper.nextMessage());
    }

    @Test
    public void testRcvValidationResult_penalizeNetwork_closeEvaluator() throws Exception {
        checkPenalizeNetwork();

        mNetworkEvaluator.close();

        // Verify penalty timeout is canceled
        mTestLooper.moveTimeForward(PENALTY_TIMEOUT_MS);
        assertNull(mTestLooper.nextMessage());
    }

    @Test
    public void testRcvValidationResult_PenaltyStateUnchanged() throws Exception {
        assertFalse(mNetworkEvaluator.isPenalized());

        // Validation passed
        when(mIpSecPacketLossDetector.isValidationFailed()).thenReturn(false);
        getMetricMonitorCbCaptor().onValidationResultReceived();

        // Verifications
        assertFalse(mNetworkEvaluator.isPenalized());
        verify(mEvaluatorCallback, never()).onEvaluationResultChanged();
    }

    @Test
    public void testSetCarrierConfig() throws Exception {
        final int additionalTimeoutMin = 10;
        when(mCarrierConfig.getIntArray(
                        eq(VCN_NETWORK_SELECTION_PENALTY_TIMEOUT_MINUTES_LIST_KEY), anyObject()))
                .thenReturn(new int[] {PENALTY_TIMEOUT_MIN + additionalTimeoutMin});

        // Update evaluator and penalize the network
        mNetworkEvaluator.reevaluate(
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);
        checkPenalizeNetwork();

        // Verify penalty timeout is changed
        mTestLooper.moveTimeForward(PENALTY_TIMEOUT_MS);
        assertNull(mTestLooper.nextMessage());
        mTestLooper.moveTimeForward(TimeUnit.MINUTES.toMillis(additionalTimeoutMin));
        assertNotNull(mTestLooper.nextMessage());

        // Verify NetworkMetricMonitor is notified
        verify(mIpSecPacketLossDetector).setCarrierConfig(any());
    }

    @Test
    public void testCompare() throws Exception {
        when(mIpSecPacketLossDetector.isValidationFailed()).thenReturn(true);
        getMetricMonitorCbCaptor().onValidationResultReceived();

        final UnderlyingNetworkEvaluator penalized = mNetworkEvaluator;
        final UnderlyingNetworkEvaluator notPenalized = newValidUnderlyingNetworkEvaluator();

        assertEquals(penalized.getPriorityClass(), notPenalized.getPriorityClass());

        final int result =
                UnderlyingNetworkEvaluator.getComparator(mVcnContext)
                        .compare(penalized, notPenalized);
        assertEquals(1, result);
    }

    @Test
    public void testNotifyNetworkMetricMonitorOnLpChange() throws Exception {
        // Clear calls invoked when initializing mNetworkEvaluator
        reset(mIpSecPacketLossDetector);

        final UnderlyingNetworkEvaluator evaluator = newUnderlyingNetworkEvaluator();
        evaluator.setNetworkCapabilities(
                CELL_NETWORK_CAPABILITIES,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);

        verify(mIpSecPacketLossDetector).onLinkPropertiesOrCapabilitiesChanged();
    }

    @Test
    public void testNotifyNetworkMetricMonitorOnNcChange() throws Exception {
        // Clear calls invoked when initializing mNetworkEvaluator
        reset(mIpSecPacketLossDetector);

        final UnderlyingNetworkEvaluator evaluator = newUnderlyingNetworkEvaluator();
        evaluator.setLinkProperties(
                LINK_PROPERTIES,
                VcnGatewayConnectionConfig.DEFAULT_UNDERLYING_NETWORK_TEMPLATES,
                SUB_GROUP,
                mSubscriptionSnapshot,
                mCarrierConfig);

        verify(mIpSecPacketLossDetector).onLinkPropertiesOrCapabilitiesChanged();
    }
}
