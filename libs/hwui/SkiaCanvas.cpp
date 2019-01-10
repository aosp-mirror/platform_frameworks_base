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
#include "NinePatchUtils.h"
#include "VectorDrawable.h"
#include "hwui/Bitmap.h"
#include "hwui/MinikinUtils.h"
#include "hwui/PaintFilter.h"
#include "pipeline/skia/AnimatedDrawables.h"

#include <SkAndroidFrameworkUtils.h>
#include <SkAnimatedImage.h>
#include <SkCanvasStateUtils.h>
#include <SkColorFilter.h>
#include <SkDeque.h>
#include <SkDrawable.h>
#include <SkFont.h>
#include <SkGraphics.h>
#include <SkImage.h>
#include <SkImagePriv.h>
#include <SkPicture.h>
#include <SkRSXform.h>
#include <SkShader.h>
#include <SkTemplates.h>
#include <SkTextBlob.h>

#include <memory>
#include <optional>
#include <utility>

namespace android {

using uirenderer::PaintUtils;

Canvas* Canvas::create_canvas(const SkBitmap& bitmap) {
    return new SkiaCanvas(bitmap);
}

Canvas* Canvas::create_canvas(SkCanvas* skiaCanvas) {
    return new SkiaCanvas(skiaCanvas);
}

SkiaCanvas::SkiaCanvas() {}

SkiaCanvas::SkiaCanvas(SkCanvas* canvas) : mCanvas(canvas) {}

SkiaCanvas::SkiaCanvas(const SkBitmap& bitmap) {
    mCanvasOwned = std::unique_ptr<SkCanvas>(new SkCanvas(bitmap));
    mCanvas = mCanvasOwned.get();
}

SkiaCanvas::~SkiaCanvas() {}

void SkiaCanvas::reset(SkCanvas* skiaCanvas) {
    if (mCanvas != skiaCanvas) {
        mCanvas = skiaCanvas;
        mCanvasOwned.reset();
    }
    mSaveStack.reset(nullptr);
}

// ----------------------------------------------------------------------------
// Canvas state operations: Replace Bitmap
// ----------------------------------------------------------------------------

void SkiaCanvas::setBitmap(const SkBitmap& bitmap) {
    // deletes the previously owned canvas (if any)
    mCanvasOwned.reset(new SkCanvas(bitmap));
    mCanvas = mCanvasOwned.get();

    // clean up the old save stack
    mSaveStack.reset(nullptr);
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
    const auto* rec = this->currentSaveRec();
    if (!rec) {
        // Fast path - no record for this frame.
        mCanvas->restore();
        return;
    }

    bool preserveMatrix = !(rec->saveFlags & SaveFlags::Matrix);
    bool preserveClip = !(rec->saveFlags & SaveFlags::Clip);

    SkMatrix savedMatrix;
    if (preserveMatrix) {
        savedMatrix = mCanvas->getTotalMatrix();
    }

    const size_t clipIndex = rec->clipIndex;

    mCanvas->restore();
    mSaveStack->pop_back();

    if (preserveMatrix) {
        mCanvas->setMatrix(savedMatrix);
    }

    if (preserveClip) {
        this->applyPersistentClips(clipIndex);
    }
}

void SkiaCanvas::restoreToCount(int restoreCount) {
    while (mCanvas->getSaveCount() > restoreCount) {
        this->restore();
    }
}

static inline SkCanvas::SaveLayerFlags layerFlags(SaveFlags::Flags flags) {
    SkCanvas::SaveLayerFlags layerFlags = 0;

    if (!(flags & SaveFlags::ClipToLayer)) {
        layerFlags |= SkCanvas::kDontClipToLayer_Legacy_SaveLayerFlag;
    }

    return layerFlags;
}

int SkiaCanvas::saveLayer(float left, float top, float right, float bottom, const SkPaint* paint,
                          SaveFlags::Flags flags) {
    const SkRect bounds = SkRect::MakeLTRB(left, top, right, bottom);
    const SkCanvas::SaveLayerRec rec(&bounds, paint, layerFlags(flags));

    return mCanvas->saveLayer(rec);
}

int SkiaCanvas::saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
                               SaveFlags::Flags flags) {
    if (static_cast<unsigned>(alpha) < 0xFF) {
        SkPaint alphaPaint;
        alphaPaint.setAlpha(alpha);
        return this->saveLayer(left, top, right, bottom, &alphaPaint, flags);
    }
    return this->saveLayer(left, top, right, bottom, nullptr, flags);
}

