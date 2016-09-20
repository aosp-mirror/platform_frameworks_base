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

#include "SkiaCanvas.h"

#include "CanvasProperty.h"
#include "VectorDrawable.h"
#include "hwui/MinikinUtils.h"

#include <SkDrawable.h>
#include <SkDevice.h>
#include <SkDeque.h>
#include <SkDrawFilter.h>
#include <SkGraphics.h>
#include <SkImage.h>
#include <SkImagePriv.h>
#include <SkRSXform.h>
#include <SkShader.h>
#include <SkTemplates.h>

#include <memory>

namespace android {

Canvas* Canvas::create_canvas(const SkBitmap& bitmap) {
    return new SkiaCanvas(bitmap);
}

Canvas* Canvas::create_canvas(SkCanvas* skiaCanvas) {
    return new SkiaCanvas(skiaCanvas);
}

SkiaCanvas::SkiaCanvas(const SkBitmap& bitmap) {
    mCanvas.reset(new SkCanvas(bitmap));
}

void SkiaCanvas::reset(SkCanvas* skiaCanvas) {
    mCanvas.reset(SkRef(skiaCanvas));
    mSaveStack.reset(nullptr);
    mHighContrastText = false;
}

// ----------------------------------------------------------------------------
// Canvas state operations: Replace Bitmap
// ----------------------------------------------------------------------------

class ClipCopier : public SkCanvas::ClipVisitor {
public:
    explicit ClipCopier(SkCanvas* dstCanvas) : m_dstCanvas(dstCanvas) {}

    virtual void clipRect(const SkRect& rect, SkRegion::Op op, bool antialias) {
        m_dstCanvas->clipRect(rect, op, antialias);
    }
    virtual void clipRRect(const SkRRect& rrect, SkRegion::Op op, bool antialias) {
        m_dstCanvas->clipRRect(rrect, op, antialias);
    }
    virtual void clipPath(const SkPath& path, SkRegion::Op op, bool antialias) {
        m_dstCanvas->clipPath(path, op, antialias);
    }

private:
    SkCanvas* m_dstCanvas;
};

void SkiaCanvas::setBitmap(const SkBitmap& bitmap) {
    sk_sp<SkCanvas> newCanvas(new SkCanvas(bitmap));

    if (!bitmap.isNull()) {
        // Copy the canvas matrix & clip state.
        newCanvas->setMatrix(mCanvas->getTotalMatrix());

        ClipCopier copier(newCanvas.get());
        mCanvas->replayClips(&copier);
    }

    // unrefs the existing canvas
    mCanvas = std::move(newCanvas);

    // clean up the old save stack
    mSaveStack.reset(NULL);
}

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------

bool SkiaCanvas::isOpaque() {
    return mCanvas->imageInfo().isOpaque();
}

int SkiaCanvas::width() {
    return mCanvas->imageInfo().width();
}

int SkiaCanvas::height() {
    return mCanvas->imageInfo().height();
}

// ----------------------------------------------------------------------------
// Canvas state operations: Save (layer)
// ----------------------------------------------------------------------------

int SkiaCanvas::getSaveCount() const {
    return mCanvas->getSaveCount();
}

int SkiaCanvas::save(SaveFlags::Flags flags) {
    int count = mCanvas->save();
    recordPartialSave(flags);
    return count;
}

// The SkiaCanvas::restore operation layers on the capability to preserve
// either (or both) the matrix and/or clip state after a SkCanvas::restore
// operation. It does this by explicitly saving off the clip & matrix state
// when requested and playing it back after the SkCanvas::restore.
void SkiaCanvas::restore() {
    const SaveRec* rec = (NULL == mSaveStack.get())
            ? NULL
            : static_cast<SaveRec*>(mSaveStack->back());
    int currentSaveCount = mCanvas->getSaveCount();
    SkASSERT(NULL == rec || currentSaveCount >= rec->saveCount);

    if (NULL == rec || rec->saveCount != currentSaveCount) {
        // Fast path - no record for this frame.
        mCanvas->restore();
        return;
    }

    bool preserveMatrix = !(rec->saveFlags & SaveFlags::Matrix);
    bool preserveClip   = !(rec->saveFlags & SaveFlags::Clip);

    SkMatrix savedMatrix;
    if (preserveMatrix) {
        savedMatrix = mCanvas->getTotalMatrix();
    }

    SkTArray<SkClipStack::Element> savedClips;
    int topClipStackFrame = mCanvas->getClipStack()->getSaveCount();
    if (preserveClip) {
        saveClipsForFrame(savedClips, topClipStackFrame);
    }

    mCanvas->restore();

    if (preserveMatrix) {
        mCanvas->setMatrix(savedMatrix);
    }

    if (preserveClip && !savedClips.empty() &&
        topClipStackFrame != mCanvas->getClipStack()->getSaveCount()) {
        // Only reapply the saved clips if the top clip stack frame was actually
        // popped by restore().  If it wasn't, it means it doesn't belong to the
        // restored canvas frame (SkCanvas lazy save/restore kicked in).
        applyClips(savedClips);
    }

    mSaveStack->pop_back();
}

void SkiaCanvas::restoreToCount(int restoreCount) {
    while (mCanvas->getSaveCount() > restoreCount) {
        this->restore();
    }
}

static inline SkCanvas::SaveLayerFlags layerFlags(SaveFlags::Flags flags) {
    SkCanvas::SaveLayerFlags layerFlags = 0;

    // We intentionally ignore the SaveFlags::HasAlphaLayer and
    // SkCanvas::kIsOpaque_SaveLayerFlag flags because HWUI ignores it
    // and our Android client may use it incorrectly.
    // In Skia, this flag is purely for performance optimization.

    if (!(flags & SaveFlags::ClipToLayer)) {
        layerFlags |= SkCanvas::kDontClipToLayer_Legacy_SaveLayerFlag;
    }

    return layerFlags;
}

int SkiaCanvas::saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, SaveFlags::Flags flags) {
    const SkRect bounds = SkRect::MakeLTRB(left, top, right, bottom);
    const SkCanvas::SaveLayerRec rec(&bounds, paint, layerFlags(flags));

    int count = mCanvas->saveLayer(rec);
    recordPartialSave(flags);
    return count;
}

