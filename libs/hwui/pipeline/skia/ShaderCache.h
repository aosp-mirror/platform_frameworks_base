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

#pragma once

#include <GrContextOptions.h>
#include <cutils/compiler.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace android {

class BlobCache;
class FileBlobCache;

namespace uirenderer {
namespace skiapipeline {

class ShaderCache : public GrContextOptions::PersistentCache {
public:
    /**
     * "get" returns a pointer to the singleton ShaderCache object.  This
     * singleton object will never be destroyed.
     */
    static ShaderCache& get();

    /**
     * initShaderDiskCache" loads the serialized cache contents from disk,
     * optionally checks that the on-disk cache matches a provided identity,
     * and puts the ShaderCache into an initialized state, such that it is
     * able to insert and retrieve entries from the cache. If identity is
     * non-null and validation fails, the cache is initialized but contains
     * no data. If size is less than zero, the cache is initilaized but
     * contains no data.
     *
     * This should be called when HWUI pipeline is initialized. When not in
     * the initialized state the load and store methods will return without
     * performing any cache operations.
     */
    virtual void initShaderDiskCache(const void* identity, ssize_t size);

    virtual void initShaderDiskCache() { initShaderDiskCache(nullptr, 0); }

    /**
     * "setFilename" sets the name of the file that should be used to store
     * cache contents from one program invocation to another. This function does not perform any
     * disk operation and it should be invoked before "initShaderCache".
     */
    virtual void setFilename(const char* filename);

    /**
     * "load" attempts to retrieve the value blob associated with a given key
     * blob from cache.  This will be called by Skia, when it needs to compile a new SKSL shader.
     */
    sk_sp<SkData> load(const SkData& key) override;

    /**
     * "store" attempts to insert a new key/value blob pair into the cache.
     * This will be called by Skia after it compiled a new SKSL shader
     */
    void store(const SkData& key, const SkData& data, const SkString& description) override;

    /**
     * "onVkFrameFlushed" tries to store Vulkan pipeline cache state.
     * Pipeline cache is saved on disk only if the size of the data has changed or there was
     * a new shader compiled.
     */
    void onVkFrameFlushed(GrDirectContext* context);

private:
    // Creation and (the lack of) destruction is handled internally.
    ShaderCache();

    // Copying is disallowed.
    ShaderCache(const ShaderCache&) = delete;
    void operator=(const ShaderCache&) = delete;

    /**
     * "getBlobCacheLocked" returns the BlobCache object being used to store the
     * key/value blob pairs.  If the BlobCache object has not yet been created,
     * this will do so, loading the serialized cache contents from disk if
     * possible.
     */
    BlobCache* getBlobCacheLocked();

    /**
     * "validateCache" updates the cache to match the given identity.  If the
     * cache currently has the wrong identity, all entries in the cache are cleared.
     */
    bool validateCache(const void* identity, ssize_t size);

    /**
     * "saveToDiskLocked" attemps to save the current contents of the cache to
     * disk. If the identity hash exists, we will insert the identity hash into
     * the cache for next validation.
     */
    void saveToDiskLocked();

    /**
     * "mInitialized" indicates whether the ShaderCache is in the initialized
     * state.  It is initialized to false at construction time, and gets set to
     * true when initialize is called.
     * When in this state, the cache behaves as normal.  When not,
     * the load and store methods will return without performing any cache
     * operations.
     */
    bool mInitialized = false;

    /**
     * "mBlobCache" is the cache in which the key/value blob pairs are stored.  It
     * is initially NULL, and will be initialized by getBlobCacheLocked the
     * first time it's needed.
     * The blob cache contains the Android build number. We treat version mismatches as an empty
     * cache (logic implemented in BlobCache::unflatten).
     */
    std::unique_ptr<FileBlobCache> mBlobCache;

    /**
     * "mFilename" is the name of the file for storing cache contents in between
     * program invocations.  It is initialized to an empty string at
     * construction time, and can be set with the setCacheFilename method.  An
     * empty string indicates that the cache should not be saved to or restored
     * from disk.
     */
    std::string mFilename;

    /**
     * "mIDHash" is the current identity hash for the cache validation. It is
     * initialized to an empty vector at construction time, and its content is
     * generated in the call of the validateCache method. An empty vector
     * indicates that cache validation is not performed, and the hash should
     * not be stored on disk.
     */
    std::vector<uint8_t> mIDHash;

    /**
     * "mSavePending" indicates whether or not a deferred save operation is
     * pending.  Each time a key/value pair is inserted into the cache via
     * load, a deferred save is initiated if one is not already pending.
     * This will wait some amount of time and then trigger a save of the cache
     * contents to disk.
     */
    bool mSavePending = false;

    /**
     *  "mObservedBlobValueSize" is the maximum value size observed by the cache reading function.
     */
    size_t mObservedBlobValueSize = 20 * 1024;

    /**
     *  The time in seconds to wait before saving newly inserted cache entries.
     */
    unsigned int mDeferredSaveDelay = 4;

    /**
     * "mMutex" is the mutex used to prevent concurrent access to the member
     * variables. It must be locked whenever the member variables are accessed.
     */
    mutable std::mutex mMutex;

    /**
     *  If set to "true", the next call to onVkFrameFlushed, will invoke
     * GrCanvas::storeVkPipelineCacheData. This does not guarantee that data will be stored on disk.
     */
    bool mTryToStorePipelineCache = true;

    /**
     * This flag is used by "ShaderCache::store" to distinguish between shader data and
     * Vulkan pipeline data.
     */
    bool mInStoreVkPipelineInProgress = false;

    /**
     *  "mNewPipelineCacheSize" has the size of the new Vulkan pipeline cache data. It is used
     *  to prevent unnecessary disk writes, if the pipeline cache size has not changed.
     */
    size_t mNewPipelineCacheSize = -1;
    /**
     *  "mOldPipelineCacheSize" has the size of the Vulkan pipeline cache data stored on disk.
     */
    size_t mOldPipelineCacheSize = -1;

    /**
     *  "mCacheDirty" is true when there is new shader cache data, which is not saved to disk.
     */
    bool mCacheDirty = false;

    /**
     * "sCache" is the singleton ShaderCache object.
     */
    static ShaderCache sCache;

    /**
     * "sIDKey" is the cache key of the identity hash
     */
    static constexpr uint8_t sIDKey = 0;

    /**
     * Most of this class concerns persistent storage for shaders, but it's also
     * interesting to keep track of how many shaders are stored in RAM. This
     * class provides a convenient entry point for that.
     */
    int mNumShadersCachedInRam = 0;

    friend class ShaderCacheTestUtils;  // used for unit testing
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
