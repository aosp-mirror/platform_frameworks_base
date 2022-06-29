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

using namespace android::uirenderer::skiapipeline;

namespace android {
namespace uirenderer {
namespace skiapipeline {

class ShaderCacheTestUtils {
public:
    /**
     * "setSaveDelay" sets the time in seconds to wait before saving newly inserted cache entries.
     * If set to 0, then deferred save is disabled.
     */
    static void setSaveDelay(ShaderCache& cache, unsigned int saveDelay) {
        cache.mDeferredSaveDelay = saveDelay;
    }

    /**
     * "terminate" optionally stores the BlobCache on disk and release all in-memory cache.
     * Next call to "initShaderDiskCache" will load again the in-memory cache from disk.
     */
    static void terminate(ShaderCache& cache, bool saveContent) {
        std::lock_guard<std::mutex> lock(cache.mMutex);
        cache.mSavePending = saveContent;
        cache.saveToDiskLocked();
        cache.mBlobCache = NULL;
    }

    /**
     *
     */
    template <typename T>
    static bool validateCache(ShaderCache& cache, std::vector<T> hash) {
        return cache.validateCache(hash.data(), hash.size() * sizeof(T));
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

inline bool checkShader(const sk_sp<SkData>& shader1, const sk_sp<SkData>& shader2) {
    return nullptr != shader1 && nullptr != shader2 && shader1->size() == shader2->size() &&
           0 == memcmp(shader1->data(), shader2->data(), shader1->size());
}

inline bool checkShader(const sk_sp<SkData>& shader, const char* program) {
    sk_sp<SkData> shader2 = SkData::MakeWithCString(program);
    return checkShader(shader, shader2);
}

template <typename T>
bool checkShader(const sk_sp<SkData>& shader, std::vector<T>& program) {
    sk_sp<SkData> shader2 = SkData::MakeWithCopy(program.data(), program.size() * sizeof(T));
    return checkShader(shader, shader2);
}

void setShader(sk_sp<SkData>& shader, const char* program) {
    shader = SkData::MakeWithCString(program);
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
    int deleteFile = remove(cacheFile1.c_str());
    ASSERT_TRUE(0 == deleteFile || ENOENT == errno);
    std::srand(0);

    // read the cache from a file that does not exist
    ShaderCache::get().setFilename(cacheFile1.c_str());
    ShaderCacheTestUtils::setSaveDelay(ShaderCache::get(), 0);  // disable deferred save
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
    remove(cacheFile1.c_str());
}

TEST(ShaderCacheTest, testCacheValidation) {
    if (!folderExist(getExternalStorageFolder())) {
        // don't run the test if external storage folder is not available
        return;
    }
    std::string cacheFile1 = getExternalStorageFolder() + "/shaderCacheTest1";
    std::string cacheFile2 = getExternalStorageFolder() + "/shaderCacheTest2";

    // remove any test files from previous test run
    int deleteFile = remove(cacheFile1.c_str());
    ASSERT_TRUE(0 == deleteFile || ENOENT == errno);
    std::srand(0);

    // generate identity and read the cache from a file that does not exist
    ShaderCache::get().setFilename(cacheFile1.c_str());
    ShaderCacheTestUtils::setSaveDelay(ShaderCache::get(), 0);  // disable deferred save
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
    remove(cacheFile1.c_str());
}

}  // namespace
