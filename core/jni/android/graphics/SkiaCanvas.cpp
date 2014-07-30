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

#include "jni.h"
#include "Canvas.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkCanvas.h"
#include "SkClipStack.h"
#include "SkDevice.h"
#include "SkDeque.h"
#include "SkDrawFilter.h"
#include "SkGraphics.h"
#include "SkPorterDuff.h"
#include "SkShader.h"
#include "SkTArray.h"
#include "SkTemplates.h"

#include "MinikinUtils.h"

#include "TypefaceImpl.h"

#include "unicode/ubidi.h"
#include "unicode/ushape.h"

#include <utils/Log.h>

namespace android {

// Holds an SkCanvas reference plus additional native data.
class SkiaCanvas : public Canvas {
public:
    SkiaCanvas(SkBitmap* bitmap);

    SkiaCanvas(SkCanvas* canvas) : mCanvas(canvas) {
        SkASSERT(canvas);
    }

    virtual SkCanvas* getSkCanvas() {
        return mCanvas.get();
    }

    virtual void setBitmap(SkBitmap* bitmap, bool copyState);

    virtual bool isOpaque();
    virtual int width();
    virtual int height();

    virtual int getSaveCount() const;
    virtual int save(SkCanvas::SaveFlags flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);

    virtual int saveLayer(float left, float top, float right, float bottom,
                const SkPaint* paint, SkCanvas::SaveFlags flags);
    virtual int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, SkCanvas::SaveFlags flags);

    virtual void getMatrix(SkMatrix* outMatrix) const;
    virtual void setMatrix(const SkMatrix& matrix);
    virtual void concat(const SkMatrix& matrix);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);
    virtual void translate(float dx, float dy);

    virtual bool getClipBounds(SkRect* outRect) const;
    virtual bool quickRejectRect(float left, float top, float right, float bottom) const;
    virtual bool quickRejectPath(const SkPath& path) const;
    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    virtual bool clipPath(const SkPath* path, SkRegion::Op op);
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op);

    virtual SkDrawFilter* getDrawFilter();
    virtual void setDrawFilter(SkDrawFilter* drawFilter);

    virtual void drawColor(int color, SkXfermode::Mode mode);
    virtual void drawPaint(const SkPaint& paint);

    virtual void drawPoint(float x, float y, const SkPaint& paint);
    virtual void drawPoints(const float* points, int count, const SkPaint& paint);
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
            const SkPaint& paint);
    virtual void drawLines(const float* points, int count, const SkPaint& paint);
    virtual void drawRect(float left, float top, float right, float bottom, const SkPaint& paint);
    virtual void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint& paint);
    virtual void drawCircle(float x, float y, float radius, const SkPaint& paint);
    virtual void drawOval(float left, float top, float right, float bottom, const SkPaint& paint);
    virtual void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint);
    virtual void drawPath(const SkPath& path, const SkPaint& paint);
    virtual void drawVertices(SkCanvas::VertexMode vertexMode, int vertexCount,
            const float* verts, const float* tex, const int* colors,
            const uint16_t* indices, int indexCount, const SkPaint& paint);

    virtual void drawBitmap(const SkBitmap& bitmap, float left, float top, const SkPaint* paint);
    virtual void drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix, const SkPaint* paint);
    virtual void drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint);
    virtual void drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint);

    virtual void drawText(const uint16_t* text, const float* positions, int count,
            const SkPaint& paint, float x, float y,
            float boundsLeft, float boundsTop, float boundsRight, float boundsBottom);
    virtual void drawPosText(const uint16_t* text, const float* positions, int count,
            int posCount, const SkPaint& paint);
    virtual void drawTextOnPath(const uint16_t* glyphs, int count, const SkPath& path,
            float hOffset, float vOffset, const SkPaint& paint);

    virtual bool drawTextAbsolutePos() const { return true; }

private:
    struct SaveRec {
        int                 saveCount;
        SkCanvas::SaveFlags saveFlags;
    };

    void recordPartialSave(SkCanvas::SaveFlags flags);
    void saveClipsForFrame(SkTArray<SkClipStack::Element>& clips, int frameSaveCount);
    void applyClips(const SkTArray<SkClipStack::Element>& clips);

    void drawPoints(const float* points, int count, const SkPaint& paint,
                    SkCanvas::PointMode mode);
    void drawTextDecorations(float x, float y, float length, const SkPaint& paint);

    SkAutoTUnref<SkCanvas> mCanvas;
    SkAutoTDelete<SkDeque> mSaveStack; // lazily allocated, tracks partial saves.
};

// Construct an SkCanvas from the bitmap.
static SkCanvas* createCanvas(SkBitmap* bitmap) {
    if (bitmap) {
        return SkNEW_ARGS(SkCanvas, (*bitmap));
    }

    // Create an empty bitmap device to prevent callers from crashing
    // if they attempt to draw into this canvas.
    SkBitmap emptyBitmap;
    return new SkCanvas(emptyBitmap);
}

