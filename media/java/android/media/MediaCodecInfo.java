/*
 * Copyright (C) 2012 The Android Open Source Project
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

/**
 * Provides information about a given media codec available on the device. You can
 * iterate through all codecs available by querying {@link MediaCodecList}. For example,
 * here's how to find an encoder that supports a given MIME type:
 * <pre>
 * private static MediaCodecInfo selectCodec(String mimeType) {
 *     int numCodecs = MediaCodecList.getCodecCount();
 *     for (int i = 0; i &lt; numCodecs; i++) {
 *         MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
 *
 *         if (!codecInfo.isEncoder()) {
 *             continue;
 *         }
 *
 *         String[] types = codecInfo.getSupportedTypes();
 *         for (int j = 0; j &lt; types.length; j++) {
 *             if (types[j].equalsIgnoreCase(mimeType)) {
 *                 return codecInfo;
 *             }
 *         }
 *     }
 *     return null;
 * }</pre>
 *
 */
public final class MediaCodecInfo {
    private int mIndex;

    /* package private */ MediaCodecInfo(int index) {
        mIndex = index;
    }

    /**
     * Retrieve the codec name.
     */
    public final String getName() {
        return MediaCodecList.getCodecName(mIndex);
    }

    /**
     * Query if the codec is an encoder.
     */
    public final boolean isEncoder() {
        return MediaCodecList.isEncoder(mIndex);
    }

    /**
     * Query the media types supported by the codec.
     */
    public final String[] getSupportedTypes() {
        return MediaCodecList.getSupportedTypes(mIndex);
    }

    /**
     * Encapsulates the capabilities of a given codec component.
     * For example, what profile/level combinations it supports and what colorspaces
     * it is capable of providing the decoded data in, as well as some
     * codec-type specific capability flags.
     * <p>You can get an instance for a given {@link MediaCodecInfo} object with
     * {@link MediaCodecInfo#getCapabilitiesForType getCapabilitiesForType()}, passing a MIME type.
     */
    public static final class CodecCapabilities {
        // Enumerates supported profile/level combinations as defined
        // by the type of encoded data. These combinations impose restrictions
        // on video resolution, bitrate... and limit the available encoder tools
        // such as B-frame support, arithmetic coding...
        public CodecProfileLevel[] profileLevels;

        // from OMX_COLOR_FORMATTYPE
        public final static int COLOR_FormatMonochrome              = 1;
        public final static int COLOR_Format8bitRGB332              = 2;
        public final static int COLOR_Format12bitRGB444             = 3;
        public final static int COLOR_Format16bitARGB4444           = 4;
        public final static int COLOR_Format16bitARGB1555           = 5;
        public final static int COLOR_Format16bitRGB565             = 6;
        public final static int COLOR_Format16bitBGR565             = 7;
        public final static int COLOR_Format18bitRGB666             = 8;
        public final static int COLOR_Format18bitARGB1665           = 9;
        public final static int COLOR_Format19bitARGB1666           = 10;
        public final static int COLOR_Format24bitRGB888             = 11;
        public final static int COLOR_Format24bitBGR888             = 12;
        public final static int COLOR_Format24bitARGB1887           = 13;
        public final static int COLOR_Format25bitARGB1888           = 14;
        public final static int COLOR_Format32bitBGRA8888           = 15;
        public final static int COLOR_Format32bitARGB8888           = 16;
        public final static int COLOR_FormatYUV411Planar            = 17;
        public final static int COLOR_FormatYUV411PackedPlanar      = 18;
        public final static int COLOR_FormatYUV420Planar            = 19;
        public final static int COLOR_FormatYUV420PackedPlanar      = 20;
        public final static int COLOR_FormatYUV420SemiPlanar        = 21;
        public final static int COLOR_FormatYUV422Planar            = 22;
        public final static int COLOR_FormatYUV422PackedPlanar      = 23;
        public final static int COLOR_FormatYUV422SemiPlanar        = 24;
        public final static int COLOR_FormatYCbYCr                  = 25;
        public final static int COLOR_FormatYCrYCb                  = 26;
        public final static int COLOR_FormatCbYCrY                  = 27;
        public final static int COLOR_FormatCrYCbY                  = 28;
        public final static int COLOR_FormatYUV444Interleaved       = 29;
        public final static int COLOR_FormatRawBayer8bit            = 30;
        public final static int COLOR_FormatRawBayer10bit           = 31;
        public final static int COLOR_FormatRawBayer8bitcompressed  = 32;
        public final static int COLOR_FormatL2                      = 33;
        public final static int COLOR_FormatL4                      = 34;
        public final static int COLOR_FormatL8                      = 35;
        public final static int COLOR_FormatL16                     = 36;
        public final static int COLOR_FormatL24                     = 37;
        public final static int COLOR_FormatL32                     = 38;
        public final static int COLOR_FormatYUV420PackedSemiPlanar  = 39;
        public final static int COLOR_FormatYUV422PackedSemiPlanar  = 40;
        public final static int COLOR_Format18BitBGR666             = 41;
        public final static int COLOR_Format24BitARGB6666           = 42;
        public final static int COLOR_Format24BitABGR6666           = 43;

