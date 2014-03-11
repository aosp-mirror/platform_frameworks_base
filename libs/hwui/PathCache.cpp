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

#define LOG_TAG "OpenGLRenderer"
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkRect.h>

#include <utils/JenkinsHash.h>
#include <utils/Trace.h>

#include "Caches.h"
#include "PathCache.h"

#include "thread/Signal.h"
#include "thread/Task.h"
#include "thread/TaskProcessor.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Cache entries
///////////////////////////////////////////////////////////////////////////////

PathDescription::PathDescription():
        type(kShapeNone),
        join(SkPaint::kDefault_Join),
        cap(SkPaint::kDefault_Cap),
        style(SkPaint::kFill_Style),
        miter(4.0f),
        strokeWidth(1.0f),
        pathEffect(NULL) {
    memset(&shape, 0, sizeof(Shape));
}

PathDescription::PathDescription(ShapeType type, SkPaint* paint):
        type(type),
        join(paint->getStrokeJoin()),
        cap(paint->getStrokeCap()),
        style(paint->getStyle()),
        miter(paint->getStrokeMiter()),
        strokeWidth(paint->getStrokeWidth()),
        pathEffect(paint->getPathEffect()) {
    memset(&shape, 0, sizeof(Shape));
}

hash_t PathDescription::hash() const {
    uint32_t hash = JenkinsHashMix(0, type);
    hash = JenkinsHashMix(hash, join);
    hash = JenkinsHashMix(hash, cap);
    hash = JenkinsHashMix(hash, style);
    hash = JenkinsHashMix(hash, android::hash_type(miter));
    hash = JenkinsHashMix(hash, android::hash_type(strokeWidth));
    hash = JenkinsHashMix(hash, android::hash_type(pathEffect));
    hash = JenkinsHashMixBytes(hash, (uint8_t*) &shape, sizeof(Shape));
    return JenkinsHashWhiten(hash);
}

int PathDescription::compare(const PathDescription& rhs) const {
    return memcmp(this, &rhs, sizeof(PathDescription));
}

///////////////////////////////////////////////////////////////////////////////
// Utilities
///////////////////////////////////////////////////////////////////////////////

bool PathCache::canDrawAsConvexPath(SkPath* path, SkPaint* paint) {
    // NOTE: This should only be used after PathTessellator handles joins properly
    return paint->getPathEffect() == NULL && path->getConvexity() == SkPath::kConvex_Convexity;
}

void PathCache::computePathBounds(const SkPath* path, const SkPaint* paint,
        float& left, float& top, float& offset, uint32_t& width, uint32_t& height) {
    const SkRect& bounds = path->getBounds();
    PathCache::computeBounds(bounds, paint, left, top, offset, width, height);
}

void PathCache::computeBounds(const SkRect& bounds, const SkPaint* paint,
        float& left, float& top, float& offset, uint32_t& width, uint32_t& height) {
    const float pathWidth = fmax(bounds.width(), 1.0f);
    const float pathHeight = fmax(bounds.height(), 1.0f);

    left = bounds.fLeft;
    top = bounds.fTop;

    offset = (int) floorf(fmax(paint->getStrokeWidth(), 1.0f) * 1.5f + 0.5f);

    width = uint32_t(pathWidth + offset * 2.0 + 0.5);
    height = uint32_t(pathHeight + offset * 2.0 + 0.5);
}

static void initBitmap(SkBitmap& bitmap, uint32_t width, uint32_t height) {
    bitmap.setConfig(SkBitmap::kA8_Config, width, height);
    bitmap.allocPixels();
    bitmap.eraseColor(0);
}

static void initPaint(SkPaint& paint) {
    // Make sure the paint is opaque, color, alpha, filter, etc.
    // will be applied later when compositing the alpha8 texture
    paint.setColor(0xff000000);
    paint.setAlpha(255);
    paint.setColorFilter(NULL);
    paint.setMaskFilter(NULL);
    paint.setShader(NULL);
    SkXfermode* mode = SkXfermode::Create(SkXfermode::kSrc_Mode);
    SkSafeUnref(paint.setXfermode(mode));
}

