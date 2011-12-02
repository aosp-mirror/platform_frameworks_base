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

#ifndef ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
#define ANDROID_HWUI_DISPLAY_LIST_RENDERER_H

#include <SkChunkAlloc.h>
#include <SkFlattenable.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkRefCnt.h>
#include <SkTDArray.h>
#include <SkTSearch.h>

#include <cutils/compiler.h>

#include "DisplayListLogBuffer.h"
#include "OpenGLRenderer.h"
#include "utils/Functor.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define MIN_WRITER_SIZE 4096

// Debug
#if DEBUG_DISPLAY_LIST
    #define DISPLAY_LIST_LOGD(...) LOGD(__VA_ARGS__)
#else
    #define DISPLAY_LIST_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Display list
///////////////////////////////////////////////////////////////////////////////

class DisplayListRenderer;

/**
 * Replays recorded drawing commands.
 */
class DisplayList {
public:
    DisplayList(const DisplayListRenderer& recorder);
    ANDROID_API ~DisplayList();

    // IMPORTANT: Update the intialization of OP_NAMES in the .cpp file
    //            when modifying this file
    enum Op {
        // Non-drawing operations
        Save = 0,
        Restore,
        RestoreToCount,
        SaveLayer,
        SaveLayerAlpha,
        Translate,
        Rotate,
        Scale,
        Skew,
        SetMatrix,
        ConcatMatrix,
        ClipRect,
        // Drawing operations
        DrawDisplayList,
        DrawLayer,
        DrawBitmap,
        DrawBitmapMatrix,
        DrawBitmapRect,
        DrawBitmapMesh,
        DrawPatch,
        DrawColor,
        DrawRect,
        DrawRoundRect,
        DrawCircle,
        DrawOval,
        DrawArc,
        DrawPath,
        DrawLines,
        DrawPoints,
        DrawText,
        ResetShader,
        SetupShader,
        ResetColorFilter,
        SetupColorFilter,
        ResetShadow,
        SetupShadow,
        DrawGLFunction,
    };

    static const char* OP_NAMES[];

    void initFromDisplayListRenderer(const DisplayListRenderer& recorder, bool reusing = false);

    ANDROID_API size_t getSize();

    bool replay(OpenGLRenderer& renderer, Rect& dirty, uint32_t level = 0);

    void output(OpenGLRenderer& renderer, uint32_t level = 0);

    ANDROID_API static void outputLogBuffer(int fd);

    void setRenderable(bool renderable) {
        mIsRenderable = renderable;
    }

    bool isRenderable() const {
        return mIsRenderable;
    }

private:
    void init();

    void clearResources();

    class TextContainer {
    public:
        size_t length() const {
            return mByteLength;
        }

        const char* text() const {
            return (const char*) mText;
        }

        size_t mByteLength;
        const char* mText;
    };

    SkBitmap* getBitmap() {
        return (SkBitmap*) getInt();
    }

    SkiaShader* getShader() {
        return (SkiaShader*) getInt();
    }

    SkiaColorFilter* getColorFilter() {
        return (SkiaColorFilter*) getInt();
    }

    inline int getIndex() {
        return mReader.readInt();
    }

    inline int getInt() {
        return mReader.readInt();
    }

    inline uint32_t getUInt() {
        return mReader.readU32();
    }

    SkMatrix* getMatrix() {
        return (SkMatrix*) getInt();
    }

    SkPath* getPath() {
        return (SkPath*) getInt();
    }

    SkPaint* getPaint() {
        return (SkPaint*) getInt();
    }

    DisplayList* getDisplayList() {
        return (DisplayList*) getInt();
    }

    inline float getFloat() {
        return mReader.readScalar();
    }

    int32_t* getInts(uint32_t& count) {
        count = getInt();
        return (int32_t*) mReader.skip(count * sizeof(int32_t));
    }

    uint32_t* getUInts(int8_t& count) {
        count = getInt();
        return (uint32_t*) mReader.skip(count * sizeof(uint32_t));
    }

    float* getFloats(int& count) {
        count = getInt();
        return (float*) mReader.skip(count * sizeof(float));
    }

    void getText(TextContainer* text) {
        size_t length = text->mByteLength = getInt();
        text->mText = (const char*) mReader.skip(length);
    }

    Vector<SkBitmap*> mBitmapResources;
    Vector<SkiaColorFilter*> mFilterResources;

