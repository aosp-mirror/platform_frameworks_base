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

#include <utils/Mutex.h>

#include <sys/sysinfo.h>

#include "Caches.h"
#include "PathCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Path precaching
///////////////////////////////////////////////////////////////////////////////

PathCache::PathProcessor::PathProcessor(Caches& caches):
        TaskProcessor<SkBitmap*>(&caches.tasks), mMaxTextureSize(caches.maxTextureSize) {
}

void PathCache::PathProcessor::onProcess(const sp<Task<SkBitmap*> >& task) {
    sp<PathTask> t = static_cast<PathTask* >(task.get());
    ATRACE_NAME("pathPrecache");

    float left, top, offset;
    uint32_t width, height;
    PathCache::computePathBounds(t->path, t->paint, left, top, offset, width, height);

    PathTexture* texture = t->texture;
    texture->left = left;
    texture->top = top;
    texture->offset = offset;
    texture->width = width;
    texture->height = height;

    if (width <= mMaxTextureSize && height <= mMaxTextureSize) {
        SkBitmap* bitmap = new SkBitmap();
        PathCache::drawPath(t->path, t->paint, *bitmap, left, top, offset, width, height);
        t->setResult(bitmap);
    } else {
        texture->width = 0;
        texture->height = 0;
        t->setResult(NULL);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Path cache
///////////////////////////////////////////////////////////////////////////////

PathCache::PathCache(): ShapeCache<PathCacheEntry>("path",
        PROPERTY_PATH_CACHE_SIZE, DEFAULT_PATH_CACHE_SIZE) {
}

PathCache::~PathCache() {
}

void PathCache::remove(SkPath* path) {
    Vector<PathCacheEntry> pathsToRemove;
    LruCache<PathCacheEntry, PathTexture*>::Iterator i(mCache);

    while (i.next()) {
        const PathCacheEntry& key = i.key();
        if (key.path == path || key.path == path->getSourcePath()) {
            pathsToRemove.push(key);
        }
    }

    for (size_t i = 0; i < pathsToRemove.size(); i++) {
        mCache.remove(pathsToRemove.itemAt(i));
    }
}

void PathCache::removeDeferred(SkPath* path) {
    Mutex::Autolock l(mLock);
    mGarbage.push(path);
}

void PathCache::clearGarbage() {
    Mutex::Autolock l(mLock);
    size_t count = mGarbage.size();
    for (size_t i = 0; i < count; i++) {
        remove(mGarbage.itemAt(i));
    }
    mGarbage.clear();
}

/**
 * To properly handle path mutations at draw time we always make a copy
 * of paths objects when recording display lists. The source path points
 * to the path we originally copied the path from. This ensures we use
 * the original path as a cache key the first time a path is inserted
 * in the cache. The source path is also used to reclaim garbage when a
 * Dalvik Path object is collected.
 */
static SkPath* getSourcePath(SkPath* path) {
    const SkPath* sourcePath = path->getSourcePath();
    if (sourcePath && sourcePath->getGenerationID() == path->getGenerationID()) {
        return const_cast<SkPath*>(sourcePath);
    }
    return path;
}

PathTexture* PathCache::get(SkPath* path, SkPaint* paint) {
    path = getSourcePath(path);

    PathCacheEntry entry(path, paint);
    PathTexture* texture = mCache.get(entry);

    if (!texture) {
        texture = addTexture(entry, path, paint);
    } else {
        // A bitmap is attached to the texture, this means we need to
        // upload it as a GL texture
        const sp<Task<SkBitmap*> >& task = texture->task();
        if (task != NULL) {
            // But we must first wait for the worker thread to be done
            // producing the bitmap, so let's wait
            SkBitmap* bitmap = task->getResult();
            if (bitmap) {
                addTexture(entry, bitmap, texture);
                texture->clearTask();
            } else {
                ALOGW("Path too large to be rendered into a texture");
                texture->clearTask();
                texture = NULL;
                mCache.remove(entry);
            }
        } else if (path->getGenerationID() != texture->generation) {
            mCache.remove(entry);
            texture = addTexture(entry, path, paint);
        }
    }

    return texture;
}

void PathCache::precache(SkPath* path, SkPaint* paint) {
    if (!Caches::getInstance().tasks.canRunTasks()) {
        return;
    }

    path = getSourcePath(path);

    PathCacheEntry entry(path, paint);
    PathTexture* texture = mCache.get(entry);

    bool generate = false;
    if (!texture) {
        generate = true;
    } else if (path->getGenerationID() != texture->generation) {
        mCache.remove(entry);
        generate = true;
    }

    if (generate) {
        // It is important to specify the generation ID so we do not
        // attempt to precache the same path several times
        texture = createTexture(0.0f, 0.0f, 0.0f, 0, 0, path->getGenerationID());
        sp<PathTask> task = new PathTask(path, paint, texture);
        texture->setTask(task);

        // During the precaching phase we insert path texture objects into
        // the cache that do not point to any GL texture. They are instead
        // treated as a task for the precaching worker thread. This is why
        // we do not check the cache limit when inserting these objects.
        // The conversion into GL texture will happen in get(), when a client
        // asks for a path texture. This is also when the cache limit will
        // be enforced.
        mCache.put(entry, texture);

        if (mProcessor == NULL) {
            mProcessor = new PathProcessor(Caches::getInstance());
        }
        mProcessor->add(task);
    }
}

}; // namespace uirenderer
}; // namespace android
