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

#include <fcntl.h>
#include <stdio.h>

#include <gtest/gtest.h>

#include <utils/BlobCache.h>
#include <utils/Errors.h>

namespace android {

class BlobCacheTest : public ::testing::Test {
protected:
    enum {
        MAX_KEY_SIZE = 6,
        MAX_VALUE_SIZE = 8,
        MAX_TOTAL_SIZE = 13,
    };

    virtual void SetUp() {
        mBC = new BlobCache(MAX_KEY_SIZE, MAX_VALUE_SIZE, MAX_TOTAL_SIZE);
    }

    virtual void TearDown() {
        mBC.clear();
    }

    sp<BlobCache> mBC;
};

TEST_F(BlobCacheTest, CacheSingleValueSucceeds) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);
    ASSERT_EQ(size_t(4), mBC->get("abcd", 4, buf, 4));
    ASSERT_EQ('e', buf[0]);
    ASSERT_EQ('f', buf[1]);
    ASSERT_EQ('g', buf[2]);
    ASSERT_EQ('h', buf[3]);
}

TEST_F(BlobCacheTest, CacheTwoValuesSucceeds) {
    char buf[2] = { 0xee, 0xee };
    mBC->set("ab", 2, "cd", 2);
    mBC->set("ef", 2, "gh", 2);
    ASSERT_EQ(size_t(2), mBC->get("ab", 2, buf, 2));
    ASSERT_EQ('c', buf[0]);
    ASSERT_EQ('d', buf[1]);
    ASSERT_EQ(size_t(2), mBC->get("ef", 2, buf, 2));
    ASSERT_EQ('g', buf[0]);
    ASSERT_EQ('h', buf[1]);
}

TEST_F(BlobCacheTest, GetOnlyWritesInsideBounds) {
    char buf[6] = { 0xee, 0xee, 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);
    ASSERT_EQ(size_t(4), mBC->get("abcd", 4, buf+1, 4));
    ASSERT_EQ(0xee, buf[0]);
    ASSERT_EQ('e', buf[1]);
    ASSERT_EQ('f', buf[2]);
    ASSERT_EQ('g', buf[3]);
    ASSERT_EQ('h', buf[4]);
    ASSERT_EQ(0xee, buf[5]);
}

TEST_F(BlobCacheTest, GetOnlyWritesIfBufferIsLargeEnough) {
    char buf[3] = { 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);
    ASSERT_EQ(size_t(4), mBC->get("abcd", 4, buf, 3));
    ASSERT_EQ(0xee, buf[0]);
    ASSERT_EQ(0xee, buf[1]);
    ASSERT_EQ(0xee, buf[2]);
}

TEST_F(BlobCacheTest, GetDoesntAccessNullBuffer) {
    mBC->set("abcd", 4, "efgh", 4);
    ASSERT_EQ(size_t(4), mBC->get("abcd", 4, NULL, 0));
}

TEST_F(BlobCacheTest, MultipleSetsCacheLatestValue) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);
    mBC->set("abcd", 4, "ijkl", 4);
    ASSERT_EQ(size_t(4), mBC->get("abcd", 4, buf, 4));
    ASSERT_EQ('i', buf[0]);
    ASSERT_EQ('j', buf[1]);
    ASSERT_EQ('k', buf[2]);
    ASSERT_EQ('l', buf[3]);
}

TEST_F(BlobCacheTest, SecondSetKeepsFirstValueIfTooLarge) {
    char buf[MAX_VALUE_SIZE+1] = { 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);
    mBC->set("abcd", 4, buf, MAX_VALUE_SIZE+1);
    ASSERT_EQ(size_t(4), mBC->get("abcd", 4, buf, 4));
    ASSERT_EQ('e', buf[0]);
    ASSERT_EQ('f', buf[1]);
    ASSERT_EQ('g', buf[2]);
    ASSERT_EQ('h', buf[3]);
}

