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

namespace android {
namespace uirenderer {

template<typename EntryKey, typename EntryValue>
class OnEntryRemoved {
public:
    virtual ~OnEntryRemoved() { };
    virtual void operator()(EntryKey key, EntryValue value) = 0;
}; // class OnEntryRemoved

template<typename K, typename V>
class GenerationCache {
public:
    GenerationCache(unsigned int maxCapacity): mMaxCapacity(maxCapacity), mListener(NULL) { };
    ~GenerationCache() { clear(); };

    void setOnEntryRemovedListener(OnEntryRemoved<K*, V*>* listener);

    void clear();

    bool contains(K* key) const;
    V* get(K* key);
    void put(K* key, V* value);
    V* remove(K* key);

    unsigned int size() const;

private:
    void removeOldest();

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

    void addToCache(sp<Entry<K*, V*> > entry, K* key, V* value);
    void attachToCache(sp<Entry<K*, V*> > entry);
    void detachFromCache(sp<Entry<K*, V*> > entry);

    unsigned int mMaxCapacity;

    OnEntryRemoved<K*, V*>* mListener;

    KeyedVector<K*, sp<Entry<K*, V*> > > mCache;

    sp<Entry<K*, V*> > mOldest;
    sp<Entry<K*, V*> > mYougest;
}; // class GenerationCache

template<typename K, typename V>
unsigned int GenerationCache<K, V>::size() const {
    return mCache.size();
}

template<typename K, typename V>
void GenerationCache<K, V>::setOnEntryRemovedListener(OnEntryRemoved<K*, V*>* listener) {
    mListener = listener;
}

template<typename K, typename V>
void GenerationCache<K, V>::clear() {
    if (mListener) {
        while (mCache.size() > 0) {
            removeOldest();
        }
    } else {
        mCache.clear();
    }
    mYougest.clear();
    mOldest.clear();
}

template<typename K, typename V>
bool GenerationCache<K, V>::contains(K* key) const {
    return mCache.indexOfKey(key) >= 0;
}

template<typename K, typename V>
V* GenerationCache<K, V>::get(K* key) {
    ssize_t index = mCache.indexOfKey(key);
    if (index >= 0) {
        sp<Entry<K*, V*> > entry = mCache.valueAt(index);
        if (entry.get()) {
            detachFromCache(entry);
            attachToCache(entry);
            return entry->value;
        }
    }

    return NULL;
}

template<typename K, typename V>
void GenerationCache<K, V>::put(K* key, V* value) {
    if (mCache.size() >= mMaxCapacity) {
        removeOldest();
    }

    ssize_t index = mCache.indexOfKey(key);
    if (index >= 0) {
        sp<Entry<K*, V*> > entry = mCache.valueAt(index);
        detachFromCache(entry);
        addToCache(entry, key, value);
    } else {
        sp<Entry<K*, V*> > entry = new Entry<K*, V*>;
        addToCache(entry, key, value);
    }
}

template<typename K, typename V>
void GenerationCache<K, V>::addToCache(sp<Entry<K*, V*> > entry, K* key, V* value) {
    entry->key = key;
    entry->value = value;
    mCache.add(key, entry);
    attachToCache(entry);
}

template<typename K, typename V>
V* GenerationCache<K, V>::remove(K* key) {
    ssize_t index = mCache.indexOfKey(key);
    if (index >= 0) {
        sp<Entry<K*, V*> > entry = mCache.valueAt(index);
        if (mListener) {
            (*mListener)(entry->key, entry->value);
        }
        mCache.removeItemsAt(index, 1);
        detachFromCache(entry);
    }

    return NULL;
}

template<typename K, typename V>
void GenerationCache<K, V>::removeOldest() {
    if (mOldest.get()) {
        remove(mOldest->key);
    }
}

template<typename K, typename V>
void GenerationCache<K, V>::attachToCache(sp<Entry<K*, V*> > entry) {
    if (!mYougest.get()) {
        mYougest = mOldest = entry;
    } else {
        entry->parent = mYougest;
        mYougest->child = entry;
        mYougest = entry;
    }
}

template<typename K, typename V>
void GenerationCache<K, V>::detachFromCache(sp<Entry<K*, V*> > entry) {
    if (entry->parent.get()) {
        entry->parent->child = entry->child;
    }

    if (entry->child.get()) {
        entry->child->parent = entry->parent;
    }

    if (mOldest == entry) {
        mOldest = entry->child;
    }

    if (mYougest == entry) {
        mYougest = entry->parent;
    }

    entry->parent.clear();
    entry->child.clear();
}

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_GENERATION_CACHE_H
