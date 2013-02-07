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

#include <SkCamera.h>

#include <private/hwui/DrawGlInfo.h>

#include "DisplayListLogBuffer.h"
#include "DisplayListOp.h"
#include "DisplayListRenderer.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Display list
///////////////////////////////////////////////////////////////////////////////

void DisplayList::outputLogBuffer(int fd) {
    DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
    if (logBuffer.isEmpty()) {
        return;
    }

    FILE *file = fdopen(fd, "a");

    fprintf(file, "\nRecent DisplayList operations\n");
    logBuffer.outputCommands(file);

    String8 cachesLog;
    Caches::getInstance().dumpMemoryUsage(cachesLog);
    fprintf(file, "\nCaches:\n%s", cachesLog.string());
    fprintf(file, "\n");

    fflush(file);
}

DisplayList::DisplayList(const DisplayListRenderer& recorder) :
    mTransformMatrix(NULL), mTransformCamera(NULL), mTransformMatrix3D(NULL),
    mStaticMatrix(NULL), mAnimationMatrix(NULL) {

    initFromDisplayListRenderer(recorder);
}

DisplayList::~DisplayList() {
    clearResources();
}

void DisplayList::destroyDisplayListDeferred(DisplayList* displayList) {
    if (displayList) {
        DISPLAY_LIST_LOGD("Deferring display list destruction");
        Caches::getInstance().deleteDisplayListDeferred(displayList);
    }
}

void DisplayList::clearResources() {
    mDisplayListData = NULL;
    delete mTransformMatrix;
    delete mTransformCamera;
    delete mTransformMatrix3D;
    delete mStaticMatrix;
    delete mAnimationMatrix;

    mTransformMatrix = NULL;
    mTransformCamera = NULL;
    mTransformMatrix3D = NULL;
    mStaticMatrix = NULL;
    mAnimationMatrix = NULL;

    Caches& caches = Caches::getInstance();
    caches.unregisterFunctors(mFunctorCount);
    caches.resourceCache.lock();

    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mBitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < mOwnedBitmapResources.size(); i++) {
        SkBitmap* bitmap = mOwnedBitmapResources.itemAt(i);
        caches.resourceCache.decrementRefcountLocked(bitmap);
        caches.resourceCache.destructorLocked(bitmap);
    }

    for (size_t i = 0; i < mFilterResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mFilterResources.itemAt(i));
    }

    for (size_t i = 0; i < mShaders.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mShaders.itemAt(i));
        caches.resourceCache.destructorLocked(mShaders.itemAt(i));
    }

    for (size_t i = 0; i < mSourcePaths.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mSourcePaths.itemAt(i));
    }

    for (size_t i = 0; i < mLayers.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mLayers.itemAt(i));
    }

    caches.resourceCache.unlock();

    for (size_t i = 0; i < mPaints.size(); i++) {
        delete mPaints.itemAt(i);
    }

    for (size_t i = 0; i < mRegions.size(); i++) {
        delete mRegions.itemAt(i);
    }

    for (size_t i = 0; i < mPaths.size(); i++) {
        SkPath* path = mPaths.itemAt(i);
        caches.pathCache.remove(path);
        delete path;
    }

    for (size_t i = 0; i < mMatrices.size(); i++) {
        delete mMatrices.itemAt(i);
    }

    mBitmapResources.clear();
    mOwnedBitmapResources.clear();
    mFilterResources.clear();
    mShaders.clear();
    mSourcePaths.clear();
    mPaints.clear();
    mRegions.clear();
    mPaths.clear();
    mMatrices.clear();
    mLayers.clear();
}

void DisplayList::reset() {
    clearResources();
    init();
}

