/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Describes a set of encoding profiles for a given media (audio and/or video) profile.
 * These settings are read-only.
 *
 * <p>Currently, this is used to describe camera recording profile with more detail than {@link
 * CamcorderProfile}, by providing encoding parameters for more than just the default audio
 * and/or video codec.
 *
 * <p>The compressed output from a camera recording session contains two tracks:
 * one for audio and one for video.
 * <p>In the future audio-only recording profiles may be defined.
 *
 * <p>Each media profile specifies a set of audio and a set of video specific settings.
 * <ul>
 * <li> The file output format
 * <li> Default file duration
 * <p>Video-specific settings are:
 * <li> Video codec format
 * <li> Video bit rate in bits per second
 * <li> Video frame rate in frames per second
 * <li> Video frame width and height,
 * <li> Video encoder profile.
 * <p>Audio-specific settings are:
 * <li> Audio codec format
 * <li> Audio bit rate in bits per second,
 * <li> Audio sample rate
 * <li> Number of audio channels for recording.
 * </ul>
 */
public final class EncoderProfiles
{
    /**
     * Default recording duration in seconds before the session is terminated.
     * This is useful for applications like MMS that have a limited file size requirement.
     * This could be 0 if there is no default recording duration.
     */
    public int getDefaultDurationSeconds() {
        return durationSecs;
    }

    /**
     * Recommended output file format
     * @see android.media.MediaRecorder.OutputFormat
     */
    public @MediaRecorder.OutputFormatValues int getRecommendedFileFormat() {
        return fileFormat;
    }

    /**
     * Configuration for a video encoder.
     */
    public final static class VideoProfile {
        /**
         * The video encoder being used for the video track
         * @see android.media.MediaRecorder.VideoEncoder
         */
        public @MediaRecorder.VideoEncoderValues int getCodec() {
            return codec;
        }

        /**
         * The media type of the video encoder being used for the video track
         * @see android.media.MediaFormat#KEY_MIME
         */
        public @NonNull String getMediaType() {
            if (codec == MediaRecorder.VideoEncoder.H263) {
                return MediaFormat.MIMETYPE_VIDEO_H263;
            } else if (codec == MediaRecorder.VideoEncoder.H264) {
                return MediaFormat.MIMETYPE_VIDEO_AVC;
            } else if (codec == MediaRecorder.VideoEncoder.MPEG_4_SP) {
                return MediaFormat.MIMETYPE_VIDEO_MPEG4;
            } else if (codec == MediaRecorder.VideoEncoder.VP8) {
                return MediaFormat.MIMETYPE_VIDEO_VP8;
            } else if (codec == MediaRecorder.VideoEncoder.HEVC) {
                return MediaFormat.MIMETYPE_VIDEO_HEVC;
            } else if (codec == MediaRecorder.VideoEncoder.VP9) {
                return MediaFormat.MIMETYPE_VIDEO_VP9;
            } else if (codec == MediaRecorder.VideoEncoder.DOLBY_VISION) {
                return MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION;
            } else if (codec == MediaRecorder.VideoEncoder.AV1) {
                return MediaFormat.MIMETYPE_VIDEO_AV1;
            }
            // we should never be here
            throw new RuntimeException("Unknown codec");
        }

        /**
         * The target video output bitrate in bits per second
         * <p>
         * This is the target recorded video output bitrate if the application configures the video
         * recording via {@link MediaRecorder#setProfile} without specifying any other
         * {@link MediaRecorder} encoding parameters. For example, for high speed quality profiles
         * (from {@link CamcorderProfile#QUALITY_HIGH_SPEED_LOW} to {@link
         * CamcorderProfile#QUALITY_HIGH_SPEED_2160P}), this is the bitrate where the video is
         * recorded with. If the application intends to record slow motion videos with the high
         * speed quality profiles, it must set a different video bitrate that is corresponding to
         * the desired recording output bit rate (i.e., the encoded video bitrate during normal
         * playback) via {@link MediaRecorder#setVideoEncodingBitRate}. For example, if {@link
         * CamcorderProfile#QUALITY_HIGH_SPEED_720P} advertises 240fps {@link #getFrameRate} and
         * 64Mbps {@link #getBitrate} in the high speed VideoProfile, and the application
         * intends to record 1/8 factor slow motion recording videos, the application must set 30fps
         * via {@link MediaRecorder#setVideoFrameRate} and 8Mbps ( {@link #getBitrate} * slow motion
         * factor) via {@link MediaRecorder#setVideoEncodingBitRate}. Failing to do so will result
         * in videos with unexpected frame rate and bit rate, or {@link MediaRecorder} error if the
         * output bit rate exceeds the encoder limit. If the application intends to do the video
         * recording with {@link MediaCodec} encoder, it must set each individual field of {@link
         * MediaFormat} similarly according to this VideoProfile.
         * </p>
         *
         * @see #getFrameRate
         * @see MediaRecorder
         * @see MediaCodec
         * @see MediaFormat
         */
        public int getBitrate() {
            return bitrate;
        }

