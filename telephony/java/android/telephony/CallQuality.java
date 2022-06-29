/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Parcelable object to handle call quality.
 * <p>
 * Currently this supports IMS calls.
 * <p>
 * It provides the call quality level, duration, and additional information related to RTP packets,
 * jitter and delay.
 * <p>
 * If there are multiple active calls, the CallQuality will pertain to the call in the foreground.
 *
 * @hide
 */
@SystemApi
public final class CallQuality implements Parcelable {

    // Constants representing the call quality level (see #CallQuality);
    public static final int CALL_QUALITY_EXCELLENT = 0;
    public static final int CALL_QUALITY_GOOD = 1;
    public static final int CALL_QUALITY_FAIR = 2;
    public static final int CALL_QUALITY_POOR = 3;
    public static final int CALL_QUALITY_BAD = 4;
    public static final int CALL_QUALITY_NOT_AVAILABLE = 5;

    /**
     * Call quality
     * @hide
     */
    @IntDef(prefix = { "CALL_QUALITY_" }, value = {
            CALL_QUALITY_EXCELLENT,
            CALL_QUALITY_GOOD,
            CALL_QUALITY_FAIR,
            CALL_QUALITY_POOR,
            CALL_QUALITY_BAD,
            CALL_QUALITY_NOT_AVAILABLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallQualityLevel {}

    @CallQualityLevel
    private int mDownlinkCallQualityLevel;
    @CallQualityLevel
    private int mUplinkCallQualityLevel;
    private int mCallDuration;
    private int mNumRtpPacketsTransmitted;
    private int mNumRtpPacketsReceived;
    private int mNumRtpPacketsTransmittedLost;
    private int mNumRtpPacketsNotReceived;
    private int mAverageRelativeJitter;
    private int mMaxRelativeJitter;
    private int mAverageRoundTripTime;
    private int mCodecType;
    private boolean mRtpInactivityDetected;
    private boolean mRxSilenceDetected;
    private boolean mTxSilenceDetected;
    private int mNumVoiceFrames;
    private int mNumNoDataFrames;
    private int mNumDroppedRtpPackets;
    private long mMinPlayoutDelayMillis;
    private long mMaxPlayoutDelayMillis;
    private int mNumRtpSidPacketsReceived;
    private int mNumRtpDuplicatePackets;

    /** @hide **/
    public CallQuality(Parcel in) {
        mDownlinkCallQualityLevel = in.readInt();
        mUplinkCallQualityLevel = in.readInt();
        mCallDuration = in.readInt();
        mNumRtpPacketsTransmitted = in.readInt();
        mNumRtpPacketsReceived = in.readInt();
        mNumRtpPacketsTransmittedLost = in.readInt();
        mNumRtpPacketsNotReceived = in.readInt();
        mAverageRelativeJitter = in.readInt();
        mMaxRelativeJitter = in.readInt();
        mAverageRoundTripTime = in.readInt();
        mCodecType = in.readInt();
        mRtpInactivityDetected = in.readBoolean();
        mRxSilenceDetected = in.readBoolean();
        mTxSilenceDetected = in.readBoolean();
        mNumVoiceFrames = in.readInt();
        mNumNoDataFrames = in.readInt();
        mNumDroppedRtpPackets = in.readInt();
        mMinPlayoutDelayMillis = in.readLong();
        mMaxPlayoutDelayMillis = in.readLong();
        mNumRtpSidPacketsReceived = in.readInt();
        mNumRtpDuplicatePackets = in.readInt();
    }

    /** @hide **/
    public CallQuality() {
    }

    /**
     * Constructor.
     *
     * @param callQualityLevel the call quality level (see #CallQualityLevel)
     * @param callDuration the call duration in milliseconds
     * @param numRtpPacketsTransmitted RTP packets sent to network
     * @param numRtpPacketsReceived RTP packets received from network
     * @param numRtpPacketsTransmittedLost RTP packets which were lost in network and never
     * transmitted
     * @param numRtpPacketsNotReceived RTP packets which were lost in network and never received
     * @param averageRelativeJitter average relative jitter in milliseconds
     * @param maxRelativeJitter maximum relative jitter in milliseconds
     * @param averageRoundTripTime average round trip delay in milliseconds
     * @param codecType the codec type
     */
    public CallQuality(
            @CallQualityLevel int downlinkCallQualityLevel,
            @CallQualityLevel int uplinkCallQualityLevel,
            int callDuration,
            int numRtpPacketsTransmitted,
            int numRtpPacketsReceived,
            int numRtpPacketsTransmittedLost,
            int numRtpPacketsNotReceived,
            int averageRelativeJitter,
            int maxRelativeJitter,
            int averageRoundTripTime,
            int codecType) {
        this(downlinkCallQualityLevel, uplinkCallQualityLevel, callDuration,
            numRtpPacketsTransmitted, numRtpPacketsReceived, numRtpPacketsTransmittedLost,
            numRtpPacketsNotReceived, averageRelativeJitter, maxRelativeJitter,
            averageRoundTripTime, codecType, false, false, false);
    }

    /**
     * Constructor.
     *
     * @param callQualityLevel the call quality level (see #CallQualityLevel)
     * @param callDuration the call duration in milliseconds
     * @param numRtpPacketsTransmitted RTP packets sent to network
     * @param numRtpPacketsReceived RTP packets received from network
     * @param numRtpPacketsTransmittedLost RTP packets which were lost in network and never
     * transmitted
     * @param numRtpPacketsNotReceived RTP packets which were lost in network and never received
     * @param averageRelativeJitter average relative jitter in milliseconds
     * @param maxRelativeJitter maximum relative jitter in milliseconds
     * @param averageRoundTripTime average round trip delay in milliseconds
     * @param codecType the codec type
     * @param rtpInactivityDetected True if no incoming RTP is received for a continuous duration of
     * 4 seconds
     * @param rxSilenceDetected True if only silence RTP packets are received for 20 seconds
     * immediately after call is connected
     * @param txSilenceDetected True if only silence RTP packets are sent for 20 seconds immediately
     * after call is connected
     */
    public CallQuality(
            @CallQualityLevel int downlinkCallQualityLevel,
            @CallQualityLevel int uplinkCallQualityLevel,
            int callDuration,
            int numRtpPacketsTransmitted,
            int numRtpPacketsReceived,
            int numRtpPacketsTransmittedLost,
            int numRtpPacketsNotReceived,
            int averageRelativeJitter,
            int maxRelativeJitter,
            int averageRoundTripTime,
            int codecType,
            boolean rtpInactivityDetected,
            boolean rxSilenceDetected,
            boolean txSilenceDetected) {
        this.mDownlinkCallQualityLevel = downlinkCallQualityLevel;
        this.mUplinkCallQualityLevel = uplinkCallQualityLevel;
        this.mCallDuration = callDuration;
        this.mNumRtpPacketsTransmitted = numRtpPacketsTransmitted;
        this.mNumRtpPacketsReceived = numRtpPacketsReceived;
        this.mNumRtpPacketsTransmittedLost = numRtpPacketsTransmittedLost;
        this.mNumRtpPacketsNotReceived = numRtpPacketsNotReceived;
        this.mAverageRelativeJitter = averageRelativeJitter;
        this.mMaxRelativeJitter = maxRelativeJitter;
        this.mAverageRoundTripTime = averageRoundTripTime;
        this.mCodecType = codecType;
        this.mRtpInactivityDetected = rtpInactivityDetected;
        this.mRxSilenceDetected = rxSilenceDetected;
        this.mTxSilenceDetected = txSilenceDetected;
    }

    // getters
    /**
     * Returns the downlink CallQualityLevel for a given ongoing call.
     */
    @CallQualityLevel
    public int getDownlinkCallQualityLevel() {
        return mDownlinkCallQualityLevel;
    }

    /**
     * Returns the uplink CallQualityLevel for a given ongoing call.
     */
    @CallQualityLevel
    public int getUplinkCallQualityLevel() {
        return mUplinkCallQualityLevel;
    }

    /**
     * Returns the duration of the call, in milliseconds.
     */
    public int getCallDuration() {
        return mCallDuration;
    }

    /**
     * Returns the total number of RTP packets transmitted by this device for a given ongoing call.
     */
    public int getNumRtpPacketsTransmitted() {
        return mNumRtpPacketsTransmitted;
    }

    /**
     * Returns the total number of RTP packets received by this device for a given ongoing call.
     */
    public int getNumRtpPacketsReceived() {
        return mNumRtpPacketsReceived;
    }

    /**
     * Returns the number of RTP packets which were sent by this device but were lost in the
     * network before reaching the other party.
     */
    public int getNumRtpPacketsTransmittedLost() {
        return mNumRtpPacketsTransmittedLost;
    }

    /**
     * Returns the number of RTP packets which were sent by the other party but were lost in the
     * network before reaching this device.
     */
    public int getNumRtpPacketsNotReceived() {
        return mNumRtpPacketsNotReceived;
    }

    /**
     * Returns the average relative jitter in milliseconds. Jitter represents the amount of variance
     * in interarrival time of packets, for example, if two packets are sent 2 milliseconds apart
     * but received 3 milliseconds apart, the relative jitter between those packets is 1
     * millisecond.
     *
     * <p>See RFC 3550 for more information on jitter calculations.
     */
    public int getAverageRelativeJitter() {
        return mAverageRelativeJitter;
    }

    /**
     * Returns the maximum relative jitter for a given ongoing call. Jitter represents the amount of
     * variance in interarrival time of packets, for example, if two packets are sent 2 milliseconds
     * apart but received 3 milliseconds apart, the relative jitter between those packets is 1
     * millisecond.
     *
     * <p>See RFC 3550 for more information on jitter calculations.
     */
    public int getMaxRelativeJitter() {
        return mMaxRelativeJitter;
    }

    /**
     * Returns the average round trip time in milliseconds.
     */
    public int getAverageRoundTripTime() {
        return mAverageRoundTripTime;
    }

    /**
     * Returns true if no rtp packets are received continuously for the last 4 seconds
     */
    public boolean isRtpInactivityDetected() {
        return mRtpInactivityDetected;
    }

    /**
     * Returns true if only silence rtp packets are received for a duration of 20 seconds starting
     * at call setup
     */
    public boolean isIncomingSilenceDetectedAtCallSetup() {
        return mRxSilenceDetected;
    }

    /**
      * Returns true if only silence rtp packets are sent for a duration of 20 seconds starting at
      * call setup
      */
    public boolean isOutgoingSilenceDetectedAtCallSetup() {
        return mTxSilenceDetected;
    }

    /**
     * Returns the number of Voice frames sent by jitter buffer to audio
     */
    public int getNumVoiceFrames() {
        return mNumVoiceFrames;
    }

    /**
     * Returns the number of no-data frames sent by jitter buffer to audio
     */
    public int getNumNoDataFrames() {
        return mNumNoDataFrames;
    }

    /**
     * Returns the number of RTP voice packets dropped by jitter buffer
     */
    public int getNumDroppedRtpPackets() {
        return mNumDroppedRtpPackets;
    }

    /**
     * Returns the minimum playout delay in the reporting interval
     * in milliseconds.
     */
    public long getMinPlayoutDelayMillis() {
        return mMinPlayoutDelayMillis;
    }

    /**
     * Returns the maximum playout delay in the reporting interval
     * in milliseconds.
     */
    public long getMaxPlayoutDelayMillis() {
        return mMaxPlayoutDelayMillis;
    }

    /**
     * Returns the total number of RTP SID (Silence Insertion Descriptor) packets
     * received by this device for an ongoing call
     */
    public int getNumRtpSidPacketsReceived() {
        return mNumRtpSidPacketsReceived;
    }

    /**
     * Returns the total number of RTP duplicate packets received by this device
     * for an ongoing call
     */
    public int getNumRtpDuplicatePackets() {
        return mNumRtpDuplicatePackets;
    }

    /**
     * Returns the codec type. This value corresponds to the AUDIO_QUALITY_* constants in
     * {@link ImsStreamMediaProfile}.
     *
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_NONE
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_AMR
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_AMR_WB
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_QCELP13K
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_EVRC
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_EVRC_B
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_EVRC_WB
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_EVRC_NW
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_GSM_EFR
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_GSM_FR
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_GSM_HR
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_G711U
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_G723
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_G711A
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_G722
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_G711AB
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_G729
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_EVS_NB
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_EVS_WB
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_EVS_SWB
     * @see ImsStreamMediaProfile#AUDIO_QUALITY_EVS_FB
     */
    public int getCodecType() {
        return mCodecType;
    }

    // Parcelable things
    @NonNull
    @Override
    public String toString() {
        return "CallQuality: {downlinkCallQualityLevel=" + mDownlinkCallQualityLevel
                + " uplinkCallQualityLevel=" + mUplinkCallQualityLevel
                + " callDuration=" + mCallDuration
                + " numRtpPacketsTransmitted=" + mNumRtpPacketsTransmitted
                + " numRtpPacketsReceived=" + mNumRtpPacketsReceived
                + " numRtpPacketsTransmittedLost=" + mNumRtpPacketsTransmittedLost
                + " numRtpPacketsNotReceived=" + mNumRtpPacketsNotReceived
                + " averageRelativeJitter=" + mAverageRelativeJitter
                + " maxRelativeJitter=" + mMaxRelativeJitter
                + " averageRoundTripTime=" + mAverageRoundTripTime
                + " codecType=" + mCodecType
                + " rtpInactivityDetected=" + mRtpInactivityDetected
                + " txSilenceDetected=" + mTxSilenceDetected
                + " rxSilenceDetected=" + mRxSilenceDetected
                + " numVoiceFrames=" + mNumVoiceFrames
                + " numNoDataFrames=" + mNumNoDataFrames
                + " numDroppedRtpPackets=" + mNumDroppedRtpPackets
                + " minPlayoutDelayMillis=" + mMinPlayoutDelayMillis
                + " maxPlayoutDelayMillis=" + mMaxPlayoutDelayMillis
                + " numRtpSidPacketsReceived=" + mNumRtpSidPacketsReceived
                + " numRtpDuplicatePackets=" + mNumRtpDuplicatePackets
                + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mDownlinkCallQualityLevel,
                mUplinkCallQualityLevel,
                mCallDuration,
                mNumRtpPacketsTransmitted,
                mNumRtpPacketsReceived,
                mNumRtpPacketsTransmittedLost,
                mNumRtpPacketsNotReceived,
                mAverageRelativeJitter,
                mMaxRelativeJitter,
                mAverageRoundTripTime,
                mCodecType,
                mRtpInactivityDetected,
                mRxSilenceDetected,
                mTxSilenceDetected,
                mNumVoiceFrames,
                mNumNoDataFrames,
                mNumDroppedRtpPackets,
                mMinPlayoutDelayMillis,
                mMaxPlayoutDelayMillis,
                mNumRtpSidPacketsReceived,
                mNumRtpDuplicatePackets);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == null || !(o instanceof CallQuality) || hashCode() != o.hashCode()) {
            return false;
        }

        if (this == o) {
            return true;
        }

        CallQuality s = (CallQuality) o;

        return (mDownlinkCallQualityLevel == s.mDownlinkCallQualityLevel
                && mUplinkCallQualityLevel == s.mUplinkCallQualityLevel
                && mCallDuration == s.mCallDuration
                && mNumRtpPacketsTransmitted == s.mNumRtpPacketsTransmitted
                && mNumRtpPacketsReceived == s.mNumRtpPacketsReceived
                && mNumRtpPacketsTransmittedLost == s.mNumRtpPacketsTransmittedLost
                && mNumRtpPacketsNotReceived == s.mNumRtpPacketsNotReceived
                && mAverageRelativeJitter == s.mAverageRelativeJitter
                && mMaxRelativeJitter == s.mMaxRelativeJitter
                && mAverageRoundTripTime == s.mAverageRoundTripTime
                && mCodecType == s.mCodecType
                && mRtpInactivityDetected == s.mRtpInactivityDetected
                && mRxSilenceDetected == s.mRxSilenceDetected
                && mTxSilenceDetected == s.mTxSilenceDetected
                && mNumVoiceFrames == s.mNumVoiceFrames
                && mNumNoDataFrames == s.mNumNoDataFrames
                && mNumDroppedRtpPackets == s.mNumDroppedRtpPackets
                && mMinPlayoutDelayMillis == s.mMinPlayoutDelayMillis
                && mMaxPlayoutDelayMillis == s.mMaxPlayoutDelayMillis
                && mNumRtpSidPacketsReceived == s.mNumRtpSidPacketsReceived
                && mNumRtpDuplicatePackets == s.mNumRtpDuplicatePackets);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDownlinkCallQualityLevel);
        dest.writeInt(mUplinkCallQualityLevel);
        dest.writeInt(mCallDuration);
        dest.writeInt(mNumRtpPacketsTransmitted);
        dest.writeInt(mNumRtpPacketsReceived);
        dest.writeInt(mNumRtpPacketsTransmittedLost);
        dest.writeInt(mNumRtpPacketsNotReceived);
        dest.writeInt(mAverageRelativeJitter);
        dest.writeInt(mMaxRelativeJitter);
        dest.writeInt(mAverageRoundTripTime);
        dest.writeInt(mCodecType);
        dest.writeBoolean(mRtpInactivityDetected);
        dest.writeBoolean(mRxSilenceDetected);
        dest.writeBoolean(mTxSilenceDetected);
        dest.writeInt(mNumVoiceFrames);
        dest.writeInt(mNumNoDataFrames);
        dest.writeInt(mNumDroppedRtpPackets);
        dest.writeLong(mMinPlayoutDelayMillis);
        dest.writeLong(mMaxPlayoutDelayMillis);
        dest.writeInt(mNumRtpSidPacketsReceived);
        dest.writeInt(mNumRtpDuplicatePackets);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CallQuality> CREATOR = new Parcelable.Creator() {
        public CallQuality createFromParcel(Parcel in) {
            return new CallQuality(in);
        }

        public CallQuality[] newArray(int size) {
            return new CallQuality[size];
        }
    };

