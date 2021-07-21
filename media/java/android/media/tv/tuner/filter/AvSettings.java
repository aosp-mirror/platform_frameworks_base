/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.tv.tuner.TunerUtils;
import android.media.tv.tuner.TunerVersionChecker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Filter Settings for a Video and Audio.
 *
 * @hide
 */
@SystemApi
public class AvSettings extends Settings {
    /** @hide */
    @IntDef(prefix = "VIDEO_STREAM_TYPE_",
            value = {VIDEO_STREAM_TYPE_UNDEFINED, VIDEO_STREAM_TYPE_RESERVED,
                    VIDEO_STREAM_TYPE_MPEG1, VIDEO_STREAM_TYPE_MPEG2,
                    VIDEO_STREAM_TYPE_MPEG4P2, VIDEO_STREAM_TYPE_AVC, VIDEO_STREAM_TYPE_HEVC,
                    VIDEO_STREAM_TYPE_VC1, VIDEO_STREAM_TYPE_VP8, VIDEO_STREAM_TYPE_VP9,
                    VIDEO_STREAM_TYPE_AV1, VIDEO_STREAM_TYPE_AVS, VIDEO_STREAM_TYPE_AVS2})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VideoStreamType {}

    /*
     * Undefined Video stream type
     */
    public static final int VIDEO_STREAM_TYPE_UNDEFINED =
            android.hardware.tv.tuner.VideoStreamType.UNDEFINED;
    /*
     * ITU-T | ISO/IEC Reserved
     */
    public static final int VIDEO_STREAM_TYPE_RESERVED =
            android.hardware.tv.tuner.VideoStreamType.RESERVED;
    /*
     * ISO/IEC 11172
     */
    public static final int VIDEO_STREAM_TYPE_MPEG1 =
            android.hardware.tv.tuner.VideoStreamType.MPEG1;
    /*
     * ITU-T Rec.H.262 and ISO/IEC 13818-2
     */
    public static final int VIDEO_STREAM_TYPE_MPEG2 =
            android.hardware.tv.tuner.VideoStreamType.MPEG2;
    /*
     * ISO/IEC 14496-2 (MPEG-4 H.263 based video)
     */
    public static final int VIDEO_STREAM_TYPE_MPEG4P2 =
            android.hardware.tv.tuner.VideoStreamType.MPEG4P2;
    /*
     * ITU-T Rec.H.264 and ISO/IEC 14496-10
     */
    public static final int VIDEO_STREAM_TYPE_AVC = android.hardware.tv.tuner.VideoStreamType.AVC;
    /*
     * ITU-T Rec. H.265 and ISO/IEC 23008-2
     */
    public static final int VIDEO_STREAM_TYPE_HEVC = android.hardware.tv.tuner.VideoStreamType.HEVC;
    /*
     * Microsoft VC.1
     */
    public static final int VIDEO_STREAM_TYPE_VC1 = android.hardware.tv.tuner.VideoStreamType.VC1;
    /*
     * Google VP8
     */
    public static final int VIDEO_STREAM_TYPE_VP8 = android.hardware.tv.tuner.VideoStreamType.VP8;
    /*
     * Google VP9
     */
    public static final int VIDEO_STREAM_TYPE_VP9 = android.hardware.tv.tuner.VideoStreamType.VP9;
    /*
     * AOMedia Video 1
     */
    public static final int VIDEO_STREAM_TYPE_AV1 = android.hardware.tv.tuner.VideoStreamType.AV1;
    /*
     * Chinese Standard
     */
    public static final int VIDEO_STREAM_TYPE_AVS = android.hardware.tv.tuner.VideoStreamType.AVS;
    /*
     * New Chinese Standard
     */
    public static final int VIDEO_STREAM_TYPE_AVS2 = android.hardware.tv.tuner.VideoStreamType.AVS2;

    /** @hide */
    @IntDef(prefix = "AUDIO_STREAM_TYPE_",
            value = {AUDIO_STREAM_TYPE_UNDEFINED, AUDIO_STREAM_TYPE_PCM, AUDIO_STREAM_TYPE_MP3,
                    AUDIO_STREAM_TYPE_MPEG1, AUDIO_STREAM_TYPE_MPEG2, AUDIO_STREAM_TYPE_MPEGH,
                    AUDIO_STREAM_TYPE_AAC, AUDIO_STREAM_TYPE_AC3, AUDIO_STREAM_TYPE_EAC3,
                    AUDIO_STREAM_TYPE_AC4, AUDIO_STREAM_TYPE_DTS, AUDIO_STREAM_TYPE_DTS_HD,
                    AUDIO_STREAM_TYPE_WMA, AUDIO_STREAM_TYPE_OPUS, AUDIO_STREAM_TYPE_VORBIS,
                    AUDIO_STREAM_TYPE_DRA})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioStreamType {}

