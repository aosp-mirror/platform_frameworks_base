/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;

/**
 * An AudioProfile is specific to an audio format and lists supported sampling rates and
 * channel masks for that format.  An {@link AudioDeviceInfo} has a list of supported AudioProfiles.
 */
public class AudioProfile {
    private final int mFormat;
    private final int[] mSamplingRates;
    private final int[] mChannelMasks;
    private final int[] mChannelIndexMasks;

    AudioProfile(int format, @NonNull int[] samplingRates, @NonNull int[] channelMasks,
                 @NonNull int[] channelIndexMasks) {
        mFormat = format;
        mSamplingRates = samplingRates;
        mChannelMasks = channelMasks;
        mChannelIndexMasks = channelIndexMasks;
    }

    /**
     * @return the encoding format for this AudioProfile.
     */
    public @AudioFormat.Encoding int getFormat() {
        return mFormat;
    }

    /**
     * @return an array of channel position masks that are associated with the encoding format.
     */
    public @NonNull int[] getChannelMasks() {
        return mChannelMasks;
    }

    /**
     * @return an array of channel index masks that are associated with the encoding format.
     */
    public @NonNull int[] getChannelIndexMasks() {
        return mChannelIndexMasks;
    }

    /**
     * @return an array of sample rates that are associated with the encoding format.
     */
    public @NonNull int[] getSampleRates() {
        return mSamplingRates;
    }
}
