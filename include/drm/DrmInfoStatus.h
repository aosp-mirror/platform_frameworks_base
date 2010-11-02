/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef __DRM_INFO_STATUS_H__
#define __DRM_INFO_STATUS_H__

#include "drm_framework_common.h"

namespace android {

/**
 * This is an utility class which wraps the result of communication between device
 * and online DRM server.
 *
 * As a result of DrmManagerClient::processDrmInfo(const DrmInfo*) an instance of
 * DrmInfoStatus would be returned. This class holds DrmBuffer which could be
 * used to instantiate DrmRights in license acquisition.
 *
 */
class DrmInfoStatus {
public:
    // Should be in sync with DrmInfoStatus.java
    static const int STATUS_OK = 1;
    static const int STATUS_ERROR = 2;

public:
    /**
     * Constructor for DrmInfoStatus
     *
     * @param[in] _statusCode Status of the communication
     * @param[in] _infoType Type of the DRM information processed
     * @param[in] _drmBuffer Rights information
     * @param[in] _mimeType MIME type
     */
    DrmInfoStatus(int _statusCode, int _infoType, const DrmBuffer* _drmBuffer, const String8& _mimeType);

    /**
     * Destructor for DrmInfoStatus
     */
    virtual ~DrmInfoStatus() {

    }

public:
    int statusCode;
    int infoType;
    const DrmBuffer* drmBuffer;
    String8 mimeType;
};

};

#endif /* __DRM_INFO_STATUS_H__ */

