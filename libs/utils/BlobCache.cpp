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
#include <utils/Errors.h>
#include <utils/Log.h>

namespace android {

// BlobCache::Header::mMagicNumber value
static const uint32_t blobCacheMagic = '_Bb$';

// BlobCache::Header::mBlobCacheVersion value
static const uint32_t blobCacheVersion = 1;

// BlobCache::Header::mDeviceVersion value
static const uint32_t blobCacheDeviceVersion = 1;

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
    ALOGV("initializing random seed using %lld", now);
}

void BlobCache::set(const void* key, size_t keySize, const void* value,
        size_t valueSize) {
    if (mMaxKeySize < keySize) {
        ALOGV("set: not caching because the key is too large: %d (limit: %d)",
                keySize, mMaxKeySize);
        return;
    }
    if (mMaxValueSize < valueSize) {
        ALOGV("set: not caching because the value is too large: %d (limit: %d)",
                valueSize, mMaxValueSize);
        return;
    }
    if (mMaxTotalSize < keySize + valueSize) {
        ALOGV("set: not caching because the combined key/value size is too "
                "large: %d (limit: %d)", keySize + valueSize, mMaxTotalSize);
        return;
    }
    if (keySize == 0) {
        ALOGW("set: not caching because keySize is 0");
        return;
    }
    if (valueSize <= 0) {
        ALOGW("set: not caching because valueSize is 0");
        return;
    }

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
                    ALOGV("set: not caching new key/value pair because the "
                            "total cache size limit would be exceeded: %d "
                            "(limit: %d)",
                            keySize + valueSize, mMaxTotalSize);
                    break;
                }
            }
            mCacheEntries.add(CacheEntry(keyBlob, valueBlob));
            mTotalSize = newTotalSize;
            ALOGV("set: created new cache entry with %d byte key and %d byte value",
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
                    ALOGV("set: not caching new value because the total cache "
                            "size limit would be exceeded: %d (limit: %d)",
                            keySize + valueSize, mMaxTotalSize);
                    break;
                }
            }
            mCacheEntries.editItemAt(index).setValue(valueBlob);
            mTotalSize = newTotalSize;
            ALOGV("set: updated existing cache entry with %d byte key and %d byte "
                    "value", keySize, valueSize);
        }
        break;
    }
}

size_t BlobCache::get(const void* key, size_t keySize, void* value,
        size_t valueSize) {
    if (mMaxKeySize < keySize) {
        ALOGV("get: not searching because the key is too large: %d (limit %d)",
                keySize, mMaxKeySize);
        return 0;
    }
    sp<Blob> dummyKey(new Blob(key, keySize, false));
    CacheEntry dummyEntry(dummyKey, NULL);
    ssize_t index = mCacheEntries.indexOf(dummyEntry);
    if (index < 0) {
        ALOGV("get: no cache entry found for key of size %d", keySize);
        return 0;
    }

    // The key was found. Return the value if the caller's buffer is large
    // enough.
    sp<Blob> valueBlob(mCacheEntries[index].getValue());
    size_t valueBlobSize = valueBlob->getSize();
    if (valueBlobSize <= valueSize) {
        ALOGV("get: copying %d bytes to caller's buffer", valueBlobSize);
        memcpy(value, valueBlob->getData(), valueBlobSize);
    } else {
        ALOGV("get: caller's buffer is too small for value: %d (needs %d)",
                valueSize, valueBlobSize);
    }
    return valueBlobSize;
}

static inline size_t align4(size_t size) {
    return (size + 3) & ~3;
}

size_t BlobCache::getFlattenedSize() const {
    size_t size = sizeof(Header);
    for (size_t i = 0; i < mCacheEntries.size(); i++) {
        const CacheEntry& e(mCacheEntries[i]);
        sp<Blob> keyBlob = e.getKey();
        sp<Blob> valueBlob = e.getValue();
        size = align4(size);
        size += sizeof(EntryHeader) + keyBlob->getSize() +
                valueBlob->getSize();
    }
    return size;
}

