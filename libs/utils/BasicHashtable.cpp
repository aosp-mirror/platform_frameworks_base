/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "BasicHashtable"

#include <math.h>

#include <utils/Log.h>
#include <utils/BasicHashtable.h>
#include <utils/misc.h>

namespace android {

BasicHashtableImpl::BasicHashtableImpl(size_t entrySize, bool hasTrivialDestructor,
        size_t minimumInitialCapacity, float loadFactor) :
        mBucketSize(entrySize + sizeof(Bucket)), mHasTrivialDestructor(hasTrivialDestructor),
        mLoadFactor(loadFactor), mSize(0),
        mFilledBuckets(0), mBuckets(NULL) {
    determineCapacity(minimumInitialCapacity, mLoadFactor, &mBucketCount, &mCapacity);
}

BasicHashtableImpl::BasicHashtableImpl(const BasicHashtableImpl& other) :
        mBucketSize(other.mBucketSize), mHasTrivialDestructor(other.mHasTrivialDestructor),
        mCapacity(other.mCapacity), mLoadFactor(other.mLoadFactor),
        mSize(other.mSize), mFilledBuckets(other.mFilledBuckets),
        mBucketCount(other.mBucketCount), mBuckets(other.mBuckets) {
    if (mBuckets) {
        SharedBuffer::bufferFromData(mBuckets)->acquire();
    }
}

void BasicHashtableImpl::dispose() {
    if (mBuckets) {
        releaseBuckets(mBuckets, mBucketCount);
    }
}

void BasicHashtableImpl::clone() {
    if (mBuckets) {
        void* newBuckets = allocateBuckets(mBucketCount);
        copyBuckets(mBuckets, newBuckets, mBucketCount);
        releaseBuckets(mBuckets, mBucketCount);
        mBuckets = newBuckets;
    }
}

void BasicHashtableImpl::setTo(const BasicHashtableImpl& other) {
    if (mBuckets) {
        releaseBuckets(mBuckets, mBucketCount);
    }

    mCapacity = other.mCapacity;
    mLoadFactor = other.mLoadFactor;
    mSize = other.mSize;
    mFilledBuckets = other.mFilledBuckets;
    mBucketCount = other.mBucketCount;
    mBuckets = other.mBuckets;

    if (mBuckets) {
        SharedBuffer::bufferFromData(mBuckets)->acquire();
    }
}

void BasicHashtableImpl::clear() {
    if (mBuckets) {
        if (mFilledBuckets) {
            SharedBuffer* sb = SharedBuffer::bufferFromData(mBuckets);
            if (sb->onlyOwner()) {
                destroyBuckets(mBuckets, mBucketCount);
                for (size_t i = 0; i < mSize; i++) {
                    Bucket& bucket = bucketAt(mBuckets, i);
                    bucket.cookie = 0;
                }
            } else {
                releaseBuckets(mBuckets, mBucketCount);
                mBuckets = NULL;
            }
            mFilledBuckets = 0;
        }
        mSize = 0;
    }
}

ssize_t BasicHashtableImpl::next(ssize_t index) const {
    if (mSize) {
        while (size_t(++index) < mBucketCount) {
            const Bucket& bucket = bucketAt(mBuckets, index);
            if (bucket.cookie & Bucket::PRESENT) {
                return index;
            }
        }
    }
    return -1;
}

ssize_t BasicHashtableImpl::find(ssize_t index, hash_t hash,
        const void* __restrict__ key) const {
    if (!mSize) {
        return -1;
    }

    hash = trimHash(hash);
    if (index < 0) {
        index = chainStart(hash, mBucketCount);

        const Bucket& bucket = bucketAt(mBuckets, size_t(index));
        if (bucket.cookie & Bucket::PRESENT) {
            if (compareBucketKey(bucket, key)) {
                return index;
            }
        } else {
            if (!(bucket.cookie & Bucket::COLLISION)) {
                return -1;
            }
        }
    }

    size_t inc = chainIncrement(hash, mBucketCount);
    for (;;) {
        index = chainSeek(index, inc, mBucketCount);

        const Bucket& bucket = bucketAt(mBuckets, size_t(index));
        if (bucket.cookie & Bucket::PRESENT) {
            if ((bucket.cookie & Bucket::HASH_MASK) == hash
                    && compareBucketKey(bucket, key)) {
                return index;
            }
        }
        if (!(bucket.cookie & Bucket::COLLISION)) {
            return -1;
        }
    }
}

size_t BasicHashtableImpl::add(hash_t hash, const void* entry) {
    if (!mBuckets) {
        mBuckets = allocateBuckets(mBucketCount);
    } else {
        edit();
    }

    hash = trimHash(hash);
    for (;;) {
        size_t index = chainStart(hash, mBucketCount);
        Bucket* bucket = &bucketAt(mBuckets, size_t(index));
        if (bucket->cookie & Bucket::PRESENT) {
            size_t inc = chainIncrement(hash, mBucketCount);
            do {
                bucket->cookie |= Bucket::COLLISION;
                index = chainSeek(index, inc, mBucketCount);
                bucket = &bucketAt(mBuckets, size_t(index));
            } while (bucket->cookie & Bucket::PRESENT);
        }

        uint32_t collision = bucket->cookie & Bucket::COLLISION;
        if (!collision) {
            if (mFilledBuckets >= mCapacity) {
                rehash(mCapacity * 2, mLoadFactor);
                continue;
            }
            mFilledBuckets += 1;
        }

        bucket->cookie = collision | Bucket::PRESENT | hash;
        mSize += 1;
        initializeBucketEntry(*bucket, entry);
        return index;
    }
}

void BasicHashtableImpl::removeAt(size_t index) {
    edit();

    Bucket& bucket = bucketAt(mBuckets, index);
    bucket.cookie &= ~Bucket::PRESENT;
    if (!(bucket.cookie & Bucket::COLLISION)) {
        mFilledBuckets -= 1;
    }
    mSize -= 1;
    if (!mHasTrivialDestructor) {
        destroyBucketEntry(bucket);
    }
}

void BasicHashtableImpl::rehash(size_t minimumCapacity, float loadFactor) {
    if (minimumCapacity < mSize) {
        minimumCapacity = mSize;
    }
    size_t newBucketCount, newCapacity;
    determineCapacity(minimumCapacity, loadFactor, &newBucketCount, &newCapacity);

    if (newBucketCount != mBucketCount || newCapacity != mCapacity) {
        if (mBuckets) {
            void* newBuckets;
            if (mSize) {
                newBuckets = allocateBuckets(newBucketCount);
                for (size_t i = 0; i < mBucketCount; i++) {
                    const Bucket& fromBucket = bucketAt(mBuckets, i);
                    if (fromBucket.cookie & Bucket::PRESENT) {
                        hash_t hash = fromBucket.cookie & Bucket::HASH_MASK;
                        size_t index = chainStart(hash, newBucketCount);
                        Bucket* toBucket = &bucketAt(newBuckets, size_t(index));
                        if (toBucket->cookie & Bucket::PRESENT) {
                            size_t inc = chainIncrement(hash, newBucketCount);
                            do {
                                toBucket->cookie |= Bucket::COLLISION;
                                index = chainSeek(index, inc, newBucketCount);
                                toBucket = &bucketAt(newBuckets, size_t(index));
                            } while (toBucket->cookie & Bucket::PRESENT);
                        }
                        toBucket->cookie = Bucket::PRESENT | hash;
                        initializeBucketEntry(*toBucket, fromBucket.entry);
                    }
                }
            } else {
                newBuckets = NULL;
            }
            releaseBuckets(mBuckets, mBucketCount);
            mBuckets = newBuckets;
            mFilledBuckets = mSize;
        }
        mBucketCount = newBucketCount;
        mCapacity = newCapacity;
    }
    mLoadFactor = loadFactor;
}

void* BasicHashtableImpl::allocateBuckets(size_t count) const {
    size_t bytes = count * mBucketSize;
    SharedBuffer* sb = SharedBuffer::alloc(bytes);
    LOG_ALWAYS_FATAL_IF(!sb, "Could not allocate %u bytes for hashtable with %u buckets.",
            uint32_t(bytes), uint32_t(count));
    void* buckets = sb->data();
    for (size_t i = 0; i < count; i++) {
        Bucket& bucket = bucketAt(buckets, i);
        bucket.cookie = 0;
    }
    return buckets;
}

void BasicHashtableImpl::releaseBuckets(void* __restrict__ buckets, size_t count) const {
    SharedBuffer* sb = SharedBuffer::bufferFromData(buckets);
    if (sb->release(SharedBuffer::eKeepStorage) == 1) {
        destroyBuckets(buckets, count);
        SharedBuffer::dealloc(sb);
    }
}

void BasicHashtableImpl::destroyBuckets(void* __restrict__ buckets, size_t count) const {
    if (!mHasTrivialDestructor) {
        for (size_t i = 0; i < count; i++) {
            Bucket& bucket = bucketAt(buckets, i);
            if (bucket.cookie & Bucket::PRESENT) {
                destroyBucketEntry(bucket);
            }
        }
    }
}

void BasicHashtableImpl::copyBuckets(const void* __restrict__ fromBuckets,
        void* __restrict__ toBuckets, size_t count) const {
    for (size_t i = 0; i < count; i++) {
        const Bucket& fromBucket = bucketAt(fromBuckets, i);
        Bucket& toBucket = bucketAt(toBuckets, i);
        toBucket.cookie = fromBucket.cookie;
        if (fromBucket.cookie & Bucket::PRESENT) {
            initializeBucketEntry(toBucket, fromBucket.entry);
        }
    }
}

// Table of 31-bit primes where each prime is no less than twice as large
// as the previous one.  Generated by "primes.py".
static size_t PRIMES[] = {
    5,
    11,
    23,
    47,
    97,
    197,
    397,
    797,
    1597,
    3203,
    6421,
    12853,
    25717,
    51437,
    102877,
    205759,
    411527,
    823117,
    1646237,
    3292489,
    6584983,
    13169977,
    26339969,
    52679969,
    105359939,
    210719881,
    421439783,
    842879579,
    1685759167,
    0,
};

void BasicHashtableImpl::determineCapacity(size_t minimumCapacity, float loadFactor,
        size_t* __restrict__ outBucketCount, size_t* __restrict__ outCapacity) {
    LOG_ALWAYS_FATAL_IF(loadFactor <= 0.0f || loadFactor > 1.0f,
            "Invalid load factor %0.3f.  Must be in the range (0, 1].", loadFactor);

    size_t count = ceilf(minimumCapacity / loadFactor) + 1;
    size_t i = 0;
    while (count > PRIMES[i] && i < NELEM(PRIMES)) {
        i++;
    }
    count = PRIMES[i];
    LOG_ALWAYS_FATAL_IF(!count, "Could not determine required number of buckets for "
            "hashtable with minimum capacity %u and load factor %0.3f.",
            uint32_t(minimumCapacity), loadFactor);
    *outBucketCount = count;
    *outCapacity = ceilf((count - 1) * loadFactor);
}

}; // namespace android
