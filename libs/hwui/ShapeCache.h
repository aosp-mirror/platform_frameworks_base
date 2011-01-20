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

#include <SkCanvas.h>
#include <SkRect.h>

#include "PathCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_SHAPES
    #define SHAPE_LOGD(...) LOGD(__VA_ARGS__)
#else
    #define SHAPE_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

/**
 * Describe a shape in the shape cache.
 */
struct ShapeCacheEntry {
    enum ShapeType {
        kShapeNone,
        kShapeRoundRect,
        kShapeCircle,
        kShapeOval,
        kShapeArc
    };

    ShapeCacheEntry() {
        shapeType = kShapeNone;
        join = SkPaint::kDefault_Join;
        cap = SkPaint::kDefault_Cap;
        style = SkPaint::kFill_Style;
        miter = 4.0f;
        strokeWidth = 1.0f;
    }

    ShapeCacheEntry(const ShapeCacheEntry& entry):
        shapeType(entry.shapeType), join(entry.join), cap(entry.cap),
        style(entry.style), miter(entry.miter),
        strokeWidth(entry.strokeWidth) {
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
    }

    virtual ~ShapeCacheEntry() {
    }

    // shapeType must be checked in subclasses operator<
    ShapeType shapeType;
    SkPaint::Join join;
    SkPaint::Cap cap;
    SkPaint::Style style;
    uint32_t miter;
    uint32_t strokeWidth;

    bool operator<(const ShapeCacheEntry& rhs) const {
        LTE_INT(shapeType) {
            LTE_INT(join) {
                LTE_INT(cap) {
                    LTE_INT(style) {
                        LTE_INT(miter) {
                            LTE_INT(strokeWidth) {
                                return lessThan(rhs);
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

    RoundRectShapeCacheEntry(const RoundRectShapeCacheEntry& entry):
            ShapeCacheEntry(entry) {
        mWidth = entry.mWidth;
        mHeight = entry.mHeight;
        mRx = entry.mRx;
        mRy = entry.mRy;
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

    CircleShapeCacheEntry(const CircleShapeCacheEntry& entry):
            ShapeCacheEntry(entry) {
        mRadius = entry.mRadius;
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

/**
 * A simple LRU shape cache. The cache has a maximum size expressed in bytes.
 * Any texture added to the cache causing the cache to grow beyond the maximum
 * allowed size will also cause the oldest texture to be kicked out.
 */
template<typename Entry>
class ShapeCache: public OnEntryRemoved<Entry, PathTexture*> {
public:
    ShapeCache();
    ShapeCache(uint32_t maxByteSize);
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

private:
    /**
     * Generates the texture from a bitmap into the specified texture structure.
     */
    void generateTexture(SkBitmap& bitmap, Texture* texture);

    void removeTexture(PathTexture* texture);

    void init();

    GenerationCache<Entry, PathTexture*> mCache;
    uint32_t mSize;
    uint32_t mMaxSize;
    GLuint mMaxTextureSize;

    bool mDebugEnabled;
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
}; // class RoundRectShapeCache


///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

template<class Entry>
ShapeCache<Entry>::ShapeCache():
        mCache(GenerationCache<ShapeCacheEntry, PathTexture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_SHAPE_CACHE_SIZE)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_SHAPE_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting shape cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default shape cache size of %.2fMB", DEFAULT_SHAPE_CACHE_SIZE);
    }
    init();
}

template<class Entry>
ShapeCache<Entry>::ShapeCache(uint32_t maxByteSize):
        mCache(GenerationCache<ShapeCacheEntry, PathTexture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(maxByteSize) {
    init();
}

template<class Entry>
ShapeCache<Entry>::~ShapeCache() {
    mCache.clear();
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

        SHAPE_LOGD("ShapeCache::callback: delete path: name, size, mSize = %d, %d, %d",
                texture->id, size, mSize);
        if (mDebugEnabled) {
            LOGD("Path deleted, size = %d", size);
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

    if (pathWidth > mMaxTextureSize || pathHeight > mMaxTextureSize) {
        LOGW("Shape too large to be rendered into a texture");
        return NULL;
    }

    const float offset = paint->getStrokeWidth() * 1.5f;
    const uint32_t width = uint32_t(pathWidth + offset * 2.0 + 0.5);
    const uint32_t height = uint32_t(pathHeight + offset * 2.0 + 0.5);

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
    pathPaint.setXfermode(mode)->safeUnref();

    SkCanvas canvas(bitmap);
    canvas.translate(-bounds.fLeft + offset, -bounds.fTop + offset);
    canvas.drawPath(*path, pathPaint);

    generateTexture(bitmap, texture);

    if (size < mMaxSize) {
        mSize += size;
        SHAPE_LOGD("ShapeCache::get: create path: name, size, mSize = %d, %d, %d",
                texture->id, size, mSize);
        if (mDebugEnabled) {
            LOGD("Shape created, size = %d", size);
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

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SHAPE_CACHE_H
