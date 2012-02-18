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

#define LOG_TAG "NObb"

#include <android/obb.h>

#include <androidfw/ObbFile.h>
#include <utils/Log.h>

using namespace android;

struct AObbInfo : public ObbFile {};

AObbInfo* AObbScanner_getObbInfo(const char* filename) {
    AObbInfo* obbFile = new AObbInfo();
    if (obbFile == NULL || !obbFile->readFrom(filename)) {
        delete obbFile;
        return NULL;
    }
    obbFile->incStrong((void*)AObbScanner_getObbInfo);
    return static_cast<AObbInfo*>(obbFile);
}

void AObbInfo_delete(AObbInfo* obbInfo) {
    if (obbInfo != NULL) {
        obbInfo->decStrong((void*)AObbScanner_getObbInfo);
    }
}

const char* AObbInfo_getPackageName(AObbInfo* obbInfo) {
    return obbInfo->getPackageName();
}

int32_t AObbInfo_getVersion(AObbInfo* obbInfo) {
    return obbInfo->getVersion();
}

int32_t AObbInfo_getFlags(AObbInfo* obbInfo) {
    return obbInfo->getFlags();
}
