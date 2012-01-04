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
    mCache = new KeyedVector<void *, ResourceReference *>();
}

ResourceCache::~ResourceCache() {
    Mutex::Autolock _l(mLock);
    delete mCache;
}

void ResourceCache::incrementRefcount(void* resource, ResourceType resourceType) {
    Mutex::Autolock _l(mLock);
    ResourceReference* ref = mCache->indexOfKey(resource) >= 0 ? mCache->valueFor(resource) : NULL;
    if (ref == NULL || mCache->size() == 0) {
        ref = new ResourceReference(resourceType);
        mCache->add(resource, ref);
    }
    ref->refCount++;
}

void ResourceCache::incrementRefcount(SkBitmap* bitmapResource) {
    SkSafeRef(bitmapResource->pixelRef());
    SkSafeRef(bitmapResource->getColorTable());
    incrementRefcount((void*)bitmapResource, kBitmap);
}

void ResourceCache::incrementRefcount(SkPath* pathResource) {
    incrementRefcount((void*)pathResource, kPath);
}

void ResourceCache::incrementRefcount(SkiaShader* shaderResource) {
    SkSafeRef(shaderResource->getSkShader());
    incrementRefcount((void*) shaderResource, kShader);
}

void ResourceCache::incrementRefcount(SkiaColorFilter* filterResource) {
    SkSafeRef(filterResource->getSkColorFilter());
    incrementRefcount((void*) filterResource, kColorFilter);
}

void ResourceCache::decrementRefcount(void* resource) {
    Mutex::Autolock _l(mLock);
    ResourceReference* ref = mCache->indexOfKey(resource) >= 0 ? mCache->valueFor(resource) : NULL;
    if (ref == NULL) {
        // Should not get here - shouldn't get a call to decrement if we're not yet tracking it
        return;
    }
    ref->refCount--;
    if (ref->refCount == 0) {
        deleteResourceReference(resource, ref);
    }
}

void ResourceCache::decrementRefcount(SkBitmap* bitmapResource) {
    SkSafeUnref(bitmapResource->pixelRef());
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

void ResourceCache::recycle(SkBitmap* resource) {
    Mutex::Autolock _l(mLock);
    if (mCache->indexOfKey(resource) < 0) {
        // not tracking this resource; just recycle the pixel data
        resource->setPixels(NULL, NULL);
        return;
    }
    ResourceReference* ref = mCache->indexOfKey(resource) >= 0 ? mCache->valueFor(resource) : NULL;
    if (ref == NULL) {
        // Should not get here - shouldn't get a call to recycle if we're not yet tracking it
        return;
    }
    ref->recycled = true;
    if (ref->refCount == 0) {
        deleteResourceReference(resource, ref);
    }
}

void ResourceCache::destructor(SkPath* resource) {
    Mutex::Autolock _l(mLock);
    ResourceReference* ref = mCache->indexOfKey(resource) >= 0 ? mCache->valueFor(resource) : NULL;
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
        deleteResourceReference(resource, ref);
    }
}

void ResourceCache::destructor(SkBitmap* resource) {
    Mutex::Autolock _l(mLock);
    ResourceReference* ref = mCache->indexOfKey(resource) >= 0 ? mCache->valueFor(resource) : NULL;
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
        deleteResourceReference(resource, ref);
    }
}

void ResourceCache::destructor(SkiaShader* resource) {
    Mutex::Autolock _l(mLock);
    ResourceReference* ref = mCache->indexOfKey(resource) >= 0 ? mCache->valueFor(resource) : NULL;
    if (ref == NULL) {
        // If we're not tracking this resource, just delete it
        delete resource;
        return;
    }
    ref->destroyed = true;
    if (ref->refCount == 0) {
        deleteResourceReference(resource, ref);
    }
}

void ResourceCache::destructor(SkiaColorFilter* resource) {
    Mutex::Autolock _l(mLock);
    ResourceReference* ref = mCache->indexOfKey(resource) >= 0 ? mCache->valueFor(resource) : NULL;
    if (ref == NULL) {
        // If we're not tracking this resource, just delete it
        delete resource;
        return;
    }
    ref->destroyed = true;
    if (ref->refCount == 0) {
        deleteResourceReference(resource, ref);
    }
}

/**
 * This method should only be called while the mLock mutex is held (that mutex is grabbed
 * by the various destructor() and recycle() methods which call this method).
 */
void ResourceCache::deleteResourceReference(void* resource, ResourceReference* ref) {
    if (ref->recycled && ref->resourceType == kBitmap) {
        ((SkBitmap*) resource)->setPixels(NULL, NULL);
    }
    if (ref->destroyed) {
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
        }
    }
    mCache->removeItem(resource);
    delete ref;
}

}; // namespace uirenderer
}; // namespace android
