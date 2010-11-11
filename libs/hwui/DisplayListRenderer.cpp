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
// Defines
///////////////////////////////////////////////////////////////////////////////

#define PATH_HEAP_SIZE 64

///////////////////////////////////////////////////////////////////////////////
// Helpers
///////////////////////////////////////////////////////////////////////////////

PathHeap::PathHeap(): mHeap(PATH_HEAP_SIZE * sizeof(SkPath)) {
}

PathHeap::PathHeap(SkFlattenableReadBuffer& buffer): mHeap(PATH_HEAP_SIZE * sizeof(SkPath)) {
    int count = buffer.readS32();

    mPaths.setCount(count);
    SkPath** ptr = mPaths.begin();
    SkPath* p = (SkPath*) mHeap.allocThrow(count * sizeof(SkPath));

    for (int i = 0; i < count; i++) {
        new (p) SkPath;
        p->unflatten(buffer);
        *ptr++ = p;
        p++;
    }
}

PathHeap::~PathHeap() {
    SkPath** iter = mPaths.begin();
    SkPath** stop = mPaths.end();
    while (iter < stop) {
        (*iter)->~SkPath();
        iter++;
    }
}

int PathHeap::append(const SkPath& path) {
    SkPath* p = (SkPath*) mHeap.allocThrow(sizeof(SkPath));
    new (p) SkPath(path);
    *mPaths.append() = p;
    return mPaths.count();
}

void PathHeap::flatten(SkFlattenableWriteBuffer& buffer) const {
    int count = mPaths.count();

    buffer.write32(count);
    SkPath** iter = mPaths.begin();
    SkPath** stop = mPaths.end();
    while (iter < stop) {
        (*iter)->flatten(buffer);
        iter++;
    }
}

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

    Caches& caches = Caches::getInstance();

    const Vector<SkBitmap*> &bitmapResources = recorder.getBitmapResources();
    for (size_t i = 0; i < bitmapResources.size(); i++) {
        SkBitmap* resource = bitmapResources.itemAt(i);
        mBitmapResources.add(resource);
        caches.resourceCache.incrementRefcount(resource);
    }

    const Vector<SkiaShader*> &shaderResources = recorder.getShaderResources();
    for (size_t i = 0; i < shaderResources.size(); i++) {
        SkiaShader* resource = shaderResources.itemAt(i);
        mShaderResources.add(resource);
        caches.resourceCache.incrementRefcount(resource);
    }

    const Vector<SkPaint*> &paints = recorder.getPaints();
    for (size_t i = 0; i < paints.size(); i++) {
        mPaints.add(paints.itemAt(i));
    }

    const Vector<SkMatrix*> &matrices = recorder.getMatrices();
    for (size_t i = 0; i < matrices.size(); i++) {
        mMatrices.add(matrices.itemAt(i));
    }

    mPathHeap = recorder.mPathHeap;
    if (mPathHeap) {
        mPathHeap->safeRef();
    }
}

DisplayList::~DisplayList() {
    sk_free((void*) mReader.base());

    Caches& caches = Caches::getInstance();

    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        caches.resourceCache.decrementRefcount(mBitmapResources.itemAt(i));
    }
    mBitmapResources.clear();

    for (size_t i = 0; i < mShaderResources.size(); i++) {
        caches.resourceCache.decrementRefcount(mShaderResources.itemAt(i));
    }
    mShaderResources.clear();

    for (size_t i = 0; i < mPaints.size(); i++) {
        delete mPaints.itemAt(i);
    }
    mPaints.clear();

    for (size_t i = 0; i < mMatrices.size(); i++) {
        delete mMatrices.itemAt(i);
    }
    mMatrices.clear();

    if (mPathHeap) {
        for (int i = 0; i < mPathHeap->count(); i++) {
            caches.pathCache.removeDeferred(&(*mPathHeap)[i]);
        }
        mPathHeap->safeUnref();
    }
}

void DisplayList::init() {
    mPathHeap = NULL;
}

