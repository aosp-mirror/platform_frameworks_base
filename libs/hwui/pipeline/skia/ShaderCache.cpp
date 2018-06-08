/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "ShaderCache.h"
#include <algorithm>
#include <log/log.h>
#include <thread>
#include "FileBlobCache.h"
#include "Properties.h"
#include "utils/TraceUtils.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

// Cache size limits.
static const size_t maxKeySize = 1024;
static const size_t maxValueSize = 64 * 1024;
static const size_t maxTotalSize = 512 * 1024;

ShaderCache::ShaderCache() {
    // There is an "incomplete FileBlobCache type" compilation error, if ctor is moved to header.
}

ShaderCache ShaderCache::sCache;

ShaderCache& ShaderCache::get() {
    return sCache;
}

void ShaderCache::initShaderDiskCache() {
    ATRACE_NAME("initShaderDiskCache");
    std::lock_guard<std::mutex> lock(mMutex);

    // Emulators can switch between different renders either as part of config
    // or snapshot migration. Also, program binaries may not work well on some
    // desktop / laptop GPUs. Thus, disable the shader disk cache for emulator builds.
    if (!Properties::runningInEmulator && mFilename.length() > 0) {
        mBlobCache.reset(new FileBlobCache(maxKeySize, maxValueSize, maxTotalSize, mFilename));
        mInitialized = true;
    }
}

void ShaderCache::setFilename(const char* filename) {
    std::lock_guard<std::mutex> lock(mMutex);
    mFilename = filename;
}

BlobCache* ShaderCache::getBlobCacheLocked() {
    LOG_ALWAYS_FATAL_IF(!mInitialized, "ShaderCache has not been initialized");
    return mBlobCache.get();
}

sk_sp<SkData> ShaderCache::load(const SkData& key) {
    ATRACE_NAME("ShaderCache::load");
    size_t keySize = key.size();
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mInitialized) {
        return nullptr;
    }

    // mObservedBlobValueSize is reasonably big to avoid memory reallocation
    // Allocate a buffer with malloc. SkData takes ownership of that allocation and will call free.
    void* valueBuffer = malloc(mObservedBlobValueSize);
    if (!valueBuffer) {
        return nullptr;
    }
    BlobCache* bc = getBlobCacheLocked();
    size_t valueSize = bc->get(key.data(), keySize, valueBuffer, mObservedBlobValueSize);
    int maxTries = 3;
    while (valueSize > mObservedBlobValueSize && maxTries > 0) {
        mObservedBlobValueSize = std::min(valueSize, maxValueSize);
        void *newValueBuffer = realloc(valueBuffer, mObservedBlobValueSize);
        if (!newValueBuffer) {
            free(valueBuffer);
            return nullptr;
        }
        valueBuffer = newValueBuffer;
        valueSize = bc->get(key.data(), keySize, valueBuffer, mObservedBlobValueSize);
        maxTries--;
    }
    if (!valueSize) {
        free(valueBuffer);
        return nullptr;
    }
    if (valueSize > mObservedBlobValueSize) {
        ALOGE("ShaderCache::load value size is too big %d", (int) valueSize);
        free(valueBuffer);
        return nullptr;
    }
    return SkData::MakeFromMalloc(valueBuffer, valueSize);
}

void ShaderCache::store(const SkData& key, const SkData& data) {
    ATRACE_NAME("ShaderCache::store");
    std::lock_guard<std::mutex> lock(mMutex);

    if (!mInitialized) {
        return;
    }

    size_t valueSize = data.size();
    size_t keySize = key.size();
    if (keySize == 0 || valueSize == 0 || valueSize >= maxValueSize) {
        ALOGW("ShaderCache::store: sizes %d %d not allowed", (int)keySize, (int)valueSize);
        return;
    }

    const void* value = data.data();

    BlobCache* bc = getBlobCacheLocked();
    bc->set(key.data(), keySize, value, valueSize);

    if (!mSavePending && mDeferredSaveDelay > 0) {
        mSavePending = true;
        std::thread deferredSaveThread([this]() {
            sleep(mDeferredSaveDelay);
            std::lock_guard<std::mutex> lock(mMutex);
            ATRACE_NAME("ShaderCache::saveToDisk");
            if (mInitialized && mBlobCache) {
                mBlobCache->writeToFile();
            }
            mSavePending = false;
        });
        deferredSaveThread.detach();
    }
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
