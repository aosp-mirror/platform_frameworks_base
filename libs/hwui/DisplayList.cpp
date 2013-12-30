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

#define ATRACE_TAG ATRACE_TAG_VIEW

#include <SkCanvas.h>

#include <utils/Trace.h>

#include "Debug.h"
#include "DisplayList.h"
#include "DisplayListOp.h"
#include "DisplayListLogBuffer.h"

namespace android {
namespace uirenderer {

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
    mDestroyed(false), mTransformMatrix(NULL), mTransformCamera(NULL), mTransformMatrix3D(NULL),
    mStaticMatrix(NULL), mAnimationMatrix(NULL) {

    initFromDisplayListRenderer(recorder);
}

DisplayList::~DisplayList() {
    mDestroyed = true;
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

    for (size_t i = 0; i < mPatchResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(mPatchResources.itemAt(i));
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
        delete mPaths.itemAt(i);
    }

    for (size_t i = 0; i < mMatrices.size(); i++) {
        delete mMatrices.itemAt(i);
    }

    mBitmapResources.clear();
    mOwnedBitmapResources.clear();
    mFilterResources.clear();
    mPatchResources.clear();
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

    const Vector<Res_png_9patch*>& patchResources = recorder.getPatchResources();
    for (size_t i = 0; i < patchResources.size(); i++) {
        Res_png_9patch* resource = patchResources.itemAt(i);
        mPatchResources.add(resource);
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
    mClipToBounds = true;
    mIsContainedVolume = true;
    mAlpha = 1;
    mHasOverlappingRendering = true;
    mTranslationX = 0;
    mTranslationY = 0;
    mTranslationZ = 0;
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
    ALOGD("%*sStart display list (%p, %s, render=%d)", (level - 1) * 2, "", this,
            mName.string(), isRenderable());
    ALOGD("%*s%s %d", level * 2, "", "Save",
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);

    outputViewProperties(level);
    int flags = DisplayListOp::kOpLogFlag_Recurse;
    for (unsigned int i = 0; i < mDisplayListData->displayListOps.size(); i++) {
        mDisplayListData->displayListOps[i]->output(level, flags);
    }

    ALOGD("%*sDone (%p, %s)", (level - 1) * 2, "", this, mName.string());
}

float DisplayList::getPivotX() {
    updateMatrix();
    return mPivotX;
}

float DisplayList::getPivotY() {
    updateMatrix();
    return mPivotY;
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
                    mPivotX = mPrevWidth / 2.0f;
                    mPivotY = mPrevHeight / 2.0f;
                }
            }
            if (!Caches::getInstance().propertyEnable3d && (mMatrixFlags & ROTATION_3D) == 0) {
                mTransformMatrix->setTranslate(mTranslationX, mTranslationY);
                mTransformMatrix->preRotate(mRotation, mPivotX, mPivotY);
                mTransformMatrix->preScale(mScaleX, mScaleY, mPivotX, mPivotY);
            } else {
                if (Caches::getInstance().propertyEnable3d) {
                    mTransform.loadTranslate(mPivotX + mTranslationX, mPivotY + mTranslationY,
                            mTranslationZ);
                    mTransform.rotate(mRotationX, 1, 0, 0);
                    mTransform.rotate(mRotationY, 0, 1, 0);
                    mTransform.rotate(mRotation, 0, 0, 1);
                    mTransform.scale(mScaleX, mScaleY, 1);
                    mTransform.translate(-mPivotX, -mPivotY);
                } else {
                    /* TODO: support this old transform approach, based on API level */
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
        }
        mMatrixDirty = false;
    }
}

void DisplayList::outputViewProperties(const int level) {
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
                level * 2, "", mAnimationMatrix, MATRIX_ARGS(mAnimationMatrix));
    }
    if (mMatrixFlags != 0) {
        if (mMatrixFlags == TRANSLATION) {
            ALOGD("%*sTranslate %f, %f", level * 2, "", mTranslationX, mTranslationY);
        } else {
            ALOGD("%*sConcatMatrix %p: " MATRIX_STRING,
                    level * 2, "", mTransformMatrix, MATRIX_ARGS(mTransformMatrix));
        }
    }

    bool clipToBoundsNeeded = mCaching ? false : mClipToBounds;
    if (mAlpha < 1) {
        if (mCaching) {
            ALOGD("%*sSetOverrideLayerAlpha %.2f", level * 2, "", mAlpha);
        } else if (!mHasOverlappingRendering) {
            ALOGD("%*sScaleAlpha %.2f", level * 2, "", mAlpha);
        } else {
            int flags = SkCanvas::kHasAlphaLayer_SaveFlag;
            if (clipToBoundsNeeded) {
                flags |= SkCanvas::kClipToLayer_SaveFlag;
                clipToBoundsNeeded = false; // clipping done by save layer
            }
            ALOGD("%*sSaveLayerAlpha %.2f, %.2f, %.2f, %.2f, %d, 0x%x", level * 2, "",
                    (float) 0, (float) 0, (float) mRight - mLeft, (float) mBottom - mTop,
                    (int)(mAlpha * 255), flags);
        }
    }
    if (clipToBoundsNeeded) {
        ALOGD("%*sClipRect %.2f, %.2f, %.2f, %.2f", level * 2, "", 0.0f, 0.0f,
                (float) mRight - mLeft, (float) mBottom - mTop);
    }
}