    Vector<SkPaint*> mPaints;
    Vector<SkPath*> mPaths;
    Vector<SkMatrix*> mMatrices;
    Vector<SkiaShader*> mShaders;

    mutable SkFlattenableReadBuffer mReader;

    size_t mSize;

    bool mIsRenderable;
};

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

/**
 * Records drawing commands in a display list for latter playback.
 */
class DisplayListRenderer: public OpenGLRenderer {
public:
    ANDROID_API DisplayListRenderer();
    virtual ~DisplayListRenderer();

    ANDROID_API DisplayList* getDisplayList(DisplayList* displayList);

    virtual void setViewport(int width, int height);
    virtual void prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void finish();

    virtual bool callDrawGLFunction(Functor *functor, Rect& dirty);

    virtual void interrupt();
    virtual void resume();

    virtual int save(int flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);

    virtual int saveLayer(float left, float top, float right, float bottom,
            SkPaint* p, int flags);
    virtual int saveLayerAlpha(float left, float top, float right, float bottom,
                int alpha, int flags);

    virtual void translate(float dx, float dy);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);

    virtual void setMatrix(SkMatrix* matrix);
    virtual void concatMatrix(SkMatrix* matrix);

    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);

    virtual bool drawDisplayList(DisplayList* displayList, uint32_t width, uint32_t height,
            Rect& dirty, uint32_t level = 0);
    virtual void drawLayer(Layer* layer, float x, float y, SkPaint* paint);
    virtual void drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint);
    virtual void drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint);
    virtual void drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, SkPaint* paint);
    virtual void drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
            float* vertices, int* colors, SkPaint* paint);
    virtual void drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
            const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
            float left, float top, float right, float bottom, SkPaint* paint);
    virtual void drawColor(int color, SkXfermode::Mode mode);
    virtual void drawRect(float left, float top, float right, float bottom, SkPaint* paint);
    virtual void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, SkPaint* paint);
    virtual void drawCircle(float x, float y, float radius, SkPaint* paint);
    virtual void drawOval(float left, float top, float right, float bottom, SkPaint* paint);
    virtual void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, SkPaint* paint);
    virtual void drawPath(SkPath* path, SkPaint* paint);
    virtual void drawLines(float* points, int count, SkPaint* paint);
    virtual void drawPoints(float* points, int count, SkPaint* paint);
    virtual void drawText(const char* text, int bytesCount, int count, float x, float y,
            SkPaint* paint, float length);

    virtual void resetShader();
    virtual void setupShader(SkiaShader* shader);

    virtual void resetColorFilter();
    virtual void setupColorFilter(SkiaColorFilter* filter);

    virtual void resetShadow();
    virtual void setupShadow(float radius, float dx, float dy, int color);

    ANDROID_API void reset();

    const SkWriter32& writeStream() const {
        return mWriter;
    }

    const Vector<SkBitmap*>& getBitmapResources() const {
        return mBitmapResources;
    }

    const Vector<SkiaColorFilter*>& getFilterResources() const {
        return mFilterResources;
    }

    const Vector<SkiaShader*>& getShaders() const {
        return mShaders;
    }

    const Vector<SkPaint*>& getPaints() const {
        return mPaints;
    }

    const Vector<SkPath*>& getPaths() const {
        return mPaths;
    }

    const Vector<SkMatrix*>& getMatrices() const {
        return mMatrices;
    }

