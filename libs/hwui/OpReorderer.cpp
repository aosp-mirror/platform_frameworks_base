/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "OpReorderer.h"

#include "LayerUpdateQueue.h"
#include "RenderNode.h"
#include "renderstate/OffscreenBufferPool.h"
#include "utils/FatVector.h"
#include "utils/PaintUtils.h"
#include "utils/TraceUtils.h"

#include <SkCanvas.h>
#include <SkPathOps.h>
#include <utils/TypeHelpers.h>

namespace android {
namespace uirenderer {

class BatchBase {

public:
    BatchBase(batchid_t batchId, BakedOpState* op, bool merging)
            : mBatchId(batchId)
            , mMerging(merging) {
        mBounds = op->computedState.clippedBounds;
        mOps.push_back(op);
    }

    bool intersects(const Rect& rect) const {
        if (!rect.intersects(mBounds)) return false;

        for (const BakedOpState* op : mOps) {
            if (rect.intersects(op->computedState.clippedBounds)) {
                return true;
            }
        }
        return false;
    }

    batchid_t getBatchId() const { return mBatchId; }
    bool isMerging() const { return mMerging; }

    const std::vector<BakedOpState*>& getOps() const { return mOps; }

    void dump() const {
        ALOGD("    Batch %p, id %d, merging %d, count %d, bounds " RECT_STRING,
                this, mBatchId, mMerging, mOps.size(), RECT_ARGS(mBounds));
    }
protected:
    batchid_t mBatchId;
    Rect mBounds;
    std::vector<BakedOpState*> mOps;
    bool mMerging;
};

class OpBatch : public BatchBase {
public:
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    OpBatch(batchid_t batchId, BakedOpState* op)
            : BatchBase(batchId, op, false) {
    }

    void batchOp(BakedOpState* op) {
        mBounds.unionWith(op->computedState.clippedBounds);
        mOps.push_back(op);
    }
};

class MergingOpBatch : public BatchBase {
public:
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    MergingOpBatch(batchid_t batchId, BakedOpState* op)
            : BatchBase(batchId, op, true) {
    }

    /*
     * Helper for determining if a new op can merge with a MergingDrawBatch based on their bounds
     * and clip side flags. Positive bounds delta means new bounds fit in old.
     */
    static inline bool checkSide(const int currentFlags, const int newFlags, const int side,
            float boundsDelta) {
        bool currentClipExists = currentFlags & side;
        bool newClipExists = newFlags & side;

        // if current is clipped, we must be able to fit new bounds in current
        if (boundsDelta > 0 && currentClipExists) return false;

        // if new is clipped, we must be able to fit current bounds in new
        if (boundsDelta < 0 && newClipExists) return false;

        return true;
    }

    static bool paintIsDefault(const SkPaint& paint) {
        return paint.getAlpha() == 255
                && paint.getColorFilter() == nullptr
                && paint.getShader() == nullptr;
    }

    static bool paintsAreEquivalent(const SkPaint& a, const SkPaint& b) {
        return a.getAlpha() == b.getAlpha()
                && a.getColorFilter() == b.getColorFilter()
                && a.getShader() == b.getShader();
    }

    /*
     * Checks if a (mergeable) op can be merged into this batch
     *
     * If true, the op's multiDraw must be guaranteed to handle both ops simultaneously, so it is
     * important to consider all paint attributes used in the draw calls in deciding both a) if an
     * op tries to merge at all, and b) if the op can merge with another set of ops
     *
     * False positives can lead to information from the paints of subsequent merged operations being
     * dropped, so we make simplifying qualifications on the ops that can merge, per op type.
     */
    bool canMergeWith(BakedOpState* op) const {
        bool isTextBatch = getBatchId() == OpBatchType::Text
                || getBatchId() == OpBatchType::ColorText;

        // Overlapping other operations is only allowed for text without shadow. For other ops,
        // multiDraw isn't guaranteed to overdraw correctly
        if (!isTextBatch || PaintUtils::hasTextShadow(op->op->paint)) {
            if (intersects(op->computedState.clippedBounds)) return false;
        }

        const BakedOpState* lhs = op;
        const BakedOpState* rhs = mOps[0];

        if (!MathUtils::areEqual(lhs->alpha, rhs->alpha)) return false;

        // Identical round rect clip state means both ops will clip in the same way, or not at all.
        // As the state objects are const, we can compare their pointers to determine mergeability
        if (lhs->roundRectClipState != rhs->roundRectClipState) return false;
        if (lhs->projectionPathMask != rhs->projectionPathMask) return false;

        /* Clipping compatibility check
         *
         * Exploits the fact that if a op or batch is clipped on a side, its bounds will equal its
         * clip for that side.
         */
        const int currentFlags = mClipSideFlags;
        const int newFlags = op->computedState.clipSideFlags;
        if (currentFlags != OpClipSideFlags::None || newFlags != OpClipSideFlags::None) {
            const Rect& opBounds = op->computedState.clippedBounds;
            float boundsDelta = mBounds.left - opBounds.left;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Left, boundsDelta)) return false;
            boundsDelta = mBounds.top - opBounds.top;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Top, boundsDelta)) return false;

