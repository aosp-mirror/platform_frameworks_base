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

import android.annotation.NonNull;

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

        // Constructor called by JNI and CamcorderProfile
        /* package private */ VideoProfile(int codec,
                             int width,
                             int height,
                             int frameRate,
                             int bitrate,
                             int profile) {
            this.codec = codec;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.bitrate = bitrate;
            this.profile = profile;
        }

        private int codec;
        private int width;
        private int height;
        private int frameRate;
        private int bitrate;
        private int profile;
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
