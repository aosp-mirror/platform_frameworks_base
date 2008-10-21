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

public interface GFeature {
  static final int NAME = 1;
  static final int FEATURE_TYPE = 2;
  static final int ADDRESS = 3;
  static final int BOUNDS = 4;
  static final int CENTER = 5;

  static final int FEATURE_TYPE_UNKNOWN_TYPE = 0;
  static final int FEATURE_TYPE_COUNTRY = 1;
  static final int FEATURE_TYPE_COUNTRY_CODE = 2;
  static final int FEATURE_TYPE_ADMINISTRATIVE_AREA = 3;
  static final int FEATURE_TYPE_SUB_ADMINISTRATIVE_AREA = 4;
  static final int FEATURE_TYPE_LOCALITY = 5;
  static final int FEATURE_TYPE_SUB_LOCALITY = 6;
  static final int FEATURE_TYPE_PREMISES = 7;
  static final int FEATURE_TYPE_THOROUGHFARE = 8;
  static final int FEATURE_TYPE_SUB_THOROUGHFARE = 9;
  static final int FEATURE_TYPE_POST_CODE = 10;    
}