/*
 * For property operations, we pass a savecount of 0, since the operations aren't part of the
 * displaylist, and thus don't have to compensate for the record-time/playback-time discrepancy in
 * base saveCount (i.e., how RestoreToCount uses saveCount + mCount)
 */
#define PROPERTY_SAVECOUNT 0

template <class T>
void DisplayList::setViewProperties(OpenGLRenderer& renderer, T& handler,
        const int level) {
#if DEBUG_DISPLAY_LIST
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
            renderer.translate(mTranslationX, mTranslationY, mTranslationZ);
        } else {
            if (Caches::getInstance().propertyEnable3d) {
                renderer.concatMatrix(mTransform);
            } else {
                renderer.concatMatrix(mTransformMatrix);
            }
        }
    }
    bool clipToBoundsNeeded = mCaching ? false : mClipToBounds;
    if (mAlpha < 1) {
        if (mCaching) {
            renderer.setOverrideLayerAlpha(mAlpha);
        } else if (!mHasOverlappingRendering) {
            renderer.scaleAlpha(mAlpha);
        } else {
            // TODO: should be able to store the size of a DL at record time and not
            // have to pass it into this call. In fact, this information might be in the
            // location/size info that we store with the new native transform data.
            int saveFlags = SkCanvas::kHasAlphaLayer_SaveFlag;
            if (clipToBoundsNeeded) {
                saveFlags |= SkCanvas::kClipToLayer_SaveFlag;
                clipToBoundsNeeded = false; // clipping done by saveLayer
            }

            SaveLayerOp* op = new (handler.allocator()) SaveLayerOp(
                    0, 0, mRight - mLeft, mBottom - mTop,
                    mAlpha * 255, SkXfermode::kSrcOver_Mode, saveFlags);
            handler(op, PROPERTY_SAVECOUNT, mClipToBounds);
        }
    }
    if (clipToBoundsNeeded) {
        ClipRectOp* op = new (handler.allocator()) ClipRectOp(0, 0,
                mRight - mLeft, mBottom - mTop, SkRegion::kIntersect_Op);
        handler(op, PROPERTY_SAVECOUNT, mClipToBounds);
    }
}

/**
 * Apply property-based transformations to input matrix
 */
void DisplayList::applyViewPropertyTransforms(mat4& matrix) {
    if (mLeft != 0 || mTop != 0) {
        matrix.translate(mLeft, mTop);
    }
    if (mStaticMatrix) {
        mat4 stat(*mStaticMatrix);
        matrix.multiply(stat);
    } else if (mAnimationMatrix) {
        mat4 anim(*mAnimationMatrix);
        matrix.multiply(anim);
    }
    if (mMatrixFlags != 0) {
        if (mMatrixFlags == TRANSLATION) {
            matrix.translate(mTranslationX, mTranslationY, mTranslationZ);
        } else {
            if (Caches::getInstance().propertyEnable3d) {
                matrix.multiply(mTransform);
            } else {
                mat4 temp(*mTransformMatrix);
                matrix.multiply(temp);
            }
        }
    }
}

/**
 * Organizes the DisplayList hierarchy to prepare for Z-based draw order.
 *
 * This should be called before a call to defer() or drawDisplayList()
 *
 * Each DisplayList that serves as a 3d root builds its list of composited children,
 * which are flagged to not draw in the standard draw loop.
 */
void DisplayList::computeOrdering() {
    ATRACE_CALL();
    if (mDisplayListData == NULL) return;

    for (unsigned int i = 0; i < mDisplayListData->children.size(); i++) {
        DrawDisplayListOp* childOp = mDisplayListData->children[i];
        childOp->mDisplayList->computeOrderingImpl(childOp, &m3dNodes, &mat4::identity());
    }
}

