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
     * @param numRtpPacketsNotReceived RTP packets which were lost in network and never recieved
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
                mCodecType);
    }

    @Override
    public boolean equals(Object o) {
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
                && mCodecType == s.mCodecType);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public @Parcelable.ContentsFlags int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel dest, @Parcelable.WriteFlags int flags) {
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
    }

    public static final Parcelable.Creator<CallQuality> CREATOR = new Parcelable.Creator() {
        public CallQuality createFromParcel(Parcel in) {
            return new CallQuality(in);
        }

        public CallQuality[] newArray(int size) {
            return new CallQuality[size];
        }
    };
}
