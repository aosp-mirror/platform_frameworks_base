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
        : type(Type::None)
        , scaleX(1.0f)
        , scaleY(1.0f)
        , aa(false)
        , cap(SkPaint::kDefault_Cap)
        , style(SkPaint::kFill_Style)
        , strokeWidth(1.0f) {
    // Shape bits should be set to zeroes, because they are used for hash calculation.
    memset(&shape, 0, sizeof(Shape));
}

TessellationCache::Description::Description(Type type, const Matrix4& transform,
                                            const SkPaint& paint)
        : type(type)
        , aa(paint.isAntiAlias())
        , cap(paint.getStrokeCap())
        , style(paint.getStyle())
        , strokeWidth(paint.getStrokeWidth()) {
    PathTessellator::extractTessellationScales(transform, &scaleX, &scaleY);
    // Shape bits should be set to zeroes, because they are used for hash calculation.
    memset(&shape, 0, sizeof(Shape));
}

bool TessellationCache::Description::operator==(const TessellationCache::Description& rhs) const {
    if (type != rhs.type) return false;
    if (scaleX != rhs.scaleX) return false;
    if (scaleY != rhs.scaleY) return false;
    if (aa != rhs.aa) return false;
    if (cap != rhs.cap) return false;
    if (style != rhs.style) return false;
    if (strokeWidth != rhs.strokeWidth) return false;
    if (type == Type::None) return true;
    const Shape::RoundRect& lRect = shape.roundRect;
    const Shape::RoundRect& rRect = rhs.shape.roundRect;

    if (lRect.width != rRect.width) return false;
    if (lRect.height != rRect.height) return false;
    if (lRect.rx != rRect.rx) return false;
    return lRect.ry == rRect.ry;
}

hash_t TessellationCache::Description::hash() const {
    uint32_t hash = JenkinsHashMix(0, static_cast<int>(type));
    hash = JenkinsHashMix(hash, aa);
    hash = JenkinsHashMix(hash, cap);
    hash = JenkinsHashMix(hash, style);
    hash = JenkinsHashMix(hash, android::hash_type(strokeWidth));
    hash = JenkinsHashMix(hash, android::hash_type(scaleX));
    hash = JenkinsHashMix(hash, android::hash_type(scaleY));
    hash = JenkinsHashMixBytes(hash, (uint8_t*)&shape, sizeof(Shape));
    return JenkinsHashWhiten(hash);
}

void TessellationCache::Description::setupMatrixAndPaint(Matrix4* matrix, SkPaint* paint) const {
    matrix->loadScale(scaleX, scaleY, 1.0f);
    paint->setAntiAlias(aa);
    paint->setStrokeCap(cap);
    paint->setStyle(style);
    paint->setStrokeWidth(strokeWidth);
}

TessellationCache::ShadowDescription::ShadowDescription() : nodeKey(nullptr) {
    memset(&matrixData, 0, sizeof(matrixData));
}

TessellationCache::ShadowDescription::ShadowDescription(const SkPath* nodeKey,
                                                        const Matrix4* drawTransform)
        : nodeKey(nodeKey) {
    memcpy(&matrixData, drawTransform->data, sizeof(matrixData));
}

bool TessellationCache::ShadowDescription::operator==(
        const TessellationCache::ShadowDescription& rhs) const {
    return nodeKey == rhs.nodeKey && memcmp(&matrixData, &rhs.matrixData, sizeof(matrixData)) == 0;
}

hash_t TessellationCache::ShadowDescription::hash() const {
    uint32_t hash = JenkinsHashMixBytes(0, (uint8_t*)&nodeKey, sizeof(const void*));
    hash = JenkinsHashMixBytes(hash, (uint8_t*)&matrixData, sizeof(matrixData));
    return JenkinsHashWhiten(hash);
}

///////////////////////////////////////////////////////////////////////////////
// General purpose tessellation task processing
///////////////////////////////////////////////////////////////////////////////

