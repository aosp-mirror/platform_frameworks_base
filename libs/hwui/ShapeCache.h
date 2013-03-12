/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_HWUI_SHAPE_CACHE_H
#define ANDROID_HWUI_SHAPE_CACHE_H

#define ATRACE_TAG ATRACE_TAG_VIEW

#include <GLES2/gl2.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkRect.h>

#include <utils/JenkinsHash.h>
#include <utils/LruCache.h>
#include <utils/Trace.h>
#include <utils/CallStack.h>

#include "Debug.h"
#include "Properties.h"
#include "Texture.h"
#include "thread/Future.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_SHAPES
    #define SHAPE_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define SHAPE_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

/**
 * Alpha texture used to represent a path.
 */
struct PathTexture: public Texture {
    PathTexture(): Texture() {
    }

    PathTexture(bool hasFuture): Texture() {
        if (hasFuture) {
            mFuture = new Future<SkBitmap*>();
        }
    }

    ~PathTexture() {
        clearFuture();
    }

    /**
     * Left coordinate of the path bounds.
     */
    float left;
    /**
     * Top coordinate of the path bounds.
     */
    float top;
    /**
     * Offset to draw the path at the correct origin.
     */
    float offset;

    sp<Future<SkBitmap*> > future() const {
        return mFuture;
    }

    void clearFuture() {
        if (mFuture != NULL) {
            delete mFuture->get();
            mFuture.clear();
        }
    }

private:
    sp<Future<SkBitmap*> > mFuture;
}; // struct PathTexture

/**
 * Describe a shape in the shape cache.
 */
struct ShapeCacheEntry {
    enum ShapeType {
        kShapeNone,
        kShapeRect,
        kShapeRoundRect,
        kShapeCircle,
        kShapeOval,
        kShapeArc,
        kShapePath
    };

    ShapeCacheEntry() {
        shapeType = kShapeNone;
        join = SkPaint::kDefault_Join;
        cap = SkPaint::kDefault_Cap;
        style = SkPaint::kFill_Style;
        miter = 4.0f;
        strokeWidth = 1.0f;
        pathEffect = NULL;
    }

    ShapeCacheEntry(ShapeType type, SkPaint* paint) {
        shapeType = type;
        join = paint->getStrokeJoin();
        cap = paint->getStrokeCap();
        miter = paint->getStrokeMiter();
        strokeWidth = paint->getStrokeWidth();
        style = paint->getStyle();
        pathEffect = paint->getPathEffect();
    }

    virtual ~ShapeCacheEntry() {
    }

    virtual hash_t hash() const {
        uint32_t hash = JenkinsHashMix(0, shapeType);
        hash = JenkinsHashMix(hash, join);
        hash = JenkinsHashMix(hash, cap);
        hash = JenkinsHashMix(hash, style);
        hash = JenkinsHashMix(hash, android::hash_type(miter));
        hash = JenkinsHashMix(hash, android::hash_type(strokeWidth));
        hash = JenkinsHashMix(hash, android::hash_type(pathEffect));
        return JenkinsHashWhiten(hash);
    }

    virtual int compare(const ShapeCacheEntry& rhs) const {
        int deltaInt = shapeType - rhs.shapeType;
        if (deltaInt != 0) return deltaInt;

        deltaInt = join - rhs.join;
        if (deltaInt != 0) return deltaInt;

        deltaInt = cap - rhs.cap;
        if (deltaInt != 0) return deltaInt;

        deltaInt = style - rhs.style;
        if (deltaInt != 0) return deltaInt;

        if (miter < rhs.miter) return -1;
        if (miter > rhs.miter) return +1;

        if (strokeWidth < rhs.strokeWidth) return -1;
        if (strokeWidth > rhs.strokeWidth) return +1;

        if (pathEffect < rhs.pathEffect) return -1;
        if (pathEffect > rhs.pathEffect) return +1;

        return 0;
    }

    bool operator==(const ShapeCacheEntry& other) const {
        return compare(other) == 0;
    }

    bool operator!=(const ShapeCacheEntry& other) const {
        return compare(other) != 0;
    }

    ShapeType shapeType;
    SkPaint::Join join;
    SkPaint::Cap cap;
    SkPaint::Style style;
    float miter;
    float strokeWidth;
    SkPathEffect* pathEffect;
}; // struct ShapeCacheEntry

