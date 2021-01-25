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
import android.annotation.NonNull;
import android.annotation.Nullable;
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
 * @hide
 */
public final class PlaybackMetrics implements Parcelable {
    // TODO(b/177209128): JavaDoc for the constants.
    public static final int STREAM_SOURCE_UNKNOWN = 0;
    public static final int STREAM_SOURCE_NETWORK = 1;
    public static final int STREAM_SOURCE_DEVICE = 2;
    public static final int STREAM_SOURCE_MIXED = 3;

    public static final int STREAM_TYPE_UNKNOWN = 0;
    public static final int STREAM_TYPE_OTHER = 1;
    public static final int STREAM_TYPE_PROGRESSIVE = 2;
    public static final int STREAM_TYPE_DASH = 3;
    public static final int STREAM_TYPE_HLS = 4;
    public static final int STREAM_TYPE_SS = 5;

    public static final int PLAYBACK_TYPE_VOD = 0;
    public static final int PLAYBACK_TYPE_LIVE = 1;
    public static final int PLAYBACK_TYPE_OTHER = 2;

    public static final int DRM_TYPE_NONE = 0;
    public static final int DRM_TYPE_OTHER = 1;
    public static final int DRM_TYPE_PLAY_READY = 2;
    public static final int DRM_TYPE_WIDEVINE_L1 = 3;
    public static final int DRM_TYPE_WIDEVINE_L3 = 4;
    // TODO: add DRM_TYPE_CLEARKEY

    public static final int CONTENT_TYPE_MAIN = 0;
    public static final int CONTENT_TYPE_AD = 1;
    public static final int CONTENT_TYPE_OTHER = 2;


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
        DRM_TYPE_WIDEVINE_L3
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DrmType {}

    /** @hide */
    @IntDef(prefix = "CONTENT_TYPE_", value = {
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
            long networkTransferDurationMillis) {
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
    }

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
     */
    public int getVideoFramesPlayed() {
        return mVideoFramesPlayed;
    }

    /**
     * Gets video frames dropped.
     */
    public int getVideoFramesDropped() {
        return mVideoFramesDropped;
    }

    /**
     * Gets audio underrun count.
     */
    public int getAudioUnderrunCount() {
        return mAudioUnderrunCount;
    }

    /**
     * Gets number of network bytes read.
     */
    public long getNetworkBytesRead() {
        return mNetworkBytesRead;
    }

    /**
     * Gets number of local bytes read.
     */
    public long getLocalBytesRead() {
        return mLocalBytesRead;
    }

    /**
     * Gets network transfer duration in milliseconds.
     */
    public long getNetworkTransferDurationMillis() {
        return mNetworkTransferDurationMillis;
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
                && mNetworkTransferDurationMillis == that.mNetworkTransferDurationMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMediaDurationMillis, mStreamSource, mStreamType, mPlaybackType,
                mDrmType, mContentType, mPlayerName, mPlayerVersion, mExperimentIds,
                mVideoFramesPlayed, mVideoFramesDropped, mAudioUnderrunCount, mNetworkBytesRead,
                mLocalBytesRead, mNetworkTransferDurationMillis);
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

        private long mMediaDurationMillis;
        private int mStreamSource;
        private int mStreamType;
        private int mPlaybackType;
        private int mDrmType;
        private int mContentType;
        private @Nullable String mPlayerName;
        private @Nullable String mPlayerVersion;
        private @NonNull List<Long> mExperimentIds = new ArrayList<>();
        private int mVideoFramesPlayed;
        private int mVideoFramesDropped;
        private int mAudioUnderrunCount;
        private long mNetworkBytesRead;
        private long mLocalBytesRead;
        private long mNetworkTransferDurationMillis;

        /**
         * Creates a new Builder.
         *
         * @hide
         */
        public Builder() {
        }

        /**
         * Sets the media duration in milliseconds.
         */
        public @NonNull Builder setMediaDurationMillis(long value) {
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
        public @NonNull Builder setDrmType(@StreamType int value) {
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
         */
        public @NonNull Builder setVideoFramesPlayed(int value) {
            mVideoFramesPlayed = value;
            return this;
        }

        /**
         * Sets the video frames dropped.
         */
        public @NonNull Builder setVideoFramesDropped(int value) {
            mVideoFramesDropped = value;
            return this;
        }

        /**
         * Sets the audio underrun count.
         */
        public @NonNull Builder setAudioUnderrunCount(int value) {
            mAudioUnderrunCount = value;
            return this;
        }

        /**
         * Sets the number of network bytes read.
         */
        public @NonNull Builder setNetworkBytesRead(long value) {
            mNetworkBytesRead = value;
            return this;
        }

        /**
         * Sets the number of local bytes read.
         */
        public @NonNull Builder setLocalBytesRead(long value) {
            mLocalBytesRead = value;
            return this;
        }

        /**
         * Sets the network transfer duration in milliseconds.
         */
        public @NonNull Builder setNetworkTransferDurationMillis(long value) {
            mNetworkTransferDurationMillis = value;
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
                    mNetworkTransferDurationMillis);
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
