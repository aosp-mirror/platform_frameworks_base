/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "Debug.h"
#include "Texture.h"
#include "thread/Task.h"
#include "thread/TaskProcessor.h"
#include "utils/Macros.h"
#include "utils/Pair.h"

#include <GLES2/gl2.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <utils/LruCache.h>
#include <utils/Mutex.h>

#include <vector>

class SkBitmap;
class SkCanvas;
class SkPaint;
struct SkRect;

namespace android {
namespace uirenderer {

class Caches;

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_PATHS
    #define PATH_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define PATH_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

/**
 * Alpha texture used to represent a path.
 */
struct PathTexture: public Texture {
    PathTexture(Caches& caches, float left, float top,
            float offset, int generation)
            : Texture(caches)
            , left(left)
            , top(top)
            , offset(offset) {
        this->generation = generation;
    }
    PathTexture(Caches& caches, int generation)
        : Texture(caches) {
        this->generation = generation;
    }

    ~PathTexture() {
        clearTask();
    }

    /**
     * Left coordinate of the path bounds.
     */
    float left = 0;
    /**
     * Top coordinate of the path bounds.
     */
    float top = 0;
    /**
     * Offset to draw the path at the correct origin.
     */
    float offset = 0;

    sp<Task<SkBitmap*> > task() const {
        return mTask;
    }

    void setTask(const sp<Task<SkBitmap*> >& task) {
        mTask = task;
    }

    void clearTask() {
        if (mTask != nullptr) {
            mTask.clear();
        }
    }

private:
    sp<Task<SkBitmap*> > mTask;
}; // struct PathTexture

enum class ShapeType {
    None,
    Rect,
    RoundRect,
    Circle,
    Oval,
    Arc,
    Path
};

struct PathDescription {
    HASHABLE_TYPE(PathDescription);
    ShapeType type;
    SkPaint::Join join;
    SkPaint::Cap cap;
    SkPaint::Style style;
    float miter;
    float strokeWidth;
    SkPathEffect* pathEffect;
    union Shape {
        struct Path {
            uint32_t mGenerationID;
        } path;
        struct RoundRect {
            float mWidth;
            float mHeight;
            float mRx;
            float mRy;
        } roundRect;
        struct Circle {
            float mRadius;
        } circle;
        struct Oval {
            float mWidth;
            float mHeight;
        } oval;
        struct Rect {
            float mWidth;
            float mHeight;
        } rect;
        struct Arc {
            float mWidth;
            float mHeight;
            float mStartAngle;
            float mSweepAngle;
            bool mUseCenter;
        } arc;
    } shape;

    PathDescription();
    PathDescription(ShapeType shapeType, const SkPaint* paint);
};

/**
 * A simple LRU shape cache. The cache has a maximum size expressed in bytes.
 * Any texture added to the cache causing the cache to grow beyond the maximum
 * allowed size will also cause the oldest texture to be kicked out.
 */
class PathCache: public OnEntryRemoved<PathDescription, PathTexture*> {
public:
    PathCache();
    ~PathCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(PathDescription& path, PathTexture*& texture) override;

    /**
     * Clears the cache. This causes all textures to be deleted.
     */
    void clear();

    /**
     * Returns the maximum size of the cache in bytes.
     */
    uint32_t getMaxSize();
    /**
     * Returns the current size of the cache in bytes.
     */
    uint32_t getSize();

    PathTexture* getRoundRect(float width, float height, float rx, float ry, const SkPaint* paint);
    PathTexture* getCircle(float radius, const SkPaint* paint);
    PathTexture* getOval(float width, float height, const SkPaint* paint);
    PathTexture* getRect(float width, float height, const SkPaint* paint);
    PathTexture* getArc(float width, float height, float startAngle, float sweepAngle,
            bool useCenter, const SkPaint* paint);
    PathTexture* get(const SkPath* path, const SkPaint* paint);
    void         remove(const SkPath* path, const SkPaint* paint);

    /**
     * Removes the specified path. This is meant to be called from threads
     * that are not the EGL context thread.
     */
    ANDROID_API void removeDeferred(const SkPath* path);
    /**
     * Process deferred removals.
     */
    void clearGarbage();
    /**
     * Trims the contents of the cache, removing items until it's under its
     * specified limit.
     *
     * Trimming is used for caches that support pre-caching from a worker
     * thread. During pre-caching the maximum limit of the cache can be
     * exceeded for the duration of the frame. It is therefore required to
     * trim the cache at the end of the frame to keep the total amount of
     * memory used under control.
     */
    void trim();

    /**
     * Precaches the specified path using background threads.
     */
    void precache(const SkPath* path, const SkPaint* paint);

    static bool canDrawAsConvexPath(SkPath* path, const SkPaint* paint);
    static void computePathBounds(const SkPath* path, const SkPaint* paint,
            float& left, float& top, float& offset, uint32_t& width, uint32_t& height);
    static void computeBounds(const SkRect& bounds, const SkPaint* paint,
            float& left, float& top, float& offset, uint32_t& width, uint32_t& height);

private:
    PathTexture* addTexture(const PathDescription& entry,
            const SkPath *path, const SkPaint* paint);
    PathTexture* addTexture(const PathDescription& entry, SkBitmap* bitmap);

    /**
     * Generates the texture from a bitmap into the specified texture structure.
     */
    void generateTexture(SkBitmap& bitmap, Texture* texture);
    void generateTexture(const PathDescription& entry, SkBitmap* bitmap, PathTexture* texture,
            bool addToCache = true);

    PathTexture* get(const PathDescription& entry) {
        return mCache.get(entry);
    }

    /**
     * Ensures there is enough space in the cache for a texture of the specified
     * dimensions.
     */
    void purgeCache(uint32_t width, uint32_t height);

    void removeTexture(PathTexture* texture);

    bool checkTextureSize(uint32_t width, uint32_t height) {
        if (width > mMaxTextureSize || height > mMaxTextureSize) {
            ALOGW("Shape too large to be rendered into a texture (%dx%d, max=%dx%d)",
                    width, height, mMaxTextureSize, mMaxTextureSize);
            return false;
        }
        return true;
    }

    void init();

    class PathTask: public Task<SkBitmap*> {
    public:
        PathTask(const SkPath* path, const SkPaint* paint, PathTexture* texture):
            path(*path), paint(*paint), texture(texture) {
        }

        ~PathTask() {
            delete future()->get();
        }

        // copied, since input path not guaranteed to survive for duration of task
        const SkPath path;

        // copied, since input paint may not be immutable
        const SkPaint paint;
        PathTexture* texture;
    };

    class PathProcessor: public TaskProcessor<SkBitmap*> {
    public:
        PathProcessor(Caches& caches);
        ~PathProcessor() { }

        virtual void onProcess(const sp<Task<SkBitmap*> >& task) override;

    private:
        uint32_t mMaxTextureSize;
    };

    LruCache<PathDescription, PathTexture*> mCache;
    uint32_t mSize;
    const uint32_t mMaxSize;
    GLuint mMaxTextureSize;

    bool mDebugEnabled;

    sp<PathProcessor> mProcessor;

    std::vector<uint32_t> mGarbage;
    mutable Mutex mLock;
}; // class PathCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATH_CACHE_H
