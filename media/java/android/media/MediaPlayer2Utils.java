/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * Helper class used by native code to reduce JNI calls from native side.
 * @hide
 */
public class MediaPlayer2Utils {
    /**
     * Returns whether audio offloading is supported for the given audio format.
     *
     * @param encoding the type of encoding defined in {@link AudioFormat}
     * @param sampleRate the sampling rate of the stream
     * @param channelMask the channel mask defined in {@link AudioFormat}
     */
    // @CalledByNative
    public static boolean isOffloadedAudioPlaybackSupported(
            int encoding, int sampleRate, int channelMask) {
        final AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();
        return AudioManager.isOffloadedPlaybackSupported(format);
    }
}