private:
    void insertRestoreToCount() {
        if (mRestoreSaveCount >= 0) {
            mWriter.writeInt(DisplayList::RestoreToCount);
            addInt(mRestoreSaveCount);
            mRestoreSaveCount = -1;
        }
    }

    inline void addOp(DisplayList::Op drawOp) {
        insertRestoreToCount();
        mWriter.writeInt(drawOp);
        mHasDrawOps = mHasDrawOps || drawOp >= DisplayList::DrawDisplayList;
    }

    inline void addInt(int value) {
        mWriter.writeInt(value);
    }

    inline void addSize(uint32_t w, uint32_t h) {
        mWriter.writeInt(w);
        mWriter.writeInt(h);
    }

    void addInts(const int32_t* values, uint32_t count) {
        mWriter.writeInt(count);
        for (uint32_t i = 0; i < count; i++) {
            mWriter.writeInt(values[i]);
        }
    }

    void addUInts(const uint32_t* values, int8_t count) {
        mWriter.writeInt(count);
        for (int8_t i = 0; i < count; i++) {
            mWriter.writeInt(values[i]);
        }
    }

    inline void addFloat(float value) {
        mWriter.writeScalar(value);
    }

    void addFloats(const float* values, int count) {
        mWriter.writeInt(count);
        for (int i = 0; i < count; i++) {
            mWriter.writeScalar(values[i]);
        }
    }

    inline void addPoint(float x, float y) {
        mWriter.writeScalar(x);
        mWriter.writeScalar(y);
    }

    inline void addBounds(float left, float top, float right, float bottom) {
        mWriter.writeScalar(left);
        mWriter.writeScalar(top);
        mWriter.writeScalar(right);
        mWriter.writeScalar(bottom);
    }

    inline void addText(const void* text, size_t byteLength) {
        mWriter.writeInt(byteLength);
        mWriter.writePad(text, byteLength);
    }

    inline void addPath(SkPath* path) {
        if (!path) {
            addInt((int) NULL);
            return;
        }

        SkPath* pathCopy = mPathMap.valueFor(path);
        if (pathCopy == NULL || pathCopy->getGenerationID() != path->getGenerationID()) {
            pathCopy = new SkPath(*path);
            mPathMap.add(path, pathCopy);
            mPaths.add(pathCopy);
        }

        addInt((int) pathCopy);
    }

    inline void addPaint(SkPaint* paint) {
        if (!paint) {
            addInt((int) NULL);
            return;
        }

        SkPaint* paintCopy =  mPaintMap.valueFor(paint);
        if (paintCopy == NULL || paintCopy->getGenerationID() != paint->getGenerationID()) {
            paintCopy = new SkPaint(*paint);
            mPaintMap.add(paint, paintCopy);
            mPaints.add(paintCopy);
        }

        addInt((int) paintCopy);
    }

    inline void addDisplayList(DisplayList* displayList) {
        // TODO: To be safe, the display list should be ref-counted in the
        //       resources cache, but we rely on the caller (UI toolkit) to
        //       do the right thing for now
        addInt((int) displayList);
    }

    inline void addMatrix(SkMatrix* matrix) {
        // Copying the matrix is cheap and prevents against the user changing the original
        // matrix before the operation that uses it
        SkMatrix* copy = new SkMatrix(*matrix);
        addInt((int) copy);
        mMatrices.add(copy);
    }

    inline void addBitmap(SkBitmap* bitmap) {
        // Note that this assumes the bitmap is immutable. There are cases this won't handle
        // correctly, such as creating the bitmap from scratch, drawing with it, changing its
        // contents, and drawing again. The only fix would be to always copy it the first time,
        // which doesn't seem worth the extra cycles for this unlikely case.
        addInt((int) bitmap);
        mBitmapResources.add(bitmap);
        Caches::getInstance().resourceCache.incrementRefcount(bitmap);
    }

    inline void addShader(SkiaShader* shader) {
        if (!shader) {
            addInt((int) NULL);
            return;
        }

        SkiaShader* shaderCopy = mShaderMap.valueFor(shader);
        // TODO: We also need to handle generation ID changes in compose shaders
        if (shaderCopy == NULL || shaderCopy->getGenerationId() != shader->getGenerationId()) {
            shaderCopy = shader->copy();
            mShaderMap.add(shader, shaderCopy);
            mShaders.add(shaderCopy);
            Caches::getInstance().resourceCache.incrementRefcount(shaderCopy);
        }

        addInt((int) shaderCopy);
    }

    inline void addColorFilter(SkiaColorFilter* colorFilter) {
        addInt((int) colorFilter);
        mFilterResources.add(colorFilter);
        Caches::getInstance().resourceCache.incrementRefcount(colorFilter);
    }

    Vector<SkBitmap*> mBitmapResources;
    Vector<SkiaColorFilter*> mFilterResources;

    Vector<SkPaint*> mPaints;
    DefaultKeyedVector<SkPaint*, SkPaint*> mPaintMap;

    Vector<SkPath*> mPaths;
    DefaultKeyedVector<SkPath*, SkPath*> mPathMap;

    Vector<SkiaShader*> mShaders;
    DefaultKeyedVector<SkiaShader*, SkiaShader*> mShaderMap;

    Vector<SkMatrix*> mMatrices;

    SkWriter32 mWriter;

    int mRestoreSaveCount;
    bool mHasDrawOps;

    friend class DisplayList;

}; // class DisplayListRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