            // right and bottom delta calculation reversed to account for direction
            boundsDelta = opBounds.right - mBounds.right;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Right, boundsDelta)) return false;
            boundsDelta = opBounds.bottom - mBounds.bottom;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Bottom, boundsDelta)) return false;
        }

        const SkPaint* newPaint = op->op->paint;
        const SkPaint* oldPaint = mOps[0]->op->paint;

        if (newPaint == oldPaint) {
            // if paints are equal, then modifiers + paint attribs don't need to be compared
            return true;
        } else if (newPaint && !oldPaint) {
            return paintIsDefault(*newPaint);
        } else if (!newPaint && oldPaint) {
            return paintIsDefault(*oldPaint);
        }
        return paintsAreEquivalent(*newPaint, *oldPaint);
    }

    void mergeOp(BakedOpState* op) {
        mBounds.unionWith(op->computedState.clippedBounds);
        mOps.push_back(op);

        const int newClipSideFlags = op->computedState.clipSideFlags;
        mClipSideFlags |= newClipSideFlags;

        const Rect& opClip = op->computedState.clipRect;
        if (newClipSideFlags & OpClipSideFlags::Left) mClipRect.left = opClip.left;
        if (newClipSideFlags & OpClipSideFlags::Top) mClipRect.top = opClip.top;
        if (newClipSideFlags & OpClipSideFlags::Right) mClipRect.right = opClip.right;
        if (newClipSideFlags & OpClipSideFlags::Bottom) mClipRect.bottom = opClip.bottom;
    }

    bool getClipSideFlags() const { return mClipSideFlags; }
    const Rect& getClipRect() const { return mClipRect; }

private:
    int mClipSideFlags = 0;
    Rect mClipRect;
};

OpReorderer::LayerReorderer::LayerReorderer(uint32_t width, uint32_t height,
        const Rect& repaintRect, const BeginLayerOp* beginLayerOp, RenderNode* renderNode)
        : width(width)
        , height(height)
        , repaintRect(repaintRect)
        , offscreenBuffer(renderNode ? renderNode->getLayer() : nullptr)
        , beginLayerOp(beginLayerOp)
        , renderNode(renderNode) {}

// iterate back toward target to see if anything drawn since should overlap the new op
// if no target, merging ops still iterate to find similar batch to insert after
void OpReorderer::LayerReorderer::locateInsertIndex(int batchId, const Rect& clippedBounds,
        BatchBase** targetBatch, size_t* insertBatchIndex) const {
    for (int i = mBatches.size() - 1; i >= 0; i--) {
        BatchBase* overBatch = mBatches[i];

        if (overBatch == *targetBatch) break;

        // TODO: also consider shader shared between batch types
        if (batchId == overBatch->getBatchId()) {
            *insertBatchIndex = i + 1;
            if (!*targetBatch) break; // found insert position, quit
        }

        if (overBatch->intersects(clippedBounds)) {
            // NOTE: it may be possible to optimize for special cases where two operations
            // of the same batch/paint could swap order, such as with a non-mergeable
            // (clipped) and a mergeable text operation
            *targetBatch = nullptr;
            break;
        }
    }
}

void OpReorderer::LayerReorderer::deferUnmergeableOp(LinearAllocator& allocator,
        BakedOpState* op, batchid_t batchId) {
    OpBatch* targetBatch = mBatchLookup[batchId];

    size_t insertBatchIndex = mBatches.size();
    if (targetBatch) {
        locateInsertIndex(batchId, op->computedState.clippedBounds,
                (BatchBase**)(&targetBatch), &insertBatchIndex);
    }

    if (targetBatch) {
        targetBatch->batchOp(op);
    } else  {
        // new non-merging batch
        targetBatch = new (allocator) OpBatch(batchId, op);
        mBatchLookup[batchId] = targetBatch;
        mBatches.insert(mBatches.begin() + insertBatchIndex, targetBatch);
    }
}

