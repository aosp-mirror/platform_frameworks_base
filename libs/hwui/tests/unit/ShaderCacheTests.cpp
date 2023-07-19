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

#include <GrDirectContext.h>
#include <Properties.h>
#include <SkData.h>
#include <SkRefCnt.h>
#include <cutils/properties.h>
#include <dirent.h>
#include <errno.h>
#include <gtest/gtest.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <utils/Log.h>

#include <cstdint>

#include "FileBlobCache.h"
#include "pipeline/skia/ShaderCache.h"
#include "tests/common/TestUtils.h"

using namespace android::uirenderer::skiapipeline;

namespace android {
namespace uirenderer {
namespace skiapipeline {

class ShaderCacheTestUtils {
public:
    /**
     * Hack to reset all member variables of the given cache to their default / initial values.
     *
     * WARNING: this must be kept up to date manually, since ShaderCache's parent disables just
     * reassigning a new instance.
     */
    static void reinitializeAllFields(ShaderCache& cache) {
        ShaderCache newCache = ShaderCache();
        std::lock_guard lock(cache.mMutex), newLock(newCache.mMutex);
        // By order of declaration
        cache.mInitialized = newCache.mInitialized;
        cache.mBlobCache.reset(nullptr);
        cache.mFilename = newCache.mFilename;
        cache.mIDHash.clear();
        cache.mSavePending = newCache.mSavePending;
        cache.mObservedBlobValueSize = newCache.mObservedBlobValueSize;
        cache.mDeferredSaveDelayMs = newCache.mDeferredSaveDelayMs;
        cache.mTryToStorePipelineCache = newCache.mTryToStorePipelineCache;
        cache.mInStoreVkPipelineInProgress = newCache.mInStoreVkPipelineInProgress;
        cache.mNewPipelineCacheSize = newCache.mNewPipelineCacheSize;
        cache.mOldPipelineCacheSize = newCache.mOldPipelineCacheSize;
        cache.mCacheDirty = newCache.mCacheDirty;
        cache.mNumShadersCachedInRam = newCache.mNumShadersCachedInRam;
    }

    /**
     * "setSaveDelayMs" sets the time in milliseconds to wait before saving newly inserted cache
     * entries. If set to 0, then deferred save is disabled, and "saveToDiskLocked" must be called
     * manually, as seen in the "terminate" testing helper function.
     */
    static void setSaveDelayMs(ShaderCache& cache, unsigned int saveDelayMs) {
        std::lock_guard lock(cache.mMutex);
        cache.mDeferredSaveDelayMs = saveDelayMs;
    }

    /**
     * "terminate" optionally stores the BlobCache on disk and release all in-memory cache.
     * Next call to "initShaderDiskCache" will load again the in-memory cache from disk.
     */
    static void terminate(ShaderCache& cache, bool saveContent) {
        std::lock_guard lock(cache.mMutex);
        if (saveContent) {
            cache.saveToDiskLocked();
        }
        cache.mBlobCache = NULL;
    }

    /**
     *
     */
    template <typename T>
    static bool validateCache(ShaderCache& cache, std::vector<T> hash) {
        std::lock_guard lock(cache.mMutex);
        return cache.validateCache(hash.data(), hash.size() * sizeof(T));
    }

