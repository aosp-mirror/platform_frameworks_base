/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <utils/JenkinsHash.h>
#include <utils/Trace.h>

#include "Caches.h"
#include "OpenGLRenderer.h"
#include "PathTessellator.h"
#include "ShadowTessellator.h"
#include "TessellationCache.h"

#include "thread/Signal.h"
#include "thread/Task.h"
#include "thread/TaskProcessor.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Cache entries
///////////////////////////////////////////////////////////////////////////////

TessellationCache::Description::Description()
        : type(kNone)
        , scaleX(1.0f)
        , scaleY(1.0f)
        , aa(false)
        , cap(SkPaint::kDefault_Cap)
        , style(SkPaint::kFill_Style)
        , strokeWidth(1.0f) {
    memset(&shape, 0, sizeof(Shape));
}

TessellationCache::Description::Description(Type type, const Matrix4& transform, const SkPaint& paint)
        : type(type)
        , aa(paint.isAntiAlias())
        , cap(paint.getStrokeCap())
        , style(paint.getStyle())
        , strokeWidth(paint.getStrokeWidth()) {
    PathTessellator::extractTessellationScales(transform, &scaleX, &scaleY);
    memset(&shape, 0, sizeof(Shape));
}

hash_t TessellationCache::Description::hash() const {
    uint32_t hash = JenkinsHashMix(0, type);
    hash = JenkinsHashMix(hash, aa);
    hash = JenkinsHashMix(hash, cap);
    hash = JenkinsHashMix(hash, style);
    hash = JenkinsHashMix(hash, android::hash_type(strokeWidth));
    hash = JenkinsHashMix(hash, android::hash_type(scaleX));
    hash = JenkinsHashMix(hash, android::hash_type(scaleY));
    hash = JenkinsHashMixBytes(hash, (uint8_t*) &shape, sizeof(Shape));
    return JenkinsHashWhiten(hash);
}

void TessellationCache::Description::setupMatrixAndPaint(Matrix4* matrix, SkPaint* paint) const {
    matrix->loadScale(scaleX, scaleY, 1.0f);
    paint->setAntiAlias(aa);
    paint->setStrokeCap(cap);
    paint->setStyle(style);
    paint->setStrokeWidth(strokeWidth);
}

TessellationCache::ShadowDescription::ShadowDescription()
        : nodeKey(NULL) {
    memset(&matrixData, 0, 16 * sizeof(float));
}

TessellationCache::ShadowDescription::ShadowDescription(const void* nodeKey, const Matrix4* drawTransform)
        : nodeKey(nodeKey) {
    memcpy(&matrixData, drawTransform->data, 16 * sizeof(float));
}

hash_t TessellationCache::ShadowDescription::hash() const {
    uint32_t hash = JenkinsHashMixBytes(0, (uint8_t*) &nodeKey, sizeof(const void*));
    hash = JenkinsHashMixBytes(hash, (uint8_t*) &matrixData, 16 * sizeof(float));
    return JenkinsHashWhiten(hash);
}

///////////////////////////////////////////////////////////////////////////////
// General purpose tessellation task processing
///////////////////////////////////////////////////////////////////////////////

class TessellationCache::TessellationTask : public Task<VertexBuffer*> {
public:
    TessellationTask(Tessellator tessellator, const Description& description)
        : tessellator(tessellator)
        , description(description) {
    }

    ~TessellationTask() {}

    Tessellator tessellator;
    Description description;
};

class TessellationCache::TessellationProcessor : public TaskProcessor<VertexBuffer*> {
public:
    TessellationProcessor(Caches& caches)
            : TaskProcessor<VertexBuffer*>(&caches.tasks) {}
    ~TessellationProcessor() {}

    virtual void onProcess(const sp<Task<VertexBuffer*> >& task) {
        TessellationTask* t = static_cast<TessellationTask*>(task.get());
        ATRACE_NAME("shape tessellation");
        VertexBuffer* buffer = t->tessellator(t->description);
        t->setResult(buffer);
    }
};

class TessellationCache::Buffer {
public:
    Buffer(const sp<Task<VertexBuffer*> >& task)
            : mTask(task)
            , mBuffer(NULL) {
    }