int SkiaCanvas::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, SaveFlags::Flags flags) {
    if (static_cast<unsigned>(alpha) < 0xFF) {
        SkPaint alphaPaint;
        alphaPaint.setAlpha(alpha);
        return this->saveLayer(left, top, right, bottom, &alphaPaint, flags);
    }
    return this->saveLayer(left, top, right, bottom, nullptr, flags);
}

// ----------------------------------------------------------------------------
// functions to emulate legacy SaveFlags (i.e. independent matrix/clip flags)
// ----------------------------------------------------------------------------

void SkiaCanvas::recordPartialSave(SaveFlags::Flags flags) {
    // A partial save is a save operation which doesn't capture the full canvas state.
    // (either SaveFlags::Matrix or SaveFlags::Clip is missing).

    // Mask-out non canvas state bits.
    flags &= SaveFlags::MatrixClip;

    if (flags == SaveFlags::MatrixClip) {
        // not a partial save.
        return;
    }

    if (NULL == mSaveStack.get()) {
        mSaveStack.reset(new SkDeque(sizeof(struct SaveRec), 8));
    }

    SaveRec* rec = static_cast<SaveRec*>(mSaveStack->push_back());
    rec->saveCount = mCanvas->getSaveCount();
    rec->saveFlags = flags;
}

