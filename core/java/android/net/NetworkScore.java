/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.net;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Object representing the quality of a network as perceived by the user.
 *
 * A NetworkScore object represents the characteristics of a network that affects how good the
 * network is considered for a particular use.
 *
 * This class is not thread-safe.
 * @hide
 */
@TestApi
@SystemApi
public final class NetworkScore implements Parcelable {
    /** An object containing scoring-relevant metrics for a network. */
    public static class Metrics {
        /** Value meaning the latency is unknown. */
        public static final int LATENCY_UNKNOWN = -1;

        /** Value meaning the bandwidth is unknown. */
        public static final int BANDWIDTH_UNKNOWN = -1;

        /**
         * Round-trip delay in milliseconds to the relevant destination for this Metrics object.
         *
         * LATENCY_UNKNOWN if unknown.
         */
        @IntRange(from = LATENCY_UNKNOWN)
        public final int latencyMs;

        /**
         * Downlink in kB/s with the relevant destination for this Metrics object.
         *
         * BANDWIDTH_UNKNOWN if unknown. If directional bandwidth is unknown, up and downlink
         * bandwidth can have the same value.
         */
        @IntRange(from = BANDWIDTH_UNKNOWN)
        public final int downlinkBandwidthKBps;

        /**
         * Uplink in kB/s with the relevant destination for this Metrics object.
         *
         * BANDWIDTH_UNKNOWN if unknown. If directional bandwidth is unknown, up and downlink
         * bandwidth can have the same value.
         */
        @IntRange(from = BANDWIDTH_UNKNOWN)
        public final int uplinkBandwidthKBps;

        /** Constructor */
        public Metrics(@IntRange(from = LATENCY_UNKNOWN) final int latency,
                @IntRange(from = BANDWIDTH_UNKNOWN) final int downlinkBandwidth,
                @IntRange(from = BANDWIDTH_UNKNOWN) final int uplinkBandwidth) {
            latencyMs = latency;
            downlinkBandwidthKBps = downlinkBandwidth;
            uplinkBandwidthKBps = uplinkBandwidth;
        }

        /** toString */
        public String toString() {
            return "latency = " + latencyMs + " downlinkBandwidth = " + downlinkBandwidthKBps
                    + "uplinkBandwidth = " + uplinkBandwidthKBps;
        }

        @NonNull
        public static final Metrics EMPTY =
                new Metrics(LATENCY_UNKNOWN, BANDWIDTH_UNKNOWN, BANDWIDTH_UNKNOWN);
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = "POLICY_", value = {
            POLICY_LOCKDOWN_VPN,
            POLICY_VPN,
            POLICY_IGNORE_ON_WIFI,
            POLICY_DEFAULT_SUBSCRIPTION
    })
    public @interface Policy {
    }

    /**
     * This network is a VPN with lockdown enabled.
     *
     * If a network with this bit is present in the list of candidates and not connected,
     * no network can satisfy the request.
     */
    public static final int POLICY_LOCKDOWN_VPN = 1 << 0;

    /**
     * This network is a VPN.
     *
     * If a network with this bit is present and the request UID is included in the UID ranges
     * of this network, it outscores all other networks without this bit.
     */
    public static final int POLICY_VPN = 1 << 1;

    /**
     * This network should not be used if a previously validated WiFi network is available.
     *
     * If a network with this bit is present and a previously validated WiFi is present too, the
     * network with this bit is outscored by it. This stays true if the WiFi network
     * becomes unvalidated : this network will not be considered.
     */
    public static final int POLICY_IGNORE_ON_WIFI = 1 << 2;

    /**
     * This network is the default subscription for this transport.
     *
     * If a network with this bit is present, other networks of the same transport without this
     * bit are outscored by it. A device with two active subscriptions and a setting
     * to decide the default one would have this policy bit on the network for the default
     * subscription so that when both are active at the same time, the device chooses the
     * network associated with the default subscription rather than the one with the best link
     * quality (which will happen if policy doesn't dictate otherwise).
     */
    public static final int POLICY_DEFAULT_SUBSCRIPTION = 1 << 3;

    /**
     * Policy bits for this network. Filled in by the NetworkAgent.
     */
    private final int mPolicy;