    /**
     * Waits until cache::mSavePending is false, checking every 0.1 ms *while the mutex is free*.
     *
     * Fails if there was no save pending, or if the cache was already being written to disk, or if
     * timeoutMs is exceeded.
     *
     * Note: timeoutMs only guards against mSavePending getting stuck like in b/268205519, and
     * cannot protect against mutex-based deadlock. Reaching timeoutMs implies something is broken,
     * so setting it to a sufficiently large value will not delay execution in the happy state.
     */
    static void waitForPendingSave(ShaderCache& cache, const int timeoutMs = 50) {
        {
            std::lock_guard lock(cache.mMutex);
            ASSERT_TRUE(cache.mSavePending);
        }
        bool saving = true;
        float elapsedMilliseconds = 0;
        while (saving) {
            if (elapsedMilliseconds >= timeoutMs) {
                FAIL() << "Timed out after waiting " << timeoutMs << " ms for a pending save";
            }
            // This small (0.1 ms) delay is to avoid working too much while waiting for
            // deferredSaveThread to take the mutex and start the disk write.
            const int delayMicroseconds = 100;
            usleep(delayMicroseconds);
            elapsedMilliseconds += (float)delayMicroseconds / 1000;

            std::lock_guard lock(cache.mMutex);
            saving = cache.mSavePending;
        }
    }
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */

namespace {

std::string getExternalStorageFolder() {
    return getenv("EXTERNAL_STORAGE");
}

bool folderExist(const std::string& folderName) {
    DIR* dir = opendir(folderName.c_str());
    if (dir) {
        closedir(dir);
        return true;
    }
    return false;
}

/**
 * Attempts to delete the given file, and asserts that either:
 * 1. Deletion was successful, OR
 * 2. The file did not exist.
 *
 * Tip: wrap calls to this in ASSERT_NO_FATAL_FAILURE(x) if a test should exit early if this fails.
 */
void deleteFileAssertSuccess(const std::string& filePath) {
    int deleteResult = remove(filePath.c_str());
    ASSERT_TRUE(0 == deleteResult || ENOENT == errno);
}

inline bool checkShader(const sk_sp<SkData>& shader1, const sk_sp<SkData>& shader2) {
    return nullptr != shader1 && nullptr != shader2 && shader1->size() == shader2->size() &&
           0 == memcmp(shader1->data(), shader2->data(), shader1->size());
}

inline bool checkShader(const sk_sp<SkData>& shader, const char* program) {
    sk_sp<SkData> shader2 = SkData::MakeWithCString(program);
    return checkShader(shader, shader2);
}

inline bool checkShader(const sk_sp<SkData>& shader, const std::string& program) {
    return checkShader(shader, program.c_str());
}

template <typename T>
bool checkShader(const sk_sp<SkData>& shader, std::vector<T>& program) {
    sk_sp<SkData> shader2 = SkData::MakeWithCopy(program.data(), program.size() * sizeof(T));
    return checkShader(shader, shader2);
}

void setShader(sk_sp<SkData>& shader, const char* program) {
    shader = SkData::MakeWithCString(program);
}

void setShader(sk_sp<SkData>& shader, const std::string& program) {
    setShader(shader, program.c_str());
}

template <typename T>
void setShader(sk_sp<SkData>& shader, std::vector<T>& buffer) {
    shader = SkData::MakeWithCopy(buffer.data(), buffer.size() * sizeof(T));
}

template <typename T>
void genRandomData(std::vector<T>& buffer) {
    for (auto& data : buffer) {
        data = T(std::rand());
    }
}

#define GrProgramDescTest(a) (*SkData::MakeWithCString(#a).get())

TEST(ShaderCacheTest, testWriteAndRead) {
    if (!folderExist(getExternalStorageFolder())) {
        // don't run the test if external storage folder is not available
        return;
    }
    std::string cacheFile1 = getExternalStorageFolder() + "/shaderCacheTest1";
    std::string cacheFile2 = getExternalStorageFolder() + "/shaderCacheTest2";

    // remove any test files from previous test run
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile1));
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile2));
    std::srand(0);

    // read the cache from a file that does not exist
    ShaderCache::get().setFilename(cacheFile1.c_str());
    ShaderCacheTestUtils::setSaveDelayMs(ShaderCache::get(), 0);  // disable deferred save
    ShaderCache::get().initShaderDiskCache();

    // read a key - should not be found since the cache is empty
    sk_sp<SkData> outVS;
    ASSERT_EQ(ShaderCache::get().load(GrProgramDescTest(432)), sk_sp<SkData>());

    // write to the in-memory cache without storing on disk and verify we read the same values
    sk_sp<SkData> inVS;
    setShader(inVS, "sassas");
    ShaderCache::get().store(GrProgramDescTest(100), *inVS.get(), SkString());
    setShader(inVS, "someVS");
    ShaderCache::get().store(GrProgramDescTest(432), *inVS.get(), SkString());
    ASSERT_NE((outVS = ShaderCache::get().load(GrProgramDescTest(100))), sk_sp<SkData>());
    ASSERT_TRUE(checkShader(outVS, "sassas"));
    ASSERT_NE((outVS = ShaderCache::get().load(GrProgramDescTest(432))), sk_sp<SkData>());
    ASSERT_TRUE(checkShader(outVS, "someVS"));

    // store content to disk and release in-memory cache
    ShaderCacheTestUtils::terminate(ShaderCache::get(), true);

    // change to a file that does not exist and verify load fails
    ShaderCache::get().setFilename(cacheFile2.c_str());
    ShaderCache::get().initShaderDiskCache();
    ASSERT_EQ(ShaderCache::get().load(GrProgramDescTest(432)), sk_sp<SkData>());
    ShaderCacheTestUtils::terminate(ShaderCache::get(), false);

    // load again content from disk from an existing file and check the data is read correctly
    ShaderCache::get().setFilename(cacheFile1.c_str());
    ShaderCache::get().initShaderDiskCache();
    sk_sp<SkData> outVS2;
    ASSERT_NE((outVS2 = ShaderCache::get().load(GrProgramDescTest(432))), sk_sp<SkData>());
    ASSERT_TRUE(checkShader(outVS2, "someVS"));