    ~Buffer() {
        mTask.clear();
        delete mBuffer;
    }

    unsigned int getSize() {
        blockOnPrecache();
        return mBuffer->getSize();
    }

    const VertexBuffer* getVertexBuffer() {
        blockOnPrecache();
        return mBuffer;
    }

private:
    void blockOnPrecache() {
        if (mTask != NULL) {
            mBuffer = mTask->getResult();
            LOG_ALWAYS_FATAL_IF(mBuffer == NULL, "Failed to precache");
            mTask.clear();
        }
    }
    sp<Task<VertexBuffer*> > mTask;
    VertexBuffer* mBuffer;
};

///////////////////////////////////////////////////////////////////////////////
// Shadow tessellation task processing
///////////////////////////////////////////////////////////////////////////////

class ShadowTask : public Task<TessellationCache::vertexBuffer_pair_t*> {
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

    ~ShadowTask() {
        TessellationCache::vertexBuffer_pair_t* bufferPair = getResult();
        delete bufferPair->getFirst();
        delete bufferPair->getSecond();
        delete bufferPair;
    }

    /* Note - we deep copy all task parameters, because *even though* pointers into Allocator
     * controlled objects (like the SkPath and Matrix4s) should be safe for the entire frame,
     * certain Allocators are destroyed before trim() is called to flush incomplete tasks.
     *
     * These deep copies could be avoided, long term, by cancelling or flushing outstanding tasks
     * before tearning down single-frame LinearAllocators.
     */
    const Matrix4 drawTransform;
    const Rect localClip;
    bool opaque;
    const SkPath casterPerimeter;
    const Matrix4 transformXY;
    const Matrix4 transformZ;
    const Vector3 lightCenter;
    const float lightRadius;
};

static void mapPointFakeZ(Vector3& point, const mat4* transformXY, const mat4* transformZ) {
    // map z coordinate with true 3d matrix
    point.z = transformZ->mapZ(point);

    // map x,y coordinates with draw/Skia matrix
    transformXY->mapPoint(point.x, point.y);
}

static void tessellateShadows(
        const Matrix4* drawTransform, const Rect* localClip,
        bool isCasterOpaque, const SkPath* casterPerimeter,
        const Matrix4* casterTransformXY, const Matrix4* casterTransformZ,
        const Vector3& lightCenter, float lightRadius,
        VertexBuffer& ambientBuffer, VertexBuffer& spotBuffer) {

    // tessellate caster outline into a 2d polygon
    Vector<Vertex> casterVertices2d;
    const float casterRefinementThresholdSquared = 4.0f;
    PathTessellator::approximatePathOutlineVertices(*casterPerimeter,
            casterRefinementThresholdSquared, casterVertices2d);
    if (!ShadowTessellator::isClockwisePath(*casterPerimeter)) {
        ShadowTessellator::reverseVertexArray(casterVertices2d.editArray(),
                casterVertices2d.size());
    }

    if (casterVertices2d.size() == 0) return;

    // map 2d caster poly into 3d
    const int casterVertexCount = casterVertices2d.size();
    Vector3 casterPolygon[casterVertexCount];
    float minZ = FLT_MAX;
    float maxZ = -FLT_MAX;
    for (int i = 0; i < casterVertexCount; i++) {
        const Vertex& point2d = casterVertices2d[i];
        casterPolygon[i] = (Vector3){point2d.x, point2d.y, 0};
        mapPointFakeZ(casterPolygon[i], casterTransformXY, casterTransformZ);
        minZ = fmin(minZ, casterPolygon[i].z);
        maxZ = fmax(maxZ, casterPolygon[i].z);
    }

    // map the centroid of the caster into 3d
    Vector2 centroid =  ShadowTessellator::centroid2d(
            reinterpret_cast<const Vector2*>(casterVertices2d.array()),
            casterVertexCount);
    Vector3 centroid3d = {centroid.x, centroid.y, 0};
    mapPointFakeZ(centroid3d, casterTransformXY, casterTransformZ);

    // if the caster intersects the z=0 plane, lift it in Z so it doesn't
    if (minZ < SHADOW_MIN_CASTER_Z) {
        float casterLift = SHADOW_MIN_CASTER_Z - minZ;
        for (int i = 0; i < casterVertexCount; i++) {
            casterPolygon[i].z += casterLift;
        }
        centroid3d.z += casterLift;
    }

    // Check whether we want to draw the shadow at all by checking the caster's bounds against clip.
    // We only have ortho projection, so we can just ignore the Z in caster for
    // simple rejection calculation.
    Rect casterBounds(casterPerimeter->getBounds());
    casterTransformXY->mapRect(casterBounds);

    // actual tessellation of both shadows
    ShadowTessellator::tessellateAmbientShadow(
            isCasterOpaque, casterPolygon, casterVertexCount, centroid3d,
            casterBounds, *localClip, maxZ, ambientBuffer);

    ShadowTessellator::tessellateSpotShadow(
            isCasterOpaque, casterPolygon, casterVertexCount, centroid3d,
            *drawTransform, lightCenter, lightRadius, casterBounds, *localClip,
            spotBuffer);
}

