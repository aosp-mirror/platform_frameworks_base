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

#ifndef __DRM_IO_SERVICE_H__
#define __DRM_IO_SERVICE_H__

#include "IDrmIOService.h"

namespace android {

/**
 * This is the implementation class for DRM IO service.
 *
 * The instance of this class is created while starting the DRM IO service.
 *
 */
class DrmIOService : public BnDrmIOService {
public:
    static void instantiate();

private:
    DrmIOService();
    virtual ~DrmIOService();

public:
    void writeToFile(const String8& filePath, const String8& dataBuffer);
    String8 readFromFile(const String8& filePath);
};

};

#endif /* __DRM_IO_SERVICE_H__ */

