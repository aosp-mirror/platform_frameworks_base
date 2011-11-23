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

#define LOG_TAG "BasicHashtable_test"

#include <utils/BasicHashtable.h>
#include <cutils/log.h>
#include <gtest/gtest.h>
#include <unistd.h>

namespace android {

typedef int SimpleKey;
typedef int SimpleValue;
typedef key_value_pair_t<SimpleKey, SimpleValue> SimpleEntry;
typedef BasicHashtable<SimpleKey, SimpleEntry> SimpleHashtable;

struct ComplexKey {
    int k;

    explicit ComplexKey(int k) : k(k) {
        instanceCount += 1;
    }

    ComplexKey(const ComplexKey& other) : k(other.k) {
        instanceCount += 1;
    }

    ~ComplexKey() {
        instanceCount -= 1;
    }

    bool operator ==(const ComplexKey& other) const {
        return k == other.k;
    }

    bool operator !=(const ComplexKey& other) const {
        return k != other.k;
    }

    static ssize_t instanceCount;
};

ssize_t ComplexKey::instanceCount = 0;

template<> inline hash_t hash_type(const ComplexKey& value) {
    return hash_type(value.k);
}

struct ComplexValue {
    int v;

    explicit ComplexValue(int v) : v(v) {
        instanceCount += 1;
    }

    ComplexValue(const ComplexValue& other) : v(other.v) {
        instanceCount += 1;
    }

    ~ComplexValue() {
        instanceCount -= 1;
    }

    static ssize_t instanceCount;
};

ssize_t ComplexValue::instanceCount = 0;

typedef key_value_pair_t<ComplexKey, ComplexValue> ComplexEntry;
typedef BasicHashtable<ComplexKey, ComplexEntry> ComplexHashtable;

class BasicHashtableTest : public testing::Test {
protected:
    virtual void SetUp() {
        ComplexKey::instanceCount = 0;
        ComplexValue::instanceCount = 0;
    }

    virtual void TearDown() {
        ASSERT_NO_FATAL_FAILURE(assertInstanceCount(0, 0));
    }

    void assertInstanceCount(ssize_t keys, ssize_t values) {
        if (keys != ComplexKey::instanceCount || values != ComplexValue::instanceCount) {
            FAIL() << "Expected " << keys << " keys and " << values << " values "
                    "but there were actually " << ComplexKey::instanceCount << " keys and "
                    << ComplexValue::instanceCount << " values";
        }
    }

public:
    template <typename TKey, typename TEntry>
    static void cookieAt(const BasicHashtable<TKey, TEntry>& h, size_t index,
            bool* collision, bool* present, hash_t* hash) {
        uint32_t cookie = h.cookieAt(index);
        *collision = cookie & BasicHashtable<TKey, TEntry>::Bucket::COLLISION;
        *present = cookie & BasicHashtable<TKey, TEntry>::Bucket::PRESENT;
        *hash = cookie & BasicHashtable<TKey, TEntry>::Bucket::HASH_MASK;
    }