// Cache support

inline int strictly_order_type(const ShapeCacheEntry& lhs, const ShapeCacheEntry& rhs) {
    return lhs.compare(rhs) < 0;
}

inline int compare_type(const ShapeCacheEntry& lhs, const ShapeCacheEntry& rhs) {
    return lhs.compare(rhs);
}

inline hash_t hash_type(const ShapeCacheEntry& entry) {
    return entry.hash();
}

struct RoundRectShapeCacheEntry: public ShapeCacheEntry {
    RoundRectShapeCacheEntry(float width, float height, float rx, float ry, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeRoundRect, paint) {
        mWidth = width;
        mHeight = height;
        mRx = rx;
        mRy = ry;
    }

    RoundRectShapeCacheEntry(): ShapeCacheEntry() {
        mWidth = 0;
        mHeight = 0;
        mRx = 0;
        mRy = 0;
    }

    hash_t hash() const {
        uint32_t hash = ShapeCacheEntry::hash();
        hash = JenkinsHashMix(hash, android::hash_type(mWidth));
        hash = JenkinsHashMix(hash, android::hash_type(mHeight));
        hash = JenkinsHashMix(hash, android::hash_type(mRx));
        hash = JenkinsHashMix(hash, android::hash_type(mRy));
        return JenkinsHashWhiten(hash);
    }

    int compare(const ShapeCacheEntry& r) const {
        int deltaInt = ShapeCacheEntry::compare(r);
        if (deltaInt != 0) return deltaInt;

        const RoundRectShapeCacheEntry& rhs = (const RoundRectShapeCacheEntry&) r;

        if (mWidth < rhs.mWidth) return -1;
        if (mWidth > rhs.mWidth) return +1;

        if (mHeight < rhs.mHeight) return -1;
        if (mHeight > rhs.mHeight) return +1;

        if (mRx < rhs.mRx) return -1;
        if (mRx > rhs.mRx) return +1;

        if (mRy < rhs.mRy) return -1;
        if (mRy > rhs.mRy) return +1;

        return 0;
    }

private:
    float mWidth;
    float mHeight;
    float mRx;
    float mRy;
}; // RoundRectShapeCacheEntry

inline hash_t hash_type(const RoundRectShapeCacheEntry& entry) {
    return entry.hash();
}

struct CircleShapeCacheEntry: public ShapeCacheEntry {
    CircleShapeCacheEntry(float radius, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeCircle, paint) {
        mRadius = radius;
    }

    CircleShapeCacheEntry(): ShapeCacheEntry() {
        mRadius = 0;
    }

    hash_t hash() const {
        uint32_t hash = ShapeCacheEntry::hash();
        hash = JenkinsHashMix(hash, android::hash_type(mRadius));
        return JenkinsHashWhiten(hash);
    }

    int compare(const ShapeCacheEntry& r) const {
        int deltaInt = ShapeCacheEntry::compare(r);
        if (deltaInt != 0) return deltaInt;

        const CircleShapeCacheEntry& rhs = (const CircleShapeCacheEntry&) r;

        if (mRadius < rhs.mRadius) return -1;
        if (mRadius > rhs.mRadius) return +1;

        return 0;
    }

private:
    float mRadius;
}; // CircleShapeCacheEntry

inline hash_t hash_type(const CircleShapeCacheEntry& entry) {
    return entry.hash();
}

struct OvalShapeCacheEntry: public ShapeCacheEntry {
    OvalShapeCacheEntry(float width, float height, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeOval, paint) {
        mWidth = width;
        mHeight = height;
    }

    OvalShapeCacheEntry(): ShapeCacheEntry() {
        mWidth = mHeight = 0;
    }

    hash_t hash() const {
        uint32_t hash = ShapeCacheEntry::hash();
        hash = JenkinsHashMix(hash, android::hash_type(mWidth));
        hash = JenkinsHashMix(hash, android::hash_type(mHeight));
        return JenkinsHashWhiten(hash);
    }

    int compare(const ShapeCacheEntry& r) const {
        int deltaInt = ShapeCacheEntry::compare(r);
        if (deltaInt != 0) return deltaInt;

        const OvalShapeCacheEntry& rhs = (const OvalShapeCacheEntry&) r;

        if (mWidth < rhs.mWidth) return -1;
        if (mWidth > rhs.mWidth) return +1;

        if (mHeight < rhs.mHeight) return -1;
        if (mHeight > rhs.mHeight) return +1;

        return 0;
    }

private:
    float mWidth;
    float mHeight;
}; // OvalShapeCacheEntry

