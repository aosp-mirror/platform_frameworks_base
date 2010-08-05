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

package android.net.rtp;

/** @hide */
public class AudioCodec {
    public static final AudioCodec ULAW = new AudioCodec("PCMU", 8000, 160, 0);
    public static final AudioCodec ALAW = new AudioCodec("PCMA", 8000, 160, 8);

    /**
     * Returns system supported codecs.
     */
    public static AudioCodec[] getSystemSupportedCodecs() {
        return new AudioCodec[] {AudioCodec.ULAW, AudioCodec.ALAW};
    }

    /**
     * Returns the codec instance if it is supported by the system.
     *
     * @param name name of the codec
     * @return the matched codec or null if the codec name is not supported by
     *      the system
     */
    public static AudioCodec getSystemSupportedCodec(String name) {
        for (AudioCodec codec : getSystemSupportedCodecs()) {
            if (codec.name.equals(name)) return codec;
        }
        return null;
    }

    public final String name;
    public final int sampleRate;
    public final int sampleCount;
    public final int defaultType;

    private AudioCodec(String name, int sampleRate, int sampleCount, int defaultType) {
        this.name = name;
        this.sampleRate = sampleRate;
        this.sampleCount = sampleCount;
        this.defaultType = defaultType;
    }
}
