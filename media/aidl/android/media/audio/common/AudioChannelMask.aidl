/*
 * Copyright (C) 2019 The Android Open Source Project
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

 // This file has been semi-automatically generated using hidl2aidl from its counterpart in
 // hardware/interfaces/audio/common/5.0/types.hal

package android.media.audio.common;

/**
 * A channel mask per se only defines the presence or absence of a channel, not
 * the order.
 *
 * The channel order convention is that channels are interleaved in order from
 * least significant channel mask bit to most significant channel mask bit,
 * with unused bits skipped. For example for stereo, LEFT would be first,
 * followed by RIGHT.
 * Any exceptions to this convention are noted at the appropriate API.
 *
 * AudioChannelMask is an opaque type and its internal layout should not be
 * assumed as it may change in the future.  Instead, always use functions
 * to examine it.
 *
 * These are the current representations:
 *
 *   REPRESENTATION_POSITION
 *     is a channel mask representation for position assignment.  Each low-order
 *     bit corresponds to the spatial position of a transducer (output), or
 *     interpretation of channel (input).  The user of a channel mask needs to
 *     know the context of whether it is for output or input.  The constants
 *     OUT_* or IN_* apply to the bits portion.  It is not permitted for no bits
 *     to be set.
 *
 *   REPRESENTATION_INDEX
 *     is a channel mask representation for index assignment.  Each low-order
 *     bit corresponds to a selected channel.  There is no platform
 *     interpretation of the various bits.  There is no concept of output or
 *     input.  It is not permitted for no bits to be set.
 *
 * All other representations are reserved for future use.
 *
 * Warning: current representation distinguishes between input and output, but
 * this will not the be case in future revisions of the platform. Wherever there
 * is an ambiguity between input and output that is currently resolved by
 * checking the channel mask, the implementer should look for ways to fix it
 * with additional information outside of the mask.
 *
 * {@hide}
 */