inline hash_t hash_type(const OvalShapeCacheEntry& entry) {
    return entry.hash();
}

struct RectShapeCacheEntry: public ShapeCacheEntry {
    RectShapeCacheEntry(float width, float height, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeRect, paint) {
        mWidth = width;
        mHeight = height;
    }

    RectShapeCacheEntry(): ShapeCacheEntry() {
        mWidth = mHeight = 0;
    }

    hash_t hash() const {
        uint32_t hash = ShapeCacheEntry::hash();
        hash = JenkinsHashMix(hash, android::hash_type(mWidth));
        hash = JenkinsHashMix(hash, android::hash_type(mHeight));
        return JenkinsHashWhiten(hash);
    }

    int compare(const ShapeCacheEntry& r) const {
        int deltaInt = ShapeCacheEntry::compare(r);
        if (deltaInt != 0) return deltaInt;

        const RectShapeCacheEntry& rhs = (const RectShapeCacheEntry&) r;

        if (mWidth < rhs.mWidth) return -1;
        if (mWidth > rhs.mWidth) return +1;

        if (mHeight < rhs.mHeight) return -1;
        if (mHeight > rhs.mHeight) return +1;

        return 0;
    }

private:
    float mWidth;
    float mHeight;
}; // RectShapeCacheEntry

inline hash_t hash_type(const RectShapeCacheEntry& entry) {
    return entry.hash();
}

struct ArcShapeCacheEntry: public ShapeCacheEntry {
    ArcShapeCacheEntry(float width, float height, float startAngle, float sweepAngle,
            bool useCenter, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeArc, paint) {
        mWidth = width;
        mHeight = height;
        mStartAngle = startAngle;
        mSweepAngle = sweepAngle;
        mUseCenter = useCenter ? 1 : 0;
    }

    ArcShapeCacheEntry(): ShapeCacheEntry() {
        mWidth = 0;
        mHeight = 0;
        mStartAngle = 0;
        mSweepAngle = 0;
        mUseCenter = 0;
    }

    hash_t hash() const {
        uint32_t hash = ShapeCacheEntry::hash();
        hash = JenkinsHashMix(hash, android::hash_type(mWidth));
        hash = JenkinsHashMix(hash, android::hash_type(mHeight));
        hash = JenkinsHashMix(hash, android::hash_type(mStartAngle));
        hash = JenkinsHashMix(hash, android::hash_type(mSweepAngle));
        hash = JenkinsHashMix(hash, mUseCenter);
        return JenkinsHashWhiten(hash);
    }

    int compare(const ShapeCacheEntry& r) const {
        int deltaInt = ShapeCacheEntry::compare(r);
        if (deltaInt != 0) return deltaInt;

        const ArcShapeCacheEntry& rhs = (const ArcShapeCacheEntry&) r;

        if (mWidth < rhs.mWidth) return -1;
        if (mWidth > rhs.mWidth) return +1;

        if (mHeight < rhs.mHeight) return -1;
        if (mHeight > rhs.mHeight) return +1;

        if (mStartAngle < rhs.mStartAngle) return -1;
        if (mStartAngle > rhs.mStartAngle) return +1;

        if (mSweepAngle < rhs.mSweepAngle) return -1;
        if (mSweepAngle > rhs.mSweepAngle) return +1;

        return mUseCenter - rhs.mUseCenter;
    }

private:
    float mWidth;
    float mHeight;
    float mStartAngle;
    float mSweepAngle;
    uint32_t mUseCenter;
}; // ArcShapeCacheEntry

inline hash_t hash_type(const ArcShapeCacheEntry& entry) {
    return entry.hash();
}

/**
 * A simple LRU shape cache. The cache has a maximum size expressed in bytes.
 * Any texture added to the cache causing the cache to grow beyond the maximum
 * allowed size will also cause the oldest texture to be kicked out.
 */
template<typename Entry>
class ShapeCache: public OnEntryRemoved<Entry, PathTexture*> {
public:
    ShapeCache(const char* name, const char* propertyName, float defaultSize);
    ~ShapeCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(Entry& path, PathTexture*& texture);