    /*
     * Undefined Audio stream type
     */
    public static final int AUDIO_STREAM_TYPE_UNDEFINED =
            android.hardware.tv.tuner.AudioStreamType.UNDEFINED;
    /*
     * Uncompressed Audio
     */
    public static final int AUDIO_STREAM_TYPE_PCM = android.hardware.tv.tuner.AudioStreamType.PCM;
    /*
     * MPEG Audio Layer III versions
     */
    public static final int AUDIO_STREAM_TYPE_MP3 = android.hardware.tv.tuner.AudioStreamType.MP3;
    /*
     * ISO/IEC 11172 Audio
     */
    public static final int AUDIO_STREAM_TYPE_MPEG1 =
            android.hardware.tv.tuner.AudioStreamType.MPEG1;
    /*
     * ISO/IEC 13818-3
     */
    public static final int AUDIO_STREAM_TYPE_MPEG2 =
            android.hardware.tv.tuner.AudioStreamType.MPEG2;
    /*
     * ISO/IEC 23008-3 (MPEG-H Part 3)
     */
    public static final int AUDIO_STREAM_TYPE_MPEGH =
            android.hardware.tv.tuner.AudioStreamType.MPEGH;
    /*
     * ISO/IEC 14496-3
     */
    public static final int AUDIO_STREAM_TYPE_AAC = android.hardware.tv.tuner.AudioStreamType.AAC;
    /*
     * Dolby Digital
     */
    public static final int AUDIO_STREAM_TYPE_AC3 = android.hardware.tv.tuner.AudioStreamType.AC3;
    /*
     * Dolby Digital Plus
     */
    public static final int AUDIO_STREAM_TYPE_EAC3 = android.hardware.tv.tuner.AudioStreamType.EAC3;
    /*
     * Dolby AC-4
     */
    public static final int AUDIO_STREAM_TYPE_AC4 = android.hardware.tv.tuner.AudioStreamType.AC4;
    /*
     * Basic DTS
     */
    public static final int AUDIO_STREAM_TYPE_DTS = android.hardware.tv.tuner.AudioStreamType.DTS;
    /*
     * High Resolution DTS
     */
    public static final int AUDIO_STREAM_TYPE_DTS_HD =
            android.hardware.tv.tuner.AudioStreamType.DTS_HD;
    /*
     * Windows Media Audio
     */
    public static final int AUDIO_STREAM_TYPE_WMA = android.hardware.tv.tuner.AudioStreamType.WMA;
    /*
     * Opus Interactive Audio Codec
     */
    public static final int AUDIO_STREAM_TYPE_OPUS = android.hardware.tv.tuner.AudioStreamType.OPUS;
    /*
     * VORBIS Interactive Audio Codec
     */
    public static final int AUDIO_STREAM_TYPE_VORBIS =
            android.hardware.tv.tuner.AudioStreamType.VORBIS;
    /*
     * SJ/T 11368-2006
     */
    public static final int AUDIO_STREAM_TYPE_DRA = android.hardware.tv.tuner.AudioStreamType.DRA;


    private final boolean mIsPassthrough;
    private int mAudioStreamType = AUDIO_STREAM_TYPE_UNDEFINED;
    private int mVideoStreamType = VIDEO_STREAM_TYPE_UNDEFINED;

    private AvSettings(int mainType, boolean isAudio, boolean isPassthrough,
            int audioStreamType, int videoStreamType) {
        super(TunerUtils.getFilterSubtype(
                mainType,
                isAudio
                        ? Filter.SUBTYPE_AUDIO
                        : Filter.SUBTYPE_VIDEO));
        mIsPassthrough = isPassthrough;
        mAudioStreamType = audioStreamType;
        mVideoStreamType = videoStreamType;
    }

    /**
     * Checks whether it's passthrough.
     */
    public boolean isPassthrough() {
        return mIsPassthrough;
    }

    /**
     * Get the Audio Stream Type.
     */
    @AudioStreamType
    public int getAudioStreamType() {
        return mAudioStreamType;
    }

    /**
     * Get the Video Stream Type.
     */
    @VideoStreamType
    public int getVideoStreamType() {
        return mVideoStreamType;
    }

    /**
     * Creates a builder for {@link AvSettings}.
     *
     * @param mainType the filter main type.
     * @param isAudio {@code true} if it's audio settings; {@code false} if it's video settings.
     */
    @NonNull
    public static Builder builder(@Filter.Type int mainType, boolean isAudio) {
        return new Builder(mainType, isAudio);
    }

    /**
     * Builder for {@link AvSettings}.
     */
    public static class Builder {
        private final int mMainType;
        private final boolean mIsAudio;
        private boolean mIsPassthrough;
        private int mAudioStreamType = AUDIO_STREAM_TYPE_UNDEFINED;
        private int mVideoStreamType = VIDEO_STREAM_TYPE_UNDEFINED;

        private Builder(int mainType, boolean isAudio) {
            mMainType = mainType;
            mIsAudio = isAudio;
        }

        /**
         * Sets whether it's passthrough.
         */
        @NonNull
        public Builder setPassthrough(boolean isPassthrough) {
            mIsPassthrough = isPassthrough;
            return this;
        }

        /**
         * Sets the Audio Stream Type.
         *
         * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
         * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
         *
         * @param audioStreamType the audio stream type to set.
         */
        @NonNull
        public Builder setAudioStreamType(@AudioStreamType int audioStreamType) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_1_1, "setAudioStreamType") && mIsAudio) {
                mAudioStreamType = audioStreamType;
                mVideoStreamType = VIDEO_STREAM_TYPE_UNDEFINED;
            }
            return this;
        }

        /**
         * Sets the Video Stream Type.
         *
         * <p>This API is only supported by Tuner HAL 1.1 or higher. Unsupported version would cause
         * no-op. Use {@link TunerVersionChecker#getTunerVersion()} to check the version.
         *
         * @param videoStreamType the video stream type to set.
         */
        @NonNull
        public Builder setVideoStreamType(@VideoStreamType int videoStreamType) {
            if (TunerVersionChecker.checkHigherOrEqualVersionTo(
                    TunerVersionChecker.TUNER_VERSION_1_1, "setVideoStreamType") && !mIsAudio) {
                mVideoStreamType = videoStreamType;
                mAudioStreamType = AUDIO_STREAM_TYPE_UNDEFINED;
            }
            return this;
        }

        /**
         * Builds a {@link AvSettings} object.
         */
        @NonNull
        public AvSettings build() {
            return new AvSettings(mMainType, mIsAudio, mIsPassthrough,
                    mAudioStreamType, mVideoStreamType);
        }
    }
}
