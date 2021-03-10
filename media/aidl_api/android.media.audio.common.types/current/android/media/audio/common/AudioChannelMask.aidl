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
 */// This file has been semi-automatically generated using hidl2aidl from its counterpart in
// hardware/interfaces/audio/common/5.0/types.hal
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
@Backing(type="int") @VintfStability
enum AudioChannelMask {
  REPRESENTATION_POSITION = 0,
  REPRESENTATION_INDEX = 2,
  NONE = 0,
  INVALID = -1073741824,
  OUT_FRONT_LEFT = 1,
  OUT_FRONT_RIGHT = 2,
  OUT_FRONT_CENTER = 4,
  OUT_LOW_FREQUENCY = 8,
  OUT_BACK_LEFT = 16,
  OUT_BACK_RIGHT = 32,
  OUT_FRONT_LEFT_OF_CENTER = 64,
  OUT_FRONT_RIGHT_OF_CENTER = 128,
  OUT_BACK_CENTER = 256,
  OUT_SIDE_LEFT = 512,
  OUT_SIDE_RIGHT = 1024,
  OUT_TOP_CENTER = 2048,
  OUT_TOP_FRONT_LEFT = 4096,
  OUT_TOP_FRONT_CENTER = 8192,
  OUT_TOP_FRONT_RIGHT = 16384,
  OUT_TOP_BACK_LEFT = 32768,
  OUT_TOP_BACK_CENTER = 65536,
  OUT_TOP_BACK_RIGHT = 131072,
  OUT_TOP_SIDE_LEFT = 262144,
  OUT_TOP_SIDE_RIGHT = 524288,
  OUT_HAPTIC_A = 536870912,
  OUT_HAPTIC_B = 268435456,
  OUT_MONO = 1,
  OUT_STEREO = 3,
  OUT_2POINT1 = 11,
  OUT_2POINT0POINT2 = 786435,
  OUT_2POINT1POINT2 = 786443,
  OUT_3POINT0POINT2 = 786439,
  OUT_3POINT1POINT2 = 786447,
  OUT_QUAD = 51,
  OUT_QUAD_BACK = 51,
  OUT_QUAD_SIDE = 1539,
  OUT_SURROUND = 263,
  OUT_PENTA = 55,
  OUT_5POINT1 = 63,
  OUT_5POINT1_BACK = 63,
  OUT_5POINT1_SIDE = 1551,
  OUT_5POINT1POINT2 = 786495,
  OUT_5POINT1POINT4 = 184383,
  OUT_6POINT1 = 319,
  OUT_7POINT1 = 1599,
  OUT_7POINT1POINT2 = 788031,
  OUT_7POINT1POINT4 = 185919,
  OUT_MONO_HAPTIC_A = 536870913,
  OUT_STEREO_HAPTIC_A = 536870915,
  OUT_HAPTIC_AB = 805306368,
  OUT_MONO_HAPTIC_AB = 805306369,
  OUT_STEREO_HAPTIC_AB = 805306371,
  IN_LEFT = 4,
  IN_RIGHT = 8,
  IN_FRONT = 16,
  IN_BACK = 32,
  IN_LEFT_PROCESSED = 64,
  IN_RIGHT_PROCESSED = 128,
  IN_FRONT_PROCESSED = 256,
  IN_BACK_PROCESSED = 512,
  IN_PRESSURE = 1024,
  IN_X_AXIS = 2048,
  IN_Y_AXIS = 4096,
  IN_Z_AXIS = 8192,
  IN_BACK_LEFT = 65536,
  IN_BACK_RIGHT = 131072,
  IN_CENTER = 262144,
  IN_LOW_FREQUENCY = 1048576,
  IN_TOP_LEFT = 2097152,
  IN_TOP_RIGHT = 4194304,
  IN_VOICE_UPLINK = 16384,
  IN_VOICE_DNLINK = 32768,
}
