/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_DISPLAY_OPERATION_H
#define ANDROID_HWUI_DISPLAY_OPERATION_H

#ifndef LOG_TAG
    #define LOG_TAG "OpenGLRenderer"
#endif

#include <SkXfermode.h>

#include <private/hwui/DrawGlInfo.h>

#include "OpenGLRenderer.h"
#include "DeferredDisplayList.h"
#include "DisplayListRenderer.h"
#include "utils/LinearAllocator.h"

#define CRASH() do { \
    *(int *)(uintptr_t)0xbbadbeef = 0; \
    ((void(*)())0)(); /* More reliable, but doesn't say BBADBEEF */ \
} while(false)

#define MATRIX_STRING "[%.2f %.2f %.2f] [%.2f %.2f %.2f] [%.2f %.2f %.2f]"
#define MATRIX_ARGS(m) \
    m->get(0), m->get(1), m->get(2), \
    m->get(3), m->get(4), m->get(5), \
    m->get(6), m->get(7), m->get(8)
#define RECT_STRING "%.2f %.2f %.2f %.2f"
#define RECT_ARGS(r) \
    r.left, r.top, r.right, r.bottom

// Use OP_LOG for logging with arglist, OP_LOGS if just printing char*
#define OP_LOGS(s) OP_LOG("%s", s)
#define OP_LOG(s, ...) ALOGD( "%*s" s, level * 2, "", __VA_ARGS__ )

namespace android {
namespace uirenderer {

/**
 * Structure for storing canvas operations when they are recorded into a DisplayList, so that they
 * may be replayed to an OpenGLRenderer.
 *
 * To avoid individual memory allocations, DisplayListOps may only be allocated into a
 * LinearAllocator's managed memory buffers.  Each pointer held by a DisplayListOp is either a
 * pointer into memory also allocated in the LinearAllocator (mostly for text and float buffers) or
 * references a externally refcounted object (Sk... and Skia... objects). ~DisplayListOp() is
 * never called as LinearAllocators are simply discarded, so no memory management should be done in
 * this class.
 */
class DisplayListOp {
public:
    // These objects should always be allocated with a LinearAllocator, and never destroyed/deleted.
    // standard new() intentionally not implemented, and delete/deconstructor should never be used.
    virtual ~DisplayListOp() { CRASH(); }
    static void operator delete(void* ptr) { CRASH(); }
    /** static void* operator new(size_t size); PURPOSELY OMITTED **/
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    enum OpLogFlag {
        kOpLogFlag_Recurse = 0x1,
        kOpLogFlag_JSON = 0x2 // TODO: add?
    };

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, int saveCount,
            uint32_t level, bool caching, int multipliedAlpha) = 0;

    // same as replay above, but draw operations will defer into the deferredList if possible
    // NOTE: colorfilters, paintfilters, shaders, shadow, and complex clips prevent deferral
    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, int saveCount,
            uint32_t level, bool caching, int multipliedAlpha,
            DeferredDisplayList& deferredList) = 0;

    virtual void output(int level, uint32_t flags = 0) = 0;

    // NOTE: it would be nice to declare constants and overriding the implementation in each op to
    // point at the constants, but that seems to require a .cpp file
    virtual const char* name() = 0;
};

class StateOp : public DisplayListOp {
public:
    StateOp() {};

    virtual ~StateOp() {}

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, int saveCount,
            uint32_t level, bool caching, int multipliedAlpha) {
        applyState(renderer, saveCount);
        return DrawGlInfo::kStatusDone;
    }

    /**
     * State operations are applied directly to the renderer, but can cause the deferred drawing op
     * list to flush
     */
    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, int saveCount,
            uint32_t level, bool caching, int multipliedAlpha, DeferredDisplayList& deferredList) {
        status_t status = DrawGlInfo::kStatusDone;
        if (requiresDrawOpFlush()) {
            // will be setting renderer state that affects ops in deferredList, so flush list first
            status |= deferredList.flush(renderer, dirty, flags, level);
        }
        applyState(renderer, saveCount);
        return status;
    }

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) = 0;

    /**
     * Returns true if it affects renderer drawing state in such a way to break deferral
     * see OpenGLRenderer::disallowDeferral()
     */
    virtual bool requiresDrawOpFlush() { return false; }
};

