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

#include "DisplayListLogBuffer.h"
#include "RenderNode.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

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
 * Records drawing commands in a display list for later playback into an OpenGLRenderer.
 */
class ANDROID_API DisplayListRenderer: public StatefulBaseRenderer {
public:
    DisplayListRenderer();
    virtual ~DisplayListRenderer();

    void insertReorderBarrier(bool enableReorder);

    DisplayListData* finishRecording();

// ----------------------------------------------------------------------------
// Frame state operations
// ----------------------------------------------------------------------------
    virtual status_t prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void finish();
    virtual void interrupt();
    virtual void resume();

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------
    // Save (layer)
    virtual int save(int flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);
    virtual int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags);

    // Matrix
    virtual void translate(float dx, float dy, float dz = 0.0f);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);

    virtual void setMatrix(const SkMatrix& matrix);
    virtual void concatMatrix(const SkMatrix& matrix);

    // Clip
    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    virtual bool clipPath(const SkPath* path, SkRegion::Op op);
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op);

    // Misc - should be implemented with SkPaint inspection
    virtual void resetPaintFilter();
    virtual void setupPaintFilter(int clearBits, int setBits);

    bool isCurrentTransformSimple() {
        return currentTransform()->isSimple();
    }

// ----------------------------------------------------------------------------
// Canvas draw operations
// ----------------------------------------------------------------------------
    virtual status_t drawColor(int color, SkXfermode::Mode mode);

    // Bitmap-based
    virtual status_t drawBitmap(const SkBitmap* bitmap, const SkPaint* paint);
    virtual status_t drawBitmap(const SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint);
    virtual status_t drawBitmapData(const SkBitmap* bitmap, const SkPaint* paint);
    virtual status_t drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint);
    virtual status_t drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
            float left, float top, float right, float bottom, const SkPaint* paint);

    // Shapes
    virtual status_t drawRect(float left, float top, float right, float bottom,
            const SkPaint* paint);
    virtual status_t drawRects(const float* rects, int count, const SkPaint* paint);
    virtual status_t drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint* paint);
    virtual status_t drawCircle(float x, float y, float radius, const SkPaint* paint);
    virtual status_t drawCircle(CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
                CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint);
    virtual status_t drawOval(float left, float top, float right, float bottom,
            const SkPaint* paint);
    virtual status_t drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint* paint);
    virtual status_t drawPath(const SkPath* path, const SkPaint* paint);
    virtual status_t drawLines(const float* points, int count, const SkPaint* paint);
    virtual status_t drawPoints(const float* points, int count, const SkPaint* paint);

    // Text
    virtual status_t drawText(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
            DrawOpMode drawOpMode = kDrawOpMode_Immediate);
    virtual status_t drawTextOnPath(const char* text, int bytesCount, int count, const SkPath* path,
            float hOffset, float vOffset, const SkPaint* paint);
    virtual status_t drawPosText(const char* text, int bytesCount, int count,
            const float* positions, const SkPaint* paint);

// ----------------------------------------------------------------------------
// Canvas draw operations - special
// ----------------------------------------------------------------------------
    virtual status_t drawLayer(Layer* layer, float x, float y);
    virtual status_t drawRenderNode(RenderNode* renderNode, Rect& dirty, int32_t replayFlags);

    // TODO: rename for consistency
    virtual status_t callDrawGLFunction(Functor* functor, Rect& dirty);

    void setHighContrastText(bool highContrastText) {
        mHighContrastText = highContrastText;
    }