class ShadowProcessor : public TaskProcessor<TessellationCache::vertexBuffer_pair_t*> {
public:
    ShadowProcessor(Caches& caches)
            : TaskProcessor<TessellationCache::vertexBuffer_pair_t*>(&caches.tasks) {}
    ~ShadowProcessor() {}

    virtual void onProcess(const sp<Task<TessellationCache::vertexBuffer_pair_t*> >& task) {
        ShadowTask* t = static_cast<ShadowTask*>(task.get());
        ATRACE_NAME("shadow tessellation");

        VertexBuffer* ambientBuffer = new VertexBuffer;
        VertexBuffer* spotBuffer = new VertexBuffer;
        tessellateShadows(&t->drawTransform, &t->localClip, t->opaque, &t->casterPerimeter,
                &t->transformXY, &t->transformZ, t->lightCenter, t->lightRadius,
                *ambientBuffer, *spotBuffer);

        t->setResult(new TessellationCache::vertexBuffer_pair_t(ambientBuffer, spotBuffer));
    }
};

///////////////////////////////////////////////////////////////////////////////
// Cache constructor/destructor
///////////////////////////////////////////////////////////////////////////////

TessellationCache::TessellationCache()
        : mSize(0)
        , mMaxSize(MB(DEFAULT_VERTEX_CACHE_SIZE))
        , mCache(LruCache<Description, Buffer*>::kUnlimitedCapacity)
        , mShadowCache(LruCache<ShadowDescription, Task<vertexBuffer_pair_t*>*>::kUnlimitedCapacity) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_VERTEX_CACHE_SIZE, property, NULL) > 0) {
        INIT_LOGD("  Setting %s cache size to %sMB", name, property);
        setMaxSize(MB(atof(property)));
    } else {
        INIT_LOGD("  Using default %s cache size of %.2fMB", name, DEFAULT_VERTEX_CACHE_SIZE);
    }

    mCache.setOnEntryRemovedListener(&mBufferRemovedListener);
    mShadowCache.setOnEntryRemovedListener(&mBufferPairRemovedListener);
    mDebugEnabled = readDebugLevel() & kDebugCaches;
}

