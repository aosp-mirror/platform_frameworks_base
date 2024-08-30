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

#include <SkData.h>
#include <gui/TraceUtils.h>
#include <include/gpu/ganesh/GrDirectContext.h>
#include <log/log.h>
#include <openssl/sha.h>

#include <algorithm>
#include <array>
#include <mutex>
#include <thread>

#include "FileBlobCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

// Cache size limits.
static const size_t maxKeySize = 1024;
static const size_t maxValueSize = 2 * 1024 * 1024;
static const size_t maxTotalSize = 4 * 1024 * 1024;
static_assert(maxKeySize + maxValueSize < maxTotalSize);

ShaderCache::ShaderCache() {
    // There is an "incomplete FileBlobCache type" compilation error, if ctor is moved to header.
}

ShaderCache ShaderCache::sCache;

ShaderCache& ShaderCache::get() {
    return sCache;
}

bool ShaderCache::validateCache(const void* identity, ssize_t size) {
    if (nullptr == identity && size == 0) return true;

    if (nullptr == identity || size < 0) {
        if (CC_UNLIKELY(Properties::debugLevel & kDebugCaches)) {
            ALOGW("ShaderCache::validateCache invalid cache identity");
        }
        mBlobCache->clear();
        return false;
    }

    SHA256_CTX ctx;
    SHA256_Init(&ctx);

    SHA256_Update(&ctx, identity, size);
    mIDHash.resize(SHA256_DIGEST_LENGTH);
    SHA256_Final(mIDHash.data(), &ctx);

    std::array<uint8_t, SHA256_DIGEST_LENGTH> hash;
    auto key = sIDKey;
    auto loaded = mBlobCache->get(&key, sizeof(key), hash.data(), hash.size());

    if (loaded && std::equal(hash.begin(), hash.end(), mIDHash.begin())) return true;

    if (CC_UNLIKELY(Properties::debugLevel & kDebugCaches)) {
        ALOGW("ShaderCache::validateCache cache validation fails");
    }
    mBlobCache->clear();
    return false;
}

void ShaderCache::initShaderDiskCache(const void* identity, ssize_t size) {
    ATRACE_NAME("initShaderDiskCache");
    std::lock_guard lock(mMutex);

    // Emulators can switch between different renders either as part of config
    // or snapshot migration. Also, program binaries may not work well on some
    // desktop / laptop GPUs. Thus, disable the shader disk cache for emulator builds.
    if (!Properties::runningInEmulator && mFilename.length() > 0) {
        mBlobCache.reset(new FileBlobCache(maxKeySize, maxValueSize, maxTotalSize, mFilename));
        validateCache(identity, size);
        mInitialized = true;
        if (identity != nullptr && size > 0 && mIDHash.size()) {
            set(&sIDKey, sizeof(sIDKey), mIDHash.data(), mIDHash.size());
        }
    }
}

void ShaderCache::setFilename(const char* filename) {
    std::lock_guard lock(mMutex);
    mFilename = filename;
}

sk_sp<SkData> ShaderCache::load(const SkData& key) {
    ATRACE_NAME("ShaderCache::load");
    size_t keySize = key.size();
    std::lock_guard lock(mMutex);
    if (!mInitialized) {
        return nullptr;
    }

    // mObservedBlobValueSize is reasonably big to avoid memory reallocation
    // Allocate a buffer with malloc. SkData takes ownership of that allocation and will call free.
    void* valueBuffer = malloc(mObservedBlobValueSize);
    if (!valueBuffer) {
        return nullptr;
    }
    size_t valueSize = mBlobCache->get(key.data(), keySize, valueBuffer, mObservedBlobValueSize);
    int maxTries = 3;
    while (valueSize > mObservedBlobValueSize && maxTries > 0) {
        mObservedBlobValueSize = std::min(valueSize, maxValueSize);
        void* newValueBuffer = realloc(valueBuffer, mObservedBlobValueSize);
        if (!newValueBuffer) {
            free(valueBuffer);
            return nullptr;
        }
        valueBuffer = newValueBuffer;
        valueSize = mBlobCache->get(key.data(), keySize, valueBuffer, mObservedBlobValueSize);
        maxTries--;
    }
    if (!valueSize) {
        free(valueBuffer);
        return nullptr;
    }
    if (valueSize > mObservedBlobValueSize) {
        ALOGE("ShaderCache::load value size is too big %d", (int)valueSize);
        free(valueBuffer);
        return nullptr;
    }
    mNumShadersCachedInRam++;
    ATRACE_FORMAT("HWUI RAM cache: %d shaders", mNumShadersCachedInRam);
    return SkData::MakeFromMalloc(valueBuffer, valueSize);
}

