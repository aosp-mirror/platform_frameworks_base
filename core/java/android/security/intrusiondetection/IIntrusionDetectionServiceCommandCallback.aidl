/*
 * Copyright 2024 The Android Open Source Project
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

package android.security.intrusiondetection;

/**
 * @hide
 */
 oneway interface IIntrusionDetectionServiceCommandCallback {
     @Backing(type="int")
     enum ErrorCode{
         UNKNOWN = 0,
         PERMISSION_DENIED = 1,
         INVALID_STATE_TRANSITION = 2,
         TRANSPORT_UNAVAILABLE = 3,
         DATA_SOURCE_UNAVAILABLE = 4,
     }
    void onSuccess();
    void onFailure(ErrorCode error);
 }