void SkiaCanvas::saveClipsForFrame(SkTArray<SkClipStack::Element>& clips,
                                   int saveCountToBackup) {
    // Each SkClipStack::Element stores the index of the canvas save
    // with which it is associated. Backup only those Elements that
    // are associated with 'saveCountToBackup'
    SkClipStack::Iter clipIterator(*mCanvas->getClipStack(),
                                   SkClipStack::Iter::kTop_IterStart);
    while (const SkClipStack::Element* elem = clipIterator.prev()) {
        if (elem->getSaveCount() < saveCountToBackup) {
            // done with the target save count.
            break;
        }
        SkASSERT(elem->getSaveCount() == saveCountToBackup);
        clips.push_back(*elem);
    }
}

void SkiaCanvas::applyClips(const SkTArray<SkClipStack::Element>& clips) {
    ClipCopier clipCopier(mCanvas.get());

    // The clip stack stores clips in device space.
    SkMatrix origMatrix = mCanvas->getTotalMatrix();
    mCanvas->resetMatrix();

    // We pushed the clips in reverse order.
    for (int i = clips.count() - 1; i >= 0; --i) {
        clips[i].replay(&clipCopier);
    }

    mCanvas->setMatrix(origMatrix);
}

// ----------------------------------------------------------------------------
// Canvas state operations: Matrix
// ----------------------------------------------------------------------------

void SkiaCanvas::getMatrix(SkMatrix* outMatrix) const {
    *outMatrix = mCanvas->getTotalMatrix();
}

void SkiaCanvas::setMatrix(const SkMatrix& matrix) {
    mCanvas->setMatrix(matrix);
}

void SkiaCanvas::concat(const SkMatrix& matrix) {
    mCanvas->concat(matrix);
}

void SkiaCanvas::rotate(float degrees) {
    mCanvas->rotate(degrees);
}

void SkiaCanvas::scale(float sx, float sy) {
    mCanvas->scale(sx, sy);
}

void SkiaCanvas::skew(float sx, float sy) {
    mCanvas->skew(sx, sy);
}

void SkiaCanvas::translate(float dx, float dy) {
    mCanvas->translate(dx, dy);
}

// ----------------------------------------------------------------------------
// Canvas state operations: Clips
// ----------------------------------------------------------------------------

// This function is a mirror of SkCanvas::getClipBounds except that it does
// not outset the edge of the clip to account for anti-aliasing. There is
// a skia bug to investigate pushing this logic into back into skia.
// (see https://code.google.com/p/skia/issues/detail?id=1303)
bool SkiaCanvas::getClipBounds(SkRect* outRect) const {
    SkIRect ibounds;
    if (!mCanvas->getClipDeviceBounds(&ibounds)) {
        return false;
    }

    SkMatrix inverse;
    // if we can't invert the CTM, we can't return local clip bounds
    if (!mCanvas->getTotalMatrix().invert(&inverse)) {
        if (outRect) {
            outRect->setEmpty();
        }
        return false;
    }

    if (NULL != outRect) {
        SkRect r = SkRect::Make(ibounds);
        inverse.mapRect(outRect, r);
    }
    return true;
}

bool SkiaCanvas::quickRejectRect(float left, float top, float right, float bottom) const {
    SkRect bounds = SkRect::MakeLTRB(left, top, right, bottom);
    return mCanvas->quickReject(bounds);
}

bool SkiaCanvas::quickRejectPath(const SkPath& path) const {
    return mCanvas->quickReject(path);
}

bool SkiaCanvas::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    mCanvas->clipRect(rect, op);
    return !mCanvas->isClipEmpty();
}

bool SkiaCanvas::clipPath(const SkPath* path, SkRegion::Op op) {
    SkRRect roundRect;
    if (path->isRRect(&roundRect)) {
        mCanvas->clipRRect(roundRect, op);
    } else {
        mCanvas->clipPath(*path, op);
    }
    return !mCanvas->isClipEmpty();
}

bool SkiaCanvas::clipRegion(const SkRegion* region, SkRegion::Op op) {
    SkPath rgnPath;
    if (region->getBoundaryPath(&rgnPath)) {
        // The region is specified in device space.
        SkMatrix savedMatrix = mCanvas->getTotalMatrix();
        mCanvas->resetMatrix();
        mCanvas->clipPath(rgnPath, op);
        mCanvas->setMatrix(savedMatrix);
    } else {
        mCanvas->clipRect(SkRect::MakeEmpty(), op);
    }
    return !mCanvas->isClipEmpty();
}

