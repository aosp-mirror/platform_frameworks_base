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
#include <algorithm>

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

DisplayList::DisplayList() :
        mDisplayListData(0), mDestroyed(false), mTransformMatrix(NULL), mTransformCamera(NULL),
        mTransformMatrix3D(NULL), mStaticMatrix(NULL), mAnimationMatrix(NULL) {

    mLeft = 0;
    mTop = 0;
    mRight = 0;
    mBottom = 0;
    mClipToBounds = true;
    mIsolatedZVolume = true;
    mProjectBackwards = false;
    mProjectionReceiver = false;
    mOutline.rewind();
    mClipToOutline = false;
    mCastsShadow = false;
    mUsesGlobalCamera = false;
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

DisplayList::~DisplayList() {
    LOG_ALWAYS_FATAL_IF(mDestroyed, "Double destroyed DisplayList %p", this);

    mDestroyed = true;
    delete mDisplayListData;
    delete mTransformMatrix;
    delete mTransformCamera;
    delete mTransformMatrix3D;
    delete mStaticMatrix;
    delete mAnimationMatrix;
}

void DisplayList::destroyDisplayListDeferred(DisplayList* displayList) {
    if (displayList) {
        DISPLAY_LIST_LOGD("Deferring display list destruction");
        Caches::getInstance().deleteDisplayListDeferred(displayList);
    }
}

void DisplayList::setData(DisplayListData* data) {
    delete mDisplayListData;
    mDisplayListData = data;
    if (mDisplayListData) {
        Caches::getInstance().registerFunctors(mDisplayListData->functorCount);
    }
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
        // NOTE: mTransformMatrix won't be up to date if a DisplayList goes from a complex transform
        // to a pure translate. This is safe because the matrix isn't read in pure translate cases.
        if (mMatrixFlags && mMatrixFlags != TRANSLATION) {
            if (!mTransformMatrix) {
                // only allocate a matrix if we have a complex transform
                mTransformMatrix = new Matrix4();
            }
            if (!mPivotExplicitlySet) {
                if (mWidth != mPrevWidth || mHeight != mPrevHeight) {
                    mPrevWidth = mWidth;
                    mPrevHeight = mHeight;
                    mPivotX = mPrevWidth / 2.0f;
                    mPivotY = mPrevHeight / 2.0f;
                }
            }

            if ((mMatrixFlags & ROTATION_3D) == 0) {
                mTransformMatrix->loadTranslate(
                        mPivotX + mTranslationX,
                        mPivotY + mTranslationY,
                        0);
                mTransformMatrix->rotate(mRotation, 0, 0, 1);
                mTransformMatrix->scale(mScaleX, mScaleY, 1);
                mTransformMatrix->translate(-mPivotX, -mPivotY);
            } else {
                if (!mTransformCamera) {
                    mTransformCamera = new Sk3DView();
                    mTransformMatrix3D = new SkMatrix();
                }
                SkMatrix transformMatrix;
                transformMatrix.reset();
                mTransformCamera->save();
                transformMatrix.preScale(mScaleX, mScaleY, mPivotX, mPivotY);
                mTransformCamera->rotateX(mRotationX);
                mTransformCamera->rotateY(mRotationY);
                mTransformCamera->rotateZ(-mRotation);
                mTransformCamera->getMatrix(mTransformMatrix3D);
                mTransformMatrix3D->preTranslate(-mPivotX, -mPivotY);
                mTransformMatrix3D->postTranslate(mPivotX + mTranslationX,
                        mPivotY + mTranslationY);
                transformMatrix.postConcat(*mTransformMatrix3D);
                mTransformCamera->restore();

                mTransformMatrix->load(transformMatrix);
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
        ALOGD("%*sConcatMatrix (static) %p: " SK_MATRIX_STRING,
                level * 2, "", mStaticMatrix, SK_MATRIX_ARGS(mStaticMatrix));
    }
    if (mAnimationMatrix) {
        ALOGD("%*sConcatMatrix (animation) %p: " SK_MATRIX_STRING,
                level * 2, "", mAnimationMatrix, SK_MATRIX_ARGS(mAnimationMatrix));
    }
    if (mMatrixFlags != 0) {
        if (mMatrixFlags == TRANSLATION) {
            ALOGD("%*sTranslate %.2f, %.2f, %.2f",
                    level * 2, "", mTranslationX, mTranslationY, mTranslationZ);
        } else {
            ALOGD("%*sConcatMatrix %p: " MATRIX_4_STRING,
                    level * 2, "", mTransformMatrix, MATRIX_4_ARGS(mTransformMatrix));
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
            renderer.translate(mTranslationX, mTranslationY);
        } else {
            renderer.concatMatrix(*mTransformMatrix);
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
                    0, 0, mRight - mLeft, mBottom - mTop, mAlpha * 255, saveFlags);
            handler(op, PROPERTY_SAVECOUNT, mClipToBounds);
        }
    }
    if (clipToBoundsNeeded) {
        ClipRectOp* op = new (handler.allocator()) ClipRectOp(0, 0,
                mRight - mLeft, mBottom - mTop, SkRegion::kIntersect_Op);
        handler(op, PROPERTY_SAVECOUNT, mClipToBounds);
    }
    if (CC_UNLIKELY(mClipToOutline && !mOutline.isEmpty())) {
        ClipPathOp* op = new (handler.allocator()) ClipPathOp(&mOutline, SkRegion::kIntersect_Op);
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
        updateMatrix();
        if (mMatrixFlags == TRANSLATION) {
            matrix.translate(mTranslationX, mTranslationY, mTranslationZ);
        } else {
            matrix.multiply(*mTransformMatrix);
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
    m3dNodes.clear();
    mProjectedNodes.clear();

    // TODO: create temporary DDLOp and call computeOrderingImpl on top DisplayList so that
    // transform properties are applied correctly to top level children
    if (mDisplayListData == NULL) return;
    for (unsigned int i = 0; i < mDisplayListData->children.size(); i++) {
        DrawDisplayListOp* childOp = mDisplayListData->children[i];
        childOp->mDisplayList->computeOrderingImpl(childOp,
                &m3dNodes, &mat4::identity(),
                &mProjectedNodes, &mat4::identity());
    }
}

void DisplayList::computeOrderingImpl(
        DrawDisplayListOp* opState,
        Vector<ZDrawDisplayListOpPair>* compositedChildrenOf3dRoot,
        const mat4* transformFrom3dRoot,
        Vector<DrawDisplayListOp*>* compositedChildrenOfProjectionSurface,
        const mat4* transformFromProjectionSurface) {
    m3dNodes.clear();
    mProjectedNodes.clear();
    if (mDisplayListData == NULL || mDisplayListData->isEmpty()) return;

    // TODO: should avoid this calculation in most cases
    // TODO: just calculate single matrix, down to all leaf composited elements
    Matrix4 localTransformFrom3dRoot(*transformFrom3dRoot);
    localTransformFrom3dRoot.multiply(opState->mTransformFromParent);
    Matrix4 localTransformFromProjectionSurface(*transformFromProjectionSurface);
    localTransformFromProjectionSurface.multiply(opState->mTransformFromParent);

    if (mTranslationZ != 0.0f) { // TODO: other signals for 3d compositing, such as custom matrix4
        // composited 3d layer, flag for out of order draw and save matrix...
        opState->mSkipInOrderDraw = true;
        opState->mTransformFromCompositingAncestor.load(localTransformFrom3dRoot);

        // ... and insert into current 3d root, keyed with pivot z for later sorting
        Vector3 pivot(mPivotX, mPivotY, 0.0f);
        mat4 totalTransform(localTransformFrom3dRoot);
        applyViewPropertyTransforms(totalTransform);
        totalTransform.mapPoint3d(pivot);
        compositedChildrenOf3dRoot->add(ZDrawDisplayListOpPair(pivot.z, opState));
    } else if (mProjectBackwards) {
        // composited projectee, flag for out of order draw, save matrix, and store in proj surface
        opState->mSkipInOrderDraw = true;
        opState->mTransformFromCompositingAncestor.load(localTransformFromProjectionSurface);
        compositedChildrenOfProjectionSurface->add(opState);
    } else {
        // standard in order draw
        opState->mSkipInOrderDraw = false;
    }

    if (mDisplayListData->children.size() > 0) {
        if (mIsolatedZVolume) {
            // create a new 3d space for descendents by collecting them
            compositedChildrenOf3dRoot = &m3dNodes;
            transformFrom3dRoot = &mat4::identity();
        } else {
            applyViewPropertyTransforms(localTransformFrom3dRoot);
            transformFrom3dRoot = &localTransformFrom3dRoot;
        }

        const bool isProjectionReceiver = mDisplayListData->projectionReceiveIndex >= 0;
        bool haveAppliedPropertiesToProjection = false;
        for (unsigned int i = 0; i < mDisplayListData->children.size(); i++) {
            DrawDisplayListOp* childOp = mDisplayListData->children[i];
            DisplayList* child = childOp->mDisplayList;

            Vector<DrawDisplayListOp*>* projectionChildren = NULL;
            const mat4* projectionTransform = NULL;
            if (isProjectionReceiver && !child->mProjectBackwards) {
                // if receiving projections, collect projecting descendent

                // Note that if a direct descendent is projecting backwards, we pass it's
                // grandparent projection collection, since it shouldn't project onto it's
                // parent, where it will already be drawing.
                projectionChildren = &mProjectedNodes;
                projectionTransform = &mat4::identity();
            } else {
                if (!haveAppliedPropertiesToProjection) {
                    applyViewPropertyTransforms(localTransformFromProjectionSurface);
                    haveAppliedPropertiesToProjection = true;
                }
                projectionChildren = compositedChildrenOfProjectionSurface;
                projectionTransform = &localTransformFromProjectionSurface;
            }
            child->computeOrderingImpl(childOp,
                    compositedChildrenOf3dRoot, transformFrom3dRoot,
                    projectionChildren, projectionTransform);
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

#define SHADOW_DELTA 0.1f

template <class T>
void DisplayList::iterate3dChildren(ChildrenSelectMode mode, OpenGLRenderer& renderer,
        T& handler, const int level) {
    if (m3dNodes.size() == 0 ||
            (mode == kNegativeZChildren && m3dNodes[0].key > 0.0f) ||
            (mode == kPositiveZChildren && m3dNodes[m3dNodes.size() - 1].key < 0.0f)) {
        // no 3d children to draw
        return;
    }

    int rootRestoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    LinearAllocator& alloc = handler.allocator();
    ClipRectOp* clipOp = new (alloc) ClipRectOp(0, 0, mWidth, mHeight,
            SkRegion::kIntersect_Op); // clip to 3d root bounds
    handler(clipOp, PROPERTY_SAVECOUNT, mClipToBounds);

    /**
     * Draw shadows and (potential) casters mostly in order, but allow the shadows of casters
     * with very similar Z heights to draw together.
     *
     * This way, if Views A & B have the same Z height and are both casting shadows, the shadows are
     * underneath both, and neither's shadow is drawn on top of the other.
     */
    const size_t nonNegativeIndex = findNonNegativeIndex(m3dNodes);
    size_t drawIndex, shadowIndex, endIndex;
    if (mode == kNegativeZChildren) {
        drawIndex = 0;
        endIndex = nonNegativeIndex;
        shadowIndex = endIndex; // draw no shadows
    } else {
        drawIndex = nonNegativeIndex;
        endIndex = m3dNodes.size();
        shadowIndex = drawIndex; // potentially draw shadow for each pos Z child
    }
    float lastCasterZ = 0.0f;
    while (shadowIndex < endIndex || drawIndex < endIndex) {
        if (shadowIndex < endIndex) {
            DrawDisplayListOp* casterOp = m3dNodes[shadowIndex].value;
            DisplayList* caster = casterOp->mDisplayList;
            const float casterZ = m3dNodes[shadowIndex].key;
            // attempt to render the shadow if the caster about to be drawn is its caster,
            // OR if its caster's Z value is similar to the previous potential caster
            if (shadowIndex == drawIndex || casterZ - lastCasterZ < SHADOW_DELTA) {

                if (caster->mCastsShadow && caster->mAlpha > 0.0f) {
                    mat4 shadowMatrix(casterOp->mTransformFromCompositingAncestor);
                    caster->applyViewPropertyTransforms(shadowMatrix);

                    DisplayListOp* shadowOp  = new (alloc) DrawShadowOp(shadowMatrix,
                            caster->mAlpha, &(caster->mOutline), caster->mWidth, caster->mHeight);
                    handler(shadowOp, PROPERTY_SAVECOUNT, mClipToBounds);
                }

                lastCasterZ = casterZ; // must do this even if current caster not casting a shadow
                shadowIndex++;
                continue;
            }
        }

        // only the actual child DL draw needs to be in save/restore,
        // since it modifies the renderer's matrix
        int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag);

        DrawDisplayListOp* childOp = m3dNodes[drawIndex].value;
        DisplayList* child = childOp->mDisplayList;

        renderer.concatMatrix(childOp->mTransformFromCompositingAncestor);
        childOp->mSkipInOrderDraw = false; // this is horrible, I'm so sorry everyone
        handler(childOp, renderer.getSaveCount() - 1, mClipToBounds);
        childOp->mSkipInOrderDraw = true;

        renderer.restoreToCount(restoreTo);
        drawIndex++;
    }
    handler(new (alloc) RestoreToCountOp(rootRestoreTo), PROPERTY_SAVECOUNT, mClipToBounds);
}

template <class T>
void DisplayList::iterateProjectedChildren(OpenGLRenderer& renderer, T& handler, const int level) {
    int rootRestoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    LinearAllocator& alloc = handler.allocator();
    ClipRectOp* clipOp = new (alloc) ClipRectOp(0, 0, mWidth, mHeight,
            SkRegion::kReplace_Op); // clip to projection surface root bounds
    handler(clipOp, PROPERTY_SAVECOUNT, mClipToBounds);

    for (size_t i = 0; i < mProjectedNodes.size(); i++) {
        DrawDisplayListOp* childOp = mProjectedNodes[i];

        // matrix save, concat, and restore can be done safely without allocating operations
        int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag);
        renderer.concatMatrix(childOp->mTransformFromCompositingAncestor);
        childOp->mSkipInOrderDraw = false; // this is horrible, I'm so sorry everyone
        handler(childOp, renderer.getSaveCount() - 1, mClipToBounds);
        childOp->mSkipInOrderDraw = true;
        renderer.restoreToCount(restoreTo);
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
        ALOGW("Error: %s is drawing after destruction", getName());
        CRASH();
    }
    if (mDisplayListData->isEmpty() || mAlpha <= 0) {
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
        // Z sort 3d children (stable-ness makes z compare fall back to standard drawing order)
        std::stable_sort(m3dNodes.begin(), m3dNodes.end());

        // for 3d root, draw children with negative z values
        iterate3dChildren(kNegativeZChildren, renderer, handler, level);

        DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
        const int saveCountOffset = renderer.getSaveCount() - 1;
        const int projectionReceiveIndex = mDisplayListData->projectionReceiveIndex;
        for (unsigned int i = 0; i < mDisplayListData->displayListOps.size(); i++) {
            DisplayListOp *op = mDisplayListData->displayListOps[i];

#if DEBUG_DISPLAY_LIST
            op->output(level + 1);
#endif

            logBuffer.writeCommand(level, op->name());
            handler(op, saveCountOffset, mClipToBounds);

            if (CC_UNLIKELY(i == projectionReceiveIndex && mProjectedNodes.size() > 0)) {
                iterateProjectedChildren(renderer, handler, level);
            }
        }

        // for 3d root, draw children with positive z values
        iterate3dChildren(kPositiveZChildren, renderer, handler, level);
    }

    DISPLAY_LIST_LOGD("%*sRestoreToCount %d", (level + 1) * 2, "", restoreTo);
    handler(new (alloc) RestoreToCountOp(restoreTo),
            PROPERTY_SAVECOUNT, mClipToBounds);
    renderer.setOverrideLayerAlpha(1.0f);
}

void DisplayListData::cleanupResources() {
    Caches& caches = Caches::getInstance();
    caches.unregisterFunctors(functorCount);
    caches.resourceCache.lock();

    for (size_t i = 0; i < bitmapResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(bitmapResources.itemAt(i));
    }

    for (size_t i = 0; i < ownedBitmapResources.size(); i++) {
        const SkBitmap* bitmap = ownedBitmapResources.itemAt(i);
        caches.resourceCache.decrementRefcountLocked(bitmap);
        caches.resourceCache.destructorLocked(bitmap);
    }

    for (size_t i = 0; i < patchResources.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(patchResources.itemAt(i));
    }

    for (size_t i = 0; i < shaders.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(shaders.itemAt(i));
        caches.resourceCache.destructorLocked(shaders.itemAt(i));
    }

    for (size_t i = 0; i < sourcePaths.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(sourcePaths.itemAt(i));
    }

    for (size_t i = 0; i < layers.size(); i++) {
        caches.resourceCache.decrementRefcountLocked(layers.itemAt(i));
    }

    caches.resourceCache.unlock();

    for (size_t i = 0; i < paints.size(); i++) {
        delete paints.itemAt(i);
    }

    for (size_t i = 0; i < regions.size(); i++) {
        delete regions.itemAt(i);
    }

    for (size_t i = 0; i < paths.size(); i++) {
        delete paths.itemAt(i);
    }

    for (size_t i = 0; i < matrices.size(); i++) {
        delete matrices.itemAt(i);
    }

    bitmapResources.clear();
    ownedBitmapResources.clear();
    patchResources.clear();
    shaders.clear();
    sourcePaths.clear();
    paints.clear();
    regions.clear();
    paths.clear();
    matrices.clear();
    layers.clear();
}

}; // namespace uirenderer
}; // namespace android
