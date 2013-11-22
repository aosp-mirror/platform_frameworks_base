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

#define LOG_TAG "OpenGLRenderer"

#include <SkPixelRef.h>
#include "ResourceCache.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Resource cache
///////////////////////////////////////////////////////////////////////////////

void ResourceCache::logCache() {
    ALOGD("ResourceCache: cacheReport:");
    for (size_t i = 0; i < mCache->size(); ++i) {
        ResourceReference* ref = mCache->valueAt(i);
        ALOGD("  ResourceCache: mCache(%d): resource, ref = 0x%p, 0x%p",
                i, mCache->keyAt(i), mCache->valueAt(i));
        ALOGD("  ResourceCache: mCache(%d): refCount, recycled, destroyed, type = %d, %d, %d, %d",
                i, ref->refCount, ref->recycled, ref->destroyed, ref->resourceType);
    }
}

ResourceCache::ResourceCache() {
    Mutex::Autolock _l(mLock);
    mCache = new KeyedVector<void*, ResourceReference*>();
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

void ResourceCache::incrementRefcount(SkBitmap* bitmapResource) {
    bitmapResource->pixelRef()->globalRef();
    SkSafeRef(bitmapResource->getColorTable());
    incrementRefcount((void*) bitmapResource, kBitmap);
}

void ResourceCache::incrementRefcount(SkPath* pathResource) {
    incrementRefcount((void*) pathResource, kPath);
}

void ResourceCache::incrementRefcount(SkiaShader* shaderResource) {
    SkSafeRef(shaderResource->getSkShader());
    incrementRefcount((void*) shaderResource, kShader);
}

void ResourceCache::incrementRefcount(SkiaColorFilter* filterResource) {
    SkSafeRef(filterResource->getSkColorFilter());
    incrementRefcount((void*) filterResource, kColorFilter);
}

void ResourceCache::incrementRefcount(Res_png_9patch* patchResource) {
    incrementRefcount((void*) patchResource, kNinePatch);
}

void ResourceCache::incrementRefcount(Layer* layerResource) {
    incrementRefcount((void*) layerResource, kLayer);
}

void ResourceCache::incrementRefcountLocked(void* resource, ResourceType resourceType) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : NULL;
    if (ref == NULL || mCache->size() == 0) {
        ref = new ResourceReference(resourceType);
        mCache->add(resource, ref);
    }
    ref->refCount++;
}

void ResourceCache::incrementRefcountLocked(SkBitmap* bitmapResource) {
    bitmapResource->pixelRef()->globalRef();
    SkSafeRef(bitmapResource->getColorTable());
    incrementRefcountLocked((void*) bitmapResource, kBitmap);
}

void ResourceCache::incrementRefcountLocked(SkPath* pathResource) {
    incrementRefcountLocked((void*) pathResource, kPath);
}

void ResourceCache::incrementRefcountLocked(SkiaShader* shaderResource) {
    SkSafeRef(shaderResource->getSkShader());
    incrementRefcountLocked((void*) shaderResource, kShader);
}

void ResourceCache::incrementRefcountLocked(SkiaColorFilter* filterResource) {
    SkSafeRef(filterResource->getSkColorFilter());
    incrementRefcountLocked((void*) filterResource, kColorFilter);
}

void ResourceCache::incrementRefcountLocked(Res_png_9patch* patchResource) {
    incrementRefcountLocked((void*) patchResource, kNinePatch);
}

void ResourceCache::incrementRefcountLocked(Layer* layerResource) {
    incrementRefcountLocked((void*) layerResource, kLayer);
}

void ResourceCache::decrementRefcount(void* resource) {
    Mutex::Autolock _l(mLock);
    decrementRefcountLocked(resource);
}

void ResourceCache::decrementRefcount(SkBitmap* bitmapResource) {
    bitmapResource->pixelRef()->globalUnref();
    SkSafeUnref(bitmapResource->getColorTable());
    decrementRefcount((void*) bitmapResource);
}

void ResourceCache::decrementRefcount(SkPath* pathResource) {
    decrementRefcount((void*) pathResource);
}

void ResourceCache::decrementRefcount(SkiaShader* shaderResource) {
    SkSafeUnref(shaderResource->getSkShader());
    decrementRefcount((void*) shaderResource);
}

void ResourceCache::decrementRefcount(SkiaColorFilter* filterResource) {
    SkSafeUnref(filterResource->getSkColorFilter());
    decrementRefcount((void*) filterResource);
}

void ResourceCache::decrementRefcount(Res_png_9patch* patchResource) {
    decrementRefcount((void*) patchResource);
}

void ResourceCache::decrementRefcount(Layer* layerResource) {
    decrementRefcount((void*) layerResource);
}

void ResourceCache::decrementRefcountLocked(void* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : NULL;
    if (ref == NULL) {
        // Should not get here - shouldn't get a call to decrement if we're not yet tracking it
        return;
    }
    ref->refCount--;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
    }
}

void ResourceCache::decrementRefcountLocked(SkBitmap* bitmapResource) {
    bitmapResource->pixelRef()->globalUnref();
    SkSafeUnref(bitmapResource->getColorTable());
    decrementRefcountLocked((void*) bitmapResource);
}

void ResourceCache::decrementRefcountLocked(SkPath* pathResource) {
    decrementRefcountLocked((void*) pathResource);
}

void ResourceCache::decrementRefcountLocked(SkiaShader* shaderResource) {
    SkSafeUnref(shaderResource->getSkShader());
    decrementRefcountLocked((void*) shaderResource);
}

void ResourceCache::decrementRefcountLocked(SkiaColorFilter* filterResource) {
    SkSafeUnref(filterResource->getSkColorFilter());
    decrementRefcountLocked((void*) filterResource);
}