TEST_F(BlobCacheTest, DoesntCacheIfKeyIsTooBig) {
    char key[MAX_KEY_SIZE+1];
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    for (int i = 0; i < MAX_KEY_SIZE+1; i++) {
        key[i] = 'a';
    }
    mBC->set(key, MAX_KEY_SIZE+1, "bbbb", 4);
    ASSERT_EQ(size_t(0), mBC->get(key, MAX_KEY_SIZE+1, buf, 4));
    ASSERT_EQ(0xee, buf[0]);
    ASSERT_EQ(0xee, buf[1]);
    ASSERT_EQ(0xee, buf[2]);
    ASSERT_EQ(0xee, buf[3]);
}

TEST_F(BlobCacheTest, DoesntCacheIfValueIsTooBig) {
    char buf[MAX_VALUE_SIZE+1];
    for (int i = 0; i < MAX_VALUE_SIZE+1; i++) {
        buf[i] = 'b';
    }
    mBC->set("abcd", 4, buf, MAX_VALUE_SIZE+1);
    for (int i = 0; i < MAX_VALUE_SIZE+1; i++) {
        buf[i] = 0xee;
    }
    ASSERT_EQ(size_t(0), mBC->get("abcd", 4, buf, MAX_VALUE_SIZE+1));
    for (int i = 0; i < MAX_VALUE_SIZE+1; i++) {
        SCOPED_TRACE(i);
        ASSERT_EQ(0xee, buf[i]);
    }
}

TEST_F(BlobCacheTest, DoesntCacheIfKeyValuePairIsTooBig) {
    // Check a testing assumptions
    ASSERT_TRUE(MAX_TOTAL_SIZE < MAX_KEY_SIZE + MAX_VALUE_SIZE);
    ASSERT_TRUE(MAX_KEY_SIZE < MAX_TOTAL_SIZE);

    enum { bufSize = MAX_TOTAL_SIZE - MAX_KEY_SIZE + 1 };

    char key[MAX_KEY_SIZE];
    char buf[bufSize];
    for (int i = 0; i < MAX_KEY_SIZE; i++) {
        key[i] = 'a';
    }
    for (int i = 0; i < bufSize; i++) {
        buf[i] = 'b';
    }

    mBC->set(key, MAX_KEY_SIZE, buf, MAX_VALUE_SIZE);
    ASSERT_EQ(size_t(0), mBC->get(key, MAX_KEY_SIZE, NULL, 0));
}

TEST_F(BlobCacheTest, CacheMaxKeySizeSucceeds) {
    char key[MAX_KEY_SIZE];
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    for (int i = 0; i < MAX_KEY_SIZE; i++) {
        key[i] = 'a';
    }
    mBC->set(key, MAX_KEY_SIZE, "wxyz", 4);
    ASSERT_EQ(size_t(4), mBC->get(key, MAX_KEY_SIZE, buf, 4));
    ASSERT_EQ('w', buf[0]);
    ASSERT_EQ('x', buf[1]);
    ASSERT_EQ('y', buf[2]);
    ASSERT_EQ('z', buf[3]);
}

TEST_F(BlobCacheTest, CacheMaxValueSizeSucceeds) {
    char buf[MAX_VALUE_SIZE];
    for (int i = 0; i < MAX_VALUE_SIZE; i++) {
        buf[i] = 'b';
    }
    mBC->set("abcd", 4, buf, MAX_VALUE_SIZE);
    for (int i = 0; i < MAX_VALUE_SIZE; i++) {
        buf[i] = 0xee;
    }
    ASSERT_EQ(size_t(MAX_VALUE_SIZE), mBC->get("abcd", 4, buf,
            MAX_VALUE_SIZE));
    for (int i = 0; i < MAX_VALUE_SIZE; i++) {
        SCOPED_TRACE(i);
        ASSERT_EQ('b', buf[i]);
    }
}

TEST_F(BlobCacheTest, CacheMaxKeyValuePairSizeSucceeds) {
    // Check a testing assumption
    ASSERT_TRUE(MAX_KEY_SIZE < MAX_TOTAL_SIZE);

    enum { bufSize = MAX_TOTAL_SIZE - MAX_KEY_SIZE };

    char key[MAX_KEY_SIZE];
    char buf[bufSize];
    for (int i = 0; i < MAX_KEY_SIZE; i++) {
        key[i] = 'a';
    }
    for (int i = 0; i < bufSize; i++) {
        buf[i] = 'b';
    }

    mBC->set(key, MAX_KEY_SIZE, buf, bufSize);
    ASSERT_EQ(size_t(bufSize), mBC->get(key, MAX_KEY_SIZE, NULL, 0));
}

