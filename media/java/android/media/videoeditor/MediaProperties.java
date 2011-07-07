/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.media.videoeditor;

import android.media.videoeditor.VideoEditorProfile;
import android.util.Pair;
import java.lang.System;
/**
 * This class defines all properties of a media file such as supported height,
 * aspect ratio, bitrate for export function.
 * {@hide}
 */
public class MediaProperties {
    /**
     *  Supported heights
     */
    public static final int HEIGHT_144 = 144;
    public static final int HEIGHT_288 = 288;
    public static final int HEIGHT_360 = 360;
    public static final int HEIGHT_480 = 480;
    public static final int HEIGHT_720 = 720;
    public static final int HEIGHT_1080 = 1080;

    /**
     *  Supported aspect ratios
     */
    public static final int ASPECT_RATIO_UNDEFINED = 0;
    public static final int ASPECT_RATIO_3_2 = 1;
    public static final int ASPECT_RATIO_16_9 = 2;
    public static final int ASPECT_RATIO_4_3 = 3;
    public static final int ASPECT_RATIO_5_3 = 4;
    public static final int ASPECT_RATIO_11_9 = 5;

    /**
     *  The array of supported aspect ratios
     */
    private static final int[] ASPECT_RATIOS = new int[] {
        ASPECT_RATIO_3_2,
        ASPECT_RATIO_16_9,
        ASPECT_RATIO_4_3,
        ASPECT_RATIO_5_3,
        ASPECT_RATIO_11_9
    };

    /**
     *  Supported resolutions for specific aspect ratios
     */
    @SuppressWarnings({"unchecked"})
    private static final Pair<Integer, Integer>[] ASPECT_RATIO_3_2_RESOLUTIONS =
        new Pair[] {
        new Pair<Integer, Integer>(720, HEIGHT_480),
        new Pair<Integer, Integer>(1080, HEIGHT_720)
    };

    @SuppressWarnings({"unchecked"})
    private static final Pair<Integer, Integer>[] ASPECT_RATIO_4_3_RESOLUTIONS =
        new Pair[] {
        new Pair<Integer, Integer>(640, HEIGHT_480),
        new Pair<Integer, Integer>(960, HEIGHT_720)
    };

    @SuppressWarnings({"unchecked"})
    private static final Pair<Integer, Integer>[] ASPECT_RATIO_5_3_RESOLUTIONS =
        new Pair[] {
        new Pair<Integer, Integer>(800, HEIGHT_480)
    };

    @SuppressWarnings({"unchecked"})
    private static final Pair<Integer, Integer>[] ASPECT_RATIO_11_9_RESOLUTIONS =
        new Pair[] {
        new Pair<Integer, Integer>(176, HEIGHT_144),
        new Pair<Integer, Integer>(352, HEIGHT_288)
    };

    @SuppressWarnings({"unchecked"})
    private static final Pair<Integer, Integer>[] ASPECT_RATIO_16_9_RESOLUTIONS =
        new Pair[] {
        new Pair<Integer, Integer>(848, HEIGHT_480),
        new Pair<Integer, Integer>(1280, HEIGHT_720),
        new Pair<Integer, Integer>(1920, HEIGHT_1080),
    };

    /**
     *  Bitrate values (in bits per second)
     */
    public static final int BITRATE_28K = 28000;
    public static final int BITRATE_40K = 40000;
    public static final int BITRATE_64K = 64000;
    public static final int BITRATE_96K = 96000;
    public static final int BITRATE_128K = 128000;
    public static final int BITRATE_192K = 192000;
    public static final int BITRATE_256K = 256000;
    public static final int BITRATE_384K = 384000;
    public static final int BITRATE_512K = 512000;
    public static final int BITRATE_800K = 800000;
    public static final int BITRATE_2M = 2000000;
    public static final int BITRATE_5M = 5000000;
    public static final int BITRATE_8M = 8000000;

    /**
     *  The array of supported bitrates
     */
    private static final int[] SUPPORTED_BITRATES = new int[] {
        BITRATE_28K,
        BITRATE_40K,
        BITRATE_64K,
        BITRATE_96K,
        BITRATE_128K,
        BITRATE_192K,
        BITRATE_256K,
        BITRATE_384K,
        BITRATE_512K,
        BITRATE_800K,
        BITRATE_2M,
        BITRATE_5M,
        BITRATE_8M
    };

    /**
     *  Video codec types
     */
    public static final int VCODEC_H263 = 1;
    public static final int VCODEC_MPEG4 = 2;
    // 3 Value is used for MPEG4_EMP
    public static final int VCODEC_H264BP = 4;
    public static final int VCODEC_H264MP = 5;  // Unsupported

    /**
     *  The array of supported video codecs
     */
    private static final int[] SUPPORTED_VCODECS = new int[] {
        VCODEC_H264BP,
        VCODEC_H263,
        VCODEC_MPEG4,
    };