static void drawPath(const SkPath *path, const SkPaint* paint, SkBitmap& bitmap,
        float left, float top, float offset, uint32_t width, uint32_t height) {
    initBitmap(bitmap, width, height);

    SkPaint pathPaint(*paint);
    initPaint(pathPaint);

    SkCanvas canvas(bitmap);
    canvas.translate(-left + offset, -top + offset);
    canvas.drawPath(*path, pathPaint);
}

static PathTexture* createTexture(float left, float top, float offset,
        uint32_t width, uint32_t height, uint32_t id) {
    PathTexture* texture = new PathTexture(Caches::getInstance());
    texture->left = left;
    texture->top = top;
    texture->offset = offset;
    texture->width = width;
    texture->height = height;
    texture->generation = id;
    return texture;
}

///////////////////////////////////////////////////////////////////////////////
// Cache constructor/destructor
///////////////////////////////////////////////////////////////////////////////

PathCache::PathCache():
        mCache(LruCache<PathDescription, PathTexture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_PATH_CACHE_SIZE)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_PATH_CACHE_SIZE, property, NULL) > 0) {
        INIT_LOGD("  Setting %s cache size to %sMB", name, property);
        setMaxSize(MB(atof(property)));
    } else {
        INIT_LOGD("  Using default %s cache size of %.2fMB", name, DEFAULT_PATH_CACHE_SIZE);
    }
    init();
}

PathCache::~PathCache() {
    mCache.clear();
}