Canvas* Canvas::create_canvas(SkBitmap* bitmap) {
    return new SkiaCanvas(bitmap);
}

Canvas* Canvas::create_canvas(SkCanvas* skiaCanvas) {
    return new SkiaCanvas(skiaCanvas);
}

SkiaCanvas::SkiaCanvas(SkBitmap* bitmap) {
    mCanvas.reset(createCanvas(bitmap));
}

// ----------------------------------------------------------------------------
// Canvas state operations: Replace Bitmap
// ----------------------------------------------------------------------------

class ClipCopier : public SkCanvas::ClipVisitor {
public:
    ClipCopier(SkCanvas* dstCanvas) : m_dstCanvas(dstCanvas) {}

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

void SkiaCanvas::setBitmap(SkBitmap* bitmap, bool copyState) {
    SkCanvas* newCanvas = createCanvas(bitmap);
    SkASSERT(newCanvas);

    if (copyState) {
        // Copy the canvas matrix & clip state.
        newCanvas->setMatrix(mCanvas->getTotalMatrix());
        if (NULL != mCanvas->getDevice() && NULL != newCanvas->getDevice()) {
            ClipCopier copier(newCanvas);
            mCanvas->replayClips(&copier);
        }
    }

    // unrefs the existing canvas
    mCanvas.reset(newCanvas);

    // clean up the old save stack
    mSaveStack.reset(NULL);
}

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------

bool SkiaCanvas::isOpaque() {
    return mCanvas->getDevice()->accessBitmap(false).isOpaque();
}

int SkiaCanvas::width() {
    return mCanvas->getBaseLayerSize().width();
}

int SkiaCanvas::height() {
    return mCanvas->getBaseLayerSize().height();
}

// ----------------------------------------------------------------------------
// Canvas state operations: Save (layer)
// ----------------------------------------------------------------------------

int SkiaCanvas::getSaveCount() const {
    return mCanvas->getSaveCount();
}

int SkiaCanvas::save(SkCanvas::SaveFlags flags) {
    int count = mCanvas->save();
    recordPartialSave(flags);
    return count;
}

void SkiaCanvas::restore() {
    const SaveRec* rec = (NULL == mSaveStack.get())
            ? NULL
            : static_cast<SaveRec*>(mSaveStack->back());
    int currentSaveCount = mCanvas->getSaveCount() - 1;
    SkASSERT(NULL == rec || currentSaveCount >= rec->saveCount);

    if (NULL == rec || rec->saveCount != currentSaveCount) {
        // Fast path - no record for this frame.
        mCanvas->restore();
        return;
    }

    bool preserveMatrix = !(rec->saveFlags & SkCanvas::kMatrix_SaveFlag);
    bool preserveClip   = !(rec->saveFlags & SkCanvas::kClip_SaveFlag);

    SkMatrix savedMatrix;
    if (preserveMatrix) {
        savedMatrix = mCanvas->getTotalMatrix();
    }

    SkTArray<SkClipStack::Element> savedClips;
    if (preserveClip) {
        saveClipsForFrame(savedClips, currentSaveCount);
    }

    mCanvas->restore();

    if (preserveMatrix) {
        mCanvas->setMatrix(savedMatrix);
    }

    if (preserveClip && !savedClips.empty()) {
        applyClips(savedClips);
    }

    mSaveStack->pop_back();
}

void SkiaCanvas::restoreToCount(int restoreCount) {
    while (mCanvas->getSaveCount() > restoreCount) {
        this->restore();
    }
}

int SkiaCanvas::saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, SkCanvas::SaveFlags flags) {
    SkRect bounds = SkRect::MakeLTRB(left, top, right, bottom);
    int count = mCanvas->saveLayer(&bounds, paint, flags | SkCanvas::kMatrixClip_SaveFlag);
    recordPartialSave(flags);
    return count;
}

int SkiaCanvas::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, SkCanvas::SaveFlags flags) {
    SkRect bounds = SkRect::MakeLTRB(left, top, right, bottom);
    int count = mCanvas->saveLayerAlpha(&bounds, alpha, flags | SkCanvas::kMatrixClip_SaveFlag);
    recordPartialSave(flags);
    return count;
}

// ----------------------------------------------------------------------------
// functions to emulate legacy SaveFlags (i.e. independent matrix/clip flags)
// ----------------------------------------------------------------------------

