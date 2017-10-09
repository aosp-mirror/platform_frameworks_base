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

#include <cutils/compiler.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <GrContextOptions.h>

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
    ANDROID_API static ShaderCache& get();

    /**
     * "initShaderDiskCache" loads the serialized cache contents from disk and puts the ShaderCache
     * into an initialized state, such that it is able to insert and retrieve entries from the
     * cache.  This should be called when HWUI pipeline is initialized.  When not in the initialized
     * state the load and store methods will return without performing any cache operations.
     */
    virtual void initShaderDiskCache();

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
    void store(const SkData& key, const SkData& data) override;

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
    size_t mObservedBlobValueSize = 20*1024;

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
     * "sCache" is the singleton ShaderCache object.
     */
    static ShaderCache sCache;

    friend class ShaderCacheTestUtils; //used for unit testing
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
