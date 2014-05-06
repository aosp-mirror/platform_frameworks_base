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

#define ATRACE_TAG ATRACE_TAG_VIEW

#include "RenderNode.h"

#include <algorithm>

#include <SkCanvas.h>
#include <algorithm>

#include <utils/Trace.h>

#include "Debug.h"
#include "DisplayListOp.h"
#include "DisplayListLogBuffer.h"
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

void RenderNode::outputLogBuffer(int fd) {
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

RenderNode::RenderNode()
        : mNeedsPropertiesSync(false)
        , mNeedsDisplayListDataSync(false)
        , mDisplayListData(0)
        , mStagingDisplayListData(0)
        , mNeedsAnimatorsSync(false) {
}

RenderNode::~RenderNode() {
    delete mDisplayListData;
    delete mStagingDisplayListData;
}

void RenderNode::setStagingDisplayList(DisplayListData* data) {
    mNeedsDisplayListDataSync = true;
    delete mStagingDisplayListData;
    mStagingDisplayListData = data;
    if (mStagingDisplayListData) {
        Caches::getInstance().registerFunctors(mStagingDisplayListData->functorCount);
    }
}

/**
 * This function is a simplified version of replay(), where we simply retrieve and log the
 * display list. This function should remain in sync with the replay() function.
 */
void RenderNode::output(uint32_t level) {
    ALOGD("%*sStart display list (%p, %s, render=%d)", (level - 1) * 2, "", this,
            getName(), isRenderable());
    ALOGD("%*s%s %d", level * 2, "", "Save",
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);

    properties().debugOutputProperties(level);
    int flags = DisplayListOp::kOpLogFlag_Recurse;
    for (unsigned int i = 0; i < mDisplayListData->displayListOps.size(); i++) {
        mDisplayListData->displayListOps[i]->output(level, flags);
    }

    ALOGD("%*sDone (%p, %s)", (level - 1) * 2, "", this, getName());
}

void RenderNode::prepareTree(TreeInfo& info) {
    ATRACE_CALL();

    prepareTreeImpl(info);
}

void RenderNode::prepareTreeImpl(TreeInfo& info) {
    if (info.performStagingPush) {
        pushStagingChanges(info);
    }
    if (info.evaluateAnimations) {
        evaluateAnimations(info);
    }
    prepareSubTree(info, mDisplayListData);
}

static bool is_finished(const sp<BaseRenderNodeAnimator>& animator) {
    return animator->isFinished();
}

void RenderNode::pushStagingChanges(TreeInfo& info) {
    if (mNeedsPropertiesSync) {
        mNeedsPropertiesSync = false;
        mProperties = mStagingProperties;
    }
    if (mNeedsAnimatorsSync) {
        mAnimators.resize(mStagingAnimators.size());
        std::vector< sp<BaseRenderNodeAnimator> >::iterator it;
        // hint: this means copy_if_not()
        it = std::remove_copy_if(mStagingAnimators.begin(), mStagingAnimators.end(),
                mAnimators.begin(), is_finished);
        mAnimators.resize(std::distance(mAnimators.begin(), it));
    }
    if (mNeedsDisplayListDataSync) {
        mNeedsDisplayListDataSync = false;
        // Do a push pass on the old tree to handle freeing DisplayListData
        // that are no longer used
        TreeInfo oldTreeInfo;
        prepareSubTree(oldTreeInfo, mDisplayListData);
        // TODO: The damage for the old tree should be accounted for
        delete mDisplayListData;
        mDisplayListData = mStagingDisplayListData;
        mStagingDisplayListData = 0;
    }
}

class AnimateFunctor {
public:
    AnimateFunctor(RenderNode* target, TreeInfo& info)
            : mTarget(target), mInfo(info) {}

    bool operator() (sp<BaseRenderNodeAnimator>& animator) {
        return animator->animate(mTarget, mInfo);
    }
private:
    RenderNode* mTarget;
    TreeInfo& mInfo;
};

void RenderNode::evaluateAnimations(TreeInfo& info) {
    if (!mAnimators.size()) return;

    AnimateFunctor functor(this, info);
    std::vector< sp<BaseRenderNodeAnimator> >::iterator newEnd;
    newEnd = std::remove_if(mAnimators.begin(), mAnimators.end(), functor);
    mAnimators.erase(newEnd, mAnimators.end());
    mProperties.updateMatrix();
    info.out.hasAnimations |= mAnimators.size();
}

void RenderNode::prepareSubTree(TreeInfo& info, DisplayListData* subtree) {
    if (subtree) {
        TextureCache& cache = Caches::getInstance().textureCache;
        info.out.hasFunctors |= subtree->functorCount;
        // TODO: Fix ownedBitmapResources to not require disabling prepareTextures
        // and thus falling out of async drawing path.
        if (subtree->ownedBitmapResources.size()) {
            info.prepareTextures = false;
        }
        for (size_t i = 0; info.prepareTextures && i < subtree->bitmapResources.size(); i++) {
            info.prepareTextures = cache.prefetchAndMarkInUse(subtree->bitmapResources[i]);
        }
        for (size_t i = 0; i < subtree->children().size(); i++) {
            RenderNode* childNode = subtree->children()[i]->mDisplayList;
            childNode->prepareTreeImpl(info);
        }
    }
}

/*
 * For property operations, we pass a savecount of 0, since the operations aren't part of the
 * displaylist, and thus don't have to compensate for the record-time/playback-time discrepancy in
 * base saveCount (i.e., how RestoreToCount uses saveCount + properties().getCount())
 */
#define PROPERTY_SAVECOUNT 0

template <class T>
void RenderNode::setViewProperties(OpenGLRenderer& renderer, T& handler) {
#if DEBUG_DISPLAY_LIST
    properties().debugOutputProperties(handler.level() + 1);
#endif
    if (properties().getLeft() != 0 || properties().getTop() != 0) {
        renderer.translate(properties().getLeft(), properties().getTop());
    }
    if (properties().getStaticMatrix()) {
        renderer.concatMatrix(properties().getStaticMatrix());
    } else if (properties().getAnimationMatrix()) {
        renderer.concatMatrix(properties().getAnimationMatrix());
    }
    if (properties().hasTransformMatrix()) {
        if (properties().isTransformTranslateOnly()) {
            renderer.translate(properties().getTranslationX(), properties().getTranslationY());
        } else {
            renderer.concatMatrix(*properties().getTransformMatrix());
        }
    }
    bool clipToBoundsNeeded = properties().getCaching() ? false : properties().getClipToBounds();
    if (properties().getAlpha() < 1) {
        if (properties().getCaching()) {
            renderer.setOverrideLayerAlpha(properties().getAlpha());
        } else if (!properties().getHasOverlappingRendering()) {
            renderer.scaleAlpha(properties().getAlpha());
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
                    0, 0, properties().getWidth(), properties().getHeight(),
                    properties().getAlpha() * 255, saveFlags);
            handler(op, PROPERTY_SAVECOUNT, properties().getClipToBounds());
        }
    }
    if (clipToBoundsNeeded) {
        ClipRectOp* op = new (handler.allocator()) ClipRectOp(
                0, 0, properties().getWidth(), properties().getHeight(), SkRegion::kIntersect_Op);
        handler(op, PROPERTY_SAVECOUNT, properties().getClipToBounds());
    }

    if (CC_UNLIKELY(properties().hasClippingPath())) {
        ClipPathOp* op = new (handler.allocator()) ClipPathOp(
                properties().getClippingPath(), properties().getClippingPathOp());
        handler(op, PROPERTY_SAVECOUNT, properties().getClipToBounds());
    }
}

