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

#include "DisplayList.h"
#include "DeferredDisplayList.h"
#include "DisplayListLogBuffer.h"
#include "DisplayListOp.h"
#include "DisplayListRenderer.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

DisplayListRenderer::DisplayListRenderer():
        mCaches(Caches::getInstance()), mDisplayListData(new DisplayListData),
        mTranslateX(0.0f), mTranslateY(0.0f), mHasTranslate(false),
        mHasDrawOps(false), mFunctorCount(0) {
}

DisplayListRenderer::~DisplayListRenderer() {
    reset();
}

void DisplayListRenderer::reset() {
    mDisplayListData = new DisplayListData();
    mCaches.resourceCache.lock();

    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mBitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < mOwnedBitmapResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mOwnedBitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < mFilterResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mFilterResources.itemAt(i));
    }

    for (size_t i = 0; i < mShaders.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mShaders.itemAt(i));
    }

    for (size_t i = 0; i < mSourcePaths.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mSourcePaths.itemAt(i));
    }

    for (size_t i = 0; i < mLayers.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mLayers.itemAt(i));
    }

    mCaches.resourceCache.unlock();

    mBitmapResources.clear();
    mOwnedBitmapResources.clear();
    mFilterResources.clear();
    mSourcePaths.clear();

    mShaders.clear();
    mShaderMap.clear();

    mPaints.clear();
    mPaintMap.clear();

    mRegions.clear();
    mRegionMap.clear();

    mPaths.clear();
    mPathMap.clear();

    mMatrices.clear();

    mLayers.clear();

    mHasDrawOps = false;
    mFunctorCount = 0;
}

///////////////////////////////////////////////////////////////////////////////
// Operations
///////////////////////////////////////////////////////////////////////////////

DisplayList* DisplayListRenderer::getDisplayList(DisplayList* displayList) {
    if (!displayList) {
        displayList = new DisplayList(*this);
    } else {
        displayList->initFromDisplayListRenderer(*this, true);
    }
    displayList->setRenderable(mHasDrawOps);
    return displayList;
}

bool DisplayListRenderer::isDeferred() {
    return true;
}

void DisplayListRenderer::setViewport(int width, int height) {
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;
}

status_t DisplayListRenderer::prepareDirty(float left, float top,
        float right, float bottom, bool opaque) {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSaveCount = 1;

    mSnapshot->setClip(0.0f, 0.0f, mWidth, mHeight);
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
    mFunctorCount++;
    return DrawGlInfo::kStatusDone; // No invalidate needed at record-time
}

