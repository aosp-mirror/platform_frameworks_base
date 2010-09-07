/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.util.Pair;

/**
 * This class defines all properties of a media file such as supported height, aspect ratio,
 * bitrate for export function.
 * {@hide}
 */
public class MediaProperties {
    // Supported heights
    public static final int HEIGHT_144 = 144;
    public static final int HEIGHT_360 = 360;
    public static final int HEIGHT_480 = 480;
    public static final int HEIGHT_720 = 720;

    // Supported aspect ratios
    public static final int ASPECT_RATIO_UNDEFINED = 0;
    public static final int ASPECT_RATIO_3_2 = 1;
    public static final int ASPECT_RATIO_16_9 = 2;
    public static final int ASPECT_RATIO_4_3 = 3;
    public static final int ASPECT_RATIO_5_3 = 4;
    public static final int ASPECT_RATIO_11_9 = 5;

    // The array of supported aspect ratios
    private static final int[] ASPECT_RATIOS = new int[] {
        ASPECT_RATIO_3_2,
        ASPECT_RATIO_16_9,
        ASPECT_RATIO_4_3,
        ASPECT_RATIO_5_3,
        ASPECT_RATIO_11_9
    };

    // Supported resolutions for specific aspect ratios
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
        new Pair<Integer, Integer>(176, HEIGHT_144)
    };

    @SuppressWarnings({"unchecked"})
    private static final Pair<Integer, Integer>[] ASPECT_RATIO_16_9_RESOLUTIONS =
        new Pair[] {
        new Pair<Integer, Integer>(640, HEIGHT_360),
        new Pair<Integer, Integer>(854, HEIGHT_480),
        new Pair<Integer, Integer>(1280, HEIGHT_720),
    };


    // Bitrate values (in bits per second)
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

    // The array of supported bitrates
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
        BITRATE_800K
    };

    // Video codec types
    public static final int VCODEC_H264BP = 1;
    public static final int VCODEC_H264MP = 2;
    public static final int VCODEC_H263 = 3;
    public static final int VCODEC_MPEG4 = 4;

    // The array of supported video codecs
    private static final int[] SUPPORTED_VCODECS = new int[] {
        VCODEC_H264BP,
        VCODEC_H263,
        VCODEC_MPEG4,
    };

    // Audio codec types
    public static final int ACODEC_AAC_LC = 1;
    public static final int ACODEC_AMRNB = 2;
    public static final int ACODEC_AMRWB = 3;
    public static final int ACODEC_MP3 = 4;
    public static final int ACODEC_OGG = 5;

    // The array of supported video codecs
    private static final int[] SUPPORTED_ACODECS = new int[] {
        ACODEC_AAC_LC,
        ACODEC_AMRNB,
        ACODEC_AMRWB
    };

    // File format types
    public static final int FILE_UNSUPPORTED = 0;
    public static final int FILE_3GP = 1;
    public static final int FILE_MP4 = 2;
    public static final int FILE_JPEG = 3;
    public static final int FILE_PNG = 4;

    // The array of the supported file formats
    private static final int[] SUPPORTED_VIDEO_FILE_FORMATS = new int[] {
        FILE_3GP,
        FILE_MP4
    };

    // The maximum count of audio tracks supported
    public static final int AUDIO_MAX_TRACK_COUNT = 1;

    // The maximum volume supported (100 means that no amplification is
    // supported, i.e. attenuation only)
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
     * @param aspectRatio The aspect ratio for which the resolutions are requested
     *
     * @return The array of width and height pairs
     */
    public static Pair<Integer, Integer>[] getSupportedResolutions(int aspectRatio) {
        final Pair<Integer, Integer>[] resolutions;
        switch(aspectRatio) {
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

        return resolutions;
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