void DisplayList::initFromDisplayListRenderer(const DisplayListRenderer& recorder, bool reusing) {
    if (reusing) {
        // re-using display list - clear out previous allocations
        clearResources();
    }

    init();

    mDisplayListData = recorder.getDisplayListData();
    mSize = mDisplayListData->allocator.usedSize();

    if (mSize == 0) {
        return;
    }

    mFunctorCount = recorder.getFunctorCount();

    Caches& caches = Caches::getInstance();
    caches.registerFunctors(mFunctorCount);
    caches.resourceCache.lock();

    const Vector<SkBitmap*>& bitmapResources = recorder.getBitmapResources();
    for (size_t i = 0; i < bitmapResources.size(); i++) {
        SkBitmap* resource = bitmapResources.itemAt(i);
        mBitmapResources.add(resource);
        caches.resourceCache.incrementRefcountLocked(resource);
    }

    const Vector<SkBitmap*> &ownedBitmapResources = recorder.getOwnedBitmapResources();
    for (size_t i = 0; i < ownedBitmapResources.size(); i++) {
        SkBitmap* resource = ownedBitmapResources.itemAt(i);
        mOwnedBitmapResources.add(resource);
        caches.resourceCache.incrementRefcountLocked(resource);
    }

    const Vector<SkiaColorFilter*>& filterResources = recorder.getFilterResources();
    for (size_t i = 0; i < filterResources.size(); i++) {
        SkiaColorFilter* resource = filterResources.itemAt(i);
        mFilterResources.add(resource);
        caches.resourceCache.incrementRefcountLocked(resource);
    }

    const Vector<SkiaShader*>& shaders = recorder.getShaders();
    for (size_t i = 0; i < shaders.size(); i++) {
        SkiaShader* resource = shaders.itemAt(i);
        mShaders.add(resource);
        caches.resourceCache.incrementRefcountLocked(resource);
    }

    const SortedVector<SkPath*>& sourcePaths = recorder.getSourcePaths();
    for (size_t i = 0; i < sourcePaths.size(); i++) {
        mSourcePaths.add(sourcePaths.itemAt(i));
        caches.resourceCache.incrementRefcountLocked(sourcePaths.itemAt(i));
    }

    const Vector<Layer*>& layers = recorder.getLayers();
    for (size_t i = 0; i < layers.size(); i++) {
        mLayers.add(layers.itemAt(i));
        caches.resourceCache.incrementRefcountLocked(layers.itemAt(i));
    }

    caches.resourceCache.unlock();

    mPaints.appendVector(recorder.getPaints());
    mRegions.appendVector(recorder.getRegions());
    mPaths.appendVector(recorder.getPaths());
    mMatrices.appendVector(recorder.getMatrices());
}

void DisplayList::init() {
    mSize = 0;
    mIsRenderable = true;
    mFunctorCount = 0;
    mLeft = 0;
    mTop = 0;
    mRight = 0;
    mBottom = 0;
    mClipChildren = true;
    mAlpha = 1;
    mMultipliedAlpha = 255;
    mHasOverlappingRendering = true;
    mTranslationX = 0;
    mTranslationY = 0;
    mRotation = 0;
    mRotationX = 0;
    mRotationY= 0;
    mScaleX = 1;
    mScaleY = 1;
    mPivotX = 0;
    mPivotY = 0;
    mCameraDistance = 0;
    mMatrixDirty = false;
    mMatrixFlags = 0;
    mPrevWidth = -1;
    mPrevHeight = -1;
    mWidth = 0;
    mHeight = 0;
    mPivotExplicitlySet = false;
    mCaching = false;
}

size_t DisplayList::getSize() {
    return mSize;
}

/**
 * This function is a simplified version of replay(), where we simply retrieve and log the
 * display list. This function should remain in sync with the replay() function.
 */
