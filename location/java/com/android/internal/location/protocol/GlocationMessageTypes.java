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

public class GlocationMessageTypes {
  public static final ProtoBufType GGPS_PROFILE = new ProtoBufType();
  public static final ProtoBufType GLOCATION = new ProtoBufType();
  public static final ProtoBufType GDEVICE_LOCATION = new ProtoBufType();
  public static final ProtoBufType GCELLULAR_PLATFORM_PROFILE = new ProtoBufType();
  public static final ProtoBufType GWIFI_PLATFORM_PROFILE = new ProtoBufType();
  public static final ProtoBufType GPREFETCH_MODE = new ProtoBufType();
  public static final ProtoBufType GPLATFORM_PROFILE = new ProtoBufType();
  public static final ProtoBufType GAPP_PROFILE = new ProtoBufType();
  public static final ProtoBufType GUSER_PROFILE = new ProtoBufType();

  static {
    GGPS_PROFILE
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GGpsProfile.GPS_FIX_TYPE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GGpsProfile.PDOP, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GGpsProfile.HDOP, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GGpsProfile.VDOP, null);

    GLOCATION
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocation.LAT_LNG, GlatlngMessageTypes.GLAT_LNG)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GLocation.SOURCE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GLocation.ACCURACY, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GLocation.CONFIDENCE, null)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GLocation.FEATURE, GfeatureMessageTypes.GFEATURE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT64,
          GLocation.TIMESTAMP, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_BOOL,
          GLocation.OBSOLETE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GLocation.LOC_TYPE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GLocation.MISC, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GLocation.ALTITUDE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GLocation.VERTICAL_ACCURACY, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GLocation.VELOCITY, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GLocation.HEADING, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocation.GPS_PROFILE, GGPS_PROFILE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GLocation.LOCATION_STRING, null);

    GDEVICE_LOCATION
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GDeviceLocation.LOCATION, GLOCATION)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GDeviceLocation.CELL, GcellularMessageTypes.GCELL)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GDeviceLocation.WIFI_DEVICE, GwifiMessageTypes.GWIFI_DEVICE);

    GCELLULAR_PLATFORM_PROFILE
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCellularPlatformProfile.RADIO_TYPE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GCellularPlatformProfile.CARRIER, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GCellularPlatformProfile.IP, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCellularPlatformProfile.HOME_MNC, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCellularPlatformProfile.HOME_MCC, null);

    GWIFI_PLATFORM_PROFILE
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GWifiPlatformProfile.SCANNER_MAC, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GWifiPlatformProfile.SCANNER_IP, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GWifiPlatformProfile.RADIO_TYPE, null);

    GPLATFORM_PROFILE
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_DATA,
          GPlatformProfile.VERSION, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GPlatformProfile.PLATFORM, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GPlatformProfile.PLATFORM_KEY, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GPlatformProfile.DISTRIBUTION_CHANNEL, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GPlatformProfile.LOCALE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GPlatformProfile.CELLULAR_PLATFORM_PROFILE, GCELLULAR_PLATFORM_PROFILE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GPlatformProfile.WIFI_PLATFORM_PROFILE, GWIFI_PLATFORM_PROFILE);

    GAPP_PROFILE
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GAppProfile.APP_NAME, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GAppProfile.APP_KEY, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GAppProfile.REQUEST_TYPE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GAppProfile.SEARCH_TYPE, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GAppProfile.SEARCH_TERM, null);

    GUSER_PROFILE
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GUserProfile.USER_NAME, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GUserProfile.AUTH_TOKEN, null);

  }
}
