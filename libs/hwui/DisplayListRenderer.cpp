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

#include "Caches.h"
#include "DeferredDisplayList.h"
#include "DisplayListLogBuffer.h"
#include "DisplayListOp.h"
#include "DisplayListRenderer.h"
#include "RenderNode.h"

namespace android {
namespace uirenderer {

DisplayListRenderer::DisplayListRenderer()
    : mCaches(Caches::getInstance())
    , mDisplayListData(0)
    , mTranslateX(0.0f)
    , mTranslateY(0.0f)
    , mHasTranslate(false)
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

status_t DisplayListRenderer::prepareDirty(float left, float top,
        float right, float bottom, bool opaque) {

    LOG_ALWAYS_FATAL_IF(mDisplayListData,
            "prepareDirty called a second time during a recording!");
    mDisplayListData = new DisplayListData();

    initializeSaveStack(0, 0, getWidth(), getHeight(), Vector3());

    mDirtyClip = opaque;
    mRestoreSaveCount = -1;

    return DrawGlInfo::kStatusDone; // No invalidate needed at record-time
}

void DisplayListRenderer::finish() {
    insertRestoreToCount();
    insertTranslate();
}

void DisplayListRenderer::interrupt() {
}

void DisplayListRenderer::resume() {
}

status_t DisplayListRenderer::callDrawGLFunction(Functor *functor, Rect& dirty) {
    // Ignore dirty during recording, it matters only when we replay
    addDrawOp(new (alloc()) DrawFunctorOp(functor));
    mDisplayListData->functors.add(functor);
    return DrawGlInfo::kStatusDone; // No invalidate needed at record-time
}

int DisplayListRenderer::save(int flags) {
    addStateOp(new (alloc()) SaveOp(flags));
    return StatefulBaseRenderer::save(flags);
}

void DisplayListRenderer::restore() {
    if (mRestoreSaveCount < 0) {
        restoreToCount(getSaveCount() - 1);
        return;
    }

    mRestoreSaveCount--;
    insertTranslate();
    StatefulBaseRenderer::restore();
}

void DisplayListRenderer::restoreToCount(int saveCount) {
    mRestoreSaveCount = saveCount;
    insertTranslate();
    StatefulBaseRenderer::restoreToCount(saveCount);
}

int DisplayListRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* paint, int flags) {
    paint = refPaint(paint);
    addStateOp(new (alloc()) SaveLayerOp(left, top, right, bottom, paint, flags));
    return StatefulBaseRenderer::save(flags);
}

void DisplayListRenderer::translate(float dx, float dy, float dz) {
    // ignore dz, not used at defer time
    mHasTranslate = true;
    mTranslateX += dx;
    mTranslateY += dy;
    insertRestoreToCount();
    StatefulBaseRenderer::translate(dx, dy, dz);
}

void DisplayListRenderer::rotate(float degrees) {
    addStateOp(new (alloc()) RotateOp(degrees));
    StatefulBaseRenderer::rotate(degrees);
}

void DisplayListRenderer::scale(float sx, float sy) {
    addStateOp(new (alloc()) ScaleOp(sx, sy));
    StatefulBaseRenderer::scale(sx, sy);
}

void DisplayListRenderer::skew(float sx, float sy) {
    addStateOp(new (alloc()) SkewOp(sx, sy));
    StatefulBaseRenderer::skew(sx, sy);
}

void DisplayListRenderer::setMatrix(const SkMatrix& matrix) {
    addStateOp(new (alloc()) SetMatrixOp(matrix));
    StatefulBaseRenderer::setMatrix(matrix);
}

void DisplayListRenderer::concatMatrix(const SkMatrix& matrix) {
    addStateOp(new (alloc()) ConcatMatrixOp(matrix));
    StatefulBaseRenderer::concatMatrix(matrix);
}

bool DisplayListRenderer::clipRect(float left, float top, float right, float bottom,
        SkRegion::Op op) {
    addStateOp(new (alloc()) ClipRectOp(left, top, right, bottom, op));
    return StatefulBaseRenderer::clipRect(left, top, right, bottom, op);
}

bool DisplayListRenderer::clipPath(const SkPath* path, SkRegion::Op op) {
    path = refPath(path);
    addStateOp(new (alloc()) ClipPathOp(path, op));
    return StatefulBaseRenderer::clipPath(path, op);
}

bool DisplayListRenderer::clipRegion(const SkRegion* region, SkRegion::Op op) {
    region = refRegion(region);
    addStateOp(new (alloc()) ClipRegionOp(region, op));
    return StatefulBaseRenderer::clipRegion(region, op);
}

status_t DisplayListRenderer::drawRenderNode(RenderNode* renderNode, Rect& dirty, int32_t flags) {
    // dirty is an out parameter and should not be recorded,
    // it matters only when replaying the display list
    DrawRenderNodeOp* op = new (alloc()) DrawRenderNodeOp(renderNode, flags, *currentTransform());
    int opIndex = addDrawOp(op);
    mDisplayListData->addChild(op);

    if (renderNode->stagingProperties().isProjectionReceiver()) {
        // use staging property, since recording on UI thread
        mDisplayListData->projectionReceiveIndex = opIndex;
    }

    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawLayer(Layer* layer, float x, float y) {
    layer = refLayer(layer);
    addDrawOp(new (alloc()) DrawLayerOp(layer, x, y));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapOp(bitmap, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(const SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, const SkPaint* paint) {
    if (srcLeft == 0 && srcTop == 0
            && srcRight == bitmap->width() && srcBottom == bitmap->height()
            && (srcBottom - srcTop == dstBottom - dstTop)
            && (srcRight - srcLeft == dstRight - dstLeft)) {
        // transform simple rect to rect drawing case into position bitmap ops, since they merge
        save(SkCanvas::kMatrix_SaveFlag);
        translate(dstLeft, dstTop);
        drawBitmap(bitmap, paint);
        restore();
    } else {
        bitmap = refBitmap(bitmap);
        paint = refPaint(paint);

        addDrawOp(new (alloc()) DrawBitmapRectOp(bitmap,
                srcLeft, srcTop, srcRight, srcBottom,
                dstLeft, dstTop, dstRight, dstBottom, paint));
    }
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmapData(const SkBitmap* bitmap, const SkPaint* paint) {
    bitmap = refBitmapData(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapDataOp(bitmap, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
        const float* vertices, const int* colors, const SkPaint* paint) {
    int vertexCount = (meshWidth + 1) * (meshHeight + 1);
    bitmap = refBitmap(bitmap);
    vertices = refBuffer<float>(vertices, vertexCount * 2); // 2 floats per vertex
    paint = refPaint(paint);
    colors = refBuffer<int>(colors, vertexCount); // 1 color per vertex

    addDrawOp(new (alloc()) DrawBitmapMeshOp(bitmap, meshWidth, meshHeight,
                    vertices, colors, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
        float left, float top, float right, float bottom, const SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    patch = refPatch(patch);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPatchOp(bitmap, patch, left, top, right, bottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addDrawOp(new (alloc()) DrawColorOp(color, mode));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        const SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRectOp(left, top, right, bottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, const SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRoundRectOp(left, top, right, bottom, rx, ry, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawCircle(float x, float y, float radius, const SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawCircleOp(x, y, radius, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawCircle(CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
        CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint) {
    mDisplayListData->refProperty(x);
    mDisplayListData->refProperty(y);
    mDisplayListData->refProperty(radius);
    mDisplayListData->refProperty(paint);
    addDrawOp(new (alloc()) DrawCirclePropsOp(&x->value, &y->value,
            &radius->value, &paint->value));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawOval(float left, float top, float right, float bottom,
        const SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawOvalOp(left, top, right, bottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, const SkPaint* paint) {
    if (fabs(sweepAngle) >= 360.0f) {
        return drawOval(left, top, right, bottom, paint);
    }

    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawArcOp(left, top, right, bottom,
                    startAngle, sweepAngle, useCenter, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPath(const SkPath* path, const SkPaint* paint) {
    path = refPath(path);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPathOp(path, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawLines(const float* points, int count, const SkPaint* paint) {
    points = refBuffer<float>(points, count);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawLinesOp(points, count, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPoints(const float* points, int count, const SkPaint* paint) {
    points = refBuffer<float>(points, count);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPointsOp(points, count, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawTextOnPath(const char* text, int bytesCount, int count,
        const SkPath* path, float hOffset, float vOffset, const SkPaint* paint) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;

    text = refText(text, bytesCount);
    path = refPath(path);
    paint = refPaint(paint);

    DrawOp* op = new (alloc()) DrawTextOnPathOp(text, bytesCount, count, path,
            hOffset, vOffset, paint);
    addDrawOp(op);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPosText(const char* text, int bytesCount, int count,
        const float* positions, const SkPaint* paint) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;

    text = refText(text, bytesCount);
    positions = refBuffer<float>(positions, count * 2);
    paint = refPaint(paint);

    DrawOp* op = new (alloc()) DrawPosTextOp(text, bytesCount, count, positions, paint);
    addDrawOp(op);
    return DrawGlInfo::kStatusDone;
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

status_t DisplayListRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, const float* positions, const SkPaint* paint,
        float totalAdvance, const Rect& bounds, DrawOpMode drawOpMode) {

    if (!text || count <= 0 || paintWillNotDrawText(*paint)) return DrawGlInfo::kStatusDone;

    text = refText(text, bytesCount);
    positions = refBuffer<float>(positions, count * 2);

    if (CC_UNLIKELY(mHighContrastText)) {
        // high contrast draw path
        int color = paint->getColor();
        int channelSum = SkColorGetR(color) + SkColorGetG(color) + SkColorGetB(color);
        bool darken = channelSum < (128 * 3);

        // outline
        SkPaint* outlinePaint = copyPaint(paint);
        simplifyPaint(darken ? SK_ColorWHITE : SK_ColorBLACK, outlinePaint);
        outlinePaint->setStyle(SkPaint::kStrokeAndFill_Style);
        addDrawOp(new (alloc()) DrawTextOp(text, bytesCount, count,
                x, y, positions, outlinePaint, totalAdvance, bounds)); // bounds?

        // inner
        SkPaint* innerPaint = copyPaint(paint);
        simplifyPaint(darken ? SK_ColorBLACK : SK_ColorWHITE, innerPaint);
        innerPaint->setStyle(SkPaint::kFill_Style);
        addDrawOp(new (alloc()) DrawTextOp(text, bytesCount, count,
                x, y, positions, innerPaint, totalAdvance, bounds));
    } else {
        // standard draw path
        paint = refPaint(paint);

        DrawOp* op = new (alloc()) DrawTextOp(text, bytesCount, count,
                x, y, positions, paint, totalAdvance, bounds);
        addDrawOp(op);
    }
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRects(const float* rects, int count, const SkPaint* paint) {
    if (count <= 0) return DrawGlInfo::kStatusDone;

    rects = refBuffer<float>(rects, count);
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRectsOp(rects, count, paint));
    return DrawGlInfo::kStatusDone;
}

void DisplayListRenderer::resetPaintFilter() {
    addStateOp(new (alloc()) ResetPaintFilterOp());
}

void DisplayListRenderer::setupPaintFilter(int clearBits, int setBits) {
    addStateOp(new (alloc()) SetupPaintFilterOp(clearBits, setBits));
}

void DisplayListRenderer::insertRestoreToCount() {
    if (mRestoreSaveCount >= 0) {
        DisplayListOp* op = new (alloc()) RestoreToCountOp(mRestoreSaveCount);
        mDisplayListData->displayListOps.add(op);
        mRestoreSaveCount = -1;
    }
}

void DisplayListRenderer::insertTranslate() {
    if (mHasTranslate) {
        if (mTranslateX != 0.0f || mTranslateY != 0.0f) {
            DisplayListOp* op = new (alloc()) TranslateOp(mTranslateX, mTranslateY);
            mDisplayListData->displayListOps.add(op);
            mTranslateX = mTranslateY = 0.0f;
        }
        mHasTranslate = false;
    }
}

int DisplayListRenderer::addStateOp(StateOp* op) {
    return addOpInternal(op);
}

int DisplayListRenderer::addDrawOp(DrawOp* op) {
    Rect localBounds;
    if (op->getLocalBounds(localBounds)) {
        bool rejected = quickRejectConservative(localBounds.left, localBounds.top,
                localBounds.right, localBounds.bottom);
        op->setQuickRejected(rejected);
    }

    mDisplayListData->hasDrawOps = true;
    return addOpInternal(op);
}

}; // namespace uirenderer
}; // namespace android