// ----------------------------------------------------------------------------
// Canvas state operations: Filters
// ----------------------------------------------------------------------------

SkDrawFilter* SkiaCanvas::getDrawFilter() {
    return mCanvas->getDrawFilter();
}

void SkiaCanvas::setDrawFilter(SkDrawFilter* drawFilter) {
    mCanvas->setDrawFilter(drawFilter);
}

// ----------------------------------------------------------------------------
// Canvas draw operations
// ----------------------------------------------------------------------------

void SkiaCanvas::drawColor(int color, SkXfermode::Mode mode) {
    mCanvas->drawColor(color, mode);
}

void SkiaCanvas::drawPaint(const SkPaint& paint) {
    mCanvas->drawPaint(paint);
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Geometry
// ----------------------------------------------------------------------------

void SkiaCanvas::drawPoints(const float* points, int count, const SkPaint& paint,
                            SkCanvas::PointMode mode) {
    // convert the floats into SkPoints
    count >>= 1;    // now it is the number of points
    std::unique_ptr<SkPoint[]> pts(new SkPoint[count]);
    for (int i = 0; i < count; i++) {
        pts[i].set(points[0], points[1]);
        points += 2;
    }
    mCanvas->drawPoints(mode, count, pts.get(), paint);
}


void SkiaCanvas::drawPoint(float x, float y, const SkPaint& paint) {
    mCanvas->drawPoint(x, y, paint);
}

void SkiaCanvas::drawPoints(const float* points, int count, const SkPaint& paint) {
    this->drawPoints(points, count, paint, SkCanvas::kPoints_PointMode);
}

void SkiaCanvas::drawLine(float startX, float startY, float stopX, float stopY,
                          const SkPaint& paint) {
    mCanvas->drawLine(startX, startY, stopX, stopY, paint);
}

void SkiaCanvas::drawLines(const float* points, int count, const SkPaint& paint) {
    this->drawPoints(points, count, paint, SkCanvas::kLines_PointMode);
}

void SkiaCanvas::drawRect(float left, float top, float right, float bottom,
        const SkPaint& paint) {
    mCanvas->drawRectCoords(left, top, right, bottom, paint);

}

void SkiaCanvas::drawRegion(const SkRegion& region, const SkPaint& paint) {
    SkRegion::Iterator it(region);
    while (!it.done()) {
        mCanvas->drawRect(SkRect::Make(it.rect()), paint);
        it.next();
    }
}

void SkiaCanvas::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, const SkPaint& paint) {
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    mCanvas->drawRoundRect(rect, rx, ry, paint);
}

void SkiaCanvas::drawCircle(float x, float y, float radius, const SkPaint& paint) {
    mCanvas->drawCircle(x, y, radius, paint);
}

void SkiaCanvas::drawOval(float left, float top, float right, float bottom, const SkPaint& paint) {
    SkRect oval = SkRect::MakeLTRB(left, top, right, bottom);
    mCanvas->drawOval(oval, paint);
}

void SkiaCanvas::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint) {
    SkRect arc = SkRect::MakeLTRB(left, top, right, bottom);
    mCanvas->drawArc(arc, startAngle, sweepAngle, useCenter, paint);
}

void SkiaCanvas::drawPath(const SkPath& path, const SkPaint& paint) {
    SkRect rect;
    SkRRect roundRect;
    if (path.isOval(&rect)) {
        mCanvas->drawOval(rect, paint);
    } else if (path.isRRect(&roundRect)) {
        mCanvas->drawRRect(roundRect, paint);
    } else {
        mCanvas->drawPath(path, paint);
    }
}