    /**
     * Provides a convenient way to set the fields of a {@link CallQuality} when creating a new
     * instance.
     *
     * <p>The example below shows how you might create a new {@code CallQuality}:
     *
     * <pre><code>
     *
     * CallQuality callQuality = new CallQuality.Builder()
     *     .setNumRtpPacketsTransmitted(150)
     *     .setNumRtpPacketsReceived(200)
     *     .build();
     * </code></pre>
     */
    public static final class Builder {

        private int mDownlinkCallQualityLevel;
        private int mUplinkCallQualityLevel;
        private int mCallDuration;
        private int mNumRtpPacketsTransmitted;
        private int mNumRtpPacketsReceived;
        private int mNumRtpPacketsTransmittedLost;
        private int mNumRtpPacketsNotReceived;
        private int mAverageRelativeJitter;
        private int mMaxRelativeJitter;
        private int mAverageRoundTripTime;
        private int mCodecType;
        private boolean mRtpInactivityDetected;
        private boolean mRxSilenceDetected;
        private boolean mTxSilenceDetected;
        private int mNumVoiceFrames;
        private int mNumNoDataFrames;
        private int mNumDroppedRtpPackets;
        private long mMinPlayoutDelayMillis;
        private long mMaxPlayoutDelayMillis;
        private int mNumRtpSidPacketsReceived;
        private int mNumRtpDuplicatePackets;

