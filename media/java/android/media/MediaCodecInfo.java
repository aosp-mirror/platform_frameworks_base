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

import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static android.media.Utils.intersectSortedDistinctRanges;
import static android.media.Utils.sortDistinctRanges;
import static com.android.internal.util.Preconditions.checkArgumentPositive;
import static com.android.internal.util.Preconditions.checkNotNull;

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
    private boolean mIsEncoder;
    private String mName;
    private Map<String, CodecCapabilities> mCaps;

    /* package private */ MediaCodecInfo(
            String name, boolean isEncoder, CodecCapabilities[] caps) {
        mName = name;
        mIsEncoder = isEncoder;
        mCaps = new HashMap<String, CodecCapabilities>();
        for (CodecCapabilities c: caps) {
            mCaps.put(c.getMimeType(), c);
        }
    }

    /**
     * Retrieve the codec name.
     */
    public final String getName() {
        return mName;
    }

    /**
     * Query if the codec is an encoder.
     */
    public final boolean isEncoder() {
        return mIsEncoder;
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
        public Feature(String name, int value, boolean def) {
            mName = name;
            mValue = value;
            mDefault = def;
        }
    }

    // COMMON CONSTANTS
    private static final Range<Integer> POSITIVE_INTEGERS =
        Range.create(1, Integer.MAX_VALUE);
    private static final Range<Long> POSITIVE_LONGS =
        Range.create(1l, Long.MAX_VALUE);
    private static final Range<Rational> POSITIVE_RATIONALS =
        Range.create(new Rational(1, Integer.MAX_VALUE),
                     new Rational(Integer.MAX_VALUE, 1));
    private static final Range<Integer> SIZE_RANGE = Range.create(1, 32768);
    private static final Range<Integer> FRAME_RATE_RANGE = Range.create(0, 960);
    private static final Range<Integer> BITRATE_RANGE = Range.create(0, 500000000);

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

        // LEGACY FIELDS

        // Enumerates supported profile/level combinations as defined
        // by the type of encoded data. These combinations impose restrictions
        // on video resolution, bitrate... and limit the available encoder tools
        // such as B-frame support, arithmetic coding...
        public CodecProfileLevel[] profileLevels;  // NOTE this array is modifiable by user

        // from OMX_COLOR_FORMATTYPE
        public static final int COLOR_FormatMonochrome              = 1;
        public static final int COLOR_Format8bitRGB332              = 2;
        public static final int COLOR_Format12bitRGB444             = 3;
        public static final int COLOR_Format16bitARGB4444           = 4;
        public static final int COLOR_Format16bitARGB1555           = 5;
        public static final int COLOR_Format16bitRGB565             = 6;
        public static final int COLOR_Format16bitBGR565             = 7;
        public static final int COLOR_Format18bitRGB666             = 8;
        public static final int COLOR_Format18bitARGB1665           = 9;
        public static final int COLOR_Format19bitARGB1666           = 10;
        public static final int COLOR_Format24bitRGB888             = 11;
        public static final int COLOR_Format24bitBGR888             = 12;
        public static final int COLOR_Format24bitARGB1887           = 13;
        public static final int COLOR_Format25bitARGB1888           = 14;
        public static final int COLOR_Format32bitBGRA8888           = 15;
        public static final int COLOR_Format32bitARGB8888           = 16;
        public static final int COLOR_FormatYUV411Planar            = 17;
        public static final int COLOR_FormatYUV411PackedPlanar      = 18;
        public static final int COLOR_FormatYUV420Planar            = 19;
        public static final int COLOR_FormatYUV420PackedPlanar      = 20;
        public static final int COLOR_FormatYUV420SemiPlanar        = 21;
        public static final int COLOR_FormatYUV422Planar            = 22;
        public static final int COLOR_FormatYUV422PackedPlanar      = 23;
        public static final int COLOR_FormatYUV422SemiPlanar        = 24;
        public static final int COLOR_FormatYCbYCr                  = 25;
        public static final int COLOR_FormatYCrYCb                  = 26;
        public static final int COLOR_FormatCbYCrY                  = 27;
        public static final int COLOR_FormatCrYCbY                  = 28;
        public static final int COLOR_FormatYUV444Interleaved       = 29;
        public static final int COLOR_FormatRawBayer8bit            = 30;
        public static final int COLOR_FormatRawBayer10bit           = 31;
        public static final int COLOR_FormatRawBayer8bitcompressed  = 32;
        public static final int COLOR_FormatL2                      = 33;
        public static final int COLOR_FormatL4                      = 34;
        public static final int COLOR_FormatL8                      = 35;
        public static final int COLOR_FormatL16                     = 36;
        public static final int COLOR_FormatL24                     = 37;
        public static final int COLOR_FormatL32                     = 38;
        public static final int COLOR_FormatYUV420PackedSemiPlanar  = 39;
        public static final int COLOR_FormatYUV422PackedSemiPlanar  = 40;
        public static final int COLOR_Format18BitBGR666             = 41;
        public static final int COLOR_Format24BitARGB6666           = 42;
        public static final int COLOR_Format24BitABGR6666           = 43;

        public static final int COLOR_TI_FormatYUV420PackedSemiPlanar = 0x7f000100;
        // COLOR_FormatSurface indicates that the data will be a GraphicBuffer metadata reference.
        // In OMX this is called OMX_COLOR_FormatAndroidOpaque.
        public static final int COLOR_FormatSurface                   = 0x7F000789;
        // This corresponds to YUV_420_888 format
        public static final int COLOR_FormatYUV420Flexible            = 0x7F420888;
        public static final int COLOR_QCOM_FormatYUV420SemiPlanar     = 0x7fa30c00;

        /**
         * Defined in the OpenMAX IL specs, color format values are drawn from
         * OMX_COLOR_FORMATTYPE.
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
        };

        /** @hide */
        public String[] validFeatures() {
            Feature[] features = getValidFeatures();
            String[] res = new String[features.length];
            for (int i = 0; i < res.length; i++) {
                res[i] = features[i].mName;
            }
            return res;
        }

        private Feature[] getValidFeatures() {
            if (!isEncoder()) {
                return decoderFeatures;
            }
            return new Feature[] {};
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
                Integer yesNo = (Integer)map.get(MediaFormat.KEY_FEATURE_ + feat.mName);
                if (yesNo == null) {
                    continue;
                }
                if ((yesNo == 1 && !isFeatureSupported(feat.mName)) ||
                        (yesNo == 0 && isFeatureRequired(feat.mName))) {
                    return false;
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
            return new CodecCapabilities(
                // clone writable arrays
                Arrays.copyOf(profileLevels, profileLevels.length),
                Arrays.copyOf(colorFormats, colorFormats.length),
                isEncoder(),
                mFlagsVerified,
                mDefaultFormat,
                mCapabilitiesInfo);
        }

        /**
         * Retrieve the codec capabilities for a certain {@code mime type}, {@code
         * profile} and {@code level}.  If the type, or profile-level combination
         * is not understood by the framework, it returns null.
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
                0 /* flags */, defaultFormat, new MediaFormat() /* info */);
            if (ret.mError != 0) {
                return null;
            }
            return ret;
        }

        /* package private */ CodecCapabilities(
                CodecProfileLevel[] profLevs, int[] colFmts,
                boolean encoder, int flags,
                Map<String, Object>defaultFormatMap,
                Map<String, Object>capabilitiesMap) {
            this(profLevs, colFmts, encoder, flags,
                    new MediaFormat(defaultFormatMap),
                    new MediaFormat(capabilitiesMap));
        }

        private MediaFormat mCapabilitiesInfo;

        /* package private */ CodecCapabilities(
                CodecProfileLevel[] profLevs, int[] colFmts, boolean encoder, int flags,
                MediaFormat defaultFormat, MediaFormat info) {
            final Map<String, Object> map = info.getMap();
            profileLevels = profLevs;
            colorFormats = colFmts;
            mFlagsVerified = flags;
            mDefaultFormat = defaultFormat;
            mCapabilitiesInfo = info;
            mMime = mDefaultFormat.getString(MediaFormat.KEY_MIME);

            if (mMime.toLowerCase().startsWith("audio/")) {
                mAudioCaps = AudioCapabilities.create(info, this);
                mAudioCaps.setDefaultFormat(mDefaultFormat);
            } else if (mMime.toLowerCase().startsWith("video/")) {
                mVideoCaps = VideoCapabilities.create(info, this);
            }
            if (encoder) {
                mEncoderCaps = EncoderCapabilities.create(info, this);
                mEncoderCaps.setDefaultFormat(mDefaultFormat);
            }

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
                mDefaultFormat.setInteger(key, 1);
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
        private int mMaxInputChannelCount;

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
            return Arrays.copyOf(mSampleRates, mSampleRates.length);
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
         * Returns the maximum number of input channels supported.  The codec
         * supports any number of channels between 1 and this maximum value.
         */
        public int getMaxInputChannelCount() {
            return mMaxInputChannelCount;
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

        /** @hide */
        public void init(MediaFormat info, CodecCapabilities parent) {
            mParent = parent;
            initWithPlatformLimits();
            applyLevelLimits();
            parseFromInfo(info);
        }

        private void initWithPlatformLimits() {
            mBitrateRange = Range.create(0, Integer.MAX_VALUE);
            mMaxInputChannelCount = MAX_INPUT_CHANNEL_COUNT;
            // mBitrateRange = Range.create(1, 320000);
            mSampleRateRanges = new Range[] { Range.create(8000, 96000) };
            mSampleRates = null;
        }

        private boolean supports(Integer sampleRate, Integer inputChannels) {
            // channels and sample rates are checked orthogonally
            if (inputChannels != null &&
                    (inputChannels < 1 || inputChannels > mMaxInputChannelCount)) {
                return false;
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
            int maxChannels = 0;
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
                maxChannels = 8;
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
            applyLimits(maxChannels, bitRates);
        }

        private void applyLimits(int maxInputChannels, Range<Integer> bitRates) {
            mMaxInputChannelCount = Range.create(1, mMaxInputChannelCount)
                    .clamp(maxInputChannels);
            if (bitRates != null) {
                mBitrateRange = mBitrateRange.intersect(bitRates);
            }
        }

        private void parseFromInfo(MediaFormat info) {
            int maxInputChannels = MAX_INPUT_CHANNEL_COUNT;
            Range<Integer> bitRates = POSITIVE_INTEGERS;

            if (info.containsKey("sample-rate-ranges")) {
                String[] rateStrings = info.getString("sample-rate-ranges").split(",");
                Range<Integer>[] rateRanges = new Range[rateStrings.length];
                for (int i = 0; i < rateStrings.length; i++) {
                    rateRanges[i] = Utils.parseIntRange(rateStrings[i], null);
                }
                limitSampleRates(rateRanges);
            }
            if (info.containsKey("max-channel-count")) {
                maxInputChannels = Utils.parseIntSafely(
                        info.getString("max-channel-count"), maxInputChannels);
            }
            if (info.containsKey("bitrate-range")) {
                bitRates = bitRates.intersect(
                        Utils.parseIntRange(info.getString("bitrate-range"), bitRates));
            }
            applyLimits(maxInputChannels, bitRates);
        }

        /** @hide */
        public void setDefaultFormat(MediaFormat format) {
            // report settings that have only a single choice
            if (mBitrateRange.getLower().equals(mBitrateRange.getUpper())) {
                format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrateRange.getLower());
            }
            if (mMaxInputChannelCount == 1) {
                // mono-only format
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            }
            if (mSampleRates != null && mSampleRates.length == 1) {
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, mSampleRates[0]);
            }
        }

        /** @hide */
        public boolean supportsFormat(MediaFormat format) {
            Map<String, Object> map = format.getMap();
            Integer sampleRate = (Integer)map.get(MediaFormat.KEY_SAMPLE_RATE);
            Integer channels = (Integer)map.get(MediaFormat.KEY_CHANNEL_COUNT);
            if (!supports(sampleRate, channels)) {
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
        private Range<Integer> mFrameRateRange;

        private int mBlockWidth;
        private int mBlockHeight;
        private int mWidthAlignment;
        private int mHeightAlignment;
        private int mSmallerDimensionUpperLimit;

        /**
         * Returns the range of supported bitrates in bits/second.
         */
        public Range<Integer> getBitrateRange() {
            return mBitrateRange;
        }

        /**
         * Returns the range of supported video widths.
         */
        public Range<Integer> getSupportedWidths() {
            return mWidthRange;
        }

        /**
         * Returns the range of supported video heights.
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
                // should not be here
                Log.w(TAG, "could not get supported widths for " + height , e);
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
                // should not be here
                Log.w(TAG, "could not get supported heights for " + width , e);
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

        private boolean supports(
                Integer width, Integer height, Number rate) {
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

        /**
         * @hide
         * @throws java.lang.ClassCastException */
        public boolean supportsFormat(MediaFormat format) {
            final Map<String, Object> map = format.getMap();
            Integer width = (Integer)map.get(MediaFormat.KEY_WIDTH);
            Integer height = (Integer)map.get(MediaFormat.KEY_HEIGHT);
            Number rate = (Number)map.get(MediaFormat.KEY_FRAME_RATE);

            // we ignore color-format for now as it is not reliably reported by codec

            return supports(width, height, rate);
        }

        /* no public constructor */
        private VideoCapabilities() { }

        /** @hide */
        public static VideoCapabilities create(
                MediaFormat info, CodecCapabilities parent) {
            VideoCapabilities caps = new VideoCapabilities();
            caps.init(info, parent);
            return caps;
        }

        /** @hide */
        public void init(MediaFormat info, CodecCapabilities parent) {
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

            mWidthRange  = SIZE_RANGE;
            mHeightRange = SIZE_RANGE;
            mFrameRateRange = FRAME_RATE_RANGE;

            mHorizontalBlockRange = SIZE_RANGE;
            mVerticalBlockRange   = SIZE_RANGE;

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
            mSmallerDimensionUpperLimit = SIZE_RANGE.getUpper();
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
            {
                Object o = map.get("size-range");
                Pair<Size, Size> sizeRange = Utils.parseSizeRange(o);
                if (sizeRange != null) {
                    try {
                        widths = Range.create(
                                sizeRange.first.getWidth(),
                                sizeRange.second.getWidth());
                        heights = Range.create(
                                sizeRange.first.getHeight(),
                                sizeRange.second.getHeight());
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "could not parse size range '" + o + "'");
                        widths = null;
                        heights = null;
                    }
                }
            }
            // for now this just means using the smaller max size as 2nd
            // upper limit.
            // for now we are keeping the profile specific "width/height
            // in macroblocks" limits.
            if (Integer.valueOf(1).equals(map.get("feature-can-swap-width-height"))) {
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

            if ((mParent.mError & ERROR_UNSUPPORTED) != 0) {
                // codec supports profiles that we don't know.
                // Use supplied values clipped to platform limits
                if (widths != null) {
                    mWidthRange = SIZE_RANGE.intersect(widths);
                }
                if (heights != null) {
                    mHeightRange = SIZE_RANGE.intersect(heights);
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
                    mBitrateRange = BITRATE_RANGE.intersect(bitRates);
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
            applyAlignment(widthAlignment, heightAlignment);
            applyBlockLimits(
                    blockWidth, blockHeight, Range.create(1, maxBlocks),
                    Range.create(1L, maxBlocksPerSecond),
                    Range.create(
                            new Rational(1, maxVerticalBlocks),
                            new Rational(maxHorizontalBlocks, 1)));
            mHorizontalBlockRange =
                    mHorizontalBlockRange.intersect(
                            1, maxHorizontalBlocks / (mBlockWidth / blockWidth));
            mVerticalBlockRange =
                    mVerticalBlockRange.intersect(
                            1, maxVerticalBlocks / (mBlockHeight / blockHeight));
        }

        private void applyLevelLimits() {
            int maxBlocksPerSecond = 0;
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
                            MBPS =    1485; FS =    99; BR =     64; DPB =    396; break;
                        case CodecProfileLevel.AVCLevel1b:
                            MBPS =    1485; FS =    99; BR =    128; DPB =    396; break;
                        case CodecProfileLevel.AVCLevel11:
                            MBPS =    3000; FS =   396; BR =    192; DPB =    900; break;
                        case CodecProfileLevel.AVCLevel12:
                            MBPS =    6000; FS =   396; BR =    384; DPB =   2376; break;
                        case CodecProfileLevel.AVCLevel13:
                            MBPS =   11880; FS =   396; BR =    768; DPB =   2376; break;
                        case CodecProfileLevel.AVCLevel2:
                            MBPS =   11880; FS =   396; BR =   2000; DPB =   2376; break;
                        case CodecProfileLevel.AVCLevel21:
                            MBPS =   19800; FS =   792; BR =   4000; DPB =   4752; break;
                        case CodecProfileLevel.AVCLevel22:
                            MBPS =   20250; FS =  1620; BR =   4000; DPB =   8100; break;
                        case CodecProfileLevel.AVCLevel3:
                            MBPS =   40500; FS =  1620; BR =  10000; DPB =   8100; break;
                        case CodecProfileLevel.AVCLevel31:
                            MBPS =  108000; FS =  3600; BR =  14000; DPB =  18000; break;
                        case CodecProfileLevel.AVCLevel32:
                            MBPS =  216000; FS =  5120; BR =  20000; DPB =  20480; break;
                        case CodecProfileLevel.AVCLevel4:
                            MBPS =  245760; FS =  8192; BR =  20000; DPB =  32768; break;
                        case CodecProfileLevel.AVCLevel41:
                            MBPS =  245760; FS =  8192; BR =  50000; DPB =  32768; break;
                        case CodecProfileLevel.AVCLevel42:
                            MBPS =  522240; FS =  8704; BR =  50000; DPB =  34816; break;
                        case CodecProfileLevel.AVCLevel5:
                            MBPS =  589824; FS = 22080; BR = 135000; DPB = 110400; break;
                        case CodecProfileLevel.AVCLevel51:
                            MBPS =  983040; FS = 36864; BR = 240000; DPB = 184320; break;
                        case CodecProfileLevel.AVCLevel52:
                            MBPS = 2073600; FS = 36864; BR = 240000; DPB = 184320; break;
                        default:
                            Log.w(TAG, "Unrecognized level "
                                    + profileLevel.level + " for " + mime);
                            errors |= ERROR_UNRECOGNIZED;
                    }
                    switch (profileLevel.profile) {
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
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_MPEG4)) {
                int maxWidth = 11, maxHeight = 9, maxRate = 15;
                maxBlocks = 99;
                maxBlocksPerSecond = 1485;
                maxBps = 64000;
                for (CodecProfileLevel profileLevel: profileLevels) {
                    int MBPS = 0, FS = 0, BR = 0, FR = 0, W = 0, H = 0;
                    boolean supported = true;
                    switch (profileLevel.profile) {
                        case CodecProfileLevel.MPEG4ProfileSimple:
                            switch (profileLevel.level) {
                                case CodecProfileLevel.MPEG4Level0:
                                    FR = 15; W = 11; H =  9; MBPS =  1485; FS =  99; BR =  64; break;
                                case CodecProfileLevel.MPEG4Level1:
                                    FR = 30; W = 11; H =  9; MBPS =  1485; FS =  99; BR =  64; break;
                                case CodecProfileLevel.MPEG4Level0b:
                                    FR = 30; W = 11; H =  9; MBPS =  1485; FS =  99; BR = 128; break;
                                case CodecProfileLevel.MPEG4Level2:
                                    FR = 30; W = 22; H = 18; MBPS =  5940; FS = 396; BR = 128; break;
                                case CodecProfileLevel.MPEG4Level3:
                                    FR = 30; W = 22; H = 18; MBPS = 11880; FS = 396; BR = 384; break;
                                case CodecProfileLevel.MPEG4Level4:
                                case CodecProfileLevel.MPEG4Level4a:
                                case CodecProfileLevel.MPEG4Level5:
                                    // While MPEG4 SP does not have level 4 or 5, some vendors
                                    // report it. Use the same limits as level 3, but mark as
                                    // unsupported.
                                    FR = 30; W = 22; H = 18; MBPS = 11880; FS = 396; BR = 384;
                                    supported = false;
                                    break;
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
                                // case CodecProfileLevel.MPEG4Level3b:
                                // TODO: MPEG4 level 3b is not defined in OMX
                                //  MBPS = 11880; FS =  396; BR = 1500; break;
                                case CodecProfileLevel.MPEG4Level4:
                                case CodecProfileLevel.MPEG4Level4a:
                                    // TODO: MPEG4 level 4a is not defined in spec
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
                        case CodecProfileLevel.MPEG4ProfileAdvancedScalable: // 1-3
                        case CodecProfileLevel.MPEG4ProfileHybrid:           // 1-2
                        case CodecProfileLevel.MPEG4ProfileBasicAnimated:    // 1-2
                        case CodecProfileLevel.MPEG4ProfileScalableTexture:  // 1
                        case CodecProfileLevel.MPEG4ProfileSimpleFace:       // 1-2
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
                    maxWidth = Math.max(W, maxWidth);
                    maxHeight = Math.max(H, maxHeight);
                    maxRate = Math.max(FR, maxRate);
                }
                applyMacroBlockLimits(maxWidth, maxHeight,
                        maxBlocks, maxBlocksPerSecond,
                        16 /* blockWidth */, 16 /* blockHeight */,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
                mFrameRateRange = mFrameRateRange.intersect(12, maxRate);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_H263)) {
                int maxWidth = 11, maxHeight = 9, maxRate = 15;
                maxBlocks = 99;
                maxBlocksPerSecond = 1485;
                maxBps = 64000;
                for (CodecProfileLevel profileLevel: profileLevels) {
                    int MBPS = 0, BR = 0, FR = 0, W = 0, H = 0;
                    switch (profileLevel.level) {
                        case CodecProfileLevel.H263Level10:
                            FR = 15; W = 11; H =  9; BR =   1; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level20:
                            // only supports CIF, 0..QCIF
                            FR = 30; W = 22; H = 18; BR =   2; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level30:
                            // only supports CIF, 0..QCIF
                            FR = 30; W = 22; H = 18; BR =   6; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level40:
                            // only supports CIF, 0..QCIF
                            FR = 30; W = 22; H = 18; BR =  32; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level45:
                            // only implies level 10 support
                            FR = 30; W = 11; H =  9; BR =   2; MBPS =  W * H * FR; break;
                        case CodecProfileLevel.H263Level50:
                            // only supports 50fps for H > 15
                            FR = 60; W = 22; H = 18; BR =  64; MBPS =  W * H * 50; break;
                        case CodecProfileLevel.H263Level60:
                            // only supports 50fps for H > 15
                            FR = 60; W = 45; H = 18; BR = 128; MBPS =  W * H * 50; break;
                        case CodecProfileLevel.H263Level70:
                            // only supports 50fps for H > 30
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
                    errors &= ~ERROR_NONE_SUPPORTED;
                    maxBlocksPerSecond = Math.max(MBPS, maxBlocksPerSecond);
                    maxBlocks = Math.max(W * H, maxBlocks);
                    maxBps = Math.max(BR * 64000, maxBps);
                    maxWidth = Math.max(W, maxWidth);
                    maxHeight = Math.max(H, maxHeight);
                    maxRate = Math.max(FR, maxRate);
                }
                applyMacroBlockLimits(maxWidth, maxHeight,
                        maxBlocks, maxBlocksPerSecond,
                        16 /* blockWidth */, 16 /* blockHeight */,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
                mFrameRateRange = Range.create(1, maxRate);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_VP8) ||
                    mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_VP9)) {
                maxBlocks = maxBlocksPerSecond = Integer.MAX_VALUE;

                // TODO: set to 100Mbps for now, need a number for VPX
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

                final int blockSize =
                    mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_VP8) ? 16 : 8;
                applyMacroBlockLimits(Short.MAX_VALUE, Short.MAX_VALUE,
                        maxBlocks, maxBlocksPerSecond, blockSize, blockSize,
                        1 /* widthAlignment */, 1 /* heightAlignment */);
            } else if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                maxBlocks = 36864;
                maxBlocksPerSecond = maxBlocks * 15;
                maxBps = 128000;
                for (CodecProfileLevel profileLevel: profileLevels) {
                    double FR = 0;
                    int FS = 0;
                    int BR = 0;
                    switch (profileLevel.level) {
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

                    errors &= ~ERROR_NONE_SUPPORTED;
                    maxBlocksPerSecond = Math.max((int)(FR * FS), maxBlocksPerSecond);
                    maxBlocks = Math.max(FS, maxBlocks);
                    maxBps = Math.max(BR * 1000, maxBps);
                }

                int maxLengthInBlocks = (int)(Math.sqrt(maxBlocks * 8));
                // CTBs are at least 8x8
                maxBlocks = Utils.divUp(maxBlocks, 8 * 8);
                maxBlocksPerSecond = Utils.divUp(maxBlocksPerSecond, 8 * 8);
                maxLengthInBlocks = Utils.divUp(maxLengthInBlocks, 8);

                applyMacroBlockLimits(
                        maxLengthInBlocks, maxLengthInBlocks,
                        maxBlocks, maxBlocksPerSecond,
                        8 /* blockWidth */, 8 /* blockHeight */,
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
         * @hide
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

        private static final Feature[] bitrates = new Feature[] {
            new Feature("VBR", BITRATE_MODE_VBR, true),
            new Feature("CBR", BITRATE_MODE_CBR, false),
            new Feature("CQ",  BITRATE_MODE_CQ,  false)
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

        /** @hide */
        public void init(MediaFormat info, CodecCapabilities parent) {
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
            if (info.containsKey("feature-bitrate-control")) {
                for (String mode: info.getString("feature-bitrate-control").split(",")) {
                    mBitControl |= parseBitrateMode(mode);
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
        public void setDefaultFormat(MediaFormat format) {
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
        public static final int AVCLevel52      = 0x10000;

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

        // from OMX_VIDEO_HEVCPROFILETYPE
        public static final int HEVCProfileMain   = 0x01;
        public static final int HEVCProfileMain10 = 0x02;

        // from OMX_VIDEO_HEVCLEVELTYPE
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
                mName, mIsEncoder,
                caps.toArray(new CodecCapabilities[caps.size()]));
    }
}
