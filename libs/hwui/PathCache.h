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

#ifndef ANDROID_HWUI_PATH_CACHE_H
#define ANDROID_HWUI_PATH_CACHE_H

#include <utils/Thread.h>
#include <utils/Vector.h>

#include "Debug.h"
#include "ShapeCache.h"
#include "thread/Signal.h"
#include "thread/Task.h"
#include "thread/TaskProcessor.h"

class SkPaint;
class SkPath;

namespace android {
namespace uirenderer {

class Caches;

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

struct PathCacheEntry: public ShapeCacheEntry {
    PathCacheEntry(SkPath* path, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapePath, paint) {
        this->path = path;
    }

    PathCacheEntry(): ShapeCacheEntry() {
        path = NULL;
    }

    hash_t hash() const {
        uint32_t hash = ShapeCacheEntry::hash();
        hash = JenkinsHashMix(hash, android::hash_type(path));
        return JenkinsHashWhiten(hash);
    }

    int compare(const ShapeCacheEntry& r) const {
        int deltaInt = ShapeCacheEntry::compare(r);
        if (deltaInt != 0) return deltaInt;

        const PathCacheEntry& rhs = (const PathCacheEntry&) r;
        return path - rhs.path;
    }

    SkPath* path;

}; // PathCacheEntry

inline hash_t hash_type(const PathCacheEntry& entry) {
    return entry.hash();
}

/**
 * A simple LRU path cache. The cache has a maximum size expressed in bytes.
 * Any texture added to the cache causing the cache to grow beyond the maximum
 * allowed size will also cause the oldest texture to be kicked out.
 */
class PathCache: public ShapeCache<PathCacheEntry> {
public:
    PathCache();
    ~PathCache();

    /**
     * Returns the texture associated with the specified path. If the texture
     * cannot be found in the cache, a new texture is generated.
     */
    PathTexture* get(SkPath* path, SkPaint* paint);
    /**
     * Removes an entry.
     */
    void remove(SkPath* path);
    /**
     * Removes the specified path. This is meant to be called from threads
     * that are not the EGL context thread.
     */
    void removeDeferred(SkPath* path);
    /**
     * Process deferred removals.
     */
    void clearGarbage();

    void precache(SkPath* path, SkPaint* paint);

private:
    class PathTask: public Task<SkBitmap*> {
    public:
        PathTask(SkPath* path, SkPaint* paint, PathTexture* texture):
            path(path), paint(paint), texture(texture) {
        }

        ~PathTask() {
            delete future()->get();
        }

        SkPath* path;
        SkPaint* paint;
        PathTexture* texture;
    };

    class PathProcessor: public TaskProcessor<SkBitmap*> {
    public:
        PathProcessor(Caches& caches);
        ~PathProcessor() { }

        virtual void onProcess(const sp<Task<SkBitmap*> >& task);

    private:
        uint32_t mMaxTextureSize;
    };

    sp<PathProcessor> mProcessor;
    Vector<SkPath*> mGarbage;
    mutable Mutex mLock;
}; // class PathCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATH_CACHE_H
