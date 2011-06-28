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

#define LOG_TAG "WVMExtractor"
#include <utils/Log.h>

#include "include/WVMExtractor.h"

#include <arpa/inet.h>
#include <utils/String8.h>
#include <media/stagefright/Utils.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>
#include <dlfcn.h>

#include <utils/Errors.h>

namespace android {

Mutex WVMExtractor::sMutex;
uint32_t WVMExtractor::sActiveExtractors = 0;
void *WVMExtractor::sVendorLibHandle = NULL;

WVMExtractor::WVMExtractor(const sp<DataSource> &source)
    : mDataSource(source) {
    {
        Mutex::Autolock autoLock(sMutex);

        if (sVendorLibHandle == NULL) {
            CHECK(sActiveExtractors == 0);
            sVendorLibHandle = dlopen("libwvm.so", RTLD_NOW);
        }

        sActiveExtractors++;

        if (sVendorLibHandle == NULL) {
            LOGE("Failed to open libwvm.so");
            return;
        }
    }

    typedef WVMLoadableExtractor *(*GetInstanceFunc)(sp<DataSource>);
    GetInstanceFunc getInstanceFunc =
        (GetInstanceFunc) dlsym(sVendorLibHandle,
                "_ZN7android11GetInstanceENS_2spINS_10DataSourceEEE");

    if (getInstanceFunc) {
        mImpl = (*getInstanceFunc)(source);
        CHECK(mImpl != NULL);
    } else {
        LOGE("Failed to locate GetInstance in libwvm.so");
    }
}

WVMExtractor::~WVMExtractor() {
    Mutex::Autolock autoLock(sMutex);

    CHECK(sActiveExtractors > 0);
    sActiveExtractors--;

    // Close lib after last use
    if (sActiveExtractors == 0) {
        if (sVendorLibHandle != NULL)
            dlclose(sVendorLibHandle);
        sVendorLibHandle = NULL;
    }
}

size_t WVMExtractor::countTracks() {
    return (mImpl != NULL) ? mImpl->countTracks() : 0;
}

sp<MediaSource> WVMExtractor::getTrack(size_t index) {
    if (mImpl == NULL) {
        return NULL;
    }
    return mImpl->getTrack(index);
}

sp<MetaData> WVMExtractor::getTrackMetaData(size_t index, uint32_t flags) {
    if (mImpl == NULL) {
        return NULL;
    }
    return mImpl->getTrackMetaData(index, flags);
}

sp<MetaData> WVMExtractor::getMetaData() {
    if (mImpl == NULL) {
        return NULL;
    }
    return mImpl->getMetaData();
}

int64_t WVMExtractor::getCachedDurationUs(status_t *finalStatus) {
    if (mImpl == NULL) {
        return 0;
    }

    return mImpl->getCachedDurationUs(finalStatus);
}

void WVMExtractor::setAdaptiveStreamingMode(bool adaptive) {
    if (mImpl != NULL) {
        mImpl->setAdaptiveStreamingMode(adaptive);
    }
}

} //namespace android

