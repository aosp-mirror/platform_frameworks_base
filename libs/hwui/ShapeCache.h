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

#include <GLES2/gl2.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkRect.h>

#include "Debug.h"
#include "Properties.h"
#include "Texture.h"
#include "utils/Compare.h"
#include "utils/GenerationCache.h"

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
        float v = 4.0f;
        miter = *(uint32_t*) &v;
        v = 1.0f;
        strokeWidth = *(uint32_t*) &v;
        pathEffect = NULL;
    }

    ShapeCacheEntry(ShapeType type, SkPaint* paint) {
        shapeType = type;
        join = paint->getStrokeJoin();
        cap = paint->getStrokeCap();
        float v = paint->getStrokeMiter();
        miter = *(uint32_t*) &v;
        v = paint->getStrokeWidth();
        strokeWidth = *(uint32_t*) &v;
        style = paint->getStyle();
        pathEffect = paint->getPathEffect();
    }

    virtual ~ShapeCacheEntry() {
    }

    ShapeType shapeType;
    SkPaint::Join join;
    SkPaint::Cap cap;
    SkPaint::Style style;
    uint32_t miter;
    uint32_t strokeWidth;
    SkPathEffect* pathEffect;

    bool operator<(const ShapeCacheEntry& rhs) const {
        LTE_INT(shapeType) {
            LTE_INT(join) {
                LTE_INT(cap) {
                    LTE_INT(style) {
                        LTE_INT(miter) {
                            LTE_INT(strokeWidth) {
                                LTE_INT(pathEffect) {
                                    return lessThan(rhs);
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

protected:
    virtual bool lessThan(const ShapeCacheEntry& rhs) const {
        return false;
    }
}; // struct ShapeCacheEntry


struct RoundRectShapeCacheEntry: public ShapeCacheEntry {
    RoundRectShapeCacheEntry(float width, float height, float rx, float ry, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeRoundRect, paint) {
        mWidth = *(uint32_t*) &width;
        mHeight = *(uint32_t*) &height;
        mRx = *(uint32_t*) &rx;
        mRy = *(uint32_t*) &ry;
    }

    RoundRectShapeCacheEntry(): ShapeCacheEntry() {
        mWidth = 0;
        mHeight = 0;
        mRx = 0;
        mRy = 0;
    }

    bool lessThan(const ShapeCacheEntry& r) const {
        const RoundRectShapeCacheEntry& rhs = (const RoundRectShapeCacheEntry&) r;
        LTE_INT(mWidth) {
            LTE_INT(mHeight) {
                LTE_INT(mRx) {
                    LTE_INT(mRy) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

private:
    uint32_t mWidth;
    uint32_t mHeight;
    uint32_t mRx;
    uint32_t mRy;
}; // RoundRectShapeCacheEntry

struct CircleShapeCacheEntry: public ShapeCacheEntry {
    CircleShapeCacheEntry(float radius, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeCircle, paint) {
        mRadius = *(uint32_t*) &radius;
    }

    CircleShapeCacheEntry(): ShapeCacheEntry() {
        mRadius = 0;
    }

    bool lessThan(const ShapeCacheEntry& r) const {
        const CircleShapeCacheEntry& rhs = (const CircleShapeCacheEntry&) r;
        LTE_INT(mRadius) {
            return false;
        }
        return false;
    }

private:
    uint32_t mRadius;
}; // CircleShapeCacheEntry

struct OvalShapeCacheEntry: public ShapeCacheEntry {
    OvalShapeCacheEntry(float width, float height, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeOval, paint) {
        mWidth = *(uint32_t*) &width;
        mHeight = *(uint32_t*) &height;
    }

    OvalShapeCacheEntry(): ShapeCacheEntry() {
        mWidth = mHeight = 0;
    }

    bool lessThan(const ShapeCacheEntry& r) const {
        const OvalShapeCacheEntry& rhs = (const OvalShapeCacheEntry&) r;
        LTE_INT(mWidth) {
            LTE_INT(mHeight) {
                return false;
            }
        }
        return false;
    }

private:
    uint32_t mWidth;
    uint32_t mHeight;
}; // OvalShapeCacheEntry

struct RectShapeCacheEntry: public ShapeCacheEntry {
    RectShapeCacheEntry(float width, float height, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeRect, paint) {
        mWidth = *(uint32_t*) &width;
        mHeight = *(uint32_t*) &height;
    }

    RectShapeCacheEntry(): ShapeCacheEntry() {
        mWidth = mHeight = 0;
    }

    bool lessThan(const ShapeCacheEntry& r) const {
        const RectShapeCacheEntry& rhs = (const RectShapeCacheEntry&) r;
        LTE_INT(mWidth) {
            LTE_INT(mHeight) {
                return false;
            }
        }
        return false;
    }

private:
    uint32_t mWidth;
    uint32_t mHeight;
}; // RectShapeCacheEntry

struct ArcShapeCacheEntry: public ShapeCacheEntry {
    ArcShapeCacheEntry(float width, float height, float startAngle, float sweepAngle,
            bool useCenter, SkPaint* paint):
            ShapeCacheEntry(ShapeCacheEntry::kShapeArc, paint) {
        mWidth = *(uint32_t*) &width;
        mHeight = *(uint32_t*) &height;
        mStartAngle = *(uint32_t*) &startAngle;
        mSweepAngle = *(uint32_t*) &sweepAngle;
        mUseCenter = useCenter ? 1 : 0;
    }

    ArcShapeCacheEntry(): ShapeCacheEntry() {
        mWidth = 0;
        mHeight = 0;
        mStartAngle = 0;
        mSweepAngle = 0;
        mUseCenter = 0;
    }

    bool lessThan(const ShapeCacheEntry& r) const {
        const ArcShapeCacheEntry& rhs = (const ArcShapeCacheEntry&) r;
        LTE_INT(mWidth) {
            LTE_INT(mHeight) {
                LTE_INT(mStartAngle) {
                    LTE_INT(mSweepAngle) {
                        LTE_INT(mUseCenter) {
                            return false;
                        }
                    }
                }
            }
        }
        return false;
    }

private:
    uint32_t mWidth;
    uint32_t mHeight;
    uint32_t mStartAngle;
    uint32_t mSweepAngle;
    uint32_t mUseCenter;
}; // ArcShapeCacheEntry

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

protected:
    PathTexture* addTexture(const Entry& entry, const SkPath *path, const SkPaint* paint);

    PathTexture* get(Entry entry) {
        return mCache.get(entry);
    }

    void removeTexture(PathTexture* texture);

    GenerationCache<Entry, PathTexture*> mCache;
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
        mCache(GenerationCache<ShapeCacheEntry, PathTexture*>::kUnlimitedCapacity),
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
PathTexture* ShapeCache<Entry>::addTexture(const Entry& entry, const SkPath *path,
        const SkPaint* paint) {
    const SkRect& bounds = path->getBounds();

    const float pathWidth = fmax(bounds.width(), 1.0f);
    const float pathHeight = fmax(bounds.height(), 1.0f);

    const float offset = (int) floorf(fmax(paint->getStrokeWidth(), 1.0f) * 1.5f + 0.5f);

    const uint32_t width = uint32_t(pathWidth + offset * 2.0 + 0.5);
    const uint32_t height = uint32_t(pathHeight + offset * 2.0 + 0.5);

    if (width > mMaxTextureSize || height > mMaxTextureSize) {
        ALOGW("Shape %s too large to be rendered into a texture (%dx%d, max=%dx%d)",
                mName, width, height, mMaxTextureSize, mMaxTextureSize);
        return NULL;
    }

    const uint32_t size = width * height;
    // Don't even try to cache a bitmap that's bigger than the cache
    if (size < mMaxSize) {
        while (mSize + size > mMaxSize) {
            mCache.removeOldest();
        }
    }

    PathTexture* texture = new PathTexture;
    texture->left = bounds.fLeft;
    texture->top = bounds.fTop;
    texture->offset = offset;
    texture->width = width;
    texture->height = height;
    texture->generation = path->getGenerationID();

    SkBitmap bitmap;
    bitmap.setConfig(SkBitmap::kA8_Config, width, height);
    bitmap.allocPixels();
    bitmap.eraseColor(0);

    SkPaint pathPaint(*paint);

    // Make sure the paint is opaque, color, alpha, filter, etc.
    // will be applied later when compositing the alpha8 texture
    pathPaint.setColor(0xff000000);
    pathPaint.setAlpha(255);
    pathPaint.setColorFilter(NULL);
    pathPaint.setMaskFilter(NULL);
    pathPaint.setShader(NULL);
    SkXfermode* mode = SkXfermode::Create(SkXfermode::kSrc_Mode);
    SkSafeUnref(pathPaint.setXfermode(mode));

    SkCanvas canvas(bitmap);
    canvas.translate(-bounds.fLeft + offset, -bounds.fTop + offset);
    canvas.drawPath(*path, pathPaint);

    generateTexture(bitmap, texture);

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

    return texture;
}

template<class Entry>
void ShapeCache<Entry>::clear() {
    mCache.clear();
}

template<class Entry>
void ShapeCache<Entry>::generateTexture(SkBitmap& bitmap, Texture* texture) {
    SkAutoLockPixels alp(bitmap);
    if (!bitmap.readyToDraw()) {
        LOGE("Cannot generate texture from bitmap");
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