// insertion point of a new batch, will hopefully be immediately after similar batch
// (generally, should be similar shader)
void OpReorderer::LayerReorderer::deferMergeableOp(LinearAllocator& allocator,
        BakedOpState* op, batchid_t batchId, mergeid_t mergeId) {
    MergingOpBatch* targetBatch = nullptr;

    // Try to merge with any existing batch with same mergeId
    auto getResult = mMergingBatchLookup[batchId].find(mergeId);
    if (getResult != mMergingBatchLookup[batchId].end()) {
        targetBatch = getResult->second;
        if (!targetBatch->canMergeWith(op)) {
            targetBatch = nullptr;
        }
    }

    size_t insertBatchIndex = mBatches.size();
    locateInsertIndex(batchId, op->computedState.clippedBounds,
            (BatchBase**)(&targetBatch), &insertBatchIndex);

    if (targetBatch) {
        targetBatch->mergeOp(op);
    } else  {
        // new merging batch
        targetBatch = new (allocator) MergingOpBatch(batchId, op);
        mMergingBatchLookup[batchId].insert(std::make_pair(mergeId, targetBatch));

        mBatches.insert(mBatches.begin() + insertBatchIndex, targetBatch);
    }
}

void OpReorderer::LayerReorderer::replayBakedOpsImpl(void* arg,
        BakedOpReceiver* unmergedReceivers, MergedOpReceiver* mergedReceivers) const {
    ATRACE_NAME("flush drawing commands");
    for (const BatchBase* batch : mBatches) {
        size_t size = batch->getOps().size();
        if (size > 1 && batch->isMerging()) {
            int opId = batch->getOps()[0]->op->opId;
            const MergingOpBatch* mergingBatch = static_cast<const MergingOpBatch*>(batch);
            MergedBakedOpList data = {
                    batch->getOps().data(),
                    size,
                    mergingBatch->getClipSideFlags(),
                    mergingBatch->getClipRect()
            };
            if (data.clipSideFlags) {
                // if right or bottom sides aren't used to clip, init them to viewport bounds
                // in the clip rect, so it can be used to scissor
                if (!(data.clipSideFlags & OpClipSideFlags::Right)) data.clip.right = width;
                if (!(data.clipSideFlags & OpClipSideFlags::Bottom)) data.clip.bottom = height;
            }
            mergedReceivers[opId](arg, data);
        } else {
            for (const BakedOpState* op : batch->getOps()) {
                unmergedReceivers[op->op->opId](arg, *op);
            }
        }
    }
}

void OpReorderer::LayerReorderer::dump() const {
    ALOGD("LayerReorderer %p, %ux%u buffer %p, blo %p, rn %p",
            this, width, height, offscreenBuffer, beginLayerOp, renderNode);
    for (const BatchBase* batch : mBatches) {
        batch->dump();
    }
}

OpReorderer::OpReorderer(const LayerUpdateQueue& layers, const SkRect& clip,
        uint32_t viewportWidth, uint32_t viewportHeight,
        const std::vector< sp<RenderNode> >& nodes, const Vector3& lightCenter)
        : mCanvasState(*this) {
    ATRACE_NAME("prepare drawing commands");

    mLayerReorderers.reserve(layers.entries().size());
    mLayerStack.reserve(layers.entries().size());

    // Prepare to defer Fbo0
    mLayerReorderers.emplace_back(viewportWidth, viewportHeight, Rect(clip));
    mLayerStack.push_back(0);
    mCanvasState.initializeSaveStack(viewportWidth, viewportHeight,
            clip.fLeft, clip.fTop, clip.fRight, clip.fBottom,
            lightCenter);

    // Render all layers to be updated, in order. Defer in reverse order, so that they'll be
    // updated in the order they're passed in (mLayerReorderers are issued to Renderer in reverse)
    for (int i = layers.entries().size() - 1; i >= 0; i--) {
        RenderNode* layerNode = layers.entries()[i].renderNode;
        const Rect& layerDamage = layers.entries()[i].damage;
        layerNode->computeOrdering();

        // map current light center into RenderNode's coordinate space
        Vector3 lightCenter = mCanvasState.currentSnapshot()->getRelativeLightCenter();
        layerNode->getLayer()->inverseTransformInWindow.mapPoint3d(lightCenter);

        saveForLayer(layerNode->getWidth(), layerNode->getHeight(), 0, 0,
                layerDamage, lightCenter, nullptr, layerNode);

        if (layerNode->getDisplayList()) {
            deferNodeOps(*layerNode);
        }
        restoreForLayer();
    }

    // Defer Fbo0
    for (const sp<RenderNode>& node : nodes) {
        if (node->nothingToDraw()) continue;
        node->computeOrdering();

        int count = mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);
        deferNodePropsAndOps(*node);
        mCanvasState.restoreToCount(count);
    }
}