void DisplayList::output(uint32_t level) {
    ALOGD("%*sStart display list (%p, %s, render=%d)", level * 2, "", this,
            mName.string(), isRenderable());

    ALOGD("%*s%s %d", level * 2, "", "Save", SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    outputViewProperties(level);
    int flags = DisplayListOp::kOpLogFlag_Recurse;
    for (unsigned int i = 0; i < mDisplayListData->displayListOps.size(); i++) {
        mDisplayListData->displayListOps[i]->output(level, flags);
    }
    ALOGD("%*sDone (%p, %s)", level * 2, "", this, mName.string());
}

void DisplayList::updateMatrix() {
    if (mMatrixDirty) {
        if (!mTransformMatrix) {
            mTransformMatrix = new SkMatrix();
        }
        if (mMatrixFlags == 0 || mMatrixFlags == TRANSLATION) {
            mTransformMatrix->reset();
        } else {
            if (!mPivotExplicitlySet) {
                if (mWidth != mPrevWidth || mHeight != mPrevHeight) {
                    mPrevWidth = mWidth;
                    mPrevHeight = mHeight;
                    mPivotX = mPrevWidth / 2;
                    mPivotY = mPrevHeight / 2;
                }
            }
            if ((mMatrixFlags & ROTATION_3D) == 0) {
                mTransformMatrix->setTranslate(mTranslationX, mTranslationY);
                mTransformMatrix->preRotate(mRotation, mPivotX, mPivotY);
                mTransformMatrix->preScale(mScaleX, mScaleY, mPivotX, mPivotY);
            } else {
                if (!mTransformCamera) {
                    mTransformCamera = new Sk3DView();
                    mTransformMatrix3D = new SkMatrix();
                }
                mTransformMatrix->reset();
                mTransformCamera->save();
                mTransformMatrix->preScale(mScaleX, mScaleY, mPivotX, mPivotY);
                mTransformCamera->rotateX(mRotationX);
                mTransformCamera->rotateY(mRotationY);
                mTransformCamera->rotateZ(-mRotation);
                mTransformCamera->getMatrix(mTransformMatrix3D);
                mTransformMatrix3D->preTranslate(-mPivotX, -mPivotY);
                mTransformMatrix3D->postTranslate(mPivotX + mTranslationX,
                        mPivotY + mTranslationY);
                mTransformMatrix->postConcat(*mTransformMatrix3D);
                mTransformCamera->restore();
            }
        }
        mMatrixDirty = false;
    }
}

void DisplayList::outputViewProperties(uint32_t level) {
    updateMatrix();
    if (mLeft != 0 || mTop != 0) {
        ALOGD("%*sTranslate (left, top) %d, %d", level * 2, "", mLeft, mTop);
    }
    if (mStaticMatrix) {
        ALOGD("%*sConcatMatrix (static) %p: " MATRIX_STRING,
                level * 2, "", mStaticMatrix, MATRIX_ARGS(mStaticMatrix));
    }
    if (mAnimationMatrix) {
        ALOGD("%*sConcatMatrix (animation) %p: " MATRIX_STRING,
                level * 2, "", mAnimationMatrix, MATRIX_ARGS(mStaticMatrix));
    }
    if (mMatrixFlags != 0) {
        if (mMatrixFlags == TRANSLATION) {
            ALOGD("%*sTranslate %f, %f", level * 2, "", mTranslationX, mTranslationY);
        } else {
            ALOGD("%*sConcatMatrix %p: " MATRIX_STRING,
                    level * 2, "", mTransformMatrix, MATRIX_ARGS(mTransformMatrix));
        }
    }
    if (mAlpha < 1 && !mCaching) {
        if (!mHasOverlappingRendering) {
            ALOGD("%*sSetAlpha %.2f", level * 2, "", mAlpha);
        } else {
            int flags = SkCanvas::kHasAlphaLayer_SaveFlag;
            if (mClipChildren) {
                flags |= SkCanvas::kClipToLayer_SaveFlag;
            }
            ALOGD("%*sSaveLayerAlpha %.2f, %.2f, %.2f, %.2f, %d, 0x%x", level * 2, "",
                    (float) 0, (float) 0, (float) mRight - mLeft, (float) mBottom - mTop,
                    mMultipliedAlpha, flags);
        }
    }
    if (mClipChildren && !mCaching) {
        ALOGD("%*sClipRect %.2f, %.2f, %.2f, %.2f", level * 2, "", 0.0f, 0.0f,
                (float) mRight - mLeft, (float) mBottom - mTop);
    }
}

void DisplayList::setViewProperties(OpenGLRenderer& renderer, uint32_t level) {
#if DEBUG_DISPLAYLIST
    outputViewProperties(level);
#endif
    updateMatrix();
    if (mLeft != 0 || mTop != 0) {
        renderer.translate(mLeft, mTop);
    }
    if (mStaticMatrix) {
        renderer.concatMatrix(mStaticMatrix);
    } else if (mAnimationMatrix) {
        renderer.concatMatrix(mAnimationMatrix);
    }
    if (mMatrixFlags != 0) {
        if (mMatrixFlags == TRANSLATION) {
            renderer.translate(mTranslationX, mTranslationY);
        } else {
            renderer.concatMatrix(mTransformMatrix);
        }
    }
    if (mAlpha < 1 && !mCaching) {
        if (!mHasOverlappingRendering) {
            renderer.setAlpha(mAlpha);
        } else {
            // TODO: should be able to store the size of a DL at record time and not
            // have to pass it into this call. In fact, this information might be in the
            // location/size info that we store with the new native transform data.
            int flags = SkCanvas::kHasAlphaLayer_SaveFlag;
            if (mClipChildren) {
                flags |= SkCanvas::kClipToLayer_SaveFlag;
            }
            renderer.saveLayerAlpha(0, 0, mRight - mLeft, mBottom - mTop,
                    mMultipliedAlpha, flags);
        }
    }
    if (mClipChildren && !mCaching) {
        renderer.clipRect(0, 0, mRight - mLeft, mBottom - mTop,
                SkRegion::kIntersect_Op);
    }
}

status_t DisplayList::replay(OpenGLRenderer& renderer, Rect& dirty, int32_t flags, uint32_t level) {
    status_t drawGlStatus = DrawGlInfo::kStatusDone;

#if DEBUG_DISPLAY_LIST
    Rect* clipRect = renderer.getClipRect();
    DISPLAY_LIST_LOGD("%*sStart display list (%p, %s), clipRect: %.0f, %.f, %.0f, %.0f",
            (level+1)*2, "", this, mName.string(), clipRect->left, clipRect->top,
            clipRect->right, clipRect->bottom);
#endif

    renderer.startMark(mName.string());

    int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    DISPLAY_LIST_LOGD("%*sSave %d %d", level * 2, "",
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag, restoreTo);
    setViewProperties(renderer, level);

    if (renderer.quickRejectNoScissor(0, 0, mWidth, mHeight)) {
        DISPLAY_LIST_LOGD("%*sRestoreToCount %d", level * 2, "", restoreTo);
        renderer.restoreToCount(restoreTo);
        renderer.endMark();
        return drawGlStatus;
    }

    DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
    int saveCount = renderer.getSaveCount() - 1;
    for (unsigned int i = 0; i < mDisplayListData->displayListOps.size(); i++) {
        DisplayListOp *op = mDisplayListData->displayListOps[i];
#if DEBUG_DISPLAY_LIST_OPS_AS_EVENTS
        Caches::getInstance().eventMark(strlen(op->name()), op->name());
#endif

        drawGlStatus |= op->replay(renderer, dirty, flags,
                saveCount, level, mCaching, mMultipliedAlpha);
        logBuffer.writeCommand(level, op->name());
    }

    DISPLAY_LIST_LOGD("%*sRestoreToCount %d", level * 2, "", restoreTo);
    renderer.restoreToCount(restoreTo);
    renderer.endMark();

    DISPLAY_LIST_LOGD("%*sDone (%p, %s), returning %d", (level + 1) * 2, "", this, mName.string(),
            drawGlStatus);
    return drawGlStatus;
}

///////////////////////////////////////////////////////////////////////////////
// Base structure
///////////////////////////////////////////////////////////////////////////////

DisplayListRenderer::DisplayListRenderer():
        mCaches(Caches::getInstance()), mDisplayListData(new DisplayListData),
        mTranslateX(0.0f), mTranslateY(0.0f), mHasTranslate(false),
        mHasDrawOps(false), mFunctorCount(0) {
}

DisplayListRenderer::~DisplayListRenderer() {
    reset();
}

void DisplayListRenderer::reset() {
    mDisplayListData = new DisplayListData();
    mCaches.resourceCache.lock();

    for (size_t i = 0; i < mBitmapResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mBitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < mOwnedBitmapResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mOwnedBitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < mFilterResources.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mFilterResources.itemAt(i));
    }

    for (size_t i = 0; i < mShaders.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mShaders.itemAt(i));
    }

    for (size_t i = 0; i < mSourcePaths.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mSourcePaths.itemAt(i));
    }

    for (size_t i = 0; i < mLayers.size(); i++) {
        mCaches.resourceCache.decrementRefcountLocked(mLayers.itemAt(i));
    }

    mCaches.resourceCache.unlock();

    mBitmapResources.clear();
    mOwnedBitmapResources.clear();
    mFilterResources.clear();
    mSourcePaths.clear();

    mShaders.clear();
    mShaderMap.clear();

    mPaints.clear();
    mPaintMap.clear();

    mRegions.clear();
    mRegionMap.clear();

    mPaths.clear();
    mPathMap.clear();

    mMatrices.clear();

    mLayers.clear();

    mHasDrawOps = false;
    mFunctorCount = 0;
}