class DrawOp : public DisplayListOp {
public:
    DrawOp(SkPaint* paint)
            : mPaint(paint), mQuickRejected(false) {}

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, int saveCount,
            uint32_t level, bool caching, int multipliedAlpha) {
        if (mQuickRejected && CC_LIKELY(flags & DisplayList::kReplayFlag_ClipChildren)) {
            return DrawGlInfo::kStatusDone;
        }

        return applyDraw(renderer, dirty, level, caching, multipliedAlpha);
    }

    /** Draw operations are stored in the deferredList with information necessary for playback */
    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, int saveCount,
            uint32_t level, bool caching, int multipliedAlpha, DeferredDisplayList& deferredList) {
        if (mQuickRejected && CC_LIKELY(flags & DisplayList::kReplayFlag_ClipChildren)) {
            return DrawGlInfo::kStatusDone;
        }

        if (renderer.disallowDeferral()) {
            // dispatch draw immediately, since the renderer's state is too complex for deferral
            return applyDraw(renderer, dirty, level, caching, multipliedAlpha);
        }

        if (!caching) multipliedAlpha = -1;
        state.mMultipliedAlpha = multipliedAlpha;
        if (!getLocalBounds(state.mBounds)) {
            // empty bounds signify bounds can't be calculated
            state.mBounds.setEmpty();
        }

        if (!renderer.storeDisplayState(state)) {
            // op wasn't quick-rejected, so defer
            deferredList.add(this, renderer.disallowReorder());
        }

        return DrawGlInfo::kStatusDone;
    }

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) = 0;

    // returns true if bounds exist
    virtual bool getLocalBounds(Rect& localBounds) { return false; }

    // TODO: better refine localbounds usage
    void setQuickRejected(bool quickRejected) { mQuickRejected = quickRejected; }
    bool getQuickRejected() { return mQuickRejected; }

    /** Batching disabled by default, turned on for individual ops */
    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_None;
    }

    float strokeWidthOutset() { return mPaint->getStrokeWidth() * 0.5f; }

public:
    /**
     * Stores the relevant canvas state of the object between deferral and replay (if the canvas
     * state supports being stored) See OpenGLRenderer::simpleClipAndState()
     */
    DeferredDisplayState state;
protected:
    SkPaint* getPaint(OpenGLRenderer& renderer) {
        return renderer.filterPaint(mPaint);
    }

    SkPaint* mPaint; // should be accessed via getPaint() when applying
    bool mQuickRejected;
};

class DrawBoundedOp : public DrawOp {
public:
    DrawBoundedOp(float left, float top, float right, float bottom, SkPaint* paint)
            : DrawOp(paint), mLocalBounds(left, top, right, bottom) {}

    // default constructor for area, to be overridden in child constructor body
    DrawBoundedOp(SkPaint* paint)
            : DrawOp(paint) {}

    bool getLocalBounds(Rect& localBounds) {
        localBounds.set(mLocalBounds);
        return true;
    }

protected:
    Rect mLocalBounds; // displayed area in LOCAL coord. doesn't incorporate stroke, so check paint
};

///////////////////////////////////////////////////////////////////////////////
// STATE OPERATIONS - these may affect the state of the canvas/renderer, but do
//         not directly draw or alter output
///////////////////////////////////////////////////////////////////////////////

class SaveOp : public StateOp {
public:
    SaveOp(int flags)
            : mFlags(flags) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.save(mFlags);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("Save flags %x", mFlags);
    }

    virtual const char* name() { return "Save"; }

private:
    int mFlags;
};

class RestoreToCountOp : public StateOp {
public:
    RestoreToCountOp(int count)
            : mCount(count) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.restoreToCount(saveCount + mCount);

    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("Restore to count %d", mCount);
    }

    virtual const char* name() { return "RestoreToCount"; }
    // Note: don't have to return true for requiresDrawOpFlush - even though restore can create a
    // complex clip, the clip and matrix are overridden by DeferredDisplayList::flush()

private:
    int mCount;
};

