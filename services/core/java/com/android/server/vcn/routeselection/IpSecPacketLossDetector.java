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

import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.IpSecTransformState;
import android.net.Network;
import android.net.vcn.VcnManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.OutcomeReceiver;
import android.os.PowerManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.vcn.VcnContext;

import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * IpSecPacketLossDetector is responsible for continuously monitoring IPsec packet loss
 *
 * <p>When the packet loss rate surpass the threshold, IpSecPacketLossDetector will report it to the
 * caller
 *
 * <p>IpSecPacketLossDetector will start monitoring when the network being monitored is selected AND
 * an inbound IpSecTransform has been applied to this network.
 *
 * <p>This class is flag gated by "network_metric_monitor" and "ipsec_tramsform_state"
 */
public class IpSecPacketLossDetector extends NetworkMetricMonitor {
    private static final String TAG = IpSecPacketLossDetector.class.getSimpleName();

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PACKET_LOSS_UNAVALAIBLE = -1;

    // For VoIP, losses between 5% and 10% of the total packet stream will affect the quality
    // significantly (as per "Computer Networking for LANS to WANS: Hardware, Software and
    // Security"). For audio and video streaming, above 10-12% packet loss is unacceptable (as per
    // "ICTP-SDU: About PingER"). Thus choose 12% as a conservative default threshold to declare a
    // validation failure.
    private static final int IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DEFAULT = 12;

    private static final int POLL_IPSEC_STATE_INTERVAL_SECONDS_DEFAULT = 20;

    private long mPollIpSecStateIntervalMs;
    private final int mPacketLossRatePercentThreshold;

    @NonNull private final Handler mHandler;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final Object mCancellationToken = new Object();
    @NonNull private final PacketLossCalculator mPacketLossCalculator;

