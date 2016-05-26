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

#include "DisplayListCanvas.h"

#include "DeferredDisplayList.h"
#include "DeferredLayerUpdater.h"
#include "DisplayListOp.h"
#include "ResourceCache.h"
#include "RenderNode.h"
#include "VectorDrawable.h"
#include "utils/PaintUtils.h"

#include <SkCamera.h>
#include <SkCanvas.h>

#include <private/hwui/DrawGlInfo.h>

namespace android {
namespace uirenderer {

DisplayListCanvas::DisplayListCanvas(int width, int height)
    : mState(*this)
    , mResourceCache(ResourceCache::getInstance())
    , mDisplayList(nullptr)
    , mTranslateX(0.0f)
    , mTranslateY(0.0f)
    , mHasDeferredTranslate(false)
    , mDeferredBarrierType(kBarrier_None)
    , mHighContrastText(false)
    , mRestoreSaveCount(-1) {
    resetRecording(width, height);
}

DisplayListCanvas::~DisplayListCanvas() {
    LOG_ALWAYS_FATAL_IF(mDisplayList,
            "Destroyed a DisplayListCanvas during a record!");
}

void DisplayListCanvas::resetRecording(int width, int height) {
    LOG_ALWAYS_FATAL_IF(mDisplayList,
            "prepareDirty called a second time during a recording!");
    mDisplayList = new DisplayList();

    mState.initializeSaveStack(width, height,
            0, 0, width, height, Vector3());

    mDeferredBarrierType = kBarrier_InOrder;
    mState.setDirtyClip(false);
    mRestoreSaveCount = -1;
}


///////////////////////////////////////////////////////////////////////////////
// Operations
///////////////////////////////////////////////////////////////////////////////

DisplayList* DisplayListCanvas::finishRecording() {
    flushRestoreToCount();
    flushTranslate();

    mPaintMap.clear();
    mRegionMap.clear();
    mPathMap.clear();
    DisplayList* displayList = mDisplayList;
    mDisplayList = nullptr;
    mSkiaCanvasProxy.reset(nullptr);
    return displayList;
}

void DisplayListCanvas::callDrawGLFunction(Functor* functor,
        GlFunctorLifecycleListener* listener) {
    addDrawOp(new (alloc()) DrawFunctorOp(functor));
    mDisplayList->functors.push_back({functor, listener});
    mDisplayList->ref(listener);
}

SkCanvas* DisplayListCanvas::asSkCanvas() {
    LOG_ALWAYS_FATAL_IF(!mDisplayList,
            "attempting to get an SkCanvas when we are not recording!");
    if (!mSkiaCanvasProxy) {
        mSkiaCanvasProxy.reset(new SkiaCanvasProxy(this));
    }

    // SkCanvas instances default to identity transform, but should inherit
    // the state of this Canvas; if this code was in the SkiaCanvasProxy
    // constructor, we couldn't cache mSkiaCanvasProxy.
    SkMatrix parentTransform;
    getMatrix(&parentTransform);
    mSkiaCanvasProxy.get()->setMatrix(parentTransform);

    return mSkiaCanvasProxy.get();
}

int DisplayListCanvas::save(SaveFlags::Flags flags) {
    addStateOp(new (alloc()) SaveOp((int) flags));
    return mState.save((int) flags);
}

void DisplayListCanvas::restore() {
    if (mRestoreSaveCount < 0) {
        restoreToCount(getSaveCount() - 1);
        return;
    }

    mRestoreSaveCount--;
    flushTranslate();
    mState.restore();
}

void DisplayListCanvas::restoreToCount(int saveCount) {
    mRestoreSaveCount = saveCount;
    flushTranslate();
    mState.restoreToCount(saveCount);
}

int DisplayListCanvas::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* paint, SaveFlags::Flags flags) {
    // force matrix/clip isolation for layer
    flags |= SaveFlags::MatrixClip;

    paint = refPaint(paint);
    addStateOp(new (alloc()) SaveLayerOp(left, top, right, bottom, paint, (int) flags));
    return mState.save((int) flags);
}

void DisplayListCanvas::translate(float dx, float dy) {
    if (dx == 0.0f && dy == 0.0f) return;

    mHasDeferredTranslate = true;
    mTranslateX += dx;
    mTranslateY += dy;
    flushRestoreToCount();
    mState.translate(dx, dy, 0.0f);
}

void DisplayListCanvas::rotate(float degrees) {
    if (degrees == 0.0f) return;

    addStateOp(new (alloc()) RotateOp(degrees));
    mState.rotate(degrees);
}

void DisplayListCanvas::scale(float sx, float sy) {
    if (sx == 1.0f && sy == 1.0f) return;

    addStateOp(new (alloc()) ScaleOp(sx, sy));
    mState.scale(sx, sy);
}

void DisplayListCanvas::skew(float sx, float sy) {
    addStateOp(new (alloc()) SkewOp(sx, sy));
    mState.skew(sx, sy);
}

void DisplayListCanvas::setMatrix(const SkMatrix& matrix) {
    addStateOp(new (alloc()) SetMatrixOp(matrix));
    mState.setMatrix(matrix);
}

void DisplayListCanvas::concat(const SkMatrix& matrix) {
    addStateOp(new (alloc()) ConcatMatrixOp(matrix));
    mState.concatMatrix(matrix);
}

bool DisplayListCanvas::getClipBounds(SkRect* outRect) const {
    Rect bounds = mState.getLocalClipBounds();
    *outRect = SkRect::MakeLTRB(bounds.left, bounds.top, bounds.right, bounds.bottom);
    return !(outRect->isEmpty());
}

bool DisplayListCanvas::quickRejectRect(float left, float top, float right, float bottom) const {
    return mState.quickRejectConservative(left, top, right, bottom);
}

bool DisplayListCanvas::quickRejectPath(const SkPath& path) const {
    SkRect bounds = path.getBounds();
    return mState.quickRejectConservative(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom);
}


bool DisplayListCanvas::clipRect(float left, float top, float right, float bottom,
        SkRegion::Op op) {
    addStateOp(new (alloc()) ClipRectOp(left, top, right, bottom, op));
    return mState.clipRect(left, top, right, bottom, op);
}

bool DisplayListCanvas::clipPath(const SkPath* path, SkRegion::Op op) {
    path = refPath(path);
    addStateOp(new (alloc()) ClipPathOp(path, op));
    return mState.clipPath(path, op);
}

bool DisplayListCanvas::clipRegion(const SkRegion* region, SkRegion::Op op) {
    region = refRegion(region);
    addStateOp(new (alloc()) ClipRegionOp(region, op));
    return mState.clipRegion(region, op);
}

void DisplayListCanvas::drawRenderNode(RenderNode* renderNode) {
    LOG_ALWAYS_FATAL_IF(!renderNode, "missing rendernode");
    DrawRenderNodeOp* op = new (alloc()) DrawRenderNodeOp(
            renderNode,
            *mState.currentTransform(),
            mState.clipIsSimple());
    addRenderNodeOp(op);
}

void DisplayListCanvas::drawLayer(DeferredLayerUpdater* layerHandle) {
    // We ref the DeferredLayerUpdater due to its thread-safe ref-counting
    // semantics.
    mDisplayList->ref(layerHandle);
    addDrawOp(new (alloc()) DrawLayerOp(layerHandle->backingLayer()));
}

void DisplayListCanvas::drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) {
    bitmap = refBitmap(*bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapOp(bitmap, paint));
}

