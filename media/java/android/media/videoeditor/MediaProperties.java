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
    public static final int VCODEC_H264 = 2;
    public static final int VCODEC_MPEG4 = 3;

    /**
     *  The array of supported video codecs
     */
    private static final int[] SUPPORTED_VCODECS = new int[] {
        VCODEC_H264,
        VCODEC_H263,
        VCODEC_MPEG4,
    };

    /**
     *  The H264 profile, the values are same as the one in OMX_Video.h
     */
    public final class H264Profile {
        public static final int H264ProfileBaseline = 0x01; /**< Baseline profile */
        public static final int H264ProfileMain     = 0x02; /**< Main profile */
        public static final int H264ProfileExtended = 0x04; /**< Extended profile */
        public static final int H264ProfileHigh     = 0x08; /**< High profile */
        public static final int H264ProfileHigh10   = 0x10; /**< High 10 profile */
        public static final int H264ProfileHigh422  = 0x20; /**< High 4:2:2 profile */
        public static final int H264ProfileHigh444  = 0x40; /**< High 4:4:4 profile */
        public static final int H264ProfileUnknown  = 0x7FFFFFFF;
   }
    /**
     *  The H264 level, the values are same as the one in OMX_Video.h
     */
    public final class H264Level {
        public static final int H264Level1   = 0x01; /**< Level 1 */
        public static final int H264Level1b  = 0x02; /**< Level 1b */
        public static final int H264Level11  = 0x04; /**< Level 1.1 */
        public static final int H264Level12  = 0x08; /**< Level 1.2 */
        public static final int H264Level13  = 0x10; /**< Level 1.3 */
        public static final int H264Level2   = 0x20; /**< Level 2 */
        public static final int H264Level21  = 0x40; /**< Level 2.1 */
        public static final int H264Level22  = 0x80; /**< Level 2.2 */
        public static final int H264Level3   = 0x100; /**< Level 3 */
        public static final int H264Level31  = 0x200; /**< Level 3.1 */
        public static final int H264Level32  = 0x400; /**< Level 3.2 */
        public static final int H264Level4   = 0x800; /**< Level 4 */
        public static final int H264Level41  = 0x1000; /**< Level 4.1 */
        public static final int H264Level42  = 0x2000; /**< Level 4.2 */
        public static final int H264Level5   = 0x4000; /**< Level 5 */
        public static final int H264Level51  = 0x8000; /**< Level 5.1 */
        public static final int H264LevelUnknown = 0x7FFFFFFF;
    }
    /**
     *  The H263 profile, the values are same as the one in OMX_Video.h
     */
    public final class H263Profile {
        public static final int H263ProfileBaseline            = 0x01;
        public static final int H263ProfileH320Coding          = 0x02;
        public static final int H263ProfileBackwardCompatible  = 0x04;
        public static final int H263ProfileISWV2               = 0x08;
        public static final int H263ProfileISWV3               = 0x10;
        public static final int H263ProfileHighCompression     = 0x20;
        public static final int H263ProfileInternet            = 0x40;
        public static final int H263ProfileInterlace           = 0x80;
        public static final int H263ProfileHighLatency       = 0x100;
        public static final int H263ProfileUnknown          = 0x7FFFFFFF;
    }
    /**
     *  The H263 level, the values are same as the one in OMX_Video.h
     */
    public final class H263Level {
        public static final int H263Level10  = 0x01;
        public static final int H263Level20  = 0x02;
        public static final int H263Level30  = 0x04;
        public static final int H263Level40  = 0x08;
        public static final int H263Level45  = 0x10;
        public static final int H263Level50  = 0x20;
        public static final int H263Level60  = 0x40;
        public static final int H263Level70  = 0x80;
        public static final int H263LevelUnknown = 0x7FFFFFFF;
    }
    /**
     *  The mpeg4 profile, the values are same as the one in OMX_Video.h
     */
    public final class MPEG4Profile {
        public static final int MPEG4ProfileSimple           = 0x01;
        public static final int MPEG4ProfileSimpleScalable   = 0x02;
        public static final int MPEG4ProfileCore             = 0x04;
        public static final int MPEG4ProfileMain             = 0x08;
        public static final int MPEG4ProfileNbit             = 0x10;
        public static final int MPEG4ProfileScalableTexture  = 0x20;
        public static final int MPEG4ProfileSimpleFace       = 0x40;
        public static final int MPEG4ProfileSimpleFBA        = 0x80;
        public static final int MPEG4ProfileBasicAnimated    = 0x100;
        public static final int MPEG4ProfileHybrid           = 0x200;
        public static final int MPEG4ProfileAdvancedRealTime = 0x400;
        public static final int MPEG4ProfileCoreScalable     = 0x800;
        public static final int MPEG4ProfileAdvancedCoding   = 0x1000;
        public static final int MPEG4ProfileAdvancedCore     = 0x2000;
        public static final int MPEG4ProfileAdvancedScalable = 0x4000;
        public static final int MPEG4ProfileAdvancedSimple   = 0x8000;
        public static final int MPEG4ProfileUnknown          = 0x7FFFFFFF;
    }
    /**
     *  The mpeg4 level, the values are same as the one in OMX_Video.h
     */
    public final class MPEG4Level {
        public static final int MPEG4Level0  = 0x01; /**< Level 0 */
        public static final int MPEG4Level0b = 0x02; /**< Level 0b */
        public static final int MPEG4Level1  = 0x04; /**< Level 1 */
        public static final int MPEG4Level2  = 0x08; /**< Level 2 */
        public static final int MPEG4Level3  = 0x10; /**< Level 3 */
        public static final int MPEG4Level4  = 0x20; /**< Level 4 */
        public static final int MPEG4Level4a = 0x40; /**< Level 4a */
        public static final int MPEG4Level5  = 0x80; /**< Level 5 */
        public static final int MPEG4LevelUnknown = 0x7FFFFFFF;
    }
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