void OpReorderer::onViewportInitialized() {}

void OpReorderer::onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {}

void OpReorderer::deferNodePropsAndOps(RenderNode& node) {
    const RenderProperties& properties = node.properties();
    const Outline& outline = properties.getOutline();
    if (properties.getAlpha() <= 0
            || (outline.getShouldClip() && outline.isEmpty())
            || properties.getScaleX() == 0
            || properties.getScaleY() == 0) {
        return; // rejected
    }

    if (properties.getLeft() != 0 || properties.getTop() != 0) {
        mCanvasState.translate(properties.getLeft(), properties.getTop());
    }
    if (properties.getStaticMatrix()) {
        mCanvasState.concatMatrix(*properties.getStaticMatrix());
    } else if (properties.getAnimationMatrix()) {
        mCanvasState.concatMatrix(*properties.getAnimationMatrix());
    }
    if (properties.hasTransformMatrix()) {
        if (properties.isTransformTranslateOnly()) {
            mCanvasState.translate(properties.getTranslationX(), properties.getTranslationY());
        } else {
            mCanvasState.concatMatrix(*properties.getTransformMatrix());
        }
    }

    const int width = properties.getWidth();
    const int height = properties.getHeight();

    Rect saveLayerBounds; // will be set to non-empty if saveLayer needed
    const bool isLayer = properties.effectiveLayerType() != LayerType::None;
    int clipFlags = properties.getClippingFlags();
    if (properties.getAlpha() < 1) {
        if (isLayer) {
            clipFlags &= ~CLIP_TO_BOUNDS; // bounds clipping done by layer
        }
        if (CC_LIKELY(isLayer || !properties.getHasOverlappingRendering())) {
            // simply scale rendering content's alpha
            mCanvasState.scaleAlpha(properties.getAlpha());
        } else {
            // schedule saveLayer by initializing saveLayerBounds
            saveLayerBounds.set(0, 0, width, height);
            if (clipFlags) {
                properties.getClippingRectForFlags(clipFlags, &saveLayerBounds);
                clipFlags = 0; // all clipping done by savelayer
            }
        }

        if (CC_UNLIKELY(ATRACE_ENABLED() && properties.promotedToLayer())) {
            // pretend alpha always causes savelayer to warn about
            // performance problem affecting old versions
            ATRACE_FORMAT("%s alpha caused saveLayer %dx%d", node.getName(), width, height);
        }
    }
    if (clipFlags) {
        Rect clipRect;
        properties.getClippingRectForFlags(clipFlags, &clipRect);
        mCanvasState.clipRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom,
                SkRegion::kIntersect_Op);
    }

    if (properties.getRevealClip().willClip()) {
        Rect bounds;
        properties.getRevealClip().getBounds(&bounds);
        mCanvasState.setClippingRoundRect(mAllocator,
                bounds, properties.getRevealClip().getRadius());
    } else if (properties.getOutline().willClip()) {
        mCanvasState.setClippingOutline(mAllocator, &(properties.getOutline()));
    }

    if (!mCanvasState.quickRejectConservative(0, 0, width, height)) {
        // not rejected, so defer render as either Layer, or direct (possibly wrapped in saveLayer)
        if (node.getLayer()) {
            // HW layer
            LayerOp* drawLayerOp = new (mAllocator) LayerOp(node);
            BakedOpState* bakedOpState = tryBakeOpState(*drawLayerOp);
            if (bakedOpState) {
                // Node's layer already deferred, schedule it to render into parent layer
                currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Bitmap);
            }
        } else if (CC_UNLIKELY(!saveLayerBounds.isEmpty())) {
            // draw DisplayList contents within temporary, since persisted layer could not be used.
            // (temp layers are clipped to viewport, since they don't persist offscreen content)
            SkPaint saveLayerPaint;
            saveLayerPaint.setAlpha(properties.getAlpha());
            onBeginLayerOp(*new (mAllocator) BeginLayerOp(
                    saveLayerBounds,
                    Matrix4::identity(),
                    saveLayerBounds,
                    &saveLayerPaint));
            deferNodeOps(node);
            onEndLayerOp(*new (mAllocator) EndLayerOp());
        } else {
            deferNodeOps(node);
        }
    }
}

typedef key_value_pair_t<float, const RenderNodeOp*> ZRenderNodeOpPair;