    @Nullable private IpSecTransformWrapper mInboundTransform;
    @Nullable private IpSecTransformState mLastIpSecTransformState;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public IpSecPacketLossDetector(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @Nullable PersistableBundleWrapper carrierConfig,
            @NonNull NetworkMetricMonitorCallback callback,
            @NonNull Dependencies deps)
            throws IllegalAccessException {
        super(vcnContext, network, carrierConfig, callback);

        Objects.requireNonNull(deps, "Missing deps");

        if (!vcnContext.isFlagIpSecTransformStateEnabled()) {
            // Caller error
            logWtf("ipsecTransformState flag disabled");
            throw new IllegalAccessException("ipsecTransformState flag disabled");
        }

        mHandler = new Handler(getVcnContext().getLooper());

        mPowerManager = getVcnContext().getContext().getSystemService(PowerManager.class);

        mPacketLossCalculator = deps.getPacketLossCalculator();

        mPollIpSecStateIntervalMs = getPollIpSecStateIntervalMs(carrierConfig);
        mPacketLossRatePercentThreshold = getPacketLossRatePercentThreshold(carrierConfig);

        // Register for system broadcasts to monitor idle mode change
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        getVcnContext()
                .getContext()
                .registerReceiver(
                        new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(
                                                intent.getAction())
                                        && mPowerManager.isDeviceIdleMode()) {
                                    mLastIpSecTransformState = null;
                                }
                            }
                        },
                        intentFilter,
                        null /* broadcastPermission not required */,
                        mHandler);
    }

    public IpSecPacketLossDetector(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @Nullable PersistableBundleWrapper carrierConfig,
            @NonNull NetworkMetricMonitorCallback callback)
            throws IllegalAccessException {
        this(vcnContext, network, carrierConfig, callback, new Dependencies());
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        public PacketLossCalculator getPacketLossCalculator() {
            return new PacketLossCalculator();
        }
    }

    private static long getPollIpSecStateIntervalMs(
            @Nullable PersistableBundleWrapper carrierConfig) {
        final int seconds;

        if (carrierConfig != null) {
            seconds =
                    carrierConfig.getInt(
                            VcnManager.VCN_NETWORK_SELECTION_POLL_IPSEC_STATE_INTERVAL_SECONDS_KEY,
                            POLL_IPSEC_STATE_INTERVAL_SECONDS_DEFAULT);
        } else {
            seconds = POLL_IPSEC_STATE_INTERVAL_SECONDS_DEFAULT;
        }

        return TimeUnit.SECONDS.toMillis(seconds);
    }

    private static int getPacketLossRatePercentThreshold(
            @Nullable PersistableBundleWrapper carrierConfig) {
        if (carrierConfig != null) {
            return carrierConfig.getInt(
                    VcnManager.VCN_NETWORK_SELECTION_IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_KEY,
                    IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DEFAULT);
        }
        return IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DEFAULT;
    }

    @Override
    protected void onSelectedUnderlyingNetworkChanged() {
        if (!isSelectedUnderlyingNetwork()) {
            mInboundTransform = null;
            stop();
        }

        // No action when the underlying network got selected. Wait for the inbound transform to
        // start the monitor
    }

    @Override
    public void setInboundTransformInternal(@NonNull IpSecTransformWrapper inboundTransform) {
        Objects.requireNonNull(inboundTransform, "inboundTransform is null");

        if (Objects.equals(inboundTransform, mInboundTransform)) {
            return;
        }

        if (!isSelectedUnderlyingNetwork()) {
            logWtf("setInboundTransform called but network not selected");
            return;
        }

        // When multiple parallel inbound transforms are created, NetworkMetricMonitor will be
        // enabled on the last one as a sample
        mInboundTransform = inboundTransform;
        start();
    }

    @Override
    public void setCarrierConfig(@Nullable PersistableBundleWrapper carrierConfig) {
        // The already scheduled event will not be affected. The followup events will be scheduled
        // with the new interval
        mPollIpSecStateIntervalMs = getPollIpSecStateIntervalMs(carrierConfig);
    }

    @Override
    protected void start() {
        super.start();
        clearTransformStateAndPollingEvents();
        mHandler.postDelayed(new PollIpSecStateRunnable(), mCancellationToken, 0L);
    }

    @Override
    public void stop() {
        super.stop();
        clearTransformStateAndPollingEvents();
    }

    private void clearTransformStateAndPollingEvents() {
        mHandler.removeCallbacksAndEqualMessages(mCancellationToken);
        mLastIpSecTransformState = null;
    }

    @Override
    public void close() {
        super.close();

        if (mInboundTransform != null) {
            mInboundTransform.close();
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @Nullable
    public IpSecTransformState getLastTransformState() {
        return mLastIpSecTransformState;
    }

    @VisibleForTesting(visibility = Visibility.PROTECTED)
    @Nullable
    public IpSecTransformWrapper getInboundTransformInternal() {
        return mInboundTransform;
    }

    private class PollIpSecStateRunnable implements Runnable {
        @Override
        public void run() {
            if (!isStarted()) {
                logWtf("Monitor stopped but PollIpSecStateRunnable not removed from Handler");
                return;
            }

            getInboundTransformInternal()
                    .getIpSecTransformState(
                            new HandlerExecutor(mHandler), new IpSecTransformStateReceiver());

            // Schedule for next poll
            mHandler.postDelayed(
                    new PollIpSecStateRunnable(), mCancellationToken, mPollIpSecStateIntervalMs);
        }
    }

    private class IpSecTransformStateReceiver
            implements OutcomeReceiver<IpSecTransformState, RuntimeException> {
        @Override
        public void onResult(@NonNull IpSecTransformState state) {
            getVcnContext().ensureRunningOnLooperThread();

            if (!isStarted()) {
                return;
            }

            onIpSecTransformStateReceived(state);
        }

        @Override
        public void onError(@NonNull RuntimeException error) {
            getVcnContext().ensureRunningOnLooperThread();

            // Nothing we can do here
            logW("TransformStateReceiver#onError " + error.toString());
        }
    }

    private void onIpSecTransformStateReceived(@NonNull IpSecTransformState state) {
        if (mLastIpSecTransformState == null) {
            // This is first time to poll the state
            mLastIpSecTransformState = state;
            return;
        }

        final int packetLossRate =
                mPacketLossCalculator.getPacketLossRatePercentage(
                        mLastIpSecTransformState, state, getLogPrefix());

        if (packetLossRate == PACKET_LOSS_UNAVALAIBLE) {
            return;
        }

        final String logMsg =
                "packetLossRate: "
                        + packetLossRate
                        + "% in the past "
                        + (state.getTimestamp() - mLastIpSecTransformState.getTimestamp())
                        + "ms";

        mLastIpSecTransformState = state;
        if (packetLossRate < mPacketLossRatePercentThreshold) {
            logV(logMsg);
            onValidationResultReceivedInternal(false /* isFailed */);
        } else {
            logInfo(logMsg);
            onValidationResultReceivedInternal(true /* isFailed */);
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class PacketLossCalculator {
        /** Calculate the packet loss rate between two timestamps */
        public int getPacketLossRatePercentage(
                @NonNull IpSecTransformState oldState,
                @NonNull IpSecTransformState newState,
                String logPrefix) {
            logVIpSecTransform("oldState", oldState, logPrefix);
            logVIpSecTransform("newState", newState, logPrefix);

            final int replayWindowSize = oldState.getReplayBitmap().length * 8;
            final long oldSeqHi = oldState.getRxHighestSequenceNumber();
            final long oldSeqLow = Math.max(0L, oldSeqHi - replayWindowSize + 1);
            final long newSeqHi = newState.getRxHighestSequenceNumber();
            final long newSeqLow = Math.max(0L, newSeqHi - replayWindowSize + 1);

            if (oldSeqHi == newSeqHi || newSeqHi < replayWindowSize) {
                // The replay window did not proceed and all packets might have been delivered out
                // of order
                return PACKET_LOSS_UNAVALAIBLE;
            }

            // Get the expected packet count by assuming there is no packet loss. In this case, SA
            // should receive all packets whose sequence numbers are smaller than the lower bound of
            // the replay window AND the packets received within the window.
            // When the lower bound is 0, it's not possible to tell whether packet with seqNo 0 is
            // received or not. For simplicity just assume that packet is received.
            final long newExpectedPktCnt = newSeqLow + getPacketCntInReplayWindow(newState);
            final long oldExpectedPktCnt = oldSeqLow + getPacketCntInReplayWindow(oldState);

            final long expectedPktCntDiff = newExpectedPktCnt - oldExpectedPktCnt;
            final long actualPktCntDiff = newState.getPacketCount() - oldState.getPacketCount();

            logV(
                    TAG,
                    logPrefix
                            + " expectedPktCntDiff: "
                            + expectedPktCntDiff
                            + " actualPktCntDiff: "
                            + actualPktCntDiff);

            if (expectedPktCntDiff < 0
                    || expectedPktCntDiff == 0
                    || actualPktCntDiff < 0
                    || actualPktCntDiff > expectedPktCntDiff) {
                logWtf(TAG, "Impossible values for expectedPktCntDiff or" + " actualPktCntDiff");
                return PACKET_LOSS_UNAVALAIBLE;
            }

            return 100 - (int) (actualPktCntDiff * 100 / expectedPktCntDiff);
        }
    }

    private static void logVIpSecTransform(
            String transformTag, IpSecTransformState state, String logPrefix) {
        final String stateString =
                " seqNo: "
                        + state.getRxHighestSequenceNumber()
                        + " | pktCnt: "
                        + state.getPacketCount()
                        + " | pktCntInWindow: "
                        + getPacketCntInReplayWindow(state);
        logV(TAG, logPrefix + " " + transformTag + stateString);
    }

    /** Get the number of received packets within the replay window */
    private static long getPacketCntInReplayWindow(@NonNull IpSecTransformState state) {
        return BitSet.valueOf(state.getReplayBitmap()).cardinality();
    }
}