///////////////////////////////////////////////////////////////////////////////
// Operations
///////////////////////////////////////////////////////////////////////////////

DisplayList* DisplayListRenderer::getDisplayList(DisplayList* displayList) {
    if (!displayList) {
        displayList = new DisplayList(*this);
    } else {
        displayList->initFromDisplayListRenderer(*this, true);
    }
    displayList->setRenderable(mHasDrawOps);
    return displayList;
}

bool DisplayListRenderer::isDeferred() {
    return true;
}

void DisplayListRenderer::setViewport(int width, int height) {
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;
}

status_t DisplayListRenderer::prepareDirty(float left, float top,
        float right, float bottom, bool opaque) {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSaveCount = 1;

    mSnapshot->setClip(0.0f, 0.0f, mWidth, mHeight);
    mDirtyClip = opaque;

    mRestoreSaveCount = -1;

    return DrawGlInfo::kStatusDone; // No invalidate needed at record-time
}

void DisplayListRenderer::finish() {
    insertRestoreToCount();
    insertTranslate();
}

void DisplayListRenderer::interrupt() {
}

void DisplayListRenderer::resume() {
}

status_t DisplayListRenderer::callDrawGLFunction(Functor *functor, Rect& dirty) {
    // Ignore dirty during recording, it matters only when we replay
    addDrawOp(new (alloc()) DrawFunctorOp(functor));
    mFunctorCount++;
    return DrawGlInfo::kStatusDone; // No invalidate needed at record-time
}

