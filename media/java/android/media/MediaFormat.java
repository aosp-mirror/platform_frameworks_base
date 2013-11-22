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
     * A key describing the frequency of I frames expressed in secs
     * between I frames.
     * The associated value is an integer.
     */
    public static final String KEY_I_FRAME_INTERVAL = "i-frame-interval";

    /**
     * @hide
     */
    public static final String KEY_STRIDE = "stride";
    /**
     * @hide
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
     * A key describing the FLAC compression level to be used (FLAC audio format only).
     * The associated value is an integer ranging from 0 (fastest, least compression)
     * to 8 (slowest, most compression).
     */
    public static final String KEY_FLAC_COMPRESSION_LEVEL = "flac-compression-level";

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
     * Sets the value of an integer key.
     */
    public final void setInteger(String name, int value) {
        mMap.put(name, new Integer(value));
    }

    /**
     * Sets the value of a long key.
     */
    public final void setLong(String name, long value) {
        mMap.put(name, new Long(value));
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
