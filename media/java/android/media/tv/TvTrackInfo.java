/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.tv;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Encapsulates the format of tracks played in {@link TvInputService}.
 */
public final class TvTrackInfo implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_AUDIO, TYPE_VIDEO, TYPE_SUBTITLE})
    public @interface Type {}

    /**
     * The type value for audio tracks.
     */
    public static final int TYPE_AUDIO = 0;

    /**
     * The type value for video tracks.
     */
    public static final int TYPE_VIDEO = 1;

    /**
     * The type value for subtitle tracks.
     */
    public static final int TYPE_SUBTITLE = 2;

    private final int mType;
    private final String mId;
    private final String mLanguage;
    private final CharSequence mDescription;
    private final int mAudioChannelCount;
    private final int mAudioSampleRate;
    private final int mVideoWidth;
    private final int mVideoHeight;
    private final float mVideoFrameRate;
    private final float mVideoPixelAspectRatio;
    private final byte mVideoActiveFormatDescription;

    private final Bundle mExtra;

    private TvTrackInfo(int type, String id, String language, CharSequence description,
            int audioChannelCount, int audioSampleRate, int videoWidth, int videoHeight,
            float videoFrameRate, float videoPixelAspectRatio, byte videoActiveFormatDescription,
            Bundle extra) {
        mType = type;
        mId = id;
        mLanguage = language;
        mDescription = description;
        mAudioChannelCount = audioChannelCount;
        mAudioSampleRate = audioSampleRate;
        mVideoWidth = videoWidth;
        mVideoHeight = videoHeight;
        mVideoFrameRate = videoFrameRate;
        mVideoPixelAspectRatio = videoPixelAspectRatio;
        mVideoActiveFormatDescription = videoActiveFormatDescription;
        mExtra = extra;
    }

    private TvTrackInfo(Parcel in) {
        mType = in.readInt();
        mId = in.readString();
        mLanguage = in.readString();
        mDescription = in.readString();
        mAudioChannelCount = in.readInt();
        mAudioSampleRate = in.readInt();
        mVideoWidth = in.readInt();
        mVideoHeight = in.readInt();
        mVideoFrameRate = in.readFloat();
        mVideoPixelAspectRatio = in.readFloat();
        mVideoActiveFormatDescription = in.readByte();
        mExtra = in.readBundle();
    }

    /**
     * Returns the type of the track. The type should be one of the followings:
     * {@link #TYPE_AUDIO}, {@link #TYPE_VIDEO} and {@link #TYPE_SUBTITLE}.
     */
    @Type
    public final int getType() {
        return mType;
    }

    /**
     * Returns the ID of the track.
     */
    public final String getId() {
        return mId;
    }

    /**
     * Returns the language information encoded by either ISO 639-1 or ISO 639-2/T. If the language
     * is unknown or could not be determined, the corresponding value will be {@code null}.
     */
    public final String getLanguage() {
        return mLanguage;
    }

    /**
     * Returns a user readable description for the current track.
     */
    public final CharSequence getDescription() {
        return mDescription;
    }

    /**
     * Returns the audio channel count. Valid only for {@link #TYPE_AUDIO} tracks.
     *
     * @throws IllegalStateException if not called on an audio track
     */
    public final int getAudioChannelCount() {
        if (mType != TYPE_AUDIO) {
            throw new IllegalStateException("Not an audio track");
        }
        return mAudioChannelCount;
    }

    /**
     * Returns the audio sample rate, in the unit of Hz. Valid only for {@link #TYPE_AUDIO} tracks.
     *
     * @throws IllegalStateException if not called on an audio track
     */
    public final int getAudioSampleRate() {
        if (mType != TYPE_AUDIO) {
            throw new IllegalStateException("Not an audio track");
        }
        return mAudioSampleRate;
    }

    /**
     * Returns the width of the video, in the unit of pixels. Valid only for {@link #TYPE_VIDEO}
     * tracks.
     *
     * @throws IllegalStateException if not called on a video track
     */
    public final int getVideoWidth() {
        if (mType != TYPE_VIDEO) {
            throw new IllegalStateException("Not a video track");
        }
        return mVideoWidth;
    }

    /**
     * Returns the height of the video, in the unit of pixels. Valid only for {@link #TYPE_VIDEO}
     * tracks.
     *
     * @throws IllegalStateException if not called on a video track
     */
    public final int getVideoHeight() {
        if (mType != TYPE_VIDEO) {
            throw new IllegalStateException("Not a video track");
        }
        return mVideoHeight;
    }

    /**
     * Returns the frame rate of the video, in the unit of fps (frames per second). Valid only for
     * {@link #TYPE_VIDEO} tracks.
     *
     * @throws IllegalStateException if not called on a video track
     */
    public final float getVideoFrameRate() {
        if (mType != TYPE_VIDEO) {
            throw new IllegalStateException("Not a video track");
        }
        return mVideoFrameRate;
    }

    /**
     * Returns the pixel aspect ratio (the ratio of a pixel's width to its height) of the video.
     * Valid only for {@link #TYPE_VIDEO} tracks.
     *
     * @throws IllegalStateException if not called on a video track
     */
    public final float getVideoPixelAspectRatio() {
        if (mType != TYPE_VIDEO) {
            throw new IllegalStateException("Not a video track");
        }
        return mVideoPixelAspectRatio;
    }

    /**
     * Returns the Active Format Description (AFD) code of the video.
     * Valid only for {@link #TYPE_VIDEO} tracks.
     *
     * <p>The complete list of values are defined in ETSI TS 101 154 V1.7.1 Annex B, ATSC A/53 Part
     * 4 and SMPTE 2016-1-2007.
     *
     * @throws IllegalStateException if not called on a video track
     */
    public final byte getVideoActiveFormatDescription() {
        if (mType != TYPE_VIDEO) {
            throw new IllegalStateException("Not a video track");
        }
        return mVideoActiveFormatDescription;
    }

    /**
     * Returns the extra information about the current track.
     */
    public final Bundle getExtra() {
        return mExtra;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeString(mId);
        dest.writeString(mLanguage);
        dest.writeString(mDescription != null ? mDescription.toString() : null);
        dest.writeInt(mAudioChannelCount);
        dest.writeInt(mAudioSampleRate);
        dest.writeInt(mVideoWidth);
        dest.writeInt(mVideoHeight);
        dest.writeFloat(mVideoFrameRate);
        dest.writeFloat(mVideoPixelAspectRatio);
        dest.writeByte(mVideoActiveFormatDescription);
        dest.writeBundle(mExtra);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
          return true;
        }

        if (!(o instanceof TvTrackInfo)) {
          return false;
        }

        TvTrackInfo obj = (TvTrackInfo) o;
        return TextUtils.equals(mId, obj.mId)
                && mType == obj.mType
                && TextUtils.equals(mLanguage, obj.mLanguage)
                && TextUtils.equals(mDescription, obj.mDescription)
                && Objects.equals(mExtra, obj.mExtra)
                && (mType == TYPE_AUDIO
                        ? mAudioChannelCount == obj.mAudioChannelCount
                        && mAudioSampleRate == obj.mAudioSampleRate
                        : (mType == TYPE_VIDEO
                                ? mVideoWidth == obj.mVideoWidth
                                && mVideoHeight == obj.mVideoHeight
                                && mVideoFrameRate == obj.mVideoFrameRate
                                && mVideoPixelAspectRatio == obj.mVideoPixelAspectRatio : true));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mId);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TvTrackInfo> CREATOR =
            new Parcelable.Creator<TvTrackInfo>() {
                @Override
                public TvTrackInfo createFromParcel(Parcel in) {
                    return new TvTrackInfo(in);
                }

                @Override
                public TvTrackInfo[] newArray(int size) {
                    return new TvTrackInfo[size];
                }
            };

    /**
     * A builder class for creating {@link TvTrackInfo} objects.
     */
    public static final class Builder {
        private final String mId;
        private final int mType;
        private String mLanguage;
        private CharSequence mDescription;
        private int mAudioChannelCount;
        private int mAudioSampleRate;
        private int mVideoWidth;
        private int mVideoHeight;
        private float mVideoFrameRate;
        private float mVideoPixelAspectRatio = 1.0f;
        private byte mVideoActiveFormatDescription;
        private Bundle mExtra;

        /**
         * Create a {@link Builder}. Any field that should be included in the {@link TvTrackInfo}
         * must be added.
         *
         * @param type The type of the track.
         * @param id The ID of the track that uniquely identifies the current track among all the
         *            other tracks in the same TV program.
         * @throws IllegalArgumentException if the type is not any of {@link #TYPE_AUDIO},
         *                                  {@link #TYPE_VIDEO} and {@link #TYPE_SUBTITLE}
         */
        public Builder(@Type int type, @NonNull String id) {
            if (type != TYPE_AUDIO
                    && type != TYPE_VIDEO
                    && type != TYPE_SUBTITLE) {
                throw new IllegalArgumentException("Unknown type: " + type);
            }
            Preconditions.checkNotNull(id);
            mType = type;
            mId = id;
        }

        /**
         * Sets the language information of the current track.
         *
         * @param language The language string encoded by either ISO 639-1 or ISO 639-2/T.
         */
        public final Builder setLanguage(String language) {
            mLanguage = language;
            return this;
        }

        /**
         * Sets a user readable description for the current track.
         *
         * @param description The user readable description.
         */
        public final Builder setDescription(CharSequence description) {
            mDescription = description;
            return this;
        }

        /**
         * Sets the audio channel count. Valid only for {@link #TYPE_AUDIO} tracks.
         *
         * @param audioChannelCount The audio channel count.
         * @throws IllegalStateException if not called on an audio track
         */
        public final Builder setAudioChannelCount(int audioChannelCount) {
            if (mType != TYPE_AUDIO) {
                throw new IllegalStateException("Not an audio track");
            }
            mAudioChannelCount = audioChannelCount;
            return this;
        }

        /**
         * Sets the audio sample rate, in the unit of Hz. Valid only for {@link #TYPE_AUDIO}
         * tracks.
         *
         * @param audioSampleRate The audio sample rate.
         * @throws IllegalStateException if not called on an audio track
         */
        public final Builder setAudioSampleRate(int audioSampleRate) {
            if (mType != TYPE_AUDIO) {
                throw new IllegalStateException("Not an audio track");
            }
            mAudioSampleRate = audioSampleRate;
            return this;
        }

        /**
         * Sets the width of the video, in the unit of pixels. Valid only for {@link #TYPE_VIDEO}
         * tracks.
         *
         * @param videoWidth The width of the video.
         * @throws IllegalStateException if not called on a video track
         */
        public final Builder setVideoWidth(int videoWidth) {
            if (mType != TYPE_VIDEO) {
                throw new IllegalStateException("Not a video track");
            }
            mVideoWidth = videoWidth;
            return this;
        }

        /**
         * Sets the height of the video, in the unit of pixels. Valid only for {@link #TYPE_VIDEO}
         * tracks.
         *
         * @param videoHeight The height of the video.
         * @throws IllegalStateException if not called on a video track
         */
        public final Builder setVideoHeight(int videoHeight) {
            if (mType != TYPE_VIDEO) {
                throw new IllegalStateException("Not a video track");
            }
            mVideoHeight = videoHeight;
            return this;
        }

        /**
         * Sets the frame rate of the video, in the unit fps (frames per rate). Valid only for
         * {@link #TYPE_VIDEO} tracks.
         *
         * @param videoFrameRate The frame rate of the video.
         * @throws IllegalStateException if not called on a video track
         */
        public final Builder setVideoFrameRate(float videoFrameRate) {
            if (mType != TYPE_VIDEO) {
                throw new IllegalStateException("Not a video track");
            }
            mVideoFrameRate = videoFrameRate;
            return this;
        }

        /**
         * Sets the pixel aspect ratio (the ratio of a pixel's width to its height) of the video.
         * Valid only for {@link #TYPE_VIDEO} tracks.
         *
         * <p>This is needed for applications to be able to scale the video properly for some video
         * formats such as 720x576 4:3 and 720x576 16:9 where pixels are not square. By default,
         * applications assume the value of 1.0 (square pixels), so it is not necessary to set the
         * pixel aspect ratio for most video formats.
         *
         * @param videoPixelAspectRatio The pixel aspect ratio of the video.
         * @throws IllegalStateException if not called on a video track
         */
        public final Builder setVideoPixelAspectRatio(float videoPixelAspectRatio) {
            if (mType != TYPE_VIDEO) {
                throw new IllegalStateException("Not a video track");
            }
            mVideoPixelAspectRatio = videoPixelAspectRatio;
            return this;
        }

        /**
         * Sets the Active Format Description (AFD) code of the video.
         * Valid only for {@link #TYPE_VIDEO} tracks.
         *
         * <p>This is needed for applications to be able to scale the video properly based on the
         * information about where in the coded picture the active video is.
         * The complete list of values are defined in ETSI TS 101 154 V1.7.1 Annex B, ATSC A/53 Part
         * 4 and SMPTE 2016-1-2007.
         *
         * @param videoActiveFormatDescription The AFD code of the video.
         * @throws IllegalStateException if not called on a video track
         */
        public final Builder setVideoActiveFormatDescription(byte videoActiveFormatDescription) {
            if (mType != TYPE_VIDEO) {
                throw new IllegalStateException("Not a video track");
            }
            mVideoActiveFormatDescription = videoActiveFormatDescription;
            return this;
        }

        /**
         * Sets the extra information about the current track.
         *
         * @param extra The extra information.
         */
        public final Builder setExtra(Bundle extra) {
            mExtra = new Bundle(extra);
            return this;
        }

        /**
         * Creates a {@link TvTrackInfo} instance with the specified fields.
         *
         * @return The new {@link TvTrackInfo} instance
         */
        public TvTrackInfo build() {
            return new TvTrackInfo(mType, mId, mLanguage, mDescription, mAudioChannelCount,
                    mAudioSampleRate, mVideoWidth, mVideoHeight, mVideoFrameRate,
                    mVideoPixelAspectRatio, mVideoActiveFormatDescription, mExtra);
        }
    }
}
