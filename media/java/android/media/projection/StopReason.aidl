/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.projection;

/**
 * Identifies the reason for a MediaProjection being stopped (for metric logging purposes)
 * @hide
 */
@Backing(type="int")
enum StopReason {
    STOP_UNKNOWN = 0,
    STOP_HOST_APP = 1,
    STOP_TARGET_REMOVED = 2,
    STOP_DEVICE_LOCKED = 3,
    STOP_PRIVACY_CHIP = 4,
    STOP_QS_TILE = 5,
    STOP_USER_SWITCH = 6,
    STOP_FOREGROUND_SERVICE_CHANGE = 7,
    STOP_NEW_PROJECTION = 8,
    STOP_NEW_MEDIA_ROUTE = 9,
    STOP_ERROR = 10,
}