class TessellationCache::TessellationTask : public Task<VertexBuffer*> {
public:
    TessellationTask(Tessellator tessellator, const Description& description)
            : tessellator(tessellator), description(description) {}

    ~TessellationTask() {}

    Tessellator tessellator;
    Description description;
};

class TessellationCache::TessellationProcessor : public TaskProcessor<VertexBuffer*> {
public:
    explicit TessellationProcessor(Caches& caches) : TaskProcessor<VertexBuffer*>(&caches.tasks) {}
    ~TessellationProcessor() {}

    virtual void onProcess(const sp<Task<VertexBuffer*> >& task) override {
        TessellationTask* t = static_cast<TessellationTask*>(task.get());
        ATRACE_NAME("shape tessellation");
        VertexBuffer* buffer = t->tessellator(t->description);
        t->setResult(buffer);
    }
};

class TessellationCache::Buffer {
public:
    explicit Buffer(const sp<Task<VertexBuffer*> >& task) : mTask(task), mBuffer(nullptr) {}

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
        if (mTask != nullptr) {
            mBuffer = mTask->getResult();
            LOG_ALWAYS_FATAL_IF(mBuffer == nullptr, "Failed to precache");
            mTask.clear();
        }
    }
    sp<Task<VertexBuffer*> > mTask;
    VertexBuffer* mBuffer;
};

///////////////////////////////////////////////////////////////////////////////
// Shadow tessellation task processing
///////////////////////////////////////////////////////////////////////////////

static void mapPointFakeZ(Vector3& point, const mat4* transformXY, const mat4* transformZ) {
    // map z coordinate with true 3d matrix
    point.z = transformZ->mapZ(point);

    // map x,y coordinates with draw/Skia matrix
    transformXY->mapPoint(point.x, point.y);
}

static void reverseVertexArray(Vertex* polygon, int len) {
    int n = len / 2;
    for (int i = 0; i < n; i++) {
        Vertex tmp = polygon[i];
        int k = len - 1 - i;
        polygon[i] = polygon[k];
        polygon[k] = tmp;
    }
}

void tessellateShadows(const Matrix4* drawTransform, const Rect* localClip, bool isCasterOpaque,
                       const SkPath* casterPerimeter, const Matrix4* casterTransformXY,
                       const Matrix4* casterTransformZ, const Vector3& lightCenter,
                       float lightRadius, VertexBuffer& ambientBuffer, VertexBuffer& spotBuffer) {
    // tessellate caster outline into a 2d polygon
    std::vector<Vertex> casterVertices2d;
    const float casterRefinementThreshold = 2.0f;
    PathTessellator::approximatePathOutlineVertices(*casterPerimeter, casterRefinementThreshold,
                                                    casterVertices2d);

    // Shadow requires CCW for now. TODO: remove potential double-reverse
    reverseVertexArray(&casterVertices2d.front(), casterVertices2d.size());

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
        minZ = std::min(minZ, casterPolygon[i].z);
        maxZ = std::max(maxZ, casterPolygon[i].z);
    }

    // map the centroid of the caster into 3d
    Vector2 centroid = ShadowTessellator::centroid2d(
            reinterpret_cast<const Vector2*>(&casterVertices2d.front()), casterVertexCount);
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
    ShadowTessellator::tessellateAmbientShadow(isCasterOpaque, casterPolygon, casterVertexCount,
                                               centroid3d, casterBounds, *localClip, maxZ,
                                               ambientBuffer);

    ShadowTessellator::tessellateSpotShadow(isCasterOpaque, casterPolygon, casterVertexCount,
                                            centroid3d, *drawTransform, lightCenter, lightRadius,
                                            casterBounds, *localClip, spotBuffer);
}

class ShadowProcessor : public TaskProcessor<TessellationCache::vertexBuffer_pair_t> {
public:
    explicit ShadowProcessor(Caches& caches)
            : TaskProcessor<TessellationCache::vertexBuffer_pair_t>(&caches.tasks) {}
    ~ShadowProcessor() {}

