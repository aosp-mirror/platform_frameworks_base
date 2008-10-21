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

public class GcellularMessageTypes {
  public static final ProtoBufType GCELL = new ProtoBufType();
  public static final ProtoBufType GCELLULAR_PROFILE = new ProtoBufType();

  static {
    GCELL
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_INT32,
          GCell.LAC, null)
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_INT32,
          GCell.CELLID, null)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCell.MNC, new Long(-1))
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCell.MCC, new Long(-1))
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCell.RSSI, new Long(-9999))
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCell.AGE, new Long(0))
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCell.TIMING_ADVANCE, new Long(-1))
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCell.PRIMARY_SCRAMBLING_CODE, null);

    GCELLULAR_PROFILE
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_MESSAGE,
          GCellularProfile.PRIMARY_CELL, GCELL)
      .addElement(ProtoBufType.REQUIRED | ProtoBufType.TYPE_INT64,
          GCellularProfile.TIMESTAMP, null)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GCellularProfile.NEIGHBORS, GCELL)
      .addElement(ProtoBufType.REPEATED | ProtoBufType.TYPE_MESSAGE,
          GCellularProfile.HISTORICAL_CELLS, GCELL)
      .addElement(ProtoBufType.OPTIONAL | ProtoBufType.TYPE_INT32,
          GCellularProfile.PREFETCH_MODE, null);

  }
}
