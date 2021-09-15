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
parcelable AudioDeviceDescription {
  android.media.audio.common.AudioDeviceType type = android.media.audio.common.AudioDeviceType.NONE;
  @utf8InCpp String connection;
  const @utf8InCpp String CONNECTION_ANALOG = "analog";
  const @utf8InCpp String CONNECTION_ANALOG_DOCK = "analog-dock";
  const @utf8InCpp String CONNECTION_BT_A2DP = "bt-a2dp";
  const @utf8InCpp String CONNECTION_BT_LE = "bt-le";
  const @utf8InCpp String CONNECTION_BT_SCO = "bt-sco";
  const @utf8InCpp String CONNECTION_BUS = "bus";
  const @utf8InCpp String CONNECTION_DIGITAL_DOCK = "digital-dock";
  const @utf8InCpp String CONNECTION_HDMI = "hdmi";
  const @utf8InCpp String CONNECTION_HDMI_ARC = "hdmi-arc";
  const @utf8InCpp String CONNECTION_HDMI_EARC = "hdmi-earc";
  const @utf8InCpp String CONNECTION_IP_V4 = "ip-v4";
  const @utf8InCpp String CONNECTION_SPDIF = "spdif";
  const @utf8InCpp String CONNECTION_WIRELESS = "wireless";
  const @utf8InCpp String CONNECTION_USB = "usb";
}