void ResourceCache::decrementRefcountLocked(Res_png_9patch* patchResource) {
    decrementRefcountLocked((void*) patchResource);
}

void ResourceCache::decrementRefcountLocked(Layer* layerResource) {
    decrementRefcountLocked((void*) layerResource);
}

void ResourceCache::destructor(SkPath* resource) {
    Mutex::Autolock _l(mLock);
    destructorLocked(resource);
}

void ResourceCache::destructorLocked(SkPath* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : NULL;
    if (ref == NULL) {
        // If we're not tracking this resource, just delete it
        if (Caches::hasInstance()) {
            Caches::getInstance().pathCache.removeDeferred(resource);
        }
        delete resource;
        return;
    }
    ref->destroyed = true;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
    }
}

void ResourceCache::destructor(SkBitmap* resource) {
    Mutex::Autolock _l(mLock);
    destructorLocked(resource);
}

void ResourceCache::destructorLocked(SkBitmap* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : NULL;
    if (ref == NULL) {
        // If we're not tracking this resource, just delete it
        if (Caches::hasInstance()) {
            Caches::getInstance().textureCache.removeDeferred(resource);
        }
        delete resource;
        return;
    }
    ref->destroyed = true;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
    }
}

void ResourceCache::destructor(SkiaShader* resource) {
    Mutex::Autolock _l(mLock);
    destructorLocked(resource);
}

void ResourceCache::destructorLocked(SkiaShader* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : NULL;
    if (ref == NULL) {
        // If we're not tracking this resource, just delete it
        delete resource;
        return;
    }
    ref->destroyed = true;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
    }
}

void ResourceCache::destructor(SkiaColorFilter* resource) {
    Mutex::Autolock _l(mLock);
    destructorLocked(resource);
}

void ResourceCache::destructorLocked(SkiaColorFilter* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : NULL;
    if (ref == NULL) {
        // If we're not tracking this resource, just delete it
        delete resource;
        return;
    }
    ref->destroyed = true;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
    }
}

void ResourceCache::destructor(Res_png_9patch* resource) {
    Mutex::Autolock _l(mLock);
    destructorLocked(resource);
}

void ResourceCache::destructorLocked(Res_png_9patch* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    ResourceReference* ref = index >= 0 ? mCache->valueAt(index) : NULL;
    if (ref == NULL) {
        if (Caches::hasInstance()) {
            Caches::getInstance().patchCache.removeDeferred(resource);
        }
        // If we're not tracking this resource, just delete it
        // A Res_png_9patch is actually an array of byte that's larger
        // than sizeof(Res_png_9patch). It must be freed as an array.
        delete[] (int8_t*) resource;
        return;
    }
    ref->destroyed = true;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
    }
}

/**
 * Return value indicates whether resource was actually recycled, which happens when RefCnt
 * reaches 0.
 */
bool ResourceCache::recycle(SkBitmap* resource) {
    Mutex::Autolock _l(mLock);
    return recycleLocked(resource);
}

/**
 * Return value indicates whether resource was actually recycled, which happens when RefCnt
 * reaches 0.
 */
bool ResourceCache::recycleLocked(SkBitmap* resource) {
    ssize_t index = mCache->indexOfKey(resource);
    if (index < 0) {
        // not tracking this resource; just recycle the pixel data
        resource->setPixels(NULL, NULL);
        return true;
    }
    ResourceReference* ref = mCache->valueAt(index);
    if (ref == NULL) {
        // Should not get here - shouldn't get a call to recycle if we're not yet tracking it
        return true;
    }
    ref->recycled = true;
    if (ref->refCount == 0) {
        deleteResourceReferenceLocked(resource, ref);
        return true;
    }
    // Still referring to resource, don't recycle yet
    return false;
}

/**
 * This method should only be called while the mLock mutex is held (that mutex is grabbed
 * by the various destructor() and recycle() methods which call this method).
 */
void ResourceCache::deleteResourceReferenceLocked(void* resource, ResourceReference* ref) {
    if (ref->recycled && ref->resourceType == kBitmap) {
        ((SkBitmap*) resource)->setPixels(NULL, NULL);
    }
    if (ref->destroyed || ref->resourceType == kLayer) {
        switch (ref->resourceType) {
            case kBitmap: {
                SkBitmap* bitmap = (SkBitmap*) resource;
                if (Caches::hasInstance()) {
                    Caches::getInstance().textureCache.removeDeferred(bitmap);
                }
                delete bitmap;
            }
            break;
            case kPath: {
                SkPath* path = (SkPath*) resource;
                if (Caches::hasInstance()) {
                    Caches::getInstance().pathCache.removeDeferred(path);
                }
                delete path;
            }
            break;
            case kShader: {
                SkiaShader* shader = (SkiaShader*) resource;
                delete shader;
            }
            break;
            case kColorFilter: {
                SkiaColorFilter* filter = (SkiaColorFilter*) resource;
                delete filter;
            }
            break;
            case kNinePatch: {
                if (Caches::hasInstance()) {
                    Caches::getInstance().patchCache.removeDeferred((Res_png_9patch*) resource);
                }
                // A Res_png_9patch is actually an array of byte that's larger
                // than sizeof(Res_png_9patch). It must be freed as an array.
                int8_t* patch = (int8_t*) resource;
                delete[] patch;
            }
            break;
            case kLayer: {
                Layer* layer = (Layer*) resource;
                Caches::getInstance().deleteLayerDeferred(layer);
            }
            break;
        }
    }
    mCache->removeItem(resource);
    delete ref;
}

}; // namespace uirenderer
}; // namespace android