        /**
         * Set the downlink call quality level for ongoing call.
         *
         * @param downlinkCallQualityLevel the Downlink call quality level
         * @return The same instance of the builder.
         */
        public @NonNull Builder setDownlinkCallQualityLevel(
                @CallQualityLevel int downlinkCallQualityLevel) {
            mDownlinkCallQualityLevel = downlinkCallQualityLevel;
            return this;
        }

        /**
         * Set the uplink call quality level for ongoing call.
         *
         * @param uplinkCallQualityLevel the Uplink call quality level
         * @return The same instance of the builder.
         */
        public @NonNull Builder setUplinkCallQualityLevel(
                @CallQualityLevel int uplinkCallQualityLevel) {
            mUplinkCallQualityLevel = uplinkCallQualityLevel;
            return this;
        }

        /**
         * Set the call duration in milliseconds.
         *
         * @param callDuration the call duration in milliseconds
         * @return The same instance of the builder.
         */
        // Newer builder includes guidelines compliant units; existing method does not.
        @NonNull
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public Builder setCallDurationMillis(int callDurationMillis) {
            mCallDuration = callDurationMillis;
            return this;
        }

        /**
         * Set the number of RTP packets sent for ongoing call.
         *
         * @param numRtpPacketsTransmitted RTP packets sent to network
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumRtpPacketsTransmitted(int numRtpPacketsTransmitted) {
            mNumRtpPacketsTransmitted = numRtpPacketsTransmitted;
            return this;
        }

        /**
         * Set the number of RTP packets received for ongoing call.
         *
         * @param numRtpPacketsReceived RTP packets received from network
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumRtpPacketsReceived(int numRtpPacketsReceived) {
            mNumRtpPacketsReceived = numRtpPacketsReceived;
            return this;
        }

        /**
         * Set the number of RTP packets which were lost in network and never
         * transmitted.
         *
         * @param numRtpPacketsTransmittedLost RTP packets which were lost in network and never
         * transmitted
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumRtpPacketsTransmittedLost(int numRtpPacketsTransmittedLost) {
            mNumRtpPacketsTransmittedLost = numRtpPacketsTransmittedLost;
            return this;
        }

        /**
         * Set the number of RTP packets which were lost in network and never received.
         *
         * @param numRtpPacketsNotReceived RTP packets which were lost in network and
         * never received
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumRtpPacketsNotReceived(int numRtpPacketsNotReceived) {
            mNumRtpPacketsNotReceived = numRtpPacketsNotReceived;
            return this;
        }

        /**
         * Set the average relative jitter in milliseconds.
         *
         * @param averageRelativeJitter average relative jitter in milliseconds
         * @return The same instance of the builder.
         */
        public @NonNull Builder setAverageRelativeJitter(int averageRelativeJitter) {
            mAverageRelativeJitter = averageRelativeJitter;
            return this;
        }

