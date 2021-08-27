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

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Playback track change event.
 */
public final class TrackChangeEvent extends Event implements Parcelable {
    /** The track is off. */
    public static final int TRACK_STATE_OFF = 0;
    /** The track is on. */
    public static final int TRACK_STATE_ON = 1;

    /** Unknown track change reason. */
    public static final int TRACK_CHANGE_REASON_UNKNOWN = 0;
    /** Other track change reason. */
    public static final int TRACK_CHANGE_REASON_OTHER = 1;
    /** Track change reason for initial state. */
    public static final int TRACK_CHANGE_REASON_INITIAL = 2;
    /** Track change reason for manual changes. */
    public static final int TRACK_CHANGE_REASON_MANUAL = 3;
    /** Track change reason for adaptive changes. */
    public static final int TRACK_CHANGE_REASON_ADAPTIVE = 4;

    /** Audio track. */
    public static final int TRACK_TYPE_AUDIO = 0;
    /** Video track. */
    public static final int TRACK_TYPE_VIDEO = 1;
    /** Text track. */
    public static final int TRACK_TYPE_TEXT = 2;

    private final int mState;
    private final int mReason;
    private final @Nullable String mContainerMimeType;
    private final @Nullable String mSampleMimeType;
    private final @Nullable String mCodecName;
    private final int mBitrate;
    private final long mTimeSinceCreatedMillis;
    private final int mType;
    private final @Nullable String mLanguage;
    private final @Nullable String mLanguageRegion;
    private final int mChannelCount;
    private final int mAudioSampleRate;
    private final int mWidth;
    private final int mHeight;
    private final float mVideoFrameRate;



