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

#ifndef ANDROID_HWUI_RESOURCE_CACHE_H
#define ANDROID_HWUI_RESOURCE_CACHE_H

#include <cutils/compiler.h>

#include <SkBitmap.h>
#include <SkiaColorFilter.h>
#include <SkiaShader.h>
#include <utils/KeyedVector.h>

namespace android {
namespace uirenderer {

/**
 * Type of Resource being cached
 */
enum ResourceType {
    kBitmap,
    kShader,
    kColorFilter,
    kPath,
};

class ResourceReference {
public:

    ResourceReference() { refCount = 0; recycled = false; destroyed = false;}
    ResourceReference(ResourceType type) {
        refCount = 0; recycled = false; destroyed = false; resourceType = type;
    }

    int refCount;
    bool recycled;
    bool destroyed;
    ResourceType resourceType;
};

class ANDROID_API ResourceCache {
    KeyedVector<void *, ResourceReference *>* mCache;
public:
    ResourceCache();
    ~ResourceCache();
    void incrementRefcount(SkPath* resource);
    void incrementRefcount(SkBitmap* resource);
    void incrementRefcount(SkiaShader* resource);
    void incrementRefcount(SkiaColorFilter* resource);
    void incrementRefcount(const void* resource, ResourceType resourceType);
    void decrementRefcount(void* resource);
    void decrementRefcount(SkBitmap* resource);
    void decrementRefcount(SkPath* resource);
    void decrementRefcount(SkiaShader* resource);
    void decrementRefcount(SkiaColorFilter* resource);
    void recycle(SkBitmap* resource);
    void destructor(SkPath* resource);
    void destructor(SkBitmap* resource);
    void destructor(SkiaShader* resource);
    void destructor(SkiaColorFilter* resource);
private:
    void deleteResourceReference(void* resource, ResourceReference* ref);
    void incrementRefcount(void* resource, ResourceType resourceType);
    void logCache();

    /**
     * Used to increment, decrement, and destroy. Incrementing is generally accessed on the UI
     * thread, but destroying resources may be called from the GC thread, the finalizer thread,
     * or a reference queue finalization thread.
     */
    mutable Mutex mLock;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RESOURCE_CACHE_H