class SaveLayerOp : public StateOp {
public:
    SaveLayerOp(float left, float top, float right, float bottom, SkPaint* paint, int flags)
            : mArea(left, top, right, bottom), mPaint(paint), mFlags(flags) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        SkPaint* paint = renderer.filterPaint(mPaint);
        renderer.saveLayer(mArea.left, mArea.top, mArea.right, mArea.bottom, paint, mFlags);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("SaveLayer of area " RECT_STRING, RECT_ARGS(mArea));
    }

    virtual const char* name() { return "SaveLayer"; }
    virtual bool requiresDrawOpFlush() { return true; }

private:
    Rect mArea;
    SkPaint* mPaint;
    int mFlags;
};

class SaveLayerAlphaOp : public StateOp {
public:
    SaveLayerAlphaOp(float left, float top, float right, float bottom, int alpha, int flags)
            : mArea(left, top, right, bottom), mAlpha(alpha), mFlags(flags) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.saveLayerAlpha(mArea.left, mArea.top, mArea.right, mArea.bottom, mAlpha, mFlags);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("SaveLayerAlpha of area " RECT_STRING, RECT_ARGS(mArea));
    }

    virtual const char* name() { return "SaveLayerAlpha"; }
    virtual bool requiresDrawOpFlush() { return true; }

private:
    Rect mArea;
    int mAlpha;
    int mFlags;
};

class TranslateOp : public StateOp {
public:
    TranslateOp(float dx, float dy)
            : mDx(dx), mDy(dy) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.translate(mDx, mDy);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("Translate by %f %f", mDx, mDy);
    }

    virtual const char* name() { return "Translate"; }

private:
    float mDx;
    float mDy;
};

class RotateOp : public StateOp {
public:
    RotateOp(float degrees)
            : mDegrees(degrees) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.rotate(mDegrees);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("Rotate by %f degrees", mDegrees);
    }

    virtual const char* name() { return "Rotate"; }

private:
    float mDegrees;
};

class ScaleOp : public StateOp {
public:
    ScaleOp(float sx, float sy)
            : mSx(sx), mSy(sy) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.scale(mSx, mSy);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("Scale by %f %f", mSx, mSy);
    }

    virtual const char* name() { return "Scale"; }

private:
    float mSx;
    float mSy;
};

class SkewOp : public StateOp {
public:
    SkewOp(float sx, float sy)
            : mSx(sx), mSy(sy) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.skew(mSx, mSy);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("Skew by %f %f", mSx, mSy);
    }

    virtual const char* name() { return "Skew"; }

private:
    float mSx;
    float mSy;
};

class SetMatrixOp : public StateOp {
public:
    SetMatrixOp(SkMatrix* matrix)
            : mMatrix(matrix) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.setMatrix(mMatrix);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("SetMatrix " MATRIX_STRING, MATRIX_ARGS(mMatrix));
    }

    virtual const char* name() { return "SetMatrix"; }

private:
    SkMatrix* mMatrix;
};

class ConcatMatrixOp : public StateOp {
public:
    ConcatMatrixOp(SkMatrix* matrix)
            : mMatrix(matrix) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.concatMatrix(mMatrix);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("ConcatMatrix " MATRIX_STRING, MATRIX_ARGS(mMatrix));
    }

    virtual const char* name() { return "ConcatMatrix"; }

private:
    SkMatrix* mMatrix;
};

class ClipRectOp : public StateOp {
public:
    ClipRectOp(float left, float top, float right, float bottom, SkRegion::Op op)
            : mArea(left, top, right, bottom), mOp(op) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.clipRect(mArea.left, mArea.top, mArea.right, mArea.bottom, mOp);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("ClipRect " RECT_STRING, RECT_ARGS(mArea));
    }

    virtual const char* name() { return "ClipRect"; }

private:
    Rect mArea;
    SkRegion::Op mOp;
};

class ClipPathOp : public StateOp {
public:
    ClipPathOp(SkPath* path, SkRegion::Op op)
            : mPath(path), mOp(op) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.clipPath(mPath, mOp);
    }

    virtual void output(int level, uint32_t flags = 0) {
        SkRect bounds = mPath->getBounds();
        OP_LOG("ClipPath bounds " RECT_STRING,
                bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
    }

    virtual const char* name() { return "ClipPath"; }
    virtual bool requiresDrawOpFlush() { return true; }

private:
    SkPath* mPath;
    SkRegion::Op mOp;
};

