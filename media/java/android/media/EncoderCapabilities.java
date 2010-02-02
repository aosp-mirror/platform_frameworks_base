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

package android.media;

import java.util.List;
import java.util.ArrayList;
import android.util.Log;

/**
 * The EncoderCapabilities class is used to retrieve the
 * capabilities for different video and audio
 * encoders supported on a specific Android platform.
 * {@hide}
 */
public class EncoderCapabilities
{
    private static final String TAG = "EncoderCapabilities";

    /**
     * The VideoEncoderCap class represents a video encoder's
     * supported parameter range in:
     *
     * <ul>
     * <li>Resolution: the frame size (width/height) in pixels;
     * <li>Bit rate: the compressed output bit rate in bits per second;
     * <li>Frame rate: the output number of frames per second.
     * </ul>
     *
     */
    static public class VideoEncoderCap {
        // These are not modifiable externally, thus are public accessible
        public final int mCodec;                                 // @see android.media.MediaRecorder.VideoEncoder
        public final int mMinBitRate, mMaxBitRate;               // min and max bit rate (bps)
        public final int mMinFrameRate, mMaxFrameRate;           // min and max frame rate (fps)
        public final int mMinFrameWidth, mMaxFrameWidth;         // min and max frame width (pixel)
        public final int mMinFrameHeight, mMaxFrameHeight;       // minn and max frame height (pixel)

        // Private constructor called by JNI
        private VideoEncoderCap(int codec,
                                int minBitRate, int maxBitRate,
                                int minFrameRate, int maxFrameRate,
                                int minFrameWidth, int maxFrameWidth,
                                int minFrameHeight, int maxFrameHeight) {
            mCodec = codec;
            mMinBitRate = minBitRate;
            mMaxBitRate = maxBitRate;
            mMinFrameRate = minFrameRate;
            mMaxFrameRate = maxFrameRate;
            mMinFrameWidth = minFrameWidth;
            mMaxFrameWidth = maxFrameWidth;
            mMinFrameHeight = minFrameHeight;
            mMaxFrameHeight = maxFrameHeight;
        }
    };

    /**
     * The AudioEncoderCap class represents an audio encoder's
     * parameter range in:
     *
     * <ul>
     * <li>Bit rate: the compressed output bit rate in bits per second;
     * <li>Sample rate: the sampling rate used for recording the audio in samples per second;
     * <li>Number of channels: the number of channels the audio is recorded.
     * </ul>
     *
     */
    static public class AudioEncoderCap {
        // These are not modifiable externally, thus are public accessible
        public final int mCodec;                         // @see android.media.MediaRecorder.AudioEncoder
        public final int mMinChannels, mMaxChannels;     // min and max number of channels
        public final int mMinSampleRate, mMaxSampleRate; // min and max sample rate (hz)
        public final int mMinBitRate, mMaxBitRate;       // min and max bit rate (bps)

        // Private constructor called by JNI
        private AudioEncoderCap(int codec,
                                int minBitRate, int maxBitRate,
                                int minSampleRate, int maxSampleRate,
                                int minChannels, int maxChannels) {
           mCodec = codec;
           mMinBitRate = minBitRate;
           mMaxBitRate = maxBitRate;
           mMinSampleRate = minSampleRate;
           mMaxSampleRate = maxSampleRate;
           mMinChannels = minChannels;
           mMaxChannels = maxChannels;
       }
    };

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    /**
     * Returns the array of supported output file formats.
     * @see android.media.MediaRecorder.OutputFormat
     */
    public static int[] getOutputFileFormats() {
        int nFormats = native_get_num_file_formats();
        if (nFormats == 0) return null;

        int[] formats = new int[nFormats];
        for (int i = 0; i < nFormats; ++i) {
            formats[i] = native_get_file_format(i);
        }
        return formats;
    }

    /**
     * Returns the capabilities of the supported video encoders.
     * @see android.media.EncoderCapabilities.VideoEncoderCap
     */
    public static List<VideoEncoderCap> getVideoEncoders() {
        int nEncoders = native_get_num_video_encoders();
        if (nEncoders == 0) return null;

        List<VideoEncoderCap> encoderList = new ArrayList<VideoEncoderCap>();
        for (int i = 0; i < nEncoders; ++i) {
            encoderList.add(native_get_video_encoder_cap(i));
        }
        return encoderList;
    }

    /**
     * Returns the capabilities of the supported audio encoders.
     * @see android.media.EncoderCapabilities.AudioEncoderCap
     */
    public static List<AudioEncoderCap> getAudioEncoders() {
        int nEncoders = native_get_num_audio_encoders();
        if (nEncoders == 0) return null;

        List<AudioEncoderCap> encoderList = new ArrayList<AudioEncoderCap>();
        for (int i = 0; i < nEncoders; ++i) {
            encoderList.add(native_get_audio_encoder_cap(i));
        }
        return encoderList;
    }


    private EncoderCapabilities() {}  // Don't call me

    // Implemented by JNI
    private static native final void native_init();
    private static native final int native_get_num_file_formats();
    private static native final int native_get_file_format(int index);
    private static native final int native_get_num_video_encoders();
    private static native final VideoEncoderCap native_get_video_encoder_cap(int index);
    private static native final int native_get_num_audio_encoders();
    private static native final AudioEncoderCap native_get_audio_encoder_cap(int index);
}