    template <typename TKey, typename TEntry>
    static const void* getBuckets(const BasicHashtable<TKey, TEntry>& h) {
        return h.mBuckets;
    }
};

template <typename TKey, typename TValue>
static size_t add(BasicHashtable<TKey, key_value_pair_t<TKey, TValue> >& h,
        const TKey& key, const TValue& value) {
    return h.add(hash_type(key), key_value_pair_t<TKey, TValue>(key, value));
}

template <typename TKey, typename TValue>
static ssize_t find(BasicHashtable<TKey, key_value_pair_t<TKey, TValue> >& h,
        ssize_t index, const TKey& key) {
    return h.find(index, hash_type(key), key);
}

template <typename TKey, typename TValue>
static bool remove(BasicHashtable<TKey, key_value_pair_t<TKey, TValue> >& h,
        const TKey& key) {
    ssize_t index = find(h, -1, key);
    if (index >= 0) {
        h.removeAt(index);
        return true;
    }
    return false;
}

template <typename TEntry>
static void getKeyValue(const TEntry& entry, int* key, int* value);

template <> void getKeyValue(const SimpleEntry& entry, int* key, int* value) {
    *key = entry.key;
    *value = entry.value;
}

template <> void getKeyValue(const ComplexEntry& entry, int* key, int* value) {
    *key = entry.key.k;
    *value = entry.value.v;
}

template <typename TKey, typename TValue>
static void dump(BasicHashtable<TKey, key_value_pair_t<TKey, TValue> >& h) {
    LOGD("hashtable %p, size=%u, capacity=%u, bucketCount=%u",
            &h, h.size(), h.capacity(), h.bucketCount());
    for (size_t i = 0; i < h.bucketCount(); i++) {
        bool collision, present;
        hash_t hash;
        BasicHashtableTest::cookieAt(h, i, &collision, &present, &hash);
        if (present) {
            int key, value;
            getKeyValue(h.entryAt(i), &key, &value);
            LOGD("  [%3u] = collision=%d, present=%d, hash=0x%08x, key=%3d, value=%3d, "
                    "hash_type(key)=0x%08x",
                    i, collision, present, hash, key, value, hash_type(key));
        } else {
            LOGD("  [%3u] = collision=%d, present=%d",
                    i, collision, present);
        }
    }
}

TEST_F(BasicHashtableTest, DefaultConstructor_WithDefaultProperties) {
    SimpleHashtable h;

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(3U, h.capacity());
    EXPECT_EQ(5U, h.bucketCount());
    EXPECT_EQ(0.75f, h.loadFactor());
}

TEST_F(BasicHashtableTest, Constructor_WithNonUnityLoadFactor) {
    SimpleHashtable h(52, 0.8f);

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(77U, h.capacity());
    EXPECT_EQ(97U, h.bucketCount());
    EXPECT_EQ(0.8f, h.loadFactor());
}

TEST_F(BasicHashtableTest, Constructor_WithUnityLoadFactorAndExactCapacity) {
    SimpleHashtable h(46, 1.0f);

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(46U, h.capacity()); // must be one less than bucketCount because loadFactor == 1.0f
    EXPECT_EQ(47U, h.bucketCount());
    EXPECT_EQ(1.0f, h.loadFactor());
}

TEST_F(BasicHashtableTest, Constructor_WithUnityLoadFactorAndInexactCapacity) {
    SimpleHashtable h(42, 1.0f);

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(46U, h.capacity()); // must be one less than bucketCount because loadFactor == 1.0f
    EXPECT_EQ(47U, h.bucketCount());
    EXPECT_EQ(1.0f, h.loadFactor());
}

TEST_F(BasicHashtableTest, FindAddFindRemoveFind_OneEntry) {
    SimpleHashtable h;
    ssize_t index = find(h, -1, 8);
    ASSERT_EQ(-1, index);

    index = add(h, 8, 1);
    ASSERT_EQ(1U, h.size());

    ASSERT_EQ(index, find(h, -1, 8));
    ASSERT_EQ(8, h.entryAt(index).key);
    ASSERT_EQ(1, h.entryAt(index).value);

    index = find(h, index, 8);
    ASSERT_EQ(-1, index);

    ASSERT_TRUE(remove(h, 8));
    ASSERT_EQ(0U, h.size());

    index = find(h, -1, 8);
    ASSERT_EQ(-1, index);
}

TEST_F(BasicHashtableTest, FindAddFindRemoveFind_MultipleEntryWithUniqueKey) {
    const size_t N = 11;

    SimpleHashtable h;
    for (size_t i = 0; i < N; i++) {
        ssize_t index = find(h, -1, int(i));
        ASSERT_EQ(-1, index);

        index = add(h, int(i), int(i * 10));
        ASSERT_EQ(i + 1, h.size());

        ASSERT_EQ(index, find(h, -1, int(i)));
        ASSERT_EQ(int(i), h.entryAt(index).key);
        ASSERT_EQ(int(i * 10), h.entryAt(index).value);

        index = find(h, index, int(i));
        ASSERT_EQ(-1, index);
    }

    for (size_t i = N; --i > 0; ) {
        ASSERT_TRUE(remove(h, int(i))) << "i = " << i;
        ASSERT_EQ(i, h.size());

        ssize_t index = find(h, -1, int(i));
        ASSERT_EQ(-1, index);
    }
}

TEST_F(BasicHashtableTest, FindAddFindRemoveFind_MultipleEntryWithDuplicateKey) {
    const size_t N = 11;
    const int K = 1;

    SimpleHashtable h;
    for (size_t i = 0; i < N; i++) {
        ssize_t index = find(h, -1, K);
        if (i == 0) {
            ASSERT_EQ(-1, index);
        } else {
            ASSERT_NE(-1, index);
        }

        add(h, K, int(i));
        ASSERT_EQ(i + 1, h.size());

        index = -1;
        int values = 0;
        for (size_t j = 0; j <= i; j++) {
            index = find(h, index, K);
            ASSERT_GE(index, 0);
            ASSERT_EQ(K, h.entryAt(index).key);
            values |= 1 << h.entryAt(index).value;
        }
        ASSERT_EQ(values, (1 << (i + 1)) - 1);

        index = find(h, index, K);
        ASSERT_EQ(-1, index);
    }

    for (size_t i = N; --i > 0; ) {
        ASSERT_TRUE(remove(h, K)) << "i = " << i;
        ASSERT_EQ(i, h.size());

        ssize_t index = -1;
        for (size_t j = 0; j < i; j++) {
            index = find(h, index, K);
            ASSERT_GE(index, 0);
            ASSERT_EQ(K, h.entryAt(index).key);
        }

        index = find(h, index, K);
        ASSERT_EQ(-1, index);
    }
}

TEST_F(BasicHashtableTest, Clear_WhenAlreadyEmpty_DoesNothing) {
    SimpleHashtable h;
    h.clear();

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(3U, h.capacity());
    EXPECT_EQ(5U, h.bucketCount());
    EXPECT_EQ(0.75f, h.loadFactor());
}

TEST_F(BasicHashtableTest, Clear_AfterElementsAdded_RemovesThem) {
    SimpleHashtable h;
    add(h, 0, 0);
    add(h, 1, 0);
    h.clear();

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(3U, h.capacity());
    EXPECT_EQ(5U, h.bucketCount());
    EXPECT_EQ(0.75f, h.loadFactor());
}

TEST_F(BasicHashtableTest, Clear_AfterElementsAdded_DestroysThem) {
    ComplexHashtable h;
    add(h, ComplexKey(0), ComplexValue(0));
    add(h, ComplexKey(1), ComplexValue(0));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));

    h.clear();
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(0, 0));

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(3U, h.capacity());
    EXPECT_EQ(5U, h.bucketCount());
    EXPECT_EQ(0.75f, h.loadFactor());
}