template <typename V>
static void buildZSortedChildList(V* zTranslatedNodes,
        const DisplayList& displayList, const DisplayList::Chunk& chunk) {
    if (chunk.beginChildIndex == chunk.endChildIndex) return;

    for (size_t i = chunk.beginChildIndex; i < chunk.endChildIndex; i++) {
        RenderNodeOp* childOp = displayList.getChildren()[i];
        RenderNode* child = childOp->renderNode;
        float childZ = child->properties().getZ();

        if (!MathUtils::isZero(childZ) && chunk.reorderChildren) {
            zTranslatedNodes->push_back(ZRenderNodeOpPair(childZ, childOp));
            childOp->skipInOrderDraw = true;
        } else if (!child->properties().getProjectBackwards()) {
            // regular, in order drawing DisplayList
            childOp->skipInOrderDraw = false;
        }
    }

    // Z sort any 3d children (stable-ness makes z compare fall back to standard drawing order)
    std::stable_sort(zTranslatedNodes->begin(), zTranslatedNodes->end());
}

template <typename V>
static size_t findNonNegativeIndex(const V& zTranslatedNodes) {
    for (size_t i = 0; i < zTranslatedNodes.size(); i++) {
        if (zTranslatedNodes[i].key >= 0.0f) return i;
    }
    return zTranslatedNodes.size();
}

template <typename V>
void OpReorderer::defer3dChildren(ChildrenSelectMode mode, const V& zTranslatedNodes) {
    const int size = zTranslatedNodes.size();
    if (size == 0
            || (mode == ChildrenSelectMode::Negative&& zTranslatedNodes[0].key > 0.0f)
            || (mode == ChildrenSelectMode::Positive && zTranslatedNodes[size - 1].key < 0.0f)) {
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
    if (mode == ChildrenSelectMode::Negative) {
        drawIndex = 0;
        endIndex = nonNegativeIndex;
        shadowIndex = endIndex; // draw no shadows
    } else {
        drawIndex = nonNegativeIndex;
        endIndex = size;
        shadowIndex = drawIndex; // potentially draw shadow for each pos Z child
    }

    float lastCasterZ = 0.0f;
    while (shadowIndex < endIndex || drawIndex < endIndex) {
        if (shadowIndex < endIndex) {
            const RenderNodeOp* casterNodeOp = zTranslatedNodes[shadowIndex].value;
            const float casterZ = zTranslatedNodes[shadowIndex].key;
            // attempt to render the shadow if the caster about to be drawn is its caster,
            // OR if its caster's Z value is similar to the previous potential caster
            if (shadowIndex == drawIndex || casterZ - lastCasterZ < 0.1f) {
                deferShadow(*casterNodeOp);

                lastCasterZ = casterZ; // must do this even if current caster not casting a shadow
                shadowIndex++;
                continue;
            }
        }

        const RenderNodeOp* childOp = zTranslatedNodes[drawIndex].value;
        deferRenderNodeOp(*childOp);
        drawIndex++;
    }
}

void OpReorderer::deferShadow(const RenderNodeOp& casterNodeOp) {
    auto& node = *casterNodeOp.renderNode;
    auto& properties = node.properties();

    if (properties.getAlpha() <= 0.0f
            || properties.getOutline().getAlpha() <= 0.0f
            || !properties.getOutline().getPath()
            || properties.getScaleX() == 0
            || properties.getScaleY() == 0) {
        // no shadow to draw
        return;
    }

    const SkPath* casterOutlinePath = properties.getOutline().getPath();
    const SkPath* revealClipPath = properties.getRevealClip().getPath();
    if (revealClipPath && revealClipPath->isEmpty()) return;

    float casterAlpha = properties.getAlpha() * properties.getOutline().getAlpha();

    // holds temporary SkPath to store the result of intersections
    SkPath* frameAllocatedPath = nullptr;
    const SkPath* casterPath = casterOutlinePath;

    // intersect the shadow-casting path with the reveal, if present
    if (revealClipPath) {
        frameAllocatedPath = createFrameAllocatedPath();

        Op(*casterPath, *revealClipPath, kIntersect_SkPathOp, frameAllocatedPath);
        casterPath = frameAllocatedPath;
    }

    // intersect the shadow-casting path with the clipBounds, if present
    if (properties.getClippingFlags() & CLIP_TO_CLIP_BOUNDS) {
        if (!frameAllocatedPath) {
            frameAllocatedPath = createFrameAllocatedPath();
        }
        Rect clipBounds;
        properties.getClippingRectForFlags(CLIP_TO_CLIP_BOUNDS, &clipBounds);
        SkPath clipBoundsPath;
        clipBoundsPath.addRect(clipBounds.left, clipBounds.top,
                clipBounds.right, clipBounds.bottom);

        Op(*casterPath, clipBoundsPath, kIntersect_SkPathOp, frameAllocatedPath);
        casterPath = frameAllocatedPath;
    }

    ShadowOp* shadowOp = new (mAllocator) ShadowOp(casterNodeOp, casterAlpha, casterPath,
            mCanvasState.getLocalClipBounds(),
            mCanvasState.currentSnapshot()->getRelativeLightCenter());
    BakedOpState* bakedOpState = BakedOpState::tryShadowOpConstruct(
            mAllocator, *mCanvasState.currentSnapshot(), shadowOp);
    if (CC_LIKELY(bakedOpState)) {
        currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Shadow);
    }
}

