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

#ifndef ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
#define ANDROID_HWUI_DISPLAY_LIST_RENDERER_H

#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <cutils/compiler.h>

#include "DisplayList.h"
#include "DisplayListLogBuffer.h"
#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define MIN_WRITER_SIZE 4096
#define OP_MAY_BE_SKIPPED_MASK 0xff000000

// Debug
#if DEBUG_DISPLAY_LIST
    #define DISPLAY_LIST_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define DISPLAY_LIST_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Display list
///////////////////////////////////////////////////////////////////////////////

class DeferredDisplayList;
class DisplayListRenderer;
class DisplayListOp;
class DrawOp;
class StateOp;

/**
 * Records drawing commands in a display list for latter playback.
 */
class DisplayListRenderer: public OpenGLRenderer {
public:
    ANDROID_API DisplayListRenderer();
    virtual ~DisplayListRenderer();

    ANDROID_API DisplayList* getDisplayList(DisplayList* displayList);

    virtual bool isDeferred();

    virtual void setViewport(int width, int height);
    virtual status_t prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void finish();

    virtual status_t callDrawGLFunction(Functor *functor, Rect& dirty);

    virtual void interrupt();
    virtual void resume();

    virtual int save(int flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);

    virtual int saveLayer(float left, float top, float right, float bottom,
            int alpha, SkXfermode::Mode mode, int flags);

    virtual void translate(float dx, float dy);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);

    virtual void setMatrix(SkMatrix* matrix);
    virtual void concatMatrix(SkMatrix* matrix);

    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    virtual bool clipPath(SkPath* path, SkRegion::Op op);
    virtual bool clipRegion(SkRegion* region, SkRegion::Op op);

    virtual status_t drawDisplayList(DisplayList* displayList, Rect& dirty, int32_t flags);
    virtual status_t drawLayer(Layer* layer, float x, float y);
    virtual status_t drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint);
    virtual status_t drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint);
    virtual status_t drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, SkPaint* paint);
    virtual status_t drawBitmapData(SkBitmap* bitmap, float left, float top, SkPaint* paint);
    virtual status_t drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
            float* vertices, int* colors, SkPaint* paint);
    virtual status_t drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
            const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
            float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawColor(int color, SkXfermode::Mode mode);
    virtual status_t drawRect(float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, SkPaint* paint);
    virtual status_t drawCircle(float x, float y, float radius, SkPaint* paint);
    virtual status_t drawOval(float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, SkPaint* paint);
    virtual status_t drawPath(SkPath* path, SkPaint* paint);
    virtual status_t drawLines(float* points, int count, SkPaint* paint);
    virtual status_t drawPoints(float* points, int count, SkPaint* paint);
    virtual status_t drawTextOnPath(const char* text, int bytesCount, int count, SkPath* path,
            float hOffset, float vOffset, SkPaint* paint);
    virtual status_t drawPosText(const char* text, int bytesCount, int count,
            const float* positions, SkPaint* paint);
    virtual status_t drawText(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, SkPaint* paint, float length, DrawOpMode drawOpMode);

    virtual status_t drawRects(const float* rects, int count, SkPaint* paint);

    virtual void resetShader();
    virtual void setupShader(SkiaShader* shader);

    virtual void resetColorFilter();
    virtual void setupColorFilter(SkiaColorFilter* filter);

    virtual void resetShadow();
    virtual void setupShadow(float radius, float dx, float dy, int color);

    virtual void resetPaintFilter();
    virtual void setupPaintFilter(int clearBits, int setBits);

    ANDROID_API void reset();

    sp<DisplayListData> getDisplayListData() const {
        return mDisplayListData;
    }

    const Vector<SkBitmap*>& getBitmapResources() const {
        return mBitmapResources;
    }

    const Vector<SkBitmap*>& getOwnedBitmapResources() const {
        return mOwnedBitmapResources;
    }

    const Vector<SkiaColorFilter*>& getFilterResources() const {
        return mFilterResources;
    }

    const Vector<SkiaShader*>& getShaders() const {
        return mShaders;
    }

    const Vector<SkPaint*>& getPaints() const {
        return mPaints;
    }

    const Vector<SkPath*>& getPaths() const {
        return mPaths;
    }

    const SortedVector<SkPath*>& getSourcePaths() const {
        return mSourcePaths;
    }

    const Vector<SkRegion*>& getRegions() const {
        return mRegions;
    }

    const Vector<Layer*>& getLayers() const {
        return mLayers;
    }

    const Vector<SkMatrix*>& getMatrices() const {
        return mMatrices;
    }

    uint32_t getFunctorCount() const {
        return mFunctorCount;
    }

