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

import static com.android.server.VcnManagementService.LOCAL_LOG;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.IpSecTransform;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.vcn.VcnManager;
import android.net.vcn.VcnUnderlyingNetworkTemplate;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * UnderlyingNetworkEvaluator evaluates the quality and priority class of a network candidate for
 * route selection.
 *
 * @hide
 */
public class UnderlyingNetworkEvaluator {
    private static final String TAG = UnderlyingNetworkEvaluator.class.getSimpleName();

    private static final int[] PENALTY_TIMEOUT_MINUTES_DEFAULT = new int[] {5};

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final Handler mHandler;
    @NonNull private final Object mCancellationToken = new Object();

    @NonNull private final UnderlyingNetworkRecord.Builder mNetworkRecordBuilder;

    @NonNull private final NetworkEvaluatorCallback mEvaluatorCallback;
    @NonNull private final List<NetworkMetricMonitor> mMetricMonitors = new ArrayList<>();

    @NonNull private final Dependencies mDependencies;

    // TODO: Support back-off timeouts
    private long mPenalizedTimeoutMs;

    private boolean mIsSelected;
    private boolean mIsPenalized;
    private int mPriorityClass = NetworkPriorityClassifier.PRIORITY_INVALID;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public UnderlyingNetworkEvaluator(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig,
            @NonNull NetworkEvaluatorCallback evaluatorCallback,
            @NonNull Dependencies dependencies) {
        mVcnContext = Objects.requireNonNull(vcnContext, "Missing vcnContext");
        mHandler = new Handler(mVcnContext.getLooper());

        mDependencies = Objects.requireNonNull(dependencies, "Missing dependencies");
        mEvaluatorCallback = Objects.requireNonNull(evaluatorCallback, "Missing deps");

        Objects.requireNonNull(underlyingNetworkTemplates, "Missing underlyingNetworkTemplates");
        Objects.requireNonNull(subscriptionGroup, "Missing subscriptionGroup");
        Objects.requireNonNull(lastSnapshot, "Missing lastSnapshot");

        mNetworkRecordBuilder =
                new UnderlyingNetworkRecord.Builder(
                        Objects.requireNonNull(network, "Missing network"));
        mIsSelected = false;
        mIsPenalized = false;
        mPenalizedTimeoutMs = getPenaltyTimeoutMs(carrierConfig);

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);