        public final static int COLOR_TI_FormatYUV420PackedSemiPlanar = 0x7f000100;
        // COLOR_FormatSurface indicates that the data will be a GraphicBuffer metadata reference.
        // In OMX this is called OMX_COLOR_FormatAndroidOpaque.
        public final static int COLOR_FormatSurface                   = 0x7F000789;
        public final static int COLOR_QCOM_FormatYUV420SemiPlanar     = 0x7fa30c00;

        /**
         * Defined in the OpenMAX IL specs, color format values are drawn from
         * OMX_COLOR_FORMATTYPE.
         */
        public int[] colorFormats;

        private final static int FLAG_SupportsAdaptivePlayback       = (1 << 0);
        private int flags;

        /**
         * <b>video decoder only</b>: codec supports seamless resolution changes.
         */
        public final static String FEATURE_AdaptivePlayback       = "adaptive-playback";

        /**
         * Query codec feature capabilities.
         */
        public final boolean isFeatureSupported(String name) {
            if (name.equals(FEATURE_AdaptivePlayback)) {
                return (flags & FLAG_SupportsAdaptivePlayback) != 0;
            }
            return false;
        }
    };

    /**
     * Encapsulates the profiles available for a codec component.
     * <p>You can get a set of {@link MediaCodecInfo.CodecProfileLevel} objects for a given
     * {@link MediaCodecInfo} object from the
     * {@link MediaCodecInfo.CodecCapabilities#profileLevels} field.
     */
    public static final class CodecProfileLevel {
        // from OMX_VIDEO_AVCPROFILETYPE
        public static final int AVCProfileBaseline = 0x01;
        public static final int AVCProfileMain     = 0x02;
        public static final int AVCProfileExtended = 0x04;
        public static final int AVCProfileHigh     = 0x08;
        public static final int AVCProfileHigh10   = 0x10;
        public static final int AVCProfileHigh422  = 0x20;
        public static final int AVCProfileHigh444  = 0x40;

        // from OMX_VIDEO_AVCLEVELTYPE
        public static final int AVCLevel1       = 0x01;
        public static final int AVCLevel1b      = 0x02;
        public static final int AVCLevel11      = 0x04;
        public static final int AVCLevel12      = 0x08;
        public static final int AVCLevel13      = 0x10;
        public static final int AVCLevel2       = 0x20;
        public static final int AVCLevel21      = 0x40;
        public static final int AVCLevel22      = 0x80;
        public static final int AVCLevel3       = 0x100;
        public static final int AVCLevel31      = 0x200;
        public static final int AVCLevel32      = 0x400;
        public static final int AVCLevel4       = 0x800;
        public static final int AVCLevel41      = 0x1000;
        public static final int AVCLevel42      = 0x2000;
        public static final int AVCLevel5       = 0x4000;
        public static final int AVCLevel51      = 0x8000;

        // from OMX_VIDEO_H263PROFILETYPE
        public static final int H263ProfileBaseline             = 0x01;
        public static final int H263ProfileH320Coding           = 0x02;
        public static final int H263ProfileBackwardCompatible   = 0x04;
        public static final int H263ProfileISWV2                = 0x08;
        public static final int H263ProfileISWV3                = 0x10;
        public static final int H263ProfileHighCompression      = 0x20;
        public static final int H263ProfileInternet             = 0x40;
        public static final int H263ProfileInterlace            = 0x80;
        public static final int H263ProfileHighLatency          = 0x100;

