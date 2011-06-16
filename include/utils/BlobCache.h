/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#ifndef ANDROID_BLOB_CACHE_H
#define ANDROID_BLOB_CACHE_H

#include <stddef.h>

#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/threads.h>

namespace android {

// A BlobCache is an in-memory cache for binary key/value pairs. All the public
// methods are thread-safe.
//
// The cache contents can be serialized to a file and reloaded in a subsequent
// execution of the program. This serialization is non-portable and should only
// be loaded by the device that generated it.
class BlobCache : public RefBase {
public:

    // Create an empty blob cache. The blob cache will cache key/value pairs
    // with key and value sizes less than or equal to maxKeySize and
    // maxValueSize, respectively. The total combined size of ALL cache entries
    // (key sizes plus value sizes) will not exceed maxTotalSize.
    BlobCache(size_t maxKeySize, size_t maxValueSize, size_t maxTotalSize);

    // set inserts a new binary value into the cache and associates it with the
    // given binary key.  If the key or value are too large for the cache then
    // the cache remains unchanged.  This includes the case where a different
    // value was previously associated with the given key - the old value will
    // remain in the cache.  If the given key and value are small enough to be
    // put in the cache (based on the maxKeySize, maxValueSize, and maxTotalSize
    // values specified to the BlobCache constructor), then the key/value pair
    // will be in the cache after set returns.  Note, however, that a subsequent
    // call to set may evict old key/value pairs from the cache.
    //
    // Preconditions:
    //   key != NULL
    //   0 < keySize
    //   value != NULL
    //   0 < valueSize
    void set(const void* key, size_t keySize, const void* value,
            size_t valueSize);

    // The get function retrieves from the cache the binary value associated
    // with a given binary key.  If the key is present in the cache then the
    // length of the binary value associated with that key is returned.  If the
    // value argument is non-NULL and the size of the cached value is less than
    // valueSize bytes then the cached value is copied into the buffer pointed
    // to by the value argument.  If the key is not present in the cache then 0
    // is returned and the buffer pointed to by the value argument is not
    // modified.
    //
    // Note that when calling get multiple times with the same key, the later
    // calls may fail, returning 0, even if earlier calls succeeded.  The return
    // value must be checked for each call.
    //
    // Preconditions:
    //   key != NULL
    //   0 < keySize
    //   0 <= valueSize
    size_t get(const void* key, size_t keySize, void* value, size_t valueSize);

private:
    // Copying is disallowed.
    BlobCache(const BlobCache&);
    void operator=(const BlobCache&);

    // A random function helper to get around MinGW not having nrand48()
    long int blob_random();

    // clean evicts a randomly chosen set of entries from the cache such that
    // the total size of all remaining entries is less than mMaxTotalSize/2.
    void clean();

    // isCleanable returns true if the cache is full enough for the clean method
    // to have some effect, and false otherwise.
    bool isCleanable() const;

    // A Blob is an immutable sized unstructured data blob.
    class Blob : public RefBase {
    public:
        Blob(const void* data, size_t size, bool copyData);
        ~Blob();

        bool operator<(const Blob& rhs) const;

        const void* getData() const;
        size_t getSize() const;

    private:
        // Copying is not allowed.
        Blob(const Blob&);
        void operator=(const Blob&);

        // mData points to the buffer containing the blob data.
        const void* mData;

        // mSize is the size of the blob data in bytes.
        size_t mSize;

        // mOwnsData indicates whether or not this Blob object should free the
        // memory pointed to by mData when the Blob gets destructed.
        bool mOwnsData;
    };

    // A CacheEntry is a single key/value pair in the cache.
    class CacheEntry {
    public:
        CacheEntry();
        CacheEntry(const sp<Blob>& key, const sp<Blob>& value);
        CacheEntry(const CacheEntry& ce);

        bool operator<(const CacheEntry& rhs) const;
        const CacheEntry& operator=(const CacheEntry&);

        sp<Blob> getKey() const;
        sp<Blob> getValue() const;

        void setValue(const sp<Blob>& value);

    private:

        // mKey is the key that identifies the cache entry.
        sp<Blob> mKey;

        // mValue is the cached data associated with the key.
        sp<Blob> mValue;
    };

    // mMaxKeySize is the maximum key size that will be cached. Calls to
    // BlobCache::set with a keySize parameter larger than mMaxKeySize will
    // simply not add the key/value pair to the cache.
    const size_t mMaxKeySize;

    // mMaxValueSize is the maximum value size that will be cached. Calls to
    // BlobCache::set with a valueSize parameter larger than mMaxValueSize will
    // simply not add the key/value pair to the cache.
    const size_t mMaxValueSize;

    // mMaxTotalSize is the maximum size that all cache entries can occupy. This
    // includes space for both keys and values. When a call to BlobCache::set
    // would otherwise cause this limit to be exceeded, either the key/value
    // pair passed to BlobCache::set will not be cached or other cache entries
    // will be evicted from the cache to make room for the new entry.
    const size_t mMaxTotalSize;

    // mTotalSize is the total combined size of all keys and values currently in
    // the cache.
    size_t mTotalSize;

    // mRandState is the pseudo-random number generator state. It is passed to
    // nrand48 to generate random numbers when needed. It must be protected by
    // mMutex.
    unsigned short mRandState[3];

    // mCacheEntries stores all the cache entries that are resident in memory.
    // Cache entries are added to it by the 'set' method.
    SortedVector<CacheEntry> mCacheEntries;

    // mMutex is used to synchronize access to all member variables.  It must be
    // locked any time the member variables are written or read.
    Mutex mMutex;
};

}

#endif // ANDROID_BLOB_CACHE_H
