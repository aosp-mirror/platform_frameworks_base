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

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkColor.h>
#include <SkColorFilter.h>
#include <SkMaskFilter.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkPathEffect.h>
#include <SkRect.h>

#include <utils/JenkinsHash.h>
#include <utils/Trace.h>

#include "Caches.h"
#include "PathCache.h"

#include "thread/Signal.h"
#include "thread/TaskProcessor.h"

#include <cutils/properties.h>

namespace android {
namespace uirenderer {

static constexpr size_t PATH_CACHE_COUNT_LIMIT = 256;

template <class T>
static bool compareWidthHeight(const T& lhs, const T& rhs) {
    return (lhs.mWidth == rhs.mWidth) && (lhs.mHeight == rhs.mHeight);
}

static bool compareRoundRects(const PathDescription::Shape::RoundRect& lhs,
                              const PathDescription::Shape::RoundRect& rhs) {
    return compareWidthHeight(lhs, rhs) && lhs.mRx == rhs.mRx && lhs.mRy == rhs.mRy;
}

static bool compareArcs(const PathDescription::Shape::Arc& lhs,
                        const PathDescription::Shape::Arc& rhs) {
    return compareWidthHeight(lhs, rhs) && lhs.mStartAngle == rhs.mStartAngle &&
           lhs.mSweepAngle == rhs.mSweepAngle && lhs.mUseCenter == rhs.mUseCenter;
}

///////////////////////////////////////////////////////////////////////////////
// Cache entries
///////////////////////////////////////////////////////////////////////////////

PathDescription::PathDescription()
        : type(ShapeType::None)
        , join(SkPaint::kDefault_Join)
        , cap(SkPaint::kDefault_Cap)
        , style(SkPaint::kFill_Style)
        , miter(4.0f)
        , strokeWidth(1.0f)
        , pathEffect(nullptr) {
    // Shape bits should be set to zeroes, because they are used for hash calculation.
    memset(&shape, 0, sizeof(Shape));
}

PathDescription::PathDescription(ShapeType type, const SkPaint* paint)
        : type(type)
        , join(paint->getStrokeJoin())
        , cap(paint->getStrokeCap())
        , style(paint->getStyle())
        , miter(paint->getStrokeMiter())
        , strokeWidth(paint->getStrokeWidth())
        , pathEffect(paint->getPathEffect()) {
    // Shape bits should be set to zeroes, because they are used for hash calculation.
    memset(&shape, 0, sizeof(Shape));
}

hash_t PathDescription::hash() const {
    uint32_t hash = JenkinsHashMix(0, static_cast<int>(type));
    hash = JenkinsHashMix(hash, join);
    hash = JenkinsHashMix(hash, cap);
    hash = JenkinsHashMix(hash, style);
    hash = JenkinsHashMix(hash, android::hash_type(miter));
    hash = JenkinsHashMix(hash, android::hash_type(strokeWidth));
    hash = JenkinsHashMix(hash, android::hash_type(pathEffect));
    hash = JenkinsHashMixBytes(hash, (uint8_t*)&shape, sizeof(Shape));
    return JenkinsHashWhiten(hash);
}

bool PathDescription::operator==(const PathDescription& rhs) const {
    if (type != rhs.type) return false;
    if (join != rhs.join) return false;
    if (cap != rhs.cap) return false;
    if (style != rhs.style) return false;
    if (miter != rhs.miter) return false;
    if (strokeWidth != rhs.strokeWidth) return false;
    if (pathEffect != rhs.pathEffect) return false;
    switch (type) {
        case ShapeType::None:
            return 0;
        case ShapeType::Rect:
            return compareWidthHeight(shape.rect, rhs.shape.rect);
        case ShapeType::RoundRect:
            return compareRoundRects(shape.roundRect, rhs.shape.roundRect);
        case ShapeType::Circle:
            return shape.circle.mRadius == rhs.shape.circle.mRadius;
        case ShapeType::Oval:
            return compareWidthHeight(shape.oval, rhs.shape.oval);
        case ShapeType::Arc:
            return compareArcs(shape.arc, rhs.shape.arc);
        case ShapeType::Path:
            return shape.path.mGenerationID == rhs.shape.path.mGenerationID;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Utilities
///////////////////////////////////////////////////////////////////////////////

static void computePathBounds(const SkPath* path, const SkPaint* paint, PathTexture* texture,
                              uint32_t& width, uint32_t& height) {
    const SkRect& bounds = path->getBounds();
    const float pathWidth = std::max(bounds.width(), 1.0f);
    const float pathHeight = std::max(bounds.height(), 1.0f);

    texture->left = floorf(bounds.fLeft);
    texture->top = floorf(bounds.fTop);

    texture->offset = (int)floorf(std::max(paint->getStrokeWidth(), 1.0f) * 1.5f + 0.5f);

    width = uint32_t(pathWidth + texture->offset * 2.0 + 0.5);
    height = uint32_t(pathHeight + texture->offset * 2.0 + 0.5);
}

static void initPaint(SkPaint& paint) {
    // Make sure the paint is opaque, color, alpha, filter, etc.
    // will be applied later when compositing the alpha8 texture
    paint.setColor(SK_ColorBLACK);
    paint.setAlpha(255);
    paint.setColorFilter(nullptr);
    paint.setMaskFilter(nullptr);
    paint.setShader(nullptr);
    paint.setBlendMode(SkBlendMode::kSrc);
}

static sk_sp<Bitmap> drawPath(const SkPath* path, const SkPaint* paint, PathTexture* texture,
                              uint32_t maxTextureSize) {
    uint32_t width, height;
    computePathBounds(path, paint, texture, width, height);
    if (width > maxTextureSize || height > maxTextureSize) {
        ALOGW("Shape too large to be rendered into a texture (%dx%d, max=%dx%d)", width, height,
              maxTextureSize, maxTextureSize);
        return nullptr;
    }

    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(SkImageInfo::MakeA8(width, height));
    SkPaint pathPaint(*paint);
    initPaint(pathPaint);

    SkBitmap skBitmap;
    bitmap->getSkBitmap(&skBitmap);
    skBitmap.eraseColor(0);
    SkCanvas canvas(skBitmap);
    canvas.translate(-texture->left + texture->offset, -texture->top + texture->offset);
    canvas.drawPath(*path, pathPaint);
    return bitmap;
}

///////////////////////////////////////////////////////////////////////////////
// Cache constructor/destructor
///////////////////////////////////////////////////////////////////////////////

PathCache::PathCache()
        : mCache(LruCache<PathDescription, PathTexture*>::kUnlimitedCapacity)
        , mSize(0)
        , mMaxSize(DeviceInfo::multiplyByResolution(4)) {
    mCache.setOnEntryRemovedListener(this);
    mMaxTextureSize = DeviceInfo::get()->maxTextureSize();
    mDebugEnabled = Properties::debugLevel & kDebugCaches;
}

PathCache::~PathCache() {
    mCache.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

uint32_t PathCache::getSize() {
    return mSize;
}

uint32_t PathCache::getMaxSize() {
    return mMaxSize;
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void PathCache::operator()(PathDescription& entry, PathTexture*& texture) {
    removeTexture(texture);
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void PathCache::removeTexture(PathTexture* texture) {
    if (texture) {
        const uint32_t size = texture->width() * texture->height();

        // If there is a pending task we must wait for it to return
        // before attempting our cleanup
        const sp<PathTask>& task = texture->task();
        if (task != nullptr) {
            task->getResult();
            texture->clearTask();
        } else {
            // If there is a pending task, the path was not added
            // to the cache and the size wasn't increased
            if (size > mSize) {
                ALOGE("Removing path texture of size %d will leave "
                      "the cache in an inconsistent state",
                      size);
            }
            mSize -= size;
        }

        PATH_LOGD("PathCache::delete name, size, mSize = %d, %d, %d", texture->id, size, mSize);
        if (mDebugEnabled) {
            ALOGD("Shape deleted, size = %d", size);
        }

        texture->deleteTexture();
        delete texture;
    }
}

void PathCache::purgeCache(uint32_t width, uint32_t height) {
    const uint32_t size = width * height;
    // Don't even try to cache a bitmap that's bigger than the cache
    if (size < mMaxSize) {
        while (mSize + size > mMaxSize) {
            mCache.removeOldest();
        }
    }
}

void PathCache::trim() {
    while (mSize > mMaxSize || mCache.size() > PATH_CACHE_COUNT_LIMIT) {
        LOG_ALWAYS_FATAL_IF(!mCache.size(),
                            "Inconsistent mSize! Ran out of items to remove!"
                            " mSize = %u, mMaxSize = %u",
                            mSize, mMaxSize);
        mCache.removeOldest();
    }
}

PathTexture* PathCache::addTexture(const PathDescription& entry, const SkPath* path,
                                   const SkPaint* paint) {
    ATRACE_NAME("Generate Path Texture");

    PathTexture* texture = new PathTexture(Caches::getInstance(), path->getGenerationID());
    sk_sp<Bitmap> bitmap(drawPath(path, paint, texture, mMaxTextureSize));
    if (!bitmap) {
        delete texture;
        return nullptr;
    }

    purgeCache(bitmap->width(), bitmap->height());
    generateTexture(entry, *bitmap, texture);
    return texture;
}

void PathCache::generateTexture(const PathDescription& entry, Bitmap& bitmap, PathTexture* texture,
                                bool addToCache) {
    generateTexture(bitmap, texture);

    // Note here that we upload to a texture even if it's bigger than mMaxSize.
    // Such an entry in mCache will only be temporary, since it will be evicted
    // immediately on trim, or on any other Path entering the cache.
    uint32_t size = texture->width() * texture->height();
    mSize += size;
    PATH_LOGD("PathCache::get/create: name, size, mSize = %d, %d, %d", texture->id, size, mSize);
    if (mDebugEnabled) {
        ALOGD("Shape created, size = %d", size);
    }
    if (addToCache) {
        mCache.put(entry, texture);
    }
}

void PathCache::clear() {
    mCache.clear();
}

void PathCache::generateTexture(Bitmap& bitmap, Texture* texture) {
    ATRACE_NAME("Upload Path Texture");
    texture->upload(bitmap);
    texture->setFilter(GL_LINEAR);
}

///////////////////////////////////////////////////////////////////////////////
// Path precaching
///////////////////////////////////////////////////////////////////////////////

PathCache::PathProcessor::PathProcessor(Caches& caches)
        : TaskProcessor<sk_sp<Bitmap> >(&caches.tasks), mMaxTextureSize(caches.maxTextureSize) {}

void PathCache::PathProcessor::onProcess(const sp<Task<sk_sp<Bitmap> > >& task) {
    PathTask* t = static_cast<PathTask*>(task.get());
    ATRACE_NAME("pathPrecache");

    t->setResult(drawPath(&t->path, &t->paint, t->texture, mMaxTextureSize));
}

///////////////////////////////////////////////////////////////////////////////
// Paths
///////////////////////////////////////////////////////////////////////////////

void PathCache::removeDeferred(const SkPath* path) {
    Mutex::Autolock l(mLock);
    mGarbage.push_back(path->getGenerationID());
}

void PathCache::clearGarbage() {
    Vector<PathDescription> pathsToRemove;

    {  // scope for the mutex
        Mutex::Autolock l(mLock);
        for (const uint32_t generationID : mGarbage) {
            LruCache<PathDescription, PathTexture*>::Iterator iter(mCache);
            while (iter.next()) {
                const PathDescription& key = iter.key();
                if (key.type == ShapeType::Path && key.shape.path.mGenerationID == generationID) {
                    pathsToRemove.push(key);
                }
            }
        }
        mGarbage.clear();
    }

    for (size_t i = 0; i < pathsToRemove.size(); i++) {
        mCache.remove(pathsToRemove.itemAt(i));
    }
}

PathTexture* PathCache::get(const SkPath* path, const SkPaint* paint) {
    PathDescription entry(ShapeType::Path, paint);
    entry.shape.path.mGenerationID = path->getGenerationID();

    PathTexture* texture = mCache.get(entry);

    if (!texture) {
        texture = addTexture(entry, path, paint);
    } else {
        // A bitmap is attached to the texture, this means we need to
        // upload it as a GL texture
        const sp<PathTask>& task = texture->task();
        if (task != nullptr) {
            // But we must first wait for the worker thread to be done
            // producing the bitmap, so let's wait
            sk_sp<Bitmap> bitmap = task->getResult();
            if (bitmap) {
                generateTexture(entry, *bitmap, texture, false);
                texture->clearTask();
            } else {
                texture->clearTask();
                texture = nullptr;
                mCache.remove(entry);
            }
        }
    }

    return texture;
}

void PathCache::remove(const SkPath* path, const SkPaint* paint) {
    PathDescription entry(ShapeType::Path, paint);
    entry.shape.path.mGenerationID = path->getGenerationID();
    mCache.remove(entry);
}

void PathCache::precache(const SkPath* path, const SkPaint* paint) {
    if (!Caches::getInstance().tasks.canRunTasks()) {
        return;
    }

    PathDescription entry(ShapeType::Path, paint);
    entry.shape.path.mGenerationID = path->getGenerationID();

    PathTexture* texture = mCache.get(entry);

    bool generate = false;
    if (!texture) {
        generate = true;
    }

    if (generate) {
        // It is important to specify the generation ID so we do not
        // attempt to precache the same path several times
        texture = new PathTexture(Caches::getInstance(), path->getGenerationID());
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

        if (mProcessor == nullptr) {
            mProcessor = new PathProcessor(Caches::getInstance());
        }
        mProcessor->add(task);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Rounded rects
///////////////////////////////////////////////////////////////////////////////

PathTexture* PathCache::getRoundRect(float width, float height, float rx, float ry,
                                     const SkPaint* paint) {
    PathDescription entry(ShapeType::RoundRect, paint);
    entry.shape.roundRect.mWidth = width;
    entry.shape.roundRect.mHeight = height;
    entry.shape.roundRect.mRx = rx;
    entry.shape.roundRect.mRy = ry;

    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        SkRect r;
        r.set(0.0f, 0.0f, width, height);
        path.addRoundRect(r, rx, ry, SkPath::kCW_Direction);

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Circles
///////////////////////////////////////////////////////////////////////////////

PathTexture* PathCache::getCircle(float radius, const SkPaint* paint) {
    PathDescription entry(ShapeType::Circle, paint);
    entry.shape.circle.mRadius = radius;

    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        path.addCircle(radius, radius, radius, SkPath::kCW_Direction);

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Ovals
///////////////////////////////////////////////////////////////////////////////

PathTexture* PathCache::getOval(float width, float height, const SkPaint* paint) {
    PathDescription entry(ShapeType::Oval, paint);
    entry.shape.oval.mWidth = width;
    entry.shape.oval.mHeight = height;

    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        SkRect r;
        r.set(0.0f, 0.0f, width, height);
        path.addOval(r, SkPath::kCW_Direction);

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Rects
///////////////////////////////////////////////////////////////////////////////

PathTexture* PathCache::getRect(float width, float height, const SkPaint* paint) {
    PathDescription entry(ShapeType::Rect, paint);
    entry.shape.rect.mWidth = width;
    entry.shape.rect.mHeight = height;

    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        SkRect r;
        r.set(0.0f, 0.0f, width, height);
        path.addRect(r, SkPath::kCW_Direction);

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Arcs
///////////////////////////////////////////////////////////////////////////////

PathTexture* PathCache::getArc(float width, float height, float startAngle, float sweepAngle,
                               bool useCenter, const SkPaint* paint) {
    PathDescription entry(ShapeType::Arc, paint);
    entry.shape.arc.mWidth = width;
    entry.shape.arc.mHeight = height;
    entry.shape.arc.mStartAngle = startAngle;
    entry.shape.arc.mSweepAngle = sweepAngle;
    entry.shape.arc.mUseCenter = useCenter;

    PathTexture* texture = get(entry);

    if (!texture) {
        SkPath path;
        SkRect r;
        r.set(0.0f, 0.0f, width, height);
        if (useCenter) {
            path.moveTo(r.centerX(), r.centerY());
        }
        path.arcTo(r, startAngle, sweepAngle, !useCenter);
        if (useCenter) {
            path.close();
        }

        texture = addTexture(entry, &path, paint);
    }

    return texture;
}

};  // namespace uirenderer
};  // namespace android
