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

#define LOG_TAG "BlobCache"
//#define LOG_NDEBUG 0

#include <stdlib.h>
#include <string.h>

#include <utils/BlobCache.h>
#include <utils/Log.h>

namespace android {

BlobCache::BlobCache(size_t maxKeySize, size_t maxValueSize, size_t maxTotalSize):
        mMaxKeySize(maxKeySize),
        mMaxValueSize(maxValueSize),
        mMaxTotalSize(maxTotalSize),
        mTotalSize(0) {
    nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
#ifdef _WIN32
    srand(now);
#else
    mRandState[0] = (now >> 0) & 0xFFFF;
    mRandState[1] = (now >> 16) & 0xFFFF;
    mRandState[2] = (now >> 32) & 0xFFFF;
#endif
    LOGV("initializing random seed using %lld", now);
}

void BlobCache::set(const void* key, size_t keySize, const void* value,
        size_t valueSize) {
    if (mMaxKeySize < keySize) {
        LOGV("set: not caching because the key is too large: %d (limit: %d)",
                keySize, mMaxKeySize);
        return;
    }
    if (mMaxValueSize < valueSize) {
        LOGV("set: not caching because the value is too large: %d (limit: %d)",
                valueSize, mMaxValueSize);
        return;
    }
    if (mMaxTotalSize < keySize + valueSize) {
        LOGV("set: not caching because the combined key/value size is too "
                "large: %d (limit: %d)", keySize + valueSize, mMaxTotalSize);
        return;
    }
    if (keySize == 0) {
        LOGW("set: not caching because keySize is 0");
        return;
    }
    if (valueSize <= 0) {
        LOGW("set: not caching because valueSize is 0");
        return;
    }

    Mutex::Autolock lock(mMutex);
    sp<Blob> dummyKey(new Blob(key, keySize, false));
    CacheEntry dummyEntry(dummyKey, NULL);

    while (true) {

        ssize_t index = mCacheEntries.indexOf(dummyEntry);
        if (index < 0) {
            // Create a new cache entry.
            sp<Blob> keyBlob(new Blob(key, keySize, true));
            sp<Blob> valueBlob(new Blob(value, valueSize, true));
            size_t newTotalSize = mTotalSize + keySize + valueSize;
            if (mMaxTotalSize < newTotalSize) {
                if (isCleanable()) {
                    // Clean the cache and try again.
                    clean();
                    continue;
                } else {
                    LOGV("set: not caching new key/value pair because the "
                            "total cache size limit would be exceeded: %d "
                            "(limit: %d)",
                            keySize + valueSize, mMaxTotalSize);
                    break;
                }
            }
            mCacheEntries.add(CacheEntry(keyBlob, valueBlob));
            mTotalSize = newTotalSize;
            LOGV("set: created new cache entry with %d byte key and %d byte value",
                    keySize, valueSize);
        } else {
            // Update the existing cache entry.
            sp<Blob> valueBlob(new Blob(value, valueSize, true));
            sp<Blob> oldValueBlob(mCacheEntries[index].getValue());
            size_t newTotalSize = mTotalSize + valueSize - oldValueBlob->getSize();
            if (mMaxTotalSize < newTotalSize) {
                if (isCleanable()) {
                    // Clean the cache and try again.
                    clean();
                    continue;
                } else {
                    LOGV("set: not caching new value because the total cache "
                            "size limit would be exceeded: %d (limit: %d)",
                            keySize + valueSize, mMaxTotalSize);
                    break;
                }
            }
            mCacheEntries.editItemAt(index).setValue(valueBlob);
            mTotalSize = newTotalSize;
            LOGV("set: updated existing cache entry with %d byte key and %d byte "
                    "value", keySize, valueSize);
        }
        break;
    }
}

size_t BlobCache::get(const void* key, size_t keySize, void* value,
        size_t valueSize) {
    if (mMaxKeySize < keySize) {
        LOGV("get: not searching because the key is too large: %d (limit %d)",
                keySize, mMaxKeySize);
        return 0;
    }
    Mutex::Autolock lock(mMutex);
    sp<Blob> dummyKey(new Blob(key, keySize, false));
    CacheEntry dummyEntry(dummyKey, NULL);
    ssize_t index = mCacheEntries.indexOf(dummyEntry);
    if (index < 0) {
        LOGV("get: no cache entry found for key of size %d", keySize);
        return 0;
    }

    // The key was found. Return the value if the caller's buffer is large
    // enough.
    sp<Blob> valueBlob(mCacheEntries[index].getValue());
    size_t valueBlobSize = valueBlob->getSize();
    if (valueBlobSize <= valueSize) {
        LOGV("get: copying %d bytes to caller's buffer", valueBlobSize);
        memcpy(value, valueBlob->getData(), valueBlobSize);
    } else {
        LOGV("get: caller's buffer is too small for value: %d (needs %d)",
                valueSize, valueBlobSize);
    }
    return valueBlobSize;
}

long int BlobCache::blob_random() {
#ifdef _WIN32
    return rand();
#else
    return nrand48(mRandState);
#endif
}

void BlobCache::clean() {
    // Remove a random cache entry until the total cache size gets below half
    // the maximum total cache size.
    while (mTotalSize > mMaxTotalSize / 2) {
        size_t i = size_t(blob_random() % (mCacheEntries.size()));
        const CacheEntry& entry(mCacheEntries[i]);
        mTotalSize -= entry.getKey()->getSize() + entry.getValue()->getSize();
        mCacheEntries.removeAt(i);
    }
}

bool BlobCache::isCleanable() const {
    return mTotalSize > mMaxTotalSize / 2;
}

BlobCache::Blob::Blob(const void* data, size_t size, bool copyData):
        mData(copyData ? malloc(size) : data),
        mSize(size),
        mOwnsData(copyData) {
    if (copyData) {
        memcpy(const_cast<void*>(mData), data, size);
    }
}

BlobCache::Blob::~Blob() {
    if (mOwnsData) {
        free(const_cast<void*>(mData));
    }
}

bool BlobCache::Blob::operator<(const Blob& rhs) const {
    if (mSize == rhs.mSize) {
        return memcmp(mData, rhs.mData, mSize) < 0;
    } else {
        return mSize < rhs.mSize;
    }
}

const void* BlobCache::Blob::getData() const {
    return mData;
}

size_t BlobCache::Blob::getSize() const {
    return mSize;
}

BlobCache::CacheEntry::CacheEntry() {
}

BlobCache::CacheEntry::CacheEntry(const sp<Blob>& key, const sp<Blob>& value):
        mKey(key),
        mValue(value) {
}

BlobCache::CacheEntry::CacheEntry(const CacheEntry& ce):
        mKey(ce.mKey),
        mValue(ce.mValue) {
}

bool BlobCache::CacheEntry::operator<(const CacheEntry& rhs) const {
    return *mKey < *rhs.mKey;
}

const BlobCache::CacheEntry& BlobCache::CacheEntry::operator=(const CacheEntry& rhs) {
    mKey = rhs.mKey;
    mValue = rhs.mValue;
    return *this;
}

sp<BlobCache::Blob> BlobCache::CacheEntry::getKey() const {
    return mKey;
}

sp<BlobCache::Blob> BlobCache::CacheEntry::getValue() const {
    return mValue;
}

void BlobCache::CacheEntry::setValue(const sp<Blob>& value) {
    mValue = value;
}

} // namespace android
