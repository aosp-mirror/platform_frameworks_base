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

public class GfeatureMessageTypes {
  public static final ProtoBufType GFEATURE_TYPE = new ProtoBufType();
  public static final ProtoBufType GFEATURE = new ProtoBufType();

  static {
    GFEATURE
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_DATA,
          GFeature.NAME, null)
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_INT32,
          GFeature.FEATURE_TYPE, null)
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_MESSAGE,
          GFeature.ADDRESS, GaddressMessageTypes.GADDRESS)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GFeature.BOUNDS, GrectangleMessageTypes.GRECTANGLE)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_MESSAGE,
          GFeature.CENTER, GlatlngMessageTypes.GLAT_LNG);

  }
}