void ShaderCache::set(const void* key, size_t keySize, const void* value, size_t valueSize) {
    switch (mBlobCache->set(key, keySize, value, valueSize)) {
        case BlobCache::InsertResult::kInserted:
            // This is what we expect/hope. It means the cache is large enough.
            return;
        case BlobCache::InsertResult::kDidClean: {
            ATRACE_FORMAT("ShaderCache: evicted an entry to fit {key: %lu value %lu}!", keySize,
                          valueSize);
            if (mIDHash.size()) {
                set(&sIDKey, sizeof(sIDKey), mIDHash.data(), mIDHash.size());
            }
            return;
        }
        case BlobCache::InsertResult::kNotEnoughSpace: {
            ATRACE_FORMAT("ShaderCache: could not fit {key: %lu value %lu}!", keySize, valueSize);
            return;
        }
        case BlobCache::InsertResult::kInvalidValueSize:
        case BlobCache::InsertResult::kInvalidKeySize: {
            ATRACE_FORMAT("ShaderCache: invalid size {key: %lu value %lu}!", keySize, valueSize);
            return;
        }
        case BlobCache::InsertResult::kKeyTooBig:
        case BlobCache::InsertResult::kValueTooBig:
        case BlobCache::InsertResult::kCombinedTooBig: {
            ATRACE_FORMAT("ShaderCache: entry too big: {key: %lu value %lu}!", keySize, valueSize);
            return;
        }
    }
}

void ShaderCache::saveToDiskLocked() {
    ATRACE_NAME("ShaderCache::saveToDiskLocked");
    if (mInitialized && mBlobCache) {
        // The most straightforward way to make ownership shared
        mMutex.unlock();
        mMutex.lock_shared();
        mBlobCache->writeToFile();
        mMutex.unlock_shared();
        mMutex.lock();
    }
}

void ShaderCache::store(const SkData& key, const SkData& data, const SkString& /*description*/) {
    ATRACE_NAME("ShaderCache::store");
    std::lock_guard lock(mMutex);
    mNumShadersCachedInRam++;
    ATRACE_FORMAT("HWUI RAM cache: %d shaders", mNumShadersCachedInRam);

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

    if (mInStoreVkPipelineInProgress) {
        if (mOldPipelineCacheSize == -1) {
            // Record the initial pipeline cache size stored in the file.
            mOldPipelineCacheSize = mBlobCache->get(key.data(), keySize, nullptr, 0);
        }
        if (mNewPipelineCacheSize != -1 && mNewPipelineCacheSize == valueSize) {
            // There has not been change in pipeline cache size. Stop trying to save.
            mTryToStorePipelineCache = false;
            return;
        }
        mNewPipelineCacheSize = valueSize;
    } else {
        mCacheDirty = true;
        // If there are new shaders compiled, we probably have new pipeline state too.
        // Store pipeline cache on the next flush.
        mNewPipelineCacheSize = -1;
        mTryToStorePipelineCache = true;
    }
    set(key.data(), keySize, value, valueSize);

    if (!mSavePending && mDeferredSaveDelayMs > 0) {
        mSavePending = true;
        std::thread deferredSaveThread([this]() {
            usleep(mDeferredSaveDelayMs * 1000);  // milliseconds to microseconds
            std::lock_guard lock(mMutex);
            // Store file on disk if there a new shader or Vulkan pipeline cache size changed.
            if (mCacheDirty || mNewPipelineCacheSize != mOldPipelineCacheSize) {
                saveToDiskLocked();
                mOldPipelineCacheSize = mNewPipelineCacheSize;
                mTryToStorePipelineCache = false;
                mCacheDirty = false;
            }
            mSavePending = false;
        });
        deferredSaveThread.detach();
    }
}

void ShaderCache::onVkFrameFlushed(GrDirectContext* context) {
    {
        mMutex.lock_shared();
        if (!mInitialized || !mTryToStorePipelineCache) {
            mMutex.unlock_shared();
            return;
        }
        mMutex.unlock_shared();
    }
    mInStoreVkPipelineInProgress = true;
    context->storeVkPipelineCacheData();
    mInStoreVkPipelineInProgress = false;
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