        /**
         * The target video frame rate in frames per second.
         * <p>
         * This is the target recorded video output frame rate per second if the application
         * configures the video recording via {@link MediaRecorder#setProfile} without specifying
         * any other {@link MediaRecorder} encoding parameters. For example, for high speed quality
         * profiles (from {@link CamcorderProfile#QUALITY_HIGH_SPEED_LOW} to {@link
         * CamcorderProfile#QUALITY_HIGH_SPEED_2160P}), this is the frame rate where the video is
         * recorded and played back with. If the application intends to create slow motion use case
         * with the high speed quality profiles, it must set a different video frame rate that is
         * corresponding to the desired output (playback) frame rate via {@link
         * MediaRecorder#setVideoFrameRate}. For example, if {@link
         * CamcorderProfile#QUALITY_HIGH_SPEED_720P} advertises 240fps {@link #getFrameRate}
         * in the VideoProfile, and the application intends to create 1/8 factor slow motion
         * recording videos, the application must set 30fps via {@link
         * MediaRecorder#setVideoFrameRate}. Failing to do so will result in high speed videos with
         * normal speed playback frame rate (240fps for above example). If the application intends
         * to do the video recording with {@link MediaCodec} encoder, it must set each individual
         * field of {@link MediaFormat} similarly according to this VideoProfile.
         * </p>
         *
         * @see #getBitrate
         * @see MediaRecorder
         * @see MediaCodec
         * @see MediaFormat
         */
        public int getFrameRate() {
            return frameRate;
        }

        /**
         * The target video frame width in pixels
         */
        public int getWidth() {
            return width;
        }

        /**
         * The target video frame height in pixels
         */
        public int getHeight() {
            return height;
        }

        /**
         * The video encoder profile being used for the video track.
         * <p>
         * This value is negative if there is no profile defined for the video codec.
         *
         * @see MediaRecorder#setVideoEncodingProfileLevel
         * @see MediaFormat#KEY_PROFILE
         */
        public int getProfile() {
            return profile;
        }

        /**
         * The bit depth of the encoded video.
         * <p>
         * This value is effectively 8 or 10, but some devices may
         * support additional values.
         */
        public int getBitDepth() {
            return bitDepth;
        }

        /**
         * The chroma subsampling of the encoded video.
         * <p>
         * For most devices this is always YUV_420 but some devices may
         * support additional values.
         *
         * @see #YUV_420
         * @see #YUV_422
         * @see #YUV_444
         */
        public @ChromaSubsampling int getChromaSubsampling() {
            return chromaSubsampling;
        }

        /**
         * The HDR format of the encoded video.
         * <p>
         * This is one of the HDR_ values.
         * @see #HDR_NONE
         * @see #HDR_HLG
         * @see #HDR_HDR10
         * @see #HDR_HDR10PLUS
         * @see #HDR_DOLBY_VISION
         */
        public @HdrFormat int getHdrFormat() {
            return hdrFormat;
        }

        // Constructor called by JNI and CamcorderProfile
        /* package private */ VideoProfile(int codec,
                             int width,
                             int height,
                             int frameRate,
                             int bitrate,
                             int profile,
                             int chromaSubsampling,
                             int bitDepth,
                             int hdrFormat) {
            this.codec = codec;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.bitrate = bitrate;
            this.profile = profile;
            this.chromaSubsampling = chromaSubsampling;
            this.bitDepth = bitDepth;
            this.hdrFormat = hdrFormat;
        }

        /* package private */ VideoProfile(int codec,
                             int width,
                             int height,
                             int frameRate,
                             int bitrate,
                             int profile) {
            this(codec, width, height, frameRate, bitrate, profile,
                 YUV_420, 8 /* bitDepth */, HDR_NONE);
        }

        private int codec;
        private int width;
        private int height;
        private int frameRate;
        private int bitrate;
        private int profile;
        private int chromaSubsampling;
        private int bitDepth;
        private int hdrFormat;