int DisplayListRenderer::save(int flags) {
    addStateOp(new (alloc()) SaveOp(flags));
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::restore() {
    if (mRestoreSaveCount < 0) {
        restoreToCount(getSaveCount() - 1);
        return;
    }

    mRestoreSaveCount--;
    insertTranslate();
    OpenGLRenderer::restore();
}

void DisplayListRenderer::restoreToCount(int saveCount) {
    mRestoreSaveCount = saveCount;
    insertTranslate();
    OpenGLRenderer::restoreToCount(saveCount);
}

int DisplayListRenderer::saveLayer(float left, float top, float right, float bottom,
        int alpha, SkXfermode::Mode mode, int flags) {
    addStateOp(new (alloc()) SaveLayerOp(left, top, right, bottom, alpha, mode, flags));
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::translate(float dx, float dy) {
    mHasTranslate = true;
    mTranslateX += dx;
    mTranslateY += dy;
    insertRestoreToCount();
    OpenGLRenderer::translate(dx, dy);
}

void DisplayListRenderer::rotate(float degrees) {
    addStateOp(new (alloc()) RotateOp(degrees));
    OpenGLRenderer::rotate(degrees);
}

void DisplayListRenderer::scale(float sx, float sy) {
    addStateOp(new (alloc()) ScaleOp(sx, sy));
    OpenGLRenderer::scale(sx, sy);
}

void DisplayListRenderer::skew(float sx, float sy) {
    addStateOp(new (alloc()) SkewOp(sx, sy));
    OpenGLRenderer::skew(sx, sy);
}

void DisplayListRenderer::setMatrix(SkMatrix* matrix) {
    matrix = refMatrix(matrix);
    addStateOp(new (alloc()) SetMatrixOp(matrix));
    OpenGLRenderer::setMatrix(matrix);
}

void DisplayListRenderer::concatMatrix(SkMatrix* matrix) {
    matrix = refMatrix(matrix);
    addStateOp(new (alloc()) ConcatMatrixOp(matrix));
    OpenGLRenderer::concatMatrix(matrix);
}

bool DisplayListRenderer::clipRect(float left, float top, float right, float bottom,
        SkRegion::Op op) {
    addStateOp(new (alloc()) ClipRectOp(left, top, right, bottom, op));
    return OpenGLRenderer::clipRect(left, top, right, bottom, op);
}

bool DisplayListRenderer::clipPath(SkPath* path, SkRegion::Op op) {
    path = refPath(path);
    addStateOp(new (alloc()) ClipPathOp(path, op));
    return OpenGLRenderer::clipPath(path, op);
}

bool DisplayListRenderer::clipRegion(SkRegion* region, SkRegion::Op op) {
    region = refRegion(region);
    addStateOp(new (alloc()) ClipRegionOp(region, op));
    return OpenGLRenderer::clipRegion(region, op);
}

status_t DisplayListRenderer::drawDisplayList(DisplayList* displayList,
        Rect& dirty, int32_t flags) {
    // dirty is an out parameter and should not be recorded,
    // it matters only when replaying the display list

    // TODO: To be safe, the display list should be ref-counted in the
    //       resources cache, but we rely on the caller (UI toolkit) to
    //       do the right thing for now

    addDrawOp(new (alloc()) DrawDisplayListOp(displayList, flags));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawLayer(Layer* layer, float x, float y) {
    layer = refLayer(layer);
    addDrawOp(new (alloc()) DrawLayerOp(layer, x, y));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapOp(bitmap, left, top, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    matrix = refMatrix(matrix);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapMatrixOp(bitmap, matrix, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    paint = refPaint(paint);

    if (srcLeft == 0 && srcTop == 0 &&
            srcRight == bitmap->width() && srcBottom == bitmap->height() &&
            (srcBottom - srcTop == dstBottom - dstTop) &&
            (srcRight - srcLeft == dstRight - dstLeft)) {
        // transform simple rect to rect drawing case into position bitmap ops, since they merge
        addDrawOp(new (alloc()) DrawBitmapOp(bitmap, dstLeft, dstTop, paint));
        return DrawGlInfo::kStatusDone;
    }

    addDrawOp(new (alloc()) DrawBitmapRectOp(bitmap,
                    srcLeft, srcTop, srcRight, srcBottom,
                    dstLeft, dstTop, dstRight, dstBottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmapData(SkBitmap* bitmap, float left, float top,
        SkPaint* paint) {
    bitmap = refBitmapData(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapDataOp(bitmap, left, top, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
        float* vertices, int* colors, SkPaint* paint) {
    int count = (meshWidth + 1) * (meshHeight + 1) * 2;
    bitmap = refBitmap(bitmap);
    vertices = refBuffer<float>(vertices, count);
    paint = refPaint(paint);
    colors = refBuffer<int>(colors, count);

    addDrawOp(new (alloc()) DrawBitmapMeshOp(bitmap, meshWidth, meshHeight,
                    vertices, colors, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs,
        const int32_t* yDivs, const uint32_t* colors, uint32_t width, uint32_t height,
        int8_t numColors, float left, float top, float right, float bottom, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    OpenGLRenderer::getAlphaAndModeDirect(paint, &alpha, &mode);

    bitmap = refBitmap(bitmap);
    xDivs = refBuffer<int>(xDivs, width);
    yDivs = refBuffer<int>(yDivs, height);
    colors = refBuffer<uint32_t>(colors, numColors);

    addDrawOp(new (alloc()) DrawPatchOp(bitmap, xDivs, yDivs, colors, width, height, numColors,
                    left, top, right, bottom, alpha, mode));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addDrawOp(new (alloc()) DrawColorOp(color, mode));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRectOp(left, top, right, bottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRoundRectOp(left, top, right, bottom, rx, ry, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawCircle(float x, float y, float radius, SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawCircleOp(x, y, radius, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawOval(float left, float top, float right, float bottom,
        SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawOvalOp(left, top, right, bottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawArcOp(left, top, right, bottom,
                    startAngle, sweepAngle, useCenter, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPath(SkPath* path, SkPaint* paint) {
    path = refPath(path);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPathOp(path, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawLines(float* points, int count, SkPaint* paint) {
    points = refBuffer<float>(points, count);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawLinesOp(points, count, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPoints(float* points, int count, SkPaint* paint) {
    points = refBuffer<float>(points, count);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPointsOp(points, count, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawTextOnPath(const char* text, int bytesCount, int count,
        SkPath* path, float hOffset, float vOffset, SkPaint* paint) {
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
        const float* positions, SkPaint* paint) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;

    text = refText(text, bytesCount);
    positions = refBuffer<float>(positions, count * 2);
    paint = refPaint(paint);

    DrawOp* op = new (alloc()) DrawPosTextOp(text, bytesCount, count, positions, paint);
    addDrawOp(op);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, const float* positions, SkPaint* paint,
        float length, DrawOpMode drawOpMode) {

    if (!text || count <= 0) return DrawGlInfo::kStatusDone;

    if (length < 0.0f) length = paint->measureText(text, bytesCount);

    text = refText(text, bytesCount);
    positions = refBuffer<float>(positions, count * 2);
    paint = refPaint(paint);

    DrawOp* op = new (alloc()) DrawTextOp(text, bytesCount, count, x, y, positions, paint, length);
    addDrawOp(op);
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRects(const float* rects, int count, SkPaint* paint) {
    if (count <= 0) return DrawGlInfo::kStatusDone;

    rects = refBuffer<float>(rects, count);
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRectsOp(rects, count, paint));
    return DrawGlInfo::kStatusDone;
}

void DisplayListRenderer::resetShader() {
    addStateOp(new (alloc()) ResetShaderOp());
}

void DisplayListRenderer::setupShader(SkiaShader* shader) {
    shader = refShader(shader);
    addStateOp(new (alloc()) SetupShaderOp(shader));
}

void DisplayListRenderer::resetColorFilter() {
    addStateOp(new (alloc()) ResetColorFilterOp());
}

void DisplayListRenderer::setupColorFilter(SkiaColorFilter* filter) {
    filter = refColorFilter(filter);
    addStateOp(new (alloc()) SetupColorFilterOp(filter));
}

void DisplayListRenderer::resetShadow() {
    addStateOp(new (alloc()) ResetShadowOp());
}

void DisplayListRenderer::setupShadow(float radius, float dx, float dy, int color) {
    addStateOp(new (alloc()) SetupShadowOp(radius, dx, dy, color));
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

void DisplayListRenderer::addStateOp(StateOp* op) {
    addOpInternal(op);
}

void DisplayListRenderer::addDrawOp(DrawOp* op) {
    Rect localBounds;
    if (op->getLocalBounds(localBounds)) {
        bool rejected = quickRejectNoScissor(localBounds.left, localBounds.top,
                localBounds.right, localBounds.bottom);
        op->setQuickRejected(rejected);
    }
    mHasDrawOps = true;
    addOpInternal(op);
}

}; // namespace uirenderer
}; // namespace android