/**
 * Apply property-based transformations to input matrix
 *
 * If true3dTransform is set to true, the transform applied to the input matrix will use true 4x4
 * matrix computation instead of the Skia 3x3 matrix + camera hackery.
 */
void RenderNode::applyViewPropertyTransforms(mat4& matrix, bool true3dTransform) {
    if (properties().getLeft() != 0 || properties().getTop() != 0) {
        matrix.translate(properties().getLeft(), properties().getTop());
    }
    if (properties().getStaticMatrix()) {
        mat4 stat(*properties().getStaticMatrix());
        matrix.multiply(stat);
    } else if (properties().getAnimationMatrix()) {
        mat4 anim(*properties().getAnimationMatrix());
        matrix.multiply(anim);
    }

    bool applyTranslationZ = true3dTransform && !MathUtils::isZero(properties().getZ());
    if (properties().hasTransformMatrix() || applyTranslationZ) {
        if (properties().isTransformTranslateOnly()) {
            matrix.translate(properties().getTranslationX(), properties().getTranslationY(),
                    true3dTransform ? properties().getZ() : 0.0f);
        } else {
            if (!true3dTransform) {
                matrix.multiply(*properties().getTransformMatrix());
            } else {
                mat4 true3dMat;
                true3dMat.loadTranslate(
                        properties().getPivotX() + properties().getTranslationX(),
                        properties().getPivotY() + properties().getTranslationY(),
                        properties().getZ());
                true3dMat.rotate(properties().getRotationX(), 1, 0, 0);
                true3dMat.rotate(properties().getRotationY(), 0, 1, 0);
                true3dMat.rotate(properties().getRotation(), 0, 0, 1);
                true3dMat.scale(properties().getScaleX(), properties().getScaleY(), 1);
                true3dMat.translate(-properties().getPivotX(), -properties().getPivotY());

                matrix.multiply(true3dMat);
            }
        }
    }
}

