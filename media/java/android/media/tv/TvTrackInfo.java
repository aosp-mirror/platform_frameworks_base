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
import android.annotation.Nullable;
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
    @Nullable
    private final String mEncoding;
    private final boolean mEncrypted;
    private final int mAudioChannelCount;
    private final int mAudioSampleRate;
    private final boolean mAudioDescription;
    private final boolean mHardOfHearing;
    private final boolean mSpokenSubtitle;
    private final int mVideoWidth;
    private final int mVideoHeight;
    private final float mVideoFrameRate;
    private final float mVideoPixelAspectRatio;
    private final byte mVideoActiveFormatDescription;

    private final Bundle mExtra;

    private TvTrackInfo(int type, String id, String language, CharSequence description,
            String encoding, boolean encrypted, int audioChannelCount, int audioSampleRate,
            boolean audioDescription, boolean hardOfHearing, boolean spokenSubtitle, int videoWidth,
            int videoHeight, float videoFrameRate, float videoPixelAspectRatio,
            byte videoActiveFormatDescription, Bundle extra) {
        mType = type;
        mId = id;
        mLanguage = language;
        mDescription = description;
        mEncoding = encoding;
        mEncrypted = encrypted;
        mAudioChannelCount = audioChannelCount;
        mAudioSampleRate = audioSampleRate;
        mAudioDescription = audioDescription;
        mHardOfHearing = hardOfHearing;
        mSpokenSubtitle = spokenSubtitle;
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
        mEncoding = in.readString();
        mEncrypted = in.readInt() != 0;
        mAudioChannelCount = in.readInt();
        mAudioSampleRate = in.readInt();
        mAudioDescription = in.readInt() != 0;
        mHardOfHearing = in.readInt() != 0;
        mSpokenSubtitle = in.readInt() != 0;
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
     * Returns the codec in the form of mime type. If the encoding is unknown or could not be
     * determined, the corresponding value will be {@code null}.
     *
     * <p>For example of broadcast, codec information may be referred to broadcast standard (e.g.
     * Component Descriptor of ETSI EN 300 468). In the case that track type is subtitle, mime type
     * could be defined in broadcast standard (e.g. "text/dvb.subtitle" or "text/dvb.teletext" in
     * ETSI TS 102 812 V1.3.1 section 7.6).
     */
    @Nullable
    public String getEncoding() {
        return mEncoding;
    }

    /**
     * Returns {@code true} if the track is encrypted, {@code false} otherwise. If the encryption
     * status is unknown or could not be determined, the corresponding value will be {@code false}.
     *
     * <p>For example: ISO/IEC 13818-1 defines a CA descriptor that can be used to determine the
     * encryption status of some broadcast streams.
     */
    public boolean isEncrypted() {
        return mEncrypted;
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
     * Returns {@code true} if the track is an audio description intended for people with visual
     * impairment, {@code false} otherwise. Valid only for {@link #TYPE_AUDIO} tracks.
     *
     * <p>For example of broadcast, audio description information may be referred to broadcast
     * standard (e.g. ISO 639 Language Descriptor of ISO/IEC 13818-1, Supplementary Audio Language
     * Descriptor, AC-3 Descriptor, Enhanced AC-3 Descriptor, AAC Descriptor of ETSI EN 300 468).
     *
     * @throws IllegalStateException if not called on an audio track
     */
    public boolean isAudioDescription() {
        if (mType != TYPE_AUDIO) {
            throw new IllegalStateException("Not an audio track");
        }
        return mAudioDescription;
    }

    /**
     * Returns {@code true} if the track is intended for people with hearing impairment, {@code
     * false} otherwise. Valid only for {@link #TYPE_AUDIO} and {@link #TYPE_SUBTITLE} tracks.
     *
     * <p>For example of broadcast, hard of hearing information may be referred to broadcast
     * standard (e.g. ISO 639 Language Descriptor of ISO/IEC 13818-1, Supplementary Audio Language
     * Descriptor, AC-3 Descriptor, Enhanced AC-3 Descriptor, AAC Descriptor of ETSI EN 300 468).
     *
     * @throws IllegalStateException if not called on an audio track or a subtitle track
     */
    public boolean isHardOfHearing() {
        if (mType != TYPE_AUDIO && mType != TYPE_SUBTITLE) {
            throw new IllegalStateException("Not an audio or a subtitle track");
        }
        return mHardOfHearing;
    }

    /**
     * Returns {@code true} if the track is a spoken subtitle for people with visual impairment,
     * {@code false} otherwise. Valid only for {@link #TYPE_AUDIO} tracks.
     *
     * <p>For example of broadcast, spoken subtitle information may be referred to broadcast
     * standard (e.g. Supplementary Audio Language Descriptor of ETSI EN 300 468).
     *
     * @throws IllegalStateException if not called on an audio track
     */
    public boolean isSpokenSubtitle() {
        if (mType != TYPE_AUDIO) {
            throw new IllegalStateException("Not an audio track");
        }
        return mSpokenSubtitle;
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
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Preconditions.checkNotNull(dest);
        dest.writeInt(mType);
        dest.writeString(mId);
        dest.writeString(mLanguage);
        dest.writeString(mDescription != null ? mDescription.toString() : null);
        dest.writeString(mEncoding);
        dest.writeInt(mEncrypted ? 1 : 0);
        dest.writeInt(mAudioChannelCount);
        dest.writeInt(mAudioSampleRate);
        dest.writeInt(mAudioDescription ? 1 : 0);
        dest.writeInt(mHardOfHearing ? 1 : 0);
        dest.writeInt(mSpokenSubtitle ? 1 : 0);
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

        if (!TextUtils.equals(mId, obj.mId) || mType != obj.mType
                || !TextUtils.equals(mLanguage, obj.mLanguage)
                || !TextUtils.equals(mDescription, obj.mDescription)
                || !TextUtils.equals(mEncoding, obj.mEncoding)
                || mEncrypted != obj.mEncrypted) {
            return false;
        }

        switch (mType) {
            case TYPE_AUDIO:
                return mAudioChannelCount == obj.mAudioChannelCount
                        && mAudioSampleRate == obj.mAudioSampleRate
                        && mAudioDescription == obj.mAudioDescription
                        && mHardOfHearing == obj.mHardOfHearing
                        && mSpokenSubtitle == obj.mSpokenSubtitle;

            case TYPE_VIDEO:
                return mVideoWidth == obj.mVideoWidth
                        && mVideoHeight == obj.mVideoHeight
                        && mVideoFrameRate == obj.mVideoFrameRate
                        && mVideoPixelAspectRatio == obj.mVideoPixelAspectRatio
                        && mVideoActiveFormatDescription == obj.mVideoActiveFormatDescription;

            case TYPE_SUBTITLE:
                return mHardOfHearing == obj.mHardOfHearing;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mId, mType, mLanguage, mDescription);

        if (mType == TYPE_AUDIO) {
            result = Objects.hash(result, mAudioChannelCount, mAudioSampleRate);
        } else if (mType == TYPE_VIDEO) {
            result = Objects.hash(result, mVideoWidth, mVideoHeight, mVideoFrameRate,
                    mVideoPixelAspectRatio);
        }

        return result;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TvTrackInfo> CREATOR =
            new Parcelable.Creator<TvTrackInfo>() {
                @Override
                @NonNull
                public TvTrackInfo createFromParcel(Parcel in) {
                    return new TvTrackInfo(in);
                }

                @Override
                @NonNull
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
        private String mEncoding;
        private boolean mEncrypted;
        private int mAudioChannelCount;
        private int mAudioSampleRate;
        private boolean mAudioDescription;
        private boolean mHardOfHearing;
        private boolean mSpokenSubtitle;
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
        @NonNull
        public  Builder setLanguage(@NonNull String language) {
            Preconditions.checkNotNull(language);
            mLanguage = language;
            return this;
        }

        /**
         * Sets a user readable description for the current track.
         *
         * @param description The user readable description.
         */
        @NonNull
        public  Builder setDescription(@NonNull CharSequence description) {
            Preconditions.checkNotNull(description);
            mDescription = description;
            return this;
        }

        /**
         * Sets the encoding of the track.
         *
         * <p>For example of broadcast, codec information may be referred to broadcast standard
         * (e.g. Component Descriptor of ETSI EN 300 468). In the case that track type is subtitle,
         * mime type could be defined in broadcast standard (e.g. "text/dvb.subtitle" or
         * "text/dvb.teletext" in ETSI TS 102 812 V1.3.1 section 7.6).
         *
         * @param encoding The encoding of the track in the form of mime type.
         */
        @NonNull
        public Builder setEncoding(@Nullable String encoding) {
            mEncoding = encoding;
            return this;
        }

        /**
         * Sets the encryption status of the track.
         *
         * <p>For example: ISO/IEC 13818-1 defines a CA descriptor that can be used to determine the
         * encryption status of some broadcast streams.
         *
         * @param encrypted The encryption status of the track.
         */
        @NonNull
        public Builder setEncrypted(boolean encrypted) {
            mEncrypted = encrypted;
            return this;
        }

        /**
         * Sets the audio channel count. Valid only for {@link #TYPE_AUDIO} tracks.
         *
         * @param audioChannelCount The audio channel count.
         * @throws IllegalStateException if not called on an audio track
         */
        @NonNull
        public Builder setAudioChannelCount(int audioChannelCount) {
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
        @NonNull
        public Builder setAudioSampleRate(int audioSampleRate) {
            if (mType != TYPE_AUDIO) {
                throw new IllegalStateException("Not an audio track");
            }
            mAudioSampleRate = audioSampleRate;
            return this;
        }

        /**
         * Sets the audio description attribute of the audio. Valid only for {@link #TYPE_AUDIO}
         * tracks.
         *
         * <p>For example of broadcast, audio description information may be referred to broadcast
         * standard (e.g. ISO 639 Language Descriptor of ISO/IEC 13818-1, Supplementary Audio
         * Language Descriptor, AC-3 Descriptor, Enhanced AC-3 Descriptor, AAC Descriptor of ETSI EN
         * 300 468).
         *
         * @param audioDescription The audio description attribute of the audio.
         * @throws IllegalStateException if not called on an audio track
         */
        @NonNull
        public Builder setAudioDescription(boolean audioDescription) {
            if (mType != TYPE_AUDIO) {
                throw new IllegalStateException("Not an audio track");
            }
            mAudioDescription = audioDescription;
            return this;
        }

        /**
         * Sets the hard of hearing attribute of the track. Valid only for {@link #TYPE_AUDIO} and
         * {@link #TYPE_SUBTITLE} tracks.
         *
         * <p>For example of broadcast, hard of hearing information may be referred to broadcast
         * standard (e.g. ISO 639 Language Descriptor of ISO/IEC 13818-1, Supplementary Audio
         * Language Descriptor, AC-3 Descriptor, Enhanced AC-3 Descriptor, AAC Descriptor of ETSI EN
         * 300 468).
         *
         * @param hardOfHearing The hard of hearing attribute of the track.
         * @throws IllegalStateException if not called on an audio track or a subtitle track
         */
        @NonNull
        public Builder setHardOfHearing(boolean hardOfHearing) {
            if (mType != TYPE_AUDIO && mType != TYPE_SUBTITLE) {
                throw new IllegalStateException("Not an audio track or a subtitle track");
            }
            mHardOfHearing = hardOfHearing;
            return this;
        }

        /**
         * Sets the spoken subtitle attribute of the audio. Valid only for {@link #TYPE_AUDIO}
         * tracks.
         *
         * <p>For example of broadcast, spoken subtitle information may be referred to broadcast
         * standard (e.g. Supplementary Audio Language Descriptor of ETSI EN 300 468).
         *
         * @param spokenSubtitle The spoken subtitle attribute of the audio.
         * @throws IllegalStateException if not called on an audio track
         */
        @NonNull
        public Builder setSpokenSubtitle(boolean spokenSubtitle) {
            if (mType != TYPE_AUDIO) {
                throw new IllegalStateException("Not an audio track");
            }
            mSpokenSubtitle = spokenSubtitle;
            return this;
        }

        /**
         * Sets the width of the video, in the unit of pixels. Valid only for {@link #TYPE_VIDEO}
         * tracks.
         *
         * @param videoWidth The width of the video.
         * @throws IllegalStateException if not called on a video track
         */
        @NonNull
        public Builder setVideoWidth(int videoWidth) {
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
        @NonNull
        public Builder setVideoHeight(int videoHeight) {
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
        @NonNull
        public Builder setVideoFrameRate(float videoFrameRate) {
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
        @NonNull
        public Builder setVideoPixelAspectRatio(float videoPixelAspectRatio) {
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
        @NonNull
        public Builder setVideoActiveFormatDescription(byte videoActiveFormatDescription) {
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
        @NonNull
        public Builder setExtra(@NonNull Bundle extra) {
            Preconditions.checkNotNull(extra);
            mExtra = new Bundle(extra);
            return this;
        }

        /**
         * Creates a {@link TvTrackInfo} instance with the specified fields.
         *
         * @return The new {@link TvTrackInfo} instance
         */
        @NonNull
        public TvTrackInfo build() {
            return new TvTrackInfo(mType, mId, mLanguage, mDescription, mEncoding, mEncrypted,
                    mAudioChannelCount, mAudioSampleRate, mAudioDescription, mHardOfHearing,
                    mSpokenSubtitle, mVideoWidth, mVideoHeight, mVideoFrameRate,
                    mVideoPixelAspectRatio, mVideoActiveFormatDescription, mExtra);
        }
    }
}
