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

import static android.media.Utils.intersectSortedDistinctRanges;
import static android.media.Utils.sortDistinctRanges;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.sysprop.MediaProperties;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

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
    private static final String TAG = "MediaCodecInfo";

    private static final int FLAG_IS_ENCODER = (1 << 0);
    private static final int FLAG_IS_VENDOR = (1 << 1);
    private static final int FLAG_IS_SOFTWARE_ONLY = (1 << 2);
    private static final int FLAG_IS_HARDWARE_ACCELERATED = (1 << 3);

    private int mFlags;
    private String mName;
    private String mCanonicalName;
    private Map<String, CodecCapabilities> mCaps;

    /* package private */ MediaCodecInfo(
            String name, String canonicalName, int flags, CodecCapabilities[] caps) {
        mName = name;
        mCanonicalName = canonicalName;
        mFlags = flags;
        mCaps = new HashMap<String, CodecCapabilities>();

        for (CodecCapabilities c: caps) {
            mCaps.put(c.getMimeType(), c);
        }
    }

    /**
     * Retrieve the codec name.
     *
     * <strong>Note:</strong> Implementations may provide multiple aliases (codec
     * names) for the same underlying codec, any of which can be used to instantiate the same
     * underlying codec in {@link MediaCodec#createByCodecName}.
     *
     * Applications targeting SDK < {@link android.os.Build.VERSION_CODES#Q}, cannot determine if
     * the multiple codec names listed in MediaCodecList are in-fact for the same codec.
     */
    @NonNull
    public final String getName() {
        return mName;
    }

    /**
     * Retrieve the underlying codec name.
     *
     * Device implementations may provide multiple aliases (codec names) for the same underlying
     * codec to maintain backward app compatibility. This method returns the name of the underlying
     * codec name, which must not be another alias. For non-aliases this is always the name of the
     * codec.
     */
    @NonNull
    public final String getCanonicalName() {
        return mCanonicalName;
    }

    /**
     * Query if the codec is an alias for another underlying codec.
     */
    public final boolean isAlias() {
        return !mName.equals(mCanonicalName);
    }

    /**
     * Query if the codec is an encoder.
     */
    public final boolean isEncoder() {
        return (mFlags & FLAG_IS_ENCODER) != 0;
    }

    /**
     * Query if the codec is provided by the Android platform (false) or the device manufacturer
     * (true).
     */
    public final boolean isVendor() {
        return (mFlags & FLAG_IS_VENDOR) != 0;
    }

    /**
     * Query if the codec is software only. Software-only codecs are more secure as they run in
     * a tighter security sandbox. On the other hand, software-only codecs do not provide any
     * performance guarantees.
     */
    public final boolean isSoftwareOnly() {
        return (mFlags & FLAG_IS_SOFTWARE_ONLY) != 0;
    }

    /**
     * Query if the codec is hardware accelerated. This attribute is provided by the device
     * manufacturer. Note that it cannot be tested for correctness.
     */
    public final boolean isHardwareAccelerated() {
        return (mFlags & FLAG_IS_HARDWARE_ACCELERATED) != 0;
    }

    /**
     * Query the media types supported by the codec.
     */
    public final String[] getSupportedTypes() {
        Set<String> typeSet = mCaps.keySet();
        String[] types = typeSet.toArray(new String[typeSet.size()]);
        Arrays.sort(types);
        return types;
    }

    private static int checkPowerOfTwo(int value, String message) {
        if ((value & (value - 1)) != 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static class Feature {
        public String mName;
        public int mValue;
        public boolean mDefault;
        public boolean mInternal;
        public Feature(String name, int value, boolean def) {
            this(name, value, def, false /* internal */);
        }
        public Feature(String name, int value, boolean def, boolean internal) {
            mName = name;
            mValue = value;
            mDefault = def;
            mInternal = internal;
        }
    }

    // COMMON CONSTANTS
    private static final Range<Integer> POSITIVE_INTEGERS =
            Range.create(1, Integer.MAX_VALUE);
    private static final Range<Long> POSITIVE_LONGS =
            Range.create(1L, Long.MAX_VALUE);
    private static final Range<Rational> POSITIVE_RATIONALS =
            Range.create(new Rational(1, Integer.MAX_VALUE),
                         new Rational(Integer.MAX_VALUE, 1));
    private static final Range<Integer> FRAME_RATE_RANGE = Range.create(0, 960);
    private static final Range<Integer> BITRATE_RANGE = Range.create(0, 500000000);
    private static final int DEFAULT_MAX_SUPPORTED_INSTANCES = 32;
    private static final int MAX_SUPPORTED_INSTANCES_LIMIT = 256;

    private static final class LazyHolder {
        private static final Range<Integer> SIZE_RANGE = Process.is64Bit()
                ? Range.create(1, 32768)
                : Range.create(1, MediaProperties.resolution_limit_32bit().orElse(4096));
    }
    private static Range<Integer> getSizeRange() {
        return LazyHolder.SIZE_RANGE;
    }

    // found stuff that is not supported by framework (=> this should not happen)
    private static final int ERROR_UNRECOGNIZED   = (1 << 0);
    // found profile/level for which we don't have capability estimates
    private static final int ERROR_UNSUPPORTED    = (1 << 1);
    // have not found any profile/level for which we don't have capability estimate
    private static final int ERROR_NONE_SUPPORTED = (1 << 2);


    /**
     * Encapsulates the capabilities of a given codec component.
     * For example, what profile/level combinations it supports and what colorspaces
     * it is capable of providing the decoded data in, as well as some
     * codec-type specific capability flags.
     * <p>You can get an instance for a given {@link MediaCodecInfo} object with
     * {@link MediaCodecInfo#getCapabilitiesForType getCapabilitiesForType()}, passing a MIME type.
     */
    public static final class CodecCapabilities {
        public CodecCapabilities() {
        }

        // CLASSIFICATION
        private String mMime;
        private int mMaxSupportedInstances;

        // LEGACY FIELDS

        // Enumerates supported profile/level combinations as defined
        // by the type of encoded data. These combinations impose restrictions
        // on video resolution, bitrate... and limit the available encoder tools
        // such as B-frame support, arithmetic coding...
        public CodecProfileLevel[] profileLevels;  // NOTE this array is modifiable by user

        // from MediaCodecConstants
        /** @deprecated Use {@link #COLOR_Format24bitBGR888}. */
        public static final int COLOR_FormatMonochrome              = 1;
        /** @deprecated Use {@link #COLOR_Format24bitBGR888}. */
        public static final int COLOR_Format8bitRGB332              = 2;
        /** @deprecated Use {@link #COLOR_Format24bitBGR888}. */
        public static final int COLOR_Format12bitRGB444             = 3;
        /** @deprecated Use {@link #COLOR_Format32bitABGR8888}. */
        public static final int COLOR_Format16bitARGB4444           = 4;
        /** @deprecated Use {@link #COLOR_Format32bitABGR8888}. */
        public static final int COLOR_Format16bitARGB1555           = 5;

        /**
         * 16 bits per pixel RGB color format, with 5-bit red & blue and 6-bit green component.
         * <p>
         * Using 16-bit little-endian representation, colors stored as Red 15:11, Green 10:5, Blue 4:0.
         * <pre>
         *            byte                   byte
         *  <--------- i --------> | <------ i + 1 ------>
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |     BLUE     |      GREEN      |     RED      |
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         *  0           4  5     7   0     2  3           7
         * bit
         * </pre>
         *
         * This format corresponds to {@link android.graphics.PixelFormat#RGB_565} and
         * {@link android.graphics.ImageFormat#RGB_565}.
         */
        public static final int COLOR_Format16bitRGB565             = 6;
        /** @deprecated Use {@link #COLOR_Format16bitRGB565}. */
        public static final int COLOR_Format16bitBGR565             = 7;
        /** @deprecated Use {@link #COLOR_Format24bitBGR888}. */
        public static final int COLOR_Format18bitRGB666             = 8;
        /** @deprecated Use {@link #COLOR_Format32bitABGR8888}. */
        public static final int COLOR_Format18bitARGB1665           = 9;
        /** @deprecated Use {@link #COLOR_Format32bitABGR8888}. */
        public static final int COLOR_Format19bitARGB1666           = 10;

        /** @deprecated Use {@link #COLOR_Format24bitBGR888} or {@link #COLOR_FormatRGBFlexible}. */
        public static final int COLOR_Format24bitRGB888             = 11;

        /**
         * 24 bits per pixel RGB color format, with 8-bit red, green & blue components.
         * <p>
         * Using 24-bit little-endian representation, colors stored as Red 7:0, Green 15:8, Blue 23:16.
         * <pre>
         *         byte              byte             byte
         *  <------ i -----> | <---- i+1 ----> | <---- i+2 ----->
         * +-----------------+-----------------+-----------------+
         * |       RED       |      GREEN      |       BLUE      |
         * +-----------------+-----------------+-----------------+
         * </pre>
         *
         * This format corresponds to {@link android.graphics.PixelFormat#RGB_888}, and can also be
         * represented as a flexible format by {@link #COLOR_FormatRGBFlexible}.
         */
        public static final int COLOR_Format24bitBGR888             = 12;
        /** @deprecated Use {@link #COLOR_Format32bitABGR8888}. */
        public static final int COLOR_Format24bitARGB1887           = 13;
        /** @deprecated Use {@link #COLOR_Format32bitABGR8888}. */
        public static final int COLOR_Format25bitARGB1888           = 14;

        /**
         * @deprecated Use {@link #COLOR_Format32bitABGR8888} Or {@link #COLOR_FormatRGBAFlexible}.
         */
        public static final int COLOR_Format32bitBGRA8888           = 15;
        /**
         * @deprecated Use {@link #COLOR_Format32bitABGR8888} Or {@link #COLOR_FormatRGBAFlexible}.
         */
        public static final int COLOR_Format32bitARGB8888           = 16;
        /** @deprecated Use {@link #COLOR_FormatYUV420Flexible}. */
        public static final int COLOR_FormatYUV411Planar            = 17;
        /** @deprecated Use {@link #COLOR_FormatYUV420Flexible}. */
        public static final int COLOR_FormatYUV411PackedPlanar      = 18;
        /** @deprecated Use {@link #COLOR_FormatYUV420Flexible}. */
        public static final int COLOR_FormatYUV420Planar            = 19;
        /** @deprecated Use {@link #COLOR_FormatYUV420Flexible}. */
        public static final int COLOR_FormatYUV420PackedPlanar      = 20;
        /** @deprecated Use {@link #COLOR_FormatYUV420Flexible}. */
        public static final int COLOR_FormatYUV420SemiPlanar        = 21;

        /** @deprecated Use {@link #COLOR_FormatYUV422Flexible}. */
        public static final int COLOR_FormatYUV422Planar            = 22;
        /** @deprecated Use {@link #COLOR_FormatYUV422Flexible}. */
        public static final int COLOR_FormatYUV422PackedPlanar      = 23;
        /** @deprecated Use {@link #COLOR_FormatYUV422Flexible}. */
        public static final int COLOR_FormatYUV422SemiPlanar        = 24;

        /** @deprecated Use {@link #COLOR_FormatYUV422Flexible}. */
        public static final int COLOR_FormatYCbYCr                  = 25;
        /** @deprecated Use {@link #COLOR_FormatYUV422Flexible}. */
        public static final int COLOR_FormatYCrYCb                  = 26;
        /** @deprecated Use {@link #COLOR_FormatYUV422Flexible}. */
        public static final int COLOR_FormatCbYCrY                  = 27;
        /** @deprecated Use {@link #COLOR_FormatYUV422Flexible}. */
        public static final int COLOR_FormatCrYCbY                  = 28;

        /** @deprecated Use {@link #COLOR_FormatYUV444Flexible}. */
        public static final int COLOR_FormatYUV444Interleaved       = 29;

        /**
         * SMIA 8-bit Bayer format.
         * Each byte represents the top 8-bits of a 10-bit signal.
         */
        public static final int COLOR_FormatRawBayer8bit            = 30;
        /**
         * SMIA 10-bit Bayer format.
         */
        public static final int COLOR_FormatRawBayer10bit           = 31;

        /**
         * SMIA 8-bit compressed Bayer format.
         * Each byte represents a sample from the 10-bit signal that is compressed into 8-bits
         * using DPCM/PCM compression, as defined by the SMIA Functional Specification.
         */
        public static final int COLOR_FormatRawBayer8bitcompressed  = 32;

        /** @deprecated Use {@link #COLOR_FormatL8}. */
        public static final int COLOR_FormatL2                      = 33;
        /** @deprecated Use {@link #COLOR_FormatL8}. */
        public static final int COLOR_FormatL4                      = 34;

        /**
         * 8 bits per pixel Y color format.
         * <p>
         * Each byte contains a single pixel.
         * This format corresponds to {@link android.graphics.PixelFormat#L_8}.
         */
        public static final int COLOR_FormatL8                      = 35;

        /**
         * 16 bits per pixel, little-endian Y color format.
         * <p>
         * <pre>
         *            byte                   byte
         *  <--------- i --------> | <------ i + 1 ------>
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                       Y                       |
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         *  0                    7   0                    7
         * bit
         * </pre>
         */
        public static final int COLOR_FormatL16                     = 36;
        /** @deprecated Use {@link #COLOR_FormatL16}. */
        public static final int COLOR_FormatL24                     = 37;

        /**
         * 32 bits per pixel, little-endian Y color format.
         * <p>
         * <pre>
         *         byte              byte             byte              byte
         *  <------ i -----> | <---- i+1 ----> | <---- i+2 ----> | <---- i+3 ----->
         * +-----------------+-----------------+-----------------+-----------------+
         * |                                   Y                                   |
         * +-----------------+-----------------+-----------------+-----------------+
         *  0               7 0               7 0               7 0               7
         * bit
         * </pre>
         *
         * @deprecated Use {@link #COLOR_FormatL16}.
         */
        public static final int COLOR_FormatL32                     = 38;

        /** @deprecated Use {@link #COLOR_FormatYUV420Flexible}. */
        public static final int COLOR_FormatYUV420PackedSemiPlanar  = 39;
        /** @deprecated Use {@link #COLOR_FormatYUV422Flexible}. */
        public static final int COLOR_FormatYUV422PackedSemiPlanar  = 40;

        /** @deprecated Use {@link #COLOR_Format24bitBGR888}. */
        public static final int COLOR_Format18BitBGR666             = 41;

        /** @deprecated Use {@link #COLOR_Format32bitABGR8888}. */
        public static final int COLOR_Format24BitARGB6666           = 42;
        /** @deprecated Use {@link #COLOR_Format32bitABGR8888}. */
        public static final int COLOR_Format24BitABGR6666           = 43;

        /**
         * P010 is 10-bit-per component 4:2:0 YCbCr semiplanar format.
         * <p>
         * This format uses 24 allocated bits per pixel with 15 bits of
         * data per pixel. Chroma planes are subsampled by 2 both
         * horizontally and vertically. Each chroma and luma component
         * has 16 allocated bits in little-endian configuration with 10
         * MSB of actual data.
         *
         * <pre>
         *            byte                   byte
         *  <--------- i --------> | <------ i + 1 ------>
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |     UNUSED      |      Y/Cb/Cr                |
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         *  0               5 6   7 0                    7
         * bit
         * </pre>
         *
         * Use this format with {@link Image}. This format corresponds
         * to {@link android.graphics.ImageFormat#YCBCR_P010}.
         * <p>
         */
        @SuppressLint("AllUpper")
        public static final int COLOR_FormatYUVP010                 = 54;

        /** @deprecated Use {@link #COLOR_FormatYUV420Flexible}. */
        public static final int COLOR_TI_FormatYUV420PackedSemiPlanar = 0x7f000100;
        // COLOR_FormatSurface indicates that the data will be a GraphicBuffer metadata reference.
        // Note: in OMX this is called OMX_COLOR_FormatAndroidOpaque.
        public static final int COLOR_FormatSurface                   = 0x7F000789;

        /**
         * 64 bits per pixel RGBA color format, with 16-bit signed
         * floating point red, green, blue, and alpha components.
         * <p>
         *
         * <pre>
         *         byte              byte             byte              byte
         *  <-- i -->|<- i+1 ->|<- i+2 ->|<- i+3 ->|<- i+4 ->|<- i+5 ->|<- i+6 ->|<- i+7 ->
         * +---------+---------+-------------------+---------+---------+---------+---------+
         * |        RED        |       GREEN       |       BLUE        |       ALPHA       |
         * +---------+---------+-------------------+---------+---------+---------+---------+
         *  0       7 0       7 0       7 0       7 0       7 0       7 0       7 0       7
         * </pre>
         *
         * This corresponds to {@link android.graphics.PixelFormat#RGBA_F16}.
         */
        @SuppressLint("AllUpper")
        public static final int COLOR_Format64bitABGRFloat            = 0x7F000F16;

        /**
         * 32 bits per pixel RGBA color format, with 8-bit red, green, blue, and alpha components.
         * <p>
         * Using 32-bit little-endian representation, colors stored as Red 7:0, Green 15:8,
         * Blue 23:16, and Alpha 31:24.
         * <pre>
         *         byte              byte             byte              byte
         *  <------ i -----> | <---- i+1 ----> | <---- i+2 ----> | <---- i+3 ----->
         * +-----------------+-----------------+-----------------+-----------------+
         * |       RED       |      GREEN      |       BLUE      |      ALPHA      |
         * +-----------------+-----------------+-----------------+-----------------+
         * </pre>
         *
         * This corresponds to {@link android.graphics.PixelFormat#RGBA_8888}.
         */
        public static final int COLOR_Format32bitABGR8888             = 0x7F00A000;

        /**
         * 32 bits per pixel RGBA color format, with 10-bit red, green,
         * blue, and 2-bit alpha components.
         * <p>
         * Using 32-bit little-endian representation, colors stored as
         * Red 9:0, Green 19:10, Blue 29:20, and Alpha 31:30.
         * <pre>
         *         byte              byte             byte              byte
         *  <------ i -----> | <---- i+1 ----> | <---- i+2 ----> | <---- i+3 ----->
         * +-----------------+---+-------------+-------+---------+-----------+-----+
         * |       RED           |      GREEN          |       BLUE          |ALPHA|
         * +-----------------+---+-------------+-------+---------+-----------+-----+
         *  0               7 0 1 2           7 0     3 4       7 0         5 6   7
         * </pre>
         *
         * This corresponds to {@link android.graphics.PixelFormat#RGBA_1010102}.
         */
        @SuppressLint("AllUpper")
        public static final int COLOR_Format32bitABGR2101010          = 0x7F00AAA2;

        /**
         * Flexible 12 bits per pixel, subsampled YUV color format with 8-bit chroma and luma
         * components.
         * <p>
         * Chroma planes are subsampled by 2 both horizontally and vertically.
         * Use this format with {@link Image}.
         * This format corresponds to {@link android.graphics.ImageFormat#YUV_420_888},
         * and can represent the {@link #COLOR_FormatYUV411Planar},
         * {@link #COLOR_FormatYUV411PackedPlanar}, {@link #COLOR_FormatYUV420Planar},
         * {@link #COLOR_FormatYUV420PackedPlanar}, {@link #COLOR_FormatYUV420SemiPlanar}
         * and {@link #COLOR_FormatYUV420PackedSemiPlanar} formats.
         *
         * @see Image#getFormat
         */
        public static final int COLOR_FormatYUV420Flexible            = 0x7F420888;

        /**
         * Flexible 16 bits per pixel, subsampled YUV color format with 8-bit chroma and luma
         * components.
         * <p>
         * Chroma planes are horizontally subsampled by 2. Use this format with {@link Image}.
         * This format corresponds to {@link android.graphics.ImageFormat#YUV_422_888},
         * and can represent the {@link #COLOR_FormatYCbYCr}, {@link #COLOR_FormatYCrYCb},
         * {@link #COLOR_FormatCbYCrY}, {@link #COLOR_FormatCrYCbY},
         * {@link #COLOR_FormatYUV422Planar}, {@link #COLOR_FormatYUV422PackedPlanar},
         * {@link #COLOR_FormatYUV422SemiPlanar} and {@link #COLOR_FormatYUV422PackedSemiPlanar}
         * formats.
         *
         * @see Image#getFormat
         */
        public static final int COLOR_FormatYUV422Flexible            = 0x7F422888;

        /**
         * Flexible 24 bits per pixel YUV color format with 8-bit chroma and luma
         * components.
         * <p>
         * Chroma planes are not subsampled. Use this format with {@link Image}.
         * This format corresponds to {@link android.graphics.ImageFormat#YUV_444_888},
         * and can represent the {@link #COLOR_FormatYUV444Interleaved} format.
         * @see Image#getFormat
         */
        public static final int COLOR_FormatYUV444Flexible            = 0x7F444888;

        /**
         * Flexible 24 bits per pixel RGB color format with 8-bit red, green and blue
         * components.
         * <p>
         * Use this format with {@link Image}. This format corresponds to
         * {@link android.graphics.ImageFormat#FLEX_RGB_888}, and can represent
         * {@link #COLOR_Format24bitBGR888} and {@link #COLOR_Format24bitRGB888} formats.
         * @see Image#getFormat()
         */
        public static final int COLOR_FormatRGBFlexible               = 0x7F36B888;

        /**
         * Flexible 32 bits per pixel RGBA color format with 8-bit red, green, blue, and alpha
         * components.
         * <p>
         * Use this format with {@link Image}. This format corresponds to
         * {@link android.graphics.ImageFormat#FLEX_RGBA_8888}, and can represent
         * {@link #COLOR_Format32bitBGRA8888}, {@link #COLOR_Format32bitABGR8888} and
         * {@link #COLOR_Format32bitARGB8888} formats.
         *
         * @see Image#getFormat()
         */
        public static final int COLOR_FormatRGBAFlexible              = 0x7F36A888;

        /** @deprecated Use {@link #COLOR_FormatYUV420Flexible}. */
        public static final int COLOR_QCOM_FormatYUV420SemiPlanar     = 0x7fa30c00;

        /**
         * The color format for the media. This is one of the color constants defined in this class.
         */
        public int[] colorFormats; // NOTE this array is modifiable by user

        // FEATURES

        private int mFlagsSupported;
        private int mFlagsRequired;
        private int mFlagsVerified;

        /**
         * <b>video decoder only</b>: codec supports seamless resolution changes.
         */
        public static final String FEATURE_AdaptivePlayback       = "adaptive-playback";

        /**
         * <b>video decoder only</b>: codec supports secure decryption.
         */
        public static final String FEATURE_SecurePlayback         = "secure-playback";

        /**
         * <b>video or audio decoder only</b>: codec supports tunneled playback.
         */
        public static final String FEATURE_TunneledPlayback       = "tunneled-playback";

        /**
         * If true, the timestamp of each output buffer is derived from the timestamp of the input
         * buffer that produced the output. If false, the timestamp of each output buffer is
         * derived from the timestamp of the first input buffer.
         */
        public static final String FEATURE_DynamicTimestamp = "dynamic-timestamp";

        /**
         * <b>decoder only</b>If true, the codec supports partial (including multiple) access units
         * per input buffer.
         */
        public static final String FEATURE_FrameParsing = "frame-parsing";

        /**
         * If true, the codec supports multiple access units (for decoding, or to output for
         * encoders). If false, the codec only supports single access units. Producing multiple
         * access units for output is an optional feature.
         */
        public static final String FEATURE_MultipleFrames = "multiple-frames";

        /**
         * <b>video decoder only</b>: codec supports queuing partial frames.
         */
        public static final String FEATURE_PartialFrame = "partial-frame";

        /**
         * <b>video encoder only</b>: codec supports intra refresh.
         */
        public static final String FEATURE_IntraRefresh = "intra-refresh";

        /**
         * <b>decoder only</b>: codec supports low latency decoding.
         * If supported, clients can enable the low latency mode for the decoder.
         * When the mode is enabled, the decoder doesn't hold input and output data more than
         * required by the codec standards.
         */
        public static final String FEATURE_LowLatency = "low-latency";

        /**
         * Do not include in REGULAR_CODECS list in MediaCodecList.
         */
        private static final String FEATURE_SpecialCodec = "special-codec";

        /**
         * <b>video encoder only</b>: codec supports quantization parameter bounds.
         * @see MediaFormat#KEY_VIDEO_QP_MAX
         * @see MediaFormat#KEY_VIDEO_QP_MIN
         */
        @SuppressLint("AllUpper")
        public static final String FEATURE_QpBounds = "qp-bounds";

        /**
         * <b>video encoder only</b>: codec supports exporting encoding statistics.
         * Encoders with this feature can provide the App clients with the encoding statistics
         * information about the frame.
         * The scope of encoding statistics is controlled by
         * {@link MediaFormat#KEY_VIDEO_ENCODING_STATISTICS_LEVEL}.
         *
         * @see MediaFormat#KEY_VIDEO_ENCODING_STATISTICS_LEVEL
         */
        @SuppressLint("AllUpper") // for consistency with other FEATURE_* constants
        public static final String FEATURE_EncodingStatistics = "encoding-statistics";

        /**
         * <b>video encoder only</b>: codec supports HDR editing.
         * <p>
         * HDR editing support means that the codec accepts 10-bit HDR
         * input surface, and it is capable of generating any HDR
         * metadata required from both YUV and RGB input when the
         * metadata is not present. This feature is only meaningful when
         * using an HDR capable profile (and 10-bit HDR input).
         * <p>
         * This feature implies that the codec is capable of encoding at
         * least one HDR format, and that it supports RGBA_1010102 as
         * well as P010, and optionally RGBA_FP16 input formats, and
         * that the encoder can generate HDR metadata for all supported
         * HDR input formats.
         */
        @SuppressLint("AllUpper")
        public static final String FEATURE_HdrEditing = "hdr-editing";

        /**
         * Query codec feature capabilities.
         * <p>
         * These features are supported to be used by the codec.  These
         * include optional features that can be turned on, as well as
         * features that are always on.
         */
        public final boolean isFeatureSupported(String name) {
            return checkFeature(name, mFlagsSupported);
        }

        /**
         * Query codec feature requirements.
         * <p>
         * These features are required to be used by the codec, and as such,
         * they are always turned on.
         */
        public final boolean isFeatureRequired(String name) {
            return checkFeature(name, mFlagsRequired);
        }

        private static final Feature[] decoderFeatures = {
            new Feature(FEATURE_AdaptivePlayback, (1 << 0), true),
            new Feature(FEATURE_SecurePlayback,   (1 << 1), false),
            new Feature(FEATURE_TunneledPlayback, (1 << 2), false),
            new Feature(FEATURE_PartialFrame,     (1 << 3), false),
            new Feature(FEATURE_FrameParsing,     (1 << 4), false),
            new Feature(FEATURE_MultipleFrames,   (1 << 5), false),
            new Feature(FEATURE_DynamicTimestamp, (1 << 6), false),
            new Feature(FEATURE_LowLatency,       (1 << 7), true),
            // feature to exclude codec from REGULAR codec list
            new Feature(FEATURE_SpecialCodec,     (1 << 30), false, true),
        };

        private static final Feature[] encoderFeatures = {
            new Feature(FEATURE_IntraRefresh, (1 << 0), false),
            new Feature(FEATURE_MultipleFrames, (1 << 1), false),
            new Feature(FEATURE_DynamicTimestamp, (1 << 2), false),
            new Feature(FEATURE_QpBounds, (1 << 3), false),
            new Feature(FEATURE_EncodingStatistics, (1 << 4), false),
            new Feature(FEATURE_HdrEditing, (1 << 5), false),
            // feature to exclude codec from REGULAR codec list
            new Feature(FEATURE_SpecialCodec,     (1 << 30), false, true),
        };

        /** @hide */
        public String[] validFeatures() {
            Feature[] features = getValidFeatures();
            String[] res = new String[features.length];
            for (int i = 0; i < res.length; i++) {
                if (!features[i].mInternal) {
                    res[i] = features[i].mName;
                }
            }
            return res;
        }

        private Feature[] getValidFeatures() {
            if (!isEncoder()) {
                return decoderFeatures;
            }
            return encoderFeatures;
        }

        private boolean checkFeature(String name, int flags) {
            for (Feature feat: getValidFeatures()) {
                if (feat.mName.equals(name)) {
                    return (flags & feat.mValue) != 0;
                }
            }
            return false;
        }

        /** @hide */
        public boolean isRegular() {
            // regular codecs only require default features
            for (Feature feat: getValidFeatures()) {
                if (!feat.mDefault && isFeatureRequired(feat.mName)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Query whether codec supports a given {@link MediaFormat}.
         *
         * <p class=note>
         * <strong>Note:</strong> On {@link android.os.Build.VERSION_CODES#LOLLIPOP},
         * {@code format} must not contain a {@linkplain MediaFormat#KEY_FRAME_RATE
         * frame rate}. Use
         * <code class=prettyprint>format.setString(MediaFormat.KEY_FRAME_RATE, null)</code>
         * to clear any existing frame rate setting in the format.
         * <p>
         *
         * The following table summarizes the format keys considered by this method.
         * This is especially important to consider when targeting a higher SDK version than the
         * minimum SDK version, as this method will disregard some keys on devices below the target
         * SDK version.
         *
         * <table style="width: 0%">
         *  <thead>
         *   <tr>
         *    <th rowspan=3>OS Version(s)</th>
         *    <td colspan=3>{@code MediaFormat} keys considered for</th>
         *   </tr><tr>
         *    <th>Audio Codecs</th>
         *    <th>Video Codecs</th>
         *    <th>Encoders</th>
         *   </tr>
         *  </thead>
         *  <tbody>
         *   <tr>
         *    <td>{@link android.os.Build.VERSION_CODES#LOLLIPOP}</td>
         *    <td rowspan=3>{@link MediaFormat#KEY_MIME}<sup>*</sup>,<br>
         *        {@link MediaFormat#KEY_SAMPLE_RATE},<br>
         *        {@link MediaFormat#KEY_CHANNEL_COUNT},</td>
         *    <td>{@link MediaFormat#KEY_MIME}<sup>*</sup>,<br>
         *        {@link CodecCapabilities#FEATURE_AdaptivePlayback}<sup>D</sup>,<br>
         *        {@link CodecCapabilities#FEATURE_SecurePlayback}<sup>D</sup>,<br>
         *        {@link CodecCapabilities#FEATURE_TunneledPlayback}<sup>D</sup>,<br>
         *        {@link MediaFormat#KEY_WIDTH},<br>
         *        {@link MediaFormat#KEY_HEIGHT},<br>
         *        <strong>no</strong> {@code KEY_FRAME_RATE}</td>
         *    <td rowspan=10>as to the left, plus<br>
         *        {@link MediaFormat#KEY_BITRATE_MODE},<br>
         *        {@link MediaFormat#KEY_PROFILE}
         *        (and/or {@link MediaFormat#KEY_AAC_PROFILE}<sup>~</sup>),<br>
         *        <!-- {link MediaFormat#KEY_QUALITY},<br> -->
         *        {@link MediaFormat#KEY_COMPLEXITY}
         *        (and/or {@link MediaFormat#KEY_FLAC_COMPRESSION_LEVEL}<sup>~</sup>)</td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1}</td>
         *    <td rowspan=2>as above, plus<br>
         *        {@link MediaFormat#KEY_FRAME_RATE}</td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#M}</td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#N}</td>
         *    <td rowspan=2>as above, plus<br>
         *        {@link MediaFormat#KEY_PROFILE},<br>
         *        <!-- {link MediaFormat#KEY_MAX_BIT_RATE},<br> -->
         *        {@link MediaFormat#KEY_BIT_RATE}</td>
         *    <td rowspan=2>as above, plus<br>
         *        {@link MediaFormat#KEY_PROFILE},<br>
         *        {@link MediaFormat#KEY_LEVEL}<sup>+</sup>,<br>
         *        <!-- {link MediaFormat#KEY_MAX_BIT_RATE},<br> -->
         *        {@link MediaFormat#KEY_BIT_RATE},<br>
         *        {@link CodecCapabilities#FEATURE_IntraRefresh}<sup>E</sup></td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#N_MR1}</td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#O}</td>
         *    <td rowspan=3 colspan=2>as above, plus<br>
         *        {@link CodecCapabilities#FEATURE_PartialFrame}<sup>D</sup></td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#O_MR1}</td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#P}</td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#Q}</td>
         *    <td colspan=2>as above, plus<br>
         *        {@link CodecCapabilities#FEATURE_FrameParsing}<sup>D</sup>,<br>
         *        {@link CodecCapabilities#FEATURE_MultipleFrames},<br>
         *        {@link CodecCapabilities#FEATURE_DynamicTimestamp}</td>
         *   </tr><tr>
         *    <td>{@link android.os.Build.VERSION_CODES#R}</td>
         *    <td colspan=2>as above, plus<br>
         *        {@link CodecCapabilities#FEATURE_LowLatency}<sup>D</sup></td>
         *   </tr>
         *   <tr>
         *    <td colspan=4>
         *     <p class=note><strong>Notes:</strong><br>
         *      *: must be specified; otherwise, method returns {@code false}.<br>
         *      +: method does not verify that the format parameters are supported
         *      by the specified level.<br>
         *      D: decoders only<br>
         *      E: encoders only<br>
         *      ~: if both keys are provided values must match
         *    </td>
         *   </tr>
         *  </tbody>
         * </table>
         *
         * @param format media format with optional feature directives.
         * @throws IllegalArgumentException if format is not a valid media format.
         * @return whether the codec capabilities support the given format
         *         and feature requests.
         */
        public final boolean isFormatSupported(MediaFormat format) {
            final Map<String, Object> map = format.getMap();
            final String mime = (String)map.get(MediaFormat.KEY_MIME);

            // mime must match if present
            if (mime != null && !mMime.equalsIgnoreCase(mime)) {
                return false;
            }

            // check feature support
            for (Feature feat: getValidFeatures()) {
                if (feat.mInternal) {
                    continue;
                }

                Integer yesNo = (Integer)map.get(MediaFormat.KEY_FEATURE_ + feat.mName);
                if (yesNo == null) {
                    continue;
                }
                if ((yesNo == 1 && !isFeatureSupported(feat.mName)) ||
                        (yesNo == 0 && isFeatureRequired(feat.mName))) {
                    return false;
                }
            }

            Integer profile = (Integer)map.get(MediaFormat.KEY_PROFILE);
            Integer level = (Integer)map.get(MediaFormat.KEY_LEVEL);

            if (profile != null) {
                if (!supportsProfileLevel(profile, level)) {
                    return false;
                }

                // If we recognize this profile, check that this format is supported by the
                // highest level supported by the codec for that profile. (Ignore specified
                // level beyond the above profile/level check as level is only used as a
                // guidance. E.g. AVC Level 1 CIF format is supported if codec supports level 1.1
                // even though max size for Level 1 is QCIF. However, MPEG2 Simple Profile
                // 1080p format is not supported even if codec supports Main Profile Level High,
                // as Simple Profile does not support 1080p.
                CodecCapabilities levelCaps = null;
                int maxLevel = 0;
                for (CodecProfileLevel pl : profileLevels) {
                    if (pl.profile == profile && pl.level > maxLevel) {
                        // H.263 levels are not completely ordered:
                        // Level45 support only implies Level10 support
                        if (!mMime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_H263)
                                || pl.level != CodecProfileLevel.H263Level45
                                || maxLevel == CodecProfileLevel.H263Level10) {
                            maxLevel = pl.level;
                        }
                    }
                }
                levelCaps = createFromProfileLevel(mMime, profile, maxLevel);
                // We must remove the profile from this format otherwise levelCaps.isFormatSupported
                // will get into this same condition and loop forever. Furthermore, since levelCaps
                // does not contain features and bitrate specific keys, keep only keys relevant for
                // a level check.
                Map<String, Object> levelCriticalFormatMap = new HashMap<>(map);
                final Set<String> criticalKeys =
                    isVideo() ? VideoCapabilities.VIDEO_LEVEL_CRITICAL_FORMAT_KEYS :
                    isAudio() ? AudioCapabilities.AUDIO_LEVEL_CRITICAL_FORMAT_KEYS :
                    null;

                // critical keys will always contain KEY_MIME, but should also contain others to be
                // meaningful
                if (criticalKeys != null && criticalKeys.size() > 1 && levelCaps != null) {
                    levelCriticalFormatMap.keySet().retainAll(criticalKeys);

                    MediaFormat levelCriticalFormat = new MediaFormat(levelCriticalFormatMap);
                    if (!levelCaps.isFormatSupported(levelCriticalFormat)) {
                        return false;
                    }
                }
            }
            if (mAudioCaps != null && !mAudioCaps.supportsFormat(format)) {
                return false;
            }
            if (mVideoCaps != null && !mVideoCaps.supportsFormat(format)) {
                return false;
            }
            if (mEncoderCaps != null && !mEncoderCaps.supportsFormat(format)) {
                return false;
            }
            return true;
        }

        private static boolean supportsBitrate(
                Range<Integer> bitrateRange, MediaFormat format) {
            Map<String, Object> map = format.getMap();

            // consider max bitrate over average bitrate for support
            Integer maxBitrate = (Integer)map.get(MediaFormat.KEY_MAX_BIT_RATE);
            Integer bitrate = (Integer)map.get(MediaFormat.KEY_BIT_RATE);
            if (bitrate == null) {
                bitrate = maxBitrate;
            } else if (maxBitrate != null) {
                bitrate = Math.max(bitrate, maxBitrate);
            }

            if (bitrate != null && bitrate > 0) {
                return bitrateRange.contains(bitrate);
            }

            return true;
        }

        private boolean supportsProfileLevel(int profile, Integer level) {
            for (CodecProfileLevel pl: profileLevels) {
                if (pl.profile != profile) {
                    continue;
                }

                // AAC does not use levels
                if (level == null || mMime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AAC)) {
                    return true;
                }

                // H.263 levels are not completely ordered:
                // Level45 support only implies Level10 support
                if (mMime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_H263)) {
                    if (pl.level != level && pl.level == CodecProfileLevel.H263Level45
                            && level > CodecProfileLevel.H263Level10) {
                        continue;
                    }
                }

                // MPEG4 levels are not completely ordered:
                // Level1 support only implies Level0 (and not Level0b) support
                if (mMime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_MPEG4)) {
                    if (pl.level != level && pl.level == CodecProfileLevel.MPEG4Level1
                            && level > CodecProfileLevel.MPEG4Level0) {
                        continue;
                    }
                }

                // HEVC levels incorporate both tiers and levels. Verify tier support.
                if (mMime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                    boolean supportsHighTier =
                        (pl.level & CodecProfileLevel.HEVCHighTierLevels) != 0;
                    boolean checkingHighTier = (level & CodecProfileLevel.HEVCHighTierLevels) != 0;
                    // high tier levels are only supported by other high tier levels
                    if (checkingHighTier && !supportsHighTier) {
                        continue;
                    }
                }

                if (pl.level >= level) {
                    // if we recognize the listed profile/level, we must also recognize the
                    // profile/level arguments.
                    if (createFromProfileLevel(mMime, profile, pl.level) != null) {
                        return createFromProfileLevel(mMime, profile, level) != null;
                    }
                    return true;
                }
            }
            return false;
        }

        // errors while reading profile levels - accessed from sister capabilities
        int mError;

        private static final String TAG = "CodecCapabilities";

        // NEW-STYLE CAPABILITIES
        private AudioCapabilities mAudioCaps;
        private VideoCapabilities mVideoCaps;
        private EncoderCapabilities mEncoderCaps;
        private MediaFormat mDefaultFormat;

        /**
         * Returns a MediaFormat object with default values for configurations that have
         * defaults.
         */
        public MediaFormat getDefaultFormat() {
            return mDefaultFormat;
        }

        /**
         * Returns the mime type for which this codec-capability object was created.
         */
        public String getMimeType() {
            return mMime;
        }

        /**
         * Returns the max number of the supported concurrent codec instances.
         * <p>
         * This is a hint for an upper bound. Applications should not expect to successfully
         * operate more instances than the returned value, but the actual number of
         * concurrently operable instances may be less as it depends on the available
         * resources at time of use.
         */
        public int getMaxSupportedInstances() {
            return mMaxSupportedInstances;
        }

        private boolean isAudio() {
            return mAudioCaps != null;
        }

        /**
         * Returns the audio capabilities or {@code null} if this is not an audio codec.
         */
        public AudioCapabilities getAudioCapabilities() {
            return mAudioCaps;
        }

        private boolean isEncoder() {
            return mEncoderCaps != null;
        }

        /**
         * Returns the encoding capabilities or {@code null} if this is not an encoder.
         */
        public EncoderCapabilities getEncoderCapabilities() {
            return mEncoderCaps;
        }

        private boolean isVideo() {
            return mVideoCaps != null;
        }

        /**
         * Returns the video capabilities or {@code null} if this is not a video codec.
         */
        public VideoCapabilities getVideoCapabilities() {
            return mVideoCaps;
        }

        /** @hide */
        public CodecCapabilities dup() {
            CodecCapabilities caps = new CodecCapabilities();

            // profileLevels and colorFormats may be modified by client.
            caps.profileLevels = Arrays.copyOf(profileLevels, profileLevels.length);
            caps.colorFormats = Arrays.copyOf(colorFormats, colorFormats.length);

            caps.mMime = mMime;
            caps.mMaxSupportedInstances = mMaxSupportedInstances;
            caps.mFlagsRequired = mFlagsRequired;
            caps.mFlagsSupported = mFlagsSupported;
            caps.mFlagsVerified = mFlagsVerified;
            caps.mAudioCaps = mAudioCaps;
            caps.mVideoCaps = mVideoCaps;
            caps.mEncoderCaps = mEncoderCaps;
            caps.mDefaultFormat = mDefaultFormat;
            caps.mCapabilitiesInfo = mCapabilitiesInfo;

            return caps;
        }

        /**
         * Retrieve the codec capabilities for a certain {@code mime type}, {@code
         * profile} and {@code level}.  If the type, or profile-level combination
         * is not understood by the framework, it returns null.
         * <p class=note> In {@link android.os.Build.VERSION_CODES#M}, calling this
         * method without calling any method of the {@link MediaCodecList} class beforehand
         * results in a {@link NullPointerException}.</p>
         */
        public static CodecCapabilities createFromProfileLevel(
                String mime, int profile, int level) {
            CodecProfileLevel pl = new CodecProfileLevel();
            pl.profile = profile;
            pl.level = level;
            MediaFormat defaultFormat = new MediaFormat();
            defaultFormat.setString(MediaFormat.KEY_MIME, mime);

            CodecCapabilities ret = new CodecCapabilities(
                new CodecProfileLevel[] { pl }, new int[0], true /* encoder */,
                defaultFormat, new MediaFormat() /* info */);
            if (ret.mError != 0) {
                return null;
            }
            return ret;
        }

        /* package private */ CodecCapabilities(
                CodecProfileLevel[] profLevs, int[] colFmts,
                boolean encoder,
                Map<String, Object>defaultFormatMap,
                Map<String, Object>capabilitiesMap) {
            this(profLevs, colFmts, encoder,
                    new MediaFormat(defaultFormatMap),
                    new MediaFormat(capabilitiesMap));
        }

        private MediaFormat mCapabilitiesInfo;

        /* package private */ CodecCapabilities(
                CodecProfileLevel[] profLevs, int[] colFmts, boolean encoder,
                MediaFormat defaultFormat, MediaFormat info) {
            final Map<String, Object> map = info.getMap();
            colorFormats = colFmts;
            mFlagsVerified = 0; // TODO: remove as it is unused
            mDefaultFormat = defaultFormat;
            mCapabilitiesInfo = info;
            mMime = mDefaultFormat.getString(MediaFormat.KEY_MIME);

            /* VP9 introduced profiles around 2016, so some VP9 codecs may not advertise any
               supported profiles. Determine the level for them using the info they provide. */
            if (profLevs.length == 0 && mMime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_VP9)) {
                CodecProfileLevel profLev = new CodecProfileLevel();
                profLev.profile = CodecProfileLevel.VP9Profile0;
                profLev.level = VideoCapabilities.equivalentVP9Level(info);
                profLevs = new CodecProfileLevel[] { profLev };
            }
            profileLevels = profLevs;

            if (mMime.toLowerCase().startsWith("audio/")) {
                mAudioCaps = AudioCapabilities.create(info, this);
                mAudioCaps.getDefaultFormat(mDefaultFormat);
            } else if (mMime.toLowerCase().startsWith("video/")
                    || mMime.equalsIgnoreCase(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC)) {
                mVideoCaps = VideoCapabilities.create(info, this);
            }
            if (encoder) {
                mEncoderCaps = EncoderCapabilities.create(info, this);
                mEncoderCaps.getDefaultFormat(mDefaultFormat);
            }

            final Map<String, Object> global = MediaCodecList.getGlobalSettings();
            mMaxSupportedInstances = Utils.parseIntSafely(
                    global.get("max-concurrent-instances"), DEFAULT_MAX_SUPPORTED_INSTANCES);

            int maxInstances = Utils.parseIntSafely(
                    map.get("max-concurrent-instances"), mMaxSupportedInstances);
            mMaxSupportedInstances =
                    Range.create(1, MAX_SUPPORTED_INSTANCES_LIMIT).clamp(maxInstances);

            for (Feature feat: getValidFeatures()) {
                String key = MediaFormat.KEY_FEATURE_ + feat.mName;
                Integer yesNo = (Integer)map.get(key);
                if (yesNo == null) {
                    continue;
                }
                if (yesNo > 0) {
                    mFlagsRequired |= feat.mValue;
                }
                mFlagsSupported |= feat.mValue;
                if (!feat.mInternal) {
                    mDefaultFormat.setInteger(key, 1);
                }
                // TODO restrict features by mFlagsVerified once all codecs reliably verify them
            }
        }
    }

    /**
     * A class that supports querying the audio capabilities of a codec.
     */
    public static final class AudioCapabilities {
        private static final String TAG = "AudioCapabilities";
        private CodecCapabilities mParent;
        private Range<Integer> mBitrateRange;

        private int[] mSampleRates;
        private Range<Integer>[] mSampleRateRanges;
        private Range<Integer>[] mInputChannelRanges;

        private static final int MAX_INPUT_CHANNEL_COUNT = 30;

        /**
         * Returns the range of supported bitrates in bits/second.
         */
        public Range<Integer> getBitrateRange() {
            return mBitrateRange;
        }

        /**
         * Returns the array of supported sample rates if the codec
         * supports only discrete values.  Otherwise, it returns
         * {@code null}.  The array is sorted in ascending order.
         */
        public int[] getSupportedSampleRates() {
            return mSampleRates != null ? Arrays.copyOf(mSampleRates, mSampleRates.length) : null;
        }

        /**
         * Returns the array of supported sample rate ranges.  The
         * array is sorted in ascending order, and the ranges are
         * distinct.
         */
        public Range<Integer>[] getSupportedSampleRateRanges() {
            return Arrays.copyOf(mSampleRateRanges, mSampleRateRanges.length);
        }

        /**
         * Returns the maximum number of input channels supported.
         *
         * Through {@link android.os.Build.VERSION_CODES#R}, this method indicated support
         * for any number of input channels between 1 and this maximum value.
         *
         * As of {@link android.os.Build.VERSION_CODES#S},
         * the implied lower limit of 1 channel is no longer valid.
         * As of {@link android.os.Build.VERSION_CODES#S}, {@link #getMaxInputChannelCount} is
         * superseded by {@link #getInputChannelCountRanges},
         * which returns an array of ranges of channels.
         * The {@link #getMaxInputChannelCount} method will return the highest value
         * in the ranges returned by {@link #getInputChannelCountRanges}
         *
         */
        @IntRange(from = 1, to = 255)
        public int getMaxInputChannelCount() {
            int overall_max = 0;
            for (int i = mInputChannelRanges.length - 1; i >= 0; i--) {
                int lmax = mInputChannelRanges[i].getUpper();
                if (lmax > overall_max) {
                    overall_max = lmax;
                }
            }
            return overall_max;
        }

        /**
         * Returns the minimum number of input channels supported.
         * This is often 1, but does vary for certain mime types.
         *
         * This returns the lowest channel count in the ranges returned by
         * {@link #getInputChannelCountRanges}.
         */
        @IntRange(from = 1, to = 255)
        public int getMinInputChannelCount() {
            int overall_min = MAX_INPUT_CHANNEL_COUNT;
            for (int i = mInputChannelRanges.length - 1; i >= 0; i--) {
                int lmin = mInputChannelRanges[i].getLower();
                if (lmin < overall_min) {
                    overall_min = lmin;
                }
            }
            return overall_min;
        }

        /*
         * Returns an array of ranges representing the number of input channels supported.
         * The codec supports any number of input channels within this range.
         *
         * This supersedes the {@link #getMaxInputChannelCount} method.
         *
         * For many codecs, this will be a single range [1..N], for some N.
         */
        @SuppressLint("ArrayReturn")
        @NonNull
        public Range<Integer>[] getInputChannelCountRanges() {
            return Arrays.copyOf(mInputChannelRanges, mInputChannelRanges.length);
        }

        /* no public constructor */
        private AudioCapabilities() { }

        /** @hide */
        public static AudioCapabilities create(
                MediaFormat info, CodecCapabilities parent) {
            AudioCapabilities caps = new AudioCapabilities();
            caps.init(info, parent);
            return caps;
        }

        private void init(MediaFormat info, CodecCapabilities parent) {
            mParent = parent;
            initWithPlatformLimits();
            applyLevelLimits();
            parseFromInfo(info);
        }

        private void initWithPlatformLimits() {
            mBitrateRange = Range.create(0, Integer.MAX_VALUE);
            mInputChannelRanges = new Range[] {Range.create(1, MAX_INPUT_CHANNEL_COUNT)};
            // mBitrateRange = Range.create(1, 320000);
            final int minSampleRate = SystemProperties.
                getInt("ro.mediacodec.min_sample_rate", 7350);
            final int maxSampleRate = SystemProperties.
                getInt("ro.mediacodec.max_sample_rate", 192000);
            mSampleRateRanges = new Range[] { Range.create(minSampleRate, maxSampleRate) };
            mSampleRates = null;
        }

        private boolean supports(Integer sampleRate, Integer inputChannels) {
            // channels and sample rates are checked orthogonally
            if (inputChannels != null) {
                int ix = Utils.binarySearchDistinctRanges(
                        mInputChannelRanges, inputChannels);
                if (ix < 0) {
                    return false;
                }
            }
            if (sampleRate != null) {
                int ix = Utils.binarySearchDistinctRanges(
                        mSampleRateRanges, sampleRate);
                if (ix < 0) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Query whether the sample rate is supported by the codec.
         */
        public boolean isSampleRateSupported(int sampleRate) {
            return supports(sampleRate, null);
        }

        /** modifies rates */
        private void limitSampleRates(int[] rates) {
            Arrays.sort(rates);
            ArrayList<Range<Integer>> ranges = new ArrayList<Range<Integer>>();
            for (int rate: rates) {
                if (supports(rate, null /* channels */)) {
                    ranges.add(Range.create(rate, rate));
                }
            }
            mSampleRateRanges = ranges.toArray(new Range[ranges.size()]);
            createDiscreteSampleRates();
        }

        private void createDiscreteSampleRates() {
            mSampleRates = new int[mSampleRateRanges.length];
            for (int i = 0; i < mSampleRateRanges.length; i++) {
                mSampleRates[i] = mSampleRateRanges[i].getLower();
            }
        }

        /** modifies rateRanges */
        private void limitSampleRates(Range<Integer>[] rateRanges) {
            sortDistinctRanges(rateRanges);
            mSampleRateRanges = intersectSortedDistinctRanges(mSampleRateRanges, rateRanges);

            // check if all values are discrete
            for (Range<Integer> range: mSampleRateRanges) {
                if (!range.getLower().equals(range.getUpper())) {
                    mSampleRates = null;
                    return;
                }
            }
            createDiscreteSampleRates();
        }

        private void applyLevelLimits() {
            int[] sampleRates = null;
            Range<Integer> sampleRateRange = null, bitRates = null;
            int maxChannels = MAX_INPUT_CHANNEL_COUNT;
            String mime = mParent.getMimeType();

            if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_MPEG)) {
                sampleRates = new int[] {
                        8000, 11025, 12000,
                        16000, 22050, 24000,
                        32000, 44100, 48000 };
                bitRates = Range.create(8000, 320000);
                maxChannels = 2;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AMR_NB)) {
                sampleRates = new int[] { 8000 };
                bitRates = Range.create(4750, 12200);
                maxChannels = 1;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AMR_WB)) {
                sampleRates = new int[] { 16000 };
                bitRates = Range.create(6600, 23850);
                maxChannels = 1;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AAC)) {
                sampleRates = new int[] {
                        7350, 8000,
                        11025, 12000, 16000,
                        22050, 24000, 32000,
                        44100, 48000, 64000,
                        88200, 96000 };
                bitRates = Range.create(8000, 510000);
                maxChannels = 48;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_VORBIS)) {
                bitRates = Range.create(32000, 500000);
                sampleRateRange = Range.create(8000, 192000);
                maxChannels = 255;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_OPUS)) {
                bitRates = Range.create(6000, 510000);
                sampleRates = new int[] { 8000, 12000, 16000, 24000, 48000 };
                maxChannels = 255;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_RAW)) {
                sampleRateRange = Range.create(1, 96000);
                bitRates = Range.create(1, 10000000);
                maxChannels = AudioSystem.OUT_CHANNEL_COUNT_MAX;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_FLAC)) {
                sampleRateRange = Range.create(1, 655350);
                // lossless codec, so bitrate is ignored
                maxChannels = 255;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_G711_ALAW)
                    || mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_G711_MLAW)) {
                sampleRates = new int[] { 8000 };
                bitRates = Range.create(64000, 64000);
                // platform allows multiple channels for this format
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_MSGSM)) {
                sampleRates = new int[] { 8000 };
                bitRates = Range.create(13000, 13000);
                maxChannels = 1;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AC3)) {
                maxChannels = 6;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_EAC3)) {
                maxChannels = 16;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_EAC3_JOC)) {
                sampleRates = new int[] { 48000 };
                bitRates = Range.create(32000, 6144000);
                maxChannels = 16;
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AC4)) {
                sampleRates = new int[] { 44100, 48000, 96000, 192000 };
                bitRates = Range.create(16000, 2688000);
                maxChannels = 24;
            } else {
                Log.w(TAG, "Unsupported mime " + mime);
                mParent.mError |= ERROR_UNSUPPORTED;
            }

            // restrict ranges
            if (sampleRates != null) {
                limitSampleRates(sampleRates);
            } else if (sampleRateRange != null) {
                limitSampleRates(new Range[] { sampleRateRange });
            }

            Range<Integer> channelRange = Range.create(1, maxChannels);

            applyLimits(new Range[] { channelRange }, bitRates);
        }

        private void applyLimits(Range<Integer>[] inputChannels, Range<Integer> bitRates) {

            // clamp & make a local copy
            Range<Integer>[] myInputChannels = new Range[inputChannels.length];
            for (int i = 0; i < inputChannels.length; i++) {
                int lower = inputChannels[i].clamp(1);
                int upper = inputChannels[i].clamp(MAX_INPUT_CHANNEL_COUNT);
                myInputChannels[i] = Range.create(lower, upper);
            }

            // sort, intersect with existing, & save channel list
            sortDistinctRanges(myInputChannels);
            Range<Integer>[] joinedChannelList =
                            intersectSortedDistinctRanges(myInputChannels, mInputChannelRanges);
            mInputChannelRanges = joinedChannelList;

            if (bitRates != null) {
                mBitrateRange = mBitrateRange.intersect(bitRates);
            }
        }

        private void parseFromInfo(MediaFormat info) {
            int maxInputChannels = MAX_INPUT_CHANNEL_COUNT;
            Range<Integer>[] channels = new Range[] { Range.create(1, maxInputChannels)};
            Range<Integer> bitRates = POSITIVE_INTEGERS;

            if (info.containsKey("sample-rate-ranges")) {
                String[] rateStrings = info.getString("sample-rate-ranges").split(",");
                Range<Integer>[] rateRanges = new Range[rateStrings.length];
                for (int i = 0; i < rateStrings.length; i++) {
                    rateRanges[i] = Utils.parseIntRange(rateStrings[i], null);
                }
                limitSampleRates(rateRanges);
            }

            // we will prefer channel-ranges over max-channel-count
            if (info.containsKey("channel-ranges")) {
                String[] channelStrings = info.getString("channel-ranges").split(",");
                Range<Integer>[] channelRanges = new Range[channelStrings.length];
                for (int i = 0; i < channelStrings.length; i++) {
                    channelRanges[i] = Utils.parseIntRange(channelStrings[i], null);
                }
                channels = channelRanges;
            } else if (info.containsKey("channel-range")) {
                Range<Integer> oneRange = Utils.parseIntRange(info.getString("channel-range"),
                                                              null);
                channels = new Range[] { oneRange };
            } else if (info.containsKey("max-channel-count")) {
                maxInputChannels = Utils.parseIntSafely(
                        info.getString("max-channel-count"), maxInputChannels);
                if (maxInputChannels == 0) {
                    channels = new Range[] {Range.create(0, 0)};
                } else {
                    channels = new Range[] {Range.create(1, maxInputChannels)};
                }
            } else if ((mParent.mError & ERROR_UNSUPPORTED) != 0) {
                maxInputChannels = 0;
                channels = new Range[] {Range.create(0, 0)};
            }

            if (info.containsKey("bitrate-range")) {
                bitRates = bitRates.intersect(
                        Utils.parseIntRange(info.getString("bitrate-range"), bitRates));
            }

            applyLimits(channels, bitRates);
        }

        /** @hide */
        public void getDefaultFormat(MediaFormat format) {
            // report settings that have only a single choice
            if (mBitrateRange.getLower().equals(mBitrateRange.getUpper())) {
                format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrateRange.getLower());
            }
            if (getMaxInputChannelCount() == 1) {
                // mono-only format
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            }
            if (mSampleRates != null && mSampleRates.length == 1) {
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, mSampleRates[0]);
            }
        }

        /* package private */
        // must not contain KEY_PROFILE
        static final Set<String> AUDIO_LEVEL_CRITICAL_FORMAT_KEYS = Set.of(
                // We don't set level-specific limits for audio codecs today. Key candidates would
                // be sample rate, bit rate or channel count.
                // MediaFormat.KEY_SAMPLE_RATE,
                // MediaFormat.KEY_CHANNEL_COUNT,
                // MediaFormat.KEY_BIT_RATE,
                MediaFormat.KEY_MIME);

        /** @hide */
        public boolean supportsFormat(MediaFormat format) {
            Map<String, Object> map = format.getMap();
            Integer sampleRate = (Integer)map.get(MediaFormat.KEY_SAMPLE_RATE);
            Integer channels = (Integer)map.get(MediaFormat.KEY_CHANNEL_COUNT);

            if (!supports(sampleRate, channels)) {
                return false;
            }

            if (!CodecCapabilities.supportsBitrate(mBitrateRange, format)) {
                return false;
            }

            // nothing to do for:
            // KEY_CHANNEL_MASK: codecs don't get this
            // KEY_IS_ADTS:      required feature for all AAC decoders
            return true;
        }
    }

    /**
     * A class that supports querying the video capabilities of a codec.
     */
    public static final class VideoCapabilities {
        private static final String TAG = "VideoCapabilities";
        private CodecCapabilities mParent;
        private Range<Integer> mBitrateRange;

        private Range<Integer> mHeightRange;
        private Range<Integer> mWidthRange;
        private Range<Integer> mBlockCountRange;
        private Range<Integer> mHorizontalBlockRange;
        private Range<Integer> mVerticalBlockRange;
        private Range<Rational> mAspectRatioRange;
        private Range<Rational> mBlockAspectRatioRange;
        private Range<Long> mBlocksPerSecondRange;
        private Map<Size, Range<Long>> mMeasuredFrameRates;
        private List<PerformancePoint> mPerformancePoints;
        private Range<Integer> mFrameRateRange;

        private int mBlockWidth;
        private int mBlockHeight;
        private int mWidthAlignment;
        private int mHeightAlignment;
        private int mSmallerDimensionUpperLimit;

        private boolean mAllowMbOverride; // allow XML to override calculated limits

        /**
         * Returns the range of supported bitrates in bits/second.
         */
        public Range<Integer> getBitrateRange() {
            return mBitrateRange;
        }

        /**
         * Returns the range of supported video widths.
         * <p class=note>
         * 32-bit processes will not support resolutions larger than 4096x4096 due to
         * the limited address space.
         */
        public Range<Integer> getSupportedWidths() {
            return mWidthRange;
        }

        /**
         * Returns the range of supported video heights.
         * <p class=note>
         * 32-bit processes will not support resolutions larger than 4096x4096 due to
         * the limited address space.
         */
        public Range<Integer> getSupportedHeights() {
            return mHeightRange;
        }

        /**
         * Returns the alignment requirement for video width (in pixels).
         *
         * This is a power-of-2 value that video width must be a
         * multiple of.
         */
        public int getWidthAlignment() {
            return mWidthAlignment;
        }

        /**
         * Returns the alignment requirement for video height (in pixels).
         *
         * This is a power-of-2 value that video height must be a
         * multiple of.
         */
        public int getHeightAlignment() {
            return mHeightAlignment;
        }

        /**
         * Return the upper limit on the smaller dimension of width or height.
         * <p></p>
         * Some codecs have a limit on the smaller dimension, whether it be
         * the width or the height.  E.g. a codec may only be able to handle
         * up to 1920x1080 both in landscape and portrait mode (1080x1920).
         * In this case the maximum width and height are both 1920, but the
         * smaller dimension limit will be 1080. For other codecs, this is
         * {@code Math.min(getSupportedWidths().getUpper(),
         * getSupportedHeights().getUpper())}.
         *
         * @hide
         */
        public int getSmallerDimensionUpperLimit() {
            return mSmallerDimensionUpperLimit;
        }

        /**
         * Returns the range of supported frame rates.
         * <p>
         * This is not a performance indicator.  Rather, it expresses the
         * limits specified in the coding standard, based on the complexities
         * of encoding material for later playback at a certain frame rate,
         * or the decoding of such material in non-realtime.
         */
        public Range<Integer> getSupportedFrameRates() {
            return mFrameRateRange;
        }

        /**
         * Returns the range of supported video widths for a video height.
         * @param height the height of the video
         */
        public Range<Integer> getSupportedWidthsFor(int height) {
            try {
                Range<Integer> range = mWidthRange;
                if (!mHeightRange.contains(height)
                        || (height % mHeightAlignment) != 0) {
                    throw new IllegalArgumentException("unsupported height");
                }
                final int heightInBlocks = Utils.divUp(height, mBlockHeight);

                // constrain by block count and by block aspect ratio
                final int minWidthInBlocks = Math.max(
                        Utils.divUp(mBlockCountRange.getLower(), heightInBlocks),
                        (int)Math.ceil(mBlockAspectRatioRange.getLower().doubleValue()
                                * heightInBlocks));
                final int maxWidthInBlocks = Math.min(
                        mBlockCountRange.getUpper() / heightInBlocks,
                        (int)(mBlockAspectRatioRange.getUpper().doubleValue()
                                * heightInBlocks));
                range = range.intersect(
                        (minWidthInBlocks - 1) * mBlockWidth + mWidthAlignment,
                        maxWidthInBlocks * mBlockWidth);

                // constrain by smaller dimension limit
                if (height > mSmallerDimensionUpperLimit) {
                    range = range.intersect(1, mSmallerDimensionUpperLimit);
                }

                // constrain by aspect ratio
                range = range.intersect(
                        (int)Math.ceil(mAspectRatioRange.getLower().doubleValue()
                                * height),
                        (int)(mAspectRatioRange.getUpper().doubleValue() * height));
                return range;
            } catch (IllegalArgumentException e) {
                // height is not supported because there are no suitable widths
                Log.v(TAG, "could not get supported widths for " + height);
                throw new IllegalArgumentException("unsupported height");
            }
        }

        /**
         * Returns the range of supported video heights for a video width
         * @param width the width of the video
         */
        public Range<Integer> getSupportedHeightsFor(int width) {
            try {
                Range<Integer> range = mHeightRange;
                if (!mWidthRange.contains(width)
                        || (width % mWidthAlignment) != 0) {
                    throw new IllegalArgumentException("unsupported width");
                }
                final int widthInBlocks = Utils.divUp(width, mBlockWidth);

                // constrain by block count and by block aspect ratio
                final int minHeightInBlocks = Math.max(
                        Utils.divUp(mBlockCountRange.getLower(), widthInBlocks),
                        (int)Math.ceil(widthInBlocks /
                                mBlockAspectRatioRange.getUpper().doubleValue()));
                final int maxHeightInBlocks = Math.min(
                        mBlockCountRange.getUpper() / widthInBlocks,
                        (int)(widthInBlocks /
                                mBlockAspectRatioRange.getLower().doubleValue()));
                range = range.intersect(
                        (minHeightInBlocks - 1) * mBlockHeight + mHeightAlignment,
                        maxHeightInBlocks * mBlockHeight);

                // constrain by smaller dimension limit
                if (width > mSmallerDimensionUpperLimit) {
                    range = range.intersect(1, mSmallerDimensionUpperLimit);
                }

                // constrain by aspect ratio
                range = range.intersect(
                        (int)Math.ceil(width /
                                mAspectRatioRange.getUpper().doubleValue()),
                        (int)(width / mAspectRatioRange.getLower().doubleValue()));
                return range;
            } catch (IllegalArgumentException e) {
                // width is not supported because there are no suitable heights
                Log.v(TAG, "could not get supported heights for " + width);
                throw new IllegalArgumentException("unsupported width");
            }
        }

        /**
         * Returns the range of supported video frame rates for a video size.
         * <p>
         * This is not a performance indicator.  Rather, it expresses the limits specified in
         * the coding standard, based on the complexities of encoding material of a given
         * size for later playback at a certain frame rate, or the decoding of such material
         * in non-realtime.

         * @param width the width of the video
         * @param height the height of the video
         */
        public Range<Double> getSupportedFrameRatesFor(int width, int height) {
            Range<Integer> range = mHeightRange;
            if (!supports(width, height, null)) {
                throw new IllegalArgumentException("unsupported size");
            }
            final int blockCount =
                Utils.divUp(width, mBlockWidth) * Utils.divUp(height, mBlockHeight);

            return Range.create(
                    Math.max(mBlocksPerSecondRange.getLower() / (double) blockCount,
                            (double) mFrameRateRange.getLower()),
                    Math.min(mBlocksPerSecondRange.getUpper() / (double) blockCount,
                            (double) mFrameRateRange.getUpper()));
        }

        private int getBlockCount(int width, int height) {
            return Utils.divUp(width, mBlockWidth) * Utils.divUp(height, mBlockHeight);
        }

        @NonNull
        private Size findClosestSize(int width, int height) {
            int targetBlockCount = getBlockCount(width, height);
            Size closestSize = null;
            int minDiff = Integer.MAX_VALUE;
            for (Size size : mMeasuredFrameRates.keySet()) {
                int diff = Math.abs(targetBlockCount -
                        getBlockCount(size.getWidth(), size.getHeight()));
                if (diff < minDiff) {
                    minDiff = diff;
                    closestSize = size;
                }
            }
            return closestSize;
        }

        private Range<Double> estimateFrameRatesFor(int width, int height) {
            Size size = findClosestSize(width, height);
            Range<Long> range = mMeasuredFrameRates.get(size);
            Double ratio = getBlockCount(size.getWidth(), size.getHeight())
                    / (double)Math.max(getBlockCount(width, height), 1);
            return Range.create(range.getLower() * ratio, range.getUpper() * ratio);
        }

        /**
         * Returns the range of achievable video frame rates for a video size.
         * May return {@code null}, if the codec did not publish any measurement
         * data.
         * <p>
         * This is a performance estimate provided by the device manufacturer based on statistical
         * sampling of full-speed decoding and encoding measurements in various configurations
         * of common video sizes supported by the codec. As such it should only be used to
         * compare individual codecs on the device. The value is not suitable for comparing
         * different devices or even different android releases for the same device.
         * <p>
         * <em>On {@link android.os.Build.VERSION_CODES#M} release</em> the returned range
         * corresponds to the fastest frame rates achieved in the tested configurations. As
         * such, it should not be used to gauge guaranteed or even average codec performance
         * on the device.
         * <p>
         * <em>On {@link android.os.Build.VERSION_CODES#N} release</em> the returned range
         * corresponds closer to sustained performance <em>in tested configurations</em>.
         * One can expect to achieve sustained performance higher than the lower limit more than
         * 50% of the time, and higher than half of the lower limit at least 90% of the time
         * <em>in tested configurations</em>.
         * Conversely, one can expect performance lower than twice the upper limit at least
         * 90% of the time.
         * <p class=note>
         * Tested configurations use a single active codec. For use cases where multiple
         * codecs are active, applications can expect lower and in most cases significantly lower
         * performance.
         * <p class=note>
         * The returned range value is interpolated from the nearest frame size(s) tested.
         * Codec performance is severely impacted by other activity on the device as well
         * as environmental factors (such as battery level, temperature or power source), and can
         * vary significantly even in a steady environment.
         * <p class=note>
         * Use this method in cases where only codec performance matters, e.g. to evaluate if
         * a codec has any chance of meeting a performance target. Codecs are listed
         * in {@link MediaCodecList} in the preferred order as defined by the device
         * manufacturer. As such, applications should use the first suitable codec in the
         * list to achieve the best balance between power use and performance.
         *
         * @param width the width of the video
         * @param height the height of the video
         *
         * @throws IllegalArgumentException if the video size is not supported.
         */
        @Nullable
        public Range<Double> getAchievableFrameRatesFor(int width, int height) {
            if (!supports(width, height, null)) {
                throw new IllegalArgumentException("unsupported size");
            }

            if (mMeasuredFrameRates == null || mMeasuredFrameRates.size() <= 0) {
                Log.w(TAG, "Codec did not publish any measurement data.");
                return null;
            }

            return estimateFrameRatesFor(width, height);
        }

        /**
         * Video performance points are a set of standard performance points defined by number of
         * pixels, pixel rate and frame rate. Performance point represents an upper bound. This
         * means that it covers all performance points with fewer pixels, pixel rate and frame
         * rate.
         */
        public static final class PerformancePoint {
            private Size mBlockSize; // codec block size in macroblocks
            private int mWidth; // width in macroblocks
            private int mHeight; // height in macroblocks
            private int mMaxFrameRate; // max frames per second
            private long mMaxMacroBlockRate; // max macro block rate

            /**
             * Maximum number of macroblocks in the frame.
             *
             * Video frames are conceptually divided into 16-by-16 pixel blocks called macroblocks.
             * Most coding standards operate on these 16-by-16 pixel blocks; thus, codec performance
             * is characterized using such blocks.
             *
             * @hide
             */
            @TestApi
            public int getMaxMacroBlocks() {
                return saturateLongToInt(mWidth * (long)mHeight);
            }

            /**
             * Maximum frame rate in frames per second.
             *
             * @hide
             */
            @TestApi
            public int getMaxFrameRate() {
                return mMaxFrameRate;
            }

            /**
             * Maximum number of macroblocks processed per second.
             *
             * @hide
             */
            @TestApi
            public long getMaxMacroBlockRate() {
                return mMaxMacroBlockRate;
            }

            /** Convert to a debug string */
            public String toString() {
                int blockWidth = 16 * mBlockSize.getWidth();
                int blockHeight = 16 * mBlockSize.getHeight();
                int origRate = (int)Utils.divUp(mMaxMacroBlockRate, getMaxMacroBlocks());
                String info = (mWidth * 16) + "x" + (mHeight * 16) + "@" + origRate;
                if (origRate < mMaxFrameRate) {
                    info += ", max " + mMaxFrameRate + "fps";
                }
                if (blockWidth > 16 || blockHeight > 16) {
                    info += ", " + blockWidth + "x" + blockHeight + " blocks";
                }
                return "PerformancePoint(" + info + ")";
            }

            @Override
            public int hashCode() {
                // only max frame rate must equal between performance points that equal to one
                // another
                return mMaxFrameRate;
            }

            /**
             * Create a detailed performance point with custom max frame rate and macroblock size.
             *
             * @param width  frame width in pixels
             * @param height frame height in pixels
             * @param frameRate frames per second for frame width and height
             * @param maxFrameRate maximum frames per second for any frame size
             * @param blockSize block size for codec implementation. Must be powers of two in both
             *        width and height.
             *
             * @throws IllegalArgumentException if the blockSize dimensions are not powers of two.
             *
             * @hide
             */
            @TestApi
            public PerformancePoint(
                    int width, int height, int frameRate, int maxFrameRate,
                    @NonNull Size blockSize) {
                checkPowerOfTwo(blockSize.getWidth(), "block width");
                checkPowerOfTwo(blockSize.getHeight(), "block height");

                mBlockSize = new Size(Utils.divUp(blockSize.getWidth(), 16),
                                      Utils.divUp(blockSize.getHeight(), 16));
                // these are guaranteed not to overflow as we decimate by 16
                mWidth = (int)(Utils.divUp(Math.max(1L, width),
                                           Math.max(blockSize.getWidth(), 16))
                               * mBlockSize.getWidth());
                mHeight = (int)(Utils.divUp(Math.max(1L, height),
                                            Math.max(blockSize.getHeight(), 16))
                                * mBlockSize.getHeight());
                mMaxFrameRate = Math.max(1, Math.max(frameRate, maxFrameRate));
                mMaxMacroBlockRate = Math.max(1, frameRate) * getMaxMacroBlocks();
            }

            /**
             * Convert a performance point to a larger blocksize.
             *
             * @param pp performance point
             * @param blockSize block size for codec implementation
             *
             * @hide
             */
            @TestApi
            public PerformancePoint(@NonNull PerformancePoint pp, @NonNull Size newBlockSize) {
                this(
                        pp.mWidth * 16, pp.mHeight * 16,
                        // guaranteed not to overflow as these were multiplied at construction
                        (int)Utils.divUp(pp.mMaxMacroBlockRate, pp.getMaxMacroBlocks()),
                        pp.mMaxFrameRate,
                        new Size(Math.max(newBlockSize.getWidth(), pp.mBlockSize.getWidth() * 16),
                                 Math.max(newBlockSize.getHeight(), pp.mBlockSize.getHeight() * 16))
                );
            }

            /**
             * Create a performance point for a given frame size and frame rate.
             *
             * @param width width of the frame in pixels
             * @param height height of the frame in pixels
             * @param frameRate frame rate in frames per second
             */
            public PerformancePoint(int width, int height, int frameRate) {
                this(width, height, frameRate, frameRate /* maxFrameRate */, new Size(16, 16));
            }

            /** Saturates a long value to int */
            private int saturateLongToInt(long value) {
                if (value < Integer.MIN_VALUE) {
                    return Integer.MIN_VALUE;
                } else if (value > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                } else {
                    return (int)value;
                }
            }

            /* This method may overflow */
            private int align(int value, int alignment) {
                return Utils.divUp(value, alignment) * alignment;
            }

            /** Checks that value is a power of two. */
            private void checkPowerOfTwo2(int value, @NonNull String description) {
                if (value == 0 || (value & (value - 1)) != 0) {
                    throw new IllegalArgumentException(
                            description + " (" + value + ") must be a power of 2");
                }
            }

            /**
             * Checks whether the performance point covers a media format.
             *
             * @param format Stream format considered
             *
             * @return {@code true} if the performance point covers the format.
             */
            public boolean covers(@NonNull MediaFormat format) {
                PerformancePoint other = new PerformancePoint(
                        format.getInteger(MediaFormat.KEY_WIDTH, 0),
                        format.getInteger(MediaFormat.KEY_HEIGHT, 0),
                        // safely convert ceil(double) to int through float cast and Math.round
                        Math.round((float)(
                                Math.ceil(format.getNumber(MediaFormat.KEY_FRAME_RATE, 0)
                                        .doubleValue()))));
                return covers(other);
            }

            /**
             * Checks whether the performance point covers another performance point. Use this
             * method to determine if a performance point advertised by a codec covers the
             * performance point required. This method can also be used for loose ordering as this
             * method is transitive.
             *
             * @param other other performance point considered
             *
             * @return {@code true} if the performance point covers the other.
             */
            public boolean covers(@NonNull PerformancePoint other) {
                // convert performance points to common block size
                Size commonSize = getCommonBlockSize(other);
                PerformancePoint aligned = new PerformancePoint(this, commonSize);
                PerformancePoint otherAligned = new PerformancePoint(other, commonSize);

                return (aligned.getMaxMacroBlocks() >= otherAligned.getMaxMacroBlocks()
                        && aligned.mMaxFrameRate >= otherAligned.mMaxFrameRate
                        && aligned.mMaxMacroBlockRate >= otherAligned.mMaxMacroBlockRate);
            }

            private @NonNull Size getCommonBlockSize(@NonNull PerformancePoint other) {
                return new Size(
                        Math.max(mBlockSize.getWidth(), other.mBlockSize.getWidth()) * 16,
                        Math.max(mBlockSize.getHeight(), other.mBlockSize.getHeight()) * 16);
            }

            @Override
            public boolean equals(Object o) {
                if (o instanceof PerformancePoint) {
                    // convert performance points to common block size
                    PerformancePoint other = (PerformancePoint)o;
                    Size commonSize = getCommonBlockSize(other);
                    PerformancePoint aligned = new PerformancePoint(this, commonSize);
                    PerformancePoint otherAligned = new PerformancePoint(other, commonSize);

                    return (aligned.getMaxMacroBlocks() == otherAligned.getMaxMacroBlocks()
                            && aligned.mMaxFrameRate == otherAligned.mMaxFrameRate
                            && aligned.mMaxMacroBlockRate == otherAligned.mMaxMacroBlockRate);
                }
                return false;
            }

            /** 480p 24fps */
            @NonNull
            public static final PerformancePoint SD_24 = new PerformancePoint(720, 480, 24);
            /** 576p 25fps */
            @NonNull
            public static final PerformancePoint SD_25 = new PerformancePoint(720, 576, 25);
            /** 480p 30fps */
            @NonNull
            public static final PerformancePoint SD_30 = new PerformancePoint(720, 480, 30);
            /** 480p 48fps */
            @NonNull
            public static final PerformancePoint SD_48 = new PerformancePoint(720, 480, 48);
            /** 576p 50fps */
            @NonNull
            public static final PerformancePoint SD_50 = new PerformancePoint(720, 576, 50);
            /** 480p 60fps */
            @NonNull
            public static final PerformancePoint SD_60 = new PerformancePoint(720, 480, 60);

            /** 720p 24fps */
            @NonNull
            public static final PerformancePoint HD_24 = new PerformancePoint(1280, 720, 24);
            /** 720p 25fps */
            @NonNull
            public static final PerformancePoint HD_25 = new PerformancePoint(1280, 720, 25);
            /** 720p 30fps */
            @NonNull
            public static final PerformancePoint HD_30 = new PerformancePoint(1280, 720, 30);
            /** 720p 50fps */
            @NonNull
            public static final PerformancePoint HD_50 = new PerformancePoint(1280, 720, 50);
            /** 720p 60fps */
            @NonNull
            public static final PerformancePoint HD_60 = new PerformancePoint(1280, 720, 60);
            /** 720p 100fps */
            @NonNull
            public static final PerformancePoint HD_100 = new PerformancePoint(1280, 720, 100);
            /** 720p 120fps */
            @NonNull
            public static final PerformancePoint HD_120 = new PerformancePoint(1280, 720, 120);
            /** 720p 200fps */
            @NonNull
            public static final PerformancePoint HD_200 = new PerformancePoint(1280, 720, 200);
            /** 720p 240fps */
            @NonNull
            public static final PerformancePoint HD_240 = new PerformancePoint(1280, 720, 240);

            /** 1080p 24fps */
            @NonNull
            public static final PerformancePoint FHD_24 = new PerformancePoint(1920, 1080, 24);
            /** 1080p 25fps */
            @NonNull
            public static final PerformancePoint FHD_25 = new PerformancePoint(1920, 1080, 25);
            /** 1080p 30fps */
            @NonNull
            public static final PerformancePoint FHD_30 = new PerformancePoint(1920, 1080, 30);
            /** 1080p 50fps */
            @NonNull
            public static final PerformancePoint FHD_50 = new PerformancePoint(1920, 1080, 50);
            /** 1080p 60fps */
            @NonNull
            public static final PerformancePoint FHD_60 = new PerformancePoint(1920, 1080, 60);
            /** 1080p 100fps */
            @NonNull
            public static final PerformancePoint FHD_100 = new PerformancePoint(1920, 1080, 100);
            /** 1080p 120fps */
            @NonNull
            public static final PerformancePoint FHD_120 = new PerformancePoint(1920, 1080, 120);
            /** 1080p 200fps */
            @NonNull
            public static final PerformancePoint FHD_200 = new PerformancePoint(1920, 1080, 200);
            /** 1080p 240fps */
            @NonNull
            public static final PerformancePoint FHD_240 = new PerformancePoint(1920, 1080, 240);

            /** 2160p 24fps */
            @NonNull
            public static final PerformancePoint UHD_24 = new PerformancePoint(3840, 2160, 24);
            /** 2160p 25fps */
            @NonNull
            public static final PerformancePoint UHD_25 = new PerformancePoint(3840, 2160, 25);
            /** 2160p 30fps */
            @NonNull
            public static final PerformancePoint UHD_30 = new PerformancePoint(3840, 2160, 30);
            /** 2160p 50fps */
            @NonNull
            public static final PerformancePoint UHD_50 = new PerformancePoint(3840, 2160, 50);
            /** 2160p 60fps */
            @NonNull
            public static final PerformancePoint UHD_60 = new PerformancePoint(3840, 2160, 60);
            /** 2160p 100fps */
            @NonNull
            public static final PerformancePoint UHD_100 = new PerformancePoint(3840, 2160, 100);
            /** 2160p 120fps */
            @NonNull
            public static final PerformancePoint UHD_120 = new PerformancePoint(3840, 2160, 120);
            /** 2160p 200fps */
            @NonNull
            public static final PerformancePoint UHD_200 = new PerformancePoint(3840, 2160, 200);
            /** 2160p 240fps */
            @NonNull
            public static final PerformancePoint UHD_240 = new PerformancePoint(3840, 2160, 240);
        }

        /**
         * Returns the supported performance points. May return {@code null} if the codec did not
         * publish any performance point information (e.g. the vendor codecs have not been updated
         * to the latest android release). May return an empty list if the codec published that
         * if does not guarantee any performance points.
         * <p>
         * This is a performance guarantee provided by the device manufacturer for hardware codecs
         * based on hardware capabilities of the device.
         * <p>
         * The returned list is sorted first by decreasing number of pixels, then by decreasing
         * width, and finally by decreasing frame rate.
         * Performance points assume a single active codec. For use cases where multiple
         * codecs are active, should use that highest pixel count, and add the frame rates of
         * each individual codec.
         * <p class=note>
         * 32-bit processes will not support resolutions larger than 4096x4096 due to
         * the limited address space, but performance points will be presented as is.
         * In other words, even though a component publishes a performance point for
         * a resolution higher than 4096x4096, it does not mean that the resolution is supported
         * for 32-bit processes.
         */
        @Nullable
        public List<PerformancePoint> getSupportedPerformancePoints() {
            return mPerformancePoints;
        }

        /**
         * Returns whether a given video size ({@code width} and
         * {@code height}) and {@code frameRate} combination is supported.
         */
        public boolean areSizeAndRateSupported(
                int width, int height, double frameRate) {
            return supports(width, height, frameRate);
        }

        /**
         * Returns whether a given video size ({@code width} and
         * {@code height}) is supported.
         */
        public boolean isSizeSupported(int width, int height) {
            return supports(width, height, null);
        }

        private boolean supports(Integer width, Integer height, Number rate) {
            boolean ok = true;

            if (ok && width != null) {
                ok = mWidthRange.contains(width)
                        && (width % mWidthAlignment == 0);
            }
            if (ok && height != null) {
                ok = mHeightRange.contains(height)
                        && (height % mHeightAlignment == 0);
            }
            if (ok && rate != null) {
                ok = mFrameRateRange.contains(Utils.intRangeFor(rate.doubleValue()));
            }
            if (ok && height != null && width != null) {
                ok = Math.min(height, width) <= mSmallerDimensionUpperLimit;

                final int widthInBlocks = Utils.divUp(width, mBlockWidth);
                final int heightInBlocks = Utils.divUp(height, mBlockHeight);
                final int blockCount = widthInBlocks * heightInBlocks;
                ok = ok && mBlockCountRange.contains(blockCount)
                        && mBlockAspectRatioRange.contains(
                                new Rational(widthInBlocks, heightInBlocks))
                        && mAspectRatioRange.contains(new Rational(width, height));
                if (ok && rate != null) {
                    double blocksPerSec = blockCount * rate.doubleValue();
                    ok = mBlocksPerSecondRange.contains(
                            Utils.longRangeFor(blocksPerSec));
                }
            }
            return ok;
        }

        /* package private */
        // must not contain KEY_PROFILE
        static final Set<String> VIDEO_LEVEL_CRITICAL_FORMAT_KEYS = Set.of(
                MediaFormat.KEY_WIDTH,
                MediaFormat.KEY_HEIGHT,
                MediaFormat.KEY_FRAME_RATE,
                MediaFormat.KEY_BIT_RATE,
                MediaFormat.KEY_MIME);

        /**
         * @hide
         * @throws java.lang.ClassCastException */
        public boolean supportsFormat(MediaFormat format) {
            final Map<String, Object> map = format.getMap();
            Integer width = (Integer)map.get(MediaFormat.KEY_WIDTH);
            Integer height = (Integer)map.get(MediaFormat.KEY_HEIGHT);
            Number rate = (Number)map.get(MediaFormat.KEY_FRAME_RATE);

            if (!supports(width, height, rate)) {
                return false;
            }

            if (!CodecCapabilities.supportsBitrate(mBitrateRange, format)) {
                return false;
            }

            // we ignore color-format for now as it is not reliably reported by codec
            return true;
        }

        /* no public constructor */
        private VideoCapabilities() { }

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public static VideoCapabilities create(
                MediaFormat info, CodecCapabilities parent) {
            VideoCapabilities caps = new VideoCapabilities();
            caps.init(info, parent);
            return caps;
        }

        private void init(MediaFormat info, CodecCapabilities parent) {
            mParent = parent;
            initWithPlatformLimits();
            applyLevelLimits();
            parseFromInfo(info);
            updateLimits();
        }

        /** @hide */
        public Size getBlockSize() {
            return new Size(mBlockWidth, mBlockHeight);
        }

        /** @hide */
        public Range<Integer> getBlockCountRange() {
            return mBlockCountRange;
        }

        /** @hide */
        public Range<Long> getBlocksPerSecondRange() {
            return mBlocksPerSecondRange;
        }

        /** @hide */
        public Range<Rational> getAspectRatioRange(boolean blocks) {
            return blocks ? mBlockAspectRatioRange : mAspectRatioRange;
        }

        private void initWithPlatformLimits() {
            mBitrateRange = BITRATE_RANGE;

            mWidthRange  = getSizeRange();
            mHeightRange = getSizeRange();
            mFrameRateRange = FRAME_RATE_RANGE;

            mHorizontalBlockRange = getSizeRange();
            mVerticalBlockRange   = getSizeRange();

            // full positive ranges are supported as these get calculated
            mBlockCountRange      = POSITIVE_INTEGERS;
            mBlocksPerSecondRange = POSITIVE_LONGS;

            mBlockAspectRatioRange = POSITIVE_RATIONALS;
            mAspectRatioRange      = POSITIVE_RATIONALS;

            // YUV 4:2:0 requires 2:2 alignment
            mWidthAlignment = 2;
            mHeightAlignment = 2;
            mBlockWidth = 2;
            mBlockHeight = 2;
            mSmallerDimensionUpperLimit = getSizeRange().getUpper();
        }

        private @Nullable List<PerformancePoint> getPerformancePoints(Map<String, Object> map) {
            Vector<PerformancePoint> ret = new Vector<>();
            final String prefix = "performance-point-";
            Set<String> keys = map.keySet();
            for (String key : keys) {
                // looking for: performance-point-WIDTHxHEIGHT-range
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String subKey = key.substring(prefix.length());
                if (subKey.equals("none") && ret.size() == 0) {
                    // This means that component knowingly did not publish performance points.
                    // This is different from when the component forgot to publish performance
                    // points.
                    return Collections.unmodifiableList(ret);
                }
                String[] temp = key.split("-");
                if (temp.length != 4) {
                    continue;
                }
                String sizeStr = temp[2];
                Size size = Utils.parseSize(sizeStr, null);
                if (size == null || size.getWidth() * size.getHeight() <= 0) {
                    continue;
                }
                Range<Long> range = Utils.parseLongRange(map.get(key), null);
                if (range == null || range.getLower() < 0 || range.getUpper() < 0) {
                    continue;
                }
                PerformancePoint given = new PerformancePoint(
                        size.getWidth(), size.getHeight(), range.getLower().intValue(),
                        range.getUpper().intValue(), new Size(mBlockWidth, mBlockHeight));
                PerformancePoint rotated = new PerformancePoint(
                        size.getHeight(), size.getWidth(), range.getLower().intValue(),
                        range.getUpper().intValue(), new Size(mBlockWidth, mBlockHeight));
                ret.add(given);
                if (!given.covers(rotated)) {
                    ret.add(rotated);
                }
            }

            // check if the component specified no performance point indication
            if (ret.size() == 0) {
                return null;
            }

            // sort reversed by area first, then by frame rate
            ret.sort((a, b) ->
                     -((a.getMaxMacroBlocks() != b.getMaxMacroBlocks()) ?
                               (a.getMaxMacroBlocks() < b.getMaxMacroBlocks() ? -1 : 1) :
                       (a.getMaxMacroBlockRate() != b.getMaxMacroBlockRate()) ?
                               (a.getMaxMacroBlockRate() < b.getMaxMacroBlockRate() ? -1 : 1) :
                       (a.getMaxFrameRate() != b.getMaxFrameRate()) ?
                               (a.getMaxFrameRate() < b.getMaxFrameRate() ? -1 : 1) : 0));

            return Collections.unmodifiableList(ret);
        }

        private Map<Size, Range<Long>> getMeasuredFrameRates(Map<String, Object> map) {
            Map<Size, Range<Long>> ret = new HashMap<Size, Range<Long>>();
            final String prefix = "measured-frame-rate-";
            Set<String> keys = map.keySet();
            for (String key : keys) {
                // looking for: measured-frame-rate-WIDTHxHEIGHT-range
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String subKey = key.substring(prefix.length());
                String[] temp = key.split("-");
                if (temp.length != 5) {
                    continue;
                }
                String sizeStr = temp[3];
                Size size = Utils.parseSize(sizeStr, null);
                if (size == null || size.getWidth() * size.getHeight() <= 0) {
                    continue;
                }
                Range<Long> range = Utils.parseLongRange(map.get(key), null);
                if (range == null || range.getLower() < 0 || range.getUpper() < 0) {
                    continue;
                }
                ret.put(size, range);
            }
            return ret;
        }

        private static Pair<Range<Integer>, Range<Integer>> parseWidthHeightRanges(Object o) {
            Pair<Size, Size> range = Utils.parseSizeRange(o);
            if (range != null) {
                try {
                    return Pair.create(
                            Range.create(range.first.getWidth(), range.second.getWidth()),
                            Range.create(range.first.getHeight(), range.second.getHeight()));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "could not parse size range '" + o + "'");
                }
            }
            return null;
        }

        /** @hide */
        public static int equivalentVP9Level(MediaFormat info) {
            final Map<String, Object> map = info.getMap();

            Size blockSize = Utils.parseSize(map.get("block-size"), new Size(8, 8));
            int BS = blockSize.getWidth() * blockSize.getHeight();

            Range<Integer> counts = Utils.parseIntRange(map.get("block-count-range"), null);
            int FS = counts == null ? 0 : BS * counts.getUpper();

            Range<Long> blockRates =
                Utils.parseLongRange(map.get("blocks-per-second-range"), null);
            long SR = blockRates == null ? 0 : BS * blockRates.getUpper();

            Pair<Range<Integer>, Range<Integer>> dimensionRanges =
                parseWidthHeightRanges(map.get("size-range"));
            int D = dimensionRanges == null ? 0 : Math.max(
                    dimensionRanges.first.getUpper(), dimensionRanges.second.getUpper());

            Range<Integer> bitRates = Utils.parseIntRange(map.get("bitrate-range"), null);
            int BR = bitRates == null ? 0 : Utils.divUp(bitRates.getUpper(), 1000);

            if (SR <=      829440 && FS <=    36864 && BR <=    200 && D <=   512)
                return CodecProfileLevel.VP9Level1;
            if (SR <=     2764800 && FS <=    73728 && BR <=    800 && D <=   768)
                return CodecProfileLevel.VP9Level11;
            if (SR <=     4608000 && FS <=   122880 && BR <=   1800 && D <=   960)
                return CodecProfileLevel.VP9Level2;
            if (SR <=     9216000 && FS <=   245760 && BR <=   3600 && D <=  1344)
                return CodecProfileLevel.VP9Level21;
            if (SR <=    20736000 && FS <=   552960 && BR <=   7200 && D <=  2048)
                return CodecProfileLevel.VP9Level3;
            if (SR <=    36864000 && FS <=   983040 && BR <=  12000 && D <=  2752)
                return CodecProfileLevel.VP9Level31;
            if (SR <=    83558400 && FS <=  2228224 && BR <=  18000 && D <=  4160)
                return CodecProfileLevel.VP9Level4;
            if (SR <=   160432128 && FS <=  2228224 && BR <=  30000 && D <=  4160)
                return CodecProfileLevel.VP9Level41;
            if (SR <=   311951360 && FS <=  8912896 && BR <=  60000 && D <=  8384)
                return CodecProfileLevel.VP9Level5;
            if (SR <=   588251136 && FS <=  8912896 && BR <= 120000 && D <=  8384)
                return CodecProfileLevel.VP9Level51;
            if (SR <=  1176502272 && FS <=  8912896 && BR <= 180000 && D <=  8384)
                return CodecProfileLevel.VP9Level52;
            if (SR <=  1176502272 && FS <= 35651584 && BR <= 180000 && D <= 16832)
                return CodecProfileLevel.VP9Level6;
            if (SR <= 2353004544L && FS <= 35651584 && BR <= 240000 && D <= 16832)
                return CodecProfileLevel.VP9Level61;
            if (SR <= 4706009088L && FS <= 35651584 && BR <= 480000 && D <= 16832)
                return CodecProfileLevel.VP9Level62;
            // returning largest level
            return CodecProfileLevel.VP9Level62;
        }

        private void parseFromInfo(MediaFormat info) {
            final Map<String, Object> map = info.getMap();
            Size blockSize = new Size(mBlockWidth, mBlockHeight);
            Size alignment = new Size(mWidthAlignment, mHeightAlignment);
            Range<Integer> counts = null, widths = null, heights = null;
            Range<Integer> frameRates = null, bitRates = null;
            Range<Long> blockRates = null;
            Range<Rational> ratios = null, blockRatios = null;

            blockSize = Utils.parseSize(map.get("block-size"), blockSize);
            alignment = Utils.parseSize(map.get("alignment"), alignment);
            counts = Utils.parseIntRange(map.get("block-count-range"), null);
            blockRates =
                Utils.parseLongRange(map.get("blocks-per-second-range"), null);
            mMeasuredFrameRates = getMeasuredFrameRates(map);
            mPerformancePoints = getPerformancePoints(map);
            Pair<Range<Integer>, Range<Integer>> sizeRanges =
                parseWidthHeightRanges(map.get("size-range"));
            if (sizeRanges != null) {
                widths = sizeRanges.first;
                heights = sizeRanges.second;
            }
            // for now this just means using the smaller max size as 2nd
            // upper limit.
            // for now we are keeping the profile specific "width/height
            // in macroblocks" limits.
            if (map.containsKey("feature-can-swap-width-height")) {
                if (widths != null) {
                    mSmallerDimensionUpperLimit =
                        Math.min(widths.getUpper(), heights.getUpper());
                    widths = heights = widths.extend(heights);
                } else {
                    Log.w(TAG, "feature can-swap-width-height is best used with size-range");
                    mSmallerDimensionUpperLimit =
                        Math.min(mWidthRange.getUpper(), mHeightRange.getUpper());
                    mWidthRange = mHeightRange = mWidthRange.extend(mHeightRange);
                }
            }

            ratios = Utils.parseRationalRange(
                    map.get("block-aspect-ratio-range"), null);
            blockRatios = Utils.parseRationalRange(
                    map.get("pixel-aspect-ratio-range"), null);
            frameRates = Utils.parseIntRange(map.get("frame-rate-range"), null);
            if (frameRates != null) {
                try {
                    frameRates = frameRates.intersect(FRAME_RATE_RANGE);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "frame rate range (" + frameRates
                            + ") is out of limits: " + FRAME_RATE_RANGE);
                    frameRates = null;
                }
            }
            bitRates = Utils.parseIntRange(map.get("bitrate-range"), null);
            if (bitRates != null) {
                try {
                    bitRates = bitRates.intersect(BITRATE_RANGE);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG,  "bitrate range (" + bitRates
                            + ") is out of limits: " + BITRATE_RANGE);
                    bitRates = null;
                }
            }

            checkPowerOfTwo(
                    blockSize.getWidth(), "block-size width must be power of two");
            checkPowerOfTwo(
                    blockSize.getHeight(), "block-size height must be power of two");

            checkPowerOfTwo(
                    alignment.getWidth(), "alignment width must be power of two");
            checkPowerOfTwo(
                    alignment.getHeight(), "alignment height must be power of two");

            // update block-size and alignment
            applyMacroBlockLimits(
                    Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Long.MAX_VALUE, blockSize.getWidth(), blockSize.getHeight(),
                    alignment.getWidth(), alignment.getHeight());

            if ((mParent.mError & ERROR_UNSUPPORTED) != 0 || mAllowMbOverride) {
                // codec supports profiles that we don't know.
                // Use supplied values clipped to platform limits
                if (widths != null) {
                    mWidthRange = getSizeRange().intersect(widths);
                }
                if (heights != null) {
                    mHeightRange = getSizeRange().intersect(heights);
                }
                if (counts != null) {
                    mBlockCountRange = POSITIVE_INTEGERS.intersect(
                            Utils.factorRange(counts, mBlockWidth * mBlockHeight
                                    / blockSize.getWidth() / blockSize.getHeight()));
                }
                if (blockRates != null) {
                    mBlocksPerSecondRange = POSITIVE_LONGS.intersect(
                            Utils.factorRange(blockRates, mBlockWidth * mBlockHeight
                                    / blockSize.getWidth() / blockSize.getHeight()));
                }
                if (blockRatios != null) {
                    mBlockAspectRatioRange = POSITIVE_RATIONALS.intersect(
                            Utils.scaleRange(blockRatios,
                                    mBlockHeight / blockSize.getHeight(),
                                    mBlockWidth / blockSize.getWidth()));
                }
                if (ratios != null) {
                    mAspectRatioRange = POSITIVE_RATIONALS.intersect(ratios);
                }
                if (frameRates != null) {
                    mFrameRateRange = FRAME_RATE_RANGE.intersect(frameRates);
                }
                if (bitRates != null) {
                    // only allow bitrate override if unsupported profiles were encountered
                    if ((mParent.mError & ERROR_UNSUPPORTED) != 0) {
                        mBitrateRange = BITRATE_RANGE.intersect(bitRates);
                    } else {
                        mBitrateRange = mBitrateRange.intersect(bitRates);
                    }
                }
            } else {
                // no unsupported profile/levels, so restrict values to known limits
                if (widths != null) {
                    mWidthRange = mWidthRange.intersect(widths);
                }
                if (heights != null) {
                    mHeightRange = mHeightRange.intersect(heights);
                }
                if (counts != null) {
                    mBlockCountRange = mBlockCountRange.intersect(
                            Utils.factorRange(counts, mBlockWidth * mBlockHeight
                                    / blockSize.getWidth() / blockSize.getHeight()));
                }
                if (blockRates != null) {
                    mBlocksPerSecondRange = mBlocksPerSecondRange.intersect(
                            Utils.factorRange(blockRates, mBlockWidth * mBlockHeight
                                    / blockSize.getWidth() / blockSize.getHeight()));
                }
                if (blockRatios != null) {
                    mBlockAspectRatioRange = mBlockAspectRatioRange.intersect(
                            Utils.scaleRange(blockRatios,
                                    mBlockHeight / blockSize.getHeight(),
                                    mBlockWidth / blockSize.getWidth()));
                }
                if (ratios != null) {
                    mAspectRatioRange = mAspectRatioRange.intersect(ratios);
                }
                if (frameRates != null) {
                    mFrameRateRange = mFrameRateRange.intersect(frameRates);
                }
                if (bitRates != null) {
                    mBitrateRange = mBitrateRange.intersect(bitRates);
                }
            }
            updateLimits();
        }

        private void applyBlockLimits(
                int blockWidth, int blockHeight,
                Range<Integer> counts, Range<Long> rates, Range<Rational> ratios) {
            checkPowerOfTwo(blockWidth, "blockWidth must be a power of two");
            checkPowerOfTwo(blockHeight, "blockHeight must be a power of two");

            final int newBlockWidth = Math.max(blockWidth, mBlockWidth);
            final int newBlockHeight = Math.max(blockHeight, mBlockHeight);

            // factor will always be a power-of-2
            int factor =
                newBlockWidth * newBlockHeight / mBlockWidth / mBlockHeight;
            if (factor != 1) {
                mBlockCountRange = Utils.factorRange(mBlockCountRange, factor);
                mBlocksPerSecondRange = Utils.factorRange(
                        mBlocksPerSecondRange, factor);
                mBlockAspectRatioRange = Utils.scaleRange(
                        mBlockAspectRatioRange,
                        newBlockHeight / mBlockHeight,
                        newBlockWidth / mBlockWidth);
                mHorizontalBlockRange = Utils.factorRange(
                        mHorizontalBlockRange, newBlockWidth / mBlockWidth);
                mVerticalBlockRange = Utils.factorRange(
                        mVerticalBlockRange, newBlockHeight / mBlockHeight);
            }
            factor = newBlockWidth * newBlockHeight / blockWidth / blockHeight;
            if (factor != 1) {
                counts = Utils.factorRange(counts, factor);
                rates = Utils.factorRange(rates, factor);
                ratios = Utils.scaleRange(
                        ratios, newBlockHeight / blockHeight,
                        newBlockWidth / blockWidth);
            }
            mBlockCountRange = mBlockCountRange.intersect(counts);
            mBlocksPerSecondRange = mBlocksPerSecondRange.intersect(rates);
            mBlockAspectRatioRange = mBlockAspectRatioRange.intersect(ratios);
            mBlockWidth = newBlockWidth;
            mBlockHeight = newBlockHeight;
        }

        private void applyAlignment(int widthAlignment, int heightAlignment) {
            checkPowerOfTwo(widthAlignment, "widthAlignment must be a power of two");
            checkPowerOfTwo(heightAlignment, "heightAlignment must be a power of two");

            if (widthAlignment > mBlockWidth || heightAlignment > mBlockHeight) {
                // maintain assumption that 0 < alignment <= block-size
                applyBlockLimits(
                        Math.max(widthAlignment, mBlockWidth),
                        Math.max(heightAlignment, mBlockHeight),
                        POSITIVE_INTEGERS, POSITIVE_LONGS, POSITIVE_RATIONALS);
            }

            mWidthAlignment = Math.max(widthAlignment, mWidthAlignment);
            mHeightAlignment = Math.max(heightAlignment, mHeightAlignment);

            mWidthRange = Utils.alignRange(mWidthRange, mWidthAlignment);
            mHeightRange = Utils.alignRange(mHeightRange, mHeightAlignment);
        }

        private void updateLimits() {
            // pixels -> blocks <- counts
            mHorizontalBlockRange = mHorizontalBlockRange.intersect(
                    Utils.factorRange(mWidthRange, mBlockWidth));
            mHorizontalBlockRange = mHorizontalBlockRange.intersect(
                    Range.create(
                            mBlockCountRange.getLower() / mVerticalBlockRange.getUpper(),
                            mBlockCountRange.getUpper() / mVerticalBlockRange.getLower()));
            mVerticalBlockRange = mVerticalBlockRange.intersect(
                    Utils.factorRange(mHeightRange, mBlockHeight));
            mVerticalBlockRange = mVerticalBlockRange.intersect(
                    Range.create(
                            mBlockCountRange.getLower() / mHorizontalBlockRange.getUpper(),
                            mBlockCountRange.getUpper() / mHorizontalBlockRange.getLower()));
            mBlockCountRange = mBlockCountRange.intersect(
                    Range.create(
                            mHorizontalBlockRange.getLower()
                                    * mVerticalBlockRange.getLower(),
                            mHorizontalBlockRange.getUpper()
                                    * mVerticalBlockRange.getUpper()));
            mBlockAspectRatioRange = mBlockAspectRatioRange.intersect(
                    new Rational(
                            mHorizontalBlockRange.getLower(), mVerticalBlockRange.getUpper()),
                    new Rational(
                            mHorizontalBlockRange.getUpper(), mVerticalBlockRange.getLower()));

            // blocks -> pixels
            mWidthRange = mWidthRange.intersect(
                    (mHorizontalBlockRange.getLower() - 1) * mBlockWidth + mWidthAlignment,
                    mHorizontalBlockRange.getUpper() * mBlockWidth);
            mHeightRange = mHeightRange.intersect(
                    (mVerticalBlockRange.getLower() - 1) * mBlockHeight + mHeightAlignment,
                    mVerticalBlockRange.getUpper() * mBlockHeight);
            mAspectRatioRange = mAspectRatioRange.intersect(
                    new Rational(mWidthRange.getLower(), mHeightRange.getUpper()),
                    new Rational(mWidthRange.getUpper(), mHeightRange.getLower()));

            mSmallerDimensionUpperLimit = Math.min(
                    mSmallerDimensionUpperLimit,
                    Math.min(mWidthRange.getUpper(), mHeightRange.getUpper()));

            // blocks -> rate
            mBlocksPerSecondRange = mBlocksPerSecondRange.intersect(
                    mBlockCountRange.getLower() * (long)mFrameRateRange.getLower(),
                    mBlockCountRange.getUpper() * (long)mFrameRateRange.getUpper());
            mFrameRateRange = mFrameRateRange.intersect(
                    (int)(mBlocksPerSecondRange.getLower()
                            / mBlockCountRange.getUpper()),
                    (int)(mBlocksPerSecondRange.getUpper()
                            / (double)mBlockCountRange.getLower()));
        }

        private void applyMacroBlockLimits(
                int maxHorizontalBlocks, int maxVerticalBlocks,
                int maxBlocks, long maxBlocksPerSecond,
                int blockWidth, int blockHeight,
                int widthAlignment, int heightAlignment) {
            applyMacroBlockLimits(
                    1 /* minHorizontalBlocks */, 1 /* minVerticalBlocks */,
                    maxHorizontalBlocks, maxVerticalBlocks,
                    maxBlocks, maxBlocksPerSecond,
                    blockWidth, blockHeight, widthAlignment, heightAlignment);
        }

        private void applyMacroBlockLimits(
                int minHorizontalBlocks, int minVerticalBlocks,
                int maxHorizontalBlocks, int maxVerticalBlocks,
                int maxBlocks, long maxBlocksPerSecond,
                int blockWidth, int blockHeight,
                int widthAlignment, int heightAlignment) {
            applyAlignment(widthAlignment, heightAlignment);
            applyBlockLimits(
                    blockWidth, blockHeight, Range.create(1, maxBlocks),
                    Range.create(1L, maxBlocksPerSecond),
                    Range.create(
                            new Rational(1, maxVerticalBlocks),
                            new Rational(maxHorizontalBlocks, 1)));
            mHorizontalBlockRange =
                    mHorizontalBlockRange.intersect(
                            Utils.divUp(minHorizontalBlocks, (mBlockWidth / blockWidth)),
                            maxHorizontalBlocks / (mBlockWidth / blockWidth));
            mVerticalBlockRange =
                    mVerticalBlockRange.intersect(
                            Utils.divUp(minVerticalBlocks, (mBlockHeight / blockHeight)),
                            maxVerticalBlocks / (mBlockHeight / blockHeight));
        }

        private void applyLevelLimits() {
            long maxBlocksPerSecond = 0;
            int maxBlocks = 0;
            int maxBps = 0;
            int maxDPBBlocks = 0;

            int errors = ERROR_NONE_SUPPORTED;
            CodecProfileLevel[] profileLevels = mParent.profileLevels;
            String mime = mParent.getMimeType();

            if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                maxBlocks = 99;
                maxBlocksPerSecond = 1485;
                maxBps = 64000;
                maxDPBBlocks = 396;
                for (CodecProfileLevel profileLevel: profileLevels) {
                    int MBPS = 0, FS = 0, BR = 0, DPB = 0;
                    boolean supported = true;
                    switch (profileLevel.level) {
                        case CodecProfileLevel.AVCLevel1:
                            MBPS =     1485; FS =     99; BR =     64; DPB =    396; break;
                        case CodecProfileLevel.AVCLevel1b:
                            MBPS =     1485; FS =     99; BR =    128; DPB =    396; break;
                        case CodecProfileLevel.AVCLevel11:
                            MBPS =     3000; FS =    396; BR =    192; DPB =    900; break;
                        case CodecProfileLevel.AVCLevel12:
                            MBPS =     6000; FS =    396; BR =    384; DPB =   2376; break;
                        case CodecProfileLevel.AVCLevel13:
                            MBPS =    11880; FS =    396; BR =    768; DPB =   2376; break;
                        case CodecProfileLevel.AVCLevel2:
                            MBPS =    11880; FS =    396; BR =   2000; DPB =   2376; break;
                        case CodecProfileLevel.AVCLevel21:
                            MBPS =    19800; FS =    792; BR =   4000; DPB =   4752; break;
                        case CodecProfileLevel.AVCLevel22:
                            MBPS =    20250; FS =   1620; BR =   4000; DPB =   8100; break;
                        case CodecProfileLevel.AVCLevel3:
                            MBPS =    40500; FS =   1620; BR =  10000; DPB =   8100; break;
                        case CodecProfileLevel.AVCLevel31:
                            MBPS =   108000; FS =   3600; BR =  14000; DPB =  18000; break;
                        case CodecProfileLevel.AVCLevel32:
                            MBPS =   216000; FS =   5120; BR =  20000; DPB =  20480; break;
                        case CodecProfileLevel.AVCLevel4:
                            MBPS =   245760; FS =   8192; BR =  20000; DPB =  32768; break;
                        case CodecProfileLevel.AVCLevel41:
                            MBPS =   245760; FS =   8192; BR =  50000; DPB =  32768; break;
                        case CodecProfileLevel.AVCLevel42:
                            MBPS =   522240; FS =   8704; BR =  50000; DPB =  34816; break;
                        case CodecProfileLevel.AVCLevel5:
                            MBPS =   589824; FS =  22080; BR = 135000; DPB = 110400; break;
                        case CodecProfileLevel.AVCLevel51:
                            MBPS =   983040; FS =  36864; BR = 240000; DPB = 184320; break;
                        case CodecProfileLevel.AVCLevel52:
                            MBPS =  2073600; FS =  36864; BR = 240000; DPB = 184320; break;
                        case CodecProfileLevel.AVCLevel6:
                            MBPS =  4177920; FS = 139264; BR = 240000; DPB = 696320; break;
                        case CodecProfileLevel.AVCLevel61:
                            MBPS =  8355840; FS = 139264; BR = 480000; DPB = 696320; break;
                        case CodecProfileLevel.AVCLevel62:
                            MBPS = 16711680; FS = 139264; BR = 800000; DPB = 696320; break;
                        default:
                            Log.w(TAG, "Unrecognized level "
                                    + profileLevel.level + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.AVCProfileConstrainedHigh:
                        case CodecProfileLevel.AVCProfileHigh:
                            BR *= 1250; break;
                        case CodecProfileLevel.AVCProfileHigh10:
                            BR *= 3000; break;
                        case CodecProfileLevel.AVCProfileExtended:
                        case CodecProfileLevel.AVCProfileHigh422:
                        case CodecProfileLevel.AVCProfileHigh444:
                            Log.w(TAG, "Unsupported profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNSUPPORTED;
                            supported = false;
                            // fall through - treat as base profile
                        case CodecProfileLevel.AVCProfileConstrainedBaseline:
                        case CodecProfileLevel.AVCProfileBaseline:
                        case CodecProfileLevel.AVCProfileMain:
                            BR *= 1000; break;
                        default:
                            Log.w(TAG, "Unrecognized profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                            BR *= 1000;
                    }
                    if (supported) {
                        errors &= ~ERROR_NONE_SUPPORTED;
                    }
                    maxBlocksPerSecond = Math.max(MBPS, maxBlocksPerSecond);
                    maxBlocks = Math.max(FS, maxBlocks);
                    maxBps = Math.max(BR, maxBps);
                    maxDPBBlocks = Math.max(maxDPBBlocks, DPB);
                }

                int maxLengthInBlocks = (int)(Math.sqrt(maxBlocks * 8));
                applyMacroBlockLimits(
                        maxLengthInBlocks, maxLengthInBlocks,
                        maxBlocks, maxBlocksPerSecond,
                        16 /* blockWidth */, 16 /* blockHeight */,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_MPEG2)) {
                int maxWidth = 11, maxHeight = 9, maxRate = 15;
                maxBlocks = 99;
                maxBlocksPerSecond = 1485;
                maxBps = 64000;
                for (CodecProfileLevel profileLevel: profileLevels) {
                    int MBPS = 0, FS = 0, BR = 0, FR = 0, W = 0, H = 0;
                    boolean supported = true;
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.MPEG2ProfileSimple:
                            switch (profileLevel.level) {
                                case CodecProfileLevel.MPEG2LevelML:
                                    FR = 30; W = 45; H =  36; MBPS =  40500; FS =  1620; BR =  15000; break;
                                default:
                                    Log.w(TAG, "Unrecognized profile/level "
                                            + profileLevel.profile + "/"
                                            + profileLevel.level + " for " + mime);
                                    errors |= ERROR_UNRECOGNIZED;
                            }
                            break;
                        case CodecProfileLevel.MPEG2ProfileMain:
                            switch (profileLevel.level) {
                                case CodecProfileLevel.MPEG2LevelLL:
                                    FR = 30; W = 22; H =  18; MBPS =  11880; FS =   396; BR =  4000; break;
                                case CodecProfileLevel.MPEG2LevelML:
                                    FR = 30; W = 45; H =  36; MBPS =  40500; FS =  1620; BR = 15000; break;
                                case CodecProfileLevel.MPEG2LevelH14:
                                    FR = 60; W = 90; H =  68; MBPS = 183600; FS =  6120; BR = 60000; break;
                                case CodecProfileLevel.MPEG2LevelHL:
                                    FR = 60; W = 120; H = 68; MBPS = 244800; FS =  8160; BR = 80000; break;
                                case CodecProfileLevel.MPEG2LevelHP:
                                    FR = 60; W = 120; H = 68; MBPS = 489600; FS =  8160; BR = 80000; break;
                                default:
                                    Log.w(TAG, "Unrecognized profile/level "
                                            + profileLevel.profile + "/"
                                            + profileLevel.level + " for " + mime);
                                    errors |= ERROR_UNRECOGNIZED;
                            }
                            break;
                        case CodecProfileLevel.MPEG2Profile422:
                        case CodecProfileLevel.MPEG2ProfileSNR:
                        case CodecProfileLevel.MPEG2ProfileSpatial:
                        case CodecProfileLevel.MPEG2ProfileHigh:
                            Log.i(TAG, "Unsupported profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNSUPPORTED;
                            supported = false;
                            break;
                        default:
                            Log.w(TAG, "Unrecognized profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    if (supported) {
                        errors &= ~ERROR_NONE_SUPPORTED;
                    }
                    maxBlocksPerSecond = Math.max(MBPS, maxBlocksPerSecond);
                    maxBlocks = Math.max(FS, maxBlocks);
                    maxBps = Math.max(BR * 1000, maxBps);
                    maxWidth = Math.max(W, maxWidth);
                    maxHeight = Math.max(H, maxHeight);
                    maxRate = Math.max(FR, maxRate);
                }
                applyMacroBlockLimits(maxWidth, maxHeight,
                        maxBlocks, maxBlocksPerSecond,
                        16 /* blockWidth */, 16 /* blockHeight */,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
                mFrameRateRange = mFrameRateRange.intersect(12, maxRate);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_MPEG4)) {
                int maxWidth = 11, maxHeight = 9, maxRate = 15;
                maxBlocks = 99;
                maxBlocksPerSecond = 1485;
                maxBps = 64000;
                for (CodecProfileLevel profileLevel: profileLevels) {
                    int MBPS = 0, FS = 0, BR = 0, FR = 0, W = 0, H = 0;
                    boolean strict = false; // true: W, H and FR are individual max limits
                    boolean supported = true;
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.MPEG4ProfileSimple:
                            switch (profileLevel.level) {
                                case CodecProfileLevel.MPEG4Level0:
                                    strict = true;
                                    FR = 15; W = 11; H =  9; MBPS =  1485; FS =  99; BR =  64; break;
                                case CodecProfileLevel.MPEG4Level1:
                                    FR = 30; W = 11; H =  9; MBPS =  1485; FS =  99; BR =  64; break;
                                case CodecProfileLevel.MPEG4Level0b:
                                    strict = true;
                                    FR = 15; W = 11; H =  9; MBPS =  1485; FS =  99; BR = 128; break;
                                case CodecProfileLevel.MPEG4Level2:
                                    FR = 30; W = 22; H = 18; MBPS =  5940; FS = 396; BR = 128; break;
                                case CodecProfileLevel.MPEG4Level3:
                                    FR = 30; W = 22; H = 18; MBPS = 11880; FS = 396; BR = 384; break;
                                case CodecProfileLevel.MPEG4Level4a:
                                    FR = 30; W = 40; H = 30; MBPS = 36000; FS = 1200; BR = 4000; break;
                                case CodecProfileLevel.MPEG4Level5:
                                    FR = 30; W = 45; H = 36; MBPS = 40500; FS = 1620; BR = 8000; break;
                                case CodecProfileLevel.MPEG4Level6:
                                    FR = 30; W = 80; H = 45; MBPS = 108000; FS = 3600; BR = 12000; break;
                                default:
                                    Log.w(TAG, "Unrecognized profile/level "
                                            + profileLevel.profile + "/"
                                            + profileLevel.level + " for " + mime);
                                    errors |= ERROR_UNRECOGNIZED;
                            }
                            break;
                        case CodecProfileLevel.MPEG4ProfileAdvancedSimple:
                            switch (profileLevel.level) {
                                case CodecProfileLevel.MPEG4Level0:
                                case CodecProfileLevel.MPEG4Level1:
                                    FR = 30; W = 11; H =  9; MBPS =  2970; FS =   99; BR =  128; break;
                                case CodecProfileLevel.MPEG4Level2:
                                    FR = 30; W = 22; H = 18; MBPS =  5940; FS =  396; BR =  384; break;
                                case CodecProfileLevel.MPEG4Level3:
                                    FR = 30; W = 22; H = 18; MBPS = 11880; FS =  396; BR =  768; break;
                                case CodecProfileLevel.MPEG4Level3b:
                                    FR = 30; W = 22; H = 18; MBPS = 11880; FS =  396; BR = 1500; break;
                                case CodecProfileLevel.MPEG4Level4:
                                    FR = 30; W = 44; H = 36; MBPS = 23760; FS =  792; BR = 3000; break;
                                case CodecProfileLevel.MPEG4Level5:
                                    FR = 30; W = 45; H = 36; MBPS = 48600; FS = 1620; BR = 8000; break;
                                default:
                                    Log.w(TAG, "Unrecognized profile/level "
                                            + profileLevel.profile + "/"
                                            + profileLevel.level + " for " + mime);
                                    errors |= ERROR_UNRECOGNIZED;
                            }
                            break;
                        case CodecProfileLevel.MPEG4ProfileMain:             // 2-4
                        case CodecProfileLevel.MPEG4ProfileNbit:             // 2
                        case CodecProfileLevel.MPEG4ProfileAdvancedRealTime: // 1-4
                        case CodecProfileLevel.MPEG4ProfileCoreScalable:     // 1-3
                        case CodecProfileLevel.MPEG4ProfileAdvancedCoding:   // 1-4
                        case CodecProfileLevel.MPEG4ProfileCore:             // 1-2
                        case CodecProfileLevel.MPEG4ProfileAdvancedCore:     // 1-4
                        case CodecProfileLevel.MPEG4ProfileSimpleScalable:   // 0-2
                        case CodecProfileLevel.MPEG4ProfileHybrid:           // 1-2

                        // Studio profiles are not supported by our codecs.

                        // Only profiles that can decode simple object types are considered.
                        // The following profiles are not able to.
                        case CodecProfileLevel.MPEG4ProfileBasicAnimated:    // 1-2
                        case CodecProfileLevel.MPEG4ProfileScalableTexture:  // 1
                        case CodecProfileLevel.MPEG4ProfileSimpleFace:       // 1-2
                        case CodecProfileLevel.MPEG4ProfileAdvancedScalable: // 1-3
                        case CodecProfileLevel.MPEG4ProfileSimpleFBA:        // 1-2
                            Log.i(TAG, "Unsupported profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNSUPPORTED;
                            supported = false;
                            break;
                        default:
                            Log.w(TAG, "Unrecognized profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    if (supported) {
                        errors &= ~ERROR_NONE_SUPPORTED;
                    }
                    maxBlocksPerSecond = Math.max(MBPS, maxBlocksPerSecond);
                    maxBlocks = Math.max(FS, maxBlocks);
                    maxBps = Math.max(BR * 1000, maxBps);
                    if (strict) {
                        maxWidth = Math.max(W, maxWidth);
                        maxHeight = Math.max(H, maxHeight);
                        maxRate = Math.max(FR, maxRate);
                    } else {
                        // assuming max 60 fps frame rate and 1:2 aspect ratio
                        int maxDim = (int)Math.sqrt(FS * 2);
                        maxWidth = Math.max(maxDim, maxWidth);
                        maxHeight = Math.max(maxDim, maxHeight);
                        maxRate = Math.max(Math.max(FR, 60), maxRate);
                    }
                }
                applyMacroBlockLimits(maxWidth, maxHeight,
                        maxBlocks, maxBlocksPerSecond,
                        16 /* blockWidth */, 16 /* blockHeight */,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
                mFrameRateRange = mFrameRateRange.intersect(12, maxRate);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_H263)) {
                int maxWidth = 11, maxHeight = 9, maxRate = 15;
                int minWidth = maxWidth, minHeight = maxHeight;
                int minAlignment = 16;
                maxBlocks = 99;
                maxBlocksPerSecond = 1485;
                maxBps = 64000;
                for (CodecProfileLevel profileLevel: profileLevels) {
                    int MBPS = 0, BR = 0, FR = 0, W = 0, H = 0, minW = minWidth, minH = minHeight;
                    boolean strict = false; // true: support only sQCIF, QCIF (maybe CIF)
                    switch (profileLevel.level) {
                        case CodecProfileLevel.H263Level10:
                            strict = true; // only supports sQCIF & QCIF
                            FR = 15; W = 11; H =  9; BR =   1; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level20:
                            strict = true; // only supports sQCIF, QCIF & CIF
                            FR = 30; W = 22; H = 18; BR =   2; MBPS =  W * H * 15; break;
                        case CodecProfileLevel.H263Level30:
                            strict = true; // only supports sQCIF, QCIF & CIF
                            FR = 30; W = 22; H = 18; BR =   6; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level40:
                            strict = true; // only supports sQCIF, QCIF & CIF
                            FR = 30; W = 22; H = 18; BR =  32; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level45:
                            // only implies level 10 support
                            strict = profileLevel.profile == CodecProfileLevel.H263ProfileBaseline
                                    || profileLevel.profile ==
                                            CodecProfileLevel.H263ProfileBackwardCompatible;
                            if (!strict) {
                                minW = 1; minH = 1; minAlignment = 4;
                            }
                            FR = 15; W = 11; H =  9; BR =   2; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level50:
                            // only supports 50fps for H > 15
                            minW = 1; minH = 1; minAlignment = 4;
                            FR = 60; W = 22; H = 18; BR =  64; MBPS =  W * H * 50; break;
                        case CodecProfileLevel.H263Level60:
                            // only supports 50fps for H > 15
                            minW = 1; minH = 1; minAlignment = 4;
                            FR = 60; W = 45; H = 18; BR = 128; MBPS =  W * H * 50; break;
                        case CodecProfileLevel.H263Level70:
                            // only supports 50fps for H > 30
                            minW = 1; minH = 1; minAlignment = 4;
                            FR = 60; W = 45; H = 36; BR = 256; MBPS =  W * H * 50; break;
                        default:
                            Log.w(TAG, "Unrecognized profile/level " + profileLevel.profile
                                    + "/" + profileLevel.level + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.H263ProfileBackwardCompatible:
                        case CodecProfileLevel.H263ProfileBaseline:
                        case CodecProfileLevel.H263ProfileH320Coding:
                        case CodecProfileLevel.H263ProfileHighCompression:
                        case CodecProfileLevel.H263ProfileHighLatency:
                        case CodecProfileLevel.H263ProfileInterlace:
                        case CodecProfileLevel.H263ProfileInternet:
                        case CodecProfileLevel.H263ProfileISWV2:
                        case CodecProfileLevel.H263ProfileISWV3:
                            break;
                        default:
                            Log.w(TAG, "Unrecognized profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    if (strict) {
                        // Strict levels define sub-QCIF min size and enumerated sizes. We cannot
                        // express support for "only sQCIF & QCIF (& CIF)" using VideoCapabilities
                        // but we can express "only QCIF (& CIF)", so set minimume size at QCIF.
                        // minW = 8; minH = 6;
                        minW = 11; minH = 9;
                    } else {
                        // any support for non-strict levels (including unrecognized profiles or
                        // levels) allow custom frame size support beyond supported limits
                        // (other than bitrate)
                        mAllowMbOverride = true;
                    }
                    errors &= ~ERROR_NONE_SUPPORTED;
                    maxBlocksPerSecond = Math.max(MBPS, maxBlocksPerSecond);
                    maxBlocks = Math.max(W * H, maxBlocks);
                    maxBps = Math.max(BR * 64000, maxBps);
                    maxWidth = Math.max(W, maxWidth);
                    maxHeight = Math.max(H, maxHeight);
                    maxRate = Math.max(FR, maxRate);
                    minWidth = Math.min(minW, minWidth);
                    minHeight = Math.min(minH, minHeight);
                }
                // unless we encountered custom frame size support, limit size to QCIF and CIF
                // using aspect ratio.
                if (!mAllowMbOverride) {
                    mBlockAspectRatioRange =
                        Range.create(new Rational(11, 9), new Rational(11, 9));
                }
                applyMacroBlockLimits(
                        minWidth, minHeight,
                        maxWidth, maxHeight,
                        maxBlocks, maxBlocksPerSecond,
                        16 /* blockWidth */, 16 /* blockHeight */,
                        minAlignment /* widthAlignment */, minAlignment /* heightAlignment */);
                mFrameRateRange = Range.create(1, maxRate);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_VP8)) {
                maxBlocks = Integer.MAX_VALUE;
                maxBlocksPerSecond = Integer.MAX_VALUE;

                // TODO: set to 100Mbps for now, need a number for VP8
                maxBps = 100000000;

                // profile levels are not indicative for VPx, but verify
                // them nonetheless
                for (CodecProfileLevel profileLevel: profileLevels) {
                    switch (profileLevel.level) {
                        case CodecProfileLevel.VP8Level_Version0:
                        case CodecProfileLevel.VP8Level_Version1:
                        case CodecProfileLevel.VP8Level_Version2:
                        case CodecProfileLevel.VP8Level_Version3:
                            break;
                        default:
                            Log.w(TAG, "Unrecognized level "
                                    + profileLevel.level + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.VP8ProfileMain:
                            break;
                        default:
                            Log.w(TAG, "Unrecognized profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    errors &= ~ERROR_NONE_SUPPORTED;
                }

                final int blockSize = 16;
                applyMacroBlockLimits(Short.MAX_VALUE, Short.MAX_VALUE,
                        maxBlocks, maxBlocksPerSecond, blockSize, blockSize,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_VP9)) {
                maxBlocksPerSecond = 829440;
                maxBlocks = 36864;
                maxBps = 200000;
                int maxDim = 512;

                for (CodecProfileLevel profileLevel: profileLevels) {
                    long SR = 0; // luma sample rate
                    int FS = 0;  // luma picture size
                    int BR = 0;  // bit rate kbps
                    int D = 0;   // luma dimension
                    switch (profileLevel.level) {
                        case CodecProfileLevel.VP9Level1:
                            SR =      829440; FS =    36864; BR =    200; D =   512; break;
                        case CodecProfileLevel.VP9Level11:
                            SR =     2764800; FS =    73728; BR =    800; D =   768; break;
                        case CodecProfileLevel.VP9Level2:
                            SR =     4608000; FS =   122880; BR =   1800; D =   960; break;
                        case CodecProfileLevel.VP9Level21:
                            SR =     9216000; FS =   245760; BR =   3600; D =  1344; break;
                        case CodecProfileLevel.VP9Level3:
                            SR =    20736000; FS =   552960; BR =   7200; D =  2048; break;
                        case CodecProfileLevel.VP9Level31:
                            SR =    36864000; FS =   983040; BR =  12000; D =  2752; break;
                        case CodecProfileLevel.VP9Level4:
                            SR =    83558400; FS =  2228224; BR =  18000; D =  4160; break;
                        case CodecProfileLevel.VP9Level41:
                            SR =   160432128; FS =  2228224; BR =  30000; D =  4160; break;
                        case CodecProfileLevel.VP9Level5:
                            SR =   311951360; FS =  8912896; BR =  60000; D =  8384; break;
                        case CodecProfileLevel.VP9Level51:
                            SR =   588251136; FS =  8912896; BR = 120000; D =  8384; break;
                        case CodecProfileLevel.VP9Level52:
                            SR =  1176502272; FS =  8912896; BR = 180000; D =  8384; break;
                        case CodecProfileLevel.VP9Level6:
                            SR =  1176502272; FS = 35651584; BR = 180000; D = 16832; break;
                        case CodecProfileLevel.VP9Level61:
                            SR = 2353004544L; FS = 35651584; BR = 240000; D = 16832; break;
                        case CodecProfileLevel.VP9Level62:
                            SR = 4706009088L; FS = 35651584; BR = 480000; D = 16832; break;
                        default:
                            Log.w(TAG, "Unrecognized level "
                                    + profileLevel.level + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.VP9Profile0:
                        case CodecProfileLevel.VP9Profile1:
                        case CodecProfileLevel.VP9Profile2:
                        case CodecProfileLevel.VP9Profile3:
                        case CodecProfileLevel.VP9Profile2HDR:
                        case CodecProfileLevel.VP9Profile3HDR:
                        case CodecProfileLevel.VP9Profile2HDR10Plus:
                        case CodecProfileLevel.VP9Profile3HDR10Plus:
                            break;
                        default:
                            Log.w(TAG, "Unrecognized profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    errors &= ~ERROR_NONE_SUPPORTED;
                    maxBlocksPerSecond = Math.max(SR, maxBlocksPerSecond);
                    maxBlocks = Math.max(FS, maxBlocks);
                    maxBps = Math.max(BR * 1000, maxBps);
                    maxDim = Math.max(D, maxDim);
                }

                final int blockSize = 8;
                int maxLengthInBlocks = Utils.divUp(maxDim, blockSize);
                maxBlocks = Utils.divUp(maxBlocks, blockSize * blockSize);
                maxBlocksPerSecond = Utils.divUp(maxBlocksPerSecond, blockSize * blockSize);

                applyMacroBlockLimits(
                        maxLengthInBlocks, maxLengthInBlocks,
                        maxBlocks, maxBlocksPerSecond,
                        blockSize, blockSize,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                // CTBs are at least 8x8 so use 8x8 block size
                maxBlocks = 36864 >> 6; // 192x192 pixels == 576 8x8 blocks
                maxBlocksPerSecond = maxBlocks * 15;
                maxBps = 128000;
                for (CodecProfileLevel profileLevel: profileLevels) {
                    double FR = 0;
                    int FS = 0;
                    int BR = 0;
                    switch (profileLevel.level) {
                        /* The HEVC spec talks only in a very convoluted manner about the
                           existence of levels 1-3.1 for High tier, which could also be
                           understood as 'decoders and encoders should treat these levels
                           as if they were Main tier', so we do that. */
                        case CodecProfileLevel.HEVCMainTierLevel1:
                        case CodecProfileLevel.HEVCHighTierLevel1:
                            FR =    15; FS =    36864; BR =    128; break;
                        case CodecProfileLevel.HEVCMainTierLevel2:
                        case CodecProfileLevel.HEVCHighTierLevel2:
                            FR =    30; FS =   122880; BR =   1500; break;
                        case CodecProfileLevel.HEVCMainTierLevel21:
                        case CodecProfileLevel.HEVCHighTierLevel21:
                            FR =    30; FS =   245760; BR =   3000; break;
                        case CodecProfileLevel.HEVCMainTierLevel3:
                        case CodecProfileLevel.HEVCHighTierLevel3:
                            FR =    30; FS =   552960; BR =   6000; break;
                        case CodecProfileLevel.HEVCMainTierLevel31:
                        case CodecProfileLevel.HEVCHighTierLevel31:
                            FR = 33.75; FS =   983040; BR =  10000; break;
                        case CodecProfileLevel.HEVCMainTierLevel4:
                            FR =    30; FS =  2228224; BR =  12000; break;
                        case CodecProfileLevel.HEVCHighTierLevel4:
                            FR =    30; FS =  2228224; BR =  30000; break;
                        case CodecProfileLevel.HEVCMainTierLevel41:
                            FR =    60; FS =  2228224; BR =  20000; break;
                        case CodecProfileLevel.HEVCHighTierLevel41:
                            FR =    60; FS =  2228224; BR =  50000; break;
                        case CodecProfileLevel.HEVCMainTierLevel5:
                            FR =    30; FS =  8912896; BR =  25000; break;
                        case CodecProfileLevel.HEVCHighTierLevel5:
                            FR =    30; FS =  8912896; BR = 100000; break;
                        case CodecProfileLevel.HEVCMainTierLevel51:
                            FR =    60; FS =  8912896; BR =  40000; break;
                        case CodecProfileLevel.HEVCHighTierLevel51:
                            FR =    60; FS =  8912896; BR = 160000; break;
                        case CodecProfileLevel.HEVCMainTierLevel52:
                            FR =   120; FS =  8912896; BR =  60000; break;
                        case CodecProfileLevel.HEVCHighTierLevel52:
                            FR =   120; FS =  8912896; BR = 240000; break;
                        case CodecProfileLevel.HEVCMainTierLevel6:
                            FR =    30; FS = 35651584; BR =  60000; break;
                        case CodecProfileLevel.HEVCHighTierLevel6:
                            FR =    30; FS = 35651584; BR = 240000; break;
                        case CodecProfileLevel.HEVCMainTierLevel61:
                            FR =    60; FS = 35651584; BR = 120000; break;
                        case CodecProfileLevel.HEVCHighTierLevel61:
                            FR =    60; FS = 35651584; BR = 480000; break;
                        case CodecProfileLevel.HEVCMainTierLevel62:
                            FR =   120; FS = 35651584; BR = 240000; break;
                        case CodecProfileLevel.HEVCHighTierLevel62:
                            FR =   120; FS = 35651584; BR = 800000; break;
                        default:
                            Log.w(TAG, "Unrecognized level "
                                    + profileLevel.level + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.HEVCProfileMain:
                        case CodecProfileLevel.HEVCProfileMain10:
                        case CodecProfileLevel.HEVCProfileMainStill:
                        case CodecProfileLevel.HEVCProfileMain10HDR10:
                        case CodecProfileLevel.HEVCProfileMain10HDR10Plus:
                            break;
                        default:
                            Log.w(TAG, "Unrecognized profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }

                    /* DPB logic:
                    if      (width * height <= FS / 4)    DPB = 16;
                    else if (width * height <= FS / 2)    DPB = 12;
                    else if (width * height <= FS * 0.75) DPB = 8;
                    else                                  DPB = 6;
                    */

                    FS >>= 6; // convert pixels to blocks
                    errors &= ~ERROR_NONE_SUPPORTED;
                    maxBlocksPerSecond = Math.max((int)(FR * FS), maxBlocksPerSecond);
                    maxBlocks = Math.max(FS, maxBlocks);
                    maxBps = Math.max(BR * 1000, maxBps);
                }

                int maxLengthInBlocks = (int)(Math.sqrt(maxBlocks * 8));
                applyMacroBlockLimits(
                        maxLengthInBlocks, maxLengthInBlocks,
                        maxBlocks, maxBlocksPerSecond,
                        8 /* blockWidth */, 8 /* blockHeight */,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AV1)) {
                maxBlocksPerSecond = 829440;
                maxBlocks = 36864;
                maxBps = 200000;
                int maxDim = 512;

                // Sample rate, Picture Size, Bit rate and luma dimension for AV1 Codec,
                // corresponding to the definitions in
                // "AV1 Bitstream & Decoding Process Specification", Annex A
                // found at https://aomedia.org/av1-bitstream-and-decoding-process-specification/
                for (CodecProfileLevel profileLevel: profileLevels) {
                    long SR = 0; // luma sample rate
                    int FS = 0;  // luma picture size
                    int BR = 0;  // bit rate kbps
                    int D = 0;   // luma D
                    switch (profileLevel.level) {
                        case CodecProfileLevel.AV1Level2:
                            SR =     5529600; FS =   147456; BR =   1500; D =  2048; break;
                        case CodecProfileLevel.AV1Level21:
                        case CodecProfileLevel.AV1Level22:
                        case CodecProfileLevel.AV1Level23:
                            SR =    10454400; FS =   278784; BR =   3000; D =  2816; break;

                        case CodecProfileLevel.AV1Level3:
                            SR =    24969600; FS =   665856; BR =   6000; D =  4352; break;
                        case CodecProfileLevel.AV1Level31:
                        case CodecProfileLevel.AV1Level32:
                        case CodecProfileLevel.AV1Level33:
                            SR =    39938400; FS =  1065024; BR =  10000; D =  5504; break;

                        case CodecProfileLevel.AV1Level4:
                            SR =    77856768; FS =  2359296; BR =  12000; D =  6144; break;
                        case CodecProfileLevel.AV1Level41:
                        case CodecProfileLevel.AV1Level42:
                        case CodecProfileLevel.AV1Level43:
                            SR =   155713536; FS =  2359296; BR =  20000; D =  6144; break;

                        case CodecProfileLevel.AV1Level5:
                            SR =   273715200; FS =  8912896; BR =  30000; D =  8192; break;
                        case CodecProfileLevel.AV1Level51:
                            SR =   547430400; FS =  8912896; BR =  40000; D =  8192; break;
                        case CodecProfileLevel.AV1Level52:
                            SR =  1094860800; FS =  8912896; BR =  60000; D =  8192; break;
                        case CodecProfileLevel.AV1Level53:
                            SR =  1176502272; FS =  8912896; BR =  60000; D =  8192; break;

                        case CodecProfileLevel.AV1Level6:
                            SR =  1176502272; FS = 35651584; BR =  60000; D = 16384; break;
                        case CodecProfileLevel.AV1Level61:
                            SR = 2189721600L; FS = 35651584; BR = 100000; D = 16384; break;
                        case CodecProfileLevel.AV1Level62:
                            SR = 4379443200L; FS = 35651584; BR = 160000; D = 16384; break;
                        case CodecProfileLevel.AV1Level63:
                            SR = 4706009088L; FS = 35651584; BR = 160000; D = 16384; break;

                        default:
                            Log.w(TAG, "Unrecognized level "
                                    + profileLevel.level + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.AV1ProfileMain8:
                        case CodecProfileLevel.AV1ProfileMain10:
                        case CodecProfileLevel.AV1ProfileMain10HDR10:
                        case CodecProfileLevel.AV1ProfileMain10HDR10Plus:
                            break;
                        default:
                            Log.w(TAG, "Unrecognized profile "
                                    + profileLevel.profile + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    errors &= ~ERROR_NONE_SUPPORTED;
                    maxBlocksPerSecond = Math.max(SR, maxBlocksPerSecond);
                    maxBlocks = Math.max(FS, maxBlocks);
                    maxBps = Math.max(BR * 1000, maxBps);
                    maxDim = Math.max(D, maxDim);
                }

                final int blockSize = 8;
                int maxLengthInBlocks = Utils.divUp(maxDim, blockSize);
                maxBlocks = Utils.divUp(maxBlocks, blockSize * blockSize);
                maxBlocksPerSecond = Utils.divUp(maxBlocksPerSecond, blockSize * blockSize);
                applyMacroBlockLimits(
                        maxLengthInBlocks, maxLengthInBlocks,
                        maxBlocks, maxBlocksPerSecond,
                        blockSize, blockSize,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
            } else {
                Log.w(TAG, "Unsupported mime " + mime);
                // using minimal bitrate here.  should be overriden by
                // info from media_codecs.xml
                maxBps = 64000;
                errors |= ERROR_UNSUPPORTED;
            }
            mBitrateRange = Range.create(1, maxBps);
            mParent.mError |= errors;
        }
    }

    /**
     * A class that supports querying the encoding capabilities of a codec.
     */
    public static final class EncoderCapabilities {
        /**
         * Returns the supported range of quality values.
         *
         * Quality is implementation-specific. As a general rule, a higher quality
         * setting results in a better image quality and a lower compression ratio.
         */
        public Range<Integer> getQualityRange() {
            return mQualityRange;
        }

        /**
         * Returns the supported range of encoder complexity values.
         * <p>
         * Some codecs may support multiple complexity levels, where higher
         * complexity values use more encoder tools (e.g. perform more
         * intensive calculations) to improve the quality or the compression
         * ratio.  Use a lower value to save power and/or time.
         */
        public Range<Integer> getComplexityRange() {
            return mComplexityRange;
        }

        /** Constant quality mode */
        public static final int BITRATE_MODE_CQ = 0;
        /** Variable bitrate mode */
        public static final int BITRATE_MODE_VBR = 1;
        /** Constant bitrate mode */
        public static final int BITRATE_MODE_CBR = 2;
        /** Constant bitrate mode with frame drops */
        public static final int BITRATE_MODE_CBR_FD =  3;

        private static final Feature[] bitrates = new Feature[] {
            new Feature("VBR", BITRATE_MODE_VBR, true),
            new Feature("CBR", BITRATE_MODE_CBR, false),
            new Feature("CQ",  BITRATE_MODE_CQ,  false),
            new Feature("CBR-FD", BITRATE_MODE_CBR_FD, false)
        };

        private static int parseBitrateMode(String mode) {
            for (Feature feat: bitrates) {
                if (feat.mName.equalsIgnoreCase(mode)) {
                    return feat.mValue;
                }
            }
            return 0;
        }

        /**
         * Query whether a bitrate mode is supported.
         */
        public boolean isBitrateModeSupported(int mode) {
            for (Feature feat: bitrates) {
                if (mode == feat.mValue) {
                    return (mBitControl & (1 << mode)) != 0;
                }
            }
            return false;
        }

        private Range<Integer> mQualityRange;
        private Range<Integer> mComplexityRange;
        private CodecCapabilities mParent;

        /* no public constructor */
        private EncoderCapabilities() { }

        /** @hide */
        public static EncoderCapabilities create(
                MediaFormat info, CodecCapabilities parent) {
            EncoderCapabilities caps = new EncoderCapabilities();
            caps.init(info, parent);
            return caps;
        }

        private void init(MediaFormat info, CodecCapabilities parent) {
            // no support for complexity or quality yet
            mParent = parent;
            mComplexityRange = Range.create(0, 0);
            mQualityRange = Range.create(0, 0);
            mBitControl = (1 << BITRATE_MODE_VBR);

            applyLevelLimits();
            parseFromInfo(info);
        }

        private void applyLevelLimits() {
            String mime = mParent.getMimeType();
            if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_FLAC)) {
                mComplexityRange = Range.create(0, 8);
                mBitControl = (1 << BITRATE_MODE_CQ);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
                    || mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AMR_WB)
                    || mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_G711_ALAW)
                    || mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_G711_MLAW)
                    || mime.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_MSGSM)) {
                mBitControl = (1 << BITRATE_MODE_CBR);
            }
        }

        private int mBitControl;
        private Integer mDefaultComplexity;
        private Integer mDefaultQuality;
        private String mQualityScale;

        private void parseFromInfo(MediaFormat info) {
            Map<String, Object> map = info.getMap();

            if (info.containsKey("complexity-range")) {
                mComplexityRange = Utils
                        .parseIntRange(info.getString("complexity-range"), mComplexityRange);
                // TODO should we limit this to level limits?
            }
            if (info.containsKey("quality-range")) {
                mQualityRange = Utils
                        .parseIntRange(info.getString("quality-range"), mQualityRange);
            }
            if (info.containsKey("feature-bitrate-modes")) {
                mBitControl = 0;
                for (String mode: info.getString("feature-bitrate-modes").split(",")) {
                    mBitControl |= (1 << parseBitrateMode(mode));
                }
            }

            try {
                mDefaultComplexity = Integer.parseInt((String)map.get("complexity-default"));
            } catch (NumberFormatException e) { }

            try {
                mDefaultQuality = Integer.parseInt((String)map.get("quality-default"));
            } catch (NumberFormatException e) { }

            mQualityScale = (String)map.get("quality-scale");
        }

        private boolean supports(
                Integer complexity, Integer quality, Integer profile) {
            boolean ok = true;
            if (ok && complexity != null) {
                ok = mComplexityRange.contains(complexity);
            }
            if (ok && quality != null) {
                ok = mQualityRange.contains(quality);
            }
            if (ok && profile != null) {
                for (CodecProfileLevel pl: mParent.profileLevels) {
                    if (pl.profile == profile) {
                        profile = null;
                        break;
                    }
                }
                ok = profile == null;
            }
            return ok;
        }

        /** @hide */
        public void getDefaultFormat(MediaFormat format) {
            // don't list trivial quality/complexity as default for now
            if (!mQualityRange.getUpper().equals(mQualityRange.getLower())
                    && mDefaultQuality != null) {
                format.setInteger(MediaFormat.KEY_QUALITY, mDefaultQuality);
            }
            if (!mComplexityRange.getUpper().equals(mComplexityRange.getLower())
                    && mDefaultComplexity != null) {
                format.setInteger(MediaFormat.KEY_COMPLEXITY, mDefaultComplexity);
            }
            // bitrates are listed in order of preference
            for (Feature feat: bitrates) {
                if ((mBitControl & (1 << feat.mValue)) != 0) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, feat.mValue);
                    break;
                }
            }
        }

        /** @hide */
        public boolean supportsFormat(MediaFormat format) {
            final Map<String, Object> map = format.getMap();
            final String mime = mParent.getMimeType();

            Integer mode = (Integer)map.get(MediaFormat.KEY_BITRATE_MODE);
            if (mode != null && !isBitrateModeSupported(mode)) {
                return false;
            }

            Integer complexity = (Integer)map.get(MediaFormat.KEY_COMPLEXITY);
            if (MediaFormat.MIMETYPE_AUDIO_FLAC.equalsIgnoreCase(mime)) {
                Integer flacComplexity =
                    (Integer)map.get(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL);
                if (complexity == null) {
                    complexity = flacComplexity;
                } else if (flacComplexity != null && !complexity.equals(flacComplexity)) {
                    throw new IllegalArgumentException(
                            "conflicting values for complexity and " +
                            "flac-compression-level");
                }
            }

            // other audio parameters
            Integer profile = (Integer)map.get(MediaFormat.KEY_PROFILE);
            if (MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(mime)) {
                Integer aacProfile = (Integer)map.get(MediaFormat.KEY_AAC_PROFILE);
                if (profile == null) {
                    profile = aacProfile;
                } else if (aacProfile != null && !aacProfile.equals(profile)) {
                    throw new IllegalArgumentException(
                            "conflicting values for profile and aac-profile");
                }
            }

            Integer quality = (Integer)map.get(MediaFormat.KEY_QUALITY);

            return supports(complexity, quality, profile);
        }
    };

    /**
     * Encapsulates the profiles available for a codec component.
     * <p>You can get a set of {@link MediaCodecInfo.CodecProfileLevel} objects for a given
     * {@link MediaCodecInfo} object from the
     * {@link MediaCodecInfo.CodecCapabilities#profileLevels} field.
     */
    public static final class CodecProfileLevel {
        // These constants were originally in-line with OMX values, but this
        // correspondence is no longer maintained.

        // Profiles and levels for AVC Codec, corresponding to the definitions in
        // "SERIES H: AUDIOVISUAL AND MULTIMEDIA SYSTEMS,
        // Infrastructure of audiovisual services – Coding of moving video
        // Advanced video coding for generic audiovisual services"
        // found at
        // https://www.itu.int/rec/T-REC-H.264-201704-I

        /**
         * AVC Baseline profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileBaseline = 0x01;

        /**
         * AVC Main profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileMain     = 0x02;

        /**
         * AVC Extended profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileExtended = 0x04;

        /**
         * AVC High profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileHigh     = 0x08;

        /**
         * AVC High 10 profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileHigh10   = 0x10;

        /**
         * AVC High 4:2:2 profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileHigh422  = 0x20;

        /**
         * AVC High 4:4:4 profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileHigh444  = 0x40;

        /**
         * AVC Constrained Baseline profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileConstrainedBaseline = 0x10000;

        /**
         * AVC Constrained High profile.
         * See definition in
         * <a href="https://www.itu.int/rec/T-REC-H.264-201704-I">H.264 recommendation</a>,
         * Annex A.
         */
        public static final int AVCProfileConstrainedHigh     = 0x80000;

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
        public static final int AVCLevel52      = 0x10000;
        public static final int AVCLevel6       = 0x20000;
        public static final int AVCLevel61      = 0x40000;
        public static final int AVCLevel62      = 0x80000;

        public static final int H263ProfileBaseline             = 0x01;
        public static final int H263ProfileH320Coding           = 0x02;
        public static final int H263ProfileBackwardCompatible   = 0x04;
        public static final int H263ProfileISWV2                = 0x08;
        public static final int H263ProfileISWV3                = 0x10;
        public static final int H263ProfileHighCompression      = 0x20;
        public static final int H263ProfileInternet             = 0x40;
        public static final int H263ProfileInterlace            = 0x80;
        public static final int H263ProfileHighLatency          = 0x100;

        public static final int H263Level10      = 0x01;
        public static final int H263Level20      = 0x02;
        public static final int H263Level30      = 0x04;
        public static final int H263Level40      = 0x08;
        public static final int H263Level45      = 0x10;
        public static final int H263Level50      = 0x20;
        public static final int H263Level60      = 0x40;
        public static final int H263Level70      = 0x80;

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

        public static final int MPEG4Level0      = 0x01;
        public static final int MPEG4Level0b     = 0x02;
        public static final int MPEG4Level1      = 0x04;
        public static final int MPEG4Level2      = 0x08;
        public static final int MPEG4Level3      = 0x10;
        public static final int MPEG4Level3b     = 0x18;
        public static final int MPEG4Level4      = 0x20;
        public static final int MPEG4Level4a     = 0x40;
        public static final int MPEG4Level5      = 0x80;
        public static final int MPEG4Level6      = 0x100;

        public static final int MPEG2ProfileSimple              = 0x00;
        public static final int MPEG2ProfileMain                = 0x01;
        public static final int MPEG2Profile422                 = 0x02;
        public static final int MPEG2ProfileSNR                 = 0x03;
        public static final int MPEG2ProfileSpatial             = 0x04;
        public static final int MPEG2ProfileHigh                = 0x05;

        public static final int MPEG2LevelLL     = 0x00;
        public static final int MPEG2LevelML     = 0x01;
        public static final int MPEG2LevelH14    = 0x02;
        public static final int MPEG2LevelHL     = 0x03;
        public static final int MPEG2LevelHP     = 0x04;

        public static final int AACObjectMain       = 1;
        public static final int AACObjectLC         = 2;
        public static final int AACObjectSSR        = 3;
        public static final int AACObjectLTP        = 4;
        public static final int AACObjectHE         = 5;
        public static final int AACObjectScalable   = 6;
        public static final int AACObjectERLC       = 17;
        public static final int AACObjectERScalable = 20;
        public static final int AACObjectLD         = 23;
        public static final int AACObjectHE_PS      = 29;
        public static final int AACObjectELD        = 39;
        /** xHE-AAC (includes USAC) */
        public static final int AACObjectXHE        = 42;

        public static final int VP8Level_Version0 = 0x01;
        public static final int VP8Level_Version1 = 0x02;
        public static final int VP8Level_Version2 = 0x04;
        public static final int VP8Level_Version3 = 0x08;

        public static final int VP8ProfileMain = 0x01;

        /** VP9 Profile 0 4:2:0 8-bit */
        public static final int VP9Profile0 = 0x01;

        /** VP9 Profile 1 4:2:2 8-bit */
        public static final int VP9Profile1 = 0x02;

        /** VP9 Profile 2 4:2:0 10-bit */
        public static final int VP9Profile2 = 0x04;

        /** VP9 Profile 3 4:2:2 10-bit */
        public static final int VP9Profile3 = 0x08;

        // HDR profiles also support passing HDR metadata
        /** VP9 Profile 2 4:2:0 10-bit HDR */
        public static final int VP9Profile2HDR = 0x1000;

        /** VP9 Profile 3 4:2:2 10-bit HDR */
        public static final int VP9Profile3HDR = 0x2000;

        /** VP9 Profile 2 4:2:0 10-bit HDR10Plus */
        public static final int VP9Profile2HDR10Plus = 0x4000;

        /** VP9 Profile 3 4:2:2 10-bit HDR10Plus */
        public static final int VP9Profile3HDR10Plus = 0x8000;

        public static final int VP9Level1  = 0x1;
        public static final int VP9Level11 = 0x2;
        public static final int VP9Level2  = 0x4;
        public static final int VP9Level21 = 0x8;
        public static final int VP9Level3  = 0x10;
        public static final int VP9Level31 = 0x20;
        public static final int VP9Level4  = 0x40;
        public static final int VP9Level41 = 0x80;
        public static final int VP9Level5  = 0x100;
        public static final int VP9Level51 = 0x200;
        public static final int VP9Level52 = 0x400;
        public static final int VP9Level6  = 0x800;
        public static final int VP9Level61 = 0x1000;
        public static final int VP9Level62 = 0x2000;

        public static final int HEVCProfileMain        = 0x01;
        public static final int HEVCProfileMain10      = 0x02;
        public static final int HEVCProfileMainStill   = 0x04;
        public static final int HEVCProfileMain10HDR10 = 0x1000;
        public static final int HEVCProfileMain10HDR10Plus = 0x2000;

        public static final int HEVCMainTierLevel1  = 0x1;
        public static final int HEVCHighTierLevel1  = 0x2;
        public static final int HEVCMainTierLevel2  = 0x4;
        public static final int HEVCHighTierLevel2  = 0x8;
        public static final int HEVCMainTierLevel21 = 0x10;
        public static final int HEVCHighTierLevel21 = 0x20;
        public static final int HEVCMainTierLevel3  = 0x40;
        public static final int HEVCHighTierLevel3  = 0x80;
        public static final int HEVCMainTierLevel31 = 0x100;
        public static final int HEVCHighTierLevel31 = 0x200;
        public static final int HEVCMainTierLevel4  = 0x400;
        public static final int HEVCHighTierLevel4  = 0x800;
        public static final int HEVCMainTierLevel41 = 0x1000;
        public static final int HEVCHighTierLevel41 = 0x2000;
        public static final int HEVCMainTierLevel5  = 0x4000;
        public static final int HEVCHighTierLevel5  = 0x8000;
        public static final int HEVCMainTierLevel51 = 0x10000;
        public static final int HEVCHighTierLevel51 = 0x20000;
        public static final int HEVCMainTierLevel52 = 0x40000;
        public static final int HEVCHighTierLevel52 = 0x80000;
        public static final int HEVCMainTierLevel6  = 0x100000;
        public static final int HEVCHighTierLevel6  = 0x200000;
        public static final int HEVCMainTierLevel61 = 0x400000;
        public static final int HEVCHighTierLevel61 = 0x800000;
        public static final int HEVCMainTierLevel62 = 0x1000000;
        public static final int HEVCHighTierLevel62 = 0x2000000;

        private static final int HEVCHighTierLevels =
            HEVCHighTierLevel1 | HEVCHighTierLevel2 | HEVCHighTierLevel21 | HEVCHighTierLevel3 |
            HEVCHighTierLevel31 | HEVCHighTierLevel4 | HEVCHighTierLevel41 | HEVCHighTierLevel5 |
            HEVCHighTierLevel51 | HEVCHighTierLevel52 | HEVCHighTierLevel6 | HEVCHighTierLevel61 |
            HEVCHighTierLevel62;

        public static final int DolbyVisionProfileDvavPer = 0x1;
        public static final int DolbyVisionProfileDvavPen = 0x2;
        public static final int DolbyVisionProfileDvheDer = 0x4;
        public static final int DolbyVisionProfileDvheDen = 0x8;
        public static final int DolbyVisionProfileDvheDtr = 0x10;
        public static final int DolbyVisionProfileDvheStn = 0x20;
        public static final int DolbyVisionProfileDvheDth = 0x40;
        public static final int DolbyVisionProfileDvheDtb = 0x80;
        public static final int DolbyVisionProfileDvheSt  = 0x100;
        public static final int DolbyVisionProfileDvavSe  = 0x200;
        /** Dolby Vision AV1 profile */
        @SuppressLint("AllUpper")
        public static final int DolbyVisionProfileDvav110 = 0x400;

        public static final int DolbyVisionLevelHd24    = 0x1;
        public static final int DolbyVisionLevelHd30    = 0x2;
        public static final int DolbyVisionLevelFhd24   = 0x4;
        public static final int DolbyVisionLevelFhd30   = 0x8;
        public static final int DolbyVisionLevelFhd60   = 0x10;
        public static final int DolbyVisionLevelUhd24   = 0x20;
        public static final int DolbyVisionLevelUhd30   = 0x40;
        public static final int DolbyVisionLevelUhd48   = 0x80;
        public static final int DolbyVisionLevelUhd60   = 0x100;
        @SuppressLint("AllUpper")
        public static final int DolbyVisionLevelUhd120  = 0x200;
        @SuppressLint("AllUpper")
        public static final int DolbyVisionLevel8k30    = 0x400;
        @SuppressLint("AllUpper")
        public static final int DolbyVisionLevel8k60    = 0x800;

        // Profiles and levels for AV1 Codec, corresponding to the definitions in
        // "AV1 Bitstream & Decoding Process Specification", Annex A
        // found at https://aomedia.org/av1-bitstream-and-decoding-process-specification/

        /**
         * AV1 Main profile 4:2:0 8-bit
         *
         * See definition in
         * <a href="https://aomedia.org/av1-bitstream-and-decoding-process-specification/">AV1 Specification</a>
         * Annex A.
         */
        public static final int AV1ProfileMain8   = 0x1;

        /**
         * AV1 Main profile 4:2:0 10-bit
         *
         * See definition in
         * <a href="https://aomedia.org/av1-bitstream-and-decoding-process-specification/">AV1 Specification</a>
         * Annex A.
         */
        public static final int AV1ProfileMain10  = 0x2;


        /** AV1 Main profile 4:2:0 10-bit with HDR10. */
        public static final int AV1ProfileMain10HDR10 = 0x1000;

        /** AV1 Main profile 4:2:0 10-bit with HDR10Plus. */
        public static final int AV1ProfileMain10HDR10Plus = 0x2000;

        public static final int AV1Level2       = 0x1;
        public static final int AV1Level21      = 0x2;
        public static final int AV1Level22      = 0x4;
        public static final int AV1Level23      = 0x8;
        public static final int AV1Level3       = 0x10;
        public static final int AV1Level31      = 0x20;
        public static final int AV1Level32      = 0x40;
        public static final int AV1Level33      = 0x80;
        public static final int AV1Level4       = 0x100;
        public static final int AV1Level41      = 0x200;
        public static final int AV1Level42      = 0x400;
        public static final int AV1Level43      = 0x800;
        public static final int AV1Level5       = 0x1000;
        public static final int AV1Level51      = 0x2000;
        public static final int AV1Level52      = 0x4000;
        public static final int AV1Level53      = 0x8000;
        public static final int AV1Level6       = 0x10000;
        public static final int AV1Level61      = 0x20000;
        public static final int AV1Level62      = 0x40000;
        public static final int AV1Level63      = 0x80000;
        public static final int AV1Level7       = 0x100000;
        public static final int AV1Level71      = 0x200000;
        public static final int AV1Level72      = 0x400000;
        public static final int AV1Level73      = 0x800000;

        /**
         * The profile of the media content. Depending on the type of media this can be
         * one of the profile values defined in this class.
         */
        public int profile;

        /**
         * The level of the media content. Depending on the type of media this can be
         * one of the level values defined in this class.
         *
         * Note that VP9 decoder on platforms before {@link android.os.Build.VERSION_CODES#N} may
         * not advertise a profile level support. For those VP9 decoders, please use
         * {@link VideoCapabilities} to determine the codec capabilities.
         */
        public int level;

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj instanceof CodecProfileLevel) {
                CodecProfileLevel other = (CodecProfileLevel)obj;
                return other.profile == profile && other.level == level;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(((long)profile << Integer.SIZE) | level);
        }
    };

    /**
     * Enumerates the capabilities of the codec component. Since a single
     * component can support data of a variety of types, the type has to be
     * specified to yield a meaningful result.
     * @param type The MIME type to query
     */
    public final CodecCapabilities getCapabilitiesForType(
            String type) {
        CodecCapabilities caps = mCaps.get(type);
        if (caps == null) {
            throw new IllegalArgumentException("codec does not support type");
        }
        // clone writable object
        return caps.dup();
    }

    /** @hide */
    public MediaCodecInfo makeRegular() {
        ArrayList<CodecCapabilities> caps = new ArrayList<CodecCapabilities>();
        for (CodecCapabilities c: mCaps.values()) {
            if (c.isRegular()) {
                caps.add(c);
            }
        }
        if (caps.size() == 0) {
            return null;
        } else if (caps.size() == mCaps.size()) {
            return this;
        }

        return new MediaCodecInfo(
                mName, mCanonicalName, mFlags,
                caps.toArray(new CodecCapabilities[caps.size()]));
    }
}