    // change data, store to disk, read back again and verify data has been changed
    setShader(inVS, "ewData1");
    ShaderCache::get().store(GrProgramDescTest(432), *inVS.get(), SkString());
    ShaderCacheTestUtils::terminate(ShaderCache::get(), true);
    ShaderCache::get().initShaderDiskCache();
    ASSERT_NE((outVS2 = ShaderCache::get().load(GrProgramDescTest(432))), sk_sp<SkData>());
    ASSERT_TRUE(checkShader(outVS2, "ewData1"));

    // write and read big data chunk (50K)
    size_t dataSize = 50 * 1024;
    std::vector<uint8_t> dataBuffer(dataSize);
    genRandomData(dataBuffer);
    setShader(inVS, dataBuffer);
    ShaderCache::get().store(GrProgramDescTest(432), *inVS.get(), SkString());
    ShaderCacheTestUtils::terminate(ShaderCache::get(), true);
    ShaderCache::get().initShaderDiskCache();
    ASSERT_NE((outVS2 = ShaderCache::get().load(GrProgramDescTest(432))), sk_sp<SkData>());
    ASSERT_TRUE(checkShader(outVS2, dataBuffer));

    ShaderCacheTestUtils::terminate(ShaderCache::get(), false);
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile1));
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile2));
}

TEST(ShaderCacheTest, testCacheValidation) {
    if (!folderExist(getExternalStorageFolder())) {
        // don't run the test if external storage folder is not available
        return;
    }
    std::string cacheFile1 = getExternalStorageFolder() + "/shaderCacheTest1";
    std::string cacheFile2 = getExternalStorageFolder() + "/shaderCacheTest2";

    // remove any test files from previous test run
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile1));
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile2));
    std::srand(0);

    // generate identity and read the cache from a file that does not exist
    ShaderCache::get().setFilename(cacheFile1.c_str());
    ShaderCacheTestUtils::setSaveDelayMs(ShaderCache::get(), 0);  // disable deferred save
    std::vector<uint8_t> identity(1024);
    genRandomData(identity);
    ShaderCache::get().initShaderDiskCache(
            identity.data(), identity.size() * sizeof(decltype(identity)::value_type));

    // generate random content in cache and store to disk
    constexpr size_t numBlob(10);
    constexpr size_t keySize(1024);
    constexpr size_t dataSize(50 * 1024);

    std::vector<std::pair<sk_sp<SkData>, sk_sp<SkData>>> blobVec(numBlob);
    for (auto& blob : blobVec) {
        std::vector<uint8_t> keyBuffer(keySize);
        std::vector<uint8_t> dataBuffer(dataSize);
        genRandomData(keyBuffer);
        genRandomData(dataBuffer);

        sk_sp<SkData> key, data;
        setShader(key, keyBuffer);
        setShader(data, dataBuffer);

        blob = std::make_pair(key, data);
        ShaderCache::get().store(*key.get(), *data.get(), SkString());
    }
    ShaderCacheTestUtils::terminate(ShaderCache::get(), true);

    // change to a file that does not exist and verify validation fails
    ShaderCache::get().setFilename(cacheFile2.c_str());
    ShaderCache::get().initShaderDiskCache();
    ASSERT_FALSE(ShaderCacheTestUtils::validateCache(ShaderCache::get(), identity));
    ShaderCacheTestUtils::terminate(ShaderCache::get(), false);

    // restore the original file and verify validation succeeds
    ShaderCache::get().setFilename(cacheFile1.c_str());
    ShaderCache::get().initShaderDiskCache(
            identity.data(), identity.size() * sizeof(decltype(identity)::value_type));
    ASSERT_TRUE(ShaderCacheTestUtils::validateCache(ShaderCache::get(), identity));
    for (const auto& blob : blobVec) {
        auto outVS = ShaderCache::get().load(*blob.first.get());
        ASSERT_TRUE(checkShader(outVS, blob.second));
    }

    // generate error identity and verify load fails
    ShaderCache::get().initShaderDiskCache(identity.data(), -1);
    for (const auto& blob : blobVec) {
        ASSERT_EQ(ShaderCache::get().load(*blob.first.get()), sk_sp<SkData>());
    }
    ShaderCache::get().initShaderDiskCache(
            nullptr, identity.size() * sizeof(decltype(identity)::value_type));
    for (const auto& blob : blobVec) {
        ASSERT_EQ(ShaderCache::get().load(*blob.first.get()), sk_sp<SkData>());
    }

    // verify the cache validation again after load fails
    ShaderCache::get().initShaderDiskCache(
            identity.data(), identity.size() * sizeof(decltype(identity)::value_type));
    ASSERT_TRUE(ShaderCacheTestUtils::validateCache(ShaderCache::get(), identity));
    for (const auto& blob : blobVec) {
        auto outVS = ShaderCache::get().load(*blob.first.get());
        ASSERT_TRUE(checkShader(outVS, blob.second));
    }

    // generate another identity and verify load fails
    for (auto& data : identity) {
        data += std::rand();
    }
    ShaderCache::get().initShaderDiskCache(
            identity.data(), identity.size() * sizeof(decltype(identity)::value_type));
    for (const auto& blob : blobVec) {
        ASSERT_EQ(ShaderCache::get().load(*blob.first.get()), sk_sp<SkData>());
    }

    ShaderCacheTestUtils::terminate(ShaderCache::get(), false);
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile1));
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile2));
}