void DisplayList::computeOrderingImpl(
        DrawDisplayListOp* opState,
        KeyedVector<float, Vector<DrawDisplayListOp*> >* compositedChildrenOf3dRoot,
        const mat4* transformFrom3dRoot) {

    // TODO: should avoid this calculation in most cases
    opState->mTransformFrom3dRoot.load(*transformFrom3dRoot);
    opState->mTransformFrom3dRoot.multiply(opState->mTransformFromParent);

    if (mTranslationZ != 0.0f) { // TODO: other signals, such as custom 4x4 matrix
        // composited layer, insert into current 3d root and flag for out of order draw
        opState->mSkipInOrderDraw = true;

        Vector3 pivot(mPivotX, mPivotY, 0.0f);
        mat4 totalTransform(opState->mTransformFrom3dRoot);
        applyViewPropertyTransforms(totalTransform);
        totalTransform.mapPoint3d(pivot);
        const float key = pivot.z;

        if (compositedChildrenOf3dRoot->indexOfKey(key) < 0) {
            compositedChildrenOf3dRoot->add(key, Vector<DrawDisplayListOp*>());
        }
        compositedChildrenOf3dRoot->editValueFor(key).push(opState);
    } else {
        // standard in order draw
        opState->mSkipInOrderDraw = false;
    }

    m3dNodes.clear();
    if (mIsContainedVolume) {
        // create a new 3d space for children by separating their ordering
        compositedChildrenOf3dRoot = &m3dNodes;
        transformFrom3dRoot = &mat4::identity();
    } else {
        transformFrom3dRoot = &(opState->mTransformFrom3dRoot);
    }

    if (mDisplayListData != NULL && mDisplayListData->children.size() > 0) {
        for (unsigned int i = 0; i < mDisplayListData->children.size(); i++) {
            DrawDisplayListOp* childOp = mDisplayListData->children[i];
            childOp->mDisplayList->computeOrderingImpl(childOp,
                    compositedChildrenOf3dRoot, transformFrom3dRoot);
        }
    }
}

class DeferOperationHandler {
public:
    DeferOperationHandler(DeferStateStruct& deferStruct, int level)
        : mDeferStruct(deferStruct), mLevel(level) {}
    inline void operator()(DisplayListOp* operation, int saveCount, bool clipToBounds) {
        operation->defer(mDeferStruct, saveCount, mLevel, clipToBounds);
    }
    inline LinearAllocator& allocator() { return *(mDeferStruct.mAllocator); }

private:
    DeferStateStruct& mDeferStruct;
    const int mLevel;
};

void DisplayList::defer(DeferStateStruct& deferStruct, const int level) {
    DeferOperationHandler handler(deferStruct, level);
    iterate<DeferOperationHandler>(deferStruct.mRenderer, handler, level);
}

class ReplayOperationHandler {
public:
    ReplayOperationHandler(ReplayStateStruct& replayStruct, int level)
        : mReplayStruct(replayStruct), mLevel(level) {}
    inline void operator()(DisplayListOp* operation, int saveCount, bool clipToBounds) {
#if DEBUG_DISPLAY_LIST_OPS_AS_EVENTS
        mReplayStruct.mRenderer.eventMark(operation->name());
#endif
        operation->replay(mReplayStruct, saveCount, mLevel, clipToBounds);
    }
    inline LinearAllocator& allocator() { return *(mReplayStruct.mAllocator); }

private:
    ReplayStateStruct& mReplayStruct;
    const int mLevel;
};

void DisplayList::replay(ReplayStateStruct& replayStruct, const int level) {
    ReplayOperationHandler handler(replayStruct, level);

    replayStruct.mRenderer.startMark(mName.string());
    iterate<ReplayOperationHandler>(replayStruct.mRenderer, handler, level);
    replayStruct.mRenderer.endMark();

    DISPLAY_LIST_LOGD("%*sDone (%p, %s), returning %d", level * 2, "", this, mName.string(),
            replayStruct.mDrawGlStatus);
}