void PathCache::init() {
    mCache.setOnEntryRemovedListener(this);

    GLint maxTextureSize;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    mMaxTextureSize = maxTextureSize;

    mDebugEnabled = readDebugLevel() & kDebugCaches;
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

void PathCache::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
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
        const uint32_t size = texture->width * texture->height;

        // If there is a pending task we must wait for it to return
        // before attempting our cleanup
        const sp<Task<SkBitmap*> >& task = texture->task();
        if (task != NULL) {
            SkBitmap* bitmap = task->getResult();
            texture->clearTask();
        } else {
            // If there is a pending task, the path was not added
            // to the cache and the size wasn't increased
            if (size > mSize) {
                ALOGE("Removing path texture of size %d will leave "
                        "the cache in an inconsistent state", size);
            }
            mSize -= size;
        }

        PATH_LOGD("PathCache::delete name, size, mSize = %d, %d, %d",
                texture->id, size, mSize);
        if (mDebugEnabled) {
            ALOGD("Shape deleted, size = %d", size);
        }

        if (texture->id) {
            Caches::getInstance().deleteTexture(texture->id);
        }
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
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

PathTexture* PathCache::addTexture(const PathDescription& entry, const SkPath *path,
        const SkPaint* paint) {
    ATRACE_CALL();

    float left, top, offset;
    uint32_t width, height;
    computePathBounds(path, paint, left, top, offset, width, height);

    if (!checkTextureSize(width, height)) return NULL;

    purgeCache(width, height);

    SkBitmap bitmap;
    drawPath(path, paint, bitmap, left, top, offset, width, height);

    PathTexture* texture = createTexture(left, top, offset, width, height,
            path->getGenerationID());
    generateTexture(entry, &bitmap, texture);

    return texture;
}

void PathCache::generateTexture(const PathDescription& entry, SkBitmap* bitmap,
        PathTexture* texture, bool addToCache) {
    generateTexture(*bitmap, texture);

    uint32_t size = texture->width * texture->height;
    if (size < mMaxSize) {
        mSize += size;
        PATH_LOGD("PathCache::get/create: name, size, mSize = %d, %d, %d",
                texture->id, size, mSize);
        if (mDebugEnabled) {
            ALOGD("Shape created, size = %d", size);
        }
        if (addToCache) {
            mCache.put(entry, texture);
        }
    } else {
        // It's okay to add a texture that's bigger than the cache since
        // we'll trim the cache later when addToCache is set to false
        if (!addToCache) {
            mSize += size;
        }
        texture->cleanup = true;
    }
}

void PathCache::clear() {
    mCache.clear();
}

void PathCache::generateTexture(SkBitmap& bitmap, Texture* texture) {
    SkAutoLockPixels alp(bitmap);
    if (!bitmap.readyToDraw()) {
        ALOGE("Cannot generate texture from bitmap");
        return;
    }

    glGenTextures(1, &texture->id);

    Caches::getInstance().bindTexture(texture->id);
    // Textures are Alpha8
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    texture->blend = true;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, texture->width, texture->height, 0,
            GL_ALPHA, GL_UNSIGNED_BYTE, bitmap.getPixels());

    texture->setFilter(GL_LINEAR);
    texture->setWrap(GL_CLAMP_TO_EDGE);
}

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
        drawPath(t->path, t->paint, *bitmap, left, top, offset, width, height);
        t->setResult(bitmap);
    } else {
        texture->width = 0;
        texture->height = 0;
        t->setResult(NULL);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Paths
///////////////////////////////////////////////////////////////////////////////

void PathCache::remove(Vector<PathDescription>& pathsToRemove, const path_pair_t& pair) {
    LruCache<PathDescription, PathTexture*>::Iterator i(mCache);

    while (i.next()) {
        const PathDescription& key = i.key();
        if (key.type == kShapePath &&
                (key.shape.path.mPath == pair.getFirst() ||
                        key.shape.path.mPath == pair.getSecond())) {
            pathsToRemove.push(key);
        }
    }
}

void PathCache::removeDeferred(SkPath* path) {
    Mutex::Autolock l(mLock);
    mGarbage.push(path_pair_t(path, const_cast<SkPath*>(path->getSourcePath())));
}

void PathCache::clearGarbage() {
    Vector<PathDescription> pathsToRemove;

    { // scope for the mutex
        Mutex::Autolock l(mLock);
        size_t count = mGarbage.size();
        for (size_t i = 0; i < count; i++) {
            const path_pair_t& pair = mGarbage.itemAt(i);
            remove(pathsToRemove, pair);
            delete pair.getFirst();
        }
        mGarbage.clear();
    }

    for (size_t i = 0; i < pathsToRemove.size(); i++) {
        mCache.remove(pathsToRemove.itemAt(i));
    }
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

    PathDescription entry(kShapePath, paint);
    entry.shape.path.mPath = path;

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
                generateTexture(entry, bitmap, texture, false);
                texture->clearTask();
            } else {
                ALOGW("Path too large to be rendered into a texture");
                texture->clearTask();
                texture = NULL;
                mCache.remove(entry);
            }
        } else if (path->getGenerationID() != texture->generation) {
            // The size of the path might have changed so we first
            // remove the entry from the cache
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

    PathDescription entry(kShapePath, paint);
    entry.shape.path.mPath = path;

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

///////////////////////////////////////////////////////////////////////////////
// Rounded rects
///////////////////////////////////////////////////////////////////////////////

PathTexture* PathCache::getRoundRect(float width, float height,
        float rx, float ry, SkPaint* paint) {
    PathDescription entry(kShapeRoundRect, paint);
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

PathTexture* PathCache::getCircle(float radius, SkPaint* paint) {
    PathDescription entry(kShapeCircle, paint);
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

PathTexture* PathCache::getOval(float width, float height, SkPaint* paint) {
    PathDescription entry(kShapeOval, paint);
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

PathTexture* PathCache::getRect(float width, float height, SkPaint* paint) {
    PathDescription entry(kShapeRect, paint);
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

PathTexture* PathCache::getArc(float width, float height,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* paint) {
    PathDescription entry(kShapeArc, paint);
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

}; // namespace uirenderer
}; // namespace android