TEST_F(BlobCacheTest, CacheMinKeyAndValueSizeSucceeds) {
    char buf[1] = { 0xee };
    mBC->set("x", 1, "y", 1);
    ASSERT_EQ(size_t(1), mBC->get("x", 1, buf, 1));
    ASSERT_EQ('y', buf[0]);
}

TEST_F(BlobCacheTest, CacheSizeDoesntExceedTotalLimit) {
    for (int i = 0; i < 256; i++) {
        uint8_t k = i;
        mBC->set(&k, 1, "x", 1);
    }
    int numCached = 0;
    for (int i = 0; i < 256; i++) {
        uint8_t k = i;
        if (mBC->get(&k, 1, NULL, 0) == 1) {
            numCached++;
        }
    }
    ASSERT_GE(MAX_TOTAL_SIZE / 2, numCached);
}

TEST_F(BlobCacheTest, ExceedingTotalLimitHalvesCacheSize) {
    // Fill up the entire cache with 1 char key/value pairs.
    const int maxEntries = MAX_TOTAL_SIZE / 2;
    for (int i = 0; i < maxEntries; i++) {
        uint8_t k = i;
        mBC->set(&k, 1, "x", 1);
    }
    // Insert one more entry, causing a cache overflow.
    {
        uint8_t k = maxEntries;
        mBC->set(&k, 1, "x", 1);
    }
    // Count the number of entries in the cache.
    int numCached = 0;
    for (int i = 0; i < maxEntries+1; i++) {
        uint8_t k = i;
        if (mBC->get(&k, 1, NULL, 0) == 1) {
            numCached++;
        }
    }
    ASSERT_EQ(maxEntries/2 + 1, numCached);
}

class BlobCacheFlattenTest : public BlobCacheTest {
protected:
    virtual void SetUp() {
        BlobCacheTest::SetUp();
        mBC2 = new BlobCache(MAX_KEY_SIZE, MAX_VALUE_SIZE, MAX_TOTAL_SIZE);
    }

    virtual void TearDown() {
        mBC2.clear();
        BlobCacheTest::TearDown();
    }

    void roundTrip() {
        size_t size = mBC->getFlattenedSize();
        uint8_t* flat = new uint8_t[size];
        ASSERT_EQ(OK, mBC->flatten(flat, size, NULL, 0));
        ASSERT_EQ(OK, mBC2->unflatten(flat, size, NULL, 0));
        delete[] flat;
    }

    sp<BlobCache> mBC2;
};

TEST_F(BlobCacheFlattenTest, FlattenOneValue) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);
    roundTrip();
    ASSERT_EQ(size_t(4), mBC2->get("abcd", 4, buf, 4));
    ASSERT_EQ('e', buf[0]);
    ASSERT_EQ('f', buf[1]);
    ASSERT_EQ('g', buf[2]);
    ASSERT_EQ('h', buf[3]);
}

TEST_F(BlobCacheFlattenTest, FlattenFullCache) {
    // Fill up the entire cache with 1 char key/value pairs.
    const int maxEntries = MAX_TOTAL_SIZE / 2;
    for (int i = 0; i < maxEntries; i++) {
        uint8_t k = i;
        mBC->set(&k, 1, &k, 1);
    }

    roundTrip();

    // Verify the deserialized cache
    for (int i = 0; i < maxEntries; i++) {
        uint8_t k = i;
        uint8_t v = 0xee;
        ASSERT_EQ(size_t(1), mBC2->get(&k, 1, &v, 1));
        ASSERT_EQ(k, v);
    }
}