void DisplayListCanvas::drawBitmap(const SkBitmap& bitmap, float left, float top,
        const SkPaint* paint) {
    save(SaveFlags::Matrix);
    translate(left, top);
    drawBitmap(&bitmap, paint);
    restore();
}

void DisplayListCanvas::drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix,
        const SkPaint* paint) {
    if (matrix.isIdentity()) {
        drawBitmap(&bitmap, paint);
    } else if (!(matrix.getType() & ~(SkMatrix::kScale_Mask | SkMatrix::kTranslate_Mask))
            && MathUtils::isPositive(matrix.getScaleX())
            && MathUtils::isPositive(matrix.getScaleY())) {
        // SkMatrix::isScaleTranslate() not available in L
        SkRect src;
        SkRect dst;
        bitmap.getBounds(&src);
        matrix.mapRect(&dst, src);
        drawBitmap(bitmap, src.fLeft, src.fTop, src.fRight, src.fBottom,
                   dst.fLeft, dst.fTop, dst.fRight, dst.fBottom, paint);
    } else {
        save(SaveFlags::Matrix);
        concat(matrix);
        drawBitmap(&bitmap, paint);
        restore();
    }
}

void DisplayListCanvas::drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, const SkPaint* paint) {
    if (srcLeft == 0 && srcTop == 0
            && srcRight == bitmap.width()
            && srcBottom == bitmap.height()
            && (srcBottom - srcTop == dstBottom - dstTop)
            && (srcRight - srcLeft == dstRight - dstLeft)) {
        // transform simple rect to rect drawing case into position bitmap ops, since they merge
        save(SaveFlags::Matrix);
        translate(dstLeft, dstTop);
        drawBitmap(&bitmap, paint);
        restore();
    } else {
        paint = refPaint(paint);

        if (paint && paint->getShader()) {
            float scaleX = (dstRight - dstLeft) / (srcRight - srcLeft);
            float scaleY = (dstBottom - dstTop) / (srcBottom - srcTop);
            if (!MathUtils::areEqual(scaleX, 1.0f) || !MathUtils::areEqual(scaleY, 1.0f)) {
                // Apply the scale transform on the canvas, so that the shader
                // effectively calculates positions relative to src rect space

                save(SaveFlags::Matrix);
                translate(dstLeft, dstTop);
                scale(scaleX, scaleY);

                dstLeft = 0.0f;
                dstTop = 0.0f;
                dstRight = srcRight - srcLeft;
                dstBottom = srcBottom - srcTop;

                addDrawOp(new (alloc()) DrawBitmapRectOp(refBitmap(bitmap),
                        srcLeft, srcTop, srcRight, srcBottom,
                        dstLeft, dstTop, dstRight, dstBottom, paint));
                restore();
                return;
            }
        }

        addDrawOp(new (alloc()) DrawBitmapRectOp(refBitmap(bitmap),
                srcLeft, srcTop, srcRight, srcBottom,
                dstLeft, dstTop, dstRight, dstBottom, paint));
    }
}