/**
 * Organizes the DisplayList hierarchy to prepare for background projection reordering.
 *
 * This should be called before a call to defer() or drawDisplayList()
 *
 * Each DisplayList that serves as a 3d root builds its list of composited children,
 * which are flagged to not draw in the standard draw loop.
 */
void RenderNode::computeOrdering() {
    ATRACE_CALL();
    mProjectedNodes.clear();

    // TODO: create temporary DDLOp and call computeOrderingImpl on top DisplayList so that
    // transform properties are applied correctly to top level children
    if (mDisplayListData == NULL) return;
    for (unsigned int i = 0; i < mDisplayListData->children().size(); i++) {
        DrawDisplayListOp* childOp = mDisplayListData->children()[i];
        childOp->mDisplayList->computeOrderingImpl(childOp,
                properties().getOutline().getPath(), &mProjectedNodes, &mat4::identity());
    }
}

void RenderNode::computeOrderingImpl(
        DrawDisplayListOp* opState,
        const SkPath* outlineOfProjectionSurface,
        Vector<DrawDisplayListOp*>* compositedChildrenOfProjectionSurface,
        const mat4* transformFromProjectionSurface) {
    mProjectedNodes.clear();
    if (mDisplayListData == NULL || mDisplayListData->isEmpty()) return;

    // TODO: should avoid this calculation in most cases
    // TODO: just calculate single matrix, down to all leaf composited elements
    Matrix4 localTransformFromProjectionSurface(*transformFromProjectionSurface);
    localTransformFromProjectionSurface.multiply(opState->mTransformFromParent);

    if (properties().getProjectBackwards()) {
        // composited projectee, flag for out of order draw, save matrix, and store in proj surface
        opState->mSkipInOrderDraw = true;
        opState->mTransformFromCompositingAncestor.load(localTransformFromProjectionSurface);
        compositedChildrenOfProjectionSurface->add(opState);
    } else {
        // standard in order draw
        opState->mSkipInOrderDraw = false;
    }

    if (mDisplayListData->children().size() > 0) {
        const bool isProjectionReceiver = mDisplayListData->projectionReceiveIndex >= 0;
        bool haveAppliedPropertiesToProjection = false;
        for (unsigned int i = 0; i < mDisplayListData->children().size(); i++) {
            DrawDisplayListOp* childOp = mDisplayListData->children()[i];
            RenderNode* child = childOp->mDisplayList;

            const SkPath* projectionOutline = NULL;
            Vector<DrawDisplayListOp*>* projectionChildren = NULL;
            const mat4* projectionTransform = NULL;
            if (isProjectionReceiver && !child->properties().getProjectBackwards()) {
                // if receiving projections, collect projecting descendent

                // Note that if a direct descendent is projecting backwards, we pass it's
                // grandparent projection collection, since it shouldn't project onto it's
                // parent, where it will already be drawing.
                projectionOutline = properties().getOutline().getPath();
                projectionChildren = &mProjectedNodes;
                projectionTransform = &mat4::identity();
            } else {
                if (!haveAppliedPropertiesToProjection) {
                    applyViewPropertyTransforms(localTransformFromProjectionSurface);
                    haveAppliedPropertiesToProjection = true;
                }
                projectionOutline = outlineOfProjectionSurface;
                projectionChildren = compositedChildrenOfProjectionSurface;
                projectionTransform = &localTransformFromProjectionSurface;
            }
            child->computeOrderingImpl(childOp,
                    projectionOutline, projectionChildren, projectionTransform);
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
    inline void startMark(const char* name) {} // do nothing
    inline void endMark() {}
    inline int level() { return mLevel; }
    inline int replayFlags() { return mDeferStruct.mReplayFlags; }

private:
    DeferStateStruct& mDeferStruct;
    const int mLevel;
};

void RenderNode::deferNodeTree(DeferStateStruct& deferStruct) {
    DeferOperationHandler handler(deferStruct, 0);
    if (MathUtils::isPositive(properties().getZ())) {
        issueDrawShadowOperation(Matrix4::identity(), handler);
    }
    issueOperations<DeferOperationHandler>(deferStruct.mRenderer, handler);
}

void RenderNode::deferNodeInParent(DeferStateStruct& deferStruct, const int level) {
    DeferOperationHandler handler(deferStruct, level);
    issueOperations<DeferOperationHandler>(deferStruct.mRenderer, handler);
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
    inline void startMark(const char* name) {
        mReplayStruct.mRenderer.startMark(name);
    }
    inline void endMark() {
        mReplayStruct.mRenderer.endMark();
    }
    inline int level() { return mLevel; }
    inline int replayFlags() { return mReplayStruct.mReplayFlags; }

private:
    ReplayStateStruct& mReplayStruct;
    const int mLevel;
};

void RenderNode::replayNodeTree(ReplayStateStruct& replayStruct) {
    ReplayOperationHandler handler(replayStruct, 0);
    if (MathUtils::isPositive(properties().getZ())) {
        issueDrawShadowOperation(Matrix4::identity(), handler);
    }
    issueOperations<ReplayOperationHandler>(replayStruct.mRenderer, handler);
}

void RenderNode::replayNodeInParent(ReplayStateStruct& replayStruct, const int level) {
    ReplayOperationHandler handler(replayStruct, level);
    issueOperations<ReplayOperationHandler>(replayStruct.mRenderer, handler);
}

void RenderNode::buildZSortedChildList(Vector<ZDrawDisplayListOpPair>& zTranslatedNodes) {
    if (mDisplayListData == NULL || mDisplayListData->children().size() == 0) return;

    for (unsigned int i = 0; i < mDisplayListData->children().size(); i++) {
        DrawDisplayListOp* childOp = mDisplayListData->children()[i];
        RenderNode* child = childOp->mDisplayList;
        float childZ = child->properties().getZ();

        if (!MathUtils::isZero(childZ)) {
            zTranslatedNodes.add(ZDrawDisplayListOpPair(childZ, childOp));
            childOp->mSkipInOrderDraw = true;
        } else if (!child->properties().getProjectBackwards()) {
            // regular, in order drawing DisplayList
            childOp->mSkipInOrderDraw = false;
        }
    }

    // Z sort 3d children (stable-ness makes z compare fall back to standard drawing order)
    std::stable_sort(zTranslatedNodes.begin(), zTranslatedNodes.end());
}

template <class T>
void RenderNode::issueDrawShadowOperation(const Matrix4& transformFromParent, T& handler) {
    if (properties().getAlpha() <= 0.0f) return;

    mat4 shadowMatrixXY(transformFromParent);
    applyViewPropertyTransforms(shadowMatrixXY);

    // Z matrix needs actual 3d transformation, so mapped z values will be correct
    mat4 shadowMatrixZ(transformFromParent);
    applyViewPropertyTransforms(shadowMatrixZ, true);

    const SkPath* outlinePath = properties().getOutline().getPath();
    const RevealClip& revealClip = properties().getRevealClip();
    const SkPath* revealClipPath = revealClip.hasConvexClip()
            ?  revealClip.getPath() : NULL; // only pass the reveal clip's path if it's convex

    /**
     * The drawing area of the caster is always the same as the its perimeter (which
     * the shadow system uses) *except* in the inverse clip case. Inform the shadow
     * system that the caster's drawing area (as opposed to its perimeter) has been
     * clipped, so that it knows the caster can't be opaque.
     */
    bool casterUnclipped = !revealClip.willClip() || revealClip.hasConvexClip();

    DisplayListOp* shadowOp  = new (handler.allocator()) DrawShadowOp(
            shadowMatrixXY, shadowMatrixZ,
            properties().getAlpha(), casterUnclipped,
            properties().getWidth(), properties().getHeight(),
            outlinePath, revealClipPath);
    handler(shadowOp, PROPERTY_SAVECOUNT, properties().getClipToBounds());
}

#define SHADOW_DELTA 0.1f

template <class T>
void RenderNode::issueOperationsOf3dChildren(const Vector<ZDrawDisplayListOpPair>& zTranslatedNodes,
        ChildrenSelectMode mode, OpenGLRenderer& renderer, T& handler) {
    const int size = zTranslatedNodes.size();
    if (size == 0
            || (mode == kNegativeZChildren && zTranslatedNodes[0].key > 0.0f)
            || (mode == kPositiveZChildren && zTranslatedNodes[size - 1].key < 0.0f)) {
        // no 3d children to draw
        return;
    }

    /**
     * Draw shadows and (potential) casters mostly in order, but allow the shadows of casters
     * with very similar Z heights to draw together.
     *
     * This way, if Views A & B have the same Z height and are both casting shadows, the shadows are
     * underneath both, and neither's shadow is drawn on top of the other.
     */
    const size_t nonNegativeIndex = findNonNegativeIndex(zTranslatedNodes);
    size_t drawIndex, shadowIndex, endIndex;
    if (mode == kNegativeZChildren) {
        drawIndex = 0;
        endIndex = nonNegativeIndex;
        shadowIndex = endIndex; // draw no shadows
    } else {
        drawIndex = nonNegativeIndex;
        endIndex = size;
        shadowIndex = drawIndex; // potentially draw shadow for each pos Z child
    }

    DISPLAY_LIST_LOGD("%*s%d %s 3d children:", (handler.level() + 1) * 2, "",
            endIndex - drawIndex, mode == kNegativeZChildren ? "negative" : "positive");

    float lastCasterZ = 0.0f;
    while (shadowIndex < endIndex || drawIndex < endIndex) {
        if (shadowIndex < endIndex) {
            DrawDisplayListOp* casterOp = zTranslatedNodes[shadowIndex].value;
            RenderNode* caster = casterOp->mDisplayList;
            const float casterZ = zTranslatedNodes[shadowIndex].key;
            // attempt to render the shadow if the caster about to be drawn is its caster,
            // OR if its caster's Z value is similar to the previous potential caster
            if (shadowIndex == drawIndex || casterZ - lastCasterZ < SHADOW_DELTA) {
                caster->issueDrawShadowOperation(casterOp->mTransformFromParent, handler);

                lastCasterZ = casterZ; // must do this even if current caster not casting a shadow
                shadowIndex++;
                continue;
            }
        }

        // only the actual child DL draw needs to be in save/restore,
        // since it modifies the renderer's matrix
        int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag);

        DrawDisplayListOp* childOp = zTranslatedNodes[drawIndex].value;
        RenderNode* child = childOp->mDisplayList;

        renderer.concatMatrix(childOp->mTransformFromParent);
        childOp->mSkipInOrderDraw = false; // this is horrible, I'm so sorry everyone
        handler(childOp, renderer.getSaveCount() - 1, properties().getClipToBounds());
        childOp->mSkipInOrderDraw = true;

        renderer.restoreToCount(restoreTo);
        drawIndex++;
    }
}

template <class T>
void RenderNode::issueOperationsOfProjectedChildren(OpenGLRenderer& renderer, T& handler) {
    DISPLAY_LIST_LOGD("%*s%d projected children:", (handler.level() + 1) * 2, "", mProjectedNodes.size());
    const SkPath* projectionReceiverOutline = properties().getOutline().getPath();
    bool maskProjecteesWithPath = projectionReceiverOutline != NULL
            && !projectionReceiverOutline->isRect(NULL);
    int restoreTo = renderer.getSaveCount();

    // If the projection reciever has an outline, we mask each of the projected rendernodes to it
    // Either with clipRect, or special saveLayer masking
    LinearAllocator& alloc = handler.allocator();
    if (projectionReceiverOutline != NULL) {
        const SkRect& outlineBounds = projectionReceiverOutline->getBounds();
        if (projectionReceiverOutline->isRect(NULL)) {
            // mask to the rect outline simply with clipRect
            handler(new (alloc) SaveOp(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag),
                    PROPERTY_SAVECOUNT, properties().getClipToBounds());
            ClipRectOp* clipOp = new (alloc) ClipRectOp(
                    outlineBounds.left(), outlineBounds.top(),
                    outlineBounds.right(), outlineBounds.bottom(), SkRegion::kIntersect_Op);
            handler(clipOp, PROPERTY_SAVECOUNT, properties().getClipToBounds());
        } else {
            // wrap the projected RenderNodes with a SaveLayer that will mask to the outline
            SaveLayerOp* op = new (alloc) SaveLayerOp(
                    outlineBounds.left(), outlineBounds.top(),
                    outlineBounds.right(), outlineBounds.bottom(),
                    255, SkCanvas::kARGB_ClipLayer_SaveFlag);
            op->setMask(projectionReceiverOutline);
            handler(op, PROPERTY_SAVECOUNT, properties().getClipToBounds());

            /* TODO: add optimizations here to take advantage of placement/size of projected
             * children (which may shrink saveLayer area significantly). This is dependent on
             * passing actual drawing/dirtying bounds of projected content down to native.
             */
        }
    }

    // draw projected nodes
    for (size_t i = 0; i < mProjectedNodes.size(); i++) {
        DrawDisplayListOp* childOp = mProjectedNodes[i];

        // matrix save, concat, and restore can be done safely without allocating operations
        int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag);
        renderer.concatMatrix(childOp->mTransformFromCompositingAncestor);
        childOp->mSkipInOrderDraw = false; // this is horrible, I'm so sorry everyone
        handler(childOp, renderer.getSaveCount() - 1, properties().getClipToBounds());
        childOp->mSkipInOrderDraw = true;
        renderer.restoreToCount(restoreTo);
    }

    if (projectionReceiverOutline != NULL) {
        handler(new (alloc) RestoreToCountOp(restoreTo),
                PROPERTY_SAVECOUNT, properties().getClipToBounds());
    }
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
void RenderNode::issueOperations(OpenGLRenderer& renderer, T& handler) {
    const int level = handler.level();
    if (mDisplayListData->isEmpty() || properties().getAlpha() <= 0) {
        DISPLAY_LIST_LOGD("%*sEmpty display list (%p, %s)", level * 2, "", this, getName());
        return;
    }

    handler.startMark(getName());

#if DEBUG_DISPLAY_LIST
    const Rect& clipRect = renderer.getLocalClipBounds();
    DISPLAY_LIST_LOGD("%*sStart display list (%p, %s), localClipBounds: %.0f, %.0f, %.0f, %.0f",
            level * 2, "", this, getName(),
            clipRect.left, clipRect.top, clipRect.right, clipRect.bottom);
#endif

    LinearAllocator& alloc = handler.allocator();
    int restoreTo = renderer.getSaveCount();
    handler(new (alloc) SaveOp(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag),
            PROPERTY_SAVECOUNT, properties().getClipToBounds());

    DISPLAY_LIST_LOGD("%*sSave %d %d", (level + 1) * 2, "",
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag, restoreTo);

    setViewProperties<T>(renderer, handler);

    bool quickRejected = properties().getClipToBounds()
            && renderer.quickRejectConservative(0, 0, properties().getWidth(), properties().getHeight());
    if (!quickRejected) {
        if (mProperties.getOutline().willClip()) {
            renderer.setClippingOutline(alloc, &(mProperties.getOutline()));
        }

        Vector<ZDrawDisplayListOpPair> zTranslatedNodes;
        buildZSortedChildList(zTranslatedNodes);

        // for 3d root, draw children with negative z values
        issueOperationsOf3dChildren(zTranslatedNodes, kNegativeZChildren, renderer, handler);

        DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
        const int saveCountOffset = renderer.getSaveCount() - 1;
        const int projectionReceiveIndex = mDisplayListData->projectionReceiveIndex;
        for (unsigned int i = 0; i < mDisplayListData->displayListOps.size(); i++) {
            DisplayListOp *op = mDisplayListData->displayListOps[i];

#if DEBUG_DISPLAY_LIST
            op->output(level + 1);
#endif
            logBuffer.writeCommand(level, op->name());
            handler(op, saveCountOffset, properties().getClipToBounds());

            if (CC_UNLIKELY(i == projectionReceiveIndex && mProjectedNodes.size() > 0)) {
                issueOperationsOfProjectedChildren(renderer, handler);
            }
        }

        // for 3d root, draw children with positive z values
        issueOperationsOf3dChildren(zTranslatedNodes, kPositiveZChildren, renderer, handler);
    }

    DISPLAY_LIST_LOGD("%*sRestoreToCount %d", (level + 1) * 2, "", restoreTo);
    handler(new (alloc) RestoreToCountOp(restoreTo),
            PROPERTY_SAVECOUNT, properties().getClipToBounds());
    renderer.setOverrideLayerAlpha(1.0f);

    DISPLAY_LIST_LOGD("%*sDone (%p, %s)", level * 2, "", this, getName());
    handler.endMark();
}

} /* namespace uirenderer */
} /* namespace android */
