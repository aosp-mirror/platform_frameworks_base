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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Encapsulates the information describing the format of media data, be it audio or video, as
 * well as optional feature metadata.
 * <p>
 * The format of the media data is specified as key/value pairs. Keys are strings. Values can
 * be integer, long, float, String or ByteBuffer.
 * <p>
 * The feature metadata is specificed as string/boolean pairs.
 * <p>
 * Keys common to all audio/video formats, <b>all keys not marked optional are mandatory</b>:
 *
 * <table>
 * <tr><th>Name</th><th>Value Type</th><th>Description</th></tr>
 * <tr><td>{@link #KEY_MIME}</td><td>String</td><td>The type of the format.</td></tr>
 * <tr><td>{@link #KEY_CODECS_STRING}</td><td>String</td><td>optional, the RFC 6381 codecs string of the MediaFormat</td></tr>
 * <tr><td>{@link #KEY_MAX_INPUT_SIZE}</td><td>Integer</td><td>optional, maximum size of a buffer of input data</td></tr>
 * <tr><td>{@link #KEY_PIXEL_ASPECT_RATIO_WIDTH}</td><td>Integer</td><td>optional, the pixel aspect ratio width</td></tr>
 * <tr><td>{@link #KEY_PIXEL_ASPECT_RATIO_HEIGHT}</td><td>Integer</td><td>optional, the pixel aspect ratio height</td></tr>
 * <tr><td>{@link #KEY_BIT_RATE}</td><td>Integer</td><td><b>encoder-only</b>, desired bitrate in bits/second</td></tr>
 * <tr><td>{@link #KEY_DURATION}</td><td>long</td><td>the duration of the content (in microseconds)</td></tr>
 * </table>
 *
 * Video formats have the following keys:
 * <table>
 * <tr><th>Name</th><th>Value Type</th><th>Description</th></tr>
 * <tr><td>{@link #KEY_WIDTH}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_HEIGHT}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_COLOR_FORMAT}</td><td>Integer</td><td>set by the user
 *         for encoders, readable in the output format of decoders</b></td></tr>
 * <tr><td>{@link #KEY_FRAME_RATE}</td><td>Integer or Float</td><td>required for <b>encoders</b>,
 *         optional for <b>decoders</b></td></tr>
 * <tr><td>{@link #KEY_CAPTURE_RATE}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_I_FRAME_INTERVAL}</td><td>Integer (or Float)</td><td><b>encoder-only</b>,
 *         time-interval between key frames.
 *         Float support added in {@link android.os.Build.VERSION_CODES#N_MR1}</td></tr>
 * <tr><td>{@link #KEY_INTRA_REFRESH_PERIOD}</td><td>Integer</td><td><b>encoder-only</b>, optional</td></tr>
 * <tr><td>{@link #KEY_LATENCY}</td><td>Integer</td><td><b>encoder-only</b>, optional</td></tr>
 * <tr><td>{@link #KEY_MAX_WIDTH}</td><td>Integer</td><td><b>decoder-only</b>, optional, max-resolution width</td></tr>
 * <tr><td>{@link #KEY_MAX_HEIGHT}</td><td>Integer</td><td><b>decoder-only</b>, optional, max-resolution height</td></tr>
 * <tr><td>{@link #KEY_REPEAT_PREVIOUS_FRAME_AFTER}</td><td>Long</td><td><b>encoder in surface-mode
 *         only</b>, optional</td></tr>
 * <tr><td>{@link #KEY_PUSH_BLANK_BUFFERS_ON_STOP}</td><td>Integer(1)</td><td><b>decoder rendering
 *         to a surface only</b>, optional</td></tr>
 * <tr><td>{@link #KEY_TEMPORAL_LAYERING}</td><td>String</td><td><b>encoder only</b>, optional,
 *         temporal-layering schema</td></tr>
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
 * <tr><td>{@link #KEY_PCM_ENCODING}</td><td>Integer</td><td>optional</td></tr>
 * <tr><td>{@link #KEY_IS_ADTS}</td><td>Integer</td><td>optional, if <em>decoding</em> AAC audio content, setting this key to 1 indicates that each audio frame is prefixed by the ADTS header.</td></tr>
 * <tr><td>{@link #KEY_AAC_PROFILE}</td><td>Integer</td><td><b>encoder-only</b>, optional, if content is AAC audio, specifies the desired profile.</td></tr>
 * <tr><td>{@link #KEY_AAC_SBR_MODE}</td><td>Integer</td><td><b>encoder-only</b>, optional, if content is AAC audio, specifies the desired SBR mode.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_TARGET_REFERENCE_LEVEL}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the target reference level.</td></tr>
 * <tr><td>{@link #KEY_AAC_ENCODED_TARGET_LEVEL}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the target reference level used at encoder.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_BOOST_FACTOR}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the DRC boost factor.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_ATTENUATION_FACTOR}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the DRC attenuation factor.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_HEAVY_COMPRESSION}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies whether to use heavy compression.</td></tr>
 * <tr><td>{@link #KEY_AAC_MAX_OUTPUT_CHANNEL_COUNT}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the maximum number of channels the decoder outputs.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_EFFECT_TYPE}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the MPEG-D DRC effect type to use.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_OUTPUT_LOUDNESS}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, returns the DRC output loudness.</td></tr>
 * <tr><td>{@link #KEY_AAC_DRC_ALBUM_MODE}</td><td>Integer</td><td><b>decoder-only</b>, optional, if content is AAC audio, specifies the whether MPEG-D DRC Album Mode is active or not.</td></tr>
 * <tr><td>{@link #KEY_CHANNEL_MASK}</td><td>Integer</td><td>optional, a mask of audio channel assignments</td></tr>
 * <tr><td>{@link #KEY_ENCODER_DELAY}</td><td>Integer</td><td>optional, the number of frames to trim from the start of the decoded audio stream.</td></tr>
 * <tr><td>{@link #KEY_ENCODER_PADDING}</td><td>Integer</td><td>optional, the number of frames to trim from the end of the decoded audio stream.</td></tr>
 * <tr><td>{@link #KEY_FLAC_COMPRESSION_LEVEL}</td><td>Integer</td><td><b>encoder-only</b>, optional, if content is FLAC audio, specifies the desired compression level.</td></tr>
 * </table>
 *
 * Subtitle formats have the following keys:
 * <table>
 * <tr><td>{@link #KEY_MIME}</td><td>String</td><td>The type of the format.</td></tr>
 * <tr><td>{@link #KEY_LANGUAGE}</td><td>String</td><td>The language of the content.</td></tr>
 * <tr><td>{@link #KEY_CAPTION_SERVICE_NUMBER}</td><td>int</td><td>optional, the closed-caption service or channel number.</td></tr>
 * </table>
 *
 * Image formats have the following keys:
 * <table>
 * <tr><td>{@link #KEY_MIME}</td><td>String</td><td>The type of the format.</td></tr>
 * <tr><td>{@link #KEY_WIDTH}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_HEIGHT}</td><td>Integer</td><td></td></tr>
 * <tr><td>{@link #KEY_COLOR_FORMAT}</td><td>Integer</td><td>set by the user
 *         for encoders, readable in the output format of decoders</b></td></tr>
 * <tr><td>{@link #KEY_TILE_WIDTH}</td><td>Integer</td><td>required if the image has grid</td></tr>
 * <tr><td>{@link #KEY_TILE_HEIGHT}</td><td>Integer</td><td>required if the image has grid</td></tr>
 * <tr><td>{@link #KEY_GRID_ROWS}</td><td>Integer</td><td>required if the image has grid</td></tr>
 * <tr><td>{@link #KEY_GRID_COLUMNS}</td><td>Integer</td><td>required if the image has grid</td></tr>
 * </table>
 */
public final class MediaFormat {
    public static final String MIMETYPE_VIDEO_VP8 = "video/x-vnd.on2.vp8";
    public static final String MIMETYPE_VIDEO_VP9 = "video/x-vnd.on2.vp9";
    public static final String MIMETYPE_VIDEO_AV1 = "video/av01";
    public static final String MIMETYPE_VIDEO_AVC = "video/avc";
    public static final String MIMETYPE_VIDEO_HEVC = "video/hevc";
    public static final String MIMETYPE_VIDEO_MPEG4 = "video/mp4v-es";
    public static final String MIMETYPE_VIDEO_H263 = "video/3gpp";
    public static final String MIMETYPE_VIDEO_MPEG2 = "video/mpeg2";
    public static final String MIMETYPE_VIDEO_RAW = "video/raw";
    public static final String MIMETYPE_VIDEO_DOLBY_VISION = "video/dolby-vision";
    public static final String MIMETYPE_VIDEO_SCRAMBLED = "video/scrambled";

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
    public static final String MIMETYPE_AUDIO_EAC3_JOC = "audio/eac3-joc";
    public static final String MIMETYPE_AUDIO_AC4 = "audio/ac4";
    public static final String MIMETYPE_AUDIO_SCRAMBLED = "audio/scrambled";
    /** MIME type for MPEG-H Audio single stream */
    public static final String MIMETYPE_AUDIO_MPEGH_MHA1 = "audio/mha1";
    /** MIME type for MPEG-H Audio single stream, encapsulated in MHAS */
    public static final String MIMETYPE_AUDIO_MPEGH_MHM1 = "audio/mhm1";

    /**
     * MIME type for HEIF still image data encoded in HEVC.
     *
     * To decode such an image, {@link MediaCodec} decoder for
     * {@link #MIMETYPE_VIDEO_HEVC} shall be used. The client needs to form
     * the correct {@link #MediaFormat} based on additional information in
     * the track format, and send it to {@link MediaCodec#configure}.
     *
     * The track's MediaFormat will come with {@link #KEY_WIDTH} and
     * {@link #KEY_HEIGHT} keys, which describes the width and height
     * of the image. If the image doesn't contain grid (i.e. none of
     * {@link #KEY_TILE_WIDTH}, {@link #KEY_TILE_HEIGHT},
     * {@link #KEY_GRID_ROWS}, {@link #KEY_GRID_COLUMNS} are present}), the
     * track will contain a single sample of coded data for the entire image,
     * and the image width and height should be used to set up the decoder.
     *
     * If the image does come with grid, each sample from the track will
     * contain one tile in the grid, of which the size is described by
     * {@link #KEY_TILE_WIDTH} and {@link #KEY_TILE_HEIGHT}. This size
     * (instead of {@link #KEY_WIDTH} and {@link #KEY_HEIGHT}) should be
     * used to set up the decoder. The track contains {@link #KEY_GRID_ROWS}
     * by {@link #KEY_GRID_COLUMNS} samples in row-major, top-row first,
     * left-to-right order. The output image should be reconstructed by
     * first tiling the decoding results of the tiles in the correct order,
     * then trimming (before rotation is applied) on the bottom and right
     * side, if the tiled area is larger than the image width and height.
     */
    public static final String MIMETYPE_IMAGE_ANDROID_HEIC = "image/vnd.android.heic";

    /**
     * MIME type for WebVTT subtitle data.
     */
    public static final String MIMETYPE_TEXT_VTT = "text/vtt";

    /**
     * MIME type for SubRip (SRT) container.
     */
    public static final String MIMETYPE_TEXT_SUBRIP = "application/x-subrip";

    /**
     * MIME type for CEA-608 closed caption data.
     */
    public static final String MIMETYPE_TEXT_CEA_608 = "text/cea-608";

    /**
     * MIME type for CEA-708 closed caption data.
     */
    public static final String MIMETYPE_TEXT_CEA_708 = "text/cea-708";

    @UnsupportedAppUsage
    private Map<String, Object> mMap;

    /**
     * A key describing the log session ID for MediaCodec. The log session ID is a random 32-byte
     * hexadecimal string that is used to associate metrics from multiple media codec instances
     * to the same playback or recording session.
     * The associated value is a string.
     * @hide
     */
    public static final String LOG_SESSION_ID = "log-session-id";

    /**
     * A key describing the mime type of the MediaFormat.
     * The associated value is a string.
     */
    public static final String KEY_MIME = "mime";

    /**
     * A key describing the codecs string of the MediaFormat. See RFC 6381 section 3.2 for the
     * syntax of the value. The value does not hold {@link MediaCodec}-exposed codec names.
     * The associated value is a string.
     *
     * @see MediaParser.TrackData#mediaFormat
     */
    public static final String KEY_CODECS_STRING = "codecs-string";

    /**
     * An optional key describing the low latency decoding mode. This is an optional parameter
     * that applies only to decoders. If enabled, the decoder doesn't hold input and output
     * data more than required by the codec standards.
     * The associated value is an integer (0 or 1): 1 when low-latency decoding is enabled,
     * 0 otherwise. The default value is 0.
     */
    public static final String KEY_LOW_LATENCY = "low-latency";

    /**
     * A key describing the language of the content, using either ISO 639-1
     * or 639-2/T codes.  The associated value is a string.
     */
    public static final String KEY_LANGUAGE = "language";

    /**
     * A key describing the closed caption service number. For CEA-608 caption tracks, holds the
     * channel number. For CEA-708, holds the service number.
     * The associated value is an int.
     */
    public static final String KEY_CAPTION_SERVICE_NUMBER = "caption-service-number";

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
     * A key describing the pixel aspect ratio width.
     * The associated value is an integer
     */
    public static final String KEY_PIXEL_ASPECT_RATIO_WIDTH = "sar-width";

    /**
     * A key describing the pixel aspect ratio height.
     * The associated value is an integer
     */
    public static final String KEY_PIXEL_ASPECT_RATIO_HEIGHT = "sar-height";

    /**
     * A key describing the average bitrate in bits/sec.
     * The associated value is an integer
     */
    public static final String KEY_BIT_RATE = "bitrate";

    /**
     * A key describing the hardware AV sync id.
     * The associated value is an integer
     *
     * See android.media.tv.tuner.Tuner#getAvSyncHwId.
     */
    public static final String KEY_HARDWARE_AV_SYNC_ID = "hw-av-sync-id";

    /**
     * A key describing the max bitrate in bits/sec.
     * This is usually over a one-second sliding window (e.g. over any window of one second).
     * The associated value is an integer
     * @hide
     */
    public static final String KEY_MAX_BIT_RATE = "max-bitrate";

    /**
     * A key describing the color format of the content in a video format.
     * Constants are declared in {@link android.media.MediaCodecInfo.CodecCapabilities}.
     */
    public static final String KEY_COLOR_FORMAT = "color-format";

    /**
     * A key describing the frame rate of a video format in frames/sec.
     * The associated value is normally an integer when the value is used by the platform,
     * but video codecs also accept float configuration values.
     * Specifically, {@link MediaExtractor#getTrackFormat MediaExtractor} provides an integer
     * value corresponding to the frame rate information of the track if specified and non-zero.
     * Otherwise, this key is not present. {@link MediaCodec#configure MediaCodec} accepts both
     * float and integer values. This represents the desired operating frame rate if the
     * {@link #KEY_OPERATING_RATE} is not present and {@link #KEY_PRIORITY} is {@code 0}
     * (realtime). For video encoders this value corresponds to the intended frame rate,
     * although encoders are expected
     * to support variable frame rate based on {@link MediaCodec.BufferInfo#presentationTimeUs
     * buffer timestamp}. This key is not used in the {@code MediaCodec}
     * {@link MediaCodec#getInputFormat input}/{@link MediaCodec#getOutputFormat output} formats,
     * nor by {@link MediaMuxer#addTrack MediaMuxer}.
     */
    public static final String KEY_FRAME_RATE = "frame-rate";

    /**
     * A key describing the width (in pixels) of each tile of the content in a
     * {@link #MIMETYPE_IMAGE_ANDROID_HEIC} track. The associated value is an integer.
     *
     * Refer to {@link #MIMETYPE_IMAGE_ANDROID_HEIC} on decoding instructions of such tracks.
     *
     * @see #KEY_TILE_HEIGHT
     * @see #KEY_GRID_ROWS
     * @see #KEY_GRID_COLUMNS
     */
    public static final String KEY_TILE_WIDTH = "tile-width";

    /**
     * A key describing the height (in pixels) of each tile of the content in a
     * {@link #MIMETYPE_IMAGE_ANDROID_HEIC} track. The associated value is an integer.
     *
     * Refer to {@link #MIMETYPE_IMAGE_ANDROID_HEIC} on decoding instructions of such tracks.
     *
     * @see #KEY_TILE_WIDTH
     * @see #KEY_GRID_ROWS
     * @see #KEY_GRID_COLUMNS
     */
    public static final String KEY_TILE_HEIGHT = "tile-height";

    /**
     * A key describing the number of grid rows in the content in a
     * {@link #MIMETYPE_IMAGE_ANDROID_HEIC} track. The associated value is an integer.
     *
     * Refer to {@link #MIMETYPE_IMAGE_ANDROID_HEIC} on decoding instructions of such tracks.
     *
     * @see #KEY_TILE_WIDTH
     * @see #KEY_TILE_HEIGHT
     * @see #KEY_GRID_COLUMNS
     */
    public static final String KEY_GRID_ROWS = "grid-rows";

    /**
     * A key describing the number of grid columns in the content in a
     * {@link #MIMETYPE_IMAGE_ANDROID_HEIC} track. The associated value is an integer.
     *
     * Refer to {@link #MIMETYPE_IMAGE_ANDROID_HEIC} on decoding instructions of such tracks.
     *
     * @see #KEY_TILE_WIDTH
     * @see #KEY_TILE_HEIGHT
     * @see #KEY_GRID_ROWS
     */
    public static final String KEY_GRID_COLUMNS = "grid-cols";

    /**
     * A key describing the raw audio sample encoding/format.
     *
     * <p>The associated value is an integer, using one of the
     * {@link AudioFormat}.ENCODING_PCM_ values.</p>
     *
     * <p>This is an optional key for audio decoders and encoders specifying the
     * desired raw audio sample format during {@link MediaCodec#configure
     * MediaCodec.configure(&hellip;)} call. Use {@link MediaCodec#getInputFormat
     * MediaCodec.getInput}/{@link MediaCodec#getOutputFormat OutputFormat(&hellip;)}
     * to confirm the actual format. For the PCM decoder this key specifies both
     * input and output sample encodings.</p>
     *
     * <p>This key is also used by {@link MediaExtractor} to specify the sample
     * format of audio data, if it is specified.</p>
     *
     * <p>If this key is missing, the raw audio sample format is signed 16-bit short.</p>
     */
    public static final String KEY_PCM_ENCODING = "pcm-encoding";

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
     * A key for retrieving the slow-motion marker information associated with a video track.
     * <p>
     * The associated value is a ByteBuffer in {@link ByteOrder#BIG_ENDIAN}
     * (networking order) of the following format:
     * </p>
     * <pre class="prettyprint">
     *     float(32) playbackRate;
     *     unsigned int(32) numMarkers;
     *     for (i = 0;i < numMarkers; i++) {
     *         int(64) timestampUs;
     *         float(32) speedRatio;
     *     }</pre>
     * The meaning of each field is as follows:
     * <table border="1" width="90%" align="center" cellpadding="5">
     *     <tbody>
     *     <tr>
     *         <td>playbackRate</td>
     *         <td>The frame rate at which the playback should happen (or the flattened
     *             clip should be).</td>
     *     </tr>
     *     <tr>
     *         <td>numMarkers</td>
     *         <td>The number of slow-motion markers that follows.</td>
     *     </tr>
     *     <tr>
     *         <td>timestampUs</td>
     *         <td>The starting point of a new segment.</td>
     *     </tr>
     *     <tr>
     *         <td>speedRatio</td>
     *         <td>The playback speed for that segment. The playback speed is a floating
     *             point number, indicating how fast the time progresses relative to that
     *             written in the container. (Eg. 4.0 means time goes 4x as fast, which
     *             makes 30fps become 120fps.)</td>
     *     </tr>
     * </table>
     * <p>
     * The following constraints apply to the timestampUs of the markers:
     * </p>
     * <li>The timestampUs shall be monotonically increasing.</li>
     * <li>The timestampUs shall fall within the time span of the video track.</li>
     * <li>The first timestampUs should match that of the first video sample.</li>
     *
     * @hide
     */
    public static final String KEY_SLOW_MOTION_MARKERS = "slow-motion-markers";

    /**
     * A key describing the frequency of key frames expressed in seconds between key frames.
     * <p>
     * This key is used by video encoders.
     * A negative value means no key frames are requested after the first frame.
     * A zero value means a stream containing all key frames is requested.
     * <p class=note>
     * Most video encoders will convert this value of the number of non-key-frames between
     * key-frames, using the {@linkplain #KEY_FRAME_RATE frame rate} information; therefore,
     * if the actual frame rate differs (e.g. input frames are dropped or the frame rate
     * changes), the <strong>time interval</strong> between key frames will not be the
     * configured value.
     * <p>
     * The associated value is an integer (or float since
     * {@link android.os.Build.VERSION_CODES#N_MR1}).
     */
    public static final String KEY_I_FRAME_INTERVAL = "i-frame-interval";

    /**
    * An optional key describing the period of intra refresh in frames. This is an
    * optional parameter that applies only to video encoders. If encoder supports it
    * ({@link MediaCodecInfo.CodecCapabilities#FEATURE_IntraRefresh}), the whole
    * frame is completely refreshed after the specified period. Also for each frame,
    * a fix subset of macroblocks must be intra coded which leads to more constant bitrate
    * than inserting a key frame. This key is recommended for video streaming applications
    * as it provides low-delay and good error-resilience. This key is ignored if the
    * video encoder does not support the intra refresh feature. Use the output format to
    * verify that this feature was enabled.
    * The associated value is an integer.
    */
    public static final String KEY_INTRA_REFRESH_PERIOD = "intra-refresh-period";

    /**
     * An optional key describing whether encoders prepend headers to sync frames (e.g.
     * SPS and PPS to IDR frames for H.264). This is an optional parameter that applies only
     * to video encoders. A video encoder may not support this feature; the component will fail
     * to configure in that case. For other components, this key is ignored.
     *
     * The value is an integer, with 1 indicating to prepend headers to every sync frames,
     * or 0 otherwise. The default value is 0.
     */
    public static final String KEY_PREPEND_HEADER_TO_SYNC_FRAMES = "prepend-sps-pps-to-idr-frames";

    /**
     * A key describing the temporal layering schema.  This is an optional parameter
     * that applies only to video encoders.  Use {@link MediaCodec#getOutputFormat}
     * after {@link MediaCodec#configure configure} to query if the encoder supports
     * the desired schema. Supported values are {@code webrtc.vp8.N-layer},
     * {@code android.generic.N}, {@code android.generic.N+M} and {@code none}, where
     * {@code N} denotes the total number of non-bidirectional layers (which must be at least 1)
     * and {@code M} denotes the total number of bidirectional layers (which must be non-negative).
     * <p class=note>{@code android.generic.*} schemas have been added in {@link
     * android.os.Build.VERSION_CODES#N_MR1}.
     * <p>
     * The encoder may support fewer temporal layers, in which case the output format
     * will contain the configured schema. If the encoder does not support temporal
     * layering, the output format will not have an entry with this key.
     * The associated value is a string.
     */
    public static final String KEY_TEMPORAL_LAYERING = "ts-schema";

    /**
     * A key describing the stride of the video bytebuffer layout.
     * Stride (or row increment) is the difference between the index of a pixel
     * and that of the pixel directly underneath. For YUV 420 formats, the
     * stride corresponds to the Y plane; the stride of the U and V planes can
     * be calculated based on the color format, though it is generally undefined
     * and depends on the device and release.
     * The associated value is an integer, representing number of bytes.
     */
    public static final String KEY_STRIDE = "stride";

    /**
     * A key describing the plane height of a multi-planar (YUV) video bytebuffer layout.
     * Slice height (or plane height/vertical stride) is the number of rows that must be skipped
     * to get from the top of the Y plane to the top of the U plane in the bytebuffer. In essence
     * the offset of the U plane is sliceHeight * stride. The height of the U/V planes
     * can be calculated based on the color format, though it is generally undefined
     * and depends on the device and release.
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
     * Instruct the video encoder in "surface-input" mode to drop excessive
     * frames from the source, so that the input frame rate to the encoder
     * does not exceed the specified fps.
     *
     * The associated value is a float, representing the max frame rate to
     * feed the encoder at.
     *
     */
    public static final String KEY_MAX_FPS_TO_ENCODER
        = "max-fps-to-encoder";

    /**
     * Instruct the video encoder in "surface-input" mode to limit the gap of
     * timestamp between any two adjacent frames fed to the encoder to the
     * specified amount (in micro-second).
     *
     * The associated value is a long int. When positive, it represents the max
     * timestamp gap between two adjacent frames fed to the encoder. When negative,
     * the absolute value represents a fixed timestamp gap between any two adjacent
     * frames fed to the encoder. Note that this will also apply even when the
     * original timestamp goes backward in time. Under normal conditions, such frames
     * would be dropped and not sent to the encoder.
     *
     * The output timestamp will be restored to the original timestamp and will
     * not be affected.
     *
     * This is used in some special scenarios where input frames arrive sparingly
     * but it's undesirable to allocate more bits to any single frame, or when it's
     * important to ensure all frames are captured (rather than captured in the
     * correct order).
     *
     */
    public static final String KEY_MAX_PTS_GAP_TO_ENCODER
        = "max-pts-gap-to-encoder";

    /**
     * If specified when configuring a video encoder that's in "surface-input"
     * mode, it will instruct the encoder to put the surface source in suspended
     * state when it's connected. No video frames will be accepted until a resume
     * operation (see {@link MediaCodec#PARAMETER_KEY_SUSPEND}), optionally with
     * timestamp specified via {@link MediaCodec#PARAMETER_KEY_SUSPEND_TIME}, is
     * received.
     *
     * The value is an integer, with 1 indicating to create with the surface
     * source suspended, or 0 otherwise. The default value is 0.
     *
     * If this key is not set or set to 0, the surface source will accept buffers
     * as soon as it's connected to the encoder (although they may not be encoded
     * immediately). This key can be used when the client wants to prepare the
     * encoder session in advance, but do not want to accept buffers immediately.
     */
    public static final String KEY_CREATE_INPUT_SURFACE_SUSPENDED
        = "create-input-buffers-suspended";

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
     * A key describing the number of frames to trim from the start of the decoded audio stream.
     * The associated value is an integer.
     */
    public static final String KEY_ENCODER_DELAY = "encoder-delay";

    /**
     * A key describing the number of frames to trim from the end of the decoded audio stream.
     * The associated value is an integer.
     */
    public static final String KEY_ENCODER_PADDING = "encoder-padding";

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
     * A key describing the Target Reference Level (Target Loudness).
     * <p>For normalizing loudness across program items, a gain is applied to the audio output so
     * that the output loudness matches the Target Reference Level. The gain is derived as the
     * difference between the Target Reference Level and the Program Reference Level (Program
     * Loudness). The latter can be given in the bitstream and indicates the actual loudness value
     * of the program item.</p>
     * <p>The Target Reference Level controls loudness normalization for both MPEG-4 DRC and
     * MPEG-D DRC.
     * <p>The value is given as an integer value between
     * 40 and 127, and is calculated as -4 * Target Reference Level in LKFS.
     * Therefore, it represents the range of -10 to -31.75 LKFS.
     * <p>For MPEG-4 DRC, a value of -1 switches off loudness normalization and DRC processing.</p>
     * <p>For MPEG-D DRC, a value of -1 switches off loudness normalization only. For DRC processing
     * options of MPEG-D DRC, see {@link #KEY_AAC_DRC_EFFECT_TYPE}</p>
     * <p>The default value on mobile devices is 64 (-16 LKFS).
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_TARGET_REFERENCE_LEVEL = "aac-target-ref-level";

    /**
     * A key describing for selecting the DRC effect type for MPEG-D DRC.
     * The supported values are defined in ISO/IEC 23003-4:2015 and are described as follows:
     * <table>
     * <tr><th>Value</th><th>Effect</th></tr>
     * <tr><th>-1</th><th>Off</th></tr>
     * <tr><th>0</th><th>None</th></tr>
     * <tr><th>1</th><th>Late night</th></tr>
     * <tr><th>2</th><th>Noisy environment</th></tr>
     * <tr><th>3</th><th>Limited playback range</th></tr>
     * <tr><th>4</th><th>Low playback level</th></tr>
     * <tr><th>5</th><th>Dialog enhancement</th></tr>
     * <tr><th>6</th><th>General compression</th></tr>
     * </table>
     * <p>The value -1 (Off) disables DRC processing, while loudness normalization may still be
     * active and dependent on {@link #KEY_AAC_DRC_TARGET_REFERENCE_LEVEL}.<br>
     * The value 0 (None) automatically enables DRC processing if necessary to prevent signal
     * clipping<br>
     * The value 6 (General compression) can be used for enabling MPEG-D DRC without particular
     * DRC effect type request.<br>
     * The default DRC effect type is 3 ("Limited playback range") on mobile devices.
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_EFFECT_TYPE = "aac-drc-effect-type";

    /**
     * A key describing the target reference level that was assumed at the encoder for
     * calculation of attenuation gains for clipping prevention.
     * <p>If it is known, this information can be provided as an integer value between
     * 0 and 127, which is calculated as -4 * Encoded Target Level in LKFS.
     * If the Encoded Target Level is unknown, the value can be set to -1.
     * <p>The default value is -1 (unknown).
     * <p>The value is ignored when heavy compression (see {@link #KEY_AAC_DRC_HEAVY_COMPRESSION})
     * or MPEG-D DRC is used.
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
     * <p>In order to prevent clipping, it is also recommended to apply the attenuation gains
     * in case of a downmix and/or loudness normalization to high target reference levels.
     * <p>Both the boost and the attenuation factor parameters are given as integer values
     * between 0 and 127, representing the range of the factor of 0 (i.e. don't apply)
     * to 1 (i.e. fully apply boost/attenuation gains respectively).
     * <p>The default value is 127 (fully apply boost DRC gains).
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_BOOST_FACTOR = "aac-drc-boost-level";

    /**
     * A key describing the attenuation factor allowing to adapt the dynamics of the output to the
     * actual listening requirements.
     * See {@link #KEY_AAC_DRC_BOOST_FACTOR} for a description of the role of this attenuation
     * factor and the value range.
     * <p>The default value is 127 (fully apply attenuation DRC gains).
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_ATTENUATION_FACTOR = "aac-drc-cut-level";

    /**
     * A key describing the selection of the heavy compression profile for MPEG-4 DRC.
     * <p>Two separate DRC gain sequences can be transmitted in one bitstream: light compression
     * and heavy compression. When selecting the application of the heavy compression, one of
     * the sequences is selected:
     * <ul>
     * <li>0 enables light compression,</li>
     * <li>1 enables heavy compression instead.
     * </ul>
     * Note that heavy compression doesn't offer the features of scaling of DRC gains
     * (see {@link #KEY_AAC_DRC_BOOST_FACTOR} and {@link #KEY_AAC_DRC_ATTENUATION_FACTOR} for the
     * boost and attenuation factors), and frequency-selective (multiband) DRC.
     * Light compression usually contains clipping prevention for stereo downmixing while heavy
     * compression, if additionally provided in the bitstream, is usually stronger, and contains
     * clipping prevention for stereo and mono downmixing.
     * <p>The default is 1 (heavy compression).
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_HEAVY_COMPRESSION = "aac-drc-heavy-compression";

    /**
     * A key to retrieve the output loudness of a decoded bitstream.
     * <p>If loudness normalization is active, the value corresponds to the Target Reference Level
     * (see {@link #KEY_AAC_DRC_TARGET_REFERENCE_LEVEL}).<br>
     * If loudness normalization is not active, the value corresponds to the loudness metadata
     * given in the bitstream.
     * <p>The value is retrieved with getInteger() and is given as an integer value between 0 and
     * 231. It is calculated as -4 * Output Loudness in LKFS. Therefore, it represents the range of
     * 0 to -57.75 LKFS.
     * <p>A value of -1 indicates that no loudness metadata is present in the bitstream.
     * <p>Loudness metadata can originate from MPEG-4 DRC or MPEG-D DRC.
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_OUTPUT_LOUDNESS = "aac-drc-output-loudness";

    /**
     * A key describing the album mode for MPEG-D DRC as defined in ISO/IEC 23003-4.
     * <p>The associated value is an integer and can be set to following values:
     * <table>
     * <tr><th>Value</th><th>Album Mode</th></tr>
     * <tr><th>0</th><th>disabled</th></tr>
     * <tr><th>1</th><th>enabled</th></tr>
     * </table>
     * <p>Disabled album mode leads to application of gain sequences for fading in and out, if
     * provided in the bitstream. Enabled album mode makes use of dedicated album loudness
     * information, if provided in the bitstream.
     * <p>The default value is 0 (album mode disabled).
     * <p>This key is only used during decoding.
     */
    public static final String KEY_AAC_DRC_ALBUM_MODE = "aac-drc-album-mode";

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
     * that specify a profile. Note: Codecs are free to use all the available
     * coding tools at the specified profile.
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
    * An optional key describing the desired encoder latency in frames. This is an optional
    * parameter that applies only to video encoders. If encoder supports it, it should ouput
    * at least one output frame after being queued the specified number of frames. This key
    * is ignored if the video encoder does not support the latency feature. Use the output
    * format to verify that this feature was enabled and the actual value used by the encoder.
    * <p>
    * If the key is not specified, the default latency will be implenmentation specific.
    * The associated value is an integer.
    */
    public static final String KEY_LATENCY = "latency";

    /**
     * An optional key describing the maximum number of non-display-order coded frames.
     * This is an optional parameter that applies only to video encoders. Application should
     * check the value for this key in the output format to see if codec will produce
     * non-display-order coded frames. If encoder supports it, the output frames' order will be
     * different from the display order and each frame's display order could be retrived from
     * {@link MediaCodec.BufferInfo#presentationTimeUs}. Before API level 27, application may
     * receive non-display-order coded frames even though the application did not request it.
     * Note: Application should not rearrange the frames to display order before feeding them
     * to {@link MediaMuxer#writeSampleData}.
     * <p>
     * The default value is 0.
     */
    public static final String KEY_OUTPUT_REORDER_DEPTH = "output-reorder-depth";

    /**
     * A key describing the desired clockwise rotation on an output surface.
     * This key is only used when the codec is configured using an output surface.
     * The associated value is an integer, representing degrees. Supported values
     * are 0, 90, 180 or 270. This is an optional field; if not specified, rotation
     * defaults to 0.
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
     * A key describing the maximum Quantization Parameter allowed for encoding video.
     * This key applies to all three video picture types (I, P, and B).
     * The value is used directly for picture type I; a per-mime formula is used
     * to calculate the value for the remaining picture types.
     *
     * This calculation can be avoided by directly specifying values for each picture type
     * using the type-specific keys {@link #KEY_VIDEO_QP_I_MAX}, {@link #KEY_VIDEO_QP_P_MAX},
     * and {@link #KEY_VIDEO_QP_B_MAX}.
     *
     * The associated value is an integer.
     */
    public static final String KEY_VIDEO_QP_MAX = "video-qp-max";

    /**
     * A key describing the minimum Quantization Parameter allowed for encoding video.
     * This key applies to all three video frame types (I, P, and B).
     * The value is used directly for picture type I; a per-mime formula is used
     * to calculate the value for the remaining picture types.
     *
     * This calculation can be avoided by directly specifying values for each picture type
     * using the type-specific keys {@link #KEY_VIDEO_QP_I_MIN}, {@link #KEY_VIDEO_QP_P_MIN},
     * and {@link #KEY_VIDEO_QP_B_MIN}.
     *
     * The associated value is an integer.
     */
    public static final String KEY_VIDEO_QP_MIN = "video-qp-min";

    /**
     * A key describing the maximum Quantization Parameter allowed for encoding video.
     * This value applies to video I-frames.
     *
     * The associated value is an integer.
     */
    public static final String KEY_VIDEO_QP_I_MAX = "video-qp-i-max";

    /**
     * A key describing the minimum Quantization Parameter allowed for encoding video.
     * This value applies to video I-frames.
     *
     * The associated value is an integer.
     */
    public static final String KEY_VIDEO_QP_I_MIN = "video-qp-i-min";

    /**
     * A key describing the maximum Quantization Parameter allowed for encoding video.
     * This value applies to video P-frames.
     *
     * The associated value is an integer.
     */
    public static final String KEY_VIDEO_QP_P_MAX = "video-qp-p-max";

    /**
     * A key describing the minimum Quantization Parameter allowed for encoding video.
     * This value applies to video P-frames.
     *
     * The associated value is an integer.
     */
    public static final String KEY_VIDEO_QP_P_MIN = "video-qp-p-min";

    /**
     * A key describing the maximum Quantization Parameter allowed for encoding video.
     * This value applies to video B-frames.
     *
     * The associated value is an integer.
     */
    public static final String KEY_VIDEO_QP_B_MAX = "video-qp-b-max";

    /**
     * A key describing the minimum Quantization Parameter allowed for encoding video.
     * This value applies to video B-frames.
     *
     * The associated value is an integer.
     */
    public static final String KEY_VIDEO_QP_B_MIN = "video-qp-b-min";

    /**
     * A key describing the audio session ID of the AudioTrack associated
     * to a tunneled video codec.
     * The associated value is an integer.
     *
     * @see MediaCodecInfo.CodecCapabilities#FEATURE_TunneledPlayback
     */
    public static final String KEY_AUDIO_SESSION_ID = "audio-session-id";

    /**
     * A key describing the audio hardware sync ID of the AudioTrack associated
     * to a tunneled video codec. The associated value is an integer.
     *
     * @hide
     *
     * @see MediaCodecInfo.CodecCapabilities#FEATURE_TunneledPlayback
     * @see AudioManager#getAudioHwSyncForSession
     */
    public static final String KEY_AUDIO_HW_SYNC = "audio-hw-sync";

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
     * This is currently used in two scenarios:
     * 1) for subtitle tracks, when the user selected 'Default' for the captioning locale.
     * 2) for a {@link #MIMETYPE_IMAGE_ANDROID_HEIC} track, indicating the image is the
     * primary item in the file.

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

    /**
     * A key describing the number of haptic channels in an audio format.
     * The associated value is an integer.
     */
    public static final String KEY_HAPTIC_CHANNEL_COUNT = "haptic-channel-count";

    /** @hide */
    public static final String KEY_IS_TIMED_TEXT = "is-timed-text";

    // The following color aspect values must be in sync with the ones in HardwareAPI.h.
    /**
     * An optional key describing the color primaries, white point and
     * luminance factors for video content.
     *
     * The associated value is an integer: 0 if unspecified, or one of the
     * COLOR_STANDARD_ values.
     */
    public static final String KEY_COLOR_STANDARD = "color-standard";

    /** BT.709 color chromacity coordinates with KR = 0.2126, KB = 0.0722. */
    public static final int COLOR_STANDARD_BT709 = 1;

    /** BT.601 625 color chromacity coordinates with KR = 0.299, KB = 0.114. */
    public static final int COLOR_STANDARD_BT601_PAL = 2;

    /** BT.601 525 color chromacity coordinates with KR = 0.299, KB = 0.114. */
    public static final int COLOR_STANDARD_BT601_NTSC = 4;

    /** BT.2020 color chromacity coordinates with KR = 0.2627, KB = 0.0593. */
    public static final int COLOR_STANDARD_BT2020 = 6;

    /** @hide */
    @IntDef({
        COLOR_STANDARD_BT709,
        COLOR_STANDARD_BT601_PAL,
        COLOR_STANDARD_BT601_NTSC,
        COLOR_STANDARD_BT2020,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ColorStandard {}

    /**
     * An optional key describing the opto-electronic transfer function used
     * for the video content.
     *
     * The associated value is an integer: 0 if unspecified, or one of the
     * COLOR_TRANSFER_ values.
     */
    public static final String KEY_COLOR_TRANSFER = "color-transfer";

    /** Linear transfer characteristic curve. */
    public static final int COLOR_TRANSFER_LINEAR = 1;

    /** SMPTE 170M transfer characteristic curve used by BT.601/BT.709/BT.2020. This is the curve
     *  used by most non-HDR video content. */
    public static final int COLOR_TRANSFER_SDR_VIDEO = 3;

    /** SMPTE ST 2084 transfer function. This is used by some HDR video content. */
    public static final int COLOR_TRANSFER_ST2084 = 6;

    /** ARIB STD-B67 hybrid-log-gamma transfer function. This is used by some HDR video content. */
    public static final int COLOR_TRANSFER_HLG = 7;

    /** @hide */
    @IntDef({
        COLOR_TRANSFER_LINEAR,
        COLOR_TRANSFER_SDR_VIDEO,
        COLOR_TRANSFER_ST2084,
        COLOR_TRANSFER_HLG,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ColorTransfer {}

    /**
     * An optional key describing the range of the component values of the video content.
     *
     * The associated value is an integer: 0 if unspecified, or one of the
     * COLOR_RANGE_ values.
     */
    public static final String KEY_COLOR_RANGE = "color-range";

    /** Limited range. Y component values range from 16 to 235 for 8-bit content.
     *  Cr, Cy values range from 16 to 240 for 8-bit content.
     *  This is the default for video content. */
    public static final int COLOR_RANGE_LIMITED = 2;

    /** Full range. Y, Cr and Cb component values range from 0 to 255 for 8-bit content. */
    public static final int COLOR_RANGE_FULL = 1;

    /** @hide */
    @IntDef({
        COLOR_RANGE_LIMITED,
        COLOR_RANGE_FULL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ColorRange {}

    /**
     * An optional key describing the static metadata of HDR (high-dynamic-range) video content.
     *
     * The associated value is a ByteBuffer. This buffer contains the raw contents of the
     * Static Metadata Descriptor (including the descriptor ID) of an HDMI Dynamic Range and
     * Mastering InfoFrame as defined by CTA-861.3. This key must be provided to video decoders
     * for HDR video content unless this information is contained in the bitstream and the video
     * decoder supports an HDR-capable profile. This key must be provided to video encoders for
     * HDR video content.
     */
    public static final String KEY_HDR_STATIC_INFO = "hdr-static-info";

    /**
     * An optional key describing the HDR10+ metadata of the video content.
     *
     * The associated value is a ByteBuffer containing HDR10+ metadata conforming to the
     * user_data_registered_itu_t_t35() syntax of SEI message for ST 2094-40. This key will
     * be present on:
     *<p>
     * - The formats of output buffers of a decoder configured for HDR10+ profiles (such as
     *   {@link MediaCodecInfo.CodecProfileLevel#VP9Profile2HDR10Plus}, {@link
     *   MediaCodecInfo.CodecProfileLevel#VP9Profile3HDR10Plus} or {@link
     *   MediaCodecInfo.CodecProfileLevel#HEVCProfileMain10HDR10Plus}), or
     *<p>
     * - The formats of output buffers of an encoder configured for an HDR10+ profiles that
     *   uses out-of-band metadata (such as {@link
     *   MediaCodecInfo.CodecProfileLevel#VP9Profile2HDR10Plus} or {@link
     *   MediaCodecInfo.CodecProfileLevel#VP9Profile3HDR10Plus}).
     *
     * @see MediaCodec#PARAMETER_KEY_HDR10_PLUS_INFO
     */
    public static final String KEY_HDR10_PLUS_INFO = "hdr10-plus-info";

    /**
     * An optional key describing the opto-electronic transfer function
     * requested for the output video content.
     *
     * The associated value is an integer: 0 if unspecified, or one of the
     * COLOR_TRANSFER_ values. When unspecified the component will not touch the
     * video content; otherwise the component will tone-map the raw video frame
     * to match the requested transfer function.
     *
     * After configure, component's input format will contain this key to note
     * whether the request is supported or not. If the value in the input format
     * is the same as the requested value, the request is supported. The value
     * is set to 0 if unsupported.
     */
    public static final String KEY_COLOR_TRANSFER_REQUEST = "color-transfer-request";

    /**
     * A key describing a unique ID for the content of a media track.
     *
     * <p>This key is used by {@link MediaExtractor}. Some extractors provide multiple encodings
     * of the same track (e.g. float audio tracks for FLAC and WAV may be expressed as two
     * tracks via MediaExtractor: a normal PCM track for backward compatibility, and a float PCM
     * track for added fidelity. Similarly, Dolby Vision extractor may provide a baseline SDR
     * version of a DV track.) This key can be used to identify which MediaExtractor tracks refer
     * to the same underlying content.
     * </p>
     *
     * The associated value is an integer.
     */
    public static final String KEY_TRACK_ID = "track-id";

    /**
     * A key describing the system id of the conditional access system used to scramble
     * a media track.
     * <p>
     * This key is set by {@link MediaExtractor} if the track is scrambled with a conditional
     * access system, regardless of the presence of a valid {@link MediaCas} object.
     * <p>
     * The associated value is an integer.
     * @hide
     */
    public static final String KEY_CA_SYSTEM_ID = "ca-system-id";

    /**
     * A key describing the {@link MediaCas.Session} object associated with a media track.
     * <p>
     * This key is set by {@link MediaExtractor} if the track is scrambled with a conditional
     * access system, after it receives a valid {@link MediaCas} object.
     * <p>
     * The associated value is a ByteBuffer.
     * @hide
     */
    public static final String KEY_CA_SESSION_ID = "ca-session-id";

    /**
     * A key describing the private data in the CA_descriptor associated with a media track.
     * <p>
     * This key is set by {@link MediaExtractor} if the track is scrambled with a conditional
     * access system, before it receives a valid {@link MediaCas} object.
     * <p>
     * The associated value is a ByteBuffer.
     * @hide
     */
    public static final String KEY_CA_PRIVATE_DATA = "ca-private-data";

    /**
     * A key describing the maximum number of B frames between I or P frames,
     * to be used by a video encoder.
     * The associated value is an integer. The default value is 0, which means
     * that no B frames are allowed. Note that non-zero value does not guarantee
     * B frames; it's up to the encoder to decide.
     */
    public static final String KEY_MAX_B_FRAMES = "max-bframes";

    /**
     * A key for applications to opt out of allowing
     * a Surface to discard undisplayed/unconsumed frames
     * as means to catch up after falling behind.
     * This value is an integer.
     * The value 0 indicates the surface is not allowed to drop frames.
     * The value 1 indicates the surface is allowed to drop frames.
     *
     * {@link MediaCodec} describes the semantics.
     */
    public static final String KEY_ALLOW_FRAME_DROP = "allow-frame-drop";

    /* package private */ MediaFormat(@NonNull Map<String, Object> map) {
        mMap = map;
    }

    /**
     * Creates an empty MediaFormat
     */
    public MediaFormat() {
        mMap = new HashMap();
    }

    @UnsupportedAppUsage
    /* package private */ Map<String, Object> getMap() {
        return mMap;
    }

    /**
     * Returns true iff a key of the given name exists in the format.
     */
    public final boolean containsKey(@NonNull String name) {
        return mMap.containsKey(name);
    }

    /**
     * Returns true iff a feature of the given name exists in the format.
     */
    public final boolean containsFeature(@NonNull String name) {
        return mMap.containsKey(KEY_FEATURE_ + name);
    }

    public static final int TYPE_NULL = 0;
    public static final int TYPE_INTEGER = 1;
    public static final int TYPE_LONG = 2;
    public static final int TYPE_FLOAT = 3;
    public static final int TYPE_STRING = 4;
    public static final int TYPE_BYTE_BUFFER = 5;

    /** @hide */
    @IntDef({
        TYPE_NULL,
        TYPE_INTEGER,
        TYPE_LONG,
        TYPE_FLOAT,
        TYPE_STRING,
        TYPE_BYTE_BUFFER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * Returns the value type for a key. If the key does not exist, it returns TYPE_NULL.
     */
    public final @Type int getValueTypeForKey(@NonNull String name) {
        Object value = mMap.get(name);
        if (value == null) {
            return TYPE_NULL;
        } else if (value instanceof Integer) {
            return TYPE_INTEGER;
        } else if (value instanceof Long) {
            return TYPE_LONG;
        } else if (value instanceof Float) {
            return TYPE_FLOAT;
        } else if (value instanceof String) {
            return TYPE_STRING;
        } else if (value instanceof ByteBuffer) {
            return TYPE_BYTE_BUFFER;
        }
        throw new RuntimeException("invalid value for key");
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
     * Returns the value of a numeric key. This is provided as a convenience method for keys
     * that may take multiple numeric types, such as {@link #KEY_FRAME_RATE}, or {@link
     * #KEY_I_FRAME_INTERVAL}.
     *
     * @return null if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is ByteBuffer or String
     */
    public final @Nullable Number getNumber(@NonNull String name) {
        return (Number) mMap.get(name);
    }

    /**
     * Returns the value of a numeric key, or the default value if the key is missing.
     *
     * @return defaultValue if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is ByteBuffer or String
     */
    public final @NonNull Number getNumber(@NonNull String name, @NonNull Number defaultValue) {
        Number ret = getNumber(name);
        return ret == null ? defaultValue : ret;
    }

    /**
     * Returns the value of an integer key.
     *
     * @throws NullPointerException if the key does not exist or the stored value for the key is
     *         null
     * @throws ClassCastException if the stored value for the key is long, float, ByteBuffer or
     *         String
     */
    public final int getInteger(@NonNull String name) {
        return (int) mMap.get(name);
    }

    /**
     * Returns the value of an integer key, or the default value if the key is missing.
     *
     * @return defaultValue if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is long, float, ByteBuffer or
     *         String
     */
    public final int getInteger(@NonNull String name, int defaultValue) {
        try {
            return getInteger(name);
        } catch (NullPointerException  e) {
            /* no such field or field is null */
            return defaultValue;
        }
    }

    /**
     * Returns the value of a long key.
     *
     * @throws NullPointerException if the key does not exist or the stored value for the key is
     *         null
     * @throws ClassCastException if the stored value for the key is int, float, ByteBuffer or
     *         String
     */
    public final long getLong(@NonNull String name) {
        return (long) mMap.get(name);
    }

    /**
     * Returns the value of a long key, or the default value if the key is missing.
     *
     * @return defaultValue if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is int, float, ByteBuffer or
     *         String
     */
    public final long getLong(@NonNull String name, long defaultValue) {
        try {
            return getLong(name);
        } catch (NullPointerException  e) {
            /* no such field or field is null */
            return defaultValue;
        }
    }

    /**
     * Returns the value of a float key.
     *
     * @throws NullPointerException if the key does not exist or the stored value for the key is
     *         null
     * @throws ClassCastException if the stored value for the key is int, long, ByteBuffer or
     *         String
     */
    public final float getFloat(@NonNull String name) {
        return (float) mMap.get(name);
    }

    /**
     * Returns the value of a float key, or the default value if the key is missing.
     *
     * @return defaultValue if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is int, long, ByteBuffer or
     *         String
     */
    public final float getFloat(@NonNull String name, float defaultValue) {
        Object value = mMap.get(name);
        return value != null ? (float) value : defaultValue;
    }

    /**
     * Returns the value of a string key.
     *
     * @return null if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is int, long, float or ByteBuffer
     */
    public final @Nullable String getString(@NonNull String name) {
        return (String)mMap.get(name);
    }

    /**
     * Returns the value of a string key, or the default value if the key is missing.
     *
     * @return defaultValue if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is int, long, float or ByteBuffer
     */
    public final @NonNull String getString(@NonNull String name, @NonNull String defaultValue) {
        String ret = getString(name);
        return ret == null ? defaultValue : ret;
    }

    /**
     * Returns the value of a ByteBuffer key.
     *
     * @return null if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is int, long, float or String
     */
    public final @Nullable ByteBuffer getByteBuffer(@NonNull String name) {
        return (ByteBuffer)mMap.get(name);
    }

    /**
     * Returns the value of a ByteBuffer key, or the default value if the key is missing.
     *
     * @return defaultValue if the key does not exist or the stored value for the key is null
     * @throws ClassCastException if the stored value for the key is int, long, float or String
     */
    public final @NonNull ByteBuffer getByteBuffer(
            @NonNull String name, @NonNull ByteBuffer defaultValue) {
        ByteBuffer ret = getByteBuffer(name);
        return ret == null ? defaultValue : ret;
    }

    /**
     * Returns whether a feature is to be enabled ({@code true}) or disabled
     * ({@code false}).
     *
     * @param feature the name of a {@link MediaCodecInfo.CodecCapabilities} feature.
     *
     * @throws IllegalArgumentException if the feature was neither set to be enabled
     *         nor to be disabled.
     */
    public boolean getFeatureEnabled(@NonNull String feature) {
        Integer enabled = (Integer)mMap.get(KEY_FEATURE_ + feature);
        if (enabled == null) {
            throw new IllegalArgumentException("feature is not specified");
        }
        return enabled != 0;
    }

    /**
     * Sets the value of an integer key.
     */
    public final void setInteger(@NonNull String name, int value) {
        mMap.put(name, value);
    }

    /**
     * Sets the value of a long key.
     */
    public final void setLong(@NonNull String name, long value) {
        mMap.put(name, value);
    }

    /**
     * Sets the value of a float key.
     */
    public final void setFloat(@NonNull String name, float value) {
        mMap.put(name, value);
    }

    /**
     * Sets the value of a string key.
     * <p>
     * If value is {@code null}, it sets a null value that behaves similarly to a missing key.
     * This could be used prior to API level {@link android os.Build.VERSION_CODES#Q} to effectively
     * remove a key.
     */
    public final void setString(@NonNull String name, @Nullable String value) {
        mMap.put(name, value);
    }

    /**
     * Sets the value of a ByteBuffer key.
     * <p>
     * If value is {@code null}, it sets a null value that behaves similarly to a missing key.
     * This could be used prior to API level {@link android os.Build.VERSION_CODES#Q} to effectively
     * remove a key.
     */
    public final void setByteBuffer(@NonNull String name, @Nullable ByteBuffer bytes) {
        mMap.put(name, bytes);
    }

    /**
     * Removes a value of a given key if present. Has no effect if the key is not present.
     */
    public final void removeKey(@NonNull String name) {
        // exclude feature mappings
        if (!name.startsWith(KEY_FEATURE_)) {
            mMap.remove(name);
        }
    }

    /**
     * Removes a given feature setting if present. Has no effect if the feature setting is not
     * present.
     */
    public final void removeFeature(@NonNull String name) {
        mMap.remove(KEY_FEATURE_ + name);
    }

    /**
     * A Partial set view for a portion of the keys in a MediaFormat object.
     *
     * This class is needed as we want to return a portion of the actual format keys in getKeys()
     * and another portion of the keys in getFeatures(), and still allow the view properties.
     */
    private abstract class FilteredMappedKeySet extends AbstractSet<String> {
        private Set<String> mKeys;

        // Returns true if this set should include this key
        abstract protected boolean keepKey(String key);

        // Maps a key from the underlying key set into its new value in this key set
        abstract protected String mapKeyToItem(String key);

        // Maps a key from this key set into its original value in the underlying key set
        abstract protected String mapItemToKey(String item);

        public FilteredMappedKeySet() {
            mKeys = mMap.keySet();
        }

        // speed up contains and remove from abstract implementation (that would iterate
        // over each element)
        @Override
        public boolean contains(Object o) {
            if (o instanceof String) {
                String key = mapItemToKey((String)o);
                return keepKey(key) && mKeys.contains(key);
            }
            return false;
        }

        @Override
        public boolean remove(Object o) {
            if (o instanceof String) {
                String key = mapItemToKey((String)o);
                if (keepKey(key) && mKeys.remove(key)) {
                    mMap.remove(key);
                    return true;
                }
            }
            return false;
        }

        private class KeyIterator implements Iterator<String> {
            Iterator<String> mIterator;
            String mLast;

            public KeyIterator() {
                // We must create a copy of the filtered stream, as remove operation has to modify
                // the underlying data structure (mMap), so the iterator's operation is undefined.
                // Use a list as it is likely less memory consuming than the other alternative: set.
                mIterator =
                    mKeys.stream().filter(k -> keepKey(k)).collect(Collectors.toList()).iterator();
            }

            @Override
            public boolean hasNext() {
                return mIterator.hasNext();
            }

            @Override
            public String next() {
                mLast = mIterator.next();
                return mapKeyToItem(mLast);
            }

            @Override
            public void remove() {
                mIterator.remove();
                mMap.remove(mLast);
            }
        }

        @Override
        public Iterator<String> iterator() {
            return new KeyIterator();
        }

        @Override
        public int size() {
            return (int) mKeys.stream().filter(this::keepKey).count();
        }
    }

    /**
     * A Partial set view for a portion of the keys in a MediaFormat object for keys that
     * don't start with a prefix, such as "feature-"
     */
    private class UnprefixedKeySet extends FilteredMappedKeySet {
        private String mPrefix;

        public UnprefixedKeySet(String prefix) {
            super();
            mPrefix = prefix;
        }

        protected boolean keepKey(String key) {
            return !key.startsWith(mPrefix);
        }

        protected String mapKeyToItem(String key) {
            return key;
        }

        protected String mapItemToKey(String item) {
            return item;
        }
    }

    /**
     * A Partial set view for a portion of the keys in a MediaFormat object for keys that
     * start with a prefix, such as "feature-", with the prefix removed
     */
    private class PrefixedKeySetWithPrefixRemoved extends FilteredMappedKeySet {
        private String mPrefix;
        private int mPrefixLength;

        public PrefixedKeySetWithPrefixRemoved(String prefix) {
            super();
            mPrefix = prefix;
            mPrefixLength = prefix.length();
        }

        protected boolean keepKey(String key) {
            return key.startsWith(mPrefix);
        }

        protected String mapKeyToItem(String key) {
            return key.substring(mPrefixLength);
        }

        protected String mapItemToKey(String item) {
            return mPrefix + item;
        }
    }


   /**
     * Returns a {@link java.util.Set Set} view of the keys contained in this MediaFormat.
     *
     * The set is backed by the MediaFormat object, so changes to the format are reflected in the
     * set, and vice-versa. If the format is modified while an iteration over the set is in progress
     * (except through the iterator's own remove operation), the results of the iteration are
     * undefined. The set supports element removal, which removes the corresponding mapping from the
     * format, via the Iterator.remove, Set.remove, removeAll, retainAll, and clear operations.
     * It does not support the add or addAll operations.
     */
    public final @NonNull java.util.Set<String> getKeys() {
        return new UnprefixedKeySet(KEY_FEATURE_);
    }

   /**
     * Returns a {@link java.util.Set Set} view of the features contained in this MediaFormat.
     *
     * The set is backed by the MediaFormat object, so changes to the format are reflected in the
     * set, and vice-versa. If the format is modified while an iteration over the set is in progress
     * (except through the iterator's own remove operation), the results of the iteration are
     * undefined. The set supports element removal, which removes the corresponding mapping from the
     * format, via the Iterator.remove, Set.remove, removeAll, retainAll, and clear operations.
     * It does not support the add or addAll operations.
     */
    public final @NonNull java.util.Set<String> getFeatures() {
        return new PrefixedKeySetWithPrefixRemoved(KEY_FEATURE_);
    }

    /**
     * Create a copy of a media format object.
     */
    public MediaFormat(@NonNull MediaFormat other) {
        this();
        mMap.putAll(other.mMap);
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
    public void setFeatureEnabled(@NonNull String feature, boolean enabled) {
        setInteger(KEY_FEATURE_ + feature, enabled ? 1 : 0);
    }

    /**
     * Creates a minimal audio format.
     * @param mime The mime type of the content.
     * @param sampleRate The sampling rate of the content.
     * @param channelCount The number of audio channels in the content.
     */
    public static final @NonNull MediaFormat createAudioFormat(
            @NonNull String mime,
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
    public static final @NonNull MediaFormat createSubtitleFormat(
            @NonNull String mime,
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
    public static final @NonNull MediaFormat createVideoFormat(
            @NonNull String mime,
            int width,
            int height) {
        MediaFormat format = new MediaFormat();
        format.setString(KEY_MIME, mime);
        format.setInteger(KEY_WIDTH, width);
        format.setInteger(KEY_HEIGHT, height);

        return format;
    }

    @Override
    public @NonNull String toString() {
        return mMap.toString();
    }
}