    /* H.263 Profiles and levels */
    public static final int     H263_PROFILE_0_LEVEL_10   = 0;
    public static final int     H263_PROFILE_0_LEVEL_20   = 1;
    public static final int     H263_PROFILE_0_LEVEL_30   = 2;
    public static final int     H263_PROFILE_0_LEVEL_40   = 3;
    public static final int     H263_PROFILE_0_LEVEL_45   = 4;
    /* MPEG-4 Profiles and levels */
    public static final int     MPEG4_SP_LEVEL_0          = 50;
    public static final int     MPEG4_SP_LEVEL_0B         = 51;
    public static final int     MPEG4_SP_LEVEL_1          = 52;
    public static final int     MPEG4_SP_LEVEL_2          = 53;
    public static final int     MPEG4_SP_LEVEL_3          = 54;
    public static final int     MPEG4_SP_LEVEL_4A         = 55;
    public static final int     MPEG4_SP_LEVEL_5          = 56;
    /* AVC Profiles and levels */
    public static final int     H264_PROFILE_0_LEVEL_1    = 150;
    public static final int     H264_PROFILE_0_LEVEL_1B   = 151;
    public static final int     H264_PROFILE_0_LEVEL_1_1  = 152;
    public static final int     H264_PROFILE_0_LEVEL_1_2  = 153;
    public static final int     H264_PROFILE_0_LEVEL_1_3  = 154;
    public static final int     H264_PROFILE_0_LEVEL_2    = 155;
    public static final int     H264_PROFILE_0_LEVEL_2_1  = 156;
    public static final int     H264_PROFILE_0_LEVEL_2_2  = 157;
    public static final int     H264_PROFILE_0_LEVEL_3    = 158;
    public static final int     H264_PROFILE_0_LEVEL_3_1  = 159;
    public static final int     H264_PROFILE_0_LEVEL_3_2  = 160;
    public static final int     H264_PROFILE_0_LEVEL_4    = 161;
    public static final int     H264_PROFILE_0_LEVEL_4_1  = 162;
    public static final int     H264_PROFILE_0_LEVEL_4_2  = 163;
    public static final int     H264_PROFILE_0_LEVEL_5    = 164;
    public static final int     H264_PROFILE_0_LEVEL_5_1  = 165;
    /* Unsupported profile and level */
    public static final int     UNSUPPORTED_PROFILE_LEVEL = 255;

    /**
     *  The array of supported video codec Profile and Levels
     */
    private static final int[] SUPPORTED_VCODEC_PROFILE_LEVELS = new int[] {
        H263_PROFILE_0_LEVEL_10,
        H263_PROFILE_0_LEVEL_20,
        H263_PROFILE_0_LEVEL_30,
        H263_PROFILE_0_LEVEL_40,
        H263_PROFILE_0_LEVEL_45,
        MPEG4_SP_LEVEL_0,
        MPEG4_SP_LEVEL_0B,
        MPEG4_SP_LEVEL_1,
        MPEG4_SP_LEVEL_2,
        MPEG4_SP_LEVEL_3,
        MPEG4_SP_LEVEL_4A,
        MPEG4_SP_LEVEL_5,
        H264_PROFILE_0_LEVEL_1,
        H264_PROFILE_0_LEVEL_1B,
        H264_PROFILE_0_LEVEL_1_1,
        H264_PROFILE_0_LEVEL_1_2,
        H264_PROFILE_0_LEVEL_1_3,
        H264_PROFILE_0_LEVEL_2,
        H264_PROFILE_0_LEVEL_2_1,
        H264_PROFILE_0_LEVEL_2_2,
        H264_PROFILE_0_LEVEL_3,
        H264_PROFILE_0_LEVEL_3_1,
        H264_PROFILE_0_LEVEL_3_2,
        H264_PROFILE_0_LEVEL_4,
        H264_PROFILE_0_LEVEL_4_1,
        H264_PROFILE_0_LEVEL_4_2,
        H264_PROFILE_0_LEVEL_5,
        H264_PROFILE_0_LEVEL_5_1,
        UNSUPPORTED_PROFILE_LEVEL
    };

    /**
     *  Audio codec types
     */
    public static final int ACODEC_NO_AUDIO = 0;
    public static final int ACODEC_AMRNB = 1;
    public static final int ACODEC_AAC_LC = 2;
    public static final int ACODEC_AAC_PLUS = 3;
    public static final int ACODEC_ENHANCED_AAC_PLUS = 4;
    public static final int ACODEC_MP3 = 5;
    public static final int ACODEC_EVRC = 6;
    // 7 value is used for PCM
    public static final int ACODEC_AMRWB = 8;
    public static final int ACODEC_OGG = 9;

    /**
     *  The array of supported audio codecs
     */
    private static final int[] SUPPORTED_ACODECS = new int[] {
        ACODEC_AAC_LC,
        ACODEC_AMRNB,
        ACODEC_AMRWB
    };


    /**
     *  Samples per frame for each audio codec
     */
    public static final int SAMPLES_PER_FRAME_AAC = 1024;
    public static final int SAMPLES_PER_FRAME_MP3 = 1152;
    public static final int SAMPLES_PER_FRAME_AMRNB = 160;
    public static final int SAMPLES_PER_FRAME_AMRWB = 320;