int SkiaCanvas::saveUnclippedLayer(int left, int top, int right, int bottom) {
    SkRect bounds = SkRect::MakeLTRB(left, top, right, bottom);
    return SkAndroidFrameworkUtils::SaveBehind(mCanvas, &bounds);
}

class SkiaCanvas::Clip {
public:
    Clip(const SkRect& rect, SkClipOp op, const SkMatrix& m)
            : mType(Type::Rect), mOp(op), mMatrix(m), mRRect(SkRRect::MakeRect(rect)) {}
    Clip(const SkRRect& rrect, SkClipOp op, const SkMatrix& m)
            : mType(Type::RRect), mOp(op), mMatrix(m), mRRect(rrect) {}
    Clip(const SkPath& path, SkClipOp op, const SkMatrix& m)
            : mType(Type::Path), mOp(op), mMatrix(m), mPath(std::in_place, path) {}

    void apply(SkCanvas* canvas) const {
        canvas->setMatrix(mMatrix);
        switch (mType) {
            case Type::Rect:
                canvas->clipRect(mRRect.rect(), mOp);
                break;
            case Type::RRect:
                canvas->clipRRect(mRRect, mOp);
                break;
            case Type::Path:
                canvas->clipPath(mPath.value(), mOp);
                break;
        }
    }

private:
    enum class Type {
        Rect,
        RRect,
        Path,
    };

    Type mType;
    SkClipOp mOp;
    SkMatrix mMatrix;

    // These are logically a union (tracked separately due to non-POD path).
    std::optional<SkPath> mPath;
    SkRRect mRRect;
};

const SkiaCanvas::SaveRec* SkiaCanvas::currentSaveRec() const {
    const SaveRec* rec = mSaveStack ? static_cast<const SaveRec*>(mSaveStack->back()) : nullptr;
    int currentSaveCount = mCanvas->getSaveCount();
    SkASSERT(!rec || currentSaveCount >= rec->saveCount);

    return (rec && rec->saveCount == currentSaveCount) ? rec : nullptr;
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

    if (!mSaveStack) {
        mSaveStack.reset(new SkDeque(sizeof(struct SaveRec), 8));
    }

    SaveRec* rec = static_cast<SaveRec*>(mSaveStack->push_back());
    rec->saveCount = mCanvas->getSaveCount();
    rec->saveFlags = flags;
    rec->clipIndex = mClipStack.size();
}

template <typename T>
void SkiaCanvas::recordClip(const T& clip, SkClipOp op) {
    // Only need tracking when in a partial save frame which
    // doesn't restore the clip.
    const SaveRec* rec = this->currentSaveRec();
    if (rec && !(rec->saveFlags & SaveFlags::Clip)) {
        mClipStack.emplace_back(clip, op, mCanvas->getTotalMatrix());
    }
}

