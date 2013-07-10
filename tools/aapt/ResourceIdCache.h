//
// Copyright 2012 The Android Open Source Project
//
// Manage a resource ID cache.

#ifndef RESOURCE_ID_CACHE_H
#define RESOURCE_ID_CACHE_H

namespace android {
class String16;

class ResourceIdCache {
public:
    static uint32_t lookup(const android::String16& package,
            const android::String16& type,
            const android::String16& name,
            bool onlyPublic);

    static uint32_t store(const android::String16& package,
            const android::String16& type,
            const android::String16& name,
            bool onlyPublic,
            uint32_t resId);

    static void dump(void);
};

}

#endif
