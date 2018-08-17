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

import android.annotation.UnsupportedAppUsage;
import java.util.List;
import java.util.ArrayList;

/**
 * {@hide}
 *
 * The DecoderCapabilities class is used to retrieve the types of the
 * video and audio decoder(s) supported on a specific Android platform.
 */
public class DecoderCapabilities
{
    /**
     * The VideoDecoder class represents the type of a video decoder
     *
     */
    public enum VideoDecoder {
        @UnsupportedAppUsage
        VIDEO_DECODER_WMV,
    };

    /**
     * The AudioDecoder class represents the type of an audio decoder
     */
    public enum AudioDecoder {
        @UnsupportedAppUsage
        AUDIO_DECODER_WMA,
    };

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    /**
     * Returns the list of video decoder types
     * @see android.media.DecoderCapabilities.VideoDecoder
     */
    @UnsupportedAppUsage
    public static List<VideoDecoder> getVideoDecoders() {
        List<VideoDecoder> decoderList = new ArrayList<VideoDecoder>();
        int nDecoders = native_get_num_video_decoders();
        for (int i = 0; i < nDecoders; ++i) {
            decoderList.add(VideoDecoder.values()[native_get_video_decoder_type(i)]);
        }
        return decoderList;
    }

    /**
     * Returns the list of audio decoder types
     * @see android.media.DecoderCapabilities.AudioDecoder
     */
    @UnsupportedAppUsage
    public static List<AudioDecoder> getAudioDecoders() {
        List<AudioDecoder> decoderList = new ArrayList<AudioDecoder>();
        int nDecoders = native_get_num_audio_decoders();
        for (int i = 0; i < nDecoders; ++i) {
            decoderList.add(AudioDecoder.values()[native_get_audio_decoder_type(i)]);
        }
        return decoderList;
    }

    private DecoderCapabilities() {}  // Don't call me

    // Implemented by JNI
    private static native final void native_init();
    private static native final int native_get_num_video_decoders();
    private static native final int native_get_video_decoder_type(int index);
    private static native final int native_get_num_audio_decoders();
    private static native final int native_get_audio_decoder_type(int index);
}