int DisplayListRenderer::save(int flags) {
    addStateOp(new (alloc()) SaveOp(flags));
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::restore() {
    if (mRestoreSaveCount < 0) {
        restoreToCount(getSaveCount() - 1);
        return;
    }

    mRestoreSaveCount--;
    insertTranslate();
    OpenGLRenderer::restore();
}

void DisplayListRenderer::restoreToCount(int saveCount) {
    mRestoreSaveCount = saveCount;
    insertTranslate();
    OpenGLRenderer::restoreToCount(saveCount);
}

int DisplayListRenderer::saveLayer(float left, float top, float right, float bottom,
        SkPaint* p, int flags) {
    addStateOp(new (alloc()) SaveLayerOp(left, top, right, bottom, p, flags));
    return OpenGLRenderer::save(flags);
}

int DisplayListRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    addStateOp(new (alloc()) SaveLayerAlphaOp(left, top, right, bottom, alpha, flags));
    return OpenGLRenderer::save(flags);
}

void DisplayListRenderer::translate(float dx, float dy) {
    mHasTranslate = true;
    mTranslateX += dx;
    mTranslateY += dy;
    insertRestoreToCount();
    OpenGLRenderer::translate(dx, dy);
}

void DisplayListRenderer::rotate(float degrees) {
    addStateOp(new (alloc()) RotateOp(degrees));
    OpenGLRenderer::rotate(degrees);
}

void DisplayListRenderer::scale(float sx, float sy) {
    addStateOp(new (alloc()) ScaleOp(sx, sy));
    OpenGLRenderer::scale(sx, sy);
}

void DisplayListRenderer::skew(float sx, float sy) {
    addStateOp(new (alloc()) SkewOp(sx, sy));
    OpenGLRenderer::skew(sx, sy);
}

void DisplayListRenderer::setMatrix(SkMatrix* matrix) {
    matrix = refMatrix(matrix);
    addStateOp(new (alloc()) SetMatrixOp(matrix));
    OpenGLRenderer::setMatrix(matrix);
}

void DisplayListRenderer::concatMatrix(SkMatrix* matrix) {
    matrix = refMatrix(matrix);
    addStateOp(new (alloc()) ConcatMatrixOp(matrix));
    OpenGLRenderer::concatMatrix(matrix);
}

bool DisplayListRenderer::clipRect(float left, float top, float right, float bottom,
        SkRegion::Op op) {
    addStateOp(new (alloc()) ClipRectOp(left, top, right, bottom, op));
    return OpenGLRenderer::clipRect(left, top, right, bottom, op);
}

bool DisplayListRenderer::clipPath(SkPath* path, SkRegion::Op op) {
    path = refPath(path);
    addStateOp(new (alloc()) ClipPathOp(path, op));
    return OpenGLRenderer::clipPath(path, op);
}

