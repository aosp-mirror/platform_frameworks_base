/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telecom;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * This class represents the quality report that bluetooth framework sends
 * whenever there's a bad voice quality is detected from their side.
 * It is sent as part of a call event via {@link Call#sendCallEvent(String, Bundle)}
 * associated with extra EXTRA_BLUETOOTH_CALL_QUALITY_REPORT.
 * Note that this report will be sent only during an active voice/voip call.
 * @hide
 */
@SystemApi
public final class BluetoothCallQualityReport implements Parcelable {

    /**
     * Event that is sent via {@link Call#sendCallEvent(String, Bundle)} for a call quality report
     */
    public static final String EVENT_BLUETOOTH_CALL_QUALITY_REPORT =
            "android.telecom.event.BLUETOOTH_CALL_QUALITY_REPORT";

    /**
     * Extra key sent with {@link Call#sendCallEvent(String, Bundle)}
     */
    public static final String EXTRA_BLUETOOTH_CALL_QUALITY_REPORT =
            "android.telecom.extra.BLUETOOTH_CALL_QUALITY_REPORT";

    private final long mSentTimestampMillis;
    private final boolean mChoppyVoice;
    private final int mRssiDbm;
    private final int mSnrDb;
    private final int mRetransmittedPacketsCount;
    private final int mPacketsNotReceivedCount;
    private final int mNegativeAcknowledgementCount;

    /**
     * @return Time in milliseconds since the epoch. Designates when report was sent.
     * Used to determine whether this report arrived too late to be useful.
     */
    public @ElapsedRealtimeLong long getSentTimestampMillis() {
        return mSentTimestampMillis;
    }

    /**
     * @return {@code true} if bluetooth hardware detects voice is choppy
     */
    public boolean isChoppyVoice() {
        return mChoppyVoice;
    }

    /**
     * @return Received Signal Strength Indication (RSSI) value in dBm.
     * This value shall be an absolute received signal strength value.
     */
    public @IntRange(from = -127, to = 20) int getRssiDbm() {
        return mRssiDbm;
    }

    /**
     * @return Signal-to-Noise Ratio (SNR) value in dB.
     * The controller shall provide the average SNR of all the channels currently used by the link.
     */
    public int getSnrDb() {
        return mSnrDb;
    }

    /**
     * @return The number of retransmissions since the last event.
     * This count shall be reset after it is reported.
     */
    public @IntRange(from = 0) int getRetransmittedPacketsCount() {
        return mRetransmittedPacketsCount;
    }

    /**
     * @return No RX count since the last event.
     * The count increases when no packet is received at the scheduled time slot or the received
     * packet is corrupted.
     * This count shall be reset after it is reported.
     */
    public @IntRange(from = 0) int getPacketsNotReceivedCount() {
        return mPacketsNotReceivedCount;
    }

    /**
     * @return NAK (Negative Acknowledge) count since the last event.
     * This count shall be reset after it is reported.
     */
    public @IntRange(from = 0) int getNegativeAcknowledgementCount() {
        return mNegativeAcknowledgementCount;
    }

    //
    // Parcelable implementation
    //

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeLong(mSentTimestampMillis);
        out.writeBoolean(mChoppyVoice);
        out.writeInt(mRssiDbm);
        out.writeInt(mSnrDb);
        out.writeInt(mRetransmittedPacketsCount);
        out.writeInt(mPacketsNotReceivedCount);
        out.writeInt(mNegativeAcknowledgementCount);
    }

    public static final @android.annotation.NonNull Creator<BluetoothCallQualityReport> CREATOR =
            new Creator<BluetoothCallQualityReport>() {
                @Override
                public BluetoothCallQualityReport createFromParcel(Parcel in) {
                    return new BluetoothCallQualityReport(in);
                }

                @Override
                public BluetoothCallQualityReport[] newArray(int size) {
                    return new BluetoothCallQualityReport[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BluetoothCallQualityReport that = (BluetoothCallQualityReport) o;
        return mSentTimestampMillis == that.mSentTimestampMillis
                && mChoppyVoice == that.mChoppyVoice && mRssiDbm == that.mRssiDbm
                && mSnrDb == that.mSnrDb
                && mRetransmittedPacketsCount == that.mRetransmittedPacketsCount
                && mPacketsNotReceivedCount == that.mPacketsNotReceivedCount
                && mNegativeAcknowledgementCount == that.mNegativeAcknowledgementCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSentTimestampMillis, mChoppyVoice, mRssiDbm, mSnrDb,
                mRetransmittedPacketsCount, mPacketsNotReceivedCount,
                mNegativeAcknowledgementCount);
    }

    /**
     * Builder class for {@link ConnectionRequest}
     */
    public static final class Builder {
        private long mSentTimestampMillis;
        private boolean mChoppyVoice;
        private int mRssiDbm;
        private int mSnrDb;
        private int mRetransmittedPacketsCount;
        private int mPacketsNotReceivedCount;
        private int mNegativeAcknowledgementCount;

        public Builder() { }

        /**
         * Set the time when report was sent in milliseconds since the epoch.
         * @param sentTimestampMillis
         */
        public @NonNull Builder setSentTimestampMillis(long sentTimestampMillis) {
            mSentTimestampMillis = sentTimestampMillis;
            return this;
        }

        /**
         * Set if bluetooth hardware detects voice is choppy
         * @param choppyVoice
         */
        public @NonNull Builder setChoppyVoice(boolean choppyVoice) {
            mChoppyVoice = choppyVoice;
            return this;
        }

        /**
         * Set Received Signal Strength Indication (RSSI) value in dBm.
         * @param rssiDbm
         */
        public @NonNull Builder setRssiDbm(int rssiDbm) {
            mRssiDbm = rssiDbm;
            return this;
        }

        /**
         * Set Signal-to-Noise Ratio (SNR) value in dB.
         * @param snrDb
         */
        public @NonNull Builder setSnrDb(int snrDb) {
            mSnrDb = snrDb;
            return this;
        }

        /**
         * Set The number of retransmissions since the last event.
         * @param retransmittedPacketsCount
         */
        public @NonNull Builder setRetransmittedPacketsCount(
                int retransmittedPacketsCount) {
            mRetransmittedPacketsCount = retransmittedPacketsCount;
            return this;
        }

        /**
         * Set No RX count since the last event.
         * @param packetsNotReceivedCount
         */
        public @NonNull Builder setPacketsNotReceivedCount(
                int packetsNotReceivedCount) {
            mPacketsNotReceivedCount = packetsNotReceivedCount;
            return this;
        }

        /**
         * Set NAK (Negative Acknowledge) count since the last event.
         * @param negativeAcknowledgementCount
         */
        public @NonNull Builder setNegativeAcknowledgementCount(
                int negativeAcknowledgementCount) {
            mNegativeAcknowledgementCount = negativeAcknowledgementCount;
            return this;
        }

        /**
         * Build the {@link BluetoothCallQualityReport}
         * @return Result of the builder
         */
        public @NonNull BluetoothCallQualityReport build() {
            return new BluetoothCallQualityReport(this);
        }
    }

    private BluetoothCallQualityReport(Parcel in) {
        mSentTimestampMillis = in.readLong();
        mChoppyVoice = in.readBoolean();
        mRssiDbm = in.readInt();
        mSnrDb = in.readInt();
        mRetransmittedPacketsCount = in.readInt();
        mPacketsNotReceivedCount = in.readInt();
        mNegativeAcknowledgementCount = in.readInt();
    }

    private BluetoothCallQualityReport(Builder builder) {
        mSentTimestampMillis = builder.mSentTimestampMillis;
        mChoppyVoice = builder.mChoppyVoice;
        mRssiDbm = builder.mRssiDbm;
        mSnrDb = builder.mSnrDb;
        mRetransmittedPacketsCount = builder.mRetransmittedPacketsCount;
        mPacketsNotReceivedCount = builder.mPacketsNotReceivedCount;
        mNegativeAcknowledgementCount = builder.mNegativeAcknowledgementCount;
    }
}
