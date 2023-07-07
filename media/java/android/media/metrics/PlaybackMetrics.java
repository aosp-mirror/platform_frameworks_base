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

package android.media.metrics;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * This class is used to store playback data.
 */
public final class PlaybackMetrics implements Parcelable {
    /** Unknown stream source. */
    public static final int STREAM_SOURCE_UNKNOWN = 0;
    /** Stream from network. */
    public static final int STREAM_SOURCE_NETWORK = 1;
    /** Stream from device. */
    public static final int STREAM_SOURCE_DEVICE = 2;
    /** Stream from more than one sources. */
    public static final int STREAM_SOURCE_MIXED = 3;

    /** Unknown stream type. */
    public static final int STREAM_TYPE_UNKNOWN = 0;
    /** Other stream type. */
    public static final int STREAM_TYPE_OTHER = 1;
    /** Progressive stream type. */
    public static final int STREAM_TYPE_PROGRESSIVE = 2;
    /** DASH (Dynamic Adaptive Streaming over HTTP) stream type. */
    public static final int STREAM_TYPE_DASH = 3;
    /** HLS (HTTP Live Streaming) stream type. */
    public static final int STREAM_TYPE_HLS = 4;
    /** SS (HTTP Smooth Streaming) stream type. */
    public static final int STREAM_TYPE_SS = 5;

    /** Unknown playback type. */
    public static final int PLAYBACK_TYPE_UNKNOWN = 0;
    /** VOD (Video on Demand) playback type. */
    public static final int PLAYBACK_TYPE_VOD = 1;
    /** Live playback type. */
    public static final int PLAYBACK_TYPE_LIVE = 2;
    /** Other playback type. */
    public static final int PLAYBACK_TYPE_OTHER = 3;

    /** DRM is not used. */
    public static final int DRM_TYPE_NONE = 0;
    /** Other DRM type. */
    public static final int DRM_TYPE_OTHER = 1;
    /** Play ready DRM type. */
    public static final int DRM_TYPE_PLAY_READY = 2;
    /** Widevine L1 DRM type. */
    public static final int DRM_TYPE_WIDEVINE_L1 = 3;
    /** Widevine L3 DRM type. */
    public static final int DRM_TYPE_WIDEVINE_L3 = 4;
    /** Widevine L3 fallback DRM type. */
    public static final int DRM_TYPE_WV_L3_FALLBACK = 5;
    /** Clear key DRM type. */
    public static final int DRM_TYPE_CLEARKEY = 6;

    /** Unknown content type. */
    public static final int CONTENT_TYPE_UNKNOWN = 0;
    /** Main contents. */
    public static final int CONTENT_TYPE_MAIN = 1;
    /** Advertisement contents. */
    public static final int CONTENT_TYPE_AD = 2;
    /** Other contents. */
    public static final int CONTENT_TYPE_OTHER = 3;


