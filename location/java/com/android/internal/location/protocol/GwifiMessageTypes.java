/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.location.protocol;

import com.google.common.io.protocol.ProtoBufType;

public class GwifiMessageTypes {
  public static final ProtoBufType GWIFI_DEVICE = new ProtoBufType();
  public static final ProtoBufType GWIFI_PROFILE = new ProtoBufType();

  static {
    GWIFI_DEVICE
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_DATA,
          GWifiDevice.MAC, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GWifiDevice.SSID, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GWifiDevice.CHANNEL, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GWifiDevice.RSSI, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GWifiDevice.NOISE, null);

    GWIFI_PROFILE
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_INT64,
          GWifiProfile.TIMESTAMP, null)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GWifiProfile.WIFI_DEVICES, GWIFI_DEVICE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GWifiProfile.PREFETCH_MODE, null);

  }
}
