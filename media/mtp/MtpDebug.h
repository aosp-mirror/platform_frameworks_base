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

#ifndef _MTP_DEBUG_H
#define _MTP_DEBUG_H

// #define LOG_NDEBUG 0
#include <utils/Log.h>

#include "MtpTypes.h"

namespace android {

class MtpDebug {
public:
    static const char* getOperationCodeName(MtpOperationCode code);
    static const char* getFormatCodeName(MtpObjectFormat code);
    static const char* getObjectPropCodeName(MtpPropertyCode code);
    static const char* getDevicePropCodeName(MtpPropertyCode code);
};

}; // namespace android

#endif // _MTP_DEBUG_H