private:
    enum DeferredBarrierType {
        kBarrier_None,
        kBarrier_InOrder,
        kBarrier_OutOfOrder,
    };

    void flushRestoreToCount();
    void flushTranslate();
    void flushReorderBarrier();

    LinearAllocator& alloc() { return mDisplayListData->allocator; }

    // Each method returns final index of op
    size_t addOpAndUpdateChunk(DisplayListOp* op);
    // flushes any deferred operations, and appends the op
    size_t flushAndAddOp(DisplayListOp* op);

    size_t addStateOp(StateOp* op);
    size_t addDrawOp(DrawOp* op);
    size_t addRenderNodeOp(DrawRenderNodeOp* op);


    template<class T>
    inline const T* refBuffer(const T* srcBuffer, int32_t count) {
        if (!srcBuffer) return NULL;

        T* dstBuffer = (T*) mDisplayListData->allocator.alloc(count * sizeof(T));
        memcpy(dstBuffer, srcBuffer, count * sizeof(T));
        return dstBuffer;
    }

    inline char* refText(const char* text, size_t byteLength) {
        return (char*) refBuffer<uint8_t>((uint8_t*)text, byteLength);
    }

    inline const SkPath* refPath(const SkPath* path) {
        if (!path) return NULL;

        const SkPath* pathCopy = mPathMap.valueFor(path);
        if (pathCopy == NULL || pathCopy->getGenerationID() != path->getGenerationID()) {
            SkPath* newPathCopy = new SkPath(*path);
            newPathCopy->setSourcePath(path);

            pathCopy = newPathCopy;
            // replaceValueFor() performs an add if the entry doesn't exist
            mPathMap.replaceValueFor(path, pathCopy);
            mDisplayListData->paths.add(pathCopy);
        }
        if (mDisplayListData->sourcePaths.indexOf(path) < 0) {
            mCaches.resourceCache.incrementRefcount(path);
            mDisplayListData->sourcePaths.add(path);
        }
        return pathCopy;
    }

    inline const SkPaint* refPaint(const SkPaint* paint) {
        if (!paint) return NULL;

        const SkPaint* paintCopy = mPaintMap.valueFor(paint);
        if (paintCopy == NULL
                || paintCopy->getGenerationID() != paint->getGenerationID()
                // We can't compare shader pointers because that will always
                // change as we do partial copying via wrapping. However, if the
                // shader changes the paint generationID will have changed and
                // so we don't hit this comparison anyway
                || !(paint->getShader() && paintCopy->getShader()
                        && paint->getShader()->getGenerationID() == paintCopy->getShader()->getGenerationID())) {
            paintCopy = copyPaint(paint);
            // replaceValueFor() performs an add if the entry doesn't exist
            mPaintMap.replaceValueFor(paint, paintCopy);
        }

        return paintCopy;
    }

    inline SkPaint* copyPaint(const SkPaint* paint) {
        if (!paint) return NULL;
        SkPaint* paintCopy = new SkPaint(*paint);
        if (paint->getShader()) {
            SkShader* shaderCopy = SkShader::CreateLocalMatrixShader(
                    paint->getShader(), paint->getShader()->getLocalMatrix());
            paintCopy->setShader(shaderCopy);
            paintCopy->setGenerationID(paint->getGenerationID());
            shaderCopy->setGenerationID(paint->getShader()->getGenerationID());
            shaderCopy->unref();
        }
        mDisplayListData->paints.add(paintCopy);
        return paintCopy;
    }

    inline const SkRegion* refRegion(const SkRegion* region) {
        if (!region) {
            return region;
        }

        const SkRegion* regionCopy = mRegionMap.valueFor(region);
        // TODO: Add generation ID to SkRegion
        if (regionCopy == NULL) {
            regionCopy = new SkRegion(*region);
            // replaceValueFor() performs an add if the entry doesn't exist
            mRegionMap.replaceValueFor(region, regionCopy);
            mDisplayListData->regions.add(regionCopy);
        }

        return regionCopy;
    }

    inline Layer* refLayer(Layer* layer) {
        mDisplayListData->layers.add(layer);
        mCaches.resourceCache.incrementRefcount(layer);
        return layer;
    }

    inline const SkBitmap* refBitmap(const SkBitmap* bitmap) {
        // Note that this assumes the bitmap is immutable. There are cases this won't handle
        // correctly, such as creating the bitmap from scratch, drawing with it, changing its
        // contents, and drawing again. The only fix would be to always copy it the first time,
        // which doesn't seem worth the extra cycles for this unlikely case.
        mDisplayListData->bitmapResources.add(bitmap);
        mCaches.resourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline const SkBitmap* refBitmapData(const SkBitmap* bitmap) {
        mDisplayListData->ownedBitmapResources.add(bitmap);
        mCaches.resourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline const Res_png_9patch* refPatch(const Res_png_9patch* patch) {
        mDisplayListData->patchResources.add(patch);
        mCaches.resourceCache.incrementRefcount(patch);
        return patch;
    }

    DefaultKeyedVector<const SkPaint*, const SkPaint*> mPaintMap;
    DefaultKeyedVector<const SkPath*, const SkPath*> mPathMap;
    DefaultKeyedVector<const SkRegion*, const SkRegion*> mRegionMap;

    Caches& mCaches;
    DisplayListData* mDisplayListData;

    float mTranslateX;
    float mTranslateY;
    bool mHasDeferredTranslate;
    DeferredBarrierType mDeferredBarrierType;
    bool mHighContrastText;

    int mRestoreSaveCount;

    friend class RenderNode;

}; // class DisplayListRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