@Backing(type="int")
enum AudioChannelMask {
    /**
     * must be 0 for compatibility
     */
    REPRESENTATION_POSITION = 0,
    /**
     * 1 is reserved for future use
     */
    REPRESENTATION_INDEX = 2,
    /**
     * 3 is reserved for future use
     *
     *
     * These can be a complete value of AudioChannelMask
     */
    NONE = 0x0,
    INVALID = 0xC0000000,
    /**
     * These can be the bits portion of an AudioChannelMask
     * with representation REPRESENTATION_POSITION.
     *
     *
     * output channels
     */
    OUT_FRONT_LEFT = 0x1,
    OUT_FRONT_RIGHT = 0x2,
    OUT_FRONT_CENTER = 0x4,
    OUT_LOW_FREQUENCY = 0x8,
    OUT_BACK_LEFT = 0x10,
    OUT_BACK_RIGHT = 0x20,
    OUT_FRONT_LEFT_OF_CENTER = 0x40,
    OUT_FRONT_RIGHT_OF_CENTER = 0x80,
    OUT_BACK_CENTER = 0x100,
    OUT_SIDE_LEFT = 0x200,
    OUT_SIDE_RIGHT = 0x400,
    OUT_TOP_CENTER = 0x800,
    OUT_TOP_FRONT_LEFT = 0x1000,
    OUT_TOP_FRONT_CENTER = 0x2000,
    OUT_TOP_FRONT_RIGHT = 0x4000,
    OUT_TOP_BACK_LEFT = 0x8000,
    OUT_TOP_BACK_CENTER = 0x10000,
    OUT_TOP_BACK_RIGHT = 0x20000,
    OUT_TOP_SIDE_LEFT = 0x40000,
    OUT_TOP_SIDE_RIGHT = 0x80000,
    /**
     * Haptic channel characteristics are specific to a device and
     * only used to play device specific resources (eg: ringtones).
     * The HAL can freely map A and B to haptic controllers, the
     * framework shall not interpret those values and forward them
     * from the device audio assets.
     */
    OUT_HAPTIC_A = 0x20000000,
    OUT_HAPTIC_B = 0x10000000,
// TODO(ytai): Aliases not currently supported in AIDL - can inline the values.
//    OUT_MONO = OUT_FRONT_LEFT,
//    OUT_STEREO = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT),
//    OUT_2POINT1 = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_LOW_FREQUENCY),
//    OUT_2POINT0POINT2 = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_TOP_SIDE_LEFT | OUT_TOP_SIDE_RIGHT),
//    OUT_2POINT1POINT2 = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_TOP_SIDE_LEFT | OUT_TOP_SIDE_RIGHT | OUT_LOW_FREQUENCY),
//    OUT_3POINT0POINT2 = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_FRONT_CENTER | OUT_TOP_SIDE_LEFT | OUT_TOP_SIDE_RIGHT),
//    OUT_3POINT1POINT2 = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_FRONT_CENTER | OUT_TOP_SIDE_LEFT | OUT_TOP_SIDE_RIGHT | OUT_LOW_FREQUENCY),
//    OUT_QUAD = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_BACK_LEFT | OUT_BACK_RIGHT),
//    OUT_QUAD_BACK = OUT_QUAD,
//    /**
//     * like OUT_QUAD_BACK with *_SIDE_* instead of *_BACK_*
//     */
//    OUT_QUAD_SIDE = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_SIDE_LEFT | OUT_SIDE_RIGHT),
//    OUT_SURROUND = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_FRONT_CENTER | OUT_BACK_CENTER),
//    OUT_PENTA = (OUT_QUAD | OUT_FRONT_CENTER),
//    OUT_5POINT1 = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_FRONT_CENTER | OUT_LOW_FREQUENCY | OUT_BACK_LEFT | OUT_BACK_RIGHT),
//    OUT_5POINT1_BACK = OUT_5POINT1,
//    /**
//     * like OUT_5POINT1_BACK with *_SIDE_* instead of *_BACK_*
//     */
//    OUT_5POINT1_SIDE = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_FRONT_CENTER | OUT_LOW_FREQUENCY | OUT_SIDE_LEFT | OUT_SIDE_RIGHT),
//    OUT_5POINT1POINT2 = (OUT_5POINT1 | OUT_TOP_SIDE_LEFT | OUT_TOP_SIDE_RIGHT),
//    OUT_5POINT1POINT4 = (OUT_5POINT1 | OUT_TOP_FRONT_LEFT | OUT_TOP_FRONT_RIGHT | OUT_TOP_BACK_LEFT | OUT_TOP_BACK_RIGHT),
//    OUT_6POINT1 = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_FRONT_CENTER | OUT_LOW_FREQUENCY | OUT_BACK_LEFT | OUT_BACK_RIGHT | OUT_BACK_CENTER),
//    /**
//     * matches the correct AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
//     */
//    OUT_7POINT1 = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_FRONT_CENTER | OUT_LOW_FREQUENCY | OUT_BACK_LEFT | OUT_BACK_RIGHT | OUT_SIDE_LEFT | OUT_SIDE_RIGHT),
//    OUT_7POINT1POINT2 = (OUT_7POINT1 | OUT_TOP_SIDE_LEFT | OUT_TOP_SIDE_RIGHT),
//    OUT_7POINT1POINT4 = (OUT_7POINT1 | OUT_TOP_FRONT_LEFT | OUT_TOP_FRONT_RIGHT | OUT_TOP_BACK_LEFT | OUT_TOP_BACK_RIGHT),
//    OUT_MONO_HAPTIC_A = (OUT_FRONT_LEFT | OUT_HAPTIC_A),
//    OUT_STEREO_HAPTIC_A = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_HAPTIC_A),
//    OUT_HAPTIC_AB = (OUT_HAPTIC_A | OUT_HAPTIC_B),
//    OUT_MONO_HAPTIC_AB = (OUT_FRONT_LEFT | OUT_HAPTIC_A | OUT_HAPTIC_B),
//    OUT_STEREO_HAPTIC_AB = (OUT_FRONT_LEFT | OUT_FRONT_RIGHT | OUT_HAPTIC_A | OUT_HAPTIC_B),
    /**
     * These are bits only, not complete values
     *
     *
     * input channels
     */
    IN_LEFT = 0x4,
    IN_RIGHT = 0x8,
    IN_FRONT = 0x10,
    IN_BACK = 0x20,
    IN_LEFT_PROCESSED = 0x40,
    IN_RIGHT_PROCESSED = 0x80,
    IN_FRONT_PROCESSED = 0x100,
    IN_BACK_PROCESSED = 0x200,
    IN_PRESSURE = 0x400,
    IN_X_AXIS = 0x800,
    IN_Y_AXIS = 0x1000,
    IN_Z_AXIS = 0x2000,
    IN_BACK_LEFT = 0x10000,
    IN_BACK_RIGHT = 0x20000,
    IN_CENTER = 0x40000,
    IN_LOW_FREQUENCY = 0x100000,
    IN_TOP_LEFT = 0x200000,
    IN_TOP_RIGHT = 0x400000,
    IN_VOICE_UPLINK = 0x4000,
    IN_VOICE_DNLINK = 0x8000,
// TODO(ytai): Aliases not currently supported in AIDL - can inline the values.
//    IN_MONO = IN_FRONT,
//    IN_STEREO = (IN_LEFT | IN_RIGHT),
//    IN_FRONT_BACK = (IN_FRONT | IN_BACK),
//    IN_6 = (IN_LEFT | IN_RIGHT | IN_FRONT | IN_BACK | IN_LEFT_PROCESSED | IN_RIGHT_PROCESSED),
//    IN_2POINT0POINT2 = (IN_LEFT | IN_RIGHT | IN_TOP_LEFT | IN_TOP_RIGHT),
//    IN_2POINT1POINT2 = (IN_LEFT | IN_RIGHT | IN_TOP_LEFT | IN_TOP_RIGHT | IN_LOW_FREQUENCY),
//    IN_3POINT0POINT2 = (IN_LEFT | IN_CENTER | IN_RIGHT | IN_TOP_LEFT | IN_TOP_RIGHT),
//    IN_3POINT1POINT2 = (IN_LEFT | IN_CENTER | IN_RIGHT | IN_TOP_LEFT | IN_TOP_RIGHT | IN_LOW_FREQUENCY),
//    IN_5POINT1 = (IN_LEFT | IN_CENTER | IN_RIGHT | IN_BACK_LEFT | IN_BACK_RIGHT | IN_LOW_FREQUENCY),
//    IN_VOICE_UPLINK_MONO = (IN_VOICE_UPLINK | IN_MONO),
//    IN_VOICE_DNLINK_MONO = (IN_VOICE_DNLINK | IN_MONO),
//    IN_VOICE_CALL_MONO = (IN_VOICE_UPLINK_MONO | IN_VOICE_DNLINK_MONO),
//    COUNT_MAX = 30,
//    INDEX_HDR = REPRESENTATION_INDEX << COUNT_MAX,
//    INDEX_MASK_1 = INDEX_HDR | ((1 << 1) - 1),
//    INDEX_MASK_2 = INDEX_HDR | ((1 << 2) - 1),
//    INDEX_MASK_3 = INDEX_HDR | ((1 << 3) - 1),
//    INDEX_MASK_4 = INDEX_HDR | ((1 << 4) - 1),
//    INDEX_MASK_5 = INDEX_HDR | ((1 << 5) - 1),
//    INDEX_MASK_6 = INDEX_HDR | ((1 << 6) - 1),
//    INDEX_MASK_7 = INDEX_HDR | ((1 << 7) - 1),
//    INDEX_MASK_8 = INDEX_HDR | ((1 << 8) - 1),
//    INDEX_MASK_9 = INDEX_HDR | ((1 << 9) - 1),
//    INDEX_MASK_10 = INDEX_HDR | ((1 << 10) - 1),
//    INDEX_MASK_11 = INDEX_HDR | ((1 << 11) - 1),
//    INDEX_MASK_12 = INDEX_HDR | ((1 << 12) - 1),
//    INDEX_MASK_13 = INDEX_HDR | ((1 << 13) - 1),
//    INDEX_MASK_14 = INDEX_HDR | ((1 << 14) - 1),
//    INDEX_MASK_15 = INDEX_HDR | ((1 << 15) - 1),
//    INDEX_MASK_16 = INDEX_HDR | ((1 << 16) - 1),
//    INDEX_MASK_17 = INDEX_HDR | ((1 << 17) - 1),
//    INDEX_MASK_18 = INDEX_HDR | ((1 << 18) - 1),
//    INDEX_MASK_19 = INDEX_HDR | ((1 << 19) - 1),
//    INDEX_MASK_20 = INDEX_HDR | ((1 << 20) - 1),
//    INDEX_MASK_21 = INDEX_HDR | ((1 << 21) - 1),
//    INDEX_MASK_22 = INDEX_HDR | ((1 << 22) - 1),
//    INDEX_MASK_23 = INDEX_HDR | ((1 << 23) - 1),
//    INDEX_MASK_24 = INDEX_HDR | ((1 << 24) - 1),
}
