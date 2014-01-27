//
// Copyright 2012 The Android Open Source Project
//
// Manage a resource ID cache.

#define LOG_TAG "ResourceIdCache"

#include <utils/String16.h>
#include <utils/Log.h>
#include "ResourceIdCache.h"
#include <map>
using namespace std;


static size_t mHits = 0;
static size_t mMisses = 0;
static size_t mCollisions = 0;

static const size_t MAX_CACHE_ENTRIES = 2048;
static const android::String16 TRUE16("1");
static const android::String16 FALSE16("0");

struct CacheEntry {
    // concatenation of the relevant strings into a single instance
    android::String16 hashedName;
    uint32_t id;

    CacheEntry() {}
    CacheEntry(const android::String16& name, uint32_t resId) : hashedName(name), id(resId) { }
};

static map< uint32_t, CacheEntry > mIdMap;


// djb2; reasonable choice for strings when collisions aren't particularly important
static inline uint32_t hashround(uint32_t hash, int c) {
    return ((hash << 5) + hash) + c;    /* hash * 33 + c */
}

static uint32_t hash(const android::String16& hashableString) {
    uint32_t hash = 5381;
    const char16_t* str = hashableString.string();
    while (int c = *str++) hash = hashround(hash, c);
    return hash;
}

namespace android {

static inline String16 makeHashableName(const android::String16& package,
        const android::String16& type,
        const android::String16& name,
        bool onlyPublic) {
    String16 hashable = String16(name);
    hashable += type;
    hashable += package;
    hashable += (onlyPublic ? TRUE16 : FALSE16);
    return hashable;
}

uint32_t ResourceIdCache::lookup(const android::String16& package,
        const android::String16& type,
        const android::String16& name,
        bool onlyPublic) {
    const String16 hashedName = makeHashableName(package, type, name, onlyPublic);
    const uint32_t hashcode = hash(hashedName);
    map<uint32_t, CacheEntry>::iterator item = mIdMap.find(hashcode);
    if (item == mIdMap.end()) {
        // cache miss
        mMisses++;
        return 0;
    }

    // legit match?
    if (hashedName == (*item).second.hashedName) {
        mHits++;
        return (*item).second.id;
    }

    // collision
    mCollisions++;
    mIdMap.erase(hashcode);
    return 0;
}

// returns the resource ID being stored, for callsite convenience
uint32_t ResourceIdCache::store(const android::String16& package,
        const android::String16& type,
        const android::String16& name,
        bool onlyPublic,
        uint32_t resId) {
    if (mIdMap.size() < MAX_CACHE_ENTRIES) {
        const String16 hashedName = makeHashableName(package, type, name, onlyPublic);
        const uint32_t hashcode = hash(hashedName);
        mIdMap[hashcode] = CacheEntry(hashedName, resId);
    }
    return resId;
}

void ResourceIdCache::dump() {
    printf("ResourceIdCache dump:\n");
    printf("Size: %ld\n", mIdMap.size());
    printf("Hits:   %ld\n", mHits);
    printf("Misses: %ld\n", mMisses);
    printf("(Collisions: %ld)\n", mCollisions);
}

}