TEST_F(BasicHashtableTest, Remove_AfterElementsAdded_DestroysThem) {
    ComplexHashtable h;
    add(h, ComplexKey(0), ComplexValue(0));
    add(h, ComplexKey(1), ComplexValue(0));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));

    ASSERT_TRUE(remove(h, ComplexKey(0)));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(1, 1));

    ASSERT_TRUE(remove(h, ComplexKey(1)));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(0, 0));

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(3U, h.capacity());
    EXPECT_EQ(5U, h.bucketCount());
    EXPECT_EQ(0.75f, h.loadFactor());
}

TEST_F(BasicHashtableTest, Destructor_AfterElementsAdded_DestroysThem) {
    {
        ComplexHashtable h;
        add(h, ComplexKey(0), ComplexValue(0));
        add(h, ComplexKey(1), ComplexValue(0));
        ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));
    } // h is destroyed here

    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(0, 0));
}

TEST_F(BasicHashtableTest, Next_WhenEmpty_ReturnsMinusOne) {
    SimpleHashtable h;

    ASSERT_EQ(-1, h.next(-1));
}

TEST_F(BasicHashtableTest, Next_WhenNonEmpty_IteratesOverAllEntries) {
    const int N = 88;

    SimpleHashtable h;
    for (int i = 0; i < N; i++) {
        add(h, i, i * 10);
    }

    bool set[N];
    memset(set, 0, sizeof(bool) * N);
    int count = 0;
    for (ssize_t index = -1; (index = h.next(index)) != -1; ) {
        ASSERT_GE(index, 0);
        ASSERT_LT(size_t(index), h.bucketCount());

        const SimpleEntry& entry = h.entryAt(index);
        ASSERT_GE(entry.key, 0);
        ASSERT_LT(entry.key, N);
        ASSERT_EQ(false, set[entry.key]);
        ASSERT_EQ(entry.key * 10, entry.value);

        set[entry.key] = true;
        count += 1;
    }
    ASSERT_EQ(N, count);
}

