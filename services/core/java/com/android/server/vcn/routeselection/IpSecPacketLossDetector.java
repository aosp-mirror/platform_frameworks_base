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

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.IpSecTransformState;
import android.net.Network;
import android.net.vcn.Flags;
import android.net.vcn.VcnManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.OutcomeReceiver;
import android.os.PowerManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.vcn.VcnContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
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

    private static final int PACKET_LOSS_PERCENT_UNAVAILABLE = -1;

    // Ignore the packet loss detection result if the expected packet number is smaller than 10.
    // Solarwinds NPM uses 10 ICMP echos to calculate packet loss rate (as per
    // https://thwack.solarwinds.com/products/network-performance-monitor-npm/f/forum/63829/how-is-packet-loss-calculated)
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int MIN_VALID_EXPECTED_RX_PACKET_NUM = 10;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"PACKET_LOSS_"},
            value = {
                PACKET_LOSS_RATE_VALID,
                PACKET_LOSS_RATE_INVALID,
                PACKET_LOSS_UNUSUAL_SEQ_NUM_LEAP,
            })
    @Target({ElementType.TYPE_USE})
    private @interface PacketLossResultType {}

    /** Indicates a valid packet loss rate is available */
    private static final int PACKET_LOSS_RATE_VALID = 0;

    /**
     * Indicates that the detector cannot get a valid packet loss rate due to one of the following
     * reasons:
     *
     * <ul>
     *   <li>The replay window did not proceed and thus all packets might have been delivered out of
     *       order
     *   <li>The expected received packet number is too small and thus the detection result is not
     *       reliable
     *   <li>There are unexpected errors
     * </ul>
     */
    private static final int PACKET_LOSS_RATE_INVALID = 1;

    /**
     * The sequence number increase is unusually large and might be caused an intentional leap on
     * the server's downlink
     *
     * <p>Inbound sequence number will not always increase consecutively. During load balancing the
     * server might add a big leap on the sequence number intentionally. In such case a high packet
     * loss rate does not always indicate a lossy network
     */
    private static final int PACKET_LOSS_UNUSUAL_SEQ_NUM_LEAP = 2;

    // For VoIP, losses between 5% and 10% of the total packet stream will affect the quality
    // significantly (as per "Computer Networking for LANS to WANS: Hardware, Software and
    // Security"). For audio and video streaming, above 10-12% packet loss is unacceptable (as per
    // "ICTP-SDU: About PingER"). Thus choose 12% as a conservative default threshold to declare a
    // validation failure.
    private static final int IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DEFAULT = 12;

    /** Carriers can disable the detector by setting the threshold to -1 */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR = -1;

    private static final int POLL_IPSEC_STATE_INTERVAL_SECONDS_DEFAULT = 20;

    // By default, there's no maximum limit enforced
    private static final int MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED = -1;

    private long mPollIpSecStateIntervalMs;
    private int mPacketLossRatePercentThreshold;
    private int mMaxSeqNumIncreasePerSecond;

    @NonNull private final Handler mHandler;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final ConnectivityManager mConnectivityManager;
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
        mConnectivityManager =
                getVcnContext().getContext().getSystemService(ConnectivityManager.class);

        mPacketLossCalculator = deps.getPacketLossCalculator();

        mPollIpSecStateIntervalMs = getPollIpSecStateIntervalMs(carrierConfig);
        mPacketLossRatePercentThreshold = getPacketLossRatePercentThreshold(carrierConfig);
        mMaxSeqNumIncreasePerSecond = getMaxSeqNumIncreasePerSecond(carrierConfig);

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

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static int getMaxSeqNumIncreasePerSecond(@Nullable PersistableBundleWrapper carrierConfig) {
        int maxSeqNumIncrease = MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED;
        if (Flags.handleSeqNumLeap() && carrierConfig != null) {
            maxSeqNumIncrease =
                    carrierConfig.getInt(
                            VcnManager.VCN_NETWORK_SELECTION_MAX_SEQ_NUM_INCREASE_PER_SECOND_KEY,
                            MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED);
        }

        if (maxSeqNumIncrease < MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED) {
            logE(TAG, "Invalid value of MAX_SEQ_NUM_INCREASE_PER_SECOND_KEY " + maxSeqNumIncrease);
            return MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED;
        }

        return maxSeqNumIncrease;
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

        if (canStart()) {
            start();
        }
    }

    @Override
    public void setCarrierConfig(@Nullable PersistableBundleWrapper carrierConfig) {
        // The already scheduled event will not be affected. The followup events will be scheduled
        // with the new interval
        mPollIpSecStateIntervalMs = getPollIpSecStateIntervalMs(carrierConfig);

        if (Flags.handleSeqNumLeap()) {
            mPacketLossRatePercentThreshold = getPacketLossRatePercentThreshold(carrierConfig);
            mMaxSeqNumIncreasePerSecond = getMaxSeqNumIncreasePerSecond(carrierConfig);
        }

        if (canStart() != isStarted()) {
            if (canStart()) {
                start();
            } else {
                stop();
            }
        }
    }

    @Override
    public void onLinkPropertiesOrCapabilitiesChanged() {
        if (!isStarted()) return;

        reschedulePolling();
    }

    private void reschedulePolling() {
        mHandler.removeCallbacksAndEqualMessages(mCancellationToken);
        mHandler.postDelayed(new PollIpSecStateRunnable(), mCancellationToken, 0L);
    }

    private boolean canStart() {
        return mInboundTransform != null
                && mPacketLossRatePercentThreshold
                        != IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR;
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
                    .requestIpSecTransformState(
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

        final PacketLossCalculationResult calculateResult =
                mPacketLossCalculator.getPacketLossRatePercentage(
                        mLastIpSecTransformState,
                        state,
                        mMaxSeqNumIncreasePerSecond,
                        getLogPrefix());

        if (calculateResult.getResultType() == PACKET_LOSS_RATE_INVALID) {
            return;
        }

        final String logMsg =
                "calculateResult: "
                        + calculateResult
                        + "% in the past "
                        + (state.getTimestampMillis()
                                - mLastIpSecTransformState.getTimestampMillis())
                        + "ms";

        mLastIpSecTransformState = state;
        if (calculateResult.getPacketLossRatePercent() < mPacketLossRatePercentThreshold) {
            logV(logMsg);

            // In both "valid" or "unusual_seq_num_leap" cases, notify that the network has passed
            // the validation
            onValidationResultReceivedInternal(false /* isFailed */);
        } else {
            logInfo(logMsg);

            if (calculateResult.getResultType() == PACKET_LOSS_RATE_VALID) {
                onValidationResultReceivedInternal(true /* isFailed */);
            }

            // In both "valid" or "unusual_seq_num_leap" cases, trigger network validation
            if (Flags.validateNetworkOnIpsecLoss()) {
                // Trigger re-validation of the underlying network; if it fails, the VCN will
                // attempt to migrate away.
                mConnectivityManager.reportNetworkConnectivity(
                        getNetwork(), false /* hasConnectivity */);
            }
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class PacketLossCalculator {
        /** Calculate the packet loss rate between two timestamps */
        public PacketLossCalculationResult getPacketLossRatePercentage(
                @NonNull IpSecTransformState oldState,
                @NonNull IpSecTransformState newState,
                int maxSeqNumIncreasePerSecond,
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
                return PacketLossCalculationResult.invalid();
            }

            boolean isUnusualSeqNumLeap = false;

            // Handle sequence number leap
            if (Flags.handleSeqNumLeap()
                    && maxSeqNumIncreasePerSecond != MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED) {
                final long timeDiffMillis =
                        newState.getTimestampMillis() - oldState.getTimestampMillis();
                final long maxSeqNumIncrease = timeDiffMillis * maxSeqNumIncreasePerSecond / 1000;

                // Sequence numbers are unsigned 32-bit values. If maxSeqNumIncrease overflows,
                // isUnusualSeqNumLeap can never be true.
                if (maxSeqNumIncrease >= 0 && newSeqHi - oldSeqHi >= maxSeqNumIncrease) {
                    isUnusualSeqNumLeap = true;
                }
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

            if (Flags.handleSeqNumLeap() && expectedPktCntDiff < MIN_VALID_EXPECTED_RX_PACKET_NUM) {
                // The sample size is too small to ensure a reliable detection result
                return PacketLossCalculationResult.invalid();
            }

            if (expectedPktCntDiff < 0
                    || expectedPktCntDiff == 0
                    || actualPktCntDiff < 0
                    || actualPktCntDiff > expectedPktCntDiff) {
                logWtf(TAG, "Impossible values for expectedPktCntDiff or" + " actualPktCntDiff");
                return PacketLossCalculationResult.invalid();
            }

            final int percent = 100 - (int) (actualPktCntDiff * 100 / expectedPktCntDiff);
            return isUnusualSeqNumLeap
                    ? PacketLossCalculationResult.unusualSeqNumLeap(percent)
                    : PacketLossCalculationResult.valid(percent);
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

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class PacketLossCalculationResult {
        @PacketLossResultType private final int mResultType;
        private final int mPacketLossRatePercent;

        private PacketLossCalculationResult(@PacketLossResultType int type, int percent) {
            mResultType = type;
            mPacketLossRatePercent = percent;
        }

        /** Construct an instance that contains a valid packet loss rate */
        public static PacketLossCalculationResult valid(int percent) {
            return new PacketLossCalculationResult(PACKET_LOSS_RATE_VALID, percent);
        }

        /** Construct an instance indicating the inability to get a valid packet loss rate */
        public static PacketLossCalculationResult invalid() {
            return new PacketLossCalculationResult(
                    PACKET_LOSS_RATE_INVALID, PACKET_LOSS_PERCENT_UNAVAILABLE);
        }

        /** Construct an instance indicating that there is an unusual sequence number leap */
        public static PacketLossCalculationResult unusualSeqNumLeap(int percent) {
            return new PacketLossCalculationResult(PACKET_LOSS_UNUSUAL_SEQ_NUM_LEAP, percent);
        }

        @PacketLossResultType
        public int getResultType() {
            return mResultType;
        }

        public int getPacketLossRatePercent() {
            return mPacketLossRatePercent;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mResultType, mPacketLossRatePercent);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof PacketLossCalculationResult)) {
                return false;
            }

            final PacketLossCalculationResult rhs = (PacketLossCalculationResult) other;
            return mResultType == rhs.mResultType
                    && mPacketLossRatePercent == rhs.mPacketLossRatePercent;
        }

        @Override
        public String toString() {
            return "mResultType: "
                    + mResultType
                    + " | mPacketLossRatePercent: "
                    + mPacketLossRatePercent;
        }
    }
}
