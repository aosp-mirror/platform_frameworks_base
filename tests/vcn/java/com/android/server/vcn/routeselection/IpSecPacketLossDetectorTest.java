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

import static android.net.vcn.VcnManager.VCN_NETWORK_SELECTION_IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_KEY;
import static android.net.vcn.VcnManager.VCN_NETWORK_SELECTION_MAX_SEQ_NUM_INCREASE_PER_SECOND_KEY;
import static android.net.vcn.VcnManager.VCN_NETWORK_SELECTION_POLL_IPSEC_STATE_INTERVAL_SECONDS_KEY;

import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.MIN_VALID_EXPECTED_RX_PACKET_NUM;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.getMaxSeqNumIncreasePerSecond;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.net.IpSecTransformState;
import android.os.OutcomeReceiver;
import android.os.PowerManager;

import com.android.server.vcn.routeselection.IpSecPacketLossDetector.PacketLossCalculationResult;
import com.android.server.vcn.routeselection.IpSecPacketLossDetector.PacketLossCalculator;
import com.android.server.vcn.routeselection.NetworkMetricMonitor.IpSecTransformWrapper;
import com.android.server.vcn.routeselection.NetworkMetricMonitor.NetworkMetricMonitorCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.TimeUnit;

public class IpSecPacketLossDetectorTest extends NetworkEvaluationTestBase {
    private static final String TAG = IpSecPacketLossDetectorTest.class.getSimpleName();