TEST_F(BasicHashtableTest, Add_RehashesOnDemand) {
    SimpleHashtable h;
    size_t initialCapacity = h.capacity();
    size_t initialBucketCount = h.bucketCount();

    for (size_t i = 0; i < initialCapacity; i++) {
        add(h, int(i), 0);
    }

    EXPECT_EQ(initialCapacity, h.size());
    EXPECT_EQ(initialCapacity, h.capacity());
    EXPECT_EQ(initialBucketCount, h.bucketCount());

    add(h, -1, -1);

    EXPECT_EQ(initialCapacity + 1, h.size());
    EXPECT_GT(h.capacity(), initialCapacity);
    EXPECT_GT(h.bucketCount(), initialBucketCount);
    EXPECT_GT(h.bucketCount(), h.capacity());
}

TEST_F(BasicHashtableTest, Rehash_WhenCapacityAndBucketCountUnchanged_DoesNothing) {
    ComplexHashtable h;
    add(h, ComplexKey(0), ComplexValue(0));
    const void* oldBuckets = getBuckets(h);
    ASSERT_NE((void*)NULL, oldBuckets);
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(1, 1));

    h.rehash(h.capacity(), h.loadFactor());

    ASSERT_EQ(oldBuckets, getBuckets(h));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(1, 1));
}

TEST_F(BasicHashtableTest, Rehash_WhenEmptyAndHasNoBuckets_ButDoesNotAllocateBuckets) {
    ComplexHashtable h;
    ASSERT_EQ((void*)NULL, getBuckets(h));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(0, 0));

    h.rehash(9, 1.0f);

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(10U, h.capacity());
    EXPECT_EQ(11U, h.bucketCount());
    EXPECT_EQ(1.0f, h.loadFactor());
    EXPECT_EQ((void*)NULL, getBuckets(h));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(0, 0));
}

TEST_F(BasicHashtableTest, Rehash_WhenEmptyAndHasBuckets_ReleasesBucketsAndSetsCapacity) {
    ComplexHashtable h(10);
    add(h, ComplexKey(0), ComplexValue(0));
    ASSERT_TRUE(remove(h, ComplexKey(0)));
    ASSERT_NE((void*)NULL, getBuckets(h));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(0, 0));

    h.rehash(0, 0.75f);

    EXPECT_EQ(0U, h.size());
    EXPECT_EQ(3U, h.capacity());
    EXPECT_EQ(5U, h.bucketCount());
    EXPECT_EQ(0.75f, h.loadFactor());
    EXPECT_EQ((void*)NULL, getBuckets(h));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(0, 0));
}

TEST_F(BasicHashtableTest, Rehash_WhenLessThanCurrentCapacity_ShrinksBuckets) {
    ComplexHashtable h(10);
    add(h, ComplexKey(0), ComplexValue(0));
    add(h, ComplexKey(1), ComplexValue(1));
    const void* oldBuckets = getBuckets(h);
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));

    h.rehash(0, 0.75f);

    EXPECT_EQ(2U, h.size());
    EXPECT_EQ(3U, h.capacity());
    EXPECT_EQ(5U, h.bucketCount());
    EXPECT_EQ(0.75f, h.loadFactor());
    EXPECT_NE(oldBuckets, getBuckets(h));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));
}