    public static final int DEFAULT_SAMPLING_FREQUENCY = 32000;
    public static final int DEFAULT_CHANNEL_COUNT = 2;

    /**
     *  File format types
     */
    public static final int FILE_3GP = 0;
    public static final int FILE_MP4 = 1;
    // 2 is for AMRNB
    public static final int FILE_MP3 = 3;
    // 4 is for PCM
    public static final int FILE_JPEG = 5;
    // 6 is for BMP
    // 7 is for GIF
    public static final int FILE_PNG = 8;
    // 9 is for ARGB8888
    public static final int FILE_M4V = 10;
    public static final int FILE_UNSUPPORTED = 255;

    /**
     * Undefined video codec profiles
     */
    public static final int UNDEFINED_VIDEO_PROFILE = 255;

    /**
     * The array of the supported file formats
     */
    private static final int[] SUPPORTED_VIDEO_FILE_FORMATS = new int[] {
        FILE_3GP,
        FILE_MP4,
        FILE_M4V
    };

    /**
     * The maximum count of audio tracks supported
     */
    public static final int AUDIO_MAX_TRACK_COUNT = 1;

    /** The maximum volume supported (100 means that no amplification is
     * supported, i.e. attenuation only)
     */
    public static final int AUDIO_MAX_VOLUME_PERCENT = 100;

    /**
     * This class cannot be instantiated
     */
    private MediaProperties() {
    }

    /**
     * @return The array of supported aspect ratios
     */
    public static int[] getAllSupportedAspectRatios() {
        return ASPECT_RATIOS;
    }

    /**
     * Get the supported resolutions for the specified aspect ratio.
     *
     * @param aspectRatio The aspect ratio for which the resolutions are
     *        requested
     * @return The array of width and height pairs
     */
    public static Pair<Integer, Integer>[] getSupportedResolutions(int aspectRatio) {
        final Pair<Integer, Integer>[] resolutions;
        switch (aspectRatio) {
            case ASPECT_RATIO_3_2: {
                resolutions = ASPECT_RATIO_3_2_RESOLUTIONS;
                break;
            }

            case ASPECT_RATIO_4_3: {
                resolutions = ASPECT_RATIO_4_3_RESOLUTIONS;
                break;
            }

            case ASPECT_RATIO_5_3: {
                resolutions = ASPECT_RATIO_5_3_RESOLUTIONS;
                break;
            }

            case ASPECT_RATIO_11_9: {
                resolutions = ASPECT_RATIO_11_9_RESOLUTIONS;
                break;
            }

            case ASPECT_RATIO_16_9: {
                resolutions = ASPECT_RATIO_16_9_RESOLUTIONS;
                break;
            }

            default: {
                throw new IllegalArgumentException("Unknown aspect ratio: " + aspectRatio);
            }
        }

        /** Check the platform specific maximum export resolution */
        VideoEditorProfile veProfile = VideoEditorProfile.get();
        if (veProfile == null) {
            throw new RuntimeException("Can't get the video editor profile");
        }
        final int maxWidth = veProfile.maxOutputVideoFrameWidth;
        final int maxHeight = veProfile.maxOutputVideoFrameHeight;
        Pair<Integer, Integer>[] tmpResolutions = new Pair[resolutions.length];
        int numSupportedResolution = 0;
        int i = 0;

        /** Get supported resolution list */
        for (i = 0; i < resolutions.length; i++) {
            if ((resolutions[i].first <= maxWidth) &&
                (resolutions[i].second <= maxHeight)) {
                tmpResolutions[numSupportedResolution] = resolutions[i];
                numSupportedResolution++;
            }
        }
        final Pair<Integer, Integer>[] supportedResolutions =
            new Pair[numSupportedResolution];
        System.arraycopy(tmpResolutions, 0,
            supportedResolutions, 0, numSupportedResolution);

        return supportedResolutions;
    }

    /**
     * @return The array of supported video codecs
     */
    public static int[] getSupportedVideoCodecs() {
        return SUPPORTED_VCODECS;
    }

    /**
     * @return The array of supported audio codecs
     */
    public static int[] getSupportedAudioCodecs() {
        return SUPPORTED_ACODECS;
    }

    /**
     * @return The array of supported file formats
     */
    public static int[] getSupportedVideoFileFormat() {
        return SUPPORTED_VIDEO_FILE_FORMATS;
    }

    /**
     * @return The array of supported video bitrates
     */
    public static int[] getSupportedVideoBitrates() {
        return SUPPORTED_BITRATES;
    }

    /**
     * @return The maximum value for the audio volume
     */
    public static int getSupportedMaxVolume() {
        return MediaProperties.AUDIO_MAX_VOLUME_PERCENT;
    }

    /**
     * @return The maximum number of audio tracks supported
     */
    public static int getSupportedAudioTrackCount() {
        return MediaProperties.AUDIO_MAX_TRACK_COUNT;
    }
}
