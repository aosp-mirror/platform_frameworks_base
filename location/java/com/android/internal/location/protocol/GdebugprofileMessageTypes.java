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

public class GdebugprofileMessageTypes {
  public static final ProtoBufType GDEBUG_PROFILE = new ProtoBufType();

  static {
    GDEBUG_PROFILE
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GDebugProfile.TRIGGER, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_BOOL,
          GDebugProfile.ACTUAL_REQUEST, ProtoBuf.TRUE)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GDebugProfile.CACHE_LOCATION, GlocationMessageTypes.GDEVICE_LOCATION);

  }
}
