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

#ifndef ANDROID_HWUI_TESSELLATION_CACHE_H
#define ANDROID_HWUI_TESSELLATION_CACHE_H

#include "Debug.h"
#include "Matrix.h"
#include "Rect.h"
#include "Vector.h"
#include "VertexBuffer.h"
#include "thread/TaskProcessor.h"
#include "utils/Macros.h"
#include "utils/Pair.h"

#include <SkPaint.h>
#include <SkPath.h>

#include <utils/LruCache.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>

class SkBitmap;
class SkCanvas;
struct SkRect;

namespace android {
namespace uirenderer {

class Caches;
class VertexBuffer;

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

class TessellationCache {
public:
    typedef Pair<VertexBuffer*, VertexBuffer*> vertexBuffer_pair_t;

    struct Description {
        HASHABLE_TYPE(Description);
        enum class Type {
            None,
            RoundRect,
        };

        Type type;
        float scaleX;
        float scaleY;
        bool aa;
        SkPaint::Cap cap;
        SkPaint::Style style;
        float strokeWidth;
        union Shape {
            struct RoundRect {
                float width;
                float height;
                float rx;
                float ry;
            } roundRect;
        } shape;

        Description();
        Description(Type type, const Matrix4& transform, const SkPaint& paint);
        void setupMatrixAndPaint(Matrix4* matrix, SkPaint* paint) const;
    };

    struct ShadowDescription {
        HASHABLE_TYPE(ShadowDescription);
        const SkPath* nodeKey;
        float matrixData[16];

        ShadowDescription();
        ShadowDescription(const SkPath* nodeKey, const Matrix4* drawTransform);
    };

    class ShadowTask : public Task<vertexBuffer_pair_t> {
    public:
        ShadowTask(const Matrix4* drawTransform, const Rect& localClip, bool opaque,
                const SkPath* casterPerimeter, const Matrix4* transformXY, const Matrix4* transformZ,
                const Vector3& lightCenter, float lightRadius)
            : drawTransform(*drawTransform)
            , localClip(localClip)
            , opaque(opaque)
            , casterPerimeter(*casterPerimeter)
            , transformXY(*transformXY)
            , transformZ(*transformZ)
            , lightCenter(lightCenter)
            , lightRadius(lightRadius) {
        }

        /* Note - we deep copy all task parameters, because *even though* pointers into Allocator
         * controlled objects (like the SkPath and Matrix4s) should be safe for the entire frame,
         * certain Allocators are destroyed before trim() is called to flush incomplete tasks.
         *
         * These deep copies could be avoided, long term, by canceling or flushing outstanding
         * tasks before tearing down single-frame LinearAllocators.
         */
        const Matrix4 drawTransform;
        const Rect localClip;
        bool opaque;
        const SkPath casterPerimeter;
        const Matrix4 transformXY;
        const Matrix4 transformZ;
        const Vector3 lightCenter;
        const float lightRadius;
        VertexBuffer ambientBuffer;
        VertexBuffer spotBuffer;
    };

    TessellationCache();
    ~TessellationCache();

    /**
     * Clears the cache. This causes all TessellationBuffers to be deleted.
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
     * Also removes transient Shadow VertexBuffers, which aren't cached between frames.
     */
    void trim();

    // TODO: precache/get for Oval, Lines, Points, etc.

    void precacheRoundRect(const Matrix4& transform, const SkPaint& paint,
            float width, float height, float rx, float ry) {
        getRoundRectBuffer(transform, paint, width, height, rx, ry);
    }
    const VertexBuffer* getRoundRect(const Matrix4& transform, const SkPaint& paint,
            float width, float height, float rx, float ry);

    // TODO: delete these when switching to HWUI_NEW_OPS
    void precacheShadows(const Matrix4* drawTransform, const Rect& localClip,
            bool opaque, const SkPath* casterPerimeter,
            const Matrix4* transformXY, const Matrix4* transformZ,
            const Vector3& lightCenter, float lightRadius);
    void getShadowBuffers(const Matrix4* drawTransform, const Rect& localClip,
            bool opaque, const SkPath* casterPerimeter,
            const Matrix4* transformXY, const Matrix4* transformZ,
            const Vector3& lightCenter, float lightRadius,
            vertexBuffer_pair_t& outBuffers);

    sp<ShadowTask> getShadowTask(const Matrix4* drawTransform, const Rect& localClip,
            bool opaque, const SkPath* casterPerimeter,
            const Matrix4* transformXY, const Matrix4* transformZ,
            const Vector3& lightCenter, float lightRadius);

private:
    class Buffer;
    class TessellationTask;
    class TessellationProcessor;

    typedef VertexBuffer* (*Tessellator)(const Description&);

    Buffer* getRectBuffer(const Matrix4& transform, const SkPaint& paint,
            float width, float height);
    Buffer* getRoundRectBuffer(const Matrix4& transform, const SkPaint& paint,
            float width, float height, float rx, float ry);

    Buffer* getOrCreateBuffer(const Description& entry, Tessellator tessellator);

    const uint32_t mMaxSize;

    bool mDebugEnabled;

    mutable Mutex mLock;

    ///////////////////////////////////////////////////////////////////////////////
    // General tessellation caching
    ///////////////////////////////////////////////////////////////////////////////
    sp<TaskProcessor<VertexBuffer*> > mProcessor;
    LruCache<Description, Buffer*> mCache;
    class BufferRemovedListener : public OnEntryRemoved<Description, Buffer*> {
        void operator()(Description& description, Buffer*& buffer) override;
    };
    BufferRemovedListener mBufferRemovedListener;

    ///////////////////////////////////////////////////////////////////////////////
    // Shadow tessellation caching
    ///////////////////////////////////////////////////////////////////////////////
    sp<TaskProcessor<vertexBuffer_pair_t> > mShadowProcessor;

    // holds a pointer, and implicit strong ref to each shadow task of the frame
    LruCache<ShadowDescription, Task<vertexBuffer_pair_t>*> mShadowCache;
    class BufferPairRemovedListener : public OnEntryRemoved<ShadowDescription, Task<vertexBuffer_pair_t>*> {
        void operator()(ShadowDescription& description, Task<vertexBuffer_pair_t>*& bufferPairTask) override {
            bufferPairTask->decStrong(nullptr);
        }
    };
    BufferPairRemovedListener mBufferPairRemovedListener;

}; // class TessellationCache

void tessellateShadows(
        const Matrix4* drawTransform, const Rect* localClip,
        bool isCasterOpaque, const SkPath* casterPerimeter,
        const Matrix4* casterTransformXY, const Matrix4* casterTransformZ,
        const Vector3& lightCenter, float lightRadius,
        VertexBuffer& ambientBuffer, VertexBuffer& spotBuffer);

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATH_CACHE_H