class ClipRegionOp : public StateOp {
public:
    ClipRegionOp(SkRegion* region, SkRegion::Op op)
            : mRegion(region), mOp(op) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.clipRegion(mRegion, mOp);
    }

    virtual void output(int level, uint32_t flags = 0) {
        SkIRect bounds = mRegion->getBounds();
        OP_LOG("ClipRegion bounds %d %d %d %d",
                bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
    }

    virtual const char* name() { return "ClipRegion"; }
    virtual bool requiresDrawOpFlush() { return true; }

private:
    SkRegion* mRegion;
    SkRegion::Op mOp;
};

class ResetShaderOp : public StateOp {
public:
    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.resetShader();
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOGS("ResetShader");
    }

    virtual const char* name() { return "ResetShader"; }
};

class SetupShaderOp : public StateOp {
public:
    SetupShaderOp(SkiaShader* shader)
            : mShader(shader) {}
    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.setupShader(mShader);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("SetupShader, shader %p", mShader);
    }

    virtual const char* name() { return "SetupShader"; }

private:
    SkiaShader* mShader;
};

class ResetColorFilterOp : public StateOp {
public:
    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.resetColorFilter();
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOGS("ResetColorFilter");
    }

    virtual const char* name() { return "ResetColorFilter"; }
};

class SetupColorFilterOp : public StateOp {
public:
    SetupColorFilterOp(SkiaColorFilter* colorFilter)
            : mColorFilter(colorFilter) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.setupColorFilter(mColorFilter);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("SetupColorFilter, filter %p", mColorFilter);
    }

    virtual const char* name() { return "SetupColorFilter"; }

private:
    SkiaColorFilter* mColorFilter;
};

class ResetShadowOp : public StateOp {
public:
    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.resetShadow();
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOGS("ResetShadow");
    }

    virtual const char* name() { return "ResetShadow"; }
};

class SetupShadowOp : public StateOp {
public:
    SetupShadowOp(float radius, float dx, float dy, int color)
            : mRadius(radius), mDx(dx), mDy(dy), mColor(color) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.setupShadow(mRadius, mDx, mDy, mColor);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("SetupShadow, radius %f, %f, %f, color %#x", mRadius, mDx, mDy, mColor);
    }

    virtual const char* name() { return "SetupShadow"; }

private:
    float mRadius;
    float mDx;
    float mDy;
    int mColor;
};

class ResetPaintFilterOp : public StateOp {
public:
    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.resetPaintFilter();
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOGS("ResetPaintFilter");
    }

    virtual const char* name() { return "ResetPaintFilter"; }
};

class SetupPaintFilterOp : public StateOp {
public:
    SetupPaintFilterOp(int clearBits, int setBits)
            : mClearBits(clearBits), mSetBits(setBits) {}

    virtual void applyState(OpenGLRenderer& renderer, int saveCount) {
        renderer.setupPaintFilter(mClearBits, mSetBits);
    }

    virtual void output(int level, uint32_t flags = 0) {
        OP_LOG("SetupPaintFilter, clear %#x, set %#x", mClearBits, mSetBits);
    }

    virtual const char* name() { return "SetupPaintFilter"; }

private:
    int mClearBits;
    int mSetBits;
};


///////////////////////////////////////////////////////////////////////////////
// DRAW OPERATIONS - these are operations that can draw to the canvas's device
///////////////////////////////////////////////////////////////////////////////

class DrawBitmapOp : public DrawBoundedOp {
public:
    DrawBitmapOp(SkBitmap* bitmap, float left, float top, SkPaint* paint)
            : DrawBoundedOp(left, top, left + bitmap->width(), top + bitmap->height(),
                    paint),
            mBitmap(bitmap) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        SkPaint* paint = getPaint(renderer);
        int oldAlpha = -1;
        if (caching && multipliedAlpha < 255) {
            oldAlpha = paint->getAlpha();
            paint->setAlpha(multipliedAlpha);
        }
        status_t ret = renderer.drawBitmap(mBitmap, mLocalBounds.left, mLocalBounds.top, paint);
        if (oldAlpha >= 0) {
            paint->setAlpha(oldAlpha);
        }
        return ret;
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw bitmap %p at %f %f", mBitmap, mLocalBounds.left, mLocalBounds.top);
    }

    virtual const char* name() { return "DrawBitmap"; }
    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_Bitmap;
    }

protected:
    SkBitmap* mBitmap;
};

