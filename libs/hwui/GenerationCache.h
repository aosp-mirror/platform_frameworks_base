/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_UI_GENERATION_CACHE_H
#define ANDROID_UI_GENERATION_CACHE_H

#include <utils/KeyedVector.h>
#include <utils/RefBase.h>

#include "SortedList.h"

namespace android {
namespace uirenderer {

template<typename K, typename V>
class GenerationCacheStorage {
public:
    virtual ~GenerationCacheStorage();

    virtual size_t size() const = 0;
    virtual void clear() = 0;
    virtual ssize_t add(const K& key, const V& item) = 0;
    virtual ssize_t indexOfKey(const K& key) const = 0;
    virtual const V& valueAt(size_t index) const = 0;
    virtual ssize_t removeItemsAt(size_t index, size_t count) = 0;
}; // GenerationCacheStorage

template<typename K, typename V>
GenerationCacheStorage<K, V>::~GenerationCacheStorage() {
}

template<typename K, typename V>
class KeyedVectorStorage: public GenerationCacheStorage<K, V> {
public:
    KeyedVectorStorage() { }
    ~KeyedVectorStorage() { }

    inline size_t size() const { return mStorage.size(); }
    inline void clear() { mStorage.clear(); }
    inline ssize_t add(const K& key, const V& value) { return mStorage.add(key, value); }
    inline ssize_t indexOfKey(const K& key) const { return mStorage.indexOfKey(key); }
    inline const V& valueAt(size_t index) const { return mStorage.valueAt(index); }
    inline ssize_t removeItemsAt(size_t index, size_t count) {
        return mStorage.removeItemsAt(index, count);
    }
private:
    KeyedVector<K, V> mStorage;
}; // class KeyedVectorStorage

template<typename K, typename V>
class SortedListStorage: public GenerationCacheStorage<K, V> {
public:
    SortedListStorage() { }
    ~SortedListStorage() { }

    inline size_t size() const { return mStorage.size(); }
    inline void clear() { mStorage.clear(); }
    inline ssize_t add(const K& key, const V& value) {
        return mStorage.add(key_value_pair_t<K, V>(key, value));
    }
    inline ssize_t indexOfKey(const K& key) const {
        return mStorage.indexOf(key_value_pair_t<K, V>(key));
    }
    inline const V& valueAt(size_t index) const { return mStorage.itemAt(index).value; }
    inline ssize_t removeItemsAt(size_t index, size_t count) {
        return mStorage.removeItemsAt(index, count);
    }
private:
    SortedList<key_value_pair_t<K, V> > mStorage;
}; // class SortedListStorage

template<typename EntryKey, typename EntryValue>
class OnEntryRemoved {
public:
    virtual ~OnEntryRemoved() { };
    virtual void operator()(EntryKey& key, EntryValue& value) = 0;
}; // class OnEntryRemoved

template<typename EntryKey, typename EntryValue>
struct Entry: public LightRefBase<Entry<EntryKey, EntryValue> > {
    Entry() { }
    Entry(const Entry<EntryKey, EntryValue>& e):
            key(e.key), value(e.value), index(e.index), parent(e.parent), child(e.child) { }
    Entry(sp<Entry<EntryKey, EntryValue> > e):
            key(e->key), value(e->value), index(e->index), parent(e->parent), child(e->child) { }

    EntryKey key;
    EntryValue value;
    ssize_t index;

    sp<Entry<EntryKey, EntryValue> > parent;
    sp<Entry<EntryKey, EntryValue> > child;
}; // struct Entry

template<typename K, typename V>
class GenerationCache {
public:
    GenerationCache(uint32_t maxCapacity);
    virtual ~GenerationCache();

    enum Capacity {
        kUnlimitedCapacity,
    };

    void setOnEntryRemovedListener(OnEntryRemoved<K, V>* listener);

    void clear();

    bool contains(K key) const;
    V get(K key);
    void put(K key, V value);
    V remove(K key);
    V removeOldest();

    uint32_t size() const;

protected:
    virtual GenerationCacheStorage<K, sp<Entry<K, V> > >* createStorage() = 0;
    GenerationCacheStorage<K, sp<Entry<K, V> > >* mCache;

private:
    void addToCache(sp<Entry<K, V> > entry, K key, V value);
    void attachToCache(sp<Entry<K, V> > entry);
    void detachFromCache(sp<Entry<K, V> > entry);

    V removeAt(ssize_t index);

    uint32_t mMaxCapacity;

    OnEntryRemoved<K, V>* mListener;