private:
    void insertRestoreToCount();
    void insertTranslate();

    LinearAllocator& alloc() { return mDisplayListData->allocator; }
    void addStateOp(StateOp* op);
    void addDrawOp(DrawOp* op);
    void addOpInternal(DisplayListOp* op) {
        insertRestoreToCount();
        insertTranslate();
        mDisplayListData->displayListOps.add(op);
    }

    template<class T>
    inline T* refBuffer(const T* srcBuffer, int32_t count) {
        if (srcBuffer == NULL) return NULL;
        T* dstBuffer = (T*) mDisplayListData->allocator.alloc(count * sizeof(T));
        memcpy(dstBuffer, srcBuffer, count * sizeof(T));
        return dstBuffer;
    }

    inline char* refText(const char* text, size_t byteLength) {
        return (char*) refBuffer<uint8_t>((uint8_t*)text, byteLength);
    }

    inline SkPath* refPath(SkPath* path) {
        if (!path) return NULL;

        SkPath* pathCopy = mPathMap.valueFor(path);
        if (pathCopy == NULL || pathCopy->getGenerationID() != path->getGenerationID()) {
            pathCopy = new SkPath(*path);
            pathCopy->setSourcePath(path);
            // replaceValueFor() performs an add if the entry doesn't exist
            mPathMap.replaceValueFor(path, pathCopy);
            mPaths.add(pathCopy);
        }
        if (mSourcePaths.indexOf(path) < 0) {
            mCaches.resourceCache.incrementRefcount(path);
            mSourcePaths.add(path);
        }
        return pathCopy;
    }

    inline SkPaint* refPaint(SkPaint* paint) {
        if (!paint) {
            return paint;
        }

        SkPaint* paintCopy = mPaintMap.valueFor(paint);
        if (paintCopy == NULL || paintCopy->getGenerationID() != paint->getGenerationID()) {
            paintCopy = new SkPaint(*paint);
            // replaceValueFor() performs an add if the entry doesn't exist
            mPaintMap.replaceValueFor(paint, paintCopy);
            mPaints.add(paintCopy);
        }

        return paintCopy;
    }

    inline SkRegion* refRegion(SkRegion* region) {
        if (!region) {
            return region;
        }

        SkRegion* regionCopy = mRegionMap.valueFor(region);
        // TODO: Add generation ID to SkRegion
        if (regionCopy == NULL) {
            regionCopy = new SkRegion(*region);
            // replaceValueFor() performs an add if the entry doesn't exist
            mRegionMap.replaceValueFor(region, regionCopy);
            mRegions.add(regionCopy);
        }

        return regionCopy;
    }

    inline SkMatrix* refMatrix(SkMatrix* matrix) {
        // Copying the matrix is cheap and prevents against the user changing the original
        // matrix before the operation that uses it
        SkMatrix* copy = new SkMatrix(*matrix);
        mMatrices.add(copy);
        return copy;
    }

    inline Layer* refLayer(Layer* layer) {
        mLayers.add(layer);
        mCaches.resourceCache.incrementRefcount(layer);
        return layer;
    }

    inline SkBitmap* refBitmap(SkBitmap* bitmap) {
        // Note that this assumes the bitmap is immutable. There are cases this won't handle
        // correctly, such as creating the bitmap from scratch, drawing with it, changing its
        // contents, and drawing again. The only fix would be to always copy it the first time,
        // which doesn't seem worth the extra cycles for this unlikely case.
        mBitmapResources.add(bitmap);
        mCaches.resourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline SkBitmap* refBitmapData(SkBitmap* bitmap) {
        mOwnedBitmapResources.add(bitmap);
        mCaches.resourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline SkiaShader* refShader(SkiaShader* shader) {
        if (!shader) return NULL;

        SkiaShader* shaderCopy = mShaderMap.valueFor(shader);
        // TODO: We also need to handle generation ID changes in compose shaders
        if (shaderCopy == NULL || shaderCopy->getGenerationId() != shader->getGenerationId()) {
            shaderCopy = shader->copy();
            // replaceValueFor() performs an add if the entry doesn't exist
            mShaderMap.replaceValueFor(shader, shaderCopy);
            mShaders.add(shaderCopy);
            mCaches.resourceCache.incrementRefcount(shaderCopy);
        }
        return shaderCopy;
    }

    inline SkiaColorFilter* refColorFilter(SkiaColorFilter* colorFilter) {
        mFilterResources.add(colorFilter);
        mCaches.resourceCache.incrementRefcount(colorFilter);
        return colorFilter;
    }

    Vector<SkBitmap*> mBitmapResources;
    Vector<SkBitmap*> mOwnedBitmapResources;
    Vector<SkiaColorFilter*> mFilterResources;

    Vector<SkPaint*> mPaints;
    DefaultKeyedVector<SkPaint*, SkPaint*> mPaintMap;

    Vector<SkPath*> mPaths;
    DefaultKeyedVector<SkPath*, SkPath*> mPathMap;

    SortedVector<SkPath*> mSourcePaths;

    Vector<SkRegion*> mRegions;
    DefaultKeyedVector<SkRegion*, SkRegion*> mRegionMap;

    Vector<SkiaShader*> mShaders;
    DefaultKeyedVector<SkiaShader*, SkiaShader*> mShaderMap;

    Vector<SkMatrix*> mMatrices;

    Vector<Layer*> mLayers;

    int mRestoreSaveCount;

    Caches& mCaches;
    sp<DisplayListData> mDisplayListData;

    float mTranslateX;
    float mTranslateY;
    bool mHasTranslate;
    bool mHasDrawOps;

    uint32_t mFunctorCount;

    friend class DisplayList;

}; // class DisplayListRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
