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

#define LOG_TAG "OpenGLRenderer"

#include <SkCamera.h>
#include <SkCanvas.h>

#include <private/hwui/DrawGlInfo.h>

#include "ResourceCache.h"
#include "DeferredDisplayList.h"
#include "DeferredLayerUpdater.h"
#include "DisplayListLogBuffer.h"
#include "DisplayListOp.h"
#include "DisplayListRenderer.h"
#include "RenderNode.h"
#include "utils/PaintUtils.h"

namespace android {
namespace uirenderer {

DisplayListRenderer::DisplayListRenderer()
    : mState(*this)
    , mResourceCache(ResourceCache::getInstance())
    , mDisplayListData(NULL)
    , mTranslateX(0.0f)
    , mTranslateY(0.0f)
    , mHasDeferredTranslate(false)
    , mDeferredBarrierType(kBarrier_None)
    , mHighContrastText(false)
    , mRestoreSaveCount(-1) {
}

DisplayListRenderer::~DisplayListRenderer() {
    LOG_ALWAYS_FATAL_IF(mDisplayListData,
            "Destroyed a DisplayListRenderer during a record!");
}

///////////////////////////////////////////////////////////////////////////////
// Operations
///////////////////////////////////////////////////////////////////////////////

DisplayListData* DisplayListRenderer::finishRecording() {
    mPaintMap.clear();
    mRegionMap.clear();
    mPathMap.clear();
    DisplayListData* data = mDisplayListData;
    mDisplayListData = 0;
    return data;
}

void DisplayListRenderer::prepareDirty(float left, float top,
        float right, float bottom) {

    LOG_ALWAYS_FATAL_IF(mDisplayListData,
            "prepareDirty called a second time during a recording!");
    mDisplayListData = new DisplayListData();

    mState.initializeSaveStack(0, 0, mState.getWidth(), mState.getHeight(), Vector3());

    mDeferredBarrierType = kBarrier_InOrder;
    mState.setDirtyClip(false);
    mRestoreSaveCount = -1;
}

bool DisplayListRenderer::finish() {
    flushRestoreToCount();
    flushTranslate();
    return false;
}

void DisplayListRenderer::interrupt() {
}

void DisplayListRenderer::resume() {
}

void DisplayListRenderer::callDrawGLFunction(Functor *functor, Rect& dirty) {
    // Ignore dirty during recording, it matters only when we replay
    addDrawOp(new (alloc()) DrawFunctorOp(functor));
    mDisplayListData->functors.add(functor);
}

int DisplayListRenderer::save(SkCanvas::SaveFlags flags) {
    addStateOp(new (alloc()) SaveOp((int) flags));
    return mState.save((int) flags);
}

void DisplayListRenderer::restore() {
    if (mRestoreSaveCount < 0) {
        restoreToCount(getSaveCount() - 1);
        return;
    }

    mRestoreSaveCount--;
    flushTranslate();
    mState.restore();
}

void DisplayListRenderer::restoreToCount(int saveCount) {
    mRestoreSaveCount = saveCount;
    flushTranslate();
    mState.restoreToCount(saveCount);
}

int DisplayListRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* paint, SkCanvas::SaveFlags flags) {
    // force matrix/clip isolation for layer
    flags |= SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag;

    paint = refPaint(paint);
    addStateOp(new (alloc()) SaveLayerOp(left, top, right, bottom, paint, (int) flags));
    return mState.save((int) flags);
}

void DisplayListRenderer::translate(float dx, float dy) {
    mHasDeferredTranslate = true;
    mTranslateX += dx;
    mTranslateY += dy;
    flushRestoreToCount();
    mState.translate(dx, dy, 0.0f);
}

void DisplayListRenderer::rotate(float degrees) {
    addStateOp(new (alloc()) RotateOp(degrees));
    mState.rotate(degrees);
}

void DisplayListRenderer::scale(float sx, float sy) {
    addStateOp(new (alloc()) ScaleOp(sx, sy));
    mState.scale(sx, sy);
}

void DisplayListRenderer::skew(float sx, float sy) {
    addStateOp(new (alloc()) SkewOp(sx, sy));
    mState.skew(sx, sy);
}

void DisplayListRenderer::setMatrix(const SkMatrix& matrix) {
    addStateOp(new (alloc()) SetMatrixOp(matrix));
    mState.setMatrix(matrix);
}

void DisplayListRenderer::concat(const SkMatrix& matrix) {
    addStateOp(new (alloc()) ConcatMatrixOp(matrix));
    mState.concatMatrix(matrix);
}

bool DisplayListRenderer::getClipBounds(SkRect* outRect) const {
    Rect bounds = mState.getLocalClipBounds();
    *outRect = SkRect::MakeLTRB(bounds.left, bounds.top, bounds.right, bounds.bottom);
    return !(outRect->isEmpty());
}

bool DisplayListRenderer::quickRejectRect(float left, float top, float right, float bottom) const {
    return mState.quickRejectConservative(left, top, right, bottom);
}

bool DisplayListRenderer::quickRejectPath(const SkPath& path) const {
    SkRect bounds = path.getBounds();
    return mState.quickRejectConservative(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom);
}


bool DisplayListRenderer::clipRect(float left, float top, float right, float bottom,
        SkRegion::Op op) {
    addStateOp(new (alloc()) ClipRectOp(left, top, right, bottom, op));
    return mState.clipRect(left, top, right, bottom, op);
}

bool DisplayListRenderer::clipPath(const SkPath* path, SkRegion::Op op) {
    path = refPath(path);
    addStateOp(new (alloc()) ClipPathOp(path, op));
    return mState.clipPath(path, op);
}

bool DisplayListRenderer::clipRegion(const SkRegion* region, SkRegion::Op op) {
    region = refRegion(region);
    addStateOp(new (alloc()) ClipRegionOp(region, op));
    return mState.clipRegion(region, op);
}

void DisplayListRenderer::drawRenderNode(RenderNode* renderNode, Rect& dirty, int32_t flags) {
    LOG_ALWAYS_FATAL_IF(!renderNode, "missing rendernode");

    // dirty is an out parameter and should not be recorded,
    // it matters only when replaying the display list
    DrawRenderNodeOp* op = new (alloc()) DrawRenderNodeOp(renderNode, flags, *mState.currentTransform());
    addRenderNodeOp(op);
}

void DisplayListRenderer::drawLayer(DeferredLayerUpdater* layerHandle, float x, float y) {
    // We ref the DeferredLayerUpdater due to its thread-safe ref-counting
    // semantics.
    mDisplayListData->ref(layerHandle);
    addDrawOp(new (alloc()) DrawLayerOp(layerHandle->backingLayer(), x, y));
}

void DisplayListRenderer::drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapOp(bitmap, paint));
}