    sp<Entry<K, V> > mOldest;
    sp<Entry<K, V> > mYoungest;
}; // class GenerationCache

template<typename K, typename V>
class GenerationSingleCache: public GenerationCache<K, V> {
public:
    GenerationSingleCache(uint32_t maxCapacity): GenerationCache<K, V>(maxCapacity) {
        GenerationCache<K, V>::mCache = createStorage();
    };
    ~GenerationSingleCache() { }
protected:
    GenerationCacheStorage<K, sp<Entry<K, V> > >* createStorage() {
        return new KeyedVectorStorage<K, sp<Entry<K, V> > >;
    }
}; // GenerationSingleCache

template<typename K, typename V>
class GenerationMultiCache: public GenerationCache<K, V> {
public:
    GenerationMultiCache(uint32_t maxCapacity): GenerationCache<K, V>(maxCapacity) {
        GenerationCache<K, V>::mCache = createStorage();
    };
    ~GenerationMultiCache() { }
protected:
    GenerationCacheStorage<K, sp<Entry<K, V> > >* createStorage() {
        return new SortedListStorage<K, sp<Entry<K, V> > >;
    }
}; // GenerationMultiCache

template<typename K, typename V>
GenerationCache<K, V>::GenerationCache(uint32_t maxCapacity): mMaxCapacity(maxCapacity), mListener(NULL) {
};

template<typename K, typename V>
GenerationCache<K, V>::~GenerationCache() {
    clear();
    delete mCache;
};

template<typename K, typename V>
uint32_t GenerationCache<K, V>::size() const {
    return mCache->size();
}

template<typename K, typename V>
void GenerationCache<K, V>::setOnEntryRemovedListener(OnEntryRemoved<K, V>* listener) {
    mListener = listener;
}

template<typename K, typename V>
void GenerationCache<K, V>::clear() {
    if (mListener) {
        while (mCache->size() > 0) {
            removeOldest();
        }
    } else {
        mCache->clear();
    }
    mYoungest.clear();
    mOldest.clear();
}

template<typename K, typename V>
bool GenerationCache<K, V>::contains(K key) const {
    return mCache->indexOfKey(key) >= 0;
}

template<typename K, typename V>
V GenerationCache<K, V>::get(K key) {
    ssize_t index = mCache->indexOfKey(key);
    if (index >= 0) {
        sp<Entry<K, V> > entry = mCache->valueAt(index);
        if (entry.get()) {
            detachFromCache(entry);
            attachToCache(entry);
            return entry->value;
        }
    }

    return NULL;
}

template<typename K, typename V>
void GenerationCache<K, V>::put(K key, V value) {
    if (mMaxCapacity != kUnlimitedCapacity && mCache->size() >= mMaxCapacity) {
        removeOldest();
    }

    ssize_t index = mCache->indexOfKey(key);
    if (index >= 0) {
        sp<Entry<K, V> > entry = mCache->valueAt(index);
        detachFromCache(entry);
        addToCache(entry, key, value);
    } else {
        sp<Entry<K, V> > entry = new Entry<K, V>;
        addToCache(entry, key, value);
    }
}

template<typename K, typename V>
void GenerationCache<K, V>::addToCache(sp<Entry<K, V> > entry, K key, V value) {
    entry->key = key;
    entry->value = value;
    entry->index = mCache->add(key, entry);
    attachToCache(entry);
}

template<typename K, typename V>
V GenerationCache<K, V>::remove(K key) {
    ssize_t index = mCache->indexOfKey(key);
    if (index >= 0) {
        return removeAt(index);
    }

    return NULL;
}

template<typename K, typename V>
V GenerationCache<K, V>::removeAt(ssize_t index) {
    sp<Entry<K, V> > entry = mCache->valueAt(index);
    if (mListener) {
        (*mListener)(entry->key, entry->value);
    }
    mCache->removeItemsAt(index, 1);
    detachFromCache(entry);

    return entry->value;
}

template<typename K, typename V>
V GenerationCache<K, V>::removeOldest() {
    if (mOldest.get()) {
        return removeAt(mOldest->index);
    }

    return NULL;
}

template<typename K, typename V>
void GenerationCache<K, V>::attachToCache(sp<Entry<K, V> > entry) {
    if (!mYoungest.get()) {
        mYoungest = mOldest = entry;
    } else {
        entry->parent = mYoungest;
        mYoungest->child = entry;
        mYoungest = entry;
    }
}

template<typename K, typename V>
void GenerationCache<K, V>::detachFromCache(sp<Entry<K, V> > entry) {
    if (entry->parent.get()) {
        entry->parent->child = entry->child;
    }

    if (entry->child.get()) {
        entry->child->parent = entry->parent;
    }

    if (mOldest == entry) {
        mOldest = entry->child;
    }

    if (mYoungest == entry) {
        mYoungest = entry->parent;
    }

    entry->parent.clear();
    entry->child.clear();
}

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_GENERATION_CACHE_H