void DisplayListCanvas::drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
        const float* vertices, const int* colors, const SkPaint* paint) {
    int vertexCount = (meshWidth + 1) * (meshHeight + 1);
    vertices = refBuffer<float>(vertices, vertexCount * 2); // 2 floats per vertex
    paint = refPaint(paint);
    colors = refBuffer<int>(colors, vertexCount); // 1 color per vertex

    addDrawOp(new (alloc()) DrawBitmapMeshOp(refBitmap(bitmap), meshWidth, meshHeight,
           vertices, colors, paint));
}

void DisplayListCanvas::drawNinePatch(const SkBitmap& bitmap, const Res_png_9patch& patch,
        float dstLeft, float dstTop, float dstRight, float dstBottom, const SkPaint* paint) {
    const SkBitmap* bitmapPtr = refBitmap(bitmap);
    const Res_png_9patch* patchPtr = refPatch(&patch);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPatchOp(bitmapPtr, patchPtr,
            dstLeft, dstTop, dstRight, dstBottom, paint));
}

void DisplayListCanvas::drawColor(int color, SkXfermode::Mode mode) {
    addDrawOp(new (alloc()) DrawColorOp(color, mode));
}

void DisplayListCanvas::drawPaint(const SkPaint& paint) {
    SkRect bounds;
    if (getClipBounds(&bounds)) {
        drawRect(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom, paint);
    }
}


void DisplayListCanvas::drawRect(float left, float top, float right, float bottom,
        const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawRectOp(left, top, right, bottom, refPaint(&paint)));
}

void DisplayListCanvas::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawRoundRectOp(left, top, right, bottom, rx, ry, refPaint(&paint)));
}

void DisplayListCanvas::drawRoundRect(
        CanvasPropertyPrimitive* left, CanvasPropertyPrimitive* top,
        CanvasPropertyPrimitive* right, CanvasPropertyPrimitive* bottom,
        CanvasPropertyPrimitive* rx, CanvasPropertyPrimitive* ry,
        CanvasPropertyPaint* paint) {
    mDisplayList->ref(left);
    mDisplayList->ref(top);
    mDisplayList->ref(right);
    mDisplayList->ref(bottom);
    mDisplayList->ref(rx);
    mDisplayList->ref(ry);
    mDisplayList->ref(paint);
    refBitmapsInShader(paint->value.getShader());
    addDrawOp(new (alloc()) DrawRoundRectPropsOp(&left->value, &top->value,
            &right->value, &bottom->value, &rx->value, &ry->value, &paint->value));
}

