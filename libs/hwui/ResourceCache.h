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

#include <utils/KeyedVector.h>
#include <utils/Singleton.h>

#include <androidfw/ResourceTypes.h>

#include "Layer.h"

namespace android {
namespace uirenderer {

/**
 * Type of Resource being cached
 */
enum ResourceType {
    kBitmap,
    kNinePatch,
    kPath
};

class ResourceReference {
public:

    ResourceReference(ResourceType type) {
        refCount = 0; recycled = false; destroyed = false; resourceType = type;
    }

    int refCount;
    bool recycled;
    bool destroyed;
    ResourceType resourceType;
};

class ANDROID_API ResourceCache: public Singleton<ResourceCache> {
    ResourceCache();
    ~ResourceCache();

    friend class Singleton<ResourceCache>;

public:

    /**
     * When using these two methods, make sure to only invoke the *Locked()
     * variants of increment/decrementRefcount(), recyle() and destructor()
     */
    void lock();
    void unlock();

    void incrementRefcount(const SkPath* resource);
    void incrementRefcount(const SkBitmap* resource);
    void incrementRefcount(const Res_png_9patch* resource);

    void incrementRefcountLocked(const SkPath* resource);
    void incrementRefcountLocked(const SkBitmap* resource);
    void incrementRefcountLocked(const Res_png_9patch* resource);

    void decrementRefcount(const SkBitmap* resource);
    void decrementRefcount(const SkPath* resource);
    void decrementRefcount(const Res_png_9patch* resource);

    void decrementRefcountLocked(const SkBitmap* resource);
    void decrementRefcountLocked(const SkPath* resource);
    void decrementRefcountLocked(const Res_png_9patch* resource);

    void destructor(SkPath* resource);
    void destructor(const SkBitmap* resource);
    void destructor(Res_png_9patch* resource);

    void destructorLocked(SkPath* resource);
    void destructorLocked(const SkBitmap* resource);
    void destructorLocked(Res_png_9patch* resource);

    bool recycle(SkBitmap* resource);
    bool recycleLocked(SkBitmap* resource);

private:
    void deleteResourceReferenceLocked(const void* resource, ResourceReference* ref);

    void incrementRefcount(void* resource, ResourceType resourceType);
    void incrementRefcountLocked(void* resource, ResourceType resourceType);

    void decrementRefcount(void* resource);
    void decrementRefcountLocked(void* resource);

    void logCache();

    /**
     * Used to increment, decrement, and destroy. Incrementing is generally accessed on the UI
     * thread, but destroying resources may be called from the GC thread, the finalizer thread,
     * or a reference queue finalization thread.
     */
    mutable Mutex mLock;

    KeyedVector<const void*, ResourceReference*>* mCache;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RESOURCE_CACHE_H
