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
// Display list
///////////////////////////////////////////////////////////////////////////////

DisplayList::DisplayList(const DisplayListRenderer& recorder) {
    const SkWriter32& writer = recorder.writeStream();
    init();

    if (writer.size() == 0) {
        return;
    }

    size_t size = writer.size();
    void* buffer = sk_malloc_throw(size);
    writer.flatten(buffer);
    mReader.setMemory(buffer, size);

    mRCPlayback.reset(&recorder.mRCRecorder);
    mRCPlayback.setupBuffer(mReader);

    mTFPlayback.reset(&recorder.mTFRecorder);
    mTFPlayback.setupBuffer(mReader);

    const SkTDArray<const SkFlatBitmap*>& bitmaps = recorder.getBitmaps();
    mBitmapCount = bitmaps.count();
    if (mBitmapCount > 0) {
        mBitmaps = new SkBitmap[mBitmapCount];
        for (const SkFlatBitmap** flatBitmapPtr = bitmaps.begin();
                flatBitmapPtr != bitmaps.end(); flatBitmapPtr++) {
            const SkFlatBitmap* flatBitmap = *flatBitmapPtr;
            int index = flatBitmap->index() - 1;
            flatBitmap->unflatten(&mBitmaps[index], &mRCPlayback);
        }
    }

    const SkTDArray<const SkFlatMatrix*>& matrices = recorder.getMatrices();
    mMatrixCount = matrices.count();
    if (mMatrixCount > 0) {
        mMatrices = new SkMatrix[mMatrixCount];
        for (const SkFlatMatrix** matrixPtr = matrices.begin();
                matrixPtr != matrices.end(); matrixPtr++) {
            const SkFlatMatrix* flatMatrix = *matrixPtr;
            flatMatrix->unflatten(&mMatrices[flatMatrix->index() - 1]);
        }
    }

    const SkTDArray<const SkFlatPaint*>& paints = recorder.getPaints();
    mPaintCount = paints.count();
    if (mPaintCount > 0) {
        mPaints = new SkPaint[mPaintCount];
        for (const SkFlatPaint** flatPaintPtr = paints.begin();
                flatPaintPtr != paints.end(); flatPaintPtr++) {
            const SkFlatPaint* flatPaint = *flatPaintPtr;
            int index = flatPaint->index() - 1;
            flatPaint->unflatten(&mPaints[index], &mRCPlayback, &mTFPlayback);
        }
    }

    mPathHeap = recorder.mPathHeap;
    mPathHeap->safeRef();
}

DisplayList::~DisplayList() {
    sk_free((void*) mReader.base());

    Caches& caches = Caches::getInstance();
    for (int i = 0; i < mBitmapCount; i++) {
        caches.textureCache.remove(&mBitmaps[i]);
    }

    delete[] mBitmaps;
    delete[] mMatrices;
    delete[] mPaints;

    mPathHeap->safeUnref();
}

void DisplayList::init() {
    mBitmaps = NULL;
    mMatrices = NULL;
    mPaints = NULL;
    mPathHeap = NULL;
    mBitmapCount = mMatrixCount = mPaintCount = 0;
}