class DrawBitmapMatrixOp : public DrawBoundedOp {
public:
    DrawBitmapMatrixOp(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint)
            : DrawBoundedOp(paint), mBitmap(bitmap), mMatrix(matrix) {
        mLocalBounds.set(0, 0, bitmap->width(), bitmap->height());
        const mat4 transform(*matrix);
        transform.mapRect(mLocalBounds);
    }

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawBitmap(mBitmap, mMatrix, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw bitmap %p matrix " MATRIX_STRING, mBitmap, MATRIX_ARGS(mMatrix));
    }

    virtual const char* name() { return "DrawBitmap"; }
    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_Bitmap;
    }

private:
    SkBitmap* mBitmap;
    SkMatrix* mMatrix;
};

class DrawBitmapRectOp : public DrawBoundedOp {
public:
    DrawBitmapRectOp(SkBitmap* bitmap, float srcLeft, float srcTop, float srcRight, float srcBottom,
            float dstLeft, float dstTop, float dstRight, float dstBottom, SkPaint* paint)
            : DrawBoundedOp(dstLeft, dstTop, dstRight, dstBottom, paint),
            mBitmap(bitmap), mSrc(srcLeft, srcTop, srcRight, srcBottom) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawBitmap(mBitmap, mSrc.left, mSrc.top, mSrc.right, mSrc.bottom,
                mLocalBounds.left, mLocalBounds.top, mLocalBounds.right, mLocalBounds.bottom,
                getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw bitmap %p src="RECT_STRING", dst="RECT_STRING,
                mBitmap, RECT_ARGS(mSrc), RECT_ARGS(mLocalBounds));
    }

    virtual const char* name() { return "DrawBitmapRect"; }
    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_Bitmap;
    }

private:
    SkBitmap* mBitmap;
    Rect mSrc;
};

class DrawBitmapDataOp : public DrawBitmapOp {
public:
    DrawBitmapDataOp(SkBitmap* bitmap, float left, float top, SkPaint* paint)
            : DrawBitmapOp(bitmap, left, top, paint) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawBitmapData(mBitmap, mLocalBounds.left,
                mLocalBounds.top, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw bitmap %p", mBitmap);
    }

    virtual const char* name() { return "DrawBitmapData"; }
    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_Bitmap;
    }
};

class DrawBitmapMeshOp : public DrawOp {
public:
    DrawBitmapMeshOp(SkBitmap* bitmap, int meshWidth, int meshHeight,
            float* vertices, int* colors, SkPaint* paint)
            : DrawOp(paint), mBitmap(bitmap), mMeshWidth(meshWidth), mMeshHeight(meshHeight),
            mVertices(vertices), mColors(colors) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawBitmapMesh(mBitmap, mMeshWidth, mMeshHeight,
                mVertices, mColors, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw bitmap %p mesh %d x %d", mBitmap, mMeshWidth, mMeshHeight);
    }

    virtual const char* name() { return "DrawBitmapMesh"; }
    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_Bitmap;
    }

private:
    SkBitmap* mBitmap;
    int mMeshWidth;
    int mMeshHeight;
    float* mVertices;
    int* mColors;
};

class DrawPatchOp : public DrawBoundedOp {
public:
    DrawPatchOp(SkBitmap* bitmap, const int32_t* xDivs,
            const int32_t* yDivs, const uint32_t* colors, uint32_t width, uint32_t height,
            int8_t numColors, float left, float top, float right, float bottom,
            int alpha, SkXfermode::Mode mode)
            : DrawBoundedOp(left, top, right, bottom, 0),
            mBitmap(bitmap), mxDivs(xDivs), myDivs(yDivs),
            mColors(colors), mxDivsCount(width), myDivsCount(height),
            mNumColors(numColors), mAlpha(alpha), mMode(mode) {};

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        // NOTE: not calling the virtual method, which takes a paint
        return renderer.drawPatch(mBitmap, mxDivs, myDivs, mColors,
                mxDivsCount, myDivsCount, mNumColors,
                mLocalBounds.left, mLocalBounds.top,
                mLocalBounds.right, mLocalBounds.bottom, mAlpha, mMode);
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw patch "RECT_STRING, RECT_ARGS(mLocalBounds));
    }

    virtual const char* name() { return "DrawPatch"; }
    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_Patch;
    }

