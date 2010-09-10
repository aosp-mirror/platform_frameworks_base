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

#ifndef __DRM_CONVERTED_STATUS_H__
#define __DRM_CONVERTED_STATUS_H__

#include "drm_framework_common.h"

namespace android {

/**
 * This is an utility class which wraps the status of the conversion, the converted
 * data/checksum data and the offset. Offset is going to be used in the case of close
 * session where the agent will inform where the header and body signature should be added
 *
 * As a result of DrmManagerClient::convertData(int, const DrmBuffer*) and
 * DrmManagerClient::closeConvertSession(int) an instance of DrmConvertedStatus
 * would be returned.
 *
 */
class DrmConvertedStatus {
public:
    // Should be in sync with DrmConvertedStatus.java
    static const int STATUS_OK = 1;
    static const int STATUS_INPUTDATA_ERROR = 2;
    static const int STATUS_ERROR = 3;

public:
    /**
     * Constructor for DrmConvertedStatus
     *
     * @param[in] _statusCode Status of the conversion
     * @param[in] _convertedData Converted data/checksum data
     * @param[in] _offset Offset value
     */
    DrmConvertedStatus(int _statusCode, const DrmBuffer* _convertedData, int _offset);

    /**
     * Destructor for DrmConvertedStatus
     */
    virtual ~DrmConvertedStatus() {

    }

public:
    int statusCode;
    const DrmBuffer* convertedData;
    int offset;
};

};

#endif /* __DRM_CONVERTED_STATUS_H__ */

