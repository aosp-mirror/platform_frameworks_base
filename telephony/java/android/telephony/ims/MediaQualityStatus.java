/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants.TransportType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A representation of Media quality status.
 *
 * @hide
 */
@SystemApi
public final class MediaQualityStatus implements Parcelable {
    public static final int MEDIA_SESSION_TYPE_AUDIO =  1;
    public static final int MEDIA_SESSION_TYPE_VIDEO =  2;

    private final String mImsCallSessionId;
    private final int mMediaSessionType;
    private final int mTransportType;
    private final int mRtpPacketLossRate;
    private final int mRtpJitterMillis;
    private final long mRtpInactivityTimeMillis;

    /** @hide */
    @IntDef(
            value = {
                    MEDIA_SESSION_TYPE_AUDIO,
                    MEDIA_SESSION_TYPE_VIDEO,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediaSessionType {}

    /**
     * The constructor for MediaQualityStatus, which represents the media quality for each session
     * type ({@link #MEDIA_SESSION_TYPE_AUDIO} or {@link #MEDIA_SESSION_TYPE_VIDEO}) of the IMS call
     *
     * @param imsCallSessionId IMS call session id of this quality status
     * @param mediaSessionType media session type of this quality status
     * @param transportType transport type of this quality status
     * @param rtpPacketLossRate measured RTP packet loss rate in percentage
     * @param rtpJitterMillis measured RTP jitter(RFC3550) in milliseconds
     * @param rptInactivityTimeMillis measured RTP inactivity time in milliseconds
     */
    public MediaQualityStatus(@NonNull String imsCallSessionId,
            @MediaSessionType int mediaSessionType, @TransportType int transportType,
            @IntRange(from = 0, to = 100) int rtpPacketLossRate,
            @IntRange(from = 0) int rtpJitterMillis,
            @IntRange(from = 0) long rptInactivityTimeMillis) {
        mImsCallSessionId = imsCallSessionId;
        mMediaSessionType = mediaSessionType;
        mTransportType = transportType;
        mRtpPacketLossRate = rtpPacketLossRate;
        mRtpJitterMillis = rtpJitterMillis;
        mRtpInactivityTimeMillis = rptInactivityTimeMillis;
    }

    /**
     * Retrieves call session ID for this quality status
     */
    @NonNull
    public String getCallSessionId() {
        return mImsCallSessionId;
    }

    /**
     * Retrieves media session type of this quality status
     */
    public @MediaSessionType int getMediaSessionType() {
        return mMediaSessionType;
    }


    /**
     * Retrieves Transport type for which this media quality was measured.
     */
    public @TransportType int getTransportType() {
        return mTransportType;
    }

    /**
     * Retrieves measured RTP packet loss rate in percentage.
     */
    @IntRange(from = 0, to = 100)
    public int getRtpPacketLossRate() {
        return mRtpPacketLossRate;
    }

    /**
     * Retrieves measured RTP jitter(RFC3550) value in milliseconds
     */
    @IntRange(from = 0)
    public int getRtpJitterMillis() {
        return mRtpJitterMillis;
    }

    /**
     * Retrieves measured RTP inactivity time in milliseconds
     */
    @IntRange(from = 0)
    public long getRtpInactivityMillis() {
        return mRtpInactivityTimeMillis;
    }

    /**
     * Creates a new instance of {@link MediaQualityStatus} from a parcel.
     * @param in The parceled data to read.
     */
    private MediaQualityStatus(@NonNull Parcel in) {
        mImsCallSessionId = in.readString();
        mMediaSessionType = in.readInt();
        mTransportType = in.readInt();
        mRtpPacketLossRate = in.readInt();
        mRtpJitterMillis = in.readInt();
        mRtpInactivityTimeMillis = in.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mImsCallSessionId);
        dest.writeInt(mMediaSessionType);
        dest.writeInt(mTransportType);
        dest.writeInt(mRtpPacketLossRate);
        dest.writeInt(mRtpJitterMillis);
        dest.writeLong(mRtpInactivityTimeMillis);
    }

    public static final @NonNull Creator<MediaQualityStatus> CREATOR =
            new Creator<MediaQualityStatus>() {
                @Override
                public MediaQualityStatus createFromParcel(@NonNull Parcel in) {
                    return new MediaQualityStatus(in);
                }

                @Override
                public MediaQualityStatus[] newArray(int size) {
                    return new MediaQualityStatus[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaQualityStatus that = (MediaQualityStatus) o;
        return mImsCallSessionId != null && mImsCallSessionId.equals(that.mImsCallSessionId)
                && mMediaSessionType == that.mMediaSessionType
                && mTransportType == that.mTransportType
                && mRtpPacketLossRate == that.mRtpPacketLossRate
                && mRtpJitterMillis == that.mRtpJitterMillis
                && mRtpInactivityTimeMillis == that.mRtpInactivityTimeMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mImsCallSessionId, mMediaSessionType, mTransportType,
                mRtpPacketLossRate, mRtpJitterMillis, mRtpInactivityTimeMillis);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MediaThreshold{mImsCallSessionId=");
        sb.append(mImsCallSessionId);
        sb.append(", mMediaSessionType=");
        sb.append(mMediaSessionType);
        sb.append(", mTransportType=");
        sb.append(mTransportType);
        sb.append(", mRtpPacketLossRate=");
        sb.append(mRtpPacketLossRate);
        sb.append(", mRtpJitterMillis=");
        sb.append(mRtpJitterMillis);
        sb.append(", mRtpInactivityTimeMillis=");
        sb.append(mRtpInactivityTimeMillis);
        sb.append("}");
        return sb.toString();
    }
}