TEST_F(BasicHashtableTest, CopyOnWrite) {
    ComplexHashtable h1;
    add(h1, ComplexKey(0), ComplexValue(0));
    add(h1, ComplexKey(1), ComplexValue(1));
    const void* originalBuckets = getBuckets(h1);
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));
    ssize_t index0 = find(h1, -1, ComplexKey(0));
    EXPECT_GE(index0, 0);

    // copy constructor acquires shared reference
    ComplexHashtable h2(h1);
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));
    ASSERT_EQ(originalBuckets, getBuckets(h2));
    EXPECT_EQ(h1.size(), h2.size());
    EXPECT_EQ(h1.capacity(), h2.capacity());
    EXPECT_EQ(h1.bucketCount(), h2.bucketCount());
    EXPECT_EQ(h1.loadFactor(), h2.loadFactor());
    EXPECT_EQ(index0, find(h2, -1, ComplexKey(0)));

    // operator= acquires shared reference
    ComplexHashtable h3;
    h3 = h2;
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));
    ASSERT_EQ(originalBuckets, getBuckets(h3));
    EXPECT_EQ(h1.size(), h3.size());
    EXPECT_EQ(h1.capacity(), h3.capacity());
    EXPECT_EQ(h1.bucketCount(), h3.bucketCount());
    EXPECT_EQ(h1.loadFactor(), h3.loadFactor());
    EXPECT_EQ(index0, find(h3, -1, ComplexKey(0)));

    // editEntryAt copies shared contents
    h1.editEntryAt(index0).value.v = 42;
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(4, 4));
    ASSERT_NE(originalBuckets, getBuckets(h1));
    EXPECT_EQ(42, h1.entryAt(index0).value.v);
    EXPECT_EQ(0, h2.entryAt(index0).value.v);
    EXPECT_EQ(0, h3.entryAt(index0).value.v);

    // clear releases reference to shared contents
    h2.clear();
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(4, 4));
    EXPECT_EQ(0U, h2.size());
    ASSERT_NE(originalBuckets, getBuckets(h2));

    // operator= acquires shared reference, destroys unshared contents
    h1 = h3;
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));
    ASSERT_EQ(originalBuckets, getBuckets(h1));
    EXPECT_EQ(h3.size(), h1.size());
    EXPECT_EQ(h3.capacity(), h1.capacity());
    EXPECT_EQ(h3.bucketCount(), h1.bucketCount());
    EXPECT_EQ(h3.loadFactor(), h1.loadFactor());
    EXPECT_EQ(index0, find(h1, -1, ComplexKey(0)));

    // add copies shared contents
    add(h1, ComplexKey(2), ComplexValue(2));
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(5, 5));
    ASSERT_NE(originalBuckets, getBuckets(h1));
    EXPECT_EQ(3U, h1.size());
    EXPECT_EQ(0U, h2.size());
    EXPECT_EQ(2U, h3.size());

    // remove copies shared contents
    h1 = h3;
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));
    ASSERT_EQ(originalBuckets, getBuckets(h1));
    h1.removeAt(index0);
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(3, 3));
    ASSERT_NE(originalBuckets, getBuckets(h1));
    EXPECT_EQ(1U, h1.size());
    EXPECT_EQ(0U, h2.size());
    EXPECT_EQ(2U, h3.size());

    // rehash copies shared contents
    h1 = h3;
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(2, 2));
    ASSERT_EQ(originalBuckets, getBuckets(h1));
    h1.rehash(10, 1.0f);
    ASSERT_NO_FATAL_FAILURE(assertInstanceCount(4, 4));
    ASSERT_NE(originalBuckets, getBuckets(h1));
    EXPECT_EQ(2U, h1.size());
    EXPECT_EQ(0U, h2.size());
    EXPECT_EQ(2U, h3.size());
}

} // namespace android