    virtual void onProcess(const sp<Task<TessellationCache::vertexBuffer_pair_t> >& task) override {
        TessellationCache::ShadowTask* t = static_cast<TessellationCache::ShadowTask*>(task.get());
        ATRACE_NAME("shadow tessellation");

        tessellateShadows(&t->drawTransform, &t->localClip, t->opaque, &t->casterPerimeter,
                          &t->transformXY, &t->transformZ, t->lightCenter, t->lightRadius,
                          t->ambientBuffer, t->spotBuffer);

        t->setResult(TessellationCache::vertexBuffer_pair_t(&t->ambientBuffer, &t->spotBuffer));
    }
};

///////////////////////////////////////////////////////////////////////////////
// Cache constructor/destructor
///////////////////////////////////////////////////////////////////////////////

TessellationCache::TessellationCache()
        : mMaxSize(MB(1))
        , mCache(LruCache<Description, Buffer*>::kUnlimitedCapacity)
        , mShadowCache(
                  LruCache<ShadowDescription, Task<vertexBuffer_pair_t*>*>::kUnlimitedCapacity) {
    mCache.setOnEntryRemovedListener(&mBufferRemovedListener);
    mShadowCache.setOnEntryRemovedListener(&mBufferPairRemovedListener);
    mDebugEnabled = Properties::debugLevel & kDebugCaches;
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

    if (mShadowCache.get(key)) return;
    sp<ShadowTask> task = new ShadowTask(drawTransform, localClip, opaque, casterPerimeter,
                                         transformXY, transformZ, lightCenter, lightRadius);
    if (mShadowProcessor == nullptr) {
        mShadowProcessor = new ShadowProcessor(Caches::getInstance());
    }
    mShadowProcessor->add(task);
    task->incStrong(nullptr);  // not using sp<>s, so manually ref while in the cache
    mShadowCache.put(key, task.get());
}

sp<TessellationCache::ShadowTask> TessellationCache::getShadowTask(
        const Matrix4* drawTransform, const Rect& localClip, bool opaque,
        const SkPath* casterPerimeter, const Matrix4* transformXY, const Matrix4* transformZ,
        const Vector3& lightCenter, float lightRadius) {
    ShadowDescription key(casterPerimeter, drawTransform);
    ShadowTask* task = static_cast<ShadowTask*>(mShadowCache.get(key));
    if (!task) {
        precacheShadows(drawTransform, localClip, opaque, casterPerimeter, transformXY, transformZ,
                        lightCenter, lightRadius);
        task = static_cast<ShadowTask*>(mShadowCache.get(key));
    }
    LOG_ALWAYS_FATAL_IF(task == nullptr, "shadow not precached");
    return task;
}

///////////////////////////////////////////////////////////////////////////////
// Tessellation precaching
///////////////////////////////////////////////////////////////////////////////

TessellationCache::Buffer* TessellationCache::getOrCreateBuffer(const Description& entry,
                                                                Tessellator tessellator) {
    Buffer* buffer = mCache.get(entry);
    if (!buffer) {
        // not cached, enqueue a task to fill the buffer
        sp<TessellationTask> task = new TessellationTask(tessellator, entry);
        buffer = new Buffer(task);

        if (mProcessor == nullptr) {
            mProcessor = new TessellationProcessor(Caches::getInstance());
        }
        mProcessor->add(task);
        bool inserted = mCache.put(entry, buffer);
        // Note to the static analyzer that this insert should always succeed.
        LOG_ALWAYS_FATAL_IF(!inserted, "buffers shouldn't spontaneously appear in the cache");
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
    SkRect rect =
            SkRect::MakeWH(description.shape.roundRect.width, description.shape.roundRect.height);
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

TessellationCache::Buffer* TessellationCache::getRoundRectBuffer(const Matrix4& transform,
                                                                 const SkPaint& paint, float width,
                                                                 float height, float rx, float ry) {
    Description entry(Description::Type::RoundRect, transform, paint);
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

};  // namespace uirenderer
};  // namespace android
