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

public interface GCellularPlatformProfile {
  static final int RADIO_TYPE_GPRS = 3;
  static final int RADIO_TYPE_CDMA = 4;
  static final int RADIO_TYPE_WCDMA = 5;

  static final int RADIO_TYPE = 1;
  static final int CARRIER = 2;
  static final int IP = 3;
  static final int HOME_MNC = 4;
  static final int HOME_MCC = 5;
}