void DisplayListCanvas::drawCircle(float x, float y, float radius, const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawCircleOp(x, y, radius, refPaint(&paint)));
}

void DisplayListCanvas::drawCircle(CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
        CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint) {
    mDisplayList->ref(x);
    mDisplayList->ref(y);
    mDisplayList->ref(radius);
    mDisplayList->ref(paint);
    refBitmapsInShader(paint->value.getShader());
    addDrawOp(new (alloc()) DrawCirclePropsOp(&x->value, &y->value,
            &radius->value, &paint->value));
}

void DisplayListCanvas::drawOval(float left, float top, float right, float bottom,
        const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawOvalOp(left, top, right, bottom, refPaint(&paint)));
}

void DisplayListCanvas::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint) {
    if (fabs(sweepAngle) >= 360.0f) {
        drawOval(left, top, right, bottom, paint);
    } else {
        addDrawOp(new (alloc()) DrawArcOp(left, top, right, bottom,
                        startAngle, sweepAngle, useCenter, refPaint(&paint)));
    }
}

void DisplayListCanvas::drawPath(const SkPath& path, const SkPaint& paint) {
    addDrawOp(new (alloc()) DrawPathOp(refPath(&path), refPaint(&paint)));
}

void DisplayListCanvas::drawLines(const float* points, int count, const SkPaint& paint) {
    points = refBuffer<float>(points, count);

    addDrawOp(new (alloc()) DrawLinesOp(points, count, refPaint(&paint)));
}

void DisplayListCanvas::drawPoints(const float* points, int count, const SkPaint& paint) {
    points = refBuffer<float>(points, count);

    addDrawOp(new (alloc()) DrawPointsOp(points, count, refPaint(&paint)));
}

void DisplayListCanvas::drawVectorDrawable(VectorDrawableRoot* tree) {
    mDisplayList->ref(tree);
    mDisplayList->vectorDrawables.push_back(tree);
    addDrawOp(new (alloc()) DrawVectorDrawableOp(tree, tree->stagingProperties()->getBounds()));
}

void DisplayListCanvas::drawGlyphsOnPath(const uint16_t* glyphs, int count,
        const SkPath& path, float hOffset, float vOffset, const SkPaint& paint) {
    if (!glyphs || count <= 0) return;

    int bytesCount = 2 * count;
    DrawOp* op = new (alloc()) DrawTextOnPathOp(refBuffer<glyph_t>(glyphs, count),
            bytesCount, count, refPath(&path),
            hOffset, vOffset, refPaint(&paint));
    addDrawOp(op);
}

void DisplayListCanvas::drawGlyphs(const uint16_t* glyphs, const float* positions,
        int count, const SkPaint& paint, float x, float y,
        float boundsLeft, float boundsTop, float boundsRight, float boundsBottom,
        float totalAdvance) {

    if (!glyphs || count <= 0 || PaintUtils::paintWillNotDrawText(paint)) return;

    int bytesCount = count * 2;
    positions = refBuffer<float>(positions, count * 2);
    Rect bounds(boundsLeft, boundsTop, boundsRight, boundsBottom);

    DrawOp* op = new (alloc()) DrawTextOp(refBuffer<glyph_t>(glyphs, count), bytesCount, count,
            x, y, positions, refPaint(&paint), totalAdvance, bounds);
    addDrawOp(op);
    drawTextDecorations(x, y, totalAdvance, paint);
}

void DisplayListCanvas::drawRegion(const SkRegion& region, const SkPaint& paint) {
    if (paint.getStyle() != SkPaint::kFill_Style ||
            (paint.isAntiAlias() && !mState.currentTransform()->isSimple())) {
        SkRegion::Iterator it(region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            drawRect(r.fLeft, r.fTop, r.fRight, r.fBottom, paint);
            it.next();
        }
    } else {
        int count = 0;
        Vector<float> rects;
        SkRegion::Iterator it(region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            rects.push(r.fLeft);
            rects.push(r.fTop);
            rects.push(r.fRight);
            rects.push(r.fBottom);
            count += 4;
            it.next();
        }
        drawRects(rects.array(), count, &paint);
    }
}