        /**
         * Set the maximum relative jitter in milliseconds.
         *
         * @param maxRelativeJitter maximum relative jitter in milliseconds
         * @return The same instance of the builder.
         */
        public @NonNull Builder setMaxRelativeJitter(int maxRelativeJitter) {
            mMaxRelativeJitter = maxRelativeJitter;
            return this;
        }

        /**
         * Set the average round trip delay in milliseconds.
         *
         * @param averageRoundTripTime average round trip delay in milliseconds
         * @return The same instance of the builder.
         */
        // Newer builder includes guidelines compliant units; existing method does not.
        @NonNull
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public Builder setAverageRoundTripTimeMillis(int averageRoundTripTimeMillis) {
            mAverageRoundTripTime = averageRoundTripTimeMillis;
            return this;
        }

        /**
         * Set the codec type used in the ongoing call.
         *
         * @param codecType the codec type.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setCodecType(int codecType) {
            mCodecType = codecType;
            return this;
        }

        /**
         * Set to be True if no incoming RTP is received for a continuous
         * duration of 4 seconds.
         *
         * @param rtpInactivityDetected True if no incoming RTP is received for
         * a continuous duration of 4 seconds
         * @return The same instance of the builder.
         */
        public @NonNull Builder setRtpInactivityDetected(boolean rtpInactivityDetected) {
            mRtpInactivityDetected = rtpInactivityDetected;
            return this;
        }