void DisplayList::replay(OpenGLRenderer& renderer) {
    TextContainer text;
    mReader.rewind();

    int saveCount = renderer.getSaveCount() - 1;

    while (!mReader.eof()) {
        int op = mReader.readInt();
        switch (op) {
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
            case SaveLayerAlpha: {
                renderer.saveLayerAlpha(getFloat(), getFloat(), getFloat(), getFloat(),
                        getInt(), getInt());
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
            case DrawDisplayList: {
                renderer.drawDisplayList(getDisplayList());
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
                uint32_t* colors = NULL;
                uint32_t xDivsCount = 0;
                uint32_t yDivsCount = 0;
                int8_t numColors = 0;

                SkBitmap* bitmap = getBitmap();

                xDivs = getInts(xDivsCount);
                yDivs = getInts(yDivsCount);
                colors = getUInts(numColors);

                renderer.drawPatch(bitmap, xDivs, yDivs, colors, xDivsCount, yDivsCount,
                        numColors, getFloat(), getFloat(), getFloat(), getFloat(), getPaint());
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
                renderer.setupShader(getShader());
            }
            break;
            case ResetColorFilter: {
                renderer.resetColorFilter();
            }
            break;
            case SetupColorFilter: {
                renderer.setupColorFilter(getColorFilter());
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

    mWriter.reset();
    mHeap.reset();

    mRCRecorder.reset();
    mTFRecorder.reset();

    Caches& caches = Caches::getInstance();
    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        SkBitmap* resource = mBitmapResources.itemAt(i);
        caches.resourceCache.decrementRefcount(resource);
    }
    mBitmapResources.clear();

    for (size_t i = 0; i < mShaderResources.size(); i++) {
        SkiaShader* resource = mShaderResources.itemAt(i);
        caches.resourceCache.decrementRefcount(resource);
    }
    mShaderResources.clear();

    mPaints.clear();
    mPaintMap.clear();
    mMatrices.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Operations
///////////////////////////////////////////////////////////////////////////////

void DisplayListRenderer::setViewport(int width, int height) {
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;
}

void DisplayListRenderer::prepare(bool opaque) {
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
        SkPaint* p, int flags) {
    addOp(DisplayList::SaveLayer);
    addBounds(left, top, right, bottom);
    addPaint(p);
    addInt(flags);
    return OpenGLRenderer::save(flags);
}

int DisplayListRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    addOp(DisplayList::SaveLayerAlpha);
    addBounds(left, top, right, bottom);
    addInt(alpha);
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

void DisplayListRenderer::drawDisplayList(DisplayList* displayList) {
    addOp(DisplayList::DrawDisplayList);
    addDisplayList(displayList);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float left, float top,
        SkPaint* paint) {
    addOp(DisplayList::DrawBitmap);
    addBitmap(bitmap);
    addPoint(left, top);
    addPaint(paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, SkMatrix* matrix,
        SkPaint* paint) {
    addOp(DisplayList::DrawBitmapMatrix);
    addBitmap(bitmap);
    addMatrix(matrix);
    addPaint(paint);
}

void DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, SkPaint* paint) {
    addOp(DisplayList::DrawBitmapRect);
    addBitmap(bitmap);
    addBounds(srcLeft, srcTop, srcRight, srcBottom);
    addBounds(dstLeft, dstTop, dstRight, dstBottom);
    addPaint(paint);
}

void DisplayListRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
        const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
        float left, float top, float right, float bottom, SkPaint* paint) {
    addOp(DisplayList::DrawPatch);
    addBitmap(bitmap);
    addInts(xDivs, width);
    addInts(yDivs, height);
    addUInts(colors, numColors);
    addBounds(left, top, right, bottom);
    addPaint(paint);
}

void DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addOp(DisplayList::DrawColor);
    addInt(color);
    addInt(mode);
}

void DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        SkPaint* paint) {
    addOp(DisplayList::DrawRect);
    addBounds(left, top, right, bottom);
    addPaint(paint);
}

void DisplayListRenderer::drawPath(SkPath* path, SkPaint* paint) {
    addOp(DisplayList::DrawPath);
    addPath(path);
    addPaint(paint);
}

void DisplayListRenderer::drawLines(float* points, int count, SkPaint* paint) {
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
}

void DisplayListRenderer::setupShader(SkiaShader* shader) {
    addOp(DisplayList::SetupShader);
    addShader(shader);
}

void DisplayListRenderer::resetColorFilter() {
    addOp(DisplayList::ResetColorFilter);
}

void DisplayListRenderer::setupColorFilter(SkiaColorFilter* filter) {
    addOp(DisplayList::SetupColorFilter);
    addColorFilter(filter);
}

void DisplayListRenderer::resetShadow() {
    addOp(DisplayList::ResetShadow);
}

void DisplayListRenderer::setupShadow(float radius, float dx, float dy, int color) {
    addOp(DisplayList::SetupShadow);
    addFloat(radius);
    addPoint(dx, dy);
    addInt(color);
}

}; // namespace uirenderer
}; // namespace android
