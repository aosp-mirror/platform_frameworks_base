/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.SystemApi;

/**
 * Meta data from AD (Audio Descriptor) according to ETSI TS 101 154 V2.1.1.
 *
 * @hide
 */
@SystemApi
public class AudioDescriptor {
    private final byte mAdFade;
    private final byte mAdPan;
    private final char mVersionTextTag;
    private final byte mAdGainCenter;
    private final byte mAdGainFront;
    private final byte mAdGainSurround;

    // This constructor is used by JNI code only
    private AudioDescriptor(byte adFade, byte adPan, char versionTextTag, byte adGainCenter,
            byte adGainFront, byte adGainSurround) {
        mAdFade = adFade;
        mAdPan = adPan;
        mVersionTextTag = versionTextTag;
        mAdGainCenter = adGainCenter;
        mAdGainFront = adGainFront;
        mAdGainSurround = adGainSurround;
    }

    /**
     * Gets AD fade byte.
     *
     * <p>Takes values between 0x00 (representing no fade of the main programme sound) and 0xFF
     * (representing a full fade). Over the range 0x00 to 0xFE one lsb represents a step in
     * attenuation of the programme sound of 0.3 dB giving a range of 76.2 dB. The fade value of
     * 0xFF represents no programme sound at all (i.e. mute).
     */
    public byte getAdFade() {
        return mAdFade;
    }

    /**
     * Gets AD pan byte.
     *
     * <p>Takes values between 0x00 representing a central forward presentation of the audio
     * description and 0xFF, each increment representing a 360/256 degree step clockwise looking
     * down on the listener (i.e. just over 1.4 degrees).
     */
    public byte getAdPan() {
        return mAdPan;
    }

    /**
     * Gets AD version tag. A single ASCII character version indicates the version.
     *
     * <p>A single ASCII character version designator (here "1" indicates revision 1).
     */
    public char getAdVersionTextTag() {
        return mVersionTextTag;
    }

    /**
     * Gets AD gain byte center in dB.
     *
     * <p>Represents a signed value in dB. Takes values between 0x7F (representing +76.2 dB boost of
     * the main programme center) and 0x80 (representing a full fade). Over the range 0x00 to 0x7F
     * one lsb represents a step in boost of the programme center of 0.6 dB giving a maximum boost
     * of +76.2 dB. Over the range 0x81 to 0x00 one lsb represents a step in attenuation of the
     * programme center of 0.6 dB giving a maximum attenuation of -76.2 dB. The gain value of 0x80
     * represents no main center level at all (i.e. mute).
     */
    public byte getAdGainCenter() {
        return mAdGainCenter;
    }

    /**
     * Gets AD gain byte front in dB.
     *
     * <p>Same as {@link #getAdGainCenter()}, but applied to left and right front channel.
     */
    public byte getAdGainFront() {
        return mAdGainFront;
    }

    /**
     * Gets AD gain byte surround in dB.
     *
     * <p>Same as {@link #getAdGainCenter()}, but applied to all surround channels
     */
    public byte getAdGainSurround() {
        return mAdGainSurround;
    }
}