        if (isIpSecPacketLossDetectorEnabled()) {
            try {
                mMetricMonitors.add(
                        mDependencies.newIpSecPacketLossDetector(
                                mVcnContext,
                                mNetworkRecordBuilder.getNetwork(),
                                carrierConfig,
                                new MetricMonitorCallbackImpl()));
            } catch (IllegalAccessException e) {
                // No action. Do not add anything to mMetricMonitors
            }
        }
    }

    public UnderlyingNetworkEvaluator(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig,
            @NonNull NetworkEvaluatorCallback evaluatorCallback) {
        this(
                vcnContext,
                network,
                underlyingNetworkTemplates,
                subscriptionGroup,
                lastSnapshot,
                carrierConfig,
                evaluatorCallback,
                new Dependencies());
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        /** Get an IpSecPacketLossDetector instance */
        public IpSecPacketLossDetector newIpSecPacketLossDetector(
                @NonNull VcnContext vcnContext,
                @NonNull Network network,
                @Nullable PersistableBundleWrapper carrierConfig,
                @NonNull NetworkMetricMonitor.NetworkMetricMonitorCallback callback)
                throws IllegalAccessException {
            return new IpSecPacketLossDetector(vcnContext, network, carrierConfig, callback);
        }
    }

    /** Callback to notify caller to reevaluate network selection */
    public interface NetworkEvaluatorCallback {
        /**
         * Called when mIsPenalized changed
         *
         * <p>When receiving this call, UnderlyingNetworkController should reevaluate all network
         * candidates for VCN underlying network selection
         */
        void onEvaluationResultChanged();
    }

    private class MetricMonitorCallbackImpl
            implements NetworkMetricMonitor.NetworkMetricMonitorCallback {
        public void onValidationResultReceived() {
            mVcnContext.ensureRunningOnLooperThread();

            handleValidationResult();
        }
    }

    private void updatePriorityClass(
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        if (mNetworkRecordBuilder.isValid()) {
            mPriorityClass =
                    NetworkPriorityClassifier.calculatePriorityClass(
                            mVcnContext,
                            mNetworkRecordBuilder.build(),
                            underlyingNetworkTemplates,
                            subscriptionGroup,
                            lastSnapshot,
                            mIsSelected,
                            carrierConfig);
        } else {
            mPriorityClass = NetworkPriorityClassifier.PRIORITY_INVALID;
        }
    }

    private boolean isIpSecPacketLossDetectorEnabled() {
        return isIpSecPacketLossDetectorEnabled(mVcnContext);
    }

    private static boolean isIpSecPacketLossDetectorEnabled(VcnContext vcnContext) {
        return vcnContext.isFlagIpSecTransformStateEnabled()
                && vcnContext.isFlagNetworkMetricMonitorEnabled();
    }

    /** Get the comparator for UnderlyingNetworkEvaluator */
    public static Comparator<UnderlyingNetworkEvaluator> getComparator(VcnContext vcnContext) {
        return (left, right) -> {
            if (isIpSecPacketLossDetectorEnabled(vcnContext)) {
                if (left.mIsPenalized != right.mIsPenalized) {
                    // A penalized network should have lower priority which means a larger index
                    return left.mIsPenalized ? 1 : -1;
                }
            }

            final int leftIndex = left.mPriorityClass;
            final int rightIndex = right.mPriorityClass;

            // In the case of networks in the same priority class, prioritize based on other
            // criteria (eg. actively selected network, link metrics, etc)
            if (leftIndex == rightIndex) {
                // TODO: Improve the strategy of network selection when both UnderlyingNetworkRecord
                // fall into the same priority class.
                if (left.mIsSelected) {
                    return -1;
                }
                if (right.mIsSelected) {
                    return 1;
                }
            }
            return Integer.compare(leftIndex, rightIndex);
        };
    }

    private static long getPenaltyTimeoutMs(@Nullable PersistableBundleWrapper carrierConfig) {
        final int[] timeoutMinuteList;

        if (carrierConfig != null) {
            timeoutMinuteList =
                    carrierConfig.getIntArray(
                            VcnManager.VCN_NETWORK_SELECTION_PENALTY_TIMEOUT_MINUTES_LIST_KEY,
                            PENALTY_TIMEOUT_MINUTES_DEFAULT);
        } else {
            timeoutMinuteList = PENALTY_TIMEOUT_MINUTES_DEFAULT;
        }

        // TODO: Add the support of back-off timeouts and return the full list
        return TimeUnit.MINUTES.toMillis(timeoutMinuteList[0]);
    }

    private void handleValidationResult() {
        final boolean wasPenalized = mIsPenalized;
        mIsPenalized = false;
        for (NetworkMetricMonitor monitor : mMetricMonitors) {
            mIsPenalized |= monitor.isValidationFailed();
        }

        if (wasPenalized == mIsPenalized) {
            return;
        }

        logInfo(
                "#handleValidationResult: wasPenalized "
                        + wasPenalized
                        + " mIsPenalized "
                        + mIsPenalized);

        if (mIsPenalized) {
            mHandler.postDelayed(
                    new ExitPenaltyBoxRunnable(), mCancellationToken, mPenalizedTimeoutMs);
        } else {
            // Exit the penalty box
            mHandler.removeCallbacksAndEqualMessages(mCancellationToken);
        }
        mEvaluatorCallback.onEvaluationResultChanged();
    }

    public class ExitPenaltyBoxRunnable implements Runnable {
        @Override
        public void run() {
            if (!mIsPenalized) {
                logWtf("Evaluator not being penalized but ExitPenaltyBoxRunnable was scheduled");
                return;
            }

            // TODO: There might be a future metric monitor (e.g. ping) that will require the
            // validation to pass before exiting the penalty box.
            mIsPenalized = false;
            mEvaluatorCallback.onEvaluationResultChanged();
        }
    }

    /** Set the NetworkCapabilities */
    public void setNetworkCapabilities(
            @NonNull NetworkCapabilities nc,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mNetworkRecordBuilder.setNetworkCapabilities(nc);

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    /** Set the LinkProperties */
    public void setLinkProperties(
            @NonNull LinkProperties lp,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mNetworkRecordBuilder.setLinkProperties(lp);

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    /** Set whether the network is blocked */
    public void setIsBlocked(
            boolean isBlocked,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mNetworkRecordBuilder.setIsBlocked(isBlocked);

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);
    }

    /** Set whether the network is selected as VCN's underlying network */
    public void setIsSelected(
            boolean isSelected,
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        mIsSelected = isSelected;

        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);

        for (NetworkMetricMonitor monitor : mMetricMonitors) {
            monitor.setIsSelectedUnderlyingNetwork(isSelected);
        }
    }

    /**
     * Update the last TelephonySubscriptionSnapshot and carrier config to reevaluate the network
     */
    public void reevaluate(
            @NonNull List<VcnUnderlyingNetworkTemplate> underlyingNetworkTemplates,
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull TelephonySubscriptionSnapshot lastSnapshot,
            @Nullable PersistableBundleWrapper carrierConfig) {
        updatePriorityClass(
                underlyingNetworkTemplates, subscriptionGroup, lastSnapshot, carrierConfig);

        // The already scheduled event will not be affected. The followup events will be scheduled
        // with the new timeout
        mPenalizedTimeoutMs = getPenaltyTimeoutMs(carrierConfig);

        for (NetworkMetricMonitor monitor : mMetricMonitors) {
            monitor.setCarrierConfig(carrierConfig);
        }
    }

    /** Update the inbound IpSecTransform applied to the network */
    public void setInboundTransform(@NonNull IpSecTransform transform) {
        if (!mIsSelected) {
            logWtf("setInboundTransform on an unselected evaluator");
            return;
        }

        for (NetworkMetricMonitor monitor : mMetricMonitors) {
            monitor.setInboundTransform(transform);
        }
    }

    /** Close the evaluator and stop all the underlying network metric monitors */
    public void close() {
        mHandler.removeCallbacksAndEqualMessages(mCancellationToken);

        for (NetworkMetricMonitor monitor : mMetricMonitors) {
            monitor.close();
        }
    }

    /** Return whether this network evaluator is valid */
    public boolean isValid() {
        return mNetworkRecordBuilder.isValid();
    }

    /** Return the network */
    public Network getNetwork() {
        return mNetworkRecordBuilder.getNetwork();
    }

    /** Return the network record */
    public UnderlyingNetworkRecord getNetworkRecord() {
        return mNetworkRecordBuilder.build();
    }

    /** Return the priority class for network selection */
    public int getPriorityClass() {
        return mPriorityClass;
    }

    /** Return whether the network is being penalized */
    public boolean isPenalized() {
        return mIsPenalized;
    }

    /** Dump the information of this instance */
    public void dump(IndentingPrintWriter pw) {
        pw.println("UnderlyingNetworkEvaluator:");
        pw.increaseIndent();

        if (mNetworkRecordBuilder.isValid()) {
            getNetworkRecord().dump(pw);
        } else {
            pw.println(
                    "UnderlyingNetworkRecord incomplete: mNetwork: "
                            + mNetworkRecordBuilder.getNetwork());
        }

        pw.println("mIsSelected: " + mIsSelected);
        pw.println("mPriorityClass: " + mPriorityClass);
        pw.println("mIsPenalized: " + mIsPenalized);

        pw.decreaseIndent();
    }

    private String getLogPrefix() {
        return "[Network " + mNetworkRecordBuilder.getNetwork() + "] ";
    }

    private void logInfo(String msg) {
        Slog.i(TAG, getLogPrefix() + msg);
        LOCAL_LOG.log("[INFO ] " + TAG + getLogPrefix() + msg);
    }

    private void logWtf(String msg) {
        Slog.wtf(TAG, getLogPrefix() + msg);
        LOCAL_LOG.log("[WTF ] " + TAG + getLogPrefix() + msg);
    }
}
