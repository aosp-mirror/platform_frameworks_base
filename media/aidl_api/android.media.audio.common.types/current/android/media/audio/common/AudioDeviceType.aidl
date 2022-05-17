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
@Backing(type="int") @VintfStability
enum AudioDeviceType {
  NONE = 0,
  IN_DEFAULT = 1,
  IN_ACCESSORY = 2,
  IN_AFE_PROXY = 3,
  IN_DEVICE = 4,
  IN_ECHO_REFERENCE = 5,
  IN_FM_TUNER = 6,
  IN_HEADSET = 7,
  IN_LOOPBACK = 8,
  IN_MICROPHONE = 9,
  IN_MICROPHONE_BACK = 10,
  IN_SUBMIX = 11,
  IN_TELEPHONY_RX = 12,
  IN_TV_TUNER = 13,
  IN_DOCK = 14,
  OUT_DEFAULT = 129,
  OUT_ACCESSORY = 130,
  OUT_AFE_PROXY = 131,
  OUT_CARKIT = 132,
  OUT_DEVICE = 133,
  OUT_ECHO_CANCELLER = 134,
  OUT_FM = 135,
  OUT_HEADPHONE = 136,
  OUT_HEADSET = 137,
  OUT_HEARING_AID = 138,
  OUT_LINE_AUX = 139,
  OUT_SPEAKER = 140,
  OUT_SPEAKER_EARPIECE = 141,
  OUT_SPEAKER_SAFE = 142,
  OUT_SUBMIX = 143,
  OUT_TELEPHONY_TX = 144,
  OUT_DOCK = 145,
  OUT_BROADCAST = 146,
}
