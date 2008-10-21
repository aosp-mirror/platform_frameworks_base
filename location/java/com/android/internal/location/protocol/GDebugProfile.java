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

public interface GDebugProfile {
  static final int TRIGGER_CELL_CHANGE = 1;
  static final int TRIGGER_WIFI_CHANGE = 2;
  static final int TRIGGER_CELL_AND_WIFI_CHANGE = 3;
  static final int TRIGGER_GPS_CHANGE = 4;
  static final int TRIGGER_OTHER = 5;
  static final int TRIGGER_COLLECTION_START_BURST = 6;
  static final int TRIGGER_COLLECTION_RESTART_BURST = 7;
  static final int TRIGGER_COLLECTION_CONTINUE_BURST = 8;
  static final int TRIGGER_COLLECTION_END_BURST = 9;
  static final int TRIGGER_COLLECTION_END_BURST_AT_SAME_LOCATION = 10;
  static final int TRIGGER_COLLECTION_MOVED_DISTANCE = 11;

  static final int TRIGGER = 1;
  static final int ACTUAL_REQUEST = 2;
  static final int CACHE_LOCATION = 3;
}