        /**
         * Set to be True if only silence RTP packets are received for 20 seconds
         * immediately after call is connected.
         *
         * @param rxSilenceDetected True if only silence RTP packets are received for 20 seconds
         * immediately after call is connected
         * @return The same instance of the builder.
         */
        public @NonNull Builder setIncomingSilenceDetectedAtCallSetup(boolean rxSilenceDetected) {
            mRxSilenceDetected = rxSilenceDetected;
            return this;
        }

        /**
         * Set to be True if only silence RTP packets are sent for 20 seconds immediately
         * after call is connected.
         *
         * @param txSilenceDetected True if only silence RTP packets are sent for
         * 20 seconds immediately after call is connected.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setOutgoingSilenceDetectedAtCallSetup(boolean txSilenceDetected) {
            mTxSilenceDetected = txSilenceDetected;
            return this;
        }

        /**
         * Set the number of voice frames sent by jitter buffer to audio.
         *
         * @param numVoiceFrames Voice frames sent by jitter buffer to audio.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumVoiceFrames(int numVoiceFrames) {
            mNumVoiceFrames = numVoiceFrames;
            return this;
        }

        /**
         * Set the number of no-data frames sent by jitter buffer to audio.
         *
         * @param numNoDataFrames no-data frames sent by jitter buffer to audio
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumNoDataFrames(int numNoDataFrames) {
            mNumNoDataFrames = numNoDataFrames;
            return this;
        }

        /**
         * Set the number of RTP Voice packets dropped by jitter buffer.
         *
         * @param numDroppedRtpPackets number of RTP Voice packets dropped by jitter buffer
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumDroppedRtpPackets(int numDroppedRtpPackets) {
            mNumDroppedRtpPackets = numDroppedRtpPackets;
            return this;
        }

        /**
         * Set the minimum playout delay in the reporting interval in milliseconds.
         *
         * @param minPlayoutDelayMillis minimum playout delay in the reporting interval
         * @return The same instance of the builder.
         */
        public @NonNull Builder setMinPlayoutDelayMillis(long minPlayoutDelayMillis) {
            mMinPlayoutDelayMillis = minPlayoutDelayMillis;
            return this;
        }