TessellationCache::~TessellationCache() {
    mCache.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

uint32_t TessellationCache::getSize() {
    LruCache<Description, Buffer*>::Iterator iter(mCache);
    uint32_t size = 0;
    while (iter.next()) {
        size += iter.value()->getSize();
    }
    return size;
}

uint32_t TessellationCache::getMaxSize() {
    return mMaxSize;
}

void TessellationCache::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////


void TessellationCache::trim() {
    uint32_t size = getSize();
    while (size > mMaxSize) {
        size -= mCache.peekOldestValue()->getSize();
        mCache.removeOldest();
    }
    mShadowCache.clear();
}

void TessellationCache::clear() {
    mCache.clear();
    mShadowCache.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void TessellationCache::BufferRemovedListener::operator()(Description& description,
        Buffer*& buffer) {
    delete buffer;
}

///////////////////////////////////////////////////////////////////////////////
// Shadows
///////////////////////////////////////////////////////////////////////////////

void TessellationCache::precacheShadows(const Matrix4* drawTransform, const Rect& localClip,
        bool opaque, const SkPath* casterPerimeter,
        const Matrix4* transformXY, const Matrix4* transformZ,
        const Vector3& lightCenter, float lightRadius) {
    ShadowDescription key(casterPerimeter, drawTransform);

    sp<ShadowTask> task = new ShadowTask(drawTransform, localClip, opaque,
            casterPerimeter, transformXY, transformZ, lightCenter, lightRadius);
    if (mShadowProcessor == NULL) {
        mShadowProcessor = new ShadowProcessor(Caches::getInstance());
    }
    mShadowProcessor->add(task);

    task->incStrong(NULL); // not using sp<>s, so manually ref while in the cache
    mShadowCache.put(key, task.get());
}

void TessellationCache::getShadowBuffers(const Matrix4* drawTransform, const Rect& localClip,
        bool opaque, const SkPath* casterPerimeter,
        const Matrix4* transformXY, const Matrix4* transformZ,
        const Vector3& lightCenter, float lightRadius, vertexBuffer_pair_t& outBuffers) {
    ShadowDescription key(casterPerimeter, drawTransform);
    ShadowTask* task = static_cast<ShadowTask*>(mShadowCache.get(key));
    if (!task) {
        precacheShadows(drawTransform, localClip, opaque, casterPerimeter,
                transformXY, transformZ, lightCenter, lightRadius);
        task = static_cast<ShadowTask*>(mShadowCache.get(key));
    }
    LOG_ALWAYS_FATAL_IF(task == NULL, "shadow not precached");
    outBuffers = *(task->getResult());
}

///////////////////////////////////////////////////////////////////////////////
// Tessellation precaching
///////////////////////////////////////////////////////////////////////////////

TessellationCache::Buffer* TessellationCache::getOrCreateBuffer(
        const Description& entry, Tessellator tessellator) {
    Buffer* buffer = mCache.get(entry);
    if (!buffer) {
        // not cached, enqueue a task to fill the buffer
        sp<TessellationTask> task = new TessellationTask(tessellator, entry);
        buffer = new Buffer(task);

        if (mProcessor == NULL) {
            mProcessor = new TessellationProcessor(Caches::getInstance());
        }
        mProcessor->add(task);
        mCache.put(entry, buffer);
    }
    return buffer;
}

static VertexBuffer* tessellatePath(const TessellationCache::Description& description,
        const SkPath& path) {
    Matrix4 matrix;
    SkPaint paint;
    description.setupMatrixAndPaint(&matrix, &paint);
    VertexBuffer* buffer = new VertexBuffer();
    PathTessellator::tessellatePath(path, &paint, matrix, *buffer);
    return buffer;
}

///////////////////////////////////////////////////////////////////////////////
// RoundRect
///////////////////////////////////////////////////////////////////////////////

static VertexBuffer* tessellateRoundRect(const TessellationCache::Description& description) {
    SkRect rect = SkRect::MakeWH(description.shape.roundRect.width,
            description.shape.roundRect.height);
    float rx = description.shape.roundRect.rx;
    float ry = description.shape.roundRect.ry;
    if (description.style == SkPaint::kStrokeAndFill_Style) {
        float outset = description.strokeWidth / 2;
        rect.outset(outset, outset);
        rx += outset;
        ry += outset;
    }
    SkPath path;
    path.addRoundRect(rect, rx, ry);
    return tessellatePath(description, path);
}

TessellationCache::Buffer* TessellationCache::getRoundRectBuffer(
        const Matrix4& transform, const SkPaint& paint,
        float width, float height, float rx, float ry) {
    Description entry(Description::kRoundRect, transform, paint);
    entry.shape.roundRect.width = width;
    entry.shape.roundRect.height = height;
    entry.shape.roundRect.rx = rx;
    entry.shape.roundRect.ry = ry;
    return getOrCreateBuffer(entry, &tessellateRoundRect);
}
const VertexBuffer* TessellationCache::getRoundRect(const Matrix4& transform, const SkPaint& paint,
        float width, float height, float rx, float ry) {
    return getRoundRectBuffer(transform, paint, width, height, rx, ry)->getVertexBuffer();
}

}; // namespace uirenderer
}; // namespace android