void SkiaCanvas::recordPartialSave(SkCanvas::SaveFlags flags) {
    // A partial save is a save operation which doesn't capture the full canvas state.
    // (either kMatrix_SaveFlags or kClip_SaveFlag is missing).

    // Mask-out non canvas state bits.
    flags = static_cast<SkCanvas::SaveFlags>(flags & SkCanvas::kMatrixClip_SaveFlag);

    if (SkCanvas::kMatrixClip_SaveFlag == flags) {
        // not a partial save.
        return;
    }

    if (NULL == mSaveStack.get()) {
        mSaveStack.reset(SkNEW_ARGS(SkDeque, (sizeof(struct SaveRec), 8)));
    }

    SaveRec* rec = static_cast<SaveRec*>(mSaveStack->push_back());
    // Store the save counter in the SkClipStack domain.
    // (0-based, equal to the number of save ops on the stack).
    rec->saveCount = mCanvas->getSaveCount() - 1;
    rec->saveFlags = flags;
}

void SkiaCanvas::saveClipsForFrame(SkTArray<SkClipStack::Element>& clips, int frameSaveCount) {
    SkClipStack::Iter clipIterator(*mCanvas->getClipStack(),
                                   SkClipStack::Iter::kTop_IterStart);
    while (const SkClipStack::Element* elem = clipIterator.next()) {
        if (elem->getSaveCount() < frameSaveCount) {
            // done with the current frame.
            break;
        }
        SkASSERT(elem->getSaveCount() == frameSaveCount);
        clips.push_back(*elem);
    }
}

void SkiaCanvas::applyClips(const SkTArray<SkClipStack::Element>& clips) {
    ClipCopier clipCopier(mCanvas);

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
    return mCanvas->isClipEmpty();
}

bool SkiaCanvas::clipPath(const SkPath* path, SkRegion::Op op) {
    mCanvas->clipPath(*path, op);
    return mCanvas->isClipEmpty();
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
    return mCanvas->isClipEmpty();
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
    SkAutoSTMalloc<32, SkPoint> storage(count);
    SkPoint* pts = storage.get();
    for (int i = 0; i < count; i++) {
        pts[i].set(points[0], points[1]);
        points += 2;
    }
    mCanvas->drawPoints(mode, count, pts, paint);
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
    mCanvas->drawPath(path, paint);
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
    mCanvas->drawBitmapMatrix(bitmap, matrix, paint);
}

void SkiaCanvas::drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
                            float srcRight, float srcBottom, float dstLeft, float dstTop,
                            float dstRight, float dstBottom, const SkPaint* paint) {
    SkRect srcRect = SkRect::MakeLTRB(srcLeft, srcTop, srcRight, srcBottom);
    SkRect dstRect = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);
    mCanvas->drawBitmapRectToRect(bitmap, &srcRect, dstRect, paint);
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
    SkAutoMalloc storage(storageSize);
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
    SkShader* shader = SkShader::CreateBitmapShader(bitmap,
                                                    SkShader::kClamp_TileMode,
                                                    SkShader::kClamp_TileMode);
    SkSafeUnref(tmpPaint.setShader(shader));

    mCanvas->drawVertices(SkCanvas::kTriangles_VertexMode, ptCount, (SkPoint*)vertices,
                         texs, (const SkColor*)colors, NULL, indices,
                         indexCount, tmpPaint);
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Text
// ----------------------------------------------------------------------------

void SkiaCanvas::drawText(const uint16_t* text, const float* positions, int count,
        const SkPaint& paint, float x, float y,
        float boundsLeft, float boundsTop, float boundsRight, float boundsBottom) {
    // Set align to left for drawing, as we don't want individual
    // glyphs centered or right-aligned; the offset above takes
    // care of all alignment.
    SkPaint paintCopy(paint);
    paintCopy.setTextAlign(SkPaint::kLeft_Align);

    SK_COMPILE_ASSERT(sizeof(SkPoint) == sizeof(float)*2, SkPoint_is_no_longer_2_floats);
    mCanvas->drawPosText(text, count << 1, reinterpret_cast<const SkPoint*>(positions), paintCopy);
}

void SkiaCanvas::drawPosText(const uint16_t* text, const float* positions, int count, int posCount,
        const SkPaint& paint) {
    SkPoint* posPtr = posCount > 0 ? new SkPoint[posCount] : NULL;
    int indx;
    for (indx = 0; indx < posCount; indx++) {
        posPtr[indx].fX = positions[indx << 1];
        posPtr[indx].fY = positions[(indx << 1) + 1];
    }

    SkPaint paintCopy(paint);
    paintCopy.setTextEncoding(SkPaint::kUTF16_TextEncoding);
    mCanvas->drawPosText(text, count, posPtr, paintCopy);

    delete[] posPtr;
}

void SkiaCanvas::drawTextOnPath(const uint16_t* glyphs, int count, const SkPath& path,
        float hOffset, float vOffset, const SkPaint& paint) {
    mCanvas->drawTextOnPathHV(glyphs, count, path, hOffset, vOffset, paint);
}

} // namespace android