private:
    SkBitmap* mBitmap;
    const int32_t* mxDivs;
    const int32_t* myDivs;
    const uint32_t* mColors;
    uint32_t mxDivsCount;
    uint32_t myDivsCount;
    int8_t mNumColors;
    int mAlpha;
    SkXfermode::Mode mMode;
};

class DrawColorOp : public DrawOp {
public:
    DrawColorOp(int color, SkXfermode::Mode mode)
            : DrawOp(0), mColor(color), mMode(mode) {};

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawColor(mColor, mMode);
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw color %#x, mode %d", mColor, mMode);
    }

    virtual const char* name() { return "DrawColor"; }

private:
    int mColor;
    SkXfermode::Mode mMode;
};

class DrawStrokableOp : public DrawBoundedOp {
public:
    DrawStrokableOp(float left, float top, float right, float bottom, SkPaint* paint)
            : DrawBoundedOp(left, top, right, bottom, paint) {};

    bool getLocalBounds(Rect& localBounds) {
        localBounds.set(mLocalBounds);
        if (mPaint && mPaint->getStyle() != SkPaint::kFill_Style) {
            localBounds.outset(strokeWidthOutset());
        }
        return true;
    }

    virtual DeferredDisplayList::OpBatchId getBatchId() {
        if (mPaint->getPathEffect()) {
            return DeferredDisplayList::kOpBatch_AlphaMaskTexture;
        }
        return mPaint->isAntiAlias() ?
                DeferredDisplayList::kOpBatch_AlphaVertices :
                DeferredDisplayList::kOpBatch_Vertices;
    }
};

class DrawRectOp : public DrawStrokableOp {
public:
    DrawRectOp(float left, float top, float right, float bottom, SkPaint* paint)
            : DrawStrokableOp(left, top, right, bottom, paint) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawRect(mLocalBounds.left, mLocalBounds.top,
                mLocalBounds.right, mLocalBounds.bottom, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Rect "RECT_STRING, RECT_ARGS(mLocalBounds));
    }

    virtual const char* name() { return "DrawRect"; }
};

class DrawRectsOp : public DrawOp {
public:
    DrawRectsOp(const float* rects, int count, SkPaint* paint)
            : DrawOp(paint), mRects(rects), mCount(count) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawRects(mRects, mCount, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Rects count %d", mCount);
    }

    virtual const char* name() { return "DrawRects"; }

    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_Vertices;
    }

private:
    const float* mRects;
    int mCount;
};

class DrawRoundRectOp : public DrawStrokableOp {
public:
    DrawRoundRectOp(float left, float top, float right, float bottom,
            float rx, float ry, SkPaint* paint)
            : DrawStrokableOp(left, top, right, bottom, paint), mRx(rx), mRy(ry) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawRoundRect(mLocalBounds.left, mLocalBounds.top,
                mLocalBounds.right, mLocalBounds.bottom, mRx, mRy, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw RoundRect "RECT_STRING", rx %f, ry %f", RECT_ARGS(mLocalBounds), mRx, mRy);
    }

    virtual const char* name() { return "DrawRoundRect"; }

private:
    float mRx;
    float mRy;
};

class DrawCircleOp : public DrawStrokableOp {
public:
    DrawCircleOp(float x, float y, float radius, SkPaint* paint)
            : DrawStrokableOp(x - radius, y - radius, x + radius, y + radius, paint),
            mX(x), mY(y), mRadius(radius) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawCircle(mX, mY, mRadius, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Circle x %f, y %f, r %f", mX, mY, mRadius);
    }

    virtual const char* name() { return "DrawCircle"; }

private:
    float mX;
    float mY;
    float mRadius;
};

class DrawOvalOp : public DrawStrokableOp {
public:
    DrawOvalOp(float left, float top, float right, float bottom, SkPaint* paint)
            : DrawStrokableOp(left, top, right, bottom, paint) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawOval(mLocalBounds.left, mLocalBounds.top,
                mLocalBounds.right, mLocalBounds.bottom, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Oval "RECT_STRING, RECT_ARGS(mLocalBounds));
    }

    virtual const char* name() { return "DrawOval"; }
};

