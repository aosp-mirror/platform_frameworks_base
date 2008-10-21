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

public interface GLocation {
  static final int LOCTYPE_GPS = 0;
  static final int LOCTYPE_MAPCENTER = 1;
  static final int LOCTYPE_CENTROID = 2;
  static final int LOCTYPE_TOWER_LOCATION = 3;

  static final int LAT_LNG = 1;
  static final int SOURCE = 2;
  static final int ACCURACY = 3;
  static final int CONFIDENCE = 4;
  static final int FEATURE = 5;
  static final int TIMESTAMP = 6;
  static final int OBSOLETE = 7;
  static final int LOC_TYPE = 8;
  static final int MISC = 9;
  static final int ALTITUDE = 10;
  static final int VERTICAL_ACCURACY = 11;
  static final int VELOCITY = 12;
  static final int HEADING = 13;
  static final int GPS_PROFILE = 14;
  static final int LOCATION_STRING = 15;
}