template <class T>
void DisplayList::iterate3dChildren(ChildrenSelectMode mode, OpenGLRenderer& renderer,
        T& handler, const int level) {
    if (m3dNodes.size() == 0 ||
            (mode == kNegativeZChildren && m3dNodes.keyAt(0) > 0.0f) ||
            (mode == kPositiveZChildren && m3dNodes.keyAt(m3dNodes.size() - 1) < 0.0f)) {
        // nothing to draw
        return;
    }

    LinearAllocator& alloc = handler.allocator();
    ClipRectOp* op = new (alloc) ClipRectOp(0, 0, mWidth, mHeight,
            SkRegion::kIntersect_Op); // clip to 3d root bounds for now
    handler(op, PROPERTY_SAVECOUNT, mClipToBounds);
    int rootRestoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);

    for (int i = 0; i < m3dNodes.size(); i++) {
        const float zValue = m3dNodes.keyAt(i);

        if (mode == kPositiveZChildren && zValue < 0.0f) continue;
        if (mode == kNegativeZChildren && zValue > 0.0f) break;

        const Vector<DrawDisplayListOp*>& nodesAtZ = m3dNodes[i];
        for (int j = 0; j < nodesAtZ.size(); j++) {
            DrawDisplayListOp* op = nodesAtZ[j];
            if (mode == kPositiveZChildren) {
                /* draw shadow on renderer with parent matrix applied, passing in the child's total matrix
                 *
                 * TODO:
                 * -determine and pass background shape (and possibly drawable alpha)
                 * -view must opt-in to shadows
                 * -consider shadows for other content
                 */
                mat4 shadowMatrix(op->mTransformFrom3dRoot);
                op->mDisplayList->applyViewPropertyTransforms(shadowMatrix);
                DisplayListOp* shadowOp  = new (alloc) DrawShadowOp(shadowMatrix, op->mDisplayList->mAlpha,
                        op->mDisplayList->getWidth(), op->mDisplayList->getHeight());
                handler(shadowOp, PROPERTY_SAVECOUNT, mClipToBounds);
            }

            renderer.concatMatrix(op->mTransformFrom3dRoot);
            op->mSkipInOrderDraw = false; // this is horrible, I'm so sorry everyone
            handler(op, renderer.getSaveCount() - 1, mClipToBounds);
            op->mSkipInOrderDraw = true;
        }
    }
    handler(new (alloc) RestoreToCountOp(rootRestoreTo), PROPERTY_SAVECOUNT, mClipToBounds);
}

/**
 * This function serves both defer and replay modes, and will organize the displayList's component
 * operations for a single frame:
 *
 * Every 'simple' state operation that affects just the matrix and alpha (or other factors of
 * DeferredDisplayState) may be issued directly to the renderer, but complex operations (with custom
 * defer logic) and operations in displayListOps are issued through the 'handler' which handles the
 * defer vs replay logic, per operation
 */
template <class T>
void DisplayList::iterate(OpenGLRenderer& renderer, T& handler, const int level) {
    if (CC_UNLIKELY(mDestroyed)) { // temporary debug logging
        ALOGW("Error: %s is drawing after destruction, size %d", getName(), mSize);
        CRASH();
    }
    if (mSize == 0 || mAlpha <= 0) {
        DISPLAY_LIST_LOGD("%*sEmpty display list (%p, %s)", level * 2, "", this, mName.string());
        return;
    }

#if DEBUG_DISPLAY_LIST
    Rect* clipRect = renderer.getClipRect();
    DISPLAY_LIST_LOGD("%*sStart display list (%p, %s), clipRect: %.0f, %.0f, %.0f, %.0f",
            level * 2, "", this, mName.string(), clipRect->left, clipRect->top,
            clipRect->right, clipRect->bottom);
#endif

    LinearAllocator& alloc = handler.allocator();
    int restoreTo = renderer.getSaveCount();
    handler(new (alloc) SaveOp(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag),
            PROPERTY_SAVECOUNT, mClipToBounds);

    DISPLAY_LIST_LOGD("%*sSave %d %d", (level + 1) * 2, "",
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag, restoreTo);

    setViewProperties<T>(renderer, handler, level + 1);

    bool quickRejected = mClipToBounds && renderer.quickRejectConservative(0, 0, mWidth, mHeight);
    if (!quickRejected) {
        // for 3d root, draw children with negative z values
        iterate3dChildren(kNegativeZChildren, renderer, handler, level);

        DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
        const int saveCountOffset = renderer.getSaveCount() - 1;
        for (unsigned int i = 0; i < mDisplayListData->displayListOps.size(); i++) {
            DisplayListOp *op = mDisplayListData->displayListOps[i];

#if DEBUG_DISPLAY_LIST
            op->output(level + 1);
#endif

            logBuffer.writeCommand(level, op->name());
            handler(op, saveCountOffset, mClipToBounds);
        }

        // for 3d root, draw children with positive z values
        iterate3dChildren(kPositiveZChildren, renderer, handler, level);
    }

    DISPLAY_LIST_LOGD("%*sRestoreToCount %d", (level + 1) * 2, "", restoreTo);
    handler(new (alloc) RestoreToCountOp(restoreTo),
            PROPERTY_SAVECOUNT, mClipToBounds);
    renderer.setOverrideLayerAlpha(1.0f);
}

}; // namespace uirenderer
}; // namespace android