void OpReorderer::deferProjectedChildren(const RenderNode& renderNode) {
    const SkPath* projectionReceiverOutline = renderNode.properties().getOutline().getPath();
    int count = mCanvasState.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);

    // can't be null, since DL=null node rejection happens before deferNodePropsAndOps
    const DisplayList& displayList = *(renderNode.getDisplayList());

    const RecordedOp* op = (displayList.getOps()[displayList.projectionReceiveIndex]);
    const RenderNodeOp* backgroundOp = static_cast<const RenderNodeOp*>(op);
    const RenderProperties& backgroundProps = backgroundOp->renderNode->properties();

    // Transform renderer to match background we're projecting onto
    // (by offsetting canvas by translationX/Y of background rendernode, since only those are set)
    mCanvasState.translate(backgroundProps.getTranslationX(), backgroundProps.getTranslationY());

    // If the projection receiver has an outline, we mask projected content to it
    // (which we know, apriori, are all tessellated paths)
    mCanvasState.setProjectionPathMask(mAllocator, projectionReceiverOutline);

    // draw projected nodes
    for (size_t i = 0; i < renderNode.mProjectedNodes.size(); i++) {
        RenderNodeOp* childOp = renderNode.mProjectedNodes[i];

        int restoreTo = mCanvasState.save(SkCanvas::kMatrix_SaveFlag);
        mCanvasState.concatMatrix(childOp->transformFromCompositingAncestor);
        deferRenderNodeOp(*childOp);
        mCanvasState.restoreToCount(restoreTo);
    }

    mCanvasState.restoreToCount(count);
}

/**
 * Used to define a list of lambdas referencing private OpReorderer::onXXXXOp() methods.
 *
 * This allows opIds embedded in the RecordedOps to be used for dispatching to these lambdas.
 * E.g. a BitmapOp op then would be dispatched to OpReorderer::onBitmapOp(const BitmapOp&)
 */
#define OP_RECEIVER(Type) \
        [](OpReorderer& reorderer, const RecordedOp& op) { reorderer.on##Type(static_cast<const Type&>(op)); },
void OpReorderer::deferNodeOps(const RenderNode& renderNode) {
    typedef void (*OpDispatcher) (OpReorderer& reorderer, const RecordedOp& op);
    static OpDispatcher receivers[] = {
        MAP_OPS(OP_RECEIVER)
    };

    // can't be null, since DL=null node rejection happens before deferNodePropsAndOps
    const DisplayList& displayList = *(renderNode.getDisplayList());
    for (const DisplayList::Chunk& chunk : displayList.getChunks()) {
        FatVector<ZRenderNodeOpPair, 16> zTranslatedNodes;
        buildZSortedChildList(&zTranslatedNodes, displayList, chunk);

        defer3dChildren(ChildrenSelectMode::Negative, zTranslatedNodes);
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            const RecordedOp* op = displayList.getOps()[opIndex];
            receivers[op->opId](*this, *op);

            if (CC_UNLIKELY(!renderNode.mProjectedNodes.empty()
                    && displayList.projectionReceiveIndex >= 0
                    && static_cast<int>(opIndex) == displayList.projectionReceiveIndex)) {
                deferProjectedChildren(renderNode);
            }
        }
        defer3dChildren(ChildrenSelectMode::Positive, zTranslatedNodes);
    }
}

void OpReorderer::deferRenderNodeOp(const RenderNodeOp& op) {
    if (op.renderNode->nothingToDraw()) return;
    int count = mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);

    // apply state from RecordedOp
    mCanvasState.concatMatrix(op.localMatrix);
    mCanvasState.clipRect(op.localClipRect.left, op.localClipRect.top,
            op.localClipRect.right, op.localClipRect.bottom, SkRegion::kIntersect_Op);

    // then apply state from node properties, and defer ops
    deferNodePropsAndOps(*op.renderNode);

    mCanvasState.restoreToCount(count);
}

void OpReorderer::onRenderNodeOp(const RenderNodeOp& op) {
    if (!op.skipInOrderDraw) {
        deferRenderNodeOp(op);
    }
}