void SkiaCanvas::drawVertices(SkCanvas::VertexMode vertexMode, int vertexCount,
                              const float* verts, const float* texs, const int* colors,
                              const uint16_t* indices, int indexCount, const SkPaint& paint) {
#ifndef SK_SCALAR_IS_FLOAT
    SkDEBUGFAIL("SkScalar must be a float for these conversions to be valid");
#endif
    const int ptCount = vertexCount >> 1;
    mCanvas->drawVertices(vertexMode, ptCount, (SkPoint*)verts, (SkPoint*)texs,
                          (SkColor*)colors, NULL, indices, indexCount, paint);
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Bitmaps
// ----------------------------------------------------------------------------

void SkiaCanvas::drawBitmap(const SkBitmap& bitmap, float left, float top, const SkPaint* paint) {
    mCanvas->drawBitmap(bitmap, left, top, paint);
}

void SkiaCanvas::drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix, const SkPaint* paint) {
    SkAutoCanvasRestore acr(mCanvas.get(), true);
    mCanvas->concat(matrix);
    mCanvas->drawBitmap(bitmap, 0, 0, paint);
}

void SkiaCanvas::drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
                            float srcRight, float srcBottom, float dstLeft, float dstTop,
                            float dstRight, float dstBottom, const SkPaint* paint) {
    SkRect srcRect = SkRect::MakeLTRB(srcLeft, srcTop, srcRight, srcBottom);
    SkRect dstRect = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    mCanvas->drawBitmapRect(bitmap, srcRect, dstRect, paint);
}

void SkiaCanvas::drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
        const float* vertices, const int* colors, const SkPaint* paint) {

    const int ptCount = (meshWidth + 1) * (meshHeight + 1);
    const int indexCount = meshWidth * meshHeight * 6;

    /*  Our temp storage holds 2 or 3 arrays.
        texture points [ptCount * sizeof(SkPoint)]
        optionally vertex points [ptCount * sizeof(SkPoint)] if we need a
            copy to convert from float to fixed
        indices [ptCount * sizeof(uint16_t)]
    */
    ssize_t storageSize = ptCount * sizeof(SkPoint); // texs[]
    storageSize += indexCount * sizeof(uint16_t);  // indices[]


#ifndef SK_SCALAR_IS_FLOAT
    SkDEBUGFAIL("SkScalar must be a float for these conversions to be valid");
#endif
    std::unique_ptr<char[]> storage(new char[storageSize]);
    SkPoint* texs = (SkPoint*)storage.get();
    uint16_t* indices = (uint16_t*)(texs + ptCount);

    // cons up texture coordinates and indices
    {
        const SkScalar w = SkIntToScalar(bitmap.width());
        const SkScalar h = SkIntToScalar(bitmap.height());
        const SkScalar dx = w / meshWidth;
        const SkScalar dy = h / meshHeight;

        SkPoint* texsPtr = texs;
        SkScalar y = 0;
        for (int i = 0; i <= meshHeight; i++) {
            if (i == meshHeight) {
                y = h;  // to ensure numerically we hit h exactly
            }
            SkScalar x = 0;
            for (int j = 0; j < meshWidth; j++) {
                texsPtr->set(x, y);
                texsPtr += 1;
                x += dx;
            }
            texsPtr->set(w, y);
            texsPtr += 1;
            y += dy;
        }
        SkASSERT(texsPtr - texs == ptCount);
    }

    // cons up indices
    {
        uint16_t* indexPtr = indices;
        int index = 0;
        for (int i = 0; i < meshHeight; i++) {
            for (int j = 0; j < meshWidth; j++) {
                // lower-left triangle
                *indexPtr++ = index;
                *indexPtr++ = index + meshWidth + 1;
                *indexPtr++ = index + meshWidth + 2;
                // upper-right triangle
                *indexPtr++ = index;
                *indexPtr++ = index + meshWidth + 2;
                *indexPtr++ = index + 1;
                // bump to the next cell
                index += 1;
            }
            // bump to the next row
            index += 1;
        }
        SkASSERT(indexPtr - indices == indexCount);
        SkASSERT((char*)indexPtr - (char*)storage.get() == storageSize);
    }

    // double-check that we have legal indices
#ifdef SK_DEBUG
    {
        for (int i = 0; i < indexCount; i++) {
            SkASSERT((unsigned)indices[i] < (unsigned)ptCount);
        }
    }
#endif

    // cons-up a shader for the bitmap
    SkPaint tmpPaint;
    if (paint) {
        tmpPaint = *paint;
    }
    sk_sp<SkShader> shader = SkMakeBitmapShader(bitmap,
                                                SkShader::kClamp_TileMode,
                                                SkShader::kClamp_TileMode,
                                                nullptr,
                                                kNever_SkCopyPixelsMode,
                                                nullptr);
    tmpPaint.setShader(std::move(shader));

    mCanvas->drawVertices(SkCanvas::kTriangles_VertexMode, ptCount, (SkPoint*)vertices,
                         texs, (const SkColor*)colors, NULL, indices,
                         indexCount, tmpPaint);
}