    /** @hide */
    @IntDef(prefix = "TRACK_STATE_", value = {
        TRACK_STATE_OFF,
        TRACK_STATE_ON
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrackState {}

    /** @hide */
    @IntDef(prefix = "TRACK_CHANGE_REASON_", value = {
        TRACK_CHANGE_REASON_UNKNOWN,
        TRACK_CHANGE_REASON_OTHER,
        TRACK_CHANGE_REASON_INITIAL,
        TRACK_CHANGE_REASON_MANUAL,
        TRACK_CHANGE_REASON_ADAPTIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrackChangeReason {}

    /** @hide */
    @IntDef(prefix = "TRACK_TYPE_", value = {
        TRACK_TYPE_AUDIO,
        TRACK_TYPE_VIDEO,
        TRACK_TYPE_TEXT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrackType {}

    private TrackChangeEvent(
            int state,
            int reason,
            @Nullable String containerMimeType,
            @Nullable String sampleMimeType,
            @Nullable String codecName,
            int bitrate,
            long timeSinceCreatedMillis,
            int type,
            @Nullable String language,
            @Nullable String languageRegion,
            int channelCount,
            int sampleRate,
            int width,
            int height,
            float videoFrameRate,
            @NonNull Bundle extras) {
        this.mState = state;
        this.mReason = reason;
        this.mContainerMimeType = containerMimeType;
        this.mSampleMimeType = sampleMimeType;
        this.mCodecName = codecName;
        this.mBitrate = bitrate;
        this.mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        this.mType = type;
        this.mLanguage = language;
        this.mLanguageRegion = languageRegion;
        this.mChannelCount = channelCount;
        this.mAudioSampleRate = sampleRate;
        this.mWidth = width;
        this.mHeight = height;
        this.mVideoFrameRate = videoFrameRate;
        this.mMetricsBundle = extras.deepCopy();
    }

    /**
     * Gets track state.
     */
    @TrackState
    public int getTrackState() {
        return mState;
    }

    /**
     * Gets track change reason.
     */
    @TrackChangeReason
    public int getTrackChangeReason() {
        return mReason;
    }

    /**
     * Gets container MIME type.
     */
    public @Nullable String getContainerMimeType() {
        return mContainerMimeType;
    }

    /**
     * Gets the MIME type of the video/audio/text samples.
     */
    public @Nullable String getSampleMimeType() {
        return mSampleMimeType;
    }

    /**
     * Gets codec name.
     */
    public @Nullable String getCodecName() {
        return mCodecName;
    }

    /**
     * Gets bitrate.
     * @return the bitrate, or -1 if unknown.
     */
    @IntRange(from = -1, to = Integer.MAX_VALUE)
    public int getBitrate() {
        return mBitrate;
    }

    /**
     * Gets timestamp since the creation of the log session in milliseconds.
     * @return the timestamp since the creation in milliseconds, or -1 if unknown.
     * @see LogSessionId
     * @see PlaybackSession
     * @see RecordingSession
     */
    @Override
    @IntRange(from = -1)
    public long getTimeSinceCreatedMillis() {
        return mTimeSinceCreatedMillis;
    }

    /**
     * Gets the track type.
     * <p>The track type must be one of {@link #TRACK_TYPE_AUDIO}, {@link #TRACK_TYPE_VIDEO},
     * {@link #TRACK_TYPE_TEXT}.
     */
    @TrackType
    public int getTrackType() {
        return mType;
    }

    /**
     * Gets language code.
     * @return a two-letter ISO 639-1 language code.
     */
    public @Nullable String getLanguage() {
        return mLanguage;
    }


    /**
     * Gets language region code.
     * @return an IETF BCP 47 optional language region subtag based on a two-letter country code.
     */
    public @Nullable String getLanguageRegion() {
        return mLanguageRegion;
    }

    /**
     * Gets channel count.
     * @return the channel count, or -1 if unknown.
     */
    @IntRange(from = -1, to = Integer.MAX_VALUE)
    public int getChannelCount() {
        return mChannelCount;
    }

    /**
     * Gets audio sample rate.
     * @return the sample rate, or -1 if unknown.
     */
    @IntRange(from = -1, to = Integer.MAX_VALUE)
    public int getAudioSampleRate() {
        return mAudioSampleRate;
    }

    /**
     * Gets video width.
     * @return the video width, or -1 if unknown.
     */
    @IntRange(from = -1, to = Integer.MAX_VALUE)
    public int getWidth() {
        return mWidth;
    }

    /**
     * Gets video height.
     * @return the video height, or -1 if unknown.
     */
    @IntRange(from = -1, to = Integer.MAX_VALUE)
    public int getHeight() {
        return mHeight;
    }

    /**
     * Gets video frame rate.
     * @return the video frame rate, or -1 if unknown.
     */
    @FloatRange(from = -1, to = Float.MAX_VALUE)
    public float getVideoFrameRate() {
        return mVideoFrameRate;
    }

    /**
     * Gets metrics-related information that is not supported by dedicated methods.
     * <p>It is intended to be used for backwards compatibility by the metrics infrastructure.
     */
    @Override
    @NonNull
    public Bundle getMetricsBundle() {
        return mMetricsBundle;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        int flg = 0;
        if (mContainerMimeType != null) flg |= 0x4;
        if (mSampleMimeType != null) flg |= 0x8;
        if (mCodecName != null) flg |= 0x10;
        if (mLanguage != null) flg |= 0x100;
        if (mLanguageRegion != null) flg |= 0x200;
        dest.writeInt(flg);
        dest.writeInt(mState);
        dest.writeInt(mReason);
        if (mContainerMimeType != null) dest.writeString(mContainerMimeType);
        if (mSampleMimeType != null) dest.writeString(mSampleMimeType);
        if (mCodecName != null) dest.writeString(mCodecName);
        dest.writeInt(mBitrate);
        dest.writeLong(mTimeSinceCreatedMillis);
        dest.writeInt(mType);
        if (mLanguage != null) dest.writeString(mLanguage);
        if (mLanguageRegion != null) dest.writeString(mLanguageRegion);
        dest.writeInt(mChannelCount);
        dest.writeInt(mAudioSampleRate);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        dest.writeFloat(mVideoFrameRate);
        dest.writeBundle(mMetricsBundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private TrackChangeEvent(@NonNull Parcel in) {
        int flg = in.readInt();
        int state = in.readInt();
        int reason = in.readInt();
        String containerMimeType = (flg & 0x4) == 0 ? null : in.readString();
        String sampleMimeType = (flg & 0x8) == 0 ? null : in.readString();
        String codecName = (flg & 0x10) == 0 ? null : in.readString();
        int bitrate = in.readInt();
        long timeSinceCreatedMillis = in.readLong();
        int type = in.readInt();
        String language = (flg & 0x100) == 0 ? null : in.readString();
        String languageRegion = (flg & 0x200) == 0 ? null : in.readString();
        int channelCount = in.readInt();
        int sampleRate = in.readInt();
        int width = in.readInt();
        int height = in.readInt();
        float videoFrameRate = in.readFloat();
        Bundle extras = in.readBundle();

        this.mState = state;
        this.mReason = reason;
        this.mContainerMimeType = containerMimeType;
        this.mSampleMimeType = sampleMimeType;
        this.mCodecName = codecName;
        this.mBitrate = bitrate;
        this.mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        this.mType = type;
        this.mLanguage = language;
        this.mLanguageRegion = languageRegion;
        this.mChannelCount = channelCount;
        this.mAudioSampleRate = sampleRate;
        this.mWidth = width;
        this.mHeight = height;
        this.mVideoFrameRate = videoFrameRate;
        this.mMetricsBundle = extras;
    }

    public static final @NonNull Parcelable.Creator<TrackChangeEvent> CREATOR =
            new Parcelable.Creator<TrackChangeEvent>() {
        @Override
        public TrackChangeEvent[] newArray(int size) {
            return new TrackChangeEvent[size];
        }

        @Override
        public TrackChangeEvent createFromParcel(@NonNull Parcel in) {
            return new TrackChangeEvent(in);
        }
    };

    @Override
    public String toString() {
        return "TrackChangeEvent { "
                + "state = " + mState + ", "
                + "reason = " + mReason + ", "
                + "containerMimeType = " + mContainerMimeType + ", "
                + "sampleMimeType = " + mSampleMimeType + ", "
                + "codecName = " + mCodecName + ", "
                + "bitrate = " + mBitrate + ", "
                + "timeSinceCreatedMillis = " + mTimeSinceCreatedMillis + ", "
                + "type = " + mType + ", "
                + "language = " + mLanguage + ", "
                + "languageRegion = " + mLanguageRegion + ", "
                + "channelCount = " + mChannelCount + ", "
                + "sampleRate = " + mAudioSampleRate + ", "
                + "width = " + mWidth + ", "
                + "height = " + mHeight + ", "
                + "videoFrameRate = " + mVideoFrameRate
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrackChangeEvent that = (TrackChangeEvent) o;
        return mState == that.mState
                && mReason == that.mReason
                && Objects.equals(mContainerMimeType, that.mContainerMimeType)
                && Objects.equals(mSampleMimeType, that.mSampleMimeType)
                && Objects.equals(mCodecName, that.mCodecName)
                && mBitrate == that.mBitrate
                && mTimeSinceCreatedMillis == that.mTimeSinceCreatedMillis
                && mType == that.mType
                && Objects.equals(mLanguage, that.mLanguage)
                && Objects.equals(mLanguageRegion, that.mLanguageRegion)
                && mChannelCount == that.mChannelCount
                && mAudioSampleRate == that.mAudioSampleRate
                && mWidth == that.mWidth
                && mHeight == that.mHeight
                && mVideoFrameRate == that.mVideoFrameRate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mState, mReason, mContainerMimeType, mSampleMimeType, mCodecName,
                mBitrate, mTimeSinceCreatedMillis, mType, mLanguage, mLanguageRegion,
                mChannelCount, mAudioSampleRate, mWidth, mHeight, mVideoFrameRate);
    }

    /**
     * A builder for {@link TrackChangeEvent}
     */
    public static final class Builder {
        // TODO: check track type for the setters.
        private int mState = TRACK_STATE_OFF;
        private int mReason = TRACK_CHANGE_REASON_UNKNOWN;
        private @Nullable String mContainerMimeType;
        private @Nullable String mSampleMimeType;
        private @Nullable String mCodecName;
        private int mBitrate = -1;
        private long mTimeSinceCreatedMillis = -1;
        private final int mType;
        private @Nullable String mLanguage;
        private @Nullable String mLanguageRegion;
        private int mChannelCount = -1;
        private int mAudioSampleRate = -1;
        private int mWidth = -1;
        private int mHeight = -1;
        private float mVideoFrameRate = -1;
        private Bundle mMetricsBundle = new Bundle();

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         * @param type the track type. It must be one of {@link #TRACK_TYPE_AUDIO},
         *             {@link #TRACK_TYPE_VIDEO}, {@link #TRACK_TYPE_TEXT}.
         */
        public Builder(@TrackType int type) {
            if (type != TRACK_TYPE_AUDIO && type != TRACK_TYPE_VIDEO && type != TRACK_TYPE_TEXT) {
                throw new IllegalArgumentException("track type must be one of TRACK_TYPE_AUDIO, "
                    + "TRACK_TYPE_VIDEO, TRACK_TYPE_TEXT.");
            }
            mType = type;
        }

        /**
         * Sets track state.
         */
        public @NonNull Builder setTrackState(@TrackState int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mState = value;
            return this;
        }

        /**
         * Sets track change reason.
         */
        public @NonNull Builder setTrackChangeReason(@TrackChangeReason int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mReason = value;
            return this;
        }

        /**
         * Sets container MIME type.
         */
        public @NonNull Builder setContainerMimeType(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mContainerMimeType = value;
            return this;
        }

        /**
         * Sets the MIME type of the video/audio/text samples.
         */
        public @NonNull Builder setSampleMimeType(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mSampleMimeType = value;
            return this;
        }

        /**
         * Sets codec name.
         */
        public @NonNull Builder setCodecName(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mCodecName = value;
            return this;
        }

        /**
         * Sets bitrate in bits per second.
         * @param value the bitrate in bits per second. -1 indicates the value is unknown.
         */
        public @NonNull Builder setBitrate(@IntRange(from = -1, to = Integer.MAX_VALUE) int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mBitrate = value;
            return this;
        }

        /**
         * Sets timestamp since the creation in milliseconds.
         * @param value the timestamp since the creation in milliseconds.
         *              -1 indicates the value is unknown.
         * @see #getTimeSinceCreatedMillis()
         */
        public @NonNull Builder setTimeSinceCreatedMillis(@IntRange(from = -1) long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mTimeSinceCreatedMillis = value;
            return this;
        }

        /**
         * Sets language code.
         * @param value a two-letter ISO 639-1 language code.
         */
        public @NonNull Builder setLanguage(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x100;
            mLanguage = value;
            return this;
        }

        /**
         * Sets language region code.
         * @param value an IETF BCP 47 optional language region subtag based on a two-letter country
         *              code.
         */
        public @NonNull Builder setLanguageRegion(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x200;
            mLanguageRegion = value;
            return this;
        }

        /**
         * Sets channel count.
         * @param value the channel count. -1 indicates the value is unknown.
         */
        public @NonNull Builder setChannelCount(
                @IntRange(from = -1, to = Integer.MAX_VALUE) int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x400;
            mChannelCount = value;
            return this;
        }

        /**
         * Sets sample rate.
         * @param value the sample rate. -1 indicates the value is unknown.
         */
        public @NonNull Builder setAudioSampleRate(
                @IntRange(from = -1, to = Integer.MAX_VALUE) int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x800;
            mAudioSampleRate = value;
            return this;
        }

        /**
         * Sets video width.
         * @param value the video width. -1 indicates the value is unknown.
         */
        public @NonNull Builder setWidth(@IntRange(from = -1, to = Integer.MAX_VALUE) int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1000;
            mWidth = value;
            return this;
        }

        /**
         * Sets video height.
         * @param value the video height. -1 indicates the value is unknown.
         */
        public @NonNull Builder setHeight(@IntRange(from = -1, to = Integer.MAX_VALUE) int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2000;
            mHeight = value;
            return this;
        }

        /**
         * Sets video frame rate.
         * @param value the video frame rate. -1 indicates the value is unknown.
         */
        public @NonNull Builder setVideoFrameRate(
                @FloatRange(from = -1, to = Float.MAX_VALUE) float value) {
            checkNotUsed();
            mVideoFrameRate = value;
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
        public @NonNull TrackChangeEvent build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4000; // Mark builder used

            TrackChangeEvent o = new TrackChangeEvent(
                    mState,
                    mReason,
                    mContainerMimeType,
                    mSampleMimeType,
                    mCodecName,
                    mBitrate,
                    mTimeSinceCreatedMillis,
                    mType,
                    mLanguage,
                    mLanguageRegion,
                    mChannelCount,
                    mAudioSampleRate,
                    mWidth,
                    mHeight,
                    mVideoFrameRate,
                    mMetricsBundle);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x4000) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