        // from OMX_VIDEO_H263LEVELTYPE
        public static final int H263Level10      = 0x01;
        public static final int H263Level20      = 0x02;
        public static final int H263Level30      = 0x04;
        public static final int H263Level40      = 0x08;
        public static final int H263Level45      = 0x10;
        public static final int H263Level50      = 0x20;
        public static final int H263Level60      = 0x40;
        public static final int H263Level70      = 0x80;

        // from OMX_VIDEO_MPEG4PROFILETYPE
        public static final int MPEG4ProfileSimple              = 0x01;
        public static final int MPEG4ProfileSimpleScalable      = 0x02;
        public static final int MPEG4ProfileCore                = 0x04;
        public static final int MPEG4ProfileMain                = 0x08;
        public static final int MPEG4ProfileNbit                = 0x10;
        public static final int MPEG4ProfileScalableTexture     = 0x20;
        public static final int MPEG4ProfileSimpleFace          = 0x40;
        public static final int MPEG4ProfileSimpleFBA           = 0x80;
        public static final int MPEG4ProfileBasicAnimated       = 0x100;
        public static final int MPEG4ProfileHybrid              = 0x200;
        public static final int MPEG4ProfileAdvancedRealTime    = 0x400;
        public static final int MPEG4ProfileCoreScalable        = 0x800;
        public static final int MPEG4ProfileAdvancedCoding      = 0x1000;
        public static final int MPEG4ProfileAdvancedCore        = 0x2000;
        public static final int MPEG4ProfileAdvancedScalable    = 0x4000;
        public static final int MPEG4ProfileAdvancedSimple      = 0x8000;

        // from OMX_VIDEO_MPEG4LEVELTYPE
        public static final int MPEG4Level0      = 0x01;
        public static final int MPEG4Level0b     = 0x02;
        public static final int MPEG4Level1      = 0x04;
        public static final int MPEG4Level2      = 0x08;
        public static final int MPEG4Level3      = 0x10;
        public static final int MPEG4Level4      = 0x20;
        public static final int MPEG4Level4a     = 0x40;
        public static final int MPEG4Level5      = 0x80;

        // from OMX_AUDIO_AACPROFILETYPE
        public static final int AACObjectMain       = 1;
        public static final int AACObjectLC         = 2;
        public static final int AACObjectSSR        = 3;
        public static final int AACObjectLTP        = 4;
        public static final int AACObjectHE         = 5;
        public static final int AACObjectScalable   = 6;
        public static final int AACObjectERLC       = 17;
        public static final int AACObjectLD         = 23;
        public static final int AACObjectHE_PS      = 29;
        public static final int AACObjectELD        = 39;

        // from OMX_VIDEO_VP8LEVELTYPE
        public static final int VP8Level_Version0 = 0x01;
        public static final int VP8Level_Version1 = 0x02;
        public static final int VP8Level_Version2 = 0x04;
        public static final int VP8Level_Version3 = 0x08;

        // from OMX_VIDEO_VP8PROFILETYPE
        public static final int VP8ProfileMain = 0x01;


        /**
         * Defined in the OpenMAX IL specs, depending on the type of media
         * this can be OMX_VIDEO_AVCPROFILETYPE, OMX_VIDEO_H263PROFILETYPE,
         * OMX_VIDEO_MPEG4PROFILETYPE or OMX_VIDEO_VP8PROFILETYPE.
         */
        public int profile;

        /**
         * Defined in the OpenMAX IL specs, depending on the type of media
         * this can be OMX_VIDEO_AVCLEVELTYPE, OMX_VIDEO_H263LEVELTYPE
         * OMX_VIDEO_MPEG4LEVELTYPE or OMX_VIDEO_VP8LEVELTYPE.
         */
        public int level;
    };

    /**
     * Enumerates the capabilities of the codec component. Since a single
     * component can support data of a variety of types, the type has to be
     * specified to yield a meaningful result.
     * @param type The MIME type to query
     */
    public final CodecCapabilities getCapabilitiesForType(
            String type) {
        return MediaCodecList.getCodecCapabilities(mIndex, type);
    }
}