class DrawArcOp : public DrawStrokableOp {
public:
    DrawArcOp(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, SkPaint* paint)
            : DrawStrokableOp(left, top, right, bottom, paint),
            mStartAngle(startAngle), mSweepAngle(sweepAngle), mUseCenter(useCenter) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawArc(mLocalBounds.left, mLocalBounds.top,
                mLocalBounds.right, mLocalBounds.bottom,
                mStartAngle, mSweepAngle, mUseCenter, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Arc "RECT_STRING", start %f, sweep %f, useCenter %d",
                RECT_ARGS(mLocalBounds), mStartAngle, mSweepAngle, mUseCenter);
    }

    virtual const char* name() { return "DrawArc"; }

private:
    float mStartAngle;
    float mSweepAngle;
    bool mUseCenter;
};

class DrawPathOp : public DrawBoundedOp {
public:
    DrawPathOp(SkPath* path, SkPaint* paint)
            : DrawBoundedOp(paint), mPath(path) {
        float left, top, offset;
        uint32_t width, height;
        computePathBounds(path, paint, left, top, offset, width, height);
        left -= offset;
        top -= offset;
        mLocalBounds.set(left, top, left + width, top + height);
    }

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawPath(mPath, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Path %p in "RECT_STRING, mPath, RECT_ARGS(mLocalBounds));
    }

    virtual const char* name() { return "DrawPath"; }

    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return DeferredDisplayList::kOpBatch_AlphaMaskTexture;
    }
private:
    SkPath* mPath;
};

class DrawLinesOp : public DrawBoundedOp {
public:
    DrawLinesOp(float* points, int count, SkPaint* paint)
            : DrawBoundedOp(paint), mPoints(points), mCount(count) {
        for (int i = 0; i < count; i += 2) {
            mLocalBounds.left = fminf(mLocalBounds.left, points[i]);
            mLocalBounds.right = fmaxf(mLocalBounds.right, points[i]);
            mLocalBounds.top = fminf(mLocalBounds.top, points[i+1]);
            mLocalBounds.bottom = fmaxf(mLocalBounds.bottom, points[i+1]);
        }
        mLocalBounds.outset(strokeWidthOutset());
    }

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawLines(mPoints, mCount, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Lines count %d", mCount);
    }

    virtual const char* name() { return "DrawLines"; }

    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return mPaint->isAntiAlias() ?
                DeferredDisplayList::kOpBatch_AlphaVertices :
                DeferredDisplayList::kOpBatch_Vertices;
    }

protected:
    float* mPoints;
    int mCount;
};

class DrawPointsOp : public DrawLinesOp {
public:
    DrawPointsOp(float* points, int count, SkPaint* paint)
            : DrawLinesOp(points, count, paint) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawPoints(mPoints, mCount, getPaint(renderer));
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Points count %d", mCount);
    }

    virtual const char* name() { return "DrawPoints"; }
};

class DrawSomeTextOp : public DrawOp {
public:
    DrawSomeTextOp(const char* text, int bytesCount, int count, SkPaint* paint)
            : DrawOp(paint), mText(text), mBytesCount(bytesCount), mCount(count) {};

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw some text, %d bytes", mBytesCount);
    }

    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return mPaint->getColor() == 0xff000000 ?
                DeferredDisplayList::kOpBatch_Text :
                DeferredDisplayList::kOpBatch_ColorText;
    }
protected:
    const char* mText;
    int mBytesCount;
    int mCount;
};

class DrawTextOnPathOp : public DrawSomeTextOp {
public:
    DrawTextOnPathOp(const char* text, int bytesCount, int count,
            SkPath* path, float hOffset, float vOffset, SkPaint* paint)
            : DrawSomeTextOp(text, bytesCount, count, paint),
            mPath(path), mHOffset(hOffset), mVOffset(vOffset) {
        /* TODO: inherit from DrawBounded and init mLocalBounds */
    }

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawTextOnPath(mText, mBytesCount, mCount, mPath,
                mHOffset, mVOffset, getPaint(renderer));
    }

    virtual const char* name() { return "DrawTextOnPath"; }

private:
    SkPath* mPath;
    float mHOffset;
    float mVOffset;
};