    /** @hide */
    @IntDef(prefix = "STREAM_SOURCE_", value = {
        STREAM_SOURCE_UNKNOWN,
        STREAM_SOURCE_NETWORK,
        STREAM_SOURCE_DEVICE,
        STREAM_SOURCE_MIXED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StreamSource {}

    /** @hide */
    @IntDef(prefix = "STREAM_TYPE_", value = {
        STREAM_TYPE_UNKNOWN,
        STREAM_TYPE_OTHER,
        STREAM_TYPE_PROGRESSIVE,
        STREAM_TYPE_DASH,
        STREAM_TYPE_HLS,
        STREAM_TYPE_SS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StreamType {}

    /** @hide */
    @IntDef(prefix = "PLAYBACK_TYPE_", value = {
        PLAYBACK_TYPE_UNKNOWN,
        PLAYBACK_TYPE_VOD,
        PLAYBACK_TYPE_LIVE,
        PLAYBACK_TYPE_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlaybackType {}

    /** @hide */
    @IntDef(prefix = "DRM_TYPE_", value = {
        DRM_TYPE_NONE,
        DRM_TYPE_OTHER,
        DRM_TYPE_PLAY_READY,
        DRM_TYPE_WIDEVINE_L1,
        DRM_TYPE_WIDEVINE_L3,
        DRM_TYPE_WV_L3_FALLBACK,
        DRM_TYPE_CLEARKEY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DrmType {}

    /** @hide */
    @IntDef(prefix = "CONTENT_TYPE_", value = {
        CONTENT_TYPE_UNKNOWN,
        CONTENT_TYPE_MAIN,
        CONTENT_TYPE_AD,
        CONTENT_TYPE_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ContentType {}



    private final long mMediaDurationMillis;
    private final int mStreamSource;
    private final int mStreamType;
    private final int mPlaybackType;
    private final int mDrmType;
    private final int mContentType;
    private final @Nullable String mPlayerName;
    private final @Nullable String mPlayerVersion;
    private final @NonNull long[] mExperimentIds;
    private final int mVideoFramesPlayed;
    private final int mVideoFramesDropped;
    private final int mAudioUnderrunCount;
    private final long mNetworkBytesRead;
    private final long mLocalBytesRead;
    private final long mNetworkTransferDurationMillis;
    private final byte[] mDrmSessionId;
    private final @NonNull Bundle mMetricsBundle;

    /**
     * Creates a new PlaybackMetrics.
     *
     * @hide
     */
    public PlaybackMetrics(
            long mediaDurationMillis,
            int streamSource,
            int streamType,
            int playbackType,
            int drmType,
            int contentType,
            @Nullable String playerName,
            @Nullable String playerVersion,
            @NonNull long[] experimentIds,
            int videoFramesPlayed,
            int videoFramesDropped,
            int audioUnderrunCount,
            long networkBytesRead,
            long localBytesRead,
            long networkTransferDurationMillis,
            byte[] drmSessionId,
            @NonNull Bundle extras) {
        this.mMediaDurationMillis = mediaDurationMillis;
        this.mStreamSource = streamSource;
        this.mStreamType = streamType;
        this.mPlaybackType = playbackType;
        this.mDrmType = drmType;
        this.mContentType = contentType;
        this.mPlayerName = playerName;
        this.mPlayerVersion = playerVersion;
        this.mExperimentIds = experimentIds;
        AnnotationValidations.validate(NonNull.class, null, mExperimentIds);
        this.mVideoFramesPlayed = videoFramesPlayed;
        this.mVideoFramesDropped = videoFramesDropped;
        this.mAudioUnderrunCount = audioUnderrunCount;
        this.mNetworkBytesRead = networkBytesRead;
        this.mLocalBytesRead = localBytesRead;
        this.mNetworkTransferDurationMillis = networkTransferDurationMillis;
        this.mDrmSessionId = drmSessionId;
        this.mMetricsBundle = extras.deepCopy();
    }

    /**
     * Gets the media duration in milliseconds.
     * <p>Media duration is the length of the media.
     * @return the media duration in milliseconds, or -1 if unknown.
     */
    @IntRange(from = -1)
    public long getMediaDurationMillis() {
        return mMediaDurationMillis;
    }

    /**
     * Gets stream source type.
     */
    @StreamSource
    public int getStreamSource() {
        return mStreamSource;
    }

    /**
     * Gets stream type.
     */
    @StreamType
    public int getStreamType() {
        return mStreamType;
    }


    /**
     * Gets playback type.
     */
    @PlaybackType
    public int getPlaybackType() {
        return mPlaybackType;
    }

    /**
     * Gets DRM type.
     */
    @DrmType
    public int getDrmType() {
        return mDrmType;
    }

    /**
     * Gets content type.
     */
    @ContentType
    public int getContentType() {
        return mContentType;
    }

    /**
     * Gets player name.
     */
    public @Nullable String getPlayerName() {
        return mPlayerName;
    }

    /**
     * Gets player version.
     */
    public @Nullable String getPlayerVersion() {
        return mPlayerVersion;
    }

    /**
     * Gets experiment IDs.
     */
    public @NonNull long[] getExperimentIds() {
        return Arrays.copyOf(mExperimentIds, mExperimentIds.length);
    }

    /**
     * Gets video frames played.
     * @return the video frames played, or -1 if unknown.
     */
    @IntRange(from = -1, to = Integer.MAX_VALUE)
    public int getVideoFramesPlayed() {
        return mVideoFramesPlayed;
    }

    /**
     * Gets video frames dropped.
     * @return the video frames dropped, or -1 if unknown.
     */
    @IntRange(from = -1, to = Integer.MAX_VALUE)
    public int getVideoFramesDropped() {
        return mVideoFramesDropped;
    }

    /**
     * Gets audio underrun count.
     * @return the audio underrun count, or -1 if unknown.
     */
    @IntRange(from = -1, to = Integer.MAX_VALUE)
    public int getAudioUnderrunCount() {
        return mAudioUnderrunCount;
    }

    /**
     * Gets number of network bytes read.
     * @return the number of network bytes read, or -1 if unknown.
     */
    @IntRange(from = -1)
    public long getNetworkBytesRead() {
        return mNetworkBytesRead;
    }

    /**
     * Gets number of local bytes read.
     */
    @IntRange(from = -1)
    public long getLocalBytesRead() {
        return mLocalBytesRead;
    }

    /**
     * Gets network transfer duration in milliseconds.
     * <p>Total transfer time spent reading from the network in ms. For parallel requests, the
     * overlapping time intervals are counted only once.
     */
    @IntRange(from = -1)
    public long getNetworkTransferDurationMillis() {
        return mNetworkTransferDurationMillis;
    }

    /**
     * Gets DRM session ID.
     */
    @NonNull
    public byte[] getDrmSessionId() {
        return mDrmSessionId;
    }

    /**
     * Gets metrics-related information that is not supported by dedicated methods.
     * <p>It is intended to be used for backwards compatibility by the metrics infrastructure.
     */
    @NonNull
    public Bundle getMetricsBundle() {
        return mMetricsBundle;
    }

    @Override
    public String toString() {
        return "PlaybackMetrics { "
                + "mediaDurationMillis = " + mMediaDurationMillis + ", "
                + "streamSource = " + mStreamSource + ", "
                + "streamType = " + mStreamType + ", "
                + "playbackType = " + mPlaybackType + ", "
                + "drmType = " + mDrmType + ", "
                + "contentType = " + mContentType + ", "
                + "playerName = " + mPlayerName + ", "
                + "playerVersion = " + mPlayerVersion + ", "
                + "experimentIds = " + Arrays.toString(mExperimentIds) + ", "
                + "videoFramesPlayed = " + mVideoFramesPlayed + ", "
                + "videoFramesDropped = " + mVideoFramesDropped + ", "
                + "audioUnderrunCount = " + mAudioUnderrunCount + ", "
                + "networkBytesRead = " + mNetworkBytesRead + ", "
                + "localBytesRead = " + mLocalBytesRead + ", "
                + "networkTransferDurationMillis = " + mNetworkTransferDurationMillis
                + "drmSessionId = " + Arrays.toString(mDrmSessionId)
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaybackMetrics that = (PlaybackMetrics) o;
        return mMediaDurationMillis == that.mMediaDurationMillis
                && mStreamSource == that.mStreamSource
                && mStreamType == that.mStreamType
                && mPlaybackType == that.mPlaybackType
                && mDrmType == that.mDrmType
                && mContentType == that.mContentType
                && Objects.equals(mPlayerName, that.mPlayerName)
                && Objects.equals(mPlayerVersion, that.mPlayerVersion)
                && Arrays.equals(mExperimentIds, that.mExperimentIds)
                && mVideoFramesPlayed == that.mVideoFramesPlayed
                && mVideoFramesDropped == that.mVideoFramesDropped
                && mAudioUnderrunCount == that.mAudioUnderrunCount
                && mNetworkBytesRead == that.mNetworkBytesRead
                && mLocalBytesRead == that.mLocalBytesRead
                && mNetworkTransferDurationMillis == that.mNetworkTransferDurationMillis
                && Arrays.equals(mDrmSessionId, that.mDrmSessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMediaDurationMillis, mStreamSource, mStreamType, mPlaybackType,
                mDrmType, mContentType, mPlayerName, mPlayerVersion,
                Arrays.hashCode(mExperimentIds), mVideoFramesPlayed, mVideoFramesDropped,
                mAudioUnderrunCount, mNetworkBytesRead, mLocalBytesRead,
                mNetworkTransferDurationMillis, Arrays.hashCode(mDrmSessionId));
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        long flg = 0;
        if (mPlayerName != null) flg |= 0x80;
        if (mPlayerVersion != null) flg |= 0x100;
        dest.writeLong(flg);
        dest.writeLong(mMediaDurationMillis);
        dest.writeInt(mStreamSource);
        dest.writeInt(mStreamType);
        dest.writeInt(mPlaybackType);
        dest.writeInt(mDrmType);
        dest.writeInt(mContentType);
        if (mPlayerName != null) dest.writeString(mPlayerName);
        if (mPlayerVersion != null) dest.writeString(mPlayerVersion);
        dest.writeLongArray(mExperimentIds);
        dest.writeInt(mVideoFramesPlayed);
        dest.writeInt(mVideoFramesDropped);
        dest.writeInt(mAudioUnderrunCount);
        dest.writeLong(mNetworkBytesRead);
        dest.writeLong(mLocalBytesRead);
        dest.writeLong(mNetworkTransferDurationMillis);
        dest.writeInt(mDrmSessionId.length);
        dest.writeByteArray(mDrmSessionId);
        dest.writeBundle(mMetricsBundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ PlaybackMetrics(@NonNull Parcel in) {
        long flg = in.readLong();
        long mediaDurationMillis = in.readLong();
        int streamSource = in.readInt();
        int streamType = in.readInt();
        int playbackType = in.readInt();
        int drmType = in.readInt();
        int contentType = in.readInt();
        String playerName = (flg & 0x80) == 0 ? null : in.readString();
        String playerVersion = (flg & 0x100) == 0 ? null : in.readString();
        long[] experimentIds = in.createLongArray();
        int videoFramesPlayed = in.readInt();
        int videoFramesDropped = in.readInt();
        int audioUnderrunCount = in.readInt();
        long networkBytesRead = in.readLong();
        long localBytesRead = in.readLong();
        long networkTransferDurationMillis = in.readLong();
        int drmSessionIdLen = in.readInt();
        byte[] drmSessionId = new byte[drmSessionIdLen];
        in.readByteArray(drmSessionId);
        Bundle extras = in.readBundle();

        this.mMediaDurationMillis = mediaDurationMillis;
        this.mStreamSource = streamSource;
        this.mStreamType = streamType;
        this.mPlaybackType = playbackType;
        this.mDrmType = drmType;
        this.mContentType = contentType;
        this.mPlayerName = playerName;
        this.mPlayerVersion = playerVersion;
        this.mExperimentIds = experimentIds;
        AnnotationValidations.validate(NonNull.class, null, mExperimentIds);
        this.mVideoFramesPlayed = videoFramesPlayed;
        this.mVideoFramesDropped = videoFramesDropped;
        this.mAudioUnderrunCount = audioUnderrunCount;
        this.mNetworkBytesRead = networkBytesRead;
        this.mLocalBytesRead = localBytesRead;
        this.mNetworkTransferDurationMillis = networkTransferDurationMillis;
        this.mDrmSessionId = drmSessionId;
        this.mMetricsBundle = extras;
    }

    public static final @NonNull Parcelable.Creator<PlaybackMetrics> CREATOR =
            new Parcelable.Creator<PlaybackMetrics>() {
        @Override
        public PlaybackMetrics[] newArray(int size) {
            return new PlaybackMetrics[size];
        }

        @Override
        public PlaybackMetrics createFromParcel(@NonNull Parcel in) {
            return new PlaybackMetrics(in);
        }
    };

    /**
     * A builder for {@link PlaybackMetrics}
     */
    public static final class Builder {

        private long mMediaDurationMillis = -1;
        private int mStreamSource = STREAM_SOURCE_UNKNOWN;
        private int mStreamType = STREAM_TYPE_UNKNOWN;
        private int mPlaybackType = PLAYBACK_TYPE_UNKNOWN;
        private int mDrmType = DRM_TYPE_NONE;
        private int mContentType = CONTENT_TYPE_UNKNOWN;
        private @Nullable String mPlayerName;
        private @Nullable String mPlayerVersion;
        private @NonNull List<Long> mExperimentIds = new ArrayList<>();
        private int mVideoFramesPlayed = -1;
        private int mVideoFramesDropped = -1;
        private int mAudioUnderrunCount = -1;
        private long mNetworkBytesRead = -1;
        private long mLocalBytesRead = -1;
        private long mNetworkTransferDurationMillis = -1;
        private byte[] mDrmSessionId = new byte[0];
        private Bundle mMetricsBundle = new Bundle();

        /**
         * Creates a new Builder.
         */
        public Builder() {
        }

        /**
         * Sets the media duration in milliseconds.
         * @param value the media duration in milliseconds. -1 indicates the value is unknown.
         * @see #getMediaDurationMillis()
         */
        public @NonNull Builder setMediaDurationMillis(@IntRange(from = -1) long value) {
            mMediaDurationMillis = value;
            return this;
        }

        /**
         * Sets the stream source type.
         */
        public @NonNull Builder setStreamSource(@StreamSource int value) {
            mStreamSource = value;
            return this;
        }

        /**
         * Sets the stream type.
         */
        public @NonNull Builder setStreamType(@StreamType int value) {
            mStreamType = value;
            return this;
        }

        /**
         * Sets the playback type.
         */
        public @NonNull Builder setPlaybackType(@PlaybackType int value) {
            mPlaybackType = value;
            return this;
        }

        /**
         * Sets the DRM type.
         */
        public @NonNull Builder setDrmType(@DrmType int value) {
            mDrmType = value;
            return this;
        }

        /**
         * Sets the content type.
         */
        public @NonNull Builder setContentType(@ContentType int value) {
            mContentType = value;
            return this;
        }

        /**
         * Sets the player name.
         */
        public @NonNull Builder setPlayerName(@NonNull String value) {
            mPlayerName = value;
            return this;
        }

        /**
         * Sets the player version.
         */
        public @NonNull Builder setPlayerVersion(@NonNull String value) {
            mPlayerVersion = value;
            return this;
        }

        /**
         * Adds the experiment ID.
         */
        public @NonNull Builder addExperimentId(long value) {
            mExperimentIds.add(value);
            return this;
        }

        /**
         * Sets the video frames played.
         * @param value the video frames played. -1 indicates the value is unknown.
         */
        public @NonNull Builder setVideoFramesPlayed(
                @IntRange(from = -1, to = Integer.MAX_VALUE) int value) {
            mVideoFramesPlayed = value;
            return this;
        }

        /**
         * Sets the video frames dropped.
         * @param value the video frames dropped. -1 indicates the value is unknown.
         */
        public @NonNull Builder setVideoFramesDropped(
                @IntRange(from = -1, to = Integer.MAX_VALUE) int value) {
            mVideoFramesDropped = value;
            return this;
        }

        /**
         * Sets the audio underrun count.
         * @param value the audio underrun count. -1 indicates the value is unknown.
         */
        public @NonNull Builder setAudioUnderrunCount(
                @IntRange(from = -1, to = Integer.MAX_VALUE) int value) {
            mAudioUnderrunCount = value;
            return this;
        }

        /**
         * Sets the number of network bytes read.
         * @param value the number of network bytes read. -1 indicates the value is unknown.
         */
        public @NonNull Builder setNetworkBytesRead(@IntRange(from = -1) long value) {
            mNetworkBytesRead = value;
            return this;
        }

        /**
         * Sets the number of local bytes read.
         * @param value the number of local bytes read. -1 indicates the value is unknown.
         */
        public @NonNull Builder setLocalBytesRead(@IntRange(from = -1) long value) {
            mLocalBytesRead = value;
            return this;
        }

        /**
         * Sets the network transfer duration in milliseconds.
         * @param value the network transfer duration in milliseconds.
         *              -1 indicates the value is unknown.
         * @see #getNetworkTransferDurationMillis()
         */
        public @NonNull Builder setNetworkTransferDurationMillis(@IntRange(from = -1) long value) {
            mNetworkTransferDurationMillis = value;
            return this;
        }

        /**
         * Sets DRM session ID.
         */
        public @NonNull Builder setDrmSessionId(@NonNull byte[] drmSessionId) {
            mDrmSessionId = drmSessionId;
            return this;
        }

        /**
         * Sets metrics-related information that is not supported by dedicated
         * methods.
         * <p>It is intended to be used for backwards compatibility by the
         * metrics infrastructure.
         */
        public @NonNull Builder setMetricsBundle(@NonNull Bundle metricsBundle) {
            mMetricsBundle = metricsBundle;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull PlaybackMetrics build() {

            PlaybackMetrics o = new PlaybackMetrics(
                    mMediaDurationMillis,
                    mStreamSource,
                    mStreamType,
                    mPlaybackType,
                    mDrmType,
                    mContentType,
                    mPlayerName,
                    mPlayerVersion,
                    idsToLongArray(),
                    mVideoFramesPlayed,
                    mVideoFramesDropped,
                    mAudioUnderrunCount,
                    mNetworkBytesRead,
                    mLocalBytesRead,
                    mNetworkTransferDurationMillis,
                    mDrmSessionId,
                    mMetricsBundle);
            return o;
        }

        private long[] idsToLongArray() {
            long[] ids = new long[mExperimentIds.size()];
            for (int i = 0; i < mExperimentIds.size(); i++) {
                ids[i] = mExperimentIds.get(i);
            }
            return ids;
        }
    }
}