/**
 * Defers an unmergeable, strokeable op, accounting correctly
 * for paint's style on the bounds being computed.
 */
void OpReorderer::onStrokeableOp(const RecordedOp& op, batchid_t batchId,
        BakedOpState::StrokeBehavior strokeBehavior) {
    // Note: here we account for stroke when baking the op
    BakedOpState* bakedState = BakedOpState::tryStrokeableOpConstruct(
            mAllocator, *mCanvasState.currentSnapshot(), op, strokeBehavior);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, batchId);
}

/**
 * Returns batch id for tessellatable shapes, based on paint. Checks to see if path effect/AA will
 * be used, since they trigger significantly different rendering paths.
 *
 * Note: not used for lines/points, since they don't currently support path effects.
 */
static batchid_t tessBatchId(const RecordedOp& op) {
    const SkPaint& paint = *(op.paint);
    return paint.getPathEffect()
            ? OpBatchType::AlphaMaskTexture
            : (paint.isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices);
}

void OpReorderer::onArcOp(const ArcOp& op) {
    onStrokeableOp(op, tessBatchId(op));
}

void OpReorderer::onBitmapOp(const BitmapOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected

    // Don't merge non-simply transformed or neg scale ops, SET_TEXTURE doesn't handle rotation
    // Don't merge A8 bitmaps - the paint's color isn't compared by mergeId, or in
    // MergingDrawBatch::canMergeWith()
    if (bakedState->computedState.transform.isSimple()
            && bakedState->computedState.transform.positiveScale()
            && PaintUtils::getXfermodeDirect(op.paint) == SkXfermode::kSrcOver_Mode
            && op.bitmap->colorType() != kAlpha_8_SkColorType) {
        mergeid_t mergeId = (mergeid_t) op.bitmap->getGenerationID();
        // TODO: AssetAtlas in mergeId
        currentLayer().deferMergeableOp(mAllocator, bakedState, OpBatchType::Bitmap, mergeId);
    } else {
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
    }
}

void OpReorderer::onBitmapMeshOp(const BitmapMeshOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
}

void OpReorderer::onBitmapRectOp(const BitmapRectOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
}

void OpReorderer::onLinesOp(const LinesOp& op) {
    batchid_t batch = op.paint->isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices;
    onStrokeableOp(op, batch, BakedOpState::StrokeBehavior::Forced);
}

void OpReorderer::onOvalOp(const OvalOp& op) {
    onStrokeableOp(op, tessBatchId(op));
}

void OpReorderer::onPatchOp(const PatchOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected

    if (bakedState->computedState.transform.isPureTranslate()
            && PaintUtils::getXfermodeDirect(op.paint) == SkXfermode::kSrcOver_Mode) {
        mergeid_t mergeId = (mergeid_t) op.bitmap->getGenerationID();
        // TODO: AssetAtlas in mergeId

        // Only use the MergedPatch batchId when merged, so Bitmap+Patch don't try to merge together
        currentLayer().deferMergeableOp(mAllocator, bakedState, OpBatchType::MergedPatch, mergeId);
    } else {
        // Use Bitmap batchId since Bitmap+Patch use same shader
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
    }
}

void OpReorderer::onPathOp(const PathOp& op) {
    onStrokeableOp(op, OpBatchType::Bitmap);
}

void OpReorderer::onPointsOp(const PointsOp& op) {
    batchid_t batch = op.paint->isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices;
    onStrokeableOp(op, batch, BakedOpState::StrokeBehavior::Forced);
}

void OpReorderer::onRectOp(const RectOp& op) {
    onStrokeableOp(op, tessBatchId(op));
}

void OpReorderer::onRoundRectOp(const RoundRectOp& op) {
    onStrokeableOp(op, tessBatchId(op));
}

void OpReorderer::onSimpleRectsOp(const SimpleRectsOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Vertices);
}

void OpReorderer::onTextOp(const TextOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected

    // TODO: better handling of shader (since we won't care about color then)
    batchid_t batchId = op.paint->getColor() == SK_ColorBLACK
            ? OpBatchType::Text : OpBatchType::ColorText;

    if (bakedState->computedState.transform.isPureTranslate()
            && PaintUtils::getXfermodeDirect(op.paint) == SkXfermode::kSrcOver_Mode) {
        mergeid_t mergeId = reinterpret_cast<mergeid_t>(op.paint->getColor());
        currentLayer().deferMergeableOp(mAllocator, bakedState, batchId, mergeId);
    } else {
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, batchId);
    }
}

