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

public class GaddressMessageTypes {
  public static final ProtoBufType GADDRESS = new ProtoBufType();
  public static final ProtoBufType GADDRESS_COMPONENT = new ProtoBufType();

  static {
    GADDRESS
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_DATA,
          GAddress.FORMATTED_ADDRESS_LINE, null)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GAddress.COMPONENT, GADDRESS_COMPONENT);

    GADDRESS_COMPONENT
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_DATA,
          GAddressComponent.NAME, null)
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_INT32,
          GAddressComponent.FEATURE_TYPE, null);

  }
}
