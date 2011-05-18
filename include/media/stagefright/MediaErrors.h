/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef MEDIA_ERRORS_H_

#define MEDIA_ERRORS_H_

#include <utils/Errors.h>

namespace android {

enum {
    MEDIA_ERROR_BASE        = -1000,

    ERROR_ALREADY_CONNECTED = MEDIA_ERROR_BASE,
    ERROR_NOT_CONNECTED     = MEDIA_ERROR_BASE - 1,
    ERROR_UNKNOWN_HOST      = MEDIA_ERROR_BASE - 2,
    ERROR_CANNOT_CONNECT    = MEDIA_ERROR_BASE - 3,
    ERROR_IO                = MEDIA_ERROR_BASE - 4,
    ERROR_CONNECTION_LOST   = MEDIA_ERROR_BASE - 5,
    ERROR_MALFORMED         = MEDIA_ERROR_BASE - 7,
    ERROR_OUT_OF_RANGE      = MEDIA_ERROR_BASE - 8,
    ERROR_BUFFER_TOO_SMALL  = MEDIA_ERROR_BASE - 9,
    ERROR_UNSUPPORTED       = MEDIA_ERROR_BASE - 10,
    ERROR_END_OF_STREAM     = MEDIA_ERROR_BASE - 11,

    // Not technically an error.
    INFO_FORMAT_CHANGED    = MEDIA_ERROR_BASE - 12,
    INFO_DISCONTINUITY     = MEDIA_ERROR_BASE - 13,

    // The following constant values should be in sync with
    // drm/drm_framework_common.h
    DRM_ERROR_BASE = -2000,

    ERROR_DRM_UNKNOWN                       = DRM_ERROR_BASE,
    ERROR_DRM_NO_LICENSE                    = DRM_ERROR_BASE - 1,
    ERROR_DRM_LICENSE_EXPIRED               = DRM_ERROR_BASE - 2,
    ERROR_DRM_SESSION_NOT_OPENED            = DRM_ERROR_BASE - 3,
    ERROR_DRM_DECRYPT_UNIT_NOT_INITIALIZED  = DRM_ERROR_BASE - 4,
    ERROR_DRM_DECRYPT                       = DRM_ERROR_BASE - 5,
    ERROR_DRM_CANNOT_HANDLE                 = DRM_ERROR_BASE - 6,
    ERROR_DRM_TAMPER_DETECTED               = DRM_ERROR_BASE - 7,

    // Heartbeat Error Codes
    HEARTBEAT_ERROR_BASE = -3000,

    ERROR_HEARTBEAT_AUTHENTICATION_FAILURE                  = HEARTBEAT_ERROR_BASE,
    ERROR_HEARTBEAT_NO_ACTIVE_PURCHASE_AGREEMENT            = HEARTBEAT_ERROR_BASE - 1,
    ERROR_HEARTBEAT_CONCURRENT_PLAYBACK                     = HEARTBEAT_ERROR_BASE - 2,
    ERROR_HEARTBEAT_UNUSUAL_ACTIVITY                        = HEARTBEAT_ERROR_BASE - 3,
    ERROR_HEARTBEAT_STREAMING_UNAVAILABLE                   = HEARTBEAT_ERROR_BASE - 4,
    ERROR_HEARTBEAT_CANNOT_ACTIVATE_RENTAL                  = HEARTBEAT_ERROR_BASE - 5,
    ERROR_HEARTBEAT_TERMINATE_REQUESTED                     = HEARTBEAT_ERROR_BASE - 6,
};

}  // namespace android

#endif  // MEDIA_ERRORS_H_
