//
// Copyright 2010 The Android Open Source Project
//
// A simple memory pool.
//
#define LOG_TAG "Pool"

//#define LOG_NDEBUG 0

#include <cutils/log.h>
#include <utils/Pool.h>

#include <stdlib.h>

namespace android {

// TODO Provide a real implementation of a pool.  This is just a stub for initial development.

PoolImpl::PoolImpl(size_t objSize) :
    mObjSize(objSize) {
}

PoolImpl::~PoolImpl() {
}

void* PoolImpl::allocImpl() {
    void* ptr = malloc(mObjSize);
    LOG_ALWAYS_FATAL_IF(ptr == NULL, "Cannot allocate new pool object.");
    return ptr;
}

void PoolImpl::freeImpl(void* obj) {
    LOG_ALWAYS_FATAL_IF(obj == NULL, "Caller attempted to free NULL pool object.");
    return free(obj);
}

} // namespace android