using namespace android::uirenderer;
RENDERTHREAD_TEST(ShaderCacheTest, testOnVkFrameFlushed) {
    if (Properties::getRenderPipelineType() != RenderPipelineType::SkiaVulkan) {
        // RENDERTHREAD_TEST declares both SkiaVK and SkiaGL variants.
        GTEST_SKIP() << "This test is only applicable to RenderPipelineType::SkiaVulkan";
    }
    if (!folderExist(getExternalStorageFolder())) {
        // Don't run the test if external storage folder is not available
        return;
    }
    std::string cacheFile = getExternalStorageFolder() + "/shaderCacheTest";
    GrDirectContext* grContext = renderThread.getGrContext();

    // Remove any test files from previous test run
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile));

    // The first iteration of this loop is to save an initial VkPipelineCache data blob to disk,
    // which sets up the second iteration for a common scenario of comparing a "new" VkPipelineCache
    // blob passed to "store" against the same blob that's already in the persistent cache from a
    // previous launch. "reinitializeAllFields" is critical to emulate each iteration being as close
    // to the state of a freshly launched app as possible, as the initial values of member variables
    // like mInStoreVkPipelineInProgress and mOldPipelineCacheSize are critical to catch issues
    // such as b/268205519
    for (int flushIteration = 1; flushIteration <= 2; flushIteration++) {
        SCOPED_TRACE("Frame flush iteration " + std::to_string(flushIteration));
        // Reset *all* in-memory data and reload the cache from disk.
        ShaderCacheTestUtils::reinitializeAllFields(ShaderCache::get());
        ShaderCacheTestUtils::setSaveDelayMs(ShaderCache::get(), 10);  // Delay must be > 0 to save.
        ShaderCache::get().setFilename(cacheFile.c_str());
        ShaderCache::get().initShaderDiskCache();

        // 1st iteration: store pipeline data to be read back on a subsequent "boot" of the "app".
        // 2nd iteration: ensure that an initial frame flush (without storing any shaders) given the
        // same pipeline data that's already on disk doesn't break the cache.
        ShaderCache::get().onVkFrameFlushed(grContext);
        ASSERT_NO_FATAL_FAILURE(ShaderCacheTestUtils::waitForPendingSave(ShaderCache::get()));
    }

    constexpr char shader1[] = "sassas";
    constexpr char shader2[] = "someVS";
    constexpr int numIterations = 3;
    // Also do n iterations of separate "store some shaders then flush the frame" pairs to just
    // double-check the cache also doesn't get stuck from that use case.
    for (int saveIteration = 1; saveIteration <= numIterations; saveIteration++) {
        SCOPED_TRACE("Shader save iteration " + std::to_string(saveIteration));
        // Write twice to the in-memory cache, which should start a deferred save with both queued.
        sk_sp<SkData> inVS;
        setShader(inVS, shader1 + std::to_string(saveIteration));
        ShaderCache::get().store(GrProgramDescTest(100), *inVS.get(), SkString());
        setShader(inVS, shader2 + std::to_string(saveIteration));
        ShaderCache::get().store(GrProgramDescTest(432), *inVS.get(), SkString());

        // Simulate flush to also save latest pipeline info.
        ShaderCache::get().onVkFrameFlushed(grContext);
        ASSERT_NO_FATAL_FAILURE(ShaderCacheTestUtils::waitForPendingSave(ShaderCache::get()));
    }

    // Reload from disk to ensure saving succeeded.
    ShaderCacheTestUtils::terminate(ShaderCache::get(), false);
    ShaderCache::get().initShaderDiskCache();

    // Read twice, ensure equal to last store.
    sk_sp<SkData> outVS;
    ASSERT_NE((outVS = ShaderCache::get().load(GrProgramDescTest(100))), sk_sp<SkData>());
    ASSERT_TRUE(checkShader(outVS, shader1 + std::to_string(numIterations)));
    ASSERT_NE((outVS = ShaderCache::get().load(GrProgramDescTest(432))), sk_sp<SkData>());
    ASSERT_TRUE(checkShader(outVS, shader2 + std::to_string(numIterations)));

    // Clean up.
    ShaderCacheTestUtils::terminate(ShaderCache::get(), false);
    ASSERT_NO_FATAL_FAILURE(deleteFileAssertSuccess(cacheFile));
}

}  // namespace