void DisplayListRenderer::drawBitmap(const SkBitmap& bitmap, float left, float top,
        const SkPaint* paint) {
    save(SkCanvas::kMatrix_SaveFlag);
    translate(left, top);
    drawBitmap(&bitmap, paint);
    restore();
}

void DisplayListRenderer::drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix,
        const SkPaint* paint) {
    if (matrix.isIdentity()) {
        drawBitmap(&bitmap, paint);
    } else if (!(matrix.getType() & ~(SkMatrix::kScale_Mask | SkMatrix::kTranslate_Mask))) {
        // SkMatrix::isScaleTranslate() not available in L
        SkRect src;
        SkRect dst;
        bitmap.getBounds(&src);
        matrix.mapRect(&dst, src);
        drawBitmap(bitmap, src.fLeft, src.fTop, src.fRight, src.fBottom,
                   dst.fLeft, dst.fTop, dst.fRight, dst.fBottom, paint);
    } else {
        save(SkCanvas::kMatrix_SaveFlag);
        concat(matrix);
        drawBitmap(&bitmap, paint);
        restore();
    }
}

void DisplayListRenderer::drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, const SkPaint* paint) {
    if (srcLeft == 0 && srcTop == 0
            && srcRight == bitmap.width() && srcBottom == bitmap.height()
            && (srcBottom - srcTop == dstBottom - dstTop)
            && (srcRight - srcLeft == dstRight - dstLeft)) {
        // transform simple rect to rect drawing case into position bitmap ops, since they merge
        save(SkCanvas::kMatrix_SaveFlag);
        translate(dstLeft, dstTop);
        drawBitmap(&bitmap, paint);
        restore();
    } else {
        paint = refPaint(paint);

        addDrawOp(new (alloc()) DrawBitmapRectOp(refBitmap(&bitmap),
                srcLeft, srcTop, srcRight, srcBottom,
                dstLeft, dstTop, dstRight, dstBottom, paint));
    }
}