        /** @hide */
        @IntDef({
            HDR_NONE,
            HDR_HLG,
            HDR_HDR10,
            HDR_HDR10PLUS,
            HDR_DOLBY_VISION,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface HdrFormat {}

        /** Not HDR (SDR).
         *  <p>
         *  An HDR format specifying SDR (Standard Dynamic
         *  Range) recording. */
        public static final int HDR_NONE = 0;

        /** HLG (Hybrid-Log Gamma).
         *  <p>
         *  An HDR format specifying HLG. */
        public static final int HDR_HLG = 1;

        /** HDR10.
         *  <p>
         *  An HDR format specifying HDR10. */
        public static final int HDR_HDR10 = 2;

        /** HDR10+.
         *  <p>
         *  An HDR format specifying HDR10+. */
        public static final int HDR_HDR10PLUS = 3;

        /**
         *  Dolby Vision
         *  <p>
         *  An HDR format specifying Dolby Vision. For this format
         *  the codec is always a Dolby Vision encoder. The encoder
         *  profile specifies which Dolby Vision version is being
         *  used.
         *
         *  @see #getProfile
         */
        public static final int HDR_DOLBY_VISION = 4;

        /** @hide */
        @IntDef({
            YUV_420,
            YUV_422,
            YUV_444,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ChromaSubsampling {}


        /** YUV 4:2:0.
         *  <p>
         *  A chroma subsampling where the U and V planes are subsampled
         *  by 2 both horizontally and vertically. */
        public static final int YUV_420 = 0;

        /** YUV 4:2:2.
         *  <p>
         *  A chroma subsampling where the U and V planes are subsampled
         *  by 2 horizontally alone. */
        public static final int YUV_422 = 1;

        /** YUV 4:4:4.
         *  <p>
         *  A chroma subsampling where the U and V planes are not
         *  subsampled. */
        public static final int YUV_444 = 2;
    }

    /**
     * Returns the defined audio encoder profiles.
     * <p>
     * The list may be empty. This means there are no audio encoder
     * profiles defined. Otherwise, the first profile is the default
     * audio profile.
     */
    public @NonNull List<AudioProfile> getAudioProfiles() {
        return audioProfiles;
    }

    /**
     * Returns the defined video encoder profiles.
     * <p>
     * The list may be empty. This means there are no video encoder
     * profiles defined. Otherwise, the first profile is the default
     * video profile.
     */
    public @NonNull List<VideoProfile> getVideoProfiles() {
        return videoProfiles;
    }

    /**
     * Configuration for an audio encoder.
     */
    public final static class AudioProfile {
        /**
         * The audio encoder being used for the audio track.
         * @see android.media.MediaRecorder.AudioEncoder
         */
        public @MediaRecorder.AudioEncoderValues int getCodec() {
            return codec;
        }

        /**
         * The media type of the audio encoder being used for the video track
         * @see android.media.MediaFormat#KEY_MIME
         */
        public @NonNull String getMediaType() {
            if (codec == MediaRecorder.AudioEncoder.AMR_NB) {
                return MediaFormat.MIMETYPE_AUDIO_AMR_NB;
            } else if (codec == MediaRecorder.AudioEncoder.AMR_WB) {
                return MediaFormat.MIMETYPE_AUDIO_AMR_WB;
            } else if (codec == MediaRecorder.AudioEncoder.AAC
                    || codec == MediaRecorder.AudioEncoder.HE_AAC
                    || codec == MediaRecorder.AudioEncoder.AAC_ELD) {
                return MediaFormat.MIMETYPE_AUDIO_AAC;
            } else if (codec == MediaRecorder.AudioEncoder.VORBIS) {
                return MediaFormat.MIMETYPE_AUDIO_VORBIS;
            } else if (codec == MediaRecorder.AudioEncoder.OPUS) {
                return MediaFormat.MIMETYPE_AUDIO_OPUS;
            }
            // we should never be here
            throw new RuntimeException("Unknown codec");
        }

        /**
         * The target audio output bitrate in bits per second
         */
        public int getBitrate() {
            return bitrate;
        }

        /**
         * The audio sampling rate used for the audio track
         */
        public int getSampleRate() {
            return sampleRate;
        }

        /**
         * The number of audio channels used for the audio track
         */
        public int getChannels() {
            return channels;
        }

        /**
         * The audio encoder profile being used for the audio track
         * <p>
         * This value is negative if there is no profile defined for the audio codec.
         * @see MediaFormat#KEY_PROFILE
         */
        public int getProfile() {
            if (codec == MediaRecorder.AudioEncoder.AAC) {
                return MediaCodecInfo.CodecProfileLevel.AACObjectMain;
            } else if (codec == MediaRecorder.AudioEncoder.HE_AAC) {
                return MediaCodecInfo.CodecProfileLevel.AACObjectHE;
            } else if (codec == MediaRecorder.AudioEncoder.AAC_ELD) {
                return MediaCodecInfo.CodecProfileLevel.AACObjectELD;
            }
            return profile;
        }


        // Constructor called by JNI and CamcorderProfile
        /* package private */ AudioProfile(
                int codec,
                int channels,
                int sampleRate,
                int bitrate,
                int profile) {
            this.codec = codec;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.bitrate = bitrate;
            this.profile = profile;
        }

        private int codec;
        private int channels;
        private int sampleRate;
        private int bitrate;
        private int profile;  // this contains the profile if codec itself does not
    }

    private int durationSecs;
    private int fileFormat;
    // non-modifiable lists
    private @NonNull List<AudioProfile> audioProfiles;
    private @NonNull List<VideoProfile> videoProfiles;

    // Constructor called by JNI and CamcorderProfile
    /* package private */ EncoderProfiles(
            int duration,
            int fileFormat,
            VideoProfile[] videoProfiles,
            AudioProfile[] audioProfiles) {
        this.durationSecs     = duration;
        this.fileFormat       = fileFormat;
        this.videoProfiles    = Collections.unmodifiableList(Arrays.asList(videoProfiles));
        this.audioProfiles    = Collections.unmodifiableList(Arrays.asList(audioProfiles));
    }
}