        /**
         * Set the maximum Playout delay in the reporting interval in milliseconds.
         *
         * @param maxPlayoutDelayMillis maximum Playout delay in the reporting interval
         * @return The same instance of the builder.
         */
        public @NonNull Builder setMaxPlayoutDelayMillis(long maxPlayoutDelayMillis) {
            mMaxPlayoutDelayMillis = maxPlayoutDelayMillis;
            return this;
        }

        /**
         * Set the total number of RTP SID (Silence Insertion Descriptor)
         * packets received by this device for an ongoing call.
         *
         * @param numRtpSidPacketsReceived the total number of RTP SID packets received
         * by this device for an ongoing call.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumRtpSidPacketsReceived(int numRtpSidPacketsReceived) {
            mNumRtpSidPacketsReceived = numRtpSidPacketsReceived;
            return this;
        }

        /**
         * Set the total number of RTP duplicate packets received by this device
         * for an ongoing call.
         *
         * @param numRtpDuplicatePackets the total number of RTP duplicate packets
         * received by this device for an ongoing call
         * @return The same instance of the builder.
         */
        public @NonNull Builder setNumRtpDuplicatePackets(int numRtpDuplicatePackets) {
            mNumRtpDuplicatePackets = numRtpDuplicatePackets;
            return this;
        }

