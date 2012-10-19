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
#include <SkCamera.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkRefCnt.h>
#include <SkTDArray.h>
#include <SkTSearch.h>

#include <cutils/compiler.h>

#include "DisplayListLogBuffer.h"
#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define MIN_WRITER_SIZE 4096
#define OP_MAY_BE_SKIPPED_MASK 0xff000000

// Debug
#if DEBUG_DISPLAY_LIST
    #define DISPLAY_LIST_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define DISPLAY_LIST_LOGD(...)
#endif

#define TRANSLATION 0x0001
#define ROTATION    0x0002
#define ROTATION_3D 0x0004
#define SCALE       0x0008
#define PIVOT       0x0010

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
        DrawBitmapData,
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
        DrawTextOnPath,
        DrawPosText,
        DrawText,
        ResetShader,
        SetupShader,
        ResetColorFilter,
        SetupColorFilter,
        ResetShadow,
        SetupShadow,
        ResetPaintFilter,
        SetupPaintFilter,
        DrawGLFunction,
    };

    // See flags defined in DisplayList.java
    enum ReplayFlag {
        kReplayFlag_ClipChildren = 0x1
    };

    static const char* OP_NAMES[];

    void setViewProperties(OpenGLRenderer& renderer, uint32_t level);
    void outputViewProperties(OpenGLRenderer& renderer, char* indent);

    ANDROID_API size_t getSize();
    ANDROID_API static void destroyDisplayListDeferred(DisplayList* displayList);
    ANDROID_API static void outputLogBuffer(int fd);

    void initFromDisplayListRenderer(const DisplayListRenderer& recorder, bool reusing = false);

    status_t replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, uint32_t level = 0);

    void output(OpenGLRenderer& renderer, uint32_t level = 0);

    ANDROID_API void reset();

    void setRenderable(bool renderable) {
        mIsRenderable = renderable;
    }

    bool isRenderable() const {
        return mIsRenderable;
    }

    void setName(const char* name) {
        if (name) {
            mName.setTo(name);
        }
    }

    void setClipChildren(bool clipChildren) {
        mClipChildren = clipChildren;
    }

    void setStaticMatrix(SkMatrix* matrix) {
        delete mStaticMatrix;
        mStaticMatrix = new SkMatrix(*matrix);
    }

    void setAnimationMatrix(SkMatrix* matrix) {
        delete mAnimationMatrix;
        if (matrix) {
            mAnimationMatrix = new SkMatrix(*matrix);
        } else {
            mAnimationMatrix = NULL;
        }
    }

    void setAlpha(float alpha) {
        alpha = fminf(1.0f, fmaxf(0.0f, alpha));
        if (alpha != mAlpha) {
            mAlpha = alpha;
            mMultipliedAlpha = (int) (255 * alpha);
        }
    }

    void setHasOverlappingRendering(bool hasOverlappingRendering) {
        mHasOverlappingRendering = hasOverlappingRendering;
    }

    void setTranslationX(float translationX) {
        if (translationX != mTranslationX) {
            mTranslationX = translationX;
            mMatrixDirty = true;
            if (mTranslationX == 0.0f && mTranslationY == 0.0f) {
                mMatrixFlags &= ~TRANSLATION;
            } else {
                mMatrixFlags |= TRANSLATION;
            }
        }
    }

    void setTranslationY(float translationY) {
        if (translationY != mTranslationY) {
            mTranslationY = translationY;
            mMatrixDirty = true;
            if (mTranslationX == 0.0f && mTranslationY == 0.0f) {
                mMatrixFlags &= ~TRANSLATION;
            } else {
                mMatrixFlags |= TRANSLATION;
            }
        }
    }

    void setRotation(float rotation) {
        if (rotation != mRotation) {
            mRotation = rotation;
            mMatrixDirty = true;
            if (mRotation == 0.0f) {
                mMatrixFlags &= ~ROTATION;
            } else {
                mMatrixFlags |= ROTATION;
            }
        }
    }

    void setRotationX(float rotationX) {
        if (rotationX != mRotationX) {
            mRotationX = rotationX;
            mMatrixDirty = true;
            if (mRotationX == 0.0f && mRotationY == 0.0f) {
                mMatrixFlags &= ~ROTATION_3D;
            } else {
                mMatrixFlags |= ROTATION_3D;
            }
        }
    }

    void setRotationY(float rotationY) {
        if (rotationY != mRotationY) {
            mRotationY = rotationY;
            mMatrixDirty = true;
            if (mRotationX == 0.0f && mRotationY == 0.0f) {
                mMatrixFlags &= ~ROTATION_3D;
            } else {
                mMatrixFlags |= ROTATION_3D;
            }
        }
    }

    void setScaleX(float scaleX) {
        if (scaleX != mScaleX) {
            mScaleX = scaleX;
            mMatrixDirty = true;
            if (mScaleX == 1.0f && mScaleY == 1.0f) {
                mMatrixFlags &= ~SCALE;
            } else {
                mMatrixFlags |= SCALE;
            }
        }
    }

    void setScaleY(float scaleY) {
        if (scaleY != mScaleY) {
            mScaleY = scaleY;
            mMatrixDirty = true;
            if (mScaleX == 1.0f && mScaleY == 1.0f) {
                mMatrixFlags &= ~SCALE;
            } else {
                mMatrixFlags |= SCALE;
            }
        }
    }

    void setPivotX(float pivotX) {
        mPivotX = pivotX;
        mMatrixDirty = true;
        if (mPivotX == 0.0f && mPivotY == 0.0f) {
            mMatrixFlags &= ~PIVOT;
        } else {
            mMatrixFlags |= PIVOT;
        }
        mPivotExplicitlySet = true;
    }

    void setPivotY(float pivotY) {
        mPivotY = pivotY;
        mMatrixDirty = true;
        if (mPivotX == 0.0f && mPivotY == 0.0f) {
            mMatrixFlags &= ~PIVOT;
        } else {
            mMatrixFlags |= PIVOT;
        }
        mPivotExplicitlySet = true;
    }

    void setCameraDistance(float distance) {
        if (distance != mCameraDistance) {
            mCameraDistance = distance;
            mMatrixDirty = true;
            if (!mTransformCamera) {
                mTransformCamera = new Sk3DView();
                mTransformMatrix3D = new SkMatrix();
            }
            mTransformCamera->setCameraLocation(0, 0, distance);
        }
    }

    void setLeft(int left) {
        if (left != mLeft) {
            mLeft = left;
            mWidth = mRight - mLeft;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void setTop(int top) {
        if (top != mTop) {
            mTop = top;
            mHeight = mBottom - mTop;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void setRight(int right) {
        if (right != mRight) {
            mRight = right;
            mWidth = mRight - mLeft;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void setBottom(int bottom) {
        if (bottom != mBottom) {
            mBottom = bottom;
            mHeight = mBottom - mTop;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void setLeftTop(int left, int top) {
        if (left != mLeft || top != mTop) {
            mLeft = left;
            mTop = top;
            mWidth = mRight - mLeft;
            mHeight = mBottom - mTop;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void setLeftTopRightBottom(int left, int top, int right, int bottom) {
        if (left != mLeft || top != mTop || right != mRight || bottom != mBottom) {
            mLeft = left;
            mTop = top;
            mRight = right;
            mBottom = bottom;
            mWidth = mRight - mLeft;
            mHeight = mBottom - mTop;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void offsetLeftRight(int offset) {
        if (offset != 0) {
            mLeft += offset;
            mRight += offset;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void offsetTopBottom(int offset) {
        if (offset != 0) {
            mTop += offset;
            mBottom += offset;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void setCaching(bool caching) {
        mCaching = caching;
    }

    int getWidth() {
        return mWidth;
    }

    int getHeight() {
        return mHeight;
    }

private:
    void init();

    void clearResources();

    void updateMatrix();

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

    SkBitmap* getBitmapData() {
        return (SkBitmap*) getInt();
    }

    SkiaShader* getShader() {
        return (SkiaShader*) getInt();
    }

    SkiaColorFilter* getColorFilter() {
        return (SkiaColorFilter*) getInt();
    }

    inline int32_t getIndex() {
        return mReader.readInt();
    }

    inline int32_t getInt() {
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

    SkPaint* getPaint(OpenGLRenderer& renderer) {
        return renderer.filterPaint((SkPaint*) getInt());
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

    float* getFloats(int32_t& count) {
        count = getInt();
        return (float*) mReader.skip(count * sizeof(float));
    }

    void getText(TextContainer* text) {
        size_t length = text->mByteLength = getInt();
        text->mText = (const char*) mReader.skip(length);
    }

    Vector<SkBitmap*> mBitmapResources;
    Vector<SkBitmap*> mOwnedBitmapResources;
    Vector<SkiaColorFilter*> mFilterResources;

    Vector<SkPaint*> mPaints;
    Vector<SkPath*> mPaths;
    SortedVector<SkPath*> mSourcePaths;
    Vector<SkMatrix*> mMatrices;
    Vector<SkiaShader*> mShaders;
    Vector<Layer*> mLayers;

    mutable SkFlattenableReadBuffer mReader;

    size_t mSize;

    bool mIsRenderable;
    uint32_t mFunctorCount;

    String8 mName;

    // View properties
    bool mClipChildren;
    float mAlpha;
    int mMultipliedAlpha;
    bool mHasOverlappingRendering;
    float mTranslationX, mTranslationY;
    float mRotation, mRotationX, mRotationY;
    float mScaleX, mScaleY;
    float mPivotX, mPivotY;
    float mCameraDistance;
    int mLeft, mTop, mRight, mBottom;
    int mWidth, mHeight;
    int mPrevWidth, mPrevHeight;
    bool mPivotExplicitlySet;
    bool mMatrixDirty;
    bool mMatrixIsIdentity;
    uint32_t mMatrixFlags;
    SkMatrix* mTransformMatrix;
    Sk3DView* mTransformCamera;
    SkMatrix* mTransformMatrix3D;
    SkMatrix* mStaticMatrix;
    SkMatrix* mAnimationMatrix;
    bool mCaching;
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

    virtual bool isDeferred();

    virtual void setViewport(int width, int height);
    virtual status_t prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void finish();

    virtual status_t callDrawGLFunction(Functor *functor, Rect& dirty);

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

    virtual status_t drawDisplayList(DisplayList* displayList, Rect& dirty, int32_t flags,
            uint32_t level = 0);
    virtual status_t drawLayer(Layer* layer, float x, float y, SkPaint* paint);
    virtual status_t drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint);
    virtual status_t drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint);
    virtual status_t drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, SkPaint* paint);
    virtual status_t drawBitmapData(SkBitmap* bitmap, float left, float top, SkPaint* paint);
    virtual status_t drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
            float* vertices, int* colors, SkPaint* paint);
    virtual status_t drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
            const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
            float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawColor(int color, SkXfermode::Mode mode);
    virtual status_t drawRect(float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, SkPaint* paint);
    virtual status_t drawCircle(float x, float y, float radius, SkPaint* paint);
    virtual status_t drawOval(float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, SkPaint* paint);
    virtual status_t drawPath(SkPath* path, SkPaint* paint);
    virtual status_t drawLines(float* points, int count, SkPaint* paint);
    virtual status_t drawPoints(float* points, int count, SkPaint* paint);
    virtual status_t drawTextOnPath(const char* text, int bytesCount, int count, SkPath* path,
            float hOffset, float vOffset, SkPaint* paint);
    virtual status_t drawPosText(const char* text, int bytesCount, int count,
            const float* positions, SkPaint* paint);
    virtual status_t drawText(const char* text, int bytesCount, int count,
            float x, float y, const float* positions, SkPaint* paint, float length);

    virtual void resetShader();
    virtual void setupShader(SkiaShader* shader);

    virtual void resetColorFilter();
    virtual void setupColorFilter(SkiaColorFilter* filter);

    virtual void resetShadow();
    virtual void setupShadow(float radius, float dx, float dy, int color);

    virtual void resetPaintFilter();
    virtual void setupPaintFilter(int clearBits, int setBits);

    ANDROID_API void reset();

    const SkWriter32& writeStream() const {
        return mWriter;
    }

    const Vector<SkBitmap*>& getBitmapResources() const {
        return mBitmapResources;
    }

    const Vector<SkBitmap*>& getOwnedBitmapResources() const {
        return mOwnedBitmapResources;
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

    const SortedVector<SkPath*>& getSourcePaths() const {
        return mSourcePaths;
    }

    const Vector<Layer*>& getLayers() const {
        return mLayers;
    }

    const Vector<SkMatrix*>& getMatrices() const {
        return mMatrices;
    }

    uint32_t getFunctorCount() const {
        return mFunctorCount;
    }

private:
    void insertRestoreToCount() {
        if (mRestoreSaveCount >= 0) {
            mWriter.writeInt(DisplayList::RestoreToCount);
            addInt(mRestoreSaveCount);
            mRestoreSaveCount = -1;
        }
    }

    void insertTranlate() {
        if (mHasTranslate) {
            if (mTranslateX != 0.0f || mTranslateY != 0.0f) {
                mWriter.writeInt(DisplayList::Translate);
                addPoint(mTranslateX, mTranslateY);
                mTranslateX = mTranslateY = 0.0f;
            }
            mHasTranslate = false;
        }
    }

    inline void addOp(const DisplayList::Op drawOp) {
        insertRestoreToCount();
        insertTranlate();
        mWriter.writeInt(drawOp);
        mHasDrawOps = mHasDrawOps || drawOp >= DisplayList::DrawDisplayList;
    }

    uint32_t* addOp(const DisplayList::Op drawOp, const bool reject) {
        insertRestoreToCount();
        insertTranlate();
        mHasDrawOps = mHasDrawOps || drawOp >= DisplayList::DrawDisplayList;
        if (reject) {
            mWriter.writeInt(OP_MAY_BE_SKIPPED_MASK | drawOp);
            mWriter.writeInt(0xdeaddead);
            mBufferSize = mWriter.size();
            return mWriter.peek32(mBufferSize - sizeof(int32_t));
        }
        mWriter.writeInt(drawOp);
        return NULL;
    }

    inline void addSkip(uint32_t* location) {
        if (location) {
            *location = (int32_t) (mWriter.size() - mBufferSize);
        }
    }

    inline void addInt(int32_t value) {
        mWriter.writeInt(value);
    }

    inline void addSize(uint32_t w, uint32_t h) {
        mWriter.writeInt(w);
        mWriter.writeInt(h);
    }

    void addInts(const int32_t* values, uint32_t count) {
        mWriter.writeInt(count);
        mWriter.write(values, count * sizeof(int32_t));
    }

    void addUInts(const uint32_t* values, int8_t count) {
        mWriter.writeInt(count);
        mWriter.write(values, count * sizeof(uint32_t));
    }

    inline void addFloat(float value) {
        mWriter.writeScalar(value);
    }

    void addFloats(const float* values, int32_t count) {
        mWriter.writeInt(count);
        mWriter.write(values, count * sizeof(float));
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
            pathCopy->setSourcePath(path);
            // replaceValueFor() performs an add if the entry doesn't exist
            mPathMap.replaceValueFor(path, pathCopy);
            mPaths.add(pathCopy);
        }
        if (mSourcePaths.indexOf(path) < 0) {
            mCaches.resourceCache.incrementRefcount(path);
            mSourcePaths.add(path);
        }

        addInt((int) pathCopy);
    }

    inline SkPaint* addPaint(SkPaint* paint) {
        if (!paint) {
            addInt((int) NULL);
            return paint;
        }

        SkPaint* paintCopy = mPaintMap.valueFor(paint);
        if (paintCopy == NULL || paintCopy->getGenerationID() != paint->getGenerationID()) {
            paintCopy = new SkPaint(*paint);
            // replaceValueFor() performs an add if the entry doesn't exist
            mPaintMap.replaceValueFor(paint, paintCopy);
            mPaints.add(paintCopy);
        }

        addInt((int) paintCopy);

        return paintCopy;
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

    inline void addLayer(Layer* layer) {
        addInt((int) layer);
        mLayers.add(layer);
        mCaches.resourceCache.incrementRefcount(layer);
    }

    inline void addBitmap(SkBitmap* bitmap) {
        // Note that this assumes the bitmap is immutable. There are cases this won't handle
        // correctly, such as creating the bitmap from scratch, drawing with it, changing its
        // contents, and drawing again. The only fix would be to always copy it the first time,
        // which doesn't seem worth the extra cycles for this unlikely case.
        addInt((int) bitmap);
        mBitmapResources.add(bitmap);
        mCaches.resourceCache.incrementRefcount(bitmap);
    }

    void addBitmapData(SkBitmap* bitmap) {
        addInt((int) bitmap);
        mOwnedBitmapResources.add(bitmap);
        mCaches.resourceCache.incrementRefcount(bitmap);
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
            // replaceValueFor() performs an add if the entry doesn't exist
            mShaderMap.replaceValueFor(shader, shaderCopy);
            mShaders.add(shaderCopy);
            mCaches.resourceCache.incrementRefcount(shaderCopy);
        }

        addInt((int) shaderCopy);
    }

    inline void addColorFilter(SkiaColorFilter* colorFilter) {
        addInt((int) colorFilter);
        mFilterResources.add(colorFilter);
        mCaches.resourceCache.incrementRefcount(colorFilter);
    }

    Vector<SkBitmap*> mBitmapResources;
    Vector<SkBitmap*> mOwnedBitmapResources;
    Vector<SkiaColorFilter*> mFilterResources;

    Vector<SkPaint*> mPaints;
    DefaultKeyedVector<SkPaint*, SkPaint*> mPaintMap;

    Vector<SkPath*> mPaths;
    DefaultKeyedVector<SkPath*, SkPath*> mPathMap;

    SortedVector<SkPath*> mSourcePaths;

    Vector<SkiaShader*> mShaders;
    DefaultKeyedVector<SkiaShader*, SkiaShader*> mShaderMap;

    Vector<SkMatrix*> mMatrices;

    Vector<Layer*> mLayers;

    uint32_t mBufferSize;

    int mRestoreSaveCount;

    Caches& mCaches;
    SkWriter32 mWriter;

    float mTranslateX;
    float mTranslateY;
    bool mHasTranslate;
    bool mHasDrawOps;

    uint32_t mFunctorCount;

    friend class DisplayList;

}; // class DisplayListRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