void DisplayList::replay(OpenGLRenderer& renderer) {
    TextContainer text;
    mReader.rewind();

    int saveCount = renderer.getSaveCount() - 1;

    while (!mReader.eof()) {
        switch (mReader.readInt()) {
            case AcquireContext: {
                renderer.acquireContext();
            }
            break;
            case ReleaseContext: {
                renderer.releaseContext();
            }
            break;
            case Save: {
                renderer.save(getInt());
            }
            break;
            case Restore: {
                renderer.restore();
            }
            break;
            case RestoreToCount: {
                renderer.restoreToCount(saveCount + getInt());
            }
            break;
            case SaveLayer: {
                renderer.saveLayer(getFloat(), getFloat(), getFloat(), getFloat(),
                        getPaint(), getInt());
            }
            break;
            case Translate: {
                renderer.translate(getFloat(), getFloat());
            }
            break;
            case Rotate: {
                renderer.rotate(getFloat());
            }
            break;
            case Scale: {
                renderer.scale(getFloat(), getFloat());
            }
            break;
            case SetMatrix: {
                renderer.setMatrix(getMatrix());
            }
            break;
            case ConcatMatrix: {
                renderer.concatMatrix(getMatrix());
            }
            break;
            case ClipRect: {
                renderer.clipRect(getFloat(), getFloat(), getFloat(), getFloat(),
                        (SkRegion::Op) getInt());
            }
            break;
            case DrawBitmap: {
                renderer.drawBitmap(getBitmap(), getFloat(), getFloat(), getPaint());
            }
            break;
            case DrawBitmapMatrix: {
                renderer.drawBitmap(getBitmap(), getMatrix(), getPaint());
            }
            break;
            case DrawBitmapRect: {
                renderer.drawBitmap(getBitmap(), getFloat(), getFloat(), getFloat(), getFloat(),
                        getFloat(), getFloat(), getFloat(), getFloat(), getPaint());
            }
            break;
            case DrawPatch: {
                int32_t* xDivs = NULL;
                int32_t* yDivs = NULL;
                uint32_t xDivsCount = 0;
                uint32_t yDivsCount = 0;

                SkBitmap* bitmap = getBitmap();

                xDivs = getInts(xDivsCount);
                yDivs = getInts(yDivsCount);

                renderer.drawPatch(bitmap, xDivs, yDivs, xDivsCount, yDivsCount,
                        getFloat(), getFloat(), getFloat(), getFloat(), getPaint());
            }
            break;
            case DrawColor: {
                renderer.drawColor(getInt(), (SkXfermode::Mode) getInt());
            }
            break;
            case DrawRect: {
                renderer.drawRect(getFloat(), getFloat(), getFloat(), getFloat(), getPaint());
            }
            break;
            case DrawPath: {
                renderer.drawPath(getPath(), getPaint());
            }
            break;
            case DrawLines: {
                int count = 0;
                float* points = getFloats(count);
                renderer.drawLines(points, count, getPaint());
            }
            break;
            case DrawText: {
                getText(&text);
                renderer.drawText(text.text(), text.length(), getInt(),
                        getFloat(), getFloat(), getPaint());
            }
            break;
            case ResetShader: {
                renderer.resetShader();
            }
            break;
            case SetupShader: {
                // TODO: Implement
            }
            break;
            case ResetColorFilter: {
                renderer.resetColorFilter();
            }
            break;
            case SetupColorFilter: {
                // TODO: Implement
            }
            break;
            case ResetShadow: {
                renderer.resetShadow();
            }
            break;
            case SetupShadow: {
                renderer.setupShadow(getFloat(), getFloat(), getFloat(), getInt());
            }
            break;
        }
    }
}

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

void DisplayListRenderer::setViewport(int width, int height) {
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;
}

void DisplayListRenderer::prepare() {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSaveCount = 1;
    mSnapshot->setClip(0.0f, 0.0f, mWidth, mHeight);
}

void DisplayListRenderer::acquireContext() {
    addOp(DisplayList::AcquireContext);
    OpenGLRenderer::acquireContext();
}

void DisplayListRenderer::releaseContext() {
    addOp(DisplayList::ReleaseContext);
    OpenGLRenderer::releaseContext();
}

