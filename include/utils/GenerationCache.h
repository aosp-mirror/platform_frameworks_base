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

#ifndef ANDROID_UTILS_GENERATION_CACHE_H
#define ANDROID_UTILS_GENERATION_CACHE_H

#include <utils/KeyedVector.h>
#include <utils/RefBase.h>

namespace android {

/**
 * GenerationCache callback used when an item is removed
 */
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
            key(e.key), value(e.value), parent(e.parent), child(e.child) { }
    Entry(sp<Entry<EntryKey, EntryValue> > e):
            key(e->key), value(e->value), parent(e->parent), child(e->child) { }

    EntryKey key;
    EntryValue value;

    sp<Entry<EntryKey, EntryValue> > parent;
    sp<Entry<EntryKey, EntryValue> > child;
}; // struct Entry

/**
 * A LRU type cache
 */
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
    K getKeyAt(uint32_t index) const;
    bool put(K key, V value);
    V remove(K key);
    V removeOldest();
    V getValueAt(uint32_t index) const;

    uint32_t size() const;

    void addToCache(sp<Entry<K, V> > entry, K key, V value);
    void attachToCache(sp<Entry<K, V> > entry);
    void detachFromCache(sp<Entry<K, V> > entry);

    V removeAt(ssize_t index);

private:
    KeyedVector<K, sp<Entry<K, V> > > mCache;
    uint32_t mMaxCapacity;

    OnEntryRemoved<K, V>* mListener;

    sp<Entry<K, V> > mOldest;
    sp<Entry<K, V> > mYoungest;
}; // class GenerationCache

template<typename K, typename V>
GenerationCache<K, V>::GenerationCache(uint32_t maxCapacity): mMaxCapacity(maxCapacity),
    mListener(NULL) {
};

template<typename K, typename V>
GenerationCache<K, V>::~GenerationCache() {
    clear();
};

template<typename K, typename V>
uint32_t GenerationCache<K, V>::size() const {
    return mCache.size();
}

/**
 * Should be set by the user of the Cache so that the callback is called whenever an item is
 * removed from the cache
 */
template<typename K, typename V>
void GenerationCache<K, V>::setOnEntryRemovedListener(OnEntryRemoved<K, V>* listener) {
    mListener = listener;
}

template<typename K, typename V>
void GenerationCache<K, V>::clear() {
    if (mListener) {
        for (uint32_t i = 0; i < mCache.size(); i++) {
            sp<Entry<K, V> > entry = mCache.valueAt(i);
            if (mListener) {
                (*mListener)(entry->key, entry->value);
            }
        }
    }
    mCache.clear();
    mYoungest.clear();
    mOldest.clear();
}

template<typename K, typename V>
bool GenerationCache<K, V>::contains(K key) const {
    return mCache.indexOfKey(key) >= 0;
}

template<typename K, typename V>
K GenerationCache<K, V>::getKeyAt(uint32_t index) const {
    return mCache.keyAt(index);
}

template<typename K, typename V>
V GenerationCache<K, V>::getValueAt(uint32_t index) const {
    return mCache.valueAt(index)->value;
}

template<typename K, typename V>
V GenerationCache<K, V>::get(K key) {
    ssize_t index = mCache.indexOfKey(key);
    if (index >= 0) {
        sp<Entry<K, V> > entry = mCache.valueAt(index);
        if (entry.get()) {
            detachFromCache(entry);
            attachToCache(entry);
            return entry->value;
        }
    }

    return NULL;
}

template<typename K, typename V>
bool GenerationCache<K, V>::put(K key, V value) {
    if (mMaxCapacity != kUnlimitedCapacity && mCache.size() >= mMaxCapacity) {
        removeOldest();
    }

    ssize_t index = mCache.indexOfKey(key);
    if (index < 0) {
        sp<Entry<K, V> > entry = new Entry<K, V>;
        addToCache(entry, key, value);
        return true;
    }

    return false;
}

template<typename K, typename V>
void GenerationCache<K, V>::addToCache(sp<Entry<K, V> > entry, K key, V value) {
    entry->key = key;
    entry->value = value;
    mCache.add(key, entry);
    attachToCache(entry);
}

template<typename K, typename V>
V GenerationCache<K, V>::remove(K key) {
    ssize_t index = mCache.indexOfKey(key);
    if (index >= 0) {
        return removeAt(index);
    }

    return NULL;
}

template<typename K, typename V>
V GenerationCache<K, V>::removeAt(ssize_t index) {
    sp<Entry<K, V> > entry = mCache.valueAt(index);
    if (mListener) {
        (*mListener)(entry->key, entry->value);
    }
    mCache.removeItemsAt(index, 1);
    detachFromCache(entry);

    return entry->value;
}

template<typename K, typename V>
V GenerationCache<K, V>::removeOldest() {
    if (mOldest.get()) {
        ssize_t index = mCache.indexOfKey(mOldest->key);
        if (index >= 0) {
            return removeAt(index);
        }
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

}; // namespace android

#endif // ANDROID_UTILS_GENERATION_CACHE_H