bool DisplayListRenderer::clipRegion(SkRegion* region, SkRegion::Op op) {
    region = refRegion(region);
    addStateOp(new (alloc()) ClipRegionOp(region, op));
    return OpenGLRenderer::clipRegion(region, op);
}

status_t DisplayListRenderer::drawDisplayList(DisplayList* displayList,
        Rect& dirty, int32_t flags, uint32_t level) {
    // dirty is an out parameter and should not be recorded,
    // it matters only when replaying the display list

    // TODO: To be safe, the display list should be ref-counted in the
    //       resources cache, but we rely on the caller (UI toolkit) to
    //       do the right thing for now

    addDrawOp(new (alloc()) DrawDisplayListOp(displayList, flags));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawLayer(Layer* layer, float x, float y, SkPaint* paint) {
    mLayers.add(layer);
    mCaches.resourceCache.incrementRefcount(layer);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawLayerOp(layer, x, y, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapOp(bitmap, left, top, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    matrix = refMatrix(matrix);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapMatrixOp(bitmap, matrix, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
        float srcRight, float srcBottom, float dstLeft, float dstTop,
        float dstRight, float dstBottom, SkPaint* paint) {
    bitmap = refBitmap(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapRectOp(bitmap,
                    srcLeft, srcTop, srcRight, srcBottom,
                    dstLeft, dstTop, dstRight, dstBottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmapData(SkBitmap* bitmap, float left, float top,
        SkPaint* paint) {
    bitmap = refBitmapData(bitmap);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawBitmapDataOp(bitmap, left, top, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
        float* vertices, int* colors, SkPaint* paint) {
    int count = (meshWidth + 1) * (meshHeight + 1) * 2;
    bitmap = refBitmap(bitmap);
    vertices = refBuffer<float>(vertices, count);
    paint = refPaint(paint);
    colors = refBuffer<int>(colors, count);

    addDrawOp(new (alloc()) DrawBitmapMeshOp(bitmap, meshWidth, meshHeight,
                    vertices, colors, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPatch(SkBitmap* bitmap, const int32_t* xDivs,
        const int32_t* yDivs, const uint32_t* colors, uint32_t width, uint32_t height,
        int8_t numColors, float left, float top, float right, float bottom, SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    OpenGLRenderer::getAlphaAndModeDirect(paint, &alpha, &mode);

    bitmap = refBitmap(bitmap);
    xDivs = refBuffer<int>(xDivs, width);
    yDivs = refBuffer<int>(yDivs, height);
    colors = refBuffer<uint32_t>(colors, numColors);

    addDrawOp(new (alloc()) DrawPatchOp(bitmap, xDivs, yDivs, colors, width, height, numColors,
                    left, top, right, bottom, alpha, mode));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawColor(int color, SkXfermode::Mode mode) {
    addDrawOp(new (alloc()) DrawColorOp(color, mode));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRect(float left, float top, float right, float bottom,
        SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRectOp(left, top, right, bottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRoundRect(float left, float top, float right, float bottom,
        float rx, float ry, SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRoundRectOp(left, top, right, bottom, rx, ry, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawCircle(float x, float y, float radius, SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawCircleOp(x, y, radius, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawOval(float left, float top, float right, float bottom,
        SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawOvalOp(left, top, right, bottom, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawArc(float left, float top, float right, float bottom,
        float startAngle, float sweepAngle, bool useCenter, SkPaint* paint) {
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawArcOp(left, top, right, bottom,
                    startAngle, sweepAngle, useCenter, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPath(SkPath* path, SkPaint* paint) {
    path = refPath(path);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPathOp(path, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawLines(float* points, int count, SkPaint* paint) {
    points = refBuffer<float>(points, count);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawLinesOp(points, count, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPoints(float* points, int count, SkPaint* paint) {
    points = refBuffer<float>(points, count);
    paint = refPaint(paint);

    addDrawOp(new (alloc()) DrawPointsOp(points, count, paint));
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawTextOnPath(const char* text, int bytesCount, int count,
        SkPath* path, float hOffset, float vOffset, SkPaint* paint) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;

    text = refText(text, bytesCount);
    path = refPath(path);
    paint = refPaint(paint);

    DrawOp* op = new (alloc()) DrawTextOnPathOp(text, bytesCount, count, path,
            hOffset, vOffset, paint);
    if (addDrawOp(op)) {
        // precache if draw operation is visible
        FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
        fontRenderer.precache(paint, text, count, *mSnapshot->transform);
    }
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawPosText(const char* text, int bytesCount, int count,
        const float* positions, SkPaint* paint) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;

    text = refText(text, bytesCount);
    positions = refBuffer<float>(positions, count * 2);
    paint = refPaint(paint);

    DrawOp* op = new (alloc()) DrawPosTextOp(text, bytesCount, count, positions, paint);
    if (addDrawOp(op)) {
        // precache if draw operation is visible
        FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
        fontRenderer.precache(paint, text, count, *mSnapshot->transform);
    }
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, const float* positions, SkPaint* paint, float length) {
    if (!text || count <= 0) return DrawGlInfo::kStatusDone;

    if (length < 0.0f) length = paint->measureText(text, bytesCount);

    text = refText(text, bytesCount);
    positions = refBuffer<float>(positions, count * 2);
    paint = refPaint(paint);

    DrawOp* op = new (alloc()) DrawTextOp(text, bytesCount, count, x, y, positions, paint, length);
    if (addDrawOp(op)) {
        // precache if draw operation is visible
        FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
        fontRenderer.precache(paint, text, count, *mSnapshot->transform);
    }
    return DrawGlInfo::kStatusDone;
}

status_t DisplayListRenderer::drawRects(const float* rects, int count, SkPaint* paint) {
    if (count <= 0) return DrawGlInfo::kStatusDone;

    rects = refBuffer<float>(rects, count);
    paint = refPaint(paint);
    addDrawOp(new (alloc()) DrawRectsOp(rects, count, paint));
    return DrawGlInfo::kStatusDone;
}

void DisplayListRenderer::resetShader() {
    addStateOp(new (alloc()) ResetShaderOp());
}

void DisplayListRenderer::setupShader(SkiaShader* shader) {
    shader = refShader(shader);
    addStateOp(new (alloc()) SetupShaderOp(shader));
}

void DisplayListRenderer::resetColorFilter() {
    addStateOp(new (alloc()) ResetColorFilterOp());
}

void DisplayListRenderer::setupColorFilter(SkiaColorFilter* filter) {
    filter = refColorFilter(filter);
    addStateOp(new (alloc()) SetupColorFilterOp(filter));
}

void DisplayListRenderer::resetShadow() {
    addStateOp(new (alloc()) ResetShadowOp());
}

void DisplayListRenderer::setupShadow(float radius, float dx, float dy, int color) {
    addStateOp(new (alloc()) SetupShadowOp(radius, dx, dy, color));
}

void DisplayListRenderer::resetPaintFilter() {
    addStateOp(new (alloc()) ResetPaintFilterOp());
}

void DisplayListRenderer::setupPaintFilter(int clearBits, int setBits) {
    addStateOp(new (alloc()) SetupPaintFilterOp(clearBits, setBits));
}

void DisplayListRenderer::insertRestoreToCount() {
    if (mRestoreSaveCount >= 0) {
        DisplayListOp* op = new (alloc()) RestoreToCountOp(mRestoreSaveCount);
        mDisplayListData->displayListOps.add(op);
        mRestoreSaveCount = -1;
    }
}

void DisplayListRenderer::insertTranslate() {
    if (mHasTranslate) {
        if (mTranslateX != 0.0f || mTranslateY != 0.0f) {
            DisplayListOp* op = new (alloc()) TranslateOp(mTranslateX, mTranslateY);
            mDisplayListData->displayListOps.add(op);
            mTranslateX = mTranslateY = 0.0f;
        }
        mHasTranslate = false;
    }
}

void DisplayListRenderer::addStateOp(StateOp* op) {
    addOpInternal(op);
}

bool DisplayListRenderer::addDrawOp(DrawOp* op) {
    bool rejected = false;
    Rect localBounds;
    if (op->getLocalBounds(localBounds)) {
        rejected = quickRejectNoScissor(localBounds.left, localBounds.top,
                localBounds.right, localBounds.bottom);
        op->setQuickRejected(rejected);
    }
    mHasDrawOps = true;
    addOpInternal(op);
    return !rejected;
}

}; // namespace uirenderer
}; // namespace android
