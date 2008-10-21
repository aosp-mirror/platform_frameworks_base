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

import com.google.common.io.protocol.ProtoBuf;
import com.google.common.io.protocol.ProtoBufType;

public class LocserverMessageTypes {
  public static final ProtoBufType RESPONSE_CODES = new ProtoBufType();
  public static final ProtoBufType GLOC_REQUEST_ELEMENT = new ProtoBufType();
  public static final ProtoBufType GLOC_REQUEST = new ProtoBufType();
  public static final ProtoBufType GGEOCODE_REQUEST = new ProtoBufType();
  public static final ProtoBufType GLOC_REPLY_ELEMENT = new ProtoBufType();
  public static final ProtoBufType GLOC_REPLY = new ProtoBufType();

  static {
    GLOC_REQUEST_ELEMENT
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocRequestElement.CELLULAR_PROFILE, GcellularMessageTypes.GCELLULAR_PROFILE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocRequestElement.WIFI_PROFILE, GwifiMessageTypes.GWIFI_PROFILE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocRequestElement.LOCATION, GlocationMessageTypes.GLOCATION)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocRequestElement.GEOCODE, GGEOCODE_REQUEST)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocRequestElement.DEBUG_PROFILE, GdebugprofileMessageTypes.GDEBUG_PROFILE);

    GLOC_REQUEST
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_MESSAGE,
          GLocRequest.PLATFORM_PROFILE, GlocationMessageTypes.GPLATFORM_PROFILE)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GLocRequest.APP_PROFILES, GlocationMessageTypes.GAPP_PROFILE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocRequest.USER_PROFILE, GlocationMessageTypes.GUSER_PROFILE)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GLocRequest.REQUEST_ELEMENTS, GLOC_REQUEST_ELEMENT)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocRequest.MASF_CLIENT_INFO, null);

    GGEOCODE_REQUEST
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_FIXED32,
          GGeocodeRequest.NUM_FEATURE_LIMIT, new Long(1))
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_BOOL,
          GGeocodeRequest.INCLUDE_BOUNDING_BOXES, ProtoBuf.FALSE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GGeocodeRequest.BOUNDING_BOX, GrectangleMessageTypes.GRECTANGLE);

    GLOC_REPLY_ELEMENT
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_INT32,
          GLocReplyElement.STATUS, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GLocReplyElement.LOCATION, GlocationMessageTypes.GLOCATION)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GLocReplyElement.DEVICE_LOCATION, GlocationMessageTypes.GDEVICE_LOCATION);

    GLOC_REPLY
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_INT32,
          GLocReply.STATUS, null)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GLocReply.REPLY_ELEMENTS, GLOC_REPLY_ELEMENT)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_DATA,
          GLocReply.PLATFORM_KEY, null);

  }
}
