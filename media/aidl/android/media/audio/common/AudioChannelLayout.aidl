/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.audio.common;

/**
 * This structure describes a layout of a multi-channel stream.
 * There are two possible ways for representing a layout:
 *
 * - indexed mask, which tells what channels of an audio frame are used, but
 *   doesn't label them in any way, thus a correspondence between channels in
 *   the same position of frames originating from different streams must be
 *   established externally;
 *
 * - layout mask, which gives a label to each channel, thus allowing to
 *   match channels between streams of different layouts.
 *
 * Both representations are agnostic of the direction of audio transfer. Also,
 * by construction, the number of bits set to '1' in the mask indicates the
 * number of channels in the audio frame. A channel mask per se only defines the
 * presence or absence of a channel, not the order. Please see 'INTERLEAVE_*'
 * constants for the platform convention of order.
 *
 * The structure also defines a "voice mask" which is a special case of
 * layout mask, intended for processing voice audio from telecommunication
 * use cases.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
union AudioChannelLayout {
    /**
     * This variant is used for representing the "null" ("none") value
     * for the channel layout. The field value must always be '0'.
     */
    int none = 0;
    /**
     * This variant is used for indicating an "invalid" layout for use by the
     * framework only. HAL implementations must not accept or emit
     * AudioChannelLayout values for this variant. The field value must always
     * be '0'.
     */
    int invalid = 0;
    /**
     * This variant is used for representing indexed masks. The mask indicates
     * what channels are used. For example, the mask that specifies to use only
     * channels 1 and 3 when interacting with a multi-channel device is defined
     * as a combination of the 1st and the 3rd bits and thus is equal to 5. See
     * also the 'INDEX_MASK_*' constants. The 'indexMask' field must have at
     * least one bit set.
     */
    int indexMask;
    /**
     * This variant is used for representing layout masks.
     * It is recommended to use one of 'LAYOUT_*' values. The 'layoutMask' field
     * must have at least one bit set.
     */
    int layoutMask;
    /**
     * This variant is used for processing of voice audio input and output.
     * It is recommended to use one of 'VOICE_*' values. The 'voiceMask' field
     * must have at least one bit set.
     */
    int voiceMask;

    /**
     * 'INDEX_MASK_*' constants define how many channels are used.
     * The mask constants below are 'canonical' masks. Each 'INDEX_MASK_N'
     * constant declares that all N channels are used and arranges
     * them starting from the LSB.
     */
    const int INDEX_MASK_1 = (1 << 1) - 1;
    const int INDEX_MASK_2 = (1 << 2) - 1;
    const int INDEX_MASK_3 = (1 << 3) - 1;
    const int INDEX_MASK_4 = (1 << 4) - 1;
    const int INDEX_MASK_5 = (1 << 5) - 1;
    const int INDEX_MASK_6 = (1 << 6) - 1;
    const int INDEX_MASK_7 = (1 << 7) - 1;
    const int INDEX_MASK_8 = (1 << 8) - 1;
    const int INDEX_MASK_9 = (1 << 9) - 1;
    const int INDEX_MASK_10 = (1 << 10) - 1;
    const int INDEX_MASK_11 = (1 << 11) - 1;
    const int INDEX_MASK_12 = (1 << 12) - 1;
    const int INDEX_MASK_13 = (1 << 13) - 1;
    const int INDEX_MASK_14 = (1 << 14) - 1;
    const int INDEX_MASK_15 = (1 << 15) - 1;
    const int INDEX_MASK_16 = (1 << 16) - 1;
    const int INDEX_MASK_17 = (1 << 17) - 1;
    const int INDEX_MASK_18 = (1 << 18) - 1;
    const int INDEX_MASK_19 = (1 << 19) - 1;
    const int INDEX_MASK_20 = (1 << 20) - 1;
    const int INDEX_MASK_21 = (1 << 21) - 1;
    const int INDEX_MASK_22 = (1 << 22) - 1;
    const int INDEX_MASK_23 = (1 << 23) - 1;
    const int INDEX_MASK_24 = (1 << 24) - 1;

    /**
     * 'LAYOUT_*' constants define channel layouts recognized by
     * the audio system. The order of the channels in the frame is assumed
     * to be from the LSB to MSB for all the bits set to '1'.
     */
    const int LAYOUT_MONO = CHANNEL_FRONT_LEFT;
    const int LAYOUT_STEREO = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT;
    const int LAYOUT_2POINT1 = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_LOW_FREQUENCY;
    const int LAYOUT_TRI = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_FRONT_CENTER;
    const int LAYOUT_TRI_BACK = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_BACK_CENTER;
    const int LAYOUT_3POINT1 =
            CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_FRONT_CENTER | CHANNEL_LOW_FREQUENCY;
    const int LAYOUT_2POINT0POINT2 = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT
            | CHANNEL_TOP_SIDE_LEFT | CHANNEL_TOP_SIDE_RIGHT;
    const int LAYOUT_2POINT1POINT2 = LAYOUT_2POINT0POINT2 | CHANNEL_LOW_FREQUENCY;
    const int LAYOUT_3POINT0POINT2 = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_FRONT_CENTER
            | CHANNEL_TOP_SIDE_LEFT | CHANNEL_TOP_SIDE_RIGHT;
    const int LAYOUT_3POINT1POINT2 = LAYOUT_3POINT0POINT2 | CHANNEL_LOW_FREQUENCY;
    const int LAYOUT_QUAD =
            CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT;
    const int LAYOUT_QUAD_SIDE =
            CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_SIDE_LEFT | CHANNEL_SIDE_RIGHT;
    const int LAYOUT_SURROUND =
            CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_FRONT_CENTER | CHANNEL_BACK_CENTER;
    const int LAYOUT_PENTA = LAYOUT_QUAD | CHANNEL_FRONT_CENTER;
    const int LAYOUT_5POINT1 = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_FRONT_CENTER
            | CHANNEL_LOW_FREQUENCY | CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT;
    const int LAYOUT_5POINT1_SIDE = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_FRONT_CENTER
            | CHANNEL_LOW_FREQUENCY | CHANNEL_SIDE_LEFT | CHANNEL_SIDE_RIGHT;
    const int LAYOUT_5POINT1POINT2 =
            LAYOUT_5POINT1 | CHANNEL_TOP_SIDE_LEFT | CHANNEL_TOP_SIDE_RIGHT;
    const int LAYOUT_5POINT1POINT4 = LAYOUT_5POINT1 | CHANNEL_TOP_FRONT_LEFT
            | CHANNEL_TOP_FRONT_RIGHT | CHANNEL_TOP_BACK_LEFT | CHANNEL_TOP_BACK_RIGHT;
    const int LAYOUT_6POINT1 = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_FRONT_CENTER
            | CHANNEL_LOW_FREQUENCY | CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT | CHANNEL_BACK_CENTER;
    const int LAYOUT_7POINT1 = LAYOUT_5POINT1 | CHANNEL_SIDE_LEFT | CHANNEL_SIDE_RIGHT;
    const int LAYOUT_7POINT1POINT2 =
            LAYOUT_7POINT1 | CHANNEL_TOP_SIDE_LEFT | CHANNEL_TOP_SIDE_RIGHT;
    const int LAYOUT_7POINT1POINT4 = LAYOUT_7POINT1 | CHANNEL_TOP_FRONT_LEFT
            | CHANNEL_TOP_FRONT_RIGHT | CHANNEL_TOP_BACK_LEFT | CHANNEL_TOP_BACK_RIGHT;
    const int LAYOUT_9POINT1POINT4 =
            LAYOUT_7POINT1POINT4 | CHANNEL_FRONT_WIDE_LEFT | CHANNEL_FRONT_WIDE_RIGHT;
    const int LAYOUT_9POINT1POINT6 =
            LAYOUT_9POINT1POINT4 | CHANNEL_TOP_SIDE_LEFT | CHANNEL_TOP_SIDE_RIGHT;
    const int LAYOUT_13POINT_360RA = CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT | CHANNEL_FRONT_CENTER
            | CHANNEL_SIDE_LEFT | CHANNEL_SIDE_RIGHT | CHANNEL_TOP_FRONT_LEFT
            | CHANNEL_TOP_FRONT_RIGHT | CHANNEL_TOP_FRONT_CENTER | CHANNEL_TOP_BACK_LEFT
            | CHANNEL_TOP_BACK_RIGHT | CHANNEL_BOTTOM_FRONT_LEFT | CHANNEL_BOTTOM_FRONT_RIGHT
            | CHANNEL_BOTTOM_FRONT_CENTER;
    const int LAYOUT_22POINT2 = LAYOUT_7POINT1POINT4 | CHANNEL_FRONT_LEFT_OF_CENTER
            | CHANNEL_FRONT_RIGHT_OF_CENTER | CHANNEL_BACK_CENTER | CHANNEL_TOP_CENTER
            | CHANNEL_TOP_FRONT_CENTER | CHANNEL_TOP_BACK_CENTER | CHANNEL_TOP_SIDE_LEFT
            | CHANNEL_TOP_SIDE_RIGHT | CHANNEL_BOTTOM_FRONT_LEFT | CHANNEL_BOTTOM_FRONT_RIGHT
            | CHANNEL_BOTTOM_FRONT_CENTER | CHANNEL_LOW_FREQUENCY_2;
    const int LAYOUT_MONO_HAPTIC_A = LAYOUT_MONO | CHANNEL_HAPTIC_A;
    const int LAYOUT_STEREO_HAPTIC_A = LAYOUT_STEREO | CHANNEL_HAPTIC_A;
    const int LAYOUT_HAPTIC_AB = CHANNEL_HAPTIC_A | CHANNEL_HAPTIC_B;
    const int LAYOUT_MONO_HAPTIC_AB = LAYOUT_MONO | LAYOUT_HAPTIC_AB;
    const int LAYOUT_STEREO_HAPTIC_AB = LAYOUT_STEREO | LAYOUT_HAPTIC_AB;
    const int LAYOUT_FRONT_BACK = CHANNEL_FRONT_CENTER | CHANNEL_BACK_CENTER;

    /**
     * Expresses the convention when stereo audio samples are stored interleaved
     * in an array.  This should improve readability by allowing code to use
     * symbolic indices instead of hard-coded [0] and [1].
     *
     * For multi-channel beyond stereo, the platform convention is that channels
     * are interleaved in order from least significant channel mask bit to most
     * significant channel mask bit, with unused bits skipped. Any exceptions
     * to this convention will be noted at the appropriate API.
     */
    const int INTERLEAVE_LEFT = 0;
    const int INTERLEAVE_RIGHT = 1;

    /**
     * 'CHANNEL_*' constants are used to build 'LAYOUT_*' masks. Each constant
     * must have exactly one bit set. The values do not match
     * 'android.media.AudioFormat.CHANNEL_OUT_*' constants from the SDK
     * for better efficiency in masks processing.
     */
    const int CHANNEL_FRONT_LEFT = 1 << 0;
    const int CHANNEL_FRONT_RIGHT = 1 << 1;
    const int CHANNEL_FRONT_CENTER = 1 << 2;
    const int CHANNEL_LOW_FREQUENCY = 1 << 3;
    const int CHANNEL_BACK_LEFT = 1 << 4;
    const int CHANNEL_BACK_RIGHT = 1 << 5;
    const int CHANNEL_FRONT_LEFT_OF_CENTER = 1 << 6;
    const int CHANNEL_FRONT_RIGHT_OF_CENTER = 1 << 7;
    const int CHANNEL_BACK_CENTER = 1 << 8;
    const int CHANNEL_SIDE_LEFT = 1 << 9;
    const int CHANNEL_SIDE_RIGHT = 1 << 10;
    const int CHANNEL_TOP_CENTER = 1 << 11;
    const int CHANNEL_TOP_FRONT_LEFT = 1 << 12;
    const int CHANNEL_TOP_FRONT_CENTER = 1 << 13;
    const int CHANNEL_TOP_FRONT_RIGHT = 1 << 14;
    const int CHANNEL_TOP_BACK_LEFT = 1 << 15;
    const int CHANNEL_TOP_BACK_CENTER = 1 << 16;
    const int CHANNEL_TOP_BACK_RIGHT = 1 << 17;
    const int CHANNEL_TOP_SIDE_LEFT = 1 << 18;
    const int CHANNEL_TOP_SIDE_RIGHT = 1 << 19;
    const int CHANNEL_BOTTOM_FRONT_LEFT = 1 << 20;
    const int CHANNEL_BOTTOM_FRONT_CENTER = 1 << 21;
    const int CHANNEL_BOTTOM_FRONT_RIGHT = 1 << 22;
    const int CHANNEL_LOW_FREQUENCY_2 = 1 << 23;
    const int CHANNEL_FRONT_WIDE_LEFT = 1 << 24;
    const int CHANNEL_FRONT_WIDE_RIGHT = 1 << 25;
    /**
     * Haptic channels are not part of multichannel standards, however they
     * enhance user experience when playing so they are packed together with the
     * channels of the program. To avoid collision with positional channels the
     * values for haptic channels start at the MSB of an integer (after the sign
     * bit) and move down to LSB.
     */
    const int CHANNEL_HAPTIC_B = 1 << 29;
    const int CHANNEL_HAPTIC_A = 1 << 30;

    /**
     * 'VOICE_*' constants define layouts for voice audio. The order of the
     * channels in the frame is assumed to be from the LSB to MSB for all the
     * bits set to '1'.
     */
    const int VOICE_UPLINK_MONO = CHANNEL_VOICE_UPLINK;
    const int VOICE_DNLINK_MONO = CHANNEL_VOICE_DNLINK;
    const int VOICE_CALL_MONO = CHANNEL_VOICE_UPLINK | CHANNEL_VOICE_DNLINK;

    /**
     * 'CHANNEL_VOICE_*' constants are used to build 'VOICE_*' masks. Each
     * constant must have exactly one bit set. Use the same values as
     * 'android.media.AudioFormat.CHANNEL_IN_VOICE_*' constants from the SDK.
     */
    const int CHANNEL_VOICE_UPLINK = 0x4000;
    const int CHANNEL_VOICE_DNLINK = 0x8000;
}
