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

//#define LOG_NDEBUG 0
#define LOG_TAG "DrmIOService"
#include <utils/Log.h>

#include <binder/IServiceManager.h>
#include "DrmIOService.h"
#include "ReadWriteUtils.h"

using namespace android;

void DrmIOService::instantiate() {
    LOGV("instantiate");
    defaultServiceManager()->addService(String16("drm.drmIOService"), new DrmIOService());
}

DrmIOService::DrmIOService() {
    LOGV("created");
}

DrmIOService::~DrmIOService() {
    LOGV("Destroyed");
}

void DrmIOService::writeToFile(const String8& filePath, const String8& dataBuffer) {
    LOGV("Entering writeToFile");
    ReadWriteUtils::writeToFile(filePath, dataBuffer);
}

String8 DrmIOService::readFromFile(const String8& filePath) {
    LOGV("Entering readFromFile");
    return ReadWriteUtils::readBytes(filePath);
}