    /**
     * Clears the cache. This causes all textures to be deleted.
     */
    void clear();

    /**
     * Sets the maximum size of the cache in bytes.
     */
    void setMaxSize(uint32_t maxSize);
    /**
     * Returns the maximum size of the cache in bytes.
     */
    uint32_t getMaxSize();
    /**
     * Returns the current size of the cache in bytes.
     */
    uint32_t getSize();

    /**
     * Trims the contents of the cache, removing items until it's under its
     * specified limit.
     *
     * Trimming is used for caches that support pre-caching from a worker
     * thread. During pre-caching the maximum limit of the cache can be
     * exceeded for the duration of the frame. It is therefore required to
     * trim the cache at the end of the frame to keep the total amount of
     * memory used under control.
     *
     * Only the PathCache currently supports pre-caching.
     */
    void trim();

    static void computePathBounds(const SkPath* path, const SkPaint* paint,
            float& left, float& top, float& offset, uint32_t& width, uint32_t& height) {
        const SkRect& bounds = path->getBounds();
        computeBounds(bounds, paint, left, top, offset, width, height);
    }

    static void computeBounds(const SkRect& bounds, const SkPaint* paint,
            float& left, float& top, float& offset, uint32_t& width, uint32_t& height) {
        const float pathWidth = fmax(bounds.width(), 1.0f);
        const float pathHeight = fmax(bounds.height(), 1.0f);

        left = bounds.fLeft;
        top = bounds.fTop;

        offset = (int) floorf(fmax(paint->getStrokeWidth(), 1.0f) * 1.5f + 0.5f);

        width = uint32_t(pathWidth + offset * 2.0 + 0.5);
        height = uint32_t(pathHeight + offset * 2.0 + 0.5);
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

protected:
    PathTexture* addTexture(const Entry& entry, const SkPath *path, const SkPaint* paint);
    PathTexture* addTexture(const Entry& entry, SkBitmap* bitmap);
    void addTexture(const Entry& entry, SkBitmap* bitmap, PathTexture* texture);

    /**
     * Ensures there is enough space in the cache for a texture of the specified
     * dimensions.
     */
    void purgeCache(uint32_t width, uint32_t height);

    PathTexture* get(Entry entry) {
        return mCache.get(entry);
    }

    void removeTexture(PathTexture* texture);

    bool checkTextureSize(uint32_t width, uint32_t height) {
        if (width > mMaxTextureSize || height > mMaxTextureSize) {
            ALOGW("Shape %s too large to be rendered into a texture (%dx%d, max=%dx%d)",
                    mName, width, height, mMaxTextureSize, mMaxTextureSize);
            return false;
        }
        return true;
    }

    static PathTexture* createTexture(float left, float top, float offset,
            uint32_t width, uint32_t height, uint32_t id, bool hasFuture = false) {
        PathTexture* texture = new PathTexture(hasFuture);
        texture->left = left;
        texture->top = top;
        texture->offset = offset;
        texture->width = width;
        texture->height = height;
        texture->generation = id;
        return texture;
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

    LruCache<Entry, PathTexture*> mCache;
    uint32_t mSize;
    uint32_t mMaxSize;
    GLuint mMaxTextureSize;

    char* mName;
    bool mDebugEnabled;

private:
    /**
     * Generates the texture from a bitmap into the specified texture structure.
     */
    void generateTexture(SkBitmap& bitmap, Texture* texture);

    void init();
}; // class ShapeCache

class RoundRectShapeCache: public ShapeCache<RoundRectShapeCacheEntry> {
public:
    RoundRectShapeCache();

    PathTexture* getRoundRect(float width, float height, float rx, float ry, SkPaint* paint);
}; // class RoundRectShapeCache

class CircleShapeCache: public ShapeCache<CircleShapeCacheEntry> {
public:
    CircleShapeCache();

    PathTexture* getCircle(float radius, SkPaint* paint);
}; // class CircleShapeCache

class OvalShapeCache: public ShapeCache<OvalShapeCacheEntry> {
public:
    OvalShapeCache();

    PathTexture* getOval(float width, float height, SkPaint* paint);
}; // class OvalShapeCache

class RectShapeCache: public ShapeCache<RectShapeCacheEntry> {
public:
    RectShapeCache();

    PathTexture* getRect(float width, float height, SkPaint* paint);
}; // class RectShapeCache

class ArcShapeCache: public ShapeCache<ArcShapeCacheEntry> {
public:
    ArcShapeCache();

    PathTexture* getArc(float width, float height, float startAngle, float sweepAngle,
            bool useCenter, SkPaint* paint);
}; // class ArcShapeCache

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

template<class Entry>
ShapeCache<Entry>::ShapeCache(const char* name, const char* propertyName, float defaultSize):
        mCache(LruCache<ShapeCacheEntry, PathTexture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(defaultSize)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(propertyName, property, NULL) > 0) {
        INIT_LOGD("  Setting %s cache size to %sMB", name, property);
        setMaxSize(MB(atof(property)));
    } else {
        INIT_LOGD("  Using default %s cache size of %.2fMB", name, defaultSize);
    }

    size_t len = strlen(name);
    mName = new char[len + 1];
    strcpy(mName, name);
    mName[len] = '\0';

    init();
}

template<class Entry>
ShapeCache<Entry>::~ShapeCache() {
    mCache.clear();
    delete[] mName;
}

template<class Entry>
void ShapeCache<Entry>::init() {
    mCache.setOnEntryRemovedListener(this);

    GLint maxTextureSize;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    mMaxTextureSize = maxTextureSize;

    mDebugEnabled = readDebugLevel() & kDebugCaches;
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

template<class Entry>
uint32_t ShapeCache<Entry>::getSize() {
    return mSize;
}

template<class Entry>
uint32_t ShapeCache<Entry>::getMaxSize() {
    return mMaxSize;
}

template<class Entry>
void ShapeCache<Entry>::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

template<class Entry>
void ShapeCache<Entry>::operator()(Entry& path, PathTexture*& texture) {
    removeTexture(texture);
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

template<class Entry>
void ShapeCache<Entry>::removeTexture(PathTexture* texture) {
    if (texture) {
        const uint32_t size = texture->width * texture->height;
        mSize -= size;

        SHAPE_LOGD("ShapeCache::callback: delete %s: name, size, mSize = %d, %d, %d",
                mName, texture->id, size, mSize);
        if (mDebugEnabled) {
            ALOGD("Shape %s deleted, size = %d", mName, size);
        }

        glDeleteTextures(1, &texture->id);
        delete texture;
    }
}

template<class Entry>
void ShapeCache<Entry>::purgeCache(uint32_t width, uint32_t height) {
    const uint32_t size = width * height;
    // Don't even try to cache a bitmap that's bigger than the cache
    if (size < mMaxSize) {
        while (mSize + size > mMaxSize) {
            mCache.removeOldest();
        }
    }
}

template<class Entry>
void ShapeCache<Entry>::trim() {
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

template<class Entry>
PathTexture* ShapeCache<Entry>::addTexture(const Entry& entry, const SkPath *path,
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
    addTexture(entry, &bitmap, texture);

    return texture;
}

template<class Entry>
void ShapeCache<Entry>::addTexture(const Entry& entry, SkBitmap* bitmap, PathTexture* texture) {
    generateTexture(*bitmap, texture);

    uint32_t size = texture->width * texture->height;
    if (size < mMaxSize) {
        mSize += size;
        SHAPE_LOGD("ShapeCache::get: create %s: name, size, mSize = %d, %d, %d",
                mName, texture->id, size, mSize);
        if (mDebugEnabled) {
            ALOGD("Shape %s created, size = %d", mName, size);
        }
        mCache.put(entry, texture);
    } else {
        texture->cleanup = true;
    }
}

template<class Entry>
void ShapeCache<Entry>::clear() {
    mCache.clear();
}

template<class Entry>
void ShapeCache<Entry>::generateTexture(SkBitmap& bitmap, Texture* texture) {
    SkAutoLockPixels alp(bitmap);
    if (!bitmap.readyToDraw()) {
        ALOGE("Cannot generate texture from bitmap");
        return;
    }

    glGenTextures(1, &texture->id);

    glBindTexture(GL_TEXTURE_2D, texture->id);
    // Textures are Alpha8
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    texture->blend = true;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, texture->width, texture->height, 0,
            GL_ALPHA, GL_UNSIGNED_BYTE, bitmap.getPixels());

    texture->setFilter(GL_LINEAR);
    texture->setWrap(GL_CLAMP_TO_EDGE);
}

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SHAPE_CACHE_H
