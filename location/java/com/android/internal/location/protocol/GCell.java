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

public interface GCell {
  static final int LAC = 1;
  static final int CELLID = 2;
  static final int MNC = 3;
  static final int MCC = 4;
  static final int RSSI = 5;
  static final int AGE = 6;
  static final int TIMING_ADVANCE = 7;
  static final int PRIMARY_SCRAMBLING_CODE = 8;
}

