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

public interface ResponseCodes {
  static final int STATUS_STATUS_SUCCESS = 0;
  static final int STATUS_STATUS_FAILED = 1;
  static final int STATUS_AUTHORIZATION_REJECTED = 2;
  static final int STATUS_NO_SOURCE_EXISTS = 3;
  static final int STATUS_SIGNAL_TOO_WEAK = 4;
  static final int STATUS_INVALID_REQUEST = 5;
  static final int STATUS_INVALID_NUM_REQUESTS = 6;
  static final int STATUS_INVALID_USERLOCATION_FORMAT = 7;
  static final int STATUS_INVALID_OPERATION_CODE = 8;
  static final int STATUS_INVALID_MAC_STRING_FORMAT = 9;
  static final int STATUS_INVALID_CELLID_STRING_FORMAT = 10;
  static final int STATUS_NON_EXISTENT_AP = 11;
  static final int STATUS_NON_EXISTENT_CELLID = 12;
  static final int STATUS_STATUS_FAILED_NO_SOURCE = 13;
  static final int STATUS_STATUS_FAILED_NO_SAVE = 14;
  static final int STATUS_PLATFORM_KEY_EXPIRED = 15;
  static final int STATUS_NO_STORE_EXISTS = 16;
  static final int STATUS_NO_CELLIDDATA_FOR_UPDATE = 17;
  static final int STATUS_NON_SUPPORTED_OPERATION_IN_UPDATE = 18;
  static final int STATUS_NON_SUPPORTED_OPERATION = 19;
  static final int STATUS_STATUS_FAILED_NO_GEOCODE = 20;
  static final int STATUS_BLACKLISTED_IP_CELLID = 100;
  static final int STATUS_BLACKLISTED_IP_WIFI = 101;

}