    /**
     * Predicted metrics to the gateway (it contains latency and bandwidth to the gateway).
     * This is filled by the NetworkAgent with the theoretical values of the link if available,
     * although they may fill it with predictions from historical data if available.
     * Note that while this member cannot be null, any and all of its members can be unknown.
     */
    @NonNull
    private final Metrics mLinkLayerMetrics;

    /**
     * Predicted metrics to representative servers.
     * This is filled by connectivity with (if available) a best-effort estimate of the performance
     * information to servers the user connects to in similar circumstances, and predicted from
     * actual measurements if possible.
     * Note that while this member cannot be null, any and all of its members can be unknown.
     */
    @NonNull
    private final Metrics mEndToEndMetrics;

    /** Value meaning the signal strength is unknown. */
    public static final int UNKNOWN_SIGNAL_STRENGTH = -1;

    /** The smallest meaningful signal strength. */
    public static final int MIN_SIGNAL_STRENGTH = 0;

    /** The largest meaningful signal strength. */
    public static final int MAX_SIGNAL_STRENGTH = 1000;

    /**
     * User-visible measure of the strength of the signal normalized 1~1000.
     * This represents a measure of the signal strength for this network.
     * If unknown, this has value UNKNOWN_SIGNAL_STRENGTH.
     */
    // A good way to populate this value is to fill it with the number of bars displayed in
    // the system UI, scaled 0 to 1000. This is what the user sees and it makes sense to them.
    // Cellular for example could quantize the ASU value (see SignalStrength#getAsuValue) into
    // this, while WiFi could scale the RSSI (see WifiManager#calculateSignalLevel).
    @IntRange(from = UNKNOWN_SIGNAL_STRENGTH, to = MAX_SIGNAL_STRENGTH)
    private final int mSignalStrength;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = "RANGE_", value = {
            RANGE_UNKNOWN, RANGE_CLOSE, RANGE_SHORT, RANGE_MEDIUM, RANGE_LONG
    })
    public @interface Range {
    }

    /**
     * The range of this network is not known.
     * This can be used by VPN the range of which depends on the range of the underlying network.
     */
    public static final int RANGE_UNKNOWN = 0;

    /**
     * This network typically only operates at close range, like an NFC beacon.
     */
    public static final int RANGE_CLOSE = 1;

    /**
     * This network typically operates at a range of a few meters to a few dozen meters, like WiFi.
     */
    public static final int RANGE_SHORT = 2;

    /**
     * This network typically operates at a range of a few dozen to a few hundred meters, like CBRS.
     */
    public static final int RANGE_MEDIUM = 3;

    /**
     * This network typically offers continuous connectivity up to many kilometers away, like LTE.
     */
    public static final int RANGE_LONG = 4;

    /**
     * The typical range of this networking technology.
     *
     * This is one of the RANGE_* constants and is filled by the NetworkAgent.
     * This may be useful when evaluating how desirable a network is, because for two networks that
     * have equivalent performance and cost, the one that won't lose IP continuity when the user
     * moves is probably preferable.
     * Agents should fill this with the largest typical range this technology provides. See the
     * descriptions of the individual constants for guidance.
     *
     * If unknown, this is set to RANGE_UNKNOWN.
     */
    @Range private final int mRange;

    /**
     * A prediction of whether this network is likely to be unusable in a few seconds.
     *
     * NetworkAgents set this to true to mean they are aware that usability is limited due to
     * low signal strength, congestion, or other reasons, and indicates that the system should
     * only use this network as a last resort. An example would be a WiFi network when the device
     * is about to move outside of range.
     *
     * This is filled by the NetworkAgent. Agents that don't know whether this network is likely
     * to be unusable soon should set this to false.
     */
    private final boolean mExiting;

    /**
     * The legacy score, as a migration strategy from Q to R.
     * STOPSHIP : remove this before R ships.
     */
    private final int mLegacyScore;

    /**
     * Create a new NetworkScore object.
     */
    private NetworkScore(@Policy final int policy, @Nullable final Metrics l2Perf,
            @Nullable final Metrics e2ePerf,
            @IntRange(from = UNKNOWN_SIGNAL_STRENGTH, to = MAX_SIGNAL_STRENGTH)
            final int signalStrength,
            @Range final int range, final boolean exiting, final int legacyScore) {
        mPolicy = policy;
        mLinkLayerMetrics = null != l2Perf ? l2Perf : Metrics.EMPTY;
        mEndToEndMetrics = null != e2ePerf ? e2ePerf : Metrics.EMPTY;
        mSignalStrength = signalStrength;
        mRange = range;
        mExiting = exiting;
        mLegacyScore = legacyScore;
    }

    /**
     * Utility function to return a copy of this with a different exiting value.
     */
    @NonNull public NetworkScore withExiting(final boolean exiting) {
        return new NetworkScore(mPolicy, mLinkLayerMetrics, mEndToEndMetrics,
                mSignalStrength, mRange, exiting, mLegacyScore);
    }

    /**
     * Utility function to return a copy of this with a different signal strength.
     */
    @NonNull public NetworkScore withSignalStrength(
            @IntRange(from = UNKNOWN_SIGNAL_STRENGTH) final int signalStrength) {
        return new NetworkScore(mPolicy, mLinkLayerMetrics, mEndToEndMetrics,
                signalStrength, mRange, mExiting, mLegacyScore);
    }

    /**
     * Returns whether this network has a particular policy flag.
     * @param policy the policy, as one of the POLICY_* constants.
     */
    public boolean hasPolicy(@Policy final int policy) {
        return 0 != (mPolicy & policy);
    }

    /**
     * Returns the Metrics representing the performance of the link layer.
     *
     * This contains the theoretical performance of the link, if available.
     * Note that while this function cannot return null, any and/or all of the members of the
     * returned object can be null if unknown.
     */
    @NonNull public Metrics getLinkLayerMetrics() {
        return mLinkLayerMetrics;
    }

    /**
     * Returns the Metrics representing the end-to-end performance of the network.
     *
     * This contains the end-to-end performance of the link, if available.
     * Note that while this function cannot return null, any and/or all of the members of the
     * returned object can be null if unknown.
     */
    @NonNull public Metrics getEndToEndMetrics() {
        return mEndToEndMetrics;
    }

    /**
     * Returns the signal strength of this network normalized 0~1000, or UNKNOWN_SIGNAL_STRENGTH.
     */
    @IntRange(from = UNKNOWN_SIGNAL_STRENGTH, to = MAX_SIGNAL_STRENGTH)
    public int getSignalStrength() {
        return mSignalStrength;
    }

    /**
     * Returns the typical range of this network technology as one of the RANGE_* constants.
     */
    @Range public int getRange() {
        return mRange;
    }

    /** Returns a prediction of whether this network is likely to be unusable in a few seconds. */
    public boolean isExiting() {
        return mExiting;
    }

    /**
     * Get the legacy score.
     * @hide
     */
    public int getLegacyScore() {
        return mLegacyScore;
    }

    /** Builder for NetworkScore. */
    public static class Builder {
        private int mPolicy = 0;
        @NonNull
        private Metrics mLinkLayerMetrics = new Metrics(Metrics.LATENCY_UNKNOWN,
                Metrics.BANDWIDTH_UNKNOWN, Metrics.BANDWIDTH_UNKNOWN);
        @NonNull
        private Metrics mEndToMetrics = new Metrics(Metrics.LATENCY_UNKNOWN,
                Metrics.BANDWIDTH_UNKNOWN, Metrics.BANDWIDTH_UNKNOWN);
        private int mSignalStrength = UNKNOWN_SIGNAL_STRENGTH;
        private int mRange = RANGE_UNKNOWN;
        private boolean mExiting = false;
        private int mLegacyScore = 0;
        @NonNull private Bundle mExtensions = new Bundle();

        /** Create a new builder. */
        public Builder() { }

        /** Add a policy flag. */
        @NonNull public Builder addPolicy(@Policy final int policy) {
            mPolicy |= policy;
            return this;
        }

        /** Clear a policy flag */
        @NonNull public Builder clearPolicy(@Policy final int policy) {
            mPolicy &= ~policy;
            return this;
        }

        /** Set the link layer metrics. */
        @NonNull public Builder setLinkLayerMetrics(@NonNull final Metrics linkLayerMetrics) {
            mLinkLayerMetrics = linkLayerMetrics;
            return this;
        }

        /** Set the end-to-end metrics. */
        @NonNull public Builder setEndToEndMetrics(@NonNull final Metrics endToEndMetrics) {
            mEndToMetrics = endToEndMetrics;
            return this;
        }

        /** Set the signal strength. */
        @NonNull public Builder setSignalStrength(
                @IntRange(from = UNKNOWN_SIGNAL_STRENGTH, to = MAX_SIGNAL_STRENGTH)
                        final int signalStrength) {
            mSignalStrength = signalStrength;
            return this;
        }

        /** Set the range. */
        @NonNull public Builder setRange(@Range final int range) {
            mRange = range;
            return this;
        }

        /** Set whether this network is exiting. */
        @NonNull public Builder setExiting(final boolean exiting) {
            mExiting = exiting;
            return this;
        }

        /** Add a parcelable extension. */
        @NonNull public Builder setLegacyScore(final int legacyScore) {
            mLegacyScore = legacyScore;
            return this;
        }

        /** Build the NetworkScore object represented by this builder. */
        @NonNull public NetworkScore build() {
            return new NetworkScore(mPolicy, mLinkLayerMetrics, mEndToMetrics,
                    mSignalStrength, mRange, mExiting, mLegacyScore);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPolicy);
        dest.writeInt(mLinkLayerMetrics.latencyMs);
        dest.writeInt(mLinkLayerMetrics.downlinkBandwidthKBps);
        dest.writeInt(mLinkLayerMetrics.uplinkBandwidthKBps);
        dest.writeInt(mEndToEndMetrics.latencyMs);
        dest.writeInt(mEndToEndMetrics.downlinkBandwidthKBps);
        dest.writeInt(mEndToEndMetrics.uplinkBandwidthKBps);
        dest.writeInt(mSignalStrength);
        dest.writeInt(mRange);
        dest.writeBoolean(mExiting);
        dest.writeInt(mLegacyScore);
    }

    public static final @NonNull Creator<NetworkScore> CREATOR = new Creator<NetworkScore>() {
        @Override
        public NetworkScore createFromParcel(@NonNull Parcel in) {
            return new NetworkScore(in);
        }

        @Override
        public NetworkScore[] newArray(int size) {
            return new NetworkScore[size];
        }
    };

    private NetworkScore(@NonNull Parcel in) {
        mPolicy = in.readInt();
        mLinkLayerMetrics = new Metrics(in.readInt(), in.readInt(), in.readInt());
        mEndToEndMetrics = new Metrics(in.readInt(), in.readInt(), in.readInt());
        mSignalStrength = in.readInt();
        mRange = in.readInt();
        mExiting = in.readBoolean();
        mLegacyScore = in.readInt();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof NetworkScore)) {
            return false;
        }
        final NetworkScore other = (NetworkScore) obj;
        return mPolicy == other.mPolicy
                && mLinkLayerMetrics.latencyMs == other.mLinkLayerMetrics.latencyMs
                && mLinkLayerMetrics.uplinkBandwidthKBps
                == other.mLinkLayerMetrics.uplinkBandwidthKBps
                && mEndToEndMetrics.latencyMs == other.mEndToEndMetrics.latencyMs
                && mEndToEndMetrics.uplinkBandwidthKBps
                == other.mEndToEndMetrics.uplinkBandwidthKBps
                && mSignalStrength == other.mSignalStrength
                && mRange == other.mRange
                && mExiting == other.mExiting
                && mLegacyScore == other.mLegacyScore;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPolicy,
                mLinkLayerMetrics.latencyMs, mLinkLayerMetrics.uplinkBandwidthKBps,
                mEndToEndMetrics.latencyMs, mEndToEndMetrics.uplinkBandwidthKBps,
                mSignalStrength, mRange, mExiting, mLegacyScore);
    }

    /** Convert to a string */
    public String toString() {
        return "NetworkScore ["
                + "Policy = " + mPolicy
                + " LinkLayerMetrics = " + mLinkLayerMetrics
                + " EndToEndMetrics = " + mEndToEndMetrics
                + " SignalStrength = " + mSignalStrength
                + " Range = " + mRange
                + " Exiting = " + mExiting
                + " LegacyScore = " + mLegacyScore
                + "]";
    }
}
