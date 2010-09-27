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

#include "DisplayListRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Base structure
///////////////////////////////////////////////////////////////////////////////

DisplayListRenderer::DisplayListRenderer():
        mHeap(HEAP_BLOCK_SIZE), mWriter(MIN_WRITER_SIZE) {
    mBitmapIndex = mMatrixIndex = mPaintIndex = 1;
    mPathHeap = NULL;
}

DisplayListRenderer::~DisplayListRenderer() {
    reset();
}

void DisplayListRenderer::reset() {
    if (mPathHeap) {
        mPathHeap->unref();
        mPathHeap = NULL;
    }

    mBitmaps.reset();
    mMatrices.reset();
    mPaints.reset();

    mWriter.reset();
    mHeap.reset();

    mRCRecorder.reset();
    mTFRecorder.reset();
}

///////////////////////////////////////////////////////////////////////////////
// Operations
///////////////////////////////////////////////////////////////////////////////

void DisplayListRenderer::acquireContext() {
    addOp(AcquireContext);
    OpenGLRenderer::acquireContext();
}

void DisplayListRenderer::releaseContext() {
    addOp(ReleaseContext);
    OpenGLRenderer::releaseContext();
}

int DisplayListRenderer::save(int flags) {
    addOp(Save);
    addInt(flags);
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::restore() {
    addOp(Restore);
    OpenGLRenderer::restore();
}

void DisplayListRenderer::restoreToCount(int saveCount) {
    addOp(RestoreToCount);
    addInt(saveCount);
    OpenGLRenderer::restoreToCount(saveCount);
}

int DisplayListRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* p, int flags) {
    addOp(SaveLayer);
    addBounds(left, top, right, bottom);
    addPaint(p);
    addInt(flags);
    return OpenGLRenderer::saveLayer(left, top, right, bottom, p, flags);
}

void DisplayListRenderer::translate(float dx, float dy) {
    addOp(Translate);
    addPoint(dx, dy);
    OpenGLRenderer::translate(dx, dy);
}

void DisplayListRenderer::rotate(float degrees) {
    addOp(Rotate);
    addFloat(degrees);
    OpenGLRenderer::rotate(degrees);
}

void DisplayListRenderer::scale(float sx, float sy) {
    addOp(Scale);
    addPoint(sx, sy);
    OpenGLRenderer::scale(sx, sy);
}

void DisplayListRenderer::setMatrix(SkMatrix* matrix) {
    addOp(SetMatrix);
    addMatrix(matrix);
    OpenGLRenderer::setMatrix(matrix);
}

void DisplayListRenderer::concatMatrix(SkMatrix* matrix) {
    addOp(ConcatMatrix);
    addMatrix(matrix);
    OpenGLRenderer::concatMatrix(matrix);
}

bool DisplayListRenderer::clipRect(float left, float top, float right, float bottom,
        SkRegion::Op op) {
    addOp(ClipRect);
    addBounds(left, top, right, bottom);
    addInt(op);
    return OpenGLRenderer::clipRect(left, top, right, bottom, op);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float left, float top,
        const SkPaint* paint) {
    addOp(DrawBitmap);
    addBitmap(bitmap);
    addPoint(left, top);
    addPaint(paint);
    OpenGLRenderer::drawBitmap(bitmap, left, top, paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, const SkMatrix* matrix,
        const SkPaint* paint) {
    addOp(DrawBitmapMatrix);
    addBitmap(bitmap);
    addMatrix(matrix);
    addPaint(paint);
    OpenGLRenderer::drawBitmap(bitmap, matrix, paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, const SkPaint* paint) {
    addOp(DrawBitmapRect);
    addBitmap(bitmap);
    addBounds(srcLeft, srcTop, srcRight, srcBottom);
    addBounds(dstLeft, dstTop, dstRight, dstBottom);
    addPaint(paint);
    OpenGLRenderer::drawBitmap(bitmap, srcLeft, srcTop, srcRight, srcBottom,
            dstLeft, dstTop, dstRight, dstBottom, paint);
}

void DisplayListRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        uint32_t width, uint32_t height, float left, float top, float right, float bottom,
        const SkPaint* paint) {
    addOp(DrawPatch);
    addBitmap(bitmap);
    addInts(xDivs, width);
    addInts(yDivs, height);
    addBounds(left, top, right, bottom);
    addPaint(paint);
    OpenGLRenderer::drawPatch(bitmap, xDivs, yDivs, width, height,
            left, top, right, bottom, paint);
}

void DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addOp(DrawColor);
    addInt(color);
    addInt(mode);
    OpenGLRenderer::drawColor(color, mode);
}

void DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        const SkPaint* paint) {
    addOp(DrawRect);
    addBounds(left, top, right, bottom);
    addPaint(paint);
    OpenGLRenderer::drawRect(left, top, right, bottom, paint);
}

void DisplayListRenderer::drawPath(SkPath* path, SkPaint* paint) {
    addOp(DrawPath);
    addPath(path);
    addPaint(paint);
    OpenGLRenderer::drawPath(path, paint);
}

void DisplayListRenderer::drawLines(float* points, int count, const SkPaint* paint) {
    addOp(DrawLines);
    addFloats(points, count);
    addPaint(paint);
    OpenGLRenderer::drawLines(points, count, paint);
}

void DisplayListRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, SkPaint* paint) {
    addOp(DrawText);
    addText(text, bytesCount);
    addInt(count);
    addPoint(x, y);
    addPaint(paint);
    OpenGLRenderer::drawText(text, bytesCount, count, x, y, paint);
}

void DisplayListRenderer::resetShader() {
    addOp(ResetShader);
    OpenGLRenderer::resetShader();
}

void DisplayListRenderer::setupShader(SkiaShader* shader) {
    // TODO: Implement
    OpenGLRenderer::setupShader(shader);
}

void DisplayListRenderer::resetColorFilter() {
    addOp(ResetColorFilter);
    OpenGLRenderer::resetColorFilter();
}

void DisplayListRenderer::setupColorFilter(SkiaColorFilter* filter) {
    // TODO: Implement
    OpenGLRenderer::setupColorFilter(filter);
}

void DisplayListRenderer::resetShadow() {
    addOp(ResetShadow);
    OpenGLRenderer::resetShadow();
}

void DisplayListRenderer::setupShadow(float radius, float dx, float dy, int color) {
    addOp(SetupShadow);
    addFloat(radius);
    addPoint(dx, dy);
    addInt(color);
    OpenGLRenderer::setupShadow(radius, dx, dy, color);
}

///////////////////////////////////////////////////////////////////////////////
// Recording management
///////////////////////////////////////////////////////////////////////////////

int DisplayListRenderer::find(SkTDArray<const SkFlatPaint*>& paints, const SkPaint* paint) {
    if (paint == NULL) {
        return 0;
    }

    SkFlatPaint* flat = SkFlatPaint::Flatten(&mHeap, *paint, mPaintIndex,
            &mRCRecorder, &mTFRecorder);
    int index = SkTSearch<SkFlatData>((const SkFlatData**) paints.begin(),
            paints.count(), (SkFlatData*) flat, sizeof(flat), &SkFlatData::Compare);
    if (index >= 0) {
        (void) mHeap.unalloc(flat);
        return paints[index]->index();
    }

    index = ~index;
    *paints.insert(index) = flat;
    return mPaintIndex++;
}

int DisplayListRenderer::find(SkTDArray<const SkFlatMatrix*>& matrices, const SkMatrix* matrix) {
    if (matrix == NULL) {
        return 0;
    }

    SkFlatMatrix* flat = SkFlatMatrix::Flatten(&mHeap, *matrix, mMatrixIndex);
    int index = SkTSearch<SkFlatData>((const SkFlatData**) matrices.begin(),
            matrices.count(), (SkFlatData*) flat, sizeof(flat), &SkFlatData::Compare);
    if (index >= 0) {
        (void) mHeap.unalloc(flat);
        return matrices[index]->index();
    }
    index = ~index;
    *matrices.insert(index) = flat;
    return mMatrixIndex++;
}

int DisplayListRenderer::find(SkTDArray<const SkFlatBitmap*>& bitmaps, const SkBitmap& bitmap) {
    SkFlatBitmap* flat = SkFlatBitmap::Flatten(&mHeap, bitmap, mBitmapIndex, &mRCRecorder);
    int index = SkTSearch<SkFlatData>((const SkFlatData**) bitmaps.begin(),
            bitmaps.count(), (SkFlatData*) flat, sizeof(flat), &SkFlatData::Compare);
    if (index >= 0) {
        (void) mHeap.unalloc(flat);
        return bitmaps[index]->index();
    }
    index = ~index;
    *bitmaps.insert(index) = flat;
    return mBitmapIndex++;
}

}; // namespace uirenderer
}; // namespace android
