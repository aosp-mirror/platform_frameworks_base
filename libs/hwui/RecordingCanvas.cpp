/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "RecordingCanvas.h"

#include "RecordedOp.h"
#include "RenderNode.h"

namespace android {
namespace uirenderer {

RecordingCanvas::RecordingCanvas(size_t width, size_t height)
        : mState(*this)
        , mResourceCache(ResourceCache::getInstance()) {
    reset(width, height);
}

RecordingCanvas::~RecordingCanvas() {
    LOG_ALWAYS_FATAL_IF(mDisplayList,
            "Destroyed a RecordingCanvas during a record!");
}

void RecordingCanvas::reset(int width, int height) {
    LOG_ALWAYS_FATAL_IF(mDisplayList,
            "prepareDirty called a second time during a recording!");
    mDisplayList = new DisplayList();

    mState.initializeSaveStack(width, height, 0, 0, width, height, Vector3());

    mDeferredBarrierType = kBarrier_InOrder;
    mState.setDirtyClip(false);
    mRestoreSaveCount = -1;
}

DisplayList* RecordingCanvas::finishRecording() {
    mPaintMap.clear();
    mRegionMap.clear();
    mPathMap.clear();
    DisplayList* displayList = mDisplayList;
    mDisplayList = nullptr;
    mSkiaCanvasProxy.reset(nullptr);
    return displayList;
}

SkCanvas* RecordingCanvas::asSkCanvas() {
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

// ----------------------------------------------------------------------------
// android/graphics/Canvas state operations
// ----------------------------------------------------------------------------
// Save (layer)
int RecordingCanvas::save(SkCanvas::SaveFlags flags) {
    return mState.save((int) flags);
}

void RecordingCanvas::RecordingCanvas::restore() {
    if (mRestoreSaveCount < 0) {
        restoreToCount(getSaveCount() - 1);
        return;
    }

    mRestoreSaveCount--;
    mState.restore();
}

void RecordingCanvas::restoreToCount(int saveCount) {
    mRestoreSaveCount = saveCount;
    mState.restoreToCount(saveCount);
}

int RecordingCanvas::saveLayer(float left, float top, float right, float bottom, const SkPaint* paint,
        SkCanvas::SaveFlags flags) {
    LOG_ALWAYS_FATAL("TODO");
    return 0;
}

// Matrix
void RecordingCanvas::rotate(float degrees) {
    if (degrees == 0) return;

    mState.rotate(degrees);
}

void RecordingCanvas::scale(float sx, float sy) {
    if (sx == 1 && sy == 1) return;

    mState.scale(sx, sy);
}

void RecordingCanvas::skew(float sx, float sy) {
    mState.skew(sx, sy);
}

void RecordingCanvas::translate(float dx, float dy) {
    if (dx == 0 && dy == 0) return;

    mState.translate(dx, dy, 0);
}

// Clip
bool RecordingCanvas::getClipBounds(SkRect* outRect) const {
    Rect bounds = mState.getLocalClipBounds();
    *outRect = SkRect::MakeLTRB(bounds.left, bounds.top, bounds.right, bounds.bottom);
    return !(outRect->isEmpty());
}
bool RecordingCanvas::quickRejectRect(float left, float top, float right, float bottom) const {
    return mState.quickRejectConservative(left, top, right, bottom);
}
bool RecordingCanvas::quickRejectPath(const SkPath& path) const {
    SkRect bounds = path.getBounds();
    return mState.quickRejectConservative(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom);
}
bool RecordingCanvas::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    return mState.clipRect(left, top, right, bottom, op);
}
bool RecordingCanvas::clipPath(const SkPath* path, SkRegion::Op op) {
    return mState.clipPath(path, op);
}
bool RecordingCanvas::clipRegion(const SkRegion* region, SkRegion::Op op) {
    return mState.clipRegion(region, op);
}

// ----------------------------------------------------------------------------
// android/graphics/Canvas draw operations
// ----------------------------------------------------------------------------
void RecordingCanvas::drawColor(int color, SkXfermode::Mode mode) {
    SkPaint paint;
    paint.setColor(color);
    paint.setXfermodeMode(mode);
    drawPaint(paint);
}

void RecordingCanvas::drawPaint(const SkPaint& paint) {
    // TODO: more efficient recording?
    Matrix4 identity;
    identity.loadIdentity();

    addOp(new (alloc()) RectOp(
            mState.getRenderTargetClipBounds(),
            identity,
            mState.getRenderTargetClipBounds(),
            refPaint(&paint)));
}

// Geometry
void RecordingCanvas::drawPoints(const float* points, int count, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawLines(const float* points, int count, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawRect(float left, float top, float right, float bottom, const SkPaint& paint) {
    addOp(new (alloc()) RectOp(
            Rect(left, top, right, bottom),
            *(mState.currentSnapshot()->transform),
            mState.getRenderTargetClipBounds(),
            refPaint(&paint)));
}

void RecordingCanvas::drawSimpleRects(const float* rects, int vertexCount, const SkPaint* paint) {
    if (rects == nullptr) return;

    Vertex* rectData = (Vertex*) mDisplayList->allocator.alloc(vertexCount * sizeof(Vertex));
    Vertex* vertex = rectData;

    float left = FLT_MAX;
    float top = FLT_MAX;
    float right = FLT_MIN;
    float bottom = FLT_MIN;
    for (int index = 0; index < vertexCount; index += 4) {
        float l = rects[index + 0];
        float t = rects[index + 1];
        float r = rects[index + 2];
        float b = rects[index + 3];

        Vertex::set(vertex++, l, t);
        Vertex::set(vertex++, r, t);
        Vertex::set(vertex++, l, b);
        Vertex::set(vertex++, r, b);

        left = std::min(left, l);
        top = std::min(top, t);
        right = std::max(right, r);
        bottom = std::max(bottom, b);
    }
    addOp(new (alloc()) SimpleRectsOp(
            Rect(left, top, right, bottom),
            *(mState.currentSnapshot()->transform),
            mState.getRenderTargetClipBounds(),
            refPaint(paint), rectData, vertexCount));
}

void RecordingCanvas::drawRegion(const SkRegion& region, const SkPaint& paint) {
    if (paint.getStyle() == SkPaint::kFill_Style
            && (!paint.isAntiAlias() || mState.currentTransform()->isSimple())) {
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
        drawSimpleRects(rects.array(), count, &paint);
    } else {
        SkRegion::Iterator it(region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            drawRect(r.fLeft, r.fTop, r.fRight, r.fBottom, paint);
            it.next();
        }
    }
}
void RecordingCanvas::drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawCircle(float x, float y, float radius, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawOval(float left, float top, float right, float bottom, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawPath(const SkPath& path, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}

// Bitmap-based
void RecordingCanvas::drawBitmap(const SkBitmap& bitmap, float left, float top, const SkPaint* paint) {
    save(SkCanvas::kMatrix_SaveFlag);
    translate(left, top);
    drawBitmap(&bitmap, paint);
    restore();
}

void RecordingCanvas::drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix,
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
        save(SkCanvas::kMatrix_SaveFlag);
        concat(matrix);
        drawBitmap(&bitmap, paint);
        restore();
    }
}
void RecordingCanvas::drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint) {
    if (srcLeft == 0 && srcTop == 0
            && srcRight == bitmap.width()
            && srcBottom == bitmap.height()
            && (srcBottom - srcTop == dstBottom - dstTop)
            && (srcRight - srcLeft == dstRight - dstLeft)) {
        // transform simple rect to rect drawing case into position bitmap ops, since they merge
        save(SkCanvas::kMatrix_SaveFlag);
        translate(dstLeft, dstTop);
        drawBitmap(&bitmap, paint);
        restore();
    } else {
        LOG_ALWAYS_FATAL("TODO!");
    }
}
void RecordingCanvas::drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawNinePatch(const SkBitmap& bitmap, const android::Res_png_9patch& chunk,
            float dstLeft, float dstTop, float dstRight, float dstBottom,
            const SkPaint* paint) {
    LOG_ALWAYS_FATAL("TODO!");
}

// Text
void RecordingCanvas::drawText(const uint16_t* glyphs, const float* positions, int count,
            const SkPaint& paint, float x, float y, float boundsLeft, float boundsTop,
            float boundsRight, float boundsBottom, float totalAdvance) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawPosText(const uint16_t* text, const float* positions, int count,
            int posCount, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}
void RecordingCanvas::drawTextOnPath(const uint16_t* glyphs, int count, const SkPath& path,
            float hOffset, float vOffset, const SkPaint& paint) {
    LOG_ALWAYS_FATAL("TODO!");
}

void RecordingCanvas::drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) {
    addOp(new (alloc()) BitmapOp(
            Rect(0, 0, bitmap->width(), bitmap->height()),
            *(mState.currentSnapshot()->transform),
            mState.getRenderTargetClipBounds(),
            refPaint(paint), refBitmap(*bitmap)));
}
void RecordingCanvas::drawRenderNode(RenderNode* renderNode) {
    RenderNodeOp* op = new (alloc()) RenderNodeOp(
            Rect(0, 0, renderNode->getWidth(), renderNode->getHeight()), // are these safe? they're theoretically dynamic
            *(mState.currentSnapshot()->transform),
            mState.getRenderTargetClipBounds(),
            renderNode);
    int opIndex = addOp(op);
    int childIndex = mDisplayList->addChild(op);

    // update the chunk's child indices
    DisplayList::Chunk& chunk = mDisplayList->chunks.back();
    chunk.endChildIndex = childIndex + 1;

    if (renderNode->stagingProperties().isProjectionReceiver()) {
        // use staging property, since recording on UI thread
        mDisplayList->projectionReceiveIndex = opIndex;
    }
}

size_t RecordingCanvas::addOp(RecordedOp* op) {
    // TODO: validate if "addDrawOp" quickrejection logic is useful before adding
    int insertIndex = mDisplayList->ops.size();
    mDisplayList->ops.push_back(op);
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

void RecordingCanvas::refBitmapsInShader(const SkShader* shader) {
    if (!shader) return;

    // If this paint has an SkShader that has an SkBitmap add
    // it to the bitmap pile
    SkBitmap bitmap;
    SkShader::TileMode xy[2];
    if (shader->asABitmap(&bitmap, nullptr, xy) == SkShader::kDefault_BitmapType) {
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