void SkiaCanvas::drawNinePatch(const SkBitmap& bitmap, const Res_png_9patch& chunk,
        float dstLeft, float dstTop, float dstRight, float dstBottom, const SkPaint* paint) {
    SkRect bounds = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    NinePatch::Draw(mCanvas.get(), bounds, bitmap, chunk, paint, nullptr);
}

void SkiaCanvas::drawVectorDrawable(VectorDrawableRoot* vectorDrawable) {
    vectorDrawable->drawStaging(this);
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Text
// ----------------------------------------------------------------------------

void SkiaCanvas::drawGlyphs(const uint16_t* text, const float* positions, int count,
        const SkPaint& paint, float x, float y,
        float boundsLeft, float boundsTop, float boundsRight, float boundsBottom,
        float totalAdvance) {
    static_assert(sizeof(SkPoint) == sizeof(float)*2, "SkPoint is no longer two floats");
    mCanvas->drawPosText(text, count << 1, reinterpret_cast<const SkPoint*>(positions), paint);
    drawTextDecorations(x, y, totalAdvance, paint);
}

void SkiaCanvas::drawLayoutOnPath(const minikin::Layout& layout, float hOffset, float vOffset,
        const SkPaint& paint, const SkPath& path, size_t start, size_t end) {
    const int N = end - start;
    SkAutoSMalloc<1024> storage(N * (sizeof(uint16_t) + sizeof(SkRSXform)));
    SkRSXform* xform = (SkRSXform*)storage.get();
    uint16_t* glyphs = (uint16_t*)(xform + N);
    SkPathMeasure meas(path, false);

    for (size_t i = start; i < end; i++) {
        glyphs[i - start] = layout.getGlyphId(i);
        float x = hOffset + layout.getX(i);
        float y = vOffset + layout.getY(i);

        SkPoint pos;
        SkVector tan;
        if (!meas.getPosTan(x, &pos, &tan)) {
            pos.set(x, y);
            tan.set(1, 0);
        }
        xform[i - start].fSCos = tan.x();
        xform[i - start].fSSin = tan.y();
        xform[i - start].fTx   = pos.x() - tan.y() * y;
        xform[i - start].fTy   = pos.y() + tan.x() * y;
    }

    this->asSkCanvas()->drawTextRSXform(glyphs, sizeof(uint16_t) * N, xform, nullptr, paint);
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Animations
// ----------------------------------------------------------------------------

class AnimatedRoundRect : public SkDrawable {
 public:
    AnimatedRoundRect(uirenderer::CanvasPropertyPrimitive* left,
            uirenderer::CanvasPropertyPrimitive* top, uirenderer::CanvasPropertyPrimitive* right,
            uirenderer::CanvasPropertyPrimitive* bottom, uirenderer::CanvasPropertyPrimitive* rx,
            uirenderer::CanvasPropertyPrimitive* ry, uirenderer::CanvasPropertyPaint* p) :
            mLeft(left), mTop(top), mRight(right), mBottom(bottom), mRx(rx), mRy(ry), mPaint(p) {}

 protected:
     virtual SkRect onGetBounds() override {
         return SkRect::MakeLTRB(mLeft->value, mTop->value, mRight->value, mBottom->value);
     }
     virtual void onDraw(SkCanvas* canvas) override {
         SkRect rect = SkRect::MakeLTRB(mLeft->value, mTop->value, mRight->value, mBottom->value);
         canvas->drawRoundRect(rect, mRx->value, mRy->value, mPaint->value);
     }

 private:
    sp<uirenderer::CanvasPropertyPrimitive> mLeft;
    sp<uirenderer::CanvasPropertyPrimitive> mTop;
    sp<uirenderer::CanvasPropertyPrimitive> mRight;
    sp<uirenderer::CanvasPropertyPrimitive> mBottom;
    sp<uirenderer::CanvasPropertyPrimitive> mRx;
    sp<uirenderer::CanvasPropertyPrimitive> mRy;
    sp<uirenderer::CanvasPropertyPaint> mPaint;
};

class AnimatedCircle : public SkDrawable {
 public:
    AnimatedCircle(uirenderer::CanvasPropertyPrimitive* x, uirenderer::CanvasPropertyPrimitive* y,
            uirenderer::CanvasPropertyPrimitive* radius, uirenderer::CanvasPropertyPaint* paint) :
            mX(x), mY(y), mRadius(radius), mPaint(paint) {}

 protected:
     virtual SkRect onGetBounds() override {
         const float x = mX->value;
         const float y = mY->value;
         const float radius = mRadius->value;
         return SkRect::MakeLTRB(x - radius, y - radius, x + radius, y + radius);
     }
     virtual void onDraw(SkCanvas* canvas) override {
         canvas->drawCircle(mX->value, mY->value, mRadius->value, mPaint->value);
     }

 private:
    sp<uirenderer::CanvasPropertyPrimitive> mX;
    sp<uirenderer::CanvasPropertyPrimitive> mY;
    sp<uirenderer::CanvasPropertyPrimitive> mRadius;
    sp<uirenderer::CanvasPropertyPaint> mPaint;
};

void SkiaCanvas::drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
        uirenderer::CanvasPropertyPrimitive* top, uirenderer::CanvasPropertyPrimitive* right,
        uirenderer::CanvasPropertyPrimitive* bottom, uirenderer::CanvasPropertyPrimitive* rx,
        uirenderer::CanvasPropertyPrimitive* ry, uirenderer::CanvasPropertyPaint* paint) {
    sk_sp<AnimatedRoundRect> drawable(
            new AnimatedRoundRect(left, top, right, bottom, rx, ry, paint));
    mCanvas->drawDrawable(drawable.get());
}

void SkiaCanvas::drawCircle(uirenderer::CanvasPropertyPrimitive* x, uirenderer::CanvasPropertyPrimitive* y,
        uirenderer::CanvasPropertyPrimitive* radius, uirenderer::CanvasPropertyPaint* paint) {
    sk_sp<AnimatedCircle> drawable(new AnimatedCircle(x, y, radius, paint));
    mCanvas->drawDrawable(drawable.get());
}

// ----------------------------------------------------------------------------
// Canvas draw operations: View System
// ----------------------------------------------------------------------------

void SkiaCanvas::drawLayer(uirenderer::DeferredLayerUpdater* layer) {
    LOG_ALWAYS_FATAL("SkiaCanvas can't directly draw Layers");
}

void SkiaCanvas::drawRenderNode(uirenderer::RenderNode* renderNode) {
    LOG_ALWAYS_FATAL("SkiaCanvas can't directly draw RenderNodes");
}

void SkiaCanvas::callDrawGLFunction(Functor* functor,
        uirenderer::GlFunctorLifecycleListener* listener) {
    LOG_ALWAYS_FATAL("SkiaCanvas can't directly draw GL Content");
}

} // namespace android
