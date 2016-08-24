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

#include "ResourceCache.h"
#include "Caches.h"

namespace android {

using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(ResourceCache);

namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Resource cache
///////////////////////////////////////////////////////////////////////////////

void ResourceCache::logCache() {
    ALOGD("ResourceCache: cacheReport:");
    for (size_t i = 0; i < mCache->size(); ++i) {
        ResourceReference* ref = mCache->valueAt(i);
        ALOGD("  ResourceCache: mCache(%zu): resource, ref = 0x%p, 0x%p",
                i, mCache->keyAt(i), mCache->valueAt(i));
        ALOGD("  ResourceCache: mCache(%zu): refCount, destroyed, type = %d, %d, %d",
                i, ref->refCount, ref->destroyed, ref->resourceType);
    }
}

ResourceCache::ResourceCache() {
    Mutex::Autolock _l(mLock);
    mCache = new KeyedVector<const void*, ResourceReference*>();
}

ResourceCache::~ResourceCache() {
    Mutex::Autolock _l(mLock);
    delete mCache;
}

void ResourceCache::lock() {
    mLock.lock();
}

void ResourceCache::unlock() {
    mLock.unlock();
}

void ResourceCache::incrementRefcount(void* resource, ResourceType resourceType) {
    Mutex::Autolock _l(mLock);
    incrementRefcountLocked(resource, resourceType);
}

void ResourceCache::incrementRefcount(const Res_png_9patch* patchResource) {
    incrementRefcount((void*) patchResource, kNinePatch);
}

void ResourceCache::incrementRefcountLocked(void* resource, ResourceType resourceType) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : nullptr;
    if (ref == nullptr || mCache->size() == 0) {
        ref = new ResourceReference(resourceType);
        mCache->add(resource, ref);
    }
    ref->refCount++;
}

void ResourceCache::decrementRefcount(void* resource) {
    Mutex::Autolock _l(mLock);
    decrementRefcountLocked(resource);
}

void ResourceCache::decrementRefcount(const Res_png_9patch* patchResource) {
    decrementRefcount((void*) patchResource);
}

void ResourceCache::decrementRefcountLocked(void* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : nullptr;
    if (ref == nullptr) {
        // Should not get here - shouldn't get a call to decrement if we're not yet tracking it
        return;
    }
    ref->refCount--;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
    }
}

void ResourceCache::decrementRefcountLocked(const Res_png_9patch* patchResource) {
    decrementRefcountLocked((void*) patchResource);
}

void ResourceCache::destructor(Res_png_9patch* resource) {
    Mutex::Autolock _l(mLock);
    destructorLocked(resource);
}

void ResourceCache::destructorLocked(Res_png_9patch* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : nullptr;
    if (ref == nullptr) {
        // If we're not tracking this resource, just delete it
        if (Caches::hasInstance()) {
            Caches::getInstance().patchCache.removeDeferred(resource);
        } else {
            // A Res_png_9patch is actually an array of byte that's larger
            // than sizeof(Res_png_9patch). It must be freed as an array.
            delete[] (int8_t*) resource;
        }
        return;
    }
    ref->destroyed = true;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
    }
}

/**
 * This method should only be called while the mLock mutex is held (that mutex is grabbed
 * by the various destructor() and recycle() methods which call this method).
 */
void ResourceCache::deleteResourceReferenceLocked(const void* resource, ResourceReference* ref) {
    if (ref->destroyed) {
        switch (ref->resourceType) {
            case kNinePatch: {
                if (Caches::hasInstance()) {
                    Caches::getInstance().patchCache.removeDeferred((Res_png_9patch*) resource);
                } else {
                    // A Res_png_9patch is actually an array of byte that's larger
                    // than sizeof(Res_png_9patch). It must be freed as an array.
                    int8_t* patch = (int8_t*) resource;
                    delete[] patch;
                }
            }
            break;
        }
    }
    mCache->removeItem(resource);
    delete ref;
}

}; // namespace uirenderer
}; // namespace android