class DrawPosTextOp : public DrawSomeTextOp {
public:
    DrawPosTextOp(const char* text, int bytesCount, int count,
            const float* positions, SkPaint* paint)
            : DrawSomeTextOp(text, bytesCount, count, paint), mPositions(positions) {
        /* TODO: inherit from DrawBounded and init mLocalBounds */
    }

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawPosText(mText, mBytesCount, mCount, mPositions, getPaint(renderer));
    }

    virtual const char* name() { return "DrawPosText"; }

private:
    const float* mPositions;
};

class DrawTextOp : public DrawBoundedOp {
public:
    DrawTextOp(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, SkPaint* paint, float length)
            : DrawBoundedOp(paint), mText(text), mBytesCount(bytesCount), mCount(count),
            mX(x), mY(y), mPositions(positions), mLength(length) {
        SkPaint::FontMetrics metrics;
        paint->getFontMetrics(&metrics, 0.0f);
        mLocalBounds.set(mX, mY + metrics.fTop, mX + length, mY + metrics.fBottom);
    }

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        return renderer.drawText(mText, mBytesCount, mCount, mX, mY,
                mPositions, getPaint(renderer), mLength);
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Text of count %d, bytes %d", mCount, mBytesCount);
    }

    virtual const char* name() { return "DrawText"; }

    virtual DeferredDisplayList::OpBatchId getBatchId() {
        return mPaint->getColor() == 0xff000000 ?
                DeferredDisplayList::kOpBatch_Text :
                DeferredDisplayList::kOpBatch_ColorText;
    }

private:
    const char* mText;
    int mBytesCount;
    int mCount;
    float mX;
    float mY;
    const float* mPositions;
    float mLength;
};

///////////////////////////////////////////////////////////////////////////////
// SPECIAL DRAW OPERATIONS
///////////////////////////////////////////////////////////////////////////////

class DrawFunctorOp : public DrawOp {
public:
    DrawFunctorOp(Functor* functor)
            : DrawOp(0), mFunctor(functor) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        renderer.startMark("GL functor");
        status_t ret = renderer.callDrawGLFunction(mFunctor, dirty);
        renderer.endMark();
        return ret;
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Functor %p", mFunctor);
    }

    virtual const char* name() { return "DrawFunctor"; }

private:
    Functor* mFunctor;
};

class DrawDisplayListOp : public DrawOp {
public:
    DrawDisplayListOp(DisplayList* displayList, int flags)
            : DrawOp(0), mDisplayList(displayList), mFlags(flags) {}

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, int saveCount,
            uint32_t level, bool caching, int multipliedAlpha, DeferredDisplayList& deferredList) {
        if (mDisplayList && mDisplayList->isRenderable()) {
            return mDisplayList->replay(renderer, dirty, mFlags, level + 1, &deferredList);
        }
        return DrawGlInfo::kStatusDone;
    }

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        if (mDisplayList && mDisplayList->isRenderable()) {
            return mDisplayList->replay(renderer, dirty, mFlags, level + 1);
        }
        return DrawGlInfo::kStatusDone;
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Display List %p, flags %#x", mDisplayList, mFlags);
        if (mDisplayList && (flags & kOpLogFlag_Recurse)) {
            mDisplayList->output(level + 1);
        }
    }

    virtual const char* name() { return "DrawDisplayList"; }

private:
    DisplayList* mDisplayList;
    int mFlags;
};

class DrawLayerOp : public DrawOp {
public:
    DrawLayerOp(Layer* layer, float x, float y, SkPaint* paint)
            : DrawOp(paint), mLayer(layer), mX(x), mY(y) {}

    virtual status_t applyDraw(OpenGLRenderer& renderer, Rect& dirty, uint32_t level,
            bool caching, int multipliedAlpha) {
        int oldAlpha = -1;

        if (caching && multipliedAlpha < 255) {
            oldAlpha = mLayer->getAlpha();
            mLayer->setAlpha(multipliedAlpha);
        }
        status_t ret = renderer.drawLayer(mLayer, mX, mY, getPaint(renderer));
        if (oldAlpha >= 0) {
            mLayer->setAlpha(oldAlpha);
        }
        return ret;
    }

    virtual void output(int level, uint32_t flags) {
        OP_LOG("Draw Layer %p at %f %f", mLayer, mX, mY);
    }

    virtual const char* name() { return "DrawLayer"; }

private:
    Layer* mLayer;
    float mX;
    float mY;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_OPERATION_H