    private static final int REPLAY_BITMAP_LEN_BYTE = 512;
    private static final int REPLAY_BITMAP_LEN_BIT = REPLAY_BITMAP_LEN_BYTE * 8;
    private static final int IPSEC_PACKET_LOSS_PERCENT_THRESHOLD = 5;
    private static final int MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED = -1;
    private static final long POLL_IPSEC_STATE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30L);

    @Mock private IpSecTransformWrapper mIpSecTransform;
    @Mock private NetworkMetricMonitorCallback mMetricMonitorCallback;
    @Mock private PersistableBundleWrapper mCarrierConfig;
    @Mock private IpSecPacketLossDetector.Dependencies mDependencies;
    @Spy private PacketLossCalculator mPacketLossCalculator = new PacketLossCalculator();

    @Captor private ArgumentCaptor<OutcomeReceiver> mTransformStateReceiverCaptor;
    @Captor private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;

    private IpSecPacketLossDetector mIpSecPacketLossDetector;
    private IpSecTransformState mTransformStateInitial;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mTransformStateInitial = newTransformState(0, 0, newReplayBitmap(0));

        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_POLL_IPSEC_STATE_INTERVAL_SECONDS_KEY), anyInt()))
                .thenReturn((int) TimeUnit.MILLISECONDS.toSeconds(POLL_IPSEC_STATE_INTERVAL_MS));
        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_KEY),
                        anyInt()))
                .thenReturn(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD);
        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_MAX_SEQ_NUM_INCREASE_PER_SECOND_KEY), anyInt()))
                .thenReturn(MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED);

        when(mDependencies.getPacketLossCalculator()).thenReturn(mPacketLossCalculator);

        mIpSecPacketLossDetector =
                new IpSecPacketLossDetector(
                        mVcnContext,
                        mNetwork,
                        mCarrierConfig,
                        mMetricMonitorCallback,
                        mDependencies);
    }

    private static IpSecTransformState newTransformState(
            long rxSeqNo, long packtCount, byte[] replayBitmap) {
        return new IpSecTransformState.Builder()
                .setRxHighestSequenceNumber(rxSeqNo)
                .setPacketCount(packtCount)
                .setReplayBitmap(replayBitmap)
                .build();
    }

    private static IpSecTransformState newNextTransformState(
            IpSecTransformState before,
            long timeDiffMillis,
            long rxSeqNoDiff,
            long packtCountDiff,
            int packetInWin) {
        return new IpSecTransformState.Builder()
                .setTimestampMillis(before.getTimestampMillis() + timeDiffMillis)
                .setRxHighestSequenceNumber(before.getRxHighestSequenceNumber() + rxSeqNoDiff)
                .setPacketCount(before.getPacketCount() + packtCountDiff)
                .setReplayBitmap(newReplayBitmap(packetInWin))
                .build();
    }

    private static byte[] newReplayBitmap(int receivedPktCnt) {
        final BitSet bitSet = new BitSet(REPLAY_BITMAP_LEN_BIT);
        for (int i = 0; i < receivedPktCnt; i++) {
            bitSet.set(i);
        }
        return Arrays.copyOf(bitSet.toByteArray(), REPLAY_BITMAP_LEN_BYTE);
    }

    private void verifyStopped() {
        assertFalse(mIpSecPacketLossDetector.isStarted());
        assertFalse(mIpSecPacketLossDetector.isValidationFailed());
        assertNull(mIpSecPacketLossDetector.getLastTransformState());

        // No event scheduled
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        assertNull(mTestLooper.nextMessage());
    }

    @Test
    public void testInitialization() throws Exception {
        assertFalse(mIpSecPacketLossDetector.isSelectedUnderlyingNetwork());
        verifyStopped();
    }

    private OutcomeReceiver<IpSecTransformState, RuntimeException>
            startMonitorAndCaptureStateReceiver() {
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(true /* setIsSelected */);
        mIpSecPacketLossDetector.setInboundTransformInternal(mIpSecTransform);

        // Trigger the runnable
        mTestLooper.dispatchAll();

        verify(mIpSecTransform)
                .requestIpSecTransformState(any(), mTransformStateReceiverCaptor.capture());
        return mTransformStateReceiverCaptor.getValue();
    }

    @Test
    public void testStartMonitor() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();

        assertTrue(mIpSecPacketLossDetector.isStarted());
        assertFalse(mIpSecPacketLossDetector.isValidationFailed());
        assertTrue(mIpSecPacketLossDetector.isSelectedUnderlyingNetwork());
        assertEquals(mIpSecTransform, mIpSecPacketLossDetector.getInboundTransformInternal());

        // Mock receiving a state
        xfrmStateReceiver.onResult(mTransformStateInitial);

        // Verify the first polled state is stored
        assertEquals(mTransformStateInitial, mIpSecPacketLossDetector.getLastTransformState());
        verify(mPacketLossCalculator, never())
                .getPacketLossRatePercentage(any(), any(), anyInt(), anyString());

        // Verify next poll is scheduled
        assertNull(mTestLooper.nextMessage());
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        assertNotNull(mTestLooper.nextMessage());
    }

    @Test
    public void testStartedMonitor_enterDozeMoze() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();

        // Mock receiving a state
        xfrmStateReceiver.onResult(mTransformStateInitial);
        assertEquals(mTransformStateInitial, mIpSecPacketLossDetector.getLastTransformState());

        // Mock entering doze mode
        final Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        when(mPowerManagerService.isDeviceIdleMode()).thenReturn(true);

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(), any(), any(), any());
        final BroadcastReceiver broadcastReceiver = mBroadcastReceiverCaptor.getValue();
        broadcastReceiver.onReceive(mContext, intent);

        assertNull(mIpSecPacketLossDetector.getLastTransformState());
    }

    @Test
    public void testStartedMonitor_updateInboundTransform() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();

        // Mock receiving a state
        xfrmStateReceiver.onResult(mTransformStateInitial);
        assertEquals(mTransformStateInitial, mIpSecPacketLossDetector.getLastTransformState());

        // Update the inbound transform
        final IpSecTransformWrapper newTransform = mock(IpSecTransformWrapper.class);
        mIpSecPacketLossDetector.setInboundTransformInternal(newTransform);

        // Verifications
        assertNull(mIpSecPacketLossDetector.getLastTransformState());
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        mTestLooper.dispatchAll();
        verify(newTransform).requestIpSecTransformState(any(), any());
    }

    @Test
    public void testStartedMonitor_updateCarrierConfig() throws Exception {
        startMonitorAndCaptureStateReceiver();

        final int additionalPollIntervalMs = (int) TimeUnit.SECONDS.toMillis(10L);
        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_POLL_IPSEC_STATE_INTERVAL_SECONDS_KEY), anyInt()))
                .thenReturn(
                        (int)
                                TimeUnit.MILLISECONDS.toSeconds(
                                        POLL_IPSEC_STATE_INTERVAL_MS + additionalPollIntervalMs));
        mIpSecPacketLossDetector.setCarrierConfig(mCarrierConfig);
        mTestLooper.dispatchAll();

        // The already scheduled event is still fired with the old timeout
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        mTestLooper.dispatchAll();

        // The next scheduled event will take 10 more seconds to fire
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        assertNull(mTestLooper.nextMessage());
        mTestLooper.moveTimeForward(additionalPollIntervalMs);
        assertNotNull(mTestLooper.nextMessage());
    }

    @Test
    public void testStopMonitor() throws Exception {
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(true /* setIsSelected */);
        mIpSecPacketLossDetector.setInboundTransformInternal(mIpSecTransform);

        assertTrue(mIpSecPacketLossDetector.isStarted());
        assertNotNull(mTestLooper.nextMessage());

        // Unselect the monitor
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(false /* setIsSelected */);
        verifyStopped();
    }

    @Test
    public void testClose() throws Exception {
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(true /* setIsSelected */);
        mIpSecPacketLossDetector.setInboundTransformInternal(mIpSecTransform);

        assertTrue(mIpSecPacketLossDetector.isStarted());
        assertNotNull(mTestLooper.nextMessage());

        // Stop the monitor
        mIpSecPacketLossDetector.close();
        verifyStopped();
        verify(mIpSecTransform).close();
    }

    @Test
    public void testTransformStateReceiverOnResultWhenStopped() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();
        xfrmStateReceiver.onResult(mTransformStateInitial);

        // Unselect the monitor
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(false /* setIsSelected */);
        verifyStopped();

        xfrmStateReceiver.onResult(newTransformState(1, 1, newReplayBitmap(1)));
        verify(mPacketLossCalculator, never())
                .getPacketLossRatePercentage(any(), any(), anyInt(), anyString());
    }

    @Test
    public void testTransformStateReceiverOnError() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();
        xfrmStateReceiver.onResult(mTransformStateInitial);

        xfrmStateReceiver.onError(new RuntimeException("Test"));
        verify(mPacketLossCalculator, never())
                .getPacketLossRatePercentage(any(), any(), anyInt(), anyString());
    }

    private void checkHandleLossRate(
            PacketLossCalculationResult mockPacketLossRate,
            boolean isLastStateExpectedToUpdate,
            boolean isCallbackExpected)
            throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();
        doReturn(mockPacketLossRate)
                .when(mPacketLossCalculator)
                .getPacketLossRatePercentage(any(), any(), anyInt(), anyString());

        // Mock receiving two states with mTransformStateInitial and an arbitrary transformNew
        final IpSecTransformState transformNew = newTransformState(1, 1, newReplayBitmap(1));
        xfrmStateReceiver.onResult(mTransformStateInitial);
        xfrmStateReceiver.onResult(transformNew);

        // Verifications
        verify(mPacketLossCalculator)
                .getPacketLossRatePercentage(
                        eq(mTransformStateInitial),
                        eq(transformNew),
                        eq(MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED),
                        anyString());

        if (isLastStateExpectedToUpdate) {
            assertEquals(transformNew, mIpSecPacketLossDetector.getLastTransformState());
        } else {
            assertEquals(mTransformStateInitial, mIpSecPacketLossDetector.getLastTransformState());
        }

        if (isCallbackExpected) {
            verify(mMetricMonitorCallback).onValidationResultReceived();
        } else {
            verify(mMetricMonitorCallback, never()).onValidationResultReceived();
        }
    }

    @Test
    public void testHandleLossRate_validationPass() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.valid(2),
                true /* isLastStateExpectedToUpdate */,
                true /* isCallbackExpected */);
    }

    @Test
    public void testHandleLossRate_validationFail() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.valid(22),
                true /* isLastStateExpectedToUpdate */,
                true /* isCallbackExpected */);
        verify(mConnectivityManager).reportNetworkConnectivity(mNetwork, false);
    }

    @Test
    public void testHandleLossRate_resultUnavalaible() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.invalid(),
                false /* isLastStateExpectedToUpdate */,
                false /* isCallbackExpected */);
    }

    @Test
    public void testHandleLossRate_unusualSeqNumLeap_highLossRate() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.unusualSeqNumLeap(22),
                true /* isLastStateExpectedToUpdate */,
                false /* isCallbackExpected */);
    }

    @Test
    public void testHandleLossRate_unusualSeqNumLeap_lowLossRate() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.unusualSeqNumLeap(2),
                true /* isLastStateExpectedToUpdate */,
                true /* isCallbackExpected */);
    }

    private void checkGetPacketLossRate(
            IpSecTransformState oldState,
            IpSecTransformState newState,
            PacketLossCalculationResult expectedLossRate)
            throws Exception {
        assertEquals(
                expectedLossRate,
                mPacketLossCalculator.getPacketLossRatePercentage(
                        oldState, newState, MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED, TAG));
    }

    private void checkGetPacketLossRate(
            IpSecTransformState oldState,
            int rxSeqNo,
            int packetCount,
            int packetInWin,
            int expectedDataLossRate)
            throws Exception {
        final IpSecTransformState newState =
                newTransformState(rxSeqNo, packetCount, newReplayBitmap(packetInWin));
        checkGetPacketLossRate(
                oldState, newState, PacketLossCalculationResult.valid(expectedDataLossRate));
    }

    private void checkGetPacketLossRate(
            IpSecTransformState oldState,
            int rxSeqNo,
            int packetCount,
            int packetInWin,
            PacketLossCalculationResult expectedDataLossRate)
            throws Exception {
        final IpSecTransformState newState =
                newTransformState(rxSeqNo, packetCount, newReplayBitmap(packetInWin));
        checkGetPacketLossRate(oldState, newState, expectedDataLossRate);
    }

    @Test
    public void testGetPacketLossRate_replayWindowUnchanged() throws Exception {
        checkGetPacketLossRate(
                mTransformStateInitial,
                mTransformStateInitial,
                PacketLossCalculationResult.invalid());
        checkGetPacketLossRate(
                mTransformStateInitial, 3000, 2000, 2000, PacketLossCalculationResult.invalid());
    }

    @Test
    public void testGetPacketLossRate_expectedPacketNumTooFew() throws Exception {
        final int oldRxNo = 4096;
        final int oldPktCnt = 4096;
        final int pktCntDiff = MIN_VALID_EXPECTED_RX_PACKET_NUM - 1;
        final byte[] bitmapReceiveAll = newReplayBitmap(4096);

        final IpSecTransformState oldState =
                newTransformState(oldRxNo, oldPktCnt, bitmapReceiveAll);
        final IpSecTransformState newState =
                newTransformState(oldRxNo + pktCntDiff, oldPktCnt + pktCntDiff, bitmapReceiveAll);

        checkGetPacketLossRate(oldState, newState, PacketLossCalculationResult.invalid());
    }

    @Test
    public void testGetPacketLossRate_againstInitialState() throws Exception {
        checkGetPacketLossRate(mTransformStateInitial, 7000, 7001, 4096, 0);
        checkGetPacketLossRate(mTransformStateInitial, 7000, 6000, 4096, 15);
        checkGetPacketLossRate(mTransformStateInitial, 7000, 6000, 4000, 14);
    }

    @Test
    public void testGetPktLossRate_oldHiSeqSmallerThanWinSize_overlappedWithNewWin()
            throws Exception {
        final IpSecTransformState oldState = newTransformState(2000, 1500, newReplayBitmap(1500));

        checkGetPacketLossRate(oldState, 5000, 5001, 4096, 0);
        checkGetPacketLossRate(oldState, 5000, 4000, 4096, 29);
        checkGetPacketLossRate(oldState, 5000, 4000, 4000, 27);
    }

    @Test
    public void testGetPktLossRate_oldHiSeqSmallerThanWinSize_notOverlappedWithNewWin()
            throws Exception {
        final IpSecTransformState oldState = newTransformState(2000, 1500, newReplayBitmap(1500));

        checkGetPacketLossRate(oldState, 7000, 7001, 4096, 0);
        checkGetPacketLossRate(oldState, 7000, 5000, 4096, 37);
        checkGetPacketLossRate(oldState, 7000, 5000, 3000, 21);
    }

    @Test
    public void testGetPktLossRate_oldHiSeqLargerThanWinSize_overlappedWithNewWin()
            throws Exception {
        final IpSecTransformState oldState = newTransformState(10000, 5000, newReplayBitmap(3000));

        checkGetPacketLossRate(oldState, 12000, 8096, 4096, 0);
        checkGetPacketLossRate(oldState, 12000, 7000, 4096, 36);
        checkGetPacketLossRate(oldState, 12000, 7000, 3000, 0);
    }

    @Test
    public void testGetPktLossRate_oldHiSeqLargerThanWinSize_notOverlappedWithNewWin()
            throws Exception {
        final IpSecTransformState oldState = newTransformState(10000, 5000, newReplayBitmap(3000));

        checkGetPacketLossRate(oldState, 20000, 16096, 4096, 0);
        checkGetPacketLossRate(oldState, 20000, 14000, 4096, 19);
        checkGetPacketLossRate(oldState, 20000, 14000, 3000, 10);
    }

    private void checkGetPktLossRate_unusualSeqNumLeap(
            int maxSeqNumIncreasePerSecond,
            int timeDiffMillis,
            int rxSeqNoDiff,
            PacketLossCalculationResult expected)
            throws Exception {
        final IpSecTransformState oldState = mTransformStateInitial;
        final IpSecTransformState newState =
                newNextTransformState(
                        oldState,
                        timeDiffMillis,
                        rxSeqNoDiff,
                        1 /* packtCountDiff */,
                        1 /* packetInWin */);

        assertEquals(
                expected,
                mPacketLossCalculator.getPacketLossRatePercentage(
                        oldState, newState, maxSeqNumIncreasePerSecond, TAG));
    }

    @Test
    public void testGetPktLossRate_unusualSeqNumLeap() throws Exception {
        checkGetPktLossRate_unusualSeqNumLeap(
                10000 /* maxSeqNumIncreasePerSecond */,
                (int) TimeUnit.SECONDS.toMillis(2L),
                30000 /* rxSeqNoDiff */,
                PacketLossCalculationResult.unusualSeqNumLeap(100));
    }

    @Test
    public void testGetPktLossRate_unusualSeqNumLeap_smallSeqNumDiff() throws Exception {
        checkGetPktLossRate_unusualSeqNumLeap(
                10000 /* maxSeqNumIncreasePerSecond */,
                (int) TimeUnit.SECONDS.toMillis(2L),
                5000 /* rxSeqNoDiff */,
                PacketLossCalculationResult.valid(100));
    }

    // Verify the polling event is scheduled with expected delays
    private void verifyPollEventDelayAndScheduleNext(long expectedDelayMs) {
        if (expectedDelayMs > 0) {
            mTestLooper.dispatchAll();
            verify(mIpSecTransform, never()).requestIpSecTransformState(any(), any());
            mTestLooper.moveTimeForward(expectedDelayMs);
        }

        mTestLooper.dispatchAll();
        verify(mIpSecTransform).requestIpSecTransformState(any(), any());
        reset(mIpSecTransform);
    }

    @Test
    public void testOnLinkPropertiesOrCapabilitiesChange() throws Exception {
        // Start the monitor; verify the 1st poll is scheduled without delay
        startMonitorAndCaptureStateReceiver();
        verifyPollEventDelayAndScheduleNext(0 /* expectedDelayMs */);

        // Verify the 2nd poll is rescheduled without delay
        mIpSecPacketLossDetector.onLinkPropertiesOrCapabilitiesChanged();
        verifyPollEventDelayAndScheduleNext(0 /* expectedDelayMs */);

        // Verify the 3rd poll is scheduled with configured delay
        verifyPollEventDelayAndScheduleNext(POLL_IPSEC_STATE_INTERVAL_MS);
    }

    @Test
    public void testGetMaxSeqNumIncreasePerSecond() throws Exception {
        final int seqNumLeapNegative = 500_000;
        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_MAX_SEQ_NUM_INCREASE_PER_SECOND_KEY), anyInt()))
                .thenReturn(seqNumLeapNegative);
        assertEquals(seqNumLeapNegative, getMaxSeqNumIncreasePerSecond(mCarrierConfig));
    }

    @Test
    public void testGetMaxSeqNumIncreasePerSecond_negativeValue() throws Exception {
        final int seqNumLeapNegative = -10;
        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_MAX_SEQ_NUM_INCREASE_PER_SECOND_KEY), anyInt()))
                .thenReturn(seqNumLeapNegative);
        assertEquals(
                MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED,
                getMaxSeqNumIncreasePerSecond(mCarrierConfig));
    }

    private IpSecPacketLossDetector newDetectorAndSetTransform(int threshold) throws Exception {
        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_KEY),
                        anyInt()))
                .thenReturn(threshold);

        final IpSecPacketLossDetector detector =
                new IpSecPacketLossDetector(
                        mVcnContext,
                        mNetwork,
                        mCarrierConfig,
                        mMetricMonitorCallback,
                        mDependencies);

        detector.setIsSelectedUnderlyingNetwork(true /* setIsSelected */);
        detector.setInboundTransformInternal(mIpSecTransform);

        return detector;
    }

    @Test
    public void testDisableAndEnableDetectorWithCarrierConfig() throws Exception {
        final IpSecPacketLossDetector detector =
                newDetectorAndSetTransform(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR);

        assertFalse(detector.isStarted());

        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_KEY),
                        anyInt()))
                .thenReturn(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD);
        detector.setCarrierConfig(mCarrierConfig);

        assertTrue(detector.isStarted());
    }

    @Test
    public void testEnableAndDisableDetectorWithCarrierConfig() throws Exception {
        final IpSecPacketLossDetector detector =
                newDetectorAndSetTransform(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD);

        assertTrue(detector.isStarted());

        when(mCarrierConfig.getInt(
                        eq(VCN_NETWORK_SELECTION_IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_KEY),
                        anyInt()))
                .thenReturn(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR);
        detector.setCarrierConfig(mCarrierConfig);

        assertFalse(detector.isStarted());
    }
}
