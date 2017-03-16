/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Runnable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;


/**
 * MediaMetricsSet contains the results returned by the getMetrics()
 * methods defined in other Media classes such as
 * {@link MediaCodec}, {@link MediaExtractor}, {@link MediaPlayer},
 * and {@link MediaRecorder}.
 *
 * MediaMetricsSet behaves similarly to a {@link Bundle}. It contains
 * a set of keys and values.
 * Methods such as {@link #getInt} and {@link #getString} are provided
 * to extract values of the corresponding types.
 * The {@link #keySet} method can be used to discover all of the keys
 * that are present in the particular instance.
 *
 */
public final class MediaMetricsSet
{

    /**
     * This MediaCodec class holds the constants defining keys related to
     * the metrics for a MediaCodec.
     */
    public final static class MediaCodec
    {
        private MediaCodec() {}

        /**
         * Key to extract the codec being used
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is a String.
         */
        public static final String KEY_CODEC = "android.media.mediacodec.codec";

        /**
         * Key to extract the MIME type
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is a String.
         */
        public static final String KEY_MIME = "android.media.mediacodec.mime";

        /**
         * Key to extract what the codec mode
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is a String. Values will be one of the constants
	 * MODE_AUDIO or MODE_VIDEO.
         */
        public static final String KEY_MODE = "android.media.mediacodec.mode";

	/**
	 * The value returned for the key {@link #KEY_MODE} when the
	 * codec is a audio codec.
	 */
        public static final String MODE_AUDIO = "audio";

	/**
	 * The value returned for the key {@link #KEY_MODE} when the
	 * codec is a video codec.
	 */
        public static final String MODE_VIDEO = "video";

        /**
         * Key to extract the flag indicating whether the codec is running
         * as an encoder or decoder from the {@link MediaCodec#getMetrics} return value.
         * The value is an integer.
         * A 0 indicates decoder; 1 indicates encoder.
         */
        public static final String KEY_ENCODER = "android.media.mediacodec.encoder";

        /**
         * Key to extract the flag indicating whether the codec is running
         * in secure (DRM) mode from the {@link MediaCodec#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_SECURE = "android.media.mediacodec.secure";

        /**
         * Key to extract the width (in pixels) of the video track
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_WIDTH = "android.media.mediacodec.width";

        /**
         * Key to extract the height (in pixels) of the video track
         * from the {@link MediaCodec#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_HEIGHT = "android.media.mediacodec.height";

        /**
         * Key to extract the rotation (in degrees) to properly orient the video
         * from the {@link MediaCodec#getMetrics} return.
         * The value is a integer.
         */
        public static final String KEY_ROTATION = "android.media.mediacodec.rotation";

    }

    /**
     * This class holds the constants defining keys related to
     * the metrics for a MediaExtractor.
     */
    public final static class MediaExtractor
    {
        private MediaExtractor() {}

        /**
         * Key to extract the container format
         * from the {@link MediaExtractor#getMetrics} return value.
         * The value is a String.
         */
        public static final String KEY_FORMAT = "android.media.mediaextractor.fmt";

        /**
         * Key to extract the container MIME type
         * from the {@link MediaExtractor#getMetrics} return value.
         * The value is a String.
         */
        public static final String KEY_MIME = "android.media.mediaextractor.mime";

        /**
         * Key to extract the number of tracks in the container
         * from the {@link MediaExtractor#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_TRACKS = "android.media.mediaextractor.ntrk";

    }

    /**
     * This class holds the constants defining keys related to
     * the metrics for a MediaPlayer.
     */
    public final static class MediaPlayer
    {
        private MediaPlayer() {}

        /**
         * Key to extract the MIME type of the video track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a String.
         */
        public static final String KEY_MIME_VIDEO = "android.media.mediaplayer.video.mime";

        /**
         * Key to extract the codec being used to decode the video track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a String.
         */
        public static final String KEY_CODEC_VIDEO = "android.media.mediaplayer.video.codec";

        /**
         * Key to extract the width (in pixels) of the video track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_WIDTH = "android.media.mediaplayer.width";

        /**
         * Key to extract the height (in pixels) of the video track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_HEIGHT = "android.media.mediaplayer.height";

        /**
         * Key to extract the count of video frames played
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_FRAMES = "android.media.mediaplayer.frames";

        /**
         * Key to extract the count of video frames dropped
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_FRAMES_DROPPED = "android.media.mediaplayer.dropped";

        /**
         * Key to extract the MIME type of the audio track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a String.
         */
        public static final String KEY_MIME_AUDIO = "android.media.mediaplayer.audio.mime";

        /**
         * Key to extract the codec being used to decode the audio track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a String.
         */
        public static final String KEY_CODEC_AUDIO = "android.media.mediaplayer.audio.codec";

