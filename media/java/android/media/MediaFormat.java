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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates the information describing the format of media data,
 * be it audio or video.
 *
 * The format of the media data is specified as string/value pairs.
 *
 * Keys common to all audio/video formats, <b>all keys not marked optional are mandatory</b>:
 *
 * <table>
 * <tr><th>Name</th><th>Value Type</th><th>Description</th></tr>
 * <tr><td>{@link #KEY_MIME}</td><td>String</td><td>The type of the format.</td></tr>
 * <tr><td>{@link #KEY_MAX_INPUT_SIZE}</td><td>Integer</td><td>optional, maximum size of a buffer of input data</td></tr>
 * <tr><td>{@link #KEY_BIT_RATE}</td><td>Integer</td><td><b>encoder-only</b>, desired bitrate in bits/second</td></tr>
 * </table>
 *
 * Video formats have the following keys:
 * <table>
 * <tr><th>Name</th><th>Value Type</th><th>Description</th></tr>
 * <tr><td>{@link #KEY_WIDTH}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_HEIGHT}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_COLOR_FORMAT}</td><td>Integer</td><td>set by the user
 *         for encoders, readable in the output format of decoders</b></td></tr>
 * <tr><td>{@link #KEY_FRAME_RATE}</td><td>Integer or Float</td><td><b>encoder-only</b></td></tr>
 * <tr><td>{@link #KEY_CAPTURE_RATE}</td><td>Integer</td><td></td></tr>
* <tr><td>{@link #KEY_I_FRAME_INTERVAL}</td><td>Integer</td><td><b>encoder-only</b></td></tr>
 * <tr><td>{@link #KEY_MAX_WIDTH}</td><td>Integer</td><td><b>decoder-only</b>, optional, max-resolution width</td></tr>
 * <tr><td>{@link #KEY_MAX_HEIGHT}</td><td>Integer</td><td><b>decoder-only</b>, optional, max-resolution height</td></tr>
 * <tr><td>{@link #KEY_REPEAT_PREVIOUS_FRAME_AFTER}</td><td>Long</td><td><b>video encoder in surface-mode only</b></td></tr>
 * <tr><td>{@link #KEY_PUSH_BLANK_BUFFERS_ON_STOP}</td><td>Integer(1)</td><td><b>video decoder rendering to a surface only</b></td></tr>
 * </table>
 * Specify both {@link #KEY_MAX_WIDTH} and {@link #KEY_MAX_HEIGHT} to enable
 * adaptive playback (seamless resolution change) for a video decoder that
 * supports it ({@link MediaCodecInfo.CodecCapabilities#FEATURE_AdaptivePlayback}).
 * The values are used as hints for the codec: they are the maximum expected
 * resolution to prepare for.  Depending on codec support, preparing for larger
 * maximum resolution may require more memory even if that resolution is never
 * reached.  These fields have no effect for codecs that do not support adaptive
 * playback.<br /><br />
 *
 * Audio formats have the following keys:
 * <table>
 * <tr><th>Name</th><th>Value Type</th><th>Description</th></tr>
 * <tr><td>{@link #KEY_CHANNEL_COUNT}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_SAMPLE_RATE}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_IS_ADTS}</td><td>Integer</td><td>optional, if <em>decoding</em> AAC audio content, setting this key to 1 indicates that each audio frame is prefixed by the ADTS header.</td></tr>
 * <tr><td>{@link #KEY_AAC_PROFILE}</td><td>Integer</td><td><b>encoder-only</b>, optional, if content is AAC audio, specifies the desired profile.</td></tr>
 * <tr><td>{@link #KEY_AAC_SBR_MODE}</td><td>Integer</td><td><b>encoder-only</b>, optional, if content is AAC audio, specifies the desired SBR mode.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_TARGET_REFERENCE_LEVEL}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the target reference level.</td></tr>
 * <tr><td>{@link #KEY_AAC_ENCODED_TARGET_LEVEL}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the target reference level used at encoder.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_BOOST_FACTOR}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the DRC boost factor.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_ATTENUATION_FACTOR}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the DRC attenuation factor.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_HEAVY_COMPRESSION}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies whether to use heavy compression.</td></tr>
 * <tr><td>{@link #KEY_AAC_MAX_OUTPUT_CHANNEL_COUNT}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the maximum number of channels the decoder outputs.</td></tr>
 * <tr><td>{@link #KEY_CHANNEL_MASK}</td><td>Integer</td><td>optional, a mask of audio channel assignments</td></tr>
 * <tr><td>{@link #KEY_FLAC_COMPRESSION_LEVEL}</td><td>Integer</td><td><b>encoder-only</b>, optional, if content is FLAC audio, specifies the desired compression level.</td></tr>
 * </table>
 *
 * Subtitle formats have the following keys:
 * <table>
 * <tr><td>{@link #KEY_MIME}</td><td>String</td><td>The type of the format.</td></tr>
 * <tr><td>{@link #KEY_LANGUAGE}</td><td>String</td><td>The language of the content.</td></tr>
 * </table>
 */
public final class MediaFormat {
    public static final String MIMETYPE_VIDEO_VP8 = "video/x-vnd.on2.vp8";
    public static final String MIMETYPE_VIDEO_VP9 = "video/x-vnd.on2.vp9";
    public static final String MIMETYPE_VIDEO_AVC = "video/avc";
    public static final String MIMETYPE_VIDEO_HEVC = "video/hevc";
    public static final String MIMETYPE_VIDEO_MPEG4 = "video/mp4v-es";
    public static final String MIMETYPE_VIDEO_H263 = "video/3gpp";
    public static final String MIMETYPE_VIDEO_MPEG2 = "video/mpeg2";
    public static final String MIMETYPE_VIDEO_RAW = "video/raw";

    public static final String MIMETYPE_AUDIO_AMR_NB = "audio/3gpp";
    public static final String MIMETYPE_AUDIO_AMR_WB = "audio/amr-wb";
    public static final String MIMETYPE_AUDIO_MPEG = "audio/mpeg";
    public static final String MIMETYPE_AUDIO_AAC = "audio/mp4a-latm";
    public static final String MIMETYPE_AUDIO_QCELP = "audio/qcelp";
    public static final String MIMETYPE_AUDIO_VORBIS = "audio/vorbis";
    public static final String MIMETYPE_AUDIO_OPUS = "audio/opus";
    public static final String MIMETYPE_AUDIO_G711_ALAW = "audio/g711-alaw";
    public static final String MIMETYPE_AUDIO_G711_MLAW = "audio/g711-mlaw";
    public static final String MIMETYPE_AUDIO_RAW = "audio/raw";
    public static final String MIMETYPE_AUDIO_FLAC = "audio/flac";
    public static final String MIMETYPE_AUDIO_MSGSM = "audio/gsm";
    public static final String MIMETYPE_AUDIO_AC3 = "audio/ac3";
    public static final String MIMETYPE_AUDIO_EAC3 = "audio/eac3";

    /**
     * MIME type for WebVTT subtitle data.
     */
    public static final String MIMETYPE_TEXT_VTT = "text/vtt";

    /**
     * MIME type for CEA-608 closed caption data.
     */
    public static final String MIMETYPE_TEXT_CEA_608 = "text/cea-608";

    private Map<String, Object> mMap;

    /**
     * A key describing the mime type of the MediaFormat.
     * The associated value is a string.
     */
    public static final String KEY_MIME = "mime";

    /**
     * A key describing the language of the content, using either ISO 639-1
     * or 639-2/T codes.  The associated value is a string.
     */
    public static final String KEY_LANGUAGE = "language";

    /**
     * A key describing the sample rate of an audio format.
     * The associated value is an integer
     */
    public static final String KEY_SAMPLE_RATE = "sample-rate";

    /**
     * A key describing the number of channels in an audio format.
     * The associated value is an integer
     */
    public static final String KEY_CHANNEL_COUNT = "channel-count";

    /**
     * A key describing the width of the content in a video format.
     * The associated value is an integer
     */
    public static final String KEY_WIDTH = "width";

    /**
     * A key describing the height of the content in a video format.
     * The associated value is an integer
     */
    public static final String KEY_HEIGHT = "height";

    /**
     * A key describing the maximum expected width of the content in a video
     * decoder format, in case there are resolution changes in the video content.
     * The associated value is an integer
     */
    public static final String KEY_MAX_WIDTH = "max-width";

    /**
     * A key describing the maximum expected height of the content in a video
     * decoder format, in case there are resolution changes in the video content.
     * The associated value is an integer
     */
    public static final String KEY_MAX_HEIGHT = "max-height";

    /** A key describing the maximum size in bytes of a buffer of data
     * described by this MediaFormat.
     * The associated value is an integer
     */
    public static final String KEY_MAX_INPUT_SIZE = "max-input-size";

    /**
     * A key describing the bitrate in bits/sec.
     * The associated value is an integer
     */
    public static final String KEY_BIT_RATE = "bitrate";

    /**
     * A key describing the color format of the content in a video format.
     * Constants are declared in {@link android.media.MediaCodecInfo.CodecCapabilities}.
     */
    public static final String KEY_COLOR_FORMAT = "color-format";

    /**
     * A key describing the frame rate of a video format in frames/sec.
     * The associated value is an integer or a float.
     */
    public static final String KEY_FRAME_RATE = "frame-rate";

    /**
     * A key describing the capture rate of a video format in frames/sec.
     * <p>
     * When capture rate is different than the frame rate, it means that the
     * video is acquired at a different rate than the playback, which produces
     * slow motion or timelapse effect during playback. Application can use the
     * value of this key to tell the relative speed ratio between capture and
     * playback rates when the video was recorded.
     * </p>
     * <p>
     * The associated value is an integer or a float.
     * </p>
     */
    public static final String KEY_CAPTURE_RATE = "capture-rate";

    /**
     * A key describing the frequency of I frames expressed in secs
     * between I frames.
     * The associated value is an integer.
     */
    public static final String KEY_I_FRAME_INTERVAL = "i-frame-interval";

    /**
     * A key describing the temporal layering schema.  This is an optional parameter
     * that applies only to video encoders.  Use {@link MediaCodec#getInputFormat}
     * after {@link MediaCodec#configure configure} to query if the encoder supports
     * the desired schema. Supported values are {@code webrtc.vp8.1-layer},
     * {@code webrtc.vp8.2-layer}, {@code webrtc.vp8.3-layer}, and {@code none}.
     * If the encoder does not support temporal layering, the input format will
     * not have an entry with this key.
     * The associated value is a string.
     */
    public static final String KEY_TEMPORAL_LAYERING = "ts-schema";

    /**
     * A key describing the stride of the video bytebuffer layout.
     * Stride (or row increment) is the difference between the index of a pixel
     * and that of the pixel directly underneath. For YUV 420 formats, the
     * stride corresponds to the Y plane; the stride of the U and V planes can
     * be calculated based on the color format.
     * The associated value is an integer, representing number of bytes.
     */
    public static final String KEY_STRIDE = "stride";

    /**
     * A key describing the plane height of a multi-planar (YUV) video bytebuffer layout.
     * Slice height (or plane height) is the number of rows that must be skipped to get
     * from the top of the Y plane to the top of the U plane in the bytebuffer. In essence
     * the offset of the U plane is sliceHeight * stride. The height of the U/V planes
     * can be calculated based on the color format.
     * The associated value is an integer, representing number of rows.
     */
    public static final String KEY_SLICE_HEIGHT = "slice-height";

    /**
     * Applies only when configuring a video encoder in "surface-input" mode.
     * The associated value is a long and gives the time in microseconds
     * after which the frame previously submitted to the encoder will be
     * repeated (once) if no new frame became available since.
     */
    public static final String KEY_REPEAT_PREVIOUS_FRAME_AFTER
        = "repeat-previous-frame-after";

    /**
     * If specified when configuring a video decoder rendering to a surface,
     * causes the decoder to output "blank", i.e. black frames to the surface
     * when stopped to clear out any previously displayed contents.
     * The associated value is an integer of value 1.
     */
    public static final String KEY_PUSH_BLANK_BUFFERS_ON_STOP
        = "push-blank-buffers-on-shutdown";

    /**
     * A key describing the duration (in microseconds) of the content.
     * The associated value is a long.
     */
    public static final String KEY_DURATION = "durationUs";

    /**
     * A key mapping to a value of 1 if the content is AAC audio and
     * audio frames are prefixed with an ADTS header.
     * The associated value is an integer (0 or 1).
     * This key is only supported when _decoding_ content, it cannot
     * be used to configure an encoder to emit ADTS output.
     */
    public static final String KEY_IS_ADTS = "is-adts";

    /**
     * A key describing the channel composition of audio content. This mask
     * is composed of bits drawn from channel mask definitions in {@link android.media.AudioFormat}.
     * The associated value is an integer.
     */
    public static final String KEY_CHANNEL_MASK = "channel-mask";

    /**
     * A key describing the AAC profile to be used (AAC audio formats only).
     * Constants are declared in {@link android.media.MediaCodecInfo.CodecProfileLevel}.
     */
    public static final String KEY_AAC_PROFILE = "aac-profile";

    /**
     * A key describing the AAC SBR mode to be used (AAC audio formats only).
     * The associated value is an integer and can be set to following values:
     * <ul>
     * <li>0 - no SBR should be applied</li>
     * <li>1 - single rate SBR</li>
     * <li>2 - double rate SBR</li>
     * </ul>
     * Note: If this key is not defined the default SRB mode for the desired AAC profile will
     * be used.
     * <p>This key is only used during encoding.
     */
    public static final String KEY_AAC_SBR_MODE = "aac-sbr-mode";

    /**
     * A key describing the maximum number of channels that can be output by the AAC decoder.
     * By default, the decoder will output the same number of channels as present in the encoded
     * stream, if supported. Set this value to limit the number of output channels, and use
     * the downmix information in the stream, if available.
     * <p>Values larger than the number of channels in the content to decode are ignored.
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_MAX_OUTPUT_CHANNEL_COUNT = "aac-max-output-channel_count";

    /**
     * A key describing a gain to be applied so that the output loudness matches the
     * Target Reference Level. This is typically used to normalize loudness across program items.
     * The gain is derived as the difference between the Target Reference Level and the
     * Program Reference Level. The latter can be given in the bitstream and indicates the actual
     * loudness value of the program item.
     * <p>The value is given as an integer value between
     * 0 and 127, and is calculated as -0.25 * Target Reference Level in dBFS.
     * Therefore, it represents the range of Full Scale (0 dBFS) to -31.75 dBFS.
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_TARGET_REFERENCE_LEVEL = "aac-target-ref-level";

    /**
     * A key describing the target reference level that was assumed at the encoder for
     * calculation of attenuation gains for clipping prevention. This information can be provided
     * if it is known, otherwise a worst-case assumption is used.
     * <p>The value is given as an integer value between
     * 0 and 127, and is calculated as -0.25 * Target Reference Level in dBFS.
     * Therefore, it represents the range of Full Scale (0 dBFS) to -31.75 dBFS.
     * The default value is the worst-case assumption of 127.
     * <p>The value is ignored when heavy compression is used (see
     * {@link #KEY_AAC_DRC_HEAVY_COMPRESSION}).
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_ENCODED_TARGET_LEVEL = "aac-encoded-target-level";

    /**
     * A key describing the boost factor allowing to adapt the dynamics of the output to the
     * actual listening requirements. This relies on DRC gain sequences that can be transmitted in
     * the encoded bitstream to be able to reduce the dynamics of the output signal upon request.
     * This factor enables the user to select how much of the gains are applied.
     * <p>Positive gains (boost) and negative gains (attenuation, see
     * {@link #KEY_AAC_DRC_ATTENUATION_FACTOR}) can be controlled separately for a better match
     * to different use-cases.
     * <p>Typically, attenuation gains are sent for loud signal segments, and boost gains are sent
     * for soft signal segments. If the output is listened to in a noisy environment, for example,
     * the boost factor is used to enable the positive gains, i.e. to amplify soft signal segments
     * beyond the noise floor. But for listening late at night, the attenuation
     * factor is used to enable the negative gains, to prevent loud signal from surprising
     * the listener. In applications which generally need a low dynamic range, both the boost factor
     * and the attenuation factor are used in order to enable all DRC gains.
     * <p>In order to prevent clipping, it is also recommended to apply the attenuation factors
     * in case of a downmix and/or loudness normalization to high target reference levels.
     * <p>Both the boost and the attenuation factor parameters are given as integer values
     * between 0 and 127, representing the range of the factor of 0 (i.e. don't apply)
     * to 1 (i.e. fully apply boost/attenuation factors respectively).
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_BOOST_FACTOR = "aac-drc-boost-level";

    /**
     * A key describing the attenuation factor allowing to adapt the dynamics of the output to the
     * actual listening requirements.
     * See {@link #KEY_AAC_DRC_BOOST_FACTOR} for a description of the role of this attenuation
     * factor and the value range.
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_ATTENUATION_FACTOR = "aac-drc-cut-level";

    /**
     * A key describing the selection of the heavy compression profile for DRC.
     * Two separate DRC gain sequences can be transmitted in one bitstream: MPEG-4 DRC light
     * compression, and DVB-specific heavy compression. When selecting the application of the heavy
     * compression, one of the sequences is selected:
     * <ul>
     * <li>0 enables light compression,</li>
     * <li>1 enables heavy compression instead.
     * </ul>
     * Note that only light compression offers the features of scaling of DRC gains
     * (see {@link #KEY_AAC_DRC_BOOST_FACTOR} and {@link #KEY_AAC_DRC_ATTENUATION_FACTOR} for the
     * boost and attenuation factors, and frequency-selective (multiband) DRC.
     * Light compression usually contains clipping prevention for stereo downmixing while heavy
     * compression, if additionally provided in the bitstream, is usually stronger, and contains
     * clipping prevention for stereo and mono downmixing.
     * <p>The default is light compression.
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_HEAVY_COMPRESSION = "aac-drc-heavy-compression";

    /**
     * A key describing the FLAC compression level to be used (FLAC audio format only).
     * The associated value is an integer ranging from 0 (fastest, least compression)
     * to 8 (slowest, most compression).
     */
    public static final String KEY_FLAC_COMPRESSION_LEVEL = "flac-compression-level";

    /**
     * A key describing the encoding complexity.
     * The associated value is an integer.  These values are device and codec specific,
     * but lower values generally result in faster and/or less power-hungry encoding.
     *
     * @see MediaCodecInfo.EncoderCapabilities#getComplexityRange()
     */
    public static final String KEY_COMPLEXITY = "complexity";

    /**
     * A key describing the desired encoding quality.
     * The associated value is an integer.  This key is only supported for encoders
     * that are configured in constant-quality mode.  These values are device and
     * codec specific, but lower values generally result in more efficient
     * (smaller-sized) encoding.
     *
     * @hide
     *
     * @see MediaCodecInfo.EncoderCapabilities#getQualityRange()
     */
    public static final String KEY_QUALITY = "quality";

    /**
     * A key describing the desired codec priority.
     * <p>
     * The associated value is an integer. Higher value means lower priority.
     * <p>
     * Currently, only two levels are supported:<br>
     * 0: realtime priority - meaning that the codec shall support the given
     *    performance configuration (e.g. framerate) at realtime. This should
     *    only be used by media playback, capture, and possibly by realtime
     *    communication scenarios if best effort performance is not suitable.<br>
     * 1: non-realtime priority (best effort).
     * <p>
     * This is a hint used at codec configuration and resource planning - to understand
     * the realtime requirements of the application; however, due to the nature of
     * media components, performance is not guaranteed.
     *
     */
    public static final String KEY_PRIORITY = "priority";

    /**
     * A key describing the desired operating frame rate for video or sample rate for audio
     * that the codec will need to operate at.
     * <p>
     * The associated value is an integer or a float representing frames-per-second or
     * samples-per-second
     * <p>
     * This is used for cases like high-speed/slow-motion video capture, where the video encoder
     * format contains the target playback rate (e.g. 30fps), but the component must be able to
     * handle the high operating capture rate (e.g. 240fps).
     * <p>
     * This rate will be used by codec for resource planning and setting the operating points.
     *
     */
    public static final String KEY_OPERATING_RATE = "operating-rate";

    /**
     * A key describing the desired profile to be used by an encoder.
     * The associated value is an integer.
     * Constants are declared in {@link MediaCodecInfo.CodecProfileLevel}.
     * This key is used as a hint, and is only supported for codecs
     * that specify a profile.
     *
     * @see MediaCodecInfo.CodecCapabilities#profileLevels
     */
    public static final String KEY_PROFILE = "profile";

    /**
     * A key describing the desired profile to be used by an encoder.
     * The associated value is an integer.
     * Constants are declared in {@link MediaCodecInfo.CodecProfileLevel}.
     * This key is used as a further hint when specifying a desired profile,
     * and is only supported for codecs that specify a level.
     * <p>
     * This key is ignored if the {@link #KEY_PROFILE profile} is not specified.
     *
     * @see MediaCodecInfo.CodecCapabilities#profileLevels
     */
    public static final String KEY_LEVEL = "level";

    /**
     * A key describing the desired clockwise rotation on an output surface.
     * This key is only used when the codec is configured using an output surface.
     * The associated value is an integer, representing degrees.
     *
     * @see MediaCodecInfo.CodecCapabilities#profileLevels
     */
    public static final String KEY_ROTATION = "rotation-degrees";

    /**
     * A key describing the desired bitrate mode to be used by an encoder.
     * Constants are declared in {@link MediaCodecInfo.CodecCapabilities}.
     *
     * @see MediaCodecInfo.EncoderCapabilities#isBitrateModeSupported(int)
     */
    public static final String KEY_BITRATE_MODE = "bitrate-mode";

    /**
     * A key describing the audio session ID of the AudioTrack associated
     * to a tunneled video codec.
     * The associated value is an integer.
     *
     * @see MediaCodecInfo.CodecCapabilities#FEATURE_TunneledPlayback
     */
    public static final String KEY_AUDIO_SESSION_ID = "audio-session-id";

    /**
     * A key for boolean AUTOSELECT behavior for the track. Tracks with AUTOSELECT=true
     * are considered when automatically selecting a track without specific user
     * choice, based on the current locale.
     * This is currently only used for subtitle tracks, when the user selected
     * 'Default' for the captioning locale.
     * The associated value is an integer, where non-0 means TRUE.  This is an optional
     * field; if not specified, AUTOSELECT defaults to TRUE.
     */
    public static final String KEY_IS_AUTOSELECT = "is-autoselect";

    /**
     * A key for boolean DEFAULT behavior for the track. The track with DEFAULT=true is
     * selected in the absence of a specific user choice.
     * This is currently only used for subtitle tracks, when the user selected
     * 'Default' for the captioning locale.
     * The associated value is an integer, where non-0 means TRUE.  This is an optional
     * field; if not specified, DEFAULT is considered to be FALSE.
     */
    public static final String KEY_IS_DEFAULT = "is-default";


    /**
     * A key for the FORCED field for subtitle tracks. True if it is a
     * forced subtitle track.  Forced subtitle tracks are essential for the
     * content and are shown even when the user turns off Captions.  They
     * are used for example to translate foreign/alien dialogs or signs.
     * The associated value is an integer, where non-0 means TRUE.  This is an
     * optional field; if not specified, FORCED defaults to FALSE.
     */
    public static final String KEY_IS_FORCED_SUBTITLE = "is-forced-subtitle";

    /** @hide */
    public static final String KEY_IS_TIMED_TEXT = "is-timed-text";

    /* package private */ MediaFormat(Map<String, Object> map) {
        mMap = map;
    }

    /**
     * Creates an empty MediaFormat
     */
    public MediaFormat() {
        mMap = new HashMap();
    }

    /* package private */ Map<String, Object> getMap() {
        return mMap;
    }

    /**
     * Returns true iff a key of the given name exists in the format.
     */
    public final boolean containsKey(String name) {
        return mMap.containsKey(name);
    }

    /**
     * A key prefix used together with a {@link MediaCodecInfo.CodecCapabilities}
     * feature name describing a required or optional feature for a codec capabilities
     * query.
     * The associated value is an integer, where non-0 value means the feature is
     * requested to be present, while 0 value means the feature is requested to be not
     * present.
     * @see MediaCodecList#findDecoderForFormat
     * @see MediaCodecList#findEncoderForFormat
     * @see MediaCodecInfo.CodecCapabilities#isFormatSupported
     *
     * @hide
     */
    public static final String KEY_FEATURE_ = "feature-";

    /**
     * Returns the value of an integer key.
     */
    public final int getInteger(String name) {
        return ((Integer)mMap.get(name)).intValue();
    }

    /**
     * Returns the value of an integer key, or the default value if the
     * key is missing or is for another type value.
     * @hide
     */
    public final int getInteger(String name, int defaultValue) {
        try {
            return getInteger(name);
        }
        catch (NullPointerException  e) { /* no such field */ }
        catch (ClassCastException e) { /* field of different type */ }
        return defaultValue;
    }

    /**
     * Returns the value of a long key.
     */
    public final long getLong(String name) {
        return ((Long)mMap.get(name)).longValue();
    }

    /**
     * Returns the value of a float key.
     */
    public final float getFloat(String name) {
        return ((Float)mMap.get(name)).floatValue();
    }

    /**
     * Returns the value of a string key.
     */
    public final String getString(String name) {
        return (String)mMap.get(name);
    }

    /**
     * Returns the value of a ByteBuffer key.
     */
    public final ByteBuffer getByteBuffer(String name) {
        return (ByteBuffer)mMap.get(name);
    }

    /**
     * Returns whether a feature is to be enabled ({@code true}) or disabled
     * ({@code false}).
     *
     * @param feature the name of a {@link MediaCodecInfo.CodecCapabilities} feature.
     *
     * @throws IllegalArgumentException if the feature was neither set to be enabled
     *        nor to be disabled.
     */
    public boolean getFeatureEnabled(String feature) {
        Integer enabled = (Integer)mMap.get(KEY_FEATURE_ + feature);
        if (enabled == null) {
            throw new IllegalArgumentException("feature is not specified");
        }
        return enabled != 0;
    }

    /**
     * Sets the value of an integer key.
     */
    public final void setInteger(String name, int value) {
        mMap.put(name, Integer.valueOf(value));
    }

    /**
     * Sets the value of a long key.
     */
    public final void setLong(String name, long value) {
        mMap.put(name, Long.valueOf(value));
    }

    /**
     * Sets the value of a float key.
     */
    public final void setFloat(String name, float value) {
        mMap.put(name, new Float(value));
    }

    /**
     * Sets the value of a string key.
     */
    public final void setString(String name, String value) {
        mMap.put(name, value);
    }

    /**
     * Sets the value of a ByteBuffer key.
     */
    public final void setByteBuffer(String name, ByteBuffer bytes) {
        mMap.put(name, bytes);
    }

    /**
     * Sets whether a feature is to be enabled ({@code true}) or disabled
     * ({@code false}).
     *
     * If {@code enabled} is {@code true}, the feature is requested to be present.
     * Otherwise, the feature is requested to be not present.
     *
     * @param feature the name of a {@link MediaCodecInfo.CodecCapabilities} feature.
     *
     * @see MediaCodecList#findDecoderForFormat
     * @see MediaCodecList#findEncoderForFormat
     * @see MediaCodecInfo.CodecCapabilities#isFormatSupported
     */
    public void setFeatureEnabled(String feature, boolean enabled) {
        setInteger(KEY_FEATURE_ + feature, enabled ? 1 : 0);
    }

    /**
     * Creates a minimal audio format.
     * @param mime The mime type of the content.
     * @param sampleRate The sampling rate of the content.
     * @param channelCount The number of audio channels in the content.
     */
    public static final MediaFormat createAudioFormat(
            String mime,
            int sampleRate,
            int channelCount) {
        MediaFormat format = new MediaFormat();
        format.setString(KEY_MIME, mime);
        format.setInteger(KEY_SAMPLE_RATE, sampleRate);
        format.setInteger(KEY_CHANNEL_COUNT, channelCount);

        return format;
    }

    /**
     * Creates a minimal subtitle format.
     * @param mime The mime type of the content.
     * @param language The language of the content, using either ISO 639-1 or 639-2/T
     *        codes.  Specify null or "und" if language information is only included
     *        in the content.  (This will also work if there are multiple language
     *        tracks in the content.)
     */
    public static final MediaFormat createSubtitleFormat(
            String mime,
            String language) {
        MediaFormat format = new MediaFormat();
        format.setString(KEY_MIME, mime);
        format.setString(KEY_LANGUAGE, language);

        return format;
    }

    /**
     * Creates a minimal video format.
     * @param mime The mime type of the content.
     * @param width The width of the content (in pixels)
     * @param height The height of the content (in pixels)
     */
    public static final MediaFormat createVideoFormat(
            String mime,
            int width,
            int height) {
        MediaFormat format = new MediaFormat();
        format.setString(KEY_MIME, mime);
        format.setInteger(KEY_WIDTH, width);
        format.setInteger(KEY_HEIGHT, height);

        return format;
    }

    @Override
    public String toString() {
        return mMap.toString();
    }
}