void DisplayListRenderer::drawBitmapData(const SkBitmap* bitmap, const SkPaint* paint) {
    bitmap = refBitmapData(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapDataOp(bitmap, paint));
}

void DisplayListRenderer::drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
        const float* vertices, const int* colors, const SkPaint* paint) {
    int vertexCount = (meshWidth + 1) * (meshHeight + 1);
    vertices = refBuffer<float>(vertices, vertexCount * 2); // 2 floats per vertex
    paint = refPaint(paint);
    colors = refBuffer<int>(colors, vertexCount); // 1 color per vertex

    addDrawOp(new (alloc()) DrawBitmapMeshOp(refBitmap(&bitmap), meshWidth, meshHeight,
           vertices, colors, paint));
}

void DisplayListRenderer::drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
        float left, float top, float right, float bottom, const SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    patch = refPatch(patch);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPatchOp(bitmap, patch, left, top, right, bottom, paint));
}

void DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addDrawOp(new (alloc()) DrawColorOp(color, mode));
}

void DisplayListRenderer::drawPaint(const SkPaint& paint) {
    SkRect bounds;
    if (getClipBounds(&bounds)) {
        drawRect(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, paint);
    }
}


void DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawRectOp(left, top, right, bottom, refPaint(&paint)));
}

void DisplayListRenderer::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawRoundRectOp(left, top, right, bottom, rx, ry, refPaint(&paint)));
}

void DisplayListRenderer::drawRoundRect(
        CanvasPropertyPrimitive* left, CanvasPropertyPrimitive* top,
        CanvasPropertyPrimitive* right, CanvasPropertyPrimitive* bottom,
        CanvasPropertyPrimitive* rx, CanvasPropertyPrimitive* ry,
        CanvasPropertyPaint* paint) {
    mDisplayListData->ref(left);
    mDisplayListData->ref(top);
    mDisplayListData->ref(right);
    mDisplayListData->ref(bottom);
    mDisplayListData->ref(rx);
    mDisplayListData->ref(ry);
    mDisplayListData->ref(paint);
    addDrawOp(new (alloc()) DrawRoundRectPropsOp(&left->value, &top->value,
            &right->value, &bottom->value, &rx->value, &ry->value, &paint->value));
}

void DisplayListRenderer::drawCircle(float x, float y, float radius, const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawCircleOp(x, y, radius, refPaint(&paint)));
}

void DisplayListRenderer::drawCircle(CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
        CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint) {
    mDisplayListData->ref(x);
    mDisplayListData->ref(y);
    mDisplayListData->ref(radius);
    mDisplayListData->ref(paint);
    addDrawOp(new (alloc()) DrawCirclePropsOp(&x->value, &y->value,
            &radius->value, &paint->value));
}

void DisplayListRenderer::drawOval(float left, float top, float right, float bottom,
        const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawOvalOp(left, top, right, bottom, refPaint(&paint)));
}

void DisplayListRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint) {
    if (fabs(sweepAngle) >= 360.0f) {
        drawOval(left, top, right, bottom, paint);
    } else {
        addDrawOp(new (alloc()) DrawArcOp(left, top, right, bottom,
                        startAngle, sweepAngle, useCenter, refPaint(&paint)));
    }
}

void DisplayListRenderer::drawPath(const SkPath& path, const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawPathOp(refPath(&path), refPaint(&paint)));
}

void DisplayListRenderer::drawLines(const float* points, int count, const SkPaint& paint) {
    points = refBuffer<float>(points, count);

    addDrawOp(new (alloc()) DrawLinesOp(points, count, refPaint(&paint)));
}

void DisplayListRenderer::drawPoints(const float* points, int count, const SkPaint& paint) {
    points = refBuffer<float>(points, count);

    addDrawOp(new (alloc()) DrawPointsOp(points, count, refPaint(&paint)));
}

void DisplayListRenderer::drawTextOnPath(const uint16_t* glyphs, int count,
        const SkPath& path, float hOffset, float vOffset, const SkPaint& paint) {
    if (!glyphs || count <= 0) return;

    int bytesCount = 2 * count;
    DrawOp* op = new (alloc()) DrawTextOnPathOp(refText((const char*) glyphs, bytesCount),
            bytesCount, count, refPath(&path),
            hOffset, vOffset, refPaint(&paint));
    addDrawOp(op);
}

void DisplayListRenderer::drawPosText(const uint16_t* text, const float* positions,
        int count, int posCount, const SkPaint& paint) {
    if (!text || count <= 0) return;

    int bytesCount = 2 * count;
    positions = refBuffer<float>(positions, count * 2);

    DrawOp* op = new (alloc()) DrawPosTextOp(refText((const char*) text, bytesCount),
                                             bytesCount, count, positions, refPaint(&paint));
    addDrawOp(op);
}

static void simplifyPaint(int color, SkPaint* paint) {
    paint->setColor(color);
    paint->setShader(NULL);
    paint->setColorFilter(NULL);
    paint->setLooper(NULL);
    paint->setStrokeWidth(4 + 0.04 * paint->getTextSize());
    paint->setStrokeJoin(SkPaint::kRound_Join);
    paint->setLooper(NULL);
}

void DisplayListRenderer::drawText(const uint16_t* glyphs, const float* positions,
        int count, const SkPaint& paint, float x, float y,
        float boundsLeft, float boundsTop, float boundsRight, float boundsBottom,
        float totalAdvance) {

    if (!glyphs || count <= 0 || PaintUtils::paintWillNotDrawText(paint)) return;

    int bytesCount = count * 2;
    const char* text = refText((const char*) glyphs, bytesCount);
    positions = refBuffer<float>(positions, count * 2);
    Rect bounds(boundsLeft, boundsTop, boundsRight, boundsBottom);

    if (CC_UNLIKELY(mHighContrastText)) {
        // high contrast draw path
        int color = paint.getColor();
        int channelSum = SkColorGetR(color) + SkColorGetG(color) + SkColorGetB(color);
        bool darken = channelSum < (128 * 3);

        // outline
        SkPaint* outlinePaint = copyPaint(&paint);
        simplifyPaint(darken ? SK_ColorWHITE : SK_ColorBLACK, outlinePaint);
        outlinePaint->setStyle(SkPaint::kStrokeAndFill_Style);
        addDrawOp(new (alloc()) DrawTextOp(text, bytesCount, count,
                x, y, positions, outlinePaint, totalAdvance, bounds)); // bounds?

        // inner
        SkPaint* innerPaint = copyPaint(&paint);
        simplifyPaint(darken ? SK_ColorBLACK : SK_ColorWHITE, innerPaint);
        innerPaint->setStyle(SkPaint::kFill_Style);
        addDrawOp(new (alloc()) DrawTextOp(text, bytesCount, count,
                x, y, positions, innerPaint, totalAdvance, bounds));
    } else {
        // standard draw path
        DrawOp* op = new (alloc()) DrawTextOp(text, bytesCount, count,
                x, y, positions, refPaint(&paint), totalAdvance, bounds);
        addDrawOp(op);
    }
}