// Applies and optionally removes all clips >= index.
void SkiaCanvas::applyPersistentClips(size_t clipStartIndex) {
    SkASSERT(clipStartIndex <= mClipStack.size());
    const auto begin = mClipStack.cbegin() + clipStartIndex;
    const auto end = mClipStack.cend();

    // Clip application mutates the CTM.
    const SkMatrix saveMatrix = mCanvas->getTotalMatrix();

    for (auto clip = begin; clip != end; ++clip) {
        clip->apply(mCanvas);
    }

    mCanvas->setMatrix(saveMatrix);

    // If the current/post-restore save rec is also persisting clips, we
    // leave them on the stack to be reapplied part of the next restore().
    // Otherwise we're done and just pop them.
    const auto* rec = this->currentSaveRec();
    if (!rec || (rec->saveFlags & SaveFlags::Clip)) {
        mClipStack.erase(begin, end);
    }
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
    if (!mCanvas->getDeviceClipBounds(&ibounds)) {
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

bool SkiaCanvas::clipRect(float left, float top, float right, float bottom, SkClipOp op) {
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    this->recordClip(rect, op);
    mCanvas->clipRect(rect, op);
    return !mCanvas->isClipEmpty();
}

bool SkiaCanvas::clipPath(const SkPath* path, SkClipOp op) {
    this->recordClip(*path, op);
    mCanvas->clipPath(*path, op);
    return !mCanvas->isClipEmpty();
}

// ----------------------------------------------------------------------------
// Canvas state operations: Filters
// ----------------------------------------------------------------------------

PaintFilter* SkiaCanvas::getPaintFilter() {
    return mPaintFilter.get();
}

void SkiaCanvas::setPaintFilter(sk_sp<PaintFilter> paintFilter) {
    mPaintFilter = std::move(paintFilter);
}

// ----------------------------------------------------------------------------
// Canvas state operations: Capture
// ----------------------------------------------------------------------------

SkCanvasState* SkiaCanvas::captureCanvasState() const {
    SkCanvas* canvas = mCanvas;
    if (mCanvasOwned) {
        // Important to use the underlying SkCanvas, not the wrapper.
        canvas = mCanvasOwned.get();
    }

    // Workarounds for http://crbug.com/271096: SW draw only supports
    // translate & scale transforms, and a simple rectangular clip.
    // (This also avoids significant wasted time in calling
    // SkCanvasStateUtils::CaptureCanvasState when the clip is complex).
    if (!canvas->isClipRect() || (canvas->getTotalMatrix().getType() &
                                  ~(SkMatrix::kTranslate_Mask | SkMatrix::kScale_Mask))) {
        return nullptr;
    }

    return SkCanvasStateUtils::CaptureCanvasState(canvas);
}

// ----------------------------------------------------------------------------
// Canvas draw operations
// ----------------------------------------------------------------------------

void SkiaCanvas::drawColor(int color, SkBlendMode mode) {
    mCanvas->drawColor(color, mode);
}

SkiaCanvas::PaintCoW&& SkiaCanvas::filterPaint(PaintCoW&& paint) const {
    if (mPaintFilter) {
        mPaintFilter->filter(&paint.writeable());
    }
    return std::move(paint);
}

void SkiaCanvas::drawPaint(const SkPaint& paint) {
    mCanvas->drawPaint(*filterPaint(paint));
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Geometry
// ----------------------------------------------------------------------------

void SkiaCanvas::drawPoints(const float* points, int count, const SkPaint& paint,
                            SkCanvas::PointMode mode) {
    if (CC_UNLIKELY(count < 2 || paint.nothingToDraw())) return;
    // convert the floats into SkPoints
    count >>= 1;  // now it is the number of points
    std::unique_ptr<SkPoint[]> pts(new SkPoint[count]);
    for (int i = 0; i < count; i++) {
        pts[i].set(points[0], points[1]);
        points += 2;
    }
    mCanvas->drawPoints(mode, count, pts.get(), *filterPaint(paint));
}

void SkiaCanvas::drawPoint(float x, float y, const SkPaint& paint) {
    mCanvas->drawPoint(x, y, *filterPaint(paint));
}

void SkiaCanvas::drawPoints(const float* points, int count, const SkPaint& paint) {
    this->drawPoints(points, count, *filterPaint(paint), SkCanvas::kPoints_PointMode);
}

void SkiaCanvas::drawLine(float startX, float startY, float stopX, float stopY,
                          const SkPaint& paint) {
    mCanvas->drawLine(startX, startY, stopX, stopY, *filterPaint(paint));
}

void SkiaCanvas::drawLines(const float* points, int count, const SkPaint& paint) {
    if (CC_UNLIKELY(count < 4 || paint.nothingToDraw())) return;
    this->drawPoints(points, count, *filterPaint(paint), SkCanvas::kLines_PointMode);
}

void SkiaCanvas::drawRect(float left, float top, float right, float bottom, const SkPaint& paint) {
    if (CC_UNLIKELY(paint.nothingToDraw())) return;
    mCanvas->drawRect({left, top, right, bottom}, *filterPaint(paint));
}

void SkiaCanvas::drawRegion(const SkRegion& region, const SkPaint& paint) {
    if (CC_UNLIKELY(paint.nothingToDraw())) return;
    mCanvas->drawRegion(region, *filterPaint(paint));
}

void SkiaCanvas::drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
                               const SkPaint& paint) {
    if (CC_UNLIKELY(paint.nothingToDraw())) return;
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    mCanvas->drawRoundRect(rect, rx, ry, *filterPaint(paint));
}

void SkiaCanvas::drawDoubleRoundRect(const SkRRect& outer, const SkRRect& inner,
                                const SkPaint& paint) {
    mCanvas->drawDRRect(outer, inner, *filterPaint(paint));
}

void SkiaCanvas::drawCircle(float x, float y, float radius, const SkPaint& paint) {
    if (CC_UNLIKELY(radius <= 0 || paint.nothingToDraw())) return;
    mCanvas->drawCircle(x, y, radius, *filterPaint(paint));
}

void SkiaCanvas::drawOval(float left, float top, float right, float bottom, const SkPaint& paint) {
    if (CC_UNLIKELY(paint.nothingToDraw())) return;
    SkRect oval = SkRect::MakeLTRB(left, top, right, bottom);
    mCanvas->drawOval(oval, *filterPaint(paint));
}

void SkiaCanvas::drawArc(float left, float top, float right, float bottom, float startAngle,
                         float sweepAngle, bool useCenter, const SkPaint& paint) {
    if (CC_UNLIKELY(paint.nothingToDraw())) return;
    SkRect arc = SkRect::MakeLTRB(left, top, right, bottom);
    if (fabs(sweepAngle) >= 360.0f) {
        mCanvas->drawOval(arc, *filterPaint(paint));
    } else {
        mCanvas->drawArc(arc, startAngle, sweepAngle, useCenter, *filterPaint(paint));
    }
}

void SkiaCanvas::drawPath(const SkPath& path, const SkPaint& paint) {
    if (CC_UNLIKELY(paint.nothingToDraw())) return;
    if (CC_UNLIKELY(path.isEmpty() && (!path.isInverseFillType()))) {
        return;
    }
    mCanvas->drawPath(path, *filterPaint(paint));
}

void SkiaCanvas::drawVertices(const SkVertices* vertices, SkBlendMode mode, const SkPaint& paint) {
    mCanvas->drawVertices(vertices, mode, *filterPaint(paint));
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Bitmaps
// ----------------------------------------------------------------------------

void SkiaCanvas::drawBitmap(Bitmap& bitmap, float left, float top, const SkPaint* paint) {
    mCanvas->drawImage(bitmap.makeImage(), left, top, filterPaint(paint));
}

void SkiaCanvas::drawBitmap(Bitmap& bitmap, const SkMatrix& matrix, const SkPaint* paint) {
    SkAutoCanvasRestore acr(mCanvas, true);
    mCanvas->concat(matrix);
    mCanvas->drawImage(bitmap.makeImage(), 0, 0, filterPaint(paint));
}

void SkiaCanvas::drawBitmap(Bitmap& bitmap, float srcLeft, float srcTop, float srcRight,
                            float srcBottom, float dstLeft, float dstTop, float dstRight,
                            float dstBottom, const SkPaint* paint) {
    SkRect srcRect = SkRect::MakeLTRB(srcLeft, srcTop, srcRight, srcBottom);
    SkRect dstRect = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);

    mCanvas->drawImageRect(bitmap.makeImage(), srcRect, dstRect, filterPaint(paint),
                           SkCanvas::kFast_SrcRectConstraint);
}

void SkiaCanvas::drawBitmapMesh(Bitmap& bitmap, int meshWidth, int meshHeight,
                                const float* vertices, const int* colors, const SkPaint* paint) {
    const int ptCount = (meshWidth + 1) * (meshHeight + 1);
    const int indexCount = meshWidth * meshHeight * 6;
    uint32_t flags = SkVertices::kHasTexCoords_BuilderFlag;
    if (colors) {
        flags |= SkVertices::kHasColors_BuilderFlag;
    }
    SkVertices::Builder builder(SkVertices::kTriangles_VertexMode, ptCount, indexCount, flags);
    memcpy(builder.positions(), vertices, ptCount * sizeof(SkPoint));
    if (colors) {
        memcpy(builder.colors(), colors, ptCount * sizeof(SkColor));
    }
    SkPoint* texs = builder.texCoords();
    uint16_t* indices = builder.indices();

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
    PaintCoW paintCoW(paint);
    SkPaint& tmpPaint = paintCoW.writeable();

    sk_sp<SkImage> image = bitmap.makeImage();
    sk_sp<SkShader> shader =
            image->makeShader(SkShader::kClamp_TileMode, SkShader::kClamp_TileMode);
    tmpPaint.setShader(std::move(shader));

    mCanvas->drawVertices(builder.detach(), SkBlendMode::kModulate,
                          *filterPaint(std::move(paintCoW)));
}

void SkiaCanvas::drawNinePatch(Bitmap& bitmap, const Res_png_9patch& chunk, float dstLeft,
                               float dstTop, float dstRight, float dstBottom,
                               const SkPaint* paint) {
    SkCanvas::Lattice lattice;
    NinePatchUtils::SetLatticeDivs(&lattice, chunk, bitmap.width(), bitmap.height());

    lattice.fRectTypes = nullptr;
    lattice.fColors = nullptr;
    int numFlags = 0;
    if (chunk.numColors > 0 && chunk.numColors == NinePatchUtils::NumDistinctRects(lattice)) {
        // We can expect the framework to give us a color for every distinct rect.
        // Skia requires a flag for every rect.
        numFlags = (lattice.fXCount + 1) * (lattice.fYCount + 1);
    }

    SkAutoSTMalloc<25, SkCanvas::Lattice::RectType> flags(numFlags);
    SkAutoSTMalloc<25, SkColor> colors(numFlags);
    if (numFlags > 0) {
        NinePatchUtils::SetLatticeFlags(&lattice, flags.get(), numFlags, chunk, colors.get());
    }

    lattice.fBounds = nullptr;
    SkRect dst = SkRect::MakeLTRB(dstLeft, dstTop, dstRight, dstBottom);

    mCanvas->drawImageLattice(bitmap.makeImage().get(), lattice, dst, filterPaint(paint));
}

double SkiaCanvas::drawAnimatedImage(AnimatedImageDrawable* imgDrawable) {
    return imgDrawable->drawStaging(mCanvas);
}

void SkiaCanvas::drawVectorDrawable(VectorDrawableRoot* vectorDrawable) {
    vectorDrawable->drawStaging(this);
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Text
// ----------------------------------------------------------------------------

void SkiaCanvas::drawGlyphs(ReadGlyphFunc glyphFunc, int count, const Paint& paint, float x,
                            float y, float boundsLeft, float boundsTop, float boundsRight,
                            float boundsBottom, float totalAdvance) {
    if (count <= 0 || paint.nothingToDraw()) return;
    SkPaint paintCopy(paint);
    if (mPaintFilter) {
        mPaintFilter->filter(&paintCopy);
    }
    SkFont font = SkFont::LEGACY_ExtractFromPaint(paintCopy);
    SkASSERT(paintCopy.getTextEncoding() == kGlyphID_SkTextEncoding);
    // Stroke with a hairline is drawn on HW with a fill style for compatibility with Android O and
    // older.
    if (!mCanvasOwned && sApiLevel <= 27 && paintCopy.getStrokeWidth() <= 0 &&
        paintCopy.getStyle() == SkPaint::kStroke_Style) {
        paintCopy.setStyle(SkPaint::kFill_Style);
    }

    SkRect bounds =
            SkRect::MakeLTRB(boundsLeft + x, boundsTop + y, boundsRight + x, boundsBottom + y);

    SkTextBlobBuilder builder;
    const SkTextBlobBuilder::RunBuffer& buffer = builder.allocRunPos(font, count, &bounds);
    glyphFunc(buffer.glyphs, buffer.pos);

    sk_sp<SkTextBlob> textBlob(builder.make());
    mCanvas->drawTextBlob(textBlob, 0, 0, paintCopy);
    drawTextDecorations(x, y, totalAdvance, paintCopy);
}

void SkiaCanvas::drawLayoutOnPath(const minikin::Layout& layout, float hOffset, float vOffset,
                                  const Paint& paint, const SkPath& path, size_t start,
                                  size_t end) {
    SkPaint paintCopy(paint);
    if (mPaintFilter) {
        mPaintFilter->filter(&paintCopy);
    }
    SkFont font = SkFont::LEGACY_ExtractFromPaint(paintCopy);
    SkASSERT(paintCopy.getTextEncoding() == kGlyphID_SkTextEncoding);

    const int N = end - start;
    SkTextBlobBuilder builder;
    auto rec = builder.allocRunRSXform(font, N);
    SkRSXform* xform = (SkRSXform*)rec.pos;
    uint16_t* glyphs = rec.glyphs;
    SkPathMeasure meas(path, false);

    for (size_t i = start; i < end; i++) {
        glyphs[i - start] = layout.getGlyphId(i);
        float halfWidth = layout.getCharAdvance(i) * 0.5f;
        float x = hOffset + layout.getX(i) + halfWidth;
        float y = vOffset + layout.getY(i);

        SkPoint pos;
        SkVector tan;
        if (!meas.getPosTan(x, &pos, &tan)) {
            pos.set(x, y);
            tan.set(1, 0);
        }
        xform[i - start].fSCos = tan.x();
        xform[i - start].fSSin = tan.y();
        xform[i - start].fTx = pos.x() - tan.y() * y - halfWidth * tan.x();
        xform[i - start].fTy = pos.y() + tan.x() * y - halfWidth * tan.y();
    }

    this->asSkCanvas()->drawTextBlob(builder.make(), 0, 0, paintCopy);
}

// ----------------------------------------------------------------------------
// Canvas draw operations: Animations
// ----------------------------------------------------------------------------

void SkiaCanvas::drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
                               uirenderer::CanvasPropertyPrimitive* top,
                               uirenderer::CanvasPropertyPrimitive* right,
                               uirenderer::CanvasPropertyPrimitive* bottom,
                               uirenderer::CanvasPropertyPrimitive* rx,
                               uirenderer::CanvasPropertyPrimitive* ry,
                               uirenderer::CanvasPropertyPaint* paint) {
    sk_sp<uirenderer::skiapipeline::AnimatedRoundRect> drawable(
            new uirenderer::skiapipeline::AnimatedRoundRect(left, top, right, bottom, rx, ry,
                                                            paint));
    mCanvas->drawDrawable(drawable.get());
}

void SkiaCanvas::drawCircle(uirenderer::CanvasPropertyPrimitive* x,
                            uirenderer::CanvasPropertyPrimitive* y,
                            uirenderer::CanvasPropertyPrimitive* radius,
                            uirenderer::CanvasPropertyPaint* paint) {
    sk_sp<uirenderer::skiapipeline::AnimatedCircle> drawable(
            new uirenderer::skiapipeline::AnimatedCircle(x, y, radius, paint));
    mCanvas->drawDrawable(drawable.get());
}

// ----------------------------------------------------------------------------
// Canvas draw operations: View System
// ----------------------------------------------------------------------------

void SkiaCanvas::drawLayer(uirenderer::DeferredLayerUpdater* layerUpdater) {
    LOG_ALWAYS_FATAL("SkiaCanvas can't directly draw Layers");
}

void SkiaCanvas::drawRenderNode(uirenderer::RenderNode* renderNode) {
    LOG_ALWAYS_FATAL("SkiaCanvas can't directly draw RenderNodes");
}

void SkiaCanvas::callDrawGLFunction(Functor* functor,
                                    uirenderer::GlFunctorLifecycleListener* listener) {
    LOG_ALWAYS_FATAL("SkiaCanvas can't directly draw GL Content");
}

}  // namespace android