        /**
         * Key to extract the duration (in milliseconds) of the
         * media being played
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a long.
         */
        public static final String KEY_DURATION = "android.media.mediaplayer.durationMs";

        /**
         * Key to extract the playing time (in milliseconds) of the
         * media being played
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a long.
         */
        public static final String KEY_PLAYING = "android.media.mediaplayer.playingMs";

        /**
         * Key to extract the count of errors encountered while
         * playing the media
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_ERRORS = "android.media.mediaplayer.err";

        /**
         * Key to extract an (optional) error code detected while
         * playing the media
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String KEY_ERROR_CODE = "android.media.mediaplayer.errcode";

    }

    /**
     * This class holds the constants defining keys related to
     * the metrics for a MediaRecorder.
     */
    public final static class MediaRecorder
    {
        private MediaRecorder() {}

        /**
         * Key to extract the audio bitrate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_AUDIO_BITRATE = "android.media.mediarecorder.audio-bitrate";

        /**
         * Key to extract the number of audio channels
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_AUDIO_CHANNELS = "android.media.mediarecorder.audio-channels";

        /**
         * Key to extract the audio samplerate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_AUDIO_SAMPLERATE = "android.media.mediarecorder.audio-samplerate";

        /**
         * Key to extract the audio timescale
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_AUDIO_TIMESCALE = "android.media.mediarecorder.audio-timescale";

        /**
         * Key to extract the video capture frame rate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is a double.
         */
        public static final String KEY_CAPTURE_FPS = "android.media.mediarecorder.capture-fps";

        /**
         * Key to extract the video capture framerate enable value
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_CAPTURE_FPS_ENABLE = "android.media.mediarecorder.capture-fpsenable";

        /**
         * Key to extract the intended playback frame rate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_FRAMERATE = "android.media.mediarecorder.frame-rate";

        /**
         * Key to extract the height (in pixels) of the captured video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_HEIGHT = "android.media.mediarecorder.height";

        /**
         * Key to extract the recorded movies time units
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         * A value of 1000 indicates that the movie's timing is in milliseconds.
         */
        public static final String KEY_MOVIE_TIMESCALE = "android.media.mediarecorder.movie-timescale";

        /**
         * Key to extract the rotation (in degrees) to properly orient the video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_ROTATION = "android.media.mediarecorder.rotation";

        /**
         * Key to extract the video bitrate from being used
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_VIDEO_BITRATE = "android.media.mediarecorder.video-bitrate";

        /**
         * Key to extract the value for how often video iframes are generated
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_VIDEO_IFRAME_INTERVAL = "android.media.mediarecorder.video-iframe-interval";

        /**
         * Key to extract the video encoding level
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_VIDEO_LEVEL = "android.media.mediarecorder.video-encoder-level";

        /**
         * Key to extract the video encoding profile
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_VIDEO_PROFILE = "android.media.mediarecorder.video-encoder-profile";

        /**
         * Key to extract the recorded video time units
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         * A value of 1000 indicates that the video's timing is in milliseconds.
         */
        public static final String KEY_VIDEO_TIMESCALE = "android.media.mediarecorder.video-timescale";

        /**
         * Key to extract the width (in pixels) of the captured video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String KEY_WIDTH = "android.media.mediarecorder.width";

    }

    /*
     * Methods that we want
     */

    private Bundle mBundle;

    MediaMetricsSet(Bundle bundle) {
        mBundle = bundle;
    }

    /**
     * Returns the number of mappings contained in this Bundle.
     *
     * @return the number of mappings as an int.
     */
    public int size() {
        return mBundle.size();
    }

    /**
     * Returns true if the mapping of this MediaMetricsSet is empty,
     * false otherwise.
     */
    public boolean isEmpty() {
        return mBundle.isEmpty();
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return a double value
     */
    public double getDouble(String key, double defaultValue) {
        return mBundle.getDouble(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return an int value
     */
    public int getInt(String key, int defaultValue) {
        return mBundle.getInt(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return a long value
     */
    public long getLong(String key, long defaultValue) {
        return mBundle.getLong(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key or if a null
     * value is explicitly associated with the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist or if a null
     *     value is associated with the given key.
     * @return the String value associated with the given key, or defaultValue
     *     if no valid String object is currently mapped to that key.
     */
    public String getString(String key, String defaultValue) {
        return mBundle.getString(key, defaultValue);
    }

    /**
     * Returns a Set containing the Strings used as keys in this Bundle.
     *
     * @return a Set of String keys
     */
    public Set<String> keySet() {
        return mBundle.keySet();
    }



    public String toString() {
        return mBundle.toString();
    }

}