void OpReorderer::saveForLayer(uint32_t layerWidth, uint32_t layerHeight,
        float contentTranslateX, float contentTranslateY,
        const Rect& repaintRect,
        const Vector3& lightCenter,
        const BeginLayerOp* beginLayerOp, RenderNode* renderNode) {
    mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);
    mCanvasState.writableSnapshot()->initializeViewport(layerWidth, layerHeight);
    mCanvasState.writableSnapshot()->roundRectClipState = nullptr;
    mCanvasState.writableSnapshot()->setRelativeLightCenter(lightCenter);
    mCanvasState.writableSnapshot()->transform->loadTranslate(
            contentTranslateX, contentTranslateY, 0);
    mCanvasState.writableSnapshot()->setClip(
            repaintRect.left, repaintRect.top, repaintRect.right, repaintRect.bottom);

    // create a new layer repaint, and push its index on the stack
    mLayerStack.push_back(mLayerReorderers.size());
    mLayerReorderers.emplace_back(layerWidth, layerHeight, repaintRect, beginLayerOp, renderNode);
}

void OpReorderer::restoreForLayer() {
    // restore canvas, and pop finished layer off of the stack
    mCanvasState.restore();
    mLayerStack.pop_back();
}

// TODO: test rejection at defer time, where the bounds become empty
void OpReorderer::onBeginLayerOp(const BeginLayerOp& op) {
    uint32_t layerWidth = (uint32_t) op.unmappedBounds.getWidth();
    uint32_t layerHeight = (uint32_t) op.unmappedBounds.getHeight();

    auto previous = mCanvasState.currentSnapshot();
    Vector3 lightCenter = previous->getRelativeLightCenter();

    // Combine all transforms used to present saveLayer content:
    // parent content transform * canvas transform * bounds offset
    Matrix4 contentTransform(*previous->transform);
    contentTransform.multiply(op.localMatrix);
    contentTransform.translate(op.unmappedBounds.left, op.unmappedBounds.top);

    Matrix4 inverseContentTransform;
    inverseContentTransform.loadInverse(contentTransform);

    // map the light center into layer-relative space
    inverseContentTransform.mapPoint3d(lightCenter);

    // Clip bounds of temporary layer to parent's clip rect, so:
    Rect saveLayerBounds(layerWidth, layerHeight);
    //     1) transform Rect(width, height) into parent's space
    //        note: left/top offsets put in contentTransform above
    contentTransform.mapRect(saveLayerBounds);
    //     2) intersect with parent's clip
    saveLayerBounds.doIntersect(previous->getRenderTargetClip());
    //     3) and transform back
    inverseContentTransform.mapRect(saveLayerBounds);
    saveLayerBounds.doIntersect(Rect(layerWidth, layerHeight));
    saveLayerBounds.roundOut();

    // if bounds are reduced, will clip the layer's area by reducing required bounds...
    layerWidth = saveLayerBounds.getWidth();
    layerHeight = saveLayerBounds.getHeight();
    // ...and shifting drawing content to account for left/top side clipping
    float contentTranslateX = -saveLayerBounds.left;
    float contentTranslateY = -saveLayerBounds.top;

    saveForLayer(layerWidth, layerHeight,
            contentTranslateX, contentTranslateY,
            Rect(layerWidth, layerHeight),
            lightCenter,
            &op, nullptr);
}

void OpReorderer::onEndLayerOp(const EndLayerOp& /* ignored */) {
    const BeginLayerOp& beginLayerOp = *currentLayer().beginLayerOp;
    int finishedLayerIndex = mLayerStack.back();

    restoreForLayer();

    // record the draw operation into the previous layer's list of draw commands
    // uses state from the associated beginLayerOp, since it has all the state needed for drawing
    LayerOp* drawLayerOp = new (mAllocator) LayerOp(
            beginLayerOp.unmappedBounds,
            beginLayerOp.localMatrix,
            beginLayerOp.localClipRect,
            beginLayerOp.paint,
            &mLayerReorderers[finishedLayerIndex].offscreenBuffer);
    BakedOpState* bakedOpState = tryBakeOpState(*drawLayerOp);

    if (bakedOpState) {
        // Layer will be drawn into parent layer (which is now current, since we popped mLayerStack)
        currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Bitmap);
    } else {
        // Layer won't be drawn - delete its drawing batches to prevent it from doing any work
        mLayerReorderers[finishedLayerIndex].clear();
        return;
    }
}

void OpReorderer::onLayerOp(const LayerOp& op) {
    LOG_ALWAYS_FATAL("unsupported");
}

void OpReorderer::onShadowOp(const ShadowOp& op) {
    LOG_ALWAYS_FATAL("unsupported");
}

} // namespace uirenderer
} // namespace android