void DisplayListRenderer::drawRects(const float* rects, int count, const SkPaint* paint) {
    if (count <= 0) return;

    rects = refBuffer<float>(rects, count);
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRectsOp(rects, count, paint));
}

void DisplayListRenderer::setDrawFilter(SkDrawFilter* filter) {
    mDrawFilter.reset(filter);
}

void DisplayListRenderer::insertReorderBarrier(bool enableReorder) {
    flushRestoreToCount();
    flushTranslate();
    mDeferredBarrierType = enableReorder ? kBarrier_OutOfOrder : kBarrier_InOrder;
}

void DisplayListRenderer::flushRestoreToCount() {
    if (mRestoreSaveCount >= 0) {
        addOpAndUpdateChunk(new (alloc()) RestoreToCountOp(mRestoreSaveCount));
        mRestoreSaveCount = -1;
    }
}

void DisplayListRenderer::flushTranslate() {
    if (mHasDeferredTranslate) {
        if (mTranslateX != 0.0f || mTranslateY != 0.0f) {
            addOpAndUpdateChunk(new (alloc()) TranslateOp(mTranslateX, mTranslateY));
            mTranslateX = mTranslateY = 0.0f;
        }
        mHasDeferredTranslate = false;
    }
}

size_t DisplayListRenderer::addOpAndUpdateChunk(DisplayListOp* op) {
    int insertIndex = mDisplayListData->displayListOps.add(op);
    if (mDeferredBarrierType != kBarrier_None) {
        // op is first in new chunk
        mDisplayListData->chunks.push();
        DisplayListData::Chunk& newChunk = mDisplayListData->chunks.editTop();
        newChunk.beginOpIndex = insertIndex;
        newChunk.endOpIndex = insertIndex + 1;
        newChunk.reorderChildren = (mDeferredBarrierType == kBarrier_OutOfOrder);

        int nextChildIndex = mDisplayListData->children().size();
        newChunk.beginChildIndex = newChunk.endChildIndex = nextChildIndex;
        mDeferredBarrierType = kBarrier_None;
    } else {
        // standard case - append to existing chunk
        mDisplayListData->chunks.editTop().endOpIndex = insertIndex + 1;
    }
    return insertIndex;
}

size_t DisplayListRenderer::flushAndAddOp(DisplayListOp* op) {
    flushRestoreToCount();
    flushTranslate();
    return addOpAndUpdateChunk(op);
}

size_t DisplayListRenderer::addStateOp(StateOp* op) {
    return flushAndAddOp(op);
}

size_t DisplayListRenderer::addDrawOp(DrawOp* op) {
    Rect localBounds;
    if (op->getLocalBounds(localBounds)) {
        bool rejected = quickRejectRect(localBounds.left, localBounds.top,
                localBounds.right, localBounds.bottom);
        op->setQuickRejected(rejected);
    }

    mDisplayListData->hasDrawOps = true;
    return flushAndAddOp(op);
}

size_t DisplayListRenderer::addRenderNodeOp(DrawRenderNodeOp* op) {
    int opIndex = addDrawOp(op);
    int childIndex = mDisplayListData->addChild(op);

    // update the chunk's child indices
    DisplayListData::Chunk& chunk = mDisplayListData->chunks.editTop();
    chunk.endChildIndex = childIndex + 1;

    if (op->renderNode()->stagingProperties().isProjectionReceiver()) {
        // use staging property, since recording on UI thread
        mDisplayListData->projectionReceiveIndex = opIndex;
    }
    return opIndex;
}

}; // namespace uirenderer
}; // namespace android