size_t BlobCache::getFdCount() const {
    return 0;
}

status_t BlobCache::flatten(void* buffer, size_t size, int fds[], size_t count)
        const {
    if (count != 0) {
        ALOGE("flatten: nonzero fd count: %zu", count);
        return BAD_VALUE;
    }

    // Write the cache header
    if (size < sizeof(Header)) {
        ALOGE("flatten: not enough room for cache header");
        return BAD_VALUE;
    }
    Header* header = reinterpret_cast<Header*>(buffer);
    header->mMagicNumber = blobCacheMagic;
    header->mBlobCacheVersion = blobCacheVersion;
    header->mDeviceVersion = blobCacheDeviceVersion;
    header->mNumEntries = mCacheEntries.size();

    // Write cache entries
    uint8_t* byteBuffer = reinterpret_cast<uint8_t*>(buffer);
    off_t byteOffset = align4(sizeof(Header));
    for (size_t i = 0; i < mCacheEntries.size(); i++) {
        const CacheEntry& e(mCacheEntries[i]);
        sp<Blob> keyBlob = e.getKey();
        sp<Blob> valueBlob = e.getValue();
        size_t keySize = keyBlob->getSize();
        size_t valueSize = valueBlob->getSize();

        size_t entrySize = sizeof(EntryHeader) + keySize + valueSize;
        if (byteOffset + entrySize > size) {
            ALOGE("flatten: not enough room for cache entries");
            return BAD_VALUE;
        }

        EntryHeader* eheader = reinterpret_cast<EntryHeader*>(
            &byteBuffer[byteOffset]);
        eheader->mKeySize = keySize;
        eheader->mValueSize = valueSize;

        memcpy(eheader->mData, keyBlob->getData(), keySize);
        memcpy(eheader->mData + keySize, valueBlob->getData(), valueSize);

        byteOffset += align4(entrySize);
    }

    return OK;
}

status_t BlobCache::unflatten(void const* buffer, size_t size, int fds[],
        size_t count) {
    // All errors should result in the BlobCache being in an empty state.
    mCacheEntries.clear();

    if (count != 0) {
        ALOGE("unflatten: nonzero fd count: %zu", count);
        return BAD_VALUE;
    }

    // Read the cache header
    if (size < sizeof(Header)) {
        ALOGE("unflatten: not enough room for cache header");
        return BAD_VALUE;
    }
    const Header* header = reinterpret_cast<const Header*>(buffer);
    if (header->mMagicNumber != blobCacheMagic) {
        ALOGE("unflatten: bad magic number: %d", header->mMagicNumber);
        return BAD_VALUE;
    }
    if (header->mBlobCacheVersion != blobCacheVersion ||
            header->mDeviceVersion != blobCacheDeviceVersion) {
        // We treat version mismatches as an empty cache.
        return OK;
    }

    // Read cache entries
    const uint8_t* byteBuffer = reinterpret_cast<const uint8_t*>(buffer);
    off_t byteOffset = align4(sizeof(Header));
    size_t numEntries = header->mNumEntries;
    for (size_t i = 0; i < numEntries; i++) {
        if (byteOffset + sizeof(EntryHeader) > size) {
            mCacheEntries.clear();
            ALOGE("unflatten: not enough room for cache entry headers");
            return BAD_VALUE;
        }

        const EntryHeader* eheader = reinterpret_cast<const EntryHeader*>(
                &byteBuffer[byteOffset]);
        size_t keySize = eheader->mKeySize;
        size_t valueSize = eheader->mValueSize;
        size_t entrySize = sizeof(EntryHeader) + keySize + valueSize;

        if (byteOffset + entrySize > size) {
            mCacheEntries.clear();
            ALOGE("unflatten: not enough room for cache entry headers");
            return BAD_VALUE;
        }

        const uint8_t* data = eheader->mData;
        set(data, keySize, data + keySize, valueSize);

        byteOffset += align4(entrySize);
    }

    return OK;
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
    if (data != NULL && copyData) {
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
