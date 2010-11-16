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

package android.media.videoeditor;

import java.io.IOException;

/**
 * Class which describes the waveform data of an audio track. The gain values
 * represent the average gain for an audio frame. For audio codecs which do
 * not operate on a per frame bases (eg. ALAW, ULAW) a reasonable audio frame
 * duration will be assumed (eg. 50ms).
 * {@hide}
 */
public class WaveformData {
    // Instance variables
    private final int mFrameDurationMs;
    private final int mFramesCount;
    private final short[] mGains;

    /**
     * This constructor shall not be used
     */
    @SuppressWarnings("unused")
    private WaveformData() throws IOException {
        mFrameDurationMs = 0;
        mFramesCount = 0;
        mGains = null;
    }

    /**
     * Constructor
     *
     * @param audioWaveformFilename The name of the audio waveform file
     */
    WaveformData(String audioWaveformFilename) {
        // TODO: Read these values from the file
        mFrameDurationMs = 20;
        mFramesCount = 300000 / mFrameDurationMs;
        mGains = new short[mFramesCount];
        for (int i = 0; i < mFramesCount; i++) {
            mGains[i] = (short)((i * 5) % 256);
        }
    }

    /**
     * @return The duration of a frame in milliseconds
     */
    public int getFrameDuration() {
        return mFrameDurationMs;
    }

    /**
     * @return The number of frames within the waveform data
     */
    public int getFramesCount() {
        return mFramesCount;
    }

    /**
     * @return The array of frame gains. The size of the array is the frames
     *  count. The values of the frame gains range from 0 to 256.
     */
    public short[] getFrameGains() {
        return mGains;
    }
}