        /**
         * Build the CallQuality.
         *
         * @return the CallQuality object.
         */
        public @NonNull CallQuality build() {

            CallQuality callQuality = new CallQuality();
            callQuality.mDownlinkCallQualityLevel = mDownlinkCallQualityLevel;
            callQuality.mUplinkCallQualityLevel = mUplinkCallQualityLevel;
            callQuality.mCallDuration = mCallDuration;
            callQuality.mNumRtpPacketsTransmitted = mNumRtpPacketsTransmitted;
            callQuality.mNumRtpPacketsReceived = mNumRtpPacketsReceived;
            callQuality.mNumRtpPacketsTransmittedLost = mNumRtpPacketsTransmittedLost;
            callQuality.mNumRtpPacketsNotReceived = mNumRtpPacketsNotReceived;
            callQuality.mAverageRelativeJitter = mAverageRelativeJitter;
            callQuality.mMaxRelativeJitter = mMaxRelativeJitter;
            callQuality.mAverageRoundTripTime = mAverageRoundTripTime;
            callQuality.mCodecType = mCodecType;
            callQuality.mRtpInactivityDetected = mRtpInactivityDetected;
            callQuality.mTxSilenceDetected = mTxSilenceDetected;
            callQuality.mRxSilenceDetected = mRxSilenceDetected;
            callQuality.mNumVoiceFrames = mNumVoiceFrames;
            callQuality.mNumNoDataFrames = mNumNoDataFrames;
            callQuality.mNumDroppedRtpPackets = mNumDroppedRtpPackets;
            callQuality.mMinPlayoutDelayMillis = mMinPlayoutDelayMillis;
            callQuality.mMaxPlayoutDelayMillis = mMaxPlayoutDelayMillis;
            callQuality.mNumRtpSidPacketsReceived = mNumRtpSidPacketsReceived;
            callQuality.mNumRtpDuplicatePackets = mNumRtpDuplicatePackets;

            return callQuality;
        }
    }
}
