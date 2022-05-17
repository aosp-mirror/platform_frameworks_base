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
///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a snapshot of an AIDL file. Do not edit it manually. There are
// two cases:
// 1). this is a frozen version file - do not edit this in any case.
// 2). this is a 'current' file. If you make a backwards compatible change to
//     the interface (from the latest frozen version), the build system will
//     prompt you to update this file with `m <name>-update-api`.
//
// You must not make a backward incompatible change to any AIDL file built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

package android.media.audio.common;
/* @hide */
@JavaDerive(equals=true, toString=true) @VintfStability
union AudioChannelLayout {
  int none = 0;
  int invalid = 0;
  int indexMask;
  int layoutMask;
  int voiceMask;
  const int INDEX_MASK_1 = 1;
  const int INDEX_MASK_2 = 3;
  const int INDEX_MASK_3 = 7;
  const int INDEX_MASK_4 = 15;
  const int INDEX_MASK_5 = 31;
  const int INDEX_MASK_6 = 63;
  const int INDEX_MASK_7 = 127;
  const int INDEX_MASK_8 = 255;
  const int INDEX_MASK_9 = 511;
  const int INDEX_MASK_10 = 1023;
  const int INDEX_MASK_11 = 2047;
  const int INDEX_MASK_12 = 4095;
  const int INDEX_MASK_13 = 8191;
  const int INDEX_MASK_14 = 16383;
  const int INDEX_MASK_15 = 32767;
  const int INDEX_MASK_16 = 65535;
  const int INDEX_MASK_17 = 131071;
  const int INDEX_MASK_18 = 262143;
  const int INDEX_MASK_19 = 524287;
  const int INDEX_MASK_20 = 1048575;
  const int INDEX_MASK_21 = 2097151;
  const int INDEX_MASK_22 = 4194303;
  const int INDEX_MASK_23 = 8388607;
  const int INDEX_MASK_24 = 16777215;
  const int LAYOUT_MONO = 1;
  const int LAYOUT_STEREO = 3;
  const int LAYOUT_2POINT1 = 11;
  const int LAYOUT_TRI = 7;
  const int LAYOUT_TRI_BACK = 259;
  const int LAYOUT_3POINT1 = 15;
  const int LAYOUT_2POINT0POINT2 = 786435;
  const int LAYOUT_2POINT1POINT2 = 786443;
  const int LAYOUT_3POINT0POINT2 = 786439;
  const int LAYOUT_3POINT1POINT2 = 786447;
  const int LAYOUT_QUAD = 51;
  const int LAYOUT_QUAD_SIDE = 1539;
  const int LAYOUT_SURROUND = 263;
  const int LAYOUT_PENTA = 55;
  const int LAYOUT_5POINT1 = 63;
  const int LAYOUT_5POINT1_SIDE = 1551;
  const int LAYOUT_5POINT1POINT2 = 786495;
  const int LAYOUT_5POINT1POINT4 = 184383;
  const int LAYOUT_6POINT1 = 319;
  const int LAYOUT_7POINT1 = 1599;
  const int LAYOUT_7POINT1POINT2 = 788031;
  const int LAYOUT_7POINT1POINT4 = 185919;
  const int LAYOUT_9POINT1POINT4 = 50517567;
  const int LAYOUT_9POINT1POINT6 = 51303999;
  const int LAYOUT_13POINT_360RA = 7534087;
  const int LAYOUT_22POINT2 = 16777215;
  const int LAYOUT_MONO_HAPTIC_A = 1073741825;
  const int LAYOUT_STEREO_HAPTIC_A = 1073741827;
  const int LAYOUT_HAPTIC_AB = 1610612736;
  const int LAYOUT_MONO_HAPTIC_AB = 1610612737;
  const int LAYOUT_STEREO_HAPTIC_AB = 1610612739;
  const int LAYOUT_FRONT_BACK = 260;
  const int INTERLEAVE_LEFT = 0;
  const int INTERLEAVE_RIGHT = 1;
  const int CHANNEL_FRONT_LEFT = 1;
  const int CHANNEL_FRONT_RIGHT = 2;
  const int CHANNEL_FRONT_CENTER = 4;
  const int CHANNEL_LOW_FREQUENCY = 8;
  const int CHANNEL_BACK_LEFT = 16;
  const int CHANNEL_BACK_RIGHT = 32;
  const int CHANNEL_FRONT_LEFT_OF_CENTER = 64;
  const int CHANNEL_FRONT_RIGHT_OF_CENTER = 128;
  const int CHANNEL_BACK_CENTER = 256;
  const int CHANNEL_SIDE_LEFT = 512;
  const int CHANNEL_SIDE_RIGHT = 1024;
  const int CHANNEL_TOP_CENTER = 2048;
  const int CHANNEL_TOP_FRONT_LEFT = 4096;
  const int CHANNEL_TOP_FRONT_CENTER = 8192;
  const int CHANNEL_TOP_FRONT_RIGHT = 16384;
  const int CHANNEL_TOP_BACK_LEFT = 32768;
  const int CHANNEL_TOP_BACK_CENTER = 65536;
  const int CHANNEL_TOP_BACK_RIGHT = 131072;
  const int CHANNEL_TOP_SIDE_LEFT = 262144;
  const int CHANNEL_TOP_SIDE_RIGHT = 524288;
  const int CHANNEL_BOTTOM_FRONT_LEFT = 1048576;
  const int CHANNEL_BOTTOM_FRONT_CENTER = 2097152;
  const int CHANNEL_BOTTOM_FRONT_RIGHT = 4194304;
  const int CHANNEL_LOW_FREQUENCY_2 = 8388608;
  const int CHANNEL_FRONT_WIDE_LEFT = 16777216;
  const int CHANNEL_FRONT_WIDE_RIGHT = 33554432;
  const int CHANNEL_HAPTIC_B = 536870912;
  const int CHANNEL_HAPTIC_A = 1073741824;
  const int VOICE_UPLINK_MONO = 16384;
  const int VOICE_DNLINK_MONO = 32768;
  const int VOICE_CALL_MONO = 49152;
  const int CHANNEL_VOICE_UPLINK = 16384;
  const int CHANNEL_VOICE_DNLINK = 32768;
}
