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
    Entry(const Entry<EntryKey, EntryValue>& e) :
            key(e.key), value(e.value),
            parent(e.parent), child(e.child) { }
    Entry(const EntryKey& key, const EntryValue& value) :
            key(key), value(value) { }

    EntryKey key;
    EntryValue value;

    sp<Entry<EntryKey, EntryValue> > parent; // next older entry
    sp<Entry<EntryKey, EntryValue> > child;  // next younger entry
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

    size_t size() const;

    void clear();

    bool contains(const K& key) const;
    const K& getKeyAt(size_t index) const;
    const V& getValueAt(size_t index) const;

    const V& get(const K& key);
    bool put(const K& key, const V& value);

    void removeAt(ssize_t index);
    bool remove(const K& key);
    bool removeOldest();

private:
    KeyedVector<K, sp<Entry<K, V> > > mCache;
    uint32_t mMaxCapacity;

    OnEntryRemoved<K, V>* mListener;

    sp<Entry<K, V> > mOldest;
    sp<Entry<K, V> > mYoungest;

    void attachToCache(const sp<Entry<K, V> >& entry);
    void detachFromCache(const sp<Entry<K, V> >& entry);

    const V mNullValue;
}; // class GenerationCache

template<typename K, typename V>
GenerationCache<K, V>::GenerationCache(uint32_t maxCapacity): mMaxCapacity(maxCapacity),
    mListener(NULL), mNullValue(NULL) {
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
bool GenerationCache<K, V>::contains(const K& key) const {
    return mCache.indexOfKey(key) >= 0;
}

template<typename K, typename V>
const K& GenerationCache<K, V>::getKeyAt(size_t index) const {
    return mCache.keyAt(index);
}

template<typename K, typename V>
const V& GenerationCache<K, V>::getValueAt(size_t index) const {
    return mCache.valueAt(index)->value;
}

template<typename K, typename V>
const V& GenerationCache<K, V>::get(const K& key) {
    ssize_t index = mCache.indexOfKey(key);
    if (index >= 0) {
        const sp<Entry<K, V> >& entry = mCache.valueAt(index);
        detachFromCache(entry);
        attachToCache(entry);
        return entry->value;
    }

    return mNullValue;
}

template<typename K, typename V>
bool GenerationCache<K, V>::put(const K& key, const V& value) {
    if (mMaxCapacity != kUnlimitedCapacity && mCache.size() >= mMaxCapacity) {
        removeOldest();
    }

    ssize_t index = mCache.indexOfKey(key);
    if (index < 0) {
        sp<Entry<K, V> > entry = new Entry<K, V>(key, value);
        mCache.add(key, entry);
        attachToCache(entry);
        return true;
    }

    return false;
}

template<typename K, typename V>
bool GenerationCache<K, V>::remove(const K& key) {
    ssize_t index = mCache.indexOfKey(key);
    if (index >= 0) {
        removeAt(index);
        return true;
    }

    return false;
}

template<typename K, typename V>
void GenerationCache<K, V>::removeAt(ssize_t index) {
    sp<Entry<K, V> > entry = mCache.valueAt(index);
    if (mListener) {
        (*mListener)(entry->key, entry->value);
    }
    mCache.removeItemsAt(index, 1);
    detachFromCache(entry);
}

template<typename K, typename V>
bool GenerationCache<K, V>::removeOldest() {
    if (mOldest.get()) {
        ssize_t index = mCache.indexOfKey(mOldest->key);
        if (index >= 0) {
            removeAt(index);
            return true;
        }
        LOGE("GenerationCache: removeOldest failed to find the item in the cache "
                "with the given key, but we know it must be in there.  "
                "Is the key comparator kaput?");
    }

    return false;
}

template<typename K, typename V>
void GenerationCache<K, V>::attachToCache(const sp<Entry<K, V> >& entry) {
    if (!mYoungest.get()) {
        mYoungest = mOldest = entry;
    } else {
        entry->parent = mYoungest;
        mYoungest->child = entry;
        mYoungest = entry;
    }
}

template<typename K, typename V>
void GenerationCache<K, V>::detachFromCache(const sp<Entry<K, V> >& entry) {
    if (entry->parent.get()) {
        entry->parent->child = entry->child;
    } else {
        mOldest = entry->child;
    }

    if (entry->child.get()) {
        entry->child->parent = entry->parent;
    } else {
        mYoungest = entry->parent;
    }

    entry->parent.clear();
    entry->child.clear();
}

}; // namespace android

#endif // ANDROID_UTILS_GENERATION_CACHE_H