int DisplayListRenderer::save(int flags) {
    addOp(DisplayList::Save);
    addInt(flags);
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::restore() {
    addOp(DisplayList::Restore);
    OpenGLRenderer::restore();
}

void DisplayListRenderer::restoreToCount(int saveCount) {
    addOp(DisplayList::RestoreToCount);
    addInt(saveCount);
    OpenGLRenderer::restoreToCount(saveCount);
}

int DisplayListRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* p, int flags) {
    addOp(DisplayList::SaveLayer);
    addBounds(left, top, right, bottom);
    addPaint(p);
    addInt(flags);
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::translate(float dx, float dy) {
    addOp(DisplayList::Translate);
    addPoint(dx, dy);
    OpenGLRenderer::translate(dx, dy);
}

void DisplayListRenderer::rotate(float degrees) {
    addOp(DisplayList::Rotate);
    addFloat(degrees);
    OpenGLRenderer::rotate(degrees);
}

void DisplayListRenderer::scale(float sx, float sy) {
    addOp(DisplayList::Scale);
    addPoint(sx, sy);
    OpenGLRenderer::scale(sx, sy);
}

void DisplayListRenderer::setMatrix(SkMatrix* matrix) {
    addOp(DisplayList::SetMatrix);
    addMatrix(matrix);
    OpenGLRenderer::setMatrix(matrix);
}

void DisplayListRenderer::concatMatrix(SkMatrix* matrix) {
    addOp(DisplayList::ConcatMatrix);
    addMatrix(matrix);
    OpenGLRenderer::concatMatrix(matrix);
}

bool DisplayListRenderer::clipRect(float left, float top, float right, float bottom,
        SkRegion::Op op) {
    addOp(DisplayList::ClipRect);
    addBounds(left, top, right, bottom);
    addInt(op);
    return OpenGLRenderer::clipRect(left, top, right, bottom, op);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float left, float top,
        const SkPaint* paint) {
    addOp(DisplayList::DrawBitmap);
    addBitmap(bitmap);
    addPoint(left, top);
    addPaint(paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, const SkMatrix* matrix,
        const SkPaint* paint) {
    addOp(DisplayList::DrawBitmapMatrix);
    addBitmap(bitmap);
    addMatrix(matrix);
    addPaint(paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, const SkPaint* paint) {
    addOp(DisplayList::DrawBitmapRect);
    addBitmap(bitmap);
    addBounds(srcLeft, srcTop, srcRight, srcBottom);
    addBounds(dstLeft, dstTop, dstRight, dstBottom);
    addPaint(paint);
}

void DisplayListRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        uint32_t width, uint32_t height, float left, float top, float right, float bottom,
        const SkPaint* paint) {
    addOp(DisplayList::DrawPatch);
    addBitmap(bitmap);
    addInts(xDivs, width);
    addInts(yDivs, height);
    addBounds(left, top, right, bottom);
    addPaint(paint);
}

void DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addOp(DisplayList::DrawColor);
    addInt(color);
    addInt(mode);
}

void DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        const SkPaint* paint) {
    addOp(DisplayList::DrawRect);
    addBounds(left, top, right, bottom);
    addPaint(paint);
}

void DisplayListRenderer::drawPath(SkPath* path, SkPaint* paint) {
    addOp(DisplayList::DrawPath);
    addPath(path);
    addPaint(paint);
}

void DisplayListRenderer::drawLines(float* points, int count, const SkPaint* paint) {
    addOp(DisplayList::DrawLines);
    addFloats(points, count);
    addPaint(paint);
}

void DisplayListRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, SkPaint* paint) {
    addOp(DisplayList::DrawText);
    addText(text, bytesCount);
    addInt(count);
    addPoint(x, y);
    addPaint(paint);
}

void DisplayListRenderer::resetShader() {
    addOp(DisplayList::ResetShader);
    OpenGLRenderer::resetShader();
}

void DisplayListRenderer::setupShader(SkiaShader* shader) {
    // TODO: Implement
    OpenGLRenderer::setupShader(shader);
}

void DisplayListRenderer::resetColorFilter() {
    addOp(DisplayList::ResetColorFilter);
    OpenGLRenderer::resetColorFilter();
}

void DisplayListRenderer::setupColorFilter(SkiaColorFilter* filter) {
    // TODO: Implement
    OpenGLRenderer::setupColorFilter(filter);
}

void DisplayListRenderer::resetShadow() {
    addOp(DisplayList::ResetShadow);
    OpenGLRenderer::resetShadow();
}

void DisplayListRenderer::setupShadow(float radius, float dx, float dy, int color) {
    addOp(DisplayList::SetupShadow);
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