TEST_F(BlobCacheFlattenTest, FlattenDoesntChangeCache) {
    // Fill up the entire cache with 1 char key/value pairs.
    const int maxEntries = MAX_TOTAL_SIZE / 2;
    for (int i = 0; i < maxEntries; i++) {
        uint8_t k = i;
        mBC->set(&k, 1, &k, 1);
    }

    size_t size = mBC->getFlattenedSize();
    uint8_t* flat = new uint8_t[size];
    ASSERT_EQ(OK, mBC->flatten(flat, size, NULL, 0));
    delete[] flat;

    // Verify the cache that we just serialized
    for (int i = 0; i < maxEntries; i++) {
        uint8_t k = i;
        uint8_t v = 0xee;
        ASSERT_EQ(size_t(1), mBC->get(&k, 1, &v, 1));
        ASSERT_EQ(k, v);
    }
}

TEST_F(BlobCacheFlattenTest, FlattenCatchesBufferTooSmall) {
    // Fill up the entire cache with 1 char key/value pairs.
    const int maxEntries = MAX_TOTAL_SIZE / 2;
    for (int i = 0; i < maxEntries; i++) {
        uint8_t k = i;
        mBC->set(&k, 1, &k, 1);
    }

    size_t size = mBC->getFlattenedSize() - 1;
    uint8_t* flat = new uint8_t[size];
    ASSERT_EQ(BAD_VALUE, mBC->flatten(flat, size, NULL, 0));
    delete[] flat;
}

TEST_F(BlobCacheFlattenTest, UnflattenCatchesBadMagic) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);

    size_t size = mBC->getFlattenedSize();
    uint8_t* flat = new uint8_t[size];
    ASSERT_EQ(OK, mBC->flatten(flat, size, NULL, 0));
    flat[1] = ~flat[1];

    // Bad magic should cause an error.
    ASSERT_EQ(BAD_VALUE, mBC2->unflatten(flat, size, NULL, 0));
    delete[] flat;

    // The error should cause the unflatten to result in an empty cache
    ASSERT_EQ(size_t(0), mBC2->get("abcd", 4, buf, 4));
}

TEST_F(BlobCacheFlattenTest, UnflattenCatchesBadBlobCacheVersion) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);

    size_t size = mBC->getFlattenedSize();
    uint8_t* flat = new uint8_t[size];
    ASSERT_EQ(OK, mBC->flatten(flat, size, NULL, 0));
    flat[5] = ~flat[5];

    // Version mismatches shouldn't cause errors, but should not use the
    // serialized entries
    ASSERT_EQ(OK, mBC2->unflatten(flat, size, NULL, 0));
    delete[] flat;

    // The version mismatch should cause the unflatten to result in an empty
    // cache
    ASSERT_EQ(size_t(0), mBC2->get("abcd", 4, buf, 4));
}

TEST_F(BlobCacheFlattenTest, UnflattenCatchesBadBlobCacheDeviceVersion) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);

    size_t size = mBC->getFlattenedSize();
    uint8_t* flat = new uint8_t[size];
    ASSERT_EQ(OK, mBC->flatten(flat, size, NULL, 0));
    flat[10] = ~flat[10];

    // Version mismatches shouldn't cause errors, but should not use the
    // serialized entries
    ASSERT_EQ(OK, mBC2->unflatten(flat, size, NULL, 0));
    delete[] flat;

    // The version mismatch should cause the unflatten to result in an empty
    // cache
    ASSERT_EQ(size_t(0), mBC2->get("abcd", 4, buf, 4));
}

TEST_F(BlobCacheFlattenTest, UnflattenCatchesBufferTooSmall) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mBC->set("abcd", 4, "efgh", 4);

    size_t size = mBC->getFlattenedSize();
    uint8_t* flat = new uint8_t[size];
    ASSERT_EQ(OK, mBC->flatten(flat, size, NULL, 0));

    // A buffer truncation shouldt cause an error
    ASSERT_EQ(BAD_VALUE, mBC2->unflatten(flat, size-1, NULL, 0));
    delete[] flat;

    // The error should cause the unflatten to result in an empty cache
    ASSERT_EQ(size_t(0), mBC2->get("abcd", 4, buf, 4));
}

} // namespace android