void DisplayListCanvas::drawRects(const float* rects, int count, const SkPaint* paint) {
    if (count <= 0) return;

    rects = refBuffer<float>(rects, count);
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRectsOp(rects, count, paint));
}

void DisplayListCanvas::setDrawFilter(SkDrawFilter* filter) {
    mDrawFilter.reset(SkSafeRef(filter));
}

void DisplayListCanvas::insertReorderBarrier(bool enableReorder) {
    flushRestoreToCount();
    flushTranslate();
    mDeferredBarrierType = enableReorder ? kBarrier_OutOfOrder : kBarrier_InOrder;
}

void DisplayListCanvas::flushRestoreToCount() {
    if (mRestoreSaveCount >= 0) {
        addOpAndUpdateChunk(new (alloc()) RestoreToCountOp(mRestoreSaveCount));
        mRestoreSaveCount = -1;
    }
}

void DisplayListCanvas::flushTranslate() {
    if (mHasDeferredTranslate) {
        if (mTranslateX != 0.0f || mTranslateY != 0.0f) {
            addOpAndUpdateChunk(new (alloc()) TranslateOp(mTranslateX, mTranslateY));
            mTranslateX = mTranslateY = 0.0f;
        }
        mHasDeferredTranslate = false;
    }
}

size_t DisplayListCanvas::addOpAndUpdateChunk(DisplayListOp* op) {
    int insertIndex = mDisplayList->ops.size();
#if HWUI_NEW_OPS
    LOG_ALWAYS_FATAL("unsupported");
#else
    mDisplayList->ops.push_back(op);
#endif
    if (mDeferredBarrierType != kBarrier_None) {
        // op is first in new chunk
        mDisplayList->chunks.emplace_back();
        DisplayList::Chunk& newChunk = mDisplayList->chunks.back();
        newChunk.beginOpIndex = insertIndex;
        newChunk.endOpIndex = insertIndex + 1;
        newChunk.reorderChildren = (mDeferredBarrierType == kBarrier_OutOfOrder);

        int nextChildIndex = mDisplayList->children.size();
        newChunk.beginChildIndex = newChunk.endChildIndex = nextChildIndex;
        mDeferredBarrierType = kBarrier_None;
    } else {
        // standard case - append to existing chunk
        mDisplayList->chunks.back().endOpIndex = insertIndex + 1;
    }
    return insertIndex;
}

size_t DisplayListCanvas::flushAndAddOp(DisplayListOp* op) {
    flushRestoreToCount();
    flushTranslate();
    return addOpAndUpdateChunk(op);
}

size_t DisplayListCanvas::addStateOp(StateOp* op) {
    return flushAndAddOp(op);
}

size_t DisplayListCanvas::addDrawOp(DrawOp* op) {
    Rect localBounds;
    if (op->getLocalBounds(localBounds)) {
        bool rejected = quickRejectRect(localBounds.left, localBounds.top,
                localBounds.right, localBounds.bottom);
        op->setQuickRejected(rejected);
    }

    mDisplayList->hasDrawOps = true;
    return flushAndAddOp(op);
}

size_t DisplayListCanvas::addRenderNodeOp(DrawRenderNodeOp* op) {
    int opIndex = addDrawOp(op);
#if !HWUI_NEW_OPS
    int childIndex = mDisplayList->addChild(op);

    // update the chunk's child indices
    DisplayList::Chunk& chunk = mDisplayList->chunks.back();
    chunk.endChildIndex = childIndex + 1;

    if (op->renderNode->stagingProperties().isProjectionReceiver()) {
        // use staging property, since recording on UI thread
        mDisplayList->projectionReceiveIndex = opIndex;
    }
#endif
    return opIndex;
}

void DisplayListCanvas::refBitmapsInShader(const SkShader* shader) {
    if (!shader) return;

    // If this paint has an SkShader that has an SkBitmap add
    // it to the bitmap pile
    SkBitmap bitmap;
    SkShader::TileMode xy[2];
    if (shader->isABitmap(&bitmap, nullptr, xy)) {
        refBitmap(bitmap);
        return;
    }
    SkShader::ComposeRec rec;
    if (shader->asACompose(&rec)) {
        refBitmapsInShader(rec.fShaderA);
        refBitmapsInShader(rec.fShaderB);
        return;
    }
}

}; // namespace uirenderer
}; // namespace android
