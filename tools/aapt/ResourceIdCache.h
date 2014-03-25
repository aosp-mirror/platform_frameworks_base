//
// Copyright 2012 The Android Open Source Project
//
// Manage a resource ID cache.

#ifndef RESOURCE_ID_CACHE_H
#define RESOURCE_ID_CACHE_H

#include <utils/String16.h>

namespace android {

class ResourceIdCache {
public:
    static uint32_t lookup(const String16& package,
            const String16& type,
            const String16& name,
            bool onlyPublic);

    static uint32_t store(const String16& package,
            const String16& type,
            const String16& name,
            bool onlyPublic,
            uint32_t resId);

    static void dump(void);
};

}

#endif
